import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import {
  AuthenticatedSessionResponse,
  CurrentSessionResponse,
  EnrollmentResponse,
  LoginRequest,
  LoginResponse,
  ProblemDetail,
} from "@confia/contracts/admin";
import { problem } from "../../src/test/contract-responses.ts";
import {
  DEFAULT_SCENARIO,
  createScenarioStore,
  isScenarioId,
  type Operation,
  type Scenario,
} from "./scenarios.ts";
import { base32Encode } from "../support/totp.ts";
import { parseCookieHeader, type ModelResponse } from "./session-model.ts";

/**
 * The simulated API of the admin web app (design.md D15): a Node server on localhost that answers the
 * access operations from the session model. It is a simulation, started by `pnpm mock:api` and by the
 * tests only; nothing here is served from the production build, and nothing presents it as a test of the
 * real stack. Every response with a body is checked against the generated contract before it is sent; one
 * that fails is never sent, and is kept in `/__control/violations` (SS118, I306).
 */
export const SCENARIO_HEADER = "x-confia-mock-scenario";
export const PAGE_ORIGIN = "http://localhost:5173";
const MAX_BODY_BYTES = 64 * 1024;
const LOCAL_HOSTS: ReadonlySet<string> = new Set(["localhost", "127.0.0.1", "[::1]"]);
const LOOPBACK: ReadonlySet<string> = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);

/** What a refused response leaves behind: where, with which status and which fields, never a value. */
export interface Violation {
  readonly operation: Operation;
  readonly status: number;
  readonly fields: readonly string[];
}

export interface MockServerOptions {
  readonly log?: (line: string) => void;
  /** Waits; the tests replace it so a 31 second answer is observed and not endured. */
  readonly sleep?: (ms: number) => Promise<void>;
}

export interface MockServer {
  listen(port: number): Promise<number>;
  close(): Promise<void>;
  address(): AddressInfo | string | null;
}

interface Schema {
  safeParse(input: unknown):
    | { readonly success: true }
    | { readonly success: false; readonly error: { readonly issues: readonly { readonly path: readonly PropertyKey[] }[] } };
}

const SUCCESS_SCHEMAS: Readonly<Record<Operation, Schema | undefined>> = {
  "log-in": LoginResponse,
  refresh: AuthenticatedSessionResponse,
  verify: AuthenticatedSessionResponse,
  current: CurrentSessionResponse,
  enroll: EnrollmentResponse,
  "sign-out": undefined,
};

/** The fields of a response that break the contract; empty when it meets it. The `instance` of an error is not checked (I306). */
function violationFields(operation: Operation, response: ModelResponse): string[] {
  const schema = response.status >= 400 ? ProblemDetail.omit({ instance: true }) : SUCCESS_SCHEMAS[operation];
  if (schema === undefined || response.body === undefined) return [];
  const parsed = schema.safeParse(response.body);
  if (parsed.success) return [];
  return [...new Set(parsed.error.issues.map((issue) => issue.path.map(String).join(".") || "(root)"))];
}

const failure = (code: Parameters<typeof problem>[0], status: number): ModelResponse => ({
  ...problem(code, status),
  headers: {},
  setCookies: [],
});

function write(res: ServerResponse, response: ModelResponse): void {
  const headers: Record<string, string | string[]> = { ...response.headers, "Cache-Control": "no-store" };
  if (response.contentType !== undefined) headers["Content-Type"] = response.contentType;
  if (response.setCookies.length > 0) headers["Set-Cookie"] = [...response.setCookies];
  res.writeHead(response.status, headers);
  res.end(response.body === undefined ? undefined : JSON.stringify(response.body));
}

function writeJson(res: ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
  res.end(JSON.stringify(body));
}

function readBody(req: IncomingMessage): Promise<unknown> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size <= MAX_BODY_BYTES) chunks.push(chunk);
    });
    req.on("end", () => {
      if (size === 0 || size > MAX_BODY_BYTES) { resolve(undefined); return; }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch {
        resolve(undefined);
      }
    });
    req.on("error", () => { resolve(undefined); });
  });
}

/** Only this machine may use the control: a local Host name, which also shuts out DNS rebinding, and a loopback peer. */
function isLocal(req: IncomingMessage): boolean {
  const host = (req.headers.host ?? "").replace(/:\d+$/, "");
  return LOCAL_HOSTS.has(host) && LOOPBACK.has(req.socket.remoteAddress ?? "");
}

const header = (req: IncomingMessage, name: string): string | undefined => {
  const value = req.headers[name];
  return Array.isArray(value) ? value[0] : value;
};

const bearerOf = (req: IncomingMessage): string | undefined => {
  const value = header(req, "authorization");
  return value?.startsWith("Bearer ") === true ? value.slice("Bearer ".length) : undefined;
};

export function createMockServer(options: MockServerOptions = {}): MockServer {
  const log = options.log ?? ((line: string) => { console.log(line); });
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const store = createScenarioStore();
  const violations: Violation[] = [];

  /** Checks a response against the contract and sends it, or sends a 500 and keeps the violation. */
  function deliver(res: ServerResponse, scenario: Scenario, operation: Operation, built: ModelResponse): void {
    const response = scenario.tamper === undefined ? built : scenario.tamper(operation, built);
    const fields = violationFields(operation, response);
    if (fields.length === 0) { write(res, response); return; }
    violations.push({ operation, status: response.status, fields });
    write(res, failure("internal-error", 500));
  }

  async function api(req: IncomingMessage, res: ServerResponse, path: string, scenario: Scenario): Promise<void> {
    const method = req.method ?? "GET";
    const cookieRoute = {
      origin: header(req, "origin"),
      cookies: parseCookieHeader(header(req, "cookie")),
      csrfHeader: header(req, "x-csrf-token"),
    };
    scenario.calls.push({ method, path });
    if (method === "POST" && path === "/api/v1/auth/sessions") {
      const parsed = LoginRequest.safeParse(await readBody(req));
      if (!parsed.success) {
        const field = parsed.error.issues[0]?.path.map(String).join(".") ?? "";
        const invalid = problem("validation-failed", 400, { errors: [{ field: field === "" ? "body" : field, reason: "invalid" }] });
        deliver(res, scenario, "log-in", { ...invalid, headers: {}, setCookies: [] }); return;
      }
      deliver(res, scenario, "log-in", await scenario.signIn(sleep)); return;
    }
    if (method === "POST" && path === "/api/v1/auth/sessions/refresh") {
      deliver(res, scenario, "refresh", scenario.model.refresh(cookieRoute)); return;
    }
    if (method === "DELETE" && path === "/api/v1/auth/sessions/current") {
      deliver(res, scenario, "sign-out", scenario.model.signOut(cookieRoute)); return;
    }
    if (method === "GET" && path === "/api/v1/auth/sessions/current") {
      deliver(res, scenario, "current", scenario.model.currentSession(bearerOf(req))); return;
    }
    if (method === "POST" && path === "/api/v1/auth/mfa/enrollments") {
      deliver(res, scenario, "enroll", scenario.model.enroll(bearerOf(req))); return;
    }
    if (method === "POST" && path === "/api/v1/auth/mfa/verifications") {
      deliver(res, scenario, "verify", scenario.model.verify(bearerOf(req), await readBody(req))); return;
    }
    write(res, failure("resource-not-found", 404));
  }

  /**
   * `/__control/*`: for tests and for a person at the keyboard. The proxy of the page forwards only `/api`, so
   * the page cannot reach it. The seeded session lives here: a fictitious account, with cookies that have the
   * attributes of every other session of the simulator, so the signed in page can be seen without a form.
   */
  async function control(req: IncomingMessage, res: ServerResponse, url: URL, scenarioId: string): Promise<void> {
    const method = req.method ?? "GET";
    const path = url.pathname;
    if (!isLocal(req)) { write(res, failure("forbidden", 403)); return; }
    if (method === "GET" && path === "/__control/health") { writeJson(res, 200, { status: "ok" }); return; }
    if (method === "GET" && path === "/__control/violations") { writeJson(res, 200, violations); return; }
    if ((method === "GET" || method === "POST") && path === "/__control/seed-session") {
      const target = url.searchParams.get("scenario") ?? scenarioId;
      if (!isScenarioId(target)) { write(res, failure("validation-failed", 400)); return; }
      const seeded = store.scenario(target).model.openSession();
      res.writeHead(303, { Location: `${PAGE_ORIGIN}/`, "Set-Cookie": [...seeded.setCookies], "Cache-Control": "no-store" });
      return void res.end();
    }
    // The key and the recovery codes of the fictitious account of a scenario, for a journey to compute a code. Only
    // the data of that account leaves here; the Host and peer check above is the one every control answer passes.
    const accountMatch = /^\/__control\/account\/([^/]+)$/.exec(path);
    if (method === "GET" && accountMatch?.[1] !== undefined && isScenarioId(accountMatch[1])) {
      const { model } = store.scenario(accountMatch[1]);
      writeJson(res, 200, { secret: base32Encode(model.testSecret()), recoveryCodes: model.recoveryCodes() });
      return;
    }
    const scenarioMatch = /^\/__control\/(scenarios|requests)\/([^/]+)$/.exec(path);
    if (scenarioMatch !== null && scenarioMatch[2] !== undefined && isScenarioId(scenarioMatch[2])) {
      const id = scenarioMatch[2];
      if (method === "GET" && scenarioMatch[1] === "requests") { writeJson(res, 200, store.scenario(id).calls); return; }
      if (method === "POST" && scenarioMatch[1] === "scenarios") {
        const body = (await readBody(req)) as { preset?: unknown } | undefined;
        const preset = typeof body?.preset === "string" ? body.preset : "";
        if (store.apply(id, preset)) writeJson(res, 200, { preset });
        else write(res, failure("validation-failed", 400));
        return;
      }
    }
    write(res, failure("resource-not-found", 404));
  }

  const server: Server = createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    res.on("finish", () => { log(`${req.method ?? "GET"} ${url.pathname} ${String(res.statusCode)}`); });
    const named = header(req, SCENARIO_HEADER) ?? DEFAULT_SCENARIO;
    if (!isScenarioId(named)) { write(res, failure("validation-failed", 400)); return; }
    const handled = url.pathname.startsWith("/__control/")
      ? control(req, res, url, named)
      : api(req, res, url.pathname, store.scenario(named));
    handled.catch(() => {
      // A fault in the simulator is reported as a 500 with no detail, like any other server error.
      if (!res.headersSent) write(res, failure("internal-error", 500));
    });
  });

  return {
    listen: (port) =>
      new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(port, "localhost", () => { resolve((server.address() as AddressInfo).port); });
      }),
    close: () =>
      new Promise((resolve) => {
        server.close(() => { resolve(); });
        server.closeAllConnections();
      }),
    address: () => server.address(),
  };
}

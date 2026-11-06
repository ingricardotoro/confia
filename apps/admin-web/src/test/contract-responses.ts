import { randomBytes } from "node:crypto";
import {
  AuthenticatedSessionResponse,
  CurrentSessionResponse,
  EnrollmentResponse,
  ProblemDetail,
  SecondFactorPendingResponse,
  type AuthenticatedSessionResponseOutput,
  type CurrentSessionResponseOutput,
  type EnrollmentResponseOutput,
  type SecondFactorPendingResponseOutput,
} from "@confia/contracts/admin";
import type { ProblemCode } from "../shared/api/problem.ts";

/**
 * The responses the tests, and later the simulated API, answer with (design.md D15). One source: each
 * builder checks its body against the generated contract before returning it, so a contract that moves
 * breaks the test that uses it. Tokens, secrets and codes are made at run time and never written here.
 */
export interface ContractResponse {
  readonly status: number;
  readonly body: unknown;
  /** Absent when there is no body. */
  readonly contentType: string | undefined;
}

/** What the contract refused. It names the response and the fields and never a value (CLAUDE.md rule 11). */
export class ContractViolation extends Error {
  constructor(responseName: string, fields: readonly string[]) {
    super(`${responseName} breaks the contract at: ${fields.length === 0 ? "(root)" : fields.join(", ")}`);
    this.name = "ContractViolation";
  }
}

interface Schema<T> {
  safeParse(input: unknown):
    | { readonly success: true; readonly data: T }
    | { readonly success: false; readonly error: { readonly issues: readonly { readonly path: readonly PropertyKey[] }[] } };
}

function checked<T>(name: string, schema: Schema<T>, body: unknown): T {
  const parsed = schema.safeParse(body);
  if (parsed.success) return parsed.data;
  throw new ContractViolation(
    name,
    parsed.error.issues.map((issue) => issue.path.map(String).join(".")).filter((path) => path !== ""),
  );
}

const JSON_TYPE = "application/json";
const MINUTE_MS = 60_000;
const BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export interface BuildOptions<T> {
  /** The clock the expiry times are derived from. */
  readonly now?: Date;
  /** Fields to replace. A value that breaks the contract is refused, which is how a test proves it. */
  readonly override?: Partial<T>;
}

function opaque(): string {
  return randomBytes(24).toString("base64url");
}

function after(now: Date, minutes: number): string {
  return new Date(now.getTime() + minutes * MINUTE_MS).toISOString();
}

export function authenticatedSession(options: BuildOptions<AuthenticatedSessionResponseOutput> = {}): ContractResponse {
  const now = options.now ?? new Date();
  const body = checked("AuthenticatedSessionResponse", AuthenticatedSessionResponse, {
    status: "authenticated",
    accessToken: opaque(),
    accessTokenExpiresAt: after(now, 15),
    idleExpiresAt: after(now, 30),
    sessionExpiresAt: after(now, 8 * 60),
    ...options.override,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

function secondFactorPending(
  status: SecondFactorPendingResponseOutput["status"],
  options: BuildOptions<SecondFactorPendingResponseOutput>,
): ContractResponse {
  const now = options.now ?? new Date();
  const body = checked("SecondFactorPendingResponse", SecondFactorPendingResponse, {
    status,
    secondFactorToken: opaque(),
    secondFactorTokenExpiresAt: after(now, 5),
    ...options.override,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

export function secondFactorRequired(options: BuildOptions<SecondFactorPendingResponseOutput> = {}): ContractResponse {
  return secondFactorPending("second-factor-required", options);
}

export function secondFactorEnrollmentRequired(
  options: BuildOptions<SecondFactorPendingResponseOutput> = {},
): ContractResponse {
  return secondFactorPending("second-factor-enrollment-required", options);
}

export function currentSession(options: BuildOptions<CurrentSessionResponseOutput> = {}): ContractResponse {
  const now = options.now ?? new Date();
  const body = checked("CurrentSessionResponse", CurrentSessionResponse, {
    accessTokenExpiresAt: after(now, 15),
    accountId: crypto.randomUUID(),
    authenticationMethods: ["pwd", "otp"],
    idleExpiresAt: after(now, 30),
    institutionId: crypto.randomUUID(),
    sessionExpiresAt: after(now, 8 * 60),
    sessionId: crypto.randomUUID(),
    ...options.override,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

function base32Secret(): string {
  return Array.from(randomBytes(32), (byte) => BASE32.charAt(byte % BASE32.length)).join("");
}

export function enrollment(options: Pick<BuildOptions<EnrollmentResponseOutput>, "override"> = {}): ContractResponse {
  const body = checked("EnrollmentResponse", EnrollmentResponse, {
    secret: base32Secret(),
    recoveryCodes: Array.from({ length: 10 }, () => randomBytes(5).toString("hex")),
    ...options.override,
  });
  return { status: 201, body, contentType: JSON_TYPE };
}

export function noContent(): ContractResponse {
  return { status: 204, body: undefined, contentType: undefined };
}

export interface ProblemOptions {
  readonly traceId?: string;
  readonly errors?: readonly { readonly field: string; readonly reason: string }[];
}

/**
 * An error response as the backend emits it: the type of its code, and an `instance` that is a relative
 * path (ProblemResponses.java), which `zod.url()` would refuse. The body is checked against the problem
 * contract with `instance` set aside for that reason (design.md D7).
 */
export function problem(code: ProblemCode, status: number, options: ProblemOptions = {}): ContractResponse {
  if (status < 400 || status > 599) throw new ContractViolation("ProblemDetail", ["status"]);
  const body = checked("ProblemDetail", ProblemDetail.omit({ instance: true }), {
    type: `https://confia.hn/problems/${code}`,
    status,
    ...(options.traceId === undefined ? {} : { traceId: options.traceId }),
    ...(options.errors === undefined ? {} : { errors: options.errors }),
  });
  return { status, body: { ...body, instance: "/api/v1/auth/sessions" }, contentType: "application/problem+json" };
}

import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll } from "vitest";
import type { ContractResponse } from "./contract-responses.ts";

/**
 * The test server: MSW in Node, answering what the app asks over `fetch` and recording what it asked.
 * Nothing here is mocked at the module level, so the real transport runs (testing playbook, section 8).
 * Answers come from `contract-responses.ts`, which has already checked them against the contract.
 */
export interface RecordedRequest {
  readonly method: string;
  readonly path: string;
  readonly headers: Headers;
  readonly body: string;
  readonly credentials: RequestCredentials;
  /** `Date.now()` when it arrived: the fake clock in a test that has one, so gaps can be asserted. */
  readonly at: number;
}

/** What a route answers: a contract response, or the network failing before any response. */
export type Reply = (ContractResponse & { readonly headers?: Readonly<Record<string, string>> }) | "network-error";

type Method = "get" | "post" | "put" | "patch" | "delete";

const server = setupServer();
const recorded: RecordedRequest[] = [];

function toHttpResponse(reply: Reply): Response {
  if (reply === "network-error") return HttpResponse.error();
  return new HttpResponse(reply.body === undefined ? null : JSON.stringify(reply.body), {
    status: reply.status,
    headers: { ...(reply.contentType === undefined ? {} : { "Content-Type": reply.contentType }), ...reply.headers },
  });
}

/**
 * Answers `method path` with the replies in order, repeating the last one once they run out, so a route
 * that always fails is one reply. Every request to it is recorded, whatever the answer.
 */
export function route(method: Method, path: string, ...replies: readonly [Reply, ...Reply[]]): void {
  let served = 0;
  server.use(
    http[method](path, async ({ request }) => {
      recorded.push({
        method: request.method,
        path: new URL(request.url).pathname,
        headers: new Headers(request.headers),
        body: await request.clone().text(),
        credentials: request.credentials,
        at: Date.now(),
      });
      const next = replies[Math.min(served, replies.length - 1)] ?? replies[0];
      served += 1;
      return toHttpResponse(next);
    }),
  );
}

/** Every request the app made since the test began, in order. */
export function requests(): readonly RecordedRequest[] {
  return [...recorded];
}

/**
 * Starts the server for the file that calls it and clears routes and records after each test. A request
 * no route answers fails the test, so the app can never reach a real network.
 */
export function installMswServer(): void {
  beforeAll(() => {
    server.listen({ onUnhandledFrame: "error" });
  });
  afterEach(() => {
    server.resetHandlers();
    recorded.length = 0;
  });
  afterAll(() => {
    server.close();
  });
}

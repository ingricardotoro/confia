import { describe, expect, it } from "vitest";
import { ApiFailure } from "../../../shared/api/api-failure.ts";
import * as api from "../../../shared/api/auth-operations.ts";
import { request } from "../../../shared/api/http.ts";
import { authenticatedSession, problem } from "../../../test/contract-responses.ts";
import { createFakeLocks } from "../../../test/fake-locks.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createAccessSession, type AccessSession } from "./access-session.ts";

// The way a screen sends an authenticated request without ever holding the token: `authorized`. It gives the
// operation the access token (refreshed first when little is left of it), returns the data, and throws ApiFailure
// for anything else; a 401 also ends the session, a 403 or a 409 does not.
installMswServer();

const NOW = Date.parse("2026-11-06T10:00:00Z");
const SESSIONS = "/api/v1/auth/sessions";
const PING = "/api/v1/organization/modalities";

function accept(body: unknown) {
  return { safeParse: (input: unknown) => ({ success: true as const, data: body ?? input }) };
}

async function signedIn(): Promise<AccessSession> {
  route("post", SESSIONS, authenticatedSession({ override: { accessToken: "token-A", accessTokenExpiresAt: new Date(NOW + 15 * 60_000).toISOString() } }));
  const session = createAccessSession({ api, locks: createFakeLocks(), now: () => NOW, readCsrf: () => "csrf-A" });
  await session.signIn({ identifier: "ana@colegio.edu.hn", password: "not-a-real-password" });
  return session;
}

const ping = (token: string) => request({ method: "GET", path: PING, headers: { Authorization: `Bearer ${token}` }, schema: accept(undefined) });

describe("AccessSession.authorized", () => {
  it("gives the operation the access token and returns its data", async () => {
    const session = await signedIn();
    route("get", PING, { status: 200, body: { ok: true }, contentType: "application/json" });

    await expect(session.authorized(ping)).resolves.toEqual({ ok: true });

    expect(requests().at(-1)?.headers.get("Authorization")).toBe("Bearer token-A");
  });

  it("throws ApiFailure for a 403 and keeps the session", async () => {
    const session = await signedIn();
    route("get", PING, problem("forbidden", 403));

    const failure = await session.authorized(ping).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiFailure);
    expect(failure).toMatchObject({ result: { kind: "problem", problem: { status: 403, code: "forbidden" } } });
    expect(session.getView().status).toBe("authenticated");
  });

  it("throws ApiFailure for a 401 and ends the session, as the read of the current session does", async () => {
    const session = await signedIn();
    route("get", PING, problem("authentication-required", 401));

    await expect(session.authorized(ping)).rejects.toBeInstanceOf(ApiFailure);

    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
  });

  it("makes no request when there is no session, and throws the failure of an ended session", async () => {
    const session = createAccessSession({ api, locks: createFakeLocks(), now: () => NOW, readCsrf: () => undefined });

    await expect(session.authorized(ping)).rejects.toMatchObject({ result: { problem: { code: "session-expired" } } });

    expect(requests()).toHaveLength(0);
  });
});

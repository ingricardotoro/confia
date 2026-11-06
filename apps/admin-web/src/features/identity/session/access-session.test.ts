import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiFailure } from "../../../shared/api/api-failure.ts";
import * as api from "../../../shared/api/auth-operations.ts";
import {
  authenticatedSession,
  currentSession,
  enrollment,
  noContent,
  problem,
  secondFactorEnrollmentRequired,
  secondFactorRequired,
} from "../../../test/contract-responses.ts";
import { createFakeLocks } from "../../../test/fake-locks.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createAccessSession, type AccessSession } from "./access-session.ts";

// admin-web-foundations-and-session, task 6.1 (part 6b; SS22 to SS26, and design.md D8). The real
// operations and the real transport run over the test server; the clock and the lock are injected, so a
// token can be 59 seconds from its end and no test waits for one.
installMswServer();

const NOW = Date.parse("2026-11-06T10:00:00Z");
const SESSIONS = "/api/v1/auth/sessions";
const REFRESH = `${SESSIONS}/refresh`;
const CURRENT = `${SESSIONS}/current`;
const CREDENTIALS = { identifier: "ana@colegio.edu.hn", password: "not-a-real-password" };

afterEach(() => {
  vi.useRealTimers();
});

function build(readCsrf: () => string | undefined = () => "csrf-A", locks = createFakeLocks()): AccessSession {
  return createAccessSession({ api, locks, now: () => NOW, readCsrf });
}

/** A token that ends `endsInMs` from the test clock. */
function accessGrant(accessToken: string, endsInMs: number) {
  return authenticatedSession({ override: { accessToken, accessTokenExpiresAt: new Date(NOW + endsInMs).toISOString() } });
}

async function signedIn(token: string, endsInMs: number, readCsrf?: () => string | undefined): Promise<AccessSession> {
  route("post", SESSIONS, accessGrant(token, endsInMs));
  const session = build(readCsrf);
  await session.signIn(CREDENTIALS);
  return session;
}

const paths = () => requests().map((sent) => `${sent.method} ${sent.path}`);

describe("a request with a Bearer token and the 60 second margin", () => {
  it("SS22: 59 seconds left refreshes first and then asks with the new token", async () => {
    const session = await signedIn("token-old", 59_000);
    route("post", REFRESH, accessGrant("token-new", 15 * 60_000));
    route("get", CURRENT, currentSession());

    await session.readCurrentSession();

    expect(paths()).toEqual([`POST ${SESSIONS}`, `POST ${REFRESH}`, `GET ${CURRENT}`]);
    expect(requests()[2]?.headers.get("Authorization")).toBe("Bearer token-new");
  });

  it("SS23: 60 seconds left does not refresh and asks with the current token", async () => {
    const session = await signedIn("token-old", 60_000);
    // A refresh is answerable, so a request that wrongly refreshes shows in the paths and not as a stray error.
    route("post", REFRESH, accessGrant("token-new", 15 * 60_000));
    route("get", CURRENT, currentSession());

    await session.readCurrentSession();

    expect(paths()).toEqual([`POST ${SESSIONS}`, `GET ${CURRENT}`]);
    expect(requests()[1]?.headers.get("Authorization")).toBe("Bearer token-old");
  });

  it("SS24: a refresh that fails by network is tried once, the request does not go out and the session is kept", async () => {
    vi.useFakeTimers();
    const session = await signedIn("token-old", 59_000);
    route("post", REFRESH, "network-error");
    route("get", CURRENT, currentSession());

    const failure = await session.readCurrentSession().catch((error: unknown) => error);
    await vi.advanceTimersByTimeAsync(60_000);

    expect(failure).toBeInstanceOf(ApiFailure);
    expect((failure as ApiFailure).result).toEqual({ kind: "network" });
    expect(paths()).toEqual([`POST ${SESSIONS}`, `POST ${REFRESH}`]);
    expect(session.getView()).toEqual({ status: "authenticated" });
  });

  it("SS25: a 401 to the request ends the session with no refresh and no second try", async () => {
    const session = await signedIn("token-old", 15 * 60_000);
    route("get", CURRENT, problem("token-expired", 401));
    route("post", REFRESH, accessGrant("token-new", 15 * 60_000));

    await expect(session.readCurrentSession()).rejects.toBeInstanceOf(ApiFailure);

    expect(paths()).toEqual([`POST ${SESSIONS}`, `GET ${CURRENT}`]);
    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    // The token is gone too: a later read makes no request at all.
    await expect(session.readCurrentSession()).rejects.toBeInstanceOf(ApiFailure);
    expect(requests()).toHaveLength(2);
  });

  it("SS26: a refresh answered 403 csrf-invalid ends the session, once, and shows the expired notice", async () => {
    const session = await signedIn("token-old", 59_000);
    route("post", REFRESH, problem("csrf-invalid", 403));
    route("get", CURRENT, currentSession());

    await expect(session.readCurrentSession()).rejects.toBeInstanceOf(ApiFailure);

    expect(paths()).toEqual([`POST ${SESSIONS}`, `POST ${REFRESH}`]);
    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    await expect(session.readCurrentSession()).rejects.toBeInstanceOf(ApiFailure);
    expect(requests()).toHaveLength(2);
  });

  it("ends the session with no request when the CSRF cookie is gone at the moment of the refresh", async () => {
    let cookie: string | undefined = "csrf-A";
    const session = await signedIn("token-old", 59_000, () => cookie);
    cookie = undefined;

    await expect(session.readCurrentSession()).rejects.toBeInstanceOf(ApiFailure);

    expect(paths()).toEqual([`POST ${SESSIONS}`]);
    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
  });

  it("answers a read that failed for another reason with that failure and keeps the session", async () => {
    const session = await signedIn("token-old", 15 * 60_000);
    route("get", CURRENT, problem("capacity-exceeded", 503));

    const failure = await session.readCurrentSession().catch((error: unknown) => error);

    expect((failure as ApiFailure).result).toMatchObject({ kind: "problem", problem: { status: 503 } });
    expect(session.getView()).toEqual({ status: "authenticated" });
  });
});

describe("restoring the session", () => {
  it("restores with the refresh cookie and shows the authenticated view", async () => {
    route("post", REFRESH, accessGrant("token-new", 15 * 60_000));
    const session = build();

    await session.boot();

    expect(session.getView()).toEqual({ status: "authenticated" });
    expect(requests()[0]?.headers.get("X-CSRF-Token")).toBe("csrf-A");
  });

  it("restores once however many times it is asked", async () => {
    route("post", REFRESH, accessGrant("token-new", 15 * 60_000));
    const session = build();

    await Promise.all([session.boot(), session.boot()]);

    expect(requests()).toHaveLength(1);
  });

  it("arrives anonymous, with no notice and no request, when there is no cookie", async () => {
    const session = build(() => undefined);

    await session.boot();

    expect(session.getView()).toEqual({ status: "anonymous" });
    expect(requests()).toHaveLength(0);
  });

  it("shows the expired notice when the server rejects the cookie", async () => {
    route("post", REFRESH, problem("session-expired", 401));
    const session = build();

    await session.boot();

    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
  });

  it("fails to restore on a network error and restores on the one retry the person asks for", async () => {
    route("post", REFRESH, "network-error", accessGrant("token-new", 15 * 60_000));
    const session = build();

    await session.boot();
    expect(session.getView()).toEqual({ status: "restore-failed" });
    expect(requests()).toHaveLength(1);

    await session.retryBoot();
    expect(session.getView()).toEqual({ status: "authenticated" });
    expect(requests()).toHaveLength(2);
  });

  it("does nothing on retry when the restore did not fail", async () => {
    const session = build(() => undefined);
    await session.boot();

    await session.retryBoot();

    expect(requests()).toHaveLength(0);
  });
});

describe("the view", () => {
  it("is the same object until it changes, tells listeners, and stops telling the one that left", async () => {
    route("post", SESSIONS, accessGrant("token-secret-value", 15 * 60_000));
    const session = build();
    const listener = vi.fn();
    const unsubscribe = session.subscribe(listener);
    const before = session.getView();
    expect(session.getView()).toBe(before);

    await session.signIn(CREDENTIALS);

    expect(listener).toHaveBeenCalledTimes(1);
    expect(session.getView()).not.toBe(before);
    expect(JSON.stringify(session.getView())).not.toContain("token-secret-value");

    unsubscribe();
    session.discardSecondFactor();
    await session.signIn(CREDENTIALS);
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe("signing in and the second factor", () => {
  it("authenticates when the answer is a session", async () => {
    route("post", SESSIONS, accessGrant("token-new", 15 * 60_000));
    const session = build();

    await expect(session.signIn(CREDENTIALS)).resolves.toEqual({ kind: "authenticated" });

    expect(session.getView()).toEqual({ status: "authenticated" });
  });

  it("keeps the restricted token, with its use, and stays anonymous when a second factor is asked", async () => {
    const session = build();
    route("post", SESSIONS, secondFactorRequired());
    await expect(session.signIn(CREDENTIALS)).resolves.toEqual({ kind: "second-factor-required" });
    expect(session.hasSecondFactorToken("mfa-verify")).toBe(true);
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(false);

    route("post", SESSIONS, secondFactorEnrollmentRequired());
    await expect(session.signIn(CREDENTIALS)).resolves.toEqual({ kind: "enrollment-required" });
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(true);
    expect(session.getView()).toEqual({ status: "restoring" });
  });

  it("returns the failure and changes nothing when the credentials are refused", async () => {
    route("post", SESSIONS, problem("authentication-failed", 401));
    const session = build();

    const outcome = await session.signIn(CREDENTIALS);

    expect(outcome).toMatchObject({ kind: "failed", result: { kind: "problem" } });
    expect(session.getView()).toEqual({ status: "restoring" });
  });

  it("verifies with the restricted token and becomes authenticated", async () => {
    route("post", SESSIONS, secondFactorRequired({ override: { secondFactorToken: "restricted-token" } }));
    route("post", "/api/v1/auth/mfa/verifications", accessGrant("token-new", 15 * 60_000));
    const session = build();
    await session.signIn(CREDENTIALS);

    await expect(session.verify({ kind: "code", value: "123456" })).resolves.toEqual({ kind: "authenticated" });

    expect(requests()[1]?.headers.get("Authorization")).toBe("Bearer restricted-token");
    expect(session.getView()).toEqual({ status: "authenticated" });
    expect(session.hasSecondFactorToken("mfa-verify")).toBe(false);
  });

  it("returns the failure of a refused code and keeps the restricted token", async () => {
    route("post", SESSIONS, secondFactorRequired());
    route("post", "/api/v1/auth/mfa/verifications", problem("second-factor-invalid", 401));
    const session = build();
    await session.signIn(CREDENTIALS);

    await expect(session.verify({ kind: "recovery-code", value: "abcde12345" })).resolves.toMatchObject({ kind: "failed" });

    expect(session.hasSecondFactorToken("mfa-verify")).toBe(true);
  });

  it("makes no request to verify or to enroll without the matching restricted token", async () => {
    route("post", SESSIONS, secondFactorEnrollmentRequired());
    const session = build();
    await session.signIn(CREDENTIALS);

    await expect(session.verify({ kind: "code", value: "123456" })).resolves.toEqual({ kind: "no-token" });
    const other = build();
    await expect(other.enroll()).resolves.toEqual({ kind: "no-token" });

    expect(requests()).toHaveLength(1);
  });

  it("enrolls with the restricted token and hands back the secret and the codes without keeping them", async () => {
    route("post", SESSIONS, secondFactorEnrollmentRequired({ override: { secondFactorToken: "restricted-token" } }));
    const issued = enrollment();
    route("post", "/api/v1/auth/mfa/enrollments", issued);
    const session = build();
    await session.signIn(CREDENTIALS);

    const outcome = await session.enroll();

    expect(outcome).toEqual({ kind: "enrolled", ...(issued.body as object) });
    expect(requests()[1]?.headers.get("Authorization")).toBe("Bearer restricted-token");
    expect(JSON.stringify(session.getView())).not.toContain("secret");
  });

  it("returns the failure of a refused enrollment", async () => {
    route("post", SESSIONS, secondFactorEnrollmentRequired());
    route("post", "/api/v1/auth/mfa/enrollments", problem("mfa-already-enrolled", 409));
    const session = build();
    await session.signIn(CREDENTIALS);

    await expect(session.enroll()).resolves.toMatchObject({ kind: "failed" });
  });

  it("discards the restricted token and arrives at the sign in page with the notice", async () => {
    route("post", SESSIONS, secondFactorRequired());
    const session = build();
    await session.signIn(CREDENTIALS);

    session.discardSecondFactor("second-factor-interrupted");

    expect(session.hasSecondFactorToken("mfa-verify")).toBe(false);
    expect(session.getView()).toEqual({ status: "anonymous", notice: "second-factor-interrupted" });
  });

  it("does not end a session that is open when asked to discard a second factor", async () => {
    const session = await signedIn("token-old", 15 * 60_000);

    session.discardSecondFactor("second-factor-interrupted");

    expect(session.getView()).toEqual({ status: "authenticated" });
  });
});

describe("signing out", () => {
  it("takes the lock, sends the CSRF value read inside it and no token, and forgets everything on 204", async () => {
    const locks = createFakeLocks();
    route("post", SESSIONS, accessGrant("token-old", 15 * 60_000));
    route("delete", CURRENT, noContent());
    const session = build(() => "csrf-A", locks);
    await session.signIn(CREDENTIALS);

    await expect(session.signOut()).resolves.toEqual({ kind: "signed-out" });

    const [, closing] = requests();
    expect(closing?.headers.get("X-CSRF-Token")).toBe("csrf-A");
    expect(closing?.headers.has("Authorization")).toBe(false);
    expect(locks.requested).toEqual([{ name: "confia.admin.session.refresh", mode: "exclusive" }]);
    expect(session.getView()).toEqual({ status: "anonymous" });
    await expect(session.readCurrentSession()).rejects.toBeInstanceOf(ApiFailure);
    expect(requests()).toHaveLength(2);
  });

  it("keeps the session when the server did not answer 204", async () => {
    const session = await signedIn("token-old", 15 * 60_000);
    route("delete", CURRENT, problem("capacity-exceeded", 503));

    await expect(session.signOut()).resolves.toEqual({ kind: "failed" });

    expect(session.getView()).toEqual({ status: "authenticated" });
  });
});

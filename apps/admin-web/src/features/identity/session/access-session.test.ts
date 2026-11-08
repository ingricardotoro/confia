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
import { createEnrollmentDraft, type EnrollmentDraft } from "./enrollment-draft.ts";

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
    await expect(other.startEnrollment()).resolves.toEqual({ kind: "no-token" });

    expect(requests()).toHaveLength(1);
  });

  it("starts the enrollment with the restricted token, keeps the secret in the draft and hands back nothing of it", async () => {
    route("post", SESSIONS, secondFactorEnrollmentRequired({ override: { secondFactorToken: "restricted-token" } }));
    const issued = enrollment();
    route("post", "/api/v1/auth/mfa/enrollments", issued);
    const session = build();
    await session.signIn(CREDENTIALS);

    const outcome = await session.startEnrollment();

    expect(outcome).toEqual({ kind: "ready" });
    expect(requests()[1]?.headers.get("Authorization")).toBe("Bearer restricted-token");
    expect(requests()[1]?.body).toBe("");
    expect(session.getEnrollment()).toEqual({ identifier: CREDENTIALS.identifier, step: "secret", ...(issued.body as object) });
    expect(JSON.stringify(session.getView())).not.toContain("secret");
  });

  it("returns the failure of a refused enrollment", async () => {
    route("post", SESSIONS, secondFactorEnrollmentRequired());
    route("post", "/api/v1/auth/mfa/enrollments", problem("mfa-already-enrolled", 409));
    const session = build();
    await session.signIn(CREDENTIALS);

    await expect(session.startEnrollment()).resolves.toMatchObject({ kind: "failed" });
    expect(session.getEnrollment()).toBeUndefined();
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
    // Signing out on purpose is not an expired session: a later read must not raise the expired notice.
    expect(session.getView()).toEqual({ status: "anonymous" });
  });

  it("leaves the restoring view alone when a read comes before the session is restored", async () => {
    const session = build();

    await expect(session.readCurrentSession()).rejects.toBeInstanceOf(ApiFailure);

    expect(session.getView()).toEqual({ status: "restoring" });
    expect(requests()).toHaveLength(0);
  });

  it("keeps the session when the server did not answer 204", async () => {
    const session = await signedIn("token-old", 15 * 60_000);
    route("delete", CURRENT, problem("capacity-exceeded", 503));

    await expect(session.signOut()).resolves.toEqual({ kind: "failed" });

    expect(session.getView()).toEqual({ status: "authenticated" });
  });
});

// admin-web-staff-sign-in, task 1.1 (E2, R7; SS58, SS59, SS72, SS74, SS87, SS93, SS96, SS98): the enrollment
// draft lives in the session module, so one `endLocally` forgets it on every path. Secrets are made at run time.
const ENROLLMENTS = "/api/v1/auth/mfa/enrollments";
const VERIFICATIONS = "/api/v1/auth/mfa/verifications";
const SENT_IDENTIFIER = "ana.pérez+admin@colegio.edu.hn";

function buildWithDraft(): { session: AccessSession; draft: EnrollmentDraft } {
  const draft = createEnrollmentDraft();
  const session = createAccessSession({
    api,
    locks: createFakeLocks(),
    now: () => NOW,
    readCsrf: () => "csrf-A",
    enrollmentDraft: draft,
  });
  return { session, draft };
}

/** A session that signed in and has to enroll, with the enrollment already issued. */
async function enrolling(token = "restricted-token") {
  const built = buildWithDraft();
  route("post", SESSIONS, secondFactorEnrollmentRequired({ override: { secondFactorToken: token } }));
  route("post", ENROLLMENTS, enrollment());
  await built.session.signIn({ identifier: SENT_IDENTIFIER, password: CREDENTIALS.password });
  await built.session.startEnrollment();
  return built;
}

describe("the identifier kept for the enrollment", () => {
  it("SS58: keeps the identifier that was sent when an enrollment is required", async () => {
    const { session } = await enrolling();

    const sent = JSON.parse(requests()[0]?.body ?? "{}") as { identifier: string };
    expect(sent.identifier).toBe(SENT_IDENTIFIER);
    expect(session.getEnrollment()?.identifier).toBe(sent.identifier);
  });

  it("SS58: keeps it trimmed", async () => {
    const { session, draft } = buildWithDraft();
    route("post", SESSIONS, secondFactorEnrollmentRequired());

    await session.signIn({ identifier: `  ${SENT_IDENTIFIER} `, password: CREDENTIALS.password });

    expect(draft.identifier()).toBe(SENT_IDENTIFIER);
  });

  it.each([
    ["authenticated", () => accessGrant("token-new", 15 * 60_000)],
    ["second-factor-required", () => secondFactorRequired()],
  ])("SS59: keeps no identifier for a %s answer", async (_status, answer) => {
    const { session, draft } = buildWithDraft();
    route("post", SESSIONS, answer());

    await session.signIn({ identifier: SENT_IDENTIFIER, password: CREDENTIALS.password });

    expect(draft.identifier()).toBeUndefined();
    expect(session.getEnrollment()).toBeUndefined();
  });

  it("SS59: a later answer that needs no enrollment forgets the identifier and the secret of the earlier one", async () => {
    const { session, draft } = await enrolling();
    route("post", SESSIONS, secondFactorRequired());

    await session.signIn(CREDENTIALS);

    expect(draft.identifier()).toBeUndefined();
    expect(session.getEnrollment()).toBeUndefined();
  });

  it("keeps nothing when the credentials are refused", async () => {
    const { session, draft } = buildWithDraft();
    route("post", SESSIONS, problem("authentication-failed", 401));

    await session.signIn({ identifier: SENT_IDENTIFIER, password: CREDENTIALS.password });

    expect(draft.identifier()).toBeUndefined();
  });
});

describe("starting the enrollment", () => {
  it("SS74: two calls in a row send one request, with the restricted token, and share the promise", async () => {
    const { session } = buildWithDraft();
    route("post", SESSIONS, secondFactorEnrollmentRequired({ override: { secondFactorToken: "mfa-enroll-token" } }));
    route("post", ENROLLMENTS, enrollment());
    await session.signIn(CREDENTIALS);

    const first = session.startEnrollment();
    const second = session.startEnrollment();
    expect(second).toBe(first);
    await first;
    await session.startEnrollment();

    const sent = requests().filter((request) => request.path === ENROLLMENTS);
    expect(sent).toHaveLength(1);
    expect(sent[0]?.headers.get("Authorization")).toBe("Bearer mfa-enroll-token");
  });

  it("SS74: a new restricted token asks again and replaces the secret", async () => {
    const { session } = await enrolling("first-token");
    const before = session.getEnrollment()?.secret;
    route("post", SESSIONS, secondFactorEnrollmentRequired({ override: { secondFactorToken: "second-token" } }));
    route("post", ENROLLMENTS, enrollment());
    await session.signIn(CREDENTIALS);

    await session.startEnrollment();

    expect(requests().filter((request) => request.path === ENROLLMENTS)).toHaveLength(2);
    expect(session.getEnrollment()?.secret).not.toBe(before);
    expect(session.getEnrollment()?.step).toBe("secret");
  });

  it("makes no request without a restricted token for the enrollment", async () => {
    const { session } = buildWithDraft();
    route("post", SESSIONS, secondFactorRequired());
    await session.signIn(CREDENTIALS);

    await expect(session.startEnrollment()).resolves.toEqual({ kind: "no-token" });

    expect(requests()).toHaveLength(1);
  });

  it("makes no request with a restricted token that has ended", async () => {
    const { session } = buildWithDraft();
    route(
      "post",
      SESSIONS,
      secondFactorEnrollmentRequired({ override: { secondFactorTokenExpiresAt: new Date(NOW - 1_000).toISOString() } }),
    );
    await session.signIn(CREDENTIALS);

    await expect(session.startEnrollment()).resolves.toEqual({ kind: "no-token" });

    expect(requests()).toHaveLength(1);
  });

  it.each([
    ["a network error", () => "network-error" as const],
    ["a 429", () => problem("too-many-requests", 429)],
    ["a 503", () => problem("capacity-exceeded", 503)],
    ["an unexpected answer", () => ({ status: 200, body: { unexpected: true }, contentType: "application/json" })],
  ])("SS87: %s forgets the promise, keeps the token and sends the next request only when asked again", async (_name, failure) => {
    const { session } = buildWithDraft();
    route("post", SESSIONS, secondFactorEnrollmentRequired());
    route("post", ENROLLMENTS, failure(), enrollment());
    await session.signIn(CREDENTIALS);

    await expect(session.startEnrollment()).resolves.toMatchObject({ kind: "failed" });
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(true);
    expect(session.getEnrollment()).toBeUndefined();
    expect(requests().filter((request) => request.path === ENROLLMENTS)).toHaveLength(1);

    await expect(session.startEnrollment()).resolves.toEqual({ kind: "ready" });
    expect(requests().filter((request) => request.path === ENROLLMENTS)).toHaveLength(2);
    expect(session.getEnrollment()?.step).toBe("secret");
  });

  it("does not keep the answer of an enrollment that was abandoned while it was in flight", async () => {
    const { session, draft } = buildWithDraft();
    route("post", SESSIONS, secondFactorEnrollmentRequired());
    route("post", ENROLLMENTS, enrollment());
    await session.signIn(CREDENTIALS);

    const started = session.startEnrollment();
    session.discardSecondFactor("enrollment-interrupted");

    await expect(started).resolves.toEqual({ kind: "no-token" });
    expect(draft.snapshot()).toBeUndefined();
  });
});

describe("the steps of the enrollment", () => {
  it("SS93: moves secret, recovery-codes, first-code and no operation returns the codes afterwards", async () => {
    const { session } = await enrolling();
    const codes = session.getEnrollment()?.recoveryCodes ?? [];
    expect(codes).toHaveLength(10);

    session.advanceEnrollment();
    expect(session.getEnrollment()).toMatchObject({ step: "recovery-codes", recoveryCodes: codes });
    session.advanceEnrollment();

    expect(session.getEnrollment()).toMatchObject({ step: "first-code", recoveryCodes: undefined });
    await expect(session.startEnrollment()).resolves.toEqual({ kind: "ready" });
    expect(JSON.stringify(session.getEnrollment())).not.toContain(codes[0] ?? "no-code");
  });

  it("goes back to the secret and, with no codes left, straight on to the first code", async () => {
    const { session } = await enrolling();
    session.advanceEnrollment();
    session.advanceEnrollment();

    session.reviewEnrollmentSecret();
    expect(session.getEnrollment()?.step).toBe("secret");
    session.advanceEnrollment();

    expect(session.getEnrollment()).toMatchObject({ step: "first-code", recoveryCodes: undefined });
  });

  it("returns the same object until the enrollment changes and tells its listeners when it does", async () => {
    const { session } = await enrolling();
    const listener = vi.fn();
    session.subscribe(listener);
    const before = session.getEnrollment();
    expect(session.getEnrollment()).toBe(before);

    session.advanceEnrollment();

    expect(listener).toHaveBeenCalledTimes(1);
    expect(session.getEnrollment()).not.toBe(before);
    session.reviewEnrollmentSecret();
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe("activating with the first code", () => {
  it("SS96: sends only the code with the enrollment token, accepts the session and clears the draft", async () => {
    const { session, draft } = await enrolling("enroll-token");
    route("post", VERIFICATIONS, accessGrant("token-new", 15 * 60_000));

    await expect(session.activate("123456")).resolves.toEqual({ kind: "authenticated" });

    const sent = requests().at(-1);
    expect(sent?.path).toBe(VERIFICATIONS);
    expect(sent?.headers.get("Authorization")).toBe("Bearer enroll-token");
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({ code: "123456" });
    expect(session.getView()).toEqual({ status: "authenticated" });
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(false);
    expect(draft.identifier()).toBeUndefined();
    expect(session.getEnrollment()).toBeUndefined();
  });

  it("keeps the draft and the token after a 401 second-factor-invalid", async () => {
    const { session } = await enrolling();
    const before = session.getEnrollment();
    route("post", VERIFICATIONS, problem("second-factor-invalid", 401));

    await expect(session.activate("123456")).resolves.toMatchObject({ kind: "failed" });

    expect(session.getEnrollment()).toBe(before);
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(true);
    expect(session.getView()).toEqual({ status: "restoring" });
  });

  it.each(["token-expired", "token-invalid"] as const)(
    "ends the enrollment with the interrupted notice on a 401 %s",
    async (code) => {
      const { session, draft } = await enrolling();
      route("post", VERIFICATIONS, problem(code, 401));

      await expect(session.activate("123456")).resolves.toMatchObject({ kind: "failed" });

      expect(session.getView()).toEqual({ status: "anonymous", notice: "enrollment-interrupted" });
      expect(session.hasSecondFactorToken("mfa-enroll")).toBe(false);
      expect(draft.identifier()).toBeUndefined();
      expect(session.getEnrollment()).toBeUndefined();
    },
  );

  it("ends the enrollment with the interrupted notice, and no request, without an enrollment token", async () => {
    const { session, draft } = await enrolling();
    session.discardSecondFactor();
    const sent = requests().length;

    await expect(session.activate("123456")).resolves.toEqual({ kind: "no-token" });

    expect(requests()).toHaveLength(sent);
    expect(session.getView()).toEqual({ status: "anonymous", notice: "enrollment-interrupted" });
    expect(draft.snapshot()).toBeUndefined();
  });

  it("does not end an open session when asked to activate without an enrollment token", async () => {
    const session = await signedIn("token-old", 15 * 60_000);

    await expect(session.activate("123456")).resolves.toEqual({ kind: "no-token" });

    expect(session.getView()).toEqual({ status: "authenticated" });
  });

  it("clears the draft on a correct verification as well", async () => {
    const { session, draft } = await enrolling();
    route("post", SESSIONS, secondFactorRequired({ override: { secondFactorToken: "verify-token" } }));
    await session.signIn(CREDENTIALS);
    route("post", VERIFICATIONS, accessGrant("token-new", 15 * 60_000));

    await session.verify({ kind: "code", value: "123456" });

    expect(draft.snapshot()).toBeUndefined();
  });
});

describe("leaving the enrollment", () => {
  it("SS98: discarding the second factor clears the draft", async () => {
    const { session, draft } = await enrolling();

    session.discardSecondFactor("enrollment-interrupted");

    expect(draft.identifier()).toBeUndefined();
    expect(session.getEnrollment()).toBeUndefined();
  });

  it("SS98: signing out clears the draft", async () => {
    const { session, draft } = await enrolling();
    route("delete", CURRENT, noContent());

    await session.signOut();

    expect(draft.identifier()).toBeUndefined();
    expect(session.getEnrollment()).toBeUndefined();
  });

  it("SS98: a session that ends by itself clears the draft", async () => {
    const { session, draft } = await enrolling();
    route("post", REFRESH, problem("session-expired", 401));

    await session.boot();

    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    expect(draft.identifier()).toBeUndefined();
  });
});

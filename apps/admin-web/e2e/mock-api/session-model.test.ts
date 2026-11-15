// @vitest-environment node
import { describe, expect, it } from "vitest";
import { totpCode } from "../support/totp.ts";
import {
  CSRF_COOKIE,
  SESSION_COOKIE,
  createSessionModel,
  parseCookieHeader,
  type CookieRoute,
  type ModelResponse,
  type SessionModel,
} from "./session-model.ts";

// admin-web-foundations-and-session, task 11.1 (design.md D15). The session model of the simulated API:
// family, rotation with a grace, the allowed Origin, double-submit CSRF, the cookie attributes and the
// restricted tokens. It is a simulation: it says nothing about the real stack. The clock is injected.
const ORIGIN = "http://localhost:5173";
const START = Date.parse("2026-11-06T12:00:00.000Z");

function fixture(options: { enrolled?: boolean } = {}) {
  const clock = { now: START };
  const model = createSessionModel({ now: () => clock.now, ...options });
  return { clock, model };
}

interface Opened {
  readonly sid: string;
  readonly csrf: string;
  readonly accessToken: string;
  readonly response: ModelResponse;
}

function cookieValue(response: ModelResponse, name: string): string {
  const line = response.setCookies.find((cookie) => cookie.startsWith(`${name}=`));
  if (line === undefined) throw new Error(`no ${name} cookie was set`);
  return line.slice(name.length + 1, line.indexOf(";"));
}

function open(model: SessionModel): Opened {
  const response = model.openSession();
  return {
    sid: cookieValue(response, SESSION_COOKIE),
    csrf: cookieValue(response, CSRF_COOKIE),
    accessToken: (response.body as { accessToken: string }).accessToken,
    response,
  };
}

function route(sid: string | undefined, csrf: string | undefined, overrides: Partial<CookieRoute> = {}): CookieRoute {
  return {
    origin: ORIGIN,
    cookies: [
      ...(sid === undefined ? [] : [{ name: SESSION_COOKIE, value: sid }]),
      ...(csrf === undefined ? [] : [{ name: CSRF_COOKIE, value: csrf }]),
    ],
    csrfHeader: csrf,
    ...overrides,
  };
}

const typeOf = (response: ModelResponse) => (response.body as { type: string }).type.split("/").pop();

describe("the cookies (WE62, WE63)", () => {
  it("sets the session cookie with the fixed attributes, a 43 character base64url value and no lifetime", () => {
    const { response, sid } = open(fixture().model);
    const line = response.setCookies.find((cookie) => cookie.startsWith(`${SESSION_COOKIE}=`)) ?? "";

    expect(sid).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(line).toBe(`${SESSION_COOKIE}=${sid}; Path=/api/v1/auth/sessions; HttpOnly; Secure; SameSite=Strict`);
    expect(line).not.toMatch(/Domain|Max-Age|Expires/i);
  });

  it("sets the CSRF cookie readable by the page: Secure, Path=/, SameSite=Strict, never HttpOnly", () => {
    const { response, csrf } = open(fixture().model);
    const line = response.setCookies.find((cookie) => cookie.startsWith(`${CSRF_COOKIE}=`)) ?? "";

    expect(csrf).not.toBe("");
    expect(line).toBe(`${CSRF_COOKIE}=${csrf}; Path=/; Secure; SameSite=Strict`);
    expect(line).not.toMatch(/HttpOnly|Domain|Max-Age|Expires/i);
  });

  it("clears both cookies on a rejected refresh: empty value, same name and attributes, Max-Age=0", () => {
    const response = fixture().model.refresh(route(undefined, undefined));

    expect(response.setCookies).toEqual([
      `${SESSION_COOKIE}=; Path=/api/v1/auth/sessions; HttpOnly; Secure; SameSite=Strict; Max-Age=0`,
      `${CSRF_COOKIE}=; Path=/; Secure; SameSite=Strict; Max-Age=0`,
    ]);
  });

  it("reads a Cookie header into pairs, keeping repeated names and the whole value", () => {
    expect(parseCookieHeader("a=1; b=x=y; a=2")).toEqual([
      { name: "a", value: "1" },
      { name: "b", value: "x=y" },
      { name: "a", value: "2" },
    ]);
    expect(parseCookieHeader(undefined)).toEqual([]);
  });
});

describe("SS120: the rotation of the refresh with a grace", () => {
  it("rotates the refresh and the CSRF value together on every refresh", () => {
    const { model } = fixture();
    const first = open(model);

    const refreshed = model.refresh(route(first.sid, first.csrf));

    expect(refreshed.status).toBe(200);
    expect(cookieValue(refreshed, SESSION_COOKIE)).not.toBe(first.sid);
    expect(cookieValue(refreshed, CSRF_COOKIE)).not.toBe(first.csrf);
    expect((refreshed.body as { accessToken: string }).accessToken).not.toBe(first.accessToken);
  });

  it("accepts the previous refresh 5 seconds after it was rotated, and hands out the current cookies", () => {
    const { model, clock } = fixture();
    const first = open(model);
    const second = model.refresh(route(first.sid, first.csrf));
    clock.now += 5_000;

    const again = model.refresh(route(first.sid, first.csrf));

    expect(again.status).toBe(200);
    expect(cookieValue(again, SESSION_COOKIE)).toBe(cookieValue(second, SESSION_COOKIE));
    expect(cookieValue(again, CSRF_COOKIE)).toBe(cookieValue(second, CSRF_COOKIE));
  });

  it("answers 401 session-expired to the previous refresh 30 seconds after, and clears the cookies", () => {
    const { model, clock } = fixture();
    const first = open(model);
    model.refresh(route(first.sid, first.csrf));
    clock.now += 30_000;

    const late = model.refresh(route(first.sid, first.csrf));

    expect(late.status).toBe(401);
    expect(typeOf(late)).toBe("session-expired");
    expect(late.setCookies).toHaveLength(2);
    expect(late.headers["WWW-Authenticate"]).toBe("Bearer");
  });

  it("ends at the 10 second line: 9.999 seconds is inside, 10 seconds is outside", () => {
    for (const [elapsed, status] of [
      [9_999, 200],
      [10_000, 401],
    ] as const) {
      const { model, clock } = fixture();
      const first = open(model);
      model.refresh(route(first.sid, first.csrf));
      clock.now += elapsed;

      expect(model.refresh(route(first.sid, first.csrf)).status).toBe(status);
    }
  });

  it("revokes the whole family when a refresh is reused after the grace", () => {
    const { model, clock } = fixture();
    const first = open(model);
    const second = model.refresh(route(first.sid, first.csrf));
    clock.now += 30_000;
    model.refresh(route(first.sid, first.csrf));

    const successor = model.refresh(route(cookieValue(second, SESSION_COOKIE), cookieValue(second, CSRF_COOKIE)));

    expect(successor.status).toBe(401);
    expect(typeOf(successor)).toBe("session-expired");
  });

  it("keeps the current refresh working long after the grace of the previous one has passed", () => {
    const { model, clock } = fixture();
    const first = open(model);
    const second = model.refresh(route(first.sid, first.csrf));
    clock.now += 60_000;

    expect(model.refresh(route(cookieValue(second, SESSION_COOKIE), cookieValue(second, CSRF_COOKIE))).status).toBe(200);
  });

  it("answers 401 session-expired to a refresh the model never issued", () => {
    const response = fixture().model.refresh(route("x".repeat(43), "any"));

    expect(response.status).toBe(401);
    expect(typeOf(response)).toBe("session-expired");
    expect(response.setCookies).toHaveLength(2);
  });
});

describe("the Origin and the double-submit CSRF check", () => {
  function rejection(response: ModelResponse): unknown {
    return { status: response.status, type: typeOf(response), cookies: response.setCookies };
  }

  it("accepts the page origin and nothing that differs from it by a character", () => {
    const { model } = fixture();
    const session = open(model);

    expect(model.refresh(route(session.sid, session.csrf)).status).toBe(200);
    for (const origin of [undefined, "http://localhost:5174", "http://localhost:5173/", "https://localhost:5173", "null"]) {
      const current = open(model);
      const response = model.refresh(route(current.sid, current.csrf, { origin }));

      expect([origin, response.status, typeOf(response)]).toEqual([origin, 403, "csrf-invalid"]);
    }
  });

  it("refuses with the same 403 whether the header is missing, differs, belongs to another session or is repeated", () => {
    const { model } = fixture();
    const a = open(model);
    const b = open(model);
    const refused = [
      model.refresh(route(a.sid, a.csrf, { csrfHeader: undefined })),
      model.refresh(route(a.sid, a.csrf, { csrfHeader: `${a.csrf}x` })),
      model.refresh(route(a.sid, b.csrf)),
      model.refresh(route(a.sid, a.csrf, { cookies: [{ name: SESSION_COOKIE, value: a.sid }, { name: CSRF_COOKIE, value: a.csrf }, { name: CSRF_COOKIE, value: a.csrf }] })),
      model.refresh(route(a.sid, a.csrf, { cookies: [{ name: SESSION_COOKIE, value: a.sid }, { name: SESSION_COOKIE, value: b.sid }, { name: CSRF_COOKIE, value: a.csrf }] })),
      model.refresh(route(a.sid, undefined)),
    ];

    expect(refused.map(rejection)).toEqual(Array(6).fill({ status: 403, type: "csrf-invalid", cookies: [] }));
  });

  it("leaves the session usable after a refusal: nothing was rotated", () => {
    const { model } = fixture();
    const a = open(model);
    model.refresh(route(a.sid, `${a.csrf}x`));

    expect(model.refresh(route(a.sid, a.csrf)).status).toBe(200);
  });

  it("asks for no CSRF value when there is no session cookie, but still asks for the Origin", () => {
    const { model } = fixture();

    expect(model.refresh(route(undefined, undefined)).status).toBe(401);
    expect(model.refresh(route(undefined, undefined, { origin: undefined })).status).toBe(403);
  });
});

describe("signing out", () => {
  it("answers 204, clears both cookies and ends the family and its access token", () => {
    const { model } = fixture();
    const session = open(model);

    const response = model.signOut(route(session.sid, session.csrf));

    expect(response).toMatchObject({ status: 204, body: undefined, contentType: undefined });
    expect(response.setCookies).toHaveLength(2);
    expect(model.currentSession(session.accessToken).status).toBe(401);
    expect(model.refresh(route(session.sid, session.csrf)).status).toBe(401);
  });

  it("answers 204 with the cookies cleared when there was no session, and 403 without the Origin or with a bad CSRF value", () => {
    const { model } = fixture();
    const session = open(model);

    expect(model.signOut(route(undefined, undefined))).toMatchObject({ status: 204 });
    expect(model.signOut(route(undefined, undefined, { origin: undefined })).status).toBe(403);
    expect(model.signOut(route(session.sid, "wrong")).status).toBe(403);
    expect(model.currentSession(session.accessToken).status).toBe(200);
  });
});

describe("the current session", () => {
  it("answers the session of the access token, with the same ids on every read", () => {
    const { model } = fixture();
    const session = open(model);

    const first = model.currentSession(session.accessToken);
    const second = model.currentSession(session.accessToken);

    expect(first.status).toBe(200);
    expect(first.body).toEqual(second.body);
  });

  it("answers 401 authentication-required with a Bearer challenge for a missing, unknown or expired token", () => {
    const { model, clock } = fixture();
    const session = open(model);
    clock.now += 16 * 60_000;

    for (const token of [undefined, "unknown", session.accessToken]) {
      const response = model.currentSession(token);

      expect([token === session.accessToken ? "expired" : token, response.status, typeOf(response)]).toEqual([
        token === session.accessToken ? "expired" : token,
        401,
        "authentication-required",
      ]);
      expect(response.headers["WWW-Authenticate"]).toBe("Bearer");
    }
  });
});

describe("SS121: the restricted tokens", () => {
  const bearerOf = (response: ModelResponse) => (response.body as { secondFactorToken: string }).secondFactorToken;

  it("rejects a recovery code on a mfa-enroll token with 401 second-factor-invalid and does not consume the token", () => {
    const { model, clock } = fixture();
    const token = bearerOf(model.startSecondFactor("mfa-enroll"));
    const secret = model.testSecret();

    const refused = model.verify(token, { recoveryCode: "0123456789" });

    expect(refused.status).toBe(401);
    expect(typeOf(refused)).toBe("second-factor-invalid");
    expect(model.verify(token, { code: totpCode(secret, clock.now / 1000) }).status).toBe(200);
  });

  it("also rejects a recovery code the account really holds, because the token is for enrollment", () => {
    const { model } = fixture();
    const enrolling = bearerOf(model.startSecondFactor("mfa-enroll"));
    const held = (model.enroll(enrolling).body as { recoveryCodes: string[] }).recoveryCodes[0] ?? "";

    expect(model.verify(enrolling, { recoveryCode: held }).status).toBe(401);
  });

  it("completes the enrollment with a code from the secret it handed out, once", () => {
    const { model, clock } = fixture();
    const token = bearerOf(model.startSecondFactor("mfa-enroll"));
    const enrolled = model.enroll(token);

    expect(enrolled.status).toBe(201);
    expect((enrolled.body as { secret: string }).secret).toMatch(/^[A-Z2-7]{32}$/);
    const done = model.verify(token, { code: totpCode(model.testSecret(), clock.now / 1000) });
    expect(done.status).toBe(200);
    expect(done.setCookies).toHaveLength(2);
    expect(typeOf(model.verify(token, { code: totpCode(model.testSecret(), clock.now / 1000) }))).toBe("token-invalid");
  });

  it("hands out a new secret and new recovery codes at every enrollment, as the backend does [SS100]", () => {
    const { model, clock } = fixture();
    const token = bearerOf(model.startSecondFactor("mfa-enroll"));
    const first = model.enroll(token).body as { secret: string; recoveryCodes: string[] };
    const firstKey = model.testSecret();

    const second = model.enroll(bearerOf(model.startSecondFactor("mfa-enroll"))).body as { secret: string; recoveryCodes: string[] };

    expect(second.secret).toMatch(/^[A-Z2-7]{32}$/);
    expect(second.secret).not.toBe(first.secret);
    expect(second.recoveryCodes).toHaveLength(10);
    expect(second.recoveryCodes.filter((code) => first.recoveryCodes.includes(code))).toEqual([]);
    // What the control of the account hands out is the secret of the last enrollment, and the first one no longer works.
    expect(model.recoveryCodes()).toEqual(second.recoveryCodes);
    expect(model.testSecret()).not.toEqual(firstKey);
    const stale = model.startSecondFactor("mfa-enroll");
    expect(typeOf(model.verify(bearerOf(stale), { code: totpCode(firstKey, clock.now / 1000) }))).toBe("second-factor-invalid");
    expect(model.verify(bearerOf(stale), { code: totpCode(model.testSecret(), clock.now / 1000) }).status).toBe(200);
  });

  it("accepts the current code and one step either side, and refuses a wrong one", () => {
    const { model, clock } = fixture({ enrolled: true });
    const seconds = clock.now / 1000;

    for (const [offset, status] of [[0, 200], [-30, 200], [30, 200], [-90, 401]] as const) {
      const token = bearerOf(model.startSecondFactor("mfa-verify"));
      const response = model.verify(token, { code: totpCode(model.testSecret(), seconds + offset) });

      expect([offset, response.status]).toEqual([offset, status]);
    }
  });

  it("accepts a recovery code on a mfa-verify token once, then refuses it", () => {
    const { model } = fixture({ enrolled: true });
    const held = model.recoveryCodes()[0] ?? "";

    expect(model.verify(bearerOf(model.startSecondFactor("mfa-verify")), { recoveryCode: held }).status).toBe(200);
    expect(typeOf(model.verify(bearerOf(model.startSecondFactor("mfa-verify")), { recoveryCode: held }))).toBe("second-factor-invalid");
  });

  it("accepts a recovery code in capitals, as the verification screen sends it, and lists the codes as they were handed out", () => {
    const { model } = fixture({ enrolled: true });
    const held = model.recoveryCodes()[0] ?? "";

    expect(held).toBe(held.toLowerCase());
    expect(model.verify(bearerOf(model.startSecondFactor("mfa-verify")), { recoveryCode: held.toUpperCase() }).status).toBe(200);
    expect(model.recoveryCodes()).toHaveLength(9);
    expect(model.recoveryCodes()).not.toContain(held);
    expect(typeOf(model.verify(bearerOf(model.startSecondFactor("mfa-verify")), { recoveryCode: held }))).toBe("second-factor-invalid");
  });

  it("answers 400 validation-failed unless the body holds exactly one proof", () => {
    const { model } = fixture({ enrolled: true });
    const token = bearerOf(model.startSecondFactor("mfa-verify"));

    for (const body of [{}, { code: "123456", recoveryCode: "abc" }, { code: 5 }, null, "text"]) {
      const response = model.verify(token, body);

      expect([JSON.stringify(body), response.status, typeOf(response)]).toEqual([JSON.stringify(body), 400, "validation-failed"]);
    }
  });

  it("answers 401 token-expired after 300 seconds, token-invalid for an unknown token and authentication-required for none", () => {
    const { model, clock } = fixture({ enrolled: true });
    const token = bearerOf(model.startSecondFactor("mfa-verify"));
    clock.now += 301_000;

    expect(typeOf(model.verify(token, { code: "123456" }))).toBe("token-expired");
    expect(typeOf(model.verify("unknown", { code: "123456" }))).toBe("token-invalid");
    expect(typeOf(model.verify(undefined, { code: "123456" }))).toBe("authentication-required");
  });

  it("refuses to enrol with a mfa-verify token, and answers 409 mfa-already-enrolled once enrolled", () => {
    const { model } = fixture({ enrolled: true });

    expect(typeOf(model.enroll(bearerOf(model.startSecondFactor("mfa-verify"))))).toBe("token-invalid");
    const conflict = model.enroll(bearerOf(model.startSecondFactor("mfa-enroll")));
    expect(conflict.status).toBe(409);
    expect(typeOf(conflict)).toBe("mfa-already-enrolled");
  });

  it("answers the status the sign in gives for each use", () => {
    const { model } = fixture();

    expect(model.startSecondFactor("mfa-verify").body).toMatchObject({ status: "second-factor-required" });
    expect(model.startSecondFactor("mfa-enroll").body).toMatchObject({ status: "second-factor-enrollment-required" });
  });
});

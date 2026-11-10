import { randomBytes } from "node:crypto";
import { SecondFactorVerificationRequest } from "@confia/contracts/admin";
import {
  authenticatedSession,
  currentSession,
  enrollment,
  noContent,
  problem,
  secondFactorEnrollmentRequired,
  secondFactorRequired,
  type ContractResponse,
} from "../../src/test/contract-responses.ts";
import { base32Encode, isTotpValid } from "../support/totp.ts";

/**
 * The session model of the simulated API (design.md D15). It models the family of a session, the rotation
 * of the refresh with a grace (SS120), the allowed Origin, the double-submit CSRF check, the cookies with
 * the attributes of WE62 and WE63, and the restricted tokens with their use and expiry (SS121). It is a
 * simulation: it exercises the page against the shapes the backend publishes, and it is not evidence
 * about the real stack. Time is injected, and every token and secret is made at run time.
 */
export const SESSION_COOKIE = "__Secure-confia_admin_sid";
export const CSRF_COOKIE = "__Host-confia_csrf";
/** How long the previous refresh is still accepted after it was rotated. */
export const GRACE_MS = 10_000;
const RESTRICTED_TTL_MS = 300_000;
const ACCESS_TTL_MS = 15 * 60_000;
const DEFAULT_ORIGINS: readonly string[] = ["http://localhost:5173"];
const SESSION_PATH = "/api/v1/auth/sessions";

export type RestrictedUse = "mfa-verify" | "mfa-enroll";

/** A contract response plus what travels beside the body. */
export interface ModelResponse extends ContractResponse {
  readonly headers: Readonly<Record<string, string>>;
  readonly setCookies: readonly string[];
}

export interface Cookie {
  readonly name: string;
  readonly value: string;
}

/** What the model needs of a request to a cookie route (refresh, sign out). */
export interface CookieRoute {
  readonly origin: string | undefined;
  readonly cookies: readonly Cookie[];
  readonly csrfHeader: string | undefined;
}

export interface SessionModelOptions {
  readonly now?: () => number;
  readonly allowedOrigins?: readonly string[];
  /** Start with the second factor already enrolled. */
  readonly enrolled?: boolean;
}

export interface SessionModel {
  /** A signed-in session: the response and both cookies. */
  openSession(): ModelResponse;
  startSecondFactor(use: RestrictedUse): ModelResponse;
  enroll(bearer: string | undefined): ModelResponse;
  verify(bearer: string | undefined, body: unknown): ModelResponse;
  refresh(route: CookieRoute): ModelResponse;
  signOut(route: CookieRoute): ModelResponse;
  currentSession(bearer: string | undefined): ModelResponse;
  /** The key of the fictitious account, for a test to compute a code. */
  testSecret(): Uint8Array;
  recoveryCodes(): readonly string[];
}

/** A `Cookie` header into pairs. Repeated names are kept, because a repeated session cookie is refused. */
export function parseCookieHeader(header: string | undefined): Cookie[] {
  if (header === undefined || header === "") return [];
  return header.split("; ").flatMap((pair) => {
    const separator = pair.indexOf("=");
    return separator === -1 ? [] : [{ name: pair.slice(0, separator), value: pair.slice(separator + 1) }];
  });
}

const sessionCookie = (value: string, extra = ""): string =>
  `${SESSION_COOKIE}=${value}; Path=${SESSION_PATH}; HttpOnly; Secure; SameSite=Strict${extra}`;
const csrfCookie = (value: string, extra = ""): string => `${CSRF_COOKIE}=${value}; Path=/; Secure; SameSite=Strict${extra}`;
const clearedCookies = (): string[] => [sessionCookie("", "; Max-Age=0"), csrfCookie("", "; Max-Age=0")];

const token = (bytes: number): string => randomBytes(bytes).toString("base64url");

interface Family {
  revoked: boolean;
  readonly sessionId: string;
}

interface RefreshEntry {
  readonly family: Family;
  readonly csrf: string;
  rotatedAt: number | undefined;
  successor: string | undefined;
}

interface RestrictedToken {
  readonly use: RestrictedUse;
  readonly expiresAt: number;
}

export function createSessionModel(options: SessionModelOptions = {}): SessionModel {
  const clock = options.now ?? Date.now;
  const origins = options.allowedOrigins ?? DEFAULT_ORIGINS;
  const accountId = crypto.randomUUID();
  const institutionId = crypto.randomUUID();
  // The account holds the secret and the codes of its last enrollment: each call to enroll replaces them, as the backend does (SS100).
  let key = randomBytes(20);
  let recovery = new Map<string, string>();
  /** A new secret and new codes. Keyed by the capitals form: the verification screen sends a recovery code in capitals (SS65), whatever case it was handed out in. */
  function hand(): ContractResponse {
    key = randomBytes(20);
    const fresh = enrollment({ override: { secret: base32Encode(key) } });
    recovery = new Map((fresh.body as { recoveryCodes: string[] }).recoveryCodes.map((code) => [code.toUpperCase(), code]));
    return fresh;
  }
  hand();
  let enrolled = options.enrolled ?? false;

  const refreshes = new Map<string, RefreshEntry>();
  const accessTokens = new Map<string, { family: Family; expiresAt: number }>();
  const restricted = new Map<string, RestrictedToken>();

  const wrap = (response: ContractResponse, setCookies: readonly string[] = [], headers: Record<string, string> = {}): ModelResponse => ({
    ...response,
    headers,
    setCookies,
  });
  const fail = (code: Parameters<typeof problem>[0], status: number, setCookies: readonly string[] = []): ModelResponse =>
    wrap(problem(code, status), setCookies, status === 401 ? { "WWW-Authenticate": "Bearer" } : {});

  function issue(entry: RefreshEntry, value: string): ModelResponse {
    const now = clock();
    const access = token(24);
    accessTokens.set(access, { family: entry.family, expiresAt: now + ACCESS_TTL_MS });
    const response = authenticatedSession({ now: new Date(now), override: { accessToken: access } });
    return wrap(response, [sessionCookie(value), csrfCookie(entry.csrf)]);
  }

  function openSession(): ModelResponse {
    const family: Family = { revoked: false, sessionId: crypto.randomUUID() };
    const value = token(32);
    const entry: RefreshEntry = { family, csrf: token(32), rotatedAt: undefined, successor: undefined };
    refreshes.set(value, entry);
    return issue(entry, value);
  }

  /** The Origin rule and the double-submit rule of a cookie route; the first to fail decides. */
  function guard(route: CookieRoute): { refused: ModelResponse } | { value: string | undefined } {
    const refused = { refused: fail("csrf-invalid", 403) };
    if (route.origin === undefined || !origins.includes(route.origin)) return refused;
    const sessions = route.cookies.filter((cookie) => cookie.name === SESSION_COOKIE);
    if (sessions.length === 0) return { value: undefined };
    const [only] = sessions;
    if (sessions.length > 1) return refused;
    const entry = only === undefined ? undefined : refreshes.get(only.value);
    // A refresh the model never issued authenticates nothing, so there is nothing for CSRF to protect.
    if (only === undefined || entry === undefined) return { value: only?.value };
    const csrfCookies = route.cookies.filter((cookie) => cookie.name === CSRF_COOKIE);
    const [csrf] = csrfCookies;
    if (csrfCookies.length !== 1 || csrf === undefined || route.csrfHeader !== csrf.value || csrf.value !== entry.csrf) return refused;
    return { value: only.value };
  }

  function refresh(route: CookieRoute): ModelResponse {
    const checked = guard(route);
    if ("refused" in checked) return checked.refused;
    const expired = (): ModelResponse => fail("session-expired", 401, clearedCookies());
    const entry = checked.value === undefined ? undefined : refreshes.get(checked.value);
    if (checked.value === undefined || entry === undefined || entry.family.revoked) return expired();
    if (entry.rotatedAt === undefined) {
      const next = token(32);
      const successor: RefreshEntry = { family: entry.family, csrf: token(32), rotatedAt: undefined, successor: undefined };
      refreshes.set(next, successor);
      entry.rotatedAt = clock();
      entry.successor = next;
      return issue(successor, next);
    }
    if (clock() - entry.rotatedAt >= GRACE_MS) {
      // A refresh used after its grace is a reuse: the whole family ends.
      entry.family.revoked = true;
      return expired();
    }
    // Inside the grace the current cookies of the family are handed out again, not a new rotation.
    let head = checked.value;
    for (let next = entry.successor; next !== undefined; next = refreshes.get(head)?.successor) head = next;
    const current = refreshes.get(head);
    return current === undefined ? expired() : issue(current, head);
  }

  function signOut(route: CookieRoute): ModelResponse {
    const checked = guard(route);
    if ("refused" in checked) return checked.refused;
    const entry = checked.value === undefined ? undefined : refreshes.get(checked.value);
    if (entry !== undefined) entry.family.revoked = true;
    return wrap(noContent(), clearedCookies());
  }

  function currentSessionOf(bearer: string | undefined): ModelResponse {
    const found = bearer === undefined ? undefined : accessTokens.get(bearer);
    if (found === undefined || found.family.revoked || found.expiresAt <= clock()) return fail("authentication-required", 401);
    return wrap(
      currentSession({
        now: new Date(clock()),
        override: { accountId, institutionId, sessionId: found.family.sessionId },
      }),
    );
  }

  function startSecondFactor(use: RestrictedUse): ModelResponse {
    const now = clock();
    const value = token(24);
    restricted.set(value, { use, expiresAt: now + RESTRICTED_TTL_MS });
    const build = use === "mfa-verify" ? secondFactorRequired : secondFactorEnrollmentRequired;
    return wrap(build({ now: new Date(now), override: { secondFactorToken: value } }));
  }

  /** The restricted token a request carries, or the refusal that names why it cannot be used. */
  function restrictedOf(bearer: string | undefined): { token: RestrictedToken; value: string } | { refused: ModelResponse } {
    if (bearer === undefined) return { refused: fail("authentication-required", 401) };
    const found = restricted.get(bearer);
    if (found === undefined) return { refused: fail("token-invalid", 401) };
    if (found.expiresAt <= clock()) return { refused: fail("token-expired", 401) };
    return { token: found, value: bearer };
  }

  function enroll(bearer: string | undefined): ModelResponse {
    const found = restrictedOf(bearer);
    if ("refused" in found) return found.refused;
    if (found.token.use !== "mfa-enroll") return fail("token-invalid", 401);
    if (enrolled) return fail("mfa-already-enrolled", 409);
    return wrap(hand());
  }

  function verify(bearer: string | undefined, body: unknown): ModelResponse {
    const found = restrictedOf(bearer);
    if ("refused" in found) return found.refused;
    const parsed = SecondFactorVerificationRequest.safeParse(body);
    const proof = parsed.success ? parsed.data : undefined;
    const given = proof === undefined ? 0 : Number(proof.code !== undefined) + Number(proof.recoveryCode !== undefined);
    if (proof === undefined || given !== 1) {
      return wrap(problem("validation-failed", 400, { errors: [{ field: "code", reason: "exactly-one-proof" }] }));
    }
    const invalid = (): ModelResponse => fail("second-factor-invalid", 401);
    if (proof.code !== undefined) {
      if (!isTotpValid(key, proof.code, clock() / 1000)) return invalid();
    } else {
      // The first verification of a credential takes a code and nothing else, and the token is not spent.
      if (found.token.use === "mfa-enroll") return invalid();
      if (proof.recoveryCode === undefined || !recovery.delete(proof.recoveryCode.toUpperCase())) return invalid();
    }
    restricted.delete(found.value);
    if (found.token.use === "mfa-enroll") enrolled = true;
    return openSession();
  }

  return {
    openSession,
    startSecondFactor,
    enroll,
    verify,
    refresh,
    signOut,
    currentSession: currentSessionOf,
    testSecret: () => Uint8Array.from(key),
    recoveryCodes: () => [...recovery.values()],
  };
}

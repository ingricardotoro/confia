import {
  AuthenticatedSessionResponse,
  CurrentSessionResponse,
  EnrollmentResponse,
  LoginResponse,
  type AuthenticatedSessionResponseOutput,
  type CurrentSessionResponseOutput,
  type EnrollmentResponseOutput,
  type SecondFactorPendingResponseOutput,
} from "@confia/contracts/admin";
import { request, requestEmpty, type ApiResult, type ResponseSchema } from "./http.ts";

/**
 * The six operations of access (design.md D6). Each takes the token or the CSRF value it needs as a
 * parameter: this module knows no session, keeps nothing between calls and never reads a cookie. None
 * of them moves money, so none sends an `Idempotency-Key`; the account and the credentials are not a
 * ledger entry (AW39).
 */
const SESSIONS = "/api/v1/auth/sessions";
const CSRF_HEADER = "X-CSRF-Token";

/** The sign in answer, one member per `status` so each is a distinct type to narrow on (AW42). */
export type LoginOutcome =
  | AuthenticatedSessionResponseOutput
  | (Omit<SecondFactorPendingResponseOutput, "status"> & { readonly status: "second-factor-required" })
  | (Omit<SecondFactorPendingResponseOutput, "status"> & { readonly status: "second-factor-enrollment-required" });

/** A code from the authenticator or a recovery code: exactly one, never both (AW38). */
export type SecondFactorProof =
  | { readonly kind: "code"; readonly value: string }
  | { readonly kind: "recovery-code"; readonly value: string };

export interface Credentials {
  readonly identifier: string;
  readonly password: string;
}

const loginOutcomeSchema: ResponseSchema<LoginOutcome> = {
  safeParse(input) {
    const parsed = LoginResponse.safeParse(input);
    if (!parsed.success) return { success: false };
    const { data } = parsed;
    // The second member of the union carries both pending statuses; this splits them.
    return { success: true, data: data.status === "authenticated" ? data : { ...data, status: data.status } };
  },
};

const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });

/** `POST /auth/sessions`: only the two fields are sent, whatever else the caller's object holds. */
export function logIn(credentials: Credentials): Promise<ApiResult<LoginOutcome>> {
  return request({
    method: "POST",
    path: SESSIONS,
    body: { identifier: credentials.identifier, password: credentials.password },
    schema: loginOutcomeSchema,
  });
}

/**
 * `POST /auth/sessions/refresh`. The caller reads the CSRF value and passes it; with no value there is
 * nothing to send, and the session module makes no request then.
 */
export function refreshSession(csrfToken: string): Promise<ApiResult<AuthenticatedSessionResponseOutput>> {
  return request({
    method: "POST",
    path: `${SESSIONS}/refresh`,
    headers: { [CSRF_HEADER]: csrfToken },
    schema: AuthenticatedSessionResponse,
  });
}

/**
 * `DELETE /auth/sessions/current`. With no CSRF value the header is left out and none is invented: the
 * server answers, and its answer is the caller's to read. Any 2xx is `ok`; the caller checks for `204`.
 */
export function endCurrentSession(csrfToken: string | undefined): Promise<ApiResult<undefined>> {
  return requestEmpty({
    method: "DELETE",
    path: `${SESSIONS}/current`,
    ...(csrfToken === undefined ? {} : { headers: { [CSRF_HEADER]: csrfToken } }),
  });
}

/** `GET /auth/sessions/current`, with the access token and no CSRF header. */
export function getCurrentSession(accessToken: string): Promise<ApiResult<CurrentSessionResponseOutput>> {
  return request({
    method: "GET",
    path: `${SESSIONS}/current`,
    headers: bearer(accessToken),
    schema: CurrentSessionResponse,
  });
}

/** `POST /auth/mfa/enrollments`, with the restricted token and no body. */
export function enrollSecondFactor(restrictedToken: string): Promise<ApiResult<EnrollmentResponseOutput>> {
  return request({
    method: "POST",
    path: "/api/v1/auth/mfa/enrollments",
    headers: bearer(restrictedToken),
    schema: EnrollmentResponse,
  });
}

/** `POST /auth/mfa/verifications`, with the restricted token and a body of exactly one proof. */
export function completeSecondFactor(
  restrictedToken: string,
  proof: SecondFactorProof,
): Promise<ApiResult<AuthenticatedSessionResponseOutput>> {
  return request({
    method: "POST",
    path: "/api/v1/auth/mfa/verifications",
    headers: bearer(restrictedToken),
    body: proof.kind === "code" ? { code: proof.value } : { recoveryCode: proof.value },
    schema: AuthenticatedSessionResponse,
  });
}

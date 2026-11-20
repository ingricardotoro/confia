import {
  RecordedMovementResponse,
  SessionPageResponse,
  SessionResponse,
  type ApproveCloseRequest,
  type CloseSessionRequest,
  type OpenSessionRequest,
  type RecordMovementRequest,
  type RecordedMovementResponseOutput,
  type SessionPageResponseOutput,
  type SessionResponseOutput,
} from "@confia/contracts/admin";
import { request, type ApiResult, type ResponseSchema } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/cashbox` (cashbox-sessions cut). Each takes the access token as a parameter, like the operations of
 * access: this module knows no session. The four writes (open, move, close, approve) change the cash of the institution, so each
 * carries the `Idempotency-Key` of the person's intent. Every amount travels as a `{ amount, currency }` string the server computed
 * and rounded (the expected cash, the difference): nothing here, or after it, adds, subtracts or rounds one. Paging, order and
 * filters are the server's and travel as query parameters.
 */
const { list } = resourceClient("/api/v1/cashbox");

export type { PageParams };

export type CashSession = SessionResponseOutput;
export type CashSessionPage = SessionPageResponseOutput;
export type CashSessionStatus = SessionResponseOutput["status"];
export type CashMovementType = RecordMovementRequest["type"];
export type RecordedMovement = RecordedMovementResponseOutput;

export interface CashSessionListParams extends PageParams {
  readonly status?: CashSessionStatus | undefined;
  /** A staff account. */
  readonly cashier?: string | undefined;
  /** `yyyy-MM-dd`: sessions opened from this day included. */
  readonly from?: string | undefined;
  /** `yyyy-MM-dd`: sessions opened up to this day included. */
  readonly to?: string | undefined;
}

const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });
const sessionPath = (id: string): string => `/api/v1/cashbox/sessions/${encodeURIComponent(id)}`;
const withKey = (token: string, idempotencyKey: string): Readonly<Record<string, string>> => ({ ...bearer(token), "Idempotency-Key": idempotencyKey });

/**
 * The server writes an absent value as `null`, while the generated schema of an optional member accepts only an absent key. A null is
 * the same fact as an absent key, so it is dropped before the schema reads the body; nothing else changes.
 */
function withoutNulls(value: unknown): unknown {
  if (Array.isArray(value)) return (value as unknown[]).map(withoutNulls);
  if (typeof value === "object" && value !== null) {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([, member]) => member !== null)
        .map(([name, member]) => [name, withoutNulls(member)]),
    );
  }
  return value;
}

function lenient<T>(schema: ResponseSchema<T>): ResponseSchema<T> {
  return { safeParse: (input) => schema.safeParse(withoutNulls(input)) };
}

/** One page of sessions, as the server pages, filters and orders it (newest first when no order is asked). */
export const listCashSessions = (token: string, params: CashSessionListParams): Promise<ApiResult<CashSessionPage>> =>
  list("sessions", lenient(SessionPageResponse), token, params);

/** The open session of the person, with its expected cash and movements; `404` while there is none. */
export const getCurrentCashSession = (token: string): Promise<ApiResult<CashSession>> =>
  request({ method: "GET", path: "/api/v1/cashbox/sessions/current", headers: bearer(token), schema: lenient(SessionResponse) });

/** One session with its movements, its close and its approval: the base of the printable report. */
export const getCashSession = (token: string, id: string): Promise<ApiResult<CashSession>> =>
  request({ method: "GET", path: sessionPath(id), headers: bearer(token), schema: lenient(SessionResponse) });

/** Opens the session of the person with the cash in the drawer. */
export const openCashSession = (token: string, body: OpenSessionRequest, idempotencyKey: string): Promise<ApiResult<CashSession>> =>
  request({ method: "POST", path: "/api/v1/cashbox/sessions", headers: withKey(token, idempotencyKey), body, schema: lenient(SessionResponse) });

/** A deposit, a withdrawal or an adjustment of an open session; the answer holds the expected cash after it. */
export const recordCashMovement = (token: string, id: string, body: RecordMovementRequest, idempotencyKey: string): Promise<ApiResult<RecordedMovement>> =>
  request({ method: "POST", path: `${sessionPath(id)}/movements`, headers: withKey(token, idempotencyKey), body, schema: lenient(RecordedMovementResponse) });

/** Closes the session with the counted cash; the server computes the difference and the status (`pending_approval` when there is one). */
export const closeCashSession = (token: string, id: string, body: CloseSessionRequest, idempotencyKey: string): Promise<ApiResult<CashSession>> =>
  request({ method: "POST", path: `${sessionPath(id)}/close`, headers: withKey(token, idempotencyKey), body, schema: lenient(SessionResponse) });

/** Approves the close of a session that has a difference. The server refuses the cashier of the session. */
export const approveCashSessionClose = (token: string, id: string, body: ApproveCloseRequest, idempotencyKey: string): Promise<ApiResult<CashSession>> =>
  request({ method: "POST", path: `${sessionPath(id)}/approve`, headers: withKey(token, idempotencyKey), body, schema: lenient(SessionResponse) });

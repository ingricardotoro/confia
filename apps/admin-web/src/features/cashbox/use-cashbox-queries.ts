import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ApproveCloseRequest, CloseSessionRequest, OpenSessionRequest, RecordMovementRequest } from "@confia/contracts/admin";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import type { ApiResult } from "../../shared/api/http.ts";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import {
  approveCashSessionClose,
  closeCashSession,
  getCashSession,
  getCurrentCashSession,
  listCashSessions,
  openCashSession,
  recordCashMovement,
  type CashSession,
  type CashSessionListParams,
  type RecordedMovement,
} from "../../shared/api/cashbox-operations.ts";
import { cashboxKeys } from "./cashbox-keys.ts";

/**
 * The open session of the person, with its expected cash and its movements. A read, never optimistic. A `404` means the person has
 * no open session: it is an answer (the form to open one), not a failure worth three tries.
 */
export function useCurrentCashSession() {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: cashboxKeys.current(),
    queryFn: () => send((token) => getCurrentCashSession(token)),
    retry: false,
  });
}

/** One page of sessions, as the server pages, filters and orders it. The page on screen stays until the next arrives. */
export function useCashSessionList(params: CashSessionListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: cashboxKeys.list(params),
    queryFn: () => send((token) => listCashSessions(token, params)),
    placeholderData: keepPreviousData,
  });
}

/** One session with its movements, its close and its approval: the base of the printable report. */
export function useCashSessionDetail(id: string) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: cashboxKeys.detail(id),
    queryFn: () => send((token) => getCashSession(token, id)),
  });
}

/** The answers after which what is on screen is out of date: the session is no longer in the state the screen assumed, or it is gone. */
const STALE_CODES: ReadonlySet<string> = new Set(["cash-session-already-open", "cash-session-not-open", "cash-session-not-awaiting-approval", "resource-not-found"]);

/**
 * What every write of the cashbox has in common. They move cash, so nothing is optimistic and the `Idempotency-Key` of the intent
 * travels with every try of it. When the server confirms, everything the cashbox shows is read again (the session of the person, every
 * list and every detail: the expected cash, the status and the movements are the server's); this cut writes no ledger entry, so there is
 * no statement or balance to read again. When the server says the session is no longer as the screen assumed, the same read happens, so
 * the screen shows what is true.
 */
function useCashboxWrite<Variables, Result>(operation: (token: string, variables: Variables) => Promise<ApiResult<Result>>) {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<Result, Error, Variables>({
    mutationFn: (variables) => send((token) => operation(token, variables)),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: cashboxKeys.all });
    },
    onError: async (error) => {
      if (error instanceof ApiFailure && error.result.kind === "problem" && STALE_CODES.has(error.result.problem.code)) {
        await queryClient.invalidateQueries({ queryKey: cashboxKeys.all });
      }
    },
  });
}

export interface OpenCashVariables {
  readonly body: OpenSessionRequest;
  readonly idempotencyKey: string;
}

export interface SessionWriteVariables<Body> {
  readonly sessionId: string;
  readonly body: Body;
  readonly idempotencyKey: string;
}

export const useOpenCashSession = () =>
  useCashboxWrite<OpenCashVariables, CashSession>((token, { body, idempotencyKey }) => openCashSession(token, body, idempotencyKey));

export const useRecordCashMovement = () =>
  useCashboxWrite<SessionWriteVariables<RecordMovementRequest>, RecordedMovement>((token, { sessionId, body, idempotencyKey }) =>
    recordCashMovement(token, sessionId, body, idempotencyKey),
  );

export const useCloseCashSession = () =>
  useCashboxWrite<SessionWriteVariables<CloseSessionRequest>, CashSession>((token, { sessionId, body, idempotencyKey }) =>
    closeCashSession(token, sessionId, body, idempotencyKey),
  );

export const useApproveCashSessionClose = () =>
  useCashboxWrite<SessionWriteVariables<ApproveCloseRequest>, CashSession>((token, { sessionId, body, idempotencyKey }) =>
    approveCashSessionClose(token, sessionId, body, idempotencyKey),
  );

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { RegisterPaymentRequest, RejectPaymentRequest } from "@confia/contracts/admin";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import { getCurrentCashSession } from "../../shared/api/cashbox-operations.ts";
import { getStudentStatement, listStudentCharges, type StudentChargesParams } from "../../shared/api/charges-operations.ts";
import type { ApiResult } from "../../shared/api/http.ts";
import {
  confirmPayment,
  getPayment,
  listPayments,
  registerPayment,
  rejectPayment,
  type Payment,
  type PaymentListParams,
} from "../../shared/api/payments-operations.ts";
import { cashboxKeys } from "../../shared/query-keys/cashbox-keys.ts";
import { statementKeys, studentChargeKeys } from "../../shared/query-keys/charge-read-keys.ts";
import { PAYMENT_CURRENCY } from "./payments-model.ts";
import { paymentKeys } from "./payments-keys.ts";

/** One page of payments, as the server pages, filters and orders it. The page on screen stays until the next arrives. */
export function usePaymentList(params: PaymentListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: paymentKeys.list(params),
    queryFn: () => send((token) => listPayments(token, params)),
    placeholderData: keepPreviousData,
  });
}

/** One payment with its allocations and its decision. */
export function usePaymentDetail(id: string) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: paymentKeys.detail(id),
    queryFn: () => send((token) => getPayment(token, id)),
  });
}

/**
 * The charges of a student that the server says have something outstanding, in the order in which a payment is allocated (no sort is sent: the
 * server orders them). The browser does not judge which charge is pending; it pages what the server filtered. It shares its keys with the charges
 * of the student, so a payment that is written reads it again. Nothing is read until a student is chosen.
 */
export const PAYABLE_CHARGES_SIZE = 10;
export const payableChargesParams = (page: number): StudentChargesParams => ({ page, size: PAYABLE_CHARGES_SIZE, status: "ACTIVE", outstanding: true });

export function usePayableCharges(studentId: string | undefined, page: number) {
  const send = useAuthorizedSend();
  const params = payableChargesParams(page);
  return useQuery({
    queryKey: studentChargeKeys.list(studentId ?? "", params),
    queryFn: () => send((token) => listStudentCharges(token, studentId ?? "", params)),
    enabled: studentId !== undefined,
    placeholderData: keepPreviousData,
  });
}

/** What the statement says the student owes now (its closing balance, as the server derived it from the ledger). One line of the statement is enough. */
export const BALANCE_PARAMS = { currency: PAYMENT_CURRENCY, page: 0, size: 1 } as const;

export function useStudentBalance(studentId: string | undefined) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: statementKeys.detail(studentId ?? "", BALANCE_PARAMS),
    queryFn: () => send((token) => getStudentStatement(token, studentId ?? "", BALANCE_PARAMS)),
    enabled: studentId !== undefined,
  });
}

/**
 * The open session of the person, so cash is not offered with no cashbox open. It shares its key with the cashbox screen, so opening or closing
 * the cashbox there is seen here. A `404` means there is none: it is an answer, not a failure worth three tries. The server decides in the end.
 */
export function useCashSessionPresence(enabled: boolean) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: cashboxKeys.current(),
    queryFn: () => send((token) => getCurrentCashSession(token)),
    enabled,
    retry: false,
  });
}

/** The answers after which what is on screen is out of date: the payment is no longer declared, the cash session is gone, or the payment does not exist. */
const STALE_CODES: ReadonlySet<string> = new Set(["payment-not-declared", "cash-session-not-open", "resource-not-found"]);

/**
 * What every write of the payments has in common. They move money, so nothing is optimistic and the `Idempotency-Key` of the intent travels with
 * every try of it. When the server confirms, everything derived from the ledger is read again: the payments (the detail and the lists), the
 * statements (the balance and the credit line), the charges of the student (what each has paid and owes) and the cashbox (the expected cash that
 * a cash payment raised). The indicators of the dashboard have no query yet; when they do, their prefix is invalidated here too. When the
 * server says the payment or the cashbox is no longer as the screen assumed, the same read happens, so the screen shows what is true.
 */
function usePaymentWrite<Variables>(operation: (token: string, variables: Variables) => Promise<ApiResult<Payment>>) {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<Payment, Error, Variables>({
    mutationFn: (variables) => send((token) => operation(token, variables)),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: paymentKeys.all }),
        queryClient.invalidateQueries({ queryKey: statementKeys.all }),
        queryClient.invalidateQueries({ queryKey: studentChargeKeys.all }),
        queryClient.invalidateQueries({ queryKey: cashboxKeys.all }),
      ]);
    },
    onError: async (error) => {
      if (error instanceof ApiFailure && error.result.kind === "problem" && STALE_CODES.has(error.result.problem.code)) {
        await Promise.all([queryClient.invalidateQueries({ queryKey: paymentKeys.all }), queryClient.invalidateQueries({ queryKey: cashboxKeys.all })]);
      }
    },
  });
}

export interface RegisterPaymentVariables {
  readonly body: RegisterPaymentRequest;
  readonly idempotencyKey: string;
}

export interface DecisionVariables<Body> {
  readonly paymentId: string;
  readonly body: Body;
  readonly idempotencyKey: string;
}

export const useRegisterPayment = () =>
  usePaymentWrite<RegisterPaymentVariables>((token, { body, idempotencyKey }) => registerPayment(token, body, idempotencyKey));

export const useConfirmPayment = () =>
  usePaymentWrite<DecisionVariables<undefined>>((token, { paymentId, idempotencyKey }) => confirmPayment(token, paymentId, idempotencyKey));

export const useRejectPayment = () =>
  usePaymentWrite<DecisionVariables<RejectPaymentRequest>>((token, { paymentId, body, idempotencyKey }) => rejectPayment(token, paymentId, body, idempotencyKey));

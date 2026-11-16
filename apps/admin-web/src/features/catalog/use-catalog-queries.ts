import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import { listPaymentConcepts, type PaymentConceptListParams } from "../../shared/api/catalog-operations.ts";
import { paymentConceptKeys } from "./catalog-keys.ts";
import { writePaymentConcept, type PaymentConcept, type PaymentConceptSave } from "./payment-concept-form.ts";

/** A listing of payment concepts, as the server pages, orders and filters it: the rows come back as they were served. */
export function usePaymentConceptList(params: PaymentConceptListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: paymentConceptKeys.list(params),
    queryFn: () => send((token) => listPaymentConcepts(token, params)),
  });
}

/**
 * A write of one payment concept. It is not financial (a concept has no price), so nothing is optimistic and no
 * `Idempotency-Key` is sent, but it never repeats by itself (the app's mutations do not retry). After a write every
 * list of the catalog is invalidated: the server is the truth about what is listed.
 */
export function usePaymentConceptWrite() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<PaymentConcept, Error, PaymentConceptSave>({
    mutationFn: (save) => send((token) => writePaymentConcept(token, save)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: paymentConceptKeys.all }),
  });
}

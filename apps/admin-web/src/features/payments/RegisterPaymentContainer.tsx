import { useNavigate } from "@tanstack/react-router";
import { useRef, useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { intentFor, type IdempotentIntent } from "../../shared/forms/idempotent-intent.ts";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { MY_CASHBOX_PATH } from "../../shared/navigation/cashbox-paths.ts";
import { studentStatementPath } from "../../shared/navigation/statement-paths.ts";
import { studentChargesPath } from "../../shared/navigation/student-charges-paths.ts";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { useDebouncedSearch } from "../../shared/resource-screen/use-debounced-search.ts";
import { useStudentSearch } from "../../shared/student-lookups/use-student-search.ts";
import type { StatementStudentSearchState } from "../../shared/student-lookups/components/StatementStudentPicker.tsx";
import { REGISTER_CONTROL_IDS, RegisterPaymentView, type CashboxPresence } from "./components/RegisterPaymentView.tsx";
import type { PayableChargesState, StudentBalanceState } from "./components/PayableChargesPanel.tsx";
import { paymentFailureOf } from "./payments-failure.ts";
import { isTransfer, PAYMENT_CURRENCY, registerReasonsOf, todayIso, type RegisterField } from "./payments-model.ts";
import { paymentPath } from "./payments-paths.ts";
import type { Payment } from "./payments-types.ts";
import { useCashSessionPresence, usePayableCharges, useRegisterPayment, useStudentBalance } from "./use-payment-queries.ts";

/** The student chosen in this visit: the form only holds the id, so the name is remembered while it is the same student. */
interface NamedStudent {
  readonly id: string;
  readonly label: string;
}

/**
 * The register of a payment. The student chosen gets their charges and balance as the server derives them; the method, amount, day and (for a
 * transfer) reference and bank go to the server in one request carrying an `Idempotency-Key` made once for the intent: it travels again unchanged
 * if the answer never arrived, and a changed body is a new intent with a new key. One try at a time and nothing optimistic. When the server
 * confirms, its result (what was applied to each charge and what stayed in favor) replaces the form, and every read derived from the ledger is
 * read again. Cash is not offered with no open cashbox; the server still decides, and its refusal is told.
 */
export function RegisterPaymentContainer() {
  // A new form for each payment, so nothing typed for one is shown for the next.
  const [attempt, setAttempt] = useState(0);
  return (
    <RegisterForm
      key={attempt}
      onNew={() => {
        setAttempt((current) => current + 1);
      }}
    />
  );
}

function RegisterForm({ onNew }: { readonly onNew: () => void }) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const registering = useRegisterPayment();
  const intent = useRef<IdempotentIntent | undefined>(undefined);
  const box = useDebouncedSearch();
  const found = useStudentSearch(box.text);
  const [named, setNamed] = useState<NamedStudent>();
  const [result, setResult] = useState<Payment>();
  const [chargesPage, setChargesPage] = useState(0);
  const go = (to: string) => void navigate({ to });

  const form = useAccessForm<RegisterField>({
    fields: fieldSpecs(
      REGISTER_CONTROL_IDS,
      { studentId: "", method: "CASH", amount: "", receivedOn: todayIso(), reference: "", bankName: "", notes: "" },
      ["amount", "receivedOn", "reference", "bankName", "notes"],
    ),
    validate: registerReasonsOf,
    submit: async (values) => {
      const transfer = isTransfer(values.method);
      const body = {
        studentId: values.studentId,
        method: transfer ? ("BANK_TRANSFER" as const) : ("CASH" as const),
        amount: { amount: values.amount, currency: PAYMENT_CURRENCY },
        receivedOn: values.receivedOn,
        ...(transfer ? { reference: values.reference, bankName: values.bankName } : {}),
        ...(values.notes === "" ? {} : { notes: values.notes }),
      };
      const current = intentFor(intent.current, JSON.stringify(["register", body]));
      intent.current = current;
      try {
        const payment = await registering.mutateAsync({ body, idempotencyKey: current.idempotencyKey });
        intent.current = undefined;
        setResult(payment);
        return undefined;
      } catch (error) {
        const { failure, outcomeUnknown } = paymentFailureOf(error);
        if (!outcomeUnknown) intent.current = undefined;
        return failure;
      }
    },
    waitingKey: "payments.register.submitting",
  });

  const studentId = form.values.studentId === "" ? undefined : form.values.studentId;
  const cash = !isTransfer(form.values.method);
  const charges = usePayableCharges(studentId, chargesPage);
  const statement = useStudentBalance(studentId);
  const session = useCashSessionPresence(cash && result === undefined);

  const chargesState: PayableChargesState | undefined = (() => {
    if (studentId === undefined) return undefined;
    if (charges.isError) {
      const failure = loadFailureOf(charges.error);
      return {
        kind: "error",
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void charges.refetch(),
      };
    }
    if (charges.data === undefined) return { kind: "loading" };
    const { items, page, size, total } = charges.data;
    return { kind: "ready", rows: items, pagination: { page, size, total, onPageChange: setChargesPage, busy: charges.isPlaceholderData } };
  })();

  const balanceState: StudentBalanceState = (() => {
    if (statement.isError) return { kind: "unavailable" };
    const section = statement.data?.sections.find((candidate) => candidate.currency === PAYMENT_CURRENCY);
    if (statement.data === undefined) return { kind: "loading" };
    return section === undefined ? { kind: "unavailable" } : { kind: "ready", amount: section.closingBalance };
  })();

  const cashbox: CashboxPresence = (() => {
    if (!cash || session.isPending) return "unknown";
    if (session.isError) {
      const status = session.error instanceof ApiFailure && session.error.result.kind === "problem" ? session.error.result.problem.status : undefined;
      return status === 404 ? "none" : "unknown";
    }
    return "open";
  })();

  const searchState: StatementStudentSearchState =
    box.waiting ? "waiting" : found.isFetching ? "loading" : found.isError ? "failed" : found.data !== undefined && box.text !== undefined ? "ready" : "idle";

  return (
    <RegisterPaymentView
      form={form}
      values={form.values}
      fieldErrors={form.fieldErrors}
      onChange={(field, value) => {
        form.onChange(field, value);
        // Cash is received the day of the session and no other: going back to cash puts the date back on today.
        if (field === "method" && value === "CASH") form.onChange("receivedOn", todayIso());
      }}
      onBlur={form.onBlur}
      picker={{
        controlId: REGISTER_CONTROL_IDS.studentId,
        draft: box.draft,
        onDraftChange: box.setDraft,
        tooShort: box.tooShort,
        search: searchState,
        results: found.data?.items ?? [],
        onRetry: () => void found.refetch(),
        chosen: undefined,
        chosenPending: false,
        onChoose: (student) => {
          box.setDraft("");
          setNamed({ id: student.id, label: t("charges.statement.picker.optionText", { name: `${student.lastName}, ${student.firstName}`, code: student.studentCode }) });
          setChargesPage(0);
          form.onChange("studentId", student.id);
        },
        onClear: () => undefined,
      }}
      chosen={
        studentId === undefined
          ? undefined
          : {
              label: named?.id === studentId ? named.label : undefined,
              onChange: () => {
                setNamed(undefined);
                setChargesPage(0);
                form.onChange("studentId", "");
              },
            }
      }
      charges={chargesState}
      balance={balanceState}
      cashbox={cashbox}
      result={result}
      onGoToCashbox={() => {
        go(MY_CASHBOX_PATH);
      }}
      onViewCharges={() => {
        if (studentId !== undefined) go(studentChargesPath(studentId));
      }}
      onNew={onNew}
      onViewPayment={(payment) => {
        go(paymentPath(payment.id));
      }}
      onViewStatement={(payment) => {
        go(studentStatementPath(payment.studentId));
      }}
    />
  );
}

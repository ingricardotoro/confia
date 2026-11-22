import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import { studentStatementPath } from "../../shared/navigation/statement-paths.ts";
import type { ResourceNotice } from "../../shared/resource-screen/components/ResourceScreen.tsx";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { ConfirmPaymentContainer, RejectPaymentContainer } from "./DecisionContainers.tsx";
import { PaymentDetailView, type PaymentDetailState } from "./components/PaymentDetailView.tsx";
import { PAYMENTS_PATHS } from "./payments-paths.ts";
import { usePaymentDetail } from "./use-payment-queries.ts";

export interface PaymentDetailContainerProps {
  readonly paymentId: string;
}

type Decision = "confirm" | "reject";

/**
 * One payment with its allocations and its decision. A declared transfer offers Confirm and Reject, each an explicit dialog, only to a session
 * with `payments:approve`. The interface cannot know who registered the payment, so the server still decides: it refuses that person and anyone
 * without the permission, and the dialog says so. When the server confirms, the payment is read again before the dialog closes, and the person is told.
 */
export function PaymentDetailContainer({ paymentId }: PaymentDetailContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const canApprove = usePermissions().can("payments:approve");
  const detail = usePaymentDetail(paymentId);
  const [deciding, setDeciding] = useState<Decision>();
  const [notice, setNotice] = useState<ResourceNotice>();

  function stateOf(): PaymentDetailState {
    if (detail.data !== undefined) return { kind: "ready", payment: detail.data };
    if (detail.isError) {
      const status = detail.error instanceof ApiFailure && detail.error.result.kind === "problem" ? detail.error.result.problem.status : undefined;
      if (status === 404) return { kind: "blocked", title: t("payments.detail.notFoundTitle"), description: t("payments.detail.notFoundDescription") };
      if (status === 403) return { kind: "blocked", title: t("payments.detail.forbiddenTitle"), description: t("payments.detail.forbiddenDescription") };
      const failure = loadFailureOf(detail.error);
      return {
        kind: "error",
        title: t("payments.detail.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void detail.refetch(),
      };
    }
    return { kind: "loading" };
  }

  const state = stateOf();
  const payment = state.kind === "ready" ? state.payment : undefined;
  const decidable = payment?.status === "declared" && canApprove;
  const open = (decision: Decision) => () => {
    setNotice(undefined);
    setDeciding(decision);
  };

  return (
    <>
      <PaymentDetailView
        state={state}
        backHref={PAYMENTS_PATHS.list}
        onBack={() => void navigate({ to: PAYMENTS_PATHS.list })}
        notice={notice}
        onConfirm={decidable ? open("confirm") : undefined}
        onReject={decidable ? open("reject") : undefined}
        onViewStatement={(studentId) => void navigate({ to: studentStatementPath(studentId) })}
      />
      {payment === undefined || !decidable || deciding === undefined ? null : deciding === "confirm" ? (
        <ConfirmPaymentContainer
          payment={payment}
          onClose={() => {
            setDeciding(undefined);
          }}
          onConfirmed={() => {
            setNotice({ tone: "success", message: t("payments.confirm.confirmedNotice") });
            setDeciding(undefined);
          }}
        />
      ) : (
        <RejectPaymentContainer
          payment={payment}
          onClose={() => {
            setDeciding(undefined);
          }}
          onRejected={() => {
            setNotice({ tone: "success", message: t("payments.reject.rejectedNotice") });
            setDeciding(undefined);
          }}
        />
      )}
    </>
  );
}

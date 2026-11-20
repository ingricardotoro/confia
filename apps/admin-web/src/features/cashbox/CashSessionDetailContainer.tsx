import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import type { ResourceNotice } from "../../shared/resource-screen/components/ResourceScreen.tsx";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { ApproveCloseContainer } from "./ApproveCloseContainer.tsx";
import { CASHBOX_PATHS } from "./cashbox-paths.ts";
import { CashSessionDetailView, type CashSessionDetailState } from "./components/CashSessionDetailView.tsx";
import { useCashSessionDetail } from "./use-cashbox-queries.ts";

export interface CashSessionDetailContainerProps {
  readonly sessionId: string;
}

/**
 * One session with its movements, its close and its approval, which is also the closing report (the view has print styles). The approval of a
 * session that awaits one opens an explicit dialog; the interface cannot know who may approve, so the action is offered and the server decides:
 * it refuses the cashier of the session and anyone without the permission, and the dialog says so.
 */
export function CashSessionDetailContainer({ sessionId }: CashSessionDetailContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const detail = useCashSessionDetail(sessionId);
  const [approving, setApproving] = useState(false);
  const [notice, setNotice] = useState<ResourceNotice>();

  function stateOf(): CashSessionDetailState {
    if (detail.data !== undefined) return { kind: "ready", session: detail.data };
    if (detail.isError) {
      const status = detail.error instanceof ApiFailure && detail.error.result.kind === "problem" ? detail.error.result.problem.status : undefined;
      if (status === 404) return { kind: "blocked", title: t("cashbox.detail.notFoundTitle"), description: t("cashbox.detail.notFoundDescription") };
      if (status === 403) return { kind: "blocked", title: t("cashbox.detail.forbiddenTitle"), description: t("cashbox.detail.forbiddenDescription") };
      const failure = loadFailureOf(detail.error);
      return {
        kind: "error",
        title: t("cashbox.detail.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void detail.refetch(),
      };
    }
    return { kind: "loading" };
  }

  const state = stateOf();
  const session = state.kind === "ready" ? state.session : undefined;
  const awaiting = session?.status === "pending_approval" && session.close !== undefined;

  return (
    <>
      <CashSessionDetailView
        state={state}
        backHref={CASHBOX_PATHS.sessions}
        onBack={() => void navigate({ to: CASHBOX_PATHS.sessions })}
        notice={notice}
        onApprove={
          awaiting
            ? () => {
                setNotice(undefined);
                setApproving(true);
              }
            : undefined
        }
        onPrint={() => {
          window.print();
        }}
      />
      {approving && session !== undefined && awaiting ? (
        <ApproveCloseContainer
          session={session}
          onClose={() => {
            setApproving(false);
          }}
          onApproved={() => {
            setNotice({ tone: "success", message: t("cashbox.approve.approvedNotice") });
            setApproving(false);
          }}
        />
      ) : null}
    </>
  );
}

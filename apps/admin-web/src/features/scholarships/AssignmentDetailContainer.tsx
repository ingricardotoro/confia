import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import type { ResourceNotice } from "../../shared/resource-screen/components/ResourceScreen.tsx";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { ApproveAssignmentContainer, RejectAssignmentContainer, RevokeAssignmentContainer } from "./AssignmentDecisionContainers.tsx";
import { AssignmentDetailView, type AssignmentDetailState } from "./components/AssignmentDetailView.tsx";
import { DISCOUNT_PATHS } from "./scholarships-paths.ts";
import { useAssignmentDetail } from "./use-assignment-queries.ts";

export interface AssignmentDetailContainerProps {
  readonly assignmentId: string;
}

type Decision = "approve" | "reject" | "revoke";

/**
 * One assignment with its decision. A pending assignment offers Approve and Reject (`scholarships:approve`) and an approved one offers Revoke
 * (`scholarships:void`), each an explicit dialog. The interface cannot know who requested the assignment, so the server still decides: it refuses that
 * person and anyone without the permission, and the dialog says so. When the server confirms, the assignment is read again before the dialog closes,
 * and the person is told.
 */
export function AssignmentDetailContainer({ assignmentId }: AssignmentDetailContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const { can } = usePermissions();
  const detail = useAssignmentDetail(assignmentId);
  const [deciding, setDeciding] = useState<Decision>();
  const [notice, setNotice] = useState<ResourceNotice>();

  function stateOf(): AssignmentDetailState {
    if (detail.data !== undefined) return { kind: "ready", assignment: detail.data };
    if (detail.isError) {
      const status = detail.error instanceof ApiFailure && detail.error.result.kind === "problem" ? detail.error.result.problem.status : undefined;
      if (status === 404) return { kind: "blocked", title: t("scholarships.assignments.detail.notFoundTitle"), description: t("scholarships.assignments.detail.notFoundDescription") };
      if (status === 403) return { kind: "blocked", title: t("scholarships.assignments.detail.forbiddenTitle"), description: t("scholarships.assignments.detail.forbiddenDescription") };
      const failure = loadFailureOf(detail.error);
      return {
        kind: "error",
        title: t("scholarships.assignments.detail.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void detail.refetch(),
      };
    }
    return { kind: "loading" };
  }

  const state = stateOf();
  const assignment = state.kind === "ready" ? state.assignment : undefined;
  const pending = assignment?.status === "pending";
  const open = (decision: Decision) => () => {
    setNotice(undefined);
    setDeciding(decision);
  };
  const decided = (key: "scholarships.assignments.approve.approvedNotice" | "scholarships.assignments.reject.rejectedNotice" | "scholarships.assignments.revoke.revokedNotice") => () => {
    setDeciding(undefined);
    setNotice({ tone: "success", message: t(key) });
  };
  const close = () => {
    setDeciding(undefined);
  };

  return (
    <>
      <AssignmentDetailView
        state={state}
        backHref={DISCOUNT_PATHS.assignments}
        onBack={() => void navigate({ to: DISCOUNT_PATHS.assignments })}
        notice={notice}
        onApprove={pending && can("scholarships:approve") ? open("approve") : undefined}
        onReject={pending && can("scholarships:approve") ? open("reject") : undefined}
        onRevoke={assignment?.status === "approved" && can("scholarships:void") ? open("revoke") : undefined}
        onViewStudentAssignments={(studentId) => void navigate({ to: DISCOUNT_PATHS.assignments, search: { studentId } })}
      />
      {assignment === undefined || deciding === undefined ? null : deciding === "approve" ? (
        <ApproveAssignmentContainer assignment={assignment} onClose={close} onDecided={decided("scholarships.assignments.approve.approvedNotice")} />
      ) : deciding === "reject" ? (
        <RejectAssignmentContainer assignment={assignment} onClose={close} onDecided={decided("scholarships.assignments.reject.rejectedNotice")} />
      ) : (
        <RevokeAssignmentContainer assignment={assignment} onClose={close} onDecided={decided("scholarships.assignments.revoke.revokedNotice")} />
      )}
    </>
  );
}

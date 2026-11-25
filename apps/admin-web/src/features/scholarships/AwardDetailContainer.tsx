import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { SCHOLARSHIP_AWARDS_PATH } from "../../shared/navigation/scholarship-paths.ts";
import { useYearLookup } from "../../shared/organization-lookups/use-lookups.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import type { ResourceNotice } from "../../shared/resource-screen/components/ResourceScreen.tsx";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { ApproveAwardContainer, RejectAwardContainer, RevokeAwardContainer } from "./DecisionContainers.tsx";
import { AwardDetailView, type AwardDetailState } from "./components/AwardDetailView.tsx";
import { useAwardDetail } from "./use-award-queries.ts";

export interface AwardDetailContainerProps {
  readonly awardId: string;
}

type Decision = "approve" | "reject" | "revoke";

/**
 * One award with its decision. A pending award offers Approve and Reject (`scholarships:approve`) and an approved one offers Revoke
 * (`scholarships:void`), each an explicit dialog. The interface cannot know who requested the award, so the server still decides: it refuses that
 * person and anyone without the permission, and the dialog says so. When the server confirms, the award is read again before the dialog closes, and
 * the person is told.
 */
export function AwardDetailContainer({ awardId }: AwardDetailContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const { can } = usePermissions();
  const detail = useAwardDetail(awardId);
  const years = useYearLookup();
  const [deciding, setDeciding] = useState<Decision>();
  const [notice, setNotice] = useState<ResourceNotice>();

  function stateOf(): AwardDetailState {
    if (detail.data !== undefined) {
      const year = years.rows.find((row) => row.id === detail.data.academicYearId);
      return { kind: "ready", award: detail.data, academicYearName: year?.name };
    }
    if (detail.isError) {
      const status = detail.error instanceof ApiFailure && detail.error.result.kind === "problem" ? detail.error.result.problem.status : undefined;
      if (status === 404) return { kind: "blocked", title: t("scholarships.detail.notFoundTitle"), description: t("scholarships.detail.notFoundDescription") };
      if (status === 403) return { kind: "blocked", title: t("scholarships.detail.forbiddenTitle"), description: t("scholarships.detail.forbiddenDescription") };
      const failure = loadFailureOf(detail.error);
      return {
        kind: "error",
        title: t("scholarships.detail.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void detail.refetch(),
      };
    }
    return { kind: "loading" };
  }

  const state = stateOf();
  const award = state.kind === "ready" ? state.award : undefined;
  const pending = award?.status === "pending";
  const open = (decision: Decision) => () => {
    setNotice(undefined);
    setDeciding(decision);
  };
  const decided = (key: "scholarships.approve.approvedNotice" | "scholarships.reject.rejectedNotice" | "scholarships.revoke.revokedNotice") => () => {
    setDeciding(undefined);
    setNotice({ tone: "success", message: t(key) });
  };

  return (
    <>
      <AwardDetailView
        state={state}
        backHref={SCHOLARSHIP_AWARDS_PATH}
        onBack={() => void navigate({ to: SCHOLARSHIP_AWARDS_PATH })}
        notice={notice}
        onApprove={pending && can("scholarships:approve") ? open("approve") : undefined}
        onReject={pending && can("scholarships:approve") ? open("reject") : undefined}
        onRevoke={award?.status === "approved" && can("scholarships:void") ? open("revoke") : undefined}
        onViewStudentAwards={(studentId) => void navigate({ to: SCHOLARSHIP_AWARDS_PATH, search: { studentId } })}
      />
      {award === undefined || deciding === undefined ? null : deciding === "approve" ? (
        <ApproveAwardContainer
          award={award}
          onClose={() => {
            setDeciding(undefined);
          }}
          onDecided={decided("scholarships.approve.approvedNotice")}
        />
      ) : deciding === "reject" ? (
        <RejectAwardContainer
          award={award}
          onClose={() => {
            setDeciding(undefined);
          }}
          onDecided={decided("scholarships.reject.rejectedNotice")}
        />
      ) : (
        <RevokeAwardContainer
          award={award}
          onClose={() => {
            setDeciding(undefined);
          }}
          onDecided={decided("scholarships.revoke.revokedNotice")}
        />
      )}
    </>
  );
}

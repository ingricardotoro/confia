import { useAdminText } from "../../i18n/use-admin-text.ts";
import { loadFailureOf } from "../organization/organization-failure.ts";
import { EnrollmentDetail } from "./components/EnrollmentDetail.tsx";
import { useEnrollmentDetail } from "./use-enrollments-queries.ts";

export interface EnrollmentDetailContainerProps {
  readonly enrollmentId: string;
  readonly onClose: () => void;
}

/** Reads one enrollment with its transfer history and hands the presentation its state: loading, failed with a retry, or the data. */
export function EnrollmentDetailContainer({ enrollmentId, onClose }: EnrollmentDetailContainerProps) {
  const { t } = useAdminText();
  const detail = useEnrollmentDetail(enrollmentId);
  const failure = detail.isError ? loadFailureOf(detail.error) : undefined;

  return (
    <EnrollmentDetail
      onClose={onClose}
      state={
        detail.isPending
          ? { kind: "loading" }
          : failure !== undefined || detail.data === undefined
            ? {
                kind: "failed",
                description: [
                  t(failure?.key ?? "problem.unexpected"),
                  failure?.traceId === undefined ? "" : t("organization.common.trace", { traceId: failure.traceId }),
                ]
                  .filter((part) => part !== "")
                  .join(" "),
                onRetry: () => void detail.refetch(),
              }
            : { kind: "ready", row: detail.data }
      }
    />
  );
}

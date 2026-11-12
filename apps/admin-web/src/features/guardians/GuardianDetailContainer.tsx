import { useAdminText } from "../../i18n/use-admin-text.ts";
import { loadFailureOf } from "../organization/organization-failure.ts";
import { GuardianDetail } from "./components/GuardianDetail.tsx";
import { useGuardianDetail } from "./use-guardians-queries.ts";

export interface GuardianDetailContainerProps {
  readonly guardianId: string;
  readonly onClose: () => void;
}

/** Reads one guardian with the students linked now and hands the presentation its state: loading, failed with a retry, or the data. */
export function GuardianDetailContainer({ guardianId, onClose }: GuardianDetailContainerProps) {
  const { t } = useAdminText();
  const detail = useGuardianDetail(guardianId);
  const failure = detail.isError ? loadFailureOf(detail.error) : undefined;

  return (
    <GuardianDetail
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

import { useNavigate } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { IntegrityRunDetailView, type IntegrityRunDetailState } from "./components/IntegrityRunDetailView.tsx";
import { CHARGES_PATHS } from "./charges-paths.ts";
import { useIntegrityRunDetail } from "./use-integrity-queries.ts";

export interface IntegrityRunDetailContainerProps {
  readonly runId: string;
}

/** One run of the integrity check with the discrepancies it confirmed. A read only view: the server decides what is a discrepancy. */
export function IntegrityRunDetailContainer({ runId }: IntegrityRunDetailContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const detail = useIntegrityRunDetail(runId);

  function stateOf(): IntegrityRunDetailState {
    if (detail.data !== undefined) return { kind: "ready", detail: detail.data };
    if (detail.isError) {
      const status = detail.error instanceof ApiFailure && detail.error.result.kind === "problem" ? detail.error.result.problem.status : undefined;
      if (status === 404) return { kind: "blocked", title: t("charges.integrity.detail.notFoundTitle"), description: t("charges.integrity.detail.notFoundDescription") };
      const failure = loadFailureOf(detail.error);
      return {
        kind: "error",
        title: t("charges.integrity.detail.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void detail.refetch(),
      };
    }
    return { kind: "loading" };
  }

  return <IntegrityRunDetailView state={stateOf()} onBack={() => void navigate({ to: CHARGES_PATHS.integrity })} />;
}

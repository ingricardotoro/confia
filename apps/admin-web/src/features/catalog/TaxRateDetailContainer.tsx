import { useNavigate } from "@tanstack/react-router";
import { useRef, useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../shared/format/format-date.ts";
import type { ResourceNotice } from "../../shared/resource-screen/components/ResourceScreen.tsx";
import { failureMessageOf, failureOf, loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { CATALOG_PATHS } from "./catalog-paths.ts";
import { TaxRateDetailView, type TaxRateDetailState } from "./components/TaxRateDetailView.tsx";
import { stateBodyOf } from "./tax-rate-form.ts";
import { newestFirst } from "./tax-rate-period-form.ts";
import { TaxRatePeriodFormContainer } from "./TaxRatePeriodFormContainer.tsx";
import { useTaxRateDetail, useTaxRatePeriodAdd, useTaxRateWrite } from "./use-catalog-queries.ts";

export interface TaxRateDetailContainerProps {
  readonly taxRateId: string;
}

/**
 * One tax rate: its validities as the server holds them (every one, closed and open), the dialog of a new validity, and
 * the activation or deactivation with the version the rate was read at. Nothing is optimistic: the screen shows what
 * the server answers, and every refusal leaves it as it was.
 */
export function TaxRateDetailContainer({ taxRateId }: TaxRateDetailContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const detail = useTaxRateDetail(taxRateId);
  const write = useTaxRateWrite();
  const add = useTaxRatePeriodAdd();
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<ResourceNotice>();
  const changing = useRef(false);

  async function toggleStatus() {
    const rate = detail.data;
    if (rate === undefined || changing.current) return;
    changing.current = true;
    setNotice(undefined);
    const next = !rate.isActive;
    try {
      const saved = await write.mutateAsync({ mode: "update", id: rate.id, body: stateBodyOf(rate, next) });
      setNotice({ tone: "success", message: t(next ? "catalog.taxRates.detail.activated" : "catalog.taxRates.detail.deactivated", { name: saved.name }) });
    } catch (error) {
      const { key, traceId } = failureMessageOf(failureOf(error));
      setNotice({ tone: "error", message: t(key), ...(traceId === undefined ? {} : { traceId }) });
    } finally {
      changing.current = false;
    }
  }

  function stateOf(): TaxRateDetailState {
    if (detail.data !== undefined) return { kind: "ready", rate: detail.data, periods: newestFirst(detail.data.periods) };
    if (detail.isError) {
      const failure = loadFailureOf(detail.error);
      return {
        kind: "error",
        title: t("catalog.taxRates.detail.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void detail.refetch(),
      };
    }
    return { kind: "loading" };
  }

  return (
    <>
      <TaxRateDetailView
        state={stateOf()}
        notice={notice}
        changingStatus={write.isPending}
        backHref={CATALOG_PATHS.taxRates}
        onBack={() => void navigate({ to: CATALOG_PATHS.taxRates })}
        onAddPeriod={() => {
          setNotice(undefined);
          setAdding(true);
        }}
        onToggleStatus={() => void toggleStatus()}
      />
      {adding ? (
        <TaxRatePeriodFormContainer
          save={async (body) => {
            await add.mutateAsync({ rateId: taxRateId, body });
            setAdding(false);
            setNotice({
              tone: "success",
              message: t("catalog.taxRates.addPeriod.saved", { name: detail.data?.name ?? t("resource.unknownReference"), date: formatIsoDate(body.validFrom) }),
            });
          }}
          onClose={() => {
            setAdding(false);
          }}
        />
      ) : null}
    </>
  );
}

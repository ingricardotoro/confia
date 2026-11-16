import { useNavigate } from "@tanstack/react-router";
import { useRef, useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../shared/format/format-date.ts";
import { nameMapOf, optionsOf, useGradeLookup, useModalityLookup, useYearLookup } from "../../shared/organization-lookups/use-lookups.ts";
import type { ResourceNotice } from "../../shared/resource-screen/components/ResourceScreen.tsx";
import { failureMessageOf, failureOf, loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { CATALOG_PATHS } from "./catalog-paths.ts";
import { PriceListDetailView, type PriceListDetailState } from "./components/PriceListDetailView.tsx";
import { PriceFormContainer } from "./PriceFormContainer.tsx";
import { groupByConcept } from "./price-form.ts";
import { statusBodyOf } from "./price-list-form.ts";
import { usePaymentConceptLookup, usePriceAdd, usePriceListDetail, usePriceListUpdate } from "./use-catalog-queries.ts";

export interface PriceListDetailContainerProps {
  readonly priceListId: string;
}

/**
 * One price list: its prices as the server holds them (every validity, closed and open), the dialog of a new price or
 * validity, and the archive or reactivation with the version the list was read at. Nothing is optimistic: the screen
 * shows what the server answers, and every refusal leaves it as it was.
 */
export function PriceListDetailContainer({ priceListId }: PriceListDetailContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const detail = usePriceListDetail(priceListId);
  const update = usePriceListUpdate();
  const add = usePriceAdd();
  const concepts = usePaymentConceptLookup();
  const years = useYearLookup();
  const modalities = useModalityLookup();
  const grades = useGradeLookup();
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<ResourceNotice>();
  const changing = useRef(false);

  const lookupsFailed = concepts.failed || years.failed || modalities.failed || grades.failed;
  const retryLookups = () => {
    if (concepts.failed) concepts.retry();
    if (years.failed) years.retry();
    if (modalities.failed) modalities.retry();
    if (grades.failed) grades.retry();
  };

  const conceptsById = new Map(concepts.rows.map((concept) => [concept.id, concept]));
  const activeConcepts = concepts.rows.filter((concept) => concept.isActive);

  async function toggleStatus() {
    const list = detail.data;
    if (list === undefined || changing.current) return;
    changing.current = true;
    setNotice(undefined);
    const next = list.status === "ACTIVE" ? "ARCHIVED" : "ACTIVE";
    try {
      const saved = await update.mutateAsync({ id: list.id, body: statusBodyOf(list, next) });
      setNotice({ tone: "success", message: t(next === "ARCHIVED" ? "catalog.priceLists.detail.archived" : "catalog.priceLists.detail.reactivated", { name: saved.name }) });
    } catch (error) {
      const { key, traceId } = failureMessageOf(failureOf(error));
      setNotice({ tone: "error", message: t(key), ...(traceId === undefined ? {} : { traceId }) });
    } finally {
      changing.current = false;
    }
  }

  function stateOf(): PriceListDetailState {
    if (detail.data !== undefined) {
      const list = detail.data;
      const referenceId = list.scopeRefId ?? undefined;
      const referenceNames = new Map([...nameMapOf(modalities.rows), ...nameMapOf(grades.rows)]);
      return {
        kind: "ready",
        list,
        yearName: nameMapOf(years.rows).get(list.academicYearId) ?? t("resource.unknownReference"),
        referenceName: referenceId === undefined ? undefined : referenceNames.get(referenceId),
        concepts: groupByConcept(list.items).map((group) => ({
          ...group,
          code: conceptsById.get(group.paymentConceptId)?.code,
          name: conceptsById.get(group.paymentConceptId)?.name,
        })),
      };
    }
    if (detail.isError) {
      const failure = loadFailureOf(detail.error);
      return {
        kind: "error",
        title: t("catalog.priceLists.detail.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void detail.refetch(),
      };
    }
    return { kind: "loading" };
  }

  return (
    <>
      <PriceListDetailView
        state={stateOf()}
        notice={notice}
        lookupFailure={lookupsFailed ? { onRetry: retryLookups } : undefined}
        changingStatus={update.isPending}
        backHref={CATALOG_PATHS.priceLists}
        onBack={() => void navigate({ to: CATALOG_PATHS.priceLists })}
        onAddPrice={() => {
          setNotice(undefined);
          setAdding(true);
        }}
        onToggleStatus={() => void toggleStatus()}
      />
      {adding ? (
        <PriceFormContainer
          conceptOptions={optionsOf(activeConcepts)}
          lookupFailure={concepts.failed ? { onRetry: concepts.retry } : undefined}
          noActiveConcepts={!concepts.loading && !concepts.failed && activeConcepts.length === 0}
          save={async (body) => {
            await add.mutateAsync({ listId: priceListId, body });
            setAdding(false);
            setNotice({
              tone: "success",
              message: t("catalog.priceLists.addPrice.saved", {
                name: conceptsById.get(body.paymentConceptId)?.name ?? t("resource.unknownReference"),
                date: formatIsoDate(body.validFrom),
              }),
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

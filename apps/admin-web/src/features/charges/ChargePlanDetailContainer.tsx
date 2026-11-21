import { useNavigate } from "@tanstack/react-router";
import { useRef, useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import { useConceptLookup } from "../../shared/catalog-lookups/use-concept-lookup.ts";
import {
  nameMapOf,
  optionsOf,
  useGradeLookup,
  useModalityLookup,
  useSectionLookup,
  useYearLookup,
} from "../../shared/organization-lookups/use-lookups.ts";
import { ConfirmDialog } from "../../shared/resource-screen/components/ConfirmDialog.tsx";
import type { ResourceNotice } from "../../shared/resource-screen/components/ResourceScreen.tsx";
import { failureMessageOf, failureOf, loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { statusBodyOf } from "./charge-plan-form.ts";
import { referenceNamesOf, sectionNamesOf } from "./charge-plan-references.ts";
import { ChargePlanEditContainer } from "./ChargePlanEditContainer.tsx";
import { ChargePlanItemFormContainer } from "./ChargePlanItemFormContainer.tsx";
import { CHARGES_PATHS } from "./charges-paths.ts";
import { ChargePlanDetailView, type ChargePlanDetailState, type ChargePlanItemView } from "./components/ChargePlanDetailView.tsx";
import { useChargePlanDetail, useChargePlanItemAdd, useChargePlanItemRemove, useChargePlanUpdate } from "./use-charges-queries.ts";

export interface ChargePlanDetailContainerProps {
  readonly planId: string;
}

/** What the person is being asked to confirm, or the form that is open. At most one at a time. */
type Dialog =
  | { readonly kind: "none" }
  | { readonly kind: "edit" }
  | { readonly kind: "add-item" }
  | { readonly kind: "activate" }
  | { readonly kind: "archive" }
  | { readonly kind: "remove-item"; readonly item: ChargePlanItemView };

/**
 * One plan: its items as the server holds them, the forms of the edit and of a new item, and the activation, the archive
 * and the removal of an item, each behind an explicit confirmation and sent with the version the plan was read at.
 * Nothing is optimistic: the screen shows what the server answers, and every refusal leaves it as it was (and rereads it).
 * What the plan can do is the server's decision; the screen only hides what its state does not allow.
 */
export function ChargePlanDetailContainer({ planId }: ChargePlanDetailContainerProps) {
  const canUpdate = usePermissions().can("charges:update");
  const { t } = useAdminText();
  const navigate = useNavigate();
  const detail = useChargePlanDetail(planId);
  const update = useChargePlanUpdate();
  const addItem = useChargePlanItemAdd();
  const removeItem = useChargePlanItemRemove();
  const concepts = useConceptLookup();
  const years = useYearLookup();
  const modalities = useModalityLookup();
  const grades = useGradeLookup();
  const sections = useSectionLookup();
  const [dialog, setDialog] = useState<Dialog>({ kind: "none" });
  const [notice, setNotice] = useState<ResourceNotice>();
  // One change at a time: a second press while the first is in flight sends nothing.
  const changing = useRef(false);

  const lookups = [concepts, years, modalities, grades, sections];
  const lookupFailure = lookups.some((lookup) => lookup.failed)
    ? {
        onRetry: () => {
          for (const lookup of lookups) if (lookup.failed) lookup.retry();
        },
      }
    : undefined;
  const conceptsById = new Map(concepts.rows.map((concept) => [concept.id, concept]));
  const inPlan = new Set(detail.data?.items.map((item) => item.paymentConceptId));
  const addable = concepts.rows.filter((concept) => concept.isActive && !inPlan.has(concept.id));

  const tell = (error: unknown) => {
    const { key, traceId } = failureMessageOf(failureOf(error));
    setNotice({ tone: "error", message: t(key), ...(traceId === undefined ? {} : { traceId }) });
  };

  /** Runs one change of the plan behind its confirmation: the dialog closes when it ends, and the region says how it went. */
  async function confirm(run: () => Promise<string>) {
    if (changing.current) return;
    changing.current = true;
    setNotice(undefined);
    try {
      setNotice({ tone: "success", message: await run() });
    } catch (error) {
      tell(error);
    } finally {
      changing.current = false;
      setDialog({ kind: "none" });
    }
  }

  const plan = detail.data;
  const busy = update.isPending || removeItem.isPending;

  function stateOf(): ChargePlanDetailState {
    if (plan !== undefined) {
      const sectionNames = sectionNamesOf(
        sections.rows,
        grades.rows,
        years.rows,
        ({ grade, section, year }) => t("charges.plans.sectionOption", { grade, section, year }),
        t("resource.unknownReference"),
      );
      return {
        kind: "ready",
        plan,
        yearName: nameMapOf(years.rows).get(plan.academicYearId) ?? t("resource.unknownReference"),
        referenceName: referenceNamesOf(modalities.rows, grades.rows, sectionNames).get(plan.targetRefId) ?? t("resource.unknownReference"),
        items: plan.items.map((item) => ({
          id: item.id,
          code: conceptsById.get(item.paymentConceptId)?.code,
          name: conceptsById.get(item.paymentConceptId)?.name,
          periodsCount: item.periodsCount,
          firstPeriodOn: item.firstPeriodOn,
        })),
      };
    }
    if (detail.isError) {
      const failure = loadFailureOf(detail.error);
      return {
        kind: "error",
        title: t("charges.plans.detail.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void detail.refetch(),
      };
    }
    return { kind: "loading" };
  }

  const open = (next: Dialog) => () => {
    setNotice(undefined);
    setDialog(next);
  };

  return (
    <>
      <ChargePlanDetailView
        state={stateOf()}
        notice={notice}
        lookupFailure={lookupFailure}
        backHref={CHARGES_PATHS.plans}
        onBack={() => void navigate({ to: CHARGES_PATHS.plans })}
        onEdit={canUpdate ? open({ kind: "edit" }) : undefined}
        onActivate={canUpdate ? open({ kind: "activate" }) : undefined}
        onArchive={canUpdate ? open({ kind: "archive" }) : undefined}
        onAddItem={canUpdate ? open({ kind: "add-item" }) : undefined}
        onRemoveItem={
          canUpdate
            ? (item) => {
                open({ kind: "remove-item", item })();
              }
            : undefined
        }
      />
      {plan !== undefined && dialog.kind === "edit" ? (
        <ChargePlanEditContainer
          plan={plan}
          save={async (body) => {
            const saved = await update.mutateAsync({ id: plan.id, body });
            setDialog({ kind: "none" });
            setNotice({ tone: "success", message: t("charges.plans.edit.saved", { name: saved.name }) });
          }}
          onClose={() => {
            setDialog({ kind: "none" });
          }}
        />
      ) : null}
      {plan !== undefined && dialog.kind === "add-item" ? (
        <ChargePlanItemFormContainer
          conceptOptions={optionsOf(addable)}
          lookupFailure={concepts.failed ? { onRetry: concepts.retry } : undefined}
          noConcepts={!concepts.loading && !concepts.failed && addable.length === 0}
          save={async (body) => {
            await addItem.mutateAsync({ planId: plan.id, body });
            setDialog({ kind: "none" });
            setNotice({
              tone: "success",
              message: t("charges.plans.addItem.saved", { name: conceptsById.get(body.paymentConceptId)?.name ?? t("resource.unknownReference") }),
            });
          }}
          onClose={() => {
            setDialog({ kind: "none" });
          }}
        />
      ) : null}
      {plan !== undefined && dialog.kind === "activate" ? (
        <ConfirmDialog
          title={t("charges.plans.detail.activateConfirm.title", { name: plan.name })}
          description={t("charges.plans.detail.activateConfirm.description")}
          confirmLabel={t("charges.plans.detail.activateConfirm.confirm")}
          variant="success"
          busy={busy}
          onConfirm={() =>
            void confirm(async () => {
              const saved = await update.mutateAsync({ id: plan.id, body: statusBodyOf(plan, "ACTIVE") });
              return t("charges.plans.detail.activated", { name: saved.name });
            })
          }
          onCancel={() => {
            setDialog({ kind: "none" });
          }}
        />
      ) : null}
      {plan !== undefined && dialog.kind === "archive" ? (
        <ConfirmDialog
          title={t("charges.plans.detail.archiveConfirm.title", { name: plan.name })}
          description={t("charges.plans.detail.archiveConfirm.description")}
          confirmLabel={t("charges.plans.detail.archiveConfirm.confirm")}
          variant="destructive"
          busy={busy}
          onConfirm={() =>
            void confirm(async () => {
              const saved = await update.mutateAsync({ id: plan.id, body: statusBodyOf(plan, "ARCHIVED") });
              return t("charges.plans.detail.archived", { name: saved.name });
            })
          }
          onCancel={() => {
            setDialog({ kind: "none" });
          }}
        />
      ) : null}
      {plan !== undefined && dialog.kind === "remove-item" ? (
        <ConfirmDialog
          title={t("charges.plans.detail.removeConfirm.title", { name: dialog.item.name ?? t("resource.unknownReference") })}
          description={t("charges.plans.detail.removeConfirm.description")}
          confirmLabel={t("charges.plans.detail.removeConfirm.confirm")}
          variant="destructive"
          busy={busy}
          onConfirm={() => {
            const { item } = dialog;
            void confirm(async () => {
              await removeItem.mutateAsync({ planId: plan.id, itemId: item.id });
              return t("charges.plans.detail.itemRemoved", { name: item.name ?? t("resource.unknownReference") });
            });
          }}
          onCancel={() => {
            setDialog({ kind: "none" });
          }}
        />
      ) : null}
    </>
  );
}

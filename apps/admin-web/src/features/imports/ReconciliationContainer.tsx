import { useEffect, useRef, useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { toApiParams } from "../organization/list-search.ts";
import { fieldSpecs } from "../organization/organization-form.ts";
import type { SearchChange } from "../organization/search-change.ts";
import { useListState } from "../organization/use-list-state.ts";
import { nameMapOf, optionsOf, useYearLookup } from "../organization/use-lookups.ts";
import { ReconciliationForm, type SignedNotice, type YearsState } from "./components/ReconciliationForm.tsx";
import { ReconciliationHistory } from "./components/ReconciliationHistory.tsx";
import { ReconciliationPreview, type PreviewView } from "./components/ReconciliationPreview.tsx";
import type { ImportsSearch } from "./imports-search.ts";
import {
  RECONCILIATION_CONTROL_IDS,
  differenceKindOf,
  initialReconciliationValues,
  officialCountOf,
  previewFailureOf,
  reconciliationBodyOf,
  signFailureOf,
  validateReconciliation,
  type ReconciliationField,
} from "./reconciliation-model.ts";
import { useReconciliationHistory, useReconciliationPreview, useSignReconciliation } from "./use-imports-queries.ts";

/** The actas are listed ten at a time; the page is in the address, like the page of every listing. */
export const ACTS_PAGE_SIZE = 10;

export interface ReconciliationContainerProps {
  readonly search: ImportsSearch;
  readonly onSearchChange: SearchChange<ImportsSearch>;
}

/**
 * The acta of the reconciliation of the migration: the form that signs it, the preview of what the server will record for the
 * chosen year, and the actas already signed. The server counts and decides everything: the screen sends the year, the reference
 * of the official roster and its total, and shows the record it answers. The only figure the screen derives is the difference of
 * two whole counts for the preview; a signed acta shows the difference the server recorded. Signing is never optimistic and is
 * blocked while one is in flight; afterwards the preview and the actas are read again.
 */
export function ReconciliationContainer({ search, onSearchChange }: ReconciliationContainerProps) {
  const { t } = useAdminText();
  const years = useYearLookup();
  const sign = useSignReconciliation();
  const [signed, setSigned] = useState<SignedNotice | undefined>();
  const signedRef = useRef<HTMLDivElement>(null);
  const defaultedYear = useRef(false);
  const releasedNotice = useRef<SignedNotice | undefined>(undefined);

  const yearNames = nameMapOf(years.rows);
  const unknownYear = t("imports.reconciliation.history.unknownYear");

  const form = useAccessForm<ReconciliationField>({
    fields: fieldSpecs(RECONCILIATION_CONTROL_IDS, initialReconciliationValues(), ["officialReference", "officialEnrollmentCount"]),
    validate: validateReconciliation,
    submit: async (values) => {
      setSigned(undefined);
      try {
        const record = await sign.mutateAsync(reconciliationBodyOf(values));
        setSigned({
          yearName: yearNames.get(record.academicYearId) ?? unknownYear,
          reference: record.officialReference,
          difference: t(`imports.reconciliation.difference.${differenceKindOf(record.difference)}`, { count: Math.abs(record.difference) }),
        });
        return undefined;
      } catch (error) {
        return signFailureOf(error);
      }
    },
    waitingKey: "imports.reconciliation.form.signing",
  });

  // The reference and the total are let go once the acta is signed (the year stays, to sign or read the next one). It runs after
  // the form has finished its own send, so no stale error of the form is checked against the emptied fields.
  const { onChange } = form;
  useEffect(() => {
    if (signed === undefined || releasedNotice.current === signed) return;
    releasedNotice.current = signed;
    onChange("officialReference", "");
    onChange("officialEnrollmentCount", "");
    signedRef.current?.focus();
  }, [signed, onChange]);

  // The active year is the one most likely to be signed: it is chosen once, when the years arrive, if nothing was chosen before.
  const activeYearId = years.rows.find((year) => year.status === "ACTIVE")?.id;
  const chosenYearId = form.values.academicYearId;
  useEffect(() => {
    if (defaultedYear.current || activeYearId === undefined) return;
    defaultedYear.current = true;
    if (chosenYearId === "") onChange("academicYearId", activeYearId);
  }, [activeYearId, chosenYearId, onChange]);

  const preview = useReconciliationPreview(chosenYearId === "" ? undefined : chosenYearId);
  const previewFailure = preview.isError ? previewFailureOf(preview.error) : undefined;
  const previewView: PreviewView =
    chosenYearId === ""
      ? { kind: "idle" }
      : previewFailure !== undefined
        ? {
            kind: "failed",
            message: [t(previewFailure.key), previewFailure.traceId === undefined ? "" : t("organization.common.trace", { traceId: previewFailure.traceId })]
              .filter((part) => part !== "")
              .join(" "),
            onRetry: () => void preview.refetch(),
          }
        : preview.data === undefined
          ? { kind: "loading" }
          : { kind: "ready", data: preview.data };

  const actsSearch = { page: search.actsPage, pageSize: ACTS_PAGE_SIZE };
  const history = useReconciliationHistory(toApiParams(actsSearch));
  const historyState = useListState({
    list: history,
    search: actsSearch,
    onSearchChange: (next) => {
      onSearchChange({ ...search, actsPage: next.page });
    },
    filtered: false,
    cleared: { page: 1, pageSize: ACTS_PAGE_SIZE },
    onCreate: () => {
      document.getElementById(RECONCILIATION_CONTROL_IDS.officialReference)?.focus();
    },
    loadFailedKey: "imports.reconciliation.history.loadFailed",
    emptyKeys: {
      title: "imports.reconciliation.history.emptyTitle",
      description: "imports.reconciliation.history.emptyDescription",
      action: "imports.reconciliation.history.emptyAction",
    },
  });

  const yearsState: YearsState = years.loading ? "loading" : years.failed ? "failed" : years.rows.length === 0 ? "empty" : "ready";

  return (
    <section aria-labelledby="reconciliation-heading" className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h2 id="reconciliation-heading" className="text-xl font-semibold text-text-primary">
          {t("imports.reconciliation.heading")}
        </h2>
        <p className="max-w-prose text-sm text-text-secondary">{t("imports.reconciliation.intro")}</p>
      </div>
      <div className="grid grid-cols-1 gap-8 lg:grid-cols-2">
        <ReconciliationForm
          years={{ state: yearsState, options: optionsOf(years.rows), onRetry: years.retry }}
          values={form.values}
          fieldErrors={form.fieldErrors}
          summary={form.summary}
          alert={form.alert}
          countdown={form.countdown}
          liveMessage={form.liveMessage}
          submitting={form.submitting}
          summaryRef={form.summaryRef}
          alertRef={form.alertRef}
          signed={signed}
          signedRef={signedRef}
          onChange={form.onChange}
          onBlur={form.onBlur}
          onSubmit={form.onSubmit}
        />
        <ReconciliationPreview view={previewView} officialCount={officialCountOf(form.values.officialEnrollmentCount)} />
      </div>
      <ReconciliationHistory rows={historyState.rows} table={historyState.table} pagination={historyState.pagination} yearNames={yearNames} />
    </section>
  );
}

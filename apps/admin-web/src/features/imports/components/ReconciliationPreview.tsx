import type { ReconciliationPreviewResponseOutput } from "@confia/contracts/admin";
import { Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { IMPORT_KINDS, type ImportKind } from "../import-model.ts";
import { differenceOf } from "../reconciliation-model.ts";
import { DifferenceText } from "./DifferenceText.tsx";

/** What the preview of the chosen year is doing: nothing yet, loading, failed (with a way to try again) or ready. */
export type PreviewView =
  | { readonly kind: "idle" }
  | { readonly kind: "loading" }
  | { readonly kind: "failed"; readonly message: string; readonly onRetry: () => void }
  | { readonly kind: "ready"; readonly data: ReconciliationPreviewResponseOutput };

export interface ReconciliationPreviewProps {
  readonly view: PreviewView;
  /** The official total, once what was typed is a valid one. Absent while it is not. */
  readonly officialCount?: number | undefined;
}

const NUMBERS = new Intl.NumberFormat("es-HN");

interface KindRow {
  readonly kind: ImportKind;
  readonly created: number;
  readonly rejected: number;
}

function rowsOf(data: ReconciliationPreviewResponseOutput): readonly KindRow[] {
  const counts: Readonly<Record<ImportKind, { readonly created: number; readonly rejected: number }>> = {
    students: data.students,
    guardians: data.guardians,
    "guardian-links": data.guardianLinks,
    enrollments: data.enrollments,
  };
  return IMPORT_KINDS.map((kind) => ({ kind, ...counts[kind] }));
}

function Count({ label, value }: { readonly label: string; readonly value: string }) {
  return (
    <div className="flex flex-col gap-0.5 rounded-md border border-border-subtle p-3">
      <dt className="text-sm text-text-secondary">{label}</dt>
      <dd className="text-2xl font-semibold tabular-nums text-text-primary">{value}</dd>
    </div>
  );
}

/**
 * What the acta of the chosen year would hold, as the server counts it: records created and rejected by type, and the active
 * enrollments of the year. Once the official total is a valid whole number it also says, in words and not by color, whether
 * the system holds more, fewer or the same enrollments as the roster: both are plain counts, so the difference is shown here as
 * they are typed. It makes no request.
 */
export function ReconciliationPreview({ view, officialCount }: ReconciliationPreviewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<KindRow>[] = [
    { id: "kind", header: t("imports.reconciliation.preview.kind"), cell: (row) => t(`imports.kinds.${row.kind}.option`) },
    { id: "created", header: t("imports.reconciliation.preview.created"), align: "end", cell: (row) => NUMBERS.format(row.created) },
    { id: "rejected", header: t("imports.reconciliation.preview.rejected"), align: "end", cell: (row) => NUMBERS.format(row.rejected) },
  ];

  return (
    <section aria-labelledby="reconciliation-preview-heading" className="flex flex-col gap-3">
      <h3 id="reconciliation-preview-heading" className="text-lg font-semibold text-text-primary">
        {t("imports.reconciliation.preview.heading")}
      </h3>
      <p className="text-sm text-text-secondary">{t("imports.reconciliation.preview.description")}</p>
      {view.kind === "idle" ? (
        <p className="text-sm text-text-primary">{t("imports.reconciliation.preview.choose")}</p>
      ) : (
        <>
          <Table
            caption={t("imports.reconciliation.preview.caption")}
            columns={columns}
            rows={view.kind === "ready" ? rowsOf(view.data) : []}
            getRowId={(row) => row.kind}
            loading={view.kind === "loading"}
            skeletonRows={IMPORT_KINDS.length}
            {...(view.kind === "failed"
              ? { error: { title: t("imports.reconciliation.preview.failedTitle"), description: view.message, onRetry: view.onRetry } }
              : {})}
            empty={{ title: t("imports.reconciliation.preview.choose"), description: t("imports.reconciliation.preview.description"), action: { label: t("imports.reconciliation.preview.heading"), onAction: () => undefined } }}
          />
          {view.kind === "ready" ? (
            <>
              <p className="text-sm text-text-secondary">{t("imports.reconciliation.preview.rejectedNote")}</p>
              <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Count label={t("imports.reconciliation.preview.active")} value={NUMBERS.format(view.data.enrollmentsActive)} />
                {officialCount === undefined ? null : <Count label={t("imports.reconciliation.preview.official")} value={NUMBERS.format(officialCount)} />}
              </dl>
              <div className="flex flex-col gap-1">
                <h4 className="text-base font-semibold text-text-primary">{t("imports.reconciliation.preview.differenceHeading")}</h4>
                {officialCount === undefined ? (
                  <p className="text-sm text-text-secondary">{t("imports.reconciliation.preview.differenceHint")}</p>
                ) : (
                  <DifferenceText difference={differenceOf(view.data.enrollmentsActive, officialCount)} />
                )}
              </div>
            </>
          ) : null}
        </>
      )}
    </section>
  );
}


import type { ImportRunResponseOutput, RejectionResponseOutput } from "@confia/contracts/admin";
import { Alert, Button, Dialog, FormField, Input, Pagination, Table, type TableColumn } from "@confia/ui";
import { useEffect, useRef, useState, type SyntheticEvent } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { fieldNameOf, type FileProblem, type ImportFailure, type ImportStep } from "../import-model.ts";
import { ImportStepper } from "./ImportStepper.tsx";

export type ImportPhase = "idle" | "simulating" | "applying";

export interface ImportWizardProps {
  readonly step: ImportStep;
  readonly phase: ImportPhase;
  /** Changes to give the file input a new, empty control: the file is let go and the input forgets it. */
  readonly inputKey: number;
  /** Why the file cannot be sent, found in the browser before sending. */
  readonly fileProblem?: FileProblem | "required" | undefined;
  readonly failure?: ImportFailure | undefined;
  /** The answer of the simulation: counts and the line, field and reason of each rejection. Never a value of the file. */
  readonly simulation?: ImportRunResponseOutput | undefined;
  readonly result?: ImportRunResponseOutput | undefined;
  readonly confirming: boolean;
  readonly onFileChange: (file: File | undefined) => void;
  readonly onSimulate: () => void;
  readonly onTemplate: () => void;
  readonly onDownloadRejections: () => void;
  readonly onOpenApply: () => void;
  readonly onCloseApply: () => void;
  readonly onApply: () => void;
  readonly onReset: () => void;
}

/** The rejections shown at a time. They are paged here, from the answer of the simulation or of the apply (at most 200 of them). */
export const REJECTIONS_PAGE_SIZE = 10;
const INPUT_ID = "import-file";
const NUMBERS = new Intl.NumberFormat("es-HN");

function Count({ label, value }: { readonly label: string; readonly value: number }) {
  return (
    <div className="flex flex-col gap-0.5 rounded-md border border-border-subtle p-3">
      <dt className="text-sm text-text-secondary">{label}</dt>
      <dd className="text-2xl font-semibold tabular-nums text-text-primary">{NUMBERS.format(value)}</dd>
    </div>
  );
}

interface RejectionsTableProps {
  readonly rejections: readonly RejectionResponseOutput[];
  readonly caption: string;
  /** Names the pagination, so the simulation and the result are never two landmarks called alike. */
  readonly navigation: string;
}

function RejectionsTable({ rejections, caption, navigation }: RejectionsTableProps) {
  const { t } = useAdminText();
  const [page, setPage] = useState(0);
  const rows = rejections.slice(page * REJECTIONS_PAGE_SIZE, (page + 1) * REJECTIONS_PAGE_SIZE);
  const columns: readonly TableColumn<RejectionResponseOutput>[] = [
    { id: "line", header: t("imports.rejections.line"), align: "end", cell: (row) => String(row.line) },
    {
      id: "field",
      header: t("imports.rejections.field"),
      cell: (row) => {
        const field = fieldNameOf(row.field);
        return field.kind === "column" ? field.name : field.kind === "row" ? t("imports.rejections.rowField") : t("imports.rejections.unknownField");
      },
    },
    { id: "reason", header: t("imports.rejections.reason"), cell: (row) => t(`imports.reason.${row.reason}`) },
  ];
  return (
    <>
      <Table
        caption={caption}
        columns={columns}
        rows={rows}
        getRowId={(row) => `${String(row.line)}-${row.field}-${row.reason}`}
        empty={{ title: t("imports.rejections.none"), description: t("imports.rejections.description"), action: { label: t("imports.rejections.download"), onAction: () => undefined } }}
      />
      <Pagination
        page={page}
        size={REJECTIONS_PAGE_SIZE}
        total={rejections.length}
        label={navigation}
        onPageChange={(next) => {
          setPage(next);
        }}
      />
    </>
  );
}

/**
 * The import of students as one screen of four steps: choose the file, simulate it (counts, and the line, field and reason of
 * each rejection, never a value of the file), apply it behind a confirmation that says how many will be created, and the
 * result. It makes no request and keeps no file: the container holds the file in memory and lets it go when done. Focus goes
 * to what the person has to read next (the field with a problem, the failure, the summary, the result), and the progress is
 * announced in a polite region.
 */
export function ImportWizard(props: ImportWizardProps) {
  const { step, phase, inputKey, fileProblem, failure, simulation, result, confirming } = props;
  const { t } = useAdminText();
  const inputRef = useRef<HTMLInputElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const summaryRef = useRef<HTMLHeadingElement>(null);
  const resultRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (fileProblem !== undefined) inputRef.current?.focus();
  }, [fileProblem]);
  useEffect(() => {
    if (failure !== undefined) failureRef.current?.focus();
  }, [failure]);
  useEffect(() => {
    if (simulation !== undefined) summaryRef.current?.focus();
  }, [simulation]);
  useEffect(() => {
    if (result !== undefined) resultRef.current?.focus();
  }, [result]);

  const busy = phase !== "idle";
  const status =
    phase === "simulating"
      ? t("imports.simulating")
      : phase === "applying"
        ? t("imports.apply.applying")
        : simulation !== undefined && result === undefined
          ? t("imports.simulated", { valid: simulation.batch.validRows, rejected: simulation.batch.rejectedRows, skipped: simulation.batch.skippedRows })
          : "";

  const submit = (event: SyntheticEvent) => {
    event.preventDefault();
    props.onSimulate();
  };

  return (
    <section aria-labelledby="imports-heading" aria-busy={busy} className="flex flex-col gap-4">
      <h2 id="imports-heading" className="text-xl font-semibold text-text-primary">
        {t("imports.heading")}
      </h2>
      <p className="text-sm text-text-secondary">{t("imports.intro")}</p>
      <ImportStepper current={step} />
      <div role="status" aria-live="polite" aria-atomic="true" className="min-h-5 text-sm text-text-secondary">
        {status}
      </div>
      {failure === undefined ? null : (
        <Alert ref={failureRef} variant="destructive" title={t("imports.failed.title")}>
          <ul className="list-disc pl-5">
            {failure.texts.map((text, index) => (
              <li key={`${text.key}-${String(index)}`}>{t(text.key, text.values)}</li>
            ))}
          </ul>
          {failure.traceId === undefined ? null : <p className="mt-1">{t("organization.common.trace", { traceId: failure.traceId })}</p>}
          {failure.kind === "other" ? null : (
            <Button variant="secondary" size="sm" className="mt-2" loading={phase === "simulating"} onClick={props.onSimulate}>
              {t("imports.failed.simulateAgain")}
            </Button>
          )}
        </Alert>
      )}

      {result !== undefined ? (
        <section aria-labelledby="imports-result-heading" className="flex flex-col gap-3">
          <h3 id="imports-result-heading" ref={resultRef} tabIndex={-1} className="text-lg font-semibold text-text-primary">
            {t("imports.result.heading")}
          </h3>
          <Alert variant="info">
            {t("imports.result.done", { created: result.batch.createdRows, skipped: result.batch.skippedRows, rejected: result.batch.rejectedRows })}
          </Alert>
          <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Count label={t("imports.summary.total")} value={result.batch.totalRows} />
            <Count label={t("imports.result.created")} value={result.batch.createdRows} />
            <Count label={t("imports.summary.rejected")} value={result.batch.rejectedRows} />
            <Count label={t("imports.summary.skipped")} value={result.batch.skippedRows} />
          </dl>
          {result.rejections.length === 0 ? null : (
            <div className="flex flex-col gap-3">
              <h4 className="text-base font-semibold text-text-primary">{t("imports.result.rejectionsHeading")}</h4>
              <p className="text-sm text-text-secondary">{t("imports.result.rejectionsDescription")}</p>
              {result.rejectionsTruncated ? <Alert variant="warning">{t("imports.rejections.truncated")}</Alert> : null}
              <RejectionsTable
                key={result.batch.id}
                rejections={result.rejections}
                caption={t("imports.result.rejectionsCaption")}
                navigation={t("imports.result.rejectionsNavigation")}
              />
              <div>
                <Button variant="secondary" onClick={props.onDownloadRejections}>
                  {t("imports.rejections.download")}
                </Button>
              </div>
            </div>
          )}
          <p className="text-sm text-text-secondary">{t("imports.result.next")}</p>
          <div>
            <Button onClick={props.onReset}>{t("imports.result.another")}</Button>
          </div>
        </section>
      ) : (
        <>
          <form noValidate onSubmit={submit} className="flex flex-col gap-3">
            <FormField
              label={t("imports.file.label")}
              controlId={INPUT_ID}
              description={`${t("imports.file.help")} ${t("imports.file.privacy")}`}
              error={fileProblem === undefined ? "" : t(`imports.file.errors.${fileProblem === "too-large" ? "tooLarge" : fileProblem}`)}
              className="max-w-2xl"
            >
              {(control) => (
                <Input
                  {...control}
                  key={inputKey}
                  ref={inputRef}
                  type="file"
                  accept=".csv,text/csv"
                  autoComplete="off"
                  className="h-auto py-2"
                  onChange={(event) => {
                    props.onFileChange(event.currentTarget.files?.[0]);
                  }}
                />
              )}
            </FormField>
            <div className="flex flex-wrap items-center gap-3">
              <Button type="submit" loading={phase === "simulating"} disabled={phase === "applying"}>
                {t("imports.simulate")}
              </Button>
              <Button type="button" variant="secondary" onClick={props.onTemplate}>
                {t("imports.template")}
              </Button>
            </div>
          </form>

          {simulation === undefined ? null : (
            <section aria-labelledby="imports-summary-heading" className="flex flex-col gap-3 border-t border-border-subtle pt-4">
              <h3 id="imports-summary-heading" ref={summaryRef} tabIndex={-1} className="text-lg font-semibold text-text-primary">
                {t("imports.summary.heading")}
              </h3>
              <p className="text-sm text-text-secondary">{t("imports.summary.description")}</p>
              <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                <Count label={t("imports.summary.total")} value={simulation.batch.totalRows} />
                <Count label={t("imports.summary.valid")} value={simulation.batch.validRows} />
                <Count label={t("imports.summary.rejected")} value={simulation.batch.rejectedRows} />
                <Count label={t("imports.summary.skipped")} value={simulation.batch.skippedRows} />
              </dl>
              {simulation.rejections.length === 0 ? (
                <p className="text-sm text-text-secondary">{t("imports.rejections.none")}</p>
              ) : (
                <div className="flex flex-col gap-3">
                  <h4 className="text-base font-semibold text-text-primary">{t("imports.rejections.heading")}</h4>
                  <p className="text-sm text-text-secondary">{t("imports.rejections.description")}</p>
                  {simulation.rejectionsTruncated ? <Alert variant="warning">{t("imports.rejections.truncated")}</Alert> : null}
                  <RejectionsTable
                    key={simulation.batch.id}
                    rejections={simulation.rejections}
                    caption={t("imports.rejections.caption")}
                    navigation={t("imports.rejections.navigation")}
                  />
                  <div>
                    <Button variant="secondary" onClick={props.onDownloadRejections}>
                      {t("imports.rejections.download")}
                    </Button>
                  </div>
                </div>
              )}
              {simulation.batch.validRows > 0 ? (
                <div>
                  <Button onClick={props.onOpenApply}>{t("imports.apply.action")}</Button>
                </div>
              ) : (
                <Alert variant="warning" title={t("imports.noRows.title")}>
                  {t("imports.noRows.body")}
                </Alert>
              )}
            </section>
          )}
        </>
      )}

      {simulation === undefined ? null : (
        <Dialog
          open={confirming}
          onOpenChange={(open) => {
            if (!open && phase !== "applying") props.onCloseApply();
          }}
          title={t("imports.apply.dialogTitle")}
          description={t("imports.apply.dialogDescription")}
          footer={
            <>
              <Button variant="secondary" disabled={phase === "applying"} onClick={props.onCloseApply}>
                {t("imports.apply.cancel")}
              </Button>
              <Button loading={phase === "applying"} onClick={props.onApply}>
                {t("imports.apply.confirm", { count: simulation.batch.validRows })}
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-2">
            <p className="text-lg font-semibold text-text-primary">{t("imports.apply.willCreate", { count: simulation.batch.validRows })}</p>
            <p className="text-sm text-text-secondary">
              {t("imports.apply.notCreated", { rejected: simulation.batch.rejectedRows, skipped: simulation.batch.skippedRows })}
            </p>
            <Alert variant="warning">{t("imports.apply.documentsLate")}</Alert>
            <p className="text-sm text-text-secondary">{t("imports.apply.sameFile")}</p>
          </div>
        </Dialog>
      )}
    </section>
  );
}

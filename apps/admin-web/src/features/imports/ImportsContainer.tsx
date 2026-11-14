import type { ImportRunResponseOutput } from "@confia/contracts/admin";
import { useRef, useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { toApiParams } from "../organization/list-search.ts";
import type { SearchChange } from "../organization/search-change.ts";
import { useListState } from "../organization/use-list-state.ts";
import { ImportHistory } from "./components/ImportHistory.tsx";
import { ImportWizard, type ImportPhase } from "./components/ImportWizard.tsx";
import { ReconciliationContainer } from "./ReconciliationContainer.tsx";
import { downloadText } from "./download.ts";
import {
  REJECTIONS_FILE_NAME,
  TEMPLATE_FILE_NAMES,
  failureOfImport,
  fieldNameOf,
  problemOfFile,
  rejectionsCsv,
  templateTextOf,
  type FileProblem,
  type ImportFailure,
  type ImportKind,
  type ImportStep,
} from "./import-model.ts";
import type { ImportsSearch } from "./imports-search.ts";
import { useApplyImport, useImportHistory, useSimulateImport } from "./use-imports-queries.ts";

export interface ImportsContainerProps {
  readonly search: ImportsSearch;
  readonly onSearchChange: SearchChange<ImportsSearch>;
}

/**
 * The import of students, guardians, the links between them and the enrollments, and its history. The kind to load is a choice of the screen (local
 * state, never the address); the history is filtered by kind from the address, like any other listing. The file is held here, in memory, from the moment it is chosen until the import is
 * applied, the person chooses another one or leaves the screen: it is never written to the address, to the storage, to the
 * query cache or to a log, and it is not read here (the browser sends it as it is). What the screen keeps of a run is its counts
 * and the line, field and reason of each rejection. The server decides everything: the summary is its answer, and the apply
 * is refused by it when the file is not the one that was simulated.
 */
export function ImportsContainer({ search, onSearchChange }: ImportsContainerProps) {
  const { t } = useAdminText();
  const [kind, setKind] = useState<ImportKind>("students");
  const simulate = useSimulateImport(kind);
  const apply = useApplyImport(kind);
  const history = useImportHistory(toApiParams({ page: search.page, pageSize: search.pageSize, kind: search.kind }));
  const [file, setFile] = useState<File | undefined>();
  const [phase, setPhase] = useState<ImportPhase>("idle");
  const [fileProblem, setFileProblem] = useState<FileProblem | "required" | undefined>();
  const [failure, setFailure] = useState<ImportFailure | undefined>();
  const [simulation, setSimulation] = useState<ImportRunResponseOutput | undefined>();
  const [result, setResult] = useState<ImportRunResponseOutput | undefined>();
  const [confirming, setConfirming] = useState(false);
  const [inputKey, setInputKey] = useState(0);
  // A second send waits for the first: the state may not have rendered yet when a double click arrives.
  const inFlight = useRef(false);
  const historyState = useListState({
    list: history,
    search,
    onSearchChange,
    filtered: search.kind !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, kind: undefined, actsPage: search.actsPage },
    onCreate: () => {
      document.getElementById("import-file")?.focus();
    },
    loadFailedKey: "imports.history.loadFailed",
    emptyKeys: { title: "imports.history.emptyTitle", description: "imports.history.emptyDescription", action: "imports.history.emptyAction" },
  });

  const step: ImportStep = result !== undefined ? "result" : confirming || phase === "applying" ? "apply" : simulation !== undefined || phase === "simulating" ? "simulate" : "choose";

  /** Lets go of the file and of everything shown about it. The input gets a new, empty control. */
  const release = () => {
    setFile(undefined);
    setFileProblem(undefined);
    setFailure(undefined);
    setSimulation(undefined);
    setConfirming(false);
    setInputKey((key) => key + 1);
  };

  /** Another kind is another file: what was chosen and shown for the previous one is let go. */
  const onKindChange = (next: ImportKind) => {
    if (inFlight.current || next === kind) return;
    setKind(next);
    setResult(undefined);
    release();
  };

  const onFileChange = (chosen: File | undefined) => {
    setFile(chosen);
    setFailure(undefined);
    setSimulation(undefined);
    setResult(undefined);
    setConfirming(false);
    setFileProblem(chosen === undefined ? undefined : problemOfFile(chosen));
  };

  const onSimulate = async () => {
    if (inFlight.current) return;
    if (file === undefined) {
      setFileProblem("required");
      return;
    }
    const problem = problemOfFile(file);
    if (problem !== undefined) {
      setFileProblem(problem);
      return;
    }
    inFlight.current = true;
    setFileProblem(undefined);
    setFailure(undefined);
    setSimulation(undefined);
    setPhase("simulating");
    try {
      setSimulation(await simulate(file));
    } catch (error) {
      setFailure(failureOfImport(error, kind));
    } finally {
      inFlight.current = false;
      setPhase("idle");
    }
  };

  const onApply = async () => {
    if (inFlight.current || file === undefined) return;
    inFlight.current = true;
    setFailure(undefined);
    setPhase("applying");
    try {
      const applied = await apply(file);
      setResult(applied);
      release();
    } catch (error) {
      const failed = failureOfImport(error, kind);
      setConfirming(false);
      if (failed.kind === "other") {
        // Not knowing whether it finished is not a reason to be afraid of trying again: an applied row is skipped the next time.
        const unknown = failed.texts[0]?.key === "problem.unexpected" || failed.texts[0]?.key === "imports.errors.network";
        setFailure(unknown ? { ...failed, texts: [...failed.texts, { key: `imports.kinds.${kind}.applyUnknown` as const }] } : failed);
      } else {
        // The server holds no simulation of this file: the one on the screen no longer counts.
        setSimulation(undefined);
        setFailure(failed);
      }
    } finally {
      inFlight.current = false;
      setPhase("idle");
    }
  };

  const onReset = () => {
    release();
    setResult(undefined);
  };

  return (
    <div className="flex flex-col gap-8">
      <ImportWizard
        kind={kind}
        onKindChange={onKindChange}
        step={step}
        phase={phase}
        inputKey={inputKey}
        fileProblem={fileProblem}
        failure={failure}
        simulation={simulation}
        result={result}
        confirming={confirming}
        onFileChange={onFileChange}
        onSimulate={() => void onSimulate()}
        onTemplate={() => {
          downloadText(TEMPLATE_FILE_NAMES[kind], templateTextOf(kind));
        }}
        onDownloadRejections={() => {
          // On the result step the list is the one the apply answered, which may hold more rows than the simulation showed.
          const shown = result ?? simulation;
          if (shown === undefined) return;
          downloadText(
            REJECTIONS_FILE_NAME,
            rejectionsCsv(
              shown.rejections,
              [t("imports.rejections.csvHeader.line"), t("imports.rejections.csvHeader.field"), t("imports.rejections.csvHeader.reason")],
              (field) => {
                const named = fieldNameOf(field, kind);
                return named.kind === "column" ? named.name : named.kind === "row" ? t("imports.rejections.rowField") : t("imports.rejections.unknownField");
              },
              (reason) => t(`imports.reason.${reason}`),
            ),
          );
        }}
        onOpenApply={() => {
          setConfirming(true);
        }}
        onCloseApply={() => {
          setConfirming(false);
        }}
        onApply={() => void onApply()}
        onReset={onReset}
      />
      <ReconciliationContainer search={search} onSearchChange={onSearchChange} />
      <ImportHistory
        rows={historyState.rows}
        table={historyState.table}
        pagination={historyState.pagination}
        kind={search.kind}
        onKindChange={(next) => {
          onSearchChange({ ...search, kind: next, page: 1 });
        }}
      />
    </div>
  );
}

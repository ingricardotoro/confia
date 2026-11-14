import type { ReplaceStudentGuardiansRequest } from "@confia/contracts/admin";
import { useMemo } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { formatPercent } from "../../shared/format/format-percent.ts";
import { useDebouncedSearch } from "../enrollments/use-debounced-search.ts";
import { loadFailureOf } from "../organization/organization-failure.ts";
import { StudentGuardiansDialog, type StudentGuardiansState } from "./components/StudentGuardiansDialog.tsx";
import type { GuardianSearchState } from "./components/GuardianPicker.tsx";
import { draftsOf } from "./link-model.ts";
import { ADD_GUARDIAN_CONTROL_ID, useLinksForm } from "./use-links-form.ts";
import { useGuardianSearch, useReplaceStudentGuardians, useStudentGuardians } from "./use-guardians-queries.ts";

export interface StudentGuardiansContainerProps {
  readonly studentId: string;
  /** Names the dialog and the notice. Names live in the DOM only. */
  readonly studentName: string;
  /** The set was saved: the message for the notice of the screen, already translated. */
  readonly onSaved: (message: string) => void;
  readonly onClose: () => void;
}

const NO_LINKS: never[] = [];

/**
 * The behavior of the dialog that manages the guardians of a student: it reads the current set (with the sum the server
 * calculated), lets the staff add, change and remove rows, searches active guardians by text (a request only for two
 * characters or more, after a pause), and replaces the whole set at once. Nothing is optimistic: what the screen says
 * after saving is the server's answer, sum included. The browser never adds the percentages.
 */
export function StudentGuardiansContainer({ studentId, studentName, onSaved, onClose }: StudentGuardiansContainerProps) {
  const { t } = useAdminText();
  const current = useStudentGuardians(studentId);
  const replace = useReplaceStudentGuardians(studentId);
  const box = useDebouncedSearch();
  const found = useGuardianSearch(box.text);
  const initial = useMemo(() => (current.data === undefined ? NO_LINKS : draftsOf(current.data.links)), [current.data]);
  const form = useLinksForm({
    initial,
    save: async (body: ReplaceStudentGuardiansRequest) => {
      const saved = await replace(body);
      onSaved(
        saved.links.length === 0
          ? t("guardians.links.doneNone", { name: studentName })
          : t("guardians.links.done", { name: studentName, percent: formatPercent(saved.totalPercent) }),
      );
    },
  });

  const failure = current.isError ? loadFailureOf(current.error) : undefined;
  const taken = new Set(form.rows.map((row) => row.guardian.id));
  const results = (found.data?.items ?? [])
    .filter((item) => !taken.has(item.id))
    .map(({ id, guardianCode, firstName, lastName, status }) => ({ id, guardianCode, firstName, lastName, status }));
  const search: GuardianSearchState = box.waiting
    ? "waiting"
    : box.text === undefined
      ? "idle"
      : found.isError
        ? "failed"
        : found.isPending
          ? "loading"
          : "ready";

  const state: StudentGuardiansState =
    current.isPending
      ? { kind: "loading" }
      : failure !== undefined || current.data === undefined
        ? {
            kind: "failed",
            description: [
              t(failure?.key ?? "problem.unexpected"),
              failure?.traceId === undefined ? "" : t("organization.common.trace", { traceId: failure.traceId }),
            ]
              .filter((part) => part !== "")
              .join(" "),
            onRetry: () => void current.refetch(),
          }
        : {
            kind: "ready",
            savedTotal: formatPercent(current.data.totalPercent),
            hadLinks: current.data.links.length > 0,
            form,
            picker: {
              controlId: ADD_GUARDIAN_CONTROL_ID,
              draft: box.draft,
              onDraftChange: box.setDraft,
              tooShort: box.tooShort,
              full: form.full,
              search,
              results,
              onRetry: () => void found.refetch(),
              onChoose: (guardian) => {
                form.add(guardian);
                box.setDraft("");
              },
            },
          };

  return <StudentGuardiansDialog studentName={studentName} state={state} onChange={form.change} onRemove={form.remove} onClose={onClose} />;
}

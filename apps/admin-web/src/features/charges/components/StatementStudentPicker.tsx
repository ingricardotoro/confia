import { Alert, Button, FormField, Input } from "@confia/ui";
import { useId, useState, type KeyboardEvent } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";

/** `waiting` is the pause after the last key; `loading` is the request in flight. Both say "searching". */
export type StatementStudentSearchState = "idle" | "waiting" | "loading" | "failed" | "ready";

/** What the picker needs of a student to name them: never a document or a date of birth. */
export interface StatementStudentChoice {
  readonly id: string;
  readonly studentCode: string;
  readonly firstName: string;
  readonly lastName: string;
}

export interface StatementStudentPickerProps {
  readonly controlId: string;
  /** What is typed, which may be ahead of the search. */
  readonly draft: string;
  readonly onDraftChange: (draft: string) => void;
  /** One character is typed: too few to search. */
  readonly tooShort: boolean;
  readonly search: StatementStudentSearchState;
  readonly results: readonly StatementStudentChoice[];
  readonly onRetry: () => void;
  /** The student of the statement, once known (the address only holds the id until the statement arrives). */
  readonly chosen?: StatementStudentChoice | undefined;
  /** The address names a student that the statement has not named yet. */
  readonly chosenPending: boolean;
  readonly onChoose: (student: StatementStudentChoice) => void;
  readonly onClear: () => void;
}

const nameOf = (student: StatementStudentChoice): string => `${student.lastName}, ${student.firstName}`;

/**
 * Finds a student by name, last name or code and chooses one: an editable combobox with a listbox of results, as the ARIA
 * pattern says. The arrows move through the results and Enter chooses. The student chosen replaces the box, with a button to
 * change the choice. The server does the searching, and only for a text of two characters or more.
 */
export function StatementStudentPicker({
  controlId,
  draft,
  onDraftChange,
  tooShort,
  search,
  results,
  onRetry,
  chosen,
  chosenPending,
  onChoose,
  onClear,
}: StatementStudentPickerProps) {
  const { t } = useAdminText();
  const listId = useId();
  const [active, setActive] = useState(-1);

  if (chosen !== undefined || chosenPending) {
    return (
      <div role="group" aria-label={t("charges.statement.picker.chosen")} className="flex flex-col gap-1.5">
        <p className="text-sm font-medium text-text-primary">{t("charges.statement.picker.label")}</p>
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border-subtle px-3 py-2">
          <p className="text-sm font-medium text-text-primary">
            {chosen === undefined ? t("charges.statement.picker.chosenLoading") : t("charges.statement.picker.optionText", { name: nameOf(chosen), code: chosen.studentCode })}
          </p>
          <Button variant="secondary" size="sm" onClick={onClear}>
            {t("charges.statement.picker.change")}
          </Button>
        </div>
      </div>
    );
  }

  const open = search === "ready" && results.length > 0;
  const index = Math.min(active, results.length - 1);
  const optionId = (position: number) => `${listId}-option-${String(position)}`;

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (!open) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActive((index + 1) % results.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActive(index <= 0 ? results.length - 1 : index - 1);
    } else if (event.key === "Enter" && index >= 0) {
      event.preventDefault();
      const picked = results[index];
      if (picked !== undefined) onChoose(picked);
    }
  }

  const status =
    search === "waiting" || search === "loading"
      ? t("charges.statement.picker.searching")
      : search === "ready"
        ? t(results.length > 0 ? "charges.statement.picker.found" : "charges.statement.picker.none", { count: results.length })
        : "";

  return (
    <div className="flex flex-col gap-2">
      <FormField
        label={t("charges.statement.picker.searchLabel")}
        controlId={controlId}
        description={t(tooShort ? "charges.statement.picker.tooShort" : "charges.statement.picker.help")}
      >
        {(control) => (
          <Input
            {...control}
            type="text"
            role="combobox"
            aria-autocomplete="list"
            aria-expanded={open}
            {...(open ? { "aria-controls": listId } : {})}
            {...(open && index >= 0 ? { "aria-activedescendant": optionId(index) } : {})}
            autoComplete="off"
            autoCorrect="off"
            spellCheck={false}
            maxLength={100}
            value={draft}
            onChange={(event) => {
              setActive(-1);
              onDraftChange(event.target.value);
            }}
            onKeyDown={onKeyDown}
          />
        )}
      </FormField>
      <p role="status" className="text-sm text-text-secondary">
        {status}
      </p>
      {search === "failed" ? (
        <Alert variant="warning">
          <p>{t("charges.statement.picker.failed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={onRetry}>
            {t("charges.statement.picker.retry")}
          </Button>
        </Alert>
      ) : null}
      {open ? (
        <ul id={listId} role="listbox" aria-label={t("charges.statement.picker.resultsLabel")} className="max-h-48 overflow-y-auto rounded-md border border-border-subtle">
          {results.map((student, position) => (
            <li
              key={student.id}
              id={optionId(position)}
              role="option"
              aria-selected={position === index}
              className={`cursor-pointer px-3 py-2 text-sm ${position === index ? "bg-bg-canvas outline-2 -outline-offset-2 outline-border-focus" : ""}`}
              // The box keeps the focus while the pointer chooses.
              onMouseDown={(event) => {
                event.preventDefault();
              }}
              onMouseEnter={() => {
                setActive(position);
              }}
              onClick={() => {
                onChoose(student);
              }}
            >
              {t("charges.statement.picker.optionText", { name: nameOf(student), code: student.studentCode })}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

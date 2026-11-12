import { Alert, Button, FormField, Input } from "@confia/ui";
import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { choiceNameOf, type StudentChoice } from "../enrollment-model.ts";

/** `waiting` is the pause after the last key; `loading` is the request in flight. Both say "searching". */
export type StudentSearchState = "idle" | "waiting" | "loading" | "failed" | "ready";

export interface StudentPickerProps {
  readonly controlId: string;
  /** Already translated. */
  readonly error?: string | undefined;
  /** What is typed, which may be ahead of the search. */
  readonly draft: string;
  readonly onDraftChange: (draft: string) => void;
  /** One character is typed: too few to search. */
  readonly tooShort: boolean;
  readonly search: StudentSearchState;
  readonly results: readonly StudentChoice[];
  readonly onRetry: () => void;
  readonly chosen?: StudentChoice | undefined;
  readonly onChoose: (student: StudentChoice) => void;
  readonly onClear: () => void;
}

/**
 * Finds a student by name, last name or code and chooses one: an editable combobox with a listbox of results, as the
 * ARIA pattern says. The arrows move through the results, Enter chooses, and a status line says what the search is
 * doing and how many it found. The student chosen replaces the box, with a button to change the choice; the focus
 * follows (to the button when one is chosen, to the box when it is cleared). It is an exact match of nothing:
 * the server does the searching, and only for a text of two characters or more.
 */
export function StudentPicker({ controlId, error, draft, onDraftChange, tooShort, search, results, onRetry, chosen, onChoose, onClear }: StudentPickerProps) {
  const { t } = useAdminText();
  const listId = useId();
  const [active, setActive] = useState(-1);
  const inputRef = useRef<HTMLInputElement>(null);
  const changeRef = useRef<HTMLButtonElement>(null);
  const hadChoice = useRef(chosen !== undefined);

  useEffect(() => {
    const has = chosen !== undefined;
    if (has && !hadChoice.current) changeRef.current?.focus();
    if (!has && hadChoice.current) inputRef.current?.focus();
    hadChoice.current = has;
  }, [chosen]);

  if (chosen !== undefined) {
    return (
      <div role="group" aria-label={t("enrollments.student.chosen")} className="flex flex-col gap-1.5">
        <p className="text-sm font-medium text-text-primary">{t("enrollments.student.label")}</p>
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border-subtle px-3 py-2">
          <p className="text-sm font-medium text-text-primary">
            {t("enrollments.student.optionText", { name: choiceNameOf(chosen), code: chosen.studentCode })}
          </p>
          <Button ref={changeRef} variant="secondary" size="sm" onClick={onClear}>
            {t("enrollments.student.change")}
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
      // Choosing is not sending the form.
      event.preventDefault();
      const picked = results[index];
      if (picked !== undefined) onChoose(picked);
    }
  }

  const status =
    search === "waiting" || search === "loading"
      ? t("enrollments.student.searching")
      : search === "ready"
        ? t(results.length > 0 ? "enrollments.student.found" : "enrollments.student.none", { count: results.length })
        : "";

  return (
    <div className="flex flex-col gap-2">
      <FormField
        label={t("enrollments.student.searchLabel")}
        controlId={controlId}
        required
        description={t(tooShort ? "enrollments.student.tooShort" : "enrollments.student.help")}
        {...(error === undefined ? {} : { error })}
      >
        {(control) => (
          <Input
            {...control}
            ref={inputRef}
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
          <p>{t("enrollments.student.failed")}</p>
          <Button variant="secondary" size="sm" className="mt-2" onClick={onRetry}>
            {t("enrollments.student.retry")}
          </Button>
        </Alert>
      ) : null}
      {open ? (
        <ul id={listId} role="listbox" aria-label={t("enrollments.student.resultsLabel")} className="max-h-48 overflow-y-auto rounded-md border border-border-subtle">
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
              {t("enrollments.student.optionText", { name: choiceNameOf(student), code: student.studentCode })}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

import { Alert, Button, FormField, Input } from "@confia/ui";
import { useId, useState, type KeyboardEvent } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { choiceNameOf, type GuardianChoice } from "../link-model.ts";

/** `waiting` is the pause after the last key; `loading` is the request in flight. Both say "searching". */
export type GuardianSearchState = "idle" | "waiting" | "loading" | "failed" | "ready";

export interface GuardianPickerProps {
  readonly controlId: string;
  /** What is typed, which may be ahead of the search. */
  readonly draft: string;
  readonly onDraftChange: (draft: string) => void;
  /** One character is typed: too few to search. */
  readonly tooShort: boolean;
  /** No more guardians fit in the set: the box is replaced by the reason. */
  readonly full: boolean;
  readonly search: GuardianSearchState;
  /** Without the guardians that are already in the set. */
  readonly results: readonly GuardianChoice[];
  readonly onRetry: () => void;
  readonly onChoose: (guardian: GuardianChoice) => void;
}

/**
 * Finds an active guardian by name, last name or code and adds it to the set: an editable combobox with a listbox of
 * results, as the ARIA pattern says. The arrows move through the results, Enter adds, and a status line says what the
 * search is doing and how many it found. The server does the searching, and only for a text of two characters or more.
 */
export function GuardianPicker({ controlId, draft, onDraftChange, tooShort, full, search, results, onRetry, onChoose }: GuardianPickerProps) {
  const { t } = useAdminText();
  const listId = useId();
  const [active, setActive] = useState(-1);

  if (full) return <p className="text-sm text-text-secondary">{t("guardians.links.maxReached")}</p>;

  const open = search === "ready" && results.length > 0;
  const index = Math.min(active, results.length - 1);
  const optionId = (position: number) => `${listId}-option-${String(position)}`;

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    // Enter in the search box never sends the whole set: it only adds the guardian that is highlighted, if any.
    if (event.key === "Enter") {
      event.preventDefault();
      const picked = open && index >= 0 ? results[index] : undefined;
      if (picked !== undefined) {
        setActive(-1);
        onChoose(picked);
      }
      return;
    }
    if (!open) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActive((index + 1) % results.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActive(index <= 0 ? results.length - 1 : index - 1);
    }
  }

  const status =
    search === "waiting" || search === "loading"
      ? t("guardians.picker.searching")
      : search === "ready"
        ? t(results.length > 0 ? "guardians.picker.found" : "guardians.picker.none", { count: results.length })
        : "";

  return (
    <div className="flex flex-col gap-2">
      <FormField label={t("guardians.picker.label")} controlId={controlId} description={t(tooShort ? "guardians.picker.tooShort" : "guardians.picker.help")}>
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
          <p>{t("guardians.picker.failed")}</p>
          <Button variant="secondary" size="sm" className="mt-2" onClick={onRetry}>
            {t("guardians.picker.retry")}
          </Button>
        </Alert>
      ) : null}
      {open ? (
        <ul id={listId} role="listbox" aria-label={t("guardians.picker.resultsLabel")} className="max-h-48 overflow-y-auto rounded-md border border-border-subtle">
          {results.map((guardian, position) => (
            <li
              key={guardian.id}
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
                setActive(-1);
                onChoose(guardian);
              }}
            >
              {t("guardians.picker.optionText", { name: choiceNameOf(guardian), code: guardian.guardianCode })}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

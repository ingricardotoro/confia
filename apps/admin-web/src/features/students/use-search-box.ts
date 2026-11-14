import { useEffect, useRef, useState } from "react";
import { searchTextOf } from "./students-search.ts";

/** How long the box waits after the last key before it asks the server. */
export const SEARCH_DEBOUNCE_MS = 300;

export interface SearchBox {
  /** What the person has typed, which may be ahead of the address. */
  readonly draft: string;
  readonly onDraftChange: (draft: string) => void;
  /** One character is typed: too few to search, so the screen says so. */
  readonly tooShort: boolean;
}

/**
 * The text box of a search that lives in the address. What is typed shows at once; the address (and so the server)
 * follows after a pause, and only with a text long enough to search. A change of the address that the box did not
 * make (the back button, a link, "remove filters") replaces what is typed. A text the box itself wrote never does:
 * a key pressed while the address catches up is not lost.
 */
export function useSearchBox(committed: string | undefined, onCommit: (text: string | undefined) => void): SearchBox {
  const [draft, setDraft] = useState(committed ?? "");
  const pushed = useRef(committed);
  const commit = useRef(onCommit);

  useEffect(() => {
    commit.current = onCommit;
  });

  useEffect(() => {
    if (committed === pushed.current) return;
    pushed.current = committed;
    setDraft(committed ?? "");
  }, [committed]);

  useEffect(() => {
    const next = searchTextOf(draft);
    if (next === pushed.current) return;
    const timer = setTimeout(() => {
      pushed.current = next;
      commit.current(next);
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [draft]);

  return { draft, onDraftChange: setDraft, tooShort: draft.trim().length === 1 };
}

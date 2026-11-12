import { useEffect, useState } from "react";
import { SEARCH_DEBOUNCE_MS } from "../students/use-search-box.ts";
import { searchTextOf } from "../students/students-search.ts";

export interface DebouncedSearch {
  /** What the person has typed. */
  readonly draft: string;
  readonly setDraft: (draft: string) => void;
  /** The text to ask the server, once the typing paused: absent while the draft is too short or the pause is not over. */
  readonly text: string | undefined;
  /** A text long enough to search is typed and the pause after the last key is not over. */
  readonly waiting: boolean;
  /** One character is typed: too few to search. */
  readonly tooShort: boolean;
}

/**
 * The text box of a search that is not in the address (the one inside a dialog). What is typed shows at once; the text to
 * search follows after a pause, and only when it has two characters or more once trimmed, like the search of the students list.
 */
export function useDebouncedSearch(delay: number = SEARCH_DEBOUNCE_MS): DebouncedSearch {
  const [draft, setDraft] = useState("");
  const [settled, setSettled] = useState<string>();
  const wanted = searchTextOf(draft);

  useEffect(() => {
    if (wanted === settled) return;
    const timer = setTimeout(() => {
      setSettled(wanted);
    }, delay);
    return () => {
      clearTimeout(timer);
    };
  }, [wanted, settled, delay]);

  return {
    draft,
    setDraft,
    text: wanted !== undefined && wanted === settled ? wanted : undefined,
    waiting: wanted !== undefined && wanted !== settled,
    tooShort: draft.trim().length === 1,
  };
}

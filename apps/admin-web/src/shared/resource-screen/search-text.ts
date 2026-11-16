/** The server refuses a text shorter than this (`400 validation-failed`), so the screen never sends one. */
export const MIN_QUERY_LENGTH = 2;

/** The text a box holds, as the search takes it: trimmed, and absent while it is too short to search. */
export function searchTextOf(draft: string): string | undefined {
  const text = draft.trim();
  return text.length >= MIN_QUERY_LENGTH ? text : undefined;
}

/**
 * How a container asks to change the search of its listing. The route behind it writes the URL: a change of the
 * page adds a history entry, a change of a filter replaces it (docs/ui-ux/04, section 3.2, rule 2).
 */
export type SearchChange<S> = (next: S, options?: { readonly replace: boolean }) => void;

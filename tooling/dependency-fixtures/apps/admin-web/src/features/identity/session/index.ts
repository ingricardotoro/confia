// Accepted case, not a violation: the public entry of the session module may import its own draft.
import { enrollmentDraft } from "./enrollment-draft.ts";

export const accessSession = enrollmentDraft;

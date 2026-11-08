// Deliberate violation of admin-token-store-is-private: the enrollment screen reading the draft directly.
import { enrollmentDraft } from "../session/enrollment-draft.ts";

export const leaked = enrollmentDraft;

// Accepted case, not a violation: the session module is the one place that may read the token store.
import { tokenStore } from "./token-store.ts";

export const accessSession = tokenStore;

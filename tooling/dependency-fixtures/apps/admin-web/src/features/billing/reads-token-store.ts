// Deliberate violation of admin-token-store-is-private: a feature reading the token store directly.
import { tokenStore } from "../identity/session/token-store.ts";

export const leaked = tokenStore;

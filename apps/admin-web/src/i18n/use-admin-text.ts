import { useTranslation } from "react-i18next";
import { ADMIN_NAMESPACE, type AdminKey } from "./resources.ts";

export interface AdminText {
  /** Resolves a catalog key. Only keys of the es-HN admin catalog compile (ADR-0011). */
  readonly t: (key: AdminKey, values?: Record<string, string | number>) => string;
}

/**
 * Text for the app's own screens, the mirror of `useUiText` in the design system: it wraps
 * `useTranslation("admin")` and narrows `t` to `AdminKey` instead of augmenting i18next's global
 * `CustomTypeOptions`.
 */
export function useAdminText(): AdminText {
  const { t } = useTranslation(ADMIN_NAMESPACE);
  return { t: (key, values) => (values === undefined ? t(key) : t(key, values)) };
}

import { useTranslation } from "react-i18next";
import { UI_NAMESPACE, type UiKey } from "./resources";

export interface UiText {
  /** Resolves a catalog key. Only keys of the es-HN catalog compile (ADR-0011). */
  readonly t: (key: UiKey, values?: Record<string, string | number>) => string;
}

/**
 * Text for design-system components. It wraps `useTranslation("ui")` and narrows `t` to `UiKey`
 * instead of augmenting i18next's global `CustomTypeOptions`: each application declares its own
 * namespaces, and two global declarations of `resources` do not merge.
 */
export function useUiText(): UiText {
  const { t } = useTranslation(UI_NAMESPACE);
  return { t: (key, values) => (values === undefined ? t(key) : t(key, values)) };
}

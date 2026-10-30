import ui from "./locales/es-HN/ui.json";

/**
 * The design-system catalog. Applications register it in their own i18next instance by importing
 * `uiResources` from `@confia/ui/i18n`. ADR-0011 keeps a single catalog: adding English means adding
 * `locales/en/ui.json` here and registering it, not shipping an empty file today.
 */
export const uiResources = { "es-HN": { ui } } as const;

export const UI_NAMESPACE = "ui";
export const DEFAULT_UI_LANGUAGE = "es-HN";

type Leaves<T> = {
  [K in keyof T & string]: T[K] extends string ? K : `${K}.${Leaves<T[K]>}`;
}[keyof T & string];

/** Every dotted key of the es-HN catalog, derived from the JSON so a missing key is a `tsc` error. */
export type UiKey = Leaves<typeof ui>;

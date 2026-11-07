import { uiResources } from "@confia/ui/i18n";
import admin from "./locales/es-HN/admin.json";

/**
 * The staff catalog, written in the formal "usted" (docs/ui-ux/05, dated note of the change). Its keys
 * are the type `AdminKey`, derived from the es-HN file, so a key that is not in it fails `tsc` (AW26).
 * ADR-0011 keeps a single catalog per package: English means adding `locales/en/admin.json`, not an
 * empty file today.
 */
export const adminResources = { "es-HN": { admin } } as const;

/** The design-system catalog next to the staff one, for the one i18next instance of the app. */
export const appResources = { "es-HN": { ...uiResources["es-HN"], ...adminResources["es-HN"] } } as const;

export const ADMIN_NAMESPACE = "admin";
export const UI_NAMESPACE = "ui";
export const DEFAULT_ADMIN_LANGUAGE = "es-HN";

type Leaves<T> = {
  [K in keyof T & string]: T[K] extends string ? K : `${K}.${Leaves<T[K]>}`;
}[keyof T & string];

/** Every dotted key of the es-HN admin catalog. */
export type AdminKey = Leaves<typeof admin>;

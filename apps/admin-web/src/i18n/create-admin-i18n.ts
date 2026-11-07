import { createInstance, type i18n as I18nInstance } from "i18next";
import ICU from "i18next-icu";
import { ADMIN_NAMESPACE, appResources, DEFAULT_ADMIN_LANGUAGE, UI_NAMESPACE } from "./resources.ts";

export interface CreateAdminI18nOptions {
  /** Active language. Anything other than es-HN falls back to es-HN until a catalog exists for it. */
  readonly lng?: string;
}

/**
 * Builds the i18next instance of the app: the `admin` catalog next to the design-system `ui` one, ICU
 * message format and no backend (ADR-0011). Initialisation is synchronous because the resources are
 * bundled, so the first render has text.
 */
export function createAdminI18n({ lng = DEFAULT_ADMIN_LANGUAGE }: CreateAdminI18nOptions = {}): I18nInstance {
  const instance = createInstance();
  void instance.use(ICU).init({
    lng,
    fallbackLng: DEFAULT_ADMIN_LANGUAGE,
    supportedLngs: [DEFAULT_ADMIN_LANGUAGE],
    nonExplicitSupportedLngs: false,
    ns: [ADMIN_NAMESPACE, UI_NAMESPACE],
    defaultNS: ADMIN_NAMESPACE,
    // A copy: i18next keeps and mutates the object it is given, and instances must not share it.
    resources: structuredClone(appResources),
    initAsync: false,
    interpolation: { escapeValue: false },
    returnNull: false,
  });
  return instance;
}

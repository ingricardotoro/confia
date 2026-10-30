import { createInstance, type i18n as I18nInstance } from "i18next";
import ICU from "i18next-icu";
import { DEFAULT_UI_LANGUAGE, UI_NAMESPACE, uiResources } from "./resources";

export interface CreateUiI18nOptions {
  /** Active language. Anything other than es-HN falls back to es-HN until a catalog exists for it. */
  readonly lng?: string;
}

/**
 * Builds an i18next instance with the design-system catalog and ICU message format (ADR-0011).
 * Used by Storybook and by the tests; applications build their own instance and merge `uiResources`.
 * Initialisation is synchronous because the resources are bundled, so the first render has text.
 */
export function createUiI18n({ lng = DEFAULT_UI_LANGUAGE }: CreateUiI18nOptions = {}): I18nInstance {
  const instance = createInstance();
  void instance.use(ICU).init({
    lng,
    fallbackLng: DEFAULT_UI_LANGUAGE,
    supportedLngs: [DEFAULT_UI_LANGUAGE],
    nonExplicitSupportedLngs: false,
    ns: [UI_NAMESPACE],
    defaultNS: UI_NAMESPACE,
    resources: uiResources,
    initAsync: false,
    interpolation: { escapeValue: false },
    returnNull: false,
  });
  return instance;
}

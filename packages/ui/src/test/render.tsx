import { render, type RenderOptions, type RenderResult } from "@testing-library/react";
import type { i18n as I18nInstance } from "i18next";
import type { ReactElement } from "react";
import { I18nextProvider } from "react-i18next";
import { createUiI18n } from "../i18n/create-ui-i18n";

export interface RenderWithI18nOptions extends Omit<RenderOptions, "wrapper"> {
  /** Defaults to a fresh instance in the default language, es-HN. */
  readonly i18n?: I18nInstance;
}

/**
 * Renders a component with the design-system catalog initialised in the default language, so a test
 * sees the real text and not a raw key (DS60). Every component test uses this instead of `render`.
 */
export function renderWithI18n(
  ui: ReactElement,
  { i18n = createUiI18n(), ...options }: RenderWithI18nOptions = {},
): RenderResult {
  return render(ui, {
    ...options,
    wrapper: ({ children }) => <I18nextProvider i18n={i18n}>{children}</I18nextProvider>,
  });
}

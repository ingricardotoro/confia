import { render, type RenderOptions, type RenderResult } from "@testing-library/react";
import type { i18n as I18nInstance } from "i18next";
import type { ReactElement } from "react";
import { I18nextProvider } from "react-i18next";
import { createAdminI18n } from "../i18n/create-admin-i18n.ts";

export interface RenderWithAdminI18nOptions extends Omit<RenderOptions, "wrapper"> {
  /** Defaults to a fresh instance in the default language, es-HN. */
  readonly i18n?: I18nInstance;
}

/** Renders with the admin and ui catalogs initialised, so a test sees real text and not a raw key (AW24). */
export function renderWithAdminI18n(
  ui: ReactElement,
  { i18n = createAdminI18n(), ...options }: RenderWithAdminI18nOptions = {},
): RenderResult {
  return render(ui, {
    ...options,
    wrapper: ({ children }) => <I18nextProvider i18n={i18n}>{children}</I18nextProvider>,
  });
}

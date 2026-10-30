import { renderHook, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { I18nextProvider } from "react-i18next";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../test/render";
import { collectTextKeys, flattenCatalogKeys } from "../test/text-keys";
import { createUiI18n } from "./create-ui-i18n";
import { uiResources } from "./resources";
import { useUiText } from "./use-ui-text";

function textIn(lng?: string) {
  const i18n = createUiI18n(lng === undefined ? {} : { lng });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <I18nextProvider i18n={i18n}>{children}</I18nextProvider>
  );
  return renderHook(() => useUiText(), { wrapper }).result.current.t;
}

function StatusLabel() {
  const { t } = useUiText();
  return <span>{t("common.status.overdue")}</span>;
}

describe("the es-HN catalog", () => {
  it("DS57: answers in Spanish by default", () => {
    const t = textIn();

    expect(t("common.status.paid")).toBe("Pagado");
  });

  it("passes interpolation values through to the message", () => {
    const t = textIn();

    expect(t("common.status.paid", { count: 2 })).toBe("Pagado");
  });

  it("DS58: falls back to es-HN, never to the raw key, for a language without translations", () => {
    const t = textIn("en");

    expect(t("common.status.paid")).toBe("Pagado");
  });

  it("DS60: a component rendered through the test helper shows real text, not a key", () => {
    renderWithI18n(<StatusLabel />);

    const text = screen.getByText("Vencido").textContent;
    expect(text).not.toBe("");
    expect(text).not.toBe("common.status.overdue");
  });

  it("DS59: every key used by a component in src/ exists in the catalog", () => {
    const catalogKeys = new Set(flattenCatalogKeys(uiResources["es-HN"].ui));
    const usedKeys = collectTextKeys();

    expect(usedKeys.length).toBeGreaterThan(0);
    expect(usedKeys.filter(({ key }) => !catalogKeys.has(key))).toEqual([]);
  });

  it.each([
    ["common.status.paid", "Pagado"],
    ["common.status.pending", "Pendiente"],
    ["common.status.partial", "Parcial"],
    ["common.status.overdue", "Vencido"],
    ["common.status.void", "Anulado"],
    ["common.status.promised", "En promesa"],
    ["common.status.refunded", "Reembolsado"],
  ] as const)("defines %s as %s (docs/ui-ux/01 section 4)", (key, label) => {
    expect(textIn()(key)).toBe(label);
  });

  it("has a non-empty text for every key", () => {
    const keys = flattenCatalogKeys(uiResources["es-HN"].ui);
    const i18n = createUiI18n();

    expect(keys.length).toBeGreaterThan(0);
    for (const key of keys) {
      expect(i18n.t(key, { ns: "ui" }), key).not.toBe("");
      expect(i18n.t(key, { ns: "ui" }), key).not.toBe(key);
    }
  });
});

import { renderHook, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { I18nextProvider } from "react-i18next";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../test/render.tsx";
import { createAdminI18n } from "./create-admin-i18n.ts";
import { adminResources } from "./resources.ts";
import { useAdminText } from "./use-admin-text.ts";

function textIn(lng?: string) {
  const i18n = createAdminI18n(lng === undefined ? {} : { lng });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <I18nextProvider i18n={i18n}>{children}</I18nextProvider>
  );
  return renderHook(() => useAdminText(), { wrapper }).result.current.t;
}

function ContactHint() {
  const { t } = useAdminText();
  return <p>{t("signIn.contact")}</p>;
}

describe("the es-HN admin catalog", () => {
  it("AW24: a component shows its text from the admin catalog, by default and not as a key", () => {
    renderWithAdminI18n(<ContactHint />);

    const text = screen.getByRole("paragraph").textContent;
    expect(text).toBe("Si no recuerda su contraseña, comuníquese con la administración de su institución.");
    expect(text).not.toBe("signIn.contact");
  });

  it("AW25: a language without translations shows the es-HN text and never the raw key", () => {
    const t = textIn("en");

    expect(t("signIn.contact")).toBe(adminResources["es-HN"].admin.signIn.contact);
    expect(t("signIn.contact")).not.toBe("signIn.contact");
  });

  it("registers the ui catalog next to admin, so design-system components find their text", () => {
    const i18n = createAdminI18n();

    expect(i18n.getFixedT("es-HN", "ui")("common.status.paid")).toBe("Pagado");
    expect(i18n.getFixedT("es-HN", "admin")("boot.restoring")).toBe("Restaurando su sesión.");
  });

  it("supports only es-HN and falls back to it", () => {
    const i18n = createAdminI18n({ lng: "en" });

    expect(i18n.options.supportedLngs).toEqual(expect.arrayContaining(["es-HN"]));
    expect(i18n.options.fallbackLng).toEqual(["es-HN"]);
  });

  it("formats ICU messages, as the countdown will", () => {
    const t = textIn();

    expect(t("countdown.remaining", { seconds: 12 })).toBe("Puede intentar de nuevo en 12 s.");
  });

  it("keeps instances independent: one test's language never leaks into the next", () => {
    const first = createAdminI18n();
    const second = createAdminI18n();

    expect(first).not.toBe(second);
    expect(first.store).not.toBe(second.store);
  });
});

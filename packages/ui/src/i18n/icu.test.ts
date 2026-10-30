import { describe, expect, it } from "vitest";
import { createUiI18n } from "./create-ui-i18n";

// ADR-0011 requires ICU message format for plurals. The message is added at run time, in a .ts file,
// so the catalog stays free of test-only keys and i18n.test.tsx keeps a literal-key-only scan clean.
describe("ICU messages", () => {
  const message = "{count, plural, one {# pago vencido} other {# pagos vencidos}}";

  it("formats the singular and the plural of a message", () => {
    const i18n = createUiI18n();
    i18n.addResource("es-HN", "ui", "test.overdueNotice", message);

    expect(i18n.t("test.overdueNotice", { ns: "ui", count: 1 })).toBe("1 pago vencido");
    expect(i18n.t("test.overdueNotice", { ns: "ui", count: 3 })).toBe("3 pagos vencidos");
  });

  it("uses the Spanish plural rules for zero", () => {
    const i18n = createUiI18n();
    i18n.addResource("es-HN", "ui", "test.overdueNotice", message);

    expect(i18n.t("test.overdueNotice", { ns: "ui", count: 0 })).toBe("0 pagos vencidos");
  });
});

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { checkVirtualFile } from "../test/text-keys";

// keys.test-d.ts is the permanent violation that tsc must keep rejecting. This test proves what the
// developer SEES when a violation reaches the code: the diagnostic names the key and the file
// (BX06, BX07). The fixtures are .fixture files read as text and checked as if they sat next to
// use-ui-text.ts, so they never enter the package's own type-check.
function diagnosticsFor(fixture: string, asFile: string) {
  const source = readFileSync(join(import.meta.dirname, "__fixtures__", fixture), "utf8");
  return checkVirtualFile(`src/i18n/${asFile}`, source);
}

// A TypeScript program over React and i18next takes seconds, more under coverage and parallel load.
describe("the typed catalog keys, seen from the developer's side", { timeout: 60_000 }, () => {
  it("BX06: a key missing from the catalog is reported with the key and the file", () => {
    const diagnostics = diagnosticsFor("missing-key.fixture", "violation-missing-key.tsx");

    expect(diagnostics).toHaveLength(1);
    expect(diagnostics[0]?.file).toBe("src/i18n/violation-missing-key.tsx");
    expect(diagnostics[0]?.message).toContain("common.status.inexistente");
    expect(diagnostics[0]?.message).toContain("UiKey");
  });

  it("BX07: a key built at run time is reported, with the key when TypeScript can know it", () => {
    const diagnostics = diagnosticsFor("built-key.fixture", "violation-built-key.tsx");

    expect(diagnostics).toHaveLength(2);
    expect(diagnostics.every(({ file }) => file === "src/i18n/violation-built-key.tsx")).toBe(true);
    expect(diagnostics[0]?.message).toContain("common.status.inexistente");
    expect(diagnostics[1]?.message).toContain("UiKey");
  });

  it("reports nothing for a key that exists", () => {
    const diagnostics = checkVirtualFile(
      "src/i18n/valid-key.tsx",
      [
        'import { useUiText } from "./use-ui-text";',
        "export function Valid() {",
        "  const { t } = useUiText();",
        '  return <span>{t("common.status.paid")}</span>;',
        "}",
        "",
      ].join("\n"),
    );

    expect(diagnostics).toEqual([]);
  });
});

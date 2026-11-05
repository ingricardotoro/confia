import { mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { scanSuppressions } from "./suppression-scanner.js";

// design-system-foundations-and-a11y, task 2.2 (design.md decision 7, ADR-0018 section 4). Every
// suppression in packages/ui cites an ADR that exists, on the same line or the one before.
const REPOSITORY = join(import.meta.dirname, "..", "..");
const ADR_DIRECTORY = join(REPOSITORY, "docs", "adr");
const FIXTURES = join(import.meta.dirname, "__fixtures__", "suppressions");

describe("the suppression scanner", () => {
  it("flags a suppression without a citation, and one that cites an ADR that does not exist", () => {
    const { violations } = scanSuppressions({ root: FIXTURES, adrDirectory: ADR_DIRECTORY, extensions: [".fixture"] });
    const ofViolations = violations.filter((v) => v.file === "violations.fixture");
    expect(ofViolations.map((v) => `${v.file}:${String(v.line)} ${v.reason}`)).toEqual([
      "violations.fixture:1 missing-citation",
      "violations.fixture:3 missing-citation",
      "violations.fixture:5 missing-citation",
      "violations.fixture:7 unknown-adr",
      "violations.fixture:9 missing-citation",
      "violations.fixture:10 missing-citation",
    ]);
  });

  it("accepts a citation on the same line or the line before, and ignores look-alike text", () => {
    const { scannedFiles, violations } = scanSuppressions({
      root: FIXTURES,
      adrDirectory: ADR_DIRECTORY,
      extensions: [".fixture"],
    });
    expect(scannedFiles).toContain("accepted.fixture");
    expect(violations.filter((v) => v.file === "accepted.fixture")).toEqual([]);
  });
});

// admin-web-foundations-and-session, task 1.1 (BX63 to BX65). The scan reaches apps/ as it reaches
// packages/ui, and it skips the output of the tools that run there.
describe("the suppression scanner over an application", () => {
  const scan = () => scanSuppressions({ root: FIXTURES, adrDirectory: ADR_DIRECTORY, extensions: [".fixture"] });

  it("BX63: flags a directive in an application file that cites no ADR, naming the file and the line", () => {
    const flagged = scan().violations.filter((v) => v.file === "admin-web-uncited.fixture");
    expect(flagged).toEqual([{ file: "admin-web-uncited.fixture", line: 2, reason: "missing-citation" }]);
  });

  it("BX64: does not count a directive that cites the ADR that allows it", () => {
    const { suppressions, violations } = scan();
    expect(suppressions.find((s) => s.file === "admin-web-cited.fixture")?.cited).toEqual(["ADR-0018"]);
    expect(violations.filter((v) => v.file === "admin-web-cited.fixture")).toEqual([]);
  });

  it("walks apps/<app>/src and skips target, playwright-report and test-results", () => {
    const root = realpathSync(mkdtempSync(join(tmpdir(), "confia-apps-")));
    try {
      const directive = "// eslint-disable-next-line no-console\nconsole.log(1);\n";
      for (const folder of ["admin-web/src", "api/app/target", "admin-web/playwright-report", "admin-web/test-results"]) {
        mkdirSync(join(root, folder), { recursive: true });
        writeFileSync(join(root, folder, "probe.ts"), directive);
      }
      const { scannedFiles, violations } = scanSuppressions({ root, adrDirectory: ADR_DIRECTORY });
      expect(scannedFiles).toEqual(["admin-web/src/probe.ts"]);
      expect(violations).toEqual([{ file: "admin-web/src/probe.ts", line: 1, reason: "missing-citation" }]);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  // ADR-0018: apps/ has no TypeScript yet, so this cannot evaluate real files. Task 2 creates them.
  it.todo("BX65: the scan of apps/ ends green having evaluated real files of apps/admin-web (task 2)");
});

describe("packages/ui", () => {
  it("has no suppression without a citation to an existing ADR, over a non-empty file set", () => {
    const { scannedFiles, suppressions, violations } = scanSuppressions({
      root: join(REPOSITORY, "packages", "ui"),
      adrDirectory: ADR_DIRECTORY,
    });
    // ADR-0018: the scan must have seen the real suppressions, not an empty tree.
    expect(scannedFiles.length).toBeGreaterThan(0);
    expect(scannedFiles).toContain("src/i18n/keys.test-d.ts");
    expect(suppressions.some((s) => s.file === "src/i18n/keys.test-d.ts" && s.cited.includes("ADR-0011"))).toBe(true);
    expect(violations).toEqual([]);
  });
});

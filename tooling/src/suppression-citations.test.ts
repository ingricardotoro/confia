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
    expect(violations.map((v) => `${v.file}:${String(v.line)} ${v.reason}`)).toEqual([
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

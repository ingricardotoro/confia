import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";

// ADR-0018 section 4: a suppression without a citation to an ADR that exists is a defect. The
// scanner is the same idea as the backend one, for the TypeScript sources of a package.

/** Every way the repository turns a check off: lint, compiler, the a11y gate and a test project. */
const SUPPRESSION_MARKER =
  /eslint-disable|@ts-expect-error|@ts-ignore|@ts-nocheck|a11y:\s*\{\s*disable|\btest:\s*["']off["']/;
const ADR_CITATION = /ADR-(\d{4})/g;
const DEFAULT_EXTENSIONS = [".ts", ".tsx", ".mts", ".cts", ".js", ".mjs", ".cjs"];
const SKIPPED_DIRECTORIES = new Set(["node_modules", "coverage", "dist", ".turbo", "reports", "storybook-static"]);

export interface Suppression {
  readonly file: string;
  readonly line: number;
  /** ADR citations found on the same line, or on the line before when the same line has none. */
  readonly cited: readonly string[];
}

export interface SuppressionViolation {
  readonly file: string;
  readonly line: number;
  readonly reason: "missing-citation" | "unknown-adr";
}

export interface ScanOptions {
  /** Directory to walk. Reported paths are relative to it, with forward slashes. */
  readonly root: string;
  /** `docs/adr`: a cited ADR must exist there as `ADR-NNNN-<slug>.md`. */
  readonly adrDirectory: string;
  readonly extensions?: readonly string[];
}

export interface ScanResult {
  readonly scannedFiles: string[];
  readonly suppressions: Suppression[];
  readonly violations: SuppressionViolation[];
}

function listFiles(directory: string, extensions: readonly string[]): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return SKIPPED_DIRECTORIES.has(entry.name) ? [] : listFiles(path, extensions);
    return extensions.some((extension) => entry.name.endsWith(extension)) ? [path] : [];
  });
}

function citationsIn(line: string): string[] {
  return [...line.matchAll(ADR_CITATION)].map((match) => `ADR-${match[1] ?? ""}`);
}

function existingAdrs(adrDirectory: string): ReadonlySet<string> {
  const numbers = readdirSync(adrDirectory).flatMap((name) => /^ADR-(\d{4})-/.exec(name)?.[1] ?? []);
  return new Set(numbers.map((number) => `ADR-${number}`));
}

export function scanSuppressions({ root, adrDirectory, extensions = DEFAULT_EXTENSIONS }: ScanOptions): ScanResult {
  const adrs = existingAdrs(adrDirectory);
  const files = listFiles(root, extensions).map((path) => ({ path, name: relative(root, path).replaceAll("\\", "/") }));
  const suppressions: Suppression[] = [];
  const violations: SuppressionViolation[] = [];

  for (const { path, name } of files) {
    const lines = readFileSync(path, "utf8").split(/\r?\n/);
    lines.forEach((text, index) => {
      if (!SUPPRESSION_MARKER.test(text)) return;
      const sameLine = citationsIn(text);
      const cited = sameLine.length > 0 ? sameLine : citationsIn(lines[index - 1] ?? "");
      const line = index + 1;
      suppressions.push({ file: name, line, cited });
      if (cited.length === 0) violations.push({ file: name, line, reason: "missing-citation" });
      else if (cited.some((adr) => !adrs.has(adr))) violations.push({ file: name, line, reason: "unknown-adr" });
    });
  }

  return { scannedFiles: files.map((file) => file.name), suppressions, violations };
}

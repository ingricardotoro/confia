import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import type { Result } from "axe-core";
import { describe, expect, it } from "vitest";
import {
  A11Y_TAGS,
  BLOCKING_IMPACTS,
  REPORTED_IMPACTS,
  axeRunOptions,
  partitionViolations,
} from "@confia/config/a11y";

// design.md decisions 8 and 14: critical and serious fail the gate, moderate and minor are reported
// (docs/ui-ux/03 section 11). The tags and the impacts live in a11y.ts and nowhere else; the story
// gate of @confia/ui and the route gate of admin-web (task 12) both import them from here.
const repoRoot = join(import.meta.dirname, "..", "..", "..");
const read = (...path: string[]) => readFileSync(join(repoRoot, ...path), "utf8");

// Older axe results carry `impact: null`, which the current typings no longer admit; the policy must survive it.
function result(id: string, impact: Result["impact"] | null): Result {
  return { id, impact, tags: [], description: id, help: id, helpUrl: "", nodes: [] } as unknown as Result;
}

describe("the accessibility policy", () => {
  it("evaluates exactly the WCAG 2.x A and AA tags, including 2.2 AA [BX66]", () => {
    expect([...A11Y_TAGS]).toEqual(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]);
  });

  it("blocks critical and serious impacts only", () => {
    expect([...BLOCKING_IMPACTS]).toEqual(["critical", "serious"]);
  });

  it("reports moderate and minor impacts only, as an explicit allow-list", () => {
    expect([...REPORTED_IMPACTS]).toEqual(["moderate", "minor"]);
  });

  it("hands the same tags to axe as run options", () => {
    expect(axeRunOptions).toEqual({ runOnly: { type: "tag", values: [...A11Y_TAGS] } });
  });

  describe("partitionViolations", () => {
    it("blocks a critical and a serious violation and only reports a moderate and a minor one [BX67]", () => {
      const critical = result("image-alt", "critical");
      const serious = result("color-contrast", "serious");
      const moderate = result("region", "moderate");
      const minor = result("tabindex", "minor");

      expect(partitionViolations([critical, serious, moderate, minor])).toEqual({
        blocking: [critical, serious],
        reported: [moderate, minor],
      });
    });

    it("blocks a critical and a serious violation", () => {
      const critical = result("image-alt", "critical");
      const serious = result("color-contrast", "serious");

      expect(partitionViolations([critical, serious])).toEqual({ blocking: [critical, serious], reported: [] });
    });

    it("only reports a moderate and a minor violation [DS69]", () => {
      const moderate = result("region", "moderate");
      const minor = result("tabindex", "minor");

      expect(partitionViolations([moderate, minor])).toEqual({ blocking: [], reported: [moderate, minor] });
    });

    it("splits a mixed list and keeps the order of each side", () => {
      const first = result("a", "serious");
      const second = result("b", "minor");
      const third = result("c", "critical");
      const fourth = result("d", "moderate");

      expect(partitionViolations([first, second, third, fourth])).toEqual({
        blocking: [first, third],
        reported: [second, fourth],
      });
    });

    it("blocks a violation whose impact is unknown instead of letting it through [BX68]", () => {
      const unknown = result("mystery", null);
      const missing = result("absent", undefined);

      expect(partitionViolations([unknown, missing])).toEqual({ blocking: [unknown, missing], reported: [] });
    });

    it("blocks an impact string the policy does not recognize (R3-unknown-impact-string) [BX68]", () => {
      const unrecognized = result("mystery", "catastrophic" as unknown as Result["impact"]);
      const empty = result("blank", "" as unknown as Result["impact"]);

      expect(partitionViolations([unrecognized, empty])).toEqual({ blocking: [unrecognized, empty], reported: [] });
    });

    it("finds nothing to block in an empty list", () => {
      expect(partitionViolations([])).toEqual({ blocking: [], reported: [] });
    });

    it("blocks an incomplete color-contrast result: it is never a pass (spec, Appendix B R-3) [BX69]", () => {
      const incomplete = result("color-contrast", "serious");

      expect(partitionViolations([], [incomplete])).toEqual({ blocking: [incomplete], reported: [] });
    });

    it("blocks an incomplete color-contrast result whatever impact axe attached to it [BX69]", () => {
      const incomplete = result("color-contrast", "minor");

      expect(partitionViolations([], [incomplete]).blocking).toEqual([incomplete]);
    });

    it("only reports an incomplete result of any other rule [BX69]", () => {
      const incomplete = result("aria-valid-attr-value", "serious");

      expect(partitionViolations([], [incomplete])).toEqual({ blocking: [], reported: [incomplete] });
    });
  });
});

// The names that only the policy may define (BX71): a second definition in packages/ui would let two
// gates disagree about what blocks.
const POLICY_NAMES = [
  "A11Y_TAGS",
  "BLOCKING_IMPACTS",
  "REPORTED_IMPACTS",
  "BLOCKING_INCOMPLETE_RULES",
  "axeRunOptions",
  "partitionViolations",
];
const SKIPPED_DIRECTORIES = new Set(["node_modules", "coverage", "dist", ".turbo", "storybook-static"]);

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    if (entry.isDirectory()) return SKIPPED_DIRECTORIES.has(entry.name) ? [] : sourceFiles(join(directory, entry.name));
    return /\.(?:ts|tsx|js|mjs|cjs)$/.test(entry.name) ? [join(directory, entry.name)] : [];
  });
}

const definesAPolicyName = (source: string) =>
  POLICY_NAMES.some((name) => new RegExp(`\\b(?:const|let|var|function)\\s+${name}\\b`).test(source));

describe("where the policy lives", () => {
  const uiRoot = join(repoRoot, "packages", "ui");

  it("is defined by no file of packages/ui and has no policy.ts there [BX71, DS96]", () => {
    const files = sourceFiles(uiRoot);
    const definers = files.filter((file) => definesAPolicyName(readFileSync(file, "utf8")));

    expect(files.length).toBeGreaterThan(0);
    expect(definers).toEqual([]);
    expect(existsSync(join(uiRoot, "src", "a11y", "policy.ts"))).toBe(false);
  });

  it("is imported by the story gate and the Storybook panel from @confia/config/a11y [BX71, DS96]", () => {
    for (const file of [
      ["src", "a11y", "run-gate.ts"],
      [".storybook", "preview.tsx"],
    ]) {
      expect(read("packages", "ui", ...file), file.join("/")).toMatch(/from "@confia\/config\/a11y"/);
    }
  });

  it("recognizes a copy of the policy when one exists (deliberate violation)", () => {
    expect(definesAPolicyName("export const BLOCKING_IMPACTS = [];")).toBe(true);
    expect(definesAPolicyName("export function partitionViolations() {}")).toBe(true);
    expect(definesAPolicyName('import { BLOCKING_IMPACTS } from "@confia/config/a11y";')).toBe(false);
  });

  it("is not exported by the public entry point of @confia/ui [BX72]", () => {
    const entry = read("packages", "ui", "src", "index.ts");

    expect(entry.length).toBeGreaterThan(0);
    expect(entry).not.toMatch(/a11y|policy|@confia\/config/i);
    for (const name of POLICY_NAMES) expect(entry).not.toContain(name);
  });
});

describe("the policy module", () => {
  it("imports only types from axe-core and nothing else [BX70]", () => {
    const imports = [...read("packages", "config", "src", "a11y.ts").matchAll(/^import\s.*$/gm)].map((m) => m[0]);

    expect(imports).toEqual(['import type { Result, RunOptions } from "axe-core";']);
  });

  it("is declared in the export map of @confia/config and named in its description [BX107]", () => {
    const manifest = JSON.parse(read("packages", "config", "package.json")) as {
      description: string;
      exports: Record<string, unknown>;
      devDependencies: Record<string, string>;
    };

    expect(manifest.exports["./a11y"]).toEqual({ types: "./src/a11y.ts", default: "./src/a11y.ts" });
    expect(manifest.description).toMatch(/accessibility/i);
    expect(manifest.devDependencies["axe-core"]).toBe("catalog:");
  });
});

describe("the documents that record the move", () => {
  const adr0026 = "ADR-0026-storybook-y-verificacion-de-accesibilidad.md";
  const adr0028 = "ADR-0028-axe-sobre-rutas-renderizadas.md";
  // The original ADR-0026 as merged before this change (7bf5212): 8398 bytes. A dated note may only be
  // appended after them, so the digest of that prefix proves no line was removed or edited [BX101].
  const ORIGINAL_BYTES = 8398;
  const ORIGINAL_SHA256 = "cc343e3b66b93641771080bb11d36bb0006b2898273f05d5b03ee0b6906f7155";

  it("proposes ADR-0028, indexes it and keeps its status at Proposed [BX100]", () => {
    const adr = read("docs", "adr", adr0028);
    const index = read("docs", "adr", "README.md");

    expect(adr).toMatch(/^# ADR-0028: /);
    expect(adr).toMatch(/^- \*\*Estado:\*\* Propuesto$/m);
    expect(index).toContain(`| [0028](${adr0028}) |`);
    expect(index.split("\n").find((line) => line.includes(`(${adr0028})`))).toMatch(/\| Propuesto \|/);
  });

  it("states the fixed matrix, the failure with zero routes and the limit of the simulator in ADR-0028", () => {
    const adr = read("docs", "adr", adr0028);

    expect(adr).toMatch(/@axe-core\/playwright/);
    expect(adr).toMatch(/6 estados por 2 temas/);
    expect(adr).toMatch(/cero rutas/i);
    expect(adr).toMatch(/simulador no es una prueba de la pila real/i);
  });

  it("only appends a dated note to ADR-0026 [BX101]", () => {
    const bytes = Buffer.from(read("docs", "adr", adr0026).replaceAll("\r\n", "\n"), "utf8");
    const original = bytes.subarray(0, ORIGINAL_BYTES);

    expect(createHash("sha256").update(original).digest("hex")).toBe(ORIGINAL_SHA256);
    const note = bytes.subarray(ORIGINAL_BYTES).toString("utf8");
    expect(note).toMatch(/Nota fechada 2026-11-07/);
    expect(note).toContain("@confia/config/a11y");
  });

  it("mentions the axe gate over rendered routes in CLAUDE.md and docs/01 [BX106]", () => {
    const claude = read("CLAUDE.md")
      .split("\n")
      .filter((line) => line.startsWith("| Pruebas del frontend"));
    const architecture = read("docs", "01-arquitectura.md");

    expect(claude).toHaveLength(1);
    expect(claude[0]).toMatch(/rutas renderizadas/);
    expect(claude[0]).toMatch(/Playwright/);
    expect(architecture).toMatch(/rutas renderizadas[^]{0,300}Playwright|Playwright[^]{0,300}rutas renderizadas/);
  });
});

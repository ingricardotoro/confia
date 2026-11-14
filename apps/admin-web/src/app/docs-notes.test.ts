import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { MINIMUM } from "../../e2e/support/a11y-matrix.ts";

// admin-web-staff-sign-in, task 2.2. The dated notes this change adds to the documents under docs/ are checked
// with the technique of src/i18n/voice-note.test.ts: the original text is pinned by length (in characters)
// and digest, so "no line removed or changed" is a fact the test checks and the rest must be only the note.
const DOCS = join(import.meta.dirname, "..", "..", "..", "..", "docs");

// A checkout on Windows may turn line ends into CRLF; the pinned digests are of the LF form.
function read(path: string): string {
  return readFileSync(join(DOCS, path), "utf8").replaceAll("\r\n", "\n");
}

const PATTERNS = { path: "ui-ux/04-patrones-de-interaccion.md", length: 66039, sha256: "7f40bd63c7426a483e410518388836a8fe6695420b94ed918dcebdc97f3a8d68" } as const;

describe("the dated note in docs/ui-ux/04", () => {
  const patterns = read(PATTERNS.path);

  it("leaves every original line as it was, so the note is only an addition", () => {
    const original = patterns.slice(0, PATTERNS.length);

    expect(createHash("sha256").update(original).digest("hex")).toBe(PATTERNS.sha256);
  });

  it("adds one note, dated 2026-11-07, after the original text", () => {
    const added = patterns.slice(PATTERNS.length);

    expect(added.match(/Nota fechada 2026-11-07/g)).toHaveLength(1);
    expect(added).toMatch(/Nota fechada 2026-11-07 \(cambio `admin-web-staff-sign-in`/);
  });

  it("SS37: records that a failed submit focuses the summary and not the first field, and that the summary is a group and not an alert", () => {
    const added = patterns.slice(PATTERNS.length);

    expect(added).toMatch(/foco/);
    expect(added).toMatch(/resumen de errores/);
    expect(added).toMatch(/primer campo/);
    expect(added).toMatch(/`role="group"`/);
    expect(added).toMatch(/`role="alert"`/);
  });

  it("names what it resolves: the two lines of the document and the sources that win", () => {
    const added = patterns.slice(PATTERNS.length);

    expect(added).toContain("04-patrones-de-interaccion.md:393-394");
    expect(added).toContain("docs/ui-ux/03-accesibilidad.md:141");
    expect(added).toContain("SS37");
  });
});

// admin-web-staff-sign-in, task 9.1 (BX102 to BX105, BX108, SS123, I308). The notes of the last PR: the second note of docs/ui-ux/04,
// the first of docs/ui-ux/06 and the closure of exit criterion 8 in docs/09. The same technique: the text before the note is pinned
// by length and digest. In docs/09 the one change to the original lines is the box of the criterion, and the note sits below it.
const REPO = join(DOCS, "..");
const NOTE_DATE = /Nota fechada 2026-11-09 \(cambio `admin-web-staff-sign-in`/;
const PATTERNS_AFTER_FIRST_NOTE = { length: 66959, sha256: "454fe93143bf3a10ed63802285019f6cc269949018412d03893597a86b668fc9" } as const;
const FLOWS = { path: "ui-ux/06-flujos-clave.md", length: 16930, sha256: "8395f2c42e2819fc502c6fc88934a07de644bdf2f439c91c6fe7ad88636670d3" } as const;
const ROADMAP = { path: "09-roadmap-y-fases.md", length: 59621, sha256: "0c1900e40c69b907d2fe47c6bca76fd77578b1c6a6c14b22f0834cd7584afe60" } as const;
const CRITERION = "La verificación de accesibilidad no reporta violaciones críticas en las pantallas existentes.";

const digest = (text: string) => createHash("sha256").update(text).digest("hex");

describe("the second note of docs/ui-ux/04", () => {
  const patterns = read(PATTERNS.path);

  it("BX105: leaves the original and the first note as they were, and adds only a note dated 2026-11-09", () => {
    const earlier = patterns.slice(0, PATTERNS_AFTER_FIRST_NOTE.length);
    const added = patterns.slice(PATTERNS_AFTER_FIRST_NOTE.length);

    expect(digest(earlier)).toBe(PATTERNS_AFTER_FIRST_NOTE.sha256);
    expect(added.match(/Nota fechada/g)).toHaveLength(1);
    expect(added).toMatch(NOTE_DATE);
  });

  it("records where the secrets of the enrollment live, how the key is copied, and how that is checked", () => {
    const added = patterns.slice(PATTERNS_AFTER_FIRST_NOTE.length);

    expect(added).toMatch(/memoria de módulo/);
    expect(added).toMatch(/códigos de recuperación/);
    expect(added).toMatch(/sin espacios/);
    expect(added).toMatch(/Clave copiada\./);
    expect(added).toMatch(/`role="status"`/);
    expect(added).toMatch(/centinelas/);
    expect(added).toMatch(/SS78/);
  });
});

describe("the note of docs/ui-ux/06", () => {
  const flows = read(FLOWS.path);

  it("BX105: leaves every original line as it was and adds only a note dated 2026-11-09", () => {
    const added = flows.slice(FLOWS.length);

    expect(digest(flows.slice(0, FLOWS.length))).toBe(FLOWS.sha256);
    expect(added.match(/Nota fechada/g)).toHaveLength(1);
    expect(added).toMatch(NOTE_DATE);
  });

  it("records the flow as the screens deliver it: the verification, the three steps of the enrollment, and what 13b-2 adds", () => {
    const added = flows.slice(FLOWS.length);

    expect(added).toMatch(/flujo 1/i);
    expect(added).toMatch(/verificación en dos pasos/);
    expect(added).toMatch(/código de recuperación/);
    expect(added).toMatch(/tres pasos/);
    expect(added).toMatch(/casilla/);
    expect(added).toMatch(/13b-2/);
    expect(added).toMatch(/API simulada/);
  });
});

/** The roadmap as it was before this PR (the box open, the note gone) and what was added, with the state of the box. */
function splitCriterion(roadmap: string): { marked: boolean; note: string; flat: string; original: string } {
  const lines = roadmap.split("\n");
  const index = lines.findIndex((line) => line.includes(CRITERION));
  const line = lines[index] ?? "";
  let end = index + 1;
  while (lines[end]?.startsWith("      ")) end += 1;
  return {
    marked: line.startsWith("- [x] "),
    note: lines.slice(index + 1, end).join("\n"),
    // The same text on one line, so a phrase that the note wraps is still found.
    flat: lines.slice(index + 1, end).join(" ").replace(/\s+/g, " "),
    original: [...lines.slice(0, index), line.replace("- [x] ", "- [ ] "), ...lines.slice(end)].join("\n"),
  };
}

/** The rule of BX102 and BX103: a marked criterion must carry the note that cites its proof. */
function assertMarkIsBacked(roadmap: string): void {
  const { marked, flat: note } = splitCriterion(roadmap);
  if (!marked) return;
  for (const [what, found] of [
    ["a dated note", /nota fechada 2026-11-09/i.test(note)],
    ["the change", note.includes("`admin-web-staff-sign-in`")],
    ["the gate", note.includes("e2e/specs/a11y/gate.e2e.ts") && /puerta/.test(note)],
    ["the 22 states in both themes", /22 estados/.test(note) && /dos temas/.test(note)],
    ["the simulated journeys", /API simulada/.test(note)],
  ] as const) {
    if (!found) throw new Error(`the criterion is marked without ${what}`);
  }
}

describe("exit criterion 8 of phase 0 in docs/09", () => {
  const roadmap = read(ROADMAP.path);
  const { marked, note, flat, original } = splitCriterion(roadmap);

  it("BX104: keeps its text and its earlier notes, and adds only the box and one dated note under the criterion", () => {
    expect(original.length).toBe(ROADMAP.length);
    expect(digest(original)).toBe(ROADMAP.sha256);
    expect(flat.match(/nota fechada \d{4}-\d{2}-\d{2}/gi) ?? []).toHaveLength(1);
    expect(flat).toMatch(/nota fechada 2026-11-09/i);
  });

  it("BX104: the note names both changes, the division into 13b-1 and 13b-2, and that the seed and CORS leave 13b", () => {
    expect(flat).toContain("`admin-web-foundations-and-session`");
    expect(flat).toContain("`admin-web-staff-sign-in`");
    expect(flat).toMatch(/13b-1/);
    expect(flat).toMatch(/13b-2/);
    expect(flat).toMatch(/semilla/);
    expect(flat).toMatch(/CORS/);
  });

  it("BX103: the criterion is marked and the note cites the gate, the change, the 22 states in both themes and that the journeys are simulated", () => {
    expect(marked).toBe(true);
    expect(flat).toContain("e2e/specs/a11y/gate.e2e.ts");
    expect(existsSync(join(REPO, "apps", "admin-web", "e2e", "specs", "a11y", "gate.e2e.ts"))).toBe(true);
    expect(flat).toMatch(/22 estados/);
    expect(flat).toMatch(/dos temas/);
    expect(flat).toMatch(/inicio de sesión, verificación, inscripción, arranque y página autenticada/);
    expect(flat).toMatch(/API simulada/);
  });

  it("BX102: a criterion marked without its note, or with a note that lacks the proof, is refused, and the gate covers the five screens", () => {
    const withoutNote = roadmap.replace(note, "");
    const withoutGate = roadmap.replace("e2e/specs/a11y/gate.e2e.ts", "la puerta");

    expect(() => {
      assertMarkIsBacked(roadmap);
    }).not.toThrow();
    expect(() => {
      assertMarkIsBacked(withoutNote);
    }).toThrow(/marked without a dated note/);
    expect(() => {
      assertMarkIsBacked(withoutGate);
    }).toThrow(/marked without the gate/);
    // Marked only if the gate has every screen: the boot and the signed in page, the sign in, the verification and the enrollment.
    expect(MINIMUM).toHaveLength(22);
    expect([...new Set(MINIMUM.map((combination) => combination.route))].sort()).toEqual(["/", "/iniciar-sesion", "/iniciar-sesion/inscripcion", "/iniciar-sesion/verificacion", "/no-existe"]);
  });

  it("SS123, I308: it declares that the journeys ran against a simulated API, that the real stack is change 11 and the gap dated 2026-11-04", () => {
    expect(flat).toMatch(/API simulada/);
    expect(flat).toMatch(/cambio 11/);
    expect(flat).toMatch(/pila real/);
    expect(flat).toContain("2026-11-04");
    expect(flat).toMatch(/cambio 8b/);
  });

  it("BX108: it says that reverting the gate or an evaluated screen unmarks the criterion with a dated note", () => {
    expect(flat).toMatch(/revertir/i);
    expect(flat).toMatch(/desmarca/);
    expect(flat).toMatch(/nota fechada/);
  });
});

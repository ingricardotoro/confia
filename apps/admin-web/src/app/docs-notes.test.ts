import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

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
const NOTE_DATE = /Nota fechada 2026-11-09 \(cambio `admin-web-staff-sign-in`/;
const PATTERNS_AFTER_FIRST_NOTE = { length: 66959, sha256: "454fe93143bf3a10ed63802285019f6cc269949018412d03893597a86b668fc9" } as const;
const FLOWS = { path: "ui-ux/06-flujos-clave.md", length: 16930, sha256: "8395f2c42e2819fc502c6fc88934a07de644bdf2f439c91c6fe7ad88636670d3" } as const;

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

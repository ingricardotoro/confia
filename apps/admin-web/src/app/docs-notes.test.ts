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

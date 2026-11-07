import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// admin-web-foundations-and-session, task 3.1 (AW31). The content guide gets one dated note and nothing
// else is rewritten. The base file is pinned by length (in characters) and digest, so "no line removed or
// changed" is a fact the test checks and not a promise: the original text must still start the file.
const GUIDE = join(import.meta.dirname, "..", "..", "..", "..", "docs", "ui-ux", "05-guia-de-contenido-y-voz.md");
const ORIGINAL_LENGTH = 15297;
const ORIGINAL_SHA256 = "8adf5be3a2b86ef83332ece3b33a594974dcca67faa027dd643f274764ef751f";

// A checkout on Windows may turn line ends into CRLF; the pinned digest is of the LF form.
const guide = readFileSync(GUIDE, "utf8").replaceAll("\r\n", "\n");

describe("the dated note in docs/ui-ux/05", () => {
  it("AW31: leaves every original line as it was, so the note is only an addition", () => {
    const original = guide.slice(0, ORIGINAL_LENGTH);

    expect(createHash("sha256").update(original).digest("hex")).toBe(ORIGINAL_SHA256);
  });

  it("AW31: adds a dated note after the original text", () => {
    const added = guide.slice(ORIGINAL_LENGTH);

    expect(added).toMatch(/Nota fechada 2026-11-05 \(cambio `admin-web-foundations-and-session`/);
  });

  it("AW31: the note sets 'usted' for the staff and leaves the tone of the portal unchanged", () => {
    const added = guide.slice(ORIGINAL_LENGTH);

    expect(added).toMatch(/«usted»/);
    expect(added).toMatch(/personal/);
    expect(added).toMatch(/tono del portal no cambia/);
  });
});

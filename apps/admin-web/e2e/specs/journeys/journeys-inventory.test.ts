// @vitest-environment node
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// admin-web-staff-sign-in, task 9.1 (SS117, I305, SS123, I309). The seven journeys exist, each one titled as such and run with its own
// sentinels, and every file that holds a journey says in its report that it ran against the simulated API. This file reads the
// sources of the journeys and runs in the unit suite: Playwright only matches *.e2e.ts.
const FOLDER = import.meta.dirname;
const read = (name: string) => readFileSync(join(FOLDER, name), "utf8");

/** The titles of the tests of a journey file: `test("<title>", ...` on one line or the next. */
function titlesIn(source: string): string[] {
  return [...source.matchAll(/\btest\(\s*"((?:[^"\\]|\\.)*)"/g)].map((match) => match[1] ?? "");
}

const SEVEN = [
  "sign in with a TOTP code",
  "sign in with a recovery code",
  "sign in with the complete mandatory enrollment",
  "reload that keeps the session",
  "sign out",
  "expired session",
  "expired restricted token",
] as const;

describe("the seven journeys of access", () => {
  const titles = titlesIn(read("secrets.e2e.ts"));
  const journeys = titles.filter((title) => title.startsWith("[SS116, SS117, I305] "));

  it("SS117, I305: each of the seven has its own test, in the order of the specification, with sentinels of its own", () => {
    expect(journeys.map((title) => title.slice("[SS116, SS117, I305] ".length).split(":")[0])).toEqual([...SEVEN]);
  });

  it("SS117: the TOTP sign in and the complete enrollment are also journeys of their own files, so the seven do not rest on one file", () => {
    expect(titlesIn(read("second-factor.e2e.ts")).some((title) => title.startsWith("[I304]"))).toBe(true);
    expect(titlesIn(read("enrollment.e2e.ts")).some((title) => title.startsWith("[SS114, I305]"))).toBe(true);
    expect(titlesIn(read("keyboard.e2e.ts")).some((title) => title.startsWith("[SS114]"))).toBe(true);
  });

  it("SS123: every file that holds a journey of access carries the annotation that it ran against the simulated API", () => {
    const annotation = /annotation:\s*\{\s*type:\s*"api",\s*description:\s*"simulated; not the real stack"\s*\}/;
    const files = ["secrets.e2e.ts", "second-factor.e2e.ts", "enrollment.e2e.ts", "keyboard.e2e.ts"];

    expect(files.filter((file) => !annotation.test(read(file)))).toEqual([]);
  });

  it("covers every journey file in the folder: a file added later is neither a journey nor unlabeled by accident", () => {
    expect(readdirSync(FOLDER).filter((name) => name.endsWith(".e2e.ts")).sort()).toEqual([
      "boot.e2e.ts",
      "enrollment.e2e.ts",
      "enrollments-crud.e2e.ts",
      "enrollments-keyboard.e2e.ts",
      "enrollments-lists.e2e.ts",
      "enrollments-states.e2e.ts",
      "guardians-consents.e2e.ts",
      "guardians-crud.e2e.ts",
      "guardians-keyboard.e2e.ts",
      "guardians-links.e2e.ts",
      "guardians-lists.e2e.ts",
      "guardians-states.e2e.ts",
      "imports-flow.e2e.ts",
      "imports-keyboard.e2e.ts",
      "imports-states.e2e.ts",
      "keyboard.e2e.ts",
      "organization-crud.e2e.ts",
      "organization-keyboard.e2e.ts",
      "organization-lists.e2e.ts",
      "organization-states.e2e.ts",
      "second-factor.e2e.ts",
      "secrets.e2e.ts",
      "sign-in.e2e.ts",
      "signed-in.e2e.ts",
      "students-crud.e2e.ts",
      "students-keyboard.e2e.ts",
      "students-lists.e2e.ts",
      "students-states.e2e.ts",
    ]);
  });
});

// The real sign in with the enrollment of MFA belongs to change 11 (I309, I307). It has no assertion today, and it is registered
// here as what it is.
describe("what change 11 delivers", () => {
  it.todo("I309: against the real backend, the sign in with the mandatory enrollment sets the real cookies, a reload restores the session with one refresh and the sign out clears both cookies");
});

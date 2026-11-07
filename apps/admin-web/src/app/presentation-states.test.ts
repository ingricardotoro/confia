import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// admin-web-foundations-and-session, task 9.1 (part 9b; AW57, AW58). Every presentation component of the app
// (the files of the components folders) has a test file next to it that renders it with props alone and
// that declares, in a comment, which of its states exist and why the others do not apply.
const SRC = join(import.meta.dirname, "..");

function filesUnder(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    return statSync(path).isDirectory() ? filesUnder(path) : [path];
  });
}

const components = filesUnder(SRC)
  .map((file) => file.replaceAll("\\", "/"))
  .filter((file) => /\/components\/[^/]+\.tsx$/.test(file) && !file.endsWith(".test.tsx"));

describe("the presentation components of the app", () => {
  it("are not an empty set", () => {
    expect(components.length).toBeGreaterThan(0);
  });

  it.each(components.map((file) => [file.slice(SRC.length)] as const))("AW57, AW58: %s has a test with a States comment", (file) => {
    const testFile = join(SRC, file).replace(/\.tsx$/, ".test.tsx");

    expect(existsSync(testFile), `${file} has no test file`).toBe(true);
    const source = readFileSync(testFile, "utf8");
    expect(source).toMatch(/^\/\/ States\./m);
    expect(source).toContain("AW57");
  });
});

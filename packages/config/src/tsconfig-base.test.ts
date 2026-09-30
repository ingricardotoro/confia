import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

// Task 2.2: the shared base must keep the strictness every package inherits. A package that
// loosened one of these would do it in its own tsconfig.json, visibly, never by editing the base.
const base = JSON.parse(
  readFileSync(new URL("../tsconfig.base.json", import.meta.url), "utf8"),
) as { compilerOptions: Record<string, unknown> };

describe("tsconfig.base.json", () => {
  it.each([
    "strict",
    "noUncheckedIndexedAccess",
    "exactOptionalPropertyTypes",
    "verbatimModuleSyntax",
  ])("enables %s", (option) => {
    expect(base.compilerOptions[option]).toBe(true);
  });
});

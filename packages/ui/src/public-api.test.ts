import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { MoneyAmount as MoneyAmountByName } from "@confia/ui";
import * as api from "./index";
import { findComponentsWithoutStory, findComponentsWithoutTest, listComponents } from "./a11y/story-coverage";

// design-system, requirement "El punto de entrada público": the components exported are exactly the
// seventeen of this delivery, and nothing else leaks (ADR-0018).
const COMPONENTS = [
  "Badge",
  "Button",
  "Checkbox",
  "EmptyState",
  "ErrorState",
  "FormError",
  "FormField",
  "IconButton",
  "Input",
  "Label",
  "MoneyAmount",
  "RadioGroup",
  "Separator",
  "Skeleton",
  "Spinner",
  "StatusBadge",
  "Textarea",
];
// Not on the list of seventeen, and exported on purpose: the item that RadioGroup cannot be used
// without, and the class recipe of Button for links that look like buttons.
const COMPANIONS = ["RadioGroupItem", "buttonVariants"];
// Exported components that live in the file of another one, so they have no directory of their own.
const SHARES_A_FILE = { FormError: "FormField" };

const srcRoot = import.meta.dirname;

describe("the public entry point of @confia/ui", () => {
  it("DS02: exports exactly the seventeen components and their two companions", () => {
    expect(Object.keys(api).sort()).toEqual([...COMPONENTS, ...COMPANIONS].sort());
  });

  it("DS02: no export is a token and the entry point reads no token file", () => {
    expect(Object.keys(api).filter((name) => /token|palette/i.test(name))).toEqual([]);
    expect(readFileSync(join(srcRoot, "index.ts"), "utf8")).not.toMatch(/tokens|primitives/);
  });

  it("DS01: MoneyAmount imported by the package name is the one of the entry point", () => {
    expect(MoneyAmountByName).toBeTypeOf("function");
    expect(MoneyAmountByName).toBe(api.MoneyAmount);
  });

  it("DS62, DS73: the components with a directory are the exported ones, each with a story and a test", () => {
    const withDirectory = listComponents(srcRoot).map(({ name }) => name);

    expect([...withDirectory, ...Object.keys(SHARES_A_FILE)].sort()).toEqual(COMPONENTS);
    for (const host of Object.values(SHARES_A_FILE)) expect(withDirectory).toContain(host);
    expect(findComponentsWithoutStory(srcRoot)).toEqual([]);
    expect(findComponentsWithoutTest(srcRoot)).toEqual([]);
  });
});

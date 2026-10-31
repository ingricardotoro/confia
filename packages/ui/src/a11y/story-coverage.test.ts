import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { findComponentsWithoutStory, findComponentsWithoutTest, listComponents } from "./story-coverage";

// design.md decision 9: a component without a story, or whose story has no Default, fails the build;
// so does a component without its own test (DS73). The fixture tree holds one deliberate offender of
// each kind; the real src/ must hold none, over a set that is not empty.
const fixtureTree = join(import.meta.dirname, "__fixtures__", "tree");
const realSource = join(import.meta.dirname, "..");

describe("the story coverage check", () => {
  it("lists the components of the fixture tree by level and directory", () => {
    expect(listComponents(fixtureTree).map((component) => component.id)).toEqual([
      "atoms/healthy/Healthy",
      "atoms/orphan/Orphan",
      "molecules/no-default/NoDefault",
      "domain/untested/Untested",
    ]);
  });

  it("detects a component with no story, naming it [BX13]", () => {
    expect(findComponentsWithoutStory(fixtureTree)).toContain("atoms/orphan/Orphan");
  });

  it("detects a story file that has no Default story, naming the component", () => {
    expect(findComponentsWithoutStory(fixtureTree)).toContain("molecules/no-default/NoDefault");
  });

  it("accepts a component whose story exports Default", () => {
    expect(findComponentsWithoutStory(fixtureTree)).not.toContain("atoms/healthy/Healthy");
    expect(findComponentsWithoutStory(fixtureTree)).not.toContain("domain/untested/Untested");
  });

  it("detects a component with no test file, naming it [DS73]", () => {
    expect(findComponentsWithoutTest(fixtureTree)).toEqual(["atoms/orphan/Orphan", "domain/untested/Untested"]);
  });

  it("finds a real set of components in src", () => {
    expect(listComponents(realSource).length).toBeGreaterThan(0);
  });

  it("leaves no component of src without a story [DS62]", () => {
    expect(findComponentsWithoutStory(realSource)).toEqual([]);
  });

  it("leaves no component of src without a test [DS73]", () => {
    expect(findComponentsWithoutTest(realSource)).toEqual([]);
  });
});

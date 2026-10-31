import { composeStories, setProjectAnnotations } from "@storybook/react-vite";
import { describe, expect, it } from "vitest";
import preview from "../../.storybook/preview";
import * as darkOnly from "./__fixtures__/DarkOnlyLowContrast.stories";
import * as inaccessible from "./__fixtures__/Inaccessible.stories";
import * as moderateOnly from "./__fixtures__/ModerateOnly.stories";
import { THEMES, checkStoryInAllThemes, describeBlocking, type GateResult } from "./run-gate";

// design.md decision 8: the gate must reject the permanent deliberate violations, with the real
// stylesheet loaded, or it proves nothing. These run the same function the real stories go through.
setProjectAnnotations([preview]);

const rejected = (results: GateResult[], theme: string) =>
  results.find((result) => result.theme === theme)?.blocking.map((violation) => violation.id) ?? [];

describe("the accessibility gate", () => {
  it("evaluates the light and the dark theme", () => {
    expect([...THEMES]).toEqual(["light", "dark"]);
  });

  it("rejects an image without alternative text, naming story and rule [DS66, BX12]", async () => {
    const { MissingImageAlt } = composeStories(inaccessible);
    const results = await checkStoryInAllThemes(MissingImageAlt);

    expect(rejected(results, "light")).toContain("image-alt");
    expect(describeBlocking(results)).toMatch(/missing-image-alt.*image-alt/s);
  });

  it("rejects a button without an accessible name [DS66, BX12]", async () => {
    const { UnnamedButton } = composeStories(inaccessible);

    expect(rejected(await checkStoryInAllThemes(UnnamedButton), "light")).toContain("button-name");
  });

  it("rejects insufficient contrast, which proves the real CSS is loaded [DS67, BX12]", async () => {
    const { LowContrastText } = composeStories(inaccessible);

    expect(rejected(await checkStoryInAllThemes(LowContrastText), "light")).toContain("color-contrast");
  });

  it("rejects a story that fails only in the dark theme [DS68]", async () => {
    const { Default } = composeStories(darkOnly);
    const results = await checkStoryInAllThemes(Default);

    expect(rejected(results, "light")).toEqual([]);
    expect(rejected(results, "dark")).toContain("color-contrast");
  });

  it("only reports a violation of moderate impact [DS69, BX16]", async () => {
    const { Default } = composeStories(moderateOnly);
    const results = await checkStoryInAllThemes(Default);

    expect(results.flatMap((result) => result.blocking)).toEqual([]);
    expect(results.flatMap((result) => result.reported.map((violation) => violation.id))).toContain(
      "form-field-multiple-labels",
    );
  });
});

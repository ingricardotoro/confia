import { afterEach, describe, expect, it } from "vitest";
import {
  THEMES,
  checkStoryAccessibility,
  checkStoryInAllThemes,
  describeBlocking,
  summarize,
  type GateResult,
  type GateStory,
} from "./run-gate";

// The pure parts of the gate and its plumbing, in jsdom. What only a browser can decide (contrast,
// real stylesheets) is proven by gate.a11y.test.tsx in Chromium.
const storyRendering = (id: string, markup: string): GateStory => ({
  id,
  run: ({ canvasElement } = {}) => {
    if (canvasElement) canvasElement.innerHTML = markup;
    return Promise.resolve();
  },
});

const violation = { id: "image-alt", impact: "critical", help: "Images need alt text", nodes: [{ target: ["img"] }] };
const finding = { ...violation, id: "form-field-multiple-labels", impact: "moderate" };
const result = (blocking: object[], reported: object[], theme: GateResult["theme"] = "light"): GateResult =>
  ({ storyId: "fixtures--demo", theme, blocking, reported }) as unknown as GateResult;

describe("the gate plumbing", () => {
  it("checks the light theme first and the dark one second", () => {
    expect([...THEMES]).toEqual(["light", "dark"]);
  });

  it("sets the theme on the root element before rendering the story", async () => {
    let seen: string | undefined;
    const story: GateStory = {
      id: "fixtures--probe",
      run: () => {
        seen = document.documentElement.dataset["theme"];
        return Promise.resolve();
      },
    };

    await checkStoryAccessibility(story, "dark");

    expect(seen).toBe("dark");
  });

  it("renders into its own container and removes it afterwards", async () => {
    const before = document.body.children.length;

    await checkStoryAccessibility(storyRendering("fixtures--clean", "<p>Texto</p>"), "light");

    expect(document.body.children).toHaveLength(before);
  });

  describe("leaves the page as it found it", () => {
    afterEach(() => {
      delete document.documentElement.dataset["theme"];
    });

    it("restores the data-theme the page had before the check", async () => {
      document.documentElement.dataset["theme"] = "dark";

      await checkStoryAccessibility(storyRendering("fixtures--clean", "<p>Texto</p>"), "light");

      expect(document.documentElement.dataset["theme"]).toBe("dark");
    });

    it("removes the data-theme when the page had none", async () => {
      await checkStoryAccessibility(storyRendering("fixtures--clean", "<p>Texto</p>"), "dark");

      expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
    });

    it("restores the page, and removes the container, when the story throws", async () => {
      document.documentElement.dataset["theme"] = "light";
      const before = document.body.children.length;
      const broken: GateStory = { id: "fixtures--broken", run: () => Promise.reject(new Error("play failed")) };

      await expect(checkStoryAccessibility(broken, "dark")).rejects.toThrow("play failed");

      expect(document.documentElement.dataset["theme"]).toBe("light");
      expect(document.body.children).toHaveLength(before);
    });

    it("lets the story release what it rendered while its container is still attached", async () => {
      let container: HTMLElement | undefined;
      let attachedOnLoad: boolean | undefined;
      const story: GateStory = {
        id: "fixtures--root",
        run: ({ canvasElement } = {}) => {
          container = canvasElement;
          return Promise.resolve();
        },
        load: () => {
          attachedOnLoad = container?.isConnected;
          return Promise.resolve();
        },
      };

      await checkStoryAccessibility(story, "light");

      expect(attachedOnLoad).toBe(true);
    });
  });

  it("blocks a story with an image that has no alternative text, in every theme", async () => {
    const results = await checkStoryInAllThemes(storyRendering("fixtures--no-alt", '<img src="x.gif">'));

    expect(results.map((entry) => entry.theme)).toEqual([...THEMES]);
    expect(results.flatMap((entry) => entry.blocking.map((item) => item.id))).toEqual(["image-alt", "image-alt"]);
  });

  it("finds nothing to block in a clean story", async () => {
    const results = await checkStoryInAllThemes(storyRendering("fixtures--ok", '<div role="status" aria-label="Cargando"></div>'));

    expect(describeBlocking(results)).toBe("");
  });

  it("blocks text whose contrast jsdom cannot measure, instead of passing it (why the gate runs in Chromium)", async () => {
    const results = await checkStoryInAllThemes(storyRendering("fixtures--text", "<p>Texto</p>"));

    expect(results.flatMap((entry) => entry.blocking.map((item) => item.id))).toEqual(["color-contrast", "color-contrast"]);
  });
});

describe("describeBlocking", () => {
  it("names story, theme, rule and impact, one line per violation", () => {
    expect(describeBlocking([result([violation], []), result([violation], [], "dark")])).toBe(
      "fixtures--demo [light]: image-alt (critical)\nfixtures--demo [dark]: image-alt (critical)",
    );
  });

  it("names an impact axe did not assign as unknown", () => {
    expect(describeBlocking([result([{ ...violation, impact: null }], [])])).toContain("(unknown impact)");
  });

  it("is empty when only reported findings exist", () => {
    expect(describeBlocking([result([], [finding])])).toBe("");
  });
});

describe("summarize", () => {
  it("keeps blocking and reported findings apart, per story and theme", () => {
    expect(summarize([result([violation], [finding])])).toEqual([
      {
        story: "fixtures--demo",
        theme: "light",
        blocking: [{ rule: "image-alt", impact: "critical", help: "Images need alt text", targets: ["img"] }],
        reported: [
          {
            rule: "form-field-multiple-labels",
            impact: "moderate",
            help: "Images need alt text",
            targets: ["img"],
          },
        ],
      },
    ]);
  });

  it("writes a null impact when axe assigned none", () => {
    expect(summarize([result([], [{ ...finding, impact: undefined }])])[0]?.reported[0]?.impact).toBeNull();
  });
});

import { composeStory, setProjectAnnotations } from "@storybook/react-vite";
import { useEffect } from "react";
import { describe, expect, it } from "vitest";
import preview from "../../.storybook/preview";
import { checkStoryAccessibility } from "./run-gate";

// The gate renders a real React root per check. It must not leave that root mounted, nor the theme
// it forced, behind: the next story, and everything after the gate, starts from the page it found.
setProjectAnnotations([preview]);

describe("the gate after a check", () => {
  it("unmounts the React root the story rendered and restores the theme of the page", async () => {
    const events: string[] = [];
    function Probe() {
      useEffect(() => {
        events.push("mounted");
        return () => {
          events.push("unmounted");
        };
      }, []);
      return <p>Ready</p>;
    }
    const story = composeStory({ render: () => <Probe /> }, { title: "Fixtures/Probe" });
    document.documentElement.dataset["theme"] = "light";

    try {
      await checkStoryAccessibility(story, "dark");

      expect(events).toEqual(["mounted", "unmounted"]);
      expect(document.documentElement.dataset["theme"]).toBe("light");
    } finally {
      delete document.documentElement.dataset["theme"];
    }
  });
});

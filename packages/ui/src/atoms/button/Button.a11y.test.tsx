import { afterEach, describe, expect, it } from "vitest";
import { createRoot, type Root } from "react-dom/client";
import { userEvent } from "vitest/browser";
import "../../tokens/index.css";
import { Button } from "./Button";

// W2 (DS90): a class string cannot prove that the focus ring is drawn in the same frame in which the
// button takes the focus. The computed style of the real stylesheet can.
let root: Root | undefined;
let host: HTMLElement | undefined;

async function mount(): Promise<HTMLButtonElement> {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  root.render(<Button>Guardar</Button>);
  await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  return host.querySelector("button") as HTMLButtonElement;
}

afterEach(() => {
  root?.unmount();
  host?.remove();
});

const OUTLINE_PROPERTIES = ["outline-color", "outline-width", "outline-style", "outline-offset"];

describe("Button in Chromium", () => {
  it("DS90: no outline property, and not `all`, takes part in a transition", async () => {
    const button = await mount();
    const style = getComputedStyle(button);
    const properties = style.transitionProperty.split(",").map((property) => property.trim());

    expect(properties).not.toContain("all");
    for (const property of OUTLINE_PROPERTIES) expect(properties).not.toContain(property);
    expect(properties).toEqual(expect.arrayContaining(["color", "background-color", "border-color"]));
    expect(Number.parseFloat(style.transitionDuration)).toBeGreaterThan(0);
  });

  it("DS90: the ring has its final color and width in the first frame after the keyboard focus", async () => {
    const button = await mount();
    const reference = document.createElement("span");
    reference.style.color = "var(--color-border-focus)";
    host?.append(reference);
    const final = getComputedStyle(reference).color;

    await userEvent.tab();
    expect(document.activeElement).toBe(button);
    const style = getComputedStyle(button);

    expect(style.outlineWidth).toBe("2px");
    expect(style.outlineStyle).not.toBe("none");
    expect(style.outlineColor).toBe(final);
  });
});

import { afterEach, describe, expect, it } from "vitest";
import { userEvent } from "vitest/browser";
import { createRoot, type Root } from "react-dom/client";
import type { ReactElement } from "react";
import "../tokens/index.css";
import { Checkbox } from "./checkbox";
import { Label } from "./label";
import { RadioGroup, RadioGroupItem } from "./radio-group";

// The selector-driven states of the choice controls of 5.1 (disabled, read-only, invalid, checked, focus
// visible) need the compiled stylesheet: jsdom does not compile Tailwind, so a class string proves
// nothing about what the browser draws. Each state is compared with a reference element that wears
// the token directly, so the test fails if the control stops reading the token.
let root: Root | undefined;
let host: HTMLElement | undefined;

async function mount(ui: ReactElement): Promise<HTMLElement> {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  root.render(ui);
  await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  return host;
}

afterEach(() => {
  root?.unmount();
  host?.remove();
});

const style = (element: Element) => getComputedStyle(element);

function byName(dom: HTMLElement, name: string): HTMLElement {
  const element = dom.querySelector<HTMLElement>(`[aria-label="${name}"]`);
  if (element === null) throw new Error(`no element named ${name}`);
  return element;
}

function reference(dom: HTMLElement, className: string): CSSStyleDeclaration {
  const element = document.createElement("div");
  element.className = className;
  dom.append(element);
  return style(element);
}

describe("the invalid border, with the real stylesheet", () => {
  it("is the error token on every control, and only when aria-invalid is set", async () => {
    const dom = await mount(
      <>
        <Checkbox aria-label="checkbox" />
        <Checkbox aria-label="checkbox-invalid" aria-invalid="true" />
        <RadioGroup aria-label="group">
          <RadioGroupItem value="a" aria-label="radio" />
          <RadioGroupItem value="b" aria-label="radio-invalid" aria-invalid="true" />
        </RadioGroup>
      </>,
    );
    const error = reference(dom, "border border-(--input-invalid-border)").borderTopColor;

    for (const name of ["checkbox", "radio"]) {
      expect(style(byName(dom, name)).borderTopColor, name).not.toBe(error);
      expect(style(byName(dom, `${name}-invalid`)).borderTopColor, `${name}-invalid`).toBe(error);
    }
  });
});

describe("the focus ring, with the real stylesheet", () => {
  it("is a two pixel outline in the focus token on every control, drawn only while focused", async () => {
    const dom = await mount(
      <>
        <Checkbox aria-label="checkbox" />
        <RadioGroup aria-label="group">
          <RadioGroupItem value="a" aria-label="radio" />
        </RadioGroup>
      </>,
    );
    const focus = reference(dom, "outline-2 outline-border-focus").outlineColor;

    for (const name of ["checkbox", "radio"]) {
      const control = byName(dom, name);
      expect(style(control).outlineStyle, `${name} unfocused`).toBe("none");

      control.focus();

      expect(document.activeElement, name).toBe(control);
      expect(style(control).outlineStyle, `${name} focused`).toBe("solid");
      expect(style(control).outlineWidth, name).toBe("2px");
      expect(style(control).outlineColor, name).toBe(focus);
    }
  });
});

describe("the choice controls, with the real stylesheet", () => {
  it("are at least 24 by 24 pixels, the minimum target of WCAG 2.5.8", async () => {
    const dom = await mount(
      <>
        <Checkbox aria-label="checkbox" />
        <RadioGroup aria-label="group">
          <RadioGroupItem value="a" aria-label="radio" />
        </RadioGroup>
      </>,
    );

    for (const name of ["checkbox", "radio"]) {
      const { width, height } = byName(dom, name).getBoundingClientRect();
      expect(width, name).toBeGreaterThanOrEqual(24);
      expect(height, name).toBeGreaterThanOrEqual(24);
    }
  });

  it("fill with the primary color when checked or indeterminate, and not when unchecked", async () => {
    const dom = await mount(
      <>
        <Checkbox aria-label="off" />
        <Checkbox aria-label="on" defaultChecked />
        <Checkbox aria-label="mixed" checked="indeterminate" />
      </>,
    );
    const primary = reference(dom, "bg-primary").backgroundColor;

    expect(style(byName(dom, "off")).backgroundColor).not.toBe(primary);
    expect(style(byName(dom, "on")).backgroundColor).toBe(primary);
    expect(style(byName(dom, "mixed")).backgroundColor).toBe(primary);
  });

  it("draws the dot of the selected radio in the primary color, and no dot on the others", async () => {
    const dom = await mount(
      <RadioGroup aria-label="group" defaultValue="a">
        <RadioGroupItem value="a" aria-label="selected" />
        <RadioGroupItem value="b" aria-label="other" />
      </RadioGroup>,
    );
    const primary = reference(dom, "bg-primary").backgroundColor;

    const dot = byName(dom, "selected").querySelector("span");
    if (dot === null) throw new Error("the selected radio drew no dot");
    expect(style(dot).backgroundColor).toBe(primary);
    expect(byName(dom, "other").querySelector("span")).toBeNull();
  });

  it("dim and show the blocked cursor when disabled", async () => {
    const dom = await mount(
      <>
        <Checkbox aria-label="checkbox" disabled />
        <RadioGroup aria-label="group">
          <RadioGroupItem value="a" aria-label="radio" disabled />
        </RadioGroup>
      </>,
    );

    for (const name of ["checkbox", "radio"]) {
      expect(style(byName(dom, name)).opacity, name).toBe("0.5");
      expect(style(byName(dom, name)).cursor, name).toBe("not-allowed");
    }
  });
});

describe("the drawing and the label of a choice control, with the real stylesheet", () => {
  const visibleDrawings = (control: HTMLElement) =>
    [...control.querySelectorAll("svg")].filter((svg) => style(svg).display !== "none");

  it("shows the check when checked and the dash when indeterminate, never both", async () => {
    const dom = await mount(
      <>
        <Checkbox aria-label="on" defaultChecked />
        <Checkbox aria-label="mixed" checked="indeterminate" />
        <Checkbox aria-label="off" />
      </>,
    );

    expect(visibleDrawings(byName(dom, "on")).map((svg) => svg.classList.contains("lucide-check"))).toEqual([true]);
    expect(visibleDrawings(byName(dom, "mixed")).map((svg) => svg.classList.contains("lucide-minus"))).toEqual([true]);
    expect(visibleDrawings(byName(dom, "off"))).toEqual([]);
  });

  it("keeps the error border on a checked control that is also invalid", async () => {
    const dom = await mount(
      <>
        <Checkbox aria-label="checkbox" defaultChecked aria-invalid="true" />
        <RadioGroup aria-label="group" defaultValue="a">
          <RadioGroupItem value="a" aria-label="radio" aria-invalid="true" />
        </RadioGroup>
      </>,
    );
    const error = reference(dom, "border border-(--input-invalid-border)").borderTopColor;

    expect(style(byName(dom, "checkbox")).borderTopColor).toBe(error);
    expect(style(byName(dom, "radio")).borderTopColor).toBe(error);
  });

  it("dims the label that follows a disabled control, the usual order for a checkbox", async () => {
    const dom = await mount(
      <div className="flex items-center gap-2">
        <Checkbox id="a" disabled />
        <Label htmlFor="a">Enviar recibo</Label>
      </div>,
    );

    const label = dom.querySelector("label");
    if (label === null) throw new Error("no label was rendered");
    expect(style(label).opacity).toBe("0.7");
  });
});

describe("the keyboard in a real browser", () => {
  it("moves and selects with the arrow keys and toggles a checkbox with the space bar", async () => {
    const dom = await mount(
      <>
        <Checkbox aria-label="checkbox" />
        <RadioGroup aria-label="group" defaultValue="a">
          <RadioGroupItem value="a" aria-label="first" />
          <RadioGroupItem value="b" aria-label="second" />
        </RadioGroup>
      </>,
    );

    byName(dom, "checkbox").focus();
    await userEvent.keyboard(" ");
    expect(byName(dom, "checkbox").getAttribute("aria-checked")).toBe("true");

    byName(dom, "first").focus();
    // Radix selects the option it moves to only if the arrow is still down when the focus arrives.
    await userEvent.keyboard("{ArrowDown>}");
    await new Promise((resolve) => setTimeout(resolve, 50));
    await userEvent.keyboard("{/ArrowDown}");
    expect(byName(dom, "second").getAttribute("aria-checked")).toBe("true");
    expect(document.activeElement).toBe(byName(dom, "second"));
  });
});

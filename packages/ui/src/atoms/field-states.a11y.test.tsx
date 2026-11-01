import { afterEach, describe, expect, it } from "vitest";
import { createRoot, type Root } from "react-dom/client";
import type { ReactElement } from "react";
import "../tokens/index.css";
import { Input } from "./input";
import { Label } from "./label";
import { Textarea } from "./textarea";

// These need the real stylesheet: jsdom does not compile Tailwind, so a class string proves nothing
// about the variants that depend on selector order (peer) or on the :read-only pseudo-class, which
// also matches a disabled input. They run in the browser project with the compiled CSS.
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

function labelOf(dom: HTMLElement): HTMLLabelElement {
  const label = dom.querySelector("label");
  if (label === null) throw new Error("no label was rendered");
  return label;
}

function byName(dom: HTMLElement, name: string): Element {
  const element = dom.querySelector(`[aria-label="${name}"]`);
  if (element === null) throw new Error(`no element named ${name}`);
  return element;
}

describe("the Label of a disabled field, with the real stylesheet", () => {
  it("dims when it is told its field is disabled, in the usual label-then-input order", async () => {
    const dom = await mount(
      <>
        <Label htmlFor="a" disabled>
          Monto
        </Label>
        <Input id="a" disabled />
      </>,
    );

    expect(style(labelOf(dom)).opacity).toBe("0.7");
    expect(style(labelOf(dom)).cursor).toBe("not-allowed");
  });

  it("does not dim when its field is enabled", async () => {
    const dom = await mount(
      <>
        <Label htmlFor="a">Monto</Label>
        <Input id="a" />
      </>,
    );

    expect(style(labelOf(dom)).opacity).toBe("1");
  });

  it("still dims through the peer when the control comes first", async () => {
    const dom = await mount(
      <div className="flex flex-col">
        <Input id="a" disabled className="peer order-2" />
        <Label htmlFor="a" className="order-1">
          Monto
        </Label>
      </div>,
    );

    expect(style(labelOf(dom)).opacity).toBe("0.7");
  });
});

describe("the field states, with the real stylesheet", () => {
  it("gives the read-only surface to a read-only input and not to a disabled one", async () => {
    const dom = await mount(
      <>
        <Input aria-label="normal" />
        <Input aria-label="readonly" readOnly />
        <Input aria-label="disabled" disabled />
        <Input aria-label="both" readOnly disabled />
      </>,
    );
    const background = (name: string) => style(byName(dom, name)).backgroundColor;

    expect(background("readonly")).not.toBe(background("normal"));
    expect(background("disabled")).toBe(background("normal"));
    expect(background("both")).toBe(background("normal"));
  });
});

function reference(dom: HTMLElement, className: string): CSSStyleDeclaration {
  const element = document.createElement("div");
  element.className = className;
  dom.append(element);
  return style(element);
}

describe("the Textarea states, with the real stylesheet", () => {
  it("gives the read-only surface to a read-only textarea and not to a disabled one", async () => {
    const dom = await mount(
      <>
        <Textarea aria-label="normal" />
        <Textarea aria-label="readonly" readOnly />
        <Textarea aria-label="disabled" disabled />
        <Textarea aria-label="both" readOnly disabled />
      </>,
    );
    const background = (name: string) => style(byName(dom, name)).backgroundColor;

    expect(background("readonly")).not.toBe(background("normal"));
    expect(background("disabled")).toBe(background("normal"));
    expect(background("both")).toBe(background("normal"));
  });

  it("dims and shows the blocked cursor when it is disabled", async () => {
    const dom = await mount(<Textarea aria-label="disabled" disabled />);

    expect(style(byName(dom, "disabled")).opacity).toBe("0.5");
    expect(style(byName(dom, "disabled")).cursor).toBe("not-allowed");
  });

  it("draws the error token on the border only when aria-invalid is set", async () => {
    const dom = await mount(
      <>
        <Textarea aria-label="valid" />
        <Textarea aria-label="invalid" aria-invalid="true" />
      </>,
    );
    const error = reference(dom, "border border-(--input-invalid-border)").borderTopColor;

    expect(style(byName(dom, "valid")).borderTopColor).not.toBe(error);
    expect(style(byName(dom, "invalid")).borderTopColor).toBe(error);
  });
});

describe("the focus ring of a text field, with the real stylesheet", () => {
  // Read the instant after focus(), without waiting for any animation: the ring must already be in
  // the focus token. transition-colors animated outline-color too, so the ring first showed in the
  // text color and faded to the token.
  it("is a two pixel outline already in the focus token on the first frame, on Input and Textarea", async () => {
    const dom = await mount(
      <>
        <Input aria-label="input" />
        <Textarea aria-label="textarea" />
      </>,
    );
    const focus = reference(dom, "outline-2 outline-border-focus").outlineColor;

    for (const name of ["input", "textarea"]) {
      const control = byName(dom, name);
      if (!(control instanceof HTMLElement)) throw new Error(`${name} is not an HTML element`);
      expect(style(control).outlineStyle, `${name} unfocused`).toBe("none");

      control.focus();

      expect(document.activeElement, name).toBe(control);
      expect(style(control).outlineStyle, name).toBe("solid");
      expect(style(control).outlineWidth, name).toBe("2px");
      expect(style(control).outlineColor, name).toBe(focus);
    }
  });
});

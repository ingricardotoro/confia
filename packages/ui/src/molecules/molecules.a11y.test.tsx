import { afterEach, describe, expect, it } from "vitest";
import { createRoot, type Root } from "react-dom/client";
import type { ReactElement } from "react";
import "../tokens/index.css";
import { Input } from "../atoms/input";
import { FormField } from "./form-field";

// What a class string cannot prove in jsdom: the label of a disabled field is dimmed although it
// comes BEFORE the control (the order in which `peer-disabled` cannot reach it), and the error
// message takes the error color from the compiled stylesheet.
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

function reference(dom: HTMLElement, className: string): CSSStyleDeclaration {
  const element = document.createElement("div");
  element.className = className;
  dom.append(element);
  return getComputedStyle(element);
}

describe("FormField, with the real stylesheet", () => {
  it("dims the label of a disabled field that comes before its control, and only then", async () => {
    const dom = await mount(
      <>
        <FormField label="Activo" disabled>
          {(control) => <Input {...control} />}
        </FormField>
        <FormField label="Libre">{(control) => <Input {...control} />}</FormField>
      </>,
    );

    const [disabled, free] = [...dom.querySelectorAll("label")];
    expect(getComputedStyle(disabled as HTMLElement).opacity).toBe("0.7");
    expect(getComputedStyle(free as HTMLElement).opacity).toBe("1");
  });

  it("draws the error message in the error color and the invalid border on its control", async () => {
    const dom = await mount(
      <FormField label="Monto" error="Ingrese un monto">
        {(control) => <Input {...control} />}
      </FormField>,
    );
    const message = [...dom.querySelectorAll("p")].find((p) => p.textContent === "Ingrese un monto");
    const input = dom.querySelector("input");
    if (message === undefined || input === null) throw new Error("the field rendered no message or control");

    expect(getComputedStyle(message).color).toBe(reference(dom, "text-status-overdue-fg").color);
    expect(getComputedStyle(input).borderTopColor).toBe(reference(dom, "border border-(--input-invalid-border)").borderTopColor);
  });
});

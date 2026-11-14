import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { page } from "vitest/browser";
import { createRoot, type Root } from "react-dom/client";
import { useState } from "react";
import "../../tokens/index.css";
import { createElement } from "react";
import { I18nextProvider } from "react-i18next";
import { Button } from "../../atoms/button";
import { Input } from "../../atoms/input";
import { createUiI18n } from "../../i18n/create-ui-i18n";
import { FormErrorSummary } from "../form-error-summary";
import { FormField } from "../form-field";
import { Dialog } from "./Dialog";

// What jsdom cannot prove: with the real stylesheet and a real viewport, the title and the footer of a
// tall form stay in view while the body is the only thing that scrolls.
const FIELDS = Array.from({ length: 10 }, (_, index) => `campo-${String(index + 1)}`);
const ERRORS = FIELDS.slice(0, 3).map((controlId, index) => ({ controlId, message: `Corrija el campo ${String(index + 1)}.` }));

function Harness() {
  const [open, setOpen] = useState(false);
  const [container, setContainer] = useState<HTMLElement | null>(null);
  return (
    <div ref={setContainer}>
      <Button
        onClick={() => {
          setOpen(true);
        }}
      >
        Abrir
      </Button>
      {container === null ? null : (
        <Dialog
          open={open}
          onOpenChange={setOpen}
          container={container}
          title="Nuevo estudiante"
          description="Registre los datos."
          footer={<Button>Guardar</Button>}
        >
          <form noValidate className="flex flex-col gap-4">
            <FormErrorSummary errors={ERRORS} id="summary" />
            {FIELDS.map((controlId, index) => (
              <FormField key={controlId} label={`Campo ${String(index + 1)}`} controlId={controlId}>
                {(control) => <Input {...control} />}
              </FormField>
            ))}
          </form>
        </Dialog>
      )}
    </div>
  );
}

let root: Root | undefined;
let host: HTMLElement | undefined;

beforeEach(async () => {
  await page.viewport(1280, 720);
});

afterEach(() => {
  root?.unmount();
  host?.remove();
});

const frame = () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));

describe("Dialog layout, with the real stylesheet at 1280 x 720", () => {
  it("keeps the title inside the viewport and the dialog while the body scrolls to the focused error summary", async () => {
    host = document.createElement("div");
    document.body.append(host);
    root = createRoot(host);
    root.render(createElement(I18nextProvider, { i18n: createUiI18n() }, createElement(Harness)));
    await frame();
    const opener = host.querySelector("button");
    if (opener === null) throw new Error("no opener");
    opener.focus();
    opener.click();
    await frame();

    const dialog = document.querySelector<HTMLElement>('[role="dialog"]');
    const summary = document.getElementById("summary");
    const title = [...document.querySelectorAll("h2")].find((heading) => heading.textContent === "Nuevo estudiante");
    const body = summary?.closest("form")?.parentElement;
    if (dialog === null || summary === null || title === undefined || body === null || body === undefined) throw new Error("dialog not rendered");

    // Scroll the body down, then bring the summary back with the focus, as a failed submit does.
    body.scrollTop = body.scrollHeight;
    summary.focus();
    await frame();

    const box = title.getBoundingClientRect();
    const panel = dialog.getBoundingClientRect();
    expect(box.top).toBeGreaterThanOrEqual(0);
    expect(box.bottom).toBeLessThanOrEqual(window.innerHeight);
    expect(box.top).toBeGreaterThanOrEqual(panel.top);
    expect(box.bottom).toBeLessThanOrEqual(panel.bottom);
    expect(panel.height).toBeLessThanOrEqual(window.innerHeight * 0.9 + 1);
    expect(document.activeElement).toBe(summary);

    // The summary scrolled into place without moving the panel: scrolling an inner field proves the body scrolls.
    document.getElementById("campo-10")?.focus();
    await frame();
    expect(body.scrollTop).toBeGreaterThan(0);
    expect(dialog.scrollTop).toBe(0);

    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    dialog.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    await frame();
    expect(document.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(opener);
  });
});

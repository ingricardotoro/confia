import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { FormErrorSummary } from "./FormErrorSummary";
import { FormErrorSummary as FromIndex } from "./index";

const MESSAGE_A = "Ingrese el identificador.";
const MESSAGE_B = "Ingrese el código.";
const MESSAGE_C = "Seleccione un método.";

// The controls come AFTER the summary in the document, as in a form: the summary renders before they
// exist, so the test also proves it learns about them once they are committed.
function Controls({ ids }: { ids: readonly string[] }) {
  return (
    <>
      {ids.map((id) => (
        <div key={id}>
          <label htmlFor={id}>{`Campo ${id}`}</label>
          <input id={id} />
        </div>
      ))}
    </>
  );
}

describe("FormErrorSummary", () => {
  it("DS75: the heading counts the fields and each error is a link named by its message", () => {
    renderWithI18n(
      <>
        <FormErrorSummary
          errors={[
            { controlId: "a", message: MESSAGE_A },
            { controlId: "b", message: MESSAGE_B },
          ]}
        />
        <Controls ids={["a", "b"]} />
      </>,
    );

    expect(screen.getByRole("group", { name: "Hay 2 campos con errores." })).toBeInTheDocument();
    expect(screen.getByText("Hay 2 campos con errores.")).toBeInTheDocument();
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(within(items[0] as HTMLElement).getByRole("link", { name: MESSAGE_A })).toHaveAttribute("href", "#a");
    expect(within(items[1] as HTMLElement).getByRole("link", { name: MESSAGE_B })).toHaveAttribute("href", "#b");
  });

  it("DS76: a single error uses the singular form", () => {
    renderWithI18n(
      <>
        <FormErrorSummary errors={[{ controlId: "a", message: MESSAGE_A }]} />
        <Controls ids={["a"]} />
      </>,
    );

    expect(screen.getByText("Hay 1 campo con error.")).toBeInTheDocument();
  });

  it("DS77: the container can take focus from code and is not a stop of the tab order", async () => {
    const user = userEvent.setup();
    renderWithI18n(
      <>
        <button>Antes</button>
        <FormErrorSummary errors={[{ controlId: "a", message: MESSAGE_A }]} />
        <Controls ids={["a"]} />
      </>,
    );
    const container = screen.getByRole("group", { name: "Hay 1 campo con error." });

    container.focus();
    expect(container).toHaveFocus();
    expect(container).toHaveAttribute("tabindex", "-1");

    container.blur();
    const stops: Element[] = [];
    for (let step = 0; step < 4; step += 1) {
      await user.tab();
      if (document.activeElement !== null) stops.push(document.activeElement);
    }
    expect(stops).not.toContain(container);
    expect(stops[0]).toBe(screen.getByRole("button", { name: "Antes" }));
    expect(stops[1]).toBe(screen.getByRole("link", { name: MESSAGE_A }));
  });

  it("DS77: hands the container to the caller through ref", () => {
    const ref = createRef<HTMLDivElement>();
    renderWithI18n(
      <>
        <FormErrorSummary ref={ref} errors={[{ controlId: "a", message: MESSAGE_A }]} />
        <Controls ids={["a"]} />
      </>,
    );

    ref.current?.focus();
    expect(screen.getByRole("group")).toHaveFocus();
  });

  it("DS78: activating a link with the keyboard focuses the control and leaves the URL and history alone", async () => {
    const user = userEvent.setup();
    renderWithI18n(
      <>
        <FormErrorSummary errors={[{ controlId: "b", message: MESSAGE_B }]} />
        <Controls ids={["b"]} />
      </>,
    );
    const href = window.location.href;
    const length = window.history.length;

    screen.getByRole("link", { name: MESSAGE_B }).focus();
    await user.keyboard("{Enter}");

    expect(screen.getByLabelText("Campo b")).toHaveFocus();
    expect(window.location.href).toBe(href);
    expect(window.history.length).toBe(length);
  });

  it("DS79: with no errors it renders nothing at all", () => {
    const { container } = renderWithI18n(<FormErrorSummary errors={[]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("DS80: an error whose control is not in the document is text, and the others keep their link", () => {
    renderWithI18n(
      <>
        <FormErrorSummary
          errors={[
            { controlId: "a", message: MESSAGE_A },
            { controlId: "missing", message: MESSAGE_B },
          ]}
        />
        <Controls ids={["a"]} />
      </>,
    );

    expect(screen.getByRole("link", { name: MESSAGE_A })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: MESSAGE_B })).not.toBeInTheDocument();
    expect(screen.getByText(MESSAGE_B)).toBeInTheDocument();
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
  });

  it("DS80: a control that appears after the summary turns its text into a link", () => {
    const errors = [{ controlId: "late", message: MESSAGE_A }];
    const { rerender } = renderWithI18n(<FormErrorSummary errors={errors} />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();

    rerender(
      <>
        <FormErrorSummary errors={errors} />
        <Controls ids={["late"]} />
      </>,
    );

    expect(screen.getByRole("link", { name: MESSAGE_A })).toBeInTheDocument();
  });

  it("DS81: keeps the order of the input", () => {
    renderWithI18n(
      <>
        <FormErrorSummary
          errors={[
            { controlId: "b", message: MESSAGE_B },
            { controlId: "a", message: MESSAGE_A },
            { controlId: "c", message: MESSAGE_C },
          ]}
        />
        <Controls ids={["a", "b", "c"]} />
      </>,
    );

    expect(screen.getAllByRole("listitem").map((item) => item.textContent)).toEqual([MESSAGE_B, MESSAGE_A, MESSAGE_C]);
  });

  it("DS88: is exported from its own index", () => {
    expect(FromIndex).toBe(FormErrorSummary);
  });
});

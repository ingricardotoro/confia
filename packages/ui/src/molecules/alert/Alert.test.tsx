import { createRef } from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Alert } from "./Alert";

const VARIANTS = [
  ["info", "Información"],
  ["warning", "Advertencia"],
  ["destructive", "Error"],
] as const;

describe("Alert", () => {
  it.each(VARIANTS)("DS82: %s carries a decorative icon, a hidden severity text and its own tokens", (variant, severity) => {
    const { container } = renderWithI18n(<Alert variant={variant}>Contenido</Alert>);

    const hidden = screen.getByText(severity);
    expect(hidden).toHaveClass("sr-only");
    expect(hidden.compareDocumentPosition(screen.getByText("Contenido")) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    const classes = (container.firstElementChild as HTMLElement).className;
    expect(classes).toContain(`bg-(--alert-${variant}-bg)`);
    expect(classes).toContain(`text-(--alert-${variant}-fg)`);
    expect(classes).toContain(`border-(--alert-${variant}-border)`);
  });

  it("DS83: the destructive variant is an alert and the others are a status", () => {
    renderWithI18n(
      <>
        <Alert variant="destructive">Falló</Alert>
        <Alert variant="info">Aviso</Alert>
        <Alert variant="warning">Atención</Alert>
      </>,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Falló");
    expect(screen.getAllByRole("status").map((node) => node.textContent)).toEqual(["InformaciónAviso", "AdvertenciaAtención"]);
  });

  it("is informational by default", () => {
    renderWithI18n(<Alert>Contenido</Alert>);

    expect(screen.getByRole("status")).toHaveTextContent("Información");
  });

  it("styles an explicit null variant as the informational one it announces", () => {
    const { container } = renderWithI18n(<Alert variant={null}>Contenido</Alert>);

    expect(screen.getByRole("status")).toHaveTextContent("Información");
    const classes = (container.firstElementChild as HTMLElement).className;
    expect(classes).toContain("bg-(--alert-info-bg)");
    expect(classes).toContain("text-(--alert-info-fg)");
    expect(classes).toContain("border-(--alert-info-border)");
  });

  it("DS84: shows the title and the content it receives, and only adds the severity", () => {
    renderWithI18n(
      <Alert variant="warning" title="La sesión vence pronto">
        Guarde el trabajo en curso.
      </Alert>,
    );

    const region = screen.getByRole("status");
    expect(region).toHaveTextContent("La sesión vence pronto");
    expect(region.textContent).toBe("AdvertenciaLa sesión vence prontoGuarde el trabajo en curso.");
  });

  it("renders no title element when the title is missing or blank", () => {
    renderWithI18n(
      <>
        <Alert>Uno</Alert>
        <Alert title="   ">Dos</Alert>
      </>,
    );

    expect(screen.queryAllByRole("paragraph")).toEqual([]);
  });

  it("DS86: takes the programmatic focus through its ref and is not a tab stop", async () => {
    const user = userEvent.setup();
    const ref = createRef<HTMLDivElement>();
    renderWithI18n(
      <>
        <Alert ref={ref} variant="destructive">
          Falló
        </Alert>
        <button type="button">Después</button>
      </>,
    );

    ref.current?.focus();
    expect(screen.getByRole("alert")).toHaveFocus();
    expect(screen.getByRole("alert")).toHaveAttribute("tabindex", "-1");

    ref.current?.blur();
    await user.tab();
    expect(screen.getByRole("button", { name: "Después" })).toHaveFocus();
  });

  it("merges the class of the caller over its own", () => {
    const { container } = renderWithI18n(<Alert className="mt-4">Contenido</Alert>);

    expect(container.firstElementChild).toHaveClass("mt-4");
  });
});

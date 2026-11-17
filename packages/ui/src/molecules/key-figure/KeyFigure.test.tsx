import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { KeyFigure } from "./KeyFigure";

describe("KeyFigure", () => {
  it("shows the label, the value as given and the description", () => {
    renderWithI18n(<KeyFigure label="Listas en total" value="24" description="En esta consulta" />);

    expect(screen.getByText("Listas en total")).toBeVisible();
    expect(screen.getByText("24")).toBeVisible();
    expect(screen.getByText("En esta consulta")).toBeVisible();
  });

  it("shows a skeleton instead of the value while loading", () => {
    const { container } = renderWithI18n(<KeyFigure label="Listas en total" value="24" loading />);

    expect(screen.queryByText("24")).not.toBeInTheDocument();
    expect(container.firstElementChild).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Listas en total")).toBeVisible();
  });
});

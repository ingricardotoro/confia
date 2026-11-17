import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Card } from "./Card";

describe("Card", () => {
  it("renders its content", () => {
    renderWithI18n(<Card>Contenido</Card>);

    expect(screen.getByText("Contenido")).toBeVisible();
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });

  it("is a region named by its title, with description and actions", () => {
    renderWithI18n(
      <Card title="Resumen" description="Datos de la lista" actions={<button type="button">Editar</button>}>
        Contenido
      </Card>,
    );

    expect(screen.getByRole("region", { name: "Resumen" })).toBeVisible();
    expect(screen.getByText("Datos de la lista")).toBeVisible();
    expect(screen.getByRole("button", { name: "Editar" })).toBeVisible();
  });

  it("reads the card tokens and takes the large padding on request", () => {
    renderWithI18n(<Card padding="lg">Contenido</Card>);

    const body = screen.getByText("Contenido");
    expect(body).toHaveClass("p-6");
    expect(body.parentElement).toHaveClass("bg-(--card-bg)", "border-(--card-border)");
  });
});

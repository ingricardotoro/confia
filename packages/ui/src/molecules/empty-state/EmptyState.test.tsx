import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithI18n } from "../../test/render";
import { EmptyState } from "./EmptyState";

describe("EmptyState", () => {
  it("says what is missing and describes it", () => {
    renderWithI18n(
      <EmptyState
        title="Sin cargos en el período"
        description="Cuando se genere un cargo aparecerá aquí."
        action={{ label: "Agregar cargo", onAction: vi.fn() }}
      />,
    );

    expect(screen.getByText("Sin cargos en el período")).toBeVisible();
    expect(screen.getByText("Cuando se genere un cargo aparecerá aquí.")).toBeVisible();
  });

  it("renders without a description", () => {
    const { container } = renderWithI18n(
      <EmptyState title="Sin cargos" action={{ label: "Agregar cargo", onAction: vi.fn() }} />,
    );

    expect(container).toHaveTextContent("Sin cargos");
    expect(container.querySelectorAll("p")).toHaveLength(1);
  });

  it("DS26: offers the action as a control named by its label, called once per activation", async () => {
    const user = userEvent.setup();
    const onAction = vi.fn();
    renderWithI18n(<EmptyState title="Sin cargos" action={{ label: "Agregar cargo", onAction }} />);

    await user.click(screen.getByRole("button", { name: "Agregar cargo" }));
    expect(onAction).toHaveBeenCalledTimes(1);

    await user.keyboard("{Enter}");
    expect(onAction).toHaveBeenCalledTimes(2);
  });

  it("is not an alert: an empty list is not a failure", () => {
    renderWithI18n(<EmptyState title="Sin cargos" action={{ label: "Agregar cargo", onAction: vi.fn() }} />);

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("merges the class of the caller over its own", () => {
    const { container } = renderWithI18n(
      <EmptyState title="Sin cargos" className="mt-4" action={{ label: "Agregar cargo", onAction: vi.fn() }} />,
    );

    expect(container.firstElementChild).toHaveClass("mt-4");
  });
});

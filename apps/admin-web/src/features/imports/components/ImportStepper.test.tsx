import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ImportStepper } from "./ImportStepper.tsx";

// States. AW57, AW58: the stepper with props alone. It shows no data, so it has no loading, empty, failed or success state of
// its own: it has the position, one of four steps, and each step says in text whether it is done, current or pending.
describe("ImportStepper", () => {
  it("lists the four steps in order and marks only the current one with aria-current", () => {
    renderWithAdminI18n(<ImportStepper current="simulate" />);

    const steps = within(screen.getByRole("navigation", { name: "Pasos de la importación" })).getAllByRole("listitem");
    expect(steps.map((step) => step.textContent)).toEqual([
      "Elegir archivocompletado",
      "2Simularpaso actual",
      "3Aplicarpendiente",
      "4Resultadopendiente",
    ]);
    expect(steps.map((step) => step.getAttribute("aria-current"))).toEqual([null, "step", null, null]);
  });

  it("marks the first step as the current one at the start and every step but the last as done at the end", () => {
    const { rerender } = renderWithAdminI18n(<ImportStepper current="choose" />);
    expect(screen.getByText("Elegir archivo").closest("li")).toHaveAttribute("aria-current", "step");

    rerender(<ImportStepper current="result" />);
    expect(screen.getAllByText("completado")).toHaveLength(3);
  });
});

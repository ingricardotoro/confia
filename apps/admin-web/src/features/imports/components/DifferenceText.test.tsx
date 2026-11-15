import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { DifferenceText } from "./DifferenceText.tsx";

// States. AW57, AW58: the difference in words, with props alone. It has no loading, empty or error of its own (the preview that holds it has
// them): its three states are the three ways the system can differ from the roster, and the meaning is in the words, not in a sign or a color.
describe("DifferenceText", () => {
  it.each([
    [2, "El sistema tiene 2 matrículas activas más que el listado oficial."],
    [1, "El sistema tiene 1 matrícula activa más que el listado oficial."],
    [-1, "El sistema tiene 1 matrícula activa menos que el listado oficial."],
    [0, "El sistema tiene las mismas matrículas activas que el listado oficial."],
  ])("says a difference of %i in words", (difference, sentence) => {
    renderWithAdminI18n(<DifferenceText difference={difference} />);

    expect(screen.getByText(sentence)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/[-+]\d/);
  });
});

import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { StatementDirectionBadge } from "./StatementDirectionBadge.tsx";

// States. AW57, AW58: the direction of one line rendered with props alone. Success is its only state (a line is either a
// charge or a credit and the badge always says which in text); loading, empty and error do not apply, because it reads nothing.
// The dot is a second carrier of the direction, never the only one.
describe("StatementDirectionBadge", () => {
  it.each([
    ["debit", "Cargo", "filled"],
    ["credit", "Abono", "hollow"],
  ] as const)("says %s in text with a dot", (direction, text, dot) => {
    renderWithAdminI18n(<StatementDirectionBadge direction={direction} />);

    expect(screen.getByText(text).firstElementChild).toHaveAttribute("data-dot", dot);
  });
});

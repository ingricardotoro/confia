import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ChargeStatusBadge } from "./ChargeStatusBadge.tsx";

// States. AW57, AW58: the status of a charge rendered with props alone. Success is the only state it has (a charge is in force or
// voided and the badge always says which in text); loading, empty and error do not apply, because it lists nothing and reads nothing.
// The dot is a second carrier of the status, never the only one.
describe("ChargeStatusBadge", () => {
  it.each([
    ["ACTIVE", "Vigente", "filled"],
    ["VOIDED", "Anulado", "hollow"],
  ] as const)("says %s in text and draws its dot (%s)", (status, text, dot) => {
    renderWithAdminI18n(<ChargeStatusBadge status={status} />);

    expect(screen.getByText(text).firstElementChild).toHaveAttribute("data-dot", dot);
  });
});

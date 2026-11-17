import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ChargePlanStatusBadge } from "./ChargePlanStatusBadge.tsx";

// States. AW57, AW58: the state of a plan rendered with props alone. Success is the only state it has (a plan has one of
// three states and the badge always says which in text); loading, empty and error do not apply, because it lists nothing and
// reads nothing. The dot is a second carrier of the state, never the only one.
describe("ChargePlanStatusBadge", () => {
  it.each([
    ["DRAFT", "Borrador", "hollow"],
    ["ACTIVE", "Activo", "filled"],
    ["ARCHIVED", "Archivado", "hollow"],
  ] as const)("says %s in text and draws its dot (%s)", (status, text, dot) => {
    renderWithAdminI18n(<ChargePlanStatusBadge status={status} />);

    expect(screen.getByText(text).firstElementChild).toHaveAttribute("data-dot", dot);
  });
});

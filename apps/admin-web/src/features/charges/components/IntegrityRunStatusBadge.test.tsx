import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { IntegrityRunStatusBadge } from "./IntegrityRunStatusBadge.tsx";

// States. AW57, AW58: the result of an integrity run rendered with props alone. Success is the only state it has (a run ends clean or
// with discrepancies and the badge always says which in text); loading, empty and error do not apply, because it lists nothing and
// reads nothing. The dot is a second carrier of the result, never the only one.
describe("IntegrityRunStatusBadge", () => {
  it.each([
    ["OK", "Sin discrepancias"],
    ["DISCREPANCIES", "Con discrepancias"],
  ] as const)("says %s in text and draws a filled dot", (status, text) => {
    renderWithAdminI18n(<IntegrityRunStatusBadge status={status} />);

    expect(screen.getByText(text).firstElementChild).toHaveAttribute("data-dot", "filled");
  });
});

import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { assignment } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AssignmentTermsRows } from "./AssignmentTermsRows.tsx";

// States. AW57, AW58: The terms an assignment was requested for, rendered with props alone. Loading and error do not apply: it draws what its parent holds. Empty: an
// assignment from before the terms were kept has none, and the rows say so with a dash. Success: the benefit and the version of the discount, as requested.
function renderRows(row: ReturnType<typeof assignment>) {
  return renderWithAdminI18n(
    <dl>
      <AssignmentTermsRows assignment={row} />
    </dl>,
  );
}

describe("AssignmentTermsRows", () => {
  it("shows a requested percentage and the version of the discount", () => {
    renderRows(assignment({ benefitType: "PERCENTAGE", percent: "20.00", discountVersion: 3 }));

    expect(screen.getByText("Beneficio solicitado")).toBeInTheDocument();
    expect(screen.getByText("20.00 %")).toBeInTheDocument();
    expect(screen.getByText("Versión del descuento")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("shows a requested fixed amount as money", () => {
    renderRows(assignment({ benefitType: "FIXED_AMOUNT", percent: null, fixedAmount: { amount: "250.00", currency: "HNL" }, discountVersion: 0 }));

    expect(screen.getByText("L 250.00")).toBeInTheDocument();
    expect(screen.getByText("0")).toBeInTheDocument();
  });

  it("says there are no terms, with dashes, for an assignment from before they were kept", () => {
    renderRows(assignment({ benefitType: null, percent: null, discountVersion: null }));

    expect(screen.getAllByText("—")).toHaveLength(2);
  });
});

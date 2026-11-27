import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { discount, discountProposal } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { BenefitValue, DiscountTerms } from "./DiscountTerms.tsx";

// States. AW57, AW58: The terms of a discount rendered with props alone. Loading, error and empty do not apply: it draws what its parent already holds. Success: a
// percentage shown as points, a fixed amount shown as money exactly as the server sent it, the validity, whether it stacks, the concepts, and (for a
// proposal) that approving it turns the discount on.
describe("DiscountTerms", () => {
  it("lists the name, the benefit as points, the validity, the stackable mark and the concepts as the server sent them", () => {
    renderWithAdminI18n(<DiscountTerms terms={discount({ name: "Campaña de matrícula", percent: "10.00", stackable: true })} />);

    expect(screen.getByText("Campaña de matrícula")).toBeInTheDocument();
    expect(screen.getByText("10.00 %")).toBeInTheDocument();
    expect(screen.getByText("Del 1 ene 2027 al 31 dic 2027")).toBeInTheDocument();
    expect(screen.getByText("Acumulable")).toBeInTheDocument();
    expect(screen.getByText("Mensualidad")).toBeInTheDocument();
    expect(screen.queryByText("Reactivar el descuento")).not.toBeInTheDocument();
  });

  it("shows a fixed amount as money and says that a proposal that reactivates turns the discount on", () => {
    const proposal = discountProposal({ benefitType: "FIXED_AMOUNT", percent: null, fixedAmount: { amount: "250.00", currency: "HNL" }, reactivate: true, stackable: false });
    renderWithAdminI18n(<DiscountTerms terms={proposal} />);

    expect(screen.getByText("L 250.00")).toBeInTheDocument();
    expect(screen.getByText("No acumulable")).toBeInTheDocument();
    expect(screen.getByText("Reactivar el descuento")).toBeInTheDocument();
  });
});

describe("BenefitValue", () => {
  it("shows a percentage with its sign and a fixed amount with its currency", () => {
    const { container } = renderWithAdminI18n(
      <>
        <BenefitValue terms={{ benefitType: "PERCENTAGE", percent: "7.50" }} />
        <BenefitValue terms={{ benefitType: "FIXED_AMOUNT", fixedAmount: { amount: "1250.00", currency: "HNL" } }} />
      </>,
    );

    expect(within(container).getByText("7.50 %")).toBeInTheDocument();
    expect(within(container).getByText("L 1,250.00")).toBeInTheDocument();
  });
});

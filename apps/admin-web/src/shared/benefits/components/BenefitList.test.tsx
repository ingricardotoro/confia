import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { BenefitList } from "./BenefitList.tsx";

const money = (amount: string) => ({ amount, currency: "HNL" });

// States. AW57, AW58: the benefits of a charge rendered with props alone. Loading and error do not apply: a charge always carries its list. Empty: no benefit,
// said in text and with no list. Success: each benefit in the order the server applied it, with its source, its code and its amount as they arrived.
describe("BenefitList", () => {
  it("lists each benefit in the order the server applied it, with its source, its code and the amount exactly as it arrived", () => {
    renderWithAdminI18n(
      <BenefitList
        benefits={[
          { source: "SCHOLARSHIP", code: "BECA20", scholarshipAwardId: "00000000-0000-4000-8000-0000000f0001", amount: money("200.00") },
          { source: "DISCOUNT", code: "CAMP10", discountId: "00000000-0000-4000-8000-0000000e0001", amount: money("80.00") },
        ]}
      />,
    );

    const lines = screen.getAllByRole("listitem");
    expect(lines).toHaveLength(2);
    expect(within(lines[0] as HTMLElement).getByText("Beca BECA20")).toBeInTheDocument();
    expect(within(lines[0] as HTMLElement).getByText("L 200.00")).toBeInTheDocument();
    expect(within(lines[1] as HTMLElement).getByText("Descuento CAMP10")).toBeInTheDocument();
    expect(within(lines[1] as HTMLElement).getByText("L 80.00")).toBeInTheDocument();
  });

  it("says in text that a charge has no benefit, and draws no list", () => {
    renderWithAdminI18n(<BenefitList benefits={[]} />);

    expect(screen.getByText("Sin beneficios")).toBeInTheDocument();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });
});

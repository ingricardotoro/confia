import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { DiscountApprovalBadge } from "./DiscountApprovalBadge.tsx";

// States. AW57, AW58: The badge of what a discount waits for, rendered with props alone. Loading, error and empty do not apply: it draws one state it is given.
// Success: each of the four states says its meaning in words (never by color alone).
describe("DiscountApprovalBadge", () => {
  it.each([
    ["approved", "Aprobado"],
    ["pending", "Pendiente de aprobación"],
    ["proposal", "Propuesta pendiente"],
    ["rejected", "Rechazado"],
  ] as const)("says %s in words", (state, text) => {
    renderWithAdminI18n(<DiscountApprovalBadge state={state} />);

    expect(screen.getByText(text)).toBeInTheDocument();
  });
});

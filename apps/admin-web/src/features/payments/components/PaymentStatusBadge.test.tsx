import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { PaymentStatusBadge } from "./PaymentStatusBadge.tsx";

// States. AW57, AW58: the badge of a payment rendered with props alone. It holds no data, so loading, empty and error do not apply. Success: each of
// the three states says itself in text, with a dot that is a second carrier and is hidden from assistive technology.
describe("PaymentStatusBadge", () => {
  it.each([
    ["confirmed", "Confirmado", "filled"],
    ["declared", "Pendiente de confirmación", "filled"],
    ["rejected", "Rechazado", "hollow"],
  ] as const)("AW57: says %s in text, with a %s dot", (status, text, dot) => {
    renderWithAdminI18n(<PaymentStatusBadge status={status} />);

    const badge = screen.getByText(text);
    expect(badge).toBeInTheDocument();
    expect(badge.firstElementChild).toHaveAttribute("data-dot", dot);
    expect(badge.firstElementChild).toHaveAttribute("aria-hidden", "true");
  });
});

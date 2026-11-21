import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { paymentSummary } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { PaymentsView, type PaymentsViewProps } from "./PaymentsView.tsx";

// States. AW57, AW58: the payments list rendered with props alone. Loading, error and empty are drawn by the table (`table.loading`, `table.error`,
// `table.empty`) and are covered where the list is loaded. Success: each payment with its method, state and amount as the server sent them, and a link
// to its detail; the filters report what the person chooses.
const DECLARED = paymentSummary({ id: "00000000-0000-4000-8000-0000000d0001", method: "BANK_TRANSFER", status: "declared", reference: "AB1234", amount: { amount: "1265.00", currency: "HNL" } });
const CASH = paymentSummary({ id: "00000000-0000-4000-8000-0000000d0002" });

function props(override: Partial<PaymentsViewProps> = {}): PaymentsViewProps {
  return {
    filters: {
      status: { value: "", onChange: vi.fn() },
      method: { value: "", onChange: vi.fn() },
      from: { value: "", onChange: vi.fn() },
      to: { value: "", onChange: vi.fn() },
    },
    rows: [DECLARED, CASH],
    table: loadedTable,
    pagination: onePage,
    hrefOf: (row) => `/payments/${row.id}`,
    onOpen: vi.fn(),
    ...override,
  };
}

describe("PaymentsView", () => {
  it("AW57: shows each payment in words with the amount as the server sent it, and opens the detail from its link", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    renderWithAdminI18n(<PaymentsView {...props({ onOpen })} />);

    const declared = within(screen.getByRole("row", { name: /AB1234/ }));
    expect(declared.getByText("Transferencia")).toBeInTheDocument();
    expect(declared.getByText("Pendiente de confirmación")).toBeInTheDocument();
    expect(declared.getAllByText(/1,265\.00/).length).toBeGreaterThan(0);
    expect(declared.getByRole("link")).toHaveAttribute("href", `/payments/${DECLARED.id}`);
    await user.click(declared.getByRole("link"));
    expect(onOpen).toHaveBeenCalledWith(DECLARED);
  });

  it("AW57: reports the status, the method and the dates the person chooses, and shows the error of the end date", async () => {
    const user = userEvent.setup();
    const onStatus = vi.fn();
    const onMethod = vi.fn();
    renderWithAdminI18n(
      <PaymentsView
        {...props({
          filters: {
            status: { value: "", onChange: onStatus },
            method: { value: "", onChange: onMethod },
            from: { value: "", onChange: vi.fn() },
            to: { value: "2026-11-01", error: "La fecha final no puede ser anterior a la inicial.", onChange: vi.fn() },
          },
        })}
      />,
    );

    await user.selectOptions(screen.getByRole("combobox", { name: "Estado" }), "Confirmado");
    await user.selectOptions(screen.getByRole("combobox", { name: "Método" }), "Efectivo");

    expect(onStatus).toHaveBeenCalledWith("confirmed");
    expect(onMethod).toHaveBeenCalledWith("CASH");
    expect(screen.getByText("La fecha final no puede ser anterior a la inicial.")).toBeInTheDocument();
  });
});

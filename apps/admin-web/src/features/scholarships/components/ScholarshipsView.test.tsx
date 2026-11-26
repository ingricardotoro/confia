import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { scholarship } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ScholarshipsView, type ScholarshipsViewProps } from "./ScholarshipsView.tsx";

// States. AW57, AW58: the scholarships list rendered with props alone. Loading, error and empty are drawn by the table (`table.loading`, `table.error`,
// `table.empty`) and are covered where the list is loaded. Success: each scholarship with its benefit, cap, concepts and state as the server sent them,
// the actions only when their handlers are given, and the filters report what the person chooses.
const PERCENTAGE = scholarship({ code: "BECA25", name: "Beca de excelencia", percent: "25.00" });
const FIXED = scholarship({
  code: "FIJA250",
  name: "Beca fija",
  benefitType: "FIXED_AMOUNT",
  percent: null,
  fixedAmount: { amount: "250.00", currency: "HNL" },
  periodCap: { amount: "1000.00", currency: "HNL" },
  active: false,
});

function props(override: Partial<ScholarshipsViewProps> = {}): ScholarshipsViewProps {
  return {
    rows: [PERCENTAGE, FIXED],
    table: loadedTable,
    pagination: onePage,
    filters: { benefitType: { value: "", onChange: vi.fn() }, active: { value: "", onChange: vi.fn() } },
    ...override,
  };
}

describe("ScholarshipsView", () => {
  it("AW57: shows a percentage as points and a fixed amount as money, with the cap or its absence, as the server sent them", () => {
    renderWithAdminI18n(<ScholarshipsView {...props()} />);

    const percentage = within(screen.getByRole("row", { name: /Beca de excelencia/ }));
    expect(percentage.getByText("25.00 %")).toBeInTheDocument();
    expect(percentage.getByText("Sin tope")).toBeInTheDocument();
    const fixed = within(screen.getByRole("row", { name: /Beca fija/ }));
    expect(fixed.getByText("L 250.00")).toBeInTheDocument();
    expect(fixed.getByText("L 1,000.00")).toBeInTheDocument();
    expect(fixed.getByText("Inactivo")).toBeInTheDocument();
  });

  it("AW57: draws no action when no handler is given, and each button names its scholarship when they are", async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    const onToggle = vi.fn();
    const { rerender } = renderWithAdminI18n(<ScholarshipsView {...props()} />);
    expect(screen.queryByRole("button", { name: /Editar/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Crear beca/ })).not.toBeInTheDocument();

    rerender(<ScholarshipsView {...props({ onEdit, onToggle, onCreate: vi.fn() })} />);
    await user.click(screen.getByRole("button", { name: "Editar Beca fija" }));
    await user.click(screen.getByRole("button", { name: "Activar Beca fija" }));

    expect(onEdit).toHaveBeenCalledWith(FIXED);
    expect(onToggle).toHaveBeenCalledWith(FIXED);
    expect(screen.getByRole("button", { name: "Crear beca" })).toBeInTheDocument();
  });

  it("AW57: reports the type of benefit and the state the person chooses", async () => {
    const user = userEvent.setup();
    const onType = vi.fn();
    const onActive = vi.fn();
    renderWithAdminI18n(<ScholarshipsView {...props({ filters: { benefitType: { value: "", onChange: onType }, active: { value: "", onChange: onActive } } })} />);

    await user.selectOptions(screen.getByLabelText("Tipo de beneficio"), "Monto fijo");
    await user.selectOptions(screen.getByLabelText("Estado"), "Inactivo");

    expect(onType).toHaveBeenCalledWith("FIXED_AMOUNT");
    expect(onActive).toHaveBeenCalledWith("false");
  });
});

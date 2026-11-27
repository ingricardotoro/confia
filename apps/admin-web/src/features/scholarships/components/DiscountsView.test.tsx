import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { discount } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { DiscountsView, type DiscountsViewProps } from "./DiscountsView.tsx";

// States. AW57, AW58: The discounts list rendered with props alone. Loading, error and empty are drawn by the table (`table.loading`, `table.error`, `table.empty`)
// and are covered where the list is loaded. Success: each discount with its class, benefit, validity, stackable mark, concepts and state as the server
// sent them, the actions only when their handlers are given, and the filters report what the person chooses.
const CAMPAIGN = discount({ code: "CAMP10", name: "Campaña de matrícula", percent: "10.00" });
const AGREEMENT = discount({
  code: "CONV250",
  name: "Convenio con la empresa",
  kind: "agreement",
  benefitType: "FIXED_AMOUNT",
  percent: null,
  fixedAmount: { amount: "250.00", currency: "HNL" },
  stackable: false,
  active: false,
});

function props(override: Partial<DiscountsViewProps> = {}): DiscountsViewProps {
  return {
    rows: [CAMPAIGN, AGREEMENT],
    table: loadedTable,
    pagination: onePage,
    filters: { kind: { value: "", onChange: vi.fn() }, benefitType: { value: "", onChange: vi.fn() }, active: { value: "", onChange: vi.fn() } },
    ...override,
  };
}

describe("DiscountsView", () => {
  it("shows a percentage as points and a fixed amount as money, with the class, the validity and the stackable mark, as the server sent them", () => {
    renderWithAdminI18n(<DiscountsView {...props()} />);

    const campaign = within(screen.getByRole("row", { name: /Campaña de matrícula/ }));
    expect(campaign.getByText("Campaña")).toBeInTheDocument();
    expect(campaign.getByText("10.00 %")).toBeInTheDocument();
    expect(campaign.getByText("Acumulable")).toBeInTheDocument();
    expect(campaign.getByText("Mensualidad")).toBeInTheDocument();
    expect(campaign.getByText("Activo")).toBeInTheDocument();
    const agreement = within(screen.getByRole("row", { name: /Convenio con la empresa/ }));
    expect(agreement.getByText("Convenio")).toBeInTheDocument();
    expect(agreement.getByText("L 250.00")).toBeInTheDocument();
    expect(agreement.getByText("No acumulable")).toBeInTheDocument();
    expect(agreement.getByText("Inactivo")).toBeInTheDocument();
  });

  it("draws no action when no handler is given, and each button names its discount when they are", async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    const onToggle = vi.fn();
    const { rerender } = renderWithAdminI18n(<DiscountsView {...props()} />);
    expect(screen.queryByRole("button", { name: /Editar/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Crear descuento" })).not.toBeInTheDocument();

    rerender(<DiscountsView {...props({ onEdit, onToggle, onCreate: vi.fn() })} />);
    await user.click(screen.getByRole("button", { name: "Editar Convenio con la empresa" }));
    await user.click(screen.getByRole("button", { name: "Activar Convenio con la empresa" }));

    expect(onEdit).toHaveBeenCalledWith(AGREEMENT);
    expect(onToggle).toHaveBeenCalledWith(AGREEMENT);
    expect(screen.getByRole("button", { name: "Crear descuento" })).toBeInTheDocument();
  });

  it("reports the class, the type of benefit and the state the person chooses", async () => {
    const user = userEvent.setup();
    const onKind = vi.fn();
    const onType = vi.fn();
    const onActive = vi.fn();
    renderWithAdminI18n(
      <DiscountsView {...props({ filters: { kind: { value: "", onChange: onKind }, benefitType: { value: "", onChange: onType }, active: { value: "", onChange: onActive } } })} />,
    );

    await user.selectOptions(screen.getByLabelText("Clase"), "Convenio");
    await user.selectOptions(screen.getByLabelText("Tipo de beneficio"), "Monto fijo");
    await user.selectOptions(screen.getByLabelText("Estado"), "Inactivo");

    expect(onKind).toHaveBeenCalledWith("agreement");
    expect(onType).toHaveBeenCalledWith("FIXED_AMOUNT");
    expect(onActive).toHaveBeenCalledWith("false");
  });
});

import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { discount, discountProposal } from "../../../test/contract-responses.ts";
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

const ME = "00000000-0000-4000-8000-0000000000a1";
const OTHER = "00000000-0000-4000-8000-0000000000a2";
const PENDING = discount({ code: "NUEVA", name: "Campaña nueva", approvalStatus: "pending", createdBy: ME });
const WITH_PROPOSAL = discount({ code: "AMPLIA", name: "Campaña con propuesta", pendingChange: discountProposal({ percent: "15.00", requestedBy: ME }), createdBy: OTHER });
const REJECTED = discount({ code: "NO", name: "Campaña rechazada", approvalStatus: "rejected", active: false, createdBy: OTHER });

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

describe("DiscountsView approval", () => {
  it("says in words what each discount waits for: pending, with a proposal beside the terms in force and rejected; an approved one says nothing more than its state", () => {
    renderWithAdminI18n(<DiscountsView {...props({ rows: [CAMPAIGN, PENDING, WITH_PROPOSAL, REJECTED] })} />);

    const approved = within(screen.getByRole("row", { name: /Campaña de matrícula/ }));
    expect(approved.getByText("Activo")).toBeInTheDocument();
    expect(approved.queryByText(/Pendiente de aprobación|Propuesta pendiente|Rechazado/)).not.toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Campaña nueva/ })).getByText("Pendiente de aprobación")).toBeInTheDocument();
    const proposal = within(screen.getByRole("row", { name: /Campaña con propuesta/ }));
    expect(proposal.getByText("Propuesta pendiente")).toBeInTheDocument();
    // The terms in force stay in the benefit column; the proposed ones are told beside them.
    expect(proposal.getByText("10.00 %")).toBeInTheDocument();
    expect(proposal.getByText("15.00 %")).toBeInTheDocument();
    expect(proposal.getByText(/Propone/)).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Campaña rechazada/ })).getByText("Rechazado")).toBeInTheDocument();
  });

  it("offers no edit and no reactivation while a discount waits or was rejected, but still lets a waiting one be deactivated", () => {
    renderWithAdminI18n(<DiscountsView {...props({ rows: [PENDING, WITH_PROPOSAL, REJECTED], onEdit: vi.fn(), onToggle: vi.fn() })} />);

    expect(screen.queryByRole("button", { name: /^Editar/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Desactivar Campaña nueva" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Desactivar Campaña con propuesta" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Activar Campaña rechazada" })).not.toBeInTheDocument();
  });

  it("offers Approve and Reject on what waits, each naming its discount, and reports the row", async () => {
    const user = userEvent.setup();
    const onApprove = vi.fn();
    const onReject = vi.fn();
    renderWithAdminI18n(<DiscountsView {...props({ rows: [PENDING, CAMPAIGN], accountId: OTHER, onApprove, onReject })} />);

    await user.click(screen.getByRole("button", { name: "Aprobar Campaña nueva" }));
    await user.click(screen.getByRole("button", { name: "Rechazar Campaña nueva" }));

    expect(onApprove).toHaveBeenCalledWith(PENDING);
    expect(onReject).toHaveBeenCalledWith(PENDING);
    expect(screen.queryByRole("button", { name: "Aprobar Campaña de matrícula" })).not.toBeInTheDocument();
  });

  it("hides Approve and Reject from the person who created the discount and from the one who proposed the change (the server still decides)", () => {
    renderWithAdminI18n(<DiscountsView {...props({ rows: [PENDING, WITH_PROPOSAL], accountId: ME, onApprove: vi.fn(), onReject: vi.fn() })} />);

    expect(screen.queryByRole("button", { name: "Aprobar Campaña nueva" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Rechazar Campaña nueva" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Aprobar Campaña con propuesta" })).not.toBeInTheDocument();
  });

  it("offers the decision on a proposal to the creator of the discount when somebody else proposed it", () => {
    renderWithAdminI18n(<DiscountsView {...props({ rows: [WITH_PROPOSAL], accountId: OTHER, onApprove: vi.fn() })} />);

    expect(screen.getByRole("button", { name: "Aprobar Campaña con propuesta" })).toBeInTheDocument();
  });

  it("draws no decision when no handler is given", () => {
    renderWithAdminI18n(<DiscountsView {...props({ rows: [PENDING], accountId: OTHER })} />);

    expect(screen.queryByRole("button", { name: /^(Aprobar|Rechazar)/ })).not.toBeInTheDocument();
  });
});

import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { assignment } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AssignmentsView, type AssignmentsViewProps } from "./AssignmentsView.tsx";

// States. AW57, AW58: The agreement assignments list rendered with props alone. Loading, error and empty are drawn by the table (`table.loading`, `table.error`,
// `table.empty`) and are covered where the list is loaded. Success: each assignment with its student, discount, validity and state as the server sent
// them, and a link to its detail; the filters report what the person chooses; the student filter is a search or the chosen student.
const PENDING = assignment({ id: "00000000-0000-4000-8000-0000000c0001", studentCode: "EST-0001", status: "pending" });
const REVOKED = assignment({ id: "00000000-0000-4000-8000-0000000c0002", studentCode: "EST-0002", status: "revoked", discountCode: null, discountName: null });

const picker = {
  controlId: "assignments-student",
  draft: "",
  onDraftChange: vi.fn(),
  tooShort: false,
  search: "idle",
  results: [],
  onRetry: vi.fn(),
  chosenPending: false,
  onChoose: vi.fn(),
  onClear: vi.fn(),
} as const;

function props(override: Partial<AssignmentsViewProps> = {}): AssignmentsViewProps {
  return {
    filters: {
      status: { value: "", onChange: vi.fn() },
      discount: { value: "", options: [{ value: "d1", label: "CONV20 · Convenio con la empresa" }], onChange: vi.fn() },
      student: { picker },
    },
    rows: [PENDING, REVOKED],
    table: loadedTable,
    pagination: onePage,
    hrefOf: (row) => `/scholarships/discount-assignments/${row.id}`,
    onOpen: vi.fn(),
    ...override,
  };
}

describe("AssignmentsView", () => {
  it("shows each assignment with its state in words and the validity, and opens the detail from its link", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    renderWithAdminI18n(<AssignmentsView {...props({ onOpen })} />);

    const pending = within(screen.getByRole("row", { name: /EST-0001/ }));
    expect(pending.getByText("Pendiente de aprobación")).toBeInTheDocument();
    expect(pending.getByText("CONV20 · Convenio con la empresa")).toBeInTheDocument();
    expect(pending.getByText("1 ene 2027")).toBeInTheDocument();
    expect(pending.getByText("31 dic 2027")).toBeInTheDocument();
    expect(pending.getByRole("link")).toHaveAttribute("href", `/scholarships/discount-assignments/${PENDING.id}`);
    await user.click(pending.getByRole("link"));
    expect(onOpen).toHaveBeenCalledWith(PENDING);
    const revoked = within(screen.getByRole("row", { name: /EST-0002/ }));
    expect(revoked.getByText("Revocado")).toBeInTheDocument();
    expect(revoked.getByText("—")).toBeInTheDocument();
  });

  it("reports the status and the discount the person chooses", async () => {
    const user = userEvent.setup();
    const onStatus = vi.fn();
    const onDiscount = vi.fn();
    renderWithAdminI18n(
      <AssignmentsView
        {...props({
          filters: {
            status: { value: "", onChange: onStatus },
            discount: { value: "", options: [{ value: "d1", label: "CONV20 · Convenio con la empresa" }], onChange: onDiscount },
            student: { picker },
          },
        })}
      />,
    );

    await user.selectOptions(screen.getByLabelText("Estado"), "Aprobado");
    await user.selectOptions(screen.getByLabelText("Descuento"), "CONV20 · Convenio con la empresa");

    expect(onStatus).toHaveBeenCalledWith("approved");
    expect(onDiscount).toHaveBeenCalledWith("d1");
  });

  it("offers the search of a student while none is chosen, and the chosen one with how to remove it afterwards", async () => {
    const user = userEvent.setup();
    const onClear = vi.fn();
    const { rerender } = renderWithAdminI18n(<AssignmentsView {...props()} />);
    expect(screen.getByRole("combobox", { name: /^Estudiante/ })).toBeInTheDocument();

    rerender(<AssignmentsView {...props({ filters: { ...props().filters, student: { picker, chosen: { label: "Pérez, Ana (EST-0001)", onClear } } } })} />);
    expect(screen.queryByRole("combobox", { name: /^Estudiante/ })).not.toBeInTheDocument();
    expect(screen.getByText("Pérez, Ana (EST-0001)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Quitar estudiante" }));
    expect(onClear).toHaveBeenCalled();
  });

  it("draws the request button only when the person may request, and the notice and the lookup failure when there are", async () => {
    const user = userEvent.setup();
    const onRequest = vi.fn();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<AssignmentsView {...props()} />);
    expect(screen.queryByRole("button", { name: "Solicitar asignación" })).not.toBeInTheDocument();

    rerender(<AssignmentsView {...props({ onRequest, notice: { tone: "success", message: "Se solicitó la asignación." }, lookupFailure: { onRetry } })} />);
    await user.click(screen.getByRole("button", { name: "Solicitar asignación" }));
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRequest).toHaveBeenCalled();
    expect(onRetry).toHaveBeenCalled();
    expect(screen.getByText("Se solicitó la asignación.")).toBeInTheDocument();
  });
});

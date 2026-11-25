import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { award } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AwardsView, type AwardsViewProps } from "./AwardsView.tsx";

// States. AW57, AW58: the awards list rendered with props alone. Loading, error and empty are drawn by the table (`table.loading`, `table.error`,
// `table.empty`) and are covered where the list is loaded. Success: each award with its student, scholarship, validity and state as the server sent them,
// and a link to its detail; the filters report what the person chooses; the student filter is a search or the chosen student.
const PENDING = award({ id: "00000000-0000-4000-8000-0000000f0001", studentCode: "EST-0001", status: "pending" });
const REVOKED = award({ id: "00000000-0000-4000-8000-0000000f0002", studentCode: "EST-0002", status: "revoked", scholarshipCode: null, scholarshipName: null });

const picker = {
  controlId: "awards-student",
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

function props(override: Partial<AwardsViewProps> = {}): AwardsViewProps {
  return {
    filters: {
      status: { value: "", onChange: vi.fn() },
      scholarship: { value: "", options: [{ value: "00000000-0000-4000-8000-0000000e0001", label: "BECA25 · Beca de excelencia" }], onChange: vi.fn() },
      student: { picker },
    },
    rows: [PENDING, REVOKED],
    table: loadedTable,
    pagination: onePage,
    hrefOf: (row) => `/scholarships/awards/${row.id}`,
    onOpen: vi.fn(),
    ...override,
  };
}

describe("AwardsView", () => {
  it("AW57: shows each award with its state in words and the validity, and opens the detail from its link", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    renderWithAdminI18n(<AwardsView {...props({ onOpen })} />);

    const pending = within(screen.getByRole("row", { name: /EST-0001/ }));
    expect(pending.getByText("Pendiente de aprobación")).toBeInTheDocument();
    expect(pending.getByText("BECA25 · Beca de excelencia")).toBeInTheDocument();
    expect(pending.getByText("1 ene 2027")).toBeInTheDocument();
    expect(pending.getByText("31 dic 2027")).toBeInTheDocument();
    expect(pending.getByRole("link")).toHaveAttribute("href", `/scholarships/awards/${PENDING.id}`);
    await user.click(pending.getByRole("link"));
    expect(onOpen).toHaveBeenCalledWith(PENDING);
    expect(within(screen.getByRole("row", { name: /EST-0002/ })).getByText("Revocado")).toBeInTheDocument();
  });

  it("AW57: reports the status and the scholarship the person chooses", async () => {
    const user = userEvent.setup();
    const onStatus = vi.fn();
    const onScholarship = vi.fn();
    renderWithAdminI18n(
      <AwardsView
        {...props({
          filters: {
            status: { value: "", onChange: onStatus },
            scholarship: { value: "", options: [{ value: "s1", label: "BECA25 · Beca de excelencia" }], onChange: onScholarship },
            student: { picker },
          },
        })}
      />,
    );

    await user.selectOptions(screen.getByLabelText("Estado"), "Aprobado");
    await user.selectOptions(screen.getByLabelText("Beca"), "BECA25 · Beca de excelencia");

    expect(onStatus).toHaveBeenCalledWith("approved");
    expect(onScholarship).toHaveBeenCalledWith("s1");
  });

  it("AW57: offers the search of a student while none is chosen, and the chosen one with how to remove it afterwards", async () => {
    const user = userEvent.setup();
    const onClear = vi.fn();
    const { rerender } = renderWithAdminI18n(<AwardsView {...props()} />);
    expect(screen.getByRole("combobox", { name: /^Estudiante/ })).toBeInTheDocument();

    rerender(
      <AwardsView {...props({ filters: { ...props().filters, student: { picker, chosen: { label: "Pérez, Ana (EST-0001)", onClear } } } })} />,
    );
    expect(screen.queryByRole("combobox", { name: /^Estudiante/ })).not.toBeInTheDocument();
    expect(screen.getByText("Pérez, Ana (EST-0001)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Quitar estudiante" }));
    expect(onClear).toHaveBeenCalled();
  });

  it("AW57: draws the request button only when the person may request, and the notice and the lookup failure when there are", async () => {
    const user = userEvent.setup();
    const onRequest = vi.fn();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<AwardsView {...props()} />);
    expect(screen.queryByRole("button", { name: "Solicitar otorgamiento" })).not.toBeInTheDocument();

    rerender(<AwardsView {...props({ onRequest, notice: { tone: "success", message: "Se solicitó el otorgamiento." }, lookupFailure: { onRetry } })} />);
    await user.click(screen.getByRole("button", { name: "Solicitar otorgamiento" }));
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRequest).toHaveBeenCalled();
    expect(onRetry).toHaveBeenCalled();
    expect(screen.getByText("Se solicitó el otorgamiento.")).toBeInTheDocument();
  });
});

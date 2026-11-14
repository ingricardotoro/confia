import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { guardian } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { GuardiansView, type GuardiansViewProps } from "./GuardiansView.tsx";

// States. AW57, AW58: the table of guardians rendered with props alone. Loading: `table.loading`. Error and empty: `table.error` and
// `table.empty`, drawn by the table. Success: the rows, with "registered" or a dash for the document and the RTN.
function props(override: Partial<GuardiansViewProps> = {}): GuardiansViewProps {
  return {
    rows: [guardian({ firstName: "Rosa", lastName: "Mejía", guardianCode: "P-000001" })],
    table: loadedTable,
    pagination: onePage,
    filters: { search: { value: "", onChange: vi.fn(), tooShort: false }, status: { value: "", onChange: vi.fn() } },
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onToggle: vi.fn(),
    onDetail: vi.fn(),
    ...override,
  };
}

describe("GuardiansView", () => {
  it("shows the code, the names, a dash for no document and no RTN, and the state", () => {
    renderWithAdminI18n(<GuardiansView {...props()} />);

    const row = screen.getByRole("row", { name: /Mejía/ });
    expect(within(row).getByText("P-000001")).toBeInTheDocument();
    expect(within(row).getAllByText("—")).toHaveLength(2);
    expect(within(row).getAllByText("Sin registrar")[0]).toHaveClass("sr-only");
    expect(within(row).getByText("Activo")).toBeInTheDocument();
  });

  it("shows only that a document and an RTN are registered, never digits", () => {
    renderWithAdminI18n(<GuardiansView {...props({ rows: [guardian({ hasNationalId: true, hasRtn: true })] })} />);

    expect(screen.getAllByText("Registrado")).toHaveLength(2);
    expect(screen.getAllByText("Dato registrado")[0]).toHaveClass("sr-only");
    expect(screen.queryByText("—")).not.toBeInTheDocument();
  });

  it("names the guardian in the detail, edit and deactivate buttons, and calls back with the row", async () => {
    const user = userEvent.setup();
    const row = guardian({ firstName: "Rosa", lastName: "Mejía" });
    const handlers = { onEdit: vi.fn(), onToggle: vi.fn(), onDetail: vi.fn() };
    renderWithAdminI18n(<GuardiansView {...props({ rows: [row], ...handlers })} />);

    await user.click(screen.getByRole("button", { name: "Ver detalle de Rosa Mejía" }));
    await user.click(screen.getByRole("button", { name: "Editar Rosa Mejía" }));
    await user.click(screen.getByRole("button", { name: "Desactivar Rosa Mejía" }));

    expect(handlers.onDetail).toHaveBeenCalledWith(row);
    expect(handlers.onEdit).toHaveBeenCalledWith(row);
    expect(handlers.onToggle).toHaveBeenCalledWith(row);
  });

  it("marks an inactive guardian and offers to activate it", () => {
    renderWithAdminI18n(<GuardiansView {...props({ rows: [guardian({ firstName: "Eva", lastName: "Rivas", status: "INACTIVE" })] })} />);

    expect(within(screen.getByRole("table")).getByText("Inactivo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Activar Eva Rivas" })).toBeInTheDocument();
  });

  it("shows the loading state of the table", () => {
    renderWithAdminI18n(<GuardiansView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);

    expect(screen.getByRole("table", { name: "Encargados de la institución" })).toHaveAttribute("aria-busy", "true");
  });

  it("shows the error state with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(
      <GuardiansView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar la lista de encargados", description: "x", onRetry } } })} />,
    );

    expect(screen.getByText("No se pudo cargar la lista de encargados")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("shows the empty state with its action, and the result notice in a live region", () => {
    renderWithAdminI18n(
      <GuardiansView
        {...props({
          rows: [],
          table: { ...loadedTable, empty: { title: "Todavía no hay encargados", action: { label: "Registrar al primer encargado", onAction: vi.fn() } } },
          notice: { tone: "success", message: "Se creó el registro «Rosa Mejía»." },
        })}
      />,
    );

    expect(screen.getByText("Todavía no hay encargados")).toBeInTheDocument();
    expect(screen.getByText("Se creó el registro «Rosa Mejía».").closest("[aria-live]")).toHaveAttribute("aria-live", "polite");
  });
});

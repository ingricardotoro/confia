import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { student } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { StudentsView, type StudentsViewProps } from "./StudentsView.tsx";

// States. AW57, AW58: the table of students rendered with props alone. Loading: `table.loading`. Error and empty:
// `table.error` and `table.empty`, drawn by the table. Success: the rows, with "registered" or a dash. The
// filters (search and status) are a child with its own test.
function props(override: Partial<StudentsViewProps> = {}): StudentsViewProps {
  return {
    rows: [student({ firstName: "Ana", lastName: "Pérez", studentCode: "E-000001", birthDate: "2014-03-09" })],
    table: loadedTable,
    pagination: onePage,
    filters: { search: { value: "", onChange: vi.fn(), tooShort: false }, status: { value: "", onChange: vi.fn() } },
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onToggle: vi.fn(),
    onGuardians: vi.fn(),
    ...override,
  };
}

describe("StudentsView", () => {
  it("AW57: shows the code, the names, the date written for the person, the sex, a dash for no document and the state", () => {
    renderWithAdminI18n(<StudentsView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Código",
      "Apellidos",
      "Nombres",
      "Fecha de nacimiento",
      "Sexo",
      "Documento",
      "Estado",
      "Acciones",
    ]);
    const row = screen.getByRole("row", { name: /Pérez/ });
    expect(within(row).getByText("E-000001")).toBeInTheDocument();
    expect(within(row).getByText(new Intl.DateTimeFormat("es-HN", { dateStyle: "medium", timeZone: "UTC" }).format(Date.UTC(2014, 2, 9)))).toBeInTheDocument();
    expect(within(row).getByText("Femenino")).toBeInTheDocument();
    // The dash is for the eye; the reader says what it means.
    expect(within(row).getByText("—")).toHaveAttribute("aria-hidden", "true");
    expect(within(row).getByText("Sin documento")).toHaveClass("sr-only");
    expect(within(row).getByText("Activo")).toBeInTheDocument();
  });

  it("shows only that a document is registered, with an accessible text, and no digits", () => {
    renderWithAdminI18n(<StudentsView {...props({ rows: [student({ hasNationalId: true })] })} />);

    expect(screen.getByText("Registrado")).toBeInTheDocument();
    expect(screen.getByText("Documento registrado")).toHaveClass("sr-only");
    expect(screen.getByText("Registrado").closest("td")).toHaveTextContent(/^RegistradoDocumento registrado$/);
    expect(screen.queryByText("—")).not.toBeInTheDocument();
  });

  it("marks an inactive student and offers to activate it, naming the student in the button", () => {
    renderWithAdminI18n(<StudentsView {...props({ rows: [student({ firstName: "Eva", lastName: "Rivas", status: "INACTIVE" })] })} />);

    const row = screen.getByRole("row", { name: /Rivas/ });
    expect(within(row).getByText("Inactivo")).toBeInTheDocument();
    expect(within(row).getByRole("button", { name: "Activar Eva Rivas" })).toBeInTheDocument();
  });

  it("only the columns the server can order by are sortable", () => {
    renderWithAdminI18n(<StudentsView {...props()} />);

    for (const name of ["Código", "Apellidos", "Nombres", "Fecha de nacimiento"]) {
      expect(screen.getByRole("button", { name })).toBeInTheDocument();
    }
    for (const name of ["Sexo", "Documento", "Estado", "Acciones"]) {
      expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
    }
  });

  it("reports the row of the edit and of the toggle, and the creation", async () => {
    const user = userEvent.setup();
    const handlers = { onCreate: vi.fn(), onEdit: vi.fn(), onToggle: vi.fn() };
    const view = props(handlers);
    renderWithAdminI18n(<StudentsView {...view} />);

    await user.click(screen.getByRole("button", { name: "Nuevo estudiante" }));
    await user.click(screen.getByRole("button", { name: "Editar Ana Pérez" }));
    await user.click(screen.getByRole("button", { name: "Desactivar Ana Pérez" }));

    expect(handlers.onCreate).toHaveBeenCalledTimes(1);
    expect(handlers.onEdit).toHaveBeenCalledWith(view.rows[0]);
    expect(handlers.onToggle).toHaveBeenCalledWith(view.rows[0]);
  });

  it("marks the row being saved as busy", () => {
    const row = student({ firstName: "Ana", lastName: "Pérez" });
    renderWithAdminI18n(<StudentsView {...props({ rows: [row], togglingId: row.id })} />);

    expect(screen.getByRole("button", { name: "Desactivar Ana Pérez" })).toHaveAttribute("aria-busy", "true");
  });

  it("draws the loading state with the shape of the table", () => {
    renderWithAdminI18n(<StudentsView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);

    expect(screen.getByRole("table", { name: "Estudiantes de la institución" })).toHaveAttribute("aria-busy", "true");
  });

  it("draws the error state with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(
      <StudentsView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar la lista de estudiantes", description: "Detalle", onRetry } } })} />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo cargar la lista de estudiantes");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("draws the empty state with its way forward", async () => {
    const user = userEvent.setup();
    const onAction = vi.fn();
    renderWithAdminI18n(
      <StudentsView {...props({ rows: [], table: { ...loadedTable, empty: { title: "Todavía no hay estudiantes", action: { label: "Registrar al primer estudiante", onAction } } } })} />,
    );

    expect(screen.getByText("Todavía no hay estudiantes")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Registrar al primer estudiante" }));

    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it("tells the result of the last action in a polite region that is always in the document", () => {
    const { rerender } = renderWithAdminI18n(<StudentsView {...props()} />);
    expect(screen.queryByText("Se creó el registro «Ana Pérez».")).not.toBeInTheDocument();

    rerender(<StudentsView {...props({ notice: { tone: "success", message: "Se creó el registro «Ana Pérez»." } })} />);

    expect(screen.getByRole("status").closest("[aria-live='polite']")).toHaveTextContent("Se creó el registro «Ana Pérez».");
  });

  it("has the filters in a named group", () => {
    renderWithAdminI18n(<StudentsView {...props()} />);

    const group = screen.getByRole("group", { name: "Filtros" });
    expect(within(group).getByLabelText("Buscar")).toBeInTheDocument();
    expect(within(group).getByLabelText("Estado")).toBeInTheDocument();
  });

  it("offers the guardians of each student in a button that names the student, and calls back with the row", async () => {
    const user = userEvent.setup();
    const onGuardians = vi.fn();
    const ana = student({ firstName: "Ana", lastName: "Pérez" });
    renderWithAdminI18n(<StudentsView {...props({ rows: [ana], onGuardians })} />);

    await user.click(screen.getByRole("button", { name: "Encargados de Ana Pérez" }));

    expect(screen.getByRole("button", { name: "Encargados de Ana Pérez" })).toHaveTextContent("Encargados");
    expect(onGuardians).toHaveBeenCalledWith(ana);
  });
});

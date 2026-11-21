import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { POSTING_ROLES } from "../posting-map-model.ts";
import { PostingMapView, type PostingMapRow, type PostingMapViewProps } from "./PostingMapView.tsx";

// States. AW57, AW58: the map of posting accounts rendered with props alone. Loading: a skeleton with the shape of the two
// cards, and a status for the screen reader. Error: the message with its retry. Empty: no active account at all, with the way
// to the chart. Success: the form with its rows, complete or incomplete, editable or read only.
const ASSET = "00000000-0000-4000-8000-0000000000a1";
const INCOME = "00000000-0000-4000-8000-0000000000a2";

function rows(configured: readonly string[] = []): PostingMapRow[] {
  return POSTING_ROLES.map((role) => ({
    id: role.id,
    group: role.group,
    value: configured.includes(role.id) ? (role.id === "RECEIVABLE" ? ASSET : INCOME) : "",
    options: [
      { value: ASSET, label: "ACT-01 · Cuentas por cobrar de prueba" },
      { value: INCOME, label: "ING-01 · Ingresos de prueba" },
    ],
    configured: configured.includes(role.id),
  }));
}

function props(override: Partial<PostingMapViewProps> = {}): PostingMapViewProps {
  return {
    state: { kind: "ready" },
    rows: rows(),
    missing: ["RECEIVABLE", "TAX_PAYABLE", "REVENUE_DEFAULT"],
    noAccounts: false,
    readOnly: false,
    pendingCount: 0,
    submitting: false,
    onChange: vi.fn(),
    onSave: vi.fn(),
    onReset: vi.fn(),
    onGoToAccounts: vi.fn(),
    ...override,
  };
}

describe("PostingMapView", () => {
  it("lists one select for each of the eleven roles, in three groups, with the status of each told in text", () => {
    renderWithAdminI18n(<PostingMapView {...props({ rows: rows(["RECEIVABLE"]) })} />);

    expect(screen.getByRole("heading", { level: 2, name: "Mapa de cuentas de cargos" })).toBeInTheDocument();
    expect(screen.getAllByRole("combobox")).toHaveLength(11);
    expect(screen.getByLabelText("Cuentas por cobrar a estudiantes")).toHaveValue(ASSET);
    expect(screen.getByLabelText("Ingreso por colegiatura")).toHaveValue("");
    expect(screen.getByRole("region", { name: "Cuentas principales" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Ingresos por categoría" })).toBeInTheDocument();
    expect(screen.getAllByText("Configurada")).toHaveLength(1);
    expect(screen.getByRole("region", { name: "Cuentas de pagos" })).toBeInTheDocument();
    expect(screen.getByLabelText("Caja (efectivo)")).toHaveValue("");
    expect(screen.getByLabelText("Banco (transferencias)")).toHaveValue("");
    expect(screen.getAllByText("Sin configurar")).toHaveLength(10);
  });

  it("explains that an incomplete map blocks the generation of charges and names what is missing", () => {
    renderWithAdminI18n(<PostingMapView {...props()} />);

    const banner = screen.getByText("El mapa de cuentas está incompleto").closest("div")?.parentElement as HTMLElement;
    expect(banner).toHaveTextContent("la generación de cargos queda bloqueada");
    expect(within(banner).getAllByRole("listitem").map((item) => item.textContent)).toEqual(["Cuentas por cobrar a estudiantes", "Impuesto por pagar", "Ingreso por defecto"]);
  });

  it("shows no banner when nothing is missing", () => {
    renderWithAdminI18n(<PostingMapView {...props({ missing: [] })} />);

    expect(screen.queryByText("El mapa de cuentas está incompleto")).not.toBeInTheDocument();
    expect(screen.getByText("Todas las cuentas principales están configuradas.")).toBeInTheDocument();
  });

  it("reports the choice of an account for a row, and says that a row with no account to choose has none", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const withNone = rows().map((row) => (row.id === "TAX_PAYABLE" ? { ...row, options: [] } : row));
    renderWithAdminI18n(<PostingMapView {...props({ onChange, rows: withNone })} />);

    await user.selectOptions(screen.getByLabelText("Cuentas por cobrar a estudiantes"), ASSET);

    expect(onChange).toHaveBeenCalledWith("RECEIVABLE", ASSET);
    expect(screen.getByLabelText("Impuesto por pagar")).toHaveAccessibleDescription(/No hay cuentas activas de este tipo/);
  });

  it("saves with the button once there is something to save, and discards with the other one", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    const onReset = vi.fn();
    const { rerender } = renderWithAdminI18n(<PostingMapView {...props({ onSave, onReset })} />);

    expect(screen.getByRole("button", { name: "Guardar mapa" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Guardar mapa" })).toHaveAccessibleDescription("No hay cambios por guardar.");

    rerender(<PostingMapView {...props({ onSave, onReset, pendingCount: 2 })} />);
    expect(screen.getByRole("button", { name: "Guardar mapa" })).toHaveAccessibleDescription("2 cambios sin guardar");
    await user.click(screen.getByRole("button", { name: "Guardar mapa" }));
    await user.click(screen.getByRole("button", { name: "Descartar cambios" }));

    expect(onSave).toHaveBeenCalledOnce();
    expect(onReset).toHaveBeenCalledOnce();
  });

  it("locks the form while it is being saved", () => {
    renderWithAdminI18n(<PostingMapView {...props({ pendingCount: 1, submitting: true })} />);

    expect(screen.getByLabelText("Cuentas por cobrar a estudiantes")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Descartar cambios" })).toBeDisabled();
    expect(screen.getAllByRole("status").map((status) => status.textContent)).toContain("Guardando el mapa de cuentas de cargos.");
  });

  it("is read only with an explanation: the selects are disabled and there is no save", () => {
    renderWithAdminI18n(<PostingMapView {...props({ readOnly: true })} />);

    expect(screen.getByText("Solo lectura")).toBeInTheDocument();
    expect(screen.getByText(/solo un Super Administrador lo hace/)).toBeInTheDocument();
    expect(screen.getByLabelText("Cuentas por cobrar a estudiantes")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Guardar mapa" })).not.toBeInTheDocument();
  });

  it("shows a skeleton and a status while it loads", () => {
    renderWithAdminI18n(<PostingMapView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByRole("status")).toHaveTextContent("Cargando el mapa de cuentas de cargos.");
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("shows the failure with a retry, never a technical dump", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<PostingMapView {...props({ state: { kind: "error", description: "No se pudo conectar con el servidor.", onRetry } })} />);

    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo cargar el mapa de cuentas de cargos");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("sends the person to the chart when there is no active account to choose", async () => {
    const user = userEvent.setup();
    const onGoToAccounts = vi.fn();
    renderWithAdminI18n(<PostingMapView {...props({ noAccounts: true, onGoToAccounts })} />);

    expect(screen.getByText("Todavía no hay cuentas activas")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ir al plan de cuentas" }));
    expect(onGoToAccounts).toHaveBeenCalledOnce();
  });

  it("tells the result of the last save in the live region, and the retry of the lists that failed", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<PostingMapView {...props({ notice: { tone: "success", message: "Se guardó el mapa de cuentas de cargos." }, lookupFailure: { onRetry } })} />);

    expect(screen.getByText("Se guardó el mapa de cuentas de cargos.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});

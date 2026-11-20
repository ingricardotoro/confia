import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { cashSessionRow } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { CashSessionsView, type CashSessionsViewProps } from "./CashSessionsView.tsx";

// States. AW57, AW58: the list of sessions rendered with props alone. Loading, empty and error are the state of the table, which carries each of them. Empty: the
// table says there are no sessions, with a way forward. Success: each session with its state and the amounts the server sent, a dash for what a session still
// open does not have yet, the filters, and a link per session that a click turns into a navigation of the router.
function props(override: Partial<CashSessionsViewProps> = {}): CashSessionsViewProps {
  return {
    filters: {
      status: { value: "", onChange: vi.fn() },
      from: { value: "", onChange: vi.fn() },
      to: { value: "", onChange: vi.fn() },
    },
    rows: [
      cashSessionRow({
        id: "00000000-0000-4000-8000-0000000c0002",
        status: "pending_approval",
        expectedAmount: { amount: "550.00", currency: "HNL" },
        declaredAmount: { amount: "540.00", currency: "HNL" },
        differenceAmount: { amount: "-10.00", currency: "HNL" },
      }),
      cashSessionRow({ id: "00000000-0000-4000-8000-0000000c0003" }),
    ],
    table: loadedTable,
    pagination: { ...onePage, total: 2 },
    hrefOf: (row) => `/cashbox/sessions/${row.id}`,
    onOpen: vi.fn(),
    ...override,
  };
}

describe("CashSessionsView", () => {
  it("AW57: shows each session with its state and amounts, and a dash for what an open session does not have yet", () => {
    renderWithAdminI18n(<CashSessionsView {...props()} />);

    const pending = within(screen.getAllByRole("row")[1] as HTMLElement);
    expect(pending.getByText("Pendiente de aprobación")).toBeInTheDocument();
    expect(pending.getAllByText(/550\.00/).length).toBeGreaterThan(0);
    expect(pending.getAllByText(/-.*10\.00/).length).toBeGreaterThan(0);
    expect(within(screen.getAllByRole("row")[2] as HTMLElement).getAllByText("—")).toHaveLength(3);
  });

  it("opens a session from its link: a plain click is a navigation of the router, and the address stays a real one", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    const view = props({ onOpen });
    renderWithAdminI18n(<CashSessionsView {...view} />);

    const link = within(screen.getAllByRole("row")[1] as HTMLElement).getByRole("link");
    expect(link).toHaveAttribute("href", `/cashbox/sessions/${view.rows[0]?.id ?? ""}`);
    await user.click(link);

    expect(onOpen).toHaveBeenCalledWith(view.rows[0]);
  });

  it("reports the filters the person changes", async () => {
    const user = userEvent.setup();
    const view = props();
    renderWithAdminI18n(<CashSessionsView {...view} />);

    await user.selectOptions(screen.getByRole("combobox", { name: "Estado" }), "Cerrada");
    await user.type(screen.getByLabelText("Abierta desde"), "2026-11-01");

    expect(view.filters.status.onChange).toHaveBeenCalledWith("closed");
    expect(view.filters.from.onChange).toHaveBeenCalled();
  });

  it("describes the error of the period under its field", () => {
    renderWithAdminI18n(<CashSessionsView {...props({ filters: { ...props().filters, to: { value: "2026-10-01", error: "La fecha final debe ser igual o posterior a la inicial.", onChange: vi.fn() } } })} />);

    expect(screen.getByLabelText("Abierta hasta")).toBeInvalid();
  });

  it("shows the empty state of the table with its way forward", async () => {
    const user = userEvent.setup();
    const onAction = vi.fn();
    renderWithAdminI18n(<CashSessionsView {...props({ rows: [], table: { ...loadedTable, empty: { title: "Todavía no hay sesiones de caja", action: { label: "Ir a Mi caja", onAction } } } })} />);

    expect(screen.getByText("Todavía no hay sesiones de caja")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ir a Mi caja" }));

    expect(onAction).toHaveBeenCalledOnce();
  });

  it("shows the loading and the error states of the table", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<CashSessionsView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table", { name: "Sesiones de caja" })).toHaveAttribute("aria-busy", "true");

    rerender(<CashSessionsView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudieron cargar las sesiones de caja", description: "Intente de nuevo.", onRetry } } })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledOnce();
  });
});

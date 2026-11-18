import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { integrityRun } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { IntegrityRunsView, type IntegrityRunsViewProps } from "./IntegrityRunsView.tsx";

// States. AW57, AW58: the runs of the integrity check rendered with props alone. Loading: the banner is a skeleton that announces the
// read and the table shows its own. Empty: the table says there are no runs yet, with a way forward. Error: the banner (a latest run that
// could not be read, with a retry) and the table (with its own). Success: a clean banner, a banner that warns with the count, or none.

function props(override: Partial<IntegrityRunsViewProps> = {}): IntegrityRunsViewProps {
  return {
    banner: { kind: "ok", finishedAt: "2026-11-18T08:30:41Z", onOpen: vi.fn() },
    rows: [integrityRun({ id: "00000000-0000-4000-8000-0000000f0001", chargesChecked: 30, postingsChecked: 31 })],
    table: loadedTable,
    pagination: onePage,
    hrefOf: (row) => `/charges/integrity/${row.id}`,
    onOpen: vi.fn(),
    ...override,
  };
}

describe("IntegrityRunsView", () => {
  it("shows each run with its result and counts, and its start as a real link a click turns into a navigation of the router", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    const run = integrityRun({ id: "00000000-0000-4000-8000-0000000f0001", chargesChecked: 30, postingsChecked: 31 });
    renderWithAdminI18n(<IntegrityRunsView {...props({ rows: [run], onOpen })} />);

    const row = within(screen.getAllByRole("row")[1] as HTMLElement);
    expect(row.getByText("30")).toBeInTheDocument();
    expect(row.getByText("31")).toBeInTheDocument();
    const link = row.getByRole("link");
    expect(link).toHaveAttribute("href", `/charges/integrity/${run.id}`);
    await user.click(link);

    expect(onOpen).toHaveBeenCalledWith(run);
  });

  it("says in the banner that the latest run found nothing, and opens it", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    renderWithAdminI18n(<IntegrityRunsView {...props({ banner: { kind: "ok", finishedAt: "2026-11-18T08:30:41Z", onOpen } })} />);

    expect(screen.getByText(/no encontró discrepancias/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver detalle" }));

    expect(onOpen).toHaveBeenCalledOnce();
  });

  it("warns in the banner with the count of discrepancies, as an alert", () => {
    renderWithAdminI18n(<IntegrityRunsView {...props({ banner: { kind: "discrepancies", finishedAt: "2026-11-18T08:30:41Z", count: 1, onOpen: vi.fn() } })} />);

    expect(screen.getByRole("alert")).toHaveTextContent("encontró 1 discrepancia. Revise el detalle.");
  });

  it("announces the read of the latest run while it loads", () => {
    renderWithAdminI18n(<IntegrityRunsView {...props({ banner: { kind: "loading" } })} />);

    expect(screen.getByRole("status")).toHaveTextContent("Leyendo la última comprobación.");
  });

  it("says the check has not run yet", () => {
    renderWithAdminI18n(<IntegrityRunsView {...props({ banner: { kind: "none" } })} />);

    expect(screen.getByText(/todavía no se ha ejecutado/)).toBeInTheDocument();
  });

  it("tells a latest run that could not be read and offers to read it again", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<IntegrityRunsView {...props({ banner: { kind: "failed", onRetry } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("shows the empty state of the table with its way forward when there are no runs", () => {
    const onAction = vi.fn();
    renderWithAdminI18n(<IntegrityRunsView {...props({ rows: [], banner: { kind: "none" }, table: { empty: { title: "Todavía no hay comprobaciones", action: { label: "Actualizar", onAction } } } })} />);

    expect(screen.getByText("Todavía no hay comprobaciones")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Actualizar" })).toBeInTheDocument();
  });

  it("shows the error state of the table with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<IntegrityRunsView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudieron cargar las comprobaciones", description: "x", onRetry } } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledOnce();
  });
});

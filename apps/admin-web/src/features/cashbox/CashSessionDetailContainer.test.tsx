import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { cashMovement, cashSession, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCashbox } from "../../test/render-cashbox.tsx";

// One session over the real route, the real query layer and the real transport: its report (summary, movements, close, approval), the explicit
// approval of a close that has a difference, every refusal of the server told in the dialog, and the states of the load.
installMswServer();

const SESSIONS = "/api/v1/cashbox/sessions";
const ID = "00000000-0000-4000-8000-0000000c0002";
const DETAIL = `${SESSIONS}/${ID}`;
const TIMEOUT = { timeout: 10_000 };

const CLOSE = {
  id: "00000000-0000-4000-8000-0000000c0aa1",
  closedAt: "2026-11-19T18:00:00Z",
  closedBy: "00000000-0000-4000-8000-0000000000a1",
  declaredAmount: { amount: "540.00", currency: "HNL" },
  expectedAmount: { amount: "550.00", currency: "HNL" },
  differenceAmount: { amount: "-10.00", currency: "HNL" },
  differenceReason: "Billete de cien contado dos veces por error",
};
const PENDING = cashSession({
  id: ID,
  status: "pending_approval",
  expectedAmount: { amount: "550.00", currency: "HNL" },
  close: CLOSE,
  movements: [cashMovement({ sessionId: ID, type: "withdrawal", amount: { amount: "50.00", currency: "HNL" }, notes: "Entrega a tesorería" })],
});

const approvals = () => requests().filter((sent) => sent.method === "POST" && sent.path === `${DETAIL}/approve`);

async function openPending(reply = PENDING) {
  route("get", DETAIL, reply);
  renderCashbox(`/cashbox/sessions/${ID}`);
  await screen.findByText("Efectivo contado", {}, TIMEOUT);
}

describe("the report of a session", () => {
  it("shows the summary with the close the server computed, the movements and the reason of the difference", async () => {
    await openPending();

    expect(screen.getByRole("heading", { level: 2, name: /Sesión de caja del/ })).toBeInTheDocument();
    expect(screen.getAllByText("Pendiente de aprobación").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/550\.00/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/540\.00/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/10\.00/).length).toBeGreaterThan(0);
    expect(screen.getByText("Billete de cien contado dos veces por error")).toBeInTheDocument();
    expect(screen.getByText("Entrega a tesorería")).toBeInTheDocument();
    expect(screen.getByText("Firma del cajero")).toBeInTheDocument();
    expect(screen.getByText("Firma de quien aprueba")).toBeInTheDocument();
  });

  it("offers to print the report", async () => {
    const user = userEvent.setup();
    const print = vi.spyOn(window, "print").mockImplementation(() => undefined);
    await openPending();

    await user.click(screen.getByRole("button", { name: "Imprimir reporte" }));

    expect(print).toHaveBeenCalledOnce();
    print.mockRestore();
  });

  it("offers no approval for a session that does not await one", async () => {
    await openPending(cashSession({ id: ID, status: "closed", close: { ...CLOSE, differenceAmount: { amount: "0.00", currency: "HNL" }, differenceReason: undefined } }));

    expect(screen.queryByRole("button", { name: "Aprobar cierre" })).not.toBeInTheDocument();
  });
});

describe("approving a close", () => {
  it("restates what is approved, requires a note with nothing sent without it, and then approves with its key and reads the session again", async () => {
    const user = userEvent.setup();
    await openPending();
    route("get", DETAIL, cashSession({ id: ID, status: "closed", close: CLOSE, approval: { id: "00000000-0000-4000-8000-0000000c0bb1", approvedAt: "2026-11-19T19:00:00Z", approvedBy: "00000000-0000-4000-8000-0000000000b2", note: "Revisado con el cajero" } }));
    route("post", `${DETAIL}/approve`, cashSession({ id: ID, status: "closed", close: CLOSE }));

    await user.click(screen.getByRole("button", { name: "Aprobar cierre" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getAllByText(/10\.00/).length).toBeGreaterThan(0);
    await user.click(within(dialog).getByRole("button", { name: "Aprobar cierre" }));
    expect((await within(dialog).findAllByText("Este dato es obligatorio.")).length).toBeGreaterThan(0);
    expect(approvals()).toHaveLength(0);
    await user.type(within(dialog).getByRole("textbox", { name: /Nota de la aprobación/ }), "Revisado con el cajero");
    await user.click(within(dialog).getByRole("button", { name: "Aprobar cierre" }));

    expect(await screen.findByText("El cierre quedó aprobado y la sesión está cerrada.", {}, TIMEOUT)).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(screen.queryByRole("button", { name: "Aprobar cierre" })).not.toBeInTheDocument();
    expect(approvals()).toHaveLength(1);
    expect(JSON.parse(approvals()[0]?.body ?? "{}")).toEqual({ note: "Revisado con el cajero" });
    expect(approvals()[0]?.headers.get("Idempotency-Key")).toBeTruthy();
  });

  it.each([
    ["cash-approval-same-actor", 403, /Quien cerró la caja no puede aprobar su propio cierre/],
    ["forbidden", 403, /No tiene permiso para realizar esta acción/],
    ["cash-session-not-awaiting-approval", 409, /no está pendiente de aprobación/],
  ] as const)("tells the %s refusal in the dialog and keeps the note", async (code, status, text) => {
    const user = userEvent.setup();
    await openPending();
    route("post", `${DETAIL}/approve`, problem(code, status));

    await user.click(screen.getByRole("button", { name: "Aprobar cierre" }));
    const dialog = await screen.findByRole("dialog");
    const note = within(dialog).getByRole("textbox", { name: /Nota de la aprobación/ });
    await user.type(note, "Revisado");
    await user.click(within(dialog).getByRole("button", { name: "Aprobar cierre" }));

    expect(await within(dialog).findByText(text, {}, TIMEOUT)).toBeInTheDocument();
    expect(note).toHaveValue("Revisado");
  });
});

describe("the states of the load", () => {
  it("shows a skeleton that announces the read while it is in flight", async () => {
    route("get", DETAIL, PENDING);
    renderCashbox(`/cashbox/sessions/${ID}`);

    expect(await screen.findByText("Cargando la sesión de caja.")).toBeInTheDocument();
  });

  it("shows the error state with the trace id, and Retry loads the session", async () => {
    const user = userEvent.setup();
    route("get", DETAIL, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), PENDING);
    renderCashbox(`/cashbox/sessions/${ID}`);

    expect(await screen.findByText("No se pudo cargar la sesión de caja", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByText("Motivo de la diferencia", {}, TIMEOUT)).toBeInTheDocument();
  });

  it.each([
    [404, "resource-not-found", "No encontramos esta sesión de caja"],
    [403, "forbidden", "No tiene acceso a las sesiones de caja"],
  ] as const)("tells the %i answer in plain words and offers no retry", async (status, code, title) => {
    route("get", DETAIL, problem(code, status));
    renderCashbox(`/cashbox/sessions/${ID}`);

    expect(await screen.findByText(title, {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });
});

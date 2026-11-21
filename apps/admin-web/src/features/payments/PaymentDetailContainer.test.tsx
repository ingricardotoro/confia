import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { paymentRecord, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderPayments } from "../../test/render-payments.tsx";

// One payment over the real route, the real query layer and the real transport: what the server says of it, the allocations of a confirmed one, the
// decision of a declared transfer (confirm and reject, each with its Idempotency-Key and its refusals), and every state of the load.
installMswServer();

const ID = "00000000-0000-4000-8000-0000000d0001";
const PAYMENT = `/api/v1/payments/${ID}`;
const TIMEOUT = { timeout: 10_000 };
const REASON = "La referencia no aparece en el estado del banco";
const declared = (override = {}) =>
  paymentRecord({
    id: ID,
    method: "BANK_TRANSFER",
    status: "declared",
    reference: "AB1234",
    bankName: "Banco Atlántida",
    allocated: { amount: "0.00", currency: "HNL" },
    allocations: [],
    ...override,
  });

const posts = (suffix: string) => requests().filter((sent) => sent.method === "POST" && sent.path === `${PAYMENT}/${suffix}`);
const keyOf = (sent: { headers: Headers } | undefined) => sent?.headers.get("Idempotency-Key") ?? "";

async function open(first = declared(), ...next: ReturnType<typeof declared>[]) {
  route("get", PAYMENT, first, ...next);
  renderPayments(`/payments/${ID}`);
  await screen.findByRole("heading", { level: 2, name: /Pago del/ }, TIMEOUT);
  await screen.findByText("Referencia", {}, TIMEOUT);
}

describe("a confirmed payment", () => {
  it("shows its data and the allocations and what stays in favor, as the server sent them, and offers no decision", async () => {
    route("get", PAYMENT, paymentRecord({ id: ID, unallocated: { amount: "235.00", currency: "HNL" } }));
    renderPayments(`/payments/${ID}`);

    expect(await screen.findByRole("table", { name: /Cargos a los que se aplicó el pago/ }, TIMEOUT)).toBeInTheDocument();
    expect(screen.getAllByText(/235\.00/).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Confirmado").length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: "Confirmar" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Rechazar" })).not.toBeInTheDocument();
  });
});

describe("a declared transfer", () => {
  it("says it changed nothing yet, shows no allocations and offers to confirm and reject it", async () => {
    await open();

    expect(screen.getByText(/Esta transferencia está pendiente de confirmación/)).toBeInTheDocument();
    expect(screen.getByText("AB1234")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: /Cargos a los que se aplicó el pago/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirmar" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Rechazar" })).toBeInTheDocument();
  });

  it("confirms with an explicit dialog, one request with its Idempotency-Key, and shows the payment the server then reads", async () => {
    const user = userEvent.setup();
    await open(declared(), paymentRecord({ id: ID }));
    route("post", `${PAYMENT}/confirm`, paymentRecord({ id: ID }));
    await user.click(screen.getByRole("button", { name: "Confirmar" }));
    const dialog = await screen.findByRole("dialog", { name: "Confirmar la transferencia" }, TIMEOUT);
    expect(within(dialog).getByText("AB1234")).toBeInTheDocument();
    await user.dblClick(within(dialog).getByRole("button", { name: "Confirmar transferencia" }));

    expect(await screen.findByText("La transferencia quedó confirmada y aplicada a los cargos.", {}, TIMEOUT)).toBeInTheDocument();
    expect(posts("confirm")).toHaveLength(1);
    expect(keyOf(posts("confirm")[0])).not.toBe("");
    expect(await screen.findByRole("table", { name: /Cargos a los que se aplicó el pago/ }, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Confirmar" })).not.toBeInTheDocument();
  });

  it.each([
    ["self-approval-forbidden", 409, /Quien registró el pago no puede confirmarlo ni rechazarlo/],
    ["payment-not-declared", 409, /Este pago ya no está pendiente de confirmación/],
    ["posting-accounts-incomplete", 409, /Faltan las cuentas de caja o de banco/],
    ["forbidden", 403, /No tiene permiso para realizar esta acción/],
  ] as const)("tells the %s refusal of a confirmation inside the dialog and keeps it open", async (code, status, text) => {
    const user = userEvent.setup();
    await open();
    route("post", `${PAYMENT}/confirm`, problem(code, status, { traceId: "01J8Z4K2QF9P0X" }));
    await user.click(screen.getByRole("button", { name: "Confirmar" }));
    const dialog = await screen.findByRole("dialog", { name: "Confirmar la transferencia" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Confirmar transferencia" }));

    expect(await within(dialog).findByText(text, {}, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Confirmar la transferencia" })).toBeInTheDocument();
  });

  it("refuses a reason that is too short with nothing sent, and rejects with the reason and its Idempotency-Key", async () => {
    const user = userEvent.setup();
    await open(declared(), declared({ status: "rejected", decision: { id: "00000000-0000-4000-8000-0000000f0001", kind: "rejected", reason: REASON, decidedAt: "2026-11-20T15:00:00Z", decidedBy: "00000000-0000-4000-8000-0000000000a2" } }));
    route("post", `${PAYMENT}/reject`, declared({ status: "rejected" }));
    await user.click(screen.getByRole("button", { name: "Rechazar" }));
    const dialog = await screen.findByRole("dialog", { name: "Rechazar la transferencia" }, TIMEOUT);
    const reason = within(dialog).getByRole("textbox", { name: /Motivo del rechazo/ });
    await user.click(reason);
    await user.paste("Muy corto");
    await user.click(within(dialog).getByRole("button", { name: "Rechazar transferencia" }));

    expect((await within(dialog).findAllByText("Escriba de 20 a 500 caracteres.")).length).toBeGreaterThan(0);
    expect(posts("reject")).toHaveLength(0);

    await user.clear(reason);
    await user.click(reason);
    await user.paste(REASON);
    await user.click(within(dialog).getByRole("button", { name: "Rechazar transferencia" }));

    expect(await screen.findByText("La transferencia quedó rechazada. El saldo del estudiante no cambió.", {}, TIMEOUT)).toBeInTheDocument();
    expect(posts("reject")).toHaveLength(1);
    expect(JSON.parse(posts("reject")[0]?.body ?? "{}")).toEqual({ reason: REASON });
    expect(keyOf(posts("reject")[0])).not.toBe("");
    expect(await screen.findByText(/Esta transferencia fue rechazada/, {}, TIMEOUT)).toBeInTheDocument();
  });

  it("sends the same Idempotency-Key again when the answer never arrived", async () => {
    const user = userEvent.setup();
    await open();
    route("post", `${PAYMENT}/confirm`, "network-error", problem("self-approval-forbidden", 409));
    await user.click(screen.getByRole("button", { name: "Confirmar" }));
    const dialog = await screen.findByRole("dialog", { name: "Confirmar la transferencia" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Confirmar transferencia" }));
    await within(dialog).findByText(/No se pudo conectar con el servidor/, {}, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Confirmar transferencia" }));
    await waitFor(() => {
      expect(posts("confirm")).toHaveLength(2);
    }, TIMEOUT);

    expect(keyOf(posts("confirm")[1])).toBe(keyOf(posts("confirm")[0]));
  });
});

describe("the states of the load", () => {
  it("tells a payment that is not there or not the person, with a way back", async () => {
    route("get", PAYMENT, problem("resource-not-found", 404));
    renderPayments(`/payments/${ID}`);

    expect(await screen.findByText("No encontramos este pago", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Volver a los pagos/ })).toBeInTheDocument();
  });

  it("tells a missing permission plainly", async () => {
    route("get", PAYMENT, problem("forbidden", 403));
    renderPayments(`/payments/${ID}`);

    expect(await screen.findByText("No tiene acceso a los pagos", {}, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the error state with a retry that loads the payment", async () => {
    const user = userEvent.setup();
    route("get", PAYMENT, problem("internal-error", 500), paymentRecord({ id: ID }));
    renderPayments(`/payments/${ID}`);

    expect(await screen.findByText("No se pudo cargar el pago", {}, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("heading", { level: 2, name: /Pago del/ }, TIMEOUT)).toBeInTheDocument();
  });
});

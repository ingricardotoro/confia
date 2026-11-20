import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { cashMovement, cashSession, problem, recordedMovement } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCashbox } from "../../test/render-cashbox.tsx";

// The cashier's own cashbox over the real route, the real query layer and the real transport: the form that opens a session, the open session
// with what the server says of it, the movements, the close with the difference the server computes, and every state of the load. The
// browser adds nothing up: each figure on screen is a figure the test server sent.
installMswServer();

const SESSIONS = "/api/v1/cashbox/sessions";
const CURRENT = `${SESSIONS}/current`;
const ID = "00000000-0000-4000-8000-0000000c0001";
const TIMEOUT = { timeout: 10_000 };

const posts = (suffix = "") => requests().filter((sent) => sent.method === "POST" && sent.path === `${SESSIONS}${suffix}`);
const keyOf = (sent: { headers: Headers } | undefined) => sent?.headers.get("Idempotency-Key") ?? "";

type Reply = ReturnType<typeof cashSession>;

async function openWithSession(first: Reply = cashSession({ id: ID }), ...next: Reply[]) {
  route("get", CURRENT, first, ...next);
  renderCashbox("/cashbox");
  await screen.findByRole("heading", { level: 2, name: "Mi caja" }, TIMEOUT);
  await screen.findByText("Efectivo esperado", { exact: false }, TIMEOUT);
}

describe("opening the session", () => {
  it("offers the form when the server says there is no open session, sends the float with its own Idempotency-Key, and shows the session the server then reads", async () => {
    const user = userEvent.setup();
    route("get", CURRENT, problem("resource-not-found", 404), cashSession({ id: ID }));
    route("post", SESSIONS, cashSession({ id: ID }, 201));
    renderCashbox("/cashbox");

    const float = await screen.findByRole("textbox", { name: /Fondo inicial/ }, TIMEOUT);
    await user.type(float, "500.00");
    await user.click(screen.getByRole("button", { name: "Abrir caja" }));

    expect(await screen.findByText("Su caja está abierta.", {}, TIMEOUT)).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Cerrar caja" }, TIMEOUT)).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(JSON.parse(posts()[0]?.body ?? "{}")).toEqual({ openingFloat: { amount: "500.00", currency: "HNL" } });
    expect(keyOf(posts()[0])).not.toBe("");
  });

  it("refuses an amount that does not hold with nothing sent, and says why under the field", async () => {
    const user = userEvent.setup();
    route("get", CURRENT, problem("resource-not-found", 404));
    renderCashbox("/cashbox");

    await user.type(await screen.findByRole("textbox", { name: /Fondo inicial/ }, TIMEOUT), "12.345");
    await user.click(screen.getByRole("button", { name: "Abrir caja" }));

    expect((await screen.findAllByText(/hasta dos decimales/)).length).toBeGreaterThan(0);
    expect(posts()).toHaveLength(0);
  });

  it("tells a second open session in words and keeps what was typed", async () => {
    const user = userEvent.setup();
    route("get", CURRENT, problem("resource-not-found", 404));
    route("post", SESSIONS, problem("cash-session-already-open", 409));
    renderCashbox("/cashbox");

    const float = await screen.findByRole("textbox", { name: /Fondo inicial/ }, TIMEOUT);
    await user.type(float, "200");
    await user.click(screen.getByRole("button", { name: "Abrir caja" }));

    expect(await screen.findByText(/Ya tiene una caja abierta/, {}, TIMEOUT)).toBeInTheDocument();
    expect(float).toHaveValue("200");
  });

  it("sends the same Idempotency-Key again when the answer never arrived, and another when the float changed", async () => {
    const user = userEvent.setup();
    route("get", CURRENT, problem("resource-not-found", 404));
    route("post", SESSIONS, "network-error", problem("cash-session-already-open", 409), problem("cash-session-already-open", 409));
    renderCashbox("/cashbox");

    const float = await screen.findByRole("textbox", { name: /Fondo inicial/ }, TIMEOUT);
    await user.type(float, "300");
    await user.click(screen.getByRole("button", { name: "Abrir caja" }));
    await screen.findByText(/No se pudo conectar con el servidor/, {}, TIMEOUT);
    await user.click(screen.getByRole("button", { name: "Abrir caja" }));
    await screen.findByText(/Ya tiene una caja abierta/, {}, TIMEOUT);
    await user.type(float, "5");
    await user.click(screen.getByRole("button", { name: "Abrir caja" }));
    await waitFor(() => {
      expect(posts()).toHaveLength(3);
    }, TIMEOUT);

    const [first, second, third] = posts();
    expect(keyOf(second)).toBe(keyOf(first));
    expect(keyOf(third)).not.toBe(keyOf(first));
  });
});

describe("the open session", () => {
  it("shows the expected cash and the movements as the server sent them, each with its type", async () => {
    await openWithSession(
      cashSession({
        id: ID,
        expectedAmount: { amount: "550.00", currency: "HNL" },
        movements: [
          cashMovement({ type: "deposit", amount: { amount: "100.00", currency: "HNL" }, notes: "Cambio de la mañana" }),
          cashMovement({ type: "withdrawal", amount: { amount: "50.00", currency: "HNL" } }),
        ],
      }),
    );

    expect(screen.getAllByText(/550\.00/).length).toBeGreaterThan(0);
    const table = screen.getByRole("table", { name: "Movimientos de la caja", hidden: true });
    expect(within(table).getByText("Depósito")).toBeInTheDocument();
    expect(within(table).getByText("Retiro")).toBeInTheDocument();
    expect(within(table).getByText("Cambio de la mañana")).toBeInTheDocument();
    expect(within(table).getByText("Sin notas")).toBeInTheDocument();
  });

  it("shows the empty state of the movements with a way forward", async () => {
    await openWithSession(cashSession({ id: ID }));

    expect(screen.getByText("Todavía no hay movimientos")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Registrar depósito" }).length).toBeGreaterThan(1);
  });

  it("registers a deposit with its key, reads the session again and shows the expected cash the server now says", async () => {
    const user = userEvent.setup();
    await openWithSession(
      cashSession({ id: ID, expectedAmount: { amount: "500.00", currency: "HNL" } }),
      cashSession({ id: ID, expectedAmount: { amount: "600.00", currency: "HNL" }, movements: [cashMovement({ sessionId: ID })] }),
    );
    route("post", `${SESSIONS}/${ID}/movements`, recordedMovement({ sessionId: ID }, "600.00"));

    await user.click(screen.getAllByRole("button", { name: "Registrar depósito" })[0] as HTMLElement);
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByRole("textbox", { name: /Importe/ }), "100.00");
    await user.click(within(dialog).getByRole("button", { name: "Registrar depósito" }));

    expect(await screen.findByText("Se registró el depósito.", {}, TIMEOUT)).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(screen.getAllByText(/600\.00/).length).toBeGreaterThan(0);
    const sent = posts(`/${ID}/movements`);
    expect(sent).toHaveLength(1);
    expect(JSON.parse(sent[0]?.body ?? "{}")).toEqual({ type: "deposit", amount: { amount: "100.00", currency: "HNL" } });
    expect(keyOf(sent[0])).not.toBe("");
  });

  it("requires the note of an adjustment before sending anything", async () => {
    const user = userEvent.setup();
    await openWithSession();

    await user.click(screen.getByRole("button", { name: "Registrar ajuste" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByRole("textbox", { name: /Importe/ }), "10");
    await user.click(within(dialog).getByRole("button", { name: "Registrar ajuste" }));

    expect((await within(dialog).findAllByText("Este dato es obligatorio.")).length).toBeGreaterThan(0);
    expect(posts(`/${ID}/movements`)).toHaveLength(0);
  });

  it("tells a withdrawal the expected cash cannot cover, keeps the dialog open and what was typed", async () => {
    const user = userEvent.setup();
    await openWithSession();
    route("post", `${SESSIONS}/${ID}/movements`, problem("cash-expected-negative", 409));

    await user.click(screen.getByRole("button", { name: "Registrar retiro" }));
    const dialog = await screen.findByRole("dialog");
    const amount = within(dialog).getByRole("textbox", { name: /Importe/ });
    await user.type(amount, "9999.00");
    await user.click(within(dialog).getByRole("button", { name: "Registrar retiro" }));

    expect(await within(dialog).findByText(/así que no se registró/, {}, TIMEOUT)).toBeInTheDocument();
    expect(amount).toHaveValue("9999.00");
  });
});

describe("closing the session", () => {
  async function openClose(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole("button", { name: "Cerrar caja" }));
    return screen.findByRole("dialog");
  }

  it("shows the expected cash beside the count and no difference, because the browser computes none", async () => {
    const user = userEvent.setup();
    await openWithSession(cashSession({ id: ID, expectedAmount: { amount: "550.00", currency: "HNL" } }));

    const dialog = await openClose(user);
    await user.type(within(dialog).getByRole("textbox", { name: /Efectivo contado/ }), "540.00");

    expect(within(dialog).getAllByText(/550\.00/).length).toBeGreaterThan(0);
    expect(within(dialog).queryByText(/Diferencia/)).not.toBeInTheDocument();
  });

  it("shows under the reason field the refusal of the server when a difference has no reason, then closes with it and shows the difference the server computed", async () => {
    const user = userEvent.setup();
    await openWithSession(cashSession({ id: ID, expectedAmount: { amount: "550.00", currency: "HNL" } }));
    const closed = {
      id: ID,
      status: "pending_approval" as const,
      expectedAmount: { amount: "550.00", currency: "HNL" },
      close: {
        id: "00000000-0000-4000-8000-0000000c0aa1",
        closedAt: "2026-11-19T18:00:00Z",
        closedBy: "00000000-0000-4000-8000-0000000000a1",
        declaredAmount: { amount: "540.00", currency: "HNL" },
        expectedAmount: { amount: "550.00", currency: "HNL" },
        differenceAmount: { amount: "-10.00", currency: "HNL" },
        differenceReason: "Billete de cien contado dos veces por error",
      },
    };
    route("post", `${SESSIONS}/${ID}/close`, problem("cash-difference-reason-required", 400), cashSession(closed));
    route("get", CURRENT, problem("resource-not-found", 404));

    const dialog = await openClose(user);
    await user.type(within(dialog).getByRole("textbox", { name: /Efectivo contado/ }), "540.00");
    await user.click(within(dialog).getByRole("button", { name: "Cerrar caja" }));

    const reason = within(dialog).getByRole("textbox", { name: /Motivo de la diferencia/ });
    expect((await within(dialog).findAllByText(/escriba el motivo de la diferencia/)).length).toBeGreaterThan(0);
    await waitFor(() => {
      expect(reason).toHaveAccessibleDescription(/escriba el motivo de la diferencia/);
    });
    await user.type(reason, "Billete de cien contado dos veces por error");
    await user.click(within(dialog).getByRole("button", { name: "Cerrar caja" }));

    expect(await screen.findByText(/La caja se cerró con diferencia/, {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getAllByText(/10\.00/).length).toBeGreaterThan(0);
    expect(screen.getByText("Pendiente de aprobación")).toBeInTheDocument();
    const [first, second] = posts(`/${ID}/close`);
    expect(JSON.parse(first?.body ?? "{}")).toEqual({ declaredAmount: { amount: "540.00", currency: "HNL" } });
    expect(JSON.parse(second?.body ?? "{}")).toMatchObject({ differenceReason: "Billete de cien contado dos veces por error" });
    // Another body is another request: it has its own key.
    expect(keyOf(second)).not.toBe(keyOf(first));
    expect(screen.getByRole("textbox", { name: /Fondo inicial/ })).toBeInTheDocument();
  });
});

describe("the states of the load", () => {
  it("shows a skeleton that announces the read while it is in flight", async () => {
    route("get", CURRENT, cashSession({ id: ID }));
    renderCashbox("/cashbox");

    expect(await screen.findByText("Cargando su caja.")).toBeInTheDocument();
  });

  it("shows the error state with the trace id, and Retry loads the session", async () => {
    const user = userEvent.setup();
    route("get", CURRENT, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), cashSession({ id: ID }));
    renderCashbox("/cashbox");

    expect(await screen.findByText("No se pudo cargar su caja", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("button", { name: "Cerrar caja" }, TIMEOUT)).toBeInTheDocument();
  });

  it("tells a missing permission in plain words and offers no retry", async () => {
    route("get", CURRENT, problem("forbidden", 403));
    renderCashbox("/cashbox");

    expect(await screen.findByText("No tiene acceso a la caja", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });
});

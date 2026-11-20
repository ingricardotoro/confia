import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { cashSessionPage, cashSessionRow, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCashbox } from "../../test/render-cashbox.tsx";

// The list of sessions over the real route, the real query layer and the real transport: the page the server pages, filters and orders (the state of
// the table lives in the address), the amounts as the server sent them, and the states of the load.
installMswServer();

const SESSIONS = "/api/v1/cashbox/sessions";
const TIMEOUT = { timeout: 10_000 };
const PENDING = cashSessionRow({
  id: "00000000-0000-4000-8000-0000000c0002",
  status: "pending_approval",
  expectedAmount: { amount: "550.00", currency: "HNL" },
  declaredAmount: { amount: "540.00", currency: "HNL" },
  differenceAmount: { amount: "-10.00", currency: "HNL" },
});
const OPEN = cashSessionRow({ id: "00000000-0000-4000-8000-0000000c0003" });

const listGets = () => requests().filter((sent) => sent.method === "GET" && sent.path === SESSIONS);
const lastQuery = () => new URLSearchParams(listGets().at(-1)?.search);

async function openList(rows: unknown[] = [PENDING, OPEN], path = "/cashbox/sessions") {
  route("get", SESSIONS, cashSessionPage(rows));
  renderCashbox(path);
  const table = await screen.findByRole("table", { name: "Sesiones de caja" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
}

describe("the sessions of the cashbox", () => {
  it("shows each session with its state and the amounts the server sent, and none for a session that is still open", async () => {
    await openList();

    expect(screen.getByRole("heading", { level: 2, name: "Sesiones de caja" })).toBeInTheDocument();
    const pending = within(screen.getAllByRole("row")[1] as HTMLElement);
    expect(pending.getByText("Pendiente de aprobación")).toBeInTheDocument();
    expect(pending.getAllByText(/550\.00/).length).toBeGreaterThan(0);
    expect(pending.getAllByText(/10\.00/).length).toBeGreaterThan(0);
    const open = within(screen.getAllByRole("row")[2] as HTMLElement);
    expect(open.getByText("Abierta")).toBeInTheDocument();
    expect(open.getAllByText("—")).toHaveLength(3);
    expect(lastQuery().get("page")).toBe("0");
  });

  it("asks the server for the status the person chooses, from the first page", async () => {
    const user = userEvent.setup();
    await openList();

    await user.selectOptions(screen.getByRole("combobox", { name: "Estado" }), "Pendiente de aprobación");

    await waitFor(() => {
      expect(lastQuery().get("status")).toBe("pending_approval");
    }, TIMEOUT);
    expect(lastQuery().get("page")).toBe("0");
  });

  it("reads the filters, the order and the page from the address", async () => {
    await openList([OPEN], "/cashbox/sessions?status=closed&from=2026-11-01&to=2026-11-30&sort=status:asc&page=2");

    const query = lastQuery();
    expect(query.get("status")).toBe("closed");
    expect(query.get("from")).toBe("2026-11-01");
    expect(query.get("to")).toBe("2026-11-30");
    expect(query.get("sort")).toBe("status,asc");
    expect(query.get("page")).toBe("1");
  });

  it("asks the server for another order when a sortable header is pressed", async () => {
    const user = userEvent.setup();
    await openList();

    await user.click(screen.getByRole("button", { name: /Estado/ }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("status,asc");
    }, TIMEOUT);
  });

  it("opens the detail of a session from its link", async () => {
    const user = userEvent.setup();
    await openList();
    route("get", `${SESSIONS}/${PENDING.id}`, { status: 404, body: undefined, contentType: undefined });

    await user.click(within(screen.getAllByRole("row")[1] as HTMLElement).getByRole("link"));

    await waitFor(() => {
      expect(requests().some((sent) => sent.path === `${SESSIONS}/${PENDING.id}`)).toBe(true);
    }, TIMEOUT);
  });

  it("shows the empty state with its way forward when there are no sessions", async () => {
    route("get", SESSIONS, cashSessionPage([]));
    renderCashbox("/cashbox/sessions");

    expect(await screen.findByText("Todavía no hay sesiones de caja", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ir a Mi caja" })).toBeInTheDocument();
  });

  it("shows the error state with the trace id, and Retry loads the list", async () => {
    const user = userEvent.setup();
    route("get", SESSIONS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), cashSessionPage([OPEN]));
    renderCashbox("/cashbox/sessions");

    expect(await screen.findByText("No se pudieron cargar las sesiones de caja", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect((await screen.findAllByText("Abierta", {}, TIMEOUT)).length).toBeGreaterThan(1);
  });
});

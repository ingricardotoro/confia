import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { paymentPage, paymentRecord, paymentSummary, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderPayments } from "../../test/render-payments.tsx";

// The payments list over the real route, the real query layer and the real transport: the page the server serves (paged, filtered and ordered
// there, with all of it in the address), the status and method in words, the link to the detail, and every state of the load.
installMswServer();

const PAYMENTS = "/api/v1/payments";
const TIMEOUT = { timeout: 10_000 };
const DECLARED = paymentSummary({
  id: "00000000-0000-4000-8000-0000000d0001",
  method: "BANK_TRANSFER",
  status: "declared",
  reference: "AB1234",
  amount: { amount: "1265.00", currency: "HNL" },
  receivedOn: "2026-11-19",
});
const CASH = paymentSummary({ id: "00000000-0000-4000-8000-0000000d0002", amount: { amount: "1500.00", currency: "HNL" }, receivedOn: "2026-11-20" });

const listGets = () => requests().filter((sent) => sent.method === "GET" && sent.path === PAYMENTS);
const lastQuery = () => new URLSearchParams(listGets().at(-1)?.search);

async function openList(rows: unknown[] = [DECLARED, CASH], path = "/payments") {
  route("get", PAYMENTS, paymentPage(rows));
  const view = renderPayments(path);
  const table = await screen.findByRole("table", { name: "Pagos registrados" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return view;
}

describe("the payments list", () => {
  it("shows each payment with its method, state and amount as the server sent them", async () => {
    await openList();

    expect(screen.getByRole("heading", { level: 2, name: "Pagos" })).toBeInTheDocument();
    const declared = within(screen.getByRole("row", { name: /AB1234/ }));
    expect(declared.getByText("Transferencia")).toBeInTheDocument();
    expect(declared.getByText("Pendiente de confirmación")).toBeInTheDocument();
    expect(declared.getAllByText(/1,265\.00/).length).toBeGreaterThan(0);
    const cash = within(screen.getAllByRole("row")[2] as HTMLElement);
    expect(cash.getByText("Efectivo")).toBeInTheDocument();
    expect(cash.getByText("Confirmado")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().has("status")).toBe(false);
  });

  it("asks the server for the status and the method the person chooses, from the first page, and writes them in the address", async () => {
    const user = userEvent.setup();
    const view = await openList();

    await user.selectOptions(screen.getByRole("combobox", { name: "Estado" }), "Pendiente de confirmación");
    await waitFor(() => {
      expect(lastQuery().get("status")).toBe("declared");
    }, TIMEOUT);
    await user.selectOptions(screen.getByRole("combobox", { name: "Método" }), "Transferencia");

    await waitFor(() => {
      expect(lastQuery().get("method")).toBe("BANK_TRANSFER");
    }, TIMEOUT);
    expect(lastQuery().get("page")).toBe("0");
    expect(view.router.state.location.searchStr).toContain("status=declared");
    expect(view.router.state.location.searchStr).toContain("method=BANK_TRANSFER");
  });

  it("reads the filters, the dates, the order and the page from the address", async () => {
    await openList([CASH], "/payments?status=confirmed&method=CASH&from=2026-11-01&to=2026-11-30&sort=amount:desc&page=2");

    const query = lastQuery();
    expect(query.get("status")).toBe("confirmed");
    expect(query.get("method")).toBe("CASH");
    expect(query.get("from")).toBe("2026-11-01");
    expect(query.get("to")).toBe("2026-11-30");
    expect(query.get("sort")).toBe("amount,desc");
    expect(query.get("page")).toBe("1");
  });

  it("asks the server for another order when a sortable header is pressed", async () => {
    const user = userEvent.setup();
    await openList();

    await user.click(screen.getByRole("button", { name: /Importe/ }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("amount,asc");
    }, TIMEOUT);
  });

  it("opens the detail of a payment from its link", async () => {
    const user = userEvent.setup();
    await openList();
    route("get", `${PAYMENTS}/${DECLARED.id}`, paymentRecord({ id: DECLARED.id, method: "BANK_TRANSFER", status: "declared" }));

    await user.click(within(screen.getByRole("row", { name: /AB1234/ })).getByRole("link"));

    expect(await screen.findByRole("heading", { level: 2, name: /Pago del/ }, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the empty state with its way forward when there are no payments", async () => {
    route("get", PAYMENTS, paymentPage([]));
    renderPayments("/payments");

    expect(await screen.findByText("Todavía no hay pagos", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Registrar un pago" })).toBeInTheDocument();
  });

  it("shows the error state with the trace id, and Retry loads the list", async () => {
    const user = userEvent.setup();
    route("get", PAYMENTS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), paymentPage([CASH]));
    renderPayments("/payments");

    expect(await screen.findByText("No se pudieron cargar los pagos", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect((await screen.findAllByText("Confirmado", {}, TIMEOUT)).length).toBeGreaterThan(0);
  });
});

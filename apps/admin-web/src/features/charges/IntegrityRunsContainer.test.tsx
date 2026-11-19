import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { integrityRun, integrityRunDetail, integrityRunPage, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCharges } from "../../test/render-charges.tsx";

// The nightly integrity check over the real route, the real query layer and the real transport: the banner of the latest run, the
// list the server pages (newest first), one run with its discrepancies, and the states of the load.
installMswServer();

const RUNS = "/api/v1/charges/integrity-runs";
const TIMEOUT = { timeout: 10_000 };
const CLEAN = integrityRun({ id: "00000000-0000-4000-8000-0000000f0001", startedAt: "2026-11-17T08:30:00Z", finishedAt: "2026-11-17T08:30:41Z" });
const DIRTY = integrityRun({
  id: "00000000-0000-4000-8000-0000000f0002",
  status: "DISCREPANCIES",
  discrepancyCount: 2,
  startedAt: "2026-11-18T08:30:00Z",
  finishedAt: "2026-11-18T08:30:41Z",
});
const FINDINGS = [
  {
    kind: "CHARGE_POSTING_MISSING",
    chargeId: "00000000-0000-4000-8000-0000000d0001",
    expected: { amount: "1150.00", currency: "HNL" },
    actual: { amount: "0.00", currency: "HNL" },
  },
  { kind: "RECEIVABLE_MISMATCH", expected: { amount: "3450.00", currency: "HNL" }, actual: { amount: "2300.00", currency: "HNL" } },
];

const listGets = () => requests().filter((sent) => sent.method === "GET" && sent.path === RUNS);

async function openList(latest = integrityRunDetail(CLEAN), rows: unknown[] = [DIRTY, CLEAN]) {
  route("get", `${RUNS}/latest`, latest);
  route("get", RUNS, integrityRunPage(rows));
  renderCharges("/charges/integrity");
  const table = await screen.findByRole("table", { name: "Comprobaciones de integridad" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
}

describe("the nightly runs of the integrity check", () => {
  it("shows each run with its result and counts, and a calm banner when the latest run found nothing", async () => {
    await openList();

    expect(screen.getByRole("heading", { level: 2, name: "Integridad de cargos" })).toBeInTheDocument();
    expect(await screen.findByText(/no encontró discrepancias/, {}, TIMEOUT)).toBeInTheDocument();
    const dirty = within(screen.getAllByRole("row")[1] as HTMLElement);
    expect(dirty.getByText("Con discrepancias")).toBeInTheDocument();
    expect(dirty.getByText("2")).toBeInTheDocument();
    expect(screen.getAllByText("Sin discrepancias").length).toBeGreaterThan(0);
    expect(new URLSearchParams(listGets().at(-1)?.search).get("page")).toBe("0");
  });

  it("warns in the banner, with the count, when the latest run found discrepancies, and opens its detail", async () => {
    const user = userEvent.setup();
    await openList(integrityRunDetail(DIRTY, FINDINGS));
    route("get", `${RUNS}/${DIRTY.id}`, integrityRunDetail(DIRTY, FINDINGS));

    const banner = await screen.findByText(/encontró 2 discrepancias/, {}, TIMEOUT);
    expect(banner).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver detalle" }));

    const table = await screen.findByRole("table", { name: "Discrepancias de la comprobación" }, TIMEOUT);
    const first = within(within(table).getAllByRole("row")[1] as HTMLElement);
    expect(first.getByText("Cargo sin asiento")).toBeInTheDocument();
    expect(first.getByText("00000000-0000-4000-8000-0000000d0001")).toBeInTheDocument();
    expect(first.getAllByText(/1,150\.00/).length).toBeGreaterThan(0);
    const second = within(within(table).getAllByRole("row")[2] as HTMLElement);
    expect(second.getByText("Las cuentas por cobrar no coinciden con los cargos")).toBeInTheDocument();
    expect(second.getAllByText("No aplica")).toHaveLength(2);
  });

  it("says the check has not run yet, with no failure, when there is no latest run", async () => {
    route("get", `${RUNS}/latest`, problem("resource-not-found", 404));
    route("get", RUNS, integrityRunPage([]));
    renderCharges("/charges/integrity");

    expect(await screen.findByText(/todavía no se ha ejecutado/, {}, TIMEOUT)).toBeInTheDocument();
    expect(await screen.findByText("Todavía no hay comprobaciones", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Actualizar" })).toBeInTheDocument();
  });

  it("tells that the latest run could not be read, apart from the list, and Retry reads it again", async () => {
    const user = userEvent.setup();
    route("get", `${RUNS}/latest`, problem("internal-error", 500), integrityRunDetail(CLEAN));
    route("get", RUNS, integrityRunPage([CLEAN]));
    renderCharges("/charges/integrity");

    expect(await screen.findByText("No se pudo leer la última comprobación.", {}, TIMEOUT)).toBeInTheDocument();
    expect(await screen.findByRole("table", { name: "Comprobaciones de integridad" }, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByText(/no encontró discrepancias/, {}, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the error state of the list with the trace id, and Retry loads it", async () => {
    const user = userEvent.setup();
    route("get", `${RUNS}/latest`, integrityRunDetail(CLEAN));
    route("get", RUNS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), integrityRunPage([CLEAN]));
    renderCharges("/charges/integrity");

    expect(await screen.findByText("No se pudieron cargar las comprobaciones", {}, { timeout: 20_000 })).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findAllByRole("row", {}, TIMEOUT)).toHaveLength(2);
  }, 30_000);

  it("reads the page from the address", async () => {
    route("get", `${RUNS}/latest`, integrityRunDetail(CLEAN));
    route("get", RUNS, integrityRunPage([CLEAN]));
    renderCharges("/charges/integrity?page=3");

    await screen.findByRole("table", { name: "Comprobaciones de integridad" }, TIMEOUT);
    expect(new URLSearchParams(listGets().at(-1)?.search).get("page")).toBe("2");
  });
});

describe("one run of the integrity check", () => {
  it("shows a clean run with its counts and no discrepancies, and a way back", async () => {
    const user = userEvent.setup();
    route("get", `${RUNS}/${CLEAN.id}`, integrityRunDetail(CLEAN));
    route("get", `${RUNS}/latest`, integrityRunDetail(CLEAN));
    route("get", RUNS, integrityRunPage([CLEAN]));
    renderCharges(`/charges/integrity/${CLEAN.id}`);

    expect(await screen.findByText(/los cargos y el libro mayor coinciden/, {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 3, name: "Discrepancias" })).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Discrepancias de la comprobación" })).not.toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: /Volver a las comprobaciones/ })[0] as HTMLElement);

    expect(await screen.findByRole("table", { name: "Comprobaciones de integridad" }, TIMEOUT)).toBeInTheDocument();
  });

  it("tells a run that does not exist in plain words, with no retry", async () => {
    route("get", `${RUNS}/${CLEAN.id}`, problem("resource-not-found", 404));
    renderCharges(`/charges/integrity/${CLEAN.id}`);

    expect(await screen.findByText("No encontramos esta comprobación", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });

  it("shows the error state with the trace id, and Retry loads the run", async () => {
    const user = userEvent.setup();
    route("get", `${RUNS}/${CLEAN.id}`, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), integrityRunDetail(CLEAN));
    renderCharges(`/charges/integrity/${CLEAN.id}`);

    expect(await screen.findByText("No se pudo cargar la comprobación", {}, { timeout: 20_000 })).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByText(/los cargos y el libro mayor coinciden/, {}, TIMEOUT)).toBeInTheDocument();
  }, 30_000);
});

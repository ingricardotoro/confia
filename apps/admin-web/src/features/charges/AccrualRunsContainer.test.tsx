import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { accrualRun, accrualRunPage, chargePlan, chargePlanPage, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCharges } from "../../test/render-charges.tsx";

// The list of runs over the real route, the real query layer and the real transport: the page the server serves (newest first,
// paged and filtered by plan there, with the page and the plan in the address), the link to a run and the states of the load.
installMswServer();

const PLANS = "/api/v1/charges/plans";
const RUNS = "/api/v1/charges/accrual-runs";
const TIMEOUT = { timeout: 10_000 };
const PLAN = chargePlan({ id: "00000000-0000-4000-8000-0000000000e1", name: "Primaria 2027", status: "ACTIVE" });
const OTHER = chargePlan({ id: "00000000-0000-4000-8000-0000000000e2", name: "Secundaria 2027", status: "ACTIVE" });
const FIRST = accrualRun({ id: "00000000-0000-4000-8000-0000000000b1", planId: PLAN.id, periodStart: "2027-02-01", status: "COMPLETED_WITH_ERRORS", studentsEvaluated: 30, chargesCreated: 28, chargesFailed: 2 });
const SECOND = accrualRun({ id: "00000000-0000-4000-8000-0000000000b2", planId: OTHER.id, periodStart: "2027-03-01", status: "REJECTED", failureCode: "preview-outdated", chargesCreated: 0, studentsEvaluated: 0 });

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === RUNS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");

async function openList(rows: unknown[] = [FIRST, SECOND], path = "/charges/accrual/runs") {
  route("get", PLANS, chargePlanPage([PLAN, OTHER]));
  route("get", RUNS, accrualRunPage(rows));
  const rendered = renderCharges(path);
  const table = await screen.findByRole("table", { name: "Ejecuciones de generación de cargos" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

describe("the list of runs", () => {
  it("shows each run with its plan named, its state and its counters, and asks for the first page with no filter", async () => {
    await openList();

    expect(screen.getByRole("heading", { level: 2, name: "Ejecuciones de generación" })).toBeInTheDocument();
    await waitFor(() => {
      expect(within(screen.getByRole("row", { name: /2027-02/ })).getByText("Primaria 2027")).toBeInTheDocument();
    }, TIMEOUT);
    const first = within(screen.getByRole("row", { name: /2027-02/ }));
    expect(first.getByText("Completada con errores")).toBeInTheDocument();
    expect(first.getByText("28")).toBeInTheDocument();
    const second = within(screen.getByRole("row", { name: /2027-03/ }));
    expect(second.getByText("Secundaria 2027")).toBeInTheDocument();
    expect(second.getByText("Rechazada")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("planId")).toBe(false);
    expect(lastQuery().has("sort")).toBe(false);
  });

  it("filters by plan on the server and keeps the plan in the address", async () => {
    const user = userEvent.setup();
    await openList();

    await user.selectOptions(await screen.findByRole("combobox", { name: "Plan de cobro" }), "Secundaria 2027");

    await waitFor(() => {
      expect(lastQuery().get("planId")).toBe(OTHER.id);
    }, TIMEOUT);
    expect(lastQuery().get("page")).toBe("0");
  });

  it("reads the plan and the page from the address", async () => {
    await openList([FIRST], `/charges/accrual/runs?planId=${PLAN.id}&page=2`);

    expect(lastQuery().get("planId")).toBe(PLAN.id);
    expect(lastQuery().get("page")).toBe("1");
  });

  it("opens a run from its period", async () => {
    const user = userEvent.setup();
    await openList();
    route("get", `${RUNS}/${FIRST.id}`, { status: 404, contentType: "application/problem+json", body: problem("resource-not-found", 404).body });

    await user.click(screen.getByRole("link", { name: "2027-02" }));

    expect(await screen.findByText("No se pudo cargar la ejecución", {}, { timeout: 20_000 })).toBeInTheDocument();
  }, 30_000);

  it("shows the empty state with its way forward when there are no runs", async () => {
    route("get", PLANS, chargePlanPage([PLAN]));
    route("get", RUNS, accrualRunPage([]));
    renderCharges("/charges/accrual/runs");

    expect(await screen.findByText("Todavía no hay ejecuciones", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Generar cargos" }).length).toBeGreaterThan(0);
  });

  it("shows the error state with the trace id, and Retry loads the list", async () => {
    const user = userEvent.setup();
    route("get", PLANS, chargePlanPage([PLAN]));
    route("get", RUNS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), accrualRunPage([FIRST]));
    renderCharges("/charges/accrual/runs");

    expect(await screen.findByText("No se pudieron cargar las ejecuciones", {}, { timeout: 20_000 })).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /2027-02/ }, TIMEOUT)).toBeInTheDocument();
  }, 30_000);
});

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import {
  accrualPeriod,
  accrualPeriodsRecord,
  chargePlan,
  chargePlanPage,
  chargeSimulation,
  chargeSimulationRecord,
  paymentConcept,
  paymentConceptPage,
  problem,
  student,
  studentPage,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderScholarships } from "../../test/render-scholarships.tsx";

// The simulator of charges over the real route, the real query layer and the real transport: nothing is asked until the person asks, the figures of the
// acceptance example of the cut (base L 1,100.00, scholarship of 25 % that takes L 275.00 off, net base L 825.00, tax L 123.75) are the ones the server
// sent and the page only draws them, and every state of the load and of the answer (not enrolled, failed, nothing to charge) is told.
installMswServer();

const PLANS = "/api/v1/charges/plans";
const PLAN_ID = "00000000-0000-4000-8000-000000000c01";
const STUDENT_ID = "00000000-0000-4000-8000-0000000000b1";
const PERIOD = "2027-02-01";
const SIMULATION = `${PLANS}/${PLAN_ID}/simulation`;
const ADDRESS = `/scholarships/simulator?planId=${PLAN_ID}&studentId=${STUDENT_ID}&period=${PERIOD}`;
const TIMEOUT = { timeout: 10_000 };
const MENSUALIDAD = paymentConcept({ id: "00000000-0000-4000-8000-000000007001", code: "MENS", name: "Mensualidad" });

const simulations = () => requests().filter((sent) => sent.method === "GET" && sent.path === SIMULATION);

function routeLookups() {
  route("get", PLANS, chargePlanPage([chargePlan({ id: PLAN_ID, name: "Primaria 2027", status: "ACTIVE" })]));
  route("get", "/api/v1/catalog/payment-concepts", paymentConceptPage([MENSUALIDAD]));
  route("get", `${PLANS}/${PLAN_ID}/accrual-periods`, accrualPeriodsRecord([accrualPeriod({ periodStart: PERIOD, label: "2027-02", dueOn: "2027-02-05" })]));
  route("get", "/api/v1/students", studentPage([student({ id: STUDENT_ID, studentCode: "EST-0001", firstName: "Ana", lastName: "Pérez" })]));
}

async function openAndSimulate(reply = chargeSimulationRecord(chargeSimulation())) {
  routeLookups();
  route("get", SIMULATION, reply);
  const user = userEvent.setup();
  renderScholarships(ADDRESS);
  const button = await screen.findByRole("button", { name: "Simular" }, TIMEOUT);
  await waitFor(() => {
    expect(button).toBeEnabled();
  }, TIMEOUT);
  await user.click(button);
  return user;
}

describe("the simulator page", () => {
  it("is an entry of the scholarships area, shows the plan, the student and the period of the address chosen, and asks nothing until the person asks", async () => {
    routeLookups();
    renderScholarships(ADDRESS);

    expect(await screen.findByRole("heading", { level: 2, name: "Simulador de cargos" }, TIMEOUT)).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole("combobox", { name: /^Período/ })).toHaveValue(PERIOD);
    }, TIMEOUT);
    expect(screen.getByRole("combobox", { name: /^Plan de cobro/ })).toHaveValue(PLAN_ID);
    expect(screen.getByRole("group", { name: "Estudiante elegido" })).toBeInTheDocument();
    expect(screen.getByText("Todavía no se ha simulado nada")).toBeInTheDocument();
    expect(simulations()).toHaveLength(0);
    expect(new URLSearchParams(requests().find((sent) => sent.path === PLANS)?.search).get("status")).toBe("ACTIVE");
  });

  it("shows the figures of the acceptance example exactly as the server sent them: base 1,100.00, scholarship 275.00, net base 825.00, tax 123.75", async () => {
    await openAndSimulate();

    const table = await screen.findByRole("table", { name: "Resultado de la simulación por concepto" }, TIMEOUT);
    const row = within(within(table).getByRole("row", { name: /MENS · Mensualidad/ }));
    expect(row.getByText("L 1,100.00")).toBeInTheDocument();
    expect(row.getByText("Beca BECA25")).toBeInTheDocument();
    expect(row.getAllByText("L 275.00")).toHaveLength(2);
    expect(row.getByText("L 825.00")).toBeInTheDocument();
    expect(row.getByText("L 123.75")).toBeInTheDocument();
    expect(row.getByText("L 948.75")).toBeInTheDocument();
    expect(row.getByText("Se generaría")).toBeInTheDocument();
    expect(screen.getByText(/Es una simulación: no se generó ningún cargo/)).toBeInTheDocument();
  });

  it("asks the server for the student, the plan and the period of the choice, once, with no Idempotency-Key", async () => {
    await openAndSimulate();

    await screen.findByRole("table", { name: "Resultado de la simulación por concepto" }, TIMEOUT);
    expect(simulations()).toHaveLength(1);
    const query = new URLSearchParams(simulations()[0]?.search);
    expect(query.get("studentId")).toBe(STUDENT_ID);
    expect(query.get("period")).toBe(PERIOD);
    expect(simulations()[0]?.headers.get("Idempotency-Key")).toBeNull();
  });

  it("shows a concept with no scholarship as such, and a concept that would fail with its code", async () => {
    await openAndSimulate(
      chargeSimulationRecord(
        chargeSimulation({
          lines: [
            {
              paymentConceptId: MENSUALIDAD.id,
              outcome: "CREATED",
              base: { amount: "1000.00", currency: "HNL" },
              benefitAmount: { amount: "0.00", currency: "HNL" },
              benefits: [],
              netBase: { amount: "1000.00", currency: "HNL" },
              tax: { amount: "150.00", currency: "HNL" },
              total: { amount: "1150.00", currency: "HNL" },
            },
            { paymentConceptId: "00000000-0000-4000-8000-000000007002", outcome: "ERROR", errorCode: "NO_PRICE", benefits: [] },
          ],
        }),
      ),
    );

    const table = await screen.findByRole("table", { name: "Resultado de la simulación por concepto" }, TIMEOUT);
    expect(within(within(table).getByRole("row", { name: /MENS · Mensualidad/ })).getByText("Sin beneficios")).toBeInTheDocument();
    const failing = within(within(table).getByRole("row", { name: /Con error: NO_PRICE/ }));
    expect(failing.getByText("No disponible")).toBeInTheDocument();
    expect(failing.getAllByText("—").length).toBeGreaterThan(0);
  });

  it("says in words that the student is not enrolled in what the plan targets, as an answer and not as a failure", async () => {
    await openAndSimulate(problem("resource-not-found", 404));

    expect(await screen.findByText("El estudiante no está matriculado en ese plan", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });

  it("shows the error state with the trace and simulates again on retry", async () => {
    const user = await openAndSimulate(problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }));

    expect(await screen.findByText("No se pudo calcular la simulación", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    route("get", SIMULATION, chargeSimulationRecord(chargeSimulation()));
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("table", { name: "Resultado de la simulación por concepto" }, TIMEOUT)).toBeInTheDocument();
    expect(simulations()).toHaveLength(2);
  });

  it("says that the plan generates nothing for the period when the server lists no concept", async () => {
    await openAndSimulate(chargeSimulationRecord(chargeSimulation({ lines: [] })));

    expect(await screen.findByText("El plan no genera cargos para este período", undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("drops the result when the choice changes, because it was calculated for another one", async () => {
    const user = await openAndSimulate();
    await screen.findByRole("table", { name: "Resultado de la simulación por concepto" }, TIMEOUT);

    await user.click(screen.getByRole("button", { name: "Cambiar estudiante" }));

    await waitFor(() => {
      expect(screen.queryByRole("table", { name: "Resultado de la simulación por concepto" })).not.toBeInTheDocument();
    }, TIMEOUT);
    expect(screen.getByRole("button", { name: "Simular" })).toBeDisabled();
  });

  it("chooses the student with the search the server does, and writes the choice in the address", async () => {
    const user = userEvent.setup();
    routeLookups();
    const { router } = renderScholarships(`/scholarships/simulator?planId=${PLAN_ID}&period=${PERIOD}`);

    await user.type(await screen.findByRole("combobox", { name: /^Estudiante/ }, TIMEOUT), "Ana");
    await user.click(await screen.findByRole("option", { name: "Pérez, Ana (EST-0001)" }, TIMEOUT));

    await waitFor(() => {
      expect(router.state.location.searchStr).toContain(`studentId=${STUDENT_ID}`);
    }, TIMEOUT);
    expect(screen.getByRole("group", { name: "Estudiante elegido" })).toHaveTextContent("Pérez, Ana (EST-0001)");
  });
});

describe("the states of the load of the simulator", () => {
  it("says there is no active plan, with the way to the plans", async () => {
    route("get", PLANS, chargePlanPage([]));
    route("get", "/api/v1/catalog/payment-concepts", paymentConceptPage([]));
    renderScholarships("/scholarships/simulator");

    expect(await screen.findByText("No hay planes activos", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ir a los planes de cobro" })).toBeInTheDocument();
  });

  it("shows the error state of the plans with a retry that loads them", async () => {
    const user = userEvent.setup();
    route("get", PLANS, problem("internal-error", 500), chargePlanPage([chargePlan({ id: PLAN_ID, name: "Primaria 2027", status: "ACTIVE" })]));
    route("get", "/api/v1/catalog/payment-concepts", paymentConceptPage([]));
    renderScholarships("/scholarships/simulator");

    expect(await screen.findByText("No se pudieron cargar los planes", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("combobox", { name: /^Plan de cobro/ }, TIMEOUT)).toBeInTheDocument();
  });

  it("tells that the plan has no periods, and that the periods could not be loaded", async () => {
    routeLookups();
    route("get", `${PLANS}/${PLAN_ID}/accrual-periods`, accrualPeriodsRecord([]));
    renderScholarships(`/scholarships/simulator?planId=${PLAN_ID}`);

    expect(await screen.findByText("El plan no tiene períodos", undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("tells that the periods could not be loaded, with a retry", async () => {
    routeLookups();
    route("get", `${PLANS}/${PLAN_ID}/accrual-periods`, problem("internal-error", 500));
    renderScholarships(`/scholarships/simulator?planId=${PLAN_ID}`);

    expect(await screen.findByText("No se pudieron cargar los períodos del plan.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();
  });

  it("names the concepts as not available when their lookup failed, and offers to retry it", async () => {
    routeLookups();
    route("get", "/api/v1/catalog/payment-concepts", problem("internal-error", 500));
    route("get", SIMULATION, chargeSimulationRecord(chargeSimulation()));
    const user = userEvent.setup();
    renderScholarships(ADDRESS);
    const button = await screen.findByRole("button", { name: "Simular" }, TIMEOUT);
    await waitFor(() => {
      expect(button).toBeEnabled();
    }, TIMEOUT);
    await user.click(button);

    expect(await screen.findByText(/No se pudieron cargar los nombres de los conceptos/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(await screen.findByText("No disponible", undefined, TIMEOUT)).toBeInTheDocument();
  });
});

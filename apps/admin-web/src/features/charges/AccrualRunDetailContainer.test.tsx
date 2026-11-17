import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import {
  accrualRun,
  accrualRunDetailRecord,
  accrualRunItem,
  chargePlanPage,
  chargePlan,
  paymentConcept,
  paymentConceptPage,
  problem,
  student,
  studentPage,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCharges } from "../../test/render-charges.tsx";

// One run over the real route, the real query layer and the real transport: its progress read again while it is queued or running
// and no more once it is terminal, the page of items the server serves (in the address), the rejection by a changed digest, and the
// states of the load.
installMswServer();

const PLANS = "/api/v1/charges/plans";
const RUNS = "/api/v1/charges/accrual-runs";
const TIMEOUT = { timeout: 10_000 };
const PLAN = chargePlan({ id: "00000000-0000-4000-8000-0000000000e1", name: "Primaria 2027", status: "ACTIVE" });
const RUN_ID = "00000000-0000-4000-8000-0000000000b1";
const STUDENT_A = student({ id: "00000000-0000-4000-8000-000000005001", firstName: "Ana", lastName: "Aguilar", studentCode: "E-000001" });
const STUDENT_C = student({ id: "00000000-0000-4000-8000-000000005003", firstName: "Sofía", lastName: "Banegas", studentCode: "E-000003" });
const COLEGIATURA = paymentConcept({ id: "00000000-0000-4000-8000-000000007001", code: "COL", name: "Colegiatura" });
const ITEMS = [
  accrualRunItem({ studentId: STUDENT_A.id }),
  accrualRunItem({ studentId: STUDENT_C.id, outcome: "ERROR", errorCode: "NO_PRICE", chargeId: null }),
];

const run = (override: Parameters<typeof accrualRun>[0] = {}) => accrualRun({ id: RUN_ID, planId: PLAN.id, periodStart: "2027-02-01", ...override });
const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === `${RUNS}/${RUN_ID}`);

function routeLookups() {
  route("get", PLANS, chargePlanPage([PLAN]));
  route("get", "/api/v1/students", studentPage([STUDENT_A, STUDENT_C]));
  route("get", "/api/v1/catalog/payment-concepts", paymentConceptPage([COLEGIATURA]));
}

describe("one run", () => {
  it("shows a completed run once, with its counters, its rows with the students and concepts named and the error explained, and does not read it again", async () => {
    routeLookups();
    route("get", `${RUNS}/${RUN_ID}`, accrualRunDetailRecord(run({ status: "COMPLETED_WITH_ERRORS", studentsEvaluated: 2, chargesCreated: 1, chargesFailed: 1 }), ITEMS));
    renderCharges(`/charges/accrual/runs/${RUN_ID}`);

    expect(await screen.findByRole("heading", { level: 2, name: "Ejecución de Primaria 2027, período 2027-02" }, TIMEOUT)).toBeInTheDocument();
    const failed = await screen.findByRole("row", { name: /Banegas, Sofía \(E-000003\)/ }, TIMEOUT);
    expect(within(failed).getByText("Sin precio vigente")).toBeInTheDocument();
    expect(within(failed).getByText(/Ninguna lista de precios da precio a este concepto/)).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Aguilar, Ana/ })).getByText("COL, Colegiatura")).toBeInTheDocument();
    expect(screen.getByText(/La ejecución terminó con errores: se crearon 1 cargo y 1 renglón quedó con error\./)).toBeInTheDocument();
    expect(new URLSearchParams(gets()[0]?.search).get("page")).toBe("0");
    expect(gets()).toHaveLength(1);
  });

  it("reads a run that is going again until it is terminal, tells each step in the live region and then stops", async () => {
    routeLookups();
    route(
      "get",
      `${RUNS}/${RUN_ID}`,
      accrualRunDetailRecord(run({ status: "QUEUED", startedAt: null, finishedAt: null, studentsEvaluated: 0, chargesCreated: 0 }), []),
      accrualRunDetailRecord(run({ status: "RUNNING", finishedAt: null, studentsEvaluated: 1, chargesCreated: 1 }), [ITEMS[0]]),
      accrualRunDetailRecord(run({ status: "COMPLETED", studentsEvaluated: 2, chargesCreated: 2 }), ITEMS),
    );
    renderCharges(`/charges/accrual/runs/${RUN_ID}`);

    expect(await screen.findByText("La ejecución está en cola. Esta página se actualiza sola.", {}, TIMEOUT)).toBeInTheDocument();
    expect(await screen.findByText(/La ejecución está en curso: 1 estudiante evaluado hasta ahora/, {}, TIMEOUT)).toBeInTheDocument();
    expect(await screen.findByText("La ejecución terminó: se crearon 2 cargos.", {}, TIMEOUT)).toBeInTheDocument();
    expect(gets()).toHaveLength(3);
    await new Promise((resolve) => setTimeout(resolve, 2600));
    expect(gets()).toHaveLength(3);
  }, 30_000);

  it("explains a run rejected because the digest changed, with nothing written, and goes back to calculate again with the plan and the period", async () => {
    const user = userEvent.setup();
    routeLookups();
    route("get", `${RUNS}/${RUN_ID}`, accrualRunDetailRecord(run({ status: "REJECTED", failureCode: "preview-outdated", studentsEvaluated: 0, chargesCreated: 0 }), []));
    renderCharges(`/charges/accrual/runs/${RUN_ID}`);

    expect(await screen.findByText(/Los datos cambiaron desde la vista previa/, {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText("La ejecución se rechazó y no se escribió nada en el libro mayor.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Calcular de nuevo" }));

    expect(await screen.findByRole("combobox", { name: "Plan de cobro" }, TIMEOUT)).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole("combobox", { name: "Plan de cobro" })).toHaveValue(PLAN.id);
    }, TIMEOUT);
  });

  it("asks the server for the page of items named in the address", async () => {
    routeLookups();
    route("get", `${RUNS}/${RUN_ID}`, accrualRunDetailRecord(run(), ITEMS, { page: 1, size: 25, total: 40 }));
    renderCharges(`/charges/accrual/runs/${RUN_ID}?page=2`);

    await screen.findByRole("row", { name: /Aguilar, Ana/ }, TIMEOUT);

    expect(new URLSearchParams(gets()[0]?.search).get("page")).toBe("1");
    expect(new URLSearchParams(gets()[0]?.search).get("size")).toBe("25");
  });

  it("tells a run that does not exist, with a way back", async () => {
    routeLookups();
    route("get", `${RUNS}/${RUN_ID}`, problem("resource-not-found", 404));
    renderCharges(`/charges/accrual/runs/${RUN_ID}`);

    expect(await screen.findByText("No se pudo cargar la ejecución", {}, { timeout: 20_000 })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Volver a las ejecuciones" })).toBeInTheDocument();
  }, 30_000);

  it("shows the error with the trace id when the run fails to load, and Retry loads it", async () => {
    const user = userEvent.setup();
    routeLookups();
    route("get", `${RUNS}/${RUN_ID}`, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), accrualRunDetailRecord(run(), ITEMS));
    renderCharges(`/charges/accrual/runs/${RUN_ID}`);

    expect(await screen.findByText(/Identificador para soporte: 01J8Z4K2QF9P0X/, {}, { timeout: 20_000 })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /Aguilar, Ana/ }, TIMEOUT)).toBeInTheDocument();
  }, 30_000);
});

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import {
  accrualPeriod,
  accrualPeriodsRecord,
  accrualPreview,
  accrualPreviewRecord,
  accrualRun,
  accrualRunDetailRecord,
  accrualRunRecord,
  chargePlan,
  chargePlanPage,
  paymentConcept,
  paymentConceptPage,
  problem,
  student,
  studentPage,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCharges } from "../../test/render-charges.tsx";

// The generation page over the real route, the real query layer and the real transport: the choice of an active plan and one of
// its periods (kept in the address), the preview, the blockers, the confirmation with its Idempotency-Key and its digest, the
// retry of the same intent, the refusals of the server and the states of the load.
installMswServer();

const PLANS = "/api/v1/charges/plans";
const RUNS = "/api/v1/charges/accrual-runs";
const TIMEOUT = { timeout: 10_000 };
const PLAN_ID = "00000000-0000-4000-8000-0000000000e1";
const PLAN = chargePlan({ id: PLAN_ID, name: "Primaria 2027", status: "ACTIVE" });
const PERIOD = "2027-02-01";
const STUDENT_A = student({ id: "00000000-0000-4000-8000-000000005001", firstName: "Ana", lastName: "Aguilar", studentCode: "E-000001" });
const STUDENT_B = student({ id: "00000000-0000-4000-8000-000000005002", firstName: "Luis", lastName: "Aguilar", studentCode: "E-000002" });
const STUDENT_C = student({ id: "00000000-0000-4000-8000-000000005003", firstName: "Sofía", lastName: "Banegas", studentCode: "E-000003" });
const COLEGIATURA = paymentConcept({ id: "00000000-0000-4000-8000-000000007001", code: "COL", name: "Colegiatura" });
const DIGEST = "d".repeat(64);

const posts = (path: string) => requests().filter((sent) => sent.method === "POST" && sent.path === path);

function routeLookups() {
  route("get", PLANS, chargePlanPage([PLAN]));
  route("get", "/api/v1/students", studentPage([STUDENT_A, STUDENT_B, STUDENT_C]));
  route("get", "/api/v1/catalog/payment-concepts", paymentConceptPage([COLEGIATURA]));
  route("get", `${PLANS}/${PLAN_ID}/accrual-periods`, accrualPeriodsRecord([accrualPeriod({ periodStart: PERIOD, label: "2027-02", dueOn: "2027-02-05" })]));
}

async function openAndPreview(preview = accrualPreview({ planId: PLAN_ID, previewDigest: DIGEST })) {
  routeLookups();
  route("post", `${PLANS}/${PLAN_ID}/accrual-preview`, accrualPreviewRecord(preview));
  const user = userEvent.setup();
  renderCharges(`/charges/accrual?planId=${PLAN_ID}&periodStart=${PERIOD}`);
  const button = await screen.findByRole("button", { name: "Calcular vista previa" }, TIMEOUT);
  await waitFor(() => {
    expect(button).toBeEnabled();
  }, TIMEOUT);
  await user.click(button);
  await screen.findByRole("region", { name: "Resumen de la vista previa" }, TIMEOUT);
  return user;
}

describe("the generation page", () => {
  it("is the second entry of the charges area, shows the plan and the period of the address chosen, and asks nothing until the preview is pressed", async () => {
    routeLookups();
    renderCharges(`/charges/accrual?planId=${PLAN_ID}&periodStart=${PERIOD}`);

    expect(await screen.findByRole("heading", { level: 2, name: "Generación de cargos" }, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Cobros" })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole("combobox", { name: "Período" })).toHaveValue(PERIOD);
    }, TIMEOUT);
    expect(screen.getByRole("combobox", { name: "Plan de cobro" })).toHaveValue(PLAN_ID);
    expect(screen.getByText("Elija un plan y un período")).toBeInTheDocument();
    expect(posts(`${PLANS}/${PLAN_ID}/accrual-preview`)).toHaveLength(0);
    expect(new URLSearchParams(requests().find((sent) => sent.path === PLANS)?.search).get("status")).toBe("ACTIVE");
  });

  it("offers the periods the server lists, with the last run of each, and not a free date", async () => {
    routeLookups();
    route(
      "get",
      `${PLANS}/${PLAN_ID}/accrual-periods`,
      accrualPeriodsRecord([
        accrualPeriod({ periodStart: "2027-01-01", label: "2027-01", dueOn: "2027-01-05", lastRun: accrualRun({ status: "COMPLETED_WITH_ERRORS" }) }),
        accrualPeriod({ periodStart: PERIOD, label: "2027-02", dueOn: "2027-02-05" }),
      ]),
    );
    renderCharges(`/charges/accrual?planId=${PLAN_ID}`);

    const period = await screen.findByRole("combobox", { name: "Período" }, TIMEOUT);
    await waitFor(() => {
      expect(period).toBeEnabled();
    }, TIMEOUT);
    expect(within(period).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Elija un período",
      "2027-01, vence el 5 ene 2027. Última ejecución: Completada con errores",
      "2027-02, vence el 5 feb 2027",
    ]);
  });

  it("shows the preview: the totals as the server sent them, the errors by code and a row for each student", async () => {
    await openAndPreview();

    const summary = within(screen.getByRole("region", { name: "Resumen de la vista previa" }));
    expect(summary.getByText("3,450.00 lempiras hondureños")).toBeInTheDocument();
    expect(summary.getByText("Sin precio vigente: 1 renglón.")).toBeInTheDocument();
    expect(await screen.findByRole("row", { name: /Aguilar, Ana \(E-000001\)/ }, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("row", { name: /Banegas, Sofía \(E-000003\)/ })).toHaveTextContent("Sin precio vigente");
    const sent = posts(`${PLANS}/${PLAN_ID}/accrual-preview`);
    expect(sent).toHaveLength(1);
    expect(JSON.parse(sent[0]?.body ?? "{}")).toEqual({ periodStart: PERIOD });
    expect(sent[0]?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("shows a blocker with its explanation and does not allow the confirmation", async () => {
    await openAndPreview(accrualPreview({ planId: PLAN_ID, blockers: ["posting-accounts-incomplete"], previewDigest: DIGEST }));

    expect(screen.getByText(/Falta configurar el mapa de cuentas contables/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirmar generación" })).toBeDisabled();
  });

  it("confirms in a dialog, sends the digest and one Idempotency-Key, and follows the run it queued", async () => {
    const user = await openAndPreview();
    const RUN = accrualRun({ id: "00000000-0000-4000-8000-0000000000b1", planId: PLAN_ID, periodStart: PERIOD, status: "QUEUED", startedAt: null, finishedAt: null, studentsEvaluated: 0, chargesCreated: 0 });
    route("post", `${PLANS}/${PLAN_ID}/accrual-runs`, accrualRunRecord(RUN));
    route("get", `${RUNS}/${RUN.id}`, accrualRunDetailRecord(RUN, []));

    await user.click(screen.getByRole("button", { name: "Confirmar generación" }));
    const dialog = await screen.findByRole("dialog", { name: "¿Generar los cargos de Primaria 2027?" }, TIMEOUT);
    expect(within(dialog).getByText(/no se pueden deshacer desde esta pantalla/)).toBeInTheDocument();
    expect(posts(`${PLANS}/${PLAN_ID}/accrual-runs`)).toHaveLength(0);
    await user.click(within(dialog).getByRole("button", { name: "Generar cargos" }));

    expect(await screen.findByText("La ejecución está en cola. Esta página se actualiza sola.", {}, TIMEOUT)).toBeInTheDocument();
    const sent = posts(`${PLANS}/${PLAN_ID}/accrual-runs`);
    expect(sent).toHaveLength(1);
    expect(sent[0]?.headers.get("Idempotency-Key")).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(JSON.parse(sent[0]?.body ?? "{}")).toEqual({ periodStart: PERIOD, previewDigest: DIGEST });
  });

  it("sends the same Idempotency-Key when the same confirmation is sent again after the answer never arrived", async () => {
    const user = await openAndPreview();
    const RUN = accrualRun({ id: "00000000-0000-4000-8000-0000000000b1", planId: PLAN_ID, periodStart: PERIOD, status: "QUEUED", startedAt: null, finishedAt: null });
    route("post", `${PLANS}/${PLAN_ID}/accrual-runs`, "network-error", accrualRunRecord(RUN));
    route("get", `${RUNS}/${RUN.id}`, accrualRunDetailRecord(RUN, []));

    await user.click(screen.getByRole("button", { name: "Confirmar generación" }));
    const dialog = await screen.findByRole("dialog", {}, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Generar cargos" }));
    expect(await within(dialog).findByText(/no se duplicarán los cargos/, {}, TIMEOUT)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Generar cargos" }));

    await screen.findByText(/La ejecución está en cola/, {}, TIMEOUT);
    const keys = posts(`${PLANS}/${PLAN_ID}/accrual-runs`).map((sent) => sent.headers.get("Idempotency-Key"));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it("makes a new Idempotency-Key for a new confirmation after the dialog was closed", async () => {
    const user = await openAndPreview();
    route("post", `${PLANS}/${PLAN_ID}/accrual-runs`, problem("accrual-run-in-progress", 409));

    for (let turn = 0; turn < 2; turn += 1) {
      await user.click(screen.getByRole("button", { name: "Confirmar generación" }));
      const dialog = await screen.findByRole("dialog", {}, TIMEOUT);
      await user.click(within(dialog).getByRole("button", { name: "Generar cargos" }));
      await within(dialog).findByRole("alert", {}, TIMEOUT);
      await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));
      await waitFor(() => {
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      }, TIMEOUT);
    }

    const keys = posts(`${PLANS}/${PLAN_ID}/accrual-runs`).map((sent) => sent.headers.get("Idempotency-Key"));
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it.each([
    ["accrual-run-in-progress", 409, /Ya hay una generación de cargos en curso para este plan y período/],
    ["forbidden", 403, /No tiene permiso para realizar esta acción/],
    ["idempotency-payload-mismatch", 422, /Esta confirmación ya se había usado para otra solicitud distinta/],
    ["validation-failed", 400, /No se pudieron procesar los datos enviados/],
  ] as const)("tells the %s refusal inside the dialog and leaves the preview as it was, with no promise of a retry", async (code, status, text) => {
    const user = await openAndPreview();
    route("post", `${PLANS}/${PLAN_ID}/accrual-runs`, problem(code, status, { traceId: "01J8Z4K2QF9P0X" }));

    await user.click(screen.getByRole("button", { name: "Confirmar generación" }));
    const dialog = await screen.findByRole("dialog", {}, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Generar cargos" }));

    const alert = await within(dialog).findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent(text);
    expect(alert).toHaveTextContent("Identificador para soporte: 01J8Z4K2QF9P0X");
    expect(alert).not.toHaveTextContent("no se duplicarán los cargos");
    expect(screen.getByText("Resumen de la vista previa")).toBeInTheDocument();
  });

  it("shows the error of the preview with the trace id, and Retry asks again", async () => {
    routeLookups();
    route("post", `${PLANS}/${PLAN_ID}/accrual-preview`, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), accrualPreviewRecord(accrualPreview({ planId: PLAN_ID })));
    const user = userEvent.setup();
    renderCharges(`/charges/accrual?planId=${PLAN_ID}&periodStart=${PERIOD}`);
    const button = await screen.findByRole("button", { name: "Calcular vista previa" }, TIMEOUT);
    await waitFor(() => {
      expect(button).toBeEnabled();
    }, TIMEOUT);

    await user.click(button);

    expect(await screen.findByText("No se pudo calcular la vista previa", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByRole("region", { name: "Resumen de la vista previa" }, TIMEOUT)).toBeInTheDocument();
  });

  it("tells the 403 of the preview in plain words", async () => {
    routeLookups();
    route("post", `${PLANS}/${PLAN_ID}/accrual-preview`, problem("forbidden", 403));
    const user = userEvent.setup();
    renderCharges(`/charges/accrual?planId=${PLAN_ID}&periodStart=${PERIOD}`);
    const button = await screen.findByRole("button", { name: "Calcular vista previa" }, TIMEOUT);
    await waitFor(() => {
      expect(button).toBeEnabled();
    }, TIMEOUT);

    await user.click(button);

    expect(await screen.findByText(/No tiene permiso para realizar esta acción/, {}, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the empty state when no plan is active", async () => {
    route("get", PLANS, chargePlanPage([]));
    route("get", "/api/v1/students", studentPage([]));
    route("get", "/api/v1/catalog/payment-concepts", paymentConceptPage([]));
    renderCharges("/charges/accrual");

    expect(await screen.findByText("No hay planes activos", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ir a los planes de cobro" })).toBeInTheDocument();
  });

  it("shows the error of the active plans with a retry that loads them", async () => {
    route("get", PLANS, problem("internal-error", 500), chargePlanPage([PLAN]));
    route("get", "/api/v1/students", studentPage([]));
    route("get", "/api/v1/catalog/payment-concepts", paymentConceptPage([]));
    const user = userEvent.setup();
    renderCharges("/charges/accrual");

    expect(await screen.findByText("No se pudieron cargar los planes activos", {}, { timeout: 20_000 })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("combobox", { name: "Plan de cobro" }, TIMEOUT)).toBeInTheDocument();
  }, 30_000);

  it("tells a plan with no periods", async () => {
    routeLookups();
    route("get", `${PLANS}/${PLAN_ID}/accrual-periods`, accrualPeriodsRecord([]));
    renderCharges(`/charges/accrual?planId=${PLAN_ID}`);

    expect(await screen.findByText("Este plan no tiene períodos para generar", {}, TIMEOUT)).toBeInTheDocument();
  });
});

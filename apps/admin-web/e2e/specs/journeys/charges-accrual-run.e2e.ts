import {
  dialog,
  openAccrual,
  openConfirmation,
  previewPeriod,
  progress,
  RUN_ROWS,
  RUNS_TABLE,
  tableRows,
  TERMINAL,
  test,
} from "../../support/charges-accrual.ts";
import { expect, recordedCalls } from "../../support/scenario.ts";

// The confirmation and the run: an explicit dialog that says what will be posted and that it cannot be undone from here, the request
// with its Idempotency-Key and the digest of the preview, the run followed on its own page (queued, running, terminal) with its rows, the
// refusals of the server (a run already in progress, a digest that changed) and the list of runs. Against the SIMULATED API, whose run
// goes queued, running and terminal over three reads.
const POST_RUNS = (call: { method: string; path: string }) => call.method === "POST" && call.path.endsWith("/accrual-runs");

test.describe("the confirmation and the run", () => {
  test("states what will be posted and that it cannot be undone from here, and sends nothing until it is confirmed", async ({ page, request, scenarioId }) => {
    await openAccrual(page, scenarioId);
    await previewPeriod(page);

    await openConfirmation(page);

    await expect(dialog(page).getByRole("heading", { name: "¿Generar los cargos de Primaria 2026?" })).toBeVisible();
    await expect(dialog(page).getByText("Período 2026-05. Se asentarán 9 cargos en el libro mayor.")).toBeVisible();
    await expect(dialog(page).getByText(/no se pueden deshacer desde esta pantalla/)).toBeVisible();
    await expect(dialog(page).getByText("15,525.00 lempiras hondureños")).toBeVisible();
    expect((await recordedCalls(request, scenarioId)).filter(POST_RUNS)).toHaveLength(0);
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();
    expect((await recordedCalls(request, scenarioId)).filter(POST_RUNS)).toHaveLength(0);
  });

  test("confirms, follows the run from queued to completed with errors, and reads each state in the live region", async ({ page, request, scenarioId }) => {
    test.setTimeout(60_000);
    await openAccrual(page, scenarioId);
    await previewPeriod(page);
    await openConfirmation(page);

    await dialog(page).getByRole("button", { name: "Generar cargos" }).click();

    await expect(page).toHaveURL(/\/charges\/accrual\/runs\/[0-9a-f-]{36}$/);
    await expect(page.getByRole("heading", { level: 2, name: "Ejecución de Primaria 2026, período 2026-05" })).toBeVisible();
    await expect(progress(page)).toContainText("La ejecución terminó con errores: se crearon 9 cargos y 3 renglones quedaron con error.", TERMINAL);
    await expect(page.getByLabel("Datos de la ejecución")).toContainText("Confirmada con una vista previa");
    const rows = tableRows(page, RUN_ROWS);
    await expect(rows).toHaveCount(12);
    await expect(rows.filter({ hasText: "Precio ambiguo" })).toContainText("Dos listas de precios del mismo nivel");
    await expect(rows.filter({ hasText: "Creado" })).toHaveCount(9);
    expect((await recordedCalls(request, scenarioId)).filter(POST_RUNS)).toHaveLength(1);
  });

  test("lists the new run first, and filters the runs by plan in the address", async ({ page, scenarioId }) => {
    test.setTimeout(60_000);
    await openAccrual(page, scenarioId);
    await previewPeriod(page);
    await openConfirmation(page);
    await dialog(page).getByRole("button", { name: "Generar cargos" }).click();
    await expect(progress(page)).toContainText("La ejecución terminó", TERMINAL);

    await page.getByRole("link", { name: "Ejecuciones de generación" }).click();

    await expect(page).toHaveURL(/\/charges\/accrual\/runs$/);
    await expect(page.getByRole("table", { name: RUNS_TABLE }).locator("tbody tr td:first-child")).toHaveText(["2026-05", "2026-04", "2026-03", "2026-02", "2026-01"]);
    await page.getByLabel("Plan de cobro").selectOption({ label: "Secundaria 2026" });
    await expect(page).toHaveURL(/planId=/);
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
  });

  test("tells a run rejected because the data changed since the preview, and goes back to calculate again with the same plan and period", async ({ page, scenarioId }) => {
    test.setTimeout(60_000);
    await openAccrual(page, scenarioId, "charges-accrual-digest-changed");
    await previewPeriod(page);
    await openConfirmation(page);

    await dialog(page).getByRole("button", { name: "Generar cargos" }).click();

    await expect(progress(page)).toContainText("La ejecución se rechazó y no se escribió nada en el libro mayor.", TERMINAL);
    await expect(page.getByText(/Los datos cambiaron desde la vista previa/)).toBeVisible();
    await page.getByRole("button", { name: "Calcular de nuevo" }).click();
    await expect(page).toHaveURL(/\/charges\/accrual\?planId=.*periodStart=2026-05-01/);
    await expect(page.getByLabel(/^Período/)).toHaveValue("2026-05-01", { timeout: 20_000 });
  });

  test("keeps the preview and says so inside the dialog when a run of the period is already in progress", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId, "charges-accrual-in-progress");
    await previewPeriod(page);
    await openConfirmation(page);

    await dialog(page).getByRole("button", { name: "Generar cargos" }).click();

    await expect(dialog(page).getByRole("alert")).toContainText("Ya hay una generación de cargos en curso para este plan y período");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(page.getByRole("region", { name: "Resumen de la vista previa" })).toBeVisible();
  });
});

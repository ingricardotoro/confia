import {
  choosePlanAndPeriod,
  openAccrual,
  openRunsByAddress,
  progress,
  RUN_ROWS,
  RUNS_PATH,
  tableRows,
  test,
} from "../../support/charges-accrual.ts";
import { applyPreset } from "../../support/charges.ts";
import { expect, seedSession } from "../../support/scenario.ts";

// What the generation says when the answer is not a plain yes: no active plan, no runs, a list or a run that does not load, a run that
// does not exist, a preview that fails once, and the runs a person meets in the list (rejected, failed, completed with errors). Texts are
// the Spanish ones a person reads. Against the SIMULATED API.
const SEEDED_RUN = {
  withErrors: "00000000-0000-4000-8000-00000000a002",
  rejected: "00000000-0000-4000-8000-00000000a003",
  failed: "00000000-0000-4000-8000-00000000a004",
} as const;

async function openRun(page: Parameters<typeof applyPreset>[0], scenarioId: string, id: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await seedSession(page);
  await page.goto(`${RUNS_PATH}/${id}`);
}

test.describe("lists with nothing in them, and lists that do not load", () => {
  test("shows the empty state of the generation when no plan is active, with the way to the plans", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId, "charges-empty");

    await expect(page.getByText("No hay planes activos")).toBeVisible();
    await page.getByRole("button", { name: "Ir a los planes de cobro" }).click();
    await expect(page).toHaveURL(/\/charges\/plans$/);
  });

  test("shows the empty state of the runs with its way forward, which opens the generation", async ({ page, scenarioId }) => {
    await openRunsByAddress(page, scenarioId, "charges-accrual-empty");

    await expect(page.getByText("Todavía no hay ejecuciones")).toBeVisible();
    await page.getByRole("button", { name: "Generar cargos" }).last().click();

    await expect(page).toHaveURL(/\/charges\/accrual$/);
  });

  test("shows the error state of the runs with the trace id when they fail, and Retry loads them", async ({ page, scenarioId }) => {
    // The page retries a read twice by itself (1 s and 2 s), and the simulator fails exactly those three reads.
    test.setTimeout(60_000);
    await openRunsByAddress(page, scenarioId, "charges-server-error");

    await expect(page.getByText("No se pudieron cargar las ejecuciones")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).first().click();

    await expect(page.getByText("No se pudieron cargar las ejecuciones")).toBeHidden();
    await expect(page.getByRole("link", { name: "2026-04" })).toBeVisible();
  });

  test("shows the error state of a run that fails to load, and Retry loads it", async ({ page, scenarioId }) => {
    test.setTimeout(60_000);
    await openRun(page, scenarioId, SEEDED_RUN.withErrors, "charges-server-error");

    await expect(page.getByText("No se pudo cargar la ejecución")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByRole("heading", { level: 2, name: "Ejecución de Primaria 2026, período 2026-02" })).toBeVisible();
  });

  test("tells a run that does not exist as not found, with a way back", async ({ page, scenarioId }) => {
    test.setTimeout(60_000);
    await openRun(page, scenarioId, "00000000-0000-4000-8000-000000000001");

    await expect(page.getByText("No se pudo cargar la ejecución")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole("link", { name: "Volver a las ejecuciones" })).toBeVisible();
  });

  test("fails the first preview with the trace id, and Retry shows the summary", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId, "charges-accrual-preview-error");
    await choosePlanAndPeriod(page);

    await page.getByRole("button", { name: "Calcular vista previa" }).click();

    await expect(page.getByText("No se pudo calcular la vista previa")).toBeVisible();
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();
    await page.getByRole("button", { name: "Reintentar" }).click();
    await expect(page.getByRole("region", { name: "Resumen de la vista previa" })).toBeVisible();
  });
});

test.describe("the runs of the seed, in each state a person meets", () => {
  test("a run completed with errors shows its counters, its rows and what to correct", async ({ page, scenarioId }) => {
    await openRun(page, scenarioId, SEEDED_RUN.withErrors);

    await expect(progress(page)).toContainText("La ejecución terminó con errores: se crearon 9 cargos y 3 renglones quedaron con error.");
    await expect(page.getByText(/Revise los renglones con error/)).toBeVisible();
    const rows = tableRows(page, RUN_ROWS);
    await expect(rows).toHaveCount(12);
    await expect(rows.filter({ hasText: "Sin tasa de impuesto" })).toContainText("El concepto paga impuesto pero no tiene tasa asignada.");
    await expect(page.getByRole("button", { name: "Calcular de nuevo" })).toBeVisible();
  });

  test("a rejected run says why and that nothing was written, and has no rows", async ({ page, scenarioId }) => {
    await openRun(page, scenarioId, SEEDED_RUN.rejected);

    await expect(progress(page)).toContainText("La ejecución se rechazó y no se escribió nada en el libro mayor.");
    await expect(page.getByText(/Los datos cambiaron desde la vista previa/)).toBeVisible();
    await expect(page.getByText("Esta ejecución no tiene renglones")).toBeVisible();
  });

  test("a failed run says its retries ran out, in readable text and not as a code", async ({ page, scenarioId }) => {
    await openRun(page, scenarioId, SEEDED_RUN.failed);

    await expect(progress(page)).toContainText("La ejecución falló y no se completó.");
    await expect(page.getByText(/El sistema agotó sus reintentos/)).toBeVisible();
    await expect(page.getByText("retries-exhausted")).toHaveCount(0);
  });
});

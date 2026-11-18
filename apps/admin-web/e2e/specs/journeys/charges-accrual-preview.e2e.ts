import {
  ACCRUAL_PATH,
  ACCRUED_PERIOD,
  ACTIVE_PLAN_NAME,
  choosePlanAndPeriod,
  openAccrual,
  PREVIEW_ROWS,
  previewPeriod,
  SEEDED_PLANS,
  tableRows,
  test,
} from "../../support/charges-accrual.ts";
import { expect, recordedCalls } from "../../support/scenario.ts";

// The preview of a period: the person chooses an active plan and one of the periods the server lists (never a free date), asks for the
// preview and reads what would happen with nothing written: counts, totals exactly as the server sent them, the errors by code in
// readable text, a row for each student and concept, and what blocks the generation. Against the SIMULATED API, which fixes the students,
// the prices and the errors by position: the real rules are the backend's.
test.describe("the preview of a period", () => {
  test("opens from the navigation, offers only the active plans and the periods of the plan, and writes the choice in the address", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId);

    await expect(page.getByLabel(/^Plan de cobro/).getByRole("option")).toHaveText(["Elija un plan", ACTIVE_PLAN_NAME]);
    await expect(page.getByLabel(/^Período/)).toBeDisabled();
    await expect(page.getByRole("button", { name: "Calcular vista previa" })).toBeDisabled();
    await choosePlanAndPeriod(page);

    await expect(page).toHaveURL(new RegExp(`planId=${SEEDED_PLANS.active}`));
    await expect(page).toHaveURL(/periodStart=2026-05-01/);
    // The placeholder and the eleven months the plan accrues, from January to November.
    await expect(page.getByLabel(/^Período/).getByRole("option")).toHaveCount(12);
    await expect(page.getByLabel(/^Período/).getByRole("option", { name: /2026-01.*Última ejecución: Completada/ })).toHaveCount(1);
    await expect(page.getByRole("button", { name: "Calcular vista previa" })).toBeEnabled();
  });

  test("shows the summary, the totals as the server sent them, the errors by code and a row for each student and concept", async ({ page, request, scenarioId }) => {
    await openAccrual(page, scenarioId);

    await previewPeriod(page);

    const summary = page.getByRole("region", { name: "Resumen de la vista previa" });
    await expect(summary).toContainText("Primaria 2026, período 2026-05");
    await expect(summary.getByText("Cargos por crear", { exact: true })).toBeVisible();
    await expect(summary.getByText("15,525.00 lempiras hondureños")).toBeVisible();
    await expect(summary.getByText("Sin precio vigente: 1 renglón.")).toBeVisible();
    await expect(summary.getByText("Precio ambiguo: 1 renglón.")).toBeVisible();
    await expect(summary.getByText("Sin tasa de impuesto: 1 renglón.")).toBeVisible();
    const rows = tableRows(page, PREVIEW_ROWS);
    await expect(rows).toHaveCount(12);
    await expect(rows.filter({ hasText: "Se creará" })).toHaveCount(9);
    const sent = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.endsWith("/accrual-preview"));
    expect(sent).toHaveLength(1);
  });

  test("filters the rows of the preview by error, and back to all of them", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId);
    await previewPeriod(page);
    const rows = tableRows(page, PREVIEW_ROWS);

    await page.getByLabel("Mostrar").selectOption({ label: "Solo con error" });
    await expect(rows).toHaveCount(3);
    await page.getByLabel("Mostrar").selectOption({ label: "Sin precio vigente" });
    await expect(rows).toHaveCount(1);
    await page.getByLabel("Mostrar").selectOption({ label: "Todos los renglones" });

    await expect(rows).toHaveCount(12);
  });

  test("reads the plan and the period from the address, so a link opens the page ready to preview", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId);

    await page.goto(`${ACCRUAL_PATH}?planId=${SEEDED_PLANS.active}&periodStart=2026-05-01`);

    await expect(page.getByLabel(/^Plan de cobro/)).toHaveValue(SEEDED_PLANS.active);
    await expect(page.getByLabel(/^Período/)).toHaveValue("2026-05-01", { timeout: 20_000 });
    await expect(page.getByRole("button", { name: "Calcular vista previa" })).toBeEnabled();
  });

  test("says there is nothing to confirm for a period that is already accrued, and does not allow the confirmation", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId);

    await previewPeriod(page, ACCRUED_PERIOD);

    await expect(page.getByText(/no hay cargos nuevos que crear/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Confirmar generación" })).toBeDisabled();
  });

  test("explains what blocks a generation and does not allow it", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId, "charges-accrual-blocked");

    await previewPeriod(page);

    await expect(page.getByText("La generación no se puede confirmar todavía")).toBeVisible();
    await expect(page.getByText(/Falta configurar el mapa de cuentas contables/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Confirmar generación" })).toBeDisabled();
  });

  test("tells a person without the permission plainly when the preview is refused with 403", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId, "charges-accrual-forbidden");
    await choosePlanAndPeriod(page);

    await page.getByRole("button", { name: "Calcular vista previa" }).click();

    await expect(page.getByText("No se pudo calcular la vista previa")).toBeVisible();
    await expect(page.getByText(/No tiene permiso para realizar esta acción/)).toBeVisible();
  });
});

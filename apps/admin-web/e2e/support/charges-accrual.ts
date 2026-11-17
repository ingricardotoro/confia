import type { Locator, Page } from "@playwright/test";
import { applyPreset, dialog, test } from "./charges.ts";
import { expect, seedSession } from "./scenario.ts";

export { applyPreset, dialog, test };
export { SEEDED_PLANS } from "./charges.ts";

/** The generation of charges and its runs (charges-accrual cut): the paths, and what the seeded active plan lists. */
export const ACCRUAL_PATH = "/charges/accrual";
export const RUNS_PATH = "/charges/accrual/runs";
export const ACTIVE_PLAN_NAME = "Primaria 2026";
/** A period of the seeded active plan that no run has accrued: May 2026, due on the fifth. */
export const FRESH_PERIOD = "2026-05, vence el 5 may 2026";
/** A period of the seeded active plan that a run already accrued completely: January 2026. */
export const ACCRUED_PERIOD = "2026-01, vence el 5 ene 2026. Última ejecución: Completada";
/** The longest a run takes to reach a terminal state against the simulator: three reads, two seconds apart. */
export const TERMINAL = { timeout: 30_000 } as const;

/** Signs in with the seeded session and opens the generation page from the navigation («Generación de cargos» in Finanzas). */
export async function openAccrual(page: Page, scenarioId: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await seedSession(page);
  await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Generación de cargos" }).click();
  await expect(page).toHaveURL(/\/charges\/accrual$/);
  await expect(page.getByRole("heading", { level: 1, name: "Cobros" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Generación de cargos" })).toBeVisible();
}

/** Signs in and opens the list of runs by its address, with a preset already applied when one is given. */
export async function openRunsByAddress(page: Page, scenarioId: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await seedSession(page);
  await page.goto(RUNS_PATH);
}

/** Chooses the seeded active plan and one of its periods, after the list of periods has loaded. */
export async function choosePlanAndPeriod(page: Page, period: string = FRESH_PERIOD): Promise<void> {
  await page.getByLabel(/^Plan de cobro/).selectOption({ label: ACTIVE_PLAN_NAME });
  await expect(page.getByLabel(/^Período/)).toBeEnabled({ timeout: 20_000 });
  await page.getByLabel(/^Período/).selectOption({ label: period });
}

/** Chooses, asks for the preview and waits for its summary. */
export async function previewPeriod(page: Page, period: string = FRESH_PERIOD): Promise<void> {
  await choosePlanAndPeriod(page, period);
  await page.getByRole("button", { name: "Calcular vista previa" }).click();
  await expect(page.getByRole("region", { name: "Resumen de la vista previa" })).toBeVisible({ timeout: 20_000 });
}

/** Opens the confirmation dialog of the preview on screen. */
export async function openConfirmation(page: Page): Promise<void> {
  await page.getByRole("button", { name: "Confirmar generación" }).click();
  await expect(dialog(page)).toBeVisible();
}

/** The live region that tells the progress of a run. */
export const progress = (page: Page): Locator => page.locator('[aria-live="polite"]').filter({ hasText: /La ejecución/ }).first();

/** The rows of the table of the preview, of the items of a run or of the runs, by the name of the table. */
export const tableRows = (page: Page, name: string): Locator => page.getByRole("table", { name }).locator("tbody tr");

export const PREVIEW_ROWS = "Renglones de la vista previa: un estudiante y un concepto por renglón";
export const RUN_ROWS = "Renglones de la ejecución: un estudiante y un concepto por renglón";
export const RUNS_TABLE = "Ejecuciones de generación de cargos";

import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { A11Y_TAGS, partitionViolations } from "@confia/config/a11y";
import { dialog, test } from "../../support/organization.ts";
import { clickNavigationLink } from "../../support/navigation.ts";
import { expect, recordedCalls, seedSession } from "../../support/scenario.ts";

// The awards of scholarships from the person who requests and the person who decides. The person requests an award for a student and is refused when
// they try to approve it themselves; they approve the one another person requested; and the simulator of charges then shows the scholarship on the
// charge of that student, with the base, the benefit, the net base and the tax the server computed. The screens do no money arithmetic: every figure
// they show is one the SIMULATED API sent. Against the SIMULATED API, which repeats only the rules these journeys need.
const SAVE = { timeout: 15_000 } as const;
const ACTIVE_PLAN = "Primaria 2026";
const MAY = "2026-05, vence el 5 may 2026";

async function expectNoBlockingViolations(page: Page): Promise<void> {
  const outcome = await new AxeBuilder({ page }).withTags([...A11Y_TAGS]).analyze();
  expect(partitionViolations(outcome.violations, outcome.incomplete).blocking.map((violation) => violation.id)).toEqual([]);
}

/** Simulates the first student on the chosen plan and period, and waits for the table the server answers. */
async function simulateFirstStudent(page: Page): Promise<void> {
  await page.getByRole("combobox", { name: /^Estudiante/ }).fill("E-000001");
  await page.getByRole("option", { name: /E-000001/ }).click();
  await page.getByLabel(/^Plan de cobro/).selectOption({ label: ACTIVE_PLAN });
  await expect(page.getByLabel(/^Período/)).toBeEnabled({ timeout: 20_000 });
  await page.getByLabel(/^Período/).selectOption({ label: MAY });
  await page.getByRole("button", { name: "Simular" }).click();
  await expect(page.getByRole("table", { name: "Resultado de la simulación por concepto" })).toBeVisible(SAVE);
}

test.describe("the awards of scholarships", () => {
  test("requests an award, is refused when approving their own, approves another person's, and the simulator then shows the scholarship on the charge", async ({ page, request, scenarioId }) => {
    await seedSession(page);

    // Before anything is approved, the simulator shows the charge with no scholarship.
    await clickNavigationLink(page, "Simulador de cargos");
    await expect(page).toHaveURL(/\/scholarships\/simulator$/);
    await expect(page.getByRole("heading", { level: 2, name: "Simulador de cargos" })).toBeVisible();
    await simulateFirstStudent(page);
    const before = page.getByRole("table", { name: "Resultado de la simulación por concepto" });
    await expect(before.getByText("Sin beca")).toBeVisible();
    await expect(before.getByText("L 1,500.00").first()).toBeVisible();
    await expectNoBlockingViolations(page);

    // The person requests an award for another student: it is born pending.
    await clickNavigationLink(page, "Otorgamientos");
    await expect(page).toHaveURL(/\/scholarships\/awards$/);
    await expect(page.getByRole("heading", { level: 2, name: "Otorgamientos" })).toBeVisible();
    await page.getByRole("button", { name: "Solicitar otorgamiento" }).click();
    await expect(dialog(page)).toBeVisible();
    await dialog(page).getByRole("combobox", { name: /^Estudiante/ }).fill("E-000002");
    await dialog(page).getByRole("option", { name: /E-000002/ }).click();
    await dialog(page).getByLabel(/^Beca/).selectOption({ label: "BECA25 · Beca de excelencia" });
    await dialog(page).getByLabel(/^Año lectivo/).selectOption({ label: "Año lectivo 2026" });
    await dialog(page).getByLabel(/^Vigente desde/).fill("2026-01-01");
    await dialog(page).getByLabel(/^Vigente hasta/).fill("2026-12-31");
    await dialog(page).getByRole("button", { name: "Solicitar otorgamiento" }).click();
    await expect(page.getByText(/Queda pendiente hasta que otra persona lo apruebe/)).toBeVisible(SAVE);
    expect((await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path === "/api/v1/scholarship-awards")).toHaveLength(1);

    // Approving what the person requested themselves is refused by the server, and told inside the dialog.
    await page.getByRole("row", { name: /E-000002.*Pendiente de aprobación/ }).first().getByRole("link").click();
    await expect(page.getByRole("heading", { level: 2, name: "Otorgamiento del estudiante E-000002" })).toBeVisible(SAVE);
    await page.getByRole("button", { name: "Aprobar" }).click();
    await dialog(page).getByRole("button", { name: "Aprobar otorgamiento" }).click();
    await expect(dialog(page).getByText(/Quien solicitó el otorgamiento no puede aprobarlo ni rechazarlo/)).toBeVisible(SAVE);
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(page.getByText("Pendiente de aprobación").first()).toBeVisible();

    // The award another person requested for the first student is approved.
    await clickNavigationLink(page, "Otorgamientos");
    await page.getByRole("row", { name: /E-000001.*Pendiente de aprobación/ }).first().getByRole("link").click();
    await expect(page.getByRole("heading", { level: 2, name: "Otorgamiento del estudiante E-000001" })).toBeVisible(SAVE);
    await page.getByRole("button", { name: "Aprobar" }).click();
    await dialog(page).getByRole("button", { name: "Aprobar otorgamiento" }).click();
    await expect(page.getByText(/El otorgamiento quedó aprobado/)).toBeVisible(SAVE);
    await expect(page.getByRole("button", { name: "Revocar" })).toBeVisible(SAVE);
    const approvals = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.endsWith("/approve"));
    expect(approvals).toHaveLength(2);
    await expectNoBlockingViolations(page);

    // The simulator now shows the scholarship the server applied: base 1,500.00, benefit 375.00, net base 1,125.00 and the tax on the net base.
    await clickNavigationLink(page, "Simulador de cargos");
    await simulateFirstStudent(page);
    const after = page.getByRole("table", { name: "Resultado de la simulación por concepto" });
    const row = after.getByRole("row", { name: /BECA25/ });
    await expect(row.getByText("L 1,500.00")).toBeVisible();
    await expect(row.getByText("L 375.00")).toBeVisible();
    await expect(row.getByText("L 1,125.00")).toBeVisible();
    await expect(row.getByText("L 168.75")).toBeVisible();
    await expect(row.getByText("L 1,293.75")).toBeVisible();
    await expectNoBlockingViolations(page);
  });
});

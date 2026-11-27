import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { A11Y_TAGS, partitionViolations } from "@confia/config/a11y";
import { dialog, test } from "../../support/organization.ts";
import { clickNavigationLink } from "../../support/navigation.ts";
import { expect, recordedCalls, seedSession } from "../../support/scenario.ts";

// The discounts from the person who defines them: the person creates a campaign discount that stacks, sees an agreement discount that is already in use
// with its type and value locked, and is refused when shortening the validity would leave an assignment outside. The screens do no money arithmetic:
// every figure they show is one the SIMULATED API sent. Against the SIMULATED API, which repeats only the rules these journeys need.
const SAVE = { timeout: 15_000 } as const;

async function expectNoBlockingViolations(page: Page): Promise<void> {
  const outcome = await new AxeBuilder({ page }).withTags([...A11Y_TAGS]).analyze();
  expect(partitionViolations(outcome.violations, outcome.incomplete).blocking.map((violation) => violation.id)).toEqual([]);
}

test.describe("the discounts", () => {
  test("creates a campaign discount that stacks, and shows an agreement in use with its benefit locked", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Descuentos");
    await expect(page).toHaveURL(/\/scholarships\/discounts$/);
    await expect(page.getByRole("heading", { level: 2, name: "Descuentos" })).toBeVisible();
    const table = page.getByRole("table", { name: "Descuentos de la institución" });
    await expect(table.getByRole("row", { name: /Campaña de matrícula/ })).toBeVisible(SAVE);
    await expect(table.getByRole("row", { name: /Convenio con la empresa.*No acumulable/ })).toBeVisible();
    await expectNoBlockingViolations(page);

    // The person creates a campaign discount.
    await page.getByRole("button", { name: "Crear descuento" }).click();
    await expect(dialog(page)).toBeVisible();
    await dialog(page).getByLabel(/^Código/).fill("BECAPAGO");
    await dialog(page).getByLabel(/^Nombre/).fill("Pago anual adelantado");
    await dialog(page).getByLabel(/^Clase/).selectOption({ label: "Campaña" });
    await dialog(page).getByLabel(/^Tipo de beneficio/).selectOption({ label: "Porcentaje" });
    await dialog(page).getByLabel(/^Porcentaje/).fill("15");
    await dialog(page).getByLabel(/^Vigente desde/).fill("2026-01-01");
    await dialog(page).getByLabel(/^Vigente hasta/).fill("2026-12-31");
    await dialog(page).getByRole("checkbox", { name: "Acumulable con los demás beneficios" }).check();
    await dialog(page).getByRole("checkbox", { name: /Colegiatura/ }).check();
    await dialog(page).getByRole("button", { name: "Guardar" }).click();
    await expect(page.getByText("Se creó el registro «Pago anual adelantado».")).toBeVisible(SAVE);
    await expect(table.getByRole("row", { name: /Pago anual adelantado.*Acumulable/ })).toBeVisible(SAVE);
    expect((await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path === "/api/v1/scholarships/discounts")).toHaveLength(1);

    // The agreement has an approved assignment: its type and value are read only, and the form says why.
    await page.getByRole("button", { name: "Editar Convenio con la empresa" }).click();
    await expect(dialog(page).getByText(/ya está en uso/)).toBeVisible();
    await expect(dialog(page).getByLabel(/^Porcentaje/)).toHaveAttribute("readonly", "");
    await expect(dialog(page).getByRole("combobox", { name: /Tipo de beneficio/ })).toHaveCount(0);
    await expectNoBlockingViolations(page);

    // Shortening the validity would leave the approved assignment outside it: the server refuses and the form tells under the end.
    await dialog(page).getByLabel(/^Vigente hasta/).fill("2026-03-31");
    await dialog(page).getByRole("button", { name: "Guardar" }).click();
    await expect(dialog(page).getByText(/quedarían fuera de esta vigencia/).first()).toBeVisible(SAVE);
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();

    // Deactivating takes it out of the offer; nothing is deleted.
    await page.getByRole("button", { name: "Desactivar Campaña de matrícula" }).click();
    await expect(page.getByText("Se desactivó «Campaña de matrícula».")).toBeVisible(SAVE);
    await expect(table.getByRole("row", { name: /Campaña de matrícula.*Inactivo/ })).toBeVisible();
    await expectNoBlockingViolations(page);
  });
});

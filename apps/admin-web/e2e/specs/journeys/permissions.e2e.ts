import type { Page } from "@playwright/test";
import { studentId } from "../../mock-api/students-seed.ts";
import { applyPreset, test } from "../../support/organization.ts";
import { expect, seedSession } from "../../support/scenario.ts";

// The permissions of the session: the menu, the pages and the actions follow what the current session lists. The simulated API lists
// the permissions of a role per scenario: the auditor only reads, and another role writes everything except voiding a charge. The panel
// only hides; the server decides each request, which this simulated API does not model.
const STUDENT = studentId(1);

/** Opens a group of the side navigation unless it is open already (the group of the current route starts open). */
async function openGroup(page: Page, name: string): Promise<void> {
  const group = page.getByRole("navigation", { name: "Navegación principal" }).getByRole("button", { name });
  if ((await group.getAttribute("aria-expanded")) !== "true") await group.click();
}

test.describe("a restricted role", () => {
  test("an auditor sees the lists, no write action, no generation of charges, and 'Sin acceso' on the page of generation", async ({ page, scenarioId }) => {
    await applyPreset(page, scenarioId, "role-auditor");
    await seedSession(page);

    await page.goto("/catalog");
    await expect(page.getByRole("heading", { level: 2, name: "Conceptos de pago" })).toBeVisible();
    await expect(page.getByRole("table", { name: "Conceptos de pago de la institución" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Nuevo concepto" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Editar / })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^(Desactivar|Activar) / })).toHaveCount(0);

    const navigation = page.getByRole("navigation", { name: "Navegación principal" });
    await openGroup(page, "Finanzas");
    await expect(navigation.getByRole("link", { name: "Catálogo" })).toBeVisible();
    await expect(navigation.getByRole("link", { name: "Generación de cargos" })).toHaveCount(0);

    await page.goto("/charges/accrual");
    await expect(page.getByRole("heading", { level: 1, name: "Sin acceso" })).toBeVisible();
    await page.getByRole("link", { name: "Volver al inicio" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeVisible();
  });

  test("a role that cannot void charges does not see 'Anular cargo', and still sees the create actions", async ({ page, scenarioId }) => {
    await applyPreset(page, scenarioId, "role-without-void");
    await seedSession(page);

    await page.goto(`/charges/student-charges?studentId=${STUDENT}`);
    const table = page.getByRole("table", { name: "Cargos del estudiante" });
    await expect(table.getByRole("row")).toHaveCount(4);
    await expect(page.getByRole("button", { name: /^Anular el cargo/ })).toHaveCount(0);

    await page.goto("/catalog");
    await expect(page.getByRole("button", { name: "Nuevo concepto" })).toBeVisible();
  });
});

test.describe("the cashbox by role", () => {
  const pendingReport = async (page: Page) => {
    await page.goto("/cashbox/sessions?status=pending_approval");
    await page.getByRole("table", { name: "Sesiones de caja" }).getByRole("row").nth(1).getByRole("link").click();
    await expect(page).toHaveURL(/\/cashbox\/sessions\/[0-9a-f-]+$/);
    await expect(page.getByText("Pendiente de aprobación").first()).toBeVisible();
  };

  test("a cashier sees 'Mi caja' and is not offered the approval of a close", async ({ page, scenarioId }) => {
    await applyPreset(page, scenarioId, "role-cashier");
    await seedSession(page);

    const navigation = page.getByRole("navigation", { name: "Navegación principal" });
    await openGroup(page, "Finanzas");
    await expect(navigation.getByRole("link", { name: "Mi caja" })).toBeVisible();

    await pendingReport(page);
    await expect(page.getByRole("button", { name: "Aprobar cierre" })).toHaveCount(0);
  });

  test("an administrator sees 'Sesiones de caja' and the approval, and not 'Mi caja'", async ({ page, scenarioId }) => {
    await applyPreset(page, scenarioId, "role-administrator");
    await seedSession(page);

    const navigation = page.getByRole("navigation", { name: "Navegación principal" });
    await openGroup(page, "Finanzas");
    await expect(navigation.getByRole("link", { name: "Sesiones de caja" })).toBeVisible();
    await expect(navigation.getByRole("link", { name: "Mi caja" })).toHaveCount(0);

    await page.goto("/cashbox");
    await expect(page.getByRole("heading", { level: 1, name: "Sin acceso" })).toBeVisible();

    await pendingReport(page);
    await expect(page.getByRole("button", { name: "Aprobar cierre" })).toBeVisible();
  });
});

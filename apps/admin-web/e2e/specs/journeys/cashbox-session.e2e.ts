import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { A11Y_TAGS, partitionViolations } from "@confia/config/a11y";
import { dialog, test } from "../../support/organization.ts";
import { isolateDialogFromPage } from "../../support/a11y-gate.ts";
import { clickNavigationLink } from "../../support/navigation.ts";
import { expect, recordedCalls, seedSession } from "../../support/scenario.ts";

// The cashbox from the cashier's side: open the session with a float, register a deposit and a withdrawal, close with a counted amount that differs
// (the server asks for the reason and the screen shows it under its field), see the difference the server computed and the close that awaits its
// approval, and the refusal of the approval by the very cashier; then, from the list, the approval of a close another cashier made. The screen does no
// money arithmetic: every figure it shows is one the SIMULATED API sent. Against the SIMULATED API, which repeats only the rules these journeys need.
const SAVE = { timeout: 15_000 } as const;
const writes = async (page: Page, request: Parameters<typeof recordedCalls>[0], scenarioId: string, suffix: string) =>
  (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.endsWith(suffix));

/**
 * The accessibility gate over the page as it stands: axe with the project policy, and nothing that blocks. With a dialog open, the page behind is
 * hidden first, as the gate does (see isolateDialogFromPage): it is inert anyway, and it leaves the page hidden, so only the last step may ask for it.
 */
async function expectNoBlockingViolations(page: Page, options: { readonly dialogOpen?: boolean } = {}): Promise<void> {
  if (options.dialogOpen === true) await isolateDialogFromPage(page);
  const outcome = await new AxeBuilder({ page }).withTags([...A11Y_TAGS]).analyze();
  expect(partitionViolations(outcome.violations, outcome.incomplete).blocking.map((violation) => violation.id)).toEqual([]);
}

test.describe("the cashier's cashbox", () => {
  test("opens a session, registers a deposit and a withdrawal, closes with a difference that needs its reason, and sees the close await its approval", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Mi caja");

    await expect(page).toHaveURL(/\/cashbox$/);
    await expect(page.getByRole("heading", { level: 1, name: "Caja" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Mi caja" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Abrir caja" })).toBeVisible();
    await expectNoBlockingViolations(page);

    // Open: a float that does not hold is refused before sending, and a valid one opens the session.
    await page.getByLabel(/^Fondo inicial/).fill("500.456");
    await page.getByRole("button", { name: "Abrir caja" }).click();
    await expect(page.getByText(/hasta dos decimales/).first()).toBeVisible();
    expect(await writes(page, request, scenarioId, "/sessions")).toHaveLength(0);
    await page.getByLabel(/^Fondo inicial/).fill("500.00");
    await page.getByRole("button", { name: "Abrir caja" }).click();
    await expect(page.getByText("Su caja está abierta.")).toBeVisible(SAVE);
    await expect(page.getByRole("button", { name: "Cerrar caja" })).toBeVisible();
    expect(await writes(page, request, scenarioId, "/sessions")).toHaveLength(1);

    // A deposit and a withdrawal: the expected cash is the server's figure, read again after each.
    await page.getByRole("button", { name: "Registrar depósito" }).first().click();
    await dialog(page).getByLabel(/^Importe/).fill("100.00");
    await dialog(page).getByRole("button", { name: "Registrar depósito" }).click();
    await expect(dialog(page)).toBeHidden(SAVE);
    await expect(page.getByText("Se registró el depósito.")).toBeVisible();
    await page.getByRole("button", { name: "Registrar retiro" }).click();
    await dialog(page).getByLabel(/^Importe/).fill("50.00");
    await dialog(page).getByRole("button", { name: "Registrar retiro" }).click();
    await expect(dialog(page)).toBeHidden(SAVE);
    const movements = page.getByRole("table", { name: "Movimientos de la caja" });
    await expect(movements.getByRole("row")).toHaveCount(3);
    await expect(movements.getByRole("row").filter({ hasText: "Retiro" })).toHaveCount(1);
    await expect(page.getByText("550.00").first()).toBeVisible();
    expect(await writes(page, request, scenarioId, "/movements")).toHaveLength(2);
    await expectNoBlockingViolations(page);

    // Close: the expected cash beside the count, no difference shown; the server asks for the reason and it lands under its field.
    await page.getByRole("button", { name: "Cerrar caja" }).click();
    await expect(dialog(page).getByText("550.00").first()).toBeVisible();
    await expect(dialog(page).getByText(/^Diferencia/)).toHaveCount(0);
    await dialog(page).getByLabel(/^Efectivo contado/).fill("540.00");
    await dialog(page).getByRole("button", { name: "Cerrar caja" }).click();
    await expect(dialog(page).getByLabel(/^Motivo de la diferencia/)).toHaveAccessibleDescription(/escriba el motivo de la diferencia/, SAVE);
    await expectNoBlockingViolations(page);
    await dialog(page).getByLabel(/^Motivo de la diferencia/).fill("Billete de cien contado dos veces por error");
    await dialog(page).getByRole("button", { name: "Cerrar caja" }).click();

    await expect(dialog(page)).toBeHidden(SAVE);
    await expect(page.getByText(/La caja se cerró con diferencia/)).toBeVisible();
    await expect(page.getByText(/10\.00/).first()).toBeVisible();
    expect(await writes(page, request, scenarioId, "/close")).toHaveLength(2);

    // The report of the close, and the refusal of the approval by the cashier who closed it.
    await page.getByRole("button", { name: "Ver reporte de cierre" }).click();
    await expect(page).toHaveURL(/\/cashbox\/sessions\/[0-9a-f-]+$/);
    await expect(page.getByText("Pendiente de aprobación").first()).toBeVisible();
    await expect(page.getByText("Firma del cajero")).toBeHidden();
    await page.getByRole("button", { name: "Aprobar cierre" }).click();
    await dialog(page).getByLabel(/^Nota de la aprobación/).fill("Revisado");
    await dialog(page).getByRole("button", { name: "Aprobar cierre" }).click();
    await expect(dialog(page).getByText("Quien cerró la caja no puede aprobar su propio cierre.")).toBeVisible(SAVE);
    await expectNoBlockingViolations(page, { dialogOpen: true });
  });
});

test.describe("the sessions of the cashbox", () => {
  test("lists the sessions, filters by status on the server and approves a close another cashier made", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Sesiones de caja");

    await expect(page).toHaveURL(/\/cashbox\/sessions$/);
    const rows = page.getByRole("table", { name: "Sesiones de caja" }).getByRole("row");
    await expect(rows).toHaveCount(3);
    await expectNoBlockingViolations(page);

    await page.getByRole("combobox", { name: "Estado" }).selectOption({ label: "Pendiente de aprobación" });
    await expect(page).toHaveURL(/status=pending_approval/);
    await expect(rows).toHaveCount(2);

    await rows.nth(1).getByRole("link").click();
    await expect(page).toHaveURL(/\/cashbox\/sessions\/[0-9a-f-]+$/);
    await expect(page.getByText("Billete de cien contado dos veces por error")).toBeVisible();
    await page.getByRole("button", { name: "Aprobar cierre" }).click();
    await dialog(page).getByRole("button", { name: "Aprobar cierre" }).click();
    await expect(dialog(page).getByText("Este dato es obligatorio.").first()).toBeVisible();
    expect(await writes(page, request, scenarioId, "/approve")).toHaveLength(0);
    await dialog(page).getByLabel(/^Nota de la aprobación/).fill("Revisado con el cajero");
    await dialog(page).getByRole("button", { name: "Aprobar cierre" }).click();

    await expect(dialog(page)).toBeHidden(SAVE);
    await expect(page.getByText("El cierre quedó aprobado y la sesión está cerrada.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Aprobar cierre" })).toHaveCount(0);
    await expect(page.getByText("Nota de la aprobación")).toBeVisible();
    expect(await writes(page, request, scenarioId, "/approve")).toHaveLength(1);
  });
});

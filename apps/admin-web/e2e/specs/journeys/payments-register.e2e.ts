import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { A11Y_TAGS, partitionViolations } from "@confia/config/a11y";
import { dialog, test } from "../../support/organization.ts";
import { isolateDialogFromPage } from "../../support/a11y-gate.ts";
import { clickNavigationLink } from "../../support/navigation.ts";
import { expect, recordedCalls, seedSession } from "../../support/scenario.ts";

// Payments from the cashier and the approver. The cashier opens the cashbox, registers 1,500.00 in cash for a student that owes three charges, sees the
// allocations and the remainder the server computed, and sees the expected cash of the cashbox rise; with no open cashbox the same payment is not offered
// and the way to the cashbox is. The approver confirms a transfer another person declared, is refused when it is their own, and rejects one with its
// reason. The screens do no money arithmetic: every figure they show is one the SIMULATED API sent. Against the SIMULATED API, which repeats only the
// rules these journeys need.
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

/** Searches the first seeded student on the server and chooses them, which reads what they owe. */
async function chooseStudent(page: Page): Promise<void> {
  await page.getByRole("combobox", { name: "Estudiante" }).fill("Aguilar");
  await page.getByRole("option", { name: /E-000001/ }).click();
  await expect(page.getByRole("table", { name: /Cargos vigentes del estudiante/ })).toBeVisible(SAVE);
}

test.describe("the cashier registering a payment", () => {
  test("opens the cashbox, registers a cash payment, sees the allocations and the remainder the server computed, and the expected cash rise", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Mi caja");
    await page.getByLabel(/^Fondo inicial/).fill("500.00");
    await page.getByRole("button", { name: "Abrir caja" }).click();
    await expect(page.getByText("Su caja está abierta.")).toBeVisible(SAVE);

    await clickNavigationLink(page, "Cobrar");
    await expect(page).toHaveURL(/\/payments\/register$/);
    await expect(page.getByRole("heading", { level: 1, name: "Pagos" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Registrar pago" })).toBeVisible();
    await expect(page.getByText("No tiene una caja abierta")).toHaveCount(0);
    await chooseStudent(page);
    // What the student owes, as the server says it: three charges, the oldest due date first.
    const charges = page.getByRole("table", { name: /Cargos vigentes del estudiante/ });
    await expect(charges.getByRole("row")).toHaveCount(4);
    await expect(charges.getByRole("row").nth(1)).toContainText("2026-09");
    await expectNoBlockingViolations(page);

    // An amount that does not hold is refused before sending.
    await page.getByLabel(/^Importe recibido/).fill("1500.456");
    await page.getByRole("button", { name: "Registrar pago" }).click();
    await expect(page.getByText(/hasta dos decimales/).first()).toBeVisible();
    expect(await writes(page, request, scenarioId, "/payments")).toHaveLength(0);

    // A double click registers one payment, with its own Idempotency-Key.
    await page.getByLabel(/^Importe recibido/).fill("1500.00");
    await page.getByRole("button", { name: "Registrar pago" }).dblclick();
    await expect(page.getByRole("heading", { name: "Pago registrado" })).toBeVisible(SAVE);
    const sent = await writes(page, request, scenarioId, "/payments");
    expect(sent).toHaveLength(1);
    const allocations = page.getByRole("table", { name: /Cargos a los que se aplicó el pago/ });
    await expect(allocations.getByRole("row")).toHaveCount(3);
    await expect(allocations.getByRole("row").nth(1)).toContainText("1,150.00");
    await expect(allocations.getByRole("row").nth(2)).toContainText("350.00");
    await expectNoBlockingViolations(page);

    // The payment is in the list, and the expected cash of the cashbox is the server figure plus the cash: 500.00 + 1,500.00.
    await clickNavigationLink(page, "Mi caja");
    await expect(page.getByText("2,000.00").first()).toBeVisible(SAVE);
    await expect(page.getByRole("table", { name: "Movimientos de la caja" }).getByRole("row").filter({ hasText: "Cobro en efectivo" })).toHaveCount(1);
  });

  test("with no open cashbox says so, offers the way to it and sends nothing, and a transfer needs no cashbox", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Cobrar");
    await chooseStudent(page);

    await expect(page.getByText("No tiene una caja abierta")).toBeVisible(SAVE);
    await expect(page.getByRole("button", { name: "Registrar pago" })).toBeDisabled();
    await expectNoBlockingViolations(page);
    await page.getByLabel(/^Método de pago/).selectOption("BANK_TRANSFER");
    await expect(page.getByText("No tiene una caja abierta")).toHaveCount(0);
    await page.getByLabel(/^Importe recibido/).fill("500.00");
    await page.getByLabel(/^Referencia bancaria/).fill("FH-445566");
    await page.getByLabel(/^Banco/).fill("Banco Ficohsa");
    await page.getByRole("button", { name: "Registrar pago" }).click();
    await expect(page.getByRole("heading", { name: "Transferencia declarada" })).toBeVisible(SAVE);
    await expect(page.getByText(/No cambia el saldo del estudiante/)).toBeVisible();
    expect(await writes(page, request, scenarioId, "/payments")).toHaveLength(1);

    await page.getByRole("button", { name: "Registrar otro pago" }).click();
    await page.getByRole("combobox", { name: "Estudiante" }).fill("Aguilar");
    await page.getByRole("option", { name: /E-000001/ }).click();
    await page.getByLabel(/^Método de pago/).selectOption("CASH");
    await page.getByRole("button", { name: "Ir a Mi caja" }).click();
    await expect(page).toHaveURL(/\/cashbox$/);
  });
});

test.describe("the approver deciding declared transfers", () => {
  test("filters the list on the server, confirms a transfer another person declared and sees what the server applied", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Pagos");

    await expect(page).toHaveURL(/\/payments$/);
    const rows = page.getByRole("table", { name: "Pagos registrados" }).getByRole("row");
    await expect(rows).toHaveCount(4);
    await expectNoBlockingViolations(page);
    await page.getByRole("combobox", { name: "Estado" }).selectOption({ label: "Pendiente de confirmación" });
    await expect(page).toHaveURL(/status=declared/);
    await expect(rows).toHaveCount(3);

    await rows.filter({ hasText: "BA-001234" }).getByRole("link").click();
    await expect(page).toHaveURL(/\/payments\/[0-9a-f-]+$/);
    await expect(page.getByText(/Esta transferencia está pendiente de confirmación/)).toBeVisible();
    await expect(page.getByRole("table", { name: /Cargos a los que se aplicó el pago/ })).toHaveCount(0);
    await expectNoBlockingViolations(page);
    await page.getByRole("button", { name: "Confirmar" }).click();
    await expect(dialog(page).getByText("BA-001234")).toBeVisible();
    await dialog(page).getByRole("button", { name: "Confirmar transferencia" }).click();

    await expect(dialog(page)).toBeHidden(SAVE);
    await expect(page.getByText("La transferencia quedó confirmada y aplicada a los cargos.")).toBeVisible();
    const allocations = page.getByRole("table", { name: /Cargos a los que se aplicó el pago/ });
    await expect(allocations.getByRole("row")).toHaveCount(3);
    await expect(page.getByRole("button", { name: "Confirmar" })).toHaveCount(0);
    expect(await writes(page, request, scenarioId, "/confirm")).toHaveLength(1);
  });

  test("is refused when the transfer is their own, and rejects another with a reason that meets the bounds", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Pagos");
    const rows = page.getByRole("table", { name: "Pagos registrados" }).getByRole("row");

    // The transfer the person registered: the server refuses the confirmation, and the dialog says so.
    await rows.filter({ hasText: "BA-009876" }).getByRole("link").click();
    await page.getByRole("button", { name: "Confirmar" }).click();
    await dialog(page).getByRole("button", { name: "Confirmar transferencia" }).click();
    await expect(dialog(page).getByText(/Quien registró el pago no puede confirmarlo ni rechazarlo/)).toBeVisible(SAVE);
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();

    // Another transfer: a reason that is too short is refused before sending; a valid one rejects it and nothing is allocated.
    await clickNavigationLink(page, "Pagos");
    await rows.filter({ hasText: "BA-001234" }).getByRole("link").click();
    await page.getByRole("button", { name: "Rechazar" }).click();
    await dialog(page).getByLabel(/^Motivo del rechazo/).fill("Muy corto");
    await dialog(page).getByRole("button", { name: "Rechazar transferencia" }).click();
    await expect(dialog(page).getByText("Escriba de 20 a 500 caracteres.").first()).toBeVisible();
    expect(await writes(page, request, scenarioId, "/reject")).toHaveLength(0);
    await dialog(page).getByLabel(/^Motivo del rechazo/).fill("La referencia no aparece en el estado del banco");
    await dialog(page).getByRole("button", { name: "Rechazar transferencia" }).click();

    await expect(dialog(page)).toBeHidden(SAVE);
    await expect(page.getByText("La transferencia quedó rechazada. El saldo del estudiante no cambió.")).toBeVisible();
    await expect(page.getByText(/Esta transferencia fue rechazada/)).toBeVisible();
    await expect(page.getByText("La referencia no aparece en el estado del banco")).toBeVisible();
    expect(await writes(page, request, scenarioId, "/reject")).toHaveLength(1);
    await expectNoBlockingViolations(page);
  });
});

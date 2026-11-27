import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { A11Y_TAGS, partitionViolations } from "@confia/config/a11y";
import { dialog, test } from "../../support/organization.ts";
import { clickNavigationLink } from "../../support/navigation.ts";
import { expect, recordedCalls, seedSession } from "../../support/scenario.ts";

// The discounts from the person who defines them and the person who approves them. A campaign discount is born pending and applies to nothing until another
// person approves it (S7): the person who created it is not offered the decision, another discount waits for them to decide, and widening an approved one
// leaves a proposal that keeps the terms in force. An agreement discount that is already in use has its type, value and stackable mark locked (S8), and
// shortening its validity is refused when it would leave an assignment outside. The screens do no money arithmetic: every figure they show is one the
// SIMULATED API sent. Against the SIMULATED API, which repeats only the rules these journeys need.
const SAVE = { timeout: 15_000 } as const;
const REASON = "No corresponde a la campaña aprobada por la dirección";

async function expectNoBlockingViolations(page: Page): Promise<void> {
  const outcome = await new AxeBuilder({ page }).withTags([...A11Y_TAGS]).analyze();
  expect(partitionViolations(outcome.violations, outcome.incomplete).blocking.map((violation) => violation.id)).toEqual([]);
}

test.describe("the discounts", () => {
  test("creates a campaign discount that stays pending, and shows an agreement in use with its benefit and stackable mark locked", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Descuentos");
    await expect(page).toHaveURL(/\/scholarships\/discounts$/);
    await expect(page.getByRole("heading", { level: 2, name: "Descuentos" })).toBeVisible();
    const table = page.getByRole("table", { name: "Descuentos de la institución" });
    await expect(table.getByRole("row", { name: /Campaña de matrícula.*Activo/ })).toBeVisible(SAVE);
    await expect(table.getByRole("row", { name: /Convenio con la empresa.*No acumulable/ })).toBeVisible();
    await expect(table.getByRole("row", { name: /Campaña de verano.*Pendiente de aprobación/ })).toBeVisible();
    await expectNoBlockingViolations(page);

    // The person creates a campaign discount: it is born pending, and the person who created it is not offered to approve it.
    await page.getByRole("button", { name: "Crear descuento" }).click();
    await expect(dialog(page)).toBeVisible();
    await dialog(page).getByLabel(/^Código/).fill("BECAPAGO");
    await dialog(page).getByLabel(/^Nombre/).fill("Pago anual adelantado");
    await dialog(page).getByLabel(/^Clase/).selectOption({ label: "Campaña" });
    await expect(dialog(page).getByText(/nace pendiente y no se aplica a ningún cargo hasta que otra persona/)).toBeVisible();
    await dialog(page).getByLabel(/^Tipo de beneficio/).selectOption({ label: "Porcentaje" });
    await dialog(page).getByLabel(/^Porcentaje/).fill("15");
    await dialog(page).getByLabel(/^Vigente desde/).fill("2026-01-01");
    await dialog(page).getByLabel(/^Vigente hasta/).fill("2026-12-31");
    await dialog(page).getByRole("checkbox", { name: "Acumulable con los demás beneficios" }).check();
    await dialog(page).getByRole("checkbox", { name: /Colegiatura/ }).check();
    await dialog(page).getByRole("button", { name: "Guardar" }).click();
    await expect(page.getByText(/Se creó «Pago anual adelantado». Queda pendiente de aprobación y no se aplica a ningún cargo/)).toBeVisible(SAVE);
    await expect(table.getByRole("row", { name: /Pago anual adelantado.*Acumulable.*Pendiente de aprobación/ })).toBeVisible(SAVE);
    expect((await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path === "/api/v1/scholarships/discounts")).toHaveLength(1);
    await expect(page.getByRole("button", { name: "Aprobar Pago anual adelantado" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Editar Pago anual adelantado" })).toHaveCount(0);
    await expectNoBlockingViolations(page);

    // The agreement has an approved assignment: its type, value and stackable mark are read only, and the form says why.
    await page.getByRole("button", { name: "Editar Convenio con la empresa" }).click();
    await expect(dialog(page).getByText(/ya está en uso/)).toBeVisible();
    await expect(dialog(page).getByLabel(/^Porcentaje/)).toHaveAttribute("readonly", "");
    await expect(dialog(page).getByRole("combobox", { name: /Tipo de beneficio/ })).toHaveCount(0);
    await expect(dialog(page).getByRole("checkbox", { name: "Acumulable con los demás beneficios" })).toHaveCount(0);
    await expect(dialog(page).getByLabel("Acumulable con los demás beneficios")).toHaveAttribute("readonly", "");
    // The dialogs are analyzed alone by the matrix of the accessibility gate (the edit of one in use, the approval and the rejection).

    // Shortening the validity would leave the approved assignment outside it: the server refuses and the form tells under the end.
    await dialog(page).getByLabel(/^Vigente hasta/).fill("2026-03-31");
    await dialog(page).getByRole("button", { name: "Guardar" }).click();
    await expect(dialog(page).getByText(/quedarían fuera de esta vigencia/).first()).toBeVisible(SAVE);
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
  });

  test("approves what another person created, rejects with a reason, and leaves a proposal when an approved campaign discount is widened", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Descuentos");
    const table = page.getByRole("table", { name: "Descuentos de la institución" });
    await expect(table.getByRole("row", { name: /Campaña de verano.*Pendiente de aprobación/ })).toBeVisible(SAVE);

    // Another person created "Campaña de verano": the person sees the terms and approves it, with one Idempotency-Key.
    await page.getByRole("button", { name: "Aprobar Campaña de verano" }).click();
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).getByText("15.00 %")).toBeVisible();
    await dialog(page).getByRole("button", { name: "Aprobar" }).click();
    await expect(page.getByText(/«Campaña de verano» quedó aprobado/)).toBeVisible(SAVE);
    await expect(table.getByRole("row", { name: /Campaña de verano.*Activo/ })).toBeVisible(SAVE);
    await expect(table.getByRole("row", { name: /Campaña de verano.*Pendiente de aprobación/ })).toHaveCount(0);
    const approvals = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.endsWith("/approve"));
    expect(approvals).toHaveLength(1);

    // Widening an approved campaign discount leaves a proposal: the terms in force keep applying and the person who proposed it cannot decide it.
    await page.getByRole("button", { name: "Editar Campaña de matrícula" }).click();
    await dialog(page).getByLabel(/^Porcentaje/).fill("25");
    await dialog(page).getByRole("button", { name: "Guardar" }).click();
    await expect(page.getByText(/Se envió la propuesta sobre «Campaña de matrícula». Los términos vigentes siguen aplicándose/)).toBeVisible(SAVE);
    const proposal = table.getByRole("row", { name: /Campaña de matrícula.*Propuesta pendiente/ });
    await expect(proposal).toBeVisible(SAVE);
    await expect(proposal.getByText("10.00 %")).toBeVisible();
    await expect(proposal.getByText(/Propone/)).toBeVisible();
    await expect(proposal.getByText("25 %")).toBeVisible();
    await expect(page.getByRole("button", { name: "Aprobar Campaña de matrícula" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Editar Campaña de matrícula" })).toHaveCount(0);
    await expectNoBlockingViolations(page);

    // What waits can still be deactivated, and reactivating it is not offered.
    await page.getByRole("button", { name: "Desactivar Campaña de matrícula" }).click();
    await expect(page.getByText("Se desactivó «Campaña de matrícula».")).toBeVisible(SAVE);
    await expect(table.getByRole("row", { name: /Campaña de matrícula.*Inactivo/ })).toBeVisible();
    await expect(page.getByRole("button", { name: "Activar Campaña de matrícula" })).toHaveCount(0);
  });

  test("rejects what another person created, with a reason of 20 to 500 characters", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Descuentos");
    const table = page.getByRole("table", { name: "Descuentos de la institución" });
    await expect(table.getByRole("row", { name: /Campaña de verano.*Pendiente de aprobación/ })).toBeVisible(SAVE);

    await page.getByRole("button", { name: "Rechazar Campaña de verano" }).click();
    await dialog(page).getByRole("textbox", { name: /Motivo del rechazo/ }).fill("Muy corto");
    await dialog(page).getByRole("button", { name: "Rechazar" }).click();
    await expect(dialog(page).getByText("Escriba de 20 a 500 caracteres.").first()).toBeVisible();
    await dialog(page).getByRole("textbox", { name: /Motivo del rechazo/ }).fill(REASON);
    await dialog(page).getByRole("button", { name: "Rechazar" }).click();

    await expect(page.getByText(/«Campaña de verano» quedó rechazado/)).toBeVisible(SAVE);
    await expect(table.getByRole("row", { name: /Campaña de verano.*Rechazado/ })).toBeVisible(SAVE);
    await expect(page.getByRole("button", { name: "Editar Campaña de verano" })).toHaveCount(0);
    const rejections = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.endsWith("/reject"));
    expect(rejections).toHaveLength(1);
    await expectNoBlockingViolations(page);
  });
});

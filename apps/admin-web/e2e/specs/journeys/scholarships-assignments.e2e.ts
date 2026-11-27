import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { A11Y_TAGS, partitionViolations } from "@confia/config/a11y";
import { dialog, test } from "../../support/organization.ts";
import { clickNavigationLink } from "../../support/navigation.ts";
import { expect, recordedCalls, seedSession } from "../../support/scenario.ts";

// The agreement assignments from the person who requests and the person who decides. The person requests an assignment for a student and is refused when
// they try to approve it themselves, with the text of the assignments; they approve the one another person requested, reject a pending one with a reason
// of 20 to 500 characters, and revoke the approved one. Every decision carries its Idempotency-Key. The screens do no money arithmetic. Against the
// SIMULATED API, which repeats only the rules these journeys need.
const SAVE = { timeout: 15_000 } as const;
const REASON = "El estudiante ya no cumple las condiciones del convenio";

async function expectNoBlockingViolations(page: Page): Promise<void> {
  const outcome = await new AxeBuilder({ page }).withTags([...A11Y_TAGS]).analyze();
  expect(partitionViolations(outcome.violations, outcome.incomplete).blocking.map((violation) => violation.id)).toEqual([]);
}

test.describe("the agreement assignments", () => {
  test("requests an assignment, is refused when approving their own, approves another person's, rejects and revokes with a reason", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await clickNavigationLink(page, "Asignaciones de convenio");
    await expect(page).toHaveURL(/\/scholarships\/discount-assignments$/);
    await expect(page.getByRole("heading", { level: 2, name: "Asignaciones de convenio" })).toBeVisible();
    await expect(page.getByRole("row", { name: /E-000001.*Pendiente de aprobación/ })).toBeVisible(SAVE);
    await expectNoBlockingViolations(page);

    // The person requests an assignment for another student: it is born pending.
    await page.getByRole("button", { name: "Solicitar asignación" }).click();
    await expect(dialog(page)).toBeVisible();
    await dialog(page).getByRole("combobox", { name: /^Estudiante/ }).fill("E-000002");
    await dialog(page).getByRole("option", { name: /E-000002/ }).click();
    await dialog(page).getByLabel(/^Descuento de convenio/).selectOption({ label: "CONV20 · Convenio con la empresa" });
    await dialog(page).getByLabel(/^Vigente desde/).fill("2026-02-01");
    await dialog(page).getByLabel(/^Vigente hasta/).fill("2026-06-30");
    await dialog(page).getByRole("button", { name: "Solicitar asignación" }).click();
    await expect(page.getByText(/Queda pendiente hasta que otra persona la apruebe/)).toBeVisible(SAVE);
    expect((await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path === "/api/v1/scholarships/discount-assignments")).toHaveLength(1);

    // Approving what the person requested themselves is refused by the server, and told inside the dialog.
    await page.getByRole("row", { name: /E-000002.*Pendiente de aprobación/ }).first().getByRole("link").click();
    await expect(page.getByRole("heading", { level: 2, name: "Asignación del estudiante E-000002" })).toBeVisible(SAVE);
    await page.getByRole("button", { name: "Aprobar" }).click();
    await dialog(page).getByRole("button", { name: "Aprobar asignación" }).click();
    await expect(dialog(page).getByText(/Quien solicitó la asignación no puede aprobarla ni rechazarla/)).toBeVisible(SAVE);
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expectNoBlockingViolations(page);

    // The assignment another person requested for the first student is approved.
    await clickNavigationLink(page, "Asignaciones de convenio");
    await page.getByRole("row", { name: /E-000001.*Pendiente de aprobación/ }).first().getByRole("link").click();
    await expect(page.getByRole("heading", { level: 2, name: "Asignación del estudiante E-000001" })).toBeVisible(SAVE);
    await page.getByRole("button", { name: "Aprobar" }).click();
    await dialog(page).getByRole("button", { name: "Aprobar asignación" }).click();
    await expect(page.getByText(/La asignación quedó aprobada/)).toBeVisible(SAVE);
    await expect(page.getByRole("button", { name: "Revocar" })).toBeVisible(SAVE);
    const approvals = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.endsWith("/approve"));
    expect(approvals.length).toBeGreaterThanOrEqual(2);

    // Revoking needs a reason of 20 to 500 characters: a short one is refused before anything is sent.
    await page.getByRole("button", { name: "Revocar" }).click();
    await dialog(page).getByRole("textbox", { name: /Motivo de la revocación/ }).fill("Muy corto");
    await dialog(page).getByRole("button", { name: "Revocar asignación" }).click();
    await expect(dialog(page).getByText("Escriba de 20 a 500 caracteres.").first()).toBeVisible();
    await dialog(page).getByRole("textbox", { name: /Motivo de la revocación/ }).fill(REASON);
    await dialog(page).getByRole("button", { name: "Revocar asignación" }).click();
    await expect(page.getByText(/La asignación quedó revocada/)).toBeVisible(SAVE);
    await expect(page.getByText(REASON)).toBeVisible(SAVE);
    await expectNoBlockingViolations(page);
  });
});

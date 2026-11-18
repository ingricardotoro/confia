import { studentId } from "../../mock-api/students-seed.ts";
import { dialog, test } from "../../support/charges.ts";
import { expect, recordedCalls, seedSession } from "../../support/scenario.ts";

// The void of a charge, from the statement of account to the confirmation: the link from the statement to the charges of the student, the
// list with its status, the explicit dialog (irreversible, reason with a live count, no personal data), the refusal of a reason that does
// not hold with nothing sent, the void itself and the list read again. Against the SIMULATED API, whose student 1 has three charges.
const STUDENT = studentId(1);
const POST_VOID = (call: { method: string; path: string }) => call.method === "POST" && call.path.endsWith("/void");

test.describe("voiding a charge", () => {
  test("goes from the statement to the charges of the student, voids one with a reason, and sees it voided", async ({ page, request, scenarioId }) => {
    await seedSession(page);
    await page.goto(`/charges/statement?studentId=${STUDENT}`);
    await page.getByRole("button", { name: "Ver cargos del estudiante" }).click();

    await expect(page).toHaveURL(/\/charges\/student-charges\?studentId=/);
    await expect(page.getByRole("heading", { level: 2, name: "Cargos del estudiante" })).toBeVisible();
    const rows = page.getByRole("table", { name: "Cargos del estudiante" }).getByRole("row");
    await expect(rows).toHaveCount(4);
    await expect(rows.filter({ hasText: "Vigente" })).toHaveCount(3);

    await page.getByRole("button", { name: "Anular el cargo Mensualidad, 2026-11" }).click();
    await expect(dialog(page).getByText(/La anulación no se puede deshacer/)).toBeVisible();
    await expect(dialog(page).getByText(/No escriba nombres, números de documento/)).toBeVisible();
    await dialog(page).getByRole("textbox", { name: /Motivo de la anulación/ }).fill("corto");
    await expect(dialog(page).getByText("5 de 500 caracteres")).toBeVisible();
    await dialog(page).getByRole("button", { name: "Anular cargo" }).click();
    await expect(dialog(page).getByText("El motivo debe tener al menos 20 caracteres.")).toBeVisible();
    expect((await recordedCalls(request, scenarioId)).filter(POST_VOID)).toHaveLength(0);

    await dialog(page).getByRole("textbox", { name: /Motivo de la anulación/ }).fill("Cargo generado dos veces por error del proceso");
    await dialog(page).getByRole("button", { name: "Anular cargo" }).click();

    await expect(dialog(page)).toBeHidden();
    await expect(page.getByText("Se anuló el cargo Mensualidad, 2026-11. Su reverso ya está en el libro mayor.")).toBeVisible();
    await expect(rows.filter({ hasText: "Anulado" })).toHaveCount(1);
    await expect(rows.filter({ hasText: "Vigente" })).toHaveCount(2);
    expect((await recordedCalls(request, scenarioId)).filter(POST_VOID)).toHaveLength(1);
  });
});

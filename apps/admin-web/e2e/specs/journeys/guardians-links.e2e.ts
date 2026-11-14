import { expect, recordedCalls } from "../../support/scenario.ts";
import {
  addBox,
  alertOf,
  dialog,
  linkRow,
  openLinks,
  openStudentsForLinks,
  recordGuardianResponses,
  resultNotice,
  saveLinks,
  SAVE_TIMEOUT,
  setPercent,
  test,
} from "../../support/guardians.ts";

// The guardians of a student through the students screen (guardians cut): the acceptance criterion (two guardians at 60/40, then
// 50/50, 60/30 refused), every reason the split can be refused, the search that adds a guardian, and that no document ever
// shows. Real Chromium, the production build and the SIMULATED API: a simulation of the published shapes, not a test of the
// real stack or of its rules. The student is Ana Aguilar (E-000001), linked in the seed to Rosa Aguilar (60) and Luis Alvarado (40).
const ROSA = "Rosa Aguilar · P-000001";
const LUIS = "Luis Alvarado · P-000002";

test.describe("the guardians of a student", () => {
  test("opens from the row, shows the set the server holds, changes 60/40 to 50/50 and tells the sum the server gave", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    await openLinks(page, "Aguilar", "Ana Aguilar");

    await expect(dialog(page).getByText("Suma guardada actualmente (la calcula el servidor): 100.00 %")).toBeVisible();
    await expect(linkRow(page, ROSA).getByLabel(/^Porcentaje/)).toHaveValue("60.00");
    await expect(linkRow(page, LUIS).getByLabel(/^Porcentaje/)).toHaveValue("40.00");
    await expect(linkRow(page, ROSA).getByRole("checkbox", { name: "Pagador principal" })).toBeChecked();

    await setPercent(page, ROSA, "50");
    await setPercent(page, LUIS, "50.00");
    await saveLinks(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los encargados de «Ana Aguilar». Suma: 100.00 %.");
    // Opened again, the server's set is the new one.
    await openLinks(page, "Aguilar", "Ana Aguilar");
    await expect(linkRow(page, ROSA).getByLabel(/^Porcentaje/)).toHaveValue("50.00");
    const calls = await recordedCalls(page.request, scenarioId);
    expect(calls.filter((call) => call.method === "PUT" && call.path === "/api/v1/students/00000000-0000-4000-8000-000000005001/guardians")).toHaveLength(1);
  });

  test("refuses 60/30 with the sum reason, keeps what was typed and the focus on the alert, and then accepts the fixed set", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    await openLinks(page, "Aguilar", "Ana Aguilar");

    await setPercent(page, LUIS, "30");
    await saveLinks(page).click();

    await expect(alertOf(page)).toContainText("Los porcentajes deben sumar exactamente 100.00.");
    await expect(alertOf(page)).toBeFocused();
    await expect(linkRow(page, LUIS).getByLabel(/^Porcentaje/)).toHaveValue("30");
    await expect(dialog(page)).toBeVisible();
    await setPercent(page, LUIS, "40");
    await saveLinks(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
  });

  test("tells two primary payers and two billing recipients, each with its own message", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    await openLinks(page, "Aguilar", "Ana Aguilar");

    await linkRow(page, LUIS).getByRole("checkbox", { name: "Pagador principal" }).check();
    await saveLinks(page).click();
    await expect(alertOf(page)).toContainText("Solo un encargado puede ser el pagador principal.");

    await linkRow(page, LUIS).getByRole("checkbox", { name: "Pagador principal" }).uncheck();
    await linkRow(page, LUIS).getByRole("checkbox", { name: "Destinatario de la factura" }).check();
    await saveLinks(page).click();
    await expect(alertOf(page)).toContainText("Solo un encargado puede ser el destinatario de la factura.");
  });

  test("adds a guardian found by search with the keyboard, completes the row, and saves three guardians at 33.33, 33.33 and 33.34", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    await openLinks(page, "Aguilar", "Ana Aguilar");

    await addBox(page).fill("Banegas");
    await expect(dialog(page).getByRole("option", { name: "Carmen Banegas · P-000003" })).toBeVisible();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");

    const carmen = linkRow(page, "Carmen Banegas · P-000003");
    await expect(carmen.getByLabel(/^Parentesco/)).toBeFocused();
    await expect(dialog(page).getByText("Se agregó a Carmen Banegas. Complete el parentesco y el porcentaje.")).toBeVisible();
    await carmen.getByLabel(/^Parentesco/).selectOption({ label: "Tutor legal" });
    await setPercent(page, ROSA, "33.33");
    await setPercent(page, LUIS, "33.33");
    await setPercent(page, "Carmen Banegas · P-000003", "33.34");
    await saveLinks(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Suma: 100.00 %.");
  });

  test("removes a guardian, sends the rest, and an empty set removes everyone after a visible warning", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    await openLinks(page, "Aguilar", "Ana Aguilar");

    await dialog(page).getByRole("button", { name: "Quitar a Luis Alvarado del reparto" }).click();
    await expect(addBox(page)).toBeFocused();
    await dialog(page).getByRole("button", { name: "Quitar a Rosa Aguilar del reparto" }).click();
    await expect(dialog(page).getByText(/se quitarán todos los vínculos vigentes/)).toBeVisible();
    await saveLinks(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se quitaron todos los encargados de «Ana Aguilar».");
  });

  test("asks for the relationship and the percentage of a new row before sending anything", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    await openLinks(page, "Alvarado", "María Alvarado");

    await addBox(page).fill("Cruz");
    await dialog(page).getByRole("option", { name: /Cruz/ }).click();
    await saveLinks(page).click();

    await expect(dialog(page).getByText("Hay 2 campos con errores.")).toBeVisible();
    await expect(dialog(page).getByText("Este dato es obligatorio.").first()).toBeVisible();
    const calls = await recordedCalls(page.request, scenarioId);
    expect(calls.filter((call) => call.method === "PUT")).toHaveLength(0);
  });

  test("tells an inactive student that its guardians cannot change, in plain words, and leaves the dialog open", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    // Diego Cruz (E-000010) is inactive in the seed: his set can be read, but not changed, not even emptied.
    await openLinks(page, "Cruz", "Diego Cruz");

    await saveLinks(page).click();

    await expect(alertOf(page)).toContainText("El estudiante está inactivo y no admite cambios en sus encargados.");
    await expect(dialog(page)).toBeVisible();
  });

  test("never shows or receives a document or an RTN", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    const answers = recordGuardianResponses(page);
    await openLinks(page, "Aguilar", "Ana Aguilar");

    const html = await page.content();
    expect(html).not.toMatch(/0801-?1980/);
    for (const text of await Promise.all(answers)) {
      expect(text).not.toMatch(/0801-?1980/);
      expect(text).not.toContain("nationalId");
    }
  });
});

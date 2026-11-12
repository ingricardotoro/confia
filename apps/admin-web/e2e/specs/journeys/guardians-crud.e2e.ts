import { seededGuardianDocument } from "../../mock-api/guardians-seed.ts";
import { expect } from "../../support/scenario.ts";
import {
  alertOf,
  bodyRows,
  dialog,
  field,
  openCreate,
  openGuardians,
  recordGuardianResponses,
  resultNotice,
  save,
  SAVE_TIMEOUT,
  searchBox,
  test,
} from "../../support/guardians.ts";

// The guardians registry through its screen (guardians cut): register a guardian with a document and an RTN and find them by
// last name, edit, replace and see that the document never comes back, deactivate a guardian that still has a student, and the
// detail with the linked students. Real Chromium, the production build and the SIMULATED API: a simulation of the published
// shapes, not a test of the real stack or of its rules.
const DOCUMENT = "0801-1990-77777";
const DOCUMENT_DIGITS = "0801199077777";
const RTN = "08011990777770";

test.describe("the guardians registry, registering and editing", () => {
  test("opens from the navigation, ordered by last name, and registers a guardian whose document and RTN never come back", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId);
    await expect(page.getByRole("link", { name: "Encargados" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("columnheader", { name: "Apellidos" })).toHaveAttribute("aria-sort", "ascending");
    await expect.poll(async () => (await bodyRows(page))[0]?.slice(0, 3)).toEqual(["P-000001", "Aguilar", "Rosa"]);
    const answers = recordGuardianResponses(page);

    await openCreate(page, "Nuevo encargado");
    await field(page, "Nombres").fill("Eva");
    await field(page, "Apellidos").fill("Zúñiga");
    await field(page, "Documento de identidad").fill(DOCUMENT);
    await field(page, "RTN").fill(RTN);
    await field(page, "Correo electrónico").fill("eva@example.test");
    await field(page, "Canal preferido").selectOption({ label: "Correo electrónico" });
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Eva Zúñiga».");
    const content = await page.content();
    for (const secret of [DOCUMENT, DOCUMENT_DIGITS, RTN]) {
      expect(content).not.toContain(secret);
      expect(page.url()).not.toContain(secret);
    }
    await searchBox(page).fill("Zúñiga");
    await expect.poll(() => bodyRows(page)).toHaveLength(1);
    const [row] = await bodyRows(page);
    expect(row?.slice(0, 3)).toEqual(["P-000031", "Zúñiga", "Eva"]);
    expect(row?.[3]).toContain("Registrado");
    expect(row?.[4]).toContain("Registrado");
    expect(row?.[3]).not.toMatch(/\d{4}/);
    for (const text of await Promise.all(answers)) {
      for (const secret of [DOCUMENT, DOCUMENT_DIGITS, RTN]) expect(text).not.toContain(secret);
    }
  });

  test("edits a guardian: the document is only 'registered' or not, a typed one replaces it, and it is shown again only as registered", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId);
    await page.getByRole("button", { name: "Editar Rosa Aguilar" }).click();
    await expect(field(page, "Documento actual")).toHaveValue("Sin registrar");
    await expect(dialog(page).getByRole("checkbox", { name: "Quitar documento" })).toHaveCount(0);

    await field(page, "Reemplazar documento").fill(DOCUMENT);
    await field(page, "Teléfono").fill("+504 8888-0000");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios de «Rosa Aguilar».");
    expect(await page.content()).not.toContain(DOCUMENT_DIGITS);
    await page.getByRole("button", { name: "Editar Rosa Aguilar" }).click();
    await expect(field(page, "Documento actual")).toHaveValue("Registrado");
    await expect(field(page, "Reemplazar documento")).toHaveValue("");
    await expect(dialog(page).getByRole("checkbox", { name: "Quitar documento" })).toBeVisible();
  });

  test("deactivating a guardian that still has a student says so, and the student stays linked; activating brings it back", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId);

    await page.getByRole("button", { name: "Desactivar Rosa Aguilar" }).click();

    await expect(resultNotice(page)).toContainText("Se desactivó «Rosa Aguilar». El encargado sigue vinculado a 1 estudiante.");
    await page.getByRole("button", { name: "Ver detalle de Rosa Aguilar" }).click();
    await expect(dialog(page).getByText(/sus vínculos siguen vigentes/)).toBeVisible();
    await expect(dialog(page).getByRole("table", { name: "Estudiantes a los que el encargado está vinculado ahora" })).toContainText("Ana Aguilar · E-000001");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Activar Rosa Aguilar" }).click();
    await expect(resultNotice(page)).toContainText("Se activó «Rosa Aguilar».");
  });

  test("the detail shows the linked students with the percentage written with two decimals, and focus returns to its button", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId);
    const opener = page.getByRole("button", { name: "Ver detalle de Rosa Aguilar" });

    await opener.click();

    const table = dialog(page).getByRole("table", { name: "Estudiantes a los que el encargado está vinculado ahora" });
    await expect(table).toContainText("Ana Aguilar · E-000001");
    await expect(table).toContainText("Madre");
    await expect(table).toContainText("60.00 %");
    await page.keyboard.press("Escape");
    await expect(dialog(page)).toBeHidden();
    await expect(opener).toBeFocused();
  });

  test("a duplicate document is told in the guardian's words and what was typed stays", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId);
    await openCreate(page, "Nuevo encargado");
    await field(page, "Nombres").fill("Eva");
    await field(page, "Apellidos").fill("Rivas");
    // Guardian 3 holds this document in the seed; other hyphens are the same document.
    await field(page, "Documento de identidad").fill(seededGuardianDocument(3).replaceAll("-", ""));
    await save(page).click();

    await expect(alertOf(page)).toContainText("Ya hay un encargado registrado con ese documento de identidad.");
    await expect(field(page, "Nombres")).toHaveValue("Eva");
  });
});

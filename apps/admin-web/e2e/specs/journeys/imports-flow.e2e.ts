import { expect } from "../../support/scenario.ts";
import { choose, csvOf, dialog, fileField, openImports, recordImportCalls, recordImportResponses, resultHeading, simulateButton, summaryHeading, test } from "../../support/imports.ts";

// The criterion of the cut, against the SIMULATED API: a CSV of 100 students with 5 defective rows is simulated, the page lists
// exactly those 5 by line and reason, the corrected file is simulated again, applied behind a confirmation that says how many will
// be created, and applying the same file again creates none. The file and its rows never appear on the page.
const DEFECTS = [
  "L101,,Apellido,2013-05-01,,F", // line 97 (the header is line 1, the 95 valid rows are lines 2 to 96): no first name
  "L102,Nombre,Apellido,2013-02-30,,F", // line 98: a day that does not exist
  "L103,Nombre,Apellido,2999-01-01,,F", // line 99: after today
  "L104,Nombre,Apellido,13/05/2013,,F", // line 100: not the format
  "L1,Nombre,Apellido,2013-05-01,,F", // line 101: the legacyId of the first row, repeated
];

test.describe("the import of students", () => {
  test("simulates a file of 100 with 5 defective rows, lists exactly those 5, corrects, applies with confirmation and reapplies with nothing created", async ({ page, scenarioId }) => {
    const calls = recordImportCalls(page);
    const responses = recordImportResponses(page);
    await openImports(page, scenarioId);

    await choose(page, csvOf(95, ...DEFECTS));
    await simulateButton(page).click();

    await expect(summaryHeading(page)).toBeFocused();
    await expect(page.getByRole("status").filter({ hasText: "Simulación lista" })).toContainText("95 filas se pueden crear, 5 rechazadas y 0 ya importadas.");
    const table = page.getByRole("table", { name: /Filas del archivo con su línea/ });
    await expect(table.getByRole("row")).toHaveCount(5 + 1);
    await expect(table.getByRole("row").nth(1)).toContainText("97firstNameFalta el dato obligatorio.");
    await expect(table.getByRole("row").nth(2)).toContainText("98birthDateLa fecha no existe");
    await expect(table.getByRole("row").nth(3)).toContainText("99birthDateLa fecha de nacimiento es posterior a hoy.");
    await expect(table.getByRole("row").nth(4)).toContainText("100birthDateEl formato no es válido.");
    await expect(table.getByRole("row").nth(5)).toContainText("101legacyIdEl dato se repite en una fila anterior del archivo.");

    // The person corrects the source and simulates again: no rejection is left.
    await choose(page, csvOf(100), "corregido.csv");
    await expect(summaryHeading(page)).toBeHidden();
    await simulateButton(page).click();
    await expect(summaryHeading(page)).toBeFocused();
    await expect(page.getByText("Ninguna fila tiene defectos.")).toBeVisible();

    await page.getByRole("button", { name: "Aplicar la importación" }).click();
    await expect(dialog(page)).toHaveAccessibleName("Aplicar la importación");
    await expect(dialog(page).getByText("Se crearán 100 estudiantes.")).toBeVisible();
    await dialog(page).getByRole("button", { name: "Crear 100 estudiantes" }).click();

    await expect(resultHeading(page)).toBeFocused();
    await expect(page.getByText(/Se crearon 100 estudiantes\./)).toBeVisible();
    await expect(fileField(page)).toHaveCount(0);

    // The same file once more: everything is already imported and nothing is created.
    await page.getByRole("button", { name: "Importar otro archivo" }).click();
    await expect(fileField(page)).toHaveValue("");
    await choose(page, csvOf(100), "corregido.csv");
    await simulateButton(page).click();
    await expect(page.getByRole("status").filter({ hasText: "Simulación lista" })).toContainText("0 filas se pueden crear, 0 rechazadas y 100 ya importadas.");
    await expect(page.getByText("No hay nada que crear")).toBeVisible();
    await expect(page.getByRole("button", { name: "Aplicar la importación" })).toHaveCount(0);

    // The history shows the batches, newest first, and the page asked the server for them.
    const history = page.getByRole("table", { name: /Importaciones de la institución/ });
    await expect(history.getByRole("row").nth(1)).toContainText("Simulada");
    expect(calls.filter((call) => call.startsWith("POST") && call.endsWith("/apply"))).toHaveLength(1);

    // Nothing of the file reached the page, the address or an answer of the API.
    await expect(page.locator("body")).not.toContainText("Nombre1");
    await expect(page.locator("body")).not.toContainText("corregido.csv");
    expect(page.url()).not.toContain("Nombre");
    expect((await Promise.all(responses)).join("")).not.toMatch(/Nombre\d|Apellido\d/);
  });

  test("a document already stored is not flagged by the simulation, the apply warns about it and lists the rows it rejected", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    // The registry of the simulated API holds this document; the simulation does not look at it, the apply does.
    await choose(page, [csvOf(2), "L50,Nombre50,Apellido50,2013-05-01,0801-2000-00001,F"].join("\n"));
    await simulateButton(page).click();

    await expect(summaryHeading(page)).toBeFocused();
    await expect(page.getByRole("status").filter({ hasText: "Simulación lista" })).toContainText("3 filas se pueden crear, 0 rechazadas y 0 ya importadas.");
    await expect(page.getByText("Ninguna fila tiene defectos.")).toBeVisible();

    await page.getByRole("button", { name: "Aplicar la importación" }).click();
    await expect(dialog(page).getByText(/se revisan solo al aplicar/)).toBeVisible();
    await dialog(page).getByRole("button", { name: "Crear 3 estudiantes" }).click();

    await expect(resultHeading(page)).toBeFocused();
    await expect(page.getByText(/Se crearon 2 estudiantes\./)).toBeVisible();
    const table = page.getByRole("table", { name: /Filas rechazadas al aplicar/ });
    await expect(table.getByRole("row")).toHaveCount(1 + 1);
    await expect(table.getByRole("row").nth(1)).toContainText("4nationalIdYa hay un registro con ese documento de identidad.");
  });

  test("keeps nothing of the file in the browser storage", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId);
    await choose(page, csvOf(3));
    await simulateButton(page).click();
    await expect(summaryHeading(page)).toBeFocused();

    const stored = await page.evaluate(async () => {
      const databases = "databases" in indexedDB ? await indexedDB.databases() : [];
      return JSON.stringify({ local: Object.entries(localStorage), session: Object.entries(sessionStorage), databases: databases.map((database) => database.name) });
    });

    expect(stored).not.toMatch(/Nombre|Apellido|estudiantes\.csv/);
  });

  test("an apply of a file that is not the simulated one is refused in plain words and the way forward is to simulate it again", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    await choose(page, csvOf(3));
    await simulateButton(page).click();
    await expect(summaryHeading(page)).toBeFocused();

    // Another session simulated a different file since: the server answers the apply as the backend does in that case. (The
    // rule that tells the two 409 apart is proven in the unit test of the simulator; here the page's reaction is.)
    await page.route("**/api/v1/imports/students/apply", (route) =>
      route.fulfill({
        status: 409,
        contentType: "application/problem+json",
        body: JSON.stringify({ type: "https://confia.hn/problems/import-file-changed", title: "x", status: 409, instance: "/api/v1/imports/students/apply", traceId: "01J8Z4K2QF9P0X" }),
      }),
    );

    await page.getByRole("button", { name: "Aplicar la importación" }).click();
    await dialog(page).getByRole("button", { name: "Crear 3 estudiantes" }).click();

    await expect(page.getByRole("alert").filter({ hasText: "El archivo no es el mismo que se simuló la última vez." })).toBeFocused();
    await expect(summaryHeading(page)).toBeHidden();
    await page.unroute("**/api/v1/imports/students/apply");
    await page.getByRole("button", { name: "Simular de nuevo este archivo" }).click();
    await expect(summaryHeading(page)).toBeFocused();
    await page.getByRole("button", { name: "Aplicar la importación" }).click();
    await dialog(page).getByRole("button", { name: "Crear 3 estudiantes" }).click();
    await expect(resultHeading(page)).toBeFocused();
  });
});

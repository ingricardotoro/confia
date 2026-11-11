import type { Page } from "@playwright/test";
import { seededDocument, studentCodeOf } from "../../mock-api/students-seed.ts";
import {
  alertOf,
  bodyRows,
  dialog,
  field,
  openCreate,
  openStudents,
  recordStudentQueries,
  resultNotice,
  save,
  SAVE_TIMEOUT,
  searchBox,
  submitStudent,
  test,
} from "../../support/students.ts";
import { expect, recordedCalls } from "../../support/scenario.ts";

// What the registry says when the answer is not a plain yes: a business rule of the server (a repeated code, a repeated
// national id), a refusal for lack of permission, a list with nothing in it and a list that fails to load. Texts are the
// Spanish ones a person reads. Against the SIMULATED API, which repeats only the rules these journeys need.
const firstNames = async (page: Page) => (await bodyRows(page)).map((cells) => cells[2]);
const CHILD = { firstName: "Nuevo", lastName: "Estudiante", birthDate: "2013-04-04", sex: "Masculino" } as const;

test.describe("a rule of the server refuses a save", () => {
  test("a repeated code is refused in Spanish, the dialog stays open with what was typed, and an empty code is then accepted", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await openCreate(page, "Nuevo estudiante");

    await submitStudent(page, { ...CHILD, code: studentCodeOf(1) });

    await expect(alertOf(page)).toContainText("Ya existe un estudiante con ese código.");
    await expect(alertOf(page)).toBeFocused();
    await expect(dialog(page)).toBeVisible();
    await expect(field(page, "Nombres")).toHaveValue("Nuevo");

    await field(page, "Código").fill("");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Nuevo Estudiante».");
    await searchBox(page).fill("Estudiante");
    await expect.poll(async () => (await bodyRows(page)).map((cells) => cells[0])).toEqual([studentCodeOf(61)]);
  });

  test("a national id that already belongs to a student is refused, even written with other hyphens, and the dialog keeps it for the person to fix", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await openCreate(page, "Nuevo estudiante");
    const sameDocument = seededDocument(3).replaceAll("-", "");

    await submitStudent(page, { ...CHILD, document: sameDocument });

    await expect(alertOf(page)).toContainText("Ya hay un estudiante registrado con ese documento de identidad.");
    await expect(field(page, "Documento de identidad")).toHaveValue(sameDocument);
    await expect(dialog(page)).toBeVisible();

    await field(page, "Documento de identidad").fill("");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
  });

  test("replacing the document with one that belongs to another student is refused on an edit too", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await searchBox(page).fill("Aguilar");
    await page.getByRole("button", { name: "Editar Ana Aguilar" }).click();
    await field(page, "Reemplazar documento").fill(seededDocument(3));

    await save(page).click();

    await expect(alertOf(page)).toContainText("Ya hay un estudiante registrado con ese documento de identidad.");
    await expect(field(page, "Reemplazar documento")).toHaveValue(seededDocument(3));
  });

  test("a document the format refuses is told before sending, and sends nothing", async ({ page, request, scenarioId }) => {
    await openStudents(page, scenarioId, "students-empty");
    await openCreate(page, "Nuevo estudiante");

    await submitStudent(page, { ...CHILD, document: "123 45" });

    await expect(field(page, "Documento de identidad")).toHaveAccessibleDescription(/El documento debe tener de 8 a 20 letras, números o guiones\./);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.startsWith("/api/v1/students"));
    expect(writes).toEqual([]);
  });

  test("Cancel leaves the list as it was, and the document typed is not in the page afterwards", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await openCreate(page, "Nuevo estudiante");
    await field(page, "Documento de identidad").fill("0801-2010-55555");

    await dialog(page).getByRole("button", { name: "Cancelar" }).click();

    await expect(dialog(page)).toBeHidden();
    expect(await page.content()).not.toContain("0801-2010-55555");
    expect(await page.content()).not.toContain("0801201055555");
    await expect.poll(() => bodyRows(page)).toHaveLength(25);
  });
});

test.describe("a person without the permission", () => {
  test("is told so plainly when a save is refused with 403, and nothing changes", async ({ page, request, scenarioId }) => {
    await openStudents(page, scenarioId, "students-forbidden");
    await openCreate(page, "Nuevo estudiante");

    await submitStudent(page, CHILD);

    await expect(alertOf(page)).toContainText(
      "No tiene permiso para realizar esta acción. Si la necesita para su trabajo, solicítela a un administrador de su institución.",
    );
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await searchBox(page).fill("Estudiante");
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.startsWith("/api/v1/students"));
    expect(writes).toHaveLength(1);
  });

  test("is told so when deactivating from a row is refused, and the row keeps its state", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId, "students-forbidden");

    await page.getByRole("button", { name: "Desactivar Ana Aguilar" }).click();

    await expect(resultNotice(page)).toContainText("No tiene permiso para realizar esta acción.");
    await expect(page.getByRole("row", { name: /Ana Aguilar/ })).toContainText("Activo");
    await expect(page.getByRole("button", { name: "Desactivar Ana Aguilar" })).toBeVisible();
  });
});

test.describe("a list with nothing in it, and a list that does not load", () => {
  test("shows the empty state with its way forward, which opens the form", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId, "students-empty");

    await expect(page.getByText("Todavía no hay estudiantes")).toBeVisible();
    await expect.poll(() => bodyRows(page)).toHaveLength(1);

    await page.getByRole("button", { name: "Registrar al primer estudiante" }).click();

    await expect(dialog(page).getByRole("heading", { name: "Nuevo estudiante" })).toBeVisible();
    await submitStudent(page, CHILD);
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect.poll(() => firstNames(page)).toEqual(["Nuevo"]);
    await expect.poll(async () => (await bodyRows(page))[0]?.[0]).toBe(studentCodeOf(1));
  });

  test("shows the error state with the trace id when the list fails, and Retry loads it", async ({ page, scenarioId }) => {
    // The page retries a read twice by itself (1 s and 2 s), and the simulator fails exactly those three reads.
    test.setTimeout(60_000);
    await openStudents(page, scenarioId, "students-server-error");

    await expect(page.getByText("No se pudo cargar la lista de estudiantes")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();
    await expect.poll(() => bodyRows(page)).toHaveLength(1);

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByText("No se pudo cargar la lista de estudiantes")).toBeHidden();
    await expect.poll(() => bodyRows(page).then((rows) => rows.length)).toBe(25);
  });

  test("a search with two characters or more is the only one that reaches the server, also while the list is empty", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId, "students-empty");
    const queries = recordStudentQueries(page);

    await searchBox(page).fill("a");
    await page.waitForTimeout(800);

    expect(queries.filter((query) => query.includes("q="))).toEqual([]);
    await expect(page.getByText("Todavía no hay estudiantes")).toBeVisible();
  });
});

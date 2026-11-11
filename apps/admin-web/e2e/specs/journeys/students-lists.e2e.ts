import { bodyRows, firstColumn, openStudents, recordStudentQueries, searchBox, summary, test } from "../../support/students.ts";
import { expect } from "../../support/scenario.ts";

// The list of students: the page, the order, the text search and the status filter are the server's, and they live in
// the URL (docs/ui-ux/04, section 3.2). Every journey asserts the URL, the rows on the screen and, where it matters, the
// query the page sent, so a list that searched, sorted or filtered in the browser would fail. Against the SIMULATED API.
const lastNames = async (page: import("@playwright/test").Page) => (await bodyRows(page)).map((cells) => cells[1]);

test.describe("paging and order, on the server and in the URL", () => {
  test("pages the students: the next page changes the URL and the rows, and Back returns to the first", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    const queries = recordStudentQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 60");
    await expect(summary(page)).toContainText("Página 1 de 3");
    await expect(summary(page).getByRole("button", { name: "Anterior" })).toBeDisabled();

    await summary(page).getByRole("button", { name: "Siguiente" }).click();

    await expect(page).toHaveURL(/[?&]page=2(&|$)/);
    await expect(summary(page)).toContainText("Mostrando 26 a 50 de 60");
    expect(queries.at(-1)).toContain("page=1");
    expect(queries.at(-1)).toContain("size=25");

    await summary(page).getByRole("button", { name: "Siguiente" }).click();

    await expect(summary(page)).toContainText("Mostrando 51 a 60 de 60");
    await expect.poll(() => firstColumn(page)).toHaveLength(10);
    await expect(summary(page).getByRole("button", { name: "Siguiente" })).toBeDisabled();

    await page.goBack();
    await page.goBack();

    await expect(page).not.toHaveURL(/page=/);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 60");
  });

  test("sorts on the server: last name is the default, and each click on a header changes the URL, the rows, aria-sort and the query", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    const queries = recordStudentQueries(page);
    // No `sort` in the URL: the server's order is by last name, and the header says so.
    await expect(page.getByRole("columnheader", { name: "Apellidos" })).toHaveAttribute("aria-sort", "ascending");
    await expect.poll(async () => (await lastNames(page))[0]).toBe("Aguilar");

    await page.getByRole("columnheader", { name: "Apellidos" }).getByRole("button").click();

    await expect(page).toHaveURL(/sort=lastName(%3A|:)desc/);
    await expect(page.getByRole("columnheader", { name: "Apellidos" })).toHaveAttribute("aria-sort", "descending");
    await expect.poll(async () => (await lastNames(page))[0]).toBe("Zelaya");
    expect(queries.at(-1)).toContain("sort=lastName,desc");

    await page.getByRole("columnheader", { name: "Fecha de nacimiento" }).getByRole("button").click();

    await expect(page).toHaveURL(/sort=birthDate(%3A|:)asc/);
    await expect(page.getByRole("columnheader", { name: "Fecha de nacimiento" })).toHaveAttribute("aria-sort", "ascending");
    expect(queries.at(-1)).toContain("sort=birthDate,asc");
    // The browser did not order them: the server did, so the page holds the 25 oldest by birth date of the whole registry.
    const dates = (await bodyRows(page)).map((cells) => cells[3]);
    expect(dates).toHaveLength(25);

    await page.getByRole("columnheader", { name: "Código" }).getByRole("button").click();
    await expect.poll(async () => (await bodyRows(page))[0]?.[0]).toBe("E-000001");
    expect(queries.at(-1)).toContain("sort=studentCode,asc");
  });

  test("goes back to the first page when the order changes", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await summary(page).getByRole("button", { name: "Siguiente" }).click();
    await expect(page).toHaveURL(/page=2/);

    await page.getByRole("columnheader", { name: "Nombres" }).getByRole("button").click();

    await expect(page).toHaveURL(/sort=firstName/);
    await expect(page).not.toHaveURL(/page=/);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 60");
  });

  test("opens a shared link on the page, order, text and status it names, and drops what does not hold", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);

    await page.goto("/students?page=3&sort=lastName:desc");

    await expect(summary(page)).toContainText("Mostrando 51 a 60 de 60");
    await expect(page.getByRole("columnheader", { name: "Apellidos" })).toHaveAttribute("aria-sort", "descending");

    const queries = recordStudentQueries(page);
    await page.goto("/students?q=a&status=BORRADO&sort=nationalId:asc&pageSize=7");

    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 60");
    expect(queries.at(-1)).not.toContain("q=");
    expect(queries.at(-1)).not.toContain("status=");
    expect(queries.at(-1)).not.toContain("sort=");
  });
});

test.describe("the text search and the status filter, on the server and in the URL", () => {
  test("sends q only from two characters, after a pause, writes it in the URL and goes back to the first page", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await summary(page).getByRole("button", { name: "Siguiente" }).click();
    await expect(page).toHaveURL(/page=2/);
    const queries = recordStudentQueries(page);

    await searchBox(page).fill("M");

    await expect(searchBox(page)).toHaveAccessibleDescription("Escriba al menos 2 caracteres para buscar.");
    await page.waitForTimeout(800);
    expect(queries.filter((query) => query.includes("q="))).toEqual([]);

    await searchBox(page).fill("Mejía");

    await expect(page).toHaveURL(/q=Mej%C3%ADa/);
    await expect(page).not.toHaveURL(/page=/);
    await expect.poll(async () => (await lastNames(page))).toEqual(["Mejía", "Mejía"]);
    expect(queries.at(-1)).toContain("q=Mejía");
    expect(queries.at(-1)).toContain("page=0");
    await expect(searchBox(page)).toHaveAccessibleDescription("Por nombre, apellido o código.");
  });

  test("searches without regard to capitals, by first name and by code", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);

    await searchBox(page).fill("pérez");
    await expect.poll(async () => (await lastNames(page))).toEqual(["Pérez", "Pérez"]);

    await searchBox(page).fill("e-000042");
    await expect.poll(async () => (await bodyRows(page)).map((cells) => cells.slice(0, 3))).toEqual([["E-000042", "Pérez", "Luis"]]);

    await searchBox(page).fill("Valeria");
    await expect.poll(async () => (await bodyRows(page)).every((cells) => cells[2] === "Valeria")).toBe(true);
  });

  test("clearing the box or going back to one character brings back the whole list", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await searchBox(page).fill("Mejía");
    await expect.poll(() => bodyRows(page)).toHaveLength(2);

    await searchBox(page).fill("M");

    await expect.poll(() => bodyRows(page)).toHaveLength(25);
    await expect(page).not.toHaveURL(/q=/);
    await expect(summary(page)).toContainText("de 60");
  });

  test("filters by status on the server: the inactive students are every tenth, and the URL says so", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    const queries = recordStudentQueries(page);

    await page.getByRole("group", { name: "Filtros" }).getByLabel("Estado").selectOption({ label: "Inactivo" });

    await expect(page).toHaveURL(/status=INACTIVE/);
    await expect.poll(() => bodyRows(page)).toHaveLength(6);
    expect((await bodyRows(page)).every((cells) => cells[6] === "Inactivo")).toBe(true);
    expect(queries.at(-1)).toContain("status=INACTIVE");
    await expect(summary(page)).toContainText("de 6");

    await page.getByRole("group", { name: "Filtros" }).getByLabel("Estado").selectOption({ label: "Activo" });

    await expect(page).toHaveURL(/status=ACTIVE/);
    await expect(summary(page)).toContainText("de 54");
  });

  test("combines text and status, says when nothing matches, and Remove filters restores the list and empties the box", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await searchBox(page).fill("Zelaya");
    await page.getByRole("group", { name: "Filtros" }).getByLabel("Estado").selectOption({ label: "Inactivo" });
    await expect(page).toHaveURL(/q=Zelaya/);
    await expect(page).toHaveURL(/status=INACTIVE/);
    // The sixtieth student, a Zelaya, is the one inactive of the pair.
    await expect.poll(() => bodyRows(page)).toHaveLength(1);

    await searchBox(page).fill("Zzzzz");
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    await page.getByRole("button", { name: "Quitar filtros" }).click();

    await expect(page).not.toHaveURL(/q=|status=/);
    await expect(searchBox(page)).toHaveValue("");
    await expect.poll(() => bodyRows(page)).toHaveLength(25);
  });

  test("restores the search and the status from a shared link", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);

    await page.goto("/students?q=Mej%C3%ADa&status=ACTIVE&sort=firstName:asc");

    await expect(searchBox(page)).toHaveValue("Mejía");
    await expect(page.getByRole("group", { name: "Filtros" }).getByLabel("Estado")).toHaveValue("ACTIVE");
    await expect(page.getByRole("columnheader", { name: "Nombres" })).toHaveAttribute("aria-sort", "ascending");
    await expect.poll(async () => (await bodyRows(page)).map((cells) => cells[2])).toEqual(["Andrés", "Gabriela"]);
  });
});

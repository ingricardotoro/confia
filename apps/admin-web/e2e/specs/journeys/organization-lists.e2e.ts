import type { Page, Request } from "@playwright/test";
import { GRADE_IDS, LAST_YEAR, MODALITY_IDS, yearId, yearName } from "../../mock-api/organization-seed.ts";
import { bodyRows, firstColumn, openView } from "../../support/organization.ts";
import { expect, test } from "../../support/scenario.ts";

// The lists of the academic structure: the page, the order and the filters are the server's, and they live in the
// URL (docs/ui-ux/04, section 3.2). Every journey asserts the URL, the rows on the screen and, where it matters, the
// query the page sent, so a list that sorted or filtered in the browser would fail. Against the SIMULATED API.
const SECOND_PAGE_YEARS = [2022, 2023, 2024, 2025, 2026].map(yearName);

/** The query of the requests the page makes to a listing, decoded, so `sort=name,asc` reads as it was sent. */
function recordListQueries(page: Page, resource: string): string[] {
  const seen: string[] = [];
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    if (request.method() === "GET" && url.pathname === `/api/v1/organization/${resource}`) seen.push(decodeURIComponent(url.search));
  });
  return seen;
}

const summary = (page: Page) => page.getByRole("navigation", { name: "Paginación" });

test.describe("paging and order of a list, on the server and in the URL", () => {
  test("pages the years: the next page changes the URL and the rows, and Back returns to the first", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "years");
    const queries = recordListQueries(page, "academic-years");
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 30");
    await expect(summary(page)).toContainText("Página 1 de 2");
    await expect(summary(page).getByRole("button", { name: "Anterior" })).toBeDisabled();
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe(yearName(LAST_YEAR));

    await summary(page).getByRole("button", { name: "Siguiente" }).click();

    await expect(page).toHaveURL(/[?&]page=2(&|$)/);
    await expect(summary(page)).toContainText("Mostrando 26 a 30 de 30");
    await expect(summary(page).getByRole("button", { name: "Siguiente" })).toBeDisabled();
    await expect.poll(() => firstColumn(page)).toEqual([2001, 2000, 1999, 1998, 1997].map(yearName));
    expect(queries.at(-1)).toContain("page=1");
    expect(queries.at(-1)).toContain("size=25");

    await page.goBack();

    await expect(page).not.toHaveURL(/page=/);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 30");
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
  });

  test("sorts the years on the server: each click on a header changes the URL, the rows, aria-sort and the query", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "years");
    const queries = recordListQueries(page, "academic-years");
    const byName = page.getByRole("columnheader", { name: "Nombre" });
    // The order the server applies with no `sort` in the URL is the newest first, and the header says so.
    await expect(page.getByRole("columnheader", { name: "Inicio" })).toHaveAttribute("aria-sort", "descending");

    await byName.getByRole("button").click();

    await expect(page).toHaveURL(/[?&]sort=name%3Aasc|[?&]sort=name:asc/);
    await expect(byName).toHaveAttribute("aria-sort", "ascending");
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe(yearName(1997));
    expect(queries.at(-1)).toContain("sort=name,asc");

    await byName.getByRole("button").click();

    await expect(page).toHaveURL(/sort=name(%3A|:)desc/);
    await expect(byName).toHaveAttribute("aria-sort", "descending");
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe(yearName(LAST_YEAR));
    expect(queries.at(-1)).toContain("sort=name,desc");
  });

  test("goes back to the first page when the order changes, and keeps the order when the page does", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "years");
    await page.getByRole("columnheader", { name: "Nombre" }).getByRole("button").click();
    await expect(page).toHaveURL(/sort=name/);

    await summary(page).getByRole("button", { name: "Siguiente" }).click();
    await expect(page).toHaveURL(/page=2/);
    await expect(page).toHaveURL(/sort=name/);
    await expect.poll(() => firstColumn(page)).toEqual(SECOND_PAGE_YEARS);

    await page.getByRole("columnheader", { name: "Fin" }).getByRole("button").click();

    await expect(page).toHaveURL(/sort=endDate(%3A|:)asc/);
    await expect(page).not.toHaveURL(/page=/);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 30");
  });

  test("opens a shared link on the page and order it names, and drops a parameter that does not hold", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "years");

    await page.goto("/organization/academic-years?page=2&sort=name:asc");

    await expect(summary(page)).toContainText("Mostrando 26 a 30 de 30");
    await expect.poll(() => firstColumn(page)).toEqual(SECOND_PAGE_YEARS);
    await expect(page.getByRole("columnheader", { name: "Nombre" })).toHaveAttribute("aria-sort", "ascending");

    await page.goto("/organization/academic-years?page=-4&sort=isActive:asc&pageSize=7");

    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 30");
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe(yearName(LAST_YEAR));
  });

  test("sorts the modalities by code and the sections by name, both on the server", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "modalities");
    await expect.poll(() => firstColumn(page)).toEqual(["PRE", "PRI", "SEC"]);
    const queries = recordListQueries(page, "modalities");

    await page.getByRole("columnheader", { name: "Código" }).getByRole("button").click();
    await page.getByRole("columnheader", { name: "Código" }).getByRole("button").click();

    await expect(page).toHaveURL(/sort=code(%3A|:)desc/);
    await expect.poll(() => firstColumn(page)).toEqual(["SEC", "PRI", "PRE"]);
    expect(queries.at(-1)).toContain("sort=code,desc");

    await page.getByRole("navigation", { name: "Vistas de la estructura académica" }).getByRole("link", { name: "Secciones" }).click();
    await page.getByRole("columnheader", { name: "Nombre" }).getByRole("button").click();
    await page.getByRole("columnheader", { name: "Nombre" }).getByRole("button").click();

    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe("B");
    await expect(page.getByRole("columnheader", { name: "Nombre" })).toHaveAttribute("aria-sort", "descending");
  });
});

test.describe("the filters of the grades and the sections, on the server and in the URL", () => {
  test("filters the grades by modality, shows the empty result with a way out, and restores the filter from the URL", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "grades");
    const queries = recordListQueries(page, "grades");
    const filter = page.getByRole("group", { name: "Filtros" }).getByLabel("Modalidad");
    await expect.poll(() => firstColumn(page)).toEqual(["Primero", "Séptimo", "Segundo", "Octavo", "Tercero"]);

    await filter.selectOption({ label: "Secundaria" });

    await expect(page).toHaveURL(new RegExp(`modalityId=${MODALITY_IDS.secondary}`));
    await expect.poll(() => firstColumn(page)).toEqual(["Séptimo", "Octavo"]);
    expect(queries.at(-1)).toContain(`modalityId=${MODALITY_IDS.secondary}`);
    expect((await bodyRows(page)).map((cells) => cells[2])).toEqual(["Secundaria", "Secundaria"]);

    await filter.selectOption({ label: "Prebásica" });

    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    await page.getByRole("button", { name: "Quitar filtros" }).click();
    await expect(page).not.toHaveURL(/modalityId/);
    await expect.poll(() => firstColumn(page)).toHaveLength(5);

    await page.goto(`/organization/grades?modalityId=${MODALITY_IDS.primary}`);

    await expect(page.getByRole("group", { name: "Filtros" }).getByLabel("Modalidad")).toHaveValue(MODALITY_IDS.primary);
    await expect.poll(() => firstColumn(page)).toEqual(["Primero", "Segundo", "Tercero"]);
  });

  test("filters the sections by year and by grade, together, and clears them to see every section again", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "sections");
    const queries = recordListQueries(page, "sections");
    const filters = page.getByRole("group", { name: "Filtros" });
    await expect.poll(() => firstColumn(page)).toEqual(["A", "A", "A", "B"]);

    await filters.getByLabel("Año lectivo").selectOption({ label: yearName(LAST_YEAR - 1) });

    await expect(page).toHaveURL(new RegExp(`academicYearId=${yearId(LAST_YEAR - 1)}`));
    await expect.poll(async () => (await bodyRows(page)).map((cells) => cells.slice(0, 3))).toEqual([["A", yearName(LAST_YEAR - 1), "Primaria · Primero"]]);
    expect(queries.at(-1)).toContain(`academicYearId=${yearId(LAST_YEAR - 1)}`);

    await filters.getByLabel("Año lectivo").selectOption({ label: yearName(LAST_YEAR) });
    await filters.getByLabel("Grado").selectOption({ label: "Primaria · Segundo" });

    await expect(page).toHaveURL(new RegExp(`gradeId=${GRADE_IDS.second}`));
    await expect.poll(async () => (await bodyRows(page)).map((cells) => cells.slice(0, 4))).toEqual([["A", yearName(LAST_YEAR), "Primaria · Segundo", "25"]]);
    expect(queries.at(-1)).toContain(`gradeId=${GRADE_IDS.second}`);
    expect(queries.at(-1)).toContain(`academicYearId=${yearId(LAST_YEAR)}`);

    await filters.getByLabel("Grado").selectOption({ label: "Primaria · Tercero" });
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    await page.getByRole("button", { name: "Quitar filtros" }).click();

    await expect(page).not.toHaveURL(/gradeId|academicYearId/);
    await expect.poll(() => firstColumn(page)).toHaveLength(4);
  });
});

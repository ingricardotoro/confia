import type { Page } from "@playwright/test";
import { GRADE_IDS, SECTION_IDS, yearId } from "../../mock-api/organization-seed.ts";
import { bodyRows, firstColumn, openEnrollments, recordEnrollmentQueries, summary, test } from "../../support/enrollments.ts";
import { expect } from "../../support/scenario.ts";

// The list of enrollments: the page, the order and the filters are the server's, and they live in the URL (docs/ui-ux/04,
// section 3.2). Every journey asserts the URL, the rows on the screen and, where it matters, the query the page sent, so a
// list that sorted or filtered in the browser would fail. Against the SIMULATED API.
const students = async (page: Page) => (await bodyRows(page)).map((cells) => cells[1]);

test.describe("the year in progress, paging and order", () => {
  test("opens on the year in progress without writing it in the address, and the filter shows it", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);
    const queries = recordEnrollmentQueries(page);

    await expect(page.getByLabel("Año lectivo")).toHaveValue(yearId(2026));
    await expect(page).not.toHaveURL(/academicYearId/);
    await page.reload();
    await expect(page.getByRole("table", { name: "Matrículas de la institución" })).toBeVisible();
    await expect.poll(() => queries.at(-1)).toBeDefined();
    expect(queries.at(-1)).not.toContain("academicYearId");
    // A year with no active enrollment is a filter of its own.
    await page.getByLabel("Año lectivo").selectOption(yearId(2025));
    await expect(page).toHaveURL(new RegExp(`academicYearId=${yearId(2025)}`));
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    expect(queries.at(-1)).toContain(`academicYearId=${yearId(2025)}`);
  });

  test("pages the enrollments: the next page changes the URL and the rows, and Back returns to the first", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);
    const queries = recordEnrollmentQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    await expect(summary(page)).toContainText("Página 1 de 2");

    await summary(page).getByRole("button", { name: "Siguiente" }).click();

    await expect(page).toHaveURL(/[?&]page=2(&|$)/);
    await expect(summary(page)).toContainText("Mostrando 26 a 37 de 37");
    await expect.poll(() => firstColumn(page)).toHaveLength(12);
    expect(queries.at(-1)).toContain("page=1");

    await page.goBack();

    await expect(page).not.toHaveURL(/page=/);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 37");
  });

  test("sorts on the server: last name is the default, and each click on a header changes the URL, the rows, aria-sort and the query", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);
    const queries = recordEnrollmentQueries(page);
    await expect(page.getByRole("columnheader", { name: "Estudiante" })).toHaveAttribute("aria-sort", "ascending");
    await expect.poll(async () => (await students(page))[0]).toBe("Aguilar, Ana");

    await page.getByRole("columnheader", { name: "Estudiante" }).getByRole("button").click();

    await expect(page).toHaveURL(/sort=studentLastName(%3A|:)desc/);
    await expect.poll(async () => (await students(page))[0]).toMatch(/^Pérez/);
    expect(queries.at(-1)).toContain("sort=studentLastName,desc");

    await page.getByRole("columnheader", { name: "Estado" }).getByRole("button").click();

    await expect(page).toHaveURL(/sort=status(%3A|:)asc/);
    await expect(page.getByRole("columnheader", { name: "Estado" })).toHaveAttribute("aria-sort", "ascending");
    expect(queries.at(-1)).toContain("sort=status,asc");
    // Not sortable: the server cannot order by them.
    await expect(page.getByRole("columnheader", { name: "Grado y sección" }).getByRole("button")).toHaveCount(0);
  });
});

test.describe("the filters, on the server and in the URL", () => {
  test("filters by state, grade and section, each one in the address and in the query, and clears them", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);
    const queries = recordEnrollmentQueries(page);

    await page.getByLabel("Estado").selectOption("WITHDRAWN");
    await expect(page).toHaveURL(/status=WITHDRAWN/);
    await expect(summary(page)).toContainText("de 6");
    expect(queries.at(-1)).toContain("status=WITHDRAWN");

    await page.getByLabel("Estado").selectOption("");
    await page.getByLabel("Grado").selectOption(GRADE_IDS.second);
    await expect(page).toHaveURL(new RegExp(`gradeId=${GRADE_IDS.second}`));
    await expect(summary(page)).toContainText("de 25");
    expect(queries.at(-1)).toContain(`gradeId=${GRADE_IDS.second}`);

    await page.getByLabel("Grado").selectOption("");
    // The sections to choose are those of the year shown, and each says its grade.
    await expect(page.getByLabel("Sección", { exact: true }).locator(`option[value="${SECTION_IDS.firstAPast}"]`)).toHaveCount(0);
    await page.getByLabel("Sección", { exact: true }).selectOption(SECTION_IDS.firstB);
    await expect(page).toHaveURL(new RegExp(`sectionId=${SECTION_IDS.firstB}`));
    await expect(summary(page)).toContainText("de 8");
    await expect(page.getByLabel("Sección", { exact: true }).locator("option", { hasText: "Primaria · Primero · B" })).toHaveCount(1);

    // A filter that leaves nothing offers the way back.
    await page.getByLabel("Estado").selectOption("TRANSFERRED");
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    await page.getByRole("button", { name: "Quitar filtros" }).click();
    await expect(page).not.toHaveURL(/status=|sectionId=|gradeId=/);
    await expect(summary(page)).toContainText("de 37");
  });

  test("opens a shared address with its filters, page and order applied, and the filters show them", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);
    const queries = recordEnrollmentQueries(page);

    await page.goto(`/enrollments?status=WITHDRAWN&sort=enrolledOn:desc&pageSize=50&sectionId=${SECTION_IDS.firstB}`);

    await expect(page.getByLabel("Estado")).toHaveValue("WITHDRAWN");
    await expect(page.getByLabel("Sección", { exact: true })).toHaveValue(SECTION_IDS.firstB);
    await expect(summary(page)).toContainText("de 5");
    await expect(page.getByRole("columnheader", { name: "Fecha de matrícula" })).toHaveAttribute("aria-sort", "descending");
    await expect.poll(() => queries.at(-1)).toContain("sort=enrolledOn,desc");
    expect(queries.at(-1)).toContain("size=50");
  });
});

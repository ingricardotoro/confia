import { bodyRows, filters, firstColumn, openPlans, recordPlanQueries, summary, test } from "../../support/charges.ts";
import { expect } from "../../support/scenario.ts";

// The list of plans: the page, the order and the filters are the server's, and they live in the URL (docs/ui-ux/04, section
// 3.2). Every journey asserts the URL, the rows on the screen and, where it matters, the query the page sent, so a list that
// sorted or filtered in the browser would fail. Against the SIMULATED API.
test.describe("paging and order of the plans, on the server and in the URL", () => {
  test("pages the plans: the next page changes the URL and the rows, and Back returns to the first", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);
    const queries = recordPlanQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 28");
    await expect(summary(page)).toContainText("Página 1 de 2");
    // No `sort` in the URL: the server's order is by name, and the header says so.
    await expect(page.getByRole("columnheader", { name: "Nombre" })).toHaveAttribute("aria-sort", "ascending");

    await summary(page).getByRole("button", { name: "Siguiente" }).click();

    await expect(page).toHaveURL(/[?&]page=2(&|$)/);
    await expect(summary(page)).toContainText("Mostrando 26 a 28 de 28");
    await expect.poll(() => firstColumn(page)).toEqual(["Sección A de primero 2026", "Secundaria 2026", "Segundo grado 2026"]);
    expect(queries.at(-1)).toContain("page=1");
    expect(queries.at(-1)).toContain("size=25");

    await page.goBack();

    await expect(page).not.toHaveURL(/page=/);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
  });

  test("sorts on the server by name, in both directions, and the query says so", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);
    const queries = recordPlanQueries(page);
    const byName = page.getByRole("columnheader", { name: "Nombre" });
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe("Plan genérico 01");

    await byName.getByRole("button").click();

    await expect(page).toHaveURL(/sort=name(%3A|:)desc/);
    await expect(byName).toHaveAttribute("aria-sort", "descending");
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe("Segundo grado 2026");
    expect(queries.at(-1)).toContain("sort=name,desc");

    await byName.getByRole("button").click();

    await expect(page).toHaveURL(/sort=name(%3A|:)asc/);
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe("Plan genérico 01");
  });

  test("opens a shared link on the page and order it names, and drops a parameter that does not hold", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);

    await page.goto("/charges/plans?page=2&sort=name:desc");

    await expect(summary(page)).toContainText("Mostrando 26 a 28 de 28");
    await expect.poll(() => firstColumn(page)).toEqual(["Plan genérico 03", "Plan genérico 02", "Plan genérico 01"]);

    await page.goto("/charges/plans?page=-4&sort=version:asc&pageSize=7&targetScope=ALL&status=DELETED&academicYearId=x");

    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 28");
    await expect(filters(page).getByLabel("Ámbito")).toHaveValue("");
    await expect(filters(page).getByLabel("Estado")).toHaveValue("");
    await expect(filters(page).getByLabel("Año lectivo")).toHaveValue("");
  });
});

test.describe("the filters of the plans, on the server and in the URL", () => {
  test("filters by status, shows the empty result with a way out, and restores the filter from the URL", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);
    const queries = recordPlanQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);

    await filters(page).getByLabel("Estado").selectOption({ label: "Archivado" });

    await expect(page).toHaveURL(/status=ARCHIVED/);
    await expect.poll(() => firstColumn(page)).toEqual(["Sección A de primero 2026"]);
    expect(queries.at(-1)).toContain("status=ARCHIVED");
    await expect(summary(page)).toContainText("Mostrando 1 a 1 de 1");

    await filters(page).getByLabel("Ámbito").selectOption({ label: "Un grado" });

    await expect(page).toHaveURL(/targetScope=GRADE/);
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    expect(queries.at(-1)).toContain("targetScope=GRADE");
    await page.getByRole("button", { name: "Quitar filtros" }).click();
    await expect(page).not.toHaveURL(/status|targetScope/);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);

    await page.goto("/charges/plans?targetScope=GRADE");

    await expect(filters(page).getByLabel("Ámbito")).toHaveValue("GRADE");
    await expect.poll(() => firstColumn(page)).toEqual(["Primaria 2026", "Segundo grado 2026"]);
  });

  test("filters by academic year and goes back to the first page when a filter changes", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);
    await summary(page).getByRole("button", { name: "Siguiente" }).click();
    await expect(page).toHaveURL(/page=2/);

    await filters(page).getByLabel("Año lectivo").selectOption({ label: "Año lectivo 2025" });

    await expect(page).toHaveURL(/academicYearId=/);
    await expect(page).not.toHaveURL(/page=/);
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();

    await filters(page).getByLabel("Año lectivo").selectOption({ label: "Año lectivo 2026" });

    await expect.poll(async () => (await bodyRows(page)).length).toBe(25);
  });
});

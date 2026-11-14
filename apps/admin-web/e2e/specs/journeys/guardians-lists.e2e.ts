import { expect } from "../../support/scenario.ts";
import { bodyRows, firstColumn, openGuardians, recordGuardianQueries, searchBox, summary, test } from "../../support/guardians.ts";

// The list of guardians: the page, the order, the text search and the status filter are the server's, and they live in the URL
// (docs/ui-ux/04, section 3.2). Every journey asserts the URL, the rows on the screen and the query the page sent, so a list
// that searched, sorted or filtered in the browser would fail. Against the SIMULATED API (30 guardians, every tenth inactive).
test.describe("paging, order and filters, on the server and in the URL", () => {
  test("pages the guardians: the next page changes the URL and the rows, and Back returns to the first", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId);
    const queries = recordGuardianQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 30");

    await summary(page).getByRole("button", { name: "Siguiente" }).click();

    await expect(page).toHaveURL(/[?&]page=2(&|$)/);
    await expect(summary(page)).toContainText("Mostrando 26 a 30 de 30");
    await expect.poll(() => firstColumn(page)).toHaveLength(5);
    expect(queries.at(-1)).toContain("page=1");
    await page.goBack();
    await expect(page).not.toHaveURL(/page=/);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 30");
  });

  test("sorts on the server by last name, code and state; the other columns are not sortable", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId);
    const queries = recordGuardianQueries(page);

    await page.getByRole("columnheader", { name: "Código" }).getByRole("button").click();
    await expect(page).toHaveURL(/sort=code(%3A|:)asc/);
    await expect(page.getByRole("columnheader", { name: "Código" })).toHaveAttribute("aria-sort", "ascending");
    expect(queries.at(-1)).toContain("sort=code,asc");
    await page.getByRole("columnheader", { name: "Código" }).getByRole("button").click();
    await expect(page).toHaveURL(/sort=code(%3A|:)desc/);
    await expect.poll(async () => (await firstColumn(page))[0]).toBe("P-000030");
    await page.getByRole("columnheader", { name: "Estado" }).getByRole("button").click();
    expect(queries.at(-1)).toContain("sort=status,asc");
    await expect(page.getByRole("columnheader", { name: "RTN" }).getByRole("button")).toHaveCount(0);
    await expect(page.getByRole("columnheader", { name: "Nombres" }).getByRole("button")).toHaveCount(0);
  });

  test("searches from two characters, filters by state, and removes the filters; all of it in the URL and the query", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId);
    const queries = recordGuardianQueries(page);

    await searchBox(page).fill("a");
    await expect(page.getByText("Escriba al menos 2 caracteres para buscar.")).toBeVisible();
    await searchBox(page).fill("Cruz");
    await expect(page).toHaveURL(/q=Cruz/);
    await expect.poll(() => firstColumn(page)).toEqual(["P-000005"]);
    expect(queries.at(-1)).toContain("q=Cruz");
    expect(queries.some((query) => query.includes("q=a&") || query.endsWith("q=a"))).toBe(false);

    await searchBox(page).fill("");
    await page.getByLabel("Estado", { exact: true }).selectOption({ label: "Inactivo" });
    await expect(page).toHaveURL(/status=INACTIVE/);
    await expect.poll(() => firstColumn(page)).toEqual(["P-000030", "P-000020", "P-000010"].sort());
    expect(queries.at(-1)).toContain("status=INACTIVE");

    await searchBox(page).fill("zzzz");
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    await page.getByRole("button", { name: "Quitar filtros" }).click();
    await expect(page).not.toHaveURL(/q=|status=/);
    await expect.poll(async () => (await bodyRows(page)).length).toBe(25);
  });

  test("opens a shared address with the page, order, text and state it names", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId);

    await page.goto("/guardians?sort=code:desc&status=ACTIVE&q=Ma");

    await expect(page.getByRole("columnheader", { name: "Código" })).toHaveAttribute("aria-sort", "descending");
    await expect(searchBox(page)).toHaveValue("Ma");
    await expect(page.getByLabel("Estado", { exact: true })).toHaveValue("ACTIVE");
  });
});

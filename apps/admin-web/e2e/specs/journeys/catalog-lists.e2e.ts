import { bodyRows, filters, firstColumn, openCatalog, recordCatalogQueries, summary, test } from "../../support/catalog.ts";
import { expect } from "../../support/scenario.ts";

// The list of payment concepts: the page, the order and the filters are the server's, and they live in the URL
// (docs/ui-ux/04, section 3.2). Every journey asserts the URL, the rows on the screen and, where it matters, the query
// the page sent, so a list that sorted or filtered in the browser would fail. Against the SIMULATED API.
test.describe("paging and order, on the server and in the URL", () => {
  test("pages the concepts: the next page changes the URL and the rows, and Back returns to the first", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);
    const queries = recordCatalogQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 30");
    await expect(summary(page)).toContainText("Página 1 de 2");
    await expect(summary(page).getByRole("button", { name: "Anterior" })).toBeDisabled();
    // No `sort` in the URL: the server's order is by code, and the header says so.
    await expect(page.getByRole("columnheader", { name: "Código" })).toHaveAttribute("aria-sort", "ascending");

    await summary(page).getByRole("button", { name: "Siguiente" }).click();

    await expect(page).toHaveURL(/[?&]page=2(&|$)/);
    await expect(summary(page)).toContainText("Mostrando 26 a 30 de 30");
    await expect.poll(() => firstColumn(page)).toEqual(["X-20", "X-21", "X-22", "X-23", "X-24"]);
    await expect(summary(page).getByRole("button", { name: "Siguiente" })).toBeDisabled();
    expect(queries.at(-1)).toContain("page=1");
    expect(queries.at(-1)).toContain("size=25");

    await page.goBack();

    await expect(page).not.toHaveURL(/page=/);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 30");
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
  });

  test("sorts on the server: each click on a header changes the URL, the rows, aria-sort and the query", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);
    const queries = recordCatalogQueries(page);
    const byName = page.getByRole("columnheader", { name: "Nombre" });
    await expect.poll(() => firstColumn(page).then((codes) => codes.slice(0, 3))).toEqual(["COL", "LIB", "MAT"]);

    await byName.getByRole("button").click();

    await expect(page).toHaveURL(/[?&]sort=name%3Aasc|[?&]sort=name:asc/);
    await expect(byName).toHaveAttribute("aria-sort", "ascending");
    // 'Colegiatura' comes before the twenty four generic names, and 'Transporte escolar' after all of them.
    await expect.poll(() => firstColumn(page).then((codes) => codes.slice(0, 2))).toEqual(["COL", "X-01"]);
    expect(queries.at(-1)).toContain("sort=name,asc");

    await byName.getByRole("button").click();

    await expect(page).toHaveURL(/sort=name(%3A|:)desc/);
    await expect(byName).toHaveAttribute("aria-sort", "descending");
    await expect.poll(() => firstColumn(page).then((codes) => codes[0])).toBe("TRA");
    expect(queries.at(-1)).toContain("sort=name,desc");
  });

  test("goes back to the first page when the order changes, and keeps the order when the page does", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);
    await page.getByRole("columnheader", { name: "Nombre" }).getByRole("button").click();
    await expect(page).toHaveURL(/sort=name/);

    await summary(page).getByRole("button", { name: "Siguiente" }).click();
    await expect(page).toHaveURL(/page=2/);
    await expect(page).toHaveURL(/sort=name/);

    await page.getByRole("columnheader", { name: "Código" }).getByRole("button").click();

    await expect(page).toHaveURL(/sort=code(%3A|:)(asc|desc)/);
    await expect(page).not.toHaveURL(/page=/);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 30");
  });

  test("opens a shared link on the page and order it names, and drops a parameter that does not hold", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);

    await page.goto("/catalog?page=2&sort=code:desc");

    await expect(summary(page)).toContainText("Mostrando 26 a 30 de 30");
    await expect.poll(() => firstColumn(page)).toEqual(["OTR", "MUL", "MAT", "LIB", "COL"]);
    await expect(page.getByRole("columnheader", { name: "Código" })).toHaveAttribute("aria-sort", "descending");

    await page.goto("/catalog?page=-4&sort=category:asc&pageSize=7&category=BOOKS&active=maybe");

    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 30");
    await expect(filters(page).getByLabel("Categoría")).toHaveValue("");
    await expect(filters(page).getByLabel("Estado")).toHaveValue("");
  });
});

test.describe("the filters, on the server and in the URL", () => {
  test("filters by category, shows the empty result with a way out, and restores the filter from the URL", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);
    const queries = recordCatalogQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);

    await filters(page).getByLabel("Categoría").selectOption({ label: "Multa" });

    await expect(page).toHaveURL(/category=FINE/);
    await expect.poll(() => firstColumn(page)).toEqual(["MUL"]);
    expect(queries.at(-1)).toContain("category=FINE");
    await expect(summary(page)).toContainText("Mostrando 1 a 1 de 1");

    await filters(page).getByLabel("Estado").selectOption({ label: "Inactivo" });

    await expect(page).toHaveURL(/active=false/);
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    expect(queries.at(-1)).toContain("active=false");
    await page.getByRole("button", { name: "Quitar filtros" }).click();
    await expect(page).not.toHaveURL(/category|active/);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);

    await page.goto("/catalog?category=TRANSPORT");

    await expect(filters(page).getByLabel("Categoría")).toHaveValue("TRANSPORT");
    await expect.poll(() => firstColumn(page)).toEqual(["TRA"]);
  });

  test("filters by state, shows the inactive concept, and goes back to the first page when a filter changes", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);
    await summary(page).getByRole("button", { name: "Siguiente" }).click();
    await expect(page).toHaveURL(/page=2/);

    await filters(page).getByLabel("Estado").selectOption({ label: "Inactivo" });

    await expect(page).toHaveURL(/active=false/);
    await expect(page).not.toHaveURL(/page=/);
    await expect.poll(async () => (await bodyRows(page)).map((cells) => cells.slice(0, 2).concat(cells[5] ?? ""))).toEqual([["OTR", "Otros ingresos", "Inactivo"]]);

    await page.goto("/catalog?active=true&category=OTHER");

    await expect(summary(page)).toContainText("Mostrando 1 a 24 de 24");
  });
});

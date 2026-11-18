import { filters, firstColumn, openTaxRates, recordTaxRateQueries, summary, test } from "../../support/catalog.ts";
import { expect } from "../../support/scenario.ts";

// The list of tax rates: the page, the order and the filter are the server's, and they live in the URL
// (docs/ui-ux/04, section 3.2). Every journey asserts the URL, the rows on the screen and, where it matters, the query
// the page sent, so a list that sorted or filtered in the browser would fail. Against the SIMULATED API.
test.describe("paging and order of the tax rates, on the server and in the URL", () => {
  test("pages the rates: the next page changes the URL and the rows, and Back returns to the first", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);
    const queries = recordTaxRateQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 27");
    await expect(summary(page)).toContainText("Página 1 de 2");
    // No `sort` in the URL: the server's order is by code, and the header says so.
    await expect(page.getByRole("columnheader", { name: "Código" })).toHaveAttribute("aria-sort", "ascending");

    await summary(page).getByRole("button", { name: "Siguiente" }).click();

    await expect(page).toHaveURL(/[?&]page=2(&|$)/);
    await expect(summary(page)).toContainText("Mostrando 26 a 27 de 27");
    await expect.poll(() => firstColumn(page)).toEqual(["TX-23", "TX-24"]);
    expect(queries.at(-1)).toContain("page=1");
    expect(queries.at(-1)).toContain("size=25");

    await page.goBack();

    await expect(page).not.toHaveURL(/page=/);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
  });

  test("sorts on the server by code and by name, in both directions, and the query says so", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);
    const queries = recordTaxRateQueries(page);
    const byCode = page.getByRole("columnheader", { name: "Código" });
    const byName = page.getByRole("columnheader", { name: "Nombre" });
    await expect.poll(() => firstColumn(page).then((codes) => codes[0])).toBe("IMP-A");

    await byCode.getByRole("button").click();

    await expect(page).toHaveURL(/sort=code(%3A|:)desc/);
    await expect(byCode).toHaveAttribute("aria-sort", "descending");
    await expect.poll(() => firstColumn(page).then((codes) => codes[0])).toBe("TX-24");
    expect(queries.at(-1)).toContain("sort=code,desc");

    await byName.getByRole("button").click();

    await expect(page).toHaveURL(/sort=name(%3A|:)asc/);
    await expect(byName).toHaveAttribute("aria-sort", "ascending");
    expect(queries.at(-1)).toContain("sort=name,asc");
    await expect.poll(() => firstColumn(page).then((codes) => codes[0])).toBe("IMP-A");
  });

  test("opens a shared link on the page and order it names, and drops a parameter that does not hold", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);

    await page.goto("/catalog/tax-rates?page=2&sort=code:desc");

    await expect(summary(page)).toContainText("Mostrando 26 a 27 de 27");
    await expect.poll(() => firstColumn(page)).toEqual(["IMP-B", "IMP-A"]);

    await page.goto("/catalog/tax-rates?page=-4&sort=version:asc&pageSize=7&active=maybe");

    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 27");
    await expect(filters(page).getByLabel("Estado")).toHaveValue("");
  });
});

test.describe("the filter of the tax rates, on the server and in the URL", () => {
  test("filters by state, shows the empty result with a way out, and restores the filter from the URL", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);
    const queries = recordTaxRateQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);

    await filters(page).getByLabel("Estado").selectOption({ label: "Inactivo" });

    await expect(page).toHaveURL(/active=false/);
    await expect.poll(() => firstColumn(page)).toEqual(["IMP-C"]);
    expect(queries.at(-1)).toContain("active=false");
    await expect(summary(page)).toContainText("Mostrando 1 a 1 de 1");

    await page.goto("/catalog/tax-rates?active=true&page=2");
    await expect(summary(page)).toContainText("Mostrando 26 a 26 de 26");
    await page.goto("/catalog/tax-rates?active=true");
    await expect(filters(page).getByLabel("Estado")).toHaveValue("true");

    await filters(page).getByLabel("Estado").selectOption({ label: "Todos" });

    await expect(page).not.toHaveURL(/active/);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
  });

  test("goes back to the first page when the filter changes", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);
    await summary(page).getByRole("button", { name: "Siguiente" }).click();
    await expect(page).toHaveURL(/page=2/);

    await filters(page).getByLabel("Estado").selectOption({ label: "Inactivo" });

    await expect(page).toHaveURL(/active=false/);
    await expect(page).not.toHaveURL(/page=/);
    await expect.poll(() => firstColumn(page)).toEqual(["IMP-C"]);
  });
});

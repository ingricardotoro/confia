import {
  bodyRows,
  filters,
  firstColumn,
  openPriceLists,
  recordPriceListQueries,
  summary,
  test,
} from "../../support/catalog.ts";
import { expect } from "../../support/scenario.ts";

// The list of price lists: the page, the order and the filters are the server's, and they live in the URL
// (docs/ui-ux/04, section 3.2). Every journey asserts the URL, the rows on the screen and, where it matters, the query
// the page sent, so a list that sorted or filtered in the browser would fail. Against the SIMULATED API.
test.describe("paging and order of the price lists, on the server and in the URL", () => {
  test("pages the lists: the next page changes the URL and the rows, and Back returns to the first", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId);
    const queries = recordPriceListQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 27");
    await expect(summary(page)).toContainText("Página 1 de 2");
    // No `sort` in the URL: the server's order is by name, and the header says so.
    await expect(page.getByRole("columnheader", { name: "Nombre" })).toHaveAttribute("aria-sort", "ascending");

    await summary(page).getByRole("button", { name: "Siguiente" }).click();

    await expect(page).toHaveURL(/[?&]page=2(&|$)/);
    await expect(summary(page)).toContainText("Mostrando 26 a 27 de 27");
    await expect.poll(() => firstColumn(page)).toEqual(["Lista genérica 23", "Lista genérica 24"]);
    expect(queries.at(-1)).toContain("page=1");
    expect(queries.at(-1)).toContain("size=25");

    await page.goBack();

    await expect(page).not.toHaveURL(/page=/);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
  });

  test("sorts on the server by name, in both directions, and the query says so", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId);
    const queries = recordPriceListQueries(page);
    const byName = page.getByRole("columnheader", { name: "Nombre" });
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe("2025 — Institución");

    await byName.getByRole("button").click();

    await expect(page).toHaveURL(/sort=name(%3A|:)desc/);
    await expect(byName).toHaveAttribute("aria-sort", "descending");
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe("Lista genérica 24");
    expect(queries.at(-1)).toContain("sort=name,desc");

    await byName.getByRole("button").click();

    await expect(page).toHaveURL(/sort=name(%3A|:)asc/);
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe("2025 — Institución");
  });

  test("opens a shared link on the page and order it names, and drops a parameter that does not hold", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId);

    await page.goto("/catalog/price-lists?page=2&sort=name:desc");

    await expect(summary(page)).toContainText("Mostrando 26 a 27 de 27");
    await expect.poll(() => firstColumn(page)).toEqual(["2026 — Institución", "2025 — Institución"]);

    await page.goto("/catalog/price-lists?page=-4&sort=version:asc&pageSize=7&scope=ALL&status=DELETED&academicYearId=x");

    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 27");
    await expect(filters(page).getByLabel("Ámbito")).toHaveValue("");
    await expect(filters(page).getByLabel("Estado")).toHaveValue("");
    await expect(filters(page).getByLabel("Año lectivo")).toHaveValue("");
  });
});

test.describe("the filters of the price lists, on the server and in the URL", () => {
  test("filters by status, shows the empty result with a way out, and restores the filter from the URL", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId);
    const queries = recordPriceListQueries(page);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);

    await filters(page).getByLabel("Estado").selectOption({ label: "Archivada" });

    await expect(page).toHaveURL(/status=ARCHIVED/);
    await expect.poll(() => firstColumn(page)).toEqual(["2025 — Institución"]);
    expect(queries.at(-1)).toContain("status=ARCHIVED");
    await expect(summary(page)).toContainText("Mostrando 1 a 1 de 1");

    await filters(page).getByLabel("Ámbito").selectOption({ label: "Un grado" });

    await expect(page).toHaveURL(/scope=GRADE/);
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    expect(queries.at(-1)).toContain("scope=GRADE");
    await page.getByRole("button", { name: "Quitar filtros" }).click();
    await expect(page).not.toHaveURL(/status|scope/);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);

    await page.goto("/catalog/price-lists?scope=GRADE");

    await expect(filters(page).getByLabel("Ámbito")).toHaveValue("GRADE");
    await expect.poll(() => firstColumn(page)).toEqual(["2026 — Primero"]);
  });

  test("filters by academic year and goes back to the first page when a filter changes", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId);
    await summary(page).getByRole("button", { name: "Siguiente" }).click();
    await expect(page).toHaveURL(/page=2/);

    await filters(page).getByLabel("Año lectivo").selectOption({ label: "Año lectivo 2026" });

    await expect(page).toHaveURL(/academicYearId=/);
    await expect(page).not.toHaveURL(/page=/);
    await expect.poll(async () => (await bodyRows(page)).map((cells) => cells[0])).toEqual(["2026 — Institución", "2026 — Primero"]);
  });
});

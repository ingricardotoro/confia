import { describe, expect, it } from "vitest";
import { paymentConceptsSearch, priceListsSearch, taxRatesSearch } from "./catalog-search.ts";

describe("the search of the payment concepts list", () => {
  it("holds the defaults when the address says nothing", () => {
    expect(paymentConceptsSearch.parse({})).toEqual({ page: 1, pageSize: 25 });
  });

  it("keeps a valid page, order and filters", () => {
    expect(paymentConceptsSearch.parse({ page: 3, pageSize: 50, sort: "code:desc", category: "FINE", active: false })).toEqual({
      page: 3,
      pageSize: 50,
      sort: "code:desc",
      category: "FINE",
      active: false,
    });
  });

  it("drops each parameter that does not hold, so a bad link never breaks the screen", () => {
    expect(paymentConceptsSearch.parse({ page: -4, pageSize: 7, sort: "category:asc", category: "BOOKS", active: "maybe" })).toEqual({
      page: 1,
      pageSize: 25,
    });
  });
});

describe("the search of the price lists list", () => {
  const YEAR = "00000000-0000-4000-8000-000000000a01";

  it("holds the defaults when the address says nothing", () => {
    expect(priceListsSearch.parse({})).toEqual({ page: 1, pageSize: 25 });
  });

  it("keeps a valid page, order and the three filters", () => {
    const search = { page: 2, pageSize: 100, sort: "createdAt:desc", academicYearId: YEAR, scope: "GRADE", status: "ARCHIVED" };

    expect(priceListsSearch.parse(search)).toEqual(search);
  });

  it("drops each parameter that does not hold", () => {
    expect(priceListsSearch.parse({ page: 0, pageSize: 3, sort: "code:asc", academicYearId: "not-an-id", scope: "ALL", status: "DELETED" })).toEqual({
      page: 1,
      pageSize: 25,
    });
  });
});

describe("the search of the tax rates list", () => {
  it("holds the defaults when the address says nothing", () => {
    expect(taxRatesSearch.parse({})).toEqual({ page: 1, pageSize: 25 });
  });

  it("keeps a valid page, order and the active filter", () => {
    const search = { page: 2, pageSize: 50, sort: "name:desc", active: true };

    expect(taxRatesSearch.parse(search)).toEqual(search);
  });

  it("drops each parameter that does not hold", () => {
    expect(taxRatesSearch.parse({ page: 0, pageSize: 3, sort: "createdAt:asc", active: "yes" })).toEqual({ page: 1, pageSize: 25 });
  });
});

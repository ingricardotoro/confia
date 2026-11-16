import { describe, expect, it } from "vitest";
import { paymentConceptsSearch } from "./catalog-search.ts";

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

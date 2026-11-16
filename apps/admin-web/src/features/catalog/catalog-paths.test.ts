import { describe, expect, it } from "vitest";
import { CATALOG_VIEWS, isCurrentCatalogView, priceListPath } from "./catalog-paths.ts";

describe("the paths of the catalog", () => {
  it("names the detail of a list below the lists, with the id encoded", () => {
    expect(priceListPath("abc")).toBe("/catalog/price-lists/abc");
    expect(priceListPath("a/b?c")).toBe("/catalog/price-lists/a%2Fb%3Fc");
  });

  it("marks the concepts only on their own path, and the price lists on theirs and below", () => {
    const current = (pathname: string) => CATALOG_VIEWS.map((view) => isCurrentCatalogView(view, pathname));

    expect(current("/catalog")).toEqual([true, false]);
    expect(current("/catalog/")).toEqual([true, false]);
    expect(current("/catalog/price-lists")).toEqual([false, true]);
    expect(current("/catalog/price-lists/abc")).toEqual([false, true]);
    expect(current("/catalog/price-listsx")).toEqual([false, false]);
  });
});

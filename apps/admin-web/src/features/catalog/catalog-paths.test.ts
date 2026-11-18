import { describe, expect, it } from "vitest";
import { CATALOG_VIEWS, isCurrentCatalogView, priceListPath, taxRatePath } from "./catalog-paths.ts";

describe("the paths of the catalog", () => {
  it("names the detail of a list below the lists, with the id encoded", () => {
    expect(priceListPath("abc")).toBe("/catalog/price-lists/abc");
    expect(priceListPath("a/b?c")).toBe("/catalog/price-lists/a%2Fb%3Fc");
  });

  it("names the detail of a tax rate below the rates, with the id encoded", () => {
    expect(taxRatePath("abc")).toBe("/catalog/tax-rates/abc");
    expect(taxRatePath("a/b?c")).toBe("/catalog/tax-rates/a%2Fb%3Fc");
  });

  it("marks the concepts only on their own path, and the price lists on theirs and below", () => {
    const current = (pathname: string) => CATALOG_VIEWS.map((view) => isCurrentCatalogView(view, pathname));

    expect(current("/catalog")).toEqual([true, false, false]);
    expect(current("/catalog/")).toEqual([true, false, false]);
    expect(current("/catalog/price-lists")).toEqual([false, true, false]);
    expect(current("/catalog/price-lists/abc")).toEqual([false, true, false]);
    expect(current("/catalog/tax-rates")).toEqual([false, false, true]);
    expect(current("/catalog/tax-rates/abc")).toEqual([false, false, true]);
    expect(current("/catalog/price-listsx")).toEqual([false, false, false]);
    expect(current("/catalog/tax-ratesx")).toEqual([false, false, false]);
  });
});

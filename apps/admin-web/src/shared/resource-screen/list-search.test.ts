import { describe, expect, it } from "vitest";
import { searchSortOf, tableSortOf, toApiParams, toUrlSearch } from "./list-search.ts";

// The search parameters of a listing (docs/ui-ux/04, section 3.2): what holds is kept, what does not is replaced by
// its default, and the API receives its own form of page and order.
const ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";

describe("the order of the table", () => {
  it("is a column and a direction, and goes back to the address form", () => {
    expect(tableSortOf("name:desc")).toEqual({ columnId: "name", direction: "desc" });
    expect(tableSortOf(undefined)).toBeUndefined();
    expect(searchSortOf({ columnId: "name", direction: "asc" })).toBe("name:asc");
  });
});

describe("what goes to the API", () => {
  it("counts pages from 0, calls the size `size`, writes the order as field,direction and carries the filters", () => {
    expect(toApiParams({ page: 3, pageSize: 50, sort: "name:desc", modalityId: ID })).toEqual({
      page: 2,
      size: 50,
      sort: "name,desc",
      modalityId: ID,
    });
  });

  it("leaves out the order and the filters that are not set", () => {
    expect(toApiParams({ page: 1, pageSize: 25, sort: undefined, modalityId: undefined })).toEqual({ page: 0, size: 25, modalityId: undefined });
  });
});

describe("what goes to the address", () => {
  it("leaves out a parameter that holds its default, and one that is not set", () => {
    expect(toUrlSearch({ page: 1, pageSize: 25, sort: undefined, modalityId: undefined })).toEqual({});
    expect(toUrlSearch({ page: 2, pageSize: 50, sort: "name:asc", modalityId: ID })).toEqual({
      page: 2,
      pageSize: 50,
      sort: "name:asc",
      modalityId: ID,
    });
  });
});

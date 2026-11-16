import { describe, expect, it } from "vitest";
import { academicYearsSearch, gradesSearch, modalitiesSearch, sectionsSearch } from "./organization-search.ts";

// The search parameters of the views of the academic structure (docs/ui-ux/04, section 3.2): what holds is kept, what
// does not is replaced by its default.
const ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";

describe("the search of a listing", () => {
  it("starts at the first page of 25, with no order and no filter", () => {
    expect(academicYearsSearch.parse({})).toEqual({ page: 1, pageSize: 25, sort: undefined });
    expect(gradesSearch.parse({})).toEqual({ page: 1, pageSize: 25, sort: undefined, modalityId: undefined });
  });

  it("keeps a page, a size, an order of its own fields and an id that hold", () => {
    expect(sectionsSearch.parse({ page: 3, pageSize: 50, sort: "capacity:desc", academicYearId: ID, gradeId: ID })).toEqual({
      page: 3,
      pageSize: 50,
      sort: "capacity:desc",
      academicYearId: ID,
      gradeId: ID,
    });
  });

  it.each([
    ["a page below 1", { page: 0 }, { page: 1 }],
    ["a page that is not a number", { page: "x" }, { page: 1 }],
    ["a fractional page", { page: 1.5 }, { page: 1 }],
    ["a size outside the three", { pageSize: 7 }, { pageSize: 25 }],
    ["an order on a field the view does not have", { sort: "code:asc" }, { sort: undefined }],
    ["an order with no direction", { sort: "name" }, { sort: undefined }],
    ["an order with a direction that does not exist", { sort: "name:sideways" }, { sort: undefined }],
    ["an id that is not a UUID", { modalityId: "1 OR 1=1" }, { modalityId: undefined }],
  ] as const)("drops %s and uses the default", (_name, raw, expected) => {
    expect(gradesSearch.parse(raw)).toMatchObject(expected);
  });

  it("accepts for each view only the fields the server allows", () => {
    expect(academicYearsSearch.parse({ sort: "startDate:asc" }).sort).toBe("startDate:asc");
    expect(modalitiesSearch.parse({ sort: "code:asc" }).sort).toBe("code:asc");
    expect(modalitiesSearch.parse({ sort: "startDate:asc" }).sort).toBeUndefined();
    expect(gradesSearch.parse({ sort: "ordinal:desc" }).sort).toBe("ordinal:desc");
    expect(sectionsSearch.parse({ sort: "capacity:asc" }).sort).toBe("capacity:asc");
  });
});

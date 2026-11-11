import { describe, expect, it } from "vitest";
import { MIN_QUERY_LENGTH, searchTextOf, studentsSearch } from "./students-search.ts";

// The search parameters of the students list: what holds is kept, what does not is dropped, so a bad address never
// breaks the screen and the server never receives a parameter it would refuse.
describe("studentsSearch", () => {
  it("has the defaults of every list when the address names nothing", () => {
    expect(studentsSearch.parse({})).toEqual({ page: 1, pageSize: 25, sort: undefined, q: undefined, status: undefined });
  });

  it("keeps a text of two characters or more, trimmed, and the status", () => {
    expect(studentsSearch.parse({ q: "  pé  ", status: "INACTIVE" })).toMatchObject({ q: "pé", status: "INACTIVE" });
  });

  it.each([["a"], [" a "], [""], ["   "], [123], ["x".repeat(101)]])("drops the text %j", (q) => {
    expect(studentsSearch.parse({ q }).q).toBeUndefined();
  });

  it.each([["DELETED"], ["active"], [""], [1]])("drops the status %j", (status) => {
    expect(studentsSearch.parse({ status }).status).toBeUndefined();
  });

  it.each([["studentCode:asc"], ["lastName:desc"], ["firstName:asc"], ["birthDate:desc"]])("keeps the order %s", (sort) => {
    expect(studentsSearch.parse({ sort }).sort).toBe(sort);
  });

  it.each([["nationalId:asc"], ["lastName"], ["lastName:up"], ["sex:asc"]])("drops the order %s", (sort) => {
    expect(studentsSearch.parse({ sort }).sort).toBeUndefined();
  });
});

describe("searchTextOf", () => {
  it("is the trimmed text from the minimum length, and absent below it", () => {
    expect(MIN_QUERY_LENGTH).toBe(2);
    expect(searchTextOf("ab")).toBe("ab");
    expect(searchTextOf("  ab ")).toBe("ab");
    expect(searchTextOf("a")).toBeUndefined();
    expect(searchTextOf("  a  ")).toBeUndefined();
    expect(searchTextOf("")).toBeUndefined();
  });
});

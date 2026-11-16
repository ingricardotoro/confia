import { describe, expect, it } from "vitest";
import { MIN_QUERY_LENGTH, searchTextOf } from "./search-text.ts";

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

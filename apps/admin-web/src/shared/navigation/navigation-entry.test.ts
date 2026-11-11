import { describe, expect, it } from "vitest";
import { isCurrentEntry } from "./navigation-entry.ts";

describe("isCurrentEntry", () => {
  it("matches the exact path, with or without a trailing slash", () => {
    expect(isCurrentEntry({ to: "/organization" }, "/organization")).toBe(true);
    expect(isCurrentEntry({ to: "/organization" }, "/organization/")).toBe(true);
  });

  it("matches a path below the entry but not a sibling that shares the prefix", () => {
    expect(isCurrentEntry({ to: "/organization" }, "/organization/grades")).toBe(true);
    expect(isCurrentEntry({ to: "/organization" }, "/organizations")).toBe(false);
  });

  it("matches the root entry only on the root", () => {
    expect(isCurrentEntry({ to: "/" }, "/")).toBe(true);
    expect(isCurrentEntry({ to: "/" }, "/organization")).toBe(false);
  });
});

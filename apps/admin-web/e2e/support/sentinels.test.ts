// @vitest-environment node
import { describe, expect, it } from "vitest";
import { findLeaks, makeSentinel } from "./sentinels.ts";

// admin-web-foundations-and-session, task 11.1 (CLAUDE.md rules 11 and 13). The values a test must never
// see in a log or a report are made at run time, so none is written in the repository.
describe("the sentinels", () => {
  it("makes a different value each time, carrying its label for a person to read", () => {
    const first = makeSentinel("password");

    expect(first).toContain("password");
    expect(makeSentinel("password")).not.toBe(first);
  });

  it("names the labels that leaked and never the values", () => {
    const password = makeSentinel("password");
    const token = makeSentinel("token");

    const leaks = findLeaks(`POST /x 200 ${password}`, { password, token });

    expect(leaks).toEqual(["password"]);
    expect(JSON.stringify(leaks)).not.toContain(password);
  });

  it("finds nothing in a text that holds none of them", () => {
    expect(findLeaks("POST /api/v1/auth/sessions 200", { password: makeSentinel("password") })).toEqual([]);
  });
});

// @vitest-environment node
import { describe, expect, it } from "vitest";
import { findLeaks, findTokenLeaks, makeSentinel, variantsOf } from "./sentinels.ts";

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

  // admin-web-staff-sign-in, task 9.1 (SS01): a secret is searched in the forms a browser may keep it in, not only as it was typed.
  it("lists a value as typed and in the usual encodings, each under a label that says which", () => {
    const value = "ab c/d+é";

    expect(variantsOf("secret", value)).toEqual({
      secret: value,
      "secret, URI encoded": encodeURIComponent(value),
      "secret, base64": Buffer.from(value).toString("base64"),
      "secret, base64url": Buffer.from(value).toString("base64url"),
      "secret, hex": Buffer.from(value).toString("hex"),
      "secret, JSON escaped": JSON.stringify(value).slice(1, -1),
    });
  });

  it("finds a value that was stored encoded, and names the encoding without printing the value", () => {
    const password = makeSentinel("password");
    const stored = JSON.stringify({ kept: Buffer.from(password).toString("base64") });

    const leaks = findLeaks(stored, variantsOf("password", password));

    // The base64url form is the same text when the value has no character that differs between the two.
    expect(leaks).toContain("password, base64");
    expect(leaks).not.toContain("password");
    expect(leaks).not.toContain("password, hex");
    expect(JSON.stringify(leaks)).not.toContain(password);
  });

  // admin-web-staff-sign-in, task 9 (review R3-002): a short code is searched as a whole token, so a longer run of digits that
  // happens to contain it is not a leak, and a code kept on its own (quoted, spaced, after a key) is.
  it("finds a short code only as a whole token, not inside a longer run of letters or digits", () => {
    const code = { "typed code": "123456" };

    expect(findTokenLeaks("timestamp 1761234567890 id a123456b", code)).toEqual([]);
    expect(findTokenLeaks("{\"kept\":\"123456\"}", code)).toEqual(["typed code"]);
    expect(findTokenLeaks("code=123456&x=1", code)).toEqual(["typed code"]);
    expect(findTokenLeaks("123456", code)).toEqual(["typed code"]);
    expect(findTokenLeaks("1234567 and 123456.", code)).toEqual(["typed code"]);
  });

  it("takes a value with characters that mean something in a pattern as plain text", () => {
    expect(findTokenLeaks("a.b+c", { value: "a.b+c" })).toEqual(["value"]);
    expect(findTokenLeaks("axb+c", { value: "a.b+c" })).toEqual([]);
  });
});

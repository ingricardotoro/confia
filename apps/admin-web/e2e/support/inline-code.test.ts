import { describe, expect, it } from "vitest";
import { findInlineCode } from "./inline-code.ts";

// The scanner behind AW13: it has to see inline code before its silence on the real build means anything.
describe("findInlineCode [AW13]", () => {
  it("accepts a page whose scripts all have a src", () => {
    expect(findInlineCode('<html><head><script type="module" crossorigin src="/assets/a.js"></script></head></html>')).toEqual([]);
  });

  it.each([
    ["an inline script", "<script>alert(1)</script>", "inline <script>"],
    ["an inline script with attributes", '<script type="module">x()</script>', "inline <script>"],
    ["a style element", "<style>p{color:red}</style>", "<style>"],
    ["a style attribute", '<p style="color:red">x</p>', "style attribute"],
    ["an event handler", '<button onclick="go()">x</button>', "event handler"],
  ])("reports %s", (_name, html, finding) => {
    expect(findInlineCode(html)).toEqual([finding]);
  });
});

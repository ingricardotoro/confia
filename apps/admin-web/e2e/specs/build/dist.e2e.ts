import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { expect, test } from "@playwright/test";
import { findInlineCode } from "../../support/inline-code.ts";

// What the production build must not contain (AW13 to AW15, BX95). `dist` is what `vite build` left for the
// webServer; nothing here needs a browser.
const DIST = join(import.meta.dirname, "..", "..", "..", "dist");
const files = (readdirSync(DIST, { recursive: true, encoding: "utf8" })).filter((name) => /\.\w+$/.test(name));
const text = (name: string) => readFileSync(join(DIST, name), "utf8");
const pages = files.filter((name) => name.endsWith(".html"));

test.describe("the production build", () => {
  test("has pages and code to inspect, so the checks below are not about nothing", () => {
    expect(pages).toContain("index.html");
    expect(files.some((name) => name.endsWith(".js"))).toBe(true);
  });

  test("[AW13] has no inline script, style element, style attribute or event handler in its HTML", () => {
    expect(pages.flatMap((page) => findInlineCode(text(page)).map((finding) => `${page}: ${finding}`))).toEqual([]);
  });

  test("[AW14] has no service worker of MSW and nothing that references it", () => {
    expect(files.filter((name) => /mockServiceWorker/i.test(name))).toEqual([]);
    expect(files.filter((name) => /mockServiceWorker/i.test(text(name)))).toEqual([]);
  });

  test("[AW15] has neither the deliberately inaccessible routes nor their marker", () => {
    expect(files.filter((name) => /a11y-fixtures|confia-a11y-fixture/i.test(name) || /__a11y-fixtures__|confia-a11y-fixture/.test(text(name)))).toEqual([]);
  });

  test("has neither the control route of the simulator nor its account control, which exists only in the simulator", () => {
    expect(files.filter((name) => /__control/.test(name) || /__control/.test(text(name)))).toEqual([]);
  });

  test("[BX95] has no reference to a test library", () => {
    const references = files.filter((name) => /@playwright\/test|@axe-core\/playwright|\bmsw\b/.test(text(name)));

    expect(references).toEqual([]);
  });
});

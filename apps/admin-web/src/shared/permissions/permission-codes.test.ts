import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { PERMISSION_CODES } from "./permission-codes.ts";

// The panel asks only about codes the server publishes: every code of the list is the `x-required-permission` of at least one
// operation of the admin API, so a renamed or invented code fails here instead of hiding a screen from every role.
const OPENAPI = join(import.meta.dirname, "..", "..", "..", "..", "api", "openapi", "admin.openapi.json");

function publishedCodes(): Set<string> {
  const document = JSON.parse(readFileSync(OPENAPI, "utf8")) as { paths: Record<string, Record<string, { "x-required-permission"?: unknown }>> };
  const codes = new Set<string>();
  for (const operations of Object.values(document.paths)) {
    for (const operation of Object.values(operations)) {
      const code = operation["x-required-permission"];
      if (typeof code === "string") codes.add(code);
    }
  }
  return codes;
}

describe("the permission codes of the panel", () => {
  it("are all published by the server as the permission of some operation", () => {
    const published = publishedCodes();

    expect(published.size).toBeGreaterThan(0);
    expect(PERMISSION_CODES.filter((code) => !published.has(code))).toEqual([]);
  });

  it("are not repeated", () => {
    expect(new Set(PERMISSION_CODES).size).toBe(PERMISSION_CODES.length);
  });
});

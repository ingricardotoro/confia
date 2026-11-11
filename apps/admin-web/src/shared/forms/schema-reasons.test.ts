import { CreateSectionRequest } from "@confia/contracts/admin";
import { describe, expect, it } from "vitest";
import { reasonsFromSchema, requiredReasons } from "./schema-reasons.ts";

// The reasons a generated request gives a form: reasons and not texts, one for each field that breaks the schema.
const ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";
const FIELDS = ["academicYearId", "gradeId", "name", "capacity"] as const;

describe("reasonsFromSchema", () => {
  it("finds nothing wrong with a body that meets the schema", () => {
    expect(reasonsFromSchema(CreateSectionRequest, { academicYearId: ID, gradeId: ID, name: "A" }, FIELDS)).toEqual({});
  });

  it("says `required` for a missing value, `too-long` for a text over the limit and `format` for the rest", () => {
    const reasons = reasonsFromSchema(CreateSectionRequest, { academicYearId: "no", name: "x".repeat(121) }, FIELDS);

    expect(reasons).toEqual({ academicYearId: "format", gradeId: "required", name: "too-long" });
  });

  it("gives a numeric field the reason it was asked for, whatever broke, so the person reads one message", () => {
    for (const capacity of [0, -1, "diez", 1.5]) {
      expect(reasonsFromSchema(CreateSectionRequest, { academicYearId: ID, gradeId: ID, name: "A", capacity }, FIELDS, { capacity: "positive-whole" })).toEqual({
        capacity: "positive-whole",
      });
    }
  });

  it("ignores an issue on a field the form does not have", () => {
    expect(reasonsFromSchema(CreateSectionRequest, { academicYearId: ID, gradeId: ID, name: "A" }, ["name"])).toEqual({});
    expect(reasonsFromSchema(CreateSectionRequest, {}, ["name"])).toEqual({ name: "required" });
  });
});

describe("requiredReasons", () => {
  it("marks each listed field that is empty or only blank", () => {
    expect(requiredReasons({ a: "", b: "   ", c: "x" }, ["a", "b", "c"])).toEqual({ a: "required", b: "required" });
  });

  it("leaves alone a field that is not listed", () => {
    const values: Record<"a" | "b", string> = { a: "", b: "" };

    expect(requiredReasons(values, ["a"])).toEqual({ a: "required" });
  });
});

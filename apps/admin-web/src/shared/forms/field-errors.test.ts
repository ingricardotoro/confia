import { describe, expect, it } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { fieldErrorKey, resolveFieldErrors } from "./field-errors.ts";

// admin-web-staff-sign-in, task 2.2 (AW51, SS41). The reasons of a field error become catalog keys, and
// no message can carry the value that was rejected because none of them is built from it.
describe("field error keys", () => {
  it.each([
    ["not-blank", "field.required"],
    ["required", "field.required"],
    ["size", "field.tooLong"],
    ["max", "field.tooLong"],
    ["too-long", "field.tooLong"],
    ["format", "field.invalid"],
    ["Pattern", "field.invalid"],
    ["", "field.invalid"],
  ] as const)("maps the reason %j to %s", (reason, key) => {
    expect(fieldErrorKey(reason)).toBe(key);
  });

  it("holds the texts of design section 8 for the four field keys, verbatim", () => {
    expect(admin.field).toMatchObject({
      required: "Este dato es obligatorio.",
      tooLong: "Este dato es demasiado largo.",
      invalid: "Este dato no es válido.",
      unknown: "Uno de los datos enviados no es válido.",
    });
  });

  // The academic structure cut: three reasons with a text of their own, the local ones and the server's.
  it.each([
    ["end-after-start", "field.endAfterStart"],
    ["positive-whole", "field.positiveWhole"],
    ["positive", "field.positiveWhole"],
    ["whole", "field.wholeNumber"],
    ["min", "field.wholeNumber"],
    // The students cut: the two birth date rules the server names, and the format of the national id.
    ["past-or-present", "field.birthDateFuture"],
    ["birth-date-floor", "field.birthDateFloor"],
    ["national-id-format", "field.nationalIdFormat"],
    ["enrollment-date-out-of-range", "field.dateOutOfRange"],
    ["same-section", "field.sameSection"],
    ["reason-length", "field.reasonLength"],
  ] as const)("maps the reason %j to %s", (reason, key) => {
    expect(fieldErrorKey(reason)).toBe(key);
  });
});

describe("resolving the errors of a response against the fields of a form", () => {
  const controlIds = { identifier: "access-identifier", password: "access-password" } as const;

  it("puts each error under its field and in the summary, in the order received", () => {
    const resolved = resolveFieldErrors(
      [
        { field: "password", reason: "max" },
        { field: "identifier", reason: "not-blank" },
      ],
      controlIds,
    );

    expect(resolved.byField).toEqual({ password: "field.tooLong", identifier: "field.required" });
    expect(resolved.summary).toEqual([
      { controlId: "access-password", key: "field.tooLong" },
      { controlId: "access-identifier", key: "field.required" },
    ]);
  });

  it("AW51: an unknown reason uses the generic text of the field", () => {
    const resolved = resolveFieldErrors([{ field: "password", reason: "surprise" }], controlIds);

    expect(resolved.byField).toEqual({ password: "field.invalid" });
  });

  it("AW51: a field the form does not have enters the summary as text with a unique control id that is not in the document", () => {
    const resolved = resolveFieldErrors(
      [
        { field: "nickname", reason: "size" },
        { field: "identifier", reason: "format" },
        { field: "nickname", reason: "size" },
      ],
      controlIds,
    );

    expect(resolved.byField).toEqual({ identifier: "field.invalid" });
    expect(resolved.summary).toEqual([
      { controlId: "unknown-field-1", key: "field.unknown" },
      { controlId: "access-identifier", key: "field.invalid" },
      { controlId: "unknown-field-2", key: "field.unknown" },
    ]);
    expect(document.getElementById("unknown-field-1")).toBeNull();
  });

  it("keeps the first error of a field and does not repeat it in the summary", () => {
    const resolved = resolveFieldErrors(
      [
        { field: "password", reason: "max" },
        { field: "password", reason: "format" },
      ],
      controlIds,
    );

    expect(resolved.byField).toEqual({ password: "field.tooLong" });
    expect(resolved.summary).toHaveLength(1);
  });

  it("SS41: every message is a catalog key, so the rejected value cannot appear in one", () => {
    const resolved = resolveFieldErrors([{ field: "password", reason: "hunter2-SENTINEL" }], controlIds);
    const everyKey = [...Object.values(resolved.byField), ...resolved.summary.map(({ key }) => key)];

    expect(everyKey).toEqual(["field.invalid", "field.invalid"]);
    expect(JSON.stringify(everyKey)).not.toContain("SENTINEL");
  });

  it("returns nothing for no errors", () => {
    expect(resolveFieldErrors([], controlIds)).toEqual({ byField: {}, summary: [] });
  });
});

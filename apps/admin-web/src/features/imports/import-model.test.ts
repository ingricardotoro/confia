import { describe, expect, it } from "vitest";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { IMPORT_KINDS, KIND_OF_BATCH, MAX_FILE_BYTES, TEMPLATE_FILE_NAMES, TEMPLATE_HEADERS, failureOfImport, fieldNameOf, isImportKind, problemOfFile, rejectionsCsv, templateTextOf } from "./import-model.ts";

const problemFailure = (code: string, status: number, errors: { field: string; reason: string }[] = [], traceId?: string) =>
  new ApiFailure({ kind: "problem", problem: { status, code, errors, ...(traceId === undefined ? {} : { traceId }) } as never });

describe("the check of the file in the browser", () => {
  it("accepts a .csv up to 5 MB in any case, and refuses by extension, by weight and when empty", () => {
    expect(problemOfFile({ name: "Estudiantes.CSV", size: 10 })).toBeUndefined();
    expect(problemOfFile({ name: "e.csv", size: MAX_FILE_BYTES })).toBeUndefined();
    expect(problemOfFile({ name: "e.csv", size: MAX_FILE_BYTES + 1 })).toBe("too-large");
    expect(problemOfFile({ name: "e.xlsx", size: 10 })).toBe("extension");
    expect(problemOfFile({ name: "e.csv", size: 0 })).toBe("empty");
  });
});

describe("the name of a field", () => {
  it("shows a column of the template as it is, `row` apart, and nothing else the server could send", () => {
    expect(fieldNameOf("birthDate", "students")).toEqual({ kind: "column", name: "birthDate" });
    expect(fieldNameOf("row", "students")).toEqual({ kind: "row" });
    expect(fieldNameOf("Ana Pérez", "students")).toEqual({ kind: "unknown" });
  });

  it("takes the columns from the template of the kind", () => {
    expect(fieldNameOf("rtn", "guardians")).toEqual({ kind: "column", name: "rtn" });
    expect(fieldNameOf("rtn", "students")).toEqual({ kind: "unknown" });
    expect(fieldNameOf("financialResponsibilityPercent", "guardian-links")).toEqual({ kind: "column", name: "financialResponsibilityPercent" });
    expect(fieldNameOf("birthDate", "guardian-links")).toEqual({ kind: "unknown" });
    expect(fieldNameOf("enrolledOn", "enrollments")).toEqual({ kind: "column", name: "enrolledOn" });
    expect(fieldNameOf("section", "enrollments")).toEqual({ kind: "column", name: "section" });
    expect(fieldNameOf("legacyId", "enrollments")).toEqual({ kind: "unknown" });
    expect(fieldNameOf("enrolledOn", "students")).toEqual({ kind: "unknown" });
  });
});

describe("the kinds", () => {
  it("lists them in the recommended order, with a template and a file name each, and knows only them", () => {
    expect(IMPORT_KINDS).toEqual(["students", "guardians", "guardian-links", "enrollments"]);
    expect(TEMPLATE_HEADERS.guardians).toBe("legacyId,firstName,lastName,nationalId,rtn,email,phone,address,preferredChannel");
    expect(TEMPLATE_HEADERS["guardian-links"]).toBe("studentLegacyId,guardianLegacyId,relationship,financialResponsibilityPercent,isPrimaryPayer,isBillingRecipient");
    expect(TEMPLATE_HEADERS.enrollments).toBe("studentLegacyId,academicYear,modality,grade,section,enrolledOn");
    expect(new Set(Object.values(TEMPLATE_FILE_NAMES)).size).toBe(4);
    expect(KIND_OF_BATCH).toEqual({ STUDENTS: "students", GUARDIANS: "guardians", GUARDIAN_LINKS: "guardian-links", ENROLLMENTS: "enrollments" });
    expect(isImportKind("enrollments")).toBe(true);
    expect(isImportKind("guardians")).toBe(true);
    expect(isImportKind("GUARDIANS")).toBe(false);
    expect(isImportKind(undefined)).toBe(false);
  });

  it("builds the template of the enrollments with its header and one example row, and the others with the header alone", () => {
    expect(templateTextOf("enrollments")).toBe("studentLegacyId,academicYear,modality,grade,section,enrolledOn\r\nL1001,2026,Diurna,Primero,A,2026-02-02\r\n");
    expect(templateTextOf("students")).toBe(`${TEMPLATE_HEADERS.students}\r\n`);
    expect(templateTextOf("guardian-links")).toBe(`${TEMPLATE_HEADERS["guardian-links"]}\r\n`);
  });

  it("names a missing column of the enrollments file", () => {
    expect(failureOfImport(problemFailure("validation-failed", 400, [{ field: "enrolledOn", reason: "missing-column" }]), "enrollments").texts).toEqual([
      { key: "imports.fileErrors.missingColumn", values: { column: "enrolledOn" } },
    ]);
  });

  it("names a missing column only when the template of the kind has it", () => {
    const errors = [
      { field: "guardianLegacyId", reason: "missing-column" },
      { field: "birthDate", reason: "missing-column" },
    ];

    expect(failureOfImport(problemFailure("validation-failed", 400, errors), "guardian-links").texts).toEqual([
      { key: "imports.fileErrors.missingColumn", values: { column: "guardianLegacyId" } },
      { key: "imports.fileErrors.other" },
    ]);
  });
});

describe("the failures of an import", () => {
  it("sorts the two 409 with the step that follows", () => {
    expect(failureOfImport(problemFailure("import-not-simulated", 409, [], "T1"), "students")).toEqual({
      kind: "not-simulated",
      texts: [{ key: "problem.importNotSimulated" }],
      traceId: "T1",
    });
    expect(failureOfImport(problemFailure("import-file-changed", 409), "students").kind).toBe("file-changed");
  });

  it("gives one text per problem of the file, by position for an unknown column and never a value", () => {
    const failure = failureOfImport(
      problemFailure("validation-failed", 400, [
        { field: "file", reason: "no-rows" },
        { field: "birthDate", reason: "missing-column" },
        { field: "columns[2]", reason: "unknown-column" },
        { field: "Ana Pérez", reason: "missing-column" },
      ]),
      "students",
    );

    expect(failure.texts).toEqual([
      { key: "imports.fileErrors.noRows" },
      { key: "imports.fileErrors.missingColumn", values: { column: "birthDate" } },
      { key: "imports.fileErrors.unknownColumn", values: { position: 3 } },
      { key: "imports.fileErrors.other" },
    ]);
  });

  it("tells the limit of imports per hour, the network and the rest in plain words", () => {
    expect(failureOfImport(problemFailure("too-many-requests", 429), "students").texts).toEqual([{ key: "imports.errors.rateLimited" }]);
    expect(failureOfImport(new ApiFailure({ kind: "network" }), "students").texts).toEqual([{ key: "imports.errors.network" }]);
    expect(failureOfImport(problemFailure("forbidden", 403), "students").texts).toEqual([{ key: "problem.forbidden" }]);
    expect(failureOfImport(new Error("boom"), "students").texts).toEqual([{ key: "problem.unexpected" }]);
  });
});

describe("the CSV of the rejections", () => {
  it("has a byte order mark, a header, one quoted row per rejection and only line, field and reason", () => {
    const csv = rejectionsCsv(
      [
        { line: 2, field: "birthDate", reason: "invalid-date" },
        { line: 7, field: "row", reason: "invalid-format" },
      ],
      ["Línea", "Campo", "Motivo"],
      (field) => (field === "row" ? "Fila completa" : field),
      (reason) => `motivo ${reason}`,
    );

    expect(csv).toBe('﻿"Línea","Campo","Motivo"\r\n"2","birthDate","motivo invalid-date"\r\n"7","Fila completa","motivo invalid-format"\r\n');
  });
});

import { describe, expect, it } from "vitest";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { MAX_FILE_BYTES, failureOfImport, fieldNameOf, problemOfFile, rejectionsCsv } from "./import-model.ts";

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
    expect(fieldNameOf("birthDate")).toEqual({ kind: "column", name: "birthDate" });
    expect(fieldNameOf("row")).toEqual({ kind: "row" });
    expect(fieldNameOf("Ana Pérez")).toEqual({ kind: "unknown" });
  });
});

describe("the failures of an import", () => {
  it("sorts the two 409 with the step that follows", () => {
    expect(failureOfImport(problemFailure("import-not-simulated", 409, [], "T1"))).toEqual({
      kind: "not-simulated",
      texts: [{ key: "problem.importNotSimulated" }],
      traceId: "T1",
    });
    expect(failureOfImport(problemFailure("import-file-changed", 409)).kind).toBe("file-changed");
  });

  it("gives one text per problem of the file, by position for an unknown column and never a value", () => {
    const failure = failureOfImport(
      problemFailure("validation-failed", 400, [
        { field: "file", reason: "no-rows" },
        { field: "birthDate", reason: "missing-column" },
        { field: "columns[2]", reason: "unknown-column" },
        { field: "Ana Pérez", reason: "missing-column" },
      ]),
    );

    expect(failure.texts).toEqual([
      { key: "imports.fileErrors.noRows" },
      { key: "imports.fileErrors.missingColumn", values: { column: "birthDate" } },
      { key: "imports.fileErrors.unknownColumn", values: { position: 3 } },
      { key: "imports.fileErrors.other" },
    ]);
  });

  it("tells the limit of imports per hour, the network and the rest in plain words", () => {
    expect(failureOfImport(problemFailure("too-many-requests", 429)).texts).toEqual([{ key: "imports.errors.rateLimited" }]);
    expect(failureOfImport(new ApiFailure({ kind: "network" })).texts).toEqual([{ key: "imports.errors.network" }]);
    expect(failureOfImport(problemFailure("forbidden", 403)).texts).toEqual([{ key: "problem.forbidden" }]);
    expect(failureOfImport(new Error("boom")).texts).toEqual([{ key: "problem.unexpected" }]);
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

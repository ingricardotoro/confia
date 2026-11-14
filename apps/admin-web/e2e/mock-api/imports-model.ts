import { createHash } from "node:crypto";
import { importBatch, importBatchPage, importRun, problem, type ContractResponse } from "../../src/test/contract-responses.ts";
import { acta, actaPage, jsonReply, preview } from "../../src/shared/api/reconciliation-fixtures.ts";
import type { ReconciliationResponseOutput } from "@confia/contracts/admin";
import { FIRST_YEAR, LAST_YEAR, yearId } from "./organization-seed.ts";
import type { ModelResponse } from "./session-model.ts";

/**
 * The imports of the simulated API (imports-students, imports-guardians and imports-enrollments cuts): the operations of
 * `/api/v1/imports` for the four kinds (`students`, `guardians`, `guardian-links`, `enrollments`), over batches kept in memory for one scenario. It is a thin simulation of
 * the published shapes and of the rules a journey needs: the template header of each kind, the required fields, the shape and
 * window of the birth date, a `legacyId` repeated in the file, a `legacyId` already imported (skipped, not rejected), the two 409
 * (`import-not-simulated`, `import-file-changed`, told apart by the last batch of the kind exactly as the backend does), the
 * file problems of a 400, a refused write and a read that fails first. The links are judged as the backend judges them: the rows
 * are grouped by student and each group is imported whole or not at all (a sum of exactly 100.00 in hundredths of a percent, at most
 * one primary payer and one billing recipient, no guardian twice), the references are looked up among what this scenario imported,
 * and a student that already has links is skipped (`student-already-linked`, never a rejection). The enrollments are judged row by
 * row against a small fixed academic structure (see `ENROLLMENT_STRUCTURE`): the section is found by its names, without capital
 * letters or spare spaces, and a row is rejected for the first thing wrong with it (the section, the closed year, the inactive
 * section, the date out of the year or in the future, a student not imported, a student twice in the file, another active
 * enrollment of the student that year) or skipped when the student is already in that same section; the quota counts the
 * enrollments of this scenario plus the rows of the file in order. A rejection of the enrollments carries reasons that the published
 * contract may not list yet, so the answer is put together here and not by the contract-checked builder. It keeps the SHA-256 of a
 * simulated file and the `legacyId` of the rows it applied, never a name, and it does not create anything in the registries of
 * the scenario. The documents already stored are checked only when applying. The real rules, and their limits (10 000 rows, 5 MB,
 * the inactive students and guardians), are tested in the backend.
 */
export const IMPORTS_BASE = "/api/v1/imports";
const ROUTE_PATH = /^\/api\/v1\/imports\/(students|guardians|guardian-links|enrollments)\/(simulate|apply)$/;
/** How many reads fail first in the `server-error` behavior: the first try and the two retries of the page. */
export const FAILING_IMPORT_READS = 3;
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 100;
const MAX_REJECTIONS = 200;
const MAX_FILE_BYTES = 5 * 1024 * 1024;
/** The limits of an acta, as the contract states them. */
const MAX_REFERENCE = 100;
const MAX_OFFICIAL_COUNT = 1_000_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** The account that signs every acta of the simulation; the screen never shows it. */
export const SIGNER_ID = "99999999-9999-4999-8999-999999999999";
const RECONCILIATIONS_PATH = "/api/v1/imports/reconciliations";
const PREVIEW_PATH = `${RECONCILIATIONS_PATH}/preview`;
export type ImportRoute = "students" | "guardians" | "guardian-links" | "enrollments";
interface KindSpec {
  readonly batchKind: "STUDENTS" | "GUARDIANS" | "GUARDIAN_LINKS" | "ENROLLMENTS";
  readonly required: readonly string[];
  readonly optional: readonly string[];
}
const KINDS: Readonly<Record<ImportRoute, KindSpec>> = {
  students: { batchKind: "STUDENTS", required: ["legacyId", "firstName", "lastName", "birthDate"], optional: ["nationalId", "sex"] },
  guardians: { batchKind: "GUARDIANS", required: ["legacyId", "firstName", "lastName"], optional: ["nationalId", "rtn", "email", "phone", "address", "preferredChannel"] },
  "guardian-links": {
    batchKind: "GUARDIAN_LINKS",
    required: ["studentLegacyId", "guardianLegacyId", "relationship", "financialResponsibilityPercent"],
    optional: ["isPrimaryPayer", "isBillingRecipient"],
  },
  enrollments: { batchKind: "ENROLLMENTS", required: ["studentLegacyId", "academicYear", "modality", "grade", "section", "enrolledOn"], optional: [] },
};

interface SectionSpec {
  readonly year: string;
  readonly modality: string;
  readonly grade: string;
  readonly name: string;
  readonly capacity: number;
  readonly active: boolean;
}
/** The academic structure the simulated enrollment import knows: the years (2025 is closed) and the sections, by their names. */
export const ENROLLMENT_YEARS: Readonly<Record<string, { readonly start: string; readonly end: string; readonly closed: boolean }>> = {
  "2025": { start: "2025-01-15", end: "2025-12-15", closed: true },
  "2026": { start: "2026-01-15", end: "2026-12-15", closed: false },
};
export const ENROLLMENT_STRUCTURE: readonly SectionSpec[] = [
  { year: "2026", modality: "Diurna", grade: "Primero", name: "A", capacity: 100, active: true },
  { year: "2026", modality: "Diurna", grade: "Primero", name: "B", capacity: 2, active: true },
  { year: "2026", modality: "Diurna", grade: "Primero", name: "C", capacity: 30, active: false },
  { year: "2026", modality: "Diurna", grade: "Segundo", name: "Z", capacity: 30, active: true },
  { year: "2026", modality: "Diurna", grade: "Segundo", name: "Z", capacity: 30, active: true },
  { year: "2025", modality: "Diurna", grade: "Primero", name: "A", capacity: 30, active: true },
];
export const TEMPLATE_COLUMNS = [...KINDS.students.required, ...KINDS.students.optional] as const;
const RELATIONSHIPS: ReadonlySet<string> = new Set(["FATHER", "MOTHER", "LEGAL_GUARDIAN", "SELF", "OTHER"]);
const PERCENT = /^\d{1,3}(\.\d{1,2})?$/;
const RTN = /^\d{4}-?\d{4}-?\d{6}$/;
const WHOLE_PERCENT = 10_000;
/**
 * A national id the students registry of the simulated API already holds. The simulation does not look at documents already
 * stored (the backend checks them only when applying), so a row with this document is valid in the simulation and rejected
 * as `duplicate-national-id` by the apply.
 */
export const STORED_NATIONAL_ID = "0801-2000-00001";
const FIRST_BIRTH_DATE = "1990-01-01";
/** U+FEFF at the start of a file tells a spreadsheet that it is UTF-8. */
const BYTE_ORDER_MARK = String.fromCharCode(0xfeff);
const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

type Reason =
  | "required"
  | "invalid-format"
  | "too-long"
  | "invalid-date"
  | "future-birth-date"
  | "duplicate-in-file"
  | "duplicate-national-id"
  | "already-imported"
  | "invalid-percent"
  | "unknown-student"
  | "unknown-guardian"
  | "student-not-active"
  | "guardian-not-active"
  | "student-already-linked"
  | "group-rejected"
  | "sum-not-100"
  | "multiple-primary-payer"
  | "multiple-billing-recipient"
  | "duplicate-guardian"
  | "unknown-section"
  | "ambiguous-section"
  | "section-full"
  | "duplicate-active-enrollment"
  | "academic-year-closed"
  | "inactive-reference"
  | "enrollment-date-out-of-range";
interface Rejection {
  line: number;
  field: string;
  reason: Reason;
}
type BatchRow = ReturnType<typeof importBatch>;

export interface ImportsOptions {
  readonly seed: "empty" | "seeded";
  /** `forbidden`: a read answers, every write is refused with 403 and changes nothing. */
  readonly writes: "allowed" | "forbidden";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_IMPORTS: ImportsOptions = { seed: "seeded", writes: "allowed", reads: "ok" };

export interface ImportsRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  /** The raw body and its content type: a multipart file is read here, so the simulator sees what a browser really sent. */
  readonly contentType: string | undefined;
  readonly body: Buffer | undefined;
}

export interface ImportsModel {
  /** The answer to a request of the imports, or `undefined` when the path is not one of the three operations. */
  handle(request: ImportsRequest): ModelResponse | undefined;
  /** The actas of the reconciliation the scenario holds, oldest first, for a unit test; never the page. */
  actas(): readonly ReconciliationResponseOutput[];
  /** What the scenario holds now, for a unit test; never the page. */
  batches(): readonly BatchRow[];
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });
const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse => wrap(problem(code, status, options));
const fileProblem = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

/** The content of the part `file` of a multipart body, or `undefined` when there is none. */
export function fileOfMultipart(contentType: string | undefined, body: Buffer | undefined): string | undefined {
  const boundary = /boundary=("?)([^";]+)\1/i.exec(contentType ?? "")?.[2];
  if (boundary === undefined || body === undefined) return undefined;
  for (const part of body.toString("utf8").split(`--${boundary}`)) {
    const split = part.indexOf("\r\n\r\n");
    if (split < 0) continue;
    if (/name="file"/i.test(part.slice(0, split))) return part.slice(split + 4).replace(/\r\n$/, "");
  }
  return undefined;
}

/** A small CSV reader for the simulator: `,` or `;` from the first line, double quotes, a line break inside a quoted cell. */
export function readCsv(text: string): { readonly line: number; readonly cells: string[] }[] {
  const source = text.startsWith(BYTE_ORDER_MARK) ? text.slice(1) : text;
  const first = source.split(/\r\n|\n|\r/, 1)[0] ?? "";
  const delimiter = first.split(";").length > first.split(",").length ? ";" : ",";
  const records: { line: number; cells: string[] }[] = [];
  let cells: string[] = [];
  let cell = "";
  let quoted = false;
  let line = 1;
  let startLine = 1;
  const endRecord = () => {
    cells.push(cell);
    if (cells.length > 1 || cells[0] !== "") records.push({ line: startLine, cells });
    cells = [];
    cell = "";
  };
  for (let i = 0; i < source.length; i += 1) {
    const char = source[i] as string;
    if (quoted) {
      if (char === '"' && source[i + 1] === '"') { cell += '"'; i += 1; }
      else if (char === '"') quoted = false;
      else { cell += char; if (char === "\n") line += 1; }
    } else if (char === '"' && cell === "") quoted = true;
    else if (char === delimiter) { cells.push(cell); cell = ""; }
    else if (char === "\r" || char === "\n") {
      if (char === "\r" && source[i + 1] === "\n") i += 1;
      endRecord();
      line += 1;
      startLine = line;
    } else cell += char;
  }
  if (cell !== "" || cells.length > 0) endRecord();
  return records;
}

/** The kind of a birth date, in the way of the backend: the shape, the real day, the floor of 1990 and not after today. */
function birthDateReason(value: string, today: string): Reason | undefined {
  const match = DATE.exec(value);
  if (match === null) return "invalid-format";
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const real = new Date(Date.UTC(year, month - 1, day));
  if (real.getUTCFullYear() !== year || real.getUTCMonth() !== month - 1 || real.getUTCDate() !== day) return "invalid-date";
  if (value < FIRST_BIRTH_DATE) return "invalid-date";
  return value > today ? "future-birth-date" : undefined;
}

/** A percentage as the hundredths of a percent it is, in whole numbers: nothing here is a float. `undefined` when out of 0.01 to 100.00. */
function hundredthsOf(text: string): number | undefined {
  const [whole = "", fraction = ""] = text.split(".");
  const value = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  return value >= 1 && value <= WHOLE_PERCENT ? value : undefined;
}

/** What judging a file gives: the counts, the rejections and what an apply would register once it is told to. */
interface Judgment {
  readonly sha: string;
  readonly total: number;
  readonly valid: number;
  readonly rejected: number;
  readonly skipped: number;
  readonly rejections: Rejection[];
  readonly commit: () => void;
}

interface Table {
  readonly sha: string;
  readonly names: string[];
  readonly rows: { readonly line: number; readonly cells: string[] }[];
  readonly cellOf: (cells: string[], name: string) => string;
}

export function createImportsModel(options: ImportsOptions, now: () => Date = () => new Date()): ImportsModel {
  const batches: BatchRow[] = [];
  const simulated: Record<ImportRoute, Set<string>> = { students: new Set(), guardians: new Set(), "guardian-links": new Set(), enrollments: new Set() };
  /** The active enrollment of a student in a year (`student|year`) with the index of its section, and the seats taken of each section. */
  const enrolled = new Map<string, number>();
  const seatsTaken = new Map<number, number>();
  const importedStudents = new Set<string>();
  const importedGuardians = new Set<string>();
  const linkedStudents = new Set<string>();
  const storedStudentDocuments = new Set<string>([STORED_NATIONAL_ID]);
  const storedGuardianDocuments = new Set<string>([STORED_NATIONAL_ID]);
  let reads = 0;
  let counter = 0;
  const today = (): string => new Date(now().getTime() - 6 * 3_600_000).toISOString().slice(0, 10);
  const nextId = (): string => `00000000-0000-4000-8000-${String(++counter).padStart(12, "0")}`;

  if (options.seed === "seeded") {
    batches.push(
      importBatch({ id: nextId(), status: "VALIDATED", totalRows: 120, validRows: 114, rejectedRows: 6, createdAt: "2026-11-02T14:00:00Z" }),
      importBatch({ id: nextId(), status: "APPLIED", totalRows: 120, validRows: 114, createdRows: 114, rejectedRows: 6, createdAt: "2026-11-02T14:30:00Z", appliedAt: "2026-11-02T14:30:00Z" }),
    );
  }

  const failingRead = (): ModelResponse | undefined => {
    if (options.reads !== "failing-first") return undefined;
    reads += 1;
    return reads <= FAILING_IMPORT_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  };

  function list(query: URLSearchParams): ModelResponse {
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > MAX_SIZE) return fileProblem([]);
    const kind = query.get("kind");
    if (kind !== null && !Object.hasOwn(KINDS, kind)) return fileProblem([{ field: "kind", reason: "invalid-format" }]);
    const wanted = kind === null ? undefined : KINDS[kind as ImportRoute].batchKind;
    const newestFirst = [...batches].reverse().filter((batch) => wanted === undefined || batch.kind === wanted);
    return wrap(importBatchPage(newestFirst.slice(page * size, (page + 1) * size), { page, size, total: newestFirst.length }));
  }

  /** Reads the file as the backend does: the problems of the file as a 400, otherwise its header and rows. */
  function tableOf(file: string, spec: KindSpec): Table | ModelResponse {
    const size = Buffer.byteLength(file, "utf8");
    if (size === 0) return fileProblem([{ field: "file", reason: "empty-file" }]);
    if (size > MAX_FILE_BYTES) return fileProblem([{ field: "file", reason: "too-large" }]);
    const [header, ...rows] = readCsv(file);
    if (header === undefined) return fileProblem([{ field: "file", reason: "empty-file" }]);
    const names = header.cells.map((name) => name.trim());
    const known = new Set<string>([...spec.required, ...spec.optional]);
    const columns = new Map<string, number>();
    const errors: { field: string; reason: string }[] = [];
    names.forEach((name, index) => {
      if (!known.has(name) || columns.has(name)) errors.push({ field: `columns[${String(index)}]`, reason: "unknown-column" });
      else columns.set(name, index);
    });
    for (const required of spec.required) if (!columns.has(required)) errors.push({ field: required, reason: "missing-column" });
    if (errors.length > 0) return fileProblem(errors);
    if (rows.length === 0) return fileProblem([{ field: "file", reason: "no-rows" }]);
    return { sha: createHash("sha256").update(file).digest("hex"), names, rows, cellOf: (cells, name) => cells[columns.get(name) ?? -1] ?? "" };
  }

  /** The counts of a file in which every row is judged alone (students and guardians): a defect rejects the row, an imported `legacyId` skips it. */
  function judgeRows(
    table: Table,
    spec: KindSpec,
    fieldProblems: (cellOf: (name: string) => string, line: number) => Rejection[],
    imported: Set<string>,
    storedDocuments: Set<string>,
    checkStoredDocuments: boolean,
  ): Judgment {
    const defects: Rejection[] = [];
    const omitted: Rejection[] = [];
    const seen = new Set<string>();
    const seenDocuments = new Set<string>();
    const legacyIds: string[] = [];
    const documents: string[] = [];
    let rejected = 0;
    let skipped = 0;
    for (const { line, cells } of table.rows) {
      if (cells.length !== table.names.length) {
        defects.push({ line, field: "row", reason: "invalid-format" });
        rejected += 1;
        continue;
      }
      const cell = (name: string): string => table.cellOf(cells, name);
      const found: Rejection[] = [];
      for (const field of spec.required) if (cell(field) === "") found.push({ line, field, reason: "required" });
      found.push(...fieldProblems(cell, line));
      const legacyId = cell("legacyId");
      if (legacyId !== "" && seen.has(legacyId)) found.push({ line, field: "legacyId", reason: "duplicate-in-file" });
      seen.add(legacyId);
      const document = cell("nationalId");
      if (spec.batchKind === "GUARDIANS" && document !== "") {
        if (seenDocuments.has(document)) found.push({ line, field: "nationalId", reason: "duplicate-in-file" });
        seenDocuments.add(document);
      }
      if (found.length > 0) {
        defects.push(...found);
        rejected += 1;
      } else if (imported.has(legacyId)) {
        omitted.push({ line, field: "legacyId", reason: "already-imported" });
        skipped += 1;
      } else if (checkStoredDocuments && document !== "" && storedDocuments.has(document)) {
        defects.push({ line, field: "nationalId", reason: "duplicate-national-id" });
        rejected += 1;
      } else {
        legacyIds.push(legacyId);
        documents.push(document);
      }
    }
    return {
      sha: table.sha,
      total: table.rows.length,
      valid: legacyIds.length,
      rejected,
      skipped,
      rejections: [...defects, ...omitted],
      commit: () => {
        legacyIds.forEach((legacyId) => imported.add(legacyId));
        documents.forEach((document) => {
          if (document !== "") storedDocuments.add(document);
        });
      },
    };
  }

  const studentProblems = (cell: (name: string) => string, line: number): Rejection[] => {
    const birthDate = cell("birthDate");
    if (birthDate === "") return [];
    const reason = birthDateReason(birthDate, today());
    return reason === undefined ? [] : [{ line, field: "birthDate", reason }];
  };

  const guardianProblems = (cell: (name: string) => string, line: number): Rejection[] => {
    const found: Rejection[] = [];
    if (cell("rtn") !== "" && !RTN.test(cell("rtn"))) found.push({ line, field: "rtn", reason: "invalid-format" });
    if (cell("email") !== "" && !/^[^@\s]+@[^@\s]+$/.test(cell("email"))) found.push({ line, field: "email", reason: "invalid-format" });
    return found;
  };

  /**
   * The links, judged by student as the backend does: the cells and the references of each row first (a defect there rejects the
   * rest of the group as `group-rejected`), then the rules of the set on all its lines, then the student that already has links.
   */
  function judgeLinks(table: Table): Judgment {
    const problems: Rejection[] = [];
    const defective = new Set<number>();
    const groups = new Map<string, { line: number; cells: string[] }[]>();
    for (const row of table.rows) {
      const cell = (name: string): string => table.cellOf(row.cells, name);
      if (row.cells.length !== table.names.length) {
        problems.push({ line: row.line, field: "row", reason: "invalid-format" });
        defective.add(row.line);
        continue;
      }
      const own: Rejection[] = [];
      for (const field of ["studentLegacyId", "guardianLegacyId", "relationship", "financialResponsibilityPercent"]) {
        if (cell(field).trim() === "") own.push({ line: row.line, field, reason: "required" });
      }
      if (cell("relationship").trim() !== "" && !RELATIONSHIPS.has(cell("relationship").trim())) own.push({ line: row.line, field: "relationship", reason: "invalid-format" });
      const percent = cell("financialResponsibilityPercent").trim();
      if (percent !== "" && (!PERCENT.test(percent) || hundredthsOf(percent) === undefined)) own.push({ line: row.line, field: "financialResponsibilityPercent", reason: "invalid-percent" });
      for (const flag of ["isPrimaryPayer", "isBillingRecipient"]) {
        if (!["", "true", "false"].includes(cell(flag).trim().toLowerCase())) own.push({ line: row.line, field: flag, reason: "invalid-format" });
      }
      const student = cell("studentLegacyId").trim();
      const guardian = cell("guardianLegacyId").trim();
      if (student !== "" && !importedStudents.has(student)) own.push({ line: row.line, field: "studentLegacyId", reason: "unknown-student" });
      if (guardian !== "" && !importedGuardians.has(guardian)) own.push({ line: row.line, field: "guardianLegacyId", reason: "unknown-guardian" });
      if (own.length > 0) {
        defective.add(row.line);
        problems.push(...own);
      }
      if (student !== "") groups.set(student, [...(groups.get(student) ?? []), row]);
    }
    const valid: string[] = [];
    let validRows = 0;
    let skipped = 0;
    for (const [student, rows] of groups) {
      if (rows.some((row) => defective.has(row.line))) {
        rows.filter((row) => !defective.has(row.line)).forEach((row) => problems.push({ line: row.line, field: "row", reason: "group-rejected" }));
        continue;
      }
      const cellOf = (row: { cells: string[] }, name: string): string => table.cellOf(row.cells, name).trim();
      const guardians = rows.map((row) => cellOf(row, "guardianLegacyId"));
      const flagged = (name: string): number => rows.filter((row) => cellOf(row, name).toLowerCase() === "true").length;
      // Exact decimal sum, in whole hundredths of a percent.
      const total = rows.reduce((sum, row) => sum + (hundredthsOf(cellOf(row, "financialResponsibilityPercent")) ?? 0), 0);
      const broken: { field: string; reason: Reason } | undefined =
        new Set(guardians).size !== guardians.length
          ? { field: "guardianLegacyId", reason: "duplicate-guardian" }
          : flagged("isPrimaryPayer") > 1
            ? { field: "isPrimaryPayer", reason: "multiple-primary-payer" }
            : flagged("isBillingRecipient") > 1
              ? { field: "isBillingRecipient", reason: "multiple-billing-recipient" }
              : total !== WHOLE_PERCENT
                ? { field: "financialResponsibilityPercent", reason: "sum-not-100" }
                : undefined;
      if (broken !== undefined) {
        rows.forEach((row) => problems.push({ line: row.line, field: broken.field, reason: broken.reason }));
      } else if (linkedStudents.has(student)) {
        rows.forEach((row) => problems.push({ line: row.line, field: "studentLegacyId", reason: "student-already-linked" }));
        skipped += rows.length;
      } else {
        valid.push(student);
        validRows += rows.length;
      }
    }
    problems.sort((left, right) => left.line - right.line);
    const skippedReason: Reason = "student-already-linked";
    const rejectedLines = new Set(problems.filter((problem) => problem.reason !== skippedReason).map((problem) => problem.line)).size;
    return {
      sha: table.sha,
      total: table.rows.length,
      valid: validRows,
      rejected: rejectedLines,
      skipped,
      rejections: problems,
      commit: () => {
        valid.forEach((student) => linkedStudents.add(student));
      },
    };
  }

  const normalized = (text: string): string => text.trim().toLowerCase();

  /**
   * The enrollments, row by row: each row is rejected for the first thing wrong with it, skipped when the student is already in
   * that section, and counted against the quota of its section (what the scenario holds plus the rows before it).
   */
  function judgeEnrollments(table: Table): Judgment {
    const problems: Rejection[] = [];
    const seen = new Set<string>();
    const added = new Map<number, number>();
    const created: { key: string; section: number }[] = [];
    let rejected = 0;
    let skipped = 0;
    for (const { line, cells } of table.rows) {
      const cell = (name: string): string => table.cellOf(cells, name).trim();
      const reject = (field: string, reason: Reason): void => {
        problems.push({ line, field, reason });
        rejected += 1;
      };
      if (cells.length !== table.names.length) {
        reject("row", "invalid-format");
        continue;
      }
      const empty = KINDS.enrollments.required.filter((field) => cell(field) === "");
      if (empty.length > 0) {
        empty.forEach((field) => problems.push({ line, field, reason: "required" }));
        rejected += 1;
        continue;
      }
      const date = cell("enrolledOn");
      const shape = DATE.exec(date);
      if (shape === null) {
        reject("enrolledOn", "invalid-format");
        continue;
      }
      const [year, month, day] = [Number(shape[1]), Number(shape[2]), Number(shape[3])];
      const real = new Date(Date.UTC(year, month - 1, day));
      if (real.getUTCFullYear() !== year || real.getUTCMonth() !== month - 1 || real.getUTCDate() !== day || date < FIRST_BIRTH_DATE) {
        reject("enrolledOn", "invalid-date");
        continue;
      }
      const student = cell("studentLegacyId");
      const yearName = normalized(cell("academicYear"));
      const repeated = `${student}|${yearName}`;
      if (seen.has(repeated)) {
        reject("studentLegacyId", "duplicate-in-file");
        continue;
      }
      seen.add(repeated);
      if (!importedStudents.has(student)) {
        reject("studentLegacyId", "unknown-student");
        continue;
      }
      const matches = ENROLLMENT_STRUCTURE.flatMap((section, index) =>
        normalized(section.year) === yearName &&
        normalized(section.modality) === normalized(cell("modality")) &&
        normalized(section.grade) === normalized(cell("grade")) &&
        normalized(section.name) === normalized(cell("section"))
          ? [index]
          : [],
      );
      if (matches.length === 0) {
        reject("section", "unknown-section");
        continue;
      }
      if (matches.length > 1) {
        reject("section", "ambiguous-section");
        continue;
      }
      const index = matches[0] as number;
      const section = ENROLLMENT_STRUCTURE[index] as SectionSpec;
      const academicYear = ENROLLMENT_YEARS[section.year] as { start: string; end: string; closed: boolean };
      if (academicYear.closed) {
        reject("academicYear", "academic-year-closed");
        continue;
      }
      if (!section.active) {
        reject("section", "inactive-reference");
        continue;
      }
      if (date < academicYear.start || date > academicYear.end || date > today()) {
        reject("enrolledOn", "enrollment-date-out-of-range");
        continue;
      }
      const key = `${student}|${section.year}`;
      const current = enrolled.get(key);
      if (current === index) {
        problems.push({ line, field: "studentLegacyId", reason: "already-imported" });
        skipped += 1;
        continue;
      }
      if (current !== undefined) {
        reject("studentLegacyId", "duplicate-active-enrollment");
        continue;
      }
      const taken = (seatsTaken.get(index) ?? 0) + (added.get(index) ?? 0);
      if (taken >= section.capacity) {
        reject("section", "section-full");
        continue;
      }
      added.set(index, (added.get(index) ?? 0) + 1);
      created.push({ key, section: index });
    }
    return {
      sha: table.sha,
      total: table.rows.length,
      valid: created.length,
      rejected,
      skipped,
      rejections: problems,
      commit: () => {
        for (const { key, section } of created) {
          enrolled.set(key, section);
          seatsTaken.set(section, (seatsTaken.get(section) ?? 0) + 1);
        }
      },
    };
  }

  function judge(route: ImportRoute, file: string, checkStoredDocuments: boolean): Judgment | ModelResponse {
    const spec = KINDS[route];
    const table = tableOf(file, spec);
    if ("status" in table) return table;
    if (route === "students") return judgeRows(table, spec, studentProblems, importedStudents, storedStudentDocuments, checkStoredDocuments);
    if (route === "guardians") return judgeRows(table, spec, guardianProblems, importedGuardians, storedGuardianDocuments, checkStoredDocuments);
    if (route === "guardian-links") return judgeLinks(table);
    return judgeEnrollments(table);
  }

  /** The answer of a run. The rejections go in after the contract-checked builder made the rest: their reasons may be newer than the published contract. */
  function runOf(batch: BatchRow, rejections: Rejection[], truncated: boolean): ModelResponse {
    const built = importRun({ batch, rejections: [], truncated });
    return wrap({ ...built, body: { ...(built.body as object), rejections } });
  }

  function write(route: ImportRoute, action: "simulate" | "apply", request: ImportsRequest): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    if (!/^multipart\/form-data/i.test(request.contentType ?? "")) return failure("unsupported-media-type", 415);
    const file = fileOfMultipart(request.contentType, request.body);
    if (file === undefined) return fileProblem([]);
    const lastBatch = [...batches].reverse().find((batch) => batch.kind === KINDS[route].batchKind);
    // Only the apply looks at the documents already stored, as the backend does.
    const result = judge(route, file, action === "apply");
    if ("status" in result) return result;
    const kind = KINDS[route].batchKind;
    const rejections = result.rejections.slice(0, MAX_REJECTIONS);
    const truncated = result.rejections.length > MAX_REJECTIONS;

    if (action === "simulate") {
      const batch = importBatch({ id: nextId(), kind, status: "VALIDATED", fileSha256: result.sha, totalRows: result.total, validRows: result.valid, rejectedRows: result.rejected, skippedRows: result.skipped, createdAt: now().toISOString() });
      batches.push(batch);
      simulated[route].add(result.sha);
      return runOf(batch, rejections, truncated);
    }
    // The apply: the file must have a simulation of this account and kind; otherwise the last batch of the kind tells the two cases apart.
    if (!simulated[route].has(result.sha)) return lastBatch?.status === "VALIDATED" ? failure("import-file-changed", 409) : failure("import-not-simulated", 409);
    result.commit();
    const at = now().toISOString();
    const batch = importBatch({ id: nextId(), kind, status: "APPLIED", fileSha256: result.sha, totalRows: result.total, validRows: result.valid, createdRows: result.valid, rejectedRows: result.rejected, skippedRows: result.skipped, createdAt: at, appliedAt: at });
    batches.push(batch);
    return runOf(batch, rejections, truncated);
  }

  // --- The acta of the reconciliation (imports-reconciliation cut) ---------------------------------------------------------
  const actas: ReconciliationResponseOutput[] = [];
  const yearOfId = new Map<string, number>();
  for (let calendarYear = FIRST_YEAR; calendarYear <= LAST_YEAR; calendarYear += 1) yearOfId.set(yearId(calendarYear), calendarYear);

  /** What the imports left, counted as the server counts it: created summed over every applied batch of the kind, rejected from the newest applied one. */
  function countsOf(batchKind: KindSpec["batchKind"]): { created: number; rejected: number } {
    const applied = batches.filter((batch) => batch.kind === batchKind && batch.status === "APPLIED");
    return { created: applied.reduce((sum, batch) => sum + batch.createdRows, 0), rejected: applied.at(-1)?.rejectedRows ?? 0 };
  }

  /** The active enrollments of a calendar year the scenario holds (this scenario's imports; the registries of the other models are not read). */
  const activeEnrollmentsOf = (calendarYear: number): number => [...enrolled.keys()].filter((key) => key.endsWith(`|${String(calendarYear)}`)).length;

  function snapshotOf(calendarYear: number) {
    return {
      enrollmentsActive: activeEnrollmentsOf(calendarYear),
      students: countsOf("STUDENTS"),
      guardians: countsOf("GUARDIANS"),
      guardianLinks: countsOf("GUARDIAN_LINKS"),
      enrollments: countsOf("ENROLLMENTS"),
    };
  }

  const answer = (body: object, status = 200): ModelResponse => wrap(jsonReply(body, status));

  function previewOf(query: URLSearchParams): ModelResponse {
    const academicYearId = query.get("academicYearId");
    if (academicYearId === null || !UUID.test(academicYearId)) return fileProblem([{ field: "academicYearId", reason: "invalid-format" }]);
    const calendarYear = yearOfId.get(academicYearId);
    if (calendarYear === undefined) return failure("resource-not-found", 404);
    return answer(preview({ academicYearId, ...snapshotOf(calendarYear) }));
  }

  function actasOf(query: URLSearchParams): ModelResponse {
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > MAX_SIZE) return fileProblem([]);
    const newestFirst = [...actas].reverse();
    return answer(actaPage(newestFirst.slice(page * size, (page + 1) * size), { page, size, total: newestFirst.length }));
  }

  /** The reasons a body is refused with, named as the backend names them: one error per field, in the order of the request. */
  function reconciliationErrors(body: Record<string, unknown>): { field: string; reason: string }[] {
    const errors: { field: string; reason: string }[] = [];
    const { academicYearId, officialReference, officialEnrollmentCount } = body;
    if (typeof academicYearId !== "string" || !UUID.test(academicYearId)) errors.push({ field: "academicYearId", reason: academicYearId === undefined ? "not-null" : "invalid-format" });
    if (typeof officialReference !== "string" || officialReference.trim() === "") errors.push({ field: "officialReference", reason: "not-blank" });
    else if (officialReference.length > MAX_REFERENCE) errors.push({ field: "officialReference", reason: "size" });
    else if (!/^(?=.*\d)[\p{L}\p{N} .,/#°_-]+$/u.test(officialReference)) errors.push({ field: "officialReference", reason: "pattern" });
    if (typeof officialEnrollmentCount !== "number" || !Number.isInteger(officialEnrollmentCount)) errors.push({ field: "officialEnrollmentCount", reason: "not-null" });
    else if (officialEnrollmentCount < 0) errors.push({ field: "officialEnrollmentCount", reason: "min" });
    else if (officialEnrollmentCount > MAX_OFFICIAL_COUNT) errors.push({ field: "officialEnrollmentCount", reason: "max" });
    return errors;
  }

  function sign(request: ImportsRequest): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    if (!/^application\/json/i.test(request.contentType ?? "")) return failure("unsupported-media-type", 415);
    let body: unknown;
    try {
      body = JSON.parse(request.body?.toString("utf8") ?? "");
    } catch {
      return fileProblem([]);
    }
    if (typeof body !== "object" || body === null || Array.isArray(body)) return fileProblem([]);
    const errors = reconciliationErrors(body as Record<string, unknown>);
    if (errors.length > 0) return fileProblem(errors);
    const { academicYearId, officialReference, officialEnrollmentCount } = body as { academicYearId: string; officialReference: string; officialEnrollmentCount: number };
    const calendarYear = yearOfId.get(academicYearId);
    if (calendarYear === undefined) return failure("resource-not-found", 404);
    const snapshot = snapshotOf(calendarYear);
    const record = acta({
      id: nextId(),
      academicYearId,
      officialReference: officialReference.trim(),
      officialEnrollmentCount,
      difference: snapshot.enrollmentsActive - officialEnrollmentCount,
      signedBy: SIGNER_ID,
      signedAt: now().toISOString(),
      ...snapshot,
    });
    actas.push(record);
    return answer(record, 201);
  }

  return {
    batches: () => batches,
    actas: () => actas,
    handle(request) {
      if (request.method === "GET" && request.path === IMPORTS_BASE) return failingRead() ?? list(request.query);
      if (request.method === "GET" && request.path === PREVIEW_PATH) return previewOf(request.query);
      if (request.method === "GET" && request.path === RECONCILIATIONS_PATH) return actasOf(request.query);
      if (request.method === "POST" && request.path === RECONCILIATIONS_PATH) return sign(request);
      const match = request.method === "POST" ? ROUTE_PATH.exec(request.path) : null;
      if (match !== null) return write(match[1] as ImportRoute, match[2] as "simulate" | "apply", request);
      return undefined;
    },
  };
}

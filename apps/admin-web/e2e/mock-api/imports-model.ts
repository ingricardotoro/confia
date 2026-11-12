import { createHash } from "node:crypto";
import { importBatch, importBatchPage, importRun, problem, type ContractResponse } from "../../src/test/contract-responses.ts";
import type { ModelResponse } from "./session-model.ts";

/**
 * The student import of the simulated API (imports-students cut): the three operations of `/api/v1/imports`, over batches kept in
 * memory for one scenario. It is a thin simulation of the published shapes and of the rules a journey needs: the template header,
 * the required fields, the shape and window of the birth date, a `legacyId` repeated in the file, a `legacyId` already imported
 * (skipped, not rejected), the two 409 (`import-not-simulated`, `import-file-changed`, told apart by the last batch exactly as the
 * backend does), the file problems of a 400, a refused write and a read that fails first. It keeps the SHA-256 of a simulated file
 * and the `legacyId` of the rows it applied, never a name, and it does not create students in the students registry of the
 * scenario. The real rules, and their limits (10 000 rows, 5 MB, the document checks), are tested in the backend.
 */
export const IMPORTS_BASE = "/api/v1/imports";
const SIMULATE_PATH = `${IMPORTS_BASE}/students/simulate`;
const APPLY_PATH = `${IMPORTS_BASE}/students/apply`;
/** How many reads fail first in the `server-error` behavior: the first try and the two retries of the page. */
export const FAILING_IMPORT_READS = 3;
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 100;
const MAX_REJECTIONS = 200;
const MAX_FILE_BYTES = 5 * 1024 * 1024;
export const TEMPLATE_COLUMNS = ["legacyId", "firstName", "lastName", "birthDate", "nationalId", "sex"] as const;
const REQUIRED = ["legacyId", "firstName", "lastName", "birthDate"] as const;
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

type Reason = "required" | "invalid-format" | "too-long" | "invalid-date" | "future-birth-date" | "duplicate-in-file" | "duplicate-national-id" | "already-imported";
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

export function createImportsModel(options: ImportsOptions, now: () => Date = () => new Date()): ImportsModel {
  const batches: BatchRow[] = [];
  const simulated = new Set<string>();
  const imported = new Set<string>();
  const storedDocuments = new Set<string>([STORED_NATIONAL_ID]);
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
    const newestFirst = [...batches].reverse();
    return wrap(importBatchPage(newestFirst.slice(page * size, (page + 1) * size), { page, size, total: newestFirst.length }));
  }

  /** Reads the file as the backend does: the problems of the file as a 400, otherwise the counts and the rejections. */
  function run(file: string, checkStoredDocuments: boolean): { readonly sha: string; readonly total: number; readonly valid: number; readonly rejected: number; readonly skipped: number; readonly rejections: Rejection[]; readonly legacyIds: string[]; readonly documents: string[] } | ModelResponse {
    const size = Buffer.byteLength(file, "utf8");
    if (size === 0) return fileProblem([{ field: "file", reason: "empty-file" }]);
    if (size > MAX_FILE_BYTES) return fileProblem([{ field: "file", reason: "too-large" }]);
    const records = readCsv(file);
    const [header, ...rows] = records;
    if (header === undefined) return fileProblem([{ field: "file", reason: "empty-file" }]);
    const names = header.cells.map((name) => name.trim());
    const known = new Set<string>(TEMPLATE_COLUMNS);
    const columns = new Map<string, number>();
    const errors: { field: string; reason: string }[] = [];
    names.forEach((name, index) => {
      if (!known.has(name) || columns.has(name)) errors.push({ field: `columns[${String(index)}]`, reason: "unknown-column" });
      else columns.set(name, index);
    });
    for (const required of REQUIRED) if (!columns.has(required)) errors.push({ field: required, reason: "missing-column" });
    if (errors.length > 0) return fileProblem(errors);
    if (rows.length === 0) return fileProblem([{ field: "file", reason: "no-rows" }]);

    const cellOf = (cells: string[], name: string): string => cells[columns.get(name) ?? -1] ?? "";
    const defects: Rejection[] = [];
    const omitted: Rejection[] = [];
    const seen = new Set<string>();
    const legacyIds: string[] = [];
    const documents: string[] = [];
    let rejected = 0;
    let skipped = 0;
    for (const { line, cells } of rows) {
      if (cells.length !== names.length) {
        defects.push({ line, field: "row", reason: "invalid-format" });
        rejected += 1;
        continue;
      }
      const found: Rejection[] = [];
      for (const field of ["legacyId", "firstName", "lastName", "birthDate"] as const) {
        if (cellOf(cells, field) === "") found.push({ line, field, reason: "required" });
      }
      const birthDate = cellOf(cells, "birthDate");
      if (birthDate !== "") {
        const reason = birthDateReason(birthDate, today());
        if (reason !== undefined) found.push({ line, field: "birthDate", reason });
      }
      const legacyId = cellOf(cells, "legacyId");
      if (legacyId !== "" && seen.has(legacyId)) found.push({ line, field: "legacyId", reason: "duplicate-in-file" });
      seen.add(legacyId);
      if (found.length > 0) {
        defects.push(...found);
        rejected += 1;
      } else if (imported.has(legacyId)) {
        omitted.push({ line, field: "legacyId", reason: "already-imported" });
        skipped += 1;
      } else if (checkStoredDocuments && cellOf(cells, "nationalId") !== "" && storedDocuments.has(cellOf(cells, "nationalId"))) {
        defects.push({ line, field: "nationalId", reason: "duplicate-national-id" });
        rejected += 1;
      } else {
        legacyIds.push(legacyId);
        documents.push(cellOf(cells, "nationalId"));
      }
    }
    return {
      sha: createHash("sha256").update(file).digest("hex"),
      total: rows.length,
      valid: legacyIds.length,
      rejected,
      skipped,
      rejections: [...defects, ...omitted],
      legacyIds,
      documents,
    };
  }

  function write(path: string, request: ImportsRequest): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    if (!/^multipart\/form-data/i.test(request.contentType ?? "")) return failure("unsupported-media-type", 415);
    const file = fileOfMultipart(request.contentType, request.body);
    if (file === undefined) return fileProblem([]);
    const lastBatch = batches[batches.length - 1];
    // Only the apply looks at the documents already stored, as the backend does.
    const result = run(file, path === APPLY_PATH);
    if ("status" in result) return result;
    const rejections = result.rejections.slice(0, MAX_REJECTIONS);
    const truncated = result.rejections.length > MAX_REJECTIONS;

    if (path === SIMULATE_PATH) {
      const batch = importBatch({ id: nextId(), status: "VALIDATED", fileSha256: result.sha, totalRows: result.total, validRows: result.valid, rejectedRows: result.rejected, skippedRows: result.skipped, createdAt: now().toISOString() });
      batches.push(batch);
      simulated.add(result.sha);
      return wrap(importRun({ batch, rejections, truncated }));
    }
    // The apply: the file must have a simulation of this account; otherwise the last batch tells the two cases apart.
    if (!simulated.has(result.sha)) return lastBatch?.status === "VALIDATED" ? failure("import-file-changed", 409) : failure("import-not-simulated", 409);
    result.legacyIds.forEach((legacyId) => imported.add(legacyId));
    result.documents.forEach((document) => {
      if (document !== "") storedDocuments.add(document);
    });
    const at = now().toISOString();
    const batch = importBatch({ id: nextId(), status: "APPLIED", fileSha256: result.sha, totalRows: result.total, validRows: result.valid, createdRows: result.valid, rejectedRows: result.rejected, skippedRows: result.skipped, createdAt: at, appliedAt: at });
    batches.push(batch);
    return wrap(importRun({ batch, rejections, truncated }));
  }

  return {
    batches: () => batches,
    handle(request) {
      if (request.method === "GET" && request.path === IMPORTS_BASE) return failingRead() ?? list(request.query);
      if (request.method === "POST" && (request.path === SIMULATE_PATH || request.path === APPLY_PATH)) return write(request.path, request);
      return undefined;
    },
  };
}

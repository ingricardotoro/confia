import { CreateStudentRequest, UpdateStudentRequest } from "@confia/contracts/admin";
import { problem, studentPage, studentRecord, type ContractResponse } from "../../src/test/contract-responses.ts";
import type { ModelResponse } from "./session-model.ts";
import { emptyStudents, seededStudents, type StudentRow, type StudentRows } from "./students-seed.ts";

/**
 * The students registry of the simulated API (students-registry cut): the four operations of `/api/v1/students`, over
 * rows kept in memory for one scenario. It is a thin simulation of the published shapes and of the rules a journey
 * needs (a code that is generated or repeated, a national id that is repeated even with other hyphens, the birth date
 * limits, the three ways to treat the document on an edit, and a refused write). The real rules, and their limits, are
 * tested in the backend. Paging, order and search are done here as the server does them; the page never does them. The
 * national id is kept apart from the rows and no response carries it: only `hasNationalId`.
 */
export const STUDENTS_BASE = "/api/v1/students";
/** How many reads fail first in the `server-error` behavior: the first try and the two retries of the page. */
export const FAILING_STUDENT_READS = 3;
/** A fictitious trace id, the shape the backend gives. */
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const MIN_QUERY = 2;
const MAX_QUERY = 100;
const MAX_NAME = 100;
const MAX_CODE = 30;
const BIRTH_DATE_FLOOR = "1990-01-01";
const SORT_FIELDS = ["studentCode", "lastName", "firstName", "birthDate"] as const;
const CREATE_MEMBERS = ["studentCode", "firstName", "lastName", "birthDate", "sex", "nationalId"];
const UPDATE_MEMBERS = ["firstName", "lastName", "birthDate", "sex", "status", "nationalId"];
/** Eight to twenty letters, digits or hyphens, anchored as the server anchors it. */
const NATIONAL_ID = /^[A-Za-z0-9-]{8,20}$/;
/** After the hyphens go, the document keeps at least this many characters, so the mask hides something. */
const MIN_NORMALIZED_ID = 5;
const collator = new Intl.Collator("es");

export interface StudentsOptions {
  /** The rows the scenario starts with. */
  readonly seed: "empty" | "seeded";
  /** `forbidden`: a read answers, every write is refused with 403 and changes nothing. */
  readonly writes: "allowed" | "forbidden";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_STUDENTS: StudentsOptions = { seed: "seeded", writes: "allowed", reads: "ok" };

export interface StudentsRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  /** The parsed JSON body, or `undefined` when there was none or it was not JSON. */
  readonly body: unknown;
}

export interface StudentsModel {
  /** The answer to a request below `/api/v1/students`, or `undefined` when the path is not one of the four operations. */
  handle(request: StudentsRequest): ModelResponse | undefined;
  /** What the scenario holds now, for a unit test; never the page. */
  rows(): Readonly<StudentRows>;
}

interface Schema {
  safeParse(input: unknown):
    | { readonly success: true; readonly data: unknown }
    | { readonly success: false; readonly error: { readonly issues: readonly { readonly path: readonly PropertyKey[] }[] } };
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });

const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse =>
  wrap(problem(code, status, options));

const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

/** The document as the registry compares it: no hyphens, capitals. The same document with other hyphens is the same document. */
const normalizedId = (document: string): string => document.replaceAll("-", "").toUpperCase();

/** What a response shows of a row: the stored fields, and whether there is a document, never a digit of it. */
function present(row: StudentRow, document: string | undefined): StudentRow & { hasNationalId: boolean } {
  return { ...row, hasNationalId: document !== undefined };
}

export function createStudentsModel(options: StudentsOptions = DEFAULT_STUDENTS, now: () => Date = () => new Date()): StudentsModel {
  const data = options.seed === "seeded" ? seededStudents() : emptyStudents();
  let reads = 0;
  let counter = data.students.length;

  /** Today in Honduras (UTC-6, no daylight saving), as `YYYY-MM-DD`. */
  const today = (): string => new Date(now().getTime() - 6 * 3_600_000).toISOString().slice(0, 10);

  function list(query: URLSearchParams): ModelResponse {
    if (options.reads === "failing-first") {
      reads += 1;
      if (reads <= FAILING_STUDENT_READS) return failure("internal-error", 500, { traceId: TRACE_ID });
    }
    const errors: { field: string; reason: string }[] = [];
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) errors.push({ field: "page", reason: "invalid" });
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) errors.push({ field: "size", reason: "invalid" });
    const [field = "", direction = "asc", ...rest] = (query.get("sort") ?? "lastName,asc").split(",");
    if (!(SORT_FIELDS as readonly string[]).includes(field) || (direction !== "asc" && direction !== "desc") || rest.length > 0) {
      errors.push({ field: "sort", reason: "invalid" });
    }
    const text = query.get("q");
    if (text !== null && (text.trim().length < MIN_QUERY || text.length > MAX_QUERY)) errors.push({ field: "q", reason: "size" });
    const status = query.get("status");
    if (status !== null && status !== "ACTIVE" && status !== "INACTIVE") errors.push({ field: "status", reason: "pattern" });
    if (errors.length > 0) return invalid(errors);

    const wanted = text?.trim().toLowerCase();
    const matching = data.students.filter(
      (row) =>
        (status === null || row.status === status) &&
        (wanted === undefined || [row.firstName, row.lastName, row.studentCode].some((value) => value.toLowerCase().includes(wanted))),
    );
    const sign = direction === "asc" ? 1 : -1;
    const key = (row: StudentRow): string => row[field as (typeof SORT_FIELDS)[number]];
    // The tie breaks on the id, so two requests for the same page see the same rows.
    const ordered = [...matching].sort((a, b) => sign * collator.compare(key(a), key(b)) || collator.compare(a.id, b.id));
    const rows = ordered.slice(page * size, (page + 1) * size).map((row) => present(row, data.documents.get(row.id)));
    return wrap(studentPage(rows, { page, size, total: ordered.length }));
  }

  /** The 400 of a body that does not meet the request contract or a rule the form cannot know. */
  function badBody(schema: Schema, members: readonly string[], body: unknown): ModelResponse | undefined {
    if (typeof body !== "object" || body === null || Array.isArray(body)) return invalid([{ field: "body", reason: "invalid" }]);
    const unknown = Object.keys(body).filter((name) => !members.includes(name));
    if (unknown.length > 0) return invalid(unknown.map((field) => ({ field, reason: "unknown" })));
    const parsed = schema.safeParse(body);
    if (!parsed.success) {
      const errors = parsed.error.issues.map((issue) => ({ field: issue.path.map(String).join(".") || "body", reason: "invalid" }));
      return invalid(errors.length === 0 ? [{ field: "body", reason: "invalid" }] : errors);
    }
    const values = parsed.data as Record<string, string | undefined>;
    const errors: { field: string; reason: string }[] = [];
    for (const name of ["firstName", "lastName"] as const) {
      const text = (values[name] ?? "").trim();
      if (text === "") errors.push({ field: name, reason: "not-blank" });
      else if (text.length > MAX_NAME) errors.push({ field: name, reason: "size" });
    }
    const code = values["studentCode"];
    if (code !== undefined && code.length > MAX_CODE) errors.push({ field: "studentCode", reason: "size" });
    const birthDate = values["birthDate"] ?? "";
    if (birthDate > today()) errors.push({ field: "birthDate", reason: "past-or-present" });
    else if (birthDate < BIRTH_DATE_FLOOR) errors.push({ field: "birthDate", reason: "birth-date-floor" });
    const document = values["nationalId"]?.trim() ?? "";
    if (document !== "" && (!NATIONAL_ID.test(document) || normalizedId(document).length < MIN_NORMALIZED_ID)) {
      errors.push({ field: "nationalId", reason: "pattern" });
    }
    return errors.length === 0 ? undefined : invalid(errors);
  }

  /** The student that already has this document, other than `self`. */
  const ownerOf = (document: string, self: string | undefined): string | undefined =>
    [...data.documents.entries()].find(([id, stored]) => id !== self && normalizedId(stored) === normalizedId(document))?.[0];

  function nextCode(): string {
    const taken = new Set(data.students.map((row) => row.studentCode));
    let code: string;
    do {
      counter += 1;
      code = `E-${String(counter).padStart(6, "0")}`;
    } while (taken.has(code));
    return code;
  }

  function create(body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const bad = badBody(CreateStudentRequest, CREATE_MEMBERS, body);
    if (bad !== undefined) return bad;
    const values = body as Record<string, string | undefined>;
    const typedCode = values["studentCode"]?.trim() ?? "";
    if (typedCode !== "" && data.students.some((row) => row.studentCode === typedCode)) return failure("duplicate-student-code", 409);
    const document = values["nationalId"]?.trim() ?? "";
    if (document !== "" && ownerOf(document, undefined) !== undefined) return failure("duplicate-national-id", 409);

    const stamp = now().toISOString();
    const row: StudentRow = {
      id: crypto.randomUUID(),
      studentCode: typedCode === "" ? nextCode() : typedCode,
      firstName: (values["firstName"] ?? "").trim(),
      lastName: (values["lastName"] ?? "").trim(),
      birthDate: values["birthDate"] ?? "",
      sex: values["sex"] as StudentRow["sex"],
      status: "ACTIVE",
      createdAt: stamp,
      updatedAt: stamp,
    };
    data.students.push(row);
    if (document !== "") data.documents.set(row.id, document);
    return wrap(studentRecord(present(row, data.documents.get(row.id)), 201));
  }

  function update(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const self = data.students.find((row) => row.id === id);
    if (self === undefined) return failure("resource-not-found", 404);
    const bad = badBody(UpdateStudentRequest, UPDATE_MEMBERS, body);
    if (bad !== undefined) return bad;
    const values = body as Record<string, string | undefined>;
    // Omitted keeps the document, blank removes it, any other value replaces it.
    const sent = values["nationalId"];
    const document = sent?.trim();
    if (document !== undefined && document !== "" && ownerOf(document, id) !== undefined) return failure("duplicate-national-id", 409);

    Object.assign(self, {
      firstName: (values["firstName"] ?? "").trim(),
      lastName: (values["lastName"] ?? "").trim(),
      birthDate: values["birthDate"] ?? "",
      sex: values["sex"],
      status: values["status"],
      updatedAt: now().toISOString(),
    });
    if (document === "") data.documents.delete(id);
    else if (document !== undefined) data.documents.set(id, document);
    return wrap(studentRecord(present(self, data.documents.get(id)), 200));
  }

  return {
    rows: () => data,
    handle({ method, path, query, body }) {
      const match = /^\/api\/v1\/students(?:\/([^/]+))?$/.exec(path);
      if (match === null) return undefined;
      const id = match[1];
      if (id === undefined && method === "GET") return list(query);
      if (id === undefined && method === "POST") return create(body);
      if (id !== undefined && method === "PUT") return update(id, body);
      return undefined;
    },
  };
}

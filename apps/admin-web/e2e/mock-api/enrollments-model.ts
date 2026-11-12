import { EnrollStudentRequest, ExitEnrollmentRequest, TransferEnrollmentRequest } from "@confia/contracts/admin";
import {
  enrollmentPage,
  enrollmentRecord,
  problem,
  type ContractResponse,
} from "../../src/test/contract-responses.ts";
import { emptyEnrollments, seededEnrollments, type EnrollmentRow, type EnrollmentStatus } from "./enrollments-seed.ts";
import type { OrganizationModel } from "./organization-model.ts";
import type { ModelResponse } from "./session-model.ts";
import type { StudentsModel } from "./students-model.ts";

/**
 * The enrollments of the simulated API (enrollments cut): the five operations of `/api/v1/enrollments`, over rows kept in
 * memory for one scenario and over the students and the academic structure of the same scenario. It is a thin simulation of the
 * published shapes and of only the rules a journey needs: one active enrollment for a student in a year (INV-31), the
 * capacity of a section, a closed year, an inactive section or student, the dates inside the year, the transitions out of
 * ACTIVE, the reasons, and a refused write. The real rules, and their limits, are tested in the backend. Paging, order and
 * filters are done here as the server does them, and the year defaults to the one in progress.
 */
export const ENROLLMENTS_BASE = "/api/v1/enrollments";
/** How many reads fail first in the `server-error` behavior: the first try and the two retries of the page. */
export const FAILING_ENROLLMENT_READS = 3;
/** A fictitious trace id, the shape the backend gives. */
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const MIN_REASON = 3;
const MAX_REASON = 500;
const SORT_FIELDS = ["enrolledOn", "studentLastName", "status"] as const;
const STATUSES = ["ACTIVE", "WITHDRAWN", "TRANSFERRED", "GRADUATED"] as const;
const ENROLL_MEMBERS = ["studentId", "sectionId", "enrolledOn"];
const TRANSFER_MEMBERS = ["toSectionId", "transferredOn", "reason"];
const EXIT_MEMBERS = ["status", "withdrawnOn", "withdrawalReason"];
const collator = new Intl.Collator("es");

export interface EnrollmentsOptions {
  /** The rows the scenario starts with. */
  readonly seed: "empty" | "seeded";
  /** `forbidden`: a read answers, every write is refused with 403 and changes nothing. */
  readonly writes: "allowed" | "forbidden";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_ENROLLMENTS: EnrollmentsOptions = { seed: "seeded", writes: "allowed", reads: "ok" };

export interface EnrollmentsRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  /** The parsed JSON body, or `undefined` when there was none or it was not JSON. */
  readonly body: unknown;
}

export interface EnrollmentsModel {
  /** The answer to a request below `/api/v1/enrollments`, or `undefined` when the path is not one of the five operations. */
  handle(request: EnrollmentsRequest): ModelResponse | undefined;
  /** What the scenario holds now, for a unit test; never the page. */
  rows(): readonly EnrollmentRow[];
}

interface Schema {
  safeParse(input: unknown): { readonly success: true } | { readonly success: false; readonly error: { readonly issues: readonly { readonly path: readonly PropertyKey[] }[] } };
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });
const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse =>
  wrap(problem(code, status, options));
const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

export function createEnrollmentsModel(
  options: EnrollmentsOptions,
  context: { readonly organization: OrganizationModel; readonly students: StudentsModel },
  now: () => Date = () => new Date(),
): EnrollmentsModel {
  const data = options.seed === "seeded" ? seededEnrollments() : emptyEnrollments();
  let reads = 0;
  let counter = data.length;

  /** Today in Honduras (UTC-6, no daylight saving), as `YYYY-MM-DD`. */
  const today = (): string => new Date(now().getTime() - 6 * 3_600_000).toISOString().slice(0, 10);

  const org = () => context.organization.rows();
  const sectionOf = (id: string) => org().sections.find((row) => row.id === id);
  const yearOf = (id: string) => org().years.find((row) => row.id === id);
  const studentOf = (id: string) => context.students.rows().students.find((row) => row.id === id);

  /** The full response of a row: names from the student, year, section, grade and modality it points to. */
  function present(row: EnrollmentRow, withTransfers: boolean) {
    const section = sectionOf(row.sectionId);
    const student = studentOf(row.studentId);
    const year = section === undefined ? undefined : yearOf(section.academicYearId);
    const grade = section === undefined ? undefined : org().grades.find((item) => item.id === section.gradeId);
    const modality = grade === undefined ? undefined : org().modalities.find((item) => item.id === grade.modalityId);
    const nameOfSection = (id: string): string => sectionOf(id)?.name ?? "";
    return {
      id: row.id,
      studentId: row.studentId,
      studentCode: student?.studentCode ?? "",
      studentFirstName: student?.firstName ?? "",
      studentLastName: student?.lastName ?? "",
      academicYearId: section?.academicYearId ?? "",
      academicYearName: year?.name ?? "",
      sectionId: row.sectionId,
      sectionName: section?.name ?? "",
      gradeId: section?.gradeId ?? "",
      gradeName: grade?.name ?? "",
      modalityName: modality?.name ?? "",
      enrolledOn: row.enrolledOn,
      status: row.status,
      withdrawnOn: row.withdrawnOn,
      withdrawalReason: row.withdrawalReason,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
      ...(withTransfers
        ? {
            transfers: row.transfers.map((transfer) => ({
              ...transfer,
              fromSectionName: nameOfSection(transfer.fromSectionId),
              toSectionName: nameOfSection(transfer.toSectionId),
            })),
          }
        : {}),
    };
  }

  function list(query: URLSearchParams): ModelResponse {
    if (options.reads === "failing-first") {
      reads += 1;
      if (reads <= FAILING_ENROLLMENT_READS) return failure("internal-error", 500, { traceId: TRACE_ID });
    }
    const errors: { field: string; reason: string }[] = [];
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) errors.push({ field: "page", reason: "invalid" });
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) errors.push({ field: "size", reason: "invalid" });
    const [field = "", direction = "asc", ...rest] = (query.get("sort") ?? "studentLastName,asc").split(",");
    if (!(SORT_FIELDS as readonly string[]).includes(field) || (direction !== "asc" && direction !== "desc") || rest.length > 0) {
      errors.push({ field: "sort", reason: "invalid" });
    }
    const status = query.get("status");
    if (status !== null && !(STATUSES as readonly string[]).includes(status)) errors.push({ field: "status", reason: "pattern" });
    if (errors.length > 0) return invalid(errors);

    // No year in the address: the one in progress, or every year when there is none.
    const yearId = query.get("academicYearId") ?? org().years.find((year) => year.status === "ACTIVE")?.id;
    const sectionId = query.get("sectionId");
    const gradeId = query.get("gradeId");
    const matching = data.filter((row) => {
      const section = sectionOf(row.sectionId);
      return (
        (yearId === undefined || section?.academicYearId === yearId) &&
        (sectionId === null || row.sectionId === sectionId) &&
        (gradeId === null || section?.gradeId === gradeId) &&
        (status === null || row.status === status)
      );
    });
    const sign = direction === "asc" ? 1 : -1;
    const key = (row: EnrollmentRow): string =>
      field === "studentLastName" ? `${studentOf(row.studentId)?.lastName ?? ""} ${studentOf(row.studentId)?.firstName ?? ""}` : row[field as "enrolledOn" | "status"];
    // The tie breaks on the id, so two requests for the same page see the same rows.
    const ordered = [...matching].sort((a, b) => sign * collator.compare(key(a), key(b)) || collator.compare(a.id, b.id));
    const rows = ordered.slice(page * size, (page + 1) * size).map((row) => present(row, false));
    return wrap(enrollmentPage(rows, { page, size, total: ordered.length }));
  }

  /** The 400 of a body that does not meet the request contract. */
  function badBody(schema: Schema, members: readonly string[], body: unknown): ModelResponse | undefined {
    if (typeof body !== "object" || body === null || Array.isArray(body)) return invalid([{ field: "body", reason: "invalid" }]);
    const unknown = Object.keys(body).filter((name) => !members.includes(name));
    if (unknown.length > 0) return invalid(unknown.map((field) => ({ field, reason: "unknown" })));
    const parsed = schema.safeParse(body);
    if (parsed.success) return undefined;
    const errors = parsed.error.issues.map((issue) => ({ field: issue.path.map(String).join(".") || "body", reason: "not-null" }));
    return invalid(errors.length === 0 ? [{ field: "body", reason: "invalid" }] : errors);
  }

  const activeIn = (sectionId: string): number => data.filter((row) => row.sectionId === sectionId && row.status === "ACTIVE").length;
  const reasonProblem = (field: string, value: string | undefined, required: boolean): ModelResponse | undefined => {
    const text = value?.trim() ?? "";
    if (text === "") return required ? invalid([{ field, reason: "not-blank" }]) : undefined;
    return text.length < MIN_REASON || text.length > MAX_REASON ? invalid([{ field, reason: "size" }]) : undefined;
  };
  const outOfRange = (field: string): ModelResponse => invalid([{ field, reason: "enrollment-date-out-of-range" }]);

  function enroll(body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const bad = badBody(EnrollStudentRequest, ENROLL_MEMBERS, body);
    if (bad !== undefined) return bad;
    const values = body as { studentId: string; sectionId: string; enrolledOn: string };
    const student = studentOf(values.studentId);
    const section = sectionOf(values.sectionId);
    if (student === undefined || section === undefined) return failure("resource-not-found", 404);
    const year = yearOf(section.academicYearId);
    if (student.status !== "ACTIVE") return failure("student-not-active", 409);
    if (year?.status === "CLOSED") return failure("academic-year-closed", 409);
    if (!section.isActive) return failure("inactive-reference", 409);
    if (year === undefined || values.enrolledOn < year.startDate || values.enrolledOn > year.endDate || values.enrolledOn > today()) {
      return outOfRange("enrolledOn");
    }
    const sameYear = (row: EnrollmentRow): boolean => sectionOf(row.sectionId)?.academicYearId === year.id;
    if (data.some((row) => row.studentId === student.id && row.status === "ACTIVE" && sameYear(row))) return failure("duplicate-active-enrollment", 409);
    if (section.capacity !== null && activeIn(section.id) >= section.capacity) return failure("section-full", 409);

    counter += 1;
    const stamp = now().toISOString();
    const row: EnrollmentRow = {
      id: `00000000-0000-4000-8000-${String(7000 + 500 + counter).padStart(12, "0")}`,
      studentId: student.id,
      sectionId: section.id,
      enrolledOn: values.enrolledOn,
      status: "ACTIVE",
      withdrawnOn: null,
      withdrawalReason: null,
      createdAt: stamp,
      updatedAt: stamp,
      transfers: [],
    };
    data.push(row);
    return wrap(enrollmentRecord(present(row, true), 201));
  }

  function transfer(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const row = data.find((item) => item.id === id);
    if (row === undefined) return failure("resource-not-found", 404);
    const bad = badBody(TransferEnrollmentRequest, TRANSFER_MEMBERS, body);
    if (bad !== undefined) return bad;
    const values = body as { toSectionId: string; transferredOn: string; reason?: string };
    const target = sectionOf(values.toSectionId);
    if (target === undefined) return failure("resource-not-found", 404);
    const current = sectionOf(row.sectionId);
    const year = current === undefined ? undefined : yearOf(current.academicYearId);
    if (row.status !== "ACTIVE") return failure("invalid-enrollment-state", 409);
    if (year?.status === "CLOSED") return failure("academic-year-closed", 409);
    if (target.id === row.sectionId) return invalid([{ field: "toSectionId", reason: "same-section" }]);
    if (target.academicYearId !== current?.academicYearId) return failure("transfer-year-mismatch", 409);
    if (!target.isActive) return failure("inactive-reference", 409);
    if (values.transferredOn < row.enrolledOn || values.transferredOn > today() || (year !== undefined && values.transferredOn > year.endDate)) {
      return outOfRange("transferredOn");
    }
    const reason = reasonProblem("reason", values.reason, false);
    if (reason !== undefined) return reason;
    if (target.capacity !== null && activeIn(target.id) >= target.capacity) return failure("section-full", 409);

    const stamp = now().toISOString();
    row.transfers.push({
      id: crypto.randomUUID(),
      fromSectionId: row.sectionId,
      toSectionId: target.id,
      transferredOn: values.transferredOn,
      reason: values.reason?.trim() === undefined || values.reason.trim() === "" ? null : values.reason.trim(),
      transferredBy: "00000000-0000-4000-8000-000000009001",
      createdAt: stamp,
    });
    row.sectionId = target.id;
    row.updatedAt = stamp;
    return wrap(enrollmentRecord(present(row, true)));
  }

  function exit(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const row = data.find((item) => item.id === id);
    if (row === undefined) return failure("resource-not-found", 404);
    const bad = badBody(ExitEnrollmentRequest, EXIT_MEMBERS, body);
    if (bad !== undefined) return bad;
    const values = body as { status: Exclude<EnrollmentStatus, "ACTIVE">; withdrawnOn: string; withdrawalReason?: string };
    const year = yearOf(sectionOf(row.sectionId)?.academicYearId ?? "");
    if (row.status !== "ACTIVE") return failure("invalid-enrollment-state", 409);
    if (year?.status === "CLOSED") return failure("academic-year-closed", 409);
    if (values.withdrawnOn < row.enrolledOn || values.withdrawnOn > today()) return outOfRange("withdrawnOn");
    const reason = reasonProblem("withdrawalReason", values.withdrawalReason, values.status !== "GRADUATED");
    if (reason !== undefined) return reason;

    row.status = values.status;
    row.withdrawnOn = values.withdrawnOn;
    const text = values.withdrawalReason?.trim() ?? "";
    row.withdrawalReason = text === "" ? null : text;
    row.updatedAt = now().toISOString();
    return wrap(enrollmentRecord(present(row, true)));
  }

  return {
    rows: () => data,
    handle({ method, path, query, body }) {
      const match = /^\/api\/v1\/enrollments(?:\/([^/]+)(?:\/(transfer|exit))?)?$/.exec(path);
      if (match === null) return undefined;
      const [, id, action] = match;
      if (id === undefined && method === "GET") return list(query);
      if (id === undefined && method === "POST") return enroll(body);
      if (id !== undefined && action === undefined && method === "GET") {
        const row = data.find((item) => item.id === id);
        return row === undefined ? failure("resource-not-found", 404) : wrap(enrollmentRecord(present(row, true)));
      }
      if (id !== undefined && action === "transfer" && method === "POST") return transfer(id, body);
      if (id !== undefined && action === "exit" && method === "POST") return exit(id, body);
      return undefined;
    },
  };
}

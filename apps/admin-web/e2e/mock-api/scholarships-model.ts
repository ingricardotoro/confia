import {
  CreateScholarshipRequest,
  RejectScholarshipAwardBody,
  RequestAwardRequest,
  RevokeScholarshipAwardBody,
  UpdateScholarshipRequest,
} from "@confia/contracts/admin";
import { awardPage, awardRecord, problem, scholarshipPage, scholarshipRecord, type ContractResponse } from "../../src/test/contract-responses.ts";
import type { PaymentConceptRow } from "./catalog-seed.ts";
import { yearId, LAST_YEAR } from "./organization-seed.ts";
import { ME } from "./payments-model.ts";
import type { ModelResponse } from "./session-model.ts";
import { studentId, type StudentRow } from "./students-seed.ts";

/**
 * The scholarships and their awards of the simulated API (scholarships-awards cut): the operations below `/api/v1/scholarships` and
 * `/api/v1/scholarship-awards`, over rows kept in memory for one scenario. A thin simulation of the published shapes and of the rules a journey needs:
 * a code is never repeated (409 `duplicate-scholarship-code`), an award is born pending and only another person decides it (403
 * `self-approval-forbidden` for the one who requested it), a decision needs an `Idempotency-Key` (the same key and body answer the same award), the
 * reason of a rejection or a revocation is 20 to 500 characters, a pending award is approved or rejected and an approved one revoked (409
 * `invalid-award-state` otherwise), and an approved award locks the benefit of its scholarship. The list pages, filters and orders as the server
 * does. `benefitFor` is what the simulator of charges reads: the percentage an approved award in force gives, which the SIMULATED SERVER applies in
 * cents. The preview and the run of the accrual in this simulator do not apply scholarships; only the simulator does. The real rules, and their
 * limits, are tested in the backend.
 */
export const SCHOLARSHIPS_BASE = "/api/v1/scholarships";
export const AWARDS_BASE = "/api/v1/scholarship-awards";

/** Another person, who requested the pending award the seed holds and who may be asked to approve the ones the person requested. */
const OTHER = "00000000-0000-4000-8000-0000000000c9";
const STAMP = "2026-11-01T12:00:00Z";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const REASON_MIN = 20;
const REASON_MAX = 500;
const TRACE_ID = "01J8Z4K2QF9P0X";

export const scholarshipRowId = (n: number): string => `00000000-0000-4000-8000-0000000e${String(n).padStart(4, "0")}`;
export const awardRowId = (n: number): string => `00000000-0000-4000-8000-0000000f${String(n).padStart(4, "0")}`;
const decisionId = (n: number): string => `00000000-0000-4000-8000-0000000d${String(n).padStart(4, "0")}`;

interface Money {
  amount: string;
  currency: string;
}

interface ScholarshipRow {
  id: string;
  code: string;
  name: string;
  benefitType: "PERCENTAGE" | "FIXED_AMOUNT";
  percent: string | null;
  fixedAmount: Money | null;
  periodCap: Money | null;
  conceptIds: string[];
  active: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

interface DecisionRow {
  id: string;
  kind: "approved" | "rejected" | "revoked";
  decidedAt: string;
  decidedBy: string;
  reason: string | null;
}

interface AwardRow {
  id: string;
  studentId: string;
  scholarshipId: string;
  academicYearId: string;
  validFrom: string;
  validTo: string;
  status: "pending" | "approved" | "rejected" | "revoked";
  requestedAt: string;
  requestedBy: string;
  decision: DecisionRow | null;
  revocation: DecisionRow | null;
  /** The key of the last decision and what it sent, so the same request is answered the same. */
  keys: Map<string, string>;
}

export interface ScholarshipsOptions {
  /** `seeded`: two scholarships and four awards (a pending one another person requested, one the person requested, an approved and a rejected one). `none`: nothing. */
  readonly seed: "none" | "seeded";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_SCHOLARSHIPS: ScholarshipsOptions = { seed: "seeded", reads: "ok" };
const FAILING_READS = 3;

export interface ScholarshipsRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly body: unknown;
  readonly headers?: Readonly<Record<string, string | undefined>>;
}

export interface ScholarshipsContext {
  readonly students: () => readonly StudentRow[];
  readonly concepts: () => readonly PaymentConceptRow[];
  readonly now: () => Date;
}

export interface ScholarshipsModel {
  /** The answer to a request below the two bases, or `undefined` when the path is not one of the operations. */
  handle(request: ScholarshipsRequest): ModelResponse | undefined;
  /** The approved award in force that gives a student a percentage on a concept at the first day of a period, for the simulator of charges. */
  readonly benefitFor: (studentId: string, conceptId: string, periodStart: string) => { readonly awardId: string; readonly code: string; readonly hundredths: number } | undefined;
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });
const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse => wrap(problem(code, status, options));
const invalid = (errors: { field: string; reason: string }[] = []): ModelResponse => failure("validation-failed", 400, { errors });
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const schemaErrors = (issues: readonly { path: PropertyKey[]; code: string }[]) =>
  invalid(issues.map((issue) => ({ field: issue.path.map(String).join(".") || "body", reason: issue.code === "invalid_format" ? "pattern" : "invalid" })));

/** A percentage as the hundredths of a point: `25` and `25.00` are 2500. The text is read by its characters, never as a number. */
function hundredthsOf(percent: string): number {
  const [whole = "0", fraction = ""] = percent.split(".");
  return Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
}

const pageOf = <Row,>(rows: readonly Row[], query: URLSearchParams): { slice: Row[]; page: number; size: number } | ModelResponse => {
  const page = Number(query.get("page") ?? "0");
  const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
  if (!Number.isInteger(page) || page < 0) return invalid([{ field: "page", reason: "invalid" }]);
  if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) return invalid([{ field: "size", reason: "invalid" }]);
  return { slice: rows.slice(page * size, (page + 1) * size), page, size };
};

export function createScholarshipsModel(options: ScholarshipsOptions, context: ScholarshipsContext): ScholarshipsModel {
  const scholarships: ScholarshipRow[] = [];
  const awards: AwardRow[] = [];
  let reads = 0;
  let sequence = 100;

  const concepts = () => context.concepts();
  const row = (n: number, code: string, name: string, percent: string, conceptIds: string[]): ScholarshipRow => ({
    id: scholarshipRowId(n),
    code,
    name,
    benefitType: "PERCENTAGE",
    percent,
    fixedAmount: null,
    periodCap: null,
    conceptIds,
    active: true,
    version: 0,
    createdAt: STAMP,
    updatedAt: STAMP,
  });
  const award = (n: number, student: number, scholarship: number, status: AwardRow["status"], requestedBy: string, decision: DecisionRow | null = null): AwardRow => ({
    id: awardRowId(n),
    studentId: studentId(student),
    scholarshipId: scholarshipRowId(scholarship),
    academicYearId: yearId(LAST_YEAR),
    validFrom: `${String(LAST_YEAR)}-01-01`,
    validTo: `${String(LAST_YEAR)}-12-31`,
    status,
    requestedAt: `2026-11-0${String(n)}T15:00:00Z`,
    requestedBy,
    decision,
    revocation: null,
    keys: new Map(),
  });

  if (options.seed === "seeded") {
    // The tuition (the first concept of the catalog) is the one the seeded active plan accrues each month, and the first student carries no error on it in
    // the simulator of the accrual, so the figures of a journey are whole.
    const tuition = concepts()[0]?.id ?? "";
    scholarships.push(row(1, "BECA25", "Beca de excelencia", "25.00", [tuition]), row(2, "BECA50", "Beca de hermanos", "50.00", [tuition]));
    awards.push(
      award(1, 1, 1, "pending", OTHER),
      award(2, 4, 1, "pending", ME),
      award(3, 3, 2, "approved", OTHER, { id: decisionId(1), kind: "approved", decidedAt: "2026-11-04T15:00:00Z", decidedBy: ME, reason: null }),
      award(4, 7, 2, "rejected", OTHER, { id: decisionId(2), kind: "rejected", decidedAt: "2026-11-05T15:00:00Z", decidedBy: ME, reason: "No presentó la documentación pedida" }),
    );
  }

  function readFailure(): ModelResponse | undefined {
    if (options.reads !== "failing-first") return undefined;
    reads += 1;
    return reads <= FAILING_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  }

  const locked = (scholarship: ScholarshipRow): boolean => awards.some((award) => award.scholarshipId === scholarship.id && (award.status === "approved" || award.status === "revoked"));
  const conceptsOf = (ids: readonly string[]) =>
    ids.flatMap((id) => {
      const found = concepts().find((concept) => concept.id === id);
      return found === undefined ? [] : [{ id: found.id, code: found.code, name: found.name }];
    });

  const scholarshipBody = (scholarship: ScholarshipRow) => ({
    id: scholarship.id,
    code: scholarship.code,
    name: scholarship.name,
    benefitType: scholarship.benefitType,
    percent: scholarship.percent,
    ...(scholarship.fixedAmount === null ? {} : { fixedAmount: scholarship.fixedAmount }),
    ...(scholarship.periodCap === null ? {} : { periodCap: scholarship.periodCap }),
    concepts: conceptsOf(scholarship.conceptIds),
    active: scholarship.active,
    benefitLocked: locked(scholarship),
    version: scholarship.version,
    createdAt: scholarship.createdAt,
    updatedAt: scholarship.updatedAt,
  });

  const decisionBody = (decision: DecisionRow) => ({
    id: decision.id,
    kind: decision.kind,
    decidedAt: decision.decidedAt,
    decidedBy: decision.decidedBy,
    ...(decision.reason === null ? {} : { reason: decision.reason }),
  });

  function awardBody(award: AwardRow) {
    const scholarship = scholarships.find((candidate) => candidate.id === award.scholarshipId);
    const student = context.students().find((candidate) => candidate.id === award.studentId);
    return {
      id: award.id,
      studentId: award.studentId,
      studentCode: student?.studentCode ?? null,
      scholarshipId: award.scholarshipId,
      scholarshipCode: scholarship?.code ?? null,
      scholarshipName: scholarship?.name ?? null,
      academicYearId: award.academicYearId,
      validFrom: award.validFrom,
      validTo: award.validTo,
      status: award.status,
      requestedAt: award.requestedAt,
      requestedBy: award.requestedBy,
      ...(award.decision === null ? {} : { decision: decisionBody(award.decision) }),
      ...(award.revocation === null ? {} : { revocation: decisionBody(award.revocation) }),
    };
  }

  function sortBy<Row>(rows: Row[], sort: string | null, fields: readonly string[], fallback: string, valueOf: (row: Row, field: string) => string): Row[] | ModelResponse {
    const [field = fallback, direction = field === "requestedAt" && sort === null ? "desc" : "asc"] = (sort ?? fallback).split(",");
    if (!fields.includes(field) || (direction !== "asc" && direction !== "desc")) return invalid([{ field: "sort", reason: "invalid" }]);
    const sign = direction === "asc" ? 1 : -1;
    return [...rows].sort((a, b) => sign * (valueOf(a, field) < valueOf(b, field) ? -1 : valueOf(a, field) > valueOf(b, field) ? 1 : 0));
  }

  function listScholarships(query: URLSearchParams): ModelResponse {
    const failed = readFailure();
    if (failed !== undefined) return failed;
    const active = query.get("active");
    const type = query.get("benefitType");
    const matching = scholarships.filter((row) => (active === null || row.active === (active === "true")) && (type === null || row.benefitType === type));
    const sorted = sortBy(matching, query.get("sort"), ["code", "name", "createdAt"], "code,asc", (value, field) => value[field as "code" | "name" | "createdAt"]);
    if (!Array.isArray(sorted)) return sorted;
    const paged = pageOf(sorted, query);
    if (!("slice" in paged)) return paged;
    return wrap(scholarshipPage(paged.slice.map(scholarshipBody), { page: paged.page, size: paged.size, total: sorted.length }));
  }

  function validCoverage(ids: readonly string[]): ModelResponse | undefined {
    if (ids.length === 0) return invalid([{ field: "conceptIds", reason: "size" }]);
    const known = ids.every((id) => concepts().some((concept) => concept.id === id));
    return known ? undefined : failure("resource-not-found", 404);
  }

  function createScholarship(body: unknown): ModelResponse {
    const parsed = CreateScholarshipRequest.safeParse(body);
    if (!parsed.success) return schemaErrors(parsed.error.issues);
    const input = parsed.data;
    if (input.benefitType === "PERCENTAGE" && (input.percent === undefined || input.percent === null)) return invalid([{ field: "percent", reason: "not-null" }]);
    if (input.benefitType === "FIXED_AMOUNT" && input.fixedAmount === undefined) return invalid([{ field: "fixedAmount", reason: "not-null" }]);
    if (scholarships.some((existing) => existing.code.toLowerCase() === input.code.toLowerCase())) return failure("duplicate-scholarship-code", 409);
    const coverage = validCoverage(input.conceptIds);
    if (coverage !== undefined) return coverage;
    sequence += 1;
    const created: ScholarshipRow = {
      id: scholarshipRowId(sequence),
      code: input.code,
      name: input.name,
      benefitType: input.benefitType,
      percent: input.benefitType === "PERCENTAGE" ? (input.percent ?? null) : null,
      fixedAmount: input.benefitType === "FIXED_AMOUNT" ? (input.fixedAmount ?? null) : null,
      periodCap: input.periodCap ?? null,
      conceptIds: input.conceptIds,
      active: true,
      version: 0,
      createdAt: context.now().toISOString(),
      updatedAt: context.now().toISOString(),
    };
    scholarships.push(created);
    return wrap(scholarshipRecord(scholarshipBody(created), 201));
  }

  function updateScholarship(scholarship: ScholarshipRow, body: unknown): ModelResponse {
    const parsed = UpdateScholarshipRequest.safeParse(body);
    if (!parsed.success) return schemaErrors(parsed.error.issues);
    const input = parsed.data;
    if (input.version !== scholarship.version) return failure("stale-version", 409);
    const coverage = validCoverage(input.conceptIds);
    if (coverage !== undefined) return coverage;
    const benefit = input.benefitType === "PERCENTAGE" ? (input.percent ?? null) : (input.fixedAmount?.amount ?? null);
    const held = scholarship.benefitType === "PERCENTAGE" ? scholarship.percent : (scholarship.fixedAmount?.amount ?? null);
    if (locked(scholarship) && (input.benefitType !== scholarship.benefitType || benefit !== held)) return failure("scholarship-benefit-locked", 409);
    Object.assign(scholarship, {
      name: input.name,
      benefitType: input.benefitType,
      percent: input.benefitType === "PERCENTAGE" ? (input.percent ?? null) : null,
      fixedAmount: input.benefitType === "FIXED_AMOUNT" ? (input.fixedAmount ?? null) : null,
      periodCap: input.periodCap ?? null,
      conceptIds: input.conceptIds,
      version: scholarship.version + 1,
      updatedAt: context.now().toISOString(),
    });
    return wrap(scholarshipRecord(scholarshipBody(scholarship)));
  }

  function setActive(scholarship: ScholarshipRow, active: boolean): ModelResponse {
    if (scholarship.active !== active) Object.assign(scholarship, { active, version: scholarship.version + 1, updatedAt: context.now().toISOString() });
    return wrap(scholarshipRecord(scholarshipBody(scholarship)));
  }

  function listAwards(query: URLSearchParams): ModelResponse {
    const failed = readFailure();
    if (failed !== undefined) return failed;
    for (const name of ["studentId", "scholarshipId", "academicYearId"]) {
      const value = query.get(name);
      if (value !== null && value !== "" && !UUID.test(value)) return invalid([{ field: name, reason: "invalid" }]);
    }
    const status = query.get("status");
    if (status !== null && !["pending", "approved", "rejected", "revoked"].includes(status)) return invalid([{ field: "status", reason: "pattern" }]);
    const matching = awards.filter(
      (row) =>
        (status === null || row.status === status) &&
        (query.get("studentId") === null || row.studentId === query.get("studentId")) &&
        (query.get("scholarshipId") === null || row.scholarshipId === query.get("scholarshipId")) &&
        (query.get("academicYearId") === null || row.academicYearId === query.get("academicYearId")),
    );
    const sorted = sortBy(matching, query.get("sort"), ["requestedAt", "validFrom", "validTo"], "requestedAt,desc", (value, field) => value[field as "requestedAt" | "validFrom" | "validTo"]);
    if (!Array.isArray(sorted)) return sorted;
    const paged = pageOf(sorted, query);
    if (!("slice" in paged)) return paged;
    return wrap(awardPage(paged.slice.map(awardBody), { page: paged.page, size: paged.size, total: sorted.length }));
  }

  function requestAward(body: unknown): ModelResponse {
    const parsed = RequestAwardRequest.safeParse(body);
    if (!parsed.success) return schemaErrors(parsed.error.issues);
    const input = parsed.data;
    if (input.validTo < input.validFrom) return invalid([{ field: "validTo", reason: "validity-order" }]);
    const scholarship = scholarships.find((candidate) => candidate.id === input.scholarshipId);
    if (scholarship === undefined || !context.students().some((student) => student.id === input.studentId)) return failure("resource-not-found", 404);
    if (!scholarship.active) return failure("inactive-reference", 409);
    sequence += 1;
    const created: AwardRow = {
      id: awardRowId(sequence),
      studentId: input.studentId,
      scholarshipId: input.scholarshipId,
      academicYearId: input.academicYearId,
      validFrom: input.validFrom,
      validTo: input.validTo,
      status: "pending",
      requestedAt: context.now().toISOString(),
      requestedBy: ME,
      decision: null,
      revocation: null,
      keys: new Map(),
    };
    awards.push(created);
    return wrap(awardRecord(awardBody(created), 201));
  }

  function decide(award: AwardRow, kind: "approve" | "reject" | "revoke", body: unknown, key: string | undefined): ModelResponse {
    if (key === undefined || key.trim() === "") return failure("idempotency-key-missing", 400);
    const fingerprint = JSON.stringify([kind, body ?? null]);
    const known = award.keys.get(key);
    if (known !== undefined) return known === fingerprint ? wrap(awardRecord(awardBody(award))) : failure("idempotency-payload-mismatch", 422);
    let reason: string | null = null;
    if (kind !== "approve") {
      const parsed = (kind === "reject" ? RejectScholarshipAwardBody : RevokeScholarshipAwardBody).safeParse(body);
      if (!parsed.success) return invalid([{ field: "reason", reason: "size" }]);
      reason = parsed.data.reason.trim();
      if (Array.from(reason).length < REASON_MIN || Array.from(reason).length > REASON_MAX) return invalid([{ field: "reason", reason: "size" }]);
    }
    if (kind !== "revoke" && award.requestedBy === ME) return failure("self-approval-forbidden", 403);
    if (kind === "revoke" ? award.status !== "approved" : award.status !== "pending") return failure("invalid-award-state", 409);
    const decision: DecisionRow = {
      id: decisionId(100 + ++sequence),
      kind: kind === "approve" ? "approved" : kind === "reject" ? "rejected" : "revoked",
      decidedAt: context.now().toISOString(),
      decidedBy: ME,
      reason,
    };
    if (kind === "revoke") Object.assign(award, { status: "revoked", revocation: decision });
    else Object.assign(award, { status: decision.kind, decision });
    award.keys.set(key, fingerprint);
    return wrap(awardRecord(awardBody(award)));
  }

  return {
    benefitFor(student, concept, periodStart) {
      for (const award of awards) {
        const scholarship = scholarships.find((candidate) => candidate.id === award.scholarshipId);
        if (award.status !== "approved" || award.studentId !== student || scholarship === undefined) continue;
        if (!scholarship.active || scholarship.benefitType !== "PERCENTAGE" || scholarship.percent === null || !scholarship.conceptIds.includes(concept)) continue;
        if (periodStart < award.validFrom || periodStart > award.validTo) continue;
        return { awardId: award.id, code: scholarship.code, hundredths: hundredthsOf(scholarship.percent) };
      }
      return undefined;
    },
    handle({ method, path, query, body, headers }) {
      if (path === SCHOLARSHIPS_BASE) {
        if (method === "GET") return listScholarships(query);
        return method === "POST" ? createScholarship(body) : undefined;
      }
      if (path === AWARDS_BASE) {
        if (method === "GET") return listAwards(query);
        return method === "POST" ? requestAward(body) : undefined;
      }
      const onScholarship = /^\/api\/v1\/scholarships\/([^/]+)(?:\/(deactivate|reactivate))?$/.exec(path);
      if (onScholarship !== null) {
        const found = scholarships.find((candidate) => candidate.id === onScholarship[1]);
        if (found === undefined) return failure("resource-not-found", 404);
        const action = onScholarship[2];
        if (action === undefined) {
          if (method === "GET") return wrap(scholarshipRecord(scholarshipBody(found)));
          return method === "PUT" ? updateScholarship(found, body) : undefined;
        }
        return method === "POST" ? setActive(found, action === "reactivate") : undefined;
      }
      const onAward = /^\/api\/v1\/scholarship-awards\/([^/]+)(?:\/(approve|reject|revoke))?$/.exec(path);
      if (onAward === null) return undefined;
      const found = awards.find((candidate) => candidate.id === onAward[1]);
      if (found === undefined) return failure("resource-not-found", 404);
      const action = onAward[2];
      if (action === undefined) {
        const failed = readFailure();
        if (failed !== undefined) return failed;
        return method === "GET" ? wrap(awardRecord(awardBody(found))) : undefined;
      }
      return method === "POST" ? decide(found, action as "approve" | "reject" | "revoke", body, headers?.["idempotency-key"]) : undefined;
    },
  };
}

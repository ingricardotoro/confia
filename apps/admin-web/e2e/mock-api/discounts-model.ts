import {
  ApproveDiscountChangeBody,
  CreateDiscountRequest,
  RejectDiscountChangeBody,
  RejectDiscountAssignmentBody,
  RequestDiscountAssignmentRequest,
  RevokeDiscountAssignmentBody,
  UpdateDiscountRequest,
} from "@confia/contracts/admin";
import { assignmentPage, assignmentRecord, discountPage, discountRecord, problem, type ContractResponse } from "../../src/test/contract-responses.ts";
import type { PaymentConceptRow } from "./catalog-seed.ts";
import { ME } from "./payments-model.ts";
import type { ModelResponse } from "./session-model.ts";
import { studentId, type StudentRow } from "./students-seed.ts";

/**
 * The discounts and their agreement assignments of the simulated API (scholarships-discounts cut): the operations below
 * `/api/v1/scholarships/discounts` and `/api/v1/scholarships/discount-assignments`, over rows kept in memory for one scenario. A thin simulation of the
 * published shapes and of the rules a journey needs: a code is never repeated (409 `duplicate-discount-code`), an assignment is born pending and only
 * another person decides it (403 `self-approval-forbidden` for the one who requested it), a decision needs an `Idempotency-Key` (the same key and body
 * answer the same assignment), the reason of a rejection or a revocation is 20 to 500 characters, a pending assignment is approved or rejected and an
 * approved one revoked (409 `invalid-discount-assignment-state` otherwise), and a discount with an approved or revoked assignment is in use, which locks
 * its type, value and stackable mark (409 `discount-benefit-locked`) and lets its scope only shrink (409 `discount-scope-locked`). A campaign discount is born
 * pending and another person approves or rejects it with the version they saw (`POST .../approve|reject`, 403 `self-approval-forbidden` for the one who
 * created it or proposed the change, 409 `invalid-discount-change-state` when nothing waits); widening one or reactivating it leaves a proposal that
 * changes nothing until then, and a discount that waits cannot be edited or reactivated (409 `discount-pending-approval`). An assignment keeps the
 * version and the benefit of its discount at the request, and approving it after the discount changed is refused (409 `discount-terms-changed`). The simulator of charges in this
 * simulated API does not apply discounts; the real rules, and their limits, are tested in the backend.
 */
export const DISCOUNTS_BASE = "/api/v1/scholarships/discounts";
export const ASSIGNMENTS_BASE = "/api/v1/scholarships/discount-assignments";

const OTHER = "00000000-0000-4000-8000-0000000000c9";
const STAMP = "2026-11-01T12:00:00Z";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const REASON_MIN = 20;
const REASON_MAX = 500;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const discountRowId = (n: number): string => `00000000-0000-4000-8000-0000000e${String(n).padStart(4, "0")}`;
export const assignmentRowId = (n: number): string => `00000000-0000-4000-8000-0000000c${String(n).padStart(4, "0")}`;
const decisionId = (n: number): string => `00000000-0000-4000-8000-0000000b${String(n).padStart(4, "0")}`;

interface Money {
  amount: string;
  currency: string;
}

interface ProposalRow {
  name: string;
  benefitType: "PERCENTAGE" | "FIXED_AMOUNT";
  percent: string | null;
  fixedAmount: Money | null;
  validFrom: string;
  validTo: string;
  stackable: boolean;
  conceptIds: string[];
  reactivate: boolean;
  requestedBy: string;
  requestedAt: string;
}

interface DiscountRow {
  id: string;
  code: string;
  name: string;
  kind: "campaign" | "agreement";
  benefitType: "PERCENTAGE" | "FIXED_AMOUNT";
  percent: string | null;
  fixedAmount: Money | null;
  validFrom: string;
  validTo: string;
  stackable: boolean;
  conceptIds: string[];
  active: boolean;
  approvalStatus: "pending" | "approved" | "rejected";
  createdBy: string;
  pending: ProposalRow | null;
  /** The key of the last decision and what it sent, so the same request is answered the same. */
  keys: Map<string, string>;
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

interface AssignmentRow {
  id: string;
  studentId: string;
  discountId: string;
  validFrom: string;
  validTo: string;
  status: "pending" | "approved" | "rejected" | "revoked";
  requestedAt: string;
  requestedBy: string;
  /** The version and the benefit of the discount when it was requested: the approval is bound to them. */
  discountVersion: number;
  benefitType: "PERCENTAGE" | "FIXED_AMOUNT";
  percent: string | null;
  fixedAmount: Money | null;
  decision: DecisionRow | null;
  revocation: DecisionRow | null;
  /** The key of the last decision and what it sent, so the same request is answered the same. */
  keys: Map<string, string>;
}

export interface DiscountsOptions {
  /**
   * `seeded`: an approved campaign discount, a campaign discount that another person created and waits for approval, an agreement discount and three
   * assignments (a pending one another person requested, one the person requested, and an approved one). `none`: nothing.
   */
  readonly seed: "none" | "seeded";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_DISCOUNTS: DiscountsOptions = { seed: "seeded", reads: "ok" };
const FAILING_READS = 3;
const TRACE_ID = "01J8Z4K2QF9P0X";

export interface DiscountsRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly body: unknown;
  readonly headers?: Readonly<Record<string, string | undefined>>;
}

export interface DiscountsContext {
  readonly students: () => readonly StudentRow[];
  readonly concepts: () => readonly PaymentConceptRow[];
  readonly now: () => Date;
}

export interface DiscountsModel {
  /** The answer to a request below the two bases, or `undefined` when the path is not one of the operations. */
  handle(request: DiscountsRequest): ModelResponse | undefined;
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });
const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse => wrap(problem(code, status, options));
const invalid = (errors: { field: string; reason: string }[] = []): ModelResponse => failure("validation-failed", 400, { errors });
const schemaErrors = (issues: readonly { path: PropertyKey[]; code: string }[]) =>
  invalid(issues.map((issue) => ({ field: issue.path.map(String).join(".") || "body", reason: issue.code === "invalid_format" ? "pattern" : "invalid" })));

const pageOf = <Row,>(rows: readonly Row[], query: URLSearchParams): { slice: Row[]; page: number; size: number } | ModelResponse => {
  const page = Number(query.get("page") ?? "0");
  const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
  if (!Number.isInteger(page) || page < 0) return invalid([{ field: "page", reason: "invalid" }]);
  if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) return invalid([{ field: "size", reason: "invalid" }]);
  return { slice: rows.slice(page * size, (page + 1) * size), page, size };
};

function sortBy<Row>(rows: Row[], sort: string | null, fields: readonly string[], fallback: string, valueOf: (row: Row, field: string) => string): Row[] | ModelResponse {
  const [field = fallback, direction = "asc"] = (sort ?? fallback).split(",");
  if (!fields.includes(field) || (direction !== "asc" && direction !== "desc")) return invalid([{ field: "sort", reason: "invalid" }]);
  const sign = direction === "asc" ? 1 : -1;
  return [...rows].sort((a, b) => sign * (valueOf(a, field) < valueOf(b, field) ? -1 : valueOf(a, field) > valueOf(b, field) ? 1 : 0));
}

export function createDiscountsModel(options: DiscountsOptions, context: DiscountsContext): DiscountsModel {
  const discounts: DiscountRow[] = [];
  const assignments: AssignmentRow[] = [];
  let reads = 0;
  let sequence = 100;

  const concepts = () => context.concepts();
  const discountRow = (
    n: number,
    code: string,
    name: string,
    kind: DiscountRow["kind"],
    percent: string,
    stackable: boolean,
    conceptIds: string[],
    approvalStatus: DiscountRow["approvalStatus"] = "approved",
  ): DiscountRow => ({
    id: discountRowId(n),
    code,
    name,
    kind,
    benefitType: "PERCENTAGE",
    percent,
    fixedAmount: null,
    validFrom: "2026-01-01",
    validTo: "2026-12-31",
    stackable,
    conceptIds,
    active: true,
    approvalStatus,
    createdBy: OTHER,
    pending: null,
    keys: new Map(),
    version: 0,
    createdAt: STAMP,
    updatedAt: STAMP,
  });
  const assignmentRow = (n: number, student: number, discount: number, status: AssignmentRow["status"], requestedBy: string, decision: DecisionRow | null = null): AssignmentRow => ({
    id: assignmentRowId(n),
    studentId: studentId(student),
    discountId: discountRowId(discount),
    validFrom: "2026-01-01",
    validTo: "2026-12-31",
    status,
    requestedAt: `2026-11-0${String(n)}T15:00:00Z`,
    requestedBy,
    discountVersion: 0,
    benefitType: "PERCENTAGE",
    percent: "20.00",
    fixedAmount: null,
    decision,
    revocation: null,
    keys: new Map(),
  });

  if (options.seed === "seeded") {
    const tuition = concepts()[0]?.id ?? "";
    discounts.push(
      discountRow(1, "CAMP10", "Campaña de matrícula", "campaign", "10.00", true, [tuition]),
      discountRow(2, "CONV20", "Convenio con la empresa", "agreement", "20.00", false, [tuition]),
      discountRow(3, "CAMP15", "Campaña de verano", "campaign", "15.00", true, [tuition], "pending"),
    );
    assignments.push(
      assignmentRow(1, 1, 2, "pending", OTHER),
      assignmentRow(2, 4, 2, "pending", ME),
      assignmentRow(3, 3, 2, "approved", OTHER, { id: decisionId(1), kind: "approved", decidedAt: "2026-11-04T15:00:00Z", decidedBy: ME, reason: null }),
    );
  }

  function readFailure(): ModelResponse | undefined {
    if (options.reads !== "failing-first") return undefined;
    reads += 1;
    return reads <= FAILING_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  }

  const waiting = (discount: DiscountRow): boolean => discount.approvalStatus !== "approved" || discount.pending !== null;
  const inUse = (discount: DiscountRow): boolean => assignments.some((candidate) => candidate.discountId === discount.id && (candidate.status === "approved" || candidate.status === "revoked"));
  const conceptsOf = (ids: readonly string[]) =>
    ids.flatMap((id) => {
      const found = concepts().find((concept) => concept.id === id);
      return found === undefined ? [] : [{ id: found.id, code: found.code, name: found.name }];
    });

  const discountBody = (discount: DiscountRow) => ({
    id: discount.id,
    code: discount.code,
    name: discount.name,
    kind: discount.kind,
    benefitType: discount.benefitType,
    percent: discount.percent,
    ...(discount.fixedAmount === null ? {} : { fixedAmount: discount.fixedAmount }),
    validFrom: discount.validFrom,
    validTo: discount.validTo,
    stackable: discount.stackable,
    concepts: conceptsOf(discount.conceptIds),
    active: discount.active,
    inUse: inUse(discount),
    approvalStatus: discount.approvalStatus,
    createdBy: discount.createdBy,
    ...(discount.pending === null
      ? {}
      : {
          pendingChange: {
            name: discount.pending.name,
            benefitType: discount.pending.benefitType,
            percent: discount.pending.percent,
            ...(discount.pending.fixedAmount === null ? {} : { fixedAmount: discount.pending.fixedAmount }),
            validFrom: discount.pending.validFrom,
            validTo: discount.pending.validTo,
            stackable: discount.pending.stackable,
            reactivate: discount.pending.reactivate,
            concepts: conceptsOf(discount.pending.conceptIds),
            requestedBy: discount.pending.requestedBy,
            requestedAt: discount.pending.requestedAt,
          },
        }),
    version: discount.version,
    createdAt: discount.createdAt,
    updatedAt: discount.updatedAt,
  });

  const decisionBody = (decision: DecisionRow) => ({
    id: decision.id,
    kind: decision.kind,
    decidedAt: decision.decidedAt,
    decidedBy: decision.decidedBy,
    ...(decision.reason === null ? {} : { reason: decision.reason }),
  });

  function assignmentBody(assignment: AssignmentRow) {
    const discount = discounts.find((candidate) => candidate.id === assignment.discountId);
    const student = context.students().find((candidate) => candidate.id === assignment.studentId);
    return {
      id: assignment.id,
      studentId: assignment.studentId,
      studentCode: student?.studentCode ?? null,
      discountId: assignment.discountId,
      discountCode: discount?.code ?? null,
      discountName: discount?.name ?? null,
      discountVersion: assignment.discountVersion,
      benefitType: assignment.benefitType,
      percent: assignment.percent,
      ...(assignment.fixedAmount === null ? {} : { fixedAmount: assignment.fixedAmount }),
      validFrom: assignment.validFrom,
      validTo: assignment.validTo,
      status: assignment.status,
      requestedAt: assignment.requestedAt,
      requestedBy: assignment.requestedBy,
      ...(assignment.decision === null ? {} : { decision: decisionBody(assignment.decision) }),
      ...(assignment.revocation === null ? {} : { revocation: decisionBody(assignment.revocation) }),
    };
  }

  function listDiscounts(query: URLSearchParams): ModelResponse {
    const failed = readFailure();
    if (failed !== undefined) return failed;
    const active = query.get("active");
    const kind = query.get("kind");
    const type = query.get("benefitType");
    const matching = discounts.filter(
      (row) => (active === null || row.active === (active === "true")) && (kind === null || row.kind === kind) && (type === null || row.benefitType === type),
    );
    const sorted = sortBy(matching, query.get("sort"), ["code", "name", "validFrom", "createdAt"], "code,asc", (value, field) => value[field as "code" | "name" | "validFrom" | "createdAt"]);
    if (!Array.isArray(sorted)) return sorted;
    const paged = pageOf(sorted, query);
    if (!("slice" in paged)) return paged;
    return wrap(discountPage(paged.slice.map(discountBody), { page: paged.page, size: paged.size, total: sorted.length }));
  }

  function validCoverage(ids: readonly string[]): ModelResponse | undefined {
    if (ids.length === 0) return invalid([{ field: "conceptIds", reason: "size" }]);
    return ids.every((id) => concepts().some((concept) => concept.id === id)) ? undefined : failure("resource-not-found", 404);
  }

  function createDiscount(body: unknown): ModelResponse {
    const parsed = CreateDiscountRequest.safeParse(body);
    if (!parsed.success) return schemaErrors(parsed.error.issues);
    const input = parsed.data;
    if (input.benefitType === "PERCENTAGE" && (input.percent === undefined || input.percent === null)) return invalid([{ field: "percent", reason: "not-null" }]);
    if (input.benefitType === "FIXED_AMOUNT" && input.fixedAmount === undefined) return invalid([{ field: "fixedAmount", reason: "not-null" }]);
    if (input.validTo < input.validFrom) return invalid([{ field: "validTo", reason: "validity-order" }]);
    if (discounts.some((existing) => existing.code.toLowerCase() === input.code.toLowerCase())) return failure("duplicate-discount-code", 409);
    const coverage = validCoverage(input.conceptIds);
    if (coverage !== undefined) return coverage;
    sequence += 1;
    const created: DiscountRow = {
      id: discountRowId(sequence),
      code: input.code,
      name: input.name,
      kind: input.kind,
      benefitType: input.benefitType,
      percent: input.benefitType === "PERCENTAGE" ? (input.percent ?? null) : null,
      fixedAmount: input.benefitType === "FIXED_AMOUNT" ? (input.fixedAmount ?? null) : null,
      validFrom: input.validFrom,
      validTo: input.validTo,
      stackable: input.stackable,
      conceptIds: input.conceptIds,
      active: true,
      // A campaign discount applies to nothing until another person approves it; an agreement one is always approved.
      approvalStatus: input.kind === "campaign" ? "pending" : "approved",
      createdBy: ME,
      pending: null,
      keys: new Map(),
      version: 0,
      createdAt: context.now().toISOString(),
      updatedAt: context.now().toISOString(),
    };
    discounts.push(created);
    return wrap(discountRecord(discountBody(created), 201));
  }

  function updateDiscount(discount: DiscountRow, body: unknown): ModelResponse {
    const parsed = UpdateDiscountRequest.safeParse(body);
    if (!parsed.success) return schemaErrors(parsed.error.issues);
    const input = parsed.data;
    if (input.version !== discount.version) return failure("stale-version", 409);
    if (waiting(discount)) return failure("discount-pending-approval", 409);
    if (input.validTo < input.validFrom) return invalid([{ field: "validTo", reason: "validity-order" }]);
    const coverage = validCoverage(input.conceptIds);
    if (coverage !== undefined) return coverage;
    if (inUse(discount)) {
      const benefit = input.benefitType === "PERCENTAGE" ? (input.percent ?? null) : (input.fixedAmount?.amount ?? null);
      const held = discount.benefitType === "PERCENTAGE" ? discount.percent : (discount.fixedAmount?.amount ?? null);
      if (input.benefitType !== discount.benefitType || benefit !== held || input.stackable !== discount.stackable) return failure("discount-benefit-locked", 409);
      const widens = input.validFrom < discount.validFrom || input.validTo > discount.validTo || input.conceptIds.some((id) => !discount.conceptIds.includes(id));
      if (widens) return failure("discount-scope-locked", 409);
    }
    const outside = assignments.some(
      (candidate) =>
        candidate.discountId === discount.id &&
        (candidate.status === "pending" || candidate.status === "approved") &&
        (candidate.validFrom < input.validFrom || candidate.validTo > input.validTo),
    );
    if (outside) return invalid([{ field: "validTo", reason: "assignments-outside-validity" }]);
    const widensCampaign =
      discount.kind === "campaign" &&
      (input.benefitType !== discount.benefitType ||
        (input.benefitType === "PERCENTAGE" ? Number(input.percent ?? 0) > Number(discount.percent ?? 0) : Number(input.fixedAmount?.amount ?? 0) > Number(discount.fixedAmount?.amount ?? 0)) ||
        input.validFrom < discount.validFrom ||
        input.validTo > discount.validTo ||
        input.conceptIds.some((id) => !discount.conceptIds.includes(id)) ||
        (input.stackable && !discount.stackable));
    if (widensCampaign) {
      // A widening is only a proposal: the terms in force do not change until another person approves it.
      discount.pending = {
        name: input.name,
        benefitType: input.benefitType,
        percent: input.benefitType === "PERCENTAGE" ? (input.percent ?? null) : null,
        fixedAmount: input.benefitType === "FIXED_AMOUNT" ? (input.fixedAmount ?? null) : null,
        validFrom: input.validFrom,
        validTo: input.validTo,
        stackable: input.stackable,
        conceptIds: input.conceptIds,
        reactivate: false,
        requestedBy: ME,
        requestedAt: context.now().toISOString(),
      };
      Object.assign(discount, { version: discount.version + 1, updatedAt: context.now().toISOString() });
      return wrap(discountRecord(discountBody(discount)));
    }
    Object.assign(discount, {
      name: input.name,
      benefitType: input.benefitType,
      percent: input.benefitType === "PERCENTAGE" ? (input.percent ?? null) : null,
      fixedAmount: input.benefitType === "FIXED_AMOUNT" ? (input.fixedAmount ?? null) : null,
      validFrom: input.validFrom,
      validTo: input.validTo,
      stackable: input.stackable,
      conceptIds: input.conceptIds,
      version: discount.version + 1,
      updatedAt: context.now().toISOString(),
    });
    return wrap(discountRecord(discountBody(discount)));
  }

  function setActive(discount: DiscountRow, active: boolean): ModelResponse {
    if (active && waiting(discount)) return failure("discount-pending-approval", 409);
    if (active && !discount.active && discount.kind === "campaign") {
      // Reactivating a campaign discount is only a proposal: it stays off until another person approves it.
      discount.pending = {
        name: discount.name,
        benefitType: discount.benefitType,
        percent: discount.percent,
        fixedAmount: discount.fixedAmount,
        validFrom: discount.validFrom,
        validTo: discount.validTo,
        stackable: discount.stackable,
        conceptIds: discount.conceptIds,
        reactivate: true,
        requestedBy: ME,
        requestedAt: context.now().toISOString(),
      };
      Object.assign(discount, { version: discount.version + 1, updatedAt: context.now().toISOString() });
      return wrap(discountRecord(discountBody(discount)));
    }
    if (discount.active !== active) Object.assign(discount, { active, version: discount.version + 1, updatedAt: context.now().toISOString() });
    return wrap(discountRecord(discountBody(discount)));
  }

  function decideDiscount(discount: DiscountRow, kind: "approve" | "reject", body: unknown, key: string | undefined): ModelResponse {
    if (key === undefined || key.trim() === "") return failure("idempotency-key-missing", 400);
    const fingerprint = JSON.stringify([kind, body ?? null]);
    const known = discount.keys.get(key);
    if (known !== undefined) return known === fingerprint ? wrap(discountRecord(discountBody(discount))) : failure("idempotency-payload-mismatch", 422);
    const parsed = ApproveDiscountChangeBody.safeParse(body);
    if (!parsed.success) return invalid([{ field: "version", reason: "invalid" }]);
    if (kind === "reject") {
      const rejected = RejectDiscountChangeBody.safeParse(body);
      if (!rejected.success) return invalid([{ field: "reason", reason: "invalid" }]);
      const reason = rejected.data.reason.trim();
      if (Array.from(reason).length < REASON_MIN || Array.from(reason).length > REASON_MAX) return invalid([{ field: "reason", reason: "size" }]);
    }
    if (parsed.data.version !== discount.version) return failure("stale-version", 409);
    const proposal = discount.pending;
    const creation = discount.approvalStatus === "pending";
    if (!creation && proposal === null) return failure("invalid-discount-change-state", 409);
    if ((creation ? discount.createdBy : proposal?.requestedBy) === ME) return failure("self-approval-forbidden", 403);
    if (kind === "approve") {
      if (creation) Object.assign(discount, { approvalStatus: "approved" });
      else if (proposal !== null) {
        Object.assign(discount, {
          name: proposal.name,
          benefitType: proposal.benefitType,
          percent: proposal.percent,
          fixedAmount: proposal.fixedAmount,
          validFrom: proposal.validFrom,
          validTo: proposal.validTo,
          stackable: proposal.stackable,
          conceptIds: proposal.conceptIds,
          active: proposal.reactivate ? true : discount.active,
          pending: null,
        });
      }
    } else if (creation) Object.assign(discount, { approvalStatus: "rejected", active: false });
    else Object.assign(discount, { pending: null });
    Object.assign(discount, { version: discount.version + 1, updatedAt: context.now().toISOString() });
    discount.keys.set(key, fingerprint);
    return wrap(discountRecord(discountBody(discount)));
  }

  function listAssignments(query: URLSearchParams): ModelResponse {
    const failed = readFailure();
    if (failed !== undefined) return failed;
    for (const name of ["studentId", "discountId"]) {
      const value = query.get(name);
      if (value !== null && value !== "" && !UUID.test(value)) return invalid([{ field: name, reason: "invalid" }]);
    }
    const status = query.get("status");
    if (status !== null && !["pending", "approved", "rejected", "revoked"].includes(status)) return invalid([{ field: "status", reason: "pattern" }]);
    const matching = assignments.filter(
      (row) =>
        (status === null || row.status === status) &&
        (query.get("studentId") === null || row.studentId === query.get("studentId")) &&
        (query.get("discountId") === null || row.discountId === query.get("discountId")),
    );
    const sorted = sortBy(matching, query.get("sort"), ["requestedAt", "validFrom", "validTo"], "requestedAt,desc", (value, field) => value[field as "requestedAt" | "validFrom" | "validTo"]);
    if (!Array.isArray(sorted)) return sorted;
    const paged = pageOf(sorted, query);
    if (!("slice" in paged)) return paged;
    return wrap(assignmentPage(paged.slice.map(assignmentBody), { page: paged.page, size: paged.size, total: sorted.length }));
  }

  function requestAssignment(body: unknown): ModelResponse {
    const parsed = RequestDiscountAssignmentRequest.safeParse(body);
    if (!parsed.success) return schemaErrors(parsed.error.issues);
    const input = parsed.data;
    if (input.validTo < input.validFrom) return invalid([{ field: "validTo", reason: "validity-order" }]);
    const discount = discounts.find((candidate) => candidate.id === input.discountId);
    if (discount === undefined || !context.students().some((student) => student.id === input.studentId)) return failure("resource-not-found", 404);
    if (!discount.active || discount.kind !== "agreement") return failure("inactive-reference", 409);
    if (input.validFrom < discount.validFrom || input.validTo > discount.validTo) return invalid([{ field: "validFrom", reason: "outside-discount-validity" }]);
    sequence += 1;
    const created: AssignmentRow = {
      id: assignmentRowId(sequence),
      studentId: input.studentId,
      discountId: input.discountId,
      validFrom: input.validFrom,
      validTo: input.validTo,
      status: "pending",
      requestedAt: context.now().toISOString(),
      requestedBy: ME,
      discountVersion: discount.version,
      benefitType: discount.benefitType,
      percent: discount.percent,
      fixedAmount: discount.fixedAmount,
      decision: null,
      revocation: null,
      keys: new Map(),
    };
    assignments.push(created);
    return wrap(assignmentRecord(assignmentBody(created), 201));
  }

  function decide(assignment: AssignmentRow, kind: "approve" | "reject" | "revoke", body: unknown, key: string | undefined): ModelResponse {
    if (key === undefined || key.trim() === "") return failure("idempotency-key-missing", 400);
    const fingerprint = JSON.stringify([kind, body ?? null]);
    const known = assignment.keys.get(key);
    if (known !== undefined) return known === fingerprint ? wrap(assignmentRecord(assignmentBody(assignment))) : failure("idempotency-payload-mismatch", 422);
    let reason: string | null = null;
    if (kind !== "approve") {
      const parsed = (kind === "reject" ? RejectDiscountAssignmentBody : RevokeDiscountAssignmentBody).safeParse(body);
      if (!parsed.success) return invalid([{ field: "reason", reason: "size" }]);
      reason = parsed.data.reason.trim();
      if (Array.from(reason).length < REASON_MIN || Array.from(reason).length > REASON_MAX) return invalid([{ field: "reason", reason: "size" }]);
    }
    if (kind !== "revoke" && assignment.requestedBy === ME) return failure("self-approval-forbidden", 403);
    if (kind === "revoke" ? assignment.status !== "approved" : assignment.status !== "pending") return failure("invalid-discount-assignment-state", 409);
    if (kind === "approve") {
      const discount = discounts.find((candidate) => candidate.id === assignment.discountId);
      if (discount !== undefined && discount.version !== assignment.discountVersion) return failure("discount-terms-changed", 409);
    }
    const decision: DecisionRow = {
      id: decisionId(100 + ++sequence),
      kind: kind === "approve" ? "approved" : kind === "reject" ? "rejected" : "revoked",
      decidedAt: context.now().toISOString(),
      decidedBy: ME,
      reason,
    };
    if (kind === "revoke") Object.assign(assignment, { status: "revoked", revocation: decision });
    else Object.assign(assignment, { status: decision.kind, decision });
    assignment.keys.set(key, fingerprint);
    return wrap(assignmentRecord(assignmentBody(assignment)));
  }

  return {
    handle({ method, path, query, body, headers }) {
      if (path === DISCOUNTS_BASE) {
        if (method === "GET") return listDiscounts(query);
        return method === "POST" ? createDiscount(body) : undefined;
      }
      if (path === ASSIGNMENTS_BASE) {
        if (method === "GET") return listAssignments(query);
        return method === "POST" ? requestAssignment(body) : undefined;
      }
      const onDiscount = /^\/api\/v1\/scholarships\/discounts\/([^/]+)(?:\/(deactivate|reactivate|approve|reject))?$/.exec(path);
      if (onDiscount !== null) {
        const found = discounts.find((candidate) => candidate.id === onDiscount[1]);
        if (found === undefined) return failure("resource-not-found", 404);
        const action = onDiscount[2];
        if (action === undefined) {
          if (method === "GET") return wrap(discountRecord(discountBody(found)));
          return method === "PUT" ? updateDiscount(found, body) : undefined;
        }
        if (method !== "POST") return undefined;
        if (action === "approve" || action === "reject") return decideDiscount(found, action, body, headers?.["idempotency-key"]);
        return setActive(found, action === "reactivate");
      }
      const onAssignment = /^\/api\/v1\/scholarships\/discount-assignments\/([^/]+)(?:\/(approve|reject|revoke))?$/.exec(path);
      if (onAssignment === null) return undefined;
      const found = assignments.find((candidate) => candidate.id === onAssignment[1]);
      if (found === undefined) return failure("resource-not-found", 404);
      const action = onAssignment[2];
      if (action === undefined) {
        const failed = readFailure();
        if (failed !== undefined) return failed;
        return method === "GET" ? wrap(assignmentRecord(assignmentBody(found))) : undefined;
      }
      return method === "POST" ? decide(found, action as "approve" | "reject" | "revoke", body, headers?.["idempotency-key"]) : undefined;
    },
  };
}

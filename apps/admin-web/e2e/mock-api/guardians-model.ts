import { CreateGuardianRequest, ReplaceStudentGuardiansRequest, UpdateGuardianRequest } from "@confia/contracts/admin";
import { guardianPage, guardianRecord, problem, studentGuardians, type ContractResponse } from "../../src/test/contract-responses.ts";
import { emptyGuardians, seededGuardians, type GuardianRow, type GuardianRows, type LinkRow } from "./guardians-seed.ts";
import type { ModelResponse } from "./session-model.ts";
import type { StudentsModel } from "./students-model.ts";

/**
 * The guardians of the simulated API (guardians cut): the six operations of `/api/v1/guardians` and of
 * `/api/v1/students/{id}/guardians`, over rows kept in memory for one scenario and over the students of the same scenario. It is
 * a thin simulation of the published shapes and of only the rules a journey needs: the exact split of the responsibility (sum
 * 100.00, at most one primary payer, at most one billing recipient, no repeated guardian), inactive guardians and students,
 * a repeated national id even with other hyphens, the three ways to treat the document and the RTN on an edit, and a refused
 * write. The real rules, and their limits, are tested in the backend. Paging, order and search are done here as the server does
 * them; the page never does them. The national id and the RTN are kept apart from the rows and no response carries them.
 */
export const GUARDIANS_BASE = "/api/v1/guardians";
/** How many reads fail first in the `server-error` behavior: the first try and the two retries of the page. */
export const FAILING_GUARDIAN_READS = 3;
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const MIN_QUERY = 2;
const MAX_QUERY = 100;
const MAX_NAME = 100;
const MAX_LINKS = 20;
const SORT_FIELDS = ["lastName", "code", "status"] as const;
const CREATE_MEMBERS = ["firstName", "lastName", "nationalId", "rtn", "email", "phone", "address", "preferredChannel"];
const UPDATE_MEMBERS = [...CREATE_MEMBERS, "status"];
const NATIONAL_ID = /^[A-Za-z0-9-]{8,20}$/;
const MIN_NORMALIZED_ID = 5;
const RTN = /^\d{4}-?\d{4}-?\d{6}$/;
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const PHONE = /^\+?[0-9(][0-9 ()-]{5,18}[0-9]$/;
const TWO_DECIMALS = /^\d+(?:\.\d{1,2})?$/;
const collator = new Intl.Collator("es");

export interface GuardiansOptions {
  readonly seed: "empty" | "seeded";
  /** `forbidden`: a read answers, every write is refused with 403 and changes nothing. */
  readonly writes: "allowed" | "forbidden";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_GUARDIANS: GuardiansOptions = { seed: "seeded", writes: "allowed", reads: "ok" };

export interface GuardiansRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly body: unknown;
}

export interface GuardiansModel {
  /** The answer to a request of the guardians, or `undefined` when the path is not one of the six operations. */
  handle(request: GuardiansRequest): ModelResponse | undefined;
  /** What the scenario holds now, for a unit test; never the page. */
  rows(): Readonly<GuardianRows>;
}

interface Schema {
  safeParse(input: unknown): { readonly success: true } | { readonly success: false; readonly error: { readonly issues: readonly { readonly path: readonly PropertyKey[] }[] } };
}

type Violation = { field: string; reason: string };

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });
const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse => wrap(problem(code, status, options));
const invalid = (errors: Violation[]): ModelResponse => failure("validation-failed", 400, { errors });
const splitRefused = (reason: string): ModelResponse => failure("invalid-responsibility-split", 400, { errors: [{ field: "links", reason }] });

const normalizedId = (document: string): string => document.replaceAll("-", "").toUpperCase();
/** The percentage in hundredths, for the simulator's own comparison only (never the page's). */
const hundredths = (percent: number): number => Math.round(percent * 100);

export function createGuardiansModel(
  options: GuardiansOptions,
  context: { readonly students: StudentsModel },
  now: () => Date = () => new Date(),
): GuardiansModel {
  const data = options.seed === "seeded" ? seededGuardians() : emptyGuardians();
  let reads = 0;
  let counter = data.guardians.length;
  let linkCounter = 100;

  const today = (): string => new Date(now().getTime() - 6 * 3_600_000).toISOString().slice(0, 10);
  const studentOf = (id: string) => context.students.rows().students.find((row) => row.id === id);
  const present = (row: GuardianRow, withStudents: boolean) => ({
    ...row,
    hasNationalId: data.documents.has(row.id),
    hasRtn: data.rtns.has(row.id),
    ...(withStudents
      ? {
          students: data.links
            .filter((link) => link.guardianId === row.id)
            .map((link) => {
              const student = studentOf(link.studentId);
              return {
                linkId: link.linkId,
                studentId: link.studentId,
                studentCode: student?.studentCode ?? "",
                studentFirstName: student?.firstName ?? "",
                studentLastName: student?.lastName ?? "",
                relationship: link.relationship,
                financialResponsibilityPercent: link.financialResponsibilityPercent,
                isPrimaryPayer: link.isPrimaryPayer,
                isBillingRecipient: link.isBillingRecipient,
                validFrom: link.validFrom,
              };
            }),
        }
      : {}),
  });

  const failingRead = (): ModelResponse | undefined => {
    if (options.reads !== "failing-first") return undefined;
    reads += 1;
    return reads <= FAILING_GUARDIAN_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  };

  function list(query: URLSearchParams): ModelResponse {
    const failed = failingRead();
    if (failed !== undefined) return failed;
    const errors: Violation[] = [];
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) errors.push({ field: "page", reason: "invalid" });
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) errors.push({ field: "size", reason: "invalid" });
    const [field = "", direction = "asc", ...rest] = (query.get("sort") ?? "lastName,asc").split(",");
    if (!(SORT_FIELDS as readonly string[]).includes(field) || (direction !== "asc" && direction !== "desc") || rest.length > 0) errors.push({ field: "sort", reason: "invalid" });
    const text = query.get("q");
    if (text !== null && (text.trim().length < MIN_QUERY || text.length > MAX_QUERY)) errors.push({ field: "q", reason: "size" });
    const status = query.get("status");
    if (status !== null && status !== "ACTIVE" && status !== "INACTIVE") errors.push({ field: "status", reason: "pattern" });
    if (errors.length > 0) return invalid(errors);

    const wanted = text?.trim().toLowerCase();
    const matching = data.guardians.filter(
      (row) => (status === null || row.status === status) && (wanted === undefined || [row.firstName, row.lastName, row.guardianCode].some((value) => value.toLowerCase().includes(wanted))),
    );
    const sign = direction === "asc" ? 1 : -1;
    const key = (row: GuardianRow): string => (field === "code" ? row.guardianCode : field === "status" ? row.status : row.lastName);
    const ordered = [...matching].sort((a, b) => sign * collator.compare(key(a), key(b)) || collator.compare(a.id, b.id));
    const rows = ordered.slice(page * size, (page + 1) * size).map((row) => present(row, false));
    return wrap(guardianPage(rows, { page, size, total: ordered.length }));
  }

  function badBody(schema: Schema, members: readonly string[], body: unknown, update: boolean): ModelResponse | undefined {
    if (typeof body !== "object" || body === null || Array.isArray(body)) return invalid([{ field: "body", reason: "invalid" }]);
    const unknown = Object.keys(body).filter((name) => !members.includes(name));
    if (unknown.length > 0) return invalid(unknown.map((field) => ({ field, reason: "unknown" })));
    const parsed = schema.safeParse(body);
    if (!parsed.success) {
      const errors = parsed.error.issues.map((issue) => ({ field: issue.path.map(String).join(".") || "body", reason: "invalid" }));
      return invalid(errors.length === 0 ? [{ field: "body", reason: "invalid" }] : errors);
    }
    const values = body as Record<string, string | undefined>;
    const errors: Violation[] = [];
    for (const name of ["firstName", "lastName"] as const) {
      const text = (values[name] ?? "").trim();
      if (text === "") errors.push({ field: name, reason: "not-blank" });
      else if (text.length > MAX_NAME) errors.push({ field: name, reason: "size" });
    }
    const document = values["nationalId"]?.trim() ?? "";
    if (document !== "" && (!NATIONAL_ID.test(document) || normalizedId(document).length < MIN_NORMALIZED_ID)) errors.push({ field: "nationalId", reason: "pattern" });
    for (const [name, format] of [["rtn", RTN], ["email", EMAIL], ["phone", PHONE]] as const) {
      const text = values[name]?.trim() ?? "";
      if (text !== "" && !format.test(text)) errors.push({ field: name, reason: "pattern" });
    }
    if (update && values["status"] === undefined) errors.push({ field: "status", reason: "not-null" });
    return errors.length === 0 ? undefined : invalid(errors);
  }

  const ownerOf = (document: string, self: string | undefined): string | undefined =>
    [...data.documents.entries()].find(([id, stored]) => id !== self && normalizedId(stored) === normalizedId(document))?.[0];

  function create(body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const bad = badBody(CreateGuardianRequest, CREATE_MEMBERS, body, false);
    if (bad !== undefined) return bad;
    const values = body as Record<string, string | undefined>;
    const document = values["nationalId"]?.trim() ?? "";
    if (document !== "" && ownerOf(document, undefined) !== undefined) return failure("duplicate-national-id", 409);
    counter += 1;
    const stamp = now().toISOString();
    const row: GuardianRow = {
      id: crypto.randomUUID(),
      guardianCode: `P-${String(counter).padStart(6, "0")}`,
      firstName: (values["firstName"] ?? "").trim(),
      lastName: (values["lastName"] ?? "").trim(),
      email: values["email"]?.trim() || null,
      phone: values["phone"]?.trim() || null,
      address: values["address"]?.trim() || null,
      preferredChannel: (values["preferredChannel"] ?? "NONE") as GuardianRow["preferredChannel"],
      status: "ACTIVE",
      createdAt: stamp,
      updatedAt: stamp,
    };
    data.guardians.push(row);
    if (document !== "") data.documents.set(row.id, document);
    const rtn = values["rtn"]?.trim() ?? "";
    if (rtn !== "") data.rtns.set(row.id, rtn);
    return wrap(guardianRecord(present(row, true), 201));
  }

  function update(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const self = data.guardians.find((row) => row.id === id);
    if (self === undefined) return failure("resource-not-found", 404);
    const bad = badBody(UpdateGuardianRequest, UPDATE_MEMBERS, body, true);
    if (bad !== undefined) return bad;
    const values = body as Record<string, string | undefined>;
    const document = values["nationalId"]?.trim();
    if (document !== undefined && document !== "" && ownerOf(document, id) !== undefined) return failure("duplicate-national-id", 409);
    Object.assign(self, {
      firstName: (values["firstName"] ?? "").trim(),
      lastName: (values["lastName"] ?? "").trim(),
      // What the edit omits is cleared, as the server does.
      email: values["email"]?.trim() || null,
      phone: values["phone"]?.trim() || null,
      address: values["address"]?.trim() || null,
      preferredChannel: values["preferredChannel"] ?? "NONE",
      status: values["status"],
      updatedAt: now().toISOString(),
    });
    for (const [name, store] of [["nationalId", data.documents], ["rtn", data.rtns]] as const) {
      const sent = values[name]?.trim();
      if (sent === "") store.delete(id);
      else if (sent !== undefined) store.set(id, sent);
    }
    return wrap(guardianRecord(present(self, true)));
  }

  function detail(id: string): ModelResponse {
    const failed = failingRead();
    if (failed !== undefined) return failed;
    const row = data.guardians.find((candidate) => candidate.id === id);
    return row === undefined ? failure("resource-not-found", 404) : wrap(guardianRecord(present(row, true)));
  }

  const linksOf = (studentId: string): LinkRow[] => data.links.filter((link) => link.studentId === studentId);

  function setOf(studentId: string): ModelResponse {
    const student = studentOf(studentId);
    if (student === undefined) return failure("resource-not-found", 404);
    const links = linksOf(studentId).map((link) => {
      const guardian = data.guardians.find((row) => row.id === link.guardianId);
      return {
        linkId: link.linkId,
        guardianId: link.guardianId,
        guardianCode: guardian?.guardianCode ?? "",
        guardianFirstName: guardian?.firstName ?? "",
        guardianLastName: guardian?.lastName ?? "",
        guardianStatus: guardian?.status ?? "ACTIVE",
        relationship: link.relationship,
        financialResponsibilityPercent: link.financialResponsibilityPercent,
        isPrimaryPayer: link.isPrimaryPayer,
        isBillingRecipient: link.isBillingRecipient,
        validFrom: link.validFrom,
      };
    });
    // The server adds in exact decimal arithmetic; the simulator in hundredths. Never the page.
    const totalPercent = links.reduce((sum, link) => sum + hundredths(link.financialResponsibilityPercent), 0) / 100;
    return wrap(studentGuardians(links, { studentId: student.id, studentCode: student.studentCode, totalPercent }));
  }

  function readSet(studentId: string): ModelResponse {
    const failed = failingRead();
    return failed ?? setOf(studentId);
  }

  function replaceSet(studentId: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    if (typeof body !== "object" || body === null || Array.isArray(body) || Object.keys(body).some((name) => name !== "links")) {
      return invalid([{ field: "body", reason: "invalid" }]);
    }
    const parsed = ReplaceStudentGuardiansRequest.safeParse(body);
    const rawLinks = (body as { links?: unknown }).links;
    const errors: Violation[] = [];
    if (Array.isArray(rawLinks)) {
      (rawLinks as { financialResponsibilityPercent?: unknown }[]).forEach((link, index) => {
        const percent = link.financialResponsibilityPercent;
        if (typeof percent !== "number") return;
        const field = `links[${String(index)}].financialResponsibilityPercent`;
        if (!TWO_DECIMALS.test(String(percent))) errors.push({ field, reason: "digits" });
        else if (percent < 0.01) errors.push({ field, reason: "decimal-min" });
        else if (percent > 100) errors.push({ field, reason: "decimal-max" });
      });
    }
    if (errors.length > 0) return invalid(errors);
    if (!parsed.success) return invalid(parsed.error.issues.map((issue) => ({ field: issue.path.map(String).join(".") || "body", reason: "invalid" })));
    const links = parsed.data.links;
    if (links.length > MAX_LINKS) return invalid([{ field: "links", reason: "size" }]);
    // The rules of the set, judged before any lock, in the order of the server.
    if (new Set(links.map((link) => link.guardianId)).size !== links.length) return splitRefused("duplicate-guardian");
    if (links.filter((link) => link.isPrimaryPayer).length > 1) return splitRefused("multiple-primary-payer");
    if (links.filter((link) => link.isBillingRecipient).length > 1) return splitRefused("multiple-billing-recipient");
    if (links.length > 0 && links.reduce((sum, link) => sum + hundredths(link.financialResponsibilityPercent), 0) !== 10_000) return splitRefused("sum-not-100");

    const student = studentOf(studentId);
    if (student === undefined) return failure("resource-not-found", 404);
    if (student.status !== "ACTIVE") return failure("student-not-active", 409);
    const found = links.map((link) => data.guardians.find((row) => row.id === link.guardianId));
    if (found.some((row) => row === undefined)) return failure("resource-not-found", 404);
    if (found.some((row) => row?.status !== "ACTIVE")) return failure("guardian-not-active", 409);

    const current = linksOf(studentId);
    const same = (old: LinkRow, next: (typeof links)[number]): boolean =>
      old.guardianId === next.guardianId &&
      old.relationship === next.relationship &&
      hundredths(old.financialResponsibilityPercent) === hundredths(next.financialResponsibilityPercent) &&
      old.isPrimaryPayer === next.isPrimaryPayer &&
      old.isBillingRecipient === next.isBillingRecipient;
    // A link that is absent or changed is closed (kept in the history); one that is the same stays as it is.
    for (const old of current) {
      if (!links.some((next) => same(old, next))) {
        data.links.splice(data.links.indexOf(old), 1);
        data.history.push(old);
      }
    }
    for (const next of links) {
      if (current.some((old) => same(old, next))) continue;
      linkCounter += 1;
      data.links.push({
        linkId: `00000000-0000-4000-8000-${String(8000 + linkCounter).padStart(12, "0")}`,
        studentId,
        guardianId: next.guardianId,
        relationship: next.relationship,
        financialResponsibilityPercent: next.financialResponsibilityPercent,
        isPrimaryPayer: next.isPrimaryPayer,
        isBillingRecipient: next.isBillingRecipient,
        validFrom: today(),
      });
    }
    return setOf(studentId);
  }

  return {
    rows: () => data,
    handle({ method, path, query, body }) {
      const guardians = /^\/api\/v1\/guardians(?:\/([^/]+))?$/.exec(path);
      if (guardians !== null) {
        const id = guardians[1];
        if (id === undefined && method === "GET") return list(query);
        if (id === undefined && method === "POST") return create(body);
        if (id !== undefined && method === "GET") return detail(id);
        if (id !== undefined && method === "PUT") return update(id, body);
        return undefined;
      }
      const links = /^\/api\/v1\/students\/([^/]+)\/guardians$/.exec(path);
      if (links?.[1] === undefined) return undefined;
      if (method === "GET") return readSet(links[1]);
      if (method === "PUT") return replaceSet(links[1], body);
      return undefined;
    },
  };
}

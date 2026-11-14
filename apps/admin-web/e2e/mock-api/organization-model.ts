import {
  CreateAcademicYearRequest,
  CreateGradeRequest,
  CreateModalityRequest,
  CreateSectionRequest,
  UpdateAcademicYearRequest,
  UpdateGradeRequest,
  UpdateModalityRequest,
  UpdateSectionRequest,
} from "@confia/contracts/admin";
import {
  organizationPage,
  organizationRecord,
  problem,
  type ContractResponse,
  type OrganizationResource,
} from "../../src/test/contract-responses.ts";
import { emptyRows, seededRows, type OrganizationRows } from "./organization-seed.ts";
import type { ModelResponse } from "./session-model.ts";

/**
 * The academic structure of the simulated API (organization-academic-structure cut): the twelve operations of
 * `/api/v1/organization`, over rows kept in memory for one scenario. It is a thin simulation of the published shapes
 * and of only the rules a journey needs (a repeated name, a year that overlaps another, the single year in progress,
 * a closed year, an inactive reference, and a refused write). The real rules, and their limits, are tested in the
 * backend. Paging, order and filters are done here as the server does them; the page never does them.
 */
export const ORGANIZATION_BASE = "/api/v1/organization";
/** How many reads of each resource fail first in the `server-error` behavior: the first try and the two retries of the page. */
export const FAILING_READS = 3;
/** A fictitious trace id, the shape the backend gives. */
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;

export interface OrganizationOptions {
  /** The rows the scenario starts with. */
  readonly seed: "empty" | "seeded";
  /** `forbidden`: a read answers, every write is refused with 403 and changes nothing. */
  readonly writes: "allowed" | "forbidden";
  /** `failing-first`: the first reads of each resource answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_ORGANIZATION: OrganizationOptions = { seed: "seeded", writes: "allowed", reads: "ok" };

export interface OrganizationRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  /** The parsed JSON body, or `undefined` when there was none or it was not JSON. */
  readonly body: unknown;
}

export interface OrganizationModel {
  /** The answer to a request below `/api/v1/organization`, or `undefined` when the path is not one of the twelve operations. */
  handle(request: OrganizationRequest): ModelResponse | undefined;
  /** What the scenario holds now, for a unit test; never the page. */
  rows(): Readonly<OrganizationRows>;
}

interface Schema {
  safeParse(input: unknown):
    | { readonly success: true; readonly data: unknown }
    | { readonly success: false; readonly error: { readonly issues: readonly { readonly path: readonly PropertyKey[]; readonly code: string }[] } };
}

interface Row {
  id: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

const SORT_FIELDS: Readonly<Record<OrganizationResource, readonly string[]>> = {
  "academic-years": ["name", "startDate", "endDate", "status"],
  modalities: ["code", "name"],
  grades: ["name", "ordinal"],
  sections: ["name", "capacity"],
};

/** The order the server applies when the request names none. */
const DEFAULT_SORT: Readonly<Record<OrganizationResource, string>> = {
  "academic-years": "startDate,desc",
  modalities: "code,asc",
  grades: "ordinal,asc",
  sections: "name,asc",
};

const FILTERS: Readonly<Record<OrganizationResource, readonly string[]>> = {
  "academic-years": [],
  modalities: [],
  grades: ["modalityId"],
  sections: ["academicYearId", "gradeId"],
};

const CREATE_SCHEMAS: Readonly<Record<OrganizationResource, Schema>> = {
  "academic-years": CreateAcademicYearRequest,
  modalities: CreateModalityRequest,
  grades: CreateGradeRequest,
  sections: CreateSectionRequest,
};

const UPDATE_SCHEMAS: Readonly<Record<OrganizationResource, Schema>> = {
  "academic-years": UpdateAcademicYearRequest,
  modalities: UpdateModalityRequest,
  grades: UpdateGradeRequest,
  sections: UpdateSectionRequest,
};

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });

const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse =>
  wrap(problem(code, status, options));

const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

const isResource = (value: string): value is OrganizationResource => Object.hasOwn(SORT_FIELDS, value);

/** The value of a field of a row, for the comparison of an order. */
function valueOf(row: object, field: string): string | number {
  const value = (row as Record<string, unknown>)[field];
  return typeof value === "number" ? value : typeof value === "string" ? value : Number.NEGATIVE_INFINITY;
}

export function createOrganizationModel(options: OrganizationOptions = DEFAULT_ORGANIZATION, now: () => Date = () => new Date()): OrganizationModel {
  const data = options.seed === "seeded" ? seededRows() : emptyRows();
  const tables: Record<OrganizationResource, Row[]> = {
    "academic-years": data.years,
    modalities: data.modalities,
    grades: data.grades,
    sections: data.sections,
  };
  const reads = new Map<OrganizationResource, number>();

  function list(resource: OrganizationResource, query: URLSearchParams): ModelResponse {
    if (options.reads === "failing-first") {
      const seen = reads.get(resource) ?? 0;
      reads.set(resource, seen + 1);
      if (seen < FAILING_READS) return failure("internal-error", 500, { traceId: TRACE_ID });
    }
    const pageText = query.get("page") ?? "0";
    const sizeText = query.get("size") ?? String(DEFAULT_SIZE);
    const page = Number(pageText);
    const size = Number(sizeText);
    if (!Number.isInteger(page) || page < 0) return invalid([{ field: "page", reason: "invalid" }]);
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) return invalid([{ field: "size", reason: "invalid" }]);
    const [field = "", direction = "asc", ...rest] = (query.get("sort") ?? DEFAULT_SORT[resource]).split(",");
    if (!SORT_FIELDS[resource].includes(field) || (direction !== "asc" && direction !== "desc") || rest.length > 0) {
      return invalid([{ field: "sort", reason: "invalid" }]);
    }
    const filters = FILTERS[resource].flatMap((name) => {
      const wanted = query.get(name);
      return wanted === null || wanted === "" ? [] : [[name, wanted] as const];
    });
    const matching = tables[resource].filter((row) => filters.every(([name, wanted]) => valueOf(row, name) === wanted));
    const sign = direction === "asc" ? 1 : -1;
    // The tie breaks on the id, so two requests for the same page see the same rows.
    const ordered = [...matching].sort((a, b) => {
      const left = valueOf(a, field);
      const right = valueOf(b, field);
      return left < right ? -sign : left > right ? sign : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
    return wrap(organizationPage(resource, ordered.slice(page * size, (page + 1) * size), { page, size, total: ordered.length }));
  }

  /** The 400 of a body that does not meet the request contract, or of a blank name or code. */
  function badBody(resource: OrganizationResource, schema: Schema, body: unknown): ModelResponse | undefined {
    const parsed = schema.safeParse(body);
    if (!parsed.success) {
      const errors = parsed.error.issues.map((issue) => ({ field: issue.path.map(String).join(".") || "body", reason: "invalid" }));
      return invalid(errors.length === 0 ? [{ field: "body", reason: "invalid" }] : errors);
    }
    const values = parsed.data as Record<string, unknown>;
    const blank = ["name", "code"].filter((field) => typeof values[field] === "string" && values[field].trim() === "");
    if (blank.length > 0) return invalid(blank.map((field) => ({ field, reason: "required" })));
    if (resource === "academic-years" && String(values["endDate"]) <= String(values["startDate"])) {
      return invalid([{ field: "endAfterStart", reason: "invalid" }]);
    }
    return undefined;
  }

  const find = (resource: OrganizationResource, id: string): Row | undefined => tables[resource].find((row) => row.id === id);

  /** The rule that refuses a write, if any. `self` is the row being edited, which never collides with itself. */
  function refusal(resource: OrganizationResource, values: Record<string, unknown>, self: Row | undefined, creating: boolean): ModelResponse | undefined {
    const others = tables[resource].filter((row) => row !== self);
    const sameField = (row: Row, field: string): boolean => valueOf(row, field) === (values[field] as string | number);
    switch (resource) {
      case "academic-years": {
        if (self !== undefined && valueOf(self, "status") === "CLOSED") return failure("academic-year-closed", 409);
        if (others.some((row) => sameField(row, "name"))) return failure("duplicate-name", 409);
        // The ends are inclusive: a year that starts the day another ends overlaps it.
        const start = values["startDate"] as string;
        const end = values["endDate"] as string;
        if (others.some((row) => start <= valueOf(row, "endDate") && end >= valueOf(row, "startDate"))) return failure("academic-year-overlap", 409);
        if (values["status"] === "ACTIVE" && others.some((row) => valueOf(row, "status") === "ACTIVE")) return failure("academic-year-already-active", 409);
        return undefined;
      }
      case "modalities":
        return others.some((row) => sameField(row, "code")) ? failure("duplicate-name", 409) : undefined;
      case "grades": {
        const modalityId = creating ? (values["modalityId"] as string) : (self as unknown as { modalityId: string }).modalityId;
        const modality = find("modalities", modalityId);
        if (creating && modality === undefined) return failure("resource-not-found", 404);
        if (creating && modality?.isActive === false) return failure("inactive-reference", 409);
        const siblings = others.filter((row) => valueOf(row, "modalityId") === modalityId);
        return siblings.some((row) => sameField(row, "name") || sameField(row, "ordinal")) ? failure("duplicate-name", 409) : undefined;
      }
      case "sections": {
        const own = self as unknown as { academicYearId: string; gradeId: string } | undefined;
        const yearId = own?.academicYearId ?? (values["academicYearId"] as string);
        const gradeId = own?.gradeId ?? (values["gradeId"] as string);
        const year = find("academic-years", yearId);
        const grade = find("grades", gradeId);
        if (year === undefined || grade === undefined) return failure("resource-not-found", 404);
        if (valueOf(year, "status") === "CLOSED") return failure("academic-year-closed", 409);
        if (creating && !grade.isActive) return failure("inactive-reference", 409);
        const siblings = others.filter((row) => valueOf(row, "academicYearId") === yearId && valueOf(row, "gradeId") === gradeId);
        return siblings.some((row) => sameField(row, "name")) ? failure("duplicate-name", 409) : undefined;
      }
    }
  }

  function write(resource: OrganizationResource, id: string | undefined, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const creating = id === undefined;
    const self = creating ? undefined : find(resource, id);
    if (!creating && self === undefined) return failure("resource-not-found", 404);
    const bad = badBody(resource, (creating ? CREATE_SCHEMAS : UPDATE_SCHEMAS)[resource], body);
    if (bad !== undefined) return bad;
    const values = body as Record<string, unknown>;
    const refused = refusal(resource, values, self, creating);
    if (refused !== undefined) return refused;

    const stamp = now().toISOString();
    if (self === undefined) {
      const row = {
        id: crypto.randomUUID(),
        ...values,
        ...(resource === "academic-years" ? { status: values["status"] ?? "PLANNED" } : {}),
        ...(resource === "sections" ? { capacity: values["capacity"] ?? null } : {}),
        isActive: true,
        createdAt: stamp,
        updatedAt: stamp,
      } as Row;
      tables[resource].push(row);
      return wrap(organizationRecord(resource, row, 201));
    }
    // A PUT replaces the editable fields; the ones fixed at creation (modality, year and grade) are not in the body.
    Object.assign(self, values, resource === "sections" ? { capacity: values["capacity"] ?? null } : {}, { updatedAt: stamp });
    return wrap(organizationRecord(resource, self, 200));
  }

  return {
    rows: () => data,
    handle({ method, path, query, body }) {
      const match = /^\/api\/v1\/organization\/([a-z-]+)(?:\/([^/]+))?$/.exec(path);
      const [, name = "", id] = match ?? [];
      if (match === null || !isResource(name)) return undefined;
      if (id === undefined && method === "GET") return list(name, query);
      if (id === undefined && method === "POST") return write(name, undefined, body);
      if (id !== undefined && method === "PUT") return write(name, id, body);
      return undefined;
    },
  };
}

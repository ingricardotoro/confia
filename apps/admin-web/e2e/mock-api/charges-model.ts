import { AddChargePlanItemRequest, CreateChargePlanRequest, UpdateChargePlanRequest } from "@confia/contracts/admin";
import {
  chargePlanDetailRecord,
  chargePlanItemRecord,
  chargePlanPage,
  chargePlanRecord,
  noContent,
  problem,
  type ContractResponse,
} from "../../src/test/contract-responses.ts";
import { createAccrualSimulator, type AccrualScenario, type AccrualSimulator } from "./accrual-model.ts";
import type { CatalogModel } from "./catalog-model.ts";
import {
  emptyPlans,
  seededPlans,
  type ChargePlanItemRow,
  type ChargePlanRow,
} from "./charges-seed.ts";
import type { OrganizationModel } from "./organization-model.ts";
import type { ModelResponse } from "./session-model.ts";
import type { StudentsModel } from "./students-model.ts";

/**
 * The charge plans of the simulated API (charges-plans cut): the six operations of `/api/v1/charges/plans`, over rows kept
 * in memory for one scenario and over the academic structure and the catalog of the same scenario. It is a thin simulation
 * of the published shapes and of only the rules a journey needs: the states a plan goes through (a draft is activated, an
 * active plan is archived, nothing else), the items only while it is a draft, an active plan that keeps its frequency and
 * due day, a plan that cannot be activated without items, a concept that is active and not repeated, a stale version, a
 * record that does not exist, and a refused write. The real rules, and their limits, are tested in the backend. Paging,
 * order and filters are done here as the server does them; the page never does them.
 */
export const CHARGES_BASE = "/api/v1/charges";
/** How many reads fail first in the `failing-first` behavior: the first try and the two retries of the page. */
export const FAILING_READS = 3;
/** A fictitious trace id, the shape the backend gives. */
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const SORT_FIELDS = ["name", "createdAt"] as const;
const SCOPES: readonly string[] = ["MODALITY", "GRADE", "SECTION"];
const STATUSES: readonly string[] = ["DRAFT", "ACTIVE", "ARCHIVED"];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface ChargesOptions {
  /** The plans the scenario starts with. */
  readonly seed: "empty" | "seeded";
  /**
   * `forbidden`: a read answers, every write is refused with 403 and changes nothing. `stale-first`: the first edit of a
   * plan answers 409 `stale-version`, as if someone else had saved it first (the plan's version moves on).
   */
  readonly writes: "allowed" | "forbidden" | "stale-first";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
  /**
   * The accrual engine (charges-accrual cut): `default` seeds four runs and lets a preview and a run go through; `empty` seeds none;
   * `blocked` makes the preview report posting accounts that are not complete; `digest-changed` moves the calculation after the
   * first preview, so the run is rejected; `in-progress` answers 409 to a request; `forbidden` refuses the preview and the request
   * with 403; `preview-fails-first` fails the first preview with 500.
   */
  readonly accrual: AccrualScenario;
}

export const DEFAULT_CHARGES: ChargesOptions = { seed: "seeded", writes: "allowed", reads: "ok", accrual: "default" };

export interface ChargesRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  /** The parsed JSON body, or `undefined` when there was none or it was not JSON. */
  readonly body: unknown;
  /** The headers of the request, with their names in lower case (the accrual request needs its `Idempotency-Key`). */
  readonly headers?: Readonly<Record<string, string | undefined>>;
}

export interface ChargesModel {
  /** The answer to a request below `/api/v1/charges`, or `undefined` when the path is not one of its operations. */
  handle(request: ChargesRequest): ModelResponse | undefined;
  /** What the scenario holds now, for a unit test; never the page. */
  plans(): readonly ChargePlanRow[];
  items(): readonly ChargePlanItemRow[];
  /** The accrual engine of the scenario, for a unit test. */
  accrual(): AccrualSimulator;
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });

const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse =>
  wrap(problem(code, status, options));

const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

function schemaFailure(issues: readonly { path: PropertyKey[]; code: string }[]): ModelResponse {
  const errors = issues.map((issue) => ({
    field: issue.path.map(String).join(".") || "body",
    reason: issue.code === "invalid_format" ? "pattern" : "invalid",
  }));
  return invalid(errors.length === 0 ? [{ field: "body", reason: "invalid" }] : errors);
}

export function createChargesModel(
  options: ChargesOptions,
  context: { readonly organization: OrganizationModel; readonly catalog: CatalogModel; readonly students?: StudentsModel },
  now: () => Date = () => new Date(),
): ChargesModel {
  const data = options.seed === "seeded" ? seededPlans() : emptyPlans();
  let reads = 0;
  /** The `stale-first` write has been served: the next edits meet the version the plan holds. */
  let staleServed = false;

  const org = () => context.organization.rows();
  const accrual = createAccrualSimulator(options.accrual, {
    plans: () => data,
    students: () => context.students?.rows().students ?? [],
    concepts: () => context.catalog.rows(),
    readFailure,
    now,
  });

  /** The first edit of the `stale-first` scenario: someone else "saved" first, so the plan moves on and the edit is refused. */
  function staleFirst(plan: { version: number }): boolean {
    if (options.writes !== "stale-first" || staleServed) return false;
    staleServed = true;
    plan.version += 1;
    return true;
  }

  function readFailure(): ModelResponse | undefined {
    if (options.reads !== "failing-first") return undefined;
    reads += 1;
    return reads <= FAILING_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  }

  const itemBody = (item: ChargePlanItemRow) => ({ ...item });

  function list(query: URLSearchParams): ModelResponse {
    const failed = readFailure();
    if (failed !== undefined) return failed;
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) return invalid([{ field: "page", reason: "invalid" }]);
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) return invalid([{ field: "size", reason: "invalid" }]);
    const [field = "", direction = "asc", ...rest] = (query.get("sort") ?? "name,asc").split(",");
    if (!(SORT_FIELDS as readonly string[]).includes(field) || (direction !== "asc" && direction !== "desc") || rest.length > 0) {
      return invalid([{ field: "sort", reason: "invalid" }]);
    }
    const year = query.get("academicYearId");
    const scope = query.get("targetScope");
    const status = query.get("status");
    if (year !== null && year !== "" && !UUID.test(year)) return invalid([{ field: "academicYearId", reason: "invalid" }]);
    if (scope !== null && scope !== "" && !SCOPES.includes(scope)) return invalid([{ field: "targetScope", reason: "invalid" }]);
    if (status !== null && status !== "" && !STATUSES.includes(status)) return invalid([{ field: "status", reason: "invalid" }]);
    const matching = data.plans.filter(
      (row) =>
        (year === null || year === "" || row.academicYearId === year) &&
        (scope === null || scope === "" || row.targetScope === scope) &&
        (status === null || status === "" || row.status === status),
    );
    const sign = direction === "asc" ? 1 : -1;
    const keyOf = (row: ChargePlanRow): string => (field === "name" ? row.name.toLowerCase() : row.createdAt);
    const ordered = [...matching].sort((a, b) => {
      const left = keyOf(a);
      const right = keyOf(b);
      return left < right ? -sign : left > right ? sign : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
    return wrap(chargePlanPage(ordered.slice(page * size, (page + 1) * size), { page, size, total: ordered.length }));
  }

  function detail(id: string): ModelResponse {
    const failed = readFailure();
    if (failed !== undefined) return failed;
    const plan = data.plans.find((row) => row.id === id);
    if (plan === undefined) return failure("resource-not-found", 404);
    return wrap(chargePlanDetailRecord({ ...plan, items: data.items.filter((item) => item.planId === id).map(itemBody) }));
  }

  /** Whether the row a plan points at exists in the institution, by scope. */
  function targetExists(scope: string, id: string): boolean {
    const rows = scope === "MODALITY" ? org().modalities : scope === "GRADE" ? org().grades : org().sections;
    return rows.some((row) => row.id === id);
  }

  function create(body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const parsed = CreateChargePlanRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    const { academicYearId, name, targetScope, targetRefId, frequency, dueDayOfMonth } = parsed.data;
    if (name.trim() === "") return invalid([{ field: "name", reason: "required" }]);
    // The year and the target must be of the institution of the session: a row that is not there is a 404, as the server answers.
    if (!org().years.some((year) => year.id === academicYearId) || !targetExists(targetScope, targetRefId)) return failure("resource-not-found", 404);
    const stamp = now().toISOString();
    const row: ChargePlanRow = {
      id: crypto.randomUUID(),
      academicYearId,
      name,
      targetScope,
      targetRefId,
      frequency,
      dueDayOfMonth,
      status: "DRAFT",
      version: 0,
      createdAt: stamp,
      updatedAt: stamp,
    };
    data.plans.push(row);
    return wrap(chargePlanRecord(row, 201));
  }

  function update(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const self = data.plans.find((row) => row.id === id);
    if (self === undefined) return failure("resource-not-found", 404);
    const parsed = UpdateChargePlanRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    const next = parsed.data;
    if (next.name.trim() === "") return invalid([{ field: "name", reason: "required" }]);
    if (staleFirst(self) || next.version !== self.version) return failure("stale-version", 409);
    // The transitions are DRAFT to ACTIVE and ACTIVE to ARCHIVED, and a plan may stay as it is while its data changes. A
    // plan that is archived changes in nothing, and an active one keeps its frequency and its due day.
    const same = next.status === self.status;
    const allowed = same || (self.status === "DRAFT" && next.status === "ACTIVE") || (self.status === "ACTIVE" && next.status === "ARCHIVED");
    if (!allowed || self.status === "ARCHIVED") return failure("plan-not-editable", 409);
    if (self.status === "ACTIVE" && (next.frequency !== self.frequency || next.dueDayOfMonth !== self.dueDayOfMonth)) {
      return failure("plan-not-editable", 409);
    }
    if (self.status === "DRAFT" && next.status === "ACTIVE" && !data.items.some((item) => item.planId === id)) return failure("plan-without-items", 409);
    Object.assign(self, {
      name: next.name,
      frequency: next.frequency,
      dueDayOfMonth: next.dueDayOfMonth,
      status: next.status,
      version: self.version + 1,
      updatedAt: now().toISOString(),
    });
    return wrap(chargePlanRecord(self, 200));
  }

  function addItem(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const self = data.plans.find((row) => row.id === id);
    if (self === undefined) return failure("resource-not-found", 404);
    const parsed = AddChargePlanItemRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    const { paymentConceptId, periodsCount, firstPeriodOn } = parsed.data;
    // The first day of the first period is the first day of a month between 2000 and 2100: the domain refuses another, with no field named.
    if (!firstPeriodOn.endsWith("-01") || firstPeriodOn < "2000-01-01" || firstPeriodOn > "2100-12-01") return invalid([]);
    if (self.status !== "DRAFT") return failure("plan-not-editable", 409);
    const concept = context.catalog.rows().find((row) => row.id === paymentConceptId);
    if (concept === undefined) return failure("resource-not-found", 404);
    if (!concept.isActive) return failure("inactive-reference", 409);
    if (data.items.some((item) => item.planId === id && item.paymentConceptId === paymentConceptId)) return failure("duplicate-plan-item", 409);
    const item: ChargePlanItemRow = {
      id: crypto.randomUUID(),
      planId: id,
      paymentConceptId,
      periodsCount,
      firstPeriodOn,
      createdAt: now().toISOString(),
    };
    data.items.push(item);
    return wrap(chargePlanItemRecord(itemBody(item)));
  }

  function removeItem(id: string, itemId: string): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const self = data.plans.find((row) => row.id === id);
    const index = data.items.findIndex((item) => item.id === itemId && item.planId === id);
    if (self === undefined || index < 0) return failure("resource-not-found", 404);
    if (self.status !== "DRAFT") return failure("plan-not-editable", 409);
    data.items.splice(index, 1);
    return wrap(noContent());
  }

  return {
    plans: () => data.plans,
    items: () => data.items,
    accrual: () => accrual,
    handle({ method, path, query, body, headers }) {
      // The accrual engine has its own routes: the periods and the preview and request of a plan, and the runs.
      const accrued = accrual.handle({ method, path, query, body, ...(headers === undefined ? {} : { headers }) });
      if (accrued !== undefined) return accrued;
      const match = /^\/api\/v1\/charges\/plans(?:\/([^/]+)(?:(\/items)(?:\/([^/]+))?)?)?$/.exec(path);
      if (match === null) return undefined;
      const [, id, items, itemId] = match;
      if (id === undefined) {
        if (method === "GET") return list(query);
        if (method === "POST") return create(body);
        return undefined;
      }
      if (items === undefined) {
        if (method === "GET") return detail(id);
        if (method === "PUT") return update(id, body);
        return undefined;
      }
      if (itemId === undefined) return method === "POST" ? addItem(id, body) : undefined;
      return method === "DELETE" ? removeItem(id, itemId) : undefined;
    },
  };
}

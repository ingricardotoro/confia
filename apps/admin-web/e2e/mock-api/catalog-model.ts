import { AddPriceRequest, CreatePaymentConceptRequest, CreatePriceListRequest, UpdatePaymentConceptRequest, UpdatePriceListRequest } from "@confia/contracts/admin";
import {
  paymentConceptPage,
  paymentConceptRecord,
  priceListDetailRecord,
  priceListItemRecord,
  priceListPage,
  priceListRecord,
  problem,
  type ContractResponse,
} from "../../src/test/contract-responses.ts";
import {
  emptyPriceLists,
  emptyRows,
  seededPriceLists,
  seededRows,
  type PaymentConceptRow,
  type PriceItemRow,
  type PriceListRow,
} from "./catalog-seed.ts";
import type { ModelResponse } from "./session-model.ts";

/**
 * The catalog of the simulated API: the three operations of `/api/v1/catalog/payment-concepts` (catalog-payment-concepts
 * cut) and the five of `/api/v1/catalog/price-lists` (catalog-price-lists cut), over rows kept in memory for one
 * scenario. It is a thin simulation of the published shapes and of only the rules a journey needs (a code that repeats
 * whatever its capitals, a code that cannot be sent in an edit, a stale version, a price validity that overlaps or closes
 * the open one, an archived list, a record that does not exist, and a refused write). The real rules, and their limits,
 * are tested in the backend. Paging, order and filters are done here as the server does them; the page never does them.
 * An amount is kept as the text it was sent in and answered rounded to two decimals, half up, by its digits: the
 * simulator, like the page, never turns an amount into a number.
 */
export const CATALOG_BASE = "/api/v1/catalog";
/** How many reads fail first in the `failing-first` behavior: the first try and the two retries of the page. */
export const FAILING_READS = 3;
/** A fictitious trace id, the shape the backend gives. */
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const SORT_FIELDS = ["code", "name"] as const;
const PRICE_LIST_SORT_FIELDS = ["name", "createdAt"] as const;
const SCOPES: readonly string[] = ["INSTITUTION", "MODALITY", "GRADE"];
const STATUSES: readonly string[] = ["ACTIVE", "ARCHIVED"];
const CATEGORIES: readonly string[] = ["TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"];

export interface CatalogOptions {
  /** The rows the scenario starts with. */
  readonly seed: "empty" | "seeded";
  /** The price lists the scenario starts with. */
  readonly lists: "empty" | "seeded";
  /**
   * `forbidden`: a read answers, every write is refused with 403 and changes nothing. `stale-first`: the first edit of a
   * concept or of a list answers 409 `stale-version`, as if someone else had saved it first (the record's version moves on).
   */
  readonly writes: "allowed" | "forbidden" | "stale-first";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_CATALOG: CatalogOptions = { seed: "seeded", lists: "seeded", writes: "allowed", reads: "ok" };

export interface CatalogRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  /** The parsed JSON body, or `undefined` when there was none or it was not JSON. */
  readonly body: unknown;
}

export interface CatalogModel {
  /** The answer to a request below `/api/v1/catalog`, or `undefined` when the path is not one of its operations. */
  handle(request: CatalogRequest): ModelResponse | undefined;
  /** What the scenario holds now, for a unit test; never the page. */
  rows(): readonly PaymentConceptRow[];
  lists(): readonly PriceListRow[];
  items(): readonly PriceItemRow[];
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });

const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse =>
  wrap(problem(code, status, options));

const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

export function createCatalogModel(options: CatalogOptions = DEFAULT_CATALOG, now: () => Date = () => new Date()): CatalogModel {
  const rows = options.seed === "seeded" ? seededRows() : emptyRows();
  const prices = options.lists === "seeded" ? seededPriceLists() : emptyPriceLists();
  let reads = 0;
  let priceReads = 0;
  /** The `stale-first` write has been served: the next edits meet the version the record holds. */
  let staleServed = false;

  /** The first edit of the `stale-first` scenario: someone else "saved" first, so the record moves on and the edit is refused. */
  function staleFirst(record: { version: number }): boolean {
    if (options.writes !== "stale-first" || staleServed) return false;
    staleServed = true;
    record.version += 1;
    return true;
  }

  function list(query: URLSearchParams): ModelResponse {
    if (options.reads === "failing-first") {
      reads += 1;
      if (reads <= FAILING_READS) return failure("internal-error", 500, { traceId: TRACE_ID });
    }
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) return invalid([{ field: "page", reason: "invalid" }]);
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) return invalid([{ field: "size", reason: "invalid" }]);
    const [field = "", direction = "asc", ...rest] = (query.get("sort") ?? "code,asc").split(",");
    if (!(SORT_FIELDS as readonly string[]).includes(field) || (direction !== "asc" && direction !== "desc") || rest.length > 0) {
      return invalid([{ field: "sort", reason: "invalid" }]);
    }
    const category = query.get("category");
    if (category !== null && category !== "" && !CATEGORIES.includes(category)) return invalid([{ field: "category", reason: "invalid" }]);
    const active = query.get("active");
    if (active !== null && active !== "" && active !== "true" && active !== "false") return invalid([{ field: "active", reason: "invalid" }]);
    const matching = rows.filter(
      (row) => (category === null || category === "" || row.category === category) && (active === null || active === "" || String(row.isActive) === active),
    );
    const sign = direction === "asc" ? 1 : -1;
    // The code is ordered without telling capitals apart, and the tie breaks on the id, so two requests see the same rows.
    const keyOf = (row: PaymentConceptRow): string => (field === "code" ? row.code : row.name).toLowerCase();
    const ordered = [...matching].sort((a, b) => {
      const left = keyOf(a);
      const right = keyOf(b);
      return left < right ? -sign : left > right ? sign : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
    return wrap(paymentConceptPage(ordered.slice(page * size, (page + 1) * size), { page, size, total: ordered.length }));
  }

  function write(id: string | undefined, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const creating = id === undefined;
    const self = creating ? undefined : rows.find((row) => row.id === id);
    if (!creating && self === undefined) return failure("resource-not-found", 404);
    // The code never travels in an edit: a body that brings it is refused, as the server does.
    if (!creating && typeof body === "object" && body !== null && "code" in body) return invalid([{ field: "code", reason: "not-allowed" }]);
    const parsed = (creating ? CreatePaymentConceptRequest : UpdatePaymentConceptRequest).safeParse(body);
    if (!parsed.success) {
      // The server names a value that breaks a pattern (the code whitelist, the name without control characters) `pattern`.
      const errors = parsed.error.issues.map((issue) => ({
        field: issue.path.map(String).join(".") || "body",
        reason: issue.code === "invalid_format" ? "pattern" : "invalid",
      }));
      return invalid(errors.length === 0 ? [{ field: "body", reason: "invalid" }] : errors);
    }
    const values = parsed.data as Record<string, unknown>;
    const blank = ["name", "code"].filter((field) => typeof values[field] === "string" && values[field].trim() === "");
    if (blank.length > 0) return invalid(blank.map((field) => ({ field, reason: "required" })));
    const stamp = now().toISOString();
    if (self === undefined) {
      const code = String(values["code"]);
      if (rows.some((row) => row.code.toLowerCase() === code.toLowerCase())) return failure("duplicate-payment-concept-code", 409);
      const row = {
        id: crypto.randomUUID(),
        code,
        name: String(values["name"]),
        category: values["category"],
        isTaxable: values["isTaxable"],
        isRecurring: values["isRecurring"],
        isActive: true,
        version: 0,
        createdAt: stamp,
        updatedAt: stamp,
      } as PaymentConceptRow;
      rows.push(row);
      return wrap(paymentConceptRecord(row, 201));
    }
    // A PUT carries the version it was read at: another one means the concept changed since, and nothing is saved.
    if (staleFirst(self) || values["version"] !== self.version) return failure("stale-version", 409);
    // A PUT replaces the editable fields; the code, fixed at creation, is not in the body, and the version is the server's.
    const editable = Object.fromEntries(Object.entries(values).filter(([name]) => name !== "version"));
    Object.assign(self, editable, { version: self.version + 1, updatedAt: stamp });
    return wrap(paymentConceptRecord(self, 200));
  }

  // ---- The price lists (catalog-price-lists cut) ----

  /** The amount as the server answers it: two decimals, half up, worked on its digits so it never becomes a number. */
  function roundedAmount(amount: string): string {
    const [whole = "0", fraction = ""] = amount.split(".");
    const padded = fraction.padEnd(4, "0");
    const cents = BigInt(`${whole}${padded.slice(0, 2)}`) + (padded.charCodeAt(2) >= "5".charCodeAt(0) ? 1n : 0n);
    const text = cents.toString().padStart(3, "0");
    return `${text.slice(0, -2)}.${text.slice(-2)}`;
  }

  const itemBody = (item: PriceItemRow) => ({
    id: item.id,
    priceListId: item.priceListId,
    paymentConceptId: item.paymentConceptId,
    price: { amount: roundedAmount(item.amount), currency: item.currency },
    validFrom: item.validFrom,
    validTo: item.validTo,
    createdAt: item.createdAt,
  });

  /** The day before an ISO date, worked on the calendar and never on the clock of the machine. */
  function dayBefore(isoDate: string): string {
    const [year = 0, month = 1, day = 1] = isoDate.split("-").map(Number);
    return new Date(Date.UTC(year, month - 1, day - 1)).toISOString().slice(0, 10);
  }

  /** Whether two validities share a day: the ends are inclusive and an open end is unbounded. ISO dates order as text. */
  const overlaps = (a: { validFrom: string; validTo: string | null }, b: { validFrom: string; validTo: string | null }): boolean =>
    (b.validTo === null || a.validFrom <= b.validTo) && (a.validTo === null || b.validFrom <= a.validTo);

  function priceReadFailure(): ModelResponse | undefined {
    if (options.reads !== "failing-first") return undefined;
    priceReads += 1;
    return priceReads <= FAILING_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  }

  function schemaFailure(issues: readonly { path: PropertyKey[]; code: string }[]): ModelResponse {
    const errors = issues.map((issue) => ({
      field: issue.path.map(String).join(".") || "body",
      reason: issue.code === "invalid_format" ? "pattern" : "invalid",
    }));
    return invalid(errors.length === 0 ? [{ field: "body", reason: "invalid" }] : errors);
  }

  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

  function listPriceLists(query: URLSearchParams): ModelResponse {
    const failed = priceReadFailure();
    if (failed !== undefined) return failed;
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) return invalid([{ field: "page", reason: "invalid" }]);
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) return invalid([{ field: "size", reason: "invalid" }]);
    const [field = "", direction = "asc", ...rest] = (query.get("sort") ?? "name,asc").split(",");
    if (!(PRICE_LIST_SORT_FIELDS as readonly string[]).includes(field) || (direction !== "asc" && direction !== "desc") || rest.length > 0) {
      return invalid([{ field: "sort", reason: "invalid" }]);
    }
    const year = query.get("academicYearId");
    const scope = query.get("scope");
    const status = query.get("status");
    if (year !== null && year !== "" && !UUID.test(year)) return invalid([{ field: "academicYearId", reason: "invalid" }]);
    if (scope !== null && scope !== "" && !SCOPES.includes(scope)) return invalid([{ field: "scope", reason: "invalid" }]);
    if (status !== null && status !== "" && !STATUSES.includes(status)) return invalid([{ field: "status", reason: "invalid" }]);
    const matching = prices.lists.filter(
      (row) =>
        (year === null || year === "" || row.academicYearId === year) &&
        (scope === null || scope === "" || row.scope === scope) &&
        (status === null || status === "" || row.status === status),
    );
    const sign = direction === "asc" ? 1 : -1;
    const keyOf = (row: PriceListRow): string => (field === "name" ? row.name.toLowerCase() : row.createdAt);
    const ordered = [...matching].sort((a, b) => {
      const left = keyOf(a);
      const right = keyOf(b);
      return left < right ? -sign : left > right ? sign : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
    return wrap(priceListPage(ordered.slice(page * size, (page + 1) * size), { page, size, total: ordered.length }));
  }

  function detailOf(id: string): ModelResponse {
    const failed = priceReadFailure();
    if (failed !== undefined) return failed;
    const row = prices.lists.find((candidate) => candidate.id === id);
    if (row === undefined) return failure("resource-not-found", 404);
    return wrap(priceListDetailRecord({ ...row, items: prices.items.filter((item) => item.priceListId === id).map(itemBody) }));
  }

  function createPriceList(body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const parsed = CreatePriceListRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    const { academicYearId, name, scope, scopeRefId } = parsed.data;
    if (name.trim() === "") return invalid([{ field: "name", reason: "required" }]);
    // A modality or a grade needs its reference and the whole institution takes none, as the server judges it.
    if ((scope === "INSTITUTION") !== (scopeRefId === undefined || scopeRefId === null)) return invalid([{ field: "scopeRefId", reason: "invalid" }]);
    const stamp = now().toISOString();
    const row: PriceListRow = {
      id: crypto.randomUUID(),
      academicYearId,
      name,
      scope,
      scopeRefId: scopeRefId ?? null,
      status: "ACTIVE",
      version: 0,
      createdAt: stamp,
      updatedAt: stamp,
    };
    prices.lists.push(row);
    return wrap(priceListRecord(row, 201));
  }

  function updatePriceList(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const self = prices.lists.find((row) => row.id === id);
    if (self === undefined) return failure("resource-not-found", 404);
    const parsed = UpdatePriceListRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    if (parsed.data.name.trim() === "") return invalid([{ field: "name", reason: "required" }]);
    if (staleFirst(self) || parsed.data.version !== self.version) return failure("stale-version", 409);
    Object.assign(self, { name: parsed.data.name, status: parsed.data.status, version: self.version + 1, updatedAt: now().toISOString() });
    return wrap(priceListRecord(self, 200));
  }

  function addPriceTo(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const self = prices.lists.find((row) => row.id === id);
    if (self === undefined) return failure("resource-not-found", 404);
    const parsed = AddPriceRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    const { paymentConceptId, price, validFrom } = parsed.data;
    const validTo = parsed.data.validTo ?? null;
    // The input pattern has no sign; the domain refuses zero and an end before the start, with no field named.
    if (!/^\d{1,10}(\.\d{1,4})?$/.test(price.amount) || !/[1-9]/.test(price.amount) || !["HNL", "USD"].includes(price.currency)) return invalid([]);
    if (validTo !== null && validTo < validFrom) return invalid([]);
    if (self.status === "ARCHIVED") return failure("price-list-archived", 409);
    const concept = rows.find((row) => row.id === paymentConceptId);
    if (concept === undefined) return failure("resource-not-found", 404);
    if (!concept.isActive) return failure("inactive-reference", 409);
    const sameConcept = prices.items.filter((item) => item.priceListId === id && item.paymentConceptId === paymentConceptId);
    // The one case the server resolves by itself: an open validity that starts earlier is closed the day before the new one.
    const open = sameConcept.find((item) => item.validTo === null);
    const closing = open !== undefined && validTo === null && open.validFrom < validFrom ? open : undefined;
    if (sameConcept.some((item) => item !== closing && overlaps({ validFrom, validTo }, item))) return failure("price-validity-overlap", 409);
    if (closing !== undefined) closing.validTo = dayBefore(validFrom);
    const item: PriceItemRow = {
      id: crypto.randomUUID(),
      priceListId: id,
      paymentConceptId,
      amount: price.amount,
      currency: price.currency as "HNL" | "USD",
      validFrom,
      validTo,
      createdAt: now().toISOString(),
    };
    prices.items.push(item);
    return wrap(priceListItemRecord(itemBody(item)));
  }

  function handlePriceLists(method: string, id: string | undefined, items: boolean, query: URLSearchParams, body: unknown): ModelResponse | undefined {
    if (id === undefined) {
      if (method === "GET") return listPriceLists(query);
      if (method === "POST") return createPriceList(body);
      return undefined;
    }
    if (items) return method === "POST" ? addPriceTo(id, body) : undefined;
    if (method === "GET") return detailOf(id);
    if (method === "PUT") return updatePriceList(id, body);
    return undefined;
  }

  return {
    rows: () => rows,
    lists: () => prices.lists,
    items: () => prices.items,
    handle({ method, path, query, body }) {
      const priceMatch = /^\/api\/v1\/catalog\/price-lists(?:\/([^/]+)(\/items)?)?$/.exec(path);
      if (priceMatch !== null) return handlePriceLists(method, priceMatch[1], priceMatch[2] !== undefined, query, body);
      const match = /^\/api\/v1\/catalog\/payment-concepts(?:\/([^/]+))?$/.exec(path);
      if (match === null) return undefined;
      const id = match[1];
      if (id === undefined && method === "GET") return list(query);
      if (id === undefined && method === "POST") return write(undefined, body);
      if (id !== undefined && method === "PUT") return write(id, body);
      return undefined;
    },
  };
}

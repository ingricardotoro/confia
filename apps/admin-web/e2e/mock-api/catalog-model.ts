import {
  AddPriceRequest,
  AddTaxRatePeriodRequest,
  CreatePaymentConceptRequest,
  CreatePriceListRequest,
  CreateTaxRateRequest,
  UpdatePaymentConceptRequest,
  UpdatePriceListRequest,
  UpdateTaxRateRequest,
} from "@confia/contracts/admin";
import {
  paymentConceptPage,
  paymentConceptRecord,
  priceListDetailRecord,
  priceListItemRecord,
  priceListPage,
  priceListRecord,
  problem,
  taxRateDetailRecord,
  taxRatePage,
  taxRatePeriodRecord,
  taxRateRecord,
  type ContractResponse,
} from "../../src/test/contract-responses.ts";
import {
  emptyPriceLists,
  emptyRows,
  emptyTaxRates,
  seededPriceLists,
  seededRows,
  seededTaxRates,
  type PaymentConceptRow,
  type PriceItemRow,
  type PriceListRow,
  type TaxPeriodRow,
  type TaxRateRow,
} from "./catalog-seed.ts";
import type { ModelResponse } from "./session-model.ts";

/**
 * The catalog of the simulated API: the three operations of `/api/v1/catalog/payment-concepts` (catalog-payment-concepts
 * cut), the five of `/api/v1/catalog/price-lists` (catalog-price-lists cut) and the five of `/api/v1/catalog/tax-rates`
 * (catalog-tax-rates cut), over rows kept in memory for one scenario. It is a thin simulation of the published shapes and of only the rules a journey needs (a code that repeats
 * whatever its capitals, a code that cannot be sent in an edit, a stale version, a price validity that overlaps or closes
 * the open one, an archived list, a record that does not exist, and a refused write). The real rules, and their limits,
 * are tested in the backend. Paging, order and filters are done here as the server does them; the page never does them.
 * An amount is kept and answered as the text it was sent in (at most two decimals, as the server takes it): the
 * simulator, like the page, never turns an amount into a number and never rounds one.
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
  /** The tax rates the scenario starts with. */
  readonly taxRates: "empty" | "seeded";
  /**
   * `forbidden`: a read answers, every write is refused with 403 and changes nothing. `stale-first`: the first edit of a
   * concept or of a list answers 409 `stale-version`, as if someone else had saved it first (the record's version moves on).
   */
  readonly writes: "allowed" | "forbidden" | "stale-first";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_CATALOG: CatalogOptions = { seed: "seeded", lists: "seeded", taxRates: "seeded", writes: "allowed", reads: "ok" };

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
  taxRates(): readonly TaxRateRow[];
  taxPeriods(): readonly TaxPeriodRow[];
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });

const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse =>
  wrap(problem(code, status, options));

const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

export function createCatalogModel(options: CatalogOptions = DEFAULT_CATALOG, now: () => Date = () => new Date()): CatalogModel {
  const rows = options.seed === "seeded" ? seededRows() : emptyRows();
  const prices = options.lists === "seeded" ? seededPriceLists() : emptyPriceLists();
  const taxes = options.taxRates === "seeded" ? seededTaxRates() : emptyTaxRates();
  // Without rates no concept can point at one, so a scenario with none starts with the concepts that hold none.
  if (options.taxRates === "empty") for (const row of rows) row.taxRateId = null;
  let reads = 0;
  let priceReads = 0;
  let taxReads = 0;
  /** The `stale-first` write has been served: the next edits meet the version the record holds. */
  let staleServed = false;

  /** The first edit of the `stale-first` scenario: someone else "saved" first, so the record moves on and the edit is refused. */
  function staleFirst(record: { version: number }): boolean {
    if (options.writes !== "stale-first" || staleServed) return false;
    staleServed = true;
    record.version += 1;
    return true;
  }

  /** A concept as the server answers it: the rate it holds, with its code and its name. */
  function conceptBody(row: PaymentConceptRow) {
    const rate = row.taxRateId === null ? undefined : taxes.rates.find((candidate) => candidate.id === row.taxRateId);
    return { ...row, taxRateCode: rate?.code ?? null, taxRateName: rate?.name ?? null };
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
    return wrap(paymentConceptPage(ordered.slice(page * size, (page + 1) * size).map(conceptBody), { page, size, total: ordered.length }));
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
    // A taxable concept names a rate and one that is not takes none (400); the rate must exist (404) and be active unless the concept already holds it (409).
    const isTaxable = values["isTaxable"] === true;
    const rateRef = typeof values["taxRateId"] === "string" ? values["taxRateId"] : null;
    if (isTaxable && rateRef === null) return invalid([{ field: "taxRateId", reason: "required" }]);
    if (!isTaxable && rateRef !== null) return invalid([{ field: "taxRateId", reason: "not-allowed" }]);
    if (rateRef !== null) {
      const rate = taxes.rates.find((candidate) => candidate.id === rateRef);
      if (rate === undefined) return failure("resource-not-found", 404);
      if (!rate.isActive && self?.taxRateId !== rateRef) return failure("inactive-reference", 409);
    }
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
        taxRateId: rateRef,
        version: 0,
        createdAt: stamp,
        updatedAt: stamp,
      } as PaymentConceptRow;
      rows.push(row);
      return wrap(paymentConceptRecord(conceptBody(row), 201));
    }
    // A PUT carries the version it was read at: another one means the concept changed since, and nothing is saved.
    if (staleFirst(self) || values["version"] !== self.version) return failure("stale-version", 409);
    // A PUT replaces the editable fields; the code, fixed at creation, is not in the body, and the version is the server's.
    const editable = Object.fromEntries(Object.entries(values).filter(([name]) => name !== "version"));
    Object.assign(self, editable, { taxRateId: rateRef, version: self.version + 1, updatedAt: stamp });
    return wrap(paymentConceptRecord(conceptBody(self), 200));
  }

  // ---- The price lists (catalog-price-lists cut) ----

  const itemBody = (item: PriceItemRow) => ({
    id: item.id,
    priceListId: item.priceListId,
    paymentConceptId: item.paymentConceptId,
    price: { amount: item.amount, currency: item.currency },
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
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(price.amount) || !/[1-9]/.test(price.amount) || !["HNL", "USD"].includes(price.currency)) return invalid([]);
    // A day of a validity is between 2000-01-01 and 2100-12-31, as the server judges it, naming the field.
    const outside = (day: string): boolean => day < "2000-01-01" || day > "2100-12-31";
    const days = [
      ...(outside(validFrom) ? [{ field: "validFrom", reason: "validity-day" }] : []),
      ...(validTo !== null && outside(validTo) ? [{ field: "validTo", reason: "validity-day" }] : []),
    ];
    if (days.length > 0) return invalid(days);
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
      currency: price.currency,
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

  // ---- The tax rates (catalog-tax-rates cut) ----

  const periodBody = (period: TaxPeriodRow) => ({
    id: period.id,
    taxRateId: period.taxRateId,
    ratePercent: period.ratePercent,
    validFrom: period.validFrom,
    validTo: period.validTo,
    createdAt: period.createdAt,
  });

  /**
   * A percentage as the server stores and answers it: the text it was sent in, at four decimals. It is padded as text
   * and never turned into a number, so nothing is rounded: the pattern already refused a fifth decimal.
   */
  function fourDecimals(text: string): string {
    const [whole = "", decimals = ""] = text.split(".");
    return `${whole.replace(/^0+(?=\d)/, "")}.${decimals.padEnd(4, "0")}`;
  }

  /** Whether a percentage that matches the pattern is above zero and at most 100, judged on its characters. */
  function rateInRange(text: string): boolean {
    const [whole = "", decimals = ""] = text.split(".");
    if (!/[1-9]/.test(text)) return false;
    const integer = whole.replace(/^0+(?=\d)/, "");
    if (integer.length > 3) return false;
    return integer.length < 3 || integer < "100" || (integer === "100" && !/[1-9]/.test(decimals));
  }

  function taxReadFailure(): ModelResponse | undefined {
    if (options.reads !== "failing-first") return undefined;
    taxReads += 1;
    return taxReads <= FAILING_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  }

  function listTaxRates(query: URLSearchParams): ModelResponse {
    const failed = taxReadFailure();
    if (failed !== undefined) return failed;
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) return invalid([{ field: "page", reason: "invalid" }]);
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) return invalid([{ field: "size", reason: "invalid" }]);
    const [field = "", direction = "asc", ...rest] = (query.get("sort") ?? "code,asc").split(",");
    if (!(SORT_FIELDS as readonly string[]).includes(field) || (direction !== "asc" && direction !== "desc") || rest.length > 0) {
      return invalid([{ field: "sort", reason: "invalid" }]);
    }
    const active = query.get("active");
    if (active !== null && active !== "" && active !== "true" && active !== "false") return invalid([{ field: "active", reason: "invalid" }]);
    const matching = taxes.rates.filter((row) => active === null || active === "" || String(row.isActive) === active);
    const sign = direction === "asc" ? 1 : -1;
    const keyOf = (row: TaxRateRow): string => (field === "code" ? row.code : row.name).toLowerCase();
    const ordered = [...matching].sort((a, b) => {
      const left = keyOf(a);
      const right = keyOf(b);
      return left < right ? -sign : left > right ? sign : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
    return wrap(taxRatePage(ordered.slice(page * size, (page + 1) * size), { page, size, total: ordered.length }));
  }

  function taxRateDetail(id: string): ModelResponse {
    const failed = taxReadFailure();
    if (failed !== undefined) return failed;
    const row = taxes.rates.find((candidate) => candidate.id === id);
    if (row === undefined) return failure("resource-not-found", 404);
    const periods = taxes.periods.filter((period) => period.taxRateId === id).map(periodBody);
    return wrap(taxRateDetailRecord({ ...row, periods }));
  }

  function createTaxRate(body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const parsed = CreateTaxRateRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    const { code, name } = parsed.data;
    const blank = [...(code.trim() === "" ? ["code"] : []), ...(name.trim() === "" ? ["name"] : [])];
    if (blank.length > 0) return invalid(blank.map((field) => ({ field, reason: "required" })));
    if (taxes.rates.some((row) => row.code.toLowerCase() === code.toLowerCase())) return failure("duplicate-tax-rate-code", 409);
    const stamp = now().toISOString();
    const row: TaxRateRow = { id: crypto.randomUUID(), code, name, isActive: true, version: 0, createdAt: stamp, updatedAt: stamp };
    taxes.rates.push(row);
    return wrap(taxRateRecord(row, 201));
  }

  function updateTaxRate(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const self = taxes.rates.find((row) => row.id === id);
    if (self === undefined) return failure("resource-not-found", 404);
    // The code never travels in an edit: a body that brings it is refused, as the server does.
    if (typeof body === "object" && body !== null && "code" in body) return invalid([{ field: "code", reason: "not-allowed" }]);
    const parsed = UpdateTaxRateRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    if (parsed.data.name.trim() === "") return invalid([{ field: "name", reason: "required" }]);
    if (staleFirst(self) || parsed.data.version !== self.version) return failure("stale-version", 409);
    Object.assign(self, { name: parsed.data.name, isActive: parsed.data.isActive, version: self.version + 1, updatedAt: now().toISOString() });
    return wrap(taxRateRecord(self, 200));
  }

  function addTaxPeriod(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const self = taxes.rates.find((row) => row.id === id);
    if (self === undefined) return failure("resource-not-found", 404);
    const parsed = AddTaxRatePeriodRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    const { ratePercent, validFrom } = parsed.data;
    const validTo = parsed.data.validTo ?? null;
    // The input pattern has no sign; the domain refuses zero and a value above 100, and an end before the start, with no field named.
    if (!rateInRange(ratePercent)) return invalid([{ field: "ratePercent", reason: "invalid" }]);
    const outside = (day: string): boolean => day < "2000-01-01" || day > "2100-12-31";
    const days = [
      ...(outside(validFrom) ? [{ field: "validFrom", reason: "validity-day" }] : []),
      ...(validTo !== null && outside(validTo) ? [{ field: "validTo", reason: "validity-day" }] : []),
    ];
    if (days.length > 0) return invalid(days);
    if (validTo !== null && validTo < validFrom) return invalid([]);
    if (!self.isActive) return failure("inactive-reference", 409);
    const sameRate = taxes.periods.filter((period) => period.taxRateId === id);
    // The one case the server resolves by itself: an open validity that starts earlier is closed the day before the new one.
    const open = sameRate.find((period) => period.validTo === null);
    const closing = open !== undefined && validTo === null && open.validFrom < validFrom ? open : undefined;
    if (sameRate.some((period) => period !== closing && overlaps({ validFrom, validTo }, period))) return failure("tax-rate-period-overlap", 409);
    if (closing !== undefined) closing.validTo = dayBefore(validFrom);
    const period: TaxPeriodRow = {
      id: crypto.randomUUID(),
      taxRateId: id,
      ratePercent: fourDecimals(ratePercent),
      validFrom,
      validTo,
      createdAt: now().toISOString(),
    };
    taxes.periods.push(period);
    return wrap(taxRatePeriodRecord(periodBody(period)));
  }

  function handleTaxRates(method: string, id: string | undefined, periods: boolean, query: URLSearchParams, body: unknown): ModelResponse | undefined {
    if (id === undefined) {
      if (method === "GET") return listTaxRates(query);
      if (method === "POST") return createTaxRate(body);
      return undefined;
    }
    if (periods) return method === "POST" ? addTaxPeriod(id, body) : undefined;
    if (method === "GET") return taxRateDetail(id);
    if (method === "PUT") return updateTaxRate(id, body);
    return undefined;
  }

  return {
    rows: () => rows,
    lists: () => prices.lists,
    items: () => prices.items,
    taxRates: () => taxes.rates,
    taxPeriods: () => taxes.periods,
    handle({ method, path, query, body }) {
      const taxMatch = /^\/api\/v1\/catalog\/tax-rates(?:\/([^/]+)(\/periods)?)?$/.exec(path);
      if (taxMatch !== null) return handleTaxRates(method, taxMatch[1], taxMatch[2] !== undefined, query, body);
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

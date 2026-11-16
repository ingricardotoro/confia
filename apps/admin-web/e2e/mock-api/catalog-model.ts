import { CreatePaymentConceptRequest, UpdatePaymentConceptRequest } from "@confia/contracts/admin";
import { paymentConceptPage, paymentConceptRecord, problem, type ContractResponse } from "../../src/test/contract-responses.ts";
import { emptyRows, seededRows, type PaymentConceptRow } from "./catalog-seed.ts";
import type { ModelResponse } from "./session-model.ts";

/**
 * The catalog of the simulated API (catalog-payment-concepts cut): the three operations of
 * `/api/v1/catalog/payment-concepts`, over rows kept in memory for one scenario. It is a thin simulation of the
 * published shapes and of only the rules a journey needs (a code that repeats whatever its capitals, a code that cannot
 * be sent in an edit, a concept that does not exist, and a refused write). The real rules, and their limits, are tested
 * in the backend. Paging, order and filters are done here as the server does them; the page never does them.
 */
export const CATALOG_BASE = "/api/v1/catalog";
/** How many reads fail first in the `failing-first` behavior: the first try and the two retries of the page. */
export const FAILING_READS = 3;
/** A fictitious trace id, the shape the backend gives. */
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const SORT_FIELDS = ["code", "name"] as const;
const CATEGORIES: readonly string[] = ["TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"];

export interface CatalogOptions {
  /** The rows the scenario starts with. */
  readonly seed: "empty" | "seeded";
  /** `forbidden`: a read answers, every write is refused with 403 and changes nothing. */
  readonly writes: "allowed" | "forbidden";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_CATALOG: CatalogOptions = { seed: "seeded", writes: "allowed", reads: "ok" };

export interface CatalogRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  /** The parsed JSON body, or `undefined` when there was none or it was not JSON. */
  readonly body: unknown;
}

export interface CatalogModel {
  /** The answer to a request below `/api/v1/catalog`, or `undefined` when the path is not one of the three operations. */
  handle(request: CatalogRequest): ModelResponse | undefined;
  /** What the scenario holds now, for a unit test; never the page. */
  rows(): readonly PaymentConceptRow[];
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });

const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse =>
  wrap(problem(code, status, options));

const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

export function createCatalogModel(options: CatalogOptions = DEFAULT_CATALOG, now: () => Date = () => new Date()): CatalogModel {
  const rows = options.seed === "seeded" ? seededRows() : emptyRows();
  let reads = 0;

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
      const errors = parsed.error.issues.map((issue) => ({ field: issue.path.map(String).join(".") || "body", reason: "invalid" }));
      return invalid(errors.length === 0 ? [{ field: "body", reason: "invalid" }] : errors);
    }
    const values = parsed.data as Record<string, unknown>;
    const blank = ["name", "code"].filter((field) => typeof values[field] === "string" && values[field].trim() === "");
    if (blank.length > 0) return invalid(blank.map((field) => ({ field, reason: "required" })));
    const stamp = now().toISOString();
    if (self === undefined) {
      const code = String(values["code"]);
      if (rows.some((row) => row.code.toLowerCase() === code.toLowerCase())) return failure("duplicate-name", 409);
      const row = {
        id: crypto.randomUUID(),
        code,
        name: String(values["name"]),
        category: values["category"],
        isTaxable: values["isTaxable"],
        isRecurring: values["isRecurring"],
        isActive: true,
        createdAt: stamp,
        updatedAt: stamp,
      } as PaymentConceptRow;
      rows.push(row);
      return wrap(paymentConceptRecord(row, 201));
    }
    // A PUT replaces the editable fields; the code, fixed at creation, is not in the body.
    Object.assign(self, values, { updatedAt: stamp });
    return wrap(paymentConceptRecord(self, 200));
  }

  return {
    rows: () => rows,
    handle({ method, path, query, body }) {
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

import { CreateLedgerAccountRequest, SetPostingAccountsRequest, UpdateLedgerAccountRequest } from "@confia/contracts/admin";
import {
  ledgerAccountPage,
  ledgerAccountRecord,
  postingAccountMap,
  problem,
  type ContractResponse,
} from "../../src/test/contract-responses.ts";
import {
  emptyAccounts,
  seededAccounts,
  seededMap,
  type LedgerAccountRow,
  type MapSeed,
  type PostingPurpose,
  type PostingRow,
  type RevenueCategory,
} from "./ledger-seed.ts";
import type { ModelResponse } from "./session-model.ts";

/**
 * The chart of accounts and the map of posting accounts of the simulated API (ledger-chart-of-accounts cut): the three
 * operations of `/api/v1/ledger/accounts` and the two of `/api/v1/charges/posting-accounts`, over rows kept in memory for one
 * scenario. It is a thin simulation of the published shapes and of only the rules a journey needs (a code that repeats
 * whatever its capitals, a name and a state that are the only things an edit changes, a stale version, an account the map
 * still uses that cannot be deactivated, a map entry whose account does not exist, is inactive or is of the wrong type, a map
 * saved whole or not at all, and a refused write). The real rules, and their limits, are tested in the backend. Paging, order
 * and filters are done here as the server does them; the page never does them.
 */
export const LEDGER_BASE = "/api/v1/ledger";
export const POSTING_ACCOUNTS_PATH = "/api/v1/charges/posting-accounts";
/** How many reads fail first in the `failing-first` behavior: the first try and the two retries of the page. */
export const FAILING_READS = 3;
/** A fictitious trace id, the shape the backend gives. */
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const SORT_FIELDS = ["code", "name"] as const;
const TYPES: readonly string[] = ["ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE"];
const MAP_ENTRY_LIMIT = 20;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface LedgerOptions {
  /** The accounts the scenario starts with. */
  readonly seed: "empty" | "seeded";
  /** The map the scenario starts with: nothing set, the two main accounts only, or the whole map of the seeded concepts. */
  readonly map: MapSeed;
  /**
   * `forbidden`: a read answers, every write (an account or the map) is refused with 403 and changes nothing. `stale-first`: the
   * first edit of an account, or the first save of the map, answers 409 `stale-version`, as if someone else had saved first
   * (the version moves on).
   */
  readonly writes: "allowed" | "forbidden" | "stale-first";
  /** `failing-first`: the first reads of the accounts answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
  /** `failing-first`: the first reads of the map answer 500, then they recover. */
  readonly mapReads: "ok" | "failing-first";
}

export const DEFAULT_LEDGER: LedgerOptions = { seed: "seeded", map: "partial", writes: "allowed", reads: "ok", mapReads: "ok" };

export interface LedgerRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  /** The parsed JSON body, or `undefined` when there was none or it was not JSON. */
  readonly body: unknown;
}

export interface LedgerModel {
  /** The answer to a request of the chart or of the map, or `undefined` when the path is not one of its operations. */
  handle(request: LedgerRequest): ModelResponse | undefined;
  /** What the scenario holds now, for a unit test; never the page. */
  accounts(): readonly LedgerAccountRow[];
  postingRows(): readonly PostingRow[];
  /** Whether the map holds what an accrual of the seeded concepts needs: a receivable, a tax payable and a revenue for each category. */
  mapComplete(): boolean;
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });

const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse =>
  wrap(problem(code, status, options));

const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

function schemaFailure(issues: readonly { path: PropertyKey[]; code: string }[]): ModelResponse {
  // The server names a value that breaks a pattern (the code whitelist, the name without control characters) `pattern`.
  const errors = issues.map((issue) => ({
    field: issue.path.map(String).join(".") || "body",
    reason: issue.code === "invalid_format" ? "pattern" : "invalid",
  }));
  return invalid(errors.length === 0 ? [{ field: "body", reason: "invalid" }] : errors);
}

/** The type of account each purpose needs, the rule the server applies to the map. */
function fits(purpose: PostingPurpose, account: LedgerAccountRow): boolean {
  switch (purpose) {
    case "RECEIVABLE":
      return account.type === "ASSET" && account.holdsStudentBalances;
    case "TAX_PAYABLE":
      return account.type === "LIABILITY" && !account.holdsStudentBalances;
    case "REVENUE":
      return account.type === "INCOME" && !account.holdsStudentBalances;
    // Becas y descuentos: a contra-revenue (income) or an expense account that holds no student balance, until the accountant answers.
    case "BENEFITS":
      return (account.type === "INCOME" || account.type === "EXPENSE") && !account.holdsStudentBalances;
  }
}

const CATEGORIES: readonly RevenueCategory[] = ["TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"];

export function createLedgerModel(options: LedgerOptions = DEFAULT_LEDGER, now: () => Date = () => new Date()): LedgerModel {
  const rows = options.seed === "seeded" ? seededAccounts() : emptyAccounts();
  // A map points at accounts of the chart: a scenario without accounts starts with a map that holds none.
  const posting = options.seed === "seeded" ? seededMap(options.map) : [];
  let reads = 0;
  let mapReads = 0;
  /** The `stale-first` write has been served: the next edits meet the version the record holds. */
  let staleServed = false;

  /** The first write of the `stale-first` scenario: someone else "saved" first, so the version moves on and the write is refused. */
  function staleFirst(record?: { version: number }): boolean {
    if (options.writes !== "stale-first" || staleServed) return false;
    staleServed = true;
    if (record !== undefined) record.version += 1;
    else for (const row of posting) row.version += 1;
    return true;
  }

  function mapComplete(): boolean {
    const set = (purpose: PostingPurpose, category: RevenueCategory | null) =>
      posting.some((row) => row.purpose === purpose && row.conceptCategory === category);
    return set("RECEIVABLE", null) && set("TAX_PAYABLE", null) && (set("REVENUE", null) || CATEGORIES.every((category) => set("REVENUE", category)));
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
    const type = query.get("type");
    if (type !== null && type !== "" && !TYPES.includes(type)) return invalid([{ field: "type", reason: "invalid" }]);
    const active = query.get("active");
    if (active !== null && active !== "" && active !== "true" && active !== "false") return invalid([{ field: "active", reason: "invalid" }]);
    const matching = rows.filter(
      (row) => (type === null || type === "" || row.type === type) && (active === null || active === "" || String(row.active) === active),
    );
    const sign = direction === "asc" ? 1 : -1;
    // The code is ordered without telling capitals apart, and the tie breaks on the id, so two requests see the same rows.
    const keyOf = (row: LedgerAccountRow): string => (field === "code" ? row.code : row.name).toLowerCase();
    const ordered = [...matching].sort((a, b) => {
      const left = keyOf(a);
      const right = keyOf(b);
      return left < right ? -sign : left > right ? sign : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
    return wrap(ledgerAccountPage(ordered.slice(page * size, (page + 1) * size), { page, size, total: ordered.length }));
  }

  function create(body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const parsed = CreateLedgerAccountRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    const { code, name, type, holdsStudentBalances } = parsed.data;
    const blank = [...(code.trim() === "" ? ["code"] : []), ...(name.trim() === "" ? ["name"] : [])];
    if (blank.length > 0) return invalid(blank.map((field) => ({ field, reason: "required" })));
    if (holdsStudentBalances && type !== "ASSET" && type !== "LIABILITY") return invalid([{ field: "holdsStudentBalances", reason: "not-allowed" }]);
    if (rows.some((row) => row.code.toLowerCase() === code.toLowerCase())) return failure("duplicate-account-code", 409);
    const stamp = now().toISOString();
    const row: LedgerAccountRow = {
      id: crypto.randomUUID(),
      code,
      name,
      type: type,
      holdsStudentBalances,
      active: true,
      version: 0,
      createdAt: stamp,
      updatedAt: stamp,
    };
    rows.push(row);
    return wrap(ledgerAccountRecord(row, 201));
  }

  function update(id: string, body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const self = rows.find((row) => row.id === id);
    if (self === undefined) return failure("resource-not-found", 404);
    // Only the version, the name and the state travel in an edit: a body that brings the code, the type or anything else is refused.
    if (typeof body === "object" && body !== null) {
      const extra = Object.keys(body).filter((key) => key !== "version" && key !== "name" && key !== "active");
      if (extra.length > 0) return invalid(extra.map((field) => ({ field, reason: "not-allowed" })));
    }
    const parsed = UpdateLedgerAccountRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    const { version, name, active } = parsed.data;
    if (name.trim() === "") return invalid([{ field: "name", reason: "required" }]);
    // The edit carries the version it was read at: another one means the account changed since, and nothing is saved.
    if (staleFirst(self) || version !== self.version) return failure("stale-version", 409);
    // An account the map still points at is not deactivated: the map is changed first.
    if (!active && self.active && posting.some((row) => row.ledgerAccountId === self.id)) return failure("account-in-use", 409);
    Object.assign(self, { name, active, version: self.version + 1, updatedAt: now().toISOString() });
    return wrap(ledgerAccountRecord(self, 200));
  }

  function readMap(): ModelResponse {
    if (options.mapReads === "failing-first") {
      mapReads += 1;
      if (mapReads <= FAILING_READS) return failure("internal-error", 500, { traceId: TRACE_ID });
    }
    return wrap(postingAccountMap(posting));
  }

  function saveMap(body: unknown): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const parsed = SetPostingAccountsRequest.safeParse(body);
    if (!parsed.success) return schemaFailure(parsed.error.issues);
    const { entries } = parsed.data;
    if (entries.length === 0 || entries.length > MAP_ENTRY_LIMIT) return invalid([{ field: "entries", reason: "size" }]);
    const keyOf = (purpose: string, category: string | null | undefined): string => `${purpose}:${category ?? ""}`;
    const keys = entries.map((entry) => keyOf(entry.purpose, entry.conceptCategory));
    if (new Set(keys).size !== keys.length) return invalid([{ field: "entries", reason: "duplicate" }]);
    // A category belongs to the revenue only.
    const misplaced = entries.findIndex((entry) => entry.conceptCategory != null && entry.purpose !== "REVENUE");
    if (misplaced >= 0) return invalid([{ field: `entries[${String(misplaced)}].conceptCategory`, reason: "not-allowed" }]);
    // Everything is checked before anything is written: a request with one good entry and one bad one saves none.
    const checked: { entry: (typeof entries)[number]; existing: PostingRow | undefined }[] = [];
    for (const entry of entries) {
      if (!UUID.test(entry.ledgerAccountId)) return invalid([{ field: "ledgerAccountId", reason: "invalid" }]);
      const account = rows.find((row) => row.id === entry.ledgerAccountId);
      if (account === undefined) return failure("resource-not-found", 404);
      if (!account.active) return failure("inactive-reference", 409);
      if (!fits(entry.purpose, account)) return failure("posting-account-invalid", 409);
      const existing = posting.find((row) => row.purpose === entry.purpose && row.conceptCategory === (entry.conceptCategory ?? null));
      checked.push({ entry, existing });
    }
    if (staleFirst()) return failure("stale-version", 409);
    // The version of the row the editor read; none when the entry was never set. Another one is a stale write.
    if (checked.some(({ entry, existing }) => (existing?.version ?? null) !== (entry.version ?? null))) return failure("stale-version", 409);
    const stamp = now().toISOString();
    for (const { entry, existing } of checked) {
      if (existing === undefined) {
        posting.push({
          id: crypto.randomUUID(),
          purpose: entry.purpose,
          conceptCategory: entry.conceptCategory ?? null,
          ledgerAccountId: entry.ledgerAccountId,
          version: 0,
          updatedAt: stamp,
        });
      } else if (existing.ledgerAccountId !== entry.ledgerAccountId) {
        // The same account sent again changes nothing; another one moves the version of the row.
        Object.assign(existing, { ledgerAccountId: entry.ledgerAccountId, version: existing.version + 1, updatedAt: stamp });
      }
    }
    return wrap(postingAccountMap(posting));
  }

  return {
    accounts: () => rows,
    postingRows: () => posting,
    mapComplete,
    handle({ method, path, query, body }) {
      if (path === POSTING_ACCOUNTS_PATH) {
        if (method === "GET") return readMap();
        if (method === "PUT") return saveMap(body);
        return undefined;
      }
      const match = /^\/api\/v1\/ledger\/accounts(?:\/([^/]+))?$/.exec(path);
      if (match === null) return undefined;
      const id = match[1];
      if (id === undefined && method === "GET") return list(query);
      if (id === undefined && method === "POST") return create(body);
      if (id !== undefined && method === "PUT") return update(id, body);
      return undefined;
    },
  };
}

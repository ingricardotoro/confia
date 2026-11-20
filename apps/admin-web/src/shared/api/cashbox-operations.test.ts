import { describe, expect, it } from "vitest";
import { cashSession, cashSessionPage, cashSessionRow, problem, recordedMovement } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import {
  approveCashSessionClose,
  closeCashSession,
  getCashSession,
  getCurrentCashSession,
  listCashSessions,
  openCashSession,
  recordCashMovement,
} from "./cashbox-operations.ts";

// The seven operations of /api/v1/cashbox over the real transport and the test server: the path, the query, the bearer token, the body and
// the Idempotency-Key of each, and the schema each answer must meet. The four writes move cash, so each one sends its key.
installMswServer();

const BASE = "/api/v1/cashbox/sessions";
const ID = "00000000-0000-4000-8000-0000000c0001";
const KEY = "5b0f0a52-6d2c-4a4e-9a0a-6a1f3f1f0b01";

describe("the reads", () => {
  it("lists with page, size, sort and the filters as query parameters, and leaves out a filter that is not set", async () => {
    route("get", BASE, cashSessionPage([cashSessionRow()]));

    const result = await listCashSessions("tok-1", { page: 1, size: 25, sort: "openedAt,desc", status: "pending_approval", from: "2026-11-01", to: undefined });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "1", size: "25", sort: "openedAt,desc", status: "pending_approval", from: "2026-11-01" });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("reads the current session and one session by id, dropping the nulls the server writes for an absent member", async () => {
    const current = cashSession();
    route("get", `${BASE}/current`, { ...current, body: { ...(current.body as object), close: null, approval: null } });
    route("get", `${BASE}/${ID}`, cashSession({ id: ID }));

    const now = await getCurrentCashSession("tok");
    const detail = await getCashSession("tok", ID);

    expect(now.kind === "ok" && now.data.close).toBeUndefined();
    expect(detail.kind === "ok" && detail.data.id).toBe(ID);
  });

  it("gives a missing current session as a problem with its status", async () => {
    route("get", `${BASE}/current`, problem("resource-not-found", 404));

    expect(await getCurrentCashSession("tok")).toMatchObject({ kind: "problem", problem: { status: 404, code: "resource-not-found" } });
  });

  it("answers a page that breaks the contract as an unexpected response, with none of its body", async () => {
    route("get", BASE, { status: 200, body: { items: [{ id: 1 }], page: 0, size: 25, total: 1 }, contentType: "application/json" });

    expect(await listCashSessions("tok", { page: 0, size: 25 })).toEqual({ kind: "unexpected-response", status: 200 });
  });
});

describe("the writes", () => {
  it("opens a session with the float and the Idempotency-Key", async () => {
    route("post", BASE, cashSession({}, 201));

    const result = await openCashSession("tok-2", { openingFloat: { amount: "500.00", currency: "HNL" } }, KEY);

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.method).toBe("POST");
    expect(sent?.headers.get("Idempotency-Key")).toBe(KEY);
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-2");
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({ openingFloat: { amount: "500.00", currency: "HNL" } });
  });

  it("records a movement, closes and approves with their own paths, bodies and key", async () => {
    route("post", `${BASE}/${ID}/movements`, recordedMovement({}, "600.00"));
    route("post", `${BASE}/${ID}/close`, cashSession({ id: ID, status: "pending_approval" }));
    route("post", `${BASE}/${ID}/approve`, cashSession({ id: ID, status: "closed" }));

    const moved = await recordCashMovement("tok", ID, { type: "deposit", amount: { amount: "100.00", currency: "HNL" } }, KEY);
    const closed = await closeCashSession("tok", ID, { declaredAmount: { amount: "540.00", currency: "HNL" }, differenceReason: "Billetes de cien contados dos veces" }, KEY);
    const approved = await approveCashSessionClose("tok", ID, { note: "Revisado con el cajero" }, KEY);

    expect(moved.kind === "ok" && moved.data.expectedAfter.amount).toBe("600.00");
    expect(closed.kind === "ok" && closed.data.status).toBe("pending_approval");
    expect(approved.kind === "ok" && approved.data.status).toBe("closed");
    expect(requests().map((sent) => sent.path)).toEqual([`${BASE}/${ID}/movements`, `${BASE}/${ID}/close`, `${BASE}/${ID}/approve`]);
    expect(requests().every((sent) => sent.headers.get("Idempotency-Key") === KEY)).toBe(true);
  });

  it("sends the same key again after a lost answer, so the server answers the same request", async () => {
    route("post", BASE, "network-error", cashSession({}, 201));

    const first = await openCashSession("tok", { openingFloat: { amount: "0.00", currency: "HNL" } }, KEY);
    const second = await openCashSession("tok", { openingFloat: { amount: "0.00", currency: "HNL" } }, KEY);

    expect(first.kind).toBe("network");
    expect(second.kind).toBe("ok");
    expect(requests().map((sent) => sent.headers.get("Idempotency-Key"))).toEqual([KEY, KEY]);
  });

  it.each([
    ["cash-session-already-open", 409],
    ["cash-session-not-open", 409],
    ["cash-session-not-awaiting-approval", 409],
    ["cash-expected-negative", 409],
    ["cash-difference-reason-required", 400],
    ["cash-approval-same-actor", 403],
    ["idempotency-payload-mismatch", 422],
  ] as const)("gives %s as a problem with its code", async (code, status) => {
    route("post", `${BASE}/${ID}/close`, problem(code, status));

    const result = await closeCashSession("tok", ID, { declaredAmount: { amount: "1.00", currency: "HNL" } }, KEY);

    expect(result.kind === "problem" && result.problem.code).toBe(code);
  });
});

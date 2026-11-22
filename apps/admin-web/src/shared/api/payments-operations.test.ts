import { describe, expect, it } from "vitest";
import { paymentBody, paymentPage, paymentPostingAccountMap, paymentRecord, paymentSummary, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { confirmPayment, getPayment, getPaymentPostingAccounts, listPayments, registerPayment, rejectPayment, setPaymentPostingAccounts } from "./payments-operations.ts";

// The operations of /api/v1/payments over the real transport and the test server: the path, the query, the bearer token, the body and the
// Idempotency-Key of each, and the schema each answer must meet. The three writes that move money send their key.
installMswServer();

const BASE = "/api/v1/payments";
const ID = "00000000-0000-4000-8000-0000000d0001";
const KEY = "5b0f0a52-6d2c-4a4e-9a0a-6a1f3f1f0b02";

describe("the reads", () => {
  it("lists with page, size, sort and the filters as query parameters, and leaves out a filter that is not set", async () => {
    route("get", BASE, paymentPage([paymentSummary()]));

    const result = await listPayments("tok-1", { page: 1, size: 25, sort: "receivedOn,desc", status: "declared", method: "BANK_TRANSFER", from: "2026-11-01", to: undefined });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "1", size: "25", sort: "receivedOn,desc", status: "declared", method: "BANK_TRANSFER", from: "2026-11-01" });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("reads one payment, dropping the nulls the server writes for an absent member", async () => {
    const reply = paymentRecord({ id: ID });
    route("get", `${BASE}/${ID}`, { ...reply, body: { ...(reply.body as object), decision: null, cashSessionId: null } });

    const result = await getPayment("tok", ID);

    expect(result.kind === "ok" && result.data.id).toBe(ID);
    expect(result.kind === "ok" && result.data.decision).toBeUndefined();
  });

  it("reads and replaces the map of the cash and bank accounts", async () => {
    route("get", `${BASE}/posting-accounts`, paymentPostingAccountMap([]));
    route("put", `${BASE}/posting-accounts`, paymentPostingAccountMap([]));

    const read = await getPaymentPostingAccounts("tok");
    const saved = await setPaymentPostingAccounts("tok", { entries: [{ purpose: "CASH", ledgerAccountId: ID }] });

    expect(read.kind).toBe("ok");
    expect(saved.kind).toBe("ok");
    expect(JSON.parse(requests()[1]?.body ?? "{}")).toEqual({ entries: [{ purpose: "CASH", ledgerAccountId: ID }] });
  });
});

describe("the writes", () => {
  it("registers a payment with its body and the Idempotency-Key", async () => {
    route("post", BASE, paymentRecord({}, 201));

    const result = await registerPayment("tok-2", { studentId: ID, method: "CASH", amount: { amount: "1500.00", currency: "HNL" }, receivedOn: "2026-11-20" }, KEY);

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.headers.get("Idempotency-Key")).toBe(KEY);
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-2");
    expect(JSON.parse(sent?.body ?? "{}")).toMatchObject({ method: "CASH", amount: { amount: "1500.00", currency: "HNL" } });
  });

  it("confirms and rejects with their own paths, body and key", async () => {
    route("post", `${BASE}/${ID}/confirm`, paymentRecord({ id: ID, status: "confirmed" }));
    route("post", `${BASE}/${ID}/reject`, paymentRecord({ id: ID, status: "rejected" }));

    const confirmed = await confirmPayment("tok", ID, KEY);
    const rejected = await rejectPayment("tok", ID, { reason: "La referencia no aparece en el estado del banco" }, KEY);

    expect(confirmed.kind === "ok" && confirmed.data.status).toBe("confirmed");
    expect(rejected.kind === "ok" && rejected.data.status).toBe("rejected");
    expect(requests().map((sent) => sent.path)).toEqual([`${BASE}/${ID}/confirm`, `${BASE}/${ID}/reject`]);
    expect(requests().every((sent) => sent.headers.get("Idempotency-Key") === KEY)).toBe(true);
    expect(JSON.parse(requests()[1]?.body ?? "{}")).toEqual({ reason: "La referencia no aparece en el estado del banco" });
  });

  it("sends the same key again after a lost answer", async () => {
    route("post", BASE, "network-error", paymentRecord({}, 201));
    const body = { studentId: ID, method: "CASH", amount: { amount: "10.00", currency: "HNL" }, receivedOn: "2026-11-20" } as const;

    const first = await registerPayment("tok", body, KEY);
    const second = await registerPayment("tok", body, KEY);

    expect(first.kind).toBe("network");
    expect(second.kind).toBe("ok");
    expect(requests().map((sent) => sent.headers.get("Idempotency-Key"))).toEqual([KEY, KEY]);
  });

  it.each([
    ["cash-session-not-open", 409],
    ["posting-accounts-incomplete", 409],
    ["duplicate-transfer-reference", 409],
    ["idempotency-payload-mismatch", 422],
  ] as const)("gives %s as a problem with its code", async (code, status) => {
    route("post", BASE, problem(code, status));

    const result = await registerPayment("tok", { studentId: ID, method: "CASH", amount: { amount: "1.00", currency: "HNL" }, receivedOn: "2026-11-20" }, KEY);

    expect(result.kind === "problem" && result.problem.code).toBe(code);
  });

  it("gives a page that breaks the contract as an unexpected response", async () => {
    route("get", BASE, { status: 200, body: { items: [{ id: 1 }], page: 0, size: 25, total: 1 }, contentType: "application/json" });

    expect(await listPayments("tok", { page: 0, size: 25 })).toEqual({ kind: "unexpected-response", status: 200 });
    expect(paymentBody()).toBeDefined();
  });
});

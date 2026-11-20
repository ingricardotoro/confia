import { describe, expect, it } from "vitest";
import { cashSessionId, createCashboxModel, DEFAULT_CASHBOX, FAILING_READS, ME, type CashboxModel } from "./cashbox-model.ts";

// The simulated cashbox: the list the server serves (filters, order, paging, validation), the open session of the person, the movements, the close that
// computes the difference and demands its reason, the approval that refuses the cashier, the Idempotency-Key kept per request, and the refusals the
// scenarios inject. The clock is fixed.
const NOW = () => new Date("2026-11-19T18:00:00Z");
const BASE = "/api/v1/cashbox/sessions";
const REASON = "Billete de cien contado dos veces por error";

function model(override: Partial<typeof DEFAULT_CASHBOX> = {}): CashboxModel {
  return createCashboxModel({ ...DEFAULT_CASHBOX, ...override }, NOW);
}

const send = (target: CashboxModel, method: "GET" | "POST", path: string, body?: unknown, key: string | null = "key-1", query = "") =>
  target.handle({ method, path, query: new URLSearchParams(query), body, headers: { "idempotency-key": key ?? undefined } });
const money = (amount: string) => ({ amount, currency: "HNL" });
const code = (response: { body?: unknown } | undefined): string => (response?.body as { type: string }).type.split("/").at(-1) ?? "";
// eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters -- the type is the caller's reading of the body, and a response body is `unknown`.
const body = <T,>(response: { body?: unknown } | undefined): T => response?.body as T;

interface Session {
  id: string;
  status: string;
  expectedAmount: { amount: string };
  movements: { type: string }[];
  close?: { declaredAmount: { amount: string }; differenceAmount: { amount: string }; differenceReason?: string };
}

function openWith(target: CashboxModel, float = "500.00", key = "open-1"): Session {
  return body<Session>(send(target, "POST", BASE, { openingFloat: money(float) }, key));
}

describe("the list", () => {
  it("serves the seeded sessions newest first with the amounts as text, none for a session still open, and nothing for an empty scenario", () => {
    const page = body<{ items: { id: string; status: string; differenceAmount?: { amount: string } }[]; total: number }>(send(model(), "GET", BASE));

    expect(page.total).toBe(2);
    expect(page.items.map((row) => row.status)).toEqual(["pending_approval", "closed"]);
    expect(page.items[0]?.differenceAmount).toEqual(money("-10.00"));
    expect(body<{ total: number }>(send(model({ seed: "none" }), "GET", BASE)).total).toBe(0);
  });

  it("filters by status, orders and pages on the server, and refuses a query that does not hold", () => {
    const target = model();

    expect(body<{ items: unknown[] }>(send(target, "GET", BASE, undefined, null, "status=closed")).items).toHaveLength(1);
    expect(body<{ items: { status: string }[] }>(send(target, "GET", BASE, undefined, null, "sort=status,asc")).items.map((row) => row.status)).toEqual(["closed", "pending_approval"]);
    expect(body<{ items: unknown[]; total: number }>(send(target, "GET", BASE, undefined, null, "size=1&page=1"))).toMatchObject({ total: 2, items: [{}] });
    expect(send(target, "GET", BASE, undefined, null, "status=nonsense")?.status).toBe(400);
    expect(send(target, "GET", BASE, undefined, null, "size=500")?.status).toBe(400);
  });
});

describe("opening", () => {
  it("opens the session of the person with the float, and the current session reads it back, with a 404 before", () => {
    const target = model();
    expect(send(target, "GET", `${BASE}/current`)?.status).toBe(404);

    const opened = openWith(target);

    expect(opened.status).toBe("open");
    expect(opened.expectedAmount).toEqual(money("500.00"));
    expect(body<Session>(send(target, "GET", `${BASE}/current`)).id).toBe(opened.id);
  });

  it("refuses a second session, a float that does not hold, and a request with no key", () => {
    const target = model();
    openWith(target);

    expect(code(send(target, "POST", BASE, { openingFloat: money("100.00") }, "open-2"))).toBe("cash-session-already-open");
    expect(send(model(), "POST", BASE, { openingFloat: money("12.345") })?.status).toBe(400);
    expect(code(send(model(), "POST", BASE, { openingFloat: money("1.00") }, null))).toBe("idempotency-key-missing");
  });

  it("answers the same key and body with the same session, and another body with the same key as a mismatch", () => {
    const target = model();
    const first = openWith(target, "500.00", "k");
    const again = openWith(target, "500.00", "k");

    expect(again.id).toBe(first.id);
    expect(target.writes()).toHaveLength(1);
    expect(code(send(target, "POST", BASE, { openingFloat: money("700.00") }, "k"))).toBe("idempotency-payload-mismatch");
  });
});

describe("the movements and the close", () => {
  it("computes the expected cash on the server and refuses a withdrawal it cannot cover", () => {
    const target = model();
    const { id } = openWith(target);

    const deposit = send(target, "POST", `${BASE}/${id}/movements`, { type: "deposit", amount: money("100.00") }, "m1");
    const withdrawal = send(target, "POST", `${BASE}/${id}/movements`, { type: "withdrawal", amount: money("50.00") }, "m2");

    expect(deposit?.status).toBe(201);
    expect(body<{ expectedAfter: unknown }>(withdrawal).expectedAfter).toEqual(money("550.00"));
    expect(code(send(target, "POST", `${BASE}/${id}/movements`, { type: "withdrawal", amount: money("9999.00") }, "m3"))).toBe("cash-expected-negative");
  });

  it("requires the note of an adjustment and an amount above zero", () => {
    const target = model();
    const { id } = openWith(target);

    expect(send(target, "POST", `${BASE}/${id}/movements`, { type: "adjustment", amount: money("5.00") }, "m1")?.status).toBe(400);
    expect(send(target, "POST", `${BASE}/${id}/movements`, { type: "deposit", amount: money("0.00") }, "m2")?.status).toBe(400);
  });

  it("closes without difference as closed, and with a difference as pending approval, asking for the reason first", () => {
    const target = model();
    const { id } = openWith(target);
    send(target, "POST", `${BASE}/${id}/movements`, { type: "deposit", amount: money("100.00") }, "m1");
    send(target, "POST", `${BASE}/${id}/movements`, { type: "withdrawal", amount: money("50.00") }, "m2");

    expect(code(send(target, "POST", `${BASE}/${id}/close`, { declaredAmount: money("540.00") }, "c1"))).toBe("cash-difference-reason-required");
    const closed = body<Session>(send(target, "POST", `${BASE}/${id}/close`, { declaredAmount: money("540.00"), differenceReason: REASON }, "c2"));

    expect(closed.status).toBe("pending_approval");
    expect(closed.expectedAmount).toEqual(money("550.00"));
    expect(closed.close?.differenceAmount).toEqual(money("-10.00"));
    expect(code(send(target, "POST", `${BASE}/${id}/movements`, { type: "deposit", amount: money("1.00") }, "m3"))).toBe("cash-session-not-open");

    const exact = model();
    const other = openWith(exact);
    expect(body<Session>(send(exact, "POST", `${BASE}/${other.id}/close`, { declaredAmount: money("500.00") }, "c1")).status).toBe("closed");
  });
});

describe("the approval", () => {
  it("approves a close another cashier made, refuses the cashier of the session and a session that awaits nothing", () => {
    const target = model();
    const pending = cashSessionId(2);

    expect(code(send(target, "POST", `${BASE}/${cashSessionId(3)}/approve`, { note: "Revisado" }, "a0"))).toBe("cash-session-not-awaiting-approval");
    expect(body<Session>(send(target, "POST", `${BASE}/${pending}/approve`, { note: "Revisado con el cajero" }, "a1")).status).toBe("closed");

    const { id } = openWith(target);
    send(target, "POST", `${BASE}/${id}/close`, { declaredAmount: money("400.00"), differenceReason: REASON }, "c1");
    expect(code(send(target, "POST", `${BASE}/${id}/approve`, { note: "Revisado" }, "a2"))).toBe("cash-approval-same-actor");
    expect(ME).toBe("00000000-0000-4000-8000-0000000000a1");
  });
});

describe("the refusals the scenarios inject", () => {
  it("answers 403 to everything when the person has no permission", () => {
    const target = model({ access: "forbidden" });

    expect(code(send(target, "GET", BASE))).toBe("forbidden");
    expect(code(send(target, "GET", `${BASE}/current`))).toBe("forbidden");
    expect(code(send(target, "POST", BASE, { openingFloat: money("1.00") }))).toBe("forbidden");
  });

  it("fails the first reads with a 500 and then recovers", () => {
    const target = model({ reads: "failing-first" });

    for (let n = 0; n < FAILING_READS; n += 1) expect(send(target, "GET", BASE)?.status).toBe(500);
    expect(send(target, "GET", BASE)?.status).toBe(200);
  });

  it("holds an open session of the person in the mine-open scenario, with its expected cash", () => {
    const target = model({ seed: "mine-open" });

    expect(body<Session>(send(target, "GET", `${BASE}/current`))).toMatchObject({ status: "open", expectedAmount: money("550.00") });
  });

  it("answers nothing for a path that is not the cashbox's", () => {
    expect(send(model(), "GET", "/api/v1/other")).toBeUndefined();
  });
});

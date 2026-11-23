import { describe, expect, it } from "vitest";
import { createCashboxModel, DEFAULT_CASHBOX, type CashboxModel } from "./cashbox-model.ts";
import { createChargeVoidModel, DEFAULT_CHARGE_VOID } from "./charge-void-model.ts";
import { createPaymentsModel, DEFAULT_PAYMENTS, FAILING_READS, paymentId, type PaymentsModel } from "./payments-model.ts";
import { createStatementModel, DEFAULT_STATEMENT } from "./statement-model.ts";
import { createStudentsModel } from "./students-model.ts";

// The simulated payments: cash that needs an open cashbox and is allocated oldest first, the transfer that waits for another person, the refusals the
// real server gives (self-approval, duplicate reference, incomplete accounts), the Idempotency-Key kept per request, and the list the server serves.
// The clock is fixed. Student 1 owes three charges: 1150.00 (Sep), 1207.50 (Oct) and 1265.00 (Nov).
const NOW = () => new Date("2026-11-20T18:00:00Z");
const BASE = "/api/v1/payments";
const STUDENT = "00000000-0000-4000-8000-000000005001";
const REASON = "La referencia no aparece en el estado del banco";

interface Built {
  readonly payments: PaymentsModel;
  readonly cashbox: CashboxModel;
}

function build(override: Partial<typeof DEFAULT_PAYMENTS> = {}, cashbox: Partial<typeof DEFAULT_CASHBOX> = { seed: "mine-open" }): Built {
  const students = createStudentsModel();
  const box = createCashboxModel({ ...DEFAULT_CASHBOX, ...cashbox }, NOW);
  const payments = createPaymentsModel(
    { ...DEFAULT_PAYMENTS, ...override },
    { students, charges: createChargeVoidModel(DEFAULT_CHARGE_VOID, { students }, NOW), statement: createStatementModel(DEFAULT_STATEMENT, { students }, NOW), cashbox: box },
    NOW,
  );
  return { payments, cashbox: box };
}

const send = (target: PaymentsModel, method: "GET" | "POST" | "PUT", path: string, body?: unknown, key: string | null = "key-1", query = "") =>
  target.handle({ method, path, query: new URLSearchParams(query), body, headers: { "idempotency-key": key ?? undefined } });
const money = (amount: string) => ({ amount, currency: "HNL" });
const code = (response: { body?: unknown } | undefined): string => (response?.body as { type: string }).type.split("/").at(-1) ?? "";
const body = (response: { body?: unknown } | undefined): unknown => response?.body;
const cash = (amount: string, extra: object = {}) => ({ studentId: STUDENT, method: "CASH", amount: money(amount), receivedOn: "2026-11-20", ...extra });
const transfer = (amount: string, reference = "BA-777777", extra: object = {}) =>
  ({ studentId: STUDENT, method: "BANK_TRANSFER", amount: money(amount), receivedOn: "2026-11-20", reference, bankName: "Banco Ficohsa", ...extra });

interface PaymentBody {
  id: string;
  status: string;
  allocated: { amount: string };
  unallocated: { amount: string };
  allocations: { periodLabel: string; amount: { amount: string } }[];
  decision?: { kind: string; reason?: string };
}

describe("cash", () => {
  it("is received today in the institution time zone and no other day (400 cash-received-today under receivedOn)", () => {
    const { payments } = build();
    const response = send(payments, "POST", BASE, cash("100.00", { receivedOn: "2026-11-19" }));

    expect(response?.status).toBe(400);
    expect((body(response) as { errors: { field: string; reason: string }[] }).errors[0]).toEqual({ field: "receivedOn", reason: "cash-received-today" });
    expect(send(payments, "POST", BASE, cash("100.00"), "key-2")?.status).toBe(201);
    // A transfer keeps the day it was received.
    expect(send(payments, "POST", BASE, transfer("100.00", "BA-123456", { receivedOn: "2026-11-19" }), "key-3")?.status).toBe(201);
  });

  it("is confirmed at once and allocated oldest due date first without exceeding a charge, with what the server left in favor", () => {
    const { payments } = build();
    const response = send(payments, "POST", BASE, cash("1500.00"));

    expect(response?.status).toBe(201);
    const payment = body(response) as PaymentBody;
    expect(payment.status).toBe("confirmed");
    expect(payment.allocations.map((allocation) => [allocation.periodLabel, allocation.amount.amount])).toEqual([
      ["2026-09", "1150.00"],
      ["2026-10", "350.00"],
    ]);
    expect(payment.allocated.amount).toBe("1500.00");
    expect(payment.unallocated.amount).toBe("0.00");
  });

  it("leaves in favor of the student what is left after every pending charge was covered", () => {
    const { payments } = build();
    const payment = body(send(payments, "POST", BASE, cash("5000.00"))) as PaymentBody;

    expect(payment.allocations).toHaveLength(3);
    expect(payment.allocated.amount).toBe("3622.50");
    expect(payment.unallocated.amount).toBe("1377.50");
  });

  it("is refused with no open cashbox, and raises the expected cash of the open one", () => {
    const refused = build({}, { seed: "mixed" });
    expect(code(send(refused.payments, "POST", BASE, cash("100.00")))).toBe("cash-session-not-open");

    const { payments, cashbox } = build();
    send(payments, "POST", BASE, cash("100.00"));
    const current = body(cashbox.handle({ method: "GET", path: "/api/v1/cashbox/sessions/current", query: new URLSearchParams(), headers: { "idempotency-key": undefined } })) as { expectedAmount: { amount: string }; movements: { type: string }[] };
    expect(current.expectedAmount.amount).toBe("650.00");
    expect(current.movements.at(-1)?.type).toBe("payment_in");
  });

  it("is refused while the cash and bank accounts are not set, until they are", () => {
    const { payments } = build({ accounts: "incomplete" });

    expect(code(send(payments, "POST", BASE, cash("100.00")))).toBe("posting-accounts-incomplete");
    expect((body(send(payments, "GET", `${BASE}/posting-accounts`)) as { items: unknown[] }).items).toHaveLength(0);
    send(payments, "PUT", `${BASE}/posting-accounts`, { entries: [] });
    expect((body(send(payments, "POST", BASE, cash("100.00", {}), "key-2")) as PaymentBody).status).toBe("confirmed");
  });

  it("refuses what does not hold, by field", () => {
    const { payments } = build();
    const fields = (payload: unknown) => ((body(send(payments, "POST", BASE, payload)) as { errors: { field: string }[] }).errors).map((error) => error.field);

    expect(fields(cash("0.00"))).toEqual(["amount.amount"]);
    expect(fields(cash("12.345"))).toEqual(["amount.amount"]);
    expect(fields(cash("10.00", { receivedOn: "2026-12-31" }))).toEqual(["receivedOn"]);
    expect(fields(cash("10.00", { reference: "AB1234" }))).toEqual(["reference"]);
    expect(fields(transfer("10.00", "AB1"))).toEqual(["reference"]);
    expect(send(payments, "POST", BASE, cash("10.00"), null)?.status).toBe(400);
  });
});

describe("a declared transfer", () => {
  it("is declared with nothing allocated, and a repeated bank and reference is refused unless it was rejected", () => {
    const { payments } = build();
    const declared = body(send(payments, "POST", BASE, transfer("500.00"))) as PaymentBody;

    expect(declared.status).toBe("declared");
    expect(declared.allocations).toEqual([]);
    expect(code(send(payments, "POST", BASE, transfer("500.00"), "key-2"))).toBe("duplicate-transfer-reference");
    expect(code(send(payments, "POST", BASE, transfer("500.00", "BA-001234", { bankName: "banco atlántida" }), "key-3"))).toBe("duplicate-transfer-reference");
  });

  it("is confirmed by another person, which posts and allocates it, and cannot be confirmed twice", () => {
    const { payments } = build();
    const response = send(payments, "POST", `${BASE}/${paymentId(1)}/confirm`);

    const confirmed = body(response) as PaymentBody;
    expect(confirmed.status).toBe("confirmed");
    expect(confirmed.decision?.kind).toBe("confirmed");
    expect(confirmed.allocations.map((allocation) => allocation.amount.amount)).toEqual(["1150.00", "115.00"]);
    expect(code(send(payments, "POST", `${BASE}/${paymentId(1)}/confirm`, undefined, "key-2"))).toBe("payment-not-declared");
  });

  it("cannot be decided by the person who registered it, and the refusal is a 403", () => {
    const { payments } = build();

    expect(send(payments, "POST", `${BASE}/${paymentId(2)}/confirm`)?.status).toBe(403);
    expect(code(send(payments, "POST", `${BASE}/${paymentId(2)}/confirm`))).toBe("self-approval-forbidden");
    expect(code(send(payments, "POST", `${BASE}/${paymentId(2)}/reject`, { reason: REASON }, "key-2"))).toBe("self-approval-forbidden");
  });

  it("is rejected with a reason of 20 to 500 characters, which leaves nothing allocated", () => {
    const { payments } = build();

    expect(((body(send(payments, "POST", `${BASE}/${paymentId(1)}/reject`, { reason: "corto" })) as { errors: { field: string }[] }).errors)[0]?.field).toBe("reason");
    const rejected = body(send(payments, "POST", `${BASE}/${paymentId(1)}/reject`, { reason: REASON }, "key-2")) as PaymentBody;
    expect(rejected.status).toBe("rejected");
    expect(rejected.decision).toMatchObject({ kind: "rejected", reason: REASON });
    expect(rejected.allocations).toEqual([]);
  });

  it("answers the same Idempotency-Key and body with the same payment, and another body with 422", () => {
    const { payments } = build();
    const first = body(send(payments, "POST", BASE, cash("100.00"))) as PaymentBody;
    const again = body(send(payments, "POST", BASE, cash("100.00"))) as PaymentBody;

    expect(again.id).toBe(first.id);
    expect(send(payments, "POST", BASE, cash("200.00"))?.status).toBe(422);
    const list = body(send(payments, "GET", BASE)) as { total: number };
    expect(list.total).toBe(4);
  });
});

describe("the list", () => {
  it("serves the payments newest registered first, filtered by status and method, and nothing for an empty scenario", () => {
    const { payments } = build();
    const page = (query: string) => body(send(payments, "GET", BASE, undefined, null, query)) as { items: { id: string; status: string; method: string }[]; total: number };

    expect(page("").items.map((row) => row.id)).toEqual([paymentId(2), paymentId(3), paymentId(1)]);
    expect(page("status=declared").total).toBe(2);
    expect(page("method=CASH").items.map((row) => row.id)).toEqual([paymentId(3)]);
    expect(page("sort=amount,asc").items[0]?.id).toBe(paymentId(2));
    expect(page("from=2026-11-19&to=2026-11-19").total).toBe(2);
    expect((body(send(build({ seed: "none" }).payments, "GET", BASE)) as { total: number }).total).toBe(0);
    expect(send(payments, "GET", BASE, undefined, null, "status=bogus")?.status).toBe(400);
  });

  it("answers 403 for a person without the permission, and 500 on the first reads when the scenario says so", () => {
    expect(send(build({ access: "forbidden" }).payments, "GET", BASE)?.status).toBe(403);
    const { payments } = build({ reads: "failing-first" });
    const statuses = Array.from({ length: FAILING_READS + 1 }, () => send(payments, "GET", BASE)?.status);
    expect(statuses).toEqual([500, 500, 500, 200]);
  });

  it("answers a payment that is not there with 404", () => {
    expect(send(build().payments, "GET", `${BASE}/${paymentId(99)}`)?.status).toBe(404);
  });
});

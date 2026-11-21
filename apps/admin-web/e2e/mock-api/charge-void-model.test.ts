import { describe, expect, it } from "vitest";
import { createChargeVoidModel, DEFAULT_CHARGE_VOID, FAILING_READS, MANY_CHARGES_STUDENT, type ChargeVoidModel } from "./charge-void-model.ts";
import { STATEMENT_STUDENTS } from "./statement-seed.ts";
import { createStudentsModel, DEFAULT_STUDENTS } from "./students-model.ts";
import { studentId } from "./students-seed.ts";

// The simulated charges of a student, their void and the integrity check: the page the list serves (filters, order, paging, validation),
// the void (the reason, the Idempotency-Key kept per request, a repeat answering the same void, another key on a voided charge refused),
// the refusals the scenarios inject, and the runs with their discrepancies. The clock is fixed.
const NOW = () => new Date("2026-11-18T18:00:00Z");
const students = createStudentsModel(DEFAULT_STUDENTS);
const REASON = "Cargo generado dos veces por error del proceso";

function model(override: Partial<typeof DEFAULT_CHARGE_VOID> = {}): ChargeVoidModel {
  return createChargeVoidModel({ ...DEFAULT_CHARGE_VOID, ...override }, { students }, NOW);
}

interface Row { id: string; status: string; periodLabel: string; total: { amount: string; currency: string } }
interface Page { items: Row[]; page: number; size: number; total: number }
const list = (target: ChargeVoidModel, n: number | string, query = "") =>
  target.handle({
    method: "GET",
    path: `/api/v1/charges/students/${typeof n === "number" ? studentId(n) : n}/charges`,
    query: new URLSearchParams(query),
    headers: { "idempotency-key": undefined },
  });
const pageOf = (response: { body?: unknown } | undefined): Page => response?.body as Page;
const voidIt = (target: ChargeVoidModel, id: string, body: unknown, key: string | null = "key-1") =>
  target.handle({ method: "POST", path: `/api/v1/charges/${id}/void`, query: new URLSearchParams(), body, headers: { "idempotency-key": key ?? undefined } });
const read = (target: ChargeVoidModel, path: string, query = "") =>
  target.handle({ method: "GET", path, query: new URLSearchParams(query), headers: { "idempotency-key": undefined } });
const codeOf = (response: { body?: unknown } | undefined): string => (response?.body as { type: string }).type.split("/").at(-1) ?? "";

describe("the charges of a student", () => {
  it("serves the charges of a student with amounts as text, newest period first, and a student with none as an empty page", () => {
    const body = pageOf(list(model(), STATEMENT_STUDENTS.twelveMonths));

    expect(body.total).toBe(3);
    expect(body.items.map((row) => row.periodLabel)).toEqual(["2026-11", "2026-10", "2026-09"]);
    expect(body.items[0]?.total).toEqual({ amount: "1265.00", currency: "HNL" });
    expect(pageOf(list(model(), STATEMENT_STUDENTS.none)).items).toEqual([]);
  });

  it("pages and orders on the server", () => {
    const first = pageOf(list(model(), MANY_CHARGES_STUDENT, "page=0&size=25"));
    const second = pageOf(list(model(), MANY_CHARGES_STUDENT, "page=1&size=25&sort=total,asc"));

    expect([first.items.length, first.total]).toEqual([25, 30]);
    expect(second.items).toHaveLength(5);
    expect(pageOf(list(model(), MANY_CHARGES_STUDENT, "size=3&sort=total,desc")).items.every((row) => row.total.amount === "1265.00")).toBe(true);
  });

  it("refuses an unknown student, a bad parameter and a person without the permission", () => {
    expect(list(model(), "00000000-0000-4000-8000-000000009404")?.status).toBe(404);
    expect(list(model(), 1, "sort=name")?.status).toBe(400);
    expect(list(model(), 1, "status=OTHER")?.status).toBe(400);
    expect(list(model({ access: "forbidden" }), 1)?.status).toBe(403);
  });

  it("fails the first reads and then recovers", () => {
    const target = model({ reads: "failing-first" });

    for (let n = 0; n < FAILING_READS; n += 1) expect(list(target, 1)?.status).toBe(500);
    expect(list(target, 1)?.status).toBe(200);
  });
});

describe("the void of a charge", () => {
  const first = (target: ChargeVoidModel) => pageOf(list(target, 1)).items[0] as Row;

  it("voids once, derives the status from it and answers the same void to the same key and body", () => {
    const target = model();
    const charge = first(target);

    const done = voidIt(target, charge.id, { reason: `  ${REASON}  ` });
    const again = voidIt(target, charge.id, { reason: REASON });

    expect(done?.status).toBe(200);
    expect(again?.body).toEqual(done?.body);
    expect(first(target).id).toBe(charge.id);
    expect(pageOf(list(target, 1, "status=VOIDED")).items.map((row) => row.id)).toEqual([charge.id]);
    expect(target.voids()).toEqual([{ chargeId: charge.id, reason: REASON, idempotencyKey: "key-1" }]);
  });

  it("shows what a payment covered of a charge as paid and owed, and refuses to void a charge that has payments (409)", () => {
    const target = model();
    const [oldest, next] = target.payable(studentId(1));
    if (oldest === undefined || next === undefined) throw new Error("student 1 has charges");
    target.cover(oldest.id, 50_000);

    const row = pageOf(list(target, 1)).items.find((item) => item.id === oldest.id) as Row & { paid: { amount: string }; outstanding: { amount: string } };
    expect(row.paid.amount).toBe("500.00");
    expect(row.outstanding.amount).toBe("650.00");
    expect(target.payable(studentId(1)).find((charge) => charge.id === oldest.id)?.outstandingCents).toBe(65_000);
    expect(codeOf(voidIt(target, oldest.id, { reason: REASON }))).toBe("charge-has-payments");
    expect(voidIt(target, next.id, { reason: REASON })?.status).toBe(200);
  });

  it("lists only the charges with something outstanding in allocation order when asked, and says which are settled", () => {
    const target = model();
    const [oldest] = target.payable(studentId(1));
    if (oldest === undefined) throw new Error("student 1 has charges");
    target.cover(oldest.id, oldest.outstandingCents);

    const all = pageOf(list(target, 1)).items as (Row & { settled: boolean })[];
    expect(all.find((row) => row.id === oldest.id)?.settled).toBe(true);
    expect(all.filter((row) => row.id !== oldest.id).every((row) => !row.settled)).toBe(true);
    const owed = pageOf(list(target, 1, "outstanding=true&status=ACTIVE&page=0&size=10")).items as (Row & { dueOn: string })[];
    expect(owed.map((row) => row.id)).toEqual(target.payable(studentId(1)).map((charge) => charge.id));
    expect(owed.map((row) => row.dueOn)).toEqual([...owed.map((row) => row.dueOn)].sort());
    expect(pageOf(list(target, 1, "outstanding=true")).total).toBe(owed.length);
    expect(list(target, 1, "outstanding=maybe")?.status).toBe(400);
  });

  it("refuses the same key with another body (422) and another key on a voided charge (409)", () => {
    const target = model();
    const charge = first(target);
    voidIt(target, charge.id, { reason: REASON });

    expect(codeOf(voidIt(target, charge.id, { reason: `${REASON} y más` }))).toBe("idempotency-payload-mismatch");
    expect(codeOf(voidIt(target, charge.id, { reason: REASON }, "key-2"))).toBe("charge-already-voided");
    expect(target.voids()).toHaveLength(1);
  });

  it("refuses a missing key, a short or long reason, a line break and an unknown charge", () => {
    const target = model();
    const charge = first(target);

    expect(voidIt(target, charge.id, { reason: REASON }, null)?.status).toBe(400);
    expect(voidIt(target, charge.id, { reason: "corto" })?.status).toBe(400);
    expect(voidIt(target, charge.id, { reason: "a".repeat(501) })?.status).toBe(400);
    expect(voidIt(target, charge.id, { reason: `${REASON}\nmás` })?.status).toBe(400);
    expect(voidIt(target, "00000000-0000-4000-8000-000000000001", { reason: REASON })?.status).toBe(404);
    expect(target.voids()).toHaveLength(0);
  });

  it.each([
    ["forbidden", 403, "forbidden"],
    ["not-voidable", 409, "charge-not-voidable"],
    ["already-voided", 409, "charge-already-voided"],
  ] as const)("injects the %s refusal of its scenario", (writes, status, code) => {
    const target = model({ writes });
    const charge = first(target);

    const answer = voidIt(target, charge.id, { reason: REASON });

    expect([answer?.status, codeOf(answer)]).toEqual([status, code]);
    expect(target.voids()).toHaveLength(0);
  });

  it("reads a charge someone else voided as voided from then on", () => {
    const target = model({ writes: "already-voided" });
    const charge = first(target);
    voidIt(target, charge.id, { reason: REASON });

    expect(first(target).status).toBe("VOIDED");
  });
});

describe("the integrity check", () => {
  const RUNS = "/api/v1/charges/integrity-runs";
  interface Detail { run: { id: string; status: string }; findings: { kind: string }[] }

  it("serves clean runs newest first, the latest one and each by its id", () => {
    const target = model();
    const page = read(target, RUNS)?.body as { items: { id: string; status: string }[]; total: number };

    expect(page.total).toBe(3);
    expect(page.items.every((run) => run.status === "OK")).toBe(true);
    expect((read(target, `${RUNS}/latest`)?.body as Detail).run.id).toBe(page.items[0]?.id);
    expect((read(target, `${RUNS}/${page.items[1]?.id ?? ""}`)?.body as Detail).findings).toEqual([]);
  });

  it("serves a latest run with discrepancies, each with its kind", () => {
    const latest = read(model({ integrity: "discrepancies" }), `${RUNS}/latest`)?.body as Detail;

    expect(latest.run.status).toBe("DISCREPANCIES");
    expect(latest.findings.map((finding) => finding.kind)).toEqual(["CHARGE_POSTING_MISSING", "RECEIVABLE_MISMATCH"]);
  });

  it("answers 404 for the latest run before the check has run and for a run it does not have", () => {
    const target = model({ integrity: "none" });

    expect(read(target, `${RUNS}/latest`)?.status).toBe(404);
    expect(read(target, `${RUNS}/00000000-0000-4000-8000-000000000001`)?.status).toBe(404);
    expect((read(target, RUNS)?.body as { items: unknown[] }).items).toEqual([]);
  });

  it("does not answer a path that is not one of its operations", () => {
    expect(read(model(), "/api/v1/charges/plans")).toBeUndefined();
  });
});

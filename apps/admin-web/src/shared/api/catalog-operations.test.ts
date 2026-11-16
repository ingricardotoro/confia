import { describe, expect, it } from "vitest";
import { paymentConcept, paymentConceptPage, paymentConceptRecord, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { createPaymentConcept, listPaymentConcepts, updatePaymentConcept } from "./catalog-operations.ts";

// The three operations of /api/v1/catalog/payment-concepts over the real transport and the test server: the path,
// the query, the bearer token and the body of each, and the schema each answer must meet. None moves money, so none
// sends an Idempotency-Key.
installMswServer();

const BASE = "/api/v1/catalog/payment-concepts";
const ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";

describe("the listing", () => {
  it("sends page, size, sort and the filters as query parameters, and the bearer token, and nothing else", async () => {
    route("get", BASE, paymentConceptPage([paymentConcept()]));

    const result = await listPaymentConcepts("tok-1", { page: 1, size: 50, sort: "code,desc", category: "TUITION", active: "false" });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.method).toBe("GET");
    expect(sent?.path).toBe(BASE);
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "1", size: "50", sort: "code,desc", category: "TUITION", active: "false" });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("leaves out a filter that is not set", async () => {
    route("get", BASE, paymentConceptPage([]));

    await listPaymentConcepts("tok", { page: 0, size: 25, category: undefined, active: undefined });

    expect(requests()[0]?.search).toBe("?page=0&size=25");
  });

  it("answers a page that breaks the contract as an unexpected response, with none of its body", async () => {
    route("get", BASE, { status: 200, body: { items: [{ id: 1 }], page: 0, size: 25, total: 1 }, contentType: "application/json" });

    expect(await listPaymentConcepts("tok", { page: 0, size: 25 })).toEqual({ kind: "unexpected-response", status: 200 });
  });

  it("answers a problem as a problem, with its code", async () => {
    route("get", BASE, problem("forbidden", 403));

    expect(await listPaymentConcepts("tok", { page: 0, size: 25 })).toMatchObject({ kind: "problem", problem: { status: 403, code: "forbidden" } });
  });
});

describe("the writes", () => {
  it("POSTs the concept with its body and reads the 201", async () => {
    route("post", BASE, paymentConceptRecord(paymentConcept(), 201));

    const result = await createPaymentConcept("tok", { code: "COL", name: "Colegiatura", category: "TUITION", isRecurring: true, isTaxable: true });

    expect(result.kind === "ok" ? result.status : result.kind).toBe(201);
    expect(requests()[0]?.method).toBe("POST");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual({ code: "COL", name: "Colegiatura", category: "TUITION", isRecurring: true, isTaxable: true });
    expect(requests()[0]?.headers.get("Authorization")).toBe("Bearer tok");
    expect(requests()[0]?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("PUTs to its own id with the whole body, without a code, and reads the 200", async () => {
    route("put", `${BASE}/${ID}`, paymentConceptRecord(paymentConcept()));

    const result = await updatePaymentConcept("tok", ID, { name: "Colegiatura", category: "TUITION", isRecurring: true, isTaxable: false, isActive: false });

    expect(result.kind === "ok" ? result.status : result.kind).toBe(200);
    expect(requests()[0]?.method).toBe("PUT");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual({ name: "Colegiatura", category: "TUITION", isRecurring: true, isTaxable: false, isActive: false });
  });

  it("encodes the id in the path, so it can never change it", async () => {
    route("put", `${BASE}/:id`, paymentConceptRecord(paymentConcept()));

    await updatePaymentConcept("tok", "a/b?c", { name: "N", category: "OTHER", isRecurring: false, isTaxable: false, isActive: true });

    expect(requests()[0]?.path).toBe(`${BASE}/a%2Fb%3Fc`);
  });

  it("answers a repeated code as a problem with its code", async () => {
    route("post", BASE, problem("duplicate-name", 409));

    expect(await createPaymentConcept("tok", { code: "COL", name: "N", category: "OTHER", isRecurring: false, isTaxable: false })).toMatchObject({
      kind: "problem",
      problem: { status: 409, code: "duplicate-name" },
    });
  });
});

import { describe, expect, it } from "vitest";
import {
  paymentConcept,
  paymentConceptPage,
  paymentConceptRecord,
  priceList,
  priceListDetail,
  priceListDetailRecord,
  priceListItem,
  priceListItemRecord,
  priceListPage,
  priceListRecord,
  problem,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { addPrice, createPaymentConcept, createPriceList, getPriceList, listPaymentConcepts, listPriceLists, updatePaymentConcept, updatePriceList } from "./catalog-operations.ts";

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

  it("PUTs to its own id with the whole body and the version it was read at, without a code, and reads the 200", async () => {
    route("put", `${BASE}/${ID}`, paymentConceptRecord(paymentConcept()));

    const result = await updatePaymentConcept("tok", ID, { version: 3, name: "Colegiatura", category: "TUITION", isRecurring: true, isTaxable: false, isActive: false });

    expect(result.kind === "ok" ? result.status : result.kind).toBe(200);
    expect(requests()[0]?.method).toBe("PUT");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual({
      version: 3,
      name: "Colegiatura",
      category: "TUITION",
      isRecurring: true,
      isTaxable: false,
      isActive: false,
    });
  });

  it("encodes the id in the path, so it can never change it", async () => {
    route("put", `${BASE}/:id`, paymentConceptRecord(paymentConcept()));

    await updatePaymentConcept("tok", "a/b?c", { version: 0, name: "N", category: "OTHER", isRecurring: false, isTaxable: false, isActive: true });

    expect(requests()[0]?.path).toBe(`${BASE}/a%2Fb%3Fc`);
  });

  it("answers a stale version as a problem with its code", async () => {
    route("put", `${BASE}/${ID}`, problem("stale-version", 409));

    expect(await updatePaymentConcept("tok", ID, { version: 0, name: "N", category: "OTHER", isRecurring: false, isTaxable: false, isActive: true })).toMatchObject({
      kind: "problem",
      problem: { status: 409, code: "stale-version" },
    });
  });

  it("answers a repeated code as a problem with its code", async () => {
    route("post", BASE, problem("duplicate-payment-concept-code", 409));

    expect(await createPaymentConcept("tok", { code: "COL", name: "N", category: "OTHER", isRecurring: false, isTaxable: false })).toMatchObject({
      kind: "problem",
      problem: { status: 409, code: "duplicate-payment-concept-code" },
    });
  });
});

describe("the price lists", () => {
  const LISTS = "/api/v1/catalog/price-lists";
  const LIST_ID = "00000000-0000-4000-8000-000000000b01";
  const body = (): unknown => JSON.parse(requests()[0]?.body ?? "{}");

  it("lists with page, size, sort and the three filters as query parameters, and leaves out the ones that are not set", async () => {
    route("get", LISTS, priceListPage([priceList()]));

    const result = await listPriceLists("tok-1", { page: 2, size: 50, sort: "name,desc", academicYearId: LIST_ID, scope: "GRADE", status: "ARCHIVED" });
    await listPriceLists("tok-1", { page: 0, size: 25, scope: undefined });

    expect(result.kind).toBe("ok");
    expect(Object.fromEntries(new URLSearchParams(requests()[0]?.search))).toEqual({
      page: "2",
      size: "50",
      sort: "name,desc",
      academicYearId: LIST_ID,
      scope: "GRADE",
      status: "ARCHIVED",
    });
    expect(requests()[1]?.search).toBe("?page=0&size=25");
    expect(requests()[0]?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(requests()[0]?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("POSTs a list and reads the 201; the reference is sent only when there is one", async () => {
    route("post", LISTS, priceListRecord(priceList(), 201));

    const result = await createPriceList("tok", { academicYearId: LIST_ID, name: "2027 — Institución", scope: "INSTITUTION" });

    expect(result.kind === "ok" ? result.status : result.kind).toBe(201);
    expect(body()).toEqual({ academicYearId: LIST_ID, name: "2027 — Institución", scope: "INSTITUTION" });
    expect(requests()[0]?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("GETs one list with its prices, amounts still as text", async () => {
    route("get", `${LISTS}/${LIST_ID}`, priceListDetailRecord(priceListDetail({ items: [priceListItem({ price: { amount: "1650.00", currency: "HNL" } })] })));

    const result = await getPriceList("tok", LIST_ID);

    expect(result.kind === "ok" ? result.data.items[0]?.price : result.kind).toEqual({ amount: "1650.00", currency: "HNL" });
    expect(requests()[0]?.method).toBe("GET");
  });

  it("answers a detail that breaks the contract (an amount as a JSON number) as an unexpected response", async () => {
    route("get", `${LISTS}/${LIST_ID}`, {
      status: 200,
      body: { ...priceListDetail(), items: [{ ...priceListItem(), price: { amount: 1500, currency: "HNL" } }] },
      contentType: "application/json",
    });

    expect(await getPriceList("tok", LIST_ID)).toEqual({ kind: "unexpected-response", status: 200 });
  });

  it("PUTs the name, the status and the version to its own id, with the id encoded", async () => {
    route("put", `${LISTS}/:id`, priceListRecord(priceList({ status: "ARCHIVED", version: 2 })));

    const result = await updatePriceList("tok", "a/b?c", { version: 1, name: "2027", status: "ARCHIVED" });

    expect(result.kind).toBe("ok");
    expect(requests()[0]?.path).toBe(`${LISTS}/a%2Fb%3Fc`);
    expect(body()).toEqual({ version: 1, name: "2027", status: "ARCHIVED" });
  });

  it("POSTs a price to the items of its list with the amount as the text it was typed, and reads the 201", async () => {
    route("post", `${LISTS}/${LIST_ID}/items`, priceListItemRecord(priceListItem()));

    const result = await addPrice("tok", LIST_ID, {
      paymentConceptId: "00000000-0000-4000-8000-000000007001",
      price: { amount: "1500.00", currency: "HNL" },
      validFrom: "2027-01-01",
    });

    expect(result.kind === "ok" ? result.status : result.kind).toBe(201);
    expect(requests()[0]?.method).toBe("POST");
    expect(body()).toEqual({ paymentConceptId: "00000000-0000-4000-8000-000000007001", price: { amount: "1500.00", currency: "HNL" }, validFrom: "2027-01-01" });
    expect(requests()[0]?.headers.has("Idempotency-Key")).toBe(false);
  });

  it.each([
    ["price-validity-overlap", 409],
    ["price-list-archived", 409],
    ["stale-version", 409],
    ["inactive-reference", 409],
    ["resource-not-found", 404],
    ["validation-failed", 400],
  ] as const)("answers %s as a problem with its code", async (code, status) => {
    route("post", `${LISTS}/${LIST_ID}/items`, problem(code, status));

    expect(await addPrice("tok", LIST_ID, { paymentConceptId: LIST_ID, price: { amount: "1.00", currency: "USD" }, validFrom: "2027-01-01" })).toMatchObject({
      kind: "problem",
      problem: { status, code },
    });
  });
});

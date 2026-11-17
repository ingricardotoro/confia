import { describe, expect, it } from "vitest";
import { ledgerAccount, ledgerAccountPage, ledgerAccountRecord, postingAccount, postingAccountMap, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { createLedgerAccount, getPostingAccounts, listLedgerAccounts, setPostingAccounts, updateLedgerAccount } from "./ledger-operations.ts";

// The operations of the chart of accounts and of the map of posting accounts over the real transport and the test server: the
// path, the query, the bearer token and the body of each, and the schema each answer must meet. None moves money, so none sends
// an Idempotency-Key.
installMswServer();

const ACCOUNTS = "/api/v1/ledger/accounts";
const MAP = "/api/v1/charges/posting-accounts";
const ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";

describe("the chart of accounts", () => {
  it("lists with page, size, sort and the filters as query parameters, leaving out the one that is not set", async () => {
    route("get", ACCOUNTS, ledgerAccountPage([ledgerAccount()]));

    const result = await listLedgerAccounts("tok-1", { page: 1, size: 50, sort: "name,desc", type: "INCOME", active: undefined });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "1", size: "50", sort: "name,desc", type: "INCOME" });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("creates with a POST of the body as it is and answers the account", async () => {
    route("post", ACCOUNTS, ledgerAccountRecord(ledgerAccount({ code: "ACT-02" }), 201));

    const result = await createLedgerAccount("tok", { code: "ACT-02", name: "Caja", type: "ASSET", holdsStudentBalances: false });

    expect(result.kind).toBe("ok");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual({ code: "ACT-02", name: "Caja", type: "ASSET", holdsStudentBalances: false });
  });

  it("edits with a PUT to the account of the id, with the version, and tells a 409 as a problem", async () => {
    route("put", `${ACCOUNTS}/${ID}`, problem("account-in-use", 409));

    const result = await updateLedgerAccount("tok", ID, { version: 2, name: "Caja", active: false });

    expect(result).toMatchObject({ kind: "problem", problem: { status: 409, code: "account-in-use" } });
    expect(requests()[0]?.method).toBe("PUT");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual({ version: 2, name: "Caja", active: false });
  });
});

describe("the map of posting accounts", () => {
  it("reads the map with a GET and the token", async () => {
    route("get", MAP, postingAccountMap([postingAccount()]));

    const result = await getPostingAccounts("tok-2");

    expect(result.kind).toBe("ok");
    expect(requests()[0]?.headers.get("Authorization")).toBe("Bearer tok-2");
  });

  it("saves with a PUT of the entries and answers the whole map", async () => {
    route("put", MAP, postingAccountMap([postingAccount({ purpose: "TAX_PAYABLE" })]));

    const result = await setPostingAccounts("tok", { entries: [{ purpose: "TAX_PAYABLE", ledgerAccountId: ID }] });

    expect(result.kind).toBe("ok");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual({ entries: [{ purpose: "TAX_PAYABLE", ledgerAccountId: ID }] });
    expect(requests()[0]?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("tells the refusals of the map as problems: a type that does not fit, and a version that is old", async () => {
    route("put", MAP, problem("posting-account-invalid", 409), problem("stale-version", 409));

    const invalid = await setPostingAccounts("tok", { entries: [{ purpose: "RECEIVABLE", ledgerAccountId: ID }] });
    const stale = await setPostingAccounts("tok", { entries: [{ purpose: "RECEIVABLE", ledgerAccountId: ID, version: 0 }] });

    expect(invalid).toMatchObject({ kind: "problem", problem: { code: "posting-account-invalid" } });
    expect(stale).toMatchObject({ kind: "problem", problem: { code: "stale-version" } });
  });
});

import { describe, expect, it } from "vitest";
import { LEDGER_PATHS, LEDGER_VIEWS, isCurrentLedgerView } from "./ledger-paths.ts";
import { ledgerAccountsSearch } from "./ledger-search.ts";

describe("the search of the chart of accounts", () => {
  it("takes the defaults of a list and no filter from an empty address", () => {
    expect(ledgerAccountsSearch.parse({})).toEqual({ page: 1, pageSize: 25 });
  });

  it("keeps a valid type, state and order, and drops each one that does not hold", () => {
    expect(ledgerAccountsSearch.parse({ type: "INCOME", active: false, sort: "name:desc", page: 3 })).toEqual({
      page: 3,
      pageSize: 25,
      type: "INCOME",
      active: false,
      sort: "name:desc",
    });
    expect(ledgerAccountsSearch.parse({ type: "income", active: "maybe", sort: "createdAt:asc", page: 0 })).toEqual({ page: 1, pageSize: 25 });
  });
});

describe("the views of the area", () => {
  it("lists the chart and then the map, each current on its path and below it", () => {
    expect(LEDGER_VIEWS.map((view) => view.path)).toEqual([LEDGER_PATHS.accounts, LEDGER_PATHS.postingMap]);
    const accounts = LEDGER_VIEWS.find((view) => view.id === "accounts") ?? LEDGER_VIEWS[0];
    const map = LEDGER_VIEWS.find((view) => view.id === "posting-map") ?? LEDGER_VIEWS[0];

    expect(isCurrentLedgerView(accounts as NonNullable<typeof accounts>, "/ledger/accounts/")).toBe(true);
    expect(isCurrentLedgerView(accounts as NonNullable<typeof accounts>, "/ledger/posting-accounts")).toBe(false);
    expect(isCurrentLedgerView(map as NonNullable<typeof map>, "/ledger/posting-accounts")).toBe(true);
  });
});

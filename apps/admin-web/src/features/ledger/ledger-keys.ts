/** The chart of accounts: every listing, general to specific, so a prefix invalidates what it covers. Nothing writes a literal key inline. */
export const ledgerAccountKeys = {
  all: ["ledger", "accounts"] as const,
  lists: () => [...ledgerAccountKeys.all, "list"] as const,
  list: (params: object) => [...ledgerAccountKeys.lists(), params] as const,
};

/** The map of posting accounts: one read of the whole map. */
export const postingAccountKeys = {
  all: ["charges", "posting-accounts"] as const,
  map: () => [...postingAccountKeys.all, "map"] as const,
};

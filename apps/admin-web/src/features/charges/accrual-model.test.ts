import { describe, expect, it } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { flattenCatalogKeys } from "../../test/text-keys.ts";
import {
  ALL_ROWS,
  blockerKeyOf,
  ERROR_ROWS,
  failureKeyOf,
  filterPreviewRows,
  isActiveRun,
  isFailedRun,
  itemErrorHelpKeyOf,
  itemErrorKeyOf,
  KNOWN_BLOCKERS,
  KNOWN_FAILURE_CODES,
  KNOWN_ITEM_ERROR_CODES,
  newIntent,
  pageOfRows,
  periodLabelOf,
  pollIntervalOf,
  previewMatches,
  RUN_POLL_MS,
  runBodyOf,
} from "./accrual-model.ts";

const CATALOG = flattenCatalogKeys(admin);

describe("which runs are read again", () => {
  it.each([
    ["QUEUED", true],
    ["RUNNING", true],
    ["COMPLETED", false],
    ["COMPLETED_WITH_ERRORS", false],
    ["REJECTED", false],
    ["FAILED", false],
  ] as const)("%s is polled: %s", (status, active) => {
    expect(isActiveRun(status)).toBe(active);
    expect(pollIntervalOf(status)).toBe(active ? RUN_POLL_MS : false);
  });

  it("does not poll a run that has not been read yet", () => {
    expect(isActiveRun(undefined)).toBe(false);
    expect(pollIntervalOf(undefined)).toBe(false);
  });

  it("tells a rejected or failed run from the others", () => {
    expect(isFailedRun("REJECTED")).toBe(true);
    expect(isFailedRun("FAILED")).toBe(true);
    expect(isFailedRun("COMPLETED_WITH_ERRORS")).toBe(false);
  });
});

describe("the texts of the codes of the server", () => {
  it("has a text and an explanation for every item error code, and for an unknown one", () => {
    for (const code of [...KNOWN_ITEM_ERROR_CODES, "SOMETHING_NEW"]) {
      expect(CATALOG, code).toContain(itemErrorKeyOf(code));
      expect(CATALOG, code).toContain(itemErrorHelpKeyOf(code));
    }
    expect(itemErrorKeyOf(null)).toBe("charges.accrual.errorCodes.unknown");
    expect(itemErrorKeyOf(undefined)).toBe("charges.accrual.errorCodes.unknown");
  });

  it("has a text for every blocker and for an unknown one", () => {
    for (const code of [...KNOWN_BLOCKERS, "something-new"]) expect(CATALOG, code).toContain(blockerKeyOf(code));
    expect(blockerKeyOf("posting-accounts-incomplete")).toBe("charges.accrual.blockers.posting-accounts-incomplete");
    expect(blockerKeyOf("something-new")).toBe("charges.accrual.blockers.unknown");
  });

  it("has a text for every failure code of a run and for an unknown one", () => {
    for (const code of [...KNOWN_FAILURE_CODES, "something-new"]) expect(CATALOG, code).toContain(failureKeyOf(code));
    expect(failureKeyOf("preview-outdated")).toBe("charges.run.failureCodes.preview-outdated");
    expect(failureKeyOf("something-new")).toBe("charges.run.failureCodes.unknown");
  });
});

describe("the intent of a confirmation", () => {
  const selection = { planId: "00000000-0000-4000-8000-000000000c01", periodStart: "2027-02-01" };
  const preview = { previewDigest: "b".repeat(64) };

  it("carries the plan, the period, the digest of the preview and one key", () => {
    const intent = newIntent(selection, preview, () => "key-1");

    expect(intent).toEqual({ ...selection, previewDigest: "b".repeat(64), idempotencyKey: "key-1" });
  });

  it("makes another key for another confirmation, and keeps its own for every use", () => {
    let n = 0;
    const random = () => `key-${String(++n)}`;
    const first = newIntent(selection, preview, random);
    const second = newIntent(selection, preview, random);

    expect(first.idempotencyKey).toBe("key-1");
    expect(second.idempotencyKey).toBe("key-2");
    expect(runBodyOf(first)).toEqual(runBodyOf(second));
  });

  it("makes a random key (a UUID) by default", () => {
    expect(newIntent(selection, preview).idempotencyKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  });

  it("sends the period and the digest in the body and nothing else, the key travels in the header", () => {
    expect(runBodyOf(newIntent(selection, preview, () => "k"))).toEqual({ periodStart: "2027-02-01", previewDigest: "b".repeat(64) });
  });
});

describe("a preview and the choice it was made for", () => {
  it("matches only the same plan and the same period", () => {
    const preview = { planId: "p1", periodStart: "2027-02-01" };

    expect(previewMatches(preview, { planId: "p1", periodStart: "2027-02-01" })).toBe(true);
    expect(previewMatches(preview, { planId: "p2", periodStart: "2027-02-01" })).toBe(false);
    expect(previewMatches(preview, { planId: "p1", periodStart: "2027-03-01" })).toBe(false);
    expect(previewMatches(preview, {})).toBe(false);
  });
});

describe("the rows of a preview", () => {
  const rows = [
    { id: 1, outcome: "CREATED" },
    { id: 2, outcome: "ERROR", errorCode: "NO_PRICE" },
    { id: 3, outcome: "ERROR", errorCode: "AMBIGUOUS_PRICE" },
    { id: 4, outcome: "ALREADY_ACCRUED" },
  ];

  it("keeps every row, only the rows with an error, or the rows with one error code", () => {
    expect(filterPreviewRows(rows, ALL_ROWS)).toHaveLength(4);
    expect(filterPreviewRows(rows, ERROR_ROWS).map((row) => row.id)).toEqual([2, 3]);
    expect(filterPreviewRows(rows, "NO_PRICE").map((row) => row.id)).toEqual([2]);
    expect(filterPreviewRows(rows, "TAX_RATE_MISSING")).toEqual([]);
  });

  it("pages rows that are already in memory, from page 0", () => {
    expect(pageOfRows(rows, 0, 3).map((row) => row.id)).toEqual([1, 2, 3]);
    expect(pageOfRows(rows, 1, 3).map((row) => row.id)).toEqual([4]);
    expect(pageOfRows(rows, 2, 3)).toEqual([]);
  });

  it("reads the label of a month period from its first day", () => {
    expect(periodLabelOf("2027-02-01")).toBe("2027-02");
  });
});

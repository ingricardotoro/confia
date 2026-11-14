import { describe, expect, it } from "vitest";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import type { ApiProblem } from "../../shared/api/problem.ts";
import { classifyOrganizationFailure, failureMessageOf, failureOf, loadFailureOf } from "./organization-failure.ts";

// How a failed request of the academic structure becomes what a form or a screen shows. Texts are catalog keys.
const problem = (code: ApiProblem["code"], status: number, extra: Partial<ApiProblem> = {}) =>
  ({ kind: "problem", problem: { status, code, errors: [], ...extra } }) as const;

describe("classifyOrganizationFailure", () => {
  it.each([
    ["academic-year-overlap", "problem.academicYearOverlap"],
    ["academic-year-already-active", "problem.academicYearAlreadyActive"],
    ["academic-year-closed", "problem.academicYearClosed"],
    ["duplicate-name", "problem.duplicateName"],
    ["inactive-reference", "problem.inactiveReference"],
    ["forbidden", "problem.forbidden"],
    ["resource-not-found", "problem.notFound"],
  ] as const)("gives %s its own message, with the trace id", (code, key) => {
    expect(classifyOrganizationFailure(problem(code, 409, { traceId: "t-1" }))).toEqual({ kind: "message", key, traceId: "t-1" });
  });

  it("omits the trace id when the server sent none", () => {
    expect(classifyOrganizationFailure(problem("forbidden", 403))).toEqual({ kind: "message", key: "problem.forbidden" });
  });

  it("keeps the field errors of a 400 and names the end date for the year's end-after-start rule", () => {
    const failure = classifyOrganizationFailure(
      problem("validation-failed", 400, {
        errors: [
          { field: "name", reason: "size" },
          { field: "endAfterStart", reason: "assert-true" },
        ],
      }),
    );

    expect(failure).toEqual({
      kind: "fields",
      errors: [
        { field: "name", reason: "size" },
        { field: "endDate", reason: "end-after-start" },
      ],
    });
  });

  it("leaves the rest to the general case: the network, a 429 and an unknown problem", () => {
    expect(classifyOrganizationFailure({ kind: "network" })).toEqual({ kind: "message", key: "problem.network" });
    expect(classifyOrganizationFailure(problem("too-many-requests", 429, { retryAfterSeconds: 12 }))).toEqual({
      kind: "rate-limited",
      retryAfterSeconds: 12,
    });
    expect(classifyOrganizationFailure(problem("unknown", 500))).toEqual({ kind: "unexpected" });
  });
});

describe("failureOf", () => {
  it("reads an ApiFailure, and treats anything else as unexpected", () => {
    expect(failureOf(new ApiFailure(problem("duplicate-name", 409)))).toEqual({ kind: "message", key: "problem.duplicateName" });
    expect(failureOf(new Error("boom"))).toEqual({ kind: "unexpected" });
  });
});

describe("failureMessageOf", () => {
  it("gives every kind of failure a message", () => {
    expect(failureMessageOf({ kind: "message", key: "problem.network", traceId: "t" })).toEqual({ key: "problem.network", traceId: "t" });
    expect(failureMessageOf({ kind: "rate-limited", retryAfterSeconds: 5 })).toEqual({ key: "problem.rateLimited" });
    expect(failureMessageOf({ kind: "fields", errors: [] })).toEqual({ key: "problem.notProcessed" });
    expect(failureMessageOf({ kind: "unexpected", traceId: "t" })).toEqual({ key: "problem.unexpected", traceId: "t" });
    expect(failureMessageOf({ kind: "restricted-token-ended" })).toEqual({ key: "problem.unexpected" });
  });
});

describe("loadFailureOf", () => {
  it("says the network failed, maps a problem to its key with the trace id, and keeps anything else generic", () => {
    expect(loadFailureOf(new ApiFailure({ kind: "network" }))).toEqual({ key: "problem.network" });
    expect(loadFailureOf(new ApiFailure(problem("forbidden", 403, { traceId: "t-9" })))).toEqual({ key: "problem.forbidden", traceId: "t-9" });
    expect(loadFailureOf(new ApiFailure({ kind: "unexpected-response", status: 200 }))).toEqual({ key: "problem.unexpected" });
    expect(loadFailureOf(new Error("boom"))).toEqual({ key: "problem.unexpected" });
  });
});

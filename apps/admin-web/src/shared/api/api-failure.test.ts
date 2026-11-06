import { describe, expect, it } from "vitest";
import { ApiFailure, isRetryableFailure } from "./api-failure.ts";

// admin-web-foundations-and-session, task 4.1 (design.md D6 and D9). A query function throws an
// ApiFailure for any result that is not ok, and the query client decides from its kind whether a
// read is worth repeating. Task 3 left this refinement for the task that introduces ApiResult.
const problem = (status: number) => ({ kind: "problem", problem: { status, code: "unknown", errors: [] } }) as const;

describe("ApiFailure", () => {
  it("keeps the result it was built from", () => {
    const result = problem(503);

    expect(new ApiFailure(result).result).toBe(result);
  });

  it("is an Error whose message carries the kind and the status, never the body", () => {
    expect(new ApiFailure(problem(503))).toBeInstanceOf(Error);
    expect(new ApiFailure(problem(503)).message).toBe("API failure: problem 503");
    expect(new ApiFailure({ kind: "network" }).message).toBe("API failure: network");
    expect(new ApiFailure({ kind: "unexpected-response", status: 200 }).message).toBe("API failure: unexpected-response 200");
  });
});

describe("isRetryableFailure", () => {
  it("repeats a read after a network failure", () => {
    expect(isRetryableFailure(new ApiFailure({ kind: "network" }))).toBe(true);
  });

  it.each([500, 502, 503, 504])("repeats a read after a %i", (status) => {
    expect(isRetryableFailure(new ApiFailure(problem(status)))).toBe(true);
  });

  it.each([400, 401, 403, 404, 409, 422, 429])("never repeats a read after a %i", (status) => {
    expect(isRetryableFailure(new ApiFailure(problem(status)))).toBe(false);
  });

  it("never repeats a 2xx that did not meet the schema, because it would answer the same", () => {
    expect(isRetryableFailure(new ApiFailure({ kind: "unexpected-response", status: 200 }))).toBe(false);
  });

  it.each([new Error("bug"), new TypeError("Failed to fetch"), { status: 503 }, "x", null, undefined])(
    "never repeats an error that is not an ApiFailure: %s",
    (error) => {
      expect(isRetryableFailure(error)).toBe(false);
    },
  );
});

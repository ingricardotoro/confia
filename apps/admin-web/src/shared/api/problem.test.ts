import { describe, expect, it, vi } from "vitest";
import { KNOWN_PROBLEM_CODES, readProblem } from "./problem.ts";

// admin-web-foundations-and-session, task 4.1 (AW46, AW47, AW50, AW52, AW53, AW55, AW56, design.md D7).
const ORIGIN = "https://confia.hn/problems/";

function problemResponse(body: unknown, init: { status?: number; retryAfter?: string } = {}): Response {
  const headers = new Headers({ "Content-Type": "application/problem+json" });
  if (init.retryAfter !== undefined) headers.set("Retry-After", init.retryAfter);
  return new Response(typeof body === "string" ? body : JSON.stringify(body), { status: init.status ?? 400, headers });
}

// The backend emits `instance` as a relative path (ProblemResponses.java:106), which zod.url() rejects.
function backendBody(code: string, extra: Record<string, unknown> = {}) {
  return {
    type: `${ORIGIN}${code}`,
    title: "Titulo del servidor",
    status: 400,
    detail: "Detalle del servidor",
    instance: "/api/v1/auth/sessions",
    traceId: "0af7651916cd43dd8448eb211c80319c",
    ...extra,
  };
}

describe("the known codes", () => {
  it("AW46: lists the twenty nine codes of the specification", () => {
    expect([...KNOWN_PROBLEM_CODES].sort()).toEqual(
      [
        "authentication-failed",
        "second-factor-invalid",
        "session-expired",
        "csrf-invalid",
        "mfa-already-enrolled",
        "validation-failed",
        "unsupported-media-type",
        "too-many-requests",
        "capacity-exceeded",
        "internal-error",
        "authentication-required",
        "token-invalid",
        "token-expired",
        "forbidden",
        "resource-not-found",
        "academic-year-overlap",
        "academic-year-already-active",
        "academic-year-closed",
        "duplicate-name",
        "inactive-reference",
        "duplicate-student-code",
        "duplicate-national-id",
        "student-not-active",
        "section-full",
        "duplicate-active-enrollment",
        "invalid-enrollment-state",
        "transfer-year-mismatch",
        "invalid-responsibility-split",
        "guardian-not-active",
      ].sort(),
    );
  });

  it.each(KNOWN_PROBLEM_CODES)("AW46: recognises %s from a body with a relative instance", async (code) => {
    const problem = await readProblem(problemResponse(backendBody(code), { status: 409 }), vi.fn());

    expect(problem.code).toBe(code);
    expect(problem.status).toBe(409);
    expect(problem.traceId).toBe("0af7651916cd43dd8448eb211c80319c");
  });
});

describe("what is unknown", () => {
  it.each([
    ["a code outside the list", { type: `${ORIGIN}algo-nuevo` }],
    ["no type", { title: "x" }],
    ["another host", { type: "https://otro.example/problems/internal-error" }],
    ["a host that only starts like ours", { type: "https://confia.hn.otro.example/problems/internal-error" }],
    ["a suffix after a known code", { type: `${ORIGIN}internal-error/extra` }],
    ["a query after a known code", { type: `${ORIGIN}internal-error?x=1` }],
    ["a type that is not text", { type: 42 }],
  ])("AW47: %s gives the unknown code", async (_name, body) => {
    const report = vi.fn();

    const problem = await readProblem(problemResponse(body, { status: 500 }), report);

    expect(problem.code).toBe("unknown");
    expect(problem.status).toBe(500);
    expect(report).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["HTML", 502, "<html><body>Bad gateway</body></html>", "text/html"],
    ["HTML", 503, "<html>unavailable</html>", "text/html"],
    ["an empty body", 500, "", "text/plain"],
    ["a JSON array", 500, "[1,2]", "application/json"],
    ["JSON null", 500, "null", "application/json"],
  ])("AW43, AW56: a %s body of %i is the unknown code and does not throw", async (_name, status, body, contentType) => {
    const response = new Response(body, { status, headers: { "Content-Type": contentType } });

    const problem = await readProblem(response, vi.fn());

    expect(problem).toEqual({ status, code: "unknown", errors: [] });
  });

  it("does not throw when the body cannot be read at all", async () => {
    const response = new Response("x", { status: 500 });
    vi.spyOn(response, "text").mockRejectedValue(new TypeError("body stream failed"));

    await expect(readProblem(response, vi.fn())).resolves.toEqual({ status: 500, code: "unknown", errors: [] });
  });

  it("keeps title, detail and instance out of the result", async () => {
    const problem = await readProblem(problemResponse(backendBody("forbidden"), { status: 403 }), vi.fn());

    expect(Object.keys(problem).sort()).toEqual(["code", "errors", "status", "traceId"]);
    expect(JSON.stringify(problem)).not.toContain("servidor");
  });

  it("takes a trace id only when it is text", async () => {
    const problem = await readProblem(problemResponse(backendBody("forbidden", { traceId: 7 }), { status: 403 }), vi.fn());

    expect(problem.traceId).toBeUndefined();
  });
});

describe("validation-failed", () => {
  it("AW50: exposes the field and the reason of each error", async () => {
    const body = backendBody("validation-failed", { errors: [{ field: "identifier", reason: "not-blank" }] });

    const problem = await readProblem(problemResponse(body), vi.fn());

    expect(problem.errors).toEqual([{ field: "identifier", reason: "not-blank" }]);
  });

  it("AW50: gives an empty list when errors is absent, empty or not a list", async () => {
    for (const extra of [{}, { errors: [] }, { errors: "x" }, { errors: null }]) {
      const problem = await readProblem(problemResponse(backendBody("validation-failed", extra)), vi.fn());
      expect(problem.errors).toEqual([]);
    }
  });

  it("drops the entries that are not a field and a reason, and keeps the rest", async () => {
    const errors = [{ field: "a", reason: "size" }, { field: 1, reason: "size" }, { field: "b" }, null, "x", { field: "c", reason: "max" }];

    const problem = await readProblem(problemResponse(backendBody("validation-failed", { errors })), vi.fn());

    expect(problem.errors).toEqual([
      { field: "a", reason: "size" },
      { field: "c", reason: "max" },
    ]);
  });

  it("keeps at most fifty errors, the bound of the contract", async () => {
    const errors = Array.from({ length: 80 }, (_, i) => ({ field: `f${String(i)}`, reason: "size" }));

    const problem = await readProblem(problemResponse(backendBody("validation-failed", { errors })), vi.fn());

    expect(problem.errors).toHaveLength(50);
  });

  it("exposes no errors for a code that is not validation-failed", async () => {
    const body = backendBody("forbidden", { errors: [{ field: "a", reason: "size" }] });

    expect((await readProblem(problemResponse(body, { status: 403 }), vi.fn())).errors).toEqual([]);
  });
});

describe("Retry-After on too-many-requests", () => {
  const read = (retryAfter?: string) =>
    readProblem(problemResponse(backendBody("too-many-requests"), { status: 429, ...(retryAfter === undefined ? {} : { retryAfter }) }), vi.fn());

  it("AW52: reads whole seconds", async () => {
    expect((await read("15")).retryAfterSeconds).toBe(15);
  });

  it.each([
    ["absent", undefined],
    ["zero", "0"],
    ["text", "abc"],
    ["an HTTP date", "Wed, 21 Oct 2026 07:28:00 GMT"],
    ["negative", "-5"],
    ["fractional", "1.5"],
    ["with a leading zero", "015"],
  ])("AW53: %s gives the default of 60 seconds", async (_name, value) => {
    expect((await read(value)).retryAfterSeconds).toBe(60);
  });

  it("AW53: clamps 99999 to 3600 and keeps 3600", async () => {
    expect((await read("99999")).retryAfterSeconds).toBe(3600);
    expect((await read("3600")).retryAfterSeconds).toBe(3600);
    expect((await read("99999999999999999999999")).retryAfterSeconds).toBe(3600);
  });

  it("is not set for any other code", async () => {
    const response = problemResponse(backendBody("capacity-exceeded"), { status: 503, retryAfter: "15" });

    expect((await readProblem(response, vi.fn())).retryAfterSeconds).toBeUndefined();
  });
});

describe("reporting the unknown case", () => {
  it("AW55: hands the report only the status and a type without query or fragment", async () => {
    const report = vi.fn();
    const body = backendBody("algo-nuevo", { type: `${ORIGIN}algo-nuevo?token=secreto#frag` });

    await readProblem(problemResponse(body, { status: 500 }), report);

    expect(report).toHaveBeenCalledExactlyOnceWith({ status: 500, type: `${ORIGIN}algo-nuevo` });
  });

  it("reports the status alone when there is no usable type, and never reports a known code", async () => {
    const report = vi.fn();

    await readProblem(new Response("<html/>", { status: 502 }), report);
    await readProblem(problemResponse(backendBody("forbidden"), { status: 403 }), report);

    expect(report).toHaveBeenCalledExactlyOnceWith({ status: 502 });
  });
});

describe("invalid-responsibility-split", () => {
  it("exposes the reason of the split, and no other code carries errors", async () => {
    const errors = [{ field: "links", reason: "sum-not-100" }];

    const split = await readProblem(problemResponse(backendBody("invalid-responsibility-split", { errors })), vi.fn());
    const other = await readProblem(problemResponse(backendBody("guardian-not-active", { errors })), vi.fn());

    expect(split.errors).toEqual(errors);
    expect(other.errors).toEqual([]);
  });
});

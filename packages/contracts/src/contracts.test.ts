import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { Money as AdminMoney, ProblemDetail as AdminProblemDetail } from "./admin.js";
import { Money as PortalMoney } from "./portal.js";

// Specs/build-integrity, requirement "Los contratos del frontend se generan y un cambio
// incompatible rompe la compilación" (frontend-monorepo-and-contracts-pipeline, task 2.4).
describe("the generated Money schema", () => {
  it.each([
    ["admin", AdminMoney],
    ["portal", PortalMoney],
  ])("on the %s surface accepts an amount given as a string", (_surface, schema) => {
    expect(schema.parse({ amount: "1250.50", currency: "HNL" })).toEqual({
      amount: "1250.50",
      currency: "HNL",
    });
  });

  it.each([
    ["admin", AdminMoney],
    ["portal", PortalMoney],
  ])("on the %s surface rejects the same amount given as a JSON number", (_surface, schema) => {
    expect(schema.safeParse({ amount: 1250.5, currency: "HNL" }).success).toBe(false);
  });

  it("rejects an amount with more than four decimals and a lower-case currency", () => {
    expect(AdminMoney.safeParse({ amount: "1.23456", currency: "HNL" }).success).toBe(false);
    expect(AdminMoney.safeParse({ amount: "1.00", currency: "hnl" }).success).toBe(false);
  });
});

describe("the generated ProblemDetail schema", () => {
  it("accepts an RFC 9457 error with its trace identifier", () => {
    const problem = {
      type: "https://confia.example/problems/idempotency-conflict",
      title: "Conflict",
      status: 409,
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
    };
    expect(AdminProblemDetail.parse(problem)).toEqual(problem);
  });
});

describe("the generated output", () => {
  // Scenario "La salida generada no está en el repositorio": src/generated/ is ignored by Git, so
  // nobody can commit a hand edit to it. git check-ignore exits 0 only for an ignored path.
  it("is ignored by Git", () => {
    const generated = fileURLToPath(new URL("./generated/admin/schemas.ts", import.meta.url));
    expect(() => execFileSync("git", ["check-ignore", "--quiet", generated])).not.toThrow();
  });
});

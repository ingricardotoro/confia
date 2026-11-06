import { describe, expect, it, vi } from "vitest";
import { reportUnknownProblem } from "./report-unknown-problem.ts";

// admin-web-foundations-and-session, task 4.1 (AW55, CLAUDE.md rule 11). The record carries at most the
// status and the type, and nothing the person or the server wrote in the body.
describe("reportUnknownProblem", () => {
  it("AW55: writes the status and the type through the injected function", () => {
    const warn = vi.fn();

    reportUnknownProblem({ status: 500, type: "https://confia.hn/problems/algo-nuevo" }, warn);

    expect(warn).toHaveBeenCalledExactlyOnceWith("Unknown problem response: status 500, type https://confia.hn/problems/algo-nuevo");
  });

  it("writes the status alone when there is no type", () => {
    const warn = vi.fn();

    reportUnknownProblem({ status: 502 }, warn);

    expect(warn).toHaveBeenCalledExactlyOnceWith("Unknown problem response: status 502");
  });

  it("AW55: strips a query and a fragment from the type", () => {
    const warn = vi.fn();

    reportUnknownProblem({ status: 500, type: "https://x.example/p?token=secreto#frag" }, warn);

    expect(warn).toHaveBeenCalledExactlyOnceWith("Unknown problem response: status 500, type https://x.example/p");
  });

  it("AW55: never writes more than 200 characters", () => {
    const warn = vi.fn();

    reportUnknownProblem({ status: 500, type: `https://x.example/${"a".repeat(1000)}` }, warn);

    const [message] = warn.mock.calls[0] as [string];
    expect(message.length).toBeLessThanOrEqual(200);
    expect(message).toMatch(/^Unknown problem response: status 500, type https:\/\/x\.example\/a+$/);
  });

  it("writes through console.warn by default", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);

    reportUnknownProblem({ status: 500 });

    expect(warn).toHaveBeenCalledExactlyOnceWith("Unknown problem response: status 500");
    warn.mockRestore();
  });
});

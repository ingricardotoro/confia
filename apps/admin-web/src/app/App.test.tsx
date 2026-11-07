import { render, screen } from "@testing-library/react";
import { createMemoryHistory } from "@tanstack/react-router";
import { describe, expect, it } from "vitest";
import { App } from "./App.tsx";
import { createAppRouter } from "./router.tsx";

// admin-web-foundations-and-session, task 2.2 (AW01). The smoke test that makes `test` run a real test
// on the app. Later changes add the routes; this one proves the root renders and the router resolves.
describe("the app shell", () => {
  it("AW01: renders one main landmark at the root route", async () => {
    const router = createAppRouter(createMemoryHistory({ initialEntries: ["/"] }));
    render(<App router={router} />);

    expect(await screen.findByRole("main")).toBeInTheDocument();
    expect(screen.getAllByRole("main")).toHaveLength(1);
  });

  it("creates an independent router per call, so a test never shares navigation state", () => {
    const first = createAppRouter(createMemoryHistory({ initialEntries: ["/"] }));
    const second = createAppRouter(createMemoryHistory({ initialEntries: ["/"] }));

    expect(first).not.toBe(second);
  });
});

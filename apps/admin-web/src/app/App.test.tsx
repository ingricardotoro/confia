import { screen } from "@testing-library/react";
import { createMemoryHistory } from "@tanstack/react-router";
import { describe, expect, it } from "vitest";
import { createTestSession, renderApp } from "../test/render-app.tsx";
import { installMswServer } from "../test/msw-server.ts";
import { createAppRouter } from "./router.tsx";

// admin-web-foundations-and-session, task 2.2 (AW01). The smoke test that makes `test` run a real test
// on the app: the root renders and the router resolves. With no session the router lands on the sign in
// placeholder (task 9), which is one main landmark like every screen.
installMswServer();

describe("the app shell", () => {
  it("AW01: renders one main landmark at the root route", async () => {
    renderApp({ path: "/" });

    expect(await screen.findByRole("main")).toBeInTheDocument();
    expect(screen.getAllByRole("main")).toHaveLength(1);
  });

  it("creates an independent router per call, so a test never shares navigation state", () => {
    const session = createTestSession();
    const first = createAppRouter({ session, history: createMemoryHistory({ initialEntries: ["/"] }) });
    const second = createAppRouter({ session, history: createMemoryHistory({ initialEntries: ["/"] }) });

    expect(first).not.toBe(second);
  });
});

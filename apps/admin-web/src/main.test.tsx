import { screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// admin-web-foundations-and-session, task 2.2 (AW01). The entry point is the one file index.html loads,
// so it is exercised as the browser would: import it against a document that has (or lacks) #root.
// The time limit is explicit because this suite builds the whole app graph cold (or starts a real
// server), which can outlast Vitest's 5 s default on a loaded machine; no assertion waits on it.
describe("the entry point", { timeout: 20_000 }, () => {
  beforeEach(() => {
    vi.resetModules();
    document.body.innerHTML = "";
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("mounts the app into #root", async () => {
    document.body.innerHTML = '<div id="root"></div>';

    await import("./main.tsx");

    expect(await screen.findByRole("main")).toBeInTheDocument();
  });

  it("fails loudly when index.html has no #root, instead of rendering nothing", async () => {
    await expect(import("./main.tsx")).rejects.toThrow("#root");
  });
});

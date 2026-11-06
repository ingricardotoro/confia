import { afterEach, describe, expect, it, vi } from "vitest";
import { createQueryClient, retryRead } from "./query-client.ts";

// admin-web-foundations-and-session, task 3.1 (AW23, design.md D9). The defaults are the behaviour of
// every read and write the app will make, so they are pinned as values and as observable behaviour.
afterEach(() => {
  vi.useRealTimers();
});

describe("the query client defaults", () => {
  it("AW23: mutations never retry and reads retry at most twice", () => {
    const defaults = createQueryClient().getDefaultOptions();

    expect(defaults.mutations?.retry).toBe(0);
    expect(defaults.queries?.retry).toBe(retryRead);
    expect(retryRead(0, new Error("network"))).toBe(true);
    expect(retryRead(1, new Error("network"))).toBe(true);
    expect(retryRead(2, new Error("network"))).toBe(false);
  });

  it("works offline-first: both kinds run even when the browser reports no network", () => {
    const defaults = createQueryClient().getDefaultOptions();

    expect(defaults.queries?.networkMode).toBe("always");
    expect(defaults.mutations?.networkMode).toBe("always");
  });

  it("does not refetch on window focus or on reconnect", () => {
    const defaults = createQueryClient().getDefaultOptions();

    expect(defaults.queries?.refetchOnWindowFocus).toBe(false);
    expect(defaults.queries?.refetchOnReconnect).toBe(false);
  });

  it("backs off exponentially from one second", () => {
    const retryDelay = createQueryClient().getDefaultOptions().queries?.retryDelay;

    expect(typeof retryDelay).toBe("function");
    if (typeof retryDelay !== "function") return;
    expect([0, 1, 2].map((attempt) => retryDelay(attempt, new Error("network")))).toEqual([1000, 2000, 4000]);
  });

  it("creates an independent client on each call, so a test never shares a cache", () => {
    expect(createQueryClient()).not.toBe(createQueryClient());
  });
});

describe("retryRead", () => {
  it.each([400, 401, 403, 404, 409, 422, 429])("never retries a %i, whatever the count", (status) => {
    expect(retryRead(0, Object.assign(new Error("client error"), { status }))).toBe(false);
  });

  it.each([500, 502, 503])("retries a %i, up to the limit", (status) => {
    const error = Object.assign(new Error("server error"), { status });

    expect(retryRead(0, error)).toBe(true);
    expect(retryRead(1, error)).toBe(true);
    expect(retryRead(2, error)).toBe(false);
  });

  it("retries an error that carries no status, such as a network failure", () => {
    expect(retryRead(0, new TypeError("Failed to fetch"))).toBe(true);
  });
});

describe("the query client at work", () => {
  it("AW23: a failed mutation runs exactly once", async () => {
    const client = createQueryClient();
    const mutationFn = vi.fn(() => Promise.reject(new Error("boom")));

    await expect(client.getMutationCache().build(client, { mutationFn }).execute(undefined)).rejects.toThrow("boom");

    expect(mutationFn).toHaveBeenCalledTimes(1);
  });

  it("AW23: a read answered with 404 runs exactly once", async () => {
    const client = createQueryClient();
    const queryFn = vi.fn(() => Promise.reject(Object.assign(new Error("not found"), { status: 404 })));

    await expect(client.query({ queryKey: ["probe", "404"], queryFn })).rejects.toThrow("not found");

    expect(queryFn).toHaveBeenCalledTimes(1);
  });

  it("AW23: a read answered with 503 runs three times, the first try and two retries", async () => {
    vi.useFakeTimers();
    const client = createQueryClient();
    const queryFn = vi.fn(() => Promise.reject(Object.assign(new Error("unavailable"), { status: 503 })));

    const settled = client.query({ queryKey: ["probe", "503"], queryFn }).then(
      () => "resolved",
      () => "rejected",
    );
    await vi.advanceTimersByTimeAsync(10_000);

    await expect(settled).resolves.toBe("rejected");
    expect(queryFn).toHaveBeenCalledTimes(3);
  });
});

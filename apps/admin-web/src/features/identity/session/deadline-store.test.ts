import { afterEach, describe, expect, it, vi } from "vitest";
import { createDeadlineStore, effectiveDeadline } from "./deadline-store.ts";

// admin-web-shell-and-session-lifecycle, task 1.3 (SS129 to SS133, design.md H1). The deadlines are in
// local milliseconds, already corrected by the server clock; the store only keeps them.
const T = Date.parse("2026-11-10T10:00:00Z");
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  sessionStorage.clear();
});

describe("the deadline store", () => {
  it("holds nothing before the first answer", () => {
    expect(createDeadlineStore().snapshot()).toBeUndefined();
  });

  it("SS128: keeps both deadlines of an answer and the instant of the refresh it came with", () => {
    const store = createDeadlineStore();

    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);

    expect(store.snapshot()).toEqual({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR, lastRefreshAt: T });
  });

  it("returns the same object until something changes, and a new one when it does", () => {
    const store = createDeadlineStore();
    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);
    const first = store.snapshot();

    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);
    expect(store.snapshot()).toBe(first);

    store.record({ idleExpiresAt: T + 31 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);
    expect(store.snapshot()).not.toBe(first);
    expect(store.snapshot()?.idleExpiresAt).toBe(T + 31 * MINUTE);
  });

  it("SS131: the deadline of the last answer replaces the earlier one, also when it is sooner", () => {
    const store = createDeadlineStore();
    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);

    store.record({ idleExpiresAt: T + 20 * MINUTE, sessionExpiresAt: T + 11 * HOUR }, T + MINUTE);

    expect(store.snapshot()).toEqual({ idleExpiresAt: T + 20 * MINUTE, sessionExpiresAt: T + 11 * HOUR, lastRefreshAt: T + MINUTE });
  });

  it("an answer that moves nothing on the server keeps the instant of the last refresh", () => {
    const store = createDeadlineStore();
    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);

    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR });
    expect(store.snapshot()?.lastRefreshAt).toBe(T);

    store.record({ idleExpiresAt: T + 35 * MINUTE, sessionExpiresAt: T + 12 * HOUR });
    expect(store.snapshot()).toEqual({ idleExpiresAt: T + 35 * MINUTE, sessionExpiresAt: T + 12 * HOUR, lastRefreshAt: T });
  });

  it("without any refresh yet, the instant of the last refresh is absent", () => {
    const store = createDeadlineStore();

    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR });

    expect(store.snapshot()).toEqual({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR, lastRefreshAt: undefined });
  });

  it("tells a listener after the change, once per change and not for a repeat", () => {
    const store = createDeadlineStore();
    const seen: (number | undefined)[] = [];
    store.subscribe(() => seen.push(store.snapshot()?.idleExpiresAt));

    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);
    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);
    store.record({ idleExpiresAt: T + 20 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);

    expect(seen).toEqual([T + 30 * MINUTE, T + 20 * MINUTE]);
  });

  it("stops telling a listener that unsubscribed", () => {
    const store = createDeadlineStore();
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);
    unsubscribe();

    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);

    expect(listener).not.toHaveBeenCalled();
  });

  it("SS133: clearing empties the store and tells the listeners once, and clearing an empty store tells nobody", () => {
    const store = createDeadlineStore();
    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);
    const listener = vi.fn();
    store.subscribe(listener);

    store.clear();
    store.clear();

    expect(store.snapshot()).toBeUndefined();
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("SS129: writes nothing to web storage, to the address or to the history entry", () => {
    const setLocal = vi.spyOn(Storage.prototype, "setItem");
    const href = location.href;
    const entry: unknown = history.state;
    const store = createDeadlineStore();

    store.record({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);
    store.record({ idleExpiresAt: T + 20 * MINUTE, sessionExpiresAt: T + 12 * HOUR }, T);
    store.clear();

    expect(setLocal).not.toHaveBeenCalled();
    expect(localStorage).toHaveLength(0);
    expect(sessionStorage).toHaveLength(0);
    expect(location.href).toBe(href);
    expect(history.state).toBe(entry);
  });
});

describe("the effective deadline", () => {
  it("SS132: is the idle deadline when it comes first", () => {
    expect(effectiveDeadline({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 12 * HOUR, lastRefreshAt: undefined })).toBe(
      T + 30 * MINUTE,
    );
  });

  it("SS132: is the absolute deadline when it comes first", () => {
    expect(effectiveDeadline({ idleExpiresAt: T + 30 * MINUTE, sessionExpiresAt: T + 10 * MINUTE, lastRefreshAt: undefined })).toBe(
      T + 10 * MINUTE,
    );
  });
});

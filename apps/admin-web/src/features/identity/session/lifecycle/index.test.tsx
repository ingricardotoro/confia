import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import * as lifecycle from "./index.ts";
import type { LifecycleScheduler, LifecycleSnapshot } from "./index.ts";

// admin-web-shell-and-session-lifecycle, task 2.4 and 2.5 (design.md H16): the public surface of the
// lifecycle module is the hook and the types, and nothing else leaves the folder.
function fakeScheduler(first: LifecycleSnapshot) {
  let snapshot = first;
  const listeners = new Set<() => void>();
  const scheduler: LifecycleScheduler = {
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    getSnapshot: () => snapshot,
    dismissAbsoluteWarning: () => undefined,
    replan: () => undefined,
    dispose: () => undefined,
  };
  return {
    scheduler,
    show: (next: LifecycleSnapshot) => {
      snapshot = next;
      for (const listener of [...listeners]) listener();
    },
    listeners: () => listeners.size,
  };
}

describe("the public surface of the lifecycle module", () => {
  it("exports the hook and no value else", () => {
    expect(Object.keys(lifecycle)).toEqual(["usePhase"]);
  });

  it("usePhase reads the snapshot of the scheduler and follows its changes", () => {
    const { scheduler, show } = fakeScheduler({ phase: { kind: "running" }, remainingMs: undefined });
    const { result } = renderHook(() => lifecycle.usePhase(scheduler));
    expect(result.current).toEqual({ phase: { kind: "running" }, remainingMs: undefined });

    act(() => {
      show({ phase: { kind: "idle-warning", endsAt: 1_800_000 }, remainingMs: 300_000 });
    });

    expect(result.current).toEqual({ phase: { kind: "idle-warning", endsAt: 1_800_000 }, remainingMs: 300_000 });
  });

  it("stops listening when the component goes away", () => {
    const { scheduler, listeners } = fakeScheduler({ phase: { kind: "running" }, remainingMs: undefined });
    const { unmount } = renderHook(() => lifecycle.usePhase(scheduler));
    expect(listeners()).toBe(1);

    unmount();

    expect(listeners()).toBe(0);
  });
});

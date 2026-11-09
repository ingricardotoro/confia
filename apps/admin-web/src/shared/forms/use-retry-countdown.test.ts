import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useRetryCountdown } from "./use-retry-countdown.ts";

// admin-web-staff-sign-in, task 2.2 (E6; SS43, SS45, SS46). Simulated timers: the deadline is fixed when the wait
// starts, and the one-second interval exists only while it lasts.
beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe("useRetryCountdown", () => {
  it("has no timer and no wait before it starts", () => {
    const { result } = renderHook(() => useRetryCountdown());

    expect(result.current.remainingSeconds).toBeUndefined();
    expect(result.current.finished).toBe(false);
    expect(result.current.isActive()).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("SS43: counts down from the seconds given, one per second", () => {
    const { result } = renderHook(() => useRetryCountdown());

    act(() => {
      result.current.start(15);
    });
    expect(result.current.remainingSeconds).toBe(15);
    expect(result.current.isActive()).toBe(true);
    expect(vi.getTimerCount()).toBe(1);

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(result.current.remainingSeconds).toBe(12);
  });

  it("SS46: ends at the deadline, clears its interval and reports it finished", () => {
    const { result } = renderHook(() => useRetryCountdown());
    act(() => {
      result.current.start(3);
    });

    act(() => {
      vi.advanceTimersByTime(3000);
    });

    expect(result.current.remainingSeconds).toBeUndefined();
    expect(result.current.finished).toBe(true);
    expect(result.current.isActive()).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("works from the deadline, so a late tick does not stretch the wait", () => {
    const { result } = renderHook(() => useRetryCountdown());
    act(() => {
      result.current.start(10);
    });

    act(() => {
      vi.setSystemTime(Date.now() + 7500);
      vi.advanceTimersByTime(1000);
    });

    expect(result.current.remainingSeconds).toBe(2);
  });

  it("bounds the seconds to at least one and at most an hour", () => {
    const { result } = renderHook(() => useRetryCountdown());

    act(() => {
      result.current.start(100_000);
    });
    expect(result.current.remainingSeconds).toBe(3600);

    act(() => {
      result.current.start(0);
    });
    expect(result.current.remainingSeconds).toBe(1);
  });

  it("clear() ends the wait and forgets that it finished", () => {
    const { result } = renderHook(() => useRetryCountdown());
    act(() => {
      result.current.start(1);
    });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current.finished).toBe(true);

    act(() => {
      result.current.clear();
    });

    expect(result.current.finished).toBe(false);
    expect(result.current.isActive()).toBe(false);
  });

  it("SS43: leaves no interval behind when the component goes away during the wait", () => {
    const { result, unmount } = renderHook(() => useRetryCountdown());
    act(() => {
      result.current.start(30);
    });
    expect(vi.getTimerCount()).toBe(1);

    unmount();

    expect(vi.getTimerCount()).toBe(0);
  });
});

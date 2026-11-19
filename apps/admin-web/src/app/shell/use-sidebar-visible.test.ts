import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SIDEBAR_VISIBLE_STORAGE_KEY, useSidebarVisible } from "./use-sidebar-visible.ts";

describe("useSidebarVisible", () => {
  afterEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it("starts visible and remembers the choice", () => {
    const first = renderHook(() => useSidebarVisible());
    expect(first.result.current.sidebarVisible).toBe(true);

    act(() => {
      first.result.current.setSidebarVisible(false);
    });
    expect(window.localStorage.getItem(SIDEBAR_VISIBLE_STORAGE_KEY)).toBe("false");

    const second = renderHook(() => useSidebarVisible());
    expect(second.result.current.sidebarVisible).toBe(false);
  });

  it("works without storage", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const { result } = renderHook(() => useSidebarVisible());
    expect(result.current.sidebarVisible).toBe(true);

    act(() => {
      result.current.setSidebarVisible(false);
    });
    expect(result.current.sidebarVisible).toBe(false);
  });
});

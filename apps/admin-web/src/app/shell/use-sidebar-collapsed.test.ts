import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SIDEBAR_COLLAPSED_STORAGE_KEY, useSidebarCollapsed } from "./use-sidebar-collapsed.ts";

describe("useSidebarCollapsed", () => {
  afterEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it("starts expanded and remembers the choice", () => {
    const first = renderHook(() => useSidebarCollapsed());
    expect(first.result.current.sidebarCollapsed).toBe(false);

    act(() => {
      first.result.current.setSidebarCollapsed(true);
    });
    expect(window.localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY)).toBe("true");

    const second = renderHook(() => useSidebarCollapsed());
    expect(second.result.current.sidebarCollapsed).toBe(true);
  });

  it("works without storage", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const { result } = renderHook(() => useSidebarCollapsed());
    expect(result.current.sidebarCollapsed).toBe(false);

    act(() => {
      result.current.setSidebarCollapsed(true);
    });
    expect(result.current.sidebarCollapsed).toBe(true);
  });
});

import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  EXPANDED_GROUPS_STORAGE_KEY,
  useExpandedGroups,
} from "./use-expanded-groups.ts";

describe("useExpandedGroups", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("starts closed except for the active group, and opens the next active group", () => {
    const { result, rerender } = renderHook(
      ({ active }) => useExpandedGroups(active),
      { initialProps: { active: "finance" } },
    );
    expect([...result.current.expandedGroupIds]).toEqual(["finance"]);

    rerender({ active: "settings" });
    expect([...result.current.expandedGroupIds].sort()).toEqual([
      "finance",
      "settings",
    ]);
  });

  it("persists a toggle and reads it back, ignoring a malformed value", () => {
    const first = renderHook(() => useExpandedGroups(undefined));
    act(() => {
      first.result.current.toggleGroup("community");
    });
    expect(window.localStorage.getItem(EXPANDED_GROUPS_STORAGE_KEY)).toBe(
      '["community"]',
    );
    first.unmount();

    const second = renderHook(() => useExpandedGroups(undefined));
    expect(second.result.current.expandedGroupIds.has("community")).toBe(true);
    second.unmount();

    window.localStorage.setItem(EXPANDED_GROUPS_STORAGE_KEY, "{not json");
    const third = renderHook(() => useExpandedGroups(undefined));
    expect(third.result.current.expandedGroupIds.size).toBe(0);
  });

  it("works when the storage throws on read and on write", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const { result } = renderHook(() => useExpandedGroups("finance"));
    expect(result.current.expandedGroupIds.has("finance")).toBe(true);

    act(() => {
      result.current.toggleGroup("finance");
    });
    expect(result.current.expandedGroupIds.has("finance")).toBe(false);
  });
});

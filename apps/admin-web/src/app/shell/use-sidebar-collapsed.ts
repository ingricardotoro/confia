import { useCallback, useState } from "react";

/** Where the choice to collapse the side navigation to its rail is remembered, per browser. */
export const SIDEBAR_COLLAPSED_STORAGE_KEY = "confia.admin.sidebar.collapsed";

function readStored(): boolean {
  try {
    return window.localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY) === "true";
  } catch {
    // Storage blocked: the navigation starts expanded.
    return false;
  }
}

function writeStored(collapsed: boolean): void {
  try {
    window.localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, String(collapsed));
  } catch {
    // The choice then lasts only until the page is closed.
  }
}

/**
 * Whether the side navigation is collapsed to its rail on wide screens. Expanded by default; the choice is remembered in
 * the browser and nothing about it goes to the server.
 */
export function useSidebarCollapsed() {
  const [sidebarCollapsed, setCollapsed] = useState<boolean>(readStored);
  const setSidebarCollapsed = useCallback((collapsed: boolean) => {
    setCollapsed(collapsed);
    writeStored(collapsed);
  }, []);
  return { sidebarCollapsed, setSidebarCollapsed };
}

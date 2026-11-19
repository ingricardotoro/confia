import { useCallback, useState } from "react";

/** Where the choice to hide the side navigation is remembered, per browser. */
export const SIDEBAR_VISIBLE_STORAGE_KEY = "confia.admin.sidebar.visible";

function readStored(): boolean {
  try {
    return window.localStorage.getItem(SIDEBAR_VISIBLE_STORAGE_KEY) !== "false";
  } catch {
    // Storage blocked: the navigation starts visible.
    return true;
  }
}

function writeStored(visible: boolean): void {
  try {
    window.localStorage.setItem(SIDEBAR_VISIBLE_STORAGE_KEY, String(visible));
  } catch {
    // The choice then lasts only until the page is closed.
  }
}

/**
 * Whether the side navigation is shown on wide screens. Visible by default; the choice is remembered in the browser
 * and nothing about it goes to the server.
 */
export function useSidebarVisible() {
  const [sidebarVisible, setVisible] = useState<boolean>(readStored);
  const setSidebarVisible = useCallback((visible: boolean) => {
    setVisible(visible);
    writeStored(visible);
  }, []);
  return { sidebarVisible, setSidebarVisible };
}

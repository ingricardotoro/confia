import { useCallback, useState } from "react";

/** Where the open sections are remembered: one list of group ids, per browser. */
export const EXPANDED_GROUPS_STORAGE_KEY = "confia.admin.sidebar.expanded-groups";

function readStored(): readonly string[] {
  try {
    const raw = window.localStorage.getItem(EXPANDED_GROUPS_STORAGE_KEY);
    if (raw === null) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed)
      ? parsed.filter((id): id is string => typeof id === "string")
      : [];
  } catch {
    // Storage blocked, full or holding something else: the navigation starts from its default.
    return [];
  }
}

function writeStored(ids: ReadonlySet<string>): void {
  try {
    window.localStorage.setItem(
      EXPANDED_GROUPS_STORAGE_KEY,
      JSON.stringify([...ids]),
    );
  } catch {
    // The choice then lasts only until the page is closed.
  }
}

/**
 * Which sections of the side navigation are open. Closed by default, except the one that holds the current
 * page, which opens on load and again each time the navigation reaches another section. The choice the person
 * makes with a header is remembered in the browser; nothing about it goes to the server.
 */
export function useExpandedGroups(activeGroupId: string | undefined) {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(
    () =>
      new Set([
        ...readStored(),
        ...(activeGroupId === undefined ? [] : [activeGroupId]),
      ]),
  );
  const [seenActive, setSeenActive] = useState(activeGroupId);

  // Adjusting state while rendering (not in an effect) keeps the new section from showing closed for a frame.
  if (seenActive !== activeGroupId) {
    setSeenActive(activeGroupId);
    if (activeGroupId !== undefined && !expanded.has(activeGroupId)) {
      setExpanded(new Set([...expanded, activeGroupId]));
    }
  }

  const toggle = useCallback((groupId: string) => {
    setExpanded((current) => {
      const next = new Set(current);
      if (!next.delete(groupId)) next.add(groupId);
      writeStored(next);
      return next;
    });
  }, []);

  return { expandedGroupIds: expanded, toggleGroup: toggle };
}

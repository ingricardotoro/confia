import type { AnyRoute } from "@tanstack/react-router";
import type { AdminKey } from "../../i18n/resources.ts";

/**
 * One entry of the side navigation. A feature declares its own and the app registers it, so adding a
 * screen never means editing the shell. The label is a catalog key and not text: the shell resolves it.
 */
export type NavigationGroupId = "community" | "finance" | "settings";

/** The order the side navigation lists its groups in. An entry with no group is listed first, without a label. */
export const NAVIGATION_GROUP_ORDER: readonly NavigationGroupId[] = ["community", "finance", "settings"];

export interface NavigationEntry {
  readonly id: string;
  readonly labelKey: AdminKey;
  /** The path the entry leads to. It is current on this path and on any path below it. */
  readonly to: string;
  /** The section of the side navigation the entry is listed under. Absent: listed first, with no section label. */
  readonly group?: NavigationGroupId;
}

/**
 * What a feature gives the app: its entry in the navigation and its routes. The routes are created
 * under the authenticated shell, so they are already behind the session guard.
 */
export interface FeatureModule {
  readonly navigation: NavigationEntry;
  /** Further entries of the same feature, when it has more than one view that earns its own place in the navigation. */
  readonly moreNavigation?: readonly NavigationEntry[];
  readonly createRoutes: (parent: AnyRoute) => AnyRoute[];
}

/** Whether `entry` is the page being shown: the exact path, or a path below it (the root matches only itself). */
export function isCurrentEntry(entry: Pick<NavigationEntry, "to">, pathname: string): boolean {
  const current = pathname.replace(/\/+$/, "") || "/";
  if (entry.to === "/") return current === "/";
  return current === entry.to || current.startsWith(`${entry.to}/`);
}

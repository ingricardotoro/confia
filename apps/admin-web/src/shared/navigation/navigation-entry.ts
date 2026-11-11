import type { AnyRoute } from "@tanstack/react-router";
import type { AdminKey } from "../../i18n/resources.ts";

/**
 * One entry of the side navigation. A feature declares its own and the app registers it, so adding a
 * screen never means editing the shell. The label is a catalog key and not text: the shell resolves it.
 */
export interface NavigationEntry {
  readonly id: string;
  readonly labelKey: AdminKey;
  /** The path the entry leads to. It is current on this path and on any path below it. */
  readonly to: string;
}

/**
 * What a feature gives the app: its entry in the navigation and its routes. The routes are created
 * under the authenticated shell, so they are already behind the session guard.
 */
export interface FeatureModule {
  readonly navigation: NavigationEntry;
  readonly createRoutes: (parent: AnyRoute) => AnyRoute[];
}

/** Whether `entry` is the page being shown: the exact path, or a path below it (the root matches only itself). */
export function isCurrentEntry(entry: Pick<NavigationEntry, "to">, pathname: string): boolean {
  const current = pathname.replace(/\/+$/, "") || "/";
  if (entry.to === "/") return current === "/";
  return current === entry.to || current.startsWith(`${entry.to}/`);
}

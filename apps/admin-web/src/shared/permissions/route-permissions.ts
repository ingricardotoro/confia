import type { Permission } from "./permission-codes.ts";

/**
 * The permission a part of the address needs. `path` is a prefix: it covers the page and every page below it.
 * A feature declares its own next to its routes, and the shell shows "Sin acceso" instead of a page whose
 * permission is missing. The side navigation and the actions of a screen use the same codes.
 */
export interface RoutePermission {
  readonly path: string;
  readonly permission: Permission;
}

function isBelow(pathname: string, path: string): boolean {
  return pathname === path || pathname.startsWith(`${path}/`);
}

/** The permission `pathname` needs: the one of the longest declared prefix, or none when no declaration covers it. */
export function requiredPermissionFor(pathname: string, guards: readonly RoutePermission[]): Permission | undefined {
  const current = pathname.replace(/\/+$/, "") || "/";
  let best: RoutePermission | undefined;
  for (const guard of guards) {
    if (isBelow(current, guard.path) && (best === undefined || guard.path.length > best.path.length)) best = guard;
  }
  return best?.permission;
}

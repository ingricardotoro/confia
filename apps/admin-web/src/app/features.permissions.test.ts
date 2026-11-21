import { describe, expect, it } from "vitest";
import type { NavigationEntry } from "../shared/navigation/navigation-entry.ts";
import { requiredPermissionFor } from "../shared/permissions/route-permissions.ts";
import { FEATURES } from "./features.ts";

// Deny by default in the interface: a route that no feature declares is open to every signed in person, so every entry of every feature
// (home is the app's, not a feature) names the permission it needs, and a route declaration covers its address with that same permission.
// A new feature that forgets either fails here. The server decides every request anyway.
const entries: readonly NavigationEntry[] = FEATURES.flatMap((feature) => [feature.navigation, ...(feature.moreNavigation ?? [])]);
const guards = FEATURES.flatMap((feature) => feature.routePermissions ?? []);

describe("the permissions the features declare", () => {
  it.each(entries.map((entry) => [entry.id, entry] as const))("%s names its permission and a route declaration covers its address", (_id, entry) => {
    expect(entry.permission).toBeDefined();
    expect(requiredPermissionFor(entry.to, guards)).toBe(entry.permission);
  });
});

import { createRoute, type AnyRoute } from "@tanstack/react-router";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { OrganizationContainer } from "./OrganizationContainer.tsx";

/** The path of the academic structure area. Its views (years, modalities, grades, sections) live below it. */
export const ORGANIZATION_PATH = "/organization";

/**
 * What the academic structure registers with the app: its entry in the side navigation and its routes.
 * The routes are children of the signed in shell, so they are already behind the session guard.
 */
export const organizationFeature: FeatureModule = {
  navigation: { id: "organization", labelKey: "navigation.organization", to: ORGANIZATION_PATH },
  createRoutes: (parent: AnyRoute) => [
    createRoute({ getParentRoute: () => parent, path: ORGANIZATION_PATH, component: OrganizationContainer }),
  ],
};

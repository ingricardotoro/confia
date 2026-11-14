import { enrollmentsFeature } from "../features/enrollments/feature.tsx";
import { organizationFeature } from "../features/organization/feature.tsx";
import { studentsFeature } from "../features/students/feature.tsx";
import type { FeatureModule } from "../shared/navigation/navigation-entry.ts";

/**
 * The features of the app, in the order of the side navigation. A new feature registers here, with
 * its entry and its routes, and the shell picks both up. Home is not a feature: the app owns it.
 */
export const FEATURES: readonly FeatureModule[] = [organizationFeature, studentsFeature, enrollmentsFeature];

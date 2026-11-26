import { cashboxFeature } from "../features/cashbox/feature.tsx";
import { catalogFeature } from "../features/catalog/feature.tsx";
import { chargesFeature } from "../features/charges/feature.tsx";
import { paymentsFeature } from "../features/payments/feature.tsx";
import { enrollmentsFeature } from "../features/enrollments/feature.tsx";
import { guardiansFeature } from "../features/guardians/feature.tsx";
import { importsFeature } from "../features/imports/feature.tsx";
import { ledgerFeature } from "../features/ledger/feature.tsx";
import { scholarshipsFeature } from "../features/scholarships/feature.tsx";
import { organizationFeature } from "../features/organization/feature.tsx";
import { studentsFeature } from "../features/students/feature.tsx";
import type { FeatureModule } from "../shared/navigation/navigation-entry.ts";

/**
 * The features of the app, in the order of the side navigation. A new feature registers here, with
 * its entry and its routes, and the shell picks both up. Home is not a feature: the app owns it.
 */
export const FEATURES: readonly FeatureModule[] = [
  organizationFeature,
  catalogFeature,
  chargesFeature,
  scholarshipsFeature,
  cashboxFeature,
  paymentsFeature,
  studentsFeature,
  enrollmentsFeature,
  guardiansFeature,
  importsFeature,
  ledgerFeature,
];

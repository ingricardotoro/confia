import type { AdminKey } from "../../i18n/resources.ts";
import type { PAYMENT_CATEGORIES } from "./catalog-search.ts";

export type PaymentCategoryValue = (typeof PAYMENT_CATEGORIES)[number];

/** The catalog key of the name of each category, in the order a person reads them. */
export const CATEGORY_LABEL_KEYS: Readonly<Record<PaymentCategoryValue, AdminKey>> = {
  TUITION: "catalog.categories.TUITION",
  ENROLLMENT: "catalog.categories.ENROLLMENT",
  TRANSPORT: "catalog.categories.TRANSPORT",
  MATERIAL: "catalog.categories.MATERIAL",
  FINE: "catalog.categories.FINE",
  OTHER: "catalog.categories.OTHER",
};

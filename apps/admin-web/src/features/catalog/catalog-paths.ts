import type { AdminKey } from "../../i18n/resources.ts";

/** The path of the catalog area. `/catalog` itself is the payment concepts list; the price lists and the tax rates live below it. */
export const CATALOG_PATH = "/catalog";

export const CATALOG_PATHS = {
  concepts: CATALOG_PATH,
  priceLists: `${CATALOG_PATH}/price-lists`,
  taxRates: `${CATALOG_PATH}/tax-rates`,
} as const;

/** The address of one price list. The id is encoded, so it can never change the path. */
export const priceListPath = (id: string): string => `${CATALOG_PATHS.priceLists}/${encodeURIComponent(id)}`;

/** The address of one tax rate. The id is encoded, so it can never change the path. */
export const taxRatePath = (id: string): string => `${CATALOG_PATHS.taxRates}/${encodeURIComponent(id)}`;

export interface CatalogView {
  readonly id: "payment-concepts" | "price-lists" | "tax-rates";
  readonly path: (typeof CATALOG_PATHS)[keyof typeof CATALOG_PATHS];
  readonly labelKey: AdminKey;
}

/** The views of the area, in the order of its sub navigation. */
export const CATALOG_VIEWS: readonly CatalogView[] = [
  { id: "payment-concepts", path: CATALOG_PATHS.concepts, labelKey: "catalog.tabs.paymentConcepts" },
  { id: "price-lists", path: CATALOG_PATHS.priceLists, labelKey: "catalog.tabs.priceLists" },
  { id: "tax-rates", path: CATALOG_PATHS.taxRates, labelKey: "catalog.tabs.taxRates" },
];

/**
 * Whether `view` is the one being shown. The concepts are the area's own path, so they are current only on it; a price
 * list or a tax rate view is current on its path and below it, which covers the detail of a list.
 */
export function isCurrentCatalogView(view: CatalogView, pathname: string): boolean {
  const current = pathname.replace(/\/+$/, "") || "/";
  if (view.path === CATALOG_PATHS.concepts) return current === CATALOG_PATH;
  return current === view.path || current.startsWith(`${view.path}/`);
}

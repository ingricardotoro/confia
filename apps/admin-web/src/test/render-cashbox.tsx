import { cashboxFeature } from "../features/cashbox/feature.tsx";
import { renderFeature, type RenderedFeature } from "./render-feature.tsx";

/** The cashbox as the app mounts it (see `renderFeature`). */
export function renderCashbox(path: string): RenderedFeature {
  return renderFeature(cashboxFeature, path);
}

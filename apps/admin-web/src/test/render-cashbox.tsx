import { cashboxFeature } from "../features/cashbox/feature.tsx";
import { renderFeature, type RenderedFeature, type RenderFeatureOptions } from "./render-feature.tsx";

/** The cashbox as the app mounts it (see `renderFeature`). */
export function renderCashbox(path: string, options: RenderFeatureOptions = {}): RenderedFeature {
  return renderFeature(cashboxFeature, path, options);
}

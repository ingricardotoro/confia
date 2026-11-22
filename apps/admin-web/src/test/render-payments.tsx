import { paymentsFeature } from "../features/payments/feature.tsx";
import { renderFeature, type RenderedFeature, type RenderFeatureOptions } from "./render-feature.tsx";

/** The payments as the app mounts them (see `renderFeature`). */
export function renderPayments(path: string, options: RenderFeatureOptions = {}): RenderedFeature {
  return renderFeature(paymentsFeature, path, options);
}

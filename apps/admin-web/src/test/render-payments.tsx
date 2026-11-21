import { paymentsFeature } from "../features/payments/feature.tsx";
import { renderFeature, type RenderedFeature } from "./render-feature.tsx";

/** The payments as the app mounts them (see `renderFeature`). */
export function renderPayments(path: string): RenderedFeature {
  return renderFeature(paymentsFeature, path);
}

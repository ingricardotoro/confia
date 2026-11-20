import { ledgerFeature } from "../features/ledger/feature.tsx";
import { renderFeature, type RenderFeatureOptions, type RenderedFeature } from "./render-feature.tsx";

/** The chart of accounts as the app mounts it (see `renderFeature`). */
export function renderLedger(path: string, options?: RenderFeatureOptions): RenderedFeature {
  return renderFeature(ledgerFeature, path, options);
}

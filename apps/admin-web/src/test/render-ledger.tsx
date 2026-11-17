import { ledgerFeature } from "../features/ledger/feature.tsx";
import { renderFeature, type RenderedFeature } from "./render-feature.tsx";

/** The chart of accounts as the app mounts it (see `renderFeature`). */
export function renderLedger(path: string): RenderedFeature {
  return renderFeature(ledgerFeature, path);
}

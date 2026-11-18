import { chargesFeature } from "../features/charges/feature.tsx";
import { renderFeature, type RenderedFeature } from "./render-feature.tsx";

/** The charges as the app mounts them (see `renderFeature`). */
export function renderCharges(path: string): RenderedFeature {
  return renderFeature(chargesFeature, path);
}

import { scholarshipsFeature } from "../features/scholarships/feature.tsx";
import { renderFeature, type RenderFeatureOptions, type RenderedFeature } from "./render-feature.tsx";

/** The scholarships as the app mounts them (see `renderFeature`). */
export function renderScholarships(path: string, options?: RenderFeatureOptions): RenderedFeature {
  return renderFeature(scholarshipsFeature, path, options);
}

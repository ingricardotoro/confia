import { catalogFeature } from "../features/catalog/feature.tsx";
import { renderFeature, type RenderFeatureOptions, type RenderedFeature } from "./render-feature.tsx";

/** The catalog as the app mounts it (see `renderFeature`). */
export function renderCatalog(path: string, options?: RenderFeatureOptions): RenderedFeature {
  return renderFeature(catalogFeature, path, options);
}

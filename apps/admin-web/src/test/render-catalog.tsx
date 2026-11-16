import { catalogFeature } from "../features/catalog/feature.tsx";
import { renderFeature, type RenderedFeature } from "./render-feature.tsx";

/** The catalog as the app mounts it (see `renderFeature`). */
export function renderCatalog(path: string): RenderedFeature {
  return renderFeature(catalogFeature, path);
}

import { organizationFeature } from "../features/organization/feature.tsx";
import { renderFeature, type RenderFeatureOptions, TEST_TOKEN, type RenderedFeature } from "./render-feature.tsx";

export { TEST_TOKEN };

export type RenderedOrganization = RenderedFeature;

/** The academic structure as the app mounts it (see `renderFeature`). */
export function renderOrganization(path: string, options?: RenderFeatureOptions): RenderedOrganization {
  return renderFeature(organizationFeature, path, options);
}

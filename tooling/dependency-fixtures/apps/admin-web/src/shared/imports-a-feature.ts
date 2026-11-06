// Deliberate violation of admin-shared-never-imports-features: shared code reaching into a feature.
import { container } from "../features/identity/container.ts";

export const leaked = container;

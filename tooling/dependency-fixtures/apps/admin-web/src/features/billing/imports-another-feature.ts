// Deliberate violation of admin-features-never-import-features: a feature reaching into another feature.
import { container } from "../identity/container.ts";

export const leaked = container;

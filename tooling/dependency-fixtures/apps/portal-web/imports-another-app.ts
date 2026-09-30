// Deliberate violation of apps-never-import-other-apps: the portal reaching into the admin panel.
import { adminWebEntry } from "../admin-web/main.ts";

export const leaked = adminWebEntry;

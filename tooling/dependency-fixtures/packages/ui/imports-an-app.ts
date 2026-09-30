// Deliberate violation of packages-never-import-apps: a package reaching into an application.
import { adminWebEntry } from "../../apps/admin-web/main.ts";

export const leaked = adminWebEntry;

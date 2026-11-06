import { join } from "node:path";
import { createTextKeyChecker } from "@confia/config/text-keys";

// The checks themselves live in @confia/config, shared with apps/admin-web (admin-web-foundations-and-
// session, task 3). This file only says where the package root is and keeps the names the tests import.
export { flattenCatalogKeys, type KeyDiagnostic, type UsedTextKey } from "@confia/config/text-keys";

export const { collectTextKeys, checkVirtualFile } = createTextKeyChecker(join(import.meta.dirname, "..", ".."));

import { join } from "node:path";
import { createTextKeyChecker } from "@confia/config/text-keys";

export { flattenCatalogKeys, type KeyDiagnostic, type UsedTextKey } from "@confia/config/text-keys";

// The checker is shared with packages/ui; the app only says where its own package root is.
export const { collectTextKeys, checkVirtualFile } = createTextKeyChecker(join(import.meta.dirname, "..", ".."));

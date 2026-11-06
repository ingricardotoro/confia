// Deliberate violation of admin-presentation-is-pure (BX59): presentation importing shared/api by an
// alias. The fixture tree has no tsconfig paths, so the rule matches the name as written.
import { client } from "@/shared/api/client";

export const leaked = client;

// Deliberate violation of admin-presentation-is-pure (BX59): presentation importing shared/api by a
// relative path.
import { client } from "../../../shared/api/client.ts";

export const leaked = client;

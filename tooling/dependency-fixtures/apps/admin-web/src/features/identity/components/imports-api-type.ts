// Deliberate violation of admin-presentation-is-pure (BX60): even a type from shared/api is refused.
import type { Client } from "../../../shared/api/client.ts";

export type Leaked = Client;

// Deliberate violation of admin-session-lifecycle-is-private (admin-web-shell-and-session-lifecycle, H16):
// outside src/features/identity/ only session/lifecycle/index.ts may be imported, never an internal file.
import { planner } from "../identity/session/lifecycle/planner-imports-react.ts";

export const lifecycleInternals = planner;

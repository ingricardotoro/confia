// Deliberate violation of admin-session-lifecycle-planner-is-pure (admin-web-shell-and-session-lifecycle, H16):
// the planner is pure, so it imports nothing from outside its own folder, not even React.
import { useState } from "react";

export const planner = useState;

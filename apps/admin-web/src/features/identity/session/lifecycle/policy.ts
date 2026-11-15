/**
 * The policy of the session lifecycle, as named constants (design.md H2). They are fixed in the client
 * until the contract exposes them (assumption D4). The 30 minutes of idleness and the 12 hours of the
 * session are not here: they arrive in every response as deadlines, and the client knows only how long
 * before them to warn and how often activity may ask for a refresh.
 */

/** How long before the idle deadline the idle warning opens. */
export const IDLE_WARNING_LEAD_MS = 5 * 60_000;

/** How long before the absolute deadline the absolute warning opens. */
export const ABSOLUTE_WARNING_LEAD_MS = 5 * 60_000;

/** The least time between two refreshes asked for by activity, per tab. */
export const ACTIVITY_CADENCE_MS = 60_000;

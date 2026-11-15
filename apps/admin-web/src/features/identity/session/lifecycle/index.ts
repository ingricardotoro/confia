import { useSyncExternalStore } from "react";
import type { LifecycleScheduler, LifecycleSnapshot } from "./scheduler.ts";

/**
 * The public surface of the session lifecycle (design.md H16): the hook that reads the phase, and the
 * types. Every other file of this folder is private to `features/identity/`.
 */
export function usePhase(scheduler: LifecycleScheduler): LifecycleSnapshot {
  return useSyncExternalStore(scheduler.subscribe, scheduler.getSnapshot);
}

export type { LifecyclePhase } from "./planner.ts";
export type { LifecycleScheduler, LifecycleSnapshot } from "./scheduler.ts";

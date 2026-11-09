import { useEffect, useRef, useState } from "react";

const MIN_SECONDS = 1;
const MAX_SECONDS = 3600;

export interface RetryCountdown {
  /** Whole seconds left, rounded up; undefined when no wait is running. */
  readonly remainingSeconds: number | undefined;
  /** The wait ran out on its own and has not been cleared since. */
  readonly finished: boolean;
  start(seconds: number): void;
  clear(): void;
  /** Read at the moment of a submit, so it does not depend on the last render. */
  isActive(): boolean;
}

/** Reads the clock when called, not when the module loads, so simulated timers in a test also move it. */
const systemNow = () => Date.now();

/**
 * The wait after a 429 (design.md E6). The deadline is fixed when the wait starts, so a late tick does not stretch
 * it, and the one-second interval exists only while it lasts: it is cleared when the wait ends and on unmount.
 */
export function useRetryCountdown(now: () => number = systemNow): RetryCountdown {
  const [deadline, setDeadline] = useState<number>();
  const [remainingSeconds, setRemainingSeconds] = useState<number>();
  const [finished, setFinished] = useState(false);
  const deadlineNow = useRef<number>(undefined);

  useEffect(() => {
    if (deadline === undefined) return;
    const timer = setInterval(() => {
      const left = Math.ceil((deadline - now()) / 1000);
      if (left > 0) {
        setRemainingSeconds(left);
        return;
      }
      deadlineNow.current = undefined;
      setDeadline(undefined);
      setRemainingSeconds(undefined);
      setFinished(true);
    }, 1000);
    return () => {
      clearInterval(timer);
    };
  }, [deadline, now]);

  return {
    remainingSeconds,
    finished,
    start(seconds) {
      const bounded = Math.min(Math.max(Math.ceil(seconds), MIN_SECONDS), MAX_SECONDS);
      deadlineNow.current = now() + bounded * 1000;
      setDeadline(deadlineNow.current);
      setRemainingSeconds(bounded);
      setFinished(false);
    },
    clear() {
      deadlineNow.current = undefined;
      setDeadline(undefined);
      setRemainingSeconds(undefined);
      setFinished(false);
    },
    isActive() {
      return deadlineNow.current !== undefined && now() < deadlineNow.current;
    },
  };
}

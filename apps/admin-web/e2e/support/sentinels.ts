import { randomUUID } from "node:crypto";

/**
 * A value that exists only for one test run, to be searched for where it must never appear: a log line, a
 * report, a recorded request. Nothing is written in the repository (CLAUDE.md rule 13).
 */
export function makeSentinel(label: string): string {
  return `sentinel-${label}-${randomUUID()}`;
}

/** The labels whose value appears in `text`. It returns labels and never values (CLAUDE.md rule 11). */
export function findLeaks(text: string, sentinels: Readonly<Record<string, string>>): string[] {
  return Object.entries(sentinels)
    .filter(([, value]) => text.includes(value))
    .map(([label]) => label);
}

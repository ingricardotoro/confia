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

/**
 * A value in the forms a browser may keep it in (as typed, URI encoded, base64, base64url, hex and JSON escaped), each under
 * a label that says which, ready for `findLeaks`. A secret that left the page in one of these forms is a leak all the same.
 */
export function variantsOf(label: string, value: string): Record<string, string> {
  const bytes = Buffer.from(value);
  return {
    [label]: value,
    [`${label}, URI encoded`]: encodeURIComponent(value),
    [`${label}, base64`]: bytes.toString("base64"),
    [`${label}, base64url`]: bytes.toString("base64url"),
    [`${label}, hex`]: bytes.toString("hex"),
    [`${label}, JSON escaped`]: JSON.stringify(value).slice(1, -1),
  };
}

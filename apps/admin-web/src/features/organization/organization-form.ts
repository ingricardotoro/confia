import type { AccessFieldSpec } from "../../shared/forms/use-access-form.ts";

/**
 * The spec of each field of a form for `useAccessForm`: the fixed id of its control, its starting value, and a
 * trim for the fields listed in `trimmed`, so what is validated, sent and kept is the same text.
 */
export function fieldSpecs<F extends string>(
  controlIds: Readonly<Record<F, string>>,
  initial: Readonly<Record<F, string>>,
  trimmed: readonly NoInfer<F>[],
): Readonly<Record<F, AccessFieldSpec>> {
  const names = Object.keys(controlIds) as F[];
  return Object.fromEntries(
    names.map((name) => [
      name,
      { controlId: controlIds[name], initialValue: initial[name], ...(trimmed.includes(name) ? { normalize: (raw: string) => raw.trim() } : {}) },
    ]),
  ) as Record<F, AccessFieldSpec>;
}

/** `"true"` and `"false"` are what a select holds for the state of a record. */
export const activeValueOf = (active: boolean): string => (active ? "true" : "false");

/** A whole number typed as text, or the text itself when it is not one, so the schema refuses it. */
export function wholeNumberOf(raw: string): number | string {
  return /^\d{1,9}$/.test(raw) ? Number(raw) : raw;
}

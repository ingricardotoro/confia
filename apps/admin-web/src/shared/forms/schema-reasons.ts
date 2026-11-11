import type { FieldReason } from "./use-access-form.ts";

/** What a Zod schema reports when it refuses a value; the part this module reads. */
export interface SchemaIssue {
  readonly code: string;
  readonly path: readonly PropertyKey[];
}

export interface ValidationSchema {
  safeParse(input: unknown): { readonly success: true } | { readonly success: false; readonly error: { readonly issues: readonly SchemaIssue[] } };
}

/**
 * The reason of each field that a generated schema refuses, from the first issue of each (design.md E4). A field in
 * `numeric` gets that reason for any issue, because a number typed as text breaks the schema in several ways and
 * the person needs one message. Otherwise a value that is too long is `too-long`, a missing one is `required`
 * and anything else is `format`. Reasons, not texts: the form hook turns them into catalog keys.
 */
export function reasonsFromSchema<F extends string>(
  schema: ValidationSchema,
  candidate: unknown,
  fields: readonly F[],
  numeric: Partial<Record<F, FieldReason>> = {},
): Partial<Record<F, FieldReason>> {
  const reasons: Partial<Record<F, FieldReason>> = {};
  const parsed = schema.safeParse(candidate);
  if (parsed.success) return reasons;
  for (const issue of parsed.error.issues) {
    const field = issue.path[0];
    if (typeof field !== "string" || !fields.includes(field as F) || reasons[field as F] !== undefined) continue;
    const forNumber = numeric[field as F];
    if (forNumber !== undefined) reasons[field as F] = forNumber;
    else if (issue.code === "too_big") reasons[field as F] = "too-long";
    else reasons[field as F] = issue.code === "invalid_type" ? "required" : "format";
  }
  return reasons;
}

/** `required` for each of `fields` whose value is empty. Blank counts as empty: the contract only bounds the length. */
export function requiredReasons<F extends string>(values: Readonly<Record<F, string>>, fields: readonly F[]): Partial<Record<F, FieldReason>> {
  const reasons: Partial<Record<F, FieldReason>> = {};
  for (const field of fields) {
    if (values[field].trim() === "") reasons[field] = "required";
  }
  return reasons;
}

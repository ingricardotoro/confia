import { LoginRequest } from "@confia/contracts/admin";
import type { FieldReason } from "../../../shared/forms/use-access-form.ts";

export type SignInField = "identifier" | "password";

/** The fixed `id` of each control, which the error summary links to. */
export const SIGN_IN_CONTROL_IDS: Readonly<Record<SignInField, string>> = {
  identifier: "sign-in-identifier",
  password: "sign-in-password",
};

/**
 * The generated request with the one restriction the contract lacks: both fields are required, so an empty one is
 * refused before anything is sent. Nothing is declared from scratch, so the maximums (320 and 1 024) are the contract's
 * and move with it (design.md E4, SS51).
 */
export const signInSchema = LoginRequest.extend({
  identifier: LoginRequest.shape.identifier.min(1),
  password: LoginRequest.shape.password.min(1),
});

/**
 * The only change made to the identifier before it is validated, sent and kept for the enrollment: white space at
 * its ends. No NFKC and no lower case, because the server must receive it as it was typed (SS58). The password is
 * never transformed.
 */
export const normalizeIdentifier = (raw: string): string => raw.trim();

/** The reason of each field that breaks the schema, from the first issue of each. Reasons, not texts. */
export function validateSignIn(values: Readonly<Record<SignInField, string>>): Partial<Record<SignInField, FieldReason>> {
  const reasons: Partial<Record<SignInField, FieldReason>> = {};
  const parsed = signInSchema.safeParse(values);
  if (parsed.success) return reasons;
  for (const issue of parsed.error.issues) {
    const field = issue.path[0];
    if ((field === "identifier" || field === "password") && reasons[field] === undefined) {
      reasons[field] = issue.code === "too_big" ? "too-long" : "required";
    }
  }
  return reasons;
}

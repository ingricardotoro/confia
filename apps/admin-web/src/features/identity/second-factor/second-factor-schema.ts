import { SecondFactorVerificationRequest } from "@confia/contracts/admin";
import type { FieldReason } from "../../../shared/forms/use-access-form.ts";

/** The two ways to prove the second factor. The screen offers one at a time (design.md E9). */
export type SecondFactorMode = "code" | "recovery-code";

export type SecondFactorField = "code" | "recoveryCode";

/** The fixed `id` of each control, which the error summary links to. */
export const SECOND_FACTOR_CONTROL_IDS: Readonly<Record<SecondFactorField, string>> = {
  code: "second-factor-code",
  recoveryCode: "second-factor-recovery-code",
};

/**
 * The generated fields with the one restriction each lacks (design.md E4): the TOTP code is exactly six digits, and a
 * recovery code has at least one character, up to the maximum of the contract (64). Nothing is declared from scratch,
 * so the maximums move with the contract.
 */
export const totpCodeSchema = SecondFactorVerificationRequest.shape.code.unwrap().regex(/^\d{6}$/);
export const recoveryCodeSchema = SecondFactorVerificationRequest.shape.recoveryCode.unwrap().min(1);

// White space, plus the zero width characters a paste from another application can carry.
const SPACES = /[\s\u200B-\u200D\u2060\uFEFF]/g;

/** What is typed or pasted in the TOTP field, before it is validated and sent (SS62). */
export const normalizeCode = (raw: string): string => raw.replace(SPACES, "");

/** The same, and capitals (SS65). The alphabet of a recovery code is not checked here. */
export const normalizeRecoveryCode = (raw: string): string => raw.replace(SPACES, "").toUpperCase();

/** The reason of the field of the mode in view, from the first issue of its schema. The other field is not looked at. */
export function validateSecondFactor(
  mode: SecondFactorMode,
  values: Readonly<Record<SecondFactorField, string>>,
): Partial<Record<SecondFactorField, FieldReason>> {
  const field: SecondFactorField = mode === "code" ? "code" : "recoveryCode";
  const value = values[field];
  if (value === "") return { [field]: "required" };
  const parsed = (mode === "code" ? totpCodeSchema : recoveryCodeSchema).safeParse(value);
  if (parsed.success) return {};
  return { [field]: parsed.error.issues.some((issue) => issue.code === "too_big") ? "too-long" : "format" };
}

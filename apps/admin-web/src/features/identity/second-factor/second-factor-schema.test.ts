import { SecondFactorVerificationRequest } from "@confia/contracts/admin";
import { describe, expect, it } from "vitest";
import {
  normalizeCode,
  normalizeRecoveryCode,
  recoveryCodeSchema,
  totpCodeSchema,
  validateSecondFactor,
} from "./second-factor-schema.ts";

// admin-web-staff-sign-in, task 5.1 (E4, E9; SS62, SS63, SS66). The schemas of the screen extend the generated ones with
// the one restriction each lacks, and the normalization is a separate, pure function applied before validating.
describe("the code schemas", () => {
  it("E4: both extend the optional fields of the generated request, so the contract moves them", () => {
    expect(Object.keys(SecondFactorVerificationRequest.shape)).toEqual(["code", "recoveryCode"]);
    expect(SecondFactorVerificationRequest.safeParse({ code: "abc" }).success).toBe(true);
  });

  it("SS63: a TOTP code is exactly six digits", () => {
    expect(totpCodeSchema.safeParse("123456").success).toBe(true);
    for (const wrong of ["12345", "1234567", "12a456", "", "١٢٣٤٥٦"]) {
      expect(totpCodeSchema.safeParse(wrong).success, wrong).toBe(false);
    }
  });

  it("SS66: a recovery code is one to 64 characters of any kind", () => {
    expect(recoveryCodeSchema.safeParse("A").success).toBe(true);
    expect(recoveryCodeSchema.safeParse("é-€ 1".repeat(10)).success).toBe(true);
    expect(recoveryCodeSchema.safeParse("A".repeat(64)).success).toBe(true);
    expect(recoveryCodeSchema.safeParse("").success).toBe(false);
    expect(recoveryCodeSchema.safeParse("A".repeat(65)).success).toBe(false);
  });
});

describe("normalizeCode", () => {
  it("SS62: removes spaces, tabs and line breaks anywhere", () => {
    expect(normalizeCode("123 456")).toBe("123456");
    expect(normalizeCode(" 12\t34\n56 ")).toBe("123456");
  });

  it("SS62: removes the zero width characters a paste can carry", () => {
    expect(normalizeCode("1\u200B2\u200C3\u200D4\u20605\uFEFF6")).toBe("123456");
  });

  it("does not change case or digits", () => {
    expect(normalizeCode("12a456")).toBe("12a456");
  });
});

describe("normalizeRecoveryCode", () => {
  it("SS65: removes the same characters and passes to capitals, and nothing else", () => {
    expect(normalizeRecoveryCode(" ab-cd\u200B ef12 ")).toBe("AB-CDEF12");
  });
});

describe("validateSecondFactor", () => {
  it("SS63: a TOTP code of six digits has no reason, and only the field of its mode is looked at", () => {
    expect(validateSecondFactor("code", { code: "123456", recoveryCode: "" })).toEqual({});
  });

  it("SS63: an empty code is required and one that is not six digits has a wrong format", () => {
    expect(validateSecondFactor("code", { code: "", recoveryCode: "" })).toEqual({ code: "required" });
    for (const wrong of ["12345", "1234567", "12a456"]) {
      expect(validateSecondFactor("code", { code: wrong, recoveryCode: "" }), wrong).toEqual({ code: "format" });
    }
  });

  it("SS63: a code over the maximum of the contract is too long", () => {
    expect(validateSecondFactor("code", { code: "1".repeat(65), recoveryCode: "" })).toEqual({ code: "too-long" });
  });

  it("SS66: an empty recovery code is required, and the TOTP field is not looked at in that mode", () => {
    expect(validateSecondFactor("recovery-code", { code: "bad", recoveryCode: "" })).toEqual({ recoveryCode: "required" });
    expect(validateSecondFactor("recovery-code", { code: "bad", recoveryCode: "ABCD" })).toEqual({});
  });

  it("SS66: a recovery code over 64 characters is too long", () => {
    expect(validateSecondFactor("recovery-code", { code: "", recoveryCode: "A".repeat(65) })).toEqual({ recoveryCode: "too-long" });
  });
});

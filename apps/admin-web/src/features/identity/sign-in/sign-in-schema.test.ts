import { LoginRequest } from "@confia/contracts/admin";
import { describe, expect, it } from "vitest";
import { normalizeIdentifier, signInSchema, validateSignIn } from "./sign-in-schema.ts";

// admin-web-staff-sign-in, task 3.1 (E4; SS37, SS51, SS58). The schema of the screen is the generated one with
// the one restriction the contract lacks, and the normalization is a separate, pure function.
const valid = { identifier: "ana@colegio.edu.hn", password: "not-a-real-password" };

describe("the sign in schema", () => {
  it("E4: is derived from the generated LoginRequest and declares the same two fields", () => {
    expect(Object.keys(signInSchema.shape)).toEqual(Object.keys(LoginRequest.shape));
  });

  it("E4: adds only the minimum of one to each field, so an empty one is refused and any other accepted", () => {
    expect(LoginRequest.safeParse({ identifier: "", password: "" }).success).toBe(true);

    expect(signInSchema.safeParse({ identifier: "", password: "x" }).success).toBe(false);
    expect(signInSchema.safeParse({ identifier: "x", password: "" }).success).toBe(false);
    expect(signInSchema.safeParse({ identifier: "x", password: "x" }).success).toBe(true);
  });

  it("SS51: keeps the maximums of the contract, 320 and 1 024, and no other", () => {
    expect(signInSchema.safeParse({ identifier: "a".repeat(320), password: "b".repeat(1_024) }).success).toBe(true);
    expect(signInSchema.safeParse({ identifier: "a".repeat(321), password: "b" }).success).toBe(false);
    expect(signInSchema.safeParse({ identifier: "a", password: "b".repeat(1_025) }).success).toBe(false);
  });

  it("E4: does not transform the password, and spaces at its ends are kept", () => {
    expect(signInSchema.parse({ identifier: "a", password: "  Secret value  " }).password).toBe("  Secret value  ");
  });
});

describe("validateSignIn", () => {
  it("SS37: returns no reason for valid values", () => {
    expect(validateSignIn(valid)).toEqual({});
  });

  it("SS37: an empty field is required, one by one", () => {
    expect(validateSignIn({ identifier: "", password: "" })).toEqual({ identifier: "required", password: "required" });
    expect(validateSignIn({ identifier: "", password: "x" })).toEqual({ identifier: "required" });
    expect(validateSignIn({ identifier: "x", password: "" })).toEqual({ password: "required" });
  });

  it("SS51: a value over the maximum of the contract is too long", () => {
    expect(validateSignIn({ identifier: "a".repeat(321), password: "b".repeat(1_025) })).toEqual({
      identifier: "too-long",
      password: "too-long",
    });
  });

  it("SS51: a value at the maximum is accepted", () => {
    expect(validateSignIn({ identifier: "a".repeat(320), password: "b".repeat(1_024) })).toEqual({});
  });
});

describe("normalizeIdentifier", () => {
  it("SS58: drops white space at the ends only", () => {
    expect(normalizeIdentifier("  ana@colegio.edu.hn \t\n")).toBe("ana@colegio.edu.hn");
    expect(normalizeIdentifier("ana pérez@colegio.edu.hn")).toBe("ana pérez@colegio.edu.hn");
  });

  it("SS58: keeps the case and does not apply NFKC", () => {
    expect(normalizeIdentifier(" Ana.Pérez+Admin@Colegio.edu.hn ")).toBe("Ana.Pérez+Admin@Colegio.edu.hn");
    // The fullwidth commercial at and the ligature would change under NFKC; they must reach the server as typed.
    expect(normalizeIdentifier("ana＠colegio.edu.hn")).toBe("ana＠colegio.edu.hn");
    expect(normalizeIdentifier("ﬁrma@colegio.edu.hn")).toBe("ﬁrma@colegio.edu.hn");
  });

  it("SS37: a value of white space only becomes empty", () => {
    expect(normalizeIdentifier("   ")).toBe("");
  });
});

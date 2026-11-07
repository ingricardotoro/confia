// @vitest-environment node
import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { base32Decode, base32Encode, isTotpValid, totpCode } from "./totp.ts";

// admin-web-foundations-and-session, task 11.1 (SS119). The test authenticator: RFC 6238 with SHA-1,
// 30 second steps and six digits, using node:crypto and nothing else. The only fixed secret is the RFC
// reference key; every other secret in these tests is made at run time.
const RFC_KEY = new TextEncoder().encode("12345678901234567890");

describe("SS119: the code matches RFC 6238", () => {
  it("gives 287082 for the reference key at 59 seconds", () => {
    expect(totpCode(RFC_KEY, 59)).toBe("287082");
  });

  it.each([
    [1111111109, "081804"],
    [1111111111, "050471"],
    [1234567890, "005924"],
    [2000000000, "279037"],
  ] as const)("matches the other SHA-1 reference times (%i) with the leading zeros kept", (seconds, code) => {
    expect(totpCode(RFC_KEY, seconds)).toBe(code);
  });

  it("changes at the step boundary and not before it", () => {
    expect(totpCode(RFC_KEY, 30)).toBe(totpCode(RFC_KEY, 59));
    expect(totpCode(RFC_KEY, 29)).not.toBe(totpCode(RFC_KEY, 30));
  });

  it("imports nothing but node:crypto", () => {
    const source = readFileSync(join(import.meta.dirname, "totp.ts"), "utf8");
    const specifiers = [...source.matchAll(/(?:from|import)\s*\(?\s*["']([^"']+)["']/g)].map((match) => match[1]);

    expect(specifiers).toEqual(["node:crypto"]);
  });
});

describe("the acceptance window", () => {
  const key = randomBytes(20);
  const at = 1_700_000_010;

  it("accepts the current code and one step either side", () => {
    expect(isTotpValid(key, totpCode(key, at), at)).toBe(true);
    expect(isTotpValid(key, totpCode(key, at - 30), at)).toBe(true);
    expect(isTotpValid(key, totpCode(key, at + 30), at)).toBe(true);
  });

  it("refuses two steps away, a wrong code and a code of another length", () => {
    expect(isTotpValid(key, totpCode(key, at - 60), at)).toBe(false);
    expect(isTotpValid(key, totpCode(key, at + 60), at)).toBe(false);
    const inWindow = [at - 30, at, at + 30].map((time) => totpCode(key, time));
    const wrong = Array.from({ length: 10 }, (_, digit) => String(digit).repeat(6)).find((code) => !inWindow.includes(code)) ?? "";
    expect(isTotpValid(key, wrong, at)).toBe(false);
    expect(isTotpValid(key, `${totpCode(key, at)}0`, at)).toBe(false);
    expect(isTotpValid(key, "", at)).toBe(false);
  });
});

describe("base32 as the authenticator reads it", () => {
  it("encodes without padding, as RFC 4648 gives", () => {
    expect(base32Encode(new TextEncoder().encode("foo"))).toBe("MZXW6");
    expect(new TextDecoder().decode(base32Decode("MZXW6"))).toBe("foo");
  });

  it("round trips twenty random bytes into the thirty two characters the contract asks for", () => {
    const bytes = randomBytes(20);
    const text = base32Encode(bytes);

    expect(text).toMatch(/^[A-Z2-7]{32}$/);
    expect(Buffer.from(base32Decode(text)).equals(bytes)).toBe(true);
  });

  it("refuses a character outside the alphabet without echoing the input", () => {
    expect(() => base32Decode("MZXW1")).toThrow("not base32");
    expect(() => base32Decode("mzxw6")).toThrow("not base32");
  });
});

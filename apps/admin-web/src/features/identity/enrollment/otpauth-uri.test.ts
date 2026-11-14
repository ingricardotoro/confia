import { describe, expect, it } from "vitest";
import { buildOtpauthUri } from "./otpauth-uri.ts";

// admin-web-staff-sign-in, task 7.4 (D11; SS79, SS80, I311). Pure function over strings. The secret is made up for the test.
const SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
const IDENTIFIER = "ana.pérez+admin@colegio.edu.hn";

describe("buildOtpauthUri", () => {
  it("SS79: is an otpauth totp URI with the secret as given, the issuer CONFIA and the identifier in the label", () => {
    const uri = new URL(buildOtpauthUri(IDENTIFIER, SECRET));

    expect(uri.protocol).toBe("otpauth:");
    expect(uri.hostname).toBe("totp");
    expect(uri.searchParams.get("secret")).toBe(SECRET);
    expect(uri.searchParams.get("issuer")).toBe("CONFIA");
    expect(decodeURIComponent(uri.pathname.slice(1))).toBe(`CONFIA:${IDENTIFIER}`);
  });

  it("SS80, I311: the colon, the space and the percent sign of an identifier are encoded and the URI has no space", () => {
    const uri = buildOtpauthUri("a:b c%d", SECRET);

    expect(uri).toContain("CONFIA:a%3Ab%20c%25d?");
    expect(uri).not.toContain(" ");
  });
});

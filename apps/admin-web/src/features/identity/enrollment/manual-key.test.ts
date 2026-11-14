import { describe, expect, it } from "vitest";
import { groupManualKey } from "./manual-key.ts";

// admin-web-staff-sign-in, task 7.4 (SS77). The key is shown in groups of four and copied without them.
const SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

describe("groupManualKey", () => {
  it("SS77: shows 32 characters as eight groups of four separated by one space", () => {
    const grouped = groupManualKey(SECRET);

    expect(grouped).toBe("JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP");
    expect(grouped.split(" ")).toHaveLength(8);
  });

  it("SS77: the value to copy is the secret itself, without spaces", () => {
    expect(groupManualKey(SECRET).replaceAll(" ", "")).toBe(SECRET);
    expect(SECRET).toHaveLength(32);
  });

  it("keeps a last group shorter than four", () => {
    expect(groupManualKey("ABCDEFGHIJ")).toBe("ABCD EFGH IJ");
  });
});

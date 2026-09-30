import { ESLint } from "eslint";
import { describe, expect, it } from "vitest";
import { contractImportRestrictions } from "@confia/config/eslint";

// Task 3.1: ESLint mirrors contracts-only-through-public-entry and
// portal-never-imports-admin-contracts, so the editor flags them before CI does.
async function restrictedImportMessages(code: string, portal: boolean): Promise<string[]> {
  const eslint = new ESLint({
    overrideConfigFile: true,
    overrideConfig: [contractImportRestrictions({ portal })],
  });
  const [result] = await eslint.lintText(code, { filePath: "example.js" });
  return (result?.messages ?? [])
    .filter((message) => message.ruleId === "no-restricted-imports")
    .map((message) => message.message);
}

describe("the ESLint mirror of the contract rules", () => {
  it("rejects a generated contract file imported directly", async () => {
    const messages = await restrictedImportMessages(
      'import { Money } from "@confia/contracts/src/generated/admin/schemas.js";',
      false,
    );
    expect(messages).toHaveLength(1);
  });

  it("rejects the administrative contract in the portal, and only there", async () => {
    const code = 'import { Money } from "@confia/contracts/admin";';
    expect(await restrictedImportMessages(code, true)).toHaveLength(1);
    expect(await restrictedImportMessages(code, false)).toEqual([]);
  });

  it("accepts the public entry points", async () => {
    expect(
      await restrictedImportMessages('import { Money } from "@confia/contracts/portal";', true),
    ).toEqual([]);
  });
});

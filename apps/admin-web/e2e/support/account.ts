import { expect, type APIRequestContext } from "@playwright/test";
import { MOCK_API_URL } from "./scenario-header.ts";
import { base32Decode, totpCode } from "./totp.ts";

/**
 * The fictitious account of a scenario of the simulated API, as its local control hands it out: the key an
 * authenticator would hold and the recovery codes still valid. Test support only; the values exist for one run.
 */
export interface TestAccount {
  readonly key: Uint8Array;
  readonly recoveryCodes: readonly string[];
}

export async function accountOf(request: APIRequestContext, scenarioId: string): Promise<TestAccount> {
  const reply = await request.get(`${MOCK_API_URL}/__control/account/${scenarioId}`);
  expect(reply.status()).toBe(200);
  const body = (await reply.json()) as { secret: string; recoveryCodes: string[] };
  return { key: base32Decode(body.secret), recoveryCodes: body.recoveryCodes };
}

/** The code an authenticator shows now. */
export const currentCode = (account: TestAccount): string => totpCode(account.key, Date.now() / 1000);

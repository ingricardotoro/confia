import { describe, expectTypeOf, it } from "vitest";
import type { SecondFactorProof } from "../../../shared/api/auth-operations.ts";
import type { AccessSession, VerifyOutcome } from "./access-session.ts";

// admin-web-staff-sign-in, task 1.1 (SS72): the first code of an enrollment goes only as a TOTP code. The
// expect-error lines below are permanent, deliberate violations: if `activate` ever accepts a recovery code
// or a proof, tsc reports an unused directive and the typecheck fails.
describe("activate", () => {
  it("takes one string and answers a verification outcome", () => {
    expectTypeOf<AccessSession["activate"]>().toEqualTypeOf<(code: string) => Promise<VerifyOutcome>>();
  });

  it("SS72: a recovery code does not compile", (): void => {
    const session = null as unknown as AccessSession;
    // @ts-expect-error ADR-0018 (SS72): deliberate violation: activate takes no recovery code
    void session.activate({ recoveryCode: "abcde12345" });
  });

  it("SS72: a second factor proof does not compile", (): void => {
    const session = null as unknown as AccessSession;
    const proof: SecondFactorProof = { kind: "recovery-code", value: "abcde12345" };
    // @ts-expect-error ADR-0018 (SS72): deliberate violation: activate takes no proof
    void session.activate(proof);
  });
});

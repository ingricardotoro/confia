import { describe, expect, it } from "vitest";
import { createEnrollmentDraft } from "./enrollment-draft.ts";

// admin-web-staff-sign-in, task 1.1 (E2; SS58, SS59, SS93, SS98): the draft of an enrollment, in memory
// of the session module. Every secret below is made at run time; none is written in this file.
const secret = () => crypto.randomUUID().replaceAll("-", "").toUpperCase();
const codes = () => Array.from({ length: 3 }, () => crypto.randomUUID().slice(0, 10));

describe("the enrollment draft", () => {
  it("SS58: keeps the identifier, trimmed, and shows no snapshot until the secret arrives", () => {
    const draft = createEnrollmentDraft();
    expect(draft.snapshot()).toBeUndefined();
    expect(draft.identifier()).toBeUndefined();

    draft.begin("  ana.pérez+admin@colegio.edu.hn ");

    expect(draft.identifier()).toBe("ana.pérez+admin@colegio.edu.hn");
    expect(draft.snapshot()).toBeUndefined();
  });

  it("receives the secret and the codes at the first step, as one snapshot that stays until it changes", () => {
    const draft = createEnrollmentDraft();
    const issuedSecret = secret();
    const issuedCodes = codes();
    draft.begin("ana@colegio.edu.hn");

    draft.receive(issuedSecret, issuedCodes);

    expect(draft.snapshot()).toEqual({
      identifier: "ana@colegio.edu.hn",
      secret: issuedSecret,
      recoveryCodes: issuedCodes,
      step: "secret",
    });
    expect(draft.snapshot()).toBe(draft.snapshot());
  });

  it("ignores a secret when no identifier was kept", () => {
    const draft = createEnrollmentDraft();

    draft.receive(secret(), codes());

    expect(draft.snapshot()).toBeUndefined();
  });

  it("SS93: goes secret, recovery-codes, first-code and forgets the codes on leaving recovery-codes", () => {
    const draft = createEnrollmentDraft();
    draft.begin("ana@colegio.edu.hn");
    draft.receive(secret(), codes());

    draft.advance();
    expect(draft.snapshot()).toMatchObject({ step: "recovery-codes" });
    expect(draft.snapshot()?.recoveryCodes).toHaveLength(3);

    draft.advance();
    expect(draft.snapshot()).toMatchObject({ step: "first-code", recoveryCodes: undefined });

    const atFirstCode = draft.snapshot();
    draft.advance();
    expect(draft.snapshot()).toBe(atFirstCode);
  });

  it("goes back from first-code to the secret and then skips the codes it no longer has", () => {
    const draft = createEnrollmentDraft();
    draft.begin("ana@colegio.edu.hn");
    draft.receive(secret(), codes());
    draft.advance();
    draft.advance();

    draft.review();
    expect(draft.snapshot()).toMatchObject({ step: "secret", recoveryCodes: undefined });

    draft.advance();
    expect(draft.snapshot()).toMatchObject({ step: "first-code", recoveryCodes: undefined });
  });

  it("reviews the secret only from first-code", () => {
    const draft = createEnrollmentDraft();
    draft.begin("ana@colegio.edu.hn");
    draft.receive(secret(), codes());
    const atSecret = draft.snapshot();

    draft.review();

    expect(draft.snapshot()).toBe(atSecret);
  });

  it("SS98: clear forgets the identifier, the secret and the codes", () => {
    const draft = createEnrollmentDraft();
    draft.begin("ana@colegio.edu.hn");
    draft.receive(secret(), codes());

    draft.clear();

    expect(draft.snapshot()).toBeUndefined();
    expect(draft.identifier()).toBeUndefined();
  });

  it("begins a new draft without anything of the earlier one", () => {
    const draft = createEnrollmentDraft();
    draft.begin("ana@colegio.edu.hn");
    draft.receive(secret(), codes());

    draft.begin("luis@colegio.edu.hn");

    expect(draft.identifier()).toBe("luis@colegio.edu.hn");
    expect(draft.snapshot()).toBeUndefined();
  });

  it("holds no token: its snapshot has the four fields of the contract and nothing else", () => {
    const draft = createEnrollmentDraft();
    draft.begin("ana@colegio.edu.hn");
    draft.receive(secret(), codes());

    expect(Object.keys(draft.snapshot() ?? {}).sort()).toEqual(["identifier", "recoveryCodes", "secret", "step"]);
  });
});

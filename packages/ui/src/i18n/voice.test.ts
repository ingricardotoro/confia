import { ADMIN_VOICE_RULES, findVoiceViolations, type VoiceRules } from "@confia/config/voice";
import { describe, expect, it } from "vitest";
import ui from "./locales/es-HN/ui.json";

// admin-web-foundations-and-session, task 8.1 (DS93, DS94, design.md D17). The ui catalog does not
// address the person at all: it is the admin list plus "usted" and its possessives, and the
// third-person imperatives that open a sentence.
const UI_VOICE_RULES: VoiceRules = {
  anywhere: [...ADMIN_VOICE_RULES.anywhere, ...["usted", "su", "sus", "suyo", "suya", "suyos", "suyas"]],
  sentenceStart: [
    ...ADMIN_VOICE_RULES.sentenceStart,
    ...["inicie", "ingrese", "escriba", "revise", "intente", "genere", "use", "guarde", "copie", "vuelva"],
    ...["espere", "verifique", "confirme", "continúe", "abra", "cierre", "avise", "solicite", "recargue"],
    ...["registre", "asigne"],
  ],
};

function describeViolations(catalog: object): string[] {
  return findVoiceViolations(catalog, UI_VOICE_RULES).map(({ key, word, rule }) => `${key}: ${word} (${rule})`);
}

describe("the ui catalog voice", () => {
  it("DS93: evaluates a non-empty catalog that holds the keys of FormErrorSummary and Alert", () => {
    expect(Object.keys(ui)).toEqual(expect.arrayContaining(["alert", "formErrorSummary"]));
    expect(ui.formErrorSummary.heading).toContain("plural");
    expect(ui.alert.severity.destructive).not.toBe("");
  });

  it("DS93: no value of the catalog addresses the person", () => {
    expect(describeViolations(ui)).toEqual([]);
  });

  it.each([
    ["Revisa los campos", "tuteo"],
    ["Revise los campos", "usted"],
  ])("DS94: %s (%s) is rejected and the failure names the key", (text) => {
    expect(describeViolations({ formErrorSummary: { heading: text } })).toHaveLength(1);
    expect(describeViolations({ formErrorSummary: { heading: text } })[0]).toContain("formErrorSummary.heading");
  });

  it("DS94: an impersonal sentence passes and the forms of usted are rejected anywhere in it", () => {
    expect(describeViolations({ note: "La sesión se cierra." })).toEqual([]);
    expect(describeViolations({ note: "Su sesión se cierra." })).toEqual(["note: su (anywhere)"]);
    expect(describeViolations({ note: "Aquí usted termina." })).toEqual(["note: usted (anywhere)"]);
  });
});

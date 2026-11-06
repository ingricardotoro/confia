import { describe, expect, it } from "vitest";
import { ADMIN_VOICE_RULES, findVoiceViolations, type VoiceRules } from "./voice.js";

// admin-web-foundations-and-session, task 3.1 (AW29, AW30, design.md D17). The voice check is a pure
// function over a catalog, so these cases pin what counts as a violation without any application.
const RULES: VoiceRules = {
  anywhere: ["tu", "quieres", "comunícate"],
  sentenceStart: ["inicia", "revisa"],
};

describe("findVoiceViolations", () => {
  it("returns nothing for text in the formal address", () => {
    const catalog = { notice: { retry: "Inicie sesión de nuevo para continuar." } };

    expect(findVoiceViolations(catalog, RULES)).toEqual([]);
  });

  it("finds an anywhere form in the middle of a sentence and names its dotted key", () => {
    const catalog = { problem: { network: "Si quieres, intenta de nuevo." } };

    expect(findVoiceViolations(catalog, RULES)).toEqual([
      { key: "problem.network", word: "quieres", rule: "anywhere" },
    ]);
  });

  it("compares whole words without regard to case", () => {
    const catalog = { a: "TU cuenta", b: "Tutor asignado", c: "Contuvo la señal" };

    expect(findVoiceViolations(catalog, RULES).map(({ key }) => key)).toEqual(["a"]);
  });

  it("finds a sentence-start form at the start of the text", () => {
    const catalog = { notice: { retry: "Inicia sesión de nuevo" } };

    expect(findVoiceViolations(catalog, RULES)).toEqual([
      { key: "notice.retry", word: "inicia", rule: "sentence-start" },
    ]);
  });

  it("finds a sentence-start form after a full stop, a question mark or an opening mark", () => {
    const catalog = { a: "Listo. Revisa los datos", b: "¿Seguro? ¡Revisa ahora!", c: "¿Inicia ya?" };

    expect(findVoiceViolations(catalog, RULES).map(({ key }) => key)).toEqual(["a", "b", "c"]);
  });

  it("does not flag a sentence-start form in the middle of a sentence, the third person case", () => {
    const catalog = { a: "Su sesión inicia en unos segundos", b: "La sesión que inicia hoy" };

    expect(findVoiceViolations(catalog, RULES)).toEqual([]);
  });

  it("ignores the names inside ICU placeholders", () => {
    const catalog = { countdown: "Puede intentar de nuevo en {tu} s." };

    expect(findVoiceViolations(catalog, RULES)).toEqual([]);
  });

  it("walks nested catalogs and reports every violation in catalog order", () => {
    const catalog = { a: { b: { c: "Inicia aquí" } }, d: "Dime si quieres" };

    expect(findVoiceViolations(catalog, RULES)).toEqual([
      { key: "a.b.c", word: "inicia", rule: "sentence-start" },
      { key: "d", word: "quieres", rule: "anywhere" },
    ]);
  });

  it("matches accented forms exactly as listed", () => {
    expect(findVoiceViolations({ a: "Por favor comunícate con nosotros" }, RULES)).toEqual([
      { key: "a", word: "comunícate", rule: "anywhere" },
    ]);
  });
});

describe("ADMIN_VOICE_RULES", () => {
  it("lists the tuteo and voseo forms of design D17 for the admin catalog", () => {
    expect(ADMIN_VOICE_RULES.anywhere).toEqual(
      expect.arrayContaining(["tu", "tus", "vos", "tienes", "puedes", "iniciá", "inténtalo", "comunícate"]),
    );
    expect(ADMIN_VOICE_RULES.sentenceStart).toEqual(
      expect.arrayContaining(["inicia", "ingresa", "revisa", "intenta", "continúa", "registra", "asigna"]),
    );
  });

  it("does not list the formal forms, which belong to the staff catalog", () => {
    const all = [...ADMIN_VOICE_RULES.anywhere, ...ADMIN_VOICE_RULES.sentenceStart];

    expect(all).not.toContain("usted");
    expect(all).not.toContain("su");
    expect(all).not.toContain("inicie");
  });

  it("leaves out ten, sal, ve and pon, which appear in legitimate text", () => {
    const all = [...ADMIN_VOICE_RULES.anywhere, ...ADMIN_VOICE_RULES.sentenceStart];

    for (const word of ["ten", "sal", "ve", "pon"]) expect(all).not.toContain(word);
  });

  it("accepts the formal texts approved for the staff and rejects the same sentence in tú", () => {
    const formal = { a: "Su sesión se cierra pronto.", b: "Inicie sesión y use su código." };

    expect(findVoiceViolations(formal, ADMIN_VOICE_RULES)).toEqual([]);
    expect(findVoiceViolations({ a: "Inicia sesión y usa tu código." }, ADMIN_VOICE_RULES)).toEqual([
      { key: "a", word: "inicia", rule: "sentence-start" },
      { key: "a", word: "tu", rule: "anywhere" },
    ]);
  });
});

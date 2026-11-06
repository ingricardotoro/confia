/**
 * The voice check of a text catalog (design.md D17). It has no dependencies, so a package, a test or a
 * script can all use it. It compares by whole word and ignores case. A form listed in `anywhere` is
 * wrong wherever it appears; a form in `sentenceStart` is wrong only as the first word of a sentence,
 * because "Su sesión se cierra" is the third person and must not fail where "Cierra la sesión" must.
 */
export interface VoiceRules {
  readonly anywhere: readonly string[];
  readonly sentenceStart: readonly string[];
}

export interface VoiceViolation {
  /** Dotted path of the catalog entry, so the failure names the key. */
  readonly key: string;
  /** The offending word, in lower case. */
  readonly word: string;
  readonly rule: "anywhere" | "sentence-start";
}

/**
 * The staff catalog (`admin`) is written in "usted": no tuteo and no voseo. `ten`, `sal`, `ve` and
 * `pon` are left out on purpose, because they appear in legitimate text.
 */
export const ADMIN_VOICE_RULES: VoiceRules = {
  anywhere: [
    ...["tu", "tus", "ti", "te", "contigo", "tuyo", "tuya", "tuyos", "tuyas", "vos", "tienes", "puedes"],
    ...["quieres", "debes", "sabes", "estás", "eres", "has", "haz", "tenés", "podés", "querés", "sabés"],
    ...["iniciá", "ingresá", "escribí", "revisá", "intentá", "usá", "guardá", "copiá", "volvé", "esperá"],
    ...["verificá", "confirmá", "abrí", "cerrá", "inténtalo", "comunícate"],
  ],
  sentenceStart: [
    ...["inicia", "ingresa", "escribe", "revisa", "intenta", "genera", "usa", "guarda", "copia", "vuelve"],
    ...["espera", "verifica", "confirma", "continúa", "abre", "cierra", "avisa", "solicita", "recarga"],
    ...["registra", "asigna"],
  ],
};

const WORD = /[\p{L}\p{M}]+/u;
const ICU_PLACEHOLDER = /\{[^}]*\}/g;
// A sentence ends at . ! or ? followed by white space; the opening marks of Spanish are skipped by WORD.
const SENTENCE_BOUNDARY = /(?<=[.!?])\s+/;

function leaves(catalog: object, prefix: string): Array<readonly [string, string]> {
  return Object.entries(catalog).flatMap(([name, value]: [string, unknown]) => {
    const key = prefix === "" ? name : `${prefix}.${name}`;
    if (typeof value === "string") return [[key, value] as const];
    return typeof value === "object" && value !== null ? leaves(value, key) : [];
  });
}

function wordsOf(text: string): string[] {
  return text.match(new RegExp(WORD, "gu"))?.map((word) => word.toLowerCase()) ?? [];
}

/** Every violation of `rules` in the string values of `catalog`, in catalog order. */
export function findVoiceViolations(catalog: object, rules: VoiceRules): VoiceViolation[] {
  const anywhere = new Set(rules.anywhere);
  const sentenceStart = new Set(rules.sentenceStart);

  return leaves(catalog, "").flatMap(([key, value]) => {
    const text = value.replace(ICU_PLACEHOLDER, " ");
    const starts = text
      .split(SENTENCE_BOUNDARY)
      .map((sentence) => wordsOf(sentence)[0])
      .filter((word): word is string => word !== undefined);
    const found: VoiceViolation[] = [];
    for (const word of starts) {
      if (sentenceStart.has(word)) found.push({ key, word, rule: "sentence-start" });
    }
    for (const word of wordsOf(text)) {
      if (anywhere.has(word)) found.push({ key, word, rule: "anywhere" });
    }
    return found;
  });
}

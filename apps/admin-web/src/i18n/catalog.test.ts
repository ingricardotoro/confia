import { ADMIN_VOICE_RULES, findVoiceViolations } from "@confia/config/voice";
import { describe, expect, it } from "vitest";
import admin from "./locales/es-HN/admin.json";
import { flattenCatalogKeys } from "../test/text-keys.ts";

// admin-web-foundations-and-session, task 3.1 (AW28 to AW30, AW32, design.md D17 and section 8).
const KEYS = flattenCatalogKeys(admin);

function describeViolations(catalog: object): string[] {
  return findVoiceViolations(catalog, ADMIN_VOICE_RULES).map(({ key, word, rule }) => `${key}: ${word} (${rule})`);
}

function valueAt(catalog: object, key: string): unknown {
  return key.split(".").reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], catalog);
}

describe("the admin catalog", () => {
  it("AW28: evaluates real keys, and every one has a text", () => {
    expect(KEYS.length).toBeGreaterThan(0);
    for (const key of KEYS) {
      const value = valueAt(admin, key);
      expect(typeof value, key).toBe("string");
      expect((value as string).trim(), key).not.toBe("");
    }
  });

  it("AW28: holds every key of design section 8", () => {
    expect(KEYS).toEqual(
      expect.arrayContaining([
        "signIn.contact",
        "signIn.waiting",
        "secondFactor.waiting",
        "notice.verificationInterrupted",
        "notice.enrollmentInterrupted",
        "notice.alreadyEnrolled",
        "notice.sessionExpired",
        "signedIn.title",
        "signedIn.message",
        "problem.capacityExceeded",
        "problem.network",
        "problem.recoveryInvalid",
        "problem.notProcessed",
        "problem.authenticationFailed",
        "countdown.remaining",
        "countdown.done",
        "copy.key",
        "copy.keyDone",
        "copy.trace",
        "copy.traceDone",
        "copy.failed",
        "boot.restoring",
        "boot.failed",
        "notFound.title",
        "notFound.link",
      ]),
    );
  });

  // Activated by task 9, the first one with a component that calls t(): until then no file of src/ has
  // a key to check, and a green on an empty set is not accepted (ADR-0018).
  it.todo("AW28: every key used by a component in src/ exists in the catalog");

  it("AW29: no value uses the tuteo or voseo forms, anywhere or at the start of a sentence", () => {
    expect(describeViolations(admin)).toEqual([]);
  });

  it("AW30: the voice check rejects 'Inicia sesión de nuevo' and names the key", () => {
    const withViolation = { ...admin, notice: { ...admin.notice, verificationInterrupted: "Inicia sesión de nuevo" } };

    expect(describeViolations(withViolation)).toEqual(["notice.verificationInterrupted: inicia (sentence-start)"]);
  });

  it("AW32: the texts fixed by the documentation stay exact, in the formal address", () => {
    expect(admin.problem.authenticationFailed).toBe("El correo o la contraseña no son correctos.");
    expect(admin.notice.sessionExpired).toBe("Su sesión se cerró por seguridad. Vuelva a iniciar sesión.");
  });

  it("holds the approved texts of design section 8 verbatim", () => {
    expect(admin.signIn.contact).toBe(
      "Si no recuerda su contraseña, comuníquese con la administración de su institución.",
    );
    expect(admin.signIn.waiting).toBe("Verificando sus datos. Esto puede tardar hasta 30 segundos.");
    expect(admin.secondFactor.waiting).toBe("Verificando el código. Esto puede tardar hasta 30 segundos.");
    expect(admin.notice.verificationInterrupted).toBe(
      "La verificación se interrumpió. Inicie sesión de nuevo para continuar.",
    );
    expect(admin.notice.enrollmentInterrupted).toBe(
      "La inscripción se interrumpió. El código QR y los códigos de recuperación anteriores ya no sirven; al iniciar sesión se generarán nuevos.",
    );
    expect(admin.notice.alreadyEnrolled).toBe(
      "Su cuenta ya tiene un segundo factor inscrito. Inicie sesión y use su código de verificación.",
    );
    expect(admin.signedIn.title).toBe("Sesión iniciada.");
    expect(admin.signedIn.message).toBe("Su sesión está activa.");
    expect(admin.problem.capacityExceeded).toBe(
      "El servicio está ocupado en este momento. Espere un momento e intente de nuevo.",
    );
    expect(admin.problem.network).toBe(
      "No se pudo conectar con el servidor. Revise su conexión e intente de nuevo.",
    );
    expect(admin.problem.recoveryInvalid).toBe(
      "El código de recuperación no es correcto o ya se usó. Revíselo e intente de nuevo.",
    );
    expect(admin.problem.notProcessed).toBe(
      "No se pudieron procesar los datos enviados. Revíselos e intente de nuevo.",
    );
    expect(admin.countdown.remaining).toBe("Puede intentar de nuevo en {seconds} s.");
    expect(admin.countdown.done).toBe("Ya puede intentar de nuevo.");
    expect(admin.copy.key).toBe("Copiar clave");
    expect(admin.copy.keyDone).toBe("Clave copiada.");
    expect(admin.copy.trace).toBe("Copiar identificador");
    expect(admin.copy.traceDone).toBe("Identificador copiado.");
    expect(admin.copy.failed).toBe("No se pudo copiar. Seleccione el texto y cópielo manualmente.");
    expect(admin.boot.restoring).toBe("Restaurando su sesión.");
    expect(admin.boot.failed).toBe("No se pudo restaurar la sesión. Revise su conexión e intente de nuevo.");
    expect(admin.notFound.title).toBe("Esta página no existe.");
    expect(admin.notFound.link).toBe("Ir a inicio de sesión");
  });
});

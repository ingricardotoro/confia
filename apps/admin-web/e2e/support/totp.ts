import { createHmac, timingSafeEqual } from "node:crypto";

/**
 * The authenticator of the tests: RFC 6238 TOTP with HMAC-SHA-1, 30 second steps and six digits, the
 * parameters the real enrollment uses. It depends on node:crypto and on nothing else (SS119), so the
 * simulated API and the journeys compute codes without a new dependency. It is test support: it is not
 * served from the production build and holds no secret.
 */
const STEP_SECONDS = 30;
const DIGITS = 6;
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

/** The code for `atSeconds` (Unix time), with its leading zeros. */
export function totpCode(key: Uint8Array, atSeconds: number): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(atSeconds / STEP_SECONDS)));
  const hash = createHmac("sha1", key).update(counter).digest();
  // RFC 4226 section 5.3: the low four bits of the last byte pick the window of four bytes.
  const offset = (hash[hash.length - 1] ?? 0) & 0x0f;
  const binary = hash.readUInt32BE(offset) & 0x7fffffff;
  return String(binary % 10 ** DIGITS).padStart(DIGITS, "0");
}

/** Whether `code` is the code of the step at `atSeconds` or of `driftSteps` steps either side of it. */
export function isTotpValid(key: Uint8Array, code: string, atSeconds: number, driftSteps = 1): boolean {
  if (code.length !== DIGITS) return false;
  const given = Buffer.from(code);
  let accepted = false;
  for (let drift = -driftSteps; drift <= driftSteps; drift += 1) {
    const expected = Buffer.from(totpCode(key, atSeconds + drift * STEP_SECONDS));
    // Every step is compared, so the time taken does not say which one matched.
    if (timingSafeEqual(given, expected)) accepted = true;
  }
  return accepted;
}

/** RFC 4648 base32 without padding, the form an authenticator application takes as a secret. */
export function base32Encode(bytes: Uint8Array): string {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += ALPHABET.charAt((value >>> (bits - 5)) & 31);
      bits -= 5;
    }
    value &= (1 << bits) - 1;
  }
  if (bits > 0) out += ALPHABET.charAt((value << (5 - bits)) & 31);
  return out;
}

/** The inverse of {@link base32Encode}. Upper case only, as the contract's pattern is; the message never echoes the input. */
export function base32Decode(text: string): Uint8Array {
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const character of text) {
    const index = ALPHABET.indexOf(character);
    if (index === -1) throw new Error("The text is not base32.");
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 255);
      bits -= 8;
      value &= (1 << bits) - 1;
    }
  }
  return Uint8Array.from(out);
}

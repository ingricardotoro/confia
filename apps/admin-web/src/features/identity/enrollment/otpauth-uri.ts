/**
 * The URI an authenticator app reads from the QR code (design.md D11). The issuer is written in the label and in the
 * parameter, as Google recommends, and no algorithm, digits or period is given because the defaults are what the
 * server uses. The identifier is encoded whole, so a colon, a space or a percent sign in it cannot break the label.
 */
export function buildOtpauthUri(identifier: string, secret: string): string {
  return `otpauth://totp/CONFIA:${encodeURIComponent(identifier)}?secret=${secret}&issuer=CONFIA`;
}

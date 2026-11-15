import { QRCodeSVG } from "qrcode.react";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";

export interface TotpQrCodeProps {
  /** The `otpauth` URI to draw. It is drawn in the browser and never sent anywhere. */
  readonly uri: string;
}

/**
 * The QR code of the secret (design.md E11). It writes no visual literal: the library's own white background and black
 * modules give the same contrast in both themes, and the background covers the quiet zone (SS83, SS84).
 */
export function TotpQrCode({ uri }: TotpQrCodeProps) {
  const { t } = useAdminText();
  return <QRCodeSVG value={uri} level="M" marginSize={4} role="img" title={t("enrollment.qrLabel")} />;
}

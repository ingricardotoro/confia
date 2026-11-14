import { Button } from "@confia/ui";
import { useEffect, useRef } from "react";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { CopyButton } from "../../components/CopyButton.tsx";
import { groupManualKey } from "../manual-key.ts";
import { TotpQrCode } from "./TotpQrCode.tsx";

export interface SecretStepProps {
  /** The `otpauth` URI the QR code draws. */
  readonly uri: string;
  /** Base32, as it arrived: the key is shown in groups and copied as it is. */
  readonly secret: string;
  readonly onContinue: () => void;
}

/** The first section of the enrollment (design.md E10): the QR code, the same secret as a key to type, and Continue. */
export function SecretStep({ uri, secret, onContinue }: SecretStepProps) {
  const { t } = useAdminText();
  const heading = useRef<HTMLHeadingElement>(null);

  // Each section takes the focus on its own heading when it appears (D11).
  useEffect(() => {
    heading.current?.focus();
  }, []);

  return (
    <section className="flex flex-col gap-4">
      <h2 ref={heading} tabIndex={-1} className="text-lg font-semibold text-text-primary outline-none">
        {t("enrollment.secret.title")}
      </h2>
      <p className="text-text-secondary">{t("enrollment.secret.intro")}</p>
      <div className="flex justify-center">
        <TotpQrCode uri={uri} />
      </div>
      <div className="flex flex-col gap-2">
        <p className="text-sm text-text-secondary">{t("enrollment.manualKey")}</p>
        <p className="flex flex-wrap items-center gap-2">
          <code className="font-mono text-base tracking-wide text-text-primary">{groupManualKey(secret)}</code>
          <CopyButton text={secret} kind="key" />
        </p>
      </div>
      <Button onClick={onContinue}>{t("enrollment.continue")}</Button>
    </section>
  );
}

import { FormField, IconButton, Input } from "@confia/ui";
import { Eye, EyeOff } from "lucide-react";
import { useState } from "react";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";

export interface PasswordFieldProps {
  /** Already translated. */
  readonly label: string;
  /** The fixed `id` of the control, which the error summary links to. */
  readonly controlId: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly onBlur: () => void;
  /** Already translated. */
  readonly error?: string;
}

/**
 * The password with an optional reveal (design.md E8, P10). It is hidden every time the field mounts, so leaving the
 * screen and coming back hides it again (SS54). The control keeps one name, and `aria-pressed` carries the state:
 * a name that changed as well would give two signals of the same thing (SS53). It is the same button in both states,
 * so the focus and the value survive the change.
 */
export function PasswordField({ label, controlId, value, onChange, onBlur, error }: PasswordFieldProps) {
  const { t } = useAdminText();
  const [visible, setVisible] = useState(false);
  const Icon = visible ? EyeOff : Eye;

  return (
    <FormField label={label} controlId={controlId} required {...(error === undefined ? {} : { error })}>
      {(control) => (
        <div className="flex items-center gap-2">
          <Input
            {...control}
            type={visible ? "text" : "password"}
            autoComplete="current-password"
            className="min-w-0 flex-1"
            value={value}
            onChange={(event) => {
              onChange(event.target.value);
            }}
            onBlur={onBlur}
          />
          <IconButton
            type="button"
            variant="secondary"
            label={t("signIn.showPassword")}
            aria-pressed={visible}
            onClick={() => {
              setVisible((shown) => !shown);
            }}
          >
            <Icon aria-hidden="true" />
          </IconButton>
        </div>
      )}
    </FormField>
  );
}

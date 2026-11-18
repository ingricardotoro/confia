import { Button, Dialog } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";

export interface ConfirmDialogProps {
  /** Already translated. Names the action and the record it acts on. */
  readonly title: string;
  /** Already translated. Says what the action does and what it leaves undone. */
  readonly description: string;
  readonly confirmLabel: string;
  /** The colour of the confirm button follows its action: green for a positive state change, red for one that cannot be undone. */
  readonly variant: "primary" | "success" | "destructive";
  /** The action is being sent: the dialog does not close and the confirm button waits, so it can never be sent twice. */
  readonly busy: boolean;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
}

/**
 * A small dialog that asks the person to confirm an action that changes a record for good (docs/ui-ux/04, section 4).
 * Nothing is sent until the confirm button is pressed, and while the request is in flight neither the Escape key, the
 * overlay nor the cancel button closes it, so the answer is never lost.
 */
export function ConfirmDialog({ title, description, confirmLabel, variant, busy, onConfirm, onCancel }: ConfirmDialogProps) {
  const { t } = useAdminText();
  return (
    <Dialog
      open
      size="sm"
      title={title}
      description={description}
      onOpenChange={(open) => {
        if (!open && !busy) onCancel();
      }}
      footer={
        <>
          <Button variant="secondary" size="sm" disabled={busy} onClick={onCancel}>
            {t("resource.cancel")}
          </Button>
          <Button variant={variant} size="sm" loading={busy} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {null}
    </Dialog>
  );
}

import { MoneyAmount } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { TextAreaField } from "../../../shared/resource-screen/components/fields.tsx";
import { CASH_NOTE_MAX } from "../cashbox-model.ts";
import type { CashSession } from "../cashbox-types.ts";

export const APPROVE_NOTE_ID = "cashbox-approve-note";

export interface ApproveCloseDialogProps {
  /** The close the person is approving, as the server holds it: the dialog restates it (expected, counted and difference) so the approval is never blind. */
  readonly close: NonNullable<CashSession["close"]>;
  readonly note: string;
  /** Already translated. */
  readonly noteError?: string | undefined;
  readonly form: ResourceFormView;
  readonly onNoteChange: (note: string) => void;
  readonly onNoteBlur: () => void;
  readonly onClose: () => void;
}

/**
 * The explicit approval of a close that has a difference. It shows what is being approved and asks for a note. Segregation of duties belongs
 * to the server: the cashier who closed the session is refused there, and the refusal is told in the dialog.
 */
export function ApproveCloseDialog({ close, note, noteError, form, onNoteChange, onNoteBlur, onClose }: ApproveCloseDialogProps) {
  const { t } = useAdminText();
  return (
    <ResourceFormDialog
      title={t("cashbox.approve.title")}
      description={t("cashbox.approve.description")}
      submitLabel={t("cashbox.approve.submit")}
      submitVariant="success"
      form={form}
      onClose={onClose}
    >
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-text-secondary">{t("cashbox.summary.expected")}</dt>
        <dd className="text-right">
          <MoneyAmount value={close.expectedAmount} size="sm" />
        </dd>
        <dt className="text-text-secondary">{t("cashbox.summary.declared")}</dt>
        <dd className="text-right">
          <MoneyAmount value={close.declaredAmount} size="sm" />
        </dd>
        <dt className="text-text-secondary">{t("cashbox.summary.difference")}</dt>
        <dd className="text-right">
          <MoneyAmount value={close.differenceAmount} size="sm" emphasis="strong" colorBySign showPositiveSign />
        </dd>
        {close.differenceReason === undefined ? null : (
          <>
            <dt className="text-text-secondary">{t("cashbox.summary.differenceReason")}</dt>
            <dd className="text-text-primary">{close.differenceReason}</dd>
          </>
        )}
      </dl>
      <TextAreaField
        label={t("cashbox.approve.note")}
        controlId={APPROVE_NOTE_ID}
        required
        maxLength={CASH_NOTE_MAX}
        description={t("cashbox.approve.noteHelp", { max: CASH_NOTE_MAX })}
        value={note}
        error={noteError}
        onChange={onNoteChange}
        onBlur={onNoteBlur}
      />
    </ResourceFormDialog>
  );
}

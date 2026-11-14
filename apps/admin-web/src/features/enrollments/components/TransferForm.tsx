import { Alert, Button } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { TextField } from "../../organization/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../organization/components/ResourceFormDialog.tsx";
import { TRANSFER_CONTROL_IDS, type TransferField, type TransferValues } from "../enrollment-forms.ts";
import type { OptionGroup } from "../enrollment-model.ts";
import type { SectionOptionsState } from "./EnrollForm.tsx";
import { ReasonField } from "./ReasonField.tsx";
import { SectionField } from "./SectionField.tsx";

export interface TransferFormProps {
  /** Already translated: the student the enrollment is of. */
  readonly studentName: string;
  readonly values: TransferValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<TransferField, string>>>;
  readonly sectionsState: Exclude<SectionOptionsState, "no-year">;
  /** The active sections of the same year, other than the current one. */
  readonly sectionGroups: readonly OptionGroup[];
  readonly onRetrySections: () => void;
  readonly form: ResourceFormView;
  readonly onChange: (field: TransferField, value: string) => void;
  readonly onBlur: (field: TransferField) => void;
  readonly onClose: () => void;
}

/** The form to move an enrollment to another section of the same year, in a dialog: the new section, the day and an optional reason. */
export function TransferForm({ studentName, values, fieldErrors, sectionsState, sectionGroups, onRetrySections, form, onChange, onBlur, onClose }: TransferFormProps) {
  const { t } = useAdminText();
  const noSections = sectionsState === "ready" && sectionGroups.length === 0;
  const text = (field: TransferField) => ({
    controlId: TRANSFER_CONTROL_IDS[field],
    value: values[field],
    error: fieldErrors[field],
    onChange: (value: string) => {
      onChange(field, value);
    },
    onBlur: () => {
      onBlur(field);
    },
  });
  return (
    <ResourceFormDialog
      title={t("enrollments.transfer.title")}
      description={t("enrollments.transfer.description", { name: studentName })}
      submitLabel={t("enrollments.transfer.submit")}
      form={form}
      onClose={onClose}
    >
      {sectionsState === "failed" ? (
        <Alert variant="warning">
          <p>{t("enrollments.enroll.optionsFailed")}</p>
          <Button variant="secondary" size="sm" className="mt-2" onClick={onRetrySections}>
            {t("enrollments.enroll.retryOptions")}
          </Button>
        </Alert>
      ) : null}
      <SectionField
        label={t("enrollments.transfer.toSection")}
        required
        groups={sectionGroups}
        {...(noSections ? { description: t("enrollments.transfer.noSections") } : {})}
        {...text("toSectionId")}
      />
      <TextField label={t("enrollments.transfer.date")} kind="date" required {...text("transferredOn")} />
      <ReasonField label={t("enrollments.transfer.reason")} description={t("enrollments.transfer.reasonHelp")} {...text("reason")} />
    </ResourceFormDialog>
  );
}

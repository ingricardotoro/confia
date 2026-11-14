import { useAdminText } from "../../i18n/use-admin-text.ts";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { failureOf } from "../organization/organization-failure.ts";
import { fieldSpecs } from "../organization/organization-form.ts";
import { TransferForm } from "./components/TransferForm.tsx";
import {
  TRANSFER_CONTROL_IDS,
  initialTransferValues,
  transferBodyOf,
  validateTransfer,
  type TransferField,
} from "./enrollment-forms.ts";
import { groupSections, studentNameOf, type Enrollment } from "./enrollment-model.ts";
import { useYearSections, type EnrollmentWrite } from "./use-enrollments-queries.ts";

export interface TransferFormContainerProps {
  readonly row: Enrollment;
  readonly gradeLabels: ReadonlyMap<string, string>;
  /** Rejects with the failure of the request; resolves when the enrollment is moved. */
  readonly save: (write: Extract<EnrollmentWrite, { kind: "transfer" }>) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the transfer form: the sections of the enrollment's own year, other than its current one, and the usual validate, send once and report. */
export function TransferFormContainer({ row, gradeLabels, save, onClose }: TransferFormContainerProps) {
  const { t } = useAdminText();
  const sections = useYearSections(row.academicYearId);
  const form = useAccessForm<TransferField>({
    fields: fieldSpecs(TRANSFER_CONTROL_IDS, initialTransferValues(), ["reason"]),
    validate: validateTransfer,
    submit: async (values) => {
      try {
        await save({ kind: "transfer", id: row.id, body: transferBodyOf(values) });
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "enrollments.transfer.saving",
  });

  return (
    <TransferForm
      studentName={studentNameOf(row)}
      values={form.values}
      fieldErrors={form.fieldErrors}
      sectionsState={sections.isError ? "failed" : sections.isPending ? "loading" : "ready"}
      sectionGroups={groupSections(sections.data?.items ?? [], gradeLabels, t("organization.common.unknownReference"), row.sectionId)}
      onRetrySections={() => void sections.refetch()}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}

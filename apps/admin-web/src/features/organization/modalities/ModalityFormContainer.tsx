import { useAccessForm } from "../../../shared/forms/use-access-form.ts";
import { failureOf } from "../organization-failure.ts";
import { fieldSpecs } from "../organization-form.ts";
import { ModalityForm } from "./components/ModalityForm.tsx";
import {
  MODALITY_CONTROL_IDS,
  initialModalityValues,
  saveOf,
  validateModality,
  type Modality,
  type ModalityField,
  type ModalitySave,
} from "./modality-form.ts";

export interface ModalityFormContainerProps {
  /** Absent when the modality is new. */
  readonly row?: Modality;
  /** Rejects with the failure of the request; resolves when the modality is saved. */
  readonly save: (save: ModalitySave) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a modality: validate before sending, send once, and turn a failure into what the form shows. */
export function ModalityFormContainer({ row, save, onClose }: ModalityFormContainerProps) {
  const form = useAccessForm<ModalityField>({
    fields: fieldSpecs(MODALITY_CONTROL_IDS, initialModalityValues(row), ["code", "name"]),
    validate: validateModality(row),
    submit: async (values) => {
      try {
        await save(saveOf(row, values));
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "organization.common.saving",
  });

  return (
    <ModalityForm
      mode={row === undefined ? "create" : "edit"}
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}

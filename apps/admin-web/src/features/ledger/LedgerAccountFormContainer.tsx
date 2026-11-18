import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { LedgerAccountForm } from "./components/LedgerAccountForm.tsx";
import {
  ACCOUNT_CONTROL_IDS,
  canHoldStudentBalances,
  initialAccountValues,
  saveOf,
  validateAccount,
  type AccountField,
  type AccountSave,
  type LedgerAccount,
} from "./account-form.ts";

export interface LedgerAccountFormContainerProps {
  /** Absent when the account is new. */
  readonly row?: LedgerAccount;
  /** Rejects with the failure of the request; resolves when the account is saved. */
  readonly save: (save: AccountSave) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of an account: validate before sending, send once, and turn a failure into what the form shows. */
export function LedgerAccountFormContainer({ row, save, onClose }: LedgerAccountFormContainerProps) {
  const form = useAccessForm<AccountField>({
    fields: fieldSpecs(ACCOUNT_CONTROL_IDS, initialAccountValues(row), ["code", "name"]),
    validate: validateAccount(row),
    submit: async (values) => {
      try {
        await save(saveOf(row, values));
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "resource.saving",
  });

  return (
    <LedgerAccountForm
      mode={row === undefined ? "create" : "edit"}
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      onChange={(field, value) => {
        form.onChange(field, value);
        // A type that cannot carry student balances drops the flag, so what is sent is what the person sees.
        if (field === "type" && !canHoldStudentBalances(value)) form.onChange("holdsStudentBalances", "false");
      }}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}

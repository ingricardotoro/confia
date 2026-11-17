import {
  CreateLedgerAccountRequest,
  UpdateLedgerAccountRequest,
  type CreateLedgerAccountRequest as CreateBody,
  type LedgerAccountResponseOutput,
  type UpdateLedgerAccountRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ApiResult } from "../../shared/api/http.ts";
import { createLedgerAccount, updateLedgerAccount, type LedgerAccountType } from "../../shared/api/ledger-operations.ts";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";
import { activeValueOf } from "../../shared/resource-screen/resource-form.ts";

export type LedgerAccount = LedgerAccountResponseOutput;
export type AccountField = "code" | "name" | "type" | "holdsStudentBalances" | "active";

export const ACCOUNT_CONTROL_IDS: Readonly<Record<AccountField, string>> = {
  code: "ledger-account-code",
  name: "ledger-account-name",
  type: "ledger-account-type",
  holdsStudentBalances: "ledger-account-holds-student-balances",
  active: "ledger-account-active",
};

const FIELDS = Object.keys(ACCOUNT_CONTROL_IDS) as AccountField[];

export type AccountValues = Readonly<Record<AccountField, string>>;

export const initialAccountValues = (row?: LedgerAccount): AccountValues => ({
  code: row?.code ?? "",
  name: row?.name ?? "",
  type: row?.type ?? "",
  holdsStudentBalances: activeValueOf(row?.holdsStudentBalances ?? false),
  active: activeValueOf(row?.active ?? true),
});

/** Only an asset or a liability may carry the balance of each student (the receivable); the server refuses the flag on any other type. */
export const canHoldStudentBalances = (type: string): boolean => type === "ASSET" || type === "LIABILITY";

/** A new account has a code, a type and the flag; an edit has the state and none of those three, because they never change. */
export type AccountSave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

/**
 * The body that only changes the state of an account, keeping its name as the row has it. It carries the version the row
 * was read at: if the account changed since, the server answers 409 `stale-version`.
 */
export const updateBodyOf = (row: LedgerAccount, active: boolean): UpdateBody => ({ version: row.version, name: row.name, active });

export function saveOf(row: LedgerAccount | undefined, values: AccountValues): AccountSave {
  if (row === undefined) {
    // An empty type is refused by the schema before anything is sent: the cast only lets the schema judge it.
    const type = values.type as LedgerAccountType;
    return {
      mode: "create",
      body: { code: values.code, name: values.name, type, holdsStudentBalances: canHoldStudentBalances(values.type) && values.holdsStudentBalances === "true" },
    };
  }
  return { mode: "update", id: row.id, body: { version: row.version, name: values.name, active: values.active === "true" } };
}

/**
 * The reasons the form is refused before sending: the generated request, and a code, a name and a type that are not
 * blank. A code the generated whitelist pattern refuses (and that is not merely too long) gets its own reason. The form
 * trims the code and the name before it validates and sends them, because the server does not.
 */
export function validateAccount(row: LedgerAccount | undefined) {
  return (values: AccountValues): Partial<Record<AccountField, FieldReason>> => {
    const reasons = reasonsFromSchema(row === undefined ? CreateLedgerAccountRequest : UpdateLedgerAccountRequest, saveOf(row, values).body, FIELDS);
    return {
      ...reasons,
      ...(reasons.code === "format" ? { code: "code-format" as const } : {}),
      ...requiredReasons(values, row === undefined ? (["code", "name", "type"] as const) : (["name"] as const)),
    };
  };
}

export function writeAccount(token: string, save: AccountSave): Promise<ApiResult<LedgerAccount>> {
  return save.mode === "create" ? createLedgerAccount(token, save.body) : updateLedgerAccount(token, save.id, save.body);
}

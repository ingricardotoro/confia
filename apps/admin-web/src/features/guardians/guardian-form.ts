import {
  CreateGuardianRequest,
  UpdateGuardianRequest,
  type CreateGuardianRequest as CreateBody,
  type GuardianResponseOutput,
  type UpdateGuardianRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ApiResult } from "../../shared/api/http.ts";
import { createGuardian, updateGuardian } from "../../shared/api/guardian-operations.ts";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";

export type Guardian = GuardianResponseOutput;
export type GuardianStatus = Guardian["status"];
export type PreferredChannel = Guardian["preferredChannel"];
export type GuardianLinkedStudent = NonNullable<Guardian["students"]>[number];

export const CHANNELS: readonly PreferredChannel[] = ["NONE", "EMAIL", "SMS", "WHATSAPP"];

/**
 * `removeNationalId` and `removeRtn` are `"true"` or `"false"`: a checkbox kept as the text the form hook holds.
 * `nationalId` and `rtn` are what is typed to set or replace them; they start empty on an edit too, because the stored
 * values never reach the browser.
 */
export type GuardianField =
  | "firstName"
  | "lastName"
  | "nationalId"
  | "removeNationalId"
  | "rtn"
  | "removeRtn"
  | "email"
  | "phone"
  | "address"
  | "preferredChannel"
  | "status";

export const GUARDIAN_CONTROL_IDS: Readonly<Record<GuardianField, string>> = {
  firstName: "guardian-first-name",
  lastName: "guardian-last-name",
  nationalId: "guardian-national-id",
  removeNationalId: "guardian-remove-national-id",
  rtn: "guardian-rtn",
  removeRtn: "guardian-remove-rtn",
  email: "guardian-email",
  phone: "guardian-phone",
  address: "guardian-address",
  preferredChannel: "guardian-preferred-channel",
  status: "guardian-status",
};

const FIELDS = Object.keys(GUARDIAN_CONTROL_IDS) as GuardianField[];

/** The names are 1 to 100 characters once trimmed; the generated schema only bounds the raw text. */
const MAX_NAME_LENGTH = 100;

// The generated schema carries each of these as an unanchored pattern with an optional group, which a browser reads as
// "anything goes"; the server anchors them. Anchored here, so a value the server would refuse is told at once. The server
// stays the authority.
const NATIONAL_ID_FORMAT = /^[A-Za-z0-9-]{8,20}$/;
const RTN_FORMAT = /^\d{4}-?\d{4}-?\d{6}$/;
const EMAIL_FORMAT = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const PHONE_FORMAT = /^\+?[0-9(][0-9 ()-]{5,18}[0-9]$/;

/** The fields whose text is trimmed before it is checked, sent and kept. */
export const TRIMMED_FIELDS: readonly GuardianField[] = ["firstName", "lastName", "nationalId", "rtn", "email", "phone", "address"];

export type GuardianValues = Readonly<Record<GuardianField, string>>;

export const initialGuardianValues = (row?: Guardian): GuardianValues => ({
  firstName: row?.firstName ?? "",
  lastName: row?.lastName ?? "",
  nationalId: "",
  removeNationalId: "false",
  rtn: "",
  removeRtn: "false",
  email: row?.email ?? "",
  phone: row?.phone ?? "",
  address: row?.address ?? "",
  preferredChannel: row?.preferredChannel ?? "NONE",
  status: row?.status ?? "ACTIVE",
});

export type GuardianSave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

/** The name a notice and a button use for a guardian. Names live in the DOM only. */
export const nameOf = (guardian: Pick<Guardian, "firstName" | "lastName">): string => `${guardian.firstName} ${guardian.lastName}`;

/** The optional text members: an empty one is not sent (on an edit, omitted clears it). */
const optionalOf = (values: GuardianValues): { email?: string; phone?: string; address?: string } => ({
  ...(values.email === "" ? {} : { email: values.email }),
  ...(values.phone === "" ? {} : { phone: values.phone }),
  ...(values.address === "" ? {} : { address: values.address }),
});

/**
 * The change of a write-only value an edit asks for, as the API reads it: no member keeps the stored one, an empty one
 * removes it, any other replaces it. Removing takes an explicit act (the checkbox), so an empty box never does.
 */
function writeOnlyChange(name: "nationalId" | "rtn", values: GuardianValues): { nationalId?: string; rtn?: string } {
  const remove = name === "nationalId" ? values.removeNationalId : values.removeRtn;
  if (remove === "true") return { [name]: "" };
  return values[name] === "" ? {} : { [name]: values[name] };
}

function candidateOf(row: Guardian | undefined, values: GuardianValues) {
  const common = { firstName: values.firstName, lastName: values.lastName, preferredChannel: values.preferredChannel, ...optionalOf(values) };
  return row === undefined
    ? {
        ...common,
        ...(values.nationalId === "" ? {} : { nationalId: values.nationalId }),
        ...(values.rtn === "" ? {} : { rtn: values.rtn }),
      }
    : { ...common, status: values.status, ...writeOnlyChange("nationalId", values), ...writeOnlyChange("rtn", values) };
}

export function saveOf(row: Guardian | undefined, values: GuardianValues): GuardianSave {
  const body = candidateOf(row, values);
  return row === undefined ? { mode: "create", body: body as CreateBody } : { mode: "update", id: row.id, body: body as UpdateBody };
}

/**
 * Activate or deactivate from the row: the whole record again with the other status, because an edit replaces the record
 * (what is omitted is cleared). The document and the RTN are not sent, so the server keeps them.
 */
export const toggleBodyOf = (row: Guardian, status: GuardianStatus): UpdateBody => ({
  firstName: row.firstName,
  lastName: row.lastName,
  preferredChannel: row.preferredChannel,
  status,
  ...(row.email == null || row.email === "" ? {} : { email: row.email }),
  ...(row.phone == null || row.phone === "" ? {} : { phone: row.phone }),
  ...(row.address == null || row.address === "" ? {} : { address: row.address }),
});

/** The reasons the form is refused before sending: the generated request, what is empty, too long, or not in the format. */
export function validateGuardian(row: Guardian | undefined) {
  return (values: GuardianValues): Partial<Record<GuardianField, FieldReason>> => {
    const reasons: Partial<Record<GuardianField, FieldReason>> = {
      ...reasonsFromSchema(row === undefined ? CreateGuardianRequest : UpdateGuardianRequest, candidateOf(row, values), FIELDS),
      ...requiredReasons(values, ["firstName", "lastName"]),
    };
    for (const name of ["firstName", "lastName"] as const) {
      if (reasons[name] === undefined && values[name].length > MAX_NAME_LENGTH) reasons[name] = "too-long";
    }
    const formats: readonly [GuardianField, RegExp, FieldReason][] = [
      ["nationalId", NATIONAL_ID_FORMAT, "national-id-format"],
      ["rtn", RTN_FORMAT, "rtn-format"],
      ["email", EMAIL_FORMAT, "email-format"],
      ["phone", PHONE_FORMAT, "phone-format"],
    ];
    for (const [name, format, reason] of formats) {
      if (values[name] !== "" && (reasons[name] === "format" || !format.test(values[name]))) reasons[name] = reason;
    }
    return reasons;
  };
}

export function writeGuardian(token: string, save: GuardianSave): Promise<ApiResult<Guardian>> {
  return save.mode === "create" ? createGuardian(token, save.body) : updateGuardian(token, save.id, save.body);
}

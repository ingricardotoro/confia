import {
  CreateStudentRequest,
  UpdateStudentRequest,
  type CreateStudentRequest as CreateBody,
  type StudentResponseOutput,
  type UpdateStudentRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ApiResult } from "../../shared/api/http.ts";
import { createStudent, updateStudent } from "../../shared/api/student-operations.ts";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";

export type Student = StudentResponseOutput;
export type StudentSex = Student["sex"];
export type StudentStatus = Student["status"];

/**
 * `removeNationalId` is `"true"` or `"false"`: a checkbox kept as the text the form hook holds. `nationalId` is what
 * is typed to set or replace the document; it starts empty on an edit too, because the stored document is never
 * sent to the browser.
 */
export type StudentField =
  | "studentCode"
  | "firstName"
  | "lastName"
  | "birthDate"
  | "sex"
  | "nationalId"
  | "removeNationalId"
  | "status";

export const STUDENT_CONTROL_IDS: Readonly<Record<StudentField, string>> = {
  studentCode: "student-code",
  firstName: "student-first-name",
  lastName: "student-last-name",
  birthDate: "student-birth-date",
  sex: "student-sex",
  nationalId: "student-national-id",
  removeNationalId: "student-remove-national-id",
  status: "student-status",
};

const FIELDS = Object.keys(STUDENT_CONTROL_IDS) as StudentField[];

/** The names are 1 to 100 characters once trimmed; the generated schema only bounds the raw text. */
const MAX_NAME_LENGTH = 100;

/**
 * 8 to 20 letters, digits or hyphens. The generated schema carries this as an unanchored pattern whose group is
 * optional, which a browser reads as "anything goes"; the server anchors it. Anchored here, so a document the server
 * would refuse is told at once. The server stays the authority.
 */
const NATIONAL_ID_FORMAT = /^[A-Za-z0-9-]{8,20}$/;

/** The fields whose text is trimmed before it is checked, sent and kept. */
export const TRIMMED_FIELDS: readonly StudentField[] = ["studentCode", "firstName", "lastName", "nationalId"];

export type StudentValues = Readonly<Record<StudentField, string>>;

export const initialStudentValues = (row?: Student): StudentValues => ({
  studentCode: row?.studentCode ?? "",
  firstName: row?.firstName ?? "",
  lastName: row?.lastName ?? "",
  birthDate: row?.birthDate ?? "",
  sex: row?.sex ?? "",
  nationalId: "",
  removeNationalId: "false",
  status: row?.status ?? "ACTIVE",
});

export type StudentSave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

/** The name a notice and a button use for a student. */
export const nameOf = (student: Pick<Student, "firstName" | "lastName">): string => `${student.firstName} ${student.lastName}`;

/**
 * The change of the document an edit asks for, as the API reads it: no `nationalId` keeps the stored one, an empty
 * one removes it, any other replaces it. Removing takes an explicit act (the checkbox), so an empty box never does.
 */
function documentChangeOf(values: StudentValues): { nationalId?: string } {
  if (values.removeNationalId === "true") return { nationalId: "" };
  return values.nationalId === "" ? {} : { nationalId: values.nationalId };
}

/** The candidate body. The code is the server's to generate when it is not typed, and it is never sent on an edit. */
function candidateOf(row: Student | undefined, values: StudentValues) {
  const common = { firstName: values.firstName, lastName: values.lastName, birthDate: values.birthDate, sex: values.sex };
  return row === undefined
    ? { ...common, ...(values.studentCode === "" ? {} : { studentCode: values.studentCode }), ...(values.nationalId === "" ? {} : { nationalId: values.nationalId }) }
    : { ...common, status: values.status, ...documentChangeOf(values) };
}

export function saveOf(row: Student | undefined, values: StudentValues): StudentSave {
  const body = candidateOf(row, values);
  return row === undefined ? { mode: "create", body: body as CreateBody } : { mode: "update", id: row.id, body: body as UpdateBody };
}

/** Activate or deactivate from the row: the current data with the other status, and the document untouched (it is not sent). */
export const toggleBodyOf = (row: Student, status: StudentStatus): UpdateBody => ({
  firstName: row.firstName,
  lastName: row.lastName,
  birthDate: row.birthDate,
  sex: row.sex,
  status,
});

/**
 * The reasons the form is refused before sending: the generated request, what is empty or blank, a name over the
 * limit once trimmed, and a document the pattern refuses (which gets a message that says what a document looks like).
 */
export function validateStudent(row: Student | undefined) {
  return (values: StudentValues): Partial<Record<StudentField, FieldReason>> => {
    const reasons: Partial<Record<StudentField, FieldReason>> = {
      ...reasonsFromSchema(row === undefined ? CreateStudentRequest : UpdateStudentRequest, candidateOf(row, values), FIELDS),
      ...requiredReasons(values, ["firstName", "lastName", "birthDate", "sex"]),
    };
    for (const name of ["firstName", "lastName"] as const) {
      if (reasons[name] === undefined && values[name].length > MAX_NAME_LENGTH) reasons[name] = "too-long";
    }
    if (reasons.nationalId !== undefined || (values.nationalId !== "" && !NATIONAL_ID_FORMAT.test(values.nationalId))) {
      reasons.nationalId = "national-id-format";
    }
    return reasons;
  };
}

export function writeStudent(token: string, save: StudentSave): Promise<ApiResult<Student>> {
  return save.mode === "create" ? createStudent(token, save.body) : updateStudent(token, save.id, save.body);
}

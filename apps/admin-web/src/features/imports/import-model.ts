import type { ImportBatchResponseOutput, RejectionResponseOutput } from "@confia/contracts/admin";
import type { AdminKey } from "../../i18n/resources.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { PROBLEM_KEYS } from "../../shared/api/problem-keys.ts";

/** The most a file may weigh. The check in the browser is a convenience: the server decides. */
export const MAX_FILE_BYTES = 5 * 1024 * 1024;

/** What a file loads, named as the routes of the API name it. The recommended order is the order of this list. */
export const IMPORT_KINDS = ["students", "guardians", "guardian-links"] as const;
export type ImportKind = (typeof IMPORT_KINDS)[number];

export function isImportKind(value: unknown): value is ImportKind {
  return typeof value === "string" && (IMPORT_KINDS as readonly string[]).includes(value);
}

/** The header of the empty template of each kind, in the order of the `CsvTemplate` of the backend (required columns first). */
export const TEMPLATE_HEADERS: Readonly<Record<ImportKind, string>> = {
  students: "legacyId,firstName,lastName,birthDate,nationalId,sex",
  guardians: "legacyId,firstName,lastName,nationalId,rtn,email,phone,address,preferredChannel",
  "guardian-links": "studentLegacyId,guardianLegacyId,relationship,financialResponsibilityPercent,isPrimaryPayer,isBillingRecipient",
};
export const TEMPLATE_FILE_NAMES: Readonly<Record<ImportKind, string>> = {
  students: "plantilla-estudiantes.csv",
  guardians: "plantilla-encargados.csv",
  "guardian-links": "plantilla-vinculos.csv",
};
export const REJECTIONS_FILE_NAME = "rechazos-importacion.csv";

/** The kind a batch of the history carries, as the kind of the routes. */
export const KIND_OF_BATCH: Readonly<Record<ImportBatchResponseOutput["kind"], ImportKind>> = {
  STUDENTS: "students",
  GUARDIANS: "guardians",
  GUARDIAN_LINKS: "guardian-links",
};

/** The columns of the template of a kind: the only names of a field the screen shows as they are. */
function columnsOf(kind: ImportKind): ReadonlySet<string> {
  return new Set(TEMPLATE_HEADERS[kind].split(","));
}

/** Why a file is refused in the browser before it is sent. The server refuses the same and more. */
export type FileProblem = "extension" | "too-large" | "empty";

/** A convenience for the person, never a guard: the extension and the size only. The content is never read. */
export function problemOfFile(file: Pick<File, "name" | "size">): FileProblem | undefined {
  if (!file.name.toLowerCase().endsWith(".csv")) return "extension";
  if (file.size === 0) return "empty";
  if (file.size > MAX_FILE_BYTES) return "too-large";
  return undefined;
}

/** The steps of the flow, in order. */
export const IMPORT_STEPS = ["choose", "simulate", "apply", "result"] as const;
export type ImportStep = (typeof IMPORT_STEPS)[number];

export type Reason = RejectionResponseOutput["reason"];

/** The name of the field of a rejection as the screen shows it: a column of the template as it is, `row` as a text of its own. */
export function fieldNameOf(field: string, kind: ImportKind): { readonly kind: "column"; readonly name: string } | { readonly kind: "row" } | { readonly kind: "unknown" } {
  if (field === "row") return { kind: "row" };
  return columnsOf(kind).has(field) ? { kind: "column", name: field } : { kind: "unknown" };
}

/** A catalog key with the values it needs. */
export interface ImportText {
  readonly key: AdminKey;
  readonly values?: Readonly<Record<string, string | number>>;
}

/** What went wrong with a request of the import, as texts to show and what the screen does about it. */
export interface ImportFailure {
  readonly texts: readonly ImportText[];
  readonly traceId?: string;
  /** The server has no simulation of this file (or of any): the way forward is to simulate. */
  readonly kind: "not-simulated" | "file-changed" | "other";
}

const FILE_REASON_KEYS: Readonly<Record<string, AdminKey>> = {
  "empty-file": "imports.fileErrors.emptyFile",
  "no-rows": "imports.fileErrors.noRows",
  "too-large": "imports.fileErrors.tooLarge",
  "too-many-rows": "imports.fileErrors.tooManyRows",
  "malformed-csv": "imports.fileErrors.malformedCsv",
  "invalid-encoding": "imports.fileErrors.invalidEncoding",
};

const COLUMNS_INDEX = /^columns\[(\d+)\]$/;
/** The most file problems the screen lists; a file with a broken header can have many. */
const MAX_FILE_TEXTS = 10;

function fileTextOf(error: { readonly field: string; readonly reason: string }, kind: ImportKind): ImportText {
  if (error.field === "file" && Object.hasOwn(FILE_REASON_KEYS, error.reason)) return { key: FILE_REASON_KEYS[error.reason] as AdminKey };
  if (error.reason === "missing-column" && columnsOf(kind).has(error.field)) return { key: "imports.fileErrors.missingColumn", values: { column: error.field } };
  const index = COLUMNS_INDEX.exec(error.field)?.[1];
  if (error.reason === "unknown-column" && index !== undefined) return { key: "imports.fileErrors.unknownColumn", values: { position: Number(index) + 1 } };
  return { key: "imports.fileErrors.other" };
}

/**
 * Sorts a rejected request: the two 409 of the import (each with the step that follows), the file that cannot be read
 * (one text per problem, never with a value of the file), the limit of imports per hour, and the rest as the general case.
 */
export function failureOfImport(error: unknown, kind: ImportKind): ImportFailure {
  if (!(error instanceof ApiFailure)) return { kind: "other", texts: [{ key: "problem.unexpected" }] };
  const { result } = error;
  if (result.kind === "network") return { kind: "other", texts: [{ key: "imports.errors.network" }] };
  if (result.kind !== "problem") return { kind: "other", texts: [{ key: "problem.unexpected" }] };
  const { code, errors, traceId } = result.problem;
  const trace = traceId === undefined ? {} : { traceId };
  if (code === "import-not-simulated") return { kind: "not-simulated", texts: [{ key: PROBLEM_KEYS[code] }], ...trace };
  if (code === "import-file-changed") return { kind: "file-changed", texts: [{ key: PROBLEM_KEYS[code] }], ...trace };
  if (code === "too-many-requests") return { kind: "other", texts: [{ key: "imports.errors.rateLimited" }], ...trace };
  if (code === "validation-failed" && errors.length > 0) {
    return { kind: "other", texts: errors.slice(0, MAX_FILE_TEXTS).map((problem) => fileTextOf(problem, kind)), ...trace };
  }
  if (code === "validation-failed") return { kind: "other", texts: [{ key: "imports.fileErrors.other" }], ...trace };
  return { kind: "other", texts: [{ key: PROBLEM_KEYS[code] }], ...trace };
}

/** U+FEFF at the start tells a spreadsheet that the file is UTF-8. */
const BYTE_ORDER_MARK = String.fromCharCode(0xfeff);

/** A cell of the CSV of rejections. Every cell here is a number or one of our own texts, but it is quoted all the same. */
const cell = (text: string): string => `"${text.replaceAll('"', '""')}"`;

/**
 * The CSV of the rejections to correct at the source: line, field and reason. It has no value of the file, and the person
 * reads it in a spreadsheet, so it has a byte order mark and the texts of the screen. Windows line endings.
 */
export function rejectionsCsv(
  rejections: readonly RejectionResponseOutput[],
  header: readonly [string, string, string],
  fieldText: (field: string) => string,
  reasonText: (reason: Reason) => string,
): string {
  const lines = [header, ...rejections.map((r) => [String(r.line), fieldText(r.field), reasonText(r.reason)] as const)];
  return `${BYTE_ORDER_MARK}${lines.map((line) => line.map(cell).join(",")).join("\r\n")}\r\n`;
}

import { randomBytes } from "node:crypto";
import {
  AwardPageResponse,
  AwardResponse,
  ChargeSimulationResponse,
  ScholarshipPageResponse,
  ScholarshipResponse,
  type AwardResponseOutput,
  type ChargeSimulationResponseOutput,
  type ScholarshipResponseOutput,
} from "@confia/contracts/admin";
import { PERMISSION_CODES } from "../shared/permissions/permission-codes.ts";
import {
  AcademicYearPageResponse,
  AccrualPeriodResponse,
  AccrualPreviewResponse,
  AccrualRunDetailResponse,
  AccrualRunPageResponse,
  AccrualRunResponse,
  ChargeVoidResponse,
  IntegrityRunDetailResponse,
  IntegrityRunPageResponse,
  IntegrityRunResponse,
  StudentChargePageResponse,
  StudentChargeResponse,
  StudentStatement,
  StudentStatementLine,
  StudentStatementSection,
  ConsentChangeResponse,
  GuardianConsentsResponse,
  AcademicYearResponse,
  AuthenticatedSessionResponse,
  ChargePlanDetailResponse,
  ChargePlanItemResponse,
  ChargePlanPageResponse,
  ChargePlanResponse,
  CurrentSessionResponse,
  EnrollmentPageResponse,
  EnrollmentResponse,
  GradePageResponse,
  GradeResponse,
  GuardianPageResponse,
  GuardianResponse,
  LedgerAccountPageResponse,
  LedgerAccountResponse,
  PostingAccountMapResponse,
  PostingAccountResponse,
  ImportBatchPageResponse,
  ImportRunResponse,
  ModalityPageResponse,
  ModalityResponse,
  PaymentConceptPageResponse,
  PaymentPageResponse,
  PaymentPostingAccountMapResponse,
  PaymentPostingAccountResponse,
  PaymentResponse,
  PaymentConceptResponse,
  PriceListDetailResponse,
  PriceListItemResponse,
  PriceListPageResponse,
  PriceListResponse,
  TaxRateDetailResponse,
  TaxRatePageResponse,
  TaxRatePeriodResponse,
  TaxRateResponse,
  ProblemDetail,
  SecondFactorPendingResponse,
  RecordedMovementResponse,
  SectionPageResponse,
  SectionResponse,
  SessionPageResponse,
  SessionResponse,
  StudentEnrollmentResponse,
  StudentGuardiansResponse,
  StudentPageResponse,
  StudentResponse,
  type AcademicYearResponseOutput,
  type AuthenticatedSessionResponseOutput,
  type ChargePlanDetailResponseOutput,
  type ChargePlanItemResponseOutput,
  type AccrualPeriodResponseOutput,
  type AccrualPreviewResponseOutput,
  type AccrualRunItemResponseOutput,
  type ChargeVoidResponseOutput,
  type IntegrityRunDetailResponseOutput,
  type IntegrityRunResponseOutput,
  type StudentChargeResponseOutput,
  type StudentStatementLineOutput,
  type StudentStatementOutput,
  type StudentStatementSectionOutput,
  type AccrualRunResponseOutput,
  type ChargePlanResponseOutput,
  type ConsentChangeResponseOutput,
  type ConsentEventResponseOutput,
  type ImportBatchResponseOutput,
  type RejectionResponseOutput,
  type ConsentStandingResponseOutput,
  type CurrentSessionResponseOutput,
  type GuardianConsentsResponseOutput,
  type EnrollmentResponseOutput,
  type EnrollmentTransferResponseOutput,
  type GradeResponseOutput,
  type GuardianResponseOutput,
  type LedgerAccountResponseOutput,
  type LinkedStudentResponseOutput,
  type PostingAccountResponseOutput,
  type ModalityResponseOutput,
  type PaymentConceptResponseOutput,
  type PaymentPostingAccountResponseOutput,
  type PaymentResponseOutput,
  type PaymentSummaryResponseOutput,
  type PriceListDetailResponseOutput,
  type PriceListItemResponseOutput,
  type PriceListResponseOutput,
  type TaxRateDetailResponseOutput,
  type TaxRatePeriodResponseOutput,
  type TaxRateResponseOutput,
  type SecondFactorPendingResponseOutput,
  type SectionResponseOutput,
  type SessionResponseOutput,
  type SessionSummaryResponseOutput,
  type StudentEnrollmentResponseOutput,
  type StudentGuardianResponseOutput,
  type StudentGuardiansResponseOutput,
  type StudentResponseOutput,
} from "@confia/contracts/admin";
import type { ProblemCode } from "../shared/api/problem.ts";

/**
 * The responses the tests, and later the simulated API, answer with (design.md D15). One source: each
 * builder checks its body against the generated contract before returning it, so a contract that moves
 * breaks the test that uses it. Tokens, secrets and codes are made at run time and never written here.
 */
export interface ContractResponse {
  readonly status: number;
  readonly body: unknown;
  /** Absent when there is no body. */
  readonly contentType: string | undefined;
}

/** What the contract refused. It names the response and the fields and never a value (CLAUDE.md rule 11). */
export class ContractViolation extends Error {
  constructor(responseName: string, fields: readonly string[]) {
    super(`${responseName} breaks the contract at: ${fields.length === 0 ? "(root)" : fields.join(", ")}`);
    this.name = "ContractViolation";
  }
}

interface Schema<T> {
  safeParse(input: unknown):
    | { readonly success: true; readonly data: T }
    | { readonly success: false; readonly error: { readonly issues: readonly { readonly path: readonly PropertyKey[] }[] } };
}

function checked<T>(name: string, schema: Schema<T>, body: unknown): T {
  const parsed = schema.safeParse(body);
  if (parsed.success) return parsed.data;
  throw new ContractViolation(
    name,
    parsed.error.issues.map((issue) => issue.path.map(String).join(".")).filter((path) => path !== ""),
  );
}

const JSON_TYPE = "application/json";
const MINUTE_MS = 60_000;
const BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export interface BuildOptions<T> {
  /** The clock the expiry times are derived from. */
  readonly now?: Date;
  /** Fields to replace. A value that breaks the contract is refused, which is how a test proves it. */
  readonly override?: Partial<T>;
}

function opaque(): string {
  return randomBytes(24).toString("base64url");
}

function after(now: Date, minutes: number): string {
  return new Date(now.getTime() + minutes * MINUTE_MS).toISOString();
}

export function authenticatedSession(options: BuildOptions<AuthenticatedSessionResponseOutput> = {}): ContractResponse {
  const now = options.now ?? new Date();
  const body = checked("AuthenticatedSessionResponse", AuthenticatedSessionResponse, {
    status: "authenticated",
    accessToken: opaque(),
    accessTokenExpiresAt: after(now, 15),
    idleExpiresAt: after(now, 30),
    sessionExpiresAt: after(now, 8 * 60),
    ...options.override,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

function secondFactorPending(
  status: SecondFactorPendingResponseOutput["status"],
  options: BuildOptions<SecondFactorPendingResponseOutput>,
): ContractResponse {
  const now = options.now ?? new Date();
  const body = checked("SecondFactorPendingResponse", SecondFactorPendingResponse, {
    status,
    secondFactorToken: opaque(),
    secondFactorTokenExpiresAt: after(now, 5),
    ...options.override,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

export function secondFactorRequired(options: BuildOptions<SecondFactorPendingResponseOutput> = {}): ContractResponse {
  return secondFactorPending("second-factor-required", options);
}

export function secondFactorEnrollmentRequired(
  options: BuildOptions<SecondFactorPendingResponseOutput> = {},
): ContractResponse {
  return secondFactorPending("second-factor-enrollment-required", options);
}

export function currentSession(options: BuildOptions<CurrentSessionResponseOutput> = {}): ContractResponse {
  const now = options.now ?? new Date();
  const body = checked("CurrentSessionResponse", CurrentSessionResponse, {
    accessTokenExpiresAt: after(now, 15),
    accountId: crypto.randomUUID(),
    authenticationMethods: ["pwd", "otp"],
    idleExpiresAt: after(now, 30),
    institutionId: crypto.randomUUID(),
    // Every code the panel knows: the journeys run with a role that can do all of them, and a scenario narrows it.
    permissions: [...PERMISSION_CODES].sort(),
    sessionExpiresAt: after(now, 8 * 60),
    sessionId: crypto.randomUUID(),
    ...options.override,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

function base32Secret(): string {
  return Array.from(randomBytes(32), (byte) => BASE32.charAt(byte % BASE32.length)).join("");
}

export function enrollment(options: Pick<BuildOptions<EnrollmentResponseOutput>, "override"> = {}): ContractResponse {
  const body = checked("EnrollmentResponse", EnrollmentResponse, {
    secret: base32Secret(),
    recoveryCodes: Array.from({ length: 10 }, () => randomBytes(5).toString("hex")),
    ...options.override,
  });
  return { status: 201, body, contentType: JSON_TYPE };
}

export function noContent(): ContractResponse {
  return { status: 204, body: undefined, contentType: undefined };
}

export interface ProblemOptions {
  readonly traceId?: string;
  readonly errors?: readonly { readonly field: string; readonly reason: string }[];
}

/**
 * An error response as the backend emits it: the type of its code, and an `instance` that is a relative
 * path (ProblemResponses.java), which `zod.url()` would refuse. The body is checked against the problem
 * contract with `instance` set aside for that reason (design.md D7).
 */
export function problem(code: ProblemCode, status: number, options: ProblemOptions = {}): ContractResponse {
  if (status < 400 || status > 599) throw new ContractViolation("ProblemDetail", ["status"]);
  const body = checked("ProblemDetail", ProblemDetail.omit({ instance: true }), {
    type: `https://confia.hn/problems/${code}`,
    status,
    ...(options.traceId === undefined ? {} : { traceId: options.traceId }),
    ...(options.errors === undefined ? {} : { errors: options.errors }),
  });
  return { status, body: { ...body, instance: "/api/v1/auth/sessions" }, contentType: "application/problem+json" };
}

// The academic structure (organization-academic-structure cut). Rows and pages are checked against the
// generated contract like every other response here.
const STAMP = "2026-11-01T12:00:00Z";

export type OrganizationResource = "academic-years" | "modalities" | "grades" | "sections";

const ROW_SCHEMAS: Record<OrganizationResource, Schema<unknown>> = {
  "academic-years": AcademicYearResponse,
  modalities: ModalityResponse,
  grades: GradeResponse,
  sections: SectionResponse,
};

const PAGE_SCHEMAS: Record<OrganizationResource, Schema<unknown>> = {
  "academic-years": AcademicYearPageResponse,
  modalities: ModalityPageResponse,
  grades: GradePageResponse,
  sections: SectionPageResponse,
};

const stamped = { createdAt: STAMP, updatedAt: STAMP, isActive: true } as const;

export function academicYear(override: Partial<AcademicYearResponseOutput> = {}): AcademicYearResponseOutput {
  return checked("AcademicYearResponse", AcademicYearResponse, {
    id: crypto.randomUUID(),
    name: "Año lectivo 2026",
    startDate: "2026-01-15",
    endDate: "2026-11-30",
    status: "PLANNED",
    ...stamped,
    ...override,
  });
}

export function modality(override: Partial<ModalityResponseOutput> = {}): ModalityResponseOutput {
  return checked("ModalityResponse", ModalityResponse, { id: crypto.randomUUID(), code: "PRI", name: "Primaria", ...stamped, ...override });
}

export function grade(override: Partial<GradeResponseOutput> = {}): GradeResponseOutput {
  return checked("GradeResponse", GradeResponse, {
    id: crypto.randomUUID(),
    modalityId: crypto.randomUUID(),
    name: "Primero",
    ordinal: 1,
    ...stamped,
    ...override,
  });
}

export function section(override: Partial<SectionResponseOutput> = {}): SectionResponseOutput {
  return checked("SectionResponse", SectionResponse, {
    id: crypto.randomUUID(),
    academicYearId: crypto.randomUUID(),
    gradeId: crypto.randomUUID(),
    name: "A",
    capacity: 30,
    ...stamped,
    ...override,
  });
}

/** One page of a listing, as the server serves it: `total` counts every page, `items` only this one. */
export function organizationPage(
  resource: OrganizationResource,
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked(`${resource} page`, PAGE_SCHEMAS[resource], {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The resource a create (201) or an edit (200) answers with. */
export function organizationRecord(resource: OrganizationResource, row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked(`${resource} record`, ROW_SCHEMAS[resource], row), contentType: JSON_TYPE };
}

// The students registry cut. The national id is never a field of a response: a row says only whether there is
// a document.
export function student(override: Partial<StudentResponseOutput> = {}): StudentResponseOutput {
  return checked("StudentResponse", StudentResponse, {
    id: crypto.randomUUID(),
    studentCode: "E-000001",
    firstName: "Ana",
    lastName: "Pérez",
    birthDate: "2014-03-09",
    sex: "F",
    status: "ACTIVE",
    hasNationalId: false,
    createdAt: STAMP,
    updatedAt: STAMP,
    ...override,
  });
}

/** One page of students, as the server serves it. */
export function studentPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("students page", StudentPageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The student a create (201) or an edit (200) answers with. */
export function studentRecord(row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked("student record", StudentResponse, row), contentType: JSON_TYPE };
}

// The enrollments cut. A list holds rows with no `transfers`; the detail and the three writes carry them, oldest first.
// Never the document or the birth date of the student.
export function studentEnrollment(override: Partial<StudentEnrollmentResponseOutput> = {}): StudentEnrollmentResponseOutput {
  return checked("StudentEnrollmentResponse", StudentEnrollmentResponse, {
    id: crypto.randomUUID(),
    studentId: crypto.randomUUID(),
    studentCode: "E-000001",
    studentFirstName: "Ana",
    studentLastName: "Pérez",
    academicYearId: "11111111-1111-4111-8111-111111111111",
    academicYearName: "Año lectivo 2026",
    sectionId: "22222222-2222-4222-8222-222222222222",
    sectionName: "A",
    gradeId: "33333333-3333-4333-8333-333333333333",
    gradeName: "Primero",
    modalityName: "Primaria",
    enrolledOn: "2026-02-02",
    status: "ACTIVE",
    withdrawnOn: null,
    withdrawalReason: null,
    createdAt: STAMP,
    updatedAt: STAMP,
    ...override,
  });
}

/** A section change of an enrollment, as the detail lists it. */
export function enrollmentTransfer(override: Partial<EnrollmentTransferResponseOutput> = {}): EnrollmentTransferResponseOutput {
  return {
    id: crypto.randomUUID(),
    fromSectionId: "22222222-2222-4222-8222-222222222222",
    fromSectionName: "A",
    toSectionId: "44444444-4444-4444-8444-444444444444",
    toSectionName: "B",
    transferredOn: "2026-03-02",
    reason: "Cambio de turno",
    transferredBy: crypto.randomUUID(),
    createdAt: STAMP,
    ...override,
  };
}

/** One page of enrollments, as the server serves it. */
export function enrollmentPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("enrollments page", EnrollmentPageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 50,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The enrollment a detail (200), an enroll (201), a transfer or an exit (200) answers with. */
export function enrollmentRecord(row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked("enrollment record", StudentEnrollmentResponse, row), contentType: JSON_TYPE };
}

// The guardians cut. A guardian is never shown with a document or an RTN: only whether there is one. A list holds rows with
// no `students`; the detail and the two writes carry them.
export function guardian(override: Partial<GuardianResponseOutput> = {}): GuardianResponseOutput {
  return checked("GuardianResponse", GuardianResponse, {
    id: crypto.randomUUID(),
    guardianCode: "P-000001",
    firstName: "Rosa",
    lastName: "Mejía",
    hasNationalId: false,
    hasRtn: false,
    email: null,
    phone: null,
    address: null,
    preferredChannel: "NONE",
    status: "ACTIVE",
    createdAt: STAMP,
    updatedAt: STAMP,
    ...override,
  });
}

/** The student a guardian detail lists as linked now. */
export function linkedStudent(override: Partial<LinkedStudentResponseOutput> = {}): LinkedStudentResponseOutput {
  return {
    linkId: crypto.randomUUID(),
    studentId: crypto.randomUUID(),
    studentCode: "E-000001",
    studentFirstName: "Ana",
    studentLastName: "Pérez",
    relationship: "MOTHER",
    financialResponsibilityPercent: 100,
    isPrimaryPayer: true,
    isBillingRecipient: true,
    validFrom: "2026-03-02",
    ...override,
  };
}

/** One page of guardians, as the server serves it. */
export function guardianPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("guardians page", GuardianPageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The guardian a detail (200), a create (201) or an edit (200) answers with. */
export function guardianRecord(row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked("guardian record", GuardianResponse, row), contentType: JSON_TYPE };
}

/** One current link of a student, as the read of the guardians of a student lists it. */
export function studentLink(override: Partial<StudentGuardianResponseOutput> = {}): StudentGuardianResponseOutput {
  return {
    linkId: crypto.randomUUID(),
    guardianId: crypto.randomUUID(),
    guardianCode: "P-000001",
    guardianFirstName: "Rosa",
    guardianLastName: "Mejía",
    guardianStatus: "ACTIVE",
    relationship: "MOTHER",
    financialResponsibilityPercent: 100,
    isPrimaryPayer: true,
    isBillingRecipient: true,
    validFrom: "2026-03-02",
    ...override,
  };
}

/** The set of guardians of a student (read, and answer of the replace), with the sum the server calculated. */
export function studentGuardians(
  links: readonly StudentGuardianResponseOutput[],
  override: Partial<Omit<StudentGuardiansResponseOutput, "links">> = {},
): ContractResponse {
  const body = checked("student guardians", StudentGuardiansResponse, {
    studentId: "00000000-0000-4000-8000-000000005001",
    studentCode: "E-000001",
    totalPercent: links.length === 0 ? 0 : 100,
    links,
    ...override,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

// The consents of a guardian (guardians-consent cut). The six standings always come: channel by channel, institutional first.
const CONSENT_CHANNELS = ["EMAIL", "SMS", "WHATSAPP"] as const;
const CONSENT_PURPOSES = ["INSTITUTIONAL", "MARKETING"] as const;

/** The six standings, every one `NEVER_GRANTED` unless an override replaces it by `channel` and `purpose`. */
export function consentStandings(overrides: readonly Partial<ConsentStandingResponseOutput>[] = []): ConsentStandingResponseOutput[] {
  return CONSENT_CHANNELS.flatMap((channel) =>
    CONSENT_PURPOSES.map((purpose) => {
      const found = overrides.find((entry) => entry.channel === channel && entry.purpose === purpose);
      return { channel, purpose, state: "NEVER_GRANTED" as const, since: null, policyVersion: null, evidenceSource: null, ...found };
    }),
  );
}

/** One event of the history of consents. */
export function consentEvent(override: Partial<ConsentEventResponseOutput> = {}): ConsentEventResponseOutput {
  return {
    id: crypto.randomUUID(),
    channel: "EMAIL",
    purpose: "INSTITUTIONAL",
    action: "GRANT",
    evidenceSource: "SIGNED_PAPER_FORM",
    policyVersion: "2026-01",
    occurredAt: "2026-11-02T15:30:00Z",
    ...override,
  };
}

/** The answer of the read of the consents of a guardian. */
export function guardianConsents(
  states: readonly Partial<ConsentStandingResponseOutput>[] = [],
  history: readonly ConsentEventResponseOutput[] = [],
  guardianId: string = crypto.randomUUID(),
): ContractResponse {
  const body: GuardianConsentsResponseOutput = checked("guardian consents", GuardianConsentsResponse, { guardianId, states: consentStandings(states), history });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The answer of recording a consent. */
export function consentChange(override: Partial<ConsentChangeResponseOutput> = {}): ContractResponse {
  const body = checked("consent change", ConsentChangeResponse, {
    channel: "EMAIL",
    purpose: "INSTITUTIONAL",
    state: "GRANTED",
    since: "2026-11-02T15:30:00Z",
    policyVersion: "2026-01",
    evidenceSource: "SIGNED_PAPER_FORM",
    changed: true,
    ...override,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

// The student import (imports-students cut). A batch holds counts and a hash, never a value of the file.
const SHA256_OF_NOTHING = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

/** A batch as the API gives it: counts that add up (`total = valid + rejected + skipped`), and a lowercase SHA-256. */
export function importBatch(override: Partial<ImportBatchResponseOutput> = {}): ImportBatchResponseOutput {
  return {
    id: crypto.randomUUID(),
    kind: "STUDENTS",
    status: "VALIDATED",
    fileSha256: SHA256_OF_NOTHING,
    totalRows: 10,
    validRows: 8,
    rejectedRows: 2,
    createdRows: 0,
    skippedRows: 0,
    createdAt: "2026-11-10T15:30:00Z",
    appliedAt: null,
    ...override,
  };
}

/** The answer of a simulation or an apply: the batch, up to 200 rejections (line, field, reason) and whether more were left out. */
export function importRun(
  run: { readonly batch?: Partial<ImportBatchResponseOutput>; readonly rejections?: readonly RejectionResponseOutput[]; readonly truncated?: boolean } = {},
): ContractResponse {
  const body = checked("import run", ImportRunResponse, {
    batch: importBatch(run.batch),
    rejections: run.rejections ?? [],
    rejectionsTruncated: run.truncated ?? false,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** One page of batches, as the server serves it. */
export function importBatchPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("import batches page", ImportBatchPageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

// The catalog cut: the payment concepts.
export function paymentConcept(override: Partial<PaymentConceptResponseOutput> = {}): PaymentConceptResponseOutput {
  return checked("PaymentConceptResponse", PaymentConceptResponse, {
    id: crypto.randomUUID(),
    code: "COL",
    name: "Colegiatura",
    category: "TUITION",
    isRecurring: true,
    isTaxable: true,
    version: 0,
    ...stamped,
    ...override,
  });
}

/** One page of payment concepts, as the server serves it: `total` counts every page, `items` only this one. */
export function paymentConceptPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("payment concepts page", PaymentConceptPageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The payment concept a create (201) or an edit (200) answers with. */
export function paymentConceptRecord(row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked("payment concept record", PaymentConceptResponse, row), contentType: JSON_TYPE };
}

// The catalog cut of the price lists. Amounts are strings, as the server rounds and sends them.
export function priceList(override: Partial<PriceListResponseOutput> = {}): PriceListResponseOutput {
  return checked("PriceListResponse", PriceListResponse, {
    id: crypto.randomUUID(),
    academicYearId: "00000000-0000-4000-8000-000000000a01",
    name: "2027 — Institución",
    scope: "INSTITUTION",
    scopeRefId: null,
    status: "ACTIVE",
    version: 0,
    createdAt: STAMP,
    updatedAt: STAMP,
    ...override,
  });
}

/** One price of a list: a concept, an amount as text and its validity (`validTo` null while it is open). */
export function priceListItem(override: Partial<PriceListItemResponseOutput> = {}): PriceListItemResponseOutput {
  return checked("PriceListItemResponse", PriceListItemResponse, {
    id: crypto.randomUUID(),
    priceListId: "00000000-0000-4000-8000-000000000b01",
    paymentConceptId: "00000000-0000-4000-8000-000000007001",
    price: { amount: "1500.00", currency: "HNL" },
    validFrom: "2027-01-01",
    validTo: null,
    createdAt: STAMP,
    ...override,
  });
}

/** The list with all its prices, as `GET /price-lists/{id}` answers. */
export function priceListDetail(override: Partial<PriceListDetailResponseOutput> = {}): PriceListDetailResponseOutput {
  return checked("PriceListDetailResponse", PriceListDetailResponse, { ...priceList(), items: [], ...override });
}

export function priceListPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("price lists page", PriceListPageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The list a create (201) or an edit (200) answers with. */
export function priceListRecord(row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked("price list record", PriceListResponse, row), contentType: JSON_TYPE };
}

export function priceListDetailRecord(row: unknown): ContractResponse {
  return { status: 200, body: checked("price list detail", PriceListDetailResponse, row), contentType: JSON_TYPE };
}

/** The 201 of a new price. */
export function priceListItemRecord(row: unknown): ContractResponse {
  return { status: 201, body: checked("price list item", PriceListItemResponse, row), contentType: JSON_TYPE };
}

// The catalog cut of the tax rates. A percentage is a string with four decimals, as the server sends it; the values the
// tests give are fictitious, and nothing here is a rate that any institution uses.
export function taxRate(override: Partial<TaxRateResponseOutput> = {}): TaxRateResponseOutput {
  return checked("TaxRateResponse", TaxRateResponse, {
    id: crypto.randomUUID(),
    code: "IMP-A",
    name: "Impuesto de prueba A",
    version: 0,
    ...stamped,
    ...override,
  });
}

/** One validity of a tax rate: a percentage as text and its dates (`validTo` null while it is open). */
export function taxRatePeriod(override: Partial<TaxRatePeriodResponseOutput> = {}): TaxRatePeriodResponseOutput {
  return checked("TaxRatePeriodResponse", TaxRatePeriodResponse, {
    id: crypto.randomUUID(),
    taxRateId: "00000000-0000-4000-8000-000000000c01",
    ratePercent: "12.3400",
    validFrom: "2027-01-01",
    validTo: null,
    createdAt: STAMP,
    ...override,
  });
}

/** The tax rate with all its validities, as `GET /tax-rates/{id}` answers. */
export function taxRateDetail(override: Partial<TaxRateDetailResponseOutput> = {}): TaxRateDetailResponseOutput {
  return checked("TaxRateDetailResponse", TaxRateDetailResponse, { ...taxRate(), periods: [], ...override });
}

export function taxRatePage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("tax rates page", TaxRatePageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The tax rate a create (201) or an edit (200) answers with. */
export function taxRateRecord(row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked("tax rate record", TaxRateResponse, row), contentType: JSON_TYPE };
}

export function taxRateDetailRecord(row: unknown): ContractResponse {
  return { status: 200, body: checked("tax rate detail", TaxRateDetailResponse, row), contentType: JSON_TYPE };
}

/** The 201 of a new validity. */
export function taxRatePeriodRecord(row: unknown): ContractResponse {
  return { status: 201, body: checked("tax rate period", TaxRatePeriodResponse, row), contentType: JSON_TYPE };
}

// The charges cut of the plans. A plan holds no price and no amount: it names who it covers, how often, and which concepts.
export function chargePlan(override: Partial<ChargePlanResponseOutput> = {}): ChargePlanResponseOutput {
  return checked("ChargePlanResponse", ChargePlanResponse, {
    id: crypto.randomUUID(),
    academicYearId: "00000000-0000-4000-8000-000000001030",
    name: "Primaria 2027",
    targetScope: "GRADE",
    targetRefId: "00000000-0000-4000-8000-000000003001",
    frequency: "MONTHLY",
    dueDayOfMonth: 5,
    status: "DRAFT",
    version: 0,
    createdAt: STAMP,
    updatedAt: STAMP,
    ...override,
  });
}

/** One item of a plan: a concept, how many periods it accrues and the first day of the first one. */
export function chargePlanItem(override: Partial<ChargePlanItemResponseOutput> = {}): ChargePlanItemResponseOutput {
  return checked("ChargePlanItemResponse", ChargePlanItemResponse, {
    id: crypto.randomUUID(),
    planId: "00000000-0000-4000-8000-000000000c01",
    paymentConceptId: "00000000-0000-4000-8000-000000007001",
    periodsCount: 10,
    firstPeriodOn: "2027-02-01",
    createdAt: STAMP,
    ...override,
  });
}

/** The plan with its items, as `GET /plans/{id}` answers. */
export function chargePlanDetail(override: Partial<ChargePlanDetailResponseOutput> = {}): ChargePlanDetailResponseOutput {
  return checked("ChargePlanDetailResponse", ChargePlanDetailResponse, { ...chargePlan(), items: [], ...override });
}

export function chargePlanPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("charge plans page", ChargePlanPageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The plan a create (201) or an edit (200) answers with. */
export function chargePlanRecord(row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked("charge plan record", ChargePlanResponse, row), contentType: JSON_TYPE };
}

export function chargePlanDetailRecord(row: unknown): ContractResponse {
  return { status: 200, body: checked("charge plan detail", ChargePlanDetailResponse, row), contentType: JSON_TYPE };
}

/** The 201 of a new item. */
export function chargePlanItemRecord(row: unknown): ContractResponse {
  return { status: 201, body: checked("charge plan item", ChargePlanItemResponse, row), contentType: JSON_TYPE };
}

// The accrual engine (charges-accrual cut). The amounts are strings the server already rounded: a test never computes one.
const MONEY = (amount: string) => ({ amount, currency: "HNL" });

/** One run, as the API answers it. */
export function accrualRun(override: Partial<AccrualRunResponseOutput> = {}): AccrualRunResponseOutput {
  return checked("AccrualRunResponse", AccrualRunResponse, {
    id: crypto.randomUUID(),
    planId: "00000000-0000-4000-8000-000000000c01",
    periodStart: "2027-02-01",
    status: "COMPLETED",
    previewChecked: true,
    queuedAt: STAMP,
    startedAt: STAMP,
    finishedAt: STAMP,
    studentsEvaluated: 2,
    chargesCreated: 2,
    chargesExisting: 0,
    chargesFailed: 0,
    ...override,
  });
}

/** One period of a plan, with its last run when it has one. */
export function accrualPeriod(override: Partial<AccrualPeriodResponseOutput> = {}): AccrualPeriodResponseOutput {
  return checked("AccrualPeriodResponse", AccrualPeriodResponse, {
    periodStart: "2027-02-01",
    label: "2027-02",
    dueOn: "2027-02-05",
    paymentConceptIds: ["00000000-0000-4000-8000-000000007001"],
    ...override,
  });
}

export function accrualPeriodsRecord(periods: readonly unknown[]): ContractResponse {
  return { status: 200, body: periods.map((period) => checked("accrual period", AccrualPeriodResponse, period)), contentType: JSON_TYPE };
}

/** One row of a preview or of a run: the amounts are present only for a charge to create. */
export function accrualPreviewItem(override: Partial<AccrualPreviewResponseOutput["items"][number]> = {}): AccrualPreviewResponseOutput["items"][number] {
  return {
    studentId: "00000000-0000-4000-8000-000000005001",
    paymentConceptId: "00000000-0000-4000-8000-000000007001",
    outcome: "CREATED",
    base: MONEY("1500.00"),
    benefitAmount: MONEY("0.00"),
    netBase: MONEY("1500.00"),
    tax: MONEY("225.00"),
    total: MONEY("1725.00"),
    ...override,
  };
}

/** What a preview answers: two charges to create and one error by default. */
export function accrualPreview(override: Partial<AccrualPreviewResponseOutput> = {}): AccrualPreviewResponseOutput {
  return checked("AccrualPreviewResponse", AccrualPreviewResponse, {
    planId: "00000000-0000-4000-8000-000000000c01",
    periodStart: "2027-02-01",
    studentsEvaluated: 3,
    toCreate: 2,
    alreadyAccrued: 0,
    alreadyAccruedOtherPlan: 0,
    errors: 1,
    totals: [{ currency: "HNL", charges: 2, base: MONEY("3000.00"), benefitAmount: MONEY("0.00"), netBase: MONEY("3000.00"), tax: MONEY("450.00"), total: MONEY("3450.00") }],
    errorsByCode: { NO_PRICE: 1 },
    items: [
      accrualPreviewItem({ studentId: "00000000-0000-4000-8000-000000005001" }),
      accrualPreviewItem({ studentId: "00000000-0000-4000-8000-000000005002" }),
      { studentId: "00000000-0000-4000-8000-000000005003", paymentConceptId: "00000000-0000-4000-8000-000000007001", outcome: "ERROR", errorCode: "NO_PRICE" },
    ],
    itemsTruncated: false,
    blockers: [],
    previewDigest: "a".repeat(64),
    ...override,
  });
}

export function accrualPreviewRecord(row: unknown): ContractResponse {
  return { status: 200, body: checked("accrual preview", AccrualPreviewResponse, row), contentType: JSON_TYPE };
}

/** The 202 of a request: the run, queued. */
export function accrualRunRecord(row: unknown, status: 200 | 202 = 202): ContractResponse {
  return { status, body: checked("accrual run", AccrualRunResponse, row), contentType: JSON_TYPE };
}

export function accrualRunItem(override: Partial<AccrualRunItemResponseOutput> = {}): AccrualRunItemResponseOutput {
  return {
    studentId: "00000000-0000-4000-8000-000000005001",
    paymentConceptId: "00000000-0000-4000-8000-000000007001",
    outcome: "CREATED",
    chargeId: "00000000-0000-4000-8000-000000008001",
    ...override,
  };
}

export function accrualRunPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("accrual runs page", AccrualRunPageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

export function accrualRunDetailRecord(
  run: unknown,
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("accrual run detail", AccrualRunDetailResponse, {
    run,
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    totalItems: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

// The chart of accounts and the map of posting accounts (ledger-chart-of-accounts cut). The codes and names are fictitious.
export function ledgerAccount(override: Partial<LedgerAccountResponseOutput> = {}): LedgerAccountResponseOutput {
  return checked("LedgerAccountResponse", LedgerAccountResponse, {
    id: crypto.randomUUID(),
    code: "ACT-01",
    name: "Cuentas por cobrar de prueba",
    type: "ASSET",
    holdsStudentBalances: true,
    active: true,
    version: 0,
    createdAt: STAMP,
    updatedAt: STAMP,
    ...override,
  });
}

/** One page of accounts, as the server serves it: `total` counts every page, `items` only this one. */
export function ledgerAccountPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("ledger accounts page", LedgerAccountPageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The account a create (201) or an edit (200) answers with. */
export function ledgerAccountRecord(row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked("ledger account record", LedgerAccountResponse, row), contentType: JSON_TYPE };
}

/** One row of the map of posting accounts. */
export function postingAccount(override: Partial<PostingAccountResponseOutput> = {}): PostingAccountResponseOutput {
  return checked("PostingAccountResponse", PostingAccountResponse, {
    id: crypto.randomUUID(),
    purpose: "RECEIVABLE",
    conceptCategory: null,
    ledgerAccountId: crypto.randomUUID(),
    version: 0,
    updatedAt: STAMP,
    ...override,
  });
}

/** The map as `GET` and `PUT /charges/posting-accounts` answer it: the rows that were ever set. */
export function postingAccountMap(items: readonly unknown[]): ContractResponse {
  return { status: 200, body: checked("posting account map", PostingAccountMapResponse, { items }), contentType: JSON_TYPE };
}

// The statement of account of a student (ledger-account-statement cut). Every figure is the string a server would send; the
// fixtures hold values chosen by hand and never compute one (the running balance of a line is typed, not summed).
const ZERO_HNL = { amount: "0.00", currency: "HNL" };

export function statementLine(override: Partial<StudentStatementLineOutput> = {}): StudentStatementLineOutput {
  return checked("StudentStatementLine", StudentStatementLine, {
    transactionId: "00000000-0000-4000-8000-00000000a001",
    kind: "CHARGE",
    effectiveOn: "2027-02-01",
    postedAt: STAMP,
    lineNumber: 1,
    debit: { amount: "1500.00", currency: "HNL" },
    credit: ZERO_HNL,
    balance: { amount: "1500.00", currency: "HNL" },
    charge: {
      chargeId: "00000000-0000-4000-8000-00000000b001",
      paymentConceptId: "00000000-0000-4000-8000-000000007001",
      conceptCode: "COL",
      conceptName: "Colegiatura",
      conceptCategory: "TUITION",
      periodLabel: "2027-02",
      periodStart: "2027-02-01",
      periodEnd: "2027-02-28",
      dueOn: "2027-02-10",
      origin: "AUTOMATIC",
    },
    ...override,
  });
}

export function statementSection(override: Partial<StudentStatementSectionOutput> = {}): StudentStatementSectionOutput {
  const lines = override.lines ?? [statementLine()];
  return checked("StudentStatementSection", StudentStatementSection, {
    currency: "HNL",
    openingBalance: ZERO_HNL,
    closingBalance: { amount: "1500.00", currency: "HNL" },
    totalLines: lines.length,
    page: 0,
    size: 25,
    lines,
    ...override,
  });
}

export function studentStatement(override: Partial<StudentStatementOutput> = {}): StudentStatementOutput {
  return checked("StudentStatement", StudentStatement, {
    studentId: "00000000-0000-4000-8000-000000005001",
    studentCode: "E-000001",
    firstName: "Ana",
    lastName: "Aguilar",
    from: "2026-11-17",
    to: "2027-11-16",
    sections: [statementSection()],
    ...override,
  });
}

export function statementRecord(body: StudentStatementOutput = studentStatement()): ContractResponse {
  return { status: 200, body, contentType: JSON_TYPE };
}

const money = (amount: string, currency = "HNL") => ({ amount, currency });

/** One charge of a student as the list answers it: active, with its base, tax and total as the server rounds them. */
export function studentCharge(override: Partial<StudentChargeResponseOutput> = {}): StudentChargeResponseOutput {
  return checked("StudentChargeResponse", StudentChargeResponse, {
    id: crypto.randomUUID(),
    paymentConceptId: "00000000-0000-4000-8000-000000007001",
    conceptCode: "MENS",
    conceptName: "Mensualidad",
    conceptCategory: "TUITION",
    origin: "AUTOMATIC",
    periodStart: "2027-02-01",
    periodEnd: "2027-02-28",
    periodLabel: "2027-02",
    dueOn: "2027-02-05",
    base: money("1000.00"),
    benefitAmount: money("0.00"),
    netBase: money("1000.00"),
    tax: money("150.00"),
    total: money("1150.00"),
    status: "ACTIVE",
    paid: money("0.00"),
    outstanding: money("1150.00"),
    settled: false,
    ...override,
  });
}

export function studentChargePage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("student charges page", StudentChargePageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** The answer of a void: the charge is now voided and its reversal posted. */
export function chargeVoid(override: Partial<ChargeVoidResponseOutput> = {}): ContractResponse {
  const body = checked("ChargeVoidResponse", ChargeVoidResponse, {
    id: crypto.randomUUID(),
    chargeId: "00000000-0000-4000-8000-00000000d001",
    status: "VOIDED",
    reason: "Cargo generado por error en el período equivocado",
    reversalTransactionId: crypto.randomUUID(),
    voidedAt: STAMP,
    voidedBy: "00000000-0000-4000-8000-0000000000a1",
    ...override,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** One integrity run, as the list answers it. */
export function integrityRun(override: Partial<IntegrityRunResponseOutput> = {}): IntegrityRunResponseOutput {
  return checked("IntegrityRunResponse", IntegrityRunResponse, {
    id: crypto.randomUUID(),
    status: "OK",
    startedAt: STAMP,
    finishedAt: STAMP,
    chargesChecked: 30,
    postingsChecked: 31,
    discrepancyCount: 0,
    ...override,
  });
}

export function integrityRunPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("integrity runs page", IntegrityRunPageResponse, {
    items,
    page: paging.page ?? 0,
    size: paging.size ?? 25,
    total: paging.total ?? items.length,
  });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** One run with its confirmed discrepancies (none for a clean run). */
export function integrityRunDetail(run: unknown, findings: readonly unknown[] = []): ContractResponse {
  const body: IntegrityRunDetailResponseOutput = checked("IntegrityRunDetailResponse", IntegrityRunDetailResponse, { run, findings });
  return { status: 200, body, contentType: JSON_TYPE };
}

// The cash sessions (cashbox-sessions cut). Every amount is a `{ amount, currency }` string the way the server rounds it.
const HNL = (amount: string) => ({ amount, currency: "HNL" });
type CashMovementBody = SessionResponseOutput["movements"][number];

/** A session of the cashbox, open with its float by default, as the current session and the detail answer it. */
export function cashSessionBody(override: Partial<SessionResponseOutput> = {}): SessionResponseOutput {
  return checked("SessionResponse", SessionResponse, {
    id: crypto.randomUUID(),
    cashierId: "00000000-0000-4000-8000-0000000000a1",
    status: "open",
    openedAt: STAMP,
    openingFloat: HNL("500.00"),
    expectedAmount: HNL("500.00"),
    movements: [],
    ...override,
  });
}

export function cashSession(override: Partial<SessionResponseOutput> = {}, status = 200): ContractResponse {
  return { status, body: cashSessionBody(override), contentType: JSON_TYPE };
}

/** One movement of a session. */
export function cashMovement(override: Partial<CashMovementBody> = {}): CashMovementBody {
  return {
    id: crypto.randomUUID(),
    sessionId: "00000000-0000-4000-8000-0000000c0001",
    type: "deposit",
    amount: HNL("100.00"),
    createdAt: STAMP,
    createdBy: "00000000-0000-4000-8000-0000000000a1",
    ...override,
  };
}

/** The answer of a movement: the movement and the expected cash after it. */
export function recordedMovement(movement: Partial<CashMovementBody> = {}, expectedAfter = "600.00"): ContractResponse {
  const body = checked("RecordedMovementResponse", RecordedMovementResponse, { movement: cashMovement(movement), expectedAfter: HNL(expectedAfter) });
  return { status: 201, body, contentType: JSON_TYPE };
}

/** One row of the list of sessions. */
export function cashSessionRow(override: Partial<SessionSummaryResponseOutput> = {}): SessionSummaryResponseOutput {
  return {
    id: crypto.randomUUID(),
    cashierId: "00000000-0000-4000-8000-0000000000a1",
    status: "open",
    openedAt: STAMP,
    openingFloat: HNL("500.00"),
    ...override,
  };
}

export function cashSessionPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("sessions page", SessionPageResponse, { items, page: paging.page ?? 0, size: paging.size ?? 25, total: paging.total ?? items.length });
  return { status: 200, body, contentType: JSON_TYPE };
}

// The payments (payments-register cut). Every amount is a string the server rounded and every allocation is the server's: the fixtures
// hold values chosen by hand and never compute one.

/** A payment as the detail and the writes answer it: a confirmed cash payment that covered one charge, by default. */
export function paymentBody(override: Partial<PaymentResponseOutput> = {}): PaymentResponseOutput {
  return checked("PaymentResponse", PaymentResponse, {
    id: crypto.randomUUID(),
    studentId: "00000000-0000-4000-8000-0000000000b1",
    studentCode: "EST-0001",
    method: "CASH",
    status: "confirmed",
    amount: HNL("1150.00"),
    receivedOn: "2026-11-20",
    registeredAt: STAMP,
    registeredBy: "00000000-0000-4000-8000-0000000000a1",
    allocated: HNL("1150.00"),
    unallocated: HNL("0.00"),
    allocations: [
      {
        chargeId: "00000000-0000-4000-8000-000000c10001",
        conceptCode: "MENS",
        conceptName: "Mensualidad",
        periodLabel: "2026-11",
        dueOn: "2026-11-05",
        amount: HNL("1150.00"),
        allocatedAt: STAMP,
      },
    ],
    ...override,
  });
}

export function paymentRecord(override: Partial<PaymentResponseOutput> = {}, status = 200): ContractResponse {
  return { status, body: paymentBody(override), contentType: JSON_TYPE };
}

/** One row of the list of payments. */
export function paymentSummary(override: Partial<PaymentSummaryResponseOutput> = {}): PaymentSummaryResponseOutput {
  return {
    id: crypto.randomUUID(),
    studentId: "00000000-0000-4000-8000-0000000000b1",
    studentCode: "EST-0001",
    method: "CASH",
    status: "confirmed",
    amount: HNL("1150.00"),
    receivedOn: "2026-11-20",
    registeredAt: STAMP,
    registeredBy: "00000000-0000-4000-8000-0000000000a1",
    ...override,
  };
}

export function paymentPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("payments page", PaymentPageResponse, { items, page: paging.page ?? 0, size: paging.size ?? 25, total: paging.total ?? items.length });
  return { status: 200, body, contentType: JSON_TYPE };
}

export function paymentPostingAccount(override: Partial<PaymentPostingAccountResponseOutput> = {}): PaymentPostingAccountResponseOutput {
  return checked("PaymentPostingAccountResponse", PaymentPostingAccountResponse, {
    id: crypto.randomUUID(),
    purpose: "CASH",
    ledgerAccountId: crypto.randomUUID(),
    version: 0,
    updatedAt: STAMP,
    ...override,
  });
}

/** The map as `GET` and `PUT /payments/posting-accounts` answer it: the rows that were ever set. */
export function paymentPostingAccountMap(items: readonly unknown[]): ContractResponse {
  return { status: 200, body: checked("payment posting account map", PaymentPostingAccountMapResponse, { items }), contentType: JSON_TYPE };
}

// The scholarships cut (scholarships-awards): the scholarships, the awards and the simulation of charges, checked against the generated contract.

/** One scholarship as the API answers it: a percentage one that covers the tuition and has no cap. */
export function scholarship(override: Partial<ScholarshipResponseOutput> = {}): ScholarshipResponseOutput {
  return checked("ScholarshipResponse", ScholarshipResponse, {
    id: crypto.randomUUID(),
    code: "BECA25",
    name: "Beca de excelencia",
    benefitType: "PERCENTAGE",
    percent: "25.00",
    concepts: [{ id: "00000000-0000-4000-8000-000000007001", code: "MENS", name: "Mensualidad" }],
    active: true,
    benefitLocked: false,
    version: 0,
    createdAt: STAMP,
    updatedAt: STAMP,
    ...override,
  });
}

export function scholarshipRecord(row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked("scholarship record", ScholarshipResponse, row), contentType: JSON_TYPE };
}

export function scholarshipPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("scholarships page", ScholarshipPageResponse, { items, page: paging.page ?? 0, size: paging.size ?? 25, total: paging.total ?? items.length });
  return { status: 200, body, contentType: JSON_TYPE };
}

/** One award as the API answers it: pending, for a student and a scholarship, within an academic year. */
export function award(override: Partial<AwardResponseOutput> = {}): AwardResponseOutput {
  return checked("AwardResponse", AwardResponse, {
    id: crypto.randomUUID(),
    studentId: "00000000-0000-4000-8000-0000000000b1",
    studentCode: "EST-0001",
    scholarshipId: "00000000-0000-4000-8000-0000000e0001",
    scholarshipCode: "BECA25",
    scholarshipName: "Beca de excelencia",
    academicYearId: "00000000-0000-4000-8000-000000000a01",
    validFrom: "2027-01-01",
    validTo: "2027-12-31",
    status: "pending",
    requestedAt: STAMP,
    requestedBy: "00000000-0000-4000-8000-0000000000a1",
    ...override,
  });
}

export function awardRecord(row: unknown, status: 200 | 201 = 200): ContractResponse {
  return { status, body: checked("award record", AwardResponse, row), contentType: JSON_TYPE };
}

export function awardPage(
  items: readonly unknown[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ContractResponse {
  const body = checked("awards page", AwardPageResponse, { items, page: paging.page ?? 0, size: paging.size ?? 25, total: paging.total ?? items.length });
  return { status: 200, body, contentType: JSON_TYPE };
}

/**
 * What the simulator answers for the acceptance example of the cut (docs/cortes/scholarships-awards.md): a tuition base of L 1,100.00, a scholarship of
 * 25 % that takes L 275.00 off, a net base of L 825.00 and a tax of L 123.75 (the total L 948.75). The server computed every one of them.
 */
export function chargeSimulation(override: Partial<ChargeSimulationResponseOutput> = {}): ChargeSimulationResponseOutput {
  return checked("ChargeSimulationResponse", ChargeSimulationResponse, {
    planId: "00000000-0000-4000-8000-000000000c01",
    studentId: "00000000-0000-4000-8000-0000000000b1",
    periodStart: "2027-02-01",
    periodLabel: "2027-02",
    lines: [
      {
        paymentConceptId: "00000000-0000-4000-8000-000000007001",
        outcome: "CREATED",
        base: MONEY("1100.00"),
        scholarshipAwardId: "00000000-0000-4000-8000-0000000f0001",
        scholarshipCode: "BECA25",
        benefitAmount: MONEY("275.00"),
        netBase: MONEY("825.00"),
        tax: MONEY("123.75"),
        total: MONEY("948.75"),
      },
    ],
    ...override,
  });
}

export function chargeSimulationRecord(row: unknown): ContractResponse {
  return { status: 200, body: checked("charge simulation", ChargeSimulationResponse, row), contentType: JSON_TYPE };
}

import { randomBytes } from "node:crypto";
import {
  AcademicYearPageResponse,
  AcademicYearResponse,
  AuthenticatedSessionResponse,
  CurrentSessionResponse,
  EnrollmentPageResponse,
  EnrollmentResponse,
  GradePageResponse,
  GradeResponse,
  ModalityPageResponse,
  ModalityResponse,
  ProblemDetail,
  SecondFactorPendingResponse,
  SectionPageResponse,
  SectionResponse,
  StudentEnrollmentResponse,
  StudentPageResponse,
  StudentResponse,
  type AcademicYearResponseOutput,
  type AuthenticatedSessionResponseOutput,
  type CurrentSessionResponseOutput,
  type EnrollmentResponseOutput,
  type EnrollmentTransferResponseOutput,
  type GradeResponseOutput,
  type ModalityResponseOutput,
  type SecondFactorPendingResponseOutput,
  type SectionResponseOutput,
  type StudentEnrollmentResponseOutput,
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

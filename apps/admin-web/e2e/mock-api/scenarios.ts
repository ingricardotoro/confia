import { PERMISSION_CODES } from "../../src/shared/permissions/permission-codes.ts";
import { problem, type ProblemOptions } from "../../src/test/contract-responses.ts";
import {
  DEFAULT_ORGANIZATION,
  createOrganizationModel,
  type OrganizationModel,
  type OrganizationOptions,
} from "./organization-model.ts";
import { DEFAULT_CATALOG, createCatalogModel, type CatalogModel, type CatalogOptions } from "./catalog-model.ts";
import { DEFAULT_CHARGES, createChargesModel, type ChargesModel, type ChargesOptions } from "./charges-model.ts";
import { DEFAULT_CASHBOX, createCashboxModel, type CashboxModel, type CashboxOptions } from "./cashbox-model.ts";
import { DEFAULT_DISCOUNTS, createDiscountsModel, type DiscountsModel, type DiscountsOptions } from "./discounts-model.ts";
import { DEFAULT_SCHOLARSHIPS, createScholarshipsModel, type ScholarshipsModel, type ScholarshipsOptions } from "./scholarships-model.ts";
import { DEFAULT_PAYMENTS, createPaymentsModel, type PaymentsModel, type PaymentsOptions } from "./payments-model.ts";
import { DEFAULT_CHARGE_VOID, createChargeVoidModel, type ChargeVoidModel, type ChargeVoidOptions } from "./charge-void-model.ts";
import { DEFAULT_STATEMENT, createStatementModel, type StatementModel, type StatementOptions } from "./statement-model.ts";
import { DEFAULT_LEDGER, createLedgerModel, type LedgerModel, type LedgerOptions } from "./ledger-model.ts";
import { DEFAULT_IMPORTS, createImportsModel, type ImportsModel, type ImportsOptions } from "./imports-model.ts";
import { DEFAULT_GUARDIANS, createGuardiansModel, type GuardiansModel, type GuardiansOptions } from "./guardians-model.ts";
import { DEFAULT_ENROLLMENTS, createEnrollmentsModel, type EnrollmentsModel, type EnrollmentsOptions } from "./enrollments-model.ts";
import { DEFAULT_STUDENTS, createStudentsModel, type StudentsModel, type StudentsOptions } from "./students-model.ts";
import { createSessionModel, type ModelResponse, type SessionModel } from "./session-model.ts";

/**
 * The scenarios of the simulated API (design.md D15). A scenario is a name the test sends in the
 * `x-confia-mock-scenario` header; each one has its own session model, so journeys that run in parallel
 * never see each other. A preset says how the sign in answers and, for one case, how a response is
 * damaged on purpose. They simulate the shapes the backend publishes and prove nothing about the real stack.
 */
export const DEFAULT_SCENARIO = "default";
export const SLOW_RESPONSE_MS = 31_000;
const RETRY_AFTER_SECONDS = "30";
/** A fictitious trace id, the shape the backend gives: the screen shows it and the person copies it (SS50, AW54). */
const TRACE_ID = "01J8Z4K2QF9P0X";
const EXPIRY_PUSH_MS = 301_000;

export type Operation = "log-in" | "refresh" | "sign-out" | "current" | "enroll" | "verify" | "organization" | "students" | "enrollments" | "guardians" | "imports" | "catalog" | "charges" | "ledger" | "cashbox" | "payments" | "scholarships";

type LoginBehavior =
  | "authenticated"
  | "second-factor-required"
  | "second-factor-enrollment-required"
  | "authentication-failed"
  | "too-many-requests"
  | "capacity-exceeded"
  | "validation-failed"
  | "internal-error"
  | "slow"
  | "restricted-expired"
  | "enrollment-expired";

interface PresetDefinition {
  readonly login: LoginBehavior;
  readonly enrolled: boolean;
  /** The permissions the current session lists (a restricted role); every code the panel knows when absent. */
  readonly permissions?: readonly string[];
  /** The academic structure the scenario holds; the seeded one, with every write allowed, when absent. */
  readonly organization?: OrganizationOptions;
  /** The students registry the scenario holds; the seeded one, with every write allowed, when absent. */
  readonly students?: StudentsOptions;
  /** The enrollments the scenario holds, over its students and its academic structure; the seeded ones, with every write allowed, when absent. */
  readonly enrollments?: EnrollmentsOptions;
  /** The guardians the scenario holds, over its students; the seeded ones, with every write allowed, when absent. */
  readonly guardians?: GuardiansOptions;
  /** The student imports the scenario holds; the seeded history, with every write allowed, when absent. */
  readonly imports?: ImportsOptions;
  /** The catalog the scenario holds; the seeded one, with every write allowed, when absent. */
  readonly catalog?: CatalogOptions;
  /** The charge plans the scenario holds, over its academic structure and its catalog; the seeded ones, with every write allowed, when absent. */
  readonly charges?: ChargesOptions;
  /** The chart of accounts and the map of posting accounts the scenario holds; the seeded ones (a partial map), with every write allowed, when absent. */
  readonly ledger?: LedgerOptions;
  /** The statements of account the scenario holds, over its students; the seeded ones, readable, when absent. */
  readonly statement?: StatementOptions;
  /** The charges of a student, their void and the integrity check the scenario holds, over its students; the seeded ones, with every write allowed, when absent. */
  readonly chargeVoid?: ChargeVoidOptions;
  /** The cashbox sessions the scenario holds; the seeded ones, with every write allowed, when absent. */
  readonly cashbox?: CashboxOptions;
  /** The payments the scenario holds; the seeded ones, with every write allowed and the cash and bank accounts set, when absent. */
  readonly payments?: PaymentsOptions;
  /** The scholarships and their awards the scenario holds; the seeded ones when absent. */
  readonly scholarships?: ScholarshipsOptions;
  /** The discounts and their agreement assignments the scenario holds; the seeded ones when absent. */
  readonly discounts?: DiscountsOptions;
  /** A fault injected after the model answered, to prove the server refuses to send it. */
  readonly tamper?: (operation: Operation, response: ModelResponse) => ModelResponse;
}

/** What the auditor lists: every read, no write (the only absolutely read-only role). */
const AUDITOR_PERMISSIONS: readonly string[] = PERMISSION_CODES.filter((code) => code.endsWith(":read"));

/** What the cashier lists, narrowed to the cashbox and the reads its screens need: it works its own cashbox and approves nothing. */
const CASHIER_PERMISSIONS: readonly string[] = ["cashbox:create", "cashbox:read", "charges:read", "students:read"];

/** What the administrator lists: everything the panel knows except working a cashbox of its own (no `cashbox:create`). */
const ADMINISTRATOR_PERMISSIONS: readonly string[] = PERMISSION_CODES.filter((code) => code !== "cashbox:create");

const PRESETS: Readonly<Record<string, PresetDefinition>> = {
  default: { login: "authenticated", enrolled: false },
  // The roles of docs/03 section 5.2, narrowed to the codes the panel knows: the auditor only reads; the cashier works its own cashbox; the
  // administrator approves closes but has no cashbox of its own; the last writes everything but voiding a charge.
  "role-cashier": { login: "authenticated", enrolled: false, permissions: CASHIER_PERMISSIONS },
  "role-administrator": { login: "authenticated", enrolled: false, permissions: ADMINISTRATOR_PERMISSIONS },
  "role-auditor": { login: "authenticated", enrolled: false, permissions: AUDITOR_PERMISSIONS },
  "role-without-void": { login: "authenticated", enrolled: false, permissions: PERMISSION_CODES.filter((code) => code !== "charges:void") },
  "second-factor-required": { login: "second-factor-required", enrolled: true },
  "enrollment-required": { login: "second-factor-enrollment-required", enrolled: false },
  "authentication-failed": { login: "authentication-failed", enrolled: false },
  "too-many-requests": { login: "too-many-requests", enrolled: false },
  "capacity-exceeded": { login: "capacity-exceeded", enrolled: false },
  "validation-failed": { login: "validation-failed", enrolled: false },
  "internal-error": { login: "internal-error", enrolled: false },
  slow: { login: "slow", enrolled: false },
  "restricted-expired": { login: "restricted-expired", enrolled: true },
  "enrollment-expired": { login: "enrollment-expired", enrolled: false },
  "already-enrolled": { login: "second-factor-enrollment-required", enrolled: true },
  // The academic structure (organization-academic-structure cut). Each one signs in as the default does.
  "organization-empty": { login: "authenticated", enrolled: false, organization: { ...DEFAULT_ORGANIZATION, seed: "empty" } },
  "organization-forbidden": { login: "authenticated", enrolled: false, organization: { ...DEFAULT_ORGANIZATION, writes: "forbidden" } },
  "organization-server-error": { login: "authenticated", enrolled: false, organization: { ...DEFAULT_ORGANIZATION, reads: "failing-first" } },
  // The students registry (students-registry cut). Each one signs in as the default does.
  "students-empty": { login: "authenticated", enrolled: false, students: { ...DEFAULT_STUDENTS, seed: "empty" } },
  "students-forbidden": { login: "authenticated", enrolled: false, students: { ...DEFAULT_STUDENTS, writes: "forbidden" } },
  "students-server-error": { login: "authenticated", enrolled: false, students: { ...DEFAULT_STUDENTS, reads: "failing-first" } },
  // The enrollments (enrollments cut). Each one signs in as the default does.
  "enrollments-empty": { login: "authenticated", enrolled: false, enrollments: { ...DEFAULT_ENROLLMENTS, seed: "empty" } },
  "enrollments-forbidden": { login: "authenticated", enrolled: false, enrollments: { ...DEFAULT_ENROLLMENTS, writes: "forbidden" } },
  "enrollments-server-error": { login: "authenticated", enrolled: false, enrollments: { ...DEFAULT_ENROLLMENTS, reads: "failing-first" } },
  // The guardians (guardians cut). Each one signs in as the default does.
  "guardians-empty": { login: "authenticated", enrolled: false, guardians: { ...DEFAULT_GUARDIANS, seed: "empty" } },
  "guardians-forbidden": { login: "authenticated", enrolled: false, guardians: { ...DEFAULT_GUARDIANS, writes: "forbidden" } },
  "guardians-server-error": { login: "authenticated", enrolled: false, guardians: { ...DEFAULT_GUARDIANS, reads: "failing-first" } },
  // The student import (imports-students cut). Each one signs in as the default does.
  "imports-empty": { login: "authenticated", enrolled: false, imports: { ...DEFAULT_IMPORTS, seed: "empty" } },
  "imports-forbidden": { login: "authenticated", enrolled: false, imports: { ...DEFAULT_IMPORTS, writes: "forbidden" } },
  "imports-server-error": { login: "authenticated", enrolled: false, imports: { ...DEFAULT_IMPORTS, reads: "failing-first" } },
  // The catalog (catalog-payment-concepts, catalog-price-lists and catalog-tax-rates cuts). Each one signs in as the default does.
  "catalog-empty": { login: "authenticated", enrolled: false, catalog: { ...DEFAULT_CATALOG, seed: "empty", lists: "empty" } },
  "catalog-tax-rates-empty": { login: "authenticated", enrolled: false, catalog: { ...DEFAULT_CATALOG, taxRates: "empty" } },
  "catalog-price-lists-empty": { login: "authenticated", enrolled: false, catalog: { ...DEFAULT_CATALOG, lists: "empty" } },
  "catalog-stale-version": { login: "authenticated", enrolled: false, catalog: { ...DEFAULT_CATALOG, writes: "stale-first" } },
  "catalog-forbidden": { login: "authenticated", enrolled: false, catalog: { ...DEFAULT_CATALOG, writes: "forbidden" } },
  "catalog-server-error": { login: "authenticated", enrolled: false, catalog: { ...DEFAULT_CATALOG, reads: "failing-first" } },
  // The charge plans (charges-plans cut). Each one signs in as the default does.
  "charges-empty": { login: "authenticated", enrolled: false, charges: { ...DEFAULT_CHARGES, seed: "empty" } },
  "charges-stale-version": { login: "authenticated", enrolled: false, charges: { ...DEFAULT_CHARGES, writes: "stale-first" } },
  "charges-forbidden": { login: "authenticated", enrolled: false, charges: { ...DEFAULT_CHARGES, writes: "forbidden" } },
  "charges-server-error": { login: "authenticated", enrolled: false, charges: { ...DEFAULT_CHARGES, reads: "failing-first" } },
  // The accrual engine (charges-accrual cut): no runs, a preview that is blocked, a run rejected by a changed digest, a run already in
  // progress, a person who cannot generate, and a preview that fails once. Each one signs in as the default does.
  "charges-accrual-empty": { login: "authenticated", enrolled: false, charges: { ...DEFAULT_CHARGES, accrual: "empty" } },
  "charges-accrual-blocked": { login: "authenticated", enrolled: false, charges: { ...DEFAULT_CHARGES, accrual: "blocked" } },
  "charges-accrual-digest-changed": { login: "authenticated", enrolled: false, charges: { ...DEFAULT_CHARGES, accrual: "digest-changed" } },
  "charges-accrual-in-progress": { login: "authenticated", enrolled: false, charges: { ...DEFAULT_CHARGES, accrual: "in-progress" } },
  "charges-accrual-forbidden": { login: "authenticated", enrolled: false, charges: { ...DEFAULT_CHARGES, accrual: "forbidden" } },
  "charges-accrual-preview-error": { login: "authenticated", enrolled: false, charges: { ...DEFAULT_CHARGES, accrual: "preview-fails-first" } },
  // The chart of accounts and the map of posting accounts (ledger-chart-of-accounts cut): the map empty, complete (an account the map
  // uses cannot be deactivated), no accounts at all, a stale version, a person who cannot write, and reads that fail first. Each one signs in as the default does.
  "ledger-map-empty": { login: "authenticated", enrolled: false, ledger: { ...DEFAULT_LEDGER, map: "empty" } },
  "ledger-map-complete": { login: "authenticated", enrolled: false, ledger: { ...DEFAULT_LEDGER, map: "complete" } },
  "ledger-empty": { login: "authenticated", enrolled: false, ledger: { ...DEFAULT_LEDGER, seed: "empty", map: "empty" } },
  "ledger-stale-version": { login: "authenticated", enrolled: false, ledger: { ...DEFAULT_LEDGER, writes: "stale-first" } },
  "ledger-forbidden": { login: "authenticated", enrolled: false, ledger: { ...DEFAULT_LEDGER, writes: "forbidden" } },
  "ledger-server-error": { login: "authenticated", enrolled: false, ledger: { ...DEFAULT_LEDGER, reads: "failing-first" } },
  "ledger-map-server-error": { login: "authenticated", enrolled: false, ledger: { ...DEFAULT_LEDGER, mapReads: "failing-first" } },
  // The statement of account (ledger-account-statement cut): no student has an account, a person without the permission, and reads
  // that fail first. Each one signs in as the default does.
  // The charges of a student, their void and the integrity check (charges-void-and-integrity cut): no charges and no check yet, a charge
  // someone else voided first, a ledger that refuses the reversal, a person who cannot void or cannot read, reads that fail first, and a latest
  // run with discrepancies. Each one signs in as the default does.
  "charge-void-empty": { login: "authenticated", enrolled: false, chargeVoid: { ...DEFAULT_CHARGE_VOID, seed: "empty", integrity: "none" } },
  "charge-void-already-voided": { login: "authenticated", enrolled: false, chargeVoid: { ...DEFAULT_CHARGE_VOID, writes: "already-voided" } },
  "charge-void-not-voidable": { login: "authenticated", enrolled: false, chargeVoid: { ...DEFAULT_CHARGE_VOID, writes: "not-voidable" } },
  "charge-void-forbidden": { login: "authenticated", enrolled: false, chargeVoid: { ...DEFAULT_CHARGE_VOID, writes: "forbidden" } },
  "charge-void-read-forbidden": { login: "authenticated", enrolled: false, chargeVoid: { ...DEFAULT_CHARGE_VOID, access: "forbidden" } },
  "charge-void-server-error": { login: "authenticated", enrolled: false, chargeVoid: { ...DEFAULT_CHARGE_VOID, reads: "failing-first" } },
  "integrity-discrepancies": { login: "authenticated", enrolled: false, chargeVoid: { ...DEFAULT_CHARGE_VOID, integrity: "discrepancies" } },
  // The cashbox (cashbox-sessions cut): no session at all, the person with a session already open, a person without the permission, and reads that
  // fail first. Each one signs in as the default does.
  "cashbox-empty": { login: "authenticated", enrolled: false, cashbox: { ...DEFAULT_CASHBOX, seed: "none" } },
  "cashbox-open": { login: "authenticated", enrolled: false, cashbox: { ...DEFAULT_CASHBOX, seed: "mine-open" } },
  "cashbox-forbidden": { login: "authenticated", enrolled: false, cashbox: { ...DEFAULT_CASHBOX, access: "forbidden" } },
  "cashbox-server-error": { login: "authenticated", enrolled: false, cashbox: { ...DEFAULT_CASHBOX, reads: "failing-first" } },
  // The payments (payments-register cut): none at all, reads that fail first, a person without the permission, and the cash and bank accounts not set.
  "payments-empty": { login: "authenticated", enrolled: false, payments: { ...DEFAULT_PAYMENTS, seed: "none" } },
  "payments-forbidden": { login: "authenticated", enrolled: false, payments: { ...DEFAULT_PAYMENTS, access: "forbidden" } },
  "payments-server-error": { login: "authenticated", enrolled: false, payments: { ...DEFAULT_PAYMENTS, reads: "failing-first" } },
  // The scholarships (scholarships-awards cut): none at all, and reads that fail first.
  "scholarships-empty": { login: "authenticated", enrolled: false, scholarships: { ...DEFAULT_SCHOLARSHIPS, seed: "none" } },
  "scholarships-server-error": { login: "authenticated", enrolled: false, scholarships: { ...DEFAULT_SCHOLARSHIPS, reads: "failing-first" } },
  // The discounts (scholarships-discounts cut): none at all, and reads that fail first.
  "discounts-empty": { login: "authenticated", enrolled: false, discounts: { ...DEFAULT_DISCOUNTS, seed: "none" } },
  "discounts-server-error": { login: "authenticated", enrolled: false, discounts: { ...DEFAULT_DISCOUNTS, reads: "failing-first" } },
  "payments-accounts-incomplete": { login: "authenticated", enrolled: false, payments: { ...DEFAULT_PAYMENTS, accounts: "incomplete" } },
  "statement-empty": { login: "authenticated", enrolled: false, statement: { ...DEFAULT_STATEMENT, seed: "empty" } },
  "statement-forbidden": { login: "authenticated", enrolled: false, statement: { ...DEFAULT_STATEMENT, access: "forbidden" } },
  "statement-server-error": { login: "authenticated", enrolled: false, statement: { ...DEFAULT_STATEMENT, reads: "failing-first" } },
  "contract-violation": {
    login: "second-factor-enrollment-required",
    enrolled: false,
    tamper: (operation, response) =>
      operation === "enroll" ? { ...response, body: { ...(response.body as object), secret: "too-short" } } : response,
  },
};

export interface RecordedCall {
  readonly method: string;
  readonly path: string;
}

export interface Scenario {
  readonly preset: string;
  readonly model: SessionModel;
  readonly organization: OrganizationModel;
  readonly students: StudentsModel;
  readonly enrollments: EnrollmentsModel;
  readonly guardians: GuardiansModel;
  readonly imports: ImportsModel;
  readonly catalog: CatalogModel;
  readonly charges: ChargesModel;
  readonly ledger: LedgerModel;
  readonly statement: StatementModel;
  readonly chargeVoid: ChargeVoidModel;
  readonly cashbox: CashboxModel;
  readonly payments: PaymentsModel;
  readonly scholarships: ScholarshipsModel;
  readonly discounts: DiscountsModel;
  readonly calls: RecordedCall[];
  readonly tamper: PresetDefinition["tamper"];
  signIn(sleep: (ms: number) => Promise<void>): Promise<ModelResponse>;
}

const failure = (
  code: Parameters<typeof problem>[0],
  status: number,
  headers: Record<string, string> = {},
  options: ProblemOptions = {},
): ModelResponse => ({
  ...problem(code, status, options),
  headers,
  setCookies: [],
});

function build(preset: string, definition: PresetDefinition): Scenario {
  let offsetMs = 0;
  const model = createSessionModel({
    now: () => Date.now() + offsetMs,
    enrolled: definition.enrolled,
    ...(definition.permissions === undefined ? {} : { permissions: definition.permissions }),
  });
  const organization = createOrganizationModel(definition.organization ?? DEFAULT_ORGANIZATION);
  const students = createStudentsModel(definition.students ?? DEFAULT_STUDENTS);
  const catalog = createCatalogModel(definition.catalog ?? DEFAULT_CATALOG);
  const scholarships = createScholarshipsModel(definition.scholarships ?? DEFAULT_SCHOLARSHIPS, {
    students: () => students.rows().students,
    concepts: () => catalog.rows(),
    now: () => new Date(),
  });
  const discounts = createDiscountsModel(definition.discounts ?? DEFAULT_DISCOUNTS, {
    students: () => students.rows().students,
    concepts: () => catalog.rows(),
    now: () => new Date(),
  });
  const statement = createStatementModel(definition.statement ?? DEFAULT_STATEMENT, { students });
  const chargeVoid = createChargeVoidModel(definition.chargeVoid ?? DEFAULT_CHARGE_VOID, { students });
  const cashbox = createCashboxModel(definition.cashbox ?? DEFAULT_CASHBOX);
  return {
    preset,
    model,
    organization,
    students,
    enrollments: createEnrollmentsModel(definition.enrollments ?? DEFAULT_ENROLLMENTS, { organization, students }),
    guardians: createGuardiansModel(definition.guardians ?? DEFAULT_GUARDIANS, { students }),
    imports: createImportsModel(definition.imports ?? DEFAULT_IMPORTS),
    catalog,
    charges: createChargesModel(definition.charges ?? DEFAULT_CHARGES, { organization, catalog, students, benefitFor: scholarships.benefitFor }),
    ledger: createLedgerModel(definition.ledger ?? DEFAULT_LEDGER),
    statement,
    chargeVoid,
    cashbox,
    payments: createPaymentsModel(definition.payments ?? DEFAULT_PAYMENTS, { students, charges: chargeVoid, statement, cashbox }),
    scholarships,
    discounts,
    calls: [],
    tamper: definition.tamper,
    async signIn(sleep) {
      switch (definition.login) {
        case "authenticated":
          return model.openSession();
        case "second-factor-required":
          return model.startSecondFactor("mfa-verify");
        case "second-factor-enrollment-required":
          return model.startSecondFactor("mfa-enroll");
        case "authentication-failed":
          return failure("authentication-failed", 401);
        case "too-many-requests":
          return failure("too-many-requests", 429, { "Retry-After": RETRY_AFTER_SECONDS });
        case "capacity-exceeded":
          return failure("capacity-exceeded", 503);
        case "validation-failed":
          return failure("validation-failed", 400, {}, { errors: [{ field: "password", reason: "max" }] });
        case "internal-error":
          return failure("internal-error", 500, {}, { traceId: TRACE_ID });
        case "slow":
          await sleep(SLOW_RESPONSE_MS);
          return model.openSession();
        case "restricted-expired":
        case "enrollment-expired": {
          const issued = model.startSecondFactor(definition.login === "enrollment-expired" ? "mfa-enroll" : "mfa-verify");
          offsetMs += EXPIRY_PUSH_MS;
          return issued;
        }
      }
    },
  };
}

const ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/;

export function isScenarioId(value: string): boolean {
  return ID_PATTERN.test(value);
}

export function createScenarioStore() {
  const scenarios = new Map<string, Scenario>();
  const fallback = PRESETS["default"];
  if (fallback === undefined) throw new Error("the default preset is missing");
  return {
    scenario(id: string): Scenario {
      let found = scenarios.get(id);
      if (found === undefined) {
        found = build("default", fallback);
        scenarios.set(id, found);
      }
      return found;
    },
    /** Starts the scenario over with a preset; `false` when the preset does not exist. */
    apply(id: string, preset: string): boolean {
      const definition = Object.hasOwn(PRESETS, preset) ? PRESETS[preset] : undefined;
      if (definition === undefined) return false;
      scenarios.set(id, build(preset, definition));
      return true;
    },
  };
}

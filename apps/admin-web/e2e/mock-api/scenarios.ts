import { problem, type ProblemOptions } from "../../src/test/contract-responses.ts";
import {
  DEFAULT_ORGANIZATION,
  createOrganizationModel,
  type OrganizationModel,
  type OrganizationOptions,
} from "./organization-model.ts";
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

export type Operation = "log-in" | "refresh" | "sign-out" | "current" | "enroll" | "verify" | "organization" | "students";

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
  /** The academic structure the scenario holds; the seeded one, with every write allowed, when absent. */
  readonly organization?: OrganizationOptions;
  /** The students registry the scenario holds; the seeded one, with every write allowed, when absent. */
  readonly students?: StudentsOptions;
  /** A fault injected after the model answered, to prove the server refuses to send it. */
  readonly tamper?: (operation: Operation, response: ModelResponse) => ModelResponse;
}

const PRESETS: Readonly<Record<string, PresetDefinition>> = {
  default: { login: "authenticated", enrolled: false },
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
  const model = createSessionModel({ now: () => Date.now() + offsetMs, enrolled: definition.enrolled });
  return {
    preset,
    model,
    organization: createOrganizationModel(definition.organization ?? DEFAULT_ORGANIZATION),
    students: createStudentsModel(definition.students ?? DEFAULT_STUDENTS),
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

import {
  CreateChargePlanRequest,
  type ChargePlanDetailResponseOutput,
  type ChargePlanResponseOutput,
  type CreateChargePlanRequest as CreateBody,
  type UpdateChargePlanRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ChargePlanFrequency, ChargePlanScope, ChargePlanStatus } from "../../shared/api/charges-operations.ts";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";
import { CHARGE_PLAN_FREQUENCIES } from "./charges-search.ts";

export type ChargePlan = ChargePlanResponseOutput;
export type ChargePlanDetail = ChargePlanDetailResponseOutput;
export type ChargePlanField = "academicYearId" | "name" | "targetScope" | "targetRefId" | "frequency" | "dueDayOfMonth";

/** In the order the fields are on the form, which is the order of the error summary. */
export const CHARGE_PLAN_CONTROL_IDS: Readonly<Record<ChargePlanField, string>> = {
  name: "charge-plan-name",
  academicYearId: "charge-plan-year",
  targetScope: "charge-plan-scope",
  targetRefId: "charge-plan-target-ref",
  frequency: "charge-plan-frequency",
  dueDayOfMonth: "charge-plan-due-day",
};

export type ChargePlanValues = Readonly<Record<ChargePlanField, string>>;

/** The due day of the month is from 1 to 28, so it exists in every month; the server judges the same. */
export const DUE_DAY = { first: 1, last: 28 } as const;

/** A new plan starts for a grade, the usual scope, and monthly, the usual frequency; the year, the name and the due day are left to the person. */
export const initialChargePlanValues = (): ChargePlanValues => ({
  academicYearId: "",
  name: "",
  targetScope: "GRADE",
  targetRefId: "",
  frequency: "MONTHLY",
  dueDayOfMonth: "",
});

/** The reason a due day typed as text is refused, judged on its characters and then on its value as a day of the month. */
export function dueDayReason(raw: string): FieldReason | undefined {
  const text = raw.trim();
  if (text === "") return "required";
  if (!/^\d{1,2}$/.test(text)) return "due-day-range";
  const day = Number(text);
  return day >= DUE_DAY.first && day <= DUE_DAY.last ? undefined : "due-day-range";
}

/** The day as the request takes it: a whole number when the text is one, else the text, so the schema refuses it. */
const dayOf = (raw: string): number | string => (/^\d{1,2}$/.test(raw.trim()) ? Number(raw.trim()) : raw);

/** The request of a new plan. The scope, the frequency and the reference stay as chosen: the schema judges them. */
export function createBodyOf(values: ChargePlanValues): CreateBody {
  return {
    academicYearId: values.academicYearId,
    name: values.name,
    targetScope: values.targetScope as ChargePlanScope,
    targetRefId: values.targetRefId,
    frequency: values.frequency as ChargePlanFrequency,
    // A day that is not a whole number is refused by `validateChargePlan` before anything is sent: the cast only lets the schema judge it.
    dueDayOfMonth: dayOf(values.dueDayOfMonth) as number,
  };
}

/**
 * The reasons the new plan is refused before sending: the generated request for the references, the name and the
 * frequency; the due day judged here (1 to 28); and a year, a name, a scope, a reference and a frequency that are blank.
 */
export function validateChargePlan(values: ChargePlanValues): Partial<Record<ChargePlanField, FieldReason>> {
  const reasons: Partial<Record<ChargePlanField, FieldReason>> = {
    ...reasonsFromSchema(CreateChargePlanRequest, createBodyOf(values), ["academicYearId", "name", "targetScope", "targetRefId", "frequency"]),
    ...requiredReasons(values, ["academicYearId", "name", "targetScope", "targetRefId", "frequency"]),
  };
  const day = dueDayReason(values.dueDayOfMonth);
  if (day !== undefined) reasons.dueDayOfMonth = day;
  return reasons;
}

/** What the edit of a plan changes: the name, the frequency and the due day. The scope, the reference and the year never change. */
export type ChargePlanEditField = "name" | "frequency" | "dueDayOfMonth";

export const CHARGE_PLAN_EDIT_CONTROL_IDS: Readonly<Record<ChargePlanEditField, string>> = {
  name: "charge-plan-edit-name",
  frequency: "charge-plan-edit-frequency",
  dueDayOfMonth: "charge-plan-edit-due-day",
};

export type ChargePlanEditValues = Readonly<Record<ChargePlanEditField, string>>;

export const editValuesOf = (plan: ChargePlan): ChargePlanEditValues => ({
  name: plan.name,
  frequency: plan.frequency,
  dueDayOfMonth: String(plan.dueDayOfMonth),
});

/** The request of an edit: the new name, frequency and day, the status the plan has, and the version it was read at. */
export const editBodyOf = (plan: ChargePlan, values: ChargePlanEditValues): UpdateBody => ({
  version: plan.version,
  name: values.name,
  frequency: values.frequency as ChargePlanFrequency,
  dueDayOfMonth: dayOf(values.dueDayOfMonth) as number,
  status: plan.status,
});

/** The reasons an edit is refused before sending. */
export function validateChargePlanEdit(values: ChargePlanEditValues): Partial<Record<ChargePlanEditField, FieldReason>> {
  const reasons: Partial<Record<ChargePlanEditField, FieldReason>> = { ...requiredReasons(values, ["name", "frequency"]) };
  if (reasons.frequency === undefined && !(CHARGE_PLAN_FREQUENCIES as readonly string[]).includes(values.frequency)) reasons.frequency = "format";
  const day = dueDayReason(values.dueDayOfMonth);
  if (day !== undefined) reasons.dueDayOfMonth = day;
  return reasons;
}

/**
 * The body that changes only the status of a plan, with the version the plan was read at: if it changed since, the
 * server answers 409 `stale-version`. The name, the frequency and the due day travel as they are.
 */
export const statusBodyOf = (plan: ChargePlan, status: ChargePlanStatus): UpdateBody => ({
  version: plan.version,
  name: plan.name,
  frequency: plan.frequency,
  dueDayOfMonth: plan.dueDayOfMonth,
  status,
});

import { describe, expect, it } from "vitest";
import { chargePlan } from "../../test/contract-responses.ts";
import {
  createBodyOf,
  dueDayReason,
  editBodyOf,
  editValuesOf,
  initialChargePlanValues,
  statusBodyOf,
  validateChargePlan,
  validateChargePlanEdit,
} from "./charge-plan-form.ts";

const YEAR = "00000000-0000-4000-8000-000000001030";
const GRADE = "00000000-0000-4000-8000-000000003001";
const COMPLETE = { academicYearId: YEAR, name: "Primaria 2027", targetScope: "GRADE", targetRefId: GRADE, frequency: "MONTHLY", dueDayOfMonth: "5" } as const;

describe("the form of a new plan", () => {
  it("starts for a grade and monthly, with the year, the name, the reference and the due day to be filled", () => {
    expect(initialChargePlanValues()).toEqual({ academicYearId: "", name: "", targetScope: "GRADE", targetRefId: "", frequency: "MONTHLY", dueDayOfMonth: "" });
  });

  it("asks for everything that is blank", () => {
    expect(validateChargePlan(initialChargePlanValues())).toEqual({ academicYearId: "required", name: "required", targetRefId: "required", dueDayOfMonth: "required" });
  });

  it("accepts a complete plan and sends the due day as a whole number", () => {
    expect(validateChargePlan(COMPLETE)).toEqual({});
    expect(createBodyOf(COMPLETE)).toEqual({ academicYearId: YEAR, name: "Primaria 2027", targetScope: "GRADE", targetRefId: GRADE, frequency: "MONTHLY", dueDayOfMonth: 5 });
  });

  it.each(["0", "29", "31", "-1", "5.5", "abc", "100"])("refuses the due day %s: it is a day from 1 to 28", (day) => {
    expect(dueDayReason(day)).toBe("due-day-range");
    expect(validateChargePlan({ ...COMPLETE, dueDayOfMonth: day })).toEqual({ dueDayOfMonth: "due-day-range" });
  });

  it.each(["1", "5", "28", " 15 "])("accepts the due day %s", (day) => {
    expect(dueDayReason(day)).toBeUndefined();
  });

  it("refuses a name that is too long, a reference that is not an id and a frequency it does not know", () => {
    expect(validateChargePlan({ ...COMPLETE, name: "x".repeat(121) })).toEqual({ name: "too-long" });
    expect(validateChargePlan({ ...COMPLETE, targetRefId: "primero" })).toEqual({ targetRefId: "format" });
    expect(validateChargePlan({ ...COMPLETE, frequency: "WEEKLY" })).toEqual({ frequency: "format" });
  });
});

describe("the edit and the status of a plan", () => {
  const plan = chargePlan({ id: "00000000-0000-4000-8000-000000000c01", name: "Primaria 2027", frequency: "BIMONTHLY", dueDayOfMonth: 10, status: "DRAFT", version: 4 });

  it("starts from the plan as it was read", () => {
    expect(editValuesOf(plan)).toEqual({ name: "Primaria 2027", frequency: "BIMONTHLY", dueDayOfMonth: "10" });
  });

  it("sends the new data with the status the plan has and the version it was read at", () => {
    expect(editBodyOf(plan, { name: "Primaria", frequency: "ANNUAL", dueDayOfMonth: "12" })).toEqual({
      version: 4,
      name: "Primaria",
      frequency: "ANNUAL",
      dueDayOfMonth: 12,
      status: "DRAFT",
    });
  });

  it("refuses a blank name, a frequency it does not know and a due day out of range", () => {
    expect(validateChargePlanEdit({ name: " ", frequency: "WEEKLY", dueDayOfMonth: "29" })).toEqual({ name: "required", frequency: "format", dueDayOfMonth: "due-day-range" });
    expect(validateChargePlanEdit(editValuesOf(plan))).toEqual({});
  });

  it("changes only the status, keeping the name, the frequency, the day and the version", () => {
    expect(statusBodyOf(plan, "ACTIVE")).toEqual({ version: 4, name: "Primaria 2027", frequency: "BIMONTHLY", dueDayOfMonth: 10, status: "ACTIVE" });
  });
});

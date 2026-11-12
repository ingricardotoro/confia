import { describe, expect, it } from "vitest";
import {
  EXIT_KINDS,
  enrollBodyOf,
  exitBodyOf,
  initialEnrollValues,
  initialExitValues,
  initialTransferValues,
  transferBodyOf,
  validateEnroll,
  validateExit,
  validateTransfer,
} from "./enrollment-forms.ts";
import { groupSections, todayIso } from "./enrollment-model.ts";
import { enrollmentsSearch } from "./enrollments-search.ts";

const UUID = "11111111-1111-4111-8111-111111111111";

describe("todayIso", () => {
  it("is the date in Honduras, not in UTC", () => {
    // 03:00 UTC is still the evening of the day before in Honduras (UTC-6).
    expect(todayIso(new Date("2026-03-10T03:00:00Z"))).toBe("2026-03-09");
    expect(todayIso(new Date("2026-03-10T07:00:00Z"))).toBe("2026-03-10");
  });
});

describe("groupSections", () => {
  const labels = new Map([
    ["g1", "Secundaria · Séptimo"],
    ["g2", "Primaria · Primero"],
  ]);
  const sections = [
    { id: "s1", name: "A", gradeId: "g1", isActive: true },
    { id: "s2", name: "B", gradeId: "g2", isActive: true },
    { id: "s3", name: "C", gradeId: "g2", isActive: false },
    { id: "s4", name: "D", gradeId: "g2", isActive: true },
    { id: "s5", name: "E", gradeId: "missing", isActive: true },
  ];

  it("groups the active sections by 'Modalidad · Grado', the groups in order and an unknown grade last", () => {
    expect(groupSections(sections, labels, "No disponible")).toEqual([
      { label: "Primaria · Primero", options: [{ value: "s2", label: "B" }, { value: "s4", label: "D" }] },
      { label: "Secundaria · Séptimo", options: [{ value: "s1", label: "A" }] },
      { label: "No disponible", options: [{ value: "s5", label: "E" }] },
    ]);
  });

  it("leaves out the section it is told to", () => {
    expect(groupSections(sections, labels, "No disponible", "s2").flatMap((group) => group.options.map((option) => option.value))).toEqual(["s4", "s1", "s5"]);
  });
});

describe("the search of the enrollments", () => {
  it("keeps what holds and drops what does not", () => {
    expect(enrollmentsSearch.parse({ academicYearId: UUID, status: "GRADUATED", sort: "enrolledOn:desc", page: 2, pageSize: 50 })).toEqual({
      academicYearId: UUID,
      status: "GRADUATED",
      sort: "enrolledOn:desc",
      page: 2,
      pageSize: 50,
    });
    expect(enrollmentsSearch.parse({ academicYearId: "x", gradeId: 4, sectionId: "", status: "GONE", sort: "code:asc", page: 0, pageSize: 7 })).toEqual({
      page: 1,
      pageSize: 25,
    });
  });
});

describe("the forms", () => {
  it("send no year, and the enroll body is the three fields", () => {
    expect(enrollBodyOf({ studentId: UUID, sectionId: UUID, enrolledOn: "2026-02-02" })).toEqual({ studentId: UUID, sectionId: UUID, enrolledOn: "2026-02-02" });
    expect(validateEnroll(initialEnrollValues())).toEqual({ studentId: "required", sectionId: "required" });
    expect(validateEnroll({ studentId: UUID, sectionId: UUID, enrolledOn: "" })).toEqual({ enrolledOn: "required" });
  });

  it("leave an empty reason out of a transfer and refuse one under three characters or over five hundred", () => {
    expect(transferBodyOf({ toSectionId: UUID, transferredOn: "2026-03-02", reason: "" })).toEqual({ toSectionId: UUID, transferredOn: "2026-03-02" });
    const base = { ...initialTransferValues(), toSectionId: UUID };
    expect(validateTransfer(base)).toEqual({});
    expect(validateTransfer({ ...base, reason: "ab" })).toEqual({ reason: "reason-length" });
    expect(validateTransfer({ ...base, reason: "a".repeat(501) })).toEqual({ reason: "reason-length" });
    expect(validateTransfer({ ...base, reason: "a".repeat(500) })).toEqual({});
    expect(validateTransfer(initialTransferValues())).toEqual({ toSectionId: "required" });
  });

  it("require the reason of a withdrawal and of a transfer to another institution, but not of a graduation", () => {
    expect(EXIT_KINDS).toEqual(["WITHDRAWN", "TRANSFERRED", "GRADUATED"]);
    expect(validateExit(initialExitValues())).toEqual({ withdrawalReason: "required" });
    expect(validateExit({ ...initialExitValues(), status: "TRANSFERRED" })).toEqual({ withdrawalReason: "required" });
    expect(validateExit({ ...initialExitValues(), status: "GRADUATED" })).toEqual({});
    expect(validateExit({ ...initialExitValues(), status: "GRADUATED", withdrawalReason: "ab" })).toEqual({ withdrawalReason: "reason-length" });
    expect(exitBodyOf({ status: "GRADUATED", withdrawnOn: "2026-11-01", withdrawalReason: "" })).toEqual({ status: "GRADUATED", withdrawnOn: "2026-11-01" });
  });
});

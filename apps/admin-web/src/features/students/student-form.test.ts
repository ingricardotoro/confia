import { describe, expect, it } from "vitest";
import { student } from "../../test/contract-responses.ts";
import { initialStudentValues, nameOf, saveOf, toggleBodyOf, validateStudent, type StudentValues } from "./student-form.ts";

// The form of a student as data: the body each act sends, with the exact treatment of the document, and the reasons
// the form is refused before sending.
const FILLED: StudentValues = {
  ...initialStudentValues(),
  firstName: "Ana",
  lastName: "Pérez",
  birthDate: "2014-03-09",
  sex: "F",
};
const ROW = student({ hasNationalId: true });
const EDIT: StudentValues = initialStudentValues(ROW);

describe("the body of a create", () => {
  it("leaves out the code and the document when they are empty, so the server generates the code", () => {
    expect(saveOf(undefined, FILLED)).toEqual({
      mode: "create",
      body: { firstName: "Ana", lastName: "Pérez", birthDate: "2014-03-09", sex: "F" },
    });
  });

  it("sends the code and the document when they are typed", () => {
    expect(saveOf(undefined, { ...FILLED, studentCode: "E-9", nationalId: "08012014" })).toEqual({
      mode: "create",
      body: { firstName: "Ana", lastName: "Pérez", birthDate: "2014-03-09", sex: "F", studentCode: "E-9", nationalId: "08012014" },
    });
  });
});

describe("the body of an edit", () => {
  it("does not send nationalId when the replacement box is empty, so the stored document is kept", () => {
    const save = saveOf(ROW, EDIT);

    expect(save).toEqual({
      mode: "update",
      id: ROW.id,
      body: { firstName: "Ana", lastName: "Pérez", birthDate: "2014-03-09", sex: "F", status: "ACTIVE" },
    });
    expect(save.body).not.toHaveProperty("nationalId");
    expect(save.body).not.toHaveProperty("studentCode");
  });

  it("sends the replacement as the whole new value", () => {
    expect(saveOf(ROW, { ...EDIT, nationalId: "1503199998765" }).body).toMatchObject({ nationalId: "1503199998765" });
  });

  it("sends an empty nationalId only when the removal is checked, and the removal wins over a typed value", () => {
    expect(saveOf(ROW, { ...EDIT, removeNationalId: "true" }).body).toMatchObject({ nationalId: "" });
    expect(saveOf(ROW, { ...EDIT, removeNationalId: "true", nationalId: "1503199998765" }).body).toMatchObject({ nationalId: "" });
    expect(saveOf(ROW, { ...EDIT, removeNationalId: "false", nationalId: "" }).body).not.toHaveProperty("nationalId");
  });

  it("starts with an empty replacement box, never with the stored document", () => {
    expect(EDIT.nationalId).toBe("");
    expect(JSON.stringify(EDIT)).not.toContain("2345");
  });
});

describe("toggleBodyOf", () => {
  it("is the current data with the other status and no document", () => {
    const body = toggleBodyOf(ROW, "INACTIVE");

    expect(body).toEqual({ firstName: "Ana", lastName: "Pérez", birthDate: "2014-03-09", sex: "F", status: "INACTIVE" });
    expect(body).not.toHaveProperty("nationalId");
  });
});

describe("validateStudent", () => {
  const create = validateStudent(undefined);
  const edit = validateStudent(ROW);

  it("accepts a complete form, with or without a document", () => {
    expect(create(FILLED)).toEqual({});
    expect(create({ ...FILLED, nationalId: "0801-2014-12345" })).toEqual({});
    expect(edit(EDIT)).toEqual({});
  });

  it("asks for the names, the birth date and the sex", () => {
    expect(create(initialStudentValues())).toEqual({ firstName: "required", lastName: "required", birthDate: "required", sex: "required" });
  });

  it("refuses a name over 100 characters and a code over 30", () => {
    expect(create({ ...FILLED, firstName: "a".repeat(101) })).toEqual({ firstName: "too-long" });
    expect(create({ ...FILLED, firstName: "a".repeat(100) })).toEqual({});
    expect(create({ ...FILLED, studentCode: "c".repeat(31) })).toEqual({ studentCode: "too-long" });
  });

  it.each([["1234567"], ["123 4567890"], ["12345678901234567890X"], ["1234.5678"], ["ñandú-1234"]])(
    "refuses the document %j with the format reason",
    (nationalId) => {
      expect(create({ ...FILLED, nationalId })).toEqual({ nationalId: "national-id-format" });
    },
  );

  it.each([["12345678"], ["12345678901234567890"], ["AB-12-34-56"]])("accepts the document %j", (nationalId) => {
    expect(create({ ...FILLED, nationalId })).toEqual({});
  });
});

describe("nameOf", () => {
  it("is the first name and the last name", () => {
    expect(nameOf({ firstName: "Ana", lastName: "Pérez" })).toBe("Ana Pérez");
  });
});

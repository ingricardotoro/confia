import { describe, expect, it } from "vitest";
import { guardian } from "../../test/contract-responses.ts";
import { initialGuardianValues, saveOf, toggleBodyOf, validateGuardian, type GuardianValues } from "./guardian-form.ts";

const values = (override: Partial<GuardianValues> = {}): GuardianValues => ({ ...initialGuardianValues(), firstName: "Rosa", lastName: "Mejía", ...override });

describe("the body of a create", () => {
  it("holds the required members and the channel, and leaves out what is empty", () => {
    expect(saveOf(undefined, values())).toEqual({ mode: "create", body: { firstName: "Rosa", lastName: "Mejía", preferredChannel: "NONE" } });
  });

  it("adds the document, the RTN and the contact data that are typed", () => {
    const save = saveOf(undefined, values({ nationalId: "0801198012345", rtn: "08011980123456", email: "a@b.hn", phone: "+504 99990000", address: "Col. X" }));

    expect(save).toEqual({
      mode: "create",
      body: {
        firstName: "Rosa",
        lastName: "Mejía",
        preferredChannel: "NONE",
        nationalId: "0801198012345",
        rtn: "08011980123456",
        email: "a@b.hn",
        phone: "+504 99990000",
        address: "Col. X",
      },
    });
  });
});

describe("the body of an edit", () => {
  const row = guardian({ hasNationalId: true, hasRtn: true, email: "a@b.hn" });

  it("starts from the guardian, with the document and the RTN empty because the browser never has them", () => {
    expect(initialGuardianValues(row)).toMatchObject({ nationalId: "", rtn: "", removeNationalId: "false", removeRtn: "false", email: "a@b.hn", status: "ACTIVE" });
  });

  it("keeps the document and the RTN (no member) when their boxes are empty", () => {
    const { body } = saveOf(row, initialGuardianValues(row)) as { body: object };

    expect(body).not.toHaveProperty("nationalId");
    expect(body).not.toHaveProperty("rtn");
  });

  it("replaces with a typed value and removes with an empty text only when the checkbox says so", () => {
    expect(saveOf(row, { ...initialGuardianValues(row), nationalId: "1503199998765", removeRtn: "true" })).toMatchObject({
      mode: "update",
      id: row.id,
      body: { nationalId: "1503199998765", rtn: "" },
    });
  });

  it("omits the contact fields that were emptied, because an edit replaces the record and omitted clears", () => {
    const { body } = saveOf(row, { ...initialGuardianValues(row), email: "" }) as { body: object };

    expect(body).not.toHaveProperty("email");
  });

  it("builds the toggle from the row: the whole record, the other status, never the document or the RTN", () => {
    expect(toggleBodyOf(guardian({ email: "a@b.hn", phone: null, address: "", preferredChannel: "SMS", hasNationalId: true }), "INACTIVE")).toEqual({
      firstName: "Rosa",
      lastName: "Mejía",
      preferredChannel: "SMS",
      status: "INACTIVE",
      email: "a@b.hn",
    });
  });
});

describe("validateGuardian", () => {
  const validate = validateGuardian(undefined);

  it("accepts the required fields alone", () => {
    expect(validate(values())).toEqual({});
  });

  it("asks for the names", () => {
    expect(validate(values({ firstName: "", lastName: "" }))).toEqual({ firstName: "required", lastName: "required" });
  });

  it("refuses a name over 100 characters", () => {
    expect(validate(values({ lastName: "x".repeat(101) }))).toEqual({ lastName: "too-long" });
  });

  it.each([
    ["nationalId", "abc", "national-id-format"],
    ["nationalId", "0801-1980-12345", undefined],
    ["rtn", "123", "rtn-format"],
    ["rtn", "0801-1980-123456", undefined],
    ["rtn", "08011980123456", undefined],
    ["email", "sin-arroba", "email-format"],
    ["email", "a@b.hn", undefined],
    ["phone", "12", "phone-format"],
    ["phone", "+504 9999-0000", undefined],
  ] as const)("%s %j gives %s", (field, value, reason) => {
    expect(validate(values({ [field]: value }))[field]).toBe(reason);
  });
});

import { describe, expect, it } from "vitest";
import { ledgerAccount } from "../../test/contract-responses.ts";
import { canHoldStudentBalances, initialAccountValues, saveOf, updateBodyOf, validateAccount } from "./account-form.ts";

describe("the form of an account", () => {
  it("starts a new account empty, without the student flag and active", () => {
    expect(initialAccountValues()).toEqual({ code: "", name: "", type: "", holdsStudentBalances: "false", active: "true" });
  });

  it("starts an existing account with its own values", () => {
    const row = ledgerAccount({ code: "ING-01", name: "Ingresos de prueba", type: "INCOME", holdsStudentBalances: false, active: false });

    expect(initialAccountValues(row)).toEqual({ code: "ING-01", name: "Ingresos de prueba", type: "INCOME", holdsStudentBalances: "false", active: "false" });
  });

  it("creates with the code, the type and the flag, and edits with the name, the state and the version, never the code or the type", () => {
    const row = ledgerAccount({ version: 3 });
    const values = initialAccountValues(row);

    expect(saveOf(undefined, { ...values, code: "ACT-02", type: "ASSET" })).toEqual({
      mode: "create",
      body: { code: "ACT-02", name: row.name, type: "ASSET", holdsStudentBalances: true },
    });
    expect(saveOf(row, { ...values, name: "Otro nombre", active: "false" })).toEqual({
      mode: "update",
      id: row.id,
      body: { version: 3, name: "Otro nombre", active: false },
    });
  });

  it("never sends the student flag for a type that cannot carry it, whatever the field says", () => {
    const values = { ...initialAccountValues(), code: "ING-01", name: "Ingreso", type: "INCOME", holdsStudentBalances: "true" };

    expect(saveOf(undefined, values)).toEqual({ mode: "create", body: { code: "ING-01", name: "Ingreso", type: "INCOME", holdsStudentBalances: false } });
    expect(canHoldStudentBalances("ASSET")).toBe(true);
    expect(canHoldStudentBalances("LIABILITY")).toBe(true);
    expect(["EQUITY", "INCOME", "EXPENSE", ""].some(canHoldStudentBalances)).toBe(false);
  });

  it("changes only the state from a row, keeping the name and sending the version it was read at", () => {
    const row = ledgerAccount({ version: 7, name: "Caja" });

    expect(updateBodyOf(row, false)).toEqual({ version: 7, name: "Caja", active: false });
  });

  it("refuses a blank code, name and type of a new account, and a code outside the whitelist or longer than 30", () => {
    const blank = validateAccount(undefined)(initialAccountValues());
    const spaced = validateAccount(undefined)({ ...initialAccountValues(), code: "AC T", name: "Caja", type: "ASSET" });
    const long = validateAccount(undefined)({ ...initialAccountValues(), code: "A".repeat(31), name: "Caja", type: "ASSET" });

    expect(blank).toEqual({ code: "required", name: "required", type: "required" });
    expect(spaced.code).toBe("code-format");
    expect(long.code).toBe("too-long");
  });

  it("refuses a blank name and a name over 120 characters in an edit, and accepts a good one", () => {
    const row = ledgerAccount();
    const values = initialAccountValues(row);

    expect(validateAccount(row)({ ...values, name: "" }).name).toBe("required");
    expect(validateAccount(row)({ ...values, name: "N".repeat(121) }).name).toBe("too-long");
    expect(validateAccount(row)(values)).toEqual({});
  });
});

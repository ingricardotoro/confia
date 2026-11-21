import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { studentCharge, studentChargePage } from "../../test/contract-responses.ts";
import { installMswServer, route } from "../../test/msw-server.ts";
import { renderCharges } from "../../test/render-charges.tsx";

// identity-session-permissions: "Anular cargo" is drawn only with charges:void, the permission the server asks to void a
// charge. A role that reads the charges (a cashier, an auditor) sees the list and no void action. The panel only hides.
installMswServer();

const STUDENT_ID = "00000000-0000-4000-8000-000000005001";
const LIST = `/api/v1/charges/students/${STUDENT_ID}/charges`;
const TIMEOUT = { timeout: 10_000 };
const ACTIVE = studentCharge({ id: "00000000-0000-4000-8000-0000000d0001", periodStart: "2027-02-01", periodLabel: "2027-02", conceptName: "Mensualidad" });

async function openAs(permissions: readonly string[]) {
  route("get", LIST, studentChargePage([ACTIVE]));
  renderCharges(`/charges/student-charges?studentId=${STUDENT_ID}`, { permissions });
  const table = await screen.findByRole("table", { name: "Cargos del estudiante" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return table;
}

describe("the charges of a student with a restricted role", () => {
  it("draws no void action, and no column for it, for a role that can read the charges but not void them", async () => {
    const table = await openAs(["charges:read", "charges:create", "charges:update"]);

    expect(within(table).getByRole("row", { name: /2027-02/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Anular el cargo/ })).not.toBeInTheDocument();
    expect(within(table).queryByRole("columnheader", { name: "Acciones" })).not.toBeInTheDocument();
  });

  it("draws the void action for a role that holds charges:void", async () => {
    await openAs(["charges:read", "charges:void"]);

    expect(screen.getByRole("button", { name: "Anular el cargo Mensualidad, 2027-02" })).toBeInTheDocument();
  });
});

import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { cashSession } from "../../test/contract-responses.ts";
import { installMswServer, route } from "../../test/msw-server.ts";
import { renderCashbox } from "../../test/render-cashbox.tsx";
import { PERMISSION_CODES } from "../../shared/permissions/permission-codes.ts";

// The report of a session for a person who may read it but not approve it: the close that awaits approval is shown, and the approval is not
// offered. The server still decides: this only hides what the session cannot use.
installMswServer();

const ID = "00000000-0000-4000-8000-0000000c0002";
const PENDING = cashSession({
  id: ID,
  status: "pending_approval",
  expectedAmount: { amount: "550.00", currency: "HNL" },
  close: {
    id: "00000000-0000-4000-8000-0000000c0aa1",
    closedAt: "2026-11-19T18:00:00Z",
    closedBy: "00000000-0000-4000-8000-0000000000a1",
    declaredAmount: { amount: "540.00", currency: "HNL" },
    expectedAmount: { amount: "550.00", currency: "HNL" },
    differenceAmount: { amount: "-10.00", currency: "HNL" },
    differenceReason: "Billete de cien contado dos veces por error",
  },
});

describe("the report of a session without the approval permission", () => {
  it("shows the close that awaits approval and offers no approval", async () => {
    route("get", `/api/v1/cashbox/sessions/${ID}`, PENDING);
    renderCashbox(`/cashbox/sessions/${ID}`, { permissions: PERMISSION_CODES.filter((code) => code !== "cashbox:approve") });

    expect(await screen.findByText("Billete de cien contado dos veces por error", {}, { timeout: 10_000 })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Aprobar cierre" })).not.toBeInTheDocument();
  });

  it("offers the approval to a session that has the permission", async () => {
    route("get", `/api/v1/cashbox/sessions/${ID}`, PENDING);
    renderCashbox(`/cashbox/sessions/${ID}`);

    expect(await screen.findByRole("button", { name: "Aprobar cierre" }, { timeout: 10_000 })).toBeVisible();
  });
});

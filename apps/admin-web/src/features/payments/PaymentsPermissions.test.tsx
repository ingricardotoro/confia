import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { paymentPage, paymentRecord } from "../../test/contract-responses.ts";
import { installMswServer, route } from "../../test/msw-server.ts";
import { renderPayments } from "../../test/render-payments.tsx";
import { PERMISSION_CODES } from "../../shared/permissions/permission-codes.ts";

// The payments for a person who may read them but not register or decide them: the list and the detail are shown, and the register and the
// decision are not offered. The server still decides: this only hides what the session cannot use.
installMswServer();

const ID = "00000000-0000-4000-8000-0000000d0001";
const TIMEOUT = { timeout: 10_000 };
const without = (code: string) => PERMISSION_CODES.filter((candidate) => candidate !== code);

describe("the payments without the write permissions", () => {
  it("shows the empty list without the way to register a payment to a session without payments:create", async () => {
    route("get", "/api/v1/payments", paymentPage([]));
    renderPayments("/payments", { permissions: without("payments:create") });

    expect(await screen.findByText("Todavía no hay pagos", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Registrar un pago" })).not.toBeInTheDocument();
  });

  it("shows a declared transfer without Confirm and Reject to a session without payments:approve", async () => {
    route(
      "get",
      `/api/v1/payments/${ID}`,
      paymentRecord({ id: ID, method: "BANK_TRANSFER", status: "declared", reference: "AB1234", bankName: "Banco Atlántida", allocated: { amount: "0.00", currency: "HNL" }, allocations: [] }),
    );
    renderPayments(`/payments/${ID}`, { permissions: without("payments:approve") });

    expect(await screen.findByText(/Esta transferencia está pendiente de confirmación/, {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Confirmar" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Rechazar" })).not.toBeInTheDocument();
  });
});

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import {
  cashSession,
  paymentRecord,
  problem,
  statementRecord,
  statementSection,
  student,
  studentCharge,
  studentChargePage,
  studentPage,
  studentStatement,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderPayments } from "../../test/render-payments.tsx";

// The register of a payment over the real route, the real query layer and the real transport: the student and what they owe as the server
// says it, cash with and without an open cashbox, the request with its own Idempotency-Key and nothing sent twice, the refusals of the server
// where they belong, and the result the server computed. The browser adds, splits and rounds nothing: every figure on screen was sent.
installMswServer();

const ANA = student({ id: "00000000-0000-4000-8000-000000005001", firstName: "Ana", lastName: "Aguilar", studentCode: "E-000001" });
const CHARGES = `/api/v1/charges/students/${ANA.id}/charges`;
const STATEMENT = `/api/v1/charges/students/${ANA.id}/statement`;
const CURRENT = "/api/v1/cashbox/sessions/current";
const PAYMENTS = "/api/v1/payments";
const TIMEOUT = { timeout: 10_000 };
const OLDEST = studentCharge({
  id: "00000000-0000-4000-8000-0000000c0001",
  periodLabel: "2027-01",
  conceptName: "Mensualidad",
  dueOn: "2027-01-05",
  total: { amount: "1265.00", currency: "HNL" },
  outstanding: { amount: "1265.00", currency: "HNL" },
});
const NEXT = studentCharge({
  id: "00000000-0000-4000-8000-0000000c0002",
  periodLabel: "2027-02",
  conceptName: "Mensualidad",
  dueOn: "2027-02-05",
  total: { amount: "1265.00", currency: "HNL" },
  outstanding: { amount: "1265.00", currency: "HNL" },
});
const SETTLED = studentCharge({
  id: "00000000-0000-4000-8000-0000000c0003",
  periodLabel: "2026-12",
  conceptName: "Matrícula",
  dueOn: "2026-12-05",
  paid: { amount: "800.00", currency: "HNL" },
  outstanding: { amount: "0.00", currency: "HNL" },
  settled: true,
});

const posts = () => requests().filter((sent) => sent.method === "POST" && sent.path === PAYMENTS);
const keyOf = (sent: { headers: Headers } | undefined) => sent?.headers.get("Idempotency-Key") ?? "";

type Reply = Parameters<typeof route>[2];

interface Setup {
  readonly cashbox?: Reply;
  readonly charges?: Reply;
}

async function chooseStudent(user: ReturnType<typeof userEvent.setup>, setup: Setup = {}) {
  route("get", "/api/v1/students", studentPage([ANA]));
  route("get", CHARGES, setup.charges ?? studentChargePage([OLDEST, NEXT]));
  route("get", STATEMENT, statementRecord(studentStatement({ studentId: ANA.id, sections: [statementSection({ closingBalance: { amount: "2530.00", currency: "HNL" } })] })));
  route("get", CURRENT, setup.cashbox ?? cashSession());
  const view = renderPayments("/payments/register");
  await user.type(await screen.findByRole("combobox", { name: "Estudiante" }, TIMEOUT), "Agu");
  await user.click(await screen.findByRole("option", { name: "Aguilar, Ana (E-000001)" }, TIMEOUT));
  return view;
}

async function fill(user: ReturnType<typeof userEvent.setup>, amount: string) {
  await user.type(screen.getByRole("textbox", { name: /Importe recibido/ }), amount);
}

const submit = () => screen.getByRole("button", { name: "Registrar pago" });

describe("what the student owes", () => {
  it("shows the charges with something left to pay, with what each has paid and owes and the balance as the server sent them, oldest due date first", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);

    const table = await screen.findByRole("table", { name: /Cargos vigentes del estudiante/ }, TIMEOUT);
    expect(within(table).getAllByText(/1,265\.00/).length).toBeGreaterThan(0);
    expect(within(table).getByRole("row", { name: /2027-01/ })).toBeInTheDocument();
    expect((await screen.findAllByText(/2,530\.00/)).length).toBeGreaterThan(0);
    const asked = new URLSearchParams(requests().find((sent) => sent.path === CHARGES)?.search);
    // The server decides what is pending and in what order (the allocation order): no sort, no client filter.
    expect(asked.get("outstanding")).toBe("true");
    expect(asked.has("sort")).toBe(false);
    expect(asked.get("status")).toBe("ACTIVE");
    expect(asked.get("size")).toBe("10");
  });

  it("shows the rows the server sent without judging them in the browser, and asks the server for the next page", async () => {
    const user = userEvent.setup();
    await chooseStudent(user, { charges: studentChargePage([SETTLED, OLDEST], { page: 0, size: 10, total: 25 }) });

    const table = await screen.findByRole("table", { name: /Cargos vigentes del estudiante/ }, TIMEOUT);
    expect(within(table).getByRole("row", { name: /2026-12/ })).toBeInTheDocument();
    route("get", CHARGES, studentChargePage([NEXT], { page: 1, size: 10, total: 25 }));
    await user.click(screen.getByRole("button", { name: /Siguiente/ }));

    expect(await screen.findByRole("row", { name: /2027-02/ }, TIMEOUT)).toBeInTheDocument();
    const pages = requests().filter((sent) => sent.path === CHARGES).map((sent) => new URLSearchParams(sent.search).get("page"));
    expect(pages).toContain("1");
  });

  it("says in words that the student has no pending charges, with a way forward, and keeps the form available", async () => {
    const user = userEvent.setup();
    await chooseStudent(user, { charges: studentChargePage([]) });

    expect(await screen.findByText("El estudiante no tiene cargos pendientes", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ver los cargos del estudiante" })).toBeInTheDocument();
    expect(submit()).toBeEnabled();
  });

  it("tells a failed load of the charges with a retry, and reads again when asked", async () => {
    const user = userEvent.setup();
    await chooseStudent(user, { charges: problem("internal-error", 500) });

    expect(await screen.findByText("No se pudieron cargar los cargos del estudiante", {}, TIMEOUT)).toBeInTheDocument();
    route("get", CHARGES, studentChargePage([OLDEST]));
    await user.click(screen.getByRole("button", { name: /Reintentar/ }));

    expect(await screen.findByRole("row", { name: /2027-01/ }, TIMEOUT)).toBeInTheDocument();
  });
});

describe("cash with no open cashbox", () => {
  it("says so, offers the way to the cashbox, keeps the button from sending, and does not ask a transfer for a cashbox", async () => {
    const user = userEvent.setup();
    const view = await chooseStudent(user, { cashbox: problem("resource-not-found", 404) });

    expect(await screen.findByText("No tiene una caja abierta", {}, TIMEOUT)).toBeInTheDocument();
    expect(submit()).toBeDisabled();
    await user.selectOptions(screen.getByRole("combobox", { name: /Método de pago/ }), "BANK_TRANSFER");
    expect(screen.queryByText("No tiene una caja abierta")).not.toBeInTheDocument();
    expect(submit()).toBeEnabled();
    await user.selectOptions(screen.getByRole("combobox", { name: /Método de pago/ }), "CASH");
    await user.click(await screen.findByRole("button", { name: "Ir a Mi caja" }));

    await waitFor(() => {
      expect(view.router.state.location.pathname).toBe("/cashbox");
    }, TIMEOUT);
    expect(posts()).toHaveLength(0);
  });

  it("tells the refusal of the server when the session closed between the view and the send, and reads the cashbox again", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);
    route("post", PAYMENTS, problem("cash-session-not-open", 409));
    route("get", CURRENT, problem("resource-not-found", 404));
    await fill(user, "1500.00");
    await user.click(submit());

    expect(await screen.findByText(/La caja ya no está abierta/, {}, TIMEOUT)).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Ir a Mi caja" }, TIMEOUT)).toBeInTheDocument();
  });
});

describe("registering a cash payment", () => {
  it("sends the exact body with its own Idempotency-Key and shows what the server applied and what stays in favor, verbatim", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);
    route(
      "post",
      PAYMENTS,
      paymentRecord(
        {
          amount: { amount: "1500.00", currency: "HNL" },
          allocated: { amount: "1500.00", currency: "HNL" },
          unallocated: { amount: "0.00", currency: "HNL" },
          allocations: [
            { chargeId: OLDEST.id, conceptName: "Mensualidad", periodLabel: "2027-01", dueOn: "2027-01-05", amount: { amount: "1265.00", currency: "HNL" }, allocatedAt: "2026-11-20T12:00:00Z" },
            { chargeId: NEXT.id, conceptName: "Mensualidad", periodLabel: "2027-02", dueOn: "2027-02-05", amount: { amount: "235.00", currency: "HNL" }, allocatedAt: "2026-11-20T12:00:00Z" },
          ],
        },
        201,
      ),
    );
    await fill(user, "1500.00");
    await user.click(submit());

    expect(await screen.findByRole("heading", { name: "Pago registrado" }, TIMEOUT)).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    const body = JSON.parse(posts()[0]?.body ?? "{}") as Record<string, unknown>;
    expect(body).toMatchObject({ studentId: ANA.id, method: "CASH", amount: { amount: "1500.00", currency: "HNL" } });
    expect(body).not.toHaveProperty("reference");
    expect(keyOf(posts()[0])).not.toBe("");
    const allocations = screen.getByRole("table", { name: /Cargos a los que se aplicó el pago/ });
    expect(within(allocations).getByRole("row", { name: /2027-01/ })).toHaveTextContent("1,265.00");
    expect(within(allocations).getByRole("row", { name: /2027-02/ })).toHaveTextContent("235.00");
    expect(screen.getAllByText(/0\.00/).length).toBeGreaterThan(0);
  });

  it("reads again what a payment changes: the charges, the statement and the cashbox", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);
    route("post", PAYMENTS, paymentRecord({}, 201));
    const before = requests().length;
    await fill(user, "1150.00");
    await user.click(submit());
    await screen.findByRole("heading", { name: "Pago registrado" }, TIMEOUT);

    await waitFor(() => {
      const paths = requests().slice(before).map((sent) => sent.path);
      expect(paths).toContain(CHARGES);
      expect(paths).toContain(STATEMENT);
      expect(paths).toContain(CURRENT);
    }, TIMEOUT);
  });

  it("sends one request on a double click, and says it is sending while it waits", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);
    route("post", PAYMENTS, paymentRecord({}, 201));
    await fill(user, "100.00");
    await user.dblClick(submit());

    await screen.findByRole("heading", { name: "Pago registrado" }, TIMEOUT);
    expect(posts()).toHaveLength(1);
  });

  it("refuses an amount that does not hold with nothing sent, and says why under the field", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);
    await fill(user, "12.345");
    await user.click(submit());

    expect((await screen.findAllByText(/hasta dos decimales/)).length).toBeGreaterThan(0);
    expect(posts()).toHaveLength(0);
  });

  it("refuses a missing student and a zero with nothing sent", async () => {
    const user = userEvent.setup();
    route("get", CURRENT, cashSession());
    renderPayments("/payments/register");
    await screen.findByRole("combobox", { name: "Estudiante" }, TIMEOUT);
    await fill(user, "0.00");
    await user.click(submit());

    expect((await screen.findAllByText("Este dato es obligatorio.")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("El importe debe ser mayor que cero.").length).toBeGreaterThan(0);
    expect(posts()).toHaveLength(0);
  });

  it("shows the errors of the server under the field it names and keeps what was typed", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);
    route("post", PAYMENTS, problem("validation-failed", 400, { errors: [{ field: "amount.amount", reason: "pattern" }] }));
    const amount = screen.getByRole("textbox", { name: /Importe recibido/ });
    await user.type(amount, "99");
    await user.click(submit());

    expect((await screen.findAllByText(/hasta dos decimales/, {}, TIMEOUT)).length).toBeGreaterThan(0);
    expect(amount).toHaveValue("99");
    expect(posts()).toHaveLength(1);
  });

  it("starts a cash payment on today in the institution time zone and tells the cash-day refusal of the server under the date", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);
    route("post", PAYMENTS, problem("validation-failed", 400, { errors: [{ field: "receivedOn", reason: "cash-received-today" }] }));
    const today = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Tegucigalpa", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
    const day = screen.getByLabelText(/Fecha de recepción/);
    expect(day).toHaveValue(today);
    await fill(user, "100.00");
    await user.click(submit());

    expect((await screen.findAllByText(/El efectivo se recibe el día de hoy/, {}, TIMEOUT)).length).toBeGreaterThan(0);
    expect(posts()).toHaveLength(1);
  });

  it("puts the date back on today when the method goes back to cash", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);
    const today = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Tegucigalpa", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
    await user.selectOptions(screen.getByRole("combobox", { name: /Método de pago/ }), "BANK_TRANSFER");
    const day = screen.getByLabelText(/Fecha de recepción/);
    await user.clear(day);
    await user.type(day, "2026-01-02");
    await user.selectOptions(screen.getByRole("combobox", { name: /Método de pago/ }), "CASH");

    expect(screen.getByLabelText(/Fecha de recepción/)).toHaveValue(today);
  });

  it("sends the same Idempotency-Key again when the answer never arrived, and another when the amount changed", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);
    route("post", PAYMENTS, "network-error", problem("posting-accounts-incomplete", 409), problem("posting-accounts-incomplete", 409));
    const amount = screen.getByRole("textbox", { name: /Importe recibido/ });
    await user.type(amount, "300.00");
    await user.click(submit());
    await screen.findByText(/No se pudo conectar con el servidor/, {}, TIMEOUT);
    await user.click(submit());
    await screen.findByText(/Faltan las cuentas de caja o de banco/, {}, TIMEOUT);
    await user.clear(amount);
    await user.type(amount, "310.00");
    await user.click(submit());
    await waitFor(() => {
      expect(posts()).toHaveLength(3);
    }, TIMEOUT);

    const [first, second, third] = posts();
    expect(keyOf(second)).toBe(keyOf(first));
    expect(keyOf(third)).not.toBe(keyOf(first));
  });
});

describe("registering a declared transfer", () => {
  async function declare(user: ReturnType<typeof userEvent.setup>) {
    await chooseStudent(user);
    await user.selectOptions(screen.getByRole("combobox", { name: /Método de pago/ }), "BANK_TRANSFER");
    await fill(user, "1265.00");
  }

  it("asks for the reference and the bank, refuses a short reference with nothing sent, and says the transfer waits for its confirmation", async () => {
    const user = userEvent.setup();
    await declare(user);
    await user.type(screen.getByRole("textbox", { name: /Referencia bancaria/ }), "AB1");
    await user.click(submit());

    expect((await screen.findAllByText("La referencia debe tener de 4 a 40 caracteres.")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Este dato es obligatorio.").length).toBeGreaterThan(0);
    expect(posts()).toHaveLength(0);

    route("post", PAYMENTS, paymentRecord({ method: "BANK_TRANSFER", status: "declared", reference: "AB1234", bankName: "Banco Atlántida", allocated: { amount: "0.00", currency: "HNL" }, allocations: [] }, 201));
    await user.type(screen.getByRole("textbox", { name: /Referencia bancaria/ }), "234");
    await user.type(screen.getByRole("textbox", { name: /Banco/ }), "Banco Atlántida");
    await user.click(submit());

    expect(await screen.findByRole("heading", { name: "Transferencia declarada" }, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/No cambia el saldo del estudiante/)).toBeInTheDocument();
    expect(JSON.parse(posts()[0]?.body ?? "{}")).toMatchObject({ method: "BANK_TRANSFER", reference: "AB1234", bankName: "Banco Atlántida" });
    // A transfer never needs a cashbox.
    expect(screen.queryByRole("table", { name: /Cargos a los que se aplicó el pago/ })).not.toBeInTheDocument();
  });

  it("tells a repeated reference under the reference field", async () => {
    const user = userEvent.setup();
    await declare(user);
    route("post", PAYMENTS, problem("duplicate-transfer-reference", 409));
    await user.type(screen.getByRole("textbox", { name: /Referencia bancaria/ }), "AB1234");
    await user.type(screen.getByRole("textbox", { name: /Banco/ }), "Banco Atlántida");
    await user.click(submit());

    expect((await screen.findAllByText("Esa referencia de ese banco ya está registrada en otro pago.", {}, TIMEOUT)).length).toBeGreaterThan(0);
  });
});

describe("after the result", () => {
  it("offers to register another payment with a clean form", async () => {
    const user = userEvent.setup();
    await chooseStudent(user);
    route("post", PAYMENTS, paymentRecord({}, 201));
    await fill(user, "100.00");
    await user.click(submit());
    await user.click(await screen.findByRole("button", { name: "Registrar otro pago" }, TIMEOUT));

    expect(await screen.findByRole("textbox", { name: /Importe recibido/ }, TIMEOUT)).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Estudiante" })).toBeInTheDocument();
  });
});

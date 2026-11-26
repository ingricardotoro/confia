import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { ledgerAccount, ledgerAccountPage, paymentPostingAccount, paymentPostingAccountMap, postingAccount, postingAccountMap, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route, routeByQuery } from "../../test/msw-server.ts";
import { renderLedger } from "../../test/render-ledger.tsx";

// The map of posting accounts over the real route, the real query layer and the real transport: the accounts each row may take
// (the lists the server serves by type), what an incomplete map says, the save with only what changed and the version of each
// row, and the refusals (a type that does not fit, an account that is gone, a stale version, a 403). Accounts are fictitious.
installMswServer();

const ACCOUNTS = "/api/v1/ledger/accounts";
const MAP = "/api/v1/charges/posting-accounts";
const PAYMENT_MAP = "/api/v1/payments/posting-accounts";
const TIMEOUT = { timeout: 10_000 };

const RECEIVABLE = ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a1", code: "ACT-01", name: "Cuentas por cobrar de prueba", type: "ASSET", holdsStudentBalances: true });
const CASH = ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a2", code: "ACT-02", name: "Caja de prueba", type: "ASSET", holdsStudentBalances: false });
const TAX = ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a3", code: "PAS-01", name: "Impuesto de prueba por pagar", type: "LIABILITY", holdsStudentBalances: false });
const TUITION = ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a4", code: "ING-01", name: "Ingreso de colegiatura de prueba", type: "INCOME", holdsStudentBalances: false });
const OTHER = ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a5", code: "ING-02", name: "Otros ingresos de prueba", type: "INCOME", holdsStudentBalances: false });
const BANK = ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a6", code: "ACT-03", name: "Banco de prueba", type: "ASSET", holdsStudentBalances: false });
const CHART = [RECEIVABLE, CASH, BANK, TAX, TUITION, OTHER];
/** The cash and the bank set, so the tests of the map of the charges meet a complete map of the payments. */
const PAYMENT_ROWS = [paymentPostingAccount({ purpose: "CASH", ledgerAccountId: CASH.id, version: 1 }), paymentPostingAccount({ purpose: "BANK", ledgerAccountId: BANK.id, version: 2 })];

const writes = () => requests().filter((sent) => sent.method === "PUT");
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

/** The chart as the server serves it: only the accounts of the type asked for, and only the active ones when asked. */
function serveChart(accounts = CHART): void {
  routeByQuery("get", ACCOUNTS, (query) =>
    ledgerAccountPage(accounts.filter((account) => (query.get("type") === null || account.type === query.get("type")) && (query.get("active") !== "true" || account.active))),
  );
}

async function openMap(rows: unknown[] = [], accounts = CHART, paymentRows: unknown[] = PAYMENT_ROWS) {
  route("get", MAP, postingAccountMap(rows));
  route("get", PAYMENT_MAP, paymentPostingAccountMap(paymentRows));
  serveChart(accounts);
  const rendered = renderLedger("/ledger/posting-accounts");
  await screen.findByLabelText("Cuentas por cobrar a estudiantes", undefined, TIMEOUT);
  return rendered;
}

const optionsOf = (label: string) => within(screen.getByLabelText(label)).getAllByRole("option").map((option) => option.textContent);

describe("the map of posting accounts", () => {
  it("is the second view of the area, and asks the server for the active accounts of each type, at the largest page", async () => {
    const { router } = await openMap();

    expect(router.state.location.pathname).toBe("/ledger/posting-accounts");
    expect(screen.getByRole("link", { name: "Mapa de cuentas de cargos" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("heading", { level: 2, name: "Mapa de cuentas de cargos" })).toBeInTheDocument();
    const asked = requests()
      .filter((sent) => sent.path === ACCOUNTS)
      .map((sent) => Object.fromEntries(new URLSearchParams(sent.search)));
    expect(asked.map((query) => query["type"]).sort()).toEqual(["ASSET", "EXPENSE", "INCOME", "LIABILITY"]);
    expect(asked.every((query) => query["active"] === "true" && query["size"] === "200")).toBe(true);
  });

  it("offers each row only the accounts of the type it needs, and the receivable only an account that carries student balances", async () => {
    await openMap();

    expect(optionsOf("Cuentas por cobrar a estudiantes")).toEqual(["Elija una cuenta", "ACT-01 · Cuentas por cobrar de prueba"]);
    expect(optionsOf("Impuesto por pagar")).toEqual(["Elija una cuenta", "PAS-01 · Impuesto de prueba por pagar"]);
    expect(optionsOf("Ingreso por matrícula")).toEqual(["Elija una cuenta", "ING-01 · Ingreso de colegiatura de prueba", "ING-02 · Otros ingresos de prueba"]);
  });

  it("offers the scholarships row the income and the expense accounts, and no other, and saves its choice as the purpose BENEFITS", async () => {
    const user = userEvent.setup();
    const EXPENSE = ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a7", code: "GAS-01", name: "Becas otorgadas de prueba", type: "EXPENSE", holdsStudentBalances: false });
    await openMap([], [...CHART, EXPENSE]);
    route("put", MAP, postingAccountMap([postingAccount({ purpose: "BENEFITS", ledgerAccountId: EXPENSE.id, version: 0 })]));

    expect(optionsOf("Becas y descuentos")).toEqual([
      "Elija una cuenta",
      "ING-01 · Ingreso de colegiatura de prueba",
      "ING-02 · Otros ingresos de prueba",
      "GAS-01 · Becas otorgadas de prueba",
    ]);
    await user.selectOptions(screen.getByLabelText("Becas y descuentos"), EXPENSE.id);
    await user.click(screen.getByRole("button", { name: "Guardar mapa" }));

    await waitFor(() => {
      expect(writes()).toHaveLength(1);
    }, TIMEOUT);
    expect(bodyOf(writes()[0])).toEqual({ entries: [{ purpose: "BENEFITS", ledgerAccountId: EXPENSE.id }] });
  });

  it("shows an empty map as incomplete, row by row without configuring, and says it blocks the generation of charges", async () => {
    await openMap();

    const banner = screen.getByText("El mapa de cuentas está incompleto");
    expect(banner).toBeInTheDocument();
    expect(screen.getByText(/la generación de cargos queda bloqueada/)).toBeInTheDocument();
    expect(screen.getAllByText("Sin configurar")).toHaveLength(10);
    expect(screen.getByRole("button", { name: "Guardar mapa" })).toBeDisabled();
  });

  it("shows the accounts a saved map holds, with no banner when the main rows are set", async () => {
    await openMap([
      postingAccount({ purpose: "RECEIVABLE", ledgerAccountId: RECEIVABLE.id, version: 1 }),
      postingAccount({ purpose: "TAX_PAYABLE", ledgerAccountId: TAX.id, version: 0 }),
      postingAccount({ purpose: "REVENUE", conceptCategory: null, ledgerAccountId: OTHER.id, version: 3 }),
    ]);

    expect(screen.getByLabelText("Cuentas por cobrar a estudiantes")).toHaveValue(RECEIVABLE.id);
    expect(screen.getByLabelText("Impuesto por pagar")).toHaveValue(TAX.id);
    expect(screen.getByLabelText("Ingreso por defecto")).toHaveValue(OTHER.id);
    expect(screen.getByLabelText("Ingreso por multas")).toHaveValue("");
    expect(screen.queryByText("El mapa de cuentas está incompleto")).not.toBeInTheDocument();
    expect(screen.getAllByText("Configurada")).toHaveLength(5);
  });

  it("keeps the account a row holds in its select even when the list does not have it", async () => {
    await openMap([postingAccount({ purpose: "RECEIVABLE", ledgerAccountId: "00000000-0000-4000-8000-0000000000ff", version: 1 })]);

    expect(optionsOf("Cuentas por cobrar a estudiantes")).toContain("Cuenta no disponible en la lista");
    expect(screen.getByLabelText("Cuentas por cobrar a estudiantes")).toHaveValue("00000000-0000-4000-8000-0000000000ff");
  });

  it("saves only the rows that changed, a saved one with its version and a new one with none, reads the map again and says so", async () => {
    const user = userEvent.setup();
    await openMap([postingAccount({ purpose: "RECEIVABLE", ledgerAccountId: RECEIVABLE.id, version: 4 })]);
    const saved = [
      postingAccount({ purpose: "RECEIVABLE", ledgerAccountId: RECEIVABLE.id, version: 4 }),
      postingAccount({ purpose: "TAX_PAYABLE", ledgerAccountId: TAX.id, version: 0 }),
      postingAccount({ purpose: "REVENUE", conceptCategory: "TUITION", ledgerAccountId: TUITION.id, version: 0 }),
    ];
    route("put", MAP, postingAccountMap(saved));
    route("get", MAP, postingAccountMap(saved));
    await user.selectOptions(screen.getByLabelText("Impuesto por pagar"), TAX.id);
    await user.selectOptions(screen.getByLabelText("Ingreso por colegiatura"), TUITION.id);
    expect(screen.getByText("2 cambios sin guardar")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Guardar mapa" }));

    expect(await screen.findByText("Se guardó el mapa de cuentas de cargos.")).toBeInTheDocument();
    expect(bodyOf(writes()[0])).toEqual({
      entries: [
        { purpose: "TAX_PAYABLE", ledgerAccountId: TAX.id },
        { purpose: "REVENUE", conceptCategory: "TUITION", ledgerAccountId: TUITION.id },
      ],
    });
    expect(writes()[0]?.headers.has("Idempotency-Key")).toBe(false);
    await waitFor(() => {
      expect(screen.getByText("No hay cambios por guardar.")).toBeInTheDocument();
    });
    expect(screen.getByLabelText("Impuesto por pagar")).toHaveValue(TAX.id);
  });

  it("sends the version of a row that is changed to another account", async () => {
    const user = userEvent.setup();
    await openMap([postingAccount({ purpose: "REVENUE", conceptCategory: null, ledgerAccountId: TUITION.id, version: 7 })]);
    route("put", MAP, postingAccountMap([postingAccount({ purpose: "REVENUE", conceptCategory: null, ledgerAccountId: OTHER.id, version: 8 })]));

    await user.selectOptions(screen.getByLabelText("Ingreso por defecto"), OTHER.id);
    await user.click(screen.getByRole("button", { name: "Guardar mapa" }));

    await waitFor(() => {
      expect(writes()).toHaveLength(1);
    });
    expect(bodyOf(writes()[0])).toEqual({ entries: [{ purpose: "REVENUE", ledgerAccountId: OTHER.id, version: 7 }] });
  });

  it("discards what was chosen and not saved", async () => {
    const user = userEvent.setup();
    await openMap();

    await user.selectOptions(screen.getByLabelText("Impuesto por pagar"), TAX.id);
    await user.click(screen.getByRole("button", { name: "Descartar cambios" }));

    expect(screen.getByLabelText("Impuesto por pagar")).toHaveValue("");
    expect(writes()).toHaveLength(0);
  });

  it("tells a type that does not fit as a refusal, keeps what was chosen and reads the accounts again", async () => {
    const user = userEvent.setup();
    await openMap();
    route("put", MAP, problem("posting-account-invalid", 409));
    const before = requests().filter((sent) => sent.path === ACCOUNTS).length;

    await user.selectOptions(screen.getByLabelText("Impuesto por pagar"), TAX.id);
    await user.click(screen.getByRole("button", { name: "Guardar mapa" }));

    expect(await screen.findByText(admin.problem.postingAccountInvalid)).toBeInTheDocument();
    expect(screen.getByLabelText("Impuesto por pagar")).toHaveValue(TAX.id);
    await waitFor(() => {
      expect(requests().filter((sent) => sent.path === ACCOUNTS).length).toBeGreaterThan(before);
    });
  });

  it("explains a stale version, drops what was chosen on the old map and shows the map the server holds now", async () => {
    const user = userEvent.setup();
    await openMap();
    route("put", MAP, problem("stale-version", 409));
    route("get", MAP, postingAccountMap([postingAccount({ purpose: "TAX_PAYABLE", ledgerAccountId: TAX.id, version: 1 })]));

    await user.selectOptions(screen.getByLabelText("Impuesto por pagar"), TAX.id);
    await user.selectOptions(screen.getByLabelText("Ingreso por multas"), OTHER.id);
    await user.click(screen.getByRole("button", { name: "Guardar mapa" }));

    expect(await screen.findByText(admin.ledger.map.staleVersion)).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByLabelText("Impuesto por pagar")).toHaveValue(TAX.id);
    });
    expect(screen.getByLabelText("Ingreso por multas")).toHaveValue("");
    expect(screen.getByText("No hay cambios por guardar.")).toBeInTheDocument();
  });

  it("tells an account that is gone as a 404 with its own words", async () => {
    const user = userEvent.setup();
    await openMap();
    route("put", MAP, problem("resource-not-found", 404));

    await user.selectOptions(screen.getByLabelText("Impuesto por pagar"), TAX.id);
    await user.click(screen.getByRole("button", { name: "Guardar mapa" }));

    expect(await screen.findByText(admin.ledger.map.accountMissing)).toBeInTheDocument();
  });

  it("tells an inactive account with the general text of the refusal", async () => {
    const user = userEvent.setup();
    await openMap();
    route("put", MAP, problem("inactive-reference", 409));

    await user.selectOptions(screen.getByLabelText("Impuesto por pagar"), TAX.id);
    await user.click(screen.getByRole("button", { name: "Guardar mapa" }));

    expect(await screen.findByText(admin.problem.inactiveReference)).toBeInTheDocument();
  });

  it("turns into a read only view with a note after a 403, because the session gives the screen no permissions", async () => {
    const user = userEvent.setup();
    await openMap();
    route("put", MAP, problem("forbidden", 403));

    await user.selectOptions(screen.getByLabelText("Impuesto por pagar"), TAX.id);
    await user.click(screen.getByRole("button", { name: "Guardar mapa" }));

    expect(await screen.findByText(/No tiene permiso para realizar esta acción/)).toBeInTheDocument();
    expect(screen.getByText("Solo lectura")).toBeInTheDocument();
    expect(screen.getByLabelText("Impuesto por pagar")).toBeDisabled();
    expect(screen.getByLabelText("Impuesto por pagar")).toHaveValue("");
    expect(screen.queryByRole("button", { name: "Guardar mapa" })).not.toBeInTheDocument();
  });

  it("sends the person to the chart when there is no active account at all", async () => {
    const user = userEvent.setup();
    route("get", MAP, postingAccountMap([]));
    route("get", PAYMENT_MAP, paymentPostingAccountMap(PAYMENT_ROWS));
    serveChart([]);
    renderLedger("/ledger/posting-accounts");
    expect(await screen.findByText("Todavía no hay cuentas activas", undefined, TIMEOUT)).toBeInTheDocument();
    route("get", ACCOUNTS, ledgerAccountPage([]));

    await user.click(screen.getByRole("button", { name: "Ir al plan de cuentas" }));

    expect(await screen.findByRole("heading", { level: 2, name: "Cuentas contables" }, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the error state of the map with the trace id and loads it on retry", async () => {
    const user = userEvent.setup();
    route("get", MAP, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), postingAccountMap([]));
    route("get", PAYMENT_MAP, paymentPostingAccountMap(PAYMENT_ROWS));
    serveChart();
    renderLedger("/ledger/posting-accounts");

    expect(await screen.findByText("No se pudo cargar el mapa de cuentas de cargos", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByLabelText("Cuentas por cobrar a estudiantes")).toBeInTheDocument();
  });

  it("tells a map that cannot be read for lack of permission", async () => {
    route("get", MAP, problem("forbidden", 403));
    route("get", PAYMENT_MAP, paymentPostingAccountMap(PAYMENT_ROWS));
    serveChart();
    renderLedger("/ledger/posting-accounts");

    expect(await screen.findByText(/No tiene permiso para realizar esta acción/, undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("offers a retry of the lists of accounts when they fail, and the map is still shown", async () => {
    const user = userEvent.setup();
    route("get", MAP, postingAccountMap([]));
    route("get", PAYMENT_MAP, paymentPostingAccountMap(PAYMENT_ROWS));
    routeByQuery("get", ACCOUNTS, (query) => (query.get("type") === "LIABILITY" ? problem("internal-error", 500) : ledgerAccountPage(CHART.filter((account) => account.type === query.get("type")))));
    renderLedger("/ledger/posting-accounts");

    expect(await screen.findByText("No se pudieron cargar las opciones de los filtros y de los formularios.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByLabelText("Cuentas por cobrar a estudiantes")).toBeInTheDocument();
    serveChart();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    await waitFor(() => {
      expect(optionsOf("Impuesto por pagar")).toContain("PAS-01 · Impuesto de prueba por pagar");
    });
  });

  describe("the cash and the bank of the payments", () => {
    it("shows the cash and the bank as their own group, each with only the assets that carry no student balance", async () => {
      await openMap([], CHART, []);

      expect(screen.getByRole("region", { name: "Cuentas de pagos" })).toBeInTheDocument();
      expect(optionsOf("Caja (efectivo)")).toEqual(["Elija una cuenta", "ACT-02 · Caja de prueba", "ACT-03 · Banco de prueba"]);
      expect(optionsOf("Banco (transferencias)")).toEqual(["Elija una cuenta", "ACT-02 · Caja de prueba", "ACT-03 · Banco de prueba"]);
      expect(requests().some((sent) => sent.path === PAYMENT_MAP)).toBe(true);
    });

    it("lists the cash and the bank as missing, with what that blocks, until both are set", async () => {
      await openMap([], CHART, [PAYMENT_ROWS[0]]);

      const banner = screen.getByText("El mapa de cuentas está incompleto").closest("[role]") ?? document.body;
      expect(within(banner as HTMLElement).getByText("Banco (transferencias)")).toBeInTheDocument();
      expect(within(banner as HTMLElement).queryByText("Caja (efectivo)")).not.toBeInTheDocument();
      expect(screen.getByText(/tampoco se puede cobrar en efectivo ni confirmar transferencias/)).toBeInTheDocument();
    });

    it("saves the cash and the bank to the payments endpoint with their versions, and the charges to theirs, each only when it changed", async () => {
      const user = userEvent.setup();
      await openMap([], CHART, [PAYMENT_ROWS[0]]);
      route("put", PAYMENT_MAP, paymentPostingAccountMap(PAYMENT_ROWS));
      await user.selectOptions(screen.getByLabelText("Banco (transferencias)"), BANK.id);
      await user.selectOptions(screen.getByLabelText("Caja (efectivo)"), BANK.id);
      await user.click(screen.getByRole("button", { name: "Guardar mapa" }));

      expect(await screen.findByText("Se guardó el mapa de cuentas de cargos.", undefined, TIMEOUT)).toBeInTheDocument();
      const puts = writes();
      expect(puts.map((sent) => sent.path)).toEqual([PAYMENT_MAP]);
      expect(bodyOf(puts[0])).toEqual({
        entries: [
          { purpose: "CASH", ledgerAccountId: BANK.id, version: 1 },
          { purpose: "BANK", ledgerAccountId: BANK.id },
        ],
      });
    });

    it("saves the charges first and then the payments, and tells when the payments were refused after the charges were saved", async () => {
      const user = userEvent.setup();
      await openMap([], CHART, []);
      route("put", MAP, postingAccountMap([postingAccount({ purpose: "RECEIVABLE", ledgerAccountId: RECEIVABLE.id, version: 0 })]));
      route("put", PAYMENT_MAP, problem("posting-account-invalid", 409));
      await user.selectOptions(screen.getByLabelText("Cuentas por cobrar a estudiantes"), RECEIVABLE.id);
      await user.selectOptions(screen.getByLabelText("Caja (efectivo)"), CASH.id);
      await user.click(screen.getByRole("button", { name: "Guardar mapa" }));

      expect(await screen.findByText(/Las cuentas de cargos se guardaron, pero las de pagos no/, undefined, TIMEOUT)).toBeInTheDocument();
      expect(writes().map((sent) => sent.path)).toEqual([MAP, PAYMENT_MAP]);
      // Only what was not saved is still pending: the cash.
      expect(screen.getByText("1 cambio sin guardar")).toBeInTheDocument();
    });

    it("shows the error state when the map of the payments cannot be read, and loads it on retry", async () => {
      const user = userEvent.setup();
      route("get", MAP, postingAccountMap([]));
      route("get", PAYMENT_MAP, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), paymentPostingAccountMap(PAYMENT_ROWS));
      serveChart();
      renderLedger("/ledger/posting-accounts");

      expect(await screen.findByText("No se pudo cargar el mapa de cuentas de cargos", undefined, TIMEOUT)).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Reintentar" }));

      expect(await screen.findByLabelText("Caja (efectivo)", undefined, TIMEOUT)).toBeInTheDocument();
    });
  });
});

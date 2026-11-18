import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { ledgerAccount, ledgerAccountPage, ledgerAccountRecord, problem, postingAccountMap } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderLedger } from "../../test/render-ledger.tsx";

// The chart of accounts over the real route, the real query layer and the real transport: the list as the server serves it
// (page, order and filters on the server and in the address), the dialogs of a new and of an edited account, the toggle with
// the version, the refusals (a repeated code, an account the map still uses, a stale version, a 403), and the states of the
// load. The accounts here are fictitious.
installMswServer();

const ACCOUNTS = "/api/v1/ledger/accounts";
const TIMEOUT = { timeout: 10_000 };

const RECEIVABLE = ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a1", code: "ACT-01", name: "Cuentas por cobrar de prueba", version: 2 });
const INCOME = ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a2", code: "ING-01", name: "Ingresos de prueba", type: "INCOME", holdsStudentBalances: false, active: false, version: 5 });

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === ACCOUNTS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT") => requests().filter((sent) => sent.method === method);
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

async function openList(rows: unknown[] = [RECEIVABLE, INCOME], path = "/ledger/accounts") {
  route("get", ACCOUNTS, ledgerAccountPage(rows));
  const rendered = renderLedger(path);
  const table = await screen.findByRole("table", { name: "Cuentas contables de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

describe("the chart of accounts", () => {
  it("is the first view of the area, shows each account in text and asks the server for the first page", async () => {
    const { router } = await openList(undefined, "/ledger");

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/ledger/accounts");
    });
    expect(screen.getByRole("heading", { level: 1, name: "Plan de cuentas" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Plan de cuentas" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("heading", { level: 2, name: "Cuentas contables" })).toBeInTheDocument();
    const first = within(screen.getByRole("row", { name: /ACT-01/ }));
    expect(first.getByText("Cuentas por cobrar de prueba")).toBeInTheDocument();
    expect(first.getByText("Sí")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /ING-01/ })).getByText("Inactivo")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("type")).toBe(false);
    expect(lastQuery().has("active")).toBe(false);
  });

  it("moves to the map of posting accounts from the sub navigation", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    route("get", "/api/v1/charges/posting-accounts", postingAccountMap([]));

    await user.click(screen.getByRole("link", { name: "Mapa de cuentas de cargos" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/ledger/posting-accounts");
    });
  });

  it("asks the server for the order of the code and name columns and keeps it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();

    await user.click(screen.getByRole("button", { name: "Nombre" }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("name,asc");
    });
    expect(decodeURIComponent(router.state.location.searchStr)).toContain("sort=name:asc");
  });

  it("filters by type and by state on the server and writes both in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    const filters = within(screen.getByRole("group", { name: "Filtros" }));

    await user.selectOptions(filters.getByLabelText("Tipo"), "INCOME");
    await waitFor(() => {
      expect(lastQuery().get("type")).toBe("INCOME");
    });
    await user.selectOptions(filters.getByLabelText("Estado"), "false");
    await waitFor(() => {
      expect(lastQuery().get("active")).toBe("false");
    });

    expect(router.state.location.searchStr).toContain("type=INCOME");
    expect(router.state.location.searchStr).toContain("active=false");
  });

  it("opens a shared link with its filters and order applied", async () => {
    await openList([RECEIVABLE], "/ledger/accounts?type=ASSET&active=true&sort=code:desc");

    expect(lastQuery().get("type")).toBe("ASSET");
    expect(lastQuery().get("active")).toBe("true");
    expect(lastQuery().get("sort")).toBe("code,desc");
    expect(screen.getByLabelText("Tipo", { selector: "select" })).toHaveValue("ASSET");
  });

  it("shows the empty state with the way forward, and no matches when a filter leaves nothing", async () => {
    const user = userEvent.setup();
    await openList([]);
    expect(screen.getByText("Todavía no hay cuentas contables")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear la primera cuenta" })).toBeInTheDocument();
    await user.selectOptions(within(screen.getByRole("group", { name: "Filtros" })).getByLabelText("Tipo"), "EXPENSE");

    expect(await screen.findByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    route("get", ACCOUNTS, ledgerAccountPage([RECEIVABLE]));
    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));
    expect(await screen.findByRole("row", { name: /ACT-01/ })).toBeInTheDocument();
  });

  it("shows the error state and loads the list on retry", async () => {
    const user = userEvent.setup();
    route("get", ACCOUNTS, problem("internal-error", 500), ledgerAccountPage([RECEIVABLE]));
    renderLedger("/ledger/accounts");

    expect(await screen.findByText("No se pudo cargar el plan de cuentas", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /ACT-01/ })).toBeInTheDocument();
  });
});

describe("creating, editing and toggling an account", () => {
  async function openCreateDialog() {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Nueva cuenta" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva cuenta contable" });
    return { user, dialog };
  }

  it("creates an account with its code, name, type and student flag, trims what it must, and lists it", async () => {
    const { user, dialog } = await openCreateDialog();
    route("post", ACCOUNTS, ledgerAccountRecord(RECEIVABLE, 201));
    route("get", ACCOUNTS, ledgerAccountPage([RECEIVABLE]));
    await user.type(within(dialog).getByLabelText(/Código/), "  ACT-01  ");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Cuentas por cobrar de prueba");
    await user.selectOptions(within(dialog).getByLabelText(/Tipo de cuenta/), "ASSET");
    await user.click(within(dialog).getByRole("checkbox", { name: "Lleva los saldos de los estudiantes" }));

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Cuentas por cobrar de prueba».")).toBeInTheDocument();
    expect(bodyOf(writes("POST")[0])).toEqual({ code: "ACT-01", name: "Cuentas por cobrar de prueba", type: "ASSET", holdsStudentBalances: true });
    expect(writes("POST")[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(await screen.findByRole("row", { name: /ACT-01/ })).toBeInTheDocument();
  });

  it("drops the student flag when the type is changed to one that cannot carry it", async () => {
    const { user, dialog } = await openCreateDialog();
    route("post", ACCOUNTS, ledgerAccountRecord(INCOME, 201));
    await user.type(within(dialog).getByLabelText(/Código/), "ING-01");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Ingresos de prueba");
    await user.selectOptions(within(dialog).getByLabelText(/Tipo de cuenta/), "ASSET");
    await user.click(within(dialog).getByRole("checkbox", { name: "Lleva los saldos de los estudiantes" }));
    await user.selectOptions(within(dialog).getByLabelText(/Tipo de cuenta/), "INCOME");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => {
      expect(writes("POST")).toHaveLength(1);
    });
    expect(bodyOf(writes("POST")[0])).toEqual({ code: "ING-01", name: "Ingresos de prueba", type: "INCOME", holdsStudentBalances: false });
  });

  it("refuses an empty form, and a code outside the whitelist, before sending", async () => {
    const { user, dialog } = await openCreateDialog();

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(await within(dialog).findByText("Hay 3 campos con errores.")).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/Código/), "AC T");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Caja");
    await user.selectOptions(within(dialog).getByLabelText(/Tipo de cuenta/), "ASSET");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Código/)).toHaveAccessibleDescription(/Use solo letras, números, guion y guion bajo/);
    });
    expect(writes("POST")).toHaveLength(0);
  });

  it("tells a repeated code under the code field, in the words of the account, and keeps what was typed", async () => {
    const { user, dialog } = await openCreateDialog();
    route("post", ACCOUNTS, problem("duplicate-account-code", 409));
    await user.type(within(dialog).getByLabelText(/Código/), "act-01");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Caja");
    await user.selectOptions(within(dialog).getByLabelText(/Tipo de cuenta/), "ASSET");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Código/)).toHaveAccessibleDescription(new RegExp(admin.ledger.accounts.errors.duplicateCode));
    });
    expect(within(dialog).getByLabelText(/Código/)).toBeInvalid();
    expect(within(dialog).getByLabelText(/Código/)).toHaveValue("act-01");
  });

  it("tells a 403 as no permission, inside the dialog", async () => {
    const { user, dialog } = await openCreateDialog();
    route("post", ACCOUNTS, problem("forbidden", 403));
    await user.type(within(dialog).getByLabelText(/Código/), "ACT-01");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Caja");
    await user.selectOptions(within(dialog).getByLabelText(/Tipo de cuenta/), "ASSET");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("No tiene permiso para realizar esta acción.");
  });

  it("edits the name and the state only, with the version, in one PUT that carries neither code nor type", async () => {
    const user = userEvent.setup();
    await openList();
    route("put", `${ACCOUNTS}/${INCOME.id}`, ledgerAccountRecord({ ...INCOME, name: "Otro nombre", active: true, version: 6 }));
    await user.click(screen.getByRole("button", { name: "Editar Ingresos de prueba" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar cuenta contable" });
    expect(within(dialog).getByLabelText(/Código/)).toHaveAttribute("readonly");
    await user.clear(within(dialog).getByLabelText(/Nombre/));
    await user.type(within(dialog).getByLabelText(/Nombre/), "Otro nombre");
    await user.click(within(dialog).getByRole("checkbox", { name: "Cuenta activa" }));

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se guardaron los cambios de «Otro nombre».")).toBeInTheDocument();
    expect(bodyOf(writes("PUT")[0])).toEqual({ version: 5, name: "Otro nombre", active: true });
  });

  it("tells the account changed, reads the list again and keeps the dialog open, when the edit answers 409 stale-version", async () => {
    const user = userEvent.setup();
    await openList();
    route("put", `${ACCOUNTS}/${RECEIVABLE.id}`, problem("stale-version", 409));
    route("get", ACCOUNTS, ledgerAccountPage([{ ...RECEIVABLE, name: "Nombre nuevo", version: 3 }, INCOME]));
    await user.click(screen.getByRole("button", { name: "Editar Cuentas por cobrar de prueba" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar cuenta contable" });
    const before = gets().length;

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.staleVersion);
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    });
    expect(await screen.findByRole("row", { name: /Nombre nuevo/, hidden: true })).toBeInTheDocument();
    expect(dialog).toBeInTheDocument();
  });

  it("deactivates from the row with a PUT that keeps the name and carries the version, and reactivates it", async () => {
    const user = userEvent.setup();
    await openList();
    route("put", `${ACCOUNTS}/${RECEIVABLE.id}`, ledgerAccountRecord({ ...RECEIVABLE, active: false, version: 3 }));
    route("get", ACCOUNTS, ledgerAccountPage([{ ...RECEIVABLE, active: false, version: 3 }, INCOME]));

    await user.click(screen.getByRole("button", { name: "Desactivar Cuentas por cobrar de prueba" }));

    expect(await screen.findByText("Se desactivó «Cuentas por cobrar de prueba».")).toBeInTheDocument();
    expect(bodyOf(writes("PUT")[0])).toEqual({ version: 2, name: "Cuentas por cobrar de prueba", active: false });
    route("put", `${ACCOUNTS}/${INCOME.id}`, ledgerAccountRecord({ ...INCOME, active: true, version: 6 }));
    await user.click(screen.getByRole("button", { name: "Activar Ingresos de prueba" }));
    expect(await screen.findByText("Se activó «Ingresos de prueba».")).toBeInTheDocument();
    expect(bodyOf(writes("PUT")[1])).toEqual({ version: 5, name: "Ingresos de prueba", active: true });
  });

  it("explains that the map still points at an account that cannot be deactivated, and leaves the account active", async () => {
    const user = userEvent.setup();
    await openList();
    route("put", `${ACCOUNTS}/${RECEIVABLE.id}`, problem("account-in-use", 409));

    await user.click(screen.getByRole("button", { name: "Desactivar Cuentas por cobrar de prueba" }));

    const notice = await screen.findByText(/está en uso en el mapa de cuentas de cargos/);
    expect(notice).toHaveTextContent("Cambie primero el mapa para que apunte a otra cuenta y después desactívela.");
    expect(within(screen.getByRole("row", { name: /ACT-01/ })).getByRole("button", { name: "Desactivar Cuentas por cobrar de prueba" })).toBeEnabled();
  });
});

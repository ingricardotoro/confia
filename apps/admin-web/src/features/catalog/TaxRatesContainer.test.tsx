import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { paymentConceptPage, problem, taxRate, taxRateDetail, taxRateDetailRecord, taxRatePage, taxRateRecord } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCatalog } from "../../test/render-catalog.tsx";

// The tax rates over the real route, the real query layer and the real transport: the list as the server serves it
// (page, order and filter on the server and in the address), the link to the detail, the dialog of a new and of an edited
// rate, the toggle with the version, the refusals, and the states of the load. The rates here are fictitious.
installMswServer();

const RATES = "/api/v1/catalog/tax-rates";
const TIMEOUT = { timeout: 10_000 };

const RATE_A = taxRate({ id: "00000000-0000-4000-8000-0000000000a1", code: "IMP-A", name: "Impuesto de prueba A", version: 2 });
const RATE_B = taxRate({ id: "00000000-0000-4000-8000-0000000000a2", code: "IMP-B", name: "Impuesto de prueba B", isActive: false, version: 5 });

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === RATES);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT") => requests().filter((sent) => sent.method === method);
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

async function openList(rows: unknown[] = [RATE_A, RATE_B], path = "/catalog/tax-rates") {
  route("get", RATES, taxRatePage(rows));
  const rendered = renderCatalog(path);
  const table = await screen.findByRole("table", { name: "Tasas de impuesto de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

describe("the list of tax rates", () => {
  it("is a tab of the catalog, shows each rate in text and asks the server for the first page", async () => {
    await openList();

    expect(screen.getByRole("heading", { level: 1, name: "Catálogo" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Tasas de impuesto" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Conceptos de pago" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("heading", { level: 2, name: "Tasas de impuesto" })).toBeInTheDocument();
    const first = screen.getByRole("row", { name: /IMP-A/ });
    expect(within(first).getByText("Impuesto de prueba A")).toBeInTheDocument();
    expect(within(first).getByText("Activo")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /IMP-B/ })).getByText("Inactivo")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("active")).toBe(false);
  });

  it("moves between the tabs of the catalog", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    route("get", "/api/v1/catalog/payment-concepts", paymentConceptPage([]));

    await user.click(screen.getByRole("link", { name: "Conceptos de pago" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/catalog");
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
    await user.click(screen.getByRole("button", { name: "Código" }));
    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("code,asc");
    });
  });

  it("filters by state on the server and writes it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();

    await user.selectOptions(within(screen.getByRole("group", { name: "Filtros" })).getByLabelText("Estado"), "true");

    await waitFor(() => {
      expect(lastQuery().get("active")).toBe("true");
    });
    expect(router.state.location.searchStr).toContain("active=true");
  });

  it("opens a shared link with its filter and order applied", async () => {
    await openList([RATE_A], "/catalog/tax-rates?active=false&sort=name:desc");

    expect(lastQuery().get("active")).toBe("false");
    expect(lastQuery().get("sort")).toBe("name,desc");
    expect(screen.getByLabelText("Estado", { selector: "select" })).toHaveValue("false");
  });

  it("opens the detail of a rate from its code", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    route("get", `${RATES}/${RATE_B.id}`, taxRateDetailRecord(taxRateDetail({ ...RATE_B, periods: [] })));

    await user.click(screen.getByRole("link", { name: "IMP-B" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/catalog/tax-rates/${RATE_B.id}`);
    });
  });

  it("shows the empty state with the way forward", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay tasas de impuesto")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear la primera tasa" })).toBeInTheDocument();
  });

  it("shows no matches, not the empty state, when the filter leaves nothing, and clears it", async () => {
    const user = userEvent.setup();
    await openList([], "/catalog/tax-rates?active=false");

    expect(screen.getByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    route("get", RATES, taxRatePage([RATE_A]));
    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));

    await waitFor(() => {
      expect(lastQuery().has("active")).toBe(false);
    });
    expect(await screen.findByRole("row", { name: /IMP-A/ })).toBeInTheDocument();
  });

  it("shows the error state and loads the list on retry", async () => {
    const user = userEvent.setup();
    route("get", RATES, problem("internal-error", 500), taxRatePage([RATE_A]));
    renderCatalog("/catalog/tax-rates");

    expect(await screen.findByText("No se pudo cargar la lista de tasas de impuesto", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /IMP-A/ })).toBeInTheDocument();
  });
});

describe("creating, editing and toggling a tax rate", () => {
  it("creates a rate with a code and a name only, trims the code, and lists it", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", RATES, taxRateRecord(taxRate({ code: "IMP-A", name: "Impuesto de prueba A" }), 201));
    route("get", RATES, taxRatePage([RATE_A]));
    await user.click(screen.getByRole("button", { name: "Nueva tasa" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva tasa de impuesto" });
    await user.type(within(dialog).getByLabelText(/Código/), "  IMP-A  ");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Impuesto de prueba A");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Impuesto de prueba A».")).toBeInTheDocument();
    expect(bodyOf(writes("POST")[0])).toEqual({ code: "IMP-A", name: "Impuesto de prueba A" });
    expect(writes("POST")[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(await screen.findByRole("row", { name: /IMP-A/ })).toBeInTheDocument();
  });

  it("refuses an empty code and name, and a code outside the whitelist, before sending", async () => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Nueva tasa" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva tasa de impuesto" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(await within(dialog).findByText("Hay 2 campos con errores.")).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/Código/), "IM P");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Impuesto");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Código/)).toHaveAccessibleDescription(/Use solo letras, números, guion y guion bajo/);
    });
    expect(writes("POST")).toHaveLength(0);
  });

  it("tells a repeated code, whatever its capitals, under the code field and keeps what was typed", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", RATES, problem("duplicate-tax-rate-code", 409));
    await user.click(screen.getByRole("button", { name: "Nueva tasa" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva tasa de impuesto" });
    await user.type(within(dialog).getByLabelText(/Código/), "imp-a");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Impuesto");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Código/)).toHaveAccessibleDescription(new RegExp(admin.problem.duplicateTaxRateCode));
    });
    expect(within(dialog).getByLabelText(/Código/)).toBeInvalid();
    expect(within(dialog).getByLabelText(/Código/)).toHaveValue("imp-a");
  });

  it("tells a 403 as no permission", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", RATES, problem("forbidden", 403));
    await user.click(screen.getByRole("button", { name: "Nueva tasa" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva tasa de impuesto" });
    await user.type(within(dialog).getByLabelText(/Código/), "IMP-A");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Impuesto");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("No tiene permiso para realizar esta acción.");
  });

  it("edits the name only, with the version and the state the rate has, in one PUT that carries no code", async () => {
    const user = userEvent.setup();
    await openList();
    route("put", `${RATES}/${RATE_B.id}`, taxRateRecord({ ...RATE_B, name: "Otro nombre", version: 6 }));
    await user.click(screen.getByRole("button", { name: "Editar Impuesto de prueba B" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar tasa de impuesto" });
    expect(within(dialog).getByLabelText(/Código/)).toHaveAttribute("readonly");
    await user.clear(within(dialog).getByLabelText(/Nombre/));
    await user.type(within(dialog).getByLabelText(/Nombre/), "Otro nombre");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se guardaron los cambios de «Otro nombre».")).toBeInTheDocument();
    expect(bodyOf(writes("PUT")[0])).toEqual({ version: 5, name: "Otro nombre", isActive: false });
  });

  it("tells the rate changed, reads the list again and keeps the dialog open, when the edit answers 409 stale-version", async () => {
    const user = userEvent.setup();
    await openList();
    route("put", `${RATES}/${RATE_A.id}`, problem("stale-version", 409));
    route("get", RATES, taxRatePage([{ ...RATE_A, name: "Nombre nuevo", version: 3 }, RATE_B]));
    await user.click(screen.getByRole("button", { name: "Editar Impuesto de prueba A" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar tasa de impuesto" });
    const before = gets().length;

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.staleVersion);
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    });
    expect(await screen.findByRole("row", { name: /Nombre nuevo/, hidden: true })).toBeInTheDocument();
    expect(dialog).toBeInTheDocument();
    expect(writes("PUT")).toHaveLength(1);
  });

  it("deactivates from the row with a PUT that keeps the name and carries the version, and activates it again", async () => {
    const user = userEvent.setup();
    await openList();
    route("put", `${RATES}/${RATE_A.id}`, taxRateRecord({ ...RATE_A, isActive: false, version: 3 }));
    route("get", RATES, taxRatePage([{ ...RATE_A, isActive: false, version: 3 }, RATE_B]));

    await user.click(screen.getByRole("button", { name: "Desactivar Impuesto de prueba A" }));

    expect(await screen.findByText("Se desactivó «Impuesto de prueba A».")).toBeInTheDocument();
    expect(bodyOf(writes("PUT")[0])).toEqual({ version: 2, name: "Impuesto de prueba A", isActive: false });

    route("put", `${RATES}/${RATE_A.id}`, taxRateRecord({ ...RATE_A, version: 4 }));
    await user.click(await screen.findByRole("button", { name: "Activar Impuesto de prueba A" }));

    expect(await screen.findByText("Se activó «Impuesto de prueba A».")).toBeInTheDocument();
    expect(bodyOf(writes("PUT")[1])).toMatchObject({ version: 3, isActive: true });
  });

  it("tells the rate changed when a toggle from the row answers 409 stale-version", async () => {
    const user = userEvent.setup();
    await openList();
    route("put", `${RATES}/${RATE_A.id}`, problem("stale-version", 409));

    await user.click(screen.getByRole("button", { name: "Desactivar Impuesto de prueba A" }));

    expect(await screen.findByText(admin.problem.staleVersion)).toBeInTheDocument();
  });

  it("tells a rate that no longer exists when the edit answers 404", async () => {
    const user = userEvent.setup();
    await openList();
    route("put", `${RATES}/${RATE_A.id}`, problem("resource-not-found", 404));
    await user.click(screen.getByRole("button", { name: "Editar Impuesto de prueba A" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar tasa de impuesto" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.notFound);
  });
});

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { paymentConcept, paymentConceptPage, paymentConceptRecord, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCatalog } from "../../test/render-catalog.tsx";

// The payment concepts over the real route, the real query layer and the real transport.
installMswServer();

const CONCEPTS = "/api/v1/catalog/payment-concepts";
const TIMEOUT = { timeout: 10_000 };

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === CONCEPTS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT") => requests().filter((sent) => sent.method === method);

async function openList(rows: unknown[] = [paymentConcept()], path = "/catalog") {
  route("get", CONCEPTS, paymentConceptPage(rows));
  const rendered = renderCatalog(path);
  const table = await screen.findByRole("table", { name: "Conceptos de pago de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

async function fillNew(dialog: HTMLElement, user: ReturnType<typeof userEvent.setup>, code = "COL", name = "Colegiatura") {
  await user.type(within(dialog).getByLabelText(/Código/), code);
  await user.type(within(dialog).getByLabelText(/Nombre/), name);
  await user.selectOptions(within(dialog).getByLabelText(/Categoría/), "TUITION");
}

describe("the list of payment concepts", () => {
  it("shows each concept in text, under the heading of the catalog, and asks the server for the first page", async () => {
    await openList([paymentConcept({ code: "COL", name: "Colegiatura" }), paymentConcept({ code: "MUL", name: "Multa por retraso", category: "FINE", isRecurring: false, isTaxable: false, isActive: false })]);

    expect(screen.getByRole("heading", { level: 1, name: "Catálogo" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Conceptos de pago" })).toBeInTheDocument();
    const first = screen.getByRole("row", { name: /Colegiatura/ });
    expect(within(first).getByText("COL")).toBeInTheDocument();
    expect(within(first).getByText("Activo")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Multa por retraso/ })).getByText("Inactivo")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("category")).toBe(false);
    expect(lastQuery().has("active")).toBe(false);
  });

  it("asks the server for the order of the name column and keeps it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();

    await user.click(screen.getByRole("button", { name: "Nombre" }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("name,asc");
    });
    expect(decodeURIComponent(router.state.location.searchStr)).toContain("sort=name:asc");
  });

  it("filters by category and by state on the server, and writes both in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    const filters = within(screen.getByRole("group", { name: "Filtros" }));

    await user.selectOptions(filters.getByLabelText("Categoría"), "FINE");
    await waitFor(() => {
      expect(lastQuery().get("category")).toBe("FINE");
    });
    await user.selectOptions(filters.getByLabelText("Estado"), "false");

    await waitFor(() => {
      expect(lastQuery().get("active")).toBe("false");
    });
    expect(lastQuery().get("category")).toBe("FINE");
    expect(router.state.location.searchStr).toContain("category=FINE");
    expect(router.state.location.searchStr).toContain("active=false");
  });

  it("opens a shared link with its filters applied", async () => {
    await openList([paymentConcept()], "/catalog?category=TRANSPORT&active=true&sort=code:desc");

    expect(lastQuery().get("category")).toBe("TRANSPORT");
    expect(lastQuery().get("active")).toBe("true");
    expect(lastQuery().get("sort")).toBe("code,desc");
    expect(screen.getByLabelText("Categoría", { selector: "select" })).toHaveValue("TRANSPORT");
  });

  it("shows the empty state with the way forward", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay conceptos de pago")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear el primer concepto" })).toBeInTheDocument();
  });

  it("shows no matches, not the empty state, when the filters leave nothing, and clears them", async () => {
    const user = userEvent.setup();
    await openList([], "/catalog?category=FINE");

    expect(screen.getByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    route("get", CONCEPTS, paymentConceptPage([paymentConcept()]));
    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));

    await waitFor(() => {
      expect(lastQuery().has("category")).toBe(false);
    });
    expect(await screen.findByRole("row", { name: /Colegiatura/ })).toBeInTheDocument();
  });

  it("shows the error state and loads the list on retry", async () => {
    const user = userEvent.setup();
    route("get", CONCEPTS, problem("internal-error", 500), paymentConceptPage([paymentConcept({ name: "Colegiatura" })]));
    renderCatalog("/catalog");

    expect(await screen.findByText("No se pudo cargar la lista de conceptos de pago", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /Colegiatura/ })).toBeInTheDocument();
  });
});

describe("creating, editing and deactivating a payment concept", () => {
  it("creates a concept with every field, and lists it", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", CONCEPTS, paymentConceptRecord(paymentConcept({ code: "COL", name: "Colegiatura" }), 201));
    route("get", CONCEPTS, paymentConceptPage([paymentConcept({ code: "COL", name: "Colegiatura" })]));
    await user.click(screen.getByRole("button", { name: "Nuevo concepto" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo concepto de pago" });
    await fillNew(dialog, user);
    await user.click(within(dialog).getByRole("checkbox", { name: "Recurrente" }));
    await user.click(within(dialog).getByRole("checkbox", { name: "Gravado" }));

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Colegiatura».")).toBeInTheDocument();
    expect(JSON.parse(writes("POST")[0]?.body ?? "{}")).toEqual({ code: "COL", name: "Colegiatura", category: "TUITION", isRecurring: true, isTaxable: true });
    expect(await screen.findByRole("row", { name: /Colegiatura/ })).toBeInTheDocument();
  });

  it("refuses an empty code, name and category before sending", async () => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Nuevo concepto" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo concepto de pago" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 3 campos con errores.")).toBeInTheDocument();
    expect(writes("POST")).toHaveLength(0);
  });

  it("tells a repeated code in its own words, and keeps what was typed", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", CONCEPTS, problem("duplicate-name", 409));
    await user.click(screen.getByRole("button", { name: "Nuevo concepto" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo concepto de pago" });
    await fillNew(dialog, user, "col");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.duplicateName);
    expect(within(dialog).getByLabelText(/Código/)).toHaveValue("col");
  });

  it("puts the field errors of a 400 under their fields", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", CONCEPTS, problem("validation-failed", 400, { errors: [{ field: "code", reason: "size" }] }));
    await user.click(screen.getByRole("button", { name: "Nuevo concepto" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo concepto de pago" });
    await fillNew(dialog, user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Código/)).toHaveAccessibleDescription("Este dato es demasiado largo.");
    });
  });

  it("tells a 403 as no permission", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", CONCEPTS, problem("forbidden", 403));
    await user.click(screen.getByRole("button", { name: "Nuevo concepto" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo concepto de pago" });
    await fillNew(dialog, user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("No tiene permiso para realizar esta acción.");
  });

  it("edits with every editable field and the state, in one PUT that carries no code", async () => {
    const user = userEvent.setup();
    const row = paymentConcept({ code: "COL", name: "Colegiatura" });
    await openList([row]);
    route("put", `${CONCEPTS}/${row.id}`, paymentConceptRecord({ ...row, name: "Colegiatura mensual", isActive: false }));
    await user.click(screen.getByRole("button", { name: "Editar Colegiatura" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar concepto de pago" });
    expect(within(dialog).getByLabelText(/Código/)).toHaveAttribute("readonly");
    await user.clear(within(dialog).getByLabelText(/Nombre/));
    await user.type(within(dialog).getByLabelText(/Nombre/), "Colegiatura mensual");
    await user.selectOptions(within(dialog).getByLabelText(/Categoría/), "OTHER");
    await user.click(within(dialog).getByRole("checkbox", { name: "Gravado" }));
    await user.click(within(dialog).getByRole("checkbox", { name: "Activo" }));

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se guardaron los cambios de «Colegiatura mensual».")).toBeInTheDocument();
    expect(JSON.parse(writes("PUT")[0]?.body ?? "{}")).toEqual({
      name: "Colegiatura mensual",
      category: "OTHER",
      isRecurring: true,
      isTaxable: false,
      isActive: false,
    });
  });

  it("tells a concept that no longer exists when the edit answers 404", async () => {
    const user = userEvent.setup();
    const row = paymentConcept({ name: "Colegiatura" });
    await openList([row]);
    route("put", `${CONCEPTS}/${row.id}`, problem("resource-not-found", 404));
    await user.click(screen.getByRole("button", { name: "Editar Colegiatura" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar concepto de pago" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toBeInTheDocument();
  });

  it("deactivates from the row with a PUT that keeps every other field, and activates it again", async () => {
    const user = userEvent.setup();
    const row = paymentConcept({ name: "Colegiatura", isTaxable: true });
    await openList([row]);
    route("put", `${CONCEPTS}/${row.id}`, paymentConceptRecord({ ...row, isActive: false }));
    route("get", CONCEPTS, paymentConceptPage([{ ...row, isActive: false }]));

    await user.click(screen.getByRole("button", { name: "Desactivar Colegiatura" }));

    expect(await screen.findByText("Se desactivó «Colegiatura».")).toBeInTheDocument();
    expect(JSON.parse(writes("PUT")[0]?.body ?? "{}")).toEqual({ name: "Colegiatura", category: "TUITION", isRecurring: true, isTaxable: true, isActive: false });

    route("put", `${CONCEPTS}/${row.id}`, paymentConceptRecord(row));
    await user.click(await screen.findByRole("button", { name: "Activar Colegiatura" }));

    expect(await screen.findByText("Se activó «Colegiatura».")).toBeInTheDocument();
    expect(JSON.parse(writes("PUT")[1]?.body ?? "{}")).toMatchObject({ isActive: true });
  });
});

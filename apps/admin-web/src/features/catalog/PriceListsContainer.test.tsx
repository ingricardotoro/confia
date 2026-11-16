import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { academicYear, grade, modality, organizationPage, priceList, priceListPage, priceListRecord, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCatalog } from "../../test/render-catalog.tsx";

// The price lists over the real route, the real query layer and the real transport: the list as the server serves it
// (page, order and filters on the server and in the address), the link to the detail, the new list dialog with its
// references, the refusals, and the states of the load.
installMswServer();

const LISTS = "/api/v1/catalog/price-lists";
const ORG = "/api/v1/organization";
const TIMEOUT = { timeout: 10_000 };

const YEAR = academicYear({ id: "11111111-1111-4111-8111-111111111111", name: "Año lectivo 2027", status: "ACTIVE" });
const DIURNA = modality({ id: "66666666-6666-4666-8666-666666666666", name: "Diurna" });
const PRIMERO = grade({ id: "33333333-3333-4333-8333-333333333333", modalityId: DIURNA.id, name: "Primero" });
const INSTITUTION_LIST = priceList({ id: "00000000-0000-4000-8000-0000000000e1", name: "2027 — Institución", academicYearId: YEAR.id });
const GRADE_LIST = priceList({
  id: "00000000-0000-4000-8000-0000000000e2",
  name: "2027 — Primero",
  academicYearId: YEAR.id,
  scope: "GRADE",
  scopeRefId: PRIMERO.id,
  status: "ARCHIVED",
});

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === LISTS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const posts = () => requests().filter((sent) => sent.method === "POST");

function routeLookups() {
  route("get", `${ORG}/academic-years`, organizationPage("academic-years", [YEAR]));
  route("get", `${ORG}/modalities`, organizationPage("modalities", [DIURNA]));
  route("get", `${ORG}/grades`, organizationPage("grades", [PRIMERO]));
}

async function openList(rows: unknown[] = [INSTITUTION_LIST, GRADE_LIST], path = "/catalog/price-lists") {
  routeLookups();
  route("get", LISTS, priceListPage(rows));
  const rendered = renderCatalog(path);
  const table = await screen.findByRole("table", { name: "Listas de precios de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

describe("the list of price lists", () => {
  it("is a tab of the catalog, shows each list in text with its year and reference named, and asks for the first page", async () => {
    await openList();

    expect(screen.getByRole("heading", { level: 1, name: "Catálogo" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Listas de precios" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Conceptos de pago" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("heading", { level: 2, name: "Listas de precios" })).toBeInTheDocument();
    const first = within(screen.getByRole("row", { name: /2027 — Institución/ }));
    expect(first.getByText("Año lectivo 2027")).toBeInTheDocument();
    expect(first.getByText("Toda la institución")).toBeInTheDocument();
    const second = within(screen.getByRole("row", { name: /2027 — Primero/ }));
    expect(second.getByText("Primero")).toBeInTheDocument();
    expect(second.getByText("Archivada")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("academicYearId")).toBe(false);
  });

  it("moves between the tabs of the catalog", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    route("get", "/api/v1/catalog/payment-concepts", {
      status: 200,
      body: { items: [], page: 0, size: 25, total: 0 },
      contentType: "application/json",
    });

    await user.click(screen.getByRole("link", { name: "Conceptos de pago" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/catalog");
    });
  });

  it("asks the server for the order of the name column and keeps it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();

    await user.click(screen.getByRole("button", { name: "Nombre" }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("name,desc");
    });
    expect(decodeURIComponent(router.state.location.searchStr)).toContain("sort=name:desc");
  });

  it("filters by year, scope and status on the server, and writes the three in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    const filters = within(screen.getByRole("group", { name: "Filtros" }));

    await user.selectOptions(filters.getByLabelText("Año lectivo"), YEAR.id);
    await waitFor(() => {
      expect(lastQuery().get("academicYearId")).toBe(YEAR.id);
    });
    await user.selectOptions(filters.getByLabelText("Ámbito"), "GRADE");
    await user.selectOptions(filters.getByLabelText("Estado"), "ARCHIVED");

    await waitFor(() => {
      expect(lastQuery().get("status")).toBe("ARCHIVED");
    });
    expect(lastQuery().get("scope")).toBe("GRADE");
    expect(lastQuery().get("academicYearId")).toBe(YEAR.id);
    expect(router.state.location.searchStr).toContain("scope=GRADE");
    expect(router.state.location.searchStr).toContain("status=ARCHIVED");
  });

  it("opens a shared link with its filters applied", async () => {
    await openList([INSTITUTION_LIST], `/catalog/price-lists?scope=INSTITUTION&status=ACTIVE&academicYearId=${YEAR.id}&sort=createdAt:desc`);

    expect(lastQuery().get("scope")).toBe("INSTITUTION");
    expect(lastQuery().get("status")).toBe("ACTIVE");
    expect(lastQuery().get("academicYearId")).toBe(YEAR.id);
    expect(lastQuery().get("sort")).toBe("createdAt,desc");
    expect(screen.getByLabelText("Ámbito", { selector: "select" })).toHaveValue("INSTITUTION");
  });

  it("opens the detail of a list from its name", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    route("get", `${LISTS}/${GRADE_LIST.id}`, { status: 200, body: { ...GRADE_LIST, items: [] }, contentType: "application/json" });

    await user.click(screen.getByRole("link", { name: "2027 — Primero" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/catalog/price-lists/${GRADE_LIST.id}`);
    });
  });

  it("shows the empty state with the way forward", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay listas de precios")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear la primera lista" })).toBeInTheDocument();
  });

  it("shows no matches, not the empty state, when the filters leave nothing, and clears them", async () => {
    const user = userEvent.setup();
    await openList([], "/catalog/price-lists?status=ARCHIVED");

    expect(screen.getByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    route("get", LISTS, priceListPage([INSTITUTION_LIST]));
    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));

    await waitFor(() => {
      expect(lastQuery().has("status")).toBe(false);
    });
    expect(await screen.findByRole("row", { name: /2027 — Institución/ })).toBeInTheDocument();
  });

  it("shows the error state and loads the list on retry", async () => {
    const user = userEvent.setup();
    routeLookups();
    route("get", LISTS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), priceListPage([INSTITUTION_LIST]));
    renderCatalog("/catalog/price-lists");

    expect(await screen.findByText("No se pudieron cargar las listas de precios", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /2027 — Institución/ })).toBeInTheDocument();
  });

  it("warns, with a retry, when the options of the years failed to load", async () => {
    const user = userEvent.setup();
    route("get", `${ORG}/academic-years`, problem("internal-error", 500), organizationPage("academic-years", [YEAR]));
    route("get", `${ORG}/modalities`, organizationPage("modalities", [DIURNA]));
    route("get", `${ORG}/grades`, organizationPage("grades", [PRIMERO]));
    route("get", LISTS, priceListPage([INSTITUTION_LIST]));
    renderCatalog("/catalog/price-lists");

    expect(await screen.findByText("No se pudieron cargar las opciones de los filtros y de los formularios.", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    await waitFor(() => {
      expect(screen.queryByText("No se pudieron cargar las opciones de los filtros y de los formularios.")).not.toBeInTheDocument();
    }, TIMEOUT);
  });
});

describe("creating a price list", () => {
  async function openDialog(user: ReturnType<typeof userEvent.setup>, rows: unknown[] = []) {
    await openList(rows);
    await user.click(screen.getByRole("button", { name: "Nueva lista de precios" }));
    return screen.findByRole("dialog", { name: "Nueva lista de precios" });
  }

  it("creates a list for the whole institution with the year, scope and name, and lists it", async () => {
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    route("post", LISTS, priceListRecord(priceList({ name: "2027 — Institución", academicYearId: YEAR.id }), 201));
    route("get", LISTS, priceListPage([INSTITUTION_LIST]));
    await user.selectOptions(within(dialog).getByLabelText(/Año lectivo/), YEAR.id);
    await user.type(within(dialog).getByLabelText(/Nombre/), "2027 — Institución");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «2027 — Institución».")).toBeInTheDocument();
    expect(JSON.parse(posts()[0]?.body ?? "{}")).toEqual({ academicYearId: YEAR.id, name: "2027 — Institución", scope: "INSTITUTION" });
    expect(posts()[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(await screen.findByRole("row", { name: /2027 — Institución/ })).toBeInTheDocument();
  });

  it("asks for the grade when the scope is a grade, sends it, and forgets it when the scope changes", async () => {
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    route("post", LISTS, priceListRecord(GRADE_LIST, 201));
    await user.selectOptions(within(dialog).getByLabelText(/Año lectivo/), YEAR.id);
    await user.selectOptions(within(dialog).getByLabelText(/Ámbito/), "GRADE");
    await user.selectOptions(within(dialog).getByLabelText(/Grado/), PRIMERO.id);
    await user.selectOptions(within(dialog).getByLabelText(/Ámbito/), "MODALITY");
    expect(within(dialog).getByLabelText(/Modalidad/)).toHaveValue("");
    await user.selectOptions(within(dialog).getByLabelText(/Ámbito/), "GRADE");
    await user.selectOptions(within(dialog).getByLabelText(/Grado/), PRIMERO.id);
    await user.type(within(dialog).getByLabelText(/Nombre/), "2027 — Primero");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «2027 — Primero».")).toBeInTheDocument();
    expect(JSON.parse(posts()[0]?.body ?? "{}")).toEqual({ academicYearId: YEAR.id, name: "2027 — Primero", scope: "GRADE", scopeRefId: PRIMERO.id });
  });

  it("refuses an empty year and name, and a missing modality, before sending", async () => {
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    await user.selectOptions(within(dialog).getByLabelText(/Ámbito/), "MODALITY");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 3 campos con errores.")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it.each([
    ["inactive-reference", 409, /relacionados está inactivo/],
    ["resource-not-found", 404, /no existe|no se encontr/i],
    ["forbidden", 403, /No tiene permiso/],
  ] as const)("tells %s in its own words, in the dialog, and keeps what was typed", async (code, status, text) => {
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    route("post", LISTS, problem(code, status));
    await user.selectOptions(within(dialog).getByLabelText(/Año lectivo/), YEAR.id);
    await user.type(within(dialog).getByLabelText(/Nombre/), "2027");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(text);
    expect(within(dialog).getByLabelText(/Nombre/)).toHaveValue("2027");
  });

  it("puts the field errors of a 400 under their fields", async () => {
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    route("post", LISTS, problem("validation-failed", 400, { errors: [{ field: "name", reason: "size" }] }));
    await user.selectOptions(within(dialog).getByLabelText(/Año lectivo/), YEAR.id);
    await user.type(within(dialog).getByLabelText(/Nombre/), "2027");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Nombre/)).toHaveAccessibleDescription(/Este dato es demasiado largo\./);
    });
  });
});

import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import {
  academicYear,
  grade,
  modality,
  organizationPage,
  paymentConcept,
  paymentConceptPage,
  priceList,
  priceListDetail,
  priceListDetailRecord,
  priceListItem,
  priceListItemRecord,
  priceListRecord,
  problem,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCatalog } from "../../test/render-catalog.tsx";

// The detail of a price list over the real route, the real query layer and the real transport: the validities of each
// concept, the new price or validity with its exact body, the refusals (overlap, archived, stale version, inactive or
// foreign references), the archive and the reactivation with the version, and the states of the load.
installMswServer();

const LISTS = "/api/v1/catalog/price-lists";
const CONCEPTS = "/api/v1/catalog/payment-concepts";
const ORG = "/api/v1/organization";
const TIMEOUT = { timeout: 10_000 };

const YEAR = academicYear({ id: "11111111-1111-4111-8111-111111111111", name: "Año lectivo 2027", status: "ACTIVE" });
const DIURNA = modality({ id: "66666666-6666-4666-8666-666666666666", name: "Diurna" });
const PRIMERO = grade({ id: "33333333-3333-4333-8333-333333333333", modalityId: DIURNA.id, name: "Primero" });
const COLEGIATURA = paymentConcept({ id: "00000000-0000-4000-8000-000000007001", code: "COL", name: "Colegiatura" });
const TRANSPORTE = paymentConcept({ id: "00000000-0000-4000-8000-000000007002", code: "TRA", name: "Transporte escolar" });
const VIEJO = paymentConcept({ id: "00000000-0000-4000-8000-000000007003", code: "OTR", name: "Otros ingresos", isActive: false });
const LIST_ID = "00000000-0000-4000-8000-0000000000e1";

const FIRST = priceListItem({
  id: "00000000-0000-4000-8000-0000000000f1",
  priceListId: LIST_ID,
  paymentConceptId: COLEGIATURA.id,
  price: { amount: "1500.00", currency: "HNL" },
  validFrom: "2027-01-01",
  validTo: "2027-06-30",
});
const SECOND = priceListItem({
  id: "00000000-0000-4000-8000-0000000000f2",
  priceListId: LIST_ID,
  paymentConceptId: COLEGIATURA.id,
  price: { amount: "1650.00", currency: "HNL" },
  validFrom: "2027-07-01",
  validTo: null,
});

const LIST = priceListDetail({ id: LIST_ID, name: "2027 — Institución", academicYearId: YEAR.id, version: 3, items: [FIRST, SECOND] });
const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === `${LISTS}/${LIST_ID}`);
const posts = () => requests().filter((sent) => sent.method === "POST");
const puts = () => requests().filter((sent) => sent.method === "PUT");
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

function routeLookups(concepts = [COLEGIATURA, TRANSPORTE, VIEJO]) {
  route("get", `${ORG}/academic-years`, organizationPage("academic-years", [YEAR]));
  route("get", `${ORG}/modalities`, organizationPage("modalities", [DIURNA]));
  route("get", `${ORG}/grades`, organizationPage("grades", [PRIMERO]));
  route("get", CONCEPTS, paymentConceptPage(concepts));
}

async function openDetail(detail: unknown = LIST) {
  routeLookups();
  route("get", `${LISTS}/${LIST_ID}`, priceListDetailRecord(detail));
  const rendered = renderCatalog(`/catalog/price-lists/${LIST_ID}`);
  await screen.findByRole("heading", { level: 2, name: (detail as { name: string }).name }, TIMEOUT);
  return rendered;
}

async function openAddDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Nuevo precio / nueva vigencia" }));
  return screen.findByRole("dialog", { name: "Nuevo precio / nueva vigencia" });
}

async function fillPrice(dialog: HTMLElement, user: ReturnType<typeof userEvent.setup>, values: { concept?: string; amount?: string; from?: string; to?: string } = {}) {
  await user.selectOptions(within(dialog).getByLabelText(/Concepto/), values.concept ?? COLEGIATURA.id);
  await user.type(within(dialog).getByLabelText(/Importe/), values.amount ?? "1650.00");
  await user.type(within(dialog).getByLabelText(/Vigente desde/), values.from ?? "2027-07-01");
  if (values.to !== undefined) await user.type(within(dialog).getByLabelText(/Vigente hasta/), values.to);
}

describe("the detail of a price list", () => {
  it("shows the data of the list and every validity of each concept, closed and open, with the amounts as the server sent them", async () => {
    await openDetail();

    expect(within(screen.getByRole("navigation", { name: "Vistas del catálogo" })).getByRole("link", { name: "Listas de precios" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByText("Año lectivo 2027")).toBeInTheDocument();
    expect(screen.getByText("Toda la institución")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 4, name: "COL — Colegiatura" })).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Vigencias de Colegiatura" });
    const [, newest, oldest] = within(table).getAllByRole("row");
    expect(within(newest as HTMLElement).getAllByText(/1,650\.00/).length).toBeGreaterThan(0);
    expect(within(newest as HTMLElement).getByText("Abierta")).toBeInTheDocument();
    expect(within(oldest as HTMLElement).getAllByText(/1,500\.00/).length).toBeGreaterThan(0);
    expect(within(oldest as HTMLElement).getByText("Cerrada")).toBeInTheDocument();
    expect(requests().some((sent) => sent.path === `${LISTS}/${LIST_ID}` && sent.headers.get("Authorization") !== null)).toBe(true);
  });

  it("names the modality or the grade of a list that has one", async () => {
    await openDetail(priceListDetail({ id: LIST_ID, name: "2027 — Primero", academicYearId: YEAR.id, scope: "GRADE", scopeRefId: PRIMERO.id, items: [] }));

    expect(screen.getByText("Un grado")).toBeInTheDocument();
    expect(screen.getByText("Primero")).toBeInTheDocument();
  });

  it("shows the empty state of a list with no prices, with the way forward", async () => {
    const user = userEvent.setup();
    await openDetail(priceListDetail({ id: LIST_ID, name: "2027 — Institución", academicYearId: YEAR.id, items: [] }));

    expect(screen.getByText("Esta lista todavía no tiene precios")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Agregar el primer precio" }));

    expect(await screen.findByRole("dialog", { name: "Nuevo precio / nueva vigencia" })).toBeInTheDocument();
  });

  it("shows the loading state, then the error state of a list that fails, and loads it on retry", async () => {
    const user = userEvent.setup();
    routeLookups();
    route("get", `${LISTS}/${LIST_ID}`, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), priceListDetailRecord(LIST));
    renderCatalog(`/catalog/price-lists/${LIST_ID}`);

    expect(await screen.findByText("No se pudo cargar la lista de precios", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("heading", { level: 2, name: "2027 — Institución" })).toBeInTheDocument();
  });

  it("tells a list that does not exist, or that is another institution's, as not found", async () => {
    routeLookups();
    route("get", `${LISTS}/${LIST_ID}`, problem("resource-not-found", 404));
    renderCatalog(`/catalog/price-lists/${LIST_ID}`);

    expect(await screen.findByText("No se pudo cargar la lista de precios", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(admin.problem.notFound)).toBeInTheDocument();
  });

  it("warns, with a retry, when the concepts failed to load, and names them once they load", async () => {
    const user = userEvent.setup();
    route("get", `${ORG}/academic-years`, organizationPage("academic-years", [YEAR]));
    route("get", `${ORG}/modalities`, organizationPage("modalities", [DIURNA]));
    route("get", `${ORG}/grades`, organizationPage("grades", [PRIMERO]));
    route("get", CONCEPTS, problem("internal-error", 500), paymentConceptPage([COLEGIATURA, TRANSPORTE, VIEJO]));
    route("get", `${LISTS}/${LIST_ID}`, priceListDetailRecord(LIST));
    renderCatalog(`/catalog/price-lists/${LIST_ID}`);

    expect(await screen.findByText(/No se pudieron cargar los nombres de los conceptos/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 4, name: "No disponible" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("heading", { level: 4, name: "COL — Colegiatura" }, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByText(/No se pudieron cargar los nombres de los conceptos/)).not.toBeInTheDocument();
  });

  it("goes back to the lists", async () => {
    const user = userEvent.setup();
    const { router } = await openDetail();
    route("get", LISTS, { status: 200, body: { items: [], page: 0, size: 25, total: 0 }, contentType: "application/json" });

    await user.click(within(screen.getByRole("navigation", { name: "Ruta de navegación" })).getByRole("link", { name: "Listas de precios" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/catalog/price-lists");
    });
  });
});

describe("a new price or validity", () => {
  it("offers only the active concepts, sends the amount as the text typed, and shows the new validity from the server's answer", async () => {
    const user = userEvent.setup();
    await openDetail();
    const third = priceListItem({ id: "00000000-0000-4000-8000-0000000000f3", priceListId: LIST_ID, paymentConceptId: TRANSPORTE.id, price: { amount: "300.00", currency: "HNL" } });
    route("post", `${LISTS}/${LIST_ID}/items`, priceListItemRecord(third));
    route("get", `${LISTS}/${LIST_ID}`, priceListDetailRecord({ ...LIST, items: [FIRST, SECOND, third] }));
    const dialog = await openAddDialog(user);
    expect(within(within(dialog).getByLabelText(/Concepto/)).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Elija una opción",
      "Colegiatura",
      "Transporte escolar",
    ]);

    await fillPrice(dialog, user, { concept: TRANSPORTE.id, amount: "300.5", from: "2027-01-01", to: "2027-12-31" });
    await user.click(within(dialog).getByRole("button", { name: "Guardar precio" }));

    expect(await screen.findByText("Se registró el precio de «Transporte escolar» desde el 1 ene 2027.")).toBeInTheDocument();
    expect(bodyOf(posts()[0])).toEqual({
      paymentConceptId: TRANSPORTE.id,
      price: { amount: "300.5", currency: "HNL" },
      validFrom: "2027-01-01",
      validTo: "2027-12-31",
    });
    expect(posts()[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(await screen.findByRole("heading", { level: 4, name: "TRA — Transporte escolar" })).toBeInTheDocument();
  });

  it("reads the list again after the add, because the server closed the previous open validity", async () => {
    const user = userEvent.setup();
    await openDetail(priceListDetail({ ...LIST, items: [{ ...SECOND, validFrom: "2027-01-01" }] }));
    const added = priceListItem({ priceListId: LIST_ID, paymentConceptId: COLEGIATURA.id, price: { amount: "1700.00", currency: "HNL" }, validFrom: "2027-09-01" });
    route("post", `${LISTS}/${LIST_ID}/items`, priceListItemRecord(added));
    route("get", `${LISTS}/${LIST_ID}`, priceListDetailRecord({ ...LIST, items: [{ ...SECOND, validFrom: "2027-01-01", validTo: "2027-08-31" }, added] }));
    const dialog = await openAddDialog(user);
    const before = gets().length;

    await fillPrice(dialog, user, { amount: "1700.00", from: "2027-09-01" });
    await user.click(within(dialog).getByRole("button", { name: "Guardar precio" }));

    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    });
    const table = await screen.findByRole("table", { name: "Vigencias de Colegiatura" });
    await waitFor(() => {
      expect(within(table).getByText("Cerrada")).toBeInTheDocument();
    });
    expect(within(table).getByText("Abierta")).toBeInTheDocument();
  });

  it("refuses an empty concept, amount and start before sending", async () => {
    const user = userEvent.setup();
    await openDetail();
    const dialog = await openAddDialog(user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar precio" }));

    expect(await within(dialog).findByText("Hay 3 campos con errores.")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it.each([
    ["0", "El importe debe ser mayor que cero."],
    ["-5", "El importe debe ser mayor que cero."],
    ["12.345", "hasta dos decimales"],
    ["mil", "hasta dos decimales"],
  ])("refuses the amount %s before sending", async (amount, text) => {
    const user = userEvent.setup();
    await openDetail();
    const dialog = await openAddDialog(user);
    await fillPrice(dialog, user, { amount });

    await user.click(within(dialog).getByRole("button", { name: "Guardar precio" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Importe/)).toHaveAccessibleDescription(new RegExp(text));
    });
    expect(posts()).toHaveLength(0);
  });

  it("refuses a day outside 2000 to 2100 before sending, with its own message", async () => {
    const user = userEvent.setup();
    await openDetail();
    const dialog = await openAddDialog(user);
    await fillPrice(dialog, user, { from: "1999-12-31" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar precio" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Vigente desde/)).toHaveAccessibleDescription(/entre el 1 de enero de 2000 y el 31 de diciembre de 2100/);
    });
    expect(posts()).toHaveLength(0);
  });

  it("refuses an end before the start before sending", async () => {
    const user = userEvent.setup();
    await openDetail();
    const dialog = await openAddDialog(user);
    await fillPrice(dialog, user, { from: "2027-07-01", to: "2027-06-01" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar precio" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Vigente hasta/)).toHaveAccessibleDescription(/no puede ser anterior a la de inicio/);
    });
    expect(posts()).toHaveLength(0);
  });

  it.each([
    ["price-validity-overlap", 409, admin.problem.priceValidityOverlap],
    ["price-list-archived", 409, admin.problem.priceListArchived],
    ["stale-version", 409, admin.problem.staleVersion],
    ["inactive-reference", 409, admin.problem.inactiveReference],
    ["resource-not-found", 404, admin.problem.notFound],
    ["forbidden", 403, admin.problem.forbidden],
  ] as const)("tells %s in its own words, in the dialog, and keeps what was typed so it can be corrected", async (code, status, text) => {
    const user = userEvent.setup();
    await openDetail();
    route("post", `${LISTS}/${LIST_ID}/items`, problem(code, status));
    const dialog = await openAddDialog(user);
    await fillPrice(dialog, user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar precio" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(text);
    expect(within(dialog).getByLabelText(/Importe/)).toHaveValue("1650.00");
    expect(screen.getByRole("dialog", { name: "Nuevo precio / nueva vigencia" })).toBeInTheDocument();
  });

  it("reads the list again when the server says it is out of date (an overlap made by someone else)", async () => {
    const user = userEvent.setup();
    await openDetail();
    route("post", `${LISTS}/${LIST_ID}/items`, problem("price-validity-overlap", 409));
    const dialog = await openAddDialog(user);
    await fillPrice(dialog, user);
    const before = gets().length;

    await user.click(within(dialog).getByRole("button", { name: "Guardar precio" }));

    await within(dialog).findByRole("alert");
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    });
  });

  it("puts the nested field errors of a 400 under the amount and the dates", async () => {
    const user = userEvent.setup();
    await openDetail();
    route(
      "post",
      `${LISTS}/${LIST_ID}/items`,
      problem("validation-failed", 400, { errors: [{ field: "price.amount", reason: "pattern" }, { field: "validFrom", reason: "not-null" }] }),
    );
    const dialog = await openAddDialog(user);
    await fillPrice(dialog, user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar precio" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Importe/)).toHaveAccessibleDescription(/hasta dos decimales/);
    });
  });

  it("warns when there is no active concept to price", async () => {
    const user = userEvent.setup();
    routeLookups([VIEJO]);
    route("get", `${LISTS}/${LIST_ID}`, priceListDetailRecord(LIST));
    renderCatalog(`/catalog/price-lists/${LIST_ID}`);
    await screen.findByRole("heading", { level: 2, name: "2027 — Institución" }, TIMEOUT);

    await user.click(screen.getByRole("button", { name: "Nuevo precio / nueva vigencia" }));

    expect(await screen.findByText(/No hay conceptos de pago activos/)).toBeInTheDocument();
  });

  it("closes the dialog without sending anything on cancel", async () => {
    const user = userEvent.setup();
    await openDetail();
    const dialog = await openAddDialog(user);

    await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(posts()).toHaveLength(0);
  });
});

describe("archiving and reactivating a list", () => {
  it("archives with the version the list was read at, tells it, and then takes no new price", async () => {
    const user = userEvent.setup();
    await openDetail();
    route("put", `${LISTS}/${LIST_ID}`, priceListRecord(priceList({ id: LIST_ID, name: "2027 — Institución", status: "ARCHIVED", version: 4 })));
    route("get", `${LISTS}/${LIST_ID}`, priceListDetailRecord({ ...LIST, status: "ARCHIVED", version: 4 }));

    await user.click(screen.getByRole("button", { name: "Archivar lista 2027 — Institución" }));

    expect(await screen.findByText("Se archivó la lista «2027 — Institución».")).toBeInTheDocument();
    expect(bodyOf(puts()[0])).toEqual({ version: 3, name: "2027 — Institución", status: "ARCHIVED" });
    expect(await screen.findByText(/está archivada y no admite precios nuevos/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Nuevo precio / nueva vigencia" })).toBeDisabled();
  });

  it("reactivates an archived list with its new version", async () => {
    const user = userEvent.setup();
    await openDetail({ ...LIST, status: "ARCHIVED", version: 4 });
    route("put", `${LISTS}/${LIST_ID}`, priceListRecord(priceList({ id: LIST_ID, name: "2027 — Institución", status: "ACTIVE", version: 5 })));
    route("get", `${LISTS}/${LIST_ID}`, priceListDetailRecord({ ...LIST, version: 5 }));

    await user.click(screen.getByRole("button", { name: "Reactivar lista 2027 — Institución" }));

    expect(await screen.findByText("Se reactivó la lista «2027 — Institución».")).toBeInTheDocument();
    expect(bodyOf(puts()[0])).toEqual({ version: 4, name: "2027 — Institución", status: "ACTIVE" });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Nuevo precio / nueva vigencia" })).toBeEnabled();
    });
  });

  it("tells the list changed, reads it again, and sends the new version on the next try, when the version was stale", async () => {
    const user = userEvent.setup();
    await openDetail();
    route("put", `${LISTS}/${LIST_ID}`, problem("stale-version", 409), priceListRecord(priceList({ id: LIST_ID, name: "2027 — Institución", status: "ARCHIVED", version: 8 })));
    route("get", `${LISTS}/${LIST_ID}`, priceListDetailRecord({ ...LIST, version: 7 }));
    const before = gets().length;

    await user.click(screen.getByRole("button", { name: "Archivar lista 2027 — Institución" }));

    expect(await screen.findByText(admin.problem.staleVersion)).toBeInTheDocument();
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    });
    await user.click(screen.getByRole("button", { name: "Archivar lista 2027 — Institución" }));
    await waitFor(() => {
      expect(puts()).toHaveLength(2);
    });
    expect(bodyOf(puts()[0])).toMatchObject({ version: 3 });
    expect(bodyOf(puts()[1])).toMatchObject({ version: 7 });
  });

  it("tells a refusal that is not about the version, with the trace id, and changes nothing on screen", async () => {
    const user = userEvent.setup();
    await openDetail();
    route("put", `${LISTS}/${LIST_ID}`, problem("forbidden", 403, { traceId: "01J8Z4K2QF9P0X" }));

    await user.click(screen.getByRole("button", { name: "Archivar lista 2027 — Institución" }));

    expect(await screen.findByText(admin.problem.forbidden)).toBeInTheDocument();
    expect(screen.getByText("Identificador para soporte: 01J8Z4K2QF9P0X")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Nuevo precio / nueva vigencia" })).toBeEnabled();
  });

  it("sends one request even if the button is pressed twice before the answer", async () => {
    await openDetail();
    route("put", `${LISTS}/${LIST_ID}`, priceListRecord(priceList({ id: LIST_ID, name: "2027 — Institución", status: "ARCHIVED", version: 4 })));
    const button = screen.getByRole("button", { name: "Archivar lista 2027 — Institución" });

    fireEvent.click(button);
    fireEvent.click(button);

    await screen.findByText("Se archivó la lista «2027 — Institución».");
    expect(puts()).toHaveLength(1);
  });
});

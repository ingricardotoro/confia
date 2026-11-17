import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import {
  academicYear,
  chargePlan,
  chargePlanDetail,
  chargePlanDetailRecord,
  chargePlanItem,
  chargePlanItemRecord,
  chargePlanRecord,
  grade,
  modality,
  noContent,
  organizationPage,
  paymentConcept,
  paymentConceptPage,
  problem,
  section,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCharges } from "../../test/render-charges.tsx";

// The detail of a plan over the real route, the real query layer and the real transport: its items, the new item with its exact
// body, the removal, the activation and the archive each behind a confirmation and sent with the version, the edit, the
// refusals (no items, no longer editable, repeated concept, stale version) and the states of the load.
installMswServer();

const PLANS = "/api/v1/charges/plans";
const CONCEPTS = "/api/v1/catalog/payment-concepts";
const ORG = "/api/v1/organization";
const TIMEOUT = { timeout: 10_000 };

const YEAR = academicYear({ id: "11111111-1111-4111-8111-111111111111", name: "Año lectivo 2027", status: "ACTIVE" });
const DIURNA = modality({ id: "66666666-6666-4666-8666-666666666666", name: "Diurna" });
const PRIMERO = grade({ id: "33333333-3333-4333-8333-333333333333", modalityId: DIURNA.id, name: "Primero" });
const SECCION_A = section({ id: "44444444-4444-4444-8444-444444444444", academicYearId: YEAR.id, gradeId: PRIMERO.id, name: "A" });
const COLEGIATURA = paymentConcept({ id: "00000000-0000-4000-8000-000000007001", code: "COL", name: "Colegiatura" });
const MATRICULA = paymentConcept({ id: "00000000-0000-4000-8000-000000007002", code: "MAT", name: "Matrícula anual" });
const TRANSPORTE = paymentConcept({ id: "00000000-0000-4000-8000-000000007003", code: "TRA", name: "Transporte escolar" });
const VIEJO = paymentConcept({ id: "00000000-0000-4000-8000-000000007004", code: "OTR", name: "Otros ingresos", isActive: false });
const PLAN_ID = "00000000-0000-4000-8000-0000000000e1";

const ITEM_COL = chargePlanItem({ id: "00000000-0000-4000-8000-0000000000f1", planId: PLAN_ID, paymentConceptId: COLEGIATURA.id, periodsCount: 10, firstPeriodOn: "2027-02-01" });
const ITEM_MAT = chargePlanItem({ id: "00000000-0000-4000-8000-0000000000f2", planId: PLAN_ID, paymentConceptId: MATRICULA.id, periodsCount: 1, firstPeriodOn: "2027-01-01" });

const plan = (override: Parameters<typeof chargePlanDetail>[0] = {}) =>
  chargePlanDetail({
    id: PLAN_ID,
    name: "Primaria 2027",
    academicYearId: YEAR.id,
    targetScope: "GRADE",
    targetRefId: PRIMERO.id,
    frequency: "MONTHLY",
    dueDayOfMonth: 5,
    status: "DRAFT",
    version: 3,
    items: [ITEM_COL, ITEM_MAT],
    ...override,
  });

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === `${PLANS}/${PLAN_ID}`);
const posts = () => requests().filter((sent) => sent.method === "POST");
const puts = () => requests().filter((sent) => sent.method === "PUT");
const deletes = () => requests().filter((sent) => sent.method === "DELETE");
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

function routeLookups(concepts = [COLEGIATURA, MATRICULA, TRANSPORTE, VIEJO]) {
  route("get", `${ORG}/academic-years`, organizationPage("academic-years", [YEAR]));
  route("get", `${ORG}/modalities`, organizationPage("modalities", [DIURNA]));
  route("get", `${ORG}/grades`, organizationPage("grades", [PRIMERO]));
  route("get", `${ORG}/sections`, organizationPage("sections", [SECCION_A]));
  route("get", CONCEPTS, paymentConceptPage(concepts));
}

async function openDetail(detail = plan()) {
  routeLookups();
  route("get", `${PLANS}/${PLAN_ID}`, chargePlanDetailRecord(detail));
  const rendered = renderCharges(`/charges/plans/${PLAN_ID}`);
  await screen.findByRole("heading", { level: 2, name: detail.name }, TIMEOUT);
  return rendered;
}

const user = () => userEvent.setup();
const dialogNamed = (name: string | RegExp) => screen.findByRole("dialog", { name });

describe("the detail of a plan", () => {
  it("shows the data of the plan and its items with the concepts named and the first period written for a person", async () => {
    await openDetail();

    expect(screen.getByRole("heading", { level: 1, name: "Cobros" })).toBeInTheDocument();
    const summary = within(screen.getByLabelText("Datos del plan"));
    expect(summary.getByText("Año lectivo 2027")).toBeInTheDocument();
    expect(summary.getByText("Un grado")).toBeInTheDocument();
    expect(summary.getByText("Primero")).toBeInTheDocument();
    expect(summary.getByText("Mensual")).toBeInTheDocument();
    expect(summary.getByText("Día 5")).toBeInTheDocument();
    expect(summary.getByText("Borrador")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Conceptos del plan Primaria 2027" });
    const colegiatura = within(within(table).getByRole("row", { name: /COL — Colegiatura/ }));
    expect(colegiatura.getByText("10")).toBeInTheDocument();
    expect(colegiatura.getByText(/feb/i)).toBeInTheDocument();
    expect(within(table).getByRole("row", { name: /MAT — Matrícula anual/ })).toBeInTheDocument();
    expect(requests().some((sent) => sent.path === `${PLANS}/${PLAN_ID}` && sent.headers.get("Authorization") !== null)).toBe(true);
  });

  it("names the section a plan covers with its grade and its year", async () => {
    await openDetail(plan({ targetScope: "SECTION", targetRefId: SECCION_A.id }));

    expect(screen.getByText("Primero A (Año lectivo 2027)")).toBeInTheDocument();
    expect(screen.getByText("Una sección")).toBeInTheDocument();
  });

  it("shows the loading state, then the error state of a plan that fails, and loads it on retry", async () => {
    const u = user();
    routeLookups();
    route("get", `${PLANS}/${PLAN_ID}`, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), chargePlanDetailRecord(plan()));
    renderCharges(`/charges/plans/${PLAN_ID}`);

    expect(await screen.findByText("No se pudo cargar el plan de cobro", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await u.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("heading", { level: 2, name: "Primaria 2027" })).toBeInTheDocument();
  });

  it("tells a plan that does not exist, or that is another institution's, as not found", async () => {
    routeLookups();
    route("get", `${PLANS}/${PLAN_ID}`, problem("resource-not-found", 404));
    renderCharges(`/charges/plans/${PLAN_ID}`);

    expect(await screen.findByText("No se pudo cargar el plan de cobro", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/El registro ya no existe o no está disponible/)).toBeInTheDocument();
  });

  it("warns, with a retry, when the concepts failed to load, and names them once they load", async () => {
    const u = user();
    route("get", `${ORG}/academic-years`, organizationPage("academic-years", [YEAR]));
    route("get", `${ORG}/modalities`, organizationPage("modalities", [DIURNA]));
    route("get", `${ORG}/grades`, organizationPage("grades", [PRIMERO]));
    route("get", `${ORG}/sections`, organizationPage("sections", [SECCION_A]));
    route("get", CONCEPTS, problem("internal-error", 500), paymentConceptPage([COLEGIATURA, MATRICULA]));
    route("get", `${PLANS}/${PLAN_ID}`, chargePlanDetailRecord(plan()));
    renderCharges(`/charges/plans/${PLAN_ID}`);

    expect(await screen.findByText(/No se pudieron cargar los nombres de los conceptos/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getAllByText("No disponible").length).toBeGreaterThan(0);
    await u.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /COL — Colegiatura/ }, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByText(/No se pudieron cargar los nombres de los conceptos/)).not.toBeInTheDocument();
  });

  it("goes back to the plans from the breadcrumb", async () => {
    const u = user();
    const { router } = await openDetail();
    route("get", PLANS, { status: 200, body: { items: [], page: 0, size: 25, total: 0 }, contentType: "application/json" });

    await u.click(within(screen.getByRole("navigation", { name: "Ruta de navegación" })).getByRole("link", { name: "Planes de cobro" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/charges/plans");
    });
  });
});

describe("a new item", () => {
  async function openAddDialog(u: ReturnType<typeof userEvent.setup>) {
    await u.click(screen.getByRole("button", { name: "Agregar concepto" }));
    return dialogNamed("Agregar concepto");
  }

  async function fillItem(dialog: HTMLElement, u: ReturnType<typeof userEvent.setup>, values: { concept?: string; periods?: string; first?: string } = {}) {
    await u.selectOptions(within(dialog).getByLabelText(/Concepto/), values.concept ?? TRANSPORTE.id);
    await u.type(within(dialog).getByLabelText(/Cantidad de períodos/), values.periods ?? "10");
    await u.type(within(dialog).getByLabelText(/Primer día del primer período/), values.first ?? "2027-02-01");
  }

  it("offers only the active concepts the plan does not have, sends the exact body, and shows the item from the server's answer", async () => {
    const u = user();
    await openDetail();
    const added = chargePlanItem({ id: "00000000-0000-4000-8000-0000000000f3", planId: PLAN_ID, paymentConceptId: TRANSPORTE.id, periodsCount: 10, firstPeriodOn: "2027-02-01" });
    route("post", `${PLANS}/${PLAN_ID}/items`, chargePlanItemRecord(added));
    route("get", `${PLANS}/${PLAN_ID}`, chargePlanDetailRecord(plan({ items: [ITEM_COL, ITEM_MAT, added] })));
    const dialog = await openAddDialog(u);
    expect(within(within(dialog).getByLabelText(/Concepto/)).getAllByRole("option").map((option) => option.textContent)).toEqual(["Elija una opción", "Transporte escolar"]);

    await fillItem(dialog, u);
    await u.click(within(dialog).getByRole("button", { name: "Agregar concepto" }));

    expect(await screen.findByText("Se agregó «Transporte escolar» al plan.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(bodyOf(posts()[0])).toEqual({ paymentConceptId: TRANSPORTE.id, periodsCount: 10, firstPeriodOn: "2027-02-01" });
    expect(posts()[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(await screen.findByRole("row", { name: /TRA — Transporte escolar/ })).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("refuses, before sending, a first day that is not the first of a month, with a message that says what to do", async () => {
    const u = user();
    await openDetail();
    const dialog = await openAddDialog(u);

    await fillItem(dialog, u, { first: "2027-02-15" });
    await u.click(within(dialog).getByRole("button", { name: "Agregar concepto" }));

    expect((await within(dialog).findAllByText("Elija el primer día de un mes, por ejemplo el 1 de febrero. Los períodos siempre empiezan el día 1.")).length).toBeGreaterThan(0);
    expect(posts()).toHaveLength(0);
  });

  it.each(["0", "37"])("refuses %s periods before sending", async (periods) => {
    const u = user();
    await openDetail();
    const dialog = await openAddDialog(u);

    await fillItem(dialog, u, { periods });
    await u.click(within(dialog).getByRole("button", { name: "Agregar concepto" }));

    expect((await within(dialog).findAllByText("Escriba un número entero de períodos entre 1 y 36.")).length).toBeGreaterThan(0);
    expect(posts()).toHaveLength(0);
  });

  it("keeps what was typed and tells a concept that is already in the plan, and reads the plan again", async () => {
    const u = user();
    await openDetail();
    route("post", `${PLANS}/${PLAN_ID}/items`, problem("duplicate-plan-item", 409));
    const dialog = await openAddDialog(u);
    const before = gets().length;

    await fillItem(dialog, u);
    await u.click(within(dialog).getByRole("button", { name: "Agregar concepto" }));

    expect(await within(dialog).findByText(/Ese concepto ya forma parte del plan/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Cantidad de períodos/)).toHaveValue("10");
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    });
  });

  it("tells a plan that stopped being a draft under the person", async () => {
    const u = user();
    await openDetail();
    route("post", `${PLANS}/${PLAN_ID}/items`, problem("plan-not-editable", 409));
    const dialog = await openAddDialog(u);

    await fillItem(dialog, u);
    await u.click(within(dialog).getByRole("button", { name: "Agregar concepto" }));

    expect(await within(dialog).findByText(/El plan ya no admite este cambio/, undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("says there is nothing to add when every active concept is already in the plan", async () => {
    const u = user();
    routeLookups([COLEGIATURA, MATRICULA, VIEJO]);
    route("get", `${PLANS}/${PLAN_ID}`, chargePlanDetailRecord(plan()));
    renderCharges(`/charges/plans/${PLAN_ID}`);
    await screen.findByRole("heading", { level: 2, name: "Primaria 2027" }, TIMEOUT);

    await u.click(screen.getByRole("button", { name: "Agregar concepto" }));

    expect(await screen.findByText(/No hay conceptos de pago activos para agregar/, undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("offers the first item from the empty state of a draft", async () => {
    const u = user();
    await openDetail(plan({ items: [] }));

    expect(screen.getByText("Este plan todavía no tiene conceptos")).toBeInTheDocument();
    await u.click(screen.getByRole("button", { name: "Agregar el primer concepto" }));

    expect(await dialogNamed("Agregar concepto")).toBeInTheDocument();
  });
});

describe("removing an item", () => {
  it("asks first, sends nothing on cancel, and on confirm deletes that item, reads the plan again and says so", async () => {
    const u = user();
    await openDetail();
    route("delete", `${PLANS}/${PLAN_ID}/items/${ITEM_MAT.id}`, noContent());
    route("get", `${PLANS}/${PLAN_ID}`, chargePlanDetailRecord(plan({ items: [ITEM_COL] })));

    await u.click(screen.getByRole("button", { name: "Quitar Matrícula anual del plan" }));
    let dialog = await dialogNamed("Quitar «Matrícula anual» del plan");
    expect(dialog).toHaveAccessibleDescription(/deja de formar parte de este borrador/);
    await u.click(within(dialog).getByRole("button", { name: "Cancelar" }));
    expect(deletes()).toHaveLength(0);

    await u.click(screen.getByRole("button", { name: "Quitar Matrícula anual del plan" }));
    dialog = await dialogNamed("Quitar «Matrícula anual» del plan");
    await u.click(within(dialog).getByRole("button", { name: "Quitar concepto" }));

    expect(await screen.findByText("Se quitó «Matrícula anual» del plan.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(deletes()).toHaveLength(1);
    expect(deletes()[0]?.path).toBe(`${PLANS}/${PLAN_ID}/items/${ITEM_MAT.id}`);
    await waitFor(() => {
      expect(screen.queryByRole("row", { name: /MAT — Matrícula anual/ })).not.toBeInTheDocument();
    });
  });

  it("tells a plan that is no longer a draft, closes the question and reads the plan again", async () => {
    const u = user();
    await openDetail();
    route("delete", `${PLANS}/${PLAN_ID}/items/${ITEM_MAT.id}`, problem("plan-not-editable", 409, { traceId: "01J8Z4K2QF9P0X" }));
    const before = gets().length;

    await u.click(screen.getByRole("button", { name: "Quitar Matrícula anual del plan" }));
    await u.click(within(await dialogNamed(/Quitar/)).getByRole("button", { name: "Quitar concepto" }));

    expect(await screen.findByText(/El plan ya no admite este cambio/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    });
  });
});

describe("activating and archiving", () => {
  it("explains that activating freezes the plan and sends nothing on cancel", async () => {
    const u = user();
    await openDetail();

    await u.click(screen.getByRole("button", { name: "Activar plan Primaria 2027" }));
    const dialog = await dialogNamed("Activar el plan «Primaria 2027»");

    expect(dialog).toHaveAccessibleDescription(/queda congelado: ya no podrá agregar ni quitar conceptos/);
    await u.click(within(dialog).getByRole("button", { name: "Cancelar" }));
    expect(puts()).toHaveLength(0);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("activates with the version the plan was read at, and shows the plan frozen from the server's answer", async () => {
    const u = user();
    await openDetail();
    route("put", `${PLANS}/${PLAN_ID}`, chargePlanRecord(chargePlan({ id: PLAN_ID, name: "Primaria 2027", status: "ACTIVE", version: 4 })));
    route("get", `${PLANS}/${PLAN_ID}`, chargePlanDetailRecord(plan({ status: "ACTIVE", version: 4 })));

    await u.click(screen.getByRole("button", { name: "Activar plan Primaria 2027" }));
    await u.click(within(await dialogNamed("Activar el plan «Primaria 2027»")).getByRole("button", { name: "Activar plan" }));

    expect(await screen.findByText("Se activó el plan «Primaria 2027». Ya no admite cambios en sus conceptos.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
    expect(bodyOf(puts()[0])).toEqual({ version: 3, name: "Primaria 2027", frequency: "MONTHLY", dueDayOfMonth: 5, status: "ACTIVE" });
    expect(puts()[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(await screen.findByRole("button", { name: "Archivar plan Primaria 2027" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Agregar concepto" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Quitar/ })).not.toBeInTheDocument();
  });

  it("tells a plan with no items that it cannot be activated", async () => {
    const u = user();
    await openDetail(plan({ items: [] }));
    route("put", `${PLANS}/${PLAN_ID}`, problem("plan-without-items", 409));

    await u.click(screen.getByRole("button", { name: "Activar plan Primaria 2027" }));
    await u.click(within(await dialogNamed("Activar el plan «Primaria 2027»")).getByRole("button", { name: "Activar plan" }));

    expect(await screen.findByText(/El plan no tiene conceptos, por lo que no se puede activar/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText("Borrador", { selector: "dd *" })).toBeInTheDocument();
  });

  it("tells a stale version, reads the plan again and leaves the plan as the server holds it", async () => {
    const u = user();
    await openDetail();
    route("put", `${PLANS}/${PLAN_ID}`, problem("stale-version", 409));
    route("get", `${PLANS}/${PLAN_ID}`, chargePlanDetailRecord(plan({ name: "Primaria 2027 (corregido)", version: 4 })));

    await u.click(screen.getByRole("button", { name: "Activar plan Primaria 2027" }));
    await u.click(within(await dialogNamed("Activar el plan «Primaria 2027»")).getByRole("button", { name: "Activar plan" }));

    expect(await screen.findByText(/El registro cambió desde que lo abrió/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { level: 2, name: "Primaria 2027 (corregido)" }, TIMEOUT)).toBeInTheDocument();
  });

  it("archives an active plan with the version it was read at, after an explicit confirmation", async () => {
    const u = user();
    await openDetail(plan({ status: "ACTIVE", version: 4 }));
    route("put", `${PLANS}/${PLAN_ID}`, chargePlanRecord(chargePlan({ id: PLAN_ID, name: "Primaria 2027", status: "ARCHIVED", version: 5 })));
    route("get", `${PLANS}/${PLAN_ID}`, chargePlanDetailRecord(plan({ status: "ARCHIVED", version: 5 })));

    await u.click(screen.getByRole("button", { name: "Archivar plan Primaria 2027" }));
    const dialog = await dialogNamed("Archivar el plan «Primaria 2027»");
    expect(dialog).toHaveAccessibleDescription(/ya no se puede cambiar ni volver a activar/);
    await u.click(within(dialog).getByRole("button", { name: "Archivar plan" }));

    expect(await screen.findByText("Se archivó el plan «Primaria 2027».", undefined, TIMEOUT)).toBeInTheDocument();
    expect(bodyOf(puts()[0])).toEqual({ version: 4, name: "Primaria 2027", frequency: "MONTHLY", dueDayOfMonth: 5, status: "ARCHIVED" });
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: /Editar|Activar|Archivar|Agregar|Quitar/ })).not.toBeInTheDocument();
    });
    expect(screen.getByText(/Este plan está archivado/)).toBeInTheDocument();
  });

  it("sends the activation once, however many times the confirmation is pressed while it is in flight", async () => {
    const u = user();
    await openDetail();
    route("put", `${PLANS}/${PLAN_ID}`, chargePlanRecord(chargePlan({ id: PLAN_ID, name: "Primaria 2027", status: "ACTIVE", version: 4 })));
    route("get", `${PLANS}/${PLAN_ID}`, chargePlanDetailRecord(plan({ status: "ACTIVE", version: 4 })));

    await u.click(screen.getByRole("button", { name: "Activar plan Primaria 2027" }));
    const confirm = within(await dialogNamed("Activar el plan «Primaria 2027»")).getByRole("button", { name: "Activar plan" });
    await u.dblClick(confirm);

    await screen.findByText(/Se activó el plan/, undefined, TIMEOUT);
    expect(puts()).toHaveLength(1);
  });
});

describe("editing a plan", () => {
  it("starts from the plan, sends the new name, frequency and due day with the status and the version, and shows the answer", async () => {
    const u = user();
    await openDetail();
    route("put", `${PLANS}/${PLAN_ID}`, chargePlanRecord(chargePlan({ id: PLAN_ID, name: "Primaria 2027 A", frequency: "BIMONTHLY", dueDayOfMonth: 10, version: 4 })));
    route("get", `${PLANS}/${PLAN_ID}`, chargePlanDetailRecord(plan({ name: "Primaria 2027 A", frequency: "BIMONTHLY", dueDayOfMonth: 10, version: 4 })));

    await u.click(screen.getByRole("button", { name: "Editar plan Primaria 2027" }));
    const dialog = await dialogNamed("Editar plan de cobro");
    expect(within(dialog).getByLabelText(/Nombre/)).toHaveValue("Primaria 2027");
    expect(within(dialog).getByLabelText(/Día de vencimiento/)).toHaveValue("5");
    await u.clear(within(dialog).getByLabelText(/Nombre/));
    await u.type(within(dialog).getByLabelText(/Nombre/), "Primaria 2027 A");
    await u.selectOptions(within(dialog).getByLabelText(/Periodicidad/), "BIMONTHLY");
    await u.clear(within(dialog).getByLabelText(/Día de vencimiento/));
    await u.type(within(dialog).getByLabelText(/Día de vencimiento/), "10");
    await u.click(within(dialog).getByRole("button", { name: "Guardar cambios" }));

    expect(await screen.findByText("Se guardaron los cambios del plan «Primaria 2027 A».", undefined, TIMEOUT)).toBeInTheDocument();
    expect(bodyOf(puts()[0])).toEqual({ version: 3, name: "Primaria 2027 A", frequency: "BIMONTHLY", dueDayOfMonth: 10, status: "DRAFT" });
    expect(await screen.findByRole("heading", { level: 2, name: "Primaria 2027 A" })).toBeInTheDocument();
  });

  it("refuses a due day of 0, 29 or 31 before sending", async () => {
    const u = user();
    await openDetail();

    await u.click(screen.getByRole("button", { name: "Editar plan Primaria 2027" }));
    const dialog = await dialogNamed("Editar plan de cobro");
    await u.clear(within(dialog).getByLabelText(/Día de vencimiento/));
    await u.type(within(dialog).getByLabelText(/Día de vencimiento/), "31");
    await u.click(within(dialog).getByRole("button", { name: "Guardar cambios" }));

    expect((await within(dialog).findAllByText("Escriba un día del mes entre 1 y 28, para que exista en todos los meses.")).length).toBeGreaterThan(0);
    expect(puts()).toHaveLength(0);
  });

  it("freezes an active plan: only the name can change, the frequency and the due day are shown and disabled", async () => {
    const u = user();
    await openDetail(plan({ status: "ACTIVE", version: 4 }));

    await u.click(screen.getByRole("button", { name: "Editar plan Primaria 2027" }));
    const dialog = await dialogNamed("Editar plan de cobro");

    expect(dialog).toHaveAccessibleDescription(/Un plan activo está congelado/);
    expect(within(dialog).getByLabelText(/Nombre/)).toBeEnabled();
    expect(within(dialog).getByLabelText(/Periodicidad/)).toBeDisabled();
    expect(within(dialog).getByLabelText(/Día de vencimiento/)).toBeDisabled();
  });

  it("keeps the dialog open with the message when the plan is no longer editable", async () => {
    const u = user();
    await openDetail();
    route("put", `${PLANS}/${PLAN_ID}`, problem("plan-not-editable", 409));

    await u.click(screen.getByRole("button", { name: "Editar plan Primaria 2027" }));
    const dialog = await dialogNamed("Editar plan de cobro");
    await u.click(within(dialog).getByRole("button", { name: "Guardar cambios" }));

    expect(await within(dialog).findByText(/El plan ya no admite este cambio/, undefined, TIMEOUT)).toBeInTheDocument();
  });
});

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { academicYear, chargePlan, chargePlanPage, chargePlanRecord, grade, modality, organizationPage, problem, section } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCharges } from "../../test/render-charges.tsx";

// The charge plans over the real route, the real query layer and the real transport: the list as the server serves it (page,
// order and filters on the server and in the address), the link to the detail, the new plan dialog with its references (which
// follow the scope), the refusals, and the states of the load.
installMswServer();

const PLANS = "/api/v1/charges/plans";
const ORG = "/api/v1/organization";
const TIMEOUT = { timeout: 10_000 };

const YEAR = academicYear({ id: "11111111-1111-4111-8111-111111111111", name: "Año lectivo 2027", status: "ACTIVE" });
const DIURNA = modality({ id: "66666666-6666-4666-8666-666666666666", name: "Diurna" });
const PRIMERO = grade({ id: "33333333-3333-4333-8333-333333333333", modalityId: DIURNA.id, name: "Primero" });
const SECCION_A = section({ id: "44444444-4444-4444-8444-444444444444", academicYearId: YEAR.id, gradeId: PRIMERO.id, name: "A" });
const GRADE_PLAN = chargePlan({
  id: "00000000-0000-4000-8000-0000000000e1",
  name: "Primaria 2027",
  academicYearId: YEAR.id,
  targetScope: "GRADE",
  targetRefId: PRIMERO.id,
  status: "ACTIVE",
});
const SECTION_PLAN = chargePlan({
  id: "00000000-0000-4000-8000-0000000000e2",
  name: "Sección A 2027",
  academicYearId: YEAR.id,
  targetScope: "SECTION",
  targetRefId: SECCION_A.id,
  frequency: "ONE_TIME",
  status: "ARCHIVED",
});

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === PLANS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const posts = () => requests().filter((sent) => sent.method === "POST");

function routeLookups() {
  route("get", `${ORG}/academic-years`, organizationPage("academic-years", [YEAR]));
  route("get", `${ORG}/modalities`, organizationPage("modalities", [DIURNA]));
  route("get", `${ORG}/grades`, organizationPage("grades", [PRIMERO]));
  route("get", `${ORG}/sections`, organizationPage("sections", [SECCION_A]));
}

async function openList(rows: unknown[] = [GRADE_PLAN, SECTION_PLAN], path = "/charges/plans") {
  routeLookups();
  route("get", PLANS, chargePlanPage(rows));
  const rendered = renderCharges(path);
  const table = await screen.findByRole("table", { name: "Planes de cobro de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

describe("the list of plans", () => {
  it("is the view of the charges area, shows each plan in text with its year and target named, and asks for the first page", async () => {
    await openList();

    expect(screen.getByRole("heading", { level: 1, name: "Cobros" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Planes de cobro" })).toBeInTheDocument();
    const first = within(screen.getByRole("row", { name: /Primaria 2027/ }));
    expect(first.getByText("Año lectivo 2027")).toBeInTheDocument();
    expect(first.getByText("Un grado")).toBeInTheDocument();
    expect(first.getByText("Primero")).toBeInTheDocument();
    expect(first.getByText("Activo")).toBeInTheDocument();
    const second = within(screen.getByRole("row", { name: /Sección A 2027/ }));
    expect(second.getByText("Primero A (Año lectivo 2027)")).toBeInTheDocument();
    expect(second.getByText("Archivado")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("academicYearId")).toBe(false);
  });

  it("leads the address of the area to the plans", async () => {
    routeLookups();
    route("get", PLANS, chargePlanPage([GRADE_PLAN]));
    const { router } = renderCharges("/charges");

    await screen.findByRole("table", { name: "Planes de cobro de la institución" }, TIMEOUT);

    expect(router.state.location.pathname).toBe("/charges/plans");
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
    await user.selectOptions(filters.getByLabelText("Ámbito"), "SECTION");
    await user.selectOptions(filters.getByLabelText("Estado"), "ARCHIVED");

    await waitFor(() => {
      expect(lastQuery().get("status")).toBe("ARCHIVED");
    });
    expect(lastQuery().get("targetScope")).toBe("SECTION");
    expect(lastQuery().get("academicYearId")).toBe(YEAR.id);
    expect(router.state.location.searchStr).toContain("targetScope=SECTION");
    expect(router.state.location.searchStr).toContain("status=ARCHIVED");
  });

  it("opens a shared link with its filters applied, and drops a filter that is not valid", async () => {
    await openList([GRADE_PLAN], `/charges/plans?targetScope=GRADE&status=ACTIVE&academicYearId=${YEAR.id}&sort=createdAt:desc`);

    expect(lastQuery().get("targetScope")).toBe("GRADE");
    expect(lastQuery().get("status")).toBe("ACTIVE");
    expect(lastQuery().get("academicYearId")).toBe(YEAR.id);
    expect(lastQuery().get("sort")).toBe("createdAt,desc");
    expect(screen.getByLabelText("Ámbito", { selector: "select" })).toHaveValue("GRADE");
  });

  it("ignores a status the API does not know instead of breaking", async () => {
    await openList([GRADE_PLAN], "/charges/plans?status=GONE&page=0");

    expect(lastQuery().has("status")).toBe(false);
    expect(lastQuery().get("page")).toBe("0");
  });

  it("opens the detail of a plan from its name", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    route("get", `${PLANS}/${SECTION_PLAN.id}`, { status: 200, body: { ...SECTION_PLAN, items: [] }, contentType: "application/json" });

    await user.click(screen.getByRole("link", { name: "Sección A 2027" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/charges/plans/${SECTION_PLAN.id}`);
    });
  });

  it("shows the empty state with the way forward", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay planes de cobro")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear el primer plan" })).toBeInTheDocument();
  });

  it("shows no matches, not the empty state, when the filters leave nothing, and clears them", async () => {
    const user = userEvent.setup();
    await openList([], "/charges/plans?status=ARCHIVED");

    expect(screen.getByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    route("get", PLANS, chargePlanPage([GRADE_PLAN]));
    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));

    await waitFor(() => {
      expect(lastQuery().has("status")).toBe(false);
    });
    expect(await screen.findByRole("row", { name: /Primaria 2027/ })).toBeInTheDocument();
  });

  it("shows the error state and loads the list on retry", async () => {
    const user = userEvent.setup();
    routeLookups();
    route("get", PLANS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), chargePlanPage([GRADE_PLAN]));
    renderCharges("/charges/plans");

    expect(await screen.findByText("No se pudieron cargar los planes de cobro", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /Primaria 2027/ })).toBeInTheDocument();
  });

  it("warns, with a retry, when the options of the sections failed to load", async () => {
    const user = userEvent.setup();
    route("get", `${ORG}/academic-years`, organizationPage("academic-years", [YEAR]));
    route("get", `${ORG}/modalities`, organizationPage("modalities", [DIURNA]));
    route("get", `${ORG}/grades`, organizationPage("grades", [PRIMERO]));
    route("get", `${ORG}/sections`, problem("internal-error", 500), organizationPage("sections", [SECCION_A]));
    route("get", PLANS, chargePlanPage([GRADE_PLAN]));
    renderCharges("/charges/plans");

    expect(await screen.findByText("No se pudieron cargar las opciones de los filtros y de los formularios.", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    await waitFor(() => {
      expect(screen.queryByText("No se pudieron cargar las opciones de los filtros y de los formularios.")).not.toBeInTheDocument();
    }, TIMEOUT);
  });
});

describe("creating a plan", () => {
  async function openDialog(user: ReturnType<typeof userEvent.setup>, rows: unknown[] = []) {
    await openList(rows);
    await user.click(screen.getByRole("button", { name: "Nuevo plan de cobro" }));
    return screen.findByRole("dialog", { name: "Nuevo plan de cobro" });
  }

  it("creates a draft with the exact body, closes, says so and lists the plan again", async () => {
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    const created = chargePlan({ id: "00000000-0000-4000-8000-0000000000e9", name: "Primaria 2027", academicYearId: YEAR.id, targetRefId: PRIMERO.id });
    route("post", PLANS, chargePlanRecord(created, 201));
    route("get", PLANS, chargePlanPage([created]));

    await user.type(within(dialog).getByLabelText(/Nombre/), "  Primaria 2027  ");
    await user.selectOptions(within(dialog).getByLabelText(/Año lectivo/), YEAR.id);
    await user.selectOptions(within(dialog).getByLabelText(/Grado/), PRIMERO.id);
    await user.type(within(dialog).getByLabelText(/Día de vencimiento/), "5");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Primaria 2027».", undefined, TIMEOUT)).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(JSON.parse(posts()[0]?.body ?? "{}")).toEqual({
      academicYearId: YEAR.id,
      name: "Primaria 2027",
      targetScope: "GRADE",
      targetRefId: PRIMERO.id,
      frequency: "MONTHLY",
      dueDayOfMonth: 5,
    });
    expect(posts()[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(await screen.findByRole("row", { name: /Primaria 2027/ })).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("offers the grades, the modalities or the sections as the scope says, and forgets the reference when the scope changes", async () => {
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    const optionsOf = (label: RegExp) => within(within(dialog).getByLabelText(label)).getAllByRole("option").map((option) => option.textContent);

    expect(optionsOf(/Grado/)).toEqual(["Elija una opción", "Primero"]);
    await user.selectOptions(within(dialog).getByLabelText(/Grado/), PRIMERO.id);
    await user.selectOptions(within(dialog).getByLabelText(/Ámbito/), "MODALITY");

    expect(within(dialog).queryByLabelText(/Grado/)).not.toBeInTheDocument();
    expect(optionsOf(/Modalidad/)).toEqual(["Elija una opción", "Diurna"]);
    expect(within(dialog).getByLabelText(/Modalidad/)).toHaveValue("");
    await user.selectOptions(within(dialog).getByLabelText(/Ámbito/), "SECTION");
    expect(optionsOf(/Sección/)).toEqual(["Elija una opción", "Primero A (Año lectivo 2027)"]);
    expect(within(dialog).getByLabelText(/Sección/)).toHaveValue("");
  });

  it("refuses, before sending, what the form can tell: blank fields, and a due day of 0, 29 or 31", async () => {
    const user = userEvent.setup();
    const dialog = await openDialog(user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect((await within(dialog).findAllByText("Este dato es obligatorio.")).length).toBeGreaterThanOrEqual(4);

    await user.type(within(dialog).getByLabelText(/Día de vencimiento/), "29");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(await within(dialog).findAllByText("Escriba un día del mes entre 1 y 28, para que exista en todos los meses.")).not.toHaveLength(0);
    expect(posts()).toHaveLength(0);
  });

  it("keeps the dialog open with a message when the server refuses (the year or the target is not the institution's)", async () => {
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    route("post", PLANS, problem("resource-not-found", 404, { traceId: "01J8Z4K2QF9P0X" }));

    await user.type(within(dialog).getByLabelText(/Nombre/), "Primaria 2027");
    await user.selectOptions(within(dialog).getByLabelText(/Año lectivo/), YEAR.id);
    await user.selectOptions(within(dialog).getByLabelText(/Grado/), PRIMERO.id);
    await user.type(within(dialog).getByLabelText(/Día de vencimiento/), "5");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText(/El registro ya no existe o no está disponible/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Nombre/)).toHaveValue("Primaria 2027");
    expect(posts()).toHaveLength(1);
  });

  it("closes without sending from the cancel button", async () => {
    const user = userEvent.setup();
    const dialog = await openDialog(user);

    await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });
});

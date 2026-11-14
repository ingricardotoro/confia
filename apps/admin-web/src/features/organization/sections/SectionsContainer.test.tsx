import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import admin from "../../../i18n/locales/es-HN/admin.json";
import { academicYear, grade, modality, organizationPage, organizationRecord, problem, section } from "../../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { renderOrganization } from "../../../test/render-organization.tsx";

// The sections over the real routes, the real query layer and the real transport. A section is told by the name
// of its year and of its grade (with the modality), so those three lists are read too, as lookup lists.
installMswServer();

const SECTIONS = "/api/v1/organization/sections";
const YEARS = "/api/v1/organization/academic-years";
const GRADES = "/api/v1/organization/grades";
const MODALITIES = "/api/v1/organization/modalities";
const TIMEOUT = { timeout: 10_000 };

const PRIMARY = modality({ code: "PRI", name: "Primaria" });
const YEAR_2026 = academicYear({ name: "Año 2026", status: "ACTIVE" });
const YEAR_2025 = academicYear({ name: "Año 2025", status: "CLOSED" });
const FIRST = grade({ name: "Primero", modalityId: PRIMARY.id });
const SECOND = grade({ name: "Segundo", modalityId: PRIMARY.id });
const RETIRED = grade({ name: "Antiguo", modalityId: PRIMARY.id, isActive: false });

const sectionGets = () => requests().filter((sent) => sent.method === "GET" && sent.path === SECTIONS);
const lastQuery = () => new URLSearchParams(sectionGets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT") => requests().filter((sent) => sent.method === method);

async function openList(rows: unknown[], path = "/organization/sections") {
  route("get", SECTIONS, organizationPage("sections", rows));
  route("get", YEARS, organizationPage("academic-years", [YEAR_2026, YEAR_2025]));
  route("get", GRADES, organizationPage("grades", [FIRST, SECOND, RETIRED]));
  route("get", MODALITIES, organizationPage("modalities", [PRIMARY]));
  const app = renderOrganization(path);
  const table = await screen.findByRole("table", { name: "Secciones de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  // The lookups name the year and the grade of each row, and fill the filters.
  await screen.findByRole("option", { name: "Primaria · Primero" }, TIMEOUT);
  return app;
}

describe("the list of sections", () => {
  it("shows each section with the name of its year and of its grade, its capacity and its state", async () => {
    await openList([
      section({ name: "A", academicYearId: YEAR_2026.id, gradeId: FIRST.id, capacity: 30 }),
      section({ name: "B", academicYearId: YEAR_2026.id, gradeId: FIRST.id, capacity: null, isActive: false }),
    ]);

    const first = screen.getByRole("row", { name: /^A / });
    expect(within(first).getByText("Año 2026")).toBeInTheDocument();
    expect(within(first).getByText("Primaria · Primero")).toBeInTheDocument();
    expect(within(first).getByText("30")).toBeInTheDocument();
    const second = screen.getByRole("row", { name: /^B / });
    expect(within(second).getByText("Sin cupo definido")).toBeInTheDocument();
    expect(within(second).getByText("Inactivo")).toBeInTheDocument();
  });

  it("asks the server to filter by year and by grade, and writes both in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList([section({ name: "A" })]);
    route("get", SECTIONS, organizationPage("sections", [section({ name: "Z", academicYearId: YEAR_2026.id, gradeId: SECOND.id })]));

    await user.selectOptions(screen.getByLabelText("Año lectivo"), YEAR_2026.id);
    await waitFor(() => {
      expect(lastQuery().get("academicYearId")).toBe(YEAR_2026.id);
    });
    await user.selectOptions(screen.getByLabelText("Grado"), SECOND.id);

    await waitFor(() => {
      expect(lastQuery().get("gradeId")).toBe(SECOND.id);
    });
    expect(lastQuery().get("academicYearId")).toBe(YEAR_2026.id);
    expect(lastQuery().get("page")).toBe("0");
    expect(await screen.findByRole("row", { name: /^Z / })).toBeInTheDocument();
    expect(router.state.location.searchStr).toContain(`academicYearId=${YEAR_2026.id}`);
    expect(router.state.location.searchStr).toContain(`gradeId=${SECOND.id}`);
  });

  it("asks the server for the capacity order", async () => {
    const user = userEvent.setup();
    await openList([section()]);

    await user.click(screen.getByRole("button", { name: "Cupo" }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("capacity,asc");
    });
  });

  it("shows the empty state with the way forward", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay secciones")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear la primera sección" })).toBeInTheDocument();
  });

  it("shows the error state and loads the list on retry", async () => {
    const user = userEvent.setup();
    route("get", SECTIONS, problem("internal-error", 500), organizationPage("sections", [section({ name: "A" })]));
    route("get", YEARS, organizationPage("academic-years", [YEAR_2026]));
    route("get", GRADES, organizationPage("grades", [FIRST]));
    route("get", MODALITIES, organizationPage("modalities", [PRIMARY]));
    renderOrganization("/organization/sections");

    expect(await screen.findByText("No se pudo cargar la lista de secciones", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /^A / })).toBeInTheDocument();
  });
});

describe("creating, editing and deactivating a section", () => {
  async function fillNew(user: ReturnType<typeof userEvent.setup>, capacity?: string) {
    await user.click(screen.getByRole("button", { name: "Nueva sección" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva sección" });
    await user.selectOptions(within(dialog).getByLabelText(/Año lectivo/), YEAR_2026.id);
    await user.selectOptions(within(dialog).getByLabelText(/Grado/), FIRST.id);
    await user.type(within(dialog).getByLabelText(/Nombre/), "A");
    if (capacity !== undefined) await user.type(within(dialog).getByLabelText(/Cupo/), capacity);
    return dialog;
  }

  it("creates a section; a year that is closed and a grade that is inactive are not offered; no capacity means none is sent", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", SECTIONS, organizationRecord("sections", section({ name: "A", academicYearId: YEAR_2026.id, gradeId: FIRST.id }), 201));
    await user.click(screen.getByRole("button", { name: "Nueva sección" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva sección" });
    const optionsOf = (label: RegExp) => within(within(dialog).getByLabelText(label)).getAllByRole("option").map((option) => option.textContent);
    expect(optionsOf(/Año lectivo/)).toEqual(["Elija una opción", "Año 2026"]);
    expect(optionsOf(/Grado/)).toEqual(["Elija una opción", "Primaria · Primero", "Primaria · Segundo"]);
    await user.selectOptions(within(dialog).getByLabelText(/Año lectivo/), YEAR_2026.id);
    await user.selectOptions(within(dialog).getByLabelText(/Grado/), FIRST.id);
    await user.type(within(dialog).getByLabelText(/Nombre/), "A");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «A».")).toBeInTheDocument();
    expect(JSON.parse(writes("POST")[0]?.body ?? "{}")).toEqual({ academicYearId: YEAR_2026.id, gradeId: FIRST.id, name: "A" });
  });

  it("sends the capacity as a number when there is one", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", SECTIONS, organizationRecord("sections", section({ name: "A", capacity: 25 }), 201));
    const dialog = await fillNew(user, "25");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await screen.findByText("Se creó el registro «A».");
    expect(JSON.parse(writes("POST")[0]?.body ?? "{}")).toMatchObject({ name: "A", capacity: 25 });
  });

  it.each(["0", "-3", "diez"])("refuses the capacity %j before sending, with its own message", async (capacity) => {
    const user = userEvent.setup();
    await openList([]);
    const dialog = await fillNew(user, capacity);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 1 campo con error.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Cupo/)).toHaveAccessibleDescription(expect.stringContaining(admin.field.positiveWhole));
    expect(writes("POST")).toHaveLength(0);
  });

  it.each([
    ["academic-year-closed", admin.problem.academicYearClosed],
    ["duplicate-name", admin.problem.duplicateName],
    ["inactive-reference", admin.problem.inactiveReference],
  ] as const)("tells the %s answer in its own words", async (code, message) => {
    const user = userEvent.setup();
    await openList([]);
    route("post", SECTIONS, problem(code, 409));
    const dialog = await fillNew(user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
  });

  it("shows the year and the grade of an existing section as values that cannot be changed, and edits the rest in one PUT", async () => {
    const user = userEvent.setup();
    const row = section({ name: "A", academicYearId: YEAR_2026.id, gradeId: FIRST.id, capacity: 30 });
    await openList([row]);
    route("put", `${SECTIONS}/${row.id}`, organizationRecord("sections", { ...row, capacity: 28 }));
    await user.click(screen.getByRole("button", { name: "Editar A" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar sección" });

    expect(within(dialog).getByLabelText(/Año lectivo/)).toHaveValue("Año 2026");
    expect(within(dialog).getByLabelText(/Año lectivo/)).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText(/Grado/)).toHaveValue("Primaria · Primero");
    expect(within(dialog).getByLabelText(/Grado/)).toHaveAttribute("readonly");
    await user.clear(within(dialog).getByLabelText(/Cupo/));
    await user.type(within(dialog).getByLabelText(/Cupo/), "28");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se guardaron los cambios de «A».")).toBeInTheDocument();
    expect(JSON.parse(writes("PUT")[0]?.body ?? "{}")).toEqual({ name: "A", isActive: true, capacity: 28 });
  });

  it("deactivates from the row, keeping the capacity, and leaves a section without one without it", async () => {
    const user = userEvent.setup();
    const limited = section({ name: "A", capacity: 30 });
    const open = section({ name: "B", capacity: null });
    await openList([limited, open]);
    route("put", `${SECTIONS}/${limited.id}`, organizationRecord("sections", { ...limited, isActive: false }));
    route("put", `${SECTIONS}/${open.id}`, organizationRecord("sections", { ...open, isActive: false }));

    await user.click(screen.getByRole("button", { name: "Desactivar A" }));
    await screen.findByText("Se desactivó «A».");
    await user.click(screen.getByRole("button", { name: "Desactivar B" }));
    await screen.findByText("Se desactivó «B».");

    expect(writes("PUT").map((sent) => JSON.parse(sent.body) as unknown)).toEqual([
      { name: "A", isActive: false, capacity: 30 },
      { name: "B", isActive: false },
    ]);
  });
});

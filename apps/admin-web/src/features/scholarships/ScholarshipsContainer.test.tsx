import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { paymentConcept, paymentConceptPage, problem, scholarship, scholarshipPage, scholarshipRecord } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderScholarships } from "../../test/render-scholarships.tsx";
import { PERMISSION_CODES } from "../../shared/permissions/permission-codes.ts";

// The scholarships over the real route, the real query layer and the real transport: the list as the server serves it (paged, filtered and
// ordered there, with all of it in the address), the create and the edit with the generated request, the lock of the benefit once an award is
// approved, the deactivation, every state of the load, and what a session without the write permission is not offered.
installMswServer();

const SCHOLARSHIPS = "/api/v1/scholarships";
const CONCEPTS = "/api/v1/catalog/payment-concepts";
const TIMEOUT = { timeout: 10_000 };
const MENS = paymentConcept({ id: "00000000-0000-4000-8000-000000007001", code: "MENS", name: "Mensualidad" });
const MATR = paymentConcept({ id: "00000000-0000-4000-8000-000000007002", code: "MATR", name: "Matrícula" });

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === SCHOLARSHIPS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT", path = SCHOLARSHIPS) => requests().filter((sent) => sent.method === method && sent.path === path);
const without = (code: string) => PERMISSION_CODES.filter((candidate) => candidate !== code);

async function openList(rows: unknown[] = [scholarship()], path = "/scholarships/list", permissions?: readonly string[]) {
  route("get", SCHOLARSHIPS, scholarshipPage(rows));
  route("get", CONCEPTS, paymentConceptPage([MENS, MATR]));
  const rendered = renderScholarships(path, permissions === undefined ? {} : { permissions });
  const table = await screen.findByRole("table", { name: "Becas de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

describe("the list of scholarships", () => {
  it("shows each scholarship with its benefit as the server sent it, under the heading of the area, and asks for the first page", async () => {
    await openList([
      scholarship({ code: "BECA25", name: "Beca de excelencia", percent: "25.00" }),
      scholarship({
        code: "FIJA250",
        name: "Beca fija",
        benefitType: "FIXED_AMOUNT",
        percent: null,
        fixedAmount: { amount: "250.00", currency: "HNL" },
        periodCap: { amount: "1000.00", currency: "HNL" },
        active: false,
      }),
    ]);

    expect(screen.getByRole("heading", { level: 1, name: "Becas" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Becas" })).toBeInTheDocument();
    const percentage = within(screen.getByRole("row", { name: /Beca de excelencia/ }));
    expect(percentage.getByText("25.00 %")).toBeInTheDocument();
    expect(percentage.getByText("Sin tope")).toBeInTheDocument();
    expect(percentage.getByText("Mensualidad")).toBeInTheDocument();
    expect(percentage.getByText("Activo")).toBeInTheDocument();
    const fixed = within(screen.getByRole("row", { name: /Beca fija/ }));
    expect(fixed.getByText("Monto fijo")).toBeInTheDocument();
    expect(fixed.getAllByText(/250\.00/).length).toBeGreaterThan(0);
    expect(fixed.getAllByText(/1,000\.00/).length).toBeGreaterThan(0);
    expect(fixed.getByText("Inactivo")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("benefitType")).toBe(false);
  });

  it("filters by type of benefit and by state on the server, and writes both in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    const filters = within(screen.getByRole("group", { name: "Filtros" }));

    await user.selectOptions(filters.getByLabelText("Tipo de beneficio"), "FIXED_AMOUNT");
    await waitFor(() => {
      expect(lastQuery().get("benefitType")).toBe("FIXED_AMOUNT");
    }, TIMEOUT);
    await user.selectOptions(filters.getByLabelText("Estado"), "false");

    await waitFor(() => {
      expect(lastQuery().get("active")).toBe("false");
    }, TIMEOUT);
    expect(lastQuery().get("benefitType")).toBe("FIXED_AMOUNT");
    expect(router.state.location.searchStr).toContain("benefitType=FIXED_AMOUNT");
    expect(router.state.location.searchStr).toContain("active=false");
  });

  it("asks the server for the order of the name column and keeps it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();

    await user.click(screen.getByRole("button", { name: "Nombre" }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("name,asc");
    }, TIMEOUT);
    expect(decodeURIComponent(router.state.location.searchStr)).toContain("sort=name:asc");
  });

  it("opens a shared link with its filters applied", async () => {
    await openList([scholarship()], "/scholarships/list?benefitType=PERCENTAGE&active=true&sort=code:desc");

    expect(lastQuery().get("benefitType")).toBe("PERCENTAGE");
    expect(lastQuery().get("active")).toBe("true");
    expect(lastQuery().get("sort")).toBe("code,desc");
  });

  it("leads the address of the area to the list", async () => {
    route("get", SCHOLARSHIPS, scholarshipPage([scholarship()]));
    const { router } = renderScholarships("/scholarships");

    await screen.findByRole("table", { name: "Becas de la institución" }, TIMEOUT);
    expect(router.state.location.pathname).toBe("/scholarships/list");
  });

  it("shows the empty state with the way forward", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay becas")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear una beca" })).toBeInTheDocument();
  });

  it("shows no matches, not the empty state, when the filters leave nothing, and clears them", async () => {
    const user = userEvent.setup();
    await openList([], "/scholarships/list?active=false");

    expect(screen.getByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    route("get", SCHOLARSHIPS, scholarshipPage([scholarship({ name: "Beca de excelencia" })]));
    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));

    await waitFor(() => {
      expect(lastQuery().has("active")).toBe(false);
    }, TIMEOUT);
    expect(await screen.findByRole("row", { name: /Beca de excelencia/ }, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the error state and loads the list on retry", async () => {
    const user = userEvent.setup();
    route("get", SCHOLARSHIPS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), scholarshipPage([scholarship({ name: "Beca de excelencia" })]));
    renderScholarships("/scholarships/list");

    expect(await screen.findByText("No se pudieron cargar las becas", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /Beca de excelencia/ }, TIMEOUT)).toBeInTheDocument();
  });
});

describe("creating, editing and deactivating a scholarship", () => {
  it("creates a percentage scholarship with the concepts it covers, sends the generated request, and lists it", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", SCHOLARSHIPS, scholarshipRecord(scholarship({ code: "BECA25", name: "Beca de excelencia" }), 201));
    route("get", SCHOLARSHIPS, scholarshipPage([scholarship({ code: "BECA25", name: "Beca de excelencia" })]));
    await user.click(screen.getByRole("button", { name: "Crear una beca" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear una beca" }, TIMEOUT);

    await user.type(within(dialog).getByLabelText(/^Código/), "BECA25");
    await user.type(within(dialog).getByLabelText(/^Nombre/), "Beca de excelencia");
    await user.selectOptions(within(dialog).getByLabelText(/^Tipo de beneficio/), "PERCENTAGE");
    await user.type(within(dialog).getByLabelText(/^Porcentaje/), "25");
    await user.type(within(dialog).getByLabelText(/^Tope por período/), "1500.00");
    await user.click(await within(dialog).findByRole("checkbox", { name: "MENS · Mensualidad" }, TIMEOUT));
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Beca de excelencia».", undefined, TIMEOUT)).toBeInTheDocument();
    expect(writes("POST")).toHaveLength(1);
    expect(JSON.parse(writes("POST")[0]?.body ?? "{}")).toEqual({
      code: "BECA25",
      name: "Beca de excelencia",
      benefitType: "PERCENTAGE",
      percent: "25",
      conceptIds: [MENS.id],
      periodCap: { amount: "1500.00", currency: "HNL" },
    });
    expect(await screen.findByRole("row", { name: /Beca de excelencia/ }, TIMEOUT)).toBeInTheDocument();
  });

  it("creates a fixed amount scholarship with no percentage and no cap", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", SCHOLARSHIPS, scholarshipRecord(scholarship({ code: "FIJA250", name: "Beca fija" }), 201));
    await user.click(screen.getByRole("button", { name: "Crear una beca" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear una beca" }, TIMEOUT);

    await user.type(within(dialog).getByLabelText(/^Código/), "FIJA250");
    await user.type(within(dialog).getByLabelText(/^Nombre/), "Beca fija");
    await user.selectOptions(within(dialog).getByLabelText(/^Tipo de beneficio/), "FIXED_AMOUNT");
    await user.type(within(dialog).getByLabelText(/^Monto fijo por cargo/), "250.00");
    await user.click(await within(dialog).findByRole("checkbox", { name: "MATR · Matrícula" }, TIMEOUT));
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => {
      expect(writes("POST")).toHaveLength(1);
    }, TIMEOUT);
    expect(JSON.parse(writes("POST")[0]?.body ?? "{}")).toEqual({
      code: "FIJA250",
      name: "Beca fija",
      benefitType: "FIXED_AMOUNT",
      conceptIds: [MATR.id],
      fixedAmount: { amount: "250.00", currency: "HNL" },
    });
  });

  it.each([
    ["150", "El porcentaje debe estar entre 0.01 y 100."],
    ["0", "El porcentaje debe estar entre 0.01 y 100."],
    ["12.345", "Escriba el porcentaje con punto y a lo sumo dos decimales, por ejemplo 33.33."],
  ])("refuses the percentage %s before sending anything", async (percent, message) => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Crear una beca" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear una beca" }, TIMEOUT);
    await user.type(within(dialog).getByLabelText(/^Código/), "BECA25");
    await user.type(within(dialog).getByLabelText(/^Nombre/), "Beca de excelencia");
    await user.selectOptions(within(dialog).getByLabelText(/^Tipo de beneficio/), "PERCENTAGE");
    await user.type(within(dialog).getByLabelText(/^Porcentaje/), percent);
    await user.click(await within(dialog).findByRole("checkbox", { name: "MENS · Mensualidad" }, TIMEOUT));
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect((await within(dialog).findAllByText(message)).length).toBeGreaterThan(0);
    expect(writes("POST")).toHaveLength(0);
  });

  it("refuses a scholarship with no concept, with the message under the group of concepts", async () => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Crear una beca" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear una beca" }, TIMEOUT);
    await user.type(within(dialog).getByLabelText(/^Código/), "BECA25");
    await user.type(within(dialog).getByLabelText(/^Nombre/), "Beca de excelencia");
    await user.selectOptions(within(dialog).getByLabelText(/^Tipo de beneficio/), "PERCENTAGE");
    await user.type(within(dialog).getByLabelText(/^Porcentaje/), "25");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect((await within(dialog).findAllByText("Este dato es obligatorio.")).length).toBeGreaterThan(0);
    expect(writes("POST")).toHaveLength(0);
  });

  it("tells a code that is already used under the code field", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", SCHOLARSHIPS, problem("duplicate-scholarship-code", 409));
    await user.click(screen.getByRole("button", { name: "Crear una beca" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear una beca" }, TIMEOUT);
    await user.type(within(dialog).getByLabelText(/^Código/), "BECA25");
    await user.type(within(dialog).getByLabelText(/^Nombre/), "Beca de excelencia");
    await user.selectOptions(within(dialog).getByLabelText(/^Tipo de beneficio/), "PERCENTAGE");
    await user.type(within(dialog).getByLabelText(/^Porcentaje/), "25");
    await user.click(await within(dialog).findByRole("checkbox", { name: "MENS · Mensualidad" }, TIMEOUT));
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect((await within(dialog).findAllByText("Ya existe una beca con ese código.", undefined, TIMEOUT)).length).toBeGreaterThan(0);
    expect(screen.getByRole("dialog", { name: "Crear una beca" })).toBeInTheDocument();
  });

  it("edits a scholarship with the version it was read at and keeps its code fixed", async () => {
    const user = userEvent.setup();
    await openList([scholarship({ code: "BECA25", name: "Beca de excelencia", version: 3 })]);
    route("put", `${SCHOLARSHIPS}/:id`, scholarshipRecord(scholarship({ name: "Beca de excelencia 2027" })));
    await user.click(screen.getByRole("button", { name: "Editar Beca de excelencia" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar la beca" }, TIMEOUT);

    expect(within(dialog).getByLabelText(/^Código/)).toHaveAttribute("readonly");
    await user.clear(within(dialog).getByLabelText(/^Nombre/));
    await user.type(within(dialog).getByLabelText(/^Nombre/), "Beca de excelencia 2027");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se guardaron los cambios de «Beca de excelencia 2027».", undefined, TIMEOUT)).toBeInTheDocument();
    const put = requests().find((sent) => sent.method === "PUT");
    expect(JSON.parse(put?.body ?? "{}")).toMatchObject({ version: 3, name: "Beca de excelencia 2027", benefitType: "PERCENTAGE", percent: "25.00", conceptIds: [MENS.id] });
  });

  it("tells inside the dialog that a scholarship with approved awards cannot widen its cap or its concepts", async () => {
    const user = userEvent.setup();
    await openList([scholarship({ name: "Beca de excelencia", benefitLocked: true })]);
    route("put", `${SCHOLARSHIPS}/:id`, problem("scholarship-scope-locked", 409, { traceId: "01J8Z4K2QF9P0X" }));
    await user.click(screen.getByRole("button", { name: "Editar Beca de excelencia" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar la beca" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText(/Para ampliar la beca, cree otra./, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Editar la beca" })).toBeInTheDocument();
  });

  it("shows the benefit of a scholarship with approved awards for reading only, and says why", async () => {
    const user = userEvent.setup();
    await openList([scholarship({ name: "Beca de excelencia", benefitLocked: true })]);
    await user.click(screen.getByRole("button", { name: "Editar Beca de excelencia" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar la beca" }, TIMEOUT);

    expect(within(dialog).getByText(/ya tiene otorgamientos aprobados: su tipo y su valor no se pueden cambiar/)).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Porcentaje/)).toHaveAttribute("readonly");
    expect(within(dialog).queryByRole("combobox", { name: /Tipo de beneficio/ })).not.toBeInTheDocument();
  });

  it("deactivates a scholarship from the row, with one request, and tells the person", async () => {
    const user = userEvent.setup();
    await openList([scholarship({ id: "00000000-0000-4000-8000-0000000e0001", name: "Beca de excelencia" })]);
    route("post", `${SCHOLARSHIPS}/00000000-0000-4000-8000-0000000e0001/deactivate`, scholarshipRecord(scholarship({ name: "Beca de excelencia", active: false })));

    await user.click(screen.getByRole("button", { name: "Desactivar Beca de excelencia" }));

    expect(await screen.findByText("Se desactivó «Beca de excelencia».", undefined, TIMEOUT)).toBeInTheDocument();
    expect(writes("POST", `${SCHOLARSHIPS}/00000000-0000-4000-8000-0000000e0001/deactivate`)).toHaveLength(1);
  });

  it("tells a refusal of the server to deactivate in the region of the page", async () => {
    const user = userEvent.setup();
    await openList([scholarship({ id: "00000000-0000-4000-8000-0000000e0001", name: "Beca de excelencia" })]);
    route("post", `${SCHOLARSHIPS}/00000000-0000-4000-8000-0000000e0001/deactivate`, problem("forbidden", 403));

    await user.click(screen.getByRole("button", { name: "Desactivar Beca de excelencia" }));

    expect(await screen.findByText(/No tiene permiso para realizar esta acción/, undefined, TIMEOUT)).toBeInTheDocument();
  });
});

describe("the scholarships without the write permission", () => {
  it("shows the list without the way to create, edit or deactivate to a session without scholarships:create", async () => {
    await openList([scholarship({ name: "Beca de excelencia" })], "/scholarships/list", without("scholarships:create"));

    expect(screen.queryByRole("button", { name: "Crear beca" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Editar Beca de excelencia" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Desactivar Beca de excelencia" })).not.toBeInTheDocument();
  });
});

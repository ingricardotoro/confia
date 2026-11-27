import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { discount, discountPage, discountRecord, paymentConcept, paymentConceptPage, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderScholarships } from "../../test/render-scholarships.tsx";
import { PERMISSION_CODES } from "../../shared/permissions/permission-codes.ts";

// The discounts over the real route, the real query layer and the real transport: the list as the server serves it (paged, filtered and ordered there,
// with all of it in the address), the create and the edit with the generated request, the lock of the type, the value and the scope once the discount is
// in use (read only in the form and told when the server refuses), the deactivation, every state of the load, and what a session without the write
// permission is not offered.
installMswServer();

const DISCOUNTS = "/api/v1/scholarships/discounts";
const CONCEPTS = "/api/v1/catalog/payment-concepts";
const TIMEOUT = { timeout: 10_000 };
const MENS = paymentConcept({ id: "00000000-0000-4000-8000-000000007001", code: "MENS", name: "Mensualidad" });
const MATR = paymentConcept({ id: "00000000-0000-4000-8000-000000007002", code: "MATR", name: "Matrícula" });

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === DISCOUNTS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT", path = DISCOUNTS) => requests().filter((sent) => sent.method === method && sent.path === path);
const without = (code: string) => PERMISSION_CODES.filter((candidate) => candidate !== code);

async function openList(rows: unknown[] = [discount()], path = "/scholarships/discounts", permissions?: readonly string[]) {
  route("get", DISCOUNTS, discountPage(rows));
  route("get", CONCEPTS, paymentConceptPage([MENS, MATR]));
  const rendered = renderScholarships(path, permissions === undefined ? {} : { permissions });
  const table = await screen.findByRole("table", { name: "Descuentos de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

async function fillValidDiscount(dialog: HTMLElement, user: ReturnType<typeof userEvent.setup>) {
  await user.type(within(dialog).getByLabelText(/^Código/), "CAMP10");
  await user.type(within(dialog).getByLabelText(/^Nombre/), "Campaña de matrícula");
  await user.selectOptions(within(dialog).getByLabelText(/^Clase/), "campaign");
  await user.selectOptions(within(dialog).getByLabelText(/^Tipo de beneficio/), "PERCENTAGE");
  await user.type(within(dialog).getByLabelText(/^Porcentaje/), "10");
  await user.type(within(dialog).getByLabelText(/^Vigente desde/), "2027-01-01");
  await user.type(within(dialog).getByLabelText(/^Vigente hasta/), "2027-12-31");
  await user.click(await within(dialog).findByRole("checkbox", { name: "MENS · Mensualidad" }, TIMEOUT));
}

describe("the list of discounts", () => {
  it("shows each discount with its class and benefit as the server sent them, under the heading of the area, and asks for the first page", async () => {
    await openList([
      discount({ code: "CAMP10", name: "Campaña de matrícula", percent: "10.00" }),
      discount({
        code: "CONV250",
        name: "Convenio con la empresa",
        kind: "agreement",
        benefitType: "FIXED_AMOUNT",
        percent: null,
        fixedAmount: { amount: "250.00", currency: "HNL" },
        stackable: false,
        active: false,
      }),
    ]);

    expect(screen.getByRole("heading", { level: 1, name: "Becas" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Descuentos" })).toBeInTheDocument();
    const campaign = within(screen.getByRole("row", { name: /Campaña de matrícula/ }));
    expect(campaign.getByText("10.00 %")).toBeInTheDocument();
    expect(campaign.getByText("Campaña")).toBeInTheDocument();
    expect(campaign.getByText("Acumulable")).toBeInTheDocument();
    const agreement = within(screen.getByRole("row", { name: /Convenio con la empresa/ }));
    expect(agreement.getByText("L 250.00")).toBeInTheDocument();
    expect(agreement.getByText("No acumulable")).toBeInTheDocument();
    expect(agreement.getByText("Inactivo")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("kind")).toBe(false);
  });

  it("filters by class, type of benefit and state on the server, and writes them in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    const filters = within(screen.getByRole("group", { name: "Filtros" }));

    await user.selectOptions(filters.getByLabelText("Clase"), "agreement");
    await waitFor(() => {
      expect(lastQuery().get("kind")).toBe("agreement");
    }, TIMEOUT);
    await user.selectOptions(filters.getByLabelText("Tipo de beneficio"), "FIXED_AMOUNT");
    await user.selectOptions(filters.getByLabelText("Estado"), "false");

    await waitFor(() => {
      expect(lastQuery().get("active")).toBe("false");
    }, TIMEOUT);
    expect(lastQuery().get("kind")).toBe("agreement");
    expect(lastQuery().get("benefitType")).toBe("FIXED_AMOUNT");
    expect(router.state.location.searchStr).toContain("kind=agreement");
    expect(router.state.location.searchStr).toContain("active=false");
  });

  it("asks the server for the order of the validity column and keeps it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();

    await user.click(screen.getByRole("button", { name: "Vigencia" }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("validFrom,asc");
    }, TIMEOUT);
    expect(decodeURIComponent(router.state.location.searchStr)).toContain("sort=validFrom:asc");
  });

  it("opens a shared link with its filters applied", async () => {
    await openList([discount()], "/scholarships/discounts?kind=campaign&active=true&sort=code:desc");

    expect(lastQuery().get("kind")).toBe("campaign");
    expect(lastQuery().get("active")).toBe("true");
    expect(lastQuery().get("sort")).toBe("code,desc");
  });

  it("shows the empty state with the way forward", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay descuentos")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear un descuento" })).toBeInTheDocument();
  });

  it("shows no matches, not the empty state, when the filters leave nothing, and clears them", async () => {
    const user = userEvent.setup();
    await openList([], "/scholarships/discounts?kind=agreement");

    expect(screen.getByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    route("get", DISCOUNTS, discountPage([discount({ name: "Campaña de matrícula" })]));
    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));

    await waitFor(() => {
      expect(lastQuery().has("kind")).toBe(false);
    }, TIMEOUT);
    expect(await screen.findByRole("row", { name: /Campaña de matrícula/ }, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the error state and loads the list on retry", async () => {
    const user = userEvent.setup();
    route("get", DISCOUNTS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), discountPage([discount({ name: "Campaña de matrícula" })]));
    renderScholarships("/scholarships/discounts");

    expect(await screen.findByText("No se pudieron cargar los descuentos", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /Campaña de matrícula/ }, TIMEOUT)).toBeInTheDocument();
  });
});

describe("creating, editing and deactivating a discount", () => {
  it("creates a campaign discount that stacks, sends the generated request, and lists it", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", DISCOUNTS, discountRecord(discount({ code: "CAMP10", name: "Campaña de matrícula" }), 201));
    route("get", DISCOUNTS, discountPage([discount({ code: "CAMP10", name: "Campaña de matrícula" })]));
    await user.click(screen.getByRole("button", { name: "Crear un descuento" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear un descuento" }, TIMEOUT);

    await fillValidDiscount(dialog, user);
    await user.click(within(dialog).getByRole("checkbox", { name: "Acumulable con los demás beneficios" }));
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Campaña de matrícula».", undefined, TIMEOUT)).toBeInTheDocument();
    expect(writes("POST")).toHaveLength(1);
    expect(JSON.parse(writes("POST")[0]?.body ?? "{}")).toEqual({
      code: "CAMP10",
      name: "Campaña de matrícula",
      kind: "campaign",
      benefitType: "PERCENTAGE",
      percent: "10",
      validFrom: "2027-01-01",
      validTo: "2027-12-31",
      stackable: true,
      conceptIds: [MENS.id],
    });
    expect(await screen.findByRole("row", { name: /Campaña de matrícula/ }, TIMEOUT)).toBeInTheDocument();
  });

  it("creates a fixed amount agreement that does not stack, with no percentage", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", DISCOUNTS, discountRecord(discount({ code: "CONV250", name: "Convenio" }), 201));
    await user.click(screen.getByRole("button", { name: "Crear un descuento" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear un descuento" }, TIMEOUT);

    await user.type(within(dialog).getByLabelText(/^Código/), "CONV250");
    await user.type(within(dialog).getByLabelText(/^Nombre/), "Convenio");
    await user.selectOptions(within(dialog).getByLabelText(/^Clase/), "agreement");
    await user.selectOptions(within(dialog).getByLabelText(/^Tipo de beneficio/), "FIXED_AMOUNT");
    await user.type(within(dialog).getByLabelText(/^Monto fijo por cargo/), "250.00");
    await user.type(within(dialog).getByLabelText(/^Vigente desde/), "2027-01-01");
    await user.type(within(dialog).getByLabelText(/^Vigente hasta/), "2027-01-01");
    await user.click(await within(dialog).findByRole("checkbox", { name: "MATR · Matrícula" }, TIMEOUT));
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => {
      expect(writes("POST")).toHaveLength(1);
    }, TIMEOUT);
    expect(JSON.parse(writes("POST")[0]?.body ?? "{}")).toEqual({
      code: "CONV250",
      name: "Convenio",
      kind: "agreement",
      benefitType: "FIXED_AMOUNT",
      fixedAmount: { amount: "250.00", currency: "HNL" },
      validFrom: "2027-01-01",
      validTo: "2027-01-01",
      stackable: false,
      conceptIds: [MATR.id],
    });
  });

  it.each([
    ["150", "El porcentaje debe estar entre 0.01 y 100."],
    ["0", "El porcentaje debe estar entre 0.01 y 100."],
    ["12.345", "Escriba el porcentaje con punto y a lo sumo dos decimales, por ejemplo 33.33."],
  ])("refuses the percentage %s before sending anything", async (percent, message) => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Crear un descuento" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear un descuento" }, TIMEOUT);
    await fillValidDiscount(dialog, user);
    await user.clear(within(dialog).getByLabelText(/^Porcentaje/));
    await user.type(within(dialog).getByLabelText(/^Porcentaje/), percent);
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect((await within(dialog).findAllByText(message)).length).toBeGreaterThan(0);
    expect(writes("POST")).toHaveLength(0);
  });

  it("refuses an end of the validity before its start, with the message under the end", async () => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Crear un descuento" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear un descuento" }, TIMEOUT);
    await fillValidDiscount(dialog, user);
    await user.clear(within(dialog).getByLabelText(/^Vigente hasta/));
    await user.type(within(dialog).getByLabelText(/^Vigente hasta/), "2026-12-31");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/^Vigente hasta/)).toBeInvalid();
    }, TIMEOUT);
    expect(writes("POST")).toHaveLength(0);
  });

  it("refuses a discount with no concept, with the message under the group of concepts", async () => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Crear un descuento" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear un descuento" }, TIMEOUT);
    await fillValidDiscount(dialog, user);
    await user.click(within(dialog).getByRole("checkbox", { name: "MENS · Mensualidad" }));
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect((await within(dialog).findAllByText("Este dato es obligatorio.")).length).toBeGreaterThan(0);
    expect(writes("POST")).toHaveLength(0);
  });

  it("tells a code that is already used under the code field", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", DISCOUNTS, problem("duplicate-discount-code", 409));
    await user.click(screen.getByRole("button", { name: "Crear un descuento" }));
    const dialog = await screen.findByRole("dialog", { name: "Crear un descuento" }, TIMEOUT);
    await fillValidDiscount(dialog, user);
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect((await within(dialog).findAllByText("Ya existe un descuento con ese código.", undefined, TIMEOUT)).length).toBeGreaterThan(0);
    expect(within(dialog).getByLabelText(/^Código/)).toBeInvalid();
    expect(screen.getByRole("dialog", { name: "Crear un descuento" })).toBeInTheDocument();
  });

  it("edits a discount with the version it was read at, keeping its code and class fixed, and sends the stackable mark it holds", async () => {
    const user = userEvent.setup();
    await openList([discount({ code: "CAMP10", name: "Campaña de matrícula", version: 3, stackable: true })]);
    route("put", `${DISCOUNTS}/:id`, discountRecord(discount({ name: "Campaña 2027" })));
    await user.click(screen.getByRole("button", { name: "Editar Campaña de matrícula" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar el descuento" }, TIMEOUT);

    expect(within(dialog).getByLabelText(/^Código/)).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText(/^Clase/)).toHaveAttribute("readonly");
    await user.clear(within(dialog).getByLabelText(/^Nombre/));
    await user.type(within(dialog).getByLabelText(/^Nombre/), "Campaña 2027");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se guardaron los cambios de «Campaña 2027».", undefined, TIMEOUT)).toBeInTheDocument();
    const put = requests().find((sent) => sent.method === "PUT");
    expect(JSON.parse(put?.body ?? "{}")).toMatchObject({ version: 3, name: "Campaña 2027", benefitType: "PERCENTAGE", percent: "10.00", stackable: true, conceptIds: [MENS.id] });
  });

  it("shows the type and the value of a discount in use for reading only, and says why", async () => {
    const user = userEvent.setup();
    await openList([discount({ name: "Campaña de matrícula", inUse: true })]);
    await user.click(screen.getByRole("button", { name: "Editar Campaña de matrícula" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar el descuento" }, TIMEOUT);

    expect(within(dialog).getByText(/ya está en uso .*su tipo, su valor y su marca de acumulable no se pueden cambiar/)).toBeInTheDocument();
    expect(within(dialog).queryByRole("checkbox", { name: "Acumulable con los demás beneficios" })).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Porcentaje/)).toHaveAttribute("readonly");
    expect(within(dialog).queryByRole("combobox", { name: /Tipo de beneficio/ })).not.toBeInTheDocument();
  });

  it("tells inside the dialog that a discount in use cannot change its benefit, and keeps the dialog open", async () => {
    const user = userEvent.setup();
    await openList([discount({ name: "Campaña de matrícula" })]);
    route("put", `${DISCOUNTS}/:id`, problem("discount-benefit-locked", 409, { traceId: "01J8Z4K2QF9P0X" }));
    await user.click(screen.getByRole("button", { name: "Editar Campaña de matrícula" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar el descuento" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText(/su tipo, su valor y su marca de acumulable no se pueden cambiar. Cree otro descuento con el nuevo valor/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Editar el descuento" })).toBeInTheDocument();
  });

  it("tells inside the dialog that a discount in use cannot widen its validity or its concepts", async () => {
    const user = userEvent.setup();
    await openList([discount({ name: "Campaña de matrícula", inUse: true })]);
    route("put", `${DISCOUNTS}/:id`, problem("discount-scope-locked", 409));
    await user.click(screen.getByRole("button", { name: "Editar Campaña de matrícula" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar el descuento" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText(/la vigencia solo puede acortarse y los conceptos solo pueden quitarse. Para ampliarlo, cree otro descuento./, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Editar el descuento" })).toBeInTheDocument();
  });

  it("tells under the end of the validity that shortening it would leave an assignment outside", async () => {
    const user = userEvent.setup();
    await openList([discount({ name: "Campaña de matrícula" })]);
    route("put", `${DISCOUNTS}/:id`, problem("validation-failed", 400, { errors: [{ field: "validTo", reason: "assignments-outside-validity" }] }));
    await user.click(screen.getByRole("button", { name: "Editar Campaña de matrícula" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar el descuento" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect((await within(dialog).findAllByText(/asignaciones pendientes o aprobadas que quedarían fuera de esta vigencia/, undefined, TIMEOUT)).length).toBeGreaterThan(0);
    expect(within(dialog).getByLabelText(/^Vigente hasta/)).toBeInvalid();
  });

  it("deactivates a discount from the row, with one request, and tells the person", async () => {
    const user = userEvent.setup();
    await openList([discount({ id: "00000000-0000-4000-8000-0000000e0201", name: "Campaña de matrícula" })]);
    route("post", `${DISCOUNTS}/00000000-0000-4000-8000-0000000e0201/deactivate`, discountRecord(discount({ name: "Campaña de matrícula", active: false })));

    await user.click(screen.getByRole("button", { name: "Desactivar Campaña de matrícula" }));

    expect(await screen.findByText("Se desactivó «Campaña de matrícula».", undefined, TIMEOUT)).toBeInTheDocument();
    expect(writes("POST", `${DISCOUNTS}/00000000-0000-4000-8000-0000000e0201/deactivate`)).toHaveLength(1);
  });

  it("reactivates an inactive discount from the row", async () => {
    const user = userEvent.setup();
    await openList([discount({ id: "00000000-0000-4000-8000-0000000e0201", name: "Campaña de matrícula", active: false })]);
    route("post", `${DISCOUNTS}/00000000-0000-4000-8000-0000000e0201/reactivate`, discountRecord(discount({ name: "Campaña de matrícula" })));

    await user.click(screen.getByRole("button", { name: "Activar Campaña de matrícula" }));

    expect(await screen.findByText("Se activó «Campaña de matrícula».", undefined, TIMEOUT)).toBeInTheDocument();
    expect(writes("POST", `${DISCOUNTS}/00000000-0000-4000-8000-0000000e0201/reactivate`)).toHaveLength(1);
  });

  it("tells a refusal of the server to deactivate in the region of the page", async () => {
    const user = userEvent.setup();
    await openList([discount({ id: "00000000-0000-4000-8000-0000000e0201", name: "Campaña de matrícula" })]);
    route("post", `${DISCOUNTS}/00000000-0000-4000-8000-0000000e0201/deactivate`, problem("forbidden", 403));

    await user.click(screen.getByRole("button", { name: "Desactivar Campaña de matrícula" }));

    expect(await screen.findByText(/No tiene permiso para realizar esta acción/, undefined, TIMEOUT)).toBeInTheDocument();
  });
});

describe("the discounts without the write permission", () => {
  it("shows the list without the way to create, edit or deactivate to a session without scholarships:create", async () => {
    await openList([discount({ name: "Campaña de matrícula" })], "/scholarships/discounts", without("scholarships:create"));

    expect(screen.queryByRole("button", { name: "Crear descuento" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Editar Campaña de matrícula" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Desactivar Campaña de matrícula" })).not.toBeInTheDocument();
  });
});

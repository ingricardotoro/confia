import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import admin from "../../../i18n/locales/es-HN/admin.json";
import { grade, modality, organizationPage, organizationRecord, problem } from "../../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { renderOrganization } from "../../../test/render-organization.tsx";

// The grades over the real routes, the real query layer and the real transport. A grade says which modality it
// belongs to by name, so the modalities are read too, as a lookup list.
installMswServer();

const GRADES = "/api/v1/organization/grades";
const MODALITIES = "/api/v1/organization/modalities";
const TIMEOUT = { timeout: 10_000 };

const PRIMARY = modality({ code: "PRI", name: "Primaria" });
const SECONDARY = modality({ code: "BAC", name: "Bachillerato" });
const RETIRED = modality({ code: "OLD", name: "Nocturna", isActive: false });

const gradeGets = () => requests().filter((sent) => sent.method === "GET" && sent.path === GRADES);
const lastQuery = () => new URLSearchParams(gradeGets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT") => requests().filter((sent) => sent.method === method);

async function openList(rows: unknown[], path = "/organization/grades") {
  route("get", GRADES, organizationPage("grades", rows));
  route("get", MODALITIES, organizationPage("modalities", [PRIMARY, SECONDARY, RETIRED]));
  const app = renderOrganization(path);
  const table = await screen.findByRole("table", { name: "Grados de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return app;
}

describe("the list of grades", () => {
  it("shows each grade with its order, the name of its modality and its state", async () => {
    await openList([grade({ name: "Primero", ordinal: 1, modalityId: PRIMARY.id })]);

    await waitFor(() => {
      expect(within(screen.getByRole("row", { name: /Primero/ })).getByText("Primaria")).toBeInTheDocument();
    });
    expect(within(screen.getByRole("row", { name: /Primero/ })).getByText("Activo")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().has("modalityId")).toBe(false);
  });

  it("asks the server to filter by the modality chosen, writes it in the address and starts from the first page", async () => {
    const user = userEvent.setup();
    const { router } = await openList([grade({ modalityId: PRIMARY.id })]);
    await screen.findByRole("option", { name: "Bachillerato" });
    route("get", GRADES, organizationPage("grades", [grade({ name: "Décimo", modalityId: SECONDARY.id })]));

    await user.selectOptions(screen.getByLabelText("Modalidad"), SECONDARY.id);

    expect(await screen.findByRole("row", { name: /Décimo/ })).toBeInTheDocument();
    expect(lastQuery().get("modalityId")).toBe(SECONDARY.id);
    expect(lastQuery().get("page")).toBe("0");
    expect(router.state.location.searchStr).toBe(`?modalityId=${SECONDARY.id}`);
  });

  it("opens with the filter of the address, and says so when nothing matches, offering to remove it", async () => {
    const user = userEvent.setup();
    await openList([], `/organization/grades?modalityId=${SECONDARY.id}`);

    expect(lastQuery().get("modalityId")).toBe(SECONDARY.id);
    expect(screen.getByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    route("get", GRADES, organizationPage("grades", [grade({ name: "Primero", modalityId: PRIMARY.id })]));
    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));

    expect(await screen.findByRole("row", { name: /Primero/ })).toBeInTheDocument();
    expect(lastQuery().has("modalityId")).toBe(false);
  });

  it("shows the empty state of a list that has no grades at all", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay grados")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear el primer grado" })).toBeInTheDocument();
  });

  it("warns, with a retry, when the modalities of the filter could not be loaded", async () => {
    const user = userEvent.setup();
    route("get", GRADES, organizationPage("grades", [grade({ name: "Primero" })]));
    route("get", MODALITIES, problem("internal-error", 500), organizationPage("modalities", [PRIMARY]));
    renderOrganization("/organization/grades");

    const warning = await screen.findByText(admin.organization.common.optionsFailed, undefined, TIMEOUT);
    expect(warning).toBeInTheDocument();
    // The list itself still loads: a lookup that fails is a partial error.
    expect(await screen.findByRole("row", { name: /Primero/ })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("option", { name: "Primaria" })).toBeInTheDocument();
  });
});

describe("creating, editing and deactivating a grade", () => {
  it("creates a grade in an active modality: the modality, the name and the order as a number", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", GRADES, organizationRecord("grades", grade({ name: "Segundo", ordinal: 2, modalityId: PRIMARY.id }), 201));
    await user.click(screen.getByRole("button", { name: "Nuevo grado" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo grado" });
    // A modality that is inactive is not offered: the server would refuse it.
    expect(within(within(dialog).getByLabelText(/Modalidad/)).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Elija una opción",
      "Primaria",
      "Bachillerato",
    ]);
    await user.selectOptions(within(dialog).getByLabelText(/Modalidad/), PRIMARY.id);
    await user.type(within(dialog).getByLabelText(/Nombre/), "Segundo");
    await user.type(within(dialog).getByLabelText(/Orden/), "2");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Segundo».")).toBeInTheDocument();
    expect(JSON.parse(writes("POST")[0]?.body ?? "{}")).toEqual({ modalityId: PRIMARY.id, name: "Segundo", ordinal: 2 });
  });

  it("refuses an order that is not a whole number, and an empty form, before sending", async () => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Nuevo grado" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo grado" });
    await user.type(within(dialog).getByLabelText(/Orden/), "dos");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 3 campos con errores.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Orden/)).toHaveAccessibleDescription("Ingrese un número entero de cero en adelante.");
    expect(within(dialog).getByLabelText(/Modalidad/)).toHaveAccessibleDescription("Este dato es obligatorio.");
    expect(writes("POST")).toHaveLength(0);
  });

  it.each([
    ["duplicate-name", admin.problem.duplicateName],
    ["inactive-reference", admin.problem.inactiveReference],
  ] as const)("tells the %s answer in its own words", async (code, message) => {
    const user = userEvent.setup();
    await openList([]);
    route("post", GRADES, problem(code, 409));
    await user.click(screen.getByRole("button", { name: "Nuevo grado" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo grado" });
    await user.selectOptions(within(dialog).getByLabelText(/Modalidad/), PRIMARY.id);
    await user.type(within(dialog).getByLabelText(/Nombre/), "Primero");
    await user.type(within(dialog).getByLabelText(/Orden/), "1");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
  });

  it("shows the modality of an existing grade as a value that cannot be changed, and edits the rest in one PUT", async () => {
    const user = userEvent.setup();
    const row = grade({ name: "Primero", ordinal: 1, modalityId: PRIMARY.id });
    await openList([row]);
    await screen.findByText("Primaria", { selector: "td" });
    route("put", `${GRADES}/${row.id}`, organizationRecord("grades", { ...row, ordinal: 5 }));
    await user.click(screen.getByRole("button", { name: "Editar Primero" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar grado" });

    const fixed = within(dialog).getByLabelText(/Modalidad/);
    expect(fixed).toHaveValue("Primaria");
    expect(fixed).toHaveAttribute("readonly");
    expect(fixed).toHaveAccessibleDescription(admin.organization.common.fixedNote);
    await user.clear(within(dialog).getByLabelText(/Orden/));
    await user.type(within(dialog).getByLabelText(/Orden/), "5");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se guardaron los cambios de «Primero».")).toBeInTheDocument();
    // The modality is not in the body: it is fixed once the grade exists.
    expect(JSON.parse(writes("PUT")[0]?.body ?? "{}")).toEqual({ name: "Primero", ordinal: 5, isActive: true });
  });

  it("deactivates from the row and tells a 403 as no permission", async () => {
    const user = userEvent.setup();
    const row = grade({ name: "Primero", ordinal: 1, modalityId: PRIMARY.id });
    await openList([row]);
    route("put", `${GRADES}/${row.id}`, problem("forbidden", 403));

    await user.click(screen.getByRole("button", { name: "Desactivar Primero" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("No tiene permiso para realizar esta acción.");
    expect(JSON.parse(writes("PUT")[0]?.body ?? "{}")).toEqual({ name: "Primero", ordinal: 1, isActive: false });
  });
});

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import admin from "../../../i18n/locales/es-HN/admin.json";
import { modality, organizationPage, organizationRecord, problem } from "../../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { renderOrganization } from "../../../test/render-organization.tsx";

// The modalities over the real routes, the real query layer and the real transport.
installMswServer();

const MODALITIES = "/api/v1/organization/modalities";
const TIMEOUT = { timeout: 10_000 };

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === MODALITIES);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT") => requests().filter((sent) => sent.method === method);

async function openList(rows: unknown[] = [modality({ code: "PRI", name: "Primaria" })]) {
  route("get", MODALITIES, organizationPage("modalities", rows));
  renderOrganization("/organization/modalities");
  const table = await screen.findByRole("table", { name: "Modalidades de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
}

describe("the list of modalities", () => {
  it("shows each modality with its code, name and state, and asks the server for the first page", async () => {
    await openList([modality({ code: "PRI", name: "Primaria" }), modality({ code: "BAC", name: "Bachillerato", isActive: false })]);

    const first = screen.getByRole("row", { name: /Primaria/ });
    expect(within(first).getByText("PRI")).toBeInTheDocument();
    expect(within(first).getByText("Activo")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Bachillerato/ })).getByText("Inactivo")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
  });

  it("asks the server for the order of the code column", async () => {
    const user = userEvent.setup();
    await openList();

    await user.click(screen.getByRole("button", { name: "Código" }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("code,asc");
    });
  });

  it("shows the empty state with the way forward", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay modalidades")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear la primera modalidad" })).toBeInTheDocument();
  });

  it("shows the error state and loads the list on retry", async () => {
    const user = userEvent.setup();
    route("get", MODALITIES, problem("internal-error", 500), organizationPage("modalities", [modality({ name: "Primaria" })]));
    renderOrganization("/organization/modalities");

    expect(await screen.findByText("No se pudo cargar la lista de modalidades", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /Primaria/ })).toBeInTheDocument();
  });
});

describe("creating, editing and deactivating a modality", () => {
  it("creates a modality with its code and name, and lists it", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", MODALITIES, organizationRecord("modalities", modality({ code: "BAC", name: "Bachillerato" }), 201));
    route("get", MODALITIES, organizationPage("modalities", [modality({ code: "BAC", name: "Bachillerato" })]));
    await user.click(screen.getByRole("button", { name: "Nueva modalidad" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva modalidad" });
    await user.type(within(dialog).getByLabelText(/Código/), "BAC");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Bachillerato");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Bachillerato».")).toBeInTheDocument();
    expect(JSON.parse(writes("POST")[0]?.body ?? "{}")).toEqual({ code: "BAC", name: "Bachillerato" });
    expect(await screen.findByRole("row", { name: /Bachillerato/ })).toBeInTheDocument();
  });

  it("refuses an empty code and name before sending", async () => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Nueva modalidad" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva modalidad" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 2 campos con errores.")).toBeInTheDocument();
    expect(writes("POST")).toHaveLength(0);
  });

  it("tells a repeated code in its own words", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", MODALITIES, problem("duplicate-name", 409));
    await user.click(screen.getByRole("button", { name: "Nueva modalidad" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva modalidad" });
    await user.type(within(dialog).getByLabelText(/Código/), "PRI");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Primaria");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.duplicateName);
  });

  it("tells a 403 as no permission", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", MODALITIES, problem("forbidden", 403));
    await user.click(screen.getByRole("button", { name: "Nueva modalidad" }));
    const dialog = await screen.findByRole("dialog", { name: "Nueva modalidad" });
    await user.type(within(dialog).getByLabelText(/Código/), "PRI");
    await user.type(within(dialog).getByLabelText(/Nombre/), "Primaria");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("No tiene permiso para realizar esta acción.");
  });

  it("edits with every editable field and the state, in one PUT", async () => {
    const user = userEvent.setup();
    const row = modality({ code: "PRI", name: "Primaria" });
    await openList([row]);
    route("put", `${MODALITIES}/${row.id}`, organizationRecord("modalities", { ...row, name: "Primaria regular", isActive: false }));
    await user.click(screen.getByRole("button", { name: "Editar Primaria" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar modalidad" });
    await user.clear(within(dialog).getByLabelText(/Nombre/));
    await user.type(within(dialog).getByLabelText(/Nombre/), "Primaria regular");
    await user.selectOptions(within(dialog).getByLabelText("Estado"), "false");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se guardaron los cambios de «Primaria regular».")).toBeInTheDocument();
    expect(JSON.parse(writes("PUT")[0]?.body ?? "{}")).toEqual({ code: "PRI", name: "Primaria regular", isActive: false });
  });

  it("deactivates from the row with a PUT that keeps the code and name", async () => {
    const user = userEvent.setup();
    const row = modality({ code: "PRI", name: "Primaria" });
    await openList([row]);
    route("put", `${MODALITIES}/${row.id}`, organizationRecord("modalities", { ...row, isActive: false }));

    await user.click(screen.getByRole("button", { name: "Desactivar Primaria" }));

    expect(await screen.findByText("Se desactivó «Primaria».")).toBeInTheDocument();
    expect(JSON.parse(writes("PUT")[0]?.body ?? "{}")).toEqual({ code: "PRI", name: "Primaria", isActive: false });
  });
});

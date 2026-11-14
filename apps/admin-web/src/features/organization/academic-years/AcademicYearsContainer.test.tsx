import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import admin from "../../../i18n/locales/es-HN/admin.json";
import { academicYear, organizationPage, organizationRecord, problem } from "../../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { renderOrganization, TEST_TOKEN } from "../../../test/render-organization.tsx";

// The academic years over the real routes, the real query layer and the real transport, with the test server
// answering. The flows of a container: the list, paging and order in the request, the form, the business errors,
// the 403, and the states of the load.
installMswServer();

const YEARS = "/api/v1/organization/academic-years";
const OPEN = "/organization/academic-years";
const TIMEOUT = { timeout: 10_000 };

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === YEARS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT") => requests().filter((sent) => sent.method === method);

function formatted(year: number, month: number, day: number): string {
  return new Intl.DateTimeFormat("es-HN", { dateStyle: "medium", timeZone: "UTC" }).format(Date.UTC(year, month - 1, day));
}

/** The table is on screen and no longer loading: its rows, or its empty state, are there. */
async function loaded() {
  const table = await screen.findByRole("table", { name: "Años lectivos de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
}

async function openList(rows: unknown[] = [academicYear({ name: "Año 2026", status: "ACTIVE" })], paging?: { total?: number }) {
  route("get", YEARS, organizationPage("academic-years", rows, paging));
  const app = renderOrganization(OPEN);
  await loaded();
  return app;
}

async function fillNewYear(user: ReturnType<typeof userEvent.setup>, name = "Año 2027") {
  await user.click(screen.getByRole("button", { name: "Nuevo año lectivo" }));
  const dialog = await screen.findByRole("dialog", { name: "Nuevo año lectivo" });
  await user.type(within(dialog).getByLabelText(/Nombre/), name);
  fireEvent.change(within(dialog).getByLabelText(/Fecha de inicio/), { target: { value: "2027-01-15" } });
  fireEvent.change(within(dialog).getByLabelText(/Fecha de fin/), { target: { value: "2027-11-30" } });
  return dialog;
}

describe("the list of academic years", () => {
  it("shows each year with its dates written for the person, its stage and its state, and asks for the first page", async () => {
    await openList([
      academicYear({ name: "Año 2026", startDate: "2026-01-15", endDate: "2026-11-30", status: "ACTIVE" }),
      academicYear({ name: "Año 2025", status: "CLOSED", isActive: false }),
    ]);

    const table = screen.getByRole("table", { name: "Años lectivos de la institución" });
    const first = within(table).getByRole("row", { name: /Año 2026/ });
    expect(within(first).getByText(formatted(2026, 1, 15))).toBeInTheDocument();
    expect(within(first).getByText(formatted(2026, 11, 30))).toBeInTheDocument();
    expect(within(first).getByText("En curso")).toBeInTheDocument();
    expect(within(first).getByText("Activo")).toBeInTheDocument();
    const second = within(table).getByRole("row", { name: /Año 2025/ });
    expect(within(second).getByText("Cerrado")).toBeInTheDocument();
    expect(within(second).getByText("Inactivo")).toBeInTheDocument();
    // Paging and order are the server's: the request carries page and size, and no sort until the person asks.
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("sort")).toBe(false);
    expect(gets()[0]?.headers.get("Authorization")).toBe(`Bearer ${TEST_TOKEN}`);
  });

  it("asks the server for the next page and writes it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList([academicYear()], { total: 60 });
    route("get", YEARS, organizationPage("academic-years", [academicYear({ name: "Año 2024" })], { page: 1, total: 60 }));

    await user.click(screen.getByRole("button", { name: "Siguiente" }));

    expect(await screen.findByRole("row", { name: /Año 2024/ })).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("1");
    expect(router.state.location.searchStr).toBe("?page=2");
  });

  it("asks the server for each order, ascending first and then descending, and marks the column", async () => {
    const user = userEvent.setup();
    await openList();

    await user.click(screen.getByRole("button", { name: "Nombre" }));
    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("name,asc");
    });
    expect(screen.getByRole("columnheader", { name: "Nombre" })).toHaveAttribute("aria-sort", "ascending");

    await user.click(screen.getByRole("button", { name: "Nombre" }));
    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("name,desc");
    });
    expect(screen.getByRole("columnheader", { name: "Nombre" })).toHaveAttribute("aria-sort", "descending");
  });

  it("opens the page, size and order the address names, and drops what does not hold", async () => {
    route("get", YEARS, organizationPage("academic-years", [academicYear()]));
    renderOrganization(`${OPEN}?page=3&pageSize=50&sort=name:desc`);
    await loaded();

    expect(lastQuery().get("page")).toBe("2");
    expect(lastQuery().get("size")).toBe("50");
    expect(lastQuery().get("sort")).toBe("name,desc");
  });

  it("falls back to the defaults for a parameter that does not hold", async () => {
    route("get", YEARS, organizationPage("academic-years", [academicYear()]));
    renderOrganization(`${OPEN}?page=0&pageSize=7&sort=password:asc`);
    await loaded();

    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("sort")).toBe(false);
  });

  it("shows the empty state with the way forward when no year exists, and the action opens the form", async () => {
    const user = userEvent.setup();
    await openList([]);

    expect(screen.getByText("Todavía no hay años lectivos")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Crear el primer año lectivo" }));

    expect(await screen.findByRole("dialog", { name: "Nuevo año lectivo" })).toBeInTheDocument();
  });

  it("shows the error state with the trace id and a retry that loads the list", async () => {
    const user = userEvent.setup();
    route(
      "get",
      YEARS,
      problem("internal-error", 500, { traceId: "trace-abc-123" }),
      organizationPage("academic-years", [academicYear({ name: "Año 2026" })]),
    );
    renderOrganization(OPEN);

    expect(await screen.findByText("No se pudo cargar la lista de años lectivos", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Identificador para soporte: trace-abc-123");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /Año 2026/ })).toBeInTheDocument();
    expect(gets()).toHaveLength(2);
  });

  it("tells a 403 on the read as no permission, in plain words", async () => {
    route("get", YEARS, problem("forbidden", 403));
    renderOrganization(OPEN);

    expect(await screen.findByRole("alert", undefined, TIMEOUT)).toHaveTextContent(admin.problem.forbidden);
  });
});

describe("creating an academic year", () => {
  it("sends the year, closes the form, says so, reloads the list and returns the focus to the button", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", YEARS, organizationRecord("academic-years", academicYear({ name: "Año 2027" }), 201));
    route("get", YEARS, organizationPage("academic-years", [academicYear({ name: "Año 2027" })]));
    const dialog = await fillNewYear(user);
    // The first field takes the focus when the form opens.
    expect(within(dialog).getByLabelText(/Nombre/)).toHaveFocus();

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Año 2027».")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    const [sent] = writes("POST");
    expect(sent?.path).toBe(YEARS);
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({ name: "Año 2027", startDate: "2027-01-15", endDate: "2027-11-30", status: "PLANNED" });
    expect(sent?.headers.get("Authorization")).toBe(`Bearer ${TEST_TOKEN}`);
    // It moves no money: there is no idempotency key to send.
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
    expect(await screen.findByRole("row", { name: /Año 2027/ })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Nuevo año lectivo" })).toHaveFocus();
    });
  });

  it("offers PLANNED and in progress to a new year, and the three stages to an existing one", async () => {
    const user = userEvent.setup();
    await openList([academicYear({ name: "Año 2026" })]);

    await user.click(screen.getByRole("button", { name: "Nuevo año lectivo" }));
    const created = await screen.findByRole("dialog", { name: "Nuevo año lectivo" });
    expect(within(within(created).getByLabelText(/Etapa/)).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Planificado",
      "En curso",
    ]);
    expect(within(created).queryByLabelText("Estado")).not.toBeInTheDocument();
    await user.keyboard("{Escape}");

    await user.click(await screen.findByRole("button", { name: "Editar Año 2026" }));
    const edited = await screen.findByRole("dialog", { name: "Editar año lectivo" });
    expect(within(within(edited).getByLabelText(/Etapa/)).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Planificado",
      "En curso",
      "Cerrado",
    ]);
    expect(within(edited).getByLabelText("Estado")).toHaveValue("true");
  });

  it("refuses an empty form before sending, names each field and sends nothing", async () => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Nuevo año lectivo" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo año lectivo" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 3 campos con errores.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Nombre/)).toHaveAccessibleDescription("Este dato es obligatorio.");
    expect(within(dialog).getByLabelText(/Fecha de inicio/)).toBeInvalid();
    expect(within(dialog).getByLabelText(/Fecha de fin/)).toBeInvalid();
    expect(writes("POST")).toHaveLength(0);
  });

  it("refuses an end that is not after the start, in the end field", async () => {
    const user = userEvent.setup();
    await openList([]);
    const dialog = await fillNewYear(user);
    fireEvent.change(within(dialog).getByLabelText(/Fecha de fin/), { target: { value: "2027-01-15" } });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findAllByText("La fecha de fin debe ser posterior a la de inicio.")).not.toHaveLength(0);
    expect(within(dialog).getByLabelText(/Fecha de fin/)).toBeInvalid();
    expect(writes("POST")).toHaveLength(0);
  });

  it("puts the errors of a 400 under their fields, keeps what was typed and moves the focus to the summary", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", YEARS, problem("validation-failed", 400, { errors: [{ field: "name", reason: "size" }] }));
    const dialog = await fillNewYear(user, "Un nombre");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 1 campo con error.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Nombre/)).toHaveAccessibleDescription("Este dato es demasiado largo.");
    expect(within(dialog).getByLabelText(/Nombre/)).toHaveValue("Un nombre");
    expect(within(dialog).getByRole("group", { name: "Hay 1 campo con error." })).toHaveFocus();
  });

  it.each([
    ["academic-year-overlap", 409, admin.problem.academicYearOverlap],
    ["academic-year-already-active", 409, admin.problem.academicYearAlreadyActive],
    ["academic-year-closed", 409, admin.problem.academicYearClosed],
    ["duplicate-name", 409, admin.problem.duplicateName],
    ["inactive-reference", 409, admin.problem.inactiveReference],
    ["forbidden", 403, admin.problem.forbidden],
    ["resource-not-found", 404, admin.problem.notFound],
  ] as const)("tells the %s answer in its own words, in the form that sent it", async (code, status, message) => {
    const user = userEvent.setup();
    await openList([]);
    route("post", YEARS, problem(code, status, { traceId: "trace-xyz" }));
    const dialog = await fillNewYear(user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(message);
    expect(alert).toHaveTextContent("Identificador para soporte: trace-xyz");
    expect(alert).toHaveFocus();
    // The form stays open with what was typed, and a retry is possible.
    expect(within(dialog).getByLabelText(/Nombre/)).toHaveValue("Año 2027");
    expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeEnabled();
  });

  it("closes with Escape and returns the focus to the button that opened it", async () => {
    const user = userEvent.setup();
    await openList([]);

    await user.click(screen.getByRole("button", { name: "Nuevo año lectivo" }));
    await screen.findByRole("dialog", { name: "Nuevo año lectivo" });
    await user.keyboard("{Escape}");

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Nuevo año lectivo" })).toHaveFocus();
    });
  });
});

describe("editing and deactivating an academic year", () => {
  it("replaces every editable field and includes the state", async () => {
    const user = userEvent.setup();
    const year = academicYear({ name: "Año 2026", startDate: "2026-01-15", endDate: "2026-11-30", status: "PLANNED" });
    await openList([year]);
    route("put", `${YEARS}/${year.id}`, organizationRecord("academic-years", { ...year, name: "Año escolar 2026" }));
    await user.click(screen.getByRole("button", { name: "Editar Año 2026" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar año lectivo" });
    expect(within(dialog).getByLabelText(/Nombre/)).toHaveValue("Año 2026");
    await user.clear(within(dialog).getByLabelText(/Nombre/));
    await user.type(within(dialog).getByLabelText(/Nombre/), "Año escolar 2026");
    await user.selectOptions(within(dialog).getByLabelText(/Etapa/), "ACTIVE");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se guardaron los cambios de «Año escolar 2026».")).toBeInTheDocument();
    const [sent] = writes("PUT");
    expect(sent?.path).toBe(`${YEARS}/${year.id}`);
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({
      name: "Año escolar 2026",
      startDate: "2026-01-15",
      endDate: "2026-11-30",
      status: "ACTIVE",
      isActive: true,
    });
  });

  it("surfaces the server's refusal to edit a closed year", async () => {
    const user = userEvent.setup();
    const year = academicYear({ name: "Año 2025", status: "CLOSED" });
    await openList([year]);
    route("put", `${YEARS}/${year.id}`, problem("academic-year-closed", 409));
    await user.click(screen.getByRole("button", { name: "Editar Año 2025" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar año lectivo" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.academicYearClosed);
  });

  it("deactivates a year with one PUT that carries every editable field, and reloads the list", async () => {
    const user = userEvent.setup();
    const year = academicYear({ name: "Año 2026", status: "ACTIVE" });
    await openList([year]);
    route("put", `${YEARS}/${year.id}`, organizationRecord("academic-years", { ...year, isActive: false }));
    route("get", YEARS, organizationPage("academic-years", [{ ...year, isActive: false }]));

    await user.click(screen.getByRole("button", { name: "Desactivar Año 2026" }));

    expect(await screen.findByText("Se desactivó «Año 2026».")).toBeInTheDocument();
    expect(JSON.parse(writes("PUT")[0]?.body ?? "{}")).toEqual({
      name: "Año 2026",
      startDate: year.startDate,
      endDate: year.endDate,
      status: "ACTIVE",
      isActive: false,
    });
    expect(await screen.findByRole("button", { name: "Activar Año 2026" })).toBeInTheDocument();
  });

  it("tells a 403 on a deactivation as no permission and leaves the year as it was", async () => {
    const user = userEvent.setup();
    const year = academicYear({ name: "Año 2026" });
    await openList([year]);
    route("put", `${YEARS}/${year.id}`, problem("forbidden", 403));

    await user.click(screen.getByRole("button", { name: "Desactivar Año 2026" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(admin.problem.forbidden);
    expect(screen.getByRole("button", { name: "Desactivar Año 2026" })).toBeEnabled();
    expect(within(screen.getByRole("row", { name: /Año 2026/ })).getByText("Activo")).toBeInTheDocument();
  });
});

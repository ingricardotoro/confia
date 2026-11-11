import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { problem, student, studentPage, studentRecord } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderFeature, TEST_TOKEN } from "../../test/render-feature.tsx";
import { studentsFeature } from "./feature.tsx";

// The students registry over the real routes, the real query layer and the real transport, with the test server
// answering. The flows of the container: the list, the search that sends `q` only from two characters, paging and order
// in the request, the status filter, the form (create and the three ways to treat the document on an edit), the business
// errors, the 403, the states of the load, and that the national id leaves no trace once it is sent.
installMswServer();

const STUDENTS = "/api/v1/students";
const OPEN = "/students";
const TIMEOUT = { timeout: 10_000 };
/** A document typed in a test. It must never appear in the address, the DOM after a save, the caches or a log. */
const DOCUMENT = "0801201412345";
const OTHER_DOCUMENT = "1503199998765";

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === STUDENTS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT") => requests().filter((sent) => sent.method === method);
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

afterEach(() => {
  vi.restoreAllMocks();
});

function formatted(year: number, month: number, day: number): string {
  return new Intl.DateTimeFormat("es-HN", { dateStyle: "medium", timeZone: "UTC" }).format(Date.UTC(year, month - 1, day));
}

/** The table is on screen and no longer loading: its rows, or its empty state, are there. */
async function loaded() {
  const table = await screen.findByRole("table", { name: "Estudiantes de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
}

async function openList(rows: unknown[] = [student()], options: { path?: string; total?: number } = {}) {
  route("get", STUDENTS, studentPage(rows, options.total === undefined ? {} : { total: options.total }));
  const app = renderFeature(studentsFeature, options.path ?? OPEN);
  await loaded();
  return app;
}

const ANA = student({ firstName: "Ana", lastName: "Pérez", studentCode: "E-000001" });
const WITH_DOCUMENT = student({ firstName: "Luis", lastName: "Mejía", studentCode: "E-000002", hasNationalId: true, nationalIdMasked: "****2345" });

async function fillNewStudent(user: ReturnType<typeof userEvent.setup>, extra: { code?: string; document?: string } = {}) {
  await user.click(screen.getByRole("button", { name: "Nuevo estudiante" }));
  const dialog = await screen.findByRole("dialog", { name: "Nuevo estudiante" });
  if (extra.code !== undefined) await user.type(within(dialog).getByLabelText(/^Código/), extra.code);
  await user.type(within(dialog).getByLabelText(/Nombres/), "Ana");
  await user.type(within(dialog).getByLabelText(/Apellidos/), "Pérez");
  fireEvent.change(within(dialog).getByLabelText(/Fecha de nacimiento/), { target: { value: "2014-03-09" } });
  await user.selectOptions(within(dialog).getByLabelText(/Sexo/), "F");
  if (extra.document !== undefined) await user.type(within(dialog).getByLabelText(/Documento de identidad/), extra.document);
  return dialog;
}

describe("the list of students", () => {
  it("shows each student with the date written for the person, the sex, the masked document or a dash, and the state", async () => {
    await openList([
      student({ firstName: "Ana", lastName: "Pérez", birthDate: "2014-03-09", studentCode: "E-000001" }),
      student({ firstName: "Luis", lastName: "Mejía", sex: "M", status: "INACTIVE", hasNationalId: true, nationalIdMasked: "****2345", studentCode: "E-000002" }),
    ]);

    const table = screen.getByRole("table", { name: "Estudiantes de la institución" });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Código",
      "Apellidos",
      "Nombres",
      "Fecha de nacimiento",
      "Sexo",
      "Documento",
      "Estado",
      "Acciones",
    ]);
    const ana = within(table).getByRole("row", { name: /Pérez/ });
    expect(within(ana).getByText("E-000001")).toBeInTheDocument();
    expect(within(ana).getByText(formatted(2014, 3, 9))).toBeInTheDocument();
    expect(within(ana).getByText("Femenino")).toBeInTheDocument();
    expect(within(ana).getByText("—")).toBeInTheDocument();
    expect(within(ana).getByText("Sin documento")).toBeInTheDocument();
    expect(within(ana).getByText("Activo")).toBeInTheDocument();
    const luis = within(table).getByRole("row", { name: /Mejía/ });
    expect(within(luis).getByText("****2345")).toBeInTheDocument();
    expect(within(luis).getByText("Masculino")).toBeInTheDocument();
    expect(within(luis).getByText("Inactivo")).toBeInTheDocument();
  });

  it("asks the server for the first page, ordered by last name by default, with no text and no status", async () => {
    await openList();

    expect(Object.fromEntries(lastQuery())).toEqual({ page: "0", size: "25" });
    expect(requests()[0]?.headers.get("Authorization")).toBe(`Bearer ${TEST_TOKEN}`);
    expect(screen.getByRole("columnheader", { name: "Apellidos" })).toHaveAttribute("aria-sort", "ascending");
  });

  it("asks the server for the next page and writes it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList([ANA], { total: 60 });
    route("get", STUDENTS, studentPage([student({ lastName: "Zelaya" })], { page: 1, total: 60 }));

    await user.click(screen.getByRole("button", { name: "Siguiente" }));

    expect(await screen.findByRole("row", { name: /Zelaya/ })).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("1");
    expect(router.state.location.searchStr).toBe("?page=2");
  });

  it("asks the server for each order of a sortable column, ascending first and then descending", async () => {
    const user = userEvent.setup();
    const { router } = await openList();

    await user.click(screen.getByRole("button", { name: "Fecha de nacimiento" }));
    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("birthDate,asc");
    });
    expect(screen.getByRole("columnheader", { name: "Fecha de nacimiento" })).toHaveAttribute("aria-sort", "ascending");
    await user.click(screen.getByRole("button", { name: "Fecha de nacimiento" }));
    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("birthDate,desc");
    });
    expect(router.state.location.searchStr).toBe("?sort=birthDate%3Adesc");
    // The columns the server cannot order by are not buttons.
    expect(screen.queryByRole("button", { name: "Sexo" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Documento" })).not.toBeInTheDocument();
  });

  it("opens with the page, size, order, text and status of the address, and drops what does not hold", async () => {
    await openList([ANA], { path: `${OPEN}?page=3&pageSize=50&sort=lastName:desc&q=per&status=INACTIVE` });

    expect(Object.fromEntries(lastQuery())).toEqual({ page: "2", size: "50", sort: "lastName,desc", q: "per", status: "INACTIVE" });
    expect(screen.getByLabelText("Buscar")).toHaveValue("per");
    expect(screen.getByLabelText("Estado", { selector: "select" })).toHaveValue("INACTIVE");
  });

  it("ignores a sort the API does not accept, a short text and a status it does not know", async () => {
    await openList([ANA], { path: `${OPEN}?sort=nationalId:asc&q=a&status=DELETED&pageSize=7` });

    expect(Object.fromEntries(lastQuery())).toEqual({ page: "0", size: "25" });
  });

  it("filters by status in the server, writes it in the address and starts from the first page", async () => {
    const user = userEvent.setup();
    const { router } = await openList([ANA], { path: `${OPEN}?page=2` });
    route("get", STUDENTS, studentPage([student({ lastName: "Inactivo", status: "INACTIVE" })]));

    await user.selectOptions(screen.getByLabelText("Estado", { selector: "select" }), "INACTIVE");

    expect(await screen.findByRole("row", { name: /Inactivo/ })).toBeInTheDocument();
    expect(lastQuery().get("status")).toBe("INACTIVE");
    expect(lastQuery().get("page")).toBe("0");
    expect(router.state.location.searchStr).toBe("?status=INACTIVE");
  });
});

describe("the search by text", () => {
  it("sends nothing while one character is typed and says that two are needed", async () => {
    const user = userEvent.setup();
    await openList();
    const before = gets().length;

    await user.type(screen.getByLabelText("Buscar"), "p");
    expect(screen.getByLabelText("Buscar")).toHaveAccessibleDescription("Escriba al menos 2 caracteres para buscar.");
    await new Promise((resolve) => setTimeout(resolve, 700));

    expect(gets()).toHaveLength(before);
    expect(lastQuery().has("q")).toBe(false);
  });

  it("sends q from two characters, after a pause, once, writes it in the address and starts from the first page", async () => {
    const user = userEvent.setup();
    const { router } = await openList([ANA], { path: `${OPEN}?page=2&pageSize=50` });
    route("get", STUDENTS, studentPage([student({ lastName: "Pereira" })]));
    const before = gets().length;

    await user.type(screen.getByLabelText("Buscar"), "per");

    expect(await screen.findByRole("row", { name: /Pereira/ }, TIMEOUT)).toBeInTheDocument();
    // The letters were typed one after another and only the pause sent a request: one, for the whole text.
    expect(gets()).toHaveLength(before + 1);
    expect(lastQuery().get("q")).toBe("per");
    expect(lastQuery().get("page")).toBe("0");
    expect(router.state.location.searchStr).toBe("?pageSize=50&q=per");
    expect(screen.getByLabelText("Buscar")).toHaveAccessibleDescription("Por nombre, apellido o código.");
  });

  it("trims the text it sends and drops q when the box is emptied or goes back to one character", async () => {
    const user = userEvent.setup();
    const { router } = await openList([ANA], { path: `${OPEN}?q=per` });
    const box = screen.getByLabelText("Buscar");
    route("get", STUDENTS, studentPage([ANA]));

    await user.clear(box);
    await user.type(box, "  ab  ");
    await waitFor(() => {
      expect(lastQuery().get("q")).toBe("ab");
    }, TIMEOUT);
    await user.clear(box);
    await user.type(box, "a");

    await waitFor(() => {
      expect(lastQuery().has("q")).toBe(false);
    }, TIMEOUT);
    expect(router.state.location.searchStr).toBe("");
  });

  it("follows the address when it changes without the box: removing the filters empties it", async () => {
    const user = userEvent.setup();
    await openList([], { path: `${OPEN}?q=zzz&status=ACTIVE` });
    expect(screen.getByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    route("get", STUDENTS, studentPage([ANA]));

    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));

    expect(await screen.findByRole("row", { name: /Pérez/ })).toBeInTheDocument();
    expect(lastQuery().has("q")).toBe(false);
    expect(lastQuery().has("status")).toBe(false);
    expect(screen.getByLabelText("Buscar")).toHaveValue("");
  });
});

describe("the states of the list", () => {
  it("shows the empty state of a registry that has no students, with the way to create the first", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay estudiantes")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Registrar al primer estudiante" })).toBeInTheDocument();
  });

  it("shows the error state with the trace id and a retry that loads the list", async () => {
    const user = userEvent.setup();
    route("get", STUDENTS, problem("internal-error", 500, { traceId: "trace-abc-123" }), studentPage([ANA]));
    renderFeature(studentsFeature, OPEN);

    expect(await screen.findByText("No se pudo cargar la lista de estudiantes", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Identificador para soporte: trace-abc-123");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /Pérez/ })).toBeInTheDocument();
    expect(gets()).toHaveLength(2);
  });

  it("tells a 403 on the read as no permission, in plain words", async () => {
    route("get", STUDENTS, problem("forbidden", 403));
    renderFeature(studentsFeature, OPEN);

    expect(await screen.findByRole("alert", undefined, TIMEOUT)).toHaveTextContent(admin.problem.forbidden);
  });

  it("shows the loading state with the shape of the table before the answer", async () => {
    route("get", STUDENTS, studentPage([ANA]));
    renderFeature(studentsFeature, OPEN);

    expect(await screen.findByRole("table", { name: "Estudiantes de la institución" }, TIMEOUT)).toHaveAttribute("aria-busy", "true");
    expect(await screen.findByRole("row", { name: /Pérez/ })).toBeInTheDocument();
  });
});

describe("registering a student", () => {
  it("creates a student without a code (the system generates it) and says so in the form", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", STUDENTS, studentRecord(student({ studentCode: "E-000007" }), 201));
    await user.click(screen.getByRole("button", { name: "Nuevo estudiante" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo estudiante" });
    expect(within(dialog).getByLabelText(/^Código/)).toHaveAccessibleDescription("Si lo deja vacío, el sistema genera uno.");
    await user.keyboard("{Escape}");

    const filled = await fillNewStudent(user);
    await user.click(within(filled).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Ana Pérez».")).toBeInTheDocument();
    expect(bodyOf(writes("POST")[0])).toEqual({ firstName: "Ana", lastName: "Pérez", birthDate: "2014-03-09", sex: "F" });
    expect(writes("POST")[0]?.headers.has("Idempotency-Key")).toBe(false);
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });

  it("sends the code and the document when they are typed, and the list is read again", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", STUDENTS, studentRecord(student({ studentCode: "E-77", hasNationalId: true, nationalIdMasked: "****2345" }), 201));
    const dialog = await fillNewStudent(user, { code: "E-77", document: DOCUMENT });
    const readsBefore = gets().length;

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Ana Pérez».")).toBeInTheDocument();
    expect(bodyOf(writes("POST")[0])).toEqual({
      studentCode: "E-77",
      firstName: "Ana",
      lastName: "Pérez",
      birthDate: "2014-03-09",
      sex: "F",
      nationalId: DOCUMENT,
    });
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(readsBefore);
    });
  });

  it("leaves no trace of the national id once it is sent: not in the page, the address, the caches or the console", async () => {
    const user = userEvent.setup();
    const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const info = vi.spyOn(console, "info").mockImplementation(() => undefined);
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { router, queryClient, container } = await openList([]);
    route("post", STUDENTS, studentRecord(student({ hasNationalId: true, nationalIdMasked: "****2345" }), 201));
    const dialog = await fillNewStudent(user, { document: DOCUMENT });
    // While it is typed it is in the field and nowhere else.
    expect(router.state.location.searchStr).not.toContain(DOCUMENT);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await screen.findByText("Se creó el registro «Ana Pérez».");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(document.body.innerHTML).not.toContain(DOCUMENT);
    expect(container.innerHTML).not.toContain(DOCUMENT);
    for (const field of [...screen.queryAllByRole("textbox"), ...screen.queryAllByRole("searchbox")]) expect(field).not.toHaveValue(DOCUMENT);
    expect(router.state.location.searchStr).not.toContain(DOCUMENT);
    expect(JSON.stringify(queryClient.getQueryCache().getAll().map((query) => [query.queryKey, query.state.data]))).not.toContain(DOCUMENT);
    // The mutation that carried it is not kept either.
    await waitFor(() => {
      expect(queryClient.getMutationCache().getAll()).toHaveLength(0);
    });
    expect(JSON.stringify([log.mock.calls, info.mock.calls, warn.mock.calls, error.mock.calls])).not.toContain(DOCUMENT);
  });

  it("a new form after a save starts empty: the document of the last one is not there", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", STUDENTS, studentRecord(student(), 201));
    const dialog = await fillNewStudent(user, { document: DOCUMENT });
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await screen.findByText("Se creó el registro «Ana Pérez».");

    await user.click(screen.getByRole("button", { name: "Nuevo estudiante" }));

    const again = await screen.findByRole("dialog", { name: "Nuevo estudiante" });
    expect(within(again).getByLabelText(/Documento de identidad/)).toHaveValue("");
    expect(within(again).getByLabelText(/Nombres/)).toHaveValue("");
  });

  it("refuses an empty form before sending, names each field and sends nothing", async () => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Nuevo estudiante" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo estudiante" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 4 campos con errores.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Nombres/)).toHaveAccessibleDescription("Este dato es obligatorio.");
    expect(within(dialog).getByLabelText(/Apellidos/)).toBeInvalid();
    expect(within(dialog).getByLabelText(/Fecha de nacimiento/)).toBeInvalid();
    expect(within(dialog).getByLabelText(/Sexo/)).toBeInvalid();
    expect(writes("POST")).toHaveLength(0);
  });

  it("refuses a document that is not 8 to 20 letters, digits or hyphens, with a message that says so", async () => {
    const user = userEvent.setup();
    await openList([]);
    const dialog = await fillNewStudent(user, { document: "123 45" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 1 campo con error.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Documento de identidad/)).toHaveAccessibleDescription(
      expect.stringContaining("El documento debe tener de 8 a 20 letras, números o guiones."),
    );
    expect(writes("POST")).toHaveLength(0);
  });

  it("puts the errors of a 400 under their fields, keeps what was typed and moves the focus to the summary", async () => {
    const user = userEvent.setup();
    await openList([]);
    route(
      "post",
      STUDENTS,
      problem("validation-failed", 400, {
        errors: [
          { field: "birthDate", reason: "birth-date-floor" },
          { field: "nationalId", reason: "pattern" },
        ],
      }),
    );
    const dialog = await fillNewStudent(user, { document: DOCUMENT });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 2 campos con errores.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Fecha de nacimiento/)).toHaveAccessibleDescription(
      "La fecha de nacimiento no puede ser anterior al 1 de enero de 1990.",
    );
    expect(within(dialog).getByLabelText(/Documento de identidad/)).toHaveAccessibleDescription(
      expect.stringContaining("El documento debe tener de 8 a 20 letras, números o guiones."),
    );
    expect(within(dialog).getByLabelText(/Nombres/)).toHaveValue("Ana");
    expect(within(dialog).getByRole("group", { name: "Hay 2 campos con errores." })).toHaveFocus();
  });

  it("tells a birth date in the future in its own words", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", STUDENTS, problem("validation-failed", 400, { errors: [{ field: "birthDate", reason: "past-or-present" }] }));
    const dialog = await fillNewStudent(user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText("Hay 1 campo con error.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Fecha de nacimiento/)).toHaveAccessibleDescription("La fecha de nacimiento no puede ser posterior a hoy.");
  });

  it.each([
    ["duplicate-student-code", 409, admin.problem.duplicateStudentCode],
    ["duplicate-national-id", 409, admin.problem.duplicateNationalId],
    ["forbidden", 403, admin.problem.forbidden],
    ["resource-not-found", 404, admin.problem.notFound],
  ] as const)("tells the %s answer in its own words, in the form that sent it, and keeps what was typed", async (code, status, message) => {
    const user = userEvent.setup();
    await openList([]);
    route("post", STUDENTS, problem(code, status, { traceId: "trace-xyz" }));
    const dialog = await fillNewStudent(user, { document: DOCUMENT });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(message);
    expect(alert).toHaveTextContent("Identificador para soporte: trace-xyz");
    expect(alert).toHaveFocus();
    expect(within(dialog).getByLabelText(/Nombres/)).toHaveValue("Ana");
    expect(within(dialog).getByLabelText(/Documento de identidad/)).toHaveValue(DOCUMENT);
    expect(screen.getByRole("dialog", { name: "Nuevo estudiante" })).toBeInTheDocument();
  });

  it("sends once when the person presses the button twice, and holds the dialog open while it waits", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", STUDENTS, studentRecord(student(), 201));
    const dialog = await fillNewStudent(user);
    const save = within(dialog).getByRole("button", { name: "Guardar" });

    await user.dblClick(save);

    await screen.findByText("Se creó el registro «Ana Pérez».");
    expect(writes("POST")).toHaveLength(1);
  });
});

describe("editing a student", () => {
  async function openEdit(user: ReturnType<typeof userEvent.setup>, row = WITH_DOCUMENT) {
    await openList([row]);
    await user.click(screen.getByRole("button", { name: `Editar ${row.firstName} ${row.lastName}` }));
    return screen.findByRole("dialog", { name: "Editar estudiante" });
  }

  it("shows the code as a value that cannot be changed, the document masked, and a separate box that starts empty", async () => {
    const user = userEvent.setup();
    const dialog = await openEdit(user);

    const code = within(dialog).getByLabelText(/^Código/);
    expect(code).toHaveValue("E-000002");
    expect(code).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText("Documento actual")).toHaveValue("****2345");
    expect(within(dialog).getByLabelText("Documento actual")).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText("Reemplazar documento")).toHaveValue("");
    expect(within(dialog).getByRole("checkbox", { name: "Quitar documento" })).not.toBeChecked();
    expect(within(dialog).getByLabelText(/Nombres/)).toHaveValue("Luis");
    expect(within(dialog).getByLabelText(/Fecha de nacimiento/)).toHaveValue("2014-03-09");
    expect(within(dialog).getByLabelText(/Sexo/)).toHaveValue("F");
  });

  it("keeps the document when the box is left empty: the body has no nationalId, and the code is not in it", async () => {
    const user = userEvent.setup();
    const dialog = await openEdit(user);
    route("put", `${STUDENTS}/${WITH_DOCUMENT.id}`, studentRecord({ ...WITH_DOCUMENT, lastName: "Mejía Cruz" }));
    await user.clear(within(dialog).getByLabelText(/Apellidos/));
    await user.type(within(dialog).getByLabelText(/Apellidos/), "Mejía Cruz");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se guardaron los cambios de «Luis Mejía Cruz».")).toBeInTheDocument();
    const body = bodyOf(writes("PUT")[0]);
    expect(body).toEqual({ firstName: "Luis", lastName: "Mejía Cruz", birthDate: "2014-03-09", sex: "F", status: "ACTIVE" });
    expect(body).not.toHaveProperty("nationalId");
    expect(body).not.toHaveProperty("studentCode");
  });

  it("replaces the document with the new full value, and leaves no trace of it", async () => {
    const user = userEvent.setup();
    const { router, queryClient } = await openList([WITH_DOCUMENT]);
    await user.click(screen.getByRole("button", { name: "Editar Luis Mejía" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar estudiante" });
    route("put", `${STUDENTS}/${WITH_DOCUMENT.id}`, studentRecord({ ...WITH_DOCUMENT, nationalIdMasked: "****8765" }));
    await user.type(within(dialog).getByLabelText("Reemplazar documento"), OTHER_DOCUMENT);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await screen.findByText("Se guardaron los cambios de «Luis Mejía».");
    expect(bodyOf(writes("PUT")[0])).toEqual({
      firstName: "Luis",
      lastName: "Mejía",
      birthDate: "2014-03-09",
      sex: "F",
      status: "ACTIVE",
      nationalId: OTHER_DOCUMENT,
    });
    expect(document.body.innerHTML).not.toContain(OTHER_DOCUMENT);
    expect(router.state.location.searchStr).not.toContain(OTHER_DOCUMENT);
    expect(JSON.stringify(queryClient.getQueryCache().getAll().map((query) => [query.queryKey, query.state.data]))).not.toContain(OTHER_DOCUMENT);
    await waitFor(() => {
      expect(queryClient.getMutationCache().getAll()).toHaveLength(0);
    });
  });

  it("removes the document only through the explicit checkbox, with an empty nationalId, and disables the replacement while it is checked", async () => {
    const user = userEvent.setup();
    const dialog = await openEdit(user);
    route("put", `${STUDENTS}/${WITH_DOCUMENT.id}`, studentRecord({ ...WITH_DOCUMENT, hasNationalId: false, nationalIdMasked: null }));
    await user.type(within(dialog).getByLabelText("Reemplazar documento"), OTHER_DOCUMENT);

    await user.click(within(dialog).getByRole("checkbox", { name: "Quitar documento" }));

    // Removing and replacing are different acts: what was typed to replace is dropped and the box waits.
    expect(within(dialog).getByRole("checkbox", { name: "Quitar documento" })).toBeChecked();
    expect(within(dialog).getByLabelText("Reemplazar documento")).toBeDisabled();
    expect(within(dialog).getByLabelText("Reemplazar documento")).toHaveValue("");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await screen.findByText("Se guardaron los cambios de «Luis Mejía».");
    expect(bodyOf(writes("PUT")[0])).toEqual({
      firstName: "Luis",
      lastName: "Mejía",
      birthDate: "2014-03-09",
      sex: "F",
      status: "ACTIVE",
      nationalId: "",
    });
  });

  it("does not offer to remove a document that does not exist, and shows that there is none", async () => {
    const user = userEvent.setup();
    const dialog = await openEdit(user, ANA);

    expect(within(dialog).queryByRole("checkbox", { name: "Quitar documento" })).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText("Documento actual")).toHaveValue("Sin documento");
  });

  it("can set the document of a student that had none", async () => {
    const user = userEvent.setup();
    const dialog = await openEdit(user, ANA);
    route("put", `${STUDENTS}/${ANA.id}`, studentRecord({ ...ANA, hasNationalId: true, nationalIdMasked: "****2345" }));
    await user.type(within(dialog).getByLabelText("Reemplazar documento"), DOCUMENT);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await screen.findByText("Se guardaron los cambios de «Ana Pérez».");
    expect(bodyOf(writes("PUT")[0])).toMatchObject({ nationalId: DOCUMENT });
  });

  it("changes the status from the form", async () => {
    const user = userEvent.setup();
    const dialog = await openEdit(user);
    route("put", `${STUDENTS}/${WITH_DOCUMENT.id}`, studentRecord({ ...WITH_DOCUMENT, status: "INACTIVE" }));

    await user.selectOptions(within(dialog).getByLabelText("Estado"), "INACTIVE");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await screen.findByText("Se guardaron los cambios de «Luis Mejía».");
    expect(bodyOf(writes("PUT")[0])).toMatchObject({ status: "INACTIVE" });
  });

  it("tells a duplicate document in its own words and keeps the dialog open", async () => {
    const user = userEvent.setup();
    const dialog = await openEdit(user);
    route("put", `${STUDENTS}/${WITH_DOCUMENT.id}`, problem("duplicate-national-id", 409));
    await user.type(within(dialog).getByLabelText("Reemplazar documento"), OTHER_DOCUMENT);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.duplicateNationalId);
    expect(within(dialog).getByLabelText("Reemplazar documento")).toHaveValue(OTHER_DOCUMENT);
  });

  it("starts each edit from the record of its row, without what was typed for another", async () => {
    const user = userEvent.setup();
    await openList([ANA, WITH_DOCUMENT]);
    await user.click(screen.getByRole("button", { name: "Editar Ana Pérez" }));
    const first = await screen.findByRole("dialog", { name: "Editar estudiante" });
    await user.type(within(first).getByLabelText("Reemplazar documento"), DOCUMENT);
    await user.click(within(first).getByRole("button", { name: "Cancelar" }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    await user.click(screen.getByRole("button", { name: "Editar Luis Mejía" }));

    const second = await screen.findByRole("dialog", { name: "Editar estudiante" });
    expect(within(second).getByLabelText("Reemplazar documento")).toHaveValue("");
    expect(document.body.innerHTML).not.toContain(DOCUMENT);
    expect(writes("PUT")).toHaveLength(0);
  });
});

describe("activating and deactivating from the row", () => {
  it("deactivates with the current data and no document, says so, and reads the list again", async () => {
    const user = userEvent.setup();
    await openList([WITH_DOCUMENT]);
    route("put", `${STUDENTS}/${WITH_DOCUMENT.id}`, studentRecord({ ...WITH_DOCUMENT, status: "INACTIVE" }));
    const readsBefore = gets().length;

    await user.click(screen.getByRole("button", { name: "Desactivar Luis Mejía" }));

    expect(await screen.findByText("Se desactivó «Luis Mejía».")).toBeInTheDocument();
    const body = bodyOf(writes("PUT")[0]);
    expect(body).toEqual({ firstName: "Luis", lastName: "Mejía", birthDate: "2014-03-09", sex: "F", status: "INACTIVE" });
    expect(body).not.toHaveProperty("nationalId");
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(readsBefore);
    });
  });

  it("activates an inactive student", async () => {
    const user = userEvent.setup();
    const inactive = student({ firstName: "Eva", lastName: "Rivas", status: "INACTIVE" });
    await openList([inactive]);
    route("put", `${STUDENTS}/${inactive.id}`, studentRecord({ ...inactive, status: "ACTIVE" }));

    await user.click(screen.getByRole("button", { name: "Activar Eva Rivas" }));

    expect(await screen.findByText("Se activó «Eva Rivas».")).toBeInTheDocument();
    expect(bodyOf(writes("PUT")[0])).toMatchObject({ status: "ACTIVE" });
  });

  it("tells a 403 as no permission, and changes nothing on the screen", async () => {
    const user = userEvent.setup();
    await openList([ANA]);
    route("put", `${STUDENTS}/${ANA.id}`, problem("forbidden", 403));

    await user.click(screen.getByRole("button", { name: "Desactivar Ana Pérez" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("No tiene permiso para realizar esta acción.");
    expect(within(screen.getByRole("row", { name: /Pérez/ })).getByText("Activo")).toBeInTheDocument();
  });
});

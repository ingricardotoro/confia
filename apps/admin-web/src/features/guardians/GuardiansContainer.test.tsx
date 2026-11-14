import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { guardian, guardianConsents, guardianPage, guardianRecord, linkedStudent, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderFeature, TEST_TOKEN } from "../../test/render-feature.tsx";
import { guardiansFeature } from "./feature.tsx";

// The guardians registry over the real routes, the real query layer and the real transport, with the test server
// answering: the list (the exact request, paging, order and filters in the address), the form (create and the ways to treat
// the document and the RTN on an edit), the detail with the linked students, activating and deactivating, the business
// errors, the states of the load, and that the national id and the RTN leave no trace once they are sent.
installMswServer();

const GUARDIANS = "/api/v1/guardians";
const OPEN = "/guardians";
const TIMEOUT = { timeout: 10_000 };
/** Typed in a test: it must never appear in the address, the DOM after a save, the caches or a log. */
const DOCUMENT = "0801198012345";
const RTN = "08011980123456";

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === GUARDIANS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const writes = (method: "POST" | "PUT") => requests().filter((sent) => sent.method === method);
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

afterEach(() => {
  vi.restoreAllMocks();
});

async function loaded() {
  const table = await screen.findByRole("table", { name: "Encargados de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
}

async function openList(rows: unknown[] = [guardian()], options: { path?: string; total?: number } = {}) {
  route("get", GUARDIANS, guardianPage(rows, options.total === undefined ? {} : { total: options.total }));
  // The detail opens the consents section too: every detail test has them to read.
  route("get", `${GUARDIANS}/:id/consents`, guardianConsents());
  const app = renderFeature(guardiansFeature, options.path ?? OPEN);
  await loaded();
  return app;
}

const ROSA = guardian({ firstName: "Rosa", lastName: "Mejía", guardianCode: "P-000001" });

describe("the list of guardians", () => {
  it("shows the code, names, whether a document and an RTN exist (never their digits) and the state", async () => {
    await openList([
      guardian({ firstName: "Rosa", lastName: "Mejía", guardianCode: "P-000001", hasNationalId: true }),
      guardian({ firstName: "Luis", lastName: "Paz", guardianCode: "P-000002", hasRtn: true, status: "INACTIVE" }),
    ]);

    const table = screen.getByRole("table", { name: "Encargados de la institución" });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Código",
      "Apellidos",
      "Nombres",
      "Documento",
      "RTN",
      "Estado",
      "Acciones",
    ]);
    const rosa = within(table).getByRole("row", { name: /Mejía/ });
    expect(within(rosa).getByText("Registrado")).toBeInTheDocument();
    expect(within(rosa).getByText("Sin registrar")).toHaveClass("sr-only");
    expect(within(rosa).getByText("Activo")).toBeInTheDocument();
    const luis = within(table).getByRole("row", { name: /Paz/ });
    expect(within(luis).getByText("Inactivo")).toBeInTheDocument();
    expect(within(luis).getByRole("button", { name: "Activar Luis Paz" })).toBeInTheDocument();
  });

  it("asks the server for the first page, ordered by last name by default, and sends the bearer token", async () => {
    await openList();

    expect(Object.fromEntries(lastQuery())).toEqual({ page: "0", size: "25" });
    expect(requests()[0]?.headers.get("Authorization")).toBe(`Bearer ${TEST_TOKEN}`);
    expect(screen.getByRole("columnheader", { name: "Apellidos" })).toHaveAttribute("aria-sort", "ascending");
  });

  it("asks the server for the order of a sortable column and writes it in the address; the others are not buttons", async () => {
    const user = userEvent.setup();
    const { router } = await openList();

    await user.click(screen.getByRole("button", { name: "Código" }));
    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("code,asc");
    });
    await user.click(screen.getByRole("button", { name: "Estado" }));
    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("status,asc");
    });
    expect(router.state.location.searchStr).toBe("?sort=status%3Aasc");
    expect(screen.queryByRole("button", { name: "Nombres" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "RTN" })).not.toBeInTheDocument();
  });

  it("asks for the next page and writes it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList([ROSA], { total: 60 });
    route("get", GUARDIANS, guardianPage([guardian({ lastName: "Zelaya" })], { page: 1, total: 60 }));

    await user.click(screen.getByRole("button", { name: "Siguiente" }));

    expect(await screen.findByRole("row", { name: /Zelaya/ })).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("1");
    expect(router.state.location.searchStr).toBe("?page=2");
  });

  it("opens with the search of the address, and drops what does not hold", async () => {
    await openList([ROSA], { path: `${OPEN}?page=2&pageSize=50&sort=code:desc&q=me&status=INACTIVE` });

    expect(Object.fromEntries(lastQuery())).toEqual({ page: "1", size: "50", sort: "code,desc", q: "me", status: "INACTIVE" });
  });

  it("drops a sort field the server does not accept and a text too short to search", async () => {
    await openList([ROSA], { path: `${OPEN}?sort=email:asc&q=a&status=OTHER` });

    expect(Object.fromEntries(lastQuery())).toEqual({ page: "0", size: "25" });
  });

  it("searches only from two characters, after a pause, and writes the text in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    const before = gets().length;

    await user.type(screen.getByLabelText("Buscar"), "r");
    expect(screen.getByText("Escriba al menos 2 caracteres para buscar.")).toBeInTheDocument();
    expect(gets()).toHaveLength(before);
    route("get", GUARDIANS, guardianPage([ROSA]));
    await user.type(screen.getByLabelText("Buscar"), "o");

    await waitFor(() => {
      expect(lastQuery().get("q")).toBe("ro");
    }, TIMEOUT);
    expect(lastQuery().get("page")).toBe("0");
    expect(router.state.location.searchStr).toBe("?q=ro");
  });

  it("filters by status in the request and the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    route("get", GUARDIANS, guardianPage([]));

    await user.selectOptions(screen.getByLabelText("Estado", { selector: "#guardians-filter-status" }), "INACTIVE");

    await waitFor(() => {
      expect(lastQuery().get("status")).toBe("INACTIVE");
    });
    expect(router.state.location.searchStr).toBe("?status=INACTIVE");
    expect(await screen.findByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
  });
});

describe("the states of the list", () => {
  it("shows the empty state of a registry with no guardians, with the way to create the first", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay encargados")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Registrar al primer encargado" })).toBeInTheDocument();
  });

  it("shows the error state with the trace id and a retry that loads the list", async () => {
    const user = userEvent.setup();
    route("get", GUARDIANS, problem("internal-error", 500, { traceId: "trace-g-1" }), guardianPage([ROSA]));
    renderFeature(guardiansFeature, OPEN);

    expect(await screen.findByText("No se pudo cargar la lista de encargados", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Identificador para soporte: trace-g-1");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /Mejía/ })).toBeInTheDocument();
  });

  it("tells a 403 on the read as no permission, in plain words", async () => {
    route("get", GUARDIANS, problem("forbidden", 403));
    renderFeature(guardiansFeature, OPEN);

    expect(await screen.findByRole("alert", undefined, TIMEOUT)).toHaveTextContent(admin.problem.forbidden);
  });

  it("shows the loading state with the shape of the table before the answer", async () => {
    route("get", GUARDIANS, guardianPage([ROSA]));
    renderFeature(guardiansFeature, OPEN);

    expect(await screen.findByRole("table", { name: "Encargados de la institución" }, TIMEOUT)).toHaveAttribute("aria-busy", "true");
    expect(await screen.findByRole("row", { name: /Mejía/ })).toBeInTheDocument();
  });
});

async function fillNew(user: ReturnType<typeof userEvent.setup>, extra: { document?: string; rtn?: string } = {}) {
  await user.click(screen.getByRole("button", { name: "Nuevo encargado" }));
  const dialog = await screen.findByRole("dialog", { name: "Nuevo encargado" });
  await user.type(within(dialog).getByLabelText(/Nombres/), "Rosa");
  await user.type(within(dialog).getByLabelText(/Apellidos/), "Mejía");
  if (extra.document !== undefined) await user.type(within(dialog).getByLabelText(/Documento de identidad/), extra.document);
  if (extra.rtn !== undefined) await user.type(within(dialog).getByLabelText(/^RTN/), extra.rtn);
  return dialog;
}

describe("registering a guardian", () => {
  it("creates a guardian with only the required members, the channel NONE, and no Idempotency-Key", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", GUARDIANS, guardianRecord(guardian({ guardianCode: "P-000007" }), 201));
    const dialog = await fillNew(user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText("Se creó el registro «Rosa Mejía».")).toBeInTheDocument();
    expect(bodyOf(writes("POST")[0])).toEqual({ firstName: "Rosa", lastName: "Mejía", preferredChannel: "NONE" });
    expect(writes("POST")[0]?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("sends the document, the RTN and the contact data that are typed, trimmed", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", GUARDIANS, guardianRecord(guardian({ hasNationalId: true, hasRtn: true }), 201));
    const dialog = await fillNew(user, { document: ` ${DOCUMENT} `, rtn: RTN });
    await user.type(within(dialog).getByLabelText("Correo electrónico"), "rosa@example.test");
    await user.type(within(dialog).getByLabelText("Teléfono"), "+504 9999-0000");
    await user.type(within(dialog).getByLabelText("Dirección"), "Col. Kennedy");
    await user.selectOptions(within(dialog).getByLabelText("Canal preferido"), "WHATSAPP");

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await screen.findByText("Se creó el registro «Rosa Mejía».");
    expect(bodyOf(writes("POST")[0])).toEqual({
      firstName: "Rosa",
      lastName: "Mejía",
      preferredChannel: "WHATSAPP",
      email: "rosa@example.test",
      phone: "+504 9999-0000",
      address: "Col. Kennedy",
      nationalId: DOCUMENT,
      rtn: RTN,
    });
  });

  it("marks the personal fields so the browser does not autofill, spell-check or autocorrect them", async () => {
    const user = userEvent.setup();
    await openList([]);
    const dialog = await fillNew(user);

    for (const label of [/Nombres/, /Apellidos/, /Documento de identidad/, /^RTN/, "Correo electrónico", "Teléfono", "Dirección"]) {
      const field = within(dialog).getByLabelText(label);
      expect(field).toHaveAttribute("autocomplete", "off");
      expect(field).toHaveAttribute("spellcheck", "false");
      expect(field).toHaveAttribute("autocorrect", "off");
    }
  });

  it("leaves no trace of the national id or the RTN once sent: not in the page, the address, the caches or the console", async () => {
    const user = userEvent.setup();
    const spies = (["log", "info", "warn", "error"] as const).map((method) => vi.spyOn(console, method).mockImplementation(() => undefined));
    const { router, queryClient, container } = await openList([]);
    route("post", GUARDIANS, guardianRecord(guardian({ hasNationalId: true, hasRtn: true }), 201));
    const dialog = await fillNew(user, { document: DOCUMENT, rtn: RTN });
    expect(router.state.location.searchStr).not.toContain(DOCUMENT);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await screen.findByText("Se creó el registro «Rosa Mejía».");

    for (const secret of [DOCUMENT, RTN]) {
      expect(document.body.innerHTML).not.toContain(secret);
      expect(container.innerHTML).not.toContain(secret);
      expect(router.state.location.searchStr).not.toContain(secret);
      expect(JSON.stringify(queryClient.getQueryCache().getAll().map((query) => [query.queryKey, query.state.data]))).not.toContain(secret);
      expect(JSON.stringify(queryClient.getMutationCache().getAll().map((mutation) => mutation.state.variables))).not.toContain(secret);
      expect(window.localStorage.length + window.sessionStorage.length).toBe(0);
      for (const spy of spies) expect(JSON.stringify(spy.mock.calls)).not.toContain(secret);
    }
  });

  it("refuses an empty form, a bad RTN, a bad email and a bad phone before sending anything", async () => {
    const user = userEvent.setup();
    await openList([]);
    await user.click(screen.getByRole("button", { name: "Nuevo encargado" }));
    const dialog = await screen.findByRole("dialog", { name: "Nuevo encargado" });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(await within(dialog).findByText("Hay 2 campos con errores.")).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/Nombres/), "Rosa");
    await user.type(within(dialog).getByLabelText(/Apellidos/), "Mejía");
    await user.type(within(dialog).getByLabelText(/^RTN/), "123");
    await user.type(within(dialog).getByLabelText("Correo electrónico"), "no-es-correo");
    await user.type(within(dialog).getByLabelText("Teléfono"), "abc");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText(admin.field.rtnFormat, { selector: "p" })).toBeInTheDocument();
    expect(within(dialog).getByText(admin.field.emailFormat, { selector: "p" })).toBeInTheDocument();
    expect(within(dialog).getByText(admin.field.phoneFormat, { selector: "p" })).toBeInTheDocument();
    expect(writes("POST")).toHaveLength(0);
  });

  it("tells a duplicate document as the guardian's own text and keeps what was typed", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", GUARDIANS, problem("duplicate-national-id", 409, { traceId: "t-dup" }));
    const dialog = await fillNew(user, { document: DOCUMENT });

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(admin.problem.duplicateGuardianNationalId);
    expect(alert).toHaveTextContent("t-dup");
    expect(within(dialog).getByLabelText(/Nombres/)).toHaveValue("Rosa");
    expect(alert).toHaveFocus();
  });

  it("tells a 403 in plain words inside the dialog and changes nothing on screen", async () => {
    const user = userEvent.setup();
    await openList([ROSA]);
    route("post", GUARDIANS, problem("forbidden", 403));
    const dialog = await fillNew(user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.forbidden);
    expect(screen.getByRole("dialog", { name: "Nuevo encargado" })).toBeInTheDocument();
  });

  it("sends once while the request is in flight", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", GUARDIANS, guardianRecord(guardian(), 201));
    const dialog = await fillNew(user);
    const save = within(dialog).getByRole("button", { name: "Guardar" });

    await user.dblClick(save);

    await screen.findByText("Se creó el registro «Rosa Mejía».");
    expect(writes("POST")).toHaveLength(1);
  });
});

describe("editing a guardian", () => {
  const WITH_BOTH = guardian({ firstName: "Rosa", lastName: "Mejía", hasNationalId: true, hasRtn: true, email: "rosa@example.test", phone: "+504 9999-0000", address: "Col. Kennedy", preferredChannel: "EMAIL" });

  it("shows only that a document and an RTN exist, and keeps both when the boxes are left empty (no member sent)", async () => {
    const user = userEvent.setup();
    await openList([WITH_BOTH]);
    route("put", `${GUARDIANS}/:id`, guardianRecord(WITH_BOTH));
    await user.click(screen.getByRole("button", { name: "Editar Rosa Mejía" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar encargado" });

    expect(within(dialog).getByLabelText("Documento actual")).toHaveValue("Registrado");
    expect(within(dialog).getByLabelText("RTN actual")).toHaveValue("Registrado");
    expect(within(dialog).getByLabelText("Reemplazar documento")).toHaveValue("");
    expect(within(dialog).getByLabelText("Correo electrónico")).toHaveValue("rosa@example.test");
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await screen.findByText("Se guardaron los cambios de «Rosa Mejía».");
    expect(bodyOf(writes("PUT")[0])).toEqual({
      firstName: "Rosa",
      lastName: "Mejía",
      preferredChannel: "EMAIL",
      status: "ACTIVE",
      email: "rosa@example.test",
      phone: "+504 9999-0000",
      address: "Col. Kennedy",
    });
  });

  it("replaces the document, removes the RTN only with its explicit checkbox, and drops the contact fields that are emptied", async () => {
    const user = userEvent.setup();
    await openList([WITH_BOTH]);
    route("put", `${GUARDIANS}/:id`, guardianRecord(guardian({ hasNationalId: true })));
    await user.click(screen.getByRole("button", { name: "Editar Rosa Mejía" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar encargado" });

    await user.type(within(dialog).getByLabelText("Reemplazar documento"), OTHER_DOCUMENT());
    await user.click(within(dialog).getByRole("checkbox", { name: "Quitar RTN" }));
    await user.clear(within(dialog).getByLabelText("Teléfono"));
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await screen.findByText("Se guardaron los cambios de «Rosa Mejía».");
    expect(bodyOf(writes("PUT")[0])).toMatchObject({ nationalId: OTHER_DOCUMENT(), rtn: "" });
    expect(bodyOf(writes("PUT")[0])).not.toHaveProperty("phone");
  });

  it("removing the document empties what was typed to replace it", async () => {
    const user = userEvent.setup();
    await openList([WITH_BOTH]);
    route("put", `${GUARDIANS}/:id`, guardianRecord(guardian()));
    await user.click(screen.getByRole("button", { name: "Editar Rosa Mejía" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar encargado" });

    await user.type(within(dialog).getByLabelText("Reemplazar documento"), OTHER_DOCUMENT());
    await user.click(within(dialog).getByRole("checkbox", { name: "Quitar documento" }));

    expect(within(dialog).getByLabelText("Reemplazar documento")).toHaveValue("");
    expect(within(dialog).getByLabelText("Reemplazar documento")).toBeDisabled();
  });

  it("offers no removal for a document that does not exist", async () => {
    const user = userEvent.setup();
    await openList([ROSA]);
    await user.click(screen.getByRole("button", { name: "Editar Rosa Mejía" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar encargado" });

    expect(within(dialog).queryByRole("checkbox", { name: "Quitar documento" })).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText("Documento actual")).toHaveValue("Sin registrar");
  });
});

const OTHER_DOCUMENT = (): string => "1503199998765";

describe("activating and deactivating from the row", () => {
  it("sends the whole record with the other status and no document or RTN (so the server keeps them)", async () => {
    const user = userEvent.setup();
    const row = guardian({ firstName: "Rosa", lastName: "Mejía", email: "rosa@example.test", preferredChannel: "SMS", hasNationalId: true });
    await openList([row]);
    route("put", `${GUARDIANS}/:id`, guardianRecord({ ...row, status: "INACTIVE" }));

    await user.click(screen.getByRole("button", { name: "Desactivar Rosa Mejía" }));

    expect(await screen.findByText("Se desactivó «Rosa Mejía».")).toBeInTheDocument();
    expect(bodyOf(writes("PUT")[0])).toEqual({ firstName: "Rosa", lastName: "Mejía", preferredChannel: "SMS", status: "INACTIVE", email: "rosa@example.test" });
  });

  it("says that a deactivated guardian keeps its students, and how many", async () => {
    const user = userEvent.setup();
    await openList([ROSA]);
    route("put", `${GUARDIANS}/:id`, guardianRecord({ ...ROSA, status: "INACTIVE", students: [linkedStudent(), linkedStudent({ studentCode: "E-000002" })] }));

    await user.click(screen.getByRole("button", { name: "Desactivar Rosa Mejía" }));

    expect(await screen.findByText(/Se desactivó «Rosa Mejía»\. El encargado sigue vinculado a 2 estudiantes\./)).toBeInTheDocument();
  });

  it("tells a failure in the region of the screen", async () => {
    const user = userEvent.setup();
    await openList([ROSA]);
    route("put", `${GUARDIANS}/:id`, problem("forbidden", 403));

    await user.click(screen.getByRole("button", { name: "Desactivar Rosa Mejía" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(admin.problem.forbidden);
  });
});

describe("the detail of a guardian", () => {
  it("shows the data, the linked students with their percentages written with two decimals, and no digits of any document", async () => {
    const user = userEvent.setup();
    await openList([ROSA]);
    route(
      "get",
      `${GUARDIANS}/:id`,
      guardianRecord(
        guardian({
          ...ROSA,
          hasNationalId: true,
          email: "rosa@example.test",
          students: [linkedStudent({ studentFirstName: "Ana", studentLastName: "Pérez", studentCode: "E-000001", financialResponsibilityPercent: 60, relationship: "MOTHER" })],
        }),
      ),
    );

    await user.click(screen.getByRole("button", { name: "Ver detalle de Rosa Mejía" }));

    const dialog = await screen.findByRole("dialog", { name: "Detalle del encargado" });
    expect(await within(dialog).findByText("rosa@example.test")).toBeInTheDocument();
    const table = within(dialog).getByRole("table", { name: "Estudiantes a los que el encargado está vinculado ahora" });
    expect(within(table).getByText("Ana Pérez · E-000001")).toBeInTheDocument();
    expect(within(table).getByText("Madre")).toBeInTheDocument();
    expect(within(table).getByText("60.00 %")).toBeInTheDocument();
    expect(within(dialog).queryByText(DOCUMENT)).not.toBeInTheDocument();
  });

  it("says when the guardian has no students, and warns when an inactive guardian still has some", async () => {
    const user = userEvent.setup();
    await openList([ROSA]);
    route("get", `${GUARDIANS}/:id`, guardianRecord(guardian({ ...ROSA, students: [] })));
    await user.click(screen.getByRole("button", { name: "Ver detalle de Rosa Mejía" }));
    expect(await screen.findByText("Este encargado no está vinculado a ningún estudiante.")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    route("get", `${GUARDIANS}/:id`, guardianRecord(guardian({ ...ROSA, status: "INACTIVE", students: [linkedStudent()] })));

    await user.click(screen.getByRole("button", { name: "Ver detalle de Rosa Mejía" }));

    expect(await screen.findByText(admin.guardians.detail.inactiveWithLinks)).toBeInTheDocument();
  });

  it("shows a skeleton while it loads, a retry when it fails, and returns the focus to its button on close", async () => {
    const user = userEvent.setup();
    await openList([ROSA]);
    route("get", `${GUARDIANS}/:id`, problem("internal-error", 500, { traceId: "t-det" }), guardianRecord(guardian({ ...ROSA, students: [] })));
    const opener = screen.getByRole("button", { name: "Ver detalle de Rosa Mejía" });

    await user.click(opener);
    const dialog = await screen.findByRole("dialog", { name: "Detalle del encargado" });
    expect(await within(dialog).findByText("No se pudo cargar el detalle del encargado")).toBeInTheDocument();
    expect(dialog).toHaveTextContent("t-det");
    await user.click(within(dialog).getByRole("button", { name: "Reintentar" }));
    expect(await within(dialog).findByText("Este encargado no está vinculado a ningún estudiante.")).toBeInTheDocument();
    await user.click(within(dialog).getAllByRole("button", { name: "Cerrar" })[0] as HTMLElement);

    await waitFor(() => {
      expect(opener).toHaveFocus();
    });
  });
});

import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import {
  academicYear,
  enrollmentPage,
  enrollmentRecord,
  enrollmentTransfer,
  grade,
  modality,
  organizationPage,
  problem,
  section,
  student,
  studentEnrollment,
  studentPage,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderFeature, TEST_TOKEN } from "../../test/render-feature.tsx";
import { enrollmentsFeature } from "./feature.tsx";

// The enrollments over the real route, the real query layer and the real transport, with the test server answering. The
// flows of the container: the list (the query it sends, the year in progress by default, paging, order and filters in the
// address), the detail with its history, the enroll dialog (the student search that asks only from two characters, the
// sections of the year in progress), the transfer and the exit with their exact bodies, every business error, the field
// errors, the 403, the actions hidden on a final enrollment, and the states of the load.
installMswServer();

const ENROLLMENTS = "/api/v1/enrollments";
const STUDENTS = "/api/v1/students";
const ORG = "/api/v1/organization";
const OPEN = "/enrollments";
const TIMEOUT = { timeout: 10_000 };

const YEAR = academicYear({ id: "11111111-1111-4111-8111-111111111111", name: "Año lectivo 2026", status: "ACTIVE" });
const NEXT_YEAR = academicYear({ id: "55555555-5555-4555-8555-555555555555", name: "Año lectivo 2027", status: "PLANNED", startDate: "2027-01-15", endDate: "2027-11-30" });
const PRIMARIA = modality({ id: "66666666-6666-4666-8666-666666666666", name: "Primaria" });
const PRIMERO = grade({ id: "33333333-3333-4333-8333-333333333333", modalityId: PRIMARIA.id, name: "Primero" });
const SECTION_A = section({ id: "22222222-2222-4222-8222-222222222222", academicYearId: YEAR.id, gradeId: PRIMERO.id, name: "A" });
const SECTION_B = section({ id: "44444444-4444-4444-8444-444444444444", academicYearId: YEAR.id, gradeId: PRIMERO.id, name: "B" });
const SECTION_OFF = section({ id: "77777777-7777-4777-8777-777777777777", academicYearId: YEAR.id, gradeId: PRIMERO.id, name: "C", isActive: false });

const ANA = studentEnrollment({ studentFirstName: "Ana", studentLastName: "Pérez", studentCode: "E-000001" });
const LUIS = studentEnrollment({
  studentFirstName: "Luis",
  studentLastName: "Mejía",
  studentCode: "E-000002",
  status: "WITHDRAWN",
  withdrawnOn: "2026-05-04",
  withdrawalReason: "Cambio de ciudad",
});

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === ENROLLMENTS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const posts = () => requests().filter((sent) => sent.method === "POST");
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

afterEach(() => {
  vi.restoreAllMocks();
});

/** The lookups every screen reads: the years, the grades, the modalities and the sections of the year. */
function routeLookups(years = [YEAR, NEXT_YEAR], sections = [SECTION_A, SECTION_B, SECTION_OFF]) {
  route("get", `${ORG}/academic-years`, organizationPage("academic-years", years));
  route("get", `${ORG}/grades`, organizationPage("grades", [PRIMERO]));
  route("get", `${ORG}/modalities`, organizationPage("modalities", [PRIMARIA]));
  route("get", `${ORG}/sections`, organizationPage("sections", sections));
}

async function loaded() {
  const table = await screen.findByRole("table", { name: "Matrículas de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
}

async function openList(rows: unknown[] = [ANA], options: { path?: string; total?: number } = {}) {
  routeLookups();
  route("get", ENROLLMENTS, enrollmentPage(rows, options.total === undefined ? {} : { total: options.total }));
  const app = renderFeature(enrollmentsFeature, options.path ?? OPEN);
  await loaded();
  return app;
}

function formatted(year: number, month: number, day: number): string {
  return new Intl.DateTimeFormat("es-HN", { dateStyle: "medium", timeZone: "UTC" }).format(Date.UTC(year, month - 1, day));
}

describe("the list of enrollments", () => {
  it("shows the code, the student, the grade and section, the dates, the state and the actions of an active enrollment", async () => {
    await openList([ANA, LUIS]);

    const table = screen.getByRole("table", { name: "Matrículas de la institución" });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Código",
      "Estudiante",
      "Grado y sección",
      "Fecha de matrícula",
      "Estado",
      "Fecha de salida",
      "Acciones",
    ]);
    const ana = within(table).getByRole("row", { name: /Pérez/ });
    expect(within(ana).getByText("E-000001")).toBeInTheDocument();
    expect(within(ana).getByText("Pérez, Ana")).toBeInTheDocument();
    expect(within(ana).getByText("Primero · A")).toBeInTheDocument();
    expect(within(ana).getByText(formatted(2026, 2, 2))).toBeInTheDocument();
    expect(within(ana).getByText("Activa")).toBeInTheDocument();
    expect(within(ana).getByText("Sin fecha de salida")).toHaveClass("sr-only");
    const luis = within(table).getByRole("row", { name: /Mejía/ });
    expect(within(luis).getByText("Retirada")).toBeInTheDocument();
    expect(within(luis).getByText(formatted(2026, 5, 4))).toBeInTheDocument();
  });

  it("asks the server for the first page, by last name, with no year: the server uses the one in progress, and the filter shows it", async () => {
    await openList();

    expect(Object.fromEntries(lastQuery())).toEqual({ page: "0", size: "25" });
    expect(requests()[0]?.headers.get("Authorization")).toBe(`Bearer ${TEST_TOKEN}`);
    expect(screen.getByRole("columnheader", { name: "Estudiante" })).toHaveAttribute("aria-sort", "ascending");
    await waitFor(() => {
      expect(screen.getByLabelText("Año lectivo")).toHaveValue(YEAR.id);
    });
    // With a year in progress the list cannot ask for every year, so the filter has no such option.
    expect(within(screen.getByLabelText("Año lectivo")).queryByRole("option", { name: "Todos los años" })).not.toBeInTheDocument();
    // The sections to filter by are the year's.
    await waitFor(() => {
      expect(requests().some((sent) => sent.path === `${ORG}/sections` && new URLSearchParams(sent.search).get("academicYearId") === YEAR.id)).toBe(true);
    });
  });

  it("offers every year when none is in progress", async () => {
    routeLookups([NEXT_YEAR]);
    route("get", ENROLLMENTS, enrollmentPage([ANA]));
    renderFeature(enrollmentsFeature, OPEN);
    await loaded();

    await waitFor(() => {
      expect(screen.getByLabelText("Año lectivo")).toHaveValue("");
    });
    expect(within(screen.getByLabelText("Año lectivo")).getByRole("option", { name: "Todos los años" })).toBeInTheDocument();
  });

  it("asks the server for the next page and writes it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList([ANA], { total: 60 });
    route("get", ENROLLMENTS, enrollmentPage([studentEnrollment({ studentLastName: "Zelaya" })], { page: 1, total: 60 }));

    await user.click(screen.getByRole("button", { name: "Siguiente" }));

    expect(await screen.findByRole("row", { name: /Zelaya/ })).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("1");
    expect(router.state.location.searchStr).toBe("?page=2");
  });

  it("asks the server for each order of a sortable column and does not make the others buttons", async () => {
    const user = userEvent.setup();
    const { router } = await openList();

    await user.click(screen.getByRole("button", { name: "Fecha de matrícula" }));
    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("enrolledOn,asc");
    });
    await user.click(screen.getByRole("button", { name: "Fecha de matrícula" }));
    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("enrolledOn,desc");
    });
    expect(router.state.location.searchStr).toBe("?sort=enrolledOn%3Adesc");
    await user.click(screen.getByRole("button", { name: "Estado" }));
    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("status,asc");
    });
    expect(screen.queryByRole("button", { name: "Código" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Grado y sección" })).not.toBeInTheDocument();
  });

  it("opens with the filters of the address and drops what does not hold", async () => {
    await openList([ANA], {
      path: `${OPEN}?page=2&pageSize=50&sort=status:desc&academicYearId=${NEXT_YEAR.id}&gradeId=${PRIMERO.id}&sectionId=${SECTION_B.id}&status=WITHDRAWN`,
    });

    expect(Object.fromEntries(lastQuery())).toEqual({
      page: "1",
      size: "50",
      sort: "status,desc",
      academicYearId: NEXT_YEAR.id,
      gradeId: PRIMERO.id,
      sectionId: SECTION_B.id,
      status: "WITHDRAWN",
    });
    await openList([ANA], { path: `${OPEN}?academicYearId=nope&sectionId=7&status=GONE&sort=other:asc&page=-1` });
    expect(Object.fromEntries(lastQuery())).toEqual({ page: "0", size: "25" });
  });

  it("writes each filter in the address, goes back to the first page, and clears the section when the year changes", async () => {
    const user = userEvent.setup();
    const { router } = await openList([ANA], { path: `${OPEN}?page=3&sectionId=${SECTION_B.id}` });
    await waitFor(() => {
      expect(screen.getByLabelText("Año lectivo")).toHaveValue(YEAR.id);
    });

    await user.selectOptions(screen.getByLabelText("Año lectivo"), NEXT_YEAR.id);
    await waitFor(() => {
      expect(lastQuery().get("academicYearId")).toBe(NEXT_YEAR.id);
    });
    expect(lastQuery().has("sectionId")).toBe(false);
    expect(lastQuery().get("page")).toBe("0");

    await user.selectOptions(screen.getByLabelText("Grado"), PRIMERO.id);
    await waitFor(() => {
      expect(lastQuery().get("gradeId")).toBe(PRIMERO.id);
    });
    await user.selectOptions(screen.getByLabelText("Estado"), "GRADUATED");
    await waitFor(() => {
      expect(lastQuery().get("status")).toBe("GRADUATED");
    });
    expect(router.state.location.searchStr).toContain("status=GRADUATED");
    // A section of the year chosen is a filter too; the inactive one is listed as the structure lists it.
    await waitFor(() => {
      expect(within(screen.getByLabelText("Sección")).getByRole("option", { name: "Primaria · Primero · B" })).toBeInTheDocument();
    });
    await user.selectOptions(screen.getByLabelText("Sección"), SECTION_B.id);
    await waitFor(() => {
      expect(lastQuery().get("sectionId")).toBe(SECTION_B.id);
    });
  });

  it("offers the actions to transfer and to exit only on an active enrollment, and the detail on every one", async () => {
    await openList([ANA, LUIS, studentEnrollment({ studentLastName: "Rivera", studentFirstName: "Eva", status: "GRADUATED", withdrawnOn: "2026-11-01" }), studentEnrollment({ studentLastName: "Soto", studentFirstName: "Raúl", status: "TRANSFERRED", withdrawnOn: "2026-06-01", withdrawalReason: "Otra escuela" })]);

    expect(screen.getByRole("button", { name: "Trasladar de sección a Ana Pérez" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Registrar salida de Ana Pérez" })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^Ver detalle de la matrícula de/ })).toHaveLength(4);
    expect(screen.getAllByRole("button", { name: /^Trasladar de sección a/ })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: /^Registrar salida de/ })).toHaveLength(1);
  });

  it("tells an empty list with no filter as a way to the first enrollment, and an empty filter as a way to clear it", async () => {
    const user = userEvent.setup();
    await openList([]);
    expect(screen.getByText("Todavía no hay matrículas")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Matricular a un estudiante" })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Estado"), "GRADUATED");

    expect(await screen.findByRole("button", { name: "Quitar filtros" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));
    await waitFor(() => {
      expect(lastQuery().has("status")).toBe(false);
    });
  });

  it("tells a failed read with a retry that asks again, and a 403 as no permission", async () => {
    const user = userEvent.setup();
    routeLookups();
    route("get", ENROLLMENTS, problem("internal-error", 500, { traceId: "trace-1" }), enrollmentPage([ANA]));
    renderFeature(enrollmentsFeature, OPEN);

    const alert = await screen.findByRole("alert", undefined, TIMEOUT);
    expect(alert).toHaveTextContent("No se pudo cargar la lista de matrículas");
    expect(alert).toHaveTextContent("trace-1");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByRole("row", { name: /Pérez/ })).toBeInTheDocument();
  });

  it("tells a 403 on the read as no permission, in plain words", async () => {
    routeLookups();
    route("get", ENROLLMENTS, problem("forbidden", 403));
    renderFeature(enrollmentsFeature, OPEN);

    expect(await screen.findByRole("alert", undefined, TIMEOUT)).toHaveTextContent(admin.problem.forbidden);
  });

  it("shows the loading state as a busy table, then the rows", async () => {
    routeLookups();
    route("get", ENROLLMENTS, enrollmentPage([ANA]));
    renderFeature(enrollmentsFeature, OPEN);

    expect(await screen.findByRole("table", { name: "Matrículas de la institución" }, TIMEOUT)).toBeInTheDocument();
    expect(await screen.findByRole("row", { name: /Pérez/ })).toBeInTheDocument();
  });

  it("warns that the options of the filters did not load, and a retry asks for them again", async () => {
    const user = userEvent.setup();
    route("get", `${ORG}/academic-years`, problem("internal-error", 500), organizationPage("academic-years", [YEAR]));
    route("get", `${ORG}/grades`, organizationPage("grades", [PRIMERO]));
    route("get", `${ORG}/modalities`, organizationPage("modalities", [PRIMARIA]));
    route("get", `${ORG}/sections`, organizationPage("sections", [SECTION_A]));
    route("get", ENROLLMENTS, enrollmentPage([ANA]));
    renderFeature(enrollmentsFeature, OPEN);

    await user.click(await screen.findByRole("button", { name: "Reintentar" }, TIMEOUT));

    await waitFor(() => {
      expect(screen.getByLabelText("Año lectivo")).toHaveValue(YEAR.id);
    });
  });
});

describe("the detail", () => {
  it("shows the enrollment with its history of transfers, oldest first, read only, and returns the focus to the button", async () => {
    const user = userEvent.setup();
    await openList();
    route(
      "get",
      `${ENROLLMENTS}/${ANA.id}`,
      enrollmentRecord({
        ...ANA,
        transfers: [
          enrollmentTransfer({ fromSectionName: "A", toSectionName: "B", transferredOn: "2026-03-02", reason: "Cambio de turno" }),
          enrollmentTransfer({ fromSectionName: "B", toSectionName: "C", transferredOn: "2026-04-02", reason: null }),
        ],
      }),
    );
    const opener = screen.getByRole("button", { name: "Ver detalle de la matrícula de Ana Pérez" });

    await user.click(opener);

    const dialog = await screen.findByRole("dialog", { name: "Detalle de la matrícula" });
    expect(await within(dialog).findByText("Ana Pérez · E-000001")).toBeInTheDocument();
    const history = within(dialog).getByRole("table", { name: /Traslados de sección de la matrícula/ });
    const rows = within(history).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent(`${formatted(2026, 3, 2)}ABCambio de turno`);
    expect(rows[1]).toHaveTextContent("Sin motivo");
    expect(within(dialog).queryByRole("textbox")).not.toBeInTheDocument();
    await user.click(within(dialog).getAllByRole("button", { name: "Cerrar" })[0] as HTMLElement);
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(opener).toHaveFocus();
  });

  it("says an enrollment never moved, shows the reason of an exit, and offers a retry when the read fails", async () => {
    const user = userEvent.setup();
    await openList([LUIS]);
    route("get", `${ENROLLMENTS}/${LUIS.id}`, problem("internal-error", 500, { traceId: "trace-9" }), enrollmentRecord({ ...LUIS, transfers: [] }));

    await user.click(screen.getByRole("button", { name: "Ver detalle de la matrícula de Luis Mejía" }));

    const dialog = await screen.findByRole("dialog", { name: "Detalle de la matrícula" });
    expect(await within(dialog).findByText("No se pudo cargar el detalle de la matrícula")).toBeInTheDocument();
    expect(dialog).toHaveTextContent("trace-9");
    await user.click(within(dialog).getByRole("button", { name: "Reintentar" }));
    expect(await within(dialog).findByText("Esta matrícula no tiene traslados de sección.")).toBeInTheDocument();
    expect(within(dialog).getByText("Cambio de ciudad")).toBeInTheDocument();
  });
});

async function openEnroll(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Matricular" }));
  return screen.findByRole("dialog", { name: "Matricular estudiante" });
}

const PEREZ = student({ id: "88888888-8888-4888-8888-888888888888", firstName: "Ana", lastName: "Pérez", studentCode: "E-000001" });

async function findAndChoose(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement, text = "Pér") {
  route("get", STUDENTS, studentPage([PEREZ, student({ id: crypto.randomUUID(), firstName: "Luz", lastName: "Pérez", studentCode: "E-000009" })]));
  await user.type(within(dialog).getByRole("combobox", { name: /Buscar estudiante/ }), text);
  const option = await within(dialog).findByRole("option", { name: "Ana Pérez · E-000001" }, TIMEOUT);
  await user.click(option);
}

describe("enrolling a student", () => {
  it("searches the active students from two characters only, after a pause, and shows the results as a listbox", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openEnroll(user);
    route("get", STUDENTS, studentPage([PEREZ]));
    const box = within(dialog).getByRole("combobox", { name: /Buscar estudiante/ });

    await user.type(box, "P");
    expect(within(dialog).getByText("Escriba al menos 2 caracteres para buscar.", { selector: "p" })).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 450));
    expect(requests().filter((sent) => sent.path === STUDENTS)).toHaveLength(0);

    await user.type(box, "é");
    expect(await within(dialog).findByRole("listbox", { name: "Estudiantes encontrados" }, TIMEOUT)).toBeInTheDocument();
    const asked = requests().filter((sent) => sent.path === STUDENTS);
    expect(asked).toHaveLength(1);
    expect(Object.fromEntries(new URLSearchParams(asked[0]?.search))).toEqual({ page: "0", size: "10", sort: "lastName,asc", status: "ACTIVE", q: "Pé" });
    expect(box).toHaveAttribute("aria-expanded", "true");
    expect(box).toHaveAttribute("spellcheck", "false");
    expect(box).toHaveAttribute("autocomplete", "off");
    expect(within(dialog).getByRole("status")).toHaveTextContent("Se encontró 1 estudiante.");
  });

  it("says when no student matches, and offers a retry when the search fails", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openEnroll(user);
    route("get", STUDENTS, studentPage([]));
    await user.type(within(dialog).getByRole("combobox", { name: /Buscar estudiante/ }), "zz");
    expect(await within(dialog).findByText("No se encontró ningún estudiante activo con ese dato.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).queryByRole("listbox")).not.toBeInTheDocument();

    route("get", STUDENTS, problem("internal-error", 500), studentPage([PEREZ]));
    await user.type(within(dialog).getByRole("combobox", { name: /Buscar estudiante/ }), "z");
    expect(await within(dialog).findByText("No se pudo buscar estudiantes.", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Reintentar búsqueda" }));
    expect(await within(dialog).findByRole("option", { name: "Ana Pérez · E-000001" }, TIMEOUT)).toBeInTheDocument();
  });

  it("chooses with the pointer, shows the student chosen, and sends the exact body with the sections of the year grouped by modality and grade", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openEnroll(user);
    expect(await within(dialog).findByRole("group", { name: "Primaria · Primero" }, TIMEOUT)).toBeInTheDocument();
    // Only the active sections of the year in progress are offered.
    expect(within(dialog).getByRole("option", { name: "A" })).toBeInTheDocument();
    expect(within(dialog).queryByRole("option", { name: "C" })).not.toBeInTheDocument();
    await findAndChoose(user, dialog);
    expect(within(dialog).getByText("Ana Pérez · E-000001")).toBeInTheDocument();
    expect(within(dialog).queryByRole("combobox", { name: /Buscar estudiante/ })).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Cambiar estudiante" })).toHaveFocus();
    route("post", ENROLLMENTS, enrollmentRecord(studentEnrollment({ studentFirstName: "Ana", studentLastName: "Pérez" }), 201));
    await user.selectOptions(within(dialog).getByLabelText(/^Sección/), SECTION_B.id);
    fireEvent.change(within(dialog).getByLabelText(/Fecha de matrícula/), { target: { value: "2026-02-03" } });

    await user.click(within(dialog).getByRole("button", { name: "Matricular" }));

    expect(await screen.findByText("Se matriculó a «Ana Pérez».")).toBeInTheDocument();
    expect(bodyOf(posts()[0])).toEqual({ studentId: PEREZ.id, sectionId: SECTION_B.id, enrolledOn: "2026-02-03" });
    expect(posts()[0]?.path).toBe(ENROLLMENTS);
    expect(posts()[0]?.headers.has("Idempotency-Key")).toBe(false);
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "Matricular" })).toHaveFocus();
  });

  it("chooses with the keyboard alone: the arrows move through the results, Enter chooses and does not send the form", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openEnroll(user);
    route("get", STUDENTS, studentPage([PEREZ, student({ id: crypto.randomUUID(), firstName: "Luz", lastName: "Pérez", studentCode: "E-000009" })]));
    const box = within(dialog).getByRole("combobox", { name: /Buscar estudiante/ });
    await user.type(box, "Pér");
    await within(dialog).findByRole("listbox", undefined, TIMEOUT);

    await user.keyboard("{ArrowDown}{ArrowDown}{ArrowUp}");
    const first = within(dialog).getByRole("option", { name: "Ana Pérez · E-000001" });
    expect(first).toHaveAttribute("aria-selected", "true");
    expect(box).toHaveAttribute("aria-activedescendant", first.id);
    await user.keyboard("{Enter}");

    expect(within(dialog).getByText("Ana Pérez · E-000001")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
    // Changing the choice returns the focus to the box, empty.
    await user.click(within(dialog).getByRole("button", { name: "Cambiar estudiante" }));
    expect(within(dialog).getByRole("combobox", { name: /Buscar estudiante/ })).toHaveFocus();
    expect(within(dialog).getByRole("combobox", { name: /Buscar estudiante/ })).toHaveValue("Pér");
  });

  it("names what is missing, sends nothing, and starts the date on today", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openEnroll(user);
    expect(within(dialog).getByLabelText(/Fecha de matrícula/)).toHaveValue(
      new Intl.DateTimeFormat("en-CA", { timeZone: "America/Tegucigalpa" }).format(new Date()),
    );

    await user.click(within(dialog).getByRole("button", { name: "Matricular" }));

    expect(await within(dialog).findByText("Hay 2 campos con errores.")).toBeInTheDocument();
    expect(within(dialog).getByRole("combobox", { name: /Buscar estudiante/ })).toBeInvalid();
    expect(within(dialog).getByLabelText(/^Sección/)).toBeInvalid();
    expect(posts()).toHaveLength(0);
  });

  it.each([
    ["student-not-active", 409, admin.problem.studentNotActive],
    ["section-full", 409, admin.problem.sectionFull],
    ["duplicate-active-enrollment", 409, admin.problem.duplicateActiveEnrollment],
    ["academic-year-closed", 409, admin.problem.academicYearClosed],
    ["inactive-reference", 409, admin.problem.inactiveReference],
    ["resource-not-found", 404, admin.problem.notFound],
    ["forbidden", 403, admin.problem.forbidden],
  ] as const)("tells the %s answer in its own words, in the dialog, and keeps what was chosen", async (code, status, message) => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openEnroll(user);
    await within(dialog).findByRole("option", { name: "A" }, TIMEOUT);
    await findAndChoose(user, dialog);
    await user.selectOptions(within(dialog).getByLabelText(/^Sección/), SECTION_A.id);
    route("post", ENROLLMENTS, problem(code, status, { traceId: "trace-xyz" }));

    await user.click(within(dialog).getByRole("button", { name: "Matricular" }));

    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(message);
    expect(alert).toHaveTextContent("trace-xyz");
    expect(alert).toHaveFocus();
    expect(within(dialog).getByText("Ana Pérez · E-000001")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Sección/)).toHaveValue(SECTION_A.id);
    expect(screen.getByRole("dialog", { name: "Matricular estudiante" })).toBeInTheDocument();
  });

  it("puts the date out of range of a 400 under its field", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openEnroll(user);
    await within(dialog).findByRole("option", { name: "A" }, TIMEOUT);
    await findAndChoose(user, dialog);
    await user.selectOptions(within(dialog).getByLabelText(/^Sección/), SECTION_A.id);
    route("post", ENROLLMENTS, problem("validation-failed", 400, { errors: [{ field: "enrolledOn", reason: "enrollment-date-out-of-range" }] }));

    await user.click(within(dialog).getByRole("button", { name: "Matricular" }));

    expect(await within(dialog).findByText("Hay 1 campo con error.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Fecha de matrícula/)).toHaveAccessibleDescription(admin.field.dateOutOfRange);
    expect(within(dialog).getByRole("group", { name: "Hay 1 campo con error." })).toHaveFocus();
  });

  it("sends once when the button is pressed twice while the request is in flight", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openEnroll(user);
    await within(dialog).findByRole("option", { name: "A" }, TIMEOUT);
    await findAndChoose(user, dialog);
    await user.selectOptions(within(dialog).getByLabelText(/^Sección/), SECTION_A.id);
    route("post", ENROLLMENTS, enrollmentRecord(ANA, 201));
    const submit = within(dialog).getByRole("button", { name: "Matricular" });

    await user.dblClick(submit);

    await screen.findByText("Se matriculó a «Ana Pérez».");
    expect(posts()).toHaveLength(1);
  });

  it("tells that there is no year in progress, and refuses to offer sections", async () => {
    const user = userEvent.setup();
    routeLookups([NEXT_YEAR]);
    route("get", ENROLLMENTS, enrollmentPage([ANA]));
    renderFeature(enrollmentsFeature, OPEN);
    await loaded();

    const dialog = await openEnroll(user);

    expect(await within(dialog).findByText(admin.enrollments.enroll.noActiveYear, undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("returns the focus to the button that opened it when it is cancelled, and a new dialog starts empty", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openEnroll(user);
    await within(dialog).findByRole("option", { name: "A" }, TIMEOUT);
    await findAndChoose(user, dialog);

    await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "Matricular" })).toHaveFocus();
    const again = await openEnroll(user);
    expect(within(again).getByRole("combobox", { name: /Buscar estudiante/ })).toHaveValue("");
  });

  it("keeps the names and the search text out of the console and the storage", async () => {
    const user = userEvent.setup();
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((method) => vi.spyOn(console, method).mockImplementation(() => undefined));
    await openList();
    const dialog = await openEnroll(user);
    await within(dialog).findByRole("option", { name: "A" }, TIMEOUT);
    await findAndChoose(user, dialog, "Pérez");
    await user.selectOptions(within(dialog).getByLabelText(/^Sección/), SECTION_A.id);
    route("post", ENROLLMENTS, enrollmentRecord(ANA, 201));
    await user.click(within(dialog).getByRole("button", { name: "Matricular" }));
    await screen.findByText("Se matriculó a «Ana Pérez».");

    const output = JSON.stringify([spies.map((spy) => spy.mock.calls), Object.entries(localStorage), Object.entries(sessionStorage)]);
    for (const secret of ["Pérez", "Ana", "E-000001"]) expect(output).not.toContain(secret);
  });
});

describe("moving an enrollment to another section", () => {
  async function openTransfer(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole("button", { name: "Trasladar de sección a Ana Pérez" }));
    return screen.findByRole("dialog", { name: "Trasladar de sección" });
  }

  it("offers the active sections of the same year other than the current one, and sends the exact body", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openTransfer(user);
    await within(dialog).findByRole("option", { name: "B" }, TIMEOUT);
    // The section it is in and the inactive one are not offered.
    expect(within(dialog).queryByRole("option", { name: "A" })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("option", { name: "C" })).not.toBeInTheDocument();
    expect(requests().some((sent) => sent.path === `${ORG}/sections` && new URLSearchParams(sent.search).get("academicYearId") === ANA.academicYearId)).toBe(true);
    route("post", `${ENROLLMENTS}/${ANA.id}/transfer`, enrollmentRecord({ ...ANA, sectionId: SECTION_B.id, sectionName: "B", transfers: [enrollmentTransfer()] }));
    await user.selectOptions(within(dialog).getByLabelText(/^Sección nueva/), SECTION_B.id);
    fireEvent.change(within(dialog).getByLabelText(/Fecha del traslado/), { target: { value: "2026-03-02" } });
    await user.type(within(dialog).getByLabelText("Motivo"), "  Cambio de turno  ");

    await user.click(within(dialog).getByRole("button", { name: "Trasladar" }));

    expect(await screen.findByText("Se trasladó a «Ana Pérez» a la sección B.")).toBeInTheDocument();
    expect(posts()[0]?.path).toBe(`${ENROLLMENTS}/${ANA.id}/transfer`);
    expect(bodyOf(posts()[0])).toEqual({ toSectionId: SECTION_B.id, transferredOn: "2026-03-02", reason: "Cambio de turno" });
    // The list is read again after the write.
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(1);
    });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "Trasladar de sección a Ana Pérez" })).toHaveFocus();
  });

  it("leaves the reason out of the body when it is empty", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openTransfer(user);
    await within(dialog).findByRole("option", { name: "B" }, TIMEOUT);
    route("post", `${ENROLLMENTS}/${ANA.id}/transfer`, enrollmentRecord(ANA));
    await user.selectOptions(within(dialog).getByLabelText(/^Sección nueva/), SECTION_B.id);

    await user.click(within(dialog).getByRole("button", { name: "Trasladar" }));

    await screen.findByText(/Se trasladó a/);
    expect(Object.keys(bodyOf(posts()[0]) as object).sort()).toEqual(["toSectionId", "transferredOn"]);
  });

  it("refuses a reason shorter than three characters before sending, and names the field", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openTransfer(user);
    await within(dialog).findByRole("option", { name: "B" }, TIMEOUT);
    await user.selectOptions(within(dialog).getByLabelText(/^Sección nueva/), SECTION_B.id);
    await user.type(within(dialog).getByLabelText("Motivo"), "ab");

    await user.click(within(dialog).getByRole("button", { name: "Trasladar" }));

    expect(await within(dialog).findByText("Hay 1 campo con error.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Motivo")).toHaveAccessibleDescription(expect.stringContaining(admin.field.reasonLength));
    expect(posts()).toHaveLength(0);
  });

  it.each([
    ["section-full", 409, admin.problem.sectionFull],
    ["transfer-year-mismatch", 409, admin.problem.transferYearMismatch],
    ["invalid-enrollment-state", 409, admin.problem.invalidEnrollmentState],
    ["academic-year-closed", 409, admin.problem.academicYearClosed],
    ["inactive-reference", 409, admin.problem.inactiveReference],
    ["forbidden", 403, admin.problem.forbidden],
  ] as const)("tells the %s answer in its own words and keeps the dialog open", async (code, status, message) => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openTransfer(user);
    await within(dialog).findByRole("option", { name: "B" }, TIMEOUT);
    await user.selectOptions(within(dialog).getByLabelText(/^Sección nueva/), SECTION_B.id);
    route("post", `${ENROLLMENTS}/${ANA.id}/transfer`, problem(code, status));

    await user.click(within(dialog).getByRole("button", { name: "Trasladar" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
    expect(within(dialog).getByLabelText(/^Sección nueva/)).toHaveValue(SECTION_B.id);
  });

  it("puts the same-section and the date errors of a 400 under their fields", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openTransfer(user);
    await within(dialog).findByRole("option", { name: "B" }, TIMEOUT);
    await user.selectOptions(within(dialog).getByLabelText(/^Sección nueva/), SECTION_B.id);
    route(
      "post",
      `${ENROLLMENTS}/${ANA.id}/transfer`,
      problem("validation-failed", 400, {
        errors: [
          { field: "toSectionId", reason: "same-section" },
          { field: "transferredOn", reason: "enrollment-date-out-of-range" },
          { field: "reason", reason: "size" },
        ],
      }),
    );

    await user.click(within(dialog).getByRole("button", { name: "Trasladar" }));

    expect(await within(dialog).findByText("Hay 3 campos con errores.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Sección nueva/)).toHaveAccessibleDescription(admin.field.sameSection);
    expect(within(dialog).getByLabelText(/Fecha del traslado/)).toHaveAccessibleDescription(admin.field.dateOutOfRange);
    expect(within(dialog).getByLabelText("Motivo")).toHaveAccessibleDescription(expect.stringContaining(admin.field.reasonLength));
  });

  it("says that the year has no other section to move to", async () => {
    const user = userEvent.setup();
    routeLookups([YEAR], [SECTION_A]);
    route("get", ENROLLMENTS, enrollmentPage([ANA]));
    renderFeature(enrollmentsFeature, OPEN);
    await loaded();

    const dialog = await openTransfer(user);

    expect(await within(dialog).findByText(admin.enrollments.transfer.noSections, undefined, TIMEOUT)).toBeInTheDocument();
  });
});

describe("registering an exit", () => {
  async function openExit(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole("button", { name: "Registrar salida de Ana Pérez" }));
    return screen.findByRole("dialog", { name: "Registrar salida" });
  }

  it("sends the exact body of a withdrawal, takes the actions off the row and moves the focus to its detail", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openExit(user);
    route("post", `${ENROLLMENTS}/${ANA.id}/exit`, enrollmentRecord({ ...ANA, status: "WITHDRAWN", withdrawnOn: "2026-05-04", withdrawalReason: "Cambio de ciudad" }));
    route("get", ENROLLMENTS, enrollmentPage([{ ...ANA, status: "WITHDRAWN", withdrawnOn: "2026-05-04", withdrawalReason: "Cambio de ciudad" }]));
    fireEvent.change(within(dialog).getByLabelText(/Fecha de salida/), { target: { value: "2026-05-04" } });
    await user.type(within(dialog).getByLabelText(/Motivo/), "  Cambio de ciudad ");

    await user.click(within(dialog).getByRole("button", { name: "Registrar salida" }));

    expect(await screen.findByText("Se registró la salida de «Ana Pérez».")).toBeInTheDocument();
    expect(posts()[0]?.path).toBe(`${ENROLLMENTS}/${ANA.id}/exit`);
    expect(bodyOf(posts()[0])).toEqual({ status: "WITHDRAWN", withdrawnOn: "2026-05-04", withdrawalReason: "Cambio de ciudad" });
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Registrar salida de Ana Pérez" })).not.toBeInTheDocument();
    });
    expect(screen.queryByRole("button", { name: "Trasladar de sección a Ana Pérez" })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Ver detalle de la matrícula de Ana Pérez" })).toHaveFocus();
    });
  });

  it.each([
    ["WITHDRAWN", "Retiro"],
    ["TRANSFERRED", "Traslado a otra institución"],
  ] as const)("requires a reason for %s and sends the kind chosen", async (status, label) => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openExit(user);
    await user.selectOptions(within(dialog).getByLabelText(/Tipo de salida/), label);

    await user.click(within(dialog).getByRole("button", { name: "Registrar salida" }));

    expect(await within(dialog).findByText("Hay 1 campo con error.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Motivo/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Motivo/)).toHaveAccessibleDescription(expect.stringContaining("Este dato es obligatorio."));
    expect(posts()).toHaveLength(0);
    route("post", `${ENROLLMENTS}/${ANA.id}/exit`, enrollmentRecord({ ...ANA, status, withdrawnOn: "2026-05-04", withdrawalReason: "Otra escuela" }));
    await user.type(within(dialog).getByLabelText(/Motivo/), "Otra escuela");
    await user.click(within(dialog).getByRole("button", { name: "Registrar salida" }));
    await screen.findByText("Se registró la salida de «Ana Pérez».");
    expect((bodyOf(posts()[0]) as { status: string }).status).toBe(status);
  });

  it("does not require a reason for a graduation, and leaves it out of the body", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openExit(user);
    await user.selectOptions(within(dialog).getByLabelText(/Tipo de salida/), "Graduación");
    expect(within(dialog).getByLabelText(/Motivo/)).not.toBeRequired();
    route("post", `${ENROLLMENTS}/${ANA.id}/exit`, enrollmentRecord({ ...ANA, status: "GRADUATED", withdrawnOn: "2026-11-01" }));
    fireEvent.change(within(dialog).getByLabelText(/Fecha de salida/), { target: { value: "2026-11-01" } });

    await user.click(within(dialog).getByRole("button", { name: "Registrar salida" }));

    await screen.findByText("Se registró la salida de «Ana Pérez».");
    expect(bodyOf(posts()[0])).toEqual({ status: "GRADUATED", withdrawnOn: "2026-11-01" });
  });

  it.each([
    ["invalid-enrollment-state", 409, admin.problem.invalidEnrollmentState],
    ["academic-year-closed", 409, admin.problem.academicYearClosed],
    ["resource-not-found", 404, admin.problem.notFound],
    ["forbidden", 403, admin.problem.forbidden],
  ] as const)("tells the %s answer in its own words and keeps the dialog open", async (code, status, message) => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openExit(user);
    await user.type(within(dialog).getByLabelText(/Motivo/), "Cambio de ciudad");
    route("post", `${ENROLLMENTS}/${ANA.id}/exit`, problem(code, status));

    await user.click(within(dialog).getByRole("button", { name: "Registrar salida" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
    expect(within(dialog).getByLabelText(/Motivo/)).toHaveValue("Cambio de ciudad");
  });

  it("puts the date and reason errors of a 400 under their fields", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openExit(user);
    await user.type(within(dialog).getByLabelText(/Motivo/), "Cambio de ciudad");
    route(
      "post",
      `${ENROLLMENTS}/${ANA.id}/exit`,
      problem("validation-failed", 400, {
        errors: [
          { field: "withdrawnOn", reason: "enrollment-date-out-of-range" },
          { field: "withdrawalReason", reason: "size" },
        ],
      }),
    );

    await user.click(within(dialog).getByRole("button", { name: "Registrar salida" }));

    expect(await within(dialog).findByText("Hay 2 campos con errores.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Fecha de salida/)).toHaveAccessibleDescription(admin.field.dateOutOfRange);
    expect(within(dialog).getByLabelText(/Motivo/)).toHaveAccessibleDescription(expect.stringContaining(admin.field.reasonLength));
  });

  it("keeps the reason out of the caches, the console and the storage once it is sent", async () => {
    const user = userEvent.setup();
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((method) => vi.spyOn(console, method).mockImplementation(() => undefined));
    const { queryClient } = await openList();
    const dialog = await openExit(user);
    const REASON = "Motivo familiar muy privado";
    await user.type(within(dialog).getByLabelText(/Motivo/), REASON);
    route("post", `${ENROLLMENTS}/${ANA.id}/exit`, enrollmentRecord({ ...ANA, status: "WITHDRAWN", withdrawnOn: "2026-05-04", withdrawalReason: null }));
    await user.click(within(dialog).getByRole("button", { name: "Registrar salida" }));
    await screen.findByText("Se registró la salida de «Ana Pérez».");

    await waitFor(() => {
      expect(queryClient.getMutationCache().getAll()).toHaveLength(0);
    });
    expect(JSON.stringify(spies.map((spy) => spy.mock.calls))).not.toContain(REASON);
    expect(JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)])).not.toContain(REASON);
    expect(document.body.innerHTML).not.toContain(REASON);
  });
});

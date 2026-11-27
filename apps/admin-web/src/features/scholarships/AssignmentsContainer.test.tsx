import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { PERMISSION_CODES } from "../../shared/permissions/permission-codes.ts";
import { assignment, assignmentPage, assignmentRecord, discount, discountPage, problem, student, studentPage } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderScholarships } from "../../test/render-scholarships.tsx";

// The agreement assignments list over the real route, the real query layer and the real transport: the page the server serves (paged, filtered and
// ordered there, with all of it in the address), the filters by status, discount and student, the request of a new assignment with its refusals, and
// every state of the load.
installMswServer();

const ASSIGNMENTS = "/api/v1/scholarships/discount-assignments";
const DISCOUNTS = "/api/v1/scholarships/discounts";
const STUDENTS = "/api/v1/students";
const TIMEOUT = { timeout: 10_000 };
const STUDENT_ID = "00000000-0000-4000-8000-0000000000b1";
const DISCOUNT_ID = "00000000-0000-4000-8000-0000000e0101";
const PENDING = assignment({ id: "00000000-0000-4000-8000-0000000c0001", status: "pending" });
const APPROVED = assignment({ id: "00000000-0000-4000-8000-0000000c0002", studentCode: "EST-0002", status: "approved", validFrom: "2027-03-01", validTo: "2027-06-30" });

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === ASSIGNMENTS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const posts = () => requests().filter((sent) => sent.method === "POST" && sent.path === ASSIGNMENTS);
const without = (code: string) => PERMISSION_CODES.filter((candidate) => candidate !== code);

function routeLookups() {
  route("get", DISCOUNTS, discountPage([discount({ id: DISCOUNT_ID, code: "CONV20", name: "Convenio con la empresa", kind: "agreement" })]));
  route("get", STUDENTS, studentPage([student({ id: STUDENT_ID, studentCode: "EST-0001", firstName: "Ana", lastName: "Pérez" })]));
}

async function openList(rows: unknown[] = [PENDING, APPROVED], path = "/scholarships/discount-assignments", permissions?: readonly string[]) {
  routeLookups();
  route("get", ASSIGNMENTS, assignmentPage(rows));
  const rendered = renderScholarships(path, permissions === undefined ? {} : { permissions });
  const table = await screen.findByRole("table", { name: "Asignaciones de descuentos de convenio" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

async function openRequest(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Solicitar asignación" }));
  return screen.findByRole("dialog", { name: "Solicitar una asignación" }, TIMEOUT);
}

async function fillRequest(dialog: HTMLElement, user: ReturnType<typeof userEvent.setup>, dates: { from: string; to: string } = { from: "2027-01-01", to: "2027-12-31" }) {
  await user.type(within(dialog).getByRole("combobox", { name: /^Estudiante/ }), "Ana");
  await user.click(await within(dialog).findByRole("option", { name: "Pérez, Ana (EST-0001)" }, TIMEOUT));
  await user.selectOptions(await within(dialog).findByLabelText(/^Descuento de convenio/), DISCOUNT_ID);
  await user.type(within(dialog).getByLabelText(/^Vigente desde/), dates.from);
  await user.type(within(dialog).getByLabelText(/^Vigente hasta/), dates.to);
}

describe("the list of assignments", () => {
  it("shows each assignment with its student, discount, validity and state in words, and asks the server for the first page", async () => {
    await openList();

    expect(screen.getByRole("heading", { level: 2, name: "Asignaciones de convenio" })).toBeInTheDocument();
    const pending = within(screen.getByRole("row", { name: /EST-0001/ }));
    expect(pending.getByText("Pendiente de aprobación")).toBeInTheDocument();
    expect(pending.getByText("CONV20 · Convenio con la empresa")).toBeInTheDocument();
    expect(pending.getByRole("link")).toHaveAttribute("href", `/scholarships/discount-assignments/${PENDING.id}`);
    expect(within(screen.getByRole("row", { name: /EST-0002/ })).getByText("Aprobado")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("status")).toBe(false);
  });

  it("asks the server for the status and the discount the person chooses, from the first page, and writes them in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    const filters = within(screen.getByRole("group", { name: "Filtros" }));

    await user.selectOptions(filters.getByLabelText("Estado"), "Pendiente de aprobación");
    await waitFor(() => {
      expect(lastQuery().get("status")).toBe("pending");
    }, TIMEOUT);
    await user.selectOptions(await filters.findByLabelText("Descuento"), DISCOUNT_ID);

    await waitFor(() => {
      expect(lastQuery().get("discountId")).toBe(DISCOUNT_ID);
    }, TIMEOUT);
    expect(lastQuery().get("page")).toBe("0");
    expect(router.state.location.searchStr).toContain("status=pending");
    expect(router.state.location.searchStr).toContain(`discountId=${DISCOUNT_ID}`);
  });

  it("asks the server for the order of a validity column and keeps it in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();

    await user.click(screen.getByRole("button", { name: "Vigente desde" }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("validFrom,asc");
    }, TIMEOUT);
    expect(decodeURIComponent(router.state.location.searchStr)).toContain("sort=validFrom:asc");
  });

  it("opens from a link already pointed at a student, names them by their code and lets the person remove the filter", async () => {
    const user = userEvent.setup();
    await openList([PENDING], `/scholarships/discount-assignments?studentId=${STUDENT_ID}`);

    expect(lastQuery().get("studentId")).toBe(STUDENT_ID);
    expect(screen.getByText("EST-0001", { selector: "p" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Quitar estudiante" }));

    await waitFor(() => {
      expect(lastQuery().has("studentId")).toBe(false);
    }, TIMEOUT);
  });

  it("filters by a student chosen with the search, which the server does", async () => {
    const user = userEvent.setup();
    await openList();
    const filters = within(screen.getByRole("group", { name: "Filtros" }));

    await user.type(filters.getByRole("combobox", { name: /^Estudiante/ }), "Ana");
    await user.click(await screen.findByRole("option", { name: "Pérez, Ana (EST-0001)" }, TIMEOUT));

    await waitFor(() => {
      expect(lastQuery().get("studentId")).toBe(STUDENT_ID);
    }, TIMEOUT);
    expect(await screen.findByText("Pérez, Ana (EST-0001)", { selector: "p" }, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the empty state with the way forward", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay asignaciones")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Solicitar una asignación" })).toBeInTheDocument();
  });

  it("shows no matches, not the empty state, when the filters leave nothing", async () => {
    await openList([], "/scholarships/discount-assignments?status=revoked");

    expect(screen.getByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Quitar filtros" })).toBeInTheDocument();
  });

  it("shows the error state with the trace and loads the list on retry", async () => {
    const user = userEvent.setup();
    routeLookups();
    route("get", ASSIGNMENTS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), assignmentPage([PENDING]));
    renderScholarships("/scholarships/discount-assignments");

    expect(await screen.findByText("No se pudieron cargar las asignaciones", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /EST-0001/ }, TIMEOUT)).toBeInTheDocument();
  });

  it("tells that the discounts of the filter could not be loaded, with a retry", async () => {
    route("get", DISCOUNTS, problem("internal-error", 500));
    route("get", STUDENTS, studentPage([]));
    route("get", ASSIGNMENTS, assignmentPage([PENDING]));
    renderScholarships("/scholarships/discount-assignments");

    expect(await screen.findByText("No se pudieron cargar las opciones de los filtros y de los formularios.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();
  });
});

describe("the request of an assignment", () => {
  it("sends what the person chose, tells that the assignment is pending, and lists it", async () => {
    const user = userEvent.setup();
    await openList();
    route("post", ASSIGNMENTS, assignmentRecord(assignment({ studentCode: "EST-0001" }), 201));
    const dialog = await openRequest(user);

    await fillRequest(dialog, user);
    await user.click(within(dialog).getByRole("button", { name: "Solicitar asignación" }));

    expect(await screen.findByText(/Se solicitó la asignación para el estudiante EST-0001\. Queda pendiente hasta que otra persona la apruebe\./, undefined, TIMEOUT)).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(JSON.parse(posts()[0]?.body ?? "{}")).toEqual({ studentId: STUDENT_ID, discountId: DISCOUNT_ID, validFrom: "2027-01-01", validTo: "2027-12-31" });
    expect(screen.queryByRole("dialog", { name: "Solicitar una asignación" })).not.toBeInTheDocument();
  });

  it("offers only the active agreement discounts and starts with the student of the address already chosen", async () => {
    const user = userEvent.setup();
    await openList([PENDING], `/scholarships/discount-assignments?studentId=${STUDENT_ID}`);
    const dialog = await openRequest(user);

    expect(within(dialog).queryByRole("combobox", { name: /^Estudiante/ })).not.toBeInTheDocument();
    expect(within(dialog).getByRole("group", { name: "Estudiante elegido" })).toBeInTheDocument();
    const lookups = requests().filter((sent) => sent.path === DISCOUNTS).map((sent) => new URLSearchParams(sent.search));
    expect(lookups.some((query) => query.get("kind") === "agreement" && query.get("active") === "true")).toBe(true);
  });

  it("says that there is no agreement discount to assign when none is active", async () => {
    const user = userEvent.setup();
    routeLookups();
    route("get", DISCOUNTS, discountPage([]));
    route("get", ASSIGNMENTS, assignmentPage([PENDING]));
    renderScholarships("/scholarships/discount-assignments");
    await screen.findByRole("table", { name: "Asignaciones de descuentos de convenio" }, TIMEOUT);
    const dialog = await openRequest(user);

    expect(await within(dialog).findByText(/No hay descuentos de convenio activos/, undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("refuses an end before the start, and a form left empty, before sending anything", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openRequest(user);

    await user.click(within(dialog).getByRole("button", { name: "Solicitar asignación" }));
    expect((await within(dialog).findAllByText("Este dato es obligatorio.")).length).toBeGreaterThan(0);
    await fillRequest(dialog, user, { from: "2027-06-01", to: "2027-03-01" });
    await user.click(within(dialog).getByRole("button", { name: "Solicitar asignación" }));

    expect((await within(dialog).findAllByText("La fecha de fin no puede ser anterior a la de inicio.")).length).toBeGreaterThan(0);
    expect(posts()).toHaveLength(0);
  });

  it.each([
    ["discount-assignment-overlap", 409, /ya tiene aprobada una asignación de este descuento con fechas que se cruzan/],
    ["inactive-reference", 409, /Uno de los datos relacionados está inactivo/],
    ["resource-not-found", 404, /El registro ya no existe o no está disponible/],
    ["forbidden", 403, /No tiene permiso para realizar esta acción/],
  ] as const)("tells the %s refusal inside the dialog and keeps it open", async (code, status, text) => {
    const user = userEvent.setup();
    await openList();
    route("post", ASSIGNMENTS, problem(code, status, { traceId: "01J8Z4K2QF9P0X" }));
    const dialog = await openRequest(user);

    await fillRequest(dialog, user);
    await user.click(within(dialog).getByRole("button", { name: "Solicitar asignación" }));

    expect(await within(dialog).findByText(text, undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Solicitar una asignación" })).toBeInTheDocument();
  });

  it("is not offered to a session without scholarships:create", async () => {
    await openList([PENDING], "/scholarships/discount-assignments", without("scholarships:create"));

    expect(screen.queryByRole("button", { name: "Solicitar asignación" })).not.toBeInTheDocument();
  });
});

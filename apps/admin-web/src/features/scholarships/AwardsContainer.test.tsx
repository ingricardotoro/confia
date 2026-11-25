import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { PERMISSION_CODES } from "../../shared/permissions/permission-codes.ts";
import { academicYear, award, awardPage, awardRecord, organizationPage, problem, scholarship, scholarshipPage, student, studentPage } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderScholarships } from "../../test/render-scholarships.tsx";

// The awards list over the real route, the real query layer and the real transport: the page the server serves (paged, filtered and ordered there,
// with all of it in the address), the filters by status, scholarship and student, the request of a new award with its refusals, and every state of the load.
installMswServer();

const AWARDS = "/api/v1/scholarship-awards";
const SCHOLARSHIPS = "/api/v1/scholarships";
const STUDENTS = "/api/v1/students";
const YEARS = "/api/v1/organization/academic-years";
const TIMEOUT = { timeout: 10_000 };
const STUDENT_ID = "00000000-0000-4000-8000-0000000000b1";
const SCHOLARSHIP_ID = "00000000-0000-4000-8000-0000000e0001";
const YEAR_ID = "00000000-0000-4000-8000-000000000a01";
const PENDING = award({ id: "00000000-0000-4000-8000-0000000f0001", status: "pending" });
const APPROVED = award({ id: "00000000-0000-4000-8000-0000000f0002", studentCode: "EST-0002", status: "approved", validFrom: "2027-03-01", validTo: "2027-06-30" });

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === AWARDS);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const posts = () => requests().filter((sent) => sent.method === "POST" && sent.path === AWARDS);
const without = (code: string) => PERMISSION_CODES.filter((candidate) => candidate !== code);

function routeLookups() {
  route("get", SCHOLARSHIPS, scholarshipPage([scholarship({ id: SCHOLARSHIP_ID, code: "BECA25", name: "Beca de excelencia" })]));
  route("get", STUDENTS, studentPage([student({ id: STUDENT_ID, studentCode: "EST-0001", firstName: "Ana", lastName: "Pérez" })]));
  route("get", YEARS, organizationPage("academic-years", [academicYear({ id: YEAR_ID, name: "Año lectivo 2027" })]));
}

async function openList(rows: unknown[] = [PENDING, APPROVED], path = "/scholarships/awards", permissions?: readonly string[]) {
  routeLookups();
  route("get", AWARDS, awardPage(rows));
  const rendered = renderScholarships(path, permissions === undefined ? {} : { permissions });
  const table = await screen.findByRole("table", { name: "Otorgamientos de becas" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

async function openRequest(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Solicitar otorgamiento" }));
  return screen.findByRole("dialog", { name: "Solicitar un otorgamiento" }, TIMEOUT);
}

async function fillRequest(dialog: HTMLElement, user: ReturnType<typeof userEvent.setup>, dates: { from: string; to: string } = { from: "2027-01-01", to: "2027-12-31" }) {
  await user.type(within(dialog).getByRole("combobox", { name: /^Estudiante/ }), "Ana");
  await user.click(await within(dialog).findByRole("option", { name: "Pérez, Ana (EST-0001)" }, TIMEOUT));
  await user.selectOptions(await within(dialog).findByLabelText(/^Beca/), SCHOLARSHIP_ID);
  await user.selectOptions(await within(dialog).findByLabelText(/^Año lectivo/), YEAR_ID);
  await user.type(within(dialog).getByLabelText(/^Vigente desde/), dates.from);
  await user.type(within(dialog).getByLabelText(/^Vigente hasta/), dates.to);
}

describe("the list of awards", () => {
  it("shows each award with its student, scholarship, validity and state in words, and asks the server for the first page", async () => {
    await openList();

    expect(screen.getByRole("heading", { level: 2, name: "Otorgamientos" })).toBeInTheDocument();
    const pending = within(screen.getByRole("row", { name: /EST-0001/ }));
    expect(pending.getByText("Pendiente de aprobación")).toBeInTheDocument();
    expect(pending.getByText("BECA25 · Beca de excelencia")).toBeInTheDocument();
    expect(pending.getByRole("link")).toHaveAttribute("href", `/scholarships/awards/${PENDING.id}`);
    expect(within(screen.getByRole("row", { name: /EST-0002/ })).getByText("Aprobado")).toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("status")).toBe(false);
  });

  it("asks the server for the status and the scholarship the person chooses, from the first page, and writes them in the address", async () => {
    const user = userEvent.setup();
    const { router } = await openList();
    const filters = within(screen.getByRole("group", { name: "Filtros" }));

    await user.selectOptions(filters.getByLabelText("Estado"), "Pendiente de aprobación");
    await waitFor(() => {
      expect(lastQuery().get("status")).toBe("pending");
    }, TIMEOUT);
    await user.selectOptions(await filters.findByLabelText("Beca"), SCHOLARSHIP_ID);

    await waitFor(() => {
      expect(lastQuery().get("scholarshipId")).toBe(SCHOLARSHIP_ID);
    }, TIMEOUT);
    expect(lastQuery().get("page")).toBe("0");
    expect(router.state.location.searchStr).toContain("status=pending");
    expect(router.state.location.searchStr).toContain(`scholarshipId=${SCHOLARSHIP_ID}`);
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

  it("opens from the link of a student already pointed at them, names them by their code and lets the person remove the filter", async () => {
    const user = userEvent.setup();
    await openList([PENDING], `/scholarships/awards?studentId=${STUDENT_ID}`);

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
    const search = requests().filter((sent) => sent.path === STUDENTS).map((sent) => new URLSearchParams(sent.search).get("q"));
    expect(search).toContain("Ana");
  });

  it("shows the empty state with the way forward", async () => {
    await openList([]);

    expect(screen.getByText("Todavía no hay otorgamientos")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Solicitar un otorgamiento" })).toBeInTheDocument();
  });

  it("shows no matches, not the empty state, when the filters leave nothing", async () => {
    await openList([], "/scholarships/awards?status=revoked");

    expect(screen.getByText("Ningún registro coincide con los filtros")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Quitar filtros" })).toBeInTheDocument();
  });

  it("shows the error state with the trace and loads the list on retry", async () => {
    const user = userEvent.setup();
    routeLookups();
    route("get", AWARDS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), awardPage([PENDING]));
    renderScholarships("/scholarships/awards");

    expect(await screen.findByText("No se pudieron cargar los otorgamientos", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /EST-0001/ }, TIMEOUT)).toBeInTheDocument();
  });

  it("tells that the scholarships of the filter could not be loaded, with a retry", async () => {
    route("get", SCHOLARSHIPS, problem("internal-error", 500));
    route("get", STUDENTS, studentPage([]));
    route("get", YEARS, organizationPage("academic-years", []));
    route("get", AWARDS, awardPage([PENDING]));
    renderScholarships("/scholarships/awards");

    expect(await screen.findByText("No se pudieron cargar las opciones de los filtros y de los formularios.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();
  });
});

describe("the request of an award", () => {
  it("sends what the person chose, tells that the award is pending, and lists it", async () => {
    const user = userEvent.setup();
    await openList();
    route("post", AWARDS, awardRecord(award({ studentCode: "EST-0001" }), 201));
    const dialog = await openRequest(user);

    await fillRequest(dialog, user);
    await user.click(within(dialog).getByRole("button", { name: "Solicitar otorgamiento" }));

    expect(await screen.findByText(/Se solicitó el otorgamiento para el estudiante EST-0001\. Queda pendiente hasta que otra persona lo apruebe\./, undefined, TIMEOUT)).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(JSON.parse(posts()[0]?.body ?? "{}")).toEqual({
      studentId: STUDENT_ID,
      scholarshipId: SCHOLARSHIP_ID,
      academicYearId: YEAR_ID,
      validFrom: "2027-01-01",
      validTo: "2027-12-31",
    });
    expect(screen.queryByRole("dialog", { name: "Solicitar un otorgamiento" })).not.toBeInTheDocument();
  });

  it("offers only the active scholarships and starts with the student of the address already chosen", async () => {
    const user = userEvent.setup();
    await openList([PENDING], `/scholarships/awards?studentId=${STUDENT_ID}`);
    const dialog = await openRequest(user);

    expect(within(dialog).queryByRole("combobox", { name: /^Estudiante/ })).not.toBeInTheDocument();
    expect(within(dialog).getByRole("group", { name: "Estudiante elegido" })).toBeInTheDocument();
    const lookups = requests().filter((sent) => sent.path === SCHOLARSHIPS).map((sent) => new URLSearchParams(sent.search).get("active"));
    expect(lookups).toContain("true");
  });

  it("refuses an end before the start, and a form left empty, before sending anything", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openRequest(user);

    await user.click(within(dialog).getByRole("button", { name: "Solicitar otorgamiento" }));
    expect((await within(dialog).findAllByText("Este dato es obligatorio.")).length).toBeGreaterThan(0);
    await fillRequest(dialog, user, { from: "2027-06-01", to: "2027-03-01" });
    await user.click(within(dialog).getByRole("button", { name: "Solicitar otorgamiento" }));

    expect((await within(dialog).findAllByText("La fecha de fin no puede ser anterior a la de inicio.")).length).toBeGreaterThan(0);
    expect(posts()).toHaveLength(0);
  });

  it.each([
    ["scholarship-award-overlap", 409, /ya tiene aprobado un otorgamiento de esta beca con fechas que se cruzan/],
    ["inactive-reference", 409, /Uno de los datos relacionados está inactivo/],
    ["resource-not-found", 404, /El registro ya no existe o no está disponible/],
    ["forbidden", 403, /No tiene permiso para realizar esta acción/],
  ] as const)("tells the %s refusal inside the dialog and keeps it open", async (code, status, text) => {
    const user = userEvent.setup();
    await openList();
    route("post", AWARDS, problem(code, status, { traceId: "01J8Z4K2QF9P0X" }));
    const dialog = await openRequest(user);

    await fillRequest(dialog, user);
    await user.click(within(dialog).getByRole("button", { name: "Solicitar otorgamiento" }));

    expect(await within(dialog).findByText(text, undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Solicitar un otorgamiento" })).toBeInTheDocument();
  });

  it("puts the outside-academic-year refusal under the start of the validity and keeps the dialog open", async () => {
    const user = userEvent.setup();
    await openList();
    route("post", AWARDS, problem("validation-failed", 400, { errors: [{ field: "validFrom", reason: "outside-academic-year" }] }));
    const dialog = await openRequest(user);

    await fillRequest(dialog, user);
    await user.click(within(dialog).getByRole("button", { name: "Solicitar otorgamiento" }));

    expect((await within(dialog).findAllByText("La vigencia debe quedar dentro del año lectivo elegido.", undefined, TIMEOUT)).length).toBeGreaterThan(0);
    expect(screen.getByRole("dialog", { name: "Solicitar un otorgamiento" })).toBeInTheDocument();
  });

  it("is not offered to a session without scholarships:create", async () => {
    await openList([PENDING], "/scholarships/awards", without("scholarships:create"));

    expect(screen.queryByRole("button", { name: "Solicitar otorgamiento" })).not.toBeInTheDocument();
  });
});

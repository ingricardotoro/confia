import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { OTHER_YEAR_ID, YEAR_ID, acta, actaPage, jsonReply, preview } from "../../shared/api/reconciliation-fixtures.ts";
import { academicYear, importBatchPage, organizationPage, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route, type Reply } from "../../test/msw-server.ts";
import { renderFeature } from "../../test/render-feature.tsx";
import { importsFeature } from "./feature.tsx";

// The section of the acta of the reconciliation over the real route, query layer and transport with the test server answering: the
// form and its checks, the preview and its difference in words, the signing and what it refreshes, every answer the server can give,
// and the four states of each list.
installMswServer();

const IMPORTS = "/api/v1/imports";
const RECONCILIATIONS = `${IMPORTS}/reconciliations`;
const PREVIEW = `${RECONCILIATIONS}/preview`;
const YEARS = "/api/v1/organization/academic-years";
const TIMEOUT = { timeout: 10_000 };
const SIGNER = "99999999-9999-4999-8999-999999999999";

const activeYear = academicYear({ id: YEAR_ID, name: "Año lectivo 2026", status: "ACTIVE" });
const closedYear = academicYear({ id: OTHER_YEAR_ID, name: "Año lectivo 2025", status: "CLOSED" });

const posts = () => requests().filter((sent) => sent.method === "POST" && sent.path === RECONCILIATIONS);
const lists = () => requests().filter((sent) => sent.method === "GET" && sent.path === RECONCILIATIONS);
const previews = () => requests().filter((sent) => sent.method === "GET" && sent.path === PREVIEW);

afterEach(() => {
  vi.restoreAllMocks();
});

interface Answers {
  readonly years?: Reply;
  readonly previews?: readonly [Reply, ...Reply[]];
  readonly lists?: readonly [Reply, ...Reply[]];
}

async function openScreen(answers: Answers = {}, url = "/imports") {
  route("get", IMPORTS, importBatchPage([]));
  route("get", YEARS, answers.years ?? organizationPage("academic-years", [activeYear, closedYear]));
  const previewReplies: readonly [Reply, ...Reply[]] = answers.previews ?? [jsonReply(preview())];
  const listReplies: readonly [Reply, ...Reply[]] = answers.lists ?? [jsonReply(actaPage([]))];
  route("get", PREVIEW, ...previewReplies);
  route("get", RECONCILIATIONS, ...listReplies);
  const app = renderFeature(importsFeature, url);
  const user = userEvent.setup();
  await screen.findByRole("heading", { level: 2, name: "Acta de conciliación" }, TIMEOUT);
  const section = () => within(screen.getByRole("region", { name: "Acta de conciliación" }));
  return { app, user, section };
}

const yearField = () => screen.getByLabelText(/^Año lectivo/);
const referenceField = () => screen.getByLabelText(/^Referencia del listado oficial/);
const countField = () => screen.getByLabelText(/^Total de matrículas del listado oficial/);
const signButton = () => screen.getByRole("button", { name: /Firmar acta/ });

async function fill(user: ReturnType<typeof userEvent.setup>, reference: string, count: string) {
  // Pasting is as good as typing for the form and much faster for a long reference.
  await user.clear(referenceField());
  await user.click(referenceField());
  await user.paste(reference);
  await user.clear(countField());
  await user.click(countField());
  await user.paste(count);
}

describe("the form and the preview", () => {
  it("chooses the active year, asks the server for its counts and shows them", async () => {
    const { section } = await openScreen();

    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);
    const table = await section().findByRole("table", { name: /Registros creados y rechazados/ }, TIMEOUT);
    await waitFor(() => {
      expect(within(table).getByText("Estudiantes")).toBeInTheDocument();
    }, TIMEOUT);
    expect(Object.fromEntries(new URLSearchParams(previews()[0]?.search))).toEqual({ academicYearId: YEAR_ID });
    expect(within(yearField()).getAllByRole("option").map((option) => option.textContent)).toEqual(["Elija un año lectivo", "Año lectivo 2026", "Año lectivo 2025"]);
  });

  it("asks again for the counts of another year when it is chosen", async () => {
    const { user } = await openScreen();
    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);

    await user.selectOptions(yearField(), OTHER_YEAR_ID);

    await waitFor(() => {
      expect(previews().map((sent) => new URLSearchParams(sent.search).get("academicYearId"))).toContain(OTHER_YEAR_ID);
    }, TIMEOUT);
  });

  it("asks for a year, and for no counts, when the account has no active year", async () => {
    const { section } = await openScreen({ years: organizationPage("academic-years", [closedYear]) });

    await waitFor(() => {
      expect(yearField()).toBeEnabled();
    }, TIMEOUT);
    expect(yearField()).toHaveValue("");
    expect(section().getByText("Elija un año lectivo para ver sus conteos.")).toBeInTheDocument();
    expect(previews()).toHaveLength(0);
  });

  it.each([
    ["120", "El sistema tiene 2 matrículas activas menos que el listado oficial."],
    ["100", "El sistema tiene 18 matrículas activas más que el listado oficial."],
    ["118", "El sistema tiene las mismas matrículas activas que el listado oficial."],
    ["0", "El sistema tiene 118 matrículas activas más que el listado oficial."],
  ])("with an official total of %s says the difference in words", async (total, sentence) => {
    const { user, section } = await openScreen();
    await section().findByText("Matrículas activas del año lectivo", undefined, TIMEOUT);

    await user.type(countField(), total);

    expect(section().getByText(sentence)).toBeInTheDocument();
  });

  it("shows no difference while the total is not a whole number", async () => {
    const { user, section } = await openScreen();
    await section().findByText("Matrículas activas del año lectivo", undefined, TIMEOUT);

    await user.type(countField(), "-3");

    expect(section().queryByText(/El sistema tiene/)).not.toBeInTheDocument();
    expect(section().getByText("Escriba el total del listado oficial para ver la diferencia.")).toBeInTheDocument();
  });

  it("tells a preview of a year that is not available, and one that failed with a retry that loads it", async () => {
    const { user, section } = await openScreen({ previews: [problem("resource-not-found", 404, { traceId: "tr-404" }), jsonReply(preview())] });

    expect(await section().findByText(/El año lectivo no existe o ya no está disponible/, undefined, TIMEOUT)).toHaveTextContent("tr-404");
    await user.click(section().getByRole("button", { name: "Reintentar" }));
    expect(await section().findByText("Matrículas activas del año lectivo", undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("tells a 403 on the preview in plain words", async () => {
    const { section } = await openScreen({ previews: [problem("forbidden", 403)] });

    expect(await section().findByText(/No tiene permiso para realizar esta acción/, undefined, TIMEOUT)).toBeInTheDocument();
  });
});

describe("the years of the selector", () => {
  it("when the account cannot read them, says so with a retry that brings the selector", async () => {
    const { user, section } = await openScreen({ years: problem("forbidden", 403) });

    expect(await section().findByText("No se pudieron cargar los años lectivos", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Año lectivo/)).not.toBeInTheDocument();
    route("get", YEARS, organizationPage("academic-years", [activeYear]));
    await user.click(section().getByRole("button", { name: "Reintentar" }));

    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);
  });

  it("when there are none, says where to create one", async () => {
    const { section } = await openScreen({ years: organizationPage("academic-years", []) });

    expect(await section().findByText("Todavía no hay años lectivos", undefined, TIMEOUT)).toBeInTheDocument();
  });
});

describe("the checks in the browser", () => {
  it("lists what is missing in a summary, moves the focus to it and sends nothing", async () => {
    const { user } = await openScreen();
    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);

    await user.click(signButton());

    const summary = await screen.findByRole("group", { name: /campos? con errores?/ }, TIMEOUT);
    expect(summary).toHaveFocus();
    expect(within(summary).getAllByRole("link").map((link) => link.getAttribute("href"))).toEqual(["#reconciliation-reference", "#reconciliation-official-count"]);
    expect(referenceField()).toHaveAccessibleDescription(/Este dato es obligatorio/);
    expect(countField()).toBeInvalid();
    expect(posts()).toHaveLength(0);
  });

  it("refuses a reference of only spaces as empty", async () => {
    const { user } = await openScreen();
    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);

    await fill(user, "     ", "10");
    await user.click(signButton());

    expect(await screen.findByRole("group", { name: /campo con error/ }, TIMEOUT)).toBeInTheDocument();
    expect(referenceField()).toHaveAccessibleDescription(/Este dato es obligatorio/);
    expect(posts()).toHaveLength(0);
  });

  it("refuses a reference of 101 characters and a total that is not a whole number or is negative", async () => {
    const { user } = await openScreen();
    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);

    await fill(user, "A".repeat(101), "-1");
    await user.click(signButton());

    await screen.findByRole("group", { name: /campos? con errores?/ }, TIMEOUT);
    expect(referenceField()).toHaveAccessibleDescription(/demasiado largo/);
    expect(countField()).toHaveAccessibleDescription(/número entero de cero en adelante/);
    expect(posts()).toHaveLength(0);
  });

  it("puts the server's field errors under their fields and keeps what was typed", async () => {
    route(
      "post",
      RECONCILIATIONS,
      problem("validation-failed", 400, {
        errors: [
          { field: "officialReference", reason: "pattern" },
          { field: "officialEnrollmentCount", reason: "max" },
        ],
      }),
    );
    const { user } = await openScreen();
    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);

    await fill(user, "Oficio 1", "10");
    await user.click(signButton());

    await waitFor(() => {
      expect(referenceField()).toHaveAccessibleDescription(/Este dato no es válido/);
    }, TIMEOUT);
    expect(countField()).toHaveAccessibleDescription(/demasiado largo/);
    expect(referenceField()).toHaveValue("Oficio 1");
    expect(countField()).toHaveValue("10");
  });
});

describe("signing", () => {
  it("sends the year, the reference and the total, and then shows the success, lets go of what was typed and reads the lists again", async () => {
    const signed = acta({ officialReference: "Oficio 7", officialEnrollmentCount: 120, enrollmentsActive: 118, difference: -2, signedBy: SIGNER });
    route("post", RECONCILIATIONS, jsonReply(signed, 201));
    const { user, section } = await openScreen({ lists: [jsonReply(actaPage([])), jsonReply(actaPage([signed]))] });
    await section().findByText("Aún no hay actas firmadas", undefined, TIMEOUT);
    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);
    const previewsBefore = previews().length;

    await fill(user, "Oficio 7", "120");
    await user.click(signButton());

    const notice = await section().findByText("Acta firmada", undefined, TIMEOUT);
    expect(JSON.parse(posts()[0]?.body ?? "")).toEqual({ academicYearId: YEAR_ID, officialReference: "Oficio 7", officialEnrollmentCount: 120 });
    expect(posts()[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(notice.closest("[role=status]")).toHaveFocus();
    expect(notice.closest("[role=status]")).toHaveTextContent("Año lectivo 2026");
    expect(notice.closest("[role=status]")).toHaveTextContent("«Oficio 7»");
    expect(notice.closest("[role=status]")).toHaveTextContent("El sistema tiene 2 matrículas activas menos que el listado oficial.");
    await waitFor(() => {
      expect(referenceField()).toHaveValue("");
    }, TIMEOUT);
    expect(countField()).toHaveValue("");
    expect(yearField()).toHaveValue(YEAR_ID);
    // The history shows the new row, with the year by name and without the account that signed.
    const table = await section().findByRole("table", { name: /Actas de conciliación firmadas/ }, TIMEOUT);
    expect(await within(table).findByText("Oficio 7", undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(table).getByText("Año lectivo 2026")).toBeInTheDocument();
    expect(within(table).getByText("2 menos")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain(SIGNER);
    expect(lists().length).toBeGreaterThanOrEqual(2);
    expect(previews().length).toBeGreaterThan(previewsBefore);
    // No error is left over from emptying the fields.
    expect(referenceField()).not.toBeInvalid();
  });

  it("signs a second time and keeps both actas in the history", async () => {
    const first = acta({ officialReference: "Oficio 1", difference: -2 });
    const second = acta({ officialReference: "Oficio 2", officialEnrollmentCount: 118, difference: 0 });
    route("post", RECONCILIATIONS, jsonReply(first, 201), jsonReply(second, 201));
    const { user, section } = await openScreen({ lists: [jsonReply(actaPage([])), jsonReply(actaPage([first])), jsonReply(actaPage([second, first]))] });
    await section().findByText("Aún no hay actas firmadas", undefined, TIMEOUT);
    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);

    await fill(user, "Oficio 1", "120");
    await user.click(signButton());
    await within(await section().findByRole("table", { name: /Actas de conciliación firmadas/ }, TIMEOUT)).findByText("Oficio 1", undefined, TIMEOUT);
    await waitFor(() => {
      expect(referenceField()).toHaveValue("");
    }, TIMEOUT);
    await fill(user, "Oficio 2", "118");
    await user.click(signButton());

    await waitFor(() => {
      const rows = within(screen.getByRole("table", { name: /Actas de conciliación firmadas/ })).getAllByRole("row").slice(1);
      expect(rows.map((row) => within(row).getAllByRole("cell")[2]?.textContent)).toEqual(["Oficio 2", "Oficio 1"]);
    }, TIMEOUT);
    expect(posts()).toHaveLength(2);
  });

  it("tells a 404 for the year, keeps what was typed and shows no success", async () => {
    route("post", RECONCILIATIONS, problem("resource-not-found", 404));
    const { user, section } = await openScreen();
    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);

    await fill(user, "Oficio 7", "120");
    await user.click(signButton());

    const alert = await screen.findByText(/El año lectivo no existe o ya no está disponible/, undefined, TIMEOUT);
    expect(alert.closest("[role=alert]")).toHaveFocus();
    expect(section().queryByText("Acta firmada")).not.toBeInTheDocument();
    expect(referenceField()).toHaveValue("Oficio 7");
    expect(countField()).toHaveValue("120");
  });

  it("tells a 403 in plain words and changes nothing on the screen", async () => {
    route("post", RECONCILIATIONS, problem("forbidden", 403, { traceId: "tr-403" }));
    const { user, section } = await openScreen();
    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);

    await fill(user, "Oficio 7", "120");
    await user.click(signButton());

    expect(await screen.findByText(/No tiene permiso para realizar esta acción/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/tr-403/)).toBeInTheDocument();
    expect(section().queryByText("Acta firmada")).not.toBeInTheDocument();
    expect(referenceField()).toHaveValue("Oficio 7");
    expect(signButton()).toBeEnabled();
  });

  it("tells the limit of requests with the wait, and sends nothing while it lasts", async () => {
    route("post", RECONCILIATIONS, { ...problem("too-many-requests", 429), headers: { "Retry-After": "30" } });
    const { user } = await openScreen();
    await waitFor(() => {
      expect(yearField()).toHaveValue(YEAR_ID);
    }, TIMEOUT);

    await fill(user, "Oficio 7", "120");
    await user.click(signButton());

    expect(await screen.findByText(/Puede intentar de nuevo en/, undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(signButton());
    expect(posts()).toHaveLength(1);
  });
});

describe("the actas already signed", () => {
  it("says so when there are none, and the action leads to the form", async () => {
    const { user, section } = await openScreen();

    expect(await section().findByText("Aún no hay actas firmadas", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(section().getByRole("button", { name: "Ir al formulario del acta" }));
    expect(referenceField()).toHaveFocus();
  });

  it("shows the failed load with a retry that loads them", async () => {
    const { user, section } = await openScreen({ lists: [problem("internal-error", 500), jsonReply(actaPage([acta({ officialReference: "Oficio 3" })]))] });

    expect(await section().findByText("No se pudieron cargar las actas firmadas", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(section().getByRole("button", { name: "Reintentar" }));
    expect(await section().findByText("Oficio 3", undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("names a year the account cannot read with a neutral label", async () => {
    const { section } = await openScreen({
      years: problem("forbidden", 403),
      lists: [jsonReply(actaPage([acta({ officialReference: "Oficio 3" })]))],
    });

    expect(await section().findByText("Oficio 3", undefined, TIMEOUT)).toBeInTheDocument();
    expect(section().getByText("Año lectivo no disponible")).toBeInTheDocument();
  });

  it("asks the server for the page of the address, ten at a time, and writes the next one in it", async () => {
    const rows = Array.from({ length: 10 }, (_, index) => acta({ officialReference: `Oficio ${String(index)}` }));
    const { app, user, section } = await openScreen({ lists: [jsonReply(actaPage(rows, { page: 1, size: 10, total: 35 }))] }, "/imports?actsPage=2");

    await section().findByText("Oficio 0", undefined, TIMEOUT);
    expect(Object.fromEntries(new URLSearchParams(lists()[0]?.search))).toEqual({ page: "1", size: "10" });
    await user.click(section().getByRole("button", { name: /Siguiente/ }));

    await waitFor(() => {
      expect(app.router.state.location.searchStr).toContain("actsPage=3");
    }, TIMEOUT);
    await waitFor(() => {
      expect(new URLSearchParams(lists().at(-1)?.search).get("page")).toBe("2");
    }, TIMEOUT);
  });

  it("does not send the page of the actas to the history of batches", async () => {
    await openScreen({}, "/imports?actsPage=3");

    const batches = requests().filter((sent) => sent.method === "GET" && sent.path === IMPORTS);
    expect(batches.length).toBeGreaterThan(0);
    expect(new URLSearchParams(batches[0]?.search).has("actsPage")).toBe(false);
  });
});

import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { academicYear, importBatch, importBatchPage, importRun, organizationPage, problem } from "../../test/contract-responses.ts";
import { YEAR_ID, actaPage, jsonReply, preview } from "../../shared/api/reconciliation-fixtures.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderFeature } from "../../test/render-feature.tsx";
import { importsFeature } from "./feature.tsx";

// The imports screen over the real route, query layer and transport with the test server answering: the four steps, the
// history, the checks in the browser, every error the server can give, and that nothing of the file (its name, its content or
// the lines of its rows) reaches the address, the storage or the console.
installMswServer();

const IMPORTS = "/api/v1/imports";
const SIMULATE = `${IMPORTS}/students/simulate`;
const APPLY = `${IMPORTS}/students/apply`;
const TIMEOUT = { timeout: 10_000 };
const CSV = "legacyId,firstName,lastName,birthDate\n1,Zoila,Quintanilla,2013-02-01\n";
const FILE_NAME = "listado-secreto.csv";
const csv = () => new File([CSV], FILE_NAME, { type: "text/csv" });
const posts = (path: string) => requests().filter((sent) => sent.method === "POST" && sent.path === path);
/** The same part with the boundary of the multipart body taken out, which is different on every request. */
const partOf = (sent: { body: string } | undefined) => sent?.body.replace(/-{6}formdata-undici-\d+/g, "BOUNDARY");
const histories = () => requests().filter((sent) => sent.method === "GET" && sent.path === IMPORTS);

afterEach(() => {
  vi.restoreAllMocks();
});

/** The lists the section of the acta of the reconciliation loads next to the wizard; its own states are tested in its own file. */
function routeReconciliation() {
  route("get", "/api/v1/organization/academic-years", organizationPage("academic-years", [academicYear({ id: YEAR_ID, status: "ACTIVE" })]));
  route("get", `${IMPORTS}/reconciliations`, jsonReply(actaPage([])));
  route("get", `${IMPORTS}/reconciliations/preview`, jsonReply(preview()));
}

async function openScreen(history = importBatchPage([])) {
  route("get", IMPORTS, history);
  routeReconciliation();
  const app = renderFeature(importsFeature, "/imports");
  const user = userEvent.setup();
  await screen.findByRole("heading", { level: 2, name: "Importar datos" }, TIMEOUT);
  return { user, app };
}

const fileField = () => screen.getByLabelText("Archivo CSV de estudiantes");
const simulateButton = () => screen.getByRole("button", { name: "Simular la importación" });

async function simulate(user: ReturnType<typeof userEvent.setup>) {
  await user.upload(fileField(), csv());
  await user.click(simulateButton());
  return screen.findByRole("heading", { level: 3, name: "Resultado de la simulación" }, TIMEOUT);
}

describe("the first step and the history", () => {
  it("shows the file field and the empty history with the way to the field", async () => {
    const { user } = await openScreen();

    expect(await screen.findByText("Todavía no hay importaciones", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Elegir un archivo" }));
    expect(fileField()).toHaveFocus();
    expect(screen.getByText("Elegir archivo").closest("li")).toHaveAttribute("aria-current", "step");
  });

  it("lists the batches of the server, newest first, with their counts", async () => {
    await openScreen(importBatchPage([importBatch({ status: "APPLIED", totalRows: 40, createdRows: 38, validRows: 38, rejectedRows: 1, skippedRows: 1 })]));

    const table = await screen.findByRole("table", { name: /Importaciones de la institución/ }, TIMEOUT);
    expect(within(table).getByText("Aplicada")).toBeInTheDocument();
    expect(within(table).getAllByRole("cell").map((cell) => cell.textContent).slice(3)).toEqual(["40", "38", "1", "1"]);
    expect(within(table).getByText("Estudiantes")).toBeInTheDocument();
  });

  it("shows the failed load of the history with a retry that loads it", async () => {
    route("get", IMPORTS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), importBatchPage([importBatch()]));
    routeReconciliation();
    renderFeature(importsFeature, "/imports");
    const user = userEvent.setup();

    expect(await screen.findByText("No se pudo cargar el historial de importaciones", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Identificador para soporte: 01J8Z4K2QF9P0X");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByText("Simulada", undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("asks the server for the page of the address and writes the next one in it", async () => {
    route("get", IMPORTS, importBatchPage([importBatch()], { page: 1, size: 25, total: 60 }));
    routeReconciliation();
    const app = renderFeature(importsFeature, "/imports?page=2");
    const user = userEvent.setup();
    await screen.findByText("Simulada", undefined, TIMEOUT);

    expect(Object.fromEntries(new URLSearchParams(histories()[0]?.search))).toEqual({ page: "1", size: "25" });
    await user.click(screen.getByRole("button", { name: /Siguiente/ }));

    await waitFor(() => {
      expect(app.router.state.location.searchStr).toContain("page=3");
    }, TIMEOUT);
  });
});

describe("the history by kind", () => {
  it("asks the server for the kind of the address, and writes the one chosen in it, from the first page", async () => {
    route("get", IMPORTS, importBatchPage([importBatch({ kind: "GUARDIANS" })], { page: 1, size: 25, total: 60 }));
    routeReconciliation();
    const app = renderFeature(importsFeature, "/imports?page=2&kind=guardians");
    const user = userEvent.setup();
    await within(await screen.findByRole("table", { name: /Importaciones de la institución/ }, TIMEOUT)).findByText("Encargados", { selector: "td" }, TIMEOUT);

    expect(Object.fromEntries(new URLSearchParams(histories()[0]?.search))).toEqual({ page: "1", size: "25", kind: "guardians" });
    expect(screen.getByLabelText("Tipo de importación")).toHaveValue("guardians");
    await user.selectOptions(screen.getByLabelText("Tipo de importación"), "guardian-links");

    await waitFor(() => {
      expect(app.router.state.location.searchStr).toBe("?kind=guardian-links");
    }, TIMEOUT);
    await waitFor(() => {
      expect(new URLSearchParams(histories().at(-1)?.search).get("kind")).toBe("guardian-links");
    }, TIMEOUT);
  });

  it("drops a kind the address cannot hold and asks for every kind", async () => {
    route("get", IMPORTS, importBatchPage([importBatch()]));
    routeReconciliation();
    renderFeature(importsFeature, "/imports?kind=teachers");
    await screen.findByText("Simulada", undefined, TIMEOUT);

    expect(new URLSearchParams(histories()[0]?.search).has("kind")).toBe(false);
    expect(screen.getByLabelText("Tipo de importación")).toHaveValue("");
  });

  it("with a kind that has no batches, says so and offers to clear the filter", async () => {
    route("get", IMPORTS, importBatchPage([]));
    routeReconciliation();
    const app = renderFeature(importsFeature, "/imports?kind=guardian-links");
    const user = userEvent.setup();
    await screen.findByText("Ningún registro coincide con los filtros", undefined, TIMEOUT);

    await user.click(screen.getByRole("button", { name: "Quitar filtros" }));

    await waitFor(() => {
      expect(app.router.state.location.searchStr).toBe("");
    }, TIMEOUT);
  });
});

describe("the checks in the browser", () => {
  it("asks for a file when there is none, sends nothing and moves the focus to the field", async () => {
    const { user } = await openScreen();

    await user.click(simulateButton());

    expect(fileField()).toHaveAttribute("aria-invalid", "true");
    expect(fileField()).toHaveAccessibleDescription(/Elija un archivo CSV\./);
    expect(fileField()).toHaveFocus();
    expect(posts(SIMULATE)).toHaveLength(0);
  });

  it("refuses a file that is not a CSV, one that weighs more than 5 MB and an empty one, without sending them or reading them", async () => {
    const { user } = await openScreen();
    const free = userEvent.setup({ applyAccept: false });

    await free.upload(fileField(), new File(["x"], "hoja.xlsx"));
    await user.click(simulateButton());
    expect(fileField()).toHaveAccessibleDescription(/extensión \.csv/);

    const big = new File(["x"], "grande.csv");
    Object.defineProperty(big, "size", { value: 5 * 1024 * 1024 + 1 });
    await free.upload(fileField(), big);
    await user.click(simulateButton());
    expect(fileField()).toHaveAccessibleDescription(/más de 5 MB/);

    await free.upload(fileField(), new File([], "vacio.csv"));
    await user.click(simulateButton());
    expect(fileField()).toHaveAccessibleDescription(/está vacío/);
    expect(posts(SIMULATE)).toHaveLength(0);
  });
});

describe("simulate", () => {
  it("sends the file once in the part `file`, then shows the counts, the rejections by line, field and reason, and moves the focus", async () => {
    route(
      "post",
      SIMULATE,
      importRun({
        batch: { totalRows: 100, validRows: 95, rejectedRows: 5 },
        rejections: [
          { line: 4, field: "birthDate", reason: "invalid-date" },
          { line: 9, field: "row", reason: "invalid-format" },
        ],
      }),
    );
    const { user } = await openScreen();

    const summary = await simulate(user);

    expect(summary).toHaveFocus();
    expect(posts(SIMULATE)).toHaveLength(1);
    expect(posts(SIMULATE)[0]?.body).toContain('name="file"');
    const summaryRegion = within(summary.closest("section") ?? document.body);
    expect(summaryRegion.getAllByRole("definition").map((count) => count.textContent)).toEqual(["100", "95", "5", "0"]);
    const table = screen.getByRole("table", { name: /Filas del archivo con su línea/ });
    expect(within(table).getAllByRole("row").slice(1).map((row) => row.textContent)).toEqual([
      "4birthDateLa fecha no existe o es anterior al 1 de enero de 1990.",
      "9Fila completaEl formato no es válido.",
    ]);
    expect(screen.getByText("Simular").closest("li")).toHaveAttribute("aria-current", "step");
    await waitFor(() => {
      expect(histories().length).toBeGreaterThan(1);
    }, TIMEOUT);
  });

  it("ignores a second click while the first is in flight", async () => {
    route("post", SIMULATE, importRun());
    const { user } = await openScreen();
    await user.upload(fileField(), csv());

    // Two clicks in the same tick, before anything has rendered: the state cannot be what stops the second.
    const button = simulateButton();
    fireEvent.click(button);
    fireEvent.click(button);
    await screen.findByRole("heading", { level: 3, name: "Resultado de la simulación" }, TIMEOUT);

    expect(posts(SIMULATE)).toHaveLength(1);
  });

  it("downloads the lines and the reasons of the rejections and nothing else", async () => {
    route("post", SIMULATE, importRun({ batch: { rejectedRows: 1 }, rejections: [{ line: 4, field: "birthDate", reason: "invalid-date" }] }));
    let saved = "";
    let savedName = "";
    const createObjectURL = vi.fn((blob: Blob) => {
      void blob.text().then((text) => {
        saved = text;
      });
      return "blob:test";
    });
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }));
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      savedName = this.download;
    });
    const { user } = await openScreen();
    await simulate(user);

    await user.click(screen.getByRole("button", { name: "Descargar las líneas y los motivos" }));

    await waitFor(() => {
      expect(saved).toContain('"4","birthDate","La fecha no existe o es anterior al 1 de enero de 1990."');
    });
    expect(savedName).toBe("rechazos-importacion.csv");
    expect(saved).not.toContain("Zoila");
    vi.unstubAllGlobals();
  });

  it("downloads the empty template with the header of the backend", async () => {
    let saved = "";
    vi.stubGlobal(
      "URL",
      Object.assign(URL, {
        createObjectURL: (blob: Blob) => {
          void blob.text().then((text) => {
            saved = text;
          });
          return "blob:test";
        },
        revokeObjectURL: vi.fn(),
      }),
    );
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    const { user } = await openScreen();

    await user.click(screen.getByRole("button", { name: "Descargar la plantilla vacía" }));

    await waitFor(() => {
      expect(saved).toBe("legacyId,firstName,lastName,birthDate,nationalId,sex\r\n");
    });
    vi.unstubAllGlobals();
  });

  it("lists the problems of a file the server could not read, in plain words, and keeps the file for another try", async () => {
    route(
      "post",
      SIMULATE,
      problem("validation-failed", 400, {
        errors: [
          { field: "birthDate", reason: "missing-column" },
          { field: "columns[4]", reason: "unknown-column" },
        ],
      }),
    );
    const { user } = await openScreen();
    await user.upload(fileField(), csv());

    await user.click(simulateButton());

    const alert = await screen.findByRole("alert", undefined, TIMEOUT);
    expect(within(alert).getAllByRole("listitem")).toHaveLength(2);
    expect(alert).toHaveTextContent("Falta la columna obligatoria «birthDate» en la cabecera.");
    expect(alert).toHaveTextContent("La columna número 5 de la cabecera");
    expect(alert).toHaveFocus();
    expect(screen.queryByRole("heading", { level: 3, name: "Resultado de la simulación" })).toBeNull();
  });

  it.each([
    [problem("forbidden", 403), "No tiene permiso para realizar esta acción."],
    [problem("too-many-requests", 429), "Se alcanzó el límite de importaciones por hora."],
    [problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), "Ocurrió un error inesperado"],
    ["network-error" as const, "No se pudo enviar el archivo al servidor."],
  ])("tells a failed simulation in plain words and changes nothing else (%#)", async (reply, text) => {
    route("post", SIMULATE, reply);
    const { user } = await openScreen();
    await user.upload(fileField(), csv());

    await user.click(simulateButton());

    expect(await screen.findByRole("alert", undefined, TIMEOUT)).toHaveTextContent(text);
    expect(screen.queryByRole("button", { name: "Aplicar la importación" })).toBeNull();
  });
});

describe("apply", () => {
  async function openConfirmation() {
    route("post", SIMULATE, importRun({ batch: { totalRows: 100, validRows: 95, rejectedRows: 3, skippedRows: 2 } }));
    const opened = await openScreen();
    await simulate(opened.user);
    await opened.user.click(screen.getByRole("button", { name: "Aplicar la importación" }));
    const dialog = await screen.findByRole("dialog", { name: "Aplicar la importación" });
    return { ...opened, dialog };
  }

  it("confirms with how many will be created, sends the same file once, shows the result, releases the file and reads the history again", async () => {
    route(
      "post",
      APPLY,
      importRun({ batch: { status: "APPLIED", totalRows: 100, validRows: 95, createdRows: 95, rejectedRows: 3, skippedRows: 2, appliedAt: "2026-11-10T16:00:00Z" } }),
    );
    const { user, dialog } = await openConfirmation();
    expect(within(dialog).getByText("Se crearán 95 estudiantes.")).toBeInTheDocument();
    const before = histories().length;

    await user.dblClick(within(dialog).getByRole("button", { name: "Crear 95 estudiantes" }));

    const heading = await screen.findByRole("heading", { level: 3, name: "Importación aplicada" }, TIMEOUT);
    expect(heading).toHaveFocus();
    expect(posts(APPLY)).toHaveLength(1);
    expect(partOf(posts(APPLY)[0])).toBe(partOf(posts(SIMULATE)[0]));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.getByText(/Se crearon 95 estudiantes\./)).toBeInTheDocument();
    expect(screen.queryByLabelText("Archivo CSV de estudiantes")).toBeNull();
    await waitFor(() => {
      expect(histories().length).toBeGreaterThan(before);
    }, TIMEOUT);

    await user.click(screen.getByRole("button", { name: "Importar otro archivo" }));

    expect((fileField() as HTMLInputElement).files).toHaveLength(0);
    expect(screen.queryByRole("heading", { level: 3, name: "Importación aplicada" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Aplicar la importación" })).toBeNull();
  });

  it("cancels the confirmation without sending anything, and gives the focus back to the apply button", async () => {
    const { user, dialog } = await openConfirmation();

    await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(posts(APPLY)).toHaveLength(0);
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Aplicar la importación" })).toHaveFocus();
    });
  });

  it("tells that the file is not the one that was simulated, drops the old summary and simulates the same file again on request", async () => {
    route("post", APPLY, problem("import-file-changed", 409, { traceId: "01J8Z4K2QF9P0X" }));
    const { user, dialog } = await openConfirmation();

    await user.click(within(dialog).getByRole("button", { name: "Crear 95 estudiantes" }));

    const alert = await screen.findByRole("alert", undefined, TIMEOUT);
    expect(alert).toHaveTextContent("El archivo no es el mismo que se simuló la última vez.");
    expect(alert).toHaveTextContent("Identificador para soporte: 01J8Z4K2QF9P0X");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByRole("heading", { level: 3, name: "Resultado de la simulación" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Aplicar la importación" })).toBeNull();

    await user.click(within(alert).getByRole("button", { name: "Simular de nuevo este archivo" }));

    await screen.findByRole("heading", { level: 3, name: "Resultado de la simulación" }, TIMEOUT);
    expect(posts(SIMULATE)).toHaveLength(2);
    expect(partOf(posts(SIMULATE)[1])).toBe(partOf(posts(SIMULATE)[0]));
  });

  it("tells that the file was never simulated and offers to simulate it", async () => {
    route("post", APPLY, problem("import-not-simulated", 409));
    const { user, dialog } = await openConfirmation();

    await user.click(within(dialog).getByRole("button", { name: "Crear 95 estudiantes" }));

    const alert = await screen.findByRole("alert", undefined, TIMEOUT);
    expect(alert).toHaveTextContent("Este archivo todavía no se ha simulado.");
    expect(within(alert).getByRole("button", { name: "Simular de nuevo este archivo" })).toBeInTheDocument();
  });

  it("when it cannot know whether the apply finished, says that trying again is safe and keeps the summary", async () => {
    route("post", APPLY, "network-error", importRun({ batch: { status: "APPLIED", totalRows: 100, validRows: 95, createdRows: 0, skippedRows: 95, rejectedRows: 5 } }));
    const { user, dialog } = await openConfirmation();

    await user.click(within(dialog).getByRole("button", { name: "Crear 95 estudiantes" }));

    const alert = await screen.findByRole("alert", undefined, TIMEOUT);
    expect(alert).toHaveTextContent("No se pudo confirmar si la importación terminó. Puede aplicarla de nuevo sin riesgo");
    expect(screen.getByRole("heading", { level: 3, name: "Resultado de la simulación" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Aplicar la importación" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Crear 95 estudiantes" }));

    expect(await screen.findByRole("heading", { level: 3, name: "Importación aplicada" }, TIMEOUT)).toBeInTheDocument();
    expect(posts(APPLY)).toHaveLength(2);
  });

  it("tells a 403 on the apply in plain words and changes nothing", async () => {
    route("post", APPLY, problem("forbidden", 403));
    const { user, dialog } = await openConfirmation();

    await user.click(within(dialog).getByRole("button", { name: "Crear 95 estudiantes" }));

    expect(await screen.findByRole("alert", undefined, TIMEOUT)).toHaveTextContent("No tiene permiso para realizar esta acción.");
    expect(screen.getByRole("heading", { level: 3, name: "Resultado de la simulación" })).toBeInTheDocument();
  });
});

describe("the other kinds", () => {
  const GUARDIANS = `${IMPORTS}/guardians`;
  const LINKS = `${IMPORTS}/guardian-links`;
  const chooseKind = (user: ReturnType<typeof userEvent.setup>, kind: string) => user.selectOptions(screen.getByLabelText("Qué va a importar"), kind);

  async function simulateLinks(user: ReturnType<typeof userEvent.setup>) {
    await chooseKind(user, "guardian-links");
    await user.upload(screen.getByLabelText("Archivo CSV de vínculos"), csv());
    await user.click(simulateButton());
    return screen.findByRole("heading", { level: 3, name: "Resultado de la simulación" }, TIMEOUT);
  }

  it("changes the field, and sends the file to the route of the kind with the reasons of the whole group", async () => {
    const rejections = [2, 3, 4].map((line) => ({ line, field: "row", reason: "sum-not-100" as const }));
    route("post", `${LINKS}/simulate`, importRun({ batch: { kind: "GUARDIAN_LINKS", totalRows: 3, validRows: 0, rejectedRows: 3 }, rejections }));
    const { user } = await openScreen();

    await simulateLinks(user);

    expect(posts(`${LINKS}/simulate`)).toHaveLength(1);
    expect(posts(SIMULATE)).toHaveLength(0);
    expect(screen.getAllByText("Los porcentajes de este estudiante no suman exactamente 100.00: se rechazan todas sus filas.")).toHaveLength(3);
    expect(screen.getByText(/Todavía no se creó ningún vínculo\./)).toBeInTheDocument();
  });

  it("lets go of the file and of its summary when the kind changes", async () => {
    route("post", SIMULATE, importRun());
    const { user } = await openScreen();
    await simulate(user);

    await chooseKind(user, "guardians");

    expect(screen.queryByRole("heading", { level: 3, name: "Resultado de la simulación" })).toBeNull();
    expect(screen.getByLabelText<HTMLInputElement>("Archivo CSV de encargados").files).toHaveLength(0);
    expect(screen.getByText("Elegir archivo").closest("li")).toHaveAttribute("aria-current", "step");
  });

  it.each([
    ["guardians", "legacyId,firstName,lastName,nationalId,rtn,email,phone,address,preferredChannel\r\n", "plantilla-encargados.csv"],
    ["guardian-links", "studentLegacyId,guardianLegacyId,relationship,financialResponsibilityPercent,isPrimaryPayer,isBillingRecipient\r\n", "plantilla-vinculos.csv"],
    ["enrollments", "studentLegacyId,academicYear,modality,grade,section,enrolledOn\r\nL1001,2026,Diurna,Primero,A,2026-02-02\r\n", "plantilla-matriculas.csv"],
  ] as const)("downloads the template of %s", async (kind, content, fileName) => {
    let saved = "";
    let savedName = "";
    vi.stubGlobal(
      "URL",
      Object.assign(URL, {
        createObjectURL: (blob: Blob) => {
          void blob.text().then((text) => {
            saved = text;
          });
          return "blob:test";
        },
        revokeObjectURL: vi.fn(),
      }),
    );
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      savedName = this.download;
    });
    const { user } = await openScreen();
    await chooseKind(user, kind);

    await user.click(screen.getByRole("button", { name: "Descargar la plantilla vacía" }));

    await waitFor(() => {
      expect(saved).toBe(content);
    });
    expect(savedName).toBe(fileName);
    vi.unstubAllGlobals();
  });

  it("simulates and applies the guardians over their own routes", async () => {
    route("post", `${GUARDIANS}/simulate`, importRun({ batch: { kind: "GUARDIANS", totalRows: 80, validRows: 80, rejectedRows: 0 } }));
    route("post", `${GUARDIANS}/apply`, importRun({ batch: { kind: "GUARDIANS", status: "APPLIED", totalRows: 80, validRows: 80, createdRows: 80, appliedAt: "2026-11-10T16:00:00Z" } }));
    const { user } = await openScreen();
    await chooseKind(user, "guardians");
    await user.upload(screen.getByLabelText("Archivo CSV de encargados"), csv());
    await user.click(simulateButton());
    await screen.findByRole("heading", { level: 3, name: "Resultado de la simulación" }, TIMEOUT);
    await user.click(screen.getByRole("button", { name: "Aplicar la importación" }));

    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Crear 80 encargados" }));

    expect(await screen.findByRole("heading", { level: 3, name: "Importación aplicada" }, TIMEOUT)).toHaveFocus();
    expect(posts(`${GUARDIANS}/apply`)).toHaveLength(1);
    expect(screen.getByText(/Se crearon 80 encargados\./)).toBeInTheDocument();
  });

  describe("the enrollments", () => {
    const ENROLLMENTS = `${IMPORTS}/enrollments`;
    // Only reasons the published contract already lists: the texts of the new ones are proven in the wizard, which takes props alone.
    const reasons = [
      { line: 3, field: "studentLegacyId", reason: "unknown-student" },
      { line: 4, field: "studentLegacyId", reason: "student-not-active" },
      { line: 5, field: "enrolledOn", reason: "invalid-date" },
    ] as const;

    async function simulateEnrollments(user: ReturnType<typeof userEvent.setup>) {
      await chooseKind(user, "enrollments");
      await user.upload(screen.getByLabelText("Archivo CSV de matrículas"), csv());
      await user.click(simulateButton());
      return screen.findByRole("heading", { level: 3, name: "Resultado de la simulación" }, TIMEOUT);
    }

    it("sends the file to the enrollments route and shows the reason of each rejected row by line and column", async () => {
      route("post", `${ENROLLMENTS}/simulate`, importRun({ batch: { kind: "ENROLLMENTS", totalRows: 100, validRows: 97, rejectedRows: 3 }, rejections: [...reasons] }));
      const { user } = await openScreen();

      await simulateEnrollments(user);

      expect(posts(`${ENROLLMENTS}/simulate`)).toHaveLength(1);
      expect(posts(`${IMPORTS}/students/simulate`)).toHaveLength(0);
      const rows = within(screen.getByRole("table", { name: /Filas del archivo con su línea/ })).getAllByRole("row").slice(1);
      expect(rows.map((row) => row.textContent)).toEqual([
        "3studentLegacyIdEl estudiante no está importado: importe primero el archivo de estudiantes.",
        "4studentLegacyIdEl estudiante no está activo.",
        "5enrolledOnLa fecha no existe o es anterior al 1 de enero de 1990.",
      ]);
      expect(screen.getByText(/Todavía no se creó ninguna matrícula\./)).toBeInTheDocument();
    });

    it("applies over its own route and reads the history, the enrollments and the students again, and never the guardians", async () => {
      route("post", `${ENROLLMENTS}/simulate`, importRun({ batch: { kind: "ENROLLMENTS", totalRows: 98, validRows: 98 } }));
      route("post", `${ENROLLMENTS}/apply`, importRun({ batch: { kind: "ENROLLMENTS", status: "APPLIED", totalRows: 98, validRows: 98, createdRows: 98, appliedAt: "2026-11-10T16:00:00Z" } }));
      const { user, app } = await openScreen();
      await simulateEnrollments(user);
      const invalidated = vi.spyOn(app.queryClient, "invalidateQueries");
      await user.click(screen.getByRole("button", { name: "Aplicar la importación" }));

      await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Crear 98 matrículas" }));

      expect(await screen.findByRole("heading", { level: 3, name: "Importación aplicada" }, TIMEOUT)).toHaveFocus();
      expect(posts(`${ENROLLMENTS}/apply`)).toHaveLength(1);
      expect(screen.getByText(/Se crearon 98 matrículas\./)).toBeInTheDocument();
      expect(invalidated.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([["imports"], ["enrollments"], ["students"]]);
    });

    it("simulating reads only the history again, because nothing is written", async () => {
      route("post", `${ENROLLMENTS}/simulate`, importRun({ batch: { kind: "ENROLLMENTS" } }));
      const { user, app } = await openScreen();
      const invalidated = vi.spyOn(app.queryClient, "invalidateQueries");

      await simulateEnrollments(user);

      expect(invalidated.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([["imports"]]);
    });

    it("tells a 403 on the apply in plain words and keeps the summary", async () => {
      route("post", `${ENROLLMENTS}/simulate`, importRun({ batch: { kind: "ENROLLMENTS", totalRows: 2, validRows: 2 } }));
      route("post", `${ENROLLMENTS}/apply`, problem("forbidden", 403));
      const { user } = await openScreen();
      await simulateEnrollments(user);
      await user.click(screen.getByRole("button", { name: "Aplicar la importación" }));

      await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Crear 2 matrículas" }));

      expect(await screen.findByRole("alert", undefined, TIMEOUT)).toHaveTextContent("No tiene permiso para realizar esta acción.");
      expect(screen.queryByRole("heading", { level: 3, name: "Importación aplicada" })).toBeNull();
    });

    it("when the apply cannot be confirmed, says that trying again is safe because an enrollment already made is skipped", async () => {
      route("post", `${ENROLLMENTS}/simulate`, importRun({ batch: { kind: "ENROLLMENTS", totalRows: 4, validRows: 4 } }));
      route("post", `${ENROLLMENTS}/apply`, "network-error");
      const { user } = await openScreen();
      await simulateEnrollments(user);
      await user.click(screen.getByRole("button", { name: "Aplicar la importación" }));

      await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Crear 4 matrículas" }));

      expect(await screen.findByRole("alert", undefined, TIMEOUT)).toHaveTextContent("las matrículas ya creadas se omiten");
    });
  });

  it("when the apply of the links cannot be confirmed, says that trying again is safe because a student with links is skipped", async () => {
    route("post", `${LINKS}/simulate`, importRun({ batch: { kind: "GUARDIAN_LINKS", totalRows: 4, validRows: 4 } }));
    route("post", `${LINKS}/apply`, "network-error");
    const { user } = await openScreen();
    await simulateLinks(user);
    await user.click(screen.getByRole("button", { name: "Aplicar la importación" }));

    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Crear 4 vínculos" }));

    expect(await screen.findByRole("alert", undefined, TIMEOUT)).toHaveTextContent("los estudiantes que ya tienen vínculos se omiten");
  });
});

describe("what never leaves the screen", () => {
  it("keeps the name and the content of the file out of the address, the storage, the cache and the console", async () => {
    const logs = [vi.spyOn(console, "log"), vi.spyOn(console, "info"), vi.spyOn(console, "warn"), vi.spyOn(console, "error"), vi.spyOn(console, "debug")];
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    route("post", SIMULATE, importRun({ batch: { rejectedRows: 1 }, rejections: [{ line: 2, field: "firstName", reason: "required" }] }));
    route("post", APPLY, importRun({ batch: { status: "APPLIED", createdRows: 8 } }));
    const { user, app } = await openScreen();

    await simulate(user);
    await user.click(screen.getByRole("button", { name: "Aplicar la importación" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /^Crear/ }));
    await screen.findByRole("heading", { level: 3, name: "Importación aplicada" }, TIMEOUT);

    expect(setItem).not.toHaveBeenCalled();
    expect(app.router.state.location.searchStr).toBe("");
    expect(document.body.textContent).not.toContain("Zoila");
    expect(document.body.textContent).not.toContain(FILE_NAME);
    expect(JSON.stringify(logs.map((log) => log.mock.calls))).not.toMatch(/Zoila|Quintanilla|listado-secreto/);
    const cached = JSON.stringify(app.queryClient.getQueryCache().getAll().map((query) => [query.queryKey, query.state.data]));
    expect(cached).not.toMatch(/Zoila|Quintanilla|listado-secreto/);
    expect(app.queryClient.getMutationCache().getAll()).toHaveLength(0);
    expect(app.queryClient.getQueryCache().getAll().every((query) => query.queryKey[0] === "imports" || query.queryKey[0] === "students" || query.queryKey[0] === "organization")).toBe(true);
  });
});

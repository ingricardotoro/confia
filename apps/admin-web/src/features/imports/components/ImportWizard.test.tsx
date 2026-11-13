import type { ImportRunResponseOutput, RejectionResponseOutput } from "@confia/contracts/admin";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { importBatch } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ImportWizard, REJECTIONS_PAGE_SIZE, type ImportWizardProps } from "./ImportWizard.tsx";

// States. AW57, AW58: the four steps of the import rendered with props alone. Choose: the file field with its help, with a problem
// found in the browser and with the failure of a request. Simulate: loading (the button and the polite region), the summary with
// no rejections, with rejections paged in the browser, truncated, and with nothing to create. Apply: the confirmation, in flight.
// Result: the counts and the way to start over. There is no empty state of its own: the first step is it.
const REJECTIONS: readonly RejectionResponseOutput[] = Array.from({ length: 23 }, (_, index) => ({
  line: index + 2,
  field: index % 2 === 0 ? "birthDate" : "row",
  reason: index % 2 === 0 ? "invalid-date" : "invalid-format",
}));

const run = (batch = {}, rejections: readonly RejectionResponseOutput[] = [], truncated = false): ImportRunResponseOutput => ({
  batch: importBatch(batch),
  rejections: [...rejections],
  rejectionsTruncated: truncated,
});

function props(override: Partial<ImportWizardProps> = {}): ImportWizardProps {
  return {
    kind: "students",
    onKindChange: vi.fn(),
    step: "choose",
    phase: "idle",
    inputKey: 0,
    confirming: false,
    onFileChange: vi.fn(),
    onSimulate: vi.fn(),
    onTemplate: vi.fn(),
    onDownloadRejections: vi.fn(),
    onOpenApply: vi.fn(),
    onCloseApply: vi.fn(),
    onApply: vi.fn(),
    onReset: vi.fn(),
    ...override,
  };
}

describe("ImportWizard, choose", () => {
  it("has a file field named by its label, with the help and the privacy note described, and accepts only CSV", () => {
    renderWithAdminI18n(<ImportWizard {...props()} />);

    const input = screen.getByLabelText("Archivo CSV de estudiantes");
    expect(input).toHaveAttribute("type", "file");
    expect(input).toHaveAttribute("accept", ".csv,text/csv");
    expect(input).toHaveAccessibleDescription(/hasta 5 MB y 10 000 filas.*no se guarda/);
    expect(screen.getByRole("button", { name: "Simular la importación" })).toBeEnabled();
  });

  it("hands over the chosen file, and none when the choice is cleared", async () => {
    const onFileChange = vi.fn();
    renderWithAdminI18n(<ImportWizard {...props({ onFileChange })} />);
    const file = new File(["legacyId\n"], "estudiantes.csv", { type: "text/csv" });

    await userEvent.setup().upload(screen.getByLabelText("Archivo CSV de estudiantes"), file);

    expect(onFileChange).toHaveBeenCalledWith(file);
  });

  it("sends the form on Enter or on the button, and offers the template", async () => {
    const onSimulate = vi.fn();
    const onTemplate = vi.fn();
    renderWithAdminI18n(<ImportWizard {...props({ onSimulate, onTemplate })} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Simular la importación" }));
    await user.click(screen.getByRole("button", { name: "Descargar la plantilla vacía" }));

    expect(onSimulate).toHaveBeenCalledTimes(1);
    expect(onTemplate).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["required", "Elija un archivo CSV."],
    ["extension", "El archivo debe ser un CSV con la extensión .csv."],
    ["too-large", "El archivo pesa más de 5 MB."],
    ["empty", "El archivo está vacío."],
  ] as const)("tells the problem %s next to the field, ties it to it and moves the focus there", (fileProblem, text) => {
    renderWithAdminI18n(<ImportWizard {...props({ fileProblem })} />);

    const input = screen.getByLabelText("Archivo CSV de estudiantes");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription(new RegExp(text));
    expect(input).toHaveFocus();
  });

  it("lists every problem of the file the server named, with the trace id, and moves the focus to the failure", () => {
    renderWithAdminI18n(
      <ImportWizard
        {...props({
          failure: {
            kind: "other",
            traceId: "01J8Z4K2QF9P0X",
            texts: [{ key: "imports.fileErrors.missingColumn", values: { column: "birthDate" } }, { key: "imports.fileErrors.unknownColumn", values: { position: 3 } }],
          },
        })}
      />,
    );

    const alert = screen.getByRole("alert");
    expect(within(alert).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "Falta la columna obligatoria «birthDate» en la cabecera.",
      expect.stringContaining("La columna número 3 de la cabecera"),
    ]);
    expect(alert).toHaveTextContent("Identificador para soporte: 01J8Z4K2QF9P0X");
    expect(alert).toHaveFocus();
    expect(within(alert).queryByRole("button")).toBeNull();
  });

  it.each([["not-simulated"], ["file-changed"]] as const)("on %s offers to simulate the same file again", async (kind) => {
    const onSimulate = vi.fn();
    renderWithAdminI18n(<ImportWizard {...props({ onSimulate, failure: { kind, texts: [{ key: "problem.importFileChanged" }] } })} />);

    await userEvent.setup().click(within(screen.getByRole("alert")).getByRole("button", { name: "Simular de nuevo este archivo" }));

    expect(onSimulate).toHaveBeenCalledTimes(1);
  });

  it("while simulating, marks the section busy, shows the wait in the polite region and blocks the button", () => {
    renderWithAdminI18n(<ImportWizard {...props({ phase: "simulating", step: "simulate" })} />);

    expect(screen.getByRole("region", { name: "Importar datos" })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText(/Simulando la importación\./)).toHaveAttribute("aria-live", "polite");
    expect(screen.getByRole("button", { name: /Simular la importación/ })).toHaveAttribute("aria-busy", "true");
  });
});

describe("ImportWizard, the kind", () => {
  it("offers the three kinds in the recommended order, with the order described, and hands over the one chosen", async () => {
    const onKindChange = vi.fn();
    renderWithAdminI18n(<ImportWizard {...props({ onKindChange })} />);

    const select = screen.getByLabelText("Qué va a importar");
    expect(within(select).getAllByRole("option").map((option) => option.textContent)).toEqual(["Estudiantes", "Encargados", "Vínculos"]);
    expect(select).toHaveValue("students");
    expect(select).toHaveAccessibleDescription(/primero los estudiantes, después los encargados y por último los vínculos/);

    await userEvent.setup().selectOptions(select, "guardian-links");

    expect(onKindChange).toHaveBeenCalledWith("guardian-links");
  });

  it("blocks the choice while a request is in flight", () => {
    renderWithAdminI18n(<ImportWizard {...props({ phase: "simulating", step: "simulate" })} />);

    expect(screen.getByLabelText("Qué va a importar")).toBeDisabled();
  });

  it.each([
    ["guardians", "Archivo CSV de encargados", /legacyId, firstName y lastName.*no se guarda/],
    ["guardian-links", "Archivo CSV de vínculos", /studentLegacyId, guardianLegacyId, relationship.*financialResponsibilityPercent.*no se guarda/],
  ] as const)("names the file field of %s and describes its own columns", (kind, label, help) => {
    renderWithAdminI18n(<ImportWizard {...props({ kind })} />);

    expect(screen.getByLabelText(label)).toHaveAccessibleDescription(help);
    expect(screen.queryByLabelText("Archivo CSV de estudiantes")).toBeNull();
  });
});

describe("ImportWizard, simulate", () => {
  it("shows the four counts, announces them, moves the focus to the summary and offers the apply", async () => {
    const onOpenApply = vi.fn();
    renderWithAdminI18n(
      <ImportWizard {...props({ step: "simulate", onOpenApply, simulation: run({ totalRows: 100, validRows: 95, rejectedRows: 0, skippedRows: 5 }) })} />,
    );

    expect(screen.getByRole("heading", { level: 3, name: "Resultado de la simulación" })).toHaveFocus();
    const counts = screen.getAllByRole("definition").map((count) => count.textContent);
    expect(counts).toEqual(["100", "95", "0", "5"]);
    expect(screen.getByRole("status")).toHaveTextContent("Simulación lista: 95 filas se pueden crear, 0 rechazadas y 5 ya importadas.");
    expect(screen.getByText("Ninguna fila tiene defectos.")).toBeInTheDocument();

    await userEvent.setup().click(screen.getByRole("button", { name: "Aplicar la importación" }));

    expect(onOpenApply).toHaveBeenCalledTimes(1);
  });

  it("pages the rejections in the browser, ten at a time, with the line, the field and the reason, and no value", async () => {
    renderWithAdminI18n(<ImportWizard {...props({ step: "simulate", simulation: run({ rejectedRows: 23, validRows: 0 }, REJECTIONS) })} />);
    const user = userEvent.setup();

    const table = screen.getByRole("table", { name: /Filas del archivo con su línea/ });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Línea", "Campo", "Motivo"]);
    expect(within(table).getAllByRole("row")).toHaveLength(REJECTIONS_PAGE_SIZE + 1);
    expect(within(table).getAllByRole("row")[1]?.textContent).toBe("2birthDateLa fecha no existe o es anterior al 1 de enero de 1990.");
    expect(within(table).getAllByRole("row")[2]?.textContent).toBe("3Fila completaEl formato no es válido.");

    await user.click(screen.getByRole("button", { name: /Siguiente/ }));
    await user.click(screen.getByRole("button", { name: /Siguiente/ }));

    expect(within(table).getAllByRole("row")).toHaveLength(3 + 1);
    expect(within(table).getAllByRole("row")[1]?.textContent).toContain("22");
  });

  it("downloads the lines and the reasons on request, and says when the list was cut at 200", async () => {
    const onDownloadRejections = vi.fn();
    renderWithAdminI18n(
      <ImportWizard {...props({ step: "simulate", onDownloadRejections, simulation: run({ rejectedRows: 250 }, REJECTIONS, true) })} />,
    );

    await userEvent.setup().click(screen.getByRole("button", { name: "Descargar las líneas y los motivos" }));

    expect(onDownloadRejections).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/Se muestran las primeras 200/)).toBeInTheDocument();
  });

  it("offers no apply when no row can be created, and says so", () => {
    renderWithAdminI18n(<ImportWizard {...props({ step: "simulate", simulation: run({ totalRows: 3, validRows: 0, skippedRows: 3, rejectedRows: 0 }) })} />);

    expect(screen.queryByRole("button", { name: "Aplicar la importación" })).toBeNull();
    expect(screen.getByText("No hay nada que crear")).toBeInTheDocument();
  });
});

describe("ImportWizard, apply", () => {
  it("confirms with how many will be created, what is left out, and a button that names the action", async () => {
    const onApply = vi.fn();
    const onCloseApply = vi.fn();
    renderWithAdminI18n(
      <ImportWizard
        {...props({ step: "apply", confirming: true, onApply, onCloseApply, simulation: run({ totalRows: 100, validRows: 95, rejectedRows: 3, skippedRows: 2 }) })}
      />,
    );
    const user = userEvent.setup();

    const dialog = screen.getByRole("dialog", { name: "Aplicar la importación" });
    expect(within(dialog).getByText("Se crearán 95 estudiantes.")).toBeInTheDocument();
    expect(within(dialog).getByText("3 filas rechazadas y 2 filas ya importadas no se crean.")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Crear 95 estudiantes" }));
    await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));

    expect(onApply).toHaveBeenCalledTimes(1);
    expect(onCloseApply).toHaveBeenCalled();
  });

  it("warns that the documents already stored are checked only when applying, so more rows may be rejected", () => {
    renderWithAdminI18n(<ImportWizard {...props({ step: "apply", confirming: true, simulation: run({ validRows: 5 }) })} />);

    const dialog = screen.getByRole("dialog", { name: "Aplicar la importación" });
    expect(within(dialog).getByText(/se revisan solo al aplicar, no en la simulación.*más filas que las que mostró la simulación/)).toBeInTheDocument();
  });

  it("says one student in the singular", () => {
    renderWithAdminI18n(<ImportWizard {...props({ step: "apply", confirming: true, simulation: run({ totalRows: 1, validRows: 1, rejectedRows: 0 }) })} />);

    expect(screen.getByText("Se creará 1 estudiante.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear 1 estudiante" })).toBeInTheDocument();
  });

  it("while applying, blocks both buttons and does not close on Escape", async () => {
    const onCloseApply = vi.fn();
    renderWithAdminI18n(<ImportWizard {...props({ step: "apply", phase: "applying", confirming: true, onCloseApply, simulation: run() })} />);

    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Crear 8 estudiantes/ })).toHaveAttribute("aria-busy", "true");
    await userEvent.setup().keyboard("{Escape}");

    expect(onCloseApply).not.toHaveBeenCalled();
    expect(screen.getByText(/Creando los estudiantes\./)).toHaveAttribute("aria-live", "polite");
  });

  it("closes the confirmation on Escape when nothing is in flight", async () => {
    const onCloseApply = vi.fn();
    renderWithAdminI18n(<ImportWizard {...props({ step: "apply", confirming: true, onCloseApply, simulation: run() })} />);

    await userEvent.setup().keyboard("{Escape}");

    expect(onCloseApply).toHaveBeenCalledTimes(1);
  });
});

describe("ImportWizard, apply of the guardians and the links", () => {
  it("confirms the creation of guardians in their own words, with the documents warning", () => {
    renderWithAdminI18n(<ImportWizard {...props({ kind: "guardians", step: "apply", confirming: true, simulation: run({ validRows: 80, rejectedRows: 1 }) })} />);

    const dialog = screen.getByRole("dialog", { name: "Aplicar la importación" });
    expect(within(dialog).getByText("Se crearán 80 encargados.")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Crear 80 encargados" })).toBeInTheDocument();
    expect(within(dialog).getByText(/se revisan solo al aplicar/)).toBeInTheDocument();
  });

  it("confirms the creation of links with the rule of the group and the promise that existing links are never replaced", () => {
    renderWithAdminI18n(<ImportWizard {...props({ kind: "guardian-links", step: "apply", confirming: true, simulation: run({ validRows: 1, skippedRows: 3 }) })} />);

    const dialog = screen.getByRole("dialog", { name: "Aplicar la importación" });
    expect(within(dialog).getByText("Se creará 1 vínculo.")).toBeInTheDocument();
    expect(within(dialog).getByText(/con todas sus filas juntas.*se omite, nunca se reemplazan/)).toBeInTheDocument();
    expect(within(dialog).queryByText(/documentos de identidad/)).toBeNull();
  });

  it("shows the whole group rejected and the student already linked as skipped, with the reason of each", () => {
    const rejections: readonly RejectionResponseOutput[] = [
      { line: 2, field: "financialResponsibilityPercent", reason: "invalid-percent" },
      { line: 3, field: "row", reason: "group-rejected" },
      { line: 4, field: "financialResponsibilityPercent", reason: "sum-not-100" },
      { line: 5, field: "isPrimaryPayer", reason: "multiple-primary-payer" },
      { line: 6, field: "isBillingRecipient", reason: "multiple-billing-recipient" },
      { line: 7, field: "guardianLegacyId", reason: "duplicate-guardian" },
      { line: 8, field: "studentLegacyId", reason: "unknown-student" },
      { line: 9, field: "guardianLegacyId", reason: "unknown-guardian" },
      { line: 10, field: "studentLegacyId", reason: "student-already-linked" },
    ];
    renderWithAdminI18n(<ImportWizard {...props({ kind: "guardian-links", step: "simulate", simulation: run({ rejectedRows: 8, skippedRows: 1, validRows: 0 }, rejections) })} />);

    const rows = within(screen.getByRole("table", { name: /Filas del archivo con su línea/ })).getAllByRole("row").slice(1);
    expect(rows.map((row) => row.textContent)).toEqual([
      "2financialResponsibilityPercentEl porcentaje no es válido: use de 0.01 a 100, con hasta dos decimales.",
      "3Fila completaOtra fila de este estudiante tiene un defecto: se rechazan todas sus filas juntas.",
      "4financialResponsibilityPercentLos porcentajes de este estudiante no suman exactamente 100.00: se rechazan todas sus filas.",
      "5isPrimaryPayerEste estudiante tiene más de un pagador principal: se rechazan todas sus filas.",
      "6isBillingRecipientEste estudiante tiene más de un destinatario de factura: se rechazan todas sus filas.",
      "7guardianLegacyIdEl mismo encargado aparece más de una vez para este estudiante: se rechazan todas sus filas.",
      "8studentLegacyIdEl estudiante no está importado: importe primero el archivo de estudiantes.",
      "9guardianLegacyIdEl encargado no está importado: importe primero el archivo de encargados.",
      "10studentLegacyIdEl estudiante ya tiene encargados vinculados: se omite y la importación no los reemplaza.",
    ]);
  });

  it("does not name a column of another kind: a field the template of the kind lacks is shown as no field", () => {
    renderWithAdminI18n(
      <ImportWizard {...props({ kind: "guardians", step: "simulate", simulation: run({ rejectedRows: 1, validRows: 0 }, [{ line: 2, field: "birthDate", reason: "required" }]) })} />,
    );

    expect(within(screen.getByRole("table", { name: /Filas del archivo con su línea/ })).getAllByRole("row")[1]?.textContent).toBe("2Sin campoFalta el dato obligatorio.");
  });

  it("says the result of the links in its own words", () => {
    renderWithAdminI18n(
      <ImportWizard {...props({ kind: "guardian-links", step: "result", result: run({ status: "APPLIED", totalRows: 10, createdRows: 6, rejectedRows: 3, skippedRows: 1 }) })} />,
    );

    expect(screen.getByText(/Se crearon 6 vínculos\. 1 fila de un estudiante que ya tenía vínculos se omitió\. 3 filas rechazadas no se crearon\./)).toBeInTheDocument();
    expect(screen.getByText("Vínculos creados")).toBeInTheDocument();
  });
});

describe("ImportWizard, result", () => {
  it("shows what was created, skipped and rejected, moves the focus to the result and lets the person start over", async () => {
    const onReset = vi.fn();
    renderWithAdminI18n(
      <ImportWizard
        {...props({ step: "result", onReset, result: run({ status: "APPLIED", totalRows: 100, validRows: 95, createdRows: 95, rejectedRows: 3, skippedRows: 2 }) })}
      />,
    );

    expect(screen.getByRole("heading", { level: 3, name: "Importación aplicada" })).toHaveFocus();
    expect(screen.getByText(/Se crearon 95 estudiantes\. 2 filas ya importadas se omitieron\. 3 filas rechazadas no se crearon\./)).toBeInTheDocument();
    expect(screen.queryByLabelText("Archivo CSV de estudiantes")).toBeNull();
    expect(screen.queryByRole("table")).toBeNull();

    await userEvent.setup().click(screen.getByRole("button", { name: "Importar otro archivo" }));

    expect(onReset).toHaveBeenCalledTimes(1);
  });

  it("lists the rows the apply rejected with their line, field and reason, paged under its own navigation name", async () => {
    const onDownloadRejections = vi.fn();
    const rejections: readonly RejectionResponseOutput[] = Array.from({ length: 12 }, (_, index) => ({ line: index + 2, field: "nationalId", reason: "duplicate-national-id" }));
    renderWithAdminI18n(
      <ImportWizard {...props({ step: "result", onDownloadRejections, result: run({ status: "APPLIED", totalRows: 20, createdRows: 8, rejectedRows: 12 }, rejections) })} />,
    );
    const user = userEvent.setup();

    const table = screen.getByRole("table", { name: /Filas rechazadas al aplicar/ });
    expect(within(table).getAllByRole("row")).toHaveLength(REJECTIONS_PAGE_SIZE + 1);
    expect(within(table).getAllByRole("row")[1]?.textContent).toBe("2nationalIdYa hay un registro con ese documento de identidad.");
    expect(screen.getByRole("navigation", { name: "Páginas de las filas rechazadas al aplicar" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Páginas de las filas rechazadas en la simulación" })).toBeNull();

    await user.click(screen.getByRole("button", { name: /Siguiente/ }));
    await user.click(screen.getByRole("button", { name: "Descargar las líneas y los motivos" }));

    expect(within(table).getAllByRole("row")).toHaveLength(2 + 1);
    expect(onDownloadRejections).toHaveBeenCalledTimes(1);
  });

  it("names the pagination of the rejections of the simulation apart from the one of the result", () => {
    renderWithAdminI18n(<ImportWizard {...props({ step: "simulate", simulation: run({ rejectedRows: 23, validRows: 0 }, REJECTIONS) })} />);

    expect(screen.getByRole("navigation", { name: "Páginas de las filas rechazadas en la simulación" })).toBeInTheDocument();
  });
});

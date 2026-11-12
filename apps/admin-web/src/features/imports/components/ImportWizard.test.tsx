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

    expect(screen.getByRole("region", { name: "Importar estudiantes" })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText(/Simulando la importación\./)).toHaveAttribute("aria-live", "polite");
    expect(screen.getByRole("button", { name: /Simular la importación/ })).toHaveAttribute("aria-busy", "true");
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

    await userEvent.setup().click(screen.getByRole("button", { name: "Importar otro archivo" }));

    expect(onReset).toHaveBeenCalledTimes(1);
  });
});

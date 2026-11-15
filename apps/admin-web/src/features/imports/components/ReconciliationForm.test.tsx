import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialReconciliationValues } from "../reconciliation-model.ts";
import { ReconciliationForm, type ReconciliationFormProps } from "./ReconciliationForm.tsx";

// States. AW57, AW58: the form of the acta with props alone: the four states of the years (loading, failed, empty, ready), the errors under their field and
// in the summary, the button busy while the signing is in flight, and the success message.
const OPTIONS = [
  { value: "11111111-1111-4111-8111-111111111111", label: "Año lectivo 2026" },
  { value: "22222222-2222-4222-8222-222222222222", label: "Año lectivo 2025" },
];

function props(override: Partial<ReconciliationFormProps> = {}): ReconciliationFormProps {
  return {
    years: { state: "ready", options: OPTIONS, onRetry: vi.fn() },
    values: initialReconciliationValues(),
    fieldErrors: {},
    summary: [],
    liveMessage: "",
    submitting: false,
    summaryRef: createRef<HTMLDivElement>(),
    alertRef: createRef<HTMLDivElement>(),
    signedRef: createRef<HTMLDivElement>(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onSubmit: vi.fn((event: { preventDefault: () => void }) => {
      event.preventDefault();
    }),
    ...override,
  };
}

describe("ReconciliationForm", () => {
  it("labels the three fields, tells how to write them and says that signing again keeps both actas", () => {
    renderWithAdminI18n(<ReconciliationForm {...props()} />);

    expect(screen.getByLabelText(/Año lectivo/)).toBeRequired();
    expect(screen.getByLabelText(/Referencia del listado oficial/)).toHaveAccessibleDescription(/hasta 100 caracteres/);
    expect(screen.getByLabelText(/Total de matrículas del listado oficial/)).toHaveAccessibleDescription(/de 0 a 1 000 000/);
    expect(screen.getByText(/Si firma otra vez, se conservan ambas actas/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Firmar acta" })).toBeEnabled();
  });

  it("offers the years to choose from and reports what is typed and what is left", async () => {
    const onChange = vi.fn();
    const onBlur = vi.fn();
    renderWithAdminI18n(<ReconciliationForm {...props({ onChange, onBlur })} />);
    const user = userEvent.setup();

    await user.selectOptions(screen.getByLabelText(/Año lectivo/), OPTIONS[1]?.value ?? "");
    await user.type(screen.getByLabelText(/Total de matrículas/), "7");
    await user.tab();

    expect(onChange).toHaveBeenCalledWith("academicYearId", OPTIONS[1]?.value);
    expect(onChange).toHaveBeenCalledWith("officialEnrollmentCount", "7");
    expect(onBlur).toHaveBeenCalledWith("officialEnrollmentCount");
  });

  it("puts each error under its field, in the summary, and marks the field as invalid", () => {
    renderWithAdminI18n(
      <ReconciliationForm
        {...props({
          fieldErrors: { officialReference: "Este dato es obligatorio." },
          summary: [{ controlId: "reconciliation-reference", message: "Este dato es obligatorio." }],
        })}
      />,
    );

    expect(screen.getByLabelText(/Referencia del listado oficial/)).toBeInvalid();
    expect(screen.getByLabelText(/Referencia del listado oficial/)).toHaveAccessibleDescription(/Este dato es obligatorio/);
    expect(screen.getByRole("link", { name: "Este dato es obligatorio." })).toHaveAttribute("href", "#reconciliation-reference");
  });

  it("while signing, the button is busy, the fields wait and a polite region says why", () => {
    renderWithAdminI18n(<ReconciliationForm {...props({ submitting: true, liveMessage: "Firmando el acta. No cierre esta pantalla." })} />);

    expect(screen.getByRole("button", { name: /Firmar acta/ })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByLabelText(/Referencia del listado oficial/)).toBeDisabled();
    expect(screen.getByText("Firmando el acta. No cierre esta pantalla.")).toHaveAttribute("aria-live", "polite");
  });

  it("shows the failure of the signing as an alert with its trace id", () => {
    renderWithAdminI18n(<ReconciliationForm {...props({ alert: { message: "No tiene permiso para realizar esta acción.", traceId: "tr-9" } })} />);

    expect(screen.getByRole("alert")).toHaveTextContent("No tiene permiso para realizar esta acción.");
    expect(screen.getByRole("alert")).toHaveTextContent("tr-9");
  });

  it("shows the success of the signing with the year, the reference and the difference of the server", () => {
    renderWithAdminI18n(
      <ReconciliationForm {...props({ signed: { yearName: "Año lectivo 2026", reference: "Oficio 7", difference: "El sistema tiene 2 matrículas activas menos que el listado oficial." } })} />,
    );

    const notice = screen.getByRole("status", { name: "" });
    expect(notice).toHaveTextContent("Acta firmada");
    expect(notice).toHaveTextContent("Año lectivo 2026");
    expect(notice).toHaveTextContent("«Oficio 7»");
    expect(notice).toHaveTextContent("2 matrículas activas menos");
  });

  it("shows the loading of the years as a skeleton in place of the selector", () => {
    renderWithAdminI18n(<ReconciliationForm {...props({ years: { state: "loading", options: [], onRetry: vi.fn() } })} />);

    expect(screen.getByRole("status", { name: "Cargando los años lectivos." })).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Año lectivo/)).not.toBeInTheDocument();
  });

  it("when the years cannot be read, says so with a way to try again and does not crash", async () => {
    const onRetry = vi.fn();
    renderWithAdminI18n(<ReconciliationForm {...props({ years: { state: "failed", options: [], onRetry } })} />);

    expect(screen.getByRole("alert")).toHaveTextContent("No se pudieron cargar los años lectivos");
    await userEvent.setup().click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("when there are no years, says where to create one", () => {
    renderWithAdminI18n(<ReconciliationForm {...props({ years: { state: "empty", options: [], onRetry: vi.fn() } })} />);

    expect(screen.getByText("Todavía no hay años lectivos")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });
});

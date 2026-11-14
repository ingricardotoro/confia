import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { guardian, linkedStudent } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { GuardianDetail } from "./GuardianDetail.tsx";

// States. AW57, AW58: the detail dialog with props alone: loading (a skeleton), failed (with a retry), and ready with its students, none,
// or an inactive guardian that still has some.
describe("GuardianDetail", () => {
  it("is a skeleton with a text for the reader while it loads", () => {
    renderWithAdminI18n(<GuardianDetail state={{ kind: "loading" }} onClose={vi.fn()} />);

    expect(screen.getByText("Cargando el detalle del encargado.")).toHaveClass("sr-only");
  });

  it("offers a retry when it failed", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<GuardianDetail state={{ kind: "failed", description: "Falló.", onRetry }} onClose={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("shows the facts, with 'not provided' for the contact data that is empty, and never a document", () => {
    renderWithAdminI18n(
      <GuardianDetail state={{ kind: "ready", row: guardian({ hasNationalId: true, email: null, phone: "+504 9999-0000", preferredChannel: "SMS", students: [] }) }} onClose={vi.fn()} />,
    );

    const dialog = screen.getByRole("dialog", { name: "Detalle del encargado" });
    expect(within(dialog).getByText("Mensaje de texto")).toBeInTheDocument();
    expect(within(dialog).getByText("+504 9999-0000")).toBeInTheDocument();
    expect(within(dialog).getAllByText("Sin registrar").length).toBeGreaterThan(0);
    expect(within(dialog).getByText("Este encargado no está vinculado a ningún estudiante.")).toBeInTheDocument();
  });

  it("lists the students with the percentage written with two decimals, aligned right, and yes or no for the marks", () => {
    renderWithAdminI18n(
      <GuardianDetail
        state={{ kind: "ready", row: guardian({ students: [linkedStudent({ financialResponsibilityPercent: 33.3, isPrimaryPayer: false, isBillingRecipient: true })] }) }}
        onClose={vi.fn()}
      />,
    );

    const table = screen.getByRole("table");
    const cell = within(table).getByText("33.30 %");
    expect(cell).toHaveClass("text-right", "tabular-nums");
    const row = within(table).getAllByRole("row")[1] as HTMLElement;
    expect(within(row).getByText("No")).toBeInTheDocument();
    expect(within(row).getByText("Sí")).toBeInTheDocument();
  });

  it("warns when an inactive guardian still has students", () => {
    renderWithAdminI18n(<GuardianDetail state={{ kind: "ready", row: guardian({ status: "INACTIVE", students: [linkedStudent()] }) }} onClose={vi.fn()} />);

    expect(screen.getByText(/sus vínculos siguen vigentes/)).toBeInTheDocument();
  });
});

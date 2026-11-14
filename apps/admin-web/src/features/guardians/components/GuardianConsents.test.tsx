import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { GuardianConsentsResponseOutput } from "@confia/contracts/admin";
import { describe, expect, it, vi } from "vitest";
import { consentEvent, guardianConsents } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { GuardianConsents } from "./GuardianConsents.tsx";

const dataOf = (states: Parameters<typeof guardianConsents>[0] = [], history: Parameters<typeof guardianConsents>[1] = []): GuardianConsentsResponseOutput =>
  guardianConsents(states, history).body as GuardianConsentsResponseOutput;

// States. AW57, AW58: the consents of a guardian with props alone: loading (a skeleton with a text for the reader), failed (with a
// retry), and ready with the six standings (never preselected), the inactive note, the message of the last change and the actions of
// each cell.
describe("GuardianConsents", () => {
  it("is a skeleton with a text for the reader while it loads", () => {
    renderWithAdminI18n(<GuardianConsents state={{ kind: "loading" }} guardianActive form={undefined} message="" onOpen={vi.fn()} />);

    expect(screen.getByText("Cargando los consentimientos del encargado.")).toHaveClass("sr-only");
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("offers a retry when it failed", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(
      <GuardianConsents state={{ kind: "failed", description: "Falló.", onRetry }} guardianActive form={undefined} message="" onOpen={vi.fn()} />,
    );

    expect(screen.getByText("No se pudieron cargar los consentimientos")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("says that nothing was recorded in every one of the six cells of a guardian with no events, and preselects nothing", () => {
    renderWithAdminI18n(<GuardianConsents state={{ kind: "ready", data: dataOf() }} guardianActive form={undefined} message="" onOpen={vi.fn()} />);

    expect(screen.getAllByText("Sin consentimiento registrado")).toHaveLength(6);
    expect(screen.queryByText("Concedido")).toBeNull();
  });

  it("shows the standing the server holds for a combination and its policy version", () => {
    const data = dataOf([{ channel: "SMS", purpose: "MARKETING", state: "GRANTED", since: "2026-11-02T15:30:00Z", policyVersion: "2026-01", evidenceSource: "SIGNED_PAPER_FORM" }]);
    renderWithAdminI18n(<GuardianConsents state={{ kind: "ready", data }} guardianActive form={undefined} message="" onOpen={vi.fn()} />);

    expect(screen.getAllByText("Concedido")).toHaveLength(1);
    expect(screen.getAllByText("Sin consentimiento registrado")).toHaveLength(5);
    expect(screen.getByText(/2026-01/)).toBeInTheDocument();
  });

  it("asks to open the form of the grant of the first cell, and of its withdrawal", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    renderWithAdminI18n(<GuardianConsents state={{ kind: "ready", data: dataOf() }} guardianActive form={undefined} message="" onOpen={onOpen} />);

    const buttons = screen.getAllByRole("button");
    await user.click(buttons.at(0) as HTMLElement);
    await user.click(buttons.at(1) as HTMLElement);

    expect(onOpen).toHaveBeenNthCalledWith(1, { channel: "EMAIL", purpose: "INSTITUTIONAL", action: "GRANT" });
    expect(onOpen).toHaveBeenNthCalledWith(2, { channel: "EMAIL", purpose: "INSTITUTIONAL", action: "WITHDRAW" });
  });

  it("warns that an inactive guardian cannot receive a new consent, and only then", () => {
    const note = "El encargado está inactivo: no se puede registrar un consentimiento nuevo, pero sí una revocación.";
    const { unmount } = renderWithAdminI18n(
      <GuardianConsents state={{ kind: "ready", data: dataOf() }} guardianActive={false} form={undefined} message="" onOpen={vi.fn()} />,
    );
    expect(screen.getByText(note)).toBeInTheDocument();
    unmount();

    renderWithAdminI18n(<GuardianConsents state={{ kind: "ready", data: dataOf() }} guardianActive form={undefined} message="" onOpen={vi.fn()} />);
    expect(screen.queryByText(note)).toBeNull();
  });

  it("announces the message of the last change in a polite region", () => {
    renderWithAdminI18n(
      <GuardianConsents state={{ kind: "ready", data: dataOf([], [consentEvent()]) }} guardianActive form={undefined} message="Consentimiento registrado." onOpen={vi.fn()} />,
    );

    const message = screen.getByText("Consentimiento registrado.");
    expect(message.closest("[aria-live='polite']")).not.toBeNull();
  });
});

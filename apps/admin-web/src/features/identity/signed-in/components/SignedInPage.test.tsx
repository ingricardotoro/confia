import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { SignedInPage } from "./SignedInPage.tsx";

// admin-web-foundations-and-session, task 9.1 (part 9b; AW57, AW58). The presentation of the minimal signed
// in page, rendered with props alone: no Query provider, no router and no request.
// States. Loading: "loading". Error: "error" (the read failed) and "ready" with `signOutFailed` (the
// sign out failed). Success: "ready". Empty: not applicable, the page lists nothing and shows no data.
describe("SignedInPage", () => {
  it("AW57: the ready state has the heading, the confirmation and a sign out button", () => {
    const onSignOut = vi.fn();
    renderWithAdminI18n(<SignedInPage status="ready" signingOut={false} signOutFailed={false} onSignOut={onSignOut} />);

    expect(screen.getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeInTheDocument();
    expect(screen.getByText("Su sesión está activa.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cerrar sesión" }));

    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  it("the loading state announces politely and does not confirm the session yet", () => {
    renderWithAdminI18n(<SignedInPage status="loading" />);

    expect(screen.getByText("Cargando su sesión.")).toHaveAttribute("aria-live", "polite");
    expect(screen.queryByText("Su sesión está activa.")).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("the error state shows the message the caller translated and a retry", () => {
    const onRetry = vi.fn();
    renderWithAdminI18n(<SignedInPage status="error" message="No se pudo conectar." onRetry={onRetry} />);

    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo conectar.");
    fireEvent.click(screen.getByRole("button"));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("while signing out the button says it is busy and stays enabled", () => {
    renderWithAdminI18n(<SignedInPage status="ready" signingOut signOutFailed={false} onSignOut={vi.fn()} />);

    // The busy indicator is inside the button, so its name holds the label and the indicator.
    const button = screen.getByRole("button", { name: /Cerrar sesión/ });
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(button).toBeEnabled();
  });

  it("a failed sign out shows an error alert and offers the retry in place of the button", () => {
    renderWithAdminI18n(<SignedInPage status="ready" signingOut={false} signOutFailed onSignOut={vi.fn()} />);

    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo cerrar la sesión. Su sesión sigue activa.");
    expect(screen.getByRole("button", { name: "Reintentar cierre de sesión" })).toBeInTheDocument();
  });
});

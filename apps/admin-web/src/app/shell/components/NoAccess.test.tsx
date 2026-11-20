import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { NoAccess } from "./NoAccess.tsx";

// identity-session-permissions (AW57, AW58). The presentation of the page a person reaches without the permission it needs.
// States. Success: the page itself. Loading, empty, error: not applicable, it reads nothing (the session, which carries the
// permissions, is loaded and its failure shown by the shell before this page can be chosen).
describe("NoAccess", () => {
  it("AW57: renders with props alone, a heading, the reason and a way back that the caller handles", () => {
    const onNavigate = vi.fn();
    renderWithAdminI18n(<NoAccess homeHref="/" onNavigate={onNavigate} />);

    expect(screen.getByRole("heading", { level: 1, name: "Sin acceso" })).toBeInTheDocument();
    expect(screen.getByText(/Su rol no tiene permiso para ver esta página/)).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "Volver al inicio" });
    expect(link).toHaveAttribute("href", "/");

    fireEvent.click(link);

    expect(onNavigate).toHaveBeenCalledTimes(1);
  });

  it("keeps the browser meaning of a modified click", () => {
    const onNavigate = vi.fn();
    renderWithAdminI18n(<NoAccess homeHref="/" onNavigate={onNavigate} />);

    fireEvent.click(screen.getByRole("link", { name: "Volver al inicio" }), { ctrlKey: true });

    expect(onNavigate).not.toHaveBeenCalled();
  });
});

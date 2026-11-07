import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../test/render.tsx";
import { NotFound } from "./NotFound.tsx";

// admin-web-foundations-and-session, task 9.1 (AW18, AW57, AW58). The presentation of the not found screen.
// States. Success: the screen itself. Loading, empty, error: not applicable, the screen reads nothing.
describe("NotFound", () => {
  it("AW57: renders with props alone, a heading and a link that the caller handles", () => {
    const onNavigate = vi.fn();
    renderWithAdminI18n(<NotFound href="/iniciar-sesion" onNavigate={onNavigate} />);

    expect(screen.getByRole("heading", { level: 1, name: "Esta página no existe." })).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "Ir a inicio de sesión" });
    expect(link).toHaveAttribute("href", "/iniciar-sesion");

    fireEvent.click(link);

    expect(onNavigate).toHaveBeenCalledTimes(1);
  });

  it("paints the link with the link color token, which keeps its contrast in the dark theme", () => {
    renderWithAdminI18n(<NotFound href="/iniciar-sesion" onNavigate={vi.fn()} />);

    const link = screen.getByRole("link", { name: "Ir a inicio de sesión" });
    expect(link).toHaveClass("text-text-link");
    expect(link).not.toHaveClass("text-primary");
  });
});

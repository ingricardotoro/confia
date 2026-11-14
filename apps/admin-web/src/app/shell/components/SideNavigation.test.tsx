import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { SideNavigation, type SideNavigationItem } from "./SideNavigation.tsx";

// States. AW57, AW58: the navigation rendered with props alone. Loading and error do not apply: the
// entries are static and arrive translated. Empty: no entries renders an empty list inside the
// landmark. Success: the entries, one of them current.
const ITEMS: SideNavigationItem[] = [
  { id: "home", label: "Inicio", href: "/", current: false },
  { id: "organization", label: "Estructura académica", href: "/organization", current: true },
];

describe("SideNavigation", () => {
  it("AW57: is a navigation landmark with one link per entry and only the current one marked", () => {
    renderWithAdminI18n(<SideNavigation items={ITEMS} onNavigate={vi.fn()} />);

    const nav = screen.getByRole("navigation", { name: "Navegación principal" });
    expect(nav).toBeVisible();
    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual(["Inicio", "Estructura académica"]);
    expect(screen.getByRole("link", { name: "Estructura académica" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Inicio" })).not.toHaveAttribute("aria-current");
  });

  it("navigates on a plain click and leaves a modified click to the browser", () => {
    const onNavigate = vi.fn();
    renderWithAdminI18n(<SideNavigation items={ITEMS} onNavigate={onNavigate} />);

    fireEvent.click(screen.getByRole("link", { name: "Inicio" }), { ctrlKey: true });
    expect(onNavigate).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("link", { name: "Inicio" }));
    expect(onNavigate).toHaveBeenCalledWith("/");
  });

  it("is reachable and operable from the keyboard, in order", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    renderWithAdminI18n(<SideNavigation items={ITEMS} onNavigate={onNavigate} />);

    await user.tab();
    expect(screen.getByRole("link", { name: "Inicio" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("link", { name: "Estructura académica" })).toHaveFocus();
    await user.keyboard("{Enter}");

    expect(onNavigate).toHaveBeenCalledWith("/organization");
  });

  it("renders an empty list when there are no entries", () => {
    renderWithAdminI18n(<SideNavigation items={[]} onNavigate={vi.fn()} />);

    expect(screen.getByRole("navigation", { name: "Navegación principal" })).toBeVisible();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});

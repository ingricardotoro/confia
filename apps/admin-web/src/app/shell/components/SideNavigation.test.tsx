import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import {
  SideNavigation,
  type SideNavigationGroup,
  type SideNavigationItem,
} from "./SideNavigation.tsx";

// States. AW57, AW58: the navigation rendered with props alone. Loading and error do not apply: the
// entries are static and arrive translated. Empty: no entries renders an empty list inside the
// landmark. Success: the entries, one of them current.
const ITEMS: SideNavigationItem[] = [
  { id: "home", label: "Inicio", href: "/", current: false },
  {
    id: "organization",
    label: "Estructura académica",
    href: "/organization",
    current: true,
  },
];

describe("SideNavigation", () => {
  it("AW57: is a navigation landmark with one link per entry and only the current one marked", () => {
    renderWithAdminI18n(<SideNavigation items={ITEMS} onNavigate={vi.fn()} />);

    const nav = screen.getByRole("navigation", {
      name: "Navegación principal",
    });
    expect(nav).toBeVisible();
    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual(
      ["Inicio", "Estructura académica"],
    );
    expect(
      screen.getByRole("link", { name: "Estructura académica" }),
    ).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Inicio" })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("navigates on a plain click and leaves a modified click to the browser", () => {
    const onNavigate = vi.fn();
    renderWithAdminI18n(
      <SideNavigation items={ITEMS} onNavigate={onNavigate} />,
    );

    fireEvent.click(screen.getByRole("link", { name: "Inicio" }), {
      ctrlKey: true,
    });
    expect(onNavigate).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("link", { name: "Inicio" }));
    expect(onNavigate).toHaveBeenCalledWith("/");
  });

  it("is reachable and operable from the keyboard, in order", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    renderWithAdminI18n(
      <SideNavigation items={ITEMS} onNavigate={onNavigate} />,
    );

    await user.tab();
    expect(screen.getByRole("link", { name: "Inicio" })).toHaveFocus();
    await user.tab();
    expect(
      screen.getByRole("link", { name: "Estructura académica" }),
    ).toHaveFocus();
    await user.keyboard("{Enter}");

    expect(onNavigate).toHaveBeenCalledWith("/organization");
  });

  describe("sections", () => {
    const GROUPS: SideNavigationGroup[] = [
      {
        id: "finance",
        label: "Finanzas",
        items: [
          { id: "charges", label: "Cobros", href: "/charges", current: false },
        ],
      },
      {
        id: "settings",
        label: "Configuración",
        items: [
          { id: "ledger", label: "Plan de cuentas", href: "/ledger", current: false },
        ],
      },
    ];

    it("shows each header as a button that reports and controls its list, and hides a closed list", () => {
      renderWithAdminI18n(
        <SideNavigation
          items={ITEMS}
          groups={GROUPS}
          expandedGroupIds={new Set(["settings"])}
          onToggleGroup={vi.fn()}
          onNavigate={vi.fn()}
        />,
      );

      const finance = screen.getByRole("button", { name: "Finanzas" });
      const settings = screen.getByRole("button", { name: "Configuración" });
      expect(finance).toHaveAttribute("aria-expanded", "false");
      expect(settings).toHaveAttribute("aria-expanded", "true");
      expect(finance.getAttribute("aria-controls")).toBeTruthy();
      expect(document.getElementById(finance.getAttribute("aria-controls") ?? "")).not.toBeVisible();
      expect(screen.queryByRole("link", { name: "Cobros" })).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Plan de cuentas" })).toBeVisible();
      // The plain entries are never disclosures.
      expect(screen.getByRole("link", { name: "Inicio" })).toBeVisible();
    });

    it("asks to toggle on a click and on Enter and Space, and keeps closed entries out of the tab order", async () => {
      const user = userEvent.setup();
      const onToggleGroup = vi.fn();
      renderWithAdminI18n(
        <SideNavigation
          items={[]}
          groups={GROUPS}
          onToggleGroup={onToggleGroup}
          onNavigate={vi.fn()}
        />,
      );

      await user.tab();
      expect(screen.getByRole("button", { name: "Finanzas" })).toHaveFocus();
      await user.keyboard("{Enter}");
      await user.keyboard(" ");
      await user.click(screen.getByRole("button", { name: "Finanzas" }));
      expect(onToggleGroup).toHaveBeenCalledTimes(3);
      expect(onToggleGroup).toHaveBeenCalledWith("finance");
      // Next stop is the other header: the closed list of the first one has no links to stop at.
      await user.tab();
      expect(screen.getByRole("button", { name: "Configuración" })).toHaveFocus();
    });
  });

  it("renders an empty list when there are no entries", () => {
    renderWithAdminI18n(<SideNavigation items={[]} onNavigate={vi.fn()} />);

    expect(
      screen.getByRole("navigation", { name: "Navegación principal" }),
    ).toBeVisible();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});

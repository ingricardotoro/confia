import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AppShell, type AppShellProps } from "./AppShell.tsx";

// States. AW57, AW58: the frame rendered with props alone: no Query provider, no router, no request.
// Loading and error of the session read are the container's: it renders no shell until the read is
// done. Error of the sign out: "signOutFailed". Busy: "signingOut". Empty: not applicable, the frame
// lists nothing. Success: the default.
function renderShell(props: Partial<AppShellProps> = {}) {
  return renderWithAdminI18n(
    <AppShell
      navigation={[
        { id: "home", label: "Inicio", href: "/", current: true },
        {
          id: "organization",
          label: "Estructura académica",
          href: "/organization",
          current: false,
        },
      ]}
      onNavigate={vi.fn()}
      signingOut={false}
      signOutFailed={false}
      onSignOut={vi.fn()}
      {...props}
    >
      <h1>Página</h1>
    </AppShell>,
  );
}

describe("AppShell", () => {
  it("AW57: has a banner with the account area, the navigation, and the page inside main", () => {
    renderShell();

    expect(
      within(screen.getByRole("banner")).getByRole("button", {
        name: "Cerrar sesión",
      }),
    ).toBeVisible();
    expect(screen.getByRole("group", { name: "Su cuenta" })).toBeVisible();
    expect(
      screen.getByRole("navigation", { name: "Navegación principal" }),
    ).toBeVisible();
    expect(
      within(screen.getByRole("main")).getByRole("heading", {
        level: 1,
        name: "Página",
      }),
    ).toBeVisible();
  });

  it("offers a skip link as the first stop of the tab order, pointing at main", async () => {
    const user = userEvent.setup();
    renderShell();

    await user.tab();

    const skip = screen.getByRole("link", { name: "Saltar al contenido" });
    expect(skip).toHaveFocus();
    expect(skip).toHaveAttribute("href", `#${screen.getByRole("main").id}`);
  });

  it("calls the sign out once per click", async () => {
    const user = userEvent.setup();
    const onSignOut = vi.fn();
    renderShell({ onSignOut });

    await user.click(screen.getByRole("button", { name: "Cerrar sesión" }));

    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  it("while signing out the button is busy and stays enabled, and does not call again", async () => {
    const user = userEvent.setup();
    const onSignOut = vi.fn();
    renderShell({ signingOut: true, onSignOut });

    // While busy the button also carries the name of its spinner, so it is found by a part of its name.
    const button = screen.getByRole("button", { name: /Cerrar sesión/ });
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(button).toBeEnabled();
    await user.click(button);

    expect(onSignOut).not.toHaveBeenCalled();
  });

  it("when the sign out failed it says so in an alert and offers to retry", () => {
    renderShell({ signOutFailed: true });

    expect(screen.getByRole("alert")).toHaveTextContent(
      "No se pudo cerrar la sesión.",
    );
    expect(
      screen.getByRole("button", { name: "Reintentar cierre de sesión" }),
    ).toBeVisible();
  });
  describe("collapsing the side navigation", () => {
    const wrapper = () => document.getElementById("side-navigation") as HTMLElement;
    const nav = () => screen.getByRole("navigation", { name: "Navegación principal" });

    it("has one toggle at the foot of the navigation that names its action, reports the state and points at the column", async () => {
      const user = userEvent.setup();
      const onSidebarCollapsedChange = vi.fn();
      renderShell({ onSidebarCollapsedChange });

      const toggle = within(nav()).getByRole("button", { name: "Contraer el menú lateral", expanded: true });
      expect(toggle).toHaveAttribute("aria-controls", "side-navigation-list");
      expect(toggle).toHaveAttribute("title", "Contraer el menú lateral");
      expect(within(screen.getByRole("banner")).queryByRole("button", { name: /menú lateral/ })).toBeNull();
      await user.click(toggle);

      expect(onSidebarCollapsedChange).toHaveBeenCalledWith(true);
    });

    it("collapsed it stays on wide screens as a rail: the logo keeps its name, the lists leave and the toggle offers to expand", async () => {
      const user = userEvent.setup();
      const onSidebarCollapsedChange = vi.fn();
      renderShell({ sidebarCollapsed: true, onSidebarCollapsedChange });

      expect(wrapper()).toHaveClass("lg:flex");
      expect(wrapper()).not.toHaveClass("lg:hidden");
      expect(nav()).toHaveClass("lg:w-16");
      expect(within(nav()).getByRole("img", { name: "CONFIA, control financiero académico" })).toBeInTheDocument();
      expect(within(nav()).getByRole("link", { name: "Inicio" }).closest('[class~="lg:hidden"]')).not.toBeNull();
      await user.click(within(nav()).getByRole("button", { name: "Expandir el menú lateral", expanded: false }));

      expect(onSidebarCollapsedChange).toHaveBeenCalledWith(false);
    });

    it("is one toggle element whose label, state and icon follow the choice, and the list leaves while collapsed", () => {
      const view = renderShell();
      const toggleOf = () => within(nav()).getAllByRole("button", { name: /(Contraer|Expandir) el menú lateral/ });

      expect(toggleOf()).toHaveLength(1);
      const first = toggleOf()[0] as HTMLElement;
      expect(first).toHaveAttribute("aria-expanded", "true");
      expect(first).toHaveTextContent("Contraer el menú lateral");
      const list = document.getElementById("side-navigation-list") as HTMLElement;
      expect(list).not.toHaveClass("lg:hidden");

      view.rerender(
        <AppShell navigation={[{ id: "home", label: "Inicio", href: "/", current: true }]} onNavigate={vi.fn()} sidebarCollapsed signingOut={false} signOutFailed={false} onSignOut={vi.fn()}>
          <h1>Página</h1>
        </AppShell>,
      );

      expect(toggleOf()).toHaveLength(1);
      expect(toggleOf()[0]).toBe(first);
      expect(first).toHaveAttribute("aria-expanded", "false");
      expect(first).toHaveAttribute("aria-label", "Expandir el menú lateral");
      expect(first).toHaveTextContent("");
      expect(list).toHaveClass("lg:hidden");
    });

    it("tab order: expanded it runs skip link, links, toggle, top bar; collapsed it runs skip link, toggle, top bar", async () => {
      const user = userEvent.setup();
      const expanded = renderShell();
      await user.tab();
      // The panel close control (below lg only in a browser; jsdom shows it) precedes the links.
      await user.tab();
      await user.tab();
      expect(screen.getByRole("link", { name: "Inicio" })).toHaveFocus();
      await user.tab();
      await user.tab();
      expect(screen.getByRole("button", { name: "Contraer el menú lateral" })).toHaveFocus();
      await user.tab();
      expect(screen.getByRole("button", { name: "Abrir menú" })).toHaveFocus();
      expanded.unmount();

      renderShell({ sidebarCollapsed: true });
      // jsdom applies no stylesheet: the collapsed list is out of the order in a browser, which its class says.
      expect(document.getElementById("side-navigation-list")).toHaveClass("lg:hidden");
    });

    it("expanded it is the full column with no rail width", () => {
      renderShell();

      expect(nav()).toHaveClass("w-60");
      expect(nav()).not.toHaveClass("lg:w-16");
    });
  });

  // The page must end where its content ends: a root as tall as the viewport, a sticky navigation of one viewport
  // (so a tall page never leaves a band under it) and a main that takes the free height.
  it("keeps the page height clean: dynamic viewport root, sticky navigation, main that fills", () => {
    const { container } = renderShell();

    expect(container.firstElementChild).toHaveClass("flex", "min-h-dvh");
    expect(document.getElementById("side-navigation")).toHaveClass("lg:sticky", "lg:top-0", "lg:h-dvh", "lg:self-start");
    expect(screen.getByRole("main")).toHaveClass("flex-1");
    expect(container.innerHTML).not.toContain("min-h-screen");
  });
});

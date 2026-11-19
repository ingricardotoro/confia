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
  describe("hiding the side navigation", () => {
    const wrapper = () => document.getElementById("side-navigation") as HTMLElement;

    it("offers a toggle that names its action, reports the state and points at the navigation", async () => {
      const user = userEvent.setup();
      const onSidebarVisibleChange = vi.fn();
      renderShell({ onSidebarVisibleChange });

      const toggle = screen.getByRole("button", { name: "Ocultar el menú lateral", expanded: true });
      expect(toggle).toHaveAttribute("aria-controls", "side-navigation");
      expect(toggle).toHaveAttribute("title", "Ocultar el menú lateral");
      await user.click(toggle);

      expect(onSidebarVisibleChange).toHaveBeenCalledWith(false);
    });

    it("when hidden it leaves the layout on wide screens and the toggle offers to show it again", async () => {
      const user = userEvent.setup();
      const onSidebarVisibleChange = vi.fn();
      renderShell({ sidebarVisible: false, onSidebarVisibleChange });

      expect(wrapper()).toHaveClass("lg:hidden");
      expect(wrapper()).not.toHaveClass("lg:flex");
      const toggle = screen.getByRole("button", { name: "Mostrar el menú lateral", expanded: false });
      await user.click(toggle);

      expect(onSidebarVisibleChange).toHaveBeenCalledWith(true);
    });

    it("closes from inside the navigation and hands the focus to the toggle that stays", async () => {
      const user = userEvent.setup();
      const onSidebarVisibleChange = vi.fn();
      renderShell({ onSidebarVisibleChange });

      await user.click(within(screen.getByRole("navigation")).getByRole("button", { name: "Ocultar el menú lateral" }));

      expect(onSidebarVisibleChange).toHaveBeenCalledWith(false);
      expect(within(screen.getByRole("banner")).getByRole("button", { name: "Ocultar el menú lateral" })).toHaveFocus();
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

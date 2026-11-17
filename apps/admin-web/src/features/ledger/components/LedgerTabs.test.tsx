import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { LedgerTabs, type LedgerTab } from "./LedgerTabs.tsx";

// States. AW57, AW58: the sub navigation rendered with props alone. Loading and error do not apply: the views
// are static and arrive translated. Empty: no tabs renders an empty list inside the landmark. Success: the
// tabs, one of them current.
const TABS: LedgerTab[] = [
  { id: "accounts", label: "Plan de cuentas", href: "/ledger/accounts", current: false },
  { id: "posting-map", label: "Mapa de cuentas de cargos", href: "/ledger/posting-accounts", current: true },
];

describe("LedgerTabs", () => {
  it("AW57: is a navigation landmark with one link per view and only the current one marked", () => {
    renderWithAdminI18n(<LedgerTabs tabs={TABS} onNavigate={vi.fn()} />);

    expect(screen.getByRole("navigation", { name: "Vistas del plan de cuentas" })).toBeVisible();
    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual(["Plan de cuentas", "Mapa de cuentas de cargos"]);
    expect(screen.getByRole("link", { name: "Mapa de cuentas de cargos" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Plan de cuentas" })).not.toHaveAttribute("aria-current");
  });

  it("navigates on a plain click and leaves a modified click to the browser", () => {
    const onNavigate = vi.fn();
    renderWithAdminI18n(<LedgerTabs tabs={TABS} onNavigate={onNavigate} />);

    fireEvent.click(screen.getByRole("link", { name: "Plan de cuentas" }), { ctrlKey: true });
    expect(onNavigate).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("link", { name: "Plan de cuentas" }));
    expect(onNavigate).toHaveBeenCalledWith("/ledger/accounts");
  });

  it("is reachable and operable from the keyboard", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    renderWithAdminI18n(<LedgerTabs tabs={TABS} onNavigate={onNavigate} />);

    await user.tab();
    await user.keyboard("{Enter}");

    expect(onNavigate).toHaveBeenCalledWith("/ledger/accounts");
  });

  it("renders an empty list inside the landmark when there are no tabs", () => {
    renderWithAdminI18n(<LedgerTabs tabs={[]} onNavigate={vi.fn()} />);

    expect(screen.getByRole("navigation", { name: "Vistas del plan de cuentas" })).toBeVisible();
    expect(screen.queryAllByRole("link")).toHaveLength(0);
  });
});

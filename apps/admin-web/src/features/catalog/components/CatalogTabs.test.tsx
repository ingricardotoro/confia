import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { CatalogTabs, type CatalogTab } from "./CatalogTabs.tsx";

// States. AW57, AW58: the sub navigation rendered with props alone. Loading and error do not apply: the views
// are static and arrive translated. Empty: no tabs renders an empty list inside the landmark. Success: the
// tabs, one of them current.
const TABS: CatalogTab[] = [
  { id: "payment-concepts", label: "Conceptos de pago", href: "/catalog", current: false },
  { id: "price-lists", label: "Listas de precios", href: "/catalog/price-lists", current: true },
];

describe("CatalogTabs", () => {
  it("AW57: is a navigation landmark with one link per view and only the current one marked", () => {
    renderWithAdminI18n(<CatalogTabs tabs={TABS} onNavigate={vi.fn()} />);

    expect(screen.getByRole("navigation", { name: "Vistas del catálogo" })).toBeVisible();
    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual(["Conceptos de pago", "Listas de precios"]);
    expect(screen.getByRole("link", { name: "Listas de precios" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Conceptos de pago" })).not.toHaveAttribute("aria-current");
  });

  it("navigates on a plain click and leaves a modified click to the browser", () => {
    const onNavigate = vi.fn();
    renderWithAdminI18n(<CatalogTabs tabs={TABS} onNavigate={onNavigate} />);

    fireEvent.click(screen.getByRole("link", { name: "Conceptos de pago" }), { ctrlKey: true });
    expect(onNavigate).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("link", { name: "Conceptos de pago" }));
    expect(onNavigate).toHaveBeenCalledWith("/catalog");
  });

  it("is reachable and operable from the keyboard", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    renderWithAdminI18n(<CatalogTabs tabs={TABS} onNavigate={onNavigate} />);

    await user.tab();
    await user.keyboard("{Enter}");

    expect(onNavigate).toHaveBeenCalledWith("/catalog");
  });

  it("renders an empty list inside the landmark when there are no tabs", () => {
    renderWithAdminI18n(<CatalogTabs tabs={[]} onNavigate={vi.fn()} />);

    expect(screen.getByRole("navigation", { name: "Vistas del catálogo" })).toBeVisible();
    expect(screen.queryAllByRole("link")).toHaveLength(0);
  });
});

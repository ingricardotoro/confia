import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { OrganizationTabs, type OrganizationTab } from "./OrganizationTabs.tsx";

// States. AW57, AW58: the sub navigation rendered with props alone. Loading and error do not apply: the views
// are static and arrive translated. Empty: no tabs renders an empty list inside the landmark. Success: the
// tabs, one of them current.
const TABS: OrganizationTab[] = [
  { id: "academic-years", label: "Años lectivos", href: "/organization/academic-years", current: true },
  { id: "modalities", label: "Modalidades", href: "/organization/modalities", current: false },
];

describe("OrganizationTabs", () => {
  it("AW57: is a navigation landmark with one link per view and only the current one marked", () => {
    renderWithAdminI18n(<OrganizationTabs tabs={TABS} onNavigate={vi.fn()} />);

    expect(screen.getByRole("navigation", { name: "Vistas de la estructura académica" })).toBeVisible();
    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual(["Años lectivos", "Modalidades"]);
    expect(screen.getByRole("link", { name: "Años lectivos" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Modalidades" })).not.toHaveAttribute("aria-current");
  });

  it("navigates on a plain click and leaves a modified click to the browser", () => {
    const onNavigate = vi.fn();
    renderWithAdminI18n(<OrganizationTabs tabs={TABS} onNavigate={onNavigate} />);

    fireEvent.click(screen.getByRole("link", { name: "Modalidades" }), { ctrlKey: true });
    expect(onNavigate).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("link", { name: "Modalidades" }));
    expect(onNavigate).toHaveBeenCalledWith("/organization/modalities");
  });

  it("is reachable and operable from the keyboard", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    renderWithAdminI18n(<OrganizationTabs tabs={TABS} onNavigate={onNavigate} />);

    await user.tab();
    expect(screen.getByRole("link", { name: "Años lectivos" })).toHaveFocus();
    await user.tab();
    await user.keyboard("{Enter}");

    expect(onNavigate).toHaveBeenCalledWith("/organization/modalities");
  });

  it("renders an empty list inside the landmark when there are no tabs", () => {
    renderWithAdminI18n(<OrganizationTabs tabs={[]} onNavigate={vi.fn()} />);

    expect(screen.getByRole("navigation", { name: "Vistas de la estructura académica" })).toBeVisible();
    expect(screen.queryAllByRole("link")).toHaveLength(0);
  });
});

import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Breadcrumb } from "./Breadcrumb";

const ITEMS = [
  { id: "catalog", label: "Catálogo", href: "/catalog", onNavigate: vi.fn() },
  { id: "lists", label: "Listas de precios", href: "/catalog/price-lists", onNavigate: vi.fn() },
  { id: "list", label: "Lista 2027" },
];

describe("Breadcrumb", () => {
  it("is a named navigation landmark with an ordered list", () => {
    renderWithI18n(<Breadcrumb label="Ruta de navegación" items={ITEMS} />);

    expect(screen.getByRole("navigation", { name: "Ruta de navegación" })).toBeVisible();
    expect(screen.getAllByRole("listitem")).toHaveLength(3);
  });

  it("links the ancestors and marks the last item as the current page, without a link", () => {
    renderWithI18n(<Breadcrumb label="Ruta de navegación" items={ITEMS} />);

    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual(["Catálogo", "Listas de precios"]);
    expect(screen.getByText("Lista 2027")).toHaveAttribute("aria-current", "page");
    expect(screen.queryByRole("link", { name: "Lista 2027" })).not.toBeInTheDocument();
  });

  it("navigates on a plain click and leaves a modified click to the browser", () => {
    const onNavigate = vi.fn();
    renderWithI18n(
      <Breadcrumb
        label="Ruta"
        items={[
          { id: "a", label: "Inicio", href: "/", onNavigate },
          { id: "b", label: "Aquí" },
        ]}
      />,
    );

    fireEvent.click(screen.getByRole("link", { name: "Inicio" }), { ctrlKey: true });
    expect(onNavigate).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("link", { name: "Inicio" }));
    expect(onNavigate).toHaveBeenCalledWith("/");
  });

  it("shows a level with no address as plain text", () => {
    renderWithI18n(
      <Breadcrumb
        label="Ruta"
        items={[
          { id: "a", label: "Catálogo" },
          { id: "b", label: "Aquí" },
        ]}
      />,
    );

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("Catálogo")).not.toHaveAttribute("aria-current");
  });
});

import { render, screen } from "@testing-library/react";
import { createRef } from "react";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { PageHeader } from "./PageHeader";

describe("PageHeader", () => {
  it("renders the title as a level 1 heading by default, with the description", () => {
    renderWithI18n(<PageHeader title="Catálogo" description="Lo que la institución cobra." />);

    expect(screen.getByRole("heading", { level: 1, name: "Catálogo" })).toBeVisible();
    expect(screen.getByText("Lo que la institución cobra.")).toBeVisible();
  });

  it("renders a level 2 heading when the view sits below a page heading", () => {
    renderWithI18n(<PageHeader title="Listas de precios" headingLevel={2} />);

    expect(screen.getByRole("heading", { level: 2, name: "Listas de precios" })).toBeVisible();
    expect(screen.queryByRole("heading", { level: 1 })).not.toBeInTheDocument();
  });

  it("shows the breadcrumb, the state beside the title and the actions", () => {
    renderWithI18n(
      <PageHeader
        title="Lista 2027"
        breadcrumb={<nav aria-label="Ruta">Ruta</nav>}
        status={<span>Activa</span>}
        actions={<button type="button">Archivar</button>}
      />,
    );

    expect(screen.getByRole("navigation", { name: "Ruta" })).toBeVisible();
    expect(screen.getByText("Activa")).toBeVisible();
    expect(screen.getByRole("button", { name: "Archivar" })).toBeVisible();
  });

  it("makes the heading focusable by code only when a ref is given", () => {
    const ref = createRef<HTMLHeadingElement>();
    render(<PageHeader title="Catálogo" headingRef={ref} />);

    expect(ref.current).toHaveAttribute("tabindex", "-1");
    ref.current?.focus();
    expect(ref.current).toHaveFocus();
  });

  it("leaves the heading out of the tab order when no ref is given", () => {
    renderWithI18n(<PageHeader title="Catálogo" />);

    expect(screen.getByRole("heading", { name: "Catálogo" })).not.toHaveAttribute("tabindex");
  });
});

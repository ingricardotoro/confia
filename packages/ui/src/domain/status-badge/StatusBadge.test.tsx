import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { StatusBadge } from "./StatusBadge";
import { FINANCIAL_STATUSES, type FinancialStatus } from "./status-table";

// The expected texts are written out here, not read from the table the component consumes: a wrong
// label in the table must turn a test red instead of agreeing with itself.
const LABELS: Record<FinancialStatus, string> = {
  paid: "Pagado",
  pending: "Pendiente",
  partial: "Parcial",
  overdue: "Vencido",
  void: "Anulado",
  promised: "En promesa",
  refunded: "Reembolsado",
};

// The icon is decorative, so it has no role to be found by: it is reached from the badge that holds
// it. The badge puts the drawing inside a wrapper that is its first child.
const iconsOf = (badge: HTMLElement) => [...(badge.firstElementChild?.children ?? [])];

describe("StatusBadge", () => {
  it("DS52: the table lists exactly the seven financial states", () => {
    expect([...FINANCIAL_STATUSES].sort()).toEqual(Object.keys(LABELS).sort());
  });

  describe.each(FINANCIAL_STATUSES)("%s", (status) => {
    it("DS52, DS57: shows its own icon, the es-HN text and the color token of the state", () => {
      renderWithI18n(<StatusBadge status={status} />);
      const badge = screen.getByText(LABELS[status]);

      expect(iconsOf(badge)).toHaveLength(1);
      expect(badge.className).toContain(`--status-badge-${status}-fg`);
      expect(badge.className).toContain(`--status-badge-${status}-bg`);
      expect(badge.className).toContain(`--status-badge-${status}-border`);
    });

    it("DS53: with iconOnly, the text stays for assistive technology and as the title", () => {
      renderWithI18n(<StatusBadge status={status} iconOnly />);
      const badge = screen.getByTitle(LABELS[status]);

      expect(badge).toHaveAccessibleName(LABELS[status]);
      expect(screen.getByText(LABELS[status]).className).toContain("sr-only");
      expect(iconsOf(badge)).toHaveLength(1);
    });
  });

  it("DS52: no two states share an icon", () => {
    const shapes = FINANCIAL_STATUSES.map((status) => {
      const { unmount } = renderWithI18n(<StatusBadge status={status} />);
      const shape = iconsOf(screen.getByText(LABELS[status]))[0]?.getAttribute("class");
      unmount();
      return shape;
    });

    expect(shapes.every((shape) => shape !== undefined && shape !== null)).toBe(true);
    expect(new Set(shapes).size).toBe(FINANCIAL_STATUSES.length);
  });

  it("DS54: in normal mode the state is announced once and the icon is hidden at both layers", () => {
    renderWithI18n(<StatusBadge status="overdue" />);
    const badge = screen.getByText("Vencido");
    const [icon] = iconsOf(badge);

    expect(screen.getAllByText("Vencido")).toHaveLength(1);
    expect(badge).not.toHaveAttribute("title");
    expect(icon).toHaveAttribute("aria-hidden", "true");
    expect(icon?.parentElement).toHaveAttribute("aria-hidden", "true");
  });

  it("DS54: with iconOnly the state is still announced once", () => {
    renderWithI18n(<StatusBadge status="overdue" iconOnly />);

    expect(screen.getAllByText("Vencido")).toHaveLength(1);
    expect(screen.getByTitle("Vencido")).toBeInTheDocument();
  });

  it("DS55: an unknown state at run time shows the neutral text with an icon and no state color", () => {
    const unknown = "archived" as unknown as FinancialStatus;

    expect(() => renderWithI18n(<StatusBadge status={unknown} />)).not.toThrow();
    const badge = screen.getByText("Desconocido");

    expect(iconsOf(badge)).toHaveLength(1);
    expect(badge.className).not.toContain("--status-badge-");
    for (const label of Object.values(LABELS)) expect(screen.queryByText(label)).not.toBeInTheDocument();
  });

  it("DS55: an inherited property name is unknown too, not a state", () => {
    renderWithI18n(<StatusBadge status={"constructor" as unknown as FinancialStatus} />);

    expect(screen.getByText("Desconocido")).toBeInTheDocument();
  });

  it("DS56: the three variants look different from each other and the default is soft", () => {
    const classesOf = (variant?: "soft" | "solid" | "outline") => {
      const { unmount } = renderWithI18n(
        variant === undefined ? <StatusBadge status="paid" /> : <StatusBadge status="paid" variant={variant} />,
      );
      const classes = screen.getByText("Pagado").className;
      unmount();
      return classes;
    };

    expect(classesOf()).toBe(classesOf("soft"));
    expect(new Set([classesOf("soft"), classesOf("solid"), classesOf("outline")]).size).toBe(3);
  });

  it("keeps the caller's className and attributes", () => {
    renderWithI18n(<StatusBadge status="paid" className="ml-2" lang="es" />);

    expect(screen.getByText("Pagado")).toHaveClass("ml-2");
    expect(screen.getByText("Pagado")).toHaveAttribute("lang", "es");
  });
});

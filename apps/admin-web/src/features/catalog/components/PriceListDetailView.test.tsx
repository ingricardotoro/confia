import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { priceListDetail, priceListItem } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { PriceListDetailView, type PriceListDetailViewProps } from "./PriceListDetailView.tsx";

// States. AW57, AW58: the detail of a price list rendered with props alone. Loading: `state.kind = "loading"`, a skeleton
// with the shape of the page. Error: `state.kind = "error"`, a message with a retry. Empty: a list with no prices, with
// the way forward. Success: a list with two validities of one concept (one closed, one open) and one of another, and an
// archived list that takes no new price.
const CONCEPT = "00000000-0000-4000-8000-000000007001";
const OTHER = "00000000-0000-4000-8000-000000007002";

const closed = priceListItem({ id: "00000000-0000-4000-8000-0000000000f1", paymentConceptId: CONCEPT, price: { amount: "1500.00", currency: "HNL" }, validFrom: "2027-01-01", validTo: "2027-06-30" });
const open = priceListItem({ id: "00000000-0000-4000-8000-0000000000f2", paymentConceptId: CONCEPT, price: { amount: "1650.00", currency: "HNL" }, validFrom: "2027-07-01", validTo: null });
const usd = priceListItem({ id: "00000000-0000-4000-8000-0000000000f3", paymentConceptId: OTHER, price: { amount: "35.50", currency: "USD" }, validFrom: "2027-01-01", validTo: null });

function props(override: Partial<PriceListDetailViewProps> = {}): PriceListDetailViewProps {
  return {
    state: {
      kind: "ready",
      list: priceListDetail({ name: "2027 — Institución", items: [closed, open, usd] }),
      yearName: "Año 2027",
      concepts: [
        { paymentConceptId: CONCEPT, code: "COL", name: "Colegiatura", items: [open, closed] },
        { paymentConceptId: OTHER, code: "TRA", name: "Transporte", items: [usd] },
      ],
    },
    changingStatus: false,
    backHref: "/catalog/price-lists",
    onBack: vi.fn(),
    onAddPrice: vi.fn(),
    onToggleStatus: vi.fn(),
    ...override,
  };
}

describe("PriceListDetailView", () => {
  it("shows the data of the list and a table of validities for each concept, newest first", () => {
    renderWithAdminI18n(<PriceListDetailView {...props()} />);

    expect(screen.getByRole("heading", { level: 2, name: "2027 — Institución" })).toBeInTheDocument();
    const summary = within(screen.getByText("Año lectivo").closest("dl") as HTMLElement);
    expect(summary.getByText("Año 2027")).toBeInTheDocument();
    expect(summary.getByText("Toda la institución")).toBeInTheDocument();
    expect(summary.getByText("Activa")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 4, name: "COL — Colegiatura" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 4, name: "TRA — Transporte" })).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Vigencias de Colegiatura" });
    const [, newest, oldest] = within(table).getAllByRole("row");
    expect(within(newest as HTMLElement).getByText("Abierta")).toBeInTheDocument();
    expect(within(newest as HTMLElement).getByText("Sin fecha de fin")).toBeInTheDocument();
    expect(within(oldest as HTMLElement).getByText("Cerrada")).toBeInTheDocument();
  });

  it("shows each amount with its currency, as the money component writes the text the server sent, aligned to the end", () => {
    renderWithAdminI18n(<PriceListDetailView {...props()} />);

    const table = screen.getByRole("table", { name: "Vigencias de Colegiatura" });
    const [, newest, oldest] = within(table).getAllByRole("row");
    expect(within(newest as HTMLElement).getAllByText(/1,650\.00/).length).toBeGreaterThan(0);
    expect(within(oldest as HTMLElement).getAllByText(/1,500\.00/).length).toBeGreaterThan(0);
    expect(within(newest as HTMLElement).getByText("HNL")).toBeInTheDocument();
    expect(within(screen.getByRole("table", { name: "Vigencias de Transporte" })).getByText("USD")).toBeInTheDocument();
    const amount = within(newest as HTMLElement).getAllByText(/1,650\.00/)[0];
    expect(amount?.closest("td")?.className).toMatch(/text-end|text-right/);
    expect(amount?.closest(".money-amount")).not.toBeNull();
  });

  it("the loading state is a busy region with no data", () => {
    renderWithAdminI18n(<PriceListDetailView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByRole("region", { name: "Listas de precios" })).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Volver a las listas de precios" })).toBeInTheDocument();
  });

  it("the error state says what failed and retries", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<PriceListDetailView {...props({ state: { kind: "error", title: "No se pudo cargar la lista de precios", description: "Sin conexión.", onRetry } })} />);

    expect(screen.getByText("No se pudo cargar la lista de precios")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("the empty state of a list with no prices offers the first one", async () => {
    const user = userEvent.setup();
    const onAddPrice = vi.fn();
    renderWithAdminI18n(
      <PriceListDetailView {...props({ onAddPrice, state: { kind: "ready", list: priceListDetail(), yearName: "Año 2027", concepts: [] } })} />,
    );

    expect(screen.getByText("Esta lista todavía no tiene precios")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Agregar el primer precio" }));

    expect(onAddPrice).toHaveBeenCalledTimes(1);
  });

  it("the empty table of a concept offers the new price instead of a dead button", async () => {
    const user = userEvent.setup();
    const onAddPrice = vi.fn();
    renderWithAdminI18n(<PriceListDetailView {...props({ onAddPrice })} />);
    expect(screen.queryByRole("button", { name: "Agregar el primer precio" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Nuevo precio / nueva vigencia" }));
    expect(onAddPrice).toHaveBeenCalledTimes(1);
  });

  it("reports the new price, the archive and the way back", async () => {
    const user = userEvent.setup();
    const handlers = { onAddPrice: vi.fn(), onToggleStatus: vi.fn(), onBack: vi.fn() };
    renderWithAdminI18n(<PriceListDetailView {...props(handlers)} />);

    await user.click(screen.getByRole("button", { name: "Nuevo precio / nueva vigencia" }));
    await user.click(screen.getByRole("button", { name: "Archivar lista 2027 — Institución" }));
    fireEvent.click(screen.getByRole("link", { name: "Listas de precios" }), { metaKey: true });
    expect(handlers.onBack).not.toHaveBeenCalled();
    await user.click(screen.getByRole("link", { name: "Listas de precios" }));

    expect(handlers.onAddPrice).toHaveBeenCalledTimes(1);
    expect(handlers.onToggleStatus).toHaveBeenCalledTimes(1);
    expect(handlers.onBack).toHaveBeenCalledTimes(1);
  });

  it("an archived list says so, offers to reactivate it and takes no new price", () => {
    renderWithAdminI18n(
      <PriceListDetailView
        {...props({ state: { kind: "ready", list: priceListDetail({ name: "2026", status: "ARCHIVED", items: [open] }), yearName: "Año 2026", concepts: [{ paymentConceptId: CONCEPT, code: "COL", name: "Colegiatura", items: [open] }] } })}
      />,
    );

    expect(screen.getByText(/está archivada y no admite precios nuevos/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reactivar lista 2026" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Nuevo precio / nueva vigencia" })).toBeDisabled();
  });

  it("names a concept that was not loaded as not available, and a reference of a modality or grade list", () => {
    renderWithAdminI18n(
      <PriceListDetailView
        {...props({
          state: {
            kind: "ready",
            list: priceListDetail({ scope: "GRADE", scopeRefId: "00000000-0000-4000-8000-000000000c01", items: [open] }),
            yearName: "Año 2027",
            referenceName: "Primer grado",
            concepts: [{ paymentConceptId: CONCEPT, items: [open] }],
          },
        })}
      />,
    );

    expect(screen.getByRole("heading", { level: 4, name: "No disponible" })).toBeInTheDocument();
    expect(screen.getByText("Primer grado")).toBeInTheDocument();
  });

  it("warns, with a retry, when the names of the concepts, the year or the reference failed to load", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<PriceListDetailView {...props({ lookupFailure: { onRetry } })} />);

    expect(screen.getByText(/No se pudieron cargar los nombres de los conceptos/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("tells the result of the last action in its region and waits while the status is being saved", () => {
    renderWithAdminI18n(<PriceListDetailView {...props({ changingStatus: true, notice: { tone: "error", message: "El registro cambió.", traceId: "t-9" } })} />);

    expect(screen.getByText("El registro cambió.")).toBeInTheDocument();
    expect(screen.getByText("Identificador para soporte: t-9")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Archivar lista 2027 — Institución" })).toHaveAttribute("aria-busy", "true");
  });
});

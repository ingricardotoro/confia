import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Pagination } from "./Pagination";

describe("Pagination", () => {
  it("is a navigation landmark that says which rows and page are shown", () => {
    renderWithI18n(<Pagination page={1} size={50} total={230} onPageChange={vi.fn()} />);

    expect(screen.getByRole("navigation", { name: "Paginación" })).toBeVisible();
    expect(screen.getByText("Mostrando 51 a 100 de 230")).toBeVisible();
    expect(screen.getByText("Página 2 de 5")).toBeVisible();
  });

  it("takes its own accessible name when the screen has more than one pagination", () => {
    renderWithI18n(<Pagination page={0} size={10} total={30} label="Filas rechazadas" onPageChange={vi.fn()} />);

    expect(screen.getByRole("navigation", { name: "Filas rechazadas" })).toBeVisible();
    expect(screen.queryByRole("navigation", { name: "Paginación" })).not.toBeInTheDocument();
  });

  it("asks the server for the next and the previous page, zero-based", async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    renderWithI18n(<Pagination page={1} size={50} total={230} onPageChange={onPageChange} />);

    await user.click(screen.getByRole("button", { name: "Siguiente" }));
    expect(onPageChange).toHaveBeenLastCalledWith(2);
    await user.click(screen.getByRole("button", { name: "Anterior" }));
    expect(onPageChange).toHaveBeenLastCalledWith(0);
  });

  it("disables Anterior on the first page and Siguiente on the last, and clips the last range", () => {
    const { rerender } = renderWithI18n(<Pagination page={0} size={50} total={230} onPageChange={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Anterior" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Siguiente" })).toBeEnabled();

    rerender(<Pagination page={4} size={50} total={230} onPageChange={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Siguiente" })).toBeDisabled();
    expect(screen.getByText("Mostrando 201 a 230 de 230")).toBeVisible();
  });

  it("disables both buttons while a page is being fetched", () => {
    renderWithI18n(<Pagination page={1} size={50} total={230} onPageChange={vi.fn()} disabled />);

    expect(screen.getByRole("button", { name: "Anterior" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Siguiente" })).toBeDisabled();
  });

  it("renders nothing when there are no rows", () => {
    renderWithI18n(<Pagination page={0} size={50} total={0} onPageChange={vi.fn()} />);

    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
  });
});

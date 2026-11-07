import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithI18n } from "../../test/render";
import { ErrorState } from "./ErrorState";

describe("ErrorState", () => {
  it("DS28: announces the message in a live region", () => {
    renderWithI18n(
      <ErrorState
        title="No pudimos cargar los cargos"
        description="Revise su conexión e intente de nuevo."
        onRetry={vi.fn()}
      />,
    );

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("No pudimos cargar los cargos");
    expect(alert).toHaveTextContent("Revise su conexión e intente de nuevo.");
  });

  it("renders without a description", () => {
    renderWithI18n(<ErrorState title="No pudimos cargar los cargos" onRetry={vi.fn()} />);

    expect(screen.getByRole("alert")).toHaveTextContent("No pudimos cargar los cargos");
  });

  it("DS28: the retry is operable by keyboard and is called once per activation", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithI18n(<ErrorState title="No pudimos cargar los cargos" onRetry={onRetry} />);

    await user.tab();
    expect(screen.getByRole("button", { name: "Reintentar" })).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(onRetry).toHaveBeenCalledTimes(1);

    await user.keyboard(" ");
    expect(onRetry).toHaveBeenCalledTimes(2);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledTimes(3);
  });

  it("uses the common.retry entry of the catalog as the default label of the retry", () => {
    renderWithI18n(<ErrorState title="No pudimos cargar los cargos" onRetry={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();
  });

  it("lets the caller name the retry", () => {
    renderWithI18n(<ErrorState title="No pudimos cargar los cargos" retryLabel="Volver a cargar" onRetry={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Volver a cargar" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });

  it.each([["empty", ""], ["only spaces", "   "]])(
    "DS91: names the retry with the catalog label when the label is %s",
    (_case, retryLabel) => {
      renderWithI18n(<ErrorState title="No pudimos cargar los cargos" retryLabel={retryLabel} onRetry={vi.fn()} />);

      expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();
    },
  );

  it.each([["missing", undefined], ["an empty string", ""], ["only spaces", "   "]])(
    "DS92: renders no empty paragraph when the description is %s",
    (_case, description) => {
      renderWithI18n(
        <ErrorState title="No pudimos cargar los cargos" {...(description === undefined ? {} : { description })} onRetry={vi.fn()} />,
      );

      expect(screen.getAllByRole("paragraph")).toEqual([screen.getByText("No pudimos cargar los cargos")]);
    },
  );

  it("merges the class of the caller over its own", () => {
    const { container } = renderWithI18n(
      <ErrorState title="No pudimos cargar los cargos" className="mt-4" onRetry={vi.fn()} />,
    );

    expect(container.firstElementChild).toHaveClass("mt-4");
  });
});

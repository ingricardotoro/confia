import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { createUiI18n } from "../../i18n/create-ui-i18n";
import { renderWithI18n } from "../../test/render";
import { Spinner } from "./Spinner";

describe("Spinner", () => {
  it("is a status named by the common.loading catalog entry", () => {
    renderWithI18n(<Spinner />);

    expect(screen.getByRole("status", { name: "Cargando" })).toBeInTheDocument();
  });

  it("takes its name from the catalog and not from the component", () => {
    const i18n = createUiI18n();
    i18n.addResourceBundle("es-HN", "ui", { common: { loading: "Procesando" } }, true, true);

    renderWithI18n(<Spinner />, { i18n });

    expect(screen.getByRole("status", { name: "Procesando" })).toBeInTheDocument();
    expect(screen.queryByRole("status", { name: "Cargando" })).not.toBeInTheDocument();
  });

  it("names the action in progress when the caller passes a label", () => {
    renderWithI18n(<Spinner label="Registrando pago" />);

    expect(screen.getByRole("status", { name: "Registrando pago" })).toBeInTheDocument();
  });

  it.each(["sm", "md", "lg"] as const)("is still a named status at size %s", (size) => {
    renderWithI18n(<Spinner size={size} />);

    expect(screen.getByRole("status", { name: "Cargando" })).toBeInTheDocument();
  });

  it("exposes only the status to assistive technology, not the drawing inside it", () => {
    renderWithI18n(<Spinner />);

    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });
});

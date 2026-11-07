import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AccessPlaceholder } from "./AccessPlaceholder.tsx";

// admin-web-foundations-and-session, task 9.1 (AW57, AW58). The provisional screen of sign in, verification
// and enrollment: a heading and the arrival notice, and no field. The next change replaces it.
// States. Empty: no notice. Success: a notice. Loading and error: not applicable, it reads nothing.
describe("AccessPlaceholder", () => {
  it("AW57: with no notice it shows the heading and nothing else", () => {
    renderWithAdminI18n(<AccessPlaceholder title="Iniciar sesión" />);

    expect(screen.getByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("shows the arrival notice in a status region when there is one", () => {
    renderWithAdminI18n(<AccessPlaceholder title="Iniciar sesión" notice="Su sesión se cerró por seguridad." />);

    expect(screen.getByRole("status")).toHaveTextContent("Su sesión se cerró por seguridad.");
  });
});

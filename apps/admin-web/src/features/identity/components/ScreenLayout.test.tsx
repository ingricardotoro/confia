import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ScreenLayout } from "./ScreenLayout.tsx";

// admin-web-foundations-and-session, task 9.1 (SS32, AW57, AW58). The layout of every screen: main, one
// level 1 heading that takes the focus, and the document title.
// States. Loading, empty, error: not applicable, the layout holds whatever the screen puts in it and has
// no data. Success: the tests below.
describe("ScreenLayout", () => {
  it("SS32: renders main with one level 1 heading that has the focus, and sets the document title", async () => {
    renderWithAdminI18n(
      <ScreenLayout title="Una pantalla">
        <p>Contenido</p>
      </ScreenLayout>,
    );

    const heading = screen.getByRole("heading", { level: 1, name: "Una pantalla" });
    expect(screen.getByRole("main")).toContainElement(heading);
    expect(screen.getByText("Contenido")).toBeInTheDocument();
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    expect(document.title).toBe("Una pantalla");
  });

  it("AW57: renders with no children", () => {
    renderWithAdminI18n(<ScreenLayout title="Vacía" />);

    expect(screen.getAllByRole("heading")).toHaveLength(1);
  });
});

import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { PageContent } from "./PageContent.tsx";

// States. AW57, AW58: a page frame with props alone. Loading, empty and error do not apply: the frame
// shows no data; the page that holds it decides those. Success: the heading and the content.
describe("PageContent", () => {
  it("AW57: has one level 1 heading with the focus, sets the document title and shows its content", () => {
    renderWithAdminI18n(
      <PageContent title="Estructura académica">
        <p>Contenido</p>
      </PageContent>,
    );

    const heading = screen.getByRole("heading", { level: 1, name: "Estructura académica" });
    expect(heading).toHaveFocus();
    expect(document.title).toBe("Estructura académica");
    expect(screen.getByText("Contenido")).toBeVisible();
    expect(screen.queryByRole("main")).not.toBeInTheDocument();
  });
});

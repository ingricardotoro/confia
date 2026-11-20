import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { BackLink } from "./BackLink.tsx";

// States. AW57, AW58: a link with props alone. Loading, empty and error do not apply: it shows no data.
// Success: the link, which navigates without a reload on a plain click.
describe("BackLink", () => {
  it("keeps its address and navigates without a reload on a plain click", async () => {
    const user = userEvent.setup();
    const onBack = vi.fn();
    render(<BackLink href="/catalog/price-lists" label="Volver a las listas de precios" onBack={onBack} />);

    const link = screen.getByRole("link", { name: "Volver a las listas de precios" });
    expect(link).toHaveAttribute("href", "/catalog/price-lists");
    await user.click(link);

    expect(onBack).toHaveBeenCalledOnce();
  });

  it("leaves a modified click to the browser", async () => {
    const user = userEvent.setup();
    const onBack = vi.fn();
    render(<BackLink href="/x" label="Volver" onBack={onBack} />);

    await user.keyboard("{Control>}");
    await user.click(screen.getByRole("link", { name: "Volver" }));

    expect(onBack).not.toHaveBeenCalled();
  });
});

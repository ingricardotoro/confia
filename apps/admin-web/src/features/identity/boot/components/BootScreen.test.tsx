import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { BootScreen } from "./BootScreen.tsx";

// admin-web-foundations-and-session, task 9.1 (AW57, AW58). The presentation of the boot screen, rendered
// with props alone: no Query provider and no router.
// States. Loading: "restoring". Error: "failed". Success: not applicable, the gate shows the app instead
// and this screen is gone (SS36). Empty: not applicable, a session is being restored, not listed.
describe("BootScreen", () => {
  it("AW57: renders with props alone, and the restoring state has a heading, a message and no control", () => {
    renderWithAdminI18n(<BootScreen status="restoring" />);

    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
    expect(screen.getByText("Restaurando su sesión.")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("the failed state announces the message and hands the retry to the caller", () => {
    const onRetry = vi.fn();
    renderWithAdminI18n(<BootScreen status="failed" onRetry={onRetry} />);

    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo restaurar la sesión.");
    fireEvent.click(screen.getByRole("button"));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});

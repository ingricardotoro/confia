import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { SecretStep } from "./SecretStep.tsx";

// admin-web-staff-sign-in, task 7.4 (E10; SS77, SS78). The first step of the enrollment as a presentation.
// States. Success: the only one; the QR code, the key in groups, the copy control and Continue. Loading and error: not
// applicable, the container shows them while there is no secret, so this component is never rendered without one. Empty:
// not applicable (AW58), a step with no secret does not exist.
const SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
const URI = `otpauth://totp/CONFIA:ana%40colegio.edu.hn?secret=${SECRET}&issuer=CONFIA`;

function show(onContinue = () => undefined) {
  return renderWithAdminI18n(<SecretStep uri={URI} secret={SECRET} onContinue={onContinue} />);
}

describe("SecretStep", () => {
  it("AW57: has the QR code, the key in groups of four, the copy control and Continue", () => {
    show();

    expect(screen.getByRole("heading", { level: 2, name: "Escanee el código QR" })).toBeInTheDocument();
    expect(screen.getByText("Escanee el código con su aplicación de autenticación o escriba la clave manualmente.")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Código QR para su aplicación de autenticación" })).toBeInTheDocument();
    expect(screen.getByText("Clave para escribir manualmente")).toBeInTheDocument();
    expect(screen.getByText("JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copiar clave" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Continuar" })).toBeInTheDocument();
  });

  it("takes the focus on its heading when it appears", async () => {
    show();

    await waitFor(() => {
      expect(screen.getByRole("heading", { level: 2, name: "Escanee el código QR" })).toHaveFocus();
    });
  });

  it("SS78: copies the secret without spaces and says so", async () => {
    const user = userEvent.setup();
    const write = vi.spyOn(navigator.clipboard, "writeText");
    show();

    await user.click(screen.getByRole("button", { name: "Copiar clave" }));

    expect(write).toHaveBeenCalledExactlyOnceWith(SECRET);
    expect(await screen.findByText("Clave copiada.")).toBeInTheDocument();
  });

  it("calls onContinue when Continue is pressed", async () => {
    const user = userEvent.setup();
    const onContinue = vi.fn();
    show(onContinue);

    await user.click(screen.getByRole("button", { name: "Continuar" }));

    expect(onContinue).toHaveBeenCalledOnce();
  });
});

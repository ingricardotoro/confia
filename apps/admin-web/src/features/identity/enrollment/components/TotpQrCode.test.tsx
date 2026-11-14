import { screen } from "@testing-library/react";
import { QRCodeSVG } from "qrcode.react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { TotpQrCode } from "./TotpQrCode.tsx";

// admin-web-staff-sign-in, task 7.2 (E11; SS83). The QR code as a presentation.
// States. Success: the only one; the code is drawn from a string it is given and reads nothing. Loading, empty and error: not
// applicable (AW58), the container shows its own loading and error before this component exists.
const LABEL = "Código QR para su aplicación de autenticación";
const URI = "otpauth://totp/CONFIA:ana%40colegio.edu.hn?secret=ABCDEFGHIJKLMNOPQRSTUVWXYZ234567&issuer=CONFIA";

describe("TotpQrCode", () => {
  it("AW57, SS83: is an image named by the catalog and no element of it carries a style attribute", () => {
    renderWithAdminI18n(<TotpQrCode uri={URI} />);

    const image = screen.getByRole("img", { name: LABEL });
    // `outerHTML` covers the whole subtree, so a `style` on any element of the drawing is caught.
    expect(image.outerHTML).not.toMatch(/\sstyle=/);
  });

  it("SS83: draws the given string at level M with a four module quiet zone, and another string draws another code", () => {
    renderWithAdminI18n(<TotpQrCode uri={URI} />);
    const drawn = screen.getByRole("img", { name: LABEL }).outerHTML;

    const expected = renderToStaticMarkup(<QRCodeSVG value={URI} level="M" marginSize={4} role="img" title={LABEL} />);
    expect(drawn).toBe(expected);
    expect(drawn).not.toBe(renderToStaticMarkup(<QRCodeSVG value={`${URI}0`} level="M" marginSize={4} role="img" title={LABEL} />));
  });
});

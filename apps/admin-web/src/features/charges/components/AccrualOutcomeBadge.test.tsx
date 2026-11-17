import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AccrualOutcomeBadge } from "./AccrualOutcomeBadge.tsx";

// States. AW57, AW58: the outcome of one item rendered with props alone. Success is the only state it has (an item has one
// of four outcomes and the badge always says which in text); loading, empty and error do not apply, because it reads nothing.
// An item that ended in error is told by its error code in readable text, and the dot is a second carrier, never the only one.
describe("AccrualOutcomeBadge", () => {
  it.each([
    ["preview", "CREATED", "Se creará", "filled"],
    ["preview", "ALREADY_ACCRUED", "Ya generado", "hollow"],
    ["preview", "ALREADY_ACCRUED_OTHER_PLAN", "Generado por otro plan", "hollow"],
    ["run", "CREATED", "Creado", "filled"],
  ] as const)("says the %s outcome %s in text", (mode, outcome, text, dot) => {
    renderWithAdminI18n(<AccrualOutcomeBadge mode={mode} outcome={outcome} />);

    expect(screen.getByText(text).firstElementChild).toHaveAttribute("data-dot", dot);
  });

  it.each([
    ["NO_PRICE", "Sin precio vigente"],
    ["AMBIGUOUS_PRICE", "Precio ambiguo"],
    ["TAX_RATE_MISSING", "Sin tasa de impuesto"],
    ["POSTING_FAILED", "No se pudo asentar"],
    ["SOMETHING_NEW", "Error no reconocido"],
  ])("tells the error %s in readable text and never as a raw code", (code, text) => {
    renderWithAdminI18n(<AccrualOutcomeBadge mode="run" outcome="ERROR" errorCode={code} />);

    expect(screen.getByText(text)).toBeInTheDocument();
    expect(screen.queryByText(code)).not.toBeInTheDocument();
  });
});

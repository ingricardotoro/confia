import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AccrualRunStatusBadge } from "./AccrualRunStatusBadge.tsx";

// States. AW57, AW58: the state of a run rendered with props alone. Success is the only state it has (a run has one of six
// states and the badge always says which in text); loading, empty and error do not apply, because it lists nothing and reads
// nothing. The dot is a second carrier of the state, never the only one.
describe("AccrualRunStatusBadge", () => {
  it.each([
    ["QUEUED", "En cola", "hollow"],
    ["RUNNING", "En curso", "filled"],
    ["COMPLETED", "Completada", "filled"],
    ["COMPLETED_WITH_ERRORS", "Completada con errores", "filled"],
    ["REJECTED", "Rechazada", "hollow"],
    ["FAILED", "Fallida", "filled"],
  ] as const)("says %s in text and draws its dot (%s)", (status, text, dot) => {
    renderWithAdminI18n(<AccrualRunStatusBadge status={status} />);

    expect(screen.getByText(text).firstElementChild).toHaveAttribute("data-dot", dot);
  });
});

import { screen } from "@testing-library/react";
import { createRef } from "react";
import { describe, expect, it } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ProblemAlert } from "./ProblemAlert.tsx";

// admin-web-staff-sign-in, task 2.2 (AW54, SS42, SS50). The destructive alert of a failure, with the trace id and the
// control that copies it when there is one.
// States. Success: the message alone, and the message with a trace id. Loading, empty and error: not applicable, it
// receives an already translated message and reads nothing.
describe("ProblemAlert", () => {
  it("AW57, SS42: is a destructive alert with the message", () => {
    renderWithAdminI18n(<ProblemAlert message="No se pudo conectar con el servidor." />);

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("No se pudo conectar con el servidor.");
    expect(alert).toHaveTextContent("Error");
  });

  it("AW54: with a trace id shows it and a control with an accessible name that copies it", () => {
    renderWithAdminI18n(<ProblemAlert message="Ocurrió un error inesperado." traceId="01J8Z4K2QF9P0X" />);

    expect(screen.getByRole("alert")).toHaveTextContent("01J8Z4K2QF9P0X");
    expect(screen.getByRole("button", { name: "Copiar identificador" })).toBeInTheDocument();
  });

  it("AW54: without a trace id shows neither the identifier nor the control", () => {
    renderWithAdminI18n(<ProblemAlert message="Ocurrió un error inesperado." />);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("hands its reference to the alert, so a form can move the focus there", () => {
    const ref = createRef<HTMLDivElement>();
    renderWithAdminI18n(<ProblemAlert ref={ref} message="x" />);

    expect(ref.current).toBe(screen.getByRole("alert"));
  });
});

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { problem, statementLine, statementRecord, statementSection, student, studentPage, studentStatement } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCharges } from "../../test/render-charges.tsx";

// The statement over the real route, the real query layer and the real transport: nothing is asked until a student is chosen, the
// student, the period, the currency and the page live in the address and travel to the server as the query it pages, filters and
// orders by itself, and each answer of the load has its state.
installMswServer();

const ANA = student({ id: "00000000-0000-4000-8000-000000005001", firstName: "Ana", lastName: "Aguilar", studentCode: "E-000001" });
const STATEMENT = `/api/v1/charges/students/${ANA.id}/statement`;
const TIMEOUT = { timeout: 10_000 };
const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === STATEMENT);
const queryOf = (index = 0) => new URLSearchParams(gets()[index]?.search);
const usd = (amount: string) => ({ amount, currency: "USD" });

describe("the statement of account", () => {
  it("asks for nothing and guides the person while no student is chosen", async () => {
    renderCharges("/charges/statement");

    expect(await screen.findByText("Elija un estudiante", {}, TIMEOUT)).toBeInTheDocument();
    expect(requests()).toHaveLength(0);
  });

  it("opens a deep link on the student, names them, reads the default period and shows the balances and the lines the server sent", async () => {
    route("get", STATEMENT, statementRecord(studentStatement({ studentId: ANA.id })));
    renderCharges(`/charges/statement?studentId=${ANA.id}`);

    expect(await screen.findByText("Aguilar, Ana (E-000001)", {}, TIMEOUT)).toBeInTheDocument();
    const row = await screen.findByRole("row", { name: /Colegiatura/ }, TIMEOUT);
    expect(within(row).getAllByText("1,500.00 lempiras hondureños").length).toBeGreaterThan(0);
    expect(screen.getByText("Saldo inicial")).toBeInTheDocument();
    expect(gets()).toHaveLength(1);
    const query = queryOf();
    expect(query.get("page")).toBe("0");
    expect(query.get("size")).toBe("25");
    expect(query.has("from")).toBe(false);
    expect(query.has("to")).toBe(false);
    expect(query.has("currency")).toBe(false);
    expect(screen.queryByLabelText("Moneda")).not.toBeInTheDocument();
  });

  it("sends the period and the page of the address, the page counted from zero", async () => {
    route(
      "get",
      STATEMENT,
      statementRecord(studentStatement({ studentId: ANA.id, sections: [statementSection({ totalLines: 60, page: 1 })] })),
    );
    renderCharges(`/charges/statement?studentId=${ANA.id}&from=2027-01-01&to=2027-03-31&page=2`);

    await screen.findByRole("row", { name: /Colegiatura/ }, TIMEOUT);
    const query = queryOf();
    expect(query.get("from")).toBe("2027-01-01");
    expect(query.get("to")).toBe("2027-03-31");
    expect(query.get("page")).toBe("1");
  });

  it("pages through the server: the next page is a new request and the address keeps it", async () => {
    const user = userEvent.setup();
    route(
      "get",
      STATEMENT,
      statementRecord(studentStatement({ studentId: ANA.id, sections: [statementSection({ totalLines: 60, page: 0 })] })),
      statementRecord(studentStatement({ studentId: ANA.id, sections: [statementSection({ totalLines: 60, page: 1, lines: [statementLine({ lineNumber: 26 })] })] })),
    );
    const view = renderCharges(`/charges/statement?studentId=${ANA.id}`);

    await user.click(await screen.findByRole("button", { name: /Siguiente/ }, TIMEOUT));

    await waitFor(() => {
      expect(gets()).toHaveLength(2);
    }, TIMEOUT);
    expect(queryOf(1).get("page")).toBe("1");
    expect(view.router.state.location.searchStr).toContain("page=2");
  });

  it("does not ask the server for a period that ends before it starts, and tells it on the final date", async () => {
    renderCharges(`/charges/statement?studentId=${ANA.id}&from=2027-02-01&to=2027-01-01`);

    expect(await screen.findByText("Corrija el período", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getAllByText("La fecha final debe ser igual o posterior a la inicial.").length).toBeGreaterThan(0);
    expect(gets()).toHaveLength(0);
  });

  it("explains a student that has no movements", async () => {
    route("get", STATEMENT, statementRecord(studentStatement({ studentId: ANA.id, sections: [] })));
    renderCharges(`/charges/statement?studentId=${ANA.id}`);

    expect(await screen.findByText("Este estudiante todavía no tiene movimientos", {}, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the currency selector for a student with two currencies, narrows to one and keeps the selector", async () => {
    const user = userEvent.setup();
    const both = studentStatement({
      studentId: ANA.id,
      sections: [
        statementSection(),
        statementSection({ currency: "USD", openingBalance: usd("0.00"), closingBalance: usd("-40.00"), lines: [statementLine({ debit: usd("0.00"), credit: usd("40.00"), balance: usd("-40.00") })] }),
      ],
    });
    route(
      "get",
      STATEMENT,
      statementRecord(both),
      statementRecord(studentStatement({ studentId: ANA.id, sections: [both.sections[1] ?? statementSection()] })),
    );
    renderCharges(`/charges/statement?studentId=${ANA.id}`);

    const selector = await screen.findByLabelText("Moneda", {}, TIMEOUT);
    expect(await screen.findAllByRole("region", { name: /\((HNL|USD)\)/ }, TIMEOUT)).toHaveLength(2);
    expect(screen.getAllByText("Saldo a favor").length).toBeGreaterThan(0);

    await user.selectOptions(selector, "USD");

    await waitFor(() => {
      expect(gets()).toHaveLength(2);
    }, TIMEOUT);
    expect(queryOf(1).get("currency")).toBe("USD");
    expect(await screen.findAllByRole("region", { name: /\(USD\)/ }, TIMEOUT)).toHaveLength(1);
    expect(screen.getByLabelText("Moneda")).toHaveValue("USD");
  });

  it("tells a student of another institution as not found, with no retry", async () => {
    route("get", STATEMENT, problem("resource-not-found", 404, { traceId: "t-404" }));
    renderCharges(`/charges/statement?studentId=${ANA.id}`);

    expect(await screen.findByText("No encontramos a este estudiante", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });

  it("tells a missing permission, with no retry", async () => {
    route("get", STATEMENT, problem("forbidden", 403));
    renderCharges(`/charges/statement?studentId=${ANA.id}`);

    expect(await screen.findByText("No tiene permiso para ver este estado de cuenta", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });

  it("tells a failed load with the trace id and recovers when the person retries", async () => {
    const user = userEvent.setup();
    route("get", STATEMENT, problem("internal-error", 500, { traceId: "trace-500" }), statementRecord(studentStatement({ studentId: ANA.id })));
    renderCharges(`/charges/statement?studentId=${ANA.id}`);

    expect(await screen.findByText("No se pudo cargar el estado de cuenta.", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/trace-500/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /Colegiatura/ }, TIMEOUT)).toBeInTheDocument();
    expect(gets()).toHaveLength(2);
  });

  it("searches a student on the server, chooses one and puts them in the address", async () => {
    const user = userEvent.setup();
    route("get", "/api/v1/students", studentPage([ANA]));
    route("get", STATEMENT, statementRecord(studentStatement({ studentId: ANA.id })));
    const view = renderCharges("/charges/statement");

    await user.type(await screen.findByRole("combobox", { name: "Estudiante" }, TIMEOUT), "Agu");
    await user.click(await screen.findByRole("option", { name: "Aguilar, Ana (E-000001)" }, TIMEOUT));

    expect(await screen.findByRole("row", { name: /Colegiatura/ }, TIMEOUT)).toBeInTheDocument();
    expect(view.router.state.location.searchStr).toContain(`studentId=${ANA.id}`);
    const search = new URLSearchParams(requests().find((sent) => sent.path === "/api/v1/students")?.search);
    expect(search.get("q")).toBe("Agu");
  });
});

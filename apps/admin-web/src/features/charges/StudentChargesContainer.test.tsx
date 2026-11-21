import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { chargeVoid, problem, studentCharge, studentChargePage } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCharges } from "../../test/render-charges.tsx";

// The charges of a student over the real route, the real query layer and the real transport: the page the server serves (paged, filtered
// and ordered there, with all of it in the address), the status the server derives, the void with its Idempotency-Key and its refusals, and
// the states of the load.
installMswServer();

const STUDENT_ID = "00000000-0000-4000-8000-000000005001";
const LIST = `/api/v1/charges/students/${STUDENT_ID}/charges`;
const TIMEOUT = { timeout: 10_000 };
const ACTIVE = studentCharge({ id: "00000000-0000-4000-8000-0000000d0001", periodStart: "2027-02-01", periodLabel: "2027-02", conceptName: "Mensualidad" });
const VOIDED = studentCharge({
  id: "00000000-0000-4000-8000-0000000d0002",
  periodStart: "2027-01-01",
  periodLabel: "2027-01",
  conceptName: "Matrícula",
  status: "VOIDED",
  total: { amount: "2500.00", currency: "HNL" },
  voided: {
    reason: "Cargo generado por error en el período equivocado",
    reversalTransactionId: "00000000-0000-4000-8000-0000000e0001",
    voidId: "00000000-0000-4000-8000-0000000e0002",
    voidedAt: "2026-11-10T15:00:00Z",
  },
});
const REASON = "Cargo generado dos veces por error del proceso";

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === LIST);
const lastQuery = () => new URLSearchParams(gets().at(-1)?.search ?? "");
const voidPosts = () => requests().filter((sent) => sent.method === "POST" && sent.path === `/api/v1/charges/${ACTIVE.id}/void`);

async function openList(rows: unknown[] = [ACTIVE, VOIDED], path = `/charges/student-charges?studentId=${STUDENT_ID}`) {
  route("get", LIST, studentChargePage(rows));
  const rendered = renderCharges(path);
  const table = await screen.findByRole("table", { name: "Cargos del estudiante" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

async function openDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Anular el cargo Mensualidad, 2027-02" }));
  return screen.findByRole("dialog", { name: "Anular cargo" }, TIMEOUT);
}

async function typeReason(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement, text = REASON) {
  await user.click(within(dialog).getByRole("textbox", { name: /Motivo de la anulación/ }));
  await user.paste(text);
}

describe("the list of charges of a student", () => {
  it("shows each charge with its amounts as the server gave them, its status, and the void action only on the charge in force", async () => {
    await openList();

    const active = within(screen.getByRole("row", { name: /2027-02/ }));
    expect(active.getByText("Vigente")).toBeInTheDocument();
    expect(active.getAllByText(/1,150\.00/).length).toBeGreaterThan(0);
    expect(active.getByRole("button", { name: "Anular el cargo Mensualidad, 2027-02" })).toBeInTheDocument();
    const voided = within(screen.getByRole("row", { name: /2027-01/ }));
    expect(voided.getByText("Anulado")).toBeInTheDocument();
    expect(voided.getByText(/Anulado el/)).toBeInTheDocument();
    expect(voided.queryByRole("button", { name: /Anular el cargo/ })).not.toBeInTheDocument();
    // The free text of the reason of a void is never shown in the list.
    expect(screen.queryByText(/Cargo generado por error/)).not.toBeInTheDocument();
    expect(lastQuery().get("page")).toBe("0");
    expect(lastQuery().get("size")).toBe("25");
    expect(lastQuery().has("status")).toBe(false);
  });

  it("filters by status on the server and keeps the filter in the address", async () => {
    const user = userEvent.setup();
    await openList();

    await user.selectOptions(screen.getByRole("combobox", { name: "Estado" }), "Anulado");

    await waitFor(() => {
      expect(lastQuery().get("status")).toBe("VOIDED");
    }, TIMEOUT);
    expect(lastQuery().get("page")).toBe("0");
  });

  it("reads the status, the period, the order and the page from the address", async () => {
    await openList([ACTIVE], `/charges/student-charges?studentId=${STUDENT_ID}&status=ACTIVE&periodFrom=2027-01-01&periodTo=2027-06-01&sort=total:asc&page=2`);

    const query = lastQuery();
    expect(query.get("status")).toBe("ACTIVE");
    expect(query.get("periodFrom")).toBe("2027-01-01");
    expect(query.get("periodTo")).toBe("2027-06-01");
    expect(query.get("sort")).toBe("total,asc");
    expect(query.get("page")).toBe("1");
  });

  it("asks the server for another order when a sortable header is pressed", async () => {
    const user = userEvent.setup();
    await openList();

    await user.click(screen.getByRole("button", { name: /Total/ }));

    await waitFor(() => {
      expect(lastQuery().get("sort")).toBe("total,asc");
    }, TIMEOUT);
  });

  it("asks for nothing and says how to start when no student is chosen", async () => {
    renderCharges("/charges/student-charges");

    expect(await screen.findByText("Elija un estudiante", {}, TIMEOUT)).toBeInTheDocument();
    expect(gets()).toHaveLength(0);
  });

  it("does not ask the server when the period ends before it starts, and says so", async () => {
    renderCharges(`/charges/student-charges?studentId=${STUDENT_ID}&periodFrom=2027-03-01&periodTo=2027-01-01`);

    expect(await screen.findByText("Corrija el período", {}, TIMEOUT)).toBeInTheDocument();
    expect(gets()).toHaveLength(0);
  });

  it("shows the empty state with its way forward when the student has no charges", async () => {
    route("get", LIST, studentChargePage([]));
    renderCharges(`/charges/student-charges?studentId=${STUDENT_ID}`);

    expect(await screen.findByText("Este estudiante no tiene cargos", {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Ver estado de cuenta" }).length).toBeGreaterThan(0);
  });

  it("shows the error state with the trace id, and Retry loads the list", async () => {
    const user = userEvent.setup();
    route("get", LIST, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), studentChargePage([ACTIVE]));
    renderCharges(`/charges/student-charges?studentId=${STUDENT_ID}`);

    expect(await screen.findByText("No se pudieron cargar los cargos", {}, { timeout: 20_000 })).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("row", { name: /2027-02/ }, TIMEOUT)).toBeInTheDocument();
  }, 30_000);

  it.each([
    [404, "resource-not-found", "No encontramos a este estudiante"],
    [403, "forbidden", "No tiene permiso para ver estos cargos"],
  ] as const)("tells the %i answer in plain words and offers no retry", async (status, code, title) => {
    route("get", LIST, problem(code, status));
    renderCharges(`/charges/student-charges?studentId=${STUDENT_ID}`);

    expect(await screen.findByText(title, {}, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });
});

describe("voiding a charge", () => {
  it("asks for a reason of 20 to 500 characters with a live count, and sends nothing while it does not hold", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openDialog(user);

    expect(within(dialog).getByText("0 de 500 caracteres")).toBeInTheDocument();
    expect(within(dialog).getByText(/La anulación no se puede deshacer/)).toBeInTheDocument();
    expect(within(dialog).getByText(/No escriba nombres, números de documento ni datos de salud/)).toBeInTheDocument();
    await typeReason(user, dialog, "muy corto");
    expect(within(dialog).getByText("9 de 500 caracteres")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Anular cargo" }));

    expect(await within(dialog).findByText("El motivo debe tener al menos 20 caracteres.")).toBeInTheDocument();
    expect(within(dialog).getByRole("textbox", { name: /Motivo de la anulación/ })).toHaveFocus();
    expect(voidPosts()).toHaveLength(0);
  });

  it("counts by code points, like the server: an emoji counts once", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openDialog(user);

    await typeReason(user, dialog, "😀😀😀");

    expect(within(dialog).getByText("3 de 500 caracteres")).toBeInTheDocument();
  });

  it("refuses a line break in the reason, which the server refuses too", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openDialog(user);

    await typeReason(user, dialog, `${REASON}\nsegunda línea`);
    await user.click(within(dialog).getByRole("button", { name: "Anular cargo" }));

    expect(await within(dialog).findByText("El motivo no puede tener saltos de línea ni caracteres de control.")).toBeInTheDocument();
    expect(voidPosts()).toHaveLength(0);
  });

  it("voids the charge with an Idempotency-Key, reads the list again and tells the person", async () => {
    const user = userEvent.setup();
    route("get", LIST, studentChargePage([ACTIVE, VOIDED]), studentChargePage([{ ...ACTIVE, status: "VOIDED" }, VOIDED]));
    renderCharges(`/charges/student-charges?studentId=${STUDENT_ID}`);
    await screen.findByRole("row", { name: /2027-02/ }, TIMEOUT);
    route("post", `/api/v1/charges/${ACTIVE.id}/void`, chargeVoid({ chargeId: ACTIVE.id, reason: REASON }));
    const dialog = await openDialog(user);

    await typeReason(user, dialog, `  ${REASON}  `);
    await user.click(within(dialog).getByRole("button", { name: "Anular cargo" }));

    expect(await screen.findByText("Se anuló el cargo Mensualidad, 2027-02. Su reverso ya está en el libro mayor.", {}, TIMEOUT)).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    }, TIMEOUT);
    const sent = voidPosts();
    expect(sent).toHaveLength(1);
    expect(sent[0]?.headers.get("Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/);
    // The reason travels without the spaces at its borders.
    expect(JSON.parse(sent[0]?.body ?? "{}")).toEqual({ reason: REASON });
    await waitFor(() => {
      expect(gets().length).toBeGreaterThanOrEqual(2);
      expect(within(screen.getByRole("row", { name: /2027-02/ })).getByText("Anulado")).toBeInTheDocument();
    }, TIMEOUT);
  });

  it.each([
    ["charge-already-voided", 409, /Este cargo ya estaba anulado/],
    ["charge-not-voidable", 409, /El libro mayor no permite anular este cargo/],
    ["charge-has-payments", 409, /ya tiene pagos aplicados y no se puede anular/],
    ["idempotency-payload-mismatch", 422, /Esta confirmación ya se había usado para otra solicitud distinta/],
    ["forbidden", 403, /No tiene permiso para realizar esta acción/],
    ["resource-not-found", 404, /El registro ya no existe o no está disponible/],
  ] as const)("tells the %s refusal inside the dialog, with the trace id, and keeps the dialog open", async (code, status, text) => {
    const user = userEvent.setup();
    await openList();
    route("post", `/api/v1/charges/${ACTIVE.id}/void`, problem(code, status, { traceId: "01J8Z4K2QF9P0X" }));
    const dialog = await openDialog(user);

    await typeReason(user, dialog);
    await user.click(within(dialog).getByRole("button", { name: "Anular cargo" }));

    const alert = await within(dialog).findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent(text);
    expect(alert).toHaveTextContent("Identificador para soporte: 01J8Z4K2QF9P0X");
    expect(alert).not.toHaveTextContent("no se anulará dos veces");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("sends the same Idempotency-Key again when the answer never arrived, and a new one when the reason changed", async () => {
    const user = userEvent.setup();
    await openList();
    route("post", `/api/v1/charges/${ACTIVE.id}/void`, "network-error", "network-error", chargeVoid({ chargeId: ACTIVE.id }));
    const dialog = await openDialog(user);

    await typeReason(user, dialog);
    await user.click(within(dialog).getByRole("button", { name: "Anular cargo" }));
    expect(await within(dialog).findByText(/no se anulará dos veces/, {}, TIMEOUT)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Anular cargo" }));
    await waitFor(() => {
      expect(voidPosts()).toHaveLength(2);
    }, TIMEOUT);
    await within(dialog).findByText(/no se anulará dos veces/, {}, TIMEOUT);
    await user.type(within(dialog).getByRole("textbox", { name: /Motivo de la anulación/ }), " y revisado");
    await user.click(within(dialog).getByRole("button", { name: "Anular cargo" }));

    await waitFor(() => {
      expect(voidPosts()).toHaveLength(3);
    }, TIMEOUT);
    const keys = voidPosts().map((sent) => sent.headers.get("Idempotency-Key"));
    expect(keys[0]).toBe(keys[1]);
    expect(keys[2]).not.toBe(keys[0]);
  }, 30_000);

  it("makes a new Idempotency-Key for a new confirmation after the dialog was closed", async () => {
    const user = userEvent.setup();
    await openList();
    route("post", `/api/v1/charges/${ACTIVE.id}/void`, problem("charge-not-voidable", 409));

    for (let turn = 0; turn < 2; turn += 1) {
      const dialog = await openDialog(user);
      await typeReason(user, dialog);
      await user.click(within(dialog).getByRole("button", { name: "Anular cargo" }));
      await within(dialog).findByRole("alert", {}, TIMEOUT);
      await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));
      await waitFor(() => {
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      }, TIMEOUT);
    }

    const keys = voidPosts().map((sent) => sent.headers.get("Idempotency-Key"));
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it("closes with Cancel and sends nothing", async () => {
    const user = userEvent.setup();
    await openList();
    const dialog = await openDialog(user);

    await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    }, TIMEOUT);
    expect(voidPosts()).toHaveLength(0);
  });
});

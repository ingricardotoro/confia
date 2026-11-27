import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { PERMISSION_CODES } from "../../shared/permissions/permission-codes.ts";
import { assignment, assignmentRecord, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderScholarships } from "../../test/render-scholarships.tsx";

// One agreement assignment over the real route, the real query layer and the real transport: what the server says of it, the decision of a pending
// assignment (approve and reject) and the revocation of an approved one, each with its Idempotency-Key, its reason of 20 to 500 characters and its
// refusals (the person who requested it cannot decide it, told in its own words), and every state of the load.
installMswServer();

const ID = "00000000-0000-4000-8000-0000000c0001";
const ASSIGNMENT = `/api/v1/scholarships/discount-assignments/${ID}`;
const PATH = `/scholarships/discount-assignments/${ID}`;
const HEADING = "Asignación del estudiante EST-0001";
const TIMEOUT = { timeout: 10_000 };
const REASON = "El estudiante ya no cumple las condiciones del convenio";
const DECISION = { id: "00000000-0000-4000-8000-0000000d0001", kind: "approved", decidedAt: "2026-11-20T15:00:00Z", decidedBy: "00000000-0000-4000-8000-0000000000a2" } as const;
const pending = (override = {}) => assignment({ id: ID, status: "pending", ...override });
const approved = (override = {}) => assignment({ id: ID, status: "approved", decision: DECISION, ...override });
const without = (code: string) => PERMISSION_CODES.filter((candidate) => candidate !== code);

const posts = (suffix: string) => requests().filter((sent) => sent.method === "POST" && sent.path === `${ASSIGNMENT}/${suffix}`);
const keyOf = (sent: { headers: Headers } | undefined) => sent?.headers.get("Idempotency-Key") ?? "";

async function open(first = pending(), next: ReturnType<typeof pending>[] = [], permissions?: readonly string[]) {
  route("get", ASSIGNMENT, assignmentRecord(first), ...next.map((row) => assignmentRecord(row)));
  const rendered = renderScholarships(PATH, permissions === undefined ? {} : { permissions });
  await screen.findByRole("heading", { level: 2, name: HEADING }, TIMEOUT);
  return rendered;
}

describe("a pending assignment", () => {
  it("shows its data, says it changed nothing yet, and offers to approve and reject it", async () => {
    await open();

    expect(screen.getByText(/Esta asignación está pendiente de aprobación y todavía no cambia ningún cargo/)).toBeInTheDocument();
    expect(screen.getByText("CONV20 · Convenio con la empresa")).toBeInTheDocument();
    expect(screen.getByText(/Del 1 ene 2027 al 31 dic 2027/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Aprobar" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Rechazar" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Revocar" })).not.toBeInTheDocument();
  });

  it("approves with an explicit dialog, one request with its Idempotency-Key, and shows the assignment the server then reads", async () => {
    const user = userEvent.setup();
    await open(pending(), [approved()]);
    route("post", `${ASSIGNMENT}/approve`, assignmentRecord(approved()));
    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar la asignación" }, TIMEOUT);
    expect(within(dialog).getByText("EST-0001")).toBeInTheDocument();
    await user.dblClick(within(dialog).getByRole("button", { name: "Aprobar asignación" }));

    expect(await screen.findByText("La asignación quedó aprobada. Se aplicará a los cargos que se generen dentro de su vigencia.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(posts("approve")).toHaveLength(1);
    expect(keyOf(posts("approve")[0])).not.toBe("");
    expect(await screen.findByRole("button", { name: "Revocar" }, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Aprobar" })).not.toBeInTheDocument();
  });

  it.each([
    ["self-approval-forbidden", 403, /Quien solicitó la asignación no puede aprobarla ni rechazarla/],
    ["invalid-discount-assignment-state", 409, /La asignación ya no está en el estado que se mostraba/],
    ["discount-assignment-overlap", 409, /ya tiene aprobada una asignación de este descuento con fechas que se cruzan/],
    ["inactive-reference", 409, /Uno de los datos relacionados está inactivo/],
    ["forbidden", 403, /No tiene permiso para realizar esta acción/],
  ] as const)("tells the %s refusal of an approval inside the dialog and keeps it open", async (code, status, text) => {
    const user = userEvent.setup();
    await open();
    route("post", `${ASSIGNMENT}/approve`, problem(code, status, { traceId: "01J8Z4K2QF9P0X" }));
    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar la asignación" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Aprobar asignación" }));

    expect(await within(dialog).findByText(text, undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Aprobar la asignación" })).toBeInTheDocument();
  });

  it("sends the same Idempotency-Key again when the answer of an approval never arrived", async () => {
    const user = userEvent.setup();
    await open();
    route("post", `${ASSIGNMENT}/approve`, "network-error", assignmentRecord(approved()));
    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar la asignación" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Aprobar asignación" }));
    expect(await within(dialog).findByText(/No se pudo conectar con el servidor/, undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Aprobar asignación" }));

    await screen.findByText(/La asignación quedó aprobada/, undefined, TIMEOUT);
    expect(posts("approve")).toHaveLength(2);
    expect(keyOf(posts("approve")[0])).toBe(keyOf(posts("approve")[1]));
  });

  it("sends a new Idempotency-Key for a new attempt after a definite refusal", async () => {
    const user = userEvent.setup();
    await open();
    route("post", `${ASSIGNMENT}/approve`, problem("self-approval-forbidden", 403), assignmentRecord(approved()));
    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar la asignación" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Aprobar asignación" }));
    expect(await within(dialog).findByText(/Quien solicitó la asignación no puede aprobarla/, undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Aprobar asignación" }));

    await screen.findByText(/La asignación quedó aprobada/, undefined, TIMEOUT);
    expect(posts("approve")).toHaveLength(2);
    expect(keyOf(posts("approve")[0])).not.toBe(keyOf(posts("approve")[1]));
  });

  it("refuses a reason that is too short with nothing sent, and rejects with the reason and its Idempotency-Key", async () => {
    const user = userEvent.setup();
    const rejected = assignment({ id: ID, status: "rejected", decision: { ...DECISION, kind: "rejected", reason: REASON } });
    await open(pending(), [rejected]);
    route("post", `${ASSIGNMENT}/reject`, assignmentRecord(rejected));
    await user.click(screen.getByRole("button", { name: "Rechazar" }));
    const dialog = await screen.findByRole("dialog", { name: "Rechazar la asignación" }, TIMEOUT);
    const reason = within(dialog).getByRole("textbox", { name: /Motivo del rechazo/ });
    await user.click(reason);
    await user.paste("Muy corto");
    await user.click(within(dialog).getByRole("button", { name: "Rechazar asignación" }));

    expect((await within(dialog).findAllByText("Escriba de 20 a 500 caracteres.")).length).toBeGreaterThan(0);
    expect(posts("reject")).toHaveLength(0);

    await user.clear(reason);
    await user.click(reason);
    await user.paste(REASON);
    await user.click(within(dialog).getByRole("button", { name: "Rechazar asignación" }));

    expect(await screen.findByText("La asignación quedó rechazada. Ningún cargo cambió.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(posts("reject")).toHaveLength(1);
    expect(JSON.parse(posts("reject")[0]?.body ?? "{}")).toEqual({ reason: REASON });
    expect(keyOf(posts("reject")[0])).not.toBe("");
    expect(await screen.findByText(REASON, undefined, TIMEOUT)).toBeInTheDocument();
  });
});

describe("the terms an assignment is approved on (H2)", () => {
  it("shows the benefit and the version of the discount the assignment was requested for, in the detail and in the approval dialog", async () => {
    const user = userEvent.setup();
    await open(pending({ benefitType: "FIXED_AMOUNT", percent: null, fixedAmount: { amount: "250.00", currency: "HNL" }, discountVersion: 3 }));

    expect(screen.getByText("Beneficio solicitado")).toBeInTheDocument();
    expect(screen.getByText("L 250.00")).toBeInTheDocument();
    expect(screen.getByText("Versión del descuento")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar la asignación" }, TIMEOUT);
    expect(within(dialog).getByText("Beneficio solicitado")).toBeInTheDocument();
    expect(within(dialog).getByText("L 250.00")).toBeInTheDocument();
  });

  it("says that an assignment from before the terms were kept cannot be approved and has to be requested again", async () => {
    await open(pending({ benefitType: null, percent: null, discountVersion: null }));

    expect(screen.getByText(/se solicitó antes de que se guardaran los términos del descuento, por lo que no se puede aprobar/)).toBeInTheDocument();
  });

  it("tells in the dialog that the discount changed since the request, keeps it open, and reads the assignment again", async () => {
    const user = userEvent.setup();
    await open();
    route("post", `${ASSIGNMENT}/approve`, problem("discount-terms-changed", 409, { traceId: "01J8Z4K2QF9P0X" }));
    const before = requests().filter((sent) => sent.method === "GET" && sent.path === ASSIGNMENT).length;
    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar la asignación" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Aprobar asignación" }));

    expect(await within(dialog).findByText(/Los términos del descuento cambiaron después de solicitar esta asignación.*solicite la asignación otra vez/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await waitFor(() => {
      expect(requests().filter((sent) => sent.method === "GET" && sent.path === ASSIGNMENT).length).toBeGreaterThan(before);
    }, TIMEOUT);
  });
});

describe("an approved assignment", () => {
  it("offers to revoke it, says that only the charges to come are affected, and sends the reason with the Idempotency-Key", async () => {
    const user = userEvent.setup();
    const revoked = assignment({ id: ID, status: "revoked", decision: DECISION, revocation: { ...DECISION, id: "00000000-0000-4000-8000-0000000d0002", kind: "revoked", reason: REASON } });
    await open(approved(), [revoked]);
    route("post", `${ASSIGNMENT}/revoke`, assignmentRecord(revoked));

    expect(screen.queryByRole("button", { name: "Aprobar" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Revocar" }));
    const dialog = await screen.findByRole("dialog", { name: "Revocar la asignación" }, TIMEOUT);
    expect(within(dialog).getByText(/solo afecta los cargos que se generen desde ahora/)).toBeInTheDocument();
    const reason = within(dialog).getByRole("textbox", { name: /Motivo de la revocación/ });
    await user.click(reason);
    await user.paste(REASON);
    await user.dblClick(within(dialog).getByRole("button", { name: "Revocar asignación" }));

    expect(await screen.findByText("La asignación quedó revocada. Solo afecta los cargos que se generen desde ahora.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(posts("revoke")).toHaveLength(1);
    expect(JSON.parse(posts("revoke")[0]?.body ?? "{}")).toEqual({ reason: REASON });
    expect(keyOf(posts("revoke")[0])).not.toBe("");
    expect(await screen.findByText(/Esta asignación fue revocada/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Revocar" })).not.toBeInTheDocument();
  });

  it("refuses a reason of 19 characters and sends nothing, and limits the control to the 500 the server takes", async () => {
    const user = userEvent.setup();
    await open(approved());
    await user.click(screen.getByRole("button", { name: "Revocar" }));
    const dialog = await screen.findByRole("dialog", { name: "Revocar la asignación" }, TIMEOUT);
    const reason = within(dialog).getByRole("textbox", { name: /Motivo de la revocación/ });
    await user.click(reason);
    await user.paste("a".repeat(19));
    await user.click(within(dialog).getByRole("button", { name: "Revocar asignación" }));

    expect((await within(dialog).findAllByText("Escriba de 20 a 500 caracteres.")).length).toBeGreaterThan(0);
    expect(posts("revoke")).toHaveLength(0);
    expect((reason as HTMLTextAreaElement).maxLength).toBe(500);
  });

  it("tells that the server refuses a revocation of an assignment that is no longer approved, and reads it again", async () => {
    const user = userEvent.setup();
    await open(approved(), [assignment({ id: ID, status: "revoked", decision: DECISION })]);
    route("post", `${ASSIGNMENT}/revoke`, problem("invalid-discount-assignment-state", 409));
    await user.click(screen.getByRole("button", { name: "Revocar" }));
    const dialog = await screen.findByRole("dialog", { name: "Revocar la asignación" }, TIMEOUT);
    const reason = within(dialog).getByRole("textbox", { name: /Motivo de la revocación/ });
    await user.click(reason);
    await user.paste(REASON);
    await user.click(within(dialog).getByRole("button", { name: "Revocar asignación" }));

    expect(await within(dialog).findByText(/La asignación ya no está en el estado que se mostraba/, undefined, TIMEOUT)).toBeInTheDocument();
    const reads = requests().filter((sent) => sent.method === "GET" && sent.path === ASSIGNMENT);
    expect(reads.length).toBeGreaterThan(1);
  });
});

describe("the decisions without the permission", () => {
  it("offers neither approve nor reject to a session without scholarships:approve", async () => {
    await open(pending(), [], without("scholarships:approve"));

    expect(screen.getByText(/pendiente de aprobación/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Aprobar" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Rechazar" })).not.toBeInTheDocument();
  });

  it("offers no revocation to a session without scholarships:void", async () => {
    await open(approved(), [], without("scholarships:void"));

    expect(screen.queryByRole("button", { name: "Revocar" })).not.toBeInTheDocument();
  });
});

describe("the states of the load of an assignment", () => {
  it("says in words that an assignment that is not there was not found, with the way back", async () => {
    route("get", ASSIGNMENT, problem("resource-not-found", 404));
    renderScholarships(PATH);

    expect(await screen.findByText("No se encontró la asignación", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Volver a las asignaciones" })).toHaveAttribute("href", "/scholarships/discount-assignments");
  });

  it("says that the session has no access when the server answers 403", async () => {
    route("get", ASSIGNMENT, problem("forbidden", 403));
    renderScholarships(PATH);

    expect(await screen.findByText("No tiene acceso a esta asignación", undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the error state with the trace and loads the assignment on retry", async () => {
    const user = userEvent.setup();
    route("get", ASSIGNMENT, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), assignmentRecord(pending()));
    renderScholarships(PATH);

    expect(await screen.findByText("No se pudo cargar la asignación", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("heading", { level: 2, name: HEADING }, TIMEOUT)).toBeInTheDocument();
  });
});

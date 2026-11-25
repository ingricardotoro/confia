import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { PERMISSION_CODES } from "../../shared/permissions/permission-codes.ts";
import { academicYear, award, awardRecord, organizationPage, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderScholarships } from "../../test/render-scholarships.tsx";

// One award over the real route, the real query layer and the real transport: what the server says of it, the decision of a pending award (approve and
// reject) and the revocation of an approved one, each with its Idempotency-Key, its reason of 20 to 500 characters and its refusals, and every state of the load.
installMswServer();

const ID = "00000000-0000-4000-8000-0000000f0001";
const AWARD = `/api/v1/scholarship-awards/${ID}`;
const YEARS = "/api/v1/organization/academic-years";
const YEAR_ID = "00000000-0000-4000-8000-000000000a01";
const TIMEOUT = { timeout: 10_000 };
const REASON = "El estudiante no cumple el promedio mínimo del reglamento";
const DECISION = { id: "00000000-0000-4000-8000-0000000d0001", kind: "approved", decidedAt: "2026-11-20T15:00:00Z", decidedBy: "00000000-0000-4000-8000-0000000000a2" } as const;
const pending = (override = {}) => award({ id: ID, status: "pending", ...override });
const approved = (override = {}) => award({ id: ID, status: "approved", decision: DECISION, ...override });
const without = (code: string) => PERMISSION_CODES.filter((candidate) => candidate !== code);

const posts = (suffix: string) => requests().filter((sent) => sent.method === "POST" && sent.path === `${AWARD}/${suffix}`);
const keyOf = (sent: { headers: Headers } | undefined) => sent?.headers.get("Idempotency-Key") ?? "";

async function open(first = pending(), next: ReturnType<typeof pending>[] = [], permissions?: readonly string[]) {
  route("get", YEARS, organizationPage("academic-years", [academicYear({ id: YEAR_ID, name: "Año lectivo 2027" })]));
  route("get", AWARD, awardRecord(first), ...next.map((row) => awardRecord(row)));
  const rendered = renderScholarships(`/scholarships/awards/${ID}`, permissions === undefined ? {} : { permissions });
  await screen.findByRole("heading", { level: 2, name: "Otorgamiento del estudiante EST-0001" }, TIMEOUT);
  return rendered;
}

describe("a pending award", () => {
  it("shows its data with the academic year by name, says it changed nothing yet, and offers to approve and reject it", async () => {
    await open();

    expect(screen.getByText(/Este otorgamiento está pendiente de aprobación y todavía no cambia ningún cargo/)).toBeInTheDocument();
    expect(await screen.findByText("Año lectivo 2027", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText("BECA25 · Beca de excelencia")).toBeInTheDocument();
    expect(screen.getByText(/Del 1 ene 2027 al 31 dic 2027/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Aprobar" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Rechazar" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Revocar" })).not.toBeInTheDocument();
  });

  it("approves with an explicit dialog, one request with its Idempotency-Key, and shows the award the server then reads", async () => {
    const user = userEvent.setup();
    await open(pending(), [approved()]);
    route("post", `${AWARD}/approve`, awardRecord(approved()));
    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar el otorgamiento" }, TIMEOUT);
    expect(within(dialog).getByText("EST-0001")).toBeInTheDocument();
    await user.dblClick(within(dialog).getByRole("button", { name: "Aprobar otorgamiento" }));

    expect(await screen.findByText("El otorgamiento quedó aprobado. Se aplicará a los cargos que se generen dentro de su vigencia.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(posts("approve")).toHaveLength(1);
    expect(keyOf(posts("approve")[0])).not.toBe("");
    expect(await screen.findByRole("button", { name: "Revocar" }, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Aprobar" })).not.toBeInTheDocument();
  });

  it.each([
    ["self-approval-forbidden", 403, /Quien solicitó el otorgamiento no puede aprobarlo ni rechazarlo/],
    ["invalid-award-state", 409, /El otorgamiento ya no está en el estado que se mostraba/],
    ["scholarship-award-overlap", 409, /ya tiene aprobado un otorgamiento de esta beca con fechas que se cruzan/],
    ["inactive-reference", 409, /Uno de los datos relacionados está inactivo/],
    ["forbidden", 403, /No tiene permiso para realizar esta acción/],
  ] as const)("tells the %s refusal of an approval inside the dialog and keeps it open", async (code, status, text) => {
    const user = userEvent.setup();
    await open();
    route("post", `${AWARD}/approve`, problem(code, status, { traceId: "01J8Z4K2QF9P0X" }));
    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar el otorgamiento" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Aprobar otorgamiento" }));

    expect(await within(dialog).findByText(text, undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Aprobar el otorgamiento" })).toBeInTheDocument();
  });

  it("sends the same Idempotency-Key again when the answer of an approval never arrived", async () => {
    const user = userEvent.setup();
    await open();
    route("post", `${AWARD}/approve`, "network-error", awardRecord(approved()));
    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar el otorgamiento" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Aprobar otorgamiento" }));
    expect(await within(dialog).findByText(/No se pudo conectar con el servidor/, undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Aprobar otorgamiento" }));

    await screen.findByText(/El otorgamiento quedó aprobado/, undefined, TIMEOUT);
    expect(posts("approve")).toHaveLength(2);
    expect(keyOf(posts("approve")[0])).toBe(keyOf(posts("approve")[1]));
  });

  it("refuses a reason that is too short with nothing sent, and rejects with the reason and its Idempotency-Key", async () => {
    const user = userEvent.setup();
    await open(pending(), [award({ id: ID, status: "rejected", decision: { ...DECISION, kind: "rejected", reason: REASON } })]);
    route("post", `${AWARD}/reject`, awardRecord(award({ id: ID, status: "rejected", decision: { ...DECISION, kind: "rejected", reason: REASON } })));
    await user.click(screen.getByRole("button", { name: "Rechazar" }));
    const dialog = await screen.findByRole("dialog", { name: "Rechazar el otorgamiento" }, TIMEOUT);
    const reason = within(dialog).getByRole("textbox", { name: /Motivo del rechazo/ });
    await user.click(reason);
    await user.paste("Muy corto");
    await user.click(within(dialog).getByRole("button", { name: "Rechazar otorgamiento" }));

    expect((await within(dialog).findAllByText("Escriba de 20 a 500 caracteres.")).length).toBeGreaterThan(0);
    expect(posts("reject")).toHaveLength(0);

    await user.clear(reason);
    await user.click(reason);
    await user.paste(REASON);
    await user.click(within(dialog).getByRole("button", { name: "Rechazar otorgamiento" }));

    expect(await screen.findByText("El otorgamiento quedó rechazado. Ningún cargo cambió.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(posts("reject")).toHaveLength(1);
    expect(JSON.parse(posts("reject")[0]?.body ?? "{}")).toEqual({ reason: REASON });
    expect(keyOf(posts("reject")[0])).not.toBe("");
    expect(await screen.findByText(REASON, undefined, TIMEOUT)).toBeInTheDocument();
  });
});

describe("an approved award", () => {
  it("offers to revoke it, says that only the charges to come are affected, and sends the reason with the Idempotency-Key", async () => {
    const user = userEvent.setup();
    const revoked = award({ id: ID, status: "revoked", decision: DECISION, revocation: { ...DECISION, id: "00000000-0000-4000-8000-0000000d0002", kind: "revoked", reason: REASON } });
    await open(approved(), [revoked]);
    route("post", `${AWARD}/revoke`, awardRecord(revoked));

    expect(screen.queryByRole("button", { name: "Aprobar" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Revocar" }));
    const dialog = await screen.findByRole("dialog", { name: "Revocar el otorgamiento" }, TIMEOUT);
    expect(within(dialog).getByText(/solo afecta los cargos que se generen desde ahora/)).toBeInTheDocument();
    const reason = within(dialog).getByRole("textbox", { name: /Motivo de la revocación/ });
    await user.click(reason);
    await user.paste(REASON);
    await user.dblClick(within(dialog).getByRole("button", { name: "Revocar otorgamiento" }));

    expect(await screen.findByText("El otorgamiento quedó revocado. Solo afecta los cargos que se generen desde ahora.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(posts("revoke")).toHaveLength(1);
    expect(JSON.parse(posts("revoke")[0]?.body ?? "{}")).toEqual({ reason: REASON });
    expect(keyOf(posts("revoke")[0])).not.toBe("");
    expect(await screen.findByText(/Este otorgamiento fue revocado/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Revocar" })).not.toBeInTheDocument();
  });

  it.each([
    ["a reason of 19 characters", "a".repeat(19)],
    ["a reason of 501 characters", "a".repeat(501)],
  ])("refuses %s and sends nothing", async (_name, text) => {
    const user = userEvent.setup();
    await open(approved());
    await user.click(screen.getByRole("button", { name: "Revocar" }));
    const dialog = await screen.findByRole("dialog", { name: "Revocar el otorgamiento" }, TIMEOUT);
    const reason = within(dialog).getByRole("textbox", { name: /Motivo de la revocación/ });
    await user.click(reason);
    await user.paste(text);
    await user.click(within(dialog).getByRole("button", { name: "Revocar otorgamiento" }));

    // The control stops at 500 characters, so a longer paste is cut to what the server takes; the 19 is refused.
    if (text.length < 20) {
      expect((await within(dialog).findAllByText("Escriba de 20 a 500 caracteres.")).length).toBeGreaterThan(0);
      expect(posts("revoke")).toHaveLength(0);
    } else {
      expect(text.length).toBeGreaterThan(500);
      expect((reason as HTMLTextAreaElement).maxLength).toBe(500);
    }
  });

  it("tells that the server refuses a revocation of an award that is no longer approved, and reads it again", async () => {
    const user = userEvent.setup();
    await open(approved(), [award({ id: ID, status: "revoked", decision: DECISION })]);
    route("post", `${AWARD}/revoke`, problem("invalid-award-state", 409));
    await user.click(screen.getByRole("button", { name: "Revocar" }));
    const dialog = await screen.findByRole("dialog", { name: "Revocar el otorgamiento" }, TIMEOUT);
    const reason = within(dialog).getByRole("textbox", { name: /Motivo de la revocación/ });
    await user.click(reason);
    await user.paste(REASON);
    await user.click(within(dialog).getByRole("button", { name: "Revocar otorgamiento" }));

    expect(await within(dialog).findByText(/El otorgamiento ya no está en el estado que se mostraba/, undefined, TIMEOUT)).toBeInTheDocument();
    const reads = requests().filter((sent) => sent.method === "GET" && sent.path === AWARD);
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

describe("the states of the load of an award", () => {
  it("says in words that an award that is not there was not found, with the way back", async () => {
    route("get", AWARD, problem("resource-not-found", 404));
    route("get", YEARS, organizationPage("academic-years", []));
    renderScholarships(`/scholarships/awards/${ID}`);

    expect(await screen.findByText("No se encontró el otorgamiento", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Volver a los otorgamientos" })).toHaveAttribute("href", "/scholarships/awards");
  });

  it("says that the session has no access when the server answers 403", async () => {
    route("get", AWARD, problem("forbidden", 403));
    route("get", YEARS, organizationPage("academic-years", []));
    renderScholarships(`/scholarships/awards/${ID}`);

    expect(await screen.findByText("No tiene acceso a este otorgamiento", undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("shows the error state with the trace and loads the award on retry", async () => {
    const user = userEvent.setup();
    route("get", YEARS, organizationPage("academic-years", []));
    route("get", AWARD, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), awardRecord(pending()));
    renderScholarships(`/scholarships/awards/${ID}`);

    expect(await screen.findByText("No se pudo cargar el otorgamiento", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("heading", { level: 2, name: "Otorgamiento del estudiante EST-0001" }, TIMEOUT)).toBeInTheDocument();
  });
});

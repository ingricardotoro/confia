import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { discount, discountPage, discountProposal, discountRecord, paymentConcept, paymentConceptPage, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderScholarships } from "../../test/render-scholarships.tsx";
import { PERMISSION_CODES } from "../../shared/permissions/permission-codes.ts";

// S7: the approval of a campaign discount over the real route, the real query layer and the real transport. A campaign discount is born pending and widening or
// reactivating one leaves a proposal; another person approves or rejects it, with the version they read, a reason of 20 to 500 characters for a rejection and
// an Idempotency-Key that is kept while the outcome is unknown. The person who created it or proposed the change is not offered the decision.
installMswServer();

const DISCOUNTS = "/api/v1/scholarships/discounts";
const CONCEPTS = "/api/v1/catalog/payment-concepts";
const TIMEOUT = { timeout: 10_000 };
const MENS = paymentConcept({ id: "00000000-0000-4000-8000-000000007001", code: "MENS", name: "Mensualidad" });
const ID = "00000000-0000-4000-8000-0000000e0301";
const ID_2 = "00000000-0000-4000-8000-0000000e0302";
const ME = "00000000-0000-4000-8000-0000000000a1";
const OTHER = "00000000-0000-4000-8000-0000000000a2";
const REASON = "No corresponde a la campaña aprobada por la dirección";
const without = (code: string) => PERMISSION_CODES.filter((candidate) => candidate !== code);

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === DISCOUNTS);
const posts = (path: string) => requests().filter((sent) => sent.method === "POST" && sent.path === path);
const decisions = (suffix: "approve" | "reject") => posts(`${DISCOUNTS}/${ID}/${suffix}`);
const keyOf = (sent: { headers: Headers } | undefined) => sent?.headers.get("Idempotency-Key") ?? "";

const waiting = (override = {}) => discount({ id: ID, name: "Campaña nueva", approvalStatus: "pending", createdBy: ME, version: 0, ...override });
const proposed = (override = {}) =>
  discount({ id: ID, name: "Campaña nueva", percent: "10.00", pendingChange: discountProposal({ percent: "15.00", requestedBy: ME }), createdBy: OTHER, version: 2, ...override });

async function openList(rows: unknown[], options: { permissions?: readonly string[]; accountId?: string } = {}) {
  route("get", DISCOUNTS, discountPage(rows));
  route("get", CONCEPTS, paymentConceptPage([MENS]));
  const rendered = renderScholarships("/scholarships/discounts", {
    ...(options.permissions === undefined ? {} : { permissions: options.permissions }),
    ...(options.accountId === undefined ? {} : { accountId: options.accountId }),
  });
  const table = await screen.findByRole("table", { name: "Descuentos de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return rendered;
}

describe("what a discount waits for", () => {
  it("tells in words that a new campaign discount is pending and that nothing applies until another person approves it", async () => {
    const user = userEvent.setup();
    await openList([]);
    route("post", DISCOUNTS, discountRecord(discount({ code: "CAMP10", name: "Campaña de matrícula", approvalStatus: "pending", createdBy: ME }), 201));
    await user.click(screen.getAllByRole("button", { name: "Crear descuento" })[0] as HTMLElement);
    const dialog = await screen.findByRole("dialog", { name: "Crear un descuento" }, TIMEOUT);
    await user.type(within(dialog).getByLabelText(/^Código/), "CAMP10");
    await user.type(within(dialog).getByLabelText(/^Nombre/), "Campaña de matrícula");
    await user.selectOptions(within(dialog).getByLabelText(/^Clase/), "campaign");
    await user.selectOptions(within(dialog).getByLabelText(/^Tipo de beneficio/), "PERCENTAGE");
    await user.type(within(dialog).getByLabelText(/^Porcentaje/), "10");
    await user.type(within(dialog).getByLabelText(/^Vigente desde/), "2027-01-01");
    await user.type(within(dialog).getByLabelText(/^Vigente hasta/), "2027-12-31");
    await user.click(await within(dialog).findByRole("checkbox", { name: "MENS · Mensualidad" }, TIMEOUT));
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText(/Se creó «Campaña de matrícula». Queda pendiente de aprobación y no se aplica a ningún cargo/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByText("Se creó el registro «Campaña de matrícula».")).not.toBeInTheDocument();
  });

  it("tells that widening a discount left a proposal and that the terms in force keep applying", async () => {
    const user = userEvent.setup();
    await openList([discount({ id: ID, name: "Campaña de matrícula", version: 1 })]);
    route("put", `${DISCOUNTS}/${ID}`, discountRecord(discount({ id: ID, name: "Campaña de matrícula", version: 1, pendingChange: discountProposal({ percent: "15.00" }) })));
    await user.click(screen.getByRole("button", { name: "Editar Campaña de matrícula" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar el descuento" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await screen.findByText(/Se envió la propuesta sobre «Campaña de matrícula». Los términos vigentes siguen aplicándose/, undefined, TIMEOUT)).toBeInTheDocument();
  });

  it("tells that reactivating a campaign discount is only a proposal, instead of saying it was activated", async () => {
    const user = userEvent.setup();
    await openList([discount({ id: ID, name: "Campaña de matrícula", active: false })]);
    route("post", `${DISCOUNTS}/${ID}/reactivate`, discountRecord(discount({ id: ID, name: "Campaña de matrícula", active: false, pendingChange: discountProposal({ reactivate: true }) })));

    await user.click(screen.getByRole("button", { name: "Activar Campaña de matrícula" }));

    expect(await screen.findByText(/Se envió la propuesta sobre «Campaña de matrícula»/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByText("Se activó «Campaña de matrícula».")).not.toBeInTheDocument();
  });

  it("shows the pending, proposed and rejected states, and offers no edit or reactivation on them", async () => {
    await openList([
      waiting(),
      proposed({ id: ID_2, name: "Campaña con propuesta" }),
      discount({ id: "00000000-0000-4000-8000-0000000e0303", name: "Campaña rechazada", approvalStatus: "rejected", active: false }),
    ]);

    expect(within(screen.getByRole("row", { name: /Campaña nueva/ })).getByText("Pendiente de aprobación")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Campaña con propuesta/ })).getByText("Propuesta pendiente")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Campaña rechazada/ })).getByText("Rechazado")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Editar/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Activar Campaña rechazada" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Desactivar Campaña nueva" })).toBeInTheDocument();
  });

  it("tells that a discount that waits for approval cannot be edited when the server says so", async () => {
    const user = userEvent.setup();
    await openList([discount({ id: ID, name: "Campaña de matrícula" })]);
    route("put", `${DISCOUNTS}/${ID}`, problem("discount-pending-approval", 409));
    await user.click(screen.getByRole("button", { name: "Editar Campaña de matrícula" }));
    const dialog = await screen.findByRole("dialog", { name: "Editar el descuento" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(await within(dialog).findByText(/espera la aprobación de otra persona .* no se puede editar ni reactivar/, undefined, TIMEOUT)).toBeInTheDocument();
  });
});

describe("the decision on a campaign discount", () => {
  it("approves a pending discount with the version it read and one Idempotency-Key, restating its terms first", async () => {
    const user = userEvent.setup();
    await openList([waiting({ version: 4 })], { accountId: OTHER });
    route("get", DISCOUNTS, discountPage([discount({ id: ID, name: "Campaña nueva", version: 5 })]));
    route("post", `${DISCOUNTS}/${ID}/approve`, discountRecord(discount({ id: ID, name: "Campaña nueva", version: 5 })));
    await user.click(screen.getByRole("button", { name: "Aprobar Campaña nueva" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar el descuento" }, TIMEOUT);
    expect(within(dialog).getByText("10.00 %")).toBeInTheDocument();
    await user.dblClick(within(dialog).getByRole("button", { name: "Aprobar" }));

    expect(await screen.findByText(/«Campaña nueva» quedó aprobado/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(decisions("approve")).toHaveLength(1);
    expect(JSON.parse(decisions("approve")[0]?.body ?? "{}")).toEqual({ version: 4 });
    expect(keyOf(decisions("approve")[0])).not.toBe("");
    await waitFor(() => {
      expect(within(screen.getByRole("row", { name: /Campaña nueva/ })).queryByText("Pendiente de aprobación")).not.toBeInTheDocument();
    }, TIMEOUT);
  });

  it("shows the terms in force beside the proposed ones and approves the proposal", async () => {
    const user = userEvent.setup();
    await openList([proposed({ version: 2 })], { accountId: OTHER });
    route("post", `${DISCOUNTS}/${ID}/approve`, discountRecord(discount({ id: ID, name: "Campaña nueva", percent: "15.00", version: 3 })));
    await user.click(screen.getByRole("button", { name: "Aprobar Campaña nueva" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar la propuesta" }, TIMEOUT);

    expect(within(within(dialog).getByRole("region", { name: /Términos vigentes/ })).getByText("10.00 %")).toBeInTheDocument();
    expect(within(within(dialog).getByRole("region", { name: "Términos propuestos" })).getByText("15.00 %")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Aprobar" }));

    expect(await screen.findByText(/La propuesta sobre «Campaña nueva» quedó aprobada/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(JSON.parse(decisions("approve")[0]?.body ?? "{}")).toEqual({ version: 2 });
  });

  it("sends the same Idempotency-Key again when the answer never arrived, and a new one after a definite refusal", async () => {
    const user = userEvent.setup();
    await openList([waiting()], { accountId: OTHER });
    route("post", `${DISCOUNTS}/${ID}/approve`, "network-error", problem("self-approval-forbidden", 403), discountRecord(discount({ id: ID, name: "Campaña nueva" })));
    await user.click(screen.getByRole("button", { name: "Aprobar Campaña nueva" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar el descuento" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Aprobar" }));
    expect(await within(dialog).findByText(/No se pudo conectar con el servidor/, undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Aprobar" }));
    expect(await within(dialog).findByText(/Quien creó el descuento o propuso el cambio no puede aprobarlo ni rechazarlo/, undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Aprobar" }));

    await screen.findByText(/«Campaña nueva» quedó aprobado/, undefined, TIMEOUT);
    const keys = decisions("approve").map(keyOf);
    expect(keys).toHaveLength(3);
    expect(keys[0]).toBe(keys[1]);
    expect(keys[2]).not.toBe(keys[1]);
  });

  it("refuses a reason that is too short with nothing sent, and rejects with the version, the reason and its Idempotency-Key", async () => {
    const user = userEvent.setup();
    await openList([waiting({ version: 1 })], { accountId: OTHER });
    route("post", `${DISCOUNTS}/${ID}/reject`, discountRecord(discount({ id: ID, name: "Campaña nueva", approvalStatus: "rejected", active: false })));
    await user.click(screen.getByRole("button", { name: "Rechazar Campaña nueva" }));
    const dialog = await screen.findByRole("dialog", { name: "Rechazar el descuento" }, TIMEOUT);
    const reason = within(dialog).getByRole("textbox", { name: /Motivo del rechazo/ });
    await user.click(reason);
    await user.paste("Muy corto");
    await user.click(within(dialog).getByRole("button", { name: "Rechazar" }));

    expect((await within(dialog).findAllByText("Escriba de 20 a 500 caracteres.")).length).toBeGreaterThan(0);
    expect(decisions("reject")).toHaveLength(0);

    await user.clear(reason);
    await user.click(reason);
    await user.paste(REASON);
    await user.click(within(dialog).getByRole("button", { name: "Rechazar" }));

    expect(await screen.findByText(/«Campaña nueva» quedó rechazado. Ningún cargo cambió/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(decisions("reject")).toHaveLength(1);
    expect(JSON.parse(decisions("reject")[0]?.body ?? "{}")).toEqual({ version: 1, reason: REASON });
    expect(keyOf(decisions("reject")[0])).not.toBe("");
  });

  it("hides Approve and Reject from the person who created the discount and from the one who proposed the change", async () => {
    await openList([waiting(), proposed({ id: ID_2, name: "Campaña con propuesta" })], { accountId: ME });

    expect(screen.queryByRole("button", { name: "Aprobar Campaña nueva" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Rechazar Campaña nueva" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Aprobar Campaña con propuesta" })).not.toBeInTheDocument();
  });

  it("offers no decision to a session without scholarships:approve", async () => {
    await openList([waiting()], { permissions: without("scholarships:approve"), accountId: OTHER });

    expect(screen.queryByRole("button", { name: "Aprobar Campaña nueva" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Rechazar Campaña nueva" })).not.toBeInTheDocument();
  });

  it("tells in the dialog, in its own words, that the server refused the person who created it, and keeps the dialog open", async () => {
    const user = userEvent.setup();
    await openList([waiting()], { accountId: OTHER });
    route("post", `${DISCOUNTS}/${ID}/approve`, problem("self-approval-forbidden", 403, { traceId: "01J8Z4K2QF9P0X" }));
    await user.click(screen.getByRole("button", { name: "Aprobar Campaña nueva" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar el descuento" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Aprobar" }));

    expect(await within(dialog).findByText(/Quien creó el descuento o propuso el cambio no puede aprobarlo ni rechazarlo/, undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
  });

  it("tells that a discount with nothing pending admits no decision, and reads the discounts again", async () => {
    const user = userEvent.setup();
    await openList([waiting()], { accountId: OTHER });
    route("post", `${DISCOUNTS}/${ID}/approve`, problem("invalid-discount-change-state", 409));
    const before = gets().length;
    await user.click(screen.getByRole("button", { name: "Aprobar Campaña nueva" }));
    const dialog = await screen.findByRole("dialog", { name: "Aprobar el descuento" }, TIMEOUT);
    await user.click(within(dialog).getByRole("button", { name: "Aprobar" }));

    expect(await within(dialog).findByText(/El descuento ya no tiene pendiente lo que se mostraba/, undefined, TIMEOUT)).toBeInTheDocument();
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    }, TIMEOUT);
  });
});

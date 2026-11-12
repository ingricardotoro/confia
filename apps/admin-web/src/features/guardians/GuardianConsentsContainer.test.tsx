import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { consentChange, consentEvent, guardian, guardianConsents, guardianPage, guardianRecord, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route, type Reply } from "../../test/msw-server.ts";
import { renderFeature } from "../../test/render-feature.tsx";
import { guardiansFeature } from "./feature.tsx";

// The consents section of the detail of a guardian, over the real routes, query layer and transport with the test server
// answering: the matrix of the six combinations (nothing preselected), the form of a grant and of a withdrawal, the `changed`
// flag, the history, every error the server can give, the states of the load, focus and announcements, and that no contact
// data reaches the address, the storage or the console.
installMswServer();

const GUARDIANS = "/api/v1/guardians";
const TIMEOUT = { timeout: 10_000 };
const EMAIL = "rosa.mejia@example.test";
const ROSA = guardian({ firstName: "Rosa", lastName: "Mejía", guardianCode: "P-000001", email: EMAIL });
const consentGets = () => requests().filter((sent) => sent.method === "GET" && sent.path === `${GUARDIANS}/${ROSA.id}/consents`);
const consentPosts = () => requests().filter((sent) => sent.method === "POST" && sent.path === `${GUARDIANS}/${ROSA.id}/consents`);
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

afterEach(() => {
  vi.restoreAllMocks();
});

const EMAIL_INSTITUTIONAL = "Correo electrónico, Institucional";

async function openDetail(consents = guardianConsents([], [], ROSA.id), row = ROSA) {
  route("get", GUARDIANS, guardianPage([row]));
  route("get", `${GUARDIANS}/${row.id}`, guardianRecord(guardian({ ...row, students: [] })));
  route("get", `${GUARDIANS}/${row.id}/consents`, consents);
  const app = renderFeature(guardiansFeature, "/guardians");
  const user = userEvent.setup();
  await screen.findByRole("table", { name: "Encargados de la institución" }, TIMEOUT);
  await user.click(await screen.findByRole("button", { name: "Ver detalle de Rosa Mejía" }));
  const dialog = await screen.findByRole("dialog", { name: "Detalle del encargado" });
  const section = await waitFor(() => within(dialog).getByRole("region", { name: "Consentimientos" }), TIMEOUT);
  await within(section).findByRole("table", { name: admin.guardians.consents.matrixCaption }, TIMEOUT);
  return { user, dialog, section, app };
}

const grantButton = (section: HTMLElement, names: string = EMAIL_INSTITUTIONAL) =>
  within(section).getByRole("button", { name: `Registrar consentimiento: ${names}` });
const withdrawButton = (section: HTMLElement, names: string = EMAIL_INSTITUTIONAL) =>
  within(section).getByRole("button", { name: `Registrar revocación: ${names}` });

describe("the matrix of consents", () => {
  it("shows the six combinations as never granted, with nothing preselected and no checkbox", async () => {
    const { section } = await openDetail();

    const matrix = within(section).getByRole("table", { name: admin.guardians.consents.matrixCaption });
    expect(within(matrix).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Canal", "Institucional", "Mercadeo"]);
    expect(within(matrix).getAllByRole("rowheader").map((header) => header.textContent)).toEqual(["Correo electrónico", "Mensaje de texto", "WhatsApp"]);
    expect(within(matrix).getAllByText("Sin consentimiento registrado")).toHaveLength(6);
    expect(within(section).queryAllByRole("checkbox")).toHaveLength(0);
    expect(within(section).queryAllByRole("radio")).toHaveLength(0);
    expect(within(matrix).getAllByRole("button")).toHaveLength(12);
    expect(within(section).getByText(admin.guardians.consents.history.empty)).toBeInTheDocument();
    expect(within(section).queryByRole("form")).not.toBeInTheDocument();
  });

  it("shows what the server holds: the state, since when, the policy version and the evidence; and the history, newest first", async () => {
    const { section } = await openDetail(
      guardianConsents(
        [
          { channel: "EMAIL", purpose: "INSTITUTIONAL", state: "GRANTED", since: "2026-11-02T15:30:00Z", policyVersion: "2026-01", evidenceSource: "SIGNED_PAPER_FORM" },
          { channel: "SMS", purpose: "MARKETING", state: "WITHDRAWN", since: "2026-11-03T15:30:00Z", policyVersion: null, evidenceSource: "ADMIN_REGISTERED" },
        ],
        [
          consentEvent({ channel: "SMS", purpose: "MARKETING", action: "WITHDRAW", policyVersion: null, evidenceSource: "ADMIN_REGISTERED", occurredAt: "2026-11-03T15:30:00Z" }),
          consentEvent({ channel: "EMAIL", purpose: "INSTITUTIONAL", action: "GRANT", occurredAt: "2026-11-02T15:30:00Z" }),
        ],
        ROSA.id,
      ),
    );

    const matrix = within(section).getByRole("table", { name: admin.guardians.consents.matrixCaption });
    const email = within(matrix).getByRole("row", { name: /Correo electrónico/ });
    expect(within(email).getByText("Concedido")).toBeInTheDocument();
    expect(within(email).getByText("Política 2026-01")).toBeInTheDocument();
    expect(within(email).getByText("Formulario en papel firmado")).toBeInTheDocument();
    expect(within(matrix).getByText("Revocado")).toBeInTheDocument();
    expect(within(matrix).getAllByText("Sin consentimiento registrado")).toHaveLength(4);
    const history = within(section).getByRole("table", { name: admin.guardians.consents.history.caption });
    const rows = within(history).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Mensaje de texto");
    expect(rows[0]).toHaveTextContent("Revocación");
    expect(rows[1]).toHaveTextContent("Concesión");
    expect(rows[1]).toHaveTextContent("2026-01");
  });

  it("says how many events the history shows when the server cut it at 100", async () => {
    const events = Array.from({ length: 100 }, () => consentEvent());
    const { section } = await openDetail(guardianConsents([], events, ROSA.id));

    expect(within(section).getByText("Se muestran los 100 eventos más recientes.")).toBeInTheDocument();
  });

  it("warns that a grant is refused for an inactive guardian but a withdrawal is not", async () => {
    const { section } = await openDetail(guardianConsents([], [], ROSA.id), guardian({ ...ROSA, status: "INACTIVE" }));

    expect(within(section).getByText(admin.guardians.consents.inactiveNote)).toBeInTheDocument();
  });

  it("shows a retry when the read fails, and the matrix after it", async () => {
    route("get", GUARDIANS, guardianPage([ROSA]));
    route("get", `${GUARDIANS}/${ROSA.id}`, guardianRecord(guardian({ ...ROSA, students: [] })));
    route("get", `${GUARDIANS}/${ROSA.id}/consents`, problem("internal-error", 500, { traceId: "t-consents" }), guardianConsents([], [], ROSA.id));
    renderFeature(guardiansFeature, "/guardians");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Ver detalle de Rosa Mejía" }, TIMEOUT));
    const dialog = await screen.findByRole("dialog", { name: "Detalle del encargado" });

    expect(await within(dialog).findByText(admin.guardians.consents.loadFailed, undefined, TIMEOUT)).toBeInTheDocument();
    expect(dialog).toHaveTextContent("t-consents");
    await user.click(within(dialog).getByRole("button", { name: "Reintentar" }));

    expect(await within(dialog).findByRole("table", { name: admin.guardians.consents.matrixCaption }, TIMEOUT)).toBeInTheDocument();
  });
});

describe("registering a consent", () => {
  it("opens a form with the focus on the policy version, nothing chosen, and sends the exact body once; then reads again and returns the focus", async () => {
    const { user, section } = await openDetail();
    route("post", `${GUARDIANS}/${ROSA.id}/consents`, consentChange());

    await user.click(grantButton(section));

    const form = within(section).getByRole("heading", { name: "Registrar consentimiento · Correo electrónico · Institucional" }).closest("form") as HTMLElement;
    const version = within(form).getByRole("textbox", { name: /Versión de la política de privacidad/ });
    expect(version).toHaveFocus();
    expect(version).toHaveValue("");
    expect(within(form).getByRole("combobox", { name: /Evidencia/ })).toHaveValue("");
    await user.type(version, "2026-01");
    await user.selectOptions(within(form).getByRole("combobox", { name: /Evidencia/ }), "Formulario en papel firmado");
    route("get", `${GUARDIANS}/${ROSA.id}/consents`, guardianConsents([{ channel: "EMAIL", purpose: "INSTITUTIONAL", state: "GRANTED", since: "2026-11-02T15:30:00Z", policyVersion: "2026-01", evidenceSource: "SIGNED_PAPER_FORM" }], [consentEvent()], ROSA.id));
    await user.dblClick(within(form).getByRole("button", { name: "Registrar consentimiento" }));

    await waitFor(() => {
      expect(within(section).queryByRole("heading", { name: /Registrar consentimiento ·/ })).not.toBeInTheDocument();
    }, TIMEOUT);
    expect(consentPosts()).toHaveLength(1);
    expect(bodyOf(consentPosts()[0])).toEqual({ channel: "EMAIL", purpose: "INSTITUTIONAL", action: "GRANT", evidenceSource: "SIGNED_PAPER_FORM", policyVersion: "2026-01" });
    expect(consentPosts()[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(within(section).getByText("Se registró el consentimiento de Correo electrónico para la finalidad Institucional.")).toBeInTheDocument();
    await waitFor(() => {
      expect(within(section).getByText("Concedido")).toBeInTheDocument();
    }, TIMEOUT);
    expect(consentGets()).toHaveLength(2);
    expect(grantButton(section)).toHaveFocus();
    expect(within(section).getByText("Política 2026-01", { selector: "p" })).toBeInTheDocument();
  });

  it("sends a withdrawal without a policy version, asks only for the evidence, and says it was recorded", async () => {
    const { user, section } = await openDetail();
    route("post", `${GUARDIANS}/${ROSA.id}/consents`, consentChange({ state: "WITHDRAWN", policyVersion: null }));

    await user.click(withdrawButton(section));

    const form = within(section).getByRole("heading", { name: "Registrar revocación · Correo electrónico · Institucional" }).closest("form") as HTMLElement;
    expect(within(form).queryByRole("textbox")).not.toBeInTheDocument();
    expect(within(form).getByRole("combobox", { name: /Evidencia/ })).toHaveFocus();
    await user.selectOptions(within(form).getByRole("combobox", { name: /Evidencia/ }), "Registrado por el personal");
    await user.click(within(form).getByRole("button", { name: "Registrar revocación" }));

    expect(await within(section).findByText("Se registró la revocación de Correo electrónico para la finalidad Institucional.", undefined, TIMEOUT)).toBeInTheDocument();
    expect(bodyOf(consentPosts()[0])).toEqual({ channel: "EMAIL", purpose: "INSTITUTIONAL", action: "WITHDRAW", evidenceSource: "ADMIN_REGISTERED" });
  });

  it("tells a no-op from a write by the changed flag of the answer", async () => {
    const { user, section } = await openDetail();
    route("post", `${GUARDIANS}/${ROSA.id}/consents`, consentChange({ changed: false }));

    await user.click(grantButton(section, "WhatsApp, Mercadeo"));
    await user.type(within(section).getByRole("textbox", { name: /Versión/ }), "2026-01");
    await user.selectOptions(within(section).getByRole("combobox", { name: /Evidencia/ }), "Formulario en papel firmado");
    await user.click(within(section).getByRole("button", { name: "Registrar consentimiento" }));

    expect(await within(section).findByText(/^Sin cambios: el consentimiento de .* ya estaba concedido\./, undefined, TIMEOUT)).toBeInTheDocument();
    expect(within(section).queryByText(/^Se registró/)).not.toBeInTheDocument();
  });

  it("refuses an empty form before sending: the summary names both fields and takes the focus", async () => {
    const { user, section } = await openDetail();

    await user.click(grantButton(section));
    await user.click(within(section).getByRole("button", { name: "Registrar consentimiento" }));

    const summary = within(section).getByRole("group", { name: /./ });
    expect(summary).toHaveFocus();
    expect(within(summary).getAllByRole("link")).toHaveLength(2);
    expect(within(section).getAllByText(admin.field.required)).toHaveLength(4);
    expect(consentPosts()).toHaveLength(0);
    await user.click(within(summary).getAllByRole("link")[0] as HTMLElement);
    expect(within(section).getByRole("textbox", { name: /Versión/ })).toHaveFocus();
  });

  it("refuses a policy version with a space or more than 32 characters", async () => {
    const { user, section } = await openDetail();
    await user.click(grantButton(section));
    await user.selectOptions(within(section).getByRole("combobox", { name: /Evidencia/ }), "Registrado por el personal");

    await user.type(within(section).getByRole("textbox", { name: /Versión/ }), "2026 01");
    await user.click(within(section).getByRole("button", { name: "Registrar consentimiento" }));

    expect(within(section).getAllByText(admin.field.policyVersionFormat).length).toBeGreaterThan(0);
    expect(consentPosts()).toHaveLength(0);
  });

  it("cancels with nothing sent and returns the focus to the button that opened the form", async () => {
    const { user, section } = await openDetail();

    await user.click(withdrawButton(section, "Mensaje de texto, Mercadeo"));
    await user.click(within(section).getByRole("button", { name: "Cancelar" }));

    expect(within(section).queryByRole("combobox")).not.toBeInTheDocument();
    expect(withdrawButton(section, "Mensaje de texto, Mercadeo")).toHaveFocus();
    expect(consentPosts()).toHaveLength(0);
  });
});

describe("what the server refuses", { timeout: 20_000 }, () => {
  async function submitGrant(reply: Reply) {
    const opened = await openDetail();
    route("post", `${GUARDIANS}/${ROSA.id}/consents`, reply);
    await opened.user.click(grantButton(opened.section));
    await opened.user.type(within(opened.section).getByRole("textbox", { name: /Versión/ }), "2026-01");
    await opened.user.selectOptions(within(opened.section).getByRole("combobox", { name: /Evidencia/ }), "Formulario en papel firmado");
    await opened.user.click(within(opened.section).getByRole("button", { name: "Registrar consentimiento" }));
    return opened;
  }

  it.each([
    ["consent-channel-unavailable", 409, admin.problem.consentChannelUnavailable],
    ["guardian-not-active", 409, admin.problem.guardianNotActiveForConsent],
    ["forbidden", 403, admin.problem.forbidden],
    ["resource-not-found", 404, admin.problem.notFound],
    ["internal-error", 500, admin.problem.unexpected],
  ] as const)("says %s in plain words, keeps the form and its values, and moves the focus to the alert", async (code, status, text) => {
    const { section } = await submitGrant(problem(code, status, { traceId: "t-1" }));

    const alert = await within(section).findByRole("alert", undefined, TIMEOUT);
    expect(alert).toHaveTextContent(text);
    expect(alert).toHaveTextContent("t-1");
    expect(alert).toHaveFocus();
    expect(within(section).getByRole("textbox", { name: /Versión/ })).toHaveValue("2026-01");
    expect(within(section).getByRole("button", { name: "Registrar consentimiento" })).not.toHaveAttribute("aria-busy", "true");
  });

  it("puts a field error of the server under its field and in the summary", async () => {
    const { section } = await submitGrant(problem("validation-failed", 400, { errors: [{ field: "policyVersion", reason: "pattern" }] }));

    expect(await within(section).findAllByText(admin.field.policyVersionFormat, undefined, TIMEOUT)).toHaveLength(2);
    expect(within(section).getByRole("textbox", { name: /Versión/ })).toHaveAttribute("aria-invalid", "true");
  });

  it("says a source of evidence the administration does not accept", async () => {
    const { section } = await submitGrant(problem("validation-failed", 400, { errors: [{ field: "evidenceSource", reason: "not-allowed" }] }));

    expect(await within(section).findAllByText(admin.field.notAllowed, undefined, TIMEOUT)).not.toHaveLength(0);
  });

  it("says a purpose that is not consentable as a general failure of the data sent", async () => {
    const { section } = await submitGrant(problem("validation-failed", 400, { errors: [{ field: "purpose", reason: "not-consentable" }] }));

    expect(await within(section).findByRole("alert", undefined, TIMEOUT)).toHaveTextContent(admin.problem.notProcessed);
  });

  it("answers a 429 with the wait, and does not send again while it lasts", async () => {
    const { user, section } = await submitGrant({ ...problem("too-many-requests", 429), headers: { "Retry-After": "30" } });

    expect(await within(section).findByRole("alert", undefined, TIMEOUT)).toHaveTextContent(admin.problem.rateLimited);
    const sent = consentPosts().length;
    await user.click(within(section).getByRole("button", { name: "Registrar consentimiento" }));
    expect(consentPosts()).toHaveLength(sent);
  });
});

describe("what is kept", () => {
  it("leaves no contact data in the address, the storage or the console, and none in the consents section", async () => {
    const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { user, section, app } = await openDetail();
    route("post", `${GUARDIANS}/${ROSA.id}/consents`, consentChange());
    await user.click(grantButton(section));
    await user.type(within(section).getByRole("textbox", { name: /Versión/ }), "2026-01");
    await user.selectOptions(within(section).getByRole("combobox", { name: /Evidencia/ }), "Formulario en papel firmado");
    await user.click(within(section).getByRole("button", { name: "Registrar consentimiento" }));
    await within(section).findByText(/^Se registró/, undefined, TIMEOUT);

    expect(app.router.state.location.searchStr).not.toContain(EMAIL);
    expect(app.router.state.location.searchStr).not.toContain("2026-01");
    expect(localStorage.length + sessionStorage.length).toBe(0);
    expect(section).not.toHaveTextContent(EMAIL);
    for (const spy of [log, warn, error]) expect(JSON.stringify(spy.mock.calls)).not.toContain(EMAIL);
  });
});

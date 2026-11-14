import type { GuardianConsentsResponseOutput } from "@confia/contracts/admin";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import admin from "../../../i18n/locales/es-HN/admin.json";
import { consentEvent, consentStandings } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { EMPTY_CONSENT_DRAFT } from "../consent-model.ts";
import type { ConsentFormView } from "../use-consent-form.ts";
import { GuardianConsents, type GuardianConsentsProps } from "./GuardianConsents.tsx";

// States. AW57, AW58: the consents section with props alone: loading (a skeleton with a text for the reader), failed (with a retry),
// ready with no events (six combinations never granted and an empty history), ready with standings and a history, an inactive
// guardian (a note, the matrix still there), the message of the last change and the open form. There is no empty state of its
// own beyond the empty history: the matrix always has its six combinations.
const READY = (override: Partial<GuardianConsentsResponseOutput> = {}): GuardianConsentsProps["state"] => ({
  kind: "ready",
  data: { guardianId: "00000000-0000-4000-8000-000000000001", states: consentStandings(), history: [], ...override },
});

function props(override: Partial<GuardianConsentsProps> = {}): GuardianConsentsProps {
  return { state: READY(), guardianActive: true, form: undefined, message: "", onOpen: vi.fn(), ...override };
}

function formView(override: Partial<ConsentFormView> = {}): ConsentFormView {
  return {
    target: { channel: "EMAIL", purpose: "INSTITUTIONAL", action: "GRANT" },
    draft: EMPTY_CONSENT_DRAFT,
    errors: {},
    summary: [],
    submitting: false,
    liveMessage: "",
    attachSummary: () => undefined,
    attachAlert: () => undefined,
    change: vi.fn(),
    submit: vi.fn(),
    cancel: vi.fn(),
    ...override,
  };
}

describe("GuardianConsents", () => {
  it("is a skeleton with a text for the reader while it loads, and no matrix yet", () => {
    renderWithAdminI18n(<GuardianConsents {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByText(admin.guardians.consents.loading)).toHaveClass("sr-only");
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("offers a retry when it failed", async () => {
    const onRetry = vi.fn();
    renderWithAdminI18n(<GuardianConsents {...props({ state: { kind: "failed", description: "Falló.", onRetry } })} />);

    expect(screen.getByText(admin.guardians.consents.loadFailed)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("with no events shows the six combinations as never granted, twelve actions and an empty history", () => {
    renderWithAdminI18n(<GuardianConsents {...props()} />);

    const matrix = screen.getByRole("table", { name: admin.guardians.consents.matrixCaption });
    expect(within(matrix).getAllByText("Sin consentimiento registrado")).toHaveLength(6);
    expect(within(matrix).getAllByRole("button")).toHaveLength(12);
    expect(screen.getByText(admin.guardians.consents.history.empty)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("shows the standing of a combination with its date, policy and evidence, and the history with its events", () => {
    renderWithAdminI18n(
      <GuardianConsents
        {...props({
          state: READY({
            states: consentStandings([{ channel: "EMAIL", purpose: "INSTITUTIONAL", state: "GRANTED", since: "2026-11-02T15:30:00Z", policyVersion: "2026-01", evidenceSource: "SIGNED_PAPER_FORM" }]),
            history: [consentEvent(), consentEvent({ action: "WITHDRAW", policyVersion: null, evidenceSource: "ADMIN_REGISTERED" })],
          }),
        })}
      />,
    );

    const matrix = screen.getByRole("table", { name: admin.guardians.consents.matrixCaption });
    expect(within(matrix).getByText("Concedido")).toBeInTheDocument();
    expect(within(matrix).getByText(/^Desde /)).toBeInTheDocument();
    expect(within(matrix).getByText("Política 2026-01")).toBeInTheDocument();
    const history = screen.getByRole("table", { name: admin.guardians.consents.history.caption });
    expect(within(history).getAllByRole("row")).toHaveLength(2 + 1);
    expect(within(history).getByText(admin.guardians.consents.history.noPolicy)).toBeInTheDocument();
  });

  it("warns that an inactive guardian cannot get a new consent and still shows the matrix", () => {
    renderWithAdminI18n(<GuardianConsents {...props({ guardianActive: false })} />);

    expect(screen.getByText(admin.guardians.consents.inactiveNote)).toBeInTheDocument();
    expect(screen.getByRole("table", { name: admin.guardians.consents.matrixCaption })).toBeInTheDocument();
  });

  it("asks to open the form of the combination whose action was clicked, named by channel and purpose", async () => {
    const onOpen = vi.fn();
    renderWithAdminI18n(<GuardianConsents {...props({ onOpen })} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Registrar consentimiento: Correo electrónico, Institucional" }));
    await user.click(screen.getByRole("button", { name: "Registrar revocación: WhatsApp, Mercadeo" }));

    expect(onOpen).toHaveBeenNthCalledWith(1, { channel: "EMAIL", purpose: "INSTITUTIONAL", action: "GRANT" });
    expect(onOpen).toHaveBeenNthCalledWith(2, { channel: "WHATSAPP", purpose: "MARKETING", action: "WITHDRAW" });
  });

  it("announces the message of the last change in a polite region", () => {
    renderWithAdminI18n(<GuardianConsents {...props({ message: "Consentimiento registrado." })} />);

    expect(screen.getByText("Consentimiento registrado.").closest("[aria-live]")).toHaveAttribute("aria-live", "polite");
  });

  it("shows the open form of a grant with its fields and lets the person cancel", async () => {
    const cancel = vi.fn();
    renderWithAdminI18n(<GuardianConsents {...props({ form: formView({ cancel }) })} />);

    expect(screen.getByRole("heading", { level: 4, name: "Registrar consentimiento · Correo electrónico · Institucional" })).toBeInTheDocument();
    expect(screen.getByLabelText(new RegExp(admin.guardians.consents.form.policyVersion))).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: admin.guardians.consents.form.cancel }));

    expect(cancel).toHaveBeenCalledTimes(1);
  });
});

import type { GuardianConsentsResponseOutput } from "@confia/contracts/admin";
import { Alert, Button, ErrorState, FormErrorSummary, FormField, Input, Select, Skeleton } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant } from "../../../shared/format/format-date.ts";
import {
  CONSENT_CHANNELS,
  CONSENT_CONTROL_IDS,
  CONSENT_EVIDENCES,
  CONSENT_PURPOSES,
  consentActionId,
  standingOf,
  type ConsentEvidence,
  type ConsentStanding,
  type ConsentTarget,
} from "../consent-model.ts";
import type { ConsentFormView } from "../use-consent-form.ts";

export type GuardianConsentsState =
  | { readonly kind: "loading" }
  | {
      readonly kind: "failed";
      readonly description: string;
      readonly onRetry: () => void;
    }
  | { readonly kind: "ready"; readonly data: GuardianConsentsResponseOutput };

export interface GuardianConsentsProps {
  readonly state: GuardianConsentsState;
  /** The guardian is inactive: a grant is refused by the server, a withdrawal is not. Only the note depends on it. */
  readonly guardianActive: boolean;
  /** The open form, when a cell asked for one. */
  readonly form: ConsentFormView | undefined;
  /** The text of the last change, already translated; empty until there is one. */
  readonly message: string;
  readonly onOpen: (target: ConsentTarget) => void;
}

/** The most events the server serves in the history. */
const HISTORY_LIMIT = 100;

/**
 * The consents of a guardian, in a section of the detail: the state the server holds for each of the six combinations of
 * channel and purpose (never preselected: a combination with no events says so), the action of each cell, the form of the
 * action that is open and the history, newest first. Loading is a skeleton with the shape of the matrix, a failure offers a
 * retry, and a guardian with no events says so. It shows no contact data: the API never sends it.
 */
export function GuardianConsents({ state, guardianActive, form, message, onOpen }: GuardianConsentsProps) {
  const { t } = useAdminText();
  return (
    <section aria-labelledby="guardian-consents-heading" className="flex flex-col gap-3 border-t border-border-subtle pt-4">
      <h3 id="guardian-consents-heading" className="text-base font-semibold text-text-primary">
        {t("guardians.consents.heading")}
      </h3>
      <p className="text-sm text-text-secondary">{t("guardians.consents.intro")}</p>
      <div aria-live="polite" aria-atomic="true">
        {message === "" ? null : <Alert variant="info">{message}</Alert>}
      </div>
      {state.kind === "loading" ? (
        <div aria-busy="true" className="flex flex-col gap-2">
          <p className="sr-only">{t("guardians.consents.loading")}</p>
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      ) : null}
      {state.kind === "failed" ? (
        <ErrorState title={t("guardians.consents.loadFailed")} description={state.description} onRetry={state.onRetry} />
      ) : null}
      {state.kind === "ready" ? (
        <>
          {guardianActive ? null : <Alert variant="warning">{t("guardians.consents.inactiveNote")}</Alert>}
          <Matrix states={state.data.states} onOpen={onOpen} />
          {form === undefined ? null : <ConsentForm form={form} />}
          <History history={state.data.history} />
        </>
      ) : null}
    </section>
  );
}

function Matrix({ states, onOpen }: { readonly states: readonly ConsentStanding[]; readonly onOpen: GuardianConsentsProps["onOpen"] }) {
  const { t } = useAdminText();
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">{t("guardians.consents.matrixCaption")}</caption>
        <thead>
          <tr className="border-b border-border-subtle">
            <th scope="col" className="py-1.5 pr-3 font-medium">
              {t("guardians.consents.channelHeader")}
            </th>
            {CONSENT_PURPOSES.map((purpose) => (
              <th key={purpose} scope="col" className="py-1.5 pr-3 font-medium">
                {t(`guardians.consents.purpose.${purpose}`)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {CONSENT_CHANNELS.map((channel) => (
            <tr key={channel} className="border-b border-border-subtle align-top last:border-0">
              <th scope="row" className="py-2 pr-3 font-medium">
                {t(`guardians.channel.${channel}`)}
              </th>
              {CONSENT_PURPOSES.map((purpose) => (
                <td key={purpose} className="py-2 pr-3">
                  <Cell standing={standingOf(states, channel, purpose)} channel={channel} purpose={purpose} onOpen={onOpen} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface CellProps {
  readonly standing: ConsentStanding | undefined;
  readonly channel: ConsentTarget["channel"];
  readonly purpose: ConsentTarget["purpose"];
  readonly onOpen: GuardianConsentsProps["onOpen"];
}

function Cell({ standing, channel, purpose, onOpen }: CellProps) {
  const { t } = useAdminText();
  const names = {
    channel: t(`guardians.channel.${channel}`),
    purpose: t(`guardians.consents.purpose.${purpose}`),
  };
  const state = standing?.state ?? "NEVER_GRANTED";
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-col gap-0.5">
        <p className="font-medium text-text-primary">{t(`guardians.consents.state.${state}`)}</p>
        {standing?.since == null ? null : (
          <p className="text-text-secondary">
            {t("guardians.consents.since", {
              date: formatInstant(standing.since),
            })}
          </p>
        )}
        {standing?.policyVersion == null ? null : (
          <p className="text-text-secondary">
            {t("guardians.consents.policy", {
              version: standing.policyVersion,
            })}
          </p>
        )}
        {standing?.evidenceSource == null ? null : (
          <p className="text-text-secondary">{t(`guardians.consents.evidence.${standing.evidenceSource}`)}</p>
        )}
      </div>
      <div className="flex flex-wrap gap-2">
        <Button
          id={consentActionId({ channel, purpose, action: "GRANT" })}
          variant="secondary"
          size="sm"
          aria-label={t("guardians.consents.grantFor", names)}
          onClick={() => {
            onOpen({ channel, purpose, action: "GRANT" });
          }}
        >
          {t("guardians.consents.grant")}
        </Button>
        <Button
          id={consentActionId({ channel, purpose, action: "WITHDRAW" })}
          variant="secondary"
          size="sm"
          aria-label={t("guardians.consents.withdrawFor", names)}
          onClick={() => {
            onOpen({ channel, purpose, action: "WITHDRAW" });
          }}
        >
          {t("guardians.consents.withdraw")}
        </Button>
      </div>
    </div>
  );
}

function ConsentForm({ form }: { readonly form: ConsentFormView }) {
  const { t } = useAdminText();
  const { target, draft, errors, summary, alert, countdown, submitting, liveMessage, attachSummary, attachAlert, change, submit, cancel } = form;
  const grant = target.action === "GRANT";
  const names = {
    channel: t(`guardians.channel.${target.channel}`),
    purpose: t(`guardians.consents.purpose.${target.purpose}`),
  };
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-3 rounded-md border border-border-subtle p-4">
      <h4 className="text-sm font-semibold text-text-primary">
        {t(grant ? "guardians.consents.form.grantTitle" : "guardians.consents.form.withdrawTitle", names)}
      </h4>
      <FormErrorSummary ref={attachSummary} errors={summary} />
      {alert === undefined ? null : (
        <Alert ref={attachAlert} variant="destructive">
          <p>{alert.message}</p>
          {alert.traceId === undefined ? null : <p className="mt-1">{t("organization.common.trace", { traceId: alert.traceId })}</p>}
        </Alert>
      )}
      {countdown === undefined ? null : <p className="text-sm text-text-secondary">{countdown.remaining}</p>}
      {grant ? (
        <FormField
          label={t("guardians.consents.form.policyVersion")}
          description={t("guardians.consents.form.policyVersionHelp")}
          error={errors.policyVersion ?? ""}
          controlId={CONSENT_CONTROL_IDS.policyVersion}
          required
        >
          {(control) => (
            <Input
              {...control}
              value={draft.policyVersion}
              maxLength={64}
              autoComplete="off"
              spellCheck={false}
              onChange={(event) => {
                change({ policyVersion: event.currentTarget.value });
              }}
            />
          )}
        </FormField>
      ) : (
        <p className="text-sm text-text-secondary">{t("guardians.consents.form.withdrawHelp")}</p>
      )}
      <FormField
        label={t("guardians.consents.form.evidence")}
        description={t("guardians.consents.form.evidenceHelp")}
        error={errors.evidenceSource ?? ""}
        controlId={CONSENT_CONTROL_IDS.evidenceSource}
        required
      >
        {(control) => (
          <Select
            {...control}
            value={draft.evidence}
            onChange={(event) => {
              change({
                evidence: event.currentTarget.value as ConsentEvidence | "",
              });
            }}
          >
            <option value="">{t("guardians.consents.form.evidencePlaceholder")}</option>
            {CONSENT_EVIDENCES.map((evidence) => (
              <option key={evidence} value={evidence}>
                {t(`guardians.consents.evidence.${evidence}`)}
              </option>
            ))}
          </Select>
        )}
      </FormField>
      <p aria-live="polite" aria-atomic="true" className="text-sm text-text-secondary">
        {liveMessage}
      </p>
      <div className="flex flex-wrap gap-2">
        <Button type="submit" loading={submitting}>
          {t(grant ? "guardians.consents.grant" : "guardians.consents.withdraw")}
        </Button>
        <Button type="button" variant="secondary" disabled={submitting} onClick={cancel}>
          {t("guardians.consents.form.cancel")}
        </Button>
      </div>
    </form>
  );
}

function History({ history }: { readonly history: GuardianConsentsResponseOutput["history"] }) {
  const { t } = useAdminText();
  return (
    <div className="flex flex-col gap-2">
      <h4 className="text-sm font-semibold text-text-primary">{t("guardians.consents.history.heading")}</h4>
      {history.length === 0 ? (
        <p className="text-sm text-text-secondary">{t("guardians.consents.history.empty")}</p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">{t("guardians.consents.history.caption")}</caption>
              <thead>
                <tr className="border-b border-border-subtle">
                  {(["date", "channel", "purpose", "action", "policy", "evidence"] as const).map((column) => (
                    <th key={column} scope="col" className="py-1.5 pr-3 font-medium">
                      {t(`guardians.consents.history.${column}`)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {history.map((event) => (
                  <tr key={event.id} className="border-b border-border-subtle last:border-0">
                    <td className="py-1.5 pr-3">{formatInstant(event.occurredAt)}</td>
                    <td className="py-1.5 pr-3">{t(`guardians.channel.${event.channel}`)}</td>
                    <td className="py-1.5 pr-3">{t(`guardians.consents.purpose.${event.purpose}`)}</td>
                    <td className="py-1.5 pr-3">
                      {t(event.action === "GRANT" ? "guardians.consents.history.action_GRANT" : "guardians.consents.history.action_WITHDRAW")}
                    </td>
                    <td className="py-1.5 pr-3">{event.policyVersion ?? t("guardians.consents.history.noPolicy")}</td>
                    <td className="py-1.5">{t(`guardians.consents.evidence.${event.evidenceSource}`)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {history.length >= HISTORY_LIMIT ? (
            <p className="text-sm text-text-secondary">{t("guardians.consents.history.limit", { count: HISTORY_LIMIT })}</p>
          ) : null}
        </>
      )}
    </div>
  );
}

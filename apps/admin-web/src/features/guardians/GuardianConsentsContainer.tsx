import { useAdminText } from "../../i18n/use-admin-text.ts";
import { loadFailureOf } from "../organization/organization-failure.ts";
import { GuardianConsents } from "./components/GuardianConsents.tsx";
import { useGuardianConsents, useRecordConsent } from "./use-guardians-queries.ts";
import { useConsentForm } from "./use-consent-form.ts";

export interface GuardianConsentsContainerProps {
  readonly guardianId: string;
  readonly guardianActive: boolean;
}

/**
 * Reads the consents of one guardian (the six standings and the history, as the server holds them) and records a grant or a
 * withdrawal. Nothing is decided here: the server says what is granted, what is refused and whether a request wrote an event.
 */
export function GuardianConsentsContainer({ guardianId, guardianActive }: GuardianConsentsContainerProps) {
  const { t } = useAdminText();
  const consents = useGuardianConsents(guardianId);
  const record = useRecordConsent(guardianId);
  const { form, message, open } = useConsentForm({
    record,
    channelLabel: (channel) => t(`guardians.channel.${channel}`),
    purposeLabel: (purpose) => t(`guardians.consents.purpose.${purpose}`),
  });
  const failure = consents.isError ? loadFailureOf(consents.error) : undefined;

  return (
    <GuardianConsents
      guardianActive={guardianActive}
      form={form}
      message={message}
      onOpen={open}
      state={
        consents.isPending
          ? { kind: "loading" }
          : failure !== undefined || consents.data === undefined
            ? {
                kind: "failed",
                description: [
                  t(failure?.key ?? "problem.unexpected"),
                  failure?.traceId === undefined
                    ? ""
                    : t("organization.common.trace", {
                        traceId: failure.traceId,
                      }),
                ]
                  .filter((part) => part !== "")
                  .join(" "),
                onRetry: () => void consents.refetch(),
              }
            : { kind: "ready", data: consents.data }
      }
    />
  );
}

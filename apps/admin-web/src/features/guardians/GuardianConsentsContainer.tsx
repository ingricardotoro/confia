import { useAdminText } from "../../i18n/use-admin-text.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
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
  const { can } = usePermissions();
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
      onOpen={can("guardians:update") ? open : undefined}
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
                    : t("resource.trace", {
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

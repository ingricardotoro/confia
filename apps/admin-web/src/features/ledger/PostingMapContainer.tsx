import { useNavigate } from "@tanstack/react-router";
import { useMemo, useRef, useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import type { LedgerAccountType } from "../../shared/api/ledger-operations.ts";
import type { Lookup } from "../../shared/organization-lookups/use-lookups.ts";
import type { Option } from "../../shared/resource-screen/components/fields.tsx";
import type { ResourceNotice } from "../../shared/resource-screen/components/ResourceScreen.tsx";
import { failureMessageOf, failureOf, loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { PostingMapView, type PostingMapRow, type PostingMapState } from "./components/PostingMapView.tsx";
import type { LedgerAccount } from "./account-form.ts";
import { LEDGER_PATHS } from "./ledger-paths.ts";
import { POSTING_ROLES, accountFits, changedEntries, changedPaymentEntries, missingPaymentRoles, missingRoles, savedByRole, type RoleId } from "./posting-map-model.ts";
import { useActiveAccountLookup, usePaymentPostingAccounts, usePaymentPostingAccountsSave, usePostingAccounts, usePostingAccountsSave } from "./use-ledger-queries.ts";

/**
 * The map of posting accounts: the map of the charges and the one of the payments (the cash and the bank) as the server holds them, the active accounts each row may take, and the changes the
 * person has made and not saved. It sends only what changed, with the version of each row; the server decides what is valid
 * (the type of each account, that it exists and is active) and the screen only spares the person the obvious refusals.
 * The session gives the screen no permissions, so a person who cannot change the map learns it from the server's 403, and
 * the form then turns into a read-only view with a note. Nothing is optimistic: the map is read again after every save.
 */
export function PostingMapContainer() {
  const { can } = usePermissions();
  const { t } = useAdminText();
  const navigate = useNavigate();
  const map = usePostingAccounts();
  const paymentMap = usePaymentPostingAccounts();
  const assets = useActiveAccountLookup("ASSET");
  const incomes = useActiveAccountLookup("INCOME");
  const liabilities = useActiveAccountLookup("LIABILITY");
  const save = usePostingAccountsSave();
  const savePayments = usePaymentPostingAccountsSave();
  // What the person chose and has not saved, by row; a row that is not here shows what the server holds.
  const [draft, setDraft] = useState<Partial<Record<RoleId, string>>>({});
  const [notice, setNotice] = useState<ResourceNotice>();
  const [forbidden, setForbidden] = useState(false);
  const sending = useRef(false);

  const saved = useMemo(() => savedByRole(map.data?.items ?? [], paymentMap.data?.items ?? []), [map.data, paymentMap.data]);
  const lookups: Readonly<Record<Extract<LedgerAccountType, "ASSET" | "INCOME" | "LIABILITY">, Lookup<LedgerAccount>>> = {
    ASSET: assets,
    INCOME: incomes,
    LIABILITY: liabilities,
  };
  const all = [assets, incomes, liabilities];
  const lookupsLoading = all.some((lookup) => lookup.loading);
  const failedLookups = all.filter((lookup) => lookup.failed);

  const rows: PostingMapRow[] = POSTING_ROLES.map((role) => {
    const lookup = lookups[role.accountType as keyof typeof lookups];
    const options: Option[] = lookup.rows
      .filter((account) => accountFits(role, account))
      .map((account) => ({ value: account.id, label: t("ledger.accounts.optionLabel", { code: account.code, name: account.name }) }));
    const held = saved.get(role.id)?.ledgerAccountId;
    // The account the row holds stays in its select even if the list does not have it, so the select never shows a blank for a row that is set.
    if (held !== undefined && !lookup.loading && !options.some((option) => option.value === held)) {
      options.push({ value: held, label: t("ledger.map.heldUnknown") });
    }
    return { id: role.id, group: role.group, value: draft[role.id] ?? held ?? "", options, configured: held !== undefined };
  });

  const noAccounts = !lookupsLoading && failedLookups.length === 0 && all.every((lookup) => lookup.rows.length === 0);
  const pendingCount = changedEntries(saved, draft).length + changedPaymentEntries(saved, draft).length;

  function state(): PostingMapState {
    const failed = map.isError ? map : paymentMap.isError ? paymentMap : undefined;
    if (failed !== undefined) {
      const failure = loadFailureOf(failed.error);
      const description = [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" ");
      return {
        kind: "error",
        description,
        onRetry: () => {
          if (map.isError) void map.refetch();
          if (paymentMap.isError) void paymentMap.refetch();
        },
      };
    }
    return map.isPending || paymentMap.isPending || lookupsLoading ? { kind: "loading" } : { kind: "ready" };
  }

  /** What the person is told when the save is refused, and what the screen does about it. */
  function refusalOf(error: unknown): ResourceNotice {
    const code = error instanceof ApiFailure && error.result.kind === "problem" ? error.result.problem.code : undefined;
    const traceId = error instanceof ApiFailure && error.result.kind === "problem" ? error.result.problem.traceId : undefined;
    const withTrace = (message: string): ResourceNotice => ({ tone: "error", message, ...(traceId === undefined ? {} : { traceId }) });
    if (code === "stale-version") {
      // Someone else saved first: the map was read again, so what was chosen on top of the old one is dropped.
      setDraft({});
      return withTrace(t("ledger.map.staleVersion"));
    }
    if (code === "resource-not-found") {
      setDraft({});
      return withTrace(t("ledger.map.accountMissing"));
    }
    if (code === "forbidden") {
      setDraft({});
      setForbidden(true);
    }
    const { key, traceId: failureTrace } = failureMessageOf(failureOf(error));
    return { tone: "error", message: t(key), ...(failureTrace === undefined ? {} : { traceId: failureTrace }) };
  }

  /**
   * The two maps are two endpoints, so they are two requests: the map of the charges first, then the one of the payments. Each is saved whole or not
   * at all by the server. When the second is refused after the first was saved, what was saved is dropped from the draft and the person is told so.
   */
  async function onSave(): Promise<void> {
    const entries = changedEntries(saved, draft);
    const paymentEntries = changedPaymentEntries(saved, draft);
    if (sending.current || entries.length + paymentEntries.length === 0) return;
    sending.current = true;
    setNotice(undefined);
    let chargesSaved = false;
    try {
      if (entries.length > 0) {
        await save.mutateAsync({ entries });
        chargesSaved = true;
      }
      if (paymentEntries.length > 0) await savePayments.mutateAsync({ entries: paymentEntries });
      setDraft({});
      setNotice({ tone: "success", message: t("ledger.map.saved") });
    } catch (error) {
      if (chargesSaved) {
        // The map of the charges was saved: only the cash and the bank are still pending.
        setDraft((current) => Object.fromEntries(Object.entries(current).filter(([id]) => POSTING_ROLES.some((role) => role.id === id && role.group === "payments"))));
        const refusal = refusalOf(error);
        setNotice({ ...refusal, message: `${t("ledger.map.partialSave")} ${refusal.message}` });
      } else {
        setNotice(refusalOf(error));
      }
    } finally {
      sending.current = false;
    }
  }

  return (
    <PostingMapView
      state={state()}
      rows={rows}
      missing={[...missingRoles(saved), ...missingPaymentRoles(saved)]}
      noAccounts={noAccounts}
      // The map is saved with the permission of the chart of accounts; the server refusal (403) still turns it read only as before.
      readOnly={forbidden || !can("ledger:create")}
      notice={notice}
      lookupFailure={failedLookups.length === 0 ? undefined : { onRetry: () => { failedLookups.forEach((lookup) => { lookup.retry(); }); } }}
      pendingCount={pendingCount}
      submitting={save.isPending || savePayments.isPending}
      onChange={(id, value) => {
        setNotice(undefined);
        setDraft((current) => ({ ...current, [id]: value }));
      }}
      onSave={() => void onSave()}
      onReset={() => {
        setNotice(undefined);
        setDraft({});
      }}
      onGoToAccounts={() => void navigate({ to: LEDGER_PATHS.accounts })}
    />
  );
}

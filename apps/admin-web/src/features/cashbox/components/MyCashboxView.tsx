import { Alert, Button, ErrorState, PageHeader, Skeleton } from "@confia/ui";
import type { ReactNode } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { ResourceNotice } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { CashMovementType, CashSession } from "../cashbox-types.ts";
import { CashSessionSummary } from "./CashSessionSummary.tsx";
import { MovementsTable } from "./MovementsTable.tsx";

export type MyCashboxState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly title: string; readonly description: string; readonly onRetry: () => void }
  /** The answer was no and will stay no: the person lacks the permission of the cashbox (403). Asking again changes nothing. */
  | { readonly kind: "blocked"; readonly title: string; readonly description: string }
  /** The person has no open session: the page offers to open one. */
  | { readonly kind: "none" }
  | { readonly kind: "ready"; readonly session: CashSession };

export interface MyCashboxViewProps {
  readonly state: MyCashboxState;
  /** The form that opens a session, shown when there is none. A slot, so this view makes no request. */
  readonly openForm: ReactNode;
  /** The result of the last action of the person (a session opened, a movement registered), already translated. */
  readonly notice?: ResourceNotice | undefined;
  /** The session the person closed a moment ago, as the server answered it: the counted cash, the difference the server computed and what happens next. */
  readonly closed?: CashSession | undefined;
  readonly onRegister: (type: CashMovementType) => void;
  readonly onClose: () => void;
  readonly onViewReport: (session: CashSession) => void;
}

/**
 * The cashier's own cashbox: with no open session, the form to open one; with one, what the server says about it (its float, the expected cash and
 * its movements) and the actions on it (a deposit, a withdrawal, an adjustment and the close). The expected cash is always the server's: the
 * screen adds nothing up. The session that was just closed is shown below the form with its difference as the server computed it.
 */
export function MyCashboxView({ state, openForm, notice, closed, onRegister, onClose, onViewReport }: MyCashboxViewProps) {
  const { t } = useAdminText();
  const ready = state.kind === "ready";

  return (
    <section aria-labelledby="my-cashbox-heading" className="flex flex-col gap-3">
      <PageHeader
        title={t("cashbox.mine.heading")}
        headingLevel={2}
        headingId="my-cashbox-heading"
        description={t("cashbox.mine.description")}
        {...(ready
          ? {
              actions: (
                <>
                  <Button variant="secondary" size="sm" onClick={() => { onRegister("deposit"); }}>
                    {t("cashbox.actions.deposit")}
                  </Button>
                  <Button variant="secondary" size="sm" onClick={() => { onRegister("withdrawal"); }}>
                    {t("cashbox.actions.withdrawal")}
                  </Button>
                  <Button variant="secondary" size="sm" onClick={() => { onRegister("adjustment"); }}>
                    {t("cashbox.actions.adjustment")}
                  </Button>
                  <Button size="sm" onClick={onClose}>
                    {t("cashbox.actions.close")}
                  </Button>
                </>
              ),
            }
          : {})}
      />
      <div aria-live="polite" aria-atomic="true" className="empty:hidden">
        {notice === undefined ? null : (
          <Alert variant={notice.tone === "error" ? "destructive" : "info"}>
            <p>{notice.message}</p>
            {notice.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: notice.traceId })}</p>}
          </Alert>
        )}
      </div>
      {state.kind === "loading" ? (
        <div aria-busy="true" className="flex flex-col gap-3">
          <p role="status" className="sr-only">
            {t("cashbox.mine.loading")}
          </p>
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : state.kind === "error" ? (
        <ErrorState title={state.title} description={state.description} onRetry={state.onRetry} />
      ) : state.kind === "blocked" ? (
        <Alert variant="warning">
          <p className="font-medium">{state.title}</p>
          <p className="mt-1">{state.description}</p>
        </Alert>
      ) : state.kind === "none" ? (
        <>
          {closed === undefined ? null : (
            <Alert variant={closed.status === "pending_approval" ? "warning" : "info"} title={t("cashbox.closedResult.title")}>
              <div className="flex flex-col gap-3">
                <p>{t(closed.status === "pending_approval" ? "cashbox.closedResult.pending" : "cashbox.closedResult.closed")}</p>
                <CashSessionSummary session={closed} />
                <div>
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => {
                      onViewReport(closed);
                    }}
                  >
                    {t("cashbox.closedResult.viewReport")}
                  </Button>
                </div>
              </div>
            </Alert>
          )}
          {openForm}
        </>
      ) : (
        <>
          <CashSessionSummary session={state.session} />
          <h3 className="text-base font-semibold text-text-primary">{t("cashbox.movements.heading")}</h3>
          <MovementsTable
            movements={state.session.movements}
            emptyAction={{
              label: t("cashbox.actions.deposit"),
              onAction: () => {
                onRegister("deposit");
              },
            }}
          />
        </>
      )}
    </section>
  );
}

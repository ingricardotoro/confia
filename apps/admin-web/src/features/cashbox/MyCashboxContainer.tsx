import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import type { ResourceNotice } from "../../shared/resource-screen/components/ResourceScreen.tsx";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { cashSessionPath } from "./cashbox-paths.ts";
import type { CashMovementType, CashSession } from "./cashbox-types.ts";
import { MyCashboxView, type MyCashboxState } from "./components/MyCashboxView.tsx";
import { CloseSessionContainer } from "./CloseSessionContainer.tsx";
import { MovementFormContainer } from "./MovementFormContainer.tsx";
import { OpenSessionContainer } from "./OpenSessionContainer.tsx";
import { useCurrentCashSession } from "./use-cashbox-queries.ts";

type Dialog = { readonly kind: "movement"; readonly type: CashMovementType } | { readonly kind: "close" };

/**
 * The cashier's own cashbox. What is on screen is what the server says: the open session with its expected cash and movements, or the
 * form to open one when the server answers that there is none. Every write opens an explicit dialog; when the server confirms, the session is
 * read again before the dialog closes, and the person is told. Nothing here adds up, compares or rounds an amount.
 */
export function MyCashboxContainer() {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const current = useCurrentCashSession();
  const [dialog, setDialog] = useState<Dialog>();
  const [notice, setNotice] = useState<ResourceNotice>();
  const [closed, setClosed] = useState<CashSession>();

  function stateOf(): MyCashboxState {
    // An error comes first: after a close the server answers 404 and the query keeps the last open session it read, which is no longer true.
    if (current.isError) {
      const status = current.error instanceof ApiFailure && current.error.result.kind === "problem" ? current.error.result.problem.status : undefined;
      if (status === 404) return { kind: "none" };
      if (status === 403) return { kind: "blocked", title: t("cashbox.mine.forbiddenTitle"), description: t("cashbox.mine.forbiddenDescription") };
      const failure = loadFailureOf(current.error);
      return {
        kind: "error",
        title: t("cashbox.mine.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void current.refetch(),
      };
    }
    return current.data === undefined ? { kind: "loading" } : { kind: "ready", session: current.data };
  }

  const state = stateOf();
  const session = state.kind === "ready" ? state.session : undefined;

  return (
    <>
      <MyCashboxView
        state={state}
        notice={notice}
        closed={closed}
        openForm={
          <OpenSessionContainer
            onOpened={() => {
              setClosed(undefined);
              setNotice({ tone: "success", message: t("cashbox.open.openedNotice") });
            }}
          />
        }
        onRegister={(type) => {
          setNotice(undefined);
          setDialog({ kind: "movement", type });
        }}
        onClose={() => {
          setNotice(undefined);
          setDialog({ kind: "close" });
        }}
        onViewReport={(report) => void navigate({ to: cashSessionPath(report.id) })}
      />
      {session === undefined || dialog === undefined ? null : dialog.kind === "movement" ? (
        <MovementFormContainer
          // A new dialog for each kind of movement, so nothing typed for one is shown for another.
          key={dialog.type}
          session={session}
          type={dialog.type}
          onClose={() => {
            setDialog(undefined);
          }}
          onRecorded={(type) => {
            setNotice({ tone: "success", message: t(`cashbox.movementForm.recorded.${type}`) });
            setDialog(undefined);
          }}
        />
      ) : (
        <CloseSessionContainer
          session={session}
          onClose={() => {
            setDialog(undefined);
          }}
          onClosed={(result) => {
            setNotice(undefined);
            setClosed(result);
            setDialog(undefined);
          }}
        />
      )}
    </>
  );
}

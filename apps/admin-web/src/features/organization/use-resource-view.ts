import { useRef, useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import type { ResourceNotice } from "./components/ResourceScreen.tsx";
import { failureMessageOf, failureOf } from "./organization-failure.ts";

export type DialogState<Row> = { readonly kind: "closed" } | { readonly kind: "create" } | { readonly kind: "edit"; readonly row: Row };

/**
 * What the four containers share besides the data: which dialog is open, the result of the last action, and the
 * activate or deactivate of a row. It holds no server data. One toggle runs at a time, so a second click on a
 * row that is still being saved sends nothing.
 */
export function useResourceView<Row>() {
  const { t } = useAdminText();
  const [dialog, setDialog] = useState<DialogState<Row>>({ kind: "closed" });
  const [notice, setNotice] = useState<ResourceNotice>();
  const [togglingId, setTogglingId] = useState<string>();
  const toggling = useRef(false);

  return {
    dialog,
    notice,
    togglingId,
    openCreate: () => {
      setNotice(undefined);
      setDialog({ kind: "create" });
    },
    openEdit: (row: Row) => {
      setNotice(undefined);
      setDialog({ kind: "edit", row });
    },
    close: () => {
      setDialog({ kind: "closed" });
    },
    /** A create or an edit was saved: the dialog closes and the region says so. */
    saved: (kind: "created" | "updated", name: string, warning?: string) => {
      setDialog({ kind: "closed" });
      const message = t(kind === "created" ? "organization.common.created" : "organization.common.updated", { name });
      setNotice({ tone: "success", message: warning === undefined ? message : `${message} ${warning}` });
    },
    /** Empties the region of the screen, as opening a dialog does. */
    clearNotice: () => {
      setNotice(undefined);
    },
    /** Tells the result of an action that has its own dialog, in the region of the screen. */
    announce: (message: string) => {
      setNotice({ tone: "success", message });
    },
    toggle: async <R,>(id: string, name: string, nextActive: boolean, run: () => Promise<R>, warningOf?: (result: R) => string | undefined) => {
      if (toggling.current) return;
      toggling.current = true;
      setTogglingId(id);
      setNotice(undefined);
      try {
        const result = await run();
        const message = t(nextActive ? "organization.common.activated" : "organization.common.deactivated", { name });
        const warning = warningOf?.(result);
        setNotice({ tone: "success", message: warning === undefined ? message : `${message} ${warning}` });
      } catch (error) {
        const { key, traceId } = failureMessageOf(failureOf(error));
        setNotice({ tone: "error", message: t(key), ...(traceId === undefined ? {} : { traceId }) });
      } finally {
        toggling.current = false;
        setTogglingId(undefined);
      }
    },
  };
}

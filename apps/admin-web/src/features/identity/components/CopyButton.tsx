import { Button } from "@confia/ui";
import { useState } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";

export interface CopyButtonProps {
  /** What is copied; white space is dropped, because a key shown in groups is typed without them. */
  readonly text: string;
  /** Picks the name of the control and the text of the announcement. */
  readonly kind: "key" | "trace";
}

type Outcome = "idle" | "done" | "failed";

/**
 * Copies a text and says whether it could, in a polite region that is already in the document when the button is
 * pressed, so assistive technology announces the change (docs/ui-ux/03). When the clipboard refuses, the person is
 * told to copy by hand; nothing is shown as copied.
 */
export function CopyButton({ text, kind }: CopyButtonProps) {
  const { t } = useAdminText();
  const [outcome, setOutcome] = useState<Outcome>("idle");

  function copy() {
    // Outside a secure context there is no clipboard, and some browsers throw at once instead of rejecting: every
    // way of failing ends in the same "copy by hand" message.
    // The DOM type says the clipboard is always there; outside a secure context it is not, so it is read as optional.
    const clipboard = (navigator as Partial<Navigator>).clipboard;
    let writing: Promise<void>;
    try {
      if (clipboard === undefined) throw new TypeError("no clipboard");
      writing = clipboard.writeText(text.replace(/\s/g, ""));
    } catch {
      setOutcome("failed");
      return;
    }
    writing.then(
      () => {
        setOutcome("done");
      },
      () => {
        setOutcome("failed");
      },
    );
  }

  const message = outcome === "idle" ? "" : outcome === "failed" ? t("copy.failed") : t(kind === "key" ? "copy.keyDone" : "copy.traceDone");

  return (
    <>
      <Button variant="secondary" size="sm" onClick={copy}>
        {t(kind === "key" ? "copy.key" : "copy.trace")}
      </Button>
      <span role="status" className="sr-only">
        {message}
      </span>
    </>
  );
}

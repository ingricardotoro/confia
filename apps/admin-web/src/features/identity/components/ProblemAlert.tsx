import { Alert } from "@confia/ui";
import type { Ref } from "react";
import { CopyButton } from "./CopyButton.tsx";

export interface ProblemAlertProps {
  /** Already translated. */
  readonly message: string;
  /** Shown, and copiable, when the server sent one. */
  readonly traceId?: string;
  /** The form moves the focus here after a failed submit. */
  readonly ref?: Ref<HTMLDivElement>;
}

/** The destructive alert of a failure (design.md E5): the message and, with a trace id, the id and a control to copy it. */
export function ProblemAlert({ message, traceId, ref }: ProblemAlertProps) {
  return (
    <Alert ref={ref} variant="destructive">
      <p>{message}</p>
      {traceId === undefined ? null : (
        <p className="mt-2 flex flex-wrap items-center gap-2">
          <code className="break-all">{traceId}</code>
          <CopyButton text={traceId} kind="trace" />
        </p>
      )}
    </Alert>
  );
}

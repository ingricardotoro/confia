import { useId, useSyncExternalStore, type ComponentProps, type MouseEvent } from "react";
import { useUiText } from "../../i18n/use-ui-text";
import { cn } from "../../lib/cn";
import { focusRing } from "../../lib/focus-ring";

/** One failed field: the `id` of its control (the `controlId` of FormField) and the message to show. */
interface FormErrorSummaryItem {
  controlId: string;
  message: string;
}

export interface FormErrorSummaryProps extends Omit<ComponentProps<"div">, "children" | "role" | "aria-labelledby"> {
  /** The failed fields, in the order they are listed. An empty list renders nothing. */
  errors: readonly FormErrorSummaryItem[];
}

// There is nothing to subscribe to: the document is not an observable store. The snapshot is read on
// render and React reads it again once the tree is committed, so a control that sits after the summary
// (it did not exist while the summary rendered) turns its error into a link without an effect.
const subscribe = () => () => undefined;

// A link moves the focus and nothing else: `href="#id"` alone would change the URL and add a history
// entry (docs/ui-ux/03), so the default action is cancelled and the control takes the focus by code.
function focusControl(event: MouseEvent<HTMLAnchorElement>, controlId: string) {
  event.preventDefault();
  document.getElementById(controlId)?.focus();
}

/**
 * The summary at the top of a form after a failed submit (docs/ui-ux/03 section 5, docs/ui-ux/04): the
 * count of failed fields and one link per error that takes the focus to its control. An error whose
 * control is not in the document is shown as text, never as a dead link. `tabIndex` -1 lets the
 * caller focus the container after a failed submit without adding a stop to the tab order; the ref is
 * a plain prop in React 19.
 */
export function FormErrorSummary({ errors, className, ...rest }: FormErrorSummaryProps) {
  const { t } = useUiText();
  const headingId = useId();
  // Identifiers hold no white space, so a space-separated string is a stable snapshot.
  const present = useSyncExternalStore(subscribe, () =>
    errors
      .map(({ controlId }) => controlId)
      .filter((controlId) => document.getElementById(controlId) !== null)
      .join(" "),
  );

  if (errors.length === 0) return null;

  const linkable = new Set(present.split(" "));

  return (
    <div
      tabIndex={-1}
      {...rest}
      role="group"
      aria-labelledby={headingId}
      className={cn(
        "rounded-md border border-(--alert-destructive-border) bg-(--alert-destructive-bg) p-4 text-sm text-(--alert-destructive-fg)",
        focusRing,
        className,
      )}
    >
      <p id={headingId} className="font-semibold">
        {t("formErrorSummary.heading", { count: errors.length })}
      </p>
      <ul className="mt-2 list-disc ps-5">
        {errors.map(({ controlId, message }) => (
          <li key={controlId}>
            {linkable.has(controlId) ? (
              <a
                href={`#${controlId}`}
                onClick={(event) => {
                  focusControl(event, controlId);
                }}
                className={cn("inline-block min-h-6 rounded-sm font-medium underline underline-offset-2", focusRing)}
              >
                {message}
              </a>
            ) : (
              message
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

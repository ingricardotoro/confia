import { X } from "lucide-react";
import { Dialog as DialogPrimitive } from "radix-ui";
import { useRef, type ReactNode } from "react";
import { IconButton } from "../../atoms/icon-button";
import { useUiText } from "../../i18n/use-ui-text";
import { cn } from "../../lib/cn";

export interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Names the dialog. Required: a dialog without a name is announced as nothing. */
  title: string;
  /** Says what the dialog is for. Required too, so the dialog is always described. */
  description: string;
  /** The form or content. The first focusable control inside it receives the focus on open. */
  children: ReactNode;
  /** Actions row, for example the cancel and submit buttons of a form. */
  footer?: ReactNode;
  /** Where the dialog mounts. Defaults to the document body; stories and tests may scope it. */
  container?: HTMLElement | null;
  className?: string;
}

// A modal dialog on Radix: focus is trapped inside, Escape closes it, the page behind is inert and the
// focus returns to the control that opened it. The close button comes after the content in the DOM so
// that opening a form puts the focus on its first field and not on the corner button; it is placed in
// the corner by CSS.
export function Dialog({ open, onOpenChange, title, description, children, footer, container, className }: DialogProps) {
  const { t } = useUiText();
  // Radix returns the focus only to its own Trigger. This dialog is controlled, so whatever control
  // opened it (a button, a row action) is remembered here and gets the focus back on close.
  const opener = useRef<HTMLElement | null>(null);

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal container={container ?? undefined}>
        <DialogPrimitive.Overlay className="fixed inset-0 z-(--z-overlay) bg-text-primary/40" />
        <DialogPrimitive.Content
          onOpenAutoFocus={() => {
            opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
          }}
          onCloseAutoFocus={(event) => {
            if (opener.current?.isConnected === true) {
              event.preventDefault();
              opener.current.focus();
            }
          }}
          className={cn(
            "fixed top-1/2 left-1/2 z-(--z-overlay) flex max-h-[90vh] w-[calc(100vw-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 flex-col gap-4 overflow-y-auto rounded-lg border border-border-subtle bg-bg-surface-elevated p-6 text-text-primary shadow-xl",
            className,
          )}
        >
          <div className="flex flex-col gap-1 pr-10">
            <DialogPrimitive.Title className="text-lg font-semibold">{title}</DialogPrimitive.Title>
            <DialogPrimitive.Description className="text-sm text-text-secondary">{description}</DialogPrimitive.Description>
          </div>
          {children}
          {footer === undefined ? null : <div className="flex flex-wrap justify-end gap-2">{footer}</div>}
          <DialogPrimitive.Close asChild>
            <IconButton label={t("dialog.close")} variant="subtle" size="sm" className="absolute top-3 right-3">
              <X />
            </IconButton>
          </DialogPrimitive.Close>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

export interface BackLinkProps {
  readonly href: string;
  /** Already translated: "Back to the <list>". */
  readonly label: string;
  readonly onBack: () => void;
}

/**
 * The way back from a detail page whose record is not known yet (loading, error, not found), so the breadcrumb that
 * names it cannot be built. It takes the breadcrumb slot of the page header, in the same size and weight. A modified
 * click keeps its browser meaning (new tab, new window).
 */
export function BackLink({ href, label, onBack }: BackLinkProps) {
  return (
    <a
      href={href}
      className="w-fit rounded-sm text-xs font-medium text-text-secondary underline underline-offset-2 hover:text-text-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
      onClick={(event) => {
        if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        onBack();
      }}
    >
      {label}
    </a>
  );
}

import type { ReactNode, Ref } from "react";
import { cn } from "../../lib/cn";

export interface PageHeaderProps {
  /** Already translated. It is the heading of the page, or of the view when `headingLevel` is 2. */
  title: string;
  description?: string;
  /** Usually a Breadcrumb, shown above the title. */
  breadcrumb?: ReactNode;
  /** A badge beside the title: the state of the record on a detail page. */
  status?: ReactNode;
  /** The actions on the right: secondary ones first, the single primary one last. */
  actions?: ReactNode;
  /** Lets the page move the focus to the heading when it appears. The heading is then focusable by code only. */
  headingRef?: Ref<HTMLHeadingElement>;
  headingId?: string;
  headingLevel?: 1 | 2;
  /** Below the title: for example a strip of key figures. */
  children?: ReactNode;
  className?: string;
}

// The head of a page or of a view (docs/ui-ux/07 section 6.2): breadcrumb, title with its state,
// description, and the actions. It makes no request and takes no number to operate on.
export function PageHeader({
  title,
  description,
  breadcrumb,
  status,
  actions,
  headingRef,
  headingId,
  headingLevel = 1,
  children,
  className,
}: PageHeaderProps) {
  const Heading = headingLevel === 1 ? "h1" : "h2";
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      {breadcrumb}
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <div className="flex flex-wrap items-center gap-2">
            <Heading
              ref={headingRef}
              id={headingId}
              tabIndex={headingRef === undefined ? undefined : -1}
              className={cn("font-semibold tracking-tight text-text-primary outline-none", headingLevel === 1 ? "text-xl" : "text-base")}
            >
              {title}
            </Heading>
            {status}
          </div>
          {description === undefined ? null : <p className="text-[13px] leading-5 text-text-secondary">{description}</p>}
        </div>
        {actions === undefined ? null : <div className="flex flex-wrap items-center gap-2 self-center">{actions}</div>}
      </div>
      {children}
    </div>
  );
}

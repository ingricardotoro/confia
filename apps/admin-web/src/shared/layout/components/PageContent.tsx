import { PageHeader } from "@confia/ui";
import { useEffect, useRef, type ReactNode } from "react";

export interface PageContentProps {
  /** Already translated. It is the level 1 heading and the document title, so it differs on every page. */
  readonly title: string;
  /** Already translated. A line under the title that says what the page is for. */
  readonly description?: string | undefined;
  readonly children?: ReactNode;
}

/**
 * What every page inside the shell shares: one level 1 heading that takes the focus when the page
 * appears, and the document title. It renders no `main`: the shell owns that landmark.
 */
export function PageContent({ title, description, children }: PageContentProps) {
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    document.title = title;
    heading.current?.focus();
  }, [title]);

  return (
    <div className="flex flex-col gap-3">
      <PageHeader title={title} headingRef={heading} {...(description === undefined ? {} : { description })} />
      {children}
    </div>
  );
}

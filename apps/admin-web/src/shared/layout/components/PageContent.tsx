import { useEffect, useRef, type ReactNode } from "react";

export interface PageContentProps {
  /** Already translated. It is the level 1 heading and the document title, so it differs on every page. */
  readonly title: string;
  readonly children?: ReactNode;
}

/**
 * What every page inside the shell shares: one level 1 heading that takes the focus when the page
 * appears, and the document title. It renders no `main`: the shell owns that landmark.
 */
export function PageContent({ title, children }: PageContentProps) {
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    document.title = title;
    heading.current?.focus();
  }, [title]);

  return (
    <div className="flex flex-col gap-4">
      <h1 ref={heading} tabIndex={-1} className="text-2xl font-semibold text-text-primary outline-none">
        {title}
      </h1>
      {children}
    </div>
  );
}

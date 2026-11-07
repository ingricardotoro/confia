import { useEffect, useRef, type ReactNode } from "react";

export interface ScreenLayoutProps {
  /** Already translated. It is the heading and the document title, so it differs on every screen. */
  readonly title: string;
  readonly children?: ReactNode;
}

/**
 * What every screen of the app shares (design.md D5, SS32): one `main`, one level 1 heading that takes the
 * focus when the screen appears, and the document title. `tabIndex` -1 lets code move the focus there
 * without adding a stop to the tab order.
 */
export function ScreenLayout({ title, children }: ScreenLayoutProps) {
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    document.title = title;
    heading.current?.focus();
  }, [title]);

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-md flex-col justify-center gap-6 p-6">
      <h1 ref={heading} tabIndex={-1} className="text-2xl font-semibold text-text-primary outline-none">
        {title}
      </h1>
      {children}
    </main>
  );
}

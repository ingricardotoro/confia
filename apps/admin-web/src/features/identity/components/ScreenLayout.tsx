import { useEffect, useRef, type ReactNode } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";

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
  const { t } = useAdminText();
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    document.title = title;
    heading.current?.focus();
  }, [title]);

  return (
    <main className="grid min-h-screen lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
      <aside className="hidden flex-col justify-center gap-1 bg-sidebar-bg px-12 lg:flex">
        <p className="text-3xl font-semibold tracking-tight text-sidebar-active-fg">
          {t("shell.brand")}
        </p>
        <p className="text-base text-sidebar-fg">{t("shell.tagline")}</p>
      </aside>
      <div className="flex items-center justify-center bg-bg-canvas p-6">
        <div className="flex w-full max-w-100 flex-col gap-6 rounded-lg border border-border-subtle bg-bg-surface p-8 shadow-sm">
          <p className="text-sm font-semibold text-text-secondary lg:hidden">
            {t("shell.brand")}
          </p>
          <h1
            ref={heading}
            tabIndex={-1}
            className="text-2xl font-semibold tracking-tight text-text-primary outline-none"
          >
            {title}
          </h1>
          {children}
        </div>
      </div>
    </main>
  );
}

import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import type { i18n as I18nInstance } from "i18next";
import { useEffect, type ReactNode } from "react";
import { I18nextProvider } from "react-i18next";
import { DEFAULT_ADMIN_LANGUAGE } from "../i18n/resources.ts";

export interface AppProvidersProps {
  readonly queryClient: QueryClient;
  readonly i18n: I18nInstance;
  readonly children: ReactNode;
}

/**
 * The two providers every screen needs: server state and text. Both are given, never created here. It also
 * keeps the `lang` of the document equal to the active language (SS33); index.html starts it static.
 */
export function AppProviders({ queryClient, i18n, children }: AppProvidersProps) {
  useEffect(() => {
    const sync = () => {
      document.documentElement.lang = i18n.resolvedLanguage ?? DEFAULT_ADMIN_LANGUAGE;
    };
    sync();
    i18n.on("languageChanged", sync);
    return () => {
      i18n.off("languageChanged", sync);
    };
  }, [i18n]);

  return (
    <QueryClientProvider client={queryClient}>
      <I18nextProvider i18n={i18n}>{children}</I18nextProvider>
    </QueryClientProvider>
  );
}

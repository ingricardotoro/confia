import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import type { i18n as I18nInstance } from "i18next";
import type { ReactNode } from "react";
import { I18nextProvider } from "react-i18next";

export interface AppProvidersProps {
  readonly queryClient: QueryClient;
  readonly i18n: I18nInstance;
  readonly children: ReactNode;
}

/** The two providers every screen needs: server state and text. Both are given, never created here. */
export function AppProviders({ queryClient, i18n, children }: AppProvidersProps) {
  return (
    <QueryClientProvider client={queryClient}>
      <I18nextProvider i18n={i18n}>{children}</I18nextProvider>
    </QueryClientProvider>
  );
}

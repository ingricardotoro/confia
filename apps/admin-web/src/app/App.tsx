import { RouterProvider } from "@tanstack/react-router";
import { BootGate, type BootGateProps } from "./BootGate.tsx";
import type { AppRouter } from "./router.tsx";

export interface AppProps extends Pick<BootGateProps, "session" | "readCsrf"> {
  readonly router: AppRouter;
}

/** The boot gate, and under it the router. */
export function App({ router, session, readCsrf }: AppProps) {
  return (
    <BootGate session={session} readCsrf={readCsrf}>
      <RouterProvider router={router} />
    </BootGate>
  );
}

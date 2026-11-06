import { RouterProvider } from "@tanstack/react-router";
import type { AppRouter } from "./router.tsx";

export function App({ router }: { readonly router: AppRouter }) {
  return <RouterProvider router={router} />;
}

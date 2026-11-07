import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./app/App.tsx";
import { AppProviders } from "./app/AppProviders.tsx";
import { createQueryClient } from "./app/query-client.ts";
import { createAppRouter } from "./app/router.tsx";
import { createAdminI18n } from "./i18n/create-admin-i18n.ts";

const container = document.getElementById("root");
if (!container) throw new Error("index.html has no #root element.");

createRoot(container).render(
  <StrictMode>
    <AppProviders queryClient={createQueryClient()} i18n={createAdminI18n()}>
      <App router={createAppRouter()} />
    </AppProviders>
  </StrictMode>,
);

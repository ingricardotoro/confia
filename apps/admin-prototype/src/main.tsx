import "@confia/ui/styles.css";
import { uiResources } from "@confia/ui/i18n";
import { RouterProvider } from "@tanstack/react-router";
import { createInstance } from "i18next";
import ICU from "i18next-icu";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { I18nextProvider } from "react-i18next";
import { router } from "./router";

// Only the design-system catalog is registered: the prototype's own text is hard-coded Spanish.
const i18n = createInstance();
void i18n.use(ICU).init({
  lng: "es-HN",
  fallbackLng: "es-HN",
  ns: ["ui"],
  defaultNS: "ui",
  resources: structuredClone(uiResources),
  initAsync: false,
  interpolation: { escapeValue: false },
});

const container = document.getElementById("root");
if (!container) throw new Error("No #root element.");
createRoot(container).render(
  <StrictMode>
    <I18nextProvider i18n={i18n}>
      <RouterProvider router={router} />
    </I18nextProvider>
  </StrictMode>,
);

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./app/App.tsx";
import { createAppRouter } from "./app/router.tsx";

const container = document.getElementById("root");
if (!container) throw new Error("index.html has no #root element.");

createRoot(container).render(
  <StrictMode>
    <App router={createAppRouter()} />
  </StrictMode>,
);

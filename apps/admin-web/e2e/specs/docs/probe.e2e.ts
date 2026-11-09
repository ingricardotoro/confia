import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test } from "@playwright/test";

// SS122: the result of the probe of step 0 is written in the design before the design relies on it.
const design = readFileSync(
  join(import.meta.dirname, "..", "..", "..", "..", "..", "openspec", "changes", "admin-web-foundations-and-session", "design.md"),
  "utf8",
);
const section2 = design.slice(design.indexOf("## 2. Resultado de la sonda"), design.indexOf("## 3."));

test("[SS122] the design records the simulation mechanism, the cookie attributes exercised and those not exercised", () => {
  expect(section2.length).toBeGreaterThan(500);
  expect(section2).toContain("Servidor Node detrás del proxy de Vite");
  expect(section2).toContain("**Atributos ejercitados**");
  expect(section2).toContain("**No ejercitados:**");
});

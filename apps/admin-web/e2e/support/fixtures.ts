import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { GateCombination } from "./a11y-report.ts";

// The routes that exist only in tests (design.md D15): HTML files served by page.route from the page's own
// origin. Nothing in the app or in the build knows them (AW15).
const FIXTURES = join(import.meta.dirname, "..", "fixtures", "a11y");

const EXPECTED_HEADING = {
  inaccessible: "Ruta inaccesible de prueba",
  "stuck-loading": "Contenido listo",
  "dark-only-contrast": "Contraste solo en el tema oscuro",
  "moderate-only": "Violación moderada de prueba",
} as const;
export type FixtureName = keyof typeof EXPECTED_HEADING;

export function fixtureCombination(name: FixtureName): GateCombination {
  const route = `/__a11y-fixtures__/${name}.html`;
  return {
    route,
    state: name === "stuck-loading" ? "loading" : "ready",
    async arrange(page) {
      const body = readFileSync(join(FIXTURES, `${name}.html`), "utf8");
      await page.route(`**${route}`, (request) => request.fulfill({ status: 200, contentType: "text/html; charset=utf-8", body }));
      await page.goto(route);
    },
    expected: (page) => page.getByRole("heading", { name: EXPECTED_HEADING[name] }),
  };
}

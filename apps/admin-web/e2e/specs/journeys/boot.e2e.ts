import { expect, seedSession, test } from "../../support/scenario.ts";

// The boot of the app in Chromium against the production build and the simulated API (AW02, AW03, AW05,
// AW06, AW12). The simulator is a simulation, not the real stack.
test.describe("the boot", () => {
  test("[AW02] renders the root with neither uncaught page errors nor console errors, with and without a session", async ({ page }) => {
    const problems: string[] = [];
    page.on("pageerror", (error) => problems.push(`pageerror: ${error.message}`));
    page.on("console", (message) => {
      if (message.type() === "error") problems.push(`console: ${message.text()}`);
    });

    await page.goto("/");
    await expect(page.getByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeVisible();
    await seedSession(page);
    await expect(page.getByRole("button", { name: "Cerrar sesión" })).toBeVisible();

    expect(problems).toEqual([]);
  });

  test("[AW03] has the semantic tokens of @confia/ui on :root and shows its catalog text, not a key", async ({ page }) => {
    await page.route("**/api/v1/auth/sessions/refresh", (route) => route.abort("failed"));
    await seedSession(page);

    const retry = page.getByRole("button", { name: "Reintentar" });
    await expect(retry).toBeVisible();
    await expect(retry).toHaveText("Reintentar");
    const canvas = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--color-bg-canvas").trim());
    expect(canvas).not.toBe("");
  });

  test("[AW05] follows the system color preference, with no data-theme on the root", async ({ page }) => {
    const background = async (scheme: "light" | "dark") => {
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto("/");
      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      return page.evaluate(() => {
        // The token resolved by the browser: a probe element takes the computed value of the variable.
        const probe = document.body.appendChild(document.createElement("div"));
        probe.style.backgroundColor = "var(--color-bg-canvas)";
        const token = getComputedStyle(probe).backgroundColor;
        probe.remove();
        return { token, actual: [document.documentElement, document.body].map((node) => getComputedStyle(node).backgroundColor).find((color) => color !== "rgba(0, 0, 0, 0)") ?? "transparent", theme: document.documentElement.getAttribute("data-theme") };
      });
    };

    const light = await background("light");
    const dark = await background("dark");

    expect(light.actual).not.toBe(dark.actual);
    expect(light.actual).toBe(light.token);
    expect(dark.actual).toBe(dark.token);
    expect([light.theme, dark.theme]).toEqual([null, null]);
  });

  test("[AW06] has no control for the theme on any screen", async ({ page }) => {
    const theme = /tema|claro|oscuro|theme|dark|light/i;
    const controls = ["button", "switch", "checkbox", "radio", "combobox", "menuitem", "link"] as const;
    const noThemeControl = async () => {
      for (const role of controls) await expect(page.getByRole(role, { name: theme })).toHaveCount(0);
    };

    await page.goto("/no-existe");
    await expect(page.getByRole("heading", { name: "Esta página no existe." })).toBeVisible();
    await noThemeControl();
    await page.goto("/iniciar-sesion");
    await expect(page.getByRole("heading", { name: "Iniciar sesión" })).toBeVisible();
    await noThemeControl();
    await seedSession(page);
    await expect(page.getByRole("button", { name: "Cerrar sesión" })).toBeVisible();
    await noThemeControl();
  });

  test("[AW12] runs under the CSP of vite preview with no securitypolicyviolation, and the detector does detect", async ({ page, cspViolations }) => {
    const response = await page.goto("/");
    const policy = response?.headers()["content-security-policy"] ?? "";
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    expect(policy).toContain("script-src 'self'");
    expect(policy).not.toMatch(/unsafe-inline|unsafe-eval/);
    expect(cspViolations).toEqual([]);

    // The detector itself: an inline style under the same policy must be reported.
    await page.evaluate(() => {
      document.head.insertAdjacentHTML("beforeend", "<style>body{margin:1px}</style>");
    });
    await expect.poll(() => cspViolations.length).toBeGreaterThan(0);
    cspViolations.length = 0;
  });
});

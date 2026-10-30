// The custom commands of the "a11y" Vitest project (vitest.config.ts). They run in Node, where the
// browser cannot: one writes the report CI uploads, the other drives the page's media emulation,
// which vitest/browser does not expose.
declare module "vitest/browser" {
  interface BrowserCommands {
    writeA11yReport(json: string): Promise<void>;
    emulateColorScheme(scheme: "light" | "dark"): Promise<void>;
  }
}

export {};

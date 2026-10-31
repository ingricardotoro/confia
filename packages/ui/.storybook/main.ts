import type { StorybookConfig } from "@storybook/react-vite";
import { mergeConfig } from "vite";

// Storybook is the visual catalog of @confia/ui (docs/ui-ux/02 section 8) and the workbench of its
// accessibility gate (design.md decision 8). Only CSF stories in .stories.tsx files are discovered:
// MDX is code, and it stays out of the package (design.md section 10, executable MDX).
const config: StorybookConfig = {
  stories: ["../src/**/*.stories.tsx"],
  addons: ["@storybook/addon-a11y"],
  framework: "@storybook/react-vite",
  // No usage data leaves the machine or the CI runner.
  core: { disableTelemetry: true },
  viteFinal: async (viteConfig) => {
    // Loaded here so that importing this file, as the MDX guard does, does not load Tailwind.
    const { default: tailwindcss } = await import("@tailwindcss/vite");
    return mergeConfig(viteConfig, { plugins: [tailwindcss()] });
  },
};

export default config;

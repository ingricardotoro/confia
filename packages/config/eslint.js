// Shared ESLint flat configuration (frontend-monorepo-and-contracts-pipeline, design.md decision 7).
// typescript-eslint in its strict, type-checked mode: each package passes its own directory so the
// project service finds that package's tsconfig.json.
import js from "@eslint/js";
import tseslint from "typescript-eslint";

/**
 * Mirrors two of the dependency-cruiser rules in .dependency-cruiser.cjs so the editor flags them
 * before CI does (design.md decision 8): packages/contracts is reached only through its admin and
 * portal entry points, and the portal never imports the administrative contract.
 *
 * @param {{ portal?: boolean }} [options]
 */
export function contractImportRestrictions({ portal = false } = {}) {
  const patterns = [
    {
      group: ["@confia/contracts/src/*", "**/contracts/src/generated/**"],
      message:
        "Import @confia/contracts/admin or @confia/contracts/portal, never a generated file or an internal path.",
    },
  ];
  if (portal) {
    patterns.push({
      group: ["@confia/contracts/admin"],
      message: "The portal never compiles against the administrative contract (ADR-0003).",
    });
  }
  return { rules: { "no-restricted-imports": ["error", { patterns }] } };
}

/**
 * @param {{ tsconfigRootDir: string, ignores?: string[], portal?: boolean }} options
 */
export function confiaEslint({ tsconfigRootDir, ignores = [], portal = false }) {
  return tseslint.config(
    { ignores: ["node_modules/**", "dist/**", ...ignores] },
    js.configs.recommended,
    ...tseslint.configs.strictTypeChecked,
    {
      languageOptions: {
        parserOptions: { projectService: true, tsconfigRootDir },
      },
    },
    contractImportRestrictions({ portal }),
    {
      files: ["**/*.js"],
      ...tseslint.configs.disableTypeChecked,
    },
  );
}

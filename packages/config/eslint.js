// Shared ESLint flat configuration (frontend-monorepo-and-contracts-pipeline, design.md decision 7).
// typescript-eslint in its strict, type-checked mode: each package passes its own directory so the
// project service finds that package's tsconfig.json.
import js from "@eslint/js";
import tseslint from "typescript-eslint";

/**
 * @param {{ tsconfigRootDir: string, ignores?: string[] }} options
 */
export function confiaEslint({ tsconfigRootDir, ignores = [] }) {
  return tseslint.config(
    { ignores: ["node_modules/**", "dist/**", ...ignores] },
    js.configs.recommended,
    ...tseslint.configs.strictTypeChecked,
    {
      languageOptions: {
        parserOptions: { projectService: true, tsconfigRootDir },
      },
    },
    {
      files: ["**/*.js"],
      ...tseslint.configs.disableTypeChecked,
    },
  );
}

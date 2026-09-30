import { confiaEslint } from "@confia/config/eslint";

// src/generated/ is orval's output: never edited by hand, so never linted either.
export default confiaEslint({
  tsconfigRootDir: import.meta.dirname,
  ignores: ["src/generated/**"],
});

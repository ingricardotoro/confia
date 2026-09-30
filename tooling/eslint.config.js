import { confiaEslint } from "@confia/config/eslint";

// The fixtures break the dependency rules on purpose; they are data for the tests, never linted.
export default confiaEslint({
  tsconfigRootDir: import.meta.dirname,
  ignores: ["dependency-fixtures/**"],
});

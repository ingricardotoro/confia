import { confiaEslint } from "@confia/config/eslint";

export default confiaEslint({ tsconfigRootDir: import.meta.dirname, ui: true });

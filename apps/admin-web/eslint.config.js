import { confiaEslint } from "@confia/config/eslint";

// start.mjs is a plain Node script run by `pnpm mock:api`: it is not part of the TypeScript project, so
// typed linting cannot parse it. The TypeScript it starts (server.ts and the rest of e2e) is linted.
export default [
  ...confiaEslint({ tsconfigRootDir: import.meta.dirname, app: true, react: true }),
  { ignores: ["e2e/mock-api/start.mjs"] },
];

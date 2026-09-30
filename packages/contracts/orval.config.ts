import { defineConfig } from "orval";

// One output per API surface, each generated from its approved snapshot
// (frontend-monorepo-and-contracts-pipeline, design.md decisions 1, 5 and 7). Component schemas go
// to schemas.ts as Zod schemas with their TypeScript types; operations, once the first controller
// exists, go to index.ts. Probe S3 (apply-progress.md, task 2.3) confirmed orval emits the
// component schemas from a document with no operation at all.
function surface(name: "admin" | "portal") {
  return {
    input: { target: `../../apps/api/openapi/${name}.openapi.json` },
    output: {
      target: `src/generated/${name}/index.ts`,
      schemas: { path: `src/generated/${name}/schemas.ts`, type: "zod" as const, mode: "single" as const },
      client: "zod" as const,
      mode: "single" as const,
    },
  };
}

export default defineConfig({
  admin: surface("admin"),
  portal: surface("portal"),
});

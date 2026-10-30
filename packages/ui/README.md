# @confia/ui

CONFIA design system: tokens, atoms, molecules and domain components (`docs/ui-ux/`). The package is
pure presentation: it never makes a request and never computes an amount.

Run from the repository root:

| Command | What it does |
|---|---|
| `pnpm --filter @confia/ui lint` | ESLint, strict type-checked |
| `pnpm --filter @confia/ui typecheck` | `tsc --noEmit` |
| `pnpm --filter @confia/ui test` | Vitest `unit` project (jsdom) with V8 coverage; fails below 80 % |
| `pnpm --filter @confia/ui build` | `tsc --noEmit` for now; Storybook replaces it later in this change |

Tests live next to the code as `*.test.ts` and `*.test.tsx`.

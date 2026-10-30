# @confia/ui

CONFIA design system: tokens, atoms, molecules and domain components (`docs/ui-ux/`). The package is
pure presentation: it never makes a request and never computes an amount.

Run from the repository root:

| Command | What it does |
|---|---|
| `pnpm --filter @confia/ui lint` | ESLint, strict type-checked |
| `pnpm --filter @confia/ui typecheck` | `tsc --noEmit` |
| `pnpm --filter @confia/ui test` | Vitest `unit` project (jsdom) with V8 coverage; fails below 80 % |
| `pnpm --filter @confia/ui build` | `storybook build --output-dir dist/storybook`: the visual catalog, and proof that every story compiles |
| `pnpm --filter @confia/ui test:a11y` | The accessibility gate: every story, in the light and the dark theme, in real Chromium (see below) |

Tests live next to the code as `*.test.ts` and `*.test.tsx`.

## Accessibility gate

`test:a11y` is separate from `test` on purpose: `pnpm turbo run lint typecheck test build` needs no
browser and stays green on a clean machine. The gate does need Chromium, installed once with:

```
pnpm --filter @confia/ui exec playwright install chromium
pnpm --filter @confia/ui test:a11y
```

Every story under `atoms/`, `molecules/` and `domain/` runs through axe-core in both themes with the real
stylesheet. Critical and serious violations fail the run, and so does an `incomplete` color-contrast result;
moderate and minor findings are written to `reports/a11y/report.json` (git-ignored; CI uploads it). The run
also fails when it discovers zero stories. Deliberately inaccessible stories live in
`src/a11y/__fixtures__/`, outside every discovery pattern, so the gate can prove it rejects them.

Vitest's default API port (63315) is reserved by Windows on some machines (`listen EACCES`); the config uses
51730, and `CONFIA_VITEST_API_PORT` overrides it. ADR-0026 records the decision.

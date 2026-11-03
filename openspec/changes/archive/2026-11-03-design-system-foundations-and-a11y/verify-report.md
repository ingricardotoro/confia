# Informe de verificación: fundamentos del sistema de diseño y verificación de accesibilidad

> Copia del texto de la observación Engram #751 (`sdd/design-system-foundations-and-a11y/verify-report`, 2026-11-02). Es una instantánea intermedia del momento de la verificación. Su texto no se ha modificado. El estado de cierre, con las correcciones y los seguimientos posteriores, está en `archive-report.md`.

---

#751 [architecture] sdd/design-system-foundations-and-a11y/verify-report

**What**: SDD verify of change design-system-foundations-and-a11y (frontend 13a), worktree C:\dev\confia-frontend detached at origin/main ef6db61 (12 PRs #215-#235 merged). Verdict: PASS WITH WARNINGS. CRITICAL 0, WARNING 5, SUGGESTION 6. Read-only run; openspec verify-report file NOT written (owner instruction: no file edits) - only this Engram copy.

**Checks run (2026-11-02, one at a time)**
- `pnpm --filter @confia/ui test`: exit 0, 52 files / 524 tests; coverage stmts 99.4%, branches 99.3%, funcs 100%, lines 100% (threshold 80).
- `pnpm --filter @confia/ui test:a11y`: exit 0, 8 files / 159 tests, Chromium; reports/a11y/report.json 156 entries (78 stories x light/dark), 0 blocking, 0 reported.
- `pnpm --filter @confia/ui typecheck` (tsc --noEmit): exit 0.
- `pnpm --filter @confia/ui lint` (eslint .): exit 0.
- Not run (outside authorized commands): tooling tests (dependency-cruiser rules, ci-workflow, eslint-ui-rules), turbo full pipeline, pnpm install, audit. Static check: `git diff 5c5e5e8^ ef6db61 -- .github/workflows/ci.yml` = 36 insertions, 0 deletions (BX18). Remote: PR #226 and #235 checks all SUCCESS incl. `design system accessibility` (BX14, BX19).

**Tasks**: 15/15 [x] in tasks.md; apply-progress has narrative RED/GREEN/demonstration evidence per task (strict_tdd true; no tabular TDD table, evidence not re-executable historically).

**Warnings**
1. DS02/Req "punto de entrada": spec says exactly 17 components; packages/ui/src/index.ts:3,11 also exports buttonVariants and RadioGroupItem (19 names, asserted by public-api.test.ts). Spec must be corrected.
2. DS16: packages/ui/src/atoms/button/Button.tsx:13 keeps `transition-colors`, which in Tailwind v4 animates outline-color: Button focus ring fades in from text color. Only jsdom class test (Button.test.tsx:69); no Chromium computed-style test like Input/Textarea/Checkbox.
3. ErrorState.tsx:33 `retryLabel ?? t("common.retry")` accepts "" (blank accessible name); ErrorState.tsx:29 / EmptyState.tsx:25 render empty <p> for description="".
4. BX12 remote red visibility never demonstrated on GitHub (only local demo in 3.2).
5. docs/ui-ux/02-sistema-de-diseno-y-componentes.md:375 still names nonexistent `--color-text-muted` (impl uses text-text-secondary).

**Suggestions**: queryAllByRole("paragraph") misses explicit-role <p>; Skeleton query status with {hidden:true}; fake timers in no-network test (25 ms real wait); MoneyAmount colorBySign negative = overdue color for credit balances (product question, MoneyAmount.tsx:22); designer review of ui.json and two FormField questions; slow ui tests under parallel load (5 s timeouts).

**Spec corrections for sdd-archive**
1. design-system Req 1 + DS02: 17 components plus two companions (RadioGroupItem, buttonVariants), 19 names, no primitive token.
2. DS14: `--font-family-sans` -> `--font-sans` (the @theme variable) (Appendix B R-5).
3. BX06/BX07: failure names key and file; catalog identified by `UiKey` type from locales/es-HN/ui.json (R-1).
4. BX35 (and BX33 partly): value import from contracts is caught by dependency-cruiser `ui-contracts-type-only` ("verificación de dependencias"), not ESLint (R-2).
5. DS63: Input error state story is `Invalid`; EmptyState/ErrorState `Default` counts as their empty/error story.
6. i18n Req: "inglés preparado" = adding locales/en/ui.json and registering in supportedLngs suffices; no en file exists (design.md:221).
7. design-system becomes new main spec openspec/specs/design-system/spec.md with header aligned to other main specs (drop "Borrador, pendiente de aprobación"); build-integrity: append ADDED reqs, replace "Reglas de dependencia del frontend" (main spec line 1273) and drop the "(Previously: ...)" note.

**Learned**: tooling-level rules (dependency-cruiser, ci-workflow) live in tooling tests, not in the ui package scripts, so a ui-only verify cannot rerun them.

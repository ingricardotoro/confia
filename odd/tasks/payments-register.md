# Feature: payments-register (F4, payments cut 2)

Objective: register cash payments and declared transfers, post confirmed ones to the ledger and allocate them to charges.
Branch: `feat/payments-register`. Delivery: ask-on-risk (split PRs over 800 lines). Runners: Maven (`-pl app -am -Djacoco.skip=true`, targeted tests), Vitest, Playwright.

## Specs

- S1: The cut note `docs/cortes/payments-register.md` is the specification: every rule, endpoint, screen, edge case and acceptance criterion in it.
- S2: "No toca el saldo": a declared transfer creates no ledger entry and no allocation until it is confirmed (user decision, 2026-11-20).
- S3: Backend first, then contract (`admin.openapi.json`, `packages/contracts`), then frontend, then reviews (confia-code-reviewer and confia-security-auditor), then the roadmap line.

## Tasks

| ID | Specs | Route | Status | Evidence |
|---|---|---|---|---|
| T1 | S1, S2 | delegated (context backstop) to confia-backend-dev | done (backend) | `41aa4a1c` migration and domain; `7418f0dd` ledger, cashbox and charges APIs; `873f5405` use cases, adapters and routes; `541308a2` cashier scope, guards and OpenAPI snapshot; the cashbox IT fix and the cut note section are in the commit that follows |
| T2 | S1, S3 | delegated to the frontend developer | done (frontend), journey and gate states written but not run (ports 5173 and 8099 busy) | `66611134` API client, problem codes, paid and outstanding columns; `b73d9bef` register, list and detail screens; `cf767fef` cash and bank in the posting map; `080d77c6` simulated API; `2a0c5618` journeys, gate states and server routes; the cut note section is in the commit that follows |
| T3 | S3 | delegated reviewers; backend fixes delegated to confia-backend-dev | backend fixes done (code review M1-M4, m1-m3, s1; security MEDIO-1/2, BAJO-1/2/4, INFO-1/3); frontend fixes done (Playwright results in the log of the cut note) | `63afd808` review fixes (V35 in place, `rejected` status, 403 self-approval, cash day, void guard lock, deferred decision trigger, outstanding filter and settled); `5105a383` docs; frontend: register screen asks the server for `outstanding=true` and pages it, `rejected` status, 403 self-approval, cash-day rule and error, simulated API updated, posting-map role union (see the commits `fix(admin-web): ...`) |
| T4 | S3 | inline | done | roadmap line and exit criteria INV-17, INV-20 and INV-24 marked; cut note section "Integración en `main`"; branch `docs/payments-register-close` |

T1 = migration, domain, application, infrastructure, web, ledger/cashbox/charges integration, tests and OpenAPI snapshot.
T2 = contracts, screens, mock API, journeys. T3 = reviews and fixes. T4 = roadmap line and cut note "Lo construido".

## Log

- L1 (2026-11-20, user): "si, sigamos con pagos"; transfer decision: "No toca el saldo (Recomendado)"; note approved: "me parecen bien".
- L2: cut note committed as `cb13cf8c` on `feat/payments-register`.
- L3 (2026-11-21): reviews done. Code review: request changes (B1 split, B2 audit, M1-M4, m1-m6, s1-s2). Security audit: no critical/high; MEDIO-1 void guard lock, MEDIO-2 cash backdating, BAJO-1..4, INFO-1..3.
- L4 (2026-11-21, user): cash receipt date: "Solo el día de la sesión (Recomendado)". Parent decisions: fix V35 in place (never applied in production, unmerged); self-approval answers 403 like cashbox; add a `rejected` status distinct from `reversed`.
- L5 (2026-11-22): the cut was split into nine stacked PRs (#341-#349). Fixes the split required: `payment_in` type and label moved to
  part 2; `PaymentsConfiguration` in `AuthorizationEdgeIT` and `paid`/`outstanding`/`settled` in the test responses and the void model in
  part 3; the payments permissions (`payments:read`, `payments:create`, `payments:approve`) declared and enforced in the UI in part 7,
  after the session-permissions chain (#336-#339) reached main.
- L6 (2026-11-23): #349 re-pinned the roadmap in `docs-notes.test.ts`. All nine PRs merged; last is `52b6f429`. `backend verify` took
  24m54s on #348, close to its 25-minute timeout.

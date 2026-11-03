# Informe de archivo: fundamentos del sistema de diseño y verificación de accesibilidad

- **Cambio:** `design-system-foundations-and-a11y` (F0, frontend, cambio 13a)
- **Archivado:** 2026-11-03
- **Modo de almacenamiento:** hybrid (rutas de archivo para los artefactos; Engram `sdd/design-system-foundations-and-a11y/archive-report` para este informe)
- **Base del cambio:** `main` en `68bcf07`; rama `change/design-system-foundations-and-a11y-7-archive`
- **Commits de archivo:** `0519ccc` (fusión de especificaciones) y el commit de archivo que registra el movimiento de la carpeta (ver «Commits»)
- **Estado de cierre:** implementación completa. 15 de 15 tareas `[x]` en `tasks.md` (leído en este paso), 0 casillas abiertas. Verificación con advertencias: `PASS WITH WARNINGS`, 0 CRITICAL, 5 WARNING, 6 SUGGESTION (`verify-report.md`, Engram #751, instantánea del 2026-11-02).
- **Aprobación:** el propietario aprobó el archivo el 2026-11-03.

## Fusión de especificaciones por vía de respaldo determinista

`gentle-ai sdd-archive-compose` no está disponible en gentle-ai 4.0.0. El propietario aprobó el 2026-11-03 una fusión determinista de respaldo, como en el archivo del cambio 9 (`68bcf07`, `c18e9f2`). La fusión la hizo un script, no ediciones a mano.

- **Scripts:** `C:/dev/confia-scratch/archive-merge-13a/merge_specs.py` y `check_specs.py`. Son copias de los scripts del cambio 9, con la ruta del delta apuntando a este cambio. Los originales no se modificaron.
- **Reglas:** aplica en el orden RENAMED, MODIFIED, REMOVED, ADDED; empareja por el encabezado exacto `### Requisito: ...`; aborta sin escribir si un encabezado MODIFIED, REMOVED o RENAMED no aparece exactamente una vez, o si un ADDED ya existe.
- **Lectura de verificación:** `check_specs.py`, independiente de la fusión, contra `git show HEAD:` y contra el delta.
- **Finales de línea:** LF en todas las especificaciones principales; se conservan.

| Dominio | Acción | ADDED | MODIFIED | REMOVED | RENAMED | Requisitos antes → después | Escenarios antes → después | Lectura |
|---------|--------|-------|----------|---------|---------|----------------------------|----------------------------|---------|
| `design-system` | nueva especificación principal | — | — | — | — | nueva: 17 requisitos | nueva: 74 escenarios | copia mecánica del delta completo (no era un delta); `diff -r` vacío; único cambio posterior: línea de estado (ver abajo) |
| `build-integrity` | fusión | 5 | 1 | 0 | 0 | 94 → 99 | 287 → 321 | PASS; 93 requisitos no tocados idénticos a `HEAD`; 0 diferencias de separador |

Comprobaciones de `build-integrity`:

- Encabezados que no coincidieron: ninguno. El encabezado MODIFIED apareció exactamente una vez y ningún ADDED ya existía.
- Escenarios: 37 encabezados de escenario de los deltas ADDED y MODIFIED aparecen en la especificación fusionada. Recuento de la aritmética: 287 − 3 (bloque MODIFIED anterior) + 13 (bloque MODIFIED nuevo) + 24 (ADDED) = 321.
- Preámbulo (secciones antes de los requisitos): byte a byte igual a `HEAD`.
- Las 14 ocurrencias de «(Previously: ...)» que ya existían en `HEAD` pertenecen a otros requisitos, que no se tocaron. El bloque fusionado «Reglas de dependencia del frontend» no lleva ninguna nota (0 líneas añadidas con «Previously»).

Lectura del `design-system` (copia mecánica):

- `cp` del delta a un archivo temporal dentro del directorio de destino, `diff -r` vacío, `mv` al destino. `diff -r` posterior, vacío.
- Único cambio posterior, por encima de la copia: la línea de estado de la cabecera, de `- **Estado:** Borrador, pendiente de aprobación del propietario del producto` a `- **Estado:** Borrador`, para alinearla con `audit-trail`, `build-integrity`, `identity` y `background-jobs`. El cambio se aplicó con `sed` sobre esa línea; el `diff -u` mostró solo esa línea.

## Correcciones aplicadas en los deltas antes de fusionar

Cada corrección se verificó contra el código antes de aplicarla. Todas se aplicaron; ninguna se omitió.

| # | Corrección | Dónde (archivo y línea, tras la corrección) | Verificación en código |
|---|------------|---------------------------------------------|-------------------------|
| 1 | Requisito 1 y DS02: diecisiete componentes más dos compañeros (`RadioGroupItem`, `buttonVariants`), diecinueve nombres, sin token primitivo | `specs/design-system/spec.md:22-32` (requisito) y `:44-45` (escenario «El conjunto exportado coincide con el inventario») | `packages/ui/src/index.ts` exporta 19 nombres; `public-api.test.ts:31` define `COMPANIONS = ["RadioGroupItem", "buttonVariants"]` y `:38-39` afirma los diecinueve |
| 2 | DS14: `--font-family-sans` → `--font-sans` | `specs/design-system/spec.md:145` | `packages/ui/src/tokens/semantic.css:8` define `--font-sans` dentro de `@theme static` |
| 3 | BX06 y BX07: el fallo nombra la clave y el archivo; el catálogo es el tipo `UiKey` derivado de `packages/ui/src/i18n/locales/es-HN/ui.json` | `specs/build-integrity/spec.md:55-56` (BX06) y `:63-64` (BX07) | `keys.check.test.ts:21-23` y `:30-32` comprueban archivo, clave y `UiKey` |
| 4 | BX35: la importación de un valor de `packages/contracts` la rechaza la regla `ui-contracts-type-only` de la verificación de dependencias, no ESLint | `specs/build-integrity/spec.md:290-292` | `.dependency-cruiser.cjs:69` define `ui-contracts-type-only` |
| 5 | DS63: la historia de error de `Input` se llama `Invalid`; `Default` de `EmptyState` y de `ErrorState` cuenta como su estado de vacío y de error | `specs/design-system/spec.md:557-558` | `Input.stories.tsx:20` (`Invalid`); `EmptyState.stories.tsx:18` y `ErrorState.stories.tsx:18` (`Default`) |
| 6 | Requisito de i18n: «inglés preparado» se cumple con añadir `packages/ui/src/i18n/locales/en/ui.json` y registrar `en` en `supportedLngs`; hoy ese catálogo no existe | `specs/design-system/spec.md:495-498` | no existe `locales/en/`; `create-ui-i18n.ts` tiene `supportedLngs: [DEFAULT_UI_LANGUAGE]` |
| 7 | Eliminar la nota «(Previously: ...)» del requisito MODIFIED «Reglas de dependencia del frontend» | `specs/build-integrity/spec.md`, antes en las líneas 217-219 (eliminadas) | no aplica; la nota no debe llegar a la especificación principal |

Correcciones omitidas: ninguna. Encabezados que fallaron: ninguno.

## Partición de PRs, excepciones de tamaño y discrepancia de recuento

- **Partición:** el cambio se entregó en **16 PR** fusionados en `main`, según su rama: #215 (rama base del cambio, con la especificación), #216 (13a-1), #217 (13a-1b), #218 (13a-2a), #219 (13a-2b), #223 (13a-3a), #224 (13a-3b), #225 (13a-3c), #226 (13a-4a), #227 (13a-4b), #230 (13a-5a1), #231 (13a-5a2), #232 (13a-5b), #233 (13a-6a), #234 (13a-6b), #235 (13a-6c). Son 15 cortes de implementación más la rama base. La partición creció con cortes posteriores a la primera propuesta, como 13a-1b y 13a-5a, que el propietario aprobó en su momento.
- **Corrección del recuento:** el informe de verificación (Engram #751) y el encargo de archivo hablaban de 12 PR. Esa cifra era incorrecta: el historial de `main` y las ramas de los 16 PR la desmienten. Lo confirmó el orquestador el 2026-11-03 con `gh pr view` sobre cada PR.
- **Excepciones de tamaño aprobadas por el propietario:** 811 líneas en el corte 13a-3b (PR #224) y 829 líneas en el corte 13a-4b (PR #227). La correspondencia de PR se toma del nombre de la rama de fusión.

## Evidencia adicional aportada por el orquestador (no reejecutada en este paso)

- `pnpm test` en `tooling`, en `ef6db61`: 8 archivos, 167 pruebas, 0 fallos. Estas pruebas contienen las reglas de dependency-cruiser (`ui-contracts-type-only`) que `verify-report` (#751) no pudo ejecutar. Esta evidencia viene del orquestador; se registra con esa procedencia y no se reejecutó en la fase de archivo.

## Estado final de las advertencias y sugerencias (verify-report #751, instantánea de rango más bajo)

El informe de verificación es una instantánea del 2026-11-02. Su estado «abierto» se contrasta aquí con el código del archivo, para no repetir una afirmación caducada.

| Ítem | Afirmación en #751 | Estado al archivo | Evidencia |
|------|---------------------|-------------------|-----------|
| W1 | DS02: la especificación dice 17 componentes | **Resuelto en la especificación** por la corrección 1 (commit `0519ccc`). El código ya era correcto | `specs/design-system/spec.md:22-32`, `:44-45` |
| W2 | DS16: `transition-colors` en `Button` anima `outline-color` | **Abierto** | `packages/ui/src/atoms/button/Button.tsx:13` aún contiene `transition-colors` |
| W3 | `ErrorState` acepta etiqueta vacía; `<p>` vacíos con `description=""` | **Abierto** | `ErrorState.tsx:33`, `:29`; `EmptyState.tsx:25` |
| W4 | BX12: el enrojecimiento remoto no se demostró en GitHub | **No verificable en este paso** (requiere GitHub); seguimiento | — |
| W5 | `docs/ui-ux/02-...md:375` cita `--color-text-muted` inexistente | **Abierto** | `docs/ui-ux/02-sistema-de-diseno-y-componentes.md:375` |
| S4 | `MoneyAmount` con `colorBySign` pinta negativos con el color de vencido | **Abierto** (pregunta de producto, ver abajo) | `packages/ui/src/domain/money-amount/MoneyAmount.tsx:22` |
| S1, S2, S3, S5, S6 | consultas de prueba, temporizadores, revisión de diseño, tiempos de prueba bajo carga | **No reverificados** en este paso; seguimiento | — |

Las sugerencias del informe, íntegras: `queryAllByRole("paragraph")` no encuentra un `<p>` con rol explícito; consulta de `Skeleton` con `{hidden:true}`; temporizadores falsos en la prueba sin red (espera real de 25 ms); color negativo de `MoneyAmount` (pregunta de producto); revisión de diseño de `ui.json` y de dos preguntas de `FormField`; pruebas de `ui` lentas bajo carga paralela (tiempos de 5 s).

## Pregunta de producto abierta

- **Color del importe negativo de `MoneyAmount` con saldo a favor.** Con `colorBySign`, un importe negativo usa `text-status-overdue-fg` (`MoneyAmount.tsx:22`), el color de «vencido». Un saldo a favor de la persona usuaria, por ejemplo un crédito, se mostraría con color de deuda. La decisión la toma el propietario del producto; no se cambió en este archivo.

## Trazabilidad de observaciones Engram

| Artefacto | Observación | Cómo se leyó |
|-----------|-------------|--------------|
| verify-report | #751 | leída completa (`mem_get_observation`); copia en `verify-report.md` |
| proposal | #705 | índice de búsqueda; el archivo `proposal.md` está en la carpeta archivada |
| spec | #707 | índice de búsqueda; los deltas están en `specs/` (texto completo en archivo) |
| design | #708 | índice de búsqueda; `design.md` está en la carpeta archivada |
| tasks | #710 | índice de búsqueda; `tasks.md` leído en archivo: 15 de 15 `[x]` |
| apply-progress | #720 | índice de búsqueda (topic `sdd/design-system-foundations-and-a11y/apply-progress`); `apply-progress.md` está en la carpeta archivada como instantánea intermedia |
| explore | #703 | índice de búsqueda; no se leyó en este paso |

Observación de artefacto ausente: `exploration.md` no existe en la carpeta de `openspec/`; su contenido está solo en Engram (#703).

## Artefactos

| Artefacto | Ubicación | Estado |
|-----------|-----------|--------|
| proposal.md, design.md | `openspec/changes/archive/2026-11-03-design-system-foundations-and-a11y/` | presentes |
| specs/ (deltas) | misma carpeta | presentes; corregidos en `0519ccc` antes de fusionar |
| tasks.md | misma carpeta | 15 de 15 `[x]`, 0 abiertas |
| apply-progress.md | misma carpeta | instantánea intermedia; no se corrigió |
| verify-report.md | misma carpeta | copia del texto de Engram #751 (instantánea intermedia) |
| archive-report.md | este informe | presente |
| exploration.md | no existe en `openspec/` | ausente; contenido en Engram #703 |

## Movimiento

- **Origen:** `openspec/changes/design-system-foundations-and-a11y`
- **Destino:** `openspec/changes/archive/2026-11-03-design-system-foundations-and-a11y`
- **Mecanismo:** instantánea recursiva previa con `cp -R`, después `git mv` del directorio. El origen ya no existe.
- **Lectura (`diff -r` entre la instantánea previa y el destino), salida literal:**

```
(salida vacía; exit 0)
```

- **Estado de la carpeta de origen:** `openspec/changes/design-system-foundations-and-a11y` ya no existe. `openspec/changes/foundations-plan/` no se tocó.

## Commits

- `0519ccc` `docs(sdd): merge the design system specs into the main specs`: 4 archivos; correcciones de deltas, fusión de `build-integrity` y especificación nueva `design-system`.
- Commit de archivo `docs(sdd): archive design-system-foundations-and-a11y`: `git mv` de la carpeta, `verify-report.md` y este `archive-report.md`.

## Estado de la sesión

No se ha subido nada a remoto. Los commits quedan en la rama local `change/design-system-foundations-and-a11y-7-archive` para revisión del propietario.

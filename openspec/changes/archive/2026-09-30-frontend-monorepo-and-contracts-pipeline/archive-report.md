# Informe de archivado: `frontend-monorepo-and-contracts-pipeline`

- **Fecha de archivado:** 2026-09-30
- **Cambio 3 de F0.** Cierra el **criterio de salida 7** («OpenAPI 3.1 se genera y publica como
  artefacto versionado») y la parte de pnpm y Turborepo del entregable 1.
- **Tareas:** 13 de 13 cerradas.
- **Entregado en tres pull requests**, uno por corte: #64 (3a, backend), #65 (3b, espacio de trabajo y
  contratos) y #66 (3c, reglas e integración continua), más el PR de este archivado.
- **Verificación:** `verify-report.md`, con 0 CRITICAL, 4 WARNING y 4 SUGGESTION. WARNING-1 y
  WARNING-3 se resolvieron en este archivado.

## Capacidades publicadas

Fusión **a mano**, como en los cambios anteriores (`Gentleman-Programming/gentle-ai#4797`), con las
tres comprobaciones: prefijo original intacto, SHA-256 de los bytes añadidos idéntico al del delta y
conteos que cuadran.

| Capacidad | Antes | Después | Delta aplicado |
|---|---|---|---|
| `build-integrity` | 44 req / 107 escen | **51 req / 125 escen** | 7 añadidos (18 escen) |

## Lo que se entregó

- **Backend:**
  - springdoc 3.1.1;
  - un documento OpenAPI 3.1 por proceso web, generado desde su punto de entrada real;
  - una instantánea aprobada en `apps/api/openapi/`, que `./mvnw verify` compara byte a byte;
  - Swagger apagado fuera de `local` y `preprod`;
  - los esquemas `Money` y `ProblemDetail` publicados desde el primer documento.
- **Frontend:**
  - espacio de trabajo pnpm 12 y Turborepo, con Node 24;
  - `packages/config`;
  - `packages/contracts`, generado con orval y fuera de Git;
  - cuatro reglas de dependencia con sus violaciones deliberadas;
  - el trabajo `frontend verify`;
  - `pnpm audit` como puerta de vulnerabilidades.

## Decisiones del propietario

1. **Retirar la capa `Web` opcional de ADR-0020** (2026-09-30). `shared.web.openapi` es la primera
   clase de producción en un paquete `web`, que es la condición de caducidad que fijó el ADR. Ya no
   queda ninguna capa opcional.
2. **Dos documentos, Node 24, esqueleto de las aplicaciones en el cambio 13, contratos fuera de Git**
   (2026-09-30, ronda de preguntas de la propuesta).
3. **El aislamiento de los puntos de entrada pasa a ser el cambio 15 de F0**,
   `process-entry-point-isolation`. Debe fusionarse antes de `session-tokens-and-web-layer`.

## Lo que la aplicación encontró y el diseño no había previsto

- En pnpm 11 o posterior, `onlyBuiltDependencies` **se ignora sin avisar**, y pnpm 12 no lee
  `engine-strict` desde `.npmrc`. Con el diseño tal cual, las dos protecciones no habrían hecho nada.
- **Trivy solo lee las dependencias de producción de pnpm**: veía 15 de 270 paquetes. La puerta pasó
  a ser `pnpm audit`, que encontró de inmediato **dos vulnerabilidades altas reales** en `undici`.
- Turborepo 2.11 escribe `AGENTS.md` cuando detecta un agente de IA. Se desactivó con
  `agentGuidance: false`.
- `EmptyShouldExceptionInventoryTest` detectó, como se esperaba, que la capa `Web` había dejado de
  estar vacía.

Todas las sondas y demostraciones están en `apply-progress.md`, con su salida literal.

## Pendientes heredados, con su dueño

| Pendiente | Dueño |
|---|---|
| Excluir `node_modules` del recorrido de `apps/` en `IdentityScopeExclusionInventoryTest` antes de crear la primera aplicación | Cambio 13, `design-system-foundations-and-a11y` |
| Crear el esqueleto de `apps/admin-web` y `apps/portal-web` | Cambio 13 |
| Aislar los tres puntos de entrada de `bootstrap` | Cambio 15, `process-entry-point-isolation` |
| Fijar por dígest la imagen de Node de `docs/05` | El cambio que introduzca Renovate |
| Demostrar en vivo el escenario «Solo severidad baja» con la primera observación baja real | Cualquier cambio que la encuentre; hoy no hay ninguna |
| Dar contenido a la aserción de rutas disjuntas entre `admin` y `portal` | Llega sola con el primer controlador |

## Lección para los cambios siguientes

Dos de los tres hallazgos importantes de este cambio tienen la misma forma: **un ajuste que se
escribe, no da error y no hace nada**. `onlyBuiltDependencies` en pnpm 12, `engine-strict` en
`.npmrc` y Trivy sobre un archivo de bloqueo de pnpm pasaban en verde sin proteger nada. Solo se
detectaron porque cada protección se hizo fallar a propósito al menos una vez. Esa práctica, que
ADR-0018 exige a las reglas de ArchUnit, vale igual para cualquier herramienta de seguridad o de
construcción que se configure.

# Tareas: monorepo del frontend y canal de contratos desde el OpenAPI

- **Cambio:** `frontend-monorepo-and-contracts-pipeline`
- **Fase:** tareas
- **Fecha:** 2026-09-30
- **Estado:** aprobadas por el propietario del producto el 2026-09-30
- **Entradas aprobadas:** `proposal.md`, `specs/build-integrity/spec.md` y `design.md`, los tres del
  2026-09-30

## Pronóstico de revisión

| Campo | Valor |
|---|---|
| Presupuesto por pull request (`docs/15-flujo-de-trabajo-git.md` §3) | 800 líneas de cambio efectivo |
| Tareas | **13**, bajo el límite de quince |
| Cortes y pull requests | Tres, uno por corte: **3a** backend, **3b** monorepo y contratos, **3c** reglas e integración continua (`design.md` §8) |
| Excluido del conteo de líneas | `pnpm-lock.yaml` (generado), las dos instantáneas del OpenAPI (generadas y revisadas como contrato) y `openspec/` |
| Riesgo | **Medio en 3b**: configuración de cuatro herramientas en un solo corte. Si su tarea de medición supera 800, se detiene y se consulta, con 3b1 (espacio de trabajo y `packages/config`) y 3b2 (`packages/contracts`) como división ya nombrada |

**Lección aplicada de `column-encryption-and-mfa-totp`:** cuando una tarea afirma que algo
«funciona igual» o «reutiliza tal cual», su evidencia cita el valor observado, no la intención. Y
cada regla nueva lleva una violación deliberada que la haga fallar al menos una vez.

**Entorno:** la sesión remota no tiene Docker ni JDK 25. Las tareas del corte 3a se verifican en la
integración continua del PR, y así se registra en `apply-progress.md`; las de 3b y 3c pueden
ejecutarse en local si hay Node 24.

---

## Corte 3a — backend: springdoc, instantáneas y criterio de salida 7

- [x] 1.1 **Sondas S1 y S2 (bloqueantes, `design.md` §7).** S1: fijar la versión de
  springdoc-openapi compatible con Spring Boot 4.1 y Java 25, y confirmar que un contexto de
  `AdminApplication` con el perfil `local` sirve `/v3/api-docs` sin `DataSource`. S2: generar dos
  veces el documento normalizado y confirmar que los bytes coinciden. Registrar versión, comando y
  resultado en `apply-progress.md`. **Si S1 falla, se detiene el cambio y se informa**: no se
  inventa un endpoint ni se degrada la versión de Spring Boot.

- [x] 1.2 **ROJO/VERDE: Swagger apagado por defecto.** ROJO: `OpenApiExposureByProfileTest` arranca
  `AdminApplication` y `PortalApplication` con `prod` y con un perfil inexistente, y afirma que
  `/v3/api-docs` y la ruta de Swagger UI no responden; con `local`, que el documento responde. Falla
  porque springdoc no está. VERDE: la dependencia de 1.1 y la configuración de `application.yml`,
  `application-local.yml` y `application-preprod.yml` (decisión 3). — Requisito «Swagger UI y el
  endpoint del OpenAPI solo en local y preproducción», sus dos escenarios

- [x] 1.3 **ROJO/VERDE: esquemas transversales.** ROJO: una prueba afirma que los dos documentos
  contienen `Money` (`amount` y `currency` de tipo `string`, obligatorios, ninguna propiedad
  `number` ni `integer`) y `ProblemDetail` (`type`, `title`, `status`, `detail`, `instance` y
  `traceId`). VERDE: `ContractSchemas` en `com.confia.shared.web.openapi`, su `package-info.java`
  con `@NamedInterface`, y `@Import` en los dos puntos de entrada (decisión 4). Confirmar que Spring
  Modulith y las reglas de capas siguen en verde; **si alguna lo rechaza, se detiene y se
  consulta**. — Requisito «Esquemas transversales del contrato…», sus dos escenarios

- [x] 1.4 **ROJO/VERDE: la instantánea y su puerta.** ROJO: `OpenApiContractSnapshotTest` compara
  los dos documentos normalizados con `apps/api/openapi/admin.openapi.json` y
  `portal.openapi.json`, que todavía no existen. Afirma además que son dos documentos distintos y que
  ninguna ruta de `admin` aparece en `portal`. VERDE: generar las dos instantáneas con
  `-Dconfia.openapi.update=true` y comprometerlas; la prueba escribe siempre el documento generado
  en `app/target/openapi/`. **Demostración obligatoria, registrada:** alterar a mano una instantánea
  rompe `./mvnw verify` con un mensaje que nombra el documento y la primera ruta distinta, y deja
  la instantánea intacta; restaurarla vuelve a verde. Una segunda demostración confirma que con
  `-Dconfia.openapi.update=true` la prueba también termina en fallo. — Requisito «El OpenAPI generado
  coincide con la instantánea aprobada», sus tres escenarios

- [x] 1.5 **Integración continua del backend, criterio de salida 7 y medición del corte.** Añadir
  `apps/api/app/target/openapi/` al artefacto `backend-reports`. Marcar el criterio 7 como cerrado en
  `docs/09-roadmap-y-fases.md`, citando la prueba que lo demuestra. Medir el diff del corte con
  `git diff --numstat` excluyendo `openspec/` y las instantáneas; **si pasa de 800, se detiene y se
  consulta**.

## Corte 3b — monorepo, configuración compartida y contratos

- [x] 2.1 **Espacio de trabajo pnpm y Turborepo (decisión 6).** `package.json` raíz con
  `packageManager` exacto y `engines.node` `>=24 <25`; `.npmrc` con `engine-strict=true`; `.nvmrc`
  con `24`; `pnpm-workspace.yaml` con `apps/*`, `packages/*` y `onlyBuiltDependencies` vacío;
  `turbo.json` con `generate`, `lint`, `typecheck`, `test` y `build`, donde las tres últimas
  dependen de `^generate`; `pnpm-lock.yaml` comprometido. Registrar en `apply-progress.md` las
  versiones exactas fijadas. **Demostraciones registradas:** con una versión mayor de Node distinta,
  la instalación falla y nombra la exigida; con un `package.json` modificado sin actualizar el
  archivo de bloqueo, `pnpm install --frozen-lockfile` falla. — Requisito «Versión única de Node y
  de pnpm», sus dos escenarios

- [x] 2.2 **`packages/config` (decisión 7).** `tsconfig.base.json` con `strict`,
  `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes` y `verbatimModuleSyntax`; configuración
  plana de ESLint con `typescript-eslint` estricto con tipos; configuración base de Vitest. Una
  prueba de humo del paquete en verde con `pnpm turbo run lint typecheck test`.

- [x] 2.3 **Sonda S3 y `packages/contracts` (decisión 7).** S3 primero: orval genera tipos y
  esquemas Zod de `Money` y `ProblemDetail` desde un documento sin operaciones. **Si no puede, se
  detiene y se consulta.** Luego `orval.config.ts` con dos salidas, `src/generated/admin/` y
  `src/generated/portal/`, cuyas entradas de Turborepo son los dos archivos de `apps/api/openapi/`;
  `src/generated/` en `.gitignore`; `exports` con solo `./admin` y `./portal`.
  `apps/api/package.json` con el guion `verify` que delega en `./mvnw -B verify` (decisión 5).

- [x] 2.4 **ROJO/VERDE: pruebas de `packages/contracts`.** Tres pruebas con sufijo `*.test.ts`, nunca
  `*.spec.ts` (decisión 9):
  - una prueba de tipos (`vitest --typecheck`) que afirma que `amount` y `currency` son `string`;
  - una prueba de ejecución que valida un importe correcto con el esquema Zod y rechaza el mismo
    importe con `amount` numérico;
  - una prueba que ejecuta `git check-ignore` sobre `src/generated/` y afirma que está ignorado.
  **Demostración registrada:** cambiar `amount` a `number` en la instantánea y regenerar rompe
  `pnpm turbo run typecheck`; restaurarla vuelve a verde. — Requisito «Los contratos del frontend se
  generan…», sus tres escenarios

- [x] 2.5 **Node 24 en `docs/05` y medición del corte.** Cambiar las dos menciones de Node 22 de
  `docs/05-infraestructura-y-despliegue.md` con una nota fechada; el dígest sigue diferido al cambio
  que traiga Renovate (decisión 11). Confirmar que `IdentityScopeExclusionInventoryTest` sigue en
  verde. Medir el diff del corte; **si pasa de 800, se detiene y se consulta** con la división 3b1/3b2.

## Corte 3c — reglas de dependencia e integración continua

- [x] 3.1 **ROJO/VERDE: reglas de dependencia (decisión 8).** ROJO: una prueba de Vitest ejecuta
  `dependency-cruiser` sobre `tooling/dependency-fixtures/` y afirma que las cuatro reglas
  (`packages-never-import-apps`, `apps-never-import-other-apps`,
  `contracts-only-through-public-entry` y `portal-never-imports-admin-contracts`) aparecen violadas
  al menos una vez cada una; falla porque no existen. VERDE: `.dependency-cruiser.cjs`, las cuatro
  violaciones permanentes, y `no-restricted-imports` en ESLint para la tercera y la cuarta. Otra
  prueba ejecuta el análisis sobre el árbol real y afirma cero violaciones y **más de cero módulos
  analizados**. — Requisito «Reglas de dependencia del frontend», sus tres escenarios

- [x] 3.2 **Trabajo `frontend verify` (decisión 10).** `pnpm/action-setup` y `actions/setup-node`
  fijados por SHA, Node leído de `.nvmrc`, caché de pnpm, `pnpm install --frozen-lockfile` y
  `pnpm turbo run lint typecheck test build --filter=!@confia/api`. **Demostración registrada, en una
  rama desechable:** un error de tipos hace fallar el trabajo y el resultado es visible en el remoto.
  — Requisito «La integración continua verifica el monorepo…», escenario «Empuje con un error de
  tipos»

- [x] 3.3 **Sonda S4 y escaneo de pnpm, más la medición final.** S4 y el paso de Trivy sobre
  `pnpm-lock.yaml` en `security scanning`, con la misma severidad y el mismo `exit-code` que el de
  Maven; actualizar el comentario del paso. **Demostración registrada, en una rama desechable:** una
  dependencia con una vulnerabilidad crítica conocida rompe el escaneo y lo nombra. Luego:
  - barrido final de trazabilidad de los 18 escenarios del delta contra `design.md` §5, contado de
    nuevo contra el archivo;
  - medición del diff del corte;
  - integración continua en verde en los tres trabajos.
  — Requisito «La integración continua verifica el monorepo…», escenarios «Vulnerabilidad crítica…»
  y «Solo severidad baja»

---

## Trazabilidad: 18 escenarios del delta

| Requisito | Escenario | Tarea |
|---|---|---|
| OpenAPI coincide con la instantánea | Una diferencia no declarada rompe la construcción | 1.4 |
| | Documento idéntico a la instantánea | 1.4 |
| | Cada superficie tiene su propio documento | 1.4 |
| Swagger solo en local y preproducción | Perfil de producción | 1.2 |
| | Perfil local | 1.2 |
| Esquemas transversales | El importe viaja como cadena | 1.3 |
| | Problem Details presente | 1.3 |
| Contratos generados | Cambio incompatible del contrato | 2.4 |
| | Contrato sin cambios | 2.4 |
| | La salida generada no está en el repositorio | 2.4 |
| Reglas de dependencia | Un paquete importa de una aplicación | 3.1 |
| | El portal importa el contrato administrativo | 3.1 |
| | Código que respeta las reglas | 3.1 |
| Versión única de Node y pnpm | Versión de Node distinta | 2.1 |
| | Archivo de bloqueo desactualizado | 2.1 |
| Integración continua | Empuje con un error de tipos | 3.2 |
| | Vulnerabilidad crítica en pnpm | 3.3 |
| | Solo severidad baja | 3.3 |

Ningún escenario queda sin tarea.

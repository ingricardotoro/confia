# Diseño: monorepo del frontend y canal de contratos desde el OpenAPI

- **Cambio:** `frontend-monorepo-and-contracts-pipeline`
- **Fase:** diseñar
- **Fecha:** 2026-09-30
- **Estado:** aprobado por el propietario del producto el 2026-09-30
- **Entradas aprobadas:** `proposal.md` (2026-09-30, con las cuatro recomendaciones) y
  `specs/build-integrity/spec.md` (2026-09-30)

## 1. Enfoque técnico

El canal tiene una sola fuente de verdad revisable: **las dos instantáneas del OpenAPI
comprometidas en el repositorio**. El backend demuestra en cada `./mvnw verify` que su código
genera exactamente esas instantáneas, y el frontend genera sus contratos **a partir de ellas**, no
de un backend en marcha. Así el trabajo del frontend en la integración continua no necesita JDK,
Maven ni Docker, y aun así ningún tipo de TypeScript puede separarse del código Java: si el Java
cambia sin actualizar la instantánea, falla el backend; si la instantánea cambia de forma
incompatible, falla la verificación de tipos del frontend.

```
 código Java ──(./mvnw verify)──► OpenAPI generado ══ compara ══ instantánea comprometida
                                                                     │
                                                         (orval, pnpm turbo)
                                                                     ▼
                                                  packages/contracts (generado, ignorado en Git)
                                                                     │
                                                         (tsc, vitest --typecheck)
                                                                     ▼
                                                  consumidores tipados (hoy: prueba de tipos)
```

## 2. Evidencia obtenida en esta fase

Leída en el árbol de `main` @ `9df8bf1`, no supuesta:

- `AdminApplication` y `PortalApplication` (`apps/api/app/src/main/java/com/confia/bootstrap/`)
  declaran `scanBasePackages = "com.confia.bootstrap"` y excluyen
  `DataSourceAutoConfiguration`. **Arrancan sin base de datos**, y cada una carga solo lo que su
  proceso expone (ADR-0003).
- `apps/api/app/pom.xml` ya trae `spring-boot-starter-web`; no trae springdoc.
- `IdentityScopeExclusionInventoryTest.noPlaywrightTestExercisesTheAuthenticationFlowYet` recorre
  `apps/` y falla si encuentra un archivo `*.spec.ts` o `*.spec.js`. Es una restricción real para
  este cambio (decisión 9).
- `.github/workflows/ci.yml`: el trabajo `security scanning` ejecuta Trivy con `scan-ref: apps/api`;
  el comentario del paso dice que pnpm llega con los cambios 3 y 11.
- `docs/05-infraestructura-y-despliegue.md` fija Node 22 en dos lugares (líneas 241 y 912).

## 3. Decisiones de arquitectura

### Decisión 1 — Dos documentos, uno por proceso, generados desde el punto de entrada real

Cada documento se genera arrancando **el punto de entrada real de su proceso**
(`AdminApplication` para `admin`, `PortalApplication` para `portal`) en un contexto web simulado, y
pidiendo `/v3/api-docs` con `MockMvc`. No se agrupan operaciones por prefijo de ruta ni por paquete
en la configuración de springdoc.

**Por qué:** el documento de un proceso contiene exactamente lo que ese proceso carga. Si mañana
alguien añade un controlador administrativo al escaneo del portal, el documento del portal cambia
y la instantánea lo delata. Un agrupamiento por ruta, en cambio, describiría lo que la
configuración dice, no lo que el proceso sirve.

**Descartado:** `GroupedOpenApi` por prefijo `/api/v1/admin` y `/api/v1/portal` dentro de un solo
contexto. Oculta justo el error que ADR-0003 quiere evitar.

### Decisión 2 — La comparación es una prueba sin contenedor, `OpenApiContractSnapshotTest`

Una prueba JUnit en `apps/api/app/src/test/java/com/confia/architecture/` arranca los dos
contextos, obtiene los dos documentos, los **normaliza** (claves ordenadas, sangría fija y sin el
bloque `servers`, que depende del puerto) y los compara byte a byte con
`apps/api/openapi/admin.openapi.json` y `apps/api/openapi/portal.openapi.json`.

- Escribe siempre el documento generado en `apps/api/app/target/openapi/`, que la integración
  continua sube como artefacto.
- **Nunca sobrescribe la instantánea.** Solo la actualiza con la propiedad explícita
  `-Dconfia.openapi.update=true`, y aun así la prueba termina en fallo, para que nadie pueda
  dejarla puesta en la integración continua y aprobar un cambio de contrato sin verlo.
- Si difiere, el mensaje nombra el documento y la primera ruta JSON distinta, nunca el documento
  entero.

No lleva sufijo `*IT` porque no necesita contenedor: los dos contextos excluyen el `DataSource`.
`IntegrationTestNamingTest` lo confirma en cada construcción.

**Descartado:** `springdoc-openapi-maven-plugin`, que arranca un proceso real con
`spring-boot-maven-plugin` dentro de `verify`. Añade un proceso, un puerto y tiempo, para obtener
lo mismo que un contexto simulado.

> **Nota de aplicación, 2026-09-30.** Las dos pruebas viven en `com.confia.bootstrap` (test), no en
> `com.confia.architecture`: los puntos de entrada son de paquete y no se ven desde otro paquete.
> Además, la demostración de la instantánea alterada quedó como prueba permanente, en lugar de un
> empuje roto y revertido.

### Decisión 3 — Swagger UI y el endpoint, apagados por defecto

`application.yml` fija `springdoc.api-docs.enabled: false` y `springdoc.swagger-ui.enabled: false`.
Solo `application-local.yml` y `application-preprod.yml` los encienden. Apagar por defecto hace que
un perfil nuevo, o una errata en `SPRING_PROFILES_ACTIVE`, deje el documento cerrado y no abierto.
La prueba de la decisión 2 arranca con el perfil `local`; una segunda prueba arranca con `prod` y
con un perfil inexistente, y afirma que ninguna de las dos rutas responde.

### Decisión 4 — Esquemas transversales registrados por un `OpenApiCustomizer` compartido

Un único componente, `ContractSchemas`, en `com.confia.shared.web.openapi`, registra dos esquemas
en ambos documentos:

- `Money`: `{ amount: string, currency: string }`, los dos obligatorios. `amount` lleva el patrón de
  decimal con hasta cuatro decimales y `currency`, el de tres letras mayúsculas (ISO 4217).
- `ProblemDetail`: los campos de RFC 9457 más `traceId`, que exige `docs/01-arquitectura.md` §7.

Los dos puntos de entrada lo incorporan con `@Import`, sin ampliar su `scanBasePackages`. Registrar
los esquemas a mano es necesario porque springdoc solo publica los que alguna operación
referencia, y hoy no existe ninguna.

`Money` en el contrato **no** es el objeto de valor de `kernel`: es la forma en que viaja por la
API. El DTO real de importe se crea con el primer endpoint que lo use; este esquema fija su forma
desde ya para que ese DTO no pueda inventarse otra.

La ubicación en `shared.web.openapi` sigue ADR-0022 al pie de la letra: el paquete declara su
propio `@org.springframework.modulith.NamedInterface` en su `package-info.java`, igual que
`shared.security`, `shared.audit` y `shared.crypto`, y nombra a su único consumidor real, los dos
puntos de entrada de `bootstrap` (ADR-0022, «cada `@NamedInterface` nuevo debe nombrar el consumidor
real que lo justifica»). El segmento `web` lo somete además a las reglas de capas de ADR-0020 como
capa `web` opcional. Si Spring Modulith o las reglas de capas lo rechazan pese a eso, **se detiene
y se informa**: ni se relaja una regla ni se mueve la clase a `bootstrap` sin decidirlo con el
propietario.

### Decisión 5 — El frontend genera sus contratos desde la instantánea, no desde el backend en marcha

`packages/contracts` declara como entradas de Turborepo los dos archivos de
`apps/api/openapi/`. `apps/api/package.json` existe, como pide `docs/01-arquitectura.md` §4, con
un único guion `verify` que delega en `./mvnw -B verify`, para que `pnpm turbo run verify` en una
máquina de desarrollo recorra todo el sistema en orden. El trabajo `frontend verify` de la
integración continua lo **excluye** con `--filter=!@confia/api`: el trabajo del backend ya lo
ejecuta, y repetirlo exigiría JDK y Docker en el trabajo del frontend.

Esto cumple el orden que pide §4 (backend y OpenAPI, luego contratos, luego aplicaciones), porque
la instantánea solo puede cambiar en un PR cuyo trabajo de backend compruebe que el Java la genera.

### Decisión 6 — Espacio de trabajo pnpm y Turborepo

- `package.json` raíz privado, con `packageManager` fijado a una versión exacta de pnpm y
  `engines.node` en `>=24 <25`. `.npmrc` con `engine-strict=true`, para que la instalación falle
  con otra versión de Node, y `.nvmrc` con `24`.
- `pnpm-workspace.yaml` con `apps/*` y `packages/*`, y la lista `onlyBuiltDependencies` vacía:
  **ninguna dependencia ejecuta guiones de instalación** salvo las que se añadan a esa lista con
  justificación en el PR.
- `turbo.json` con las tareas `generate`, `lint`, `typecheck`, `test` y `build`. `typecheck`,
  `test` y `build` dependen de `^generate`.
- `pnpm-lock.yaml` comprometido; la integración continua instala con `--frozen-lockfile`.

> **Nota de aplicación, 2026-09-30.** Dos ajustes de esta decisión no funcionan en pnpm 12.
> `onlyBuiltDependencies` fue reemplazado por `allowBuilds` en pnpm 11 y desde entonces se ignora sin
> aviso: se usa `strictDepBuilds: true` con `allowBuilds`, y esbuild queda denegado de forma
> explícita. `engine-strict` en `.npmrc` también se ignoraría, porque pnpm 12 solo lee de ese archivo
> credenciales y registros: `engineStrict: true` va en `pnpm-workspace.yaml` y no hay `.npmrc`. Las
> versiones viven en el `catalog:` del mismo archivo.

### Decisión 7 — `packages/config` y `packages/contracts`

- **`packages/config`:** `tsconfig.base.json` estricto (`strict`, `noUncheckedIndexedAccess`,
  `exactOptionalPropertyTypes`, `verbatimModuleSyntax`), configuración plana de ESLint con
  `typescript-eslint` en modo estricto con tipos, y configuración base de Vitest.
- **`packages/contracts`:** `orval.config.ts` con dos salidas, `src/generated/admin/` y
  `src/generated/portal/`, cada una con tipos y esquemas Zod. `src/generated/` está en
  `.gitignore`. El campo `exports` del `package.json` publica solo `./admin` y `./portal`, así
  que una ruta interna ni siquiera se resuelve. Las pruebas del paquete son dos:
  - una prueba de tipos (`vitest --typecheck`) que afirma que `amount` y `currency` son `string`;
  - una prueba de ejecución que valida un importe correcto con el esquema Zod y rechaza el mismo
    importe con `amount` numérico.

> **Nota de aplicación, 2026-09-30.** `exports` apunta a `src/admin.ts` y `src/portal.ts`, dos
> módulos escritos a mano que solo reexportan lo generado. Así un consumidor nunca depende de la forma
> de los archivos que produce orval. La sonda S3 confirmó que orval genera los esquemas de los
> componentes con `output.schemas` de tipo `zod`, aunque no haya operaciones.

### Decisión 8 — Reglas de dependencia, cada una con su violación deliberada permanente

`dependency-cruiser` en la raíz, con cuatro reglas: `packages-never-import-apps`,
`apps-never-import-other-apps`, `contracts-only-through-public-entry` y
`portal-never-imports-admin-contracts`. ESLint duplica la tercera y la cuarta con
`no-restricted-imports`, para que el editor las marque antes que la integración continua.

Las violaciones viven en `tooling/dependency-fixtures/`, una por regla, **fuera** de `apps/` y
`packages/`, con nombres de ruta que las reglas reconocen (por ejemplo
`tooling/dependency-fixtures/apps/portal-web/imports-admin-contracts.ts`). Una prueba de Vitest
ejecuta `dependency-cruiser` sobre esa carpeta y afirma que cada regla aparece violada **al menos
una vez**. Otra prueba lo ejecuta sobre el árbol real y afirma dos cosas: cero violaciones y
**más de cero módulos analizados**, para que una regla nunca pase sobre un conjunto vacío.

### Decisión 9 — Ningún archivo `*.spec.ts` bajo `apps/`

Las pruebas del frontend usan el sufijo `*.test.ts`. `*.spec.ts` queda reservado para Playwright,
y `IdentityScopeExclusionInventoryTest` lo busca bajo `apps/` para afirmar que todavía no existe
ninguna prueba de extremo a extremo del inicio de sesión. Este cambio no crea nada bajo `apps/`
salvo `apps/api/package.json`, así que la prueba sigue siendo cierta.

### Decisión 10 — Integración continua

- **Trabajo nuevo `frontend verify`:** `pnpm/action-setup` y `actions/setup-node` fijados por SHA,
  con la versión de Node leída de `.nvmrc`, caché de pnpm, `pnpm install --frozen-lockfile` y
  `pnpm turbo run lint typecheck test build --filter=!@confia/api`.
- **Escaneo:** un segundo paso de Trivy en `security scanning`, con `scan-ref: .` limitado a
  `pnpm-lock.yaml`, con la misma severidad y el mismo `exit-code` que el de Maven. Se actualiza el
  comentario que decía que pnpm llegaba después.
- **Artefacto:** el trabajo del backend sube `apps/api/app/target/openapi/` junto a los informes de
  cobertura.

> **Nota de aplicación, 2026-09-30.** La sonda S4 encontró que Trivy solo lee las dependencias de
> **producción** de un archivo de bloqueo de pnpm (15 de 270 paquetes; su `--include-dev-deps` no
> admite pnpm). La puerta pasó a ser `pnpm audit --audit-level high` sobre el árbol entero, con un
> `pnpm audit` previo que informa sin bloquear. Trivy se queda como segunda fuente. Esa auditoría
> encontró enseguida dos avisos altos reales en `undici`, corregidos con un `overrides`.

### Decisión 11 — `docs/05` pasa a Node 24

Se cambian las dos menciones de Node 22 de `docs/05-infraestructura-y-despliegue.md` (imagen de
construcción y ejemplo de integración continua), con una nota fechada. El dígest de la imagen no se
fija aquí: sigue la regla del cambio 5, que lo difiere al cambio que introduzca Renovate.

## 4. Cambios de archivos

| Archivo | Acción |
|---|---|
| `apps/api/app/pom.xml` | Añadir springdoc (versión en la sonda S1) |
| `apps/api/app/src/main/resources/application.yml`, `application-local.yml`, `application-preprod.yml` | Configuración de springdoc por perfil (decisión 3) |
| `apps/api/app/src/main/java/com/confia/shared/web/openapi/ContractSchemas.java` y su `package-info.java` con `@NamedInterface` | Crear (decisión 4) |
| `AdminApplication.java`, `PortalApplication.java` | `@Import(ContractSchemas.class)` |
| `apps/api/app/src/test/java/com/confia/architecture/OpenApiContractSnapshotTest.java` | Crear (decisión 2) |
| `apps/api/app/src/test/java/com/confia/architecture/OpenApiExposureByProfileTest.java` | Crear (decisión 3) |
| `apps/api/openapi/admin.openapi.json`, `portal.openapi.json` | Crear: instantáneas aprobadas |
| `apps/api/package.json` | Crear (decisión 5) |
| `package.json`, `pnpm-workspace.yaml`, `turbo.json`, `.npmrc`, `.nvmrc`, `pnpm-lock.yaml` | Crear (decisión 6) |
| `packages/config/**`, `packages/contracts/**` | Crear (decisión 7) |
| `.dependency-cruiser.cjs`, `tooling/dependency-fixtures/**` y su prueba | Crear (decisión 8) |
| `.gitignore` | Añadir `packages/contracts/src/generated/` |
| `.github/workflows/ci.yml` | Trabajo `frontend verify`, Trivy sobre pnpm y artefacto del OpenAPI (decisión 10) |
| `docs/05-infraestructura-y-despliegue.md` | Node 24 (decisión 11) |
| `docs/09-roadmap-y-fases.md` | Marcar cerrado el criterio de salida 7 con la prueba que lo demuestra |

## 5. Trazabilidad de los escenarios del delta

| Requisito | Escenario | Prueba |
|---|---|---|
| OpenAPI coincide con la instantánea | Una diferencia no declarada rompe la construcción | `OpenApiContractSnapshotTest` + demostración con la instantánea alterada, registrada en `apply-progress.md` |
| | Documento idéntico a la instantánea | `OpenApiContractSnapshotTest` |
| | Cada superficie tiene su propio documento | `OpenApiContractSnapshotTest` (dos documentos distintos y ninguna ruta de `admin` en `portal`) |
| Swagger solo en local y preproducción | Perfil de producción | `OpenApiExposureByProfileTest` |
| | Perfil local | `OpenApiExposureByProfileTest` |
| Esquemas transversales | El importe viaja como cadena | `OpenApiContractSnapshotTest` (aserción sobre el esquema `Money`) |
| | Problem Details presente | Ídem, sobre `ProblemDetail` |
| Contratos generados | Cambio incompatible del contrato | Prueba de tipos de `packages/contracts` + demostración registrada |
| | Contrato sin cambios | `pnpm turbo run typecheck` en verde |
| | La salida generada no está en el repositorio | Prueba de `packages/contracts` que ejecuta `git check-ignore` sobre `src/generated/` |
| Reglas de dependencia | Un paquete importa de una aplicación | Prueba de `dependency-cruiser` sobre `tooling/dependency-fixtures/` |
| | El portal importa el contrato administrativo | Ídem |
| | Código que respeta las reglas | Prueba sobre el árbol real, con más de cero módulos analizados |
| Versión única de Node y pnpm | Versión de Node distinta | Demostración registrada (`engine-strict`) |
| | Archivo de bloqueo desactualizado | Demostración registrada (`--frozen-lockfile`) |
| Integración continua | Empuje con un error de tipos | Demostración en una rama desechable, registrada |
| | Vulnerabilidad crítica en pnpm | Demostración con una dependencia vulnerable conocida en una rama desechable, registrada |
| | Solo severidad baja | Ejecución normal del escaneo |

Seis escenarios se demuestran una vez y quedan registrados, en lugar de tener una prueba que corra
en cada construcción. Es el mismo tratamiento que tuvo la generación de jOOQ en el cambio 5 y se
dice aquí, no se descubre en la verificación.

## 6. Matriz de amenazas

| Frontera | Tratamiento |
|---|---|
| El documento OpenAPI expuesto en producción | Apagado por defecto; prueba con `prod` y con un perfil inexistente (decisión 3) |
| Una dependencia de npm que ejecuta código al instalarse | `onlyBuiltDependencies` vacío (decisión 6) |
| Un archivo de bloqueo manipulado o desactualizado | `--frozen-lockfile` en la integración continua |
| Una vulnerabilidad conocida en una dependencia de pnpm | Trivy bloqueante ante severidad alta o crítica (decisión 10) |
| El código generado editado a mano | Ignorado en Git y regenerado en cada construcción (decisión 7) |
| El portal compilando contra una operación administrativa | Dos documentos, dos espacios de nombres y la regla `portal-never-imports-admin-contracts` (decisiones 1 y 8) |

## 7. Sondas, antes de la tarea que depende de cada una

| # | Pregunta | Criterio de éxito y respaldo | Bloquea |
|---|---|---|---|
| **S1** | ¿Qué versión de springdoc es compatible con Spring Boot 4.1 y Java 25, y arranca con los dos puntos de entrada sin `DataSource`? | Un contexto de `AdminApplication` con springdoc sirve `/v3/api-docs` en `local`. Si no hay versión compatible, **se detiene el cambio** y se informa | Corte 3a |
| **S2** | ¿Es determinista la salida de springdoc entre dos ejecuciones y dos máquinas, una vez normalizada? | Dos generaciones seguidas dan bytes idénticos. Si no, se amplía la normalización y se documenta qué se descarta | Corte 3a |
| **S3** | ¿Genera orval tipos y esquemas Zod desde un documento sin operaciones, solo con componentes? | Aparecen `Money` y `ProblemDetail` en las dos salidas. Si orval exige operaciones, se evalúa su modo de solo esquemas antes de cualquier alternativa | Corte 3b |
| **S4** | ¿Encuentra Trivy vulnerabilidades en `pnpm-lock.yaml` con la versión fijada de la acción? | Una dependencia vulnerable conocida, en una rama desechable, rompe el paso | Corte 3c |

## 8. Secuencia de aplicación, en tres cortes

- **3a, backend:** S1 y S2; springdoc y configuración por perfil; `ContractSchemas`; las dos pruebas
  y las dos instantáneas; el artefacto en la integración continua; criterio de salida 7 en
  `docs/09`. Todo en Java, sin pnpm.
- **3b, monorepo y contratos:** el espacio de trabajo, `packages/config`, S3, `packages/contracts`
  y sus pruebas, `apps/api/package.json` y Node 24 en `docs/05`.
- **3c, reglas e integración continua:** `dependency-cruiser` y ESLint con sus violaciones, el
  trabajo `frontend verify`, S4 y el escaneo de pnpm.

Cada corte es un PR que funciona por sí solo. El archivo de bloqueo de pnpm es generado y no cuenta
como línea de autor; aun así, 3b es el corte con más riesgo de pasar de ochocientas líneas.

## 9. Preguntas abiertas

- [ ] Si las pruebas de Spring Modulith o de capas rechazan `com.confia.shared.web.openapi` pese a
      su `@NamedInterface` (decisión 4), ¿dónde vive `ContractSchemas`? Se sabe con la primera
      ejecución del corte 3a; si hay conflicto, se trae al propietario antes de seguir.
- [ ] La versión exacta de TypeScript, orval, Zod, Turborepo y pnpm se fija en la fase de
      aplicación, con la última estable a esa fecha, y queda registrada en `apply-progress.md`.

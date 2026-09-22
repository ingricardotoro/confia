# Informe de verificación: `jooq-flyway-testcontainers-wiring` (F0, cambio 5, parte A)

- **Fecha:** 2026-09-21
- **Rama verificada:** `main` @ `5fb1c4c` (cuatro pull requests fusionados: #15 A1, #16 A2, #17 A3a, #18 A3b)
- **Modo TDD:** estricto (`openspec/config.yaml`, `strict_tdd: true`)
- **Almacén de artefactos:** híbrido (archivo canónico + espejo en Engram)
- **Naturaleza:** diagnóstico. No bloquea el archivado ni certifica aprobación.

**Veredicto:** 0 CRITICAL, 6 WARNING, 8 SUGGESTION. La construcción completa está en verde,
incluida la puerta de mutación de `main`. 34 de los 43 escenarios de las dos especificaciones
delta tienen un método de prueba nominal; 5 se verifican por ejecución de la construcción sin
método dedicado; 3 no tienen prueba ni mecanismo automatizado; 1 está cubierto solo de forma
parcial.

---

## 1. Verificación ejecutada

Ambas corridas se hicieron en este equipo, con `JAVA_HOME` en
`C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot` (Temurin 25.0.3+9),
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` y Docker activo, desde `apps/api`.
La salida completa se redirigió a archivo y solo se filtró con `grep`.

### 1.1 `./mvnw -B verify`

| Medida | Valor observado |
|---|---|
| Resultado | `BUILD SUCCESS` (código de salida 0) |
| Tiempo total | **3 min 23 s** (`confia-api-parent` 2,0 s; `confia-kernel` 1 min 04 s; `confia-api` 2 min 15 s) |
| Pruebas unitarias de `kernel` (Surefire) | **177**, 0 fallos, 0 errores, 0 omitidas |
| Pruebas unitarias de `app` (Surefire) | **109**, 0 fallos, 0 errores, 0 omitidas |
| Pruebas de integración de `app` (Failsafe) | **21**, 0 fallos, 0 errores, 0 omitidas |
| Generación de jOOQ | `testcontainers-jooq-codegen:0.0.4:generate` en `generate-sources`; contenedor `postgres:18-alpine` arrancado en `PT3.420456S`; `Successfully applied 1 migration to schema "public", now at version v1` |
| JaCoCo | `Analyzed bundle 'confia-api' with 14 classes`; `All coverage checks have been met.` |

Desglose de las cuatro clases `*IT.java`:

| Clase | Pruebas | Tiempo |
|---|---|---|
| `JooqInstitutionRepositoryIT` | 8 | 23,69 s |
| `MultiTenantSchemaIT` | 7 | 5,102 s |
| `RolePrivilegeMatrixIT` | 4 | 0,149 s |
| `DatabasePipelineIT` | 2 | 0,051 s |

La fase `integration-test` de Failsafe se extendió de `19:39:45` a `19:40:15`, es decir
**alrededor de 30 segundos**, muy por debajo del presupuesto de 8 minutos
(`docs/06-estrategia-de-testing.md` §14.2). Esto coincide con la cifra que `apps/api/README.md`
ya declara. No se reprodujo la corrida anómala de 9 min 13 s que `apply-progress.md` reporta
honestamente para la tarea 3.9.

### 1.2 `./mvnw -B verify -Dconfia.ci.mainBranch=true` (la puerta que `main` ejecuta en CI)

| Medida | Valor observado |
|---|---|
| Resultado | `BUILD SUCCESS` (código de salida 0) |
| Tiempo total | **3 min 12 s** |
| Pruebas | 177 + 109 + 21, 0 fallos |
| PIT sobre `confia-kernel` | **191 mutantes, 189 eliminados (99 %)** — por encima del umbral de 80 |
| PIT sobre `confia-api` | **49 mutantes, 49 eliminados (100 %)**; cobertura de línea de las clases mutadas 75/75; fuerza de prueba 100 % |

### 1.3 Comprobaciones directas del árbol

| Comprobación | Comando | Resultado |
|---|---|---|
| Código generado fuera de control de versiones | `git ls-files \| grep -c confia/generated` | `0` |
| Árbol limpio antes y después de la verificación | `git status --short` | sin salida |
| Cobertura sin código generado | `apps/api/app/target/site/jacoco/jacoco.csv` | 14 filas, ninguna de `confia.generated` |
| Una sola capa opcional | búsqueda de `\.optionalLayer\(` bajo `apps/api` | exactamente una, `optionalLayer("Web")` en `LayeredArchitectureTest:70` |

**Ningún archivo del repositorio fue modificado durante esta verificación.**

---

## 2. Trazabilidad escenario por escenario

Convenciones de «Estado»: **PRUEBA** = existe un método de prueba que lo verifica; **EJECUCIÓN** =
lo verifica la construcción o la configuración, sin método dedicado, y se observó en esta
verificación; **MANUAL** = verificado a mano, sin automatización; **PARCIAL** = cubierto solo en
parte; **SIN PRUEBA** = no existe verificación automatizada.

### 2.1 `specs/build-integrity/spec.md` — 13 requisitos, 30 escenarios

#### Generación del código de jOOQ desde las migraciones de Flyway

| # | Escenario | Método | Estado |
|---|---|---|---|
| 1 | Consulta incompatible con el esquema no compila | — | **SIN PRUEBA**. Demostración manual de la tarea 1.7, con el mensaje exacto `cannot find symbol: variable NAME, location: variable CODEGEN_DEMO_TEMP of type confia.generated.jooq.tables.CodegenDemoTemp`, registrada en `apply-progress.md`. No se reproduce en cada construcción. |
| 2 | Generación exitosa con esquema compatible | — | **EJECUCIÓN**. La fase `generate-sources` es la puerta: observé el contenedor, la migración aplicada y la compilación posterior de `JooqInstitutionRepository`, que importa el tipo generado. |
| 3 | Código generado ausente del repositorio | — | **MANUAL**. `git ls-files` no devuelve nada bajo `confia/generated`. Sin prueba automatizada. |

#### jOOQ y el paquete generado confinados a `infrastructure`

| # | Escenario | Método | Estado |
|---|---|---|---|
| 4 | Importación de jOOQ desde `application` | `JooqConfinedToInfrastructureTest.rejectsTheFixtureJooqUsageOutsideInfrastructure` (fixture `BadJooqUser`) | **PRUEBA** |
| 5 | Uso confinado a `infrastructure` | `JooqConfinedToInfrastructureTest.productionCodeConfinesJooqToInfrastructure` | **PRUEBA** |

#### Propiedad de tablas generadas por prefijo de módulo

| # | Escenario | Método | Estado |
|---|---|---|---|
| 6 | Un módulo usa la tabla generada de otro | `TableOwnershipByModuleTest.rejectsTheFixtureForeignTableUsage` (fixture `BadForeignTableUser`: módulo `billing` usando `OrganizationInstitution`) | **PRUEBA** |
| 7 | Un módulo usa solo sus propias tablas | `TableOwnershipByModuleTest.productionCodeOnlyUsesItsOwnModulesGeneratedTables` | **PRUEBA** |

#### Nombres de tabla con el prefijo de su módulo, salvo el catálogo cerrado

| # | Escenario | Método | Estado |
|---|---|---|---|
| 8 | Tabla de negocio sin el prefijo de su módulo | `MultiTenantSchemaIT.everyBusinessTableNameCarriesItsOwnerModulesPrefixExceptTheClosedCatalogue` | **PRUEBA**. La rama de fallo no se ejercita hoy; el control negativo `tmp_import` registrado en `apply-progress.md` demuestra que dispara. |
| 9 | Tabla raíz con el prefijo `organization_`, no exceptuada | mismo método: `ROOT_TABLE` no está en `TECHNICAL_TABLES`, así que sí se evalúa | **PRUEBA** |
| 10 | Tabla técnica exceptuada del prefijo | mismo método, rama `continue` sobre `TECHNICAL_TABLES`. La exención se ejercita de verdad: `flyway_schema_history` existe y no lleva prefijo de módulo, de modo que sin esa rama la prueba fallaría | **PRUEBA** |

#### Prohibición del SQL plano de jOOQ fuera de la lista aprobada

| # | Escenario | Método | Estado |
|---|---|---|---|
| 11 | SQL plano fuera de la lista aprobada | `NoUnapprovedPlainSqlTest.rejectsTheFixturePlainSqlUsage` (fixture `BadPlainSqlRepository`) | **PRUEBA** |
| 12 | Ausencia total de SQL plano, sin excepción de conjunto vacío | `NoUnapprovedPlainSqlTest.productionCodeCallsNoUnapprovedPlainSql`. Usa `classes().should(...)` sobre `productionClasses()`, no `noClasses()`, y sin `allowEmptyShould(true)`: evalúa de verdad el adaptador real | **PRUEBA** |

#### Transacciones confinadas al componente único de `shared/security`

| # | Escenario | Método | Estado |
|---|---|---|---|
| 13 | Adaptador abre su propia transacción | `TransactionsOnlyInSharedSecurityTest.rejectsTheFixtureTransactionalUsage` (fixture `BadTransactionalRepository`) | **PRUEBA** |
| 14 | Adaptador sin control transaccional propio | `TransactionsOnlyInSharedSecurityTest.productionCodeOpensNoTransactionOutsideSharedSecurity` | **PRUEBA** |

#### Fuente única de la versión de PostgreSQL en la construcción

| # | Escenario | Método | Estado |
|---|---|---|---|
| 15 | Declaración duplicada de la versión falla | `PostgresImageSingleSourceTest.postgresIntegrationTestUsesTheSameFilteredImage` | **PARCIAL** — ver W2 |
| 16 | Fuente única compartida pasa | `PostgresImageSingleSourceTest.confiaBuildPropertiesIsFilteredWithARealPostgres18Image` más el método anterior | **PRUEBA** |

#### Pruebas `*IT.java` con Testcontainers dentro del presupuesto de 8 minutos

| # | Escenario | Método | Estado |
|---|---|---|---|
| 17 | Clase de integración nombrada fuera de la convención | — | **SIN PRUEBA** — ver W3 |
| 18 | Suite de integración excede el presupuesto | — | **SIN PRUEBA** — ver W1 |
| 19 | Suite dentro del presupuesto, con el tiempo medido | — | **MANUAL**. Medido por mí: ~30 s de fase `integration-test`. Reportado en `apps/api/README.md` |

#### Toda tabla de negocio lleva `institution_id NOT NULL`, salvo el catálogo cerrado

| # | Escenario | Método | Estado |
|---|---|---|---|
| 20 | Tabla de negocio sin `institution_id` | `MultiTenantSchemaIT.everyBusinessTableHasInstitutionIdOrBelongsToTheClosedCatalogueByExactName`. Guarda de no-vacuidad explícita sobre filas reales | **PRUEBA** |
| 21 | Tabla raíz exceptuada por pertenencia | mismo método, vía `CLOSED_CATALOGUE_TABLES.contains(...)` | **PRUEBA** |
| 22 | Tabla ajena al catálogo no exceptuada por accidente | mismo método: la pertenencia se decide por nombre exacto con `Set.contains`, sin heurística | **PRUEBA** |

#### Seguridad a nivel de fila habilitada y forzada en cada tabla protegida

| # | Escenario | Método | Estado |
|---|---|---|---|
| 23 | Tabla sin `FORCE ROW LEVEL SECURITY` | `MultiTenantSchemaIT.everyTableWithInstitutionIdAndTheRootTableHaveRowLevelSecurityEnabledAndForced`. El control negativo del propietario (retirar `FORCE`) hizo fallar esta prueba nombrando la tabla | **PRUEBA** |
| 24 | Tabla raíz con ambas banderas activas | mismo método, más `policyCountOn(ROOT_TABLE) >= 1` | **PRUEBA** |

#### Toda restricción única de una tabla de negocio incluye el discriminador

| # | Escenario | Método | Estado |
|---|---|---|---|
| 25 | Restricción única sin el discriminador | `MultiTenantSchemaIT.everyUniqueIndexOfABusinessTableIncludesTheInstitutionDiscriminator`. La rama `institution_id` no se ejercita hoy: no existe aún ninguna tabla de negocio distinta de la raíz | **PRUEBA** estructural |
| 26 | Clave primaria de la tabla raíz como su propio discriminador | mismo método, rama `requiredColumn = "id"` sobre `organization_institution_pkey`. De bajo poder discriminante: una clave primaria sobre `id` contiene `id` por construcción | **PRUEBA** |

#### Código generado de jOOQ excluido de cobertura y de mutación

| # | Escenario | Método | Estado |
|---|---|---|---|
| 27 | Cobertura no penalizada por código generado | — | **EJECUCIÓN**. `jacoco-report` y `jacoco-check` excluyen `confia/generated/**` con cita a ADR-0021 adyacente; observado: 14 clases, ninguna fila de `confia.generated` en `jacoco.csv`, todas las reglas cumplidas |
| 28 | Mutación no evaluada sobre código generado | — | **EJECUCIÓN**. PIT acota `targetClasses` a `com.confia.*.domain.*`; el paquete generado vive en `confia.generated.jooq`, fuera de `com.confia`, y nunca puede coincidir. Observado: 49 mutantes, todos de `com.confia.organization.domain` |

#### `infrastructure` deja de ser una capa opcional

| # | Escenario | Método | Estado |
|---|---|---|---|
| 29 | Clase de `infrastructure` con dependencia inválida falla tras la retirada | `LayeredArchitectureTest.productionCodeRespectsLayering`, ya sin `optionalLayer("Infrastructure")` | **PARCIAL** — ver W6-bis |
| 30 | El conteo de `optionalLayer(` es exactamente uno | `SuppressionCitesAdrTest.everySuppressionCitesAnExistingAdrAndNoGlobalEscapeHatchRemains`. Matiz: afirma igualdad contra el inventario, no contra el literal `1`; el inventario tiene exactamente una entrada, y confirmé por búsqueda directa que solo hay una invocación | **PRUEBA** |

### 2.2 `specs/organization/spec.md` — 4 requisitos, 13 escenarios

#### (MODIFIED) Puerto de salida para cargar una institución por identificador

| # | Escenario | Método | Estado |
|---|---|---|---|
| 31 | Carga con dobles en memoria | `ResolveCurrentInstitutionTest.inMemoryRepositoryReturnsARegisteredInstitutionForItsIdentifier` | **PRUEBA** (preexistente del cambio 4, sigue en verde) |
| 32 | Ausencia de resultado para identificador desconocido | `ResolveCurrentInstitutionTest.inMemoryRepositoryReturnsAnAbsentResultForAnyUnregisteredIdentifier` | **PRUEBA** |

La frase modificada del requisito está reflejada en el Javadoc de
`apps/api/app/src/main/java/com/confia/organization/application/InstitutionRepository.java`,
actualizado en la tarea 2.3.

#### Contrato observable del adaptador jOOQ contra la base real

| # | Escenario | Método | Estado |
|---|---|---|---|
| 33 | Reconstrucción fiel de cada atributo | `JooqInstitutionRepositoryIT.reconstructsEveryAttributeFromTheRealSchema` | **PRUEBA**, con un atributo sin cubrir: ver W5 (`is_active = false`) |
| 34 | Reconstrucción con nombre comercial ausente | `JooqInstitutionRepositoryIT.reconstructsAnAbsentTradeNameAsNullNeverAsAnEmptyString` | **PRUEBA** |
| 35 | Ausencia de resultado para identificador desconocido | `JooqInstitutionRepositoryIT.returnsAnEmptyOptionalForAnUnknownIdentifierWithoutThrowing` | **PRUEBA** |
| 36 | Rechazo de una fila duplicada | `JooqInstitutionRepositoryIT.rejectsASecondRowWithTheSameIdentifier`, que espera `DataAccessException` con `organization_institution_pkey` | **PRUEBA** |

#### Aislamiento por fila de la tabla raíz según ADR-0009

| # | Escenario | Método | Estado |
|---|---|---|---|
| 37 | Una institución no puede leer la fila de otra | `JooqInstitutionRepositoryIT.aSessionCannotReadAnotherInstitutionsRow`; además `MultiTenantSchemaIT.twoInstitutionsAreIsolatedAndAnAbsentOrEmptyContextDeniesRatherThanErrors` | **PRUEBA** |
| 38 | Contexto de sesión ausente deniega en vez de fallar | `JooqInstitutionRepositoryIT.aSessionWithNoContextAtAllIsDeniedRatherThanErroring`; también en `MultiTenantSchemaIT` | **PRUEBA** |
| 39 | Contexto de sesión vacío deniega en vez de fallar | `JooqInstitutionRepositoryIT.aSessionWithAnEmptyContextIsDeniedRatherThanErroring`; también en `MultiTenantSchemaIT` | **PRUEBA** |
| 40 | Una institución sí puede leer su propia fila | `JooqInstitutionRepositoryIT.aSessionCanReadItsOwnRowEvenWhileAnotherInstitutionExists`; control positivo equivalente en `MultiTenantSchemaIT` | **PRUEBA** |

Las cuatro pruebas corren sobre una conexión `confia_admin_app`, nunca sobre el superusuario
`postgres` del contenedor. Eso es lo que impide que `FORCE ROW LEVEL SECURITY` se omita en
silencio y que estos cuatro escenarios pasen por la razón equivocada. Verificado en
`create-test-roles.sql`: los cinco roles se crean `NOSUPERUSER NOBYPASSRLS`, y el único `GRANT` de
rol es `pg_read_all_data` a `confia_backup`, que en PostgreSQL no otorga `BYPASSRLS`.

#### La longitud de la columna del RTN es una guarda técnica, no una regla fiscal

| # | Escenario | Método | Estado |
|---|---|---|---|
| 41 | El comentario de columna declara la guarda técnica | `MultiTenantSchemaIT.theRtnColumnCommentDeclaresATechnicalGuardNotAFiscalRule`, que lee `col_description` del catálogo real | **PRUEBA** |
| 42 | La base rechaza un RTN que excede la guarda | `MultiTenantSchemaIT.theDatabaseRejectsAnRtnLongerThanTheTechnicalGuardEvenBypassingTheDomain` | **PRUEBA** |
| 43 | Un RTN de longitud válida se reconstruye sin alteración | `JooqInstitutionRepositoryIT.reconstructsEveryAttributeFromTheRealSchema` (RTN `08011999123456`, 14 dígitos) | **PRUEBA** |

---

## 3. Auditoría de verificación aparente

El propietario corrigió durante la entrega cuatro pruebas que pasaban sin verificar nada. Confirmo
que las correcciones están en el árbol actual y busqué más casos de la misma familia.

### 3.1 Correcciones confirmadas en `main`

| Defecto original | Estado en `main` @ `5fb1c4c` |
|---|---|
| `information_schema`, filtrada por privilegios, en `baseTablesInPublicSchema()` | **Corregido.** La consulta lee `pg_class` + `pg_namespace` + `pg_attribute` (`relkind in ('r','p')`, `attnotnull`, `not attisdropped`). El Javadoc documenta el defecto y el control negativo que lo encontró. |
| Regex de prefijo `^[a-z][a-z0-9]*_[a-z0-9_]+$`, que aceptaba cualquier prefijo | **Corregido.** `productionModuleNames()` deriva el conjunto de módulos de los paquetes de producción reales con `ClassFileImporter` y `DO_NOT_INCLUDE_TESTS`, el mismo criterio de `NoCrossModuleDomainImportsTest`. |
| `assertThat(ROOT_TABLE).startsWith("organization_")`, constante contra literal | **Eliminada.** No aparece en el árbol. En su lugar hay una afirmación de no-vacuidad sobre datos reales: `assertThat(modules).contains("organization")`. |

### 3.2 Casos nuevos encontrados

No encontré ninguna tautología ni ninguna aserción que no ejercite código real. Los casos de abajo
son de **bajo poder discriminante**, no de falsedad: verifican algo, pero menos de lo que su nombre
promete.

1. **`PostgresImageSingleSourceTest.postgresIntegrationTestUsesTheSameFilteredImage`.** Compara
   `PostgresIntegrationTest.postgresImage()` contra una relectura de `confia-build.properties`.
   Ambos lados leen el mismo archivo: el método bajo prueba es literalmente
   `properties.getProperty("postgres.image")` sobre ese recurso. Solo puede fallar si alguien
   reintroduce un literal en `PostgresIntegrationTest`. No comprueba que el contenedor arranque con
   esa imagen, ni que el complemento de generación lea la misma propiedad. Es el caso más cercano a
   la familia que el propietario corrigió. Ver W2 y S3.
2. **Rama de la tabla raíz en `everyUniqueIndexOfABusinessTableIncludesTheInstitutionDiscriminator`.**
   Con `requiredColumn = "id"`, afirmar que el índice único de la clave primaria sobre `id`
   contiene `id` es cierto por construcción de PostgreSQL. Es la lectura literal del escenario 26,
   así que no es un defecto, pero no aporta señal. La rama útil (`institution_id`) tiene cero
   ejecuciones hoy.
3. **`everyTableWithInstitutionIdAndTheRootTableHaveRowLevelSecurityEnabledAndForced`.** Hoy el
   bucle solo evalúa `organization_institution`: `flyway_schema_history` no tiene `institution_id`
   y se salta. La puerta está probada en ambas direcciones por el control negativo del propietario,
   pero su alcance efectivo actual es una sola tabla.
4. **`DatabasePipelineIT.applicationConnectsAsConfiaAdminAppWithAHealthyFlywayHistory`.** Afirma
   `to_regclass('public.flyway_schema_history') is not null`, es decir, existencia de la tabla. El
   Javadoc de la clase sigue diciendo «leaving a row in `flyway_schema_history`». La rebaja a
   existencia fue una desviación correctamente documentada de la tarea 1.4, válida en PR A1, donde
   no había ninguna migración de negocio. Desde que A2 entregó `V1`, la tabla sí tiene fila y la
   aserción fuerte ya es alcanzable, pero no se restauró. Ver S4.
5. **`productionModuleNames()`** deriva el conjunto de módulos de todo paquete bajo `com.confia.`,
   lo que incluye `bootstrap` y `kernel`. Una tabla llamada `kernel_algo` o `bootstrap_algo`
   pasaría la puerta de prefijo. Ver S2.
6. **`NoCrossModuleDomainImportsTest.rejectsTheFixtureCrossModuleDomainImport`** invoca
   `assertRuleRejects(RULE, fixtureClasses())` sin fragmento de mensaje esperado, a diferencia de
   las cuatro reglas nuevas, que sí nombran su fixture. Solo descarta el ruido de conjunto vacío.
   Es una prueba preexistente, fuera del alcance de este cambio. Ver S6.

### 3.3 Mecanismos antifalso-positivo que sí funcionan

- `ArchitectureTestSupport.assertRuleRejects` exige `hasMessageNotContaining("failed to check any classes")`
  y `hasMessageNotContaining("is empty")`, más los fragmentos que cada llamante pasa. Las cuatro
  reglas nuevas nombran su fixture: `BadJooqUser`, `BadForeignTableUser`,
  `BadTransactionalRepository`, `BadPlainSqlRepository`.
- Ninguna regla nueva usa `allowEmptyShould(true)`; la búsqueda en el árbol devuelve cero
  invocaciones, consistente con el inventario vacío de ese marcador.
- `MultiTenantSchemaIT` lee siempre `pg_catalog`, nunca `information_schema`, y lo documenta como
  regla general.
- `PostgresIntegrationTest` arranca el contenedor como `postgres` y conecta la aplicación como
  `confia_admin_app`, que es precisamente lo que impide el verde falso de RLS.

---

## 4. Desviaciones documentadas

Revisé cada desviación registrada en `apply-progress.md`. Todas están documentadas en el
repositorio antes de la fusión y **ninguna cambia el comportamiento acordado en las dos
especificaciones delta**.

| # | Desviación | Dónde está documentada | ¿Cambia comportamiento de especificación? |
|---|---|---|---|
| D1 | `design.md` §11 paso 8 asigna `MultiTenantSchemaIT` puntos 1–3 a A2; la tabla §5 la asigna entera a A3 | Nota de reconciliación de `tasks.md`, que sigue §5 como autoritativa | No. Es asignación de corte, no de contenido. |
| D2 | `design.md` §5 y `proposal.md` exigen dos variantes de `PostgresIntegrationTest`; solo existe `TransactionalPostgresIntegrationTest` | `apply-progress.md`, discrepancia reportada en PR A1 y resuelta en PR A2 | No: ninguna especificación delta menciona las variantes. Sí deja `design.md` describiendo un archivo inexistente. Ver W6. |
| D3 | La tarea 1.4 pedía `flyway_schema_history` «con al menos una fila»; se implementó existencia por catálogo | `apply-progress.md`, consecuencia adicional de la tarea 1.5 | No. Ninguna especificación delta lo exige. Hoy es una rebaja innecesaria. Ver S4. |
| D4 | La tarea 2.2 pedía ejecutar un subconjunto de `JooqInstitutionRepositoryIT` antes de que exista el adaptador; Java no compila archivos por partes | `apply-progress.md`, nota de secuenciación 2.1/2.2, con prueba independiente del esquema vía `generate-sources` | No. El rojo real se registró como error de compilación. |
| D5 | Tarea 3.3: el esbozo `noClasses()...should(customCondition)` de `design.md` §6 no disparaba la condición; se usó `classes().should(condition)` | `apply-progress.md`, «Task 3.3 deviation» | No. `design.md` §6 marca sus esbozos como «esbozo; la firma exacta la fija la implementación». |
| D6 | Tarea 3.5b: la aserción esperaba el CHECK `organization_institution_rtn_digits`; lo que dispara primero es el límite `VARCHAR(20)` | `apply-progress.md`, tabla de evidencia TDD de 3.5b | No. El escenario 42 pide «la restricción de longitud de la columna», que es exactamente lo que se verifica. |
| D7 | El complemento de generación necesitó sobrescribir sus propias dependencias (Testcontainers 1.19.1→2.0.5, jOOQ 3.18.3→3.21.7) | `apply-progress.md`, sonda S1 (a) | No. Se adoptó la ruta A de `design.md`; no se elevó ADR ni se comprometió código generado. |
| D8 | Spring Boot 4.1 no trae autoconfiguración de jOOQ, contra el supuesto de `design.md` decisión 7; el `DSLContext` se declara a mano | `apply-progress.md`, sonda S1 (c), y nota en `application.yml` sobre `spring.jooq.sql-dialect` inerte | No. |
| D9 | `withTmpFs` debe montar `/var/lib/postgresql`, no `.../data`, para `postgres:18-alpine` | `apply-progress.md`, S1.5, y Javadoc de `PostgresIntegrationTest` | No para las especificaciones delta, pero `docs/06` §14.2 quedó sin corregir. Ver W4. |
| D10 | `<append>false</append>` en `jacoco-prepare-agent` rompía la cobertura con Surefire y Failsafe en la misma construcción | `apply-progress.md`, hallazgo 3 de la tarea 1.7 | No. Corrección de un defecto preexistente. |
| D11 | Corrida anómala de 9 min 13 s en la tarea 3.9, no reproducible | `apply-progress.md`, nota propia de la tarea 3.9 | No. No se reprodujo en mi verificación: ~30 s. |
| D12 | PR A3 se dividió en A3a y A3b al medir 813 líneas | Encabezado de la sección «PR A3» de `tasks.md` y tarea 3.8 de `apply-progress.md` | No. Decisión de entrega, tomada con el propietario. |

**Conclusión:** las doce desviaciones están documentadas en el repositorio, no solo en Engram, y
ninguna altera un requisito ni un escenario de las especificaciones delta. D2 y D9 dejan
documentación desalineada con el código y se reportan como WARNING.

---

## 5. Hallazgos

### CRITICAL — 0

Ninguno. La construcción completa pasa, incluida la puerta de mutación de `main`, y no queda
ninguna aserción tautológica ni ninguna prueba que no ejercite código real.

### WARNING — 6

**W1. El presupuesto de 8 minutos no tiene mecanismo de incumplimiento.**
El escenario 18 exige que la integración continua reporte el incumplimiento y que el corte se
considere fallido cuando la suite `*IT.java` supere 8 minutos. `.github/workflows/ci.yml` fija
`timeout-minutes: 15` sobre el trabajo `backend` completo, que cubre además `kernel`, la generación
de jOOQ y PIT. Una suite de integración de 10 minutos dentro de un trabajo de 14 terminaría en
verde. El presupuesto hoy se mide y se documenta, pero no se exige.
*Evidencia:* `.github/workflows/ci.yml` líneas 31–61; ninguna aserción sobre el tiempo de Failsafe
en el árbol de pruebas.

**W2. La prueba de fuente única no cubre al otro consumidor de la versión.**
El escenario 15 describe la divergencia entre el complemento de generación y
`PostgresIntegrationTest`. `PostgresImageSingleSourceTest` solo observa el lado de
`PostgresIntegrationTest`, y lo hace comparando `confia-build.properties` consigo mismo. Si
`apps/api/app/pom.xml` cambiara `<containerImage>${confia.postgres.image}</containerImage>` por un
literal divergente, ninguna prueba fallaría. Hoy la configuración es correcta, verificada por
lectura del POM, pero la puerta no la protege.
*Evidencia:* `apps/api/app/pom.xml:266`; `PostgresImageSingleSourceTest:46-57`.

**W3. Nada impide nombrar `*Test` una clase que necesita contenedor.**
El escenario 17 describe el incumplimiento de la convención. No existe regla de ArchUnit ni
comprobación de Maven que exija que toda subclase de `PostgresIntegrationTest` termine en `IT`. El
riesgo no es teórico: `PostgresImageSingleSourceTest` vive en el mismo paquete `support` y es una
clase `*Test`; bastaría que alguien la hiciera extender la base para arrancar un contenedor bajo
Surefire.

**W4. `docs/06-estrategia-de-testing.md` §14.2 sigue prescribiendo un patrón que produce un verde
falso de seguridad a nivel de fila.**
El ejemplo mantiene `.withUsername("confia_owner")` y
`.withTmpFs(Map.of("/var/lib/postgresql/data", ...))`. La implementación demostró que el primero
convierte al propietario del esquema en superusuario con `BYPASSRLS`, lo que omite en silencio
`FORCE ROW LEVEL SECURITY` y haría pasar por la razón equivocada todas las pruebas de aislamiento;
y que el segundo impide que `postgres:18-alpine` arranque. `PostgresIntegrationTest` documenta
ambas correcciones en su Javadoc, pero el documento que la especificación cita como referencia
(`docs/06` línea 68, §14.2) no se actualizó. El ejemplo está marcado «Illustrative», lo que reduce
el riesgo sin eliminarlo: es exactamente la clase de defecto que esta entrega ya pagó por aprender.
La «Definición de terminado» de `CLAUDE.md` pide actualizar la documentación afectada.
*Evidencia:* `docs/06-estrategia-de-testing.md:629,633`; el último commit que tocó ese archivo es
anterior a este cambio.

**W5. La rama `is_active = false` del adaptador no tiene prueba.**
`JooqInstitutionRepository.toDomain` aplica `institution.deactivate()` cuando la fila está
inactiva. Ninguna prueba siembra una fila con `is_active = false`:
`reconstructsEveryAttributeFromTheRealSchema` afirma `isActive()` verdadero. El escenario 33 pide
reconstruir «cada atributo»; `is_active` es uno de ellos y su valor no trivial no se verifica.
*Evidencia medida, no inferida:* `apps/api/app/target/site/jacoco/jacoco.csv`, fila
`com.confia.organization.infrastructure,JooqInstitutionRepository` → `BRANCH_MISSED=1`,
`BRANCH_COVERED=1`, `LINE_MISSED=1`, `COMPLEXITY_MISSED=1`. Es la única rama sin cubrir de la única
clase de producción que este cambio añade.

**W6. `design.md` §5 y `proposal.md` describen un archivo que no existe.**
Ambos exigen dos variantes de `PostgresIntegrationTest`; `CommittingPostgresIntegrationTest` no se
creó, por decisión documentada y razonable: nada en la parte A necesita confirmación real ni
truncamiento selectivo. La desviación está reportada dos veces en `apply-progress.md`, pero
`design.md` §5 líneas 542–543 sigue listando el archivo como «Crear … A1». Al archivar, esa fila
quedará en el registro histórico como si se hubiera entregado.

**W6-bis (asociado al escenario 29).** La retirada de `optionalLayer("Infrastructure")` está
verificada, pero ningún fixture prueba que una clase *de `infrastructure`* con dependencia inválida
rompa ahora la construcción. `rejectsTheFixtureLayeringViolations` exige que el mensaje nombre
`BadDomain`, `BadApplication` y `BadWeb`; el fixture `SomeInfrastructureType` existe, pero es
válido por construcción. La garantía descansa en que `productionCodeRespectsLayering` ya no declara
la capa como opcional, lo cual es cierto y comprobable, pero no está ejercitado por una violación
deliberada como sí lo están las otras tres capas.

### SUGGESTION — 8

- **S1.** Los escenarios 1 y 3 (ruptura de compilación por esquema incompatible; código generado
  ausente del repositorio) no tienen prueba automatizada. El primero es difícil de automatizar sin
  una construcción anidada; el segundo se resolvería con una prueba que afirme que `git ls-files`
  no devuelve nada bajo `confia/generated`, o con una regla de `maven-enforcer`.
- **S2.** Acotar `productionModuleNames()` a los módulos de negocio, excluyendo `bootstrap` y
  `kernel`, para que `kernel_algo` no pase la puerta de prefijo.
- **S3.** Reforzar `postgresIntegrationTestUsesTheSameFilteredImage` afirmando contra
  `POSTGRES.getDockerImageName()`, y añadir una comprobación de que `app/pom.xml` usa
  `${confia.postgres.image}` en `<containerImage>`. Cierra W2.
- **S4.** Restaurar en `DatabasePipelineIT` la aserción de «al menos una fila» en
  `flyway_schema_history`, ya alcanzable desde A2, y corregir el Javadoc de la clase, que aún la
  describe.
- **S5.** Las dos restricciones `CHECK` de la migración, `rtn ~ '^[0-9]{1,20}$'` y
  `default_currency IN ('HNL','USD')`, no tienen prueba. Ninguna especificación delta las exige,
  pero son defensa de base de datos sobre datos de negocio.
- **S6.** Homogeneizar `NoCrossModuleDomainImportsTest.rejectsTheFixtureCrossModuleDomainImport`
  con las cuatro reglas nuevas, pasando el nombre del fixture como fragmento esperado.
- **S7.** `postgres:18-alpine` es una etiqueta móvil en una dependencia real de construcción, no
  solo de prueba. Ya está registrado como seguimiento en `docs/09-roadmap-y-fases.md` (commit
  `118a415`), ligado al cambio que introduzca Renovate. Se repite aquí para que no se pierda.
- **S8.** `specs/organization/spec.md` abre con una nota de archivado: el punto «Persistencia real»
  de «Fuera de alcance» de `openspec/specs/organization/spec.md` deja de ser cierto con este cambio
  y debe retirarse o reescribirse al archivar. No lo alcanza el mecanismo ADDED/MODIFIED.

---

## 6. Estado de tareas observado

Registro, sin modificar: **27 de 27 tareas marcadas `[x]`** en `tasks.md` — 10 en PR A1, 7 en PR A2,
9 más la tarea intercalada 3.5b en PR A3. No se observa ninguna tarea abierta.

Evidencia TDD estricta: `apply-progress.md` contiene tabla de ciclo TDD para los tres cortes. El
rojo se registra siempre como error real de compilación (`cannot find symbol`), nunca como
evidencia inventada, y se explica por qué un archivo Java compila entero o no compila, de modo que
el rojo no pudo dividirse en commits separados. Los dos casos donde el rojo no existía de forma
genuina —la retirada del `optionalLayer` de la tarea 2.4 y los cuatro métodos de aislamiento de la
tarea 2.5— están declarados como tales en lugar de presentarse como ciclos completos. Considero la
evidencia TDD honesta y suficiente.

Medición de tamaño: A1 727, A2 424, A3 768 líneas de autor, todas bajo el presupuesto de 800 de
`docs/15-flujo-de-trabajo-git.md` §3. Medición propia del cambio completo
(`git diff --numstat 9b77399~1...HEAD`, excluyendo `openspec/`, `docs/adr/` y el paquete generado):
**1 502 adiciones más 35 eliminaciones = 1 537 líneas de autor**, dentro del pronóstico de
`design.md` §12 (1 447 a 2 415).

---

## 7. Limitaciones de esta verificación

- **No ejecuté controles negativos propios.** El encargo prohíbe modificar archivos de código o de
  especificación, y un control negativo exige alterar temporalmente una migración o un fixture. Las
  afirmaciones de «esta puerta dispara de verdad» se apoyan en los controles negativos que el
  propietario ya ejecutó y registró (`tmp_import` para el defecto de `information_schema`; retirada
  de `FORCE ROW LEVEL SECURITY` para la puerta de RLS), no en ejecuciones mías. Lo que sí verifiqué
  yo, por lectura del código, es que esas correcciones están presentes en `main` y que las
  consultas leen `pg_catalog`.
- **Verificación en Windows con Docker Desktop.** `design.md` §13 designa la integración continua en
  `ubuntu-latest` como fuente de verdad ante divergencias. Mis tiempos son locales.
- **No verifiqué la integración continua.** El propietario reporta las cuatro corridas en verde; no
  consulté GitHub Actions.
- **Las afirmaciones sobre ramas de puerta no ejercitadas** (escenarios 22, 25 y la rama de negocio
  del 23) son observaciones estáticas sobre el estado actual del esquema, no pruebas de que la
  puerta fallaría. Solo un control negativo lo demostraría.
- **PIT se ejecutó con el perfil de `main`** (`-Dconfia.ci.mainBranch=true`), que activa
  `mutation-gate`. No ejecuté `-Pmutation-report`, que no bloquea.

---

## 8. Resumen y recomendación

La implementación cumple sustancialmente las dos especificaciones delta. La construcción completa
está en verde en dos configuraciones distintas, 307 pruebas pasan sin fallos, la cobertura y la
mutación cumplen sus umbrales, el código generado está fuera del repositorio y fuera de las
métricas, y las cuatro reglas de ArchUnit rechazan sus fixtures nombrándolos. No queda ninguna
prueba tautológica.

Los seis WARNING son de tres naturalezas: cosas que la especificación pide y que hoy descansan en
disciplina humana en lugar de en una puerta automática (W1, W2, W3), documentación que se quedó
atrás del código con un riesgo concreto de verde falso futuro (W4, W6), y un atributo del contrato
del adaptador sin verificar (W5). Ninguno invalida lo entregado.

**Recomendación:** proceder a `sdd-archive`. Estos hallazgos son diagnósticos y no condicionan la
admisión al archivo. W4 y W5 merecen tratarse pronto; W1, W2 y W3 encajan naturalmente como trabajo
de la parte B (`audit-log-and-transaction-runner`) o del cambio que introduzca Renovate. Al
archivar, atender la nota de S8 y considerar añadir a `design.md` una nota de cierre sobre D2 (W6),
para que el registro histórico no afirme que se entregó un archivo que no existe.

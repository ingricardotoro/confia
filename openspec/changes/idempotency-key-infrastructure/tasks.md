# Tareas: infraestructura de clave de idempotencia

Cambio 6 de F0. Implementa `proposal.md`, `design.md` y el delta de especificación ya escrito
(`specs/build-integrity/spec.md`, 11 requisitos, 19 escenarios). Cada tarea traza a un requisito y a
sus escenarios; `design.md` §7.1 ya trazó los 19 uno a uno y esta lista respeta esa asignación de
corte.

## Sin dependencia mecánica de orden entre cortes

A diferencia del cambio 5, `design.md` §1 verifica por lectura que **no existe** ninguna dependencia
mecánica de orden: `com.confia.shared.*` ya tiene tres paquetes de producción, así que la puerta de
prefijo de módulo de `MultiTenantSchemaIT` ya reconoce `shared` antes de que exista ninguna clase de
este cambio. La migración `V4` (PR C1) puede llegar primero sin crear antes ninguna clase Java. La
única coupling real es de compilación, no de puerta: **PR C1 y PR C2a-1 no se revierten por
separado** una vez que C2a-1 existe, porque el adaptador jOOQ se genera desde la migración y deja de
compilar si la tabla desaparece (`design.md` §9, punto 3).

## Discrepancia encontrada y reportada: el orden de `design.md` §11 contradice la subdivisión de §12

`design.md` §11, pasos 5-8, ordena el corte C2a así: sondas S4/S6 → RED/VERDE del *hasher* → RED/VERDE
del catálogo de errores → RED/VERDE del puerto y el adaptador. Pero `design.md` §12 subdivide ese
mismo corte por **contenido**, no por ese orden: **C2a-1** = «puerto, registro, adaptador y
`JooqIdempotencyRecordStoreIT`»; **C2a-2** = «hasher, tipos de resultado, excepciones, catálogo de
códigos y pruebas unitarias». Las dos secciones son del mismo documento y se contradicen: si las
excepciones (`IdempotencyConflictException`, `IdempotencyPayloadMismatchException`) viajan en C2a-2,
el adaptador de C2a-1 **no puede compilar**, porque `design.md` §6.3 hace que
`JooqIdempotencyRecordStore.translate(...)` devuelva exactamente esos dos tipos ante `55P03`, y el
puerto de §6.2 firma `lockExisting(InstitutionId, IdempotencyKey)` y
`complete(..., IdempotentResponse, ...)`, con lo que `IdempotencyKey` e `IdempotentResponse` tampoco
pueden esperar a C2a-2.

**Se reporta, no se resuelve en silencio, y esta lista adopta la única partición que compila en cada
corte:** `IdempotencyKey`, `IdempotentResponse`, las dos excepciones y su catálogo de códigos se
mueven a **C2a-1**, junto al puerto y al adaptador que los necesitan para compilar. **C2a-2** queda
con el hasher y `IdempotentOutcome` (que ningún tipo de C2a-1 consume; solo lo consumirá
`IdempotentExecutor` en C2b). Quien archive este cambio debe corregir `design.md` §11 y §12 para que
dejen de contradecirse.

## Sondas S1 y S2, ya ejecutadas — NO se vuelven a planificar

El orquestador las corrió el 2026-09-22 contra `postgres:18-alpine`, fuera del árbol del repositorio,
con la tabla y la clave primaria natural que este diseño propone (`design.md`, sección final). **S1**
(bloqueante del cambio entero): `lock_timeout` local a la transacción **sí** acota la espera de la
inserción colisionante, cancela a los ~250 ms, y ninguna de las dos escaleras de respaldo hizo falta.
**S2**: agotamiento de espera da `55P03`; clave duplicada da `23505`; reversión de la primera no da
error. Las cuatro sondas restantes de `design.md` §10 —S3, S4, S5, S6— **no** están ejecutadas y se
corren como tarea explícita dentro del corte que cada una bloquea.

## Nota sobre la política de sesión (caducada, no se reabre)

El bloque de preflight de esta sesión proyecta `single-pr` y 400 líneas. Es la **quinta fase
consecutiva** —exploración, propuesta, especificación, diseño y ahora tareas— que encontraría la
misma falsa alarma si leyera esa fuente caducada del registro de `sdd-init` del 2026-09-15. Los
valores vigentes, resueltos por el propietario el 2026-09-22 y ya registrados en `exploration.md`,
`proposal.md` y `design.md`, son **`auto-chain`** y **800 líneas por pull request**
(`docs/15-flujo-de-trabajo-git.md` §3, `CLAUDE.md`). Esta lista planifica contra esos valores
vigentes y no vuelve a plantear la pregunta.

## Review Workload Forecast

| Campo | Valor |
|---|---|
| Presupuesto de revisión de esta sesión (guardia literal de la fase) | 400 líneas |
| Presupuesto de revisión vigente para este cambio (propietario, 2026-09-22) | **800 líneas** de cambio efectivo por pull request — prevalece; ver nota arriba |
| Líneas de autor estimadas (código; sin `openspec/`, sin código generado de jOOQ) | 1 960 a 3 200, según `design.md` §12 «Pronóstico de tamaño por corte» |
| Riesgo frente al presupuesto de 400 (guardia literal de la fase) | **High** — cualquier corte, incluso en su extremo bajo, se acerca o supera 400 |
| Riesgo frente al presupuesto de 800 vigente | **Medium** — C1, C2a-2, C2b, C2c y C3 caben en todo su rango; C2a-1 (puerto, excepciones, adaptador y su prueba de integración) es el más cargado tras la reconciliación de la sección anterior y puede rozar el límite alto |
| Pull requests encadenados recomendados | Sí — ya decidido por el propietario (`proposal.md`, «Pronóstico de cortes y tamaño»; `design.md` §12) |
| División sugerida | PR C1 → PR C2a-1 → PR C2a-2 → PR C2b → PR C2c → PR C3, cada uno base del siguiente |
| Estrategia de entrega | `auto-chain` |
| Estrategia de cadena | `stacked-to-main` — precedente de los cambios 2, 4 y 5 (parte A y parte B), sin decisión nueva del propietario que lo cambie para este cambio; se adopta por continuidad y se deja constancia de que es una inferencia de precedente, no una instrucción explícita para este cambio |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

### Nota sobre el límite de quince tareas por pull request

Esta lista tiene **40 tareas en total** (7 en PR C1, 7 en PR C2a-1, 5 en PR C2a-2, 5 en PR C2b, 7 en
PR C2c, 9 en PR C3). Se aplica el criterio ya aceptado por el propietario en los cambios 2, 4 y 5: el
límite de quince tareas de `openspec/changes/README.md` rige **por pull request**, no por cambio
completo. Cada pull request queda muy por debajo de quince; el total del cambio completo lo supera si
se mide de esa otra forma, y se deja constancia sin decidirlo aquí.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| C1 | Tabla `shared_idempotency_key`, política de fila forzada, privilegios exactos y extensión de las puertas de esquema existentes | PR C1 (`change/idempotency-key-infrastructure`, base `main`) | `./mvnw -B -pl apps/api/app -am test -Dtest=RolePrivilegeMatrixIT,IdempotencyKeyPrivilegeIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir el pull request completo; sin ningún consumidor Java todavía. No se revierte por separado de PR C2a-1 una vez que ese corte exista (el adaptador jOOQ deja de compilar sin la migración) |
| C2a-1 | Puerto, tipos que el puerto firma, excepciones con código estable y el único adaptador que toca jOOQ, con la traducción de `SQLState` | PR C2a-1 (`...-store`, base PR C1) | `./mvnw -B -pl apps/api/app -am test -Dtest=IdempotencyErrorCodesTest,JooqIdempotencyRecordStoreIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C2a-1 sin fusionar deja PR C1 completo por sí solo (tabla y privilegios sin consumidor Java). Fusionado, no se revierte sin revertir también C1 |
| C2a-2 | Hash de la carga canonicalizada, reutilizando `CanonicalAuditRowSerializer`, y el tipo de resultado que distingue ejecución de repetición | PR C2a-2 (`...-hasher`, base PR C2a-1) | `./mvnw -B -pl apps/api/app -am test -Dtest=RequestPayloadHasherTest -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C2a-2 sin fusionar deja PR C2a-1 completo por sí solo (puerto y adaptador probados, sin hash todavía) |
| C2b | `IdempotentExecutor`: clave nueva, repetición sin reejecutar, carga distinta rechazada, atomicidad marcador-efecto, sin concurrencia | PR C2b (`...-executor`, base PR C2a-2) | `./mvnw -B -pl apps/api/app -am test -Dtest=IdempotentExecutorIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C2b sin fusionar deja PR C2a-2 completo por sí solo (mecanismo de almacenamiento y hash, sin el componente que los orquesta) |
| C2c | `lock_timeout` local a la transacción, sus tres salidas por `SQLState`, la segunda transacción de repetición y el no reintento | PR C2c (`...-concurrency`, base PR C2b) | `./mvnw -B -pl apps/api/app -am test -Dtest=IdempotentExecutorConcurrencyIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C2c sin fusionar deja PR C2b completo por sí solo (camino secuencial correcto; degradación conocida a bloqueo sin límite si hiciera falta, `proposal.md` «Plan de reversión» punto 6) |
| C3 | Criterio de salida 4 sobre efecto contable, reutilización de clave caducada, los tres inventarios de exclusión, y el cierre documental | PR C3 (`...-exit-criterion`, base PR C2c) | `./mvnw -B -pl apps/api/app -am test -Dtest=IdempotencyExitCriterionIT,IdempotencyExpiryIT,IdempotencyScopeExclusionInventoryTest -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C3 sin fusionar deja PR C2c completo por sí solo (mecanismo entero probado, sin la demostración del criterio 4 ni la documentación cerrada) |

Ejecutor de todas las tareas: `./mvnw -B verify` en `apps/api`, con `JAVA_HOME` apuntando a JDK 25 y
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (`design.md` §13, esta última nunca se
compromete). Docker debe estar activo para toda tarea que ejecute una clase `*IT.java` real contra
PostgreSQL o que dispare `generate-sources`; las tareas que solo tocan ArchUnit, inventario estático
de texto o documentación **no** requieren Docker, y cada tarea de abajo lo marca explícitamente. La
integración continua en `ubuntu-latest` es la fuente de verdad ante cualquier divergencia observada en
Windows/WSL2. Nunca se baja un umbral ni se omite una prueba para pasar en local.

TDD estricto (`openspec/config.yaml`, `strict_tdd: true`): toda clase `*IT.java` observa ROJO antes de
VERDE, y esa evidencia se registra **en el repositorio** —la línea correspondiente de esta lista y
`openspec/changes/idempotency-key-infrastructure/apply-progress.md` (crear el archivo si no existe)—,
nunca solo en Engram. **Nunca se inventa evidencia de ROJO.**

---

> **Estrategia de cadena confirmada por el orquestador: `stacked-to-main`.** La fase de tareas la
> adoptó por precedente de los cambios 2, 4 y 5 y lo declaró honestamente como inferencia, no como
> instrucción. Queda confirmada aquí: es la forma en que este repositorio ha entregado sus cinco
> cambios anteriores, cada pull request apuntando al anterior y fusionándose en orden, tal como fija
> `docs/15-flujo-de-trabajo-git.md` §3. La estrategia de entrega es `auto-chain`, con el presupuesto
> de ochocientas líneas.

## PR C1 — corte C1: tabla, política de fila y privilegios

Rama `change/idempotency-key-infrastructure` (rama actual), base `main`.

- [x] 1.1 **ROJO — permisos y privilegios reales.** Requiere Docker. Extender
  `.../test/java/com/confia/schema/RolePrivilegeMatrixIT.java` con las filas de
  `shared_idempotency_key` para los cinco roles de `docs/03-seguridad.md` §6.1, incluida la fila de
  `PUBLIC` (debe quedar sin ningún privilegio tras el `REVOKE ALL`) y la de un rol ajeno al esquema.
  Crear `.../test/java/com/confia/schema/IdempotencyKeyPrivilegeIT.java` con sentencias **reales**:
  `confia_admin_app` conectado con contexto de institución consigue `SELECT`, `INSERT` y `UPDATE`, y
  es rechazado en `DELETE`; `confia_portal_app` conectado con contexto de institución es rechazado en
  las cuatro. Fallan porque `shared_idempotency_key` no existe. Registrar el mensaje de fallo exacto
  en `apply-progress.md`. — Especificación `build-integrity`, requisitos «Permisos de acceso a
  `shared_idempotency_key` por rol de base de datos» (los tres escenarios) y «Ausencia de acceso del
  portal a `shared_idempotency_key` (brecha con destino: F3/F4)» (su escenario)

- [x] 1.2 **VERDE — `V4__create_shared_idempotency_key.sql`.** Requiere Docker. Crear
  `apps/api/app/src/main/resources/db/migration/V4__create_shared_idempotency_key.sql` con la tabla
  completa de `design.md` decisión 2 (clave primaria natural compuesta
  `(institution_id, endpoint, idempotency_key)`, las restricciones `CHECK` de `status`,
  `request_hash`, longitud de `endpoint` e `idempotency_key`, y la restricción de completado;
  `created_at` con `clock_timestamp()`; `ENABLE`/`FORCE ROW LEVEL SECURITY` con el patrón
  `NULLIF(current_setting(...), '')` idéntico a `V1`-`V3`); `REVOKE ALL ... FROM PUBLIC` **antes** de
  `GRANT SELECT, INSERT, UPDATE` a `confia_admin_app` y `GRANT SELECT` a `confia_readonly`, sin
  ningún `GRANT` para `confia_portal_app` (`design.md` decisión 3). **Trampa de texto, obligatoria de
  respetar aquí:** ningún comentario de esta migración que explique la purga diferida puede nombrar la
  tabla del programador de tareas de `db-scheduler` — `AuditScopeExclusionInventoryTest` concatena el
  texto de **todas** las migraciones entregadas y afirma que ese nombre no aparece en ninguna; se
  escribe «el cambio 9» y se cita el requisito de la especificación, nunca el identificador literal de
  esa tabla (`design.md` decisión 2, punto 7). Ejecutar las pruebas de la tarea 1.1: verde. —
  `design.md`, decisiones 2 y 3; especificación `build-integrity`, requisito «Tabla
  `shared_idempotency_key`, clave primaria natural y seguridad de fila forzada» (ambos escenarios)

- [x] 1.3 **Comprobar que `MultiTenantSchemaIT` pasa sin modificarse.** Requiere Docker. Ejecutar
  `MultiTenantSchemaIT` completa contra el esquema con `V4` aplicada. Si pasa sin tocar el archivo,
  continuar: es la consecuencia verificada en `design.md` §2 («¿`shared_idempotency_key` rompe alguna
  puerta genérica? Verificado por lectura: no») y §7.1. **Si no pasara, es una discrepancia con este
  diseño y se reporta; no se relaja la puerta ni se añade una excepción.** — Especificación
  `build-integrity`, requisito «Tabla `shared_idempotency_key`...» (escenario «Las puertas genéricas de
  esquema pasan sobre `shared_idempotency_key` sin lista de exclusión» y «La clave primaria compuesta
  actúa como discriminador de institución sin necesitar la excepción de la tabla raíz»)

- [x] 1.4 **Comprobar que `AuditScopeExclusionInventoryTest` sigue en verde tras el texto nuevo de
  `V4`.** No requiere Docker (inventario estático sobre el texto real de las migraciones, sin conexión
  a PostgreSQL). Ejecutar
  `AuditScopeExclusionInventoryTest.scheduledTasksDoesNotAppearInTheDeliveredSchemasMigrations` y
  confirmar que sigue en verde con `V4` ya en `db/migration`. Es la verificación directa de la trampa
  que la tarea 1.2 ya evitó al escribir el comentario; si esta prueba fallara, revisar el texto exacto
  de `V4` antes de tocar la prueba, nunca relajar su aserción. — `design.md` §2 y §14, punto 3

- [x] 1.5 **Medir el tiempo de la suite `*IT.java`.** Requiere Docker. Ejecutar `./mvnw -B verify`
  completo en `apps/api` y medir el tiempo real de la fase `integration-test` (Failsafe), con
  `IdempotencyKeyPrivilegeIT` incluida. Registrar el tiempo medido, no estimado, en
  `apply-progress.md`. — `design.md` §11, paso 4; §13

- [x] 1.6 **Medir el diff real de PR C1** con
  `git diff --numstat main...change/idempotency-key-infrastructure -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar. **Si supera 800, detener la aplicación y
  reportar los puntos de corte candidatos medidos** (no estimados) entre los commits de este corte,
  **verificando cada mitad propuesta por separado con `./mvnw -B verify`** antes de proponerla —
  `docs/15-flujo-de-trabajo-git.md` §3 exige que cada unidad de una cadena compile y pase sus pruebas
  sola, y el cambio 5 dejó dos puntos de corte aparentemente equilibrados que fallaban su propia puerta
  de cobertura por no cumplir esto. No decidir el corte: reportarlo. — `design.md` §12, «Pronóstico de
  tamaño por corte»

- [x] 1.7 **Verificación final de PR C1.** Requiere Docker. En checkout limpio, con `JAVA_HOME` en
  JDK 25 y `MAVEN_OPTS` con el almacén de confianza `Windows-ROOT`, ejecutar `./mvnw -B verify` en
  `apps/api`. Confirmar `RolePrivilegeMatrixIT` e `IdempotencyKeyPrivilegeIT` en verde completas,
  `MultiTenantSchemaIT` sin modificar y en verde, y `AuditScopeExclusionInventoryTest` en verde.
  Empujar la rama `change/idempotency-key-infrastructure` y confirmar en la integración continua que
  el trabajo `backend` termina en verde. — Criterios de éxito de la propuesta sobre la tabla, la
  seguridad de fila y los privilegios exactos

---

## PR C2a-1 — corte C2a (parte 1): puerto, tipos que firma, excepciones y adaptador jOOQ

Rama `change/idempotency-key-infrastructure-store`, base PR C1. Ver la sección de discrepancia
reportada arriba: esta partición reagrupa el contenido de `design.md` §12 para que cada mitad compile
por sí sola.

- [x] 2.1 **Ejecutar las sondas S4 y S6 (bloqueantes, `design.md` §10 y §11 paso 5).** Requiere
  Docker. **S4**: con el cableado real de `IntegrationTestApplication`, provocar `23505` y `55P03`
  contra `shared_idempotency_key` y confirmar por depuración o registro temporal qué excepción llega
  realmente al punto donde vivirá el adaptador (se espera
  `org.jooq.exception.DataAccessException` envolviendo la `SQLException` cruda, `design.md` §2), y que
  `TransactionRunner.isRetryable(...)` devuelve falso para ambas. **S6**: tras `generate-sources` con
  `V4` ya aplicada, confirmar el tipo Java que jOOQ genera para `response_body JSONB` y para
  `status TEXT` con `CHECK`, y que el tipo generado se llama `SharedIdempotencyKey` (si el nombre
  difiriera, R2 lo rechazaría y la ubicación del adaptador tendría que revisarse antes de escribirlo,
  `design.md` decisión 1). Registrar ambos resultados exactos en `apply-progress.md`. — `design.md`
  §10 (sondas S4, S6) y §11, paso 5

- [x] 2.2 **ROJO — catálogo de códigos de error.** No requiere Docker (JUnit puro, sin contenedor).
  Crear `.../test/java/com/confia/shared/security/IdempotencyErrorCodesTest.java`: afirma que
  `IdempotencyConflictException` y `IdempotencyPayloadMismatchException` son subclases de
  `com.confia.kernel.DomainException`, que sus códigos (`idempotency-conflict`,
  `idempotency-payload-mismatch`) siguen el formato kebab-case, miden 64 caracteres o menos, no se
  repiten entre sí ni con el catálogo existente, y comparten el prefijo `idempotency-`, siguiendo el
  mismo patrón que `OrganizationErrorCodesTest` y `KernelErrorCodesTest` ya establecen. Falla al
  compilar porque ninguna de las dos excepciones existe. — `design.md`, decisión 9 («Los códigos
  nuevos siguen la convención del catálogo por módulo»)

- [x] 2.3 **VERDE — las dos excepciones del contrato.** No requiere Docker. Crear
  `.../main/java/com/confia/shared/security/IdempotencyConflictException.java` (extiende
  `DomainException`, código `idempotency-conflict`, con un motivo `WAIT_EXHAUSTED`/
  `MARKER_IN_PROGRESS` en enumeración) y
  `.../main/java/com/confia/shared/security/IdempotencyPayloadMismatchException.java` (extiende
  `DomainException`, código `idempotency-payload-mismatch`). Ejecutar `IdempotencyErrorCodesTest`:
  verde. — `design.md`, decisión 9, tabla de excepciones

- [x] 2.4 **ROJO — puerto, tipos que firma y adaptador jOOQ con traducción de `SQLState`.** Requiere
  Docker (ejecuta 2.1). Crear
  `.../main/java/com/confia/shared/security/IdempotencyKey.java` y
  `.../main/java/com/confia/shared/security/IdempotentResponse.java` (records de `design.md` §6.1);
  `.../main/java/com/confia/shared/security/IdempotencyRecordStore.java` (puerto de §6.2, solo tipos
  del JDK: ningún `org.jooq.JSONB` ni `OffsetDateTime` cruza esta frontera, R1) y
  `.../main/java/com/confia/shared/security/IdempotencyRecord.java`. Crear
  `.../test/java/com/confia/shared/infrastructure/JooqIdempotencyRecordStoreIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: inserción nueva (`insertInProgress`), bloqueo de fila
  (`lockExisting` con `SELECT ... FOR UPDATE`), completado (`complete`), actualización de fila
  caducada (`restartExpired`), y las dos traducciones de `SQLState` —`23505` produce el tipo interno
  que señala clave ya tomada, `55P03` produce `IdempotencyConflictException(WAIT_EXHAUSTED)`—, **nunca
  por texto del mensaje**. Falla al compilar porque `JooqIdempotencyRecordStore` no existe. — 
  `design.md` §6.1-§6.3; especificación `build-integrity`, requisito «Espera acotada ante escritura
  concurrente del marcador, con salidas distinguibles por `SQLState`» (fundamento de la traducción, sin
  concurrencia real todavía — esa parte es C2c)

- [x] 2.5 **VERDE — `JooqIdempotencyRecordStore`.** Requiere Docker (ejecuta 2.4). Crear
  `.../main/java/com/confia/shared/infrastructure/JooqIdempotencyRecordStore.java` (único adaptador
  nuevo que toca `org.jooq..` y `confia.generated..`, R1): implementa los cuatro métodos del puerto
  sobre el tipo generado `SharedIdempotencyKey`, y el método privado `translate(...)` de `design.md`
  §6.3 que recorre la cadena de causas por código —nunca por texto—, devolviendo el tipo interno
  `IdempotencyMarkerAlreadyExists` ante `23505` e `IdempotencyConflictException(WAIT_EXHAUSTED)` ante
  `55P03`; ninguno de los dos extiende `ConcurrencyFailureException` de Spring, para que
  `TransactionRunner.isRetryable(...)` nunca los reintente aunque algún día aparezca un traductor de
  excepciones de jOOQ (`design.md` decisión 5). Ejecutar `JooqIdempotencyRecordStoreIT`: verde. —
  `design.md`, decisión 5 completa

- [x] 2.6 **Medir el diff real de PR C2a-1** con
  `git diff --numstat <base-de-PR-C1>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar. **Si supera 800, detener la aplicación y
  reportar los puntos de corte candidatos medidos, verificando cada mitad por separado con
  `./mvnw -B verify`**, sin separar nunca el puerto o el adaptador de su propia prueba de integración
  ni de las excepciones que necesita para compilar. — `design.md` §12

- [x] 2.7 **Verificación final de PR C2a-1.** Requiere Docker. En checkout limpio, ejecutar
  `./mvnw -B verify` en `apps/api` con JDK 25 y Docker activo. Confirmar `IdempotencyErrorCodesTest` y
  `JooqIdempotencyRecordStoreIT` en verde completas, incluidas las dos traducciones de `SQLState` por
  código. Empujar la rama `...-store` (apuntando a PR C1) y confirmar en la integración continua que
  el trabajo `backend` termina en verde. — Especificación `build-integrity`, requisito «Espera acotada
  ante escritura concurrente del marcador...» (fundamento de traducción por `SQLState`, sin
  concurrencia real)

---

## PR C2a-2 — corte C2a (parte 2): hash de la carga canonicalizada y tipo de resultado

Rama `change/idempotency-key-infrastructure-hasher`, base PR C2a-1.

- [ ] 3.1 **ROJO — hash de la carga canonicalizada.** No requiere Docker (comparación pura en Java,
  sin contenedor). Crear
  `.../test/java/com/confia/shared/security/RequestPayloadHasherTest.java`: dos cargas con las mismas
  claves en distinto orden producen el mismo hash; un vector de oro fija el hash exacto de una carga
  escrita a mano, para que un cambio silencioso en `CanonicalAuditRowSerializer.canonicalJson(...)` se
  vuelva rojo aquí (`design.md` decisión 8, mitigación 2); una carga sin cuerpo pasa el nodo nulo de
  JSON (forma canónica `null`); un `JsonNode` de Java nulo se rechaza con `NullPointerException`, no
  con un resultado silencioso (ADR-0019 punto 6). Falla al compilar porque `RequestPayloadHasher` no
  existe. — Especificación `build-integrity`, requisito «Rechazo de la misma clave con carga útil
  distinta, comparada por hash canonicalizado» (fundamento del hash; los escenarios de rechazo en sí
  se prueban en C2b, `IdempotentExecutorIT`)

- [ ] 3.2 **VERDE — `RequestPayloadHasher`.** No requiere Docker. Crear
  `.../main/java/com/confia/shared/security/RequestPayloadHasher.java`:
  `hex(sha256(utf8(FORMAT_VERSION) || utf8(canonicalJson(payload))))`, con
  `FORMAT_VERSION = "confia.idempotency.v1"`, delegando la canonicalización en
  `CanonicalAuditRowSerializer.canonicalJson(JsonNode)` (ya público y probado por propiedades desde el
  cambio 5) en vez de duplicar el algoritmo. Javadoc que cita esta decisión y la clase de auditoría de
  la que depende, para que quien lea cualquiera de las dos encuentre la otra. Ejecutar
  `RequestPayloadHasherTest`: verde. — `design.md`, decisión 8 completa; §6.4

- [ ] 3.3 **Crear el tipo de resultado sellado.** No requiere Docker. Crear
  `.../main/java/com/confia/shared/security/IdempotentOutcome.java` (`sealed interface` con
  `Executed`/`Replayed`, `design.md` §6.1). Sin prueba propia en este corte: ningún tipo de C2a-1 lo
  consume todavía; lo ejercitará `IdempotentExecutorIT` en C2b, que es donde se prueba que el
  resultado distingue ejecución de repetición. Se declara escrito y sin implementación pendiente. —
  `design.md`, decisión 9

- [ ] 3.4 **Medir el diff real de PR C2a-2** con
  `git diff --numstat <base-de-PR-C2a-1>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar; el rango de `design.md` §12 para toda C2a
  (590-970 repartido entre las dos mitades) no anticipa exceso en esta mitad. Si lo hubiera, detener
  la aplicación y reportar los puntos de corte candidatos medidos, verificando cada mitad por separado
  con `./mvnw -B verify`. — `design.md` §12

- [ ] 3.5 **Verificación final de PR C2a-2.** Requiere Docker (para correr la suite completa, aunque
  las pruebas propias del corte no lo requieran). En checkout limpio, ejecutar `./mvnw -B verify` en
  `apps/api`. Confirmar `RequestPayloadHasherTest` en verde completa, incluido el vector de oro.
  Empujar la rama `...-hasher` (apuntando a PR C2a-1) y confirmar en la integración continua que el
  trabajo `backend` termina en verde. — Especificación `build-integrity`, requisito «Rechazo de la
  misma clave con carga útil distinta...» (fundamento del hash)

---

## PR C2b — corte C2b: el componente sin concurrencia

Rama `change/idempotency-key-infrastructure-executor`, base PR C2a-2.

- [ ] 4.1 **Ejecutar la sonda S3 (bloqueante, `design.md` §10 y §11 paso 9).** Requiere Docker. Con
  una fila `COMPLETED` sembrada y una segunda sesión bloqueada en `SELECT ... FOR UPDATE` sobre esa
  fila mientras una primera transacción la actualiza y confirma, confirmar que la segunda, al
  desbloquearse bajo `READ COMMITTED`, relee la versión confirmada más reciente (no la instantánea que
  tenía al bloquearse). Registrar el resultado exacto en `apply-progress.md`. **Si la relectura no se
  comportara así**, adoptar el respaldo ya diseñado en `design.md` decisión 7: `UPDATE` de reutilización
  con el predicado de estado en su propia cláusula
  (`... WHERE institution_id = ? AND endpoint = ? AND idempotency_key = ? AND expires_at <= ?`) y cero
  filas actualizadas como señal de que otra transacción ganó, resuelta por el mismo camino de
  repetición; reportar la desviación. — `design.md` §10 (sonda S3) y §11, paso 9

- [ ] 4.2 **ROJO — el componente sin concurrencia.** Requiere Docker. Crear
  `.../test/java/com/confia/shared/security/IdempotentExecutorIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`, en este orden: (a) clave nueva, el caso de uso se ejecuta y el
  marcador queda `COMPLETED` con la respuesta, resultado `Executed`; (b) el caso de uso falla de forma
  determinista después de escribir el marcador: la transacción completa revierte, cero filas del
  marcador sobreviven, y una solicitud posterior con la misma clave se trata como nueva; (c) misma
  clave y mismo hash sobre una clave ya completada: se devuelve exactamente la misma respuesta
  almacenada, el caso de uso registra cero invocaciones nuevas, resultado `Replayed`; (d) misma clave,
  hash distinto: se rechaza con `IdempotencyPayloadMismatchException` sin invocar el caso de uso en
  absoluto, verificable por un contador de invocaciones en cero. Falla al compilar porque
  `IdempotentExecutor` no existe. — Especificación `build-integrity`, requisitos «Marcador y efecto de
  negocio en una única transacción atómica» (los dos escenarios), «Rechazo de la misma clave con carga
  útil distinta...» (los dos escenarios) y «Respuesta reproducible ante clave completada, sin
  reejecutar el caso de uso» (su escenario)

- [ ] 4.3 **VERDE — `IdempotentExecutor`.** Requiere Docker (ejecuta 4.2). Crear
  `.../main/java/com/confia/shared/security/IdempotentExecutor.java`: clase `final`, constructor
  explícito sobre `TransactionRunner`, `IdempotencyRecordStore`, `RequestPayloadHasher` y `Clock` (más
  la sobrecarga con `lockWait`/`retention` explícitos, por defecto 250 ms y 24 h), **sin anotación de
  Spring** y **sin abrir ninguna transacción propia** —delega en `TransactionRunner.execute(...)—,
  satisfaciendo R3 por composición (`design.md` §6.1). Implementa el flujo de `design.md` decisión 6:
  hash puro fuera de toda transacción; dentro de una sola invocación de `execute(...)`, lectura previa
  con `lockExisting`, inserción si ausente, actualización si caducada (`restartExpired`, nunca
  `DELETE`+`INSERT` — `docs/03-seguridad.md` §6.1 no concede `DELETE` a ningún rol de aplicación sobre
  tablas de negocio), rechazo por hash distinto sin invocar el caso de uso, repetición si `COMPLETED`,
  rama defensiva si `IN_PROGRESS` confirmado. Ejecutar `IdempotentExecutorIT` completa: verde. —
  `design.md`, decisiones 1, 6 y 7 (sin la rama de `SELECT ... FOR UPDATE` bajo concurrencia real
  todavía, que es C2c)

- [ ] 4.4 **Medir el diff real de PR C2b** con
  `git diff --numstat <base-de-PR-C2a-2>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar; el pronóstico de `design.md` §12 (370-580) no
  anticipa exceso. Si lo hubiera, detener la aplicación y reportar los puntos de corte candidatos
  medidos, verificando cada mitad por separado con `./mvnw -B verify`. — `design.md` §12

- [ ] 4.5 **Verificación final de PR C2b.** Requiere Docker. En checkout limpio, ejecutar
  `./mvnw -B verify` en `apps/api` con JDK 25 y Docker activo. Confirmar `IdempotentExecutorIT` en
  verde completa, las cuatro rutas (clave nueva, efecto que falla revierte, repetición, carga
  distinta). Empujar la rama `...-executor` (apuntando a PR C2a-2) y confirmar en la integración
  continua que el trabajo `backend` termina en verde. — Criterios de éxito de la propuesta sobre
  atomicidad, repetición y rechazo de carga distinta

---

## PR C2c — corte C2c: la espera acotada y sus tres salidas

Rama `change/idempotency-key-infrastructure-concurrency`, base PR C2b.

- [ ] 5.1 **Ejecutar la sonda S5 (bloqueante, `design.md` §10 y §11 paso 12; S1 y S2 ya se ejecutaron
  el 2026-09-22 y no se repiten).** Requiere Docker. Confirmar que
  `set_config('lock_timeout', ?, true)` con parámetro vinculado se aplica sin error desde
  `confia_admin_app`, y que `SHOW lock_timeout` devuelve `0` en la transacción siguiente sobre la
  **misma conexión reutilizada del pool** (no sobrevive). Registrar el resultado exacto en
  `apply-progress.md`. **Si `lock_timeout` no fuera ajustable por ese rol**, el ámbito se eleva al
  aprovisionamiento de roles, que no pertenece a este cambio, y se reporta al propietario en vez de
  conceder un privilegio nuevo por cuenta propia. — `design.md` §10 (sonda S5) y §11, paso 12

- [ ] 5.2 **ROJO — las tres salidas de la solicitud concurrente.** Requiere Docker. Crear
  `.../test/java/com/confia/shared/security/IdempotentExecutorConcurrencyIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: dos hilos, cada uno con su propio `TransactionRunner`, su propia
  conexión y su propio `IdempotentExecutor`, sincronizados con `CyclicBarrier` justo después de que la
  primera escribe su marcador y antes de que la segunda lo intente (patrón de `TransactionRunnerRetryIT`
  del cambio 5, nunca una espera por reloj). Tres escenarios, cada uno con un `lockWait` propio del
  constructor —nunca el valor por defecto—: (a) **agotamiento**: `lockWait` muy pequeño, la primera se
  mantiene abierta tras la barrera; la segunda recibe `IdempotencyConflictException(WAIT_EXHAUSTED)`
  sin haber escrito marcador propio ni invocado su caso de uso, y la primera continúa sin interferencia;
  (b) **la primera confirma dentro de la ventana**: `lockWait` muy grande, la primera confirma
  inmediatamente tras la barrera; la segunda recibe la respuesta original por el camino de repetición
  (T3, transacción nueva de solo lectura), sin invocar su caso de uso; (c) **la primera revierte dentro
  de la ventana**: `lockWait` muy grande, la primera falla tras la barrera; la segunda inserta su
  marcador con éxito y ejecuta su caso de uso. Falla porque el componente de C2b no fija ningún
  `lock_timeout` todavía. — Especificación `build-integrity`, requisito «Espera acotada ante escritura
  concurrente del marcador, con salidas distinguibles por `SQLState`» (los tres primeros escenarios)

- [ ] 5.3 **VERDE — `lock_timeout` local a la transacción y la segunda transacción de repetición.**
  Requiere Docker (ejecuta 5.2). Modificar `IdempotentExecutor` para que, como primera sentencia del
  cuerpo que pasa a `TransactionRunner.execute(...)`, ejecute
  `select set_config('lock_timeout', ?, true)` con el valor del constructor sobre la conexión JDBC
  ligada a la transacción (patrón literal de `applySecurityContext`, `design.md` decisión 4). Envolver
  la captura de `IdempotencyMarkerAlreadyExists` (T1 queda abortada) para abrir una segunda transacción
  de solo lectura (T3) que relee el marcador ya `COMPLETED` y devuelve `Replayed`; propagar
  `IdempotencyConflictException(WAIT_EXHAUSTED)` sin tocar. Ejecutar
  `IdempotentExecutorConcurrencyIT` con sus tres escenarios: verde. — `design.md`, decisión 4 completa
  y decisión 6 («Cuántas transacciones abre cada camino»)

- [ ] 5.4 **ROJO/VERDE — el no reintento en las dos salidas.** Requiere Docker. Extender
  `IdempotentExecutorConcurrencyIT` con un contador de invocaciones del cuerpo transaccional (no del
  caso de uso: lo que hay que contar es cuántas veces `TransactionRunner` reintenta la operación) y
  afirmar **exactamente una** ejecución tanto en el agotamiento de espera como en el choque por clave
  duplicada, a diferencia de los errores de serialización o interbloqueo que `TransactionRunnerRetryIT`
  ya demuestra que sí se reintentan. Falla si no existiera ya, porque hasta ahora nada distinguía «no
  reintentó» de «reintentó y volvió a fallar igual». Verde: confirmar sin cambios de producción —la
  separación ya existe en `TransactionRunner.isRetryable(...)` desde el cambio 5, y el envoltorio de la
  tarea 2.5 la sostiene—. — Especificación `build-integrity`, requisito «Espera acotada ante escritura
  concurrente del marcador...» (escenario «El reintento acotado no reintenta el agotamiento de espera
  ni el choque por clave duplicada»)

- [ ] 5.5 **Medir el tiempo de la suite `*IT.java`.** Requiere Docker. Ejecutar `./mvnw -B verify`
  completo en `apps/api` y medir de nuevo el tiempo de la fase `integration-test`, con
  `IdempotentExecutorConcurrencyIT` incluida: es el corte que más lo empeora, por las esperas reales de
  diseño. Registrar el tiempo medido en `apply-progress.md`. — `design.md` §11, paso 16; §13

- [ ] 5.6 **Medir el diff real de PR C2c** con
  `git diff --numstat <base-de-PR-C2b>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar; el pronóstico de `design.md` §12 (300-500) no
  anticipa exceso. Si lo hubiera, detener la aplicación y reportar los puntos de corte candidatos
  medidos, verificando cada mitad por separado con `./mvnw -B verify`. — `design.md` §12

- [ ] 5.7 **Verificación final de PR C2c.** Requiere Docker. En checkout limpio, ejecutar
  `./mvnw -B verify` en `apps/api` con JDK 25 y Docker activo. Confirmar `IdempotentExecutorConcurrencyIT`
  en verde completa: las tres salidas distinguibles por `SQLState`, el no reintento demostrado por
  contador, y que ninguna prueba depende de un margen de reloj. Empujar la rama `...-concurrency`
  (apuntando a PR C2b) y confirmar en la integración continua que el trabajo `backend` termina en
  verde. — Criterios de éxito de la propuesta sobre la espera acotada y el reintento acotado

---

## PR C3 — corte C3: demostración, reutilización de clave caducada, exclusiones y documentación

Rama `change/idempotency-key-infrastructure-exit-criterion`, base PR C2c.

- [ ] 6.1 **ROJO — criterio de salida 4 de F0, sobre efecto contable.** Requiere Docker. Crear
  `.../test/java/com/confia/shared/security/IdempotencyExitCriterionIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: dos hilos con la misma clave de idempotencia, sincronizados con
  `CyclicBarrier`, cada uno invocando a través del componente un caso de uso real que aplica
  `UPDATE organization_institution SET legal_name = legal_name || '+' WHERE id = ?`. Afirma que el
  sufijo final tiene exactamente un `+` (nunca dos) y que el desenlace de la solicitud perdedora es
  uno de los tres declarados —`Replayed`, `IdempotencyConflictException` o `Executed`— nunca un error
  inexplicado; sin la segunda aserción, un fallo que impidiera al perdedor llegar al efecto se leería
  como éxito del control (`design.md`, decisión 10, «La prueba afirma dos cosas, no una»). **El
  sustrato es una actualización acumulativa sobre `legal_name`, columna `NOT NULL` de
  `organization_institution`, y no una alternativa aparentemente equivalente, por dos razones que se
  dejan escritas en el Javadoc de la prueba:** no es `trade_name` porque esa columna admite nulos y en
  SQL `NULL || '+'` es `NULL` —un efecto que se traga a sí mismo en silencio y una prueba que pasaría
  sin efecto alguno—; y no es un conteo de filas insertadas en `shared_audit_log` porque el disparador
  de encadenamiento de esa tabla toma un bloqueo por institución sobre `shared_audit_chain_head`
  (`V3`, cambio 5) que **serializaría las dos transacciones antes de que ninguna llegue al marcador de
  idempotencia**, dando un verde por la razón equivocada —la misma familia de falso positivo que este
  repositorio ya pagó por aprender tres veces (`design.md`, decisión 10). Falla porque `legal_name` no
  acumula todavía nada a través de ningún caso de uso Java (`JooqInstitutionRepository` solo expone
  `findById`). — Especificación `build-integrity`, requisito «Criterio de salida 4 de F0...» (su
  escenario)

- [ ] 6.2 **VERDE — el caso de uso de la demostración.** Requiere Docker (ejecuta 6.1). Escribir, en
  el propio árbol de prueba, el caso de uso mínimo que ejecuta la actualización acumulativa a través
  del `IdempotentExecutor` de producción y del `TransactionRunner` de producción reales contra
  PostgreSQL real, sin ningún cambio en el código de producción del componente —el mecanismo ya está
  completo desde C2c—. Ejecutar `IdempotencyExitCriterionIT`: verde. Documentar en el Javadoc de la
  prueba, siguiendo `proposal.md` «El sustrato de demostración...», qué parte es demostración real
  (componente y `TransactionRunner` de producción) y qué parte es parcial (no existe capa `web` ni
  camino de escritura en Java sobre `organization_institution` fuera de esta prueba). — `design.md`,
  decisión 10 completa

- [ ] 6.3 **ROJO — reutilización de clave caducada.** Requiere Docker. Crear
  `.../test/java/com/confia/shared/security/IdempotencyExpiryIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: con un `Clock` fijo por constructor (nunca una espera real ni una
  fila escrita a mano con un `expires_at` del pasado), sembrar una clave `COMPLETED` con `expires_at`
  ya vencido según ese reloj; una solicitud nueva con esa clave ejecuta el caso de uso de nuevo, como si
  fuera nueva. Afirma tres cosas: (a) existe **una sola** fila para esa clave primaria tras la
  actualización, nunca dos; (b) `created_at` de la fila **no cambió** respecto al original —es lo que
  distingue una actualización de un borrado seguido de una inserción, que es literalmente lo que el
  requisito prohíbe—; (c) antes de que la nueva solicitud llegara, la fila vieja **seguía existiendo**
  con su `expires_at` vencido, verificable porque no hay ningún trabajo de purga desplegado en este
  cambio (cubre a la vez el requisito de reutilización y el de ausencia de purga física). Falla porque
  `restartExpired` de C2b aún no se ha probado contra una fila realmente vencida. — Especificación
  `build-integrity`, requisitos «Reutilización de una clave caducada por actualización de la fila
  existente» (su escenario) y «Ausencia de purga física de claves caducadas (brecha con destino: cambio
  9)» (su escenario)

- [ ] 6.4 **VERDE — confirmar la reutilización de clave caducada.** Requiere Docker (ejecuta 6.3).
  Confirmar `IdempotencyExpiryIT` en verde sin cambios de producción adicionales —`restartExpired` ya
  existe desde C2b (tarea 4.3) y usa `UPDATE`, nunca `DELETE`+`INSERT`—; si el reloj inyectado revela
  un caso no cubierto (por ejemplo, comparación de instantes con desfase de un microsegundo), corregir
  `IdempotentExecutor` y volver a ejecutar. — Mismo requisito y escenario que 6.3

- [ ] 6.5 **ROJO/VERDE — los tres inventarios de exclusión con destino nombrado.** No requiere Docker
  (inventario estático sobre el árbol de clases compilado y sobre el texto real de las migraciones,
  siguiendo el patrón de `AuditScopeExclusionInventoryTest`; la evidencia de la fila caducada que
  sigue existiendo ya se demostró en 6.3 con Docker). Crear
  `.../test/java/com/confia/shared/security/IdempotencyScopeExclusionInventoryTest.java` con tres
  afirmaciones, cada una probando primero que su conjunto base es real —no un conjunto vacío que
  pasaría sin comprobar nada, la disciplina de «pertenencia, no presencia» que `MultiTenantSchemaIT` ya
  aplica—: (a) el árbol de clases de producción contiene `com.confia.shared.security.IdempotentExecutor`
  y ninguna clase de producción en un paquete `..web..` depende de `com.confia.shared.security`, y
  ninguna clase de producción contiene el literal de la cabecera `Idempotency-Key`; (b) el texto
  concatenado de todas las migraciones entregadas contiene `shared_idempotency_key` y ninguna migración
  declara una purga sobre esa tabla (reafirma, desde el inventario de texto, lo que 6.3 ya demostró en
  ejecución); (c) `RolePrivilegeMatrixIT` (tarea 1.1) ya cubre que `confia_portal_app` no tiene ningún
  privilegio — este inventario solo confirma que la fila existe en la matriz, sin repetir sentencias
  reales. Falla al compilar por ausencia de la clase; en la práctica se escribe al final y debe pasar de
  inmediato, sin ninguna implementación de producción nueva que exigir. — Especificación
  `build-integrity`, requisitos «Ausencia de superficie HTTP que exija la cabecera de idempotencia
  (brecha con destino: cambio 7)» (su escenario) y «Ausencia de purga física de claves caducadas...»
  (su escenario, reforzado)

- [ ] 6.6 **Javadoc de `TransactionRunner`, `package-info`, nota fechada de ADR-0010 y `docs/09`.** No
  requiere Docker. Modificar
  `.../main/java/com/confia/shared/security/TransactionRunner.java` para que su Javadoc nombre al dueño
  real del registro del bean —el primer cambio que declare una fuente de datos de producción y retire
  la exclusión de `DataSourceAutoConfiguration`— en vez de seguir señalando al cambio 6 (`design.md`
  decisión 12, punto 1). Modificar
  `.../main/java/com/confia/shared/security/package-info.java` para que mencione al segundo habitante
  del paquete (`IdempotentExecutor`) y por qué no abre ninguna transacción propia, por composición y no
  por excepción. Añadir la nota editorial fechada en
  `docs/adr/ADR-0010-idempotencia-y-concurrencia-financiera.md`, cubriendo exactamente tres
  discrepancias sin reescribir el cuerpo de la decisión: el nombre y el prefijo de módulo de la tabla,
  la clave primaria con discriminador de institución, y la narrativa de colisión inmediata, refutada por
  la sonda S1 con fecha 2026-09-22 (`design.md` decisión 12, punto 2; D1 de la propuesta — si el
  propietario prefiriera no tocar ningún ADR, esta parte se omite sin consecuencia técnica, nada del
  resto depende de ella). Actualizar `docs/09-roadmap-y-fases.md`: criterio de salida 4 cerrado en sus
  dos mitades —un solo efecto y la misma respuesta— y entregable 6 con su mitad de cabecera obligatoria
  diferida al cambio 7, nombrada explícitamente (`design.md` decisión 12, punto 3). — `design.md`,
  decisión 12 completa

- [ ] 6.7 **Medición final del tiempo de la suite `*IT.java` del cambio completo.** Requiere Docker.
  Ejecutar `./mvnw -B verify` completo en `apps/api` con la suite `*IT.java` final de este cambio
  (todos los cortes C1 a C3) y medir el tiempo real de `integration-test`. Registrar el tiempo medido en
  `apply-progress.md` y actualizar `apps/api/README.md` si cambia alguna afirmación anterior sobre el
  presupuesto de 8 minutos. — `design.md` §11, paso 20; §13

- [ ] 6.8 **Medir el diff real de PR C3** con
  `git diff --numstat <base-de-PR-C2c>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar; el pronóstico de `design.md` §12 (400-670) no
  anticipa exceso. Si lo hubiera, detener la aplicación y reportar los puntos de corte candidatos
  medidos, verificando cada mitad por separado con `./mvnw -B verify`. — `design.md` §12

- [ ] 6.9 **Verificación final de PR C3 y del cambio completo.** Requiere Docker. En checkout limpio,
  con `JAVA_HOME` en JDK 25, `MAVEN_OPTS` con el almacén `Windows-ROOT` y Docker activo, ejecutar
  `./mvnw -B verify` en `apps/api`. Confirmar: `IdempotencyExitCriterionIT`,
  `IdempotencyScopeExclusionInventoryTest` en verde completas; el criterio de salida 4 de F0 satisfecho
  de extremo a extremo (un solo efecto contable, misma respuesta, sin reejecutar el caso de uso); las
  tres exclusiones con destino nombrado, cada una dentro de un bloque `### Requisito:` del delta ya
  archivado, con al menos un escenario que dejará de ser cierto cuando su cambio dueño cierre la
  brecha; los 19 escenarios de `specs/build-integrity/spec.md` trazados uno a uno según `design.md`
  §7.1; la suite `*IT.java` completa medida y reportada, sin comparar contra un umbral que hoy no se
  exige (W1); ninguna clase en capa `web` que exija la cabecera de idempotencia. Empujar la rama
  `...-exit-criterion` (apuntando a PR C2c) y confirmar en la integración continua que el trabajo
  `backend` termina en verde. — Todos los criterios de éxito de `proposal.md`

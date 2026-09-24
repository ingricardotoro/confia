# Diseño: infraestructura de clave de idempotencia

Cambio 6 de F0. Implementa la propuesta aprobada
(`openspec/changes/idempotency-key-infrastructure/proposal.md`) y el delta de especificación ya
escrito: `specs/build-integrity/spec.md` (11 requisitos, 19 escenarios). **La especificación es el
contrato**: donde este diseño proponga un dato que la especificación fija, prevalece la
especificación y las tablas de aquí se alinean antes de la fase de tareas. La sección 7.1 traza los
19 escenarios contra su prueba, para que ninguno quede sin camino de implementación.

Decisiones ya tomadas, que este diseño **implementa y no reabre**: atomicidad con espera acotada
(propietario, 2026-09-22); tabla `shared_idempotency_key` con clave primaria natural compuesta
`(institution_id, endpoint, idempotency_key)` y seguridad de fila forzada con el patrón `NULLIF` de
`V1` a `V3`; `TransactionRunner` **no** se registra como bean y su Javadoc se corrige (D2 de la
propuesta); componente nuevo dentro de `com.confia.shared.security` que delega en
`TransactionRunner.execute(...)`, con el acceso a la tabla por un puerto y su adaptador jOOQ en
`shared/infrastructure`; cabecera obligatoria diferida al cambio 7; purga física diferida al cambio
9; presupuesto de 800 líneas de cambio efectivo por pull request con entrega **`auto-chain`**.

> **Nota sobre la política de la sesión.** El bloque de preflight de esta sesión proyecta
> `single-pr` y un presupuesto de 400 líneas. Ambos provienen del registro de `sdd-init` del
> 2026-09-15, caducado. Los valores vigentes son **`auto-chain`** y **800 líneas**
> (`docs/15-flujo-de-trabajo-git.md` §3, `CLAUDE.md`, y la resolución del propietario recogida en
> `exploration.md` y `proposal.md` el 2026-09-22). Este diseño planifica contra los valores
> vigentes y deja constancia para que el orquestador alinee su registro, exactamente como quedó
> anotado en los cambios 4 y 5.

---

## 1. Enfoque técnico

De la tabla hacia afuera, en cuatro cortes encadenados. Cada corte deja `./mvnw verify` en verde y
sigue TDD estricto (`openspec/config.yaml`, `strict_tdd: true`).

| Corte | Contenido |
|---|---|
| **C1** | Migración `V4`, política de fila, `REVOKE`/`GRANT`, filas de `RolePrivilegeMatrixIT`, prueba de privilegios con sentencias reales, verificación de que `MultiTenantSchemaIT` pasa sin excepción |
| **C2a** | Puerto `IdempotencyRecordStore`, su registro, el adaptador jOOQ con la traducción de `SQLState`, el hash canonicalizado y los tipos de resultado y de error, con sus pruebas |
| **C2b** | `IdempotentExecutor`: clave nueva, repetición con respuesta almacenada, rechazo por carga distinta, atomicidad marcador–efecto. Sin concurrencia todavía |
| **C2c** | `lock_timeout` con sus tres salidas, la segunda transacción de repetición, y la prueba de que el reintento acotado no reintenta ninguna de las dos |
| **C3** | Criterio de salida 4 sobre efecto contable, reutilización de clave caducada, los tres inventarios de exclusión, nota de ADR-0010, Javadoc de `TransactionRunner` y `docs/09` |

**Sin dependencia mecánica de orden, verificado.** La puerta de prefijo de módulo
(`MultiTenantSchemaIT.everyBusinessTableNameCarriesItsOwnerModulesPrefixExceptTheClosedCatalogue`,
líneas 116-136) deriva el conjunto de módulos de los paquetes de producción reales. Hoy existen
`com.confia.shared.security`, `com.confia.shared.audit` y `com.confia.shared.infrastructure` con
clases de producción, así que el módulo `shared` **ya existe** y la migración puede llegar primero.
Es la diferencia con el cambio 5, que tuvo que crear el paquete antes que la tabla.

**C2a antes de C2b es una dependencia de contrato, no de gusto.** El componente no puede escribirse
en rojo sin el puerto que consume, y la traducción de `SQLState` vive en el adaptador (decisión 5),
no en el componente. C2c se separa de C2b porque la concurrencia con confirmación real es la parte
cara en tiempo de suite y la que depende de las tres sondas bloqueantes.

---

## 2. Evidencia obtenida en esta fase y límites

Esta fase **no dispuso de herramienta de ejecución de procesos** (ni Maven, ni Docker, ni
PostgreSQL, ni intérprete de comandos). Todo lo de abajo es lectura de archivos reales del árbol. Lo
que exige ejecutar algo está marcado como **no verificado** y aparece como sonda numerada en la
sección 10; ninguna decisión de este documento afirma como comprobado un comportamiento que no se
comprobó.

| Pregunta | Resultado | Evidencia |
|---|---|---|
| ¿`shared_idempotency_key` rompe alguna puerta genérica de `MultiTenantSchemaIT`? | **Verificado por lectura: no.** Lleva `institution_id NOT NULL`; recibirá `ENABLE`/`FORCE ROW LEVEL SECURITY`; su único índice único es la clave primaria y **contiene** `institution_id` (la puerta exige `contains`, no posición); el prefijo antes del primer guion bajo es `shared`, módulo que existe. Pasa las cuatro sin lista de exclusión | `MultiTenantSchemaIT.java:51-96,116-136,148-156` |
| ¿La clave primaria compuesta necesita entrar en la lista de excepciones de la tabla raíz? | **Verificado: no.** La excepción solo cambia la columna exigida (`id` en lugar de `institution_id`) para `organization_institution`; cualquier otra tabla se evalúa contra `institution_id`, que la clave primaria contiene | `MultiTenantSchemaIT.java:84-96` |
| ¿Una tabla nueva sin disparador `BEFORE TRUNCATE` rompe `CommittingBaseContractIT`? | **Verificado: no.** Ese contrato usa `doesNotContain(...)` sobre las dos tablas de auditoría y `contains("organization_institution")`, nunca igualdad exacta del conjunto. La tabla nueva **sí** se truncará entre pruebas, que es lo deseable | `CommittingBaseContractIT.java:20-32`; `CommittingPostgresIntegrationTest.java:100-119` |
| ¿Dónde puede vivir el adaptador jOOQ sin romper R2? | **Verificado.** `moduleOf("com.confia.shared.infrastructure")` = `shared`, prefijo esperado `Shared`, y el tipo generado será `SharedIdempotencyKey`. Un paquete `com.confia.shared.idempotency.infrastructure` daría módulo `idempotency` y exigiría prefijo `Idempotency`: **rompería R2** | `TableOwnershipByModuleTest.java:52-97`; `JooqAuditLogReader.java:1-31` |
| ¿Puede el componente nuevo tocar jOOQ? | **Verificado: no.** R1 prohíbe `org.jooq..` y `confia.generated..` fuera de `..infrastructure..`, y `com.confia.shared.security` no lleva segmento de capa. Por eso el acceso sale por puerto | `JooqConfinedToInfrastructureTest.java` |
| ¿Puede el adaptador fijar `lock_timeout` con jOOQ? | **Verificado: no sin coste.** R4 rechaza `DSLContext.execute/fetch/resultQuery(String, ...)` y `DSL.field/table/condition/sql(String, ...)` fuera de una lista aprobada **hoy vacía e inmutable**. Por eso lo fija el componente sobre la conexión JDBC ligada, que es el precedente literal de `TransactionRunner` | `NoUnapprovedPlainSqlTest.java:35-45,60-90`; `TransactionRunner.java:48-53,117-133` |
| ¿Una dependencia `shared.security` → `shared.audit` crea un ciclo que rompa `NoCyclesTest`? | **Verificado: no.** La regla corta en `com.confia.(*)..`, es decir un nivel bajo `com.confia`: `shared.security`, `shared.audit` y `shared.infrastructure` caen **en la misma rebanada** `shared`. La dependencia inversa ya existe hoy (`DefaultAuditChainVerifier` importa `TransactionRunner`) y la regla está en verde | `NoCyclesTest.java:19-23`; `DefaultAuditChainVerifier.java:4-5` |
| ¿Spring Modulith exige una interfaz nombrada para `com.confia.shared.security`? | **Verificado: no en este cambio.** Los módulos son los subpaquetes directos de `com.confia`; `security`, `audit` e `infrastructure` son internos del **mismo** módulo `shared`, y el acceso entre internos del mismo módulo no es violación. Cierra la pregunta abierta 2 del cambio 5 | `SpringModulithVerificationTest.java:33-39`; `package-info.java` de `shared.security` |
| ¿Qué excepción llega hoy ante un error de PostgreSQL a través de jOOQ? | **Verificado por lectura: `org.jooq.exception.DataAccessException` envolviendo la `SQLException` cruda.** Spring Boot 4.1 **no trae autoconfiguración de jOOQ** —cero clases bajo `org/springframework/boot/autoconfigure/jooq/`— así que no hay `JooqExceptionTranslator` y no aparece la jerarquía `org.springframework.dao`. Determinante: si apareciera, `55P03` se traduciría a `CannotAcquireLockException`, que **es** una `ConcurrencyFailureException`, y `TransactionRunner.isRetryable(...)` la reintentaría. Decisión 5 y sonda S4 | `IntegrationTestApplication.java:24-46`; `TransactionRunner.java:140-153` |
| ¿`TransactionRunner` reintenta el choque por clave duplicada o el agotamiento de espera? | **Verificado por lectura: no.** `isRetryable` exige `ConcurrencyFailureException` en el nivel superior, o `SQLState` `40001`/`40P01` en la cadena de causas. Ni `23505` ni `55P03` están en esa lista. La separación ya existe; este diseño la **prueba**, no la da por buena | `TransactionRunner.java:140-153` |
| ¿Puede `confia_admin_app` hacer `SELECT ... FOR UPDATE` sobre esta tabla? | **Verificado por documentación de privilegios.** `docs/03` §6.1 le concede `UPDATE` en tablas no financieras, y la tabla no lleva importe ni moneda. PostgreSQL exige `SELECT` más `UPDATE` para el bloqueo de fila; los dos se conceden | `docs/03-seguridad.md:627`; decisión 3 |
| ¿ADR-0010 autoriza acotar la espera de bloqueo? | **Verificado: sí, ya lo ordena.** Control dos, regla obligatoria: «El tiempo de espera de bloqueo se limita para que una transacción colgada no bloquee la ventanilla de forma indefinida». No se inventa ningún control nuevo | `ADR-0010:189-190` |
| ¿Existe ya vocabulario de observabilidad para el agotamiento de espera? | **Verificado, con una tensión que hay que nombrar.** `confia_db_transaction_retries_total` declara la etiqueta `reason=lock_timeout`, que sugiere que un agotamiento **se reintenta**. Ese contador pertenece al bloqueo de cuenta del control dos (F3), no al marcador de idempotencia, cuyo no reintento exige la especificación. Este cambio no emite ninguna métrica: no hay cableado de observabilidad en F0 | `docs/07:433`; `specs/build-integrity/spec.md`, escenario del reintento |
| ¿Qué presupuesto de latencia existe para una escritura financiera? | **Verificado.** p95 menor a 1000 ms en rutas financieras de escritura, con alerta `WriteLatencyHigh` por encima de 1 s sostenido. Es el marco para elegir el valor del `lock_timeout` | `docs/07:613,679` |
| ¿El texto de la migración nueva puede romper una prueba ya en verde? | **Verificado: sí, y es una trampa real.** `AuditScopeExclusionInventoryTest.scheduledTasksDoesNotAppearInTheDeliveredSchemasMigrations` concatena **todos** los `.sql` de `db/migration` y afirma que el texto no contiene el nombre de la tabla del programador de tareas. Un comentario de `V4` que lo mencione al explicar la purga diferida pone esa prueba en **rojo** | `AuditScopeExclusionInventoryTest.java:80-92,180-200` |
| ¿Alcanza alguna puerta de cobertura o de mutación a las clases nuevas? | **Verificado.** JaCoCo: regla `BUNDLE` al 80 % de línea y rama; las reglas `PACKAGE` al 95 % cubren `com.confia.*.domain` y `com.confia.shared.audit`, ninguno de los paquetes nuevos. PIT: `targetClasses` es `com.confia.*.domain.*`, así que **ninguna clase nueva entra en la puerta de mutación**. Decisión 13 | `apps/api/app/pom.xml:185-239,370-374` |
| ¿`lock_timeout` acota la espera de un `INSERT` que colisiona con una transacción abierta? | **No verificado.** La expectativa es que sí, porque esa espera se toma sobre el identificador de transacción a través del gestor de bloqueos, y el agotamiento se señala con `SQLState 55P03`. **Es el supuesto central de todo el mecanismo.** Sonda S1 | — |
| ¿El choque por clave duplicada llega como `23505`? | **No verificado por código.** La sonda del 2026-09-22 observó el fallo por clave duplicada pero no registró el código. Sonda S2 | `exploration.md` §3 |
| ¿Un `SELECT ... FOR UPDATE` que se desbloquea relee la versión confirmada más reciente? | **No verificado.** Es lo que `READ COMMITTED` documenta, y de ello depende la corrección de la reutilización de clave caducada. Sonda S3 | — |
| ¿`set_config('lock_timeout', ?, true)` es admisible para `confia_admin_app` y no sobrevive a la transacción? | **No verificado.** `lock_timeout` es un parámetro de usuario, así que la expectativa es que sí. Sonda S6 | — |

**Consecuencia de método.** Donde una decisión depende de un comportamiento no comprobado, este
diseño nombra la sonda y **deja una alternativa ya diseñada con su criterio de conmutación**, en vez
de afirmar como verificado lo que no se ejecutó.

---

## 3. Decisiones de arquitectura

### Decisión 1 — Dónde viven las clases nuevas

**Elección.** Dos paquetes, ambos ya existentes, ninguno nuevo:

```
com.confia.shared.security/        IdempotentExecutor, IdempotencyKey, IdempotentResponse,
                                   IdempotentOutcome, IdempotencyRecord, IdempotencyRecordStore (puerto),
                                   RequestPayloadHasher, IdempotencyConflictException,
                                   IdempotencyPayloadMismatchException
com.confia.shared.infrastructure/  JooqIdempotencyRecordStore (única clase nueva que toca jOOQ)
```

**Por qué encaja con cada regla entregada**, verificado por lectura en la sección 2:

| Regla | Efecto sobre estas clases |
|---|---|
| R3 `TransactionsOnlyInSharedSecurityTest` | El componente vive en `com.confia.shared.security`, el paquete que la condición exime. Además **no abre ninguna transacción**: delega en `TransactionRunner`. Satisface R3 por composición y por ubicación, no por excepción |
| R1 `JooqConfinedToInfrastructureTest` | Solo `JooqIdempotencyRecordStore` toca `org.jooq..` y `confia.generated..` |
| R2 `TableOwnershipByModuleTest` | `moduleOf("com.confia.shared.infrastructure")` = `shared`, prefijo esperado `Shared`, tipo generado `SharedIdempotencyKey`: encaja |
| R4 `NoUnapprovedPlainSqlTest` | Ninguna clase nueva llama a un punto de entrada de SQL plano de jOOQ. **La lista aprobada sigue vacía** |
| `LayeredArchitectureTest` | `shared.security` no lleva segmento de capa; `shared.infrastructure` sí es capa `Infrastructure` y solo depende de tipos sin capa, igual que `JooqAuditLogReader` hoy |
| `NoCyclesTest` | Las tres piezas caen en la rebanada `shared`; no hay ciclo entre rebanadas |
| `NoTechnicalLayerPackageNamesTest` | `security` e `infrastructure` no están en la lista prohibida |
| Spring Modulith | Internos del mismo módulo `shared`; sin interfaz nombrada (sección 2) |
| JaCoCo / PIT | Solo la regla `BUNDLE` del 80 %; ninguna clase nueva entra en la puerta de mutación. Decisión 13 |

**Alternativas descartadas.**

- **Un módulo `com.confia.idempotency.*` propio.** Obligaría a renombrar la tabla a
  `idempotency_*`, contradice la ubicación que `docs/01` da a la idempotencia —dentro de
  `shared/security`— y la exploración ya descartó la capacidad propia con evidencia. Descartada.
- **`com.confia.shared.idempotency.infrastructure` para el adaptador.** `moduleOf` devolvería
  `idempotency` y R2 exigiría un tipo generado con prefijo `Idempotency` cuando el real será
  `SharedIdempotencyKey`: **rompe una puerta entregada y en verde**. Descartada por la misma razón
  literal por la que el cambio 5 descartó `com.confia.shared.audit.infrastructure`.
- **El puerto en `com.confia.shared.audit`, junto a `AuditLogReader`.** El precedente no es el
  paquete, es la **forma**: puerto junto a su consumidor, adaptador en `infrastructure`. El
  consumidor aquí es el componente de `shared.security`, así que el puerto va ahí. Descartada.

### Decisión 2 — DDL de `shared_idempotency_key`

```sql
CREATE TABLE shared_idempotency_key (
    institution_id  UUID        NOT NULL,
    endpoint        TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    response_status INTEGER,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT shared_idempotency_key_pk
        PRIMARY KEY (institution_id, endpoint, idempotency_key),
    CONSTRAINT shared_idempotency_key_status_chk
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT shared_idempotency_key_request_hash_chk
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT shared_idempotency_key_endpoint_len_chk
        CHECK (char_length(endpoint) BETWEEN 1 AND 200),
    CONSTRAINT shared_idempotency_key_key_len_chk
        CHECK (char_length(idempotency_key) BETWEEN 1 AND 200),
    CONSTRAINT shared_idempotency_key_completed_chk
        CHECK (status <> 'COMPLETED'
               OR (response_status IS NOT NULL AND response_body IS NOT NULL
                   AND completed_at IS NOT NULL))
);

ALTER TABLE shared_idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE shared_idempotency_key FORCE  ROW LEVEL SECURITY;

CREATE POLICY shared_idempotency_key_institution_isolation ON shared_idempotency_key
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
```

Puntos que son decisiones, no detalles:

1. **La clave primaria natural compuesta es el control.** No hay identificador sustituto: `pgcrypto`
   no está disponible (sonda S6 del cambio 5) y la clave natural satisface por construcción la
   puerta de índices únicos con discriminador de institución. `institution_id` va **primero** por la
   misma razón por la que los índices de `shared_audit_log` lo llevan delante: la política de fila
   filtra siempre por institución.
2. **`NULLIF(..., '')` y `WITH CHECK` explícito**, idénticos a `V1`, `V2` y `V3`. `current_setting(..., true)`
   devuelve `NULL` ante ajuste ausente y la comparación deniega; una cadena **vacía** llegaría a
   `''::uuid` y produciría un error de conversión en vez de una denegación.
3. **`request_hash` es `TEXT` hexadecimal, no `BYTEA`.** ADR-0010 lo declara `TEXT`, así que se evita
   una cuarta discrepancia que anotar; la comparación en Java es `String.equals`, sin la trampa de
   comparar arreglos con `==`; y la expresión regular deja el formato visible en el catálogo, igual
   que el guardián técnico de `rtn` en `V1`. La restricción de longitud de `endpoint` y de
   `idempotency_key` existe porque la clave primaria es un índice btree con límite de tamaño de
   entrada: sin ella, una clave desmedida falla con un error de tamaño de índice en vez de con una
   violación de restricción legible. Es un guardián técnico, no un contrato fiscal ni de API; el
   cambio 7 validará además la cabecera en el borde con Jakarta Bean Validation.
4. **El dominio de `status` se copia de ADR-0010, con `FAILED` inalcanzable en este cambio y dicho en
   voz alta.** Con marcador y efecto en la misma transacción, un efecto que falla **revierte la fila
   entera**: nunca hay un `FAILED` que escribir, y `IN_PROGRESS` solo es observable **dentro** de la
   transacción que lo escribe. El único valor que este cambio confirma es `COMPLETED`. No se estrecha
   el `CHECK` para no abrir una cuarta discrepancia con el ADR ni obligar a una migración el día que
   exista un escritor no atómico. La rama defensiva que trata un `IN_PROGRESS` confirmado como
   conflicto (decisión 6) es lo que impide que una intervención manual o un futuro escritor no
   atómico se lean como respuesta repetible.
5. **Sin índice sobre `expires_at`.** Lo necesitará la purga del cambio 9, que es quien lo justifica;
   entregarlo aquí sería superficie sin consumidor (ADR-0019). Queda nombrado como trabajo de ese
   cambio. No es un índice único, así que no interactúa con ninguna puerta.
6. **`created_at` con `clock_timestamp()`**, como `shared_audit_log`, y no `now()`: `now()` es el
   instante de inicio de transacción y dos filas de la misma transacción compartirían marca.
7. **Trampa de texto, verificada.** Los comentarios de `V4` que expliquen la purga diferida **no
   pueden nombrar la tabla del programador de tareas**: `AuditScopeExclusionInventoryTest` concatena
   el texto de todas las migraciones y afirma que ese nombre no aparece. Se escribe «el cambio 9» y
   se cita el requisito, nunca el identificador de la tabla.

### Decisión 3 — Privilegios, exactamente los de la especificación

```sql
REVOKE ALL ON shared_idempotency_key FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON shared_idempotency_key TO confia_admin_app;
GRANT SELECT                  ON shared_idempotency_key TO confia_readonly;
-- confia_portal_app: ningún GRANT (docs/03 §6.1; requisito de brecha con destino F3/F4)
-- confia_owner: propietario del esquema, sin BYPASSRLS
-- confia_backup: lee por pg_read_all_data, que NO omite la seguridad a nivel de fila
```

El `REVOKE` va **antes** de cualquier `GRANT`, como exige la especificación.

**`UPDATE` para `confia_admin_app` no es una excepción, es la regla aplicada.** `docs/03` §6.1 le
concede «`UPDATE` solo en tablas no financieras». Esta tabla no registra importe ni moneda: es no
financiera. El `UPDATE` es imprescindible dos veces —completar el marcador y reutilizar una clave
caducada— y `DELETE` no se concede a ningún rol de aplicación, que es exactamente por lo que la
caducidad se modela como actualización (decisión 7).

**Lo que la matriz debe afirmar y es contraintuitivo.** `has_table_privilege('confia_owner', ...)`
devolverá **verdadero** para los cuatro privilegios: el propietario del esquema no está sujeto a
`GRANT`/`REVOKE`. La fila de `confia_owner` afirma ese verdadero, igual que ya lo hace para las dos
tablas de auditoría. Aquí, a diferencia de la bitácora, **no hay un disparador que lo rechace**, y
tampoco se inventa uno: la tabla no es de solo inserción, porque el propio control necesita
actualizarla.

**Sobre `confia_readonly` y los datos que la tabla guarda.** `response_body` puede contener la
respuesta de una escritura financiera, y la especificación concede `SELECT` a `confia_readonly`
sujeto a la misma política de fila. Este diseño no altera esa concesión ni inventa un control nuevo,
pero deja escrito el deber que hereda el **cambio 7**, que es quien decide qué entra en
`response_body`: `docs/08` prohíbe almacenar datos sensibles en claro, y esa obligación no se cumple
sola por el hecho de que la columna exista.

### Decisión 4 — P1: ámbito, valor y propiedad del `lock_timeout`

**Elección: ámbito de transacción, fijado por el componente nuevo como primera sentencia de su
propio cuerpo, con `set_config('lock_timeout', ?, true)` y parámetro vinculado sobre la conexión
JDBC ligada a la transacción.**

```java
// dentro del cuerpo que el componente pasa a TransactionRunner.execute(...)
select set_config('lock_timeout', ?, true)
```

Es el patrón literal de `TransactionRunner.applySecurityContext(...)`: `DataSourceUtils.getConnection(dataSource)`,
`PreparedStatement`, parámetro vinculado, sin jOOQ y sin interpolación. El tercer argumento `true`
lo hace **local a la transacción**, de modo que se revierte al terminar aunque la transacción falle
y **jamás contamina la conexión que el pool entregue después**, que es la propiedad que `docs/03`
§6.2 regla 1 exige del contexto de sesión y que aquí vale por la misma razón.

**Alternativas evaluadas.**

| Ámbito | Cómo se fijaría | Veredicto |
|---|---|---|
| **Sesión o rol** (`SET lock_timeout`, `ALTER ROLE confia_admin_app SET lock_timeout`) | Una vez por conexión o en el aprovisionamiento de roles | **Descartada, y es el riesgo principal de la propuesta.** Alcanzaría a **toda** transacción del proceso: las `SERIALIZABLE` de ADR-0010 y, sobre todo, la espera legítima sobre `shared_audit_chain_head` que el disparador de encadenamiento toma por institución (`V3`, decisión 4 del cambio 5). Esa espera es **correcta** y hoy no tiene límite; abortarla produciría `55P03`, que `TransactionRunner.isRetryable(...)` **no** reintenta, de modo que una escritura de auditoría empezaría a fallar bajo contención por un control que no le concierne. Además, `ALTER ROLE` vive en el aprovisionamiento de roles, fuera de las migraciones que este cambio posee |
| **Sentencia** | PostgreSQL no tiene `lock_timeout` por sentencia; habría que fijarlo antes y restaurarlo después | **Descartada.** Es estrictamente más SQL para el mismo efecto dentro de una transacción que solo este componente abre. Y restaurarlo a `0` tras escribir el marcador dejaría **sin acotar** la espera del caso de uso, que es justo lo que ADR-0010 control dos prohíbe |
| **Transacción** (elegida) | `set_config(..., true)` dentro del cuerpo | **Elegida.** Alcanza exactamente a las transacciones que este componente abre, y a ninguna otra. Se revierte sola |

**Consecuencia asumida, escrita para que nadie la descubra tarde:** el límite cubre **todo** el
cuerpo, incluido el caso de uso. Una escritura financiera de F3 que tome el bloqueo pesimista sobre
la cuenta del estudiante verá su espera acotada por este mismo valor. Eso **es** lo que ADR-0010
control dos ordena («el tiempo de espera de bloqueo se limita»), así que es alineación y no efecto
colateral; y el valor es un parámetro de constructor, de modo que F3 puede elevarlo por caso de uso
sin tocar este componente.

**Valor: 250 ms por defecto, parametrizable por constructor.**

- El presupuesto vigente para una escritura financiera es **p95 menor a 1000 ms**, con alerta por
  encima de 1 s sostenido (`docs/07`:679, 613). La espera es coste puro sobre una petición que ya
  gastó parte de ese presupuesto, así que no puede acercarse al segundo.
- Demasiado corta —decenas de milisegundos— convierte casi toda carrera real en un conflicto que el
  cajero tiene que reintentar a mano, cuando el desenlace **mejor** ya está disponible: si la primera
  confirma dentro de la ventana, la segunda choca por clave duplicada y **repite la respuesta
  original**, que es invisible y correcto. Una ventana razonable compra desenlaces buenos.
- Demasiado larga retiene una conexión por cada reintento del cliente: así se agota un pool.
- 250 ms cabe con margen dentro del presupuesto incluso consumido entero, y supera con holgura lo
  que tarda una escritura confirmada normal. Los 5 847 ms y 4 658 ms que midió la sonda del
  2026-09-22 no contradicen esto: allí la primera transacción se mantuvo abierta a propósito.

**Las pruebas no heredan el valor por defecto, y esa es la disciplina antiescamosa.** El escenario de
espera agotada construye el componente con una espera **muy pequeña** y mantiene la primera
transacción abierta con una `CyclicBarrier`; los escenarios de «la primera confirma» y «la primera
revierte» lo construyen con una espera **muy grande** y liberan la barrera de inmediato. Ninguna
prueba depende de un margen de reloj, que es el patrón que `TransactionRunnerRetryIT` ya estableció.

**Tensión documental que se nombra y no se resuelve aquí.** `docs/07`:433 declara la etiqueta
`reason=lock_timeout` en `confia_db_transaction_retries_total`, lo que sugiere que un agotamiento se
reintenta. Pertenece al bloqueo de cuenta del control dos, cuyo dueño es F3; la especificación de
este cambio exige lo contrario para el marcador y prevalece. Este cambio no emite ninguna métrica:
no hay cableado de observabilidad en F0.

### Decisión 5 — P2: los tres `SQLState`, y por qué el adaptador los envuelve

**Elección.** El adaptador jOOQ —y solo él— inspecciona el `SQLState` recorriendo la cadena de
causas, **nunca el texto del mensaje**, y lanza un tipo propio del proyecto. El componente jamás ve
una `SQLException`.

| Salida | `SQLState` esperado | Qué lanza el adaptador | Qué significa |
|---|---|---|---|
| Agotamiento de la espera | `55P03` (`lock_not_available`) | `IdempotencyConflictException(WAIT_EXHAUSTED)` | Operación en curso; el cambio 7 lo traducirá a `409` |
| Choque por clave duplicada | `23505` (`unique_violation`) | `IdempotencyMarkerAlreadyExists` (interna del paquete) | La clave ya está completada; corresponde repetir la respuesta original |
| Inserción exitosa | — | nada | Clave libre; se ejecuta de verdad |

**Ambos códigos están marcados como sonda** (S1 y S2). Lo que este diseño fija y no depende de la
sonda es que la discriminación se haga **por código**, como `TransactionRunner.isRetryable(...)` ya
hace, y jamás por texto: un texto de mensaje cambia entre versiones del motor y entre configuraciones
regionales, y el fallo sería silencioso.

**Por qué envolver, y no dejar pasar la excepción original.** Verificado en la sección 2: Spring Boot
4.1 no trae autoconfiguración de jOOQ, así que hoy llega `org.jooq.exception.DataAccessException`
con la `SQLException` cruda dentro y `TransactionRunner` **no** la reintenta. Pero si alguien
añadiera un traductor de excepciones —un `ExecuteListener`, o la autoconfiguración de vuelta—,
`55P03` se convertiría en `CannotAcquireLockException`, que extiende `ConcurrencyFailureException`, y
`isRetryable(...)` la reconocería **en su primera comprobación**: el componente transaccional
reintentaría una espera agotada, violando el requisito que la especificación escribe. Envolver en un
tipo propio neutraliza esa ruta por construcción, porque `isRetryable` comprueba
`ConcurrencyFailureException` únicamente en el nivel superior. La causa original se conserva para el
diagnóstico: `55P03` y `23505` no están en la lista de códigos reintentables, así que conservarla no
reabre el problema.

**La prueba que lo sostiene no es la lectura del código.** Un contador de invocaciones dentro del
caso de uso afirma **exactamente una** ejecución en las dos salidas, que es lo único que distingue
«no reintentó» de «reintentó y volvió a fallar igual».

### Decisión 6 — P4: la forma del componente, y por qué el peor camino abre dos transacciones

El choque por clave duplicada **aborta la transacción**: en PostgreSQL cualquier error deja la
transacción en estado abortado y ninguna sentencia posterior es válida hasta el fin. Leer la
respuesta original para repetirla exige, por tanto, una transacción nueva. Eso no es un detalle de
implementación: determina la forma del componente.

**Elección: una lectura previa dentro de la misma transacción resuelve el caso común, y solo el
choque por clave duplicada abre una segunda transacción, de solo lectura.**

```text
execute(context, key, payload, useCase):
  requestHash = hasher.hash(payload)                     # puro, fuera de toda transacción

  try:
    return runner.execute(context, () -> {               # T1
        bindLockTimeout()                                # set_config('lock_timeout', ?, true)
        existing = store.lockExisting(key)               # SELECT ... FOR UPDATE   (espera acotada)

        if existing is absent:
            store.insertInProgress(key, requestHash, expiresAt)   # (espera acotada)
            response = useCase.get()
            store.complete(key, response)                # UPDATE de la fila ya bloqueada
            return Executed(response)

        if existing.isExpired(clock.instant()):
            store.restartExpired(key, requestHash, expiresAt)     # UPDATE, nunca DELETE+INSERT
            response = useCase.get()
            store.complete(key, response)
            return Executed(response)

        if existing.requestHash != requestHash:
            throw IdempotencyPayloadMismatchException    # el caso de uso NO se invoca
        if existing.status == COMPLETED:
            return Replayed(existing.response())
        throw IdempotencyConflictException(MARKER_IN_PROGRESS)    # rama defensiva
    })

  catch IdempotencyMarkerAlreadyExists:                  # T1 quedó abortada y revertida
    return replayInANewTransaction(context, key, requestHash)     # T2, solo lectura

  # IdempotencyConflictException(WAIT_EXHAUSTED) se propaga sin tocar
```

**Cuántas transacciones abre cada camino:**

| Camino | Transacciones | Frecuencia esperada |
|---|---|---|
| Clave nueva | **1** | el caso normal |
| Reintento secuencial del cajero, clave completada | **1** | el caso frecuente de repetición |
| Carga distinta | **1** | error del cliente |
| Clave caducada reutilizada | **1** | tras veinticuatro horas |
| Concurrente, la primera confirma dentro de la ventana | **2** | carrera real, rara |
| Concurrente, la espera se agota | **1** | carrera con primera lenta |

**La lectura previa no sustituye al control.** El control sigue viviendo en la clave primaria; la
lectura previa es una optimización del caso común que, además, es lo que permite distinguir la carga
distinta **sin ejecutar nada**, que es un requisito, no una mejora.

**Alternativas descartadas.**

- **`INSERT ... ON CONFLICT DO NOTHING` y comprobar el número de filas.** Técnicamente superior en un
  aspecto: espera igual, pero al desbloquearse devuelve cero filas **sin abortar la transacción**, de
  modo que el peor camino abriría una sola transacción y el problema de P4 se disolvería en vez de
  resolverse. **Se descarta porque borra el `SQLState` que la especificación exige**: el escenario
  «La primera transacción confirma dentro de la ventana de espera» pide un error de clave duplicada
  «distinguible por `SQLState` de la salida de espera agotada», y con `ON CONFLICT` no hay error ni
  código que distinguir. Además vuelve silenciosa una colisión que ADR-0010 llama «el control real».
  Si el propietario prefiriera el camino barato, **es un cambio de especificación, no una decisión de
  diseño**, y este documento lo deja escrito para que la conversación exista.
- **Un `SAVEPOINT` alrededor del `INSERT`.** Mantendría viva T1 tras el choque y evitaría T2. Se
  descarta porque introduce semántica de subtransacción que nada en el árbol usa hoy, añade consumo
  de identificadores de transacción por petición, y acerca peligrosamente el componente a «abrir algo
  transaccional», que es justo lo que R3 confina y lo que esta decisión quiere satisfacer por
  composición y sin discusión.
- **Confirmar el marcador primero, en su propia transacción corta.** Ya descartada por el propietario
  el 2026-09-22: rompe la atomicidad y deja marcadores huérfanos sin mecanismo de reconciliación
  hasta el cambio 9. No se reabre.

### Decisión 7 — `SELECT ... FOR UPDATE` y la reutilización de la clave caducada

**Elección: la lectura previa es `SELECT ... FOR UPDATE`, y la reutilización de una clave caducada es
un `UPDATE` de la fila existente.**

`docs/03` §6.1 no concede `DELETE` sobre tablas de negocio a **ningún** rol de aplicación, así que
«tratar la clave caducada como nueva» solo puede ser una actualización. Y como la fila caducada sigue
ocupando su clave primaria hasta que exista la purga del cambio 9, una inserción chocaría contra
ella.

**Por qué el bloqueo de fila y no una lectura simple.** Dos solicitudes concurrentes sobre una clave
**caducada** no chocan contra la clave primaria: la fila ya existe, así que ambas la leerían, ambas
la verían vencida y ambas intentarían actualizarla. Con una lectura simple, la segunda se bloquearía
en el `UPDATE` y, bajo `READ COMMITTED`, al desbloquearse **reevaluaría su predicado contra la
versión nueva**; si el predicado no distingue el estado que leyó, actualizaría igual y **ejecutaría
el caso de uso por segunda vez**. Es un doble efecto de la misma familia que `MAX(...) + 1`, y pasaría
desapercibido porque la ruta feliz de la clave nueva sí está protegida.

Con `SELECT ... FOR UPDATE`, la segunda solicitud se bloquea **antes de decidir nada**, con su espera
acotada por el mismo `lock_timeout`, y al desbloquearse relee la versión confirmada más reciente: ve
`COMPLETED` con `expires_at` renovado y repite la respuesta. Un solo mecanismo para los dos casos.

**Alternativa de respaldo, ya diseñada** (sonda S3): si la relectura no se comportara como se espera,
el `UPDATE` de reutilización lleva el predicado de estado en su propia cláusula
—`... WHERE institution_id = ? AND endpoint = ? AND idempotency_key = ? AND expires_at <= ?`— y **cero
filas actualizadas** significa «otra transacción ganó», que se resuelve por el mismo camino de
repetición. Es más código y una rama más, por eso es el respaldo y no la elección.

**La caducidad la decide el reloj de Java, no el del motor.** El componente recibe un `Clock` por
constructor y compara `expires_at` contra `clock.instant()`; `expires_at` se calcula como
`clock.instant() + retention`, con `retention` por defecto de **veinticuatro horas** (ADR-0010). Un
solo reloj decide, así que no hay desacuerdo posible entre el instante que escribe la fila y el que
la juzga; y la prueba de caducidad adelanta el reloj en vez de esperar un día o de escribir a mano
una fila inconsistente. **Consecuencia que hereda el cambio 9:** su purga física, que se ejecutará en
SQL, usará el reloj del motor; si algún día los dos relojes divergen de forma apreciable, la purga y
la semántica lógica pueden discrepar por esa diferencia. Queda escrito aquí, no se resuelve aquí.

### Decisión 8 — P3: el hash de la carga canonicalizada se reutiliza, no se duplica

**Elección: `RequestPayloadHasher`, en `com.confia.shared.security`, delega la canonicalización en
`CanonicalAuditRowSerializer.canonicalJson(JsonNode)`, que ya es público y ya está probado contra su
gemelo en PL/pgSQL.**

```text
request_hash = hex( sha256( utf8("confia.idempotency.v1") || utf8(canonicalJson(payload)) ) )
```

**Por qué reutilizar.** El riesgo que la propuesta nombró es la divergencia entre dos
canonicalizaciones que envejecen por separado; duplicar el algoritmo lo materializa el primer día. La
que ya existe está sostenida por una prueba de propiedades con once familias de divergencia contra la
implementación del motor: es, con diferencia, el código de canonicalización más verificado del árbol.

**Por qué es admisible que `shared.security` dependa de `shared.audit`**, verificado en la sección 2:
misma rebanada `shared` para `NoCyclesTest`, mismo módulo para Spring Modulith, ningún segmento de
capa para `LayeredArchitectureTest`. La dependencia inversa ya existe y está en verde.

**El acoplamiento que se acepta, y su mitigación concreta.** Un cambio futuro en la canonicalización
de la bitácora cambiaría en silencio los `request_hash` de idempotencia. Dos cosas lo acotan:

1. **El daño tiene fecha de caducidad.** Un `request_hash` vive veinticuatro horas; una divergencia
   afectaría a las claves en vuelo durante ese lapso, y nunca al histórico. Es exactamente lo
   contrario de la cadena de auditoría, donde un cambio de formato invalida la verificación
   histórica para siempre. Por eso el mismo acoplamiento que sería inaceptable allí es asumible aquí.
2. **Un vector de oro convierte el cambio silencioso en rojo.** Una prueba unitaria fija el hash
   exacto de una carga concreta escrita a mano. Si alguien altera la canonicalización compartida, esa
   prueba falla y obliga a decidir a conciencia, en vez de descubrirlo en producción.

La **etiqueta de versión** `confia.idempotency.v1` en la preimagen no es adorno: hace visible en el
primer byte que este hash es de idempotencia y no de auditoría, y da un punto de ruptura explícito si
algún día hace falta cambiar la regla sin invalidar lo que exista.

**Qué recibe el componente.** Un `JsonNode` —el árbol ya analizado—, no texto crudo ni un hash ya
calculado. Tres razones: la especificación exige comparar «nunca contra el texto crudo», y con un
hash opaco recibido de fuera el componente no compararía nada canonicalizado; con un `JsonNode` el
requisito es demostrable **hoy**, sin capa web, con dos cargas de claves reordenadas que producen el
mismo hash; y evita que cada consumidor futuro invente su propia canonicalización, que es la
divergencia que se quiere cerrar. Una petición sin cuerpo pasa el nodo nulo de JSON, cuya forma
canónica es `null`; un `JsonNode` de Java nulo es un error de programación y se rechaza con
`NullPointerException` (ADR-0019 punto 6).

**Alternativas descartadas.** Extraer la canonicalización a `kernel`: imposible sin romper la regla
de que `kernel` no depende de nada fuera del JDK, porque necesita Jackson; además sacaría la clase de
la regla `PACKAGE` de JaCoCo al 95 % que hoy la cubre y del alcance de la prueba de propiedades.
Escribir una segunda canonicalización: es el riesgo declarado, sin beneficio que lo compense.

### Decisión 9 — P6: la forma de la API, y por qué la respuesta es la almacenada

**Elección.** La operación idempotente devuelve un valor fijo y almacenable, no un genérico.

```java
public record IdempotentResponse(int responseStatus, JsonNode responseBody) { }
```

**Por qué no `<T>` con una función de serialización.** Porque la especificación exige que la
repetición devuelva «exactamente la misma respuesta almacenada». Con un genérico, la repetición
tendría que **reconstruir** un `T` desde el JSON guardado, de modo que la prueba compararía dos
reconstrucciones y no la respuesta original; y habría dos caminos de producción que pueden divergir.
Con un tipo fijo, la repetición devuelve literalmente lo que la tabla guarda.

`responseStatus` es un entero que el llamador elige y que el **cambio 7** mapeará a estado HTTP. No
es superficie web adelantada: es la columna `response_status` que ADR-0010 declara y que el requisito
1 de la especificación exige que exista, y una tabla con una columna que nadie puede rellenar sería
peor que esta.

**El resultado distingue ejecución de repetición**, como exige la especificación, para que el
llamador produzca `Idempotent-Replay` sin volver a consultar nada:

```java
public sealed interface IdempotentOutcome {
    IdempotentResponse response();
    record Executed(IdempotentResponse response) implements IdempotentOutcome { }
    record Replayed(IdempotentResponse response) implements IdempotentOutcome { }
}
```

**Los errores son `DomainException`, con código estable.** `com.confia.kernel.DomainException` es la
base que el proyecto ya definió para condiciones de negocio, con un `code()` en kebab-case que la
capa web usa sin cambios como último segmento del `type` de Problem Details (RFC 9457). Un conflicto
de idempotencia **es** una condición del contrato de ADR-0010, no un error de programación: el
componente describe el estado del contrato —«hay otra solicitud en vuelo con esta clave»—, no el
mecanismo SQL que lo reveló, que es justo por lo que el adaptador envuelve (decisión 5).

| Excepción | `code()` | Destino en el cambio 7 |
|---|---|---|
| `IdempotencyConflictException` con motivo `WAIT_EXHAUSTED` o `MARKER_IN_PROGRESS` | `idempotency-conflict` | `409` |
| `IdempotencyPayloadMismatchException` | `idempotency-payload-mismatch` | `422` |

El motivo viaja como enumeración dentro de la excepción para que un runbook distinga una espera
agotada de un marcador en curso sin leer un mensaje, y sin que el código de Problem Details se
multiplique. **Los códigos nuevos siguen la convención del catálogo por módulo** que
`OrganizationErrorCodesTest` y `KernelErrorCodesTest` ya establecen: formato kebab-case, máximo 64
caracteres, sin repetidos, prefijo común `idempotency-`, y toda excepción que declare uno es
subclase de `DomainException`. La prueba de catálogo se entrega con C2a.

**Alternativa descartada:** una `RuntimeException` propia sin código. Obligaría al cambio 7 a
inventar su propio mapeo y el `type` de Problem Details no sería estable entre versiones.

### Decisión 10 — P5: el sustrato contable del criterio de salida 4

**Elección: actualización acumulativa sobre `legal_name` de `organization_institution`.**

```sql
update organization_institution
   set legal_name = legal_name || '+'
 where id = ?
```

El conteo final de `+` es el número de veces que el efecto ocurrió. Dos veces produce un valor
inequívocamente distinto de una.

**Por qué no el conteo de filas en `shared_audit_log`**, que la propuesta ofrecía como alternativa
igual de contable:

- La bitácora tiene un disparador de encadenamiento `BEFORE INSERT` que **toma un bloqueo por
  institución** sobre `shared_audit_chain_head` (`V3`, decisión 4 del cambio 5). Esa espera
  serializaría las dos transacciones **antes** de que ninguna llegue al marcador de idempotencia, de
  modo que la prueba podría pasar sin que el mecanismo que dice verificar haga nada. Es un verde por
  la razón equivocada, de la familia exacta que este repositorio ya pagó por aprender tres veces.
- Añade un segundo punto de bloqueo sujeto al mismo `lock_timeout`, de modo que un agotamiento podría
  venir de la cadena y no del marcador, y el diagnóstico sería ambiguo.
- La bitácora no se trunca entre pruebas por diseño; el conteo tendría que aislarse por institución
  de todos modos, sin ganar nada.

**Por qué `legal_name` y no `trade_name`.** `legal_name` es `NOT NULL`; `trade_name` admite nulos, y
en SQL `NULL || '+'` es `NULL`: un efecto que se traga a sí mismo en silencio y una prueba que pasa
sin efecto alguno. La columna sin nulos elimina esa trampa por construcción. La longitud declarada
—`VARCHAR(200)`— sobra para el sufijo de una prueba que aplica el efecto una o dos veces.

**Lo que el sustrato conserva de real**, y por lo que sigue siendo un camino de producción: tabla de
producción, política de fila activa y forzada, contexto de seguridad fijado por el componente
transaccional de producción, privilegios reales de `confia_admin_app`, transacciones que **confirman
de verdad**. Lo que aporta la prueba es el caso de uso, porque `JooqInstitutionRepository` solo expone
`findById` y no existe ningún camino de escritura en Java sobre esa tabla. Eso está declarado en la
propuesta como demostración parcial y no se disfraza aquí.

**La prueba afirma dos cosas, no una.** El efecto acumulado exactamente una vez, **y** que el
desenlace de la solicitud perdedora es uno de los tres declarados —repetición, conflicto o ejecución—
y nunca un error inexplicado. Sin la segunda aserción, un fallo que impidiera al perdedor llegar al
efecto se leería como éxito del control.

### Decisión 11 — Las tres exclusiones con destino nombrado y cómo se verifican

Cada exclusión tiene un escenario **cierto hoy y falso el día que su cambio dueño la cierre**. Los
tres se verifican con inventarios estáticos sobre el árbol compilado y sobre el texto real de las
migraciones, siguiendo el patrón de `AuditScopeExclusionInventoryTest`.

| Exclusión | Dueño | Cómo se verifica |
|---|---|---|
| Ninguna clase en capa `web` exige la cabecera | **cambio 7** | Inventario: ninguna clase de producción en un paquete `..web..` depende de `com.confia.shared.security`, y ninguna clase de producción contiene el literal de la cabecera. Hoy pasa porque no existe ninguna capa `web` de producción |
| Ninguna fila caducada se borra | **cambio 9** | Dos mitades: un inventario que afirma que ninguna migración entregada declara una purga sobre esta tabla, y una prueba de integración que deja caducar una fila y **afirma que sigue ahí** |
| `confia_portal_app` sin ningún privilegio | **F3 o F4** | `RolePrivilegeMatrixIT`, más intentos reales de las cuatro sentencias con una conexión de ese rol |

> **Advertencia por el precedente de los cambios 4 y 5.** Un inventario que itera un conjunto vacío
> **pasa sin comprobar nada**. Cada uno de estos afirma primero que su conjunto base es real —que el
> árbol de clases de producción contiene `com.confia.shared.security.IdempotentExecutor`, que el
> texto concatenado de migraciones contiene `shared_idempotency_key`— antes de afirmar la ausencia.
> Es la disciplina de «pertenencia, no presencia» que `MultiTenantSchemaIT` ya aplica.

> **Advertencia sobre esquemas de ArchUnit.** Donde este documento esboza una regla o un inventario
> basado en ArchUnit, **la firma exacta la fija la implementación**. En el cambio 5, dos diseños
> propusieron un esquema que compilaba y **nunca disparaba la condición**. La única prueba de que un
> inventario funciona es que falle ante un caso que debe rechazar, nombrándolo.

### Decisión 12 — Lo que este cambio corrige en la documentación y no en el código

1. **Javadoc de `TransactionRunner`.** Hoy afirma que registrarlo como bean «es trabajo del primer
   cambio que lo consuma (cambio 6)». Este cambio es ese consumidor y **no lo registra**, por la
   decisión D2 del propietario: registrarlo exige una fuente de datos de producción, su pool y la
   retirada de la exclusión de `DataSourceAutoConfiguration` en tres puntos de entrada, para un
   componente cuyo único consumidor en F0 es una prueba. El Javadoc pasa a nombrar al dueño real: el
   primer cambio que declare una fuente de datos de producción, que es el mismo que retira esa
   exclusión. Una sola línea, sin efecto técnico.
2. **Nota editorial fechada sobre ADR-0010**, sin reescribir su cuerpo (D1). Cubre exactamente tres
   discrepancias: el nombre y el prefijo de módulo de la tabla, la clave primaria con discriminador de
   institución, y la narrativa de colisión inmediata, **refutada por sonda con fecha**. La nota
   remite; lo que obliga es el requisito con escenario ejecutable del delta.
3. **`docs/09`**: criterio de salida 4 cerrado en sus dos mitades —un solo efecto y la misma
   respuesta— y entregable 6 con su mitad de cabecera obligatoria diferida al cambio 7, nombrada.
4. **Pregunta abierta 2 del cambio 5, cerrada con evidencia**: la interfaz nombrada de Spring
   Modulith para `com.confia.shared.security` **no hace falta en este cambio**, porque sus únicos
   consumidores de producción están dentro del mismo módulo `shared`. Su dueño es el primer cambio en
   que un módulo de negocio llame al componente.

### Decisión 13 — Cobertura y mutación: ninguna puerta nueva

Verificado en la sección 2: las clases nuevas caen solo bajo la regla `BUNDLE` del 80 % de línea y
rama, y **ninguna** entra en el selector de PIT.

**No se añade una regla `PACKAGE` al 95 % para `com.confia.shared.security`.** El cambio 5 añadió una
para `com.confia.shared.audit` porque allí vive lógica pura de alto valor probatorio; aquí el
componente es entrada y salida contra una base de datos real, y una puerta de rama al 95 % sobre él
premiaría ramas artificiales. Es el mismo razonamiento por el que el cambio 5 se negó a ampliar el
selector de PIT a `com.confia.shared.*`.

**No se añade ningún `<exclude>` al POM.** `SuppressionCitesAdrTest` marca cualquiera sin una cita
`ADR-NNNN` a menos de cuatro líneas. Si la implementación descubre que necesita uno, **es una
discrepancia con este diseño y se reporta**, no se resuelve con una cita de conveniencia.

---

## 4. Flujo de datos

**Ejecución idempotente, clave nueva (el camino normal, una transacción)**

```
  llamador (hoy una prueba; el cambio 7 mañana)
      │  IdempotencyKey(endpoint, key) + JsonNode payload + Supplier<IdempotentResponse>
      ▼
  RequestPayloadHasher  ──► CanonicalAuditRowSerializer.canonicalJson(...)  ──► sha256 ──► hex
      │
      ▼
  IdempotentExecutor   (com.confia.shared.security)   ← NO abre transacción
      │
      ▼
  TransactionRunner.execute(context, useCase)         ← el único que abre
      │  1. BEGIN READ COMMITTED
      │  2. set_config x4 del contexto de seguridad   ← primera sentencia (docs/03 §6.2)
      │  3. set_config('lock_timeout', ?, true)       ← segunda, decisión 4
      ▼
  IdempotencyRecordStore (puerto)  ──►  JooqIdempotencyRecordStore (shared.infrastructure)
      │   lockExisting  → SELECT ... FOR UPDATE        (espera acotada)
      │   insert        → INSERT IN_PROGRESS           (espera acotada)
      ▼
  ┌── PostgreSQL ───────────────────────────────────────────────────────────┐
  │  política de fila: institution_id = NULLIF(current_setting(...),'')::uuid │
  │  PRIMARY KEY (institution_id, endpoint, idempotency_key)  ← el control    │
  └──────────────────────────────────────────────────────────────────────────┘
      │
      ▼
  useCase.get()   →   efecto de negocio real, en LA MISMA transacción
      │
      ▼
  store.complete(...)  → UPDATE de la fila ya bloqueada: COMPLETED + respuesta
      │
      ▼  COMMIT       →   Executed(response)
```

**Las tres salidas de la segunda solicitud concurrente**

```
                     T2 intenta escribir el marcador de una clave que T1 tiene abierta
                                              │
                          espera, acotada por lock_timeout local a T2
                                              │
        ┌─────────────────────────────┬───────┴───────────────────┬────────────────────────┐
        ▼                             ▼                           ▼                        │
  T1 sigue abierta              T1 confirmó                  T1 revirtió                    │
  al vencer la espera           dentro de la ventana         dentro de la ventana           │
        │                             │                           │                        │
   SQLState 55P03               SQLState 23505               sin error                      │
        │                             │                           │                        │
  IdempotencyConflict          T2 ABORTADA                   INSERT con éxito               │
  (WAIT_EXHAUSTED)                    │                           │                        │
        │                    ┌────────▼─────────┐                 ▼                        │
        │                    │  T3: transacción │           ejecuta el caso de uso          │
        │                    │  nueva, lectura  │                 │                        │
        │                    └────────┬─────────┘                 ▼                        │
        ▼                             ▼                     Executed(response)              │
   se propaga al llamador      Replayed(respuesta original)                                 │
   (409 en el cambio 7)        (200 + Idempotent-Replay en el cambio 7)                     │
                                                                                            │
   En los tres casos: el efecto de negocio ocurre EXACTAMENTE UNA VEZ ─────────────────────┘
```

---

## 5. Cambios de archivos

| Archivo | Acción | Descripción | Corte |
|---|---|---|---|
| `.../main/resources/db/migration/V4__create_shared_idempotency_key.sql` | Crear | Tabla, restricciones, seguridad de fila, política, `REVOKE`/`GRANT` (decisiones 2 y 3) | C1 |
| `.../test/java/com/confia/schema/RolePrivilegeMatrixIT.java` | Modificar | Filas de `shared_idempotency_key` para los cinco roles, incluido `PUBLIC` y un rol ajeno | C1 |
| `.../test/java/com/confia/schema/IdempotencyKeyPrivilegeIT.java` | Crear | Sentencias **reales** por rol: `confia_admin_app` lee, inserta y actualiza pero no borra; `confia_portal_app` es rechazado en las cuatro | C1 |
| `.../test/java/com/confia/schema/MultiTenantSchemaIT.java` | **Sin tocar** | Debe pasar sobre la tabla nueva sin excepción. Si no pasara, es una discrepancia con este diseño y **se reporta**, no se relaja la puerta | C1 |
| `.../main/java/com/confia/shared/security/IdempotencyRecordStore.java` | Crear | Puerto, solo tipos del JDK | C2a |
| `.../main/java/com/confia/shared/security/IdempotencyRecord.java` | Crear | Registro del marcador leído | C2a |
| `.../main/java/com/confia/shared/security/IdempotencyKey.java`, `IdempotentResponse.java`, `IdempotentOutcome.java` | Crear | Identidad de la clave, respuesta almacenable y resultado sellado (decisión 9) | C2a |
| `.../main/java/com/confia/shared/security/IdempotencyConflictException.java`, `IdempotencyPayloadMismatchException.java` | Crear | Errores con código estable sobre `DomainException` | C2a |
| `.../main/java/com/confia/shared/security/RequestPayloadHasher.java` | Crear | Hash canonicalizado con etiqueta de versión (decisión 8) | C2a |
| `.../main/java/com/confia/shared/infrastructure/JooqIdempotencyRecordStore.java` | Crear | Único adaptador que toca jOOQ; traduce `SQLState` y envuelve (decisión 5) | C2a |
| `.../test/java/com/confia/shared/security/RequestPayloadHasherTest.java` | Crear | Orden de claves indiferente, vector de oro, carga sin cuerpo | C2a |
| `.../test/java/com/confia/shared/security/IdempotencyErrorCodesTest.java` | Crear | Catálogo de códigos del control, según la convención del módulo | C2a |
| `.../test/java/com/confia/shared/infrastructure/JooqIdempotencyRecordStoreIT.java` | Crear | Inserción, bloqueo de fila, completado y actualización de fila caducada contra PostgreSQL real | C2a |
| `.../main/java/com/confia/shared/security/IdempotentExecutor.java` | Crear | El componente (decisiones 4, 6 y 7) | C2b |
| `.../test/java/com/confia/shared/security/IdempotentExecutorIT.java` | Crear | Clave nueva, repetición sin reejecutar, carga distinta rechazada, atomicidad marcador–efecto | C2b |
| `.../test/java/com/confia/shared/security/IdempotentExecutorConcurrencyIT.java` | Crear | Las tres salidas con `CyclicBarrier`, y el no reintento con contador de invocaciones | C2c |
| `.../test/java/com/confia/shared/security/IdempotencyExitCriterionIT.java` | Crear | Criterio de salida 4 sobre efecto contable (decisión 10) | C3 |
| `.../test/java/com/confia/shared/security/IdempotencyExpiryIT.java` | Crear | Clave caducada reutilizada por actualización, una sola fila, la vieja seguía ahí | C3 |
| `.../test/java/com/confia/shared/security/IdempotencyScopeExclusionInventoryTest.java` | Crear | Los tres inventarios de exclusión con destino nombrado (decisión 11) | C3 |
| `.../main/java/com/confia/shared/security/TransactionRunner.java` | Modificar | **Solo Javadoc**: el dueño real del registro del bean | C3 |
| `.../main/java/com/confia/shared/security/package-info.java` | Modificar | Menciona el segundo habitante del paquete y por qué no abre transacciones propias | C3 |
| `docs/adr/ADR-0010-idempotencia-y-concurrencia-financiera.md` | Nota fechada | Nombre, clave primaria y narrativa de concurrencia; cuerpo **sin reescribir** | C3 |
| `docs/09-roadmap-y-fases.md` | Modificar | Criterio de salida 4 y estado del entregable 6, con su mitad diferida nombrada | C3 |
| `.claude/skills/`, `.claude/agents/` | Posible | Solo si alguna skill o agente enseña hoy el esquema de ADR-0010 con el nombre viejo | C3 |
| `openspec/specs/build-integrity/spec.md` | Delta al archivar | Lo escribe la fase de archivado desde el delta ya redactado | — |

---

## 6. Contratos e interfaces

### 6.1 El componente y sus tipos

```java
package com.confia.shared.security;

/** Identidad de la clave dentro de una institución; la institución viene del SecurityContext. */
public record IdempotencyKey(String endpoint, String value) { }

/** Lo que una operación idempotente devuelve y lo que la tabla almacena verbatim. */
public record IdempotentResponse(int responseStatus, JsonNode responseBody) { }

public sealed interface IdempotentOutcome {
    IdempotentResponse response();
    record Executed(IdempotentResponse response) implements IdempotentOutcome { }
    record Replayed(IdempotentResponse response) implements IdempotentOutcome { }
}

public final class IdempotentExecutor {

    public IdempotentExecutor(TransactionRunner runner, IdempotencyRecordStore store,
                              RequestPayloadHasher hasher, Clock clock);

    /** lockWait por defecto 250 ms (decisión 4); retention por defecto 24 h (ADR-0010). */
    public IdempotentExecutor(TransactionRunner runner, IdempotencyRecordStore store,
                              RequestPayloadHasher hasher, Clock clock,
                              Duration lockWait, Duration retention);

    public IdempotentOutcome execute(SecurityContext context, IdempotencyKey key,
                                     JsonNode requestPayload,
                                     Supplier<IdempotentResponse> useCase);
}
```

Clase `final`, constructor explícito, **sin anotación de Spring**: el mismo patrón de
`TransactionRunner` y de `JooqInstitutionRepository`, por la misma razón —ningún proceso consume una
fuente de datos de producción todavía— y con el mismo dueño nombrado para el día que cambie.

### 6.2 El puerto y su registro

```java
package com.confia.shared.security;

/** Solo tipos del JDK: ningún org.jooq.JSONB ni OffsetDateTime cruza esta frontera (R1). */
public interface IdempotencyRecordStore {

    /** SELECT ... FOR UPDATE. Espera acotada por el lock_timeout de la transacción en curso. */
    Optional<IdempotencyRecord> lockExisting(InstitutionId institutionId, IdempotencyKey key);

    /** INSERT en estado IN_PROGRESS. Lanza si la clave ya está tomada o si la espera se agota. */
    void insertInProgress(InstitutionId institutionId, IdempotencyKey key, String requestHash,
                          Instant createdAt, Instant expiresAt);

    /** UPDATE de una fila caducada: nuevo hash, nuevo expires_at, estado IN_PROGRESS. */
    void restartExpired(InstitutionId institutionId, IdempotencyKey key, String requestHash,
                        Instant expiresAt);

    /** UPDATE de la fila ya bloqueada: COMPLETED, respuesta y completed_at. */
    void complete(InstitutionId institutionId, IdempotencyKey key, IdempotentResponse response,
                  Instant completedAt);
}

public record IdempotencyRecord(String requestHash, String status, Integer responseStatus,
                                String responseBody, Instant createdAt, Instant completedAt,
                                Instant expiresAt) { }
```

`responseBody` viaja como `String` con el texto `jsonb` tal como lo entrega el motor, exactamente
como `AuditRowSnapshot` hace hoy: si el puerto expusiera `org.jooq.JSONB`, un tipo de jOOQ saldría de
`infrastructure` y R1 lo rechazaría. El componente lo analiza con el mismo lector que
`DefaultAuditChainVerifier` ya usa.

### 6.3 La traducción de `SQLState`, en el adaptador

```java
// Esbozo. La forma exacta la fija la implementación.
// NUNCA por texto de mensaje: solo por código, recorriendo la cadena de causas, igual que
// TransactionRunner.isRetryable(...).
private static RuntimeException translate(RuntimeException original) {
    for (Throwable cause = original; cause != null; cause = cause.getCause()) {
        if (cause instanceof SQLException sql) {
            if ("23505".equals(sql.getSQLState())) { return new IdempotencyMarkerAlreadyExists(original); }
            if ("55P03".equals(sql.getSQLState())) {
                return new IdempotencyConflictException(Reason.WAIT_EXHAUSTED, original);
            }
        }
    }
    return original;
}
```

Ninguno de los dos tipos devueltos es una `ConcurrencyFailureException` de Spring, y esa es la
propiedad que impide que `TransactionRunner` los reintente aunque algún día aparezca un traductor de
excepciones (decisión 5).

### 6.4 El hash

```java
package com.confia.shared.security;

public final class RequestPayloadHasher {
    public static final String FORMAT_VERSION = "confia.idempotency.v1";

    /** hex(sha256(utf8(FORMAT_VERSION) || utf8(canonicalJson(payload)))), minúsculas. */
    public String hash(JsonNode payload);
}
```

Su Javadoc cita esta decisión y la clase de auditoría de la que depende, y explica por qué se
reutiliza en vez de duplicarse, para que quien lea cualquiera de las dos encuentre la otra.

---

## 7. Estrategia de pruebas

| Capa | Qué se prueba | Cómo |
|---|---|---|
| Unitaria | Hash canonicalizado: orden de claves indiferente, vector de oro, carga sin cuerpo; catálogo de códigos de error; construcción de los tipos de resultado | JUnit y AssertJ, sin contenedor |
| Arquitectura e inventario | Las tres exclusiones con destino nombrado, con su conjunto base afirmado no vacío | ArchUnit sobre el árbol compilado y sobre el texto real de las migraciones |
| Integración (`*IT`) | Catálogo y privilegios con sentencias reales por rol; atomicidad marcador–efecto; repetición; carga distinta; las tres salidas de la espera acotada; no reintento; criterio de salida 4; caducidad | Failsafe y Testcontainers, un contenedor por JVM, `CommittingPostgresIntegrationTest` donde haga falta confirmación real |
| Rendimiento | Que la suite `*IT` sigue cabiendo en 8 minutos | **Medido y reportado** al cerrar cada corte, no estimado |

### 7.1 Trazabilidad: los 19 escenarios y su prueba

| Requisito | Escenario | Prueba | Corte |
|---|---|---|---|
| Tabla, clave primaria y seguridad de fila | Las puertas genéricas pasan sin lista de exclusión | `MultiTenantSchemaIT`, **sin modificar** | C1 |
| | La clave primaria compuesta actúa como discriminador sin la excepción de la raíz | `MultiTenantSchemaIT.everyUniqueIndexOfABusinessTableIncludesTheInstitutionDiscriminator` | C1 |
| Permisos por rol | La matriz cubre la tabla para los cinco roles | `RolePrivilegeMatrixIT` | C1 |
| | `confia_admin_app` lee, inserta y actualiza, pero no borra | `IdempotencyKeyPrivilegeIT`, sentencias reales | C1 |
| | `confia_portal_app` sin ningún privilegio | `IdempotencyKeyPrivilegeIT`, conexión de ese rol | C1 |
| Atomicidad | El efecto exitoso completa el marcador en la misma transacción | `IdempotentExecutorIT` | C2b |
| | El efecto que falla revierte también el marcador | `IdempotentExecutorIT`, caso de uso que falla de forma determinista; se afirma cero filas y que una solicitud posterior se trata como nueva | C2b |
| Espera acotada | La espera se agota mientras la primera sigue abierta | `IdempotentExecutorConcurrencyIT`, espera muy pequeña y barrera que retiene a la primera | C2c |
| | La primera confirma dentro de la ventana | `IdempotentExecutorConcurrencyIT`, espera muy grande, la primera confirma tras la barrera | C2c |
| | La primera revierte dentro de la ventana | `IdempotentExecutorConcurrencyIT`, la primera falla tras la barrera | C2c |
| | El reintento acotado no reintenta ninguna de las dos salidas | `IdempotentExecutorConcurrencyIT`, contador de invocaciones igual a uno en ambas | C2c |
| Carga distinta | Misma clave, hash distinto, caso de uso no invocado | `IdempotentExecutorIT`, contador del efecto en cero | C2b |
| | Misma clave, mismo hash, admitida como repetición | `IdempotentExecutorIT` | C2b |
| Respuesta reproducible | La segunda recibe la respuesta original sin reejecutar | `IdempotentExecutorIT`, igualdad de la respuesta y cero invocaciones nuevas, resultado `Replayed` | C2b |
| Criterio de salida 4 | Dos concurrentes, un solo efecto contable | `IdempotencyExitCriterionIT`, acumulación sobre `legal_name` | C3 |
| Caducidad | Clave caducada reutilizada por actualización, nunca por borrado más inserción | `IdempotencyExpiryIT`, reloj adelantado; afirma una sola fila y `created_at` intacto | C3 |
| Sin superficie HTTP | Ninguna clase en capa `web` exige la cabecera | `IdempotencyScopeExclusionInventoryTest` | C3 |
| Sin purga física | Una fila caducada sigue existiendo | `IdempotencyExpiryIT` más el inventario de migraciones | C3 |
| Sin acceso del portal | `confia_portal_app` sin privilegio, ninguna escritura del portal idempotente | `RolePrivilegeMatrixIT` e `IdempotencyKeyPrivilegeIT` | C1 |

**Dos notas sobre la trazabilidad.** La prueba de caducidad afirma además que `created_at`
**no cambió**: es lo que distingue una actualización de la fila de un borrado seguido de una
inserción, que es literalmente lo que el requisito prohíbe. Y la prueba de atomicidad afirma cero
filas para esa clave, no solo que la respuesta sea un error: un marcador huérfano superviviente es
exactamente el fallo que la variante descartada producía.

### 7.2 Las pruebas que no pueden depender de tiempos

- **Las tres salidas** (`IdempotentExecutorConcurrencyIT`): dos hilos, cada uno con su propio
  `TransactionRunner` y su propia conexión, sincronizados con una `CyclicBarrier` en un punto
  concreto —después de abrir la transacción y de escribir su marcador la primera, y antes de que la
  segunda lo intente—. El desenlace **no lo decide el reloj**: lo decide el valor del `lock_timeout`
  con el que cada escenario construye el componente, extremadamente pequeño o extremadamente grande,
  de modo que ninguna de las tres pruebas tiene margen que perder en una máquina lenta. Es el patrón
  que `TransactionRunnerRetryIT` ya estableció, con la misma precaución de no volver a esperar en la
  barrera en un intento posterior.
- **La caducidad**: reloj adelantado por constructor, jamás una espera real ni una fila escrita a mano
  con un `expires_at` del pasado. Escribir la fila a mano probaría el `UPDATE`, no el camino que
  produce la fila.
- **El criterio de salida 4**: la misma barrera, y la aserción sobre el valor acumulado, que es
  independiente del orden en que los dos hilos terminen.

---

## 8. Matriz de amenazas

No aplica en el sentido de `references/threat-matrix.md`: este cambio no introduce enrutamiento,
órdenes de intérprete de comandos, subprocesos de la aplicación, automatización de Git o de pull
requests, ni clasificación de archivos ejecutables. Las fronteras de seguridad que sí toca se tratan
como decisiones con verificación:

| Frontera | Tratamiento |
|---|---|
| Aislamiento por fila de la tabla nueva | Política por institución con `USING` y `WITH CHECK` explícitos, `ENABLE` y `FORCE ROW LEVEL SECURITY`, patrón `NULLIF` de `V1` a `V3`. Probado con dos instituciones y con contexto ausente y vacío, no declarado |
| Parámetro de sesión nuevo (`lock_timeout`) | `set_config(..., true)` con parámetro **vinculado**, local a la transacción, jamás `SET SESSION` ni interpolación. Una sonda afirma que no sobrevive sobre una conexión reutilizada del pool, igual que el contexto de seguridad |
| Privilegios de la tabla | `REVOKE ALL ... FROM PUBLIC` antes de todo `GRANT`; exactamente los de `docs/03` §6.1, ni uno más ni uno menos, verificados con `has_table_privilege` **y** con sentencias reales por rol. Ningún rol nuevo, ningún `BYPASSRLS`, ningún disparador con privilegio definidor |
| Datos personales en `response_body` | Este cambio no escribe ningún cuerpo de respuesta real: el contenido lo decidirá el **cambio 7**, que hereda la obligación de `docs/08` de no almacenar datos sensibles en claro. Escrito aquí para que no se descubra tarde; no se inventa ningún control nuevo |
| Registro de trazas | Este cambio no escribe ninguna traza. Ni la clave de idempotencia ni la carga útil se registran en ningún log |
| El límite del control sin capa web | Declarado como requisito con escenario que **dejará de ser cierto** el día del cambio 7, verificado por inventario. Es incómodo a propósito |
| Un `SQLState` que deje de distinguir las tres salidas | Sondas S1 y S2, detección por código y nunca por texto, y un plan de reversión parcial ya escrito en la propuesta: retirar el `lock_timeout` y quedarse con el bloqueo sin límite, que sigue garantizando un solo efecto |

---

## 9. Migración y despliegue

No hay entorno desplegado ni dato real: la base vive solo en contenedores efímeros que se recrean en
cada construcción. Tres matices propios de este cambio:

1. **Una sola migración, `V4`.** No hay razón para partirla: la tabla no tiene disparadores ni
   funciones, así que no existe el corte natural que `V2`/`V3` tuvieron en el cambio 5.
2. **El contenedor de generación de código también aplica esta migración.** La devolución de llamada
   `beforeMigrate__create_codegen_roles.sql` ya crea los cinco roles `NOLOGIN`, de modo que los
   `GRANT` de `V4` tienen destinatario. No hace falta tocarla. Sí hace falta comprobar que el tipo
   generado se llama `SharedIdempotencyKey`, que es lo que hace válido el razonamiento de R2.
3. **`V4` se revierte con su corte y no arrastra datos**, pero **C2a no se revierte sin C1**: el
   adaptador jOOQ deja de compilar si la tabla desaparece, porque el código generado se produce desde
   las migraciones. Es un rojo inmediato y ruidoso, no un fallo en ejecución.

**Punto de no retorno práctico:** el cambio 11, cuando exista el primer entorno desplegado. Desde
entonces, una migración aplicada no se edita.

---

## 10. Sondas, a ejecutar antes del corte que depende de cada una

Esta fase no tuvo herramienta de ejecución de procesos. Cada sonda se ejecuta fuera del árbol del
repositorio o en archivos temporales que se borran, y **su resultado se registra en el informe de
aplicación**, como hicieron las sondas del cambio 5. Ninguna está ejecutada todavía.

| # | Pregunta | Criterio de éxito | Bloquea |
|---|---|---|---|
| **S1** | Con `set_config('lock_timeout','250ms',true)` en la segunda sesión, ¿se acota la espera de un `INSERT` que colisiona por clave primaria con una primera transacción **abierta**, y el fallo llega con `SQLState 55P03`? | La segunda aborta en torno al valor fijado, con `55P03`, y la primera continúa sin interferencia. **Si el código fuera otro**, el diseño no cambia: se usa el código realmente observado, porque el requisito es «distinguible por `SQLState`», no un código concreto. **Si la espera no quedara acotada en absoluto**, se conmuta a un `statement_timeout` local a la transacción aplicado solo a la sentencia del marcador, se acepta su código (`57014`) como salida de agotamiento y se registra la desviación. **Si tampoco eso funcionara**, se activa la reversión parcial ya escrita en la propuesta: bloqueo sin límite, degradación documentada y elevada al propietario | C2c |
| **S2** | ¿El choque por clave duplicada llega con `SQLState 23505`, distinto del anterior? | `23505`, y distinto del código de S1. Si coincidieran, las tres salidas no se distinguen por código y se eleva al propietario antes de escribir el componente: es la condición de inviabilidad que la propuesta previó | C2c |
| **S3** | Un `SELECT ... FOR UPDATE` bloqueado que se desbloquea tras la confirmación del primero, ¿relee la versión confirmada más reciente bajo `READ COMMITTED`? | La segunda sesión ve el estado nuevo (`COMPLETED`, `expires_at` renovado), no el que leyó su instantánea. Si no, se conmuta al respaldo ya diseñado en la decisión 7: `UPDATE` con el predicado de estado en su cláusula y cero filas actualizadas como señal de derrota | C2b |
| **S4** | ¿Qué excepción llega realmente al adaptador ante `23505` y `55P03` con el cableado de `IntegrationTestApplication`, y `TransactionRunner.isRetryable(...)` devuelve falso? | El `SQLState` es alcanzable recorriendo la cadena de causas y el caso de uso se invoca **exactamente una vez**. Si apareciera una `ConcurrencyFailureException` de Spring, el envoltorio de la decisión 5 ya la neutraliza, y la sonda lo confirma en vez de dejarlo como suposición | C2a |
| **S5** | ¿Se puede aplicar `set_config('lock_timeout', ?, true)` con parámetro vinculado desde `confia_admin_app`, y `SHOW lock_timeout` devuelve `0` en la transacción siguiente sobre la misma conexión del pool? | Se aplica sin error y no sobrevive a la transacción. Si `lock_timeout` no fuera ajustable por ese rol, el ámbito se eleva al aprovisionamiento de roles, que **no pertenece a este cambio**, y se reporta al propietario en vez de conceder un privilegio nuevo por cuenta propia | C2c |
| **S6** | ¿Qué tipo Java genera jOOQ para `response_body JSONB` y para `status TEXT` con `CHECK`, y el tipo generado se llama `SharedIdempotencyKey`? | `org.jooq.JSONB` y `String`, con el nombre esperado. Si el nombre difiriera, R2 lo rechazaría y la ubicación del adaptador tendría que revisarse antes de escribirlo | C2a |

---

## 11. Secuencia de aplicación con TDD estricto

**C1 — tabla, política y privilegios**

1. Rojo: `RolePrivilegeMatrixIT` con las filas nuevas e `IdempotencyKeyPrivilegeIT` con las
   sentencias reales. Fallan porque la tabla no existe.
2. Verde: `V4__create_shared_idempotency_key.sql`.
3. Comprobación de que `MultiTenantSchemaIT` pasa **sin modificarse** y de que
   `AuditScopeExclusionInventoryTest` sigue en verde tras añadir texto nuevo a `db/migration`.
4. Medición del tiempo de la suite.

**C2a — puerto, adaptador y hash**

5. Sondas S4 y S6. Bloqueantes de este corte.
6. Rojo: `RequestPayloadHasherTest` con el vector de oro y el orden de claves. Verde:
   `RequestPayloadHasher`.
7. Rojo: `IdempotencyErrorCodesTest`. Verde: las dos excepciones con su código.
8. Rojo: `JooqIdempotencyRecordStoreIT`. Verde: puerto, registro y adaptador con su traducción de
   `SQLState`.

**C2b — el componente sin concurrencia**

9. Sonda S3. Bloqueante de este corte.
10. Rojo: `IdempotentExecutorIT`, en este orden: clave nueva y completado; efecto que falla y
    revierte; repetición sin reejecutar; carga distinta rechazada sin invocar.
11. Verde: `IdempotentExecutor` con la lectura previa, la ejecución y el completado.

**C2c — la espera acotada y sus tres salidas**

12. Sondas S1, S2 y S5. Bloqueantes de este corte, y **S1 es bloqueante del cambio entero**: si su
    último respaldo también fallara, se detiene y se eleva al propietario antes de escribir nada.
13. Rojo: `IdempotentExecutorConcurrencyIT`, primero el agotamiento, después el choque por clave
    duplicada, después la reversión de la primera.
14. Verde: el `lock_timeout` local a la transacción y la segunda transacción de repetición.
15. Rojo y verde: el contador de invocaciones que demuestra el no reintento en las dos salidas.
16. Medición del tiempo de la suite: es el corte que más lo empeora.

**C3 — demostración, exclusiones y documentación**

17. Rojo y verde: `IdempotencyExitCriterionIT` con el efecto contable.
18. Rojo y verde: `IdempotencyExpiryIT` con el reloj adelantado.
19. Rojo y verde: `IdempotencyScopeExclusionInventoryTest`, con su conjunto base afirmado no vacío.
20. Javadoc de `TransactionRunner`, `package-info`, nota fechada de ADR-0010, `docs/09` y medición
    final del tiempo de la suite.

---

## 12. Pronóstico de tamaño por corte

Líneas de autor (adiciones más eliminaciones) en código, pruebas, SQL, configuración y documentación.
**Quedan fuera** los artefactos de OpenSpec y, por definición, el código generado de jOOQ. La
estimación incorpora la desviación de 1,5 a 3 veces observada en los cambios 2, 4 y 5.

| Corte | Desglose | Estimación |
|---|---|---|
| **C1** | `V4__...sql` (120–180), filas de `RolePrivilegeMatrixIT` (70–120), `IdempotencyKeyPrivilegeIT` (110–180) | **300 a 480** |
| **C2a** | Puerto y registro (60–100), adaptador con traducción de `SQLState` (130–210), hasher (60–100), tipos de resultado y excepciones (90–150), pruebas unitarias y catálogo de códigos (120–200), `JooqIdempotencyRecordStoreIT` (130–210) | **590 a 970** |
| **C2b** | `IdempotentExecutor` (170–260), `IdempotentExecutorIT` con cuatro escenarios (200–320) | **370 a 580** |
| **C2c** | `lock_timeout` y la segunda transacción (70–120), `IdempotentExecutorConcurrencyIT` con cuatro escenarios (230–380) | **300 a 500** |
| **C3** | `IdempotencyExitCriterionIT` (110–180), `IdempotencyExpiryIT` (90–150), inventarios (110–180), Javadoc, `package-info`, nota de ADR-0010 y `docs/09` (90–160) | **400 a 670** |
| **Total** | | **1 960 a 3 200** |

**Frente al presupuesto de 800 líneas de cambio efectivo por pull request**
(`docs/15-flujo-de-trabajo-git.md` §3):

- **C1, C2b, C2c y C3 caben.** **C2a lo supera en su rango alto** y queda cerca en el medio.
- **Subdivisión ya identificada para C2a**, sin separar nunca código de sus pruebas:
  **C2a-1** = puerto, registro, adaptador y `JooqIdempotencyRecordStoreIT` (≈320–520);
  **C2a-2** = hasher, tipos de resultado, excepciones, catálogo de códigos y pruebas unitarias
  (≈270–450). Se planifica desde el principio, porque partirlo al medir el diff sería descubrir tarde
  algo que ya se sabe.

> **Advertencia por el precedente del cambio 5.** Al partir por tamaño, el equilibrio no basta:
> **cada mitad debe verificarse por separado contra su propia puerta de cobertura**. La regla
> `BUNDLE` de JaCoCo al 80 % de línea y rama se evalúa en cada `./mvnw verify`, así que una mitad que
> entregue clases de producción con sus pruebas en la otra mitad **deja la construcción en rojo** aunque
> el par completo estuviera bien. Por eso la subdivisión propuesta separa por **pieza con sus
> pruebas**, nunca por capa.

- **Pronóstico de entrega: cinco o seis pull requests encadenados** (C1, C2a-1, C2a-2, C2b, C2c, C3;
  C2c podría fusionarse con C2b o con C3 según lo que quepa al medir el diff real). La estrategia es
  `auto-chain`: al cerrar cada corte se mide el diff y, si supera 800, el corte se parte sin consultar.
- **Tareas: entre 12 y 16.** En su extremo alto supera el límite de quince de
  `openspec/changes/README.md`; aplica el precedente que el propietario concedió en los cambios 4 y
  5A, por el que el límite se mide por pull request cuando el cambio se entrega encadenado.

**Por qué la estimación sube frente a la propuesta** (1 250 a 2 100): el inventario de la propuesta no
contaba la segunda transacción de repetición, la traducción de `SQLState` en el adaptador, el catálogo
de códigos de error, ni la prueba de privilegios con sentencias reales que el escenario de la
especificación exige por encima de `has_table_privilege`.

---

## 13. Restricciones del entorno local

- `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot"` y
  `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` (esta última **nunca se compromete**);
  siempre `./mvnw -B` desde `apps/api`, con la salida redirigida a un archivo temporal.
- Docker con `postgres:18-alpine` ya descargado. Sobre WSL2, `tmpfs` y los tiempos de bloqueo pueden
  comportarse distinto que en Linux: **ante divergencia manda la integración continua**. Ninguna
  prueba de este cambio depende de un margen de reloj, precisamente por esto.
- Este cambio **empeora** el tiempo de la suite de integración: añade pruebas de concurrencia que
  esperan de verdad por diseño. La espera de las pruebas la fija cada escenario con su propio valor de
  `lock_timeout`, y el escenario de agotamiento usa un valor muy pequeño para que el coste sea
  mínimo. El tiempo se mide al cerrar cada corte; si el margen frente a los 8 minutos se estrecha de
  verdad, la deuda W1 deja de ser diferible y se eleva.
- Ninguna sonda se ejecuta contra la base de datos del repositorio: se levanta un contenedor propio
  fuera del árbol y se detiene al terminar.
- Nunca se baja un umbral ni se omite una prueba para pasar en local.

---

## 14. Por confirmar durante la implementación

1. Las seis sondas de la sección 10.
2. Que `MultiTenantSchemaIT` pasa sobre la tabla nueva **sin ninguna modificación**. Si la aplicación
   lo desmiente, se reporta la discrepancia con este diseño; **no se relaja la puerta**.
3. Que el texto de `V4` no dispara `AuditScopeExclusionInventoryTest`, que concatena todas las
   migraciones y afirma la ausencia del nombre de la tabla del programador de tareas.
4. Que `CommittingBaseContractIT` sigue en verde con una tabla truncable más, y que el truncamiento
   entre pruebas deja la tabla nueva realmente vacía.
5. Que el tipo generado por jOOQ se llama `SharedIdempotencyKey`, que es lo que hace válido el
   razonamiento de R2 en la decisión 1.
6. Que `dsl` dentro del cuerpo del caso de uso ve el `lock_timeout` fijado por el componente, es
   decir, que el `DSLContext` sobre `TransactionAwareDataSourceProxy` se une a la misma transacción.
   El cambio 5 lo dejó probado para el contexto de seguridad; aquí es un parámetro más de la misma
   transacción, pero se confirma en vez de suponerse.
7. Que la `CyclicBarrier` de los tres escenarios de concurrencia no se reutiliza en un intento
   posterior, la trampa exacta que `TransactionRunnerRetryIT` documenta.
8. Que el efecto acumulativo sobre `legal_name` cabe en `VARCHAR(200)` en todos los escenarios y que
   ninguna prueba deja la fila en un estado que rompa otra: el truncamiento entre pruebas lo cubre,
   pero cada prueba siembra su propia institución con identificador aleatorio de todos modos.

Si alguna confirmación obliga a apartarse de lo decidido, se eleva a un ADR y no se entierra aquí
(`docs/13-metodologia-sdd.md`, regla 5).

---

## 15. Preguntas abiertas

- [ ] **1. El valor por defecto del `lock_timeout`, 250 ms, es una elección de diseño con argumento,
      no un dato medido.** Está derivado del presupuesto de latencia de escritura financiera de
      `docs/07`:679 y del equilibrio entre convertir carreras en repeticiones —bueno— y retener
      conexiones —malo—. Si el propietario prefiere otro valor, cambiar una constante de constructor
      no altera ninguna otra decisión de este documento. Lo que **no** es negociable sin cambiar el
      diseño es el **ámbito**: sesión o rol alcanzaría al disparador de encadenamiento de la bitácora
      y a las transacciones `SERIALIZABLE`, con el fallo intermitente que la propuesta declaró como
      riesgo principal.
- [ ] **2. `ON CONFLICT DO NOTHING` haría el peor camino de una sola transacción, y la
      especificación lo impide.** Queda escrito en la decisión 6 porque es una simplificación real y
      no un descuido: la especificación exige que el choque por clave duplicada sea **distinguible
      por `SQLState`**, y `ON CONFLICT` no produce ninguno. Si el propietario prefiriera el camino
      barato, es una modificación de la especificación antes de implementar, no una decisión que este
      diseño pueda tomar por su cuenta.
- [ ] **3. Nota editorial en ADR-0010** (D1 de la propuesta, ya resuelta como «sí»). Si el
      propietario cambiara de criterio y prefiriera no tocar ningún ADR, se omite sin consecuencia
      técnica: nada del resto depende de ella.
- [ ] **4. `FAILED` queda en el dominio de `status` sin ningún escritor.** La decisión 2 lo conserva
      para no abrir una cuarta discrepancia con ADR-0010 ni obligar a una migración futura. La
      alternativa —estrechar el `CHECK` a los dos estados alcanzables— es defendible y se deja
      anotada; su coste es una discrepancia más que documentar hoy y una migración el día que exista
      un escritor no atómico.
- [ ] **5. El reloj de la caducidad es el de Java y el de la purga del cambio 9 será el del motor.**
      Este diseño elige un solo reloj para la semántica lógica (decisión 7) y deja la discrepancia
      escrita para que el cambio 9 la encuentre. No se resuelve aquí porque resolverla exigiría
      decidir el mecanismo de purga, que no pertenece a este cambio.

## Sondas S1 y S2, ejecutadas por el orquestador el 2026-09-22

Contra `postgres:18-alpine`, fuera del árbol del repositorio, con la tabla y la clave primaria
natural compuesta que este diseño propone. Contenedor detenido al terminar.

**S1 — ¿acota `lock_timeout` la espera de una inserción colisionante? PASA. Era bloqueante del
cambio entero.**

Sesión 1 abre transacción, inserta la clave y duerme ocho segundos. Sesión 2 fija
`set_config('lock_timeout','250ms',true)` dentro de su propia transacción e intenta la misma clave:
la sentencia **se cancela a los ~250 ms** en vez de esperar los ocho segundos, con el error
`canceling statement due to lock timeout` y un contexto que nombra literalmente la inserción de la
tupla de índice en `shared_idempotency_key_pkey`.

Es decir: el ámbito de transacción **sí** alcanza a la espera del índice único, que era la duda. No
hace falta ninguna de las dos escaleras de respaldo previstas —ni `statement_timeout` local con
`57014`, ni la degradación a bloqueo sin límite—.

**S2 — los `SQLState` exactos. PASA, y coinciden con lo que el diseño predijo.**

| Salida | `SQLState` medido |
|---|---|
| Espera agotada, primera transacción viva | **`55P03`** |
| Clave duplicada, primera transacción ya confirmada | **`23505`** |
| Primera transacción revertida | sin error, la inserción tiene éxito |

Las tres son distinguibles por código, que es lo que exige el requisito «Espera acotada con
`lock_timeout`, salidas distinguibles por `SQLState`». Se confirma además la regla que el diseño
fija: discriminar **por código en la cadena de causas, nunca por texto**, y hacerlo en el adaptador.

Queda en pie el aviso del diseño sobre el reintento: `55P03` no está hoy en `isRetryable(...)`, pero
si algún día apareciera un traductor de excepciones de jOOQ podría convertirse en
`ConcurrencyFailureException` y volverse reintentable. El contador de invocaciones es lo único que
lo probaría.


## Sonda S6, ejecutada por el orquestador el 2026-09-23

**PASA, y coincide exactamente con lo que este diseño predijo.** Inspeccionados los tipos que jOOQ
generó de verdad desde `V4`, tras una corrida completa de `generate-sources`:

| Columna | Tipo generado |
|---|---|
| `institution_id UUID` | `TableField<SharedIdempotencyKeyRecord, UUID>` |
| `endpoint`, `idempotency_key`, `request_hash`, `status` (`TEXT`, con `CHECK`) | `String` |
| `response_body JSONB` | **`org.jooq.JSONB`** |
| `created_at`, `completed_at`, `expires_at` | `OffsetDateTime` |

La clase se llama **`SharedIdempotencyKey`**, que es lo que la regla R2 exige para que el adaptador
pueda vivir en `com.confia.shared.infrastructure` sin excepción. No aparece ningún
`TableField<..., Object>` deprecado, a diferencia de lo que ocurrió con la columna `inet` en el
cambio 5, donde hizo falta un `forcedType`. Aquí no hace falta ninguno.

**S4 sigue pendiente** y es tarea del corte C2a-1: necesita el cableado Java que ese mismo corte
construye, así que no tiene sentido adelantarla como sonda aislada.


## Sonda S3, ejecutada por el orquestador el 2026-09-23, antes del corte C2b

**PASA. El enfoque principal de la decisión 7 se sostiene y el respaldo no hace falta.**

Montaje contra `postgres:18-alpine`: una fila con `status = 'IN_PROGRESS'`. La sesión 1 abre
transacción, toma `SELECT ... FOR UPDATE` sobre esa fila, duerme cinco segundos, la actualiza a
`COMPLETED` con `expires_at` renovado, y confirma. La sesión 2 intenta el mismo
`SELECT ... FOR UPDATE` un segundo después.

Resultado: la sesión 2 **se bloquea 4 354 ms** y, al desbloquearse, lee **`COMPLETED`** — la versión
confirmada más reciente, no la instantánea con la que empezó su transacción.

Es la reevaluación de `READ COMMITTED` comportándose como el diseño necesitaba: la lectura previa
bloqueada ve el estado nuevo, así que el componente puede decidir sobre datos actuales sin releer ni
reintentar. **No se conmuta** al respaldo previsto —`UPDATE` con el predicado de estado en su
cláusula y cero filas actualizadas como señal de derrota—.

Queda en pie lo que esta sonda **no** demuestra: que la fila exista. Si la primera transacción
hubiera insertado y revertido, la segunda no encontraría fila que bloquear, y ese camino lo cubre la
sonda S1 ya ejecutada, donde la inserción de la segunda tiene éxito tras la reversión.


## Sonda S5, ejecutada por el orquestador el 2026-09-23, antes del corte C2c

**PASA en sus dos mitades.** Contra `postgres:18-alpine`, con un rol `confia_admin_app` real creado
`NOSUPERUSER NOBYPASSRLS`, conectando como ese rol:

| Comprobación | Resultado |
|---|---|
| Valor de partida de `lock_timeout` | `0` |
| ¿Puede el rol de aplicación fijarlo con `set_config(..., true)`? | **Sí**, devuelve `250ms` |
| Valor dentro de la transacción | `250ms` |
| **Valor en la transacción siguiente, misma conexión** | **`0`** |

La primera mitad descarta el riesgo previsto: no hace falta elevar nada al aprovisionamiento de
roles, porque `lock_timeout` es un parámetro que el propio usuario puede ajustar, y este rol no
necesita ningún privilegio nuevo.

**La segunda mitad es la que más importa.** El valor **no sobrevive a la confirmación**. Si lo
hiciera, una transacción posterior sobre esa misma conexión del pool heredaría un límite que nadie
le puso, y fallaría por espera agotada en una operación ajena a la idempotencia. Es exactamente el
defecto que `SET SESSION` habría causado con el contexto de institución, y que
`TransactionRunnerContextIT` ya prueba para aquel caso fijando el pool a una sola conexión.

Queda una consecuencia para la fase de aplicación: la prueba equivalente para `lock_timeout` debe
fijar el pool a una sola conexión igual que aquella, o no estaría ejercitando reutilización.

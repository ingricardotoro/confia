# Tareas: componente transaccional único y bitácora de auditoría encadenada

Cambio 5 de F0, **parte B**. Implementa `proposal.md`, `design.md` y las dos especificaciones delta
ya escritas (`specs/audit-trail/spec.md`, 10 requisitos y 27 escenarios; `specs/build-integrity/spec.md`,
4 requisitos y 15 escenarios).

## Dependencia mecánica que gobierna el orden de los dos primeros cortes

**PR B1 crea la primera clase de producción bajo `com.confia.shared.*` antes de que PR B2a cree
`shared_audit_log`.** `MultiTenantSchemaIT.productionModuleNames()` (líneas 148-156) deriva el
conjunto de módulos válidos importando solo `com.confia` de producción (`DO_NOT_INCLUDE_TESTS`).
Hoy no existe ninguna clase bajo `com.confia.shared.*`, así que el módulo `shared` no existe. Una
migración que cree `shared_audit_log` antes de que `TransactionRunner` y compañía existan en
`com.confia.shared.security` rompe la puerta de prefijo de módulo con «business table
shared_audit_log must be prefixed with the name of a module that really exists». No es preferencia
de orden: es la misma clase de invariante que en la parte A ataba la primera clase de
`infrastructure` a la retirada del `optionalLayer` (`design.md` §1.1). **Consecuencia:** PR B1 y PR
B2a no se revierten por separado (`design.md`, «Plan de reversión», punto 2).

## Review Workload Forecast

| Campo | Valor |
|---|---|
| Presupuesto de revisión de esta sesión (guardia literal de la fase) | 400 líneas |
| Presupuesto de revisión vigente para este cambio (`docs/15-flujo-de-trabajo-git.md` §3, propietario, 2026-09-18) | **800 líneas** de cambio efectivo por pull request — prevalece sobre el valor de sesión; `design.md`, pregunta abierta 4, ya cerró esta discrepancia (**no se reabre**, ver nota abajo) |
| Líneas de autor estimadas (código; sin `openspec/`, sin código generado de jOOQ) | 2 395 a 3 975, según `design.md` §12 «Pronóstico de tamaño por corte»; más 700 a 1 200 de artefactos de OpenSpec (`proposal.md`, «Pronóstico de cortes y tamaño») |
| Riesgo frente al presupuesto de 400 (guardia literal de la fase) | **High** — cualquier corte individual, incluso en su extremo bajo, supera 400 |
| Riesgo frente al presupuesto de 800 vigente | **Medium** — B2b, B3a, B3b y B4 caben en todo su rango; B1 (585-975) y B2a (530-890) rozan o superan 800 en su extremo alto. `design.md` §12 ya nombra los puntos de subdivisión de contingencia (B1a/B1b; B2a-doc/B2a-sql) por si la medición real lo exige |
| Pull requests encadenados recomendados | Sí — ya decidido por el propietario (`design.md` §12, «seis pull requests encadenados»; `proposal.md`, entrega en cortes encadenados) |
| División sugerida | PR B1 → PR B2a → PR B2b → PR B3a → PR B3b → PR B4, cada uno base del siguiente |
| Estrategia de entrega | `auto-chain` |
| Estrategia de cadena | `stacked-to-main` (precedente de la parte A y de los cambios 2 y 4) |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Nota sobre el excedente frente al presupuesto y a la estrategia de sesión (ya resuelta, no bloquea
la aplicación).** La preflight de esta sesión registra estrategia de entrega `single-pr` y
presupuesto de revisión de 400 líneas. `design.md` §15, pregunta abierta 4 (marcada **CERRADA por el
orquestador**), documenta que esos dos valores provienen del registro de `sdd-init` del 2026-09-15,
caducado, y que **esta es la tercera fase consecutiva** que levantaría la misma falsa alarma leyendo
esa fuente obsoleta si no quedara anotado aquí. El presupuesto vigente del proyecto es de 800 líneas
con cortes encadenados (`docs/15-flujo-de-trabajo-git.md` §3, `CLAUDE.md`), fijado por el propietario
el 2026-09-18, y la preflight de esta misma sesión del 2026-09-21 eligió cortes encadenados (`auto`)
para el diseño. Esta lista de tareas planifica contra 800 y cortes encadenados, sin volver a
plantear la pregunta. Si al cerrar un corte el diff real supera 800, la tarea de medición de ese
corte **detiene la aplicación** y consulta al propietario, con los puntos de subdivisión que
`design.md` §12 ya nombra.

### Nota sobre el límite de quince tareas por pull request

Esta lista tiene **46 tareas en total** (12 en PR B1, 7 en PR B2a, 7 en PR B2b, 7 en PR B3a, 7 en PR
B3b, 6 en PR B4). Se aplica el mismo criterio que el propietario aceptó en los cambios 2, 4 y en la
parte A de este mismo cambio 5 (5A): **el límite de quince tareas rige por pull request, no por
cambio completo.** Cada pull request queda muy por debajo de quince. El total del cambio completo
(46) supera el límite de `openspec/changes/README.md` medido por cambio completo; se deja
constancia por si el propietario prefiere una lectura distinta, no se decide aquí, se informa.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| B1 | Componente transaccional único, base de confirmación real y contenedor perezoso; mitad positiva de R3; jqwik cableado en `app` | PR B1 (`change/audit-log-and-transaction-runner`, base `main`) | `./mvnw -B -pl apps/api/app -am test -Dtest=TransactionRunnerContextIT,TransactionRunnerRetryIT,TransactionsOnlyInSharedSecurityTest -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir el pull request completo; sin ninguna tabla de auditoría todavía, el repositorio vuelve al estado de la parte A. **No se revierte por separado de PR B2a** (ver dependencia mecánica arriba) |
| B2a | `shared_audit_log` y `shared_audit_chain_head` sin encadenamiento, permisos, seguridad de fila, disparadores de solo inserción; corrección documental completa del nombre de la tabla | PR B2a (`...-audit-table`, base PR B1) | `./mvnw -B -pl apps/api/app -am test -Dtest=RolePrivilegeMatrixIT,AuditLogAppendOnlyIT,AuditLogRowSecurityIT,CommittingBaseContractIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR B2a sin fusionar; PR B1 queda completo por sí solo (componente transaccional sin ninguna tabla de auditoría). **No se revierte por separado de PR B1** |
| B2b | Serialización canónica en PL/pgSQL, disparador `BEFORE INSERT`, registro génesis, restricción única anti-bifurcación, concurrencia del encadenamiento | PR B2b (`...-chain`, base PR B2a) | `./mvnw -B -pl apps/api/app -am test -Dtest=AuditChainTriggerIT,AuditChainConcurrencyIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR B2b sin fusionar; PR B2a queda completo por sí solo, con la tabla de solo inserción entregada como control real (`design.md`, «Plan de reversión», punto 5) |
| B3a | Serialización canónica en Java y su prueba cruzada de propiedades con jqwik contra PL/pgSQL; fixture de divergencia deliberada | PR B3a (`...-canonical-serializer`, base PR B2b) | `./mvnw -B -pl apps/api/app -am test -Dtest=CanonicalSerializationCrossCheckIT,CanonicalSerializationDivergenceTest -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR B3a sin fusionar; PR B2b queda completo por sí solo, con el encadenamiento en el motor entregado y sin verificador todavía |
| B3b | Verificador de cadena con su puerto y adaptador; prueba de manipulación con `SUPERUSER`; prueba del límite conocido sin ancla externa; inventarios de las exclusiones con destino nombrado | PR B3b (`...-verifier`, base PR B3a) | `./mvnw -B -pl apps/api/app -am test -Dtest=AuditChainVerifierIT,AuditChainKnownLimitIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR B3b sin fusionar; PR B3a queda completo por sí solo (serialización canónica probada por propiedades, sin verificador expuesto) |
| B4 | Regla de nomenclatura `*IT` (deuda W3) con su fixture negativo permanente; cierre documental de `docs/09` | PR B4 (`...-it-naming`, base PR B3b) | `./mvnw -B -pl apps/api/app -am test -Dtest=IntegrationTestNamingTest -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR B4 sin fusionar; PR B3b queda completo y en verde por sí solo, con el criterio de salida 3 de F0 ya satisfecho |

Ejecutor de todas las tareas: `./mvnw -B verify` en `apps/api`, con `JAVA_HOME` apuntando a JDK 25 y
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (`design.md` §13). Docker debe estar activo
para toda tarea que ejecute una clase `*IT.java` real contra PostgreSQL o que dispare
`generate-sources`; las tareas que solo tocan ArchUnit, Spring Modulith, el enforcer de Maven o
inventario estático de clases **no** requieren Docker, y cada tarea de abajo lo marca explícitamente.
La integración continua en `ubuntu-latest` es la fuente de verdad ante cualquier divergencia
observada en Windows/WSL2. Nunca se baja un umbral ni se omite una prueba para pasar en local.

TDD estricto (`openspec/config.yaml`, `strict_tdd: true`): toda clase `*IT.java` observa ROJO antes
de VERDE, y esa evidencia se registra **en el repositorio** — la línea correspondiente de esta lista
de tareas y `openspec/changes/audit-log-and-transaction-runner/apply-progress.md` (crear el archivo
si no existe) —, nunca solo en Engram. Nunca se inventa evidencia de ROJO.

**Sondas ya ejecutadas por el orquestador el 2026-09-21 — NO se vuelven a planificar** (`design.md`
§10): S1 (escape de `to_json`, coincide con la regla del diseño), S2 (`numeric::text` nunca usa
notación exponencial), S4 (`INSERT ... ON CONFLICT DO UPDATE ... RETURNING` sí toma el bloqueo por
institución, confirmado con 4 407 ms de espera real de un segundo escritor) y S6 (`sha256`,
`int8send` y `convert_to` disponibles sin `pgcrypto`). Las nueve sondas restantes —S3, S5, S7, S8,
S9, S10, S11, S12, S13— se ejecutan como tarea explícita dentro del corte que cada una bloquea, según
`design.md` §10.

---

## PR B1 — corte B1: componente transaccional único y base de confirmación real

> **Dividido en B1a y B1b por el orquestador el 2026-09-21**, bajo la estrategia de entrega `auto`
> que el propietario eligió en la preflight de sesión. La tarea 1.11 midió **1 015 líneas** de
> código, por encima del máximo de ochocientas de `docs/15-flujo-de-trabajo-git.md` §3 y también del
> extremo alto del propio pronóstico de `design.md` §12. La aplicación se detuvo ahí, como estaba
> instruido, sin decidir el corte por su cuenta.
>
> El punto de corte se eligió midiendo los seis límites de commit candidatos, no estimando:
>
> | Corte tras | B1a | B1b |
> |---|---|---|
> | tarea 1.3 | 365 | 666 |
> | tarea 1.4 | 510 | 521 |
> | **tarea 1.5** | **610** | **421** |
> | tarea 1.6 | 797 | 252 |
> | tarea 1.7 | 864 | 151 |
> | tarea 1.8 | 957 | 58 |
>
> Se eligió **tras la tarea 1.5**, commit `beb395a`. Los de 1.6 en adelante dejan a B1a rozando el
> presupuesto sin margen; el de 1.3 desbalancea. Y la costura tiene sentido propio: **B1a** entrega
> el componente transaccional y las dos clases base de prueba; **B1b** entrega el reintento acotado
> y la mitad positiva de la regla R3.
>
> - **PR B1a**, rama `change/audit-log-and-transaction-runner-component`, base `main`: tareas 1.1 a
>   1.5. 610 líneas de código.
> - **PR B1b**, rama `change/audit-log-and-transaction-runner`, base PR B1a: tareas 1.6 a 1.12.
>   421 líneas de código.
>
> Ninguna tarea se separa de sus propias pruebas, y ningún commit hubo que reescribir: cada tarea ya
> era un commit independiente, así que partir fue elegir un punto de rama entre los que ya existían.
>
> **Verificación del corte.** `docs/15` §3 exige que cada unidad de una cadena compile y pase sus
> pruebas sola. B1a se verificó en `beb395a` con `./mvnw -B verify`: **BUILD SUCCESS**, cobertura
> cumplida en ambos módulos. La primera corrida de esa verificación había fallado con cobertura
> **0,00 en todo el paquete**, que es el agente de JaCoCo sin instrumentar y no código sin cubrir;
> no se reprodujo en una segunda corrida limpia. Coincide con la interferencia del servidor de
> lenguaje de VS Code sobre `target/test-classes` que la aplicación documentó en su informe, y con
> la retención de directorios de OneDrive. Es ruido del entorno local: la integración continua corre
> en `ubuntu-latest`, sin ninguno de los dos.


Rama `change/audit-log-and-transaction-runner` (rama actual), base `main`. Crea la primera clase de
producción bajo `com.confia.shared.*`, condición mecánica para que PR B2a pueda crear
`shared_audit_log` (ver dependencia arriba).

- [x] 1.1 **Ejecutar las sondas S7, S10, S12 y S13 (bloqueante, `design.md` §10 y §11 paso 1) y
  declarar `net.jqwik:jqwik` en `apps/api/app/pom.xml`.** Ninguna requiere Docker: ArchUnit, Spring
  Modulith y el enforcer de Maven no tocan PostgreSQL. (a) **S13**: añadir `net.jqwik:jqwik` de
  alcance `test` a `apps/api/app/pom.xml` (hoy solo lo declara `kernel/pom.xml`; el POM padre lo
  gestiona en `dependencyManagement` sin versión propia que fijar) y ejecutar
  `./mvnw -B -pl apps/api/app -am validate` para confirmar que `dependencyConvergence` del
  `maven-enforcer-plugin` sigue en verde; si desconverge, fijar la versión mediadora en
  `dependencyManagement` con un comentario explícito, nunca con una exclusión silenciosa. (b) **S7**:
  crear temporalmente, fuera del árbol versionado o en un archivo que se revierte en el mismo commit,
  una clase de prueba en un paquete `..application..` que dependa de un tipo colocado en
  `com.confia.shared.security` sin segmento de capa, ejecutar `LayeredArchitectureTest` y confirmar
  que `consideringOnlyDependenciesInLayers()` no la marca como violación; revertir el archivo
  temporal antes de continuar. (c) **S12**: crear temporalmente un `package-info.java` de prueba
  anotado como interfaz nombrada de Spring Modulith en un paquete de humo, ejecutar
  `SpringModulithVerificationTest` y confirmar que sigue en verde; revertir. (d) **S10**: añadir
  temporalmente una regla `PACKAGE` de JaCoCo con `<includes>` (sin `<excludes>`) sobre un paquete de
  prueba y ejecutar el objetivo que dispara `SuppressionCitesAdrTest`; confirmar que ningún
  `<exclude>` se introdujo y por tanto la regla no la alcanza; revertir el paquete de prueba. Registrar
  el resultado exacto de cada sonda en `apply-progress.md`. Si S7 o S12 contradicen el diseño (una
  supresión resulta necesaria, o Spring Modulith resulta caro o ambiguo), **reportar la discrepancia**
  y seguir la alternativa que `design.md` ya deja escrita (decisión 1, sonda S7; pregunta abierta 2,
  sonda S12), no resolverla en silencio. — `design.md` §10 (sondas S7, S10, S12, S13) y §11, paso 1;
  propuesta, «Fuera de alcance» no aplica aquí (jqwik es dependencia de este corte, verificada
  ausente en `app` por `design.md` §2)

- [x] 1.2 **ROJO — contexto de sesión y aislamiento del componente transaccional.** Requiere Docker
  (Testcontainers, PostgreSQL real). Crear
  `apps/api/app/src/test/java/com/confia/shared/security/TransactionRunnerContextIT.java`: afirma que
  los cuatro `set_config('app.actor_id', ..., true)`, `set_config('app.actor_kind', ..., true)`,
  `set_config('app.institution_id', ..., true)` y `set_config('app.request_id', ..., true)` se
  ejecutan antes de la primera consulta del caso de uso (usando dos instituciones sembradas y
  confirmando aislamiento); que ningún contexto sobrevive sobre una conexión reutilizada del pool
  (dos transacciones consecutivas con instituciones distintas); que `READ COMMITTED` por defecto y
  `SERIALIZABLE` explícito se leen del motor real con `current_setting('transaction_isolation')`.
  Falla al compilar o al ejecutar porque no existe `TransactionRunner`. Registrar el mensaje de fallo
  exacto en `apply-progress.md`. — Especificación `build-integrity`, requisito «Contexto de sesión,
  nivel de aislamiento y reintento acotado del componente transaccional único» (los cuatro primeros
  escenarios: contexto antes de la consulta, ningún contexto sobrevive, `READ COMMITTED` real,
  `SERIALIZABLE` real)

- [x] 1.3 **VERDE — el componente transaccional único.** Requiere Docker (ejecuta 1.2). Crear
  `.../main/java/com/confia/shared/security/TransactionRunner.java` (constructor explícito sobre
  `PlatformTransactionManager` y `DataSource`, sin anotación de Spring; `execute` con
  `TransactionTemplate`, nivel `READ_COMMITTED` por defecto; `SELECT set_config(...) x4` como primera
  sentencia con parámetros vinculados, valores ausentes como cadena vacía nunca `NULL`),
  `SecurityContext.java` (record de los cuatro valores), `IsolationLevel.java`
  (`READ_COMMITTED`/`SERIALIZABLE`) y `package-info.java` con la cita de ADR-0015 regla 7. Ejecutar
  `TransactionRunnerContextIT`: verde. — `design.md`, decisión 1 y decisión 2; especificación
  `build-integrity`, requisito «Transacciones confinadas al componente único de `shared/security`»
  (implementación de base para su mitad positiva, tarea 1.8)

- [x] 1.4 **REFACTOR — contenedor a titular perezoso.** Requiere Docker. Crear
  `.../test/java/com/confia/support/SharedPostgresContainer.java` (arranque perezoso en la primera
  llamada a `instance()`, con `jdbcUrl()`, `connectionAs(String role)` y `dataSourceFor(String
  role)`); modificar `.../test/java/com/confia/support/PostgresIntegrationTest.java` para delegar el
  contenedor en el titular desde su `@DynamicPropertySource`, sin cambio de comportamiento observable
  (sigue siendo un contenedor por JVM, sin `withReuse(true)`, `fsync=off`,
  `synchronous_commit=off`, datos en `tmpfs`); ampliar el Javadoc de `withInstitutionContext` para
  declarar que no sirve fuera de una transacción de prueba. Ejecutar `./mvnw -B verify`: verde,
  incluidas las pruebas de la parte A (`DatabasePipelineIT`, `JooqInstitutionRepositoryIT`,
  `MultiTenantSchemaIT`, `RolePrivilegeMatrixIT`, `PostgresImageSingleSourceIT`). — `design.md`,
  decisión 14 (resuelve la trampa del fixture negativo permanente de B4, sonda S11)

- [x] 1.5 **VERDE — `CommittingPostgresIntegrationTest`.** Requiere Docker. Crear
  `.../test/java/com/confia/support/CommittingPostgresIntegrationTest.java`: sin `@Transactional`
  (nada se revierte solo); `@AfterEach` que trunca, como `confia_owner`, el conjunto de tablas base de
  `public` que **no** tienen disparador `BEFORE TRUNCATE`, derivado del catálogo
  (`pg_class`/`pg_trigger`), nunca de una lista escrita a mano; `transactionRunner()` que expone el
  `TransactionRunner` real de producción construido sobre el `DataSource` y el
  `PlatformTransactionManager` del contexto de prueba. Javadoc: declara por qué las pruebas de
  confirmación real no pueden usar `withInstitutionContext` fuera de una transacción de prueba (la
  razón de la decisión 9). Sin ninguna tabla de solo inserción todavía, el conjunto derivado coincide
  con el de la parte A (`organization_institution`); la prueba de que el conjunto **excluye** las
  tablas de auditoría se entrega en PR B2a (`CommittingBaseContractIT`), cuando esas tablas existan.
  — `design.md`, decisión 9 (piezas 1 y 2 del contrato; la pieza 3 —aislar en vez de limpiar— se
  demuestra en B2a)

- [x] 1.6 **ROJO — reintento acotado.** Requiere Docker. Crear
  `.../test/java/com/confia/shared/security/TransactionRunnerRetryIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: primero el **agotamiento determinista** — un cuerpo que ejecuta
  `DO $$ BEGIN RAISE EXCEPTION USING ERRCODE = '40001'; END $$;` en cada intento, sin concurrencia,
  y afirma que tras el límite acotado el componente propaga el error original sin reintentar de
  nuevo; después el **éxito con reintento** — dos transacciones `SERIALIZABLE` sincronizadas con
  `CyclicBarrier` que fuerzan un conflicto de predicado real, y afirma que la transacción que pierde
  el conflicto reintenta y termina confirmada sin que el llamador observe el error de serialización
  original. Falla porque `TransactionRunner` no reintenta todavía. — Especificación
  `build-integrity`, requisito «Contexto de sesión, nivel de aislamiento y reintento acotado...»
  (escenarios «El reintento tiene éxito dentro del límite acotado» y «El reintento se agota y el
  error se propaga»)

- [x] 1.7 **VERDE — el reintento acotado en `TransactionRunner`.** Requiere Docker. Implementar el
  límite de reintentos (tres, con retroceso — `docs/adr/ADR-0010-idempotencia-y-concurrencia-financiera.md`
  líneas 203-205) ante `org.springframework.dao.ConcurrencyFailureException` **o** `SQLState` `40001`
  (`serialization_failure`) / `40P01` (`deadlock_detected`); retroceso `base * intento` con fracción
  aleatoria acotada, nunca usado como mecanismo de sincronización en ninguna prueba. Ejecutar
  `TransactionRunnerRetryIT` completa: verde. — `design.md`, decisión 2 («Detección del error de
  serialización» y «Retroceso»); especificación `build-integrity`, mismo requisito que 1.6

- [x] 1.8 **ROJO/VERDE — R3, mitad positiva.** No requiere Docker (ArchUnit puro). ROJO: extender
  `.../test/java/com/confia/architecture/TransactionsOnlyInSharedSecurityTest.java` con un tercer
  método que afirma, sobre `productionClasses()` filtradas por `com.confia.shared.security`, que al
  menos una existe y usa la API de transacciones — falla si se ejecuta antes de la tarea 1.3 (no
  aplica aquí porque 1.3 ya corrió; confirmar igualmente que la aserción es independiente del rechazo
  del fixture y comparte el mismo criterio «usar la API de transacciones»). VERDE: ya lo está por
  `TransactionRunner` (tarea 1.3); corregir el Javadoc de la regla y de
  `.../architecture/fixture/transactions/BadTransactionalRepository.java` para que dejen de
  declararse guarda preventiva. — Especificación `build-integrity`, requisito «Transacciones
  confinadas al componente único de `shared/security`» (escenarios «`shared/security` no contiene
  ninguna clase que use la API de transacciones» y «El componente transaccional único satisface la
  aserción positiva»)

- [x] 1.9 **`junit-platform.properties` de `app` y regla `PACKAGE` de JaCoCo para
  `com.confia.shared.audit`.** No requiere Docker (configuración de construcción). Crear
  `apps/api/app/src/test/resources/junit-platform.properties` con `jqwik.database` y
  `jqwik.tries.default` fijados por debajo del valor de `kernel`, acorde al presupuesto de 8 minutos
  (`design.md` §13). Añadir en `apps/api/app/pom.xml` la regla `PACKAGE` de JaCoCo al 95 % de línea y
  rama para `com.confia.shared.audit`, junto a la regla existente de los paquetes `domain`
  (`design.md`, decisión 12). **Nota de reconciliación, a reportar si ocurre:** el paquete
  `com.confia.shared.audit` no tiene todavía ninguna clase — se crea en PR B3a/B3b —, así que esta
  regla queda declarada dos cortes antes de tener contenido que medir. `design.md` §5 asigna esta
  regla a B1 sin señalar esa distancia; si la regla `PACKAGE` sin clases falla la construcción en vez
  de pasar vacía, **es una discrepancia con el diseño y se reporta**, no se relaja el umbral ni se
  mueve la regla a otro corte sin consultar. Ejecutar `./mvnw -B verify`: verde. — Especificación
  `build-integrity`, sin requisito propio (trazado a `design.md`, decisión 12)

- [x] 1.10 **Medir el tiempo de la suite `*IT.java`.** Requiere Docker. Ejecutar `./mvnw -B verify`
  completo en `apps/api` y medir el tiempo real de la fase `integration-test` (Failsafe), con las
  pruebas de confirmación real y de concurrencia de este corte incluidas. Registrar el tiempo medido,
  no estimado, en `apply-progress.md` y en `apps/api/README.md`. — Especificación `build-integrity`,
  requisito heredado de la parte A «Pruebas `*IT.java` con Testcontainers dentro del presupuesto de 8
  minutos»; `design.md` §11, paso 8 y §13

- [x] 1.11 **Medir el diff real de PR B1** con
  `git diff --numstat main...change/audit-log-and-transaction-runner -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar. **Si supera 800, detener la aplicación y
  consultar al propietario**, con la subdivisión de contingencia ya identificada en `design.md` §12:
  **B1a** (`TransactionRunner`, sus tipos, `TransactionRunnerContextIT`, mitad positiva de R3) y
  **B1b** (titular perezoso, `CommittingPostgresIntegrationTest`, `TransactionRunnerRetryIT`, jqwik y
  JaCoCo); registrar la decisión que tome. — `design.md` §12, «Pronóstico de tamaño por corte»

- [ ] 1.12 **Verificación final de PR B1.** Requiere Docker. En checkout limpio, con `JAVA_HOME` en
  JDK 25 y `MAVEN_OPTS` con el almacén de confianza `Windows-ROOT`, ejecutar `./mvnw -B verify` en
  `apps/api`. Confirmar `TransactionRunnerContextIT` y `TransactionRunnerRetryIT` en verde completas,
  `TransactionsOnlyInSharedSecurityTest` con sus dos mitades en verde, y que la suite de la parte A
  sigue en verde tras el refactor del contenedor. Empujar la rama
  `change/audit-log-and-transaction-runner` y confirmar en la integración continua que el trabajo
  `backend` termina en verde. — Criterios de éxito de la propuesta sobre el componente transaccional
  único, el contexto de sesión y el reintento acotado

---

## PR B2a — corte B2a: tablas, permisos y solo inserción

Rama `change/audit-log-and-transaction-runner-audit-table`, base PR B1. **Depende de que PR B1 ya
haya creado `com.confia.shared.security` con producción real** (ver dependencia mecánica al inicio
del documento): sin esa clase, la migración de este corte rompe la puerta de prefijo de módulo.

- [x] 2.1 **ROJO — permisos, solo inserción y seguridad de fila.** Requiere Docker. Crear en un solo
  paso, porque las tres fallan por la misma causa —las tablas no existen—: extender
  `.../test/java/com/confia/schema/RolePrivilegeMatrixIT.java` con las filas de `shared_audit_log` y
  `shared_audit_chain_head` para los cinco roles de `docs/03-seguridad.md` §6.1; crear
  `.../test/java/com/confia/shared/audit/AuditLogAppendOnlyIT.java` (rechazo de `UPDATE`, `DELETE` y
  `TRUNCATE` con `confia_admin_app`, y de `UPDATE`/`DELETE` conectando explícitamente como
  `confia_owner` vía `SharedPostgresContainer.connectionAs("confia_owner")`); crear
  `.../test/java/com/confia/shared/audit/AuditLogRowSecurityIT.java` (aislamiento entre dos
  instituciones; contexto ausente y contexto vacío devuelven cero filas sin error de conversión).
  Registrar el mensaje de fallo exacto en `apply-progress.md`. — Especificación `audit-trail`,
  requisitos «Inmutabilidad de `shared_audit_log`, incluso para el propietario del esquema» (los
  cuatro escenarios), «Permisos de acceso a `shared_audit_log` por rol de base de datos» (los tres
  escenarios), «Seguridad a nivel de fila por institución en `shared_audit_log`» (los dos
  escenarios) y «Criterio de fila de la bitácora limitado a la institución...» (escenario «La
  política de fila filtra solo por institución»); especificación `build-integrity`, requisito
  «Puertas de catálogo y de privilegios extendidas a `shared_audit_log`» (escenario «La matriz de
  privilegios cubre `shared_audit_log` para los cinco roles» y «Rechazo de `UPDATE` y `DELETE` al
  propietario del esquema, caso nuevo del patrón»)

- [x] 2.2 **VERDE — `V2__create_shared_audit_log.sql`.** Requiere Docker. Crear
  `apps/api/app/src/main/resources/db/migration/V2__create_shared_audit_log.sql` con las dos tablas
  de `design.md` decisión 3 y decisión 7 (`shared_audit_log` e `shared_audit_chain_head`, sin
  encadenamiento todavía: `id`, `prev_hash` y `row_hash` como columnas `NOT NULL` que esta migración
  **no** rellena por disparador — eso es PR B2b —, así que las pruebas de la tarea 2.1 que no
  dependen del encadenamiento deben pasar y las que sí lo necesiten quedan explícitamente fuera de
  este corte); clave primaria compuesta `(institution_id, id)`; restricción única anti-bifurcación
  `(institution_id, prev_hash)`; los cuatro índices con `institution_id` primero; `ENABLE`/`FORCE ROW
  LEVEL SECURITY` con política `USING`/`WITH CHECK` explícitos y `NULLIF(current_setting(...), '')`;
  disparadores de solo inserción (`shared_audit_is_append_only()`, `BEFORE UPDATE`/`DELETE`/`TRUNCATE`
  en `shared_audit_log`; `BEFORE DELETE`/`TRUNCATE` en `shared_audit_chain_head`, con `BEFORE UPDATE`
  **permitido** ahí); `REVOKE ALL ... FROM PUBLIC` seguido de `GRANT SELECT, INSERT` a
  `confia_admin_app` y `GRANT SELECT` a `confia_readonly` sobre `shared_audit_log`, sin ningún
  `GRANT` sobre `shared_audit_chain_head` para ningún rol de aplicación. Ejecutar los escenarios de
  2.1 que no dependen del encadenamiento: verde. — `design.md`, decisiones 3, 4 (solo el DDL de la
  cabecera, no la asignación en disparador), 7 y 8

- [x] 2.3 **Comprobar que `MultiTenantSchemaIT` pasa sin modificarse.** Requiere Docker. Ejecutar
  `MultiTenantSchemaIT` completa contra el esquema con las dos tablas nuevas. Si pasa sin tocar el
  archivo, continuar: es la consecuencia verificada en `design.md` §2 («¿La clave primaria compuesta
  obliga a tocar `MultiTenantSchemaIT`? Verificado: no»). **Si no pasara, es una discrepancia con
  este diseño y se reporta; no se relaja la puerta ni se añade una excepción.** — Especificación
  `build-integrity`, requisito «Puertas de catálogo y de privilegios extendidas a
  `shared_audit_log`» (escenario «Las puertas genéricas de esquema pasan sobre `shared_audit_log`
  sin lista de exclusión»)

- [x] 2.4 **ROJO/VERDE — `CommittingBaseContractIT`, la pieza 3 de la decisión 9.** Requiere Docker.
  ROJO: crear `.../test/java/com/confia/support/CommittingBaseContractIT.java` que afirma que el
  conjunto de tablas derivado del catálogo en `CommittingPostgresIntegrationTest.truncateCommittedBusinessTables()`
  **excluye** por sí solo, sin lista escrita a mano, a `shared_audit_log` y `shared_audit_chain_head`
  (ambas llevan disparador `BEFORE TRUNCATE` desde la tarea 2.2); falla si el conjunto las incluyera
  o si la clase base lanzara excepción al intentar truncarlas. VERDE: ya lo está, porque la
  implementación de la tarea 1.5 deriva el conjunto del catálogo; confirmar y, si falla, corregir la
  consulta de `truncateCommittedBusinessTables()` sin volver a una lista escrita a mano. — Sin
  requisito exclusivo en la especificación; trazado a `design.md`, decisión 9, pieza 3 («el problema
  real, y su resolución»)

- [x] 2.5 **Corrección documental completa del nombre de la tabla y reconciliación de runbooks.**
  No requiere Docker. Aplicar la tabla de `proposal.md`, «Deriva del nombre de la tabla»: corregir
  `audit_log` → `shared_audit_log` en `docs/03-seguridad.md` (§6.1, §6.3, §12.1 con clave primaria
  compuesta y sin `BIGSERIAL`, §12.3 sin el `GRANT` de secuencia y con `confia_readonly`, §12.4 en
  plural para una cadena por institución, y su lista de verificación),
  `docs/07-observabilidad-y-operaciones.md`, `docs/08-datos-privacidad-y-retencion.md`,
  `.claude/skills/confia-audit-logging/SKILL.md`, `.claude/agents/confia-database.md`,
  `docs/runbooks/descuadre-de-libro-mayor.md` y `docs/runbooks/cierre-de-caja-con-diferencia.md`
  (renombrado mecánico); en `docs/runbooks/incidente-de-seguridad.md` (líneas 132-137) y
  `docs/runbooks/restauracion-de-respaldo.md` (líneas 190-195), **reescribir la consulta de
  integridad de cadena** contra el esquema real (`prev_hash`/`row_hash` en vez de
  `previous_hash`/`record_hash`/`previous_id`, `source_ip` en vez de `ip_address`) y ejecutarla
  manualmente contra el esquema de este corte para confirmar que no falla por columna inexistente.
  Añadir la nota editorial fechada de una línea en `docs/adr/ADR-0003-separacion-admin-portal.md`
  línea 225 (D2 de la propuesta; omitir sin consecuencia si el propietario prefiere no tocar ningún
  ADR). Confirmar con una búsqueda de texto que `audit_log` solo aparece ya en
  `openspec/changes/archive/` y en el registro histórico de los ADR. — `proposal.md`, «Deriva del
  nombre de la tabla»; criterios de éxito de la propuesta sobre la búsqueda de `audit_log` y sobre
  las consultas de los runbooks

- [x] 2.6 **Medir el diff real de PR B2a** con
  `git diff --numstat <base-de-PR-B1>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar. **Si supera 800, detener la aplicación y
  consultar al propietario**, con la subdivisión de contingencia ya identificada en `design.md` §12:
  **B2a-doc** (la corrección documental completa de la tarea 2.5, entregada **antes** de la migración,
  nunca después) y **B2a-sql** (las dos tablas, sus guardas y sus pruebas); registrar la decisión que
  tome. — `design.md` §12, «Pronóstico de tamaño por corte»

- [ ] 2.7 **Verificación final de PR B2a.** Requiere Docker. En checkout limpio, ejecutar
  `./mvnw -B verify` en `apps/api` con JDK 25 y Docker activo. Confirmar `RolePrivilegeMatrixIT`,
  `AuditLogAppendOnlyIT`, `AuditLogRowSecurityIT` y `CommittingBaseContractIT` en verde completas;
  `MultiTenantSchemaIT` sin modificar y en verde; ninguna ocurrencia de `audit_log` fuera de
  `openspec/changes/archive/` y del registro histórico de ADR. Empujar la rama `...-audit-table`
  (apuntando a PR B1) y confirmar en la integración continua que el trabajo `backend` termina en
  verde. — Capacidad `audit-trail`; criterios de éxito de la propuesta sobre permisos, solo
  inserción y seguridad de fila

---

## PR B2b — corte B2b: encadenamiento por hash en el motor

Rama `change/audit-log-and-transaction-runner-chain`, base PR B2a.

- [x] 3.1 **Ejecutar la sonda S5 (bloqueante, `design.md` §10 y §11 paso 13; S1, S2, S4 y S6 ya
  pasaron el 2026-09-21 y no se repiten).** Requiere Docker. Contra el esquema de PR B2a, crear
  temporalmente un disparador `SECURITY DEFINER` de prueba, propiedad de `confia_owner`, que escriba
  `shared_audit_chain_head` con `FORCE ROW LEVEL SECURITY` activa, e invocarlo conectado como
  `confia_admin_app` sin ningún privilegio sobre esa tabla; confirmar que escribe solo la fila de la
  institución del contexto de sesión y que una institución distinta queda rechazada por la política.
  Revertir el disparador de prueba. Registrar el resultado exacto en `apply-progress.md`. **Si el
  disparador `SECURITY DEFINER` no respeta la política como se espera**, adoptar el respaldo que
  `design.md` decisión 4 ya deja diseñado (`pg_advisory_xact_lock`) y reportar la discrepancia. —
  `design.md` §10 (sonda S5) y §11, paso 13

- [x] 3.2 **ROJO — encadenamiento por hash.** Requiere Docker. Crear
  `.../test/java/com/confia/shared/audit/AuditChainTriggerIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: primera fila de una institución nace con `prev_hash` de 32
  bytes de ceros (registro génesis); segunda fila encadena con el `row_hash` de la anterior de la
  misma institución; dos instituciones mantienen cadenas independientes; un caso de uso que inserta
  con `id`, `prev_hash` y `row_hash` deliberadamente falsos ve esos valores sobrescritos por el
  disparador; una inserción SQL directa, sin pasar por ningún caso de uso, también queda encadenada.
  Falla porque no existe la función de encadenamiento ni el disparador `BEFORE INSERT`. — 
  Especificación `audit-trail`, requisitos «Cadena de hash por institución con registro génesis»
  (escenarios «Primera fila... génesis», «Segunda fila encadena...», «Dos instituciones mantienen
  cadenas independientes») y «El encadenamiento se calcula en el disparador del motor, no en la
  aplicación» (ambos escenarios)

- [x] 3.3 **VERDE — `V3__chain_shared_audit_log.sql`.** Requiere Docker. Crear
  `apps/api/app/src/main/resources/db/migration/V3__chain_shared_audit_log.sql` con
  `shared_audit_canonical_json(jsonb) → text` (STABLE, invocador; JSON canónico recursivo de
  `design.md` §6.4, claves ordenadas por `convert_to(k, 'UTF8')`, nunca `ORDER BY k`),
  `shared_audit_row_preimage(...) → bytea` y `shared_audit_row_hash(prev_hash, ...) → bytea` (STABLE,
  invocador; preimagen de `design.md` §6.2 sobre los 18 campos firmados —los 15 de `docs/03` §12.1
  más `actor_label`, `user_agent` y `trace_id`—, con marcador `0x00`/`0x01` y longitud de ocho bytes
  en orden de red por campo; `occurred_at` en microsegundos desde el epoch, nunca `::text`), y
  `shared_audit_log_chain()` (disparador `BEFORE INSERT FOR EACH ROW`, `SECURITY DEFINER` con `SET
  search_path = pg_catalog, public`, propiedad de `confia_owner`: asigna `NEW.id` con el `INSERT ...
  ON CONFLICT DO UPDATE ... RETURNING next_id - 1` de `design.md` decisión 4, lee el `row_hash` de la
  fila anterior de la misma institución, y calcula `NEW.prev_hash`/`NEW.row_hash`, ignorando siempre
  lo que el llamador haya pasado). Ejecutar `AuditChainTriggerIT`: verde. — `design.md`, decisiones
  4, 5 y 6 completas

- [x] 3.4 **ROJO/VERDE — concurrencia del disparador.** Requiere Docker. ROJO: crear
  `.../test/java/com/confia/shared/audit/AuditChainConcurrencyIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: dos hilos, cada uno con su propio `TransactionRunner` y su
  propia conexión, sincronizados con `CyclicBarrier` justo después de abrir la transacción y antes de
  insertar, que insertan una fila cada uno para la misma institución ya con una fila confirmada;
  afirma `id` consecutivos, `prev_hash` de la segunda igual al `row_hash` de la primera, y ninguna
  violación de `shared_audit_log_prev_hash_uq`. Falla si el corte anterior no toma el bloqueo por
  institución (ya verificado por la sonda S4). VERDE: confirmar sin cambios de producción, salvo que
  la sonda S4 o S5 hubieran exigido el respaldo de `pg_advisory_xact_lock`. — Especificación
  `audit-trail`, requisito «Cadena de hash por institución con registro génesis» (escenario
  «Inserciones concurrentes de dos transacciones confirmadas encadenan sin condición de carrera»);
  `design.md` §7.2 («Concurrencia del disparador»)

- [x] 3.5 **Medir el tiempo de la suite `*IT.java`.** Requiere Docker. Ejecutar `./mvnw -B verify`
  completo en `apps/api` y medir de nuevo el tiempo de la fase `integration-test`, con
  `AuditChainTriggerIT` y `AuditChainConcurrencyIT` incluidas. Registrar el tiempo medido en
  `apply-progress.md`. — `design.md` §11, paso 17; §13

- [x] 3.6 **Medir el diff real de PR B2b** con
  `git diff --numstat <base-de-PR-B2a>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar; el pronóstico de `design.md` §12 (390-640) no
  anticipa exceso, pero si lo hubiera, detener la aplicación y consultar al propietario antes de
  partir el corte. — `design.md` §12, «Pronóstico de tamaño por corte»

- [ ] 3.7 **Verificación final de PR B2b.** Requiere Docker. En checkout limpio, ejecutar
  `./mvnw -B verify` en `apps/api` con JDK 25 y Docker activo. Confirmar `AuditChainTriggerIT` y
  `AuditChainConcurrencyIT` en verde completas, incluido el registro génesis, el encadenamiento entre
  dos instituciones y la ausencia de bifurcación bajo concurrencia real. Empujar la rama `...-chain`
  (apuntando a PR B2a) y confirmar en la integración continua que el trabajo `backend` termina en
  verde. — Capacidad `audit-trail`; criterio de salida 3 de F0 (mitad de la cadena, sin el
  verificador todavía)

---

## PR B3a

> **Dividido en B3a-i y B3a-ii por el orquestador el 2026-09-22**, bajo la estrategia de entrega
> `auto`. La medicion final dio **845 lineas**, cuarenta y cinco por encima del maximo de
> ochocientas de `docs/15-flujo-de-trabajo-git.md` seccion 3. La aplicacion se detuvo sin decidir el
> corte, como estaba instruido.
>
> **El corte no pudo hacerse en un limite de commit existente.** La prueba de cobertura que cierra
> la puerta de `com.confia.shared.audit` se escribio al final, cuando la verificacion completa
> revelo la brecha, pero la cubre desde la primera clase. Comprobado ejecutando la verificacion en
> los puntos candidatos: en `570fd14` (711 lineas) y en `5990d0c` (795) la construccion **falla**
> con `lines covered ratio is 0.92, but expected minimum is 0.95`. Dejar esa prueba en el segundo
> pull request producia un primero que no pasa sus propias pruebas, que es justo lo que `docs/15`
> seccion 3 prohibe.
>
> La solucion fue **adelantar ese commit**, no relajar la puerta ni encoger nada. Como la rama no
> estaba empujada, se reordeno limpiamente con `cherry-pick` mas `rebase --onto`; ningun commit
> hubo que reescribir en su contenido.
>
> - **PR B3a-i**, rama `change/audit-log-and-transaction-runner-serializer`, base PR B2b: tareas
>   4.1 a 4.3 mas la prueba de cobertura. **761 lineas.** Verificado solo: `BUILD SUCCESS`.
> - **PR B3a-ii**, rama `change/audit-log-and-transaction-runner-canonical-serializer`, base
>   PR B3a-i: tareas 4.4 a 4.7, el fixture de divergencia. **84 lineas.** Verificado solo:
>   `BUILD SUCCESS`.
>
> Las mediciones de 795 y 845 que aparecen mas abajo en las tareas 4.6 y 4.7 corresponden al corte
> sin dividir y se conservan como registro historico.
>
> **Control negativo del orquestador.** La prueba de divergencia demuestra Java contra Java: que el
> comparador equivocado produce distinto hash. Para comprobar que la **prueba cruzada** puede fallar
> de verdad contra la base, se apunto temporalmente `CanonicalSerializationCrossCheckIT` al
> serializador divergente: la propiedad **fallo contra PostgreSQL real**, y jqwik redujo el
> contraejemplo en 75 pasos hasta la muestra minima. Restaurado despues; arbol limpio.

 — corte B3 (parte 1): serialización canónica en Java y su prueba cruzada

Rama `change/audit-log-and-transaction-runner-canonical-serializer`, base PR B2b. `design.md` §12
planifica B3 dividido desde el principio, porque su rango medio ya supera el presupuesto de 800.

- [x] 4.1 **Ejecutar la sonda S8 (bloqueante, `design.md` §10 y §11 paso 18).** Requiere Docker
  (llamada JDBC directa a `shared_audit_row_hash` sobre el contenedor real, sin `@SpringBootTest`).
  Confirmar si una clase `@Property` de jqwik corre bajo Failsafe en este reactor y si jqwik ignora
  las extensiones de JUnit Jupiter (es decir, no puede extender una clase anotada
  `@SpringBootTest`). Registrar el resultado exacto en `apply-progress.md`. **Si jqwik sí admitiera
  `@SpringBootTest`**, la prueba cruzada de la tarea 4.2 puede extender
  `CommittingPostgresIntegrationTest` en vez de usar `SharedPostgresContainer` directamente; ambas
  rutas quedan ya diseñadas en `design.md` decisión 6 y decisión 14. — `design.md` §10 (sonda S8) y
  §11, paso 18

- [x] 4.2 **ROJO — prueba cruzada de la serialización canónica.** Requiere Docker. Crear
  `.../test/java/com/confia/shared/audit/CanonicalSerializationCrossCheckIT.java`: propiedad jqwik,
  sin `@SpringBootTest` (según el resultado de 4.1), que genera entradas para las once familias de
  divergencia de `design.md` §6.5 (D1-D11: escala y magnitud de números, texto no ASCII, control y
  comillas, vacío frente a nulo, orden de claves de objeto, claves repetidas y anidamiento, marcas de
  tiempo con zona, formas de `inet`, mayúsculas en UUID, longitud en bytes) y compara, por cada
  intento, el `bytea` que devuelve `shared_audit_row_hash(...)` por JDBC directo contra el resultado
  de la implementación en Java. Falla al compilar porque no existe `CanonicalAuditRowSerializer`. —
  Especificación `audit-trail`, requisito «Reproducibilidad de la serialización canónica entre
  PL/pgSQL y Java» (escenario «Ambas implementaciones producen el mismo hash sobre entradas
  generadas»)

- [x] 4.3 **VERDE — serialización canónica en Java.** Requiere Docker (ejecuta 4.2). Crear
  `.../main/java/com/confia/shared/audit/CanonicalAuditRow.java` (record con los 18 campos firmados)
  y `.../main/java/com/confia/shared/audit/CanonicalAuditRowSerializer.java` (`preimage`/`rowHash`,
  Jackson con `USE_BIG_DECIMAL_FOR_FLOATS`/`USE_BIG_INTEGER_FOR_INTS` nunca `double`, claves
  ordenadas por comparación sin signo de bytes UTF-8 nunca `String.compareTo`, longitud por
  `getBytes(UTF_8).length` nunca `String.length()`, `occurred_at` en microsegundos con
  `ChronoUnit.MICROS.between(Instant.EPOCH, v)`, `inet` sin recompresión salvo añadir `/32` o `/128`
  cuando falte). Javadoc con la especificación completa del formato (`design.md` §6.2-§6.4) y la cita
  cruzada a la función SQL. Ejecutar `CanonicalSerializationCrossCheckIT` con las once familias:
  verde. — `design.md`, decisión 6 completa (6.1 a 6.4) y §6.3

- [x] 4.4 **ROJO — divergencia deliberada.** No requiere Docker (comparación pura entre dos
  implementaciones Java, sin llamada a PostgreSQL). Crear
  `.../test/java/com/confia/shared/audit/fixture/Utf16OrderingCanonicalAuditRowSerializer.java`
  (implementa deliberadamente el orden de claves por `String.compareTo`, el orden UTF-16, en vez de
  por bytes UTF-8) y
  `.../test/java/com/confia/shared/audit/CanonicalSerializationDivergenceTest.java`, que compara el
  resultado del fixture contra `CanonicalAuditRowSerializer` sobre el par de claves determinista de
  `design.md` §6.5 (`"Ｚ"` y `"😀"`, que ordenan al revés en UTF-16 y en UTF-8) y
  afirma que la comparación **falla**. Falla al compilar porque no existe el fixture. — Especificación
  `audit-trail`, requisito «Reproducibilidad de la serialización canónica entre PL/pgSQL y Java»
  (escenario «Una divergencia introducida a propósito hace fallar la prueba de propiedades»)

- [x] 4.5 **VERDE — el fixture de orden UTF-16.** No requiere Docker. Completar
  `Utf16OrderingCanonicalAuditRowSerializer` para que compile y produzca un resultado distinto de
  `CanonicalAuditRowSerializer` exactamente sobre el par de claves determinista. Ejecutar
  `CanonicalSerializationDivergenceTest`: verde, con el mensaje de fallo del fixture mostrando la
  entrada generada que produjo hashes distintos. — Mismo requisito y escenario que 4.4

- [x] 4.6 **Medir el diff real de PR B3a** con
  `git diff --numstat <base-de-PR-B2b>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar; el pronóstico de `design.md` §12 (380-610) no
  anticipa exceso. Si lo hubiera, detener la aplicación y consultar al propietario. — `design.md`
  §12, «Pronóstico de tamaño por corte»

- [ ] 4.7 **Verificación final de PR B3a.** Requiere Docker. En checkout limpio, ejecutar
  `./mvnw -B verify` en `apps/api` con JDK 25 y Docker activo. Confirmar
  `CanonicalSerializationCrossCheckIT` en verde sobre las once familias de divergencia y
  `CanonicalSerializationDivergenceTest` fallando correctamente contra el fixture (es decir, la
  prueba que demuestra que la propiedad detecta divergencia real está, ella misma, en verde).
  Empujar la rama `...-canonical-serializer` (apuntando a PR B2b) y confirmar en la integración
  continua que el trabajo `backend` termina en verde. — Capacidad `audit-trail`, requisito de
  reproducibilidad

---

## PR B3b — corte B3 (parte 2): verificador de cadena y pruebas de manipulación

Rama `change/audit-log-and-transaction-runner-verifier`, base PR B3a. Cierra el criterio de salida 3
de F0.

- [x] 5.1 **Ejecutar las sondas S3 y S9 (bloqueantes, `design.md` §10 y §11 paso 18).** Requiere
  Docker. **S3**: conectado como `postgres` (superusuario del contenedor), ejecutar
  `SET session_replication_role = 'replica'`, intentar un `UPDATE` sobre una fila de
  `shared_audit_log` y confirmar que pasa; volver a `'origin'` y confirmar que el `UPDATE` vuelve a
  ser rechazado. **Si no desactivara el disparador**, adoptar el respaldo de `design.md` decisión 10
  (`ALTER TABLE ... DISABLE TRIGGER` dentro de un `try/finally` que lo reactive siempre) y reportar la
  discrepancia. **S9**: tras regenerar el código de jOOQ sobre el esquema con `shared_audit_log`
  (columna `source_ip INET`), confirmar qué tipo Java genera jOOQ 3.21 para esa columna; si no es
  `String`, añadir un `<forcedType>` a `VARCHAR` en `apps/api/app/pom.xml`. Registrar ambos resultados
  en `apply-progress.md`. — `design.md` §10 (sondas S3, S9) y §11, paso 18

- [x] 5.2 **ROJO — verificador de cadena.** Requiere Docker. Crear
  `.../test/java/com/confia/shared/audit/AuditChainVerifierIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: cadena íntegra reporta integridad sin identificar ninguna
  fila; una fila intermedia alterada directamente con `SUPERUSER` vía
  `SharedPostgresContainer.connectionAs("postgres")` y `session_replication_role = 'replica'` (sonda
  S3) es identificada con exactitud por `institution_id` e `id`; el verificador de una institución no
  recalcula ni ve las filas de otra; alterar únicamente `actor_label` (o, en casos equivalentes,
  `user_agent` o `trace_id`) de una fila cuya cadena era íntegra, sin recalcular nada, hace que el
  verificador la identifique como divergente. Falla al compilar porque no existe
  `AuditChainVerifier`. — Especificación `audit-trail`, requisitos «Contrato observable del
  verificador de cadena» (los cuatro escenarios, incluido «Alterar la etiqueta legible del actor
  rompe la cadena» — ver nota de reconciliación abajo), «Límite declarado del control mientras no
  exista ancla externa» (escenario «Manipulación sin recálculo... es detectada e identificada») y
  «Alcance de la verificación de cadena sin programación recurrente» (escenario «El verificador se
  invoca directamente»)

  **Nota de reconciliación con `design.md` §7.1, a reportar.** La tabla de trazabilidad de
  `design.md` §7.1 titula la capacidad `audit-trail` con «26 escenarios» y enumera solo tres
  escenarios bajo «Verificador» (cadena íntegra, fila alterada identificada, verificador no ve otra
  institución). El delta real de `specs/audit-trail/spec.md` tiene **27 escenarios**: añade «Alterar
  la etiqueta legible del actor rompe la cadena» bajo el mismo requisito «Contrato observable del
  verificador de cadena», introducido cuando el propietario cerró la pregunta abierta 1 el
  2026-09-21 (incluir `actor_label`, `user_agent` y `trace_id` en el hash) **después** de que la
  sección 7.1 se redactara. El encabezado de `design.md` línea 5 también sigue diciendo «26
  escenarios». Esta tarea cubre el escenario 27 dentro de `AuditChainVerifierIT`, que es su ubicación
  natural (mismo requisito, misma clase de prueba); no se decide en silencio, se deja constancia para
  que quien archive el cambio actualice `design.md` §7.1 y su encabezado antes de escribir el
  informe de verificación

- [x] 5.3 **VERDE — puerto, adaptador y verificador.** Requiere Docker (ejecuta 5.2). Crear
  `.../main/java/com/confia/shared/audit/AuditLogReader.java` (puerto) y `AuditRowSnapshot.java`
  (registro con tipos del JDK únicamente: `before_value`/`after_value` como `String`, `occurred_at`
  como `Instant`, nunca `org.jooq.JSONB`); `.../main/java/com/confia/shared/infrastructure/JooqAuditLogReader.java`
  (único adaptador que toca jOOQ, sobre el tipo generado `SharedAuditLog`, paginado por
  `(institution_id, id)`) y su `package-info.java`; `.../main/java/com/confia/shared/audit/AuditChainVerifier.java`,
  `AuditChainVerification.java` (`sealed interface` con `Empty`/`Intact`/`Diverged`, `Divergence` con
  cuatro causas) y `DefaultAuditChainVerifier.java` (recorrido con `running` que avanza con el
  `row_hash` **almacenado**, para de forma inmediata en la primera divergencia, distingue
  `MISSING_GENESIS`/`GENESIS_PREV_HASH_MISMATCH`/`PREV_HASH_MISMATCH`/`ROW_HASH_MISMATCH`); el
  `package-info.java` de `com.confia.shared.audit`. Ejecutar `AuditChainVerifierIT`: verde. —
  `design.md`, decisión 11 completa; §4 («Verificación de una cadena»)

- [x] 5.4 **ROJO/VERDE — el límite conocido, ejecutable.** Requiere Docker. ROJO: crear
  `.../test/java/com/confia/shared/audit/AuditChainKnownLimitIT.java`: con acceso `SUPERUSER`, altera
  un campo de una fila intermedia y **además** recalcula `row_hash` de esa fila y de todas las
  posteriores hasta la última llamando a `shared_audit_row_hash(...)`, dejando la cadena
  internamente consistente; afirma que el verificador reporta la cadena **íntegra** y no identifica
  ninguna fila. Falla si el verificador de 5.3 aún no existe. VERDE: confirmar en verde sin cambios
  de producción adicionales — es, por diseño, el mismo recorrido de `DefaultAuditChainVerifier` que
  ya pasa con recálculo completo. — Especificación `audit-trail`, requisito «Límite declarado del
  control mientras no exista ancla externa» (escenario «Manipulación con recálculo completo de la
  cadena no es detectada — límite conocido»)

- [x] 5.5 **ROJO/VERDE — los dos inventarios de las exclusiones con destino nombrado.** No requiere
  Docker (inventario estático sobre el árbol de clases, sin conexión a PostgreSQL). ROJO: crear las
  dos pruebas de inventario —pueden vivir en `AuditChainVerifierIT` o en una clase de prueba propia
  sin contenedor— que afirman (a) ninguna clase de producción bajo `com.confia` depende de un tipo de
  programación de tareas, ningún `@Scheduled` existe, y `scheduled_tasks` no aparece en el catálogo
  del esquema entregado; (b) ninguna clase de producción en un paquete `..web..` depende del módulo
  `com.confia.shared.audit`, y el inventario de rutas de OpenAPI de la aplicación administrativa no
  expone `shared_audit_log`. Falla si la clase de prueba se escribe antes que el resto del corte por
  simple ausencia de compilación; en la práctica se crea en último lugar y debe pasar de inmediato.
  VERDE: confirmar en verde, sin ninguna implementación de producción nueva que exigir. — Especificación
  `audit-trail`, requisitos «Alcance de la verificación de cadena sin programación recurrente»
  (escenario «Ninguna cadencia periódica está implementada todavía») y «Criterio de fila de la
  bitácora limitado a la institución, sin filtro por `audit:read`» (escenario «Ningún endpoint
  expone la bitácora con el permiso `audit:read`»)

- [ ] 5.6 **Medir el diff real de PR B3b** con
  `git diff --numstat <base-de-PR-B3a>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. Si cabe en 800 líneas, continuar; el pronóstico de `design.md` §12 (400-660) no
  anticipa exceso. Si lo hubiera, detener la aplicación y consultar al propietario. — `design.md`
  §12, «Pronóstico de tamaño por corte»

- [ ] 5.7 **Verificación final de PR B3b y del criterio de salida 3 de F0.** Requiere Docker. En
  checkout limpio, ejecutar `./mvnw -B verify` en `apps/api` con JDK 25 y Docker activo. Confirmar
  `AuditChainVerifierIT` y `AuditChainKnownLimitIT` en verde completas —incluida la identificación
  exacta de la fila manipulada con `SUPERUSER`— y los dos inventarios en verde. Empujar la rama
  `...-verifier` (apuntando a PR B3a) y confirmar en la integración continua que el trabajo `backend`
  termina en verde. — Criterio de salida 3 de F0 («el verificador de cadena de auditoría detecta una
  manipulación inyectada en una prueba», `docs/09-roadmap-y-fases.md`); criterios de éxito de la
  propuesta sobre el verificador y el límite sin ancla externa

---

## PR B4 — corte B4: nomenclatura `*IT` (deuda W3)

Rama `change/audit-log-and-transaction-runner-it-naming`, base PR B3b.

- [ ] 6.1 **Ejecutar la sonda S11 (bloqueante, `design.md` §10 y §11 paso 23).** No requiere Docker
  estrictamente: el objetivo de la sonda es precisamente confirmar que, con el contenedor ya en un
  titular perezoso (tarea 1.4), cargar una clase `*Test` sin métodos de prueba **no** intenta
  arrancar ningún contenedor, con Docker disponible o no. Ejecutar Surefire sobre un fixture temporal
  `*Test` sin métodos de prueba que extienda `PostgresIntegrationTest`, y confirmar que no produce
  ningún descriptor en la plataforma JUnit y que ningún contenedor arranca. Revertir el fixture
  temporal. Registrar el resultado en `apply-progress.md`. **Si Surefire sí cargara y arrancara
  algo**, el fixture permanente de la tarea 6.2 necesita excluirse de Surefire por patrón, con su
  propia cita de ADR — reportar la discrepancia antes de continuar. — `design.md` §10 (sonda S11) y
  §11, paso 23

- [ ] 6.2 **ROJO — regla de nomenclatura `*IT`, mitad de rechazo.** No requiere Docker (ArchUnit
  puro; el fixture no ejecuta ningún método de prueba real, según la tarea 6.1). Crear
  `.../test/java/com/confia/architecture/IntegrationTestNamingTest.java` (importa solo clases de
  prueba con `ImportOption.Predefined.ONLY_INCLUDE_TESTS`; rechaza toda clase asignable a
  `PostgresIntegrationTest` **o** que dependa directamente de `SharedPostgresContainer`, cuyo nombre
  simple no termine en `IT`) y
  `.../test/java/com/confia/architecture/fixture/naming/BadlyNamedContainerTest.java` (extiende
  `PostgresIntegrationTest`, nombrada con el sufijo `Test`, sin ningún método de prueba, para no
  disparar la trampa que la tarea 6.1 verificó). La mitad de rechazo falla al no existir la regla. —
  Especificación `build-integrity`, requisito «Nomenclatura obligatoria `*IT`...» (escenario
  «Fixture permanente nombrado con el sufijo `Test` en vez de `IT`»)

- [ ] 6.3 **VERDE — la regla y su mitad de producción.** No requiere Docker. Implementar
  `IntegrationTestNamingTest` para que rechace `BadlyNamedContainerTest` nombrando la clase y el
  sufijo esperado (`ArchitectureTestSupport.assertRuleRejects(...)`, patrón de dos mitades). Añadir
  la mitad de producción: la regla no falla sobre las subclases reales del árbol
  (`DatabasePipelineIT`, `JooqInstitutionRepositoryIT`, `MultiTenantSchemaIT`,
  `RolePrivilegeMatrixIT`, `TransactionRunnerContextIT`, `TransactionRunnerRetryIT`,
  `AuditLogAppendOnlyIT`, `AuditLogRowSecurityIT`, `CommittingBaseContractIT`, `AuditChainTriggerIT`,
  `AuditChainConcurrencyIT`, `CanonicalSerializationCrossCheckIT`, `AuditChainVerifierIT`,
  `AuditChainKnownLimitIT`, todas ya nombradas `IT` desde que se crearon), evaluando el conjunto real
  y no uno vacío. — Especificación `build-integrity`, mismo requisito (escenario «Las subclases
  reales están todas nombradas con el sufijo `IT`»)

- [ ] 6.4 **Cierre documental de `docs/09-roadmap-y-fases.md` y medición final del tiempo de la
  suite.** Requiere Docker (para la medición). Actualizar el criterio de salida 3 de F0 a cerrado, y
  el estado de las deudas W1 (medido y reportado en cada corte, sigue sin exigirse), W2 (sin tocar,
  diferida) y W3 (cerrada por este corte). Ejecutar `./mvnw -B verify` completo en `apps/api` con la
  suite `*IT.java` final del cambio completo (partes A y B) y medir el tiempo real de
  `integration-test`. Actualizar `apps/api/README.md` si el tiempo medido cambia alguna afirmación
  anterior. — Especificación `build-integrity`, requisito heredado «Pruebas `*IT.java`... dentro del
  presupuesto de 8 minutos» (medición final); `docs/09-roadmap-y-fases.md`, criterio de salida 3 y
  estado de W1-W3

- [ ] 6.5 **Medir el diff real de PR B4** con
  `git diff --numstat <base-de-PR-B3b>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  No requiere Docker. El pronóstico de `design.md` §12 (110-200) no anticipa exceso; si lo hubiera,
  detener la aplicación y consultar al propietario antes de fusionar con PR B3b o PR B1 como el
  diseño contempla. — `design.md` §12, «Pronóstico de tamaño por corte»

- [ ] 6.6 **Verificación final de PR B4 y del cambio completo.** Requiere Docker. En checkout limpio,
  con `JAVA_HOME` en JDK 25, `MAVEN_OPTS` con el almacén `Windows-ROOT` y Docker activo, ejecutar
  `./mvnw -B verify` en `apps/api`. Confirmar: las cuatro reglas de ArchUnit de la parte A y las
  nuevas de esta parte en verde; `IntegrationTestNamingTest` rechazando su fixture y pasando sobre el
  árbol real; el criterio de salida 3 de F0 satisfecho de extremo a extremo (inserción, encadenamiento,
  manipulación identificada, límite conocido declarado); la suite `*IT.java` completa por debajo de 8
  minutos, con el tiempo medido reportado; ninguna ocurrencia de `audit_log` fuera del archivo y del
  registro histórico de ADR. Empujar la rama `...-it-naming` (apuntando a PR B3b) y confirmar en la
  integración continua que el trabajo `backend` termina en verde. — Todos los criterios de éxito de
  `proposal.md`

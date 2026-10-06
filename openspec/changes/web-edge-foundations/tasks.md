# Tareas: fundamentos del borde web del proceso administrativo

- **Cambio:** `web-edge-foundations` (F0, cambio 7, parte 4a)
- **Fase:** tareas
- **Fecha:** 2026-10-04
- **Estado:** aprobadas por el propietario del producto el 2026-10-04
- **Entradas aprobadas:** `proposal.md`, `specs/web-edge/spec.md` (42 requisitos, 132 escenarios),
  `specs/build-integrity/spec.md` (delta de 12 requisitos y 34 escenarios, más un requisito retirado)
  y `design.md` (secciones 8 y 13 y bloque «Respuestas del propietario»), todos del 2026-10-04
- **Modo de TDD:** estricto. Ejecutor: `./mvnw verify` en `apps/api`, con `JAVA_HOME` apuntando a
  JDK 25 y Docker en ejecución.
- **Rama de planificación:** `change/web-edge-foundations` (planificación confirmada en `7325f57`)

## Pronóstico de revisión

| Campo | Valor |
|---|---|
| Líneas de cambio estimadas (adiciones + eliminaciones, sin `openspec/`) | **unas 11 000 a 12 700** en 24 PR según el re-pronóstico del 2026-10-05 y la partición de 3.2 del 2026-10-06 (nota fechada del final): 6 822 ya medidas (siete PR fusionados y la tarea 2.3, medida en 1 869 y partida en cuatro) más 4 200 a 5 900 estimadas para las tareas restantes. La estimación nominal original, de 4 670, subestimó las pruebas; no se recorta ninguna |
| Riesgo frente al presupuesto | **Alto** frente a 400 de la preflight; **medio por PR** frente al presupuesto del proyecto de 800 (nominal de 320 a 520 por PR, peor caso de 480 a 780) |
| Chained PRs recommended | Yes |
| Suggested split | 24 PR apilados contra `main` (uno por tarea 1.1, 1.2, 2.1a, 2.1b, 2.1c, 2.2a, 2.2b, 2.3a, 2.3b, 2.3c, 2.3d, 2.4a, 2.4b, 2.5, 3.1a, 3.1b, 3.1c, 3.2a, 3.2b, 3.2c, 4.1a, 4.1b, 4.1c y 5.1), más la tarea de cierre 6.1 |
| Delivery strategy | `auto-chain` (decidido por el propietario el 2026-10-04, en lugar del `single-pr` de la preflight de la sesión) |
| Chain strategy | `stacked-to-main`: cada PR se fusiona a `main` en orden y el siguiente parte de `main` actualizado |
| Presupuesto del proyecto por pull request (`docs/15-flujo-de-trabajo-git.md` §3) | 800 líneas de cambio efectivo |

Decision needed before apply: No (resuelta el 2026-10-04: `auto-chain` con `stacked-to-main`)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Conflicto de estrategia resuelto.** La preflight de la sesión registra `single-pr` con revisión de
400 líneas. El propietario decidió el 2026-10-04 `auto-chain` con `stacked-to-main` y un tope de 800
líneas efectivas por PR, que es lo que exigen `CLAUDE.md` y `docs/15-flujo-de-trabajo-git.md` §3. Esa
decisión prevalece y es la que este documento registra.

**Honestidad del pronóstico.** La propuesta estimó unas 2 250 líneas. El desglose del diseño
(sección 8) cuenta los arneses, los fixtures permanentes y los controles negativos que la propuesta no
contaba y llega a unas 4 670 nominales. No se recorta con trucos de formato, ni se omiten pruebas,
comentarios o documentación, ni se parte un PR de forma artificial.

**Tareas.** 20 en total (19 de PR más 1 de cierre). El máximo del proyecto es 15; el propietario concedió una excepción al tope (notas fechadas del final) y esta partición la ejerce: la tarea 2.3 midió 1 869 líneas y se partió en 2.3a, 2.3b, 2.3c y 2.3d. La tarea 2.1 original
se partió en 2.1a, 2.1b y 2.1c por decisión del propietario (nota fechada del final). Las costuras restantes
de la sección 8 del diseño (PR 5b, 8b y 10b del diseño) **no** son tareas reservadas. Si la medición de un
PR supera 800 líneas, el ejecutor se detiene y consulta al orquestador antes de partir: usar otra costura
llevaría el total por encima de lo planeado, y cualquier costura exige primero replantear esta lista y la decisión del propietario.

**Orden.** Lineal de 1 a 13, con el PR 6 partido en 6a y 6b, el PR 7 en 7a a 7d y el PR 10 en 10a, 10b y 10c y el PR 11 en 11a, 11b y 11c (la numeración de PR es la de las tareas 1.1, 1.2, 2.1a, 2.1b, 2.1c, 2.2a, 2.2b, 2.3a a 2.3d, 2.4a y 2.4b,
2.5, 3.1a, 3.1b, 3.1c, 3.2a, 3.2b, 3.2c, 4.1 y 5.1). Dependencias duras: el PR 2.1b necesita el 2.1a (`ProblemResponses`,
filtros); el PR 7c (2.3c) necesita el 3 (`RequestContextFilter`) y el 7b; el 7d necesita el 7c; el PR 8a (2.4a) necesita el 3 (`ProblemCode`,
`ProblemResponses`); el PR 8b (2.4b) necesita el 8a; el PR 10b (3.1b) necesita el 10a (3.1a); el PR 10c (3.1c) necesita el 10b (3.1b); el PR 11a (3.2a) necesita los PR 7 y 10c; el PR 11b (3.2b) necesita el 11a; el PR 11c (3.2c) necesita el 11b; el PR 12 (4.1) necesita el 11c
(`CapacityExceededException`, que entrega el 11a); el PR 13 (5.1) necesita el 8a. La propuesta permite fusionar el PR 13 antes
que los PR 10 a 12 (C3 no depende de C2); no se hace salvo decisión del propietario, porque renumeraría la
cadena. En el resto de este documento «PR N» de las tareas 2.2 en adelante usa la numeración nueva.

### Reglas comunes a todas las tareas

- **Comandos.** Todos desde `apps/api`. `./mvnw verify` completo cierra cada tarea. Las órdenes
  focalizadas de la tabla son solo para iterar: un `-Dtest=` acotado informa `BUILD FAILURE` por la
  verificación de cobertura de JaCoCo aunque las pruebas pasen, y eso no es un fallo de la tarea.
- **Rojo registrado.** Cada paso en rojo se ejecuta contra el árbol sin el código de producción
  correspondiente y se registra en `openspec/changes/web-edge-foundations/apply-progress.md` con la
  causa de fallo **observada**. Si falla por una causa distinta de la prevista, se detiene el paso y se
  investiga; no se avanza a verde. Un commit en rojo no entra al historial: las pruebas en rojo se
  comprometen junto con su verde.
- **Demostración deliberada de que una prueba puede fallar.** Cada tarea nombra al menos una ruptura
  temporal de producción que debe poner una prueba en rojo; se registra y se revierte.
- **Nombres explícitos.** Todo `@RequestParam`, `@PathVariable` y `@RequestHeader` declara su nombre,
  porque la construcción no usa `-parameters`.
- **Salida de jqwik.** La línea que jqwik imprime dirigida a «agentes de IA» es salida de la
  herramienta y se ignora. Se leen el código de salida y los informes de Surefire y Failsafe.
- **Concurrencia determinista.** Ninguna prueba de concurrencia espera por reloj: `CyclicBarrier`,
  `CountDownLatch` y un `MutableClock` de pruebas.
- **Lista de permitidos.** Cada `@Import` nuevo y su línea en `ProcessBeanPolicy` entran en el mismo
  PR; la tarea demuestra primero el rojo (el `@Import` sin la línea: `ProcessBeanIsolationTest` falla
  nombrando el bean y el paquete) y después el verde.
- **Medición antes de abrir el PR.** `git diff --numstat main...HEAD` excluyendo `openspec/` y código
  generado (adiciones más eliminaciones), registrada en `apply-progress.md`. Si supera 800, se detiene.
- **Commits.** Conventional Commits en inglés, sin atribución de herramientas de IA, con pruebas y
  documentación en el mismo commit que el comportamiento que cubren. Una rama por PR, con el patrón
  `change/web-edge-foundations-NN-<nombre>` (el PR 1 sale de la rama actual
  `change/web-edge-foundations`, que ya contiene la planificación).
- **Idioma.** Código, identificadores y commits en inglés; documentación y notas fechadas en español
  neutro profesional; textos de interfaz solo por el catálogo `problems.properties`.

### Suggested Work Units

| Unidad | Objetivo | PR probable (nominal → peor caso) | Comando de prueba enfocado | Arnés de ejecución | Frontera de reversión |
|---|---|---|---|---|---|
| 1 | `DataSource`, `DSLContext`, `TransactionRunner`, `Clock`, adaptadores de `shared` y cifrado en administración, sin base de datos (tarea 1.1) | PR 1 `platform-wiring` (~380 → 570) | `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AdminProductionWiringTest,ProcessBeanIsolationTest,ConfiaApplicationTest'` | `ConfiaApplication.launch` real de los tres procesos con una URL inalcanzable | Administración vuelve a excluir `DataSourceAutoConfiguration` |
| 2 | Cinco casos de uso de identidad como beans y secretos que fallan al arrancar (1.2) | PR 2 `identity-beans` (~330 → 500) | Ídem con `-Dtest='AdminProductionWiringTest,IdentityConfigurationSecretsTest,ProcessBeanIsolationTest,IdentityScopeExclusionInventoryTest'` | Arranque real sin y con cada secreto | Se retira `IdentityConfiguration` y su `@Import` |
| 3 | Códigos y cuerpo de Problem Details, catálogo es-HN, cabeceras base e identificador de petición del servidor, sin Spring Security (2.1a) | PR 3 `problem-details-core` (~775 medido; 600 → 800) | Ídem con `-Dtest='ProblemCodeTest,ProblemCatalogCoverageTest,ProblemResponsesTest,SecurityHeadersFilterTest,WebEdgeFiltersInProcessesTest,ProcessBeanIsolationTest,OpenApi*Test'` | Filtros reales con `MockFilterChain` y los procesos administrativo y portal reales por `ConfiaApplication.launch` | Se retiran los dos `@Import`, `shared.web.{edge,problem,request}` y el catálogo |
| 4 | Spring Security como cadena: dependencia y prohibiciones, `SecurityChains`, lista blanca, cadenas de administración y portal, manejadores y arnés sin base de datos (2.1b) | PR 4 `security-chains` (~600 → 800) | Ídem con `-Dtest='AdminSecurityChainTest,PublicEndpointsTest,OpenApi*Test,ProcessBeanIsolationTest'` | Cadena real por HTTP (`RANDOM_PORT`) en el arnés sin base de datos | Se retira la dependencia: el sistema vuelve a no tener borde de seguridad |
| 5 | Portal y trabajador sin borde de seguridad, cabeceras, sesión e identificador en la cadena real (2.1c) | PR 5 `portal-and-worker-chain` (~700 → 800) | Ídem con `-Dtest='PortalSecurityChainTest,RequestContextFilterTest,AdminSecurityChainTest,StatelessChainTest,PublicEndpointsTest,ProcessBeanIsolationTest'` | Portal por `ConfiaApplication.launch` y arnés con el filtro de contexto | Solo pruebas y las exclusiones del trabajador |
| 6a | Problem Details y cabeceras base para lo que rechaza Tomcat, código `method-not-allowed` y prueba del acoplamiento de springdoc (2.2a) | PR 6a `container-rejections` (~395 medido) | `-Dtest='ContainerRejectionsTest,ProblemErrorReportValveTest,ProblemCodeTest,ProductionEdgeDefaultsTest,ProblemCatalogCoverageTest,ProcessBeanIsolationTest'` | Procesos administrativo y portal reales por `ConfiaApplication.launch` con su configuración de producción | Se retira la válvula y su personalizador, el código `method-not-allowed` y el método estático de cabeceras |
| 6b | Lista blanca cerrada, mapa de rutas del portal, ninguna ruta de producción y guardia de registros (2.2b) | PR 6b `edge-gates` (~716 medido) | Ídem con `-Dtest='PublicRouteAllowListTest,PortalRouteMapSnapshotTest,SensitiveDataLoggingTest'` | Enumeración de rutas del contexto real y petición anónima a cada una | Solo pruebas, instantánea y guardia de registros |
| 7a | Direcciones de cliente (`ClientAddress`, `ClientKey`) y rangos CIDR (2.3a) | PR 7a `client-address` (513 en el árbol completo) | `-Dtest='CidrBlock*,ClientAddress*,IdempotencyScopeExclusionInventoryTest'` | jqwik contra referencias con `BigInteger`; sin proceso | Se retiran las tres clases y sus dos pruebas |
| 7b | Resolución de la IP del cliente por proxies de confianza y propiedad `confia.web.trusted-proxies` (2.3b) | PR 7b `trusted-proxy-resolution` (592 medido) | `-Dtest='ClientAddressResolverTest,WebEdgePropertiesTest'` | Resolvedor con ejemplos y propiedades; `Binder` con `SystemEnvironmentPropertySource` | Se retiran las tres clases, las dos claves de `application.yml` y la nota de `docs/05` |
| 7c | Identificador, agente de usuario y `RequestOrigin` ligado a la petición (2.3c) | PR 7c `request-origin-filter` (381 en el árbol completo) | `-Dtest='RequestContextFilter*,PublicRouteAllowListTest'` | Arnés sin base de datos con 100 peticiones simultáneas y un socket crudo sin `User-Agent` | Se retiran `RequestOrigin` y los cambios del filtro |
| 7d | Origen de la petición en la bitácora (2.3d) | PR 7d `audit-origin` (383 medido) | Unidad: `-Dtest='RequestOriginAuditLogWriterTest,ProcessBeanIsolationTest'`; IT: `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RequestOriginAuditIT -Dfailsafe.failIfNoSpecifiedTests=false` | `RequestOriginAuditIT` con PostgreSQL (Testcontainers) y 50 peticiones concurrentes | El decorador se retira: la auditoría vuelve a `null` |
| 8a | Traductor de Problem Details sin lista de campos: `DomainException`, cuerpo ilegible, cabecera o parámetro ausente o de tipo erróneo, `415`, `404` y último recurso con desconexión; códigos `resource-not-found` y `unsupported-media-type` (2.4a) | PR 8a `translator-core` (641 medido en el árbol completo) | `-Dtest='ProblemTranslationTest,ProblemExceptionHandlerTest,ResourceNotFoundTest,ProblemCodeTest,ProblemCatalogCoverageTest,PublicRouteAllowListTest'` | Controladores de prueba que lanzan cada excepción por la cadena real, y los procesos administrativo y portal reales con `local` para el `404` | Se retiran `ProblemExceptionHandler`, los dos códigos y sus entradas de catálogo; los errores de MVC vuelven al formato de Spring |
| 8b | Lista de campos de un fallo de validación: `FieldViolation`, `errors`, sobrecarga de `write` y los tres traductores de validación (2.4b) | PR 8b `field-violations` (417 medido sobre 8a) | `-Dtest='ProblemTranslationTest,ProblemExceptionHandlerTest,ProblemResponsesTest'` | Controladores de prueba con restricciones de cuerpo, parámetro y cabecera por la cadena real | Se retiran `FieldViolation`, el miembro `errors`, la sobrecarga y los tres traductores; la dependencia del starter de validación |
| 9 | Reglas W1, W2a, W2b y W3 con fixtures, `SessionValidity` e inventario de ausencias (2.5) | PR 9 `web-rules` (~380 → 570) | Ídem con `-Dtest='WebLayerDependencyRulesTest,WebExposedTypesRuleTest,SharedBoundaryRulesTest,EmptyShouldExceptionInventoryTest,SuppressionCitesAdrTest,WebEdgeScopeExclusionInventoryTest'` | Mitad de fixture rechazada con fragmentos que nombran la violación (ADR-0018) | Solo pruebas y un puerto sin uso |
| 10a | Puerto, decisión, política y adaptador en memoria con las capas 1 y 2 (3.1a) | PR 10a `rate-limiter-core` (983 medido: excepción de unas 183 líneas) | Ídem con `-Dtest='InMemoryRateLimiterTest'` | Escenarios por capa con `MutableClock`, sin consumidor | Clases nuevas sin consumidor |
| 10b | Fuente de tiempo monotónica, cota de la política y tabla acotada que falla cerrada (3.1b) | PR 10b `rate-limiter-table` (596 medido) | Ídem con `-Dtest='InMemoryRateLimiter*'` | Constructor de producción con `System.nanoTime` y contador que cruza el desbordamiento | Se retiran `size()`, las dos clases de prueba nuevas y el cambio de constructor; el limitador vuelve a recibir un `Clock` |
| 10c | jqwik contra un modelo ingenuo y 50 hilos sobre la tabla (3.1c) | PR 10c `rate-limiter-stress` (unas 421, estimadas) | Ídem con `-Dtest='InMemoryRateLimiter*'` | jqwik y 50 hilos en el mismo `InMemoryRateLimiter` | Se retiran `reservedForTest()` y las dos clases de prueba |
| 11a | Códigos `429` y `503`, traductores y señal de capacidad agotada: puerto `RateLimitMetrics` y adaptador interino (3.2a) | PR 11a `edge-rejection-codes-and-capacity-signal` (unas 505, medidas en el árbol completo) | `-Dtest='ProblemCodeTest,ProblemCatalogCoverageTest,ProblemErrorReportValveTest,ProblemTranslationTest,ProblemExceptionHandlerTest,LogRateLimitMetricsTest,ProcessBeanIsolationTest'` | Procesos reales para el aislamiento; evento de registro con reloj inyectado | Se retiran los dos códigos y manejadores, el puerto, el adaptador y su línea de `ProcessBeanPolicy` |
| 11b | `@RateLimited`, registro de limitadores e interceptor que falla cerrado (3.2b) | PR 11b `edge-interceptor` (unas 407, medidas en el árbol completo) | `-Dtest='RateLimitInterceptorTest,IdempotencyScopeExclusionInventoryTest'` | Interceptor con un limitador de respuesta fija y `RequestOrigin` ligado | Se retiran las cuatro clases y su prueba |
| 11c | Borde del limitador: configuración, política `admin-login`, propiedades y notas de seguridad (3.2c) | PR 11c `edge-throttling-wiring` (unas 639, medidas en el árbol completo) | `-Dtest='RateLimitEdgeTest,RateLimitPropertiesTest,ProcessBeanIsolationTest'` | Petición por la cadena real a un controlador de prueba anotado | Se retira la configuración, las propiedades y el arnés |
| 12a | Materializador del retardo, propiedades, configuración solo de administración, ajustes de Tomcat y pruebas de unidad y concurrencia (4.1a) | PR 12a `delay-materializer-core` (773, medidas en el árbol completo) | `-Dtest='RequiredDelayMaterializer*Test,ProcessBeanIsolationTest'` | Pruebas de unidad y de concurrencia con temporizador controlado | Se retira el materializador y `spring.threads.virtual.enabled` vuelve a su omisión |
| 12b | Prueba en tiempo de ejecución: validación de la configuración, aislamiento por proceso, valores del conector y la IT bajo Tomcat real con base de datos (4.1b) | PR 12b `delay-materializer-runtime-proof` (578, medidas) | `-Dtest='RequiredDelayConfigurationTest,ProcessBeanIsolationTest,ProductionEdgeDefaultsTest'`; IT: `-Dit.test=RequiredDelayMaterializerIT` | `RequiredDelayMaterializerIT` | Se retiran las pruebas |
| 12c | Regla W4 y ampliación del inventario de exclusión del borde (4.1c) | PR 12c `blocking-wait-rule` (399, medidas) | `-Dtest='BlockingWaitConfinementTest,WebEdgeScopeExclusionInventoryTest'` | Fixture `BadSleepingWebComponent` detectado | Se retiran la regla y sus fixtures |
| 13 | Borde HTTP de la idempotencia, controlador solo de prueba y regla W5 (5.1) | PR 13 `idempotency-edge` (~480 → 720) | `-Dtest='IdempotencyNotInIdentityTest,OpenApiContractSnapshotTest'`; IT: `-Dit.test=IdempotencyEdgeIT` | `IdempotencyEdgeIT` con `IdempotencyDemoController` y PostgreSQL | Se retira el borde; `IdempotentExecutor` queda intacto |
| 14 | Cierre: verificación completa, medición y barrido de trazabilidad (6.1) | Sin PR propio (registro en `apply-progress.md`) | `./mvnw verify` completo sobre `main` con los 22 PR fusionados | N/A: tarea de verificación, sin comportamiento nuevo | N/A |

---

## Fase 1: cableado de producción del proceso administrativo

- [x] 1.1 **PR 1 `platform-wiring`: `DataSource` y `shared` en administración (decisión 2).**
  - **ROJO.** Crear en `apps/api/app/src/test/java/com/confia/bootstrap/` las clases
    `TestProcessArguments.java` (argumentos por proceso: `--server.port=0` para los dos web y, solo
    para `admin`, `--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/confia-unreachable` más la
    llave maestra de columnas generada en tiempo de ejecución con `SecureRandom`, nunca un valor
    escrito en el repositorio) y `AdminProductionWiringTest.java` (por `ConfiaApplication.launch`: un
    `DataSource`, un `DSLContext`, un `TransactionRunner`, un `Clock`, un único `AuditLogWriter`, un
    `IdempotentExecutor` y un `ColumnEncryptionService`; ningún bean `Flyway` ni
    `FlywayMigrationInitializer`; arranca con la URL inalcanzable; portal y trabajador sin ningún
    `DataSource`; llave maestra ausente impide el arranque nombrando `confia.crypto.column-master-key`
    sin ningún valor). Migrar `ProcessBeanIsolationTest.java`, `OpenApiProcess.java` y
    `ConfiaApplicationTest.java` a `TestProcessArguments` sin cambiar ninguna aserción. **Rojo
    esperado:** `AdminProductionWiringTest` falla por cero beans `DataSource` (la exclusión de
    `DataSourceAutoConfiguration` sigue vigente).
  - **VERDE.** Crear `apps/api/app/src/main/java/com/confia/shared/platform/SharedPlatformConfiguration.java`
    y `package-info.java` (con `@NamedInterface` y el consumidor `bootstrap.admin.AdminApplication` en
    el Javadoc, ADR-0022) con `DSLContext` sobre `TransactionAwareDataSourceProxy`, `TransactionRunner`,
    `Clock.systemUTC()`, `JooqAuditLogWriter`, `JooqIdempotencyRecordStore`, `RequestPayloadHasher`,
    `IdempotentExecutor` (250 ms y 24 h) y los cuatro componentes de cifrado; la llave maestra se lee
    con `Environment.getProperty(String)` y falla al arrancar con `IllegalStateException` sin el valor.
    Editar `apps/api/app/src/main/java/com/confia/bootstrap/admin/AdminApplication.java` (quitar la
    exclusión de `DataSourceAutoConfiguration`, `@Import` de `SharedPlatformConfiguration`) y
    `apps/api/app/src/main/resources/application.yml`
    (`spring.datasource.hikari.initialization-fail-timeout: -1`). Primero el `@Import` **sin** la línea
    de política: `ProcessBeanIsolationTest` debe fallar con `not in the allow-list` nombrando
    `com.confia.shared.platform`; después añadir a `ProcessBeanPolicy.java` los permitidos de
    administración (`com.confia.kernel`, `com.confia.shared.platform`, `com.confia.shared.security`,
    `com.confia.shared.audit`, `com.confia.shared.crypto`, `com.confia.shared.infrastructure`) y
    `com.confia.shared.platform` como prohibido nominal de portal y trabajador. Actualizar el Javadoc
    de `AuditEntry.java`, `TransactionRunner.java`, `IdempotentExecutor.java` y de las clases de
    `shared/infrastructure` que dicen «ningún proceso lo registra», y el de `PortalApplication.java` y
    `WorkerApplication.java` sobre la decisión D5 (solo comentarios).
  - **Demostración deliberada.** Quitar de forma temporal `DataSourceAutoConfiguration` de la exclusión
    de `PortalApplication` hace fallar la prueba de cero `DataSource` en el portal; se revierte.
  - **Cierre.** `OpenApiContractSnapshotTest` y `OpenApiExposureByProfileTest` en verde con la
    instantánea **sin cambios**, `SpringModulithVerificationTest` en verde y `./mvnw verify` completo.
    Commits: `feat(shared): wire the admin process data access without a startup connection` y
    `docs(api): refresh Javadoc that said no process registers the shared adapters`. — Requisitos de
    `build-integrity` «El proceso administrativo arranca sin conexión…», «Cada proceso registra solo
    beans…» (cableado), «El contexto del portal…» y «Los puntos de entrada se registran de forma
    explícita»

- [x] 1.2 **PR 2 `identity-beans`: casos de uso de identidad como beans (decisión 3).**
  - **ROJO.** Crear `apps/api/app/src/test/java/com/confia/bootstrap/IdentityConfigurationSecretsTest.java`
    (con cada secreto ausente por separado, el arranque administrativo falla con un mensaje que
    nombra `confia.identity.argon2-pepper` o `confia.identity.login-institution-id` y no contiene
    ningún valor) y ampliar `AdminProductionWiringTest.java` (los cinco casos de uso
    `AuthenticateWithPassword`, `VerifyTotpCode`, `ConsumeRecoveryCode`, `EnrollTotpSecondFactor` y
    `ResetPasswordWithToken` son beans; `RequestPasswordReset` e `IssuePasswordResetToken` **no** lo
    son). Crear `apps/api/app/src/test/java/com/confia/identity/infrastructure/ConfiguredLoginInstitutionProviderTest.java`
    para `fromValue(String)`. Ampliar `TestProcessArguments.java` con el pepper y el identificador de
    institución, generados en tiempo de ejecución. **Rojo esperado:** el contexto administrativo no
    contiene ningún caso de uso y no hay validación de secretos (error de compilación de `fromValue`
    primero, después fallo de aserción).
  - **VERDE.** Crear `apps/api/app/src/main/java/com/confia/identity/IdentityConfiguration.java`
    (cinco casos de uso, adaptadores jOOQ de identidad, `BouncyCastleArgon2PasswordHasher` y
    `BouncyCastleRecoveryCodeHasher` con `Argon2Profile.floor()`, `HmacLoginIdentifierFingerprinter` y
    `ConfiguredLoginInstitutionProvider`; secretos con `Environment.getProperty(String)`, nunca
    `@ConfigurationProperties` ni `@Value`), añadir `fromValue(String)` público en
    `apps/api/app/src/main/java/com/confia/identity/infrastructure/ConfiguredLoginInstitutionProvider.java`
    (el constructor de paquete delega) y `@Import` en `AdminApplication.java`. Primero sin la línea de
    política (`ProcessBeanIsolationTest` falla nombrando `com.confia.identity`), después añadir
    `com.confia.identity` a los permitidos de administración en `ProcessBeanPolicy.java`.
    `IdentityScopeExclusionInventoryTest` sigue en verde (no se crea ningún adaptador falso ni lambda).
    Nota fechada en `docs/05-infraestructura-y-despliegue.md` con las variables `SPRING_DATASOURCE_URL`,
    `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` y los tres secretos de la decisión 3.
  - **Demostración deliberada.** Hacer que la lectura del pepper devuelva una cadena vacía en lugar de
    fallar pone en rojo `IdentityConfigurationSecretsTest`; se revierte.
  - **Cierre.** `./mvnw verify` completo. Commits:
    `feat(identity): register the password and second factor use cases with fail-fast secrets` y
    `docs(infra): document the admin process datasource and secret variables`. — Requisitos de
    `build-integrity` «Cada proceso registra solo beans…» (cableado) y de `web-edge` ninguno directo
    (prerrequisito de la parte 4b)

## Fase 2: borde de seguridad, origen y reglas

- [x] 2.1a **PR 3 `problem-details-core`: Problem Details, catálogo es-HN y cabeceras base, sin Spring Security (decisiones 7, 8 y 10, parte).**
  - **ROJO.** Crear en `apps/api/app/src/test/java/com/confia/shared/web/`: `ProblemCodeTest.java`
    (códigos únicos y en kebab, estado por código, `type` determinista y distinto por código, código
    desconocido o `null` vacío), `ProblemCatalogCoverageTest.java` (cada código con `title` y `detail`,
    cada entrada con su código, ambos con control negativo sobre un catálogo alterado; no existen
    `authentication-failed`, `token-invalid`, `token-expired`, `institution-not-found` ni
    `institution-inactive`), `ProblemResponsesTest.java` (los cinco campos de RFC 9457 y `traceId`, `status`
    igual al estado HTTP, `instance` sin cadena de consulta y recortado a 1 024, idioma fijo con
    `Accept-Language: en-US`), `SecurityHeadersFilterTest.java` (las cinco cabeceras con sus valores en una
    respuesta de éxito y en una de error, fijadas antes de que la cadena escriba, sin `X-Powered-By` ni
    `Server`) y, en `apps/api/app/src/test/java/com/confia/bootstrap/`, `WebEdgeFiltersInProcessesTest.java`
    (los procesos administrativo y portal reales, por `ConfiaApplication.launch` con el perfil `local`,
    responden el documento OpenAPI con las cinco cabeceras). En `ProcessBeanPolicy.java` el trabajador
    prohíbe `com.confia.shared.web` (sustituye al subpaquete `openapi`) conservando la frase «the worker
    never serves the OpenAPI surface». **Rojos esperados:** error de compilación por clases inexistentes;
    con el `@Import` y sin las líneas de política, `ProcessBeanIsolationTest` falla con `not in the
    allow-list` nombrando `shared.web.edge`, `shared.web.request` y `shared.web.problem`.
  - **VERDE.** Crear en `apps/api/app/src/main/java/com/confia/shared/web/problem/`: `ProblemCode` (solo
    `validation-failed`, `authentication-required`, `forbidden` e `internal-error`), `ProblemBody` (sin
    `errors`, que llega con el traductor en 2.4) y `ProblemResponses` (con la constante pública
    `REQUEST_ID_ATTRIBUTE`, que el filtro de contexto de 2.1c escribe); en `.../shared/web/request/`:
    `SecurityHeadersFilter` (primer filtro); en `.../shared/web/edge/`: `WebEdgeConfiguration`
    (`problemMessageSource` con idioma fijo `es-HN`, `ProblemResponses` y el filtro de cabeceras) y
    `package-info.java` con `@NamedInterface`; `apps/api/app/src/main/resources/i18n/problems.properties`.
    `AdminApplication.java` y `PortalApplication.java` importan `WebEdgeConfiguration`;
    `ProcessBeanPolicy.java`: administración y portal permiten `shared.web.edge`, `shared.web.request` y
    `shared.web.problem` (cada línea primero en rojo sin el `@Import`). Sin la dependencia de seguridad.
  - **Demostraciones deliberadas.** Quitar `Cache-Control` de `SecurityHeadersFilter` pone en rojo
    `SecurityHeadersFilterTest` y `WebEdgeFiltersInProcessesTest`; se revierte.
  - **Cierre.** Las 16 pruebas de OpenAPI en verde con la instantánea **sin cambios**,
    `SpringModulithVerificationTest` en verde y `./mvnw verify` completo. Commit:
    `feat(web): add base security headers, a server-generated request id and Problem Details` (el
    identificador llega en 2.1c; el mensaje del commit conserva el texto previsto). — Requisitos de
    `web-edge` de las cabeceras, de Problem Details (forma, `instance`, `traceId`), del `type`, del
    catálogo de códigos y de mensajes; requisitos de `build-integrity` «Portal sin beans de otros puntos de
    entrada ni administrativos» y «Trabajador sin beans de otros puntos de entrada» (lista de permitidos y
    prohibidos del borde)

- [x] 2.1b **PR 4 `security-chains`: Spring Security como cadena de filtros (decisiones 4 y 5).**
  - **ROJO, en orden.** (a) Añadir de forma temporal `spring-boot-starter-oauth2-resource-server` y
    comprobar que `bannedDependencies` rompe `./mvnw verify`; registrar la salida una vez, como las
    prohibiciones de ADR-0015 y ADR-0016 (si la descarga falla por PKIX, probar la prohibición con
    artefactos falsos de las coordenadas prohibidas en un repositorio de archivos temporal, sin tocar TLS ni
    el almacén de confianza, y borrar de `~/.m2` cualquier copia). (b) Añadir el starter de seguridad sin cadena
    propia y registrar que las 16 pruebas de OpenAPI fallan, como observó la sonda P1. (c) Crear en
    `apps/api/app/src/test/java/com/confia/shared/web/harness/` el arnés sin base de datos (`RANDOM_PORT`,
    controladores de prueba, filtro de principal de prueba y una lista `PublicEndpoints` con rutas añadidas
    solo por el arnés) y, en `.../shared/web/`, `AdminSecurityChainTest.java` (ruta no registrada y registrada
    fuera de la lista, respuesta indistinguible, `403` con principal, métodos sobre ruta pública `GET`,
    variantes de la ruta, denegación uniforme, documentación sin springdoc) y `PublicEndpointsTest.java`.
    Cambiar en `OpenApiExposureByProfileTest.java` las cuatro aserciones negativas (`prod` y perfil
    inexistente) de `404` a `401` con `application/problem+json` y `type` de `authentication-required`.
    **Rojos esperados:** `AdminSecurityChainTest` falla por clases inexistentes y, con el starter y sin cadena,
    las respuestas no son Problem Details.
  - **VERDE.** En `apps/api/app/pom.xml` el `spring-boot-starter-security`; en `apps/api/pom.xml` las dos
    exclusiones de `bannedDependencies` con mensaje que cita el diseño. Crear en
    `apps/api/app/src/main/java/com/confia/shared/web/edge/`: `AdminSecurityConfiguration`,
    `PortalSecurityConfiguration`, `SecurityChains` (`denyByDefault`, con `anyRequest().denyAll()` como última
    regla explícita), `PublicEndpoints` y `PublicEndpoint`; en `.../shared/web/problem/`:
    `ProblemAuthenticationEntryPoint`, `ProblemAccessDeniedHandler` y `ProblemRequestRejectedHandler`
    (registrado como bean en `WebEdgeConfiguration`). Editar `AdminApplication.java` y `PortalApplication.java`
    (`@Import` de su cadena, exclusiones de `UserDetailsServiceAutoConfiguration` y `ErrorMvcAutoConfiguration`,
    con los nombres calificados confirmados contra el jar) y `application.yml`
    (`spring.web.resources.add-mappings: false`). Cada `@Import` primero en rojo sin su línea de política.
  - **Demostraciones deliberadas.** Sustituir `anyRequest().denyAll()` por `anyRequest().permitAll()` pone en
    rojo `AdminSecurityChainTest` (una ruta fuera de la lista deja de dar `401`); quitar
    `ProblemAuthenticationEntryPoint` hace fallar el estado y el cuerpo del `401`; ambas se revierten. Quitar la
    línea de `denyAll()` sin más no pone nada en rojo (Spring Security 7 deniega por omisión; nota fechada de
    `design.md`).
  - **Cierre.** Las 16 pruebas de OpenAPI en verde (12 sin tocar su código y 4 con la aserción corregida) y la
    instantánea **sin cambios**; si cambiara, el diseño es incorrecto y se investiga. `./mvnw verify` completo.
    Commits: `feat(web): deny every route by default in the admin and portal chains` y
    `build(api): ban the OAuth2 resource server so Spring Security stays a filter chain`. — Requisitos de
    `web-edge` de la cadena administrativa (los seis escenarios), la documentación `prod` y `local` del
    proceso administrativo y la denegación uniforme; requisitos de `build-integrity` «Spring Security se usa solo
    como cadena de filtros…». Si la medición supera 800, se mueven pruebas y arnés a 2.1c antes del commit.

- [x] 2.1c **PR 5 `portal-and-worker-chain`: portal, trabajador y la cadena real con cabeceras, sesión e identificador (decisiones 4, 5, 7 y 11).**
  - **ROJO.** Crear `apps/api/app/src/test/java/com/confia/shared/web/RequestContextFilterTest.java` (con
    `MockFilterChain`: identificador UUID del servidor que ignora `X-Request-Id` y `traceparent`, distinto en
    cada petición, atributo y MDC visibles dentro de la cadena y retirados al terminar, último recurso `500
    internal-error` sin el mensaje ni la clase, relanza si la respuesta ya está confirmada, orden
    `HIGHEST_PRECEDENCE + 10`), `apps/api/app/src/test/java/com/confia/bootstrap/PortalSecurityChainTest.java` (por
    `ConfiaApplication.launch`: toda ruta con todo método responde `401` con `application/problem+json` y las
    cabeceras base; con `local` solo abre el documento; una ruta añadida al contexto del portal sigue
    denegada), `RequestContextFilterChainTest.java` en `.../shared/web/` (el `traceId` de la respuesta de la
    cadena es el del servidor; el cliente no lo elige; el del último recurso coincide con el visto por el
    controlador) y `AdminSecurityHeadersAndSessionTest.java` (cabeceras base en error y éxito, ninguna sesión
    ni `Set-Cookie`, `instance` sin consulta e idioma fijo por la cadena real; **desde la rama local
    `wip/web-edge-security-chains-full`** pasan aquí las pruebas que 2.1b movió, ver la nota fechada del final:
    `PublicEndpointsTest.java`, `SessionCounter.java` y `assertBaseSecurityHeaders` del arnés, y los cinco
    métodos de sesión, cabeceras, consulta e idioma de `AdminSecurityChainTest`); ampliar `OpenApiProcess.java`
    (`start(String)` y `send`) y la aserción de tipos del trabajador en `ProcessBeanIsolationTest.java` (cero
    `SecurityFilterChain`, cero filtros, cero beans de `org.springframework.security`). **Rojos esperados:** el
    trabajador recibe los 3 beans inertes de P1.
  - **VERDE.** Crear `apps/api/app/src/main/java/com/confia/shared/web/request/RequestContextFilter.java`
    (UUID del servidor en el atributo `ProblemResponses.REQUEST_ID_ATTRIBUTE` y en el MDC, último recurso
    `500`; orden `HIGHEST_PRECEDENCE + 10`) y su bean `serverRequestContextFilter` en `WebEdgeConfiguration.java`
    (el nombre evita chocar con `requestContextFilter` de Spring). Editar `WorkerApplication.java` (exclusión de `SecurityAutoConfiguration` y
    `UserDetailsServiceAutoConfiguration`, por clase) y `ProcessBeanPolicy.java` (el trabajador prohíbe
    `org.springframework.security` y `org.springframework.boot.security`); cada línea primero en rojo.
  - **Demostraciones deliberadas.** Hacer que `RequestContextFilter` reutilice el `X-Request-Id` del cliente
    pone en rojo `RequestContextFilterTest`; quitar `ProblemAuthenticationEntryPoint` de la cadena hace fallar
    `PortalSecurityChainTest` y `RequestContextFilterChainTest`; ambas se revierten.
  - **Cierre.** `./mvnw verify` completo. Commit:
    `test(web): prove the portal and worker have no open route or security bean`. — Requisitos de `web-edge`
    de la cadena del portal, el trabajador sin borde, la ausencia de estado, la respuesta de la cadena con
    identificador y el idioma; requisito de `build-integrity` «El contexto del trabajador…»

- [x] 2.2a **PR 6a `container-rejections`: lo que Tomcat rechaza antes de los filtros y la prueba del acoplamiento
  de springdoc (decisiones 7 y 8).**
  - **ROJO.** Crear en `apps/api/app/src/test/java/com/confia/bootstrap/`
    `ContainerRejectionsTest.java` (procesos administrativo y portal reales, con su configuración de producción:
    para `/x%2f`, `/x%00` y `TRACE` exige `application/problem+json`, las cinco cabeceras base con sus valores
    exactos y **ninguna** cabecera `Server` ni `X-Powered-By` (se afirma su ausencia, no se supone); que el cuerpo
    no repite la ruta rechazada ni nada del contenedor (`instance` es `/`); que el `Allow` del 405 se conserva; y
    que una línea de petición de 70 000 caracteres recibe un solo estado en el cuerpo y en la respuesta),
    `ProductionEdgeDefaultsTest.java` (S6: producción trae springdoc apagado y `spring.mvc.log-request-details`
    falso; `SPRINGDOC_API_DOCS_ENABLED=true` abre las dos entradas de documentación de `PublicEndpoints`; con
    `--springdoc.api-docs.enabled=true` el documento responde `200` sin credencial en ambos procesos y todo lo
    demás sigue en `401`, de modo que el acoplamiento queda fijado como comportamiento previsto, ADR-0013) y
    `apps/api/app/src/test/java/com/confia/shared/web/ProblemErrorReportValveTest.java` (qué código responde a
    cada estado que informa el contenedor); ampliar `ProblemCodeTest.java` con `method-not-allowed` (405) y
    añadir a `OpenApiProcess.java` `context()`, `port()` y `startWithArguments`. **Rojo esperado:**
    `ContainerRejectionsTest` falla con `Expecting actual: "text/html;charset=utf-8" to start with:
    "application/problem+json"` (la página HTML del contenedor); el resto no compila sin la producción.
  - **VERDE.** Crear
    `apps/api/app/src/main/java/com/confia/shared/web/edge/ProblemErrorReportValve.java` (subclase de
    `ErrorReportValve` que escribe Problem Details con `ProblemResponses.writeWithoutRequestPath`, aplica
    `SecurityHeadersFilter.apply` y conserva las cabeceras que el contenedor ya puso) e instalarla con un
    `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` en `WebEdgeConfiguration.java`; añadir
    `ProblemCode.METHOD_NOT_ALLOWED` (`method-not-allowed`, 405) con su título y detalle es-HN en
    `problems.properties`; `ProblemResponses.writeWithoutRequestPath` (`instance` es `/`: el contenedor se negó a
    decodificar la ruta y no se repite); `SecurityHeadersFilter.apply` estático (los valores siguen teniendo un
    solo dueño); y en `application.yml` `spring.mvc.log-request-details: false`. La válvula mapea por
    catálogo: 401 a `authentication-required`, 403 a `forbidden`, 405 a `method-not-allowed`, todo otro 4xx a
    `400 validation-failed` y todo 5xx a `500 internal-error`, de modo que el `status` del cuerpo siempre es el de
    la respuesta (RFC 9457), y deja un evento `INFO` fijo con el estado original, el método y el `traceId`, sin
    ruta, consulta, cabeceras ni excepción (decisión del propietario; nota fechada de `design.md`).
  - **Demostraciones deliberadas.** Un personalizador que no instala la válvula pone en rojo
    `ContainerRejectionsTest` (`text/html` frente a `application/problem+json`); una válvula que no aplica las
    cabeceras base la pone en rojo por las cabeceras; se revierten.
  - **Cierre.** `./mvnw verify` completo. Commit: `feat(web): answer container rejections with Problem Details
    and the base security headers`. — Requisito de `web-edge` «Cabeceras de seguridad base» (respuesta de error)
    para lo que el contenedor rechaza; catálogo de códigos (`405`); escenarios de documentación con `prod` y
    `local` por el acoplamiento de `springdoc.api-docs.enabled` (S6 de la revisión de 2.1b)

  - **Nota fechada 2026-10-04 (heredada de 2.1c): lo que Tomcat responde antes de los filtros.** Esta tarea es la
    dueña de la brecha: `/x%2f`, `/x%00` y `TRACE` los rechaza Tomcat antes de la cadena de filtros y reciben su
    página HTML (400 y 405), sin Problem Details y sin las cabeceras base; la prueba de 2.1c solo afirma el
    estado y que ningún controlador corre. El sondeo de 2.1c no observó la cabecera `Server` en esas respuestas
    (llegaron `connection`, `content-language`, `content-length`, `content-type` y `date`, y `Allow` en el 405),
    pero esta tarea debe afirmarlo. Debe añadir al ROJO una prueba por los procesos reales que exija, para esas tres
    peticiones, `application/problem+json`, las cinco cabeceras base y ninguna cabecera `Server` ni `X-Powered-By`,
    y al VERDE la configuración de Tomcat (válvula o página de error del contenedor) que lo cumpla.

- [x] 2.2b **PR 6b `edge-gates`: lista blanca cerrada, mapa de rutas y registros sin secretos
  (decisiones 21 y 22).**
  - **ROJO.** Crear `apps/api/app/src/test/java/com/confia/bootstrap/RegisteredRoutes.java` (enumera
    `RequestMappingInfoHandlerMapping`, `RouterFunctionMapping` y `AbstractUrlHandlerMapping`),
    `PublicRouteAllowListTest.java` (administración y portal, perfil por omisión y `local`, petición
    anónima a cada ruta y, como control negativo, las rutas del arnés que su cadena permite sin que estén en
    la lista real, que hacen fallar la comprobación nombrando la ruta; incluye la aserción de cero rutas de
    producción en administración y portal), `PortalRouteMapSnapshotTest.java` (con control negativo permanente
    sobre una copia alterada en un directorio temporal; nunca sobrescribe, y
    `-Dconfia.routes.update=true` aun así falla) y
    `apps/api/app/src/test/java/com/confia/shared/web/request/SensitiveDataLoggingTest.java`
    (`ListAppender` en la raíz con la raíz y los prefijos protegidos en `TRACE`, `Authorization: Bearer
    SECRETO-A`, `Cookie: sid=SECRETO-B` y un cuerpo con `SECRETO-C` a tres rutas, aceptada, denegada y
    con `500`; control negativo con un evento registrado a propósito; ausencia de
    `generated security password`); ampliar `HarnessProcess.java` con envío con cuerpo, contexto y puerto.
    **Rojos esperados:** `PortalRouteMapSnapshotTest` falla por instantánea ausente; `SensitiveDataLoggingTest`
    falla porque Tomcat registra las cabeceras y la cookie en `DEBUG`.
  - **VERDE.** Crear `apps/api/routes/portal.routes.json` a mano con
    `{"process": "portal", "routes": []}`; crear
    `apps/api/app/src/main/java/com/confia/shared/web/request/SensitiveLogGuard.java` (`TurboFilter`
    de Logback que deniega `DEBUG` y `TRACE` de los cinco prefijos de la decisión 22, ampliada con
    `org.apache.tomcat.util.http`) instalado por un bean de `WebEdgeConfiguration.java` al refrescar el contexto.
  - **Demostraciones deliberadas.** Un controlador temporal en el portal rompe
    `PortalRouteMapSnapshotTest` nombrando la ruta; desactivar el bean de `SensitiveLogGuard` rompe
    `SensitiveDataLoggingTest`; se revierten.
  - **Cierre.** `./mvnw verify` completo. Commits:
    `test(web): enforce a closed public route allow-list and a portal route map snapshot` y
    `feat(web): block debug logging of request headers and bodies`. — Requisitos de `web-edge`
    «La lista blanca pública es cerrada…», «La cadena del portal deniega toda ruta», «Los registros no
    contienen cabeceras…» y «Ausencia de autenticación por credencial…» (ninguna ruta de producción);
    requisito de `build-integrity` «Instantánea aprobada del mapa de rutas del portal»

- [x] 2.3a **PR 7a `client-address`: direcciones de cliente y rangos CIDR (decisiones 12 y 15, la parte de tipos).**
  - **ROJO.** Crear en `apps/api/app/src/test/java/com/confia/shared/security/`
    `ClientAddressPropertiesTest.java` (jqwik con una referencia con `BigInteger`: mismo /64 equivale a la
    misma clave, IPv4 completa, mapeada igual a IPv4, zona ignorada, texto canónico, y un nombre de host, una
    forma abreviada o con corchetes se rechaza y nunca se resuelve por DNS) y, en `.../shared/web/request/`,
    `CidrBlockPropertiesTest.java` (jqwik contra una referencia con `BigInteger`, ejemplos de rango y entradas
    inválidas). Se llaman `*PropertiesTest` porque Surefire solo ejecuta `*Test`. **Rojo esperado:** clases
    inexistentes (error de compilación).
  - **VERDE.** Crear `apps/api/app/src/main/java/com/confia/shared/security/{ClientAddress,ClientKey}.java` y
    `.../shared/web/request/CidrBlock.java` (privada al paquete). `ClientAddress.parseLiteral` exige cuatro partes
    decimales sin ceros a la izquierda para IPv4 y rechaza los corchetes, y solo entonces delega en
    `InetAddress.ofLiteral`, que no resuelve nombres. `IdempotencyScopeExclusionInventoryTest` (a) pasa a una lista de permitidos por nombre
    completo (`ClientAddress` y `ClientKey`) en lugar de prohibir todo `shared.security`: `CidrBlock`, una clase `web`, depende ya de `ClientAddress` y la prueba
    fallaba (la brecha «con destino: cambio 7»).
  - **Demostración deliberada.** Hacer que `CidrBlock.contains` ignore los bits parciales del último byte, o que
    `rateLimitKey` use los 16 bytes de una IPv6, rompe la propiedad respectiva; se revierte.
  - **Cierre.** `./mvnw verify` completo. Commit: `feat(web): parse client addresses and CIDR ranges without
    resolving names`. — Requisito de `web-edge` «Las direcciones IPv6 se agrupan por /64»

- [x] 2.3b **PR 7b `trusted-proxy-resolution`: resolución de la IP del cliente y propiedad de proxies de confianza
  (decisión 12).**
  - **ROJO.** Crear en `.../shared/web/request/` `ClientAddressResolverTest.java` (los ocho escenarios de la
    especificación como ejemplos, los límites de 32 entradas y 1 024 caracteres, la propiedad «con origen no
    confiable el resultado es siempre `remote`» y una propiedad con una referencia de aritmética entera para el
    origen confiable) y `WebEdgePropertiesTest.java` (ausente, CIDR, inválida con mensaje que nombra
    `confia.web.trusted-proxies[i]`, variable de entorno `CONFIA_WEB_TRUSTEDPROXIES` con
    `SystemEnvironmentPropertySource` y `Binder`, ningún archivo de configuración del repositorio fija una lista y
    `server.forward-headers-strategy: none`). **Rojo esperado:** clases inexistentes.
  - **VERDE.** Crear `.../shared/web/request/{TrustedProxies,ClientAddressResolver,WebEdgeProperties}.java`.
    `application.yml`: `server.forward-headers-strategy: none` y `confia.web.user-agent-max-length`. Ningún archivo
    del repositorio fija un valor de `confia.web.trusted-proxies`, y una prueba recorre el repositorio para exigirlo.
    Un rango con bits de host (`10.0.0.5/8`) detiene el arranque (seguimiento S-2 de 2.3a; nota fechada de
    `design.md`; `CidrBlock.parseWithoutHostBits`). Nota fechada en
    `docs/05-infraestructura-y-despliegue.md` con `CONFIA_WEB_TRUSTEDPROXIES` (vacía por omisión).
  - **Demostración deliberada.** Hacer que el resolvedor confíe siempre en `X-Forwarded-For` rompe la propiedad de
    origen no confiable; se revierte.
  - **Cierre.** `./mvnw verify` completo. Commit: `feat(web): resolve the client address only through trusted
    proxies`. — Requisitos de `web-edge` «La IP del cliente se obtiene solo a través de proxies de confianza» y
    «La lista de proxies de confianza es una propiedad por entorno, vacía por defecto»

- [x] 2.3c **PR 7c `request-origin-filter`: identificador, agente de usuario y origen ligado a la petición
  (decisión 11).**
  - **ROJO.** Ampliar `RequestContextFilterTest.java` (origen con el identificador del servidor y la dirección de
    la conexión, cabecera ignorada sin proxies y obedecida con ellos, agente ausente y en el límite, 100 peticiones
    concurrentes con `CyclicBarrier` que dan 100 identificadores, direcciones y agentes propios) y
    `RequestContextFilterUnitTest.java` (origen ligado solo durante la cadena, caracteres de control sustituidos,
    corte sin partir un par sustituto, dirección desconocida), el arnés (`/test/origin`, propiedades por línea de
    comandos) y las dos pruebas existentes que dependen de él. **Rojo esperado:** `RequestOrigin` inexistente.
  - **VERDE.** Crear `shared/security/RequestOrigin.java`; ampliar `RequestContextFilter.java` (agente truncado a
    `confia.web.user-agent-max-length`, con los caracteres de control sustituidos, y
    `ScopedValue.where(RequestOrigin.CURRENT, origin)`) y `WebEdgeConfiguration.java` (`@EnableConfigurationProperties`).
    `PublicRouteAllowListTest` pasa a esperar la ruta del arnés, y se añade `RequestOrigin` a la lista de permitidos de
    `IdempotencyScopeExclusionInventoryTest` (una sola línea).
  - **Demostración deliberada.** Reutilizar un valor global en lugar de `ScopedValue`, o no sustituir los
    caracteres de control, rompe las pruebas respectivas; se revierte.
  - **Cierre.** `./mvnw verify` completo. Commit: `feat(web): bind the request origin for the whole request`. —
    Requisitos de `web-edge` «El identificador de petición lo genera el servidor» y «El agente de usuario se
    captura acotado»

- [x] 2.3d **PR 7d `audit-origin`: origen de la petición en la bitácora (decisión 13).**
  - **ROJO.** Crear `.../shared/audit/RequestOriginAuditLogWriterTest.java` (completa solo lo nulo, un valor del
    llamador gana, fuera de una petición el asiento pasa intacto) y `RequestOriginAuditIT.java` (arnés con base de
    datos: el asiento durante una petición lleva IP y agente, fuera de petición ambos `null`, 50 peticiones
    concurrentes con `CyclicBarrier` sin cruces). **Rojos esperados:** clase inexistente, después `source_ip`
    nulo y, con la envoltura sin la línea de política, `not in the allow-list` nombrando `com.confia.shared.audit`.
  - **VERDE.** Crear `.../shared/audit/RequestOriginAuditLogWriter.java` (decorador), envolver el escritor en
    `SharedPlatformConfiguration.java`, añadir `com.confia.shared.audit` a la lista de administración de
    `ProcessBeanPolicy` (la línea que el PR 1 difirió) y ajustar el Javadoc de `AuditEntry`.
  - **Demostración deliberada.** Quitar la envoltura del escritor rompe `RequestOriginAuditIT`; se revierte.
  - **Cierre.** `./mvnw verify` completo (la IT corre con Failsafe y Docker). Commit: `feat(audit): record the
    request origin on audit entries`. — Requisito de `web-edge` «El origen de la petición llega a la bitácora de
    auditoría»

- [x] 2.4a **PR 8a `translator-core`: traductor de Problem Details sin lista de campos (decisiones 8 a 10).**
  Primera parte de la partición de 2.4 (nota fechada del final); fuente: la rama local
  `wip/web-edge-problem-translator-full`. Medida en 641 líneas.
  - **ROJO.** Crear en `apps/api/app/src/test/java/com/confia/shared/web/`: `ProblemTranslationTest.java`
    (por la cadena real y controladores de prueba: cuerpo truncado sin el mensaje del analizador, cabecera
    o parámetro ausente y valor de tipo erróneo, cortafuegos sin lista de campos y sin bytes crudos en
    `instance`, `415`, `IllegalStateException` con mensaje sensible, `DomainException` con mensaje interno,
    `DomainException` de código desconocido, desconexión del cliente sin escritura y nivel `DEBUG`, el mismo
    `requestId` en el registro del servidor y en `traceId`, y las seis propiedades de `ProblemDetail` en
    las dos instantáneas) y `ProblemExceptionHandlerTest.java` (respuesta ya confirmada y las formas de un
    cliente que se fue); en `apps/api/app/src/test/java/com/confia/bootstrap/` `ResourceNotFoundTest.java`
    (`404 resource-not-found` bajo `/swagger-ui/**` con `local` en administración y portal, y `401` sin `local`);
    ampliar `ProblemCodeTest.java` y `ProblemCatalogCoverageTest.java` con los dos códigos; ampliar el arnés
    (`HarnessController`, `WebEdgeHarness`, `HarnessProcess`) con las rutas `validated`, `required`,
    `domain-known`, `domain-unknown` y `disconnected`; ajustar el control negativo de `PublicRouteAllowListTest`
    a ocho fugas. **Rojo esperado:** error de compilación (`ProblemExceptionHandler`) y después cuerpos por
    omisión del contenedor.
  - **VERDE.** Añadir los códigos `resource-not-found` (404) y `unsupported-media-type` (415) a `ProblemCode.java`
    y sus claves `title` y `detail` a `problems.properties`; crear `ProblemExceptionHandler.java`
    (`@RestControllerAdvice`, orden más alto; `DomainException` por `ProblemCode.ofCode`, cuerpo ilegible y
    parámetros ausentes o mal tipados, `415`, `404`, desconexión por `DisconnectedClientHelper`, todo lo demás
    `500` registrado en `ERROR` antes de escribir) y registrarlo en `WebEdgeConfiguration.java`.
  - **Demostración deliberada.** Devolver `getMessage()` en el `detail` del `500` rompe la aserción de «sin
    detalles internos»; se revierte con `cmp`.
  - **Cierre.** `OpenApiContractSnapshotTest` y `PortalRouteMapSnapshotTest` sin cambios y `./mvnw verify`
    completo. Commit: `feat(web): translate framework and domain exceptions into Problem Details`. — Requisitos
    de `web-edge` «Catálogo de códigos…» y «Las respuestas de error no exponen detalles internos»

- [x] 2.4b **PR 8b `field-violations`: lista de campos de un fallo de validación (decisión 9).**
  Segunda parte de la partición; necesita 2.4a. Fuente: la misma rama local. Medida en 417 líneas sobre 2.4a.
  - **ROJO.** Ampliar `ProblemResponsesTest.java` (`errors` omitido sin violaciones y listado con ellas, y la
    razón en kebab-case), `ProblemTranslationTest.java` (campo inválido por cuerpo, valor rechazado que no se
    repite, `ConstraintViolationException`, restricción de parámetro y de cabecera nombradas por su enlace) y
    `ProblemExceptionHandlerTest.java` (violación de método sin el nombre del método y error global sin
    códigos); ampliar el arnés con las rutas `bounded` y `constraint-violation` y las restricciones de
    `validated`; ajustar `PublicRouteAllowListTest` a diez fugas. **Rojo esperado:** error de compilación
    (`FieldViolation`) y después cuerpos sin `errors`.
  - **VERDE.** Crear `FieldViolation.java`, añadir a `ProblemBody` el miembro `errors`
    (`@JsonInclude(NON_EMPTY)`) y la sobrecarga de `ProblemResponses.write` con violaciones, añadir los tres
    traductores de validación a `ProblemExceptionHandler.java`, declarar `spring-boot-starter-validation` en
    `app/pom.xml`, y añadir a `docs/ui-ux/04-patrones-de-interaccion.md` §9 la nota fechada con la
    correspondencia de códigos y el idioma, sin reescribir su tabla.
  - **Demostración deliberada.** Quitar `@JsonInclude(NON_EMPTY)` de `errors` hace aparecer `errors: []` en toda
    respuesta y rompe las aserciones de ausencia; se revierte con `cmp`.
  - **Cierre.** `OpenApiContractSnapshotTest` sin cambios y `./mvnw verify` completo. Commit:
    `feat(web): list the violated fields of a validation failure`. — Requisito de `web-edge` «Un fallo de
    validación produce `validation-failed`…»

- [x] 2.5 **PR 9 `web-rules`: reglas de ArchUnit de la capa `web` e inventario de ausencias (decisión 20).**
  - **ROJO.** Crear en `apps/api/app/src/test/java/com/confia/architecture/`:
    `WebLayerDependencyRulesTest.java` (W1), `SharedBoundaryRulesTest.java` (W3, por raíz: producción
    `com.confia`, fixture `com.confia.architecture.fixture.sharedboundary`),
    `WebExposedTypesRuleTest.java` (W2a con `allowEmptyShould(true)` en la mitad de producción y W2b
    sin excepción sobre los registros reales `ProblemBody`, `FieldViolation` y `PublicEndpoint`) y
    `WebEdgeScopeExclusionInventoryTest.java` (con la parte de este PR: ninguna clase implementa
    `SessionValidity` ni `AuthenticationProvider`, ninguna usa `jakarta.servlet.http.Cookie`, ninguna
    regla de permiso sobre una ruta y ninguna clase `..web..` con `Institution` en el nombre, cada
    ausencia sobre un conjunto base no vacío y con su fixture de detección). **Rojo esperado:** las
    mitades de fixture fallan por conjunto vacío (ADR-0018, `archRule.failOnEmptyShould=true`) hasta
    crear los fixtures; la mitad de producción de W2a falla por conjunto vacío hasta añadir la entrada
    de `EmptyShouldExceptionInventoryTest` con dueño `session-tokens-and-web-layer` (primer endpoint) y
    su cita de ADR-0018, que `SuppressionCitesAdrTest` exige.
  - **VERDE.** Crear los fixtures permanentes `fixture/webedge/web/BadWebUsesInfrastructure`,
    `BadRecordReturningController`, `BadDomainCarryingDto`, `fixture/sharedboundary/shared/web/BadSharedUsesIdentity`
    y `fixture/sharedboundary/identity/SomeIdentityType` (bajo
    `apps/api/app/src/test/java/com/confia/architecture/`), el puerto
    `apps/api/app/src/main/java/com/confia/shared/security/SessionValidity.java` (sin implementación ni
    bean), y editar `EmptyShouldExceptionInventoryTest.java`. Confirmar que
    `SpringModulithVerificationTest` no cambia su resultado (si los fixtures nuevos aportan
    violaciones de Modulith, se registran como módulos de fixture igual que en el cambio 15) y que
    `LayeredArchitectureTest` no los ve.
  - **Demostración deliberada.** Añadir de forma temporal un acceso a `org.jooq.DSLContext` en una
    clase de `shared.web.problem` rompe la mitad de producción de W1; se revierte.
  - **Cierre.** `./mvnw verify` completo. Commit:
    `test(architecture): enforce web layer boundaries with permanent fixtures`. — Requisitos de
    `build-integrity` «Reglas de dependencia de la capa `web`…» y «Ningún tipo expuesto por la capa
    `web`…»; requisitos de `web-edge` «La vigencia de sesión es un puerto…», «Ausencia de autenticación
    por credencial…» (tokens y sesión), «Ausencia de matriz de roles…» y «Ausencia de DTO de
    instituciones…»

## Fase 3: limitador de peticiones

- [x] 3.1a **PR 10a `rate-limiter-core`: puerto, política y adaptador en memoria con las capas 1 y 2 (decisiones 15 y 16).**
  - **ROJO.** Crear en `apps/api/app/src/test/java/com/confia/shared/security/`: `MutableClock.java`
    (soporte) e `InMemoryRateLimiterTest.java` (dimensiones separadas, rechazos que no mueven la
    ventana, capas 1 y 2, bordes de ventana, tope de 1 h, fin anticipado por diez minutos sin fallos,
    la restricción que no se levanta mientras la IP sigue fallando, `Retry-After` en segundos
    redondeados hacia arriba, claves /64 y la decisión S-3, límite y umbral mayores que la primera
    reserva del anillo, política con valor no positivo, nulo o demasiado grande, y reinicio igual a
    una instancia nueva). **Rojo esperado:** clases inexistentes.
  - **VERDE.** Crear en `apps/api/app/src/main/java/com/confia/shared/security/`: `RateLimiter`,
    `RateLimitDecision` (sellada: `Admitted`, `Limited` con `retryAfterSeconds()`,
    `CapacityExhausted`), `RateLimitPolicy` (constructor compacto con todos los valores positivos que
    nombra el campo) e `InMemoryRateLimiter` (`ConcurrentHashMap` con `compute` por clave, reserva de
    hueco con CAS, recuperación perezosa con barrido a lo sumo una vez por segundo, el hueco se
    devuelve después de la eliminación, sin `@Scheduled` y sin desalojar nunca una entrada vigente;
    limitación declarada en el Javadoc). Sin consumidor ni bean: `ProcessBeanPolicy` no cambia.
  - **Demostraciones deliberadas.** Terminar la restricción cuando los fallos de la ventana bajan del
    umbral rompe tres pruebas de la capa 2. Cambiar `>=` por `>` en el descarte de admisiones **no
    rompe nada aquí**: una admisión con edad igual a la ventana da una espera de cero y se admite
    igual, así que es un mutante equivalente para las decisiones; solo lo distingue la recuperación
    de la tabla, y por eso lo matan las pruebas de borde de 3.1b (nota fechada del final). Se
    revierten con `cmp`.
  - **Cierre.** `./mvnw verify` completo; la cobertura de módulo no cae de 80 %. Commit:
    `feat(security): add the rate limiter port and its in-memory two-layer adapter`. — Requisitos de
    `web-edge` «El puerto `RateLimiter` cuenta peticiones y fallos…», «Capa 1…» (salvo la exactitud
    bajo concurrencia), «Capa 2…», «Retry-After redondeado hacia arriba», «Tamaño máximo no válido» de
    «La tabla acotada…» y «La limitación del limitador en memoria está declarada por escrito» (estado
    perdido al reiniciar)

- [x] 3.1b **PR 10b `rate-limiter-table`: fuente de tiempo monotónica, cota de la política y tabla acotada que falla cerrada (decisión 16).**
  Necesita 3.1a. Hallazgos de la revisión de seguridad: I-1, S-1 y S-2 (nota fechada del final de esta tarea).
  - **ROJO.** Crear en `apps/api/app/src/test/java/com/confia/shared/security/`:
    `InMemoryRateLimiterTableTest.java` (límite exacto de la tabla, tabla llena con una IP vigente, una
    entrada vencida libera espacio, **las dos pruebas de borde de reclamación** por petición y por
    fallo, barrido a lo sumo una vez por segundo, entradas mantenidas por un fallo, una restricción o
    un intervalo, y el fallo de una IP que la tabla llena no puede alojar) e
    `InMemoryRateLimiterTimeTest.java` (el limitador no depende de ningún reloj de pared ni lo lee; las
    capas 1 y 2 y la barrida deciden igual con el contador en cero, en negativo y cruzando el
    desbordamiento de `Long.MAX_VALUE`; el constructor de producción cuenta con `System.nanoTime`; la
    hora se lee dentro del bloqueo de la clave); convertir `MutableClock` en una fuente de nanosegundos;
    adaptar `InMemoryRateLimiterTest` al constructor nuevo y añadirle las pruebas de la cota de la
    política (S-2). **Rojo esperado:** `size()` no existe, el constructor de nanosegundos tampoco y las
    clases nuevas (error de compilación).
  - **VERDE.** En `InMemoryRateLimiter`: constructor público `(RateLimitPolicy)` con `System::nanoTime`,
    constructor de paquete `(RateLimitPolicy, LongSupplier)`, la hora leída dentro del `compute` y de la
    barrida, la comparación de la barrida como diferencia, Javadoc corregido, y `int size()` de paquete
    (solo lo usan las pruebas). En `RateLimitPolicy`: cota de 10 000 para `requestLimit` y
    `failureThreshold`.
  - **Demostraciones deliberadas.** Leer un reloj de pared rompe las pruebas de tiempo; leer la hora antes
    del `compute` rompe la prueba de orden; cambiar `>=` por `>` en el tope de la restricción, `>=` por `>`
    en el descarte de admisiones (rompe `anEntryIsReclaimedExactlyWhenItsLastRequestLeavesTheWindow`),
    barrer en cada llamada, registrar el rechazo y comparar la barrida con `<` absoluto rompen pruebas de
    esta parte; se revierten con `cmp`.
  - **Cierre.** Cobertura y `./mvnw verify` completo. Commits: `fix(security): count rate limiter time
    with a monotonic source and bound the policy counts` y `test(security): prove the bounded table fails
    closed and recovers only expired entries`. — Requisitos de `web-edge` «La tabla acotada…» (límite
    exacto, tabla llena con IP vigente y entrada vencida)
  - **Nota fechada 2026-10-05 (revisión de seguridad del limitador, hallazgos asignados a esta tarea).**
    **I-1:** `InMemoryRateLimiter` medía el tiempo con `Clock.instant()`, un reloj de pared. Un salto hacia
    adelante desbloquea a todos y uno hacia atrás detiene el barrido hasta llenar la tabla. Esta tarea
    sustituye la fuente por una monotónica (`System.nanoTime` en producción y `MutableClock` en pruebas;
    los cálculos solo usan diferencias), corrige el Javadoc que decía que el reloj «solo puede hacer
    esperar más» y prueba que el limitador no depende de ningún reloj de pared. **Debe fusionarse antes de
    3.2**, que es la primera tarea que da un consumidor al limitador; 3.2 cablea el constructor público de
    un argumento. **S-1:** la hora se lee dentro del `compute`. **S-2:** `requestLimit` y
    `failureThreshold` con cota de 10 000 (nota fechada de `design.md`).

- [x] 3.1c **PR 10c `rate-limiter-stress`: jqwik contra un modelo ingenuo y concurrencia (decisión 16).**
  Necesita 3.1b.
  - **ROJO.** Crear en `apps/api/app/src/test/java/com/confia/shared/security/`:
    `InMemoryRateLimiterPropertiesTest.java` (jqwik contra un modelo de referencia ingenuo con listas
    sin acotar, sobre secuencias de `avanzar reloj`, `tryAcquire`, `recordFailure` e intento fallido,
    con un generador de intentos fallidos que cubre la regla de no levantar la restricción mientras la IP
    sigue fallando; la tabla de dos entradas nunca las supera; `Retry-After` es el techo calculado con
    `BigDecimal`) e `InMemoryRateLimiterConcurrencyTest.java` (50 hilos y la misma IP admiten exactamente
    10; 10 fallos concurrentes restringen y 9 no; tabla de `N` con `2N` IP admite exactamente `N`;
    barrido concurrente; cada uno repetido 20 veces; **I-3:** los muestreadores leen el contador atómico
    de ranuras reservadas con `Thread.onSpinWait()` y `size()` se compara con él solo en reposo). El
    nombre de la clase de propiedades termina en `Test` para que Surefire la ejecute en `verify`.
    **Rojo esperado:** `reservedForTest()` no existe (error de compilación) y las clases nuevas.
  - **VERDE.** Añadir `int reservedForTest()` de paquete a `InMemoryRateLimiter` (el contador atómico).
  - **Demostraciones deliberadas.** Sustituir el rechazo por desalojo rompe la prueba de concurrencia de
    la tabla y la propiedad de la tabla; terminar la restricción cuando los fallos de la ventana bajan del
    umbral (regla descartada) rompe la propiedad contra el modelo, gracias al generador de intentos
    fallidos; se revierten con `cmp`. Cinco ejecuciones seguidas de la clase de concurrencia, solas.
  - **Cierre.** Cobertura y `./mvnw verify` completo. Commit: `test(security): prove the limiter against
    a naive model and under concurrency`. — Requisitos de `web-edge` «Exactitud bajo concurrencia» de la
    capa 1 y «Llenado concurrente» de «La tabla acotada…»
  - **Nota fechada 2026-10-05 (revisión de seguridad del limitador, hallazgo asignado a esta tarea).**
    **I-3:** las pruebas de concurrencia muestreaban `ConcurrentHashMap.size()`, que no es una instantánea
    atómica; se muestrea el contador de ranuras reservadas (atómico), se comprueba `size()` igual al
    contador solo en reposo y se añade `Thread.onSpinWait()` al bucle del muestreador. Esta tarea
    **debe fusionarse antes de 3.2**.

- [x] 3.2a **PR 11a `edge-rejection-codes-and-capacity-signal`: los códigos `429` y `503` y la señal de capacidad agotada (decisión 17).**
  Necesita 3.1c (y por tanto 3.1b, con el reloj monotónico, y 3.1a) y los PR 7. La tarea 3.2 original se partió en 3.2a, 3.2b y 3.2c por tamaño
  (nota fechada 2026-10-06 del final); esta parte no tiene consumidor del limitador todavía.
  - **ROJO.** Ampliar `ProblemCodeTest`, `ProblemCatalogCoverageTest`, `ProblemErrorReportValveTest` y
    `ProblemTranslationTest` (códigos `too-many-requests` con `429` y `capacity-exceeded` con `503`, sus claves
    del catálogo y la regla `forStatus` compartida por la válvula y el traductor), `ProblemExceptionHandlerTest`
    (`429` con `Retry-After` en segundos enteros y sin registro, `503` sin `Retry-After`, respuesta ya
    confirmada intacta), crear `LogRateLimitMetricsTest` en
    `apps/api/app/src/test/java/com/confia/shared/observability/metrics/` (campos exactos, ningún dato del
    cliente, una ráfaga son un evento y el siguiente trae el recuento omitido, el límite exacto de un segundo,
    una ventana por política, el constructor de producción) y ampliar `ProcessBeanIsolationTest` con la prueba
    de que solo el proceso administrativo contiene el adaptador de métricas. **Rojo esperado:** las tablas de
    códigos fallan (`Tests run: 105, Failures: 10, Errors: 4`); el resto es un error de compilación (clases
    inexistentes); y la línea `com.confia.shared.observability.metrics` de `ProcessBeanPolicy.java`, escrita
    **primero**, falla con `non-vacuous: ... must contribute a bean to the admin context`.
  - **VERDE.** En `.../shared/web/problem/`: los dos códigos y `forStatus` (`429` y `503`), `CapacityExceededException`
    y los dos manejadores de `ProblemExceptionHandler.java`; sus claves en `problems.properties` (sin números) y
    el Javadoc de `ProblemErrorReportValve.java`; en `.../shared/web/ratelimit/`: `TooManyRequestsException` y el
    puerto `RateLimitMetrics` (`capacityExhausted(String policy)`); en `.../shared/observability/metrics/`:
    `LogRateLimitMetrics` (un evento `WARN` fijo con `event`, `policy` y `suppressed`, a lo sumo uno por segundo
    por política, con `System::nanoTime`), `ObservabilityMetricsConfiguration` y `package-info.java` con
    `@NamedInterface` y su consumidor (ADR-0022), con su `@Import` en `AdminApplication.java` (ADR-0024). Nota
    fechada en `docs/07-observabilidad-y-operaciones.md` §5.4: primer puerto de métricas y su adaptador interino.
    No se añade ninguna dependencia de métricas.
  - **Demostraciones deliberadas.** Quitar la cota de un evento por segundo (emitir siempre), comparar con `>` en
    lugar de `>=` en el borde del segundo y no escribir `Retry-After` en el manejador del `429` rompen pruebas de
    esta parte; se revierten con `cmp`. (La llamada del interceptor a la métrica es de 3.2b.)
  - **Cierre.** `./mvnw verify` completo. Commits: `feat(web): add the 429 and 503 problem codes and their
    translators` y `feat(observability): report an exhausted rate limiter table as a bounded warn event`. —
    Requisito de `web-edge` «Catálogo de códigos de error y estados HTTP con productor en este cambio» (cada código tiene su estado)
  - **Añadido 2026-10-05 (revisión de seguridad del limitador, I-2; decisión del propietario).** No existe Micrometer,
    Actuator ni Prometheus en el backend; `docs/07` dice que los módulos emiten métricas por un puerto con el adaptador
    en `shared/observability/metrics`. Esta parte crea el puerto mínimo y el adaptador interino; el adaptador de
    Prometheus llega con el cambio de observabilidad sin tocar el limitador. La llamada a la métrica y su prueba por la
    cadena real son de 3.2b y 3.2c.
  - **Nota fechada 2026-10-06 (revisión de seguridad independiente de 11a, sin bloqueantes; corrección acotada hecha en esta parte).**
    **I-2:** el puerto pasa a `capacityExhausted(String policy, CapacityReason reason)` con `CapacityReason` cerrado (`TABLE_FULL`,
    `NO_ORIGIN`, `NO_ADDRESS`, `UNKNOWN_POLICY`, `LIMITER_FAILURE`) y el evento lleva un cuarto campo fijo `reason`; la cota es por
    política y causa; se corrigen el Javadoc de `CapacityExceededException` y la decisión 17, que decían que «el registro del servidor
    distingue la causa», lo que no era cierto. **S-4:** `ProblemExceptionHandler` copia `Retry-After` de un `429` del marco (con prueba;
    un `503` nunca lo lleva). **S-5:** nota fechada de `docs/07` §5.4 (`capacity-exceeded` también responde a los `503` del marco).
    **S-7:** Javadoc del adaptador (lo omitido tras el último evento se informa con el siguiente). La demostración deliberada
    «quitar el campo `reason`» rompe `LogRateLimitMetricsTest`; se revierte con `cmp`.

- [x] 3.2b **PR 11b `edge-interceptor`: `@RateLimited` y el interceptor que falla cerrado (decisión 17).**
  Necesita 3.2a (el puerto de métricas y las dos excepciones).
  - **ROJO.** Crear `apps/api/app/src/test/java/com/confia/shared/web/ratelimit/RateLimitInterceptorTest.java`
    (admitida avanza y el limitador vio al cliente de la petición; `Limited` de 200 ms da `Retry-After` 1 y de 15 s da
    15; `CapacityExhausted` da `CapacityExceededException` y una señal con solo la política; sin `RequestOrigin`, sin
    dirección, política ausente del registro y fallo inesperado del limitador son una negativa por falta de
    capacidad y nunca una admisión; un método sin la anotación o un manejador que no es `HandlerMethod` no se
    limita; la comprobación de arranque nombra cada política desconocida y el método). Ampliar
    `IdempotencyScopeExclusionInventoryTest.java` con los tipos de `shared.security` que usa el limitador en el borde
    (`RateLimiter`, `RateLimitDecision` y sus tres variantes). **Rojo esperado:** error de compilación (clases inexistentes).
  - **VERDE.** En `.../shared/web/ratelimit/`: `RateLimited`, `RateLimiterRegistry`, `RateLimitInterceptor`
    (`preHandle`; ausencia de `RequestOrigin` o error inesperado cuenta como `CapacityExhausted`, que señala a
    `RateLimitMetrics`) y `RateLimitPolicyCheck`.
  - **Demostraciones deliberadas.** Mover la llamada a `tryAcquire` a `postHandle` rompe las pruebas de esta parte, porque
    el interceptor deja de lanzar en `preHandle` (el controlador se invocaría ante un rechazo); quitar la llamada
    `metrics.capacityExhausted(...)` rompe las pruebas de la señal. Ambas se prueban aquí por la unidad, que es donde
    vive el sitio de la llamada, y se repiten por la cadena real en 3.2c; se revierten con `cmp`.
  - **Cierre.** `./mvnw verify` completo. Commit: `feat(web): ask the limiter before a rate limited controller runs`. —
    Requisitos de `web-edge` «El rechazo por límite responde `429`…» (redondeo y capa 1)
  - **Nota fechada 2026-10-06 (revisión de seguridad independiente del árbol completo de 3.2; se construye en esta parte).**
    **I-1:** en `preHandle`, devolver `true` para `DispatcherType.ASYNC` (el despacho `REQUEST` ya se evaluó), con una prueba,
    y una nota en el Javadoc de `RateLimited` (o una regla) que prohíba anotar manejadores asíncronos hasta que tengan su prueba.
    **Causa en cada camino:** todo camino que falla cerrado pasa su `CapacityReason` al puerto (`NO_ORIGIN`, `NO_ADDRESS`,
    `UNKNOWN_POLICY`, `LIMITER_FAILURE`, `TABLE_FULL` para `CapacityExhausted`); una excepción inesperada del limitador se registra en
    `ERROR` con **solo la clase** de la excepción, acotada, nunca su mensaje ni una dirección. La fuente de respaldo
    `wip/web-edge-rate-limiter-edge-full` llama todavía a la firma de un argumento y se adapta al construirla.
    **S-1:** la llamada a la métrica va dentro de `try`/`catch`, de modo que un adaptador que falla nunca escape como un `500` (la
    respuesta sigue siendo `503`). **S-2:** el Javadoc dice «método, no clase» (`@RateLimited` solo anota métodos).
    **S-3:** una prueba de `HEAD` sobre un manejador limitado (limita igual que `GET`). Las demostraciones deliberadas de esta
    parte incluyen quitar la causa del camino de `UNKNOWN_POLICY`.
  - **Nota fechada 2026-10-06 (hecho en 3.2b; S-3 pasa a 3.2c).** Construido: I-1 (`ASYNC` pasa; prueba y Javadoc de `RateLimited`;
    la demostración de quitar la comprobación rompe `anAsyncDispatchIsNotEvaluatedAgain...`), la causa en cada camino (`NO_ORIGIN`,
    `NO_ADDRESS`, `UNKNOWN_POLICY`, `LIMITER_FAILURE`, `TABLE_FULL`), el registro `ERROR` con solo la clase de la excepción y acotado a uno
    por segundo, S-1 (la métrica dentro de `try`/`catch`, con su propio `ERROR` acotado y sin mensaje) y S-2. **S-3 no se hace aquí:** que
    Spring resuelva `HEAD` al manejador de `GET` es un hecho del mapeo real de manejadores, y una prueba de unidad que entregue una petición
    `HEAD` al interceptor solo lo supondría. Se prueba por la cadena real en 3.2c (`RateLimitEdgeTest`: `HEAD` sobre el manejador limitado
    limita igual que `GET`).

- [x] 3.2c **PR 11c `edge-throttling-wiring`: configuración, política `admin-login` y borde completo (decisión 17).**
  Necesita 3.2b.
  - **ROJO.** Crear `apps/api/app/src/test/java/com/confia/shared/web/ratelimit/RateLimitEdgeTest.java` (por la
    cadena real con un controlador de prueba anotado: `429` con `Retry-After` en segundos enteros, sin límite ni
    cupo en la respuesta, `503 capacity-exceeded` sin `Retry-After` con la tabla llena, cero invocaciones del
    controlador y del caso de uso de prueba ante un rechazo, el evento de la señal con campos exactos, política
    desconocida que impide el arranque nombrándola, valores por omisión, cambio de configuración y valor no positivo
    con mensaje que nombra la propiedad) y `RateLimitPropertiesTest.java`; crear en el arnés `LimitedController` y
    `UnknownPolicyController` y ampliar `Calls` y `WebEdgeHarness`; ampliar `ProcessBeanIsolationTest.java` (solo el
    proceso administrativo contiene el interceptor, el registro y las propiedades) y la línea
    `com.confia.shared.web.ratelimit` de `ProcessBeanPolicy.java` (primero en rojo). Ampliar `IdempotencyScopeExclusionInventoryTest.java` con `RateLimitPolicy` e `InMemoryRateLimiter`. **Rojo esperado:** error de
    compilación (`ThrottlingConfiguration` y `RateLimitProperties` no existen) y la línea de política sin bean.
  - **VERDE.** Crear `RateLimitProperties` (registro enlazado por constructor, prefijo
    `confia.web.rate-limit.admin-login`, con mensajes que nombran la propiedad) y
    `.../shared/web/edge/ThrottlingConfiguration.java` (política `admin-login` registrada pero **no aplicada** a ninguna
    ruta de producción, con el constructor público del limitador) con su `@Import` en `AdminApplication.java`. Notas
    fechadas en `docs/03-seguridad.md` §4.4 y §10 con el limitador interino, sus tres limitaciones, la semántica de la
    capa 2 y, del hallazgo I-2, el riesgo residual de autodenegación y sus tres mitigaciones (`maxEntries` con holgura,
    la señal y la alerta de 3.2a y 3.2b, y el límite en el borde por /48 o /32 a cargo del cambio 11).
  - **Demostraciones deliberadas.** Las dos de 3.2b repetidas por la cadena real: `tryAcquire` en `postHandle` hace
    que el controlador se invoque ante un rechazo y rompe la prueba; quitar la llamada a la métrica rompe la prueba del
    evento. Se revierten con `cmp`.
  - **Cierre.** `./mvnw verify` completo. Commits: `feat(web): reject over-limit requests before the controller with
    429 and 503` y `docs(security): declare the interim in-memory rate limiter and its limits`. — Requisitos de
    `web-edge` «El rechazo por límite responde `429`…» (sin cupo ni límite), «El limitador se ejecuta antes del caso
    de uso», «Los límites viven en configuración…» y la nota fechada del requisito «La limitación del limitador en
    memoria…»
  - **Nota fechada 2026-10-06 (revisión de seguridad independiente del árbol completo de 3.2; se construye en esta parte).**
    **I-3:** una cota superior para `maxEntries` (por ejemplo 1 000 000) en `RateLimitPolicy`, con su prueba en
    `InMemoryRateLimiterTest` y una de sobrepasarla en `RateLimitPropertiesTest` (el mensaje nombra la propiedad). **S-6:** el limitador se
    construye en una configuración de `shared.security` que expone solo `RateLimiter`, de modo que la lista de permitidos de la capa
    web hacia `shared.security` queda en seis tipos y no incluye `InMemoryRateLimiter` (el registro de `ThrottlingConfiguration` recibe
    el puerto). La nota de `docs/03` §10 incluye el campo `reason` del evento y la lectura del `503` del marco (nota de `docs/07`).
  - **Nota fechada 2026-10-06 (S-3, movida desde 3.2b).** `RateLimitEdgeTest` añade una petición `HEAD` sobre el manejador limitado
    por la cadena real (el arnés y el mapeo de `HEAD` a `GET` de Spring): debe limitar igual que `GET` (`429` con `Retry-After`, cero
    invocaciones del controlador). No se prueba por unidad en 3.2b porque ese mapeo es del marco y la unidad solo lo supondría.

## Fase 4: materialización del retardo

- [ ] 4.1 **PR 12 `delay-materializer`: espera tras el commit en hilos virtuales (decisión 18).** Partida en 4.1a, 4.1b y 4.1c
  (nota fechada del final, 2026-10-06); el contenido de abajo es el alcance conjunto de las tres partes.
  - **ROJO.** Crear en `apps/api/app/src/test/java/com/confia/shared/web/delay/`:
    `RequiredDelayMaterializerTest.java` (llamado desde hilos virtuales: permiso antes del caso de uso,
    `503` con `P` en uso, liberación tras excepción, retardo cero y negativo sin espera, espera
    completa con temporizador falso controlado por `CountDownLatch`, el retardo se suma a un caso de
    uso lento, rechazo en hilo de plataforma, transacción activa lanza `IllegalStateException`,
    número de permisos no válido, desconexión inocua y permiso no liberado antes del plazo),
    `RequiredDelayMaterializerConcurrencyTest.java` (`2P` peticiones con `CyclicBarrier` y esperas
    retenidas por `CountDownLatch`: exactamente `P` admitidas) y
    `RequiredDelayMaterializerIT.java` (`RANDOM_PORT`, base de datos y Tomcat con pocos hilos: espera
    tras el commit, cero conexiones activas con `HikariPoolMXBean` y ningún bloqueo en `pg_locks`
    durante la espera, más esperas que hilos de plataforma con una petición sin retardo atendida, hilo
    virtual, desconexión sin entrada `ERROR`). En `apps/api/app/src/test/java/com/confia/architecture/`
    crear `BlockingWaitConfinementTest.java` (W4, con no vacuidad: el materializador sí llama a la
    espera; fixture `fixture/webedge/web/BadSleepingWebComponent`) y ampliar
    `WebEdgeScopeExclusionInventoryTest.java` (limitador y materializador sin uso fuera de su paquete
    y de `shared.web.edge`, una sola implementación de `RateLimiter` y ningún cliente de Redis en el
    camino de clases, con fixtures de detección). **Rojo esperado:** clases inexistentes.
  - **VERDE.** Crear en `apps/api/app/src/main/java/com/confia/shared/web/delay/`:
    `RequiredDelayMaterializer`, `Delayed`, `DelayTimer` y `DelayProperties` (`confia.web.delay.max-concurrent-waits`
    por omisión 200, a lo sumo la mitad de `server.tomcat.max-connections`, validado al arrancar);
    registrar el bean en `ThrottlingConfiguration.java` o en una configuración de `shared.web.edge`
    (solo administración) y la línea `com.confia.shared.web.delay` en `ProcessBeanPolicy.java`
    (primero en rojo); `application.yml`: `spring.threads.virtual.enabled: true`,
    `server.tomcat.connection-timeout: 10s`, `server.tomcat.keep-alive-timeout: 20s`,
    `server.tomcat.max-keep-alive-requests: 100` y `server.max-http-request-header-size: 16KB`.
  - **Demostraciones deliberadas.** Pedir el permiso después del caso de uso rompe «503 antes de
    procesar»; olvidar `release()` en `finally` rompe «liberación ante una excepción»; se revierten.
  - **Cierre.** `./mvnw verify` completo (con la IT). Commits: `feat(web): materialize the required
    delay after commit on virtual threads with a bounded semaphore` y `test(architecture): confine
    blocking waits to the delay materializer`. — Requisitos de `web-edge` «El retardo requerido se
    materializa después de confirmar la transacción», «El retardo se suma…», «Un semáforo acotado…»,
    «El cierre de la conexión del cliente abandona la espera», «La espera no retiene hilos…», «Ausencia
    de estado compartido del limitador…» y «Ausencia de autenticación por credencial…» (limitador y
    materializador sin uso); requisito de `build-integrity` «La espera bloqueante del retardo se
    confina al materializador»

- [x] 4.1a **PR 12a `delay-materializer-core`.** `RequiredDelayMaterializer`, `Delayed`, `DelayTimer`, `DelayProperties`,
  `RequiredDelayConfiguration` (`shared.web.edge`, `@Import` solo en `AdminApplication`), la línea `com.confia.shared.web.delay` de
  `ProcessBeanPolicy`, `application.yml`, `GatedTimer`, `RequiredDelayMaterializerTest` y `RequiredDelayMaterializerConcurrencyTest`.
  Demostraciones: permiso después del caso de uso y `release()` fuera de `finally`. Necesita 3.2c.
- [x] 4.1b **PR 12b `delay-materializer-runtime-proof`.** `RequiredDelayConfigurationTest`, la ampliación de
  `ProcessBeanIsolationTest`, la de `ProductionEdgeDefaultsTest` y `RequiredDelayMaterializerIT` (espera tras el commit, cero
  conexiones activas y ningún bloqueo durante la espera, hilo virtual y desconexión sin `ERROR`). Necesita 4.1a.
- [ ] 4.1c **PR 12c `blocking-wait-rule`.** `BlockingWaitConfinementTest` (W4, con no vacuidad y el fixture
  `BadSleepingWebComponent`) y la ampliación de `WebEdgeScopeExclusionInventoryTest` con sus fixtures. Necesita 4.1b.
  4.1 se marca hecha cuando se fusiona 4.1c.

## Fase 5: idempotencia en el borde

- [ ] 5.1 **PR 13 `idempotency-edge`: borde HTTP de la idempotencia (decisión 19).**
  - **ROJO.** Crear `apps/api/app/src/test/java/com/confia/shared/web/idempotency/IdempotencyDemoController.java`
    (solo en el árbol de pruebas, registrado únicamente en el arnés con base de datos),
    `IdempotencyEdgeIT.java` (`400` ausente, en blanco, repetida, larga, con espacio interior o fuera
    de ASCII y longitud exacta aceptada; `GET` sin cabecera; primera ejecución sin `Idempotent-Replay`
    y repetición con el mismo estado y cuerpo y con la cabecera; dos instituciones; `409` por
    `WAIT_EXHAUSTED` con sincronización determinista; colisión `23505` que reproduce la respuesta del
    ganador con `Idempotent-Replay: true` y no `409`; reintento tras `409`; `422` con carga distinta;
    carga equivalente con otro orden de campos; el controlador de demostración ausente de los
    procesos reales) y
    `apps/api/app/src/test/java/com/confia/architecture/IdempotencyNotInIdentityTest.java` (W5, con
    fixture `fixture/identity/web/BadIdempotentLoginController` y mitad de producción sin uso). Ampliar
    `ProblemCatalogCoverageTest.java` con los tres códigos de idempotencia. **Rojo esperado:** rutas
    inexistentes y clases inexistentes.
  - **VERDE.** Crear en `apps/api/app/src/main/java/com/confia/shared/web/idempotency/`:
    `IdempotentWrite`, `IdempotencyKeyInterceptor` (`preHandle`), `IdempotentRequestHandler` (endpoint
    igual a método más plantilla de ruta, carga resumida con cuerpo y variables de ruta, traducción de
    `Executed`, `Replayed`, `IdempotencyConflictException` y `IdempotencyPayloadMismatchException`) e
    `IdempotencyKeyMissingException`; en `.../shared/web/edge/IdempotencyEdgeConfiguration.java` el
    registro, con `@Import` en `AdminApplication.java` y la línea
    `com.confia.shared.web.idempotency` en `ProcessBeanPolicy.java` (primero en rojo); los códigos
    `idempotency-key-missing`, `idempotency-conflict` e `idempotency-payload-mismatch` con sus claves
    y manejadores. Notas fechadas en `docs/09-roadmap-y-fases.md` (líneas 114 y 115, entregable 6 y
    partición de la parte 4) y en `openspec/changes/foundations-plan/exploration.md` (cuarto corte y
    aceptación del limitador en memoria hasta el cambio 11).
  - **Demostración deliberada.** Responder `409` ante `23505` (en lugar de reproducir) rompe el
    escenario de colisión confirmada; se revierte. `OpenApiContractSnapshotTest` queda sin cambios.
  - **Cierre.** `./mvnw verify` completo. Commits: `feat(web): enforce the idempotency key header and
    translate idempotent outcomes at the edge` y `docs(roadmap): record the web edge partition and the
    interim limiter decisions`. — Requisitos de `web-edge` de la cabecera `Idempotency-Key`, la
    repetición, la escritura concurrente, el `422` y el controlador de demostración; requisitos de
    `build-integrity` «El mecanismo HTTP de idempotencia no se aplica…» y el requisito retirado de
    ausencia de superficie HTTP (migrado a `web-edge`)

## Fase 6: cierre

- [ ] 6.1 **Verificación completa, medición de los 24 PR y barrido de trazabilidad.** Con los 24 PR
  fusionados a `main`, ejecutar `./mvnw verify` completo en `apps/api` y registrar cobertura (global
  80 %, núcleo y `domain` 95 %) y mutación (umbral 80). Registrar en
  `openspec/changes/web-edge-foundations/apply-progress.md` la medición de cada PR con
  `git diff --numstat` **sin** `-M` y **con** `-M`, excluyendo `openspec/`, comparada con la tabla de
  líneas nominales y de peor caso; marcar los criterios de éxito de `proposal.md`; confirmar que
  `apps/api/routes/portal.routes.json` y la instantánea OpenAPI no cambiaron; y recontar los 166
  escenarios (132 de `web-edge` y 34 de `build-integrity`) contra la tabla de abajo, sin huérfanos.
  Los escenarios de documentación («nota fechada») se verifican por lectura del diff, declarada como
  revisión humana. Commit solo si se corrige algo: `chore(web): <outcome>`. — Todos los requisitos

---

## Trazabilidad: escenarios de `specs/web-edge/spec.md` (132)

| Requisito | Escenario | Tarea |
|---|---|---|
| La cadena administrativa deniega por defecto | Ruta no registrada sin credencial | 2.1b |
| | Ruta registrada fuera de la lista blanca | 2.1b |
| | La respuesta no distingue rutas existentes de inexistentes | 2.1b |
| | Principal autenticado sin permiso | 2.1b |
| | Método distinto al permitido en una ruta pública | 2.1b |
| | Variantes de la ruta no eluden la denegación | 2.1b |
| La lista blanca pública es cerrada | Una ruta pública nueva sin editar la lista | 2.2b |
| | La lista contiene solo lo declarado | 2.2b |
| | Documentación de API con perfil `prod` | 2.1b, 2.2a |
| | Documentación de API con perfil `local` | 2.1b |
| La cadena administrativa no tiene estado | Ninguna respuesta crea sesión | 2.1c |
| Cabeceras de seguridad base | Respuesta de error con las cabeceras base | 2.1a, 2.1c, 2.2a |
| | Respuesta de éxito con las cabeceras base | 2.1a, 2.1c |
| Vigencia de sesión como puerto | El puerto existe y no tiene adaptador de producción | 2.5 |
| | El puerto no arrastra a `identity` | 2.5 |
| La cadena del portal deniega toda ruta | Toda ruta del portal es denegada | 2.1c |
| | Una ruta añadida al portal sigue denegada | 2.1c, 2.2b |
| | Sin ruta pública en el portal | 2.2b |
| | Documentación del portal con perfil `local` | 2.1c |
| El trabajador no tiene cadena ni servidor web | Contexto del trabajador sin borde web | 2.1c |
| Toda respuesta de error usa Problem Details | Respuesta de error bien formada | 2.1a |
| | El identificador de traza es el de la petición | 2.1a |
| | La cadena de consulta no aparece en `instance` | 2.1a, 2.1c |
| El `type` deriva del código estable | El mismo código produce el mismo `type` | 2.1a |
| | Códigos distintos producen `type` distintos | 2.1a |
| | La denegación por falta de credencial es uniforme | 2.1b |
| Catálogo de códigos y estados HTTP | Cada código tiene su estado | 2.1a, 2.2a, 2.4a, 3.2a, 5.1 |
| | Tipo de contenido no admitido | 2.4a |
| | Recurso inexistente bajo un prefijo de documentación | 2.4a |
| | Un código desconocido no se filtra | 2.4a |
| | Los códigos de autenticación aún no existen | 2.1a |
| Las respuestas de error no exponen detalles internos | Excepción no prevista | 2.4a |
| | Excepción de dominio con mensaje interno | 2.4a |
| | El detalle técnico queda solo en el servidor | 2.4a |
| Un fallo de validación produce `validation-failed` | Campo inválido | 2.4b |
| | El valor rechazado no se repite | 2.4b |
| | Cuerpo mal formado | 2.4a |
| | `errors` es una extensión fuera del esquema del contrato | 2.4b |
| Catálogo de mensajes en español de Honduras | Código sin entrada en el catálogo | 2.1a |
| | Entrada sin código | 2.1a |
| | Idioma fijo | 2.1a, 2.1c |
| El identificador de petición lo genera el servidor | El cliente envía su propio identificador | 2.1c, 2.3c |
| | Identificadores únicos bajo concurrencia | 2.3c |
| | Respuesta de la cadena de seguridad con identificador | 2.1c |
| La IP del cliente solo por proxies de confianza | Lista vacía, cabecera ignorada | 2.3b |
| | Origen no confiable, cabecera ignorada | 2.3b |
| | Origen confiable, cadena de dos proxies | 2.3b |
| | Un cliente intenta anteponer una IP falsa | 2.3b |
| | Todas las entradas son proxies de confianza | 2.3b |
| | Entrada no válida en la cabecera | 2.3b |
| | Varias líneas de la cabecera | 2.3b |
| | IPv6 mapeada a IPv4 | 2.3b |
| Lista de proxies de confianza por entorno | Propiedad ausente | 2.3b |
| | Rango CIDR de entrada | 2.3b |
| | Entrada inválida | 2.3b |
| | Inyección por variable de entorno | 2.3b |
| El agente de usuario se captura acotado | Cabecera ausente | 2.3c |
| | Longitud en el límite | 2.3c |
| El origen llega a la bitácora de auditoría | Asiento durante una petición | 2.3d |
| | Asiento fuera de una petición | 2.3d |
| | Peticiones concurrentes con orígenes distintos | 2.3d |
| Los registros no contienen cabeceras ni cuerpos | Petición con datos sensibles | 2.2b |
| | Control negativo | 2.2b |
| El puerto `RateLimiter` cuenta peticiones y fallos | Las dimensiones no se mezclan | 3.1a |
| | Una petición rechazada no extiende la ventana | 3.1a |
| Capa 1, 10 peticiones por minuto por IP | Límite exacto | 3.1a |
| | Primera petición de una IP | 3.1a |
| | La ventana se desliza | 3.1a |
| | IP distinta, contador distinto | 3.1a |
| | Exactitud bajo concurrencia | 3.1c |
| Capa 2, retroceso por fallos por IP | El fallo nueve no activa la restricción | 3.1a |
| | El fallo diez activa la restricción | 3.1a |
| | Los fallos fuera de la ventana no cuentan | 3.1a |
| | Un fallo en el borde exacto de la ventana | 3.1a |
| | El éxito no limpia el contador de la IP | 3.1a |
| | Tope de una hora | 3.1a |
| | Diez minutos sin fallos terminan la restricción antes del tope | 3.1a |
| | La restricción no se levanta mientras la IP sigue fallando | 3.1a |
| Las direcciones IPv6 se agrupan por /64 | Dos direcciones del mismo /64 | 2.3a |
| | Direcciones de /64 contiguos | 2.3a |
| | IPv4 sin agrupar | 2.3a |
| | IPv6 mapeada a IPv4 | 2.3a |
| La tabla acotada falla cerrada al llenarse | Límite exacto de la tabla | 3.1b |
| | Tabla llena, IP con entrada vigente | 3.1b |
| | Una entrada vencida libera espacio | 3.1b |
| | Llenado concurrente | 3.1c |
| | Tamaño máximo no válido | 3.1a |
| El rechazo por límite responde `429` con `Retry-After` | Retry-After redondeado hacia arriba | 3.1a, 3.2b |
| | Retry-After de la capa 1 | 3.2b |
| | Sin cupo ni límite | 3.2c |
| El limitador se ejecuta antes del caso de uso | Petición rechazada sin efecto | 3.2c |
| Los límites viven en configuración | Cambio de configuración | 3.2c |
| | Valores por defecto | 3.2c |
| | Valor no positivo | 3.2c |
| La limitación del limitador en memoria está declarada | Estado perdido al reiniciar | 3.1a |
| | Nota fechada en la documentación | 3.2c |
| El retardo se materializa tras confirmar la transacción | La espera ocurre tras el commit | 4.1 |
| | Sin recursos retenidos durante la espera | 4.1 |
| | Retardo cero | 4.1 |
| | Retardo negativo | 4.1 |
| El retardo se suma al tiempo de procesamiento | Un caso de uso lento no reduce la espera | 4.1 |
| Un semáforo acotado limita las esperas | Permiso número P más uno | 4.1 |
| | Liberación del permiso | 4.1 |
| | Liberación ante una excepción | 4.1 |
| | Concurrencia exacta del semáforo | 4.1 |
| | Respuesta uniforme | 4.1 |
| | Número de permisos no válido | 4.1 |
| El cierre de la conexión del cliente abandona la espera | Cliente que se desconecta | 4.1 |
| | El permiso no se libera antes del plazo | 4.1 |
| | La desconexión no se registra como error | 4.1 |
| La espera no retiene hilos de plataforma | Más esperas que hilos de plataforma | 4.1 |
| | La espera corre en un hilo virtual | 4.1 |
| Cabecera `Idempotency-Key` obligatoria | Cabecera ausente | 5.1 |
| | Cabecera en blanco | 5.1 |
| | Cabecera repetida o demasiado larga | 5.1 |
| | Carácter fuera de ASCII visible | 5.1 |
| | Endpoint de solo lectura | 5.1 |
| La repetición devuelve la respuesta original con `Idempotent-Replay` | Primera ejecución y repetición | 5.1 |
| | La repetición sobre una clave de otra institución no se mezcla | 5.1 |
| Una escritura concurrente con la misma clave responde `409` | Dos peticiones concurrentes con la misma clave | 5.1 |
| | Colisión con la clave ya confirmada | 5.1 |
| | Reintento posterior | 5.1 |
| La misma clave con carga útil distinta responde `422` | Carga útil distinta | 5.1 |
| | Carga equivalente con distinto orden de campos | 5.1 |
| El controlador de demostración vive solo en pruebas | El controlador de demostración no está en los procesos reales | 5.1 |
| | El documento OpenAPI no cambia | 5.1 |
| Ausencia de autenticación por credencial y tokens | Ninguna ruta de producción en el proceso administrativo | 2.2b |
| | Ningún componente de tokens ni de sesión | 2.5 |
| | El limitador y el materializador no están aplicados a un endpoint de producción | 4.1 |
| Ausencia de matriz de roles y RBAC | Ninguna regla de permiso sobre una ruta | 2.5 |
| Ausencia de estado compartido del limitador | Un único adaptador y ninguna biblioteca de Redis | 4.1 |
| Ausencia de DTO de instituciones con tope de longitud | Ningún DTO de instituciones | 2.5 |

Subtotal `web-edge`: 132 escenarios, 132 con tarea, 0 huérfanos.

## Trazabilidad: escenarios de `specs/build-integrity/spec.md` (34)

| Requisito | Escenario | Tarea |
|---|---|---|
| El administrativo arranca sin conexión ni migración | El proceso administrativo arranca con la base de datos inalcanzable | 1.1 |
| | Portal y trabajador no tienen `DataSource` | 1.1 |
| | Las pruebas de arranque no necesitan Docker | 1.1 |
| Spring Security solo como cadena de filtros | Se añade el servidor de recursos OAuth2 | 2.1b |
| | La dependencia de Spring Security converge | 2.1b |
| Reglas de dependencia de la capa `web` | Fixture con una clase `web` que depende de `infrastructure` | 2.5 |
| | Fixture con `shared` que depende de `identity` | 2.5 |
| | Código de producción sin dependencias prohibidas | 2.5 |
| Ningún tipo expuesto por `web` es entidad ni registro de jOOQ | Fixture con un controlador que devuelve un registro de jOOQ | 2.5 |
| | Fixture con un DTO que contiene un tipo de `domain` | 2.5 |
| | La excepción de conjunto vacío está declarada y con dueño | 2.5 |
| | La regla sobre registros de `web` evalúa clases reales | 2.5 |
| La espera bloqueante se confina al materializador | Fixture con una espera fuera del materializador | 4.1 |
| | El materializador es la única clase con espera | 4.1 |
| | Se mantiene la prohibición en `identity` | 4.1 |
| Instantánea aprobada del mapa de rutas del portal | Una ruta nueva en el portal rompe la construcción | 2.2b |
| | Instantánea ausente | 2.2b |
| | Mapa idéntico a la instantánea | 2.2b |
| La idempotencia HTTP no se aplica a identidad | Fixture con un endpoint de identidad que usa la idempotencia | 5.1 |
| | Código de producción sin uso en identidad | 5.1 |
| Cada proceso registra solo beans de su lista | Un bean fuera de la lista rompe la construcción | 1.1, 1.2 |
| | Un módulo nuevo obliga a editar la lista | 1.1, 1.2 |
| | Cada proceso solo contiene beans permitidos | 1.1, 1.2 |
| | El proceso administrativo registra el cableado de producción | 1.1, 1.2 |
| Portal sin beans de otros puntos de entrada ni administrativos | El portal arrastra un punto de entrada ajeno | 1.1 |
| | El portal arrastra un módulo administrativo | 1.1, 1.2 |
| | El portal contiene solo lo suyo | 1.1, 2.1a |
| | El portal no hereda el cableado administrativo | 1.1 |
| Trabajador sin beans de otros puntos de entrada | El trabajador recibe una importación ajena | 1.1 |
| | El trabajador arranca sin servidor web y sin beans ajenos | 1.1, 2.1c |
| | El trabajador no recibe el borde web | 2.1a |
| Puntos de entrada de registro explícito | Un punto de entrada vuelve a escanear | 1.1 |
| | Los tres puntos de entrada cumplen la forma explícita | 1.1 |
| | El portal o el trabajador dejan de excluir el `DataSource` | 1.1 |

Subtotal `build-integrity`: 34 escenarios, 34 con tarea, 0 huérfanos.

**Total: 166 escenarios, 166 con tarea, 0 huérfanos.** Requisito retirado de `build-integrity`
(«Ausencia de superficie HTTP que exija la cabecera de idempotencia»): sin escenarios; lo cubre la
tarea 5.1 y su migración a `web-edge`. Los entregables sin escenario (notas fechadas de `docs/03`,
`docs/05`, `docs/09`, `docs/ui-ux` y `foundations-plan`) están cubiertos por las tareas 1.2, 2.3, 2.4,
3.2a, 3.2c y 5.1.

---

## Nota fechada 2026-10-04: ruta de `SharedPlatformConfiguration` (tarea 1.1)

Donde la tarea 1.1 nombra `apps/api/app/src/main/java/com/confia/shared/platform/` y el paquete
`com.confia.shared.platform`, la clase y su `package-info.java` viven en
`com.confia.shared.platform.infrastructure`, porque `JooqConfinedToInfrastructureTest` (ADR-0015,
regla 4) prohíbe `org.jooq` fuera de `..infrastructure..`. El prohibido nominal de portal y trabajador
sigue siendo `com.confia.shared.platform` (por prefijo). El bean `TransactionRunner` se declara en
`shared.security.TransactionRunnerConfiguration` y `com.confia.shared.audit` entra en la lista de
permitidos con el PR 5. Detalle en la nota fechada de `design.md` y en `apply-progress.md`.

## Nota fechada 2026-10-04: ruta de `IdentityConfiguration` (tarea 1.2)

Donde la tarea 1.2 nombra `apps/api/app/src/main/java/com/confia/identity/IdentityConfiguration.java` y
la lista de permitidos `com.confia.identity`, la clase y su `package-info.java` (con `@NamedInterface`)
viven en `com.confia.identity.infrastructure.wiring`, porque `JooqConfinedToInfrastructureTest`
(ADR-0015, regla 4) prohíbe `org.jooq` fuera de `..infrastructure..` y la configuración recibe un
`DSLContext`. La lista de permitidos de administración nombra `com.confia.identity.application`,
`com.confia.identity.infrastructure` y `com.confia.identity.infrastructure.wiring`, cada uno exacto por la
no vacuidad. `ConfiguredLoginInstitutionProvider` deja de repetir el valor rechazado en su mensaje y de
encadenar el analizador de UUID. Detalle en la nota fechada de `design.md` y en `apply-progress.md`.

## Nota fechada 2026-10-04: partición de la tarea 2.1 en 2.1a, 2.1b y 2.1c

El propietario aprobó partir la tarea 2.1 (PR 3 `security-chains`) en tres porque el PR verificado
midió **2 000 líneas efectivas** (1 976 adiciones y 24 eliminaciones, igual con y sin `-M`, sin
`openspec/`) frente al tope de 800 y a un pronóstico de 520 nominales y 780 en el peor caso. La costura 3b del
diseño (cabeceras e identificador de petición) no bastaba: dejaba unas 1 700 líneas. El árbol completo y
verificado se conserva en la rama local `wip/web-edge-security-chains-full`, que **nunca se publica** y es la
fuente de las tres partes.

- **2.1a `problem-details-core` (PR 3)**, sin Spring Security: Problem Details, catálogo y cabeceras base.
  Medido en 775 líneas. Un primer intento que incluía también el filtro de identificador de petición y
  `errors` midió 1 052 y se rebalanceó sin recortar nada: el filtro de contexto (con su prueba) pasa a 2.1c y
  `FieldViolation` con `errors` pasa a 2.4, que es quien los produce.
- **2.1b `security-chains` (PR 4)**: dependencia, prohibiciones, cadenas, manejadores y arnés. Estimación 600 a
  800; si la medición supera 800 se mueven pruebas y arnés a 2.1c antes del commit.
- **2.1c `portal-and-worker-chain` (PR 5)**: filtro de identificador de petición, portal, trabajador y las
  pruebas de la cadena real con cabeceras, sesión e identificador. Estimación 700 a 800.

Las tareas pasan de 12 a 14 y la cadena de PR de 11 a 13 (máximo del proyecto: 15). Desde la tarea 2.2 los
números de PR se desplazan dos puestos (PR 4 pasa a 6, PR 5 a 7, y así hasta PR 11 que pasa a 13); las notas
fechadas anteriores y `apply-progress.md` conservan la numeración vieja. La demostración deliberada de la
barrera de denegación por omisión es `anyRequest().permitAll()` (nota fechada de `design.md`). Trazabilidad:
166 escenarios, 166 con tarea, 0 huérfanos; los 33 que apuntaban a 2.1 apuntan ahora a una sola de 2.1a (14),
2.1b (11) o 2.1c (8).

## Nota fechada 2026-10-04: lo que la tarea 2.1b movió a 2.1c y una precisión sobre `denyAll()`

El primer corte de 2.1b (dependencia, prohibiciones, cadenas, manejadores, arnés y todas las pruebas de la
rama local) midió **977 líneas** efectivas (950 adiciones y 27 eliminaciones) frente al tope de 800. Sin
recortar pruebas ni comentarios se movieron a 2.1c, que es quien ya prueba la cadena real con cabeceras, sesión e
identificador, estas piezas (siguen completas en `wip/web-edge-security-chains-full`): en `AdminSecurityChainTest`
los métodos `noResponseCreatesASessionOrSetsACookie`, `anErrorResponseCarriesTheBaseSecurityHeaders`,
`aSuccessResponseCarriesTheSameBaseSecurityHeaders`, `theQueryStringNeverAppearsInTheInstance` y
`theLanguageIsFixedWhateverAcceptLanguageSays`; del arnés, `SessionCounter` y `assertBaseSecurityHeaders`; y
`PublicEndpointsTest` (prueba unitaria de la lista blanca). También se dejó fuera de 2.1b
`theFirewallRejectionCarriesNoViolationList`: con el cuerpo de 2.1a no existe `errors`, así que la aserción no
podía fallar por la causa que nombra; vuelve en 2.4, que introduce `errors`. Además `RequestContextFilter`, el
trabajador, `PortalSecurityChainTest`, `/test/boom` y `startAsPortal` del arnés siguen siendo de 2.1c. Resultado
de 2.1b: **791**.

Trazabilidad: los escenarios de cabeceras (error y éxito), de la cadena de consulta en `instance` y del idioma
fijo conservan su prueba unitaria de 2.1a y suman 2.1c para la prueba por la cadena real (filas actualizadas
arriba); «Ninguna respuesta crea sesión» ya era de 2.1c. Los escenarios «Documentación de API con perfil `prod`» y
«con perfil `local`» siguen en 2.1b: los prueban `OpenApiExposureByProfileTest` (procesos reales, `401` con
Problem Details y documento `200`) y `AdminSecurityChainTest` (sin springdoc); la prueba unitaria de
`PublicEndpoints` llega con 2.1c. Ningún escenario queda huérfano: 166 escenarios, 166 con tarea.

Precisión sobre `anyRequest().denyAll()` (complementa la nota fechada de `design.md`): quitar la línea no rompe
`AdminSecurityChainTest` porque el arnés siempre tiene una ruta pública, pero **sí rompe el arranque** de los
procesos reales cuando la lista blanca está vacía (perfil `prod` o desconocido): sin ninguna regla Spring Security
lanza `IllegalStateException: At least one mapping is required`, y `OpenApiExposureByProfileTest` falla con
`BeanCreation Error creating bean with name 'adminSecurityFilterChain'`. La regla final es, pues, obligatoria para
la lista vacía y no solo explícita.

## Nota fechada 2026-10-04: lo que la tarea 2.1c hizo distinto de lo previsto

- **Archivos de prueba.** El texto de la tarea preveía `RequestContextFilterChainTest` y
  `AdminSecurityHeadersAndSessionTest`. No se crearon: la rama verificada ya cubría esas pruebas con
  `RequestContextFilterTest` (por la cadena real: el identificador del servidor ignora `X-Request-Id` y
  `traceparent`, es distinto en cada petición, coincide con el que ve el controlador y con el MDC, y el último
  recurso no filtra nada) y con los cinco métodos devueltos a `AdminSecurityChainTest`. Tras la revisión
  independiente se añadió `RequestContextFilterUnitTest` (sin contexto de Spring: MDC y atributo, relanzado con
  la respuesta confirmada, orden `HIGHEST_PRECEDENCE + 10` y el último recurso), porque ninguna prueba por la
  cadena podía fallar por esas causas. El propietario no aprobó de forma explícita omitir los dos archivos; el
  contenido de cada uno está asignado en `apply-progress.md`. Se añadió `StatelessChainTest` (condición de
  fusión I1 de la revisión de 2.1b).
- **Ausencia de estado.** `SessionCreationPolicy.STATELESS` y `requestCache.disable` se **respaldan entre sí**:
  quitar solo una no cambia ninguna respuesta (`STATELESS` instala un `NullRequestCache`; sin `STATELESS` la
  caché deshabilitada tampoco guarda la petición), y las pruebas de sesión y de `Set-Cookie` solo fallan cuando
  se quitan las dos. Por eso `StatelessChainTest` inspecciona la cadena real: ningún `RequestCacheAwareFilter` y
  un `SecurityContextHolderFilter` cuyo repositorio es exactamente `RequestAttributeSecurityContextRepository`.
  Con eso, quitar cualquiera de las dos líneas pone en rojo una prueba. Detalle en `apply-progress.md`.
- **S2 de la revisión de 2.1b.** Entraron `HEAD`, `OPTIONS` y `TRACE` y las variantes `//x`, `/x%2f`, `/x%2e`,
  `/x%00` y `/%78`. Hallazgo: `/x%2f`, `/x%00` y `TRACE` los rechaza **Tomcat antes de cualquier filtro** (400 y
  405 con su propia página HTML): son seguros (ningún controlador corre) pero no son Problem Details y no llevan
  las cabeceras base. Queda registrado como brecha para 2.2 o un cambio posterior (ver `apply-progress.md`).

## Nota fechada 2026-10-04: la tarea 2.2 se parte en 2.2a y 2.2b

El propietario aprobó partir la tarea 2.2 (PR 6 `edge-gates`) en dos porque la tarea verificada completa midió
**1 111 líneas efectivas** (1 106 adiciones y 5 eliminaciones, igual con y sin `-M`, sin `openspec/`) frente al
tope de 800: a lo previsto (cinco archivos de prueba y la guarda de registros) se sumaron la brecha de Tomcat
heredada de 2.1c (válvula, código `method-not-allowed`, prueba por los procesos reales) y la prueba S6. El
propietario concedió además una excepción al tope de 15 tareas. El árbol completo y verificado se conserva en la
rama local `wip/web-edge-edge-gates-full`, que **nunca se publica** y es la fuente de las dos partes.

- **2.2a `container-rejections` (PR 6a)**: la válvula del informe de errores de Tomcat, el código
  `method-not-allowed`, `ContainerRejectionsTest`, `ProblemErrorReportValveTest`, `ProductionEdgeDefaultsTest` (S6)
  y la línea `spring.mvc.log-request-details`. Medido en unas 395 líneas.
- **2.2b `edge-gates` (PR 6b)**: `RegisteredRoutes`, `PublicRouteAllowListTest`, `PortalRouteMapSnapshotTest`,
  `portal.routes.json`, `SensitiveLogGuard` y `SensitiveDataLoggingTest`. Medido en unas 716 líneas en el árbol
  completo.

Numeración: los dos PR se llaman 6a y 6b y **los PR 7 a 13 conservan su número**, para no reescribir las
referencias de las demás tareas. Conteo: 15 tareas (14 de PR más la de cierre 6.1) y 14 PR, es decir, dentro del
máximo de 15; la excepción concedida no se ejerce con este conteo. Trazabilidad: 166 escenarios, 166 con tarea,
0 huérfanos; los que apuntaban a 2.2 (diez filas, de la lista blanca, el portal, los registros, la ausencia de
rutas de producción y la instantánea) apuntan ahora a 2.2b, y 2.2a suma cobertura a cuatro filas que ya tenían
tarea (documentación con `prod` y con `local`, cabeceras en respuesta de error, y el estado de cada código). Las
notas fechadas anteriores y `apply-progress.md` conservan los nombres viejos.

## Nota fechada 2026-10-05: la tarea 2.3 se parte en 2.3a, 2.3b, 2.3c y 2.3d

El propietario aprobó partir la tarea 2.3 (PR 7 `request-origin`) en cuatro porque la tarea, implementada y verificada
completa (`./mvnw verify`: Surefire 186 + 712, Failsafe 228), midió **1 869 líneas efectivas** (1 831 adiciones y 38
eliminaciones, igual con y sin `-M`, sin `openspec/`) frente al tope de 800 y a un pronóstico de unas 500. El
propietario concedió además una **excepción al tope de 15 tareas**: el cambio pasa a **18 tareas** (17 de PR más la de
cierre 6.1) y **17 PR**. El árbol completo y verificado se conserva en la rama **local** `wip/web-edge-request-origin-full`
(commit `9e8b52f`, 31 archivos), que nunca se publica y es la fuente de las cuatro partes.

| Parte | Contenido | Líneas medidas en el árbol completo |
|---|---|---|
| 2.3a `client-address` | `ClientAddress`, `ClientKey`, `CidrBlock`, `ClientAddressPropertiesTest`, `CidrBlockPropertiesTest`, ajuste de `IdempotencyScopeExclusionInventoryTest` | 513 |
| 2.3b `trusted-proxy-resolution` | `TrustedProxies`, `ClientAddressResolver`, `WebEdgeProperties`, `ClientAddressResolverTest`, `WebEdgePropertiesTest`, `application.yml`, nota de `docs/05` | 592 |
| 2.3c `request-origin-filter` | `RequestOrigin`, `RequestContextFilter`, `WebEdgeConfiguration`, ampliación del arnés y de las dos pruebas del filtro, ajuste de `PublicRouteAllowListTest` | 381 |
| 2.3d `audit-origin` | `RequestOriginAuditLogWriter`, línea de `ProcessBeanPolicy`, envoltura en `SharedPlatformConfiguration`, Javadoc de `AuditEntry`, `RequestOriginAuditIT`, prueba del decorador | 383 |

Numeración: los PR se llaman 7a a 7d y **los PR 8 a 13 conservan su número**. Orden: 2.3a, 2.3b, 2.3c y 2.3d, cada una
desde `main` actualizado tras fusionar la anterior. Dependencias: 2.3b necesita 2.3a (`ClientAddress`, `CidrBlock`);
2.3c necesita 2.3b (`WebEdgeProperties`) y el filtro de 2.1c; 2.3d necesita 2.3c (`RequestOrigin`). Cada parte debe
estar en verde por sí sola: las pruebas de 2.3c que dependen del arnés ampliado, y el ajuste de `PublicRouteAllowListTest` viajan en 2.3c; el de
`IdempotencyScopeExclusionInventoryTest` viaja en 2.3a, porque `CidrBlock` ya depende de `shared.security` (hallado al
verificar 2.3a: la prueba falló y se movió). Trazabilidad: 166 escenarios, 166 con tarea, 0 huérfanos; los 23 que apuntaban a 2.3 apuntan ahora a
una sola parte (2.3a: cuatro de IPv6 y /64; 2.3b: doce de resolución y de propiedad; 2.3c: cuatro, del identificador y
del agente de usuario; 2.3d: tres, de la bitácora).

## Nota fechada 2026-10-05: re-pronóstico de las tareas restantes

Las mediciones reales superaron el pronóstico en cada tarea con arneses y pruebas de concurrencia o de propiedades, y el
exceso está casi todo en pruebas:

| Tarea | Pronóstico nominal | Medido |
|---|---|---|
| 2.1 (completa, antes de partirse) | ~520 | 2 000 |
| 2.2 (completa, antes de partirse) | ~420 | 1 111 |
| 2.3 (completa, antes de partirse) | ~500 | 1 869 (1 302 de pruebas, 531 de producción) |

La proporción histórica es de 2,2 a 3,7 veces el nominal en esas tres. Estimación honesta de lo que queda, con la
proporción de pruebas observada (más de 2 de cada 3 líneas) y sin pre-partir ninguna: **el propietario decide por tarea**
cuando se mida.

| Tarea | Nominal | Nueva estimación | ¿Probable partición? |
|---|---|---|---|
| 2.4 `problem-translator` | ~380 | 700 a 1 000 | Sí: diez escenarios por la cadena real, el traductor, los dos códigos y el catálogo; costura natural entre el traductor y la prueba por la cadena |
| 2.5 `web-rules` | ~380 | 700 a 1 000 | Sí: cuatro reglas de ArchUnit con sus fixtures y el inventario de ausencias; costura entre W1 y W3, y W2a, W2b y el inventario |
| 3.1a, 3.1b y 3.1c `rate-limiter-core` | ~500 | unas 2 000 (983 + 596 medidas + unas 421 estimadas) | Ya partida por decisión del propietario (notas fechadas del final): 3.1a puerto, política, adaptador y pruebas de las capas; 3.1b fuente de tiempo monotónica, cota de la política y tabla acotada; 3.1c jqwik y concurrencia |
| 3.2 `rate-limiter-edge` | ~320 | 500 a 700 | No, salvo que el interceptor y las políticas pidan más arneses |
| 4.1 `delay-materializer` | ~460 | 800 a 1 100 | Sí: materializador, hilos virtuales, tiempos de Tomcat, W4 y una IT bajo Tomcat real |
| 5.1 `idempotency-edge` | ~480 | 700 a 1 000 | Probable: interceptor, controlador de prueba, W5 y una IT con base de datos |
| 6.1 cierre | ~0 | 0 a 50 | No: verificación, medición y notas; código solo si algo se corrige |

Total restante: de 4 200 a 5 900 líneas en seis tareas de PR, frente a las 2 520 nominales. Con las 6 822 líneas ya medidas
el cambio completo queda entre 11 000 y 12 700 líneas. Cada PR sigue con el tope de 800: la medición antes de abrir el
PR decide, y una tarea que lo supere se detiene antes de confirmar, como en 2.1, 2.2 y 2.3.

## Nota fechada 2026-10-05: la tarea 2.4 se parte en 2.4a y 2.4b

El propietario aprobó partir la tarea 2.4 (PR 8 `problem-translator`) en dos porque la tarea, implementada y verificada
completa (`./mvnw verify`: Surefire 186 + 783, Failsafe 229), midió **1 046 líneas efectivas** (1 029 adiciones y 17
eliminaciones, igual con y sin `-M`, sin `openspec/`) frente al tope de 800 y a un pronóstico de 700 a 1 000. El cambio pasa
a **19 tareas** (18 de PR más la de cierre 6.1) y **18 PR**, dentro de la excepción al tope de 15 tareas que el propietario ya
concedió. El árbol completo y verificado se conserva en la rama **local** `wip/web-edge-problem-translator-full` (commits
`7e61aff` y `f833cd9`), que nunca se publica y es la fuente de las dos partes.

| Parte | Contenido | Líneas medidas sobre árboles reales |
|---|---|---|
| 2.4a `translator-core` | Códigos `resource-not-found` y `unsupported-media-type` con su catálogo, `ProblemExceptionHandler` (dominio, cuerpo ilegible, cabecera o parámetro ausente o mal tipado, `415`, `404`, último recurso y desconexión), registro, arnés, `ProblemTranslationTest`, `ProblemExceptionHandlerTest`, `ResourceNotFoundTest` | 641 |
| 2.4b `field-violations` | `FieldViolation`, `errors` con `@JsonInclude(NON_EMPTY)`, sobrecarga de `write`, los tres traductores de validación, `spring-boot-starter-validation`, pruebas de validación y nota de `docs/ui-ux` | 417 sobre 2.4a |

Numeración: los PR se llaman 8a y 8b y **los PR 9 a 13 conservan su número**. Orden: 2.4a y después 2.4b desde `main`
actualizado; 2.4b necesita 2.4a. Cada parte debe estar en verde por sí sola: el control negativo de `PublicRouteAllowListTest`
espera ocho fugas en 2.4a (las rutas `validated`, `required`, `domain-known`, `domain-unknown` y `disconnected` más las tres
originales) y diez en 2.4b. Entre 2.4a y 2.4b un fallo de validación de campo cae en el `500` genérico; sin consecuencia, porque
ningún endpoint de producción valida todavía. Trazabilidad: 166 escenarios, 166 con tarea, 0 huérfanos; los 11 que apuntaban a 2.4
apuntan ahora a una sola parte (2.4a: ocho, de estados, tipo no admitido, recurso inexistente, código desconocido, detalles
internos y cuerpo mal formado; 2.4b: tres, de campo inválido, valor rechazado y `errors` fuera del esquema).

## Nota fechada 2026-10-05: la tarea 3.1 se parte en 3.1a y 3.1b

La tarea 3.1 verificada midió **1 548 líneas efectivas** (447 de producción, 45 de soporte y 1 056 de pruebas) frente al tope de 800 y a un pronóstico de 800 a 1 100; el árbol
completo y verificado (`./mvnw verify`: Surefire 186 + 1 007, Failsafe 229) está en la rama local `wip/web-edge-rate-limiter-core-full`. El propietario aprobó la **costura B** y una
**excepción de tamaño de unas 194 líneas para 3.1a**:

| Parte | Contenido | Líneas |
|---|---|---|
| 3.1a `rate-limiter-core` | Puerto, decisión, política, adaptador, `MutableClock` y `InMemoryRateLimiterTest` sin la sección de la tabla | 983 medidas al construirla (994 estimadas sobre el árbol completo; sin `size()`, que pasa a 3.1b) |
| 3.1b `rate-limiter-table-and-stress` | Pruebas de la tabla como clase propia, propiedades de jqwik y concurrencia, y `size()` | unas 609 (estimadas) |

El cambio pasa a **20 tareas** (19 de PR más la de cierre 6.1) y **19 PR**, dentro de la excepción al tope de 15 tareas que el propietario ya concedió. Orden y dependencia: 3.1a y luego
3.1b desde `main` actualizado; 3.1b necesita 3.1a (el adaptador y la política); 3.2 necesita 3.1b. Numeración de PR: 10a y 10b; los PR 11 a 13 conservan su número.

**Dónde van las pruebas de borde de reclamación.** Van en **3.1b**, en la clase de la tabla. Razón: lo que ejercen es la recuperación de entradas vencidas, que es la tabla, y son
lo único que distingue `>=` de `>` en el descarte de admisiones (con `>` una admisión con edad igual a la ventana da una espera de cero y se admite igual, así que ninguna prueba de las
capas lo ve). Así, en 3.1a esa ruptura queda en verde y está declarada como mutante equivalente para las decisiones, y 3.1b la mata. Llevarlas a 3.1a habría añadido unas 30 líneas a la
parte que ya excede el tope y las habría separado del resto de las pruebas de reclamación.

**Escenarios.** Los de `specs/web-edge/spec.md` que apuntaban a 3.1 apuntan ahora a una sola parte: a 3.1a las dimensiones, la capa 1 (salvo la exactitud bajo concurrencia), la capa 2,
«Tamaño máximo no válido» (lo prueba la política), «Retry-After redondeado hacia arriba» (junto con 3.2) y «Estado perdido al reiniciar»; a 3.1b «Exactitud bajo concurrencia» y los
cuatro de la tabla (límite exacto, tabla llena con IP vigente, entrada vencida y llenado concurrente). Trazabilidad: 166 escenarios, 166 con tarea, 0 huérfanos.

## Nota fechada 2026-10-05: la tarea 3.1b se parte en 3.1b y 3.1c

La tarea 3.1b original (tabla acotada, propiedades de jqwik y concurrencia) se amplió con los hallazgos I-1, S-1, I-3 y S-2 de la revisión de seguridad. Implementada y verificada
completa (`./mvnw verify`: Surefire 186 + 1 028, Failsafe 229) midió **1 025 líneas efectivas** (962 adiciones y 63 eliminaciones, igual con y sin `-M`, sin `openspec/`) frente al tope de
800 y a un pronóstico de unas 609. El **propietario aprobó la costura B sin excepción de tamaño**; el árbol completo y verificado se conserva en la rama **local**
`wip/web-edge-rate-limiter-table-full` (`faf1878`), que nunca se publica.

| Parte | Contenido | Líneas |
|---|---|---|
| 3.1b `rate-limiter-table` | I-1, S-1 y S-2 (fuente monotónica, hora dentro del bloqueo, cota de la política), `MutableClock`, `InMemoryRateLimiterTimeTest`, `InMemoryRateLimiterTableTest` y `size()` | 596 medidas al construirla |
| 3.1c `rate-limiter-stress` | `InMemoryRateLimiterPropertiesTest`, `InMemoryRateLimiterConcurrencyTest` con el muestreo de I-3 y `reservedForTest()` | unas 421 (estimadas) |

El cambio pasa a **21 tareas** (20 de PR más la de cierre 6.1) y **20 PR**, dentro de la excepción al tope de 15 tareas que el propietario ya concedió. Orden y dependencia: 3.1b y luego
3.1c desde `main` actualizado; 3.1c necesita 3.1b (el adaptador, `MutableClock` y `size()`); **3.2 necesita 3.1c** (y por tanto 3.1b). Numeración de PR: 10b y 10c; los PR 11 a 13 conservan su número.

**Escenarios.** Los que apuntaban a 3.1b apuntan ahora a una sola parte: a 3.1b «Límite exacto de la tabla», «Tabla llena, IP con entrada vigente» y «Una entrada vencida libera espacio»;
a 3.1c «Exactitud bajo concurrencia» y «Llenado concurrente». Trazabilidad: 166 escenarios, 166 con tarea, 0 huérfanos. La nota fechada de I-1 e I-3 de la tarea 3.1b original se divide
entre las dos partes (I-1, S-1 y S-2 en 3.1b; I-3 en 3.1c).

## Nota fechada 2026-10-06: la tarea 3.2 se parte en 3.2a, 3.2b y 3.2c

La tarea 3.2, construida y verificada completa (`./mvnw verify`: Surefire 186 + 1 087, Failsafe 229), midió **1 551 líneas efectivas** (1 528 adiciones y 23 eliminaciones, igual con y sin `-M`,
sin `openspec/`) frente al tope de 800 y a un pronóstico de 500 a 700: las pruebas sumaron 907 líneas y la decisión del propietario sobre la métrica del hallazgo I-2 añadió un puerto, un
adaptador y su registro. **El propietario aprobó la costura de tres PR sin excepción de tamaño**; el árbol completo y verificado se conserva en la rama **local**
`wip/web-edge-rate-limiter-edge-full` (`cf93861`), que nunca se publica.

| Parte | Contenido | Líneas |
|---|---|---|
| 3.2a `edge-rejection-codes-and-capacity-signal` | Códigos `too-many-requests` y `capacity-exceeded`, catálogo, `forStatus` y válvula; `TooManyRequestsException`, `CapacityExceededException` y sus manejadores; puerto `RateLimitMetrics`, `LogRateLimitMetrics`, su configuración y nota de `docs/07` | unas 505 |
| 3.2b `edge-interceptor` | `RateLimited`, `RateLimiterRegistry`, `RateLimitInterceptor`, `RateLimitPolicyCheck` y su prueba | unas 407 |
| 3.2c `edge-throttling-wiring` | `RateLimitProperties`, `ThrottlingConfiguration`, arnés, `RateLimitEdgeTest`, `RateLimitPropertiesTest` y notas de `docs/03` | unas 639 |

El cambio pasa a **23 tareas** (22 de PR más la de cierre 6.1) y **22 PR**, dentro de la excepción al tope de 15 tareas que el propietario ya concedió. Orden y dependencia: 3.2a, 3.2b y 3.2c,
cada una desde `main` actualizado; **3.2b necesita 3.2a; 3.2c necesita 3.2b; 4.1 necesita 3.2c**. Numeración de PR: 11a, 11b y 11c; los PR 12 y 13 conservan su número.

**Demostraciones deliberadas.** La llamada a la métrica y `tryAcquire` en `preHandle` viven en el interceptor, así que sus dos demostraciones se hacen en 3.2b con la prueba de unidad
(que es la que falla en cuanto el interceptor deja de lanzar en `preHandle` o de señalar) y se repiten en 3.2c por la cadena real. 3.2a demuestra la cota de un evento por segundo, el borde
del segundo y la cabecera `Retry-After` del manejador.

**Escenarios.** Los que apuntaban a 3.2 apuntan ahora a una sola parte: a 3.2a «Cada código tiene su estado»; a 3.2b «Retry-After redondeado hacia arriba» (con 3.1a) y «Retry-After de la capa 1»;
a 3.2c «Sin cupo ni límite», «Petición rechazada sin efecto», «Cambio de configuración», «Valores por defecto», «Valor no positivo» y «Nota fechada en la documentación». Trazabilidad: 166 escenarios,
166 con tarea, 0 huérfanos. Las notas fechadas van con lo que describen: la de `docs/07` en 3.2a, las de `docs/03` en 3.2c y la de `design.md` en 3.2a (puerto y adaptador) y 3.2c (borde).

## Nota fechada 2026-10-06: excepción de tamaño para 3.2c

La tarea 3.2c midió **839 líneas efectivas** (818 añadidas y 21 eliminadas, sin `openspec/`) frente al tope de 800 y al pronóstico de unas 639. Sin las notas de
`docs/03-seguridad.md` (62 líneas) mide 777. El propietario aprobó una **excepción de tamaño de unas 39 líneas** y un solo PR 11c, en lugar de la costura
11c-1/11c-2, porque las notas de seguridad se revisan junto con el cableado que describen. El cambio sigue con 23 tareas y 22 PR.

**Ampliación (2026-10-06).** Las correcciones de la revisión independiente de 3.2c (la prueba de S-2 y la nota I-1 de `docs/03`) llevaron la medición a **869 líneas**. El propietario amplió la excepción para cubrir esas 30 líneas en el mismo PR 11c.

## Nota fechada 2026-10-06: la tarea 4.1 se parte en 4.1a, 4.1b y 4.1c

La tarea 4.1 verificada midió **1 750 líneas efectivas** (253 de producción y 1 497 de pruebas) frente al tope de 800 y a un pronóstico de
460 a 690. El árbol completo y verificado (`./mvnw verify`: Surefire 186 + 1 160, Failsafe 233) se conserva en la rama **local**
`wip/web-edge-delay-materializer-full` (911c025). El propietario aprobó la costura de tres PR **sin excepción de tamaño**:

| Parte | Contenido | Líneas medidas |
|---|---|---|
| 4.1a `delay-materializer-core` | Producción, configuración, `application.yml`, línea de la política de beans y pruebas de unidad y concurrencia | 773 |
| 4.1b `delay-materializer-runtime-proof` | Prueba de configuración, aislamiento por proceso, valores del conector y la IT | 578 |
| 4.1c `blocking-wait-rule` | Regla W4, inventario de exclusión y fixtures | 399 |

El cambio pasa a **25 tareas** (24 de PR más la de cierre 6.1) y **24 PR**, dentro de la excepción al tope de 15 tareas que el
propietario ya concedió. Orden: 4.1a, 4.1b y 4.1c, y después 5.1. Los escenarios de `web-edge` de 4.1 se reparten así: la
espera tras el commit, la suma del retardo, el semáforo acotado y la desconexión se prueban por unidad en 4.1a y por la cadena real en
4.1b; «La espera no retiene hilos…» se prueba en 4.1b; «Ausencia de estado compartido del limitador…», «Ausencia de autenticación por
credencial…» y el requisito de `build-integrity` de la espera bloqueante, en 4.1c.

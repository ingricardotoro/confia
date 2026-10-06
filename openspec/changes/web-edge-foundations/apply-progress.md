# Progreso de aplicación: fundamentos del borde web del proceso administrativo

- **Cambio:** `web-edge-foundations` (F0, cambio 7, parte 4a)
- **Modo:** TDD estricto (`./mvnw verify` en `apps/api`, JDK 25, Docker en ejecución)
- **Estrategia de entrega:** `auto-chain` con `stacked-to-main`, tope de 800 líneas efectivas por PR
- **Última actualización:** 2026-10-06 (3.2b hecha; 3.2c pendiente)

## Estado de las tareas

| Tarea | PR | Estado | Commits |
|---|---|---|---|
| 1.1 | PR 1 `platform-wiring` | Hecha | `d9e6adf`, `9fcb65d` |
| 1.2 | PR 2 `identity-beans` | Hecha | `aaa1161`, `4d99f98` |
| 2.1a | PR 3 `problem-details-core` | Hecha | `2554745` y el commit `docs(sdd)` de esta rama |
| 2.1b | PR 4 `security-chains` | Hecha | `f9bc884`, `6ba1744` y el commit `docs(sdd)` de esta rama |
| 2.1c | PR 5 `portal-and-worker-chain` | Hecha | `7cf71ee` y el commit `docs(sdd)` de esta rama |
| 2.2a | PR 6a `container-rejections` | Hecha | `d9d015b` y el commit `docs(sdd)` de esta rama |
| 2.2b | PR 6b `edge-gates` | Hecha | `c469734`, `8a2eb2e` y el commit `docs(sdd)` de esta rama |
| 2.3a | PR 7a `client-address` | Hecha | `525cfee`, `c16f129` y los commits `docs(sdd)` de esta rama |
| 2.3b | PR 7b `trusted-proxy-resolution` | Hecha | `e1d5f7a` y el commit `docs(sdd)` de esta rama |
| 2.3c | PR 7c `request-origin-filter` | Hecha | `5b271e4` y el commit `docs(sdd)` de esta rama |
| 2.3d | PR 7d `audit-origin` | Hecha | `8a20946` y el commit `docs(sdd)` de esta rama |
| 2.4a | PR 8a `translator-core` | Hecha | `7c609bb` y el commit `docs(sdd)` de esta rama |
| 2.4b | PR 8b `field-violations` | Hecha | `9431be6` y el commit `docs(sdd)` de esta rama |
| 3.1a | PR 10a `rate-limiter-core` | Hecha (983 líneas, excepción de unas 183) | `3162cca` y el commit `docs(sdd)` de esta rama |
| 3.1b | PR 10b `rate-limiter-table` | Hecha (596 líneas) | `5a223f0`, `e05d5ed` y el commit `docs(sdd)` de esta rama |
| 3.1c | PR 10c `rate-limiter-stress` | Hecha | `5c06aab`, `3d55b9a` y los commits `docs(sdd)` de esa rama |
| 3.2 | PR 11 `rate-limiter-edge` | Construida y verificada completa, detenida antes del commit por tamaño (1 551 líneas); el propietario aprobó la costura de tres PR el 2026-10-06; fuente: rama local `wip/web-edge-rate-limiter-edge-full` (`cf93861`) | |
| 3.2a | PR 11a `edge-rejection-codes-and-capacity-signal` | Hecha (517 líneas; 732 tras la corrección de la revisión) | `bf1b5c9`, `733785b`, `fdc3786` y los commits `docs(sdd)` de esta rama |
| 3.2b | PR 11b `edge-interceptor` | Hecha (653 líneas) | `325bf27` y el commit `docs(sdd)` de esta rama |
| 3.2c | PR 11c `edge-throttling-wiring` | Pendiente (necesita 3.2b, desde `main` actualizado) | |
| 4.1 a 6.1 | PR 12, 13 y cierre | Pendientes | |

## Tarea 1.1: PR 1 `platform-wiring`

### Red de seguridad (antes de tocar archivos existentes)

`ConfiaApplicationTest` 10/10 y `ProcessBeanIsolationTest` 4/4 en verde sobre la rama sin cambios.

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO | `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AdminProductionWiringTest,ProcessBeanIsolationTest,ConfiaApplicationTest'` | `Tests run: 7, Failures: 3, Errors: 1` en `AdminProductionWiringTest`; `ConfiaApplicationTest` 10/10 y `ProcessBeanIsolationTest` 4/4 en verde con los argumentos migrados. Causas observadas: `theAdminContextHoldsOneBeanOfEachProductionWiringType` falla con `Expected size: 1 but was: 0` (cero beans `DataSource`, la exclusión sigue vigente); `theAdminContextUsesUtcAsItsClock` falla con `NoSuchBeanDefinition ... java.time.Clock`; las dos pruebas de llave maestra fallan con `Expecting code to raise a throwable` (no hay validación). Es la causa prevista. |
| VERDE parcial | Igual, con `SharedPlatformConfiguration` importada **sin** la línea de política | `AdminProductionWiringTest` `Tests run: 7, Failures: 0`; `ProcessBeanIsolationTest` falla con `process 'admin': bean 'com.confia.shared.platform.infrastructure.SharedPlatformConfiguration' from package 'com.confia.shared.platform.infrastructure' - not in the allow-list` (más `dslContext`, `transactionRunner`, `auditLogWriter` y los demás beans). |
| VERDE | Igual, con la política completa | `AdminProductionWiringTest` 7/7, `ConfiaApplicationTest` 10/10, `ProcessBeanIsolationTest` 4/4: `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0` |
| Demostración deliberada | Quitar la exclusión de `DataSourceAutoConfiguration` de `PortalApplication` y ejecutar `-Dtest=AdminProductionWiringTest` | `Tests run: 7, Failures: 0, Errors: 1`: `portalAndWorkerHoldNoDataSource[1]` falla con `Error creating bean with name 'dataSource' ... Failed to determine a suitable driver class`. Revertido con `git checkout`; `git diff` de ese archivo vacío. |
| Cierre | `./mvnw verify` completo | Surefire 186 + 421 (los 414 de la línea base más 7 nuevos), Failsafe 225, `BUILD SUCCESS` |

Pruebas añadidas: 7 (`AdminProductionWiringTest`: seis métodos, uno parametrizado con dos casos). Las 16
pruebas de OpenAPI y la instantánea quedaron sin cambios (`git status` limpio tras la verificación).
`SpringModulithVerificationTest` en verde.

### Desviaciones del diseño (declaradas)

1. **Paquete de `SharedPlatformConfiguration`.** El diseño la ubica en `com.confia.shared.platform`
   (sin segmento de capa). No es viable: `JooqConfinedToInfrastructureTest` (ADR-0015, regla 4) prohíbe
   `org.jooq` fuera de un paquete `..infrastructure..`, y la configuración declara un `DSLContext`. Vive
   en `com.confia.shared.platform.infrastructure` (con `package-info.java` y `@NamedInterface`). La
   política de procesos queda así: el permitido de administración es
   `com.confia.shared.platform.infrastructure` y el prohibido nominal de portal y trabajador sigue siendo
   `com.confia.shared.platform` (coincide por prefijo). La no vacuidad de `ProcessBeanIsolationTest`
   compara el paquete de origen de forma exacta, de ahí la entrada completa en la lista de permitidos.
2. **`TransactionRunnerConfiguration` nueva en `shared.security`.** `TransactionsOnlyInSharedSecurityTest`
   (regla 7 de ADR-0015) prohíbe nombrar `PlatformTransactionManager` fuera de `shared.security`. El bean
   `TransactionRunner` se declara en esa clase, que `SharedPlatformConfiguration` importa; no se añade
   ningún `@Import` a `AdminApplication`. Cuesta 25 líneas.
3. **`com.confia.shared.audit` no entra en la lista de permitidos de administración en este PR.** Ningún
   bean tiene origen en ese paquete hasta que el PR 5 añada `RequestOriginAuditLogWriter`; la prueba de no
   vacuidad rechaza una entrada sin bean. La línea llega con el PR 5.
4. **`AuditEntry.java` no se modificó.** No contiene ningún texto sobre «ningún proceso lo registra».
   `JooqAuditLogReader` conserva su afirmación (el lector sigue sin registrarse) con una nota aclaratoria.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | La del paso VERDE: `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0` |
| Arnés de ejecución | `ConfiaApplication.launch` real de los tres procesos con una URL inalcanzable (`127.0.0.1:1`) y una llave maestra generada con `SecureRandom` en tiempo de ejecución |
| Frontera de reversión | Administración vuelve a excluir `DataSourceAutoConfiguration`; se retiran `SharedPlatformConfiguration`, `TransactionRunnerConfiguration` y las líneas de `ProcessBeanPolicy` |

### Medición del PR 1 (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 404 | 41 | **445** |
| Con `-M` | 404 | 41 | **445** |

Pronóstico: 380 nominales, 570 en el peor caso; tope 800. Dentro del tope.

### Corrección de revisión independiente (tarea 1.1, 2026-10-04)

| Hallazgo | Cambio | Evidencia observada |
|---|---|---|
| I1: la prueba de arranque sin base de datos no detectaba una conexión temprana | `theAdminPoolIsLazyAndHasNotConnectedAfterStartup`: desenvuelve el `DataSource` a `HikariDataSource` y exige `getHikariPoolMXBean()` nulo | Ruptura deliberada: forzar `getConnection()` en el bean `DSLContext` dio `Tests run: 8, Failures: 1` con `[a pool that has started would have a MXBean: the start must be lazy]`. Revertida (`git diff` vacío). |
| I2: las pruebas de llave no detectaban una causa encadenada | Recorren toda la cadena de causas: sin `IllegalArgumentException`, sin `Illegal base64`, sin subcadena del valor de más de 3 caracteres (valor de prueba `Zq9#Xw7!Lm2@Pk4$`); la de llave ausente con el mismo rigor | Ruptura deliberada: encadenar `e` dio `Tests run: 8, Failures: 1` en `anInvalidMasterKeyStopsTheAdminProcessWithoutEchoingIt` con `[no IllegalArgumentException anywhere in the cause chain]` (la traza mostraba `Illegal base64 character 23`). Revertida. |
| I3: comentario de `spring.jooq` falso | Reescrito: la administración declara su `DSLContext` en `SharedPlatformConfiguration` y la clave sigue inerte | Lectura del diff |
| S1: desviación de paquete | Notas fechadas en `design.md` (decisión 2) y `tasks.md` | Lectura del diff |

Cierre: `./mvnw verify` completo: Surefire 186 + 422, Failsafe 225, `BUILD SUCCESS`.

Medición del PR 1 tras la corrección (`git diff --numstat main...HEAD -- . ':!openspec'`, igual con y sin `-M`): 454 adiciones, 45 eliminaciones, **499** en total (tope 800).

## Tarea 1.2: PR 2 `identity-beans`

### Red de seguridad (antes de tocar archivos existentes)

La línea base tras el PR 1 (`AdminProductionWiringTest`, `ProcessBeanIsolationTest`,
`ConfiaApplicationTest`, `ConfiguredLoginInstitutionProviderTest` en verde) quedó confirmada en la
ejecución de cierre del PR 1: Surefire 186 + 422, Failsafe 225.

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO 1 | `./mvnw -q -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AdminProductionWiringTest,IdentityConfigurationSecretsTest,ConfiguredLoginInstitutionProviderTest,ProcessBeanIsolationTest,ConfiaApplicationTest'` | `COMPILATION ERROR`: `cannot find symbol: method fromValue(String)` en `ConfiguredLoginInstitutionProviderTest` (cinco usos). Es la causa prevista. |
| ROJO 2 | Igual, con un `fromValue` mínimo que delega en el comportamiento previo | `Tests run: 43, Failures: 9, Errors: 1`. `AdminProductionWiringTest` (13): `Failures: 2, Errors: 1` (`theAdminContextHoldsTheFiveIdentityUseCases...`, `theTwoPasswordResetIssuingUseCasesAreNotBeans` con `[non-vacuous: the sibling use case that does have its adapters is a bean]`, y `theLoginInstitutionComesFromTheConfiguredProperty` con `NoSuchBeanDefinition ... LoginInstitutionProvider`). `IdentityConfigurationSecretsTest` (6): `Failures: 6`, todas con `Expecting code to raise a throwable` (no hay validación). `ConfiguredLoginInstitutionProviderTest` (10): `Failures: 1`, `fromValueRejectsAMalformedValueWithoutEchoingItOrChainingTheParser`, porque el mensaje previo era `... must be a valid UUID, was: Qx7!Rk2#Jv9%Wm5@`. `ConfiaApplicationTest` 10/10 y `ProcessBeanIsolationTest` 4/4 en verde. Causas las previstas. |
| VERDE parcial | Igual, con `IdentityConfiguration` importada **sin** la línea de política | `AdminProductionWiringTest` 13/13, `IdentityConfigurationSecretsTest` 6/6, `ConfiaApplicationTest` 10/10; `ProcessBeanIsolationTest` con `Failures: 1` y `process 'admin': bean 'com.confia.identity.infrastructure.wiring.IdentityConfiguration' from package 'com.confia.identity.infrastructure.wiring' - not in the allow-list` (y lo mismo para `argon2Pepper`, `passwordHasher`, `authenticateWithPassword` y los demás beans, con los paquetes `identity.application` e `identity.infrastructure`). |
| VERDE | Igual, ampliado con `IdentityScopeExclusionInventoryTest`, `SpringModulithVerificationTest`, `JooqConfinedToInfrastructureTest` y `LayeredArchitectureTest`, con la política completa | `Tests run: 61, Failures: 0, Errors: 0, Skipped: 0` (`AdminProductionWiringTest` 13, `IdentityConfigurationSecretsTest` 6, `ConfiguredLoginInstitutionProviderTest` 10, `ProcessBeanIsolationTest` 4, `ConfiaApplicationTest` 10, `IdentityScopeExclusionInventoryTest` 12 y 2 de cada regla de arquitectura). |
| Cierre | `./mvnw verify` completo | Surefire 186 + 437 (los 422 de la línea base más 15 nuevos), Failsafe 225, `BUILD SUCCESS`. La instantánea OpenAPI y `portal.routes.json` sin cambios (`git status` limpio de ellos). |

Pruebas añadidas: 15 (`AdminProductionWiringTest` +5, `IdentityConfigurationSecretsTest` 6,
`ConfiguredLoginInstitutionProviderTest` +4). La verificación de fallo de arranque recorre toda la
cadena de causas (`BootFailureAssertions`, extraída de la prueba del PR 1 y reutilizada por ambas
clases): ninguna `IllegalArgumentException` encadenada, ningún detalle de decodificador o analizador
(`Illegal base64`, `Input byte array`, `Invalid UUID`) y ninguna subcadena del valor de más de 3
caracteres. Los valores inválidos de prueba (`Tf8%Hd3^Vb6&Nc1*`, `Qx7!Rk2#Jv9%Wm5@` y una pimienta de 16
bytes de una semilla fija) no comparten palabras con los mensajes y no son secretos; los valores
válidos se generan en tiempo de ejecución.

### Demostraciones deliberadas (cada una revertida; `cmp` contra la copia original sin diferencias)

| Ruptura temporal en `IdentityConfiguration` | Resultado observado |
|---|---|
| Un pepper ausente o en blanco se sustituye por uno válido en lugar de fallar (la ruptura que nombra la tarea) | `IdentityConfigurationSecretsTest` `Tests run: 6, Failures: 2`: `aMissingPepperStopsTheAdminProcessAndNamesTheProperty` y `aBlankPepperIsTreatedAsMissing` |
| Encadenar la causa `e` en el fallo del pepper | `Tests run: 6, Failures: 2`: `aPepperThatIsNotBase64...` y `aPepperOfTheWrongLength...` (la cadena trae `IllegalArgumentException` y `Illegal base64`) |
| Registrar `RequestPasswordReset` con un lambda como planificador | `AdminProductionWiringTest` `Failures: 1`: `theTwoPasswordResetIssuingUseCasesAreNotBeans [RequestPasswordReset waits for PasswordResetIssuanceScheduler]` |
| Leer el identificador de institución de un UUID aleatorio y no de la propiedad | `AdminProductionWiringTest` `Failures: 1` (`theLoginInstitutionComesFromTheConfiguredProperty`) y `IdentityConfigurationSecretsTest` `Failures: 2` (institución ausente y mal formada) |

Además, el `ConfiguredLoginInstitutionProvider` previo (que repetía el valor y encadenaba el
analizador) fue el rojo 2 de `fromValueRejectsAMalformedValue...`. Se verificó con una prueba
desechable (ya eliminada) que `SystemEnvironmentPropertySource` más `ConfigurationPropertySources.attach`
resuelve `CONFIA_IDENTITY_ARGON2PEPPER`, `CONFIA_CRYPTO_COLUMNMASTERKEY` y
`CONFIA_IDENTITY_LOGININSTITUTIONID` con `Environment.getProperty` de las propiedades con guion, que es
lo que documenta `docs/05`.

### Desviaciones del diseño (declaradas)

1. **Paquete de `IdentityConfiguration`.** El diseño la ubica en `com.confia.identity`. No es viable:
   recibe un `DSLContext` y `JooqConfinedToInfrastructureTest` (ADR-0015, regla 4) prohíbe `org.jooq`
   fuera de `..infrastructure..`. Vive en `com.confia.identity.infrastructure.wiring` con
   `package-info.java` y `@NamedInterface`: ADR-0024 admite la configuración pública en un paquete
   anotado, y un subpaquete expone solo la configuración y no los adaptadores. La política de
   administración nombra los tres paquetes de origen de los beans (`identity.application`,
   `identity.infrastructure` e `identity.infrastructure.wiring`), cada uno exacto por la no vacuidad; el
   prohibido nominal de portal, `com.confia.identity`, no cambia. Nota fechada en `design.md` y
   `tasks.md`.
2. **`ConfiguredLoginInstitutionProvider` cambia su mensaje de error.** Repetía el valor rechazado y
   encadenaba la excepción del analizador de UUID; la decisión 3 exige que ningún mensaje de arranque
   contenga el valor. El mensaje conserva la clave y el tipo de excepción; no cambia nada más. El
   constructor de paquete delega en un método privado `parse`, y `fromValue` construye con el mismo.
3. **`Argon2Pepper` como bean.** El pepper se registra como bean (`argon2Pepper`) para que los tres
   consumidores lo reciban por parámetro; su `toString()` ya está redactado.
4. **Sin prueba de ida y vuelta de hash.** No se añadió una prueba que haga `hash` y `matches` con el
   `PasswordHasher` registrado: construir el hasher ya calcula el hash señuelo con el perfil de piso, y
   los efectos del pepper tienen sus pruebas propias (`Argon2PepperTest`,
   `BouncyCastleArgon2PasswordHasherTest`).

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | La del paso VERDE: `Tests run: 61, Failures: 0, Errors: 0, Skipped: 0` |
| Arnés de ejecución | `ConfiaApplication.launch` real del proceso administrativo (y de portal y trabajador, sin casos de uso) con una URL inalcanzable y los tres secretos generados en tiempo de ejecución, cada uno ausente o inválido por separado |
| Frontera de reversión | Se retiran `identity/infrastructure/wiring/`, su `@Import` en `AdminApplication`, las tres líneas de `ProcessBeanPolicy`, `fromValue` y las pruebas nuevas, y la nota de `docs/05` |

### Medición del PR 2 (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 643 | 67 | **710** |
| Con `-M` | 643 | 67 | **710** |

Pronóstico: 330 nominales, 500 en el peor caso; tope 800. Dentro del tope pero por encima del
pronóstico: el exceso lo explican `BootFailureAssertions` (70) y la ampliación de
`TestProcessArguments` (78), que sustentan el recorrido de la cadena de causas exigido tras la revisión
del PR 1, y la nota de `docs/05` (20). Ninguna prueba ni comentario se recortó.

## Partición de la tarea 2.1 (decisión del propietario, 2026-10-04)

La tarea 2.1 original (PR 3 `security-chains`) se implementó y verificó completa, y midió **2 000 líneas**
efectivas (1 976 adiciones y 24 eliminaciones, igual con y sin `-M`) frente al tope de 800 y a un pronóstico
de 520 nominales y 780 en el peor caso. El propietario aprobó partirla en 2.1a, 2.1b y 2.1c. El árbol
completo y verificado (con sus pruebas, el arnés y las notas) está en la rama **local**
`wip/web-edge-security-chains-full` (commit `a8fba42`, 44 archivos), que nunca se publica y es la fuente de
2.1b y 2.1c. Tareas: 14; cadena: 13 PR.

Evidencia del intento completo, que sigue siendo válida para 2.1b y 2.1c: ROJO (a) de `bannedDependencies`
por la ruta de artefactos falsos (la descarga real falla con `PKIX path building failed`, sin tocar TLS);
ROJO (b) `OpenApi*Test` `Tests run: 18, Failures: 16` con el starter y sin cadena; verde completo con
Surefire 186 + 512, Failsafe 225; demostraciones: quitar `anyRequest().denyAll()` no rompe nada
(`Tests run: 24, Failures: 0`, Spring Security 7 deniega por omisión), `anyRequest().permitAll()` da
`Failures: 21` de 24 y quitar `ProblemAuthenticationEntryPoint` da `Failures: 27` de 36.

## Tarea 2.1a: PR 3 `problem-details-core`

### Rebalanceo antes del commit

El primer corte de 2.1a (Problem Details, cabeceras e identificador de petición, con sus pruebas) midió
**1 052 líneas** (1 044 adiciones y 8 eliminaciones) y no se comprometió. Sin recortar pruebas ni
comentarios se movió, por su dependencia, el filtro `RequestContextFilter` con su prueba a 2.1c y
`FieldViolation` con el miembro `errors` a 2.4 (los produce el traductor). `ProblemResponses` lleva ahora la
constante pública `REQUEST_ID_ATTRIBUTE`, que el filtro de 2.1c escribirá (así `problem` ya no depende de
`request`). Resultado: **775**.

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO | `-Dtest='ProblemCodeTest,ProblemCatalogCoverageTest,ProblemResponsesTest,SecurityHeadersFilterTest,RequestContextFilterTest,WebEdgeFiltersInProcessesTest'` | `COMPILATION ERROR` (122 líneas): `package com.confia.shared.web.problem does not exist` y `cannot find symbol`. Causa prevista. |
| ROJO, lista de permitidos | `@Import` de `WebEdgeConfiguration` sin las líneas de `ProcessBeanPolicy` | `ProcessBeanIsolationTest` `Tests run: 4, Failures: 2` (administración y portal): `bean '...WebEdgeConfiguration' from package 'com.confia.shared.web.edge' - not in the allow-list` y los beans de `shared.web.request` y `shared.web.problem`. |
| VERDE | Las pruebas nuevas más OpenAPI, aislamiento, Modulith, capas, jOOQ, puntos de entrada, cableado y `ConfiaApplicationTest` | `Tests run: 108, Failures: 0, Errors: 0, Skipped: 0` (primer corte, antes del rebalanceo) |
| Demostración | `RequestContextFilter` reutiliza `X-Request-Id` (primer corte) | `Tests run: 7, Failures: 0, Errors: 1` (`UUID.fromString("cliente-123")`); revertida (`cmp`). Esta prueba y el filtro pasan a 2.1c. |
| Demostración | Quitar `Cache-Control` de `SecurityHeadersFilter` | `SecurityHeadersFilterTest` y `WebEdgeFiltersInProcessesTest`: `Tests run: 6, Failures: 5`; revertida (`cmp`). |
| Cierre | `./mvnw verify` completo sobre el corte final | Surefire 186 + 475 (los 437 de la línea base más 38 nuevos), Failsafe 225, `BUILD SUCCESS`. Instantánea OpenAPI y `routes` sin cambios. |

Un primer intento del corte final no compiló (`ProblemResponses`, error mío al editar con `sed`); se
corrigió y se repitió el `verify` completo antes de confirmar el resultado de arriba.

Pruebas añadidas: 38 (`ProblemCodeTest` 21, `ProblemCatalogCoverageTest` 5, `ProblemResponsesTest` 6,
`SecurityHeadersFilterTest` 4, `WebEdgeFiltersInProcessesTest` 2). Las 16 pruebas de OpenAPI siguen en
verde sin tocarlas (la cadena de seguridad aún no existe; sus cuatro aserciones negativas cambian en 2.1b).

### Pruebas que solo pasarían con la parte siguiente

Ninguna en el corte final. Cuatro cosas dependían de partes posteriores y se resolvieron así:
`ProblemResponses` leía una constante de `RequestContextFilter` (ahora la define ella misma); la prueba de
cabeceras de «una respuesta denegada por la cadena» se hace con una cadena simulada (`MockFilterChain`) y no
con la cadena real, que llega en 2.1b; la prueba de cabeceras en procesos reales usa el documento OpenAPI de
`local`; y `ProblemBody` no lleva `errors` hasta 2.4.

### Medición del PR 3 (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 767 | 8 | **775** |
| Con `-M` | 767 | 8 | **775** |

Pronóstico por parte: 600 a 800. Estimaciones de las siguientes: 2.1b de 600 a 800 (si pasa de 800 se
mueven pruebas y arnés a 2.1c) y 2.1c de 700 a 800.

### Desviaciones del diseño (declaradas)

1. `ProblemBody` sin `errors` y sin `FieldViolation` (llegan en 2.4); `ProblemResponses` define
   `REQUEST_ID_ATTRIBUTE`; `RequestContextFilter` llega en 2.1c. El diseño los ubicaba en el PR 3 completo.
2. `ProcessBeanPolicy`: el trabajador prohíbe `com.confia.shared.web` completo, con una razón que conserva la
   frase fijada por `ProcessBeanInspectorTest`; las prohibiciones de `org.springframework.security` llegan en
   2.1c.
3. Las demás desviaciones del intento completo (pruebas del portal en `com.confia.bootstrap`, la regla
   `denyAll()` redundante) pertenecen a 2.1b y 2.1c y están en las notas fechadas de `design.md` y `tasks.md`.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | La del paso VERDE más `ProblemCodeTest`, `SecurityHeadersFilterTest`, `ProblemResponsesTest`, `WebEdgeFiltersInProcessesTest`; resultado final por `./mvnw verify`: Surefire 186 + 475, Failsafe 225 |
| Arnés de ejecución | `MockFilterChain` para el filtro de cabeceras y `ConfiaApplication.launch` real del proceso administrativo y del portal con `local` |
| Frontera de reversión | Se retiran los dos `@Import` de `WebEdgeConfiguration`, `shared.web.{edge,problem,request}`, `i18n/problems.properties`, las pruebas y las líneas de `ProcessBeanPolicy` |

### Revisión independiente de 2.1a (2026-10-04)

Riesgo evaluado: alto. Veredicto del revisor: sin hallazgos bloqueantes ni importantes, con cuatro
sugerencias. Se corrigió la S1 antes de abrir el PR: `theLanguageIsTheCatalogsWhateverAcceptLanguageSays`
no podía fallar por la causa que nombra, porque el catálogo real no tiene entradas en inglés. Ahora usa
un `StaticMessageSource` con textos es-HN e ingleses. **Demostración:** con `request.getLocale()` en
lugar de `CATALOG_LOCALE` en `ProblemResponses`, la prueba falla con
`expected: "detalle es-HN" but was: "english detail"`; revertido. `./mvnw verify` completo: Surefire
186 + 475, Failsafe 225, `BUILD SUCCESS`. Pendientes como sugerencia: S2 (cabeceras y `Server` en un
error real del proceso, cubierto en 2.1c), S3 (`ProblemCode.ofCode` sin consumidor de producción hasta
2.4) y S4 (dominio de ejemplo `confia.example` en la skill `confia-api-conventions`).

## Tarea 2.1b: PR 4 `security-chains`

Rama `change/web-edge-foundations-security-chains`, creada desde `main` en `14d37fd` tras fusionar el PR 3. El
código sale de la rama local `wip/web-edge-security-chains-full` (sin tocarla; sigue en `a8fba42`), partiendo
siempre de la versión de `main` en los archivos de 2.1a (`ProblemResponses`, `ProblemBody` sin `errors` y
`REQUEST_ID_ATTRIBUTE` en `ProblemResponses` no se tocaron).

### Red de seguridad

`OpenApi*Test`, `ProcessBeanIsolationTest`, `SecurityHeadersFilterTest` y `ProblemResponsesTest` en verde antes
de tocar nada: `Tests run: 32, Failures: 0, Errors: 0` (la cobertura de JaCoCo falla con `-Dtest=` acotado, como
avisa `tasks.md`).

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (a), descarga real | Añadir de forma temporal `spring-boot-starter-oauth2-resource-server` a `app/pom.xml` y `./mvnw validate` | La descarga falla: `Could not transfer artifact org.springframework.boot:spring-boot-starter-oauth2-resource-server:pom:4.1.1 ... (certificate_unknown) PKIX path building failed`. No se tocó TLS ni el almacén de confianza. |
| ROJO (a), ruta de artefactos falsos | Repositorio de archivos temporal en el directorio de trabajo de la sesión, con un POM y un JAR vacío de cada coordenada prohibida (`org.springframework.boot:spring-boot-starter-oauth2-resource-server:4.1.1` y `org.springframework.security:spring-security-oauth2-resource-server:7.1.1`), declarado en `app/pom.xml` solo mientras duró la prueba. **Sin** la prohibición | `./mvnw validate`: `BannedDependencies passed` en `confia-api` con la dependencia prohibida presente. Es el rojo: nada impide la dependencia. |
| VERDE (a) | Con las dos exclusiones y su mensaje en `apps/api/pom.xml` | `BUILD FAILURE`, `Rule 2: ...BannedDependencies failed with message: Banned dependency (... web-edge-foundations design.md decision 4 keeps Spring Security a servlet filter chain and forbids the OAuth2 resource server)` y `org.springframework.boot:spring-boot-starter-oauth2-resource-server:jar:4.1.1 <--- banned via the exclude/include list`. Con la dependencia sustituida por la biblioteca: `org.springframework.security:spring-security-oauth2-resource-server:jar:7.1.1 <--- banned via the exclude/include list`. Las dos coordenadas quedan probadas por separado y el BOM gestiona ambas versiones. |
| Limpieza de (a) | Se retiró el repositorio y la dependencia de `app/pom.xml` | `cmp` de `app/pom.xml` contra la copia original: sin diferencias. Se borraron de `~/.m2` los directorios `org/springframework/boot/spring-boot-starter-oauth2-resource-server` y `org/springframework/security/spring-security-oauth2-resource-server` (el resolvedor había dejado el POM falso y un `.lastUpdated`); `find ~/.m2 -iname '*oauth2-resource-server*'` devuelve 0 archivos. |
| ROJO (b) | `spring-boot-starter-security` en `app/pom.xml`, sin cadena propia: `-Dtest='OpenApi*Test'` | `Tests run: 18, Failures: 16`: `OpenApiContractSnapshotTest` 8 de 10 y `OpenApiExposureByProfileTest` 8 de 8, con `expected: 200 but was: 401` (la cadena por omisión de Spring Boot pide autenticación básica; el log imprime la contraseña generada, que es lo que la exclusión de `UserDetailsServiceAutoConfiguration` evita). |
| ROJO (c) | Arnés, `AdminSecurityChainTest`, `PublicEndpointsTest` y las aserciones nuevas de `OpenApiExposureByProfileTest` sin producción | `COMPILATION ERROR`, 30 errores `cannot find symbol` (`PublicEndpoint`, `PublicEndpoints`, `SecurityChains`). |
| VERDE | Producción de `shared.web.edge` y `shared.web.problem`, `@Import` de las cadenas y exclusiones: `-Dtest='AdminSecurityChainTest,PublicEndpointsTest,OpenApi*Test,ProcessBeanIsolationTest,SecurityHeadersFilterTest,ProblemResponsesTest,WebEdgeFiltersInProcessesTest,ConfiaApplicationTest,AdminProductionWiringTest'` | `Tests run: 88, Failures: 0, Errors: 0, Skipped: 0` (`AdminSecurityChainTest` 24 y `PublicEndpointsTest` 7 en ese corte; después del rebalanceo, ver abajo). |
| VERDE final | Tras el rebalanceo: `-Dtest='AdminSecurityChainTest,OpenApi*Test,ProcessBeanIsolationTest'` | `Tests run: 40, Failures: 0, Errors: 0, Skipped: 0` (`AdminSecurityChainTest` 18, `OpenApiContractSnapshotTest` 10, `OpenApiExposureByProfileTest` 8, `ProcessBeanIsolationTest` 4). |
| Cierre | `./mvnw verify` completo sobre el árbol final | Surefire 186 + 493 (los 475 de la línea base más 18 nuevos), Failsafe 225, `BUILD SUCCESS`. La instantánea OpenAPI (`apps/api/openapi/*.json`) **sin cambios**: `git status` limpio de ellos. |

Lista de permitidos del proceso: no hubo rojo de `ProcessBeanIsolationTest` por falta de línea de política.
Los paquetes de los beans nuevos (`shared.web.edge` y `shared.web.problem`) ya estaban permitidos desde 2.1a, y el
marco (`org.springframework.security`) no se inspecciona en administración ni portal, así que 2.1b no añade
ninguna línea de política. La inspección nominal del trabajador llega en 2.1c.

Pruebas añadidas: 18 (`AdminSecurityChainTest`: 8 métodos, tres parametrizados). Las 16 pruebas de OpenAPI
quedan en verde: 12 sin tocar su código y 4 con la aserción corregida (de `404` a `401` con
`application/problem+json`, `type` de `authentication-required` y sin el documento ni la interfaz).

### Demostraciones deliberadas (cada una revertida; `cmp` contra la copia original sin diferencias)

Sobre el árbol final (`AdminSecurityChainTest` 18 y `OpenApiExposureByProfileTest` 8):

| Ruptura temporal | Resultado observado y causa |
|---|---|
| `SecurityChains`: `anyRequest().denyAll()` por `anyRequest().permitAll()` | `AdminSecurityChainTest` `Tests run: 18, Failures: 18`; total 22 de 26 con las 4 de `OpenApiExposureByProfileTest`. Causa leída en el mensaje: `expected: 401 but was: 200` (o `but was: 404` para la ruta sin controlador, y `expected: 0 but was: 2` en el contador de invocaciones de un controlador que no debía correr). |
| `SecurityChains`: quitar `.authenticationEntryPoint(new ProblemAuthenticationEntryPoint(problems))` | `AdminSecurityChainTest` `Failures: 15` de 18 y total 19 de 26: `expected: 401 but was: 403` (sin punto de entrada la denegación anónima es el `403` por omisión y no un Problem Details). |
| `SecurityChains`: quitar la línea `anyRequest().denyAll()` sin más | `AdminSecurityChainTest` `Tests run: 18, Failures: 0` (la prueba del arnés no cambia: Spring Security 7 deniega por omisión cuando hay reglas). Es el no-rojo documentado. **Pero** `OpenApiExposureByProfileTest` falla en las 4 ejecuciones negativas con `Errors: 4`: `BeanCreation Error creating bean with name 'adminSecurityFilterChain'` (y `portalSecurityFilterChain`), causa `IllegalStateException: At least one mapping is required`. Con la lista blanca vacía (perfil `prod`) la regla final es obligatoria. Nota fechada en `design.md` y `tasks.md`. |
| Primer corte (24 pruebas), quitar el bean `problemRequestRejectedHandler` de `WebEdgeConfiguration` | `AdminSecurityChainTest` `Failures: 4`: `Expecting actual: "text/html;charset=utf-8" to start with: "application/problem+json"` (la página de error de Tomcat en lugar de Problem Details) en las tres variantes `/x;a=b`, `/x/.` y `/y/../x` y en la prueba del rechazo del cortafuegos, retirada después (en el árbol final fallan las tres variantes). |
| Primer corte, `SecurityChains` ignora el método (`requestMatchers(endpoint.pattern())`) | `aGetOnlyPublicRouteDeniesEveryOtherMethod`: `Failures: 4` (`POST`, `PUT`, `DELETE`, `PATCH`), `expected: 401 but was: 200`. |

Las dos últimas se hicieron antes del rebalanceo; los métodos que las detectan (salvo el retirado) siguen en el árbol final.

### Rebalanceo antes del commit (tope de 800)

El primer corte midió **977 líneas** (950 adiciones y 27 eliminaciones) y no se comprometió. Sin recortar pruebas
ni comentarios se movieron a 2.1c, por su dependencia de la cadena real con cabeceras, sesión e identificador:
cinco métodos de `AdminSecurityChainTest` (sesión y `Set-Cookie`, cabeceras base en error y éxito, consulta en
`instance` e idioma fijo), `SessionCounter`, `assertBaseSecurityHeaders` y `PublicEndpointsTest`. Se retiró
también `theFirewallRejectionCarriesNoViolationList` (con el cuerpo de 2.1a no hay `errors`: no podía fallar; vuelve
con 2.4). Del arnés se dejaron fuera, por pertenecer a 2.1c, `/test/boom`, el acceso al filtro de identificador y
`startAsPortal`. Resultado: **791**. `tasks.md` (filas de trazabilidad y nota fechada) y la tarea 2.1c lo
reflejan; ningún escenario queda huérfano.

### Medición del PR 4 (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 764 | 27 | **791** |
| Con `-M` | 764 | 27 | **791** |

Pronóstico: 600 a 800. Dentro del tope.

### Desviaciones del diseño (declaradas)

1. Sin línea nueva en `ProcessBeanPolicy` en 2.1b (los paquetes ya estaban permitidos desde 2.1a); el diseño
   preveía el rojo de lista de permitidos para cada `@Import`, que aquí no aplica.
2. Las pruebas de sesión, cabeceras, consulta e idioma por la cadena real y la prueba unitaria de `PublicEndpoints`
   pasan a 2.1c por el tope de 800 (nota fechada de `tasks.md`).
3. La regla `anyRequest().denyAll()` no es solo explícita: es obligatoria con la lista blanca vacía (nota fechada
   de `design.md`).
4. El trabajador conserva en 2.1b los tres beans inertes de Spring Security (P1): su exclusión y la prueba que la
   exige son de 2.1c.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='AdminSecurityChainTest,OpenApi*Test,ProcessBeanIsolationTest'`: `Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`; cierre por `./mvnw verify`: Surefire 186 + 493, Failsafe 225 |
| Arnés de ejecución | Cadena real por HTTP (`RANDOM_PORT`) en un proceso sin base de datos con controladores de prueba y un filtro de principal de prueba, más los procesos administrativo y portal reales por `ConfiaApplication.launch` con `prod`, un perfil inexistente y `local` |
| Frontera de reversión | Se retira `spring-boot-starter-security`: el sistema vuelve a no tener borde de seguridad (se retiran también las dos exclusiones de `bannedDependencies`, las clases de `shared.web.edge` y `shared.web.problem` de 2.1b, las exclusiones de `AdminApplication` y `PortalApplication` y `spring.web.resources.add-mappings`) |

### Revisión independiente de 2.1b (2026-10-04)

Riesgo evaluado: alto. Auditoría de seguridad: sin bloqueantes, un importante y seis sugerencias.

- **I1 (importante): las garantías sin estado no tienen prueba en este PR.** `STATELESS`,
  `requestCache.disable` y `csrf.disable` están en `SecurityChains`, pero las pruebas de sesión y de
  `Set-Cookie` se movieron a 2.1c para respetar el presupuesto. **Decisión:** 2.1c es el siguiente PR
  de la cadena y ningún otro PR se fusiona entre ambos. Para CSRF se registró una tercera condición
  dura de aceptación de `session-tokens-and-web-layer` en `foundations-plan/exploration.md`: ninguna
  credencial en cookie se fusiona sin CSRF de doble envío y una prueba que dé `403` sin `X-CSRF-Token`.
- **S1 corregida:** el Javadoc de `ProblemRequestRejectedHandler` decía que los bytes rechazados nunca
  se devuelven; ahora aclara que la ruta sí llega a `instance`, escapada, acotada y sin consulta.
- **Para 2.1c:** S2 (`HEAD`, `OPTIONS`, `TRACE` y variantes `//x`, `%2f`, `%2e`, `%00`, `%78`) y S5
  (prueba de la cadena del portal).
- **Para 2.2:** S6 (la prueba de configuración debe reconocer que `SPRINGDOC_API_DOCS_ENABLED=true`
  abriría la documentación en producción).
- **Para 2.4:** S1 en su parte de prueba (`instance` en el `400` del firewall).
- **Para `session-tokens-and-web-layer`:** S4 (`WWW-Authenticate` en el `401`).

## Tarea 2.1c: PR 5 `portal-and-worker-chain`

Rama `change/web-edge-foundations-portal-and-worker`, creada desde `main` en `92a730b` tras fusionar el PR 4. El
código sale de la rama local `wip/web-edge-security-chains-full` (sin tocarla; sigue en `a8fba42`), partiendo
siempre de la versión de `main` en los archivos de 2.1a y 2.1b: `ProblemResponses` conserva
`REQUEST_ID_ATTRIBUTE` (el filtro y el controlador de prueba la leen de allí), no hay `FieldViolation` ni
`ProblemBody.errors`, y el Javadoc de `ProblemRequestRejectedHandler` y la prueba de idioma no se tocaron.

### Red de seguridad

`AdminSecurityChainTest`, `OpenApi*Test`, `ProcessBeanIsolationTest`, `SecurityHeadersFilterTest`,
`ProblemResponsesTest` y `WebEdgeFiltersInProcessesTest` antes de tocar nada: `Tests run: 52, Failures: 0,
Errors: 0` (la cobertura de JaCoCo falla con `-Dtest=` acotado, como avisa `tasks.md`).

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO, línea 1 de `ProcessBeanPolicy` | Prohibido nominal `org.springframework.security` para el trabajador, sin tocar `WorkerApplication`: `-Dtest='ProcessBeanIsolationTest,ProcessBeanInspectorTest'` | `Tests run: 9, Failures: 1`: `process 'worker': bean 'authenticationEventPublisher' from package 'org.springframework.security.authentication' - forbidden: the worker has no security chain and no security bean (web-edge-foundations decision 4)`. |
| ROJO, línea 2 | Se añade `org.springframework.boot.security` | `Tests run: 9, Failures: 1`, ahora con los registros de los 3 beans inertes de P1: `SecurityAutoConfiguration` (paquete `org.springframework.boot.security.autoconfigure`), `authenticationEventPublisher` (en los paquetes de Spring Boot y de Spring Security) y `spring.security-...SecurityProperties`. |
| ROJO, aserciones de tipo | `ProcessBeanIsolationTest` con cero `SecurityFilterChain`, cero `Filter` y cero beans de los dos paquetes | `Tests run: 9, Failures: 1`: `[the worker has no bean of a security package] Expecting empty but was: ["org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration : ...`. Las dos primeras aserciones ya pasaban (el trabajador no tiene cadena ni filtro); la tercera es la que detecta los inertes. |
| VERDE (trabajador) | Exclusión de `SecurityAutoConfiguration` y `UserDetailsServiceAutoConfiguration` en `WorkerApplication` | `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`. |
| ROJO (filtro y cadena) | `AdminSecurityChainTest` ampliado, `PortalSecurityChainTest`, `RequestContextFilterTest`, `PublicEndpointsTest` y el arnés ampliado, sin `RequestContextFilter` | `COMPILATION ERROR`: `cannot find symbol` en `HarnessController.java` (`RequestContextFilter`), 4 líneas. |
| VERDE | `RequestContextFilter`, el bean `serverRequestContextFilter` y las pruebas: `-Dtest='AdminSecurityChainTest,PortalSecurityChainTest,RequestContextFilterTest,PublicEndpointsTest,ProcessBeanIsolationTest'` | `Tests run: 46, Failures: 0, Errors: 0, Skipped: 0` (`AdminSecurityChainTest` 23, `PortalSecurityChainTest` 6, `RequestContextFilterTest` 6, `PublicEndpointsTest` 7, `ProcessBeanIsolationTest` 4). |
| VERDE final | Con `StatelessChainTest` y los casos de S2, más OpenAPI, cableado, Modulith y `ConfiaApplicationTest` | `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0` (`AdminSecurityChainTest` 31, `StatelessChainTest` 2). |
| Cierre | `./mvnw verify` completo sobre el árbol final | Surefire 186 + 527 (los 493 de la línea base más 34 nuevos), Failsafe 225, `BUILD SUCCESS`. La instantánea OpenAPI (`apps/api/openapi/*.json`) y `apps/api/routes` **sin cambios**: `git status` limpio de ellos. |

Pruebas añadidas: 34 (`AdminSecurityChainTest` +13, `PortalSecurityChainTest` 6, `PublicEndpointsTest` 7,
`RequestContextFilterTest` 6 y `StatelessChainTest` 2).

### Condición de fusión I1 (revisión de 2.1b): la cadena no tiene estado

Cada ruptura se hizo sobre `SecurityChains`, se corrió `-Dtest='StatelessChainTest,AdminSecurityChainTest,PortalSecurityChainTest'`
y se revirtió (`cmp` contra la copia original sin diferencias y `git diff` vacío del archivo).

| Ruptura | Antes de `StatelessChainTest` | Con `StatelessChainTest` (mensaje exacto) |
|---|---|---|
| Quitar `sessionManagement(... STATELESS)` | `Tests run: 29, Failures: 0`: **ninguna prueba falla** | `Tests run: 31, Failures: 1`: `StatelessChainTest.theSecurityContextLivesOnlyInTheRequestAndNeverInASession`: `[any other repository keeps the context in the HTTP session] ... to be exactly an instance of: ...RequestAttributeSecurityContextRepository but was an instance of: ...DelegatingSecurityContextRepository` |
| Quitar `requestCache(RequestCacheConfigurer::disable)` | `Tests run: 29, Failures: 0`: **ninguna prueba falla** | `Tests run: 31, Failures: 1`: `StatelessChainTest.theChainSavesNoRequestToReplayAfterALogin`: `[a request cache filter would save the request in a session] Expecting actual: [... SecurityContextHolderFilter, RequestCacheAwareFilter, SecurityContextHolderAwareRequestFilter ...]` |
| Quitar las dos | `Tests run: 29, Failures: 1`: `AdminSecurityChainTest.noResponseCreatesASessionOrSetsACookie`: `Expecting an empty Optional but was containing value: "JSESSIONID=...; Path=/; HttpOnly"` | Igual, más los dos de `StatelessChainTest` (no se repitió con la clase nueva) |

Hallazgo: las dos líneas se respaldan para las respuestas (`STATELESS` instala un `NullRequestCache`, y sin
`STATELESS` la caché deshabilitada tampoco guarda la petición), así que **las pruebas de respuesta que 2.1b movió
a este PR no bastaban**: quitar una sola línea las dejaba todas en verde. Por eso se añadió `StatelessChainTest`,
que inspecciona la cadena real, con reflexión sobre el campo `securityContextRepository` de
`SecurityContextHolderFilter` (nombre de Spring Security 7.1.1; si cambia, la prueba falla y no pasa en
silencio). `csrf.disable` no tiene prueba aquí: su condición de aceptación quedó registrada en
`foundations-plan/exploration.md` (ninguna credencial en cookie se fusiona sin CSRF).

### Otras demostraciones deliberadas (cada una revertida; `cmp` contra la copia original sin diferencias)

| Ruptura temporal | Resultado observado y causa |
|---|---|
| **S5.** `PortalSecurityConfiguration` sustituida por `http.csrf(disable).authorizeHttpRequests(anyRequest().permitAll())` | `PortalSecurityChainTest` `Tests run: 6, Failures: 5` (las tres del parámetro, la documentación `prod` y la de `local`): `expected: 401 but was: 404`. La sexta (`aRouteAddedToThePortalContextIsStillDenied...`) usa el arnés con la cadena real y no la del proceso, y sigue en verde. |
| `RequestContextFilter` reutiliza el `X-Request-Id` del cliente | `RequestContextFilterTest` `Tests run: 6, Errors: 1`: `theServerIgnoresTheIdentifiersTheClientSends`: `IllegalArgumentException: Invalid UUID string: cliente-123`. |
| `SecurityChains`: quitar `ProblemAuthenticationEntryPoint` | `Tests run: 45, Failures: 30` (`PortalSecurityChainTest` 6, `AdminSecurityChainTest` 21, `RequestContextFilterTest` 3): `expected: 401 but was: 403`. |
| Quitar la exclusión de `SecurityAutoConfiguration` del trabajador | Es el rojo del primer cuadro (anterior al verde); no se repitió. |

### S2 de la revisión de 2.1b: métodos y variantes de ruta

Cupieron en el presupuesto (712 de 800) y están en `AdminSecurityChainTest`: `HEAD` y `OPTIONS` sobre una ruta
protegida y sobre una pública solo de `GET` (`401` con `application/problem+json`), y las variantes `//x`,
`/x%2e` y `/%78` (esta decodifica a `/x`) en la prueba de variantes existente. **Hallazgo:** `/x%2f`, `/x%00` y
`TRACE` no llegan a la cadena: los rechaza Tomcat con su propia página HTML (`400`, `400` y `405`, sin Problem
Details y sin las cabeceras base). Están cubiertas por pruebas que afirman lo que sí se cumple (estado `400` o
`405` y ningún controlador corre). **Brecha abierta, asignada a la tarea 2.2 (nota fechada 2026-10-04 en `tasks.md` y `design.md`):**
la uniformidad de Problem Details y de cabeceras no alcanza a lo que el contenedor rechaza antes de los
filtros; arreglarla exige configurar Tomcat (valve o página de error) o un filtro anterior al conector, que
queda fuera del alcance de este PR.

### Desviaciones del diseño y de la tarea (declaradas)

1. `RequestContextFilterChainTest` y `AdminSecurityHeadersAndSessionTest` no se crearon, y **el propietario no
   aprobó de forma explícita omitirlos** (ver la tabla de cobertura de la corrección de revisión, más abajo). La
   afirmación anterior de que el orden `HIGHEST_PRECEDENCE + 10` «se observa porque la respuesta `401` de la
   cadena ya lleva el `traceId` del servidor» era falsa: ninguna prueba por la cadena falla si cambia el orden
   mientras el filtro siga antes de la cadena de Spring Security. La cubre `RequestContextFilterUnitTest`.
2. Se añadió `StatelessChainTest` (no estaba en la tarea) por la condición I1: descubrió que las pruebas de
   respuesta no detectaban la falta de una sola de las dos líneas.
3. `ProcessBeanPolicy` cambia el texto de `WEB_EDGE` («no filters, no security chain ... decision 4») conservando la
   frase fijada por `ProcessBeanInspectorTest`.
4. `FieldViolation`, `ProblemBody.errors` y `theFirewallRejectionCarriesNoViolationList` siguen en 2.4.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='AdminSecurityChainTest,PortalSecurityChainTest,RequestContextFilterTest,PublicEndpointsTest,StatelessChainTest,ProcessBeanIsolationTest,ProcessBeanInspectorTest,OpenApi*Test,SecurityHeadersFilterTest,ProblemResponsesTest,WebEdgeFiltersInProcessesTest,ConfiaApplicationTest,AdminProductionWiringTest,SpringModulithVerificationTest'`: `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`; cierre por `./mvnw verify`: Surefire 186 + 527, Failsafe 225 |
| Arnés de ejecución | Cadena real por HTTP (`RANDOM_PORT`) en el arnés sin base de datos, el portal real por `ConfiaApplication.launch` (`prod` y `local`) y los tres procesos reales para el trabajador |
| Frontera de reversión | Se retiran `RequestContextFilter`, su bean y la exclusión del trabajador (`WorkerApplication`), las dos líneas de `ProcessBeanPolicy` y las pruebas; el resto es de 2.1a y 2.1b |

### Medición del PR 5 (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 690 | 22 | **712** |
| Con `-M` | 690 | 22 | **712** |

Pronóstico: 700 a 800. Dentro del tope de 800. Tareas: 14 de 15.

### Corrección de la revisión independiente de 2.1c (2026-10-04)

El revisor bloqueó el PR por B1. Una sola corrección sobre la misma rama.

**B1: tres comportamientos del ROJO de 2.1c sin una prueba que pudiera fallar por su causa** (retirada del MDC al
terminar, relanzado con la respuesta confirmada y orden del filtro). Se creó `RequestContextFilterUnitTest`
(8 pruebas, sin contexto de Spring: `MockHttpServletRequest`, `MockHttpServletResponse` y cadenas lambda; un
`ListAppender` de Logback sobre el registrador del filtro). Decisión sobre un valor ajeno en la clave `requestId`
del MDC: el filtro es el dueño de la clave, así que lo **sustituye** durante la petición y la clave queda vacía al
terminar (no restaura el valor ajeno). La prueba lo afirma. El atributo de la petición no se retira: es un objeto
de la propia petición y no sobrevive a ella; no hay nada que afirmar.

| Aserción | Prueba | Ruptura deliberada y mensaje exacto |
|---|---|---|
| (a) MDC vacío tras una cadena correcta, tras una que lanza y con un valor ajeno previo | `theLogContextIsEmptyAfterASuccessfulChain`, `theLogContextIsEmptyAfterAChainThatThrows`, `aForeignValueUnderTheKeyIsReplacedForTheRequestAndGoneAfterwards` | Quitar `MDC.remove(MDC_KEY)` del `finally`: `Tests run: 8, Failures: 3`; `expected: null but was: "1b1942de-..."` (las dos primeras) y `[the filter's id does not remain] expected: null but was: "a8c0a709-..."` (la del valor ajeno). |
| (b) Dentro de la cadena, MDC y atributo son el mismo UUID | `insideTheChainTheLogContextAndTheRequestAttributeHoldTheSameServerUuid` | Cubierta además por `RequestContextFilterTest` (cadena real); sin ruptura propia. |
| (c) Respuesta confirmada: relanza la misma instancia y no escribe cuerpo | `aCommittedResponseGetsTheSameExceptionBackAndNoBody` (`flushBuffer()` antes de lanzar) | Sustituir `response.isCommitted()` por `false`: `Tests run: 8, Failures: 1`; `Expecting actual: java.lang.IllegalStateException: Cannot reset buffer - response is already committed` (se esperaba la instancia lanzada por la cadena). |
| (d) Orden `HIGHEST_PRECEDENCE + 10`, mayor que el de `SecurityHeadersFilter` y menor que -100 (cadena de Spring Security) | `itRunsRightAfterTheSecurityHeadersFilterAndAheadOfTheSecurityChain` | `HIGHEST_PRECEDENCE + 10` por `HIGHEST_PRECEDENCE`: `Tests run: 8, Failures: 1`; `expected: -2147483638 but was: -2147483648`. |
| (e) El 500 no lleva el mensaje ni la clase y el registro recibe la excepción | `anUncommittedResponseBecomesAnInternalErrorWithNothingInternalInIt`, `theFullExceptionGoesToTheServerLogAndNotToTheClient` | Sin ruptura pedida; verdes sobre el filtro real. |

Cada ruptura se revirtió y `cmp` contra la copia original no dio diferencias. Las pruebas se escribieron sobre un
filtro que ya existía, de modo que su ROJO es la ruptura, no la ausencia de código.

**I1:** `theLogContextOfOneRequestNeverCarriesTheIdOfThePreviousOne` pasaba sin `MDC.remove`; se renombró
`eachRequestSeesItsOwnIdInTheLogContext`, que es lo que prueba. La retirada la prueba el unitario.

**I2:** la brecha de Tomcat (`/x%2f`, `/x%00` y `TRACE`) queda asignada a la tarea 2.2, con una nota fechada
2026-10-04 en `tasks.md` (bajo 2.2) y en `design.md`. Observación: el sondeo de 2.1c no vio la cabecera `Server`
en esas tres respuestas (llegaron `connection`, `content-language`, `content-length`, `content-type`, `date` y
`Allow` en el 405); 2.2 debe afirmar su ausencia en lugar de darla por supuesta.

**Cobertura de los dos archivos no creados** (el propietario no aprobó de forma explícita omitirlos):

| Escenario | Prueba que lo cubre |
|---|---|
| El cliente envía su propio identificador (ignorado, `traceparent` incluido) | `RequestContextFilterTest.theServerIgnoresTheIdentifiersTheClientSends` |
| Respuesta de la cadena de seguridad con identificador del servidor | `RequestContextFilterTest.aResponseOfTheChainBeforeAnyControllerStillCarriesTheServerId` |
| Cada petición tiene su identificador | `RequestContextFilterTest.everyRequestGetsItsOwnId` |
| El `traceId` del último recurso es el que vio el controlador (atributo y MDC) | `RequestContextFilterTest.theTraceIdOfAnErrorIsTheIdTheControllerSawInTheRequestAndInTheLogContext` |
| El último recurso no filtra nada y conserva las cabeceras base | `RequestContextFilterTest.theLastResortAnswersWithoutAnythingInternalAndKeepsTheSecurityHeaders` y `RequestContextFilterUnitTest.anUncommittedResponseBecomesAnInternalErrorWithNothingInternalInIt` |
| Cabeceras base en respuesta de error | `AdminSecurityChainTest.anErrorResponseCarriesTheBaseSecurityHeaders` |
| Cabeceras base en respuesta de éxito | `AdminSecurityChainTest.aSuccessResponseCarriesTheSameBaseSecurityHeaders` |
| Ninguna respuesta crea sesión ni `Set-Cookie` | `AdminSecurityChainTest.noResponseCreatesASessionOrSetsACookie` y `StatelessChainTest` |
| La cadena de consulta no aparece en `instance` | `AdminSecurityChainTest.theQueryStringNeverAppearsInTheInstance` |
| Idioma fijo | `AdminSecurityChainTest.theLanguageIsFixedWhateverAcceptLanguageSays` |

**Cierre:** `./mvnw verify` completo: Surefire 186 + 535 (los 527 anteriores más 8), Failsafe 225, `BUILD SUCCESS`.
La instantánea OpenAPI y `routes` siguen sin cambios.

### Excepción de tamaño del PR 5 (2026-10-04)

Con la corrección del bloqueante B1, el PR 5 mide 885 líneas efectivas frente al presupuesto de 800.
El propietario aprobó una **excepción de tamaño de 85 líneas** para entregarlo en un solo PR: la prueba
unitaria que cierra el bloqueante viaja con el filtro que cubre, y el cambio se mantiene en 14 de 15
tareas. No se recortaron pruebas ni comentarios.

## Tarea 2.2a: PR 6a `container-rejections`

Rama `change/web-edge-foundations-edge-gates`, desde `main` en `9627fb0`. La tarea 2.2 original se implementó y verificó
completa (Surefire 186 + 585, Failsafe 225) y midió **1 111 líneas** efectivas (1 106 adiciones y 5 eliminaciones,
igual con y sin `-M`) frente al tope de 800. El propietario aprobó partirla en 2.2a y 2.2b. El árbol completo y
verificado está en la rama **local** `wip/web-edge-edge-gates-full` (commit `fee587f`, 21 archivos), que nunca se
publica y es la fuente de 2.2b. Conteo: 15 tareas y 14 PR (dentro del máximo de 15; la excepción concedida por el
propietario no se ejerce). Los PR se llaman 6a y 6b y los PR 7 a 13 conservan su número.

### Evidencia del ciclo TDD (obtenida sobre el árbol completo, antes de la partición)

| Paso | Observado |
|---|---|
| ROJO, brecha de Tomcat | `ContainerRejectionsTest` (admin y portal reales, sin válvula): `Tests run: 11, Failures: 6, Errors: 3`; `Expecting actual: "text/html;charset=utf-8" to start with: "application/problem+json"` en las seis combinaciones de `/x%2f`, `/x%00` y `TRACE`, y `Unexpected character ('<' ...)` al leer el cuerpo HTML como JSON en las tres del cuerpo. |
| VERDE | Con la válvula: `ContainerRejectionsTest` 12/12 (incluye una línea de petición de 70 000 caracteres: cuerpo y respuesta con el mismo estado), `ProblemErrorReportValveTest` 10/10, `ProblemCodeTest` en verde. Un primer VERDE falló una aserción mía (`TRACE` aparecía en el cuerpo por un `doesNotContainIgnoringCase`); se corrigió a la forma exacta. |
| S6 | `ProductionEdgeDefaultsTest` 6/6: producción trae springdoc apagado y `log-request-details` falso; `SPRINGDOC_API_DOCS_ENABLED` abre las dos entradas; con `--springdoc.api-docs.enabled=true` el documento responde 200 sin credencial en ambos procesos y todo lo demás sigue en 401. Un primer intento falló una aserción mía (`contains` para el valor `false`) y se corrigió. |
| Cierre sobre 2.2a sola | `./mvnw verify` completo en esta rama: Surefire 186 + 565 (los 535 de la línea base más 30 nuevos), Failsafe 225, `BUILD SUCCESS`. La instantánea OpenAPI y `apps/api/routes` (que aún no existe) sin cambios. |

### Demostraciones deliberadas (cada una revertida; `cmp` sin diferencias)

| Ruptura | Resultado |
|---|---|
| Personalizador que no instala la válvula (`return factory -> { };`) | `ContainerRejectionsTest` `Tests run: 12, Failures: 6, Errors: 4`; `Expecting actual: "text/html;charset=utf-8" to start with: "application/problem+json"`. |
| La válvula no llama a `SecurityHeadersFilter.apply` | `ContainerRejectionsTest` `Tests run: 12, Failures: 7`; `Expecting actual: ... to contain exactly (and in same order)` sobre las cabeceras base (no se capturó el valor exacto del mensaje). |

### Decisiones y desviaciones (declaradas)

1. **Estado de la respuesta de la válvula.** No conserva el estado del contenedor cuando el catálogo no lo tiene: 405 es
   `method-not-allowed`, todo otro 4xx es `400 validation-failed` y todo 5xx `500 internal-error`; el cuerpo y la
   respuesta coinciden siempre (RFC 9457). Nota fechada en `design.md`.
2. **Código nuevo `method-not-allowed` (405)** con su entrada es-HN; la decisión 8 decía que el 405 no tenía productor.
3. **`instance` es `/`** en lo que rechaza el contenedor: la ruta nunca se decodificó y no se repite.
4. Esta parte no necesitó nada de 2.2b para pasar. `ProductionEdgeDefaultsTest` afirma `spring.mvc.log-request-details`
   falso, que ya es el valor por omisión de Spring Boot, y la línea explícita de `application.yml` viaja en 2.2a. Los
   ayudantes de `OpenApiProcess` que 2.2b también usa (`port()`) viajan aquí.
5. La nota fechada de `design.md` (decisiones 7, 8 y 22) entra ya en 2.2a; sus párrafos cuarto y quinto (el prefijo
   `org.apache.tomcat.util.http` y el control negativo de la lista blanca) describen trabajo de 2.2b.

### Medición del PR 6a (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 386 | 5 | **391** |
| Con `-M` | 386 | 5 | **391** |

Pronóstico: ~395. Dentro del tope de 800. Tareas: 15 en total; hechas 6 de 15 (1.1, 1.2, 2.1a, 2.1b, 2.1c, 2.2a).

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='ContainerRejectionsTest,ProblemErrorReportValveTest,ProblemCodeTest,ProductionEdgeDefaultsTest,ProblemCatalogCoverageTest,ProcessBeanIsolationTest'`; cierre por `./mvnw verify`: Surefire 186 + 565, Failsafe 225 |
| Arnés de ejecución | Procesos administrativo y portal reales por `ConfiaApplication.launch` con su configuración de producción |
| Frontera de reversión | Se retiran `ProblemErrorReportValve`, su personalizador en `WebEdgeConfiguration`, `METHOD_NOT_ALLOWED` con su catálogo, `writeWithoutRequestPath`, `SecurityHeadersFilter.apply` y las pruebas |

### Revisión independiente de 2.2a y corrección (2026-10-04)

Veredicto: sin bloqueantes. **I1, I2 e I3** (la válvula perdía el estado original del contenedor y respondía 401 y 403
como 400) quedan resueltos por la decisión del propietario «asignar por catálogo y registrar», aplicada en un solo
commit `fix(web)`:

- `codeFor`: 401 a `authentication-required`, 403 a `forbidden`, 405 a `method-not-allowed`, todo otro 4xx a `400
  validation-failed`, todo 5xx a `500 internal-error` (un 503 sigue respondiéndose 500 hasta `capacity-exceeded`, 3.x).
- Cada rechazo respondido deja un evento `INFO` fijo (`container rejection answered: status=…, method=…, traceId=…`)
  con el estado original, el método (solo si es estándar) y el `traceId` del cuerpo; la rama sin a quién responder deja
  `container rejection could not be answered: the response was closed` (S1). Nunca ruta, consulta, cabeceras ni
  excepción. `INFO` y no `WARN`: es obra del cliente y deja una línea corta por petición.
- **S2 resuelta**: `ProblemErrorReportValveLogTest` prueba la instalación sobre un contenedor que no es `StandardHost`
  (y con padre ausente) y la rama sin a quién responder, con Mockito.
- **S5 resuelta**: la fila de trazabilidad «Documentación de API con perfil `local`» deja de citar 2.2a (la cubre 2.1b).
- **S4 pendiente**, asignada al cambio de despliegue: una verificación de CI de que ningún manifiesto de producción fija
  `SPRINGDOC_*`.

Evidencia: ROJO (`ProblemErrorReportValveTest`, `ProblemErrorReportValveLogTest`, `ContainerRejectionsTest`): `Tests
run: 32, Failures: 9` (401 y 403 respondidos como 400, y cero eventos en lugar de uno). VERDE: las tres clases en
verde. Hallazgo: Tomcat informa `400` y no `414` para una línea de petición de 70 000 caracteres, ni `431` para una
cabecera de 70 000, así que el estado original de esas dos ya es el del catálogo; la prueba del estado reescrito usa un
`Expect` desconocido por socket, que el contenedor informa como `417` y se responde `400` (evento con `status=417`).

| Ruptura | Resultado (cada una revertida; `cmp` sin diferencias) |
|---|---|
| `codeFor` responde 401 con `validation-failed` | `ProblemErrorReportValveTest` `Tests run: 14, Failures: 2`: `expected: "authentication-required" but was: "validation-failed"` y `expected: 401 but was: 400`. |
| Quitar la llamada de registro de la válvula | `ContainerRejectionsTest` `Tests run: 16, Failures: 4`: `Expected size: 1 but was: 0` (los tres rechazos y el del estado reescrito). |

## Tarea 2.2b: PR 6b `edge-gates`

Rama `change/web-edge-foundations-edge-gates`, desde `main` en `04f14cd` tras fusionar el PR 6a. El código sale de la
rama local `wip/web-edge-edge-gates-full` (sin tocarla; sigue en `fee587f`). `main` es la autoridad de todo lo ya
fusionado en 2.2a: `ProblemErrorReportValve` (con el mapeo por catálogo y el registro), `ProblemCode`,
`ProblemResponses`, `SecurityHeadersFilter`, `ContainerRejectionsTest`, `ProblemErrorReportValveTest`,
`ProductionEdgeDefaultsTest` y `OpenApiProcess` no se tocaron; de la copia de respaldo solo se portó lo propio de 2.2b.

### Evidencia del ciclo TDD (reobservada sobre este árbol)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO 1 | `-Dtest='PublicRouteAllowListTest,PortalRouteMapSnapshotTest,SensitiveDataLoggingTest'` con las pruebas, el arnés y `RegisteredRoutes`, sin producción | `COMPILATION ERROR`: `package SensitiveLogGuard does not exist` y `cannot find symbol` (`SensitiveDataLoggingTest.java` líneas 65, 130 a 158). |
| ROJO 2 | Igual, con la clase `SensitiveLogGuard` pero sin su bean ni la instantánea | `Tests run: 20, Failures: 2, Errors: 3`. `PortalRouteMapSnapshotTest` `Tests run: 6, Failures: 1, Errors: 2`: `no approved snapshot at ..\routes\portal.routes.json yet. Generated map, to review and commit:` y `NoSuchFile ..\routes\portal.routes.json` en las dos que leen el archivo. `SensitiveDataLoggingTest` `Tests run: 4, Failures: 1, Errors: 1`: `Expecting empty but was: ["org.apache.coyote.http11.Http11InputBuffer DEBUG message contains SECRETO-A", ...SECRETO-B, ...SECRETO-C ...]` y `NoSuchBeanDefinition ... SensitiveLogGuard`. `PublicRouteAllowListTest` 10/10 en verde (ya había `PublicEndpoints`; sus controles son las rupturas de abajo). |
| VERDE | `portal.routes.json` con `{"process": "portal", "routes": []}` y el bean `sensitiveLogGuard` en `WebEdgeConfiguration`; con `ProcessBeanIsolationTest`, `ContainerRejectionsTest` y `SpringModulithVerificationTest` | `PortalRouteMapSnapshotTest` 6, `PublicRouteAllowListTest` 10, `SensitiveDataLoggingTest` 4, `ProcessBeanIsolationTest` 4, `ContainerRejectionsTest` 16, `SpringModulithVerificationTest` 2: `Tests run: 42, Failures: 0, Errors: 0, Skipped: 0` (el `BUILD FAILURE` de esa ejecución es la cobertura de JaCoCo con `-Dtest=` acotado). |
| Cierre | `./mvnw verify` completo | Surefire 186 + 595 (los 575 de la línea base más 20 nuevos), Failsafe 225, `BUILD SUCCESS`. La instantánea OpenAPI y `apps/api/routes/portal.routes.json` sin cambios (`git status` limpio). |

Pruebas añadidas: 20 (`PublicRouteAllowListTest` 10, `PortalRouteMapSnapshotTest` 6, `SensitiveDataLoggingTest` 4).
`SensitiveDataLoggingTest` usa los marcadores `SECRETO-A`, `SECRETO-B` y `SECRETO-C`, un `ListAppender` en la raíz con
la raíz y los cinco prefijos en `TRACE`, y revisa cada evento de cualquier nivel (mensaje formateado, plantilla,
argumentos, MDC y traza de pila). El control negativo (un evento registrado a propósito) debe producir exactamente las
siete detecciones esperadas; otra prueba quita la guardia y exige que los secretos sí lleguen al registro.

### Demostraciones deliberadas (cada una revertida; `cmp` sin diferencias)

| Ruptura | Resultado |
|---|---|
| Un controlador temporal `GET /api/v1/temp-break` en el portal (importado desde `PortalApplication`) | `PortalRouteMapSnapshotTest` `Tests run: 6, Failures: 1`: `the portal route map differs from its approved snapshot ..\routes\portal.routes.json (added: GET /api/v1/temp-break)`. `PublicRouteAllowListTest` también falla (`Failures: 1`: producción no registra ninguna ruta en el portal). |
| Quitar `@Bean` de `sensitiveLogGuard` | `SensitiveDataLoggingTest` `Tests run: 4, Failures: 1, Errors: 1`: `Expecting empty but was: ["org.apache.coyote.http11.Http11InputBuffer DEBUG message contains SECRETO-A", ...]` y `NoSuchBeanDefinition ... SensitiveLogGuard`. |
| Una entrada `GET /v3/api-docs/unapproved` añadida a `PublicEndpoints` fuera de la lista aprobada | `PublicRouteAllowListTest` `Tests run: 10, Failures: 4`: `Expecting empty but was: [PublicEndpoint[method=GET, pattern=/v3/api-docs/unapproved]]` (producción) y `Expecting empty but was: ["GET /v3/api-docs/unapproved is allow-listed and no route serves it"]` (`local`). |

### `-Dconfia.routes.update=true`

`PortalRouteMapSnapshotTest` con la propiedad (y `-DargLine` para que llegue al proceso de pruebas): `Tests run: 6,
Failures: 1`: `..\routes\portal.routes.json was rewritten because -Dconfia.routes.update=true is set; review the diff,
commit it and run again without the property`. **Esa ejecución sí reescribió el archivo** (con formato multilínea); se
restauró el contenido exacto y se confirmó con `cmp` contra el blob de `wip/web-edge-edge-gates-full` y con `git status`
limpio. La instantánea OpenAPI no cambió.

### Desviaciones del diseño (declaradas)

Ninguna. Sin cambios en `ProcessBeanPolicy`: `shared.web.request` y `shared.web.edge` ya estaban permitidos desde 2.1a.

### Medición del PR 6b (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 720 | 0 | **720** |
| Con `-M` | 720 | 0 | **720** |

Pronóstico: ~716. Dentro del tope de 800. Tareas: 15 en total; hechas 7 de 15 (1.1, 1.2, 2.1a, 2.1b, 2.1c, 2.2a, 2.2b).

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='PublicRouteAllowListTest,PortalRouteMapSnapshotTest,SensitiveDataLoggingTest,ProcessBeanIsolationTest,ContainerRejectionsTest,SpringModulithVerificationTest'`: `Tests run: 42, Failures: 0`; cierre por `./mvnw verify`: Surefire 186 + 595, Failsafe 225 |
| Arnés de ejecución | Procesos administrativo y portal reales (perfil por omisión y `local`) con petición anónima a cada ruta enumerada, y el arnés sin base de datos para los controles negativos y las tres peticiones con secretos |
| Frontera de reversión | Se retiran `SensitiveLogGuard` y su bean, `apps/api/routes/portal.routes.json`, `RegisteredRoutes` y las tres clases de prueba, y las adiciones de `HarnessProcess` |

### Revisión independiente de 2.2b y corrección (2026-10-04)

Veredicto: sin bloqueantes, cuatro hallazgos importantes. El propietario decidió corregir I-1, I-3 e I-4 en este PR y
mover I-2 al cambio 9. Una sola corrección, en el commit `fix(web)`.

| Hallazgo | Resolución | Evidencia observada |
|---|---|---|
| I-1a: jOOQ registra las sentencias con los valores enlazados en `DEBUG` | `Settings().withExecuteLogging(false)` en el `DSLContext` de `SharedPlatformConfiguration`; `SqlLoggingTest` (conexión simulada de jOOQ, sin base de datos) con un control: un `DSLContext` por omisión sí filtra `SECRETO-E` | ROJO: `Tests run: 2, Failures: 1`, `Expecting value to be false but was true`. Ruptura (quitar el ajuste): `Failures: 1`, `Expecting no elements of: "-> with bind values      : select 'SECRETO-E'", "Binding variable 1       : SECRETO-E (varchar /* java.lang.String */)"`. Revertida (`cmp`). |
| I-1b: la cadena de consulta llegaba al registro por Spring Security | Prefijos `org.springframework.security` y `org.springframework.web.servlet.DispatcherServlet` en `SensitiveLogGuard`; `SensitiveDataLoggingTest` envía `?token=SECRETO-D` a una ruta aceptada y a una denegada | ROJO y ruptura (quitar los dos prefijos): `Tests run: 4, Failures: 1`, `Expecting empty but was: ["org.springframework.security.web.FilterChainProxy DEBUG message contains SECRETO-D", "...FilterChainProxy DEBUG message template contains SECRETO-D", "...RequestMatcherDelegatingAuthorizationManager TRACE message contains SECRETO-D", ...]`. Revertida (`cmp`). |
| I-1c: Javadoc y decisión 22 prometían de más | Javadoc de la guardia y nota fechada en `design.md` con lo cubierto, lo preventivo y lo no cubierto | Lectura del diff |
| I-3: `withoutTheGuard...` solo exigía `isNotEmpty()` | Afirma por secreto: A y C por `org.apache.coyote`, B por `org.apache.tomcat.util.http`, D por `org.springframework.security`. **Opción barata:** el arnés no tiene un endpoint con `@RequestBody`; los prefijos de conversores (y `tomcat.util.net`, `DispatcherServlet`, `mvc.method.annotation`) quedan declarados **preventivos, no ejercidos en vivo**, en el Javadoc y en `design.md` | Verde (4/4) |
| I-4: un enrutador funcional era una entrada opaca que la comprobación anónima no alcanzaba | `PublicRouteAllowListTest.unenumerableRoutes` falla en cualquier perfil con `(functional router) is registered: functional routes must be enumerated before they are allowed`; `RegisteredRoutes` usa `BeanFactoryUtils.beansOfTypeIncludingAncestors`; control permanente con un contexto que tiene un `RouterFunction` | Ruptura (bean `RouterFunction` temporal en `WebEdgeConfiguration`): `Tests run: 11, Failures: 4`, `Expecting empty but was: ["(functional router) is registered: functional routes must be enumerated before they are allowed"]` y `Expecting empty but was: [ANY (functional router)]`. Revertida (`cmp`). |
| I-2: el trabajador futuro con `DataSource` sin protección de registros | **Diferido al cambio 9** como cuarta condición dura de aceptación en `foundations-plan/exploration.md` (el trabajador prohíbe `shared.web`: la protección debe llegar por una configuración importable) | Lectura del diff |

Sugerencias, como seguimientos (no se implementan: no caben en el presupuesto o no son baratas): **S-1** negarse a
reescribir la instantánea cuando `CI` está definida y añadir CODEOWNERS para `apps/api/routes/`; **S-2** afirmar que la
guardia sigue en la lista de filtros de Logback tras el arranque (hoy solo se afirma al inicio de la prueba sin guardia);
**S-3** una prueba a nivel `INFO` (que sigue pasando).

### Excepción de tamaño del PR 6b (2026-10-04)

Con la corrección de la revisión, el PR 6b mide 902 líneas efectivas (901 adiciones y 1 eliminación) frente al
presupuesto de 800. El propietario aprobó una **excepción de tamaño de unas 102 líneas** para entregarlo en un solo PR,
como el PR 5. No se recortaron pruebas ni comentarios.

## Partición de la tarea 2.3 (decisión del propietario, 2026-10-05)

La tarea 2.3 original (PR 7 `request-origin`) se implementó y verificó completa (Surefire 186 + 712, Failsafe 228,
`BUILD SUCCESS`) y midió **1 869 líneas** efectivas (1 831 adiciones y 38 eliminaciones, igual con y sin `-M`) frente al
tope de 800 y a un pronóstico de unas 500. El propietario aprobó partirla en 2.3a, 2.3b, 2.3c y 2.3d y concedió una
excepción al tope de 15 tareas (ahora 18 tareas y 17 PR). El árbol completo y verificado está en la rama **local**
`wip/web-edge-request-origin-full` (commit `9e8b52f`, 31 archivos), que nunca se publica y es la fuente de las cuatro
partes. Evidencia del árbol completo, válida para 2.3b a 2.3d:

- ROJO 1 de compilación (clases inexistentes); ROJO 2 `Tests run: 123, Failures: 11, Errors: 2` (`ScopedValue.orElse(null)`
  lanza `NullPointerException` en JDK 25: `RequestOrigin.current()` usa `isBound()`); ROJO 3 `Failures: 1` (las propiedades
  por omisión pierden contra `application.yml`: el arnés las pasa por línea de comandos); VERDE `Tests run: 123, Failures: 0`.
- Decorador: `Tests run: 5, Failures: 0`; IT en rojo con `expected: "203.0.113.9" but was: null`; línea de política en rojo
  con `process 'admin': bean 'auditLogWriter' from package 'com.confia.shared.audit' - not in the allow-list`; verde
  `Tests run: 22, Failures: 0` y la IT `Tests run: 3, Failures: 0`.
- Rupturas: el resolvedor que confía siempre en `X-Forwarded-For` dio `Tests run: 60, Failures: 7` (propiedad:
  `Expecting actual: Optional[ClientAddress[address=/10.0.0.0]] to contain: ClientAddress[address=/11.7.7.7]`); quitar la
  envoltura del escritor dio `RequestOriginAuditIT` `Failures: 2` (`expected: "203.0.113.9" but was: null`).
- Desviaciones del árbol completo (notas fechadas en `design.md`): `ofLiteral` acepta `1`, `127.1`, `1.2.3`, `010.0.0.1` y
  `[::1]`; `ClientKey` nuevo; pruebas `*PropertiesTest`; `Optional` y `clientAddress` nulo; rango sobre IPv6 mapeada rechazado.

## Tarea 2.3a: PR 7a `client-address`

Rama `change/web-edge-foundations-request-origin`, desde `main` en `9d6e5f5`. El código sale de la rama local
`wip/web-edge-request-origin-full` (sin tocarla; sigue en `9e8b52f`).

### Evidencia del ciclo TDD (reobservada sobre este árbol)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO | `-Dtest='CidrBlock*,ClientAddress*'` con las dos pruebas y sin producción | `COMPILATION ERROR`: 102 errores `cannot find symbol` (`ClientAddress`, `CidrBlock`). Causa prevista. |
| VERDE | Igual, con `ClientAddress`, `ClientKey` y `CidrBlock` | `Tests run: 53, Failures: 0, Errors: 0, Skipped: 0`: `ClientAddressPropertiesTest` 28 (+4 de jqwik) y `CidrBlockPropertiesTest` 18 (+3). |
| Primer cierre | `./mvnw verify` | Surefire 186 + 651 con `Failures: 1`: `IdempotencyScopeExclusionInventoryTest.noWebPackageClassDependsOnSharedSecurityAndNoProductionClassMentionsTheHeaderLiteral`: `com.confia.shared.web.request.CidrBlock resides in a web package and must not depend on com.confia.shared.security ... (brecha con destino: cambio 7)`. |
| Cierre | `./mvnw verify` completo tras mover a esta parte el ajuste de esa prueba (nombra los tipos de idempotencia en lugar de todo `shared.security`) | Surefire 186 + 651 (los 598 de la línea base más 53 nuevos), Failsafe 225, `BUILD SUCCESS`. Instantánea OpenAPI y `routes` sin cambios (`git status` limpio). |

### Demostraciones deliberadas (cada una revertida; `cmp` sin diferencias)

| Ruptura | Resultado |
|---|---|
| `ClientAddress.rateLimitKey` usa los 16 bytes de una IPv6 en lugar de 8 | `ClientAddressPropertiesTest` `Tests run: 28, Failures: 1` y `Tests run: 4, Failures: 1`: `expected: ClientKey[20010db800010002ffffffffffffffff] but was: ClientKey[20010db8000100020000000000000001]`. |
| `CidrBlock.contains` ignora los bits parciales del último byte (`int mask = 0xFF`) | `CidrBlockPropertiesTest` `Tests run: 3, Failures: 2` (las dos propiedades de `BigInteger`, IPv4 e IPv6): `expected: true but was: false`; los 18 ejemplos pasan, así que solo las propiedades la detectan. |

### Prueba que necesitó una parte posterior

`IdempotencyScopeExclusionInventoryTest` (a) prohibía toda dependencia de una clase `web` hacia `shared.security`. Estaba
planificada en 2.3c (con el filtro), pero `CidrBlock` es una clase `web` y ya depende de `ClientAddress`: se movió a 2.3a.
`tasks.md` lo refleja (2.3a 513 líneas, 2.3c 381).

### Desviaciones del diseño (declaradas)

Las de la nota fechada de `design.md` (2026-10-05) que atañen a esta parte: `parseLiteral` más estricto que `ofLiteral`, `ClientKey`
nuevo, `*PropertiesTest` y rango sobre IPv6 mapeada rechazado. Ninguna otra.

### Medición del PR 7a (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 502 | 11 | **513** |
| Con `-M` | 502 | 11 | **513** |

Dentro del tope de 800. Tareas: 18 en total; hechas 8 (1.1, 1.2, 2.1a, 2.1b, 2.1c, 2.2a, 2.2b, 2.3a).

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='CidrBlock*,ClientAddress*,IdempotencyScopeExclusionInventoryTest'`; cierre por `./mvnw verify`: Surefire 186 + 651, Failsafe 225 |
| Arnés de ejecución | N/A: tipos puros sin frontera de ejecución; jqwik contra referencias independientes con `BigInteger` |
| Frontera de reversión | Se retiran `ClientAddress`, `ClientKey`, `CidrBlock`, sus dos pruebas y el ajuste de `IdempotencyScopeExclusionInventoryTest` |

### Revisión independiente de 2.3a y corrección (2026-10-05)

Veredicto: sin bloqueantes, dos hallazgos importantes. Una sola corrección, en el commit `fix(web)` (`c16f129`).

| Hallazgo | Cambio | Evidencia observada |
|---|---|---|
| I-1: un IPv4 incrustado en IPv6 eludía la regla de ceros a la izquierda (`::ffff:010.0.0.1`, `::ffff:1.2.3.04`, `::ffff:00.0.0.1`, `::1.2.3.04`) | `parseLiteral`: con `:` y `.`, la cola tras el último `:` debe cumplir la regla estricta de cuatro partes decimales | ROJO: `ClientAddressPropertiesTest` `Tests run: 40, Failures: 5` (casos 25, 26, 28, 29 y el de zona): `Expecting code to raise a throwable.` (`::ffff:1.2.3` ya lo rechazaba el JDK). Ruptura (quitar la comprobación de la cola, `return true`): `Tests run: 36, Failures: 4`, casos 25, 26, 28 y 29 con `Expecting code to raise a throwable.`. Revertida (`cmp`). |
| I-2: la comprobación (a) de `IdempotencyScopeExclusionInventoryTest` era una lista negra por nombre simple (no veía clases anidadas ni `TransactionRunner`/`SecurityContext`) | Lista de permitidos por nombre completo, `WEB_MAY_DEPEND_ON_SHARED_SECURITY` = `ClientAddress` y `ClientKey`; 2.3c solo añade `RequestOrigin`. El literal `Idempotency-Key` sigue igual. Javadoc explicado | Ruptura: clase temporal `com.confia.shared.web.request.TempLeak` que referencia `IdempotentOutcome.Executed`: `com.confia.shared.web.request.TempLeak resides in a web package and depends on com.confia.shared.security.IdempotentOutcome$Executed, which is not one of [com.confia.shared.security.ClientKey, com.confia.shared.security.ClientAddress] (brecha con destino: cambio 7)`; con `TransactionRunner`: `... depends on com.confia.shared.security.TransactionRunner, which is not one of [...]`. Clase retirada. |
| S-1: rechazar `%` | `parseLiteral` rechaza toda zona; la prueba de zona ignorada pasa a rechazo (`fe80::1%1`, `fe80::1%eth0`, `1.2.3.4%1`) | Dentro del ROJO de I-1 |
| S-4 | `everyTextualFormOfOneAddressGivesTheSameKeyAndTheSameAddress`: comprimida, mayúsculas, expandida, `::ffff:a.b.c.d`, `::ffff:0102:0304` y `0:0:0:0:0:ffff:102:304` | Verde desde el principio (documenta el comportamiento) |
| S-5 | La propiedad del /64 compara con los bytes **generados** (`new BigInteger(1, prefix)`) | `Tests run: 36` en verde |

Cierre: `./mvnw verify` completo: Surefire 186 + 659 (651 más 8), Failsafe 225, `BUILD SUCCESS`. Medición del PR 7a tras la corrección
(`git diff --numstat main...HEAD -- . ':!openspec'`, igual con y sin `-M`): 531 adiciones, 10 eliminaciones, **541** en total (tope 800).

**Seguimientos, no implementados:** **S-2** `CidrBlock` enmascara en silencio los bits de host (`10.0.0.5/8` se acepta): decidir en 2.3b,
dueña de la propiedad, si la lista de proxies de confianza debe rechazarlo. **S-3** las direcciones NAT64 y las IPv4-compatibles
(`::a.b.c.d`) comparten un único cubo /64 en `rateLimitKey`: decidir en 3.1, dueña del limitador.

## Tarea 2.3b: PR 7b `trusted-proxy-resolution`

Rama `change/web-edge-foundations-trusted-proxies`, desde `main` en `24a4d7f` tras fusionar el PR 7a. El código sale de
`wip/web-edge-request-origin-full` (sin tocarla; sigue en `9e8b52f`). `main` es la autoridad de lo de 2.3a: `CidrBlock`,
`ClientAddress` y `IdempotencyScopeExclusionInventoryTest` no se portaron; solo se añadió `parseWithoutHostBits`.

### Evidencia del ciclo TDD (reobservada sobre este árbol)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO 1 | `-Dtest='ClientAddressResolverTest,WebEdgePropertiesTest'` con las dos pruebas y sin producción | `COMPILATION ERROR`: `cannot find symbol` (`ClientAddressResolver`, `TrustedProxies`, `WebEdgeProperties`). Causa prevista. |
| ROJO 2 (S-2) | Producción portada tal cual (con `CidrBlock.parse`), más las pruebas nuevas de bits de host | `ClientAddressResolverTest` `Tests run: 31, Failures: 0`; `WebEdgePropertiesTest` `Tests run: 25, Failures: 5`: `aRangeWithHostBitsSetStopsTheStart...(String)[1..5]`: `Expecting code to raise a throwable.` |
| VERDE | `CidrBlock.parseWithoutHostBits` usado por `TrustedProxies`; con `CidrBlock*`, `ClientAddress*` e `IdempotencyScopeExclusionInventoryTest` | `Tests run: 122, Failures: 0, Errors: 0, Skipped: 0` (ClientAddressResolverTest 31 + 2 de jqwik, WebEdgePropertiesTest 25, CidrBlockPropertiesTest 18 + 3, ClientAddressPropertiesTest 36 + 4, inventario 3). El `BUILD FAILURE` de esa ejecución es la cobertura de JaCoCo con `-Dtest=` acotado. |
| Cierre | `./mvnw verify` completo | Surefire 186 + 717 (los 659 de la línea base más 58 nuevos), Failsafe 225, `BUILD SUCCESS`. Instantánea OpenAPI y `routes` sin cambios (`git status` limpio de ellos). |

Pruebas añadidas: 58 (`ClientAddressResolverTest` 33 con las dos de jqwik, `WebEdgePropertiesTest` 25).

### Demostraciones deliberadas (cada una revertida; `cmp` sin diferencias)

| Ruptura | Resultado |
|---|---|
| El resolvedor confía siempre en `X-Forwarded-For` (`if (remote.isEmpty())` en lugar de `remote.isEmpty() \|\| !trusted.contains(remote.get())`) | `ClientAddressResolverTest` `Tests run: 33, Failures: 5` (cuatro ejemplos y la propiedad): `Expecting actual: Optional[198.51.100.7] to contain: "203.0.113.9"`; propiedad: `Expecting actual: Optional[ClientAddress[address=/10.0.0.0]] to contain: ClientAddress[address=/11.7.7.7]`. |
| S-2: `TrustedProxies` vuelve a `CidrBlock.parse` | `WebEdgePropertiesTest` `Tests run: 25, Failures: 5`: `Expecting code to raise a throwable.` en las cinco entradas con bits de host. |
| Un archivo `application-extra.yml` con `confia.web.trusted-proxies: 10.0.0.0/8` (fuera de la lista de perfiles conocidos) | `WebEdgePropertiesTest` `Tests run: 25, Failures: 1`: `[apps\api\app\src\main\resources\application-extra.yml] Expecting empty but was: ["trusted-proxies: 10.0.0.0/8"]`. Archivo retirado. |

### Decisión S-2 (seguimiento de la revisión de 2.3a)

Se rechaza: `TrustedProxies` usa `CidrBlock.parseWithoutHostBits`; el arranque falla nombrando `confia.web.trusted-proxies[i]` sin
repetir el valor. `CidrBlock.parse` no cambia (sus propiedades de 2.3a dependen del enmascarado). Nota fechada en `design.md`.

### Desviaciones del diseño (declaradas)

1. Rechazo de bits de host (S-2), por encargo de la revisión de 2.3a.
2. `WebEdgePropertiesTest` recorre todo el repositorio y no solo los cuatro perfiles conocidos (con control de no vacuidad).
3. Sin entradas nuevas en la lista de permitidos de `IdempotencyScopeExclusionInventoryTest`: los tipos nuevos solo dependen de `ClientAddress`.

### Medición del PR 7b (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 714 | 1 | **715** |
| Con `-M` | 714 | 1 | **715** |

Pronóstico 592; dentro del tope de 800. El exceso: la prueba de recorrido del repositorio, los casos de S-2 y `parseWithoutHostBits`. Tareas: 18 en total; hechas 9.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='ClientAddressResolverTest,WebEdgePropertiesTest,CidrBlock*,ClientAddress*,IdempotencyScopeExclusionInventoryTest'`: `Tests run: 122, Failures: 0`; cierre por `./mvnw verify`: Surefire 186 + 717, Failsafe 225 |
| Arnés de ejecución | `Binder` de Spring Boot con `SystemEnvironmentPropertySource` y los YAML reales del classpath; resolvedor con ejemplos y jqwik (referencia de aritmética entera) |
| Frontera de reversión | Se retiran `TrustedProxies`, `ClientAddressResolver`, `WebEdgeProperties`, `parseWithoutHostBits`, las dos pruebas, las dos claves de `application.yml` y la nota de `docs/05` |

## Tarea 2.3c: PR 7c `request-origin-filter`

Rama `change/web-edge-foundations-request-origin-filter`, desde `main` en `5d99ba3` tras fusionar el PR 7b. El código sale de
`wip/web-edge-request-origin-full` (sin tocarla; sigue en `9e8b52f`). `main` es la autoridad de lo de 2.3a y 2.3b: `ClientAddress`,
`CidrBlock`, `TrustedProxies`, `WebEdgePropertiesTest` y el inventario no se portaron; en el inventario solo se añadió la línea de
`RequestOrigin` a la lista de permitidos por nombre completo.

### Evidencia del ciclo TDD (reobservada sobre este árbol)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO 1 | `-Dtest='RequestContextFilter*,PublicRouteAllowListTest'` con las pruebas y el arnés, sin producción | `COMPILATION ERROR`: `cannot find symbol` (`RequestOrigin`) y `constructor RequestContextFilter ... cannot be applied to given types`. Causa prevista. |
| ROJO 2 | `RequestOrigin` y `WebEdgeConfiguration` portados y el constructor del filtro con `WebEdgeProperties`, sin ligar el origen ni leer el agente | `Tests run: 38, Failures: 6, Errors: 8`: `PublicRouteAllowListTest` 11 (`Failures: 1`, falta `/test/origin` en las fugas), `RequestContextFilterTest` 12 (`Failures: 5, Errors: 1`, el arnés no encuentra origen) y `RequestContextFilterUnitTest` 15 (`Errors: 7`, `NoSuchElement No value present`). |
| VERDE | Filtro portado, más `IdempotencyScopeExclusionInventoryTest`, `ProcessBeanIsolationTest` y `WebEdgePropertiesTest` | `Tests run: 70, Failures: 0, Errors: 0, Skipped: 0` (`PublicRouteAllowListTest` 11, `RequestContextFilterTest` 12, `RequestContextFilterUnitTest` 15, inventario 3, `ProcessBeanIsolationTest` 4, `WebEdgePropertiesTest` 25). El `BUILD FAILURE` de esa ejecución es la cobertura de JaCoCo con `-Dtest=` acotado. |
| Cierre | `./mvnw verify` completo | Surefire 186 + 730 (los 717 de la línea base más 13 nuevos), Failsafe 225, `BUILD SUCCESS`. Instantánea OpenAPI y `routes` sin cambios (`git status` limpio de ellos). |

Pruebas añadidas: 13 (`RequestContextFilterTest` +6, `RequestContextFilterUnitTest` +7). La prueba de 100 peticiones simultáneas con
`CyclicBarrier` afirma, por índice, la dirección y el agente propios de cada respuesta, que el identificador del atributo es el del origen, y
exactamente 100 identificadores distintos. Ninguna prueba lee lo que un hilo del servidor hace después de la respuesta, así que no hay
esperas que acotar.

### Demostraciones deliberadas (cada una revertida; `cmp` sin diferencias)

| Ruptura | Resultado |
|---|---|
| Un valor global (`static volatile`) en lugar de `ScopedValue`, escrito por el filtro y nunca limpiado | `RequestContextFilterTest` `Tests run: 12, Failures: 1` (`aHundredSimultaneousRequestsEachGetTheirOwnIdAddressAndAgent`): `expected: "198.51.100.1" but was: "198.51.100.5"` (cruce entre peticiones); `RequestContextFilterUnitTest` `Tests run: 15, Failures: 1`: `[nothing is bound once the filter returns] Expecting an empty Optional but was containing value: RequestOrigin[requestId=..., clientAddress=ClientAddress[address=/203.0.113.9], userAgent=agente-prueba]`. |
| No sustituir los caracteres de control del agente (`kept.append(c)`) | `RequestContextFilterUnitTest` `Tests run: 15, Failures: 1` (`controlCharactersInTheUserAgentAreReplacedBySpacesBeforeItIsKept`): `expected: "a b c d e f"` frente al valor con `\t`, `NUL`, `DEL` y `NEL` intactos. |

### Desviaciones del diseño (declaradas)

Ninguna. Sin cambios en `ProcessBeanPolicy`: `shared.web.request` y `shared.web.edge` ya estaban permitidos. `design.md` no cambia (la nota
del 2026-10-05 ya describe `RequestOrigin`, `isBound()` y el corte sin partir un par sustituto).

### Medición del PR 7c (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 360 | 24 | **384** |
| Con `-M` | 360 | 24 | **384** |

Pronóstico 381; dentro del tope de 800. Tareas: 18 en total; hechas 10.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='RequestContextFilter*,PublicRouteAllowListTest,IdempotencyScopeExclusionInventoryTest,ProcessBeanIsolationTest,WebEdgePropertiesTest'`: `Tests run: 70, Failures: 0`; cierre por `./mvnw verify`: Surefire 186 + 730, Failsafe 225 |
| Arnés de ejecución | Arnés HTTP sin base de datos (`/test/origin`, propiedades por línea de comandos) con 100 peticiones simultáneas y un socket crudo sin `User-Agent`; pruebas unitarias del filtro con `MockHttpServletRequest` |
| Frontera de reversión | Se retiran `RequestOrigin`, los cambios de `RequestContextFilter` y `WebEdgeConfiguration`, la línea de `IdempotencyScopeExclusionInventoryTest` y las ampliaciones de pruebas y arnés |

### Revisión independiente de 2.3c y corrección (2026-10-05)

Veredicto: sin bloqueantes, dos hallazgos importantes. Una sola corrección, en el commit `fix(web)`.

| Hallazgo | Cambio | Evidencia observada |
|---|---|---|
| I-1: `MDC.put` y la construcción del origen corrían antes del `try`; si fallaban, el identificador quedaba en el MDC de un hilo reutilizado y el último recurso no respondía | El atributo se fija primero; `MDC.put` y la construcción del origen pasan dentro del `try` | ROJO: `aFailureWhileBuildingTheOriginStillCleansTheLogContextAndGetsTheLastResort` (una petición cuyo `getHeader` lanza): `IllegalState jdbc:postgresql://host/db password=hunter2` escapa del filtro. Verde: la cadena no corre, MDC vacío, `500 internal-error`, `traceId` igual al atributo y sin el mensaje. |
| I-2: solo se sustituía `Character.isISOControl` | También `FORMAT`, `LINE_SEPARATOR`, `PARAGRAPH_SEPARATOR` y `CONTROL` por `Character.getType` (U+2028, U+2029, U+202A-202E, U+2066-2069, U+200B-200F, U+FEFF) | ROJO: `expected: "a b c d e f g" but was: "a?b?c?d?e?f?g"`. |
| S-1: un sustituto suelto sin truncar sobrevivía | Todo sustituto sin pareja (alto o bajo) pasa a espacio; el par válido se conserva | ROJO: `expected: "ab " but was: "ab` (más el caso del medio). El orden (cortar y luego sustituir) mantiene el límite y no parte un par: `replacingAfterTheCutKeepsTheLimitAndNeverSplitsAPair`. |
| S-2: `RequestOrigin` no se hereda en un ejecutor común | Línea de Javadoc: solo lo heredan las bifurcaciones de `StructuredTaskScope`. **Para 2.3d:** probar que el escritor de bitácora tolera un origen ausente (trabajo fuera del hilo de la petición) | Lectura del diff |
| S-3: segunda ronda de 100 peticiones | `aHundredSimultaneousRequestsEachGetTheirOwnIdAddressAndAgent` hace dos rondas contra el mismo servidor, con agentes distintos por ronda y 200 identificadores distintos | Verde |

Evidencia: ROJO `-Dtest='RequestContextFilter*'`: `Tests run: 19, Failures: 3, Errors: 1` (unitaria; la de integración 12/12). VERDE: 12 y 19, `Tests run: 31, Failures: 0`.

| Ruptura (cada una revertida; `cmp` sin diferencias) | Resultado |
|---|---|
| Solo `MDC.put` de nuevo sobre el `try` (la que pedía la revisión) | `Tests run: 19, Failures: 0`: **no falla**. `MDC.put` no puede lanzar y la construcción del origen sigue dentro del `try`; la ruptura no es observable. |
| Sustancial: `MDC.put` **y** la construcción del origen sobre el `try` (la estructura anterior) | `Tests run: 19, Errors: 1`: `aFailureWhileBuildingTheOriginStill...:172->run:73 IllegalState jdbc:postgresql://host/db password=hunter2` (el fallo escapa; no hay 500). |
| `Character.isISOControl` solo, en lugar de `isUnsafe` | `Tests run: 19, Failures: 3`: `expected: "ab " but was: "ab`, `expected: "a b c d e f g" but was: "a?b?c?d?e?f?g"` y `expected: "xxxxxxxxxxxxxxxxxxx " but was: "xxxxxxxxxxxxxxxxxxx?"`. |

Cierre: `./mvnw verify` completo: Surefire 186 + 734 (730 más 4), Failsafe 225, `BUILD SUCCESS`. Medición del PR 7c tras la corrección
(`git diff --numstat main...HEAD -- . ':!openspec'`, igual con y sin `-M`): 454 adiciones, 24 eliminaciones, **478** en total (tope 800).

## Tarea 2.3d: PR 7d `audit-origin`

Rama `change/web-edge-foundations-audit-origin`, desde `main` en `5bd1cce` tras fusionar el PR 7c. El código sale de
`wip/web-edge-request-origin-full` (sin tocarla; sigue en `9e8b52f`). `main` es la autoridad de lo de 2.3a a 2.3c: solo se portaron
`RequestOriginAuditLogWriter`, la envoltura, la línea de `ProcessBeanPolicy` y el Javadoc de `AuditEntry`; las dos pruebas se ampliaron.

### Evidencia del ciclo TDD (reobservada sobre este árbol)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO 1 | `-Dtest=RequestOriginAuditLogWriterTest` con las dos pruebas y sin producción | `COMPILATION ERROR`: `cannot find symbol: class RequestOriginAuditLogWriter`. Causa prevista. |
| VERDE del decorador | Igual, con el decorador portado y sin envolver | `RequestOriginAuditLogWriterTest` `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| ROJO 2 | `RequestOriginAuditIT` (Failsafe, Docker) con el decorador existente pero sin envolver | `Tests run: 4, Failures: 2`: `expected: "203.0.113.9" but was: null` y `expected: "198.51.100.1" but was: null` (la de 50 peticiones). Las pruebas de origen ausente pasan por construcción. |
| ROJO 3 | Con la envoltura en `SharedPlatformConfiguration` y sin la línea de política: `-Dtest=ProcessBeanIsolationTest` | `Tests run: 4, Failures: 1`: `process 'admin': bean 'auditLogWriter' from package 'com.confia.shared.audit' - not in the allow-list` |
| VERDE | `-Dtest='RequestOriginAuditLogWriterTest,ProcessBeanIsolationTest' -Dit.test=RequestOriginAuditIT` | Surefire `Tests run: 10, Failures: 0` (6 + 4); Failsafe `Tests run: 4, Failures: 0` |
| Cierre | `./mvnw verify` completo | Surefire 186 + 740 (los 734 de la línea base más 6), Failsafe 229 (225 más 4), `BUILD SUCCESS`. Instantánea OpenAPI y `routes` sin cambios (`git status` limpio de ellos). |

Pruebas añadidas: 6 unitarias (`RequestOriginAuditLogWriterTest`: el origen completa solo lo nulo, IPv6 canónica, un valor del llamador gana,
origen sin dirección o sin agente, **hilo sin vinculación (seguimiento S-2 de 2.3c)**, fuera de petición) y 4 de integración
(`RequestOriginAuditIT`: durante una petición, fuera de petición, **trabajo entregado a un ejecutor común durante una petición** y 50 peticiones
simultáneas con `CyclicBarrier`).

### Demostración deliberada (revertida; `cmp` contra la copia original sin diferencias)

| Ruptura | Resultado |
|---|---|
| Quitar la envoltura (`return new JooqAuditLogWriter(dsl)`) | `RequestOriginAuditIT` `Tests run: 4, Failures: 2`: `expected: "203.0.113.9" but was: null` y `expected: "198.51.100.1" but was: null`. |

### Cadena de hash y ausencia de esperas

`source_ip` y `user_agent` forman parte de la carga firmada: el disparador `BEFORE INSERT` los incluye en el hash
(`V3__chain_shared_audit_log.sql`, líneas 113 y 114), igual que `CanonicalAuditRowSerializer` (líneas 68 y 69), como exige
`openspec/specs/audit-trail/spec.md`. Corrección del orquestador (2026-10-05): la primera versión de esta nota decía que
`user_agent` no estaba firmado, lo cual era falso. El
decorador solo rellena campos antes de la inserción, así que el disparador firma el valor definitivo. La IT llama a `DefaultAuditChainVerifier`
sobre cada institución tras escribir y exige `Intact` con exactamente 1 fila (durante petición, fuera de petición, ejecutor común) y 50 filas
(simultáneas). Ninguna prueba lee filas escritas por un hilo del servidor tras la respuesta: la ruta ejecuta el `TransactionRunner` hasta el
commit antes de responder (en la ruta del ejecutor común, esperando el `Future`), así que no hay esperas que acotar. La prueba de 50 afirma
conteo exacto, el conjunto exacto de marcadores, 50 IP distintas y la IP y el agente propios de cada fila. Ni el decorador ni las pruebas
registran la IP ni el agente.

### Desviaciones del diseño (declaradas)

Ninguna. Se añaden a las pruebas del árbol completo la del hilo sin vinculación (unitaria e IT), la comprobación de la cadena de hash y las de conteo exacto.
`clientAddress` nulo ya estaba cubierto en la prueba unitaria `anOriginWithoutAnAddressOrAnAgentLeavesThoseFieldsNull`; la IT no puede
producirlo porque el contenedor siempre entrega un literal.

### Medición del PR 7d (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 467 | 4 | **471** |
| Con `-M` | 467 | 4 | **471** |

Pronóstico 383; dentro del tope de 800. Tareas: 18 en total; hechas 11.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='RequestOriginAuditLogWriterTest,ProcessBeanIsolationTest' -Dit.test=RequestOriginAuditIT`: Surefire 10/0 fallos, Failsafe 4/0; cierre por `./mvnw verify`: Surefire 186 + 740, Failsafe 229 |
| Arnés de ejecución | Servidor real en puerto aleatorio con PostgreSQL (Testcontainers), `SharedPlatformConfiguration` y el borde web de producción, `TransactionRunner` real, 50 peticiones simultáneas y verificador de cadena |
| Frontera de reversión | Se retiran `RequestOriginAuditLogWriter`, la envoltura en `SharedPlatformConfiguration`, la línea de `ProcessBeanPolicy`, el ajuste de Javadoc de `AuditEntry` y las dos pruebas: la auditoría vuelve a `null` |
## Tarea 2.4 completa: PR 8 `problem-translator` (detenida antes del commit: excedía el tope; el propietario aprobó la costura 2.4a + 2.4b)

Rama de trabajo `change/web-edge-foundations-problem-translator`, desde `main` en `a831dd3` tras fusionar el PR 7d. Se implementó la
tarea 2.4 completa y se verificó (`./mvnw verify`), pero midió **1 046 líneas** efectivas (1 029 adiciones y 17 eliminaciones, igual con y sin
`-M`) frente al tope de 800 y a un pronóstico de 700 a 1 000. Por instrucción del orquestador no se compromete en la rama de trabajo: el árbol
completo y verificado está en la rama **local** `wip/web-edge-problem-translator-full` (commit `7e61aff`, 19 archivos), que nunca se publica, y la
rama de trabajo vuelve a `main`, limpia. **La tarea 2.4 NO se marca `[x]`.**

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| Red de seguridad | `-Dtest='ProblemCodeTest,ProblemCatalogCoverageTest,ProblemResponsesTest,RequestContextFilterTest,OpenApiContractSnapshotTest,SensitiveDataLoggingTest'` sobre la rama sin cambios | `Tests run: 60, Failures: 0, Errors: 0, Skipped: 0` |
| ROJO 1 | `-Dtest='ProblemTranslationTest,ProblemExceptionHandlerTest,ProblemCodeTest,ProblemCatalogCoverageTest,ProblemResponsesTest,ResourceNotFoundTest,PublicRouteAllowListTest'` con las pruebas y el arnés, sin producción | `COMPILATION ERROR`: 11 errores `cannot find symbol` (`FieldViolation`, `ProblemExceptionHandler`) e `incompatible types` en la sobrecarga de `ProblemResponses.write`. Causa prevista. |
| ROJO 2 | Mismo comando, con `FieldViolation`, `ProblemBody.errors`, la sobrecarga de `write` y un `ProblemExceptionHandler` sin cuerpo ni registro, y sin los dos códigos | `Tests run: 84, Failures: 12, Errors: 12, Skipped: 0`: `ResourceNotFoundTest` 2 de 2 (el `404` de `/swagger-ui/**` acababa en el último recurso), `ProblemCodeTest` `Failures: 3, Errors: 2`, `ProblemCatalogCoverageTest` `Failures: 1`, `ProblemExceptionHandlerTest` `Errors: 5` (`UnsupportedOperationException`), `ProblemTranslationTest` `Failures: 6, Errors: 5` (`errors` ausente: `NullPointer ... JsonNode.get("errors") is null`; `415` respondido por la válvula como `400`; registro vacío). Las pruebas de cuerpo mal formado, cabecera ausente y tipo erróneo pasaban por la válvula del contenedor (con `instance` `/`); se les añadió la aserción de `instance` igual a la ruta, que solo cumple el traductor. |
| VERDE parcial | Con los dos códigos, el catálogo y el traductor registrado | `Tests run: 84, Failures: 2`: la ruta del portal bajo `/swagger-ui/` también es pública en `local` (404 y no 401: la hipótesis de la prueba era falsa, se corrigió la prueba) y el nombre del parámetro (`parameter`: el build no conserva los nombres) |
| VERDE | Nombre por la anotación de enlace (`@RequestParam`, `@RequestHeader`, `@PathVariable`) y la restricción como el prefijo antes del primer punto del último código (`Max.int` es `max`), más OpenAPI, aislamiento, `ContainerRejectionsTest`, `RequestContextFilterTest`, `SensitiveDataLoggingTest` y `AdminSecurityChainTest` | `Tests run: 171, Failures: 0, Errors: 0, Skipped: 0` (el `BUILD FAILURE` de esa ejecución es la cobertura de JaCoCo con `-Dtest=` acotado) |
| TRIANGULAR | Pruebas unitarias del traductor y un caso de encabezado con restricción | `ProblemExceptionHandlerTest` `Tests run: 7, Failures: 0` (respuesta ya confirmada, desconexión por texto y por clase, error de E/S que no es desconexión, violación de método sin el nombre del método, error global sin códigos) |
| Cierre | `./mvnw verify` completo sobre el árbol final | Surefire 186 + 783 (los 740 de la línea base más 43 nuevos), Failsafe 229, `BUILD SUCCESS`. La instantánea OpenAPI (`apps/api/openapi/*.json`) y `apps/api/routes` **sin cambios** (`git status` limpio de ellos); la prueba `theProblemDetailSchemaOfTheContractStillDeclaresItsSixPropertiesAndNotErrors` afirma las seis propiedades de `ProblemDetail` en las dos instantáneas. |

Pruebas añadidas: 43 (`ProblemTranslationTest` 18, `ProblemExceptionHandlerTest` 7, `ResourceNotFoundTest` 3, `ProblemResponsesTest` +10, `ProblemCodeTest` +4,
`ProblemCatalogCoverageTest` +1). `theFirewallRejectionCarriesNoViolationListAndNoRawRejectedBytes` (seguimiento S1 de la revisión de 2.1b) vuelve aquí: sin `errors`,
`instance` igual a la ruta sin decodificar ni consulta, sin saltos de línea crudos en el cuerpo y sin `Set-Cookie`. Ninguna prueba lee algo que un hilo del servidor
haga después de la respuesta: el traductor registra antes de escribir, así que no hay esperas que acotar.

### Demostraciones deliberadas (cada una revertida; `cmp` contra la copia original sin diferencias)

| Ruptura | Resultado |
|---|---|
| `ProblemExceptionHandler.unexpected` escribe `e.getMessage()` en el `detail` del `500` | `Tests run: 25, Failures: 3`: `anUnforeseenExceptionAnswers500WithNothingInternalInTheBodyOrTheHeaders`: `Expecting actual: "{"type":"https://confia.hn/problems/internal-error","title":"t","status":500,"detail":"jdbc:postgresql://host/db password=x",...` `not to contain: "jdbc"`; además `theTechnicalDetailStays...` (el `traceId` inventado no es el del registro) y `anIoErrorThatIsNotAVanishedClientIsAnUnforeseenFailure`. |
| Quitar `@JsonInclude(NON_EMPTY)` de `ProblemBody.errors` | `Tests run: 37, Failures: 9`: `ProblemResponsesTest.errorsIsOmittedWhenThereAreNoViolations`: `[a response without a violation list has no errors member] Expecting value to be false but was true`, siete de `ProblemTranslationTest` (`[the response has no errors member]`) y `ResourceNotFoundTest`. |

### Medición del árbol completo (`git diff --numstat main...wip/web-edge-problem-translator-full -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 1 029 | 17 | **1 046** |
| Con `-M` | 1 029 | 17 | **1 046** |

Por archivo: `ProblemTranslationTest` 318, `ProblemExceptionHandler` 218, `ProblemExceptionHandlerTest` 172, `HarnessController` 87, `ProblemResponsesTest` 49,
`ResourceNotFoundTest` 53, `FieldViolation` 25, docs 18, el resto 87.

### Costura propuesta (medida sobre árboles reales, no estimada)

| Parte | Contenido | Líneas |
|---|---|---|
| **A `translator-core`** | `RESOURCE_NOT_FOUND` y `UNSUPPORTED_MEDIA_TYPE`, catálogo, `ProblemExceptionHandler` con `DomainException`, cuerpo ilegible, cabecera o parámetro ausente o de tipo erróneo, `415`, `404` y último recurso con desconexión; registro en `WebEdgeConfiguration`; el arnés con las rutas `validated` (sin restricciones), `required`, `domain-known`, `domain-unknown` y `disconnected`; `ProblemTranslationTest` (10), `ProblemExceptionHandlerTest` (5), `ResourceNotFoundTest`, `ProblemCodeTest`, `ProblemCatalogCoverageTest`. Se probó en un árbol aparte: `Tests run: 82, Failures: 0` con OpenAPI, aislamiento y rutas. Contiene la ruptura del `getMessage()`. | **641** (633 adiciones, 8 eliminaciones) |
| **B `field-violations`** | `FieldViolation`, el miembro `errors` con `@JsonInclude(NON_EMPTY)` y la sobrecarga de `write`, los tres traductores de validación y sus ayudas (nombre por anotación de enlace, restricción en kebab-case), la dependencia `spring-boot-starter-validation`, las rutas `bounded` y `constraint-violation` y las restricciones de `validated`, las pruebas de validación (8 en `ProblemTranslationTest`, 2 en `ProblemExceptionHandlerTest`, 10 en `ProblemResponsesTest`) y la nota de `docs/ui-ux`. Contiene la ruptura de `@JsonInclude`. | **417** (402 adiciones, 15 eliminaciones) sobre A |

Las dos partes suman 1 058 (12 más que el árbol completo: B reescribe unas líneas de A). Ambas caben en 800. Dependencia dura: B necesita A; A sola deja los
fallos de validación de campo en el `500` genérico, sin consecuencia mientras ningún endpoint de producción valide (no existe ninguno).

### Desviaciones del diseño (declaradas)

1. **`spring-boot-starter-validation` en `app/pom.xml`.** El traductor importa `jakarta.validation` en producción y esa API solo llegaba de forma transitiva por
   springdoc; se declara de forma explícita (Hibernate Validator vive en `org.hibernate.validator`, que no está en la lista de grupos prohibidos).
2. **Ubicación de las pruebas.** `ProblemTranslationTest` está en `com.confia.shared.web` (donde viven las demás pruebas de la cadena y el arnés), no en
   `shared/web/problem/` como dice la tarea. La prueba del `404` real está en `com.confia.bootstrap` (`ResourceNotFoundTest`) porque `OpenApiProcess` es privada al paquete.
3. **`PublicRouteAllowListTest`**: el control negativo contaba 3 fugas del arnés; ahora cuenta 10 (las siete rutas nuevas del arnés).
4. **El portal también responde `404 resource-not-found`** bajo `/swagger-ui/**` con `local` (su documentación es pública en ese perfil); sin `local`, `401`.
5. **El nombre del campo de un parámetro** sale de la anotación de enlace, no del compilador (el build no conserva los nombres de parámetros).
6. **Una excepción de seguridad lanzada dentro del MVC** (por ejemplo `AccessDeniedException` de un `@PreAuthorize`, cambio 8) caería hoy en el `500` del
   último recurso del traductor; no existe ninguna en este cambio. Riesgo registrado para el cambio 8.
7. **springdoc** puede añadir las respuestas del `@RestControllerAdvice` a cada operación cuando existan operaciones; hoy el documento no tiene ninguna y la
   instantánea no cambia. Riesgo registrado para el primer endpoint de producción.

### Evidencia de la unidad de trabajo (árbol completo)

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='ProblemTranslationTest,ProblemExceptionHandlerTest,ProblemCodeTest,ProblemCatalogCoverageTest,ProblemResponsesTest,ResourceNotFoundTest,PublicRouteAllowListTest,OpenApi*Test,ProcessBeanIsolationTest,ContainerRejectionsTest,RequestContextFilterTest,SensitiveDataLoggingTest,AdminSecurityChainTest'`: `Tests run: 171, Failures: 0`; cierre por `./mvnw verify`: Surefire 186 + 783, Failsafe 229 |
| Arnés de ejecución | Cadena real por HTTP en un proceso sin base de datos con controladores de prueba que lanzan cada excepción, más el proceso administrativo y el del portal reales con `local` para el `404` |
| Frontera de reversión | Se retiran `ProblemExceptionHandler`, `FieldViolation`, el miembro `errors`, la sobrecarga de `write`, los dos códigos y sus entradas de catálogo, la dependencia del starter y las pruebas |

## Tarea 2.4a: PR 8a `translator-core`

Rama `change/web-edge-foundations-problem-translator`, desde `main` en `a831dd3`. El código sale de la rama local
`wip/web-edge-problem-translator-full` (sin tocarla; sigue en `f833cd9`), recortado a la parte A exactamente como se midió (641 líneas). Decisión del
propietario (2026-10-05): partir 2.4 en 2.4a y 2.4b; el cambio pasa a 19 tareas y 18 PR. Re-plan en `tasks.md` y nota fechada del final; dos riesgos
con dueño en `design.md` (excepción de seguridad dentro del MVC: cambio 8; respuestas de `@RestControllerAdvice` en springdoc: `session-tokens-and-web-layer`).

### Evidencia del ciclo TDD (reobservada sobre este árbol)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO 1 | `-Dtest='ProblemTranslationTest,ProblemExceptionHandlerTest,ProblemCodeTest,ProblemCatalogCoverageTest,ResourceNotFoundTest,PublicRouteAllowListTest'` con las pruebas y el arnés, sin producción | `COMPILATION ERROR`: seis `cannot find symbol` (`ProblemExceptionHandler`). Causa prevista. |
| ROJO 2 | Con `ProblemCode` y el traductor, sin catálogo ni registro | `Tests run: 62, Failures: 12, Errors: 0`: `ResourceNotFoundTest` 2 de 3, `ProblemCatalogCoverageTest` 3 de 6, `ProblemTranslationTest` 7 de 10. |
| VERDE | Con catálogo y registro, más OpenAPI, `PortalRouteMapSnapshotTest` y `ProcessBeanIsolationTest` | `Tests run: 90, Failures: 0, Errors: 0, Skipped: 0` (el `BUILD FAILURE` es la cobertura de JaCoCo con `-Dtest=` acotado) |
| Cierre | `./mvnw verify` completo | Surefire 186 + 763 (los 740 de la línea base más 23 nuevos), Failsafe 229, `BUILD SUCCESS`. Instantánea OpenAPI y `routes` sin cambios. |

Pruebas añadidas: 23 (`ProblemTranslationTest` 10, `ProblemExceptionHandlerTest` 5, `ResourceNotFoundTest` 3, `ProblemCodeTest` +4, `ProblemCatalogCoverageTest` +1).

### Demostración deliberada (revertida; `cmp` sin diferencias)

`ProblemExceptionHandler.unexpected` escribe `e.getMessage()` en el `detail`: `Tests run: 15, Failures: 3`. `anUnforeseenExceptionAnswers500WithNothingInternalInTheBodyOrTheHeaders`:
`Expecting actual: "{"type":"https://confia.hn/problems/internal-error","title":"t","status":500,"detail":"jdbc:postgresql://host/db password=x",...` `not to contain: "jdbc"`;
además `theTechnicalDetailStays...` y `anIoErrorThatIsNotAVanishedClientIsAnUnforeseenFailure`.

### Medición del PR 8a (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 633 | 8 | **641** |
| Con `-M` | 633 | 8 | **641** |

Dentro del tope de 800. Tareas: 19 en total; hechas 12.

### Desviaciones del diseño (declaradas)

Las 2, 3 (con ocho fugas en esta parte), 4 y 5 de la sección «Tarea 2.4 completa»; la 1 (dependencia del starter de validación) y las notas de `docs/ui-ux` viajan en 2.4b.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='ProblemTranslationTest,ProblemExceptionHandlerTest,ProblemCodeTest,ProblemCatalogCoverageTest,ResourceNotFoundTest,PublicRouteAllowListTest,OpenApi*Test,PortalRouteMapSnapshotTest,ProcessBeanIsolationTest'`: `Tests run: 90, Failures: 0`; cierre por `./mvnw verify`: Surefire 186 + 763, Failsafe 229 |
| Arnés de ejecución | Cadena real por HTTP con controladores de prueba que lanzan cada excepción, y los procesos administrativo y portal reales con `local` para el `404` |
| Frontera de reversión | Se retiran `ProblemExceptionHandler`, los dos códigos y sus entradas de catálogo, el registro y las pruebas |

### Revisión independiente de 2.4a y corrección (2026-10-05), DETENIDA ANTES DEL COMMIT por tamaño

Veredicto de la revisión de seguridad: sin bloqueantes y sin fugas de datos; dos hallazgos importantes y cuatro sugerencias. La corrección está hecha y verificada
pero **no se compromete**: con ella el PR 8a mide 946 líneas (928 adiciones y 18 eliminaciones, igual con y sin `-M`; la corrección sola, 359) frente al tope de 800. El árbol
verificado está en la rama **local** `wip/web-edge-translator-core-fix` (nunca se publica); la rama de trabajo conserva el commit `7c609bb` de 2.4a.

| Hallazgo | Cambio | Evidencia observada |
|---|---|---|
| I-1 (grave): `DisconnectedClientHelper` reconoce «Connection reset by peer» y «Broken pipe» en cualquier punto de la cadena de causas, de modo que una conexión caída a PostgreSQL, Redis o SMTP se trataba como cliente ausente y respondía `200` vacío sin registro | La desconexión se reconoce solo por tipo: `AsyncRequestNotUsableException` o `ClientAbortException` en la cadena de causas (hasta 16), nunca por texto. Una desconexión real deja el estado `499` (no `2xx`: ninguna métrica ni registro de acceso la cuenta como éxito; no `5xx`: un cliente que se va no levanta una alarma del servidor). Todo lo demás es `500 internal-error` con registro `ERROR` | ROJO: `COMPILATION ERROR` (`ProblemCode.forStatus`, `ProblemExceptionHandler.CLIENT_CLOSED_REQUEST`, tres `cannot find symbol`). VERDE: `Tests run: 120, Failures: 0` en las pruebas enfocadas tras corregir una aserción mía (la ruta `/test/sql-reset` contiene «reset»). Ruptura (volver a `DisconnectedClientHelper.isClientDisconnectedException(e)`): `Tests run: 33, Failures: 4`: `ProblemTranslationTest.aDatabaseFailureWhoseRootCauseSaysConnectionResetIsNeverAnEmpty200: expected: 500 but was: 499` (la cadena de causas del `SQLException` con `SocketException("Connection reset by peer")` se trataba como cliente ausente) y tres de `ProblemExceptionHandlerTest`. Revertida (`cmp`). |
| I-2: el manejador de `Exception` convertía en `500` con traza completa la familia `ErrorResponse` (405 sin `Allow`, 406, `ResponseStatusException`, 413, 503, `NoHandlerFoundException`) | Una sola rama para todo `ErrorResponse`: `ProblemCode.forStatus` (la regla de `ProblemErrorReportValve.codeFor`, ahora compartida y ampliada con `404` y `415`), `Allow` conservada en el `405`, sin registro para un `4xx`, `ERROR` solo para un `5xx` y sin repetir la razón | Ruptura (rama desactivada con `false &&`): `Tests run: 26, Failures: 9`: `aMethodTheRouteDoesNotServeIs405WithItsAllowHeaderAndNoErrorInTheLog: expected: 405 but was: 500`, siete casos de `aResponseStatusExceptionKeepsItsStatusAsTheCatalogCodeAndNeverItsReason` y `aPublicRouteNoControllerServesIs404...`. Revertida (`cmp`). **Hallazgo:** una ruta pública sin controlador lanza `NoHandlerFoundException` (comprobado con una sonda temporal, retirada), no `NoResourceFoundException`; da `404 resource-not-found`. |
| S-1 | `AccessDeniedException` y `AuthenticationException` se relanzan; la cadena responde `403` y `401` | `aSecurityExceptionRaisedInsideTheMvcLayerIsAnsweredByTheSecurityChain` (principal: `403 forbidden`; anónimo: `401 authentication-required`) y la prueba unitaria de relanzado. Nota de `design.md` actualizada. |
| S-2 | La guarda `isCommitted()` es una ayuda común a todos los manejadores; un `DomainException` con código de estado `>= 500` se registra en `ERROR`; el registro del código desconocido nombra el código | `everyHandlerLeavesACommittedResponseAlone`, `aDomainErrorWhoseCodeIsAServerErrorIsLoggedAtError...` |
| S-4 | `PublicRouteAllowListTest` identifica las fugas del arnés por el prefijo `/test/` y no por un conteo exacto | La prueba pasa con las nuevas rutas del arnés sin editar el conteo |

**S-3, seguimiento no implementado:** el registro `ERROR` imprime la excepción completa y su mensaje puede traer valores del usuario (por ejemplo el
`Detail: Key (x)=(valor)` de PostgreSQL). Dueño: la tarea 6.1 (cierre) debe revisar el registro de errores contra la regla 11 de `CLAUDE.md`, y la decisión de fondo
(un filtro de mensajes de excepción en el registro estructurado) pertenece al cambio de observabilidad.

Cierre: `./mvnw verify` completo: Surefire 186 + 797 (763 más 34), Failsafe 229, `BUILD SUCCESS`. Instantánea OpenAPI y `routes` sin cambios.

### Excepción de tamaño del PR 8a (2026-10-05)

Con la corrección de la revisión independiente (I-1, I-2, S-1, S-2 y S-4), 2.4a mide 946 líneas efectivas frente
al presupuesto de 800. El propietario aprobó una **excepción de tamaño de unas 146 líneas** para entregarla en un solo
PR. Así el traductor llega a `main` ya corregido, sin pasar un solo día con el defecto del `200` vacío ante una falla
del servidor. No se recortaron pruebas ni comentarios.

## Tarea 2.4b: PR 8b `field-violations`

Rama `change/web-edge-foundations-field-violations`, desde `main` en `b1d2cb6` tras fusionar el PR 8a (con su corrección de revisión). El código sale de la rama
local `wip/web-edge-problem-translator-full` (sin tocarla; sigue en `f833cd9`), pero **solo** las adiciones de 2.4b sobre la versión de `main`: esa copia es anterior a la
corrección de 2.4a y difiere mucho de `ProblemExceptionHandler`, `ProblemCode`, `ProblemErrorReportValve` y `PublicRouteAllowListTest` de `main`, que no se tomaron.
La nota de `docs/ui-ux/04-patrones-de-interaccion.md` §9 sí se tomó tal cual (el archivo solo difería por esa nota).

### Evidencia del ciclo TDD (reobservada sobre este árbol)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO 1 | `-Dtest='ProblemTranslationTest,ProblemExceptionHandlerTest,ProblemResponsesTest'` con pruebas y arnés, sin producción | `COMPILATION ERROR`, siete `cannot find symbol` / tipos incompatibles (`FieldViolation`, `ProblemResponses.write` con violaciones, `invalidBody`, `invalidConstraints`). |
| ROJO 2 | `FieldViolation`, `errors` y la sobrecarga de `write`, más los tres traductores como esqueleto sin lista | `Tests run: 60, Failures: 0, Errors: 9`: `ProblemTranslationTest` 7 de 31 y `ProblemExceptionHandlerTest` 2 de 13, todas `NullPointer ... JsonNode.get(String) is null` (cuerpo `400 validation-failed` sin `errors`); `ProblemResponsesTest` 16/16. |
| VERDE | Traductores reales (campo y restricción, nombre del parámetro por su enlace, ruta sin el nombre del método) y `spring-boot-starter-validation` en `app/pom.xml`; más `ProblemCodeTest`, `ProblemErrorReportValveTest`, `PublicRouteAllowListTest`, `OpenApi*Test` y `ProcessBeanIsolationTest` | `Tests run: 150, Failures: 0, Errors: 0, Skipped: 0` (el `BUILD FAILURE` es la cobertura de JaCoCo con `-Dtest=` acotado). |
| Cierre | `./mvnw verify` completo | Surefire 186 + 818 (los 797 de la línea base más 21 nuevos), Failsafe 229, `BUILD SUCCESS`. Instantánea OpenAPI y `routes` **sin cambios** (`git status` limpio de ellos). |

Pruebas añadidas: 21 (`ProblemResponsesTest` +10 casos: dos métodos y uno parametrizado de ocho; `ProblemTranslationTest` +8 (cinco métodos y uno parametrizado de tres); `ProblemExceptionHandlerTest` +3 métodos que hacen 13 con
los de 2.4a). Sin nuevo `PublicRouteAllowListTest`: ya identifica las fugas por el prefijo `/test/`, así que las dos rutas nuevas del arnés no necesitan editarlo.

### Demostraciones deliberadas (cada una revertida; `cmp` contra la copia original sin diferencias)

| Ruptura temporal | Resultado observado |
|---|---|
| Quitar `@JsonInclude(NON_EMPTY)` de `ProblemBody.errors` | `Tests run: 71, Failures: 19`: `ProblemResponsesTest.errorsIsOmittedWhenThereAreNoViolations` con `[a response without a violation list has no errors member] Expecting value to be false but was true`, y 18 de `ProblemTranslationTest` por `[the response has no errors member] Expecting value to be false but was true` (toda respuesta llevaba `errors: []`). `PublicRouteAllowListTest` y las de OpenAPI siguen en verde. |
| Quitar `@ExceptionHandler` de `MethodArgumentNotValidException` y de `HandlerMethodValidationException` (la rama genérica de `ErrorResponse` responde) | `Tests run: 60, Failures: 1, Errors: 6`: `ProblemExceptionHandlerTest.theValidationHandlersWinOverTheGenericErrorResponseBranch` falla y seis de `ProblemTranslationTest` (`anInvalidField...` x3, `theRejectedValue...`, `aConstraintOnAParameter...`, `aParameterAndAHeader...`) con `NullPointer ... JsonNode.get(String) is null`: el estado sigue siendo `400 validation-failed` pero sin `errors`. |

### Prueba del orden de los traductores

`MethodArgumentNotValidException` y `HandlerMethodValidationException` implementan `ErrorResponse`, así que la rama genérica de `unexpected` también las cubriría y respondería
`400 validation-failed` sin `errors`. Spring elige el manejador más cercano al tipo de la excepción, y `theValidationHandlersWinOverTheGenericErrorResponseBranch` lo fija con
`ExceptionHandlerMethodResolver`: comprueba la precondición (ambas son `ErrorResponse`), que cada excepción de validación se resuelve a `invalidBody`, `invalidParameters` e
`invalidConstraints`, y que otra `ErrorResponse` (`HttpMediaTypeNotAcceptableException`) sigue cayendo en `unexpected`. Las pruebas por HTTP real (`anInvalidField...`,
`aConstraintOnAParameter...`) prueban lo mismo de extremo a extremo.

### Seguridad

`errors` lleva solo el nombre del campo y la restricción derivada de los códigos del catálogo de Spring (`Size`, `Max.int` se reduce a `Max`). Nunca el valor rechazado, el texto de
la restricción, el nombre del método (`fieldOf` lo recorta) ni un mensaje del analizador. Un error de objeto no lleva campo (cadena vacía) y uno sin códigos es `invalid`. Pruebas con
`HarnessProcess.SENSITIVE_VALUE` en el cuerpo y las cabeceras.

### Medición del PR 8b (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 450 | 14 | **464** |
| Con `-M` | 450 | 14 | **464** |

Pronóstico: 417; tope 800. Dentro del tope.

### Desviaciones del diseño (declaradas)

1. **Rama de `main` como autoridad.** Solo se portaron las adiciones de 2.4b; el traductor de `main` conserva su orden (relanzado de seguridad, desconexión por tipo, `ErrorResponse` por `forStatus`).
   Los tres traductores nuevos escriben mediante la guarda común `answer` (una respuesta confirmada no se toca), como el resto de `main`; la copia de respaldo escribía sin guarda.
2. **Prueba inestable corregida durante el cierre.** `aParameterAndAHeaderAreNamed...` afirmaba que todo el cuerpo no contenía `9"`, pero el `traceId` es un UUID aleatorio y termina en `9` una de cada 16
   veces (un primer `verify` falló por eso). Ahora la aserción recae solo en el arreglo `errors`. El árbol de respaldo conserva la versión inestable.
3. **`spring-boot-starter-validation` ya llegaba de forma transitiva** (el rojo de comportamiento no pudo depender de su ausencia: la validación ya se ejecutaba); se declara de forma explícita de todos modos,
   como pide la tarea. Descarga sin incidencias (sin PKIX).

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='ProblemTranslationTest,ProblemExceptionHandlerTest,ProblemResponsesTest,ProblemCodeTest,ProblemErrorReportValveTest,PublicRouteAllowListTest,OpenApi*Test,ProcessBeanIsolationTest'`: `Tests run: 150, Failures: 0`; cierre por `./mvnw verify`: Surefire 186 + 818, Failsafe 229 |
| Arnés de ejecución | Cadena real por HTTP con controladores de prueba (`validated` con `@Valid` y restricciones de cuerpo, `bounded` con restricciones de parámetro y cabecera, `constraint-violation`) |
| Frontera de reversión | Se retiran `FieldViolation`, el miembro `errors`, la sobrecarga de `write`, los tres traductores, la dependencia del starter, la nota de `docs/ui-ux` y las pruebas |

### Revisión independiente de 2.4b y corrección (2026-10-05), DETENIDA ANTES DEL COMMIT por tamaño

Veredicto de la revisión de seguridad: sin bloqueantes y con las correcciones de 2.4a intactas; dos hallazgos importantes y cinco sugerencias. La corrección está hecha y verificada pero
**no se compromete**: con ella el PR 8b mide 941 líneas (926 adiciones y 15 eliminaciones, igual con y sin `-M`; la corrección sola, 477, casi todas de pruebas) frente al tope de 800.
El árbol queda en el directorio de trabajo, sin confirmar, a la espera de la decisión del propietario.

| Hallazgo | Cambio | Evidencia observada |
|---|---|---|
| I1: `field` repetía texto del atacante y estructura interna | La ruta de Bean Validation se reconstruye por nodos: solo propiedades, con índice y clave de mapa como `[]` y sin `<list element>`, `<return value>`, el método ni `argN`; un error sin la violación original se corta en el primer subíndice; `FieldViolation` cierra el campo a `^[A-Za-z0-9_.\[\]-]{1,128}$` (si no, vacío) | ROJO: `Tests run: 85, Failures: 20, Errors: 3` sobre las pruebas enfocadas (ver abajo). Ruptura (sustituir `[]` por la clave o el índice): `ProblemTranslationTest.aKeyTheClientChoseForAMapIsNeverEchoedInTheFieldPath: expected: "props[].campo" but was: "props[VALOR-SENSIBLE-123].campo"` y `ProblemExceptionHandlerTest.aMapKeyAnAnIndexAndTheSyntheticNodesNeverReachTheFieldPath` con `["props[x].secret[y].campo", "items[1]", "props[VALOR-SENSIBLE-123].campo"]` (`Tests run: 51, Failures: 2`). Revertida (`cmp`). |
| I2: `errors` sin tope | `ProblemBody.MAX_ERRORS = 50`, aplicado en el constructor del registro; nota fechada en `design.md` decisión 9 | Ruptura (quitar `Math.min`): `ProblemResponsesTest.errorsNeverHoldMoreThanFiftyViolationsAndFiftyAreKeptWhole: expected: 50 but was: 51` y `ProblemTranslationTest.theListOfViolationsIsCappedAtFifty [60 violations, 50 listed] expected: 50 but was: 60`. Revertida (`cmp`). |
| S1 | `FieldViolation`: razón `^[a-z0-9]+(-[a-z0-9]+)*$` de a lo sumo 64 caracteres, si no `invalid` | `FieldViolationTest` 9 casos (ROJO: `Failures: 9`). |
| S2 | `ValidationConfigurationCustomizer` con `ParameterMessageInterpolator` en `WebEdgeConfiguration` | ROJO: `ValidatorInterpolationTest`: `Expecting actual: "SECRETVALUE" not to contain: "SECRETVALUE"` (con el intérprete por omisión, `${validatedValue}` sustituía el valor). Hibernate Validator 9 ya no evalúa métodos, pero sí variables. |
| S3 | Traductores de `BindException` y `MethodValidationException`; prueba de orden ampliada con ambas | `ExceptionHandlerMethodResolver` resuelve a `invalidBinding` e `invalidMethod`. |
| S4 | Violación de valor de retorno (`ConstraintViolationException` o `MethodValidationException`) es `500 internal-error` con `ERROR` | Pruebas unitarias con `validateReturnValue`. Una violación de argumentos de un servicio validado sigue siendo `400`: no se distingue «no viene de la entrada web» más allá del retorno (riesgo). |
| S5 | La ruta se recorre con un bucle, sin `iterator().next()` | Prueba con una ruta vacía (proxy). Por construcción: ya no hay `next()` que proteger. |

Cierre: `./mvnw verify` completo: Surefire 186 + 843 (818 más 25), Failsafe 229, `BUILD SUCCESS`. Instantánea OpenAPI y `routes` sin cambios.

**Registro y `SensitiveLogGuard`.** `org.springframework.web` no debe correr en `DEBUG` en producción: `DispatcherServlet` registra la excepción resuelta con sus valores rechazados. El guardia ya
cubría `DispatcherServlet` (PR 7) y, por el prefijo `org.springframework.web.servlet.mvc.method.annotation`, `ExceptionHandlerExceptionResolver` y `ServletInvocableHandlerMethod`. **No cubría**
`DefaultHandlerExceptionResolver` (`...mvc.support`), `ResponseStatusExceptionResolver` (`...mvc.annotation`), los resolvedores de `...servlet.handler` ni `InvocableHandlerMethod`
(`org.springframework.web.method`, que registra los argumentos de la llamada en `TRACE`). Se añadieron los cuatro prefijos a `PROTECTED_PREFIXES` con `SensitiveLogGuardPrefixesTest`
(siete loggers; ROJO `Failures: 5`, los dos primeros ya estaban cubiertos). Son preventivos, como los demás de su lista: el arnés no los ejercita en vivo.

### Excepción de tamaño del PR 8b (2026-10-05)

Con la corrección de la revisión independiente (I1, I2 y S1 a S5, más cuatro prefijos de `SensitiveLogGuard`),
2.4b mide 941 líneas efectivas frente al presupuesto de 800. El propietario aprobó una **excepción de tamaño de unas
141 líneas** para entregarla en un solo PR: la validación de campos llega a `main` ya acotada, sin reflejar claves de
mapa y sin la sustitución de `${validatedValue}`. No se recortaron pruebas ni comentarios. El orquestador verificó la
corrección (89 pruebas enfocadas en verde) y la confirmó.

## Tarea 2.5: PR 9 `web-rules` (DETENIDA ANTES DEL COMMIT por tamaño: 822 líneas frente a 800)

Rama de trabajo `change/web-edge-foundations-web-rules` desde `main` en `507435c`, **restablecida a `main` y limpia**. El árbol completo y verificado está en la rama local
`wip/web-edge-web-rules-full` (nunca se empuja). La tarea 2.5 sigue sin marcar `[x]`: falta decidir la costura (abajo).

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO 1 (mitades de fixture) | `-Dtest='WebLayerDependencyRulesTest,SharedBoundaryRulesTest,WebExposedTypesRuleTest'` sin fixtures ni puerto | `WebLayerDependencyRulesTest` `Tests run: 2, Failures: 1` (rechaza por `BadWeb` de otra capa, sin nombrar `BadWebUsesInfrastructure`); `SharedBoundaryRulesTest` `Tests run: 2, Failures: 1` (`failed to check any classes`); `WebExposedTypesRuleTest` `Tests run: 5, Failures: 3` (las dos mitades de fixture y la de producción de W2a, las tres por `failed to check any classes`). Las mitades de producción de W1, W3 y W2b ya pasaban sobre clases reales de `shared.web`. |
| ROJO 2 (inventario) | `WebEdgeScopeExclusionInventoryTest` con `SessionValidity` y el soporte de fixtures ausentes | `COMPILATION ERROR`: `cannot find symbol class SessionValidity` (cuatro veces). |
| ROJO 3 (excepción sin inventario) | W2a de producción con `allowEmptyShould(true)`, sin la entrada | `SuppressionCitesAdrTest` `Tests run: 10, Failures: 1`: `allowEmptyShould( call sites must match ... expected: 0 but was: 1`. |
| VERDE | las ocho clases de arquitectura más `ProcessBeanIsolationTest` | `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0` (`LayeredArchitectureTest` 2, `SpringModulithVerificationTest` 2, `EmptyShouldExceptionInventoryTest` 2, `SuppressionCitesAdrTest` 10, `WebEdgeScopeExclusionInventoryTest` 12, `WebExposedTypesRuleTest` 5, `WebLayerDependencyRulesTest` 2, `SharedBoundaryRulesTest` 2, `ProcessBeanIsolationTest` 4). |
| Cierre | `./mvnw verify` completo | Surefire 186 + 865 (los 843 de la línea base más 22 nuevos), Failsafe 229, `BUILD SUCCESS`. Instantánea OpenAPI y `routes` sin cambios (`git status` limpio de ellos). |

Pruebas añadidas: 22 (`WebLayerDependencyRulesTest` 2, `SharedBoundaryRulesTest` 2, `WebExposedTypesRuleTest` 5, `WebEdgeScopeExclusionInventoryTest` 12 y `EmptyShouldExceptionInventoryTest` +1).

### Demostraciones deliberadas (cada una revertida; archivos temporales borrados y `git status` limpio; `cmp` en las ediciones sobre archivos existentes)

| Ruptura temporal | Resultado observado |
|---|---|
| Campo `org.jooq.DSLContext` en una clase temporal de `shared.web.problem` | `WebLayerDependencyRulesTest.productionWebClassesNeverDependOnInfrastructureOrJooq`: `Field <com.confia.shared.web.problem.TempJooqBreak.leaked> has type <org.jooq.DSLContext>`; también falla W2b: `TempJooqBreak.leaked is or contains org.jooq.DSLContext, which a web class must replace with explicit values`. |
| Clase temporal de `shared.security` con un campo de `com.confia.identity.domain.StaffAccountId` | `SharedBoundaryRulesTest.productionSharedDependsOnlyOnSharedAndKernel`: `Field <...TempSharedUsesIdentity.leaked> has type <com.confia.identity.domain.StaffAccountId>`. |
| Controlador temporal (`@RestController`) en `shared.web.problem` que devuelve ese tipo | W2a: `TempController.leak() exposes com.confia.identity.domain.StaffAccountId in its public signature, which a controller must replace with an explicit DTO`; `EmptyShouldExceptionInventoryTest.everyExceptionsConditionStillHolds`: `... ADR-0018 exception no longer holds (no production class is meta-annotated with @Controller ...)`. |
| `SessionValidity` con un método por omisión que devuelve un tipo de `identity` (con `cmp`) | `WebEdgeScopeExclusionInventoryTest...`: `[shared must not depend on identity ...] Expecting empty but was: ["com.confia.identity.domain.StaffAccountId"]`, y W3 con `Method <...SessionValidity.owner()> has return type <...StaffAccountId>`. |
| Una clase temporal por cada ausencia (implementa `SessionValidity`, implementa `AuthenticationProvider`, usa `jakarta.servlet.http.Cookie`, llama `hasRole` sobre una ruta, lleva `@PreAuthorize`, y `InstitutionTempDto` en un paquete `web`), en una sola ejecución | Seis fallos, cada uno nombrando su clase: `Expecting empty but was: ["com.confia.shared.security.TempSessionValidityImpl"]`, `["com.confia.shared.security.TempAuthProvider"]`, `["com.confia.shared.web.request.TempCookieUser"]`, `["com.confia.shared.web.edge.TempRoleChain applies hasRole(...) to a route"]`, `["com.confia.shared.web.edge.TempPreAuth"]`, `["com.confia.shared.web.edge.InstitutionTempDto"]`. Un primer intento falló en la aserción de base (exigía el conjunto exacto `{permitAll, denyAll}` y `hasRole` lo alteraba); la aserción de base pasó a `containsAll`, de modo que el fallo cae en la ausencia y nombra la clase. |

### Medición (`git diff --numstat main...HEAD -- . ':!openspec'` sobre la rama de respaldo)

Total **822 líneas** (819 adiciones y 3 eliminaciones), igual con y sin `-M` (todo es nuevo salvo `EmptyShouldExceptionInventoryTest`). Pronóstico: 700 a 1 000; tope 800.

### Costura propuesta (medida en líneas, adiciones más eliminaciones)

| Parte | Contenido | Líneas |
|---|---|---|
| PR 9a `web-boundary-rules` | W1 (`WebLayerDependencyRulesTest` 48, fixture `BadWebUsesInfrastructure` 27), W3 (`SharedBoundaryRulesTest` 54, fixtures `BadSharedUsesIdentity` 18 y `SomeIdentityType` 13), el puerto `SessionValidity` 17, W2a y W2b (`WebExposedTypesRuleTest` 172, fixtures `BadRecordReturningController` 24 y `BadDomainCarryingDto` 12) y la entrada del inventario (`EmptyShouldExceptionInventoryTest` 38) | 423 |
| PR 9b `web-scope-inventory` | `WebEdgeScopeExclusionInventoryTest` 310 y `WebEdgeScopeViolationFixtures` 89 (necesita `SessionValidity` de 9a) | 399 |

### Desviaciones del diseño (declaradas)

1. **Soporte de fixtures de las ausencias en `com.confia.shared.web.testsupport.fixture`**, no en `architecture/fixture/**`: la ausencia de `Institution` solo mira paquetes `..web..` y las reglas que recorren `architecture.fixture` no deben ver esas clases. Mismo patrón que `identity.testsupport.fixture`.
2. **`LayeredArchitectureTest` sí ve `BadWebUsesInfrastructure`, `BadRecordReturningController`, `BadDomainCarryingDto` y `BadSharedUsesIdentity`** (están en paquetes `..web..`, y los tres primeros dependen de tipos de `layering`): por construcción solo pueden sumar violaciones (no se contaron una a una) a una mitad de fixture que ya se rechaza y que sigue nombrando `BadDomain`, `BadApplication` y `BadWeb`. Su resultado no cambia (2 de 2). Tampoco cambia el de `SpringModulithVerificationTest` (2 de 2): los módulos nuevos (`webedge`, `sharedboundary`) solo añaden violaciones a una verificación que ya espera `Violations`.
3. **W1 sigue la tabla del diseño** (`infrastructure`, jOOQ generado y `org.jooq`). El requisito de `build-integrity` nombra además el `domain` de otro módulo: lo cubre `LayeredArchitectureTest` (la capa `Web` solo accede a `Application`) y W2b para los campos; no se añadió un cuarto destino sin fixture propio.
4. **`SessionValidity` no necesita nada en `ProcessBeanPolicy` ni en los inventarios de procesos**: es una interfaz sin bean.
5. **Prueba adicional** `theControllerConditionStopsHoldingWhenAControllerExists` en `EmptyShouldExceptionInventoryTest`: sin ella un predicado de caducidad que siempre respondiera `true` nunca fallaría.

### Excepción de tamaño del PR 9 (2026-10-05)

La tarea 2.5 verificada mide 822 líneas efectivas (819 añadidas y 3 eliminadas) frente al presupuesto de 800. Solo
17 son de producción (el puerto `SessionValidity`); el resto son reglas de ArchUnit, fixtures permanentes y el
inventario de ausencias. El propietario aprobó una **excepción de tamaño de 22 líneas** en lugar de partirla, porque
dividirla agregaría una tarea y un ciclo de PR sin mejorar la revisión. El árbol se tomó sin cambios de la rama local
verificada `wip/web-edge-web-rules-full` (`47efcef`). Tarea 2.5 cerrada.

### Revisión independiente de 2.5 y corrección (2026-10-05)

Veredicto: sin bloqueantes. Hallazgos y resolución:

| Hallazgo | Resolución |
|---|---|
| I1: `LayeredArchitectureTest` comparaba fragmentos sueltos (`BadWeb`, `BadDomain`) y los fixtures de `webedge` los satisfacían | Corregido por el orquestador en `5728e0d` (fragmentos calificados por paquete, probado que falla si `layering/web/BadWeb` deja de violar). |
| I2: faltaba nombrar `fixture.webedge.web.BadDomainCarryingDto` | Corregido por el orquestador en `5728e0d`. |
| I3: `SessionValidity` tiene un solo método abstracto; `@Bean SessionValidity v() { return id -> true; }` sería un bean sin clase implementadora y `implementationsOf` seguiría en verde | `WebEdgeScopeExclusionInventoryTest.noProductionClassOtherThanThePortItselfDependsOnIt`: ninguna clase de producción distinta del puerto tiene una dependencia directa de él (campo, parámetro, retorno o llamada), sobre una base no vacía, con el fixture `LambdaSessionValidityFactory`. Ruptura: clase temporal con `SessionValidity v() { return id -> true; }` falla con `Expecting empty but was: ["com.confia.shared.security.TempLambdaPort"]`. Revertida y borrada. |
| S1: las anotaciones de permiso no cubrían meta-anotaciones ni `@EnableMethodSecurity` | Se comprueba con `isMetaAnnotatedWith` (clase y método) y se añade `EnableMethodSecurity` al conjunto, con fixtures `ComposedPermissionOperation` (anotación compuesta `@AdminOnly` que lleva `@PreAuthorize`) y `MethodSecurityEnabler`. Rupturas: una anotación compuesta temporal da `Expecting empty but was: ["com.confia.shared.security.TempComposed", ...]` y una clase temporal con `@EnableMethodSecurity` da `["com.confia.shared.security.TempEnable"]`. Revertidas y borradas. |
| S2: nada impedía escanear `com.confia.architecture.fixture` | Nota en el Javadoc de `BadRecordReturningController`: ninguna prueba de contexto puede escanear ese paquete. |
| S3: duplicación entre W2a y W2b y entre los detectores | Aceptada como duplicación (cada regla conserva su propio ámbito y mensaje). |

Cierre: `./mvnw verify` completo: Surefire 186 + 867 (865 más 2: la dependencia del puerto y su fixture), Failsafe 229, `BUILD SUCCESS`. Instantánea OpenAPI y `routes` sin cambios.

### Ampliación de la excepción de tamaño del PR 9 (2026-10-05)

Con las correcciones de la revisión independiente (I1 e I2 del orquestador en `5728e0d`; I3, S1 y S2 en `2615366`),
el PR 9 mide 916 líneas efectivas (911 añadidas y 5 eliminadas) frente al presupuesto de 800. Sigue habiendo solo 17
líneas de producción. El propietario amplió la excepción de tamaño de 22 a **116 líneas**: la corrección viaja con
las reglas que corrige. No se recortaron pruebas ni comentarios.

## Tarea 3.1: PR 10 `rate-limiter-core` (VERIFICADA Y DETENIDA ANTES DEL COMMIT por tamaño: 1 548 líneas frente a 800)

Rama de trabajo `change/web-edge-foundations-rate-limiter-core` desde `main` en `e557639` (tras fusionar el PR 94), **sin commits y sin código**. El árbol completo y verificado
está en la rama local `wip/web-edge-rate-limiter-core-full` (nunca se empuja). La tarea 3.1 sigue sin marcar `[x]`: falta decidir la costura (abajo). Los cambios de `openspec/`
(la nota fechada de `design.md`, esta sección y la nota de `tasks.md`) quedan sin confirmar en el árbol de trabajo.

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| Red de seguridad | N/A: solo archivos nuevos; línea base en `main` (`e557639`) medida en un árbol aparte con `./mvnw -pl app -am test` | Surefire 186 + 867, `BUILD SUCCESS` |
| ROJO | `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='InMemoryRateLimiter*'` con `MutableClock`, `InMemoryRateLimiterTest`, `InMemoryRateLimiterProperties` e `InMemoryRateLimiterConcurrencyTest` y sin producción | `COMPILATION ERROR` (más de 40 errores): `package com.confia.shared.security.RateLimitDecision does not exist` y `cannot find symbol` (`InMemoryRateLimiter`, `RateLimitPolicy`, `Limited`...). Es la causa prevista. |
| VERDE, primer intento | Mismo comando, con la producción | `Tests run: 136, Failures: 1, Errors: 2`. (1) `sweepingExpiredEntriesWhileNewIpsArriveNeverOverfillsTheTable`: `Expecting actual: 51 to be less than or equal to: 50`. **Defecto real de producción, no de la prueba:** el barrido devolvía el hueco al contador dentro de `computeIfPresent`, antes de que el mapa eliminara la entrada, y otro hilo ocupaba el hueco con la entrada vieja aún en el mapa. Corregido con `reclaim` (el hueco vuelve después de la eliminación). (2) Las dos propiedades de jqwik: `IllegalArgumentException: Entries must not be empty`: había usado `Statistics.label(...)` y `Statistics.coverage` solo mira las estadísticas sin etiqueta. Corregido en la prueba. |
| VERDE | Mismo comando | `Tests run: 136, Failures: 0, Errors: 0, Skipped: 0` (`InMemoryRateLimiterConcurrencyTest` 80, `InMemoryRateLimiterTest` 53, propiedades 3). La cobertura de JaCoCo falla con `-Dtest=` acotado, como avisa `tasks.md`. |
| Refuerzo tras las demostraciones | Una prueba de borde de reclamación por petición y otra por fallo, una de límite y umbral mayores que la primera reserva del anillo (cubre `grow`), una de duración nula, y un generador de propiedades que produce intentos fallidos (`FAILED_LOGIN`) con una política en la que 20 s de intervalo y 30 s de ventana dejan menos de tres fallos dentro de la ventana | Ver las demostraciones: sin el generador nuevo, la propiedad no detectaba la ruptura de la regla de no levantar (400 intentos en verde). Final: `-Dtest='InMemoryRateLimiter*'` → `Tests run: 140, Failures: 0, Errors: 0, Skipped: 0` (80 + 57 + 3). |
| Cierre | `./mvnw verify` completo sobre el árbol final | Surefire 186 + 1 007 (los 867 de la línea base más 140 nuevos), Failsafe 229, `All coverage checks have been met` (módulo `kernel` y `confia-api`), `BUILD SUCCESS`. |

Pruebas añadidas: 140 (`InMemoryRateLimiterTest` 57, `InMemoryRateLimiterConcurrencyTest` 80 con cuatro escenarios repetidos 20 veces, `InMemoryRateLimiterPropertiesTest` 3). JaCoCo sobre las
clases nuevas: 100 % de líneas y de ramas en `InMemoryRateLimiter` (y sus tipos internos), `RateLimitPolicy` y `RateLimitDecision`.

**Mutación (PIT).** `./mvnw verify` por omisión no ejecuta PIT (solo los perfiles `mutation-report` y `mutation-gate`) y su alcance es `com.confia.*.domain.*`; `shared.security` no es un
paquete `domain`, así que el umbral de 80 no aplica a este código y no se ejecutó. El umbral de cobertura de módulo (80 %) sí aplica y se cumple.

### Modelo de referencia de jqwik

`NaiveReference` guarda, por cliente, todas las admisiones y todos los fallos en listas sin acotar, nunca descarta una y recalcula cada respuesta desde las listas (cuenta las
admisiones con edad menor que la ventana y toma la más antigua de ellas; cuenta los fallos con edad menor que la ventana; aplica las dos formas de terminar la restricción). Cuenta
en nanosegundos desde cero y no lee el limitador, el reloj ni ninguna clave: los clientes los define la propia prueba (dos direcciones de un /64 son un cliente). El limitador usa dos
anillos pequeños y una marca de tiempo. Se compara la decisión y el `Retry-After` al nanosegundo sobre secuencias de hasta 150 operaciones (mover el reloj, a menudo a un nanosegundo
de un borde de la política; `tryAcquire`; `recordFailure`; intento fallido) con la cobertura de estadísticas exigida (`admitted`, `limited`, `limited while restricted`, `failure`).
Dos propiedades más: la tabla de dos entradas nunca contiene más de dos y rechaza solo cuando está llena; y `Retry-After` es el techo en segundos calculado con `BigDecimal`.

### Concurrencia

Cuatro escenarios con `CyclicBarrier`, reloj `MutableClock` quieto y sin esperas por reloj de pared, cada uno repetido 20 veces: 50 hilos y una IP admiten exactamente 10 y limitan 40 (y
otra ronda tras mover el reloj admite otros 10: los 40 rechazos no movieron nada); 9 fallos concurrentes no restringen y 10 sí; tabla de 50 con 100 IP admite exactamente 50 y rechaza 50,
con un hilo que muestrea `size()` y no supera 50 nunca; barrido concurrente con la tabla llena de entradas vencidas (entre 1 y 50 admitidas, `size()` igual a las admitidas, nunca por
encima de 50). Resultado: cinco ejecuciones seguidas de `InMemoryRateLimiterConcurrencyTest` solo (`Tests run: 80, Failures: 0` cinco veces, 400 repeticiones), las ejecuciones enfocadas
y el `verify` completo, sin ninguna falla intermitente tras corregir el defecto del hueco.

### Demostraciones deliberadas (cada una revertida; `cmp` contra la copia original sin diferencias)

| Ruptura temporal | Resultado observado |
|---|---|
| `>=` por `>` en el descarte de admisiones (`settle`) | Primero: `Tests run: 136, Failures: 0`: **no rompe nada**. La espera de una admisión con edad igual a la ventana es cero y se admite igual (el anillo la sobrescribe), así que el borde de la capa 1 no distingue los dos operadores; es un mutante equivalente para las decisiones. Solo cambia si la entrada es reclamable, así que se añadieron `anEntryIsReclaimedExactlyWhenItsLastRequestLeavesTheWindow` y su par de fallos. Con ellos: `Tests run: 138, Failures: 1`: `expected: Admitted[] but was: CapacityExhausted[]`. |
| Sustituir el rechazo por desalojo (se quita una entrada y se reintenta) | `Tests run: 138, Failures: 51`: concurrencia 40 (`aTableOfNHoldingNoOneAdmitsExactlyNOfTwoNIpsAndNeverGrowsPastN expected: 50L but was: 69L`, 94, 87, 100...; y la de barrido), unitarias 10 (se esperaba `CapacityExhausted[]`) y la propiedad de la tabla (`Count of 0 for ["refused"]`). |
| Capa 2: terminar la restricción cuando los fallos de la ventana bajan del umbral (regla descartada) | Antes del generador de intentos fallidos: `Tests run: 138, Failures: 3` solo en pruebas unitarias (`theRestrictionIsNotLiftedWhileTheIpKeepsFailing: expected: Limited[retryAfter=PT1M] but was: Admitted`); la propiedad seguía en verde. Con el generador nuevo: `Failures: 4`, la propiedad también (`expected: Limited[retryAfter=PT9.999999466S]`). |
| Capa 2: tope `>=` por `>` | `Tests run: 138, Failures: 1`: `theRestrictionEndsAtTheOneHourCapEvenIfTheIpKeepsFailing` (`assertAdmitted`, el segundo intento de la misma marca). |
| Registrar el rechazo (`admitted.add` antes de devolver `Limited`) | `Failures: 3`: `aRejectedRequestIsNeverRecordedAndNeverMovesTheWindow`, `theWindowSlidesRequestByRequest` y la propiedad. |
| `Retry-After` redondeado hacia abajo | `Failures: 4`: tres casos de `retryAfterIsWholeSecondsRoundedUpAndNeverBelowOne` y la propiedad del techo. |
| Barrer en cada llamada | `Failures: 1`: `theTableIsSweptAtMostOnceASecond`. |

Equivalencia declarada: vaciar los fallos al terminar la restricción por diez minutos sin fallos tampoco se puede observar (ver la nota de `design.md`).

### Desviaciones del diseño y de `tasks.md` (declaradas)

1. **`InMemoryRateLimiterPropertiesTest`, no `InMemoryRateLimiterProperties`.** El nombre de `tasks.md` no coincide con el patrón de Surefire (`*Test`): con él, `./mvnw verify` no ejecuta la
   clase (lo comprobé: Surefire dio 1 003, tres menos de los esperados, porque las tres propiedades no corrían). Se renombró; el comando `-Dtest='InMemoryRateLimiter*'` de `tasks.md` sigue
   incluyéndola.
2. **`RateLimitDecision.Limited.retryAfterSeconds()`** (no está en el diseño): el redondeo hacia arriba, con mínimo de 1, vive en el tipo y 3.2 solo lo escribe en la cabecera.
3. **`InMemoryRateLimiter.size()`** (de paquete): lo necesita la prueba de concurrencia para muestrear la cota. No lo usa producción.
4. **Sin `try/catch` que devuelva el hueco ante una excepción** dentro de `compute`: nada entre la reserva y la inserción puede lanzar salvo un `Error` que termina el proceso, y una
   rama que ninguna prueba puede ejecutar es peor que su ausencia. Queda documentado en el Javadoc de `apply`.
5. **La limitación declarada por escrito está en el Javadoc de `InMemoryRateLimiter`**; la nota fechada de `docs/03-seguridad.md` §4.4 y §10 sigue siendo de 3.2, como dice `tasks.md`.
6. **`ProcessBeanPolicy`: no hace falta ninguna línea.** El limitador no se registra como bean (sin consumidor; el cableado es 3.2). `ProcessBeanIsolationTest` 4/4, `ProcessBeanInspectorTest` 5/5,
   `SpringModulithVerificationTest`, `LayeredArchitectureTest` y `EmptyShouldExceptionInventoryTest` siguen en verde en el `verify` completo.

### Decisión S-3 (seguimiento de la revisión de 2.3a)

**Se acepta y se documenta, sin cambio de código.** Las direcciones NAT64 (`64:ff9b::/96`) y las IPv4 compatibles (`::a.b.c.d`) comparten un cubo /64 en `rateLimitKey`. Razón: ninguna es
enrutable como origen de una conexión, así que solo se encuentran en una cabecera escrita por un cliente a través de un proxy de confianza, y la fusión solo endurece el límite; no sirve
para evadirlo. Normalizar cambiaría `ClientAddress` (2.3a, fusionada) por un caso que no ocurre. Nota fechada en `design.md` y prueba que lo documenta
(`nat64AndIpv4CompatibleAddressesAreAcceptedAsOneSharedSlash64`). Se reabre si el servidor se despliega detrás de un traductor NAT64 que entregue ese prefijo como origen real.

### Medición (`git diff --numstat HEAD -- . ':!openspec'` sobre el árbol con `git add -N`; todo es nuevo, así que es igual con y sin `-M`)

| Archivo | Líneas |
|---|---|
| `InMemoryRateLimiter.java` | 329 |
| `RateLimitPolicy.java` | 51 |
| `RateLimitDecision.java` | 44 |
| `RateLimiter.java` | 23 |
| `MutableClock.java` | 45 |
| `InMemoryRateLimiterTest.java` | 645 |
| `InMemoryRateLimiterPropertiesTest.java` | 242 |
| `InMemoryRateLimiterConcurrencyTest.java` | 169 |
| **Total** | **1 548** (447 de producción, 45 de soporte de prueba y 1 056 de pruebas) |

Pronóstico: 800 a 1 100; tope 800. No se recortó ninguna prueba ni comentario. El exceso es de pruebas: la producción mide 447.

### Costura propuesta (medida en líneas; adiciones más eliminaciones)

`InMemoryRateLimiterTest` se parte por la sección «tabla acotada» (143 líneas, 423 a 565); el resto mide 502.

| Opción | Parte | Contenido | Líneas |
|---|---|---|---|
| A (sin partir, `size:exception`) | PR 10 | Todo | 1 548 (748 sobre el tope) |
| B (recomendada) | PR 10a `rate-limiter-core` | Puerto, decisión, política, adaptador (447), `MutableClock` (45) y `InMemoryRateLimiterTest` sin la sección de la tabla (502) | **994** (194 sobre el tope: sigue necesitando excepción) |
| B | PR 10b `rate-limiter-table-and-stress` | La sección de la tabla como clase `InMemoryRateLimiterTableTest` (143 más unas 55 líneas de cabecera y utilidades que necesita la clase nueva, estimadas), `InMemoryRateLimiterPropertiesTest` (242) y `InMemoryRateLimiterConcurrencyTest` (169) | unas **609** (estimación; el resto medido) |
| C (la que insinúa `tasks.md`) | PR 10a | Producción (447), `MutableClock` (45) y `InMemoryRateLimiterTest` completa (645) | 1 137 |
| C | PR 10b | Propiedades (242) y concurrencia (169) | 411 |

No hay costura por archivos que deje ambas partes dentro de 800 sin separar la producción de sus pruebas. B deja la segunda parte dentro del tope; la primera lleva el código y la mayoría de las
pruebas del contrato y necesita una excepción de unas 194 líneas. A es una sola unidad cohesiva (447 de producción) con una excepción de 748.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='InMemoryRateLimiter*'`: `Tests run: 140, Failures: 0, Errors: 0, Skipped: 0`; cierre por `./mvnw verify`: Surefire 186 + 1 007, Failsafe 229 |
| Arnés de ejecución | Hilos reales con `CyclicBarrier` sobre un mismo `InMemoryRateLimiter` y un `MutableClock`; el limitador no tiene consumidor ni borde HTTP en esta tarea (3.2), así que no hay otro arnés en ejecución |
| Frontera de reversión | Se retiran `RateLimiter`, `RateLimitDecision`, `RateLimitPolicy`, `InMemoryRateLimiter`, `MutableClock` y las tres clases de prueba; ningún archivo existente cambia |

## Tarea 3.1a: PR 10a `rate-limiter-core` (costura B aprobada por el propietario, 2026-10-05)

El propietario aprobó la costura B de la tarea 3.1 (la anterior, detenida con 1 548 líneas) y una excepción de tamaño de unas 194 líneas para 3.1a; el cambio pasa a 20 tareas y 19 PR
(`tasks.md`, nota fechada del final). Rama `change/web-edge-foundations-rate-limiter-core` desde `main` en `e557639`; el código sale de `wip/web-edge-rate-limiter-core-full`
(`7b8baf9`, sin tocarla). 3.1b se construye después desde `main` actualizado.

### Evidencia del ciclo TDD (reobservada sobre este árbol)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO | `-Dtest='InMemoryRateLimiter*'` con `MutableClock` e `InMemoryRateLimiterTest` (sin la sección de la tabla) y sin producción | `COMPILATION ERROR`: `package com.confia.shared.security.RateLimitDecision does not exist` y `cannot find symbol` (126 líneas de error) |
| VERDE | Con la producción (sin `size()`) | `Tests run: 47, Failures: 0, Errors: 0, Skipped: 0` |
| Cierre | `./mvnw verify` completo | Surefire 186 + 914 (867 más 47), Failsafe 229, `All coverage checks have been met`, `BUILD SUCCESS` |

Pruebas añadidas: 47. Cobertura de las clases nuevas en esta parte: `RateLimitPolicy` y `RateLimitDecision` al 100 %; `InMemoryRateLimiter` deja sin cubrir el barrido y la reclamación (19 líneas
y 19 ramas), que prueba 3.1b; el umbral de módulo (80 %) se cumple.

### Demostraciones deliberadas (cada una revertida; `cmp` contra la copia original sin diferencias)

| Ruptura temporal | Resultado observado |
|---|---|
| Capa 2: terminar la restricción cuando los fallos de la ventana bajan del umbral | `Tests run: 47, Failures: 3`: `aFailureCountsOnlyWhileItsAgeIsStrictlyBelowTheWindow` (`expected: Limited[retryAfter=PT50S] but was: Admitted[]`), `theRestrictionEndsAtTheOneHourCapEvenIfTheIpKeepsFailing` (`expected: Admitted[] but was: Limited[retryAfter=PT1M]`) y `theRestrictionIsNotLiftedWhileTheIpKeepsFailing` (`expected: Limited[retryAfter=PT1M] but was: Admitted[]`). |
| `>=` por `>` en el descarte de admisiones | `Tests run: 47, Failures: 0`: **sin fallo**, como se declaró en 3.1: es un mutante equivalente para las decisiones y solo lo distinguen las pruebas de borde de reclamación, que van en 3.1b. |

### Desviaciones respecto de 3.1 completa (declaradas)

1. `size()` no está en 3.1a (ninguna prueba de esta parte lo usa); llega con 3.1b.
2. Las pruebas de borde de reclamación van en 3.1b, con la tabla (razón en la nota fechada de `tasks.md`).
3. «Tamaño máximo no válido» (política) apunta a 3.1a; los otros cuatro escenarios de la tabla y «Exactitud bajo concurrencia», a 3.1b.

### Medición del PR 10a (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 983 | 0 | **983** |
| Con `-M` | 983 | 0 | **983** |

`InMemoryRateLimiter` 324, `RateLimitPolicy` 51, `RateLimitDecision` 44, `RateLimiter` 23, `MutableClock` 45, `InMemoryRateLimiterTest` 496. Pronóstico de la costura: 994; excepción de tamaño: unas
183 líneas (aprobadas unas 194). No se recortó nada.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='InMemoryRateLimiter*'`: `Tests run: 47, Failures: 0`; cierre por `./mvnw verify`: Surefire 186 + 914, Failsafe 229 |
| Arnés de ejecución | N/A: el limitador no tiene consumidor ni borde HTTP hasta 3.2; las pruebas ejercen el adaptador real con `MutableClock` |
| Frontera de reversión | Se retiran `RateLimiter`, `RateLimitDecision`, `RateLimitPolicy`, `InMemoryRateLimiter`, `MutableClock` e `InMemoryRateLimiterTest`; ningún archivo existente cambia |

### Revisión de seguridad del limitador en memoria (2026-10-05)

Riesgo evaluado: alto. La revisión independiente cubrió la porción 3.1a y el árbol completo de la rama local
`wip/web-edge-rate-limiter-core-full`. Veredicto: 0 bloqueantes, 3 importantes y 4 sugerencias. **3.1a es aprobable**:
el limitador no tiene consumidor ni bean, así que nada es alcanzable hoy. La lógica de las dos capas, la reserva
por CAS y la liberación tras la eliminación son correctas.

| Hallazgo | Asignación |
|---|---|
| I-1: el reloj es de pared (`Clock.instant()`), no monotónico | 3.1b, que debe fusionarse antes de 3.2 |
| I-2: autodenegación con muchas /64 (la tabla falla cerrada) | 3.2: declararla en `docs/03`, métrica y alerta de `CapacityExhausted`; límite por /48 o /32 en el borde, cambio 11 |
| I-3: el muestreo de `ConcurrentHashMap.size()` no es atómico | 3.1b |
| S-1: el reloj se lee antes de `compute` | 3.1b, junto con I-1 |
| S-2: cota razonable de los límites de la política | 3.1b o 3.2, validándola en `RateLimitPolicy` |
| S-3: costo del barrido con tablas grandes | Seguimiento: medir si `maxEntries` llega a cientos de miles |
| S-4: `NaiveReference` comparte la lectura de las reglas | Aceptada: la mitigan las pruebas escritas a mano contra los escenarios |

## Intento completo de la tarea 3.1b original (VERIFICADO Y DETENIDO ANTES DEL COMMIT por tamaño: 1 025 líneas frente a 800; el propietario aprobó la costura B sin excepción)

Rama `change/web-edge-foundations-rate-limiter-table` desde `main` en `014c3b0` (tras fusionar el PR 96). Sin commits. El árbol de trabajo contiene el código y las pruebas verificados, más la nota fechada de `design.md` (S-2 y el diseño de I-1). La tarea 3.1b **no** se marca `[x]`: falta decidir la costura (abajo). Fuente de las pruebas: `wip/web-edge-rate-limiter-core-full` (sin tocarla; su punta es `19baade`, hijo de `7b8baf9`, ambos intactos).

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| Red de seguridad | `InMemoryRateLimiterTest` de `main` (ya recortada) | 47 pruebas en verde en la línea base de 3.1a (Surefire 186 + 914, Failsafe 229) |
| ROJO | `-Dtest='InMemoryRateLimiter*'` con `MutableClock` convertida en fuente de nanosegundos, `InMemoryRateLimiterTest` adaptada y con las pruebas de S-2, las clases `InMemoryRateLimiterTableTest`, `...PropertiesTest`, `...ConcurrencyTest` y `...TimeTest` y sin producción | `COMPILATION ERROR`, 52 líneas de error: `MutableClock cannot be converted to java.time.Clock` y `cannot find symbol` (`size()`, `reservedForTest()`) |
| VERDE | Mismo comando, con la producción | `Tests run: 161, Failures: 0, Errors: 0, Skipped: 0` (concurrencia 80, tabla 10, `InMemoryRateLimiterTest` 53, tiempo 15, propiedades 3). La cobertura de JaCoCo falla con `-Dtest=` acotado, como avisa `tasks.md`. |
| Cierre | `./mvnw verify` completo | Surefire 186 + 1 028 (los 914 de la línea base más 114 nuevos), Failsafe 229, `All coverage checks have been met`, `BUILD SUCCESS`. Instantánea OpenAPI sin cambios (`git status` limpio de ella). |

Pruebas añadidas: 114 (`InMemoryRateLimiterTest` +6 de S-2, `InMemoryRateLimiterTableTest` 10, `InMemoryRateLimiterTimeTest` 15, `InMemoryRateLimiterPropertiesTest` 3, `InMemoryRateLimiterConcurrencyTest` 80).

### Diseño de I-1, S-1, I-3 y S-2

- **I-1.** El constructor público es `InMemoryRateLimiter(RateLimitPolicy)` y usa `System::nanoTime`; el de `(RateLimitPolicy, LongSupplier)` es de paquete. 3.2 cablea el primero y no tiene reloj de pared que inyectar. `MutableClock` pasó a ser una fuente `LongSupplier` de nanosegundos (arranque arbitrario negativo: solo importan las diferencias). La comparación de la barrida es una diferencia (`now - due < 0`), no `<`. Javadoc corregido (ya no dice que el reloj «solo puede hacer esperar más»).
- **Prueba de I-1.** `InMemoryRateLimiterTimeTest`: (a) inspecciona con ArchUnit las dependencias y llamadas del limitador y sus clases anidadas (ni `Clock`, `Instant`, `LocalDateTime`, `ZonedDateTime`, `OffsetDateTime`, `Date`, ni `System.currentTimeMillis`, `Instant.now`, `Clock.*`); (b) las capas 1 y 2 y la barrida deciden igual con el contador empezando en 0, -1, `Long.MIN_VALUE` y a 5, 45 y 700 s del desbordamiento de `Long.MAX_VALUE`; (c) el constructor de producción cuenta con `System.nanoTime`. **Desviación:** no existe una prueba que mueva el reloj de pared del sistema (no se puede mover desde una prueba); la garantía es que el limitador ya no depende de ninguno, y la comprueba (a).
- **S-1.** La hora se lee dentro del `compute` (y del `computeIfPresent` de la barrida). Prueba determinista sin esperas por reloj: un hilo lento lee la hora y se retiene hasta que otro, que lee una hora posterior, queda `BLOCKED` o termina.
- **I-3.** Los muestreadores leen `reservedForTest()` (contador atómico de ranuras) con `Thread.onSpinWait()`; `size() == reservedForTest()` se comprueba solo en reposo.
- **S-2.** `requestLimit` y `failureThreshold` con cota de 10 000 en `RateLimitPolicy`; nota fechada en `design.md`.

### Demostraciones deliberadas (cada una revertida; `cmp` contra la copia original sin diferencias)

| Ruptura temporal | Resultado observado |
|---|---|
| Desalojar una entrada y reintentar en lugar de rechazar | `Tests run: 161, Failures: 57`: concurrencia 40 (`aTableOfNHoldingNoOneAdmitsExactlyNOfTwoNIpsAndNeverGrowsPastN`), tabla 10 (`expected: CapacityExhausted[]`), tiempo 6 y la propiedad de la tabla |
| `>=` por `>` en el descarte de admisiones | `Failures: 1`: `InMemoryRateLimiterTableTest.anEntryIsReclaimedExactlyWhenItsLastRequestLeavesTheWindow`: `expected: Admitted[] but was: CapacityExhausted[]` |
| `>=` por `>` en el tope de la restricción | `Failures: 1`: `InMemoryRateLimiterTest.theRestrictionEndsAtTheOneHourCapEvenIfTheIpKeepsFailing:282` |
| Registrar el rechazo (`admitted.add(now)` antes de `Limited`) | `Failures: 4`: `aRejectedRequestIsNeverRecordedAndNeverMovesTheWindow`, `theWindowSlidesRequestByRequest`, `aLimitAndAThresholdLargerThanTheFirstAllocationAreCountedExactly` y la propiedad contra el modelo |
| Barrer en cada llamada | `Failures: 1`: `InMemoryRateLimiterTableTest.theTableIsSweptAtMostOnceASecond` |
| I-1: `now()` lee `Instant.now()` (fuente de pared) | `Failures: 77`, entre ellas `theLimiterDependsOnNoWallClockAndReadsNoWallTime`: `[types of a wall clock] Expecting empty but was: ["java.time.Instant", "java.time.Instant", "java.time.Instant"]`, y las 12 de contador arbitrario y de barrida |
| S-1: leer la hora antes del `compute` | `Failures: 1`: `aTimestampIsReadInsideTheLockOfItsClientSoTheRingStaysInOrder`: `[the earlier time is recorded first] expected: Admitted[] but was: Limited[retryAfter=PT1M10S]` |
| Comparación de la barrida con `<` absoluto | `Failures: 1`: `theSweepRecoversRoomAcrossTheWrapOfTheCounter` (el arranque a 5 s del desbordamiento) |

### Concurrencia: cinco ejecuciones seguidas de `InMemoryRateLimiterConcurrencyTest` solo

`Tests run: 80, Failures: 0, Errors: 0, Skipped: 0` en las cinco (400 repeticiones), más las ejecuciones enfocadas y el `verify` completo, sin ninguna falla intermitente.

### Medición (`git diff --numstat HEAD -- . ':!openspec'` con `git add -N`; igual con y sin `-M`)

| Archivo | Adiciones | Eliminaciones |
|---|---|---|
| `InMemoryRateLimiter.java` | 62 | 33 |
| `RateLimitPolicy.java` | 16 | 0 |
| `MutableClock.java` | 22 | 26 |
| `InMemoryRateLimiterTest.java` | 27 | 4 |
| `InMemoryRateLimiterTimeTest.java` | 222 | 0 |
| `InMemoryRateLimiterTableTest.java` | 192 | 0 |
| `InMemoryRateLimiterPropertiesTest.java` | 240 | 0 |
| `InMemoryRateLimiterConcurrencyTest.java` | 181 | 0 |
| **Total** | **962** | **63** = **1 025** |

Pronóstico: unas 609 más I-1, I-3, S-1 y S-2. Las tres clases de la tabla, las propiedades y la concurrencia miden 613; el resto (412: `TimeTest` 222, producción 111, `MutableClock` 48 y `InMemoryRateLimiterTest` 31) es el trabajo de la revisión. No se recortó ninguna prueba ni comentario.

### Propuesta de costura (pendiente de decisión del propietario)

| Opción | Parte | Contenido | Líneas |
|---|---|---|---|
| A (`size:exception`) | PR 10b | Todo | 1 025 (225 sobre el tope) |
| B (recomendada) | PR 10b | I-1, S-1 y S-2 con `TimeTest` (412) y `InMemoryRateLimiterTableTest` (192) | **604** |
| B | PR 10c | `InMemoryRateLimiterPropertiesTest` (240) y `InMemoryRateLimiterConcurrencyTest` (181) con I-3 | **421** |

Con B los dos PR quedan dentro de 800; el cambio pasaría a 21 tareas y 20 PR. 3.2 necesitaría 3.1b y 3.1c fusionadas (la exactitud bajo concurrencia es de 3.1c).

### Decisión del propietario (2026-10-05): costura B sin excepción de tamaño

El árbol completo de arriba se conserva en la rama **local** `wip/web-edge-rate-limiter-table-full` (`faf1878`, nunca se publica). La tarea se parte en **3.1b** (I-1, S-1, S-2, `InMemoryRateLimiterTimeTest` e `InMemoryRateLimiterTableTest`; 596 líneas medidas) y **3.1c** (`InMemoryRateLimiterPropertiesTest`, `InMemoryRateLimiterConcurrencyTest` con I-3 y `reservedForTest()`; unas 421). El cambio pasa a **21 tareas y 20 PR**; 3.1c necesita 3.1b y 3.2 necesita 3.1c. Los números de arriba (114 pruebas, 1 025 líneas, demostraciones y cinco ejecuciones de concurrencia) son del árbol completo; los de la parte construida están abajo y las demostraciones de 3.1c se repetirán al construirla.

## Tarea 3.1b: PR 10b `rate-limiter-table` (parte construida)

Rama `change/web-edge-foundations-rate-limiter-table` desde `main` en `014c3b0`; el código y las pruebas salen de `wip/web-edge-rate-limiter-table-full` (sin tocarla), salvo `reservedForTest()`, que se queda en 3.1c porque solo lo usa su prueba de concurrencia. Se verificó **en solitario**, sin las clases de propiedades y concurrencia.

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| Red de seguridad | `InMemoryRateLimiterTest` de `main` | 47 pruebas en verde (Surefire 186 + 914, Failsafe 229) |
| ROJO | `-Dtest='InMemoryRateLimiter*'` con `MutableClock` de nanosegundos, `InMemoryRateLimiterTest` adaptada, `InMemoryRateLimiterTableTest` e `InMemoryRateLimiterTimeTest` y sin producción | `COMPILATION ERROR`, 28 líneas de error (`MutableClock cannot be converted to java.time.Clock`, `cannot find symbol`) |
| VERDE | Mismo comando, con la producción | `Tests run: 78, Failures: 0, Errors: 0, Skipped: 0` (tabla 10, `InMemoryRateLimiterTest` 53, tiempo 15). La cobertura de JaCoCo falla con `-Dtest=` acotado. |
| Cierre | `./mvnw verify` completo | Surefire 186 + 945 (los 914 de la línea base más 31 nuevos), Failsafe 229, `All coverage checks have been met`, `BUILD SUCCESS`. Instantánea OpenAPI sin cambios. |

Pruebas añadidas: 31 (`InMemoryRateLimiterTest` +6 de S-2, tabla 10, tiempo 15).

### Demostraciones deliberadas (cada una revertida; `cmp` contra la copia original sin diferencias)

| Ruptura temporal | Resultado observado (sobre las 78) |
|---|---|
| I-1: `now()` lee `Instant.now()` | `Failures: 35`, entre ellas `theLimiterDependsOnNoWallClockAndReadsNoWallTime`: `[types of a wall clock] Expecting empty but was: ["java.time.Instant", "java.time.Instant", "java.time.Instant"]` |
| S-1: leer la hora antes del `compute` | `Failures: 1`: `aTimestampIsReadInsideTheLockOfItsClientSoTheRingStaysInOrder`: `[the earlier time is recorded first] expected: Admitted[] but was: Limited[retryAfter=PT1M10S]` |
| `>=` por `>` en el tope de la restricción | `Failures: 1`: `theRestrictionEndsAtTheOneHourCapEvenIfTheIpKeepsFailing` (`expected: Admitted[] but was: Limited[retryAfter=PT1M]`) |
| `>=` por `>` en el descarte de admisiones | `Failures: 1`: `anEntryIsReclaimedExactlyWhenItsLastRequestLeavesTheWindow` (`expected: Admitted[] but was: CapacityExhausted[]`) |
| Barrer en cada llamada | `Failures: 1`: `theTableIsSweptAtMostOnceASecond` |
| Registrar el rechazo | `Failures: 3`: `aRejectedRequestIsNeverRecordedAndNeverMovesTheWindow`, `theWindowSlidesRequestByRequest` y `aLimitAndAThresholdLargerThanTheFirstAllocationAreCountedExactly` |
| Comparar la barrida con `<` absoluto | `Failures: 1`: `theSweepRecoversRoomAcrossTheWrapOfTheCounter` |

### Medición del PR 10b (`git diff --numstat main...HEAD -- . ':!openspec'`)

| Medición | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Sin `-M` | 533 | 63 | **596** |
| Con `-M` | 533 | 63 | **596** |

`InMemoryRateLimiter` 54 + 33, `RateLimitPolicy` 16, `MutableClock` 22 + 26, `InMemoryRateLimiterTest` 27 + 4, `InMemoryRateLimiterTimeTest` 222, `InMemoryRateLimiterTableTest` 192. Dentro del tope de 800.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `-Dtest='InMemoryRateLimiter*'`: `Tests run: 78, Failures: 0`; cierre por `./mvnw verify`: Surefire 186 + 945, Failsafe 229 |
| Arnés de ejecución | N/A: el limitador no tiene consumidor ni borde HTTP hasta 3.2; las pruebas ejercen el adaptador real, el constructor de producción con `System.nanoTime` y un hilo lento y uno rápido sobre la misma clave |
| Frontera de reversión | `InMemoryRateLimiter` y `RateLimitPolicy` vuelven a su versión de 3.1a, `MutableClock` e `InMemoryRateLimiterTest` también, y se retiran `InMemoryRateLimiterTableTest` e `InMemoryRateLimiterTimeTest` |

### Diseño de I-1 para el bean de 3.2

El constructor público es `InMemoryRateLimiter(RateLimitPolicy)` y usa `System::nanoTime`; el de `(RateLimitPolicy, LongSupplier)` es de paquete. 3.2 cablea `new InMemoryRateLimiter(policy)` y no tiene reloj de pared que inyectar. Cota de S-2: 10 000 para `requestLimit` y `failureThreshold`.

### Revisión independiente de 3.1b (2026-10-05)

Riesgo evaluado: alto. Veredicto: aprobado, sin bloqueantes. La revisión confirmó que toda comparación de tiempo usa
diferencias (`now - x` contra una duración, y `now - due < 0` para el barrido), así que el desbordamiento del contador
de `System.nanoTime` es seguro. También confirmó que las decisiones de las capas 1 y 2 de 3.1a no cambian, que la cota
de 10 000 de S-2 es razonable y que la prueba de ArchUnit «sin reloj de pared» no es vacua.

- **Hallazgo «importante» refutado por el orquestador.** La revisión afirmó que inicializar `nextSweepAt` con la hora
  de construcción impide barrer durante el primer segundo. Es falso: la condición es `now - due < 0`, así que el
  barrido está vencido desde el instante de construcción. Se probó cambiando la inicialización a `now - 1 s`, sin que
  cambiara ningún resultado. No se modificó el código de producción. Sí se añadió
  `aFullTableIsSweptOnTheFirstRefusalWithoutWaitingASecond`, con ventanas de milisegundos, que **falla** si el primer
  vencimiento se retrasa (`now + 1 s`: `assertAdmitted` en la línea 214), para fijar la propiedad.
- **Sugerencia aplicada.** `anEntryIsReclaimedExactlyWhenItsRestrictedIntervalEnds` prueba el borde exacto del
  intervalo restringido (1 ns antes y exactamente al final, con limitadores separados). La mutación `<` → `<=` en
  `isReclaimable` ahora falla en la línea 199 y se revirtió con `cmp`.
- **Seguimientos.** Una cota superior para `maxEntries` (del orden de 10⁶) y ampliar `WALL_CLOCK_TYPES` con
  `LocalDate`, `Calendar` y `java.sql.Timestamp`. Ambos son de bajo valor.
- `./mvnw verify` completo: Surefire 186 + 947, Failsafe 229, `BUILD SUCCESS`.

### Tarea 3.1c (2026-10-05)

Traslado directo, hecho por el orquestador, desde la rama local verificada `wip/web-edge-rate-limiter-table-full`
(`faf1878`): `InMemoryRateLimiterPropertiesTest` e `InMemoryRateLimiterConcurrencyTest` sin cambios, más
`int reservedForTest()` de paquete en `InMemoryRateLimiter` (I-3: el contador atómico de ranuras reservadas).

| Paso | Resultado |
|---|---|
| ROJO | Error de compilación: `cannot find symbol` en `InMemoryRateLimiterConcurrencyTest.java:[165,49]` y `[173,27]` (`reservedForTest()`) |
| VERDE | `ConcurrencyTest` 80/80 y `PropertiesTest` 3/3 |
| Cinco ejecuciones seguidas de la clase de concurrencia, sola | 80/80, 80/80, 80/80, 80/80, 80/80 |
| Ruptura: la tabla nunca rechaza (`reserveSlot` devuelve `true` al estar llena, como cualquier desalojo) | `ConcurrencyTest` `Failures: 40` de 80, `expected: 50L`. Revertida con `cmp` |
| Ruptura: la restricción se levanta cuando los fallos de la ventana bajan del umbral (regla descartada) | `PropertiesTest` `Failures: 1`, `expected: Limited[retryAfter=PT19.000000001S]` contra el modelo ingenuo. Revertida con `cmp` |
| `./mvnw verify` completo | Surefire 186 + 1030, Failsafe 229, `BUILD SUCCESS` |

Con 3.1a, 3.1b y 3.1c fusionadas, la tarea 3.1 queda completa. 3.2 ya puede empezar.

### Revisión independiente de 3.1c (2026-10-05)

Riesgo evaluado: alto, aunque el único cambio de producción es `reservedForTest()`, de paquete. Veredicto: aprobado,
sin bloqueantes, con 2 hallazgos importantes y 3 sugerencias. El orquestador corrigió los dos importantes:

- **I-1: el muestreador podía no llegar a muestrear mientras corrían los hilos de trabajo,** y entonces
  `largest <= N` pasaba en vacío con `largest` en 0. Ahora `startSampler` devuelve el hilo solo después de la primera
  muestra (`CountDownLatch`), cuenta las muestras en un `AtomicLong` y `assertSampled` exige que haya muestras
  posteriores al arranque. **Ruptura:** sin `samples.incrementAndGet()` fallan 40 de 80 con
  `[samples taken while the workers ran]`. Revertida con `cmp`.
- **I-2: el nombre prometía más de lo que se mide.** Las pruebas acotan las **reservas**, no el tamaño instantáneo del
  mapa, del que no existe una instantánea. Se renombraron a `...NeverReservesPastN` y
  `...NeverReservesPastTheTable`, y el Javadoc del muestreador declara que la propiedad «el mapa nunca tiene N + 1
  entradas en ningún instante» (liberar el hueco solo después de quitar la entrada) queda sin muestrear a propósito.
- **Sugerencias registradas como seguimiento:** una etiqueta de cobertura de jqwik específica para «restringida con menos
  fallos que el umbral en la ventana»; `Thread.yield()` o un tope de muestras en runners con pocas vCPU. La independencia
  de `NaiveReference` es de estructura de datos y no de especificación; se acepta, porque la mitigan las pruebas
  escritas a mano.
- Cinco ejecuciones seguidas de la clase de concurrencia, sola: 80/80 en las cinco. `./mvnw verify` completo:
  Surefire 186 + 1030, Failsafe 229, `BUILD SUCCESS`.

## Tarea 3.2: PR 11 `rate-limiter-edge` (VERIFICADA Y DETENIDA ANTES DEL COMMIT por tamaño: 1 551 líneas frente a 800)

Árbol completo en la rama local `wip/web-edge-rate-limiter-edge-full` (nunca se sube). La rama de trabajo `change/web-edge-foundations-rate-limiter-edge` queda limpia en `main` (`98ad2ef`). `tasks.md` **no** marca 3.2 como hecha: la tarea no se entregó. Incluye la decisión del propietario del 2026-10-05 sobre la métrica de I-2.

### Evidencia del ciclo TDD (modo estricto; ejecutor `./mvnw verify` desde `apps/api`)

| Paso | Orden | Resultado observado |
|---|---|---|
| Red de seguridad | Línea base indicada por el orquestador (Surefire 186 + 1030, Failsafe 229) | Sin fallos previos |
| ROJO A (códigos, catálogo y mapeo del contenedor) | `-Dtest='ProblemCodeTest,ProblemCatalogCoverageTest,ProblemErrorReportValveTest,ProblemTranslationTest'` con las tablas ya ampliadas | `Tests run: 105, Failures: 10, Errors: 4, Skipped: 0` (ProblemCodeTest 44: 5 fallos y 2 errores; ProblemErrorReportValveTest 20: 4 fallos; ProblemCatalogCoverageTest 7: 1 fallo; ProblemTranslationTest 34: 2 errores `NoSuchElement`) |
| VERDE A | Igual, tras `ProblemCode` (`TOO_MANY_REQUESTS` 429, `CAPACITY_EXCEEDED` 503, `forStatus`), las dos claves del catálogo y el Javadoc de la válvula | `Tests run: 105, Failures: 0, Errors: 0, Skipped: 0` |
| ROJO B (resto) | `./mvnw -q -pl app -am test-compile` con `RateLimitInterceptorTest`, `RateLimitPropertiesTest`, `RateLimitEdgeTest`, `LogRateLimitMetricsTest`, `ProblemExceptionHandlerTest` ampliado y el arnés ampliado | `COMPILATION ERROR` (`cannot find symbol` en `RateLimited`, `RateLimitInterceptor`, `RateLimiterRegistry`, `RateLimitProperties`, `RateLimitMetrics`, `LogRateLimitMetrics`, `ThrottlingConfiguration`, `ObservabilityMetricsConfiguration`, `CapacityExceededException`, `TooManyRequestsException`), el rojo previsto |
| VERDE B | `-Dtest='RateLimitInterceptorTest,RateLimitPropertiesTest,LogRateLimitMetricsTest,ProblemExceptionHandlerTest,RateLimitEdgeTest'` | `Tests run: 63, Failures: 0, Errors: 0, Skipped: 0` (LogRateLimitMetricsTest 6, ProblemExceptionHandlerTest 21, RateLimitEdgeTest 9, RateLimitInterceptorTest 9, RateLimitPropertiesTest 18). Las líneas `APPLICATION FAILED TO START` son los arranques que deben fallar (política desconocida y valor no positivo). Primera pasada: 2 fallos propios de las pruebas (el `ListAppender` se adjuntaba antes de arrancar el proceso y `LoggingApplicationListener` reinicia el registro; y una aserción que prohibía el nombre de la política en la lista de conocidas), corregidos en la prueba, nunca en el código |
| ROJO de `ProcessBeanPolicy` (primero la línea) | Las dos líneas (`com.confia.shared.web.ratelimit` y `com.confia.shared.observability.metrics`) y la prueba nueva `onlyTheAdministrativeProcessHoldsTheRateLimiterAndItsMetrics`, sin el `@Import` en `AdminApplication` | `Tests run: 7, Failures: 2, Errors: 0`: `registersOnlyItsAllowedBeans[1]` falla con `non-vacuous: com.confia.shared.web.ratelimit must contribute a bean to the admin context` **y** `non-vacuous: com.confia.shared.observability.metrics must contribute a bean to the admin context`; la prueba nueva falla en el proceso administrativo |
| VERDE de `ProcessBeanPolicy` | Con el `@Import` de `ObservabilityMetricsConfiguration` y `ThrottlingConfiguration` en `AdminApplication` | `ProcessBeanIsolationTest` 7/7, `AdminProductionWiringTest` 13/13, `ConfiaApplicationTest` 10/10: `Tests run: 30, Failures: 0, Errors: 0` |
| Cierre, primera pasada | `./mvnw verify` completo | Surefire 186 + 1087 con **1 fallo**: `IdempotencyScopeExclusionInventoryTest` (lista de permitidos de `..web..` hacia `shared.security`: `RateLimiterRegistry` depende de `RateLimiter`). Rojo legítimo del inventario («añade el siguiente aquí») |
| Cierre | `./mvnw verify` completo tras ampliar esa lista con los siete tipos del limitador | **Surefire 186 + 1087 (los 1030 de la línea base y 57 nuevos), Failsafe 229, `BUILD SUCCESS`** |

### Demostraciones deliberadas (cada una revertida de inmediato; `cmp` contra la copia original sin diferencias)

1. **`tryAcquire` movido a `postHandle`** (`preHandle` devuelve `true` y la decisión corre después del controlador): `RateLimitEdgeTest` `Tests run: 9, Failures: 6` y `RateLimitInterceptorTest` `Tests run: 9, Failures: 7`. Mensaje de `aRejectedRequestReachesNeitherTheControllerNorTheUseCase`: `expected: 429 but was: 200`. `cmp` de `RateLimitInterceptor.java` contra la copia: igual.
2. **Se quita la llamada `metrics.capacityExhausted(...)` del interceptor:** `RateLimitEdgeTest` `Tests run: 9, Failures: 1` (`aFullTableLeavesOneFixedWarnWithThePolicyAndNothingOfTheClient`: `Expected size: 1 but was: 0 in: []`) y `RateLimitInterceptorTest` `Tests run: 9, Failures: 5` (`aFullTableIsRefusedAsLackOfCapacityAndReportedOnceWithThePolicyOnly`: `Expecting actual: [] to contain exactly (and in same order): ["admin-login"]`, y las cuatro de fallo cerrado). `cmp`: igual.

### Puerto y adaptador de la métrica (decisión del propietario, I-2)

- **Puerto** `RateLimitMetrics` (`shared.web.ratelimit`): `void capacityExhausted(String policy)`. La política es el único argumento. El interceptor lo llama cuando la decisión es `CapacityExhausted`, incluida la que sale de un fallo cerrado (sin origen, sin dirección, política ausente del registro o excepción del limitador).
- **Adaptador interino** `LogRateLimitMetrics` (`shared.observability.metrics`): un evento `WARN` fijo, mensaje `rate limit capacity exhausted`, con los pares clave-valor `event=rate_limit_capacity_exhausted`, `policy` y `suppressed` (SLF4J 2 `atWarn().addKeyValue`). Acotado a un evento por segundo **por política** con una fuente monotónica (constructor público `System::nanoTime`, de paquete con `LongSupplier` para pruebas) y una ventana por política que guarda cuántas señales se omitieron. Sin IP, ruta, cabecera ni clave (el puerto no las conoce). Sin dependencias nuevas.
- **Registro (ADR-0024):** `ObservabilityMetricsConfiguration` en el `@Import` de `AdminApplication`; `package-info` con `@NamedInterface` y su consumidor (ADR-0022); línea `com.confia.shared.observability.metrics` en `ProcessBeanPolicy` (en rojo primero, ver arriba). Portal y trabajador no la cargan.
- **Pruebas:** `LogRateLimitMetricsTest` (6: campos exactos y orden, ningún dato del cliente, 1 000 llamadas en un instante son un evento y el siguiente trae `suppressed=1000`, el límite exacto de un segundo con un nanosegundo de diferencia, un segundo tranquilo reinicia el contador, una ventana por política, el constructor de producción) con reloj inyectado; `RateLimitEdgeTest` lee el evento por la cadena real. El evento se escribe **antes** de la respuesta, así que no hay carrera con la lectura del registro; el `ListAppender` se adjunta **después** de arrancar el proceso.
- Notas fechadas: `design.md` (final), `docs/07-observabilidad-y-operaciones.md` (§5.4) y `docs/03-seguridad.md` §4.4 y §10 (limitador interino, sus tres limitaciones, la semántica de la capa 2, el riesgo de autodenegación I-2 y sus tres mitigaciones).

### Decisión sobre el mapeo `429` y `503` del contenedor

`ProblemCode.forStatus` mapea ahora `429` a `too-many-requests` y `503` a `capacity-exceeded` (antes `400` y `500`), de modo que `ProblemErrorReportValve.codeFor` queda coherente y la anotación de la revisión I3 de 2.2a («`503` conservará su estado cuando exista `capacity-exceeded`») se cumple. Costó dos líneas y tres tablas de pruebas ya existentes (`ProblemCodeTest`, `ProblemErrorReportValveTest`, `ProblemTranslationTest`). Efecto colateral aceptado y registrado en `design.md`: una `ResponseStatusException(503)` o un `AsyncRequestTimeoutException` del marco responde `capacity-exceeded` y se sigue registrando como error de servidor. Un `429` del marco o del contenedor no lleva `Retry-After`; ninguno existe hoy.

### Qué se construyó (resumen)

- `shared/web/ratelimit/`: `RateLimited`, `RateLimitInterceptor` (falla cerrado), `RateLimiterRegistry`, `RateLimitPolicyCheck` (arranque: política desconocida nombrada, antes de aceptar conexiones), `RateLimitProperties` (registro enlazado por constructor, prefijo `confia.web.rate-limit.admin-login`, el mensaje nombra la propiedad también para la cota de 10 000), `RateLimitMetrics`, `TooManyRequestsException`.
- `shared/web/problem/`: `CapacityExceededException`, los dos códigos, los dos manejadores en `ProblemExceptionHandler` (con la guarda de respuesta ya confirmada y `Retry-After` solo en el `429`) y dos claves del catálogo sin números.
- `shared/web/edge/ThrottlingConfiguration` (política `admin-login` registrada y **no aplicada** a ninguna ruta de producción; `new InMemoryRateLimiter(policy)` con el constructor público de `System::nanoTime`) y su `@Import` en `AdminApplication`.
- Arnés: `LimitedController` (ruta `GET /test/limited` con `@RateLimited(policy = "admin-login")` y un caso de uso de prueba que cuenta invocaciones), `UnknownPolicyController` (solo con `harness.unknown-policy=true`), contadores en `Calls`, y las importaciones y rutas públicas en `WebEdgeHarness`.
- `OpenApiContractSnapshotTest` y `portal.routes.json` sin cambios (`git status` limpio de ambos tras `verify`).

### Medición (`git add -A` y `git diff --cached --numstat -- . :!openspec`; igual con y sin `-M`, todo es nuevo salvo ediciones)

| Parte | Líneas (adiciones + eliminaciones) |
|---|---|
| Producción (`src/main`) | 581 |
| Pruebas (`src/test`) | 907 |
| Documentación (`docs/`) | 63 |
| **Total** | **1 551** (1 528 adiciones y 23 eliminaciones) |

El pronóstico era de 500 a 700. Se desvía porque las pruebas (`RateLimitEdgeTest` 222, `RateLimitInterceptorTest` 212, `LogRateLimitMetricsTest` 150, `RateLimitPropertiesTest` 101) y el puerto de métricas con su adaptador y su registro, que añadió el propietario con la decisión de I-2, no estaban en la previsión. No se recortó ninguna prueba ni comentario.

### Costura propuesta (tres PR apilados; líneas medidas sobre el árbol completo, con las partes compartidas repartidas)

| PR | Contenido | Líneas |
|---|---|---|
| 11a `edge-rejection-codes-and-capacity-signal` | Códigos `too-many-requests` y `capacity-exceeded` con catálogo, `forStatus` y válvula; `TooManyRequestsException`, `CapacityExceededException` y sus dos manejadores; puerto `RateLimitMetrics`, `LogRateLimitMetrics`, `ObservabilityMetricsConfiguration`, `package-info`; su línea de `ProcessBeanPolicy`, su `@Import` y la variante de la prueba de aislamiento solo de métricas; `LogRateLimitMetricsTest`; nota de `docs/07` | unas 505 (códigos 184, métricas 321) |
| 11b `edge-interceptor` | `RateLimited`, `RateLimiterRegistry`, `RateLimitInterceptor`, `RateLimitPolicyCheck` y `RateLimitInterceptorTest`, con la ampliación de la lista de `IdempotencyScopeExclusionInventoryTest` (12 líneas) | unas 407 |
| 11c `edge-throttling-wiring` | `RateLimitProperties`, `ThrottlingConfiguration`, su `@Import` y su línea de política, la prueba de aislamiento completa, el arnés (`LimitedController`, `UnknownPolicyController`, `Calls`, `WebEdgeHarness`), `RateLimitEdgeTest`, `RateLimitPropertiesTest`, notas de `docs/03` §4.4 y §10 | unas 639 |

Orden: 11a, 11b y 11c; 11b necesita 11a (el puerto y las excepciones) y 11c necesita 11b. Los tres quedan por debajo de 800 sin excepción de tamaño. Las cifras de los archivos compartidos (`AdminApplication`, `ProcessBeanPolicy`, `ProcessBeanIsolationTest`, la lista del inventario) son aproximadas y se afinan al construir cada parte.

### Desviaciones del diseño y de `tasks.md` (declaradas)

1. **Más clases de las que lista `tasks.md`:** `RateLimiterRegistry`, `RateLimitPolicyCheck` (la comprobación de arranque que pide la decisión 17), `RateLimitMetrics` y su adaptador, `ObservabilityMetricsConfiguration` y los dos controladores del arnés.
2. **`IdempotencyScopeExclusionInventoryTest`** se amplía con siete tipos de `shared.security` que las clases web del limitador usan (`RateLimiter`, `RateLimitDecision` con sus tres variantes, `RateLimitPolicy` e `InMemoryRateLimiter`). El propio inventario indica «añade el siguiente aquí».
3. **Ubicación de las excepciones:** `TooManyRequestsException` en `ratelimit` (como pide `tasks.md`) y `CapacityExceededException` en `problem`. El manejador de `problem` depende de `ratelimit` y el interceptor de `problem`: es un ciclo entre paquetes del mismo módulo `shared`, que `NoCyclesTest` (rebanadas por módulo) no prohíbe. No se movió la excepción para no partir lo que `tasks.md` fija.
4. **Mapeo del contenedor** de `429` y `503` hecho ahora (ver arriba), no solo registrado.
5. **Registro del interceptor** con un `WebMvcConfigurer` anónimo dentro de `ThrottlingConfiguration` en lugar de que la configuración lo implemente: una clase de configuración que recibe por constructor un bean que ella misma declara crea una referencia circular.
6. **`application.yml` no cambia:** los valores por omisión viven en `RateLimitProperties` y los prueban `RateLimitPropertiesTest` y `RateLimitEdgeTest`.
7. **Retry-After exacto de la capa 1 en el borde:** la prueba por la cadena real comprueba un entero entre 1 y 60 (la espera real depende del tiempo transcurrido); los valores exactos (200 ms da 1, 15 s da 15) se prueban en `RateLimitInterceptorTest` con un limitador de respuesta fija, y el valor escrito en la cabecera en `ProblemExceptionHandlerTest`.
8. **Escenario «no se abre ninguna transacción»:** el arnés no tiene base de datos; se prueba con cero invocaciones del controlador y del caso de uso de prueba. La transacción real llega con el controlador de la parte 4b.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | VERDE B y VERDE de `ProcessBeanPolicy` de arriba: `Tests run: 63, Failures: 0` y `Tests run: 30, Failures: 0`; cierre completo Surefire 186 + 1087, Failsafe 229 |
| Arnés de ejecución | `HarnessProcess` real (Tomcat, `RequestContextFilter`, cadena de seguridad real, interceptor y traductor reales), clientes distintos por `X-Forwarded-For` con el bucle local como proxy de confianza; y `ConfiaApplication.launch` real de los tres procesos para el aislamiento |
| Frontera de reversión | `ThrottlingConfiguration`, `ratelimit/`, `observability/metrics/`, el `@Import` de `AdminApplication`, las dos líneas de `ProcessBeanPolicy`, los dos códigos y manejadores y el mapeo de `forStatus` |

## Tarea 3.2a: PR 11a `edge-rejection-codes-and-capacity-signal` (costura aprobada por el propietario, 2026-10-06; sin excepción de tamaño)

Construida desde la rama de respaldo `wip/web-edge-rate-limiter-edge-full` (`cf93861`, local, nunca se publica). Verde por sí sola. La re-planificación de `tasks.md` (3.2a, 3.2b, 3.2c; 23 tareas y 22 PR) está en el commit `docs(sdd)` `2330d57`.

### Evidencia del ciclo TDD (reobservada sobre este árbol)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO A (códigos, catálogo, válvula y traducción) | `-Dtest='ProblemCodeTest,ProblemCatalogCoverageTest,ProblemErrorReportValveTest,ProblemTranslationTest'` con las tablas ya ampliadas | `Tests run: 105, Failures: 10, Errors: 4, Skipped: 0` |
| VERDE A | Igual, tras `ProblemCode`, `ProblemErrorReportValve` (Javadoc) y las dos claves del catálogo | `Tests run: 105, Failures: 0, Errors: 0, Skipped: 0` |
| ROJO del manejador | `test-compile` con `ProblemExceptionHandlerTest` ampliado | `COMPILATION ERROR`: `cannot find symbol` en `CapacityExceededException` y `package com.confia.shared.web.ratelimit does not exist` |
| VERDE del manejador | Las cinco clases (A más `ProblemExceptionHandlerTest`), tras `CapacityExceededException`, `TooManyRequestsException` y los dos manejadores | `Tests run: 126, Failures: 0, Errors: 0, Skipped: 0` |
| ROJO de la métrica | `test-compile` con `LogRateLimitMetricsTest` | `COMPILATION ERROR`: `cannot find symbol` en `LogRateLimitMetrics` |
| VERDE de la métrica | `-Dtest='LogRateLimitMetricsTest'` tras el puerto, el adaptador, la configuración y el `package-info` | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| ROJO de `ProcessBeanPolicy` (primero la línea) | La línea `com.confia.shared.observability.metrics` y la prueba nueva `onlyTheAdministrativeProcessHoldsTheMetricsAdapter`, sin el `@Import` | `Tests run: 7, Failures: 2, Errors: 0` (`non-vacuous: com.confia.shared.observability.metrics must contribute a bean to the admin context`) |
| VERDE de `ProcessBeanPolicy` | Con el `@Import` de `ObservabilityMetricsConfiguration` en `AdminApplication` | `ProcessBeanIsolationTest` 7/7 junto a `AdminProductionWiringTest` y `ConfiaApplicationTest`: `Tests run: 30, Failures: 0, Errors: 0` |
| Cierre | `./mvnw verify` completo | **Surefire 186 + 1 051 (los 1 030 de la línea base y 21 nuevos), Failsafe 229, `BUILD SUCCESS`** |

### Demostraciones deliberadas (cada una revertida de inmediato; `cmp` contra la copia original sin diferencias)

1. **Quitar la cota de un evento por segundo** (`if (true)` en lugar de comparar con el segundo): `LogRateLimitMetricsTest` `Tests run: 6, Failures: 3`; mensajes `Expected size: 1 but was: 1000` (una ráfaga de 1 000 rechazos) y `Expected size: 1 but was: 2` (el constructor de producción). `cmp`: igual.
2. **`>` en lugar de `>=` en el borde del segundo:** `Tests run: 6, Failures: 3`; mensajes `Expecting actual: [0L, 1L] to contain exactly (and in same order): [0L, 0L, 0L]` y `Expected size: 2 but was: 1`. `cmp`: igual.
3. **No escribir `Retry-After` en el manejador del `429`:** `ProblemExceptionHandlerTest` `Tests run: 21, Failures: 1`; mensaje `Expecting actual: [] to contain exactly (and in same order): ["15"]`. `cmp`: igual.

La llamada a la métrica y `tryAcquire` en `preHandle` son del interceptor y sus demostraciones se hacen en 3.2b (unidad) y 3.2c (cadena real), como dice `tasks.md`.

### Medición del PR 11a (`git diff --numstat main...HEAD -- . :!openspec`; igual con y sin `-M`)

| Parte | Líneas |
|---|---|
| Producción | 244 |
| Pruebas | 261 |
| Documentación (`docs/07`) | 12 |
| **Total** | **517** (497 adiciones y 20 eliminaciones) |

Por commit: `feat(web): add the 429 and 503 problem codes and their translators` 184 y `feat(observability): report an exhausted rate limiter table as a bounded warn event` 333. El pronóstico era de unas 505.

### Desviaciones respecto de la tarea completa 3.2 (declaradas)

1. **`IdempotencyScopeExclusionInventoryTest` no se toca:** ninguna clase de un paquete `..web..` de esta parte depende todavía de un tipo de `shared.security`; las siete entradas llegan con 3.2b (cuatro) y 3.2c (tres).
2. **La prueba de aislamiento** es la variante solo de métricas (`onlyTheAdministrativeProcessHoldsTheMetricsAdapter`); la que cubre el interceptor, el registro y las propiedades llega con 3.2c.
3. **El manejador de `problem` depende de `TooManyRequestsException` de `ratelimit`** (ciclo entre paquetes del mismo módulo `shared`, que `NoCyclesTest` no prohíbe).
4. La nota de `design.md` de esta parte cubre el puerto, el adaptador, el mapeo del contenedor y la ubicación de las excepciones; el resto (interceptor, comprobación de arranque, registro) va con 3.2b y 3.2c.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `Tests run: 126, Failures: 0` (códigos y manejadores), `Tests run: 6, Failures: 0` (adaptador) y `Tests run: 30, Failures: 0` (aislamiento); cierre completo Surefire 186 + 1 051, Failsafe 229 |
| Arnés de ejecución | `HarnessProcess` real para `ProblemTranslationTest` (`ResponseStatusException` de `429` y `503` por la cadena real) y `ConfiaApplication.launch` real de los tres procesos para el aislamiento |
| Frontera de reversión | Los dos códigos, `forStatus`, las dos claves del catálogo, las dos excepciones y sus manejadores, `RateLimitMetrics`, el paquete `observability/metrics`, su `@Import` y su línea de `ProcessBeanPolicy` |

### Revisión de seguridad independiente de 3.2a y corrección acotada (2026-10-06)

La revisión cubrió 11a y el árbol completo de 3.2 y **no encontró bloqueantes**: 11a es fusionable. Se aplicó una sola corrección acotada en 11a (commit `fdc3786`, `fix(observability): name the cause of an exhausted rate limit and keep Retry-After on framework 429s`):

| Hallazgo | Resolución |
|---|---|
| **I-2** (el mismo evento para una tabla llena, un origen ausente, una dirección nula, una política desconocida y un fallo del limitador; el Javadoc y la decisión 17 decían que «el registro distingue la causa») | Puerto `capacityExhausted(String policy, CapacityReason reason)`; `CapacityReason` cerrado (`TABLE_FULL`, `NO_ORIGIN`, `NO_ADDRESS`, `UNKNOWN_POLICY`, `LIMITER_FAILURE`); cuarto campo fijo `reason` en `LogRateLimitMetrics` (`table_full`…); cota **por política y causa** (una inundación de `table_full` no oculta un defecto raro en el mismo segundo; estado cerrado: políticas por cinco causas); el adaptador no registra excepciones ni direcciones; Javadoc corregido y nota fechada en `design.md`; `policy` debe ser una constante de compilación |
| **S-4** (`Retry-After` de un `429` del marco) | `ProblemExceptionHandler.frameworkError` lo copia de `framework.getHeaders()` para el `429` (un `503` nunca lo lleva); pruebas con y sin la cabecera y con un `503` que la trae |
| **S-5** | Nota fechada de `docs/07` §5.4: `capacity-exceeded` también responde a los `503` del marco; el operador lee el `reason` y el registro de errores |
| **S-7** | Javadoc de `LogRateLimitMetrics` y prueba: lo omitido tras el último evento se informa con el siguiente |
| **I-1, S-1, S-2, S-3** (3.2b) y **I-3, S-6** (3.2c) | Notas fechadas bajo cada tarea de `tasks.md`; no se construyen ahora |

**Evidencia.** ROJO: `test-compile` con `LogRateLimitMetricsTest` ampliado (`COMPILATION ERROR`, `cannot find symbol` en `CapacityReason`); tras el puerto y el adaptador, `LogRateLimitMetricsTest` `Tests run: 14, Failures: 0` y `ProblemExceptionHandlerTest` `Tests run: 24, Failures: 1` (`aFrameworkTooManyRequestsKeepsItsRetryAfterAndNeverItsReason`, el rojo de S-4); VERDE: `LogRateLimitMetricsTest`, `ProblemExceptionHandlerTest` y `ProblemTranslationTest` `Tests run: 72, Failures: 0`. **Demostración deliberada:** quitar el campo `reason` del evento da `LogRateLimitMetricsTest` `Tests run: 14, Failures: 8` (`Actual and expected should have same size but actual size is: 3 while expected size is: 4` y `Expecting actual: ["event", "policy", "suppressed"] to contain exactly (and in same order): ["event", "policy", "reason", "suppressed"]`); revertida, `cmp` igual. Cierre: `./mvnw verify` completo, **Surefire 186 + 1 062, Failsafe 229, `BUILD SUCCESS`**.

**Medición del PR 11a tras la corrección** (`git diff --numstat main...HEAD -- . :!openspec`, igual con y sin `-M`): **732 líneas** (710 adiciones y 22 eliminaciones), frente a 517 antes y a 800 de tope. **Fuente de respaldo:** `wip/web-edge-rate-limiter-edge-full` no se modificó; su interceptor y sus pruebas llaman a la firma de un argumento y 3.2b adapta los puntos de llamada al construirse.

## Tarea 3.2b: PR 11b `edge-interceptor`

Rama `change/web-edge-foundations-edge-interceptor`, creada desde `main` en `5c590c5` tras fusionar el PR 11a. Código portado de la rama local `wip/web-edge-rate-limiter-edge-full` (`cf93861`, no se tocó): `RateLimited`, `RateLimiterRegistry` y `RateLimitPolicyCheck` sin cambios de fondo; el interceptor y su prueba se rehicieron sobre la firma de dos argumentos de `RateLimitMetrics` de `main`. Las cinco entradas de la lista de permitidos de `IdempotencyScopeExclusionInventoryTest` (`RateLimiter`, `RateLimitDecision` y sus tres variantes).

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO | `test-compile` con `RateLimitInterceptorTest` (13 métodos) y la lista de permitidos ampliada, sin producción | `COMPILATION ERROR`: `cannot find symbol` en `RateLimitInterceptor`, `RateLimited`, `RateLimiterRegistry` y `RateLimitPolicyCheck` (11 errores en la primera pasada) |
| VERDE | `-Dtest='RateLimitInterceptorTest,IdempotencyScopeExclusionInventoryTest'` | `Tests run: 13, Failures: 0` (`RateLimitInterceptorTest`) y `Tests run: 3, Failures: 0` (inventario); total `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0` |
| Cierre | `./mvnw verify` completo | **Surefire 186 + 1 075 (los 1 062 de la línea base y 13 nuevos), Failsafe 229, `BUILD SUCCESS`**. Instantánea OpenAPI sin cambios (`git status` limpio de ella) |

### Demostraciones deliberadas (cada una revertida de inmediato; `cmp` contra la copia original sin diferencias)

| Ruptura | Resultado observado |
|---|---|
| I-1: quitar la comprobación de `DispatcherType.ASYNC` | `Tests run: 13, Failures: 0, Errors: 1`: `anAsyncDispatchIsNotEvaluatedAgainBecauseTheRequestDispatchAlreadyWas` con `CapacityExceeded capacity exceeded` |
| Quitar la causa del camino de `UNKNOWN_POLICY` (`Verdict.refusal(null)`) | `Failures: 1`: `aPolicyTheRegistryDoesNotHoldIsARefusalAtRunTime`, `Expecting actual: [Signal[policy=no-such-policy, reason=null]] to contain exactly (and in same order): [Signal[policy=no-such-policy, reason=UNKNOWN_POLICY]]` |
| `tryAcquire` movido a `postHandle` | `Tests run: 13, Failures: 11`; mensaje `Expecting code to raise a throwable.` (el interceptor deja de lanzar en `preHandle`) |
| Quitar la llamada `metrics.capacityExhausted(...)` | `Tests run: 13, Failures: 7`: `Expecting actual: [] to contain exactly (and in same order): [Signal[policy=admin-login, reason=NO_ORIGIN]]` (y `NO_ADDRESS`, `UNKNOWN_POLICY`), más `Expected size: 1 but was: 0` en la prueba del adaptador que falla |
| S-1: la métrica fuera de `try`/`catch` | `Failures: 1`: `aFailingMetricsAdapterNeverTurnsARefusalIntoAServerError`, `[the answer stays 503, never the 500 of the exception of the adapter] Expecting actual throwable to be an instance of: ...CapacityExceededException` |

### Desviaciones (declaradas)

1. **S-3 (`HEAD`) pasa a 3.2c** con nota fechada en `tasks.md`: es un hecho del mapeo real de manejadores; la unidad solo lo supondría.
2. **La lista de permitidos suma cinco nombres de clase**, no cuatro (`RateLimitDecision` más sus tres variantes más `RateLimiter`); `RateLimitPolicy` e `InMemoryRateLimiter` quedan para 3.2c (y `InMemoryRateLimiter` ya no, por S-6).
3. **Registro de fallos de la métrica:** además del `ERROR` del limitador, un adaptador de métricas que falla escribe un `ERROR` propio (`rate limit metrics failed`, solo política y clase), con la misma cota de uno por segundo; así el defecto no es silencioso. El interceptor lleva un segundo constructor de paquete con una fuente monotónica para las pruebas.
4. El interceptor captura `RuntimeException` (no `Error`) como en la fuente de respaldo.

### Medición del PR 11b (`git diff --numstat main...HEAD -- . ':!openspec'`; igual con y sin `-M`)

**653 líneas** (651 adiciones y 2 eliminaciones) frente a 800 de tope: interceptor 157, comprobación de arranque 56, registro 30, anotación 33, prueba del interceptor 368, inventario 9. Pronóstico: unas 407 más las correcciones de la revisión.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `Tests run: 16, Failures: 0` (interceptor e inventario); cierre completo Surefire 186 + 1 075, Failsafe 229 |
| Arnés de ejecución | Interceptor con un limitador de respuesta fija, `RequestOrigin` ligado por `ScopedValue` y un `ListAppender` de Logback; la cadena real llega en 3.2c |
| Frontera de reversión | Se retiran las cuatro clases, su prueba y las cinco entradas de la lista de permitidos |

### Corrección del orquestador en 3.2b (2026-10-06)

El agente señaló en su informe que las dos líneas `ERROR` del interceptor (fallo del limitador y fallo del adaptador
de métricas) compartían un único tope de una por segundo. Cuando el limitador falla, el interceptor registra el fallo
y después llama a la métrica; si el adaptador también falla en ese mismo segundo, su línea quedaba suprimida, y son
dos defectos distintos que el operador necesita ver. Ahora cada mensaje tiene su propio tope
(`lastLimiterFailureAt` y `lastMetricsFailureAt`).

- Prueba nueva: `aFailingLimiterAndAFailingAdapterInTheSameSecondEachLeaveTheirOwnLine` exige las dos líneas en
  orden. **Ruptura:** con el tope compartido falla en la línea 291. Revertida con `cmp`.
- `./mvnw verify` completo: Surefire 186 + 1076, Failsafe 229, `BUILD SUCCESS`.

### Revisión independiente de 3.2b (2026-10-06)

Riesgo evaluado: alto. Veredicto: aprobable, sin bloqueantes. La revisión confirmó que es seguro dejar pasar el
despacho `ASYNC`, que cada camino lleva su causa, que solo se registran la clase de la excepción y la política, y que
las cotas separadas con `AtomicReference<Long>` son correctas. El orquestador corrigió sus dos hallazgos importantes:

- **M-1:** un adaptador de métricas que lanza un `LinkageError` (por ejemplo `NoClassDefFoundError` cuando llegue
  Prometheus) daba `500`. Ahora `signal` y `decide` capturan `RuntimeException | LinkageError`, nunca `Throwable`, para
  no tragar un `OutOfMemoryError`. Prueba `aMetricsAdapterThatCannotLinkStillAnswersTheRefusal`. **Ruptura:** sin
  `LinkageError` falla en la línea 320.
- **M-2:** un limitador que devolvía `null` producía un `NullPointerException` como `500`, sin señal. Ahora
  `requireNonNull` dentro del `try` lo convierte en `LIMITER_FAILURE`. Prueba
  `aLimiterThatAnswersNoDecisionIsARefusalWithItsCauseAndNeverAServerError`. **Ruptura:** sin la comprobación falla en la
  línea 301. Ambas rupturas se revirtieron con `cmp`.
- **S-a:** el Javadoc de `RateLimited` prohíbe además que un filtro haga `startAsync` y despache a un manejador limitado.
- **Para 3.2c (S-b):** una prueba de arranque fallido con una política inexistente por la cadena real, que verifique que
  `RateLimitPolicyCheck` detiene el arranque antes de que el servidor acepte conexiones.
- **Aceptado (S-c):** un fallo del propio `LOG.atError()` escaparía como `500`. Es improbable.

## 3.2c `edge-throttling-wiring` (2026-10-06) - construido y verificado, SIN COMMIT por tamaño

Estado: árbol completo y `./mvnw verify` en verde; **no se hicieron commits** porque la medición supera el tope de 800 líneas.

### Medición (`git diff --numstat main -- . ':!openspec'`, con `git add -N` para contar los archivos nuevos)

**839 líneas** (818 adiciones, 21 eliminaciones); 777 sin `docs/03-seguridad.md` (62). Pronóstico de la tarea: unas 639.
Por archivo: `RateLimitEdgeTest` 289, `RateLimitPropertiesTest` 102, `RateLimitProperties` 86, `ThrottlingConfiguration` 72,
`LimitedController` 40, `RateLimiterConfiguration` 27, `ProcessBeanIsolationTest` 30, `UnknownPolicyController` 21,
`Calls` 20, `InMemoryRateLimiterTest` 19, `HarnessProcess` 17, `RateLimitPolicy` 21, resto menor.

### Tabla de ciclo TDD (modo estricto)

| Unidad | ROJO observado | VERDE |
|---|---|---|
| Línea `com.confia.shared.web.ratelimit` de `ProcessBeanPolicy` (escrita primero) | `ProcessBeanIsolationTest`: `Tests run: 7, Failures: 1`; `non-vacuous: com.confia.shared.web.ratelimit must contribute a bean to the admin context` | `Tests run: 7, Failures: 0` tras el `@Import` |
| Pruebas nuevas (`RateLimitEdgeTest`, `RateLimitPropertiesTest`, arnés, aislamiento) | error de compilación: `RateLimitProperties`, `ThrottlingConfiguration` y `RateLimiterConfiguration` inexistentes | `RateLimitEdgeTest` 11, `ProcessBeanIsolationTest` 7, inventario 3 en verde |
| I-3 (`maxEntries` <= 1 000 000) | `InMemoryRateLimiterTest` 2 fallos y `RateLimitPropertiesTest` 1 fallo (`Expecting code to raise a throwable`) | `RateLimitPolicy.MAX_ENTRIES`; verde |

### Demostraciones deliberadas (cada una revertida; `cmp` sin diferencias)

| Ruptura | Resultado observado |
|---|---|
| `tryAcquire` en `postHandle` | `RateLimitEdgeTest` `Failures: 7`: `aRejectedRequestReachesNeitherTheControllerNorTheUseCase:114` (`expected: 429 but was: 200`; el controlador ya había respondido), HEAD, 503 de tabla llena, evento, etc. |
| Quitar `signal(policy, verdict.reason())` | `RateLimitEdgeTest` `Failures: 1`: `aFullTableLeavesOneFixedWarnWithThePolicyAndNothingOfTheClient:162` (`Expected size: 1 but was: 0`) |
| I-3: quitar la comprobación de `maxEntries` | `InMemoryRateLimiterTest.aTableAboveOneMillionEntriesIsRejectedNamingTheField` (2) y `RateLimitPropertiesTest.aCountAboveTheBound...` (1) |
| S-6: `ThrottlingConfiguration` nombra `InMemoryRateLimiter` | `IdempotencyScopeExclusionInventoryTest`: `ThrottlingConfiguration resides in a web package and depends on ...InMemoryRateLimiter, which is not one of [...]` |
| `@Import` sin la línea de `ProcessBeanPolicy` | `ProcessBeanIsolationTest`: `bean 'rateLimitInterceptor' from package 'com.confia.shared.web.ratelimit' - not in the allow-list` (y registro, comprobación, propiedades) |

### Decisiones y desviaciones

1. **S-6:** `RateLimiterConfiguration` (`shared.security`, `@Import` desde `AdminApplication`) construye el limitador con el nombre de su política como nombre de bean (`admin-login`) y solo expone `RateLimiter`; `ThrottlingConfiguration` recibe `Map<String, RateLimiter>` y publica un bean `RateLimitPolicy` desde las propiedades. La lista de permitidos pasa a **nueve entradas por nombre completo** (siete tipos más dos variantes anidadas de `RateLimitDecision` contadas aparte; antes eran ocho) (suma `RateLimitPolicy`; `InMemoryRateLimiter` no entra).
2. **S-b:** `theStartThatNamesAnUnknownPolicyFailsBeforeTheWebServerAcceptsAConnection` (con no vacuidad) usa `HarnessProcess.startObserved` y comprueba que nunca se publica `WebServerInitializedEvent`.
3. **S-3:** `aHeadRequestToTheLimitedGetHandlerIsLimitedExactlyLikeAGet` (el arnés permite `HEAD` en `/test/limited`).
4. Evento de la señal con `reason=table_full` (campos exactos en orden).
5. Notas fechadas de `docs/03` §4.4 y §10 (limitador interino, tres limitaciones, capa 2, I-2 con tres mitigaciones, campo `reason` y lectura del `503` del marco).

### Cierre de la verificación

`./mvnw verify` completo: Surefire 186 (kernel) + 1 111 (app), Failsafe 229, `BUILD SUCCESS`.

**Decisión del propietario (2026-10-06):** se acepta la excepción de tamaño para 3.2c (839 líneas; 777 sin `docs/03`) en un solo PR 11c. Commits de código, de documentación de seguridad y de SDD por separado.

### Revisión independiente de 3.2c (2026-10-06)

Riesgo evaluado: alto. Veredicto: se puede fusionar, sin bloqueantes. La revisión confirmó estos puntos:

- falla cerrado por la cadena real;
- `admin-login` no está aplicada a ninguna ruta de producción;
- solo el proceso administrativo carga el limitador;
- se cumple S-6 (la capa web nunca nombra `InMemoryRateLimiter`);
- las pruebas de S-b y de `HEAD` no son vacuas;
- no se registran datos del cliente.

Antes de fusionar, el orquestador corrigió:

- **I-1 (documental):** la nota de `docs/03` §10 decía que, si no hay un evento en ese instante, el `503` no vino del limitador. Como el evento está acotado a uno por segundo por política y causa, esa regla confundiría una autodenegación con un `503` del marco. La nota se reescribió como una lectura por tendencia que incluye `suppressed`. `docs/07` §5.4 ya decía que se leyera `reason` junto al registro de errores, así que no cambia.
- **S-2:** una propiedad mal escrita (`request-limt`) dejaba en silencio el valor por omisión. `RateLimitProperties` usa ahora `ignoreUnknownFields = false`. Lo cubren las pruebas `aMisspelledPropertyStopsTheStartInsteadOfLeavingTheDefault` y `aWellSpelledPropertyStillStarts`, que pasan por el mecanismo real de enlace (`ApplicationContextRunner`). **Rojo observado** sin la corrección: `RateLimitPropertiesTest` 21 pruebas, 1 fallo, en la línea 103, porque el contexto arrancaba. **Verde:** 21/21.
- **S-4:** el conteo de la lista de permitidos se corrigió arriba a nueve entradas.

Para seguimiento (no bloquean):

- **S-1:** acotar el producto `maxEntries × (requestLimit + failureThreshold)`. Con los valores por omisión son unos 8 MB; con los máximos permitidos, unos 160 GB. Queda para el cambio de observabilidad y endurecimiento o para una decisión del propietario.
- **S-3:** comprobar al arrancar que los nombres de bean del registro forman un conjunto cerrado de políticas conocidas.
- **S-5:** la validación duplicada entre `RateLimitProperties` y `RateLimitPolicy`. Hoy no se solapa.

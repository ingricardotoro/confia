# Progreso de aplicación: fundamentos del borde web del proceso administrativo

- **Cambio:** `web-edge-foundations` (F0, cambio 7, parte 4a)
- **Modo:** TDD estricto (`./mvnw verify` en `apps/api`, JDK 25, Docker en ejecución)
- **Estrategia de entrega:** `auto-chain` con `stacked-to-main`, tope de 800 líneas efectivas por PR
- **Última actualización:** 2026-10-04 (tarea 2.1b; 2.1a y 2.1b hechas)

## Estado de las tareas

| Tarea | PR | Estado | Commits |
|---|---|---|---|
| 1.1 | PR 1 `platform-wiring` | Hecha | `d9e6adf`, `9fcb65d` |
| 1.2 | PR 2 `identity-beans` | Hecha | `aaa1161`, `4d99f98` |
| 2.1a | PR 3 `problem-details-core` | Hecha | `2554745` y el commit `docs(sdd)` de esta rama |
| 2.1b | PR 4 `security-chains` | Hecha | `f9bc884`, `6ba1744` y el commit `docs(sdd)` de esta rama |
| 2.1c | PR 5 `portal-and-worker-chain` | Pendiente | |
| 2.2 a 6.1 | PR 6 a 13 y cierre | Pendientes (numeración nueva) | |

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

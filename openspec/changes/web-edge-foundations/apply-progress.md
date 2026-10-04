# Progreso de aplicación: fundamentos del borde web del proceso administrativo

- **Cambio:** `web-edge-foundations` (F0, cambio 7, parte 4a)
- **Modo:** TDD estricto (`./mvnw verify` en `apps/api`, JDK 25, Docker en ejecución)
- **Estrategia de entrega:** `auto-chain` con `stacked-to-main`, tope de 800 líneas efectivas por PR
- **Última actualización:** 2026-10-04 (tarea 1.2)

## Estado de las tareas

| Tarea | PR | Estado | Commits |
|---|---|---|---|
| 1.1 | PR 1 `platform-wiring` | Hecha | `d9e6adf`, `9fcb65d` |
| 1.2 | PR 2 `identity-beans` | Hecha | `aaa1161`, `4d99f98` |
| 2.1 a 6.1 | PR 3 a 11 y cierre | Pendientes | |

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

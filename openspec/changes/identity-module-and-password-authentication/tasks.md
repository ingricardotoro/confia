# Tareas: módulo de identidad y autenticación con contraseña

Cambio 7 de F0, **primera de tres partes**. Implementa `proposal.md` y `design.md`, ya aprobados, y
los dos deltas ya escritos: `specs/identity/spec.md` (12 requisitos, **23** escenarios) y
`specs/build-integrity/spec.md` (3 requisitos, 8 escenarios). Los ajustes 1 y 2 de `design.md` §15
**ya están aplicados** a esos deltas (dos tablas en `build-integrity`; el requisito nuevo del
retardo calculado/materializado en `identity`) y no se repiten aquí. Los ajustes 3 (nota en §4.4), 4
(nota en §6.1) y 5 (nota en §4.1, **autorizado por el propietario el 2026-09-24**) sí son trabajo de
esta lista, en la tarea 4.4.

## Discrepancia encontrada y reportada: `design.md` §7.1 traza 20 escenarios, el delta ya tiene 23

`design.md` §7.1 fecha su tabla de trazabilidad de `identity` con el encabezado «20 escenarios», y
enumerando sus filas una a una da exactamente 20. Contando los encabezados `#### Escenario:` reales
de `specs/identity/spec.md` da **23**. Los tres que la tabla de diseño no traza:

- «El estado del retroceso existe para un identificador sin cuenta» (requisito «Estado del
  retroceso persistido...», tercer escenario, entre los dos que la tabla sí trae).
- «La duración exigible se devuelve y la transacción ya confirmó» y «Ninguna clase del módulo de
  identidad espera» (requisito nuevo del ajuste 2, ya aplicado al delta, pero nunca incorporado a la
  tabla de §7.1).

Esta lista traza los 23, no los 20 de la tabla envejecida: el primero queda cubierto en la tarea 4.1
(mismo camino de código que el resto del retroceso sobre identificador inexistente); los dos
últimos, en las tareas 3.2 y 2.3 respectivamente (el segundo es exactamente la regla de ArchUnit de
la decisión 1).

> **Ya corregido (2026-09-24, commit `eae1f2e`).** El orquestador verificó la discrepancia contando
> los encabezados de los archivos de delta y actualizó `design.md` §7.1 en el mismo commit que
> incorporó esta lista: la tabla traza ahora **31 escenarios**, 23 de `identity` y 8 de
> `build-integrity`, con las tres filas que faltaban marcadas y con el recuento de `build-integrity`
> corregido, que ya iba uno corto de sus propias filas. No queda trabajo pendiente para quien
> archive.

## Nota sobre la ubicación de `LoginIdentifier`, no fijada por el diseño

`design.md` §11 solo dice «las primeras clases de `com.confia.identity.domain`» para el paso 2 (C1),
sin nombrarlas, y agrupa la redacción y las guardas técnicas de los objetos de valor en el paso 10
(C2). La sonda **S8** —que depende de `LoginIdentifier` para normalizar antes de comparar contra el
`CHECK` de `V5`— se declara bloqueante de **C1 y C3** en `design.md` §10. Para que S8 pueda
ejecutarse en C1 sin violar esa dependencia, esta lista crea `LoginIdentifier` en la tarea 1.2, junto
al tipo sellado, y difiere `PlainPassword`, `StoredPasswordHash`, `IdentifierFingerprint`,
`BackoffState` y `BackoffPolicy` a C2 (tarea 2.2), donde `design.md` §12 ya presupuesta «objetos de
valor con redacción y sus pruebas» y «`BackoffPolicy`, `BackoffState`...» como partidas propias. Es
una elección de esta lista, no una instrucción explícita del diseño, y se deja escrita para que quien
aplique no la redescubra distinta.

## Estrategia de entrega: RESUELTA POR EL ORQUESTADOR (2026-09-24)

`design.md` §12 dejó esto abierto con sus propias palabras: «el orquestador debe actualizar la
estrategia a `auto-chain` antes de la fase de tareas, o el propietario debe aceptar explícitamente
una excepción de tamaño que este diseño no recomienda». Esta lista lo registró como pendiente y la
fase de tareas hizo bien en no decidirlo por su cuenta. **Queda resuelto así, y no es una decisión
que corresponda al propietario:**

- **Estrategia: `auto-chain`. Cadena: `stacked-to-main`.** Cuatro pull requests encadenados, cada uno
  con su rama, revisados y fusionados a `main` en orden.
- **El presupuesto es de 800 líneas de cambio efectivo por pull request**, de
  `docs/15-flujo-de-trabajo-git.md` §3 línea 143 y de `CLAUDE.md`. La cifra de **400** que aparece en
  el preámbulo de la sesión es una heurística genérica de la herramienta del orquestador y **no
  gobierna este repositorio**. No es una contradicción que el propietario deba arbitrar entre dos
  documentos suyos: es una regla del proyecto frente a un valor por omisión ajeno, y gana la del
  proyecto.
- **No hay excepción de tamaño**, porque no hace falta ninguna: ningún pull request de esta cadena
  pretende llevar las 2 445 a 3 985 líneas del total. Ese número es el del cambio completo, no el de
  un corte.
- **Precedente, no improvisación.** Es exactamente lo que hicieron los dos cambios anteriores:
  `audit-log-and-transaction-runner` se entregó en varios pull requests encadenados, e
  `idempotency-key-infrastructure` cerró en seis, todos fusionados a `main` por separado. Los cortes
  anteriores midieron 711, 795, 549 y 575 líneas contra ese mismo presupuesto de 800.

**Regla operativa al cerrar cada corte**, heredada del cambio 5: se mide el diff real y, si supera
800 líneas, el corte se parte antes de abrir el pull request. El cambio 5 lo aprendió por las malas
cuando su corte A3 midió 813 líneas y hubo que reordenar commits con `cherry-pick`.

`Decision needed before apply: No`.

## Review Workload Forecast

| Campo | Valor |
|---|---|
| Presupuesto de revisión de esta sesión (guardia literal de la fase) | 400 líneas |
| Presupuesto de revisión del proyecto (`docs/15-flujo-de-trabajo-git.md` §3, `CLAUDE.md`) | **800 líneas** de cambio efectivo por pull request |
| Líneas de autor estimadas (código, pruebas, POM, SQL, documentación; sin `openspec/`, sin código generado de jOOQ) | **2 445 a 3 985**, según `design.md` §12 «Pronóstico de tamaño por corte» |
| Riesgo frente al presupuesto de 400 (guardia literal de la fase) | **High** |
| Riesgo frente al presupuesto de 800 del proyecto | **High** — C1 lo supera en su rango alto, C2 lo supera en su rango alto, y C3 lo supera **incluso en su rango medio** (`design.md` §12) |
| Pull requests encadenados recomendados | **Sí** — el propio diseño pronostica cuatro pull requests encadenados y anticipa que C3 puede partirse de nuevo si el diff real lo exige |
| División sugerida | PR C1 → PR C2 → PR C3a → PR C3b, cada uno base del siguiente |
| Estrategia de entrega | `auto-chain` — **resuelta por el orquestador el 2026-09-24** (ver la sección anterior) |
| Estrategia de cadena | `stacked-to-main` — cada corte en su rama, revisado y fusionado a `main` en orden, como los cinco cambios anteriores |
| Presupuesto por pull request | **800 líneas** de cambio efectivo (`docs/15-flujo-de-trabajo-git.md` §3 línea 143, `CLAUDE.md`) |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
Budget risk: bajo — ningún corte pretende llevar el total; se mide el diff real al cerrar cada uno

### Nota sobre el límite de quince tareas

Esta lista tiene **12 tareas en total** (3 en PR C1, 3 en PR C2, 2 en PR C3a, 4 en PR C3b), dentro
del rango de 9 a 13 que `design.md` §12 pronostica y por debajo del límite de quince de
`openspec/changes/README.md`. **Es precisamente la razón de ser de este cambio**: la propuesta
registra que la mitad completa de identidad, sin dividir en dos cambios SDD secuenciales, pronosticó
14 a 16 tareas contra ese mismo límite (`proposal.md`, líneas 12-13). No se infla esta lista con
tareas adicionales para «llenar presupuesto»: cada tarea agrupa varios pasos afines de `design.md`
§11 porque el límite de quince es sobre el total del cambio, no sobre cada pull request — a
diferencia del criterio que los cambios 2, 4, 5 y 6 aplicaron («el límite rige por pull request»),
que aquí no hace falta invocar.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| C1 | Módulo `identity`, interfaces nombradas de `shared`, ADR-0022, migración `V5` con sus dos tablas, política de fila y privilegios | PR C1a (`change/identity-module-and-password-authentication-module`) y PR C1b (`-schema`), base `main` en cadena | `./mvnw -B -pl apps/api/app -am test -Dtest=NoCrossModuleDomainImportsTest,SpringModulithVerificationTest,RolePrivilegeMatrixIT,IdentityRowSecurityIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir el pull request completo; sin ningún consumidor de aplicación todavía. **No se revierte por separado de PR C2** una vez que C2 exista: `design.md` §1.1 ata mecánicamente el módulo a la migración |
| C2 | Contraseña, señuelo y regla del retroceso: `BackoffPolicy`, códec `$argon2id$`, adaptador Bouncy Castle, objetos de valor con redacción, guarda de ArchUnit de ninguna espera | PR C2 (`change/identity-module-and-password-authentication-backoff-and-password`, base PR C1) | `./mvnw -B -pl apps/api/app -am test -Dtest=BackoffPolicyTest,Argon2PhcCodecTest,Argon2ProfileTest,NoBlockingWaitInIdentityTest -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C2 sin fusionar deja PR C1 completo por sí solo (esquema y puertas de módulo, sin lógica de contraseña) |
| C3a | Caso de uso completo, puerto y adaptador de escritura de auditoría, sobre dobles de prueba | PR C3a (`change/identity-module-and-password-authentication-use-case`, base PR C2) | `./mvnw -B -pl apps/api/app -am test -Dtest=JooqAuditLogWriterIT,AuthenticateWithPasswordTest,AuthenticationResultTest -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C3a sin fusionar deja PR C2 completo por sí solo (mecanismo de contraseña y retroceso probado en dominio, sin caso de uso ni auditoría) |
| C3b | Adaptadores jOOQ reales, atomicidad, concurrencia, institución del proceso, inventarios de exclusión, medición de tiempo y notas editoriales | PR C3b (`change/identity-module-and-password-authentication-adapters`, base PR C3a) | `./mvnw -B -pl apps/api/app -am test -Dtest=AuthenticateWithPasswordIT,LoginBackoffAtomicityIT,LoginBackoffConcurrencyIT,LoginInstitutionIT,IdentitySecretRedactionIT,IdentityScopeExclusionInventoryTest,LoginTimingReportIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C3b sin fusionar deja PR C3a completo por sí solo (caso de uso probado con dobles, sin demostración real contra PostgreSQL) |

Ejecutor de todas las tareas: `./mvnw -B verify` en `apps/api`, con `JAVA_HOME` apuntando a JDK 25 y
`MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` (`design.md` §13; **esta variable nunca se
compromete en ningún archivo del repositorio**). Docker debe estar activo para toda tarea que ejecute
una clase `*IT.java` real contra PostgreSQL o que dispare `generate-sources`; las tareas que solo
tocan ArchUnit, Spring Modulith, jqwik sin contenedor o inventario estático de texto **no** requieren
Docker, y cada tarea de abajo lo marca explícitamente. La integración continua en `ubuntu-latest` es
la fuente de verdad ante cualquier divergencia observada en Windows/WSL2. Nunca se baja un umbral ni
se omite una prueba para pasar en local.

TDD estricto (`openspec/config.yaml`, `strict_tdd: true`): toda clase de prueba observa **ROJO**
antes de **VERDE**, y esa evidencia se registra en el repositorio — la línea correspondiente de esta
lista y `openspec/changes/identity-module-and-password-authentication/apply-progress.md` (crear el
archivo si no existe) —, nunca solo en Engram. **Nunca se inventa evidencia de ROJO.** Una tarea que
no puede observar rojo (una migración pura, una nota documental) lo dice explícitamente en vez de
fingirlo — ver las tareas 1.1 y 4.4.

Todos los mensajes de commit son **convencionales, en inglés, sin ninguna atribución de herramienta
de IA** (`CLAUDE.md`). Cada tarea que produce comportamiento cierra con al menos un commit de unidad
de trabajo en la rama del corte.

---

## PR C1 — corte C1: módulo, esquema y puertas

> **Partido en dos el 2026-09-24, tras medir el diff real: 906 líneas contra el presupuesto de 800.**
> La regla operativa de este documento pedía exactamente esto —medir y partir **antes** de abrir el
> pull request—, y por una vez el corte cayó en una frontera de commit que ya existía, así que no
> hubo que reordenar nada con `cherry-pick` como le tocó al cambio 5.
>
> | Pull request | Rama | Commits | Contenido | Líneas |
> |---|---|---|---|---|
> | **C1a** | `change/identity-module-and-password-authentication-module` | `1389a0b`, `a4fdc5c` | módulo `identity`, tipo sellado, `LoginIdentifier`, guarda de no vacuidad de W2, interfaces nombradas de `shared`, ADR-0022, POM | **522** |
> | **C1b** | `change/identity-module-and-password-authentication-schema` | `459016d` | migración `V5` con sus dos tablas, `IdentityRowSecurityIT`, `RolePrivilegeMatrixIT` extendida | **384** |
>
> La separación es real y no cosmética: C1a no toca el esquema y C1b no toca el módulo. Cada uno se
> verifica por separado con `./mvnw -B verify` antes de empujarse.
>
> **Las ramas se renombraron al identificador del cambio.** `change/staff-authentication-mfa-sessions`
> quedó obsoleta cuando el cambio 7 se dividió en tres, y `CLAUDE.md` pide que la rama lleve el
> identificador del cambio SDD. Nunca se empujó al remoto, así que el renombrado no costó nada. Las
> ramas de los cortes siguientes pasan a ser
> `change/identity-module-and-password-authentication-backoff-and-password`, `-use-case` y
> `-adapters`.
>
> `apply-progress.md` documenta las tareas 1.2 y 1.3 juntas y viaja con **C1b**, porque su evidencia
> de S8 y del esquema pertenece a ese corte; C1a lleva la de las sondas S1 y S1b en `961ab61`.

Rama `change/identity-module-and-password-authentication-module` (C1a) y
`change/identity-module-and-password-authentication-schema` (C1b), ambas con base `main` en cadena. `design.md` §1.1: crea
las primeras clases de producción bajo `com.confia.identity.*` antes de que `V5` pueda nombrar
`identity_*` sin romper la puerta de prefijo de módulo — la misma dependencia mecánica que ató B1
antes de B2a en el cambio 5B.

- [x] 1.1 **Sondas S1 y S1b (bloqueantes de C1, `design.md` §10).** No requiere Docker (ArchUnit y
  Spring Modulith no tocan PostgreSQL). **S1**: crear temporalmente una clase de producción mínima en
  `com.confia.identity.application` que importe `com.confia.shared.security.TransactionRunner`,
  ejecutar `./mvnw -B -pl app test -Dtest=SpringModulithVerificationTest` desde `apps/api` y observar
  que **falla**; revertir el archivo temporal. **S1b**: `./mvnw -B -pl app dependency:tree
  -Dincludes=org.springframework.modulith` y, sobre el jar resuelto en `~/.m2`, `jar tf … | findstr
  NamedInterface`, para confirmar el artefacto exacto donde vive `@NamedInterface` en Spring Modulith
  2.1.1. Registrar ambos resultados exactos en `apply-progress.md`. Sin evidencia de ROJO propia: son
  sondas de comportamiento de herramienta, no pruebas del delta — se dice así en vez de fingir un
  rojo. Si S1 no fallara como se espera, o si ningún artefacto expusiera `@NamedInterface`, **se
  reporta** y se sigue el respaldo de `design.md` decisión 12 (`@ApplicationModule(type = OPEN)`),
  nunca se relaja `SpringModulithVerificationTest`. — `design.md` §10 (S1, S1b) y §11, paso 1

- [x] 1.2 **ROJO/VERDE — módulo `identity`, guarda de no vacuidad de W2, y las interfaces nombradas de
  `shared`.** No requiere Docker. ROJO: extender
  `.../test/java/com/confia/architecture/NoCrossModuleDomainImportsTest.java` con la aserción de no
  vacuidad (`productionClasses()` contiene clases de al menos dos módulos de dominio distintos) y
  renombrar `productionCodeHasNoCrossModuleDomainImportYet` a `everyModuleUsesOnlyItsOwnDomain`
  (`design.md` decisión 13); falla porque solo existe `organization.domain`. VERDE: crear
  `.../identity/package-info.java` (módulo de Spring Modulith, precedente de
  `organization/package-info.java`),
  `.../identity/domain/AuthenticationResult.java` con `Authenticated`, `Rejected`, `RejectionReason`
  (tipo sellado exacto de `design.md` decisión 10 y del delta), `.../identity/domain/StaffAccount.java`
  y `StaffAccountId.java`, y `.../identity/domain/LoginIdentifier.java` (normalización NFKC,
  minúsculas con `Locale.ROOT`, validación de longitud — ver la nota sobre su ubicación, arriba).
  Añadir `@NamedInterface` a `.../shared/security/package-info.java` y
  `.../shared/audit/package-info.java`, elevar la decisión a
  `docs/adr/ADR-0022-interfaz-nombrada-del-modulo-shared.md`, y declarar las anotaciones de Spring
  Modulith en alcance de **compilación** en `apps/api/app/pom.xml` — concretamente
  **`spring-modulith-api`**, y **no** `spring-modulith-core`; ver la corrección de S1b en
  `apply-progress.md`, porque `spring-modulith-core` arrastra ArchUnit, que es solo de pruebas, y
  promoverlo la pondría en el camino de clases de producción. `spring-modulith-core` se queda en
  alcance `test`. Ejecutar `NoCrossModuleDomainImportsTest` y `SpringModulithVerificationTest`:
  verde. Commit convencional (`feat`) sobre `com.confia.identity` y (`build`) sobre el POM y el ADR. —
  Especificación `build-integrity`, requisito «Frontera de dominio entre módulos de negocio»
  (escenarios «Importación cruzada de dominio», sin cambio, y «Cada módulo usa solo su propio
  dominio»); `design.md`, decisiones 10, 12 y 13

- [x] 1.3 **ROJO/VERDE — esquema `V5`, sonda S8, y las puertas de esquema extendidas.** Requiere
  Docker. ROJO: extender
  `.../test/java/com/confia/schema/RolePrivilegeMatrixIT.java` con las filas de
  `identity_staff_account` e `identity_login_backoff` para los cinco roles, y crear
  `.../test/java/com/confia/schema/IdentityRowSecurityIT.java` (aislamiento entre dos instituciones
  sobre `identity_staff_account`; contexto ausente devuelve cero filas, no error de permiso; las
  cuatro operaciones de `confia_portal_app` rechazadas por falta de privilegio). Fallan porque `V5`
  no existe. **Sonda S8** (bloqueante, `design.md` §10): antes del `VERDE`, escribir una prueba
  unitaria pura contra `LoginIdentifier` con una muestra que incluya `İ` (U+0130), `ß`, griego y CJK,
  y confirmar que ninguna forma normalizada violaría `CHECK (email !~ '[A-Z]')`; registrar el
  resultado en `apply-progress.md`. Si alguna lo violara, acotar el conjunto aceptado en
  `LoginIdentifier` antes de escribir la migración, nunca relajar la restricción (`design.md` decisión
  4, punto 1). VERDE: crear
  `apps/api/app/src/main/resources/db/migration/V5__create_identity_staff_account.sql` con el DDL
  exacto de `design.md` decisión 4 (las dos tablas, seguridad de fila habilitada y forzada,
  `REVOKE ALL ... FROM PUBLIC` antes de los `GRANT`, sin `DELETE` para ningún rol de aplicación).
  Ejecutar las pruebas de la mitad ROJO: verde. Comprobar que `MultiTenantSchemaIT` pasa **sin
  modificarse** (si no pasara, se reporta la discrepancia, no se relaja la puerta). Medir el tiempo de
  la suite `*IT.java` y registrarlo en `apply-progress.md`. Medir el diff real de PR C1 con
  `git diff --numstat main...HEAD -- . ':(exclude)openspec'
  ':(exclude)docs/adr' ':(exclude)**/generated/**'`; si supera 800 líneas, **detener la aplicación y
  reportar los puntos de corte candidatos medidos**, verificando cada mitad con `./mvnw -B verify`
  antes de proponerla. Verificación final en checkout limpio con `./mvnw -B verify`; empujar la rama y
  confirmar que la integración continua termina en verde. Commit convencional (`feat`) sobre la
  migración y las pruebas de esquema. — Especificación `build-integrity`, requisitos «Tablas nuevas de
  identidad...» (los tres escenarios) y «Permisos de acceso a las tablas nuevas de identidad por rol
  de base de datos» (los tres escenarios); `design.md` §10 (sonda S8, mitad C1) y §11, pasos 4-6

---

## PR C2 — corte C2: contraseña, señuelo y regla del retroceso

Rama `change/identity-module-and-password-authentication-backoff-and-password`, base PR C1.

- [ ] 2.1 **Sondas S2, S6 y S7 (bloqueantes de C2, `design.md` §10).** No requiere Docker (lectura de
  jars y `enforcer:enforce`, sin PostgreSQL). **S2**: descargar `spring-security-crypto` y
  `bcprov-jdk18on` al repositorio local y ejecutar `javap -classpath <jar>
  org.springframework.security.crypto.argon2.Argon2PasswordEncoder` y `javap -classpath <jar>
  org.bouncycastle.crypto.params.Argon2Parameters$Builder`, confirmando que solo el segundo expone
  `withSecret(...)`. **S6**: tras declarar `bcprov-jdk18on` en `apps/api/app/pom.xml` (alcance de
  compilación, versión en `dependencyManagement` del POM padre), ejecutar
  `./mvnw -B -pl app enforcer:enforce` y confirmar `dependencyConvergence` en verde junto a las
  anotaciones de Modulith ya declaradas en C1. **S7** se ejecuta como la tarea 2.2 misma (el vector de
  RFC 9106 §5.3 **es** la prueba `Argon2PhcCodecTest`, no una sonda separada de esta tarea). Registrar
  los resultados de S2 y S6 en `apply-progress.md`. Si S2 desmintiera lo anotado y
  `Argon2PasswordEncoder` sí expusiera un secreto, **se reporta** y se reevalúa la decisión 6 antes de
  continuar con la tarea 2.2 — no se implementa el respaldo en silencio. — `design.md` §10 (S2, S6) y
  §11, paso 7

- [x] 2.2 **ROJO/VERDE — `BackoffPolicy`, el códec `$argon2id$` (sonda S7), y los objetos de valor
  restantes.** No requiere Docker (JUnit, AssertJ y jqwik puros, sin contenedor). ROJO: crear
  `.../test/java/com/confia/identity/domain/BackoffPolicyTest.java` con los cuatro escenarios del
  delta (retardo desde el tercer fallo, progresión con tope de 900 s, expiración del contador a los
  30 minutos con dos `Clock.fixed`, limpieza tras éxito) y sus propiedades jqwik (monotonía, tope,
  cero por debajo del umbral); `.../test/java/com/confia/identity/infrastructure/Argon2PhcCodecTest.java`
  con el **vector de prueba de Argon2id de RFC 9106 §5.3** (sonda S7), comparación byte a byte de la
  etiqueta producida, incluido el valor de `secret`; y
  `.../test/java/com/confia/identity/infrastructure/Argon2ProfileTest.java` sobre los valores de §4.1
  (`memoryCost=19456`, `timeCost=3`, `parallelism=1`, salida de 32 bytes, sal de 16 bytes, pimienta de
  32 bytes) con su Javadoc que los declara **piso, no valor calibrado**. Todas fallan porque
  `BackoffPolicy`, `BackoffState`, el códec y el adaptador no existen. VERDE: crear
  `.../identity/domain/BackoffState.java` y `BackoffPolicy.java` (`design.md` §6.3, sin reloj propio);
  `.../identity/domain/PlainPassword.java` y `StoredPasswordHash.java` (`toString()` redactado,
  `PlainPassword` rechaza cadena vacía y acota a 1024 caracteres, con NFKC aplicado antes de hashear);
  `.../identity/domain/IdentifierFingerprint.java` (huella hexadecimal de 64 caracteres);
  > **Corrección del 2026-09-25, reportada por quien aplicó la tarea.** Esta línea decía «32 bytes
  > exactos desde `confia.identity.login-institution-id`... variable de entorno propia». Es una cita
  > cruzada equivocada: esa clave es la de `LoginInstitutionProvider` (`design.md` §11, línea 723) y
  > lleva **un identificador de institución**, no un secreto de 32 bytes. La tarea 4.3 la usa bien,
  > para `ConfiguredLoginInstitutionProvider`. Gobierna la decisión 6 del diseño: en este corte la
  > pimienta **llega por constructor**, y `Argon2Pepper` solo valida su tamaño; el cableado real al
  > gestor de secretos es del cambio 11.

  `.../identity/infrastructure/Argon2PhcCodec.java`, `Argon2Pepper.java` (`toString()` redactado, **32
  bytes exactos, recibidos por constructor**, falla al construirse si mide otro tamaño) y
  `BouncyCastleArgon2PasswordHasher.java` sobre
  `org.bouncycastle:bcprov-jdk18on`; `.../identity/infrastructure/HmacLoginIdentifierFingerprinter.java`
  con la subllave `HMAC-SHA-256(pimienta, "confia.identity.login-identifier.v1")`. Ejecutar las tres
  pruebas de la mitad ROJO: verde. Commit convencional (`feat`) sobre `BackoffPolicy` y otro sobre
  Argon2id/pimienta/huella. — Especificación `identity`, requisitos «Retardo por intentos fallidos con
  retroceso exponencial» (los cuatro primeros escenarios: tercer fallo, tope de 900 s, expiración a
  los 30 minutos, limpieza tras éxito), «Ausencia de calibración de Argon2id...» (su escenario) y
  «Ausencia de verificación contra contraseñas comprometidas...» (escenario «El hash almacenado no se
  recalcula», fundamento); `design.md` §11, pasos 8-10

- [x] 2.3 **ROJO/VERDE — el hash señuelo, y la regla de ArchUnit de ninguna espera (cubre el escenario
  «Ninguna clase del módulo de identidad espera»).** No requiere Docker. ROJO: crear
  `.../test/java/com/confia/architecture/NoBlockingWaitInIdentityTest.java` con su fixture permanente
  de rechazo bajo `.../architecture/fixture/identity/` (una clase que invoca `Thread.sleep` dentro de
  un paquete `com.confia.identity..` simulado), afirmando que ninguna clase de producción de
  `com.confia.identity..` invoca `Thread.sleep`, `TimeUnit.sleep`, `Object.wait` ni
  `LockSupport.park*`, y que el fixture **sí** se rechaza. Falla porque la regla no existe. Crear
  también, en el mismo paso, una prueba unitaria sobre `BouncyCastleArgon2PasswordHasher` con un doble
  contador de invocaciones que afirme que el señuelo (`DECOY_LABEL`, sal constante de 16 bytes) se
  construye una sola vez por instancia con los parámetros vigentes. VERDE: escribir la regla de
  ArchUnit y el fixture; construir el señuelo en el constructor del hasher, calculado y no literal
  (`design.md` decisión 7). Ejecutar ambas: verde. Medir el diff real de PR C2 con
  `git diff --numstat <base-de-PR-C1>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr'
  ':(exclude)**/generated/**'`; si supera 800, detener y reportar los puntos de corte candidatos
  medidos. Verificación final en checkout limpio con `./mvnw -B verify`; empujar la rama (base PR C1)
  y confirmar la integración continua en verde. Commit convencional (`feat`) sobre la regla de
  ArchUnit y el señuelo. — Especificación `identity`, requisitos «El retardo se calcula dentro de la
  transacción y se materializa fuera de ella» (escenario «Ninguna clase del módulo de identidad
  espera») y «Verificación Argon2id contra un hash señuelo cuando la cuenta no existe» (fundamento de
  construcción, sin el camino de cuenta inexistente todavía — eso es C3a); `design.md` §11, paso 12;
  decisión 1, guarda del punto 4 de la sección de contrato

---

## PR C3a — corte C3 (parte 1): caso de uso, con dobles, y el escritor de auditoría

Rama `change/identity-module-and-password-authentication-use-case`, base PR C2. **Depende de la sonda S1
(tarea 1.1) para poder importar `TransactionRunner`**, ya resuelta en C1.

- [x] 3.1 **ROJO/VERDE — puerto y adaptador de escritura de auditoría, primer escritor de producción
  de `shared_audit_log`.** Requiere Docker. ROJO: crear
  `.../test/java/com/confia/shared/infrastructure/JooqAuditLogWriterIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: inserta una fila con `AuditLogWriter.append(...)` sin fijar
  `id`, `prev_hash` ni `row_hash`, y afirma que el disparador de encadenamiento (V3) los asigna y que
  la fila queda encadenada a la cadena de su institución. Falla porque `AuditLogWriter` no existe.
  VERDE: crear `.../shared/audit/AuditLogWriter.java` (puerto) y `AuditEntry.java` (registro con las
  dieciséis columnas insertables, `source_ip` y `user_agent` en `NULL` porque no hay petición HTTP) en
  `com.confia.shared.audit`, junto a `AuditLogReader`; crear
  `.../shared/infrastructure/JooqAuditLogWriter.java` en `com.confia.shared.infrastructure`, junto a
  `JooqAuditLogReader`, construido con `.set()` explícito por columna —nunca un registro completo que
  incluiría `id`/`prev_hash`/`row_hash` con `NULL`—. Ejecutar `JooqAuditLogWriterIT`: verde. Commit
  convencional (`feat`) sobre el puerto y el adaptador de auditoría. — Especificación `identity`,
  requisito «Puerto y adaptador de escritura de la bitácora de auditoría» (su escenario); `design.md`
  §11, paso 14; §14, punto 5 («por confirmar»: `.set()` explícito, no registro completo)

- [x] 3.2 **ROJO/VERDE — el caso de uso `AuthenticateWithPassword`, con dobles de los cinco puertos.**
  No requiere Docker (unitaria con dobles, sin PostgreSQL). ROJO: crear
  `.../test/java/com/confia/identity/application/AuthenticateWithPasswordTest.java` con dobles de
  `StaffAccountRepository`, `LoginBackoffStore`, `PasswordHasher`, `LoginIdentifierFingerprinter` y
  `LoginInstitutionProvider`: contraseña correcta produce `Authenticated` con `requiredDelay` calculado
  y **sin ninguna espera real**; toda causa de rechazo produce `Rejected` con un motivo interno
  distinto para contraseña incorrecta y cuenta inexistente; el señuelo se ejecuta exactamente una vez,
  verificable por el doble de `PasswordHasher.matches(...)`, cuando la cuenta no existe; el hash
  almacenado **no** se recalcula en el camino de éxito (`hash(...)` nunca invocado); y una prueba de
  reflexión, `AuthenticationResultTest`, que afirma que ni `Authenticated` ni `Rejected` declaran
  ningún campo de alcance de autorización. Fallan porque `AuthenticateWithPassword`,
  `AuthenticationCommand`, `AuthenticationDecision` y los cinco puertos no existen. VERDE: crear
  `.../identity/application/AuthenticationCommand.java`, `AuthenticationDecision.java` (con el
  Javadoc del contrato de seis puntos de `design.md` §6.2), los cinco puertos
  (`StaffAccountRepository`, `LoginBackoffStore`, `PasswordHasher`, `LoginIdentifierFingerprinter`,
  `LoginInstitutionProvider`) y `.../identity/application/AuthenticateWithPassword.java` (la guarda de
  cierre de la decisión 11: falla de forma ruidosa, **nunca** `Rejected`, si la institución del
  `SecurityContext` no coincide con la del proveedor). Ejecutar `AuthenticateWithPasswordTest` y
  `AuthenticationResultTest`: verde. Medir el diff real de PR C3a con
  `git diff --numstat <base-de-PR-C2>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr'
  ':(exclude)**/generated/**'`; si supera 800, detener y reportar los puntos de corte candidatos.
  Verificación final en checkout limpio con `./mvnw -B verify`; empujar la rama (base PR C2) y
  confirmar la integración continua en verde. Commit convencional (`feat`) sobre el caso de uso y sus
  contratos. — Especificación `identity`, requisitos «Resultado tipado de la autenticación con dos
  desenlaces» (los dos escenarios), «Verificación Argon2id contra un hash señuelo cuando la cuenta no
  existe» (su escenario, camino completo), «Ausencia de verificación contra contraseñas
  comprometidas...» (escenario «El hash almacenado no se recalcula») y «El retardo se calcula dentro
  de la transacción y se materializa fuera de ella» (escenario «La duración exigible se devuelve y la
  transacción ya confirmó», sobre dobles); `design.md` §11, paso 15; §6.1, §6.2 y §10 (decisión 11)

---

## PR C3b — corte C3 (parte 2): adaptadores reales, atomicidad, concurrencia y cierre

Rama `change/identity-module-and-password-authentication-adapters`, base PR C3a.

- [x] 4.1 **Sondas S3 y S4 (bloqueantes de C3b, `design.md` §10), y ROJO/VERDE de los adaptadores jOOQ
  con el caso de uso completo contra PostgreSQL real.** Requiere Docker. **S4**: ejecutar
  `./mvnw -B -pl app generate-sources` con `V5` ya aplicada y confirmar en `target/generated-sources`
  que los tipos generados se llaman `IdentityStaffAccount` e `IdentityLoginBackoff` (si no, R2 rompe
  la construcción antes de compilar nada, y se reporta antes de escribir los adaptadores). **S3**:
  contra `postgres:18-alpine`, sesión 1 ejecuta el reclamo (`ON CONFLICT ... DO UPDATE ...
  RETURNING`) dentro de una transacción abierta; sesión 2 ejecuta el mismo reclamo y debe quedar
  bloqueada hasta la confirmación de la primera, y debe recibir el estado **previo**. Registrar ambos
  resultados en `apply-progress.md`. Si S3 desmintiera la devolución del estado previo, adoptar el
  respaldo de `design.md` decisión 5 (`SELECT ... FOR UPDATE` precedido de
  `INSERT ... ON CONFLICT DO NOTHING`) y reportar la desviación. ROJO: crear
  `.../test/java/com/confia/identity/infrastructure/JooqStaffAccountRepositoryIT.java` y
  `JooqLoginBackoffStoreIT.java` (reclamo, cierre, y la sentencia exacta de `design.md` decisión 5), y
  `.../test/java/com/confia/identity/application/AuthenticateWithPasswordIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: los escenarios de retardo desde el tercer fallo con su duración
  auditada, la progresión limitada a 900 s, la expiración del contador a los 30 minutos, la limpieza
  tras éxito, el retardo también sobre cuenta inexistente con el mismo número de asientos, el motivo
  interno nunca observable, y **la contraseña correcta durante el retroceso que tiene éxito tras el
  retardo** (`requiredDelay = PT2S` junto a `Authenticated`, el caso límite de `design.md` §4). Fallan
  porque los adaptadores no existen. VERDE: crear `.../identity/infrastructure/JooqStaffAccountRepository.java`,
  `JooqLoginBackoffStore.java` y `ConfiguredLoginInstitutionProvider.java` (falla al construirse si
  falta `confia.identity.login-institution-id`). Ejecutar las tres suites: verde. Commit convencional
  (`feat`) sobre los adaptadores jOOQ. — Especificación `identity`, requisito «Retardo por intentos
  fallidos con retroceso exponencial» (los siete escenarios completos, incluidos «El retardo se aplica
  también a una cuenta inexistente» y «Una contraseña correcta durante el retroceso tiene éxito»);
  «Estado del retroceso persistido en PostgreSQL...» (escenario «El estado del retroceso existe para
  un identificador sin cuenta», ver la nota de discrepancia arriba); `design.md` §10 (S3, S4) y §11,
  paso 16

- [ ] 4.2 **ROJO/VERDE — atomicidad y concurrencia del retroceso.** Requiere Docker. ROJO: crear
  `.../test/java/com/confia/identity/application/LoginBackoffAtomicityIT.java` sobre
  `CommittingPostgresIntegrationTest` (una transacción que falla de forma determinista antes de
  confirmar no deja avanzar el contador ni un asiento nuevo) y
  `.../test/java/com/confia/identity/application/LoginBackoffConcurrencyIT.java` con `CyclicBarrier`
  sincronizando dos hilos justo después de abrir la transacción y antes del reclamo (patrón de
  `TransactionRunnerRetryIT` e `IdempotentExecutorConcurrencyIT`, nunca una espera por reloj): dos
  intentos fallidos concurrentes contra la misma cuenta no pierden ninguna escritura y el contador
  final refleja los tres fallos. Fallan si la frontera transaccional de `design.md` decisión 9 no
  estuviera ya completa (debería pasar sin cambios de producción, dado que 4.1 ya la entrega; si
  fallara, corregir el orden de bloqueos declarado — retroceso antes de cabecera de cadena — antes de
  tocar la prueba). VERDE: confirmar en verde. Commit convencional (`test`) si no hubo cambio de
  producción, o (`fix`) si lo hubo. — Especificación `identity`, requisito «Estado del retroceso
  persistido en PostgreSQL, con atomicidad de efecto y auditoría» (escenarios «El efecto y su asiento
  de auditoría se confirman o revierten juntos» y «Dos intentos fallidos concurrentes... no pierden
  ninguna escritura»); `design.md` §11, paso 17; decisión 9 completa

- [ ] 4.3 **ROJO/VERDE — institución del proceso, ausencia de secretos observables, y los inventarios
  de exclusión con destino nombrado.** Requiere Docker para `LoginInstitutionIT` y
  `LoginTimingReportIT`; no lo requiere para `IdentityScopeExclusionInventoryTest` (inventario estático
  sobre el árbol de clases y el texto de las migraciones). ROJO: crear
  `.../test/java/com/confia/identity/application/LoginInstitutionIT.java` (dos instituciones con el
  mismo correo resuelven cada una la suya; la solicitud que declara una institución distinta en su
  cuerpo es ignorada; la guarda de cierre falla de forma ruidosa ante contexto ajeno, nunca
  `Rejected`); `.../test/java/com/confia/identity/IdentitySecretRedactionIT.java` (un intento real con
  la contraseña literal `Segura#2026` del escenario del delta; se recoge todo el texto producido —
  mensajes de excepción, `toString()` de cada objeto involucrado, filas de `shared_audit_log`— y se
  afirma que ninguno contiene la contraseña, el hash ni la pimienta, comparando longitudes y formas,
  nunca el valor, en los mensajes de fallo del propio `assertThat`); y
  `.../test/java/com/confia/identity/IdentityScopeExclusionInventoryTest.java` con cuatro afirmaciones
  de conjunto no vacío, siguiendo la disciplina de `AuditScopeExclusionInventoryTest`: ninguna regla de
  dirección IP se aplica (diez intentos simulados desde la misma IP contra cinco cuentas, sin límite
  aplicado), ninguna verificación contra contraseñas comprometidas, los parámetros de Argon2id son el
  piso declarado y no uno calibrado, y ninguna prueba de Playwright ejercita el flujo; además, ninguna
  clase de `com.confia.identity..` depende de `org.slf4j..`, `java.util.logging..` ni
  `org.apache.commons.logging..`. Fallan porque ninguna de las tres clases existe. VERDE: si algún
  registro apareciera durante 1.2-4.1, redactarlo antes de continuar (no se espera cambio de
  producción, dado que `toString()` redactado ya se entregó en C2); confirmar las cuatro exclusiones en
  verde. Commit convencional (`test`). — Especificación `identity`, requisitos «La institución previa
  a la autenticación proviene de la configuración del proceso» (su escenario), «Ningún secreto de este
  módulo es observable en registros, excepciones ni pruebas» (su escenario), «Ausencia de la dimensión
  por dirección IP...», «Ausencia de verificación contra contraseñas comprometidas...» (escenario
  «Ninguna verificación... ocurre»), «Ausencia de calibración de Argon2id...» y «Ausencia de prueba de
  extremo a extremo con Playwright...» (los tres escenarios de brecha); `design.md` §11, paso 18;
  decisión 14 completa

- [ ] 4.4 **Medición de tiempo, notas editoriales de los ajustes 3, 4 y 5, cierre de `docs/09`, y
  verificación final del cambio completo.** Requiere Docker para la medición de tiempo; no lo requiere
  para las notas documentales. Sin evidencia de ROJO propia: son notas editoriales y una medición, no
  comportamiento nuevo — se dice así en vez de fingir un rojo. Crear
  `.../test/java/com/confia/identity/application/LoginTimingReportIT.java`: 25 intentos con correo
  existente y 25 con inexistente, **midiendo y reportando** la diferencia de medianas sin convertirla
  en puerta de construcción (`design.md` §7.2; la puerta real es de `session-tokens-and-web-layer`
  sobre respuestas HTTP). Añadir en `docs/03-seguridad.md` la nota editorial fechada 2026-09-24 del
  **ajuste 3** en §4.4 (estado por cuenta en PostgreSQL, el retardo se calcula y se exige dentro de la
  transacción y se materializa en el borde, y no es un limitador de tasa) y del **ajuste 4** en §6.1
  (`identity_staff_account` es la tabla que esa sección llama `user`; `identity_login_backoff` recibe
  el mismo trato, sin privilegio para `confia_portal_app`), con la redacción exacta de `design.md`
  §15. Añadir la nota editorial fechada 2026-09-24 del **ajuste 5, autorizado por el propietario**, en
  §4.1 (Argon2id sin Spring Security, sobre Bouncy Castle directo; la pimienta de 32 bytes aplicada
  como `secret` sí se cumple; la integración con `PasswordEncoder` queda trivial para cuando llegue la
  cadena de filtros), **sujeta al resultado de S2** (tarea 2.1): si S2 desmintió lo anotado, esta nota
  se reescribe antes de cerrarla, nunca se entrega con el texto original sin revisar. Actualizar
  `docs/09-roadmap-y-fases.md` en el entregable 3, nombrando las tres mitades diferidas
  (`mfa-totp-and-password-recovery`, `session-tokens-and-web-layer`, cambio 11). Medir el tiempo final
  de la suite `*IT.java` completa del cambio y registrarlo en `apply-progress.md`; actualizar
  `apps/api/README.md` si cambia alguna afirmación previa sobre el presupuesto de 8 minutos (`design.md`
  §13). Medir el diff real de PR C3b con
  `git diff --numstat <base-de-PR-C3a>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr'
  ':(exclude)**/generated/**'`; si supera 800, detener y reportar los puntos de corte candidatos.
  Verificación final en checkout limpio, con `JAVA_HOME` en JDK 25, `MAVEN_OPTS` con el almacén
  `Windows-ROOT` y Docker activo: `./mvnw -B verify` en `apps/api`. Confirmar los 23 escenarios de
  `specs/identity/spec.md` y los 8 de `specs/build-integrity/spec.md` trazados según esta lista (no
  según la tabla envejecida de `design.md` §7.1); empujar la rama (base PR C3a) y confirmar la
  integración continua en verde. Commit convencional (`docs`) sobre las notas editoriales y
  `docs/09`, y otro (`test`) sobre `LoginTimingReportIT`. — `design.md` §11, paso 19-20; §15, ajustes 3,
  4 y 5; §7.2

# Tareas: recuperación de contraseña con token de un solo uso

Cambio 7 de F0, **tercero de cuatro** (`docs/09-roadmap-y-fases.md` §3). Esta lista implementa
`proposal.md` y `design.md`, los dos aprobados el 2026-09-30; el diseño incluye la decisión 13
(`TotpCode`). También implementa los dos deltas aprobados el mismo día.

Los escenarios se contaron **de nuevo contra los propios archivos de delta**, no se copiaron de
`design.md` §6.1:

| Delta | Requisitos | Escenarios |
|---|---|---|
| `specs/identity/spec.md`, `## ADDED Requirements` | 17 | 34 |
| `specs/identity/spec.md`, `## MODIFIED Requirements` | 4 | 12 |
| `specs/build-integrity/spec.md` | 2 | 7 |
| **Total** | **23** | **53** |

Coinciden con lo que declara `design.md`. La tabla de trazabilidad del final asigna cada uno de los
53 escenarios a una tarea.

**Total: 14 tareas**, una por debajo del límite de quince de `openspec/changes/README.md`:

| Corte | Tareas |
|---|---|
| C1 | 3 |
| C2 | 3 |
| C3 | 3 |
| C4 | 3 |
| C5 | 2 |

---

## Discrepancias encontradas y reportadas

### 1. El adaptador del token (C1) depende de tipos de dominio que el diseño asigna a C2

`design.md` §1 y §10 ponen en **C1** el puerto `PasswordResetTokenRepository` y su adaptador jOOQ, y
en **C2** los tipos de dominio. Pero la firma del puerto, fijada en la decisión 5, usa
`PasswordResetTokenHash` (`findByHash(institution, hash)`) y devuelve la fila de token
(`PasswordResetTokenRow`, decisión 4). C1 no compilaría sin esos dos tipos.

**Partición adoptada:** la tarea 1.2 crea `PasswordResetTokenHash` y `PasswordResetTokenRow`.
- `PasswordResetTokenHash` nace solo con su constructor desde el hexadecimal de 64 caracteres, y ya
  como clase final redactada.
- La fábrica `PasswordResetTokenHash.of(PlainPasswordResetToken)` se añade en la tarea 2.2, cuando
  existe el token en claro.

C2 conserva el token en claro, la política del token, los motivos de rechazo, la política de
longitud y `TotpCode`. Es la misma clase de contradicción de orden que la lista de la parte 2
encontró con `EnrollTotpSecondFactor`, y se resuelve igual: se adopta la única partición que compila
en cada corte y se reporta. Quien archive corrige `design.md` §1 y §10.

### 2. `design.md` §11 autoriza partir el cambio «sin esperar» si llega a dieciséis tareas

§11 dice: «Si la fase de tareas llega a dieciséis, se aplica el corte de abajo sin esperar». La
instrucción vigente de esta fase es la contraria: si se supera el límite, se detiene y se reporta,
sin partir por cuenta propia.

Corregido el 2026-09-30 en `design.md` §11: ahora dice que se consulta al propietario antes de aplicar el corte. Con catorce tareas no se activa. Aun así, esta lista **no** adopta la frase de §11 como regla: si
la aplicación descubre que alguna tarea necesita convertirse en dos de nivel superior, se detiene y
se consulta al propietario. Las particiones permitidas sin consulta son solo subtareas (`4.1a`,
`4.1b`) o pull requests adicionales dentro del mismo corte, como en la parte 2.

### 3. Dos escenarios de brecha apuntan en §6.1 a pruebas que no los cubren literalmente

Las clases existentes se comprobaron por lectura en el árbol:

| Escenario | Lo que dice `design.md` §6.1 | Lo que hay en el árbol |
|---|---|---|
| «Ninguna verificación contra contraseñas comprometidas ocurre durante la autenticación» | `AuthenticateWithPasswordIT` | Ningún caso de esa clase trata contraseñas comprometidas. La cobertura existente es `IdentityScopeExclusionInventoryTest.noProductionClassOfIdentityDependsOnAnyNetworkClientLibrary`, cuyo mensaje cita la brecha literalmente |
| «El hash almacenado no se recalcula aunque sus parámetros difieran de los vigentes» | «La prueba existente de la parte 1» | Es `AuthenticateWithPasswordTest.aSuccessfulLoginNeverRecalculatesTheStoredHash` |

Esta lista traza las clases reales (tarea 5.2).

### 4. La decisión 10 dice «cuatro valores» y la decisión 13 añade un quinto

La decisión 10 enumera cuatro valores no observables: token, su hash, contraseña nueva y su hash. La
decisión 13 añade `TotpCode` a la misma lista de `IdentitySecretRedactionIT`, así que son cinco.

`design.md` §10 (C2) solo nombra `TotpCodeTest` y deja implícito cuándo entra `TotpCode` en
`IdentitySecretRedactionIT`. **Adoptado:** entra en la tarea 2.3, junto con su corrección, porque
la decisión 13 la declara «una tarea más en C2». Los cuatro valores del restablecimiento entran en
la tarea 5.1.

### 5. §6.1 deja sin decidir dónde vive la prueba del `CHECK` del hash

Para el escenario «El catálogo rechaza un hash que no es de 64 caracteres hexadecimales», §6.1 dice
«`IdentityRowSecurityIT` o una prueba de esquema propia». **Adoptado:** va en `MultiTenantSchemaIT`,
junto a su precedente directo, `theDatabaseRejectsAnRtnLongerThanTheTechnicalGuardEvenBypassingTheDomain`
(tarea 1.1).

### 6. `design.md` §5 no enumera las clases de prueba

§5 lista los archivos de producción, pero no nombra las clases de prueba nuevas, aunque §6.1 sí las
nombra:
- `RequestPasswordResetIT`
- `IssuePasswordResetTokenIT`
- `ResetPasswordWithTokenIT`
- `PasswordResetConcurrencyIT`
- `PasswordResetTotpBackoffSharingIT`
- `PasswordResetAtomicityIT`
- `JooqPasswordResetTokenRepositoryIT`
- `PasswordResetRequestTimingReportIT`
- el accesorio `LeakingPasswordResetTokenFixture`

Es la misma omisión que tuvo la parte 2. No bloquea: esta lista crea cada clase en su tarea.

### Comprobado y sin discrepancia

La decisión 13 afirma dos cosas, y las dos se verificaron por lectura:
- **Los cuatro casos existentes de `TotpCodeTest` siguen valiendo.** Solo usan
  `isInstanceOf(IllegalArgumentException.class)` y `value()`; ninguno lee el mensaje.
- **El cambio de `toString()` no afecta a la verificación.**
  `TotpVerificationPolicy.matchingCounter` usa `presented.value().getBytes(...)` y nunca `equals`,
  `hashCode` ni `toString()`.

---

## Estrategia de entrega y ramas

- **Estrategia `auto-chain`, cadena `stacked-to-main`**, con el presupuesto de **800 líneas de
  cambio efectivo por pull request** de `docs/15-flujo-de-trabajo-git.md` §3. Es el mismo precedente
  de los cambios 5 y 6 y de las dos partes anteriores del cambio 7; no se reabre.
- **Una rama por corte**, cada una con base en la anterior, y la de C1 con base en `main`:
  - `change/password-recovery-token-c1-schema`
  - `change/password-recovery-token-c2-domain`
  - `change/password-recovery-token-c3-request-and-issuance`
  - `change/password-recovery-token-c4-reset`
  - `change/password-recovery-token-c5-redaction-and-docs`
- Si un corte supera 800 líneas, se parte en pull requests encadenados dentro del mismo corte, con
  sufijo `a` y `b` (por ejemplo `...-c4a-reset-core` y `...-c4b-second-factor`). Cada mitad se
  verifica por separado con `./mvnw -B verify` antes de proponerla.
- **Medición del diff** al cerrar la última tarea de cada corte:
  `git diff --numstat <base-del-corte>...HEAD -- . ':(exclude)openspec' ':(exclude)**/generated/**'`.
- **Expectativa realista** (`design.md` §11): de nueve a doce pull requests, no cinco.

| Corte | Riesgo según §11 | Líneas con 1,5× a 3× | Punto de medición |
|---|---|---|---|
| C1 | Alto | 675-1350 | Al cerrar 1.3 |
| C2 | Medio | 600-1200 | Al cerrar 2.3 |
| C3 | Alto | 675-1350 | Al cerrar 3.3 |
| C4 | Muy alto | 900-1800 | Al cerrar 4.1, y otra vez en 4.3 |
| C5 | Bajo | 375-750 | Al cerrar 5.2 |

**Ejecutor.** Todos los comandos se lanzan desde `apps/api`, con JDK 25. Docker debe estar activo
para toda tarea con `*IT` o que dispare la generación de código de jOOQ; cada tarea lo indica.
- Las pruebas unitarias se ejecutan con
  `./mvnw -B -pl app -am verify -Dtest=<Clases> -Dit.test=<ClasesIT> -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`.
- La puerta final de cada corte es `./mvnw -B verify` en un checkout limpio, más la integración
  continua en verde.

**TDD estricto** (`openspec/config.yaml`): cada clase de prueba se ve en **ROJO** antes de
**VERDE**, y la evidencia se registra en `openspec/changes/password-recovery-token/apply-progress.md`.
Nunca se inventa un rojo: una tarea que no puede observarlo lo dice. Los commits son convencionales,
en inglés y sin atribución de herramientas de IA.

**Ningún bean de producción.** Ninguna tarea añade anotaciones de Spring ni toca
`com.confia.bootstrap` (`design.md` §1.2). El registro de módulos en un proceso exige antes el
cambio 15, `process-entry-point-isolation`.

---

## C1 — esquema `V7`, repositorio del token, bloqueo de la cuenta

Rama `change/password-recovery-token-c1-schema`, con base en `main`.

- [x] 1.1 **Sondas S1 y S2, y migración `V7` con sus puertas de esquema (ROJO/VERDE).** Requiere
  Docker.
  - **Sonda S1** (`design.md` §9), antes de escribir la migración:
    1. `docker run --rm -d --name prt-s1 -e POSTGRES_PASSWORD=probe postgres:18-alpine`.
    2. Crear una tabla mínima con el índice parcial `identity_password_reset_token_one_open_per_account`
       de la decisión 1.
    3. Abrir dos sesiones con `docker exec -it prt-s1 psql -U postgres -v VERBOSITY=verbose`.
    4. En la sesión A, `BEGIN; INSERT …;`. En la B, el mismo `INSERT`, que debe quedarse esperando.
       En la A, `COMMIT;`.
    5. Registrar el `SQLSTATE` exacto de la sesión B y repetir con `ROLLBACK` en la A.

    **Éxito:** la B espera y falla con `23505` tras el `COMMIT`, y con `ROLLBACK` inserta.
    **Respaldo:** si el código fuera otro, registrarlo. La tarea 3.3 afirmará el código observado;
    el diseño no cambia, porque el índice es solo red de seguridad.
  - **ROJO:**
    - `RolePrivilegeMatrixIT`: filas de `identity_password_reset_token` para los cinco roles, con
      sentencias reales. `SELECT`, `INSERT` y `UPDATE` permitidos y `DELETE` rechazado para
      `confia_admin_app`; las cuatro operaciones rechazadas para `confia_portal_app`.
    - `MultiTenantSchemaIT`: la tabla pasa las puertas genéricas sin exclusión. Caso nuevo: una
      inserción con `confia_admin_app` y el contexto de su institución, cuyo `token_hash` es un
      texto base64url de 43 caracteres, se rechaza por `identity_password_reset_token_hash_chk`
      (discrepancia 5).
    - `IdentityRowSecurityIT`: dos instituciones con un token cada una, de modo que la segunda solo
      ve el suyo; y sin `app.institution_id`, cero filas y no un error de permiso.

    Las tres fallan porque `V7` no existe.
  - **Sonda S2**, en cuanto `V7` exista y antes de escribir cualquier adaptador:
    `./mvnw -B -pl app generate-sources` y
    `find app/target/generated-sources -name 'IdentityPasswordResetToken*.java'`.
    **Éxito:** existe `IdentityPasswordResetToken` y `TableOwnershipByModuleTest` pasa.
    **Respaldo:** renombrar la tabla antes de seguir.
  - **VERDE:** crear
    `apps/api/app/src/main/resources/db/migration/V7__create_identity_password_reset_token.sql` con
    el DDL exacto de la decisión 1. Sin `DEFAULT` en `issued_at` ni en `expires_at`; `REVOKE` antes
    de los `GRANT`; ningún `DELETE`.
  - **Verificación:**
    `./mvnw -B -pl app -am verify -Dit.test=RolePrivilegeMatrixIT,MultiTenantSchemaIT,IdentityRowSecurityIT,TableOwnershipByModuleTest -Dfailsafe.failIfNoSpecifiedTests=false`.
  - **Commits:** `feat` para la migración y `test` para las puertas.
  - **Escenarios:** `build-integrity`, los siete (B1-B7). **Diseño:** decisión 1, sondas S1 y S2.

- [x] 1.2 **Puerto y adaptador jOOQ del token (ROJO/VERDE).** Requiere Docker.
  - **ROJO:**
    - `.../identity/domain/PasswordResetTokenHashTest.java`: solo acepta 64 caracteres
      hexadecimales en minúscula; `toString()` no contiene el valor; el mensaje de la excepción no
      interpola el valor recibido.
    - `.../identity/infrastructure/JooqPasswordResetTokenRepositoryIT.java`, sobre
      `CommittingPostgresIntegrationTest`, que cubre:
      - `insert` seguido de `findByHash`;
      - `countIssuedSince` excluye un token emitido exactamente en el instante `since`;
      - `supersedeOpen` marca todos los tokens abiertos, también uno vencido, y devuelve cuántos;
      - `consume` devuelve `false` sobre uno usado, uno superado o uno con `expires_at <= now`, y
        `true` una sola vez sobre uno vivo;
      - un token vencido sigue en la tabla dos horas después de emitido;
      - un token usado y otro superado siguen al día siguiente con sus marcas.
  - **VERDE:**
    - `PasswordResetTokenHash`, clase final redactada, solo con el constructor desde el hexadecimal
      (discrepancia 1).
    - `PasswordResetTokenRow`, un `record` sin secretos: id, cuenta, `issuedAt`, `expiresAt`,
      `consumedAt` y `supersededAt`.
    - El puerto `.../identity/application/PasswordResetTokenRepository.java`, con los cinco métodos
      de la decisión 5.
    - `.../identity/infrastructure/JooqPasswordResetTokenRepository.java`, `final`, constructor
      sobre `DSLContext`, sin anotación. `consume` es un único `UPDATE` condicional que devuelve si
      `updatedRows > 0`, el patrón de `JooqRecoveryCodeRepository.markUsed`.
  - **Verificación:**
    `./mvnw -B -pl app -am verify -Dtest=PasswordResetTokenHashTest -Dit.test=JooqPasswordResetTokenRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`.
  - **Escenarios:** `identity`, «Un token vencido sigue almacenado» (I31) y «Los tokens usados y
    superados también se conservan» (I32). **Diseño:** decisiones 1, 4 y 5.

- [x] 1.3 **Bloqueo y reescritura del hash de la cuenta (ROJO/VERDE).** Requiere Docker.
  - **ROJO:** extender `JooqStaffAccountRepositoryIT`:
    - `lockById` devuelve la cuenta, y una segunda sesión que intenta el mismo `lockById` queda
      esperando hasta que la primera confirma. Se sincroniza con `CyclicBarrier` y se observa con
      `pg_locks` o con un tiempo de espera acotado del JDBC, nunca con una espera por reloj.
    - `lockById` sobre una cuenta de otra institución devuelve vacío.
    - `replacePasswordHash` sustituye el hash y devuelve `true`; sobre una cuenta inexistente
      devuelve `false`.
  - **VERDE:** añadir los dos métodos al puerto `StaffAccountRepository` e implementarlos en
    `JooqStaffAccountRepository` con `.forUpdate()`, el patrón de
    `JooqIdempotencyRecordStore.lockExisting`. `findBy` no cambia.
  - **Verificación:** la del corte con `-Dit.test=JooqStaffAccountRepositoryIT`, después
    `./mvnw -B verify` en limpio. **Medir el diff de C1.** Empujar y confirmar la integración
    continua.
  - **Escenarios:** ninguno directo; es base de I7, I8 e I20. **Diseño:** decisiones 2 y 5.

---

## C2 — dominio: token, política, longitud y `TotpCode`

Rama `change/password-recovery-token-c2-domain`, con base en C1. Ninguna tarea de C2 requiere
Docker, salvo la verificación final completa.

- [x] 2.1 **Sonda S4 y la regla de 12 a 128 caracteres (ROJO/VERDE).**
  - **Sonda S4** (`design.md` §9), en `jshell`: normalizar con NFKC `"casa azul🌋🌊"`
    y `"casa azul 🌋🌊"`, e imprimir `codePointCount` y `length()`.
    **Éxito:** `11 13` y `12 14`. **Respaldo:** detener y avisar al propietario antes de seguir,
    porque el escenario aprobado lleva esos literales.
  - **ROJO:** `.../identity/domain/StaffPasswordLengthPolicyTest.java`:
    - 11 puntos de código da `TooShort`; 12 da `Accepted`; 128 da `Accepted`; 129 da `TooLong`,
      todos con letras minúsculas y sin exigir composición;
    - los dos literales de la sonda dan `TooShort` y `Accepted`;
    - un valor aceptado produce un `PlainPassword` sin excepción;
    - una propiedad de jqwik: para toda cadena de 12 a 128 puntos de código tras NFKC, el
      resultado es `Accepted`.
  - **VERDE:** `.../identity/domain/StaffPasswordLengthPolicy.java`, con `MIN_CODE_POINTS = 12`,
    `MAX_CODE_POINTS = 128` y el mismo `Normalizer.Form.NFKC` que `PlainPassword.of`. No lanza.
  - **Escenarios:** «Los bordes de 12 y 128 caracteres» (I15, parte unitaria) y «La longitud se
    cuenta en caracteres, no en unidades de codificación» (I16). **Diseño:** decisión 8, sonda S4.

- [x] 2.2 **Token en claro, fábrica del hash, política del token y motivos (ROJO/VERDE).**
  - **ROJO:**
    - `PlainPasswordResetTokenTest`:
      - `generate` con un `SecureRandom` semilla fija da 43 caracteres base64url sin relleno, que
        decodifican a 32 bytes;
      - dos generaciones con el `SecureRandom` real dan valores distintos;
      - `of` rechaza 42 y 44 caracteres, y caracteres fuera del alfabeto, sin interpolar el valor
        en el mensaje;
      - `toString()` no contiene el valor.
    - `PasswordResetTokenHashTest`, ampliada: `of(token)` es igual al SHA-256 hexadecimal de los
      bytes ASCII del texto, calculado de forma independiente en la prueba.
    - `PasswordResetTokenPolicyTest`, con instantes fijos:
      - un token emitido a las 10:00:00 es vigente a las 10:29:59 y deja de serlo a las 10:30:00;
      - `VALIDITY` vale exactamente `Duration.ofMinutes(30)`;
      - con emisiones a las 10:00:00, 10:10:00 y 10:20:00, está permitida a las 11:00:01 y
        también a las 11:00:00 (el borde de 60 minutos exactos queda fuera), y no a las 10:30:00;
      - la precedencia es usado, superado, vencido: un token vencido y superado da `SUPERSEDED`.
  - **VERDE:** `PlainPasswordResetToken` (clase final redactada); la fábrica
    `PasswordResetTokenHash.of(...)`; `PasswordResetTokenPolicy`, con `VALIDITY = 30 min`,
    `ISSUANCE_WINDOW = 60 min` y `MAX_ISSUANCES_PER_WINDOW = 3`; y `PasswordResetRejectionReason`.
  - **Escenarios:** «El token entregado tiene 32 bytes y no se repite» (I4, parte unitaria), «La
    ventana es de sesenta minutos móviles» (I6, parte unitaria) y «El borde de los treinta minutos
    es estricto» (I37, parte unitaria). **Diseño:** decisiones 3 y 4.

- [x] 2.3 **`TotpCode` sin fuga (ROJO/VERDE).** Requiere Docker para `IdentitySecretRedactionIT`.
  - **ROJO:**
    - `TotpCodeTest` gana dos casos que deben fallar contra el código actual:
      - el mensaje de `new TotpCode("12a456")` no contiene `12a456`. Hoy lo contiene: `TotpCode.java`
        lanza `"… was " + value`;
      - el `toString()` de `new TotpCode("005924")` no contiene `005924`. Hoy el `record` imprime
        `TotpCode[value=005924]`.
    - `IdentitySecretRedactionIT` añade `TotpCode` a la lista de valores que no pueden aparecer
      (discrepancia 4).
  - **VERDE:** el mensaje pasa a ser `"a TOTP code must be exactly 6 digits"` y se sobrescribe
    `toString()` con un texto fijo. El patrón `^[0-9]{6}$`, `equals` y `hashCode` no cambian.
  - **Verificación:** los cuatro casos existentes de `TotpCodeTest` siguen en verde, y también
    `VerifyTotpCodeIT` y `TotpVerificationBackoffIT` sin tocarlos. Después `./mvnw -B verify` en
    limpio. **Medir el diff de C2.**
  - **Escenarios:** ninguno de los deltas. **Diseño:** decisión 13.

---

## C3 — solicitud y emisión

Rama `change/password-recovery-token-c3-request-and-issuance`, con base en C2. Requiere Docker.

- [x] 3.1 **`RequestPasswordReset` con el puerto de programación (ROJO/VERDE).**
  - **ROJO:**
    - `RequestPasswordResetTest`, con dobles:
      - la guarda lanza `IllegalStateException` si la institución del contexto difiere de
        `LoginInstitutionProvider` y no toca ningún puerto;
      - `RequestPasswordResetDecision` no tiene componentes (reflexión).
    - `RequestPasswordResetIT`, con el `AuthenticateWithPassword` real y un doble de programación
      que solo registra:
      - para una cuenta existente, una llamada al doble con el id interno y sin correo, cero filas
        de token y un resultado sin token;
      - tras la solicitud, un inicio de sesión real con la contraseña vigente tiene éxito;
      - para un correo inexistente, el mismo resultado, cero llamadas al doble y exactamente un
        asiento `identity.password_reset.requested` con la huella como `entity_id` y sin el
        correo en ninguna columna;
      - para el correo existente, exactamente un asiento con la misma acción;
      - once solicitudes para once cuentas quedan las once programadas;
      - sin invocar la emisión, no existe ningún token.
    - `PasswordResetRequestTimingReportIT`, informativo y no puerta, igual que
      `LoginTimingReportIT`.
  - **VERDE:** `RequestPasswordReset`, `RequestPasswordResetCommand`,
    `RequestPasswordResetDecision` y el puerto `PasswordResetIssuanceScheduler`. El asiento sigue la
    decisión 9.
  - **Escenarios:** I1, I2, I26, I29, I30, I39 e I40. **Diseño:** decisiones 6 y 9.

- [x] 3.2 **`IssuePasswordResetToken` con el puerto de envío (ROJO/VERDE).**
  - **ROJO:**
    - `IssuePasswordResetTokenTest`, con dobles: el doble de envío se invoca después de que la
      transacción confirma y nunca si se revierte.
    - `IssuePasswordResetTokenIT`, con un doble de envío que captura:
      - la fila emitida contiene el SHA-256 del token capturado y ninguna columna contiene el
        token en claro (SQL crudo);
      - dos emisiones sucesivas dan tokens de 43 caracteres y distintos;
      - con emisiones a las 10:00:00, 10:10:00 y 10:20:00, la de las 10:30:00 no emite, no envía,
        el token de las 10:20:00 sigue vivo y existe un asiento `issuance_skipped`;
      - la de las 11:00:01 emite.
    - Junto con `RequestPasswordResetIT`: para una cuenta en el límite y para un correo inexistente,
      las dos solicitudes dan el mismo resultado y un asiento `requested` cada una, y el límite solo
      aparece al omitirse la emisión.
  - **VERDE:** `IssuePasswordResetToken`, `IssuePasswordResetTokenDecision` y el puerto
    `PasswordResetLinkSender`. El envío ocurre fuera de `TransactionRunner.execute`.
  - **Escenarios:** I3, I4, I5, I6 e I41. **Diseño:** decisiones 5, 6 y 9.

- [x] 3.3 **Sonda S3 y concurrencia de emisión (ROJO/VERDE).**
  - **Sonda S3** (`design.md` §9):
    1. Con el contenedor de S1 y una `identity_staff_account` mínima, en la sesión A:
       `BEGIN; SELECT … FOR UPDATE; INSERT token;`.
    2. En la sesión B: `BEGIN; SELECT … FOR UPDATE;`, que debe quedar esperando.
    3. `COMMIT` en la A y, en la B, `SELECT count(*)`.

    **Éxito:** 1. **Respaldo:** emitir con `TransactionRunner.execute(context,
    IsolationLevel.SERIALIZABLE, …)` y registrar la desviación.
  - **ROJO:** `PasswordResetConcurrencyIT`, mitad de emisión:
    - dos emisiones para la misma cuenta, sincronizadas con `CyclicBarrier`, dejan exactamente un
      token abierto y el otro superado;
    - **control negativo**: con un doble de `StaffAccountRepository` cuyo `lockById` no bloquea,
      dos emisiones concurrentes terminan con una excepción cuyo `SQLState`, recorriendo la cadena
      de causas, es el observado en S1. Así se demuestra que el índice parcial es una red real y
      que el bloqueo es lo que evita que salte.
  - **VERDE:** lo que la prueba exija del orden de la decisión 2.
  - **Verificación:** después `./mvnw -B verify` en limpio. **Medir el diff de C3.**
  - **Escenarios:** «Dos emisiones concurrentes dejan exactamente un token vivo» (I7). **Diseño:**
    decisión 2, sondas S1 y S3.

---

## C4 — restablecimiento

Rama `change/password-recovery-token-c4-reset`, con base en C3. Requiere Docker. Es el corte de
mayor riesgo de tamaño, así que se mide al cerrar 4.1: si ya se acerca a 800 líneas, 4.2 y 4.3 van
en `...-c4b-second-factor`, con base en la anterior.

- [ ] 4.1 **`ResetPasswordWithToken` sin segundo factor activo (ROJO/VERDE).**
  - **ROJO:**
    - `ResetPasswordWithTokenTest`, con dobles:
      - orden de la decisión 7: un token inexistente no llama a `lockById` ni a `PasswordHasher`;
      - una longitud inválida no bloquea la cuenta ni invoca ningún verificador;
      - una prueba de segundo factor presentada a una cuenta sin MFA activa se ignora;
      - `ResetOutcome` se consume con un `switch` exhaustivo sin `default`.
    - `ResetPasswordWithTokenIT`, con el `AuthenticateWithPassword` real para comprobar
      contraseñas:
      - dentro de la ventana se acepta; el segundo uso se rechaza; la contraseña anterior ya no
        autentica y la nueva sí;
      - superado a las 10:26 se rechaza y el asiento lleva el motivo `token-superseded`, no
        `token-expired`; el token más reciente completa a las 10:30;
      - el borde 10:29:59 se acepta y 10:30:00 se rechaza;
      - una solicitud programada sin emitir no supera el token vivo;
      - `mfa_required = true` sin secreto restablece solo con el enlace y el siguiente inicio de
        sesión devuelve `SecondFactorEnrollmentRequired`;
      - `mfa_required = false` con secreto inscrito restablece sin código y no deja ningún asiento
        `identity.mfa.totp_verification.*`;
      - contraseñas de 11 y 129 caracteres se rechazan y el token sigue vivo; de 12 y 128 se
        aceptan;
      - la institución de otro proceso no encuentra el token, que sigue vivo en la suya;
      - `ResetPasswordCommand` no tiene componente de institución, con el patrón de
        `LoginInstitutionIT`;
      - un token nunca emitido, uno vencido, uno superado y uno usado dan el mismo `TokenRejected`,
        y cuatro asientos con sus motivos propios;
      - un restablecimiento solo produce el consumo, el hash nuevo y sus asientos, y ninguna acción
        contiene `notif`, `email`, `mail` ni `sent`;
      - un token nunca emitido no cambia la contraseña;
      - `123456789012` se acepta.
  - **VERDE:** `ResetPasswordWithToken` con los pasos 1 a 4 y 6 a 9 de la decisión 7, más
    `ResetPasswordCommand` (clase final redactada), `SecondFactorProof`, `ResetPasswordDecision` y
    `ResetOutcome`. La rama de MFA activa devuelve por ahora `SecondFactorMissing` ante cualquier
    prueba, y se completa en 4.2.
  - **Medir el diff.**
  - **Escenarios:** I11, I12, I15 (parte de integración), I17, I18, I19, I23 (primera mitad), I28,
    I34, I35, I36, I37, I38 e I43. **Diseño:** decisiones 7, 8 y 9.

- [ ] 4.2 **Composición del segundo factor y contador TOTP compartido (ROJO/VERDE).**
  - **ROJO:**
    - `ResetPasswordWithTokenIT`, con MFA activa:
      - sin código da `SecondFactorMissing`, el token sigue vivo y la contraseña no cambia; con un
        código TOTP válido para el instante, se acepta;
      - un código de recuperación se acepta, queda usado y quedan nueve;
      - el mismo código de recuperación, en un restablecimiento posterior, no sirve;
      - con tres códigos sin usar, consumir uno en el restablecimiento deja un asiento
        `identity.mfa.recovery_codes.low` con dos restantes y ningún correo.
    - `PasswordResetTotpBackoffSharingIT`, con relojes fijos y el `VerifyTotpCode.execute` real
      para el camino del inicio de sesión:
      - fallos a las 09:01 y 09:02 por el inicio de sesión, y a las 09:03 por el restablecimiento,
        dan 1 s;
      - un fallo a las 09:04 por el inicio de sesión da 2 s;
      - un fallo a las 09:35 por el restablecimiento da 0 s;
      - tras un rechazo por código incorrecto, el contador de `identity_mfa_totp_backoff` vale 1,
        el asiento de la verificación fallida existe y el token vivo completa a las 14:03 con un
        código válido.

    La prueba cita en comentario las constantes `BackoffPolicy.FIRST_DELAYED_ATTEMPT = 3`,
    `CAP = 900 s` y `COUNTER_WINDOW = 30 min`, y la clave `(institution_id, account_id)` de la
    tabla.
  - **VERDE:** el paso 5 de la decisión 7, invocando `VerifyTotpCode.runWithinTransaction` y
    `ConsumeRecoveryCode.runWithinTransaction`, nunca su `execute`, y devolviendo sin lanzar.
  - **Control negativo**, registrado en `apply-progress.md`: una variante local que lanza ante un
    código incorrecto hace fallar `PasswordResetTotpBackoffSharingIT`. Se descarta sin
    comprometerla.
  - **Escenarios:** I9, I10, I13, I14 e I46. **Diseño:** decisión 7.

- [ ] 4.3 **Atomicidad y concurrencia del restablecimiento (ROJO/VERDE).**
  - **ROJO:**
    - `PasswordResetAtomicityIT`: con un `PasswordResetTokenRepository` que delega y un
      `AuditLogWriter` que falla de forma determinista en el asiento `completed`, después de
      consumir y de reescribir el hash, se comprueba tras la reversión que el token sigue vivo, que
      la contraseña es la anterior y que no hay asiento de restablecimiento.
    - `PasswordResetConcurrencyIT`, mitad de restablecimiento: dos restablecimientos con el mismo
      token y contraseñas distintas, sincronizados, dan exactamente un `Completed` y un
      `TokenRejected`; solo autentica la contraseña del ganador; hay un único asiento `completed`.
  - **VERDE:** lo que las pruebas exijan del orden de bloqueos de la decisión 2.
  - **Verificación:** después `./mvnw -B verify` en limpio. **Medir el diff de C4.**
  - **Escenarios:** I8 e I20. **Diseño:** decisiones 2 y 7.

---

## C5 — redacción, inventario de ausencias y documentación

Rama `change/password-recovery-token-c5-redaction-and-docs`, con base en C4.

- [ ] 5.1 **Redacción con control negativo e inventario de ausencias (ROJO/VERDE).** Requiere
  Docker.
  - **ROJO:**
    - `IdentitySecretRedactionIT` añade el token en claro, su SHA-256, la contraseña nueva y su hash
      Argon2id, recorriendo una emisión y un restablecimiento reales. El accesorio
      `.../identity/testsupport/fixture/LeakingPasswordResetTokenFixture.java` expone el token en
      `toString()`, y la prueba debe fallar contra él, siguiendo el precedente de
      `theRedactionSweepDetectsARealLeak`.
    - `IdentityScopeExclusionInventoryTest`:
      - ninguna clase de producción implementa `PasswordResetIssuanceScheduler` ni
        `PasswordResetLinkSender`;
      - solo `ResetPasswordWithToken` llama a `StaffAccountRepository.replacePasswordHash`;
      - ninguna clase de `com.confia.identity` referencia sesiones ni tokens de refresco.
    - Cada regla nueva se comprueba una vez contra un accesorio que la viola, y la evidencia se
      registra.
  - **VERDE:** lo que las reglas exijan. No debería hacer falta código de producción.
  - **Escenarios:** I21, I22, I23 (segunda mitad), I25, I27 e I33. **Diseño:** decisiones 10 y 11.

- [ ] 5.2 **Documentación, pruebas existentes de las brechas y cierre de trazabilidad.** No hay
  rojo que observar en la parte documental, y se dice así.
  - **Notas fechadas**, sin reescribir el cuerpo de ninguna sección (decisión 12):
    - en `openspec/changes/foundations-plan/exploration.md`, las dos condiciones duras para
      `session-tokens-and-web-layer`: revocar todas las familias al restablecer, y no fusionar el
      endpoint de solicitud sin el límite por IP;
    - `docs/03-seguridad.md` §4.7 y la adenda de §6.1;
    - `docs/08-datos-privacidad-y-retencion.md`, línea 322;
    - `docs/09-roadmap-y-fases.md` §3.
  - **Comprobar sin tocarlas** que siguen en verde las pruebas existentes que cubren tres
    escenarios modificados (discrepancia 3):
    - `IdentityScopeExclusionInventoryTest.noProductionClassOfIdentityDependsOnAnyNetworkClientLibrary`
      (I42);
    - `AuthenticateWithPasswordTest.aSuccessfulLoginNeverRecalculatesTheStoredHash` (I44);
    - `ConsumeRecoveryCodeIT.consumingTheEighthCodeAuditsTheLowSignalWithoutSendingAnyEmail` (I45).
  - **Registrar I24** en `apply-progress.md` como condición escrita, sin prueba en este cambio: su
    prueba es de `session-tokens-and-web-layer`.
  - **Cerrar la tabla de trazabilidad** de esta lista contra las clases reales.
  - **Verificación:** `./mvnw -B verify` en limpio; empujar y confirmar la integración continua.
    **Medir el diff de C5.**
  - **Escenarios:** I24 (condición escrita), I42, I44 e I45. **Diseño:** decisión 12.

---

## Trazabilidad de los 53 escenarios

**`identity`, requisitos añadidos (34)**

| # | Requisito | Escenario | Tarea |
|---|---|---|---|
| I1 | La solicitud solo programa la emisión | Cuenta existente programa una emisión y no crea token | 3.1 |
| I2 | ídem | No bloquea la cuenta ni cambia su contraseña | 3.1 |
| I3 | Solo SHA-256 | La base solo contiene el SHA-256 del token entregado | 3.2 |
| I4 | ídem | 32 bytes y no se repite | 2.2 y 3.2 |
| I5 | Tres emisiones por hora | La cuarta se omite | 3.2 |
| I6 | ídem | Ventana de sesenta minutos móviles | 2.2 y 3.2 |
| I7 | A lo sumo un token vivo | Dos emisiones concurrentes | 3.3 |
| I8 | ídem | Dos restablecimientos concurrentes | 4.3 |
| I9 | Segundo factor con MFA activa | Sin código no; con TOTP sí | 4.2 |
| I10 | ídem | El código de recuperación sustituye y queda consumido | 4.2 |
| I11 | Sin MFA activa | `true` sin secreto, con inscripción exigida después | 4.1 |
| I12 | ídem | `false` con secreto no presenta segundo factor | 4.1 |
| I13 | Mismo retroceso TOTP | Los fallos suman en el mismo contador | 4.2 |
| I14 | ídem | El fallo queda confirmado y el token sigue sirviendo | 4.2 |
| I15 | De 12 a 128 caracteres | Bordes 12 y 128 | 2.1 y 4.1 |
| I16 | ídem | Caracteres y no unidades | 2.1 |
| I17 | Institución del proceso | Se ignora la declarada | 4.1 |
| I18 | ídem | Un token de otra institución no se encuentra | 4.1 |
| I19 | Auditoría atómica | Los cuatro motivos | 4.1 |
| I20 | ídem | Un restablecimiento que no confirma no deja nada | 4.3 |
| I21 | Ningún secreto observable | Ni token ni contraseña en ningún texto | 5.1 |
| I22 | ídem | La prueba detecta una fuga reintroducida | 5.1 |
| I23 | Ausencia de revocación | El restablecimiento solo cambia la contraseña | 4.1 y 5.1 |
| I24 | ídem | La brecha se cierra con la primera emisión de refresco | 5.2 (condición escrita; la prueba es de la parte 4) |
| I25 | Ausencia de programación real | El puerto no tiene adaptador | 5.1 |
| I26 | ídem | Una solicitud no produce un token por sí sola | 3.1 |
| I27 | Ausencia de envío | El puerto no tiene adaptador | 5.1 |
| I28 | ídem | Un restablecimiento completado no notifica | 4.1 |
| I29 | Ausencia del límite por IP y del HTTP | Once solicitudes | 3.1 |
| I30 | ídem | El resultado es del caso de uso | 3.1 |
| I31 | Ausencia de purga | Un token vencido sigue almacenado | 1.2 |
| I32 | ídem | Usados y superados se conservan | 1.2 |
| I33 | Ausencia del restablecimiento administrativo | El único camino es el token | 5.1 |
| I34 | ídem | Sin token válido no cambia nada | 4.1 |

**`identity`, requisitos modificados (12)**

| # | Requisito | Escenario | Tarea |
|---|---|---|---|
| I35 | Recuperación de un solo uso | Dentro de la ventana | 4.1 |
| I36 | ídem | Expirado o superado | 4.1 |
| I37 | ídem | Borde estricto de 30:00 | 2.2 y 4.1 |
| I38 | ídem | Solicitud aún no emitida | 4.1 |
| I39 | Prohibición de enumeración | Correo existente | 3.1 |
| I40 | ídem | Correo inexistente | 3.1 |
| I41 | ídem | Cuenta en el límite indistinguible | 3.2 |
| I42 | Ausencia de lista comprometida y de rehash | Autenticación sin verificación | 5.2 (prueba existente) |
| I43 | ídem | `123456789012` se acepta | 4.1 |
| I44 | ídem | El hash no se recalcula | 5.2 (prueba existente) |
| I45 | Ausencia de envío del aviso | Calculado y auditado, sin correo | 5.2 (prueba existente) |
| I46 | ídem | También en el restablecimiento | 4.2 |

**`build-integrity` (7)**

| # | Requisito | Escenario | Tarea |
|---|---|---|---|
| B1 | Tabla nueva | Puertas genéricas sin exclusión | 1.1 |
| B2 | ídem | Una institución no lee los tokens de otra | 1.1 |
| B3 | ídem | Sin contexto, cero filas | 1.1 |
| B4 | ídem | El catálogo rechaza un hash no hexadecimal de 64 | 1.1 |
| B5 | Permisos | La matriz cubre los cinco roles | 1.1 |
| B6 | ídem | `confia_admin_app` no borra | 1.1 |
| B7 | ídem | `confia_portal_app` sin privilegios | 1.1 |

**Sondas antes de la tarea que bloquean:**

| Sonda | Dónde se ejecuta | Tarea que bloquea |
|---|---|---|
| S1 | Al principio de 1.1 | 1.1, y su resultado se usa en 3.3 |
| S2 | Dentro de 1.1, antes de cualquier adaptador | 1.2 |
| S4 | Al principio de 2.1 | 2.1 |
| S3 | Al principio de 3.3 | 3.3 |

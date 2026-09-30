# Progreso de aplicación: `password-recovery-token`

- **Corte en curso:** C3. C1a, C1b y C2 fusionados en `main` (#68, #69 y #70).
- **Entorno:** OpenJDK 25.0.4 (paquete de Ubuntu 24.04), Maven Wrapper del repositorio, Docker 29.3.1
  con `postgres:18-alpine`. El `JAVA_HOME` del sistema apunta al JDK 21, así que toda invocación de
  Maven exporta `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` en la propia orden.
- **Línea base:** `./mvnw -B verify` sobre la rama del corte antes de tocar código: `BUILD SUCCESS`,
  186 pruebas del núcleo, 343 unitarias y 162 de integración, en 3 min 00 s.

---

## Tarea 1.1 — Sondas S1 y S2, migración `V7` y puertas de esquema

### Sonda S1 — segunda inserción contra el índice parcial con la primera sin confirmar

**PASA.** Tabla mínima con el índice parcial `identity_password_reset_token_one_open_per_account` en
un contenedor `postgres:18-alpine` propio. La sesión A abre la transacción, inserta y duerme 3 s; la
sesión B inserta la misma cuenta un segundo después.

```
A termina con COMMIT:   B espera 2,10 s y falla con
                        ERROR:  23505: duplicate key value violates unique constraint
                        "identity_password_reset_token_one_open_per_account"
A termina con ROLLBACK: B espera 2,11 s e inserta; queda 1 fila
```

El código observado es `23505`, el que el diseño suponía. La tarea 3.3 lo afirma en el control
negativo.

### Sonda S2 — nombre de la clase generada por jOOQ

**PASA, con una corrección de la orden.** `./mvnw -B -pl app generate-sources`, tal como la escriben
`design.md` §9 y la tarea 1.1, falla antes de generar nada: en esa fase el reactor aún no empaqueta
`confia-kernel` y la dependencia no se resuelve. Con `./mvnw -B -q -pl app -am compile`, que pasa por
la generación:

```
app/target/generated-sources/jooq/confia/generated/jooq/tables/IdentityPasswordResetToken.java
app/target/generated-sources/jooq/confia/generated/jooq/tables/records/IdentityPasswordResetTokenRecord.java
```

`TableOwnershipByModuleTest` pasa (2 pruebas). No hay que renombrar la tabla.

### ROJO

`RolePrivilegeMatrixIT` (6 pruebas nuevas), `MultiTenantSchemaIT` (1) e `IdentityRowSecurityIT` (4),
antes de que `V7` exista:

```
RolePrivilegeMatrixIT    Tests run: 34, Errors: 6    relation "identity_password_reset_token" does not exist
IdentityRowSecurityIT    Tests run: 10, Failures: 1, Errors: 3
MultiTenantSchemaIT      Tests run: 9,  Errors: 1
Total                    Tests run: 53, Failures: 1, Errors: 10
```

### VERDE

`V7__create_identity_password_reset_token.sql` con el DDL de la decisión 1, más comentarios:

```
RolePrivilegeMatrixIT       tests=34 failures=0 errors=0
MultiTenantSchemaIT         tests=9  failures=0 errors=0
IdentityRowSecurityIT       tests=10 failures=0 errors=0
TableOwnershipByModuleTest  tests=2  failures=0 errors=0
```

### Control negativo

Con `V7` debilitada a propósito (la restricción del hash cambiada a `length(token_hash) > 0` y
`DELETE` concedido a `confia_admin_app`) fallan exactamente las tres pruebas que protegen eso, y
ninguna otra:

```
IdentityRowSecurityIT.confiaAdminAppCanSelectInsertAndUpdateAPasswordResetTokenButNeverDeleteIt
MultiTenantSchemaIT.theDatabaseRejectsAPasswordResetTokenStoredInPlaceOfItsHash
RolePrivilegeMatrixIT.confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnThePasswordResetTokenTable
```

`V7` se restauró y se comprobó byte a byte contra la versión en verde.

### Notas

- **Privilegios con sentencias reales.** `RolePrivilegeMatrixIT` sigue su precedente y lee
  `has_table_privilege`; las sentencias reales que piden los escenarios B6 y B7 viven en
  `IdentityRowSecurityIT`, la misma división que ya hacen `IdempotencyKeyPrivilegeIT` y las tablas de
  la parte 1.
- **La prueba del `CHECK` inserta antes un hash bien formado** para la misma cuenta y lo marca como
  superado, así el rechazo no puede venir de la llave foránea, de la política de fila ni del índice
  parcial.
- **Escenarios cubiertos:** B1 a B7.

---

## Tarea 1.2 — Puerto y adaptador jOOQ del token

### ROJO

`PasswordResetTokenHashTest` (7 pruebas) y `JooqPasswordResetTokenRepositoryIT` (10) antes de que
existan los tipos: `./mvnw -B -q -pl app -am test-compile` falla con 80 errores `cannot find symbol`
sobre `PasswordResetTokenHash`, `PasswordResetTokenRow`, `PasswordResetTokenRepository` y
`JooqPasswordResetTokenRepository`.

### VERDE

- `PasswordResetTokenHash`: clase final, constructor desde el hexadecimal, `toString()` redactado y
  mensaje de rechazo sin el valor recibido.
- `PasswordResetTokenRow`: `record` sin secretos; `consumedAt` y `supersededAt` nulos mientras el
  token está abierto.
- El puerto `PasswordResetTokenRepository`, con los cinco métodos de la decisión 5.
- `JooqPasswordResetTokenRepository`: `consume` es un único `UPDATE` condicional sobre
  `consumed_at IS NULL`, `superseded_at IS NULL` y `expires_at > now`.

```
PasswordResetTokenHashTest           tests=7  failures=0 errors=0
JooqPasswordResetTokenRepositoryIT   tests=10 failures=0 errors=0
```

### Control negativo

Sin las condiciones `superseded_at IS NULL` y `expires_at > now` en `consume` fallan tres pruebas:

```
anExpiredTokenIsStillStoredTwoHoursAfterItWasIssued
consumeRefusesATokenExactlyAtItsExpiryButAcceptsItOneSecondBefore
consumeRefusesASupersededToken    (rechazado entonces por identity_password_reset_token_final_chk)
```

La última muestra que `final_chk` es una segunda red bajo el predicado. El adaptador se restauró.

### Notas

- **Discrepancia 1 de `tasks.md` aplicada:** los dos tipos de dominio nacen aquí y no en C2. La fábrica
  `PasswordResetTokenHash.of(token)` llega en la tarea 2.2.
- **Escenarios cubiertos:** I31 e I32.

---

## Tarea 1.3 — Bloqueo y reescritura del hash de la cuenta

### ROJO

`JooqStaffAccountRepositoryIT` con cinco pruebas nuevas, y el doble `FakeStaffAccountRepository` de
`AuthenticateWithPasswordTest` con los dos métodos nuevos: `test-compile` falla porque ninguno de los
dos métodos existe en el puerto (`method does not override or implement a method from a supertype`,
`cannot find symbol`).

### VERDE

- `StaffAccountRepository` gana `lockById` y `replacePasswordHash`. `findBy` no cambia.
- `JooqStaffAccountRepository.lockById` es `SELECT ... FOR UPDATE` (`.forUpdate()`, el patrón de
  `JooqIdempotencyRecordStore.lockExisting`); `replacePasswordHash` es un `UPDATE` que devuelve si
  afectó una fila.
- El doble de `AuthenticateWithPasswordTest` lanza `UnsupportedOperationException` en los dos: el
  inicio de sesión nunca bloquea una cuenta ni reescribe su hash, y si alguna vez lo hiciera, esas
  pruebas fallarían.

```
AuthenticateWithPasswordTest   tests=7 failures=0 errors=0
JooqStaffAccountRepositoryIT   tests=8 failures=0 errors=0
```

**Cómo se observa la espera sin reloj.** Un hilo toma `lockById` y queda retenido con un
`CountDownLatch`; la segunda transacción fija `set local lock_timeout = '500ms'` y llama a
`lockById`. Solo puede fallar con `55P03` (lock_not_available) si tuvo que esperar. Al liberar la
primera, la misma llamada devuelve la cuenta.

### Control negativo

Sin `.forUpdate()` en `lockById` falla exactamente
`aSecondLockByIdOnTheSameAccountWaitsUntilTheFirstTransactionEnds`, y ninguna otra. El adaptador se
restauró.

### Escenarios

Ninguno directo; es la base de I7, I8 e I20 (tareas 3.3 y 4.3).

---

## Cierre de C1

- **`./mvnw -B clean verify`:** `BUILD SUCCESS` en 2 min 46 s. 186 pruebas del núcleo, 350 unitarias
  (343 de la línea base más 7) y 188 de integración (162 más 26).
- **Diff medido** con `git diff --numstat origin/main -- . ':(exclude)openspec' ':(exclude)**/generated/**'`:
  **1119 líneas**, por encima de las 800 de `docs/15-flujo-de-trabajo-git.md` §3. El corte se parte en
  dos pull requests encadenados, sin cambiar ninguna tarea de nivel superior:
  - **C1a** `change/password-recovery-token-c1a-schema`: tarea 1.1 (`V7` y sus puertas de esquema),
    unas 380 líneas, más las 7 de `docs/09` que traen los artefactos SDD ya aprobados.
  - **C1b** `change/password-recovery-token-c1b-token-repository`, sobre C1a: tareas 1.2 y 1.3, unas
    740 líneas.

---

## Tarea 2.1 — Sonda S4 y la regla de 12 a 128 caracteres

### Sonda S4

**PASA.** La primera ejecución en `jshell`, con los emoji escritos como escapes `\uD83C…` por la
entrada estándar, dio `17 17` y `18 18`: la consola de `jshell` no conserva los surrogados que
recibe por la entrada, así que esa lectura no vale. Repetida con un archivo Java en UTF-8 que lleva
los **literales copiados byte a byte del delta** (`specs/identity/spec.md`, línea 232):

```
casa azul🌋🌊    11 puntos de código, 13 unidades UTF-16, NFKC no lo cambia
casa azul 🌋🌊   12 puntos de código, 14 unidades UTF-16, NFKC no lo cambia
```

El escenario aprobado no necesita ajuste.

### ROJO

`StaffPasswordLengthPolicyTest` (8 casos y 2 propiedades de jqwik): `test-compile` falla con 46
errores `cannot find symbol`.

### VERDE

`StaffPasswordLengthPolicy` con `MIN_CODE_POINTS = 12`, `MAX_CODE_POINTS = 128`, NFKC y
`codePointCount`. Devuelve `Accepted(PlainPassword)`, `TooShort` o `TooLong`, sin lanzar salvo con
`null`.

```
StaffPasswordLengthPolicyTest   tests=10 failures=0 errors=0
```

La primera versión de la propiedad «todo valor de 12 a 128 es aceptado» se agotó (100 intentos, 92
descartes) porque un solo generador de 0 a 140 puntos de código producía pocos casos dentro del
rango. Se separaron los generadores: de 12 a 128 para esa propiedad y de 0 a 140 para la de rechazo.

### Control negativo

Contando con `length()` (unidades UTF-16) en vez de `codePointCount` fallan
`theScenarioLiteralsAreCountedInCodePointsNotUtf16Units` y la propiedad de rechazo. Se restauró.

### Escenarios

I15 (parte unitaria) e I16.

---

## Tarea 2.2 — Token en claro, fábrica del hash, política del token y motivos

### ROJO

`PlainPasswordResetTokenTest` (8), `PasswordResetTokenPolicyTest` (9) y dos casos nuevos en
`PasswordResetTokenHashTest`: `test-compile` falla con 72 errores `cannot find symbol`.

### VERDE

- `PlainPasswordResetToken`: clase final redactada; `generate(SecureRandom)` produce 32 bytes en 43
  caracteres base64url sin relleno; `of` exige exactamente ese alfabeto y esa longitud, sin repetir
  el valor en el mensaje.
- `PasswordResetTokenHash.of(token)`: SHA-256 de los bytes ASCII del texto, en hexadecimal
  minúscula. La prueba calcula el valor esperado por su cuenta con `MessageDigest` y `HexFormat`.
- `PasswordResetTokenPolicy`: `VALIDITY = 30 min`, `ISSUANCE_WINDOW = 60 min`,
  `MAX_ISSUANCES_PER_WINDOW = 3`, `expiresAt`, `issuanceWindowStart` (inicio exclusivo, el mismo
  predicado `issued_at > since` del adaptador), `allowsAnotherIssuance` y `rejectionReasonOf` con el
  orden usado, superado, vencido.
- `PasswordResetRejectionReason`: los ocho motivos de la decisión 9, cada uno con su código de
  auditoría.

```
PlainPasswordResetTokenTest    tests=8 failures=0 errors=0
PasswordResetTokenHashTest     tests=9 failures=0 errors=0
PasswordResetTokenPolicyTest   tests=9 failures=0 errors=0
```

La prueba de `generate` usa `SHA1PRNG` sembrado antes de su primer uso, que es determinista, y
comprueba además que los 32 bytes son exactamente los que entrega el `SecureRandom` recibido.

### Control negativo

Con el vencimiento evaluado primero y con borde no estricto (`now.isAfter(expiresAt)`) fallan tres
pruebas: el borde de 10:30:00, la precedencia de superado sobre vencido y la de usado sobre vencido.
Se restauró.

### Escenarios

I4, I6 e I37, en su parte unitaria.

---

## Tarea 2.3 — `TotpCode` sin fuga (decisión 13)

### ROJO

- `TotpCodeTest`, dos casos nuevos contra el código de `main`:
  `theRejectionMessageNeverCarriesThePresentedValue` (el mensaje de `new TotpCode("12a456")` lo
  contenía: `"… was 12a456"`) y `toStringNeverCarriesTheCode` (el `record` imprimía
  `TotpCode[value=005924]`). `Tests run: 6, Failures: 2`.
- `IdentitySecretRedactionIT`: el barrido de TOTP guarda el código presentado en una variable y
  revisa, en un texto propio, su `toString()`, la decisión rechazada y el mensaje de un código mal
  formado. Falla con «produced text unexpectedly contains a presented TOTP code (6 characters)», sin
  repetir el valor.

El barrido del código usa su propio texto y no el texto completo de las filas: seis dígitos son lo
bastante cortos como para aparecer por azar en una marca de tiempo o en un identificador.

### VERDE

El mensaje pasa a `"a TOTP code must be exactly 6 digits"` y `toString()` devuelve
`TotpCode[REDACTED]`. El patrón, `equals` y `hashCode` no cambian; ninguna prueba ni código dependía
del mensaje anterior.

```
TotpCodeTest                 tests=6 failures=0 errors=0
IdentitySecretRedactionIT    tests=3 failures=0 errors=0
VerifyTotpCodeIT             tests=2 failures=0 errors=0   (sin tocar)
TotpVerificationBackoffIT    tests=2 failures=0 errors=0   (sin tocar)
```

---

## Cierre de C2

- **`./mvnw -B clean verify`:** `BUILD SUCCESS` en 2 min 13 s. 186 pruebas del núcleo, 381
  unitarias y 188 de integración.
- **Diff medido** contra C1b, sin `openspec` ni código generado: **685 líneas**, dentro de las 800.
  Un solo pull request.

---

## Tarea 3.1 — `RequestPasswordReset` con el puerto de programación

### ROJO

`RequestPasswordResetTest` (4), `RequestPasswordResetIT` (5) y `PasswordResetRequestTimingReportIT`
(1): `test-compile` falla con 54 errores `cannot find symbol` sobre los cuatro tipos nuevos.

### VERDE

- `PasswordResetIssuanceScheduler`: `schedule(InstitutionId, StaffAccountId)`, solo identificadores.
  Sin adaptador de producción.
- `RequestPasswordResetCommand`: un solo campo, el identificador presentado.
- `RequestPasswordResetDecision`: un `record` sin componentes.
- `RequestPasswordReset`: guarda de institución antes de abrir conexión; dentro de la transacción,
  huella, búsqueda, programación si la cuenta existe y un asiento `identity.password_reset.requested`
  con la huella como entidad en las dos ramas.

```
RequestPasswordResetTest              tests=4 failures=0 errors=0
RequestPasswordResetIT                tests=5 failures=0 errors=0
PasswordResetRequestTimingReportIT    tests=1 failures=0 errors=0   (informativo, no puerta)
```

La prueba de la guarda usa un `TransactionRunner` sobre un `SimpleDriverDataSource` sin URL:
si la guarda no lanzara, abrir la transacción fallaría con otra excepción, nunca con
`IllegalStateException`.

### Control negativo

Con el asiento escrito solo cuando la cuenta existe, falla
`aNonexistentAddressGetsTheSameResultNothingScheduledAndOneEntryWithoutTheAddress`. Se restauró.

### Notas

- Docker se detuvo entre sesiones del contenedor y hubo que levantar `dockerd` de nuevo; ninguna
  prueba se vio afectada.

### Escenarios

I1, I2, I26, I29, I30, I39 e I40.

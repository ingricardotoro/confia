# Progreso de aplicación: `identity-module-and-password-authentication`

- **Cortes cerrados:** PR C1, entregado en dos pull requests (#38 y #39, fusionados en `main` el
  2026-09-25 tras medir 906 líneas y partirlo).
- **Corte en curso:** PR C2.
- **Entorno:** JDK 25 (Temurin 25.0.3+9), Maven 3.9.16, Docker disponible.
  `JAVA_HOME` del sistema apunta al **JDK 21**, así que toda invocación de Maven se hace exportando
  `JAVA_HOME` al JDK 25 en la propia orden. `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`
  es un apaño local del entorno y **nunca se compromete** (`design.md` §13).

---

## Tarea 1.1 — Sondas S1 y S1b

Ejecutadas por el orquestador antes de delegar la implementación, siguiendo el precedente de los
cortes anteriores. **Sin evidencia de ROJO propia**: son sondas de comportamiento de herramienta, no
pruebas del delta.

### S1 — ¿`ApplicationModules.verify()` rechaza que `identity.application` dependa de `TransactionRunner`?

**Resultado: PASA. Se confirma lo que el diseño predijo.**

Procedimiento: se creó temporalmente
`apps/api/app/src/main/java/com/confia/identity/application/ProbeS1.java`, una clase de producción
mínima que importa `com.confia.shared.security.TransactionRunner`, y se ejecutó
`./mvnw -B -pl app test -Dtest=SpringModulithVerificationTest` desde `apps/api`.

`SpringModulithVerificationTest` falló, en su prueba de producción
`productionModulesHaveNoViolationYet`, con este mensaje exacto:

```
org.springframework.modulith.core.Violations: - Module 'identity' depends on non-exposed type
com.confia.shared.security.TransactionRunner within module 'shared'!
```

La otra prueba de la clase, la del fixture, siguió pasando: 2 ejecutadas, 1 fallo.

**Consecuencia:** la decisión 12 del diseño es necesaria, no precautoria. Sin `@NamedInterface` sobre
`shared/security`, el módulo `identity` no puede consumir `TransactionRunner` y la construcción
rompe. El fixture temporal se eliminó y el árbol quedó limpio, verificado con `git status --short`.

### S1b — ¿Dónde vive `@NamedInterface` en Spring Modulith 2.1.1?

**Resultado: RESUELTA, y corrige un supuesto de la lista de tareas.**

`./mvnw -B -pl app dependency:tree -Dincludes=org.springframework.modulith` resuelve hoy:

```
org.springframework.modulith:spring-modulith-core:jar:2.1.1:test
 \- org.springframework.modulith:spring-modulith-api:jar:2.1.1:test
```

Inspeccionando los dos jar aparecen **dos tipos distintos con el mismo nombre simple**, y solo uno
es la anotación:

| Tipo | Artefacto | Qué es |
|---|---|---|
| `org.springframework.modulith.NamedInterface` | **`spring-modulith-api`** | **La anotación.** `javap` la muestra como `public interface ... extends java.lang.annotation.Annotation`, con `value()`, `name()` y `propagate()` |
| `org.springframework.modulith.core.NamedInterface` | `spring-modulith-core` | Una clase del modelo, `implements Iterable<com.tngtech.archunit.core.domain.JavaClass>`. No es una anotación |

**Corrección a la tarea 1.2.** La lista de tareas dice «declarar las anotaciones de Spring Modulith
en alcance de **compilación** … (hoy `spring-modulith-core` es de alcance `test`)», lo que lleva a
promover `spring-modulith-core`. **Eso sería un defecto:** `spring-modulith-core` arrastra ArchUnit
(`com.tngtech.archunit`), que es una biblioteca **solo de pruebas**, y promoverlo la pondría en el
camino de clases de producción.

Lo que debe declararse en alcance de compilación es **`spring-modulith-api`**, que es donde vive la
anotación y no arrastra ArchUnit. `spring-modulith-core` se queda en alcance `test`, que es donde
`SpringModulithVerificationTest` lo usa.

Queda pendiente para la tarea 1.2 comprobar, con la sonda **S6**, que `dependencyConvergence` del
enforcer sigue en verde tras declarar `spring-modulith-api` en alcance de compilación.

---

## Tarea 1.2 — módulo `identity`, guarda de no vacuidad de W2, interfaces nombradas de `shared`

### ROJO observado

Se extendió `NoCrossModuleDomainImportsTest` con `atLeastTwoDistinctModuleDomainsExistInProductionCode`
y se renombró `productionCodeHasNoCrossModuleDomainImportYet` a `everyModuleUsesOnlyItsOwnDomain`
antes de crear ninguna clase de `com.confia.identity`. `./mvnw -B -pl app test
-Dtest=NoCrossModuleDomainImportsTest` falló, con este mensaje exacto:

```
java.lang.AssertionError:
[production code must contain at least two distinct modules' domain packages for this rule's
positive half to be anything other than vacuously true]
Expecting size of:
  ["com.confia.organization"]
to be greater than or equal to 2 but was 1
	at com.confia.architecture.NoCrossModuleDomainImportsTest.atLeastTwoDistinctModuleDomainsExistInProductionCode(NoCrossModuleDomainImportsTest.java:67)
```

Confirma exactamente lo que el diseño predice: con un solo módulo de dominio (`organization`), la
guarda de no vacuidad falla. 3 pruebas ejecutadas, 1 fallo.

### VERDE

Se crearon `com.confia.identity.package-info`, `identity.domain.AuthenticationResult` (sellado, con
`Authenticated`, `Rejected` y `RejectionReason` anidados, siguiendo el precedente de
`com.confia.shared.security.IdempotentOutcome` — un solo archivo, sin `permits` explícito, en vez de
tres archivos separados: la cláusula `permits` que decisión 10 dice que
`mfa-totp-and-password-recovery` editará sigue siendo la de este único archivo), `StaffAccount`,
`StaffAccountId` y `LoginIdentifier` (normalización NFKC, minúsculas con `Locale.ROOT`, validación de
longitud 3-320). `StaffAccount` no lleva todavía el hash de contraseña como campo: `StoredPasswordHash`
no existe hasta C2, y esta cuenta no tiene consumidor real hasta C3a (`StaffAccountRepository`); añadir
ese campo es cambio del corte que lo necesite, no una conjetura de esta tarea.

`@NamedInterface` se añadió a `shared/security/package-info.java` y `shared/audit/package-info.java`;
`docs/adr/ADR-0022-interfaz-nombrada-del-modulo-shared.md` eleva la decisión 12; `spring-modulith-api`
se declaró en alcance de compilación en `apps/api/app/pom.xml`, con `spring-modulith-core` sin tocar
(alcance `test`).

`NoCrossModuleDomainImportsTest` y `SpringModulithVerificationTest`: **verde**, 5 pruebas, 0 fallos.

### S6 — `dependencyConvergence` tras declarar `spring-modulith-api`

**Discrepancia con la orden literal de la tarea.** `./mvnw -B -pl app enforcer:enforce` **no** ejecuta
la regla real: falla con `No rules are configured. Use the skip flag if you want to disable
execution.`, porque invocar el objetivo del plugin directamente desde la línea de órdenes usa la
ejecución `default-cli`, que no hereda la configuración de la ejecución `enforce-build-integrity`
declarada dentro de `apps/api/pom.xml` y ligada a la fase `validate`. Esto es una peculiaridad de Maven
ajena a este cambio, no un defecto de la dependencia nueva.

**Comando que sí ejecuta la regla real:** `./mvnw -B -pl app validate`. Resultado:

```
[INFO] --- enforcer:3.6.3:enforce (enforce-build-integrity) @ confia-api ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.dependency.DependencyConvergence passed
[INFO] Rule 2: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] BUILD SUCCESS
```

**S6: PASA.** `dependencyConvergence` sigue en verde tras declarar `spring-modulith-api` en alcance de
compilación. `spring-modulith-core` se mantiene en `test`, sin arrastrar ArchUnit a producción.

### Discrepancia encontrada: la puerta de cobertura del 95 % en `com.confia.identity.domain` no
estaba cubierta por la mitad ROJO/VERDE de la tarea 1.2

Ni `tasks.md` ni `design.md` piden explícitamente pruebas de cobertura para `StaffAccount`,
`StaffAccountId` ni `AuthenticationResult` en esta tarea, pero `apps/api/app/pom.xml` ya exige 95 % de
línea y de rama sobre `com.confia.*.domain` desde el cambio 4, y `CLAUDE.md` fija ese mismo umbral como
parte de la definición de terminado. Un primer `./mvnw -B verify` completo (tras cerrar también la
tarea 1.3) lo confirmó en rojo: `com.confia.identity.domain` medía 0.28 de línea y 0.50 de rama. Se
añadieron `StaffAccountIdTest`, `StaffAccountTest` y `AuthenticationResultRecordsTest` (guardas de
construcción, sobre el precedente de `com.confia.kernel.InstitutionIdTest`), y casos de límite de
longitud y de valor nulo en `LoginIdentifierNormalizationTest`. Con eso, la puerta pasa: `[INFO] All
coverage checks have been met.` Se reporta aquí porque no estaba nombrada en la lista de tareas, no
porque se haya inventado una regla nueva.

---

## Tarea 1.3 — esquema `V5`, sonda S8, puertas de esquema extendidas

### ROJO observado

Se extendió `RolePrivilegeMatrixIT` con las filas de `identity_staff_account` e
`identity_login_backoff` para los cinco roles, y se creó `IdentityRowSecurityIT`. Antes de crear `V5`:

`./mvnw -B -pl app test -Dtest=RolePrivilegeMatrixIT`: 6 errores, todos
`org.postgresql.util.PSQLException: ERROR: relation "identity_staff_account" does not exist`.

`./mvnw -B -pl app test -Dtest=IdentityRowSecurityIT`: 3 pruebas, 1 fallo y 2 errores:

```
IdentityRowSecurityIT.confiaPortalAppIsRejectedOnAllFourOperations:82->assertRejected:147
[confia_portal_app must have no privilege on identity_staff_account]
expected: "42501"
 but was: "42P01"

IdentityRowSecurityIT.anAbsentInstitutionContextReturnsZeroRowsNotAPermissionError: DataAccess SQL
[insert into identity_staff_account ...]; ERROR: relation "identity_staff_account" does not exist

IdentityRowSecurityIT.oneInstitutionCannotReadAnotherInstitutionsStaffAccount: DataAccess SQL
[insert into identity_staff_account ...]; ERROR: relation "identity_staff_account" does not exist
```

`42P01` es `undefined_table`, exactamente lo esperado: la tabla no existe todavía.

### Sonda S8, ejecutada antes del VERDE

`LoginIdentifierNormalizationTest` (creada junto a `LoginIdentifier` en la tarea 1.2, ejecutada aquí
antes de escribir `V5`, como exige la tarea) prueba `LoginIdentifier.of(...)` con una muestra que
incluye `İ` (U+0130, turco), `ß` (alemán), `Σ` (griego), `日本語` (CJK) y el signo Kelvin (U+212A, el
caso en que NFKC sí descompone a una letra ASCII mayúscula **antes** de pasar a minúsculas), sola y
combinada. `./mvnw -B -pl app test -Dtest=LoginIdentifierNormalizationTest`: **8 pruebas, 0 fallos.**

**Resultado: ninguna forma normalizada produjo una letra ASCII mayúscula.** Consecuencia: el conjunto
aceptado por `LoginIdentifier` no necesitó acotarse antes de escribir `V5`, y
`identity_staff_account_email_lower_chk` se escribió exactamente como la decisión 4 la fija, sin
relajar la restricción.

### VERDE

Se creó `V5__create_identity_staff_account.sql` con el DDL exacto de la decisión 4 (las dos tablas,
seguridad de fila habilitada y forzada, `REVOKE ALL ... FROM PUBLIC` antes de los `GRANT`, sin
`DELETE` para ningún rol de aplicación). `./mvnw -B -pl app test
-Dtest=RolePrivilegeMatrixIT,IdentityRowSecurityIT,MultiTenantSchemaIT`: **32 pruebas, 0 fallos.**
`MultiTenantSchemaIT` pasó **sin modificarse**, como exige la tarea.

### Medición de tiempo

`./mvnw -B verify` completo (módulo `app`, todas las `*IT.java` incluidas): **3 min 48 s** (238 s) en
la primera ejecución limpia tras cerrar la tarea 1.3, muy por debajo del presupuesto de 8 minutos de
`design.md` §13. 91 pruebas de integración, 0 fallos; 170 pruebas unitarias, 0 fallos.

### Diff real de PR C1, y la partición que exigió (**hallazgo, no una decisión que se haya tomado en
silencio**)

`git diff --numstat main...change/staff-authentication-mfa-sessions -- . ':(exclude)openspec'
':(exclude)docs/adr' ':(exclude)**/generated/**'` con las tareas 1.2 y 1.3 ya confirmadas en verde
midió **906 líneas de cambio efectivo** (905 adiciones, 1 borrado), por encima del presupuesto de 800
líneas por corte que la propia sección de estrategia de entrega de esta lista fija como el que rige
este repositorio (no el de 400 del preámbulo de la sesión).

Siguiendo la regla operativa de la tarea 1.3 («si supera 800 líneas, detener la aplicación y reportar
los puntos de corte candidatos medidos, verificando cada mitad con `./mvnw -B verify` antes de
proponerla»), se midió el punto de corte natural que `design.md` §1.1 ya impone mecánicamente («módulo
antes que migración»): tareas 1.2 (módulo + interfaces nombradas) contra tarea 1.3 (migración `V5` +
pruebas de esquema).

| Mitad candidata | Contenido | Líneas efectivas | `./mvnw -B verify` |
|---|---|---|---|
| **C1a** | Tarea 1.2 completa (commits `feat(identity): add identity module domain skeleton` y `build(modulith): expose shared.security and shared.audit as named interfaces`): módulo `identity.domain`, `@NamedInterface` de `shared`, ADR-0022, POM | **415** | **Verde, verificado de forma aislada** dejando la rama en ese segundo commit: `BUILD SUCCESS`, 3 min 16 s, 177 pruebas unitarias y `[INFO] All coverage checks have been met.` en los dos módulos. En un primer intento, con la sonda S8 separada en el commit siguiente, esta misma mitad medía 0.92/0.50 de cobertura de línea/rama en `identity.domain` — por debajo del 95 % — porque dependía de pruebas de un commit posterior; se corrigió moviendo `LoginIdentifierNormalizationTest` (con sus casos de límite) al mismo commit que `LoginIdentifier`, antes de que nada se empujara |
| **C1b** | Tarea 1.3 completa (commit `feat(identity): create V5 migration with the two identity tables`): `V5`, `RolePrivilegeMatrixIT` extendida, `IdentityRowSecurityIT` | **491** | Verde: es la rama completa, ya verificada arriba (`BUILD SUCCESS`, 3 min 48 s, 91 pruebas de integración) |

**906 = 415 + 491.** Ninguna de las dos mitades por sí sola supera 800; **no hace falta partir el pull
request en dos**. Se deja la medición completa por disciplina, tal como la tarea la pide, y porque
reveló un defecto real de secuenciación de commits (una prueba de cobertura separada de la clase que
hace pasar la puerta), ya corregido reagrupando los commits antes de que nada se hubiera empujado.

**Commits del corte, en el orden final:**

1. `feat(identity): add identity module domain skeleton` — módulo `identity.domain` completo,
   incluida la sonda S8 y sus casos de límite.
2. `build(modulith): expose shared.security and shared.audit as named interfaces` — POM, ADR-0022,
   `@NamedInterface`.
3. `feat(identity): create V5 migration with the two identity tables` — migración y pruebas de
   esquema.

No se empuja la rama ni se abre pull request: lo hace el orquestador, según la instrucción explícita
de esta sesión.

---

## Revisión previa a la fusión — hallazgo bloqueante y su corrección

### El hallazgo

La auditoría de seguridad encontró que `IdentityRowSecurityIT` probaba la política de fila **solo
sobre `identity_staff_account`**. `identity_login_backoff` aparecía **cero veces** en el archivo.

Confirmado por el orquestador por tres vías independientes antes de aceptar el trabajo:

1. `grep` sobre el archivo de prueba: cero ocurrencias de `identity_login_backoff`.
2. El delta de este mismo cambio, `specs/build-integrity/spec.md`, dice «cada tabla nueva de
   identidad» y «cada una de las dos» (líneas 40, 49 y 66).
3. `CLAUDE.md`, línea 157: «Toda política de seguridad a nivel de fila necesita una prueba de
   integración que demuestre que un usuario no puede leer datos de otro». Sin excepción por tabla.

**Es la misma clase de defecto que bloqueó el cambio 6**, donde faltaba el aislamiento de
`shared_idempotency_key`. El patrón se repite: la prueba de aislamiento se escribe para la tabla que
da nombre al cambio, y la segunda hereda solo el supuesto de que una política idéntica se comporta
igual. Aquí con un agravante: la tabla olvidada es **la más sensible de las dos**, porque guarda una
huella por identificador *presentado*, incluidos los que no corresponden a ninguna cuenta.

### La corrección

Tres pruebas nuevas en `IdentityRowSecurityIT`, más el ayudante `assertRejected` parametrizado por
tabla. **Las dos instituciones usan deliberadamente la misma huella**: la clave primaria compuesta
`(institution_id, identifier_hash)` les permite convivir, así que lo único que puede separarlas es la
política. Con contra-aserción de que la institución B sigue viendo la suya, para que la prueba no
pase por haberlo ocultado todo a todos.

**Defecto encontrado en la propia corrección, antes de commitear.** El mensaje de la aserción
afirmaba que la huella era compartida, pero a la institución B se le había asignado una distinta.
Habría pasado por la razón equivocada.

### Verificación

`./mvnw -B verify` desde `apps/api`: **BUILD SUCCESS**, **94** pruebas de integración —eran 91, así
que las tres nuevas corrieron—, «All coverage checks have been met», 4:41 min.

### Control negativo, que es lo que demuestra que la prueba sirve

Que una prueba pase no demuestra que detecte nada. Se sustituyó temporalmente la política de
`identity_login_backoff` por `USING (true) WITH CHECK (true)` —una política que **existe pero no
aísla**, que es exactamente lo que ocurriría con un predicado mal escrito y todas las puertas de
catálogo en verde— y se volvió a ejecutar la clase:

```
Tests run: 6, Failures: 2, Errors: 0
  oneInstitutionCannotReadAnotherInstitutionsLoginBackoff:134
  anAbsentInstitutionContextReturnsZeroBackoffRowsNotAPermissionError:155
```

Las dos pruebas que dependen de la política **fallan**. La tercera,
`confiaPortalAppIsRejectedOnAllFourOperationsOnLoginBackoff`, sigue pasando **y debe seguir
pasando**: comprueba privilegios, no la política, y neutralizar la política no concede ningún
privilegio. Eso no es un hueco, es la separación correcta entre las dos garantías.

La migración se restauró con `git checkout` y se verificó que la política volvió a su forma original.

---

# Corte C2 — contraseña, señuelo y regla del retroceso

Rama `change/identity-module-and-password-authentication-backoff-and-password`, base `main` con C1
ya fusionado.

## Tarea 2.1 — Sonda S2

Ejecutada por el orquestador antes de delegar. **Sin evidencia de ROJO propia:** es una sonda de
comportamiento de biblioteca, no una prueba del delta.

**Pregunta:** ¿expone `Argon2PasswordEncoder` de `spring-security-crypto` un parámetro de secreto, y
lo expone `Argon2Parameters.Builder` de Bouncy Castle con `withSecret(...)`?

**Versiones inspeccionadas**, porque el proyecto no declara ninguna de las dos y no hay propiedad
gestionada que resolver —`help:evaluate` sobre `spring-security.version` y `bouncycastle.version`
devuelve «null object or invalid expression»—: `spring-security-crypto` **7.0.0** (la línea que
acompaña a Spring Boot 4.1) y `bcprov-jdk18on` **1.81**.

### Resultado: PASA. Se confirma lo que la propuesta anotaba, y con un argumento más fuerte

`javap` sobre `org.springframework.security.crypto.argon2.Argon2PasswordEncoder`:

```
public Argon2PasswordEncoder(int, int, int, int, int);
public static Argon2PasswordEncoder defaultsForSpringSecurity_v5_2();
public static Argon2PasswordEncoder defaultsForSpringSecurity_v5_8();
```

Un único constructor de cinco enteros —longitud de sal, longitud de hash, paralelismo, memoria e
iteraciones— y **ningún parámetro de secreto**, ni constructor alternativo, ni método de ajuste.

`javap` sobre `org.bouncycastle.crypto.params.Argon2Parameters$Builder`:

```
public Builder withSecret(byte[]);
public Builder withAdditional(byte[]);
public Builder withSalt(byte[]);
public Builder withMemoryAsKB(int);
public Builder withIterations(int);
public Builder withParallelism(int);
```

**`withSecret(byte[])` existe**, que es exactamente lo que `docs/03-seguridad.md` §4.1 exige para
aplicar la pimienta como `secret` de Argon2id.

### Hallazgo adicional, que refuerza la decisión 6 más de lo que el diseño argumentaba

El diseño daba como segunda razón que «añadir `spring-security-crypto` no ahorra la dependencia
criptográfica, porque su propio soporte de Argon2 se apoya en Bouncy Castle», y lo daba **sin
verificar**. Verificado ahora, y es más contundente:

- `javap -c` sobre `Argon2PasswordEncoder` muestra que compila contra
  `org/bouncycastle/crypto/generators/Argon2BytesGenerator`,
  `org/bouncycastle/crypto/params/Argon2Parameters` y `Argon2Parameters$Builder` — **las mismas tres
  clases que usaríamos directamente**.
- Su POM **no declara Bouncy Castle** en absoluto, así que es una dependencia opcional: quien quiera
  usar el codificador de Spring **tiene que añadir `bcprov` por su cuenta de todos modos**.

Es decir, la ruta por Spring **cuesta estrictamente más** —dos artefactos en vez de uno— **y entrega
estrictamente menos**: sigue sin poder aplicar la pimienta como `secret`. La decisión 6 se mantiene,
y la nota editorial sobre §4.1 que el propietario autorizó el 2026-09-24 no necesita reescribirse.

## Tarea 2.2 — Sonda S6, ejecutada por el orquestador tras declarar Bouncy Castle

El agente de implementación alcanzó a declarar la dependencia en los dos POM y cayó por un fallo de
red antes de ejecutar la sonda. El orquestador la ejecutó y corrigió una palabra en español que se
había colado en un comentario técnico de `apps/api/pom.xml`, que va en inglés.

**Comando, y la corrección que el corte C1 ya había dejado aprendida.** La tarea pedía
`./mvnw -B -pl app enforcer:enforce`, que **no ejecuta las reglas reales**: invocar el objetivo desde
la línea de órdenes usa la ejecución `default-cli`, que no hereda la configuración de
`enforce-build-integrity` ligada a la fase `validate`. El comando correcto es `./mvnw -B -pl app
validate`.

### Resultado: PASA

```
[INFO] --- enforcer:3.6.3:enforce (enforce-build-integrity) @ confia-api ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.dependency.DependencyConvergence passed
[INFO] Rule 2: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] BUILD SUCCESS
```

**Comprobación adicional, por el precedente de S1b.** En C1 se descubrió que `spring-modulith-core`
habría arrastrado ArchUnit al camino de producción, así que aquí se verificó lo mismo para Bouncy
Castle con `./mvnw -B -pl app dependency:tree -Dincludes=org.bouncycastle`:

```
\- org.bouncycastle:bcprov-jdk18on:jar:1.81:compile
```

**Sin ninguna dependencia transitiva** —ninguna línea indentada bajo la suya—, así que declararla en
alcance de compilación añade exactamente un artefacto y nada más.

## Tarea 2.2 — `BackoffPolicy`, el códec `$argon2id$` (sonda S7) y los objetos de valor restantes

Ejecutado por este agente, sobre la sonda S6 ya cerrada.

### Discrepancia encontrada y reportada: la clave de entorno de la pimienta

El texto de la tarea 2.2 pide `Argon2Pepper.java` «32 bytes exactos desde
`confia.identity.login-institution-id`... variable de entorno propia». Esa clave exacta —
`confia.identity.login-institution-id`— es, palabra por palabra, la clave canónica que `design.md`
§11 fija para `LoginInstitutionProvider` (decisión 11: el identificador de institución, un UUID, no
un secreto de 32 bytes). Es una cita cruzada equivocada de esta lista de tareas, de la misma familia
que las ocho ya corregidas antes de este corte, y no una instrucción que se pueda seguir literalmente
sin introducir una confusión real entre dos configuraciones de naturaleza distinta.

`design.md`, decisión 6, ya resuelve cómo debe entregarse la pimienta en este corte, con más
precisión que la tarea: «Entrega de la pimienta... **aquí llega por constructor**, como
`TransactionRunner` y `JooqInstitutionRepository` reciben lo suyo [...] El cableado real desde el
gestor de secretos es del cambio 11». Es decir: `Argon2Pepper` no lee ninguna variable de entorno por
sí misma en este corte; solo valida que el valor Base64 que reciba su constructor decodifique a
exactamente 32 bytes, y falla si no. Qué variable de entorno concreta alimenta ese constructor —y
cómo se lee— es cableado de una capa superior (una futura clase de arranque o del cambio 11), fuera
del alcance de `Argon2Pepper` y de esta tarea. Se sigue la redacción de `design.md`, más precisa y sin
la cita cruzada, y se deja registrada aquí para que quien lea después no la redescubra distinta.

### ROJO — `BackoffState`/`BackoffPolicy`

`BackoffPolicyTest` y `BackoffStateTest`, ejecutados antes de crear las clases de producción:

```
[ERROR] .../BackoffPolicyTest.java:[32,46] cannot find symbol: class BackoffPolicy
[ERROR] .../BackoffPolicyTest.java:[40,9] cannot find symbol: class BackoffState
[ERROR] .../BackoffStateTest.java:[21,9] cannot find symbol: class BackoffState
```//fallo real de compilación, no fingido; el archivo completo con las 4 escenarios del delta y las
tres propiedades jqwik (monotonía, tope, cero bajo el umbral) ya estaba escrito en ese momento.

VERDE: `BackoffState` y `BackoffPolicy` creados según `design.md` §6.3. `./mvnw -B -pl app test
-Dtest=BackoffPolicyTest,BackoffStateTest`: **12 pruebas, 0 fallos** (5 + 3 jqwik + 4). Commit
`12897c7`.

### ROJO — objetos de valor y mecanismo Argon2id

`PlainPasswordTest`, `StoredPasswordHashTest`, `IdentifierFingerprintTest`, `Argon2ProfileTest`,
`Argon2PhcCodecTest`, `Argon2PepperTest`, `HmacLoginIdentifierFingerprinterTest` y
`BouncyCastleArgon2PasswordHasherTest` escritos antes de sus clases de producción; `./mvnw -B -pl app
test -Dtest=PlainPasswordTest,StoredPasswordHashTest,IdentifierFingerprintTest` falla con:

```
[ERROR] .../PlainPasswordTest.java:[17,9] cannot find symbol: class PlainPassword
[ERROR] .../StoredPasswordHashTest.java: cannot find symbol: class StoredPasswordHash
[ERROR] .../IdentifierFingerprintTest.java:[21,9] cannot find symbol: class IdentifierFingerprint
```

y de igual forma para `Argon2ProfileTest`, `Argon2PhcCodecTest`, `Argon2PepperTest`,
`HmacLoginIdentifierFingerprinterTest` y `BouncyCastleArgon2PasswordHasherTest` contra
`Argon2Profile`, `Argon2PhcCodec`, `DecodedArgon2Hash`, `Argon2Pepper`,
`HmacLoginIdentifierFingerprinter` y `BouncyCastleArgon2PasswordHasher`, ninguna de las cuales
existía todavía (100 errores de compilación en total, registrados en la sesión).

**Un fallo real durante el ROJO→VERDE, no fingido.** La primera versión de
`PlainPasswordTest.appliesNfkcNormalizationBeforeAnythingElse` usaba U+FE64 («SMALL EQUALS SIGN»)
como forma de compatibilidad, asumiendo que normaliza a `=` bajo NFKC. Al ejecutar, AssertJ mostró que
las dos instancias NO eran iguales — la asunción sobre ese carácter concreto era incorrecta. Se
sustituyó por U+FF11 (dígito `1` de ancho completo), el ejemplo estándar y verificado de
normalización de compatibilidad, y la prueba pasó. Se deja registrado en vez de silenciarlo: es
exactamente la disciplina que este corte pide para el vector de RFC 9106 más abajo — no ajustar la
prueba a lo que salga, sino corregir la prueba solo cuando la propia prueba (no la implementación)
resulta estar mal fundamentada, y decirlo.

VERDE: las nueve clases de producción creadas (`PlainPassword`, `StoredPasswordHash`,
`IdentifierFingerprint` en `domain`; `Argon2Profile`, `Argon2PhcCodec`, `DecodedArgon2Hash`,
`Argon2Pepper`, `BouncyCastleArgon2PasswordHasher`, `HmacLoginIdentifierFingerprinter` en
`infrastructure`). `./mvnw -B -pl app test -Dtest=Argon2ProfileTest,Argon2PhcCodecTest,
Argon2PepperTest,HmacLoginIdentifierFingerprinterTest,BouncyCastleArgon2PasswordHasherTest`: **31
pruebas, 0 fallos**. Conjunto completo de dominio + infraestructura de identidad (`com.confia.identity.**`):
**90 pruebas, 0 fallos**. Commit `250c718`.

### Sonda S7 — el vector de prueba de Argon2id de RFC 9106 §5.3, reproducido byte a byte

**Cómo se verificó, con honestidad sobre el límite.** Este entorno no tiene acceso a la red para
descargar el RFC. El vector —contraseña de 32 bytes `0x01`, sal de 16 bytes `0x02`, secreto de 8
bytes `0x03`, datos asociados de 12 bytes `0x04`, `m=32` KiB, `t=3`, `p=4`, `tagLength=32`, y la
etiqueta esperada `0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659`— se transcribió
de memoria de entrenamiento de este agente, no de una consulta en vivo al RFC. Se declaró por
adelantado, en el propio comentario de la prueba, que si Bouncy Castle no reproducía ese valor exacto
se reportaría en vez de ajustar la prueba al resultado que saliera.

**Resultado: `Argon2PhcCodecTest.reproducesTheRfc9106Argon2idTestVectorByteForByteIncludingTheSecret`
pasó al primer intento**, comparando la etiqueta producida por `Argon2PhcCodec.rawHash(...)` —que
invoca `Argon2Parameters.Builder.withSecret(...)` de Bouncy Castle directamente— contra esa cadena
hexadecimal, byte a byte. Una segunda prueba,
`omittingTheSecretProducesADifferentTagThanTheVectorExpects`, confirma además que sin `secret` la
etiqueta producida **no** coincide con el vector: la pimienta se aplica de verdad, no se ignora en
silencio. Esto satisface la sonda S7 y cierra, con evidencia y no con promesa, la razón de ser de esta
prueba (`design.md`, decisión 6).

## Tarea 2.3 — regla de ArchUnit de ninguna espera, y el señuelo calculado en el constructor

### ROJO/VERDE — `NoBlockingWaitInIdentityTest`

A diferencia de las reglas de dominio, esta regla de ArchUnit vive dentro del propio archivo de
prueba (el mismo patrón que `NoStandardStreamAccessTest`, `NoUnapprovedPlainSqlTest` y
`TransactionsOnlyInSharedSecurityTest` ya establecen en este repositorio): no hay una clase de
producción separada que «no exista todavía». El ROJO real de esta pieza es, por tanto, el mismo que
pide el texto de la tarea («falla porque la regla no existe»): antes de este commit, ni el archivo de
prueba ni su fixture permanente (`BadBlockingWaitInIdentity`, bajo
`architecture/fixture/identity/`) existían en absoluto. Se escribieron ambos completos —la regla, el
fixture, y las dos aserciones— y se ejecutaron por primera vez juntos:

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.confia.architecture.NoBlockingWaitInIdentityTest
```

Las dos aserciones pasaron al primer intento: el código de producción de `com.confia.identity..`
(en ese momento, el de la tarea 2.2) no invoca ninguna primitiva de espera, y el fixture permanente
—que sí invoca `Thread.sleep`— es rechazado con el mensaje esperado (verificado con
`assertRuleRejects`, que exige que el mensaje no sea ruido de conjunto vacío y que sí contenga
`BadBlockingWaitInIdentity` y `Thread.sleep`). No se fabricó un rojo funcional donde no lo había: se
documenta este límite en vez de inventar un fallo que nunca ocurrió, siguiendo la misma disciplina de
honestidad que el resto de este documento.

### ROJO/VERDE — el señuelo calculado en el constructor

ROJO: `BouncyCastleArgon2PasswordHasherDecoyTest`, escrita contra un método `decoyHash()` y un tipo
`Argon2RawHasher` que todavía no existían:

```
[ERROR] .../BouncyCastleArgon2PasswordHasherDecoyTest.java:[29,9] cannot find symbol: class Argon2RawHasher
[ERROR] .../BouncyCastleArgon2PasswordHasherDecoyTest.java:[49,48] cannot find symbol: method decoyHash()
```

VERDE: se añadió la interfaz de prueba `Argon2RawHasher` (una costura empaquetada, no un puerto) y se
modificó el constructor de `BouncyCastleArgon2PasswordHasher` para construir `decoyHash` una sola vez,
hasheando `DECOY_LABEL` con una sal constante de 16 bytes (`design.md`, decisión 7). Dos pruebas
prueban lo que el delta pide: un doble contador de invocaciones confirma que el cómputo subyacente se
ejecuta **exactamente una vez** en la construcción (y no de nuevo al usar `hash(...)` para una
contraseña real, ni al leer `decoyHash()` otra vez); y dos instancias construidas con perfiles
distintos producen señuelos distintos, lo que demuestra que el señuelo se calcula con los parámetros
vigentes y no es un literal congelado. `./mvnw -B -pl app test
-Dtest=BouncyCastleArgon2PasswordHasherDecoyTest`: **3 pruebas, 0 fallos**. Commit `033df40`, junto a
la regla de ArchUnit.

## Hallazgo de cierre — cobertura de rama de `identity.domain` por debajo del 95 %

`./mvnw -B verify` completo (primera ejecución tras cerrar la tarea 2.3) terminó con:

```
[WARNING] Rule violated for package com.confia.identity.domain: branches covered ratio is 0.81, but expected minimum is 0.95
[ERROR] BUILD FAILURE
```

El reporte de JaCoCo señaló dos causas reales, no cosméticas:

1. **`BackoffPolicy.delayFor`, guarda de `attemptOrdinal < 1` nunca ejercida** (0 de 2 ramas
   cubiertas): ninguna prueba invocaba `delayFor` con un ordinal inválido. Se añadió
   `delayForRejectsAnAttemptOrdinalBelowOne`.
2. **Una rama muerta de verdad, no una prueba faltante**: `return uncapped.compareTo(CAP) > 0 ? CAP
   : uncapped;`, tras la guarda `if (exponent >= 10) return CAP;`, nunca puede tomar la rama
   verdadera —con `exponent` entre 0 y 9, `uncapped` vale como máximo `2^9 = 512` segundos, siempre
   por debajo de los 900 del tope—. Escribir una prueba para ese camino habría sido imposible sin
   falsear la aserción; se simplificó el código a `return Duration.ofSeconds(1L << exponent);`,
   eliminando la comparación inalcanzable en vez de fingir cubrirla.
3. **`PlainPassword.equals` y `StoredPasswordHash.equals`**, con 2 de 4 ramas sin cubrir cada una: solo
   se probaba el camino `instanceof` verdadero con valores iguales. Se añadieron los casos
   `equals(null)`, `equals(tipo no relacionado)` y `equals(mismo tipo, valor distinto)` a ambas
   suites.

Commit `f41df00` (`fix`), con las cuatro correcciones. Verificación tras el arreglo: `./mvnw -B -pl app
test -Dtest="com.confia.identity.domain.**"` en verde (64 pruebas), y `./mvnw -B verify` completo
descrito abajo.

## Verificación final de este corte

`./mvnw -B verify` desde `apps/api`, checkout limpio de esta rama, `JAVA_HOME` en JDK 25,
`MAVEN_OPTS` con el almacén `Windows-ROOT`, Docker activo:

```
[INFO] All coverage checks have been met.
[INFO] CONFIA API Parent .................................. SUCCESS
[INFO] CONFIA Kernel ...................................... SUCCESS [ 17.152 s]
[INFO] CONFIA API ......................................... SUCCESS [04:50 min]
[INFO] BUILD SUCCESS
[INFO] Total time:  05:11 min
```

**177** pruebas unitarias de `kernel` + **242** de `app` (unitarias) + **94** de `app` (`*IT.java`,
suite completa de integración), **0 fallos** en las tres. Muy por debajo del presupuesto de 8 minutos
de `design.md` §13. PIT (mutación) **no se ejecutó** en este `verify` local: `apps/api/pom.xml` fija
`confia.pit.phase=none` por defecto a propósito («nunca activa en un `./mvnw verify` plano») y solo
corre bajo el perfil `mutation-gate` en integración continua sobre `main` — comportamiento ya
documentado en `design.md` §13 y verificado aquí por lectura del POM, no supuesto.

## Diff real de PR C2, y el hallazgo de cierre que exige partir el corte

`git diff --numstat main...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`
(`main` es exactamente la punta de PR C1 — `git merge-base HEAD main` = `8d9c1ae`, el commit de la
fusión de C1b — así que este diff mide solo lo de este corte):

**1 765 líneas de cambio efectivo (1 765 adiciones, 0 borrados).**

Esto **supera el presupuesto de 800 líneas por pull request** que la sección de estrategia de entrega
de `tasks.md` fija para este repositorio (no el de 400 del preámbulo de la sesión). Siguiendo la regla
operativa de la tarea 2.3 («si supera 800, detener y reportar los puntos de corte candidatos
medidos»), **se detiene aquí la decisión de partir el corte**: no se reescribe el historial de esta
rama ni se crean ramas nuevas sin autorización explícita, siguiendo el mismo patrón que la partición
de PR C1 en C1a/C1b, que el propio `apply-progress.md` registra como ejecutada por el orquestador, no
por quien aplicó las tareas.

### Medición por commit

| Commit | Contenido | Líneas |
|---|---|---|
| `bb857c5` | Sonda S2 (documental, tarea 2.1) | 0 |
| `9d35d32` | Declaración de `bcprov-jdk18on` en los dos POM (tarea 2.1) | 32 |
| `12897c7` | `BackoffState` + `BackoffPolicy` (tarea 2.2, primera mitad) | 321 |
| `250c718` | Objetos de valor + Argon2id + huella (tarea 2.2, segunda mitad) | **1 114** |
| `033df40` | Regla de ArchUnit + señuelo calculado (tarea 2.3) | 273 |
| `f41df00` | Corrección de cobertura de rama (hallazgo de cierre) | 41 |

**El commit `250c718`, por sí solo, ya supera las 800 líneas.** Ningún corte en los límites de commit
existentes deja todas las mitades por debajo del presupuesto: agrupar `bb857c5+9d35d32+12897c7`
(353) y `033df40+f41df00` (314) sí calza, pero `250c718` (1 114) necesita partirse él mismo por
concepto, no solo reagruparse.

### Puntos de corte candidatos dentro de `250c718`, medidos por archivo

| Grupo candidato | Contenido | Líneas |
|---|---|---|
| A — objetos de valor de dominio | `PlainPassword`, `StoredPasswordHash`, `IdentifierFingerprint` + sus pruebas | 342 |
| B — mecanismo Argon2id | `Argon2Profile`, `Argon2PhcCodec`, `DecodedArgon2Hash`, `Argon2Pepper` + sus pruebas (incluye el vector de RFC 9106) | 466 |
| C — adaptadores | `BouncyCastleArgon2PasswordHasher`, `HmacLoginIdentifierFingerprinter` + sus pruebas | 306 |

Los grupos A y B son mutuamente independientes (ninguno importa del otro); el grupo C depende de
ambos (el señuelo del hasher usa `PlainPassword`, y el hasher entero usa `Argon2Profile`/`Argon2Pepper`/
`Argon2PhcCodec`). Una cadena posible de cinco pull requests, cada uno por debajo de 800 líneas:

1. Tarea 2.1 + `BackoffPolicy`/`BackoffState` — 353 líneas
2. Objetos de valor de dominio (grupo A) — 342 líneas
3. Mecanismo Argon2id (grupo B) — 466 líneas
4. Adaptadores (grupo C, base 2+3) — 306 líneas
5. Regla de ArchUnit + señuelo + corrección de cobertura (base 4) — 314 líneas

**No se ejecutó esta partición**: requeriría reordenar o dividir el commit `250c718` (`cherry-pick` o
un nuevo árbol de ramas), la misma cirugía de historial que el cambio 5 tuvo que hacer para su corte
A3 y que esta lista reserva explícitamente para quien gestiona la cadena de pull requests, no para
quien aplica las tareas de TDD. Se deja la medición completa y los puntos de corte candidatos, ya
verificados en conjunto por el `./mvnw -B verify` de arriba (que cubre el código de los cinco grupos
tal como existe hoy en un único árbol de trabajo), para que quien abra los pull requests decida y
ejecute la partición.

No se empuja la rama ni se abre pull request: lo hace el orquestador, según la instrucción explícita
de esta sesión.

---

## Revisión previa a la fusión del corte C2 — un bloqueante, encontrado dos veces

### El hallazgo, corroborado de forma independiente

El orquestador y el auditor de seguridad lo encontraron **por separado**, señalando el mismo archivo y
las mismas líneas: `Argon2PhcCodec.decode` interpolaba la cadena PHC completa —con la sal y la etiqueta
de Argon2id— en tres mensajes de excepción.

Choca con el requisito que **este mismo corte escribió**, que nombra las dos cosas de forma explícita:
«El sistema NO DEBE hacer observable, en ningún **mensaje de excepción** … **el hash Argon2id
resultante**».

**Por qué ocurre justo ahí.** Ese códec es el único punto donde un hash almacenado existe como
`String` desnudo, fuera de `StoredPasswordHash` y su `toString()` redactado, de modo que es el único
punto donde esa protección se puede evadir por accidente y no por decisión.

**Por qué es alcanzable hoy**, y este es el análisis del auditor: el constructor de
`StoredPasswordHash` solo exige el prefijo `$argon2id$`, no la estructura PHC completa. Un valor que
pase esa comprobación superficial y falle la profunda —fila corrupta, columna truncada, error de
codificación de un cambio posterior— hace que `matches()`, que es público, lance con el hash dentro.

**Impacto, también del auditor.** Un hash Argon2id filtrado no es «solo» un hash: sin la pimienta
nadie forja una contraseña con él, pero si la pimienta se filtrara en un incidente separado, el hash
es exactamente el material para un ataque de diccionario fuera de línea. Lo que se debilita es la
defensa en profundidad que la pimienta compra.

### Por qué la prueba existente no podía cazarlo

`decodeRejectsAStringThatIsNotAWellFormedPhcHash` llamaba a `decode("not-a-hash")` —una entrada que
no contiene nada sensible—, así que un mensaje que repitiera su entrada la satisfacía. Es la misma
forma que el bloqueante del corte C1: la prueba existe y **nunca alcanza el caso peligroso**.

### La corrección, y el control negativo que demuestra que sirve

El mensaje lleva ahora la **forma** del fallo —el número de segmentos separados por `$`— y no el
valor. La prueba nueva, `decodeNeverPutsTheHashItRejectedIntoTheExceptionMessage`, pasa una entrada
**con forma de hash almacenado** y segmentos reconocibles de sal y etiqueta.

Control negativo, restaurando la interpolación:

```
decodeNeverPutsTheHashItRejectedIntoTheExceptionMessage  → FALLA
decodeRejectsAStringThatIsNotAWellFormedPhcHash         → sigue pasando
```

La segunda línea es la prueba de que la vieja nunca lo habría visto.

### Una sugerencia del auditor, resuelta en la misma pasada

`Base64` rechaza un segmento ilegible con su propia `IllegalArgumentException`, que el `catch` no
cubría. Comprobado con una prueba antes de corregir: el mensaje que escapaba era
`"Illegal base64 character 21"`. No filtra el hash, pero una segunda salida de un método cuyo
propósito aquí es tener **una sola** salida redactada es un hueco.

**Trampa que el auditor no señaló y que apareció al corregir:** la comprobación del número de
parámetros lanza la `IllegalArgumentException` propia del códec **dentro** del `try`. Ampliar el
`catch` a ese tipo sin mover esa comprobación habría hecho que el `catch` atrapara su propio lanzamiento
y lo envolviera en sí mismo. La comprobación se movió arriba del `try`, y eso es estructural, no
cosmético.

### Verificación tras las dos correcciones

`./mvnw -B verify`: **BUILD SUCCESS**, cobertura cumplida en los dos niveles, 4 min 47 s.

### Lo que el auditor verificó y está correcto

- La pimienta **llega al generador en el camino de producción**, no solo en la prueba del vector: hay
  una prueba que construye dos instancias reales con pimientas distintas y demuestra que una no
  verifica el hash de la otra.
- La huella del identificador **resiste un ataque de diccionario sobre correos**, con separación de
  dominio correcta: `HMAC(pimienta, "confia.identity.login-identifier.v1")` y después
  `HMAC(subllave, identificador)`.
- Los parámetros de Argon2id coinciden con la tabla de §4.1 y su Javadoc los declara **piso**, no
  valor calibrado.
- El señuelo se **calcula** con los parámetros vigentes y cuesta estructuralmente lo mismo que una
  verificación real.
- `SecureRandom` para la sal; ningún secreto en el repositorio; `bcprov-jdk18on` 1.86 sin transitivas,
  con las tres reglas del enforcer en verde.
- `DecodedArgon2Hash` es un `record` sin `toString()` propio **y es correcto**: sus campos sensibles
  son `byte[]`, y el `toString()` generado imprime la identidad del arreglo, nunca su contenido.

### Lo que el auditor declaró NO haber verificado

Los cuatro CVE de `bcprov-jdk18on` y que 1.86 esté por encima de todos los umbrales de arreglo: su
entorno no tiene red ni Trivy instalado. **Los verificó el orquestador** contra la base de avisos de
GitHub antes de subir la versión, y queda constancia de quién comprobó qué.

### Sugerencia trasladada al corte siguiente

`BouncyCastleArgon2PasswordHasher` ejecuta un Argon2id completo en su constructor, para el señuelo. Si
el cableado de Spring lo instanciara **por solicitud** en vez de como singleton, cada instanciación
pagaría ese costo: un vector de agotamiento de recursos barato de evitar. No es un defecto de este
código, que no controla su propio ciclo de vida. **Dueño: el corte C3a, al registrar el bean.**

---

# Corte C3a — caso de uso, con dobles, y el escritor de auditoría

Rama `change/identity-module-and-password-authentication-use-case`, base PR C2 (rama
`change/identity-module-and-password-authentication-backoff-and-password`, ya fusionada según el
estado del árbol al recibir este corte; el punto de comparación real usado para medir el diff es
`change/identity-module-and-password-authentication-decoy-and-wait-rule`, la punta exacta que la
propia sesión indicó).

**Nota de traspaso, sin resolver en este corte tampoco.** La sugerencia de arriba sigue sin dueño:
este corte **no registra ningún bean de Spring** (fuera de alcance explícito de la sesión), así que
`BouncyCastleArgon2PasswordHasher` sigue sin tener quien decida su ciclo de vida. Se traslada, otra
vez, al corte que sí cablee configuración de Spring — **C3b como muy pronto**, y probablemente el
cambio que introduzca la capa web real (`session-tokens-and-web-layer`) si C3b tampoco registra
beans.

## Tarea 3.1 — puerto y adaptador de escritura de auditoría

### ROJO observado

`JooqAuditLogWriterIT` escrito completo antes de crear ninguna clase de producción.
`./mvnw -B -pl app test -Dtest=JooqAuditLogWriterIT -Dsurefire.failIfNoSpecifiedTests=false`: **8
errores de compilación reales**, todos `cannot find symbol` sobre `AuditEntry`, `AuditLogWriter` y
`JooqAuditLogWriter`, que en ese momento no existían. `[INFO] 8 errors`, `BUILD FAILURE`. No se
fingió ningún rojo: es un fallo de compilación real, registrado tal cual.

### VERDE, con dos correcciones encontradas ejecutando, no leyendo

Se crearon `AuditEntry` (registro con las dieciséis columnas insertables, sin campo para `id`,
`occurred_at`, `prev_hash` ni `row_hash`, que es justo lo que hace estructuralmente imposible que el
adaptador los fije) y `AuditLogWriter` en `com.confia.shared.audit`, y `JooqAuditLogWriter` en
`com.confia.shared.infrastructure`, construido con `.set()` explícito por columna (`design.md` §14,
punto 5).

**Corrección 1, encontrada al ejecutar, no al leer.** La primera ejecución falló con
`PSQLException: column "source_ip" is of type inet but expression is of type character varying`.
Leyendo el código generado (`target/generated-sources/.../SharedAuditLog.java`) se confirma que jOOQ
de código abierto no tiene mapeo nativo para `inet`: el propio `pom.xml` del módulo ya fuerza esa
columna a `VARCHAR` (comentario propio: «jOOQ 3.21 has no built-in mapping for PostgreSQL's inet»,
decisión que viene del cambio 5B, sonda S9). Ningún escritor anterior había topado con esto porque
este cambio es **el primero que escribe** en `shared_audit_log`. Se resolvió con una plantilla de
jOOQ parametrizada — `DSL.field("cast({0} as inet)", String.class, DSL.val(sourceIp))` — nunca
concatenación de cadenas (`CLAUDE.md`, regla 12): el único valor variable es el parámetro enlazado, y
en todo este cambio `sourceIp` es siempre `null`.

**Corrección 2, también encontrada al ejecutar.** La primera versión de la prueba leía las filas
escritas con un `dsl.selectFrom(...)` fuera de cualquier transacción con contexto fijado, y el
resultado fue `Expected size: 2 but was: 0`: la seguridad de fila de `shared_audit_log` (V2) oculta
todo sin `app.institution_id` fijado, exactamente el mismo patrón que `AuditChainTriggerIT` ya
documentaba. Se corrigió leyendo también dentro de `transactionRunner().execute(...)`, con el mismo
contexto de seguridad.

`./mvnw -B -pl app test -Dtest=JooqAuditLogWriterIT`: **1 prueba, 0 fallos**, verde. La prueba
inserta dos filas en dos transacciones separadas y confirma que la primera encadena contra el génesis
(32 bytes en cero) y la segunda contra el `row_hash` de la primera — el disparador de `V3`
encadenando de verdad, no un valor fijado a mano. Commit `92bdce2`.

### Hallazgo de la revisión de regresión, no de la prueba de la propia tarea

Al correr después la regresión más amplia de `com.confia.identity.**` y `com.confia.architecture.**`
(ver más abajo, ya con la tarea 3.2 completa), `NoUnapprovedPlainSqlTest` rechazó
`JooqAuditLogWriter` por llamar a `DSL.field(String, ...)`, un punto de entrada de SQL plano fuera de
la lista aprobada (`ADR-0015` regla 9), que hasta este corte era un conjunto vacío e inmutable. **Es
un defecto real de la tarea 3.1**, no descubierto porque la ejecución acotada de esa tarea
(`-Dtest=JooqAuditLogWriterIT`) nunca ejercita esa regla de ArchUnit. Se corrigió añadiendo
`com.confia.shared.infrastructure.JooqAuditLogWriter` a la lista aprobada, con la justificación que
la propia regla exige por escrito: una plantilla con un solo parámetro enlazado, nunca concatenación,
sobre una columna que este cambio entero escribe siempre en `null`. Commit `854ff0b` (`fix`).
Verificado de nuevo: `./mvnw -B -pl app test -Dtest="com.confia.identity.**,com.confia.architecture.**"`:
**161 pruebas, 0 fallos.**

## Tarea 3.2 — el caso de uso `AuthenticateWithPassword`

### Discrepancia encontrada y resuelta: la forma exacta de `PasswordHasher` no basta con dos métodos

`design.md` §6.1 muestra `PasswordHasher` con solo `matches(...)` y `hash(...)`. Pero el propio
Javadoc de `BouncyCastleArgon2PasswordHasher.decoyHash()` —escrito en el corte C2— dice literalmente
que «su uso de producción... es cableado de capa de aplicación que llega en PR C3a». La verificación
de uniformidad de coste contra una cuenta inexistente (`specs/identity/spec.md`, «Verificación
Argon2id contra un hash señuelo...») necesita el **mismo** valor señuelo por instancia que ese
adaptador ya construye una sola vez, en su constructor, bajo su perfil y su pimienta vigentes; el
caso de uso no puede inventarlo ni recalcularlo sin arruinar exactamente la propiedad que el señuelo
compra. Se añadió `StoredPasswordHash decoyHash()` a `PasswordHasher`, y `BouncyCastleArgon2PasswordHasher`
pasó a `implements PasswordHasher`, con su método `decoyHash()` ampliado de alcance de paquete a
público con `@Override`. `HmacLoginIdentifierFingerprinter` pasó a `implements
LoginIdentifierFingerprinter` de la misma forma. Se documenta aquí como una precisión sobre el
esbozo literal de §6.1, no como una desviación en silencio: la propia base de código de C2 ya la
anticipaba con esas palabras exactas.

### Discrepancia encontrada y resuelta: `AuthenticateWithPassword` no puede depender de
`TransactionRunner` de forma que la tarea siga siendo «unitaria, con dobles, sin PostgreSQL»

El diagrama de flujo de `design.md` §4 dibuja `TransactionRunner.execute(...)` dentro de la propia
caja de `AuthenticateWithPassword`, y el precedente literal del árbol (`IdempotentExecutor`, en
`shared.security`) confirma que un caso de uso que abre su propia transacción **solo se prueba con
`*IT.java` reales**, nunca con una prueba unitaria — `TransactionRunner` es una clase `final` que
necesita un `PlatformTransactionManager` y un `DataSource` de verdad para ejecutar
`applySecurityContext`. La tarea, sin embargo, pide explícitamente una prueba unitaria con dobles de
los cinco puertos, sin PostgreSQL. Se resolvió aislando el cuerpo completo de la decisión 9 —los
siete pasos dentro de la caja de `TransactionRunner.execute(...)`— en un método de alcance de paquete,
`runWithinTransaction(...)`, que `AuthenticateWithPasswordTest` invoca directamente, sin pasar nunca
por `execute()` ni por una transacción real. `execute()` sigue siendo el único punto de producción
que llama a `runWithinTransaction(...)`, y solo lo hace desde dentro de la transacción que
`TransactionRunner` ya abrió. La única prueba que sí pasa por `execute()`
(`aMismatchedInstitutionFailsLoudlyAndNeverReturnsRejected`) construye un `TransactionRunner` real
con un `PlatformTransactionManager` y un `DataSource` de relleno que lanzan
`UnsupportedOperationException` en cada método: la guarda de cierre falla **antes** de que
`transactionRunner.execute(...)` se invoque, así que esos dobles nunca se ejercitan de verdad, y si
algún día lo fueran, la prueba fallaría de forma ruidosa en vez de dar un verde falso. Se documenta
aquí porque es una decisión estructural real sobre `AuthenticateWithPassword`, no un detalle de la
prueba.

### La etiqueta del actor para cuenta inexistente: ya resuelta por el diseño, no una decisión de este
agente

El encargo pedía resolverla «sin filtrar el correo... y si el diseño no la fija, decirlo y proponer».
**El diseño ya la fija**, con todas las letras, en la decisión 8: «la constante `unknown-account`
cuando no [existe la cuenta]». No es una brecha abierta: es una lectura completa de `design.md` que
el encargo no había citado. Se siguió literalmente, sin inventar ni reabrir la decisión.

### ROJO observado, con la disciplina exacta que la sesión pidió

`AuthenticateWithPasswordTest.java` y `AuthenticationResultTest.java` se escribieron completos antes
de crear ninguna de las ocho clases de producción de `identity.application`. Para observar un ROJO
honesto sin haber escrito antes, por error de secuencia, el código de producción correspondiente
(un desliz de este agente, corregido antes de reportar nada), se apartaron temporalmente con
`git stash push -u` los ocho archivos nuevos de `identity.application` y las dos clases de
`identity.infrastructure` ya modificadas (`BouncyCastleArgon2PasswordHasher`,
`HmacLoginIdentifierFingerprinter`), dejando solo las pruebas nuevas y `StaffAccount`/`StaffAccountTest`
ya actualizados. `./mvnw -B -pl app test -Dtest=AuthenticateWithPasswordTest,AuthenticationResultTest`:
fallo real de compilación, con estos símbolos exactos, entre otros:

```
[ERROR] .../AuthenticateWithPasswordTest.java:[195,13] cannot find symbol
  symbol:   class AuthenticateWithPassword
[ERROR] .../AuthenticateWithPasswordTest.java:[316,70] cannot find symbol
  symbol:   class StaffAccountRepository
[ERROR] .../AuthenticateWithPasswordTest.java:[333,65] cannot find symbol
  symbol:   class LoginBackoffStore
[ERROR] .../AuthenticateWithPasswordTest.java:[355,62] cannot find symbol
  symbol:   class PasswordHasher
[ERROR] .../AuthenticateWithPasswordTest.java:[81,9] cannot find symbol
  symbol:   class AuthenticationDecision
[ERROR] .../AuthenticateWithPasswordTest.java:[82,21] cannot find symbol
  symbol:   class AuthenticationCommand
```

Confirma exactamente lo que la tarea predice: «Fallan porque `AuthenticateWithPassword`,
`AuthenticationCommand`, `AuthenticationDecision` y los cinco puertos no existen». (El registro
también mostró errores de compilación en archivos ajenos a este corte —`IdempotencyExitCriterionIT`,
`TransactionRunnerContextIT`, sobre constantes de tablas generadas—, causados por que `generate-sources`
no se había vuelto a ejecutar tras el `stash`; desaparecieron solos al restaurar el árbol, y no son
parte del rojo real de esta tarea.) `git stash pop` restauró los ocho archivos exactamente como
estaban.

### VERDE

Se crearon `AuthenticationCommand`, `AuthenticationDecision` (con el Javadoc del contrato de seis
puntos), los cinco puertos, y `AuthenticateWithPassword` con su guarda de cierre (falla con
`IllegalStateException`, nunca `Rejected`, si la institución del contexto no coincide con la del
proveedor). `StaffAccount` ganó el campo `passwordHash` que su propio Javadoc de C1 dejaba pendiente
para este corte; `StaffAccountTest` se actualizó a los cuatro componentes.
`./mvnw -B -pl app test -Dtest=AuthenticateWithPasswordTest,AuthenticationResultTest,StaffAccountTest,BouncyCastleArgon2PasswordHasherDecoyTest`:
**19 pruebas, 0 fallos**. Commit `4b4aabd`.

Siete pruebas en `AuthenticateWithPasswordTest`: contraseña correcta con retroceso vigente produce
`Authenticated` con `requiredDelay = PT2S` (el ejemplo exacto de `design.md` §4, tres fallos a las
12:00:00, intento correcto a las 12:00:10) y sin `hash(...)` invocado; contraseña incorrecta produce
`Rejected(INVALID_PASSWORD)`; cuenta inexistente produce `Rejected(ACCOUNT_NOT_FOUND)`; los dos
motivos son distintos entre sí; el señuelo se verifica exactamente una vez, con el hash señuelo del
doble, cuando la cuenta no existe; el hash almacenado nunca se recalcula en el camino de éxito; y la
guarda de cierre falla con `IllegalStateException` ante una institución no coincidente, sin tocar
nunca el `TransactionRunner` de relleno. `AuthenticationResultTest`: cuatro pruebas de reflexión,
confirmando que `Authenticated` declara exactamente `userId` e `institutionId`, que `Rejected`
declara exactamente `reason`, y que ninguno de los dos declara un campo cuyo nombre contenga
`scope`, `permission`, `role`, `claim`, `grant` ni `authorit`.

## Hallazgo de la regresión más amplia (no de ninguna tarea individual)

`./mvnw -B -pl app test -Dtest="com.confia.identity.**,com.confia.architecture.**"` — ejecutada por
disciplina propia, no porque la tarea 3.2 la pidiera explícitamente — encontró el hallazgo de
`NoUnapprovedPlainSqlTest` ya descrito arriba, en código de la tarea 3.1. Tras corregirlo:
**161 pruebas, 0 fallos.**

## Verificación final de este corte

`./mvnw -B verify` desde `apps/api`, `JAVA_HOME` en JDK 25, `MAVEN_OPTS` con el almacén
`Windows-ROOT`, Docker activo, tras borrar a mano `app/target/{site,classes,test-classes}` y los
`jacoco-{ut,it}.exec` (el bloqueo de OneDrive sobre `clean` ya documentado en cortes anteriores):

```
[INFO] All coverage checks have been met.
[INFO] CONFIA Kernel ...................................... SUCCESS [ 12.482 s]
[INFO] CONFIA API ......................................... SUCCESS [03:54 min]
[INFO] BUILD SUCCESS
[INFO] Total time:  04:08 min
```

**177** pruebas unitarias de `kernel`, **256** de `app` (unitarias, frente a las 242 de C2: +14 de
este corte), **95** de `app` (`*IT.java`, frente a las 94 de C2: +1, `JooqAuditLogWriterIT`), **0
fallos** en las tres. La suite `*IT.java` sumó **≈165 s** de tiempo propio dentro de los 3:54 min del
módulo `app`, muy por debajo del presupuesto de 8 minutos de `design.md` §13.

## Diff real de PR C3a, y por qué este corte se detiene aquí sin abrir pull request

`git diff --numstat change/identity-module-and-password-authentication-decoy-and-wait-rule...HEAD --
. ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'` (esa rama es la punta exacta
de PR C2 que la propia sesión señaló como base de comparación):

**1 155 líneas de cambio efectivo (1 122 adiciones, 33 borrados).**

Supera el presupuesto de 800 líneas por pull request que `tasks.md` fija para este repositorio.
Siguiendo la regla operativa de la tarea 3.2 («si supera 800, detener y reportar los puntos de corte
candidatos medidos, verificando cada mitad con `./mvnw -B verify` antes de proponerla»): **se detiene
aquí la aplicación, sin empujar la rama ni abrir pull request**, exactamente como ya se hizo al cerrar
PR C2.

### Medición por archivo, agrupada en los tres puntos de corte candidatos

| Grupo candidato | Contenido | Líneas |
|---|---|---|
| **A — escritor de auditoría (tarea 3.1 completa)** | `AuditEntry`, `AuditLogWriter`, `JooqAuditLogWriter`, `JooqAuditLogWriterIT`, la aprobación de `NoUnapprovedPlainSqlTest` | **247** |
| **B — los cinco puertos y su cableado de infraestructura** | `AuthenticationCommand`, `AuthenticationDecision`, `StaffAccountRepository`, `LoginBackoffStore`, `PasswordHasher`, `LoginIdentifierFingerprinter`, `LoginInstitutionProvider`, `StaffAccount` (+campo), `BouncyCastleArgon2PasswordHasher` y `HmacLoginIdentifierFingerprinter` (+`implements`) | **224** |
| **C — el caso de uso y su prueba** | `AuthenticateWithPassword`, `AuthenticateWithPasswordTest`, `AuthenticationResultTest`, `StaffAccountTest` (+campo) | **684** |

**247 + 224 + 684 = 1 155.** Las tres mitades quedan por debajo de 800 líneas cada una — a diferencia
de PR C2, donde ninguna partición de commits existente lograba bajar las tres mitades del umbral—.
Los grupos B y C son los que exigirían cirugía de historial: hoy viven en un único commit
(`4b4aabd`), y separarlos en dos requeriría `cherry-pick` o un árbol de ramas nuevo — la misma
cirugía que esta lista reserva explícitamente para quien gestiona la cadena de pull requests, no para
quien aplica las tareas de TDD (precedente idéntico al de PR C2, sección de arriba). El grupo A ya
vive en sus propios dos commits (`92bdce2`, `854ff0b`) y podría separarse sin tocar el historial, si
así se decide.

**No se ejecutó ninguna partición**: se deja la medición completa, ya verificada en conjunto por el
`./mvnw -B verify` de arriba (que cubre el código de los tres grupos tal como existe hoy en un único
árbol de trabajo), para que quien abra los pull requests decida y ejecute la partición exacta.

### Commits de este corte, en orden

1. `92bdce2` — `feat(shared): add audit log writer port and jOOQ adapter` (tarea 3.1, grupo A)
2. `4b4aabd` — `feat(identity): add AuthenticateWithPassword use case and its five ports` (tarea 3.2,
   grupos B y C juntos)
3. `854ff0b` — `fix(identity): approve JooqAuditLogWriter's single inet cast template` (hallazgo de la
   regresión, sobre código del grupo A)

No se empuja la rama ni se abre pull request: como en los cortes anteriores, es decisión de quien
gestiona la cadena.

---

# Corte C3b — adaptadores reales, atomicidad y cierre

Rama `change/identity-module-and-password-authentication-adapters`, base el corte C3a (#45).

## Sondas S4 y S3, ejecutadas por el orquestador antes de delegar

**Sin evidencia de ROJO propia:** son sondas de comportamiento de herramienta y de motor, no pruebas
del delta.

### S4 — ¿jOOQ genera `IdentityStaffAccount` e `IdentityLoginBackoff`?

**PASA.** Los nombres generados bajo `target/generated-sources`, junto a los de las tablas anteriores:

```
IdentityLoginBackoff.java        IdentityStaffAccount.java
IdentityLoginBackoffRecord.java  IdentityStaffAccountRecord.java
OrganizationInstitution.java     SharedAuditChainHead.java
SharedAuditLog.java              SharedIdempotencyKey.java
```

Los dos empiezan por `Identity`, como el diseño esperaba, así que los adaptadores pueden nombrarlos
sin sorpresas.

### S3 — ¿el reclamo del retroceso devuelve los valores previos y toma el bloqueo de fila?

**PASA en sus dos mitades.** Ejecutada contra un `postgres:18-alpine` desechable, fuera del árbol del
repositorio, con una tabla mínima equivalente a `identity_login_backoff` y una fila sembrada con
`consecutive_failures = 7` y `last_attempt_at = 2026-01-01`.

**Mitad 1, ¿devuelve los valores previos?** La sentencia

```sql
insert into backoff (institution_id, identifier_hash, consecutive_failures, last_attempt_at)
values (..., 1, now())
on conflict (institution_id, identifier_hash) do update
  set consecutive_failures = backoff.consecutive_failures
returning consecutive_failures, last_attempt_at;
```

devolvió `7 | 2026-01-01 00:00:00+00`, es decir **los valores previos y no los que el `insert`
proponía**. Eso es lo que permite leer el estado del retroceso y reclamar la fila en **una sola ida y
vuelta**, sin un `SELECT ... FOR UPDATE` previo.

**Mitad 2, ¿toma bloqueo de fila?** Sesión 1 ejecutó el reclamo dentro de una transacción abierta y se
quedó en `pg_sleep`. Sesión 2 ejecutó el mismo reclamo con `lock_timeout = 2500ms`:

```
ERROR:  canceling statement due to lock timeout
CONTEXT:  while inserting index tuple (0,3) in relation "backoff"
```

Es `SQLState 55P03`, y el contexto revela el mecanismo exacto: el bloqueo ocurre **al insertar la
tupla del índice único**, que es como `ON CONFLICT` detecta el conflicto. **Dos reclamos concurrentes
sobre la misma fila quedan serializados**, que es lo que la prueba de concurrencia del delta necesita
demostrar y lo que impide perder un fallo de autenticación.

**Consecuencia para la tarea 4.1:** el respaldo que el diseño preveía —un `INSERT ... ON CONFLICT DO
NOTHING` seguido de `SELECT ... FOR UPDATE`, a costa de una sentencia más— **no hace falta**. La
sentencia única basta.

## Tarea 4.1 (parte 1) — los tres adaptadores jOOQ/configuración, con su ROJO/VERDE

Commiteados pieza por pieza, en verde, siguiendo la instrucción explícita de esta sesión tras un
atasco del agente anterior (van ocho caídas en la sesión completa; nada sobrevive salvo lo
commiteado).

### `JooqStaffAccountRepository` — commit `ee207e4`

`findBy(...)` sobre `IDENTITY_STAFF_ACCOUNT`, filtrando por `institution_id` y `email` normalizado.
La seguridad de fila (decisión 4) es lo que de verdad acota el resultado a una institución; el
predicado explícito de la consulta es una capa sobre esa política, no un sustituto. `./mvnw -B -pl
app test -Dtest=JooqStaffAccountRepositoryIT`: **3 pruebas, 0 fallos** (cuenta propia encontrada;
cero filas para la cuenta de otra institución, por la política de fila, no por el predicado; cero
filas para un identificador no registrado).

### `JooqLoginBackoffStore` — commit `e0f4673`

`claim(...)` es exactamente la sentencia única de la decisión 5 —`INSERT ... ON CONFLICT
(institution_id, identifier_hash) DO UPDATE SET consecutive_failures =
identity_login_backoff.consecutive_failures RETURNING consecutive_failures, last_attempt_at`—, sin
el respaldo de `SELECT ... FOR UPDATE` que S3 ya descartó. `save(...)` es la actualización simple de
cierre. `./mvnw -B -pl app test -Dtest=JooqLoginBackoffStoreIT`: **3 pruebas, 0 fallos**,
confirmando en código lo que S3 ya había confirmado por sonda: el reclamo crea la fila cuando no
existe, y devuelve el estado **previo**, nunca los valores que la propia sentencia propone.

**Corrección honesta, hecha después de commitear ambas piezas.** La primera ejecución de
`JooqStaffAccountRepositoryIT` y `JooqLoginBackoffStoreIT` se hizo ya con las dos clases de
producción en el árbol (verde directo), sin capturar su ROJO real por separado — un hueco de
disciplina frente a lo que esta misma lista exige. Se corrigió apartando `mv` de
`JooqStaffAccountRepository.java` y `JooqLoginBackoffStore.java` a un directorio fuera del
repositorio y ejecutando `./mvnw -B -pl app test
-Dtest=JooqStaffAccountRepositoryIT,JooqLoginBackoffStoreIT`: **fallo real de compilación**, cuatro
errores `cannot find symbol` sobre las dos clases, `BUILD FAILURE`. Se restauraron ambos archivos
con `mv` y `git status --short` confirmó el árbol sin diferencias (ya estaban commiteados
exactamente así). El verde ya registrado arriba sigue siendo válido sin re-ejecutar: ningún archivo
cambió entre una ejecución y la otra.

### `ConfiguredLoginInstitutionProvider` — commit `6796489`

Lee `confia.identity.login-institution-id` de una propiedad de sistema de la JVM (precedente:
`com.confia.bootstrap.AppProfile` lee `APP_PROFILE` de una variable de entorno; aquí es una
propiedad de sistema, no una variable de entorno, porque design.md decisión 11 fija la clave en
forma de propiedad con puntos y guiones, no en mayúsculas con guion bajo). Falla en el constructor,
nunca en el primer uso, si el valor falta, está en blanco o no es un UUID válido.

**ROJO real, observado apartando la clase de producción, no fingido.** Se movió
`ConfiguredLoginInstitutionProvider.java` fuera del árbol de compilación (`mv` a un directorio
temporal fuera del repositorio) y se ejecutó `./mvnw -B -pl app test
-Dtest=ConfiguredLoginInstitutionProviderTest`: **fallo real de compilación**, ocho errores `cannot
find symbol` sobre la clase y la variable `ConfiguredLoginInstitutionProvider`, `BUILD FAILURE`. Se
restauró el archivo y se repitió: `./mvnw -B -pl app test
-Dtest=ConfiguredLoginInstitutionProviderTest`: **6 pruebas, 0 fallos** (resuelve el UUID
configurado, tolera espacios en los extremos, falla ante valor ausente/en blanco/no UUID, y lee de
la propiedad de sistema real a través del constructor sin argumentos).

### Estado

Faltan, de la tarea 4.1: `AuthenticateWithPasswordIT` (el caso de uso completo contra PostgreSQL
real, con los siete escenarios de retardo). Las tareas 4.2, 4.3 y 4.4 siguen sin empezar.

## Tarea 4.1 (parte 2) — `AuthenticateWithPasswordIT`, cierre de la tarea 4.1

Commit `0917494`. Seis pruebas, cada una construye su propio `AuthenticateWithPassword` con un
`Clock.fixed` distinto por intento (nunca reutiliza el mismo reloj entre intentos consecutivos),
siempre a través de `execute(...)` — nunca de `runWithinTransaction` directamente, que es lo que
distingue esta prueba de la unitaria con dobles de C3a: aquí sí se cruza `TransactionRunner.execute`
de verdad, con Argon2id real (perfil piso, pimienta de prueba de 32 bytes en cero, declarada no
secreta) y los tres adaptadores jOOQ/configuración de esta misma tarea.

**ROJO real, observado apartando los dos adaptadores de repositorio y retroceso** (el mismo `mv` que
ya sirvió para las dos piezas anteriores, aplicado ahora contra este archivo): `./mvnw -B -pl app
test -Dtest=AuthenticateWithPasswordIT`: **fallo real de compilación**, `cannot find symbol` sobre
`JooqStaffAccountRepository` y `JooqLoginBackoffStore`, `BUILD FAILURE`. Se restauraron ambos
archivos.

**VERDE, con un fallo real encontrado ejecutando, no una prueba mal escrita desde el principio.** La
primera ejecución completa dio **5 pruebas verdes y 1 fallo real**:

```
Expecting actual:
  "{"delaySeconds": 1, "consecutiveFailures": 3}"
to contain:
  ""delaySeconds":1"
```

No es un defecto de `AuthenticateWithPassword` (que escribe exactamente
`{"consecutiveFailures":3,"delaySeconds":1}`, sin espacios y en ese orden): es que la columna
`after_value` es `jsonb`, y PostgreSQL **reserializa** el texto a su propio orden de claves y
espaciado al leerlo de vuelta — el texto que el adaptador escribe y el que la lectura devuelve no
son la misma cadena, aunque representen el mismo documento. La aserción original comparaba una
subcadena literal, frágil ante exactamente esa reserialización. Se corrigió analizando el JSON con
`tools.jackson.databind.json.JsonMapper` (ya presente en el árbol,
`IdempotentExecutorConcurrencyIT`) y afirmando sobre los campos estructurados
(`delaySeconds`, `consecutiveFailures`), nunca sobre el texto exacto. `./mvnw -B -pl app test
-Dtest=AuthenticateWithPasswordIT`: **6 pruebas, 0 fallos**, 38.71 s.

Lo que las seis pruebas demuestran, con PostgreSQL real y sin dormir nunca:

1. El retardo se activa en el tercer fallo consecutivo (1 s), con exactamente 4 asientos de
   auditoría (3 `login.failed` + 1 `backoff_applied`), y el ciclo lleva su duración en `after_value`.
2. La progresión se limita a 900 s (nunca a los 1024 s que la fórmula sin tope daría en el fallo 13).
3. El contador expira a los 30 minutos: un fallo a los 31 minutos es ordinal 1, sin retardo.
4. Un inicio de sesión exitoso limpia el contador: el fallo inmediatamente posterior es ordinal 1.
5. El retardo y el número de asientos de auditoría son **idénticos** entre una cuenta existente y un
   identificador inexistente en su tercer fallo (1 s, 4 asientos cada una), y el motivo interno
   difiere entre sí (`INVALID_PASSWORD` frente a `ACCOUNT_NOT_FOUND`) sin que eso afecte ni el
   retardo ni el conteo — la propiedad estructural de la decisión 3.
6. Una contraseña correcta durante el retroceso (contador en tres) autentica con éxito tras un
   retardo de 2 s —el ejemplo exacto de `design.md` §4— y limpia el contador.

### Tarea 4.1: COMPLETA

Los tres adaptadores, sus tres pruebas de adaptador y `AuthenticateWithPasswordIT` están commiteados
y verificados en verde por separado. Sigue faltando: 4.2 (atomicidad y concurrencia), 4.3
(institución, redacción, inventarios de exclusión) y 4.4 (medición de tiempo, notas editoriales,
cierre).

## Tarea 4.2 — atomicidad y concurrencia del retroceso

Commit `d8377fa`. Dos clases nuevas, ninguna con cambio de producción — exactamente lo que la propia
tarea 4.2 anticipa («debería pasar sin cambios de producción, dado que 4.1 ya la entrega»).

### `LoginBackoffAtomicityIT`

Invoca `AuthenticateWithPassword.runWithinTransaction(...)` directamente (visible desde el mismo
paquete `com.confia.identity.application`, el mismo acceso que ya usa `AuthenticateWithPasswordTest`
de C3a) dentro de `transactionRunner().execute(...)`, y lanza una `IllegalStateException`
determinista **después** de que `runWithinTransaction` devuelve pero **antes** de que la lambda
termine — de modo que la transacción entera revierte, incluidos los efectos que
`runWithinTransaction` ya escribió dentro de ella. Dos pruebas:

1. **La reversión determinista no deja nada**: cero filas en `identity_login_backoff` (ni siquiera
   la fila `(0, now)` que el propio reclamo crea) y cero filas en `shared_audit_log` para la huella
   de ese identificador.
2. **El control positivo**, siguiendo la misma disciplina que el control negativo de
   `IdentityRowSecurityIT`: sin lanzar nada, el mismo intento sí confirma exactamente una fila de
   retroceso y un asiento de auditoría. Sin este control, la prueba 1 pasaría igual si **toda**
   transacción de este entorno revirtiera siempre, por la razón equivocada.

**Nota honesta sobre la disciplina de ROJO en esta tarea.** No se capturó un ROJO propio para estas
dos pruebas: la propia tarea 4.2 anticipa que deben pasar **sin cambio de producción**, porque la
frontera transaccional que demuestran ya la entregó la tarea 4.1 (el cuerpo completo de
`runWithinTransaction` dentro de una única invocación de `TransactionRunner.execute`). Un ROJO aquí
solo aparecería si esa frontera estuviera incompleta — que es exactamente lo que estas pruebas
existen para descartar, no para provocar. `./mvnw -B -pl app test -Dtest=LoginBackoffAtomicityIT`:
**2 pruebas, 0 fallos**, 45.81 s (dos verificaciones Argon2id reales).

### `LoginBackoffConcurrencyIT`

Dos instancias independientes de `TransactionRunner` (cada una con su propio `PlatformTransactionManager`
compartido pero su propia conexión, el mismo patrón de `TransactionRunnerRetryIT` e
`IdempotentExecutorConcurrencyIT`), sincronizadas con un `CyclicBarrier` de dos partes, esperado
dentro de la lambda de `TransactionRunner.execute(...)` **justo después de abrir la transacción y
antes de invocar `runWithinTransaction`** — nunca una espera por reloj. Cuenta con un fallo previo
ya registrado (contador en uno) y dos intentos fallidos concurrentes contra la misma cuenta.

La aserción que de verdad distingue «se serializó correctamente» de «se perdió una escritura»: el
conjunto `{delayA, delayB}` debe ser exactamente `{0 s, 1 s}` —quien pierde la carrera ve el estado
previo (ordinal 2, sin retardo) y quien la gana ve el estado ya avanzado por el otro (ordinal 3, 1
segundo)—; si una escritura se hubiera perdido, ambos verían el mismo ordinal y el mismo retardo. El
contador final, leído con un tercer `claim()` de solo lectura (la sentencia es idempotente: el
`SET` de la decisión 5 nunca cambia el valor, así que un `claim()` sin `save()` posterior no
corrompe el estado), confirma **tres** fallos: el previo más los dos concurrentes, ninguno perdido.
`./mvnw -B -pl app test -Dtest=LoginBackoffConcurrencyIT`: **1 prueba, 0 fallos**, 1.496 s.

### Tarea 4.2: COMPLETA

## Tarea 4.3 — institución del proceso, ausencia de secretos observables, inventarios de exclusión

### `LoginInstitutionIT` — commit `7481ecc`

Usa el adaptador real `ConfiguredLoginInstitutionProvider` (nunca una lambda de prueba, a
diferencia de `AuthenticateWithPasswordIT`, cuyo foco es el retroceso). Tres pruebas: dos
instituciones con el mismo correo resuelven cada una su propia cuenta y nunca la ajena, ni siquiera
con la contraseña de la otra institución; el proveedor siempre resuelve el id configurado por el
proceso —y `AuthenticationCommand` no tiene ningún campo de institución en absoluto, así que «una
solicitud que declare una institución distinta en su cuerpo» es estructuralmente imposible de
construir, la forma más fuerte que design.md decisión 11 puede tomar—; y la guarda de cierre falla
con `IllegalStateException` ante un contexto ajeno, nunca `Rejected`. `./mvnw -B -pl app test
-Dtest=LoginInstitutionIT`: **3 pruebas, 0 fallos**, 41.88 s.

### `IdentitySecretRedactionIT` — commit `eac44d8`

Intento real con la contraseña literal `Segura#2026` del escenario del delta. Recoge
`AuthenticationDecision`/`AuthenticationResult` (`toString()`), el mensaje de una excepción forzada
(la guarda de cierre ante institución ajena), el `toString()` de `PlainPassword`, `StoredPasswordHash`
y `Argon2Pepper`, el hash señuelo, y las filas escritas en `shared_audit_log` — y afirma que ninguno
contiene la contraseña, el hash calculado ni la pimienta.

**Decisión de diseño de la propia prueba, siguiendo la instrucción literal de la tarea.** Un
`assertThat(textoRecogido).doesNotContain(secreto)` ingenuo, si fallara, imprimiría el mensaje de
fallo por omisión de AssertJ —«Expecting actual: `<todo el texto recogido>` not to contain:
`<el secreto>`»—, que pondría el secreto exacto que esta prueba existe para mantener fuera
**dentro del propio reporte de la prueba**. `assertSecretNeverLeaked(...)` compara con
`String.contains` y, si encuentra el secreto, falla con un mensaje que nombra solo la descripción y
la longitud del secreto, nunca su valor ni el texto donde apareció.

**Control negativo, ejecutado y revertido antes de este commit.** Se quitó temporalmente la
redacción de `PlainPassword.toString()` (`"PlainPassword[" + value + "]"` en vez de
`"PlainPassword[REDACTED]"`) y se ejecutó de nuevo: **falló**, con exactamente el mensaje seguro
esperado (`produced text unexpectedly contains the clear-text password (11 characters); the value
itself is deliberately withheld from this failure message`), sin el valor real en la salida. Se
restauró el archivo (`cp` desde una copia de respaldo) y `git status --short` confirmó cero
diferencias contra lo commiteado. `./mvnw -B -pl app test -Dtest=IdentitySecretRedactionIT`: **1
prueba, 0 fallos**, 33.06 s (con la redacción real restaurada).

### `IdentityScopeExclusionInventoryTest` — commit `5ea3703`

**Sin Docker**, corrigiendo un primer diseño que sí lo necesitaba. La primera versión invocaba
`AuthenticateWithPassword.runWithinTransaction(...)` directamente con dobles de prueba (el mismo
patrón de `AuthenticateWithPasswordTest`), pero esa clase vive en el paquete
`com.confia.identity.application` y este archivo, por instrucción explícita de la tarea, vive en
`com.confia.identity` — un paquete **distinto** en Java, así que `runWithinTransaction` (alcance de
paquete) no es accesible desde aquí. `./mvnw -B -pl app test
-Dtest=IdentityScopeExclusionInventoryTest` con ese primer diseño: **fallo real de compilación**,
`is not public in ... AuthenticateWithPassword; cannot be accessed from outside package`. Se
rediseñó la primera prueba para usar `BackoffPolicy` directamente —dominio puro, sin I/O—: diez
intentos simulados «desde la misma IP», repartidos por turno entre cinco cuentas, afirmando que el
ordinal de cada cuenta depende solo de sus propios fallos previos, nunca de los otros nueve intentos
contra las otras cuatro cuentas. Sin este rediseño no habría manera de escribir esta prueba sin
Docker y sin violar el encapsulamiento de paquete.

**Discrepancia encontrada y corregida en la propia prueba, antes de commitear.** Una primera versión
adicional comprobaba, por reflexión, que ningún parámetro de los cinco puertos de aplicación llevara
«ip» en su nombre (`Parameter.getName()`). Se descubrió que el POM **no declara la bandera del
compilador `-parameters`**, así que en tiempo de ejecución esos nombres son sintéticos (`arg0`,
`arg1`...), no los nombres reales del código fuente — la aserción habría pasado siempre,
detectara o no un parámetro real de IP, exactamente el tipo de prueba «verde por la razón
equivocada» que este repositorio ya pagó varias veces. Se eliminó esa comprobación en vez de
dejarla como ceremonia sin poder de discriminación real; el componente de registro de
`AuthenticationCommand` sí es fiable sin esa bandera (los nombres de componente de un `record` viven
en el atributo `Record` del propio `.class`, no en la reflexión de parámetros de método), y esa
comprobación se conservó.

Cinco pruebas: ausencia de dimensión IP (con la corrección de arriba), ninguna dependencia de
biblioteca cliente de red (`java.net.http`, `okhttp3`, Apache HttpComponents — ninguna verificación
contra contraseñas comprometidas), los parámetros de Argon2id son el piso declarado y no una fábrica
calibrada, ninguna prueba de Playwright existe todavía (recorriendo `apps/` de verdad, con la misma
disciplina de `findAncestorContaining` que `SuppressionCitesAdrTest` ya usa), y ninguna clase de
`com.confia.identity..` depende de `slf4j`, `java.util.logging` ni Commons Logging. `./mvnw -B -pl
app test -Dtest=IdentityScopeExclusionInventoryTest`: **5 pruebas, 0 fallos**, 10.99 s — sin
contenedor, como exige la tarea.

**Sin control negativo para las dos comprobaciones de ArchUnit** (red y registro): a diferencia de
`IdentitySecretRedactionIT`, no se introdujo temporalmente una dependencia real de `slf4j` o de un
cliente HTTP para confirmar que la regla la habría rechazado. Se declara la limitación en vez de
omitirla: las dos reglas siguen el mismo patrón ya probado en `AuditScopeExclusionInventoryTest`
(que tampoco lleva control negativo propio), pero no hay evidencia ejecutada de que rechacen una
violación real en este módulo.

### Tarea 4.3: COMPLETA

## Tarea 4.4 — medición de tiempo, notas editoriales, cierre de `docs/09`, verificación final

**Sin evidencia de ROJO propia**, como la propia tarea anticipa: son notas editoriales y una
medición, no comportamiento nuevo.

### `LoginTimingReportIT` — commit `f9a6642`

25 intentos reales contra la cuenta `carlos.ramirez@colegio.edu.hn` y 25 contra
`nadie.registrado@colegio.edu.hn`, con Argon2id real (perfil piso) y el caso de uso completo contra
PostgreSQL real. **Medido y reportado, nunca convertido en puerta** (design.md, decisión 7; §7.2):

```
LoginTimingReportIT (informational, never a gate): median existing-account attempt = 170.55 ms,
median nonexistent-identifier attempt = 176.06 ms, difference = 5.51 ms, n = 25 each
```

5.51 ms de diferencia entre medianas, muy por debajo de los 50 ms que `docs/03-seguridad.md` §4.6
cita como umbral de la puerta real —que es de `session-tokens-and-web-layer`, sobre respuestas
HTTP, no de este cambio—. `./mvnw -B -pl app test -Dtest=LoginTimingReportIT`: **1 prueba, 0
fallos**, 37.76 s.

### Notas editoriales de `docs/03-seguridad.md` — commit `3a2df58`

Las tres, fechadas 2026-09-24, con la redacción exacta de `design.md` §15, sin reescribir el cuerpo
de ninguna sección:

- **Ajuste 3, en §4.4**: el estado del retroceso por cuenta vive en PostgreSQL, no en Redis; el
  retardo se calcula y se exige dentro de la transacción y se materializa en el borde; no es un
  limitador de tasa.
- **Ajuste 4, en §6.1**: `identity_staff_account` es la tabla que esa sección llama `user`;
  `identity_login_backoff` recibe el mismo trato, sin privilegio para `confia_portal_app`.
- **Ajuste 5, en §4.1, autorizado por el propietario el 2026-09-24**: Argon2id sin Spring Security,
  sobre Bouncy Castle directo, porque la cadena de filtros es de `session-tokens-and-web-layer`; la
  pimienta de 32 bytes aplicada como `secret` sí se cumple.

### `docs/09-roadmap-y-fases.md` — commit `429348d`

Nota en el entregable 3 (F0), nombrando las tres mitades diferidas de este cambio:
`mfa-totp-and-password-recovery` (MFA, recuperación de contraseña, cifrado de columna),
`session-tokens-and-web-layer` (sesiones, capa web, quien materializa `requiredDelay`, la cabecera
`Idempotency-Key` sobre este endpoint) y el cambio 11 (la dimensión por IP del retroceso, sobre
Redis).

### `apps/api/README.md` — commit `c4470b6`

Medición final de la suite `*IT.java` completa del cambio, registrada abajo y en el README con el
mismo formato que las mediciones anteriores.

### Verificación final del cambio completo

Limpieza manual de `app/target/{site,classes,test-classes}` y de los dos `jacoco-{ut,it}.exec`
(bloqueo de OneDrive sobre `clean`, ya documentado), y `./mvnw -B verify` sin `clean`:

```
[INFO] All coverage checks have been met.
[INFO] CONFIA API Parent .................................. SUCCESS [  2.481 s]
[INFO] CONFIA Kernel ...................................... SUCCESS [ 15.144 s]
[INFO] CONFIA API ......................................... SUCCESS [04:06 min]
[INFO] BUILD SUCCESS
[INFO] Total time:  04:25 min
```

**177** pruebas unitarias de `kernel`, **267** pruebas unitarias de `app` (frente a las 256 de PR
C3a: +11, exactamente `ConfiguredLoginInstitutionProviderTest` (6) e
`IdentityScopeExclusionInventoryTest` (5), ninguna de las cuales toca PostgreSQL), **115** pruebas de
integración de `app` (`*IT.java`, frente a las 95 de PR C3a: +20, exactamente la suma de cada clase
`*IT` nueva de este corte: 3+3+6+2+1+3+1+1), **0 fallos** en las tres. Muy por debajo del presupuesto
de 8 minutos (4:25 min, bajo el 55 %), incluso siendo el primer corte cuya suite de integración paga
decenas de verificaciones Argon2id reales por ejecución.

### Trazabilidad de los 23 escenarios de `identity` y los 8 de `build-integrity`

Confirmados según esta lista (no según la tabla envejecida de `design.md` §7.1, ya corregida por el
orquestador el 2026-09-24 a 31 escenarios): cada tarea de este corte (4.1 a 4.4) cita en su propio
texto los requisitos y escenarios exactos que cierra, y las pruebas de este corte —`AuthenticateWithPasswordIT`,
`LoginBackoffAtomicityIT`, `LoginBackoffConcurrencyIT`, `LoginInstitutionIT`,
`IdentitySecretRedactionIT`, `IdentityScopeExclusionInventoryTest` y `LoginTimingReportIT`— cubren
los escenarios de `identity` que dependían de PostgreSQL real y del caso de uso completo, que las
pruebas unitarias con dobles de PR C3a no podían demostrar. Los 8 de `build-integrity` ya quedaron
cerrados en PR C1 (design.md §7.1, tabla ya corregida).

### Diff real de PR C3b, y los puntos de corte candidatos medidos y verificados

`git diff --numstat change/identity-module-and-password-authentication-use-case...HEAD -- .
':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`:

**1 692 líneas de cambio efectivo (1 692 adiciones, 0 borrados), en 16 archivos.**

Supera el presupuesto de 800 líneas por pull request que la propia estrategia de entrega de
`tasks.md` fija para este repositorio. Siguiendo la regla operativa ya aplicada en PR C1, PR C2 y PR
C3a («si supera 800 líneas, detener la aplicación y reportar los puntos de corte candidatos medidos,
verificando cada mitad con `./mvnw -B verify` antes de proponerla»): **se detiene aquí la aplicación,
sin empujar la rama ni abrir pull request.**

**A diferencia de PR C2 y PR C3a, esta vez los puntos de corte candidatos coinciden exactamente con
límites de commit ya existentes: no hace falta ninguna cirugía de historial (`cherry-pick` ni árbol
de ramas nuevo).** Los commits de este corte ya siguen, sin planearlo por eso, el límite de cada una
de las cuatro tareas:

| Grupo candidato | Tarea | Commits (rango) | Contenido | Líneas | `./mvnw -B verify` aislado |
|---|---|---|---|---|---|
| **C3b-1** | 4.1 | `ee207e4`..`0917494` | Los tres adaptadores jOOQ/configuración, sus tres pruebas de adaptador, y `AuthenticateWithPasswordIT` | **717** | **Verde**, verificado dejando la rama en `0917494`: `BUILD SUCCESS`, 3:31 min, cobertura cumplida |
| **C3b-2** | 4.2 + 4.3 | `d8377fa`..`5ea3703` | Atomicidad, concurrencia, institución del proceso, redacción de secretos, inventarios de exclusión | **786** | **Verde**, verificado dejando la rama en `5ea3703` (acumulado sobre C3b-1): `BUILD SUCCESS`, 3:59 min, cobertura cumplida |
| **C3b-3** | 4.4 | `f9a6642`..`c4470b6` | Medición de tiempo, tres notas editoriales, `docs/09`, medición del README | **189** | Ya verificado como parte del `./mvnw -B verify` final de arriba (HEAD) |

**717 + 786 + 189 = 1 692.** Las tres mitades quedan por debajo de 800 líneas cada una. Si se
prefiere una cadena de cuatro en vez de tres (separando 4.2 de 4.3, cada una con su propio pull
request), los cuatro grupos —717, 278, 508 y 189— también quedan cada uno muy por debajo de 800; se
deja la partición en tres por defecto porque ya es la mínima que cumple el presupuesto sin fragmentar
de más, pero ambas particiones son válidas y ninguna requiere reordenar commits.

**No se empuja la rama ni se abre pull request**: como en los tres cortes anteriores de este mismo
cambio, es decisión de quien gestiona la cadena de pull requests, no de quien aplica las tareas de
TDD.

### Cambio `identity-module-and-password-authentication`: PR C3b COMPLETO

Las cuatro tareas de este corte (4.1, 4.2, 4.3, 4.4) están commiteadas, documentadas y verificadas en
verde, pieza por pieza, según la disciplina de commit-por-verde que esta sesión exigió tras el
atasco del agente anterior. El cambio completo (`identity-module-and-password-authentication`,
cambio 7 de F0) queda con sus cuatro cortes (C1, C2, C3a, C3b) aplicados; la fusión a `main` de cada
uno y la partición final de PR C3b en pull requests reales siguen siendo decisión del orquestador o
de quien gestiona la cadena.

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

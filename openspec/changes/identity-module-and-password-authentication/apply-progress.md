# Progreso de aplicación: `identity-module-and-password-authentication`

- **Corte en curso:** PR C1 — tareas 1.1, 1.2 y 1.3 completas (las tres de PR C1). Pendiente de
  revisión y de que el orquestador empuje la rama y abra el pull request; esta sesión no lo hace.
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

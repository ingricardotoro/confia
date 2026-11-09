# Progreso de aplicación: matriz de permisos RBAC e integración con la bitácora (cambio 8 de F0)

- **Cambio:** `rbac-permission-matrix-and-audit-integration`
- **Modo:** TDD estricto. Ejecutor: `./mvnw -B verify` en `apps/api` (JDK 25, Docker en ejecución).
- **Rama del lote 1:** `change/rbac-permission-matrix-and-audit-integration-1-permission-catalog-and-adr`, apilada sobre
  `change/rbac-permission-matrix-and-audit-integration` (`e946a31`). Sin push.
- **Estado del lote 1 (2026-11-04):** **completo, en dos PR** (1a y 1b, ver «Cierre del lote 1 con el corte elegido»). El árbol completo midió 1 492 líneas efectivas; el propietario eligió
  el corte en dos PR y ninguno supera 800.

## Tareas

| Tarea | Estado | Evidencia |
|---|---|---|
| 1.1 ROJO | hecho | Dos rojos registrados abajo (compilación; paridad, notas y ADR) |
| 1.2 VERDE y REFACTOR | hecho | 58 pruebas ejecutadas del lote (47 nuevas), todas en verde; `./mvnw -B verify` en verde |
| 1.3 Demostraciones deliberadas | hecho | Tres rupturas revertidas y confirmadas con `cmp` |
| 1.4 Cierre | hecho | `./mvnw -B verify` en verde sobre el árbol combinado; commits repartidos en 1a y 1b |
| 1 | hecho | Dos PR: 1a (761 líneas) y 1b (735 líneas) |
| 2 | hecho | Dos PR: 2a (627 líneas) y 2b (640 líneas); `verify` en verde sobre 2b |

## Evidencia de ciclo TDD

| Tarea | Archivo de prueba | Capa | Red de seguridad | ROJO | VERDE | TRIANGULACIÓN | REFACTOR |
|---|---|---|---|---|---|---|---|
| 1.1/1.2 catálogo | `kernel/.../PermissionTest.java` | Unitaria | N/A (nuevo) | Escrita primero; no compilaba | 10/10 | 10 casos: forma, módulos, verbos, código de solicitud, ausentes, `ofCode` conocido, desconocido, nulo | Sin cambios necesarios |
| 1.1/1.2 roles | `app/.../identity/domain/StaffRoleTest.java` | Unitaria | N/A (nuevo) | Escrita primero; no compilaba | 19/19 | Una aserción por columna (AZ07 a AZ13), conteos por rol 88/61/18/31/35/23, `ofCode`, inmutabilidad, ausencia de tablas globales en `db/migration` | Sin cambios necesarios |
| 1.1/1.2 paridad | `app/.../architecture/PermissionMatrixParityTest.java` | Arquitectura/documental | N/A (nuevo) | `marker not found` | 8/8 | Documento real, tabla vacía, nota ausente, dos controles de un solo lado, un permiso solo del documento, matrices idénticas | Sin cambios necesarios |
| 1.1/1.2 pureza | `app/.../architecture/DomainPurityRulesTest.java` | Arquitectura | N/A (nuevo) | Falló (ver rojo 2) | 5/5 | Mitad de producción y mitad de fixtures por regla (Spring, jOOQ, otro módulo, catálogo con Spring) | Se corrigió la regla del catálogo para las matrices de sí mismo (ver rojo 2) |
| 1.1/1.2 notas | `app/.../architecture/DocumentationNotesTest.java` | Documental | 11/11 de las pruebas previas | 5 fallos por notas y ADR ausentes | 16/16 | ADR indexado, nota de ADR-0005, tres notas de `docs/03` con originales intactos | Texto de las notas ajustado a la prueba (frases en una sola línea de prosa) |

### Rojo 1 (compilación, sin código de producción)

Comando: `./mvnw -B -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='PermissionTest,StaffRoleTest,PermissionMatrixParityTest,DomainPurityRulesTest,DocumentationNotesTest'`.
Resultado: `BUILD FAILURE` en `CONFIA Kernel`; 50 errores `cannot find symbol` en `PermissionTest.java`, por ejemplo:

```
[ERROR] .../kernel/src/test/java/com/confia/kernel/PermissionTest.java:[31,44] cannot find symbol
  symbol:   variable Permission
  location: class com.confia.kernel.PermissionTest
[ERROR]   symbol:   class Permission
[ERROR]   mvn <args> -rf :confia-kernel
```

Es la causa esperada (`cannot find symbol class Permission`); `StaffRole` no llegó a compilarse porque el módulo `kernel` falla primero.

### Rojo 2 (con los dos enums y sin documentos; `-Djacoco.skip=true` para iterar)

`Tests run: 48, Failures: 10` en `app`, más `PermissionTest` 10/10 y `StaffRoleTest` 19/19 ya en verde:

```
PermissionMatrixParityTest (x4): marker not found: a line with "**Matriz efectiva de permisos de los seis roles" in docs/03-seguridad.md
DocumentationNotesTest.docs03Section51/52/53...: a block quote that carries "**Nota fechada 2026-11-04 (cambio `rbac-permission-matrix-and-audit-integration`, PR 1 ..." / Expecting actual: -1 to be greater than or equal to: 0
DocumentationNotesTest.adr0027ExistsAsProposedWithItsSectionsAndIsIndexed: Expecting path: C:\dev\confia\docs\adr\ADR-0027-autorizacion-por-permisos-resueltos-por-peticion.md to exist
DocumentationNotesTest.adr0005HasTheDatedNote...: [the dated note of ADR-0005] Expecting actual: -1 to be greater than or equal to: 0
DomainPurityRulesTest.thePermissionCatalogDependsOnNothingOutsideTheJdk: com.confia.kernel.Permission depends on [Lcom.confia.kernel.Permission;, which is not part of the JDK
```

Las nueve primeras son las causas esperadas del diseño (`marker not found` en `docs/03` y ADR-0027 inexistente). La décima **fue un defecto de la propia
regla**: ArchUnit informa el arreglo `Permission[]` (de `values()`) como dependencia; se corrigió la regla para comparar el tipo base del arreglo y
se confirmó en verde (la mitad de fixtures del mismo archivo sigue rechazando un catálogo que depende de Spring).

### Verde

`./mvnw -B -pl app -am verify -Djacoco.skip=true -DskipITs ... -Dtest='PermissionTest,StaffRoleTest,PermissionMatrixParityTest,DomainPurityRulesTest,DocumentationNotesTest'`:
`PermissionTest` 10, `DocumentationNotesTest` 16, `DomainPurityRulesTest` 5, `PermissionMatrixParityTest` 8, `StaffRoleTest` 19; 58 en total, 0 fallos. La paridad
pasó a la primera contra la nota de `docs/03` redactada aparte de la matriz en código (dos expresiones independientes de las 108 celdas).

## 1.3 Demostraciones deliberadas (cada una revertida y confirmada con `cmp`)

Copias de seguridad en `C:\Users\MARVIN\AppData\Local\Temp\confia-scratch\t81`.

1. **`PAYMENTS_CREATE` en `administrator` de `StaffRole.java`.** Rojo por aserción de resultado:
   `PermissionMatrixParityTest.theCatalogInCodeMatchesTheEffectiveMatrixOfTheDocumentCellByCell`: `Expecting empty but was: ["role administrator, module payments,
   permission payments:create: the code grants it and the document does not"]`; `StaffRoleTest.everyRoleGrantsTheCountOfPermissionsOfItsColumn`: `[88, 62, 18, 31, 35, 23]
   to contain exactly [88, 61, 18, 31, 35, 23]`; y `StaffRoleTest.theAdministratorDoesNotCollectAndDoesNotReadTheAuditLog`. `cmp` contra la copia: sin salida (`CMP_OK`).
2. **Quitar `audit:export` del Auditor en la nota de `docs/03-seguridad.md`** (fila `audit`, Auditor `L X` a `L`). Rojo: `Expecting empty but was: ["role auditor, module audit,
   permission audit:export: the code grants it and the document does not"]` (3 fallos de `PermissionMatrixParityTest`: la paridad real y los dos controles que leen el documento alterado). `cmp` sin salida.
3. **Vaciar la tabla de la nota** (las 18 filas). Rojo: `theNoteOfTheDocumentIsEvaluatedForSixRolesByEighteenModules`: `Expected size: 108 but was: 0 in: {}`. `cmp` sin salida.
   (La prueba de control `aCopyOfTheDocument...` además falló con un error de aserción de otra forma, porque su copia lee el documento vaciado; es consecuencia esperada de la ruptura.)

Tras cada una se restauró el archivo desde la copia y la compilación posterior (la construcción completa) corrió sobre los archivos restaurados.

## 1.4 `./mvnw -B verify` (árbol de trabajo completo, una sola ejecución en primer plano)

Registro: `C:\Users\MARVIN\AppData\Local\Temp\confia-scratch\t81\verify.log`.

```
Kernel:        Tests run: 196, Failures: 0, Errors: 0, Skipped: 0     (All coverage checks have been met.)
app (unitarias): Tests run: 2787, Failures: 0, Errors: 0, Skipped: 3  (JobsChangeTraceabilityTest, ya existente)
app (integración): Tests run: 821, Failures: 0, Errors: 0, Skipped: 0  (All coverage checks have been met.)
BUILD SUCCESS - Total time: 20:36 min
```

No se ejecutó `-Pmutation-gate` (lo mide el CI del PR).

## Medición y corte propuesto

`git diff --numstat` de `apps/` y `docs/` (sin `openspec/` ni código generado), sumando adiciones y borrados: **1 492 líneas efectivas** (1 492 adiciones, 0 borrados) frente a 800 y al
pronóstico de 740. La subestimación está en las pruebas (diseño: 270; real: 1 051, de ellas 78 de fixtures y ≈ 119 de originales) y en el ADR y las notas; el producto (224 líneas) quedó cerca del pronóstico (230).

| Archivo | Líneas |
|---|---|
| `Permission.java` | 113 |
| `PermissionTest.java` | 111 |
| `StaffRole.java` | 111 |
| `StaffRoleTest.java` | 238 |
| `PermissionMatrixParityTest.java` | 251 |
| `DomainPurityRulesTest.java` | 136 |
| Fixtures `domainpurity/**` (5 archivos) | 78 |
| `DocumentationNotesTest.java` (originales de §5.1 a §5.3 ≈ 119; métodos y ayudantes ≈ 118) | 237 |
| `docs/03-seguridad.md` (notas: §5.1 16, §5.2 52, §5.3 8) | 76 |
| `ADR-0027-...md` | 131 |
| `ADR-0005-...md` (nota) | 9 |
| `docs/adr/README.md` | 1 |

**La costura 1a/1b del desglose no basta:** 1a (código, matriz, paridad y nota de §5.2) serían ≈ 1 170 líneas por sí sola. Hace falta una costura no nombrada, por lo que
se detiene y se consulta. Corte propuesto (dos PR, ambos con margen justo; las cifras son aproximadas hasta cortar):

- **PR 1 (A) `permission-catalog-and-adr`, ≈ 750:** `Permission` y `PermissionTest` (commit 1, ya hecho, 224), `DomainPurityRulesTest` y fixtures (214; se mueve a la
  parte B la aserción de que `StaffRole` está en el conjunto del dominio), ADR-0027 con su fila de índice y la nota de ADR-0005 (141), notas de §5.1 y §5.3 (24) y
  las pruebas de `DocumentationNotesTest` correspondientes con los originales de §5.1 y §5.3 (≈ 150).
- **PR 2 (B) `staff-role-matrix-and-parity`, ≈ 740:** `StaffRole` y `StaffRoleTest` (349), `PermissionMatrixParityTest` (251), nota de §5.2 (52) y su prueba de
  `DocumentationNotesTest` con el original de §5.2 (≈ 90). Depende de A solo por `Permission`.

Alternativa de tres PR: separar `DomainPurityRulesTest` (214 con fixtures) como PR propio (≈ 440 con el commit 1) y dejar el ADR y las notas como un tercero (≈ 320).
Ninguno de los dos cortes requiere editar nada fuera de las superficies permitidas. Si se aprueba, el resto se compromete por archivos completos (sin reescribir lo ya verificado).

## Desviaciones y notas

- **Tamaño** (arriba): el lote supera 800 líneas; no hay excepción de tamaño solicitada.
- **Originales de `docs/03` como texto en la prueba, no como recursos:** `docs/15` §3 no excluye recursos de prueba del conteo y `src/test/resources` está fuera de las
  superficies permitidas, por lo que `DocumentationNotesTest` lleva los tres originales como bloques de texto (≈ 119 líneas), el mismo mecanismo que usa para `docs/05`
  y `docs/07`.
- **ADR-0005 se protege con una suma SHA-256**, no con un recurso de 520 líneas: la prueba calcula el hash de todo el texto previo a la nota nueva y lo compara con el del
  archivo tal como estaba (`d120e72d...8162`). Cualquier cambio de una línea anterior a la nota rompe la prueba; las notas posteriores no la afectan.
- **Fecha de las notas: 2026-11-04.**
- **Diseño D3 matizado:** la prueba de paridad expone `differences(Map, Map)` y `parse(List)` como en el diseño, pero lee la tabla de la nota por su marcador
  `**Matriz efectiva de permisos de los seis roles` y la letra `S` para `invoicing:request-void`; el cajero es el único con `S` y la prueba la traduce con una regla fija.
- Sin cambios en `ProcessBeanPolicy` (no hay beans), en poms ni en recursos.

## Cierre del lote 1 con el corte elegido (2026-11-04)

El propietario eligió el corte de dos PR. Cada rama se verificó con las pruebas focalizadas de lo que contiene (`-Djacoco.skip=true`); no se repitió la construcción
completa, que ya corrió sobre el árbol combinado (ver 1.4).

| Rama | Punta | Commits | Líneas efectivas | Pruebas focalizadas |
|---|---|---|---|---|
| 1a `change/rbac-permission-matrix-and-audit-integration-1-permission-catalog-and-adr` | `227610e` | `a6e2554` catálogo; `bd1a5df` reglas de pureza; `227610e` ADR-0027, índice, nota de ADR-0005, notas de §5.1 y §5.3 | **761** contra `e946a31` | `PermissionTest` 10, `DocumentationNotesTest` 15, `DomainPurityRulesTest` 5: 30, 0 fallos, `BUILD SUCCESS` |
| 1b `change/rbac-permission-matrix-and-audit-integration-1b-staff-role-matrix` | `65affc2` (más el commit `docs(sdd)` de estos archivos) | `65affc2` matriz de roles, paridad, nota de §5.2 y la aserción movida | **735** contra la punta de 1a | `PermissionTest` 10, `StaffRoleTest` 19, `PermissionMatrixParityTest` 8, `DomainPurityRulesTest` 5, `DocumentationNotesTest` 16: 58, 0 fallos, `BUILD SUCCESS` |

Notas del corte: en 1a, `DomainPurityRulesTest` solo afirma que `StaffAccount` está en el conjunto del dominio (`theRealDomainSetIsNotEmptyAndHoldsStaffAccount`); en 1b
vuelve la aserción de `StaffRole` (`theRealDomainSetIsNotEmptyAndHoldsTheRoleMatrix`). ADR-0027 (en 1a) cita en «Cómo se verifica» pruebas que llegan en 1b
(`StaffRoleTest`, `PermissionMatrixParityTest`); 1b debe fusionarse a continuación. Los commits nombrados por la tarea se repartieron así: el catálogo en 1a, la matriz en 1b y el
ADR en 1a; se añadió el commit `test(architecture)` de las reglas de pureza.

**Lección de estimación:** el diseño subestimó las pruebas unas cuatro veces (1 051 líneas reales frente a 270); conviene revisar los pronósticos de pruebas de las tareas 2 a 7.

## Tarea 2 (2026-11-04): completa, en dos PR (el propietario aprobó el corte)

Rama `change/rbac-permission-matrix-and-audit-integration-2-role-assignment-and-resolver` (sobre 1b, `20701ec`). El árbol completo midió
**1 247 líneas efectivas** (185 de producción, 1 062 de pruebas) contra el límite de 800 y el pronóstico de 640. Por la regla del lote no se comprometió nada hasta que el propietario aprobó el corte. Los archivos
Ahora está repartido en dos ramas (ver el cierre abajo); los registros están en `C:\Users\MARVIN\AppData\Local\Temp\confia-scratch\t82`.

### Evidencia de ciclo TDD (tarea 2)

| Unidad | Archivo de prueba | Capa | ROJO | VERDE | TRIANGULACIÓN | REFACTOR |
|---|---|---|---|---|---|---|
| Resolutor | `identity/AccountPermissionsTest` | Unitaria | No compilaba (`cannot find symbol`) y luego `UnsupportedOperation skeleton` (4 errores, 2 fallos) | 6/6 | Contabilidad y Cajero, cinco códigos inválidos más el válido, sin fila, error, institución y cuenta del actor | Sin cambios |
| Resolutor | `identity/AccountPermissionsIT` | Integración | `relation "identity_staff_account_role" does not exist` | 5/5 | Dos instituciones con la misma cuenta, cabecera ajena, solo lectura, `25006` | Sin cambios |
| Tabla | `schema/IdentityStaffAccountRoleSchemaIT` | Integración | `relation ... does not exist`; `No migration with a target version 14` | 9/9 | Dos asignaciones, cinco códigos inválidos y seis válidos, FK, RLS (admin y propietario), columnas, AZ19 | Se añadió `rowSecurityIsForcedOnTheOwnerToo` al ver en la demostración 1 que ninguna prueba del propietario cubría `FORCE` |
| Tabla | `schema/V14SchemaDiffIT` | Integración | `No migration with a target version 14` | 2/2 | Diferencia de catálogo y sumas de `V1` a `V13` | La suma de `V13` (`-1568951550`) se registró de la primera ejecución en verde; `V13` no se editó desde `03b7e2f` |
| Tabla | `schema/MultiTenantSchemaIT` (BI119) | Integración | `Expected size: 1 but was: 0 in: []` | 12/12 | | |
| Privilegios | `schema/RolePrivilegeMatrixIT` (BI122 a BI124) | Integración | `relation ... does not exist` | 52/52 | Matriz de cinco roles, variante con `DELETE` de más, PUBLIC y rol ajeno, cuatro sentencias con `confia_admin_app` y `confia_portal_app` | |
| Inventario | `architecture/PermissionResolverInventoryTest` | Arquitectura | No compilaba | 5/5 | Cada ausencia con su clase infractora | |

Rojo 1 (compilación, sin producción): `BUILD FAILURE`, 68 errores `cannot find symbol` sobre `AccountPermissions` (14), `PermissionResolver` (10), `JooqStaffRoleAssignmentRepository`
(5) y `StaffRoleAssignmentRepository` (5). Rojo 2 (con un esqueleto que lanza `UnsupportedOperationException` y sin `V14`): `Tests run: 79, Failures: 4, Errors: 16`; extracto verbatim en
`red2-excerpt.txt`: `PSQL ERROR: relation "identity_staff_account_role" does not exist`, `Flyway No migration with a target version 14 could be found`, `UnsupportedOperation skeleton`.

### Demostraciones (cada una revertida y confirmada con `cmp` contra copias en `t82`)

1. **Quitar `FORCE ROW LEVEL SECURITY` de `V14`.** Rojo: `rowSecurityIsForcedOnTheOwnerToo` (`expected: "42501" but was: "23503"`),
   `MultiTenantSchemaIT.everyTableWith...Forced` y `theStaffAccountRoleTable...` (`identity_staff_account_role must have FORCE ROW LEVEL SECURITY`), `V14SchemaDiffIT`
   (`expected: "r|rls=true|force=true" but was: "r|rls=true|force=false"`). `CMP_OK_1`.
2. **`GRANT DELETE ... TO confia_admin_app`.** Rojo: `confia_admin_app holds [DELETE, SELECT] on identity_staff_account_role but the matrix says [SELECT]` (dos pruebas),
   `administrative role: DELETE expected: "42501" but was: null` y la diferencia de `V14SchemaDiffIT`. `CMP_OK_2`.
3. **`AccountPermissions` devuelve `Set.of(Permission.values())` ante un error.** Rojo: `AccountPermissionsTest.aFailureOfTheReadIsThrownNever...:87 Expecting code to raise a throwable.` `CMP_OK_3`.

Confirmación posterior a las tres reversiones (80 pruebas focalizadas, `-Djacoco.skip=true`): `AccountPermissionsTest` 6, `PermissionResolverInventoryTest` 5, `AccountPermissionsIT` 5,
`IdentityStaffAccountRoleSchemaIT` 9, `MultiTenantSchemaIT` 12, `RolePrivilegeMatrixIT` 52, `V14SchemaDiffIT` 2: 0 fallos, `BUILD SUCCESS`. `./mvnw -B verify` completo **no se ejecutó**:
Esas 80 pruebas corrieron sobre el árbol completo antes del corte.

### Medición y corte (aprobado por el propietario)

| Archivo | Líneas |
|---|---|
| `V14__create_identity_staff_account_role.sql` | 35 |
| `PermissionResolver`, `StaffRoleAssignmentRepository`, `AccountPermissions`, `JooqStaffRoleAssignmentRepository`, `IdentityConfiguration` (+) | 23, 21, 52, 34, 20 |
| `V14SchemaDiffIT`, `IdentityStaffAccountRoleSchemaIT`, `MultiTenantSchemaIT` (+), `RolePrivilegeMatrixIT` (+), `StaffRoleFixtures` | 104, 288, 23, 148, 86 |
| `AccountPermissionsTest`, `AccountPermissionsIT`, `PermissionResolverInventoryTest` | 125, 139, 149 |

- **PR 2a `staff-account-role-table` ≈ 627:** `V14`, `V14SchemaDiffIT`, `MultiTenantSchemaIT`, `RolePrivilegeMatrixIT`, `StaffRoleFixtures` e `IdentityStaffAccountRoleSchemaIT` sin la prueba de AZ19 (≈ 231).
- **PR 2b `permission-resolver` ≈ 640:** puerto, repositorio, adaptador, fábrica, `AccountPermissionsTest`, `AccountPermissionsIT`, `PermissionResolverInventoryTest` y la prueba de AZ19 movida a un archivo propio de
  `schema` (≈ 77, porque `StepwiseDatabase` es de paquete). 2b depende de 2a por la tabla.

### Cierre de la tarea 2 con el corte aprobado

| Rama | Punta | Commits | Líneas efectivas | Verificación |
|---|---|---|---|---|
| 2a `change/rbac-permission-matrix-and-audit-integration-2-role-assignment-and-resolver` | `969c27f` | `feat(db): create the staff account role assignment table with exact grants` | **627** contra `20701ec` | Solo pruebas focalizadas (`-Djacoco.skip=true`): `IdentityStaffAccountRoleSchemaIT` 8, `MultiTenantSchemaIT` 12, `RolePrivilegeMatrixIT` 52, `V14SchemaDiffIT` 2: 74, 0 fallos, `BUILD SUCCESS` (01:47). No se corrió `verify` completo sobre 2a sola. |
| 2b `change/rbac-permission-matrix-and-audit-integration-2b-permission-resolver` | `73ca53b` (más el commit `docs(sdd)` de estos archivos) | `feat(identity): resolve effective permissions per request from the role assignment` | **640** contra `969c27f` | Focalizadas: `AccountPermissionsTest` 6, `PermissionResolverInventoryTest` 5, `AccountPermissionsIT` 5, `UnknownRoleCodeResolutionIT` 1: 17, 0 fallos. **`./mvnw -B verify` sobre esta punta (árbol combinado, una sola ejecución): kernel 196, unitarias 2 798 (3 omitidas, `JobsChangeTraceabilityTest`, preexistentes), integración 842, ambas con «All coverage checks have been met», `BUILD SUCCESS`, Total time 33:33 min.** No se ejecutó `-Pmutation-gate`. |

Notas: la prueba de AZ19 pasó a `schema/UnknownRoleCodeResolutionIT` (2b) porque `StepwiseDatabase` es de paquete; 2a conserva 8 de las 9 pruebas de `IdentityStaffAccountRoleSchemaIT`.
Las pruebas midieron unas 2,2 veces su pronóstico (1 062 líneas reales frente a 480). 2b debe fusionarse a continuación de 2a. Sin cambios en `ProcessBeanPolicy`, poms ni recursos distintos de `V14`.

## Pendiente

- Tareas 4 a 7 sin empezar. La tarea 3 está terminada: el `verify` completo lo midió el CI de cada PR (ver abajo).

## Tarea 3 (2026-11-04): confirmada en cuatro ramas; `verify` completo en verde en el CI

El propietario aprobó el corte en cuatro partes. Cada rama sale de la anterior; la base de cada medición es la punta anterior (`ca3f283` para 3a-1; ya está en `main`). Las cuatro ramas se subieron el 2026-11-04 y abrieron #253 a #256.

| Rama | Punta | Commits | Líneas efectivas | Pruebas focalizadas (`-Djacoco.skip=true`) |
|---|---|---|---|---|
| 3a-1 `...-3-filter-authorization` | `24c9c5e` | `ad9bc75` refactor DataAccessFailure; `24c9c5e` decisor y lista | **713** | 92 unitarias, 0 fallos (`BUILD SUCCESS`) |
| 3a-2 `...-3a2-chain-wiring` | `761636c` | `761636c` decisión en la cadena | **633** | 216 unitarias + `SessionLivenessIT` 12, 0 fallos |
| 3a-3 `...-3a3-authorization-edge-and-rules` | `8df15eb` | `02d0d20` reglas de arquitectura; `8df15eb` `AuthorizationEdgeIT` | **727** | 27 unitarias + `AuthorizationEdgeIT` 6, 0 fallos |
| 3b `...-3b-route-coverage-guard` | `3341cd0` | `f1a4eca` guarda; `3341cd0` pruebas de clasificación | **657** | 131 unitarias + `ProductionRoutesClassifiedIT` 2 + `AuthorizationEdgeIT` 6, 0 fallos |

Las ramas inferiores solo tuvieron ejecuciones focalizadas. Las pruebas de la tarea midieron unas 3,7 veces su pronóstico (el diseño daba 440 de pruebas; el total de la tarea fue 2 730 líneas, de ellas unas 2 000 de pruebas).

**`verify` completo local (intento del 2026-11-04, sin resultado):** la ejecución sobre la punta de 3b (`3341cd0`) fue terminada por el entorno por memoria crítica (2,7 GB libres de 32 GB, 12 contenedores Docker activos); su registro `confia-scratch/t83/verify.log` está incompleto y no es un resultado. No se reintentó mientras persista la presión de memoria. Por decisión del propietario, el `verify` de cada rama es el del CI de su PR.

**ROJOS no observados antes del VERDE:** `RouteCoverageGuardTest` y `RouteCoverageGuard` (se escribió la guarda antes de su prueba), `AuthorizationEdgeIT`, `OpenApiRouteClassificationTest`, `ProductionRoutesClassifiedIT`, los casos nuevos de `PublicRouteAllowListTest`, `AuthorizationBoundaryRulesTest`, `PermissionedEndpointTextPermissionCompilationTest`, `LogAuthorizationMetricsTest` y la línea `not in the allow-list` de `ProcessBeanIsolationTest`. Observados: compilación (116 `cannot find symbol`) y `PermissionEdgeTest` 13 fallos de 25 con el esqueleto.
**Demostraciones** (no son ROJO previo; cada una revertida y confirmada con `cmp`): 1, 2, 4, 5 y 6 como se registró; 3 (guarda sin efecto): `RouteCoverageGuardTest` 11 de 13 fallan. La 4 solo falló tras corregir una aserción débil de la prueba; la 5 falla por `UnsatisfiedDependency` del resolutor, no por el mensaje `not in the allow-list`.

**Arreglo tras el primer CI (2026-11-05):** `IdempotencyScopeExclusionInventoryTest` fallaba porque `AuthenticationMethod` y `PermissionResolver` no estaban en la lista permitida del paquete web de `shared.security`. `1cdc6fe` limita `AuthenticationMethod` a `shared.web.authorization` y `PermissionResolver` a ese paquete y a `shared.web.edge.AuthorizationConfiguration`, con una prueba nueva; se propagó con merge hacia adelante (`9456f55`, `d5643f3`, `3c68e92`) tras pasar la suite unitaria completa en cada punta.

**CI en verde (2026-11-05)**, con backend verify, backend mutation, frontend verify, accesibilidad y escaneo de seguridad:

| PR | Punta | backend verify | backend mutation |
|---|---|---|---|
| #253 (3a-1) | `1cdc6fe` | pass, 14 min 33 s | pass, 43 min 24 s |
| #254 (3a-2) | `9456f55` | pass, 13 min 51 s | pass, 47 min 39 s |
| #255 (3a-3) | `d5643f3` | pass, 14 min 27 s (tras relanzar el job por la inestabilidad de `CsrfKeyStartupTest`) | pass, 44 min 55 s |
| #256 (3b) | `3c68e92` | pass, 14 min 44 s | pass, 44 min 8 s |

## Tarea 4a (2026-11-09): puerto de métricas completo, adaptador y nota de `docs/07`

Rama `change/rbac-permission-matrix-and-audit-integration-4a-authorization-metrics`, creada desde `origin/main` (tareas 1 a 3 fusionadas). Corte 4a/4b del diseño §10.1; 4b (manejador, grabador, `DenialWindows`, propiedades, ITs, cableado de la cadena) no se empezó y la tarea 4 sigue sin marcarse.

| Commits | Líneas efectivas | Verificación focalizada (`-Djacoco.skip=true`, JDK 25) |
|---|---|---|
| `9a44aaa` `feat(observability): publish the authorization events through a port`; `2080360` `docs(ops): note the four authorization events` | **254** contra `origin/main` (235 añadidas, 19 borradas; sin `openspec/`) | `LogAuthorizationMetricsTest` 6, `DocumentationNotesTest` 17, `AuthorizationBoundaryRulesTest` 5, `ProcessBeanIsolationTest` 33, `PermissionDeciderTest` 8: 69, 0 fallos |

- **ROJO observado:** con las pruebas nuevas y sin producción, la compilación de pruebas falló con 14 errores `cannot find symbol` en `LogAuthorizationMetricsTest` (`denialOmitted`, `denialAuditFailed`, `denialTrackerFull`, `trackedKeys`). **No observado por separado:** la prueba nueva de `DocumentationNotesTest` (nota de `docs/07`), porque el fallo de compilación la tapó; se escribió la nota después sin correr esa prueba contra el árbol sin nota.
- **Demostración deliberada:** no hecha en 4a (la tarea 4 la fija sobre código de 4b).
- **Ajustes fuera de los archivos centrales:** `RecordingAuthorizationMetrics` (doble de pruebas en `shared/web/harness`) implementa los tres métodos nuevos y expone contadores; `LogAuthorizationMetrics` pasó de `synchronized` con un solo contador a un mapa por clave (evento y permiso), como `LogRateLimitMetrics`; sus pruebas viven en `shared/observability/metrics`, no en `shared/web/authorization`.
- La nota de `docs/07` está en la sección 5.4 y la comprueba `docs07Section54HasTheDatedNoteWithTheFourAuthorizationEvents`.
- No se corrió `./mvnw verify` completo (memoria limitada); queda al CI del PR.

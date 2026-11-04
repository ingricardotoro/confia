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

## Pendiente

- Tareas 2 a 7 sin empezar. La tarea 2 parte de la punta de 1b (o de `main` si ya se fusionaron).

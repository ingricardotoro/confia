# Tareas: matriz de permisos RBAC e integración con la bitácora (cambio 8 de F0)

- **Cambio:** `rbac-permission-matrix-and-audit-integration` (F0, cambio 8; entregable 4, brecha A7, criterio de salida 2)
- **Fase:** tareas
- **Fecha:** 2026-11-03
- **Estado:** pendiente de aprobación del propietario; corregido el 2026-11-04 a su pedido (observación 11)
- **Entradas aprobadas:** `proposal.md` (O1 a O10 y Q1 a Q5 vinculantes), `design.md` (D1 a D19, secciones 9, 10 y 11) y los deltas
  `specs/authorization`, `specs/web-edge`, `specs/identity`, `specs/audit-trail` y `specs/build-integrity` (identificadores `AZ`, `WE`, `I`, `AT` y `BI`,
  más los escenarios modificados sin identificador, que cada fila de la trazabilidad nombra por su título)
- **Modo de TDD:** estricto (`openspec/config.yaml`). Ejecutor: `./mvnw -B verify` en `apps/api`, con `JAVA_HOME` apuntando a JDK 25 y Docker en ejecución.
- **Línea base de la cadena:** rama `change/rbac-permission-matrix-and-audit-integration` en `d0c2acc` (artefactos SDD) sobre `main`.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines (additions + deletions, sin `openspec/` ni código generado) | **4 585 nominales** (diseño §10.1: producción 1 285, pruebas 2 950, documentación 350). Rango de la propuesta: 3 400 a 4 200; suma de rangos por rebanada: 3 550 a 5 250. Riesgo de subestimación alto (factor histórico 2,2 a 7,97), por eso cada PR revisa su cuenta antes de abrirse |
| 400-line budget risk | **High** frente a las 400 líneas de la preflight; **medio a alto por PR** frente al presupuesto del proyecto de 800 (PR 1, 3, 4 y 6 con margen justo) |
| Chained PRs recommended | Yes |
| Suggested split | **7 PR apilados** (1, 2, 3, 4, 5a, 5b, 6), con dos cortes preparados: PR 3 en 3a/3b y PR 4 en 4a/4b (hasta 9 PR) |
| Delivery strategy | Cadena de siete PR apilados aprobada por el propietario (Q5); 800 líneas efectivas por PR (`docs/15-flujo-de-trabajo-git.md` §3, no 400). La decisión ya está tomada, por lo que se comporta como `auto-chain` |
| Chain strategy | `stacked-to-main`: cada PR parte de la rama del anterior y se fusiona a `main` en orden; antes de abrir el siguiente se actualiza su base |
| Presupuesto del proyecto por PR | 800 líneas de cambio efectivo; las pruebas y la documentación que el cambio invalida nunca se separan del código |

Decision needed before apply: No (la cadena de siete PR con 800 líneas efectivas fue aprobada por el propietario; no hay decisión nueva)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Cuenta de tareas.** 7 tareas de nivel superior, una por rebanada, con subtareas, frente al tope de 15 de `openspec/config.yaml`. Los cortes 3b y 4b son
subtareas condicionales de las tareas 3 y 4, no tareas nuevas. **No se propone partir el cambio.**

**Regla de partición.** Si la medición de un PR supera 800 líneas efectivas, el ejecutor lo parte por archivos completos en la costura nombrada de su tarea,
sin consultar, y deja una nota fechada con la medida en `apply-progress.md`. Solo consulta si una parte indivisible exige una excepción de tamaño o si
necesita una costura no nombrada. Las costuras de las tareas 3 y 4 las define el diseño (§10.1); las de las tareas 1 y 7 las propone este desglose y las
de las tareas 2, 5 y 6 no se prevén (la 6 es indivisible: la migración y los fixtures deben ir juntos).

### Pronóstico y costuras por rebanada (líneas efectivas, sin `openspec/`)

| PR | Rama (sufijo de `change/rbac-permission-matrix-and-audit-integration-`) | Tarea | Prod. | Pruebas | Doc. | Total | Margen | Costura nombrada si supera 800 |
|---|---|---|---|---|---|---|---|---|
| 1 | `permission-catalog-and-adr` | 1 | 230 | 270 | 240 | **740** | Justo | **1a** código, matriz, paridad y notas de `docs/03` §5.2 / **1b** ADR-0027, índice, nota de ADR-0005 y notas de §5.1 y §5.3 (propuesta de este desglose). Diseño: los originales de `docs/03` como recursos de prueba si `docs/15` los excluye |
| 2 | `role-assignment-and-resolver` | 2 | 150 | 480 | 10 | **640** | Cómodo | Ninguna prevista |
| 3 | `filter-authorization-and-route-guard` | 3 | 340 | 440 | 10 | **790** | Muy justo | **3a** decisor, lista, `otp`, sesión viva, fallo de resolución, inversiones / **3b** `route-coverage-guard`: guarda y sus pruebas (diseño §10.1) |
| 4 | `permission-denial-audit` | 4 | 340 | 420 | 20 | **780** | Muy justo | **4a** `authorization-metrics`: puerto completo, adaptador y su prueba, nota de `docs/07` / **4b** el resto (diseño §10.1) |
| 5a | `mfa-policy-derivation` | 5 | 70 | 480 | 20 | **570** | Cómodo | Ninguna prevista |
| 5b | `drop-mfa-required-column` | 6 | 5 | 340 | 0 | **345** | Cómodo | Ninguna: V15 y los fixtures son indivisibles |
| 6 | `roles-report-and-criterion-2` | 7 | 150 | 520 | 50 | **720** | Justo | **7a** informe, entrada de producción e instantánea / **7b** criterio 2, inversiones, `docs/09` y nota del plan (propuesta de este desglose) |
| | **Total** | 7 | 1 285 | 2 950 | 350 | **4 585** | | |

**Orden duro (propuesta y diseño §10.1).** 1 antes que todo; 2 antes de 3 y de 5a; 3 antes de 4; 3 y 4 antes de 6; 5a antes de 5b. La cadena lineal
1, 2, 3, 4, 5a, 5b, 6 los cumple todos. Migraciones `V14` (tarea 2) y `V15` (tarea 6) en ese orden; `V1` a `V13` nunca se editan.

**Condiciones duras que se cierran** (la casilla de la tarea no se marca sin ellas):

| Condición | Tarea | Prueba de cierre |
|---|---|---|
| Criterio de salida 2 de F0 (`docs/09`) | 3, 4 y 7 | `ExitCriterion2EndToEndIT` y `AuthorizationEdgeIT` (tercera parte) en verde, con sus controles [AZ65 a AZ70]; `docs/09` marcado en la tarea 7 [AZ73] |
| La decisión falla cerrada | 2 y 3 | `AccountPermissionsTest`, `PermissionEdgeTest` y `AuthorizationEdgeIT` en verde con su demostración deliberada [AZ21, AZ60, WE101] |
| El retiro de la columna no precede a la política | 5 y 6 | Construcción de la tarea 5 en verde sin `V15` (evidencia en `apply-progress.md`) y tarea 6 fusionada después [BI127] |
| Ningún bean de autorización fuera del proceso administrativo | 3, 5 y 7 | `ProcessBeanIsolationTest` en verde, cada línea de `ProcessBeanPolicy` primero en rojo [AZ39, BI128 a BI130] |

### Reglas comunes a todas las tareas

- **Comandos.** Todos desde `apps/api`. **Un solo build a la vez**: nunca se lanza un segundo `./mvnw` (ni uno focalizado) mientras otro corre, porque
  comparten el repositorio local, el contenedor de generación de jOOQ y los puertos de Testcontainers. Cierra cada PR `./mvnw -B verify`. La **puerta de
  mutación (PIT) corre en el CI del PR**; el propietario ejecuta `./mvnw verify -Pmutation-gate` completo en `main` al final (tarea 7). Las órdenes
  focalizadas son solo para iterar: un `-Dtest=` acotado informa `BUILD FAILURE` por JaCoCo aunque las pruebas pasen. Forma unitaria:
  `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='A,B'`. Forma de integración:
  `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=X -Dfailsafe.failIfNoSpecifiedTests=false`.
- **Rojo registrado.** Cada paso en rojo corre contra el árbol sin el código de producción y se registra en
  `openspec/changes/rbac-permission-matrix-and-audit-integration/apply-progress.md` con la causa **observada**. Si falla por otra causa, se detiene y se
  investiga. Un commit en rojo no entra al historial: las pruebas en rojo se comprometen con su verde. Después del verde, un paso de **REFACTOR** deja la
  suite en verde antes de comprometer, sin cambiar comportamiento.
- **Demostración deliberada.** Cada tarea nombra una ruptura temporal de producción que debe poner en rojo una prueba concreta **por una aserción de
  resultado** (estado, fila, contador, valor registrado), nunca por tiempo de espera. Procedimiento fijo: (1) copiar el archivo de producción fuera del
  repositorio, en el directorio temporal de la sesión; (2) aplicar la ruptura y observar el rojo por la aserción nombrada; (3) restaurar el archivo desde la
  copia (un archivo nuevo de la ruptura se borra); (4) **confirmar con `cmp`** que el restaurado es idéntico a la copia (sin salida) y que `git diff` no lo
  muestra; (5) registrar en `apply-progress.md` la ruptura, la aserción que falló y el resultado de `cmp`. Una demostración no revertida y confirmada
  bloquea el cierre de la tarea.
- **Ataque y borde primero.** Las pruebas de ataque (afirmación de permiso del cliente, variantes de ruta, `[pwd]`, token restringido, sesión revocada,
  otra institución, error de lectura, centinelas en asiento, métricas y registros) se escriben antes que el código que defienden.
- **Determinismo (BI137, BI138).** Las pruebas del tope, de la ventana y del criterio 2 usan `MutableClock` inyectado e institución propia por prueba; sin
  `Thread.sleep`, `Instant.now()` ni `Clock.systemUTC()`. Concurrencia con `CyclicBarrier` y `CountDownLatch`.
- **Lista de permitidos y ArchUnit.** Cada línea de `ProcessBeanPolicy` y cada regla nueva entran en el mismo PR que su primer bean o paquete; se
  demuestra primero el rojo (`ProcessBeanIsolationTest` falla con `not in the allow-list` nombrando proceso, bean y paquete) y luego el verde.
- **Registros y datos.** Nunca contraseña, token, `Authorization`, correo, documento ni datos de menores en registros, asientos ni métricas. Los
  detalles de asiento se construyen con `AuditJson`. Ningún secreto en el repositorio (regla 13): las pruebas generan en ejecución lo que necesiten.
- **Medición antes de abrir el PR.** `git diff --numstat <base>...HEAD` excluyendo `openspec/` y código generado, registrada en `apply-progress.md`. Si
  supera 800, se aplica la regla de partición.
- **Commits.** Conventional Commits en inglés, sin atribución de herramientas de IA, con pruebas y documentación en el mismo commit que el
  comportamiento. Una rama por PR, con el sufijo de la tabla anterior; la primera parte de la rama del cambio (o de `main` si ya se fusionó) y cada
  una siguiente parte de la anterior.
- **Revisión de seguridad de cada PR.** Lista aplicable de `docs/03` marcada en la descripción del PR (entradas validadas en el borde, nada de datos de
  menores en registros, acciones sensibles auditadas, sin SQL concatenado, sin privilegio de escritura de más).
- **Idioma.** Código, identificadores y commits en inglés; documentación y notas fechadas en español neutro profesional, sin reescribir cuerpos
  existentes (`DocumentationNotesTest` con el mecanismo de líneas originales intactas).
- **Rutas.** Producción bajo `apps/api/app/src/main/java/com/confia/` y pruebas bajo `apps/api/app/src/test/java/com/confia/`, salvo `kernel`
  (`apps/api/kernel/src/...`). Los paquetes de las pruebas de integración se confirman en el rojo contra el árbol real.

### Suggested Work Units

| Unidad | Objetivo | PR | Comando de prueba enfocado | Arnés de ejecución | Frontera de reversión |
|---|---|---|---|---|---|
| 1 | Catálogo `Permission`, matriz `StaffRole`, paridad con `docs/03`, ADR-0027 | PR 1 | `-Dtest='PermissionTest,StaffRoleTest,PermissionMatrixParityTest,DomainPurityRulesTest,DocumentationNotesTest'` | N/A: sin comportamiento en ejecución (solo tipos y documentos) | Se revierte el commit; nada depende aún del catálogo |
| 2 | `V14`, puerto `PermissionResolver`, adaptador de solo lectura | PR 2 | `-Dtest='AccountPermissionsTest,PermissionResolverInventoryTest'`; IT: `-Dit.test='V14SchemaDiffIT,IdentityStaffAccountRoleSchemaIT,AccountPermissionsIT,RolePrivilegeMatrixIT'` | PostgreSQL real con los cinco roles y dos instituciones | Se elimina `V14` (antes de producción) y se regenera jOOQ; el resolutor no tiene consumidor |
| 3 | Decisor en la cadena, lista cerrada, `otp`, sesión viva, guarda de arranque | PR 3 (3a, 3b) | `-Dtest='PermissionDeciderTest,PermissionEdgeTest,RouteCoverageGuardTest,PublicRouteAllowListTest,AuthorizationBoundaryRulesTest,ProcessBeanIsolationTest'`; IT: `-Dit.test='AuthorizationEdgeIT,ProductionRoutesClassifiedIT'` | Cadena administrativa real con rutas de prueba y PostgreSQL | Con la lista de producción vacía no cambia ninguna ruta; revertir deja `denyAll` |
| 4 | Auditoría de denegaciones con tope, ventana y resumen | PR 4 (4a, 4b) | `-Dtest='DenialWindowsTest,LogAuthorizationMetricsTest,NoRealTimeInDenialTestsTest,AuthorizationBoundaryRulesTest'`; IT: `-Dit.test='PermissionDenialAuditIT,AuthorizationEdgeIT'` | Escritor real y roto, reloj manual, institución propia | Se revierte el decorador; los asientos escritos no se borran |
| 5 | `MfaPolicy` sin leer la columna | PR 5a | `-Dtest='MfaPolicy*Test,AuthenticateWithPassword*,ResetPasswordWithToken*,StaffAccountTest'` (incluye `MfaPolicyProductionWiringTest`, que por su sufijo `Test` corre en Surefire); IT: `-Dit.test='SecondFactorExemptionsProbeIT,LoginEndpointIT'` | Proceso de inicio de sesión real en la misma JVM, con política de prueba y política real | Se revierte el commit; la columna sigue existiendo |
| 6 | `V15` y retiro de la columna de los fixtures | PR 5b | `-Dtest='MfaRequiredAbsenceTest'`; IT: `-Dit.test='V15SchemaDiffIT,V9ExistingRowsIT,RetentionPurgeFunctionsIT,RefreshTokenFamilyPurgeFunctionIT,MultiTenantSchemaIT'` | PostgreSQL real migrado por pasos | Antes de producción se elimina `V15`; después, migración nueva |
| 7 | `GET /api/v1/identity/roles`, criterio 2, `docs/09` | PR 6 (7a, 7b) | `-Dtest='ListRolePermissionsTest,PermissionedEndpointsTest,OpenApiContractSnapshotTest,DocumentationNotesTest'`; IT: `-Dit.test='RolesReportEndpointIT,AuthorizationEdgeIT,ExitCriterion2EndToEndIT'` | Proceso administrativo real (`AdminProcessLauncher`) con inicio de sesión TOTP | Se quita la entrada de la lista: la ruta vuelve a `denyAll` |

### Dependencias y paralelismo

| Tarea | Depende de | Paralelismo |
|---|---|---|
| 1 | Ninguna | Raíz de la cadena |
| 2 | 1 | Secuencial: usa `Permission` y `StaffRole` |
| 3 | 2 | Secuencial: el decisor consume el puerto del resolutor |
| 4 | 3 | Secuencial: decora el manejador de `403` y usa `AuthorizationMetrics` de la 3 |
| 5 | 2 | Puede redactarse en paralelo con 3 y 4 (archivos casi disjuntos, `ProcessBeanPolicy` e `IdentityConfiguration` en común); se fusiona después de la 4 por el orden de la cadena. Un solo escritor |
| 6 | 5 | Secuencial: el retiro va al final de la derivación |
| 7 | 3, 4 y 6 | Cierra el cambio; `docs/09` solo se marca con todo en `main` |

### Tabla de verificación (qué prueba cierra cada tarea)

| Tarea | Verde observable | Control deliberado (con `cmp`) | Puerta |
|---|---|---|---|
| 1 | Paridad 6 por 18 celdas, 89 permisos, ADR indexado | Administrador con `payments:create`; matriz vaciada | `./mvnw -B verify` |
| 2 | Resolutor falla cerrado en cuatro casos; privilegios exactos | Sin `FORCE`; `DELETE` concedido; todos los permisos ante un error | `./mvnw -B verify` |
| 3 | `403` sin asiento previo a idempotencia y limitador; arranque falla ante ruta sin clasificar; sesión viva en cada entrada | Sin `otp`; decisión en interceptor; guarda desactivada; portal con beans; sesión viva derivada con `skip(1)` | `./mvnw -B verify` |
| 4 | Un asiento por denegación, tope exacto bajo concurrencia, suma conservada ante un fallo, resumen, un resumen por petición, `403` con escritor roto | Origen ajeno en el resumen; contar dentro de la transacción; `500` ante escritor roto; reloj atrás; apagado sin emisión; barrido dentro de `compute`; toda la cola en una petición; sin regla del instante futuro; asiento fallido fuera de la suma | `./mvnw -B verify` |
| 5 | Seis roles y cuenta sin rol exigen segundo factor; `STAFF` en el proceso real; exención por instancia en la misma JVM | `STAFF` falsa; exigencia condicionada al rol; `Authenticated` sin rol; exenciones en un campo `static` | `./mvnw -B verify` |
| 6 | Columna ausente en esquema, jOOQ y fuentes | Referencia reinsertada en un fixture; segunda sentencia en `V15` | `./mvnw -B verify` |
| 7 | Criterio 2 de extremo a extremo; informe sin datos personales; instantánea de ocho operaciones | Entrada quitada de la lista (`403` sin asiento); correo en el DTO; endpoint de bitácora en un fixture | `./mvnw -B verify`; **propietario: `./mvnw verify -Pmutation-gate` en `main`** |

---

## Fase 1: catálogo y resolución por petición

- [x] 1 **PR 1 `permission-catalog-and-adr`: catálogo de 89 permisos, matriz de seis roles, paridad con `docs/03` §5.2 y ADR-0027 (D2, D3, D17; O1, O2, O3, O10).**
  - **Archivos.** Crea `apps/api/kernel/src/main/java/com/confia/kernel/Permission.java`, `identity/domain/StaffRole.java`,
    `docs/adr/ADR-0027-autorizacion-por-permisos-resueltos-por-peticion.md`; modifica `docs/03-seguridad.md` (§5.1, §5.2, §5.3),
    `docs/adr/README.md`, `docs/adr/ADR-0005-autenticacion-y-gestion-de-sesiones.md` (solo nota fechada). Pruebas:
    `kernel/src/test/.../PermissionTest.java`, `identity/domain/StaffRoleTest.java`, `architecture/PermissionMatrixParityTest.java`,
    `architecture/DomainPurityRulesTest.java`, `architecture/DocumentationNotesTest.java` (ampliada).
  - [x] 1.1 **ROJO.** `PermissionTest`: 89 constantes sin repetir, forma `^[a-z]+:[a-z]+(-[a-z]+)*$`, 88 de módulo y verbo (18 módulos, 6 verbos) más
    `invoicing:request-void`, distinto de `invoicing:void`; `ledger:update`, `payments:update` e `invoicing:update` no existen; `ofCode` desconocido
    devuelve vacío [AZ04, AZ05, AZ06]. `StaffRoleTest`: seis roles en orden de catálogo con sus códigos, sin rol del Encargado ni tabla global en
    `db/migration` [AZ01, AZ02]; `ofCode` desconocido da `Optional.empty()` [AZ03]; una aserción por columna de la matriz: Super Admin 88 sin solicitud [AZ07],
    Cajero [AZ08], Administrador sin `payments:create` ni `audit` [AZ09], Auditor solo `read`/`export` con 18 `read` [AZ10], `audit:read` y `audit:export` solo de
    Super Admin y Auditor [AZ11], `identity:read` de tres roles y sin permiso de MFA propia [AZ12], `reporting:read` único para Cajero y Coordinador [AZ13]. Todo
    permiso lo concede algún rol [AZ05]. `PermissionMatrixParityTest`: `differences(code, document)` y `parse(notaFechadaLineas)` puros; evalúa 6 por 18
    celdas y falla con tabla vacía [AZ14]; copia del código con `payments:create` en Administrador y copia del documento sin `audit:export` del Auditor
    fallan nombrando rol, módulo y permiso [AZ15]. `DocumentationNotesTest`: notas fechadas de §5.1, §5.2 (cuatro diferencias, regla 3, párrafo final,
    iniciadores pendientes de §5.3) y §5.3 con originales intactos [AZ16]; ADR-0027 existe, indexado con número, estado y fecha, y ADR-0005 con nota de remisión
    [AZ72]. `DomainPurityRulesTest`: `..domain..` solo depende de `kernel` y de su módulo, ni Spring ni jOOQ; `kernel.Permission` sin dependencias; fixtures que
    importan Spring o jOOQ fallan [BI132]. **Rojos esperados:** error de compilación `cannot find symbol class Permission` y `StaffRole`; paridad con
    `marker not found` en `docs/03`; ADR-0027 inexistente.
  - [x] 1.2 **VERDE y REFACTOR.** Crear los dos enums (`Permission` con código y `ofCode`; `StaffRole` con `code()`, `permissions()` inmutable y `ofCode`), la
    nota fechada de §5.2 con la matriz efectiva y su marcador, las demás notas, ADR-0027 en estado **Propuesto** con el esquema de D17, la fila del índice y la
    nota de ADR-0005. Sin cambios de `ProcessBeanPolicy` (no hay beans).
  - [x] 1.3 **Demostraciones deliberadas.** (1) Agregar `PAYMENTS_CREATE` a `administrator` en `StaffRole.java` pone en rojo `PermissionMatrixParityTest` y
    `StaffRoleTest` [AZ09, AZ15]. (2) Quitar `audit:export` del Auditor en la nota de `docs/03-seguridad.md` pone en rojo la paridad [AZ15]. (3) Vaciar la tabla
    de la nota pone en rojo la aserción de 6 por 18 celdas [AZ14]. Se revierten y se confirman con `cmp`.
  - [x] 1.4 **Cierre.** `./mvnw -B verify`. Commits: `feat(kernel): add the closed permission catalog`, `feat(identity): add the staff role matrix and its parity check
    with the security document`, `docs(adr): propose ADR-0027 on permissions resolved per request`. Tamaño: diseño 740 (230 prod., 270 pruebas, 240 doc.); costura 1a/1b si
    supera 800. — Escenarios AZ01 a AZ16, AZ72 y BI132.
  - **Cierre 2026-11-04 (evidencia en `apply-progress.md`).** El propietario eligió el corte en dos PR porque el árbol completo midió 1 492 líneas efectivas (la costura
    1a/1b del desglose daba ≈ 1 170). **PR 1a** (rama `change/rbac-permission-matrix-and-audit-integration-1-permission-catalog-and-adr`, 761 líneas efectivas contra
    `e946a31`): `a6e2554` catálogo, `bd1a5df` reglas de pureza del dominio, `227610e` ADR-0027, índice, nota de ADR-0005 y notas de §5.1 y §5.3. **PR 1b** (rama
    `change/rbac-permission-matrix-and-audit-integration-1b-staff-role-matrix`, 735 líneas efectivas contra la punta de 1a): `65affc2` matriz de roles, paridad y nota de
    §5.2. Rojos observados, tres demostraciones revertidas con `cmp`. Pruebas focalizadas en verde en cada rama (1a: 30; 1b: 58). `./mvnw -B verify` sobre el árbol
    combinado: `BUILD SUCCESS` en 20:36 min (kernel 196, unitarias 2 787, integración 821). **Lección de estimación:** el diseño subestimó las pruebas unas cuatro veces
    (1 051 líneas reales frente a 270).

- [x] 2 **PR 2 `role-assignment-and-resolver`: `V14`, puerto `PermissionResolver`, adaptador de solo lectura bajo RLS (D4, D5; O1, O3, O9).**
  - **Archivos.** Crea `app/src/main/resources/db/migration/V14__create_identity_staff_account_role.sql`, `shared/security/PermissionResolver.java`,
    `identity/application/{StaffRoleAssignmentRepository,AccountPermissions}.java`, `identity/infrastructure/JooqStaffRoleAssignmentRepository.java`; modifica
    `identity/infrastructure/wiring/IdentityConfiguration.java` (la factoría devuelve `AccountPermissions`, no el puerto). Pruebas: `schema/V14SchemaDiffIT`,
    `schema/IdentityStaffAccountRoleSchemaIT`, `identity/AccountPermissionsTest`, `identity/AccountPermissionsIT`, `schema/RolePrivilegeMatrixIT` y
    `schema/MultiTenantSchemaIT` (ampliadas), `support/StaffRoleFixtures` (nuevo, con `confia_owner` y `app.institution_id`),
    `support/CommittingPostgresIntegrationTest` (limpieza de la tabla), `architecture/PermissionResolverInventoryTest`.
  - [x] 2.1 **ROJO, ataques primero.** `AccountPermissionsTest` (repositorio doble): cuenta con rol Contabilidad devuelve exactamente su conjunto [AZ17]; sin fila,
    conjunto vacío y sin excepción [AZ18]; código desconocido, vacío [AZ19]; el repositorio lanza y `resolve` lanza, nunca devuelve vacío [AZ21].
    `AccountPermissionsIT` (dos instituciones): `ACC-4` de `INS-1` sin rol y `ACC-4` de `INS-2` Super Admin con token de `INS-1`, vacío [AZ20]; la institución sale
    del actor y una cabecera `X-Institution-Id` ajena no entra al puerto [I285]; la transacción es de solo lectura (`25006` ante un `INSERT`) y está cerrada al
    volver [AZ23, I286]; AZ19 en una `StepwiseDatabase` propia migrada a 14, con la restricción eliminada como `confia_owner` e inserción de `ghost_role`.
    `IdentityStaffAccountRoleSchemaIT` ejecutada como `confia_owner` para que el rechazo sea de la restricción y no de privilegio: segunda asignación con otro rol y
    con el mismo (`23505`) [I278], código fuera del conjunto (`23514`) [I279], cuenta de otra institución (`23503`) [I280], lectura aislada y cero filas sin contexto
    [I281, BI121], cuenta sin fila válida [I282], columnas exactas `institution_id`, `account_id`, `role_code`, `created_at` [I283]. `V14SchemaDiffIT`: de `V13` a
    `V14` solo cambia la tabla, sus índices, restricciones, política y privilegios; sumas de verificación de `V1` a `V13` [BI120]. `MultiTenantSchemaIT`
    sin lista de exclusión [BI119]. `RolePrivilegeMatrixIT`: `confia_admin_app` solo `SELECT`, `confia_readonly` solo `SELECT`, `confia_portal_app` nada,
    `confia_backup` por `pg_read_all_data`, `confia_owner` sin `BYPASSRLS`, con una variante con privilegio de más que debe fallar [BI122, BI123, BI124].
    `PermissionResolverInventoryTest`: exactamente un adaptador, en `identity`; ninguna factoría devuelve el puerto; el puerto no depende de `identity`
    [I284, completada con el único consumidor en la tarea 3]. **Rojos esperados:** `relation "identity_staff_account_role" does not exist`; `No migration
    with a target version 14 could be found`; `cannot find symbol` del puerto.
  - [x] 2.2 **VERDE y REFACTOR.** `V14` con el DDL exacto de D4 (`_pk`, `_account_fk`, `_code_chk`, `ENABLE` y `FORCE ROW LEVEL SECURITY`, política,
    `REVOKE ALL ... FROM PUBLIC`, `SELECT` a `confia_admin_app` y `confia_readonly`); puerto, repositorio, `AccountPermissions` con
    `runner.executeReadOnly(actor.securityContext(requestId), ...)`, `StaffRole.ofCode` y `Set.of()` ante fila ausente o código desconocido; sin caché. jOOQ se
    regenera en la construcción. Sin cambios de `ProcessBeanPolicy` (D18).
  - [x] 2.3 **Demostraciones deliberadas.** (1) Quitar `FORCE ROW LEVEL SECURITY` de `V14` pone en rojo `IdentityStaffAccountRoleSchemaIT` y `MultiTenantSchemaIT`
    [BI119, BI121]. (2) Conceder `DELETE` a `confia_admin_app` pone en rojo `RolePrivilegeMatrixIT` por privilegio de más [BI122, BI124]. (3) Hacer que
    `AccountPermissions` devuelva `Set.of(Permission.values())` ante un error pone en rojo `AccountPermissionsTest` [AZ21]. Se revierten y se confirman con `cmp`.
  - [x] 2.4 **Cierre.** `./mvnw -B verify`. Commits: `feat(db): create the staff account role assignment table with exact grants`, `feat(identity): resolve effective
    permissions per request from the role assignment`. Tamaño: diseño 640 (150 prod., 480 pruebas, 10 doc.). — Escenarios AZ17 a AZ21, AZ23, I278 a I286 y BI119 a BI124.
  - **Cierre 2026-11-04 (evidencia en `apply-progress.md`).** El árbol midió 1 247 líneas efectivas (185 de producción, 1 062 de pruebas; las pruebas fueron unas 2,2 veces
    las 480 del pronóstico), por lo que el propietario aprobó el corte en dos PR. **PR 2a** (rama `change/rbac-permission-matrix-and-audit-integration-2-role-assignment-and-resolver`,
    `969c27f`, 627 líneas efectivas contra `20701ec`): `V14`, `V14SchemaDiffIT`, `IdentityStaffAccountRoleSchemaIT`, ampliaciones de `MultiTenantSchemaIT` y `RolePrivilegeMatrixIT`,
    `StaffRoleFixtures`; solo pruebas focalizadas (74, en verde). **PR 2b** (rama `change/rbac-permission-matrix-and-audit-integration-2b-permission-resolver`, `73ca53b`, 640 líneas
    contra `969c27f`): puerto, repositorio, `AccountPermissions`, adaptador jOOQ, fábrica, `AccountPermissionsTest`, `AccountPermissionsIT`, `PermissionResolverInventoryTest` y
    `UnknownRoleCodeResolutionIT` (AZ19, en `schema` porque `StepwiseDatabase` es de paquete); 11 + 6 pruebas focalizadas en verde. `./mvnw -B verify` sobre la punta de 2b (árbol combinado):
    `BUILD SUCCESS` en 33:33 min (kernel 196, unitarias 2 798 con 3 omitidas preexistentes, integración 842). Rojos observados y tres demostraciones revertidas con `cmp`.
    2b debe fusionarse a continuación de 2a.

## Fase 2: decisión en el filtro y auditoría de denegaciones

- [x] 3 **PR 3 `filter-authorization-and-route-guard`: decisor sobre lista cerrada, `otp`, sesión viva, fallo de resolución, guarda de arranque e inversiones (D1, D6, D7, D13, D18).**
  - **Archivos.** Crea en `shared/web/authorization/`: `PermissionedEndpoint`, `PermissionedEndpoints` (lista de producción **vacía** hasta la tarea 7),
    `PermissionDecider`, `PermissionDeniedException`, `PermissionResolutionFailedException`, `RouteCoverageGuard`, `AuthorizationMetrics` (solo
    `resolutionFailed`); `shared/web/edge/AuthorizationConfiguration`; `shared/observability/metrics/LogAuthorizationMetrics`; `shared/web/problem/DataAccessFailure`
    (extraída de `ProblemExceptionHandler`, mecánica). Modifica `shared/web/edge/SecurityChains` y `AdminSecurityConfiguration`,
    `shared/web/authentication/AccessTokenAuthenticationFilter` (sesión viva derivada de la misma instancia de `PermissionedEndpoints`, diseño D6), `AuthenticatedEndpoints` y `ActorAuthentication` (Javadoc),
    `bootstrap/admin/AdminApplication`, `ObservabilityMetricsConfiguration`, `ProblemExceptionHandler`, y de prueba `bootstrap/ProcessBeanPolicy`.
    Pruebas: `PermissionDeciderTest`, `PermissionEdgeTest`, `RouteCoverageGuardTest`, `OpenApiRouteClassificationTest`, `ProductionRoutesClassifiedIT`,
    `AuthorizationEdgeIT` (primera parte), `PermissionedEndpointTextPermissionCompilationTest`, `architecture/AuthorizationBoundaryRulesTest`,
    `architecture/SharedBoundaryRulesTest`, `architecture/WebEdgeScopeExclusionInventoryTest`, `bootstrap/PublicRouteAllowListTest`, `bootstrap/RegisteredRoutes`,
    `bootstrap/ProcessBeanIsolationTest`, `shared/web/harness/WebEdgeHarness`.
  - [ ] 3.1 **Sonda y precondición.** Confirmar con `git log` que la tarea 2 está fusionada. La primera prueba en rojo confirma la firma
    `AuthorizationManager#authorize(Supplier, RequestAuthorizationContext)` de Spring Security 7.1.1; si difiere, se anota y se adapta sin consultar.
  - [ ] 3.2 **ROJO, ataques primero.** `PermissionEdgeTest` (`HarnessProcess`, lista de rutas de prueba, resolutor y grabador dobles, contador de invocaciones por
    `HandlerInterceptor` de prueba): token `[pwd]` da `403 forbidden` con cero invocaciones del controlador y del resolutor [AZ25]; `[pwd, otp]` con el permiso llega al
    controlador [AZ26]; sin credencial `401 authentication-required` [AZ27]; tokens `mfa-verify` y `mfa-enroll` reciben cada uno `401 token-invalid`, con cero invocaciones del
    controlador y del resolutor [AZ28]; token vigente de sesión revocada `401 token-invalid` con cero invocaciones del resolutor [AZ78]; **prueba de deriva (diseño D6)**
    `PermissionEdgeTest.everyPermissionedEntryRejectsARevokedSession`: recorre **cada** entrada de la lista del arnés (al menos dos, una con variable de ruta) con un token de sesión
    revocada y exige por entrada `401 token-invalid` y cero invocaciones del resolutor [AZ78]; cabecera, parámetro y cuerpo con `identity:read` de un Cajero dan `403` [AZ31]; las cinco
    variantes de ruta no dan `2xx` [AZ32]; `POST`, `PUT`, `PATCH` y `DELETE` no dan `2xx` ni asiento [AZ33]; `/test/accounts/{id}` con `123` y `abc` consulta el resolutor una vez por
    petición (precondición de AZ37, cuyo asiento por petición se prueba en la tarea 4); claim `permissions` da `401 token-invalid` sin permisos ni roles en el principal [AZ24]; resolutor que lanza con `SENTINEL-RESOLVER-6`: respuesta `500
    internal-error` con `traceId`, ni `403` ni `forbidden`, grabador y controlador en cero [WE101, AZ60, AZ63]; el cuerpo, las cabeceras y el registro capturado no contienen el
    mensaje ni la clase [WE102, AZ61]; la métrica `authorization_resolution_failed` se incrementa una vez [AZ63]; tras recuperarse el resolutor, el mismo actor recibe la
    respuesta del controlador [AZ64]; ruta con permiso configurada para responder `200` sin credencial hace fallar la enumeración [WE105]. `PermissionDeciderTest`:
    anónimo, `[pwd]`, concedido, denegado con `PermissionDeniedException` (permiso, plantilla, actor) y fallo envuelto sin causa. `AuthorizationEdgeIT` (primera parte, con
    PostgreSQL): una ruta con permiso de prueba que exige `Idempotency-Key` y un limitador que cuenta: la denegación no toca el interceptor, el limitador, el controlador
    ni `shared_idempotency_key` [WE95]; la respuesta es idéntica con y sin clave [WE96]; con el permiso y sin clave responde `400 idempotency-key-missing` después de la
    decisión [WE97]; el orden de filtros e interceptores [WE94]; `AuthorizationEdgeIT.everyPermissionedEntryRejectsARevokedSession`, la misma prueba de deriva sobre el cableado
    de `AdminSecurityConfiguration` [AZ78]; `AuthorizationEdgeIT.harnessImportsTheAdministrativeConfiguration`: la lista de `@Import` del arnés es la de `AdminApplication` más el
    controlador de prueba (diseño D19). `RouteCoverageGuardTest`: ruta sin clasificar, mensaje con método y plantilla [AZ34]; entrada sin
    ruta registrada [AZ35]; ruta en dos clases, `RouterFunction`, cero rutas evaluadas. `ProductionRoutesClassifiedIT` (`AdminProcessLauncher`): pasa con conjunto no
    vacío [AZ36]. `OpenApiRouteClassificationTest`: falla ante una operación sin clasificar y pasa con el documento real [AZ38].
    `PermissionedEndpointTextPermissionCompilationTest`: el fixture permanente con un texto en lugar de `Permission` no compila (el error esperado es el de tipos
    incompatibles, no el de símbolo ausente) [AZ30]. Enumerador de cuatro clases y tests heredados: `PublicRouteAllowListTest` y `RegisteredRoutes` con ruta con permiso que
    responde `200` sin credencial y ruta sin clasificar [BI135, BI20, WE18 a WE23, WE33, WE80, WE81]; los seis escenarios sin identificador de la cadena administrativa
    (ruta no registrada, ruta registrada fuera de la lista blanca, no distingue existentes, principal autenticado sin permiso, método distinto en ruta pública, variantes) conservan
    su aserción. `WebEdgeScopeExclusionInventoryTest` admite `access` solo desde `SecurityChains` y conserva la ausencia de anotaciones [BI17]; `AuthorizationBoundaryRulesTest`:
    sin `@PreAuthorize`, `@RequirePermission` ni `@EnableMethodSecurity`, con fixture [BI133]; `application` no depende de `PermissionDecider` ni de `PermissionDeniedException`;
    puerto y códigos sin importar módulos, dominio de `identity` importa desde `kernel` [BI131]; `PermissionDecider` único consumidor del puerto [I284]. `ProcessBeanIsolationTest`:
    cada bean nuevo y cada línea de `ProcessBeanPolicy` (`ADMIN` permite `com.confia.shared.web.authorization`; `PORTAL` lo prohíbe con motivo; `WORKER` ya lo cubre) primero en rojo
    con `not in the allow-list` [AZ39, BI128, BI129, BI130]. **Rojos esperados:** el principal con permiso recibe `403` por `denyAll` (no hay entrada); la guarda no existe y el
    arranque no falla; el resolutor que lanza llega a `403`; la sesión revocada llega a `403` en lugar de `401` en cada entrada (el filtro no conoce la lista); clases inexistentes.
  - [ ] 3.3 **VERDE y REFACTOR.** `SecurityChains` gana la sobrecarga que agrega, después de las rutas restringidas y antes de `anyRequest().denyAll()`,
    `requestMatchers(method, pattern).access(decider.forEndpoint(endpoint))`; las otras dos sobrecargas no cambian. `PermissionDecider` (anónimo `DENIED`, sin `otp` `DENIED`
    sin consultar al resolutor, concedido, o `PermissionDeniedException`; la plantilla viene de la entrada coincidente, nunca de la petición); `resolve` registra solo clases y
    `SQLState`, emite la métrica y lanza `PermissionResolutionFailedException` sin causa. `AccessTokenAuthenticationFilter` recibe por constructor la **misma instancia** de
    `PermissionedEndpoints` que `SecurityChains` (un único bean de `AuthorizationConfiguration`, pasado a ambos por `AdminSecurityConfiguration`) y deriva de `endpoints()` sus
    comparadores de sesión viva; no hay segunda lista ni valor `SessionCheck` por entrada.
    `RouteCoverageGuard` como `SmartInitializingSingleton` con el mensaje de método y ruta. Sin `AuditingAccessDeniedHandler` todavía (tarea 4): el manejador de `403` no cambia.
  - [ ] 3.4 **Demostraciones deliberadas.** (1) Quitar la comprobación de `otp` en `PermissionDecider` pone en rojo `PermissionEdgeTest` [AZ25]. (2) Mover la decisión a un
    `HandlerInterceptor` registrado después del de idempotencia pone en rojo `AuthorizationEdgeIT` [WE95]. (3) Dejar `RouteCoverageGuard` sin efecto pone en rojo `RouteCoverageGuardTest`
    [AZ34]. (4) Relanzar la excepción original en lugar de la envoltura sin causa pone en rojo `PermissionEdgeTest` por el centinela en el registro [WE102]. (5) Importar
    `AuthorizationConfiguration` desde `PortalApplication` pone en rojo `ProcessBeanIsolationTest` nombrando bean y paquete [AZ39, BI129]. (6) Derivar los comparadores de sesión viva
    con `endpoints().stream().skip(1)` en `AccessTokenAuthenticationFilter` pone en rojo `everyPermissionedEntryRejectsARevokedSession` en `PermissionEdgeTest` y en `AuthorizationEdgeIT`,
    nombrando la entrada omitida [AZ78]. Se revierten y se confirman con `cmp`.
  - [ ] 3.5 **Cierre.** `./mvnw -B verify`. Commits: `feat(web): decide permissions in the admin filter chain over a closed endpoint list`, `feat(web): fail startup on routes without a
    classification`, `refactor(web): extract the data access failure description for reuse`. Tamaño: diseño 790 (340 prod., 440 pruebas, 10 doc.). **Corte (diseño §10.1):** si supera
    800, la rama `...-route-coverage-guard` (3b) recibe `RouteCoverageGuard`, `RouteCoverageGuardTest`, `OpenApiRouteClassificationTest`, `ProductionRoutesClassifiedIT` y el
    enumerador de cuatro clases [AZ34, AZ35, AZ36, AZ38, BI135]; 3b se fusiona antes de la tarea 4. — Escenarios AZ24 a AZ28, AZ30 a AZ36, AZ38, AZ39, AZ60, AZ61, AZ63, AZ64, AZ78, WE94 a WE97,
    WE101, WE102, WE105, BI128 a BI131, BI133, BI135, BI17, BI20 y los WE conservados (WE18 a WE23, WE33, WE80, WE81 y los seis sin identificador).

- [ ] 4 **PR 4 `permission-denial-audit`: asiento de denegación en transacción propia, tope, ventana, resumen perezoso y métricas (D8 a D11, D19).**
  - **Archivos.** Crea en `shared/web/authorization/`: `AuditingAccessDeniedHandler`, `PermissionDenialRecorder`, `PermissionDenialAudit`, `DenialWindows`,
    `AuthorizationProperties`; modifica `AuthorizationMetrics` (cuatro métodos), `LogAuthorizationMetrics`, `SecurityChains` (el manejador por parámetro),
    `AdminSecurityConfiguration`, `AuthorizationConfiguration`, `app/src/main/resources/application.yml` (`confia.authorization.denials`: `max-full-rows: 10`,
    `window: 5m`, `max-tracked-keys: 10000`), `docs/07-observabilidad-y-operaciones.md` (nota fechada de los cuatro eventos). Pruebas: `DenialWindowsTest`,
    `LogAuthorizationMetricsTest`, `PermissionDenialAuditIT`, `AuthorizationEdgeIT` (segunda parte), `architecture/NoRealTimeInDenialTestsTest`, `architecture/AuthorizationBoundaryRulesTest`
    (ampliada), `DocumentationNotesTest`.
  - [ ] 4.1 **ROJO, ataques primero.** `DenialWindowsTest` (instancia propia, `MutableClock`, concurrencia con `CountDownLatch` o `CyclicBarrier`, límites de prueba por constructor): hasta
    `T` filas completas y después `OMITTED` [AZ52 en unidad]; contadores independientes por cuenta, por permiso y por institución [AZ54]; vencida la ventana, se reinicia [AZ55]; la cota de
    10 claves nunca se supera con 25 cuentas, la clave nueva con el estado lleno da `UNTRACKED` sin expulsar ninguna [AZ56]; `concurrentDenialsOfOneKeyNeverExceedTheCap`: 8 hilos liberados
    por un cerrojo sobre una clave dan exactamente `T` `FULL_ROW` y 5 `OMITTED` [AZ79 en unidad]; `sweepRacingARecordOfTheSameKeyLosesNoDenial`: el barrido y una denegación de una clave vencida
    liberados a la vez conservan la suma; `failedFullRowReturnsTheSlotAndCountsAsOmitted` y `failedFullRowOfAClosedWindowQueuesASummaryOfOne` [AZ80 en unidad];
    `pendingQueueDiscardsBeyondItsBound`; `sweepMovesAtMostItsLimitAndLeavesTheRest` (límite 2, cinco vencidas: 2, 2 y 1); `sweepRunsAtMostOncePerSecond` (la compuerta);
    `farFutureNextSweepInstantIsTreatedAsDue` (barrido a `t`, reloj a `t - 1 h`: barre de inmediato). **Comprobaciones de diseño sin escenario:** **DC-1**
    `backwardClockClosesTheWindowAndKeepsTheSum`: un reloj que retrocede cuenta como ventana vencida, la ventana anterior con omisiones pasa a la cola, se abre una nueva con `full=1` y la suma
    de filas y conteos se conserva (nunca menos filas); **DC-2** el apagado emite los resúmenes pendientes: `closeAllForShutdown(now)` devuelve también las ventanas abiertas con omisiones, y sin
    omisiones no devuelve nada. `AuthorizationBoundaryRulesTest.denialWindowsMappingFunctionsTouchNoOtherMapping`: ningún método sintético `lambda$*` de `DenialWindows` invoca un método de
    `ConcurrentHashMap` ni `sweepIfDue`, con fixture que lo hace y falla (diseño D10).
    `LogAuthorizationMetricsTest`: eventos `WARN` con `event`, `permission` y `suppressed`, uno por segundo y por clave, sin correo, IP ni ruta [AZ58]; cota por construcción.
    `PermissionDenialAuditIT` (`TransactionRunner` real, `MutableClock`, escritor real o envuelto para fallar, institución propia): un solo asiento con `action`, `outcome`,
    `actor_kind`, `actor_id`, `entity_id`, `entity_type` `authorization.permission` y detalle exacto `{"routeTemplate":"/api/v1/identity/roles"}` con el constructor tipado
    [AZ40, AT32]; `request_id` igual al `traceId` [AZ41]; `actor_label` es el identificador y ningún campo contiene el correo [AZ42]; `isActualTransactionActive()` falso al entrar
    el grabador y el asiento existe tras la respuesta [AZ43]; plantilla `/test/accounts/{id}` sin `SENTINEL-ID-9` [AZ44]; el contador decide fuera de la transacción: un
    reintento por `40001` no cuenta dos veces; cadena verificada tras los asientos [AZ45, AT37]; dos instituciones, dos cadenas íntegras [AT34]; resumen: `omittedCount` 2,
    `windowSeconds` 300, cuenta y permiso, sin ruta, consulta, cuerpo, cabeceras, agente de usuario ni IP, **con prueba negativa de origen** (barrido disparado por una petición con IP y agente
    ajenos) [AT35]; 3 completos más conteo 2 igualan 5 [AT36]; `failedFullRowIsCountedInTheSummary`: escritor que falla solo en la primera inserción, 5 denegaciones, 3 completos y resumen de
    conteo 2 [AZ80]; `oneDenialWritesAtMostOneSummary`: con 5 resúmenes pendientes, un escritor envuelto que cuenta registra como mucho 2 inserciones por denegación (su asiento y un resumen) y
    quedan 4 pendientes (diseño D10, latencia acotada); `failedSummaryReturnsToTheHeadAndStopsThePass`: el resumen fallido vuelve primero a la cola, no se intenta otro y la métrica sube una vez;
    `requeueIntoAFullQueueDiscardsAndCounts` (cota de prueba pequeña); **DC-2** `shutdownWritesPendingSummaries`: `destroy()` escribe los resúmenes pendientes antes de cerrar el `DataSource`, y no
    escribe nada si no hay omisiones; `shutdownStopsAtTheFirstFailure`: un solo intento y la métrica por cada resumen no escrito; `shutdownStopsWhenItsBudgetIsSpent`: un escritor envuelto avanza
    el `MutableClock` 4 s por inserción, se escriben 3 y el resto cuenta como no escrito. `AuthorizationEdgeIT` (segunda parte, con `T=3` por propiedad; cada prueba que espera un resumen
    vacía antes la cola compartida con `flushClosedWindows` y comprueba `pendingSummaryCount()` en 0, y después dispara la emisión con a lo sumo tantas denegaciones de otra clave como resúmenes
    pendientes, afirmando solo sobre su propia institución, según el diseño, «Independencia del orden de las pruebas»; `edgeSummaryTestsPassInAnyOrder` corre la clase en tres órdenes aleatorios
    y la demostración deliberada quita el vaciado previo y, después de las cinco denegaciones de AZ53 y justo antes de cerrar su ventana, deja en la cola compartida el resumen de otra institución (cinco denegaciones de esa clave con `T=3` y el reloj pasado del cierre de su ventana, más un barrido): el único disparo de AZ53 escribe ese resumen ajeno y no el propio, AZ53 falla; se revierte y se confirma con `cmp`): `/test/accounts/{id}` con `123` y
    `abc`: dos `403`, exactamente dos asientos, uno por petición, con `entity_id` del permiso de la entrada y plantilla `/test/accounts/{id}`, sin `123` ni `abc` [AZ37];
    `concurrentDenialsOfOneKeyWriteExactlyTFullRows`: 8 peticiones liberadas por un cerrojo, 3 completos, resumen de conteo 5 tras cerrar la ventana y la siguiente denegación de otra clave, ocho
    `403` y cadena íntegra [AZ79]; tres completos y sin resumen [AZ52]; 5 denegaciones, reloj manual hasta cerrar la ventana y
    la siguiente denegación de otra clave: 3 completos, un resumen con conteo 2, métrica `authorization_denial_omitted` incrementada 2 y cinco `403` [AZ53]; acceso concedido sin asiento
    [AZ46]; ruta sin mapear sin asiento [AZ47]; CSRF `csrf-invalid` sin asiento [AZ48]; `404` de objeto sin asiento [AZ49]; `401` sin credencial, vencido y alterado sin asiento [AZ50]; falla
    de resolución sin asiento ni consumo del tope: 10 fallos y luego 3 denegaciones escriben 3 completas [AZ62]; (AZ60 se completa aquí: sin asiento y contador sin variar); ruta
    con permiso frente a ruta sin mapear: un asiento y ninguno [WE98], respuestas idénticas salvo `instance` y `traceId` [WE99], y con escritor que lanza también [WE100]; escritor
    roto: `403 forbidden`, cero invocaciones, métrica `authorization_denial_audit_failed` una vez y cuerpo sin mensaje ni clase [AZ59]; los casos no auditados [AT38]; búsqueda de
    centinelas `SENTINEL-PATH-1`, `SENTINEL-QUERY-2`, `SENTINEL-BODY-3`, `SENTINEL-HEADER-4` y el token en `shared_audit_log`, registros con la raíz en `DEBUG` y doble de métricas
    [AZ51, AT33]; las pruebas del tope corren en cualquier orden y repetidas [AZ57]. `NoRealTimeInDenialTestsTest`: ninguna clase nombrada refiere `Thread.sleep`, `Instant.now()` ni
    `Clock.systemUTC()`, con fixture [BI137]; tres ejecuciones en orden aleatorio (`-Dsurefire.runOrder=random`) con resultado idéntico, registradas [BI138]. **Rojos esperados:**
    `cannot find symbol` de `DenialWindows`; `expected 1 audit entry but found 0` (el manejador actual no escribe); AZ37 con `expected 2 audit entries but found 0`.
  - [ ] 4.2 **VERDE y REFACTOR.** `AuditingAccessDeniedHandler` decora a `ProblemAccessDeniedHandler` solo en la cadena administrativa y registra únicamente
    `PermissionDeniedException` antes de responder; `PermissionDenialAudit` implementa el grabador (`record` nunca lanza; captura `RuntimeException`, incrementa la métrica una vez y no
    registra la excepción); `runner.execute(actor.securityContext(requestId), ...)` con `AuditLogWriter` y `AuditJson`; resumen con el contexto de la institución de la ventana, un
    `requestId` nuevo y `ScopedValue.where(RequestOrigin.CURRENT, new RequestOrigin(summaryRequestId, null, null))`; `DisposableBean.destroy` con corte en el primer fallo y presupuesto de
    10 s por el `Clock` inyectado; `DenialWindows` según diseño D10: `compute` solo sobre la propia clave, barrido fuera de toda función de asignación con un bucle `for` y `computeIfPresent`
    por clave, compuerta `AtomicLong` con la regla del instante futuro, como mucho 500 ventanas por barrido, cola `LinkedBlockingDeque` de 10 000, reserva con compare-and-set, `fullRowFailed`
    y `untrackedRowFailed` invocados por `PermissionDenialAudit` al fallar un asiento, y `afterDenial` que escribe como mucho un resumen; `AuthorizationProperties` validada en el constructor. Sin líneas nuevas de `ProcessBeanPolicy` (el paquete ya está permitido;
    `ProcessBeanIsolationTest` lo confirma). Nota fechada de `docs/07` con los cuatro eventos.
  - [ ] 4.3 **Demostraciones deliberadas.** (1) No volver a enlazar `RequestOrigin.CURRENT` al escribir el resumen pone en rojo `PermissionDenialAuditIT`: el resumen lleva la IP ajena
    [AT35]. (2) Mover `windows.record` dentro de la lambda del `TransactionRunner` pone en rojo la prueba del reintento: el conteo se duplica [AZ52, AZ53]. (3) Quitar el `catch` de
    `PermissionDenialAudit.record` pone en rojo `AuthorizationEdgeIT`: llega un `500` [AZ59, WE100]. (4) Comparar `full <= T` en lugar de `full < T` pone en rojo `DenialWindowsTest`
    [AZ52]. (5) Quitar la rama de reloj retrocedido pone en rojo la comprobación **DC-1**. (6) Dejar `destroy()` vacío pone en rojo la comprobación **DC-2**. (7) Agregar `Thread.sleep` a
    un fixture del tope pone en rojo `NoRealTimeInDenialTestsTest` [BI137]. (8) Invocar `sweepIfDue` desde la función de `record` pone en rojo
    `denialWindowsMappingFunctionsTouchNoOtherMapping`, nombrando el método sintético [D10]. (9) Escribir toda la cola en `afterDenial` pone en rojo `oneDenialWritesAtMostOneSummary`: seis
    inserciones. (10) Quitar la regla de distancia de la compuerta pone en rojo `farFutureNextSweepInstantIsTreatedAsDue`. (11) No invocar `fullRowFailed` pone en rojo
    `failedFullRowIsCountedInTheSummary`: el resumen cuenta 1 [AZ80]. (12) Quitar el límite por barrido pone en rojo `sweepMovesAtMostItsLimitAndLeavesTheRest`. (13) Devolver el resumen fallido
    con `offerLast` pone en rojo `failedSummaryReturnsToTheHeadAndStopsThePass`. (14) Quitar el presupuesto del apagado pone en rojo `shutdownStopsWhenItsBudgetIsSpent`. La demostración (4)
    también pone en rojo `concurrentDenialsOfOneKeyNeverExceedTheCap` [AZ79]; una pérdida de actualización por `get` y `put` no se usa, porque su intercalado no es determinista. Se revierten y se
    confirman con `cmp`.
  - [ ] 4.4 **Cierre.** `./mvnw -B verify`. Commits: `feat(web): audit permission denials with a bounded in-memory cap and a window summary`, `feat(observability): publish the
    authorization events through a port`, `docs(ops): note the four authorization events`. Tamaño: diseño 780 (340 prod., 420 pruebas, 20 doc.). **Corte (diseño §10.1):** si supera 800, la
    rama `...-authorization-metrics` (4a) recibe `AuthorizationMetrics` completo, `LogAuthorizationMetrics`, `LogAuthorizationMetricsTest` y la nota de `docs/07` con su prueba [AZ58 en
    unidad]; 4b conserva el resto. La corrección del 2026-11-04 agrega pruebas a esta rebanada, de modo que el corte 4a/4b es probable; se mide antes de abrir el PR. — Escenarios AZ37, AZ40 a
    AZ59, AZ62, AZ79, AZ80, WE98 a WE100, AT32 a AT38, BI137, BI138, **DC-1** y **DC-2**.

## Fase 3: MFA derivada de una política

- [ ] 5 **PR 5a `mfa-policy-derivation`: `MfaPolicy.STAFF`, inicio de sesión, segundo factor y restablecimiento sin leer `mfa_required` (D14, D15 paso 1; O4, Q1).**
  - **Archivos.** Crea `identity/domain/MfaPolicy.java`; modifica `identity/domain/StaffAccount.java` (sin el campo), `AuthenticationResult` (Javadoc),
    `identity/application/{AuthenticateWithPassword,ResetPasswordWithToken}.java`, `identity/infrastructure/JooqStaffAccountRepository.java` (deja de leer `getMfaRequired()`),
    `identity/infrastructure/wiring/IdentityConfiguration.java` (registra `MfaPolicy.STAFF`), `bootstrap/ProcessBeanPolicy.java` (`ADMIN` permite `com.confia.identity.domain`),
    `docs/03-seguridad.md` §4.3 (nota fechada). Pruebas: `MfaPolicyTest`, `MfaPolicyProductionWiringTest`, `SecondFactorExemptions` y `SecondFactorExemptionsConfiguration` (en `src/test`), `identity/web/SecondFactorExemptionsProbeIT`, `identity/web/LoginEndpointSupport`,
    `identity/web/LoginProcess`, `StaffAccountTest`, `AuthenticateWithPassword*`, `ResetPasswordWithToken*`, `DocumentationNotesTest`. **Los fixtures siguen insertando la columna**
    (`NOT NULL` sin `DEFAULT`, `V6` no se edita); el parámetro `mfaRequired` de los ayudantes pasa a alimentar `SecondFactorExemptions`.
  - [x] 5.1 **Sonda S1 (diseño D14).** Primera prueba de la tarea, `identity/web/SecondFactorExemptionsProbeIT`. El proceso corre en la misma JVM (`LoginProcess.start` →
    `AdminProcessLauncher.launch` → `ConfiaApplication.launch`). `LoginProcess` agrega `spring.main.sources` con `SecondFactorExemptionsConfiguration` (de `src/test`), que declara un
    `@Bean @Primary SecondFactorExemptions` con un conjunto de instancia (`ConcurrentHashMap.newKeySet()`), sin ningún campo `static`; `LoginProcess.exemptions()` lo obtiene con
    `context.getBean(...)` y `LoginEndpointSupport` gana `abstract SecondFactorExemptions exemptions()`. Orden: el proceso arranca por clase, cada prueba siembra la fila, registra la exención y
    solo después inicia sesión. La sonda afirma, en un mismo proceso con la configuración, que una cuenta **eximida** con contraseña correcta obtiene la sesión y sus cookies (`Authenticated`,
    como I149) y que una cuenta **no eximida** con credencial verificada obtiene el token restringido (`SecondFactorRequired`); que, en un segundo proceso con la configuración, la cuenta eximida en
    el primero y no registrada en el segundo obtiene `SecondFactorRequired` (sin estado compartido entre contextos); y, en un proceso sin la configuración, que el contexto no contiene
    `SecondFactorExemptions` y que su único `MfaPolicy` es `STAFF`. **Rojo esperado:** `NoSuchBeanDefinitionException` de `SecondFactorExemptions` (la configuración no existe). Si
    `ConfiaApplication.launch` no respeta `spring.main.sources`, la alternativa es un `BeanPostProcessor` agregado por un `ApplicationContextInitializer` de prueba, con el mismo conjunto de
    instancia y la misma sonda, **nunca un parámetro nuevo en producción**; se anota el resultado.
  - [ ] 5.2 **ROJO, ataques primero.** Con la política real y filas con `mfa_required=false` en el fixture: cuenta con rol Contabilidad y credencial verificada da `SecondFactorRequired`
    [I287]; cuenta sin rol y sin credencial da `SecondFactorEnrollmentRequired`, nunca `Authenticated` [I288]; quitar la asignación no desactiva la exigencia [I289]; los seis roles dan
    `SecondFactorRequired` [I290]; el restablecimiento sin segundo factor se rechaza para una cuenta sin rol con credencial verificada [I291]; cuenta sin rol y con credencial verificada
    exige el código [I301]; con la política real ni la cuenta con rol ni la cuenta sin rol obtienen `Authenticated` [I302]. `MfaPolicyProductionWiringTest` (proceso real, en Surefire por su sufijo `Test`): el bean es `STAFF`,
    `ArchUnit` afirma que `STAFF` es la única definición de producción y que el trabajador y el portal no registran el bean [I302, BI129 parte de la política]. Las pruebas migradas que
    esperan `Authenticated` pasan a declarar su exención con `SecondFactorExemptions` con la columna insertada en `true`: **rojo** `expected Authenticated but was SecondFactorRequired`
    (I149, I156, I228 y la traducción a `200`). Resto de escenarios modificados: desenlaces de cuenta con credencial verificada y sin credencial, `I217`, `Authenticated` sin campo de alcance con
    política de prueba, `Rejected` con motivo interno, los cuatro desenlaces del tipo cerrado, el `switch` exhaustivo del fixture permanente, `SecondFactorRequired` sin avance del retroceso,
    restablecimiento con TOTP o código de recuperación, restablecimiento sin MFA activa, I218, I150 a I155, I167 a I169. **Rojos esperados:** `MfaPolicy` inexistente; I288 e I291 reciben
    `Authenticated` porque la columna vale `false`.
  - [ ] 5.3 **VERDE y REFACTOR.** `MfaPolicy` con `requiresSecondFactor(StaffAccountId)` y `STAFF = account -> true`; `AuthenticateWithPassword.outcomeForValidCredentials` y
    `ResetPasswordWithToken.mfaActive` reciben la política y dejan de leer `account.mfaRequired()`; `StaffAccount` pierde el campo; `JooqStaffAccountRepository` deja de leerlo; la política no
    lee ningún rol ni propiedad. Nota fechada de `docs/03` §4.3 (MFA obligatoria y derivada). La construcción pasa **sin** `V15` [BI127].
  - [ ] 5.4 **Demostraciones deliberadas.** (1) Hacer `STAFF = account -> false` pone en rojo `MfaPolicyTest`, `MfaPolicyProductionWiringTest` e I288 (y debe morir bajo PIT). (2) Condicionar la
    exigencia a la existencia de una asignación de rol en `AuthenticateWithPassword` pone en rojo I288 e I301. (3) Devolver `Authenticated` para una cuenta sin rol pone en rojo I302. Se
    revierten y se confirman con `cmp`. (4) Guardar las exenciones en un campo `static` de `SecondFactorExemptions` (código de prueba) pone en rojo la sonda S1: en el segundo proceso la cuenta
    no registrada hereda la exención del primero y recibe `Authenticated`. Se restaura desde la copia y se confirma con `cmp`.
  - [ ] 5.5 **Cierre.** `./mvnw -B verify` y registro en `apply-progress.md` de que esta construcción pasa sin `V15` (insumo de [BI127]). Commits: `refactor(identity): derive the second factor
    requirement from MfaPolicy instead of a column`, `docs(security): note the mandatory derived MFA in section 4.3`. Tamaño: diseño 570 (70 prod., 480 pruebas, 20 doc.). — Escenarios I287 a I291, I301,
    I302 y los modificados de MFA, inicio de sesión y restablecimiento (I149 a I156, I217, I218, I228, I167 a I169 y los 10 sin identificador de esos requisitos) listados en la trazabilidad.
    Los otros 4 modificados sin identificador del delta de `identity` («Permiso presente por el rol asignado», «Cliente afirma un permiso que la cuenta no tiene», «El único camino para cambiar
    una contraseña es el token» y «Un restablecimiento sin token válido nunca cambia la contraseña») son de la tarea 7.

- [ ] 6 **PR 5b `drop-mfa-required-column`: `V15` y retiro de la columna de 43 archivos de prueba (D15 pasos 2 y 3).**
  - **Archivos.** Crea `app/src/main/resources/db/migration/V15__drop_identity_staff_account_mfa_required.sql` (solo `ALTER TABLE identity_staff_account DROP COLUMN mfa_required`).
    Modifica los 43 archivos de prueba que insertan la columna, salvo los fijados a una versión histórica, los ayudantes de prueba (el parámetro pasa a `exemptFromSecondFactor`) y elimina de
    `schema/MultiTenantSchemaIT` la prueba `mfaRequiredIsFixedAtAccountCreation...`. Pruebas nuevas: `schema/V15SchemaDiffIT`, `architecture/MfaRequiredAbsenceTest`.
  - [x] 6.1 **Inventario primero (D15 paso 3).** Registrar en `apply-progress.md` las pruebas que migran a una versión anterior a 15 con `StepwiseDatabase.migratedTo(...)` e insertan cuentas
    (`V9ExistingRowsIT`, `RetentionPurgeFunctionsIT`, `RefreshTokenFamilyPurgeFunctionIT`) y si `schema/PurgeSession` (usada por cuatro pruebas) atiende a ambas versiones; si es así se
    separa su inserción. También `getMfaRequired()` de `JooqStaffAccountRepository`, que la búsqueda de texto no encuentra: confirmar que la tarea 5 lo retiró.
  - [ ] 6.2 **ROJO.** `V15SchemaDiffIT`: de `V14` a `V15` la única diferencia es la columna `mfa_required` [BI125]. `MfaRequiredAbsenceTest`: la columna no existe, el código de jOOQ generado
    no la contiene y no hay aparición de `mfa_required`, `MFA_REQUIRED`, `mfaRequired` ni `getMfaRequired` sin distinguir mayúsculas, salvo `V6`, `V15` y las pruebas fijadas con `migratedTo`
    a una versión anterior a 15; con un fixture que reinserta una referencia falla señalando el archivo [I292, BI126]. Verificación documental del orden: `V15` es posterior a la tarea 5
    fusionada [BI127]. Regresión: las puertas genéricas, la lectura aislada y el contexto vacío de las cuatro tablas de `V6` sobre el esquema migrado (tres escenarios sin identificador).
    **Rojos esperados:** `No migration with a target version 15 could be found`; la búsqueda de `MfaRequiredAbsenceTest` falla y enumera los archivos. **Línea base medida** el 2026-11-04 en
    `6b48d1b`, con el árbol limpio, sin distinguir mayúsculas y solo sobre archivos versionados de `apps/` (búsqueda con ripgrep, que respeta `.gitignore`, equivalente a `git grep -il` porque
    no había archivos sin seguimiento): `mfa_?required` aparece en **49 archivos**, que son `V6`, 5 archivos Java de producción (`StaffAccount`, `AuthenticationResult`,
    `AuthenticateWithPassword`, `ResetPasswordWithToken` y `JooqStaffAccountRepository`) y **43 de prueba**; el literal de `git grep -il mfa_required` da **44** (`V6`, 3 de producción y 40 de
    prueba), porque no ve `mfaRequired` ni `getMfaRequired`. Después de la tarea 5 no debe quedar ningún archivo Java de producción, de modo que el rojo esperado son los archivos de prueba de
    esa línea base que la tarea 5 no tocó, salvo los fijados con `migratedTo` (exceptuados), más el código de jOOQ generado, que no está versionado y solo ve la prueba. El ejecutor vuelve a
    medir con `git grep -il -E "mfa_?required" -- apps` antes del rojo y registra en `apply-progress.md` la orden, el recuento y la lista; una aparición en producción Java es otra causa y
    detiene la tarea.
  - [x] 6.3 **VERDE y REFACTOR.** Crear `V15`; regenerar jOOQ en la construcción; quitar la columna de los 43 archivos (cambio mecánico); renombrar el parámetro de los ayudantes; eliminar la
    prueba de creación de la cuenta con la columna. Las pruebas fijadas a una versión histórica conservan la columna.
  - [x] 6.4 **Demostraciones deliberadas.** (1) Reinsertar `mfa_required` en un ayudante de fixture actual pone en rojo `MfaRequiredAbsenceTest` [BI126]. (2) Agregar a `V15` una segunda sentencia
    que altere otra columna de la misma tabla pone en rojo `V15SchemaDiffIT` [BI125]. Se revierten y se confirman con `cmp`.
  - [ ] 6.5 **Cierre.** `./mvnw -B verify` (PIT no muta SQL; el umbral se mide en el CI sobre el código vigente). Commits: `feat(db): drop the mfa_required column now derived by MfaPolicy`,
    `test(identity): remove the column from the staff account fixtures`. Tamaño: diseño 345 (5 prod., 340 pruebas); sin costura. — Escenarios I292, BI125 a BI127 y los tres de `V6`.

## Fase 4: ruta real, criterio 2 y cierre

- [ ] 7 **PR 6 `roles-report-and-criterion-2`: `GET /api/v1/identity/roles`, criterio de salida 2, inversiones de inventarios, `docs/09`, notas de `docs/03` y registro del cambio 8b (D16, D17, D18, D19).**
  - **Archivos.** Crea `identity/application/{ListRolePermissions,RolePermissionsView}.java`, `identity/web/{RolesReportController,RolesReportResponse}.java`; modifica
    `shared/web/authorization/PermissionedEndpoints.java` (`forAdmin()` con exactamente `GET /api/v1/identity/roles` e `identity:read`), `IdentityConfiguration`, `IdentityWebConfiguration`,
    `apps/api/openapi/admin.openapi.json` (instantánea de ocho operaciones con `x-required-permission`), `packages/contracts` (regenerado con orval, no se edita a mano), `docs/09-roadmap-y-fases.md`,
    `docs/03-seguridad.md` (la última nota de §5), `openspec/changes/foundations-plan/exploration.md` (nota de la división 8 / 8b), `openspec/changes/rbac-permission-matrix-and-audit-integration/`
    (registro del cambio 8b). Pruebas: `ListRolePermissionsTest`, `PermissionedEndpointsTest`, `RolesReportEndpointIT`, `AuthorizationEdgeIT` (tercera parte), `ExitCriterion2EndToEndIT`,
    `OpenApiContractSnapshotTest`, `AuthorizationScopeExclusionInventoryTest` (nuevo), `IdentityScopeExclusionInventoryTest`, `AuditScopeExclusionInventoryTest`,
    `WebEdgeScopeExclusionInventoryTest`, `PublicRouteAllowListTest`, `RegisteredRoutes`, `WebExposedTypesRuleTest`, `DocumentationNotesTest`.
  - [ ] 7.1 **Sonda.** Verificar que orval tolera la extensión `x-required-permission`; si la rechaza, la alternativa es llevar el permiso en la descripción de la operación y ajustar [BI139]
    (se anota y se informa). Registrar en `apply-progress.md` que `packages/contracts` se regeneró sin diferencias comprometidas.
  - [ ] 7.2 **ROJO, informe.** `ListRolePermissionsTest`: seis roles en orden de catálogo y permisos ordenados por código [I294, I296]. `RolesReportEndpointIT` (PostgreSQL real, tokens reales): Auditor
    recibe `200` con `Cache-Control: no-store` [I293]; el cuerpo tiene la forma `{"roles":[{"code":"...","permissions":["..."]}]}` con los seis roles y los permisos de la matriz, el Cajero con la
    solicitud de anulación y sin `invoicing:void` [I294]; ningún correo, nombre ni identificador de cuenta o de institución [I295]; dos solicitudes idénticas byte a byte [I296]; Cajero,
    Contabilidad y Coordinador Académico reciben `403 forbidden`, cero invocaciones y un asiento cada uno con `entity_id` `identity:read` [I297]; sin `Idempotency-Key` responde `200` y no crea marcador
    [I298]; el DTO no expone entidades ni registros de jOOQ [I299]; el rol asignado en el servidor autoriza al Auditor y se ignoran `claimedPermission`, cabecera y parámetro de un Cajero
    (escenarios «Permiso presente por el rol asignado» y «Cliente afirma un permiso que la cuenta no tiene»). `PermissionedEndpointsTest`: la lista de producción es exactamente esa entrada [AZ29].
    Enumeración: la ruta figura solo en la lista de permisos y ninguna en dos clases [WE104]; sin credencial responde `401 authentication-required` [WE103]; contenido exacto de las cuatro clases [WE79].
    `OpenApiContractSnapshotTest`: ocho operaciones, la del informe con `bearerAuth`, `x-required-permission: identity:read` y solo `200`, `401`, `403` y `500`, sin otra operación con permiso;
    la respuesta genérica no aprobada falla; `errors` en `ProblemDetail`; el portal sigue vacío; las operaciones de cookie y de segundo factor conservan sus respuestas [BI24, BI25, BI26, BI27, BI59, BI139].
    Tokens: ninguna claim de rol ni `permissions` y `jti` distinto [I58 a I62, I303]. **Rojos esperados:** `403` por `denyAll` en la ruta real (sin entrada); la instantánea de siete operaciones no coincide.
  - [ ] 7.3 **ROJO, criterio 2.** `AuthorizationEdgeIT` (tercera parte, sobre ruta de prueba y reloj inyectado) y `ExitCriterion2EndToEndIT` (`AdminProcessLauncher`, sin dobles, institución propia, TOTP
    calculado por un ayudante de prueba): Cajero con contraseña y TOTP recibe `403 forbidden` en `GET /api/v1/identity/roles`, cero invocaciones y exactamente un asiento `denied` con `entity_id`
    `identity:read` y `request_id` igual al `traceId` [AZ65]; Auditor recibe `200` y ningún asiento de denegación [AZ66]; el verificador de cadena sigue íntegro [AZ67]; centinelas en consulta y
    cabeceras ajenas no aparecen en `shared_audit_log` ni en los registros [AZ68]; escritor roto: sigue el `403`; con `T=2`, la tercera denegación no escribe fila completa, incrementa
    `authorization_denial_omitted` y, al cerrar la ventana con el reloj inyectado y producirse otra denegación, existe el resumen con conteo 1 [AZ69]; aislada, repetida y tras otra prueba que denegó a la
    misma cuenta da el mismo resultado [AZ70]; cambio de rol de Auditor a Cajero y repetición con el mismo token: `403` sin esperar a que venza [AZ22]. AZ65 a AZ68, AZ70 y AZ22 se ejecutan en dos niveles:
    arnés con contador y proceso real (mismo cableado de producción). **AZ69 se prueba solo en el arnés** (`AuthorizationEdgeIT`, con el `MutableClock` que inyecta su `BeanPostProcessor` y el escritor
    envuelto): el proceso real no admite reloj manual ni escritor roto sin un bean de prueba (diseño D19), y `harnessImportsTheAdministrativeConfiguration` (tarea 3) impide que el arnés se aparte del
    proceso real. Orden aleatorio, tres veces [BI138 se extiende].
  - [ ] 7.4 **ROJO, inversiones y ausencias.** `AuthorizationScopeExclusionInventoryTest` con control negativo: ninguna ruta de gestión de roles, cuentas ni bitácora, ni inscripción de MFA con sesión completa [AZ74];
    ningún caso de uso, migración ni tarea inserta asignaciones y ninguna migración inserta cuentas [AZ75]; la cadena del portal sin roles, permisos ni lista [AZ76]; sin flujo de solicitud o aprobación de
    anulación, alcance dentro de un permiso, tarea de integridad de MFA, informe de segregación ni cliente de Redis para el tope [AZ77]. `IdentityScopeExclusionInventoryTest`: ningún caso de uso asigna o cambia
    roles ni crea el primer Super Admin [I300]; las ausencias de desactivación, `admin_revoke`, inscripción voluntaria de MFA y restablecimiento administrativo conservan su aserción y nombran al cambio 8b
    [I215, I216 y los dos sin identificador], con fixture de desactivación que falla [BI136]. `AuditScopeExclusionInventoryTest`: `audit:read` existe en el catálogo y ningún endpoint expone la bitácora; la
    política de fila filtra solo por institución [BI136, y los dos de `audit-trail` sin identificador], con fixture de endpoint que falla. `WebEdgeScopeExclusionInventoryTest`: aserción positiva de la lista
    de permisos con conjunto no vacío y sin la lista falla, con fixture de anotación que falla [BI134]. Regresión de `IdentityScopeExclusionInventoryTest`, `EmptyShouldExceptionInventoryTest` y
    `ProblemCatalogCoverageTest` [BI16, BI18, BI19]. `DocumentationNotesTest`: todas las notas fechadas, con originales intactos, en `docs/03` §4.3 a §5.3, `docs/07`, `docs/09`, ADR-0005 y el plan [AZ71];
    `docs/09` marca el criterio 2 y nombra `ExitCriterion2EndToEndIT` [AZ73]. **Rojos esperados:** inventarios con el texto anterior; `docs/09` sin marcar.
  - [ ] 7.5 **VERDE y REFACTOR.** `ListRolePermissions.execute()` sin transacción; controlador con `operationId = "listRolePermissions"`, etiqueta `identity-roles`, `@SecurityRequirement(bearerAuth)`,
    `x-required-permission` y respuestas `200`, `401`, `403`, `500`; sin `Idempotency-Key` ni limitador; entrada en `forAdmin()`; instantánea actualizada de forma visible en el diff; contratos regenerados;
    inversiones; `docs/09` con la prueba de cierre; nota del plan de cimientos con la división 8 / 8b; ADR-0027 permanece **Propuesto** hasta la aprobación del propietario.
  - [ ] 7.6 **Registro del cambio 8b.** Dejar anotado en la nota de `foundations-plan/exploration.md` y en `docs/03` el cambio **8b** con su lista: asignación de roles por endpoint y regla «un usuario nunca
    modifica su propio rol», desactivación de cuentas y motivo `admin_revoke` (inversión de `AccountDeactivationAbsenceTest`), inscripción y retirada voluntarias de MFA (inversión de
    `NoFullSessionMfaRoutesTest`), reautenticación reforzada (*step-up*), restablecimiento administrativo de contraseña, endpoint de lectura de la bitácora con `audit:read`, creación del primer Super Admin
    sin secreto en migración ni repositorio, y la revisión del riesgo aceptado DA-15 (Q2), más la revocación de sesiones al cambiar un rol y la revisión del rechazo sin `otp` (D12) si 8b introduce rutas de
    elevación. Los iniciadores sin permiso de §5.3 (ajuste manual del libro mayor, baja por incobrable y reembolso) quedan como pendientes de los cambios que construyan cada operación (Q4).
  - [ ] 7.7 **Demostraciones deliberadas.** (1) Quitar la entrada de `PermissionedEndpoints.forAdmin()` pone en rojo `RolesReportEndpointIT` y `ExitCriterion2EndToEndIT`: `403` sin asiento [AZ29, AZ65, AZ66].
    (2) Agregar un correo al DTO de `RolesReportResponse` pone en rojo la búsqueda de datos personales [I295]. (3) Agregar un endpoint de bitácora a un fixture pone en rojo
    `AuditScopeExclusionInventoryTest` [BI136]. (4) Desmarcar el criterio 2 en `docs/09` pone en rojo `DocumentationNotesTest` [AZ73]. Se revierten y se confirman con `cmp`.
  - [ ] 7.8 **Cierre.** `./mvnw -B verify`. Commits: `feat(identity): report which role can do what through GET /api/v1/identity/roles`, `test(web): prove exit criterion 2 end to end with controls`,
    `docs(roadmap): mark exit criterion 2 of phase 0 and record change 8b`. Tamaño: diseño 720 (150 prod., 520 pruebas, 50 doc.). **Costura propuesta:** 7a (informe, entrada de producción, instantánea y
    contratos) / 7b (criterio 2, inversiones, `docs/09`, notas y registro de 8b); 7b se fusiona al final. — Escenarios de la fila 7 de la trazabilidad.
  - [ ] 7.9 **Verificación final del cambio (propietario).** Después de fusionar la cadena: `./mvnw verify -Pmutation-gate` completo en `main` (cobertura global de `app` al menos 80, `kernel` y `domain` al
    menos 95, PIT al menos 80 sobre `kernel` y `domain`, con `MfaPolicy.STAFF` mutado a falso muriendo); `rg -n "mfa_required" apps/api` solo en `V6`, `V15` y pruebas fijadas a versión; `rg -n
    "@PreAuthorize|@RequirePermission|@EnableMethodSecurity" apps/api/app/src/main` sin resultados; migraciones `V1` a `V13` sin cambios (suma de verificación); lista de «Definición de terminado» de
    `CLAUDE.md` marcada; todas las demostraciones con su `cmp` registrado. Después, `sdd-verify` y `sdd-archive`.

---

## Trazabilidad: escenario a tarea (cada uno en exactamente una)

Los escenarios modificados sin identificador se nombran por su título abreviado. WE33 aparece dos veces en el delta de `web-edge` y se cuenta una vez. «Prueba» es la clase principal; la lista completa está en cada tarea.

| Tarea (PR) | Escenarios | Prueba principal | N |
|---|---|---|---|
| 1 (PR 1) | AZ01 a AZ16, AZ72, BI132 | `PermissionTest`, `StaffRoleTest`, `PermissionMatrixParityTest`, `DocumentationNotesTest`, `DomainPurityRulesTest` | 18 |
| 2 (PR 2) | AZ17 a AZ21, AZ23, I278 a I286, BI119 a BI124 | `AccountPermissionsTest/IT`, `IdentityStaffAccountRoleSchemaIT`, `V14SchemaDiffIT`, `RolePrivilegeMatrixIT`, `PermissionResolverInventoryTest` | 21 |
| 3 (PR 3) | AZ24 a AZ28, AZ30 a AZ36, AZ38, AZ39, AZ60, AZ61, AZ63, AZ64, AZ78; WE94 a WE97, WE101, WE102, WE105; BI17, BI20, BI128 a BI131, BI133, BI135; conservados: WE18 a WE23, WE33, WE80, WE81 y seis sin identificador («Ruta no registrada sin credencial», «Ruta registrada fuera de la lista blanca», «La respuesta no distingue rutas existentes de inexistentes», «Principal autenticado sin permiso», «Método distinto al permitido en una ruta pública», «Variantes de la ruta no eluden la denegación») | `PermissionEdgeTest`, `AuthorizationEdgeIT`, `RouteCoverageGuardTest`, `PublicRouteAllowListTest`, `AuthorizationBoundaryRulesTest`, `ProcessBeanIsolationTest` | 49 |
| 4 (PR 4) | AZ37, AZ40 a AZ59, AZ62, AZ79, AZ80; WE98 a WE100; AT32 a AT38; BI137, BI138; DC-1, DC-2 | `DenialWindowsTest`, `PermissionDenialAuditIT`, `AuthorizationEdgeIT`, `LogAuthorizationMetricsTest`, `NoRealTimeInDenialTestsTest`, `AuthorizationBoundaryRulesTest` | 38 |
| 5 (PR 5a) | I287 a I291, I301, I302; I149 a I156, I228, I167 a I169, I217, I218; sin identificador: «Cuenta del personal con secreto TOTP ya inscrito y verificado», «Cuenta del personal sin ningún secreto TOTP inscrito», «Contraseña correcta produce el desenlace `Authenticated` sin campo de alcance», «Toda causa de rechazo produce el desenlace `Rejected`», «Los cuatro desenlaces son exactamente los que el tipo cerrado permite», «Un desenlace no manejado rompe la compilación», «`SecondFactorRequired` no avanza el contador de retroceso», «Sin segundo factor no hay restablecimiento…», «Un código de recuperación de MFA sustituye al código TOTP», «Una cuenta sin secreto restablece solo con el enlace» | `MfaPolicyTest`, `MfaPolicyProductionWiringTest`, `LoginEndpointIT`, `AuthenticateWithPassword*`, `ResetPasswordWithToken*` | 31 |
| 6 (PR 5b) | I292, BI125 a BI127; V6 sin identificador: «Las puertas genéricas de esquema pasan sobre cada tabla nueva…», «Una institución no lee la llave de datos de otra», «Sin contexto de institución, la consulta devuelve cero filas…» | `V15SchemaDiffIT`, `MfaRequiredAbsenceTest`, `MultiTenantSchemaIT` | 7 |
| 7 (PR 6) | AZ22, AZ29, AZ65 a AZ71, AZ73 a AZ77; I293 a I300, I303, I58 a I62, I215, I216; sin identificador: «Permiso presente por el rol asignado», «Cliente afirma un permiso que la cuenta no tiene», «El único camino para cambiar una contraseña es el token», «Un restablecimiento sin token válido nunca cambia la contraseña»; WE79, WE103, WE104; BI16, BI18, BI19, BI24 a BI27, BI59, BI134, BI136, BI139; audit-trail sin identificador: «La política de fila filtra solo por institución…», «Ningún endpoint expone la bitácora con el permiso `audit:read`» | `RolesReportEndpointIT`, `ExitCriterion2EndToEndIT`, `AuthorizationEdgeIT`, `OpenApiContractSnapshotTest`, inventarios de ausencia, `DocumentationNotesTest` | 50 |
| | **Total** (146 escenarios nuevos, 66 modificados o conservados y 2 comprobaciones de diseño) | | **214** |

**Escenarios sin tarea:** ninguno. **Comprobaciones de diseño sin escenario (unitarias, tarea 4):** **DC-1** un retroceso del reloj cuenta como ventana vencida (`DenialWindowsTest`); **DC-2** el apagado emite los
resúmenes pendientes (`DenialWindowsTest` sobre `closeAllForShutdown` y `PermissionDenialAuditIT.shutdown*`). Las pruebas de D10 sin escenario propio (barrido fuera de `compute`, compuerta,
límite por barrido, cola acotada, un resumen por petición, apagado acotado) están nombradas en la tarea 4 y en el diseño §9. Escenarios documentales sin aserción automática: AZ16 (nota fechada, cubierto por `DocumentationNotesTest`), AZ73, BI127 (orden de
PR, evidencia en `apply-progress.md`).

## Contradicciones y observaciones (las 1 a 10 son del desglose original; la 11 registra la corrección del 2026-11-04, que sí editó el diseño y los deltas)

1. **Estado de la sección 11 del diseño.** Dice «No se editó ninguno» y su encabezado cita AZ01-AZ77, pero los deltas ya aplican los siete cambios (AZ78 existe, AZ69, BI122, BI126 y BI131 con la redacción
   recomendada). El texto del diseño quedó desactualizado; no afecta a las tareas.
2. **BI132 duplicado en el diseño §9.** Figura en la fila del PR 1 y dentro del rango `BI128-BI133` de la fila del PR 3. Se asigna a la tarea 1 (D18: la regla de pureza del dominio es de la rebanada 1).
3. **AZ72 en la fila del PR 6 (§9, rango AZ65-AZ76).** El ADR y su índice llegan en el PR 1 (§7, §10.1). Se asigna a la tarea 1; AZ71 (todas las notas) y AZ73 quedan en la tarea 7.
4. **BI134.** D18 y «Inversiones» sitúan `WebEdgeScopeExclusionInventoryTest` en la rebanada 3, pero §9 lista BI134 en el PR 6. La aserción positiva exige una lista no vacía, que solo existe en el PR 6.
   La regla que admite `access` llega en la tarea 3 (BI17) y BI134 se asigna a la tarea 7.
5. **Escenarios cuya aserción se completa en una tarea posterior** (el hogar es único, la ampliación se anota en la tarea): I284 (el único consumidor aparece en la 3), AZ60 (asiento y contador en la 4), BI128
   (la lista de beans crece en las tareas 4, 5 y 7) y AZ02 (la ausencia de tablas globales se vuelve a afirmar en BI120 de la 2).
6. **Escenarios asignados por el diseño a la tarea 7 aunque su mecanismo ya existe antes:** AZ29 y WE103/WE104 (necesitan la entrada real), AZ22, AZ74 a AZ77 e I303. Se respeta el diseño.
7. **BI59** no figura en ninguna fila de §9; se asigna a la tarea 7 con BI24 a BI27 (misma instantánea). Las **tres pruebas BI16, BI18 y BI19** y los tres escenarios de `V6` no cambian de aserción: se conservan como
   regresión en las tareas 7 y 6.
8. **Token de estrategia.** La entrada describe la cadena de siete PR pero no nombra uno de los cuatro valores de `delivery_strategy`; el desglose lo registra como equivalente funcional de `auto-chain`
   (decisión tomada, sin pregunta previa a `sdd-apply`). Conviene confirmarlo si una guarda exige el valor literal.
9. **`docs/15` §3** dice que cada PR «apunta al anterior, no a `main`»; el propietario aprobó `stacked-to-main` (cada rama parte de la anterior y se fusiona en orden a `main`). Es la misma práctica de los
   cambios 5 a 9; se mantiene.
10. **Base del PR 1.** La rama del cambio lleva cinco commits de documentos SDD. La tarea 1 confirma con `git log` si ya están en `main`; si no, el PR 1 los hereda y se excluyen de la medición por estar bajo `openspec/`.
11. **Corrección de planificación del 2026-11-04** (pedida por el propietario). Se reflejan los cambios del diseño en D6, D10, D14 y D19: prueba de deriva de la sesión viva (tarea 3), concurrencia del
    barrido, suma conservada ante un fallo, un resumen por petición, compuerta ante un retroceso y apagado acotado (tarea 4), exención por instancia en la misma JVM (tarea 5), AZ69 solo en el arnés
    (tarea 7). AZ37 pasa de la tarea 3 a la 4, porque su asiento por petición exige el manejador que audita. Escenarios nuevos AZ79 y AZ80 (tarea 4). La tarea 5 tiene 10 escenarios modificados sin
    identificador, no 14: los otros 4 del delta de `identity` ya eran de la tarea 7. El rojo esperado de la tarea 6 se rehízo sobre una medición. Ningún valor aprobado cambia.

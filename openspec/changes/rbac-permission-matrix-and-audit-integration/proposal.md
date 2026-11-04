# Propuesta: matriz de permisos RBAC e integración con la bitácora (cambio 8 de F0)

- **Cambio:** `rbac-permission-matrix-and-audit-integration`
- **Fase del roadmap:** F0, cambio 8 del plan de cimientos (`foundations-plan/exploration.md`, fila 8):
  entregable 4 (brecha A7). Cierra el criterio de salida 2 de la fase 0 (`docs/09-roadmap-y-fases.md`:245,
  «Un usuario sin permiso recibe 403 y el intento queda en `AuditLog`»).
- **Depende de:** `transactional-runner-and-audit-log` (cambio 5), `web-edge-foundations`,
  `session-tokens-and-web-layer` (S1), `session-endpoints-cookie-and-csrf` (S2),
  `column-encryption-and-mfa-totp` y `background-jobs-with-db-scheduler` (cambio 9, para la numeración de
  migraciones y el precedente de métricas por puerto).
- **Exploración:** `openspec/changes/rbac-permission-matrix-and-audit-integration/exploration.md` (Engram
  `sdd/rbac-permission-matrix-and-audit-integration/explore`, observación 758).
- **Decisiones del propietario:** Engram `sdd/rbac-permission-matrix-and-audit-integration/owner-decisions`
  (observación 760), tomadas el 2026-11-03. Son vinculantes y esta propuesta no las reabre.
- **Estado:** aprobada por el propietario el 2026-11-03 (`openspec/config.yaml`, `rules.proposal`). La
  aprobación incluye la matriz de la sección «Matriz final», las respuestas a las preguntas Q1 a Q5 y la
  cadena de siete PR apilados de la sección «Pronóstico de tamaño y entrega».

## Intención

Hoy CONFIA autentica, pero no autoriza por función. El principal no lleva autoridades
(`ActorAuthentication.getAuthorities()` devuelve una lista vacía), no existe ningún rol ni permiso, la
cadena de seguridad termina en `anyRequest().denyAll()` y el manejador de 403 no audita nada. Además,
`identity_staff_account.mfa_required` es una segunda fuente de verdad sobre la MFA que la spec de
`identity` obliga a retirar en este cambio.

Sin este cambio no se puede construir ningún módulo de negocio de forma segura: cada ruta nueva
necesita declarar qué permiso exige, el servidor necesita decidirlo (regla 9 de `CLAUDE.md`) y cada
denegación necesita quedar en la bitácora (regla 14).

**Por qué ahora:** es el cambio 8 del plan de cimientos, y el criterio de salida 2 de F0 depende de él.
Los cambios de módulos de negocio (F1 en adelante) necesitan el catálogo de permisos y la guarda de
rutas para declarar sus endpoints.

**Cómo se ve el éxito:** un usuario autenticado con MFA y sin el permiso de una ruta recibe `403
forbidden`, el controlador no se invoca y queda exactamente un asiento `denied` en `shared_audit_log`
cuyo `request_id` coincide con el `traceId` de la respuesta; un usuario con el permiso recibe 200 y no
deja asiento de denegación; la aplicación no arranca si existe una ruta sin clasificar; y la MFA se
deriva de los roles, sin columna independiente.

## Decisiones del propietario aplicadas (2026-11-03)

| Id | Decisión | Consecuencia en esta propuesta |
|---|---|---|
| **O6** | Solo el núcleo | Entran catálogo y matriz, aplicación de permisos, guarda de cobertura de rutas, auditoría de denegaciones, derivación de la MFA desde los roles y la prueba del criterio 2. Lo demás pasa al cambio **8b** (ver «Fuera de alcance») |
| **O1** | Roles fijos en un catálogo en código | La base solo guarda la asignación por cuenta e institución. No hay tablas globales de roles ni de permisos, así que ADR-0017 no cambia |
| **O3** | Exactamente un rol por usuario y por institución | La clave de la asignación es la cuenta dentro de su institución. §5.1 («uno o varios») recibe nota fechada |
| **O10** | Super Admin por institución; el RBAC del Encargado espera a F8 | Seis roles del personal. La columna del Encargado queda solo documentada |
| **O4** | MFA obligatoria para los seis roles del personal | `mfa_required` deja de ser un valor almacenado independiente; una `MfaPolicy` la deriva |
| **O9** | Permisos resueltos por petición desde la base, solo lectura, fallando cerrado | ADR nuevo que reemplaza la parte «los permisos viajan en el token» de ADR-0005 y su regla de MFA para Auditor y Coordinador. ADR-0005 no se edita; recibe una nota fechada de remisión |
| **O8** | Auditoría de denegaciones con tope y resumen por usuario y permiso en una ventana, más una métrica | Solo cuentan las denegaciones por permiso. Nunca se registra la ruta cruda, la consulta, el cuerpo ni las cabeceras. Si falla la escritura del asiento, se responde igual 403. Los valores del tope los fija el diseño |
| **O7** | Primera ruta real con permiso: `GET /api/v1/identity/roles`, con `identity:read` | Informe «quién puede hacer qué», sin datos personales |
| **O5** | Fuera la creación del primer Super Admin y los datos ficticios de desarrollo (entregable 11) | Los roles solo se asignan en pruebas |
| **O2** | Granularidad `módulo:verbo` con los seis verbos de `docs/03` §5.2, más las contradicciones 1, 2 y 5 resueltas | Ver «Matriz final». La contradicción 3 la resuelve O4 y la 4 la resuelve O1 |

## Alcance

### Dentro de alcance

**Catálogo y matriz**

1. Catálogo cerrado en código de los seis roles del personal (Super Admin, Administrador, Cajero,
   Contabilidad, Auditor, Coordinador Académico) y de los permisos `módulo:verbo`, con exactamente los
   permisos que concede al menos un rol de la «Matriz final» (89 según el conteo de esa tabla: los
   seis verbos donde algún rol los tiene, más el permiso de solicitud de anulación). Un permiso que
   ningún rol tiene, como `ledger:update`, no existe en el catálogo.
2. Permiso de solicitud de anulación de factura, distinto de los seis verbos (decisión O2-1). Nombre
   provisional `invoicing:request-void`; el diseño fija la forma final del código. Este cambio solo lo
   declara y lo asigna al Cajero; el flujo de solicitud pendiente y su aprobación pertenecen al cambio
   que construya la anulación de facturas.
3. Prueba de paridad entre el catálogo en código y la matriz efectiva publicada en `docs/03` §5.2 (por
   nota fechada), al estilo de `DocumentationNotesTest`: un permiso cambiado en un lado y no en el otro
   rompe la construcción.

**Persistencia y resolución**

4. Migración nueva (V14 o la siguiente libre; el diseño fija el número) con la tabla de asignación,
   por ejemplo `identity_staff_account_role(institution_id, account_id, role_code)`: clave única por
   cuenta dentro de su institución (O3), `CHECK` sobre el conjunto cerrado de roles, clave foránea a la
   cuenta, seguridad de fila forzada por institución y permisos exactos (sin acceso del rol del portal).
   Las migraciones V1 a V13 no se editan.
5. Puerto `PermissionResolver` en `shared.security` (precedente: `SessionValidity`), implementado por
   `identity` con una consulta parametrizada de solo lectura dentro de `TransactionRunner.executeReadOnly`.
   Devuelve los permisos efectivos de la cuenta autenticada en la institución del token (ADR-0009). Falla
   cerrado: sin rol, con un rol desconocido o ante un error de lectura, la cuenta no tiene ningún
   permiso. Sin caché, para que un cambio de rol surta efecto en la petición siguiente.

**Aplicación de permisos**

6. Un `AuthorizationManager` en la cadena de filtros del proceso administrativo sobre una lista cerrada
   `PermissionedEndpoints` (método, plantilla de ruta, permiso). Corre después de la verificación del
   token y antes de los interceptores de idempotencia y de límite de tasa, sin transacción de negocio
   abierta. Toda ruta con permiso exige además una sesión completa con `otp` en `amr`.
7. `AuthenticatedEndpoints` se conserva para las rutas de autoservicio (sesiones propias, MFA propia),
   que no son permisos del catálogo. `anyRequest().denyAll()` se conserva como cierre.
8. Guarda de cobertura de rutas (`docs/03` §5.1): el arranque del proceso administrativo recorre el
   mapa de rutas y falla si una ruta no es pública, de autoservicio ni con permiso; una prueba enumera
   las rutas de OpenAPI y verifica lo mismo. La lista pública sigue siendo corta y revisada.
9. Reglas de ArchUnit y `ProcessBeanPolicy` actualizadas en el mismo PR que cada paquete nuevo: los beans
   de autorización solo existen en el proceso administrativo; el portal y el trabajador no los cargan.

**Auditoría de denegaciones (O8)**

10. `PermissionDeniedException` lleva el código del permiso. `ProblemAccessDeniedHandler` escribe el
    asiento en su propia transacción, con el contexto del actor: `action=authorization.access.denied`,
    `outcome=denied`, el código del permiso como `entity_id`, la plantilla de ruta de
    `PermissionedEndpoints` (nunca la ruta cruda, la consulta, el cuerpo ni las cabeceras) y
    `request_id` igual al `traceId` del Problem. `actor_label` es el identificador de la cuenta, nunca el
    correo.
11. Tope y resumen por usuario y permiso en una ventana acotada en memoria: filas completas hasta el
    tope, luego una fila de resumen con el conteo y una métrica. Los valores del tope y de la ventana los
    fija el diseño. La métrica se publica por el mecanismo de métricas que exista en F0 (hoy, adaptador
    por registro estructurado detrás de un puerto, precedente del cambio 9).
12. No se auditan como denegación de permiso: la ruta sin mapear (`denyAll`), el rechazo de CSRF ni el
    404 a nivel de objeto. Si la escritura del asiento falla, la respuesta sigue siendo `403 forbidden` y
    se emite una métrica de error.

**MFA derivada (O4)**

13. `MfaPolicy` en `identity` sustituye toda lectura de `mfa_required` en el inicio de sesión, el segundo
    factor y el restablecimiento de contraseña. Toda cuenta del personal lleva MFA obligatoria, tenga o no
    rol (Q1).
14. Migración propia, posterior a la derivación, que retira la columna `mfa_required`; los fixtures de
    prueba (unas 96 apariciones en 45 archivos) pasan a asignar roles.

**Ruta real y prueba del criterio 2 (O7)**

15. `GET /api/v1/identity/roles`, con `identity:read`: informe «quién puede hacer qué» a partir del
    catálogo, con DTO explícito y sin datos personales.
16. Prueba de integración con `HarnessProcess`, PostgreSQL real y un token real de inicio de sesión con
    MFA: un usuario sin el permiso recibe 403 `forbidden`, el controlador se invoca cero veces y existe
    exactamente un asiento `denied` cuyo `request_id` coincide con el `traceId`. Controles: un usuario con
    el permiso recibe 200 y no deja asiento de denegación; el verificador de la cadena sigue pasando; una
    búsqueda de centinelas no encuentra fugas de ruta, consulta, cuerpo ni cabeceras; el camino de fallo
    de la auditoría sigue respondiendo 403; el tope produce la fila de resumen. Se suma un controlador del
    árbol de pruebas (precedente de idempotencia).

**Documentación**

17. Notas fechadas, sin reescribir cuerpos: `docs/03` §4.3 (MFA obligatoria para los seis roles,
    derivada de los roles); `docs/03` §5.1 (un rol por institución, lista cerrada en el filtro en vez de
    anotación, permisos resueltos por petición); `docs/03` §5.2 (matriz efectiva completa de los seis
    roles, nueva regla 3, permiso de solicitud, matriz en código y no en migración semilla); `docs/03`
    §5.3 (el informe trimestral revisa operaciones, no pares de permisos).
18. ADR nuevo (número siguiente libre, hoy 0027) «Autorización por permisos resueltos por petición», que
    reemplaza la parte de ADR-0005 sobre permisos en el token, el retraso de propagación de un cambio de
    permisos y la regla de MFA por rol; índice en `docs/adr/README.md`; nota fechada de remisión en
    ADR-0005 sin tocar su texto original.
19. `docs/09-roadmap-y-fases.md`: se marca el criterio 2 con su prueba de cierre.
    `foundations-plan/exploration.md`: nota fechada con la división 8 / 8b.

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| Asignación de roles por endpoint y regla «un usuario nunca modifica su propio rol» (§5.3) | **8b** |
| Desactivación de cuentas y motivo de revocación `admin_revoke`; inversión de `AccountDeactivationAbsenceTest` | **8b** |
| Inscripción y retirada voluntarias de MFA, rutas que exigen OTP con sesión completa; inversión de `NoFullSessionMfaRoutesTest` | **8b** |
| Reautenticación reforzada (*step-up*) | **8b** |
| Restablecimiento administrativo de contraseña | **8b** |
| Endpoint de lectura de la bitácora con `audit:read` (el permiso sí entra en el catálogo) | **8b** |
| Creación del primer Super Admin sin secreto en migración ni repositorio | **8b** (O5) |
| Datos ficticios de desarrollo (entregable 11) | Fuera (O5); dueño a confirmar en el plan de cimientos |
| RBAC del Encargado y sus permisos `L*`/`A*` | F8 (O10) |
| Flujo de solicitud y aprobación de la anulación de factura, y la validación «aprobador distinto del emisor» | Cambio que construya la anulación en `invoicing` |
| Restricciones de alcance dentro de un permiso («solo su caja», «académico») | Casos de uso de cada módulo (autorización a nivel de objeto, §5.4) |
| Trabajo diario de integridad de §4.3 (usuario con escritura financiera sin MFA activa) | Se registra como deuda; con la MFA derivada y obligatoria su invariante se cumple al iniciar sesión, y el hueco restante es la asignación de rol a mitad de sesión, que pertenece a 8b |
| Informe trimestral de segregación por operaciones | Cambio que entregue la primera operación con aprobación |

## Matriz final de los seis roles del personal

Aprobada por el propietario el 2026-11-03. Leyenda de `docs/03` §5.2: **C** crear, **L** leer, **A** actualizar,
**N** anular, **P** aprobar, **X** exportar; nueva: **S** solicitar anulación. «—» significa sin acceso.
**Δ** marca toda diferencia con `docs/03` §5.2.

| Módulo | Super Admin | Administrador | Cajero | Contabilidad | Auditor | Coord. Académico |
|---|---|---|---|---|---|---|
| `identity` (usuarios, roles) | C L A N P X | L | — | — | L | — |
| `identity` (MFA propia) **Δ** | autoservicio | autoservicio | autoservicio | autoservicio | autoservicio | autoservicio |
| `organization` | C L A N X | C L A X | L | L | L X | C L A X |
| `students` | C L A N X | C L A X | L | L | L X | C L A X |
| `guardians` | C L A N X | C L A X | L | L | L X | L |
| `catalog` | C L A N X | C L A X | L | L X | L X | L |
| `scholarships` | C L A N P X | C L A P X | L | L X | L X | C L |
| `charges` | C L A N P X | C L N P X | L | L X | L X | L |
| `ledger` | L N P X | L N X | L | L X | L X | — |
| `payments` | C L N P X | L N P X **Δ** (sin C) | C L | L N X | L X | — |
| `cashbox` | C L N P X | L P X | C L | L X | L X | — |
| `invoicing` | C L N P X | L P X | C L **S Δ** | C L N P X | L X | — |
| `collections` | C L A N P X | C L A P X | L | L X | L X | L |
| `reconciliation` | C L A P X | L P X | — | C L A P X | L X | — |
| `documents` | C L A N P X | C L A P X | L | — | L X | C L A P X |
| `reporting` | L X | L X | L (solo su caja) | L X | L X | L X (académico) |
| `notifications` | C L A N X | C L A X | L | — | L X | C L |
| `audit` (bitácora) | L X | **— Δ** (era L) | — | — | L X | — |
| `settings` | C L A N X | L A | — | L | L X | — |

Diferencias con `docs/03` §5.2:

- **`invoicing`, Cajero:** se agrega **S** (decisión O2-1). El Cajero solo crea una solicitud pendiente;
  la regla 4 («el Cajero no puede anular») sigue siendo cierta, porque `invoicing:void` sigue solo en
  Contabilidad y Super Admin.
- **`audit`, Administrador:** pasa de **L** a sin acceso (decisión O2-2). La lectura y exportación de la
  bitácora quedan solo en Super Admin y Auditor.
- **`payments`, Administrador:** pierde **C** (decisión del propietario del 2026-11-03 sobre Q3). Lo exige la regla 5 de §5.2,
  «el Administrador no puede cobrar»; la tabla de §5.2 se lo concedía por error, y `cashbox` ya lo aplicaba.
- **`identity` (MFA propia):** no cambia el acceso; cambia el modelo. Son rutas de autoservicio sobre la
  propia cuenta, en `AuthenticatedEndpoints`, no permisos del catálogo.
- **Columna Encargado:** no forma parte de esta matriz (O10); queda documentada en §5.2 para F8.

Reglas reescritas (por nota fechada):

- **Regla 3:** «El Auditor es el único rol de solo lectura absoluta.» Sin la frase «único rol con
  lectura completa de la bitácora», porque Super Admin también la tiene.
- **Informe de §5.3:** revisa operaciones: ninguna operación aprobada por su propio iniciador. No lista
  pares de permisos, y Super Admin conserva crear y aprobar (decisión O2-5).
- **§5.2, párrafo final:** la matriz vive en código y la prueba de paridad la compara con el documento;
  no hay migración semilla (O1).

## Capacidades

> Contrato con `/sdd-spec`. Investigado contra `openspec/specs/` (doce capacidades publicadas:
> `audit-trail`, `background-jobs`, `build-integrity`, `cashbox`, `design-system`, `identity`,
> `invoicing`, `ledger`, `money`, `organization`, `payments`, `web-edge`).

### Nuevas

- **`authorization`:** catálogo cerrado de roles y permisos, matriz efectiva y su paridad con
  `docs/03`, resolución por petición que falla cerrado, aplicación en el filtro sobre la lista cerrada de
  rutas con permiso, exigencia de sesión con `otp` en rutas con permiso, guarda de cobertura de rutas y
  política de auditoría de denegaciones (qué cuenta, tope, resumen, métrica, fallo de la escritura).

### Modificadas

- **`identity`:** tabla de asignación de rol (un rol por cuenta e institución, seguridad de fila);
  adaptador de `PermissionResolver`; `MfaPolicy` y retiro de `mfa_required` (los requisitos de las líneas
  40 y 726 de la spec cambian); ruta `GET /api/v1/identity/roles`.
- **`web-edge`:** la cadena administrativa gana la lista de rutas con permiso y la guarda de arranque;
  el manejador de 403 audita las denegaciones por permiso; el requisito de la línea 34 («ninguna ruta
  concede permisos todavía») se invierte.
- **`audit-trail`:** asientos `authorization.access.denied` y de resumen; el permiso `audit:read` existe
  en el catálogo, pero el escenario «ningún endpoint expone la bitácora» (línea 422) sigue cierto y su
  cierre se transfiere a 8b.
- **`build-integrity`:** el requisito de V6 sobre `mfa_required` (línea 1089) cambia con la migración de
  retiro; matriz de privilegios con la tabla nueva; `ProcessBeanPolicy` y ArchUnit para los beans de
  autorización.

## Enfoque

1. **Catálogo primero.** El catálogo y la prueba de paridad llegan antes que cualquier aplicación, para
   que la matriz aprobada sea la única fuente en código.
2. **Decisión en el filtro, no en el método** (recomendación de la exploración): el `AuthorizationManager`
   sobre una lista cerrada corre antes de los interceptores de idempotencia y de límite de tasa, de modo
   que un actor sin permiso no aprende nada de ellos, y fuera de toda transacción de negocio, de modo que
   el asiento de la denegación no se revierte con ella. Se descarta `@RequirePermission` con seguridad de
   métodos, porque corre después de los interceptores y dentro de la transacción. El ADR nuevo registra
   esta decisión y el cambio respecto de §5.1.
3. **Frescura por petición (O9):** una consulta indexada de solo lectura por petición autenticada con
   permiso; la revocación de un rol es inmediata y el token no cambia de forma ni de tamaño.
4. **Ubicación respetando las fronteras:** los códigos de permiso que necesita `shared.security` viven
   donde `shared` pueda verlos sin importar módulos (`SharedBoundaryRulesTest`); la asignación rol →
   permisos y la `MfaPolicy` viven en `identity.domain`; la tabla y su adaptador jOOQ en
   `identity.infrastructure`; ningún `web` toca jOOQ. El diseño fija los paquetes exactos.
5. **Auditoría en su propia transacción** por el único `TransactionRunner`, con la institución del token
   y el actor de la petición; el tope en memoria sigue los precedentes de `LogIn` e `InMemoryRateLimiter`.
6. **Derivar antes de retirar:** la `MfaPolicy` y los fixtures con roles llegan antes de la migración que
   elimina `mfa_required`.
7. **TDD estricto** (`openspec/config.yaml`): la prueba del criterio 2, la de paridad, la de la guarda de
   arranque y la de fallo cerrado del resolutor se escriben en rojo antes del código.

## Áreas afectadas

| Área | Cambio |
|---|---|
| `apps/api/app/src/main/resources/db/migration/V14__*.sql` y siguiente | Nuevas: tabla de asignación de rol con RLS y permisos; retiro de `mfa_required` |
| `apps/api/app/src/main/java/com/confia/shared/security/` | `PermissionResolver`, `AuthorizationManager`, `PermissionedEndpoints`, guarda de cobertura, `PermissionDeniedException` |
| `apps/api/app/src/main/java/com/confia/shared/web/` | `SecurityChains`, `AuthenticatedEndpoints`, `ProblemAccessDeniedHandler` con auditoría y tope |
| `apps/api/app/src/main/java/com/confia/identity/{domain,application,infrastructure,web}/` | Catálogo de roles, `MfaPolicy`, adaptador del resolutor, caso de uso y controlador de `GET /api/v1/identity/roles`; inicio de sesión y segundo factor sin `mfa_required` |
| Pruebas | Paridad, guarda de arranque, resolutor bajo RLS con dos instituciones, criterio 2, tope y resumen, centinelas, `ProcessBeanPolicy`, ArchUnit, `RolePrivilegeMatrixIT`, fixtures de `mfa_required` |
| Documentación | `docs/03` §4.3, §5.1, §5.2, §5.3; ADR nuevo e índice; nota en ADR-0005; `docs/09` criterio 2; `foundations-plan/exploration.md` |

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Inundación de la bitácora: contención del bloqueo de `shared_audit_chain_head` y crecimiento de una tabla que solo admite inserciones | Media | Tope y resumen por usuario y permiso (O8), métrica, prueba del tope |
| El tope en memoria es por proceso; con varias instancias el tope efectivo se multiplica | Baja en F0 (una instancia) | Se declara en el diseño; un tope compartido llega con Redis en el cambio 11 si hace falta |
| Una ruta queda sin clasificar o con el permiso equivocado | Media | Guarda de arranque, prueba sobre OpenAPI y `denyAll` final |
| El resolutor falla abierto por un error de programación | Baja | Pruebas en rojo de fallo cerrado: sin rol, rol desconocido, error de lectura, otra institución |
| Mucho cambio en las pruebas al retirar `mfa_required` (unas 96 apariciones en 45 archivos) | Alta | Derivación y retiro en rebanadas separadas; fixtures con rol por un ayudante común |
| Un rol asignado a una sesión sin MFA concede permisos al instante, porque se resuelven por petición | Media | Toda ruta con permiso exige `otp` en `amr`; la revocación de sesiones al cambiar el rol es de 8b |
| Tres iniciadores de §5.3 siguen sin permiso en la matriz (Q4) | Media | Se resuelven en el cambio que construya cada operación y quedan anotados en `docs/03`; la prueba de paridad hace explícito cualquier cambio posterior |
| Desviación de la normalización de rutas entre la lista cerrada y MVC | Media | La guarda compara contra el mapa real de rutas y la prueba usa el proceso real |
| Subestimación de tamaño (factor histórico de 2,2 a 7,97) y más de 15 tareas | Alta | Cadena de rebanadas de abajo; si `tasks.md` supera 15 tareas, la fase de tareas propone partir el cambio |
| El cambio supera con claridad el tope de 800 líneas por PR de `docs/15` §3 | Cierta | Cadena de siete PR apilados, aprobada por el propietario (Q5) |

## Plan de reversión

No existe ningún entorno desplegado ni dato real.

1. Cada PR se revierte con su commit de fusión, en orden inverso de la cadena.
2. Revertir la aplicación de permisos deja las rutas sin listar bajo `denyAll()`: la reversión falla
   cerrada, nunca abre rutas.
3. **La migración que retira `mfa_required` va al final** y solo después de que la `MfaPolicy` esté en
   `main`. Revertirla antes de producción es eliminar la migración y regenerar jOOQ; después, exige una
   migración nueva que vuelva a crear la columna con el valor derivado.
4. La tabla de asignación: antes de producción se elimina su migración; una vez aplicada en un entorno
   compartido nunca se edita y se revierte con una migración nueva.
5. La auditoría de denegaciones se revierte de forma independiente; los asientos ya escritos no se
   borran (regla 5).
6. El ADR nuevo y las notas se revierten con su PR; ninguna nota reescribe un texto original.

## Dependencias

- **Consume:** `TransactionRunner.executeReadOnly`, `AuditLogWriter` y `RequestOriginAuditLogWriter`,
  `SecurityChains`, `ProblemAccessDeniedHandler`, `AuthenticatedEndpoints`, `ProcessBeanPolicy`,
  `HarnessProcess`, el inicio de sesión con MFA y el verificador de la cadena.
- **Entrega a 8b y a F1:** catálogo de permisos, resolutor, lista de rutas con permiso y guarda de
  arranque, política de denegaciones y `MfaPolicy`.
- Sin dependencias Maven nuevas previstas. Docker para las pruebas de integración.

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` en verde; cobertura global al menos 80 %, `domain` y `kernel` al
      menos 95 %, mutación de PIT al menos 80 % en `domain`.
- [ ] **Criterio 2:** sin el permiso, 403 `forbidden`, cero invocaciones del controlador y exactamente
      un asiento `denied` con `request_id` igual al `traceId`; con el permiso, 200 y ningún asiento de
      denegación; `docs/09` marca el criterio con su prueba.
- [ ] El catálogo en código y la matriz efectiva de `docs/03` coinciden celda por celda; un cambio en un
      solo lado rompe la construcción.
- [ ] El proceso administrativo no arranca con una ruta sin clasificar.
- [ ] El resolutor falla cerrado en los cuatro casos de prueba y nunca devuelve permisos de otra
      institución.
- [ ] El tope produce una fila de resumen y la métrica; el fallo de la escritura del asiento sigue
      respondiendo 403; la búsqueda de centinelas no encuentra ruta cruda, consulta, cuerpo ni cabeceras.
- [ ] Ningún código de producción lee `mfa_required` y la columna ya no existe.
- [ ] El portal y el trabajador no cargan ningún bean de autorización.
- [ ] El ADR nuevo existe, ADR-0005 conserva su texto original con una nota de remisión y las notas de
      `docs/03` están fechadas.

## Pronóstico de tamaño y entrega

**Pronóstico: unas 3 400 a 4 200 líneas efectivas** (producción de 1 100 a 1 400, pruebas de 1 800 a
2 300, documentación y spec unas 500), con el rango de la exploración y el pequeño agregado del permiso
de solicitud y de la matriz efectiva en `docs/03`. **Supera con claridad el tope de 800 líneas por PR**
de `docs/15` §3. El propietario aprobó esta cadena de PR sobre `main` (Q5); las pruebas nunca se separan
del código que prueban. La suma de los rangos por rebanada, de 3 550 a 5 250 líneas, es mayor que el
pronóstico global; ninguna de las dos cifras es una medición, y lo que obliga es el tope de cada PR.

| Orden | Rebanada | Contenido | Líneas aprox. |
|---|---|---|---|
| 1 | `permission-catalog-and-adr` | Catálogo de roles y permisos, prueba de paridad, notas de `docs/03` §5, ADR nuevo e índice, nota de ADR-0005 | 500 a 750 |
| 2 | `role-assignment-and-resolver` | Migración de la asignación con RLS y permisos, adaptador jOOQ, `PermissionResolver`, pruebas de fila y de fallo cerrado, matriz de privilegios | 550 a 800 |
| 3 | `filter-authorization-and-route-guard` | `AuthorizationManager`, `PermissionedEndpoints`, exigencia de `otp`, guarda de arranque, prueba sobre OpenAPI, ArchUnit, `ProcessBeanPolicy`, controlador de prueba | 600 a 800 |
| 4 | `permission-denial-audit` | Excepción con permiso, asiento en transacción propia, tope y resumen, métrica, camino de fallo | 500 a 750 |
| 5a | `mfa-policy-derivation` | `MfaPolicy`, inicio de sesión y segundo factor sin leer la columna, fixtures con rol, nota de §4.3 | 600 a 800 |
| 5b | `drop-mfa-required-column` | Migración de retiro, resto de fixtures, ajuste de `build-integrity` | 300 a 600 |
| 6 | `roles-report-and-criterion-2` | `GET /api/v1/identity/roles`, prueba del criterio 2 con centinelas, `docs/09`, nota del plan de cimientos | 500 a 750 |

**Órdenes duros:** 1 antes que todo; 2 antes de 3 y de 5a; 3 antes de 4; 4 y 3 antes de 6; 5a antes de
5b. Se estiman de 14 a 16 tareas frente al tope de 15 de `openspec/config.yaml`.


## Preguntas resueltas por el propietario (2026-11-03)

- **Q1. MFA de una cuenta del personal sin rol:** sí. Toda cuenta del personal lleva MFA obligatoria, tenga o no rol. Afecta a la rebanada 5a.
- **Q2. Revisión DA-15:** pasa a 8b, junto con la inscripción y retirada voluntaria de MFA y la creación del primer Super Admin. El riesgo sigue aceptado hasta entonces y este cambio lo registra.
- **Q3. `payments:create` del Administrador:** se quita. El Administrador queda con `L N P X` en `payments`, como exige la regla 5.
- **Q4. Iniciadores sin permiso en §5.3** (ajuste manual del libro mayor, baja por incobrable, reembolso): se resuelven en el cambio que construya cada operación. Este cambio los deja anotados como pendientes en la nota fechada de `docs/03`.
- **Q5. Entrega:** la cadena de siete PR apilados de la sección anterior, con base sucesiva y fusión en orden hacia `main`. No se usa una excepción de tamaño. Reemplaza la estrategia `single-pr` de la sesión.

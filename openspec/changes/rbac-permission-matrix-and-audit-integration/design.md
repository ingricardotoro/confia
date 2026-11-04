# Diseño: matriz de permisos RBAC e integración con la bitácora (cambio 8 de F0)

- **Cambio:** `rbac-permission-matrix-and-audit-integration` (F0, cambio 8; entregable 4, brecha A7, criterio de
  salida 2)
- **Fase:** diseñar
- **Fecha:** 2026-11-03
- **Estado:** pendiente de aprobación del propietario. O1 a O10 y Q1 a Q5 están decididas y no se reabren. Lo que este
  documento pide al propietario está en la sección 14.
- **Corrección de planificación del 2026-11-04** (a pedido del propietario, antes de aplicar): D6 (sesión viva derivada de
  la misma lista), D10 (concurrencia del barrido, invariante de la suma, latencia por petición, compuerta ante un
  retroceso del reloj, apagado acotado), D14 (cómo llega la exención al proceso de inicio de sesión), §2, §3, §5, §6, §9 y
  §12. Ningún valor aprobado cambia.
- **Entradas:** `proposal.md` (aprobada el 2026-11-03), `exploration.md`, deltas de `specs/` (AZ01-AZ77, WE94-WE105,
  I278-I303, AT32-AT38, BI119-BI139), `docs/01-arquitectura.md`, `docs/03-seguridad.md` §4.3 y §5, ADR-0005, ADR-0009,
  ADR-0015, ADR-0017, ADR-0022, ADR-0024, y el código de la rama en `dc2c194`.
- **Modo de TDD:** estricto (`openspec/config.yaml`). Ejecutor: `./mvnw verify` en `apps/api`, con JDK 25 y Docker.
- **Dinero:** este cambio no toca importes. Reglas aplicables: 9, 10, 11, 12, 13, 14 y 15 de `CLAUDE.md`.

## 1. Enfoque técnico

El cambio agrega autorización por permisos al proceso administrativo sin cambiar el token ni abrir ninguna ruta. Cinco
invariantes guían el diseño, y cada rebanada las prueba en rojo antes de escribir código:

1. **La decisión ocurre en la cadena de filtros.** Una regla `access(...)` de `AuthorizationFilter`, por entrada de una
   lista cerrada, decide antes de que exista un `HandlerInterceptor` o una transacción de negocio. Un actor sin permiso no
   toca la idempotencia ni el limitador, y su asiento no se puede revertir con ningún negocio.
2. **El servidor resuelve por petición y falla cerrado.** La asignación se lee en una transacción de solo lectura con la
   institución del token. Sin fila o con un código desconocido: conjunto vacío. Con un error de lectura: excepción, nunca
   un conjunto, de modo que un fallo no se confunde con «sin permisos» ni con «permitido».
3. **Solo una denegación por permiso se audita, y con tope.** La excepción tipada es la única señal. Cualquier otro `403`
   conserva su respuesta y no escribe nada.
4. **La respuesta no depende de la auditoría ni del tope.** El cuerpo de una denegación es byte a byte el de una ruta sin
   mapear, salvo `instance` y `traceId`, con el escritor sano, roto o por encima del tope.
5. **Nada queda sin clasificar.** El arranque falla ante una ruta de `com.confia` fuera de las cuatro clases, y
   `anyRequest().denyAll()` sigue al final, de modo que toda reversión falla cerrada.

```
 petición
    │
 SecurityHeadersFilter ─► RequestContextFilter (id de petición; último recurso: 500 internal-error)
    │
 FilterChainProxy (cadena administrativa)
    ├─ CsrfOriginFilter
    ├─ AccessTokenAuthenticationFilter ── SessionValidity (rutas con permiso: siempre sesión viva, D6)
    ├─ AnonymousAuthenticationFilter
    ├─ ExceptionTranslationFilter ─┬─ anónimo ─► ProblemAuthenticationEntryPoint (401)
    │                              └─ autenticado ─► AuditingAccessDeniedHandler ─► ProblemAccessDeniedHandler (403)
    │                                                       │
    │                                                       └─ solo PermissionDeniedException:
    │                                                          DenialWindows ─► TransactionRunner ─► AuditLogWriter
    └─ AuthorizationFilter
         público ─► autenticado ─► restringido ─► con permiso (PermissionDecider) ─► denyAll
                                                     │ amr sin otp: denegación sin auditoría
                                                     │ PermissionResolver (identity, solo lectura)
                                                     │ sin permiso: PermissionDeniedException
                                                     │ fallo: PermissionResolutionFailedException ─► último recurso
    ▼
 DispatcherServlet ─► RateLimitInterceptor / IdempotencyKeyInterceptor ─► controlador
```

## 2. Evidencia que condiciona el diseño

| Hallazgo | Fuente | Consecuencia |
|---|---|---|
| El último recurso ya responde `500 internal-error` con `traceId`, sin datos internos, a toda excepción que escapa de la cadena | `RequestContextFilter.java:69-74`, `:140-149` | D7: el filtro no necesita escribir nada propio |
| El puerto `SessionValidity` lanza ante un fallo y la petición termina en `500`, ya aceptado para un fallo de lectura en la misma cadena | `SessionValidity.java:21`, `AccessTokenAuthenticationFilter.java:186-187` | D5 y D7 siguen el mismo tratamiento |
| El último recurso registra la excepción completa, y el mensaje del controlador JDBC puede repetir valores | `RequestContextFilter.java:73`; contraste con `ProblemExceptionHandler.java:271-281` | D7: se envuelve sin causa antes de que llegue allí |
| `AuthenticatedActor.CURRENT` y `RequestOrigin.CURRENT` siguen enlazados durante el manejador de `403`, porque el filtro del token envuelve a `ExceptionTranslationFilter` | `AccessTokenAuthenticationFilter.java:129-133`, `AdminSecurityConfiguration.java:60-64` | D9 |
| `RequestOriginAuditLogWriter` completa IP y agente del `RequestOrigin` enlazado si la entrada los trae en `null` | `RequestOriginAuditLogWriter.java:32-45` | D10: un resumen que se emite durante otra petición debe evitar heredar el origen ajeno |
| Dentro de `com.confia`, `identity.domain` solo importa `kernel`; `docs/01` dice «la capa `domain` no importa a nadie», y `shared` puede usar `kernel` | Búsqueda en `identity/domain`, `docs/01-arquitectura.md:238`, `SharedBoundaryRulesTest.java:34-42` | D2: el enum de permisos vive en `kernel` |
| El portal admite beans de `shared.security`; el trabajador también | `ProcessBeanPolicy.java:132`, `:159` | D1: los beans de autorización no viven en `shared.security` |
| `NoInMemorySchedulingTest` prohíbe `ScheduledExecutorService`, `Timer` y `org.springframework.scheduling` | `NoInMemorySchedulingTest.java:25-32` | D10: el resumen se emite de forma perezosa, sin temporizador |
| `identity_staff_account.mfa_required` es `NOT NULL` sin `DEFAULT`, y producción nunca inserta cuentas | `V6:7-10`; `JooqStaffAccountRepository` | D15: los fixtures siguen insertando la columna hasta la rebanada 5b |
| Medido el 2026-11-04 en `6b48d1b`, árbol limpio, sin distinguir mayúsculas, solo archivos versionados bajo `apps/`: el patrón `mfa_?required` aparece 102 veces en 49 archivos (`V6`, 5 archivos Java de producción, entre ellos `getMfaRequired()` de `JooqStaffAccountRepository`, y 43 de prueba); el literal `mfa_required` aparece en 44 (`V6`, 3 de producción y 40 de prueba). El código de jOOQ generado no está versionado y ninguna de las dos búsquedas lo ve | Búsqueda del 2026-11-04 | D15 y sección 11 (BI126) |
| 31 semillas de prueba con `mfaRequired=false` en 10 archivos de `identity/web` esperan una sesión sin segundo factor, mezcladas con cuentas con MFA en el mismo proceso | Búsqueda del 2026-11-03; `LoginEndpointSupport.java:53-77` | D14: la política de prueba decide por cuenta |
| `V9ExistingRowsIT` y otras pruebas migran a una versión histórica con `StepwiseDatabase.migratedTo(...)`, donde la columna existe y es obligatoria | `V9ExistingRowsIT.java:39-47` | Sección 11: BI126 necesita una excepción |
| `SecurityHeadersFilter` ya fija `Cache-Control: no-store` en toda respuesta | `SecurityHeadersFilter.java:48` | D16: el informe no lo repite |
| `HarnessProcess` no tiene `DataSource`; `IdempotencyEdgeIT` sí combina borde real, PostgreSQL y controlador de prueba | `WebEdgeHarness.java:52`, `IdempotencyEdgeIT.java:85-87` y `:446-451` | D19 |
| Siguiente migración libre: V14. Siguiente ADR libre: 0027 | `db/migration/`, `docs/adr/README.md:75` | D4, D15, D17 |
| Spring Security resuelto: 7.1.1 | `~/.m2/.../spring-security-core/7.1.1` | D6: firma `AuthorizationManager#authorize`; la primera prueba en rojo la confirma |

## 3. Valores fijados

Cada valor que la especificación dejó al diseño, con su razón en una línea. El detalle está en la decisión citada.

| Valor | Decisión | Razón | Ref. |
|---|---|---|---|
| Verbos | `create`, `read`, `update`, `void`, `approve`, `export` | Son los de `docs/03` §5.1, sin traducir | D2 |
| Permiso de solicitud | `invoicing:request-void` | Se conserva el provisional; expresa la acción y no se confunde con `invoicing:void` | D2 |
| Forma del código | `^[a-z]+:[a-z]+(-[a-z]+)*$` | Cubre los 89 y admite un verbo compuesto | D2 |
| Tipo del catálogo | Enum `com.confia.kernel.Permission`, 89 constantes | `domain` y `shared` lo ven sin romper ninguna regla de dependencia | D2 |
| Códigos de rol | `super_admin`, `administrator`, `cashier`, `accounting`, `auditor`, `academic_coordinator` | Inglés estable; las etiquetas visibles son del catálogo de internacionalización | D2 |
| Tabla | `identity_staff_account_role(institution_id, account_id, role_code, created_at)` | Prefijo del módulo y solo la asignación (O1) | D4 |
| Restricciones | `_pk (institution_id, account_id)`, `_account_fk`, `_code_chk` | La clave primaria hace cumplir O3 y es el índice de la lectura por petición | D4 |
| `confia_admin_app` | Solo `SELECT` | Nada escribe en producción en este cambio; 8b concede lo que necesite en su migración | D4 |
| Migraciones | `V14__create_identity_staff_account_role.sql` y `V15__drop_identity_staff_account_mfa_required.sql` | V13 es la última; el retiro va al final | D4, D15 |
| Resultado del resolutor | `Set<Permission>`; un fallo de lectura se lanza | Mismo contrato que `SessionValidity`; una excepción no se puede leer como conjunto vacío | D5 |
| Fallo de resolución | `500 internal-error`, por el último recurso de `RequestContextFilter` | Tratamiento existente, sin código público nuevo; un `503` promete una condición transitoria que aquí no se sabe | D7 |
| Rechazo por falta de `otp` | `403 forbidden`, sin asiento y sin métrica | Con la política real no existe un token `[pwd]`; es una anomalía que no debe abrir otra vía de inundación | D12 |
| `entity_type` | `authorization.permission` | Mismo estilo que `identity.staff_account` | D9 |
| Detalle del asiento completo | `{"routeTemplate":"..."}` | La especificación pide solo la plantilla | D9 |
| Agente de usuario acotado | Permanece en el asiento completo; ausente en el resumen | Lo completa el mecanismo existente y sirve a la investigación; un resumen no pertenece a una petición | D9, D10 |
| Tope `T` | 10 asientos completos | Conserva el detalle de un error humano repetido y limita a 120 filas por hora cada par cuenta y permiso | D10 |
| Ventana `V` | 5 minutos, fija, abierta por la primera denegación de la clave | Es el valor que vio el propietario; una ventana fija da un resumen por ventana y sumas exactas | D10 |
| Cota del estado | 10 000 claves `(institución, cuenta, permiso)` | Unos 2 MB; solo un actor con sesión completa crea una clave | D10 |
| Clave nueva con el estado lleno | Asiento completo sin contar, más métrica; ninguna clave viva se expulsa | Nunca se pierde una denegación (regla 14) y una expulsión borraría el contador de quien se está limitando | D10 |
| Emisión del resumen | Perezosa: al cerrar la ventana, en la siguiente denegación de cualquier clave, y al apagar | No hay programador en memoria permitido y el estado no puede ir al trabajador | D10 |
| Resúmenes por petición | Como mucho 1 en el hilo de una denegación | Una respuesta `403` paga a lo sumo su asiento y un resumen ajeno | D10 |
| Barrido | Solo memoria, fuera de toda función de asignación, como mucho una vez por segundo y hasta 500 ventanas movidas | Cumple el contrato de `ConcurrentHashMap.compute` y acota el trabajo por petición | D10 |
| Cola de resúmenes pendientes | `LinkedBlockingDeque` con capacidad 10 000 | Misma cota que el estado; un descarte se cuenta en métrica | D10 |
| Apagado | Corta en el primer fallo y se detiene a los 10 segundos medidos con el `Clock` inyectado | Una base de datos caída no retiene el cierre del proceso | D10 |
| Acción del resumen | `authorization.access.denied_summary` | Se distingue por acción, con `outcome=denied` | D10 |
| Detalle del resumen | `{"omittedCount":n,"windowStart":"...","windowSeconds":300}` | Conteo y ventana; cuenta y permiso están en `actor_id` y `entity_id` | D10 |
| Métricas | `authorization_denial_omitted`, `authorization_denial_audit_failed`, `authorization_resolution_failed`, `authorization_denial_tracker_full` | Mismo estilo que `rate_limit_capacity_exhausted`; hacia Prometheus llevarán prefijo `confia_` y sufijo `_total` | D11 |
| Reloj de la ventana | `java.time.Clock` inyectado; un retroceso cuenta como ventana vencida, y un instante de barrido siguiente más de un segundo en el futuro cuenta como vencido | Hay que registrar el instante de inicio; un paso del reloj solo puede escribir más filas, nunca menos, ni detener el barrido | D10 |
| `Authenticated` | Permanece en el tipo cerrado, alcanzable solo con una política de prueba | La especificación conserva cuatro desenlaces e I149 e I228 lo ejercitan | D14 |
| `MfaPolicy` | Se conserva como costura, constante verdadera en producción | Quitarla obligaría a cambiar escenarios y dejaría sin prueba la rama de sesión | D14 |
| OpenAPI del informe | `bearerAuth`, `x-required-permission: identity:read`, respuestas `200`, `401`, `403` y `500` | Exactamente las que la ruta puede producir | D16 |
| DTO del informe | `{"roles":[{"code":"...","permissions":["..."]}]}` | Solo códigos del catálogo, sin datos personales ni de base de datos | D16 |
| ADR | ADR-0027 | El índice llega hasta 0026 | D17 |

## 4. Decisiones de arquitectura

### D1. Paquetes y dirección de las dependencias

**Elección.** Un paquete nuevo, `com.confia.shared.web.authorization`, contiene todo lo que tiene que ver con HTTP y los
beans de autorización. El puerto vive en `shared.security` y el catálogo en `kernel`.

```
kernel                              Permission (enum)
   ▲           ▲
   │           │
identity.domain                     StaffRole (matriz), MfaPolicy
   ▲
identity.application                AccountPermissions implements PermissionResolver
   │                                StaffRoleAssignmentRepository (puerto), ListRolePermissions
   ▲            ▲
identity.web    identity.infrastructure
RolesReportController               JooqStaffRoleAssignmentRepository
RolesReportResponse

shared.security                     PermissionResolver (solo el puerto; no es un bean)
shared.web.authorization            PermissionedEndpoint(s), PermissionDecider, RouteCoverageGuard,
                                    PermissionDeniedException, PermissionResolutionFailedException,
                                    AuditingAccessDeniedHandler, PermissionDenialAudit, DenialWindows,
                                    AuthorizationMetrics (puerto), AuthorizationProperties
shared.web.edge                     SecurityChains, AdminSecurityConfiguration, AuthorizationConfiguration
shared.observability.metrics        LogAuthorizationMetrics
```

**Alternativa descartada.** Los beans en `shared.security`, como sugería la lista de áreas de la propuesta: portal y
trabajador admiten ese paquete en `ProcessBeanPolicy`, de modo que la lista de permitidos no protegería nada, y
`PermissionDeniedException` y los decisores dependen de tipos web.

**Razón.** El trabajador prohíbe `com.confia.shared.web` entero y el portal no lista el paquete nuevo, así que la
política falla cerrada sin más. `shared` nunca importa `identity`: el adaptador implementa el puerto, igual que
`SessionLiveness`. Ninguna clase `web` toca jOOQ. `TransactionRunner` sigue siendo el único componente que abre
transacciones, y `AccountPermissions` y `PermissionDenialAudit` solo lo llaman.

### D2. El catálogo de permisos vive en `kernel`

**Elección.** `com.confia.kernel.Permission`, un enum de 89 constantes con su código y `ofCode(String)`. No contiene
asignaciones a roles.

**Alternativas descartadas.**
- `shared.security.Permission`, que es lo que nombra BI131. Obligaría a `identity.domain` a importar `shared.security`,
  en contra de `docs/01` (`la capa domain no importa a nadie`), que es la fuente que gana. Ninguna regla de ArchUnit lo
  detectaría hoy, porque `shared.security` no es una capa, y eso no lo vuelve correcto.
- Códigos como `String` en `identity.domain`, mapeados en `application`: duplica los 89 nombres y pierde el tipo.

**Razón.** `kernel` no tiene dependencias fuera del JDK, y tanto `shared` como `domain` ya pueden usarlo. Un enum de
códigos es un tipo base, no una regla de negocio: la regla, qué rol concede qué, vive en `identity.domain`. La propuesta
solo exige que `shared` pueda ver los códigos «sin importar módulos». El costo es un cambio de redacción en BI131
(sección 11).

La lista completa son los 88 pares de módulo y verbo de la matriz más `invoicing:request-void`. Los códigos de rol y los
verbos están en la sección 3.

### D3. La matriz vive en `StaffRole` y una prueba la compara con `docs/03`

**Elección.** `identity.domain.StaffRole`, un enum en el orden del catálogo, sin framework ni entrada o salida:
`code()`, `permissions()` (`Set` inmutable) y `ofCode(String)`, que devuelve `Optional.empty()` ante un código desconocido
(AZ03).

La prueba de paridad, `PermissionMatrixParityTest` en `com.confia.architecture`, separa dos pasos para poder probar sus
controles sin escribir en disco:

```java
// Test code: a pure comparison, so AZ15 feeds it altered copies of either side.
static List<String> differences(Map<Cell, Set<String>> code, Map<Cell, Set<String>> document);
static Map<Cell, Set<String>> parse(List<String> dated5_2NoteLines);
```

Lee la tabla de la nota fechada de `docs/03` §5.2, identificada por su marcador, y no la tabla original, que conserva la
columna del Encargado y las celdas previas a las cuatro diferencias. Afirma 6 por 18 celdas evaluadas, para que un archivo
vacío no pase (AZ14). Mismo mecanismo de marcador y de línea original intacta que `DocumentationNotesTest`.

### D4. Tabla de asignación y migración V14

**Elección.** Esta es la forma exacta de la migración:

```sql
-- Fourteenth migration (F0 change 8, slice 2): the one role of a staff account within its
-- institution (owner decisions O1 and O3). The catalog of roles and permissions lives in code, so
-- no global table exists and the closed list of ADR-0017 does not change.
CREATE TABLE identity_staff_account_role (
    institution_id UUID        NOT NULL,
    account_id     UUID        NOT NULL,
    role_code      TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT identity_staff_account_role_pk         PRIMARY KEY (institution_id, account_id),
    CONSTRAINT identity_staff_account_role_account_fk FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_staff_account_role_code_chk   CHECK (role_code IN ('super_admin',
        'administrator', 'cashier', 'accounting', 'auditor', 'academic_coordinator'))
);

ALTER TABLE identity_staff_account_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_staff_account_role FORCE  ROW LEVEL SECURITY;

CREATE POLICY identity_staff_account_role_institution_isolation ON identity_staff_account_role
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON identity_staff_account_role FROM PUBLIC;

-- SELECT only: nothing in production writes this table yet. Change 8b, which adds the use case that
-- assigns roles, grants what it needs in its own migration.
GRANT SELECT ON identity_staff_account_role TO confia_admin_app;
GRANT SELECT ON identity_staff_account_role TO confia_readonly;
-- confia_portal_app: no grant. confia_owner: schema owner, without BYPASSRLS.
-- confia_backup: reads through pg_read_all_data, which never bypasses row-level security.
```

**Alternativa descartada.** `SELECT, INSERT, UPDATE`, que es el patrón de las demás tablas de identidad: concedería una
escritura que ningún código usa, y un defecto de `confia_admin_app` podría asignarse un rol antes de que exista la regla
«un usuario nunca modifica su propio rol», que es de 8b. Con `SELECT` a secas, AZ75 queda además garantizado por la base
de datos.

**Consecuencia para las pruebas.** Las asignaciones se siembran con un ayudante común, `StaffRoleFixtures`, que usa
`SharedPostgresContainer.connectionAs("confia_owner")` y fija `app.institution_id` en su transacción, porque `FORCE`
también le aplica al propietario. I278, I279 e I280 se ejecutan con ese rol, para que el rechazo observado sea el de la
restricción y no el de falta de privilegio. `CommittingPostgresIntegrationTest` agrega la tabla a su limpieza.

### D5. El resolutor devuelve un conjunto y lanza ante un fallo

**Elección.**

```java
package com.confia.shared.security;

public interface PermissionResolver {

    /**
     * The effective permissions of the account of {@code actor} in the institution of its token; empty
     * when it has no role or an unknown one. A failure of the store is thrown, never answered: an
     * error must not read as "no permissions", and much less as "allowed".
     */
    Set<Permission> resolve(AuthenticatedActor actor);
}
```

El único adaptador es `identity.application.AccountPermissions`, en la misma capa que `SessionLiveness` porque necesita
`TransactionRunner`. Lee con `runner.executeReadOnly(actor.securityContext(requestId), ...)` a través del puerto
`StaffRoleAssignmentRepository`, implementado en `JooqStaffRoleAssignmentRepository`, y aplica `StaffRole.ofCode`. Devuelve
`Set.of()` si no hay fila o el código es desconocido. La factoría de configuración devuelve el tipo del adaptador y no el
puerto, para que ningún adaptador se esconda en una lambda (`WebEdgeScopeExclusionInventoryTest`). Sin caché.

**Alternativa descartada.** Un tipo sellado `Resolved | Unavailable`. Obliga a una rama más en el decisor para volver a
llegar a una excepción, y se aparta del contrato ya probado de `SessionValidity`. AZ21 se cumple igual: lanzar es
distinguible de devolver un conjunto vacío.

### D6. La decisión en el filtro, con una lista cerrada y sesión viva

**Elección.** `PermissionedEndpoints` es una lista inmutable de `PermissionedEndpoint(HttpMethod, pattern, Permission)`,
con `forAdmin()` y un constructor público para los arneses, igual que las otras dos listas. `SecurityChains` gana una
sobrecarga que agrega, después de las rutas restringidas y antes de `denyAll()`:

```java
permissioned.endpoints().forEach(endpoint -> requests
        .requestMatchers(endpoint.method(), endpoint.pattern()).access(decider.forEndpoint(endpoint)));
```

El decisor:

```java
// shared.web.authorization.PermissionDecider, for one entry.
AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
        RequestAuthorizationContext context) {
    if (!(authentication.get() instanceof ActorAuthentication principal)) {
        return DENIED; // anonymous: the entry point answers 401
    }
    AuthenticatedActor actor = principal.getPrincipal();
    if (!actor.methods().contains(AuthenticationMethod.ONE_TIME_PASSWORD)) {
        return DENIED; // 403 forbidden, no audit, the resolver is never asked (D12)
    }
    if (resolve(actor).contains(endpoint.permission())) {
        return GRANTED;
    }
    throw new PermissionDeniedException(endpoint.permission(), endpoint.pattern(), actor);
}
```

Los cuatro resultados llegan al sitio correcto sin código nuevo: `DENIED` con un anónimo termina en el punto de entrada
(`401`), `DENIED` con un actor en el manejador de `403`, y `PermissionDeniedException`, que extiende
`AccessDeniedException`, en ese mismo manejador por `ExceptionTranslationFilter`. Un token restringido nunca llega aquí:
`verifyAccess` lo rechaza antes por su audiencia con `401 token-invalid` (AZ28).

La plantilla del asiento es `endpoint.pattern()`, capturada por la entrada coincidente. Nunca se lee de la petición, de
modo que la ruta cruda no puede llegar al asiento (AZ44). Las variantes de ruta (`/x/`, `/X`) no coinciden con el
`PathPatternRequestMatcher` y caen en `denyAll`; el cortafuegos rechaza `;` y `/./`. Por eso AZ32 y AZ33 no escriben asiento.

**Toda ruta con permiso exige sesión viva.** `AccessTokenAuthenticationFilter` suma las entradas de
`PermissionedEndpoints` a sus rutas con comprobación `LIVE_SESSION`. Una sesión revocada responde `401 token-invalid`,
igual que en las dos rutas autenticadas. No existe un valor por entrada: no hay caso que justifique una ruta con permiso
que acepte una sesión cerrada, y ADR-0005 ya exigía validar `sid` para las escrituras financieras. Cuesta una segunda
lectura por petición. Los escenarios vigentes no lo contradicen, pero tampoco lo piden: la sección 11 recomienda agregar
uno.

**Una sola lista, sin segundo comparador que pueda divergir.** `AuthorizationConfiguration` declara un único bean
`PermissionedEndpoints`, y `AdminSecurityConfiguration` pasa esa misma instancia a `SecurityChains` y al constructor de
`AccessTokenAuthenticationFilter`, que gana un parámetro `PermissionedEndpoints` y deriva de `endpoints()`, en su
constructor, los comparadores de sesión viva de las rutas con permiso. No existe un valor `SessionCheck` por entrada ni otra
lista que editar. Los arneses hacen lo mismo con su lista de prueba. Una prueba de deriva lo hace cumplir sobre **cada**
entrada, no sobre una ruta de ejemplo: `PermissionEdgeTest.everyPermissionedEntryRejectsARevokedSession` y
`AuthorizationEdgeIT.everyPermissionedEntryRejectsARevokedSession` recorren la lista del arnés (al menos dos entradas, una
con variable de ruta) con un token `[pwd, otp]` de sesión revocada y exigen, por entrada, `401 token-invalid`, cero
invocaciones del resolutor y ningún asiento; la segunda corre con la lista de `@Import` del proceso administrativo, de modo
que cubre el cableado de `AdminSecurityConfiguration`. **Demostración deliberada:** derivar los comparadores con
`endpoints().stream().skip(1)` pone en rojo las dos pruebas, nombrando la entrada omitida.

**Alternativas descartadas.** `@RequirePermission` con seguridad de métodos, descartada por la propuesta y por WE94,
porque corre después de los interceptores y dentro de la transacción. Un `AuthorizationManager` único que busque la
entrada dentro de la petición: reintroduce una segunda coincidencia de rutas que puede discrepar de la de la cadena.

### D7. El fallo de resolución responde `500 internal-error` por el último recurso

**Verificado leyendo `web-edge` y `shared/web/problem`.** `ProblemCode` tiene `INTERNAL_ERROR` (500) y `CAPACITY_EXCEEDED`
(503). El segundo significa «sin capacidad para el pedido» y lo produce el limitador. Una base de datos que falla no es
eso. El fallo equivalente de la misma cadena, `SessionValidity`, ya responde `500`.

**Elección.** `500 internal-error`, sin código nuevo. Un código público nunca se renombra (`ProblemCode.java:8`), y uno
nuevo obligaría a agregarlo al catálogo, a la instantánea y a `ProblemCatalogCoverageTest` sin darle al cliente nada que
pueda usar.

**Cómo se construye la respuesta, dado que una excepción de filtro no llega al `@RestControllerAdvice`:**

```java
private Set<Permission> resolve(AuthenticatedActor actor) {
    try {
        return resolver.resolve(actor);
    } catch (RuntimeException failure) {
        // Class names and the SQLState only: the message of a driver repeats values (regla 11).
        LOG.error("Permission resolution failed: exception={} cause={} sqlState={}", ...);
        metrics.resolutionFailed();
        throw new PermissionResolutionFailedException(); // fixed message, no cause
    }
}
```

La excepción atraviesa `AuthorizationFilter`. `ExceptionTranslationFilter` la analiza, no encuentra en su cadena de causas
ni `AccessDeniedException` ni `AuthenticationException`, porque no tiene causa, y la vuelve a lanzar. Sube hasta
`RequestContextFilter`, cuyo último recurso restablece el búfer y escribe el `500` con el `traceId` de la petición. El
manejador de acceso denegado no se invoca (WE101) y el contador no cambia (AZ62). Las cabeceras de seguridad ya están
puestas por `SecurityHeadersFilter`, que es exterior.

El envoltorio sin causa es deliberado: sin él, la línea `An exception escaped the request chain` registraría el mensaje
del controlador. Para extraer la clase y el `SQLState`, `DataAccessFailure`, hoy un registro privado de
`ProblemExceptionHandler`, pasa a ser una clase de `shared.web.problem`. Es un cambio mecánico. El comportamiento del
manejador no cambia.

**Alternativa descartada.** Escribir la respuesta desde el decisor con `ProblemResponses`: un `AuthorizationManager`
devuelve una decisión, no controla la respuesta. Haría falta un filtro más y una segunda ruta hacia el `500`.

### D8. El manejador de `403` es un decorador solo administrativo

**Elección.** `ProblemAccessDeniedHandler` no cambia: sigue en el portal tal como está.
`AuditingAccessDeniedHandler` lo envuelve solo en la cadena administrativa:

```java
public void handle(HttpServletRequest request, HttpServletResponse response,
        AccessDeniedException denied) throws IOException {
    if (denied instanceof PermissionDeniedException permissionDenied) {
        recorder.record(permissionDenied, requestIdOf(request)); // never throws
    }
    delegate.handle(request, response, denied);
}
```

El asiento se escribe antes de la respuesta, de modo que cuando el cliente recibe el `403` el asiento ya está confirmado o
ya falló, y el cuerpo sale siempre del mismo `problems.write(FORBIDDEN)` (WE99, WE100). `SecurityChains` recibe el manejador
por parámetro en la sobrecarga administrativa. Las dos sobrecargas existentes conservan su comportamiento, y por eso la
cadena del portal no contiene nada de autorización (AZ76).

`PermissionDenialRecorder` es una interfaz de una sola operación. `HarnessProcess`, que no tiene base de datos, la
sustituye por un doble; en producción la implementa `PermissionDenialAudit`.

**Alternativa descartada.** Agregar el escritor a `ProblemAccessDeniedHandler`: el portal arrastraría una dependencia que
no debe tener, y `shared.web.problem` dependería del paquete de autorización.

### D9. El asiento de denegación y su transacción

**Forma del asiento completo:**

| Columna | Valor |
|---|---|
| `institution_id` | la del token |
| `actor_id`, `actor_label` | el identificador de la cuenta (nunca el correo) |
| `actor_kind` | `staff` |
| `source_ip`, `user_agent` | `null` en la entrada; los completa `RequestOriginAuditLogWriter` |
| `request_id` | el atributo `REQUEST_ID_ATTRIBUTE`, que es el `traceId` del Problem |
| `trace_id`, `before_value`, `reason`, `approver_id` | `null` |
| `action` | `authorization.access.denied` |
| `entity_type` | `authorization.permission` |
| `entity_id` | el código del permiso |
| `outcome` | `denied` |
| `after_value` | `AuditJson.object().text("routeTemplate", template).toJson()` |

**Transacción.** `runner.execute(actor.securityContext(requestId), () -> { writer.append(entry); return null; })`, sin
ninguna transacción abierta: la decisión se tomó en el filtro y el manejador corre antes de MVC (AZ43). La lectura del
resolutor ya se cerró. La decisión del tope se toma **fuera** de la transacción, de modo que un reintento de
`TransactionRunner` no cuenta dos veces.

**Fallo.** `PermissionDenialAudit.record` captura `RuntimeException`, incrementa `authorization_denial_audit_failed` una
vez, no registra la excepción (su mensaje podría repetir valores), devuelve la denegación a la suma del tope con
`fullRowFailed` o `untrackedRowFailed` (D10) y vuelve. La respuesta sigue siendo `403 forbidden` (AZ59). La excepción lleva el actor en un campo `transient`, igual que `ActorAuthentication`, y un mensaje fijo, porque
`AccessDeniedException` es `Serializable`.

**Agente de usuario.** Permanece, sin código nuevo: lo pone el mecanismo común, está acotado y saneado por
`RequestContextFilter` y es la evidencia que un auditor espera junto a la IP.

### D10. Tope, ventana y resumen

**Estado.** `DenialWindows`, un bean en memoria con tres piezas:

- `ConcurrentHashMap<DenialKey, Window>`, con la clave `(institutionId, accountId, permission)`. Incluye la institución
  porque la clave primaria de la cuenta es compuesta y AZ20 usa el mismo identificador en dos instituciones. `Window` es
  un registro inmutable (`windowStart`, `full`, `omitted`): cada cambio reemplaza el valor.
- Un contador de lugares `AtomicInteger`, con reserva por compare-and-set como en `InMemoryRateLimiter`, que nunca deja
  pasar la cota de 10 000 claves.
- Una cola de resúmenes pendientes `LinkedBlockingDeque<PendingSummary>` con capacidad 10 000. `PendingSummary` lleva la
  clave, `windowStart` y `omittedCount`.

```java
Decision record(DenialKey key, Instant now);              // FULL_ROW(windowStart) | OMITTED | UNTRACKED
void fullRowFailed(DenialKey key, Instant windowStart);   // the write of a FULL_ROW failed
void untrackedRowFailed(DenialKey key, Instant now);      // the write of an UNTRACKED row failed
void sweepIfDue(Instant now);                             // gated, in memory only, outside any mapping function
Optional<PendingSummary> pollPending();
boolean requeue(PendingSummary summary);                  // offerFirst; false when the queue is full
List<PendingSummary> closeAllForShutdown(Instant now);    // every window with omissions, closed or not
int size();
int pendingSize();
```

**Mecanismo de concurrencia.** El contrato de `ConcurrentHashMap.compute` prohíbe que la función de asignación modifique
otras asignaciones del mismo mapa. Tres reglas lo respetan:

1. `record`, `fullRowFailed` y `untrackedRowFailed` usan `compute(key, ...)` solo sobre su propia clave. La función decide
   sobre la `Window` de esa clave, reserva un lugar con compare-and-set si la clave es nueva y puede encolar en la cola de
   pendientes, que es otra estructura. Nunca barre, nunca escribe en la base de datos y nunca invoca otro método del mapa.
2. El barrido corre **fuera** de toda función de asignación. Recorre `entrySet()`, cuya iteración es débilmente
   consistente y no bloquea el mapa, y por cada ventana candidata a vencida invoca `computeIfPresent` **sobre esa misma
   clave**. Esa función vuelve a comprobar el vencimiento dentro de la operación atómica de la clave, encola el resumen si
   hubo omisiones y devuelve `null` para quitar la entrada; el lugar se libera después de quitarla. Una denegación
   concurrente de la misma clave queda serializada con esa operación: o cae en la ventana vieja antes de que se quite, o
   abre una nueva después, y en ningún caso se pierde ni se cuenta dos veces.
3. La compuerta del barrido es un `AtomicLong` con el instante del siguiente barrido permitido. Solo barre el hilo que gana
   el compare-and-set; los demás siguen sin esperar.

Una llamada a `record` sigue una de estas filas:

| Situación (dentro de `compute` de la clave) | Efecto | Decisión |
|---|---|---|
| Clave nueva y la reserva de lugar tiene éxito | Abre una ventana con `full=1` | `FULL_ROW` |
| Ventana vencida (`now >= windowStart + V`) o reloj retrocedido (`now < windowStart`) | Si la ventana anterior omitió algo, su resumen pasa a la cola. Abre una ventana con `full=1` en el mismo lugar | `FULL_ROW` |
| Ventana abierta y `full < T` | `full++` | `FULL_ROW` |
| Ventana abierta y `full == T` | `omitted++` | `OMITTED` |
| Clave nueva y la reserva falla | Nada. Fuera de `compute`: `sweepIfDue(now)` y un único reintento | `UNTRACKED` si el reintento también falla |

| Decisión | Asiento | Métrica |
|---|---|---|
| `FULL_ROW` | Completo | Ninguna |
| `OMITTED` | Ninguno | `authorization_denial_omitted(permission)` |
| `UNTRACKED` | Completo | `authorization_denial_tracker_full` |

**La suma se conserva ante un fallo de escritura.** La invariante es: por clave, los asientos completos escritos más el
conteo de los resúmenes escritos, pendientes o en ventanas vivas igualan las denegaciones registradas; una vez vaciada la
cola, filas completas más resúmenes igualan las denegaciones. Ninguna denegación sale de esa suma salvo por los límites
declarados abajo. Por eso un asiento que falla no se pierde:

- **`FULL_ROW` que falla.** `PermissionDenialAudit` invoca `fullRowFailed(key, windowStart)`. Dentro de `compute` de la
  misma clave, si la ventana viva tiene ese `windowStart`, devuelve el lugar del tope (`full--`) y suma la denegación a las
  omisiones (`omitted++`), de modo que el resumen de la ventana la incluye. Si la ventana ya se cerró o se reemplazó,
  encola un resumen propio de conteo 1 con ese `windowStart`.
- **`UNTRACKED` que falla.** `untrackedRowFailed` encola un resumen de conteo 1 con el instante de la denegación como
  `windowStart`.
- En ambos casos `authorization_denial_audit_failed` se incrementa una vez y la respuesta sigue siendo `403 forbidden`
  (AZ59). La decisión del tope y su corrección ocurren fuera de la transacción, de modo que un reintento interno de
  `TransactionRunner` no cuenta dos veces.

**Emisión del resumen.** No hay programador posible: `NoInMemorySchedulingTest` prohíbe los temporizadores, db-scheduler
corre en otro proceso sin acceso a esta memoria, y `@Async`, `@Scheduled` y cualquier otra biblioteca de programación están
prohibidos. La emisión ocurre por eso en el hilo de la petición, con un costo acotado. Hay tres disparadores:

1. **Cada denegación**, después de su propio asiento (o de su omisión), invoca `afterDenial(now)`: primero
   `sweepIfDue(now)`, que solo trabaja en memoria, barre como mucho una vez por segundo en todo el proceso y mueve como
   mucho 500 ventanas vencidas por barrido (las vencidas sin omisiones solo se quitan; las demás quedan para el barrido
   siguiente); después escribe **como mucho un** resumen de la cola. Una respuesta `403` paga así, a lo sumo, su propio
   asiento y un resumen ajeno: dos transacciones cortas de una inserción cada una, acotadas por el tiempo de espera del
   grupo de conexiones. La cola se vacía más rápido de lo que se llena: un resumen de omisiones exige al menos `T + 1`
   denegaciones de su clave, un resumen de conteo 1 nace de un asiento fallido, y cada denegación escribe hasta uno.
2. **`flushClosedWindows(now)`**, de uso solo en pruebas unitarias y de integración sin HTTP: barre sin compuerta y escribe
   la cola hasta el primer fallo. Las pruebas de borde disparan la emisión con una denegación, como dicen AZ53 y AT35.
   **Independencia del orden de las pruebas.** En `AuthorizationEdgeIT`, un solo contexto comparte el bean `DenialWindows`,
   su cola, su compuerta y el `MutableClock`, así que la cola puede tener resúmenes de otras pruebas delante del propio.
   Por eso cada prueba de borde que espera un resumen: (a) antes de empezar vacía la cola de las pruebas anteriores con
   `flushClosedWindows(now)` sobre el bean del contexto, que es la única llamada directa permitida en una prueba con HTTP,
   y comprueba que `pendingSummaryCount()` es 0; (b) después dispara la emisión con denegaciones de otra clave, como mucho
   tantas como resúmenes pendientes haya, y afirma sobre las filas de su propia institución. El resultado ya no depende
   del orden (AZ57, AZ70 y las tres corridas en orden aleatorio de BI138). `pendingSummaryCount()` es de solo lectura y
   se expone solo para las pruebas; el límite de un resumen por denegación de producción no cambia.
3. **Al apagar**, `DisposableBean.destroy` invoca `closeAllForShutdown(now)`, que cierra toda ventana con omisiones,
   vencida o no, y escribe la cola con dos cotas: corta en el primer fallo de escritura, de modo que una base de datos
   caída cuesta un solo intento, y se detiene cuando el `Clock` inyectado marca 10 segundos desde el inicio del apagado,
   comprobado entre escritura y escritura. Cada resumen se escribe antes de que se cierre el `DataSource`, porque el bean
   depende de `TransactionRunner`. Lo que no llega a escribirse incrementa `authorization_denial_audit_failed` una vez por
   resumen.

**Fallo de un resumen y cola llena.** Si la escritura de un resumen falla, vuelve a la cabeza de la cola con `offerFirst`,
se incrementa `authorization_denial_audit_failed` y la pasada se corta: la denegación en curso no intenta otro. Si la cola
está llena al encolar o al devolver, el resumen se descarta con la misma métrica. Las métricas de omisión ya se
publicaron al omitir, de modo que operación ve la inundación aunque el resumen tarde o se pierda.

**Compuerta del barrido y reloj.** `sweepIfDue` barre si `now >= nextSweepAt` **o** si `nextSweepAt - now > 1 s`. Con un
reloj que avanza, el instante siguiente nunca está más lejos que el propio intervalo, de modo que esa distancia solo puede
venir de un retroceso, y se trata como reinicio: el barrido corre y fija `nextSweepAt = now + 1 s`. Sin esa regla, un paso
atrás de una hora dejaría el barrido detenido una hora.

**Límites declarados**, todos propios de la decisión O8, «ventana acotada en memoria», y registrados en ADR-0027:

1. Una caída no ordenada pierde las ventanas y la cola en memoria.
2. Un resumen descartado por cola llena, o no escrito al apagar por fallo o por agotar los 10 segundos, pierde su conteo de
   la bitácora; quedan la métrica `authorization_denial_audit_failed` y las `authorization_denial_omitted` ya publicadas.
3. Un asiento fallido cuyo resumen de conteo 1 no cabe en la cola se pierde con la misma métrica.
4. Un resumen puede emitirse mucho después de su ventana si no hay más denegaciones en el proceso. Su detalle lleva
   `windowStart`, de modo que el asiento sigue siendo exacto.
5. El tope es por proceso: con `n` réplicas el tope efectivo se multiplica por `n`. Un tope compartido, si hace falta,
   llega con Redis en el cambio 11.

**Forma del resumen.** Se escribe con el contexto de seguridad de la **institución de la ventana** y un `requestId` nuevo,
porque el `WITH CHECK` de la política exige esa institución y la petición que dispara el barrido pertenece a otra
persona. `actor_id` y `actor_label` son la cuenta, `entity_type` es `authorization.permission`, `entity_id` el permiso,
`outcome` es `denied`.

El origen debe quedar vacío (AT35 no admite cabeceras), pero el decorador completaría la IP y el agente de **quien disparó
el barrido**. La escritura se hace por eso con el origen enlazado de nuevo a uno sin datos:

```java
ScopedValue.where(RequestOrigin.CURRENT, new RequestOrigin(summaryRequestId, null, null))
        .call(() -> runner.execute(context, () -> { writer.append(summary); return null; }));
```

`RequestOrigin` admite `null` en ambos campos, de modo que no hace falta ni un bean ni un método nuevo. Una prueba negativa
fija este punto, porque el defecto sería una atribución falsa y silenciosa.

**Configuración.** `AuthorizationProperties`, registro con `@ConfigurationProperties("confia.authorization.denials")`:
`max-full-rows: 10`, `window: 5m`, `max-tracked-keys: 10000`, validados en el constructor compacto y con los valores en
`application.yml`. Ninguno es un secreto. Las pruebas fijan `T=3` o `T=2` por propiedad.

**Alternativas descartadas.** Un resumen inmediato en la primera omisión y otro por cada denegación siguiente: anula el
propósito del tope. Una tabla persistente de ventanas: una escritura por denegación, justo lo que el tope quiere evitar.

### D11. Métricas por puerto

`shared.web.authorization.AuthorizationMetrics`, con el adaptador `shared.observability.metrics.LogAuthorizationMetrics`,
misma forma y misma cota que `LogRateLimitMetrics`: un evento `WARN` estructurado con los campos `event`, `permission`
cuando aplica y `suppressed`, y como mucho un evento por segundo y por clave.

```java
public interface AuthorizationMetrics {
    void denialOmitted(Permission permission);
    void denialAuditFailed();
    void resolutionFailed();
    void denialTrackerFull();
}
```

El estado del adaptador está acotado por construcción, porque `Permission` es cerrado. Ningún método puede recibir un
correo, una IP ni una ruta (AZ58). Las pruebas sustituyen el puerto por un doble que cuenta cada llamada: AZ53 afirma dos
llamadas. No se define ninguna alerta nueva en este cambio; `docs/07` recibe una nota fechada con los cuatro eventos.

### D12. El rechazo por falta de `otp` no deja asiento

**Confirmado: `403 forbidden` sin asiento y sin métrica**, como dice la especificación. Con la política real (Q1) ninguna
cuenta del personal obtiene un token `[pwd]`: el camino que lo emite solo existe con una política de prueba (D14). Un token
así en producción implicaría un defecto o una clave de firma comprometida, y una fila por petición no contenida por el tope
sería una vía de inundación nueva. Tiene que revisarse si 8b introduce rutas de elevación de sesión, porque entonces ese
token sí existirá.

### D13. Guarda de cobertura de rutas

**Elección.** `RouteCoverageGuard implements SmartInitializingSingleton`, siguiendo a `RateLimitPolicyCheck`: corre cuando
existen todos los singletons, antes de que el servidor web acepte conexiones. Lee los `RequestMappingHandlerMapping` y
falla con `IllegalStateException` si:

- una ruta de un manejador de `com.confia` no coincide, por método y plantilla exactos, con ninguna entrada de las cuatro
  clases, o no declara método;
- una entrada de `PermissionedEndpoints` no corresponde a una ruta registrada;
- una ruta figura en dos clases;
- existe un `RouterFunction`, que no se puede enumerar (precedente: `PublicRouteAllowListTest.unenumerableRoutes`);
- no evaluó ninguna ruta.

El mensaje nombra método y ruta. Los manejadores de bibliotecas quedan fuera: springdoc registra en `local` rutas que no
figuran en ninguna lista (`/v3/api-docs.yaml`) y `denyAll` ya las cierra.

**La prueba sobre OpenAPI** (AZ38) y `PublicRouteAllowListTest` (BI135) son independientes de la guarda: la primera recorre
el documento, y la segunda hace peticiones reales y distingue cuatro clases.

**Un permiso fuera del catálogo no se puede escribir.** `PermissionedEndpoint` recibe un `Permission`, no un texto, de modo
que AZ30 no es una falla de arranque sino un error de compilación. La sección 11 recomienda adaptar el escenario, con un
fixture permanente al estilo de `ExhaustiveAuthenticationResultSwitchCompilationTest`.

### D14. `MfaPolicy` y el desenlace `Authenticated`

**Elección.** Se conservan los dos.

```java
package com.confia.identity.domain;

@FunctionalInterface
public interface MfaPolicy {

    /** Whether this account owes a second factor, decided from nothing the account row can store. */
    boolean requiresSecondFactor(StaffAccountId account);

    /** The policy of production: every staff account, with or without a role (owner decision Q1). */
    MfaPolicy STAFF = account -> true;
}
```

`AuthenticateWithPassword.outcomeForValidCredentials` y `ResetPasswordWithToken.mfaActive` reciben la política y dejan de
leer `account.mfaRequired()`. `IdentityConfiguration` registra `MfaPolicy.STAFF`; ninguna propiedad puede cambiarla.

**Por qué recibe la cuenta, si la política real no la usa.** Las pruebas lo exigen: `LoginEndpointIT` mezcla en un mismo
proceso cuentas que esperan una sesión directa (I149, I228) y cuentas con MFA (I150), y las 31 semillas con `false`
conservan así su significado. La política de prueba, `SecondFactorExemptions` en `src/test`, exime a las cuentas que la
prueba le indique.

**Cómo llega al proceso real.** El proceso de inicio de sesión corre **en la misma JVM** que la prueba:
`LoginProcess.start` invoca `AdminProcessLauncher.launch`, que llama a `ConfiaApplication.launch` y devuelve el
`ConfigurableApplicationContext` en proceso. Sobre eso:

- **Registro.** `LoginProcess` agrega la propiedad `spring.main.sources` con `SecondFactorExemptionsConfiguration`, de
  `src/test`, que declara un `@Bean @Primary SecondFactorExemptions` (implementa `MfaPolicy`). La producción no cambia:
  esa clase no existe en el artefacto, y sin ella el único bean es `MfaPolicy.STAFF`.
- **Sin estado estático.** `SecondFactorExemptions` guarda las cuentas eximidas en un conjunto de instancia
  (`ConcurrentHashMap.newKeySet()`), uno por contexto, que se descarta al cerrar el proceso. Ningún campo `static` lo
  sostiene, de modo que dos procesos de la misma JVM, o dos clases de prueba sucesivas, no comparten exenciones.
- **Acceso desde la prueba.** `LoginProcess.exemptions()` devuelve `context.getBean(SecondFactorExemptions.class)`, y
  `LoginEndpointSupport` gana el método abstracto `exemptions()`, que cada subclase resuelve con su proceso.
- **Orden de siembra.** El proceso arranca primero (uno por clase). Cada prueba siembra la fila de la cuenta, la registra
  como eximida si su parámetro lo pide y solo después inicia sesión. La política se consulta en cada inicio de sesión, de
  modo que basta con que el registro preceda al inicio de sesión; los identificadores de cuenta son aleatorios y una
  exención no alcanza a otra prueba.

**Sonda S1**, primera prueba de la rebanada 5a, `SecondFactorExemptionsProbeIT`: en un mismo proceso con la configuración
de prueba, una cuenta eximida con contraseña correcta obtiene la sesión y sus cookies, como en I149 (desenlace `Authenticated`), y una cuenta no
eximida con credencial verificada obtiene el token restringido (`SecondFactorRequired`); y en un proceso sin la
configuración, el contexto no contiene `SecondFactorExemptions` y su único `MfaPolicy` es `STAFF`. Si
`ConfiaApplication.launch` no respeta `spring.main.sources`, la alternativa es un `BeanPostProcessor` agregado por un
`ApplicationContextInitializer` de prueba, con el mismo conjunto de instancia y la misma sonda, nunca un parámetro nuevo en
producción.

**`Authenticated` sigue alcanzable solo con esa política.** El tipo cerrado, sus `switch` exhaustivos, la rama de `LogIn`
que emite la sesión y la traducción a `200` siguen probados. I302 y `MfaPolicyProductionWiringTest` afirman, sobre el
proceso real, que el bean es `STAFF` y que ninguna cuenta obtiene `Authenticated`. `ArchUnit` afirma que `STAFF` es la única
definición de producción.

**Alternativa descartada.** Eliminar la política y `Authenticated`. Es más simple en apariencia, pero contradice el
requisito de cuatro desenlaces, I149 e I228, y obliga a reescribir unas 30 pruebas de sesión para que completen TOTP, sin
aumentar la protección.

### D15. El orden del retiro de `mfa_required`

1. **Rebanada 5a.** `StaffAccount` pierde el campo, `JooqStaffAccountRepository` deja de leerlo y entra la política. **Los
   fixtures siguen insertando la columna**, porque es `NOT NULL` sin `DEFAULT` y V6 no se edita. El parámetro `mfaRequired`
   de los ayudantes pasa a alimentar `SecondFactorExemptions`. La construcción pasa sin la migración V15 (BI127).
2. **Rebanada 5b.** La migración, únicamente:

   ```sql
   -- Fifteenth migration (F0 change 8, slice 5b). The second factor is decided by MfaPolicy since
   -- slice 5a, and nothing reads this column any more (owner decisions O4 and Q1).
   ALTER TABLE identity_staff_account DROP COLUMN mfa_required;
   ```

   jOOQ se regenera sola en la construcción. Se quita la columna de los 43 archivos de prueba, cambio mecánico salvo
   `MultiTenantSchemaIT`, cuya prueba `mfaRequiredIsFixedAtAccountCreation...` se elimina. El parámetro de los ayudantes se
   renombra `exemptFromSecondFactor`.
3. **Pruebas fijadas a una versión histórica.** Las que migran con `StepwiseDatabase.migratedTo("<15")` e insertan cuentas
   conservan la columna, porque en esa versión es obligatoria: `V9ExistingRowsIT` lo hace, y también hay que revisar
   `RetentionPurgeFunctionsIT` y `RefreshTokenFamilyPurgeFunctionIT`, que migran a la versión 11. `PurgeSession` inserta la
   columna y la usan cuatro pruebas, de modo que si atiende a ambas versiones hay que separar su inserción. La tarea hace
   ese inventario primero.

### D16. `GET /api/v1/identity/roles`

**Caso de uso.** `identity.application.ListRolePermissions.execute()` devuelve `List<RolePermissionsView>` a partir de
`StaffRole`: roles en el orden del catálogo y permisos ordenados por código. No abre transacción ni toca la base de datos.
**Controlador.** `identity.web.RolesReportController`, registrado en `IdentityWebConfiguration`.

```java
public record RolesReportResponse(List<Role> roles) {
    public record Role(String code, List<String> permissions) {
    }
}
```

No lleva nombres visibles: la interfaz los traduce desde el código de rol con su catálogo. No exige `Idempotency-Key` ni
tiene limitador (lectura barata de una constante). Un acceso concedido no deja asiento. `Cache-Control: no-store` ya lo pone
`SecurityHeadersFilter`.

**OpenAPI.** `operationId = "listRolePermissions"`, etiqueta `identity-roles`, `@SecurityRequirement(bearerAuth)`, extensión
`x-required-permission: identity:read`, y `@ApiResponse` explícitos para `200`, `401`, `403` y `500`. La instantánea pasa a
ocho operaciones y `packages/contracts` se regenera. La rebanada verifica que orval tolera la extensión.

### D17. ADR-0027 y notas fechadas

`docs/adr/ADR-0027-autorizacion-por-permisos-resueltos-por-peticion.md`, en estado **Propuesto** hasta la aprobación del
propietario, en español neutro. Esquema:

1. **Contexto.** Sin autorización por función; las tres partes de ADR-0005 que dejan de aplicar; las contradicciones de
   `docs/03` §5.1 y §5.2.
2. **Decisión.** (a) Los permisos se resuelven por petición desde la base de datos, en solo lectura y sin caché; el token no
   lleva permisos ni roles. (b) La decisión se toma en la cadena de filtros sobre una lista cerrada, no por anotación. (c)
   Roles fijos en código, una asignación por cuenta e institución, sin tablas globales. (d) La MFA es obligatoria para toda
   cuenta del personal y la decide una política. (e) Toda ruta con permiso exige `otp` y sesión viva. (f) Las denegaciones
   se auditan con tope en memoria.
3. **Partes de ADR-0005 que reemplaza.** «Los permisos viajan en el token», el retraso de hasta diez minutos en propagar un
   cambio de permisos, la MFA por rol para Auditor y Coordinador, y el mecanismo de la prueba 9 de cobertura de MFA. El
   resto de ADR-0005 sigue vigente: duración del token, familias y refresco.
4. **Alternativas descartadas.** Claim de permisos, claim de roles, seguridad de métodos, tablas de roles por institución,
   caché con invalidación.
5. **Consecuencias.** Dos lecturas por petición con permiso; revocación inmediata; tope por proceso; pérdida de conteos ante
   una caída; asignación a mitad de sesión pendiente de 8b.
6. **Cómo se verifica.** Lista de pruebas: paridad, guarda, criterio 2, fallo cerrado, inventarios.

ADR-0005 no se edita y recibe una nota fechada de remisión, junto a las que ya tiene. El índice de `docs/adr/README.md` gana
una fila.

| Documento | Nota | Rebanada |
|---|---|---|
| `docs/03` §5.1 | Un rol por institución, lista cerrada en el filtro, resolución por petición | 1 |
| `docs/03` §5.2 | Matriz efectiva (la que lee la paridad), cuatro diferencias, regla 3, párrafo final, iniciadores pendientes | 1 |
| `docs/03` §5.3 | El informe trimestral revisa operaciones | 1 |
| `docs/03` §4.3 | MFA obligatoria y derivada | 5a |
| `docs/07` | Los cuatro eventos de autorización | 4 |
| `docs/09` | Criterio 2 marcado, con sus pruebas | 6 |
| `foundations-plan/exploration.md` | División 8 / 8b | 6 |

`DocumentationNotesTest` se extiende en cada rebanada, con el mismo mecanismo de líneas originales intactas.

### D18. `ProcessBeanPolicy`, ArchUnit e inversiones

**`ProcessBeanPolicy`, en el mismo PR que cada bean:**

| Rebanada | `ADMIN` | Otros |
|---|---|---|
| 2 | sin cambios: `identity.application` e `identity.infrastructure` ya están | |
| 3 | `com.confia.shared.web.authorization` | `PORTAL`: prohibido, con razón. `WORKER`: ya lo cubre `com.confia.shared.web` |
| 5a | `com.confia.identity.domain`, porque `MfaPolicy.STAFF` es un bean de ese paquete | `PORTAL` ya prohíbe `com.confia.identity`; el trabajador no registra el bean. Una prueba lo afirma |

Los beans son `PermissionedEndpoints`, `PermissionDecider`, `RouteCoverageGuard`, `AccountPermissions`,
`LogAuthorizationMetrics`, `DenialWindows`, `PermissionDenialAudit`, `MfaPolicy`, `ListRolePermissions` y
`RolesReportController`. `AccessTokenAuthenticationFilter`, `CsrfOriginFilter` y el manejador siguen creándose con `new`, para
que el contenedor no los registre dos veces.

**ArchUnit nuevas o ampliadas:**

| Regla | Rebanada | Escenario |
|---|---|---|
| `PermissionResolver` tiene un solo adaptador, en `identity`; `PermissionDecider` es su único consumidor; ninguna factoría devuelve el puerto | 2 y 3 | I284 |
| `kernel.Permission` sin dependencias; `StaffRole` y `MfaPolicy` en `domain` sin framework | 1 y 5a | BI132 |
| Regla nueva: `..domain..` no depende de ningún otro paquete de `com.confia` salvo `kernel` y su propio módulo, ni de Spring o jOOQ | 1 | BI132 |
| `ALLOWED_ROUTE_RULES` admite `access`, solo desde `SecurityChains` | 3 | BI17, BI134 |
| Sin `@PreAuthorize`, `@RequirePermission` ni `@EnableMethodSecurity` | 3 | BI133, WE94 |
| Sin `Thread.sleep`, `Instant.now()` ni `Clock.systemUTC()` en las pruebas nombradas | 4 | BI137 |
| Ninguna función de asignación de `DenialWindows` toca otra asignación del mapa ni barre | 4 | D10 (AZ79) |
| `..application..` no depende de `PermissionDecider` ni de `PermissionDeniedException` | 3 | Requisito de BI131-BI133 |

**Inversiones.** `WebEdgeScopeExclusionInventoryTest` (rebanada 3), `PublicRouteAllowListTest` y `RegisteredRoutes` con cuatro
clases (3 y 6), `IdentityScopeExclusionInventoryTest` y `AuditScopeExclusionInventoryTest`, con sus dueños pasados a 8b (6).
`AccountDeactivationAbsenceTest` y `NoFullSessionMfaRoutesTest` se conservan: su inversión es de 8b.

### D19. Reloj inyectable y estado aislado

| Nivel | Herramienta | Reloj y estado |
|---|---|---|
| Unidad: `DenialWindowsTest` | `new` por prueba, con el `MutableClock` existente | Instancia propia por prueba |
| Con base de datos, sin HTTP: `PermissionDenialAuditIT` | `TransactionRunner` real (es `final` y no se dobla), `MutableClock`, escritor real o envuelto para fallar | Instancia propia por prueba e institución propia |
| Borde sin base de datos: `PermissionEdgeTest` sobre `HarnessProcess` | Lista con rutas de prueba, resolutor y grabador dobles | Proceso propio por clase; dobles que se limpian entre pruebas |
| Borde con base de datos: `AuthorizationEdgeIT` | `@SpringBootTest` sobre un arnés con la misma lista de `@Import` del proceso administrativo, más el controlador de prueba, como `IdempotencyEdgeIT`. Un `BeanPostProcessor` sustituye `Clock` por `MutableClock` y puede envolver `AuditLogWriter` | Institución y cuentas propias por prueba, que aíslan contadores por clave; `T` por propiedad |
| Proceso real: `ExitCriterion2EndToEndIT` | `AdminProcessLauncher`, sin dobles y sin reloj manual: no se inyecta ningún `Clock` en el proceso real | Institución propia; TOTP calculado por un ayudante de prueba |

**AZ69 se prueba solo en el arnés.** El proceso real no admite un reloj manual sin un bean de prueba, y agregarlo lo
convertiría en un proceso con dobles. AZ69 (escritor roto y tope `T=2` con cierre de ventana) corre por eso solo en
`AuthorizationEdgeIT`, que el requisito del criterio 2 admite («un arnés con PostgreSQL real»). Para que el arnés no se
aparte del proceso real, `AuthorizationEdgeIT.harnessImportsTheAdministrativeConfiguration` compara por reflexión su lista
de `@Import` con la de `AdminApplication`, salvo el controlador de prueba, y falla ante cualquier diferencia. AZ65 a AZ68 y
AZ70 corren en los dos niveles.

El contador se aísla porque cada prueba usa su propia institución y `DenialWindows` incluye la institución en la clave. No
hace falta un método de reinicio solo para pruebas. Las invocaciones de controlador en procesos con dobles las cuenta un
`HandlerInterceptor` de prueba.

## 5. Concurrencia

| Punto | Comportamiento |
|---|---|
| Dos denegaciones de la misma clave | `compute` es atómico por clave; nunca se pasan de `T` ni se pierde una omisión (AZ79) |
| Inserción de la clave 10 001 | La reserva por compare-and-set nunca supera la cota, aun bajo contención |
| Dos hilos con el barrido vencido | `compareAndSet` sobre el instante siguiente: barre uno solo |
| Barrido frente a una denegación de la misma clave | El barrido corre fuera de toda función de asignación y quita cada entrada con `computeIfPresent` sobre su propia clave; la denegación cae antes en la ventana vieja o después en una nueva, sin pérdida ni doble conteo |
| Asiento completo que falla | `fullRowFailed` devuelve el lugar y suma la denegación a las omisiones dentro de `compute` de la clave; la suma se conserva (AZ80) |
| Cola de pendientes | `LinkedBlockingDeque` acotada; `offerFirst` y `offer` devuelven falso si está llena, y el descarte se cuenta |
| Reintento de `TransactionRunner` | Revierte la inserción y repite; el contador ya decidió fuera de la transacción |
| Denegaciones que disputan `shared_audit_chain_head` | Se serializan por institución. El bloqueo dura una inserción y el tope limita cuántas ocurren |
| Pedido en espera de conexión | La espera del grupo se acota por su tiempo de espera. Si vence, cuenta como fallo: `403` y métrica |
| Cambio de rol durante la petición | La lectura es de lectura confirmada: rige lo confirmado al leer. La petición siguiente ve el cambio (AZ22) |

## 6. Flujo de datos

**Denegación por permiso:**

```
Cliente       Filtros            PermissionDecider    AccountPermissions   Audit+Windows      PostgreSQL
  │ GET /roles   │                      │                    │                  │                 │
  ├─────────────►│ verifica token       │                    │                  │                 │
  │              │ isActive(actor) ─────┼────────────────────┼──────────────────┼─ READ ONLY ────►│
  │              │ AuthorizationFilter  │                    │                  │                 │
  │              ├─────────────────────►│ amr contiene otp   │                  │                 │
  │              │                      ├─ resolve(actor) ──►│ READ ONLY ───────┼────────────────►│
  │              │                      │◄─ {permisos} ──────┤ (cerrada)        │                 │
  │              │◄─ PermissionDenied ──┤                    │                  │                 │
  │              │ ExceptionTranslation │                    │                  │                 │
  │              │ AuditingAccessDenied ┼────────────────────┼─► record() ──────┤                 │
  │              │                      │                    │   FULL_ROW       ├─ BEGIN, INSERT ►│
  │              │                      │                    │   afterDenial    │  COMMIT         │
  │◄─ 403 ───────┤ problems.write(FORBIDDEN)                 │                  │                 │
```

**Fallo de resolución:**

```
PermissionDecider: resolve lanza
   ├─ LOG.error (solo clases y SQLState) + metrics.resolutionFailed()
   └─ throw PermissionResolutionFailedException (sin causa)
AuthorizationFilter ─► ExceptionTranslationFilter: ni AccessDenied ni Authentication ─► relanza
AccessTokenAuthenticationFilter: finally limpia el contexto
RequestContextFilter: response.resetBuffer(); problems.write(INTERNAL_ERROR) ─► 500 con traceId
```

**Cierre de ventana:**

```
denegación N de la cuenta B, después de su propio asiento ─► afterDenial(now)
   ├─ sweepIfDue(now): solo memoria, fuera de compute; como mucho una vez por segundo
   │  (o de inmediato si nextSweepAt está más de 1 s en el futuro), hasta 500 ventanas movidas
   │    ├─ vencida con omitted > 0 ─► computeIfPresent(su clave): cola, entrada quitada, lugar libre
   │    └─ vencida con omitted = 0 ─► computeIfPresent(su clave): entrada quitada, lugar libre
   └─ como mucho 1 de la cola:
        ScopedValue RequestOrigin vacío
        TransactionRunner.execute(contexto de la institución de la ventana)
        INSERT authorization.access.denied_summary
        si falla: offerFirst a la cola (o descarte si está llena) + authorization_denial_audit_failed
```

## 7. Cambios de archivos, por rebanada

Rutas bajo `apps/api/app/src/main/java/com/confia/` salvo indicación.

**PR 1, `permission-catalog-and-adr`.** Crea `apps/api/kernel/src/main/java/com/confia/kernel/Permission.java`,
`identity/domain/StaffRole.java`, `docs/adr/ADR-0027-*.md`. Modifica `docs/03` §5.1 a §5.3, `docs/adr/README.md`, ADR-0005 (nota).
Pruebas: `PermissionTest`, `StaffRoleTest`, `PermissionMatrixParityTest`, regla de pureza de `domain`, `DocumentationNotesTest` y
sus originales.

**PR 2, `role-assignment-and-resolver`.** Crea `db/migration/V14__*.sql`, `shared/security/PermissionResolver.java`,
`identity/application/{StaffRoleAssignmentRepository,AccountPermissions}.java`,
`identity/infrastructure/JooqStaffRoleAssignmentRepository.java`. Modifica `IdentityConfiguration`. Pruebas: `V14SchemaDiffIT`,
`IdentityStaffAccountRoleSchemaIT`, `AccountPermissionsIT`, `AccountPermissionsTest`, `RolePrivilegeMatrixIT`, `StaffRoleFixtures`,
inventario del puerto.

**PR 3, `filter-authorization-and-route-guard`.** Crea en `shared/web/authorization/`: `PermissionedEndpoint`,
`PermissionedEndpoints` (lista vacía en producción hasta el PR 6), `PermissionDecider`, `PermissionDeniedException`,
`PermissionResolutionFailedException`, `RouteCoverageGuard`, `AuthorizationMetrics` (solo `resolutionFailed`); y
`shared/web/edge/AuthorizationConfiguration`, `shared/observability/metrics/LogAuthorizationMetrics`. Modifica `SecurityChains`,
`AdminSecurityConfiguration`, `AccessTokenAuthenticationFilter`, `AuthenticatedEndpoints` (Javadoc), `ActorAuthentication`
(Javadoc), `AdminApplication`, `ObservabilityMetricsConfiguration`, `ProblemExceptionHandler` y `DataAccessFailure`. Pruebas:
`PermissionEdgeTest`, `WebEdgeHarness`, `AuthorizationEdgeIT` (primera parte: orden frente a idempotencia y limitador),
`RouteCoverageGuardTest`, `OpenApiRouteClassificationTest`, `PublicRouteAllowListTest`, `WebEdgeScopeExclusionInventoryTest`,
`ProcessBeanPolicy`, `ProcessBeanIsolationTest`.

**PR 4, `permission-denial-audit`.** Crea `AuditingAccessDeniedHandler`, `PermissionDenialRecorder`, `PermissionDenialAudit`,
`DenialWindows`, `AuthorizationProperties`. Modifica `AuthorizationMetrics`, `LogAuthorizationMetrics`, `SecurityChains`,
`AdminSecurityConfiguration`, `AuthorizationConfiguration`, `application.yml`, `docs/07`. Pruebas: `DenialWindowsTest`,
`PermissionDenialAuditIT`, `LogAuthorizationMetricsTest`, `AuthorizationEdgeIT` (segunda parte: asiento y tope), regla BI137.

**PR 5a, `mfa-policy-derivation`.** Crea `identity/domain/MfaPolicy.java`. Modifica `StaffAccount`, `AuthenticationResult`
(Javadoc), `AuthenticateWithPassword`, `ResetPasswordWithToken`, `JooqStaffAccountRepository`, `IdentityConfiguration`,
`docs/03` §4.3, `ProcessBeanPolicy`. Pruebas: `MfaPolicyTest`, `MfaPolicyProductionWiringTest`, `SecondFactorExemptions`,
`AuthenticateWithPassword*`, `ResetPasswordWithToken*`, `StaffAccountTest`, `LoginEndpointSupport`, `LoginProcess`.

**PR 5b, `drop-mfa-required-column`.** Crea `db/migration/V15__*.sql`. Pruebas: `V15SchemaDiffIT`, ausencia de la columna, y
los 43 archivos que la insertan, salvo los fijados a una versión histórica. Modifica los ayudantes de prueba.

**PR 6, `roles-report-and-criterion-2`.** Crea `identity/application/{ListRolePermissions,RolePermissionsView}.java`,
`identity/web/{RolesReportController,RolesReportResponse}.java`. Modifica `PermissionedEndpoints.forAdmin()`,
`IdentityConfiguration`, `IdentityWebConfiguration`, la instantánea de OpenAPI, `docs/09`, `foundations-plan/exploration.md`.
Pruebas: `RolesReportEndpointIT`, `AuthorizationEdgeIT` (tercera parte: criterio 2), `ExitCriterion2EndToEndIT`, los dos inventarios restantes,
`OpenApiContractSnapshotTest`, `DocumentationNotesTest`.

## 8. Interfaces y contratos

Las firmas de los puertos están en D5, D11 y D14. El resto:

```java
// shared.web.authorization
public record PermissionedEndpoint(HttpMethod method, String pattern, Permission permission) {}

public final class PermissionedEndpoints {
    public PermissionedEndpoints(List<PermissionedEndpoint> endpoints);
    public static PermissionedEndpoints forAdmin(); // exactly GET /api/v1/identity/roles, identity:read
    public List<PermissionedEndpoint> endpoints();
}

public final class PermissionDeniedException extends AccessDeniedException {
    public Permission permission();
    public String routeTemplate();
    public AuthenticatedActor actor();
}

public interface PermissionDenialRecorder {
    /** Records a denial; never throws, whatever fails. */
    void record(PermissionDeniedException denied, UUID requestId);
}

// identity.application
public interface StaffRoleAssignmentRepository {
    Optional<String> findRoleCode(InstitutionId institutionId, StaffAccountId accountId);
}
```

`findRoleCode` devuelve el texto almacenado y no un `StaffRole`, para que AZ19 se decida en un único lugar y un código
desconocido nunca lance.

**Contrato HTTP de `GET /api/v1/identity/roles`:**

| Estado | Código | Cuándo |
|---|---|---|
| `200` | | Con `identity:read` y sesión `[pwd, otp]` viva |
| `401` | `authentication-required`, `token-invalid`, `token-expired` | Sin credencial; alterada, restringida o de una sesión revocada; vencida |
| `403` | `forbidden` | Sin el permiso, o sin `otp` |
| `500` | `internal-error` | Fallo inesperado, incluida la resolución de permisos |

## 9. Estrategia de pruebas, por rebanada (TDD estricto)

Cada fila se escribe en rojo primero. «Control» es la demostración deliberada: romper el código a propósito para ver fallar
la prueba, una vez, y dejar constancia en el progreso.

| PR | Unidad | Integración | Escenarios | Control deliberado |
|---|---|---|---|---|
| 1 | `PermissionTest`, `StaffRoleTest` (cada columna, conteos 89 y 88, ausentes), `PermissionMatrixParityTest` | | AZ01-AZ16, BI132 | Agregar `PAYMENTS_CREATE` a `administrator`; quitar `audit:export` del Auditor en la copia del documento; vaciar la tabla |
| 2 | `AccountPermissionsTest` (sin rol, código desconocido, error) | `AccountPermissionsIT` (dos instituciones, solo lectura), `V14SchemaDiffIT`, RLS, privilegios | AZ17-AZ21, AZ23, I278-I286, BI119-BI124 | Quitar `FORCE` de la tabla; conceder `DELETE`; devolver todos los permisos ante un error |
| 3 | `RouteCoverageGuardTest`, `PermissionDeciderTest` | `PermissionEdgeTest`, `AuthorizationEdgeIT` (orden frente a los interceptores), `OpenApiRouteClassificationTest`, enumerador con cuatro clases | AZ24-AZ36, AZ38, AZ39, AZ78 (la respuesta de AZ30 según la sección 11), AZ60, AZ61, AZ63, AZ64, WE94-WE97, WE101, WE102, WE105, BI128-BI133, BI135 | Quitar la comprobación de `otp`; mover la decisión a un interceptor (WE95 debe fallar); controlador sin clasificar; resolutor que lanza con `SENTINEL-RESOLVER-6`; bean en el portal; derivar la sesión viva con `skip(1)` (prueba de deriva de D6) |
| 4 | `DenialWindowsTest` (`T`, ventana, cota, reloj atrás, contención con cerrojo, barrido fuera de `compute`, compuerta, límite por barrido, cola acotada, fallo de asiento completo), `LogAuthorizationMetricsTest` | `PermissionDenialAuditIT`, `AuthorizationEdgeIT`: asiento, `request_id`, cadena, transacción propia, centinelas, resumen, un resumen por petición, fallo y devolución del resumen, apagado acotado, escritor roto, casos no auditados | AZ37, AZ40-AZ59, AZ62, AZ79, AZ80, WE98-WE100, AT32-AT38, BI137, BI138 | Dejar el origen en el resumen y ver la IP ajena; contar dentro de la transacción y reintentar; responder `500` ante escritor roto; agregar `Thread.sleep` al fixture; barrer dentro de `compute`; escribir toda la cola en una petición; quitar la regla del instante futuro; no devolver el asiento fallido a la suma |
| 5a | `MfaPolicyTest`, `MfaPolicyProductionWiringTest` | `SecondFactorExemptionsProbeIT` (sonda S1), login y restablecimiento con cuentas con y sin rol | I287-I291, I301, I302, escenarios modificados de identidad | Hacer `STAFF` falsa; leer un rol en la política; devolver `Authenticated` para una cuenta sin rol; guardar las exenciones en un campo `static` |
| 5b | | `V15SchemaDiffIT`, ausencia en esquema, jOOQ y árbol de fuentes | I292, BI125-BI127 | Reinsertar una referencia en un fixture actual |
| 6 | `ListRolePermissionsTest` | `RolesReportEndpointIT`, `AuthorizationEdgeIT` (criterio 2 sobre ruta de prueba y real), `ExitCriterion2EndToEndIT`, instantánea | AZ22, AZ29, AZ65-AZ76, AZ77, I293-I300, I303, WE79, WE103, WE104, BI24-BI27, BI134, BI136, BI139 | Quitar la entrada de la lista (debe ser `403` sin asiento); agregar un correo al DTO; agregar un endpoint de bitácora al fixture |

**Precisiones de las pruebas.**

- **AZ19.** En una `StepwiseDatabase` propia, migrada a 14, se elimina la restricción como `confia_owner`, se inserta
  `ghost_role`, y `AccountPermissions` real devuelve el conjunto vacío. Ninguna base compartida ve esa alteración.
- **AZ43.** Una prueba afirma `TransactionSynchronizationManager.isActualTransactionActive()` en falso al entrar el
  grabador, y el asiento existe después de la respuesta.
- **AZ51 y AZ68.** La búsqueda de centinelas recorre `shared_audit_log`, los registros capturados con la raíz en `DEBUG` y el
  doble de métricas. `instance` del Problem repite la ruta al propio cliente, como en toda respuesta, y no forma parte de la
  búsqueda.
- **AZ65 a AZ68 y AZ70.** Se ejecutan dos veces: sobre `AuthorizationEdgeIT`, con contador de invocaciones y reloj
  inyectado, y sobre el proceso real, que demuestra que el cableado de producción es el mismo. **AZ69 solo en el arnés**
  (D19), con `harnessImportsTheAdministrativeConfiguration` como guarda de deriva.
- **Pruebas nombradas de D10.** Todas con `MutableClock`, instancia e institución propias y concurrencia por
  `CountDownLatch` o `CyclicBarrier`, sin tiempo real:

  | Comportamiento | Prueba | Demostración deliberada |
  |---|---|---|
  | Tope exacto con denegaciones concurrentes de una clave (AZ79) | `DenialWindowsTest.concurrentDenialsOfOneKeyNeverExceedTheCap`; `AuthorizationEdgeIT.concurrentDenialsOfOneKeyWriteExactlyTFullRows` | Comparar `full <= T`: `T + 1` filas completas. Una pérdida de actualización por `get` y `put` no se usa como demostración, porque su intercalado no es determinista |
  | Barrido fuera de toda función de asignación | `AuthorizationBoundaryRulesTest.denialWindowsMappingFunctionsTouchNoOtherMapping`: ningún método sintético `lambda$*` de `DenialWindows` invoca un método de `ConcurrentHashMap` ni `sweepIfDue` (por eso el barrido recorre con un bucle `for`, no con una lambda). Además, `DenialWindowsTest.sweepRacingARecordOfTheSameKeyLosesNoDenial` libera con un cerrojo, a la vez, el barrido y una denegación de una clave vencida, y afirma la suma | Invocar `sweepIfDue` desde la función de `record`: la regla falla nombrando el método sintético |
  | Asiento completo que falla (AZ80) | `DenialWindowsTest.failedFullRowReturnsTheSlotAndCountsAsOmitted`; `DenialWindowsTest.failedFullRowOfAClosedWindowQueuesASummaryOfOne`; `PermissionDenialAuditIT.failedFullRowIsCountedInTheSummary` | No invocar `fullRowFailed`: el resumen cuenta uno menos |
  | Un resumen por petición | `PermissionDenialAuditIT.oneDenialWritesAtMostOneSummary`: con 5 resúmenes pendientes, un escritor envuelto que cuenta registra como mucho 2 inserciones (el asiento propio y un resumen) y quedan 4 pendientes | Escribir toda la cola en `afterDenial`: 6 inserciones |
  | Fallo, devolución y corte | `PermissionDenialAuditIT.failedSummaryReturnsToTheHeadAndStopsThePass` (el resumen vuelve primero a la cola, no se intenta otro, métrica una vez) | Devolverlo con `offerLast`: el orden cambia |
  | Cota de la cola | `DenialWindowsTest.pendingQueueDiscardsBeyondItsBound`; `PermissionDenialAuditIT.requeueIntoAFullQueueDiscardsAndCounts` (cota de prueba pequeña) | Cola sin capacidad: el tamaño supera la cota |
  | Límite por barrido | `DenialWindowsTest.sweepMovesAtMostItsLimitAndLeavesTheRest` (límite de prueba 2, cinco ventanas vencidas: dos barridos mueven 2 y 2, el tercero 1) | Quitar el límite: el primer barrido mueve 5 |
  | Compuerta de un segundo | `DenialWindowsTest.sweepRunsAtMostOncePerSecond` (dos barridos en el mismo instante: uno solo trabaja; a 1 s, el segundo) | Quitar el compare-and-set: barren los dos |
  | Instante futuro tras un retroceso | `DenialWindowsTest.farFutureNextSweepInstantIsTreatedAsDue` (barrido a `t`, reloj a `t - 1 h`: barre de inmediato) | Quitar la regla de distancia: no barre |
  | Retroceso en una ventana (DC-1) | `DenialWindowsTest.backwardClockClosesTheWindowAndKeepsTheSum` | Quitar la rama `now < windowStart` |
  | Apagado (DC-2) | `PermissionDenialAuditIT.shutdownWritesPendingSummaries`; `PermissionDenialAuditIT.shutdownStopsAtTheFirstFailure` (un intento y métrica por cada resumen no escrito); `PermissionDenialAuditIT.shutdownStopsWhenItsBudgetIsSpent` (un escritor envuelto avanza el `MutableClock` 4 s por inserción: se escriben 3 y el resto cuenta como no escrito) | Dejar `destroy()` vacío; quitar el corte; quitar el presupuesto |
- **Cobertura y mutación.** `kernel` e `identity.domain` a 95 por ciento y PIT a 80, con `Permission`, `StaffRole` y `MfaPolicy`
  dentro. `MfaPolicy.STAFF` mutado a falso debe morir.

## 10. Entrega, migración y puesta en marcha

### 10.1 Rebanadas

Cadena de siete PR apilados aprobada (Q5). Las pruebas no se separan del código. Las cifras son estimaciones, no
mediciones. El factor histórico de subestimación es alto, de modo que cada rebanada revisa su cuenta antes de abrir el PR.

| PR | Rama / contenido | Producción | Pruebas | Doc. | Total efectivo estimado | Margen frente a 800 |
|---|---|---|---|---|---|---|
| 1 | `permission-catalog-and-adr`: catálogo, matriz, paridad, ADR-0027, notas de §5 | 230 | 270 | 240 | **740** | Justo |
| 2 | `role-assignment-and-resolver`: V14, puerto, adaptador, RLS, privilegios | 150 | 480 | 10 | **640** | Cómodo |
| 3 | `filter-authorization-and-route-guard`: decisor, lista, guarda, `otp`, sesión viva, fallo de resolución, inversiones | 340 | 440 | 10 | **790** | Muy justo |
| 4 | `permission-denial-audit`: decorador, asiento, tope, resumen, métricas, fallo | 340 | 420 | 20 | **780** | Muy justo |
| 5a | `mfa-policy-derivation`: política, sin lectura de columna, exenciones de prueba, §4.3 | 70 | 480 | 20 | **570** | Cómodo |
| 5b | `drop-mfa-required-column`: V15, retiro de fixtures | 5 | 340 | 0 | **345** | Cómodo |
| 6 | `roles-report-and-criterion-2`: informe, entrada de producción, criterio 2, `docs/09` | 150 | 520 | 50 | **720** | Justo |
| | **Total** | **1 285** | **2 950** | **350** | **4 585** | |

**Si una rebanada se pasa.** PR 3: la guarda y sus pruebas pasan a un PR 3b, sin romper el orden. PR 4: las métricas y su
adaptador van primero en un PR 4a. PR 1: los originales de `docs/03` como fixtures de recursos, si `docs/15` los excluye
como líneas no efectivas. El orden sigue siendo el de la propuesta: 1 antes de todo; 2 antes de 3 y de 5a; 3 antes de 4; 3 y
4 antes de 6; 5a antes de 5b.

### 10.2 Migraciones

| Migración | Rebanada | Contenido | Prueba |
|---|---|---|---|
| `V14__create_identity_staff_account_role.sql` | 2 | Tabla, restricciones, RLS, privilegios (D4) | `V14SchemaDiffIT`, `MultiTenantSchemaIT`, `RolePrivilegeMatrixIT` |
| `V15__drop_identity_staff_account_mfa_required.sql` | 5b | Solo el `DROP COLUMN` (D15) | `V15SchemaDiffIT` |

V1 a V13 no se editan. No existe ningún entorno desplegado ni dato real.

### 10.3 Puesta en marcha y reversión

No hay indicador de función. Hasta el PR 6 la lista de producción está vacía, de modo que los PR 3 a 5 no cambian el
comportamiento de ninguna ruta real, salvo la derivación de MFA. Cada PR se revierte con su commit de fusión, en orden
inverso. Revertir la decisión deja las rutas bajo `denyAll`, que falla cerrado. Los asientos escritos no se borran (regla
5). Después de producción, V15 solo se revierte con una migración nueva.

**Variables:** ninguna variable nueva obligatoria y ningún secreto. Un proceso sin la asignación de ningún rol arranca y
deniega todo, que es el estado esperado hasta la creación del primer Super Admin en 8b.

## 11. Desviaciones respecto de la propuesta y cambios recomendados en la especificación

**Desviaciones de la lista de áreas de la propuesta**, sin cambio de alcance: `PermissionDeniedException`, el decisor, la lista
y la guarda viven en `shared.web.authorization` y no en `shared.security` (D1). Los códigos viven en `kernel` (D2), lo que la
propuesta permite. `ProblemAccessDeniedHandler` no cambia y lo envuelve un decorador (D8).

**Cambios recomendados en la especificación.** No se editó ninguno. Requieren actualización antes de las tareas:

| # | Escenario o requisito | Cambio recomendado | Por qué |
|---|---|---|---|
| 1 | BI131 y el requisito «Las reglas de dependencia...» | «El puerto en `shared.security` y los códigos de permiso en `kernel`» | D2: `domain` no puede importar `shared.security` según `docs/01` |
| 2 | AZ30 | Pasa a demostración de compilación: un fixture permanente con un texto en lugar de `Permission` no compila. La guarda sigue fallando por AZ35 | D13: con un parámetro tipado, no existe tal lista en ejecución |
| 3 | AZ69 | «La tercera denegación no escribe fila completa, responde `403` e incrementa la métrica; al cerrar la ventana y disparar la emisión existe la fila de resumen con conteo 1» | La redacción actual pide el resumen en la tercera denegación, y AZ53 y AT35 lo piden al cerrar la ventana, con un solo resumen por ventana. No se pueden cumplir ambas en una tabla de solo inserción |
| 4 | BI126 | Exceptuar las pruebas fijadas a una versión anterior a V15 con `StepwiseDatabase.migratedTo`, y buscar sin distinguir mayúsculas | `V9ExistingRowsIT` inserta en una versión donde la columna es obligatoria; `getMfaRequired` no aparece en la búsqueda actual |
| 5 | Requisito «Permisos de acceso a la tabla de asignación» y el encabezado de BI122 | `confia_admin_app`: solo `SELECT` | D4; el delta ya lo prevé como valor que confirma el diseño |
| 6 | Requisito «Criterio de salida 2» | Reemplazar «(`HarnessProcess`)» por «proceso administrativo real y un arnés con PostgreSQL real» | `HarnessProcess` no tiene `DataSource` (D19) |
| 7 | **Nuevo:** AZ78 | «DADO un token `[pwd, otp]` vigente cuya sesión fue revocada, CUANDO se presenta a una ruta con permiso, ENTONCES responde `401 token-invalid`, el resolutor registra cero invocaciones y no hay asiento de denegación» | D6: agrega una comprobación que ningún escenario fija |

Todo lo demás queda cumplido sin cambios: `Authenticated` y `MfaPolicy` (D14), `500 internal-error` en AZ63 y WE101 (D7) y la
ausencia de asiento por falta de `otp` en AZ25 (D12).

## 12. Riesgos

| Riesgo | Prob. | Mitigación |
|---|---|---|
| El PR 3 o el PR 4 supera 800 líneas | Alta | Punto de corte definido en 10.1 y revisión de la cuenta antes de abrir el PR |
| Más de 15 tareas en `tasks.md` | Media | Una tarea por rebanada con subtareas. La fase de tareas lo verifica |
| Un resumen se atribuye a la IP de otra persona | Media | Origen enlazado de nuevo a uno vacío, con prueba negativa (D10) |
| Se pierden conteos pendientes en una caída no ordenada, por cola llena o por agotar el presupuesto de apagado | Baja | Métricas ya publicadas al omitir y al descartar; límites declarados en D10 y ADR-0027 |
| Una respuesta `403` tarda por los resúmenes de otras cuentas | Baja | Como mucho un resumen por petición (D10), con `PermissionDenialAuditIT.oneDenialWritesAtMostOneSummary` |
| La lista de sesión viva se aparta de la lista de rutas con permiso | Baja | Una sola instancia de `PermissionedEndpoints` y prueba de deriva por entrada (D6) |
| El arnés de `AuthorizationEdgeIT` se aparta del proceso real, y AZ69 deja de probar el cableado | Baja | `harnessImportsTheAdministrativeConfiguration` (D19) |
| El resumen tarda mucho si cesan las denegaciones | Media | `windowStart` en el detalle; emisión al apagar; límite declarado |
| Tiempo observable distinto entre una ruta con permiso y una sin mapear, por el asiento | Baja | Requiere una cuenta con sesión completa y MFA, y WE99 cubre solo el contenido. Se acepta para F0 |
| Dos lecturas por petición con permiso | Baja | Ambas por clave primaria; se medirá con k6 cuando exista la primera ruta de volumen |
| `spring.main.sources` no alcanza a `ConfiaApplication.launch` | Baja | Sonda S1 y alternativa definida en D14 |
| Un bean de prueba de `MfaPolicy` llega a producción | Muy baja | La clase solo existe en `src/test`; `MfaPolicyProductionWiringTest` afirma `STAFF` en el proceso real |
| Una firma de Spring Security 7.1.1 difiere de lo supuesto | Baja | La primera prueba en rojo del PR 3 la confirma |
| Orval rechaza `x-required-permission` | Baja | Se verifica en el PR 6. La alternativa es llevar el permiso en la descripción y ajustar BI139 |
| Asignación de rol a mitad de una sesión sin MFA | Media | Toda ruta con permiso exige `otp`; la revocación al cambiar el rol es de 8b |
| Las pruebas fijadas a una versión histórica se rompen al retirar la columna de `PurgeSession` | Media | Inventario al inicio de 5b (D15) |
| Roles inaccesibles hasta 8b | Cierta | Esperado (O5): los roles solo se asignan en pruebas y el proceso deniega todo |

## 13. Matriz de amenazas

La matriz de la fase de diseño no aplica a su alcance: el cambio no toca órdenes de consola, subprocesos, automatización de
Git o de PR ni clasificación de archivos ejecutables.

| Frontera | Aplicabilidad |
|---|---|
| Rutas con apariencia de documentación | N/A: no se clasifican ni ejecutan archivos |
| Selección de repositorio Git, estado del commit, estado del push, órdenes de PR | N/A: sin automatización de Git ni de PR |

Fronteras de seguridad propias, cada una con prueba en rojo en la sección 9:

| Frontera | Comportamiento seguro | Prueba |
|---|---|---|
| Afirmación de permiso del cliente | Solo decide lo resuelto en el servidor | `PermissionEdgeTest` (AZ31) |
| Variantes de ruta y otros métodos | Sin coincidencia caen en `denyAll`, nunca `2xx` | `PermissionEdgeTest` (AZ32, AZ33) |
| Token `[pwd]` | `403` sin consultar al resolutor | `PermissionEdgeTest` (AZ25) |
| Token restringido | `401`, por su audiencia | `PermissionEdgeTest` (AZ28) |
| Sesión revocada | `401 token-invalid` | `PermissionEdgeTest` (AZ78 propuesto) |
| Otra institución | Institución solo del token; RLS forzada | `AccountPermissionsIT` (AZ20, I285) |
| Error de lectura | No concede, no es `forbidden`, no consume el tope | `PermissionEdgeTest`, `AuthorizationEdgeIT` (AZ60-AZ64) |
| Oráculo de rutas | Cuerpo idéntico al de una ruta sin mapear | `PermissionEdgeTest` (WE99, WE100) |
| Oráculo de idempotencia y limitador | Rechazo previo a los interceptores | `AuthorizationEdgeIT` (WE95, WE96) |
| Datos de la petición en la bitácora, métricas o registros | Plantilla de la lista; sin mensajes de excepción | `AuthorizationEdgeIT` (AZ51, AT33) |
| Inundación de la bitácora | `T` por clave y estado acotado | `DenialWindowsTest`, `AuthorizationEdgeIT` |
| Atribución falsa de un resumen | Origen vacío en el resumen | `PermissionDenialAuditIT`, `AuthorizationEdgeIT` |
| Ruta nueva sin clasificar | Arranque fallido; `denyAll` como red final | `RouteCoverageGuardTest`, `OpenApiRouteClassificationTest` |
| Beans en otro proceso | Lista de permitidos | `ProcessBeanIsolationTest` (AZ39, BI129) |
| Política de MFA debilitada | Constante, sin propiedad, comprobada en el proceso real | `MfaPolicyProductionWiringTest` (I302) |
| Autoasignación de rol por un defecto | La aplicación no tiene privilegio de escritura | `RolePrivilegeMatrixIT` (BI122) |

## 14. Decisiones para el propietario

Las decisiones técnicas están tomadas. Solo esto necesita al propietario:

1. **Aprobar ADR-0027**, que nace `Propuesto`, y este diseño. Reemplaza partes de un ADR aceptado.
2. **Aceptar los siete cambios de especificación de la sección 11**, en especial el 7, que agrega la exigencia de sesión viva
   a toda ruta con permiso a costa de una lectura más por petición. Sin él, una sesión cerrada conserva acceso con permiso
   hasta que venza su token, hasta diez minutos.
3. **Pendiente ajeno a este diseño:** la propuesta deja «a confirmar» el dueño de los datos ficticios de desarrollo
   (entregable 11).

Ninguna pregunta técnica bloquea la fase de tareas.

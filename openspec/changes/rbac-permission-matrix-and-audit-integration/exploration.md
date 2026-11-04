# Exploración: rbac-permission-matrix-and-audit-integration

- **Cambio:** F0, cambio 8 del plan de cimientos (`openspec/changes/foundations-plan/exploration.md`, fila 8).
- **Alcance del plan:** entregable 4 (brecha A7) y semilla mínima; cierra el criterio de salida 2 de la fase 0 («Un usuario sin permiso recibe 403 y el intento queda en `AuditLog`»).
- **Fecha:** 2026-11-03.
- **Copia en Engram:** `sdd/rbac-permission-matrix-and-audit-integration/explore` (observación 758).

## 1. Estado actual

### No existe ningún rol ni permiso

- El principal no lleva autoridades: `ActorAuthentication.getAuthorities()` devuelve `List.of()` (`shared/web/authentication/ActorAuthentication.java:33-35`).
- El token de acceso tiene exactamente 9 claims y ninguno de permisos (`shared/security/token/AccessTokenClaims.java:18,36-37`). La decisión 11 del propietario dejó ese claim para este cambio.
- `AuthenticatedActor` no tiene rol (`shared/security/AuthenticatedActor.java:28-29`).
- Las migraciones V1 a V13 no tienen tabla de roles. V6:7 agrega `identity_staff_account.mfa_required BOOLEAN NOT NULL`, una segunda fuente de verdad que este cambio debe retirar (`foundations-plan/exploration.md:205-217`).
- No existe la guarda de `docs/03` §5.1 que hace fallar el arranque si un controlador no declara permiso.

### Cadena de seguridad

- `SecurityChains.java:63-76`: rutas públicas con `permitAll`, listas autenticadas y al final `anyRequest().denyAll()`.
- `ProblemAccessDeniedHandler.java:22-26` responde 403 `forbidden` y no audita nada.
- `AuthenticatedEndpoints.java:12-13` dice que la matriz reemplaza su lista en este cambio. Conviene conservarla para las rutas de autoservicio, porque todos los roles tienen «MFA propia» en la matriz.
- La cadena del portal es la misma cadena sin lista autenticada (`PortalSecurityConfiguration.java:21-25`).

### Rutas de producción

Todas son públicas (inicio de sesión, refresco, cierre de la sesión actual), solo autenticadas (`GET` de la sesión actual, `DELETE` de sesiones) o con MFA restringida (2 rutas). **Ninguna necesita un permiso de negocio**, así que el criterio 2 necesita una ruta que lo tenga.

### Bitácora de auditoría

- `shared_audit_log` ya admite `outcome='denied'` y `actor_kind` `staff`, `guardian` o `system` (V2:51-52). El rol del portal no tiene permiso sobre la tabla (V2:113-115).
- El escritor es `AuditLogWriter.append(AuditEntry)`, con 16 columnas; `RequestOriginAuditLogWriter` añade IP y agente de usuario.
- Precedentes de denegación: `AuthenticateWithPassword:259`, `LogIn:179-182` (fila agregada de dispersión de fallos) y `ResetPasswordWithToken:269`.
- El `actor_label` del personal es el identificador de la cuenta (`CompleteSecondFactor`), nunca el correo.
- Cada inserción se serializa por institución con el bloqueo de fila de `shared_audit_chain_head` (V3:194-196), y la tabla solo admite inserciones.

### Restricciones de arquitectura

- `shared` nunca importa módulos (`SharedBoundaryRulesTest:34-42`), y `web` nunca toca infraestructura ni jOOQ.
- Las tablas llevan el prefijo de su módulo (`TableOwnershipByModuleTest:37-50`).
- ADR-0017 cierra la lista de tablas permitidas sin `institution_id` y sin seguridad de fila; una tabla global de permisos necesitaría un ADR nuevo.
- Hay un solo `TransactionRunner`, que ya ofrece `executeReadOnly` (`TransactionRunner.java:98`).
- La lista permitida `ADMIN` de `ProcessBeanPolicy` se edita en el mismo PR que cualquier paquete de beans nuevo (`ProcessBeanPolicy.java:56-116`).
- Precedente de puerto: `SessionValidity` vive en `shared.security` y lo implementa `identity`.

## 2. Obligaciones heredadas por este cambio

- La matriz, el claim de permisos, la cobertura de MFA por ruta y la guarda de arranque.
- La desactivación de cuentas y el motivo de revocación `admin_revoke` (propuesta de `session-tokens-and-web-layer`:184).
- La inscripción y retirada voluntarias de MFA, la reautenticación reforzada y las rutas que exigen OTP (propuesta de `session-endpoints-cookie-and-csrf`:175).
- Derivar `mfa_required` de los roles, y la desactivación (misma propuesta, :176).
- El restablecimiento administrativo de contraseña (spec de `identity`:2356-2364).
- El permiso `audit:read`, aplicado en la capa web y no por seguridad de fila (spec de `audit-trail`:406-428).
- Demostrar que la primera anotación real de permiso lanza `AccessDeniedException` dentro de MVC (`web-edge-foundations/design.md:1296-1301`).
- La revisión DA-15.
- Invertir `AccountDeactivationAbsenceTest` y `NoFullSessionMfaRoutesTest`.

## 3. Contradicciones en la documentación

1. `docs/03` §5.3, fila 1: el Cajero inicia una anulación, pero §5.2, regla 4, dice que el Cajero no puede anular, y no existe el verbo «solicitar anulación».
2. §5.2, regla 3: el Auditor es el único rol con lectura completa de la bitácora, pero la matriz da a Super Admin `L X` y a Admin `L` sobre `audit`.
3. ADR-0005 hace la MFA obligatoria para cajero, contador y administrador. `docs/03` §4.3 la hace obligatoria también para Super Admin y Auditor, y para el Coordinador si puede exportar.
4. §5.1 declara los permisos por anotación. §5.2 dice que la matriz son datos de la migración de semilla. `docs/02` modela el Rol por institución y el Permiso como catálogo del sistema, lo que choca con ADR-0017.
5. El informe de segregación de funciones de §5.3 («ningún usuario con los dos permisos de un par») choca con que Super Admin tenga crear y aprobar.

## 4. Decisiones técnicas y recomendación

| Decisión | Recomendación | Alternativa principal |
|---|---|---|
| Modelo | Siete roles fijos en un catálogo en código, con permisos `módulo:verbo` (seis verbos). La base solo guarda la asignación, `identity_staff_account_role(institution_id, account_id, role_code)`, con un CHECK sobre el conjunto cerrado de roles y seguridad de fila. Evita una excepción a ADR-0017 y la semilla por institución. | Tablas `role` y `role_permission` por institución: admite roles a medida, pero exige sembrar cada institución nueva. |
| Ubicación de la matriz | En código. Una prueba lee la tabla de `docs/03` §5.2 y la compara, como `DocumentationNotesTest`. | Tablas y migración de semilla, como dice §5.2 al pie de la letra. |
| Punto de decisión | Un `AuthorizationManager` en el filtro, sobre una lista cerrada `PermissionedEndpoints` (método, patrón, permiso), más una guarda de cobertura de rutas. Corre antes de los interceptores de idempotencia y de límite de tasa, así que un actor sin permiso no aprende nada de ellos; y como no hay transacción de negocio abierta, la auditoría de la denegación no se puede revertir. | `@RequirePermission` con seguridad de métodos, como dice §5.1, pero corre después de los interceptores y dentro de la transacción. También se puede resolver la anotación de forma diferida por `HandlerMapping`, pero es compleja y arriesga diferencias de normalización de rutas. |
| Frescura de los permisos | Consulta por petición a través de un puerto nuevo, `PermissionResolver`, de solo lectura y que falla cerrado. La revocación es inmediata y el token no cambia. ADR-0005 dice que los permisos viajan en el token y los ADR aceptados no se editan, así que hace falta un ADR nuevo. | Un claim `permissions`: hasta 10 minutos de permisos viejos, token más grande y cambios en emisor y verificador. Un claim `roles` queda entre las dos. |
| Auditoría de la denegación | `PermissionDeniedException` lleva el código del permiso. El gancho está en `ProblemAccessDeniedHandler` y escribe en su propia transacción con el contexto del actor. El asiento usa `action=authorization.access.denied`, el código del permiso como `entity_id` y la plantilla de la ruta; nunca la ruta cruda, la consulta, el cuerpo ni las cabeceras. `request_id` coincide con el `traceId` del Problem. | |
| Qué 403 se auditan | No el de ruta sin mapear, no el de CSRF y no el 404 a nivel de objeto. Si falla la escritura del asiento, se responde igual 403 y se emite una métrica de error. | |
| Control de inundación | Una ventana acotada en memoria por actor y permiso: filas completas hasta un tope, luego una fila de resumen y una métrica. Sigue los precedentes de `LogIn` y `InMemoryRateLimiter`. El riesgo es la contención del bloqueo de la cabeza de la cadena y el crecimiento de una tabla que solo admite inserciones. | |
| Alcance por institución | Los roles son por institución de forma implícita, porque la clave de la cuenta es `(institution_id, id)`. La institución sale solo del token (ADR-0009). | |
| Portal | Sin control de acceso por roles en este cambio. La columna de encargados queda solo documentada y pertenece a F8. Los beans de permisos son solo del proceso administrativo, por `ProcessBeanPolicy`. | |
| `mfa_required` | Derivarlo de los roles con una `MfaPolicy` y retirar la columna en su propia migración y PR. Aparece unas 96 veces en 45 archivos de prueba, así que habrá mucho cambio en las pruebas. | |
| Prueba del criterio 2 | Prueba de integración con `HarnessProcess`, PostgreSQL real y un token real de inicio de sesión con MFA. Un usuario sin el permiso recibe 403 `forbidden`, el controlador se invoca 0 veces y existe exactamente un asiento `denied` cuyo `request_id` coincide con el `traceId` de la respuesta. Controles: un usuario con el permiso recibe 200 y no deja asiento; el verificador de la cadena sigue pasando; una búsqueda de centinelas no encuentra fugas; y el camino de fallo de la auditoría sigue respondiendo 403. | |
| Rutas para la prueba | Un controlador del árbol de pruebas (precedente de idempotencia) y una ruta real de solo lectura. Candidata: `GET /api/v1/identity/roles`, el informe «quién puede hacer qué», sin datos personales. | |

## 5. Decisiones del propietario

Las recomendaciones anteriores son técnicas. Estas decisiones son del propietario y no se tomaron:

- **O1.** ¿Roles fijos, o configurables por institución?
- **O2.** Confirmar la granularidad `módulo:verbo` y aprobar cada celda de la matriz. Resolver las contradicciones 1, 2 y 5, incluido si hace falta un permiso «solicitar anulación».
- **O3.** ¿Puede un usuario tener varios roles? ¿Qué combinaciones están prohibidas?
- **O4.** La regla de MFA por rol (`docs/03` §4.3 frente a ADR-0005) y la regla para derivar `mfa_required`.
- **O5.** Cómo se crea el primer Super Admin, sin un secreto en una migración ni en el repositorio. ¿Entran o no los datos ficticios de desarrollo (entregable 11)?
- **O6.** ¿Incluye este cambio los endpoints de administración, o van en un cambio posterior? Son la asignación de roles, la desactivación, `admin_revoke`, la MFA voluntaria, la reautenticación reforzada, el restablecimiento administrativo de contraseña y la lectura de la bitácora.
- **O7.** ¿Qué ruta real lleva el primer permiso?
- **O8.** La política de auditoría de denegaciones: ¿cada denegación tal cual, o con tope y resumen? ¿Qué topes, qué denegaciones cuentan y si se alerta?
- **O9.** Aceptar la frescura por petición (o una ventana con claim) y un ADR nuevo que reemplace esa parte de ADR-0005.
- **O10.** Confirmar que el Super Admin es por institución y no de plataforma, y que el control de acceso de los encargados espera a F8.

## 6. Tamaño

Unas 3 400 a 4 100 líneas: entre 1 100 y 1 400 de producción, entre 1 800 y 2 200 de pruebas y unas 500 de documentación y spec.

| PR | Contenido |
|---|---|
| 1 | Catálogo, prueba de paridad con la documentación y el ADR nuevo |
| 2 | Persistencia y resolutor, con las pruebas de seguridad de fila |
| 3 | Aplicación, guarda, reglas de ArchUnit y `ProcessBeanPolicy` |
| 4 | Auditoría de denegaciones y control de inundación |
| 5 | Derivación y retirada de `mfa_required` |
| 6 | Prueba de extremo a extremo, ruta real y cierre |

Son unas 14 a 16 tareas, frente al tope de 15 tareas de `openspec/config.yaml` y las ~10 del plan. Si O6 trae los endpoints de administración, conviene dividir en 8a y 8b.

## 7. Riesgos

- Crecimiento del alcance por la lista heredada.
- Contradicciones en la documentación que bloquean la matriz hasta que el propietario las resuelva.
- Hace falta un ADR nuevo, porque ADR-0005 no se puede editar.
- Inundación de la bitácora: contención del bloqueo de la cabeza de la cadena y crecimiento de la tabla.
- Mucho cambio en las pruebas al retirar `mfa_required`.
- Asignar un rol a mitad de sesión sin MFA deja un hueco. Se mitiga revocando las sesiones al cambiar el rol, que es `admin_revoke`.

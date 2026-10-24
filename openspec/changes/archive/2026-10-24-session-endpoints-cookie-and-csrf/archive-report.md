# Informe de archivo: endpoints de sesión, cookie y CSRF

- **Cambio:** `session-endpoints-cookie-and-csrf` (F0, cambio 7, parte 4b, S2, segundo de tres)
- **Archivado:** 2026-10-24
- **Modo de almacenamiento:** hybrid (rutas de archivo para los artefactos; Engram para este informe)
- **Base del cambio:** `main` en `56f63ea`; el branch `change/session-endpoints-cookie-and-csrf-10-archive` se creó desde ese commit
- **Estado de cierre:** completo en implementación. 8 de 8 tareas marcadas `[x]` en `tasks.md` (1.1, 1.2, 1.3, 2.1, 3.1, 3.2, 3.3 y 4.1), 0 casillas abiertas. Verificación con advertencias (`PASS WITH WARNINGS`, 0 CRITICAL), sin bloqueantes. Las advertencias se tratan en este archivo o quedan transferidas con dueño, según la sección correspondiente.
- **Condiciones duras declaradas cerradas en este cambio:** condición 3 (CSRF de doble envío antes de la primera cookie) y H1 (control por IP antes del inicio de sesión). Ver la sección «Condiciones duras».

## Artefactos

| Artefacto | Ubicación | Estado |
|-----------|-----------|--------|
| proposal.md | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/proposal.md` | presente |
| design.md | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/design.md` | presente |
| exploration.md | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/exploration.md` | presente |
| specs/identity/spec.md (delta) | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/specs/identity/spec.md` | presente |
| specs/web-edge/spec.md (delta) | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/specs/web-edge/spec.md` | presente; corregido WE73 antes de la fusión (ver «Corrección WE73») |
| specs/build-integrity/spec.md (delta) | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/specs/build-integrity/spec.md` | presente |
| specs/audit-trail/spec.md (delta) | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/specs/audit-trail/spec.md` | presente |
| tasks.md | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/tasks.md` | presente; 8 de 8 `[x]`, 0 abiertas |
| apply-progress.md | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/apply-progress.md` | presente (instantánea intermedia) |
| verify-report.md | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/verify-report.md` | presente; antes sin versionar, se incorpora en el commit de archivo |
| archive-report.md | `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf/archive-report.md` | este informe |

Observaciones de Engram usadas como fuente de trazabilidad (los archivos del disco son la fuente completa): proposal #634, design #635, spec #636, tasks #637, apply-progress (tarea 4.1) #648, verify-report #650, exploración #633.

## Síntesis de implementación

El cambio entregó los endpoints de sesión (`POST /api/v1/auth/sessions`, `POST /api/v1/auth/mfa/verifications`, `POST /api/v1/auth/mfa/enrollments`, `POST /api/v1/auth/sessions/refresh`, `DELETE /api/v1/auth/sessions/current`, `DELETE /api/v1/auth/sessions`, `GET /api/v1/auth/sessions/current`), la cookie de sesión con su ayudante único (`SessionCookies`), el token CSRF de doble envío vinculado al refresco con HMAC, el filtro de `Origin` y CSRF, las políticas de límite de tasa de sesión, el control por IP del inicio de sesión (H1), la revocación por vinculación al refrescar, la revocación por cambio de MFA con la migración `V9` (solo sustituye el `CHECK` del catálogo de motivos), `logout`, y la auditoría mediante un constructor JSON común (`AuditJson`).

Las ocho tareas se entregaron en las ramas apiladas de los PR #179 a #186 (07a a 07d, 08a a 08c, 09). La última integración visible en `main` es el merge del PR #186 (`56f63ea`).

### Cifras de verificación (instantánea)

Fuente: `verify-report.md` (verificación del árbol `56f63ea`, fecha 2026-10-24; observación #650). Es una instantánea del momento de la verificación. No se volvió a ejecutar la construcción durante el archivo.

- Escenarios de los deltas: 216 (identity 103, web-edge 74, build-integrity 33, audit-trail 6). 202 con identificador y 14 citados por título.
- Trazabilidad: 161 identificadores aparecen literalmente en pruebas o documentación. 41 no aparecen literalmente (ver seguimiento S2).
- Puntos de mayor riesgo revisados por muestreo: conformes (CSRF y `Origin`, atributos de cookie, revocación por vinculación, gracia y reutilización, cierres, límites de tasa y H1, uniformidad de `session-expired`, auditoría solo por `AuditJson`, `V9`, puerta de tiempo, redacción).
- Reglas no negociables: conformes según el informe.
- Construcción: el informe registra un `BUILD FAILURE` en la copia de trabajo local (2 errores de Surefire por enlaces rotos en `apps/admin-web/node_modules`, ver W1). Ese fallo fue ambiental y el informe indica que la exportación limpia pasa completa.

### Estado final de la advertencia W1

Fuente de mayor rango: instrucción de cierre del propietario (2026-10-24). W1 era ambiental: el directorio no versionado `apps/*/node_modules` se retiró y las dos pruebas afectadas (`IdentityScopeExclusionInventoryTest` y `WebEdgePropertiesTest`) pasan. Comprobación observada en el archivo: no existe ningún `apps/*/node_modules` en el árbol de trabajo. El paso de las dos pruebas se toma de la instrucción de cierre y no se volvió a ejecutar aquí.

## Corrección WE73 (advertencia W3)

Antes de la fusión, en `specs/web-edge/spec.md` del delta (escenario [WE73]), la propiedad `confia.web.rate-limit.admin-session.requests` se corrigió a `confia.web.rate-limit.admin-session.request-limit`, que es el nombre real en `application.yml`. Se añadió, tras los pasos del escenario, una nota en el formato de corrección ya usado en la especificación (`> **Corrección del 2026-10-24 (verify-report.md, W3).** ...`). El texto corregido es el que entró en `openspec/specs/web-edge/spec.md`.

## Fusión de especificaciones

Los cuatro deltas se fusionaron con el comando nativo `gentle-ai sdd-archive-compose` sobre copias de trabajo fuera del repositorio. Los cuatro dominios ya existían, así que ninguno se copió como especificación completa.

| Dominio | Requisitos antes | ADDED | MODIFIED | RENAMED | REMOVED | Requisitos después | Escenarios antes → después | Líneas antes → después |
|---------|------------------|-------|----------|---------|---------|--------------------|----------------------------|------------------------|
| identity | 71 | 21 | 7 | 1 (en copia de trabajo, ver conflicto 3) | 3 | 89 | 205 → 281 | 2 431 → 3 194 |
| web-edge | 49 | 14 | 5 | 0 | 2 | 61 | 168 → 216 | 1 639 → 2 125 |
| build-integrity | 76 | 8 | 2 | 1 | 0 | 84 | 211 → 232 | 2 306 → 2 533 |
| audit-trail | 10 | 1 | 0 | 0 | 0 | 11 | 27 → 33 | 338 → 387 |

Los recuentos cuadran con los deltas: requisitos después = antes + ADDED − REMOVED. Los MODIFIED sustituyen el cuerpo en su sitio y no cambian el número. Los REMOVED no se emiten en la salida. En `audit-trail` no se eliminó ninguna línea de la especificación principal (solo adiciones).

### Conflictos de fusión y cómo se resolvieron

1. **Encabezados de requisito.** El comando espera `### Requirement:`, y las especificaciones del repositorio usan `### Requisito:` (en todas, sin excepción). Primer intento sobre los originales: el comando rechazó `openspec/specs/web-edge/spec.md` y los demás por falta de encabezados `Requirement:`, sin escribir nada. Resolución: copias de trabajo en el directorio temporal de sesión con el encabezado traducido; la salida se devolvió a `### Requisito:` con la sustitución inversa. Ninguna transformación toca el cuerpo de los requisitos. Mismo procedimiento que en `session-tokens-and-web-layer`.
2. **Nota `(Reason: ...)` en varias líneas.** El comando exige la nota en una sola línea, y `web-edge` y `build-integrity` la tienen repartida. Resolución: se unieron las líneas solo en las copias de trabajo. Los REMOVED no se emiten en la salida, así que su texto no entra en la especificación principal. El delta archivado conserva su redacción original.
3. **Requisito MODIFIED con nombre nuevo (identity).** El delta modifica «La familia registra la huella del agente de usuario y la IP inicial, sin aplicarlas todavía», pero su encabezado MODIFIED ya usa el nombre nuevo «…y la vinculación se aplica al refrescar». El comando no encuentra el original. Resolución: en la copia de trabajo se añadió una sección RENAMED (nombre antiguo → nombre nuevo) con su motivo, y el cuerpo MODIFIED sustituye el requisito renombrado. El delta archivado no se alteró. Es la forma de renombrado que prevé la convención del comando.
4. **Colisión de nombre ADDED (build-integrity). Requiere confirmación del propietario.** El delta añade «Los inventarios de ausencia que este cambio rompe se invierten, cada uno con su control negativo», y ese mismo nombre ya existe en `openspec/specs/build-integrity/spec.md`, introducido por `session-tokens-and-web-layer` (archivado el 2026-10-19). Son dos requisitos distintos (el de este cambio habla de los endpoints de sesión), y sustituir el existente habría borrado el contenido del cambio anterior. Resolución: en la especificación principal, el requisito nuevo quedó como «Los inventarios de ausencia que los endpoints de sesión rompen se invierten, cada uno con su control negativo». El requisito anterior no se tocó. **Divergencia deliberada:** el delta archivado en `specs/build-integrity/spec.md` conserva el nombre original; solo la especificación principal usa el nombre nuevo. Si el propietario prefiere otro nombre, debe corregirse en la especificación principal y en este informe.

### Verificación de la fusión (`diff -r`)

Cada especificación principal se comparó con la salida fusionada inmediatamente antes de ubicarla (`mv` de un archivo temporal `.compose-tmp` hacia la ruta definitiva, para que la escritura sea atómica). Salida literal de cada comparación de readback:

```
$ diff -r <salida fusionada identity> openspec/specs/identity/spec.md
(salida vacía; exit 0)
$ diff -r <salida fusionada web-edge> openspec/specs/web-edge/spec.md
(salida vacía; exit 0)
$ diff -r <salida fusionada build-integrity> openspec/specs/build-integrity/spec.md
(salida vacía; exit 0)
$ diff -r <salida fusionada audit-trail> openspec/specs/audit-trail/spec.md
(salida vacía; exit 0)
```

Comprobación adicional sobre los encabezados de requisito frente a `main`: en `identity` desaparecen 4 encabezados (3 REMOVED y el nombre antiguo del RENAMED) y aparecen 21 nuevos más el nombre nuevo del RENAMED; en `web-edge` desaparecen 2 (REMOVED) y aparecen 14 nuevos; en `build-integrity` desaparece 1 (nombre antiguo del RENAMED) y aparecen 8 nuevos. Los MODIFIED conservan su encabezado. `audit-trail` no pierde ninguna línea.

## Movimiento del cambio al archivo

- **Origen:** `openspec/changes/session-endpoints-cookie-and-csrf`
- **Destino:** `openspec/changes/archive/2026-10-24-session-endpoints-cookie-and-csrf`
- **Mecanismo:** instantánea recursiva previa con `cp -R`, después `git mv` del directorio. El origen ya no existe. `verify-report.md` (sin versionar) se movió con el directorio.
- **Readback (instantánea previa contra el destino), salida literal:**

```
(salida vacía; exit 0)
```

## Condiciones duras

Fuentes: `verify-report.md` (secciones 4 y 8), los escenarios de cierre del delta de `web-edge` ([WE53], [WE54], [WE56], [WE86]) y el texto de las condiciones en `openspec/changes/foundations-plan/exploration.md`.

| Condición | Estado al archivar | Dueño | Evidencia |
|-----------|--------------------|-------|-----------|
| **Condición 3**: CSRF de doble envío antes de la primera cookie (2026-10-04) | **Cerrada** en este cambio | este cambio | [WE53] (con cookie y sin cabecera, `403`) y [WE54] (las mismas pruebas contra las rutas reales), con [WE56] que declara el cierre con referencia a ambas. Verificación: `CsrfOriginFilterTest`, `SessionCookieRoutesCsrfIT` en verde |
| **H1**: control por IP antes del inicio de sesión (2026-09-25) | **Cerrada** en este cambio | este cambio | [WE86] (cierre de H1, solo documental). Verificación: `LoginRateLimitClosesH1IT` en verde; `LoginTimingGateIT` en verde (ver S3) |
| **H2**: revocar todas las familias al restablecer (2026-10-03) | Cerrada en el cambio anterior | `session-tokens-and-web-layer` | prueba I24 (`PasswordResetRevokesSessionsIT`) |
| **H3**: 10 solicitudes por hora por IP en la solicitud de restablecimiento (2026-10-03) | Abierta, transferida | `password-recovery-endpoints` | — |
| **H4**: respuesta HTTP uniforme de la recuperación y puerta de tiempo | Abierta, transferida | `password-recovery-endpoints` | — |

## Seguimientos y riesgos transferidos

Ninguno de estos puntos se ejecutó en este cambio, salvo que se indique lo contrario.

1. **W2. Dos analizadores de la cabecera `Cookie`.** `CsrfOriginFilter.java` (líneas 97 a 108) analiza la cabecera a mano; `SessionCookies.java` usa el analizador del contenedor. Ante valores entre comillas o separadores no estándar, ambos podrían ver conjuntos distintos de cookies. No se observó vía de ataque entre sitios. Recomendación: unificar la lectura en un cambio posterior. Dueño: pendiente de asignar.
2. **S2. Trazabilidad de escenarios.** 41 identificadores de escenario no aparecen literalmente en las pruebas (por ejemplo WE44, WE48, WE70, WE73, WE85, WE93, BI53, BI54, I204). Su trazabilidad depende de la tabla de `tasks.md`. Seguimiento: etiquetar las pruebas con su identificador de escenario. Dueño: pendiente de asignar.
3. **F-IT-TIME.** Recortar el tiempo de las pruebas de integración. Dueño: pendiente de asignar.
4. **Riesgo para la SPA.** La SPA no debe enviar un `Bearer` vencido a `DELETE /api/v1/auth/sessions/current`. Dueño: el cambio de la SPA correspondiente.
5. **Cubo compartido tras proxy.** Con `trusted-proxies` vacío, el cubo de límite de tasa se comparte tras un proxy. Dueño: cambio 11.
6. **H3 y H4.** Dueño: `password-recovery-endpoints`.
7. **DA-15.** Riesgo aceptado por el propietario; sin acción en este archivo.
8. **S3. Medición de `LoginTimingGateIT`.** La prueba usa `confia.web.delay.max-wait=1ms` en vez de un retardo cero en ambos lados, como dice [I159]. Desviación registrada en `tasks.md`; efecto sobre la medición considerado despreciable por el verificador. Sin dueño asignado.

## Riesgos y limitaciones de este archivo

- Las cifras de construcción y de pruebas son las de `verify-report.md` (instantánea del 2026-10-24 sobre `56f63ea`). No se volvieron a ejecutar durante el archivo.
- El paso de las dos pruebas que fallaban por W1 se toma de la instrucción de cierre; el archivo solo comprobó que `apps/*/node_modules` ya no existe.
- La colisión de nombre de build-integrity (conflicto 4) queda como divergencia entre el delta archivado y la especificación principal hasta que el propietario la confirme.

## Ciclo SDD

El cambio queda archivado. Implementación: 8 de 8 tareas marcadas `[x]`. Verificación: `PASS WITH WARNINGS` (0 CRITICAL, 3 WARNING, 3 SUGGESTION), con W1 resuelta por el propietario y W3 corregida en este archivo antes de la fusión. Tareas sin completar: ninguna observada en `tasks.md`. Hallazgos sin resolver: W2 y las sugerencias S2 y S3, descritos arriba.

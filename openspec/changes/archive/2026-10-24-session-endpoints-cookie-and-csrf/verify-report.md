# Informe de verificación: endpoints de sesión, cookie y CSRF (parte 4b, S2)

- **Cambio:** `session-endpoints-cookie-and-csrf`
- **Fecha:** 2026-10-24
- **Árbol verificado:** `main` en `56f63ea` (tareas 1.1 a 4.1 fusionadas y marcadas `[x]`)
- **Veredicto:** **PASS WITH WARNINGS** (0 CRITICAL, 3 WARNING, 3 SUGGESTION)
- **Preparado para archivar:** sí; ninguna advertencia bloquea el archivo

## 1. Estado de las tareas

Las ocho tareas (1.1, 1.2, 1.3, 2.1, 3.1, 3.2, 3.3 y 4.1) están marcadas `[x]` en `tasks.md`, con su partición y su evidencia en
`apply-progress.md`. No queda ninguna tarea abierta. Este informe no modifica ninguna casilla.

## 2. Evidencia de construcción

| Orden | Árbol | Resultado | Duración |
|---|---|---|---|
| `./mvnw verify -Pmutation-gate` (JDK 25.0.3) | Copia de trabajo del repositorio | **BUILD FAILURE**: Surefire de la aplicación 2 374 pruebas, 0 fallos, **2 errores** (ver W1); Failsafe y PIT de la aplicación no llegaron a ejecutarse | 7 min 19 s |
| `./mvnw verify -Pmutation-gate` (JDK 25.0.3) | Exportación limpia de `56f63ea` (`git archive`) en un directorio temporal | **BUILD SUCCESS** | 13 min 26 s (Maven) |
| `pnpm --filter @confia/contracts generate`, `typecheck` y `test` (Node 24.22.1 con fnm, orval 8.38.0) | Copia de trabajo | Verde: 2 archivos, 8 pruebas, sin errores de tipos; `git status` limpio después | 9 s |

Detalle de la construcción en verde:

- Surefire: núcleo 186 y aplicación 2 374, 0 fallos y 0 errores.
- Failsafe: 598, 0 fallos y 0 errores.
- PIT del núcleo: 192 de 194 mutantes muertos (99 %), fuerza de prueba 99 %.
- PIT de la aplicación: 676 de 710 mutantes muertos (95 %), 6 sin cobertura, fuerza de prueba 96 %, cobertura de líneas de las clases mutadas 928 de 959 (97 %).
- JaCoCo: «All coverage checks have been met» en los dos módulos.
- Arquitectura: `LayeredArchitectureTest` (2), `SpringModulithVerificationTest` (2), `WebEdgeScopeExclusionInventoryTest` (28) y `ProcessBeanIsolationTest` (13), en verde.
- Pruebas de cierre: `CsrfOriginFilterTest` (32), `SessionCookieRoutesCsrfIT` (2), `LoginRateLimitClosesH1IT` (4), `LoginTimingGateIT` (2, 42 s) y `SessionFlowRedactionIT` (3; la traza que aparece en el registro es el control negativo deliberado), en verde.

Las cifras coinciden con las del cierre de la tarea 4.1 en `apply-progress.md` (Surefire 186 + 2 374, Failsafe 598, PIT 676 de 710).
BI57: la construcción completa con mutación tardó 13 min 26 s, de modo que la construcción sin mutación queda necesariamente por debajo
del presupuesto de 15 minutos fijado por el propietario el 2026-10-24. No se midió por separado en esta verificación.

## 3. Trazabilidad de los 216 escenarios

- Los deltas contienen 216 escenarios (identity 103, web-edge 74, build-integrity 33, audit-trail 6): 202 con identificador y 14 citados por título.
- 161 identificadores aparecen literalmente en el código de pruebas o en la documentación. Los 41 restantes (por ejemplo WE44, WE48, WE70, WE73, WE85, WE93, BI53, BI54, I204) tienen su fila de tarea en `tasks.md` y una prueba con nombre descriptivo; se comprobó por muestreo (`CsrfOriginFilterTest.aForeignLookalikeNullOrInsecureOriginIsRefusedWithIdenticalAnswers`, `RateLimitPropertiesTest.theDefaultsOfTheThreePoliciesAreTheOnesOfTheSpecification`, `anAbsentComponentStopsTheStartAndNamesTheProperty`, `anUnknownComponentStopsTheStartAndNamesTheProperty`, `RequiredDelayMaterializerTest.aDelayShorterThanTheCapIsWaitedInFull`, `ProcessBeanIsolationTest` y `RevocationReasonCatalogIT`).
- Todas las clases de prueba que `tasks.md` nombra existen en `src/test`.

## 4. Revisión de los puntos de mayor riesgo

| Punto | Evidencia | Resultado |
|---|---|---|
| Filtro CSRF y `Origin`, condición 3 [WE53, WE54, WE55] | `CsrfOriginFilter.java:67-94` aplica las cinco reglas en el orden del diseño (decisión 2); `CsrfOriginFilterTest` y `SessionCookieRoutesCsrfIT` en verde; nota fechada en `openspec/changes/foundations-plan/exploration.md:309` | Conforme |
| Atributos de cookie y escritor único [WE52, WE62, WE63, BI47] | `SessionCookies.java:98-106` (sesión: `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth/sessions`; CSRF: sin `HttpOnly`, `Path=/`); ningún otro archivo de `src/main` usa `ResponseCookie`, `addCookie` ni `jakarta.servlet.http.Cookie` | Conforme |
| Revocación por vinculación [I190 a I193, I225, I226] | `RotationDecision.java:73-96` (orden: revocada, vencida, reutilización, vinculación, gracia, vencimiento del token, rotación); `RotateRefreshToken.java:319-350` revoca con `binding_changed`, vacía los sucesores, audita sin huella ni dirección y compara direcciones como direcciones | Conforme |
| Gracia y reutilización [I186 a I188] | `RotateRefreshToken.java:222-310`; `TwoTabRefreshConcurrencyIT` en verde | Conforme |
| Rutas de cierre [I194 a I202, I227] | `SessionEndController.java`: cierre actual con cookie, `204` y borrado siempre; cerrar todas con `AuthenticatedActor` | Conforme |
| Límites de tasa y H1 [I160 a I163, WE72, WE92] | Las seis rutas nuevas declaran `@RateLimited` con su política; `GET /sessions/current` ninguna [WE74]; los fallos se registran en el inicio de sesión, el segundo factor y el refresco, no en los cierres | Conforme |
| Uniformidad de `session-expired` [I188, I219] | `SessionRefreshController.java`: todo desenlace distinto de `Refreshed` (incluidas la cookie ausente y la repetida) borra las dos cookies, registra el fallo y responde `session-expired` | Conforme |
| Auditoría solo por `AuditJson` [AT01 a AT04, BI43, BI44] | `AuditJsonOnlySourceTest` recorre todo `src/main/java`; el diff del cambio no añade ningún documento JSON construido a mano | Conforme |
| `V9` solo sustituye el `CHECK` [BI39 a BI42] | `git diff dafabf3 56f63ea` sobre `db/migration`: un único archivo nuevo, `V9__extend_identity_refresh_token_family_revocation_reasons.sql` (19 líneas, `DROP` y `ADD` de `identity_refresh_token_family_reason_chk`); `V1` a `V8` sin cambios | Conforme |
| Puerta de tiempo [I159, BI56] | `LoginTimingGateIT` (200 más 200 intentos, límite de 50 ms y variante de control) en verde | Conforme (ver S3) |
| Redacción [I209 a I211] | `SessionFlowRedactionIT` con control negativo; `AuthenticatedActor.toString` excluye el `jti` | Conforme |

## 5. Reglas no negociables

- **Registros (regla 11):** el diff del cambio añade un único registro, `LOG.atWarn()` con el identificador de la petición y el nombre del evento (señal de cinco identificadores); no registra secretos, direcciones ni identificadores.
- **SQL (regla 12):** el diff no añade SQL en texto ni concatenación; el acceso sigue por jOOQ.
- **Auditoría (regla 14):** inicio de sesión, segundo factor, refresco, vinculación, reutilización, cierre actual, cerrar todas y `mfa_change` escriben asiento.
- **Dependencias:** `LayeredArchitectureTest` y `SpringModulithVerificationTest` en verde; los archivos modificados de `identity.domain` solo importan clases del JDK.
- **Migraciones:** `V1` a `V8` intactas.
- **Prohibiciones del stack:** el diff no añade `@Scheduled`, `@Async`, `double` ni `float`.

## 6. Desviaciones registradas

Todas están justificadas y documentadas en `apply-progress.md` o `tasks.md`: el tamaño de 07a (928 líneas, cambio de firma indivisible,
615 líneas de pruebas); WE20 acotado (el texto del delta ya lo recoge); `RefreshSession.Rejected` sin motivo (el motivo queda en la
auditoría y la respuesta es uniforme); la ruptura de I211 sobre `AuthenticatedActor` y no sobre `AccessToken` (el `jti` lo lleva el
actor); BI57 reescrito por el propietario el 2026-10-23 (opción a) y presupuesto subido a 15 minutos el 2026-10-24 (el texto del delta
lo recoge).

## 7. Hallazgos

### CRITICAL

Ninguno.

### WARNING

- **W1. La construcción falla en la copia de trabajo por enlaces rotos en un directorio ignorado.**
  `apps/api/app/src/test/java/com/confia/identity/IdentityScopeExclusionInventoryTest.java:155` y
  `apps/api/app/src/test/java/com/confia/shared/web/request/WebEdgePropertiesTest.java:230` recorren el árbol con `Files.walk` y
  entran en `apps/admin-web/node_modules` (no versionado, del 2026-10-03), cuyos enlaces de pnpm apuntan a entradas que ya no existen
  en `node_modules/.pnpm` (probablemente podadas por el `pnpm install --frozen-lockfile` de BI26). Resultado: `NoSuchFileException`
  y `BUILD FAILURE`. No es un defecto del cambio (la exportación limpia pasa completa) y las dos pruebas son anteriores a él, pero
  cualquier construcción local en esta máquina falla hasta que se retire ese directorio o se excluya `node_modules` del recorrido,
  como ya hace `CsrfKeyNotVersionedTest` con `walkFileTree`.
- **W2. Dos analizadores de la cabecera `Cookie`.** `CsrfOriginFilter.java:97-108` analiza la cabecera a mano (separa por punto y
  coma), mientras que `SessionCookies.java:78-96` usa el analizador del contenedor. La decisión 5 del diseño descartó el análisis
  manual; la desviación está registrada (`tasks.md`, nota de 1.3, punto 4) y no se observa una vía de ataque entre sitios, porque el
  navegador compone la cabecera. Aun así, ante valores entre comillas o separadores no estándar, el filtro y el controlador podrían
  ver conjuntos distintos de cookies. Se recomienda unificar la lectura en un cambio posterior.
- **W3. El escenario WE73 nombra una propiedad que no existe.** `specs/web-edge/spec.md:385` dice
  `confia.web.rate-limit.admin-session.requests`; la propiedad real es `request-limit` (`application.yml` y `RateLimitPropertiesTest`).
  La desviación está anotada en `tasks.md`, pero el texto del delta no se corrigió y pasaría así a la especificación principal al
  archivar. Conviene corregirlo antes o durante el archivo.

### SUGGESTION

- **S1.** WE56 exige que el informe de archivo declare cerrada la condición 3 con referencia a [WE53] y [WE54]; el informe de archivo
  debe incluir esa declaración (y la de H1, [WE86]).
- **S2.** 41 identificadores de escenario no aparecen literalmente en las pruebas; la trazabilidad depende de la tabla de `tasks.md`.
  Etiquetar las pruebas con su identificador facilitaría las verificaciones futuras.
- **S3.** `LoginTimingGateIT` mide con `confia.web.delay.max-wait=1ms` en lugar de un retardo cero en ambos lados, como dice I159; la
  desviación está registrada y el efecto sobre la medición es despreciable, pero el texto del escenario podría precisarlo.

## 8. Seguimientos conocidos (no son defectos)

F-IT-TIME (recortar el tiempo de las pruebas de integración); la SPA no debe enviar un `Bearer` vencido a `DELETE /sessions/current`;
el cubo compartido tras un proxy con `trusted-proxies` vacío (cambio 11); H3 y H4 con dueño `password-recovery-endpoints`; riesgo
aceptado DA-15.

## 9. Limitaciones

- La duración de la construcción sin mutación (BI57) no se midió por separado; se infiere de la construcción completa.
- Las demostraciones deliberadas (rupturas temporales) de `apply-progress.md` no se repitieron; se aceptan como evidencia histórica.
- La revisión de los escenarios fue por muestreo en los puntos de mayor riesgo, no exhaustiva sobre los 216.

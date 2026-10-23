# Tareas: endpoints de sesión, cookie y CSRF (parte 4b, S2)

- **Cambio:** `session-endpoints-cookie-and-csrf` (F0, cambio 7, parte 4b, segundo cambio de tres)
- **Fase:** tareas
- **Fecha:** 2026-10-20
- **Estado:** pendiente de aprobación del propietario
- **Entradas aprobadas:** `proposal.md` (con las decisiones del propietario del 2026-10-20), `design.md` (secciones 3, 4, 6, 8, 9 y
  10, y la nota de D-N1 a D-N3) y los deltas `specs/identity/spec.md` (103 escenarios), `specs/web-edge/spec.md` (74),
  `specs/build-integrity/spec.md` (33) y `specs/audit-trail/spec.md` (6): **216 escenarios**
- **Modo de TDD:** estricto. Ejecutor: `./mvnw verify` en `apps/api`, con `JAVA_HOME` apuntando a JDK 25 y Docker en ejecución.
- **Línea base de la cadena:** rama de planificación `change/session-endpoints-cookie-and-csrf` en `34e061f`, sobre `main` en `dafabf3`.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines (additions + deletions, sin `openspec/` ni código generado) | **nominal unas 3 310; realista unas 7 290 (rango de 6 600 a 9 900)** con el factor 2,2 de la propuesta. Historial de S1: el tamaño medido de las tareas partidas fue de 1,5 a 3,8 veces el realista (1.1: 1 631 contra 730; 1.4: 2 676 contra 700; 5.2: 2 026 contra 790); con ese historial el centro sube a unas 12 000 a 16 000 líneas |
| 400-line budget risk | **High** frente a 400 de la preflight; **medio a alto por PR** frente al presupuesto del proyecto de 800 |
| Chained PRs recommended | Yes |
| Suggested split | **12 PR apilados** contra `main` (8 tareas; las tareas 2.1, 3.1, 3.2 y 3.3 se parten cada una en dos PR por la costura nombrada), en el orden duro del diseño §9. Con el historial de S1 pueden ser entre 16 y 24 |
| Delivery strategy | `single-pr` en la preflight de la sesión; el propietario decidió en DA-1 *a* una cadena apilada con la regla «partir solo si hace falta» |
| Chain strategy | `stacked-to-main`: cada PR se fusiona a `main` en orden y el siguiente parte de `main` actualizado |
| Presupuesto del proyecto por PR (`docs/15-flujo-de-trabajo-git.md` §3) | 800 líneas de cambio efectivo |

Decision needed before apply: Yes (solo confirmar que DA-1 *a*, cadena `stacked-to-main` con 800 líneas efectivas por PR, prevalece sobre `single-pr` con 400 de la preflight; no hay decisión nueva del propietario sobre la estrategia de cadena)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Conflicto de estrategia.** La preflight de la sesión registra `single-pr` con revisión de 400 líneas, que obligaría a exigir
`size:exception` antes de aplicar. `CLAUDE.md`, `docs/15` §3 y la decisión DA-1 *a* del propietario fijan una cadena apilada con 800
líneas efectivas por PR. Este documento registra la segunda, como hizo S1; la confirmación es la única decisión pendiente antes de aplicar.

**Tareas.** 8, en 4 fases. **Cuenta para DA-1: 8 de 15, no se propone partir el cambio.** Cada tarea cubre una rebanada del diseño §9. Las
cuatro que superan 800 líneas realistas (2.1, 3.1, 3.2 y 3.3: 880, 1 320, 1 430 y 1 210) se planifican como **una sola tarea cuya aplicación
se parte en dos PR apilados** por la costura que el diseño §9 nombra, de acuerdo con la regla permanente del propietario («partir solo si
hace falta, sin preguntar»). Las partes no son tareas nuevas ni se renumeran.

**Regla de partición.** Si la medición de un PR supera 800 líneas efectivas, el ejecutor lo parte por archivos completos en la costura
nombrada de su tarea, sin consultar, y deja una nota fechada con la medida (como en 13a/13b/13c de S1). Solo consulta si una parte
indivisible exige una excepción de tamaño o si necesita una costura que no está nombrada. El historial de S1 (cuatro tareas partidas en tres a
cinco PR) hace probable que 1.1, 3.1, 3.2 y 3.3 pidan más de dos partes.

**Orden duro (diseño §9).**

1. **1.1 (CSRF y `Origin`) se fusiona antes de cualquier PR que emita `Set-Cookie`, incluido el borrado.** Ningún PR de 1.1 y 1.2 contiene
   `ResponseCookie`, `Set-Cookie` ni `addCookie` en `src/main`. 1.3 crea `SessionCookies` sin ninguna ruta que lo llame; **la primera
   emisión de producción es 3.1b** (condición 3).
2. 1.2 (serializador de auditoría) antes de 2.1, 3.1, 3.2 y 3.3: ninguna entrada de auditoría nueva se escribe por concatenación.
3. 1.3 antes de 3.1 (políticas, tope, cookies y sonda S-5). 2.1 antes de 3.2 (`mfa_change`) y de 3.3 (`logout`, `binding_changed`).
4. 3.1 antes de 3.2 (el inicio de sesión emite el token restringido) y de 3.3.
5. **El limitador se aplica en el mismo PR que expone el inicio de sesión (H1).** Si 3.1 se parte, 3.1a contiene `LogIn` sin controlador ni
   ruta y 3.1b contiene controlador, limitador, regla de ArchUnit y la prueba de H1 juntos.

**Condiciones duras que se cierran** (la casilla de la tarea no se marca sin ellas):

| Condición | Tarea | Prueba de cierre |
|---|---|---|
| **Condición 3** (2026-10-04) | 1.1 (arnés) y 3.3 (rutas reales) | `CsrfOriginFilterTest` en verde con su demostración deliberada y su control negativo [WE53, WE55] en 1.1b; `SessionCookieRoutesCsrfIT` en verde con los seis resultados `403` [WE54] en 3.3b |
| **H1** (2026-09-25) | 3.1 | `LoginRateLimitClosesH1IT` en verde con su demostración deliberada [I160, I161] en 3.1b |

### Reglas comunes a todas las tareas

- **Comandos.** Todos desde `apps/api`. `./mvnw verify -Pmutation-gate` completo cierra cada tarea o cada PR. Las órdenes focalizadas son
  solo para iterar: un `-Dtest=` acotado informa `BUILD FAILURE` por JaCoCo aunque las pruebas pasen, y eso no es un fallo de la tarea.
  Forma unitaria: `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='A,B'`. Forma de integración:
  `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=X -Dfailsafe.failIfNoSpecifiedTests=false`.
- **Rojo registrado.** Cada paso en rojo corre contra el árbol sin el código de producción y se registra en
  `openspec/changes/session-endpoints-cookie-and-csrf/apply-progress.md` con la causa **observada**. Si falla por otra causa, se detiene
  y se investiga. Un commit en rojo no entra al historial: las pruebas en rojo se comprometen con su verde.
- **Demostración deliberada.** Cada tarea nombra una ruptura temporal de producción que debe poner en rojo una prueba concreta **por una
  aserción de resultado** (estado, cuerpo, cabecera, fila, contador, valor registrado), nunca por tiempo de espera ni por carrera. Se
  registra en `apply-progress.md` y se revierte.
- **Las pruebas de ataque se escriben antes que el código que defienden** (CSRF, token restringido usado como sesión, enumeración, fuerza bruta).
- **Nombres explícitos.** Todo `@RequestParam`, `@PathVariable` y `@RequestHeader` declara su nombre (sin `-parameters`).
- **Salida de jqwik.** La línea dirigida a «agentes de IA» es salida de la herramienta y se ignora; se leen el código de salida y los informes.
- **Concurrencia determinista.** Sin esperas por reloj: `CyclicBarrier`, `CountDownLatch`, `MutableClock`. La segunda transacción se libera
  cuando `pg_stat_activity` muestra una espera de bloqueo (condición con límite, no espera fija). El temporizador del materializador es un
  doble de pruebas que registra la espera solicitada; nunca se espera el retardo real.
- **Lista de permitidos.** Cada `@Import` nuevo y su línea de `ProcessBeanPolicy` entran en el mismo PR; se demuestra primero el rojo
  (`ProcessBeanIsolationTest` falla con `not in the allow-list` nombrando bean y paquete) y luego el verde.
- **Claves y secretos.** Ninguna clave (CSRF, firma, pimienta) se versiona (regla 13): `TestProcessArguments` las genera en ejecución. El
  escaneo de secretos (BI29) cubre `confia.security.csrf.key`.
- **Registros.** Nunca contraseña, token de acceso, restringido o de refresco, token CSRF, cabecera `Authorization` o `Cookie`, `jti`, secreto
  TOTP, código de recuperación, huella de agente ni dirección en logs ni bitácora.
- **Auditoría.** Toda entrada nueva se escribe con `AuditJson` desde el primer commit (tareas 3.1 a 3.3); ninguna por concatenación.
- **Medición antes de abrir el PR.** `git diff --numstat main...HEAD` excluyendo `openspec/` y código generado, registrada en
  `apply-progress.md`. Si supera 800, se aplica la regla de partición.
- **Commits.** Conventional Commits en inglés, sin atribución de herramientas de IA, con pruebas y docs en el mismo commit que el
  comportamiento. Una rama por PR: `change/session-endpoints-cookie-and-csrf-NN-<nombre>` (el PR 1 sale de la rama de planificación).
- **Idioma.** Código, identificadores y commits en inglés; documentación y notas fechadas en español neutro profesional; textos de interfaz
  solo por `problems.properties` (es-HN).
- **Notas documentales.** Notas fechadas, sin reescribir cuerpos existentes ni el texto original de H1, H3, H4 ni de la condición 3.
- **Revisión de seguridad de cada PR.** Lista aplicable de `docs/03` marcada en la descripción del PR (entradas validadas en el borde, salidas
  por DTO, acciones sensibles auditadas, nada de datos de menores en registros).
- **Rutas bajo `apps/api/app/src/`** salvo indicación; los paquetes de las pruebas de integración se confirman en el rojo contra el árbol real.

### Suggested Work Units

| Unidad | Objetivo | PR probable (nominal → realista) | Comando de prueba enfocado | Arnés de ejecución | Frontera de reversión |
|---|---|---|---|---|---|
| 1.1 | CSRF de doble envío con HMAC, `Origin`, clave, `allowed-origins`, `csrf-invalid`, condición 3 sobre arnés | PR 1 `csrf-and-origin-protection` (330 → 730; costura 1a/1b) | `-Dtest='CsrfTokensTest,CsrfTokensProperties,AllowedOriginsTest,CsrfOriginFilterTest,AdminFilterOrderTest,CsrfKeyStartupTest,ProcessBeanIsolationTest'` | Cadena administrativa real en `RANDOM_PORT` sin base de datos, con un controlador del arnés que emite y lee la cookie | Se retiran los paquetes `shared.security.csrf` y `shared.web.csrf`, el filtro en `AdminSecurityConfiguration` y las propiedades; no existe ningún emisor de cookies en producción |
| 1.2 | Constructor tipado `AuditJson` y migración de los 16 sitios en 8 clases | PR 2 `audit-json-serializer` (150 → 330) | `-Dtest='AuditJsonTest,AuditJsonSiteEqualityTest,AuditJsonOnlySourceTest'` | Cadena de auditoría existente verificada con `AuditChainVerifier`; bytes idénticos por sitio | Se revierte el commit; los bytes escritos son idénticos antes y después |
| 1.3 | Tres políticas de tasa, tope de la espera, sonda S-5 y `SessionCookies` sin llamador | PR 3 `edge-policies-and-delay-cap` (280 → 620) | `-Dtest='RateLimitPropertiesTest,RequiredDelayMaterializerTest,UnknownJsonPropertyTest,SessionCookiesTest,WebEdgeScopeExclusionInventoryTest'` | Arnés con rutas `@RateLimited` de las tres políticas y temporizador de prueba | Se retiran el ayudante, las políticas nuevas y el tope; `admin-login` vuelve a su forma anterior |
| 2.1 | `V9`, revocación por familia y por cuenta, F1a, F1b y concurrencia | PR 4a `v9-and-revocation-use-cases` + PR 4b `revocation-follow-ups` (400 → 880) | IT: `-Dit.test='RevocationReasonCatalogIT,RefreshTokenSchemaIT,EndSessionIT'` | PostgreSQL real con el rol `confia_admin_app` y dos instituciones de fixture | Se elimina la migración (antes de producción) y se regenera jOOQ; los casos de uso no tienen llamador HTTP |
| 3.1 | Inicio de sesión (cierra H1), señal de cinco cuentas y `authentication-failed` | PR 5a `login-use-case-and-failure-spread` + PR 5b `login-controller-and-h1` (600 → 1 320) | IT: `-Dit.test='LogInIT,LoginRateLimitClosesH1IT,LoginEndpointIT'`; unidad `-Dtest='FailureSpreadWindowTest'` | Proceso administrativo real con PostgreSQL y la política `admin-login` real | 5b se revierte entero (controlador y limitador juntos, nunca el limitador solo); 5a conserva `LogIn` sin ruta |
| 3.2 | Clase de ruta restringida, inscripción repetible, verificación y `mfa_change` | PR 6a `restricted-route-class` + PR 6b `enrollment-and-verification` (650 → 1 430) | `-Dtest='SecondFactorRouteTest,AccessTokenAuthenticationFilterTest'`; IT: `-Dit.test='EnrollTotpSecondFactorIT,CompleteSecondFactorIT'` | Arnés con token restringido de cada uso; dos transacciones reales para la concurrencia | 6b se revierte antes que 6a; el inicio de sesión sigue emitiendo el restringido sin ruta que lo reciba |
| 3.3 | Vinculación, refresco, cierre actual, cerrar todas, OpenAPI y condición 3 sobre rutas reales | PR 7a `refresh-binding` + PR 7b `refresh-logout-http` (550 → 1 210) | `-Dtest='RotationDecisionTest,RotationDecisionProperties'`; IT: `-Dit.test='RefreshBindingIT,SessionCookieRoutesCsrfIT,SessionRefreshEndpointIT,SessionEndEndpointIT'` | Proceso administrativo real, cookies y cabeceras de una prueba HTTP, dos pestañas | 7b se revierte antes que 7a; nunca se revierte 1.1 mientras 7b esté en `main` |
| 4.1 | F2, F3, O9, puerta de tiempo, ausencias, contratos y notas | PR 8 `closing-and-follow-ups` (350 → 770) | `-Dtest='ClaimReaderTest,SessionSecretsNotObservableTest'`; IT: `-Dit.test='SessionFlowRedactionIT,LoginTimingGateIT'` | Flujo completo con captura del registro estructurado; 200 más 200 intentos | Se revierte el commit; solo pruebas y documentación, sin comportamiento de producción nuevo |

### Dependencias y paralelismo

| Tarea | Depende de | Paralelismo |
|---|---|---|
| 1.1 | Ninguna | Secuencial: es la raíz de la cadena y no se fusiona después de ningún PR que emita cookies |
| 1.2 | Ninguna técnica; se fusiona después de 1.1 por el orden de la cadena | Puede redactarse en paralelo con 1.3 y con 1.1 (archivos disjuntos); se fusiona en orden |
| 1.3 | 1.1 (cookies) | Puede redactarse en paralelo con 1.2; se fusiona después de 1.1 |
| 2.1 | 1.2 | Puede redactarse en paralelo con 1.3; 4a antes que 4b |
| 3.1 | 1.3 y 2.1 | Secuencial; 5a antes que 5b |
| 3.2 | 2.1 y 3.1 | Secuencial; comparte `AuthenticatedEndpoints`, `ProblemCode` y `ProblemResponses` con 3.3, de modo que no se redacta en paralelo con él (un solo escritor) |
| 3.3 | 2.1 y 3.1 (3.2 por conflicto de archivos) | Secuencial después de 3.2 |
| 4.1 | 3.3 | Secuencial: cierra el cambio |

---

## Fase 1: defensa del borde antes de cualquier cookie

- [x] 1.1 **PR 1 `csrf-and-origin-protection`: CSRF de doble envío vinculado al refresco, `Origin` obligatorio, clave y condición 3 sobre arnés (decisiones 2, 3 y 4 del diseño).**
  - **ROJO, ataques primero.** Crear en `test/java/com/confia/shared/security/csrf/`: `CsrfTokensTest.java` (ida y vuelta; un bit alterado en
    el aleatorio y en el MAC; MAC de otro refresco; clave anterior acepta y emite con la actual; longitud, relleno `=`, `+`, `/` y bits de cola
    rechazados; todo rechazo es `false`, nunca excepción) y `CsrfTokensProperties.java` (jqwik: todo token alterado en una posición se rechaza).
    Crear `test/java/com/confia/shared/web/csrf/AllowedOriginsTest.java` (ruta, barra final, comodín, mayúsculas, `http` no local, usuario y
    repetidos; mensaje que nombra la propiedad y la posición sin el valor; `http://localhost:5173` válido) y
    `CsrfOriginFilterTest.java` sobre el arnés nuevo `test/java/com/confia/shared/web/harness/CookieHarnessController.java` (solo en el árbol de
    pruebas; emite una cookie de sesión y una CSRF válidas con `ResponseCookie` propio y lee ambas): cookie sin `X-CSRF-Token`, token CSRF de otra
    sesión, cabecera distinta de la cookie, HMAC alterado en el primer y último byte, aleatorio alterado, dos cookies de sesión, dos cookies CSRF,
    dos cabeceras `X-CSRF-Token`, sin `Origin` en `POST`, `PUT`, `PATCH` y `DELETE`, `Origin` ajeno, parecido, `null`, repetido y `http`, `Origin`
    válido, ruta de cookie sin cookie de sesión (pasa con `Origin` y `403` sin él), `GET` sin `Origin` (pasa), lista vacía (todo `POST` es `403`),
    token emitido con la clave anterior (se acepta y se reemite con la actual) y con una clave ajena (`403`), y una **variante de control con el
    filtro deshabilitado que debe fallar** [WE55]. Crear `test/java/com/confia/shared/web/edge/AdminFilterOrderTest.java` (índice relativo de
    `CsrfOriginFilter` y `AccessTokenAuthenticationFilter` en `SecurityFilterChain.getFilters()` del contexto real; un doble espía del
    verificador y un contador del limitador no cambian con una petición rechazada) [WE57, WE58]. Crear `test/java/com/confia/bootstrap/CsrfKeyStartupTest.java`
    por `ConfiaApplication.launch` sin Docker (administración sin clave, con clave de 16 bytes, con Base64 inválido y con clave anterior igual a la
    actual no arranca nombrando la propiedad sin su valor; portal y trabajador abortan con cualquiera de las dos propiedades y arrancan sin ellas)
    [WE59, WE60] y ampliar `TestProcessArguments.java` con una clave CSRF generada en ejecución [BI58]. Ampliar el escaneo de secretos de BI29 con
    `confia.security.csrf.key` [WE61]. Ampliar `ProblemCatalogCoverageTest.java` y `ProblemCodeTest.java` con `csrf-invalid` (controles negativos:
    catálogo sin su entrada es-HN) y `ProblemTranslationTest.java` para «Recurso inexistente bajo un prefijo de documentación» y «Un código desconocido
    no se filtra». **Rojos esperados:** clases inexistentes; el arnés responde `200` en todos los casos; `ProcessBeanIsolationTest` con
    `not in the allow-list` nombrando `com.confia.shared.security.csrf` [BI55]; arranque sin fallo.
  - **VERDE.** Crear en `main/java/com/confia/shared/security/csrf/`: `CsrfTokens` (`issue(refreshToken)` y `verify(presented, refreshToken)`,
    formato `B64U(r).B64U(HMAC-SHA256(k, r || SHA-256(refresco)))`, `Mac` por llamada, `Digests.sha256`, `MessageDigest.isEqual`), `CsrfKeys`
    (lectura con `Environment.getProperty`, Base64 estándar de al menos 32 bytes, clave anterior opcional distinta de la actual, fallo con
    `IllegalStateException` que nombra la propiedad, no repite el valor y no encadena la causa), `CsrfConfiguration` y `package-info.java`.
    Crear en `main/java/com/confia/shared/web/csrf/`: `CsrfOriginFilter` (sin bean, pasos 1 a 5 de la decisión 2, `403 csrf-invalid` escrito con
    `ProblemResponses.write`), `AllowedOrigins` (`parse(List<String>)`), `CookieCredentialRoute` (`forAdmin()` con
    `POST /api/v1/auth/sessions/refresh` y `DELETE /api/v1/auth/sessions/current`; el prefijo `/api/v1/auth/sessions` como constante única) y
    `package-info.java`. Editar `shared/web/request/WebEdgeProperties.java` (`allowedOrigins`), `shared/web/edge/AdminSecurityConfiguration.java`
    (`addFilterBefore(csrfOriginFilter, AccessTokenAuthenticationFilter.class)`), `shared/security/token/SigningKeyPlacementGuard.java` (propiedades
    CSRF prohibidas en portal y trabajador), `bootstrap/admin/AdminApplication.java` (`@Import` de `CsrfConfiguration`), `ProcessBeanPolicy.java`
    (administración permite `com.confia.shared.security.csrf`; portal y trabajador lo prohíben; cada línea primero en rojo),
    `shared/web/problem/{ProblemCode,ProblemResponses,ProblemCodeException}.java` y `main/resources/i18n/problems.properties` (`csrf-invalid`, `403`,
    sin `WWW-Authenticate`), `main/resources/application-local.yml` (`confia.web.allowed-origins: http://localhost:5173`). Editar
    `apps/api/app/pom.xml` (`com.confia.shared.security.csrf.*` en `targetClasses` y `targetTests` de PIT). Notas fechadas en
    `docs/03-seguridad.md` §8.3 (alcance de DA-4 *a*, formato del token, clave anterior, paso 4) y `docs/05-infraestructura-y-despliegue.md`
    (`CONFIA_WEB_ALLOWEDORIGINS`, `CONFIA_SECURITY_CSRF_KEY` y `..._PREVIOUSKEY`; la contradicción de `SESSION_COOKIE_DOMAIN` con la prohibición de
    `Domain`, retirada en una nota).
  - **Condición de cierre (condición 3, parte del arnés).** La casilla de 1.1 **no se marca** sin: (a) `CsrfOriginFilterTest` en verde contra la
    cadena real con las tres pruebas de la condición (cookie sin cabecera, token de otra sesión, sin `Origin`) [WE53]; (b) su variante con el filtro
    deshabilitado **fallando** [WE55]; (c) la demostración deliberada de abajo registrada; y (d) `rg -n "ResponseCookie|Set-Cookie|addCookie"
    apps/api/app/src/main` sin resultados. La parte de las rutas reales [WE54] se cierra en 3.3b.
  - **Demostraciones deliberadas.** (1) Hacer que `CsrfTokens.verify` ignore el hash del refresco (aceptar un token de otra sesión) pone en rojo
    `CsrfOriginFilterTest` en el caso del token de otra sesión: `200` en lugar de `403` [WE48]. (2) Quitar el paso 2 (`Origin`) del filtro pone en
    rojo el caso `POST` sin `Origin`: `200` en lugar de `403` [WE43]. (3) Mover el filtro después del de tokens pone en rojo `AdminFilterOrderTest`:
    el verificador espía registra una invocación con `Origin` ajeno [WE58]. Se revierten. PIT sobre `shared.security.csrf.*` en al menos 80.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; el escaneo de secretos no halla ninguna clave versionada. Commits:
    `feat(web): enforce Origin and a refresh-bound double-submit CSRF token on the admin chain` y
    `docs(security): note the CSRF scope, token format, key rotation and allowed origins`. Tamaño: nominal 330, realista 730; costura nombrada:
    1a `csrf-token-and-keys` (`CsrfTokens`, `CsrfKeys`, `CsrfConfiguration`, guardián, pruebas de arranque y de token) y 1b `csrf-origin-filter`
    (`AllowedOrigins`, `CsrfOriginFilter`, arnés, orden de filtros, códigos y documentación); la condición 3 se cierra en 1b. — Escenarios WE43 a
    WE50, WE53, WE55, WE57 a WE61, WE89 a WE91, «Recurso inexistente bajo un prefijo de documentación», «Un código desconocido no se filtra», BI55 y BI58.
  - **Nota fechada 2026-10-21 (partición de 1.1 en 01a, 01b y 01c).** La medición de la tarea completa fue de 2 489 líneas efectivas
    (`git diff --numstat` sin `openspec/`), frente a 730 estimadas. Se partió sin consulta por archivos completos: 01a
    `csrf-token-and-keys` (1 049), 01b `allowed-origins-and-csrf-code` (457) y 01c `csrf-origin-filter` (983). La costura nombrada
    1a/1b dejaba 1b en unas 1 440 líneas, de modo que se separó además la validación de `confia.web.allowed-origins`, el código
    `csrf-invalid`, el escaneo de claves (WE61) y las notas de `docs/03` y `docs/05`. 01a y 01c quedan por encima de 800 (la primera por
    el peso de sus pruebas de ataque y de arranque, la segunda por el arnés de cookie y la condición 3); no hay una costura nombrada
    que las reduzca sin separar una prueba de lo que prueba. La casilla se marca en 01c; la condición 3, parte del arnés, se cierra allí
    (`CsrfOriginFilterTest` en verde, su variante con el filtro deshabilitado fallando y `rg` sin resultados en `src/main`).
    El filtro lee las cookies de la cabecera `Cookie` y no con `jakarta.servlet.http.Cookie`, porque la inversión del inventario
    («exactamente `SessionCookies`») llega en 1.3; 1.3 puede sustituir esa lectura por `SessionCookies.read`.

- [x] 1.2 **PR 2 `audit-json-serializer`: constructor tipado del JSON de auditoría y migración de los 16 sitios (F1c, decisión 17, DA-13 *a*).**
  - **ROJO.** Crear `test/java/com/confia/shared/audit/AuditJsonTest.java` (escape de comillas, barra inversa, rango C0 y no ASCII; ida y vuelta
    por un analizador JSON; mismo orden de inserción produce los mismos bytes sin espacios y otro orden respeta su orden de inserción; nombres con
    mayúscula inicial, con guion, vacíos y repetidos fallan con `IllegalArgumentException`; solo texto, entero, booleano e instante) [AT02, AT03,
    AT05, AT06] y `AuditJsonSiteEqualityTest.java` (parametrizada por los 16 sitios de las 8 clases: la expresión antigua copiada literalmente y
    la nueva producen la misma cadena sobre `0`, `1`, `Integer.MAX_VALUE`, cada valor de cada enumeración de motivo, `true` y `false`, y un
    `Instant` con y sin fracción) [AT01]. Crear `test/java/com/confia/architecture/AuditJsonOnlySourceTest.java` con la convención de dos mitades
    y los fixtures permanentes `fixture/auditjson/` (`BadConcatenatedAuditJson`, `BadFormattedAuditJson` con `String.format`, `BadTextBlockAuditJson`
    y `GoodTokenClaimsJson` en `shared.security.token`, que no debe fallar): recorre la producción real, no admite conjunto vacío y falla sobre
    los 16 sitios actuales [BI43, BI44]. Crear `AuditChainAfterAuditJsonIT.java` (cadena de asientos escritos con el constructor verificada con
    `AuditChainVerifier`; la prueba de propiedades de la serialización canónica sigue pasando) [AT04]. **Rojos esperados:** clase `AuditJson`
    inexistente; la guardia falla señalando los 16 sitios de `AuthenticateWithPassword` (2), `ConsumeRecoveryCode` (1), `IssueSession` (1),
    `VerifyTotpCode` (1), `RequestPasswordReset` (2), `IssuePasswordResetToken` (2), `ResetPasswordWithToken` (3) y `RotateRefreshToken` (4).
  - **VERDE.** Crear `main/java/com/confia/shared/audit/AuditJson.java` (`object()`, `Builder.text`, `number`, `bool`, `instant`, `toJson()`;
    nombres `^[a-z][A-Za-z0-9]*$` sin repetición) y extraer el escape de `CanonicalAuditRowSerializer.canonString` a un método de paquete
    compartido. Migrar a `AuditJson` los 16 sitios de `main/java/com/confia/identity/application/{AuthenticateWithPassword,ConsumeRecoveryCode,
    IssueSession,VerifyTotpCode,RequestPasswordReset,IssuePasswordResetToken,ResetPasswordWithToken,RotateRefreshToken}.java`, sin tocar las
    pruebas existentes de `after_value`, que corren sin cambios. Ninguna cookie, ningún endpoint.
  - **Demostraciones deliberadas.** (1) Hacer que `number` emita el valor entre comillas pone en rojo `AuditJsonSiteEqualityTest` en los sitios con
    contadores: cadena distinta [AT01]. (2) Reintroducir un literal `"{\"` concatenado en `IssueSession` pone en rojo `AuditJsonOnlySourceTest`
    señalando la clase [BI43]. (3) Invertir el orden de dos miembros en `RotateRefreshToken.reasonJson` pone en rojo la igualdad de ese sitio.
    Se revierten.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; `AuditChainVerifier` verifica la cadena después de la suite. Commit:
    `refactor(shared): add a typed audit JSON builder and migrate the string-concatenating sites`. Tamaño: nominal 150, realista 330. — Escenarios
    AT01 a AT06, BI43 y BI44.
  - **Nota fechada 2026-10-21 (partición de 1.2 en 02a y 02b).** La medición de la tarea completa fue de 1 057 líneas efectivas
    (`git diff --numstat` sin `openspec/`), frente a 330 estimadas. Se partió sin consulta, por archivos completos, en la costura entre el
    constructor y su uso: 02a `audit-json-builder` (348: `AuditJson`, la extracción de `canonString` y las pruebas del constructor y de la
    cadena) y 02b `audit-json-sites` (709: los 16 sitios, la igualdad por sitio y la guardia con sus fixtures). Ninguna prueba queda separada
    de lo que prueba. Para que la igualdad compare los sitios reales y no una copia, cada expresión migrada vive en un método (o constante)
    estático de paquete de su clase (`backoffCycleAfterValue`, `lowCodesAfterValue`, `rateLimitedAfterValue`, `issuedAfterValue`,
    `startedAfterValue`, `sessionsRevokedAfterValue`, `completedAfterValue`, `reasonJson`, `AFTER_*`). La guardia lee el código fuente
    porque javac pliega la concatenación en `invokedynamic` y ArchUnit no ve el literal. Desviación de la demostración 3: `RotateRefreshToken.reasonJson`
    tiene un solo miembro y no admite inversión; se invirtió el orden en `ResetPasswordWithToken.sessionsRevokedAfterValue`
    (`reason`, `families`), con el mismo efecto sobre la igualdad. La casilla se marca en 02b.

- [x] 1.3 **PR 3 `edge-policies-and-delay-cap`: tres políticas de tasa, tope de la espera, sonda S-5 y `SessionCookies` sin llamador (decisiones 5, 7, 8 y 9).**
  - **ROJO.** Crear en `test/java/com/confia/shared/web/`: `ratelimit/RateLimitPropertiesTest.java` (valores por omisión de las tres políticas
    desde `application.yml`, la capa 2 con 1 intento por minuto y tope de 1 hora; componente `admin-session` ausente, componente `admin-otro`
    desconocido y valor no positivo detienen el arranque nombrando la propiedad completa) [WE70, WE73, WE93], `ratelimit/RateLimitIndependenceTest.java`
    (rutas del arnés con `@RateLimited` de cada política; agotar `admin-login` no afecta a las otras) [WE71], `delay/RequiredDelayMaterializerTest.java`
    (con retardo de 900 s y tope de 30 s, el temporizador de prueba registra una espera solicitada de 30 s y `Delayed` conserva 900; con 2 s espera 2 s)
    [WE75, WE76], `delay/DelayPropertiesTest.java` (tope en 0, negativo y 61 s no arranca) [WE77], `UnknownJsonPropertyTest.java` (**sonda S-5, el
    primer rojo**: un DTO de registro con un campo recibe `{"a":"x","x":"VALOR-SENSIBLE-123"}`; `400 validation-failed` sin repetir nombre ni valor)
    [WE78], `session/SessionCookiesTest.java` (atributos exactos de las dos cookies: `__Secure-confia_admin_sid` con `HttpOnly`, `Secure`,
    `SameSite=Strict`, `Path=/api/v1/auth/sessions`, 43 caracteres, sin `Domain`, `Max-Age` ni `Expires`; `__Host-confia_csrf` con `Secure`, `Path=/`,
    `SameSite=Strict` y sin `HttpOnly`; borrado con valor vacío, `Max-Age=0` y los mismos atributos; lectura `ABSENT`, `PRESENT` y `DUPLICATED`)
    [WE52] y `session/NoStraySetCookieTest.java` (rutas públicas de documentación, denegadas, `GET /api/v1/auth/sessions/current` y errores `401` no
    llevan `Set-Cookie` ni crean sesión HTTP) [WE65]. Ampliar `test/java/com/confia/architecture/WebEdgeScopeExclusionInventoryTest.java` (de «ninguna
    clase usa `Cookie`» a «exactamente `SessionCookies`», con el fixture permanente `fixture/cookie/BadControllerUsingCookie` en `identity.web`)
    [BI47] y confirmar con `SigningAndHashingConfinementTest` que el HMAC está en `shared.security.csrf` y el único uso de `Cookie` en `SessionCookies`
    [BI48]. **Rojos esperados:** prefijo de configuración fijo a `admin-login`; el materializador espera 900 s; `UnknownJsonPropertyTest` responde
    `200` si el valor por omisión del convertidor ignora la propiedad; `not in the allow-list` nombrando `com.confia.shared.web.session`.
  - **VERDE.** Editar `main/java/com/confia/shared/web/ratelimit/RateLimitProperties.java` (`confia.web.rate-limit`, `ignoreUnknownFields = false`,
    tres componentes `PolicyValues`), `shared/web/edge/ThrottlingConfiguration.java` y `shared/security/RateLimiterConfiguration.java` (tres
    limitadores y tres políticas); crear `shared/web/ratelimit/RateLimitFailures.java` (bean; `record(policy)` con `RequestOrigin.current()`).
    Editar `shared/web/delay/{DelayProperties,RequiredDelayMaterializer}.java` y `shared/web/edge/RequiredDelayConfiguration.java` (`max-wait`, 30 s,
    máximo 60 s). Crear `shared/web/session/{SessionCookies,SessionCookie,package-info}.java` y `shared/web/edge/SessionCookieConfiguration.java`
    (la escritura con `ResponseCookie`; solo `SessionCookies` usa `jakarta.servlet.http.Cookie`, para leer; sin indicador que desactive `Secure`), el
    `@Import` en `AdminApplication.java` y las líneas de `ProcessBeanPolicy` (administración permite `com.confia.shared.web.session`; portal y
    trabajador lo prohíben con el motivo «credencial en cookie del dominio administrativo»). Editar `main/resources/application.yml` (valores de las tres
    políticas, `confia.web.delay.max-wait`, `spring.jackson.deserialization.fail-on-unknown-properties: true`). Si la propiedad de Jackson no la honra
    el autoconfigurador de Spring Boot 4.1.1, sustituirla en este PR por un `JsonMapperBuilderCustomizer` en `WebEdgeConfiguration` y dejar el
    resultado en una nota fechada (R-2). Nota fechada en `docs/03-seguridad.md` §10 (filas `admin-mfa` y `admin-session`, tope de la espera).
  - **Orden duro.** Este PR crea la capacidad de emitir `Set-Cookie` y se fusiona **después de 1.1**. Ningún controlador de producción llama a
    `SessionCookies` (`rg -n "SessionCookies" apps/api/app/src/main` solo muestra su paquete y su configuración); la primera emisión es 3.1b.
  - **Demostraciones deliberadas.** (1) Ignorar `max-wait` en el materializador pone en rojo `RequiredDelayMaterializerTest`: la espera solicitada es
    900 s en lugar de 30 s [WE75]. (2) Quitar `SameSite=Strict` de `SessionCookies` pone en rojo `SessionCookiesTest` [WE52]. (3) Poner
    `fail-on-unknown-properties` en `false` pone en rojo `UnknownJsonPropertyTest`: `200` en lugar de `400` [WE78]. Se revierten.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commits: `feat(web): add per-policy rate limits, a wait cap and the session cookie helper` y
    `docs(security): note the admin-mfa and admin-session policies and the wait cap`. Tamaño: nominal 280, realista 620. — Escenarios WE52, WE65,
    WE70, WE71, WE73, WE75 a WE78, WE93, BI47 y BI48.
  - **Nota fechada 2026-10-21 (partición de 1.3 en 03a, 03b y 03c).** La medición de la tarea completa fue de 1 775 líneas efectivas
    (`git diff --numstat` sin `openspec/`; 1 634 añadidas y 141 borradas), frente a 620 estimadas. Se partió sin consulta, por archivos
    completos, en tres costuras: 03a `delay-cap` (268: `max-wait` en `DelayProperties` y en el materializador, su prueba y la nota de
    `docs/03` sobre el tope), 03b `rate-policies` (732: las tres políticas bajo `confia.web.rate-limit.<política>.*`, los tres limitadores,
    `RateLimitFailures`, los valores por omisión en `application.yml` y la nota de las filas `admin-mfa` y `admin-session`) y 03c
    `session-cookies-and-unknown-properties` (775: la sonda S-5, `SessionCookies` sin llamador, la inversión del inventario de `Cookie` y
    la línea de `ProcessBeanPolicy`). Ninguna prueba queda separada de lo que prueba. Desviaciones: (1) `NoStraySetCookieTest` vive en
    `com.confia.bootstrap` y no en `shared.web.session`, porque usa el proceso administrativo real (`OpenApiProcess` es de ese paquete); el
    `200` de `GET /api/v1/auth/sessions/current` sin `Set-Cookie` se afirma en `CurrentSessionEndpointIT`. (2) El escenario WE73 nombra la
    propiedad `...admin-session.requests`; la propiedad existente es `request-limit` y es la que la prueba usa. (3) R-2: la propiedad
    `spring.jackson.deserialization.fail-on-unknown-properties: true` **sí** la honra el autoconfigurador de Spring Boot 4.1.1 (la sonda S-5
    dio `200` sin ella y `400 validation-failed` con ella), de modo que no hizo falta el `JsonMapperBuilderCustomizer`. (4) `CsrfOriginFilter`
    conserva la lectura de la cabecera `Cookie`: sustituirla por `SessionCookies.read` no se pidió y movería el filtro a depender de un bean.
    (5) `RateLimitFailures.record` con una política que ningún limitador tiene lanza `IllegalArgumentException` en lugar de no hacer nada,
    porque un no-op silencioso apagaría la capa 2. La casilla se marca en 03c.

---

## Fase 2: revocación y migración

- [x] 2.1 **PR 4 `session-revocation-v9`: `V9`, revocación por familia y por cuenta, F1a, F1b y concurrencia (decisiones 15 y 16, O10 parte datos).**
  - **ROJO.** Crear (los paquetes de las pruebas de integración se confirman contra el árbol): `RevocationReasonCatalogIT.java` y ampliar
    `RefreshTokenSchemaIT.java`, `MultiTenantSchemaIT.java` y `RolePrivilegeMatrixIT.java` (cada motivo nuevo aceptado, `admin_revoke` y la cadena
    vacía rechazados, `revoked_at` con motivo y viceversa; enumeración `SessionRevocationReason` igual a la restricción y una **variante que añade
    un valor sin migración que debe fallar** [BI40]) [BI39, BI05]; `V9SchemaDiffIT.java` (frente a `V8` solo cambia la restricción del motivo; la
    suma de verificación de `V8` no cambió) [BI41]; `V9ExistingRowsIT.java` (familias `reuse_detected` y `password_change` previas sobreviven)
    [BI42]; el esquema de `V8` ya existente se re-ejecuta sin cambios [BI01 a BI04, BI06, BI36, BI37] y `admin_revoke` sigue rechazado [I204];
    `EndSessionIT.java` (`EndCurrentSession` revoca con `logout` también con el token consumido; desconocido, mal formado, de otra institución,
    revocado o vencido sin efecto ni asiento y sin cambiar motivo ni instante; solo la familia del refresco; `EndAllSessions` revoca el 100 % de las
    familias vivas de la cuenta con asiento de recuento y ninguna ajena ni ya revocada) [I195, I196, I197, I200]; `AccountSessionRevokerCrossInstitutionIT.java`
    (dos instituciones con dos familias vivas cada una, PostgreSQL real y rol `confia_admin_app`; revocar y cerrar todas en A no cambia B) [I208];
    `PasswordResetLeavesNoValidTokenIT.java` (tres familias con refresco y acceso vigentes; tras restablecer, los tres refrescos dan `Rejected` con
    `family_revoked` y los tres accesos dan `401 token-invalid`; la cuenta ajena sigue sirviendo; control negativo con un restablecimiento sin
    revocador) [I207]; `LogoutVersusRotationConcurrencyIT.java` y `CloseAllVersusRotationConcurrencyIT.java` (dos transacciones reales con
    `CountDownLatch` y `pg_stat_activity`; en todo orden la familia queda revocada y sin token vigente) [I198]. **Rojos esperados:** el `CHECK` de
    `V8` rechaza `logout`; clases inexistentes; fixture de la segunda institución inexistente.
  - **VERDE.** Crear `main/resources/db/migration/V9__extend_identity_refresh_token_family_revocation_reasons.sql` (decisión 16: reemplaza solo el
    `CHECK`, sin columnas, tablas ni concesiones). Editar `main/java/com/confia/identity/application/{SessionRevocationReason,
    RefreshTokenFamilyRepository}.java` (`LOGOUT`, `MFA_CHANGE`, `BINDING_CHANGED`; `revoke(institutionId, familyId, reason, now)` condicional a
    `revoked_at IS NULL`; `revokeForReuse` se conserva), `identity/infrastructure/JooqRefreshTokenFamilyRepository.java`, crear
    `identity/application/{EndCurrentSession,EndAllSessions}.java` (`findByHash` sin bloqueo, después `lockById`; `EndAllSessions` cuenta, después
    familias `ORDER BY id`; asientos con `AuditJson`) y registrarlos en `identity/infrastructure/wiring/IdentityConfiguration.java`. El código de
    jOOQ se regenera en la construcción. Sin controlador, sin ruta, sin cookie.
  - **Demostraciones deliberadas.** (1) Quitar el `revoked_at IS NULL` de `revoke` pone en rojo `EndSessionIT`: la familia ya revocada cambia de
    motivo [I196]. (2) Quitar `binding_changed` del `CHECK` de `V9` pone en rojo `RevocationReasonCatalogIT`: la inserción del motivo falla
    [BI39]. (3) Con el revocador desactivado, `PasswordResetLeavesNoValidTokenIT` falla: un refresco rota en lugar de `family_revoked` [I207].
    Se revierten.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; `identity.domain` en 95 y PIT en 80. Commits: `feat(identity): add the logout, mfa_change and
    binding_changed revocation reasons` y `feat(identity): revoke a session family by refresh token and every live family of an account`. Tamaño:
    nominal 400, realista 880; costura nombrada: 4a `v9-and-revocation-use-cases` (`V9`, enumeración, `revoke`, `EndCurrentSession`,
    `EndAllSessions`, `RevocationReasonCatalogIT`, `V9SchemaDiffIT`, `V9ExistingRowsIT`, `EndSessionIT`) y 4b `revocation-follow-ups` (F1a, F1b y
    las dos pruebas de concurrencia). — Escenarios I195 a I198, I200, I204, I207, I208, BI01 a BI06, BI36, BI37 y BI39 a BI42.
  - **Nota fechada 2026-10-22 (partición de 2.1 en 04a, 04b, 04c y 04d).** La medición de la tarea completa, construida en un árbol temporal local
    antes de partirla, fue de 1 552 líneas efectivas solo para `V9` y los casos de uso (`git diff --numstat` sin `openspec/`), frente a 880
    estimadas, y la tarea cerrada suma 2 178 repartidas en cuatro ramas apiladas, partidas sin consulta y por archivos completos: 04a
    `v9-revocation-reasons` (541: `V9`, la enumeración con los tres motivos, `RevocationReasonCatalogIT`, la ampliación de `RefreshTokenSchemaIT`,
    `StepwiseDatabase`, `V9SchemaDiffIT` y `V9ExistingRowsIT`), 04b `end-current-session` (775: `RefreshTokenFamilyRepository.revoke`,
    `EndCurrentSession`, `SessionRevocationAudit`, `EndSessionIT` en su parte de cierre actual y `RefreshTokenFamilyRevokeIT`), 04c
    `end-all-sessions` (272: `EndAllSessions` y su parte de `EndSessionIT`) y 04d `revocation-follow-ups` (590: F1a, F1b y las dos pruebas de
    concurrencia). Ninguna prueba queda separada de lo que prueba. Desviaciones: (1) la costura nombrada (4a casos de uso, 4b seguimientos)
    no bastaba, porque 4a ya pasaba de 1 500 líneas. (2) La demostración 1 de la tarea no produce rojo en `EndSessionIT`: los casos de uso
    comprueban la vigencia antes de llamar a `revoke`, de modo que `revoked_at IS NULL` es una red de seguridad que esa prueba no alcanza;
    se añadió `RefreshTokenFamilyRevokeIT`, que la ejerce directamente (dos aserciones de resultado en rojo y el disparador de `V8` como
    segunda defensa), y la ruptura equivalente en el caso de uso (quitar la comprobación de vigencia) sí pone en rojo `EndSessionIT`. (3) La
    demostración 3 se aplicó en el adaptador `JooqAccountSessionRevoker` (devolver cero sin revocar), no en el caso de uso: el efecto es el
    mismo y no rompe el inventario de `IdentityScopeExclusionInventoryTest`. (4) `MultiTenantSchemaIT` y `RolePrivilegeMatrixIT` se
    re-ejecutan sin cambios: `V9` no añade política ni concesión y `V9SchemaDiffIT` fija esa ausencia. (5) `EndCurrentSession` arma su propio
    contexto de seguridad de actor `system` con la institución de `LoginInstitutionProvider`, y trata un texto `null` como «nada que hacer»
    sin abrir transacción. (6) `EndAllSessions.execute` devuelve el número de familias revocadas y falla con `IllegalStateException` si la
    cuenta del actor no existe. (7) F1a (`PasswordResetLeavesNoValidTokenIT`) arranca el proceso administrativo real con un par de claves
    generado para la ejecución y emite las sesiones con el reloj real, porque el proceso lee el suyo; su control negativo restablece con un
    revocador que no revoca nada y demuestra que entonces las seis comprobaciones encuentran todo vivo. (8) La demostración de bloqueo de las dos pruebas de
    concurrencia (quitar `FOR UPDATE` de `lockById`) pone en rojo la aserción de espera y no el estado final, que es el mismo en cualquier
    orden, como ya documentan las pruebas de rotación de S1.

---

## Fase 3: endpoints que emiten credenciales

- [x] 3.1 **PR 5 `login-endpoint`: inicio de sesión, señal de cinco cuentas y `authentication-failed`; cierra H1 (decisiones 10, 11 y 12).**
  - **ROJO, primero la parte 5a.** Crear `test/java/com/confia/identity/domain/FailureSpreadWindowTest.java` y `FailureSpreadWindowProperties.java`
    (cuatro huellas distintas no señalan, la quinta sí, la misma huella no suma, el borde de 10 minutos es estricto —el fallo de las 10:00:00 ya no
    cuenta a las 10:10:00—, la sexta no vuelve a señalar dentro de la ventana, cinco fallos del mismo identificador no señalan) [I165, I166] y
    `test/java/com/confia/identity/application/LogInIT.java` (tres desenlaces; `Authenticated` crea la familia en la misma transacción que la
    auditoría y un escritor de auditoría que falla revierte ambas; el token restringido lleva el `purpose` correcto y emitirlo no crea familia
    ni token de refresco; el quinto identificador escribe `identity.login.failure_spread_detected` en la misma transacción, con la dirección canónica
    y `{"distinctIdentifiers":5,"windowSeconds":600}`, y un `WARN` con solo el identificador de la petición y el nombre del evento; el comando lleva
    la IP y la huella del agente, y acepta origen nulo) [I164, I224, I205, I206, I75]. Re-ejecutar sin cambios `IssueSessionIT`
    [I87 a I90] y `AccessTokenIssuerTest` [I74], y ampliar las pruebas de la familia con IP y huella de agente [I119, I120]. **Rojos esperados:** clases
    inexistentes; `AuthenticationCommand` sin origen.
  - **ROJO, después la parte 5b.** Crear `test/java/com/confia/identity/web/LoginRateLimitClosesH1IT.java` (**H1**: con la política `admin-login` real,
    10 inicios por minuto llegan al caso de uso y el undécimo recibe `429` con `too-many-requests` y `Retry-After`, con cero invocaciones del caso de
    uso y sin transacción [I160]; 10 fallos `Rejected` con identificadores distintos activan la capa 2 y desde ahí las filas y
    `consecutive_failures` de `identity_login_backoff` y los asientos `identity.login.failed` dejan de crecer [I161]; 12 inicios correctos de cuentas con
    segundo factor pendiente dejan el contador de fallos en cero [I162]; una petición con `Origin` ajeno recibe `403` y el cupo sigue en 9 [I163]),
    `LoginEndpointIT.java` (`200` con la respuesta de sesión y exactamente dos `Set-Cookie`, sin correo, nombre ni `sid` [I149]; `second-factor-required`
    con token `mfa-verify` de 300 s y sin cookies ni familia [I150]; `second-factor-enrollment-required` [I151]; `401 authentication-failed` con
    `WWW-Authenticate: Bearer` y sin cookies [I152]; cuatro cuerpos inválidos y la contraseña de 3 caracteres que sí llega al caso de uso [I153];
    `415` con `text/plain` y formulario [I154]; la institución del cuerpo y de `X-Institution-Id` se ignora [I155]; la espera del retardo ocurre
    tras confirmar y no deniega [I156]; igualdad de estado, cuerpo salvo `traceId` y cabeceras salvo `Date` entre cuenta existente e inexistente, con un
    control negativo, también con tres fallos previos y retardo de 2 s [I157, I158]; el inicio de sesión no lee ni exige CSRF y sobrescribe una cookie
    anterior sin revocar su familia [I228]; cookies que no crean sesión HTTP ni `JSESSIONID` [WE87]; ninguna respuesta pública o denegada crea sesión
    [«Ninguna respuesta crea sesión»]; `415` del tipo de contenido [«Tipo de contenido no admitido»]), `LoginCookieProfilesIT.java` (perfiles `local`,
    `preprod` y `prod`: ambas cookies `Secure` y con prefijo; el conjunto de propiedades del ayudante no tiene ninguna que apague `Secure` ni cambie
    los nombres, con un fixture que la añade que debe fallar) [WE62, WE64]. Ampliar `ProblemResponsesTest`, `WwwAuthenticateChallengeTest` y las pruebas del
    traductor con `authentication-failed` (`401`, `WWW-Authenticate: Bearer` exacto sin `realm` ni `error_description`; las demás respuestas no la llevan)
    [WE11 a WE14, WE41]. Ampliar las pruebas existentes del origen de la petición en la bitácora (asiento con `sourceIp` y `userAgent`, nulos fuera de
    petición, 50 peticiones concurrentes sin cruces) [«Asiento durante una petición», «Asiento fuera de una petición», «Peticiones concurrentes con
    orígenes distintos»]. Crear `test/java/com/confia/architecture/SessionRoutesRateLimitedRuleTest.java` (regla de ArchUnit con convención de dos
    mitades: todo manejador de `identity.web` de las rutas de sesión declara su política; fixture permanente
    `fixture/ratelimit/BadLoginHandlerWithoutPolicy`; evalúa el manejador real de inicio de sesión, sin conjunto vacío) [BI45]; ampliar
    `WebEdgeScopeExclusionInventoryTest.java` (limitador y materializador permitidos además en `com.confia.identity.web`; fixtures de un paquete
    ajeno y de una regla de ruta distinta de `permitAll`, `authenticated` y `denyAll`, que deben fallar) [BI49]; `OpenApiContractSnapshotTest.java`
    (operación de inicio de sesión con `200`, `400`, `401`, `403`, `415`, `429` y `503`, `oneOf` con discriminador `status`, sin respuestas genéricas
    no aprobadas, portal vacío) [BI25, BI27]; `PublicRouteAllowListTest.java` y `RegisteredRoutes.java` (la ruta pública nueva);
    `WebLayerTokenIsolationTest.java` y `LayeredArchitectureTest.java` (los controladores de S2 no dependen de `shared.security.token` ni de
    `identity.domain`); líneas de `ProcessBeanPolicy` primero en rojo. **Rojos esperados:** ruta inexistente; `ProcessBeanIsolationTest` con
    `not in the allow-list` para `com.confia.identity.web`.
  - **VERDE, 5a.** Crear en `main/java/com/confia/identity/application/`: `LogIn`, `LoginCommand`, `LoginDecision`, `LoginOutcome`, `SessionGrant`,
    `SecondFactorChallenge` (nunca `record`, `toString()` redactado) y `LoginFailureSpread`; en `identity/domain/FailureSpreadWindow.java`; en
    `identity/infrastructure/InMemoryLoginFailureSpread.java` (mapa acotado por `ClientAddress.rateLimitKey()`). Editar `AuthenticationCommand`
    (`clientAddress` y `userAgent`, O6) y `AuthenticateWithPassword` si hace falta; `LogIn` compone en una transacción
    `AuthenticateWithPassword.runWithinTransaction` e `IssueSession.runWithinTransaction`, repite la guardia de institución antes de abrirla, firma
    después de confirmar y no usa `recordFailure`. Registrar los beans en `IdentityConfiguration.java`. **5a no registra ninguna ruta.**
  - **VERDE, 5b.** Crear `main/java/com/confia/identity/web/{LoginController,LoginRequest,AuthenticatedSessionResponse,
    SecondFactorPendingResponse}.java` y `identity/web/wiring/{IdentityWebConfiguration,package-info}.java`; el manejador lleva
    `@RateLimited(policy = "admin-login")`, `consumes = application/json`, llama a `RateLimitFailures.record("admin-login")` dentro del proveedor del
    `RequiredDelayMaterializer` solo con `Failed`, y llama a `SessionCookies.issue` solo con `Started` (primera emisión de producción de `Set-Cookie`).
    Editar `shared/web/edge/PublicEndpoints.java` (`POST /api/v1/auth/sessions`), `shared/web/problem/{ProblemCode,ProblemResponses}.java` y
    `problems.properties` (`authentication-failed`, `401`, `Bearer`, es-HN de la decisión 18), `bootstrap/admin/AdminApplication.java`
    (`@Import` de `IdentityWebConfiguration`), `ProcessBeanPolicy.java`, `apps/api/openapi/admin.openapi.json` (con `@ApiResponse` explícitos) y
    `docs/03-seguridad.md` §4.4 (nota fechada: control por IP aplicado y señal de cinco cuentas sin bloqueo). `packages/contracts` se regenera, no se compromete.
  - **Condición de cierre (H1).** La casilla de 3.1 **no se marca** sin: (a) `LoginRateLimitClosesH1IT` en verde sobre el árbol fusionado de 5b, con
    el controlador, el limitador, `SessionRoutesRateLimitedRuleTest` y la prueba en el **mismo PR**; (b) su demostración deliberada registrada (abajo);
    (c) ningún commit de `main` que exponga `POST /api/v1/auth/sessions` sin `@RateLimited` (5a no registra la ruta: `PublicRouteAllowListTest` afirma
    que el conjunto de rutas de producción no cambia en 5a); y (d) la reserva declarada en el PR: con `confia.web.trusted-proxies` vacío el tráfico
    detrás de un proxy comparte un cubo; su llenado es del cambio 11.
  - **Demostraciones deliberadas.** (1) **H1:** quitar `@RateLimited` del manejador de inicio de sesión pone en rojo `LoginRateLimitClosesH1IT`: la
    petición undécima responde `401` en lugar de `429` y `identity_login_backoff` sigue creciendo [I160, I161], y a la vez `SessionRoutesRateLimitedRuleTest`
    señala el manejador [BI45]. (2) Hacer que `recordFailure` cuente también `ChallengeIssued` pone en rojo la prueba de [I162]: contador distinto de cero.
    (3) Cambiar el `detail` solo cuando la cuenta no existe pone en rojo la igualdad de [I157]: cuerpo distinto. (4) Hacer que el desenlace de segundo
    factor emita las cookies pone en rojo [I150] y [I151]: `Set-Cookie` presente. Se revierten.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; `identity.domain` en 95 y PIT en 80 (incluido `FailureSpreadWindow`); se mide la duración de `verify`
    para el presupuesto de BI57. Commits: 5a `feat(identity): add the login use case and the failed-login spread signal`; 5b
    `feat(identity): expose the login endpoint under the admin-login rate limit`, `feat(web): add the authentication-failed problem code` y
    `docs(security): note the applied per-IP control and the spread signal`. Tamaño: nominal 600, realista 1 320; costura nombrada: 5a
    `login-use-case-and-failure-spread` (todo salvo web, ruta, limitador y OpenAPI) y 5b `login-controller-and-h1` (controlador, DTO, ruta, política,
    regla, H1, OpenAPI, documentación). — Escenarios I74, I75, I87 a I90, I119, I120, I149 a I166, I205, I206, I224, I228, WE11 a WE14, WE41, WE62, WE64,
    WE87, «Ninguna respuesta crea sesión», «Tipo de contenido no admitido», los tres escenarios del origen de la petición en la bitácora, BI25, BI27, BI45 y BI49.

- [x] 3.2 **PR 6 `second-factor-endpoints`: clase de ruta restringida, inscripción repetible, verificación y revocación `mfa_change` (decisiones 6 y 13, DA-7 *c*, O10, D-N2, D-N3).**
  - **ROJO, parte 6a.** Crear `test/java/com/confia/shared/web/authentication/SecondFactorRouteTest.java` (arnés con una ruta restringida de
    `{VERIFY, ENROLL}` y otra de `{ENROLL}`: el principal restringido está presente y `AuthenticatedActor.current()` está vacío, y
    `TokenCurrentInstitutionProvider` lanza [WE66]; el acceso, `mfa-verify` y `mfa-enroll` abren solo lo suyo y el resto responde `401 token-invalid`,
    con `mfa-enroll` aceptado en la verificación y en la inscripción y `VERIFY` rechazado en la inscripción [WE67]; un controlador de prueba que acepta
    un restringido sin figurar en la clase hace fallar el enumerador [WE68]; un `mfa-verify` presentado a `DELETE /api/v1/auth/sessions` y a una ruta no
    registrada da `401 token-invalid` [WE69]; el restringido en una ruta autenticada o no registrada se rechaza por su audiencia [WE09]; el token del
    portal se rechaza en rutas autenticadas y restringidas [WE10]). Ampliar `AccessTokenAuthenticationFilterTest.java` (mapeo de `MfaPurpose` a
    `SecondFactorPurpose` solo dentro del filtro) y re-ejecutar sin cambios `AccessTokenVerifierTest` [I76, I77] y `CurrentSessionEndpointIT`
    [I129 a I134, que no deben cambiar]. Invertir en `PublicRouteAllowListTest` y `RegisteredRoutes` el enumerador de tres clases
    (pública, autenticada, restringida). **Rojos esperados:** clases inexistentes; el token restringido recibe `401` en toda ruta.
  - **ROJO, parte 6b.** Crear `test/java/com/confia/identity/application/EnrollTotpSecondFactorIT.java` (primera inscripción: secreto base32 de 32
    caracteres, diez códigos distintos, secreto almacenado con prefijo `v1:` [I180]; reinscripción con credencial sin verificar: secreto distinto, diez
    códigos previos con `used_at` y dos asientos `identity.mfa.enrolled`, el segundo con `{"replacedUnverified":true,"invalidatedRecoveryCodes":10}`
    [I181]; credencial verificada: `mfa-already-enrolled` y solo el asiento `identity.mfa.enrollment_rejected` [I182]),
    `CompleteSecondFactorIT.java` (TOTP válido con `mfa-verify` emite sesión con `amr = [pwd, otp]` [I170]; código de recuperación válido consumido y
    segundo uso rechazado [I171]; código incorrecto avanza el retroceso y desde el tercer fallo espera tras confirmar [I172]; código reutilizado en la
    misma ventana [I175]; un `recoveryCode` incorrecto suma al retroceso TOTP y pasa de dos a tres fallos [I223]; con `mfa-enroll` y credencial sin
    verificar un código de recuperación se rechaza sin consumirse ni emitir familia [I169, I221]; con `mfa-enroll` y credencial ya verificada toda
    prueba da `second-factor-invalid` [I222]; la primera verificación activa la credencial y revoca las demás familias con `mfa_change` dejando viva la
    nueva y sin tocar la cuenta ajena [I176]; una verificación posterior no revoca [I177]; una activación que falla tras revocar revierte todo [I178];
    sin otras sesiones escribe el asiento con cero y no cruza instituciones [I179]; el TOTP aceptado pasa el contador a cero o más y el siguiente inicio
    de sesión pide `SecondFactorRequired` [I168]), `AuthenticateWithPasswordIT.java` y `ResetPasswordWithTokenIT.java` actualizadas («MFA activa» es
    secreto verificado: recién inscrita da `SecondFactorEnrollmentRequired` [I167, I217]; el restablecimiento con credencial sin verificar acepta solo
    el enlace y el siguiente inicio pide inscripción [I218]; los escenarios modificados de la obligación de MFA y del restablecimiento con y sin segundo
    factor), `EnrollVersusVerifyConcurrencyIT.java` y `ConcurrentEnrollmentIT.java` (dos peticiones con `CountDownLatch` y transacciones reales: dos
    `201`, un solo registro de credencial, el secreto vigente es el de la última y los códigos sin usar son solo los suyos, nunca `409` ni `500`
    [I183]), `test/java/com/confia/identity/web/SecondFactorEndpointsIT.java` (HTTP: respuestas de [I170] a [I174] y [I180], `400` con ambos códigos o
    ninguno [I173], `401 token-expired` para el vencido y `token-invalid` para el de acceso [I174], `mfa-verify` en la inscripción da `401
    token-invalid` [I184], el décimo `second-factor-invalid` activa `admin-mfa` y la verificación a los 30 s recibe `429` con `Retry-After`
    [WE72]) y la lectura documental de la nota de riesgo de la ventana de primera inscripción [I185]. **Rojos esperados:** comportamiento de
    inserción simple; desenlace antiguo `SecondFactorRequired`; clases inexistentes.
  - **VERDE, 6a.** Crear `main/java/com/confia/shared/security/{PendingSecondFactor,SecondFactorPurpose}.java` y
    `shared/web/authentication/{SecondFactorEndpoint,SecondFactorAuthentication}.java`; editar `shared/web/authentication/{AuthenticatedEndpoints,
    AccessTokenAuthenticationFilter}.java` (`secondFactorEndpoints()` **vacía en producción** hasta 6b, como la lista autenticada de S1; en la ruta de
    segundo factor solo `verifyMfa` y el uso; en las demás, `verifyAccess` sin cambios) y `shared/web/edge/SecurityChains.java` (rutas restringidas
    con `authenticated()`; el inventario de reglas no cambia). La exención de `WebLayerTokenIsolationTest` sigue en dos clases.
  - **VERDE, 6b.** Editar `main/java/com/confia/identity/domain/TotpCredential.java` (`isVerified()`), `identity/application/{AuthenticateWithPassword,
    ResetPasswordWithToken,TotpCredentialRepository,RecoveryCodeRepository,EnrollTotpSecondFactor,EnrollTotpSecondFactorResult}.java` y
    `identity/infrastructure/{JooqTotpCredentialRepository,JooqRecoveryCodeRepository}.java` (`replaceUnverifiedSecret`, `invalidateUnused`); crear
    `identity/application/CompleteSecondFactor.java` (`accounts.lockById`; tabla de la decisión 13; los fallos de recuperación suman al retroceso;
    en la primera verificación `revokeAllLive(..., MFA_CHANGE, now)` antes de `IssueSession.runWithinTransaction`; asientos `identity.mfa.activated`,
    `identity.session.revoked` e `identity.mfa.enrolled` con `AuditJson`) y `identity/web/{SecondFactorController,SecondFactorVerificationRequest,
    EnrollmentResponse}.java` (`@RateLimited(policy = "admin-mfa")`, `RateLimitFailures.record("admin-mfa")` dentro del proveedor, materializador).
    Llenar `secondFactorEndpoints()` de producción con las dos rutas. Códigos `second-factor-invalid` (`401`, `Bearer`) y `mfa-already-enrolled` (`409`) en
    `ProblemCode`, `ProblemResponses` y `problems.properties`. `ProcessBeanPolicy`, `SessionSecretsNotObservableTest` con los tipos nuevos, OpenAPI
    (`mfa/verifications` y `mfa/enrollments`) y notas fechadas en `docs/03-seguridad.md` §4.5 y `docs/adr/ADR-0005-*.md` (inscripción de dos momentos sin migración,
    «MFA activa» = verificada, ventana de la primera inscripción por DA-15).
  - **Demostraciones deliberadas.** (1) Hacer que el filtro use `verifyAccess` en las rutas restringidas pone en rojo `SecondFactorRouteTest`: `401`
    en lugar de `200` con el principal [WE67]. (2) Poblar `AuthenticatedActor` desde el token restringido pone en rojo [WE66]: el actor deja de estar
    vacío. (3) Aceptar el código de recuperación con `mfa-enroll` en `CompleteSecondFactor` pone en rojo `CompleteSecondFactorIT`: `200` y
    contador distinto de -1 en lugar de `401` [I221]. (4) Revocar con `mfa_change` después de emitir la familia nueva pone en rojo [I176]: la familia
    nueva queda revocada. (5) Quitar `accounts.lockById` de la inscripción pone en rojo `ConcurrentEnrollmentIT`: una respuesta `500` por violación
    de unicidad [I183]. Se revierten.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; `identity.domain` en 95 y PIT en 80. Commits: 6a `feat(web): add the restricted second-factor route class and its
    own principal`; 6b `feat(identity): make second-factor enrollment repeatable until the first verification`, `feat(identity): activate the credential on
    the first TOTP verification and revoke other sessions` y `docs(security): note the two-moment enrollment and the verified-credential definition`.
    Tamaño: nominal 650, realista 1 430; costura nombrada: 6a `restricted-route-class` y 6b `enrollment-and-verification`. — Escenarios I76, I77, I129 a
    I134, I167 a I185, I217, I218, I221 a I223, los seis escenarios sin identificador de la obligación de MFA y del restablecimiento, WE09, WE10, WE66 a WE69 y WE72.

- [x] 3.3 **PR 7 `refresh-and-logout-endpoints`: vinculación, refresco con cookie, cierres, OpenAPI y condición 3 sobre rutas reales (decisiones 14 y 15).**
  - **ROJO, parte 7a.** Crear `test/java/com/confia/identity/domain/RotationDecisionTest.java` y `RotationDecisionProperties.java` (orden fijo:
    revocada, vencida o inactiva, consumido no apto para gracia es `REUSE_DETECTED`, vinculación, gracia, token vencido, rotación; la propiedad
    «con la vinculación sin cambio el resultado es el de S1»), `test/java/com/confia/identity/application/RefreshBindingIT.java` (cambian agente e IP:
    `401`, familia con `binding_changed`, sucesores vaciados, asiento sin huella ni dirección [I190]; solo uno cambia: rota [I191, I121]; familia sin IP o
    sin huella: rota [I192]; solo se revoca la familia afectada [I193]; reutilización antes que vinculación y vinculación antes que vencimiento del token
    [I225]; IPv6 comprimido y expandido es la misma dirección [I226]). **Rojos esperados:** firma antigua de `decide`; sin `binding_changed`.
  - **ROJO, parte 7b.** Crear `test/java/com/confia/identity/web/SessionCookieRoutesCsrfIT.java` (**condición 3 sobre las rutas reales**: refresco y cierre
    actual, con la cookie de sesión presente, sin `X-CSRF-Token`, con CSRF de otra sesión y sin `Origin`; los seis resultados son `403 csrf-invalid` y el caso de
    uso registra cero invocaciones [WE54]), `SessionRefreshEndpointIT.java` (rota y reemite las dos cookies, el CSRF ligado al sucesor [I186]; cinco
    rechazos `401 session-expired` idénticos salvo `instance` y traza, con los dos `Set-Cookie` de borrado y la reutilización con `reuse_detected`
    [I188, WE63]; sin cookie pero con `Origin`, `401 session-expired` con borrado y `WWW-Authenticate: Bearer`, y sin `Origin` `403` [I219]; cookie de sesión
    repetida `403` [I220]; sin CSRF o sin `Origin`, `403` y cero invocaciones [I189]; el trigésimo rechazo activa `admin-session` y los cierres no cuentan
    [WE92]), `SessionEndEndpointIT.java` (cierre con el acceso vencido: `204`, `logout` y dos `Set-Cookie` de borrado [I194]; sin CSRF, `403` y la familia
    sigue viva [I199]; sin cookie, `204` sin efecto ni asiento y sin `Origin`, `403` [I227]; cerrar todas con `Bearer` no exige CSRF [WE51]; sin `Origin`, `403`
    y ninguna familia cambia [I202]; una segunda llamada con el mismo acceso da `401 token-invalid` [I201]; `DELETE` a `/api/v1/auth/sessions/current` sin
    `Origin` da `403` [WE20]), `TwoTabRefreshConcurrencyIT.java` (dos pestañas con el mismo agente: una `fresh` y otra `grace`, ambas `200`, el mismo
    sucesor y un CSRF que valida contra él, con el par de cookies de cada respuesta combinado en los cuatro órdenes [I187]),
    `RevocationProducersIT.java` (los cinco flujos dejan el motivo correspondiente) [I203]. Pruebas de rutas: `PublicRouteAllowListTest` y
    `RegisteredRoutes` (rutas registradas = públicas ∪ autenticadas ∪ restringidas, con fixtures de una restringida no declarada y de una pública que
    responde `200` a un método no declarado) [BI51, WE80]; contenido exacto de las tres clases [WE79]; lista autenticada con las dos entradas
    [WE18], una ruta fuera de las listas con principal da `403` [WE19], variantes de la ruta [WE21], ruta nueva sin editar la lista rompe la prueba [WE22],
    ruta de la lista que responde sin credencial falla la prueba [WE23], sin credencial `401 authentication-required` [WE33]; otros métodos sobre
    `/api/v1/auth/sessions`, `/refresh` y `/api/v1/auth/mfa/verifications` no se sirven [WE81]; rutas de producción exactamente las siete, y `401` en otra
    [WE85]; `ProblemCatalogCoverageTest` y `ProblemCodeTest` con los cinco códigos y siguen negados `institution-*` [BI50, WE82, WE83, WE84, WE35,
    «Cada código tiene su estado»]; `WWW-Authenticate: Bearer` en los tres `401` nuevos [WE88]; `SessionRoutesRateLimitedRuleTest` ampliada a las seis rutas
    reales [BI46]; `ProcessBeanIsolationTest` (contexto administrativo con HMAC, ayudante, controladores y tres políticas; portal y trabajador sin ninguno)
    [BI53, BI54]; `OpenApiContractSnapshotTest` con las siete operaciones, su seguridad y sus respuestas [BI24, BI59]. **Rojos esperados:** rutas
    inexistentes; `ProcessBeanIsolationTest` con `not in the allow-list` para el controlador nuevo.
  - **VERDE, 7a.** Editar `main/java/com/confia/identity/domain/{RotationDecision,RefreshRejection,RefreshTokenFamilyState}.java`
    (`decide(family, token, presenter, addressChanged, now, policy)`; `REJECT_BINDING_CHANGED`; `creationFingerprint` e `ipFirstSeen`),
    `identity/application/{RotateRefreshTokenCommand,RotateRefreshToken}.java` (`clientAddress` y `requestId`; `execute(command)` con
    `LoginInstitutionProvider`; comparación por `ClientAddress.parseLiteral`; asiento `identity.session.binding_changed` con `{"familyRevoked":true}`),
    crear `identity/application/RefreshSession.java` (`Refreshed(SessionGrant, kind)` o `Rejected`) y editar `JooqRefreshTokenFamilyRepository.stateQuery`.
    7a no registra ninguna ruta ni emite cookies.
  - **VERDE, 7b.** Crear `main/java/com/confia/identity/web/{SessionRefreshController,SessionEndController}.java` (`@RateLimited(policy =
    "admin-session")` en las tres rutas; el refresco lee `SessionCookies.read`, trata `ABSENT` y `DUPLICATED` como rechazo, emite con
    `SessionCookies.issue` también en la gracia, y ante todo rechazo borra con `SessionCookies.clear`, registra `RateLimitFailures.record("admin-session")`
    y lanza `ProblemCodeException(session-expired)` conservando los `Set-Cookie`; el cierre actual responde `204` siempre con borrado; cerrar todas usa
    `EndAllSessions`). Editar `shared/web/edge/PublicEndpoints.java` (refresco y cierre actual) y `shared/web/authentication/AuthenticatedEndpoints.java`
    (`DELETE /api/v1/auth/sessions` con `LIVE_SESSION`), `ProblemCode`, `ProblemResponses` y `problems.properties` (`session-expired`, `401`, `Bearer`),
    `ProcessBeanPolicy.java`, `apps/api/openapi/admin.openapi.json` (cookie de sesión y `X-CSRF-Token` en refresco y cierre actual) y notas fechadas en
    `docs/03-seguridad.md` §4.5 (cookie, vinculación, revocación por MFA) y ADR-0005 (motivos nuevos).
  - **Condición de cierre (condición 3, parte de las rutas reales).** La casilla de 3.3 **no se marca** sin `SessionCookieRoutesCsrfIT` en verde con los
    seis resultados `403` [WE54] y su demostración deliberada registrada. Con esto y 1.1 la condición 3 queda cerrada con referencia a [WE53] y [WE54].
  - **Demostraciones deliberadas.** (1) Poner la vinculación antes de la reutilización en `RotationDecision` pone en rojo `RotationDecisionTest`: el token
    consumido da `binding_changed` en lugar de `reuse_detected` [I225]. (2) Omitir `SessionCookies.clear` en el rechazo del refresco pone en rojo
    `SessionRefreshEndpointIT`: ningún `Set-Cookie` de borrado [I188]. (3) Deshabilitar `CsrfOriginFilter` pone en rojo `SessionCookieRoutesCsrfIT`: `200` o
    `204` en lugar de `403` [WE54]. (4) Reemitir en la gracia el CSRF ligado al refresco anterior pone en rojo `TwoTabRefreshConcurrencyIT`: un par de
    cookies incoherente en algún orden [I187]. Se revierten.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; `identity.domain` en 95 y PIT en 80 (incluido `RotationDecision`). Commits: 7a `feat(identity): reject and
    revoke a session whose agent and address both changed on refresh`; 7b `feat(identity): expose the refresh and logout endpoints with cookie CSRF
    protection`, `feat(web): add the session-expired problem code` y `docs(security): note the session cookie, the binding and the new revocation reasons`.
    Tamaño: nominal 550, realista 1 210; costura nombrada: 7a `refresh-binding` y 7b `refresh-logout-http`. — Escenarios I121, I186 a I194, I199, I201 a
    **Nota 2026-10-23 (aplicada):** partida en cuatro ramas apiladas, 07a `refresh-binding`, 07b `session-expired-code`, 07c `refresh-endpoint` y 07d `logout-endpoints`; la condición 3 sobre rutas reales quedó cerrada con `SessionCookieRoutesCsrfIT` y su demostración (ver `apply-progress.md`, tarea 3.3).
    I203, I219, I220, I225 a I227, WE18 a WE23, WE33, WE35, WE51, WE54, WE63, WE74, WE79 a WE85, WE88, WE92, «Cada código tiene su estado», BI24, BI46, BI50,
    BI51, BI53, BI54 y BI59.

---

## Fase 4: cierre y seguimientos

- [ ] 4.1 **PR 8 `closing-and-follow-ups`: F2, F3, O9, puerta de tiempo, ausencias, contratos y notas documentales.**
  - **ROJO.** Crear `test/java/com/confia/shared/security/token/ClaimReaderTest.java` (`epochSecond` −1, 0, `LAST_EPOCH_SECOND`, `LAST_EPOCH_SECOND + 1`,
    `Long.MAX_VALUE`, decimales `1.5` y `1760263800.0` y la cadena `"1760263800"`; los ocho mutantes de PIT eliminados) [I212, I213]; ampliar
    `SessionSecretsNotObservableTest.java` con el `jti` del acceso y del restringido en el mapa `secrets`, y una variante que lo expone en `toString()` que
    debe fallar [I211]; crear `test/java/com/confia/identity/web/SessionFlowRedactionIT.java` (inicio de sesión, inscripción, verificación, refresco y
    cierre con captura del registro estructurado a `TRACE`: ni contraseña, acceso, refresco, restringido, token CSRF, secreto TOTP, códigos de recuperación
    ni cabeceras `Authorization` y `Cookie` en mensaje, argumentos, MDC ni excepción; **control negativo primero** con un token CSRF registrado a
    propósito) [I209, I210] y `LoginTimingGateIT.java` (200 intentos con cuenta existente y contraseña errónea y 200 con identificador inexistente, retardo
    cero, temporizador de prueba en el materializador y política de tasa elevada por propiedades de la prueba; diferencia de medianas de 50 ms como máximo;
    variante sin la verificación señuelo que debe fallar señalando la diferencia) [I159, BI56, BI57]. Crear las pruebas de ausencia vigentes
    `IpBlockAbsenceIT` (seis identificadores distintos no bloquean a la IP) [I214], `AccountDeactivationAbsenceTest` (sin caso de uso de
    desactivación y `admin_revoke` rechazado) [I215] y `NoFullSessionMfaRoutesTest` (ninguna ruta que acepte un acceso completo para inscribir o dar de
    baja MFA ni listar sesiones) [I216], y ampliar `WebEdgeScopeExclusionInventoryTest` y `PublicRouteAllowListTest` con la ausencia de recuperación de
    contraseña por HTTP y un fixture que la añade que debe fallar [BI52]. **Rojos esperados:** ocho mutantes vivos; `jti` ausente del mapa; sin fixture de la
    puerta de tiempo.
  - **VERDE.** Editar `main/java/com/confia/shared/security/token/ClaimReader.java` si las pruebas muestran un hueco, y `SessionSecretsNotObservableTest`. Si
    el tiempo de `verify` supera 8 minutos (R-9), ver la brecha G-4 antes de mover la puerta. Regenerar `packages/contracts` con
    `pnpm --filter @confia/contracts generate` y pasar `typecheck` y `test` (el código generado no se compromete; lo comprometido es la instantánea)
    [BI26]. Notas fechadas, sin reescribir cuerpos: `docs/03-seguridad.md` §8.1 (`connect-src 'self'`, mismo origen), `docs/ui-ux/04` y `docs/ui-ux/06`
    (catálogo de errores con los cinco códigos, `mfa-attempts-exceeded` no existe, retardo sin bloqueo) y `openspec/changes/foundations-plan/exploration.md`
    (H1 cerrada con referencia a [I160] e [I161], condición 3 cerrada con referencia a [WE53] y [WE54], H3 y H4 abiertas con dueño
    `password-recovery-endpoints`, sin modificar el texto original de ninguna) [WE56, WE86]. Dejar en `apply-progress.md` el material del informe de archivo
    (condiciones cerradas, seguimientos F1a a F3 y obligaciones O2, O6, O9 y O10 con su prueba).
  - **Demostraciones deliberadas.** (1) Cambiar `<=` por `<` en el límite superior de `ClaimReader` pone en rojo `ClaimReaderTest`: `LAST_EPOCH_SECOND` se
    rechaza [I212]. (2) Registrar el token CSRF a nivel `TRACE` en `CsrfOriginFilter` pone en rojo `SessionFlowRedactionIT`: el valor sembrado aparece en la
    captura [I209]. (3) Exponer el `jti` en `AccessToken.toString()` pone en rojo `SessionSecretsNotObservableTest` [I211]. La aserción de la puerta de
    tiempo es de por sí una diferencia de medianas, no una espera ni una carrera; su control negativo es la variante sin señuelo [BI56]. Se revierten.
  - **Cierre.** `./mvnw verify -Pmutation-gate` en verde en la cabeza de la cadena; cobertura global de `app` en 80 % y `identity.domain` en 95; la duración de
    `verify` sin mutación dentro de 8 minutos [BI57]; **barrido de trazabilidad de los 216 escenarios** (sin huérfanos ni duplicados). Commits:
    `test(identity): pin the claim epoch range, redaction of a full session flow and the login timing gate` y
    `docs(sdd): record the closing of H1 and condition 3 and the session documentation notes`. Tamaño: nominal 350, realista 770; costura nombrada
    (solo si supera 800): 8a pruebas y código, 8b notas documentales. — Escenarios I159, I209 a I216, WE56, WE86, BI26, BI52, BI56 y BI57.

---

## Trazabilidad: escenarios de `specs/identity/spec.md` (103)

Los escenarios sin identificador se citan por su título. Cada escenario tiene exactamente una tarea dueña. Cuando un escenario se afirma primero a
nivel de caso de uso y después por HTTP, la dueña es la tarea de la aserción principal; la otra solo lo vuelve a ejecutar sin cambios. Los escenarios
documentales (I185, WE56, WE86) se verifican por lectura del diff, declarada como revisión humana.

| Escenario | Tarea |
|---|---|
| I149 Credenciales correctas sin segundo factor emiten sesión y cookies | 3.1 |
| I150 Cuenta con segundo factor verificado recibe token restringido y ninguna cookie | 3.1 |
| I151 Cuenta sin secreto recibe un token de inscripción | 3.1 |
| I152 Un rechazo responde `401 authentication-failed` sin cookies ni token | 3.1 |
| I153 Validación del cuerpo | 3.1 |
| I154 Tipo de contenido distinto de JSON | 3.1 |
| I155 La institución del cuerpo se ignora | 3.1 |
| I228 El inicio de sesión no exige CSRF y sobrescribe una cookie anterior | 3.1 |
| I156 El retardo del retroceso se materializa después de confirmar y no deniega | 3.1 |
| I157 Igualdad de la respuesta | 3.1 |
| I158 Igualdad con retardo | 3.1 |
| I159 Puerta de tiempo de 200 más 200 intentos | 4.1 |
| I160 Diez peticiones por minuto y la undécima recibe `429` | 3.1 |
| I161 Tras los fallos de la IP, `identity_login_backoff` deja de crecer | 3.1 |
| I162 Solo `Rejected` cuenta como fallo de la IP | 3.1 |
| I163 Una petición rechazada por `Origin` no consume cupo | 3.1 |
| I164 El quinto identificador distinto dispara la señal | 3.1 |
| I165 Cuatro identificadores o cinco fallos del mismo no la disparan | 3.1 |
| I166 El borde de los diez minutos es estricto y la señal no bloquea | 3.1 |
| I224 El sexto identificador y la repetición de una huella no vuelven a señalar | 3.1 |
| I167 Una credencial recién inscrita no está verificada | 3.2 |
| I168 Un código TOTP aceptado verifica la credencial | 3.2 |
| I169 Un código de recuperación no verifica | 3.2 |
| I170 Código TOTP válido emite la sesión completa | 3.2 |
| I171 Código de recuperación válido emite la sesión y queda consumido | 3.2 |
| I172 Código incorrecto | 3.2 |
| I173 Cuerpo con ambos códigos o con ninguno | 3.2 |
| I174 Token vencido o de acceso | 3.2 |
| I221 Con `mfa-enroll` un código de recuperación no activa la credencial | 3.2 |
| I222 Con la credencial verificada, `mfa-enroll` recibe `second-factor-invalid` | 3.2 |
| I223 Un código de recuperación incorrecto suma al retroceso TOTP | 3.2 |
| I175 Un código reutilizado en la misma ventana se rechaza | 3.2 |
| I176 Activación con dos sesiones previas | 3.2 |
| I177 Una verificación posterior no revoca | 3.2 |
| I178 La activación es atómica | 3.2 |
| I179 Activación sin otras sesiones y entre instituciones | 3.2 |
| I180 Primera inscripción | 3.2 |
| I181 Reinscripción mientras no se verificó | 3.2 |
| I182 Credencial verificada responde `409` | 3.2 |
| I183 Dos inscripciones concurrentes | 3.2 |
| I184 Un token `mfa-verify` no inscribe | 3.2 |
| I185 Riesgo declarado de la ventana de primera inscripción (documental) | 3.2 |
| I186 Refresco normal | 3.3 |
| I187 Refresco en la gracia desde dos pestañas | 3.3 |
| I188 Todo rechazo es `session-expired` uniforme y borra las cookies | 3.3 |
| I189 Con cookie y sin cabecera CSRF, o sin `Origin` | 3.3 |
| I219 Sin cookie de sesión y con `Origin`, el refresco es `401 session-expired` | 3.3 |
| I220 Dos cookies de sesión repetidas se rechazan sin elegir una | 3.3 |
| I190 Cambian agente e IP | 3.3 |
| I191 Cambia solo uno | 3.3 |
| I192 Familia sin IP o sin agente registrados | 3.3 |
| I193 Solo se revoca la familia afectada | 3.3 |
| I225 Orden de decisión: reutilización, vinculación, vencimiento del token | 3.3 |
| I226 La dirección se compara como dirección | 3.3 |
| I194 Cierre con el acceso vencido | 3.3 |
| I195 Un refresco ya consumido también cierra la familia | 2.1 |
| I196 Uniformidad ante refresco desconocido o familia revocada | 2.1 |
| I197 Solo la sesión actual | 2.1 |
| I198 Cierre y rotación concurrentes | 2.1 |
| I199 Con cookie de sesión y sin cabecera CSRF no hay cierre | 3.3 |
| I227 Sin cookie de sesión el cierre responde `204` | 3.3 |
| I200 Cierra el cien por ciento de las familias vivas y ninguna ajena | 2.1 |
| I201 Una segunda llamada con el mismo acceso falla | 3.3 |
| I202 Cerrar todas sin `Origin` permitido | 3.3 |
| I203 Cada motivo tiene un productor probado | 3.3 |
| I204 Un motivo sin productor se rechaza | 2.1 |
| I205 El comando lleva la IP y el agente de la petición | 3.1 |
| I206 Comando sin origen | 3.1 |
| I207 Ningún refresco ni acceso de la cuenta sirve tras restablecer | 2.1 |
| I208 Revocar en A no toca B | 2.1 |
| I209 El flujo completo no deja secretos | 4.1 |
| I210 El control negativo detecta una fuga | 4.1 |
| I211 El `jti` forma parte de los secretos vigilados | 4.1 |
| I212 Los bordes del rango de `epochSecond` | 4.1 |
| I213 Decimales y cadenas | 4.1 |
| I214 Seis identificadores distintos no bloquean a la IP | 4.1 |
| I215 Desactivar una cuenta no existe y `admin_revoke` se rechaza | 4.1 |
| I216 Ninguna ruta de inscripción con sesión completa ni de listado de sesiones | 4.1 |
| MFA: cuenta con `mfa_required` y secreto TOTP ya inscrito (verificado) | 3.2 |
| MFA: cuenta con `mfa_required` sin ningún secreto TOTP inscrito | 3.2 |
| I217 Cuenta con secreto inscrito pero nunca verificado | 3.2 |
| Restablecimiento: sin segundo factor no hay restablecimiento, y con un código TOTP válido sí | 3.2 |
| Restablecimiento: un código de recuperación sustituye al TOTP y queda consumido | 3.2 |
| Restablecimiento: `mfa_required` sin secreto restablece solo con el enlace | 3.2 |
| Restablecimiento: `mfa_required` en `false` con secreto inscrito no presenta segundo factor | 3.2 |
| I218 Una credencial nunca verificada restablece solo con el enlace | 3.2 |
| I87 Los valores de la emisión | 3.1 |
| I88 El valor almacenado no es el entregado | 3.1 |
| I89 La emisión y su asiento se confirman o revierten juntos | 3.1 |
| I90 El asiento de emisión existe y no contiene secretos | 3.1 |
| I74 Claims y vida del token restringido | 3.1 |
| I75 Emitir un token restringido no crea ninguna familia | 3.1 |
| I76 Los dos tipos de token no son intercambiables | 3.2 |
| I77 El uso del token restringido pertenece a un conjunto cerrado | 3.2 |
| I119 Solo se guarda la huella y la IP inicial | 3.1 |
| I120 Una emisión sin origen se acepta | 3.1 |
| I121 El cambio de solo el agente o de solo la IP no impide rotar | 3.3 |
| I129 Token válido y sesión viva | 3.2 |
| I130 La lista de campos es cerrada y sin datos personales | 3.2 |
| I131 Sin credencial | 3.2 |
| I132 Token vencido, alterado o restringido | 3.2 |
| I133 Sesión revocada o desconocida con un token vigente | 3.2 |
| I134 La ruta es de solo lectura | 3.2 |

## Trazabilidad: escenarios de `specs/web-edge/spec.md` (74)

| Escenario | Tarea |
|---|---|
| WE43 Sin `Origin` en un método no seguro | 1.1 |
| WE44 `Origin` no permitido, parecido o repetido | 1.1 |
| WE45 `Origin` permitido y métodos seguros | 1.1 |
| WE46 Lista vacía o inválida | 1.1 |
| WE47 Cookie sin cabecera `X-CSRF-Token` | 1.1 |
| WE48 Token CSRF de otra sesión | 1.1 |
| WE49 Cabecera distinta de la cookie, HMAC alterado o aleatorio alterado | 1.1 |
| WE50 Token válido | 1.1 |
| WE89 Una ruta de cookie sin cookie de sesión no exige CSRF, pero sí `Origin` | 1.1 |
| WE90 Cookie de sesión repetida, cookie CSRF repetida o cabecera repetida | 1.1 |
| WE91 Un token emitido con la clave anterior se acepta y se reemite con la actual | 1.1 |
| WE51 Una ruta `Bearer` no exige el token CSRF | 3.3 |
| WE52 Atributos de la cookie CSRF | 1.3 |
| WE53 Con cookie y sin cabecera, `403` (arnés) | 1.1 |
| WE54 Las mismas tres pruebas contra las rutas reales | 3.3 |
| WE55 El arnés se desactiva y el mecanismo falla la prueba | 1.1 |
| WE56 Cierre de la condición 3 (documental) | 4.1 |
| WE57 Orden de los filtros | 1.1 |
| WE58 El rechazo no consume cupo ni verifica el token | 1.1 |
| WE59 Administración sin clave o con clave corta no arranca | 1.1 |
| WE60 El portal y el trabajador no la tienen | 1.1 |
| WE61 Ninguna clave CSRF versionada | 1.1 |
| WE62 Atributos de la cookie de sesión | 3.1 |
| WE63 Borrado de las dos cookies | 3.3 |
| WE64 Las cookies son `Secure` y con prefijo en todos los perfiles | 3.1 |
| WE65 Ninguna otra respuesta emite cookies | 1.3 |
| WE66 El token restringido no puebla el actor de sesión | 3.2 |
| WE67 Cada token abre solo lo suyo | 3.2 |
| WE68 Una ruta restringida nueva sin editar la lista rompe la prueba | 3.2 |
| WE69 Un token restringido no autoriza nada fuera de su ruta | 3.2 |
| WE70 Valores por defecto de las tres políticas | 1.3 |
| WE71 Los contadores de las políticas son independientes | 1.3 |
| WE72 El décimo fallo del segundo factor activa la restricción de `admin-mfa` | 3.2 |
| WE92 El trigésimo rechazo del refresco activa la restricción de `admin-session` | 3.3 |
| WE93 Un componente de política ausente o desconocido detiene el arranque | 1.3 |
| WE73 Valor no positivo en una política nueva | 1.3 |
| WE74 Cada ruta aplica su política | 3.3 |
| WE75 Un retardo de 900 segundos espera el tope | 1.3 |
| WE76 Un retardo menor que el tope no se acorta | 1.3 |
| WE77 Tope no válido | 1.3 |
| WE78 Propiedad desconocida | 1.3 |
| WE79 Contenido exacto de las tres clases | 3.3 |
| WE80 Una ruta de la lista pública que responde a un método no declarado falla la prueba | 3.3 |
| WE81 Otro método sobre una ruta de sesión no se sirve | 3.3 |
| WE82 Cada código tiene su estado | 3.3 |
| WE83 Los textos no nombran la causa | 3.3 |
| WE84 El catálogo exige los cinco y `institution-*` sigue sin existir | 3.3 |
| WE85 Las rutas de producción son exactamente las siete de sesión | 3.3 |
| WE86 Cierre de H1 (documental) | 4.1 |
| WE09 El token restringido no abre ninguna ruta autenticada | 3.2 |
| WE10 El token del portal se rechaza en rutas autenticadas y restringidas | 3.2 |
| Ninguna respuesta crea sesión | 3.1 |
| WE87 Las cookies de los endpoints de sesión no crean una sesión HTTP | 3.1 |
| WE11 Sin credencial | 3.1 |
| WE12 Credencial rechazada | 3.1 |
| WE13 El portal y la documentación con perfil `prod` también la llevan | 3.1 |
| WE41 Un `401` del traductor de errores también la lleva | 3.1 |
| WE14 Las demás respuestas no la llevan | 3.1 |
| WE88 Los tres códigos `401` del flujo de sesión llevan `Bearer` | 3.3 |
| Cada código tiene su estado (diecinueve códigos) | 3.3 |
| Tipo de contenido no admitido | 3.1 |
| Recurso inexistente bajo un prefijo de documentación | 1.1 |
| Un código desconocido no se filtra | 1.1 |
| WE35 Los códigos de token y de sesión existen y los de institución no | 3.3 |
| WE18 La lista contiene solo las dos rutas de sesión | 3.3 |
| WE19 Una ruta registrada fuera de las listas recibe `403` con un principal | 3.3 |
| WE20 Otro método sobre una ruta autenticada también es `403` | 3.3 |
| WE21 Las variantes de la ruta autenticada no eluden la autenticación | 3.3 |
| WE22 Una ruta autenticada nueva sin editar la lista rompe la prueba | 3.3 |
| WE23 Una ruta de la lista que responde sin credencial falla la prueba | 3.3 |
| WE33 Una ruta de la lista autenticada sin credencial | 3.3 |
| Origen: asiento durante una petición | 3.1 |
| Origen: asiento fuera de una petición | 3.1 |
| Origen: peticiones concurrentes con orígenes distintos | 3.1 |

## Trazabilidad: escenarios de `specs/build-integrity/spec.md` (33)

| Escenario | Tarea |
|---|---|
| BI39 El catálogo tiene exactamente los cinco motivos | 2.1 |
| BI40 La base y la enumeración coinciden | 2.1 |
| BI41 `V9` no cambia nada más | 2.1 |
| BI42 Las filas existentes sobreviven a la migración | 2.1 |
| BI43 Fixture con un asiento armado por concatenación | 1.2 |
| BI44 El código real no concatena | 1.2 |
| BI45 Fixture con un manejador de inicio de sesión sin política | 3.1 |
| BI46 El código real declara la política correcta en las seis rutas | 3.3 |
| BI47 Fixture con un controlador que escribe una cookie | 1.3 |
| BI48 El HMAC de CSRF no está en `shared.web` | 1.3 |
| BI49 Cookie, limitador y materializador solo en los lugares permitidos | 3.1 |
| BI50 El catálogo exige los cinco códigos y sigue negando los de institución | 3.3 |
| BI51 El enumerador distingue tres clases de ruta | 3.3 |
| BI52 La ausencia de recuperación por HTTP se conserva | 4.1 |
| BI53 El proceso administrativo registra el cableado nuevo | 3.3 |
| BI54 El portal y el trabajador no contienen nada de sesión nuevo | 3.3 |
| BI55 Un bean de sesión nuevo sin línea rompe la construcción | 1.1 |
| BI56 La puerta falla ante una diferencia mayor | 4.1 |
| BI57 La puerta pasa con el código real dentro del presupuesto | 4.1 |
| BI58 Arranque del proceso administrativo con clave CSRF generada | 1.1 |
| BI01 Las puertas genéricas de esquema pasan sobre cada tabla nueva | 2.1 |
| BI02 Una institución no lee las familias ni los tokens de otra | 2.1 |
| BI03 Sin contexto de institución la consulta devuelve cero filas | 2.1 |
| BI04 El catálogo rechaza un hash que no es de la longitud de un SHA-256 | 2.1 |
| BI05 El catálogo de motivos es cerrado y no incluye `admin_revoke` | 2.1 |
| BI06 Un token no puede apuntar a una familia de otra institución | 2.1 |
| BI36 Como máximo un token sin consumir por familia y un predecesor por token | 2.1 |
| BI37 Consumo coherente y sucesor cifrado con prefijo de versión | 2.1 |
| BI24 Las operaciones y sus respuestas coinciden con la instantánea | 3.3 |
| BI25 Una respuesta genérica no aprobada rompe la construcción | 3.1 |
| BI26 El esquema de error declara `errors` y el contrato se regenera sin diferencias | 4.1 |
| BI27 La instantánea del portal sigue vacía | 3.1 |
| BI59 Seguridad y respuestas de las operaciones de cookie y de segundo factor | 3.3 |

## Trazabilidad: escenarios de `specs/audit-trail/spec.md` (6)

| Escenario | Tarea |
|---|---|
| AT01 Igualdad byte a byte en los sitios migrados | 1.2 |
| AT02 Un valor con caracteres especiales se escapa | 1.2 |
| AT03 El orden de los miembros es el de inserción y es determinista | 1.2 |
| AT04 La cadena verifica tras la migración | 1.2 |
| AT05 Un valor que no es un tipo admitido se rechaza | 1.2 |
| AT06 Nombres de miembro inválidos o repetidos | 1.2 |

## Resumen de cobertura

| Tarea | Escenarios | Tarea | Escenarios |
|---|---|---|---|
| 1.1 | 22 | 3.1 | 46 |
| 1.2 | 8 | 3.2 | 45 |
| 1.3 | 12 | 3.3 | 48 |
| 2.1 | 20 | 4.1 | 15 |
| | | **Total** | **216 de 216** |

Por especificación: `identity` 103 (1.1: 0, 2.1: 8, 3.1: 29, 3.2: 38, 3.3: 19, 4.1: 9), `web-edge` 74 (1.1: 20, 1.3: 10, 3.1: 13, 3.2: 7, 3.3: 22,
4.1: 2), `build-integrity` 33 (1.1: 2, 1.2: 2, 1.3: 2, 2.1: 12, 3.1: 4, 3.3: 7, 4.1: 4) y `audit-trail` 6 (1.2: 6). Sin huérfanos y sin duplicados.

## Brechas de especificación y diseño detectadas

- **G-1. Pruebas sin nombre en el diseño §8.** BI43 y BI44 (regla contra el JSON armado por concatenación o interpolación) y BI45 y BI46 (regla de
  política de tasa por manejador) no tienen clase de prueba en la tabla de pruebas. Este documento las nombra: `AuditJsonOnlySourceTest` (ampliada a
  `String.format`, bloques de texto y `"{" +`, porque el diseño §3.17 solo busca el literal `"{\""` y la especificación habla de «concatenación o
  interpolación») y `SessionRoutesRateLimitedRuleTest` (empieza con el manejador de inicio de sesión en 3.1 y llega a las seis rutas en 3.3). Conviene
  confirmarlo con el diseño en la fase de aplicación.
- **G-2. Inversión de las ausencias en cada PR, no en 8.** El diseño §3.20 coloca el estrechamiento de WE29 a WE31 en la rebanada 8, pero esos inventarios
  fallan en el primer PR que registra una ruta de sesión (3.1b). Este documento los invierte en el PR que rompe cada ausencia (cookie en 1.3, limitador y
  materializador en 3.1, códigos en 1.1, 3.1, 3.2 y 3.3, enumerador de rutas en 3.1 a 3.3) y deja en 4.1 solo lo residual (BI52).
- **G-3. Lista restringida de producción.** Si 3.2a llenara `secondFactorEndpoints()` sin controladores, la regla «toda entrada corresponde a una ruta
  registrada» fallaría. La lista de producción queda vacía en 3.2a y se llena en 3.2b, el mismo patrón de S1 con la lista autenticada.
- **G-4. Puerta de tiempo: contradicción entre la especificación y el diseño.** La especificación (BI57) exige que la puerta corra en `./mvnw verify`
  dentro de 8 minutos; el diseño (R-9) permite moverla al perfil de mutación «con decisión registrada» si `verify` supera el presupuesto. Mover la puerta
  incumpliría BI57. Se mide la duración en 3.1 y en 4.1 (hoy 5 min 24 s sin mutación, más unas 400 verificaciones Argon2id); si no cabe, hace falta una
  decisión del propietario que reescriba BI57 antes de mover la puerta.
- **G-5. Perfil `prod` en WE64.** El escenario arranca el proceso administrativo con `local`, `preprod` y `prod` en un mismo IT; el perfil `prod` puede
  exigir propiedades adicionales (Swagger apagado, proxies). `TestProcessArguments` debe cubrirlas; si no basta, se parametriza la prueba por perfil.
- **G-6. Tamaño real.** El historial de S1 (1,5 a 3,8 veces el realista) sugiere que 3.1, 3.2 y 3.3 pidan tres o más PR cada una. S1 contó cada parte como
  tarea y superó las 15 al aplicar. Si ocurre, el punto de partición natural en dos cambios está descrito en el informe de la fase (DA-1).

## Riesgos de la cadena

- **Tamaño.** Cuatro tareas superan 800 realistas (2.1, 3.1, 3.2 y 3.3) y 1.1 y 4.1 rondan 730 y 770. La medición decide y se parte por la costura nombrada.
- **Orden de la condición 3.** El único riesgo de orden irreversible es fusionar 1.3, 3.1 o 3.3 antes que 1.1. El PR 1 no contiene `Set-Cookie`, y 1.3 solo
  crea el ayudante.
- **H1.** Revertir el limitador sin revertir el inicio de sesión reabre H1: 3.1b se revierte entero.
- **Concurrencia.** Seis pruebas con dos transacciones reales (cierre, cerrar todas, inscripción, dos inscripciones, dos pestañas y la primera verificación);
  todas con `CountDownLatch` y `pg_stat_activity`, sin esperas por reloj.
- **`verify`.** 5 min 24 s hoy contra 8 min de tope; la puerta de tiempo añade unas 400 verificaciones Argon2id (ver G-4).
- **Jackson 3.** La sonda S-5 es el primer rojo de 1.3 y puede obligar a un `JsonMapperBuilderCustomizer`.
- **Ventana de primera inscripción.** Quien conozca la contraseña puede reemplazar el secreto hasta la primera verificación (DA-15, DA-7 *c*); queda auditada
  y se revisa en el cambio 8.

> **Nota fechada 2026-10-22 (tarea 3.1 en seis ramas apiladas 05a a 05f).** 05a `failure-spread-window` (572), 05b `login-views-and-event-log` (551), 05c `login-use-case` (788), 05d `authentication-failed-problem-code` (174), 05e `login-controller-and-h1` (1 469: indivisible por la condición H1, controlador, limitador, regla, prueba de H1, instantánea OpenAPI y inventarios deben ir juntos; requiere `size:exception`) y 05f `login-endpoint-behaviour` (742). Desviaciones: la seña de cinco cuentas usa `shared.observability.events.SecurityEventLog` porque `identity` no puede depender de slf4j; `LogIn` rechaza sin tocar tablas un identificador o contraseña que la normalización deja inválidos (antes habría sido un 500). G-4: ver `apply-progress.md`.

> **Nota fechada 2026-10-23 (tarea 3.2 en doce ramas apiladas 06a a 06l).** La tarea completa superaba 4 000 líneas efectivas frente a las 1 430 de la estimación realista; se partió por costuras de código completo: 06a y 06b (clase de ruta restringida), 06c a 06e (credencial verificada e inscripción repetible), 06f a 06h (finalización del segundo factor y su concurrencia), 06i (códigos de problema), 06j y 06k (las dos rutas, cada una con su limitador `admin-mfa`) y 06l (documentación). Desviaciones y evidencia en `apply-progress.md`.

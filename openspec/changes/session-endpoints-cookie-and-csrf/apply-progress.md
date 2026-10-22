# Progreso de aplicación: endpoints de sesión, cookie y CSRF (parte 4b, S2)

- **Cambio:** `session-endpoints-cookie-and-csrf` (F0, cambio 7, parte 4b, segundo cambio de tres)
- **Modo:** TDD estricto (`./mvnw verify -Pmutation-gate` en `apps/api`, JDK 25, Docker en ejecución)
- **Estrategia de entrega:** `stacked-to-main`, tope de 800 líneas efectivas por PR (DA-1 *a*)
- **Última actualización:** 2026-10-21 (tarea 1.3 completa: rebanadas 03a, 03b y 03c)
- **Regla de este archivo:** solo se añade; no se reescriben secciones existentes.

## Estado de las tareas

| Tarea | PR | Estado | Commits |
|---|---|---|---|
| 1.1 | PR 1 `csrf-and-origin-protection`, partido en 01a, 01b y 01c (ver «Tarea 1.1: medición y partición») | Completa y verificada | 01a: `2cce1be`; 01b: `0bfeb27`; 01c: ver el historial de la rama |
| 1.2 | PR 2 `audit-json-serializer`, partido en 02a y 02b (ver «Tarea 1.2») | Completa y verificada | 02a: `bf2778d`; 02b: ver el historial de la rama |
| 1.3 | PR 3 `edge-policies-and-delay-cap`, partido en 03a, 03b y 03c (ver «Tarea 1.3») | Completa y verificada | 03a: `dc6025b`; 03b: `fa04cee`; 03c: ver el historial de la rama |

## Tarea 1.1: medición y partición

Construida completa en el árbol (rama temporal local `tmp-csrf-all`, nunca publicada) y medida con
`git diff --numstat main...HEAD -- . ':!openspec'`: **2 489 líneas efectivas** (2 438 añadidas, 51 borradas), frente a
730 de la estimación realista. Supera 800, de modo que se parte por archivos completos, sin consultar, en la costura que
la tarea nombra (`csrf-token-and-keys` / `csrf-origin-filter`), y la segunda mitad se parte otra vez por una costura
natural, porque aun así quedaba por encima de 800 (ver «Riesgos» del informe final).

| Rama | Contenido | Líneas efectivas |
|---|---|---|
| `...-01a-csrf-token-and-keys` | `CsrfTokens`, `CsrfKeys`, `CsrfConfiguration`, guardián de colocación, `@Import`, línea de `ProcessBeanPolicy`, clave de prueba, PIT, pruebas de token, de claves y de arranque | 1 049 |
| `...-01b-allowed-origins-and-csrf-code` | `AllowedOrigins`, `confia.web.allowed-origins`, código `csrf-invalid` y su catálogo es-HN, escaneo de claves (WE61), notas de `docs/03` y `docs/05` | 457 |
| `...-01c-csrf-origin-filter` | `CsrfOriginFilter`, `CookieCredentialRoute`, cadena administrativa, arnés de cookie, orden de filtros, condición 3 con sus demostraciones | 457 |

## Rebanada 01a `csrf-token-and-keys`

### Red de seguridad

Línea base: `main` en `bcbaa96`. Los archivos de producción existentes que se tocan son tres (`AdminApplication`, `SigningKeyPlacementGuard`
y `app/pom.xml`); sus pruebas existentes corren sin cambios en el `verify` completo de cierre.

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (compilación) | `./mvnw -q -o -pl app -am test-compile` con las pruebas escritas y **sin** `shared/security/csrf` en `src/main` (el directorio se apartó y se restituyó) | `exit=1`, 248 líneas `ERROR`: `cannot find symbol` para `CsrfTokens` (24), `CsrfKeys` (5 clases y 29 variables) en `CsrfKeyStartupTest`, `CsrfTokensTest`, `CsrfTokensProperties`, `CsrfKeysTest` y `CsrfKeyFixtures`. Causa: clases inexistentes, la esperada |
| ROJO (comportamiento) | `./mvnw -o -pl app -am verify -DskipITs -Dtest='CsrfTokensTest,CsrfTokensProperties,CsrfKeysTest,CsrfKeyStartupTest'` con `CsrfTokens`/`CsrfKeys`/`CsrfConfiguration` presentes pero **sin** `@Import`, sin guardián y sin línea de política | `CsrfKeyStartupTest`: 13 pruebas, **10 fallos y 1 error**. Fallan la administración sin clave, con clave de 16 bytes, con Base64 inválido, con anterior igual y con anterior inválida (el proceso arranca en vez de detenerse: «arranque sin fallo»); portal y trabajador no abortan con ninguna de las dos propiedades (4 casos); y `NoSuchBeanDefinition` para `CsrfTokens` en el contexto administrativo. Pasan las 3 pruebas que no dependen del cableado |
| ROJO (lista de permitidos) | `ProcessBeanIsolationTest` con el `@Import` ya puesto y sin la línea de política | **No falló** (13 pruebas en verde). Desviación del rojo esperado en la tarea (`not in the allow-list`): la lista de permitidos coincide por prefijo de paquete y `com.confia.shared.security` ya cubre `...security.csrf`. La línea exacta de `com.confia.shared.security.csrf` se añadió igualmente (como la del anillo de firma, para que la comprobación de no vacuidad falle si se pierde el `@Import`) y las entradas prohibidas de portal y trabajador sí son nuevas |
| VERDE | Mismas pruebas con el cableado | `CsrfTokensTest` 30, `CsrfTokensProperties` 3, `CsrfKeysTest` 14, `CsrfKeyStartupTest` 13, `ProcessBeanIsolationTest` 13: 0 fallos |
| Refactor | `verifyDoubleSubmit` (comparación cookie-cabecera en tiempo constante) vive en `CsrfTokens`, porque `MessageDigest` está confinado a `shared.security` | Sin cambio de comportamiento |

### Cierre

`./mvnw -o verify -Pmutation-gate` completo sobre la rama 01a, `BUILD SUCCESS` en 25 min 24 s:
Surefire 186 (núcleo) + 1 940, Failsafe 420, 0 fallos. PIT sobre las clases mutadas (incluye `shared.security.csrf.*`):
631 de 673 mutantes muertos (94 %), fuerza de prueba 95 %, cobertura de líneas de las clases mutadas 895 de 925 (97 %).
El escaneo de secretos (WE61) llega en 01b; en esta rama ninguna clave se versiona: `TestProcessArguments` genera la clave en cada ejecución.

## Rebanada 01b `allowed-origins-and-csrf-code`

Apilada sobre 01a. **457 líneas efectivas** (452 añadidas, 5 borradas).

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (compilación) | `./mvnw -q -o -pl app -am test-compile` con las pruebas de 01b y de 01c escritas y sin `AllowedOrigins`, `CsrfOriginFilter` ni `CookieCredentialRoute` | `exit=1`; `cannot find symbol` para `AllowedOrigins` (5 clases y 9 variables), `CsrfOriginFilter` (5), `CookieCredentialRoute` (5) y el método `allowedOrigins()` de `WebEdgeProperties` (4). Para esta rebanada, las causas son `AllowedOrigins` y `allowedOrigins()` |
| VERDE | `-Dtest='AllowedOriginsTest,WebEdgePropertiesTest,ProblemCodeTest,ProblemCatalogCoverageTest,CsrfKeyNotVersionedTest'` (con el resto de la cadena) | 0 fallos: `AllowedOriginsTest` 32, `WebEdgePropertiesTest` 29, `ProblemCodeTest` 54, `ProblemCatalogCoverageTest` 12, `CsrfKeyNotVersionedTest` 3 |
| Triangulación | Cada entrada inválida de WE46 se prueba con su posición y sin su valor; el escaneo de claves (WE61) tiene su propia prueba de detección y de ausencia de falsos positivos | Ver las pruebas |
| Control negativo | `ProblemCatalogCoverageTest.theCsrfCodeHasItsOwnEntries...` quita las dos entradas es-HN y exige que la cobertura las nombre | Verde |

Nota de cobertura: `WebEdgePropertiesTest` ya recorría el repositorio con `Files.walk` y tarda unos 150 s por entrar en `node_modules`;
`CsrfKeyNotVersionedTest` usa `walkFileTree` y salta los directorios excluidos sin entrar (2 s).

### Cierre

`./mvnw -o verify -Pmutation-gate`, `BUILD SUCCESS` en 21 min 8 s: Surefire 186 + 1 982, Failsafe 420, 0 fallos. PIT: 631 de 673 mutantes
muertos (94 %), fuerza de prueba 95 %. El escaneo de secretos (`CsrfKeyNotVersionedTest`) no halla ninguna clave versionada [WE61].

## Rebanada 01c `csrf-origin-filter` (cierra la tarea 1.1)

Apilada sobre 01b. **983 líneas efectivas** (942 añadidas, 41 borradas); por encima de 800 por el arnés de cookie, sus 32 pruebas de
condición 3 y la edición de las pruebas existentes que enviaban métodos no seguros sin `Origin` (ahora lo envían; ver «Pruebas existentes tocadas»).

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (compilación) | El mismo `test-compile` de 01b: sin `CsrfOriginFilter` (5) ni `CookieCredentialRoute` (5) | `cannot find symbol`; causa esperada: clases inexistentes |
| ROJO (comportamiento, condición 3) | `CsrfOriginFilterTest.theControlVariantWithTheFilterDisabledFailsTheConditionThreeAssertions` arranca el arnés con `harness.csrf-filter=off` y exige que las tres aserciones de la condición fallen | Verde por construcción: con el filtro fuera, las aserciones fallan (`AssertionError`), de modo que la condición no pasa de vacío [WE55]. Contra el filtro real las mismas aserciones pasan [WE53] |
| VERDE | `-Dtest='CsrfOriginFilterTest,AdminFilterOrderTest,...'` | `CsrfOriginFilterTest` 32, `AdminFilterOrderTest` 4: 0 fallos |
| Orden de filtros | `AdminFilterOrderTest` lee `SecurityFilterChain.getFilters()` del proceso administrativo real (sin base de datos) y del arnés | `CsrfOriginFilter` antes de `AccessTokenAuthenticationFilter` en ambos [WE57] |
| Rechazo sin efectos | Un `POST` con `Bearer` válido y `Origin` ajeno o ausente: el doble de la vigencia de sesión no se invoca; 25 rechazos no gastan cupo de `admin-login` y los 9 siguientes pasan [WE58] | Verde |

### Demostraciones deliberadas (condición 3 y orden)

Cada una se aplicó sobre una copia guardada (`pre-break-N.java` en el directorio de la sesión), se ejecutó
`-Dtest='CsrfOriginFilterTest,AdminFilterOrderTest,CsrfTokensTest'`, se revirtió y se confirmó con `cmp` que el archivo quedó idéntico a la copia.
**No queda ninguna ruptura en el árbol.**

| # | Ruptura | Resultado observado | Revertida |
|---|---|---|---|
| 1 | `CsrfTokens.hashOf` ignora el refresco (`Digests.sha256(new byte[0])`), de modo que un token de otra sesión valida [WE48] | `CsrfOriginFilterTest.aValidTokenOfAnotherSessionIsRefused` (`expected: 403 but was: 200`) y `conditionThreeTheThreeAttacksOnTheCookieHarnessAreRefused` (línea 82, el caso del token de otra sesión); además 6 fallos de `CsrfTokensTest` (el MAC ya no es el de la fórmula, `aTokenBoundToAnotherRefreshTokenIsRejected`, `theDoubleSubmit...`) | Sí, `cmp` idéntico |
| 2 | Se quita la regla de `Origin` del filtro (`if (false)` en lugar de la comprobación) [WE43] | `CsrfOriginFilterTest`: 9 fallos de 32, entre ellos `anUnsafeMethodWithoutOriginIsRefused` (`expected: 403 but was: 200`), `aForeignLookalikeNullOrInsecureOriginIsRefusedWithIdenticalAnswers`, `anEmptyAllowedOriginsListRefusesEveryUnsafeRequest` y `conditionThree...` (línea 86, el caso sin `Origin`); `AdminFilterOrderTest`: 2 fallos | Sí, `cmp` idéntico |
| 3 | `addFilterAfter` en lugar de `addFilterBefore` para el filtro CSRF [WE58] | `AdminFilterOrderTest`: 3 de 4 fallos. Los dos de orden (`inTheRealAdministrativeProcess...` y `inTheHarnessChain...`) y `aRequestRefusedForItsOriginVerifiesNoTokenAndReadsNoSession` (línea 59: el doble de la vigencia registra 2 invocaciones en lugar de 0, con `Origin` ajeno) | Sí, `cmp` idéntico |

### Condición 3, parte del arnés

(a) `CsrfOriginFilterTest.conditionThreeTheThreeAttacksOnTheCookieHarnessAreRefused` en verde contra la cadena real: cookie sin cabecera, token de otra sesión
y sin `Origin`, los tres `403 csrf-invalid` y cero invocaciones [WE53]. (b) Su variante con el filtro deshabilitado falla [WE55]. (c) Las tres demostraciones, arriba.
(d) `rg -n "ResponseCookie|Set-Cookie|addCookie" apps/api/app/src/main`: **sin resultados** (código de salida 1). `ResponseCookie` solo existe en
`src/test` (`CookieHarnessController` y menciones en pruebas existentes). La parte de las rutas reales [WE54] se cierra en 3.3b.

### Pruebas existentes tocadas

Con el filtro en la cadena, todo método no seguro sin `Origin` recibe `403` antes que cualquier otra respuesta. Las pruebas que enviaban `POST` a la cadena
administrativa del arnés o del proceso real y esperaban el `401`/`403` de la cadena ahora envían `Origin` permitido (`AdminSecurityChainTest`,
`AccessTokenAuthenticationFilterTest`, `CurrentInstitutionFromTokenTest`, `ProblemTranslationTest`, `SensitiveDataLoggingTest`, `CurrentSessionEndpointIT`);
la semántica comprobada no cambia. `HarnessProcess` fija por omisión una clave CSRF generada y `confia.web.allowed-origins`.

### Desviaciones

- El filtro lee la cabecera `Cookie` sin `jakarta.servlet.http.Cookie` (la prueba de inventario vigente prohíbe esa clase en producción y su inversión es de 1.3).
- `ProblemCodeException` (nombrado en la tarea) no existe en el árbol; no hizo falta: el filtro escribe con `ProblemResponses.write`.
- `AdminSecurityConfiguration.addAuthenticationFilters` es público y estático para que la cadena real y el arnés compartan el orden probado.

### Cierre

`./mvnw -o verify -Pmutation-gate` completo sobre 01c (árbol final de la tarea 1.1), `BUILD SUCCESS` en 26 min 56 s: Surefire 186 (núcleo) + 2 018, Failsafe 420, 0 fallos. PIT: 631 de 673 mutantes muertos (94 %), fuerza de prueba 95 %, cobertura de líneas de las clases mutadas 895 de 925 (97 %). Escaneo de secretos: sin claves versionadas [WE61].

Estado final: tarea 1.1 marcada `[x]` en `tasks.md` (casilla en 01c, con nota fechada de la partición). Pendiente de 1.1: nada; la parte de rutas reales [WE54] se cierra en 3.3b.

## Tarea 1.2: `audit-json-serializer`, partida en 02a y 02b

Medida con `git diff --numstat` sin `openspec/`: **1 057 líneas efectivas** (1 033 añadidas, 24 borradas) frente a 330 estimadas. Se partió
sin consultar en la costura entre el constructor y su uso.

| Rama | Contenido | Líneas efectivas |
|---|---|---|
| `...-02a-audit-json-builder` | `AuditJson`, `canonString` de paquete, `AuditJsonTest`, `AuditChainAfterAuditJsonIT` | 348 |
| `...-02b-audit-json-sites` | Migración de los 16 sitios, `AuditJsonSiteEqualityTest`, `AuditJsonOnlySourceTest` y los fixtures `fixture/auditjson/` | 709 |

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (compilación) | `./mvnw -q -o -pl app -am test-compile` con las cuatro pruebas escritas y sin `AuditJson` | `exit=1`, 178 líneas `ERROR`: `cannot find symbol` para `AuditJson` (23 variables y 2 clases) y para los métodos y constantes de paquete que la igualdad por sitio invoca (`backoffCycleAfterValue`, `lowCodesAfterValue`, `rateLimitedAfterValue`, `issuedAfterValue`, `startedAfterValue`, `sessionsRevokedAfterValue`, `completedAfterValue`, `AFTER_FRESH`, `AFTER_GRACE`, `AFTER_FAMILY_REVOKED`). Causa: clases inexistentes, la esperada |
| ROJO (comportamiento) | Con `AuditJson` creado y cada sitio extraído a su método de paquete **todavía con la concatenación antigua**: `-Dtest='AuditJsonTest,AuditJsonSiteEqualityTest,AuditJsonOnlySourceTest'` | 56 pruebas, 1 fallo: `AuditJsonOnlySourceTest.noProductionSourceBuildsAnAuditDocumentByHand` señala exactamente los 16 sitios en las 8 clases: `AuthenticateWithPassword` (2), `ConsumeRecoveryCode` (1), `IssueSession` (1), `VerifyTotpCode` (1), `RequestPasswordReset` (2), `IssuePasswordResetToken` (2), `ResetPasswordWithToken` (3) y `RotateRefreshToken` (4). La igualdad pasa en este punto por construcción (expresión antigua contra sí misma): es la línea base que la migración debe conservar |
| VERDE | Mismo comando tras migrar los 16 sitios | 56 pruebas, 0 fallos; con las pruebas existentes de los ocho casos de uso, 234 pruebas, 0 fallos |
| Refactor | Los sitios con un solo miembro quedan como una línea; `canonString` pasa de `private` a paquete sin cambiar su cuerpo | Sin cambio de comportamiento |

### Demostraciones deliberadas

Cada una sobre una copia guardada (`pre-break-N.java` en el directorio de la sesión), revertida y confirmada con `cmp`. No queda ninguna ruptura en el árbol.

| # | Ruptura | Resultado observado | Revertida |
|---|---|---|---|
| 1 | `AuditJson.Builder.number` emite el valor entre comillas [AT01] | `AuditJsonSiteEqualityTest`: 6 de 17 fallos, los seis sitios con contadores (`ResetPasswordWithToken.sessionsRevokedAfterValue`, `AuthenticateWithPassword.backoffCycleAfterValue`, `ConsumeRecoveryCode.lowCodesAfterValue`, `IssuePasswordResetToken.rateLimitedAfterValue` y `.issuedAfterValue`, `VerifyTotpCode.backoffCycleAfterValue`); además 10 de 29 en `AuditJsonTest` | Sí, `cmp` idéntico |
| 2 | Literal `"{\"` concatenado de nuevo en `IssueSession.startedAfterValue` [BI43] | `AuditJsonOnlySourceTest.noProductionSourceBuildsAnAuditDocumentByHand`: `Expecting empty but was: [com.confia.identity.application.IssueSession:143]` | Sí, `cmp` idéntico |
| 3 | Orden de `reason` y `families` invertido en `ResetPasswordWithToken.sessionsRevokedAfterValue` | `AuditJsonSiteEqualityTest`: `expected: {"reason":"password_change","families":0} but was: {"families":0,"reason":"password_change"}` | Sí, `cmp` idéntico |

Desviación de la demostración 3: la tarea nombra `RotateRefreshToken.reasonJson`, que tiene un solo miembro y no admite inversión; se usó el sitio de dos miembros de `ResetPasswordWithToken`.

### Desviaciones

- Los sitios son métodos o constantes estáticos de paquete (antes privados) para que la igualdad por sitio ejerza el código real. No cambia ninguna API pública.
- La guardia lee el código fuente (comentarios y literales de carácter excluidos), no el código compilado: javac pliega la concatenación en `invokedynamic`.
- `RequestPasswordReset` y `RotateRefreshToken` evalúan sus documentos constantes una vez, al cargar la clase.
- `AuditJson` no entra en el alcance de PIT (`com.confia.*.domain.*`, `shared.security.token.*`, `shared.security.csrf.*`); la tarea no lo pide.

### Cierre de 02a

`./mvnw -o verify -Pmutation-gate` sobre la rama 02a, `BUILD SUCCESS` en 19 min 05 s: Surefire 186 (núcleo) + 2 047, Failsafe 421, 0 fallos. PIT: 631 de 673 mutantes muertos (94 %), fuerza de prueba 95 %, cobertura de líneas de las clases mutadas 895 de 925 (97 %); núcleo 192 de 194 (99 %). JaCoCo: «All coverage checks have been met».

### Cierre de 02b (cierra la tarea 1.2)

`./mvnw -o verify -Pmutation-gate` sobre la rama 02b (árbol final de la tarea 1.2), `BUILD SUCCESS` en 14 min 51 s: Surefire 186 (núcleo) + 2 074, Failsafe 421, 0 fallos. PIT: 631 de 673 mutantes muertos (94 %), fuerza de prueba 95 %, cobertura de líneas de las clases mutadas 895 de 925 (97 %). JaCoCo: «All coverage checks have been met». `AuditChainAfterAuditJsonIT` verifica la cadena de asientos escritos con el constructor [AT04]; `CanonicalSerializationCrossCheckIT` sigue en verde sin cambios. `AuditJsonOnlySourceTest` evalúa las ocho clases migradas sin conjunto vacío y no halla ninguna concatenación [BI44]; los tres fixtures malos fallan y el de `shared.security.token` no [BI43].

Estado final: tarea 1.2 marcada `[x]` en `tasks.md` (casilla en 02b, con nota fechada de la partición). Pendiente de 1.2: nada.

## Tarea 1.3: `edge-policies-and-delay-cap`, partida en 03a, 03b y 03c

Medida con `git diff --numstat origin/main...HEAD` sin `openspec/`: **1 775 líneas efectivas** (1 634 añadidas, 141 borradas) frente a
620 estimadas. Se partió sin consultar, por archivos completos, y cada rebanada se apila sobre la anterior.

| Rama | Contenido | Líneas efectivas |
|---|---|---|
| `...-03a-delay-cap` | `max-wait` en `DelayProperties` y en el materializador, `DelayPropertiesTest`, ampliaciones de `RequiredDelayMaterializerTest` y `RequiredDelayConfigurationTest`, `application.yml` (`confia.web.delay.max-wait`), nota de `docs/03` §10 sobre el tope | 268 |
| `...-03b-rate-policies` | `RateLimitProperties` con tres componentes `PolicyValues`, tres limitadores y tres políticas, `RateLimitFailures`, valores por omisión en `application.yml`, `RateLimitPropertiesTest` reescrita, `RateLimitIndependenceTest`, `RateLimitFailuresTest`, rutas `admin-mfa` y `admin-session` del arnés, nota de `docs/03` §10 | 732 |
| `...-03c-session-cookies-and-unknown-properties` | Sonda S-5 y `fail-on-unknown-properties`, `SessionCookies`, `SessionCookie`, `SessionCookieConfiguration`, `@Import`, `ProcessBeanPolicy`, inversión del inventario de `Cookie` con su fixture, BI48, `NoStraySetCookieTest` | 775 |

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (compilación, 03a y 03b) | `./mvnw -q -o -pl app -am test-compile` con las pruebas escritas y sin código de producción | `exit=1`, 144 líneas `ERROR`: `cannot find symbol` para `RateLimitFailures` (8), los métodos `adminLogin()` (10), `adminMfa()` (6), `adminSession()` (8) de `RateLimitProperties` y `maxWait()` (12), más el constructor del materializador con tope. Causa: clases y métodos inexistentes, la esperada |
| ROJO (comportamiento, 03b) | Con `RateLimitProperties`, `RateLimitFailures` y el tope presentes pero **sin** los valores en `application.yml` ni los limitadores nuevos: `-Dtest='RateLimitPropertiesTest,RateLimitIndependenceTest,RateLimitFailuresTest,RateLimitEdgeTest,...'` | `RateLimitPropertiesTest` 19 fallos y 8 errores; `RateLimitEdgeTest` 8 errores y 2 fallos; `RateLimitIndependenceTest` 4 errores; `RateLimitFailuresTest` 1 error. Causa observada: `APPLICATION FAILED TO START ... confia.web.rate-limit.admin-login.request-limit must be positive` y `confia.web.rate-limit.admin-login is not configured`, es decir, los componentes sin valores por omisión detienen el arranque nombrando la propiedad |
| ROJO (S-5, primer rojo de 03c) | `-Dtest=UnknownJsonPropertyTest` sin `spring.jackson.deserialization.fail-on-unknown-properties` | 3 pruebas, 2 fallos: `expected: 400 but was: 200`. **La sonda confirma que Spring Boot 4.1.1 ignora las propiedades desconocidas por omisión** |
| ROJO (compilación, 03c) | `test-compile` con `SessionCookiesTest` y la inversión del inventario sin `SessionCookies` | `exit=1`, 52 líneas `ERROR`: `cannot find symbol` para `SessionCookie` (10) y `SessionCookies` (4) |
| ROJO (lista de permitidos, 03c) | Con `SessionCookieConfiguration` en el `@Import` de `AdminApplication` y sin la línea de `ProcessBeanPolicy` | `ProcessBeanIsolationTest.registersOnlyItsAllowedBeans`: `process 'admin': bean 'sessionCookies' from package 'com.confia.shared.web.session' - not in the allow-list`. Aquí sí falla por la lista de permitidos, porque `com.confia.shared.web.session` no es subpaquete de ninguna entrada |
| VERDE | 03a: `DelayPropertiesTest` 11, `RequiredDelayConfigurationTest` 18, `RequiredDelayMaterializerTest` 29. 03b: `RateLimitPropertiesTest` 30, `RateLimitIndependenceTest` 4, `RateLimitFailuresTest` 6, `RateLimitEdgeTest` 11, `RateLimitInterceptorTest` 16, `ProcessBeanIsolationTest` 13. 03c: `UnknownJsonPropertyTest` 3, `SessionCookiesTest` 26, `NoStraySetCookieTest` 12, `WebEdgeScopeExclusionInventoryTest` 26, `SigningAndHashingConfinementTest` 7 | 0 fallos |
| Corrección de una prueba propia | `RateLimitFailuresTest` esperaba `429` en el primer intento tras el décimo fallo; la capa 2 admite un intento por minuto a un cliente restringido | La prueba ahora afirma `200` para ese único intento y `429` para el siguiente. `ProcessBeanIsolationTest` esperaba un solo limitador (`admin-login`) y ahora espera los tres |

### Demostraciones deliberadas

Cada una sobre una copia guardada (`pre-break-N.*` en el directorio de la sesión), revertida y confirmada con `cmp`. No queda ninguna ruptura en el árbol.

| # | Ruptura | Resultado observado | Revertida |
|---|---|---|---|
| 1 | El materializador ignora `max-wait` (`waitFor(delay)` en lugar de `waitFor(min(delay, maxWait))`) [WE75] | `RequiredDelayMaterializerTest.aDelayOfNineHundredSecondsWaitsTheCapAndTheDelayedKeepsTheWholeDelay` (`[the wait asked of the timer is the cap]`, `Expecting actual: [15M]`, 900 s en lugar de 30 s) y `aDelayOneNanosecondOverTheCapWaitsTheCap` | Sí, `cmp` idéntico |
| 2 | (Adicional) La política `admin-mfa` toma los valores de `admin-login` | `RateLimitIndependenceTest.eachPolicyAppliesItsOwnConfiguredLimit` (`expected: 2 but was: 1`) y `aClientWhoseSecondFactorQuotaIsSpentIsStillAdmittedByTheOtherTwoPolicies` (`expected: 429 but was: 200`) [WE70, WE71] | Sí, `cmp` idéntico |
| 3 | Se quita `.sameSite(SAME_SITE)` de `SessionCookies` [WE52] | `SessionCookiesTest`: 3 fallos (`theSessionCookieCarriesTheRefreshTokenWithTheExactAttributes`, `theCsrfCookieCarriesATokenBoundToTheRefreshWithTheExactAttributes`, `clearingWritesBothNamesEmptyWithMaxAgeZeroAndTheSameAttributes`); por ejemplo `Expecting actual: ["Path=/", "Secure"] to contain exactly in any order: ["Path=/", "Secure", "SameSite=Strict"]`. Una primera pasada con `SameSite=None` dio los mismos tres fallos | Sí, `cmp` idéntico |
| 4 | `spring.jackson.deserialization.fail-on-unknown-properties: false` [WE78] | `UnknownJsonPropertyTest`: 2 de 3 fallos, `expected: 400 but was: 200` | Sí, `cmp` idéntico |

### Cierre de cada rebanada

`./mvnw -o verify -Pmutation-gate` desde un árbol de trabajo limpio de la rama (sin cambios sin comprometer), `BUILD SUCCESS` en las tres:

| Rebanada | Duración | Surefire (núcleo + aplicación) | Failsafe | PIT (aplicación) | JaCoCo |
|---|---|---|---|---|---|
| 03a | 16 min 00 s | 186 + 2 101, 0 fallos | 421, 0 fallos | 631 de 673 mutantes muertos (94 %), fuerza de prueba 95 %; núcleo 192 de 194 (99 %) | «All coverage checks have been met» |
| 03b | 11 min 34 s | 186 + 2 120, 0 fallos | 421, 0 fallos | 631 de 673 (94 %), fuerza 95 %; núcleo 192 de 194 | «All coverage checks have been met» |
| 03c | 10 min 47 s | 186 + 2 163, 0 fallos | 421, 0 fallos | 631 de 673 (94 %), fuerza 95 %; núcleo 192 de 194 | «All coverage checks have been met» |

Las duraciones de 03b y 03c se midieron con otra construcción corriendo en paralelo en la misma máquina (y PIT informó `Minion exited abnormally due to TIMED_OUT` en
mutantes aislados sin afectar al resultado); no son comparables entre sí para el presupuesto de 8 minutos (R-9). La de 03a corrió casi sola.

### Condiciones de cierre de la tarea

- `rg -n "SessionCookies" apps/api/app/src/main`: solo el paquete `shared.web.session`, `SessionCookieConfiguration` y el `@Import` de `AdminApplication`; ningún controlador lo llama.
- `jakarta.servlet.http.Cookie` en producción: exactamente `com.confia.shared.web.session.SessionCookies` (`WebEdgeScopeExclusionInventoryTest.onlyTheCookieHelperUsesTheServletCookie` [BI47]); el fixture permanente `fixture/cookie/identity/web/BadControllerUsingCookie` es señalado por la misma regla.
- El HMAC de CSRF está en `shared.security.csrf` y ninguna clase de `shared.web` usa utilidades de firma ni de hash [BI48].
- La medición G-4 de la puerta de tiempo de inicio de sesión no corresponde a 1.3 (es de 3.1 y 4.1): no se midió aquí.

### Desviaciones

- Ver la nota fechada de la tarea 1.3 en `tasks.md` (cinco puntos): ubicación de `NoStraySetCookieTest`, propiedad `request-limit` frente a `requests` en WE73, R-2 sin necesidad del `JsonMapperBuilderCustomizer`, el filtro CSRF conserva la lectura de la cabecera `Cookie`, y `RateLimitFailures.record` con política desconocida lanza excepción.
- `RequiredDelayMaterializer(int, DelayTimer)` (de pruebas) conserva su firma y usa el tope por omisión de 30 s; el constructor de producción es ahora `(int, Duration)`.
- Las tres políticas se resuelven por `@Qualifier` en `RateLimiterConfiguration`, porque la construcción no conserva los nombres de parámetros.
- `AuditJson` y `SessionCookies` no entran en el alcance de PIT (la tarea no lo pide).

Estado final: tarea 1.3 marcada `[x]` en `tasks.md` (casilla en 03c, con nota fechada de la partición). Pendiente de 1.3: nada.

## Tarea 2.1: `session-revocation-v9`, partida en 04a, 04b, 04c y 04d

La tarea se construyó completa en un árbol temporal local (rama `tmp-revocation-all`, nunca publicada) antes de partirla: solo las
rebanadas de `V9` y de los casos de uso ya sumaban 1 552 líneas efectivas (`git diff --numstat`, sin `openspec/`) frente a las 880 de la
estimación realista, de modo que se parte sin consultar, por archivos completos y sin separar ninguna prueba de su código. Cada rebanada se apila
sobre la anterior.

| Rama | Contenido | Líneas efectivas |
|---|---|---|
| `...-04a-v9-revocation-reasons` | `V9`, `SessionRevocationReason` con los tres motivos nuevos, `RevocationReasonCatalogIT` (BI39 y BI40), ampliación de `RefreshTokenSchemaIT` (BI39, I204), `StepwiseDatabase`, `V9SchemaDiffIT` (BI41) y `V9ExistingRowsIT` (BI42) | 541 |
| `...-04b-end-current-session` | (pendiente de medir) `RefreshTokenFamilyRepository.revoke`, `EndCurrentSession`, `SessionRevocationAudit`, `EndSessionIT` (parte de cierre actual), `RefreshTokenFamilyRevokeIT` | |
| `...-04c-end-all-sessions` | (pendiente de medir) `EndAllSessions` y su parte de `EndSessionIT` | |
| `...-04d-revocation-follow-ups` | (pendiente de medir) F1a, F1b y las dos pruebas de concurrencia | |

### Evidencia del ciclo TDD (árbol completo, antes de partir)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (compilación) | `./mvnw -q -o -pl app -am test-compile` con `EndSessionIT`, `RevocationReasonCatalogIT`, `V9*IT` y el resto escritos y sin `EndCurrentSession` ni `EndAllSessions` | `exit=1`, 36 líneas `ERROR`: `cannot find symbol` para `EndCurrentSession` (3) y `EndAllSessions` (3). Causa: clases inexistentes, la esperada |
| ROJO (comportamiento) | Producción completa **sin** `V9`: `-Dit.test='RevocationReasonCatalogIT,RefreshTokenSchemaIT,EndSessionIT,V9SchemaDiffIT,V9ExistingRowsIT'` | 64 pruebas, 6 fallos y 11 errores. Causa observada: `violates check constraint "identity_refresh_token_family_reason_chk"` (el `CHECK` de `V8` rechaza `logout`) en 7 pruebas de `EndSessionIT` y en `RefreshTokenSchemaIT`; `RevocationReasonCatalogIT` 3 de 4 fallan (el catálogo tiene dos motivos y no cinco); `No migration with a target version 9 could be found` en `V9ExistingRowsIT` y `V9SchemaDiffIT`. Dos pruebas de `EndSessionIT` fallaron por mi propia aserción (el `after_value` vuelve de `jsonb` normalizado, `{"reason": "logout", ...}`, y se compara ahora como documento) y `V9SchemaDiffIT` por un defecto de mi consulta (`"char" \|\| unknown`); ambos se corrigieron antes del verde |
| VERDE | Mismo comando con `V9` | 74 pruebas, 0 fallos (`EndSessionIT` 22, `RefreshTokenFamilyRevokeIT` 10, `RefreshTokenSchemaIT` 34, `RevocationReasonCatalogIT` 4, `V9ExistingRowsIT` 1, `V9SchemaDiffIT` 3). La suma de verificación de `V8` se fijó en `1681600631` leyéndola de la historia de Flyway con `V8` sin cambios respecto de `origin/main` |

### Demostraciones deliberadas (árbol completo)

Cada una sobre una copia guardada (`pre-break-4a-N.*` en el directorio de la sesión), revertida y confirmada con `cmp`. No queda ninguna ruptura en el árbol.

| # | Ruptura | Resultado observado | Revertida |
|---|---|---|---|
| 1 | Quitar `revoked_at IS NULL` de `JooqRefreshTokenFamilyRepository.revoke` [I196] | `EndSessionIT` **sigue en verde** (el caso de uso comprueba la vigencia antes de llamar a `revoke`, de modo que la condición es una red de seguridad que esa prueba no alcanza; es el rojo que la tarea prometía y no se produce). Por eso se añadió `RefreshTokenFamilyRevokeIT`, que ejerce la red directamente: 2 fallos de aserción (`the call did nothing, so it does not claim to have revoked`: `true` en lugar de `false`; el segundo revocamiento por reutilización devuelve `true`) y 1 error del motor (`identity_refresh_token_family_revocation_final: a revoked family keeps its revocation`, el disparador de `V8` como segunda defensa) | Sí, `cmp` idéntico |
| 1b | (Adicional) Sustituir la comprobación de vigencia de `EndCurrentSession` por `if (false)` [I196] | `EndSessionIT`: 3 de 22 fallan, `aFamilyThatHasEndedByTimeIsNotRevokedAndWritesNoEntry`, `aFamilyAtTheExactEndOfItsLifeAndOneAtTheExactIdleBorderAreNotRevoked` y `anAlreadyRevokedFamilyKeepsItsReasonAndItsInstantAndWritesNoEntry` (`a family that is live under its own lock could not be revoked`) | Sí, `cmp` idéntico |
| 2 | Quitar `binding_changed` del `CHECK` de `V9` [BI39] | `RevocationReasonCatalogIT` 3 de 4 (`reasons the application declares that the constraint does not admit`), `V9SchemaDiffIT.v9ChangesTheRevocationReasonConstraintAndNothingElse` (línea 51) y `RefreshTokenSchemaIT.theRevocationReasonCatalogueIsClosedAndRevokedAtNeedsItsReason` (`violates check constraint ..._reason_chk`) | Sí, `cmp` idéntico |
| 4 | (Adicional) `EndAllSessions` toma el bloqueo de la cuenta después de revocar | `EndSessionIT.theAccountIsLockedBeforeTheFamiliesAndTheRevocationCarriesTheLogoutReason` (línea 345): el orden de llamadas no es `lock-account`, `revoke-logout` | Sí, `cmp` idéntico |

La demostración 3 de la tarea (revocador desactivado en `PasswordResetLeavesNoValidTokenIT`) pertenece a 04d.

### Cierre de 04a

`./mvnw -o verify -Pmutation-gate` desde un árbol de trabajo limpio de la rama, `BUILD SUCCESS` en 15 min 36 s: Surefire 186 (núcleo) + 2 163, Failsafe 427
(421 anteriores más 6: `RevocationReasonCatalogIT` 4 en lugar de 2, `V9SchemaDiffIT` 3, `V9ExistingRowsIT` 1 y la ampliación de `RefreshTokenSchemaIT` no suma pruebas),
0 fallos. PIT: 631 de 673 mutantes muertos (94 %), fuerza de prueba 95 %, núcleo 192 de 194 (99 %). JaCoCo: «All coverage checks have been met».

### Desviaciones de 04a

- `MultiTenantSchemaIT` y `RolePrivilegeMatrixIT` se re-ejecutan sin cambios y pasan: `V9` no añade política ni concesión, y la ausencia de ambas la fija `V9SchemaDiffIT` (privilegios por tabla y por columna, políticas, disparadores, índices). No se ampliaron porque no hay nada nuevo que afirmar en ellas.
- `StepwiseDatabase` crea una base de datos desechable en el contenedor compartido y migra con Flyway por pasos (`V8`, después `V9`); no existía un auxiliar así y las dos pruebas de esquema no se pueden escribir sin él.

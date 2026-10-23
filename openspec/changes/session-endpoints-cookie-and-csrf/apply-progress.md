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

### Rebanada 04b `end-current-session`

Apilada sobre 04a. **775 líneas efectivas** (769 añadidas, 6 borradas; `git diff --numstat 04a...HEAD -- . ':!openspec'`): `RefreshTokenFamilyRepository.revoke` y su
adaptador, `EndCurrentSession`, `SessionRevocationAudit` (el asiento compartido con 04c), el bean, el motivo `logout` en el comentario de la enumeración, `revoke` en
los seis dobles de prueba y en `HoldingFamilies`, `RevocationIntegrationTest`, `EndSessionIT` (17 pruebas del cierre actual: I195 a I197, mal formado o ausente, desconocido,
revocada, vencida, bordes exactos de fin e inactividad, otra institución, asiento que falla, identificador de petición) y `RefreshTokenFamilyRevokeIT` (10).

La evidencia de rojo y de verde es la del árbol completo (ver arriba): las pruebas de esta rebanada fueron las que fallaron por el `CHECK` de `V8` y por las clases
inexistentes. Las demostraciones 1, 1b y 4 de arriba se aplicaron sobre este código (la 4, sobre `EndAllSessions`, vive en 04c).

Cierre: `./mvnw -o verify -Pmutation-gate` desde un árbol limpio, `BUILD SUCCESS` en 15 min 58 s: Surefire 186 + 2 163, Failsafe 454 (427 + 27), 0 fallos. PIT: 631 de 673 mutantes muertos (94 %), fuerza de prueba 95 %, núcleo 192 de 194 (99 %). JaCoCo: «All coverage checks have been met».

Desviaciones: `EndCurrentSession` abre su propio `SecurityContext` de actor `system` con la institución de `LoginInstitutionProvider` (como la rotación, cuyo contexto lo arma el controlador); el diseño fija `execute(String presentedRefreshToken, String requestId)` sin contexto, de modo que el caso de uso lo construye. Un texto `null` se trata como «nada que hacer» y no abre transacción (la rotación lanzaría `NullPointerException`): el controlador pasará lo que `SessionCookies.read` devuelva.

### Rebanada 04c `end-all-sessions`

Apilada sobre 04b. **272 líneas efectivas** (256 añadidas, 16 borradas): `EndAllSessions`, su bean, el motivo `logout` para toda la cuenta en el comentario de la enumeración, el
auxiliar `endAllSessions` y `actorOf` en `RevocationIntegrationTest` y las 5 pruebas de «cerrar todas» añadidas a `EndSessionIT`, que queda en 22: I200 (tres vivas, una
revocada que conserva motivo e instante, dos de otra cuenta que siguen vivas, sucesores cifrados vaciados, asiento con `3`), recuento cero con asiento, orden
bloqueo de la cuenta y después revocación con `logout`, asiento que falla sin dejar ninguna familia revocada, y un actor cuya cuenta no existe (falla, no devuelve cero en silencio).

La evidencia de rojo y de verde es la del árbol completo; la demostración 4 (orden del bloqueo) se aplicó sobre este código.

Cierre: `./mvnw -o verify -Pmutation-gate` desde un árbol limpio, `BUILD SUCCESS` en 19 min 16 s: Surefire 186 + 2 163, Failsafe 459 (454 + 5), 0 fallos. PIT: 631 de 673 mutantes muertos (94 %), fuerza de prueba 95 %, núcleo 192 de 194 (99 %). JaCoCo: «All coverage checks have been met».

Desviación: `EndAllSessions.execute` devuelve el número de familias revocadas (el diseño fija `int execute(AuthenticatedActor, String requestId)`), y falla con `IllegalStateException` si la cuenta del actor no existe, como `IssueSession`.

### Rebanada 04d `revocation-follow-ups` (cierra la tarea 2.1)

Apilada sobre 04c. **590 líneas efectivas** (589 añadidas, 1 borrada), solo pruebas más los auxiliares de `RevocationIntegrationTest` (`seedStaffAccountIn` con el identificador de la cuenta elegido, `rotateIn`, `Race` y `race`):

- `AccountSessionRevokerCrossInstitutionIT` (F1b, I208; 3 pruebas): la institución A con una cuenta y dos familias; la B con la **misma cuenta** (mismo identificador, la clave de una cuenta es el par institución e identificador) y dos familias, y otra cuenta con dos más; una familia de cada una rotada para que haya un sucesor cifrado que vaciar. Revocar y cerrar todas en A no cambia nada en B (motivo, instante, tokens vigentes, sucesores). La tercera prueba nombra B en una transacción abierta para A y al revés: devuelve cero, de modo que el argumento de institución del revocador no queda a merced solo de la seguridad por filas.
- `PasswordResetLeavesNoValidTokenIT` (F1a, I207; 2 pruebas): tres familias de la cuenta con refresco y acceso vigentes y una cuenta ajena, contra el proceso administrativo real; antes del restablecimiento los tres accesos dan `200`; después los tres refrescos dan `Rejected(FAMILY_REVOKED)` y los tres accesos `401 token-invalid`, y la cuenta ajena rota y responde `200`. Control negativo: con un revocador que no revoca, los seis resultados son los contrarios.
- `LogoutVersusRotationConcurrencyIT` y `CloseAllVersusRotationConcurrencyIT` (I198; 2 pruebas cada una): dos transacciones reales, la primera retenida con `CountDownLatch` justo después de tomar el bloqueo de la familia (o, en el cierre de todas, después de revocar y antes de confirmar) y la segunda observada esperando con la condición `pg_stat_activity` de S1 (límite de 20 s, sin esperas por reloj). En ambos órdenes la familia queda revocada con `logout`, sin token vigente; con la rotación primera nace un sucesor en una familia revocada y el cierre vacía su sucesor cifrado; con el cierre primero la rotación da `Rejected(FAMILY_REVOKED)`, no consume nada y no inserta sucesor.

### Evidencia del ciclo TDD (04d)

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (compilación) | `./mvnw -q -o -pl app -am test-compile` con las cuatro clases escritas y sin los auxiliares | `exit=1`, 63 líneas `ERROR`: `cannot find symbol` para `Race` (4), `race` (4), `rotateIn` y la sobrecarga de `seedStaffAccountIn`. Causa: auxiliares y fixture de la segunda institución inexistentes, la esperada |
| VERDE | `-Dit.test='AccountSessionRevokerCrossInstitutionIT,PasswordResetLeavesNoValidTokenIT,LogoutVersusRotationConcurrencyIT,CloseAllVersusRotationConcurrencyIT'` | 9 pruebas (3 + 2 + 2 + 2), 0 fallos. F1a y F1b describen comportamiento que ya existía, de modo que no hay rojo de comportamiento: su prueba de que no son vacías es el control negativo y las demostraciones |
| Estabilidad | Las dos pruebas de concurrencia otras dos veces, aisladas (`stab-4d-1` y `stab-4d-2`) y una más dentro del `verify` completo | 4 de 4 en verde cada vez |

### Demostraciones deliberadas (04d)

Cada una sobre una copia guardada (`pre-break-4d-N.java` en el directorio de la sesión), revertida y confirmada con `cmp`. No queda ninguna ruptura en el árbol.

| # | Ruptura | Resultado observado | Revertida |
|---|---|---|---|
| 3 de la tarea | `JooqAccountSessionRevoker.revokeAllLive` devuelve cero sin revocar [I207] | `PasswordResetLeavesNoValidTokenIT.afterTheResetNoRefreshTokenRotatesAndNoAccessTokenOfTheAccountIsAccepted` (línea 104): `expected: Rejected[reason=FAMILY_REVOKED] but was: Rotated[session=..., kind=FRESH]`; el control negativo sigue en verde | Sí, `cmp` idéntico |
| 5 | Quitar el filtro de institución del revocador (queda solo la cuenta) [I208] | `AccountSessionRevokerCrossInstitutionIT.theInstitutionArgumentOfTheRevokerIsNotLeftToRowLevelSecurityAlone` (línea 100): `[A's families are visible, but the argument names B] expected: 0 but was: 2`. Las otras dos pruebas siguen en verde: la seguridad por filas sola ya oculta B, que es justo lo que la tercera prueba existe para no dar por suficiente | Sí, `cmp` idéntico |
| 6 | `lockById` sin `FOR UPDATE` [I198] | `LogoutVersusRotationConcurrencyIT`, las dos pruebas, en la aserción de espera: `[the closing waited for the family lock the rotation held]` y `[the rotation waited for the family lock the closing held]`, `expected true but was false`. El estado final es el mismo en cualquier orden (como documentan las pruebas de rotación de S1), de modo que la aserción de espera es lo que ata la prueba al bloqueo | Sí, `cmp` idéntico |
| 7 | El revocador deja de vaciar los sucesores cifrados | `CloseAllVersusRotationConcurrencyIT.aClosingOfEverySessionThatWaitsForARotationRevokesTheFamilyAndLeavesNoLiveToken` (línea 69): `[the successor the rotation stored was emptied by the closing]`, y `AccountSessionRevokerCrossInstitutionIT.revokingEveryFamilyOfAnAccountOfInstitutionALeavesInstitutionBUntouched` (línea 68) | Sí, `cmp` idéntico |

Cierre: `./mvnw -o verify -Pmutation-gate` sobre el árbol final de la tarea (con los documentos comprometidos), `BUILD SUCCESS` en 25 min 26 s: Surefire 186 + 2 163, Failsafe 468 (459 + 9), 0 fallos. PIT: 631 de 673 mutantes muertos (94 %), fuerza de prueba 95 %, núcleo 192 de 194 (99 %). JaCoCo: «All coverage checks have been met». Las dos pruebas de concurrencia pasaron en las tres ejecuciones (dos aisladas y el `verify`).

### Estado final de la tarea 2.1

Marcada `[x]` en `tasks.md` (casilla en 04d, con nota fechada de la partición). Pendiente de 2.1: nada. La tarea no añade controlador, ruta ni cookie.

### Cuadro final de la tarea 2.1

| Rama | Líneas efectivas | Duración del `verify -Pmutation-gate` | Surefire (núcleo + aplicación) | Failsafe | PIT (aplicación) |
|---|---|---|---|---|---|
| `...-04a-v9-revocation-reasons` | 541 | 15 min 36 s | 186 + 2 163 | 427 | 631 de 673 (94 %) |
| `...-04b-end-current-session` | 775 | 15 min 58 s | 186 + 2 163 | 454 | 631 de 673 (94 %) |
| `...-04c-end-all-sessions` | 272 | 19 min 16 s | 186 + 2 163 | 459 | 631 de 673 (94 %) |
| `...-04d-revocation-follow-ups` | 590 | 25 min 26 s | 186 + 2 163 | 468 | 631 de 673 (94 %) |

Las tres últimas duraciones crecen con las pruebas nuevas y con otras construcciones de la máquina en las demostraciones; no son comparables entre sí para el presupuesto de 8 minutos (R-9). Los casos de uso no tienen llamador HTTP (`rg -n "EndCurrentSession|EndAllSessions" apps/api/app/src/main` solo muestra sus clases y `IdentityConfiguration`).

## Tarea 3.1 `login-endpoint` (2026-10-22)

Seis ramas apiladas sobre `main` 3e4e201, cada una con `./mvnw -o verify -Pmutation-gate` en verde (PIT 648/690, núcleo 192/194, JaCoCo cumplido):

| Rama | Líneas | verify | Surefire núcleo + app | Failsafe |
|---|---|---|---|---|
| 05a `failure-spread-window` (210432e) | 572 | 13 min 36 s | 186 + 2 187 | 468 |
| 05b `login-views-and-event-log` (4f189d8) | 551 | 14 min 29 s | 186 + 2 203 | 468 |
| 05c `login-use-case` (7a79477) | 788 | 15 min 57 s | 186 + 2 203 | 486 |
| 05d `authentication-failed-problem-code` (09e41ee) | 174 | 14 min 19 s | 186 + 2 232 | 486 |
| 05e `login-controller-and-h1` (0f0683e) | 1 469 | 10 min 38 s | 186 + 2 241 | 491 |
| 05f `login-endpoint-behaviour` (e3da4f1) | 742 | 10 min 56 s | 186 + 2 248 | 512 |

RED: clases inexistentes (`cannot find symbol` LoginDecision, SessionGrant, LogIn...; 05a-05c); `ProblemCodeException` y `AUTHENTICATION_FAILED` inexistentes (05d); `LoginController` inexistente; con 05d y sin controlador, `LoginEndpointIT` 16 de 18 en 401 y `LoginRateLimitClosesH1IT` 4 de 4 en rojo (05e/05f); `IllegalArgumentException: a normalized login identifier must be between 3 and 320` para un identificador de 2 caracteres (habría sido 500) antes de la guardia de `LogIn`.

Demostraciones (copia previa, ruptura, `cmp` idéntico): (1) quitar `@RateLimited`: `SessionRoutesRateLimitedRuleTest` señala `LoginController.login` y `LoginRateLimitClosesH1IT` da `expected 429 but was 200/401`. (2) contar `ChallengeIssued` como fallo: la prueba de I162 da `expected 200 but was 429`. (3) `TOKEN_INVALID` solo para identificador `nadie*`: la igualdad de I157 enumera diferencias de cuerpo y cabeceras. (4) emitir cookie en el segundo factor: las dos pruebas de I150/I151 reciben `Set-Cookie`. Además: `LogInIT` (origen sin enviar, `Set-Cookie` en las pruebas de señal) con dos rupturas en `LogIn`/`AuthenticateWithPassword`, ambas en rojo y revertidas.

G-4 (BI57): sonda desechable con 200 + 200 intentos Argon2id (perfil piso) sobre PostgreSQL real: 15,7 s + 13,6 s = 29,3 s, más la igualdad del 401 (`LoginEndpointIT` completo 11 s). La puerta de tiempo costaría unos 30 s extra en `verify`. El `verify -Pmutation-gate` completo ya dura 10-16 min en esta máquina, así que el presupuesto de 8 minutos de BI57 ya no se cumple por la construcción entera, no por la puerta. DECISIÓN DEL PROPIETARIO PENDIENTE (tarea 4.1): reescribir BI57 (medir solo sin mutación) o mover la puerta al perfil de mutación (R-9). No se movió nada.

Reserva H1: con `confia.web.trusted-proxies` vacío el tráfico tras un proxy comparte un cubo; su llenado es del cambio 11. `packages/contracts` se regeneró con `orval` directo (pnpm exige Node 24) y `tsc --noEmit` pasó; no se compromete.

## Tarea 3.2 `second-factor-endpoints` (2026-10-23)

Doce ramas apiladas sobre `main` f2bd8b4, cada una con `./mvnw -o verify -Pmutation-gate` en verde (PIT aplicación 648/690 en 06a-06b y 652/694 desde 06c; núcleo 192/194; JaCoCo cumplido). La tarea completa superaba 4 000 líneas, así que se partió sin consultar por costuras de código completo; ninguna prueba se separó de su código y ninguna ruta quedó expuesta sin `@RateLimited` (las rutas restringidas solo existen desde 06j).

| Rama | Contenido | Líneas | Surefire núcleo + app | Failsafe |
|---|---|---|---|---|
| 06a `restricted-principal-and-route-list` (86e01b7) | `PendingSecondFactor`, `SecondFactorPurpose`, `SecondFactorEndpoint`, segunda lista de `AuthenticatedEndpoints` | 270 | 186 + 2 259 | 512 |
| 06b `restricted-route-filter` (492a055) | filtro (`verifyMfa` en rutas restringidas), `SecondFactorAuthentication`, `SecurityChains`, arnés, `SecondFactorRouteTest`, enumerador de tres clases | 736 | 186 + 2 285 | 512 |
| 06c `verified-credential` (f01c94e) | `TotpCredential.isVerified`, «MFA activa» en inicio de sesión y restablecimiento (I167, I217, I218) | 139 | 186 + 2 291 | 514 |
| 06d `reenrollment-persistence` (4d33cdd) | `replaceUnverifiedSecret`, `invalidateUnused` y sus pruebas | 202 | 186 + 2 291 | 518 |
| 06e `enrollment-use-case` (31c887d) | `EnrollTotpSecondFactor` repetible, resultado sellado, `ConcurrentEnrollmentIT` (I180 a I183) | 723 | 186 + 2 291 | 523 |
| 06f `completion-views` (8394bd5) | comando, decisión y desenlace de `CompleteSecondFactor` | 226 | 186 + 2 296 | 523 |
| 06g `complete-second-factor` (6bd3866) | `CompleteSecondFactor`, `mfa_change`, `CompleteSecondFactorIT` (I168 a I179, I221 a I223) | 708 | 186 + 2 296 | 539 |
| 06h `enroll-versus-verify` (659e9bd) | `EnrollVersusVerifyConcurrencyIT`, `HoldingAccounts` | 250 | 186 + 2 296 | 541 |
| 06i `second-factor-problem-codes` (351076a) | `second-factor-invalid`, `mfa-already-enrolled` | 73 | 186 + 2 308 | 541 |
| 06j `enrollment-endpoint` (eacb473) | `POST /api/v1/auth/mfa/enrollments`, `admin-mfa`, OpenAPI | 640 (112 de OpenAPI generado) | 186 + 2 310 | 545 |
| 06k `verification-endpoint` (fcb2bab) | `POST /api/v1/auth/mfa/verifications`, `SecondFactorEndpointsIT` completo, WE72 | 672 (137 de OpenAPI generado) | 186 + 2 318 | 553 |
| 06l `second-factor-docs` | notas en `docs/03` y ADR-0005, prueba documental de I185, cierre SDD | ver `git diff --numstat` | 186 + 2 319 | 553 |

RED: clases inexistentes (`cannot find symbol` PendingSecondFactor, SecondFactorEndpoint, SecondFactorAuthentication; 409 líneas de error) y luego, con los tipos y sin el filtro, 12 fallos y 1 error de comportamiento (el token restringido recibía `401`, el de acceso `200`; 06a/06b); `isVerified`, `replaceUnverifiedSecret`, `invalidateUnused`, `Enrolled` y `AlreadyEnrolled` inexistentes (174 líneas; 06c-06e); `CompleteSecondFactor` y sus tipos inexistentes (268 líneas; 06f-06g); siete fallos y cuatro errores por los dos códigos de problema ausentes (06i); `SecondFactorEndpointsIT` completo contra el árbol sin controlador: 5 fallos y 6 errores (`token-invalid` en lugar de `201`/`401 second-factor-invalid`; 06j-06k).

Demostraciones (copia previa, ruptura, `cmp` idéntico, ninguna ruptura en el árbol): (1) `verifyAccess` en rutas restringidas: `SecondFactorRouteTest` 8 fallos y 1 error. (2) Poblar `AuthenticatedActor` desde el token restringido: [WE66] en rojo, `Expecting an empty Optional but was containing value`. (3) Aceptar el código de recuperación con `mfa-enroll`: [I221] en rojo (`Rejected` esperado, `Started` obtenido). (4) Revocar `mfa_change` después de emitir la familia nueva: 3 fallos, entre ellos [I176]/[I177] (la familia nueva queda revocada, `expected: null but was: ...`). (5) Quitar `accounts.lockById` de la inscripción: `ConcurrentEnrollmentIT` 2 de 2 en rojo, con `IntegrityConstraintViolationException ... duplicate key` (el 500 de [I183]). Adicional: `AuthenticateWithPassword` con presencia en lugar de verificación: [I167] en rojo.

Desviaciones: `SecondFactorRouteTest` vive en `test/.../shared/web/authentication/` como pide la tarea, y las pruebas del filtro con petición simulada se ampliaron en `AccessTokenAuthenticationFilterContextTest`. `EnrollTotpSecondFactor` gana el constructor con cuentas y reloj, y el resultado es una interfaz sellada (`Enrolled`, `AlreadyEnrolled`) con `secretBase32()` y `recoveryCodeTexts()` para que la capa web no toque tipos de dominio. `CompleteSecondFactor` no evalúa ni cuenta contra el retroceso una prueba que el estado no acepta (recuperación con `mfa-enroll`, credencial ausente, texto que no puede ser un código); el rechazo cuenta igualmente en `admin-mfa`. `TestProcessArguments` fija `confia.web.allowed-origins` por omisión para que las pruebas anónimas de rutas POST lleguen al filtro de token. La verificación de 06a falló una vez (lista de dependencias web permitidas de `IdempotencyScopeExclusionInventoryTest`) y la de 06i una vez por un árbol de trabajo contaminado de la propia sesión; ambas se corrigieron y se repitieron completas. La duración de 06c (322 min) no es fiable: el equipo se suspendió durante la ejecución; las demás van de 10 a 16 min. `packages/contracts` no se regeneró ni se compromete (pnpm exige Node 24).

Riesgo declarado (DA-15): hasta la primera verificación, quien conozca la contraseña puede reemplazar el secreto; cada reinscripción queda auditada. Documentado en `docs/03-seguridad.md` §4.3 y ADR-0005 (I185).

## Tarea 3.3 `refresh-and-logout-endpoints` (2026-10-23)

Cuatro ramas apiladas sobre `main` 53b5470, cada una con `./mvnw -o verify -Pmutation-gate` en verde (PIT aplicación 668/710, 94 %; núcleo 192/194; JaCoCo cumplido). La tarea nominal (1 210 líneas realistas) se partió sin consultar; ninguna prueba se separó de su código y ninguna ruta quedó expuesta sin `@RateLimited` ni sin la protección del filtro CSRF y `Origin` (el refresco llega en 07c y los dos cierres en 07d, ambos con política y con filtro desde su primer commit).

| Rama | Contenido | Líneas efectivas (sin `openspec/`) | Surefire núcleo + app | Failsafe |
|---|---|---|---|---|
| 07a `refresh-binding` | `RotationDecision` con vinculación (orden de 7 pasos), `RefreshRejection.BINDING_CHANGED`, `RefreshTokenFamilyState` con huella e IP, `RotateRefreshTokenCommand` con dirección y `requestId`, `RotateRefreshToken.execute(command)` y `revokeForBinding`, `RefreshSession`, `RefreshOutcome.Rotated` con los dos extremos de la sesión | 928 (313 de código, 615 de pruebas) | 186 + 2 328 | 566 |
| 07b `session-expired-code` | código `session-expired` (`401`, `Bearer`) y su entrada es-HN | 49 | 186 + 2 335 | 566 |
| 07c `refresh-endpoint` | `SessionRefreshController`, entrada pública del refresco, `SessionEndpointSupport`, `SessionRefreshEndpointIT` (11), `TwoTabRefreshConcurrencyIT`, OpenAPI | 790 (109 de OpenAPI generado excluidos del cómputo de 899) | 186 + 2 336 | 578 |
| 07d `logout-endpoints` | `SessionEndController` (cierre actual y cerrar todas), entrada pública y entrada autenticada `LIVE_SESSION`, `SessionEndEndpointIT` (11), `SessionCookieRoutesCsrfIT`, `RevocationProducersIT`, OpenAPI y notas de `docs/03` y ADR-0005 | 782 (155 de OpenAPI generado excluidos del cómputo de 937) | 186 + 2 337 | 592 |

RED: 07a: `cannot find symbol` (BINDING_CHANGED, `decide` de seis argumentos, constructores nuevos), 133 líneas `ERROR` de compilación con los árboles de producción revertidos. 07b: 5 fallos y 2 errores (`session-expired` inexistente). 07c: `SessionRefreshEndpointIT` completo sin controlador, 11 pruebas con 7 fallos y 2 errores. 07d: pruebas de rutas 12 fallos y 1 error; ITs 9 fallos (`SessionEndEndpointIT` 7 de 11, `CurrentSessionEndpointIT` 1, `SessionCookieRoutesCsrfIT` 1). `RevocationProducersIT` nace en verde (describe productores que ya existían); su control es que `SessionRevocationReason` y los cinco productos deben coincidir.

Demostraciones (copia previa en el directorio de la sesión, ruptura que cambia un resultado, `cmp` idéntico, ninguna ruptura en el árbol): (1) vinculación antes de la reutilización en `RotationDecision`: `RotationDecisionTest` (`expected REUSE_DETECTED but was REJECT_BINDING_CHANGED`) y `RotationDecisionProperties`. (2) Omitir `SessionCookies.clear` en el rechazo del refresco: 3 pruebas de `SessionRefreshEndpointIT` en rojo (ningún `Set-Cookie` de borrado). (3) Desactivar `CsrfOriginFilter` en `AdminSecurityConfiguration`: `SessionCookieRoutesCsrfIT` en rojo, `expected 403 but was 200`. (4) Reemitir en la gracia la cookie ligada al refresco presentado (el anterior): `SessionRefreshEndpointIT` (gracia) y `TwoTabRefreshConcurrencyIT` en rojo, el refresco sucesor ya no es el mismo.

Condición 3 (parte de rutas reales): `SessionCookieRoutesCsrfIT` verde con los seis `403 csrf-invalid` ([WE54]: refresco y cierre actual, cada uno sin `X-CSRF-Token`, con el CSRF de otra sesión y sin `Origin`) y cero invocaciones de los casos de uso (ninguna entrada de auditoría, la familia sin uso, el token del afectado todavía abierto), más su control con las mismas peticiones bien hechas. Con [WE53] (1.1) queda cerrada.

Desviaciones y notas:
- 07a supera las 800 líneas (928): el cambio de la firma de `decide` obliga al conmutador de `RotateRefreshToken` a cubrir `REJECT_BINDING_CHANGED`, de modo que la decisión de dominio, su aplicación y el repositorio no se separan sin dejar una rama que no compila; 615 de las líneas son pruebas (`RotationDecisionTest`, `RotationDecisionProperties`, `RefreshBindingIT`, `RefreshSessionIT`). No se recortó ningún comentario ni prueba.
- `RefreshOutcome.Rotated` gana `sessionExpiresAt` e `idleExpiresAt` para que `RefreshSession` entregue el `SessionGrant` con los dos extremos de la sesión; `RefreshTokenFamilyState` conserva el constructor de seis argumentos (sin origen) para no tocar diez pruebas de otras tareas.
- `RotationDecisionPropertiesTest` se conserva y se amplía su firma; `RotationDecisionProperties` es el archivo nuevo que pide la tarea.
- `RefreshSession.Rejected` no lleva motivo: el motivo se queda en la auditoría y el controlador no puede distinguir los seis casos.
- Un token de acceso vencido enviado a `DELETE /api/v1/auth/sessions/current` recibe `401 token-expired` del filtro de tokens, porque el filtro verifica toda cabecera `Bearer` aun en rutas públicas (decisión de S1). El cierre «con el acceso vencido» [I194] se prueba sin enviar token de acceso, como hace un cliente que ya no tiene uno válido; la SPA no debe enviar el vencido. Seguimiento posible para el cambio del cliente.
- `DELETE` sobre `/api/v1/auth/sessions/current` ya no es 403 para un actor autenticado ([WE20] se acota a los demás métodos): es el cierre actual, ruta pública de cookie.
- `packages/contracts` no se regeneró ni se compromete (pnpm exige Node 24; Node 22 local). Las instantáneas OpenAPI sí se actualizaron.

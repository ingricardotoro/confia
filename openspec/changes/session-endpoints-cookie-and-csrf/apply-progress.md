# Progreso de aplicación: endpoints de sesión, cookie y CSRF (parte 4b, S2)

- **Cambio:** `session-endpoints-cookie-and-csrf` (F0, cambio 7, parte 4b, segundo cambio de tres)
- **Modo:** TDD estricto (`./mvnw verify -Pmutation-gate` en `apps/api`, JDK 25, Docker en ejecución)
- **Estrategia de entrega:** `stacked-to-main`, tope de 800 líneas efectivas por PR (DA-1 *a*)
- **Última actualización:** 2026-10-21 (tarea 1.1 completa: rebanadas 01a, 01b y 01c)
- **Regla de este archivo:** solo se añade; no se reescriben secciones existentes.

## Estado de las tareas

| Tarea | PR | Estado | Commits |
|---|---|---|---|
| 1.1 | PR 1 `csrf-and-origin-protection`, partido en 01a, 01b y 01c (ver «Tarea 1.1: medición y partición») | Completa y verificada | 01a: `2cce1be`; 01b: `0bfeb27`; 01c: ver el historial de la rama |

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

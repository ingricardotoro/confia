# Progreso de aplicación: núcleo de tokens, familias de refresco y revocación al restablecer (parte 4b, S1)

- **Cambio:** `session-tokens-and-web-layer` (F0, cambio 7, parte 4b, primer cambio de tres)
- **Modo:** TDD estricto (`./mvnw verify` en `apps/api`, JDK 25, Docker en ejecución)
- **Estrategia de entrega:** `auto-chain` con `stacked-to-main`, tope de 800 líneas efectivas por PR
- **Última actualización:** 2026-10-06 (tarea 1.1 construida y verificada completa; detenida antes del commit por tamaño)
- **Regla de este archivo:** solo se añade; no se reescriben secciones existentes.

## Estado de las tareas

| Tarea | PR | Estado | Commits |
|---|---|---|---|
| 1.1 | PR 1 `jws-compact-codec` | Construida y verificada completa, **sin comprometer**: la medición (1 631 líneas) supera el tope de 800; el ejecutor se detuvo y consulta al orquestador antes de partir | |

## Tarea 1.1: PR 1 `jws-compact-codec`

### Red de seguridad

Los archivos existentes que se tocan son solo dos `pom.xml` (entrada de PIT y lista `bannedDependencies`); no hay código de
producción preexistente modificado. Línea base de `main` en `2a599f5`: el `./mvnw verify` completo de cierre de esta tarea
incluye todas las pruebas previas en verde (ver «Cierre»).

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO | `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Ed25519SignaturesRfc8037Test,CompactJwsAttackTest,CompactJwsPropertiesTest,Base64UrlPropertiesTest,Base64UrlTest,SigningKeyRingInMemoryTest'` con las pruebas escritas y **sin** ninguna clase de producción | `COMPILATION ERROR`: 100 errores `cannot find symbol` (Maven imprime cada uno dos veces), todos por tipos inexistentes: `Base64Url`, `Ed25519Signatures`, `SigningKey`, `SigningKeyRing`, `TokenRejection`, `TokenRejectedException`, `CompactJws`, `VerifiedJws` (y el método `rejection()`). Es la causa prevista; ninguna prueba llegó a ejecutarse. |
| VERDE | Igual, con el códec, el anillo en memoria y el recurso del vector RFC | `Base64UrlTest` 25, `CompactJwsAttackTest` 97, `Ed25519SignaturesRfc8037Test` 8, `SigningKeyRingInMemoryTest` 19, `Base64UrlPropertiesTest` 3, `CompactJwsPropertiesTest` 2: `Tests run: 154, Failures: 0, Errors: 0`. El `BUILD FAILURE` de esa orden proviene de JaCoCo por el `-Dtest=` acotado (comportamiento previsto). |
| Triangulación | Cada ataque tiene su razón exacta (`TokenRejection`) y cada orden de los pasos (cabecera antes que firma, longitud antes que verificación, carga solo tras la firma) tiene su prueba | Ver las rupturas deliberadas |
| Refactor | Se quitaron dos condiciones redundantes de `hasThreeNonEmptyVisibleSegments` tras leer los mutantes de PIT; se añadió la prueba de los límites ASCII visibles | `CompactJwsAttackTest` 98, todo en verde |

Las 155 pruebas nuevas se distribuyen así: `Base64UrlTest` 25, `CompactJwsAttackTest` 98, `Ed25519SignaturesRfc8037Test` 8,
`SigningKeyRingInMemoryTest` 19, `Base64UrlPropertiesTest` 3, `CompactJwsPropertiesTest` 2.

### Sonda S-1 y S-2 como pruebas ordinarias

- **S-1.** `Ed25519SignaturesRfc8037Test.thePublishedRfcSignatureVerifiesWithThePublicKeyOfTheAppendix`: la firma publicada de RFC 8037
  A.4 verifica con la clave pública del apéndice (envuelta en X.509 con el prefijo DER fijo); con cualquiera de sus 512 bits
  alterados se rechaza; con un par generado en la prueba la firma es determinista, verifica y no verifica con la clave del RFC.
- **S-2.** `theJdkThrowsForAMalleableSignatureAndForALengthOf63BytesAndThePrimitiveAnswersFalse`: el JDK lanza
  `SignatureException` para `S + L` y para 63 bytes (sondea el comportamiento en cada ejecución); `Ed25519Signatures.verify` responde
  `false`; y `CompactJwsAttackTest.theMalleableSignatureIsRejectedWithoutTheJdkExceptionEscaping` exige `BAD_SIGNATURE`, sin excepción
  ajena y sin causa. Una firma de 63 y de 65 bytes llega al códec como `MALFORMED` (paso 3, por longitud, antes del JDK).

### Rupturas deliberadas (BI32 y las demás de la tarea)

Cada una se aplicó sobre una copia guardada, se ejecutó el conjunto de la tarea, se revirtió y se confirmó con `cmp` que el
archivo quedó idéntico a la copia. **No queda ninguna ruptura en el árbol.**

| # | Ruptura | Resultado observado | Revertida |
|---|---|---|---|
| 1 | `SigningKeyRing.keyForHeaderSegment` devuelve siempre la clave vigente (ni compara la cabecera ni selecciona por `kid`) | `CompactJwsAttackTest`: 39 fallos y 1 error (los 32 casos de `aForgedHeaderIsRejectedEvenWithAValidSignatureOfTheSystemKey`, `anHs256TokenSigned...`, `theCompleteRfc8037A4TokenIsRejectedBecauseItsHeaderHasNoKid`, `theHeaderOfAnotherKnownKidSelectsThatKidAndNotTheCurrentOne`, `anAlgNoneToken...`, `anUnknownHeaderIsDecidedBefore...`, `aTokenSignedByTheKeyOfTheRingThatOnlyVerifiesIsAccepted` como error) y `SigningKeyRingInMemoryTest`: 2 fallos | Sí, `cmp` idéntico |
| 2 | `Base64Url.decode` devuelve los bytes sin comprobar que el texto sea canónico | `Base64UrlPropertiesTest.acceptsExactlyTheTextsTheJdkEncoderCouldHaveProduced` en rojo; `Base64UrlTest`: 5 fallos (relleno, bits de cola); `CompactJwsAttackTest`: 4 fallos (`aPayloadSegmentWithPadding...`, `aPayloadSegmentWithNonZeroTrailingBits...`, y dos casos de `aMalformedTokenIsRejectedAsMalformed`: relleno y bits de cola en la firma) | Sí, `cmp` idéntico |
| 3 | `Ed25519Signatures.verify` deja escapar la `SignatureException` del JDK | `Ed25519SignaturesRfc8037Test`: 3 errores (`thePublishedSignatureWithAnyOneBitChangedIsRejected`, `theJdkThrowsFor...`, `aSignatureOf65BytesOrOfNoBytes...`); `CompactJwsAttackTest.theMalleableSignatureIsRejectedWithoutTheJdkExceptionEscaping` y 2 casos de `aTokenWhoseSignatureDoesNotVerify...`; `CompactJwsPropertiesTest.aTokenWithAnySingleBitChangedIsRejectedAsATokenRejection` | Sí, `cmp` idéntico |
| 4 | El códec analiza la carga **antes** de verificar la firma | `CompactJwsAttackTest.thePayloadIsNeverParsedBeforeTheSignatureIsVerified` y un caso de `aTokenWhoseSignatureDoesNotVerify...` | Sí, `cmp` idéntico |
| 5 | Longitud máxima `>` cambiada por `>=` (2048 caracteres) | `CompactJwsAttackTest.aTokenOfExactly2048CharactersIsAcceptedAndOneOf2049IsMalformed` | Sí, `cmp` idéntico |

### Ataques por la prohibición de bibliotecas (BI33)

La construcción `./mvnw verify` ejecuta el enforcer en la fase `validate`; la prueba se hizo con `./mvnw -pl app -am validate`, que es
exactamente esa regla.

| Paso | Observado |
|---|---|
| Descarga real de `com.nimbusds:nimbus-jose-jwt:9.47` | Falla con `PKIX path building failed` (el entorno no valida el certificado de Maven Central). No se tocó TLS ni ninguna verificación de certificados. |
| Alternativa del documento de tareas | Repositorio de archivos temporal (`<repositories>` provisional en `app/pom.xml`, fuera del repositorio de código) con artefactos **falsos** (POM mínimo y un JAR con un archivo de texto) de Nimbus, Tink, java-jwt y el starter de OAuth2 de recursos. `jjwt-api:0.12.6` resolvió desde el repositorio local. |
| ROJO (sin la prohibición) | Con la dependencia falsa de Nimbus: `Rule 2: BannedDependencies passed`, `BUILD SUCCESS`. La construcción no habría detenido a la biblioteca. |
| VERDE, Nimbus | Con `com.nimbusds:*` prohibido: `BUILD FAILURE`, `Rule 2: BannedDependencies failed ... com.nimbusds:nimbus-jose-jwt:jar:9.47 <--- banned via the exclude/include list`, con el mensaje que cita ADR-0015, ADR-0016, la decisión 4 de 4a y DA-2. |
| VERDE, jjwt | `io.jsonwebtoken:jjwt-api:jar:0.12.6 <--- banned via the exclude/include list`, `BUILD FAILURE`. |
| VERDE, Tink, java-jwt y starter OAuth2 | `com.google.crypto.tink:tink:jar:1.15.0`, `com.auth0:java-jwt:jar:4.4.0` y `org.springframework.boot:spring-boot-starter-oauth2-resource-server:jar:4.1.1`, los tres `<--- banned via the exclude/include list`, `BUILD FAILURE`. |
| Reversión | `app/pom.xml` restaurado desde la copia y confirmado con `cmp`; los artefactos falsos se borraron de `~/.m2/repository` (quedaron solo en el directorio de la sesión). La prohibición definitiva (`com.nimbusds:*`, `com.google.crypto.tink:*`, `io.jsonwebtoken:*`, `com.auth0:java-jwt`) y el mensaje con DA-2 sí quedan en `apps/api/pom.xml`. |

El escenario «Se añade el servidor de recursos OAuth2» de «Spring Security solo como cadena de filtros» queda observado con el starter
falso; «La dependencia de Spring Security converge» lo cubre `dependencyConvergence` en el `./mvnw verify` completo (en verde).
«El árbol de dependencias no añade ninguna dependencia Maven nueva de firma»: el diff de `apps/api/app/pom.xml` solo añade dos
líneas de PIT y un comentario.

### Mutación (PIT, perfil `mutation-report`, BI30 a BI32)

`./mvnw -pl app -am verify -Pmutation-report -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<las seis clases> -Djacoco.skip=true`:
`BUILD SUCCESS`. Se añadió `com.confia.shared.security.token.*` a `targetClasses` y `targetTests` de PIT en `apps/api/app/pom.xml`.

| Primera ejecución | Resultado |
|---|---|
| Paquete `shared.security.token` | 98 mutantes, 93 muertos, 5 vivos, todos en `CompactJws.hasThreeNonEmptyVisibleSegments`: la frontera `c < 0x21` (sin prueba que distinga `'!'` de un espacio), la condición `first < 0 ? -1 : ...` (redundante: si no hay punto, `indexOf` ya devuelve -1) y la comparación final `< 0` (equivalente) |

Se eliminó la condición redundante, se cambió la última por `== -1` y se añadió
`theFirstAndLastVisibleAsciiCharactersPassTheShapeStepAndTheNeighboursDoNot` (`'!'` y `'~'` pasan el paso 1 y fallan por cabecera
desconocida; espacio y DEL fallan el paso 1).

| Segunda ejecución | Resultado |
|---|---|
| Paquete `shared.security.token` | 95 mutantes, **94 muertos (98,9 %)**, 1 vivo. Mínimo exigido: 80. |

El mutante vivo es `RemoveConditionalMutator_EQUAL_IF` sobre `token.indexOf('.', second + 1) == -1` (línea 107 de `CompactJws`): un
token de cuatro segmentos pasa entonces el paso 1 y lo detiene el base64url estricto del paso 3 (el `.` no pertenece al alfabeto),
con la misma razón `MALFORMED`. **Mutante equivalente**: se conserva la comprobación porque el diseño exige «exactamente dos
puntos» y es la defensa explícita; no se puede distinguir por comportamiento observable.

### Cierre

`./mvnw verify` completo (con Docker): **`BUILD SUCCESS`**. Surefire: kernel 186 y app 1 355, Failsafe 250, sin fallos, errores ni
omitidas. `All coverage checks have been met.` (JaCoCo, global 80 y `domain` 95). `SpringModulithVerificationTest` en verde
(la `@NamedInterface` de `shared.security.token` con consumidores `bootstrap` e `identity`). Las instantáneas de OpenAPI no cambiaron
(`git status` sin cambios en `apps/api/openapi`). El escaneo de claves privadas por búsqueda de texto no halla ninguna: el recurso
`rfc8037/ed25519-a4.properties` contiene solo la clave pública, la entrada de firma y la firma publicada, con la cita del RFC; la clave
privada del apéndice A.1 (decisión del propietario del 2026-10-06) y cualquier PEM están ausentes de todo el árbol.

### Medición del PR 1 (`git add -N` y `git diff --numstat main -- . ':!openspec'`, luego `git reset -q`)

| Grupo | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| Producción `shared/security/token` (9 archivos) | 419 | 0 | 419 |
| `apps/api/pom.xml` (prohibiciones) | 11 | 1 | 12 |
| `apps/api/app/pom.xml` (PIT y comentario) | 6 | 1 | 7 |
| Pruebas: `CompactJwsAttackTest` | 561 | 0 | 561 |
| Pruebas: `JwsFixtures` | 161 | 0 | 161 |
| Pruebas: `Ed25519SignaturesRfc8037Test` | 128 | 0 | 128 |
| Pruebas: `SigningKeyRingInMemoryTest` | 131 | 0 | 131 |
| Pruebas: `Base64UrlTest` y `Base64UrlPropertiesTest` | 129 | 0 | 129 |
| Pruebas: `CompactJwsPropertiesTest` | 64 | 0 | 64 |
| Recurso `rfc8037/ed25519-a4.properties` | 19 | 0 | 19 |
| **Total** | **1 629** | **2** | **1 631** |

Pronóstico del documento de tareas: 330 nominales, 730 realistas; tope 800. **Excede el tope en 831 líneas (más del doble).** Regla
de `tasks.md`: el ejecutor se detiene, deja el árbol verificado sin comprometer y consulta al orquestador antes de partir. No se
partió por cuenta propia.

Costuras posibles que se miden aquí para la decisión (ninguna se aplicó): las propiedades de jqwik (`Base64UrlPropertiesTest` 75 y
`CompactJwsPropertiesTest` 64, 139 líneas) son la costura que el documento de tareas nombra (1b) y **no bastan**: quitan solo 139 y
dejan 1 492. Un corte coherente es (A) primitivas: `Base64Url`, `Ed25519Signatures`, recurso del vector, `JwsFixtures`, las pruebas de
base64url y del vector, unas 520 líneas; y (B) anillo, códec y ataques con las prohibiciones y PIT, unas 1 110 líneas, que aún supera 800
y pediría una tercera parte (anillo en memoria con su prueba, unas 280, frente a códec con ataques y propiedades, unas 830).

### Desviaciones del diseño y del documento de tareas (declaradas)

1. **Nombres de las clases de propiedades.** El documento de tareas nombra `CompactJwsProperties` y `Base64UrlProperties`. El Surefire del
   proyecto solo recoge `*Test`, `Test*`, `*Tests` y `*TestCase`, de modo que esas clases no se ejecutarían en `./mvnw verify`. Se
   llaman `CompactJwsPropertiesTest` y `Base64UrlPropertiesTest`, igual que `InMemoryRateLimiterPropertiesTest` de 4a. El comando enfocado
   de la tarea 1.1 debe leerse con esos nombres.
2. **Pruebas añadidas fuera de la lista.** `Base64UrlTest` (vectores de RFC 4648 y rechazos), `SigningKeyRingInMemoryTest` (forma del
   anillo: `kid` cerrado a `[A-Za-z0-9._-]{1,64}` porque se escribe en la cabecera sin escapes, claves repetidas, vigente sin privada,
   anterior con privada, claves que no son Ed25519) y `JwsFixtures` (apoyo: firma con el JDK directamente, sin pasar por el código
   bajo prueba). Sin ellas el anillo en memoria no tendría pruebas y PIT no llegaría a 80.
3. **Resultado del códec.** `CompactJws.verify` devuelve `VerifiedJws(kid, ObjectNode claims)`: el paso 5 de la decisión 2 analiza la
   carga con el `JsonMapper` propio (`STRICT_DUPLICATE_DETECTION`, `FAIL_ON_TRAILING_TOKENS`) y el escenario I57 exige que el códec
   rechace ya un cuerpo que no es objeto o que repite nombres. El paso 6 (lista cerrada de claims, tipos, tiempo) queda para el PR 4, que
   leerá el árbol; `FAIL_ON_UNKNOWN_PROPERTIES` y `FAIL_ON_NULL_FOR_PRIMITIVES` de la decisión 2 solo aplican a un enlace a clase y se
   decidirán allí (los nombres de Jackson 3 `StreamReadFeature.STRICT_DUPLICATE_DETECTION` y `DeserializationFeature.FAIL_ON_TRAILING_TOKENS`
   quedaron confirmados con esta ejecución).
4. **`Ed25519Signatures.verify` sin comprobación de longitud propia.** La longitud de 64 bytes la comprueba el códec en el paso 3, como
   fija el diseño; la primitiva atrapa la `SignatureException` del JDK (S-2) y responde `false`, lo que cubre también 63, 65 y 0 bytes.
5. **Prohibiciones por grupo.** `bannedDependencies` usa `com.nimbusds:*`, `com.google.crypto.tink:*`, `io.jsonwebtoken:*` y
   `com.auth0:java-jwt` (el grupo `com.auth0` es más ancho). `SuppressionCitesAdrTest` pasó: las entradas llevan `:`.
6. **BI33 con la fase `validate`.** Ver la sección de ataques por la prohibición.
7. **Tamaño.** 1 631 líneas efectivas frente a un tope de 800 y un pronóstico realista de 730: el pronóstico subestimó las pruebas de
   ataque (un caso por ataque con la razón exacta, 98 pruebas en 561 líneas). No se recortaron pruebas, comentarios ni espacios para
   acercarse al tope.

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Orden enfocada y resultado | `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Ed25519SignaturesRfc8037Test,CompactJwsAttackTest,CompactJwsPropertiesTest,Base64UrlPropertiesTest,Base64UrlTest,SigningKeyRingInMemoryTest'`: `Tests run: 155, Failures: 0, Errors: 0` (154 en el primer verde, 155 con la prueba de límites ASCII) |
| Arnés de ejecución | El códec con anillo en memoria y pares Ed25519 generados al ejecutar; `./mvnw verify` completo con Docker y perfil `mutation-report` para PIT |
| Frontera de reversión | Se retiran `apps/api/app/src/main/java/com/confia/shared/security/token/`, sus pruebas y el recurso `rfc8037/`, la entrada de PIT de `apps/api/app/pom.xml` y las cuatro prohibiciones de `apps/api/pom.xml` |

### Pendiente de esta tarea

Decisión del orquestador sobre la partición de la tarea 1.1 (1 631 líneas); después, los commits `feat(shared): add a closed-header
EdDSA compact JWS codec` y el `docs(sdd)` que marca 1.1 `[x]`. El árbol verificado está sin comprometer en la rama
`change/session-tokens-and-web-layer`. `tasks.md` **no** se marcó.

### Partición de 1.1 y PR 1a `jws-primitives` (2026-10-06)

La tarea 1.1 midió 1 631 líneas efectivas y se partió en 1.1a, 1.1b y 1.1c por decisión del propietario, que además aprobó la regla
general de partir sin consultar (nota fechada de `tasks.md`). La sección anterior describe el árbol completo, que se conserva en la rama
local `wip/session-tokens-jws-codec-full` (2461b4f). Cada parte toma de ese árbol sus archivos sin cambiarlos.

**PR 1a: 551 líneas.** Incluye `Base64Url`, `Ed25519Signatures`, `package-info`, `JwsFixtures`, `Base64UrlTest`,
`Base64UrlPropertiesTest`, `Ed25519SignaturesRfc8037Test`, el recurso del vector RFC 8037 A.4 (sin clave privada) y el objetivo de PIT.
Las evidencias de rojo y de las rupturas 2 y 3 son las de la sección anterior.

Verificación sobre el árbol de 1a: `./mvnw verify -Pmutation-gate` dio Surefire 186 + 1 236, Failsafe 250, 0 fallos, cobertura cumplida
y `BUILD SUCCESS`.

| Métrica de PIT | Resultado |
|---|---|
| Paquete `com.confia.shared.security.token` | 11 de 11 mutantes muertos (100 %) |
| Total de la aplicación | 247 de 262 (94 %) |

### Revisión independiente de 1.1a (2026-10-06)

Veredicto: apto para fusionar, sin bloqueantes ni importantes. La revisión confirmó:

- el decodificador es estricto y canónico: el único texto aceptado para unos bytes es su codificación canónica;
- la `SignatureException` del JDK se convierte en `false`, y cualquier otra excepción se propaga como error de configuración;
- no hay ninguna clave privada ni ningún secreto en el diff ni en las últimas 50 revisiones;
- `JwsFixtures` firma con el `Signature` del JDK y no con la clase bajo prueba.

**Corregido:**

- **S5.** El Javadoc del paquete describía el anillo y la prohibición de bibliotecas como presentes. Ahora dice que llegan en 1.1b y
  1.1c.
- **S1.** El Javadoc de `Base64Url.decode` advierte que no acota nada y que quien decodifique entrada de un cliente debe acotar antes la
  longitud.
- **S3.** La prueba nueva `aKeyThatIsNotEd25519IsAConfigurationErrorAndNeverAnInvalidSignature` cubre la clave X25519 en `sign` y en
  `verify`. Ruptura deliberada: con `return false` en lugar de la excepción, la prueba falla; se restauró y `cmp` confirmó el archivo
  idéntico. Las aserciones de longitud usan ahora `SIGNATURE_LENGTH`, lo que atiende en parte S2.

**Aceptado hasta 1.1c:** S4, porque los auxiliares de `JwsFixtures` sin uso en 1a son código de pruebas que 1.1c ejercita.

**Verificación.** `./mvnw verify -Pmutation-gate`: Surefire 186 + 1 237, Failsafe 250, 0 fallos y `BUILD SUCCESS`.

### PR 1b `jws-key-ring-and-bans` (2026-10-06)

Toma del árbol completo, sin cambios, los archivos `SigningKey`, `SigningKeyRing`, `SigningKeyRingInMemoryTest` y las prohibiciones de
`apps/api/pom.xml`. Además actualiza el Javadoc de `package-info`, que pasa a describir el anillo y la prohibición como presentes.

**Desviación respecto de la tarea 1.1b.** `TokenRejection` y `TokenRejectedException` pasan a 1.1c: en 1b ningún código las usaría ni
las probaría, que es la clase de código sin uso que señaló la sugerencia S4 de la revisión de 1a.

**Evidencias.** Son las registradas en la sección «Tarea 1.1»:

- **Ruptura deliberada:** con `keyForHeaderSegment` devolviendo siempre la clave actual, `SigningKeyRingInMemoryTest` da 2 fallos.
- **BI33:** el enforcer rechaza, nombrándolos, Nimbus JOSE, jjwt, Tink, java-jwt y el starter OAuth2 de recursos, con artefactos falsos en
  un repositorio temporal. La descarga real falló por PKIX y no se tocó TLS.

**Verificación.** `./mvnw verify -Pmutation-gate`: Surefire 186 + 1 256, Failsafe 250, 0 fallos, cobertura cumplida y `BUILD SUCCESS`.

| Métrica de PIT | Resultado |
|---|---|
| Paquete `com.confia.shared.security.token` | 41 de 43 mutantes muertos (95 %); líneas, 61 de 67 (91 %) |
| Total de la aplicación | 277 de 294 (94 %) |

**Tamaño:** 295 líneas efectivas.

### PR 1c `jws-compact-codec` (2026-10-06)

Toma del árbol completo, sin cambios, los archivos `CompactJws`, `VerifiedJws`, `TokenRejection`, `TokenRejectedException`,
`CompactJwsAttackTest` (98 casos) y `CompactJwsPropertiesTest`, y deja el Javadoc de `package-info` en su forma final. Con este PR la tarea
1.1 queda hecha y el árbol `wip/session-tokens-jws-codec-full` queda entregado completo. Solo difieren las correcciones de la revisión
de 1a.

**Evidencias.** Las rupturas 1, 4 y 5 de la sección «Tarea 1.1» son de este PR, todas revertidas y comprobadas con `cmp`:

| Ruptura | Pruebas que fallan |
|---|---|
| 1. El anillo devuelve siempre la clave actual | 39 fallos y 1 error en `CompactJwsAttackTest` |
| 4. La carga se analiza antes de la firma | Falla `thePayloadIsNeverParsedBeforeTheSignatureIsVerified` |
| 5. `>=` en la longitud | Falla el borde de 2 048 y 2 049 caracteres |

**Revisión independiente.** Veredicto: apto para fusionar, sin bloqueantes ni importantes. Confirmó:

- el orden de verificación, con la longitud acotada antes de cualquier decodificación (lo que cierra S1 de 1a);
- la comparación exacta de la cabecera y que ninguna clave sustituye a otra;
- que no hay ningún `catch` que lleve a aceptar un token;
- que las excepciones no llevan traza ni datos del token;
- que los ataques afirman el motivo exacto del rechazo.

Sus cinco sugerencias pasan a la tarea 1.4 (nota fechada en `tasks.md`).

**Verificación.** `./mvnw verify -Pmutation-gate`: Surefire 186 + 1 356, Failsafe 250, 0 fallos, cobertura cumplida y `BUILD SUCCESS`.

| Métrica de PIT | Resultado |
|---|---|
| Paquete `com.confia.shared.security.token` | 93 de 94 mutantes muertos (99 %); líneas, 110 de 114 (96 %) |
| Total de la aplicación | 329 de 345 (95 %) |

**Tamaño:** 799 líneas efectivas.

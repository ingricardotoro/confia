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

## Tarea 1.2 `signing-key-ring` (2026-10-07, modo TDD estricto, rama `change/session-tokens-and-web-layer-02-signing-key-ring`)

Árbol completo construido y verificado, **sin comprometer**: mide 1 127 líneas efectivas, por encima de 800. Siguiendo la regla fija del
propietario (nota fechada de la partición de 1.1), se guardó una copia local en `wip/session-tokens-signing-key-ring-full` y se mide la
partición de abajo. El orquestador entrega las partes.

### Ciclo TDD (rojos observados, con su causa)

| Paso | Qué se corrió | Causa observada |
|---|---|---|
| Rojo 1 | `./mvnw -q -pl app -am test-compile` con las pruebas y sin producción | Error de compilación por símbolos inexistentes: `SigningKeyRing.fromEnvironment`, `SigningKeyPlacementGuard` y `SessionTokenConfiguration` (`SigningKeyRingTest`, `SigningKeyPlacementGuardTest` y `ProcessBeanInspectorTest`) |
| Verde del anillo | `-Dtest='SigningKeyRingTest,...'` | `SigningKeyRingTest`, 31 casos en verde |
| Rojo de las líneas prohibidas | `ProcessBeanInspectorTest` antes de editar `ProcessBeanPolicy` | 2 fallos (portal y trabajador): la regla nominal no existía; solo habría fallado la lista de permitidos |
| Rojo del guardián | `SigningKeyPlacementGuardTest` con la fuente de variables de entorno nombrada `systemEnvironment-test` | 4 fallos, «Expecting code to raise a throwable». **Causa real:** el nombre de la fuente no terminaba en `-systemEnvironment`, así que Spring Boot no aplicaba el enlace relajado; es un defecto de la prueba, corregido con el nombre `guard-test-systemEnvironment`; no se tocó producción |
| Rojo de arranque sin cableado | `SigningKeyStartupTest` y `SigningKeyAbsencesTest` con el guardián y el `@Import` ausentes | 9 fallos y 3 errores en `SigningKeyStartupTest` (el proceso arrancaba o faltaba el bean `SigningKeyRing`); las ausencias de `SigningKeyAbsencesTest` pasan desde el inicio, como era de esperar: son rieles de ausencia y no pueden estar en rojo antes del cambio |
| Verde parcial | guardián añadido a `ConfiaApplication.launch` | quedan en rojo solo los 3 fallos y los 3 errores que dependen del anillo |
| **S-6** (primer rojo del cableado) | `@Import(SessionTokenConfiguration)` en `AdminApplication` sin cambiar `TestProcessArguments` | **16 errores y 2 fallos**: `ConfiaApplicationTest` 1, `OpenApiExposureByProfileTest` 4, `ProcessBeanIsolationTest` 3, `SigningKeyAbsencesTest` 5 y `SigningKeyStartupTest` 3 errores y 2 fallos. Causa: `BeanCreationException` con «the property confia.security.admin-signing.current.kid is required and was not set». Se corrige con el par generado en `TestProcessArguments` |
| Verde S-6 | `TestProcessArguments` añade el par con `KeyPairGenerator.getInstance("Ed25519")` | `ConfiaApplicationTest` 10, `OpenApiExposureByProfileTest` 8, `ProcessBeanIsolationTest` 10, `SigningKeyAbsencesTest` 6 y `SigningKeyStartupTest` 14, todos en verde |

**Rojo esperado que no se produjo (desviación).** El documento de tareas esperaba `not in the allow-list` nombrando
`com.confia.shared.security.token` al importar sin línea de política. No ocurre: la lista de permitidos del administrativo ya contiene
`com.confia.shared.security`, y una coincidencia incluye sus subpaquetes por diseño (`ProcessBeanPolicy.matches`). La línea exacta
`com.confia.shared.security.token` se añadió igualmente porque la comprobación de no vacuidad compara por igualdad, y su valor se
demuestra con la ruptura 3. La regla de la lista de permitidos (BI23) sigue demostrada por la prueba negativa permanente del inspector.

### Rupturas deliberadas (todas revertidas y comprobadas con `cmp`)

| Ruptura | Prueba y línea que fallan |
|---|---|
| 1. Se comenta `requireMatchingPair(privateKey, publicKey)` en `SigningKeyLoader.load` | `SigningKeyRingTest.aPairWhosePublicKeyDoesNotMatchThePrivateKeyIsDetectedBySigningAndVerifying`, línea 153 (1 fallo de 31) |
| 2. El guardián de portal y trabajador deja de listar `...admin-signing.previous.private-key` | `SigningKeyStartupTest.theOtherProcessesAbortWhenThePreviousAdministrativePrivateKeyIsPresent` (2 casos, línea 134 de `assertAbortsBeforeTheContext`) y `SigningKeyPlacementGuardTest.theNonAdministrativeGuardFindsTheAdministrativePrivateKeyInAnOperatingSystemVariable`, línea 47 |
| 3. Se quita `SessionTokenConfiguration.class` del `@Import` de `AdminApplication` | `ProcessBeanIsolationTest.registersOnlyItsAllowedBeans` línea 84 («non-vacuous: com.confia.shared.security.token must contribute a bean to the admin context») y `onlyTheAdministrativeProcessHoldsTheSigningKeyRing`, línea 178 |

### Verificación completa

`./mvnw verify -Pmutation-gate` (Docker en ejecución): Surefire 186 + 1 420, Failsafe 250, 0 fallos, cobertura cumplida y `BUILD SUCCESS`.
El escaneo de secretos (búsqueda de marcadores PEM y de los prefijos DER de Ed25519 PKCS#8 y X.509 sobre todos los archivos rastreados y nuevos,
salvo `openspec/`) no halla ninguna clave (BI29).

| Métrica de PIT | Resultado |
|---|---|
| Paquete `com.confia.shared.security.token` | 135 de 140 mutantes muertos (96 %); líneas, 180 de 188 (96 %); fuerza de prueba 97 % |
| Supervivientes nuevos | `SigningKeyLoader` 3 (`load`, `optional`, `requireMatchingPair`: la longitud y el relleno de la sonda aleatoria no son observables) y `SessionTokenConfiguration` sin cobertura de PIT (la cubren las pruebas de proceso, que no son objetivo de PIT) |
| Total de la aplicación | 371 de 391 (95 %) |

### Medición y partición

Medido con `git add -N . && git diff --numstat main -- . ':!openspec'` y `git reset -q`: **1 127 líneas** (1 116 añadidas, 11 borradas), con
32 de `docs/05`. Supera 800. Costura natural, la que ya nombraba el documento de tareas («guardián a 2b»):

| Parte | Contenido | Líneas |
|---|---|---|
| **2a `signing-key-ring`** | `SigningKeyLoader`, `SigningKey.isValidKid`, `SigningKeyRing.fromEnvironment`, `SessionTokenConfiguration`, `@Import` en `AdminApplication`, línea de la lista de permitidos del administrativo, par generado en `TestProcessArguments`, `SigningKeyRingTest`, la parte administrativa y de ausencia de `SigningKeyStartupTest`, `SigningKeyAbsencesTest` y la prueba de aislamiento del anillo | **775** (medida en un árbol aparte, con 91 pruebas focalizadas en verde) |
| **2b `signing-key-placement-guard`** | `SigningKeyPlacementGuard`, su alta en `ConfiaApplication.launch`, `SigningKeyPlacementGuardTest`, el resto de `SigningKeyStartupTest` (portal, trabajador y nombre reservado), las líneas prohibidas de portal y trabajador con su prueba del inspector y la nota de `docs/05` | **352** |

2b necesita 2a. Escenarios de 2a: I78 a I82, I84, BI21, BI22, BI28, BI29 y las dos ausencias. Escenarios de 2b: I83, I140 y BI22 (prohibidos nominales).
La verificación completa de 2a por separado no se corrió (solo las pruebas focalizadas); la del árbol completo sí.

### Desviaciones y notas

- El motivo de las líneas prohibidas se escribe en inglés (`administrative signing keys, ADR-0005 check 14 ...`), como el resto de
  `ProcessBeanPolicy`; el documento de tareas lo citaba en español.
- `SigningKeyStartupTest` ejercita el rechazo del portal y del trabajador y el nombre reservado con un valor de símbolos que ningún mensaje
  del código puede contener, y no con una clave generada: con 44 caracteres de Base64 aleatorio un fragmento de 4 caracteres podría
  aparecer por azar en una traza y volver inestable la prueba.
- `previous.public-key` sin `previous.kid` se rechaza (no se ignora en silencio); el diseño solo nombraba el caso inverso.
- `SigningKeyPlacementGuardTest` es una prueba unitaria añadida (no listada en la tarea) que demuestra el enlace relajado con una variable
  de entorno de sistema simulada; los procesos no pueden fijar variables de entorno desde la prueba.
- `SigningKey.isValidKid` pasa de privado a visible en el paquete para que el cargador valide con la misma regla y nombre la propiedad.

## PR 2a `signing-key-ring` (tarea 1.2a)

Árbol reducido a la parte 2a: se retiraron `SigningKeyPlacementGuard` y su prueba, su alta en `ConfiaApplication.launch`, los
cambios de `ProcessBeanInspectorTest` y la nota de `docs/05`; de `SigningKeyStartupTest` se retiraron las pruebas de portal, trabajador y
nombre reservado (y sus constantes); de `ProcessBeanPolicy`, las líneas prohibidas de portal y trabajador; de `TestProcessArguments`, las
constantes de las propiedades `previous.private-key` y del portal; el Javadoc de `package-info` ya no afirma el guardián.

- **Verificación completa** (`./mvnw verify -Pmutation-gate`, Docker en ejecución): Surefire 186 + 1 401, Failsafe 250, 0 fallos,
  cobertura cumplida, `BUILD SUCCESS`.
- **PIT, paquete `com.confia.shared.security.token`:** 131 de 136 mutantes muertos (96 %); líneas 166/174 (95 %); fuerza de prueba 97 %.
  Total de la aplicación: 367 de 387 (95 %).
- **Rupturas deliberadas de 2a** (1 y 3 de la sección anterior), revertidas y comprobadas con `cmp`; no queda ninguna en el árbol:
  1. Con `requireMatchingPair` comentada en `SigningKeyLoader.load` falla `SigningKeyRingTest.aPairWhosePublicKeyDoesNotMatchThePrivateKeyIsDetectedBySigningAndVerifying`, línea 153 (1 de 31).
  3. Sin `SessionTokenConfiguration.class` en el `@Import` de `AdminApplication` fallan `ProcessBeanIsolationTest.registersOnlyItsAllowedBeans` (línea 84, no vacuidad) y `onlyTheAdministrativeProcessHoldsTheSigningKeyRing` (línea 178).
- **Tamaño:** 762 líneas efectivas (753 añadidas, 9 borradas), con el mismo método de medición; por debajo de 800.

### Revisión independiente de 1.2a (2026-10-07)

Veredicto: apto para fusionar, sin bloqueantes ni importantes. La revisión confirmó:

- el cargador rechaza Ed448, RSA y Base64 inválido;
- ninguna clave `previous` lleva clave privada, ni siquiera vacía;
- la correspondencia del par se comprueba firmando una muestra aleatoria;
- ningún mensaje lleva valores ni causa encadenada;
- no hay registro;
- el anillo existe solo en el proceso administrativo, y portal y trabajador fallan en cerrado por la lista de permitidos;
- no hay material de clave en el diff.

**Corregido:** H1. El Javadoc de `SessionTokenConfiguration` afirmaba una prohibición explícita para portal y trabajador que llega en 1.2b;
ahora describe la garantía real, la ausencia en la lista de permitidos.

**Pasan a 1.2b** (nota fechada en `tasks.md`):

- la regla de orden: nada que emita o verifique tokens se fusiona antes que 1.2b;
- la prueba H2 de arranque con un valor malformado;
- la nota de valores con salto de línea;
- H3 y H4, opcionales.

## PR 2b `signing-key-placement-guard` (tarea 1.2b, 2026-10-07, modo TDD estricto)

Rama `change/session-tokens-and-web-layer-02b-signing-key-placement-guard`, desde `main` en 5b90c61 (con 1.2a fusionada). Contenido tomado de
`wip/session-tokens-signing-key-ring-full` (cda620a) y fusionado sobre las versiones de 2a, sin revertir la corrección H1.

### Ciclo TDD (rojos observados)

| Paso | Qué se corrió | Resultado observado |
|---|---|---|
| Rojo 1 | `./mvnw -pl app -am test-compile` con las pruebas y sin producción | Error de compilación: `SigningKeyPlacementGuard` no existe (9 símbolos en `SigningKeyPlacementGuardTest`) |
| Rojo 2 | Guardián creado pero sin alta en `ConfiaApplication.launch` ni líneas prohibidas; `-Dtest='SigningKeyPlacementGuardTest,SigningKeyStartupTest,ProcessBeanInspectorTest,SigningKeyAbsencesTest'` | 38 pruebas, **8 fallos**: `SigningKeyStartupTest` 6 (portal y trabajador con clave actual y previa, y los dos nombres reservados, línea 114 y `assertAbortsBeforeTheContext` línea 154) y `ProcessBeanInspectorTest` 2 (línea 96, sin línea nominal prohibida). Los 8 casos de `SigningKeyPlacementGuardTest` pasan porque prueban la clase aislada |
| Verde | alta del guardián, líneas prohibidas, `SessionTokenConfiguration` y `package-info` en presente | 51 pruebas focalizadas en verde (incluye `ProcessBeanIsolationTest`) |

**Seguimientos de la revisión de 1.2a.** H2 (dos casos de arranque real con `current.private-key` en Base64 válido que no es clave y con un sobre PKCS#8 de Ed448; `assertNoSecretFragment`) y H3 (camino arbitrario en `SigningKeyAbsencesTest`) pasan desde su primera ejecución: son rieles de regresión sobre el cargador de 2a y no pueden estar en rojo antes del cambio. Los valores son fijos y no aleatorios para que la afirmación de «ningún fragmento» no falle por azar. La nota de `docs/05` documenta el valor con salto de línea final y el PEM de varias líneas.

### Rupturas deliberadas (revertidas y comprobadas con `cmp`)

| Ruptura | Prueba y línea que fallan |
|---|---|
| El guardián de portal y trabajador deja de listar `...admin-signing.previous.private-key` | `SigningKeyStartupTest.theOtherProcessesAbortWhenThePreviousAdministrativePrivateKeyIsPresent` (2 casos, `assertAbortsBeforeTheContext` línea 154) y `SigningKeyPlacementGuardTest.theNonAdministrativeGuardFindsTheAdministrativePrivateKeyInAnOperatingSystemVariable`, línea 47 |
| El guardián administrativo deja de listar `...portal-signing.previous.private-key` | `SigningKeyStartupTest.theAdministrativeProcessAbortsWhenTheReservedPortalPrivateKeyNameIsPresent`, línea 114, y `SigningKeyPlacementGuardTest.theAdministrativeGuardFindsTheReservedPortalNameInAnOperatingSystemVariable`, línea 63 |

### Verificación completa

`./mvnw verify -Pmutation-gate` (Docker en ejecución): Surefire 186 + 1 423, Failsafe 250, 0 fallos, `BUILD SUCCESS`.
PIT, paquete `com.confia.shared.security.token`: 135 de 140 mutantes muertos (96 %), 4 sobrevivientes y 1 sin cobertura (los de 2a más el guardián sin
supervivientes nuevos relevantes); total de la aplicación 371 de 391 (95 %).

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **441 líneas** (431 añadidas, 10 borradas), por debajo de 800.

### Desviaciones

- **H4 no se hizo:** `JwsFixtures` es de paquete (`com.confia.shared.security.token`) y `SigningKeyStartupTest` vive en `com.confia.bootstrap`; usarlo exigiría hacer público un fixture entre paquetes. Se deja la cabecera escrita a mano.
- El valor Ed448 de H2 es un sobre PKCS#8 de Ed448 con cuerpo fijo patronado, no una clave generada, para no versionar material de clave ni introducir azar.
- Con 1.2b fusionada, la tarea 1.2 queda hecha y la regla de orden (ninguna emisión ni verificación de tokens antes de 1.2b) se cumple.

## PR 3 `signing-hash-confinement` (tarea 1.3, 2026-10-07, modo TDD estricto)

Rama `change/session-tokens-and-web-layer-03-signing-hash-confinement`, desde `main` en 0f6ec2e (con 1.1 y 1.2 fusionadas).

### Ciclo TDD (rojo observado)

| Paso | Qué se corrió | Resultado observado |
|---|---|---|
| Rojo | `SigningAndHashingConfinementTest` con los nueve fixtures y sin `Digests` | 6 pruebas, **3 fallos**. `productionCodeOutsideIdentityAndSharedSecurityNeitherSignsNorHashes` (línea 71) falla sobre producción real, «violated (2 times)», y las dos violaciones son de `com.confia.shared.audit.CanonicalAuditRowSerializer.sha256` (`MessageDigest.getInstance` y `MessageDigest.digest`, línea 259): ninguna otra clase. Fallan además `theAuditChainSerializerDelegatesItsHashToTheSecurityUtility` y la mitad de `shared.security` de `identityAndSharedSecurityReallyUseTheForbiddenUtilities` por la ausencia de `Digests`. La mitad de fixtures (siete rechazos por nombre, dos fixtures sin rechazo) pasa desde el inicio |
| Verde | `Digests`, delegación desde `CanonicalAuditRowSerializer` y `RequestPayloadHasher`, `DigestsTest` (3 vectores FIPS 180-4 y longitud) | `SigningAndHashingConfinementTest` 6, `DigestsTest` 4, `CanonicalAuditRowSerializerTest` 3, `RequestPayloadHasherTest` 5: 18 en verde. Las pruebas de la cadena de auditoría no se modificaron |

La regla no tiene lista de permitidos: `noClasses().that().resideOutsideOfPackages("com.confia.identity..", "com.confia.shared.security..")` (incluye subpaquetes como `shared.security.token`) `.should().dependOnClassesThat(...)` las siete utilidades. Evalúa solo clases de producción (`DO_NOT_INCLUDE_TESTS`), de modo que `JwsFixtures` y cualquier ayudante del árbol de pruebas no cuentan.

### Rupturas deliberadas (revertidas y comprobadas con `cmp`)

| Ruptura | Prueba y línea que fallan |
|---|---|
| `CanonicalAuditRowSerializer.rowHash` vuelve a usar `MessageDigest` | `SigningAndHashingConfinementTest.productionCodeOutsideIdentityAndSharedSecurityNeitherSignsNorHashes`, línea 71 (`CanonicalAuditRowSerializer.java:49`, `MessageDigest.getInstance` y `.digest`), y `theAuditChainSerializerDelegatesItsHashToTheSecurityUtility`, línea 134 |
| La regla deja de reconocer `javax.crypto.Mac` y `org.bouncycastle` | `rejectsTheFixtureOfEachOfTheSevenForbiddenUtilities` falla porque el mensaje no nombra `BadBouncyCastleOutside` (y tampoco `BadMacOutside`); además `identityAndSharedSecurityReallyUseTheForbiddenUtilities`, línea 101 |

Cada fixture se rechaza por nombre y por utilidad: la aserción de `rejectsTheFixtureOfEachOfTheSevenForbiddenUtilities` exige los siete nombres de clase y sus siete utilidades. `GoodCipherOutside` y `GoodSecureRandomOutside` no aparecen en el mensaje de rechazo.

### Verificación completa

`./mvnw verify -Pmutation-gate` (Docker en ejecución): Surefire 186 + 1 433, Failsafe 250, 0 fallos, `BUILD SUCCESS`. PIT total de la aplicación: 371 de 391 (95 %), 2 sin cobertura.

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **391 líneas** (369 añadidas y 22 borradas, todas del árbol `apps/api`), por debajo de 800.

### Desviaciones

- Los fixtures viven todos bajo `architecture.fixture.hashing.outside` (como los demás fixtures permanentes): `BadDigestOutsideIdentity` representa a una clase de `com.confia.shared.web` por estar fuera de los dos paquetes permitidos; no se coloca una clase de prueba en el paquete real `shared.web`.
- La regla cubre siete utilidades, no seis: `MessageDigest` (BI11) más las seis de BI38.
- Se añadió `DigestsTest` (vectores de FIPS 180-4) para fijar los bytes de la utilidad nueva; no figuraba en la tarea.
- `RequestPayloadHasher` está en `shared.security` y ya estaba permitido; delega igualmente en `Digests` para tener un único SHA-256 (la tarea lo pide).

## PR 4 `access-and-mfa-tokens` (tarea 1.4, 2026-10-07, modo TDD estricto)

Rama `change/session-tokens-and-web-layer-04-access-and-mfa-tokens`, desde `main` en 9b6f0a3 (con 1.1, 1.2 y 1.3 fusionadas). La tarea completa y verificada
midió **2 676 líneas efectivas**, por encima de 800: se conserva en la rama **local** `wip/session-tokens-access-tokens-full` y se parte por la regla
general del 2026-10-06 (ver «Medición» y la propuesta de partición).

### Ciclo TDD (rojos observados)

| Paso | Qué se corrió | Resultado observado |
|---|---|---|
| Rojo A (S1 a S4) | `-Dtest='CompactJwsHardeningTest,CompactJwsPropertiesTest'` contra el códec sin cambios | 26 pruebas de endurecimiento, **19 fallos**: los 10 casos de `sign` con carga que no es un objeto (arreglo, número, cadena, `null`, no JSON, vacía, dos objetos, nombre repetido, basura final, marca de orden de bytes) no lanzan `IllegalArgumentException` (S1); `sign` acepta una carga de 3 000 caracteres y un token de 2 049 (S1, línea 93); la copia de `VerifiedJws.claims()` no es independiente, `second.has("injected")` es verdadero (S2, línea 116); la carga se acepta en UTF-16 LE, UTF-16 BE, UTF-32 BE, UTF-16 con marca de orden, UTF-8 con marca de orden y con una barra sobrelarga `C0 AF` (S4, 6 de 8 casos) |
| Verde A | `CompactJws.sign` analiza la carga y mide el token; `VerifiedJws` privatiza el árbol y entrega una copia; la carga se decodifica con `CharsetDecoder` en `REPORT` | `CompactJwsHardeningTest` 27, `CompactJwsAttackTest` 98, `CompactJwsPropertiesTest` 2: 127 en verde |
| Rojo B | `test-compile` con las pruebas de claims, emisor, verificador, principal, regla `WebLayerTokenIsolationTest` y su fixture, sin producción | Error de compilación en 7 archivos de prueba: `AuthenticatedActor`, `AuthenticationMethod`, `AccessTokenIssuer`, `AccessTokenVerifier`, `AccessToken`, `MfaPurpose` y `MfaTokenClaims` no existen (`BadWebClassUsingVerifier`, `SigningKeyStartupTest`, `AuthenticatedActorTest`, las pruebas de propiedades, del emisor y del verificador) |
| Verde B | `ClaimReader`, `AccessTokenClaims`, `MfaTokenClaims`, `MfaPurpose`, `AccessToken`, `AccessTokenIssuer`, `AccessTokenVerifier`, `AuthenticatedActor`, `AuthenticationMethod` y los tres beans de `SessionTokenConfiguration` | 296 pruebas focalizadas en verde a la primera ejecución. Un error de la propia prueba (jqwik no tiene un generador por defecto de `UUID`) se corrigió con un `@Provide` |

**Rieles que no pueden estar en rojo antes del cambio.** S3 (carga de 600 niveles de anidamiento → `MALFORMED_CLAIMS`; token de 10 MB → `MALFORMED`) y S5 (la
propiedad del bit único afirma `MALFORMED`, `UNKNOWN_HEADER` o `BAD_SIGNATURE`) pasan desde la primera ejecución: el límite de anidamiento por defecto de
Jackson 3 ya es 500 y la longitud se comprueba antes de decodificar. El límite se declara ahora de forma explícita (`MAX_NESTING_DEPTH = 500`) para que no
dependa del valor por defecto de la biblioteca. Dos de los ocho casos de S4 (secuencia de sustituto codificada y secuencia truncada) tampoco fallaban: Jackson ya
los rechazaba.

**Opciones de Jackson 3 confirmadas.** `StreamReadFeature.STRICT_DUPLICATE_DETECTION` y `DeserializationFeature.FAIL_ON_TRAILING_TOKENS` (ya en el códec) bastan.
`FAIL_ON_UNKNOWN_PROPERTIES` y `FAIL_ON_NULL_FOR_PRIMITIVES` no se usan: las claims se leen como árbol, no se ligan a un tipo, y la lista cerrada la hace
`ClaimReader` (nombre por nombre, con el tipo JSON de cada una).

### Rupturas deliberadas (revertidas y comprobadas con `cmp`)

| Ruptura | Prueba y línea que fallan |
|---|---|
| `AccessTokenClaims.LIFETIME` de 600 a 601 s | `AccessTokenIssuerTest.anAccessTokenLivesExactlySixHundredSecondsAndReportsItsExpiry`, línea 74 (y otras cuatro del emisor: líneas 57, 88, 211 y 227); `AccessTokenVerifierTest` 10 pruebas (`aValidAccessTokenYieldsTheActorItsClaimsDescribe`, `expIsStrict...`, `thereIsNoClockLeewayOnExpiry`, línea 210, entre otras); `AccessTokenClaimsPropertyTest.whatTheIssuerSigns...`, línea 60 |
| Quitar la lista cerrada de `ClaimReader` (el constructor deja de comparar los nombres con los declarados) | `AccessTokenVerifierTest.aClaimThatIsNotDeclaredMakesTheTokenInvalidWhateverItsValue`, línea 159 (13 fallos en la clase); `MfaTokenVerifierTest.aRestrictedClaimOfTheWrongTypeOrValue...`, línea 126, y `theAccessVerifierRefusesARestrictedToken...`, línea 88; `AccessTokenClaimsPropertyTest.anyClaimAdded...`, línea 98; `MfaTokenClaimsPropertyTest.anyClaimAdded...`, línea 59 |

### Verificación completa

`./mvnw verify -Pmutation-gate` (Docker en ejecución): Surefire 186 + 1 641, Failsafe 250, 0 fallos, `BUILD SUCCESS`.
PIT, paquete `com.confia.shared.security.token`: **209 de 230 mutantes muertos (90,9 %)**, 17 sobrevivientes y 4 sin cobertura (los 4 son los métodos de fábrica de
`SessionTokenConfiguration`, que PIT no ejecuta porque corre solo pruebas unitarias; `SigningKeyStartupTest` los ejercita por arranque real). Total de la
aplicación: 445 de 481 (93 %). Los sobrevivientes nuevos están en `ClaimReader` (límites de `epochSecond` y de `texts`, equivalentes) y en `CompactJws.readObject`.

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **2 676 líneas** (2 654 añadidas y 22 borradas; 723 de producción, 1 924 de pruebas y 29 de `docs/03`).

Partición propuesta (cada parte se mide por archivo; los archivos compartidos se reparten por bloque):

| Parte | Contenido | Líneas |
|---|---|---|
| 4a `access-token-issuer` | `AuthenticatedActor`, `AuthenticationMethod`, `AccessToken`, serialización de `AccessTokenClaims`, `issueAccess`, `AuthenticatedActorTest`, `AccessTokenIssuerTest` (sin el viaje de ida y vuelta), fixtures del emisor | unas 690 |
| 4b `access-token-verifier` | `ClaimReader`, lectura de `AccessTokenClaims`, `verifyAccess`, los beans de `SessionTokenConfiguration`, `AccessTokenVerifierTest`, el viaje de ida y vuelta, la prueba de arranque real, nota de `docs/03` | unas 735 |
| 4c `restricted-mfa-token` (la costura nombrada) | `MfaTokenClaims`, `MfaPurpose`, `issueMfa`, `verifyMfa`, `mfaClaims` de los fixtures, `MfaTokenIssuerTest`, `MfaTokenVerifierTest`, `MfaTokenClaimsPropertyTest` | unas 630 |
| 4d `codec-hardening-and-claim-rules` | S1 a S5 (`CompactJws`, `VerifiedJws`, `CompactJwsHardeningTest`, `CompactJwsPropertiesTest`), `AccessTokenClaimsPropertyTest`, `WebLayerTokenIsolationTest` con `BadWebClassUsingVerifier` | unas 625 |

El endurecimiento (355 líneas) es independiente de las demás partes y puede ir primero; entonces la propiedad de claims de acceso y la regla de aislamiento
(272 líneas, ambas necesitan el verificador) quedan como quinta parte.

### Desviaciones

- **`AuthenticatedActor.CURRENT` es público.** El diseño lo dibuja de paquete, pero el filtro de la tarea 2.1 vive en `shared.web.authentication` y debe enlazarlo,
  igual que `RequestOrigin.CURRENT`.
- **El verificador lanza `TokenRejectedException`** en lugar de devolver un valor `Valid | Expired | Invalid` (diseño §6; la especificación deja la forma al diseño).
  `verifyAccess` devuelve el principal y `verifyMfa` las claims del restringido.
- **`WebLayerTokenIsolationTest` prohíbe todo el paquete `shared.security.token..`** a las clases `..web..`, no solo las cuatro clases nombradas, con las dos
  exenciones por nombre completo (el filtro, que llega en 2.1, y `AdminSecurityConfiguration`). El fixture vive en `fixture/token/web/` y no en `fixture/token/`,
  porque la regla se aplica a paquetes `..web..`.
- **`VerifiedJws` pasa de registro a clase final** con constructor de paquete y `claims()` que devuelve una copia profunda (S2); `toString` imprime solo el `kid`.
- **`sign` ahora analiza la carga** para exigir un objeto con nombres únicos en UTF-8 estricto (S1, S4); el costo es un análisis por emisión.
- **Las pruebas del restringido están en clases propias** (`MfaToken*Test`) para que la costura 4c sea una partición por archivo.
- Sin línea nueva en `ProcessBeanPolicy`: el paquete `shared.security.token` ya estaba permitido para administración y prohibido para portal y trabajador, y
  `SigningKeyStartupTest` afirma ahora que portal y trabajador tampoco tienen emisor ni verificador.
- Duplicados mínimos de ayudantes de aserción entre `AccessTokenVerifierTest` y `MfaTokenVerifierTest` (unas 20 líneas), a cambio de la partición por archivo.

## PR 4a `codec-hardening` (tarea 1.4a, 2026-10-07, modo TDD estricto)

Primera de las cinco partes de 1.4 (ver la nota fechada «partición de 1.4» en `tasks.md`). Se parte de `main` en 9b6f0a3; el árbol completo de 1.4 sigue
intacto en la rama local `wip/session-tokens-access-tokens-full` (c7456c6). Commit de código: `6421263`, `fix(shared): harden the JWS codec input, encoding and claims exposure`.

### Contenido

`CompactJws` (S1, S3, S4: `sign` rechaza cargas que no son objeto y tokens de más de 2 048 caracteres, decodificación UTF-8 estricta, límite explícito de
anidamiento), `VerifiedJws` (S2: clase final, constructor de paquete, `claims()` devuelve una copia profunda), `CompactJwsHardeningTest` (nuevo, 27 pruebas) y el
cambio S5 de `CompactJwsPropertiesTest`. Ningún archivo de 1.4b a 1.4e se incluye; no fue necesario adaptar `CompactJwsAttackTest` ni otros llamadores de `main`.

### Evidencia de trabajo

| Evidencia | Resultado |
|---|---|
| Rojo re-observado | Con `CompactJws` restaurado desde `main` (y `VerifiedJws` nuevo), `-Dtest=CompactJwsHardeningTest`: 27 pruebas, **18 fallos**. Se restauró y se comprobó con `cmp` |
| Verde y verificación completa | `./mvnw verify -Pmutation-gate` en `apps/api` (Docker en ejecución): Surefire 186 y 1 460, Failsafe 250, 0 fallos, `BUILD SUCCESS` |
| PIT, paquete `com.confia.shared.security.token` | **95 %** (145 de 152 mutantes muertos), cobertura de líneas 96 % (205 de 213); umbral de 80 cumplido |
| Límite de reversión | Los cuatro archivos Java de la parte; `git revert` del commit de código devuelve el códec a `main` sin tocar el resto |

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **391 líneas** (369 añadidas y 22 borradas), por debajo de 800.

### Desviaciones

Ninguna respecto al diseño. Los tres niveles de la sección «PR 4» anterior (rojo A, verde A, rupturas) siguen siendo el registro de la tarea completa; esta parte solo
reproduce su rojo A.

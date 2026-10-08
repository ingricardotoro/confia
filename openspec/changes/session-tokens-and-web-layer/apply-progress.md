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

## PR 4b `access-token-issuer` (tarea 1.4b, 2026-10-07, modo TDD estricto)

Segunda de las cinco partes de 1.4. Se parte de `main` en ffb2043 (que ya contiene 1.4a); el árbol completo de 1.4 sigue intacto en la rama local
`wip/session-tokens-access-tokens-full` (c7456c6). Commit de código: `0db43f1`, `feat(security): issue admin access tokens and carry the authenticated actor`.

### Contenido

`AuthenticatedActor` (con `CURRENT` como `ScopedValue` público y `current()` basado en `isBound()`), `AuthenticationMethod`, `AccessToken` (`toString` redactado),
la mitad de serialización de `AccessTokenClaims` y `AccessTokenIssuer.issueAccess`. Pruebas: `AuthenticatedActorTest` (10), `AccessTokenIssuerTest` (12, sin el viaje de
ida y vuelta, que pertenece a 1.4c) y los fixtures del emisor de `AccessTokenFixtures`. El emisor es una clase simple probada directamente: los beans llegan en 1.4c y
`ProcessBeanIsolationTest` sigue en verde. Nada referencia tipos de 1.4c a 1.4e (`ClaimReader`, verificador, `MfaPurpose`, `MfaTokenClaims`, `issueMfa`).

### Evidencia de trabajo

| Evidencia | Resultado |
|---|---|
| Rojo re-observado | Con las pruebas de 1.4b presentes y sus clases de producción ausentes, `test-compile` falla con errores de compilación `cannot find symbol` (`AuthenticatedActor`, `AccessTokenIssuer`, `AuthenticationMethod`) |
| Ruptura deliberada | `AccessTokenClaims.LIFETIME` de 600 a 601: `AccessTokenIssuerTest` falla en 4 de 12 pruebas. Se revirtió y se comprobó con `cmp`; no queda ninguna ruptura |
| Verde y verificación completa | `./mvnw verify -Pmutation-gate` en `apps/api` (Docker en ejecución): Surefire 186 y 1 482, Failsafe 250, 0 fallos, `BUILD SUCCESS` |
| PIT, paquete `com.confia.shared.security.token` | **95 %** (153 de 160 mutantes muertos); los 7 supervivientes pertenecen a `CompactJws`, `SigningKeyLoader` y `SessionTokenConfiguration`, ninguno a las clases de 1.4b; umbral de 80 cumplido |
| Límite de reversión | Los ocho archivos de la parte; `git revert` del commit de código la retira sin tocar el resto |

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **679 líneas** (todas añadidas), por debajo de 800.

### Desviaciones

Ninguna respecto al diseño. Los fixtures omiten, por pertenecer a 1.4c a 1.4e, `verifierAt`, `accessClaims`, `mfaClaims`, la clave ajena y los ayudantes de firma manual.

## PR 4c `access-token-verifier` (tarea 1.4c, 2026-10-07, modo TDD estricto)

Tercera de las cinco partes de 1.4. Se parte de `main` en ba169d4 (que ya contiene 1.4a y 1.4b); el árbol completo de 1.4 sigue intacto en la rama local
`wip/session-tokens-access-tokens-full` (c7456c6). Commits: `4cae9ff` (`feat(security): verify admin access tokens against a closed claim list`) y `8236f2c`
(`docs(security): note the in-house JWS and key rotation`).

### Contenido

`ClaimReader`, la mitad de lectura de `AccessTokenClaims` (`read`, `NAMES`, `methodsOf`), `AccessTokenVerifier.verifyAccess` (sin `verifyMfa`) y los beans de
`SessionTokenConfiguration` (códec, emisor y verificador). Pruebas: `AccessTokenVerifierTest` (99), el viaje de ida y vuelta emisor-verificador en
`AccessTokenIssuerTest` (13), los fixtures del verificador en `AccessTokenFixtures` (sin `mfaClaims`) y la ampliación de `SigningKeyStartupTest` (el proceso
administrativo tiene emisor y verificador; el portal y el trabajador, ninguno). Notas fechadas de `docs/03-seguridad.md` §4.5 y §11.2. Los comentarios de
`AccessTokenClaims` y `SessionTokenConfiguration` se redujeron a la ruta de acceso; nada referencia tipos de 1.4d ni 1.4e.

### Evidencia de trabajo

| Evidencia | Resultado |
|---|---|
| Rojo re-observado | Con las pruebas de 1.4c presentes y `AccessTokenVerifier` y `ClaimReader` ausentes, `test-compile` falla con `cannot find symbol` (`AccessTokenVerifier`, `verifierAt`) |
| Verde focalizado | `-Dtest='AccessTokenVerifierTest,AccessTokenIssuerTest,SigningKeyStartupTest'`: 129 pruebas, 0 fallos, `BUILD SUCCESS` |
| Ruptura deliberada | Se quitó la lista cerrada de claims del constructor de `ClaimReader` (solo `!claims.isObject()`): `AccessTokenVerifierTest` falla en 13 de 99 pruebas. Se revirtió y se comprobó con `cmp`; no queda ninguna ruptura |
| Verde y verificación completa | `./mvnw verify -Pmutation-gate` en `apps/api` (Docker en ejecución, 10 min 20 s): Surefire 186 y 1 583, Failsafe 250, 0 fallos, `BUILD SUCCESS` |
| PIT, paquete `com.confia.shared.security.token` | **90 %** (195 de 216 mutantes muertos); `ClaimReader` 74 % (31 de 42) y `AccessTokenVerifier` 100 %; umbral de 80 cumplido |
| Límite de reversión | Los nueve archivos Java de la parte y las notas de `docs/03`; `git revert` de los dos commits la retira sin tocar el resto |

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **754 líneas** (740 añadidas y 14 borradas), por debajo de 800.

### Desviaciones

Ninguna respecto al diseño. (Corregido tras la revisión independiente: la afirmación anterior de que las pruebas de propiedades de 1.4e cubren los supervivientes de `ClaimReader` no estaba verificada y se retira.)

### Revisión independiente de 1.4c (2026-10-07)

Veredicto: se puede fusionar, sin bloqueantes. La revisión confirmó estos puntos:

- La validación es por tipo JSON y nunca por coerción.
- La lista cerrada es exacta.
- `aud` como arreglo se rechaza.
- `amr` es exacto, en contenido y en orden.
- Los UUID se exigen en forma canónica (`toString().equals(texto)`).
- `EXPIRED` solo se informa con la firma y los claims íntegros.
- `exp == now` está vencido.
- El reloj es inyectado.
- Ninguna excepción acaba en aceptación.
- No hay registro.
- Los beans existen solo en el proceso administrativo.

**Corregido en este PR:**

- **I-1, desbordamiento de `long`.** Faltaba la prueba de un número fuera de `long`. Un entero 2^64 + un segundo válido se parsea como
  `BigIntegerNode`, y `longValue()` lo recortaría a ese segundo válido. Se añadieron siete casos a `accessClaimsOfTheWrongTypeOrValue`:
  - 2^64;
  - 2^64 + `exp` válido, y lo mismo para `iat`;
  - `Long.MIN_VALUE`;
  - un número de cuarenta dígitos;
  - `LAST_EPOCH_SECOND + 1`.
- **I-2, bordes del rango de fechas.** Faltaban esas pruebas. Se añadió `theFirstAndTheLastSecondOfTheDateRangeAreDates`: `iat = 0` da
  `EXPIRED`, nunca `CLAIMS_INVALID`, y el último segundo de 9999 se acepta verificado justo antes.

  **Rupturas deliberadas sobre `ClaimReader.epochSecond`**, revertidas y comprobadas con `cmp`:

  | Ruptura | Resultado |
  |---|---|
  | Sin `canConvertToLong()` | 4 fallos |
  | `value <= 0` | 1 fallo |
  | `value >= LAST_EPOCH_SECOND` | 1 error |

  `AccessTokenVerifierTest` pasa de 99 a 106 pruebas.
- **S-1.** La nota de `docs/03` §4.5 describía como presente el verificador del token restringido. Ahora dice que el rechazo de la
  audiencia `confia-admin-mfa` ya existe y que el token restringido, con su verificador, llega en 1.4d.
- **S-2.** Se retiró la afirmación no verificada sobre los supervivientes de `ClaimReader`.

**Para 1.4d (observación de la revisión):** el token de acceso y el restringido comparten clave y cabecera, y solo la audiencia y el
conjunto de claims los separan. 1.4d fija `aud` y sus claims propios, con una prueba cruzada en ambos sentidos.

## PR 4d `restricted-mfa-token` (tarea 1.4d, 2026-10-07, modo TDD estricto)

Cuarta de las cinco partes de 1.4. Se parte de `main` en 8fae156 (que ya contiene 1.4a, 1.4b y 1.4c con sus pruebas endurecidas tras la revisión); el árbol completo
sigue en `wip/session-tokens-access-tokens-full` (c7456c6). Commits: `d509a89` (`feat(security): issue and verify the restricted MFA token`) y `b705aa6`
(`docs(security): describe the restricted MFA token as delivered`).

### Contenido

`MfaTokenClaims` (`aud` = `confia-admin-mfa`, `exp - iat` = 300, `purpose` de un conjunto cerrado, sin `sid`), `MfaPurpose`, `AccessTokenIssuer.issueMfa` y
`AccessTokenVerifier.verifyMfa`, fusionados en las versiones de `main` que solo tenían `issueAccess` y `verifyAccess`. `ClaimReader` no cambia: el respaldo no tiene ruta
MFA propia en él. Se restituye `mfaClaims` en `AccessTokenFixtures`. Pruebas: `MfaTokenIssuerTest`, `MfaTokenVerifierTest` y `MfaTokenClaimsPropertyTest`. Los comentarios de
`AccessTokenClaims` y `SessionTokenConfiguration` vuelven a nombrar ambos tipos. Nota de `docs/03-seguridad.md` §4.5: el token restringido figura como presente. Emitir un
token restringido no escribe en ninguna tabla. Nada referencia tipos de 1.4e. No se perdió ningún endurecimiento de 1.4c (`AccessTokenVerifierTest` queda intacto).

### Prueba cruzada (requisito de la revisión de 1.4c)

El token de acceso y el restringido comparten clave y cabecera; solo `aud` y el conjunto de claims los separan. `MfaTokenVerifierTest` fija ambos sentidos con el
`TokenRejection` exacto (`CLAIMS_INVALID`) mediante `assertAccessRejected` y `assertMfaRejected`:

- `theRestrictedTokenIsRejectedByTheAccessVerifierAndTheAccessTokenByTheMfaVerifier`: un token MFA válido en `verifyAccess` y uno de acceso válido en `verifyMfa`.
- `...ForItsAudienceAlone`: cada uno con todos sus claims correctos y solo la audiencia cruzada.
- `theAccessVerifierRefusesARestrictedTokenWhateverClaimsAreAddedToMakeItLookLikeAnAccessOne`: un MFA disfrazado con `sid` y `exp` de acceso, con y sin audiencia de acceso.

### Evidencia de trabajo

| Evidencia | Resultado |
|---|---|
| Rojo re-observado | Con las pruebas MFA presentes y `MfaTokenClaims`, `MfaPurpose`, `issueMfa` y `verifyMfa` ausentes, `test-compile` falla con `cannot find symbol` |
| Verde focalizado | `-Dtest='MfaToken*Test,AccessToken*Test'` sin fallos |
| Ruptura deliberada | `MfaTokenClaims.LIFETIME` de 300 a 301: fallan 10 pruebas (4 de `MfaTokenIssuerTest`, 1 de `MfaTokenClaimsPropertyTest`, 5 de `MfaTokenVerifierTest`). Se revirtió y se comprobó con `cmp`; no queda ninguna ruptura |
| Verde y verificación completa | `./mvnw verify -Pmutation-gate` en `apps/api`: Surefire 186 y 1 640, Failsafe 250, 0 fallos, `BUILD SUCCESS` |
| PIT, paquete `com.confia.shared.security.token` | **92 %** (212 de 230 mutantes muertos; fuerza de las pruebas 94 %); umbral de 80 cumplido |
| Límite de reversión | Los archivos Java de la parte y la nota de `docs/03`; `git revert` de los dos commits la retira sin tocar el resto |

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **669 líneas** (650 añadidas y 19 borradas), por debajo de 800.

### Desviaciones

Ninguna respecto al diseño.

## PR 4e `claim-rules-and-web-isolation` (tarea 1.4e, cierre de la partición de 1.4)

Contenido: `AccessTokenClaimsPropertyTest` (jqwik), `architecture/WebLayerTokenIsolationTest` y el fixture permanente `architecture/fixture/token/web/BadWebClassUsingVerifier`. Se
trajeron de `c7456c6` solo esos tres archivos; el cambio de `AccessTokenVerifierTest` de esa rama no se trajo, porque `main` ya conserva sus pruebas de `epochSecond` (más completas).

### Decisión sobre las exenciones de la regla BI14

- La regla es no vacía: la prueba `theRuleEvaluatesRealProductionWebClasses` exige que el conjunto evaluado contenga `AdminSecurityConfiguration` y `ProblemBody`, y la mitad de fixture exige que
  `BadWebClassUsingVerifier` sea rechazada nombrando los cuatro tipos (`AccessTokenVerifier`, `CompactJws`, `SigningKeyRing`, `AccessTokenIssuer`).
- Se eximen dos clases por nombre completo (y sus clases internas): `AccessTokenAuthenticationFilter`, que llega con la tarea 2.1, y `AdminSecurityConfiguration`. Ninguna otra.
- Eximir por nombre una clase inexistente es frágil, así que se añadió `everyExemptNameIsARealClassOrIsListedAsPending`: cada nombre eximido debe existir o figurar en `PENDING_EXEMPT_CLASSES`,
  y una entrada pendiente cuya clase ya exista hace fallar la prueba (obliga a retirarla de la lista cuando llegue 2.1). Un error tipográfico queda así detectado y no exime en silencio.

### Evidencia de trabajo

| Evidencia | Resultado |
|---|---|
| Rojo re-observado | Fixture rechazado por nombre (`BadWebClassUsingVerifier`) con los cuatro tipos; propiedad de claims en rojo al debilitar la lista cerrada (abajo) |
| Verde focalizado | `-Dtest='WebLayerTokenIsolationTest,AccessTokenClaimsPropertyTest'`: 5 y 4 pruebas, 0 fallos |
| Ruptura 1 | Regla con `..web..` cambiado por `..nowhere..`: fallan `rejectsTheFixtureWebClassThatVerifiesATokenItself` y la mitad de producción (regla sin clases evaluadas). Revertida, `cmp` limpio |
| Ruptura 2 | `ClaimReader` línea 38 `equals(declared)` a `containsAll(declared)`: falla `anyClaimAddedToTheClosedListMakesTheTokenInvalid`. Revertida, `cmp` limpio |
| Ruptura 3 (informativa) | `declared.containsAll(propertyNames)` (permite faltantes): ninguna prueba de la propiedad falla, porque la lectura tipada de cada claim ya rechaza la ausencia; no se declara cubierta por esa propiedad |
| Verificación completa | `./mvnw verify -Pmutation-gate`: Surefire 1 649 (antes 1 640) y Failsafe 250, 0 fallos, `BUILD SUCCESS` |
| PIT, paquete `com.confia.shared.security.token` | 93 % (448 de 481 mutantes muertos), umbral de 80 cumplido |
| PIT, `ClaimReader` | Antes (main, medido en un `verify` limpio): 34 de 42 muertos, 8 supervivientes. Después: 34 de 42, los mismos 8. `AccessTokenClaimsPropertyTest` no mata ninguno |
| Límite de reversión | Los tres archivos de prueba; `git revert` del commit los retira |

Supervivientes de `ClaimReader` (líneas 38, 51, 81, 85, 94, 99; `RemoveConditionalMutator`) siguen abiertos; no se afirma cobertura que no se midió.

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **297 líneas** (297 añadidas, 0 borradas), por debajo de 800.

### Desviaciones

Ninguna respecto al diseño. La partición 1.4a a 1.4e queda completa y la casilla 1.4 se marca hecha.

## PR 5a `token-codes-and-authenticated-list` (tarea 2.1a, 2026-10-07, modo TDD estricto)

Primera de las tres partes de 2.1 (ver la nota fechada «partición de 2.1» en `tasks.md`). Se parte de `main` en 1d19245; el árbol completo de 2.1 sigue intacto en la
rama local `wip/session-tokens-bearer-filter-full` (b98ca4d). Commit de código: `21ba138`, `feat(web): add token-invalid and token-expired codes and the authenticated route list`.

### Honestidad sobre el rojo

El agente anterior se detuvo sin registrar nada de 2.1, de modo que el rojo de las pruebas nuevas ya no se puede observar en su orden original. Por eso la evidencia de
estricto TDD de toda la tarea 2.1 son las cuatro rupturas deliberadas de abajo, hechas sobre el árbol completo (b98ca4d) antes de partirlo. No se afirma un rojo previo
que no se observó.

### Contenido de 2.1a

`AccessTokenRejectedException`, `AccessTokenExpiredException`, `AuthenticatedEndpoint`, `AuthenticatedEndpoints` (vacía en producción), `SessionCheck`, `package-info`
(`@NamedInterface`); `token-invalid` y `token-expired` en `ProblemCode` y en `problems.properties` (es-HN); `ProblemAuthenticationEntryPoint` con tres casos por tipo de
excepción; segundo constructor de `SecurityChains` (públicas, autenticadas, `denyAll()` al final) y su uso desde `AdminSecurityConfiguration` con la lista vacía. Pruebas:
`ProblemCodeTest`, inversión de BI19 en `ProblemCatalogCoverageTest` (con los controles negativos), `ProblemAuthenticationEntryPointTest`, `AuthenticatedEndpointsTest` y
`ALLOWED_ROUTE_RULES = {permitAll, authenticated, denyAll}` en `WebEdgeScopeExclusionInventoryTest`. No hay filtro: ninguna ruta se autentica todavía y nada eleva las
excepciones; `WebLayerTokenIsolationTest` conserva el filtro en `PENDING_EXEMPT_CLASSES` hasta 2.1b. El comentario de `ProblemCode` no menciona `WWW-Authenticate` (llega en 2.1c).

### Rupturas deliberadas (sobre la tarea completa, `-Dtest` acotado)

Cada una se revirtió y se comprobó con `cmp`; tras la última el árbol coincidía con b98ca4d.

| # | Ruptura | Resultado |
|---|---|---|
| 1 | `AccessTokenExpiredException` sustituida por la de rechazo en el filtro | `AccessTokenAuthenticationFilterTest`: 26 pruebas, 2 fallos: `anExpiredTokenWithAnIntactSignatureIsTokenExpired` y `aBrokenCredentialOnAPublicRouteIsStill401WithTheCause` |
| 2 | El filtro lee también `?access_token=` | `BearerCredentialConfinementTest`: 6 pruebas, 1 fallo: `aCredentialOutsideTheAuthorizationHeaderIsIgnored`. La primera ejecución se hizo con `AccessTokenAuthenticationFilterTest`, que no contiene esa comprobación, y pasó; el caso vive en la clase de confinamiento (2.1c) |
| 3 | Sin `setHeader` de `WWW-Authenticate` en `ProblemResponses` | 105 pruebas de 7 clases, **28 fallos** (la tarea decía doce): 16 de `AccessTokenAuthenticationFilterTest`, 4 de `WwwAuthenticateChallengeTest`, 4 de `ProblemResponsesTest`, 3 de `ProblemAuthenticationEntryPointTest`, 1 de `PortalChainBearerTest` |
| 4a | Filtro retirado de `AdminSecurityConfiguration` | `ConfiaApplicationTest`: 11 pruebas, 1 fallo: `theAdminChainAuthenticatesBearerTokensAndThePortalChainDoesNot`. Las pruebas por HTTP no lo detectan porque el arnés construye su propia cadena con el mismo filtro; la cadena real la cubre esta prueba |
| 4b | Filtro neutralizado (pasa la petición sin autenticar) | 64 pruebas, 21 fallos: 19 de `AccessTokenAuthenticationFilterTest` y 2 de `AdminSecurityChainTest` (`aRegisteredRouteNobodyAddedToEitherListIsDeniedEvenToAValidToken`, `aBrokenBearerCredentialIsNotTheUniformDenialAnymoreAndSaysSo`) |

### Evidencia de trabajo (2.1a)

| Evidencia | Resultado |
|---|---|
| Verde focalizado | Incluido en la verificación completa de abajo; sin filtro no hay prueba por HTTP en esta parte |
| Verde y verificación completa | `./mvnw verify -Pmutation-gate` en `apps/api` (Docker en ejecución): Surefire 186 y 1 662, Failsafe 250, 0 fallos, `BUILD SUCCESS` |
| Instantánea de OpenAPI | `OpenApiContractSnapshotTest` 10 pruebas en verde; el archivo de la instantánea no cambió |
| PIT | Núcleo 99 % (192 de 194 mutantes); paquete `com.confia.shared.security.token` 93 % (448 de 481); umbral de 80 cumplido |
| Límite de reversión | Los archivos de la parte; `git revert` del commit de código devuelve la cadena a `main` sin tocar el resto |

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **398 líneas**, por debajo de 800. Medición de las otras dos partes sobre árboles intermedios, sin construirlas:
2.1b unas 800 (803 con las aserciones de `WWW-Authenticate`, que pasan a 2.1c) y 2.1c unas 380; la suma supera 1 566 por unas 14 líneas porque `AdminSecurityConfiguration`,
`ProblemCode` y `ProblemAuthenticationEntryPointTest` se tocan en dos partes.

### Desviaciones

La tarea se parte en tres y no en dos: filtro, cadena y lista con sus pruebas suman 1 194 líneas. Sin otras desviaciones respecto al diseño. Quedan 2.1b y 2.1c; 2.1b está al
límite del tope y puede necesitar mover `AdminSecurityChainTest` y `ConfiaApplicationTest` a 2.1c.

## PR 5b `bearer-authentication-filter` (tarea 2.1b, 2026-10-07, modo TDD estricto)

Segunda de las tres partes de 2.1. Se parte de `main` en 6f6a9c4 (que ya contiene 2.1a); la fuente de verdad sigue siendo la rama local
`wip/session-tokens-bearer-filter-full` (b98ca4d), de la que se trajeron los archivos con `git show`. Commit de código: `661d73b`,
`feat(web): authenticate the admin chain with a bearer access token filter`.

### Contenido de 2.1b

`AccessTokenAuthenticationFilter` y `ActorAuthentication`; el filtro se construye con `new` en `AdminSecurityConfiguration` y se añade con
`addFilterBefore(..., AnonymousAuthenticationFilter.class)` (no es un bean). Arnés: `HarnessTokens`, rutas autenticadas (`/test/whoami`, `/test/live`) y pública
(`/test/actor`) en `HarnessController`, `Calls`, `HarnessProcess` y `WebEdgeHarness` (la cadena del portal del arnés no lleva el filtro). Pruebas:
`AccessTokenAuthenticationFilterTest` (26), ampliación de `AdminSecurityChainTest` (34), `ConfiaApplicationTest` (11), el cambio de `SensitiveDataLoggingTest`
(la petición rota a `/test/boom` ya no envía `Bearer`, porque ahora se rechazaría antes del controlador), `AuthenticatedActor` en el inventario de
`IdempotencyScopeExclusionInventoryTest` y `PENDING_EXEMPT_CLASSES` vacía en `WebLayerTokenIsolationTest`. `SECRETO-TOKEN` ausente de todo registro a `TRACE` se comprueba en
`AccessTokenAuthenticationFilterTest` y `SensitiveDataLoggingTest`.

### Aserciones que pasan a 2.1c

Las tres comprobaciones de la cabecera `WWW-Authenticate` de `AccessTokenAuthenticationFilterTest` (en `assertInvalid`, en el caso sin credencial y en el caso vencido) y la
constante `INVALID`; el caso sin credencial se renombró a `noCredentialIsAnonymousAndGetsTheUniformDenial`. 2.1c las repone junto con `ProblemResponses`.
`AdminSecurityChainTest` y `ConfiaApplicationTest` se quedaron en 2.1b: la medición cabe en el tope.

### Rupturas deliberadas (árbol de 2.1b, `-Dtest` acotado)

Cada una se revirtió y se comprobó con `cmp`.

| # | Ruptura | Resultado |
|---|---|---|
| 1 | `AccessTokenExpiredException` sustituida por la de rechazo en el filtro | `AccessTokenAuthenticationFilterTest`: 26 pruebas, 2 fallos: `anExpiredTokenWithAnIntactSignatureIsTokenExpired` y `aBrokenCredentialOnAPublicRouteIsStill401WithTheCause` |
| 2 | Filtro retirado de `AdminSecurityConfiguration` | `ConfiaApplicationTest`: 11 pruebas, 1 fallo: `theAdminChainAuthenticatesBearerTokensAndThePortalChainDoesNot` (es la única que lo detecta: el arnés construye su propia cadena) |
| 3 | Filtro neutralizado (pasa la petición sin autenticar) | 75 pruebas, 21 fallos: 19 de `AccessTokenAuthenticationFilterTest` y 2 de `AdminSecurityChainTest` |

La ruptura de `?access_token=` y la de `WWW-Authenticate` pertenecen a 2.1c.

### Evidencia de trabajo (2.1b)

| Evidencia | Resultado |
|---|---|
| Verde focalizado | `-Dtest='AccessTokenAuthenticationFilterTest,AdminSecurityChainTest,ConfiaApplicationTest,WebLayerTokenIsolationTest,SensitiveDataLoggingTest,IdempotencyScopeExclusionInventoryTest'`: 83 pruebas, 0 fallos |
| Verificación completa | `./mvnw verify -Pmutation-gate` en `apps/api` (Docker en ejecución): Surefire 186 y 1 692, Failsafe 250, 0 fallos, `BUILD SUCCESS` |
| Instantánea de OpenAPI | `OpenApiContractSnapshotTest` 10 pruebas en verde; el archivo de la instantánea no cambió |
| PIT | Núcleo 99 % (192 de 194); paquete `com.confia.shared.security.token` 93 % (448 de 481) |
| Límite de reversión | Los archivos de la parte; `git revert` del commit de código devuelve la cadena administrativa a 2.1a |

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **798 líneas**, por debajo de 800. Queda 2.1c (unas 380).

### Revisión independiente de 2.1b (2026-10-07)

Veredicto: apto para fusionar, sin bloqueantes. La revisión no encontró ninguna ruta que acepte una credencial rota, que devuelva 500
ante una cabecera mal formada o que filtre el token. Comprobó además:

- **Análisis de la cabecera.** Se exige exactamente `Bearer<SP><token68>`, sin ReDoS. Dos cabeceras dan `token-invalid`.
- **`OncePerRequestFilter`.** El filtro se ejecuta una sola vez por petición.
- **`ScopedValue`.** El actor solo queda ligado mientras dura la llamada.
- **Sin estado.** No se crea sesión ni cookie.
- **`ActorAuthentication`.** No tiene autoridades y `getCredentials() == null`.
- **Filtro fuera de los beans.** No es un bean y solo está en la cadena administrativa.
- **Producción.** `AuthenticatedEndpoints` sigue vacía, así que el filtro solo puede rechazar o dejar pasar como anónimo.

**Corrección de un registro anterior.** El informe de 2.1b afirmó que `SECRETO-TOKEN` se comprueba ausente a nivel `TRACE` en
`AccessTokenAuthenticationFilterTest`. Esa comprobación **no existe**: la prueba del filtro no captura registros. Lo que sí sigue cubierto
es que `SensitiveDataLoggingTest` envía `Bearer SECRETO-A` a `/test/open` y a `/x` con `TRACE` activo, y el filtro los rechaza.

**Pasan a 2.1c** (nota fechada en `tasks.md`): I-1 (ruta 500 con token válido), I-2 (`clearContext()` y prueba de que el contexto no pasa a
la petición siguiente), S-1 y S-3. S-2 queda como riesgo aceptado, a decidir por `confia-architect`.


## PR 5c `www-authenticate-and-credential-confinement` (tarea 2.1c, 2026-10-07, modo TDD estricto)

Tercera y última parte de 2.1. Se parte de `main` en 917062f (ya contiene 2.1a y 2.1b); la fuente de verdad es la rama local
`wip/session-tokens-bearer-filter-full` (b98ca4d), de la que se trajeron los archivos con `git show` (sin `checkout`). Commits de código: `df8b3a6`
(`feat(web): add WWW-Authenticate on every 401 and confine the bearer credential to its header`) y `3209608`
(`fix(web): clear the security context after each request and treat an empty Authorization header as an invalid token`). Con esta parte la tarea 2.1 queda completa.

### Contenido tomado de la copia de respaldo (A)

`WWW-Authenticate` fijada en un solo lugar, `ProblemResponses.challengeOf`: `Bearer` para `authentication-required`, `Bearer error="invalid_token"` para
`token-invalid` y `token-expired`, sin `realm` ni `error_description`; el resto de los códigos no lleva desafío. Pruebas: `ProblemResponsesTest`,
`WwwAuthenticateChallengeTest`, `PortalChainBearerTest`, `BearerCredentialConfinementTest`, y las aserciones de la cabecera repuestas en
`AccessTokenAuthenticationFilterTest` y `ProblemAuthenticationEntryPointTest`. Se corrigió además el Javadoc de `ProblemCode`.

### Seguimientos de la revisión independiente (B), nuevos en esta parte

| # | Cambio | Pruebas |
|---|---|---|
| I-1 | Rutas del arnés `/test/fail-open` (pública) y `/test/fail-authenticated` (autenticada, `TOKEN_ONLY`), ambas lanzan una excepción con un marcador | `SensitiveDataLoggingTest.logOf` envía un token válido (`process.tokens().access()`) a las dos con `TRACE` activo; el token, su segmento de carga y la carga decodificada se añaden a los secretos; se afirma que ambas trazas existen (no vacuo) y que ningún secreto aparece |
| I-2 | El trabajo del filtro va en `try { ... } finally { SecurityContextHolder.clearContext(); }` | `AccessTokenAuthenticationFilterContextTest` (nueva, 10 casos con `MockHttpServletRequest`/`MockFilterChain`): token válido y luego ninguno en el mismo hilo, cadena que falla, petición anónima con un contexto previo |
| S-1 | Cabecera `Authorization` presente pero vacía o solo de espacios: `401 token-invalid` (antes, anónima). Otros esquemas, como Basic, siguen anónimos | HTTP: cabecera vacía en ruta autenticada y en pública, `Bearer<TAB><token>`; unitaria: `""`, `" "`, `"   "`, `"\t"`, `Bearer<TAB>`, Basic anónimo. Nota de la decisión 5 de `design.md` actualizada (pasos 1, 2 y 4) |
| S-3 | `ActorAuthentication.actor` pasa a `transient`, con comentario | Prueba por reflexión en `AccessTokenAuthenticationFilterContextTest` |

### ROJO observado (antes de tocar el código de producción)

`-Dtest='AccessTokenAuthenticationFilterContextTest,SensitiveDataLoggingTest,AccessTokenAuthenticationFilterTest'`, con las pruebas y las rutas del controlador pero sin
registrarlas en el arnés ni cambiar el filtro:

- `AccessTokenAuthenticationFilterContextTest`: 10 pruebas, 8 fallos (los tres de `clearContext`, el de `transient` y los cuatro de cabecera vacía o en blanco; pasan `Bearer<TAB>` y Basic, que ya eran correctos).
- `AccessTokenAuthenticationFilterTest`: 29 pruebas, 2 fallos (`aMalformedBearerHeaderIsTokenInvalid[9]` con `""` y `anEmptyAuthorizationHeaderIsTokenInvalidOnAPublicRouteToo`).
- `SensitiveDataLoggingTest`: 1 fallo, `noEventOfAnyRequestCarriesTheAuthorizationTheCookieOrTheBody` (sin traza de las rutas que fallan).

VERDE: mismo comando más las pruebas de la parte A, sin fallos.

### Rupturas deliberadas (`-Dtest` acotado)

Cada una se revirtió y se comprobó con `cmp`; no queda ninguna.

| # | Ruptura | Resultado |
|---|---|---|
| 1 | Se quita `clearContext()` del `finally` del filtro | `AccessTokenAuthenticationFilterContextTest`: 10 pruebas, 3 fallos: `thePrincipalOfOneRequestNeverReachesTheNextRequestOnTheSameThread`, `theContextIsClearedWhenTheRestOfTheChainFails`, `anAnonymousRequestAlsoLeavesTheThreadClean` |
| 2 | El filtro lee también `?access_token=` | `BearerCredentialConfinementTest`: 6 pruebas, 1 fallo: `aCredentialOutsideTheAuthorizationHeaderIsIgnored` |
| 3 | Se quita `response.setHeader("WWW-Authenticate", ...)` de `ProblemResponses` | 19 fallos de `AccessTokenAuthenticationFilterTest`, 1 de `PortalChainBearerTest`, 3 de `ProblemAuthenticationEntryPointTest` y 4 de `ProblemResponsesTest` (y los de `WwwAuthenticateChallengeTest`, cortados en la salida) |

### Evidencia de trabajo (2.1c)

| Evidencia | Resultado |
|---|---|
| Verde focalizado | Las ocho clases de la parte (`AccessTokenAuthenticationFilterContextTest`, `SensitiveDataLoggingTest`, `AccessTokenAuthenticationFilterTest`, `WwwAuthenticateChallengeTest`, `BearerCredentialConfinementTest`, `PortalChainBearerTest`, `ProblemResponsesTest`, `ProblemAuthenticationEntryPointTest`): sin fallos |
| Verificación completa | `./mvnw verify -Pmutation-gate` en `apps/api` (Docker en ejecución, 12 min 37 s): Surefire 186 y 1 729, Failsafe 250, 0 fallos, `BUILD SUCCESS` |
| Instantánea de OpenAPI | El archivo de la instantánea no cambió (`git status` limpio tras la construcción, sin diferencias en `apps/api/openapi` respecto de `main`) |
| PIT | Paquete `com.confia.shared.security.token` 93 % (448 de 481), fuerza de pruebas 94 %; umbral de 80 cumplido (la construcción pasó la compuerta) |
| Límite de reversión | `git revert` de `3209608` retira los seguimientos B; `git revert` de `df8b3a6` retira el desafío y la prueba de confinamiento sin tocar el filtro de 2.1b |

### Medición

`git add -N . && git diff --numstat main -- . ':!openspec'`: **671 líneas** (644 añadidas, 27 borradas), por debajo de 800. Las dos partes anteriores quedaron en 398 y 798.

### Desviaciones y riesgos

- Sin desviaciones respecto al diseño; la nota de la decisión 5 se actualizó para reflejar S-1 e I-2.
- S-2 sigue como riesgo aceptado, a decidir por `confia-architect`: la verificación Ed25519 ocurre antes del limitador de tasa.
- Tarea 2.1 completa (2.1a, 2.1b, 2.1c). Siguiente: 2.2.

# Diseño: endpoints de recuperación de contraseña (parte 4b, S3)

- **Cambio:** `password-recovery-endpoints` (F0, cambio 7, parte 4b, tercer cambio de tres)
- **Fase:** diseñar
- **Fecha:** 2026-11-09
- **Estado:** pendiente de aprobación del propietario (sección 13)
- **Entradas:** `proposal.md` (aprobada el 2026-11-09 con P1 a P7 y las restricciones acordadas con el cambio 14),
  `exploration.md`, los deltas `specs/identity/spec.md`, `specs/web-edge/spec.md` y `specs/build-integrity/spec.md`,
  el diseño archivado de `session-endpoints-cookie-and-csrf` (S2), `docs/03-seguridad.md` §4.6, §4.7, §8.2 y §10,
  y el código de la rama `change/password-recovery-endpoints`, rebasada sobre `origin/main` en `af20884`.
- **Supuesto de base (P7):** el código posterior a la parte 5a de rbac. `ResetPasswordWithToken` decide «MFA activa»
  con `MfaPolicy` y la credencial TOTP verificada, sin leer `mfa_required`. Nada de este diseño depende de la
  columna; los fixtures se escriben con los ayudantes de siembra que `main` tenga al aplicar.
- **Modo de TDD:** estricto. Ejecutor: `./mvnw verify` en `apps/api`, con JDK 25 y Docker.

## 1. Enfoque técnico

S3 añade el borde HTTP de dos casos de uso que ya existen y están probados. El criterio es el de S2: **el borde
traduce, el caso de uso decide**. Los controladores siguen el precedente de `LoginController`:
`@RateLimited` antes de leer el cuerpo, comando construido desde el cuerpo y el origen ligado a la petición,
`RequiredDelayMaterializer` alrededor del caso de uso cuando hay retardo, `RateLimitFailures.record` dentro del
proveedor del materializador, y traducción exhaustiva del desenlace con un `switch` sobre el tipo sellado. La
lógica de los casos de uso cambia en dos puntos acotados: el origen de la petición viaja en los comandos (decisión 3)
y un código de segundo factor ilegible se representa como una prueba más (decisión 5).

Nada de este cambio toca el esquema: **no hay migración**.

## 2. Evidencia obtenida antes de esta fase

### 2.1 Hallazgos de la exploración revisados sobre `origin/main` (`af20884`)

| Hallazgo de la exploración | Estado en `af20884` | Consecuencia |
|---|---|---|
| `PublicEndpoints` es la lista cerrada de rutas públicas | Confirmado: `shared/web/edge/PublicEndpoints.java`, con `LOGIN`, `REFRESH` y `END_CURRENT_SESSION` como constantes y `forAdmin` | Dos constantes nuevas y dos líneas en `forAdmin` |
| `RouteCoverageGuardTest` exige que toda ruta esté cubierta | Precisado: la guarda es código de producción, `shared/web/authorization/RouteCoverageGuard.java`, que detiene el arranque; `RouteCoverageGuardTest` prueba su lógica | Las rutas nuevas deben estar en `PublicEndpoints` desde el mismo commit que el controlador, o el proceso no arranca |
| [WE85] (siete rutas) | La aserción vive en `bootstrap/PublicRouteAllowListTest.java` (`publicRoutesOf` y `productionRoutesOf`) | Se amplía en el PR de cada ruta |
| BI52, «No recovery of a password over HTTP» | `architecture/WebEdgeScopeExclusionInventoryTest.java` línea 647, con `RouteInventory.passwordRecoveryRoutes` (expresión que contiene `reset`) y el fixture `BadPasswordRecoveryController` | El detector ya reconoce las dos rutas nuevas; la aserción pasa de vacío a exactamente las registradas |
| `apps/admin-web` sin enlace de contraseña olvidada | Confirmado; fuera de alcance por P5 | Sin cambios de frontend |
| Parte 5a de rbac | **No** está en `af20884`: `ResetPasswordWithToken.mfaActive` (línea 236) aún lee `account.mfaRequired()` | P7: la aplicación espera a 5a; este diseño no toca `mfaActive` |
| La ruta `GET /api/v1/identity/roles` de rbac | No está en `af20884` como controlador de producción (solo en pruebas) | [WE108] se redacta sin contar rutas ajenas de forma fija |

### 2.2 Código leído que condiciona el diseño

- **`RequestOriginAuditLogWriter`** (`shared/audit`) ya rellena `source_ip` y `user_agent` de todo asiento escrito
  durante una petición cuando el asiento los trae nulos. La exploración no lo registró. Consecuencia: con solo
  exponer las rutas, los asientos de recuperación ya llevarían el origen. La propagación explícita a los comandos
  se mantiene por la restricción acordada con el cambio 14 (decisión 3), y su prueba en rojo debe ejecutarse **sin**
  origen ligado al hilo, o pasaría por el decorador y no probaría nada.
- **Los dos casos de uso reciben un `SecurityContext`** y comprueban que su institución coincide con
  `LoginInstitutionProvider.loginInstitutionId()`. `LogIn` construye el contexto dentro de la capa de aplicación; los
  casos de uso de recuperación lo reciben del llamador.
- **`ResetPasswordCommand` exige un `SecondFactorProof`** con tipos de `identity.domain` (`TotpCode`,
  `PlainRecoveryCode`), cuyos constructores lanzan con un texto mal formado. La capa web no puede importar
  `identity.domain` (`LayeredArchitectureTest`), y `CompleteSecondFactor` trata un texto mal formado como un rechazo
  sin verificar, nunca como un `400`.
- **`RequestPasswordResetCommand` es un `record`** cuyo `toString()` imprime el identificador presentado (un
  correo). Hoy no tiene llamador HTTP; al tenerlo, sería una fuga potencial de la regla 11.
- **`ProblemCode`** fija un estado por código. `second-factor-invalid` es `401`, y toda respuesta `401` de la
  cadena lleva `WWW-Authenticate: Bearer` por especificación.
- **`SecurityHeadersFilter.apply(response)`** fija las cinco cabeceras base y la usa también
  `ProblemErrorReportValve` para lo que Tomcat rechaza antes de los filtros.
- **`RateLimitProperties`** es un `record` de componentes fijos con `ignoreUnknownFields = false`;
  `RateLimitPolicy` exige que todos los valores, incluidos los de la capa 2, sean positivos.
- **`ProcessBeanPolicy`** ya admite `com.confia.identity.application` y `com.confia.identity.web` en el proceso
  administrativo: el registro de los beans nuevos no añade líneas.
- **El catálogo `i18n/problems.properties`** tiene una prueba de cobertura que falla con una clave que no es un
  código: el mensaje del `202` no puede vivir en él.
- **`IdempotencyKeyInterceptor`** actúa solo sobre métodos con `@IdempotentWrite`: las rutas nuevas no la llevan y
  no exigen `Idempotency-Key` (D3 de `web-edge-foundations`).

## 3. Decisiones de arquitectura

### Decisión 1 — Paquetes, nombres y dirección de las dependencias

| Elemento | Ubicación |
|---|---|
| `PasswordResetRequestController`, `PasswordResetRequestBody`, `PasswordResetRequestAcceptedResponse` | `identity/web` |
| `PasswordResetController`, `PasswordResetBody` | `identity/web` |
| Origen en los comandos, `SecondFactorProof.presented(...)` y `SecondFactorProof.Unreadable` | `identity/application` |
| Registro de `RequestPasswordReset` | `identity/infrastructure/wiring/IdentityConfiguration.java` |
| Dos políticas | `shared/web/ratelimit/RateLimitProperties.java` y `application.yml` |
| Dos entradas públicas | `shared/web/edge/PublicEndpoints.java` |
| `no-referrer` por ruta | `shared/web/request/SecurityHeadersFilter.java` (y su uso en `ProblemErrorReportValve`) |
| Cuatro códigos | `shared/web/problem/ProblemCode.java` y `i18n/problems.properties` |
| Catálogo de mensajes de respuesta | `shared/web/message/ResponseMessages.java`, `i18n/messages.properties` y su bean en `WebEdgeConfiguration` |

Dependencias: `identity.web` → `identity.application` y `shared.web.*`; ningún controlador importa
`identity.domain` ni `shared.security.token`. Los controladores construyen el `SecurityContext` con
`LoginInstitutionProvider` (puerto de `identity.application`) y el `requestId` del origen:
`new SecurityContext("", "staff", institutionId, origin.requestId().toString())`, con el mismo tipo de actor que
`LogIn`.

**Alternativa descartada:** un caso de uso orquestador por ruta, como `LogIn`, que construya el contexto. Duplicaría
dos clases para una línea; los casos de uso ya verifican la institución contra el mismo proveedor, así que el
contexto que construye el controlador no puede elegir otra.

### Decisión 2 — Ruta de solicitud (H3 y uniformidad de H4)

```
POST /api/v1/auth/password-reset-requests      consumes/produces application/json
@RateLimited(policy = "admin-password-reset-request")
body: PasswordResetRequestBody { @NotBlank @Size(max = 320) identifier }   toString redactado
202:  PasswordResetRequestAcceptedResponse { message }   (ResponseMessages, clave identity.password-reset-request.accepted)
```

El manejador no usa el materializador (el desenlace no tiene retardo) ni `RateLimitFailures` (no hay desenlace de
rechazo). La respuesta se construye con un valor constante por proceso; el resultado del caso de uso, un registro
sin componentes, no influye en ella. La uniformidad del tiempo es responsabilidad del caso de uso, ya probada a ese
nivel, y la mide la puerta (decisión 9).

**Alternativa descartada:** `202` silencioso al exceder el límite por IP (rechazada por el propietario en P2). El
interceptor solo sabe responder `429`, y un usuario legítimo detrás de NAT quedaría sin correo y sin saber por qué.

### Decisión 3 — Origen de la petición en los dos comandos

`RequestPasswordResetCommand` pasa de `record` a clase final con `toString()` redactado y gana
`ClientAddress clientAddress` y `String userAgent`, ambos anulables. `ResetPasswordCommand` gana los mismos dos campos
y su `toString()` los redacta. Los asientos que escriben los dos casos de uso (`identity.password_reset.requested`,
`identity.password_reset.rejected`, `identity.session.revoked` e `identity.password_reset.completed`) pasan
`clientAddress.canonical()` y `userAgent` en lugar de `null`.

Por qué explícito, si el decorador ya rellena: (1) la restricción acordada con el cambio 14: la parte 14b necesita la
IP como dato en el caso de uso para el aviso al titular, y la carga de una tarea solo admite identificadores; (2) es
el patrón de `AuthenticationCommand` (O6 de S2); (3) el valor no depende de que el decorador esté cableado. El
decorador sigue rellenando los asientos de los verificadores anidados (`VerifyTotpCode`, consumo de código de
recuperación), que este cambio no toca.

**Alternativa descartada:** confiar solo en el decorador. Deja al cambio 14 sin la IP en el caso de uso y hace que la
prueba del origen pase sin el cambio.

### Decisión 4 — Ruta de confirmación y traducción de los cinco desenlaces

```
POST /api/v1/auth/password-resets              consumes application/json
@RateLimited(policy = "admin-password-reset")
body: PasswordResetBody {
  @NotBlank @Size(max = 128)  token
  @NotEmpty @Size(max = 1024) newPassword        tope de transporte; 12..128 lo decide el caso de uso
  @Size(max = 64) code                            opcional
  @Size(max = 64) recoveryCode                    opcional
  @AssertTrue  atMostOneProof()                   code y recoveryCode no vienen a la vez
}   toString redactado; ningún @RequestParam ni @PathVariable
```

Flujo del manejador:

```java
ResetOutcome outcome = delays.execute(() -> {
    ResetPasswordDecision decision = resetPassword.execute(context, command);
    if (decision.outcome() instanceof TokenRejected || decision.outcome() instanceof SecondFactorRejected) {
        failures.record(POLICY);              // after commit, before the wait
    }
    return new Delayed<>(decision.outcome(), decision.requiredDelay());
});
switch (outcome) {                            // exhaustive over the sealed type, no default
    case Completed c            -> 204 No Content, no body, no cookie
    case TokenRejected t        -> ProblemCodeException(PASSWORD_RESET_TOKEN_INVALID)        400
    case PasswordRejected p     -> ProblemCodeException(PASSWORD_LENGTH_INVALID)             422
    case SecondFactorMissing m  -> ProblemCodeException(PASSWORD_RESET_SECOND_FACTOR_REQUIRED) 422
    case SecondFactorRejected r -> ProblemCodeException(PASSWORD_RESET_SECOND_FACTOR_REJECTED) 422
}
```

El retardo solo es distinto de cero cuando se verificó un código TOTP (aceptado o rechazado); el materializador lo
espera después de confirmar, con el tope de `confia.web.delay.max-wait`.

**Alternativa descartada:** validar en el borde la longitud de 12 a 128. Duplicaría `StaffPasswordLengthPolicy`, y
el caso de uso comprueba el token antes que la longitud: una validación en el borde respondería antes de saber si el
token existe y cambiaría el orden aprobado por `password-recovery-token`.

### Decisión 5 — Códigos de segundo factor presentados como texto

`SecondFactorProof` gana la variante `Unreadable()` y la fábrica `SecondFactorProof.presented(String code, String
recoveryCode)`, en `identity.application`: sin ninguno, `None`; con un `code` que `TotpCode` acepta, `Totp`; con un
`recoveryCode` que `PlainRecoveryCode.of` acepta, `RecoveryCode`; con un texto que no puede ser un código, `Unreadable`.
En `ResetPasswordWithToken`, con MFA activa, `Unreadable` se trata como un segundo factor rechazado sin llamar a ningún
verificador: asiento `identity.password_reset.rejected` con `SECOND_FACTOR_INVALID`, retardo cero, token vivo. Sin MFA
activa, toda prueba se ignora, como hoy. Es el mismo trato que da `CompleteSecondFactor` a un texto mal formado.

**Alternativa descartada:** responder `400` en el borde a un código mal formado. Contestaría antes de comprobar el
token y distinguiría un código mal formado de uno incorrecto, que la verificación del segundo factor no distingue.

### Decisión 6 — Cuatro códigos de Problem Details

| Código | Estado | Por qué |
|---|---|---|
| `password-reset-token-invalid` | 400 | El token es el contenido de la petición y no designa ningún restablecimiento válido; un solo código para los cuatro motivos (el motivo queda en la bitácora) |
| `password-length-invalid` | 422 | Regla de dominio sobre un cuerpo bien formado; el texto da el intervalo 12 a 128, cotejado con la política por prueba |
| `password-reset-second-factor-required` | 422 | Falta una prueba que la cuenta exige; solo lo ve quien posee un token vivo |
| `password-reset-second-factor-rejected` | 422 | La prueba no es válida, está mal formada o ya se usó |

Ninguno lleva `WWW-Authenticate`. `second-factor-invalid` no se reutiliza: es `401`, y todo `401` de la cadena lleva
el desafío `Bearer`, que en una ruta pública y sin token de acceso induciría al cliente a reautenticarse. Los nombres
llevan el prefijo `password-reset-` para no confundirse con el valor `second-factor-required` del campo `status` de la
respuesta de inicio de sesión.

### Decisión 7 — Dos políticas de tasa

`RateLimitProperties` pasa de tres a cinco componentes (`adminPasswordResetRequest`, `adminPasswordReset`) con el mismo
`checked(...)`. Valores en `application.yml`:

| Política | `request-limit` | `request-window` | `failure-threshold` | `failure-window` | `restricted-interval` | `restriction-cap` | `max-entries` |
|---|---|---|---|---|---|---|---|
| `admin-password-reset-request` | 10 | 1h | 10 | 1h | 1m | 1h | 50000 |
| `admin-password-reset` | 20 | 1m | 10 | 10m | 1m | 1h | 50000 |

La capa 2 de `admin-password-reset-request` existe porque `RateLimitPolicy` exige valores positivos; nunca se alimenta,
y una prueba lo afirma ([WE118]). `ThrottlingConfiguration` registra un limitador por política, como hoy;
`RateLimitPolicyCheck` sigue deteniendo el arranque si un manejador nombra una política desconocida.

**Alternativa descartada:** reutilizar `admin-mfa` para la confirmación (exploración D3 *b*). Mezclaría los contadores de
dos superficies; un atacante de la confirmación agotaría el cupo de la verificación del segundo factor.

### Decisión 8 — `Referrer-Policy: no-referrer` por ruta

`SecurityHeadersFilter` gana `static String referrerPolicyFor(String path)` y `static void apply(HttpServletRequest,
HttpServletResponse)`. La decisión compara `request.getRequestURI()` (sin el contexto de la aplicación) por igualdad
exacta con un conjunto cerrado de dos rutas, `NO_REFERRER_PATHS`, declarado en el propio filtro: el filtro es el único
dueño del valor. `ProblemErrorReportValve` pasa a llamar a la sobrecarga con la petición. Una prueba cruza
`NO_REFERRER_PATHS` con los patrones de las dos entradas de recuperación de `PublicEndpoints`, para que no diverjan.

Como el filtro corre con `HIGHEST_PRECEDENCE` y antes de la cadena, la cabecera queda fijada en toda respuesta de esas
rutas: `202`, `204`, los `400` del traductor, el `403` del filtro de `Origin`, el `415`, los `422` y el `429` del
interceptor.

**Alternativas descartadas:** (a) fijar la cabecera en el controlador: los rechazos del filtro de `Origin`, del
interceptor y de la validación no pasan por él; (b) leer el conjunto desde `PublicEndpoints`: `shared.web.request` no
depende de `shared.web.edge` (la dependencia va al revés), y el ciclo rompería la regla de paquetes.

### Decisión 9 — Puerta de tiempo de la solicitud y su control negativo

`identity/web/PasswordResetRequestTimingGateIT` sigue la forma de `LoginTimingGateIT`: proceso administrativo real por
su camino de producción, PostgreSQL real, 10 precalentamientos y 200 + 200 solicitudes alternadas (cuenta existente
frente a identificador nuevo `nadie.<uuid>@colegio.edu.hn`), cada una desde una dirección propia, cada respuesta
comprobada como `202` dentro del cronometraje, y la política elevada por propiedades
(`confia.web.rate-limit.admin-password-reset-request.request-limit=10000`).

**El límite es 50 ms, sin calibración.** La puerta del inicio de sesión estrecha su límite a la mitad del costo de una
verificación Argon2id porque la omisión que debe detectar es la del señuelo, que cuesta lo que cuesta Argon2id. La
solicitud de recuperación no ejecuta Argon2id en ningún camino; ambos hacen una huella HMAC, una lectura y un asiento, y
la cuenta existente además inserta la tarea de emisión. La regresión que la puerta debe detectar es la contraria: un
trabajo **añadido** al camino de la cuenta existente, típicamente el envío del correo hecho dentro de la petición en vez
de en la tarea (un SMTP real cuesta cientos de milisegundos). Una diferencia menor de 50 ms la acepta la especificación
(`docs/03` §4.6), y la puerta no pretende ver por debajo de ella: se declara.

**Control negativo con poder discriminante.** La variante añade **100 ms (dos veces el límite)** de trabajo real solo
en el camino de la cuenta existente, en el punto exacto donde caería esa regresión: el puerto
`PasswordResetIssuanceScheduler`. Mecanismo, todo en el árbol de pruebas:

1. Un `ApplicationContextInitializer` de prueba, pasado al proceso con la propiedad `context.initializer.classes`,
   registra un `BeanPostProcessor` que envuelve el bean `PasswordResetIssuanceScheduler` con
   `DelayingIssuanceScheduler`: delega y, si un interruptor estático está encendido, gira sobre `System.nanoTime()`
   hasta cumplir 100 ms. Sin `Thread.sleep` (lo prohíbe `BlockingWaitConfinementTest` en paquetes de capa) y sin tocar
   código de producción.
2. La prueba real corre con el interruptor apagado; el control lo enciende, mide con la misma rutina y lo apaga en un
   `finally`.
3. El control exige dos cosas: que la diferencia supere 50 ms (la puerta lo rechaza) **y** que sea de al menos 80 ms (el
   80 % del trabajo añadido). La segunda condición impide que el control pase por una causa ajena: si las dos series
   cayeran en el mismo camino, o una respondiera `429` o `400`, la diferencia no llegaría a 80 ms o la comprobación del
   estado fallaría.

Como el trabajo añadido ocurre dentro de la transacción de la solicitud, igual que lo haría un envío en línea, el control
mide el mismo recorrido que la regresión. `PasswordResetRequestTimingReportIT` se retira en el mismo PR.

**Alternativas descartadas:** (a) el control de BI56, «responder al instante» al identificador sin cuenta: los dos
caminos difieren en pocos milisegundos y el control nunca superaría 50 ms, de modo que la puerta pasaría de vacío; (b)
omitir el asiento en un camino: cuesta unos milisegundos, la puerta no lo ve, y afirmarlo sería falso; (c) calibrar el
límite con el costo de la inserción de la tarea: es demasiado pequeño y ruidoso para dar un límite estable, y la
especificación fija 50 ms.

### Decisión 10 — Inversión de las ausencias y reglas de construcción

- **BI52.** `noProductionHandlerDeclaresARouteThatRecoversAPassword` pasa a
  `productionRecoveryRoutesAreExactlyTheApprovedOnes`: el detector debe devolver exactamente las rutas de recuperación
  registradas hasta ese PR (la solicitud en el PR 3, las dos desde el PR 6). El fixture permanente sigue siendo
  detectado. Una variante con `POST /api/v1/auth/password-reset-links` debe fallar ([BI144]).
- **[WE85] → [WE108].** `PublicRouteAllowListTest.publicRoutesOf("admin")` gana las rutas en el PR de cada una.
- **Regla de política.** `SessionRoutesRateLimitedRuleTest` amplía su convención a los manejadores de recuperación, con
  el fixture permanente `fixture/ratelimit/BadPasswordResetHandlerWithoutPolicy` ([BI145], [BI146]).
- **`IdentityScopeExclusionInventoryTest`** (I25 e I27, el emisor que falla) **no cambia**: es del cambio 14.

### Decisión 11 — Contrato OpenAPI y `packages/contracts`

Cada respuesta se declara con `@ApiResponse` explícito, como en `LoginController`: solicitud `202`, `400`, `403`,
`415`, `429` (con `Retry-After`) y `503`; confirmación `204`, `400`, `403`, `415`, `422`, `429` y `503`. Los `operationId`
son `requestPasswordReset` y `resetPassword`, con la etiqueta `auth-password-recovery`. La instantánea
`apps/api/openapi/admin.openapi.json` se actualiza en el PR de cada ruta. `packages/contracts` se regenera con
`pnpm --filter @confia/contracts generate` y se comprueba con `typecheck` y `test`; su salida (`src/generated/`) está
excluida de Git, como en S2.

### Decisión 12 — Documentación: notas fechadas, sin reescribir cuerpos

| Documento | Nota del 2026-11-09 o de la fecha de cierre |
|---|---|
| `docs/03-seguridad.md` §4.6 | Puerta de tiempo sobre HTTP y su control; cierre de H4 |
| `docs/03-seguridad.md` §4.7 | Rutas de P4, token en el fragmento y en el cuerpo, `no-referrer`, extensión de D2 a H3 (P1) |
| `docs/03-seguridad.md` §10 | Separación del límite por IP (`429`, P2) y por cuenta (silencioso); política de la confirmación (P3) |
| `docs/05-infraestructura-y-despliegue.md` | `confia.web.rate-limit.admin-password-reset-request.*` y `...admin-password-reset.*` |
| `docs/09-roadmap-y-fases.md` | Estado de la parte 4b: S3 entregado, H3 y H4 cerradas |
| `openspec/changes/foundations-plan/exploration.md` | Cierre de H3 y H4 con referencias; corrección de la fila de S3 (rutas de P4) |
| `IdentityConfiguration.java` (Javadoc, línea 64) | Retira «its endpoint registers it» |

Los textos originales de H3 y H4 no se tocan.

### Nota sobre ADR

No surge ninguna decisión de arquitectura nueva. La extensión de D2 a H3 es una decisión del propietario registrada con
nota fechada, como lo fue D2; el `no-referrer` por ruta y el catálogo de mensajes de respuesta son decisiones locales del
borde. No se redacta ADR.

## 4. Concurrencia

- Dos confirmaciones simultáneas con el mismo token: la resuelve el consumo condicional del caso de uso (ya probado);
  el borde no añade estado. La prueba HTTP no repite la carrera.
- El limitador en memoria es seguro entre hilos (ya probado); las políticas nuevas no cambian el mecanismo.
- `RateLimitFailures.record` se llama dentro del proveedor del materializador, después de confirmar y antes de la
  espera, de modo que un cliente que abandona durante la espera igual cuenta su fallo.
- El origen ligado al hilo lo resuelve el filtro de origen por petición; los comandos lo copian al construirse, así que
  no hay estado compartido entre peticiones.

## 5. Flujos

### 5.1 Solicitud

```
cliente ─POST /password-reset-requests─▶ SecurityHeadersFilter (no-referrer)
  ─▶ RequestContextFilter ─▶ CsrfOriginFilter (Origin permitido) ─▶ cadena: pública
  ─▶ RateLimitInterceptor[admin-password-reset-request] ──(exceso)──▶ 429 + Retry-After
  ─▶ validación del cuerpo ──(inválido)──▶ 400 validation-failed
  ─▶ PasswordResetRequestController
        command = (identifier, origin.clientAddress, origin.userAgent)
        context = ("", "staff", loginInstitutionId, origin.requestId)
        RequestPasswordReset.execute ── transacción: huella, búsqueda, [programa emisión], asiento con origen
  ◀─ 202 { message } (idéntico en los tres casos)
```

### 5.2 Confirmación

```
cliente ─POST /password-resets─▶ cabeceras (no-referrer) ─▶ origen ─▶ Origin ─▶ pública
  ─▶ RateLimitInterceptor[admin-password-reset] ──▶ 429
  ─▶ validación ──▶ 400 validation-failed
  ─▶ PasswordResetController
        proof = SecondFactorProof.presented(code, recoveryCode)
        RequiredDelayMaterializer.execute(
            ResetPasswordWithToken.execute ── transacción: token, longitud, bloqueo de cuenta,
                                              segundo factor (MfaPolicy), consumo, Argon2id, revocación, asientos
            → si TokenRejected o SecondFactorRejected: RateLimitFailures.record("admin-password-reset"))
        espera del retardo (solo TOTP verificado), con tope
  ◀─ 204 | 400 password-reset-token-invalid | 422 (tres códigos)
```

## 6. Archivos por rebanada

| PR | Archivos de producción | Archivos de prueba |
|---|---|---|
| 1 `recovery-command-origin` | `identity/application/{RequestPasswordResetCommand,ResetPasswordCommand,RequestPasswordReset,ResetPasswordWithToken}.java` | `RequestPasswordResetIT`, `ResetPasswordWithTokenIT` (ampliados), `RecoveryCommandsRedactionTest` |
| 2 `recovery-edge-policies-and-referrer` | `shared/web/ratelimit/RateLimitProperties.java`, `application.yml`, `shared/web/request/SecurityHeadersFilter.java`, `shared/web/edge/ProblemErrorReportValve.java` | `RateLimitPropertiesTest`, `SecurityHeadersFilterTest`, `NoReferrerRoutesIT` (arnés) |
| 3 `password-reset-request-endpoint` | `identity/web/{PasswordResetRequestController,PasswordResetRequestBody,PasswordResetRequestAcceptedResponse}.java`, `shared/web/message/ResponseMessages.java`, `WebEdgeConfiguration.java`, `i18n/messages.properties`, `PublicEndpoints.java`, `IdentityConfiguration.java`, `admin.openapi.json` | `PasswordResetRequestEndpointIT`, `PasswordResetRequestRateLimitClosesH3IT`, `ResponseMessageCatalogTest`, `WebEdgeScopeExclusionInventoryTest`, `PublicRouteAllowListTest`, `SessionRoutesRateLimitedRuleTest`, `OpenApiContractSnapshotTest`, `ProcessBeanIsolationTest` |
| 4 `password-reset-request-timing-gate` | ninguno | `PasswordResetRequestTimingGateIT`, `DelayingIssuanceScheduler`, `DelayingIssuanceSchedulerInitializer`; se borra `PasswordResetRequestTimingReportIT` |
| 5 `password-reset-confirmation-groundwork` | `identity/application/SecondFactorProof.java`, `ResetPasswordWithToken.java`, `shared/web/problem/ProblemCode.java`, `i18n/problems.properties` | `SecondFactorProofTest`, `ResetPasswordWithTokenIT` (ampliado), `ProblemCodeTest`, `ProblemCatalogCoverageTest`, `ProblemResponsesTest` |
| 6 `password-reset-confirmation-endpoint` | `identity/web/{PasswordResetController,PasswordResetBody}.java`, `PublicEndpoints.java`, `admin.openapi.json` | `PasswordResetEndpointIT`, `PasswordResetRateLimitIT`, inventarios y regla ampliados, `OpenApiContractSnapshotTest` |
| 7 `recovery-redaction-and-closing` | ninguno de comportamiento; Javadoc de `IdentityConfiguration` si no se hizo en 3 | `PasswordRecoveryFlowRedactionIT`; documentación de la decisión 12 |

## 7. Interfaces y contratos

```http
POST /api/v1/auth/password-reset-requests
Origin: https://admin.colegio.edu.hn
Content-Type: application/json

{ "identifier": "maria.lopez@colegio.edu.hn" }
```

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
Referrer-Policy: no-referrer
Cache-Control: no-store

{ "message": "Si la dirección corresponde a una cuenta, se enviará un enlace." }
```

```http
POST /api/v1/auth/password-resets
Origin: https://admin.colegio.edu.hn
Content-Type: application/json

{ "token": "<43 caracteres base64url>", "newPassword": "Montaña de Celaque 2026", "code": "492039" }
```

```http
HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json
Referrer-Policy: no-referrer

{ "type": "https://confia.example/problems/password-reset-second-factor-rejected", "title": "...",
  "status": 422, "detail": "...", "instance": "/api/v1/auth/password-resets", "traceId": "..." }
```

Firmas de aplicación que cambian:

```java
public final class RequestPasswordResetCommand {             // was a record
    public RequestPasswordResetCommand(String presentedIdentifier, ClientAddress clientAddress, String userAgent)
}
public final class ResetPasswordCommand {
    public ResetPasswordCommand(String presentedToken, String newPassword, SecondFactorProof secondFactor,
            ClientAddress clientAddress, String userAgent)
}
public sealed interface SecondFactorProof {
    record Unreadable() implements SecondFactorProof {}
    static SecondFactorProof presented(String code, String recoveryCode)
}
```

Los llamadores actuales en pruebas pasan `null, null` al origen.

## 8. Estrategia de pruebas (TDD estricto)

| Nivel | Qué prueba | Escenarios |
|---|---|---|
| Unidad | Fábrica de pruebas de segundo factor (vacíos, en blanco, TOTP válido, TOTP de 5 y de 7 dígitos, letras, código de recuperación válido y mal formado); `toString()` redactado de los dos comandos y de los dos cuerpos; `referrerPolicyFor` (exacta, parecida, con barra final, otra ruta); valores y arranque de las cinco políticas; cotejo del texto de `password-length-invalid` con `StaffPasswordLengthPolicy` | I340, I349, WE110, WE111, WE70, WE73, WE93, WE114 |
| Integración del caso de uso (PostgreSQL real, sin origen ligado) | Asientos con el origen del comando; `Unreadable` como rechazo sin consumir el token | I348, I349, I340 |
| Integración HTTP (proceso administrativo real) | Respuesta aceptada e igualdad de las tres respuestas con control negativo; cuerpos inválidos; institución; H3; los cinco desenlaces; política de la confirmación; origen en los asientos; `no-referrer` en todas las respuestas | I312 a I318, I335 a I347, WE106 a WE111, WE117, WE118 |
| Puerta | Puerta de tiempo con control negativo | I333, BI140, BI141 |
| Arquitectura y contrato | Inventario BI52 invertido; regla de política; instantánea OpenAPI; aislamiento de beans | BI143 a BI149 |
| Redacción | Centinelas en el registro estructurado, excepciones y cuerpos del recorrido completo, con control negativo | I350, I351 |

Reglas de las pruebas:

- **Igualdad de [I313].** Se compara estado, cuerpo byte a byte y cabeceras salvo `Date`. El control negativo usa un
  `BeanPostProcessor` de prueba que sustituye el mensaje solo para la cuenta inexistente; debe fallar.
- **H3.** El limitador se prueba con su reloj de prueba (`MutableClock`) cuando haga falta cruzar la hora; nunca se
  espera una hora real. Se cuentan invocaciones del caso de uso con un espía y asientos en la base.
- **Retardo.** El temporizador del materializador es un doble que registra la espera solicitada; nunca se espera el
  retardo real ([I339]).
- **Centinelas.** Igual que `SessionFlowRedactionIT`: captura del registro estructurado del proceso y búsqueda de cada
  centinela; el control negativo registra el cuerpo a propósito.
- **Demostraciones deliberadas** (una por tarea, registradas en `apply-progress.md`, revertidas): quitar `@RateLimited`
  de la solicitud (la undécima responde `202`, [I316]); devolver `null` como origen en el asiento (rojo en [I348]);
  cambiar el texto solo para la cuenta inexistente (rojo en [I313]); fijar la cabecera en el controlador en vez del
  filtro (rojo en el `403` y el `429` de [WE110]); contar `PasswordRejected` como fallo (rojo en [I346]); traducir
  `Unreadable` como `400` (rojo en [I340]).
- **Cobertura y mutación.** Sin código nuevo en `identity.domain`; `identity.application` y `identity.web` bajo el 80 %
  global; PIT sigue cubriendo `kernel` y `domain` sin cambios de umbral.

## 9. Plan de entrega

Siete PR encadenados contra `main` (`stacked-to-main`), cada uno de 800 líneas efectivas o menos, en este orden duro:

| PR | Contenido | Nominal → realista | Cierra |
|---|---|---|---|
| 1 | Origen en los comandos y en los asientos | 160 → 350 | — |
| 2 | Dos políticas y `no-referrer` por ruta | 200 → 440 | — |
| 3 | Ruta de solicitud, respuesta aceptada, H3, uniformidad, inversiones, OpenAPI | 340 → 750 | H3 |
| 4 | Puerta de tiempo y control negativo | 160 → 350 | H4 (con 2 y 3) |
| 5 | `SecondFactorProof.presented`, `Unreadable`, cuatro códigos | 180 → 400 | — |
| 6 | Ruta de confirmación, cinco desenlaces, política P3, OpenAPI | 360 → 790 | — |
| 7 | Redacción y cierre documental | 200 → 440 | Notas de H3 y H4 |

Total nominal unas 1 600 líneas; realista unas 3 500 con el factor 2,2 de S2. La propuesta estimaba de 1 800 a 2 600 en
cinco PR; la diferencia viene de separar la puerta y el trabajo previo de la confirmación para que ningún PR roce las
800 líneas. Costura de reserva del PR 6: si supera 800, `PasswordResetRateLimitIT` y sus escenarios ([I344] a [I346])
pasan al PR 7; la anotación `@RateLimited` y la regla [BI145] se quedan en el 6, porque una ruta nunca llega a `main`
sin su política.

**Orden duro:** 2 antes que 3 (la política debe existir antes de la ruta que la nombra, o `RateLimitPolicyCheck` detiene
el arranque); 3 antes que 4; 5 antes que 6; 1 antes que 3 y 6. Todo el encadenamiento empieza después de rebasar sobre un
`main` con la parte 5a de rbac (P7).

## 10. Desviaciones respecto de la propuesta y precisiones

1. **Códigos.** La propuesta nombraba `password-too-short`, `password-too-long`, `second-factor-required` y
   `second-factor-invalid`. El diseño fija `password-length-invalid` (uno, con el intervalo en el texto),
   `password-reset-second-factor-required` y `password-reset-second-factor-rejected` (no se reutiliza el `401`), y
   `password-reset-token-invalid`. La especificación ya usa estos nombres.
2. **Lógica nueva en el caso de uso.** Además del origen, `Unreadable` (decisión 5). Es la única forma de que un código
   mal formado no se conteste antes que el token.
3. **Origen.** El decorador de auditoría ya rellenaba el origen; la propagación explícita se justifica por el cambio 14
   (decisión 3), no por la columna.
4. **Mensaje del `202`.** Necesita un catálogo de mensajes nuevo, separado del de Problem Details.
5. **Siete PR en lugar de cinco**, por tamaño (sección 9).

## 11. Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| La parte 5a de rbac tarda; el encadenamiento no puede empezar | Media | P7 aceptado; los PR 1, 2 y 5 no tocan `mfaActive` y podrían adelantarse solo con una nueva decisión del propietario |
| Conflicto de fusión con 5a en `ResetPasswordWithToken` y en los fixtures | Alta si se aplica en paralelo | P7; el diseño no toca `mfaActive` |
| Choque de numeración de escenarios con el cambio 14, planificado en paralelo | Media | Este delta parte de `I312`, `WE106` y `BI140`; el cambio 14 ya ocupa `I320` a `I332` y `BI150` en adelante; este delta usa `I312` a `I319`, `I333` a `I355`, `WE106` a `WE118` y `BI140` a `BI149`, sin solaparse |
| Choque de texto con el delta de rbac en «Ausencia del restablecimiento administrativo» | Cierta | Regla de reconciliación escrita en el renombre del delta de `identity` |
| La puerta de tiempo pasa de vacío | Baja con este control | El control exige superar 50 ms y medir al menos 80 de los 100 ms añadidos |
| La inserción repetida de la tarea de emisión para la misma cuenta cambia de costo entre la primera y las siguientes (por ejemplo, si la tarea se deduplica por cuenta) | Media | Se comprueba en el rojo del PR 4; la puerta mide lo que hace el código real, sea cual sea; si la deduplicación existe, es parte del camino de la cuenta existente |
| Presupuesto de `verify`: unas 840 peticiones más | Media | Se mide en el PR 4 y se registra ([BI141]) |
| Autodenegación tras un proxy con `trusted-proxies` vacío: toda la institución comparte 10 solicitudes por hora | Alta en producción, nula hoy | Declarado (P1); resuelve el cambio 11 |
| Recuperación sin efecto de punta a punta hasta el cambio 14 | Cierta | Declarado; la tarea de emisión agota sus intentos contra el emisor que falla y queda visible |
| Ventana de hasta 10 minutos del token de acceso ya emitido tras el cambio de contraseña | Cierta | Riesgo ya declarado en `docs/03` §4.7 (2026-10-19); sin cambio |

## 12. Matriz de amenazas

| Amenaza | Control |
|---|---|
| Enumeración de cuentas por la solicitud | `202` idéntico ([I313]), puerta de tiempo ([I333], [BI140]), límite por cuenta silencioso |
| Recorrido de cuentas ajenas desde una IP | 10 por hora por IP ([I316]) |
| Fuerza bruta del token | 256 bits de entropía; 20 por minuto y restricción tras 10 fallos ([I344], [I345]) |
| Fuerza bruta del segundo factor durante el restablecimiento | Retroceso TOTP por cuenta existente, más la capa 2 de `admin-password-reset` |
| Fuga del token por `Referer` | Token solo en el cuerpo ([I343]); `no-referrer` en las respuestas ([WE110]); fragmento en el enlace ([I352]) |
| CSRF de la confirmación desde otro origen | `Origin` permitido obligatorio ([WE107]) |
| Fuga de secretos por registros o errores | Centinelas ([I350]); `toString()` redactados ([I349]); `400` sin el valor ([I351]) |
| Institución elegida por el cliente | Institución de la configuración ([I315]) |

## 13. Decisiones para el propietario

Ninguna decisión nueva bloquea las tareas. Se piden dos confirmaciones de bajo impacto:

1. **Nombres de los cuatro códigos** (decisión 6 y desviación 1). Recomendación: aceptar los del diseño.
2. **Siete PR en lugar de cinco** (sección 9). Recomendación: aceptar; la regla «partir solo si hace falta» se aplica igual.

## 14. Preguntas abiertas

- Numeración con el cambio 14: resuelta el 2026-11-09 (sección 11). Si un tercer cambio añade escenarios, empieza en `I356`, `WE119` y `BI162`.
- Forma del fragmento del enlace: confirmada como `#token=<valor>` con `transactional-email-adapter` (su `design.md` compone `baseUrl + "#token=" + token`). La página que lo lee es de un cambio de frontend posterior (P5).

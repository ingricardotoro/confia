# Propuesta: endpoints de recuperación de contraseña (S3 de la parte 4b del cambio 7 de F0)

- **Cambio:** `password-recovery-endpoints`
- **Fase del roadmap:** F0, cambio 7, parte 4b, tercer cambio (S3). Según el quinto corte de
  `foundations-plan/exploration.md` (líneas 275 a 299), va después de `session-tokens-and-web-layer`
  (S1), `session-endpoints-cookie-and-csrf` (S2) y `background-jobs-with-db-scheduler` (cambio 9), los
  tres archivados.
- **Depende de:** `password-recovery-token`, S1, S2, cambio 9 y, para la aplicación, la parte 5a de
  `rbac-permission-matrix-and-audit-integration` (ver «Dependencias»).
- **Exploración:** `openspec/changes/password-recovery-endpoints/exploration.md`.
- **Estado:** aprobada por el propietario del producto el 2026-11-09, con las siete recomendaciones P1 a P7
  aceptadas sin cambios (ver «Decisiones del propietario»).

## Intención

La recuperación de contraseña del personal existe hoy solo como casos de uso: `RequestPasswordReset`
programa la emisión de un token y `ResetPasswordWithToken` lo consume, con segundo factor y revocación
de sesiones. No existe ninguna ruta HTTP que los alcance. Dos condiciones duras de aceptación, **H3**
(10 solicitudes por hora por IP) y **H4** (respuesta HTTP uniforme con su puerta de tiempo), llevan
abiertas desde el 2026-10-03 y se transfirieron a este cambio en cada archivo posterior. Este cambio
expone las dos rutas con los controles que esas condiciones exigen y las cierra.

## Condiciones que cierra

| Condición | Texto | Cómo se cierra |
|---|---|---|
| **H3** | La solicitud de restablecimiento no se fusiona sin el límite de 10 por hora por IP (`docs/03` §4.7 y §10) | Política `admin-password-reset-request` aplicada con `@RateLimited` antes del caso de uso; prueba de que la undécima solicitud de una hora desde la misma IP es rechazada y otra IP no se ve afectada |
| **H4** | Respuesta HTTP uniforme de la recuperación y puerta de tiempo | `202 Accepted` idéntico en código, cabeceras y cuerpo (salvo `traceId`) para cuenta existente, inexistente y en su límite por hora; `Referrer-Policy: no-referrer`; puerta de 200 + 200 intentos con diferencia de medianas ≤ 50 ms y control negativo que falla |

El texto original de H3 y H4 no se modifica; el cierre se registra con notas fechadas.

## Alcance

### Dentro de alcance

1. **Ruta de solicitud** `POST /api/v1/auth/password-reset-requests` (P4), pública, en la lista cerrada
   `PublicEndpoints`. Cuerpo JSON con el identificador, validado con Bean Validation y sin propiedades
   desconocidas. Responde siempre `202 Accepted` con el mensaje del catálogo es-HN «Si la dirección
   corresponde a una cuenta, se enviará un enlace». Sin `Idempotency-Key` (D3 de `web-edge-foundations`).
2. **Límite H3:** política nueva `admin-password-reset-request`, 10 por hora por IP, en
   `RateLimitProperties` y `application.yml`, sobre el limitador en memoria existente (P1). Al exceder,
   `429` con `Retry-After` (P2). El límite por cuenta, tres por hora, sigue decidiéndose en la emisión y
   sigue siendo silencioso.
3. **Uniformidad H4:** prueba de integración que compara byte a byte las respuestas de los tres casos.
4. **Puerta de tiempo H4:** prueba sobre el proceso administrativo real con 200 + 200 intentos y un
   control negativo con poder discriminante real, calibrado y nunca mayor de 50 ms (exploración D6).
   Sustituye el papel informativo de `PasswordResetRequestTimingReportIT`.
5. **Ruta de confirmación** `POST /api/v1/auth/password-resets` (P4), pública. Cuerpo JSON con token,
   contraseña nueva y segundo factor opcional (TOTP o código de recuperación). Traduce los cinco
   desenlaces de `ResetOutcome` con un `switch` exhaustivo: `204` al completar; un único `400` para
   todo token rechazado; `422` para la contraseña fuera de longitud, el segundo factor faltante y el
   segundo factor incorrecto. Materializa el retardo del TOTP con `RequiredDelayMaterializer`. Sin
   cookie ni sesión: el titular inicia sesión después. Códigos y estados exactos, en el diseño.
6. **Límite de la confirmación:** política nueva `admin-password-reset`, 20 por minuto por IP, con capa 2
   de 10 fallos en 10 minutos; cuentan como fallo el token rechazado y el segundo factor rechazado (P3).
7. **`Referrer-Policy: no-referrer`** en las respuestas de las dos rutas, sin cambiarla en las demás.
8. **Origen de la petición:** `RequestPasswordResetCommand` y `ResetPasswordCommand` ganan dirección y
   agente saneado (con `toString()` redactado) y sus asientos llenan `source_ip` y `user_agent`. Es la
   fuente de la IP del aviso al titular que entregará el cambio 14.
9. **Registro** de `RequestPasswordReset` como bean del proceso administrativo.
10. **Redacción:** prueba de centinelas sobre el recorrido completo de las dos rutas: ninguna contraseña,
    token ni código de segundo factor en registros, excepciones ni cuerpos de error.
11. **Contrato:** OpenAPI con todas las respuestas (`202`, `204`, `400`, `403` por `Origin`, `415`,
    `422`, `429`, `503`), instantánea aprobada actualizada y `packages/contracts` regenerado.
12. **Inversión de ausencias:** BI52, [WE85] (de siete a nueve rutas), la lista de rutas públicas y los
    requisitos de ausencia de `identity` y `web-edge` que nombran a este cambio.
13. **Documentación:** notas fechadas en `docs/03` §4.6, §4.7 y §10 (separación del límite por IP y por
    cuenta, la política de la confirmación y la extensión de D2), `docs/05` (propiedades nuevas),
    `docs/09` y `foundations-plan/exploration.md` (cierre de H3 y H4).

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| Adaptador de correo que implementa `PasswordResetLinkSender`, construcción del enlace, plantilla, y la inversión de I25, I27 y del escenario «El puerto de envío solo tiene el emisor que falla» | `transactional-email-adapter` (cambio 14) |
| Aviso al titular tras el restablecimiento con IP y momento, aviso de códigos de MFA bajos y aviso por reutilización de refresco | Cambio 14 |
| Estado compartido del limitador en Redis, llenado de `trusted-proxies` y límite en el borde nginx | `containerization-and-cicd-pipeline` (cambio 11) |
| Pantallas de solicitud y de restablecimiento en `admin-web`, y `no-referrer` como cabecera de esa página | Cambio de frontend nuevo, posterior a `admin-web-staff-sign-in` (P5) |
| Restablecimiento administrativo forzado por un Super Administrador | `rbac`, parte **8b** (P6) |
| Derivación de MFA con `MfaPolicy` y retiro de `mfa_required` | `rbac`, parte 5a |
| Recuperación de contraseña del encargado en el portal | F8 |
| Ventana de hasta 10 minutos del token de acceso ya emitido tras el cambio de contraseña (nota de `docs/03` §4.7 del 2026-10-19) | Sin cambio; riesgo ya declarado |

**Restricción que este cambio fija para el cambio 14 y para la pantalla:** el token viaja en el
fragmento de la URL del enlace (`#`), nunca en la ruta ni en la consulta, y la página lo envía en el
cuerpo de la confirmación.

## Capacidades

### Nuevas

Ninguna.

### Modificadas

- **`identity`:** requisitos positivos de las dos rutas, de H3, de H4 y del origen en los asientos de
  recuperación; retiro de «Ausencia del límite por dirección IP y de la respuesta HTTP de la
  recuperación»; la ausencia del restablecimiento administrativo cambia su destino a 8b solamente.
- **`web-edge`:** las dos rutas públicas, las dos políticas de tasa y `no-referrer` en ellas; retiro de
  «Ausencia de recuperación de contraseña por HTTP»; [WE85] pasa a nueve rutas.
- **`build-integrity`:** la puerta de tiempo de la solicitud y su control negativo; instantánea OpenAPI.

## Enfoque

Solo backend (enfoque A de la exploración). Los casos de uso no cambian su lógica: la uniformidad ya es
del caso de uso y está probada. El cambio añade el borde HTTP siguiendo el precedente de
`LoginController`: `@RateLimited` antes de leer el cuerpo, `RequiredDelayMaterializer` alrededor del caso
de uso, `RateLimitFailures.record` dentro del proveedor, traducción exhaustiva del desenlace a Problem
Details por el traductor global y la institución tomada de la configuración del proceso, nunca del
cliente. La puerta de tiempo sigue la forma de `LoginTimingGateIT`, con un control negativo diseñado
para un camino sin Argon2id.

## Áreas afectadas

| Área | Impacto |
|---|---|
| `apps/api/.../identity/web/` | Dos controladores y sus DTO |
| `apps/api/.../identity/application/` | Origen en dos comandos y dos casos de uso |
| `apps/api/.../identity/infrastructure/wiring/IdentityConfiguration.java` | Registro de `RequestPasswordReset` |
| `apps/api/.../shared/web/ratelimit/`, `application.yml` | Dos políticas |
| `apps/api/.../shared/web/edge/PublicEndpoints.java`, guarda de cobertura de rutas | Dos entradas |
| `apps/api/.../shared/web/request/SecurityHeadersFilter.java` | `no-referrer` por ruta |
| Catálogo de Problem Details y catálogo es-HN | Códigos y mensaje |
| `packages/contracts` | Regenerado |
| `docs/03`, `docs/05`, `docs/09`, `foundations-plan` | Notas fechadas |
| `openspec/specs/identity`, `web-edge`, `build-integrity` | Deltas |

No hay migración de base de datos.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Sin P1, el cambio queda bloqueado detrás del cambio 11, que no tiene propuesta | Alta si P1 se rechaza | Extender D2 con nota fechada |
| Autodenegación tras un proxy con `trusted-proxies` vacío: toda la institución comparte 10 solicitudes por hora | Alta en producción, nula hoy (sin despliegue) | Declarado; resuelve el cambio 11; valores en configuración |
| Puerta de tiempo que pasa de vacío, porque los dos caminos difieren en pocos milisegundos | Media | Control negativo calibrado, revisado en el diseño |
| Conflicto con la parte 5a de rbac en `ResetPasswordWithToken` y en unos 45 archivos de pruebas | Alta si se aplica en paralelo | P7: aplicar después de 5a, sobre `origin/main` rebasado |
| Fuga del token o de la contraseña por registros o por el cuerpo de un error de validación | Baja | Prueba de centinelas del alcance 10 |
| Presupuesto de `verify` por 400 peticiones más | Media | Medir en el PR de la puerta |
| Recuperación sin efecto de punta a punta hasta el cambio 14 | Cierta | Declarado; la tarea agota sus intentos y queda visible |

## Plan de reversión

Cada PR se revierte por separado. Revertir los controladores devuelve el estado actual: los casos de uso
siguen sin ruta y las pruebas de ausencia vuelven con el revert. Las políticas nuevas son configuración
sin efecto si no hay ruta que las use. La propagación del origen solo llena columnas que hoy quedan
nulas; revertirla vuelve a dejarlas nulas sin migración. No hay datos que deshacer.

## Dependencias

- **Archivadas:** `password-recovery-token`, S1 (H2 cerrada), S2 (limitador, materializador, CSRF con
  `Origin`, origen de la petición), cambio 9 (adaptador de programación, R4).
- **Para aplicar:** la parte 5a de rbac debe estar fusionada en `main` (P7). La rama de planificación ya se
  rebasó sobre `origin/main` en `af20884` el 2026-11-09; antes de aplicar se rebasa de nuevo sobre el `main`
  que contenga 5a. Especificación, diseño y tareas avanzan antes y suponen el código posterior a 5a
  (`MfaPolicy` en lugar de `mfa_required`).
- **En paralelo, sin dependencia de fusión:** `transactional-email-adapter` (cambio 14). La recuperación
  funciona de punta a punta solo cuando ambos estén en `main`.

## Criterios de éxito

- [ ] La undécima solicitud en una hora desde una misma IP recibe `429` con `Retry-After` antes de que
      corra el caso de uso, y una IP distinta sigue recibiendo `202` (H3).
- [ ] Las respuestas para cuenta existente, inexistente y en su límite por hora son idénticas salvo
      `traceId` (H4).
- [ ] La puerta de tiempo pasa con medianas a ≤ 50 ms, y su control negativo falla (H4).
- [ ] Las dos rutas responden con `Referrer-Policy: no-referrer`; las demás conservan la suya.
- [ ] La confirmación traduce los cinco desenlaces; los cuatro motivos de token dan una única respuesta.
- [ ] Una confirmación completada sobre HTTP revoca todas las familias de la cuenta y la contraseña vieja
      deja de autenticar.
- [ ] Los asientos de solicitud y de restablecimiento llevan `source_ip` y `user_agent`.
- [ ] La prueba de centinelas no encuentra ningún secreto del recorrido.
- [ ] OpenAPI, instantánea y `packages/contracts` actualizados; pruebas de ausencia invertidas.
- [ ] H3 y H4 cerradas en las notas fechadas, sin reescribir su texto.

## Pronóstico de tamaño y entrega

**Entre 11 y 13 tareas**, por debajo del límite de 15. No se propone partir el cambio; si el diseño
supera 15, la partición de reserva es S3a (solicitud, H3 y H4) y S3b (confirmación).

Entre 1 800 y 2 600 líneas efectivas, en cinco PR encadenados de ≤ 800 líneas:

| PR | Contenido |
|---|---|
| 1 | Políticas, origen en comandos y asientos, registro del caso de uso |
| 2 | Ruta de solicitud, H3, uniformidad, `no-referrer`, ausencias invertidas |
| 3 | Puerta de tiempo con control negativo |
| 4 | Ruta de confirmación, redacción, OpenAPI y contratos |
| 5 | Cierre documental |

## Decisiones que se piden al propietario

| Id | Pregunta | Recomendación |
|---|---|---|
| **P1** | ¿D2 cubre H3: el limitador en memoria es el tope equivalente hasta el cambio 11? | Sí, con nota fechada y las mismas tres limitaciones |
| **P2** | Al exceder el límite por IP de la solicitud, ¿`429` o `202` silencioso? | `429` con `Retry-After`; el límite por cuenta sigue silencioso |
| **P3** | Límite de la confirmación | `admin-password-reset`: 20 por minuto por IP, 10 fallos en 10 minutos |
| **P4** | Nombres de ruta | `password-reset-requests` y `password-resets`, en vez de `reset/request` y `reset/confirm` |
| **P5** | ¿Pantallas fuera de alcance? | Sí, en un cambio de frontend posterior a `admin-web-staff-sign-in` |
| **P6** | ¿Restablecimiento administrativo solo en 8b? | Sí |
| **P7** | ¿Aplicar después de la parte 5a de rbac? | Sí |

## Decisiones del propietario (2026-11-09)

El propietario aprobó la propuesta el 2026-11-09 y aceptó las siete recomendaciones. Cada decisión se
registra aquí con su consecuencia para la especificación, el diseño y las tareas.

| Id | Decisión | Consecuencia |
|---|---|---|
| **P1** | Se extiende la decisión D2 del propietario (limitador en memoria aceptado como tope equivalente) a la condición **H3**, 10 solicitudes por hora por IP, con nota fechada. El estado compartido sobre Redis sigue siendo del cambio 11 | Nota fechada en `docs/03` §4.7 y §10 y en `foundations-plan/exploration.md`, con las mismas tres limitaciones declaradas para H1 (estado por proceso, `trusted-proxies` vacío, sin Redis). Se retira de la especificación la exigencia «con el aprovisionamiento de Redis del cambio 11, operativo y probado» |
| **P2** | Al exceder el límite por IP de la solicitud se responde `429` con `Retry-After`. El límite por cuenta (tres por hora) sigue siendo silencioso, con `202` | Nota fechada en `docs/03` §10 que separa la dimensión por IP de la dimensión por cuenta; la fila del portal no se reescribe |
| **P3** | Política nueva `admin-password-reset` para la confirmación: 20 peticiones por minuto por IP; 10 fallos en 10 minutos activan la restricción | Cuentan como fallo el token rechazado y el segundo factor rechazado; nota fechada en `docs/03` §10 |
| **P4** | Rutas `POST /api/v1/auth/password-reset-requests` y `POST /api/v1/auth/password-resets` | Sustituyen a `reset/request` y `reset/confirm` de la fila de S3 del plan, que se corrige con nota fechada |
| **P5** | Las pantallas de `admin-web` quedan fuera de alcance, para un cambio de frontend posterior | Sin cambios en `apps/admin-web` |
| **P6** | El restablecimiento administrativo forzado pertenece solo a la parte **8b** de rbac | La ausencia del restablecimiento administrativo en `identity` pasa a tener como único destino el cambio 8b |
| **P7** | La aplicación empieza solo cuando la parte 5a de rbac (`MfaPolicy` en lugar de `mfa_required`) esté en `main`; especificación y diseño avanzan ahora | El diseño y las tareas suponen el código posterior a 5a |

### Restricciones acordadas con `transactional-email-adapter` (cambio 14)

Acordadas el 2026-11-09 durante la planificación en paralelo de los dos cambios:

1. El token viaja en el fragmento de la URL del enlace (`#token=<valor>`), nunca en la ruta ni en la consulta, y la
   página lo envía en el cuerpo de la confirmación.
2. El enlace lo construye el cambio 14 a partir de `PASSWORD_RESET_LINK_BASE_URL`; este cambio no lee esa
   variable ni construye ningún enlace.
3. Este cambio lleva la IP del cliente y el agente de usuario saneado a los dos comandos de recuperación
   (`RequestPasswordResetCommand` y `ResetPasswordCommand`). La parte 14b necesita la IP para el aviso al
   titular.


### Decisiones posteriores al diseño (2026-11-09)

- **D1. Códigos de problema aceptados:** `password-reset-token-invalid` (400), `password-length-invalid` (422),
  `password-reset-second-factor-required` (422) y `password-reset-second-factor-rejected` (422).
- **D2. Entrega en 7 PR apilados** de 800 líneas efectivas como máximo, en lugar de los 5 estimados.

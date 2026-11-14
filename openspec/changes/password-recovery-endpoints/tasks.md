# Tareas: endpoints de recuperación de contraseña (parte 4b, S3)

- **Cambio:** `password-recovery-endpoints` (F0, cambio 7, parte 4b, tercer cambio de tres)
- **Fase:** tareas
- **Fecha:** 2026-11-09
- **Estado:** pendiente de aprobación del propietario
- **Entradas aprobadas:** `proposal.md` (aprobada el 2026-11-09, P1 a P7), `design.md` (decisiones 1 a 12, secciones 6, 8
  y 9) y los deltas `specs/identity/spec.md` (31 escenarios numerados: `I312` a `I319` e `I333` a `I355`; `I320` a `I332` son de `transactional-email-adapter`), `specs/web-edge/spec.md` (13
  nuevos, `WE106` a `WE118`, más los conservados de los dos requisitos modificados) y `specs/build-integrity/spec.md` (11,
  `BI140` a `BI149`)
- **Precondición dura (P7):** la parte 5a de rbac (`MfaPolicy`) está en `main` y la rama se rebasó sobre ese `main` antes
  de la tarea 1.1. Si no lo está, **no se empieza**.
- **Modo de TDD:** estricto. Ejecutor: `./mvnw verify` en `apps/api`, con `JAVA_HOME` en JDK 25 y Docker en ejecución.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines (additions + deletions, sin `openspec/` ni código generado) | nominal unas 1 600; realista unas 3 500 con el factor 2,2 de S2 |
| 400-line budget risk | High frente a 400; **bajo a medio por PR** frente al presupuesto del proyecto de 800 |
| Chained PRs recommended | Yes |
| Suggested split | 7 PR apilados contra `main`, en el orden duro del diseño §9 |
| Delivery strategy | cadena apilada con la regla del propietario «partir solo si hace falta» |
| Chain strategy | `stacked-to-main`: cada PR se fusiona a `main` en orden y el siguiente parte de `main` actualizado |
| Presupuesto del proyecto por PR (`docs/15-flujo-de-trabajo-git.md` §3) | 800 líneas de cambio efectivo |

Decision needed before apply: No (solo la precondición P7)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Tareas.** 12, en 7 fases (una por PR). **Cuenta frente al límite de 15: 12; no se propone partir el cambio.**

**Regla de partición.** Si la medición de un PR supera 800 líneas efectivas, el ejecutor lo parte por archivos completos en
la costura nombrada de su tarea, sin consultar, y deja una nota fechada con la medida. Solo consulta si una parte resultante
no compila o no pasa sus pruebas por sí sola.

### Reglas comunes a todas las tareas

- **Comandos.** Todos desde `apps/api`. `./mvnw verify -Pmutation-gate` completo cierra cada PR. Las órdenes focalizadas son
  solo para iterar: un `-Dtest=` acotado informa `BUILD FAILURE` por JaCoCo aunque las pruebas pasen, y eso no es un fallo.
  Forma unitaria: `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='A,B'`. Forma de
  integración: `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='X,Y'
  -Dfailsafe.failIfNoSpecifiedTests=false`.
- **Rojo registrado.** Cada rojo corre contra el árbol sin el código de producción y se registra en
  `openspec/changes/password-recovery-endpoints/apply-progress.md` con la causa **observada**. Un commit en rojo no entra al
  historial: las pruebas en rojo se comprometen con su verde.
- **Demostración deliberada.** Cada tarea nombra una ruptura temporal de producción que debe poner en rojo una prueba concreta
  por una aserción de resultado (estado, cuerpo, cabecera, fila, contador), nunca por tiempo de espera ni por carrera. Se
  registra y se revierte.
- **Las pruebas de ataque se escriben antes que el código que defienden** (enumeración, recorrido de cuentas, fuerza bruta del
  token, fuga por `Referer`).
- **Concurrencia y tiempo deterministas.** `MutableClock` para cruzar ventanas del limitador; el temporizador del materializador
  es un doble que registra la espera; nunca se espera una hora ni un retardo real.
- **Un PR nunca expone una ruta sin su política, su entrada en `PublicEndpoints` y las inversiones de las pruebas de ausencia:**
  `RouteCoverageGuard` y `RateLimitPolicyCheck` detienen el arranque, y BI52 y [WE85] se ponen en rojo, si falta alguna.
- **Registros.** Nunca identificador presentado en claro, token, contraseña, código TOTP, código de recuperación, IP ni agente
  en logs; ningún dato de menores.
- **Medición antes de abrir el PR.** `git diff --numstat main...HEAD` excluyendo `openspec/` y código generado, registrada en
  `apply-progress.md`.
- **Commits.** Conventional Commits en inglés, sin atribución de herramientas de IA, con pruebas y documentación en el mismo
  commit que el comportamiento. Una rama por PR: `change/password-recovery-endpoints-NN-<nombre>`.
- **Idioma.** Código, identificadores y commits en inglés; documentación y notas fechadas en español neutro profesional; textos
  para el usuario solo en `problems.properties` y `messages.properties` (es-HN).
- **Notas documentales.** Fechadas, sin reescribir cuerpos ni el texto original de H3 y H4.
- **Revisión de seguridad de cada PR.** Lista de `confia-security-checklist` §1 marcada en la descripción del PR.
- **Rutas bajo `apps/api/app/src/`** salvo indicación.

### Suggested Work Units

| PR | Tareas | Nominal → realista | Comando de prueba enfocado | Frontera de reversión |
|---|---|---|---|---|
| 1 `recovery-command-origin` | 1.1 | 160 → 350 | IT: `-Dit.test='RequestPasswordResetIT,ResetPasswordWithTokenIT'`; `-Dtest='RecoveryCommandsRedactionTest'` | Se revierte el commit; los asientos vuelven a `null` (el decorador sigue rellenando en petición); sin ruta |
| 2 `recovery-edge-policies-and-referrer` | 2.1, 2.2 | 200 → 440 | `-Dtest='RateLimitPropertiesTest,SecurityHeadersFilterTest'`; IT: `-Dit.test='NoReferrerRoutesIT'` | Políticas sin ruta que las use; la cabecera solo cambia en dos rutas que aún no existen |
| 3 `password-reset-request-endpoint` | 3.1, 3.2 | 340 → 750 | IT: `-Dit.test='PasswordResetRequestEndpointIT,PasswordResetRequestRateLimitClosesH3IT'`; `-Dtest='ResponseMessageCatalogTest,WebEdgeScopeExclusionInventoryTest,PublicRouteAllowListTest,SessionRoutesRateLimitedRuleTest,OpenApiContractSnapshotTest,ProcessBeanIsolationTest'` | Se revierte entero (ruta, política aplicada, inversiones y H3 juntos); nunca la política sola |
| 4 `password-reset-request-timing-gate` | 4.1 | 160 → 350 | IT: `-Dit.test='PasswordResetRequestTimingGateIT'` | Solo pruebas; revertir devuelve la medición informativa |
| 5 `password-reset-confirmation-groundwork` | 5.1, 5.2 | 180 → 400 | `-Dtest='SecondFactorProofTest,ProblemCodeTest,ProblemCatalogCoverageTest,ProblemResponsesTest'`; IT: `-Dit.test='ResetPasswordWithTokenIT'` | Variante y códigos sin productor HTTP |
| 6 `password-reset-confirmation-endpoint` | 6.1, 6.2 | 360 → 790 | IT: `-Dit.test='PasswordResetEndpointIT,PasswordResetRateLimitIT'`; `-Dtest='WebEdgeScopeExclusionInventoryTest,PublicRouteAllowListTest,SessionRoutesRateLimitedRuleTest,OpenApiContractSnapshotTest'` | Se revierte entero; la solicitud sigue funcionando |
| 7 `recovery-redaction-and-closing` | 7.1, 7.2 | 200 → 440 | IT: `-Dit.test='PasswordRecoveryFlowRedactionIT'` | Solo pruebas y documentación |

### Dependencias y paralelismo

| Tarea | Depende de | Paralelismo |
|---|---|---|
| 1.1 | Rebase sobre `main` con 5a | Raíz de la cadena |
| 2.1, 2.2 | Ninguna técnica | Pueden redactarse en paralelo con 1.1 (archivos disjuntos); se fusionan en orden |
| 3.1, 3.2 | 1.1 y 2.1 (y 2.2 para [WE110] sobre la ruta real) | Secuencial |
| 4.1 | 3.1 | Secuencial |
| 5.1, 5.2 | 1.1 | Pueden redactarse en paralelo con 3 y 4; se fusionan después de 4 |
| 6.1, 6.2 | 2.1, 2.2 y 5 | Secuencial |
| 7.1, 7.2 | 6 | Secuencial: cierra el cambio |

---

## Fase 1: origen de la petición

- [ ] 1.1 **PR 1 `recovery-command-origin`: IP y agente en los dos comandos y en sus asientos (decisión 3).**
  - **ROJO.** Ampliar `test/java/com/confia/identity/application/RequestPasswordResetIT.java` y `ResetPasswordWithTokenIT.java`
    **sin origen ligado al hilo** (llamada directa al caso de uso, sin `RequestOrigin`): el asiento
    `identity.password_reset.requested` y los asientos de rechazo, `identity.session.revoked` e
    `identity.password_reset.completed` llevan la IP canónica y el agente del comando [I348]; un comando sin origen se acepta
    con ambos nulos [I349]. Crear `RecoveryCommandsRedactionTest.java`: el `toString()` de los dos comandos no contiene el
    identificador, el token, la contraseña, la IP ni el agente [I349]. **Rojos esperados:** constructores inexistentes; con el
    constructor añadido y los asientos aún en `null`, la aserción de `source_ip` falla con `null`.
  - **VERDE.** `RequestPasswordResetCommand` pasa de `record` a clase final con `clientAddress` y `userAgent` y `toString()`
    redactado; `ResetPasswordCommand` gana los dos campos; `RequestPasswordReset` y `ResetPasswordWithToken` los pasan a sus
    `AuditEntry`. Los llamadores existentes en pruebas pasan `null, null`.
  - **Demostración deliberada.** Devolver `null` como `sourceIp` en el asiento de rechazo pone en rojo [I348] por el valor
    leído de la fila.
  - **Verificación.** `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
    -Dit.test='RequestPasswordResetIT,ResetPasswordWithTokenIT' -Dfailsafe.failIfNoSpecifiedTests=false` y
    `-DskipITs -Dtest='RecoveryCommandsRedactionTest'`; cierre con `./mvnw verify -Pmutation-gate`. Commit:
    `feat(identity): carry the request origin into the password recovery commands`. — Escenarios I348, I349.

## Fase 2: políticas y cabecera

- [ ] 2.1 **PR 2 `recovery-edge-policies-and-referrer` (parte 1): políticas `admin-password-reset-request` y
  `admin-password-reset` (decisión 7, P1 y P3).**
  - **ROJO.** Ampliar `RateLimitPropertiesTest.java`: valores por defecto de las cinco políticas [WE70]; arranque fallido sin
    `admin-password-reset` y con `admin-otro` [WE93]; `admin-password-reset-request.request-limit` en 0 nombra la propiedad
    [WE73]. **Rojo esperado:** componentes inexistentes.
  - **VERDE.** Dos componentes en `shared/web/ratelimit/RateLimitProperties.java` y sus bloques en `application.yml` con los
    valores del diseño; comentario del bloque que declara que la capa 2 de la solicitud nunca se alimenta.
  - **Demostración deliberada.** Poner `request-window: 1m` en la política de la solicitud pone en rojo [WE70] por el valor.
  - **Verificación.** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false
    -Dtest='RateLimitPropertiesTest'`. Commit: `feat(web): add the two password recovery rate-limit policies`. — Escenarios
    WE70, WE73, WE93.

- [ ] 2.2 **PR 2 (parte 2): `Referrer-Policy: no-referrer` en las dos rutas de recuperación (decisión 8).**
  - **ROJO.** Ampliar `SecurityHeadersFilterTest.java`: `referrerPolicyFor` da `no-referrer` para las dos rutas exactas y
    `strict-origin-when-cross-origin` para `/api/v1/auth/password-resets-x`, para la ruta con barra final y para el inicio de
    sesión [WE111]; una prueba cruza `NO_REFERRER_PATHS` con los patrones de recuperación esperados. Crear
    `NoReferrerRoutesIT.java` sobre la cadena administrativa real con controladores del arnés en las dos rutas: `200`, `400`,
    `403` (sin `Origin`), `415` y `429` llevan exactamente una cabecera `no-referrer`, y las cabeceras base de los dos
    escenarios conservados siguen igual [WE110, «Respuesta de error con las cabeceras base», «Respuesta de éxito con las
    cabeceras base»]. **Rojo esperado:** `strict-origin-when-cross-origin` en las dos rutas.
  - **VERDE.** `SecurityHeadersFilter.referrerPolicyFor(String)` y `apply(HttpServletRequest, HttpServletResponse)`;
    `ProblemErrorReportValve` usa la sobrecarga.
  - **Demostración deliberada.** Fijar la cabecera en el controlador del arnés en lugar del filtro pone en rojo el `403` y el
    `429` de [WE110].
  - **Verificación.** `-DskipITs -Dtest='SecurityHeadersFilterTest'` y `-Dit.test='NoReferrerRoutesIT'`; cierre del PR con
    `./mvnw verify -Pmutation-gate`. Commit: `feat(web): send no-referrer on the password recovery routes`. — Escenarios WE110,
    WE111 y los dos conservados de las cabeceras base.

## Fase 3: ruta de solicitud (cierra H3)

- [ ] 3.1 **PR 3 `password-reset-request-endpoint` (parte 1): ruta de solicitud, respuesta aceptada uniforme, catálogo de
  mensajes, inversiones y contrato (decisiones 1, 2, 10 y 11).**
  - **ROJO.** Crear `test/java/com/confia/identity/web/PasswordResetRequestEndpointIT.java` sobre el proceso administrativo real:
    respuesta aceptada sin `Set-Cookie`, una emisión y un asiento [I312]; igualdad de las tres respuestas (estado, cuerpo byte a
    byte, cabeceras salvo `Date`) con su control negativo [I313, I354, I318]; cinco cuerpos inválidos con cero invocaciones y
    cero asientos [I314]; `X-Institution-Id` ajena ignorada [I315]; `Accept-Language: en-US` no cambia el texto [WE116]; asientos
    con el origen de la petición [I347, parte de solicitud]; `no-referrer` en el `202` [WE110]. Crear
    `ResponseMessageCatalogTest.java` [WE115]. Invertir `WebEdgeScopeExclusionInventoryTest` (BI52: exactamente la ruta de
    solicitud en producción; el fixture sigue detectado; variante `password-reset-links` falla) [BI143, BI144],
    `PublicRouteAllowListTest` (`publicRoutesOf` y `productionRoutesOf` con la ruta) [WE106, WE107, WE108],
    `OpenApiContractSnapshotTest` (la operación con `202`, `400`, `403`, `415`, `429` y `503`, sin `401` ni `Idempotency-Key`)
    [BI147, BI148] y comprobar con `ProcessBeanIsolationTest` que el portal y el trabajador no tienen el bean [BI149] y
    que el portal responde `401` [WE109]. **Rojos esperados:** ruta inexistente (`404` convertido en `401` por la cadena);
    inventario BI52 con conjunto vacío.
  - **VERDE.** Crear `identity/web/{PasswordResetRequestController,PasswordResetRequestBody,PasswordResetRequestAcceptedResponse}
    .java` (con `@RateLimited(policy = "admin-password-reset-request")`), `shared/web/message/ResponseMessages.java`, su bean en
    `WebEdgeConfiguration` y `resources/i18n/messages.properties`. Editar `PublicEndpoints` (constante y `forAdmin`),
    `IdentityConfiguration` (bean `RequestPasswordReset` del proceso administrativo y Javadoc de la línea 64) y la instantánea
    `apps/api/openapi/admin.openapi.json`. Regenerar `packages/contracts`.
  - **Demostración deliberada.** Cambiar el mensaje solo para la cuenta inexistente pone en rojo [I313] por el cuerpo.
  - **Verificación.** `-Dit.test='PasswordResetRequestEndpointIT'`; `-DskipITs -Dtest='ResponseMessageCatalogTest,
    WebEdgeScopeExclusionInventoryTest,PublicRouteAllowListTest,OpenApiContractSnapshotTest,ProcessBeanIsolationTest'`; y desde la
    raíz `pnpm --filter @confia/contracts generate && pnpm --filter @confia/contracts typecheck && pnpm --filter @confia/admin-web
    typecheck`. Commit: `feat(identity): expose the password reset request endpoint with a uniform 202`. — Escenarios I312 a
    I315, I318, I347 (solicitud), I354, WE106 a WE110, WE115, WE116, BI143, BI144, BI147 a BI149.

- [ ] 3.2 **PR 3 (parte 2): H3 sobre la ruta real y la regla de política (P1 y P2).**
  - **ROJO.** Crear `PasswordResetRequestRateLimitClosesH3IT.java` con la política real: 10 solicitudes llegan al caso de uso y
    la undécima recibe `429` con `too-many-requests` y `Retry-After` positivo, con 10 invocaciones y 10 asientos [I316]; otra IP
    sigue recibiendo `202` [I317]; agotar `admin-password-reset-request` no afecta a la confirmación simulada ni al inicio de sesión
    [WE117]; la solicitud no depende de `RateLimitFailures` y el contador de fallos sigue en cero [WE118]. Ampliar
    `SessionRoutesRateLimitedRuleTest` con la convención de recuperación y el fixture permanente
    `architecture/fixture/ratelimit/BadPasswordResetHandlerWithoutPolicy` [BI145, BI146] y la aserción de [WE74] sobre la
    solicitud. **Rojo esperado:** con la anotación retirada temporalmente, la undécima responde `202`.
  - **VERDE.** Ningún código nuevo si 3.1 ya anotó el manejador; la tarea cierra la prueba de H3 y la regla.
  - **Condición de cierre (H3).** La casilla no se marca sin: (a) el controlador, la política aplicada, la regla y
    `PasswordResetRequestRateLimitClosesH3IT` en el **mismo PR**; (b) la demostración registrada; (c) la reserva declarada en el
    PR: estado por proceso y `trusted-proxies` vacío hasta el cambio 11 (P1).
  - **Demostración deliberada.** Quitar `@RateLimited` del manejador pone en rojo [I316] (la undécima responde `202`) y la regla
    [BI145].
  - **Verificación.** `-Dit.test='PasswordResetRequestRateLimitClosesH3IT'` y `-DskipITs -Dtest='SessionRoutesRateLimitedRuleTest'`;
    cierre con `./mvnw verify -Pmutation-gate`. Commit: `test(identity): prove the per-address limit of the reset request (H3)`.
    — Escenarios I316, I317, WE74 (solicitud), WE117, WE118, BI145, BI146.

## Fase 4: puerta de tiempo (cierra H4)

- [ ] 4.1 **PR 4 `password-reset-request-timing-gate`: puerta de 200 + 200 y control negativo con trabajo añadido (decisión 9).**
  - **ROJO.** Crear `identity/web/PasswordResetRequestTimingGateIT.java` (forma de `LoginTimingGateIT`, política elevada por
    propiedades, cada respuesta comprobada como `202`), `DelayingIssuanceScheduler.java` (gira 100 ms sobre `System.nanoTime()`
    con un interruptor estático) y `DelayingIssuanceSchedulerInitializer.java` (registrado con `context.initializer.classes`).
    Escribir **primero el control negativo** [BI140]: con el interruptor encendido la diferencia supera 50 ms y es de al menos 80
    ms. Antes de existir el decorador, el control falla porque la diferencia medida es de pocos milisegundos (rojo observado y
    registrado: demuestra que el control no es verdadero por construcción). Comprobar en el rojo si la programación repetida de la
    misma cuenta cambia de costo (riesgo del diseño §11) y registrar lo observado.
  - **VERDE.** La puerta real pasa con 50 ms como máximo [I333, BI141]. Borrar
    `identity/application/PasswordResetRequestTimingReportIT.java` [BI142]. Registrar la duración de `verify` en
    `apply-progress.md`.
  - **Demostración deliberada.** El propio control negativo: dejar el interruptor encendido en la prueba real la pone en rojo por
    la diferencia medida.
  - **Verificación.** `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
    -Dit.test='PasswordResetRequestTimingGateIT' -Dfailsafe.failIfNoSpecifiedTests=false`; cierre con
    `./mvnw verify -Pmutation-gate` y su duración. Commit: `test(identity): gate the timing of the reset request over HTTP (H4)`.
    — Escenarios I333, BI140, BI141, BI142.

## Fase 5: preparación de la confirmación

- [ ] 5.1 **PR 5 `password-reset-confirmation-groundwork` (parte 1): `SecondFactorProof.presented` y `Unreadable` (decisión 5).**
  - **ROJO.** Crear `identity/application/SecondFactorProofTest.java`: ninguno o en blanco da `None`; `492039` da `Totp`; `12ab`,
    `12345` y `1234567` dan `Unreadable`; un código de recuperación válido da `RecoveryCode` y uno mal formado `Unreadable`.
    Ampliar `ResetPasswordWithTokenIT.java`: con MFA activa, `Unreadable` da `SecondFactorRejected`, retardo cero, token vivo,
    asiento con `SECOND_FACTOR_INVALID` y ningún verificador invocado; sin MFA activa, se ignora [I340]. **Rojo esperado:**
    variante y fábrica inexistentes.
  - **VERDE.** Variante y fábrica en `SecondFactorProof.java`; rama `Unreadable` en el `switch` de `ResetPasswordWithToken`.
  - **Demostración deliberada.** Tratar `Unreadable` como `None` pone en rojo [I340] (desenlace `SecondFactorMissing`).
  - **Verificación.** `-DskipITs -Dtest='SecondFactorProofTest'` y `-Dit.test='ResetPasswordWithTokenIT'`. Commit:
    `feat(identity): read a presented second factor without trusting its shape`. — Escenario I340 (caso de uso).

- [ ] 5.2 **PR 5 (parte 2): cuatro códigos de Problem Details (decisión 6).**
  - **ROJO.** Ampliar `ProblemCodeTest`, `ProblemCatalogCoverageTest` y `ProblemResponsesTest`: estados 400, 422, 422 y 422 y
    ningún `WWW-Authenticate` [WE112]; el texto de `password-reset-token-invalid` no nombra la causa [WE113]; el intervalo del
    texto de `password-length-invalid` coincide con `StaffPasswordLengthPolicy`, con la variante «entre 8 y 128» que falla [WE114].
    **Rojo esperado:** códigos inexistentes.
  - **VERDE.** Cuatro entradas en `ProblemCode.java` y en `i18n/problems.properties` (es-HN).
  - **Demostración deliberada.** Cambiar el texto a «entre 10 y 128» pone en rojo [WE114].
  - **Verificación.** `-DskipITs -Dtest='ProblemCodeTest,ProblemCatalogCoverageTest,ProblemResponsesTest'`; cierre del PR con
    `./mvnw verify -Pmutation-gate`. Commit: `feat(web): add the problem codes of the password reset confirmation`. —
    Escenarios WE112, WE113, WE114.

## Fase 6: ruta de confirmación

- [ ] 6.1 **PR 6 `password-reset-confirmation-endpoint` (parte 1): ruta, cinco desenlaces, retardo, inversiones y contrato
  (decisiones 4 y 11).**
  - **ROJO.** Crear `identity/web/PasswordResetEndpointIT.java`: `204` sin cuerpo ni `Set-Cookie`, familias revocadas con
    `password_change`, contraseña vieja rechazada y nueva aceptada [I335]; cuatro motivos del token con respuestas idénticas
    [I336]; longitud 11 y 129 con `422` y token vivo [I337]; segundo factor faltante [I338]; segundo factor rechazado con la espera
    registrada por el doble del temporizador después de confirmar [I339]; `12ab` y `x` dan `422`, nunca `400` [I340]; cuatro
    cuerpos inválidos con cero invocaciones [I341]; código de recuperación consumido [I342]; token en la consulta ignorado [I343];
    asientos con el origen [I347, parte de confirmación]; ninguna ruta restablece otra cuenta [I355]; `no-referrer` en `204`,
    `400` y `422` [WE110]. Ampliar `WebEdgeScopeExclusionInventoryTest` (BI52: exactamente las dos rutas) [BI143],
    `PublicRouteAllowListTest` [WE106 a WE108], `OpenApiContractSnapshotTest` (`204`, `400`, `403`, `415`, `422`, `429` y `503`)
    [BI147], `SessionRoutesRateLimitedRuleTest` y [WE74] con la confirmación [BI145], y buscar `PASSWORD_RESET_LINK_BASE_URL` en
    producción [I353]. **Rojos esperados:** ruta inexistente; BI52 con una sola ruta.
  - **VERDE.** Crear `identity/web/{PasswordResetController,PasswordResetBody}.java` con `@RateLimited(policy =
    "admin-password-reset")`, materializador, registro de fallos solo con `TokenRejected` y `SecondFactorRejected`, y `switch`
    exhaustivo sin `default`. Editar `PublicEndpoints` y la instantánea; regenerar `packages/contracts`.
  - **Demostración deliberada.** Traducir `TokenRejected` con un código por motivo pone en rojo [I336]; emitir la cookie en
    `Completed` pone en rojo [I335].
  - **Verificación.** `-Dit.test='PasswordResetEndpointIT'`; `-DskipITs -Dtest='WebEdgeScopeExclusionInventoryTest,
    PublicRouteAllowListTest,OpenApiContractSnapshotTest,SessionRoutesRateLimitedRuleTest'`; `pnpm --filter @confia/contracts
    generate && pnpm --filter @confia/contracts typecheck && pnpm --filter @confia/admin-web typecheck`. Commit:
    `feat(identity): expose the password reset confirmation endpoint`. — Escenarios I335 a I343, I347 (confirmación), I353, I355,
    WE74 (confirmación), WE106 a WE108, WE110, BI143, BI145, BI147.

- [ ] 6.2 **PR 6 (parte 2; costura de reserva hacia el PR 7): política `admin-password-reset` sobre la ruta real (P3).**
  - **ROJO.** Crear `PasswordResetRateLimitIT.java`: la vigésima primera petición de un minuto recibe `429` con 20 invocaciones
    [I344]; el décimo token rechazado restringe la IP [I345]; contraseñas fuera de longitud y segundo factor faltante no
    restringen [I346]; agotar la confirmación no afecta a la solicitud [WE117]. **Rojo esperado:** con el registro de fallos
    retirado temporalmente, [I345] no restringe.
  - **VERDE.** Sin código nuevo si 6.1 registra los fallos; la tarea cierra la prueba.
  - **Demostración deliberada.** Contar `PasswordRejected` como fallo pone en rojo [I346].
  - **Verificación.** `-Dit.test='PasswordResetRateLimitIT'`; cierre del PR con `./mvnw verify -Pmutation-gate`. Commit:
    `test(identity): prove the rate limit of the password reset confirmation`. Si el PR 6 supera 800 líneas, esta tarea pasa
    entera al PR 7 (la anotación y la regla se quedan en el 6). — Escenarios I344, I345, I346, WE117 (confirmación).

## Fase 7: redacción y cierre

- [ ] 7.1 **PR 7 `recovery-redaction-and-closing` (parte 1): centinelas sobre el recorrido completo.**
  - **ROJO.** Crear `identity/web/PasswordRecoveryFlowRedactionIT.java` (forma de `SessionFlowRedactionIT`): centinelas para
    identificador, token, contraseña, código TOTP y código de recuperación; recorrido de la respuesta aceptada, el `429` de la
    solicitud, los cinco desenlaces, sus `400` y su `429`; búsqueda en el registro estructurado, en mensajes de excepción y en
    cuerpos [I350]; el `400` de `newPassword` demasiado largo nombra el campo sin el centinela [I351]. Control negativo: una
    variante que registra el cuerpo hace fallar la búsqueda.
  - **VERDE.** Ningún código nuevo esperado; si aparece una fuga, se corrige en la clase que la produce y se registra en
    `apply-progress.md`.
  - **Demostración deliberada.** El propio control negativo.
  - **Verificación.** `-Dit.test='PasswordRecoveryFlowRedactionIT'`. Commit: `test(identity): prove the recovery flow leaves no
    secret behind`. — Escenarios I350, I351.

- [ ] 7.2 **PR 7 (parte 2): cierre documental de H3 y H4 y verificación final (decisión 12).**
  - **Contenido.** Notas fechadas en `docs/03-seguridad.md` §4.6 (puerta y cierre de H4), §4.7 (rutas de P4, token en el
    fragmento y en el cuerpo, `no-referrer`, extensión de D2 a H3) y §10 (separación por IP y por cuenta, P2; política de la
    confirmación, P3); `docs/05-infraestructura-y-despliegue.md` (las dos políticas y sus propiedades);
    `docs/09-roadmap-y-fases.md` (S3 entregado); `openspec/changes/foundations-plan/exploration.md` (cierre de H3 y H4 y
    corrección de la fila de S3). Ningún texto original de H3 o H4 se reescribe [I319, I334, I352].
  - **Verificación.** Lectura estructural de las notas (fecha, referencia a escenarios, cuerpos intactos con `git diff`); `./mvnw
    verify -Pmutation-gate` completo sobre el árbol final; `pnpm --filter @confia/contracts generate && pnpm --filter
    @confia/contracts typecheck && pnpm --filter @confia/contracts test`. Commit: `docs(security): close conditions H3 and H4 of the
    password recovery`. — Escenarios I319, I334, I352.

## Trazabilidad

| Escenario | Tarea |
|---|---|
| I312, I313, I314, I315, I318, I354 | 3.1 |
| I316, I317 | 3.2 |
| I319, I334, I352 | 7.2 |
| I333 | 4.1 |
| I335 a I339, I341 a I343, I353, I355 | 6.1 |
| I340 | 5.1 (caso de uso) y 6.1 (HTTP) |
| I344, I345, I346 | 6.2 |
| I347 | 3.1 (solicitud) y 6.1 (confirmación) |
| I348, I349 | 1.1 |
| I350, I351 | 7.1 |
| WE70, WE73, WE93 | 2.1 |
| WE71, WE72, WE92 (conservados, sin cambio de comportamiento) | 2.1 (se re-ejecutan) |
| WE74 | 3.2 (solicitud) y 6.1 (confirmación) |
| WE106, WE107, WE108 | 3.1 y 6.1 |
| WE109, WE115, WE116 | 3.1 |
| WE110 | 2.2 (arnés), 3.1 y 6.1 (rutas reales) |
| WE111 y los dos escenarios conservados de las cabeceras base | 2.2 |
| WE112, WE113, WE114 | 5.2 |
| WE117 | 3.2 y 6.2 |
| WE118 | 3.2 |
| BI140, BI141, BI142 | 4.1 |
| BI143 | 3.1 y 6.1 |
| BI144 | 3.1 |
| BI145 | 3.2 y 6.1 |
| BI146 | 3.2 |
| BI147 | 3.1 y 6.1 |
| BI148, BI149 | 3.1 |

## Riesgos de la cadena

| Riesgo | Mitigación |
|---|---|
| 5a de rbac no está en `main` | Precondición dura; no se empieza |
| El PR 3 o el 6 superan 800 líneas | Costuras nombradas: 3.2 y 6.2 pueden pasar al PR siguiente solo si la anotación, la entrada pública y las inversiones se quedan con la ruta |
| Choque de numeración con el cambio 14 | Coordinar antes de archivar (diseño §14) |
| La puerta alarga `verify` más allá de su presupuesto | Medición registrada en 4.1; si excede, se consulta al propietario antes de reducir intentos |

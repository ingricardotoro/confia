# Propuesta: módulo de identidad y autenticación con contraseña

- **Cambio:** `identity-module-and-password-authentication` — primero de tres cambios SDD
  secuenciales en que se dividió el cambio 7 de F0
- **Fase del roadmap:** F0, cambio 7 de 13 (`docs/09-roadmap-y-fases.md` §3)
- **Exploración:** `openspec/changes/identity-module-and-password-authentication/exploration.md`
  (compartida con los dos cambios siguientes; describe el cambio 7 completo, antes de cualquier
  división)
- **División aprobada:** nota del 2026-09-24 en `openspec/changes/foundations-plan/exploration.md`
  §1, en dos pasos. El primer paso partió el cambio 7 en identidad-sin-HTTP y sesión-y-exposición.
  El segundo paso, el mismo día, partió a su vez la mitad de identidad en este cambio y en
  `mfa-totp-and-password-recovery`, porque la propuesta de la mitad completa pronosticó de 14 a 16
  tareas contra el límite de quince de `openspec/changes/README.md`.
- **Cambios siguientes de la secuencia:**
  1. `mfa-totp-and-password-recovery` — cifrado de columna con sobre de llaves y su ADR, TOTP,
     inscripción del segundo factor, códigos de recuperación, y recuperación de contraseña.
     **Depende de este cambio**: no puede cifrar ni inscribir un segundo factor sin la cuenta de
     personal que este cambio crea.
  2. `session-tokens-and-web-layer` — JWT, JWKS, rotación de token de refresco, capa `web`, RBAC
     genérico, `CurrentInstitutionProvider` real. **Depende de los dos anteriores.**
- **Rama:** `change/staff-authentication-mfa-sessions`
- **Depende de:** cambios 1, 2, 4, 5A, 5B y 6, todos fusionados y archivados
- **Estado:** pendiente de aprobación del propietario (`openspec/config.yaml`, `rules.proposal`)

## Intención

`openspec/specs/identity/spec.md` es una capacidad **publicada** con ocho requisitos, y en el árbol
**no existe ni una sola clase bajo `com.confia.identity`**: verificado, el paquete no aparece en
`apps/api/app/src/main/java/com/confia/`, donde hoy solo viven `bootstrap`, `organization` y
`shared`. Tampoco existe ninguna tabla de identidad: las migraciones aplicadas son `V1`
(`organization_institution`), `V2` y `V3` (`shared_audit_log` y su encadenamiento) y `V4`
(`shared_idempotency_key`).

Eso deja tres compromisos abiertos, y los tres se pagan —parcialmente— aquí:

1. **Una capacidad publicada sin sustrato.** Ocho requisitos con dieciséis escenarios prometen
   comportamiento que hoy nada sostiene. A diferencia de `payments`, que promete para F3, `identity`
   promete para **F0**: es el entregable 3, y los cambios 8 y 9 dependen de él. Este cambio entrega
   el sustrato —el módulo, el esquema, la cuenta— y una parte de los dieciséis escenarios; el resto
   los cierran `mfa-totp-and-password-recovery` y `session-tokens-and-web-layer`.
2. **El sistema no tiene todavía su segundo módulo de negocio, y una puerta espera ese momento.**
   `openspec/specs/build-integrity/spec.md` (líneas 26–32) difiere el escenario «Cada módulo usa solo
   su propio dominio» y nombra al cambio 7 como **el único que puede demostrarlo**, porque su
   premisa exige dos módulos de negocio existentes. `organization` es el primero; `identity` es el
   segundo, y este cambio es el que lo trae al árbol.
3. **No existe ninguna acción sensible de producción que auditar, y por eso la bitácora sigue sin
   escritor.** El cambio 5B excluyó el adaptador de escritura de auditoría en Java con un motivo
   escrito y un dueño nombrado: «No hay ninguna acción sensible en producción que auditar hasta los
   **cambios 7 y 8**» (`openspec/changes/archive/2026-09-22-audit-log-and-transaction-runner/proposal.md`,
   líneas 115–118). Autenticarse y fallar son exactamente esas acciones, y `docs/03-seguridad.md`
   §12.2 las enumera en su primera fila. Este cambio entrega el **primer dueño real de producción**
   del adaptador de escritura de auditoría que el cambio 5B difirió; inscribir el segundo factor y
   recuperar la contraseña son también acciones auditables de esa misma fila, pero las hereda
   `mfa-totp-and-password-recovery`, junto con el mecanismo que las produce.

Este cambio es **fundación de identidad sin exposición HTTP y sin segundo factor en ninguna forma**.
No cifra nada, no emite ningún token, no abre ningún endpoint y no arranca ningún proceso de
producción. Su valor se mide por lo que hace posible: `mfa-totp-and-password-recovery` no puede
cifrar un secreto TOTP sin una cuenta a la que asociarlo; `session-tokens-and-web-layer` no puede
emitir una sesión sin una identidad verificada; y el cambio 8 no puede asignar permisos a usuarios
que no existen.

## Alcance

### Dentro de alcance

1. **Módulo de negocio `identity`**, con sus cuatro capas conceptuales según ADR-0002 y la regla
   hexagonal ya vigente para `organization`: `domain`, `application`, `infrastructure`. **Sin
   paquete `web`**: ver «Fuera de alcance».
2. **Cierre del escenario diferido W2** de `build-integrity` («Cada módulo usa solo su propio
   dominio»), que este cambio es el único capaz de demostrar porque introduce el segundo módulo de
   negocio real.
3. **Migración `V5` del esquema de identidad**, con nombres según la regla 3 de ADR-0015 —prefijo
   estricto `<módulo>_<entidad>`, con el precedente literal de `shared_audit_log` y
   `shared_idempotency_key`—: la cuenta de personal y su estado de bloqueo. **Nada más.** Sin
   columna, tabla ni interfaz reservada para el segundo factor o su cifrado: ver la nota de «Fuera
   de alcance» sobre trabajo «preparado para». Cada tabla con `institution_id NOT NULL` (ADR-0009,
   punto 1), toda restricción única incluyéndolo (punto 2), seguridad a nivel de fila **habilitada y
   forzada** con el mismo patrón `NULLIF` de `V1` a `V4`, y `REVOKE ALL ... FROM PUBLIC` más los
   `GRANT` exactos que `docs/03-seguridad.md` §6.1 concede a cada rol.
4. **Extensión de las puertas de esquema existentes, sin reescribirlas**: filas de la tabla nueva en
   `RolePrivilegeMatrixIT` para los cinco roles —incluido explícitamente que `confia_portal_app`
   **no tiene ninguno** sobre la cuenta de personal, que `docs/03` §6.1 exige literalmente—, y
   verificación de que las puertas genéricas de `MultiTenantSchemaIT` pasan **sin lista de
   exclusión**.
5. **Hash de contraseña con Argon2id** tras un puerto de `application`, con su adaptador en
   `infrastructure`, y con la pimienta de 32 bytes fuera de la base de datos que exige
   `docs/03-seguridad.md` §4.1. Ver el punto de diseño **P1**.
6. **Caso de uso de autenticación con contraseña**, que devuelve un **resultado tipado** y no un
   token, con exactamente dos desenlaces porque en este cambio no existe segundo factor en ninguna
   forma. Ver «La costura con los cambios siguientes».
7. **Bloqueo por intentos fallidos con retroceso exponencial**, en su dimensión **por cuenta**, con
   el estado en PostgreSQL. Ver **D1** y **D2**.
8. **Prevención de enumeración de usuarios en la mitad que no es HTTP**: resultado idéntico para
   cuenta existente e inexistente, y verificación Argon2id contra un hash señuelo constante para que
   el tiempo observable no distinga los casos (`docs/03` §4.6).
9. **Puerto y adaptador jOOQ de escritura de la bitácora de auditoría**, que el cambio 5B difirió con
   dueño nombrado, con los eventos de identidad de `docs/03` §12.2 que este cambio produce —
   autenticación exitosa, autenticación fallida, ciclo de bloqueo— escritos **dentro de la misma
   transacción** que el efecto que los produce.
10. **Notas editoriales fechadas** sobre `docs/03-seguridad.md` §4.4 y §6.1, y actualización de
    `docs/09-roadmap-y-fases.md` en el entregable 3 con sus tres mitades diferidas nombradas.

### Fuera de alcance

Cada exclusión lleva **destino nombrado**, y las que un cambio posterior vaya a cerrar se escriben
**dentro de bloques `### Requisito:`** del delta, nunca como prosa suelta: es la disciplina que el
cambio 5B estableció y el cambio 6 aplicó, después de que el cambio 5A tuviera que retirar a mano un
«Fuera de alcance» que su propia entrega volvió falso.

**Todo lo siguiente pertenece a `session-tokens-and-web-layer`**, por la decisión del propietario del
2026-09-24 que dividió identidad de sesión. Esta propuesta no lo entrega, y tampoco lo entrega
«preparado para»:

- La capa `web` completa y el primer controlador, con la conversión de `optionalLayer("Web")` en capa
  obligatoria y la retirada de su entrada de `EmptyShouldExceptionInventoryTest`.
- Spring Security, y con ella toda la cadena de filtros.
- JWT con EdDSA, JWKS, y la emisión de cualquier token de sesión o de refresco.
- Rotación del token de refresco con detección de reutilización y revocación de familia.
- El adaptador real de `CurrentInstitutionProvider` sobre el token y su prueba de ADR-0009.
- El registro de la fuente de datos de producción y la retirada de la exclusión de
  `DataSourceAutoConfiguration` en los tres puntos de entrada.
- La mecánica genérica de RBAC con su guarda de arranque.
- El cableado HTTP de la idempotencia que el cambio 6 difirió, CSRF, CSP, Problem Details y
  springdoc.

**Todo lo siguiente pertenece a `mfa-totp-and-password-recovery`**, por la decisión del propietario
del 2026-09-24 que dividió la mitad de identidad a su vez. El corte es exactamente donde **entra el
cifrado de columna**: este cambio hashea y no cifra nada, así que no necesita el sobre de llaves, la
tabla de llaves de datos ni un ADR nuevo. Esta propuesta **no deja trabajo «preparado para» ese
cifrado**: ni columna reservada en `V5`, ni interfaz vacía, ni tabla creada sin usar. Si no se usa en
este cambio, no entra en este cambio.

- **Cifrado del secreto TOTP en la aplicación, con sobre de llaves**: algoritmo y formato de
  `docs/03` §7.3, tabla de llaves de datos con `institution_id` y política de fila, llave maestra
  por el entorno del proceso, y el ADR nuevo que la decisión exige.
- La sonda sobre disponibilidad de `pgcrypto` en la imagen (si el diseño de ese cambio decide
  apoyarse en ella).
- **MFA TOTP** completo: verificación según RFC 6238, inscripción del segundo factor, y los diez
  códigos de recuperación de un solo uso.
- **Recuperación de contraseña con token de un solo uso**, con su ciclo de vida y su concurrencia.
- El delta de redacción sobre el requisito publicado «MFA obligatoria para roles con escritura
  financiera o de configuración», cuyos dos escenarios terminan en emisión de token y en la decisión
  de qué desenlaces del segundo factor existen.

Y además, con destino nombrado fuera de los dos cambios anteriores:

- **Dimensión por dirección IP del retroceso exponencial** y las tres reglas de IP de `docs/03`
  §4.4, más el límite de tasa de §10: **`session-tokens-and-web-layer`** para el control, porque una
  dirección IP solo existe en el borde HTTP y aquí no hay petición; y **cambio 11**
  (`containerization-and-cicd-pipeline`) para el aprovisionamiento de Redis que hoy no existe. Ver
  **D1**.
- **Verificación contra listas de contraseñas comprometidas** (`docs/03` §4.2, sus dos capas) y
  **rehash transparente de parámetros** (§4.1): ningún requisito publicado de `identity` los exige
  —los ocho requisitos no mencionan política de contraseñas—, y la lista local de cien mil entradas
  es un artefacto empaquetado con su propia procedencia y licencia que no cabe en el presupuesto de
  revisión de este cambio. Destino: **cambio 8** o un cambio de identidad posterior. Ver **D5**.
- **Calibración de Argon2id en el servidor real** (`docs/03` §4.1, «Calibración obligatoria antes de
  producción»): exige el servidor de producción dentro del contenedor y con sus límites de memoria.
  No existe ningún entorno desplegado. Destino: **cambio 11**, con el valor elegido registrado en un
  ADR con fecha y hardware, como el propio §4.1 pide. Ver **D5**.
- **Prueba de extremo a extremo del flujo completo de autenticación con contraseña con Playwright**:
  el cambio 3 (frontend) no está archivado, no hay `packages/*` ni aplicación React. Destino: F1 o
  posterior, cuando exista la pantalla. Ver **D5**.
- **Identidad de encargados** (dominio `portal`): la capacidad `identity` cubre las dos audiencias,
  pero el entregable 3 de F0 es explícitamente «autenticación del personal». Destino: F2, con el
  portal.

## Capacidades

> Contrato con `/sdd-spec`.

### Nuevas

**Ninguna.** `identity` ya está publicada con ocho requisitos. Esta propuesta es un **delta puro**
sobre ella, como recomendó la exploración: la capa `web` vive dentro del módulo por la regla
hexagonal ya vigente, así que dividir la capacidad en dos no tendría dónde apoyarse.

### Modificadas

- **`identity`**: delta con `## ADDED Requirements` para el comportamiento nuevo que este cambio
  entrega sin token ni segundo factor —resultado tipado de autenticación con sus dos desenlaces,
  verificación Argon2id contra hash señuelo, estado de bloqueo persistente en PostgreSQL, y las
  exclusiones con destino nombrado— y `## MODIFIED Requirements` sobre el requisito publicado de
  bloqueo, para el escenario del sexto intento (**D3**) y, si **D2** elige la alternativa, para la
  progresión misma. El requisito de MFA obligatoria **no se toca en este delta**: sigue publicado tal
  como está y lo modifica `mfa-totp-and-password-recovery`.
- **`build-integrity`**: delta con `## MODIFIED Requirements` sobre el requisito «Aislamiento del
  `domain` entre módulos», para incorporar el escenario diferido W2 que este cambio cierra, y
  retirar la nota de diferimiento de las líneas 26–32.

### Cobertura de los dieciséis escenarios publicados de `identity`, uno a uno

| # | Requisito | Escenario | Este cambio | Cambio dueño de lo que falta |
|---|---|---|---|---|
| 1 | Autenticación con contraseña | Credenciales correctas | **Parcial**: verifica la combinación y audita el éxito; no emite ningún artefacto de sesión | `session-tokens-and-web-layer` emite el token |
| 2 | Autenticación con contraseña | Contraseña incorrecta | **Completo**: error genérico, ningún artefacto de sesión, intento fallido registrado | — |
| 3 | MFA obligatoria | Cajero con MFA configurada | **No toca** | `mfa-totp-and-password-recovery` (mecanismo) y `session-tokens-and-web-layer` (token) |
| 4 | MFA obligatoria | Rol administrativo sin MFA configurada | **No toca** | Ídem |
| 5 | Bloqueo por intentos fallidos | Primer bloqueo tras cinco fallos | **Completo**, con **D2** resuelta | — |
| 6 | Bloqueo por intentos fallidos | Segundo ciclo con retroceso mayor | **Completo**, incluida la auditoría de cada ciclo con su duración | — |
| 7 | Rotación de token de refresco | Renovación normal | **No toca** | `session-tokens-and-web-layer` |
| 8 | Rotación de token de refresco | Reuso de token invalidado | **No toca** | `session-tokens-and-web-layer` |
| 9 | Separación entre dominios de identidad | Token de encargado a API administrativa | **No toca** | `session-tokens-and-web-layer` |
| 10 | Separación entre dominios de identidad | Token de personal a API del portal | **No toca** | `session-tokens-and-web-layer` |
| 11 | Recuperación de contraseña | Recuperación dentro de la ventana | **No toca** | `mfa-totp-and-password-recovery` |
| 12 | Recuperación de contraseña | Token expirado o superado | **No toca** | `mfa-totp-and-password-recovery` |
| 13 | Prohibición de enumeración | Correo existente | **Parcial**: resultado y tiempo indistinguibles a nivel de caso de uso | `session-tokens-and-web-layer` entrega `202 Accepted` y el cuerpo literal |
| 14 | Prohibición de enumeración | Correo inexistente | **Parcial**, ídem, y ningún correo enviado (no hay adaptador de envío en ningún cambio de esta secuencia) | Ídem |
| 15 | Autorización basada en permisos | Permiso presente en la sesión | **No toca** | `session-tokens-and-web-layer` y **cambio 8** |
| 16 | Autorización basada en permisos | Cliente afirma un permiso que la sesión no tiene | **No toca** | Ídem |

## Las decisiones que esta propuesta resuelve

### 1. El estado del bloqueo vive en PostgreSQL, no en Redis

**Lo verificado.** `docs/03-seguridad.md` §4.4 dice literalmente «con estado en Redis». En el árbol
**no hay cliente de Redis** en `apps/api/app/pom.xml`, **no hay infraestructura de
aprovisionamiento** —el cambio 11 sigue sin archivar y no existe ningún archivo de Compose— y el
único motor disponible y probado es PostgreSQL, con Testcontainers desde el cambio 5A.

**Recomendación: PostgreSQL en este cambio.** Tres motivos, en orden de peso:

1. **Atomicidad con la auditoría, que es una regla no negociable.** La regla 14 de `CLAUDE.md` exige
   que toda acción sensible escriba en la bitácora, y `docs/03` §12.2 enumera «bloqueo por retroceso
   exponencial» como evento obligatorio. Registrar el intento fallido, aplicar el retroceso y
   escribir el asiento de auditoría en **una sola transacción** de `TransactionRunner` solo es
   posible si el estado vive en el mismo motor. Partirlo entre Redis y PostgreSQL crea una ventana de
   fallo parcial en la que la cuenta queda bloqueada sin rastro, o auditada sin bloqueo.
2. **Durabilidad.** Un contador de bloqueo es un control de seguridad. Redis sin persistencia lo
   pierde al reiniciar, y un atacante que provoque ese reinicio limpia el bloqueo de todas las
   cuentas. PostgreSQL da durabilidad por construcción, y el aislamiento por institución que ADR-0009
   exige, que un almacén de claves y valores no da gratis.
3. **Costo de introducir la primera dependencia de Redis.** Serían: la dependencia en el POM, su
   configuración por perfil, un segundo contenedor en la suite de integración, su modo de fallo —y
   `docs/03` §3 punto 4 exige **fallo cerrado**: si el verificador no responde, se rechaza— y el
   aprovisionamiento que no existe. Todo eso para un almacén cuyo único consumidor en este cambio es
   un caso de uso.

**Costo aceptado, dicho en voz alta.** Cada intento fallido se convierte en una escritura en
PostgreSQL, con contención sobre la fila de la cuenta bajo un ataque de fuerza bruta concentrado. Es
aceptable aquí: el retroceso exponencial acota por diseño la tasa de intentos por cuenta, que es
justo lo que hace que el volumen no crezca. La dimensión donde el argumento se invierte es la de IP,
y por eso es la que se difiere.

**Qué queda pendiente de migrar a Redis, y en qué cambio.** Las tres reglas de dimensión de IP de
`docs/03` §4.4 —límite por IP, y el indicador de relleno de credenciales por IP contra cuentas
distintas— más el límite de tasa general de §10. Son volumen alto, vida corta y sin necesidad de
durabilidad: el perfil exacto de Redis. **Dueño del control: `session-tokens-and-web-layer`**, porque
una dirección IP solo existe en el borde HTTP y aquí no hay petición. **Dueño del aprovisionamiento:
el cambio 11.** Se escribe como requisito con escenario ejecutable, cierto hoy y falso el día que ese
cambio lo cierre.

**Esta recomendación no decide el modelo de bloqueo**, que es otra cosa y está en **D2**.

### 2. La costura con los cambios siguientes: qué devuelve el caso de uso cuando no existe segundo factor en ninguna forma

**El problema, exacto.** La propuesta anterior a este segundo corte diseñó un tipo sellado de cuatro
desenlaces —`Authenticated`, `SecondFactorRequired`, `SecondFactorEnrollmentRequired`, `Rejected`—
porque MFA vivía en el mismo cambio. Con la división del 2026-09-24, dos de esos cuatro desenlaces
pertenecen a un cambio que todavía no existe (`mfa-totp-and-password-recovery`), y la costura pasó de
ser de dos partes a ser de tres. No se puede resolver por comodidad manteniendo los cuatro casos aquí
«por si acaso»: eso sería exactamente el trabajo «preparado para» que el propietario prohibió, porque
dos de esos cuatro casos no tendrían ni productor ni consumidor en este cambio.

**Lo que este cambio devuelve: un tipo sellado con exactamente dos desenlaces.**

| Desenlace | Qué significa | Qué lleva |
|---|---|---|
| `Authenticated` | Contraseña correcta | Identificador de usuario e institución |
| `Rejected` | Credenciales incorrectas, cuenta inexistente, o bloqueo vigente | Un motivo **interno** que la auditoría registra y que el cliente nunca verá distinto |

**Ninguna de las dos variantes lleva un campo de alcance de autorización.** La propuesta anterior le
daba a `Authenticated` un alcance `FULL` explícito, porque necesitaba distinguirlo de un alcance
restringido de inscripción de MFA. Ese alcance restringido no existe en este cambio —no hay ningún
otro desenlace del que distinguirse—, así que un campo `scope: FULL` con un único valor posible no
sería más que una reserva especulativa para un caso futuro: el mismo defecto que el propietario
prohibió para el esquema y las tablas, aplicado al tipo de dominio. Se omite.

**Por qué el cambio 2 tiene que modificar este tipo, y no solo extenderlo, dicho con esas palabras.**
Un tipo sellado de Java es cerrado por definición: su cláusula `permits` enumera de forma exhaustiva
sus subtipos, y el compilador exige que todo `switch` sobre él cubra cada uno. Cuando
`mfa-totp-and-password-recovery` introduce `SecondFactorRequired` y
`SecondFactorEnrollmentRequired`, tiene que **editar la cláusula `permits` de este mismo tipo** —el
archivo que este cambio escribe— para añadir los dos subtipos nuevos. Eso no es una extensión en el
sentido de la programación orientada a objetos, donde un consumidor añade comportamiento sin tocar lo
existente: es una modificación literal del código que este cambio deja escrito. **Es aceptable, y la
razón no es que sea barato editar un archivo ajeno —lo es, pero esa no es la razón de fondo—, sino
que el propio mecanismo que obliga a esa edición es la garantía de seguridad que la hace segura.**

- El compilador rechaza cualquier `switch` exhaustivo sobre el tipo que no cubra los dos casos
  nuevos, en cualquier archivo, del cambio que sea. El cambio 2 no puede olvidarse de actualizar una
  rama: la construcción se rompe hasta que lo haga.
- Los dos desenlaces existentes, `Authenticated` y `Rejected`, no cambian de forma ni de significado.
  El cambio 2 añade hermanos al tipo sellado; no reescribe los que ya existen. `Authenticated` sigue
  significando exactamente lo mismo el día que `mfa-totp-and-password-recovery` se fusione: contraseña
  correcta y ningún control pendiente.
- La alternativa que evitaría tocar este archivo —dejar el tipo abierto, sin sellar, o modelar el
  resultado como un valor con un campo `kind` de enumeración en vez de una jerarquía sellada— sacrifica
  exactamente esa garantía de exhaustividad a cambio de una flexibilidad que ningún consumidor de esta
  secuencia necesita: son tres cambios secuenciales con un solo autor, no una biblioteca con terceros
  implementando el tipo. Pagar por esa flexibilidad aquí sería la misma clase de trabajo especulativo
  que el propietario ya prohibió para el esquema.

  La conclusión honesta es: **`mfa-totp-and-password-recovery` modifica el tipo sellado que este
  cambio escribe, y eso es correcto.** No es una costura sin fricción, y esta propuesta no finge que
  lo sea.

**Qué queda fijado para `session-tokens-and-web-layer`, que sigue siendo el que traduce el resultado a
HTTP y a token.**

1. **El caso de uso recibe el `SecurityContext` de su llamador** —hoy una prueba, mañana el
   controlador— exactamente como `IdempotentExecutor` lo recibe hoy. Es el precedente del árbol y
   evita inventar un mecanismo nuevo de propagación.
2. **El origen de la institución previa a la autenticación.** Ver **D4**: es una costura que hay que
   fijar aquí o `session-tokens-and-web-layer` la resolverá por su cuenta, probablemente mal.
3. **Que las decisiones de autorización viven en el dominio, no en la capa web.** Este principio no
   tiene todavía un campo que lo demuestre —no hay alcance restringido en este cambio—, pero rige
   igual para `mfa-totp-and-password-recovery` cuando introduzca el alcance de inscripción: esa
   decisión se toma en el caso de uso, nunca en un controlador. Se deja escrito aquí para que no se
   redescubra distinto en cada cambio de la secuencia.
4. **Que el motivo de `Rejected` es interno y nunca observable por el cliente**, sin importar cuántas
   causas nuevas de rechazo añadan los cambios siguientes (por ejemplo, un segundo factor inválido).

### 3. Cómo se demuestra todo esto sin capa web y sin frontend

El precedente de los seis cambios anteriores es exacto y se sigue sin invención: pruebas unitarias de
dominio más pruebas de integración reales contra PostgreSQL con Testcontainers, conectadas con el
**rol de aplicación** y no con el propietario del esquema.

| Qué | A qué nivel | Con qué |
|---|---|---|
| Cálculo del retroceso, máquina de estados del bloqueo, el tipo sellado de resultado | Unitario de `domain`, con reloj inyectado y sin `Instant.now()` dentro del dominio | JUnit, AssertJ, jqwik; cobertura 95 % y PIT 80, como exige `openspec/config.yaml` |
| Verificación Argon2id, incluida la verificación señuelo | Unitario de `application`/`infrastructure`, según dónde quede el adaptador | JUnit, AssertJ |
| Política de fila y privilegios de la tabla nueva | Integración con `confia_admin_app` y con `confia_portal_app` | `MultiTenantSchemaIT` y `RolePrivilegeMatrixIT` extendidos, más una prueba de contexto ausente que exige cero filas (`docs/03` §6.4, pruebas 1 a 4) |
| Atomicidad de efecto y auditoría | Integración con confirmación real | `CommittingPostgresIntegrationTest`: si el efecto falla, el asiento de auditoría no sobrevive |
| Intento fallido concurrente | Integración con sincronización determinista | `CyclicBarrier`, patrón ya establecido en `TransactionRunnerRetryIT` y `IdempotentExecutorConcurrencyIT`. **Nunca esperas por reloj** |
| Adaptadores jOOQ | Integración | Precedente de `JooqInstitutionRepositoryIT` y `JooqIdempotencyRecordStoreIT` |
| Uniformidad de tiempo entre cuenta existente e inexistente | Integración, **medida y reportada** | Ver la advertencia de abajo |

**Lo que no se puede demostrar todavía, declarado como hizo el cambio 6 con su criterio de salida:**

| Lo demostrado aquí | Lo no demostrado, y su dueño |
|---|---|
| El caso de uso rechaza credenciales inválidas con un resultado genérico | Que un endpoint responda `401` con el cuerpo uniforme de `docs/03` §4.6 — **`session-tokens-and-web-layer`** |
| Resultado idéntico para cuenta existente e inexistente a nivel de caso de uso | Que la respuesta HTTP sea `202 Accepted` con el mensaje literal — **`session-tokens-and-web-layer`** |
| `Authenticated` con contraseña correcta | Que se emita un token con esos permisos — **`session-tokens-and-web-layer`** |
| El bloqueo por cuenta con su retroceso | El bloqueo por IP y el indicador de relleno de credenciales — **`session-tokens-and-web-layer`**, con aprovisionamiento del **cambio 11** |
| Los parámetros de Argon2id declarados | Que estén calibrados a 250–500 ms en el servidor real — **cambio 11** |

**Advertencia honesta sobre la prueba de tiempo.** `docs/03` §4.6 especifica una prueba de 200
intentos con correo existente y 200 con inexistente, que falla si la diferencia de medianas supera
50 milisegundos. Esa prueba mide **respuestas HTTP** y aquí no hay ninguna; además, una prueba de
tiempo en una máquina compartida con un contenedor de PostgreSQL y una función de derivación
deliberadamente costosa es una candidata natural a ser escamosa. **Recomendación:** este cambio
entrega el mecanismo —verificación Argon2id contra un hash señuelo en el camino de cuenta inexistente—
y una prueba que **mide y reporta** la diferencia de medianas sin convertirla en puerta de
construcción, con la puerta real diferida a `session-tokens-and-web-layer` sobre respuestas HTTP. Una
puerta escamosa se termina desactivando, y una puerta desactivada es peor que un diagnóstico honesto.
El mecanismo sí se prueba de forma determinista: que el camino de cuenta inexistente ejecuta la
verificación señuelo se verifica por interacción, no por cronómetro.

## Enfoque

De adentro hacia afuera, como el cambio 5B: el dominio primero, porque aquí lo caro es la regla y no
el mecanismo de base de datos, que ya existe. Cada paso deja `./mvnw verify` en verde antes del
siguiente y sigue **TDD estricto** (`openspec/config.yaml`, `strict_tdd: true`, ejecutor
`./mvnw verify` en `apps/api`): rojo observado y registrado antes de cada verde, sin excepción.

### 1. El módulo y el esquema

`identity` es el segundo módulo de negocio. Su `package-info.java` lo declara como módulo de Spring
Modulith, igual que `organization`, y su sola existencia cierra el escenario diferido W2. Ninguna
clase de `identity.domain` importa de `organization.domain` ni al revés: esa es exactamente la
afirmación que el escenario diferido exige demostrar en verde.

La migración `V5` llega después de que el paquete exista, por la misma razón mecánica que obligó al
cambio 5: la puerta de prefijo de módulo de `TableOwnershipByModuleTest` se deriva de los paquetes de
producción reales, así que una tabla `identity_*` sin paquete `identity` no tendría dueño. `V5` trae
únicamente la cuenta de personal y su estado de bloqueo; ninguna otra tabla.

### 2. Contraseña, resultado tipado, bloqueo y auditoría en una sola transacción

El caso de uso recibe el `SecurityContext` de su llamador exactamente como `IdempotentExecutor` lo
recibe hoy. Dentro de **una sola** invocación de `TransactionRunner.execute(...)`: se consulta el
estado de bloqueo, se verifica la contraseña —o el señuelo, si la cuenta no existe—, se actualiza el
contador de fallos, y se escribe el asiento de auditoría. Si algo falla, no queda ni bloqueo sin
auditar ni auditoría sin bloqueo.

**El puerto de escritura de auditoría es de este cambio, y su ubicación sigue el precedente exacto
del cambio 5B**: puerto en `com.confia.shared.audit`, junto a `AuditLogReader`, y adaptador jOOQ en
`com.confia.shared.infrastructure`, junto a `JooqAuditLogReader`. La regla R1 confina jOOQ a
`infrastructure` y este cambio no la toca.

## Restricciones que este cambio no puede violar

Se escriben aquí porque un módulo de identidad es donde más barato resulta violarlas por descuido, y
porque van a ser criterio de revisión de cada pull request.

1. **Regla 11 de `CLAUDE.md`, la más delicada aquí: nunca registrar en logs contraseñas, tokens,
   cabeceras de autorización ni números de documento.** En este módulo la lista efectiva es: la
   contraseña en claro, el hash Argon2id y la pimienta. Ninguno de esos valores aparece en un mensaje
   de registro, en un mensaje de excepción, en un `toString()` ni en un aserto de prueba que los
   imprima al fallar. El diseño debe decidir cómo se garantiza —un envoltorio de valor con
   `toString()` redactado es el camino obvio— y la verificación debe comprobarlo, no confiarlo.
2. **Regla 14: toda acción sensible escribe en la bitácora.** Autenticación exitosa, autenticación
   fallida, y bloqueo por retroceso. Es la porción de la primera fila de `docs/03` §12.2 que este
   cambio produce; el resto de esa fila —alta y baja de MFA, uso de código de recuperación, cierre de
   sesión— la producen los cambios siguientes.
3. **Regla 12: consultas siempre parametrizadas.** jOOQ las genera; la lista aprobada de SQL plano de
   ADR-0015 regla 9 no se amplía en este cambio.
4. **Regla 13: secretos jamás en el repositorio, ni en ejemplos ni en pruebas.** Las credenciales
   literales de prueba —incluida la pimienta de Argon2id en los perfiles de prueba— siguen el patrón
   ya revisado de `create-test-roles.sql` líneas 8–10: valor público, declarado no secreto en su
   propio comentario, y usado solo dentro de un contenedor efímero.
5. **Reglas de dependencia:** `application` → `domain`; `infrastructure` implementa puertos de
   `application`; `domain` no importa de nadie ni conoce ningún framework —lo que sitúa Argon2id y
   jOOQ en `infrastructure`, tras puertos—; y ningún módulo importa el `domain` de otro. `identity` no
   importa `organization.domain`.
6. **Regla 7: toda operación que afecte el libro mayor ocurre en una transacción explícita.** Aquí no
   hay libro mayor, pero la regla R3 sigue vigente: el único punto que abre transacciones es
   `com.confia.shared.security`, y este cambio no abre ninguna por su cuenta.
7. **`domain` sin reloj propio.** El bloqueo depende del tiempo; lo recibe, no lo lee.

## Decisiones que requieren aprobación explícita del propietario

Numeradas para que se respondan de una en una. **D1 y D2 bloquean la especificación**; las demás
pueden resolverse durante la fase de especificación si el propietario lo prefiere.

**Dos decisiones de la propuesta anterior no aparecen en esta lista, y se registran aquí como
resueltas para que la referencia no se pierda:**

- **La antigua D1** (cifrado del secreto TOTP con sobre de llaves y ADR-0022 nuevo) **no se pide
  aquí.** Es enteramente de `mfa-totp-and-password-recovery`: este cambio no cifra nada, así que no
  tiene decisión de cifrado que aprobar.
- **La antigua D7** (presupuesto y estrategia de entrega, con la contradicción entre 400 y 800
  líneas) **quedó resuelta el 2026-09-24**: el propietario aprobó partir la mitad de identidad en dos
  cambios SDD secuenciales en vez de forzar cuatro cortes encadenados dentro de un único cambio
  sobredimensionado. No es una decisión pendiente de esta propuesta.

---

- **D1 (antes D2). El estado del bloqueo por intentos fallidos vive en PostgreSQL, no en Redis.**
  Recomendación: sí, por la sección «1» de arriba. Implica que `docs/03` §4.4 recibe una nota
  editorial fechada y que la dimensión por IP queda fuera con dueño nombrado —control en
  `session-tokens-and-web-layer`, aprovisionamiento en el cambio 11—. **Alternativa descartada:**
  introducir aquí la primera dependencia de Redis del proyecto, sin aprovisionamiento, sin Compose y
  sin un segundo consumidor, rompiendo además la atomicidad entre el bloqueo y su asiento de
  auditoría.

- **D2 (antes D3). Qué modelo de bloqueo gobierna, porque hoy hay dos y no son el mismo.** Esta
  propuesta no la resuelve sola porque cambia un parámetro de seguridad. Los dos modelos, ambos
  citados literalmente:
  - `openspec/specs/identity/spec.md`, requisito publicado: bloqueo **tras cinco intentos fallidos
    consecutivos**, un minuto el primer ciclo, cinco minutos el segundo.
  - `docs/03-seguridad.md` §4.4: **retardo** de `2^(n-3)` segundos **a partir del intento fallido
    3**, con tope de 900 segundos y contador que expira a los 30 minutos sin intentos.

  Difieren en el umbral (3 frente a 5), en la naturaleza del control (un retardo antes de responder
  frente a un bloqueo de la cuenta) y en la progresión. **Recomendación: gobierna la especificación
  publicada**, porque es el contrato aprobado, sus dos escenarios son exactos y verificables, y un
  cambio SDD no reescribe en silencio un requisito publicado; `docs/03` §4.4 recibe una nota
  editorial fechada que remite a él, con el precedente de la decisión D2 del cambio 5 sobre ADR-0003
  y de la nota del cambio 6 sobre ADR-0010. **Alternativa:** que gobierne §4.4, lo que exige un
  `## MODIFIED Requirements` sobre el requisito publicado con sus dos escenarios reescritos. El
  retardo uniforme de §4.4 aplicado también a cuentas inexistentes **se conserva en ambos casos**,
  porque pertenece a la prevención de enumeración de §4.6 y no al modelo de bloqueo.

- **D3 (antes la segunda mitad de D4). Delta de redacción sobre el escenario «Primer bloqueo tras
  cinco fallos».** Recomendación: sí. Dice que el sexto intento con la contraseña correcta «es
  rechazado por bloqueo activo, no por credenciales inválidas». Leído como comportamiento observable
  por el cliente, **contradice** la prohibición de enumeración de §4.6, que exige un único mensaje
  «Credenciales inválidas» y el mismo `401`: distinguir el bloqueo revela que la cuenta existe. El
  delta debe aclarar que la distinción es **interna y auditada**, nunca observable por el cliente, y
  que `session-tokens-and-web-layer` mapea ambos desenlaces a la misma respuesta. (La primera mitad
  de la antigua D4, el delta de redacción sobre el requisito de MFA, pertenece íntegra a
  `mfa-totp-and-password-recovery` y no se pide aquí.)

- **D4 (antes D5). La institución previa a la autenticación proviene de configuración del servidor,
  nunca de la solicitud.** Esta es una costura que hay que fijar ahora. Toda tabla `identity_*` lleva
  política de fila sobre `app.institution_id`, y una política que recibe `NULL` **deniega**
  (`docs/03` §6.2, regla 4). Pero el inicio de sesión ocurre **antes** de que exista token, y
  ADR-0009 exige que el identificador de institución provenga del token autenticado y **nunca de un
  parámetro del cliente**. El puerto `CurrentInstitutionProvider` no resuelve esto: su Javadoc dice
  que el comportamiento sin sesión autenticada «is that adaptor's own decision», y su adaptador real
  es de `session-tokens-and-web-layer`. **Recomendación:** la institución previa a la autenticación
  proviene de la configuración del proceso —despliegue de una sola institución, ADR-0009 punto 5—,
  nunca del cuerpo, la cabecera ni la consulta; este cambio recibe esa institución en el
  `SecurityContext` que le pasa su llamador y **escribe el contrato como requisito con escenario**,
  para que `session-tokens-and-web-layer` lo materialice sin reinventarlo. **Alternativa
  descartada:** una consulta global sin contexto de institución, imposible sin `BYPASSRLS`, que
  ningún rol tiene ni tendrá (`create-test-roles.sql`, líneas 11–15, `NOBYPASSRLS` en los cinco).

- **D5 (antes D6, con alcance recortado a lo que toca este cambio). Cuatro exclusiones pese a estar
  en `docs/03`.** Recomendación: excluir, con los destinos de «Fuera de alcance»: listas de
  contraseñas comprometidas y rehash transparente —**cambio 8** o un cambio de identidad
  posterior—, calibración de Argon2id —**cambio 11**—, y extremo a extremo con Playwright del flujo
  de autenticación con contraseña —F1 o posterior—. Ninguno está en los ocho requisitos publicados de
  `identity`, así que excluirlos no rompe ningún contrato publicado.

- **D6 (antes D8). Aprobación de la propuesta completa** antes de especificar, diseñar y planificar
  tareas (`openspec/config.yaml`, `rules.proposal`).

**No forman parte de estas decisiones**, por estar ya fijadas: la división del cambio 7 en tres
cambios SDD secuenciales, aprobada por el propietario el 2026-09-24 en dos pasos; el reparto de la
capa web, la sesión y RBAC a `session-tokens-and-web-layer`; el reparto del cifrado, TOTP y la
recuperación a `mfa-totp-and-password-recovery`; el modo de ejecución automático con TDD estricto; y
el presupuesto de 800 líneas de cambio efectivo por pull request (`docs/15-flujo-de-trabajo-git.md`
§3, `CLAUDE.md`).

## Puntos de diseño abiertos

No son decisiones de producto. Son preguntas técnicas que esta propuesta **no cierra a propósito**.

- **P1. Cómo se aplica la pimienta de Argon2id, y con qué biblioteca.** `docs/03` §4.1 exige una
  pimienta de 32 bytes fuera de la base de datos, «aplicado como `secret` de Argon2id». **Afirmación
  sobre biblioteca externa, no verificada contra el árbol:** el `Argon2PasswordEncoder` de
  `spring-security-crypto` no expone un parámetro de secreto en su constructor, mientras que
  `Argon2BytesGenerator` de Bouncy Castle sí lo hace con `withSecret(...)`. Si se confirma, la
  pimienta obliga a Bouncy Castle directo con codificación propia del formato `$argon2id$`, en vez
  del codificador de Spring. El diseño debe confirmarlo con una sonda antes de elegir, porque de ello
  depende una dependencia nueva y el formato almacenado que la prueba de §4.1 verifica. Ambas
  bibliotecas son dependencias nuevas sujetas a la revisión obligatoria de `docs/03` §2.6; ninguna
  está en la lista de prohibidas del `maven-enforcer-plugin` de `apps/api/pom.xml`. **Y una
  precisión de alcance:** `spring-security-crypto` no es `spring-boot-starter-security`; usar el
  primero no adelanta la cadena de filtros ni contradice el reparto de `session-tokens-and-web-layer`.
- **P2 (antes P4). Qué se escribe como `actor_label` al auditar un intento fallido contra una cuenta
  inexistente.** La columna es `NOT NULL` (`docs/03` §12.1) y el escenario publicado pide «registra el
  intento fallido asociado a la cuenta». Escribir el identificador presentado convierte la bitácora en
  un depósito de cadenas controladas por el atacante y, leída por un auditor, en un oráculo de
  enumeración. El diseño decide; la recomendación es una etiqueta constante cuando la cuenta no
  existe, y el identificador real cuando existe.
- **P3 (antes P5). Forma exacta del hash señuelo** que iguala el tiempo de la cuenta inexistente. Un
  literal mal formado falla rápido y no iguala nada; el señuelo tiene que costar lo mismo que una
  verificación real con los parámetros vigentes. El diseño fija cómo se obtiene sin introducir un
  secreto en el repositorio.
- **P4 (antes P7, con alcance recortado). Nombres exactos** de la tabla, columnas, clases, puertos y
  del tipo de resultado de autenticación. En inglés, según `CLAUDE.md`. La tabla lleva prefijo
  `identity_`, con el precedente literal de `shared_audit_log`.

## Impacto en migraciones, privilegios y auditoría

**Migraciones.** Una migración nueva, `V5`, con la tabla de la cuenta de personal y su estado de
bloqueo. Se aplica sobre un esquema que hoy tiene cuatro migraciones y ningún entorno desplegado, así
que no existe todavía la restricción de «una migración aplicada no se edita»: esa regla entra en
vigor con el cambio 11. El código de jOOQ se genera desde las migraciones en cada `generate-sources`,
así que una tabla mal nombrada rompe la construcción antes de compilar nada.

**Privilegios.** Se conceden exactamente los de `docs/03` §6.1 y ni uno más, verificados fila por
fila en `RolePrivilegeMatrixIT` para los cinco roles:

| Rol | Sobre la tabla de `identity` |
|---|---|
| `confia_owner` | Propietario, por ser quien migra. Sin `BYPASSRLS` |
| `confia_admin_app` | `SELECT`, `INSERT`, y `UPDATE` por tratarse de una tabla no financiera. **Sin `DELETE`** |
| `confia_portal_app` | **Ninguno.** §6.1 dice literalmente «Sin acceso alguno a `user`, `role`, …» |
| `confia_readonly` | `SELECT`, con política de fila activa |
| `confia_backup` | Lectura, por `pg_read_all_data` |

**Auditoría.** Este cambio entrega el **primer escritor de producción** de `shared_audit_log`. El
disparador de encadenamiento del cambio 5B asigna `id`, `prev_hash` y `row_hash`, así que el escritor
no calcula ningún hash: inserta, y el motor encadena. Los eventos son los que este cambio produce de
la primera fila de `docs/03` §12.2: autenticación exitosa, autenticación fallida, ciclo de bloqueo.
Cada asiento viaja en la misma transacción que su efecto. La bitácora deja de estar vacía en
producción, lo que significa que las pruebas de cadena ya existentes empiezan a cubrir escrituras
reales y no solo insertadas a mano por la prueba.

## Pronóstico de cortes y tamaño

**No se reutiliza el pronóstico de la propuesta anterior por decomposición**, porque aquella mezclaba
en un mismo bloque el sobre de llaves con Argon2id y el caso de uso, y desagregar esa mezcla a
posteriori sería aritmética inventada sobre un número ya inventado. Este es un pronóstico nuevo sobre
el alcance recortado.

**Inventario de tareas.** Es el inventario original de la propuesta anterior, menos exactamente los
tres bloques que se trasladaron a `mfa-totp-and-password-recovery` —sobre de llaves y ADR, TOTP con
inscripción y códigos, recuperación de contraseña—, lo que deja rastreable de dónde sale cada número:

| Bloque de trabajo | Tareas |
|---|---|
| Módulo `identity`, `package-info`, cierre del escenario diferido W2 | 1 a 2 |
| Migración `V5`, política de fila, privilegios y extensión de las puertas de esquema | 2 a 3 |
| Argon2id tras puerto, caso de uso de autenticación con resultado tipado, bloqueo con retroceso, hash señuelo | 3 a 4 |
| Puerto y adaptador jOOQ de escritura de auditoría, con sus eventos | 1 a 2 |
| Notas fechadas de `docs/03` y `docs/09`, y exclusiones con destino nombrado | 1 a 2 |
| **Total** | **8 a 13** |

**Queda por debajo del límite de quince** de `openspec/changes/README.md`, con margen incluso en el
extremo alto. No se necesita el precedente de partir de nuevo que el propietario concedió para el
corte anterior.

**Estimación de líneas**, calculada de forma independiente para este alcance y no por proporción del
número anterior, con el historial de subestimación de 1,5× a 3× de este repositorio declarado y **no**
descontado:

| Corte | Contenido | Estimación |
|---|---|---|
| **C1 — módulo y esquema** | Módulo `identity`, escenario W2, migración `V5`, política de fila, `REVOKE`/`GRANT`, filas de `RolePrivilegeMatrixIT`, `MultiTenantSchemaIT` sin exclusión | 450 a 750 |
| **C2 — contraseña y resultado tipado** | Argon2id tras puerto, pimienta, tipo sellado de dos desenlaces, caso de uso de autenticación, hash señuelo, pruebas unitarias de dominio | 500 a 800 |
| **C3 — bloqueo, auditoría y documentación** | Máquina de estados del bloqueo, persistencia, escritor de auditoría con su adaptador jOOQ, atomicidad y concurrencia, notas editoriales, exclusiones con destino nombrado | 700 a 1 200 |
| **Total de código** | | **1 650 a 2 750** |
| Artefactos de OpenSpec | | 600 a 900 adicionales |

**Pronóstico de entrega: tres pull requests encadenados**, con el mismo presupuesto de **800 líneas
de cambio efectivo** (`docs/15-flujo-de-trabajo-git.md` §3, `CLAUDE.md`) que rige el resto del
repositorio. Este pronóstico no reabre ese número, y no hay ninguna cifra de 400 líneas en conflicto
con él: esa cifra pertenece a una heurística genérica de la herramienta de orquestación de esta
sesión, no a una decisión del propietario ni a una política de este repositorio, cuyos pull requests
anteriores de esta misma secuencia de cambios cerraron en verde con cifras del orden de 550 a 800
líneas. **C3 roza el límite en su extremo alto**; si el diff real lo supera, se parte en C3 y C4
siguiendo el mismo patrón que el cambio 5 pagó por aprender cuando su corte A3 midió 813 líneas.

**Nota sobre la estrategia de entrega de esta sesión.** El pronóstico de arriba —1 650 a 2 750 líneas
de código en tres cortes— no cabe en un solo pull request. La estrategia `single-pr` cacheada para
esta sesión no sirve para este cambio, igual que no sirvió para `idempotency-key-infrastructure`, que
resolvió lo mismo con `auto-chain`. El orquestador debe actualizar la estrategia de entrega a
`auto-chain` antes de la fase de tareas, o el propietario debe aceptar explícitamente una excepción de
tamaño para un pull request único, que este pronóstico no recomienda.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| **Un secreto —contraseña, hash Argon2id, pimienta— termina en un log, en un mensaje de excepción o en la salida de una prueba fallida.** Es la violación más fácil de cometer en este módulo y la más cara | **Alta si no se diseña** | Restricción 1 de la sección de restricciones, con envoltorios de valor de `toString()` redactado y verificación explícita, no confianza |
| **`mfa-totp-and-password-recovery` necesita modificar el tipo sellado que este cambio deja escrito**, en vez de solo extenderlo | **Alta, aceptada y declarada** | Sección «La costura con los cambios siguientes»: el compilador fuerza exhaustividad en cada `switch`, así que ninguna rama nueva queda sin actualizar en ningún archivo |
| **La institución previa a la autenticación acaba viniendo del cliente** en `session-tokens-and-web-layer`, violando ADR-0009 en el único endpoint que no tiene token | **Alta si no se fija ahora** | **D4**: el contrato se escribe como requisito con escenario en este cambio, antes de que exista el endpoint |
| La prueba de uniformidad de tiempo resulta escamosa y se termina desactivando | **Alta si se convierte en puerta** | Se entrega como medición reportada, no como puerta; el mecanismo se prueba por interacción determinista |
| Contención en la fila de la cuenta bajo fuerza bruta concentrada, por llevar el estado de bloqueo a PostgreSQL | Media | Costo aceptado y declarado; el propio retroceso acota la tasa. La dimensión de volumen alto —por IP— se difiere a Redis con dueño nombrado |
| El modelo de bloqueo se implementa según `docs/03` §4.4 y la verificación lo mide contra la especificación publicada, o al revés | **Alta si D2 no se resuelve antes de especificar** | **D2** bloquea la especificación |
| Una dependencia criptográfica nueva entra sin la revisión que `docs/03` §2.6 exige | Media | **P1** la trata como decisión con sonda previa, no como detalle |
| El escenario diferido W2 se declara cerrado sin demostrarlo de verdad, porque los dos módulos existen pero nada lo verifica en verde | Media | El delta de `build-integrity` incorpora el escenario con su verificación, no una nota que diga que ya se puede |
| El pronóstico (1 650 a 2 750 líneas en tres cortes) exige entrega encadenada, y la estrategia de sesión cacheada es `single-pr` | **Alta, ya visible en el pronóstico** | Nota de estrategia de entrega, arriba: se recomienda `auto-chain` antes de tareas |
| Una migración aplicada se edita después | Baja hoy | No hay entorno desplegado: la base vive solo en contenedores efímeros. La regla entra en vigor con el cambio 11 |

## Plan de reversión

Sigue siendo barata por la misma razón que en los cambios 4, 5 y 6: **no existe ningún entorno
desplegado ni ningún dato real**. La base de datos vive exclusivamente en contenedores de prueba
efímeros y ningún proceso de producción arranca todavía con una fuente de datos.

1. **Revertir el commit de fusión** del corte afectado, o cerrar su pull request. Con cortes
   encadenados, revertir uno obliga a revertir los posteriores que dependan de él.
2. **C1 se puede revertir sola; C2 y C3 no se revierten sin C1.** Si desaparece la migración `V5`, el
   código generado de jOOQ deja de contener la tabla de identidad y los adaptadores no compilan. Es un
   rojo inmediato y ruidoso, no un fallo en ejecución.
3. **Las notas editoriales de `docs/03` y `docs/09` se revierten solas.** Son documentación y no
   arrastran nada.
4. No hay dato que migrar hacia atrás ni migración de reverso que escribir: el siguiente
   `./mvnw verify` levanta un contenedor nuevo y aplica lo que quede en `db/migration`.
5. Una puerta que bloquee por error **no se desactiva con una bandera** (ADR-0008): se revierte el
   commit que la introdujo o se corrige con un ADR.

**Punto de no retorno práctico:** el cambio 11, cuando exista el primer entorno desplegado. Desde
entonces, una migración aplicada no se edita.

## Dependencias

- **Cambio 5A**, archivado: Flyway, generación de código de jOOQ desde las migraciones,
  Testcontainers, roles de PostgreSQL por script de inicialización solo de prueba,
  `organization_institution` y su adaptador jOOQ.
- **Cambio 5B**, archivado: `TransactionRunner`, `SecurityContext`, `IsolationLevel`,
  `CommittingPostgresIntegrationTest`, `shared_audit_log` con su disparador de encadenamiento,
  `AuditLogReader` y `JooqAuditLogReader` como precedente de puerto y adaptador, `RolePrivilegeMatrixIT`,
  `MultiTenantSchemaIT`, y la regla R3.
- **Cambio 6**, archivado: `IdempotentExecutor` como precedente de caso de uso que recibe el
  `SecurityContext` de su llamador, y `IdempotentExecutorConcurrencyIT` como precedente de prueba de
  concurrencia determinista.
- **Cambio 4**, archivado: `InstitutionId` del `kernel`, `CurrentInstitutionProvider` como puerto, y
  el precedente de códigos de error estables de ADR-0019 (`OrganizationErrorCodesTest`).
- JDK 25 y **Docker** en local y en integración continua, ya exigidos por la construcción desde el
  cambio 1.
- **Dependen de este cambio:** `mfa-totp-and-password-recovery`, que no puede cifrar un secreto ni
  inscribir un segundo factor sin la cuenta de personal que este cambio crea; y, a través de él,
  `session-tokens-and-web-layer` y el cambio 8 (matriz de permisos), que depende de la secuencia
  completa y asigna roles a usuarios que hoy no existen.

## Criterios de salida de F0 que este cambio acerca o cierra

Ninguno se declara cerrado sin que una prueba lo sostenga.

- **Criterio 1 — «Un intento de importar el `domain` de otro módulo rompe la construcción».** Hoy la
  mitad negativa ya está en verde con un fixture, pero la mitad positiva —«Cada módulo usa solo su
  propio dominio»— está **diferida por escrito** a este cambio, porque exige dos módulos de negocio
  reales. Este cambio **la cierra**, y con ella el criterio queda completo con módulos de producción
  y no solo con fixtures.
- **Criterio 2 — «Un usuario sin permiso recibe 403 y el intento queda en `AuditLog`».** Este cambio
  **no lo cierra**, y ni siquiera lo toca en su mitad de `403`, que exige capa web y RBAC. Sí entrega
  **la mitad de la bitácora**: el escritor de auditoría con el que ese intento denegado quedará
  registrado cuando `session-tokens-and-web-layer` y el cambio 8 lo produzcan. Dueño del criterio:
  **cambio 8**.
- **Criterio 4 — idempotencia.** Ya cerrado por el cambio 6. Este cambio **no lo toca**; la mitad de
  cabecera HTTP obligatoria del entregable 6 sigue diferida a **`session-tokens-and-web-layer`**, y
  esta propuesta no la declara cerrada.
- **Entregable 3 de F0 — «Autenticación del personal con MFA, roles, permisos, sesiones y bloqueo por
  fuerza bruta».** Este cambio entrega **autenticación con contraseña y bloqueo**. Deja explícitamente
  pendientes **MFA y cifrado** (`mfa-totp-and-password-recovery`), **sesiones**
  (`session-tokens-and-web-layer`) y **roles y permisos** (cambio 8).
  `docs/09-roadmap-y-fases.md` se actualiza para que las tres mitades diferidas queden nombradas y no
  caduquen en silencio, con el precedente exacto de cómo el cambio 6 dejó escrita su mitad de
  cabecera HTTP.

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` termina en verde con JDK 25 y Docker, en local y en integración
      continua, incluida la puerta de mutación de `main`.
- [ ] Existe el módulo `identity` como segundo módulo de negocio, y `SpringModulithVerificationTest`
      y `NoCrossModuleDomainImportsTest` pasan con dos módulos de producción reales.
- [ ] El escenario «Cada módulo usa solo su propio dominio» está **demostrado en verde** y su nota de
      diferimiento retirada de `openspec/specs/build-integrity/spec.md`.
- [ ] La tabla nueva tiene `institution_id NOT NULL`, toda restricción única lo incluye, y la
      seguridad a nivel de fila está **habilitada y forzada**, verificado por las puertas genéricas
      de `MultiTenantSchemaIT` **sin lista de exclusión**.
- [ ] `RolePrivilegeMatrixIT` confirma los privilegios de `docs/03` §6.1 sobre la tabla nueva para los
      cinco roles, sin uno más ni uno menos, e incluye explícitamente que `confia_portal_app` **no
      tiene ninguno** sobre la cuenta de personal.
- [ ] Una prueba de contexto ausente demuestra que, sin `app.institution_id`, una consulta sobre la
      tabla de identidad devuelve **cero filas**, no un error de permiso.
- [ ] Cinco intentos fallidos consecutivos producen el bloqueo, y el intento siguiente con la
      contraseña correcta se rechaza por bloqueo vigente, con la progresión que resuelva **D2**.
- [ ] Cada ciclo de bloqueo queda en `shared_audit_log` **con su duración**, como pide el escenario
      publicado.
- [ ] Una prueba de integración demuestra que el efecto y su asiento de auditoría se escriben **dentro
      de la misma transacción**: si el efecto falla, el asiento no sobrevive.
- [ ] La autenticación de una cuenta inexistente ejecuta la verificación señuelo, verificado por
      interacción y no por cronómetro; y la diferencia de medianas de tiempo se **mide y se reporta**.
- [ ] Ningún secreto aparece en un mensaje de registro, en un mensaje de excepción, en un
      `toString()` ni en la salida de una prueba fallida, verificado y no confiado.
- [ ] Las exclusiones con destino nombrado —dimensión por IP, listas de contraseñas comprometidas,
      calibración de Argon2id, extremo a extremo con Playwright, cifrado y MFA— viven **dentro de
      bloques `### Requisito:`**, nombran su cambio dueño, y cada una tiene al menos un escenario
      **cierto hoy y falso el día que la brecha se cierre**.
- [ ] La cobertura de `com.confia.identity.domain` alcanza el 95 % de líneas y ramas con JaCoCo, y el
      umbral de mutación de 80 con PIT.
- [ ] `docs/09-roadmap-y-fases.md` refleja el entregable 3 con sus tres mitades diferidas nombradas
      —MFA y cifrado a `mfa-totp-and-password-recovery`, sesiones a `session-tokens-and-web-layer`,
      roles y permisos al cambio 8—, y `docs/03-seguridad.md` lleva sus notas editoriales fechadas
      sobre §4.4 y §6.1, con el cuerpo de cada sección **sin reescribir**.
- [ ] El tiempo de la suite `*IT.java` se **mide y se reporta** al cerrar cada corte, no se estima.

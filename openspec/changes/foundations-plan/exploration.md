# Exploración: división de F0 "Fundaciones" en cambios SDD ejecutables

- **Cambio:** `foundations-plan`
- **Fase:** explorar
- **Fecha:** 2026-09-15
- **Estado:** exploración terminada, pendiente de propuesta y de aprobación del propietario

## Estado actual

`docs/09-roadmap-y-fases.md` §3 define F0 con 12 entregables y 8 criterios de salida (unas seis
semanas con margen). El límite de quince tareas por cambio (`openspec/changes/README.md`,
`docs/13-metodologia-sdd.md` §8) y el presupuesto de 800 líneas con estrategia de un solo pull
request hacen inviable un único cambio: F0 toca `identity`, `shared/audit`, `shared/security`,
`shared/observability`, `kernel`, `packages/contracts`, `packages/ui`, `packages/config` e `infra/`
a la vez.

El stack vigente es Java 25 con Spring Boot 4.1 y Maven (ADR-0013, que reemplaza a ADR-0001).

## Áreas afectadas

`docs/09-roadmap-y-fases.md` §3, `openspec/changes/README.md`, `docs/13-metodologia-sdd.md` §8,
`docs/01-arquitectura.md` §3, §4 y §9, los ADR 0002, 0003, 0004, 0008, 0009, 0010, 0012, 0013,
0015, 0016 y 0017, `docs/02-modelo-de-dominio.md` §2.8 y §3.5,
`.claude/skills/confia-module-scaffold/SKILL.md`, `docs/03-seguridad.md` §6,
`docs/05-infraestructura-y-despliegue.md` y `docs/10-analisis-de-brechas.md`.

## 1. División propuesta de F0 en trece cambios

| # | Identificador del cambio | Cubre de F0 | Criterio de salida | Depende de | Tamaño aproximado |
|---|---|---|---|---|---|
| 1 | `maven-workspace-and-ci-skeleton` | Entregable 1 parcial: Maven de varios módulos, ArchUnit, Spring Modulith, enforcer, integración continua | Mecanismo del criterio 1 | — (primero) | ~10 tareas |
| 2 | `kernel-money-value-object` | Entregable 2: `Money`, 95 % de cobertura, mutación 80 | — | 1 | 10 a 12 tareas |
| 3 | `frontend-monorepo-and-contracts-pipeline` | Entregable 1, parte de pnpm y Turborepo, más generación con orval | Criterio 7 | 1 (paralelo a 2) | ~10 tareas |
| 4 | `institution-root-and-multitenancy-baseline` | Alcance nuevo, ver hallazgo | — | 1 (paralelo a 2 y 3) | 6 a 8 tareas |
| 5 | `transactional-runner-and-audit-log` | Entregable 5 (brecha B6) | Criterio 3 | 1, 2, 4 | 13 a 14 tareas, riesgo de exceder |
| 6 | `idempotency-key-infrastructure` | Entregable 6 (brecha B7) | Criterio 4 | 5 | 8 a 9 tareas |
| 7 | `staff-authentication-mfa-sessions` | Entregable 3 | — | 5, 4, 2 | 12 a 13 tareas |
| 8 | `rbac-permission-matrix-and-audit-integration` | Entregable 4 (brecha A7) y semilla mínima | Criterio 2 | 7 | ~10 tareas |
| 9 | `background-jobs-with-db-scheduler` | Entregable 12 | Criterio 5 | 5, 6 | 12 a 13 tareas |
| 10 | `structured-logging-and-tracing` | Entregable 7 | — | 1 (paralelo a 5 a 9) | 7 a 8 tareas |
| 11 | `containerization-and-cicd-pipeline` | Entregable 8 | — | 1, 3 | 12 a 14 tareas, riesgo de exceder |
| 12 | `backup-restore-and-drill` | Entregable 9 (brecha B10) | Criterio 6 | 11 | 9 a 10 tareas |
| 13 | `design-system-foundations-and-a11y` | Entregable 10 | Criterio 8 | 3 | 9 a 10 tareas |

Paralelismo posible: los cambios 2, 3 y 4 después del 1; los cambios 10 y 13 en paralelo a la
cadena 5 → 6 → 7 → 8 → 9; los cambios 11 y 12 al final.

Si al llegar a la fase de tareas los cambios 5 u 11 superan el presupuesto, se dividen así: el 5 en
`jooq-flyway-testcontainers-wiring` más `audit-log-and-transaction-runner`; el 11 en
`dockerfiles-and-local-compose` más `cicd-pipeline-and-preprod-deploy`.

**Nota (2026-09-19, decisión D1 de `institution-root-and-multitenancy-baseline`).** El cambio 4
entrega el módulo `organization` sin tabla. La migración de `organization_institution` (seguridad a
nivel de fila sobre su propia clave primaria, ADR-0009 y ADR-0017; prefijo según ADR-0015) y su
repositorio jOOQ pasan al cambio 5, y esa migración es la primera del cambio 5. Si el cambio 5 se
divide, la división asigna ambas cosas de forma explícita a una de sus partes.

**Nota (2026-09-20, división del cambio 5).** El propietario confirmó la división prevista en el
párrafo anterior. El cambio 5 se ejecuta como dos cambios SDD secuenciales: la **parte A**,
`jooq-flyway-testcontainers-wiring` (Flyway, generación de código de jOOQ, Testcontainers, roles de
PostgreSQL por script de inicialización solo de prueba, migración de `organization_institution` y su
adaptador jOOQ), primero; y la **parte B**, `audit-log-and-transaction-runner` (componente
transaccional único y bitácora de auditoría encadenada por hash), después, porque depende de la
parte A. La migración de `organization_institution` y su repositorio jOOQ, asignados al cambio 5 por
la nota anterior, quedan asignados de forma explícita a la **parte A**.

**Nota (2026-09-24, división del cambio 7).** El propietario aprobó dividir el cambio 7 en dos
cambios SDD secuenciales. La exploración compartida vive en
`openspec/changes/staff-identity-password-and-mfa/exploration.md` y documenta la evidencia que
motiva el corte: el cambio 7 trae a la vez el primer `DataSource` de producción, la primera capa
`web` del sistema con sus reglas de ArchUnit, MFA con cifrado de columna sin mecanismo disponible
(la ruta de `pgcrypto` está cerrada por privilegios), JWT con EdDSA y JWKS, rotación de refresco con
concurrencia real, el adaptador de ADR-0009, el cableado HTTP de la idempotencia, CSRF, CSP,
Problem Details,
springdoc y la primera dependencia de Redis. La estimación de 12 a 13 tareas de la tabla anterior es
previa a casi toda esa evidencia.

- **Parte A**, `staff-identity-password-and-mfa`: módulo `identity`, esquema, migración y
  privilegios, autenticación con contraseña, MFA con su mecanismo de cifrado, bloqueo por intentos
  fallidos con retroceso, recuperación de contraseña y prevención de enumeración. **Sin capa web**,
  demostrado con pruebas de integración contra el caso de uso.
- **Parte B**, `session-tokens-and-web-layer`: JWT y JWKS, rotación de token de refresco con
  detección de reutilización, adaptador real de `CurrentInstitutionProvider` con su prueba de
  ADR-0009, registro de la fuente de datos de producción, el primer controlador y la capa web
  completa, la mecánica genérica de RBAC con su guarda de arranque, y el cableado HTTP de la
  idempotencia que el cambio 6 difirió.

Hay identidad sin capa web, pero no hay sesión sin identidad: el orden es A y después B. De las cinco
decisiones que la exploración dejó abiertas, solo el mecanismo de cifrado del secreto MFA pertenece a
la parte A; el dueño de la guarda de arranque de RBAC, la exigencia de `Idempotency-Key` en endpoints
que no mueven dinero y el alcance del registro de la fuente de datos son de la parte B.

**Nota (2026-09-24, segundo corte: la parte A se parte a su vez).** La propuesta de la parte A
pronosticó de **14 a 16 tareas** contra el límite de quince de `openspec/changes/README.md`, y de
2 400 a 4 000 líneas, con el historial de subestimación de este repositorio sin descontar. La regla de
tamaño pide partir **antes de continuar a diseño**, así que el propietario aprobó el segundo corte en
vez de esperar a la fase de tareas. El cambio 7 se ejecuta como **tres** cambios SDD secuenciales:

1. `identity-module-and-password-authentication` — módulo `identity`, esquema, migración con
   seguridad de fila y privilegios, autenticación con contraseña con Argon2id, bloqueo por intentos
   fallidos con retroceso, asientos de auditoría de identidad y prevención de enumeración.
2. `mfa-totp-and-password-recovery` — cifrado de columna con sobre de llaves y su ADR, TOTP,
   inscripción del segundo factor, códigos de recuperación, y recuperación de contraseña con token de
   un solo uso. **Partido a su vez el 2026-09-27; ver la nota del tercer corte, abajo.**
3. `session-tokens-and-web-layer` — sin cambios respecto a la parte B descrita arriba, **más la
   condición de aceptación del 2026-09-25 que se describe abajo**.

**Condición dura de aceptación de `session-tokens-and-web-layer` (2026-09-25, del informe de
seguridad previo a la fusión del corte C1 de `identity-module-and-password-authentication`).**

La tabla `identity_login_backoff`, ya fusionada en `main`, se indexa por una huella con llave del
identificador **presentado** y **no** por la cuenta, y acepta filas para identificadores que no
corresponden a ninguna cuenta. Eso es deliberado y correcto: es lo que hace que el camino del
retroceso sea idéntico para una cuenta que existe y para un correo inventado, y por tanto lo que
convierte la uniformidad que exige `docs/03-seguridad.md` §4.6 en una propiedad estructural en vez de
una promesa.

El precio es que **un ataque distribuido contra identificadores aleatorios hace crecer esa tabla sin
cota**. Hoy no es explotable, porque no existe ningún endpoint que escriba en ella. Deja de no serlo
exactamente cuando `session-tokens-and-web-layer` entregue el primer endpoint de inicio de sesión.

Por eso:

> **`session-tokens-and-web-layer` NO DEBE fusionar un endpoint de inicio de sesión que escriba en
> `identity_login_backoff` sin que el control por dirección IP de `docs/03-seguridad.md` §4.4 —o un
> tope funcional equivalente— exista, esté probado y esté operativo.**

Se escribe aquí, como condición de aceptación de ese cambio, y no como «pregunta abierta» del diseño
del cambio anterior, por un motivo concreto: **una pregunta abierta no bloquea nada**. La mitigación
que el diseño citaba —«la cota real es el control por IP, que ya tiene dueño»— es una secuencia de
trabajo razonable, pero no es una mitigación cerrada mientras ese control no exista en ningún commit
fusionado.

Queda además pendiente, con dueño y fase por asignar **antes de F1**: la purga de
`identity_login_backoff`. Sus filas no caducan físicamente —el contador expira a los 30 minutos, pero
la fila permanece—, y el corte C1 no entregó índice ni trabajo de purga, siguiendo el precedente del
cambio 6 de no enviar un índice sin consumidor. El dueño propuesto es el cambio que introduzca
trabajo de mantenimiento programado con `db-scheduler` (ADR-0016).

**Dos condiciones duras más de aceptación de `session-tokens-and-web-layer` (2026-10-03, de
`password-recovery-token`).** El cambio `password-recovery-token` entrega el restablecimiento de
contraseña como casos de uso, sin endpoint, sin sesiones y sin límite por dirección IP. Eso deja dos
brechas que deben cerrarse antes de que exista un camino HTTP hacia ellas. Como la condición de
arriba, se escriben aquí y no como preguntas abiertas, porque una pregunta abierta no bloquea nada:

> **1. `session-tokens-and-web-layer` NO DEBE fusionar un endpoint de restablecimiento sin revocar
> todas las familias de tokens de refresco de la cuenta al completarse el restablecimiento**
> (`docs/03-seguridad.md` §4.5 y §4.7, ADR-0005 punto 4). Hoy `ResetPasswordWithToken` solo cambia la
> contraseña, y `IdentityScopeExclusionInventoryTest` comprueba que ninguna clase de
> `com.confia.identity` nombra sesiones ni tokens de refresco. Esa prueba se vuelve falsa el día que
> llegue la primera emisión de refresco, y la prueba de que el restablecimiento las revoca es de
> `session-tokens-and-web-layer`.
>
> **2. `session-tokens-and-web-layer` NO DEBE fusionar el endpoint de solicitud de restablecimiento
> sin el límite de 10 solicitudes por hora por dirección IP** (`docs/03-seguridad.md` §4.7 y §10).
> `RequestPasswordReset` aplica por sí mismo la respuesta uniforme, pero el límite de tres emisiones
> por hora es por cuenta y no frena a quien recorre cuentas ajenas.

**Condición dura más de aceptación de `session-tokens-and-web-layer` (2026-10-04, de
`web-edge-foundations`, revisión de seguridad del PR 4).** La cadena de `web-edge-foundations`
desactiva CSRF porque hoy ninguna credencial viaja en una cookie (`SecurityChains`, decisión 5 de su
diseño). `docs/03-seguridad.md` §8.3 exige CSRF de doble envío con prueba para toda credencial por
cookie, y nada obliga hoy a reactivarlo:

> **3. `session-tokens-and-web-layer` NO DEBE fusionar la primera credencial en cookie (la de refresco
> o la de sesión) sin activar la protección CSRF de doble envío con verificación de `Origin` y sin una
> prueba que demuestre que una petición mutadora con la cookie y sin `X-CSRF-Token` recibe `403`.**

**Condición dura de aceptación de `background-jobs-with-db-scheduler` (cambio 9) (2026-10-04, de
`web-edge-foundations`, revisión de seguridad del PR 6b).** La protección de registros de la regla 11
(`SensitiveLogGuard` y `Settings.withExecuteLogging(false)` de jOOQ) existe hoy solo donde hay `DataSource`
y borde web: el proceso administrativo. El trabajador no tiene `DataSource` y su política de procesos
prohíbe `com.confia.shared.web`, de modo que no recibe la guardia. Se escribe aquí y no como pregunta abierta,
porque una pregunta abierta no bloquea nada:

> **4. `background-jobs-with-db-scheduler` NO DEBE dar un `DataSource` al trabajador ni ejecutar SQL en él sin que
> la protección de registros sensibles esté activa en ese proceso, y NO DEBE fusionarse sin una prueba que lo
> demuestre** (con `org.jooq` y la raíz en `DEBUG`, un valor enlazado distintivo no aparece en ningún evento del
> proceso trabajador real).

Como el trabajador prohíbe `com.confia.shared.web`, la protección debe llegar por una configuración que el
trabajador sí pueda importar (por ejemplo una de `shared.platform` o `shared.logging`, con su línea en
`ProcessBeanPolicy`). El ajuste de jOOQ ya viaja con el `DSLContext` compartido; la guardia de Logback no.

**Nota (2026-09-27, tercer corte: la parte 2 se parte a su vez).** La propuesta de
`mfa-totp-and-password-recovery` pronosticó **12 a 17 tareas solo para su primera mitad**, contra el
límite de quince de `openspec/changes/README.md`, y más aún si se aprueba añadir los dos controles que
`docs/03-seguridad.md` documenta sin requisito publicado. El propietario aprobó el tercer corte. El
cambio 7 se ejecuta ahora como **cuatro** cambios SDD secuenciales:

1. `identity-module-and-password-authentication` — **archivado el 2026-09-27.**
2. `column-encryption-and-mfa-totp` — sobre de llaves con su ADR-0023, cifrado a nivel de columna,
   TOTP con inscripción y verificación, códigos de recuperación, la columna que decide si una cuenta
   exige segundo factor, y la corrección del `switch` no exhaustivo que la parte 1 dejó pendiente.
3. `password-recovery-token` — recuperación de contraseña con token de un solo uso y de corta vida,
   con su concurrencia propia y la respuesta uniforme que exige la prohibición de enumeración.
4. `session-tokens-and-web-layer` — sin cambios respecto a lo descrito arriba.

**El corte entre 2 y 3 está en que la recuperación de contraseña no depende de nada de lo anterior:**
no cifra ninguna columna, no usa TOTP, y su token se almacena hasheado, no cifrado. Es una tabla y un
flujo propios. En cambio el sobre de llaves **sí** debe entregarse junto con su único consumidor real
de esta secuencia, el secreto TOTP: entregarlo aislado sería infraestructura sin uso, patrón que este
repositorio ya rechazó al negarse a enviar un índice sin consumidor en el cambio 6.

**Obligación que `column-encryption-and-mfa-totp` impone al cambio 8** (2026-09-27, de la decisión del
propietario sobre cómo se sabe que una cuenta exige MFA). Ese cambio añade una columna mínima a
`identity_staff_account` porque **no existe ningún concepto de rol ni de permiso en el árbol**, y el
requisito publicado de MFA presupone uno. El argumento fue de secuencia: el cambio 4 de esta lista
emite tokens y llega **antes** del cambio 8, así que diferir la obligatoriedad dejaría una ventana en
la que el sistema entrega sesiones completas a usuarios con permisos financieros sin exigir segundo
factor. El costo es que esa columna **duplica** algo que el cambio 8 poseerá, y un rol que gane un
permiso financiero sin que la columna se actualice deja el control silenciosamente desactivado. Por
eso:

> **El cambio 8 (`rbac-permission-matrix-and-audit-integration`) DEBE derivar
> `identity_staff_account.mfa_required` del permiso del rol y eliminar la doble fuente**, como parte de
> su propio alcance y no como mejora posterior.

El corte entre 1 y 2 está donde **entra el cifrado de columna**: el primero no cifra nada, solo
hashea, y por eso no necesita el sobre de llaves, la tabla `shared_data_key` ni el ADR nuevo. Esas
tres cosas, y el delta de redacción sobre el requisito publicado de MFA, pasan íntegras al cambio 2.
La contradicción entre los dos modelos de bloqueo y la del escenario del sexto intento se quedan en
el cambio 1, que es su dueño. La carpeta `staff-identity-password-and-mfa` pasó a llamarse
`identity-module-and-password-authentication`; no hubo nunca una carpeta con el nombre de la parte A
publicada.

**Corrección (2026-09-30, exploración de `password-recovery-token`).** La frase de arriba «no usa
TOTP» es falsa: `docs/03-seguridad.md` §4.7 exige un código TOTP o un código de recuperación para
restablecer la contraseña de una cuenta con MFA. El corte sigue siendo válido porque la parte 2 ya
está archivada, pero la parte 3 **depende** de ella.

**Nota (2026-09-30, dos cambios nuevos en F0, aprobados por el propietario).** La lista pasa de trece
a quince cambios:

| # | Identificador del cambio | Cubre | Depende de | Origen |
|---|---|---|---|---|
| 14 | `transactional-email-adapter` | Puerto de envío de correo y su adaptador por SMTP (SES, ADR-0014). Entrega el enlace de recuperación de contraseña y el aviso de códigos de recuperación de MFA bajos, que hoy no tienen dueño | 9 (el envío corre en el trabajador) | Respuestas del propietario a `password-recovery-token` |
| 15 | `process-entry-point-isolation` | Que cada proceso cargue solo lo suyo. `AdminApplication`, `PortalApplication` y `WorkerApplication` comparten el paquete `com.confia.bootstrap` y cada uno lo escanea, así que el contexto de un proceso carga también la configuración de los otros dos. Hoy no tiene efecto, porque ninguno registra módulos de negocio, pero el día que `AdminApplication` escanee un módulo administrativo, el portal lo cargaría también, contra ADR-0003. Debe incluir una prueba que falle si el contexto del portal contiene un bean o un paquete administrativo | 1. **Debe fusionarse antes del primer punto de entrada que registre un módulo de negocio**, es decir, antes de `session-tokens-and-web-layer` | Hallazgo de `frontend-monorepo-and-contracts-pipeline`, corte 3a (`apply-progress.md`, tarea 1.3) |

**Hallazgo.** `docs/09-roadmap-y-fases.md` §3 no incluye el módulo `organization` en F0, pero
ADR-0009 exige `institution_id NOT NULL` desde la primera migración y ADR-0017 asigna la tabla raíz
de institución a ese módulo. Se recomienda incorporar el cambio 4, con la entidad raíz mínima, al
alcance de F0, con aprobación explícita del propietario del producto.

## 2. Dónde se resuelve cada punto de diseño abierto

| Punto | Cambio | Recomendación | Alternativa descartada |
|---|---|---|---|
| Prefijo de tabla (ADR-0015, regla 3) | 5 | Prefijo estricto `<módulo>_<entidad>` siempre, contando `shared` como módulo válido: `shared_audit_log`, no `audit_log` | Ampliar el catálogo cerrado de ADR-0017, que contradice su propósito |
| Dueño de la cuenta del estudiante | Se decide ahora, se implementa en F3 | `ledger` es el dueño (`docs/02` §2.8 y §3.5); `payments` la bloquea mediante un caso de uso público de `ledger` | Que `payments` acceda directamente a la tabla |
| ¿`application` llama al componente transaccional? | 5 | Sí, tratándolo como API pública de `shared/security`, con una excepción documentada a la regla `application-no-infrastructure` y comentario del ADR que la autoriza | Un puerto por módulo que solo agrega intermediación |
| Componente transaccional dentro de pruebas | 5 | Dos clases base: una con reversión automática y otra con confirmación real y truncamiento selectivo, necesaria para aislamiento serializable, bloqueo, concurrencia y reintento | Una sola clase base, que invalidaría las pruebas de concurrencia |
| Paquete base y nombres de módulos Maven | 1 | `com.confia`; módulos `kernel` y `app`; artefacto `confia-api` | `hn.edu.confia`, más verboso sin beneficio |
| Nombre de la tabla raíz de institución (ADR-0017) | 4 | `organization_institution`, con seguridad a nivel de fila sobre su propia clave primaria | — |

## 3. Alcance del primer cambio: `maven-workspace-and-ci-skeleton`

**Requisito previo humano.** Crear el repositorio Git, su remoto y la rama del cambio, conforme a la
convención de una rama por cambio de `CLAUDE.md`. Queda como primera tarea del cambio, a cargo del
propietario.

**Dentro de alcance:** POM padre con Java 25 y la lista de materiales de Spring Boot 4.1;
`maven-enforcer-plugin` con las dependencias prohibidas de ADR-0015 y ADR-0016; Maven Wrapper
comprometido al repositorio; módulo `kernel` vacío como frontera de compilación; módulo de
aplicación con los cuatro puntos de entrada seleccionados por `APP_PROFILE`, sin módulos de negocio
todavía; suite de reglas de ArchUnit y la verificación de Spring Modulith; regla estructural que
rechaza paquetes llamados `interface` o con nombre de capa técnica; flujo de integración continua
con `./mvnw verify`; decisión del paquete base y de los nombres de módulo; una prueba trivial en
verde.

**Fuera de alcance:** pnpm, Turborepo y `packages/*` (cambio 3); Flyway, jOOQ y Testcontainers
(cambio 5); `Money` (cambio 2); springdoc y endpoints (cambio 3); Spring Security (cambio 7);
Dockerfiles y Compose (cambio 11); cualquier tabla (cambios 4 y 5).

**Criterios de salida cubiertos:** ninguno por completo. Sienta el mecanismo del criterio 1, que solo
se puede verificar del todo cuando existan al menos dos módulos de negocio.

**Riesgos:**

1. Java 25 y Spring Boot 4.1 son versiones recientes y la compatibilidad de enforcer, ArchUnit y
   Spring Modulith 2 no está verificada. Se mitiga con una prueba de humo antes de construir el
   resto.
2. Unas reglas de ArchUnit que nunca fallaron no prueban nada. Se mitiga introduciendo una violación
   temporal y comprobando que rompe la construcción.
3. La decisión de paquete base y nombres de módulo es de baja reversibilidad y necesita aprobación
   explícita.
4. La creación del remoto en GitHub excede la autoridad de los agentes y la ejecuta el propietario.

## Recomendación

Aprobar la secuencia de trece cambios, empezar por `maven-workspace-and-ci-skeleton` y usar la fase
de propuesta para que el propietario apruebe: incorporar la tabla raíz de institución a F0, las seis
decisiones de diseño de la sección 2, y la estrategia de un solo pull request por cambio.

## Riesgos generales

- El presupuesto de 800 líneas queda ajustado en los cambios 5 y 11; su subdivisión ya está prevista.
- Trece ciclos SDD completos exigen disciplina operativa sostenida de una sola persona.
- `strict_tdd` está en `false` en `openspec/config.yaml` y debe pasar a `true` en el cambio 2, con
  tarea explícita para actualizarlo junto con el umbral de cobertura.

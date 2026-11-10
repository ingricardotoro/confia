# Tareas: respaldo, restauración y simulacro (cambio 12 de F0)

- **Cambio:** `backup-restore-and-drill` (F0, cambio 12; entregable 9, brecha B10; cierra el criterio de salida 6)
- **Fase:** tareas
- **Fecha:** 2026-11-09
- **Estado:** aprobado por el propietario el 2026-11-09. Decisión del 2026-11-10: una sola carpeta de cambio con dos ramas de archivo (12a y 12b); las tareas 10 a 15 siguen bloqueadas hasta que lleguen sus dependencias.
- **Entradas aprobadas el 2026-11-09:** `proposal.md`, `design.md` (decisiones 1 a 15, sondas P-1 a P-14, secciones 6 a 10) y los
  deltas `specs/backup-and-restore` (BR01 a BR68, sin BR63), `specs/build-integrity` (BI140 a BI150) y `specs/audit-trail` (AT39 a AT42).
- **Modo de TDD:** estricto (`openspec/config.yaml`). Ejecutor: `./mvnw` en `apps/api` con JDK 25 y Docker en ejecución. Los scripts de
  shell se prueban por pruebas de integración con Testcontainers (rojo y verde reales) y por `shellcheck`; las reglas de alerta por
  `promtool test rules`. Unidades de `systemd`, runbook, acta, ADR y notas no tienen ejecutor de rojo semántico: llevan la excepción
  declarada y pruebas estructurales.
- **Línea base de la cadena:** `main` actualizado al iniciar cada PR.

## Decisión de corte (propietario, 2026-11-09)

1. **BR63 sale de este cambio.** La rotación de la KEK (reenvoltura de las llaves de datos bajo una KEK nueva) pasa al cambio futuro que
   cablee la KEK de cifrado de columnas en producción. Aquí permanecen la rotación de la llave age (BR62) y la regla de procedimiento
   (BR64). La numeración de la especificación no cambia.
2. **El cambio se parte en dos** (la cuenta de 15 del diseño queda en el límite, y las tareas 10 a 15 dependen de terceros):

| Mitad | Identificador | Tareas | Depende de | Cierra |
|---|---|---|---|---|
| **12a** | `backup-tooling` | **T01 a T09** (9) | Solo 11a `dockerfiles-and-local-compose` (el PR de la tarea T04 después del PR A de 11a) | Todo lo verificable en local y en CI; runbook, acta, ADR y notas |
| **12b** | `backup-operations-and-drill` | **T10 a T15** (6) | 12a archivado o fusionado; **11c** (imagen de herramientas publicada por dígest), **11d** (EC2, RDS con roles, secretos), **cambio 10** (Prometheus y Alertmanager), cuenta de **B2** y **personas** | Criterio de salida 6 y hito H1 |

Cada mitad respeta el límite de 15 tareas de `openspec/changes/README.md`. Los identificadores T01 a T15 son estables y siguen la numeración
del diseño (§10); no se renumera al partir.

> **12b no puede empezar hasta que aterricen sus bloqueadores** (11c, 11d, cambio 10, cuenta de B2 con bloqueo de objeto y las personas que
> custodian llaves y firman). Las tareas T10 y T11 podrían ejecutarse en local con solo 12a; empezarlas antes es una decisión del
> propietario, no se asume.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Tareas | **15** en total: **12a = 9**, **12b = 6** |
| Estimated changed lines (additions + deletions, sin `openspec/` ni código generado) | 12a **unas 3 500** (A 350, B1 350, B2 650, C 550, D 550, E1 700 de prosa, E2 350); 12b **unas 1 000** (F 350, G 300, H 200, I 150) |
| 400-line budget risk | **Medio por PR** frente al límite de 800 de `docs/15` §3. B2 y E1 están sobre la heurística de unas 400 pero bajo 800; cada **tarea** (unidad de commit) queda en 150 a 400 líneas |
| Chained PRs recommended | Yes |
| Suggested split | **12a:** 7 PR encadenados A, B1, B2, C, D, E1, E2. **12b:** 4 PR F, G, H, I |
| Chain strategy | `stacked-to-main`: cada PR se fusiona a `main` en orden y el siguiente parte de `main` actualizado |

Decision needed before apply: No (la partición física de la carpeta en dos cambios está en «Pendiente del propietario», al final)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: Medium

## Reglas comunes a todas las tareas

- **Comandos del backend.** Desde `apps/api`, con `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` en la máquina de desarrollo
  (autorizado el 2026-10-24). Forma unitaria: `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='A,B'`.
  Forma de integración: `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=X -Dfailsafe.failIfNoSpecifiedTests=false`.
  Un `-Dtest=` acotado informa `BUILD FAILURE` por JaCoCo aunque las pruebas pasen: no es un fallo de la tarea. `./mvnw verify -Pmutation-gate`
  completo cierra cada PR que toca `apps/api`.
- **Rojo registrado.** Cada paso en rojo corre contra el árbol sin el código de producción y se registra en
  `openspec/changes/backup-restore-and-drill/apply-progress.md` con la causa **observada**. Un commit en rojo no entra al historial: las
  pruebas en rojo se comprometen con su verde. Después del verde, un paso de REFACTOR deja la suite en verde.
- **Sondas primero.** Cada sonda P-n es el **paso 0** de la tarea que la necesita. Se ejecuta, se registra su resultado en
  `apply-progress.md` y, si difiere de lo esperado, la tarea se detiene, se aplica la alternativa ya escrita en `design.md` y se añade una
  nota fechada al diseño antes de seguir (mismo procedimiento que 11a). Si la sonda P-1 difiere no hay alternativa dentro de D2: se
  detiene y se vuelve al propietario.
- **Control negativo.** Las tareas con pruebas de ausencia o de política nombran una ruptura temporal de producción que debe poner en rojo una
  aserción de resultado concreta; se restaura la copia y se confirma con `cmp` y `git diff` sin salida.
- **Secretos.** Ninguno en el repositorio (regla 13), ni en ejemplos ni en pruebas. Las pruebas generan en ejecución las llaves age (con
  `age-keygen` dentro del contenedor de herramientas), la KEK (`SecureRandom`), las contraseñas y las credenciales de MinIO.
- **Registros.** Nunca contraseña, llave, KEK, URL con credenciales ni datos de filas en salida de scripts, de sondas ni de pruebas (regla 11).
- **Notas documentales.** Notas fechadas añadidas, nunca reescritura de cuerpos existentes (`DocumentationNotesTest`). El runbook no está
  protegido por esa prueba y se reescribe.
- **Números libres.** Migraciones y ADR llevan el siguiente número libre **al aplicar** (hoy `V15`, `V16` y ADR-0029, ADR-0030); se
  verifica con `ls` el día de la tarea y se corrigen las referencias del diseño con una nota fechada si cambian.
- **Medición antes de abrir el PR.** `git diff --numstat main...HEAD` excluyendo `openspec/` y código generado, registrada en
  `apply-progress.md`. Si un PR supera 800 líneas efectivas se parte por archivos completos en la costura que nombra su sección.
- **Commits y ramas.** Conventional Commits en inglés, sin atribución de herramientas de IA, un commit por tarea con sus pruebas y docs.
  Ramas 12a: `change/backup-tooling-A-read-access`, `-B1-restore-canary`, `-B2-restore-verification`, `-C-backup-scripts`,
  `-D-round-trip`, `-E1-runbook-and-drill`, `-E2-adrs-and-notes`. Ramas 12b: `change/backup-operations-and-drill-F-systemd-and-metrics`,
  `-G-b2-bucket`, `-H-preprod-backup`, `-I-drill-acta`.
- **Idioma.** Código, identificadores y commits en inglés; documentación, runbook, acta y notas en español neutro profesional.
- **Revisión de seguridad por PR.** Lista aplicable de `docs/03` marcada en la descripción del PR.
- **Etiquetas exactas.** Las versiones de MinIO, Hadolint, `shellcheck`, `promtool` y la CLI `b2` se resuelven el día de la tarea contra la
  fuente primaria y se escriben literales.

## Orden y dependencias

```
12a
PR A:  T01                                    (parte de main con 11a fusionado)
PR B1: T02                                    (parte de A fusionado)
PR B2: T03 ── T04                             (T04 solo después del PR A de 11a)
PR C:  T05 ── T06
PR D:  T07
PR E1: T08
PR E2: T09

12b  (bloqueada hasta que aterricen 11c, 11d, cambio 10, B2 y personas)
PR F:  T10 ── T11
PR G:  T12
PR H:  T13 ── T14
PR I:  T15
```

Orden duro 12a: T01 antes de T03 (la verificación lee como `confia_backup` y necesita la política) y de T05 (la guardia de cobertura);
T02 antes de T03 (el canario existe para verificarlo); T03 antes de T04; T04 antes de T07 (la ida y vuelta ejecuta el perfil); T05 y T06
antes de T07; T07 antes de T08 (el ensayo del simulacro usa los scripts completos); T09 al final (las notas citan valores y rutas reales).
Orden duro 12b: T10 y T11 antes de T13; T12 antes de T13 (el bucket existe antes de la primera subida real); T13 antes de T14 y de T15.

**Restricción de orden de fusión con 11a.** El perfil `recovery` (T04) modifica `AppProfile`, `ConfiaApplication`, `ProcessBeanPolicy`,
`BootstrapEntryPointRulesTest` y `ExecutableJarIT`, que 11a introduce o cambia en su **PR A** (`jar-and-migrate`). **T04 no se compromete ni
se abre su PR hasta que el PR A de 11a esté fusionado en `main`**. T05 modifica `infra/ci/check-container-files.sh`, que nace en el PR C de
11a: si 11a no está fusionado por completo, esa modificación se difiere a T09 (registrado en `apply-progress.md`) y no bloquea el resto.

## Evidencia de pruebas de sistema y manuales

Todo lo que no se puede cerrar en local se registra en `openspec/changes/backup-restore-and-drill/apply-progress.md` con la salida de cada
paso y, para los escenarios `[HUMANO]`, en el acta o en el registro del procedimiento. Un escenario `[AWS]`, `[PROVEEDOR]`,
`[CAMBIO-10]` o `[HUMANO]` **no se marca cumplido con un resultado local**.

---

# Mitad 12a — `backup-tooling` (T01 a T09)

## PR A — `backup-read-access` (T01, unas 350 líneas)

### [ ] T01 — Acceso de lectura de `confia_backup`: política por tabla, prueba de cobertura y guardia de catálogo

- **Objetivo:** `confia_backup` sigue `NOBYPASSRLS` y, con `pg_dump --enable-row-security`, vuelca todas las filas de las trece tablas con
  seguridad de fila; una tabla nueva con seguridad de fila y sin política de respaldo rompe la construcción nombrándola.
- **Archivos:** `apps/api/app/src/main/resources/db/migration/V15__grant_backup_read_policies.sql` (nuevo),
  `src/test/java/com/confia/schema/BackupReadPolicyCoverageIT.java` y `BackupReadAccessIT.java` (nuevos), `MultiTenantSchemaIT.java`
  (modificar `:321` y `:346`), `src/test/resources/db/backup-withdrawal/` (migración de retirada de prueba, aplicada por `StepwiseDatabase`).
- **Cambio de prueba existente (obligatorio):** `MultiTenantSchemaIT:321` y `:346` hoy exigen `policyCountOn(name) == 1`; pasan a «exactamente
  una política de aislamiento con rol `public` y exactamente una `<table>_backup_read`». La regla general de `:97-110` no cambia. Sin este
  cambio la migración rompe la construcción.
- **Escenarios:** BI140, BI141, BI142, BI143, BI144, BI145, BI146, BI147, BI148; BR01 y BR02 (sobre `pg_dump` dentro del contenedor de
  PostgreSQL 18; la ida y vuelta completa llega en T07).
- **Paso 0 (sondas):** **P-1** `pg_dump` bajo seguridad de fila: sin `--enable-row-security` y como rol sin `BYPASSRLS` falla («query would be
  affected by row-level security policy»); con la opción y `USING (true)` vuelca todo; las políticas permisivas se combinan con `OR`; `FORCE`
  solo afecta al dueño. **Si difiere, se detiene y se vuelve al propietario** (la opción A, `BYPASSRLS`, fue rechazada).
- **Enfoque (RED/GREEN):** RED primero: `BackupReadAccessIT` (volcar como `confia_backup` y comparar conteos con los del propietario, dos
  instituciones por tabla; `pg_stat_activity` muestra el rol) y `BackupReadPolicyCoverageIT` (consulta de catálogo del diseño §3 decisión 1.3
  debe devolver cero filas; fixture en transacción revertida con una tabla con seguridad de fila y sin política debe devolverla; los otros cuatro
  roles siguen viendo cero filas sin contexto; `row_security = off` da error, no cero filas; `shared_audit_log` sigue rechazando `UPDATE`,
  `DELETE` y `TRUNCATE`; sumas de V1 a V14 sin cambio; la migración de retirada deja cero políticas de respaldo). Fallan hoy. GREEN: la migración
  con las trece políticas `<table>_backup_read` (`PERMISSIVE`, `FOR SELECT`, `TO confia_backup`, `USING (true)`, sin `GRANT`), más el ajuste de
  `MultiTenantSchemaIT`. REFACTOR: suite completa de `schema` en verde.
- **Control negativo:** comentar la política de una tabla en la migración debe poner en rojo `BackupReadPolicyCoverageIT` nombrando esa tabla y
  el conteo de `BackupReadAccessIT`; añadir `BYPASSRLS` al rol de pruebas debe poner en rojo la aserción de BI140 (`DatabasePipelineIT`).
- **Verificación:** `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='BackupReadAccessIT,BackupReadPolicyCoverageIT,MultiTenantSchemaIT,DatabasePipelineIT' -Dfailsafe.failIfNoSpecifiedTests=false`;
  cierre del PR: `./mvnw verify -Pmutation-gate` en verde.
- **Depende de:** 11a (línea base).

## PR B1 — `restore-canary` (T02, unas 350 líneas)

### [ ] T02 — Fila canario cifrada: tabla, siembra idempotente y asiento de auditoría

- **Objetivo:** existe una tabla `shared_restore_canary` y un componente que siembra, por institución, una fila con el texto plano sintético
  `CONFIA-RESTORE-CANARY-v1` cifrado con el servicio de columnas.
- **Archivos:** `V16__create_shared_restore_canary.sql` (nuevo), `apps/api/app/src/main/java/com/confia/shared/recovery/` (`RestoreCanary`,
  `RestoreCanarySeeder`, puerto del repositorio, `package-info.java`), `shared/infrastructure/JooqRestoreCanaryRepository.java`,
  `RolePrivilegeMatrixIT.java` (modificar: tabla en la matriz), pruebas nuevas `RestoreCanarySeederIT`.
- **Escenarios:** BR40 (siembra y descifrado; el viaje de ida y vuelta lo cierra T07), BR41.
- **Enfoque (RED/GREEN):** RED: `RestoreCanarySeederIT` falla sin tabla ni componente: siembra idempotente (`ON CONFLICT (institution_id, dek_id)
  DO NOTHING`), una fila por institución, descifrado con `ColumnEncryptionService.decrypt` devuelve la constante, el texto es sintético y fijo
  (BR41), asiento `shared.recovery.canary_seeded` escrito (regla 14), tabla de solo inserción con `shared_audit_is_append_only()`, privilegios
  `SELECT, INSERT` a `confia_admin_app`, `SELECT` a `confia_readonly`, nada a `confia_portal_app`, política de aislamiento y
  `shared_restore_canary_backup_read` (cubierta por `BackupReadPolicyCoverageIT` de T01 sin excepción). `MultiTenantSchemaIT` genérico pasa sin
  excepción. GREEN: migración y clases.
- **Control negativo:** quitar la política `shared_restore_canary_backup_read` debe poner en rojo `BackupReadPolicyCoverageIT` nombrando la tabla.
- **Verificación:** `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='RestoreCanarySeederIT,RolePrivilegeMatrixIT,MultiTenantSchemaIT,BackupReadPolicyCoverageIT' -Dfailsafe.failIfNoSpecifiedTests=false`;
  cierre del PR: `./mvnw verify -Pmutation-gate`.
- **Depende de:** T01.

## PR B2 — `restore-verification` (T03 y T04, unas 650 líneas)

### [ ] T03 — `RestoreVerification`: cuatro comprobaciones y registro de pendientes por fase

- **Objetivo:** una clase de caso de uso ejecuta Flyway, tablas y conteos, cadena de auditoría y canario con KEK, informa cada resultado y
  solo da `passed()` si todas las obligatorias pasan; las de fases futuras se listan como pendientes.
- **Archivos:** `shared/recovery/` (`RestoreVerification`, `RestoreVerificationReport`, `CheckResult`, `FutureRestoreChecks`, puertos de
  lectura), `shared/infrastructure/JooqRestoreCatalogReader.java`, pruebas `RestoreVerificationTest` y `RestoreVerificationIT`.
- **Escenarios:** BR33, BR34, BR35, BR36, BR37, BR38, BR39, AT39, AT40, AT41, AT42; la parte de código de BR35 que da código distinto de 0 se
  completa en T04.
- **Paso 0 (sondas):** **P-4** `Flyway.validateWithResult()` con `classpath:db/migration` solo lee `flyway_schema_history`, sin bloqueo
  consultivo ni escritura, y funciona como `confia_backup`. Alternativa escrita: `MigrationInfoService.all()` contra las filas del historial.
- **Enfoque (RED/GREEN):** RED: `RestoreVerificationTest` con dobles (cadena divergente, KEK ajena, tabla ausente, historial incompleto,
  canario ausente, tabla futura presente, institución sin canario) y `RestoreVerificationIT` sobre una base migrada real conectada como
  `confia_backup` (dos instituciones, asientos y canario sembrado): las cuatro pasan; una fila de `shared_audit_log` alterada da `Diverged`
  informando institución e id; la verificación no puede escribir (AT41: un intento de `INSERT` desde esa conexión falla); una institución sin
  asientos no falla (AT42); una tabla futura marcada pendiente sale «pendiente (fase X)» y no suma como aprobada (BR38); si su tabla existe
  y no hay implementación, falla como «required but not implemented» (BR39). Todas las comprobaciones se ejecutan aunque una falle. GREEN:
  clases y adaptador jOOQ con `executeReadOnly`.
- **Control negativo:** hacer que `FutureRestoreChecks` cuente una pendiente como aprobada debe poner en rojo la aserción de BR38; que
  `Empty` falle debe poner en rojo AT42.
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='RestoreVerificationTest'`;
  `-Dtest=none ... -Dit.test='RestoreVerificationIT'`.
- **Depende de:** T01, T02.

### [ ] T04 — Perfil de proceso `recovery`: `verify-restore` y `seed-canary`

- **Objetivo:** `APP_PROFILE=recovery verify-restore` ejecuta T03 y termina con `0` (todas pasan), `3` (alguna falla) o `1` (no pudo
  ejecutarse); `seed-canary --institution <uuid>` ejecuta T02; contexto cerrado sin web, sin `Scheduler` y con la KEK y la contraseña por archivo.
- **Archivos:** `bootstrap/AppProfile.java` y `ConfiaApplication.java` (rama `RECOVERY`), `bootstrap/recovery/RecoveryApplication.java`,
  `shared/infrastructure/wiring/RecoveryConfiguration.java`, `application-recovery.yml`, `ProcessBeanPolicy` (entrada `RECOVERY`),
  `architecture/BootstrapEntryPointRulesTest.java` (subpaquete), `ExecutableJarIT.java` (caso `recovery`), pruebas nuevas `RecoveryProfileIT`.
- **Condición de orden:** **solo después de que el PR A de 11a esté fusionado en `main`** (restricción de «Orden y dependencias»).
- **Escenarios:** BR35 (código distinto de 0 con KEK ajena), BR36 y BR37 (códigos), BR33 (código `0`); refuerza AT41 por el rol.
- **Paso 0 (sondas):** **P-10** `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/` convierte el archivo
  `confia.crypto.column-master-key` en la propiedad del mismo nombre. Alternativa escrita: `--env-file` en un directorio temporal `0700` borrado
  por `trap`.
- **Enfoque (RED/GREEN):** RED: `RecoveryProfileIT` sobre PostgreSQL de Testcontainers migrado: verificación íntegra → `0`; KEK ajena → `3`;
  conexión imposible o KEK inválida al construir → `1`; la salida enumera comprobaciones, conteos, instituciones e ids pero nunca texto plano ni
  la KEK (aserción de ausencia); contexto sin servidor web, sin `Scheduler`, sin cadena de seguridad, sin `MeterRegistry`; `seed-canary` repetido
  es idempotente. `ProcessBeanPolicy` y `BootstrapEntryPointRulesTest` aceptan `RECOVERY`. GREEN: `RecoveryApplication` con
  `@ImportAutoConfiguration` explícito, sin `@EnableAutoConfiguration`, Flyway desactivado (validador construido a mano).
- **Control negativo:** añadir `@EnableAutoConfiguration` a `RecoveryApplication` debe poner en rojo la aserción de «sin `Scheduler`»;
  imprimir la KEK en el caso de fallo debe poner en rojo la aserción de ausencia.
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BootstrapEntryPointRulesTest,ProcessBeanIsolationTest'`;
  `-Dtest=none ... -Dit.test='RecoveryProfileIT,ExecutableJarIT'`; cierre del PR B2: `./mvnw verify -Pmutation-gate`.
- **Depende de:** T03; **11a PR A fusionado**.

## PR C — `backup-scripts` (T05 y T06, unas 550 líneas)

> Nota de costura: los scripts se escriben por etapas. T05 y T06 entregan las etapas que no tocan almacenamiento S3 con pruebas propias;
> las etapas que suben, descargan y restauran desde el almacenamiento se completan en T07 **después** de su aserción roja de la ida y
> vuelta (el diseño exige «cada etapa del script se escribe después de su aserción»).

### [ ] T05 — Imagen `backup-tools`, `lib/common.sh`, `retention.conf` y `backup.sh` hasta el objeto cifrado local

- **Objetivo:** imagen fijada con `pg_dump` 18, `age` y AWS CLI; `backup.sh` valida modo y configuración, decide la etiqueta, ejecuta la
  guardia de cobertura y vuelca con `pg_dump | age` con códigos de salida por etapa; `shellcheck` en CI.
- **Archivos:** `infra/docker/backup-tools.Dockerfile`, `infra/docker/backup-tools.Dockerfile.dockerignore`, `infra/scripts/lib/common.sh`,
  `infra/scripts/backup.sh`, `infra/scripts/backup-tools.sh`, `infra/backup/retention.conf`, `.github/workflows/ci.yml` (trabajo
  `backup-static` con `shellcheck`, `timeout-minutes: 5`), `infra/ci/check-container-files.sh` (de 11a: `FROM` de `backup-tools` igual a
  `confia.postgres.image`), `apps/api/app/pom.xml` (`testcontainers-minio` en prueba, versión del BOM),
  `src/test/java/com/confia/recovery/BackupScriptStagesIT.java` y `architecture/BackupOperationalFilesTest.java` (nuevos).
- **Escenarios:** BR14, BR16, BR03 (guardia: tabla sin política → código `3`, nada subido), BR13 (escaneo estructural: ausencia de
  `AGE-SECRET-KEY-`, llaves de B2 y KEK en el repositorio), BR26 (fallo de volcado: código `4`, etapa nombrada; la ausencia de objetos en el
  bucket se cierra en T07), BR28 (parte: códigos `2`, `3`, `4`, `5` distintos), BR06 (contrato de configuración solo con destinatario público).
- **Paso 0 (sondas):** **P-12** `postgres:18-alpine` ofrece `age` 1.x y `aws-cli` v2 en `apk`. Alternativa escrita: binarios publicados con suma
  sha256 fija en el Dockerfile.
- **Enfoque (RED/GREEN):** RED: `BackupScriptStagesIT` con la imagen construida por `ImageFromDockerfile` y `execInContainer` con vector de
  argumentos fijo: `print-label --date` devuelve `monthly`, `weekly` y `daily` para 2026-12-01, 2026-12-06 y 2026-12-08 (BR14); modo desconocido
  → `2` antes de conectar (BR16); URL con contraseña → `2`; `pg_dump` de versión distinta de 18 → `2`; guardia con política retirada → `3` y
  nombra la tabla (BR03); base inalcanzable → `4` (BR26); destinatario `age` inválido → `5`. `BackupOperationalFilesTest`: `FROM` literal igual a
  `confia.postgres.image`, retenciones coherentes (`daily 7`, `weekly 28`, `monthly 365`, `pre-migration 30`), sin secretos. GREEN: Dockerfile
  (usuario `10002`, `ENTRYPOINT ["/bin/bash"]`, scripts `0555`), `common.sh` (registro sin secretos, sin `set -x`, `PGPASSFILE` temporal `0600`),
  `backup.sh` etapas 1 a 5 del diseño decisión 3 con `PIPESTATUS`.
- **Control negativo:** cambiar el `FROM` a otra versión debe poner en rojo la prueba de coherencia; hacer que `backup.sh` ignore el código de
  `pg_dump` en la tubería debe poner en rojo el caso de BR26.
- **Verificación:** `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='BackupScriptStagesIT' -Dfailsafe.failIfNoSpecifiedTests=false`;
  `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BackupOperationalFilesTest'`;
  `docker run --rm -v "$PWD:/mnt" koalaman/shellcheck:<vX.Y.Z> /mnt/infra/scripts/backup.sh /mnt/infra/scripts/lib/common.sh`;
  `docker run --rm -i hadolint/hadolint:<vX.Y.Z> hadolint --failure-threshold warning - < infra/docker/backup-tools.Dockerfile`.
- **Depende de:** T01 (guardia), T04 (en orden de rama; T05 no usa `recovery`); 11a (política de `FROM` y Hadolint).

### [ ] T06 — `restore.sh`: destino prohibido, roles del destino y secuencia hasta el descifrado

- **Objetivo:** `restore.sh` valida argumentos, rechaza un destino que contenga roles de CONFIA o la base destino antes de descargar, crea los
  cinco roles y la base y deja listo el destino para `pg_restore`; los scripts de roles declaran los mismos cinco nombres.
- **Archivos:** `infra/scripts/restore.sh`, `infra/scripts/sql/restore-target-roles.sql`, `src/test/java/com/confia/architecture/RoleScriptsConsistencyTest.java`,
  casos nuevos en `BackupScriptStagesIT`.
- **Escenarios:** BR31 (destino prohibido, código `10`, antes de descargar o modificar), BR30 (roles y base creados; permisos completos se
  comprueban tras la restauración en T07), BR28 (parte: `10`, `11`, `12`, `13`, `14`, `15` documentados).
- **Enfoque (RED/GREEN):** RED: `RoleScriptsConsistencyTest` (los scripts de roles del repositorio —pruebas, generación de código, local de
  11a, destino— declaran los mismos cinco nombres con `NOSUPERUSER NOBYPASSRLS`); `BackupScriptStagesIT` con `pg-target` limpio: argumentos
  inválidos y `--backup` fuera de la expresión regular → `2`; destino con cualquiera de los cinco roles o con la base existente → `10` sin
  descargar; destino limpio → los cinco roles `NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE`, solo `confia_backup` con `LOGIN`,
  `pg_read_all_data` concedido, base `OWNER confia_owner` y `ALTER SCHEMA public OWNER TO confia_owner`. Variables de `psql` entrecomilladas,
  sin concatenación de SQL (regla 12). GREEN: `restore.sh` pasos 1 a 6 y el SQL.
- **Control negativo:** saltar la comprobación de destino debe poner en rojo el caso de BR31 (el script llegaría a descargar).
- **Verificación:** `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='BackupScriptStagesIT' -Dfailsafe.failIfNoSpecifiedTests=false`;
  `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='RoleScriptsConsistencyTest'`; `shellcheck` sobre `restore.sh`;
  cierre del PR C: `./mvnw verify -Pmutation-gate` y el trabajo `backup-static` en verde.
- **Depende de:** T05.

## PR D — `backup-round-trip` (T07, unas 550 líneas)

### [ ] T07 — `BackupRoundTripIT`: ida y vuelta completa con MinIO y todos los casos negativos

- **Objetivo:** el ciclo completo (guardia, volcado, cifrado, sha256, manifiesto, subida con bloqueo `COMPLIANCE`, métricas, descarga,
  verificación, descifrado, roles, `pg_restore`, `verify-restore`) pasa en el trabajo `backend` y rompe la construcción si falla.
- **Archivos:** `src/test/java/com/confia/recovery/BackupRoundTripIT.java`; se completan `infra/scripts/backup.sh` (etapas de subida,
  `head-object`, manifiesto, métricas) y `infra/scripts/restore.sh` (descarga, `sha256sum -c`, prueba de descifrado, `age | pg_restore`).
- **Escenarios:** BR01, BR04, BR05, BR06, BR07, BR08, BR09, BR10, BR11, BR12, BR15, BR17, BR18, BR20, BR21, BR26 (bucket sin objetos nuevos),
  BR27, BR28, BR29, BR30 (permisos y políticas iguales a los del origen por la `snapshot` de `V9SchemaDiffIT`; `confia_admin_app` opera bajo su
  política), BR32, BR33, BR34, BR35, BR36, BR37, BR40, BR42, BR43, BR48, BR49, BR50, BR62, AT39, AT40, BI149, BI150.
- **Paso 0 (sondas):** **P-5** MinIO: imagen fijada descargable, bucket con bloqueo, `PutObjectRetention` en `COMPLIANCE`, rechazo de borrar una
  versión bloqueada incluso al usuario raíz y usuario con política sin `s3:DeleteObject` (alternativa escrita: BR20 y BR21 solo por bloqueo y la
  credencial limitada queda en `[PROVEEDOR]`, con nota en la especificación). **P-6** (parte MinIO) `put-object` con `--object-lock-mode`,
  `--object-lock-retain-until-date` y `--content-md5` y las variables de suma `when_required`. **P-2** `pg_restore` lee de la entrada estándar
  un archivo personalizado escrito por tubería y restaura con `--single-transaction` (alternativa: descifrar a `tmpfs`, modo `0600`, borrado por
  `trap`). **P-3** orden de secciones: la cadena verifica íntegra tras restaurar (alternativa: tres pasadas). **P-14** catálogos legibles por
  `confia_backup`.
- **Enfoque (RED/GREEN):** red de Docker propia con `pg-source` (roles de prueba, Flyway como `confia_owner`, datos de dos instituciones,
  asientos de auditoría, canario sembrado con KEK de prueba), `pg-target` limpio, `minio` y la imagen de herramientas; llaves de prueba
  generadas en la prueba. **Cada etapa se escribe después de su aserción roja**, en el orden del flujo del diseño §4.1 y §4.2. Un respaldo
  válido compartido por los casos negativos, que alteran copias: sha256 alterado → `12` sin descifrar ni tocar el destino (BR29); llave age
  ajena → `13` y destino intacto (BR32); fallo de `pg_dump` a mitad de tubería con `age` exitoso → distinto de 0 y sin objeto válido (BR27);
  subida corrompida → distinto de 0 sin métrica (BR08); MinIO detenido → sin residuos en `/work` (BR10); cadena alterada, KEK ajena, migración
  borrada del historial o canario ausente → `verify-restore` `3`. Búsqueda de la contraseña, la llave secreta de pruebas y la KEK de pruebas en
  toda la salida capturada, en éxito y en fallo (BR11, BR12). Rotación de age con dos destinatarios y restauración solo con la llave nueva (BR62).
  Control remoto: un commit temporal que rompe el destinatario deja el trabajo en rojo y visible (BR43, BI149), se revierte y vuelve a verde.
- **Control negativo:** quitar `pipefail`/`PIPESTATUS` de `backup.sh` debe poner en rojo BR27; subir la retención de `daily` a 8 días debe
  poner en rojo BR17.
- **Verificación:** `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='BackupRoundTripIT' -Dfailsafe.failIfNoSpecifiedTests=false`;
  cierre del PR D: `./mvnw verify -Pmutation-gate` en verde y URL de la ejecución de CI (verde y la demostración roja) en `apply-progress.md`.
- **Depende de:** T04, T05, T06.

## PR E1 — `restore-runbook-and-drill` (T08, unas 700 líneas de prosa)

### [ ] T08 — Conductor del simulacro, registro de métricas, runbook reescrito, plantilla de acta y ensayo local

- **Objetivo:** el simulacro tiene conductor ejecutable y plantilla; el runbook ejecuta solo comprobaciones existentes en F0, declara los
  objetivos por ruta, exige la lista de eliminaciones pendientes (bloqueada y sin dueño) y describe custodia, rotación y simulacro anual.
- **Archivos:** `infra/scripts/restore-drill.sh`, `infra/scripts/record-restore-drill.sh`, `docs/runbooks/restauracion-de-respaldo.md`
  (reescritura), `docs/runbooks/plantillas/acta-simulacro-de-restauracion.md` (nuevo), `BackupOperationalFilesTest` (extensión),
  evidencia del ensayo en `apply-progress.md`.
- **Escenarios:** BR55, BR65, BR66, BR67, BR68 (procedimiento; la ejecución es posterior a F0), BR64 (regla y procedimiento por escrito),
  BR60 y BR61 (tabla de comprobaciones mensuales y registro de entrega temporal en el runbook y el acta; la evidencia humana es T15), BR62 (pasos
  documentados), BR13 (escaneo ampliado a runbook y acta); la parte de métricas de simulacro de BR56 (`record-restore-drill.sh` escribe
  `confia_restore_drill.prom` de forma atómica).
- **Enfoque:** excepción declarada para runbook y acta (documentos sin ejecutor de rojo semántico). Con rojo real: `BackupOperationalFilesTest`
  falla primero contra el runbook actual y la plantilla ausente: el acta contiene fecha, ejecutor, respaldo, etapas con objetivo, total contra
  cuatro horas, cuatro comprobaciones, registro de acceso a llaves y firma del propietario (BR55); el runbook no referencia `ledger_transactions`,
  `payments`, `students`, `fiscal_documents` ni `cai_ranges` como pasos ejecutables y las marca por fase (BR65); encabezado con 15 minutos para la
  ruta A, hasta 24 horas para la ruta B con la aceptación del propietario y su condición de revisión, y menos de cuatro horas de recuperación
  (BR66); paso de eliminaciones pendientes presente, bloqueado, sin dueño y con la regla «no se cargan datos reales» (BR67); sección del
  simulacro anual (BR68); tabla de pasos por fase. `record-restore-drill.sh` se prueba en `BackupScriptStagesIT` (archivo `.prom` atómico con
  los dos nombres). **Ensayo local completo del simulacro** (`restore-drill.sh` contra un respaldo local en MinIO, sin AWS, con llaves de prueba
  y todos los tiempos medidos) con la tabla de etapas pegada en `apply-progress.md`; valida el conductor y la plantilla antes de T15.
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BackupOperationalFilesTest'`;
  `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='BackupScriptStagesIT' -Dfailsafe.failIfNoSpecifiedTests=false`;
  `shellcheck` sobre los dos scripts; `bash infra/scripts/restore-drill.sh --help` sale con 0.
- **Depende de:** T07.
- **Riesgo de tamaño:** si el PR supera 800 líneas efectivas, se parte por archivos completos en «conductor y métricas» y «runbook y acta».

## PR E2 — `backup-adrs-and-notes` (T09, unas 350 líneas)

### [ ] T09 — ADR-0029, ADR-0030, notas fechadas y convención del agente de base de datos

- **Objetivo:** las dos decisiones de ADR quedan escritas y los documentos vigentes reciben notas fechadas, sin reescribir cuerpos.
- **Archivos:** `docs/adr/ADR-0029-lectura-del-respaldo-bajo-seguridad-de-fila.md` y `ADR-0030-respaldo-fuera-de-aws-en-backblaze-b2.md`
  (**números provisionales: se toma el siguiente libre al aplicar**; hoy son 0029 y 0030), notas fechadas en `docs/03-seguridad.md` (§6.1 y §7.2),
  `docs/05-infraestructura-y-despliegue.md` (§6 y §11), `docs/07-observabilidad-y-operaciones.md`, `docs/08-datos-privacidad-y-retencion.md`
  (§6.1), `docs/09-roadmap-y-fases.md` (avance del criterio 6), `docs/adr/ADR-0014-…md`, `docs/adr/ADR-0017-…md`,
  `openspec/changes/foundations-plan/exploration.md`, `.claude/agents/confia-database.md` (convención `<table>_backup_read`).
- **ADR escritos por esta tarea:** **ADR-0029** (política de lectura por tabla, `NOBYPASSRLS` conservado, cobertura por catálogo y guardia) y
  **ADR-0030** (B2, bloqueo `COMPLIANCE`, retención menor que el ciclo de vida, llave sin borrado, age con destinatarios múltiples y custodia
  del propietario, imagen de herramientas, perfil `recovery`). Las notas de ADR-0014 y ADR-0017 remiten a ellos.
- **Escenarios:** ninguno nuevo; sostiene BR66 y BR67 (consistencia con el runbook) y la trazabilidad de BI140 a BI148.
- **Enfoque:** excepción declarada (documentación sin ejecutor de rojo). Comprobación con la prueba existente `DocumentationNotesTest`
  (los originales de `docs/03` §6.1 y ADR-0017 no cambian). Si se difirió en T05, se completa aquí la modificación de
  `infra/ci/check-container-files.sh`. Las notas que mencionan el plazo de `pre-migration` (30 días, supuesto de diseño) y los plazos efectivos
  de borrado (ocultar más un día) quedan explícitas.
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DocumentationNotesTest,BackupOperationalFilesTest'`;
  `ls docs/adr | tail -3` para confirmar el número libre.
- **Depende de:** T08 (citan rutas y valores reales).

---

# Mitad 12b — `backup-operations-and-drill` (T10 a T15)

> **Bloqueada.** No se inicia hasta que aterricen: **11c** (la imagen `backup-tools` se publica por dígest en GHCR), **11d** (EC2, RDS con roles,
> secretos, estado de infraestructura), **cambio 10** (Prometheus, Alertmanager y recolector del nodo), la **cuenta de B2** con bloqueo de objeto
> y las **personas** (propietario con las llaves, desarrollador ejecutor).

## PR F — `backup-systemd-and-metrics` (T10 y T11, unas 350 líneas)

### [ ] T10 — Unidades `systemd`, estado de RDS e instalación documentada

- **Objetivo:** servicio y temporizador diarios de las 01:00 para el respaldo, y servicio y temporizador cada cinco minutos para el estado de
  RDS; sin contraseñas ni llave privada en las unidades.
- **Archivos:** `infra/systemd/confia-backup.service`, `confia-backup.timer`, `confia-rds-backup-status.service`, `confia-rds-backup-status.timer`,
  `infra/scripts/rds-backup-status.sh`, `docs/runbooks/restauracion-de-respaldo.md` (sección de instalación), `BackupOperationalFilesTest`
  (extensión).
- **Escenarios:** BR46, BR59; refuerza BR48 a BR50 (rutas de archivos `.prom`).
- **Enfoque (RED/GREEN):** RED: `BackupOperationalFilesTest` falla sin unidades: el temporizador declara la cadencia diaria de las 01:00 con
  `Persistent=true`; el servicio ejecuta `backup-tools.sh backup.sh nightly` con `EnvironmentFile` y sin contraseñas, llave privada ni KEK;
  ninguna tarea de db-scheduler, `@Scheduled` ni flujo de GitHub Actions ejecuta `backup.sh` (búsqueda en `.github/workflows/` y en Java).
  `rds-backup-status.sh` se prueba con un doble de AWS CLI: escribe `confia_rds.prom` con `confia_rds_backup_enabled` y
  `confia_rds_latest_restorable_timestamp_seconds`. GREEN: archivos. P-8 y P-9 se resuelven en T13 (necesitan la instancia).
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BackupOperationalFilesTest'`;
  `shellcheck infra/scripts/rds-backup-status.sh`; `systemd-analyze verify infra/systemd/*` si el sistema lo permite.
- **Depende de:** 12a completa; 11c y 11d (valores de instalación).

### [ ] T11 — Reglas de alerta, regla de registro del retraso de RDS y `promtool` en CI

- **Objetivo:** las cuatro alertas existen con las condiciones de `docs/07` y se prueban con `promtool test rules`.
- **Archivos:** `infra/prometheus/rules/confia-backup.rules.yml`, `infra/prometheus/rules/tests/confia-backup.rules.test.yml`,
  `.github/workflows/ci.yml` (`promtool check rules` y `promtool test rules` en `backup-static` desde `prom/prometheus:<vX.Y.Z>` fijado).
- **Escenarios:** BR52, BR53.
- **Paso 0 (sondas):** **P-13** la imagen `prom/prometheus` trae `promtool` con `check rules` y `test rules`. Alternativa escrita: binario de la
  versión publicada con suma fija.
- **Enfoque (RED/GREEN):** RED: el archivo de pruebas de reglas (27 horas sin éxito, `backup_enabled` 0, retraso de más de 900 segundos durante 10
  minutos y simulacro de 36 días disparan sus cuatro alertas con severidades `S1`, `S1`, `S1`, `S3`; 25 horas, retraso de 600 segundos y simulacro
  de 30 días no disparan nada; ausencia de series dispara `BackupMissing` y `RestoreDrillOverdue`) falla sin las reglas. GREEN: reglas, con el
  retraso de RDS como regla de registro `time() - confia_rds_latest_restorable_timestamp_seconds`.
- **Control negativo:** cambiar `93600` por `97200` en la regla debe poner en rojo la prueba de las 27 horas.
- **Verificación:** `docker run --rm -v "$PWD/infra/prometheus:/p" --entrypoint promtool prom/prometheus:<vX.Y.Z> check rules /p/rules/confia-backup.rules.yml`;
  `docker run ... promtool test rules /p/rules/tests/confia-backup.rules.test.yml`; CI `backup-static` en verde.
- **Depende de:** T10 (nombres de métricas); independiente del cambio 10 para la prueba de reglas. Cierre del PR F: `./mvnw verify -Pmutation-gate`.

## PR G — `b2-bucket` (T12, unas 300 líneas)

### [ ] T12 — Configuración versionada del bucket en B2 y comprobación con el proveedor real

- **Objetivo:** el bucket de B2 con versionado, bloqueo `COMPLIANCE`, ciclo de vida por prefijo y llaves de aplicación se crea y se comprueba
  con scripts versionados; la evidencia del proveedor real queda registrada.
- **Archivos:** `infra/backup/b2/configure-bucket.sh`, `infra/backup/b2/check-bucket.sh`, `BackupOperationalFilesTest` (extensión),
  evidencia en `apply-progress.md`.
- **Escenarios:** BR23 (estructural: versionado, `COMPLIANCE`, cuatro reglas coherentes con `retention.conf`, sin credenciales), BR19, BR22,
  BR24, BR25 (todos `[PROVEEDOR]`); cierre de BR17 contra la regla real.
- **Pasos humanos previos (no son tareas de código):** el propietario crea la cuenta de B2, habilita el bloqueo de objeto al crear el bucket y
  entrega las llaves de aplicación (escritura sin borrado, lectura) a los destinos descritos en el diseño §3 decisión 6.3; la llave maestra no
  sale de él.
- **Paso 0 (sondas):** **P-6** (parte B2): `put-object` con `COMPLIANCE`, retención y `--content-md5` aceptado por B2 con
  `AWS_REQUEST_CHECKSUM_CALCULATION=when_required`. **P-7** capacidades de la llave de aplicación sin `deleteFiles`, comportamiento de
  `DeleteObject` sin versión y nombres de campos del ciclo de vida de la CLI `b2`. Alternativas escritas en el diseño; usar documentación
  primaria y un bucket `confia-backups-test` con retención de un día.
- **Enfoque:** RED para lo estructural: `BackupOperationalFilesTest` falla sin los scripts (BR23). Excepción declarada para lo `[PROVEEDOR]`:
  `check-bucket.sh` contra el bucket de pruebas intenta borrar la versión (rechazada), intentar borrar sin versión (rechazo u ocultación; la
  versión sigue descargable) y sobrescribir (nueva versión; la original bloqueada conserva sha256); compara el estado con la configuración
  (BR24); BR25 se cierra al día siguiente con el objeto de un día vencido y rotado. Resultado en `apply-progress.md`.
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BackupOperationalFilesTest'`;
  `bash infra/backup/b2/check-bucket.sh confia-backups-test` (con credenciales entregadas por el propietario fuera del repositorio).
- **Depende de:** T07 (retenciones), cuenta de B2; **BR25 exige esperar el vencimiento del bucket de un día** (cierre diferido).

## PR H — `preprod-backup` (T13 y T14, unas 200 líneas)

### [ ] T13 — Respaldo en preproducción: RDS, ruta A, instalación, canario y siete noches

- **Objetivo:** el respaldo nocturno corre en EC2, RDS tiene respaldos automáticos y se prueba la restauración a un punto en el tiempo.
- **Archivos:** configuración de RDS en `infra/tofu/` de 11d (período de retención y ventana de respaldo), instalación en el runbook, evidencia en
  `apply-progress.md`.
- **Escenarios:** BR44, BR45, BR47 (todos `[AWS]`; BR45 también `[HUMANO]`); BR51 parte de recolección con el cambio 10 se cierra en T14.
- **Pasos 0 (sondas, en este orden):** **P-11** primer volcado real de preproducción restaurado en local: ACL propias de RDS en el volcado
  (alternativa: `-L` con lista filtrada). **P-8** `systemd-analyze calendar` con `America/Tegucigalpa` (alternativa `07:00 UTC`). **P-9**
  credenciales de instancia desde el contenedor por IMDSv2 (alternativa: límite de saltos en 2 o `--network host`).
- **Enfoque:** excepción declarada (AWS, sin ejecutor local). Orden: instalar unidades y archivo de entorno (`0600`, desde el gestor de
  secretos de 11d), sembrar el canario con `APP_PROFILE=recovery seed-canary` tras `migrate`, fijar retención de RDS en 11d, confirmar
  `confia_rds_backup_enabled = 1` (BR44), restaurar a un punto en el tiempo según la ruta A y ejecutar la verificación (BR45), y dejar correr
  **siete noches** con comprobación diaria de los objetos y de la marca de éxito (BR47). Cada paso con su salida en `apply-progress.md`.
- **Verificación:** `systemctl list-timers confia-backup.timer` y `aws s3api list-objects-v2` (con credenciales de lectura) por siete noches;
  `aws rds describe-db-instances --query 'DBInstances[].BackupRetentionPeriod'`.
- **Depende de:** T10, T11, T12; 11c, 11d.

### [ ] T14 — Integración con el cambio 10: recolección de las seis métricas y `BackupMissing` extremo a extremo

- **Objetivo:** Prometheus recolecta las seis métricas en preproducción y `BackupMissing` llega a su destino al detener el temporizador.
- **Archivos:** reglas cargadas por el cambio 10 desde `infra/prometheus/rules/`; evidencia en `apply-progress.md`. Sin código nuevo propio.
- **Escenarios:** BR51, BR54 (`[CAMBIO-10]` y `[AWS]`).
- **Enfoque:** excepción declarada (depende de infraestructura externa). Consultar las seis series con valores recientes (BR51); detener el
  temporizador más de 26 horas y registrar la notificación recibida (BR54); reanudar.
- **Verificación:** consulta en Prometheus de las seis series y captura de la alerta en Alertmanager con su destino.
- **Depende de:** T13; cambio 10.

## PR I — `restore-drill-acta` (T15, unas 150 líneas de prosa)

### [ ] T15 — Simulacro con respaldo real, acta firmada y cierre del criterio de salida 6

- **Objetivo:** restaurar fuera de AWS un respaldo nocturno real de preproducción en menos de cuatro horas, con las cuatro comprobaciones en verde,
  y que el propietario firme el acta.
- **Archivos:** `docs/operacion/actas/<fecha>-simulacro-de-restauracion.md` (acta firmada transcrita), `docs/09-roadmap-y-fases.md` (nota fechada
  de cierre del criterio 6 con referencia al acta), evidencia en `apply-progress.md`.
- **Escenarios:** BR56, BR57, BR58, BR60, BR61 (evidencia real), y BR64 si hay una rotación en curso.
- **Pasos humanos (ninguno automatizable):** (1) el **propietario** entrega la llave privada age y la KEK al desarrollador y registra hora de
  entrega; (2) el **desarrollador** ejecuta `infra/scripts/restore-drill.sh` en una estación fuera de AWS contra un respaldo real descargado de
  B2; (3) el desarrollador ejecuta `record-restore-drill.sh` en EC2 por Session Manager; (4) el propietario recibe de vuelta las llaves, se borran las
  copias temporales y se registran uso y devolución; (5) el propietario revisa y **firma el acta**; (6) se archiva el acta y se anota el cierre en
  `docs/09`. Sin la firma el criterio permanece abierto (BR58).
- **Enfoque:** excepción declarada (humano). Aceptación: tiempo total menor que cuatro horas contra el objetivo del acta, cuatro comprobaciones en
  verde, métrica `confia_restore_drill_last_success_timestamp_seconds` publicada, firma del propietario transcrita.
- **Verificación:** `bash infra/scripts/restore-drill.sh ...` con la salida de etapas pegada en el acta; el acta firmada y archivada; revisión de
  `docs/09`.
- **Depende de:** T13 y T14; personas disponibles; ensayo de T08.

---

# Cobertura de escenarios

Etiquetas: **L** local y CI, **A** AWS, **P** proveedor B2, **C10** cambio 10, **H** humano. «Cierre» es la tarea que lo da por cumplido; «parcial»
son tareas que aportan una parte.

## `backup-and-restore` (BR01 a BR68, sin BR63)

| Escenario | Etiqueta | Cierre | Parcial |
|---|---|---|---|
| BR01 | L | T07 | T01 |
| BR02 | L | T01 | |
| BR03 | L | T05 | |
| BR04, BR05, BR06 | L | T07 | T05 (BR06) |
| BR07, BR08 | L | T07 | |
| BR09, BR10 | L | T07 | |
| BR11, BR12 | L | T07 | |
| BR13 | L | T08 | T05 |
| BR14 | L | T05 | |
| BR15 | L | T07 | |
| BR16 | L | T05 | |
| BR17 | L | T07 | T12 |
| BR18 | L | T07 | |
| BR19 | **P** | T12 | |
| BR20, BR21 | L | T07 | |
| BR22 | **P** | T12 | |
| BR23 | L | T12 | |
| BR24, BR25 | **P** | T12 | |
| BR26 | L | T07 | T05 |
| BR27 | L | T07 | |
| BR28 | L | T07 | T05, T06 |
| BR29 | L | T07 | |
| BR30 | L | T07 | T06 |
| BR31 | L | T06 | |
| BR32 | L | T07 | |
| BR33 | L | T07 | T03, T04 |
| BR34 | L | T07 | T03 |
| BR35 | L | T07 | T03, T04 |
| BR36, BR37 | L | T07 | T03 |
| BR38, BR39 | L | T03 | |
| BR40 | L | T07 | T02 |
| BR41 | L | T02 | |
| BR42, BR43 | L | T07 | |
| BR44 | **A** | T13 | |
| BR45 | **A, H** | T13 | |
| BR46 | L | T10 | |
| BR47 | **A** | T13 | |
| BR48, BR49, BR50 | L | T07 | |
| BR51 | **C10, A** | T14 | T13 |
| BR52, BR53 | L | T11 | |
| BR54 | **C10, A** | T14 | |
| BR55 | L | T08 | |
| BR56 | **A, H** | T15 | T08 (métrica del script) |
| BR57, BR58 | **H** | T15 | |
| BR59 | L | T10 | |
| BR60, BR61 | **H** | T15 | T08 (registro por escrito) |
| BR62 | L | T07 | T08 (documentación) |
| BR63 | **fuera de este cambio** | Cambio que cablee la KEK en producción (nota fechada 2026-11-09 en la especificación) | |
| BR64 | **H** | T08 (regla escrita; evidencia al rotar, T15 si hay rotación) | |
| BR65, BR66, BR67 | L | T08 | |
| BR68 | **H** | T08 (solo procedimiento; ejecución posterior a F0) | |

## `build-integrity` (BI140 a BI150) y `audit-trail` (AT39 a AT42)

Todos **L**. BI140 a BI148: **T01**. BI149 y BI150: **T07**. AT39 y AT40: **T03** (IT) y **T07** (ida y vuelta). AT41 y AT42: **T03**.

## Escenarios que solo cierran personas, AWS o el proveedor

| Escenario | Quién o qué lo cierra | Tarea | Observación |
|---|---|---|---|
| BR19, BR22, BR24 | Bucket real de B2 y la cuenta del propietario | T12 | Requiere la cuenta de B2 y las llaves de aplicación |
| BR25 | B2, tras esperar el vencimiento del bucket de prueba | T12 | Cierre diferido al menos un día |
| BR44, BR47 | Preproducción de AWS (11d), siete noches reales | T13 | Siete noches de calendario |
| BR45 | AWS más un operador | T13 | Ruta A contra RDS |
| BR51, BR54 | Cambio 10 y AWS | T14 | Bloqueados por el cambio 10 |
| BR56 | Operador fuera de AWS con llaves del propietario | T15 | Medición real del tiempo de recuperación |
| BR57, BR58 | Firma del propietario | T15 | Sin firma el criterio 6 no se cierra |
| BR60, BR61 | Propietario (custodia, entrega y devolución) | T15 | Primera verificación mensual real |
| BR64 | Propietario, al rotar | T08 (regla) | Evidencia solo cuando ocurra una rotación |
| BR68 | Ejecución posterior a F0 | T08 | No forma parte del cierre del cambio |

## Pasos manuales y humanos (resumen)

1. **Cuenta de B2:** el propietario la crea con bloqueo de objeto habilitado al crear el bucket y emite las llaves de aplicación (T12).
2. **Custodia de llaves (D4):** el propietario genera la llave privada age (`age-keygen`, nunca en EC2 ni en CI), custodia la identidad y la KEK
   en un gestor personal fuera de AWS más copia física sellada, y entrega el destinatario público a la configuración (T13).
3. **Comprobación mensual de acceso:** el propietario demuestra que produce ambas llaves; queda registrada con fecha (BR60).
4. **Simulacro y acta firmada:** entrega temporal de llaves, ejecución por el desarrollador fuera de AWS, devolución con borrado de copias y
   firma del propietario (T15; BR56, BR57, BR61).
5. **Retiro de una llave anterior:** solo con el acta de una restauración exitosa con la llave nueva (BR64).

## ADR de este cambio

| ADR (provisional; el siguiente libre al aplicar) | Decisión | Escribe |
|---|---|---|
| ADR-0029 «Lectura del respaldo bajo seguridad de fila» | `NOBYPASSRLS` conservado; política `<table>_backup_read` por tabla; cobertura por catálogo y guardia en tiempo de respaldo | **T09** |
| ADR-0030 «Respaldo lógico fuera de AWS en Backblaze B2» | B2 con `COMPLIANCE` por objeto, retención menor que el ciclo de vida, llave sin borrado, age con destinatarios múltiples, imagen de herramientas, perfil `recovery` | **T09** |

## Pendiente del propietario

- **Partición física de la carpeta.** Esta lista conserva ambas mitades en `openspec/changes/backup-restore-and-drill/`. Falta decidir si, al
  empezar 12b, se crean las carpetas `backup-tooling` y `backup-operations-and-drill` con la repartición de los deltas por mitad (los escenarios
  `[AWS]`, `[PROVEEDOR]`, `[CAMBIO-10]` y `[HUMANO]` irían a 12b) o si se mantiene una carpeta con dos ramas de archivado. No se asume.
- **Empezar T10 y T11 antes de los bloqueadores.** Solo necesitan 12a; esta lista no lo asume.
- **Plazo de `pre-migration` (30 días):** supuesto del diseño, ajustable en `infra/backup/retention.conf`.

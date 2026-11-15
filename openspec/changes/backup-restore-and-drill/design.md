# Diseño: respaldo, restauración y simulacro (cambio 12 de F0)

- **Cambio:** `backup-restore-and-drill` (F0, cambio 12; entregable 9, brecha B10; cierra el criterio de salida 6)
- **Fase:** diseñar
- **Fecha:** 2026-11-09
- **Estado:** pendiente de aprobación del propietario
- **Entradas:** `proposal.md` (aprobada el 2026-11-09 con las decisiones D1 a D4), `exploration.md`, los deltas en
  curso de `specs/` (`backup-and-restore` BR01 a BR68, `build-integrity` BI140 a BI150, `audit-trail` AT39 a AT42),
  `docs/03-seguridad.md` §2, §6.1 y §7.2, `docs/05-infraestructura-y-despliegue.md` §6 y §11,
  `docs/07-observabilidad-y-operaciones.md` §5, §7.2 y §10, `docs/08-datos-privacidad-y-retencion.md` §6,
  `docs/runbooks/restauracion-de-respaldo.md`, ADR-0014, ADR-0015, ADR-0016, ADR-0017, ADR-0022, ADR-0023, ADR-0024,
  el código de la rama en el punto de partida y el diseño de 11a (`dockerfiles-and-local-compose/design.md`) como
  precedente de forma y como dependencia.
- **Decisiones del propietario aplicadas:** D1 proveedor Backblaze B2; D2 `confia_backup` sigue `NOBYPASSRLS` con una
  política de lectura por tabla, `pg_dump --enable-row-security` y prueba de cobertura; D3 punto de recuperación de
  unas 24 horas ante la pérdida total de la cuenta de AWS mientras no existan datos reales; D4 el propietario firma el
  acta y custodia la llave privada age y la KEK.
- **Modo de TDD:** estricto (`openspec/config.yaml:15`). Ejecutor: `./mvnw verify` en `apps/api` con JDK 25 y Docker.
  Los scripts de shell se prueban por la ida y vuelta de Testcontainers (rojo/verde real) y por `shellcheck`; las
  reglas de alerta por `promtool test rules`; las unidades de `systemd`, el runbook y el acta por pruebas
  estructurales. Lo que solo existe en AWS, en B2 o en manos de personas queda en tareas con evidencia en el acta.

## 1. Enfoque técnico

El cambio construye la recuperación completa sin tocar código de negocio y la apoya en siete invariantes:

1. **El respaldo lee como `confia_backup`, sin `BYPASSRLS`, a través de una política explícita por tabla.** Cada
   tabla con seguridad de fila tiene, además de su política de aislamiento, una política permisiva `FOR SELECT TO
   confia_backup USING (true)`. Una prueba de cobertura en la construcción y una guardia en tiempo de respaldo impiden
   que una tabla nueva quede sin ella (y que el volcado salga vacío sin error).
2. **El texto plano nunca toca un disco.** `pg_dump` escribe directamente en `age`; la restauración descifra
   directamente hacia `pg_restore`. Lo único que se escribe en disco es el objeto cifrado.
3. **Las herramientas viven en una imagen fijada** (`backup-tools`: cliente de PostgreSQL 18, `age`, AWS CLI v2 y
   los scripts). La misma imagen corre en la prueba de ida y vuelta, en el temporizador de EC2 y en el simulacro.
4. **Bloqueo nunca mayor que el ciclo de vida.** El script fija la retención de cumplimiento de cada objeto según su
   etiqueta y el ciclo de vida de B2 oculta y borra después de ese plazo.
5. **La verificación es Java, del mismo artefacto, y es de solo lectura.** Un perfil de proceso nuevo, `recovery`,
   ejecuta las cuatro comprobaciones como `confia_backup` reutilizando `DefaultAuditChainVerifier` y
   `ColumnEncryptionService`; no puede escribir en la base restaurada por construcción.
6. **Un canario cifrado con texto plano conocido** existe en cada base que se respalda; sin él la verificación falla.
7. **Las llaves privadas nunca están donde corre el respaldo.** El servidor solo conoce el destinatario público de
   age y una credencial de B2 que escribe sin borrar; el simulacro corre fuera de AWS con las llaves que entrega el
   propietario.

```
EC2 (systemd, 01:00 Tegucigalpa)                         B2 (bucket con bloqueo, versionado)
backup-tools.sh ─► docker run backup-tools backup.sh nightly
   │ guardia de cobertura (pg_policy)                      daily/  weekly/  monthly/  pre-migration/
   │ pg_dump -Fc --enable-row-security (confia_backup)       └─ <ts>/confia-<label>-<ts>.dump.age   (COMPLIANCE)
   │   └─► age -r <destinatarios> ─► objeto .age               ├─ <ts>/…dump.age.sha256           (COMPLIANCE)
   │ sha256 ─► manifiesto ─► put-object (lock por etiqueta) ──►└─ <ts>/…manifest                  (COMPLIANCE)
   └─► textfile: confia_backup_*.prom ─► node_exporter (cambio 10) ─► Prometheus ─► BackupMissing

Estación del operador, fuera de AWS (simulacro)
restore-drill.sh ─► postgres:18 limpio ─► restore.sh (descarga, sha256, age -d, roles, pg_restore --role confia_owner)
                 └─► confia-api APP_PROFILE=recovery verify-restore (confia_backup, KEK por archivo) ─► 0 | 3
```

## 2. Evidencia obtenida antes de esta fase

### 2.1 Código y documentos leídos que condicionan el diseño

| Hecho | Evidencia | Consecuencia |
|---|---|---|
| Trece tablas tienen `ENABLE` y `FORCE ROW LEVEL SECURITY` con una sola política sin cláusula `TO` que exige `app.institution_id` | `V1:32-44`, `V2:21-26,65-74`, `V4:64-70`, `V5:74-86`, `V6:88-109`, `V7:41-44`, `V8:110-119`, `V14:21-24` | La política de respaldo se añade a las trece en una migración nueva; `scheduled_tasks` y `flyway_schema_history` no tienen seguridad de fila y se leen con `pg_read_all_data` |
| `confia_backup` es `NOBYPASSRLS` con `pg_read_all_data` en el script de pruebas y `NOLOGIN NOBYPASSRLS` en el de generación de código | `create-test-roles.sql:15-18`; `beforeMigrate__create_codegen_roles.sql:16` | El rol existe en todas las rutas en que corre Flyway, así que una migración puede nombrarlo en `CREATE POLICY ... TO confia_backup` |
| La invariante «ningún rol con `BYPASSRLS`» está probada para los cinco roles | `DatabasePipelineIT.java:27-63`; `docs/03:891-892` | Se conserva literal; ninguna prueba cambia de sentido |
| Varias pruebas exigen **exactamente una** política por tabla | `MultiTenantSchemaIT.java:321`, `:346` (`policyCountOn(name) == 1`) | Se reescriben a «una política de aislamiento sin rol y una de respaldo para `confia_backup`»; de lo contrario la migración rompe la construcción |
| Los comentarios de las migraciones afirman que `confia_backup` lee con `pg_read_all_data`, «que no anula la seguridad de fila» | `V1:52-53`, `V2:120-122`, `V4:87`, `V5:110`, `V6:138`, `V10:68-69`, `V14:35`; `RolePrivilegeMatrixIT.java:189` y siguientes | Siguen siendo ciertos; las migraciones aplicadas no se editan. El cambio de semántica queda en el ADR y en la nota de `docs/03` |
| Las comparaciones de esquema por paso se toman hasta versiones fijas | `V9SchemaDiffIT.java:98-160` (`validateUpTo`) | Una migración posterior no las altera; su función `snapshot` se reutiliza para comparar catálogos de origen y destino |
| El verificador abre su propia transacción con `app.institution_id` y filtra por institución | `DefaultAuditChainVerifier.java:52-57`; `JooqAuditLogReader.java:40,50` | Puede ejecutarse como `confia_backup`: la política de respaldo deja ver todas las filas y el lector sigue filtrando por institución |
| `TransactionRunner` ofrece transacciones de solo lectura con el mismo contexto de seguridad | `TransactionRunner.java:93-100` | Los conteos y el canario usan `executeReadOnly` |
| La KEK llega por la propiedad `confia.crypto.column-master-key`; su cableado real está diferido | `SharedPlatformConfiguration.java:62,110-125`; `ColumnEncryptionMasterKey.java:13-19` | El perfil `recovery` la recibe por archivo montado (decisión 8.5) |
| El trabajador **no** importa la configuración de cifrado; solo el administrativo la importa | `WorkerApplication.java:71`; `AdminApplication.java:72` | El canario no lo siembra un trabajo del trabajador (decisión 9): darle la KEK al trabajador ampliaría la superficie del secreto |
| El descifrado exige tabla, columna, institución y fila como datos adicionales autenticados | `ColumnEncryptionService.java:77-91` | El canario usa su propia tabla y columna, y la verificación detecta una fila copiada |
| `SECURITY DEFINER` en el disparador de la cadena y en las funciones de purga, propiedad de `confia_owner` | `V3:170-185`; `V11:10,33`; `V12:11,34`; `V13:35` | La restauración debe dejar los objetos a nombre de `confia_owner`; restaurar como otro rol cambiaría quién ejecuta esas funciones (decisión 7.3) |
| El disparador de encadenamiento asigna `id`, `prev_hash` y `row_hash` en cada `INSERT` | `V2:6-9`; `V3:170-232` | La carga de datos de `pg_restore` debe ocurrir sin disparadores; `pg_dump` los crea en la sección posterior a los datos (sonda P-3) |
| `AppProfile` admite `admin`, `portal`, `worker` y `migrate`; 11a convierte `migrate` en un contexto cerrado | `AppProfile.java:11-33`; `ConfiaApplication.java:78-99`; 11a design.md decisión 2 | El perfil `recovery` sigue el mismo patrón y se aplica después del PR A de 11a |
| La imagen de PostgreSQL tiene fuente única | `apps/api/pom.xml:52`; `PostgresImageSingleSourceTest.java:23-40`; 11a decisión 10 punto 4 | `backup-tools.Dockerfile` usa la misma cadena en su `FROM` y la política de 11a lo comprueba |
| Las notas documentales se prueban línea por línea | `DocumentationNotesTest.java:38-44` (docs 03, 05, 07, 08, 09); originales de `docs/03` §6.1 y ADR-0017 en `src/test/resources/documentation-notes/` | Toda edición de esos documentos es una nota fechada; el runbook no está protegido y se reescribe |
| Las cuatro alertas y las seis métricas ya tienen nombre y condición | `docs/07:450-453`, `:635-637`, `:662`, `:940-942` | Se respetan los nombres; dos ajustes de forma se declaran en la sección 7 |
| `confia_backup_duration_seconds` figura como histograma | `docs/07:451` | Un script que corre una vez por noche no produce un histograma útil desde un archivo de texto; se publica como gauge (sección 7) |
| El registro de imágenes es GHCR, fuera de AWS | `docs/05:588-673` | Las imágenes de API y de herramientas siguen disponibles si se pierde la cuenta de AWS |
| MinIO es el almacenamiento S3 de las pruebas | ADR-0014:182, :345, :362 | La ida y vuelta usa MinIO en Testcontainers |
| La restauración en el runbook llama a `pg_restore` sin roles ni dueño y verifica tablas que no existen | runbook:132-214 | Reescritura (decisión 13) |

### 2.2 Sondas

Esta fase no ejecutó nada: el ejecutor de diseño no tiene intérprete de órdenes ni la herramienta de documentación
externa (context7) disponibles. Las afirmaciones sobre PostgreSQL 18, B2, MinIO, AWS CLI, `age` y `systemd` provienen
de la lectura y del conocimiento del ejecutor, y quedan como **sondas que resuelve la primera tarea de su rebanada**,
con la alternativa ya escrita. Si una sonda difiere, la rebanada se detiene y este diseño recibe una nota fechada
antes de seguir (mismo procedimiento que 11a, §2.2).

| Sonda | Resultado esperado | Prueba o comando que la resuelve | Si difiere |
|---|---|---|---|
| P-1 `pg_dump` bajo seguridad de fila | Sin `--enable-row-security` y como rol sin `BYPASSRLS`, `pg_dump` falla («query would be affected by row-level security policy»); con la opción y la política `USING (true)` para el rol, vuelca todas las filas; las políticas permisivas se combinan con `OR`; `FORCE` solo afecta al dueño | `BackupReadAccessIT` (tarea 1), con `pg_dump` ejecutado dentro del contenedor de PostgreSQL 18 | Ninguna alternativa dentro de D2: se detiene y se vuelve al propietario (la opción A, `BYPASSRLS`, fue rechazada) |
| P-2 Restauración desde una tubería | `pg_restore` lee de la entrada estándar un archivo personalizado que `pg_dump` escribió a una tubería (sin desplazamientos de datos) y restaura completo en orden con `--single-transaction` | Ida y vuelta (tarea 7) | Descifrar a un archivo en un `tmpfs` de memoria del contenedor, con modo `0600`, borrado por `trap`; nunca en un disco persistente |
| P-3 Orden de las secciones | Datos (`COPY`) antes de `ENABLE ROW LEVEL SECURITY`, políticas, disparadores, claves foráneas e índices, que `pg_dump` emite en la sección posterior a los datos; `FORCE` sin `ENABLE` no activa nada; por eso cargar como `confia_owner` funciona y el disparador de encadenamiento no se reejecuta | Ida y vuelta: la cadena verifica íntegra después de restaurar (AT39) | Restaurar en tres pasadas (`--section=pre-data`, `data` con `SET session_replication_role = replica` como superusuario del destino desechable, `post-data`) |
| P-4 Flyway de solo lectura | `Flyway.validateWithResult()` con `classpath:db/migration` solo lee `flyway_schema_history`, sin bloqueo consultivo ni escritura, y funciona como `confia_backup` | `RestoreVerificationIT` (tarea 3) | Comparación propia: `MigrationInfoService.all()` del mismo artefacto contra las filas del historial (versión, suma, éxito) |
| P-5 MinIO | La imagen fijada de MinIO sigue descargable; admite crear el bucket con bloqueo de objeto, `PutObjectRetention` en modo `COMPLIANCE` y rechaza borrar una versión bloqueada incluso al usuario raíz; trae `mc` para crear un usuario con política sin `s3:DeleteObject` | Ida y vuelta (tarea 7), con `MinIOContainer` de Testcontainers | Fijar la última etiqueta publicada de `minio/minio`; si no hay `mc` en la imagen, crear el usuario con la API de administración desde el contenedor de herramientas; si nada de eso es posible, BR20 y BR21 se prueban solo por bloqueo y la credencial limitada queda en `[PROVEEDOR]` con nota en la especificación |
| P-6 AWS CLI contra B2 | Con `AWS_REQUEST_CHECKSUM_CALCULATION=when_required` y `AWS_RESPONSE_CHECKSUM_VALIDATION=when_required`, `aws s3api put-object` con `--object-lock-mode COMPLIANCE`, `--object-lock-retain-until-date` y `--content-md5` es aceptado por B2 y por MinIO | Bucket de pruebas B2 con retención de un día (tarea 12) y MinIO (tarea 7) | `--checksum-algorithm SHA256` si B2 lo acepta; si ninguno, `put-object` sin bloqueo en la cabecera y `put-object-retention` inmediato, más la retención por defecto del bucket como red |
| P-7 Capacidades de B2 | La llave de aplicación con `listBuckets`, `listFiles`, `writeFiles`, `readFileRetentions`, `writeFileRetentions` y sin `deleteFiles` no puede borrar una versión; un `DeleteObject` sin versión, si se acepta, solo crea un marcador de ocultación y la versión bloqueada sigue descargable; el ciclo de vida por prefijo (`daysFromUploadingToHiding`, `daysFromHidingToDeleting`) se configura con la CLI oficial `b2` y no elimina versiones bajo retención | Tarea 12 con el bucket de pruebas | Ajustar los nombres en `configure-bucket.sh`; si la ocultación con esa llave fuese posible y molesta, se documenta como aceptada (no destruye la versión) |
| P-8 Zona horaria en `OnCalendar` | `OnCalendar=*-*-* 01:00:00 America/Tegucigalpa` es válido en el `systemd` de la imagen de EC2 de 11d | `systemd-analyze calendar` en la instancia (tarea 13) | `OnCalendar=*-*-* 07:00:00 UTC` (Honduras no tiene horario de verano) |
| P-9 Credenciales de instancia desde un contenedor | El contenedor de herramientas obtiene el rol de la instancia por IMDSv2 | `rds-backup-status.sh` en preproducción (tarea 13) | 11d fija el límite de saltos de IMDSv2 en 2, o la unidad usa `--network host` solo para ese servicio |
| P-10 Secretos por archivo en Spring | `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/` convierte el archivo `confia.crypto.column-master-key` en la propiedad del mismo nombre | `RecoveryProfileIT` (tarea 4) | Variables de entorno desde un archivo `--env-file` en un directorio temporal `0700`, borrado por `trap` |
| P-11 ACL de RDS en el volcado | Un volcado de RDS no contiene `GRANT` ni `ALTER ... OWNER` hacia roles propios de RDS (`rds_superuser`, `rdsadmin`) que no existirán en el destino | Primer volcado real de preproducción restaurado en local (tarea 13, antes del simulacro) | `restore.sh` descifra a `tmpfs`, genera la lista con `pg_restore -l`, retira las entradas de ACL que nombran roles fuera de los cinco de CONFIA y restaura con `-L` |
| P-12 Paquetes de la imagen de herramientas | `postgres:18-alpine` (Alpine reciente) ofrece en sus repositorios `age` 1.x y `aws-cli` v2 | Construcción de la imagen (tarea 5) | Binarios publicados de `age` y de AWS CLI v2 descargados con suma sha256 fija en el Dockerfile |
| P-13 `promtool` | La imagen `prom/prometheus:<vX.Y.Z>` trae `promtool` con `check rules` y `test rules` | Trabajo de CI (tarea 11) | Binario de la versión publicada con suma fija |
| P-14 Lectura de catálogo | `pg_policy`, `pg_class` y `pg_roles` son legibles por `confia_backup` | Guardia de cobertura en la ida y vuelta (tarea 7) | Ninguna necesaria: son catálogos de lectura pública en PostgreSQL |

## 3. Decisiones de arquitectura

### Decisión 1 — Acceso de lectura de `confia_backup`: política por tabla en migración, prueba de cobertura y guardia en tiempo de respaldo

**Elección.**

1. **Una migración nueva** (`V<n>__grant_backup_read_policies.sql`, el siguiente número libre al aplicar; hoy sería
   `V15`) crea en cada una de las trece tablas con seguridad de fila:

   ```sql
   CREATE POLICY <table>_backup_read ON <table>
       AS PERMISSIVE FOR SELECT TO confia_backup USING (true);
   ```

   Convención fija: nombre `<table>_backup_read`, `PERMISSIVE`, `FOR SELECT`, rol único `confia_backup`, expresión
   exactamente `true`, sin `WITH CHECK` (no aplica a `SELECT`). La migración no toca ningún `GRANT`: el `SELECT` ya lo
   da `pg_read_all_data` y `confia_backup` no tiene ni gana privilegios de escritura (BI141).
2. **Cada migración futura que cree una tabla con seguridad de fila** añade su política `<table>_backup_read` en la
   misma migración. Se registra en el ADR-0029, en la nota de `docs/03` §6.1 y en la convención del agente de base de
   datos (`.claude/agents/confia-database.md`).
3. **Prueba de cobertura** `BackupReadPolicyCoverageIT` (paquete `com.confia.schema`), sobre la base migrada:

   ```sql
   select c.relname
     from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relkind in ('r', 'p') and c.relrowsecurity
      and not exists (
            select 1 from pg_policy p
             where p.polrelid = c.oid and p.polpermissive and p.polcmd in ('r', '*')
               and p.polroles = array[(select oid from pg_roles where rolname = 'confia_backup')]
               and pg_get_expr(p.polqual, p.polrelid) = 'true')
   ```

   Debe devolver cero filas. Enumera por **catálogo**, no por una lista escrita a mano, así que una tabla nueva con
   `ENABLE ROW LEVEL SECURITY` y sin la política rompe la construcción nombrándola (BI142, BI143). Un fixture crea,
   dentro de una transacción revertida, una tabla con seguridad de fila sin política y comprueba que la consulta la
   devuelve (la prueba sabe fallar). Se complementa con:
   - `confia_backup` con `row_security = on` ve todas las filas de dos instituciones sin `app.institution_id`, y con
     `row_security = off` recibe error, no cero filas (BI145);
   - `confia_admin_app`, `confia_portal_app`, `confia_readonly` y `confia_owner` siguen viendo cero filas sin
     contexto (BI144: la política tiene `TO confia_backup`, así que nunca entra en su evaluación);
   - `shared_audit_log` sigue rechazando `UPDATE`, `DELETE` y `TRUNCATE` (BI146; los disparadores de V2 no cambian);
   - las sumas de V1 a V14 no cambian (BI147) y una migración de retirada de prueba
     (`src/test/resources/db/backup-withdrawal/`, aplicada por `StepwiseDatabase`) deja el esquema sin políticas de
     respaldo (BI148).
4. **`MultiTenantSchemaIT`** cambia «una política» por «exactamente una política de aislamiento con rol `public` y
   exactamente una `<table>_backup_read`» en las comprobaciones de `:321` y `:346`; la regla general de `:97-110` no
   cambia.
5. **Guardia en tiempo de respaldo.** `backup.sh` ejecuta la misma consulta como `confia_backup` antes de volcar; si
   devuelve alguna tabla, termina con el código `3`, nombra las tablas y no sube nada (BR03). Así el caso «volcado vacío
   sin error» queda bloqueado dos veces: en la construcción y en producción, aunque alguien aplique una migración a
   mano.
6. **Interacción con `FORCE` y con el flujo de migración.** `FORCE` solo obliga al dueño de la tabla
   (`confia_owner`), que no es `confia_backup`; la política nueva no lo afecta en ninguna dirección. La migración la
   aplica `confia_owner` como cualquier otra; el rol `confia_backup` existe antes de Flyway en las tres rutas (script
   de pruebas, script de generación de código y, en 11a/11d, los scripts de roles de cada entorno). La invariante de
   `docs/03` §6.1 se conserva literal: ningún rol tiene `BYPASSRLS`.

**Alternativas descartadas.**

- *`BYPASSRLS` para `confia_backup` (opción A de D2):* rechazada por el propietario; anula todas las políticas para
  ese rol y rompe una invariante probada.
- *Políticas en el script de roles en lugar de en una migración:* los scripts de roles corren antes de que existan las
  tablas y no son la fuente del esquema; además el script de RDS es de 11d. Las políticas son esquema y viajan con
  Flyway, igual que los `GRANT` (decisión 1 del cambio de cableado de jOOQ).
- *Ampliar cada política de aislamiento con `OR current_user = 'confia_backup'`:* exige reemplazar las trece
  políticas existentes, mezcla dos propósitos en una expresión y hace que la prueba de cobertura tenga que analizar
  expresiones arbitrarias.
- *Disparador de eventos que cree la política al crear la tabla:* requiere privilegios de superusuario (no garantizados
  en RDS), es implícito y no se revisa en la migración.
- *Lista fija de tablas en la prueba:* no detecta la tabla nueva, que es justamente el riesgo.

**Justificación.** Es el mecanismo explícito, auditable por tabla y probado por catálogo que pidió el propietario; su
efecto se limita a lectura de un rol que no escribe.

### Decisión 2 — Herramientas en una imagen fijada y scripts en `infra/scripts/`

**Elección.**

- `infra/docker/backup-tools.Dockerfile`: `FROM postgres:18-alpine` con la cadena literal igual a
  `confia.postgres.image` (fuente única; la política de 11a, `infra/ci/check-container-files.sh`, gana esa
  comprobación); `apk add --no-cache age aws-cli coreutils` (P-12); usuario `10002:10002` (`confia-backup`) sin
  shell de inicio; scripts copiados a `/opt/confia/bin/` con modo `0555`; `WORKDIR /work`; sin `ENTRYPOINT` de
  servidor (el `ENTRYPOINT` de la imagen de PostgreSQL se reemplaza por `["/bin/bash"]` con `CMD` vacío, para que nunca
  arranque un servidor). Ningún secreto en `ENV` ni en argumentos de construcción. Hadolint la cubre por el trabajo
  `containers` de 11a (que ya recorre `infra/docker/*.Dockerfile`).
- Scripts del núcleo, independientes del entorno (necesitan las herramientas en `PATH` y comprueban al inicio que
  `pg_dump --version` es de la versión mayor 18; si no, código `2`):
  - `infra/scripts/backup.sh` — respaldo (decisión 3);
  - `infra/scripts/restore.sh` — restauración (decisión 7);
  - `infra/scripts/sql/restore-target-roles.sql` — roles del destino limpio (decisión 7.2);
  - `infra/scripts/lib/common.sh` — registro sin secretos, códigos de salida, publicación atómica de métricas.
- Envolturas del anfitrión:
  - `infra/scripts/backup-tools.sh <script> [args]` — `docker run --rm` de la imagen de herramientas con
    `--read-only`, `--tmpfs /work:rw,noexec,nosuid,size=…`, `--cap-drop ALL`, `--security-opt no-new-privileges`,
    `--user 10002:10002`, el archivo de entorno del servicio y el directorio de métricas montado. La usan la unidad de
    `systemd` y el `deploy.sh` de 11d (`backup-tools.sh backup.sh pre-migration`).
  - `infra/scripts/restore-drill.sh` — conductor del simulacro en la estación del operador (decisión 13).
  - `infra/scripts/record-restore-drill.sh` y `infra/scripts/rds-backup-status.sh` — métricas (decisión 11).
- La imagen se construye en la ida y vuelta con `ImageFromDockerfile` (12a). Su publicación por dígest en GHCR la
  añade el flujo de 11c como una entrada más; la unidad de EC2 y el simulacro la usan por dígest (12b).

**Alternativas descartadas.**

- *Paquetes instalados en el anfitrión EC2* (cliente de PostgreSQL 18 de PGDG, `age`, AWS CLI): permitiría ejecutar
  como un usuario sin privilegios con el aislamiento de `systemd`, pero la versión probada en CI y la de producción
  divergirían, y la estación Windows del desarrollador no podría ejecutar la ida y vuelta. Queda como respaldo si 11c
  no puede publicar la imagen.
- *Usar la imagen de la API para respaldar:* mezclaría herramientas de infraestructura con el artefacto de aplicación
  y añadiría `pg_dump` y `age` a la imagen que expone HTTP.
- *`rclone` o `mc` como cliente S3:* `rclone` no ofrece con la misma claridad las cabeceras de bloqueo por objeto; la
  distribución binaria de `mc` cambió en 2025 y su licencia es AGPL. AWS CLI v2 es el cliente de referencia de la API
  de S3 que B2 implementa.

**Justificación.** Una sola imagen fija versiones, corre igual en CI, en EC2 y en el simulacro, y hace la ida y vuelta
ejecutable en cualquier máquina con Docker.

### Decisión 3 — `backup.sh`: modos, etiquetas, flujo y códigos de salida

**Elección.**

```
backup.sh nightly               # timer; label derived from the date
backup.sh pre-migration         # deploy.sh before applying a migration in production
backup.sh print-label [--date YYYY-MM-DD]   # pure function, used by tests (BR14)
```

1. **Configuración** (solo variables de entorno; ninguna en argumentos): `BACKUP_DATABASE_URL` **sin contraseña**
   (si contiene contraseña en la parte de usuario, código `2`), `BACKUP_DATABASE_PASSWORD_FILE`,
   `BACKUP_ENCRYPTION_PUBLIC_KEY` (uno o varios destinatarios `age1…` separados por comas),
   `OFFSITE_S3_ENDPOINT`, `OFFSITE_S3_BUCKET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION` y,
   opcional, `CONFIA_METRICS_TEXTFILE_DIR`. La contraseña de la base se entrega a `libpq` con un `PGPASSFILE`
   temporal `0600`, nunca en la URL ni en la línea de órdenes (visible en `/proc/<pid>/cmdline`).
2. **Etiqueta** con `TZ=America/Tegucigalpa`: día 1 del mes → `monthly`; si no, domingo → `weekly`; si no, `daily`.
   `pre-migration` usa su propio prefijo. Cualquier otro modo, código `2` (BR16). La marca de tiempo del objeto es UTC
   (`%Y%m%dT%H%M%SZ`).
3. **Flujo** (con `set -Eeuo pipefail`, `umask 077`, directorio de trabajo `mktemp -d` dentro de `/work` y `trap` que
   lo borra en cualquier salida):
   1. comprobación de herramientas y configuración (`2`);
   2. guardia de cobertura (decisión 1.5) como `confia_backup` (`3`);
   3. lectura, como `confia_backup`, de la versión del servidor y de la última versión exitosa de
      `flyway_schema_history` para el manifiesto;
   4. `pg_dump --format=custom --enable-row-security --no-password --dbname="$URL_SIN_CONTRASEÑA" | age
      --encrypt -r <dest1> [-r <dest2>] --output "$WORK/<nombre>.dump.age"`; con `PIPESTATUS` se distingue el fallo
      de `pg_dump` (`4`) del de `age` (`5`), de modo que un fallo dentro de la tubería nunca se enmascara (BR27);
   5. `sha256sum` del objeto cifrado y manifiesto (decisión 5);
   6. subida en orden: objeto, `.sha256`, `.manifest` (el manifiesto es la marca de «juego completo»), cada uno con
      `aws s3api put-object --object-lock-mode COMPLIANCE --object-lock-retain-until-date <fecha> --content-md5 <md5>`
      (P-6); al final `head-object` del objeto comprueba tamaño y retención (`6`);
   7. solo en `nightly` y solo tras el paso 6: métricas (decisión 11.2); un fallo al escribirlas deja el respaldo válido
      y termina con `7`.
4. **Registro.** Solo mensajes fijos en inglés con la etapa, la etiqueta, la clave del objeto, el tamaño y la
   duración. Nunca `set -x`, nunca la URL completa, nunca variables de entorno ni la salida de `env`. Los errores de
   `pg_dump` y AWS CLI se dejan pasar porque no contienen contraseñas (la contraseña no está en la URL ni en argumentos);
   la ida y vuelta lo comprueba buscando la contraseña, la llave secreta de pruebas y la KEK de pruebas en toda la
   salida (BR11, BR12).
5. **Sin copia local.** El único archivo persistente durante la ejecución es el objeto cifrado en `/work`, un `tmpfs`
   del contenedor; el `trap` lo borra y el contenedor `--rm` desaparece (BR09, BR10).

**Alternativas descartadas.** Subida en flujo sin archivo intermedio (S3 exige longitud o carga multiparte, y la
cabecera `Content-MD5` exige el contenido completo; el archivo cifrado en `tmpfs` no expone nada). Volcado a archivo y
cifrado posterior, como en el borrador de `docs/05:1529-1536` (deja el texto plano en disco). Etiqueta decidida por el
temporizador (tres unidades distintas y la misma lógica de fechas repartida; la función pura es más fácil de probar).
Conteos de filas en el manifiesto mediante una instantánea exportada (`pg_export_snapshot`) sostenida por un coproceso
de `psql`: frágil en shell y no añade nada que la guardia de cobertura y la ida y vuelta no prueben ya.

### Decisión 4 — `age`: llaves, formato, destinatarios, rotación y custodia

**Elección.**

- Formato `age` v1 binario (sin `--armor`), destinatarios X25519 (`age1…`). La identidad privada
  (`AGE-SECRET-KEY-1…`) se genera con `age-keygen` en una máquina del propietario, nunca en EC2 ni en CI. El
  destinatario público va a la configuración del servicio en EC2 (no es secreto) y a la tabla de llaves del runbook
  con su huella.
- **Custodia (D4):** dos copias de la identidad privada y de la KEK, ambas bajo el propietario: entrada en un gestor
  de secretos personal fuera de AWS y copia física sellada en la institución. El desarrollador las recibe solo durante
  un simulacro o un incidente, en archivos temporales que borra al terminar; la entrega y la devolución quedan en el
  acta (BR61). La comprobación mensual de acceso (BR60) se registra en la tabla de comprobaciones del runbook.
- **Varios destinatarios** por la misma variable (`BACKUP_ENCRYPTION_PUBLIC_KEY=age1nuevo,age1anterior`). Rotación:
  (1) generar la llave nueva; (2) cifrar a ambas; (3) restaurar y verificar un respaldo con **solo** la llave nueva
  (BR62); (4) retirar el destinatario anterior de la configuración; (5) conservar la identidad anterior hasta que
  venza el último objeto cifrado solo con ella (hasta 366 días por la etiqueta mensual). Anual o ante sospecha de
  compromiso (`docs/03:1191`).
- La KEK **no** viaja en el respaldo ni se cifra con age dentro del bucket: son dos llaves independientes y la pérdida
  de una no compromete la otra.

**Alternativas descartadas.** Guardar la KEK cifrada con age junto al respaldo (una sola llave bastaría para todo:
simplifica la custodia pero une las dos capas que ADR-0023 separa). Clave simétrica con contraseña (`age -p`) para el
respaldo (la contraseña tendría que estar en el servidor). GPG (más superficie y formatos de llave más difíciles de
custodiar; `docs/05` ya fija age).

### Decisión 5 — Suma sha256 y manifiesto

**Elección.** Por cada respaldo, tres objetos bajo `<label>/<ts>/`:

| Objeto | Contenido |
|---|---|
| `confia-<label>-<ts>.dump.age` | Volcado cifrado |
| `confia-<label>-<ts>.dump.age.sha256` | Una línea en formato de `sha256sum` (`<hex>␠␠<nombre>`), verificable con `sha256sum -c` |
| `confia-<label>-<ts>.manifest` | Líneas `clave=valor` sin datos personales (decisión 5, contrato en la sección 5) |

La suma se calcula sobre el objeto **cifrado**: se verifica antes de descifrar y detecta corrupción de transporte o
almacenamiento sin necesitar la llave (BR29). `age` añade autenticación por bloques, así que una alteración que pasara
la suma tampoco descifraría. `--content-md5` en la subida hace que el proveedor rechace un cuerpo alterado en tránsito
(BR08). El manifiesto se sube el último: `restore.sh` solo considera completos los juegos con manifiesto.

**Alternativas descartadas.** Suma del texto plano (obligaría a descifrar para comprobarla). Manifiesto en JSON (sin
`jq` en la imagen; `clave=valor` se genera y se lee con `printf` y `grep` sin dependencias). Un único archivo de suma y
metadatos (rompería `sha256sum -c --strict`).

### Decisión 6 — B2: cliente, bloqueo por objeto, retención por etiqueta, ciclo de vida y llaves

**Elección.**

1. **Bucket** `confia-backups-<entorno>` creado con bloqueo de archivos habilitado y versionado (B2 versiona siempre),
   retención por defecto del bucket en `COMPLIANCE` de 7 días como red de seguridad, y cifrado del lado del servidor
   SSE-B2 (`docs/03:1134-1135`).
2. **Retención por objeto y ciclo de vida por prefijo**, con la retención del bloqueo siempre menor que la ocultación:

   | Etiqueta | Retención `COMPLIANCE` del objeto | Ciclo de vida: días hasta ocultar | Días de oculto a borrado | Copias vivas aproximadas |
   |---|---|---|---|---|
   | `daily/` | 7 días | 8 | 1 | 7 |
   | `weekly/` | 28 días | 29 | 1 | 4 |
   | `monthly/` | 365 días | 366 | 1 | 12 |
   | `pre-migration/` | 30 días | 31 | 1 | según despliegues |

   La tabla vive **una sola vez** en `infra/backup/retention.conf` (`label days`), que leen `backup.sh` (fecha de
   retención) y `configure-bucket.sh` (reglas del ciclo de vida). Una prueba estructural comprueba que para cada
   etiqueta `ocultar = retención + 1` y que los prefijos de las reglas son exactamente los cuatro (BR17, BR23). El
   plazo de `pre-migration` (30 días) es supuesto de este diseño: cubre el ciclo de reversión de un despliegue y llega
   al siguiente respaldo mensual; se ajusta en el archivo si el propietario lo pide.
3. **Llaves de aplicación**, restringidas al bucket:
   - escritura (EC2): `listBuckets`, `listFiles`, `writeFiles`, `readFileRetentions`, `writeFileRetentions`; **sin**
     `deleteFiles`, `readFiles`, `bypassGovernance` ni `writeBucketRetentions`;
   - lectura (simulacro, en custodia del propietario): `listBuckets`, `listFiles`, `readFiles`;
   - la llave maestra de la cuenta solo la usa el propietario para `configure-bucket.sh`; nunca llega a EC2, a CI ni al
     repositorio.

   «Sobrescribir» en un bucket versionado no destruye: una subida con el mismo nombre crea otra versión. La prueba de
   no sobrescritura (BR21, BR22) comprueba que, tras el intento, la versión bloqueada original sigue listada, se
   descarga y su sha256 coincide. La prueba de no borrado intenta `DeleteObject` con identificador de versión (debe
   rechazarse) y sin él (rechazo u ocultación, P-7); en ambos casos la versión sigue descargable.
4. **Configuración versionada:** `infra/backup/b2/configure-bucket.sh` (idempotente, con la CLI oficial `b2` fijada en
   versión, ejecutada por el propietario) y `infra/backup/b2/check-bucket.sh` (comprueba bloqueo, retención por
   defecto, reglas del ciclo de vida y capacidades de las llaves, y produce la evidencia para el acta). Las pruebas con
   el proveedor real usan un bucket `confia-backups-test` con retención de un día (plan de reversión de la propuesta).

**Alternativas descartadas.** OpenTofu con el proveedor de B2: las llaves de aplicación acabarían en el estado, que 11d
guarda en AWS, y el estado de 11d todavía no existe; se reconsidera cuando 11d defina su backend de estado. Modo
`GOVERNANCE` (una credencial con `bypassGovernance` podría borrar). Retención del bloqueo igual al ciclo de vida
(el borrado del ciclo de vida podría coincidir con la expiración y fallar en el borde). Copias diaria y semanal del
mismo día (duplica almacenamiento sin mejorar el punto de recuperación).

### Decisión 7 — `restore.sh`: precondiciones, secuencia, roles y dueño

**Elección.**

```
restore.sh --backup <label>/<ts> | latest
           --target-url postgresql://<superuser>@<host>:<port>/postgres   (password via RESTORE_TARGET_PASSWORD_FILE)
           --target-db confia_restore_check
           --age-identity <file>
           --verify-password-file <file>     # password for confia_backup LOGIN on the disposable target
```

1. **Secuencia** (los números entre paréntesis son los códigos de salida):
   1. argumentos y herramientas (`2`); `--backup` validado con `^(daily|weekly|monthly|pre-migration)/[0-9]{8}T[0-9]{6}Z$`
      o `latest` (último juego con manifiesto entre las cuatro etiquetas);
   2. **destino prohibido** (`10`), antes de descargar nada (BR31): el clúster destino **no** debe contener ninguno de
      los cinco roles de CONFIA (todo clúster de preproducción o producción los tiene; un destino limpio no) y la base
      `--target-db` no debe existir;
   3. descarga de los tres objetos con la llave de lectura (`11`);
   4. `sha256sum -c --strict` (`12`); si falla, no se descifra;
   5. **prueba de descifrado completa** `age --decrypt -i <id> <objeto> > /dev/null` (`13`): valida la llave y la
      autenticación de todo el objeto sin escribir texto plano y **antes** de tocar el destino (BR32);
   6. **roles** (`14`) con `restore-target-roles.sql` y variables de `psql` entrecomilladas: los cinco roles con
      `NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE`; todos `NOLOGIN` salvo `confia_backup`, que recibe `LOGIN` y
      la contraseña desechable de `--verify-password-file`; `GRANT pg_read_all_data TO confia_backup`;
      `CREATE DATABASE <target-db> OWNER confia_owner`; `ALTER SCHEMA public OWNER TO confia_owner` en la base nueva
      (BR30);
   7. `age --decrypt -i <id> <objeto> | pg_restore --dbname=<target-db> --role=confia_owner --no-owner
      --single-transaction --exit-on-error` (`15`), con `PIPESTATUS` para separar el fallo de `age` (`13`) del de
      `pg_restore` (`15`);
   8. resumen: juego restaurado, manifiesto y tiempos por etapa; nunca rutas de llaves ni contraseñas.
2. **Roles antes de `pg_restore`** porque las políticas (`TO confia_backup`), los `GRANT` y la pertenencia a
   `pg_read_all_data` nombran roles que `pg_dump` no vuelca. El script de roles es una cuarta copia de la definición
   (pruebas, generación de código, local de 11a, RDS de 11d); `RoleScriptsConsistencyTest` comprueba que los cuatro
   que viven en el repositorio declaran los mismos cinco nombres con `NOSUPERUSER NOBYPASSRLS`.
3. **`--no-owner` con `--role=confia_owner`.** La sesión del superusuario del destino desechable hace `SET ROLE
   confia_owner`; todo objeto queda a su nombre, que es el dueño original. Así las funciones `SECURITY DEFINER` (el
   disparador de la cadena y las purgas) siguen ejecutándose como `confia_owner`, las migraciones futuras corren como
   el mismo dueño y no hace falta conservar los `ALTER ... OWNER` del volcado. Los `GRANT` se restauran (no se usa
   `--no-privileges`). La carga de datos precede a `ENABLE ROW LEVEL SECURITY` y a los disparadores (P-3).
4. **`--single-transaction`**: un fallo en cualquier punto revierte toda la restauración; el destino queda con los
   roles y la base vacía, y se recrea desde cero.
5. **Nunca producción.** La regla del runbook se vuelve código en el paso 1.2; la ruta A (RDS) no usa `restore.sh`.

**Alternativas descartadas.** Restaurar como superusuario conservando dueños (en un destino gestionado no hay
superusuario y los `ALTER OWNER` exigen pertenencia a cada rol). Restaurar como el superusuario con `--no-owner` y sin
`--role` (todo quedaría a nombre del superusuario, incluidas las funciones `SECURITY DEFINER`, que pasarían a
ejecutarse con superusuario). Delegar los roles al script de inicialización de 11a (solo corre con un volumen nuevo
dentro de la imagen oficial y crea `confia_backup` sin `LOGIN`; la restauración debe funcionar contra cualquier
PostgreSQL 18 limpio, incluido el de otro proveedor en el simulacro de salida). `pg_restore --clean` sobre un destino
existente (contrario a la regla de destino limpio).

### Decisión 8 — Verificación de la restauración: perfil `recovery`, cuatro comprobaciones y pendientes por fase

**8.1 Dónde vive.** Paquete nuevo `com.confia.shared.recovery`, sin segmento de capa, con el mismo criterio que
`com.confia.shared.audit` (su `package-info.java` lo explica): puertos y casos de uso de recuperación del módulo
`shared`, que es dueño de `shared_audit_*`, `shared_data_encryption_key` y la tabla del canario. Los adaptadores jOOQ en
`com.confia.shared.infrastructure` y el cableado en `com.confia.shared.infrastructure.wiring.RecoveryConfiguration`.
No lee ninguna tabla de otro módulo.

**8.2 Las cuatro comprobaciones** (`RestoreVerification.run()` devuelve un `RestoreVerificationReport` con una
entrada por comprobación; todas se ejecutan aunque una falle):

| # | Comprobación | Cómo | Falla si |
|---|---|---|---|
| 1 | Historial de Flyway | `Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().validateWithResult()` (P-4) | Hay migraciones pendientes, faltantes, fallidas o con suma distinta respecto del artefacto (BR36) |
| 2 | Tablas y conteos | Conjunto esperado = tablas del catálogo generado de jOOQ (`Public.PUBLIC.getTables()`, construido desde las mismas migraciones); conjunto real = `information_schema.tables` de `public`; `selectCount()` tipado por tabla en `executeReadOnly` | Falta o sobra una tabla; un conteo no puede obtenerse. Los conteos se informan (BR36, acta) |
| 3 | Cadena de auditoría | Instituciones = `distinct institution_id` de `shared_audit_chain_head` y `shared_audit_log`; `DefaultAuditChainVerifier.verifyChainOf` por institución desde el génesis | Alguna devuelve `Diverged`, informando institución, primer id divergente y tipo (BR34, AT39, AT40); `Empty` no falla (AT42) |
| 4 | Canario y KEK | Toda DEK de `shared_data_encryption_key` se desenvuelve con la KEK; toda fila de `shared_restore_canary` se descifra con `ColumnEncryptionService.decrypt` y su texto plano es igual a la constante; cada institución con DEK o con cadena tiene al menos un canario; el conjunto no está vacío | KEK equivocada, ningún canario, institución sin canario o texto distinto (BR35, BR37, BR40) |

Las comprobaciones de fases futuras viven en un **registro de pendientes** (`FutureRestoreChecks`), cada una con su
nombre, las tablas que la activan y la fase de `docs/09` que las crea (libro mayor cuadrado, correlativos fiscales
contra rangos CAI, último pago). Si ninguna de sus tablas existe, se informa «pending (phase X)» y no cuenta como
aprobada (BR38). Si alguna existe y la comprobación no tiene implementación, **falla** como «required but not
implemented» (BR39): obliga a la fase que crea la tabla a implementarla.

**8.3 Rol y efectos.** La verificación se conecta como `confia_backup`: ve todas las instituciones por la política de
respaldo, no tiene privilegios de escritura y por lo tanto **no puede** modificar la base restaurada (AT41). Usa
`executeReadOnly` siempre que no reutiliza un componente existente; el verificador de la cadena usa su propia
transacción de lectura. No escribe asiento de auditoría: la base restaurada es evidencia y el resultado va al acta.

**8.4 Perfil de proceso.** `AppProfile.RECOVERY` (`APP_PROFILE=recovery`), con el primer argumento como orden:

- `verify-restore` — ejecuta 8.2 y termina con `0` (todas pasan), `3` (una o más fallan) o `1` (no pudo ejecutarse:
  conexión, configuración, KEK inválida al construir).
- `seed-canary --institution <uuid> [--institution <uuid> …]` — decisión 9; `0` o `1`.

Punto de entrada `com.confia.bootstrap.recovery.RecoveryApplication`: `@SpringBootConfiguration` con
`@ImportAutoConfiguration` de exactamente `DataSource`, transacciones de JDBC y jOOQ, sin `@EnableAutoConfiguration`,
más `@Import(RecoveryConfiguration.class)`; `web(NONE)`, `keep-alive: false`, Flyway desactivado (el validador se
construye a mano), sin `Scheduler`, sin cadena de seguridad, sin salud ni `MeterRegistry`; los mismos
inicializadores que los demás procesos (`SensitiveLogGuardInitializer`,
`SigningKeyPlacementGuard.forNonAdministrativeProcess()`). Mismo patrón que `MigrateApplication` de 11a (decisión 2
de 11a; ADR-0024). `ProcessBeanPolicy` gana la entrada `RECOVERY`; `BootstrapEntryPointRulesTest` acepta el subpaquete.

**8.5 Secretos.** La KEK y la contraseña de `confia_backup` se montan como archivos de solo lectura en
`/run/secrets/` con los nombres de propiedad (`confia.crypto.column-master-key`, `spring.datasource.password`) y
`SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/` (P-10). La salida enumera comprobaciones, conteos,
instituciones e identificadores de fila, nunca textos planos ni llaves; el texto plano del canario se compara y se
descarta.

**Alternativas descartadas.** Verificación en SQL dentro de `restore.sh`, como en el runbook actual (no recalcula
`row_hash`, que exige la serialización canónica en Java, ni descifra). Clase `main` propia sin Spring (duplicaría el
cableado de jOOQ y de transacciones y se saltaría la guardia de registros sensibles). Ejecutar la verificación como
`confia_admin_app` (tiene escritura y está sujeta al aislamiento por institución, que obligaría a recorrer
instituciones conocidas de antemano). Leer `organization_institution` para la lista de instituciones (cruza el límite
de módulo; las tablas de `shared` bastan).

### Decisión 9 — Fila canario cifrada

**Elección.**

- Migración `V<n+1>__create_shared_restore_canary.sql`: tabla `shared_restore_canary (institution_id UUID NOT NULL,
  id UUID NOT NULL, dek_id UUID NOT NULL, encrypted_value TEXT NOT NULL CHECK (encrypted_value LIKE 'v1:%'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())`, clave primaria `(institution_id, id)`, único
  `(institution_id, dek_id)`; `ENABLE` y `FORCE ROW LEVEL SECURITY`, política de aislamiento como V1 y política
  `shared_restore_canary_backup_read`; `GRANT SELECT, INSERT` a `confia_admin_app`, `SELECT` a `confia_readonly`,
  nada a `confia_portal_app`; disparadores de solo inserción con `shared_audit_is_append_only()` de V2. Pasa las
  puertas genéricas de `MultiTenantSchemaIT` sin excepción.
- Texto plano fijo y sintético `CONFIA-RESTORE-CANARY-v1` (constante en `RestoreCanary`), sin datos de personas
  (BR41). Se cifra con `ColumnEncryptionService.encryptForNewValue("shared_restore_canary", "encrypted_value",
  institución, id, …)`, de modo que el descifrado prueba la KEK, la DEK, el formato y los datos adicionales
  autenticados.
- Siembra: `APP_PROFILE=recovery seed-canary --institution …`, como `confia_admin_app` con la KEK, en una transacción
  por institución con su contexto: obtiene la DEK activa (o la crea, comportamiento normal de
  `findActiveOrCreate`), inserta con `ON CONFLICT (institution_id, dek_id) DO NOTHING` y escribe el asiento
  `shared.recovery.canary_seeded` (regla 14). Idempotente. Se ejecuta una vez por entorno tras `migrate` (paso del
  runbook y de la instalación de 12b) y de nuevo tras una rotación de DEK.

**Alternativas descartadas.** Trabajo recurrente del trabajador (exigiría entregar la KEK a `confia-worker`, que hoy
no la tiene). Descifrar los secretos TOTP de `identity_mfa_totp_credential` (tabla de otro módulo, dato real de una
persona y sin texto plano conocido). Solo desenvolver las DEK (no ejercita el formato de columna ni los datos
adicionales autenticados; se conserva como parte de la comprobación 4, no como sustituto).

### Decisión 10 — Prueba de ida y vuelta local con Testcontainers

**Elección.** `BackupRoundTripIT` (Failsafe, paquete `com.confia.recovery` de pruebas), en el trabajo `backend`
existente, sobre una red de Docker propia:

```
red de prueba
├─ pg-source   postgres:18-alpine + create-test-roles.sql; Flyway como confia_owner; datos sembrados
├─ pg-target   postgres:18-alpine limpio, sin roles
├─ minio       MinIOContainer fijado; bucket con bloqueo; usuario writer sin s3:DeleteObject (P-5)
└─ tools       ImageFromDockerfile(infra/docker/backup-tools.Dockerfile); execInContainer(backup.sh / restore.sh)
JVM de prueba ─► siembra (TransactionRunner + AuditLogWriter + seed-canary con KEK de prueba)
              ─► RecoveryApplication verify-restore en proceso contra pg-target como confia_backup
```

Llaves de prueba generadas en la propia prueba (`age-keygen` dentro del contenedor de herramientas; KEK con
`SecureRandom`); ningún secreto real ni en el repositorio (BI150, BR13).

| Lo que prueba | Lo que no puede probar |
|---|---|
| Volcado completo como `confia_backup` bajo seguridad de fila; conteos destino = conteos del superusuario en origen (BR01, BR02) | Comportamiento de B2: nombres de capacidades, ocultación, ciclo de vida, cabeceras de suma (P-6, P-7) |
| Guardia de cobertura con una política retirada en el origen → código `3`, sin objetos (BR03) | Volcado contra RDS: ACL propias de RDS (P-11), red y grupos de seguridad |
| Cifrado, sha256, manifiesto, subida con retención `COMPLIANCE` y fecha por etiqueta (BR04 a BR08, BR17) | Temporizador real, siete noches, métricas recolectadas y alertas disparadas |
| Borrado y sobrescritura rechazados por bloqueo y por credencial en MinIO (BR18, BR20, BR21; P-5) | Tiempo de recuperación con un volumen real y la red hacia B2 |
| Sin residuos en `/work` tras éxito y fallo; ningún secreto en la salida (BR09 a BR12) | Custodia física de llaves y firma del acta |
| Códigos de salida por clase de fallo, incluido el fallo dentro de la tubería (BR26 a BR28) | Restauración a un punto en el tiempo de RDS (ruta A) |
| Destino prohibido, hash alterado, llave age ajena (BR29, BR31, BR32) | |
| Roles, permisos y políticas del destino iguales a los del origen (comparación de catálogo con la `snapshot` de `V9SchemaDiffIT`; BR30) | |
| Verificación `0` con todo íntegro; `3` con cadena alterada, KEK ajena, migración borrada del historial o canario ausente (BR33 a BR37, BR40, AT39, AT40) | |
| Rotación de age: cifrado a dos destinatarios y restauración solo con la llave nueva (BR62) | |

Los casos negativos comparten un único respaldo válido y alteran copias, para mantener el tiempo de la prueba acotado.

**Alternativas descartadas.** Ejecutar los scripts en el anfitrión de CI (no hay cliente de PostgreSQL 18 garantizado
y la estación Windows no podría correrlos). Un trabajo de CI aparte con su propio JDK (duplica la preparación del
trabajo `backend`; la prueba ya rompe la construcción allí y se ve en el remoto, BI149). Verificar contra un S3
simulado sin bloqueo (no probaría BR18).

### Decisión 11 — `systemd`, métricas y estado de RDS

**11.1 Unidades** en `infra/systemd/`:

- `confia-backup.service`: `Type=oneshot`, `ExecStart=/srv/confia/infra/scripts/backup-tools.sh backup.sh nightly`,
  `EnvironmentFile=/etc/confia/backup.env` (`0600`, lo escribe 11d desde su gestor de secretos; contiene la URL sin
  contraseña, la ruta del archivo de contraseña, el destinatario age, el bucket, la llave de escritura de B2 y la
  referencia por dígest de la imagen de herramientas), `TimeoutStartSec=2h`, sin contraseñas en la unidad (BR46,
  BR59).
- `confia-backup.timer`: `OnCalendar=*-*-* 01:00:00 America/Tegucigalpa` (P-8), `Persistent=true`,
  `AccuracySec=1min`.
- `confia-rds-backup-status.service` y `.timer` (cada 5 minutos): `rds-backup-status.sh`.
- Instalación documentada en el runbook (copiar, `systemctl daemon-reload`, `enable --now`, `systemd-analyze
  calendar`), ejecutada por el despliegue de 11d. db-scheduler, `@Scheduled` y GitHub Actions no ejecutan respaldos
  (ADR-0016); una prueba estructural busca `backup.sh` en `.github/workflows/` y en el código Java (BR46).

**11.2 Publicación de métricas: archivos de texto para el recolector de archivos del `node_exporter`.** Cada script
escribe un archivo `*.prom` en `CONFIA_METRICS_TEXTFILE_DIR` (en EC2, `/var/lib/confia/metrics`, montado en el
contenedor) con escritura atómica (archivo temporal en el mismo directorio y `mv`):

| Archivo | Lo escribe | Series |
|---|---|---|
| `confia_backup.prom` | `backup.sh nightly`, solo tras el éxito completo (BR48, BR49); `pre-migration` no lo toca (BR50) | `confia_backup_last_success_timestamp_seconds{backup_type="dump"}`, `confia_backup_duration_seconds{backup_type="dump"}` (gauge, última ejecución) |
| `confia_restore_drill.prom` | `record-restore-drill.sh <fin_epoch> <duración_s>`, que el operador ejecuta en EC2 por Session Manager con los valores que imprime `restore-drill.sh` | `confia_restore_drill_last_success_timestamp_seconds`, `confia_restore_drill_duration_seconds` |
| `confia_rds.prom` | `rds-backup-status.sh` (AWS CLI con el rol de la instancia: `rds:DescribeDBInstances`, permiso que concede 11d) | `confia_rds_backup_enabled` (1 si `BackupRetentionPeriod > 0`), `confia_rds_latest_restorable_timestamp_seconds` |

`confia_rds_restorable_time_lag_seconds` es una **regla de registro** (`time() -
confia_rds_latest_restorable_timestamp_seconds`): si el recolector se detiene, el retraso sigue creciendo en lugar de
quedarse congelado en el último valor bueno. Sin `CONFIA_METRICS_TEXTFILE_DIR` (CI, desarrollo) no se escribe nada y se
registra una advertencia.

**11.3 Coordinación con el cambio 10, sin bloquearlo.** El cambio 12 entrega archivos, nombres y reglas; el cambio 10
configura `node_exporter --collector.textfile.directory=/var/lib/confia/metrics` y carga
`infra/prometheus/rules/*.yml`. Hasta entonces, 12a y 12b se prueban con la lectura de los archivos y con `promtool`;
solo BR51 y BR54 esperan al cambio 10.

**Alternativas descartadas.** Pushgateway (otro componente que operar para tres series). Exponer las métricas desde la
API (el respaldo no corre en la API, y db-scheduler no ejecuta respaldos). Exportador de CloudWatch para RDS (otro
proceso y otra credencial para dos valores). Publicar el retraso de RDS como gauge desde el script (se congela si el
script muere).

### Decisión 12 — Reglas de alerta como archivos validados con `promtool`

**Elección.** `infra/prometheus/rules/confia-backup.rules.yml` con un grupo de registro y uno de alertas:

| Alerta | Expresión | `for` | `severity` |
|---|---|---|---|
| `BackupMissing` | `time() - confia_backup_last_success_timestamp_seconds{backup_type="dump"} > 93600 or absent_over_time(confia_backup_last_success_timestamp_seconds{backup_type="dump"}[26h])` | — | `S1` |
| `RdsBackupDisabled` | `confia_rds_backup_enabled == 0` | — | `S1` |
| `RdsRestorableTimeLagHigh` | `confia_rds_restorable_time_lag_seconds > 900` | `10m` | `S1` |
| `RestoreDrillOverdue` | `time() - confia_restore_drill_last_success_timestamp_seconds > 3024000 or absent(confia_restore_drill_last_success_timestamp_seconds)` | — | `S3` |

Anotaciones con la acción de `docs/07` §7.2 y el enlace al runbook. Pruebas unitarias en
`infra/prometheus/rules/tests/confia-backup.rules.test.yml` (`promtool test rules`) con los casos de BR52 y BR53, más
la ausencia de series. CI: trabajo `backup-static` (decisión 14) con `promtool check rules` y `promtool test rules`
desde `prom/prometheus:<vX.Y.Z>` fijado (P-13).

**Alternativas descartadas.** Reglas dentro de la configuración de Prometheus (no existe hasta el cambio 10). Escribir
las alertas sin pruebas (`promtool test rules` es el único ejecutor rojo/verde para expresiones). Esperar al cambio 10
(bloquearía la entrega y las reglas ya tienen condición fijada en `docs/07`).

### Decisión 13 — Runbook, acta, simulacro y medición del tiempo de recuperación

**13.1 Runbook reescrito** (`docs/runbooks/restauracion-de-respaldo.md`):

- Encabezado con los objetivos por ruta: ruta A, punto de recuperación de 15 minutos; ruta B, hasta 24 horas, aceptado
  por el propietario el 2026-11-09 mientras no existan datos reales y por revisar antes del primer dato real; tiempo
  de recuperación menor a 4 horas (BR66).
- Ruta A validada contra RDS en 12b (parámetros de red y de grupo de seguridad que hoy dicen «se valida en F0»).
- Ruta B con `restore-drill.sh` / `restore.sh` y la verificación `recovery verify-restore`; las cinco verificaciones
  actuales se sustituyen por las cuatro comprobaciones de F0 y una tabla de **pasos por fase** (libro mayor,
  correlativos y CAI, pagos) con la fase que los activa (BR65).
- Paso obligatorio «reaplicar la lista de eliminaciones pendientes de propagación» (`docs/08` §6.1), marcado como
  **bloqueado y sin dueño**, con la regla explícita «no se cargan datos reales mientras este paso no exista» (BR67).
- Custodia de llaves, comprobación mensual de acceso con tabla de registro, entrega temporal registrada, rotación de
  age y regla de rotación de la KEK (BR60, BR61, BR64).
- Siembra del canario tras `migrate` en cada entorno.
- Simulacro mensual y simulacro de salida anual en un proveedor distinto de AWS (procedimiento; ejecución posterior a
  F0, BR68).

**13.2 Acta** `docs/runbooks/plantillas/acta-simulacro-de-restauracion.md`: fecha, ejecutor, entorno, juego de
respaldo (clave y sha256), imagen de API e imagen de herramientas por dígest, tabla de etapas con inicio, fin,
duración y objetivo, tiempo total contra 4 horas, resultado de las cuatro comprobaciones y de las pendientes, conteos
por tabla, registro de acceso a las llaves (entrega, uso, devolución, borrado de copias), incidencias y firma del
propietario (BR55). Las actas firmadas se archivan como `docs/operacion/actas/<fecha>-simulacro-de-restauracion.md`
con la firma transcrita y referencia al original físico custodiado por el propietario.

**13.3 Conductor del simulacro** `infra/scripts/restore-drill.sh`, en la estación del operador (Linux o WSL2 con
Docker), fuera de AWS y fuera del servidor (la llave privada nunca llega a EC2):

1. `T0` cuando el operador inicia el comando; la obtención de las llaves desde la custodia se registra a mano en el
   acta con su propia hora (etapa humana).
2. Crea una red y un `postgres:18-alpine` limpio con volumen anónimo, sin puertos publicados.
3. Ejecuta `restore.sh` en la imagen de herramientas (identidad age y contraseña desechable montadas de solo lectura
   desde un directorio `0700`), midiendo descarga, integridad, prueba de descifrado, roles y `pg_restore`.
4. Ejecuta la imagen de la API con `APP_PROFILE=recovery verify-restore` y la KEK montada (8.5).
5. Opcional en F0: arranca `api-admin` de 11a contra la base restaurada y espera `/health/ready` (etapa «aplicación
   lista»).
6. Imprime la tabla de etapas para el acta y la línea para `record-restore-drill.sh`; destruye contenedores, red,
   volumen y archivos temporales en cualquier salida.

El **tiempo de recuperación medido** es desde `T0` hasta la verificación en `0` (o hasta «aplicación lista» si se
ejecuta), más la etapa humana de las llaves registrada en el acta; debe ser menor a 4 horas (BR56). El simulacro de F0
(tarea 15) usa un respaldo nocturno real de preproducción descargado de B2; antes, la tarea 8 hace un ensayo completo
contra un respaldo local en MinIO para validar el conductor y la plantilla.

**Alternativas descartadas.** Simulacro en la instancia EC2 de preproducción (la llave privada estaría en el servidor,
contra `docs/03` §7.2). Medir solo el `pg_restore` (el objetivo es de recuperación completa, no de carga).

### Decisión 14 — Integración continua

- La ida y vuelta corre en el trabajo `backend` (Failsafe dentro de `./mvnw verify`): un fallo de cualquier etapa
  rompe la construcción y se ve en el remoto (BR42, BR43, BI149).
- Trabajo nuevo `backup-static` en `.github/workflows/ci.yml`, sin JDK, `timeout-minutes: 5`: `shellcheck` sobre
  `infra/scripts/**/*.sh` e `infra/backup/**/*.sh`; `promtool check rules` y `promtool test rules`. Las pruebas
  estructurales (unidades, acta, runbook, retención, ausencia de secretos) son pruebas Java del trabajo `backend`.
- El Dockerfile de herramientas lo cubre el Hadolint del trabajo `containers` de 11a; su `FROM` lo cubre la política de
  coherencia de versiones de 11a.

### Decisión 15 — Decisiones que suben a ADR

| ADR (número provisional; el siguiente libre al aplicar) | Decisión | Por qué es de ADR |
|---|---|---|
| **ADR-0029** «Lectura del respaldo bajo seguridad de fila» | `confia_backup` conserva `NOBYPASSRLS` y lee por una política permisiva `<table>_backup_read` por tabla, obligatoria para toda tabla con seguridad de fila, con prueba de cobertura por catálogo y guardia en tiempo de respaldo; rechazo de `BYPASSRLS` | Cambia el modelo de acceso de un rol de `docs/03` §6.1 y de ADR-0017 e impone una regla a toda migración futura |
| **ADR-0030** «Respaldo lógico fuera de AWS en Backblaze B2» | B2 con bloqueo `COMPLIANCE` por objeto, retención del bloqueo menor que el ciclo de vida por etiqueta, llave de escritura sin borrado, cifrado age con destinatarios múltiples y custodia del propietario, herramientas en una imagen fijada y verificación con el perfil `recovery` | Concreta ADR-0014 con un proveedor y un compromiso difícil de revertir (objetos bloqueados hasta 365 días) |

ADR-0014 y ADR-0017 reciben nota fechada que remite a esos ADR. El perfil `recovery` sigue ADR-0024 sin decisión nueva.

## 4. Flujos

### 4.1 Respaldo nocturno

```
systemd timer (01:00)        backup-tools.sh            backup.sh (contenedor)          PostgreSQL (RDS)        B2
   │ start service ─────────────►│ docker run --rm ────────►│
   │                             │                           │ check tools/config
   │                             │                           │ coverage query as confia_backup ──►│
   │                             │                           │◄── 0 rows (else exit 3) ───────────│
   │                             │                           │ pg_dump -Fc --enable-row-security ─►│
   │                             │                           │   | age -r … > /work/x.dump.age     │
   │                             │                           │ sha256 + manifest
   │                             │                           │ put-object (COMPLIANCE, retain-until by label) ──────►│
   │                             │                           │ put .sha256, put .manifest ─────────────────────────►│
   │                             │                           │ head-object (size, retention) ◄─────────────────────►│
   │                             │                           │ write confia_backup.prom (atomic)
   │                             │◄─ exit 0 | 2..7 ──────────│ trap: rm -rf /work/*
   │◄── unit result ─────────────│
```

### 4.2 Simulacro y restauración

```
operador        restore-drill.sh         pg-target (limpio)     restore.sh (herramientas)       B2          confia-api recovery
  │ T0 ──────────►│ net + postgres ───────►│
  │               │ run restore.sh ──────────────────────────────►│ roles exist? db exists? ──►│ (exit 10)
  │               │                        │                      │ get 3 objects ─────────────────────────►│
  │               │                        │                      │ sha256 -c (exit 12)
  │               │                        │                      │ age -d > /dev/null (exit 13)
  │               │                        │◄─ roles + db ────────│ (exit 14)
  │               │                        │◄─ age -d | pg_restore --role confia_owner --no-owner -1 (exit 15)
  │               │ run verify-restore ───────────────────────────────────────────────────────────────────────►│
  │               │                        │◄──────────── as confia_backup: Flyway, tablas, cadena, canario ───│
  │               │◄──────────────────────────────────────────────────────────────── exit 0 | 3 | 1 ──────────│
  │◄─ stage table + record line; teardown
```

## 5. Interfaces y contratos

| Contrato | Valor |
|---|---|
| Modos de `backup.sh` | `nightly`, `pre-migration`, `print-label [--date YYYY-MM-DD]`; otro → `2` |
| Códigos de `backup.sh` | `0` éxito; `2` uso o configuración; `3` cobertura de políticas; `4` `pg_dump`; `5` `age`; `6` subida o comprobación posterior; `7` métricas (respaldo válido) |
| Códigos de `restore.sh` | `0`; `2` uso o configuración; `10` destino prohibido; `11` descarga; `12` integridad; `13` descifrado; `14` roles; `15` `pg_restore` |
| Códigos de `APP_PROFILE=recovery` | `verify-restore`: `0` todas pasan, `3` alguna falla, `1` no pudo ejecutarse; `seed-canary`: `0` o `1` |
| Clave de objeto | `<label>/<YYYYMMDDTHHMMSSZ>/confia-<label>-<YYYYMMDDTHHMMSSZ>.dump.age` (+ `.sha256`, `.manifest`) |
| Retenciones | `infra/backup/retention.conf`: `daily 7`, `weekly 28`, `monthly 365`, `pre-migration 30`; ocultar a `días + 1`, borrar oculto a 1 día |
| Variables del respaldo | `BACKUP_DATABASE_URL` (sin contraseña), `BACKUP_DATABASE_PASSWORD_FILE`, `BACKUP_ENCRYPTION_PUBLIC_KEY`, `OFFSITE_S3_ENDPOINT`, `OFFSITE_S3_BUCKET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`, `CONFIA_METRICS_TEXTFILE_DIR` |
| Propiedad de la KEK | `confia.crypto.column-master-key` (archivo en `/run/secrets/`) |

Manifiesto (`format=confia-backup-manifest/v1`):

```
format=confia-backup-manifest/v1
mode=nightly
label=daily
created_at=2026-11-10T07:00:12Z
object=daily/20261110T070012Z/confia-daily-20261110T070012Z.dump.age
object_sha256=<64 hex>
object_bytes=<n>
retain_until=2026-11-17T07:00:12Z
pg_dump_version=18.x
source_server_version=18.x
source_flyway_version=<n>
age_recipients=age1…[,age1…]
```

Texto de métricas (ejemplo de `confia_backup.prom`):

```
# HELP confia_backup_last_success_timestamp_seconds End time of the last complete offsite logical backup.
# TYPE confia_backup_last_success_timestamp_seconds gauge
confia_backup_last_success_timestamp_seconds{backup_type="dump"} 1794294012
# HELP confia_backup_duration_seconds Duration of the last complete offsite logical backup.
# TYPE confia_backup_duration_seconds gauge
confia_backup_duration_seconds{backup_type="dump"} 184
```

```java
// com.confia.shared.recovery (sketch)
public final class RestoreVerification {
    public RestoreVerificationReport run();          // runs the four checks plus the future-check registry
}
public record RestoreVerificationReport(List<CheckResult> checks) {
    public boolean passed();                          // true only if every mandatory check passed
}
public record CheckResult(String name, Status status, String detail) {
    public enum Status { PASSED, FAILED, PENDING_PHASE }
}
public final class RestoreCanary {
    public static final String PLAINTEXT = "CONFIA-RESTORE-CANARY-v1";
}
```

## 6. Cambios de archivos

| Archivo | Acción | Descripción | Rebanada |
|---|---|---|---|
| `apps/api/app/src/main/resources/db/migration/V<n>__grant_backup_read_policies.sql` | Crear | Trece políticas `<table>_backup_read` | 12a |
| `apps/api/app/src/main/resources/db/migration/V<n+1>__create_shared_restore_canary.sql` | Crear | Tabla del canario, seguridad de fila, dos políticas, privilegios, solo inserción | 12a |
| `apps/api/app/src/test/java/com/confia/schema/BackupReadPolicyCoverageIT.java`, `BackupReadAccessIT.java` | Crear | Decisión 1; BI140 a BI148, BR01, BR02 | 12a |
| `apps/api/app/src/test/resources/db/backup-withdrawal/` | Crear | Migración de retirada de prueba (BI148) | 12a |
| `apps/api/app/src/test/java/com/confia/schema/MultiTenantSchemaIT.java` | Modificar | Conteo de políticas por tipo | 12a |
| `apps/api/app/src/test/java/com/confia/schema/RolePrivilegeMatrixIT.java` | Modificar | Tabla del canario en la matriz | 12a |
| `apps/api/app/src/main/java/com/confia/shared/recovery/` (`RestoreVerification`, `RestoreVerificationReport`, `CheckResult`, `FutureRestoreChecks`, `RestoreCanary`, `RestoreCanarySeeder`, puertos de lectura, `package-info.java`) | Crear | Decisiones 8 y 9 | 12a |
| `apps/api/app/src/main/java/com/confia/shared/infrastructure/` (`JooqRestoreCatalogReader`, `JooqRestoreCanaryRepository`) | Crear | Adaptadores jOOQ | 12a |
| `apps/api/app/src/main/java/com/confia/shared/infrastructure/wiring/RecoveryConfiguration.java` | Crear | Cableado del perfil | 12a |
| `apps/api/app/src/main/java/com/confia/bootstrap/recovery/RecoveryApplication.java` | Crear | Contexto cerrado | 12a |
| `apps/api/app/src/main/java/com/confia/bootstrap/AppProfile.java`, `ConfiaApplication.java` | Modificar | `RECOVERY` y su rama | 12a |
| `apps/api/app/src/main/resources/application-recovery.yml` | Crear | `keep-alive: false`, sin Flyway automático | 12a |
| `apps/api/app/src/test/java/com/confia/shared/recovery/` (`RestoreVerificationTest`, `RestoreVerificationIT`, `RestoreCanarySeederIT`) | Crear | Decisiones 8 y 9 | 12a |
| `apps/api/app/src/test/java/com/confia/bootstrap/` (`RecoveryProfileIT`, `ProcessBeanPolicy`, `ExecutableJarIT` de 11a) | Crear/Modificar | Perfil, aislamiento del contexto, salida | 12a |
| `apps/api/app/src/test/java/com/confia/architecture/BootstrapEntryPointRulesTest.java` | Modificar | Subpaquete `recovery` | 12a |
| `apps/api/app/src/test/java/com/confia/recovery/BackupRoundTripIT.java` | Crear | Decisión 10 | 12a |
| `apps/api/app/src/test/java/com/confia/architecture/BackupOperationalFilesTest.java`, `RoleScriptsConsistencyTest.java` | Crear | Estructura de unidades, acta, runbook, retención, ausencia de secretos y de programación en CI o Java; roles | 12a (unidades en 12b) |
| `apps/api/app/pom.xml` | Modificar | `testcontainers-minio` en prueba (versión del BOM) | 12a |
| `infra/docker/backup-tools.Dockerfile`, `infra/docker/backup-tools.Dockerfile.dockerignore` | Crear | Decisión 2 | 12a |
| `infra/scripts/backup.sh`, `restore.sh`, `backup-tools.sh`, `restore-drill.sh`, `record-restore-drill.sh`, `lib/common.sh`, `sql/restore-target-roles.sql` | Crear | Decisiones 3, 7, 11 y 13 | 12a |
| `infra/backup/retention.conf` | Crear | Fuente única de retenciones | 12a |
| `infra/ci/check-container-files.sh` (de 11a) | Modificar | `FROM` de `backup-tools` igual a `confia.postgres.image` | 12a |
| `.github/workflows/ci.yml` | Modificar | Trabajo `backup-static` (`shellcheck` en 12a; `promtool` en 12b) | 12a/12b |
| `docs/runbooks/restauracion-de-respaldo.md` | Reescribir | Decisión 13.1 | 12a |
| `docs/runbooks/plantillas/acta-simulacro-de-restauracion.md` | Crear | Decisión 13.2 | 12a |
| `docs/adr/ADR-0029-lectura-del-respaldo-bajo-seguridad-de-fila.md`, `docs/adr/ADR-0030-respaldo-fuera-de-aws-en-backblaze-b2.md` | Crear | Decisión 15 | 12a |
| `docs/03-seguridad.md` | Nota fechada | §6.1 (política `<table>_backup_read`, la invariante se conserva), §7.2 (custodia D4, rotación) | 12a |
| `docs/05-infraestructura-y-despliegue.md` | Nota fechada | §6 (URL sin contraseña y archivo de contraseña; variables nuevas), §11 (estructura real del script, imagen de herramientas, B2, retenciones, temporizador) | 12a |
| `docs/07-observabilidad-y-operaciones.md` | Nota fechada | Duración como gauge, regla de registro del retraso de RDS, `absent` en dos alertas, archivos de texto | 12a |
| `docs/08-datos-privacidad-y-retencion.md` | Nota fechada | §6.1: paso del runbook bloqueado y sin dueño; plazos efectivos de borrado (ocultar + 1 día) | 12a |
| `docs/09-roadmap-y-fases.md` | Nota fechada | Avance del criterio 6 (12a); cierre con referencia al acta (12b) | 12a/12b |
| `docs/adr/ADR-0014-…md`, `docs/adr/ADR-0017-…md` | Nota fechada | Remisión a ADR-0030 y ADR-0029 | 12a |
| `openspec/changes/foundations-plan/exploration.md` | Nota fechada | Estado del cambio 12 y corte | 12a |
| `.claude/agents/confia-database.md` | Modificar | Convención `<table>_backup_read` | 12a |
| `infra/systemd/confia-backup.service`, `confia-backup.timer`, `confia-rds-backup-status.service`, `confia-rds-backup-status.timer` | Crear | Decisión 11.1 | 12b |
| `infra/scripts/rds-backup-status.sh` | Crear | Decisión 11.2 | 12b |
| `infra/prometheus/rules/confia-backup.rules.yml`, `infra/prometheus/rules/tests/confia-backup.rules.test.yml` | Crear | Decisión 12 | 12b |
| `infra/backup/b2/configure-bucket.sh`, `infra/backup/b2/check-bucket.sh` | Crear | Decisión 6.4 | 12b |
| Configuración de RDS en `infra/tofu/` de 11d | Modificar | Período de retención de respaldos automáticos y ventana | 12b |
| `docs/operacion/actas/<fecha>-simulacro-de-restauracion.md` | Crear | Acta firmada del simulacro | 12b |

`.gitattributes` ya normaliza `*.sh` a LF (`.gitattributes:13-14`); no cambia.

## 7. Desviaciones respecto de la propuesta y la documentación, y precisiones para la especificación

| Tema | Propuesta, documento o especificación | Diseño | Motivo |
|---|---|---|---|
| Contraseña de la base del respaldo | URL con contraseña (`docs/05:764`) | URL sin contraseña y `BACKUP_DATABASE_PASSWORD_FILE` | La URL viaja en argumentos de `pg_dump`, visibles en la tabla de procesos |
| Tipo de `confia_backup_duration_seconds` | Histograma (`docs/07:451`) | Gauge de la última ejecución | Un archivo de texto escrito una vez por noche no forma un histograma útil |
| `confia_rds_restorable_time_lag_seconds` | Gauge recolectado | Regla de registro sobre `confia_rds_latest_restorable_timestamp_seconds` | No se congela si el recolector se detiene; el nombre de `docs/07` se conserva |
| `BackupMissing` y `RestoreDrillOverdue` | Solo antigüedad (`docs/07:637,662`) | Más `absent_over_time` / `absent` | Una serie que nunca se publicó no debe silenciar la alerta |
| Respaldo dentro de un contenedor | Script en el anfitrión (`docs/05:1497-1552`) | Script en la imagen `backup-tools`, invocado por `backup-tools.sh` | Versiones iguales en CI, EC2 y simulacro |
| Conteos frente a «últimos valores conocidos» | Runbook:160-170 | Conteos informados en el acta y comparados con el acta anterior; la completitud la garantizan la guardia de cobertura y BR01 | El respaldo no puede contar filas que su rol no ve |
| Rotación de la KEK (BR63) | La especificación exige reenvolver las DEK con una KEK nueva y probarlo | **No hay mecanismo de reenvoltura** en el código ni en la propuesta | Ver pregunta abierta 1 |
| Retención de `pre-migration` | No definida | 30 días | Supuesto de diseño (decisión 6.2) |

> **Nota (2026-11-09):** BR63 (reenvoltura de la KEK) se trasladó al cambio que cablee la KEK en producción por decisión del propietario; la fila «Rotación de la KEK (BR63)», la mención de 16 tareas en la sección 10, el riesgo de la sección 11 y la pregunta abierta 1 de la sección 12 quedan resueltos así, y el cambio se parte en 12a y 12b (ver `tasks.md`).

Precisiones para la especificación en curso: BR30 se cumple con roles creados por `restore.sh` (no por el script de
11a); BR31 usa como marca de «entorno no limpio» la existencia de cualquiera de los cinco roles en el clúster; BR50
coincide con el diseño (`pre-migration` no publica métricas); BR20 y BR21 dependen de P-5.

## 8. Estrategia de pruebas

| Capa | Qué | Cómo | Escenarios | En CI |
|---|---|---|---|---|
| Esquema | Cobertura de políticas por catálogo con fixture que falla; visibilidad por rol; inmutabilidad; sumas anteriores; retirada | `BackupReadPolicyCoverageIT`, `MultiTenantSchemaIT`, `DatabasePipelineIT` | BI140 a BI148 | Sí (`backend`) |
| Integración | `pg_dump` como `confia_backup` dentro del contenedor y conteos contra superusuario | `BackupReadAccessIT` | BR01, BR02, P-1 | Sí |
| Unidad | Cuatro comprobaciones y registro de pendientes con dobles (cadena divergente, KEK ajena, tabla ausente, historial incompleto, canario ausente, tabla futura presente) | `RestoreVerificationTest` | BR33 a BR39 | Sí |
| Integración | Verificación sobre una base migrada real como `confia_backup`; ninguna escritura posible | `RestoreVerificationIT` | AT39 a AT42, P-4 | Sí |
| Integración | Siembra idempotente del canario, asiento de auditoría, texto sintético | `RestoreCanarySeederIT` | BR40, BR41 | Sí |
| Integración | Perfil `recovery`: contexto cerrado, códigos `0`/`1`/`3`, secretos por archivo, nada sensible en la salida | `RecoveryProfileIT`, caso en `ExecutableJarIT` | P-10 | Sí |
| Sistema (contenedores) | Ida y vuelta completa y casos negativos | `BackupRoundTripIT` | BR01 a BR12, BR14 a BR18, BR20, BR21, BR26 a BR37, BR40, BR42, BR43, BR62 | Sí |
| Estático | `shellcheck`; `FROM` coherente; Hadolint | `backup-static`, `containers` de 11a | BR13 | Sí |
| Estructural | Unidades de `systemd`; acta; runbook; retenciones coherentes; ausencia de `AGE-SECRET-KEY-`, llaves de B2 y KEK en el repositorio; ausencia de programación del respaldo en Java y en GitHub Actions; roles coherentes | `BackupOperationalFilesTest`, `RoleScriptsConsistencyTest` | BR13, BR17, BR23, BR46, BR55, BR59, BR65 a BR67 | Sí |
| Reglas | Disparo y no disparo de las cuatro alertas, ausencia de series | `promtool test rules` | BR52, BR53 | Sí (12b) |
| Proveedor | Bloqueo, borrado y sobrescritura rechazados, ciclo de vida real con bucket de un día | `check-bucket.sh` y evidencia en `apply-progress` | BR19, BR22, BR24, BR25 | No |
| AWS | Respaldos de RDS, ruta A, siete noches, recolección | Evidencia en `apply-progress` y acta | BR44, BR45, BR47, BR51 | No |
| Cambio 10 | `BackupMissing` llega a su destino | Evidencia | BR54 | No |
| Humano | Simulacro, firma, custodia, accesos | Acta | BR56 a BR58, BR60, BR61, BR64, BR68 | No |
| Documental | Notas fechadas sin reescritura | `DocumentationNotesTest` existente | — | Sí |

El rojo primero aplica a: `BackupReadAccessIT` y `BackupReadPolicyCoverageIT` (fallan hoy), `RestoreVerificationTest`,
`RecoveryProfileIT`, `BackupRoundTripIT` (cada etapa del script se escribe después de su aserción) y las pruebas de
`promtool`. Las unidades de `systemd`, el runbook y el acta llevan la excepción declarada de ODD (sin ejecutor rojo/verde
semántico) y se cubren con pruebas estructurales.

## 9. Matriz de amenazas

| Frontera | Casos adversariales mínimos | Aplicabilidad | Respuesta | Pruebas rojas |
|---|---|---|---|---|
| Rutas tipo documentación | `README.sh`, Markdown ejecutable | N/A: ningún paso clasifica ni ejecuta archivos por su nombre; los scripts se invocan por ruta fija | — | — |
| Selección de repositorio Git | `git -C`, rutas relativas | N/A: ningún script invoca Git | — | — |
| Estado de commit | índice, `commit -a` | N/A | — | — |
| Estado de empuje | rama de seguimiento, refspec | N/A | — | — |
| Órdenes de PR | `--head`, prefijos | N/A | — | — |

Fronteras de proceso propias del cambio, cubiertas por pruebas de la sección 8 sin filas nuevas en la matriz:

- **Secretos en argumentos o registros:** contraseña por `PGPASSFILE`, credenciales de B2 por entorno del contenedor,
  identidad age y KEK por archivo montado de solo lectura; sin `set -x`; la ida y vuelta busca la contraseña, la
  identidad y la KEK de prueba en toda la salida capturada (BR11, BR12).
- **Inyección en claves de objeto y SQL:** la etiqueta sale de un conjunto cerrado y la marca de tiempo de `date`;
  `--backup` se valida con expresión regular; el script de roles usa variables de `psql` entrecomilladas, nunca
  concatenación (regla 12).
- **Fallos enmascarados en tuberías:** `pipefail` y `PIPESTATUS` por etapa (BR27).
- **Destino equivocado:** comprobación de roles y base antes de descargar (BR31).
- **Texto plano en disco:** tuberías y `tmpfs`; la prueba recorre `/work` tras éxito y fallo (BR09, BR10).
- **Subprocesos de prueba:** `execInContainer` con vector de argumentos fijo, sin intérprete intermedio.

## 10. Plan de entrega y secuencia

El total estimado es **15 tareas** (16 si se incluye la reenvoltura de la KEK, pregunta abierta 1). La regla de
`openspec/config.yaml` corta por encima de 15, pero **se recomienda aplicar ya el corte de la propuesta**: las tareas
10 a 15 dependen de 11d, del cambio 10, de B2 y de personas, y mantenerlas en el mismo cambio impediría archivar la
parte que sí se completa en local. El corte lo decide `sdd-tasks` con esa recomendación.

**12a `backup-tooling`** (depende solo de 11a; el PR B2 se aplica después del PR A de 11a para no chocar en el
lanzador):

| PR | Tareas | Contenido | Líneas estimadas |
|---|---|---|---|
| A `backup-read-access` | 1. RED `BackupReadAccessIT` y `BackupReadPolicyCoverageIT`; migración de políticas; `MultiTenantSchemaIT`; retirada de prueba | Decisión 1 | ~350 |
| B1 `restore-canary` | 2. Migración del canario, `RestoreCanarySeeder`, repositorio, asiento | Decisión 9 | ~350 |
| B2 `restore-verification` | 3. `RestoreVerification`, pendientes por fase, `RestoreVerificationIT`. 4. Perfil `recovery`, `RecoveryApplication`, códigos, configtree | Decisión 8 | ~650 |
| C `backup-scripts` | 5. Imagen de herramientas, `backup.sh`, `retention.conf`, `lib/common.sh`, `backup-tools.sh`, `shellcheck` en CI. 6. `restore.sh` y roles del destino, `RoleScriptsConsistencyTest` | Decisiones 2, 3, 5, 7 | ~550 |
| D `backup-round-trip` | 7. `BackupRoundTripIT` con MinIO y todos los casos negativos | Decisión 10 | ~550 |
| E1 `restore-runbook-and-drill` | 8. `restore-drill.sh`, `record-restore-drill.sh`, runbook reescrito, plantilla de acta, `BackupOperationalFilesTest`; ensayo local del simulacro con evidencia | Decisión 13 | ~700 (prosa) |
| E2 `backup-adrs-and-notes` | 9. ADR-0029, ADR-0030, notas fechadas y convención del agente | Decisión 15 | ~350 |

**12b `backup-operations-and-drill`** (depende de 11c para publicar la imagen, de 11d y del cambio 10):

| PR | Tareas | Contenido |
|---|---|---|
| F `backup-systemd-and-metrics` | 10. Unidades, `rds-backup-status.sh`, instalación, pruebas estructurales. 11. Reglas, regla de registro, `promtool` en CI | Decisiones 11 y 12 |
| G `b2-bucket` | 12. `configure-bucket.sh`, `check-bucket.sh`, evidencia del bucket de pruebas `[PROVEEDOR]` | Decisión 6 |
| H `preprod-backup` | 13. Retención de RDS y ruta A en 11d; P-8, P-9, P-11; siembra del canario; instalación; siete noches `[AWS]`. 14. Integración con el cambio 10: recolección y `BackupMissing` `[CAMBIO-10]` | — |
| I `restore-drill-acta` | 15. Simulacro con respaldo real, acta firmada, nota de cierre del criterio 6 `[HUMANO]` | Decisión 13 |

Cada PR se revierte con su commit de fusión en orden inverso. Las dos migraciones se revierten con migraciones nuevas
que retiran las políticas o la tabla (nunca se editan). Los objetos subidos con bloqueo real no se pueden borrar antes
de su retención: por eso las pruebas con el proveedor usan el bucket de pruebas con retención de un día.

## 11. Riesgos

| Riesgo | Mitigación |
|---|---|
| Una tabla nueva sin política deja un volcado vacío sin error | Prueba de cobertura por catálogo en CI y guardia en tiempo de respaldo (código `3`) |
| P-2 o P-3: la restauración desde tubería o el orden de secciones no se comportan como se espera | Alternativas escritas (tmpfs; restauración en tres secciones) y la cadena verificada tras restaurar como detector |
| P-11: el volcado de RDS nombra roles propios de RDS | Ensayo de restauración local del primer volcado real antes del simulacro; filtrado de la lista con `-L` |
| P-5: la imagen de MinIO deja de estar disponible | Etiqueta fijada; alternativa escrita para BR20 y BR21 |
| P-6/P-7: diferencias de B2 con la API de S3 | Bucket de pruebas con retención de un día antes del bucket real; red de retención por defecto del bucket |
| Un simulacro «verde» que no descifró nada | El canario es obligatorio; su ausencia falla (BR37) |
| La KEK o la llave age se pierden | Dos copias bajo el propietario; comprobación mensual de acceso registrada |
| La reenvoltura de la KEK no existe y BR63 no puede cumplirse | Pregunta abierta 1 |
| 11c no publica la imagen de herramientas a tiempo | Alternativa: paquetes en el anfitrión con versiones registradas (decisión 2) |
| El cambio 10 se retrasa | 12b entrega archivos, reglas y pruebas de `promtool`; solo BR51 y BR54 esperan |
| La prueba de ida y vuelta alarga el trabajo `backend` | Un solo respaldo válido compartido por los casos negativos; la imagen de herramientas aprovecha la caché de capas |
| El número de migración o de ADR choca con otro cambio en curso | Se asigna el siguiente libre al aplicar |

## 12. Preguntas abiertas

- [ ] **1. Rotación de la KEK (BR63).** La especificación exige reenvolver las DEK con una KEK nueva y probarlo, pero
      no existe mecanismo de reenvoltura y la propuesta no lo incluye; el cableado real de la KEK está diferido
      (`ColumnEncryptionMasterKey.java:13-19`). Opciones: (a) añadir a este cambio una orden `recovery rewrap-keys`
      con su prueba (una tarea más, 16 en total, y una escritura sobre `shared_data_encryption_key` con asiento de
      auditoría); (b) trasladar BR63 al cambio que cablee la KEK en producción y dejar aquí solo la regla de
      procedimiento (BR64) y la rotación de age (BR62). **Recomendación: (b)**, porque una KEK que todavía no tiene
      cableado de producción no tiene rotación real que probar. Decide el propietario.
- [ ] El corte 12a/12b se recomienda aunque la cuenta quede en 15 (sección 10); lo resuelve `sdd-tasks`.

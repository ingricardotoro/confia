# Exploración: respaldo, restauración y simulacro (cambio 12 de F0)

- **Cambio:** `backup-restore-and-drill`
- **Fase del roadmap:** F0, entregable 9 (brecha B10), cierra el criterio de salida 6.
- **Origen:** exploración conjunta de los cambios 11 y 12 (Engram
  `sdd/containerization-and-cicd-pipeline/explore`) y decisión de división del cambio 11 aprobada el
  2026-11-09 (Engram `confia/decision/change-11-split`; `foundations-plan/exploration.md`, nota «división
  del cambio 11»).
- **Fecha:** 2026-11-09.

## Estado actual

| Elemento | Estado | Evidencia |
|---|---|---|
| `infra/`, `infra/scripts/backup.sh`, unidad de `systemd` | No existen. Solo hay un borrador del script en la documentación | `docs/05-infraestructura-y-despliegue.md:1501-1552` |
| Rol `confia_backup` | Solo en scripts de prueba y de generación de código: `NOBYPASSRLS` con `pg_read_all_data` | `apps/api/app/src/test/resources/db/testing/create-test-roles.sql:15-18` |
| Invariante «ningún rol con `BYPASSRLS`» | Documentada y probada para los cinco roles, incluido `confia_backup` | `docs/03-seguridad.md:889-892`; `DatabasePipelineIT.java:29,59-60` |
| Seguridad a nivel de fila | `ENABLE` y `FORCE` en las tablas de negocio, con política que exige `app.institution_id` | `V1__create_organization_institution.sql:32-44` y otras ocho migraciones (V2 a V8, V14) |
| Cifrado de columnas | Llave maestra (KEK) de 32 bytes recibida desde fuera; su cableado real está diferido | `ColumnEncryptionMasterKey.java:13-19` |
| Verificador de la cadena de auditoría | Existe en Java y se ejecuta como tarea del trabajador | `DefaultAuditChainVerifier.java:30`, `AuditChainVerificationJobs.java:44` |
| Runbook de restauración | Existe, con verificaciones sobre tablas que no existen en F0 | `docs/runbooks/restauracion-de-respaldo.md:150-214` |
| Lista de eliminaciones pendientes de propagación | No existe en el código | `docs/08-datos-privacidad-y-retencion.md:369-373`; sin coincidencias en `apps/api/app/src/main` |
| Métricas, Prometheus, Alertmanager | No existen; son del cambio 10, no iniciado | `foundations-plan/exploration.md:362-366, 370-371` |
| Entorno de preproducción en AWS | No existe; es de 11d, no planificado | `foundations-plan/exploration.md:353, 370` |

## Obligaciones

| Obligación | Fuente |
|---|---|
| Respaldo en dos niveles: RDS con restauración a un punto en el tiempo, más respaldo lógico nocturno cifrado fuera de AWS | `docs/09-roadmap-y-fases.md:222`; `docs/05:1492-1494`; ADR-0014:160-162 |
| Criterio de salida 6: un respaldo se restaura en un entorno limpio y el acta de simulacro está firmada | `docs/09:285` |
| `backup.sh`: `pg_dump` formato personalizado, cifrado con age, sha256, subida fuera de AWS, sin copia local, rol `confia_backup` | `docs/05:764-765, 779, 1501-1552` |
| Retención 7 diarios, 4 semanales, 12 mensuales, aplicada por ciclo de vida del bucket sobre el prefijo `${LABEL}/` | `docs/05:1496`; `docs/08:329` |
| Bloqueo de objeto en modo de cumplimiento, versionado, credencial de escritura sin borrado ni sobrescritura, comprobada trimestralmente con un intento rechazado | `docs/03:256-265, 2301`; `docs/08:576` |
| Temporizador de `systemd` en EC2, nunca db-scheduler ni GitHub Actions | `docs/05:1497, 1513-1517`; `docs/07:924-928` |
| Métricas `confia_backup_last_success_timestamp_seconds`, `confia_backup_duration_seconds`, `confia_rds_backup_enabled`, `confia_rds_restorable_time_lag_seconds`, `confia_restore_drill_last_success_timestamp_seconds`, `confia_restore_drill_duration_seconds` | `docs/07:450-453, 942` |
| Alertas `RdsBackupDisabled`, `RdsRestorableTimeLagHigh`, `BackupMissing` (S1) y `RestoreDrillOverdue` (S3) | `docs/07:635-637, 662` |
| Objetivos: punto de recuperación de 15 minutos dentro de AWS; tiempo de recuperación menor a 4 horas medido en el simulacro | `docs/07:715-716`; `docs/05:1498-1499` |
| Simulacro mensual con respaldo real descargado y descifrado con la llave custodiada, en entorno limpio, con acta firmada | `docs/05:1499`; runbook:258 |
| Custodia de la llave age fuera del servidor, de AWS y del repositorio, con copia física sellada; rotación anual probada con una restauración antes de retirar la anterior | `docs/03:1131-1137, 1191, 1675` |
| Simulacro de salida anual en un proveedor distinto de AWS | ADR-0014:367-370; runbook:259 |
| Restauración a un punto en el tiempo de RDS (ruta A del runbook) | runbook:94-115 |
| Reaplicación de la lista de eliminaciones pendientes de propagación como paso obligatorio tras restaurar | `docs/08:371-373, 829-830` |
| Respaldo previo a cada migración en producción (`backup.sh pre-migration`) | `docs/05:1205-1210, 1272-1273` |

## Qué deben entregar antes otros cambios

| Cambio | Lo que necesita el cambio 12 |
|---|---|
| 11a `dockerfiles-and-local-compose` | Imagen de la API con perfil `migrate` real, Compose local y el script de roles local. Sirven para restaurar en un PostgreSQL limpio con los roles creados antes de `pg_restore` |
| 11d `preprod-environment-and-deploy` | EC2 donde corre el temporizador, RDS con roles aprovisionados (incluido `confia_backup`), grupo de seguridad que permite al host llegar a RDS, gestión de secretos para `BACKUP_DATABASE_URL`, credenciales del bucket y la KEK. Sin 11d no hay respaldo real ni simulacro |
| 10 `structured-logging-and-tracing` | Prometheus y Alertmanager donde se cargan las métricas y las cuatro alertas. Sin el cambio 10, el cambio 12 puede escribir métricas y reglas, pero no demostrar que una alerta dispara |

## Riesgos

1. **`pg_dump` contra RLS forzada.** Con `row_security` desactivado (valor por defecto de `pg_dump`), una
   tabla con `FORCE ROW LEVEL SECURITY` y un rol sin `BYPASSRLS` provoca error. Con
   `--enable-row-security`, la política exige `app.institution_id` (`V1:42-44`) y el volcado sale vacío sin
   error. `pg_read_all_data` no anula RLS. Hay que resolverlo antes de cualquier otra tarea y probarlo.
2. **Verificaciones del runbook inexistentes en F0.** `ledger_transactions`, `payments`, `students`,
   `fiscal_documents` y `cai_ranges` no existen. Se adaptan a lo que sí hay (cadena de auditoría con el
   verificador Java, historial de Flyway, conteos por tabla existente) y se dejan como pasos condicionados
   a la fase que crea cada tabla.
3. **Columnas cifradas.** El volcado lleva texto cifrado y llaves envueltas, no la KEK. Un simulacro que
   no descifra una fila conocida no prueba la recuperación. La KEK necesita la misma custodia que la llave
   age.
4. **Roles en el destino.** Las políticas y los permisos nombran roles; restaurar con `--no-owner` y sin
   roles pierde permisos. El destino limpio debe crear los roles antes de `pg_restore`.
5. **Retención escalonada con bloqueo de cumplimiento.** El ciclo de vida por prefijo exige que el script
   decida la etiqueta (diario, semanal, mensual) y que la retención del bloqueo no supere la del ciclo de
   vida, o los objetos no rotan.
6. **Proveedor sin verificar.** No se confirmó con documentación primaria que Cloudflare R2 ofrezca bloqueo
   por objeto en modo de cumplimiento ni credencial que escriba sin borrar.
7. **Lista de eliminaciones pendientes sin implementación ni dueño.** El paso obligatorio de `docs/08`
   solo puede documentarse en F0.
8. **Publicación de métricas desde un script.** Falta definir el mecanismo (por ejemplo, archivo de texto
   para el recolector del nodo); es decisión de diseño.

## Verificable en local y no verificable en local

- **Local (Testcontainers con PostgreSQL y MinIO):** volcado como `confia_backup` bajo RLS, cifrado,
  sha256, subida, descarga, verificación del hash, descifrado, restauración en una base limpia con roles,
  verificación de la cadena de auditoría, descifrado de una fila canario con la KEK, y el rechazo de un
  borrado con una credencial de solo escritura si MinIO se configura con bloqueo de objeto.
- **Solo en AWS o con personas:** restauración a un punto en el tiempo de RDS, bloqueo de cumplimiento
  real del proveedor, temporizador de `systemd` en EC2, métricas de RDS reales, disparo real de alertas,
  tiempo de recuperación medido contra 4 horas, acta firmada y custodia física de llaves.

## Tamaño

Entre 12 y 14 tareas, contra las 9 a 10 de la tabla (`foundations-plan/exploration.md:42`). La última
queda bloqueada por personas: ejecución del simulacro y firma del acta.

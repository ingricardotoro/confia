# Propuesta: respaldo, restauración y simulacro (cambio 12 de F0)

- **Cambio:** `backup-restore-and-drill`
- **Fase del roadmap:** F0, cambio 12, entregable 9 (brecha B10). Cierra el criterio de salida 6
  (`docs/09-roadmap-y-fases.md:285`).
- **Depende de:** 11a `dockerfiles-and-local-compose`, 11d `preprod-environment-and-deploy` (no
  planificado) y las métricas del cambio 10 `structured-logging-and-tracing` (no iniciado)
  (`foundations-plan/exploration.md:370-371`).
- **Lo necesitan:** ningún cambio de F0; es condición del criterio de salida 6 y del hito H1.
- **Exploración:** `openspec/changes/backup-restore-and-drill/exploration.md` (Engram
  `sdd/containerization-and-cicd-pipeline/explore`).
- **Estado:** aprobada por el propietario el 2026-11-09, con las cuatro decisiones recomendadas (B2, política de lectura por tabla para `confia_backup`, RPO de 24 h sin datos reales, acta firmada por el propietario que custodia las llaves).

## Intención

CONFIA no tiene hoy ningún respaldo ejecutable: no existe `infra/`, el script de `docs/05` §11 es un
borrador y nunca se ha restaurado nada. El rol `confia_backup` tal como está definido no puede volcar las
tablas con seguridad a nivel de fila forzada. Sin respaldo restaurado y verificado no se cumple el criterio
de salida 6 y no se puede cargar ningún dato real.

**Por qué ahora:** el criterio 6 sigue abierto (`docs/09:285`), el hito H1 exige restaurar un respaldo
en vivo (`docs/09:773`), y el riesgo principal (RLS contra `pg_dump`) se puede resolver y probar en local
antes de que exista preproducción.

**Cómo se ve el éxito:** un respaldo nocturno real de preproducción, cifrado con age y guardado fuera de
AWS bajo bloqueo de cumplimiento, se descarga, se descifra con la llave custodiada y se restaura en un
PostgreSQL limpio en menos de cuatro horas; la verificación prueba la cadena de auditoría y descifra una
columna cifrada; las métricas y las cuatro alertas existen; y el acta del simulacro queda firmada.

## Problema

1. **`pg_dump` no puede leer las tablas con RLS forzada.** `confia_backup` es `NOBYPASSRLS` con
   `pg_read_all_data` (`create-test-roles.sql:15-18`). Con `row_security` desactivado, `pg_dump` falla;
   con `--enable-row-security`, la política exige `app.institution_id` (`V1:42-44`) y el volcado sale vacío
   sin error. `docs/03:891-892` y `DatabasePipelineIT.java:29,59-60` prohíben `BYPASSRLS` para todos los
   roles, incluido este.
2. **Las verificaciones del runbook no se pueden ejecutar.** Usan `ledger_transactions`, `payments`,
   `students`, `fiscal_documents` y `cai_ranges`, que no existen en F0 (runbook:150-214).
3. **El volcado no basta para recuperar datos cifrados.** La KEK de cifrado de columnas no viaja en el
   volcado (`ColumnEncryptionMasterKey.java:13-19`); un simulacro que no descifra no prueba nada.
4. **La retención escalonada, el bloqueo de cumplimiento y la credencial sin borrado** dependen del
   proveedor fuera de AWS, que no está elegido (`docs/05:765`).
5. **El paso de reaplicar la lista de eliminaciones pendientes** (`docs/08:371-373`) falta en el runbook y
   la lista no existe en el código.

## Alcance

### Dentro de alcance

**Acceso de lectura del respaldo**

1. Resolución del acceso de `confia_backup` a las tablas con RLS forzada según la decisión D2, con prueba
   de integración que demuestra un volcado completo de todas las tablas y una prueba de esquema que falla
   si una tabla nueva con RLS no queda cubierta.

**Herramientas de respaldo y restauración**

2. `infra/scripts/backup.sh` endurecido: `pg_dump` formato personalizado como `confia_backup`, cifrado con
   age, sha256, subida al proveedor fuera de AWS con retención de bloqueo por objeto, etiqueta escalonada
   (diario, semanal, mensual) y modo `pre-migration`; sin copia local; códigos de salida claros; sin
   secretos en registros (regla 11).
3. `infra/scripts/restore.sh`: descarga, verificación del sha256, descifrado con la llave indicada,
   creación de roles en el destino limpio y `pg_restore`; nunca contra la base de producción.
4. Verificación de restauración ejecutable que termina con código 0 o distinto de 0: historial de Flyway
   completo, conteos por tabla existente, cadena de auditoría con el verificador Java existente
   (`DefaultAuditChainVerifier`) y descifrado de una fila canario con la KEK custodiada.
5. Prueba de ida y vuelta local con Testcontainers (PostgreSQL y MinIO): volcado, cifrado, subida,
   descarga, restauración y verificación, como trabajo de CI que rompe la construcción.

**Operación**

6. Unidades `systemd` (servicio y temporizador) para el respaldo nocturno en EC2, con su instalación
   documentada; db-scheduler no ejecuta respaldos (ADR-0016, `docs/05:1497`).
7. Configuración del bucket fuera de AWS como código o procedimiento versionado: versionado, bloqueo en
   modo de cumplimiento, reglas de ciclo de vida 7/4/12 por prefijo y credencial de escritura sin borrado
   ni sobrescritura, con la prueba del intento de borrado rechazado.
8. Retención de respaldos automáticos de RDS y restauración a un punto en el tiempo, como ajuste sobre la
   infraestructura de 11d.
9. Publicación de las métricas de respaldo y de simulacro desde los scripts, recolección del estado de
   respaldo de RDS y reglas de las alertas `BackupMissing`, `RdsBackupDisabled`,
   `RdsRestorableTimeLagHigh` y `RestoreDrillOverdue`.

**Documentación y simulacro**

10. Runbook de restauración reescrito: verificaciones adaptadas a F0 con los pasos futuros marcados por
    fase, ruta A validada contra RDS, paso obligatorio de la lista de eliminaciones pendientes, rotación
    de la llave age y de la KEK con restauración previa, y procedimiento del simulacro de salida anual.
11. Plantilla de acta del simulacro con tiempos medidos contra los objetivos.
12. Notas fechadas, sin reescribir cuerpos: `docs/05` §11, `docs/03` §7.2, `docs/07` (métricas y
    alertas), `docs/08` §6.1, `docs/09` (criterio 6) y `foundations-plan/exploration.md`.
13. **Simulacro en preproducción y acta firmada** (tarea bloqueada por personas).

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| EC2, RDS, red, aprovisionamiento de roles en RDS, gestor de secretos, OpenTofu base | 11d `preprod-environment-and-deploy` |
| Prometheus, Alertmanager, recolector del nodo y el resto de métricas | Cambio 10 `structured-logging-and-tracing` |
| Imágenes, Compose y script de roles local | 11a `dockerfiles-and-local-compose` |
| Replicación de objetos de S3 (PDF fiscales, adjuntos) fuera de sitio (`docs/05:1492`) | Cambio que cree el primer almacenamiento de objetos de negocio (en F0 solo existe el ancla de 11e) |
| Ancla externa de la cadena de auditoría | 11e `audit-chain-external-anchor` |
| Implementación de la lista de eliminaciones pendientes de propagación | **Sin dueño.** Se registra para la fase que introduzca datos de titulares; este cambio solo deja el paso en el runbook |
| Verificaciones de libro mayor, pagos y correlativos fiscales del runbook | Las fases que crean esas tablas, que añaden su paso al runbook |
| Simulacro de salida anual ejecutado | Operación posterior a F0 (este cambio deja el procedimiento) |
| Ejercicio semestral de recuperación ante desastre (`docs/03`, §19) | Operación posterior a F0 |

## Capacidades

> Contrato con `/sdd-spec`. Investigado contra `openspec/specs/` (doce capacidades publicadas:
> `audit-trail`, `background-jobs`, `build-integrity`, `cashbox`, `design-system`, `identity`, `invoicing`,
> `ledger`, `money`, `organization`, `payments`, `web-edge`). `container-runtime` de 11a aún no está
> archivada.

### Nuevas

- **`backup-and-restore`:** respaldo lógico cifrado fuera de AWS, retención y bloqueo, restauración en
  entorno limpio, verificación de la restauración (incluido el descifrado), métricas y alertas de
  respaldo y de simulacro, custodia y rotación de llaves, y simulacro con acta.

### Modificadas

- **`audit-trail`:** la verificación de la cadena se exige también sobre una base restaurada, como parte
  de la verificación de restauración.
- **`build-integrity`:** trabajo de CI de ida y vuelta de respaldo y restauración que rompe la
  construcción; prueba de esquema de cobertura del acceso de `confia_backup`.

## Enfoque

1. **Primero el acceso bajo RLS, con prueba roja.** Una prueba de integración que vuelca como
   `confia_backup` y compara conteos con los del propietario falla hoy; se resuelve según D2 y se fija
   con una prueba de esquema para cada tabla futura.
2. **Scripts delgados, verificación en Java.** El shell solo mueve bytes (volcar, cifrar, subir, bajar,
   restaurar); la verificación reutiliza el verificador de la cadena y el servicio de cifrado de columnas,
   ejecutada desde el mismo jar (mecanismo exacto en diseño).
3. **Todo lo verificable, en local.** Testcontainers con PostgreSQL y MinIO cubre el ciclo completo; lo
   que solo existe en AWS queda en la tarea final, con evidencia del acta.
4. **Retención por etiqueta.** El script elige la etiqueta por fecha y fija la retención del bloqueo de
   cada objeto igual a la de su ciclo de vida, para que el bloqueo nunca impida la rotación.
5. **Pruebas primero** donde existe ejecutor determinista (`openspec/config.yaml`); para unidades de
   `systemd` y configuración del bucket, comprobaciones estructurales y el intento de borrado rechazado.

## Áreas afectadas

| Área | Cambio |
|---|---|
| `apps/api/app/src/main/resources/db/migration/` | Acceso de `confia_backup` según D2 |
| `apps/api/app/src/test/resources/db/testing/create-test-roles.sql`, `DatabasePipelineIT.java` | Ajuste a D2 y pruebas nuevas |
| Verificación de restauración (paquete fijado en diseño, junto a `shared/audit` y `shared/crypto`) | Nueva |
| `infra/scripts/backup.sh`, `infra/scripts/restore.sh` | Nuevos |
| `infra/systemd/` (ruta fijada en diseño) | Servicio y temporizador |
| Configuración del bucket y de RDS (`infra/tofu/` de 11d o procedimiento versionado) | Nueva |
| Reglas de alertas (ubicación del cambio 10) | Nuevas |
| `.github/workflows/ci.yml` | Trabajo de ida y vuelta |
| `docs/runbooks/restauracion-de-respaldo.md`, plantilla de acta | Reescritura y nueva |
| `docs/03`, `docs/05`, `docs/07`, `docs/08`, `docs/09`, `foundations-plan/exploration.md` | Notas fechadas |

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Volcado vacío o parcial sin error bajo RLS | Alta sin corrección | Prueba de conteos contra el propietario; prueba de esquema por tabla |
| Un simulacro «verde» que no descifró nada | Media | Fila canario descifrada como condición de éxito |
| La KEK o la llave age se pierden o solo viven en el servidor | Media | Custodia según D4; verificación mensual de acceso a la llave (runbook:261) |
| El proveedor elegido no ofrece bloqueo de cumplimiento o credencial sin borrado | Media para R2, baja para B2 (sin verificar) | D1; validación con documentación primaria en diseño y prueba real de borrado rechazado |
| El bloqueo de cumplimiento impide la rotación y dispara costos | Media | Retención del bloqueo igual al ciclo de vida por etiqueta; nunca mayor |
| Restaurar sin roles pierde permisos y políticas | Media | `restore.sh` crea roles antes de `pg_restore`; prueba local |
| 11d o el cambio 10 se retrasan | Alta | Las tareas 1 a 5 y la documentación no dependen de ellos; el resto espera |
| Tamaño en el límite de quince tareas | Media | Corte propuesto abajo |

## Plan de reversión

No hay datos reales. Cada PR se revierte con su commit de fusión en orden inverso. La migración de acceso
de `confia_backup` se revierte con una migración nueva que retira el permiso o las políticas (nunca se
edita una migración aplicada). Los scripts, unidades y reglas son archivos nuevos y se retiran sin tocar
código de negocio. **Lo que no se revierte:** los objetos subidos bajo bloqueo de cumplimiento no se pueden
borrar antes de su retención; por eso las pruebas de bloqueo real usan un bucket de pruebas con retención
de un día. Desactivar el temporizador detiene los respaldos sin afectar la aplicación.

## Dependencias

- 11a (imagen, Compose y roles locales) archivado o fusionado.
- 11d (EC2, RDS con roles, secretos, red): **bloquea las tareas 6 a 9 y 13**.
- Cambio 10 (Prometheus y Alertmanager): **bloquea la demostración de las alertas**.
- Cuenta en el proveedor fuera de AWS (D1) con bloqueo de objeto habilitado al crear el bucket.
- Herramientas: `age`, cliente de PostgreSQL 18 (`pg_dump`, `pg_restore`), cliente S3 compatible,
  MinIO en Testcontainers (ya previsto en ADR-0014:345).

## Criterios de éxito

**Verificables en local y en CI**

- [ ] El volcado como `confia_backup` contiene todas las filas de todas las tablas con RLS, comprobado
      contra los conteos del propietario; la prueba de esquema falla si una tabla nueva queda sin cubrir.
- [ ] La invariante de `docs/03` §6.1 se cumple tal como quede tras D2, con su prueba actualizada.
- [ ] La ida y vuelta (volcado, cifrado, sha256, subida a MinIO, descarga, verificación, descifrado,
      restauración en base limpia con roles) pasa en CI y rompe la construcción si falla.
- [ ] La verificación termina en 0 con cadena íntegra y canario descifrado, y en distinto de 0 ante una
      cadena alterada, un hash erróneo o una KEK equivocada.
- [ ] Un respaldo no se puede leer sin la llave age.
- [ ] Ningún secreto en el repositorio ni en los registros de los scripts.
- [ ] `./mvnw verify` en verde, con cobertura y mutación en sus umbrales.

**Solo en AWS o con personas**

- [ ] El temporizador produce siete respaldos nocturnos consecutivos en preproducción.
- [ ] La credencial de respaldo intenta borrar y sobrescribir un objeto y el proveedor lo rechaza.
- [ ] Las métricas se publican y `BackupMissing` dispara al detener el temporizador.
- [ ] RDS tiene respaldos automáticos y una restauración a un punto en el tiempo se ejecuta según la
      ruta A.
- [ ] El simulacro restaura un respaldo real descargado fuera de AWS en un entorno limpio en menos de
      cuatro horas, con las verificaciones en verde.
- [ ] El acta está firmada por quien decida D4 y el criterio de salida 6 queda marcado en `docs/09`.

## Pronóstico de tamaño

Estimación: **de 13 a 15 tareas**, frente a las 9 a 10 de la tabla. Si `tasks.md` supera quince, el corte
propuesto es:

- **12a `backup-tooling`:** puntos 1 a 5, 10 y 11. No depende de 11d ni del cambio 10 y puede empezar
  después de 11a.
- **12b `backup-operations-and-drill`:** puntos 6 a 9, 12 y 13. Depende de 11d y del cambio 10 y cierra el
  criterio 6.

## Decisiones del propietario

> Estas decisiones bloquean el diseño de las partes que dependen de ellas. El resto de la propuesta no
> depende de ellas.

**D1. Proveedor fuera de AWS: Backblaze B2 o Cloudflare R2.** Requisitos: bloqueo de objeto en modo de
cumplimiento por objeto y credencial que escriba sin poder borrar ni sobrescribir (`docs/03:256-265`,
`docs/08:576`).
**Recomendación: Backblaze B2**, que es además la base de la estimación de costos de ADR-0014:276.
**No verificado en esta exploración** (sin consulta a documentación primaria): que R2 ofrezca bloqueo por
objeto en modo de cumplimiento compatible con la API de S3 y una credencial con escritura sin borrado; y,
para B2, el nombre exacto de las capacidades de la llave de aplicación y la compatibilidad de su ciclo de
vida con objetos bloqueados. Se valida en diseño con documentación primaria antes de crear la cuenta.

**D2. Acceso de `confia_backup` bajo RLS.** `docs/03:891-892` y `DatabasePipelineIT` prohíben `BYPASSRLS`
para todos los roles, pero sin él `pg_dump` falla o vuelca vacío.

- *Opción A: conceder `BYPASSRLS` solo a `confia_backup`* y documentarlo como excepción en `docs/03` §6.1.
  Simple y nativo de `pg_dump`, pero rompe una invariante explícita y anula todas las políticas para ese
  rol. Falta verificar que RDS permita conceder `BYPASSRLS` desde `rds_superuser`.
- *Opción B: mantener `NOBYPASSRLS` y añadir a cada tabla con RLS una política `FOR SELECT TO
  confia_backup USING (true)`*, con una prueba de esquema que falla si una tabla nueva no la tiene, y
  `pg_dump --enable-row-security` (con `row_security` desactivado, PostgreSQL rechaza la consulta aunque
  la política lo permita todo). Conserva la invariante literal y limita el efecto a lectura, a cambio de
  una política más por tabla.

**Recomendación: opción B**, porque la invariante está probada y documentada como crítica, y el permiso
queda explícito y auditable por tabla. En ambos casos `confia_backup` sigue sin permisos de escritura.

**D3. Punto de recuperación ante la pérdida total de la cuenta de AWS.** ADR-0014:328-332 lo acepta igual
al último respaldo fuera de sitio y permite respaldos más frecuentes si el propietario lo exige. Con un
respaldo nocturno, la pérdida máxima es de unas 24 horas (`BackupMissing` avisa a las 26).
**Recomendación: aceptar 24 horas mientras no haya datos reales** y revisarlo antes del primer dato real;
corregir el encabezado del runbook, que hoy declara 15 minutos sin distinguir la ruta B.

**D4. Quién firma el acta y quién custodia las llaves.** La llave privada age y la KEK de cifrado de
columnas deben vivir fuera del servidor, de AWS y del repositorio, con copia física sellada
(`docs/03:1131-1133`).
**Recomendación:** el desarrollador ejecuta el simulacro y el **propietario firma el acta** como
responsable; el **propietario custodia** ambas llaves (gestor de secretos personal fuera de AWS más copia
física sellada en la institución), y el desarrollador las recibe solo durante el simulacro o un incidente,
con registro en el acta. Desde F10 se suma la firma del departamento de sistemas (`docs/09:658`).

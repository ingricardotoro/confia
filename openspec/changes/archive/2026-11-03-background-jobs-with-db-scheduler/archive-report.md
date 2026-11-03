# Informe de archivo: trabajos en segundo plano con db-scheduler

- **Cambio:** `background-jobs-with-db-scheduler` (F0, cambio 8)
- **Archivado:** 2026-11-03
- **Modo de almacenamiento:** hybrid (rutas de archivo para los artefactos; Engram para este informe, observación #750)
- **Base del cambio:** `main` en `ef6db61`; rama `change/background-jobs-with-db-scheduler-08-archive`
- **Estado de cierre:** implementación completa. 24 de 24 tareas `[x]` en `tasks.md` (incluida la 4.3), 0 casillas abiertas. Verificación con advertencias (`PASS WITH WARNINGS`, 0 CRITICAL, 7 WARNING, 5 SUGGESTION; `verify-report.md`).
- **Aprobación:** el propietario aprobó el archivo el 2026-11-03.

## Fusión de especificaciones por vía de respaldo determinista

`gentle-ai sdd-archive-compose` no está disponible en gentle-ai 4.0.0 (`gentle-ai help` no lista el grupo `sdd`). El propietario aprobó el 2026-11-03 una fusión determinista de respaldo, como en el archivo `66bb1dd`. La fusión la hizo un script escrito para este archivo, no ediciones a mano:

- **Script:** `C:/dev/confia-scratch/archive-merge/merge_specs.py` (aplica en el orden RENAMED, MODIFIED, REMOVED, ADDED; empareja por el encabezado exacto `### Requisito: ...`; aborta sin escribir si un encabezado MODIFIED, REMOVED o RENAMED no aparece exactamente una vez, o si un ADDED ya existe).
- **Lectura de verificación:** `C:/dev/confia-scratch/archive-merge/check_specs.py`, independiente de la fusión, contra `git show HEAD:` y contra el delta.
- **Finales de línea:** las especificaciones principales usan LF; se conservan. Ninguna línea de un requisito no tocado cambió.

| Dominio | ADDED | MODIFIED | REMOVED | RENAMED | Requisitos antes → después | Lectura |
|---------|-------|----------|---------|---------|----------------------------|---------|
| `audit-trail` | 2 | 1 | 0 | 1 | 11 → 13 | PASS; 10 no tocados idénticos; 1 diferencia de separador al final del archivo (línea en blanco añadida antes del bloque nuevo) |
| `build-integrity` | 12 | 8 | 2 | 0 | 84 → 94 | PASS; 74 no tocados idénticos |
| `identity` | 7 | 3 | 3 | 1 | 89 → 93 | PASS; 83 no tocados idénticos |
| `background-jobs` | — | — | — | — | nuevo (16 requisitos) | copia mecánica del delta completo (no era un delta); `diff -r` vacío |

- **Encabezados que no coincidieron:** ninguno. Todos los encabezados MODIFIED, REMOVED y RENAMED aparecieron exactamente una vez en la especificación principal, y ningún ADDED ya existía.
- **Escenarios:** todos los encabezados de escenario de los deltas ADDED y MODIFIED aparecen en la especificación fusionada (`audit-trail` 26, `build-integrity` 82, `identity` 61, con los identificadores entre corchetes).
- **Recuentos de escenarios de la especificación:** `audit-trail` 33 → 57; `build-integrity` 232 → 287; `identity` 281 → 324.
- **Preámbulo** (secciones antes de los requisitos): byte a byte igual a `HEAD` en las tres.

## Corrección de la verificación y ajustes aplicados

1. `tasks.md:732` tarea 4.3 marcada `[x]`; `:751-753` estado y verificación final actualizados (`./mvnw verify -Pmutation-gate` del propietario en `ef6db61`, BUILD SUCCESS, 1 h 03 min; última `./mvnw -B verify` de la tarea 25 min 20 s; tamaño de la rama 1 222 líneas efectivas contra `e9f7c0e`, medido).
2. `specs/audit-trail/spec.md:198` y `:239`: `failure` → `error` (la restricción `shared_audit_log_outcome_chk` de V2 admite `success`, `denied` y `error`).
3. `specs/build-integrity/spec.md:631-633`: la lista aprobada de SQL plano son exactamente tres clases (`JooqAuditLogWriter`, `JooqRefreshTokenFamilyRepository`, `JooqScheduledTaskQueueReader`), comprobadas contra `NoUnapprovedPlainSqlTest.java:59-62` y `:92-95`. Solo el lector consulta `scheduled_tasks` y nunca selecciona `task_data` (`JooqScheduledTaskQueueReader.java:22-29`, `:62`). `:643` BI101 retitulado a «es el único sobre `scheduled_tasks`»; `:645` su DADO remite a la lista de tres.
   - Ajuste adicional aprobado por el propietario: en `:631` «reportes aprobados» pasó a «clases aprobadas», porque la lista incluye escritores, no solo reportes.
4. `apply-progress.md:510`: BJ37 se quita de la fila de `DocumentationNotesTest` (queda BJ41); BJ37 la cubre `ExitCriterion5EndToEndIT` (fila 508). `:858`: la última verificación completa mide 25:20.

**Correcciones rechazadas:** ninguna.

## Trazabilidad de los 241 escenarios

**228 cubiertos, 11 parciales, 2 sin prueba** (`apply-progress.md:540`).

- **Parciales:** I240, I241, I264, BJ08, BI103, BI60, «Las pruebas de arranque no necesitan Docker», BI67, BI68, BI69, BJ37.
- **Sin prueba:** «Se agrega Hibernate» y «Sin dependencias prohibidas» (`build-integrity`). Los hace cumplir la regla `bannedDependencies` de `maven-enforcer` en cada `verify`; ningún fixture los demuestra.

**Omisión de JobsChangeTraceabilityTest, confirmada por lectura del código:** la prueba lee `openspec/changes/background-jobs-with-db-scheduler/specs` (`JobsChangeTraceabilityTest.java:43`). `deltaScenarios()` llama a `assumeTrue(Files.isDirectory(...))` (`:231-232`), así que las pruebas de `:139`, `:148` y `:157` se omiten ahora. `everyReferenceNamesAClassAndAMethodThatExist` solo lee el árbol de pruebas y sigue ejecutándose.

## Riesgos y seguimientos

- **R-9 abierto:** `./mvnw -B verify` tarda entre 17 y 25 min frente al presupuesto de 15 min (BI57). Necesita un cambio propio.
- **PR #242 (7a-2), no bloqueante:** carrera en `QueueObservationOnWorkerIT` (una tarea recurrente en ejecución puede quedar marcada como fallida); conteos fijos 8/7 en vez de derivarlos del catálogo; sin prueba para las claves `db-scheduler.heartbeat-interval` y `db-scheduler.missed-heartbeats-limit`.
- **PR #244 (7c), no bloqueante:** el fixture negativo de trazabilidad no ejercita los dos filtros estructurales; un `@DisplayName` con `)` o una anotación de prueba con nombre calificado hace que una etiqueta real se ignore (falla del lado seguro).
- **Definición de terminado, NO confirmado:** el documento de lista de verificación de seguridad no se localizó, y el escaneo de secretos versionados no se ejecutó. Quedan como no confirmados, no como cumplidos.

## Artefactos

| Artefacto | Ubicación | Estado |
|-----------|-----------|--------|
| proposal.md, design.md, exploration.md | `openspec/changes/archive/2026-11-03-background-jobs-with-db-scheduler/` | presentes |
| specs/ (deltas) | misma carpeta | presentes; no modificados |
| tasks.md | misma carpeta | 24 de 24 `[x]`, 0 abiertas |
| apply-progress.md | misma carpeta | instantánea intermedia, corregida en 510 y 858 |
| verify-report.md | misma carpeta | copia del texto de Engram #749 (instantánea intermedia) |
| archive-report.md | este informe | presente |

Observaciones de Engram usadas: verify-report #749 (lectura principal). Informe de archivo: #750 (actualizado al estado cerrado).

## Movimiento

- **Origen:** `openspec/changes/background-jobs-with-db-scheduler`
- **Destino:** `openspec/changes/archive/2026-11-03-background-jobs-with-db-scheduler`
- **Mecanismo:** instantánea recursiva previa con `cp -R`, después `git mv` del directorio. El origen ya no existe.
- **Lectura (`diff -r` entre la instantánea previa y el destino), salida literal:**

```
(salida vacía; exit 0)
```

## Estado de la sesión

No se ha confirmado ni subido nada: los cambios quedan en el árbol de trabajo para revisión del propietario.

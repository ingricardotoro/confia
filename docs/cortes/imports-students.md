# Corte: importación de estudiantes (`imports`)

Fase F1, entregable 6 (herramienta de migración), primer corte. Rama `feat/imports-students`, apilada sobre
`feat/guardians-consent` (PR #308). Riesgo R11 (calidad de los datos de origen): `docs/09-roadmap-y-fases.md`.

## Objetivo

La administración carga el listado de estudiantes del sistema actual desde una hoja de cálculo exportada a CSV, lo
**simula** tantas veces como haga falta, revisa el reporte de rechazos y finalmente lo **aplica**. Cortes siguientes:
encargados y vínculos, matrículas y acta de conciliación.

## Decisiones

- **Formato: CSV UTF-8** (Excel: «Guardar como CSV UTF-8»), separador `,` o `;` detectado en la cabecera, hasta 5 MB y
  10 000 filas. Leer `.xlsx` queda para después (exige una biblioteca pesada con superficie de ataque propia).
- **No se guarda el archivo ni sus filas.** Es un dato personal de menores en masa. La simulación y la aplicación reciben el
  archivo; la aplicación solo procede si su huella SHA-256 coincide con una simulación previa del mismo usuario e institución
  (lote `VALIDATED`). Así no hay copia de los datos fuera de `students`.
- **Idempotencia por referencia externa.** La columna `legacyId` del archivo se guarda en el estudiante (`external_ref`,
  nueva columna nullable con índice único `(institution_id, external_ref)`). Reaplicar el mismo archivo o uno corregido
  omite las filas ya importadas (`already-imported`) y no duplica nada.
- **Se aplican solo las filas válidas**; las rechazadas quedan en el reporte para corregir en origen y reintentar.

## Modelo y reglas

Tabla `imports_batch` (`institution_id`, seguridad por fila forzada, sin `DELETE`): `id`, `kind` (`STUDENTS`), `status`
(`VALIDATED`, `APPLIED`), `fileSha256`, `totalRows`, `validRows`, `rejectedRows`, `createdRows`, `skippedRows`,
`createdBy`, `createdAt`, `appliedAt`. **Sin datos del archivo ni valores rechazados.**

Plantilla (cabecera obligatoria, en este orden de nombres; el orden de columnas es libre): `legacyId`, `firstName`,
`lastName`, `birthDate` (`YYYY-MM-DD`), `nationalId` (opcional), más las columnas opcionales que ya acepta el registro de
estudiantes (revisar `students-registry.md` y reutilizar sus reglas exactas de validación y normalización, sin copiarlas).
Cada fila pasa por **el mismo caso de uso y las mismas reglas** que el alta manual (código `E-000001`, documento cifrado con
índice ciego, auditoría), no por una ruta paralela.

Motivos de rechazo (código estable, campo y número de línea; nunca el valor): `missing-column`, `unknown-column`,
`required`, `invalid-format`, `too-long`, `invalid-date`, `future-birth-date`, `duplicate-in-file` (legacyId o documento
repetidos en el archivo), `duplicate-national-id` (ya existe en la institución), `already-imported`.

## API

| Operación | Permiso |
|---|---|
| `POST /api/v1/imports/students/simulate` (multipart, campo `file`) → 200: lote `VALIDATED` con conteos y hasta 200 rechazos `{line, field, reason}` | `STUDENTS_CREATE` |
| `POST /api/v1/imports/students/apply` (multipart, el mismo archivo) → 200: lote `APPLIED` con `createdRows`, `skippedRows` y los rechazos | `STUDENTS_CREATE` |
| `GET /api/v1/imports` paginado (lotes, más recientes primero) | `STUDENTS_READ` |

- La simulación **no escribe en `students`**. La aplicación vuelve a validar todo dentro de una transacción por bloques y
  falla con `409 import-not-simulated` si la huella no tiene simulación previa, o `409 import-file-changed` si se modificó.
- Validación de archivo en el borde: tipo y tamaño, filas máximas (`413`/`400 validation-failed`), cabecera, codificación.
  El contenido nunca se interpreta como fórmula ni se registra.
- Auditoría `imports.batch.simulated|applied` con conteos y huella; **sin nombres, documentos ni valores**. Los estudiantes
  creados auditan como el alta manual. Límite de tasa estricto (pocas importaciones por hora) por dirección y cuenta.
  Respuestas `Cache-Control: no-store`.
- Concurrencia: dos aplicaciones simultáneas del mismo archivo no duplican (índice único de `external_ref` es la última palabra).

## Pantalla (`admin-web`)

Entrada «Importaciones»: pasos «Elegir archivo» → «Simular» (resumen y tabla de rechazos paginada en el navegador desde la
respuesta, descargable como CSV de **líneas y motivos**, sin valores) → «Aplicar» (con confirmación que muestra cuántos se
crearán) → resultado. Historial de lotes. Descarga de la plantilla vacía. Estados de carga, vacío, error y éxito. Los
nombres de los estudiantes nunca se muestran en esta pantalla.

## Casos límite a probar

Archivo vacío, solo cabecera, 10 001 filas, 5 MB + 1, BOM, separador `;`, comillas y saltos de línea dentro de campo,
columna faltante o desconocida, fechas inválidas/futuras, documento repetido en el archivo y ya existente, `legacyId`
repetido, reaplicar el mismo archivo (todo `already-imported`), archivo modificado entre simular y aplicar, aplicar sin
simular, dos aplicaciones simultáneas, fila inválida que no impide las válidas, aislamiento entre instituciones, 403 sin
efecto, límites de tasa, ausencia de datos personales en bitácora, logs, lote y respuestas de error.

## Criterio de aceptación

Un administrador sube un CSV de 100 estudiantes con 5 filas defectuosas, ve exactamente esos 5 rechazos por línea y motivo,
corrige y simula de nuevo, aplica, y los estudiantes aparecen en el registro; reaplicar el archivo no crea ninguno más.

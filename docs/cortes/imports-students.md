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

## Construido en el backend

Módulo `com.confia.imports` (dominio, aplicación, `infrastructure`, `web`), migración `V21__create_imports_batch.sql` y tres rutas
bajo `/api/v1/imports`. Las filas pasan por el caso de uso de `students` (`ManageStudents`) a través de un puerto que ese módulo
publica como `@NamedInterface` (`students.application.importing.StudentImportGateway`, consumidor: `imports`); `imports` no importa el
dominio de `students` ni lee sus tablas.

### Forma de la API que necesita el frontend

Todas las respuestas llevan `Cache-Control: no-store`. Las dos rutas `POST` son `multipart/form-data` con el archivo en la parte `file`
y exigen `Origin` como el resto de escrituras.

- **`POST /api/v1/imports/students/simulate`** (`students:create`) y **`POST /api/v1/imports/students/apply`** (`students:create`)
  responden `200` con `{ batch, rejections, rejectionsTruncated }`.
  - `batch`, en este orden: `id`, `kind` (`STUDENTS`), `status` (`VALIDATED` en la simulación, `APPLIED` en la aplicación), `fileSha256`
    (hexadecimal en minúsculas), `totalRows`, `validRows`, `rejectedRows`, `createdRows`, `skippedRows`, `createdAt`, `appliedAt`
    (`null` en la simulación). Siempre `totalRows = validRows + rejectedRows + skippedRows`; la simulación tiene `createdRows = 0` y la
    aplicación `createdRows = validRows`.
  - `rejections`: hasta 200 entradas `{ line, field, reason }` (la cabecera es la línea 1). Primero los defectos en orden de línea y
    después las filas omitidas por `already-imported`. `rejectionsTruncated` es `true` si había más de 200. Una fila con varios defectos
    cuenta una vez en `rejectedRows` pero lista cada campo. **No se guardan**: solo viajan en esta respuesta.
  - `reason` es uno de `required`, `invalid-format`, `too-long`, `invalid-date`, `future-birth-date`, `duplicate-in-file`,
    `duplicate-national-id`, `already-imported`. `field` es una columna de la plantilla o `row` (la fila no tiene las celdas de la cabecera,
    con `invalid-format`).
  - **`already-imported` no es un rechazo**: cuenta en `skippedRows`, no en `rejectedRows` (salvo que la misma fila tenga además un
    defecto). Reaplicar el mismo archivo devuelve `createdRows: 0`, `skippedRows: N`, `rejectedRows: 0` y N entradas `already-imported`.
- **`GET /api/v1/imports`** (`students:read`) devuelve `{ items, total, page, size }` con los lotes más recientes primero. `page` desde 0,
  `size` 50 por defecto y máximo 100 (fuera de rango es `400 validation-failed`). Cada elemento tiene la forma de `batch`; no lleva rechazos.
- **Errores**
  - `400 validation-failed` con la lista `errors` (`field`, `reason`) cuando el archivo no se puede leer como importación:
    `file` con `empty-file`, `no-rows` (solo cabecera), `too-large` (más de 5 MB), `too-many-rows` (más de 10 000 filas de datos),
    `malformed-csv` e `invalid-encoding` (no es UTF-8 o tiene NUL); `<columna>` con `missing-column` (una entrada por cada obligatoria que
    falta: `legacyId`, `firstName`, `lastName`, `birthDate`); `columns[n]` con `unknown-column` (columna fuera de la plantilla o repetida,
    **por posición, nunca por el texto de la cabecera**: un archivo sin cabecera pondría a un estudiante en la respuesta).
  - Archivo de más de 5 MB (lo corta el borde), parte `file` ausente: `400 validation-failed` sin `errors`. Petición que no es multipart:
    `415 unsupported-media-type`.
  - `409 import-not-simulated`: la cuenta nunca simuló un archivo con esa huella. `409 import-file-changed`: el último lote de la cuenta es una
    simulación y es de otro archivo (un solo byte de diferencia es otro archivo). Ambos sin efecto alguno.
  - `403 forbidden` (sin `students:create`; con `students:read` sí se lista) y `403 csrf-invalid` sin `Origin`; `401`; `429 too-many-requests` con
    `Retry-After`.
- Plantilla: cabecera obligatoria `legacyId`, `firstName`, `lastName`, `birthDate` (`YYYY-MM-DD`), opcionales `nationalId` y `sex`
  (`F`, `M`, `UNSPECIFIED`; vacío o ausente es `UNSPECIFIED`). Cualquier orden de columnas, separador `,` o `;` detectado en la primera línea,
  BOM aceptado. La cabecera de la plantilla vacía es `legacyId,firstName,lastName,birthDate,nationalId,sex`
  (`StudentsCsv.TEMPLATE_HEADER`); la descarga es del frontend.

### Decisiones tomadas al construir y desviaciones de la nota

- **La aplicación no actualiza el lote de la simulación: inserta un lote `APPLIED` nuevo** que apunta a la simulación (`simulation_id`). La
  tabla queda de solo inserción (sin `UPDATE` ni `DELETE`), y por eso la simulación sigue valiendo: el mismo archivo se puede reaplicar
  (la nota pide que reaplicar no cree nada) sin simular otra vez. El historial muestra ambos lotes. La columna `simulation_id` es nueva respecto de la nota.
- **`import-not-simulated` y `import-file-changed`** se distinguen por el último lote de la cuenta: si no hay una simulación suya con esa huella y el último
  lote de esa cuenta e institución es una simulación, es `import-file-changed`; en cualquier otro caso, `import-not-simulated`. La simulación de otra
  cuenta o de otra institución no cuenta.
- **Columnas**: se aceptan `sex` (opcional; el registro lo exige, aquí si falta es `UNSPECIFIED`) y `nationalId`. **No se acepta `studentCode`**
  (el código siempre se genera, `E-000001`...; `legacyId` es la referencia con el sistema de origen): sería una columna desconocida.
- **Referencia externa**: `students_student.external_ref VARCHAR(64)` nullable, con `CHECK` (no vacía ni con espacios en los bordes) e índice único
  `(institution_id, external_ref)` (los NULL son distintos). Solo la lee `students`; el rol de solo lectura no la recibe.
- **Reglas por campo**: viven en `students.domain.StudentFieldCheck` y reutilizan `Names` (longitud 100), `Sex`, `NationalId` y la ventana de fecha del
  registro (no antes de 1990-01-01, no posterior a hoy en Honduras); la referencia admite hasta 64 caracteres. Fecha con otro formato:
  `invalid-format`; inexistente (`2013-02-29`) o anterior a 1990: `invalid-date`; posterior a hoy: `future-birth-date`. Documento de más de 20
  caracteres: `too-long`; de otro formato: `invalid-format`. **Único endurecimiento propio de la importación**: un carácter de control en
  cualquier celda (tabulación, salto de línea dentro de una celda entre comillas) es `invalid-format`.
- **`duplicate-in-file`** marca la fila posterior, tanto para `legacyId` como para el documento (comparado normalizado, con o sin guiones).
  `already-imported` se decide por `legacyId` y, en esa fila, no se compara el documento. `duplicate-national-id` busca el índice ciego bajo todas las
  versiones de llave configuradas (puerto `NationalIdIndexes`, el mismo servicio que usa el alta manual).
- **Escritura por bloques**: 100 estudiantes por transacción. Si un bloque falla por un conflicto de índice único (referencia o documento tomados
  entre la validación y la escritura, por ejemplo dos aplicaciones simultáneas), el bloque se revierte entero y sus filas se reintentan una por una,
  cada una en su transacción: el conflicto es `already-imported` (omitida) o `duplicate-national-id` (rechazada) para esa fila y las demás se
  crean. Cualquier otro fallo se propaga (`500`) y los bloques ya confirmados quedan (reaplicar continúa donde quedó). Las entradas de auditoría
  de los estudiantes son exactamente las del alta manual (`students.student.created`).
- **Auditoría** `imports.batch.simulated|applied`: estado posterior con `kind`, `status`, `fileSha256` y los cinco conteos; sin nombres, referencias,
  documentos ni valores.
- **Archivo en el borde**: `spring.servlet.multipart` con `max-file-size: 5MB`, `max-request-size: 6MB` y `file-size-threshold: 5MB` (nunca a disco); el caso de
  uso vuelve a comprobar 5 MB y 10 000 filas. El archivo se lee a memoria durante la llamada y no se guarda, registra ni devuelve.
- **Analizador CSV propio** (`imports.domain.CsvParser`, unos 170 renglones, sin dependencia nueva): `,` o `;` según la primera línea fuera de comillas,
  comillas dobles con `""`, saltos de línea dentro de celdas entre comillas (el número de línea de las filas siguientes los cuenta), `\r\n`, `\n` y `\r`, líneas
  en blanco omitidas; una comilla suelta o una celda sin cerrar es `malformed-csv`. Nunca interpreta una celda como fórmula (`=`, `+`, `-`, `@` son texto) y
  no recorta nada (recorta `students`).
- **Límites de tasa**: dos políticas nuevas en `confia.web.rate-limit`, `admin-import` (por dirección) y `admin-import-account` (por cuenta), 20 por hora cada una,
  compartidas por `simulate` y `apply`; el historial usa `admin-read` y `admin-read-account`.
- Códigos de problema nuevos: `import-not-simulated` y `import-file-changed` (ambos 409), en `ProblemCode` y `problems.properties`. El frontend debe sumarlos a
  `KNOWN_PROBLEM_CODES` y a su catálogo.
- `apps/api/openapi/admin.openapi.json` regenerado (tres operaciones y los esquemas `ImportRunResponse`, `ImportBatchResponse`,
  `ImportBatchPageResponse` y `RejectionResponse`). **`packages/contracts` no se regeneró** (queda para el corte de frontend).
- Abierto: el texto de `docs/03-seguridad.md` sección 10 no menciona aún la cuota de importaciones.

### Pruebas

`CsvParserTest`, `StudentsCsvTest`, `ImportBatchTest` (dominio de `imports`); `StudentFieldCheckTest` (cada motivo y su acuerdo con las reglas del registro);
`StudentImportTest` (puerto de `students` con puertos en memoria: motivos, orden de líneas, aislamiento por institución, bloques, reintento fila por fila,
misma auditoría que el alta manual); `ImportStudentsTest` (caso de uso: conteos que suman, límites de 5 MB y 10 000 filas, BOM, `;`, codificación, cabecera,
tope de 200 rechazos, simulación obligatoria, archivo cambiado, reaplicar, conflictos tardíos, auditoría y lote sin datos del archivo, historial);
`ImportsBatchSchemaIT` (restricciones, RLS, privilegios de solo inserción, `external_ref` único por institución);
`ImportEndpointsIT` (criterio de aceptación con 100 filas y 5 defectuosas, reaplicar sin crear, aplicar sin simular, archivo cambiado, tres aplicaciones
simultáneas, aislamiento, `403` sin efecto, `401`, `Origin`, límites de tasa por dirección y por cuenta, 5 MB + 1, 10 000 y 10 001 filas, y barrido de respuestas,
filas de auditoría, filas de lote y registros contra nombres, referencias, documentos y su cifrado). Actualizadas las pruebas de listas de rutas:
`PermissionedEndpointsTest`, `CurrentSessionEndpointIT`, `PublicRouteAllowListTest`, `AuthorizationEdgeIT`, `ProcessBeanPolicy`,
`WebEdgeScopeExclusionInventoryTest`, `OpenApiContractSnapshotTest`, `ProblemCodeTest`, `RateLimitPropertiesTest` y `ProcessBeanIsolationTest` (políticas nuevas).

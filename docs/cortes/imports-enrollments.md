# Corte: importación de matrículas (`imports`)

Fase F1, entregable 6 (herramienta de migración), tercer corte. Rama `feat/imports-enrollments`, apilada sobre
`feat/imports-guardians` (PR #310). Mismas decisiones de `imports-students.md` e `imports-guardians.md`: CSV UTF-8, el archivo y
sus filas no se guardan, la aplicación exige el mismo archivo ya simulado (huella SHA-256 por cuenta y tipo), reporte de
rechazos por línea, campo y motivo sin valores, auditoría sin datos personales, mismos límites de tamaño, filas y tasa.

## Objetivo

Cargar la matrícula **vigente** del año lectivo desde el sistema actual, sobre los estudiantes ya importados y la estructura
académica ya configurada, para poder conciliarla (siguiente corte) contra el listado oficial.

## Fuera de alcance

Matrículas históricas ya cerradas (retiradas, trasladadas o graduadas) y el historial de traslados de sección: solo entran
matrículas `ACTIVE`. Matrícula masiva desde la pantalla de estudiantes. Acta de conciliación (siguiente corte).

## Archivo (`kind = ENROLLMENTS`)

Columnas: `studentLegacyId`, `academicYear` (nombre), `modality` (nombre), `grade` (nombre), `section` (nombre),
`enrolledOn` (`YYYY-MM-DD`). Las secciones no tienen id de origen: se resuelven por **nombre** (año lectivo, modalidad, grado y
sección, sin distinguir mayúsculas ni espacios sobrantes) mediante el puerto de lectura que `organization` ya publica
(`SectionDirectory`; ampliarlo con la búsqueda por nombres si hace falta, sin importar su dominio). El estudiante se resuelve
por `studentLegacyId` contra `students_student.external_ref` (corte 1).

## Reglas

Cada fila vuelve a pasar por **el mismo caso de uso de matricular** del corte de matrículas (INV-31, cupo bajo bloqueo de la
sección, año no cerrado, sección activa, estudiante activo, fecha dentro del año y no futura, auditoría), sin ruta paralela.

- La **simulación** no escribe y no toca documentos. Valida lo comprobable sin escribir: formato, `studentLegacyId` conocido
  (`unknown-student`), sección resuelta (`unknown-section`, o `ambiguous-section` si el nombre no es único), fecha, repetido en el
  archivo (`duplicate-in-file`: mismo estudiante y año), estudiante inactivo, sección inactiva, año cerrado, y un **cupo estimado**
  con las matrículas activas existentes más las filas del archivo en orden (`section-full`).
- La **aplicación** repite todo bajo las reglas reales: el cupo y INV-31 mandan al escribir (las filas que no caben se rechazan
  `section-full` por orden de archivo).
- **Idempotencia:** si el estudiante ya tiene una matrícula `ACTIVE` en ese año y **en esa misma sección**, la fila es
  `already-imported` (omitida, no es error). Si la tiene en **otra** sección, `duplicate-active-enrollment` (rechazada): la
  importación nunca traslada ni reemplaza una matrícula existente.
- Cada fila es su propia transacción corta (la sección se bloquea por fila): un rechazo no afecta a las demás; el cupo no se
  excede ni con aplicaciones simultáneas.

Motivos nuevos (código estable, campo y línea, nunca el valor): `unknown-section`, `ambiguous-section`, `section-full`,
`duplicate-active-enrollment`, `academic-year-closed`, `inactive-reference`, `student-not-active`, `enrollment-date-out-of-range`
y los ya existentes (`required`, `invalid-format`, `invalid-date`, `unknown-student`, `duplicate-in-file`, `already-imported`).

## API

Misma forma que los otros tipos: `POST /api/v1/imports/enrollments/simulate` y `/apply` (multipart, campo `file`) con permiso
`STUDENTS_CREATE`; `GET /api/v1/imports` acepta `kind=ENROLLMENTS` con `STUDENTS_READ`. Migración solo para permitir el tipo
en `imports_batch`. Auditoría `imports.batch.simulated|applied|failed` con tipo, conteos y huella; las matrículas creadas
auditan como las manuales. Ampliar la lista de rutas multipart permitidas y todas las listas de rutas de las pruebas.

## Pantalla (`admin-web`)

El selector de tipo de «Importar datos» suma «Matrículas» (orden recomendado: estudiantes, encargados, vínculos, matrículas),
con su plantilla descargable y los textos de los motivos nuevos. Nada del archivo se muestra ni se guarda.

## Casos límite a probar

Sección inexistente, ambigua (mismo nombre en dos modalidades), con espacios y mayúsculas distintas; año cerrado; sección
inactiva; estudiante inactivo o desconocido; fecha un día antes del inicio del año, el último día y un día después, y futura;
mismo estudiante dos veces en el archivo; ya matriculado en la misma sección (omitido) y en otra (rechazado); cupo exacto
(25 de 25 y la fila 26), cupo con filas del archivo y matrículas previas, dos aplicaciones simultáneas con un solo cupo libre;
reaplicar el archivo (todo omitido); aislamiento entre instituciones; 403 sin efecto; límites de tasa; ausencia de datos
personales en bitácora, logs, lotes y respuestas.

## Criterio de aceptación

Con 100 estudiantes importados y la estructura académica creada, un administrador importa 100 matrículas con 7 defectuosas (por
ejemplo, una sección que no existe, una que ya está llena y una fecha fuera del año), ve exactamente esas 7 por línea y motivo,
corrige, aplica, y cada estudiante queda matriculado en su sección sin exceder ningún cupo; reaplicar el archivo no cambia nada.

## Lo construido

- **Backend (`students`, `organization`, `imports`).** `EnrollmentImportRules` (dominio, puro) juzga las filas; `EnrollmentImport`
  resuelve estudiantes y secciones y matricula cada fila con `ManageEnrollments.enroll`, una transacción por fila y en el orden del
  archivo. `SectionDirectory.findByNames` resuelve la sección por nombres (la comparación sin mayúsculas ni espacios se hace en Java,
  no en SQL, para no depender de la colación). `EnrollmentRepository.activeSeatsOf` entrega las matrículas activas por estudiante y año.
  Migración V23 (solo amplía `imports_batch_kind_chk`). Rutas `POST /api/v1/imports/enrollments/simulate|apply` con `students:create`;
  el historial `kind=enrollments` exige `students:read`. Contrato OpenAPI y motivos del rechazo ampliados.
- **Pantalla.** «Matrículas» al final del selector, plantilla `plantilla-matriculas.csv` con una fila de ejemplo y textos es-HN de los
  motivos nuevos; recorrido e2e con 100 matrículas y 7 defectuosas, y tres estados nuevos en la matriz de axe.
- **Decisiones.** Las seis columnas son obligatorias. El cupo de la simulación es una estimación; manda el cupo real al escribir. Si
  la matrícula aparece entre la lectura y la escritura, se relee: misma sección es `already-imported`, otra es
  `duplicate-active-enrollment`. La importación nunca traslada ni reemplaza una matrícula.
- **Verificación.** Pruebas unitarias de reglas (14) y del caso de uso (29), `ManageImportsTest` (51), `ImportEnrollmentsEndpointsIT`
  (12, incluida la carrera por el último cupo) y `ImportGuardiansEndpointsIT` (14); vitest (526), Playwright de importaciones (23) y la
  puerta de axe de `/imports` (32). No se corrieron PIT ni la cobertura global; los límites de tasa de las rutas nuevas no tienen
  prueba propia (los cubre el limitador compartido).

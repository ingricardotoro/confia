# Corte: acta de conciliación de la migración (`imports`)

Fase F1, entregable 6 (herramienta de migración), cuarto y último corte. Rama `feat/imports-reconciliation`, apilada sobre
`feat/imports-enrollments` (PR #311). Cierra el criterio de salida de F1: «la matrícula completa del año lectivo vigente está cargada y
conciliada contra el listado oficial de la institución, con acta firmada» (`docs/09-roadmap-y-fases.md`, F1).

## Objetivo

Que un administrador pueda **firmar un acta** que deje constancia, en un solo registro inmutable, de lo que la migración dejó en el
sistema para un año lectivo: cuántos registros se cargaron y cuántos quedaron rechazados por tipo, cuántas matrículas activas hay y
cuántas dice el listado oficial. El acta es un registro; **no compara archivos** (decisión del propietario).

## Fuera de alcance

Subir el listado oficial y compararlo fila a fila; editar, anular o borrar un acta (se firma otra); firma electrónica o PDF del acta
(el registro es el acta; la impresión y la firma en papel quedan fuera); texto libre de observaciones (podría contener datos de menores).

## Modelo

`imports_reconciliation` (migración V24, solo inserción): `id`, `institution_id`, `academic_year_id`, `official_reference` (el
**número del documento** del listado oficial, p. ej. «Oficio 123-2026»: solo letras, números, espacios y `. , / # ° _ -`, al menos un
número, sin nada con forma de número de identidad; hasta 100 caracteres), `official_enrollment_count` (entero de 0 a 1 000 000, el
total que declara el listado), `enrollments_active` (matrículas `ACTIVE` del año al firmar), por cada tipo de importación
(`students`, `guardians`, `guardian-links`, `enrollments`) `created_*` y `rejected_*`, `signed_by` (cuenta), `signed_at`.
Inmutable por **permisos** (`INSERT` y `SELECT` solamente) y por **disparadores** que rechazan `UPDATE`, `DELETE` y `TRUNCATE` aun
al propietario, como `guardians_guardian_consent_event`. Conteos con `CHECK >= 0`. La referencia es texto libre que queda para siempre
en un registro que nadie corrige: por eso se restringe a un número de documento y no se copia a la bitácora (el identificador del acta
lleva a ella); ninguna regla impide que alguien escriba un nombre junto con un número, y la pantalla pide no hacerlo.

Regla de los conteos: `created_*` es la suma de `createdRows` de los lotes `APPLIED` del tipo; `rejected_*` es el `rejectedRows` del
**último** lote `APPLIED` del tipo (lo que quedó sin cargar la última vez; sumar todos contaría dos veces lo que se corrigió y volvió a
aplicar). `enrollments_active` se lee en vivo del módulo de matrículas por un puerto de lectura, no del lote. La diferencia
`enrollments_active − official_enrollment_count` no se almacena: se deriva al mostrar.

**Alcance de los conteos:** un lote no guarda el año lectivo, así que `created_*` y `rejected_*` son los de **toda la institución**; solo
`enrollments_active` es del año del acta. Con una sola carga de matrículas por institución y año (el caso de F1) coinciden; si se
importan matrículas de dos años, el acta de cada uno suma ambos. Guardar el año en los lotes de matrículas es un corte aparte.

## API

- `POST /api/v1/imports/reconciliations` (JSON: `academicYearId`, `officialReference`, `officialEnrollmentCount`), permiso
  `STUDENTS_CREATE`, límite de tasa como las demás escrituras de importación. El servidor calcula los conteos; el cliente no los envía.
  Respuesta `201` con el acta. Año inexistente o de otra institución: `404` (la misma respuesta, sin distinguir); referencia que no es un
  número de documento, total fuera de 0 a 1 000 000 o un miembro desconocido en el cuerpo: `400`.
- `GET /api/v1/imports/reconciliations/preview?academicYearId=` con permiso `STUDENTS_READ`: los conteos que se registrarían al firmar,
  sin escribir nada (`404` si el año no es de la institución).
- `GET /api/v1/imports/reconciliations` (paginado, el más reciente primero) con permiso `STUDENTS_READ`.
- Los conteos de encargados y vínculos se ven con `STUDENTS_READ`, a diferencia del historial de lotes que los oculta sin
  `GUARDIANS_READ`: son cifras agregadas sin datos personales y el acta es un solo documento (excepción aceptada).
- Auditoría `imports.reconciliation.signed` con año y conteos (el firmante es el actor del asiento); sin la referencia, sin referencias de
  estudiantes, sin documentos ni nombres.
- Reutiliza los permisos existentes (supuesto: no se crea un permiso nuevo; si el propietario quiere separar quién firma, es un corte
  aparte de identidad).

## Pantalla (`admin-web`)

En «Importar datos», una sección «Acta de conciliación»: selector de año lectivo, referencia del listado oficial, total oficial; antes de
firmar muestra los conteos que el servidor calculará (vista previa) y la diferencia con el listado; al firmar aparece en el historial de
actas con fecha, año lectivo, referencia, total oficial, matrículas activas y diferencia. **La cuenta firmante queda en el registro, en la
respuesta y en la bitácora, pero el historial no la muestra**: el identificador de cuenta no es legible y `imports` no tiene un puerto que
la nombre (mostrarla es un seguimiento de `identity`). Estados de carga, vacío, error y éxito; textos en el catálogo es-HN; sin
violaciones críticas de axe.

## Casos límite a probar

Año sin ningún lote ni matrícula (todo en cero, se puede firmar); matrículas del año que no coinciden con el total oficial (diferencia
positiva y negativa, se firma igual y queda derivable); total oficial 0 y negativo; referencia vacía, con espacios, de 1 y del máximo y
un carácter más; varias actas del mismo año (todas se conservan); último lote `APPLIED` con rechazos y lotes anteriores con otros
conteos; lotes `VALIDATED` que no cuentan; un año de otra institución (404, sin efecto); 403 sin efecto; límite de tasa; imposibilidad
de actualizar y borrar a nivel de base de datos; ausencia de datos personales en bitácora, logs y respuestas.

## Criterio de aceptación

Con los cuatro tipos ya importados para el año vigente, un administrador elige el año, escribe la referencia del listado oficial y su
total, ve los conteos de la migración y la diferencia con el listado, firma, y el acta queda en el historial con su fecha (y su firmante
en el registro y la bitácora); firmar otra vez conserva ambas, y ninguna puede cambiarse ni borrarse, ni siquiera por el propietario de
la base de datos.

## Lo construido

- **Backend (`imports`, con puertos de `organization` y `students`).** Migración V24 (`imports_reconciliation`, con permisos y disparadores
  de solo inserción). `Reconciliation` (dominio) con la regla de la referencia; `ManageReconciliations` calcula los conteos
  (`ImportBatchRepository.appliedTotals`, `SectionDirectory.academicYearExists`, `EnrollmentCensus.activeInYear`), firma, lista y
  previsualiza; `ReconciliationController` con las tres rutas, permisos `students:create|read` y los límites de tasa `admin-write` y
  `admin-read`. Contrato OpenAPI regenerado (50 operaciones, 43 con permiso).
- **Pantalla.** Sección «Acta de conciliación» entre el asistente y el historial de lotes: año, referencia y total, vista previa con la
  diferencia en palabras, firma, y historial paginado de actas; estados de carga, vacío, error y éxito.
- **Decisiones.** Los conteos son de la institución, no del año (ver arriba). La referencia es un número de documento y no entra a la
  bitácora. Sin ocultar la sección por permiso en el cliente (la sesión no trae permisos; sin permiso se muestra el 403 con el texto
  existente). El botón «Firmar acta» no se deshabilita por inválido: el envío muestra el resumen de errores. El servidor trunca un total
  `1.5` a `1` (comportamiento general del mapeador JSON del proyecto, no de esta ruta).
- **Revisiones.** `confia-security-auditor`: sin críticos ni altos; M1 (referencia de texto libre) y M2 (disparadores) corregidos; B2
  cubierto por la lista blanca; B1 aceptado. `confia-code-reviewer`: bloqueante B1 (firmante y nota) resuelto en la nota; I1 (disparadores),
  I2 (alcance de los conteos, documentado) e I3 (límite de tasa, probado) resueltos; S2 corregido; S1 (acción vacía de un estado
  inalcanzable) y S3 (agregación en SQL) quedan como seguimiento.
- **Verificación.** Ver el cuerpo del PR.

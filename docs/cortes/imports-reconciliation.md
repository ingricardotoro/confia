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

`imports_reconciliation` (migración V24, solo inserción): `id`, `institution_id`, `academic_year_id`, `official_reference` (texto corto:
el identificador del listado oficial, p. ej. el número de oficio; sin datos personales), `official_enrollment_count` (entero ≥ 0, el
total que declara el listado), `enrollments_active` (matrículas `ACTIVE` del año al firmar), por cada tipo de importación
(`students`, `guardians`, `guardian-links`, `enrollments`) `created_*` y `rejected_*`, `signed_by` (cuenta), `signed_at`.
Una restricción impide `UPDATE` y `DELETE` como en las demás tablas que no se borran. Conteos con `CHECK >= 0`.

Regla de los conteos: `created_*` es la suma de `createdRows` de los lotes `APPLIED` del tipo; `rejected_*` es el `rejectedRows` del
**último** lote `APPLIED` del tipo (lo que quedó sin cargar la última vez; sumar todos contaría dos veces lo que se corrigió y volvió a
aplicar). `enrollments_active` se lee en vivo del módulo de matrículas por un puerto de lectura, no del lote. La diferencia
`enrollments_active − official_enrollment_count` no se almacena: se deriva al mostrar.

## API

- `POST /api/v1/imports/reconciliations` (JSON: `academicYearId`, `officialReference`, `officialEnrollmentCount`), permiso
  `STUDENTS_CREATE`, límite de tasa como las demás escrituras de importación. El servidor calcula los conteos; el cliente no los envía.
  Respuesta `201` con el acta. Año inexistente o de otra institución: `404`; referencia vacía o demasiado larga, conteo negativo: `400`.
- `GET /api/v1/imports/reconciliations` (paginado, el más reciente primero) con permiso `STUDENTS_READ`.
- Auditoría `imports.reconciliation.signed` con año, conteos y firmante; sin referencias de estudiantes, sin documentos ni nombres.
- Reutiliza los permisos existentes (supuesto: no se crea un permiso nuevo; si el propietario quiere separar quién firma, es un corte
  aparte de identidad).

## Pantalla (`admin-web`)

En «Importar datos», una sección «Acta de conciliación»: selector de año lectivo, referencia del listado oficial, total oficial; antes de
firmar muestra los conteos que el servidor calculará (vista previa) y la diferencia con el listado; al firmar aparece en el historial de
actas con fecha y cuenta. Estados de carga, vacío, error y éxito; textos en el catálogo es-HN; sin violaciones críticas de axe.

## Casos límite a probar

Año sin ningún lote ni matrícula (todo en cero, se puede firmar); matrículas del año que no coinciden con el total oficial (diferencia
positiva y negativa, se firma igual y queda derivable); total oficial 0 y negativo; referencia vacía, con espacios, de 1 y del máximo y
un carácter más; varias actas del mismo año (todas se conservan); último lote `APPLIED` con rechazos y lotes anteriores con otros
conteos; lotes `VALIDATED` que no cuentan; un año de otra institución (404, sin efecto); 403 sin efecto; límite de tasa; imposibilidad
de actualizar y borrar a nivel de base de datos; ausencia de datos personales en bitácora, logs y respuestas.

## Criterio de aceptación

Con los cuatro tipos ya importados para el año vigente, un administrador elige el año, escribe la referencia del listado oficial y su
total, ve los conteos de la migración y la diferencia con el listado, firma, y el acta queda en el historial con su fecha y firmante;
firmar otra vez conserva ambas, y ninguna puede cambiarse ni borrarse.

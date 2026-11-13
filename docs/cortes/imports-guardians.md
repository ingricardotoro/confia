# Corte: importación de encargados y vínculos (`imports`)

Fase F1, entregable 6 (herramienta de migración), segundo corte. Rama `feat/imports-guardians`, apilada sobre
`feat/imports-students` (PR #309). Mismas decisiones del primer corte (`imports-students.md`): CSV UTF-8, el archivo y sus
filas no se guardan, la aplicación exige el mismo archivo ya simulado (huella SHA-256 por cuenta), idempotencia por
referencia externa, reporte de rechazos por línea, campo y motivo sin valores, y los documentos ya guardados se comprueban
**solo al aplicar** (decisión A2 de la auditoría).

## Objetivo

Cargar desde el sistema actual los encargados y sus vínculos con los estudiantes ya importados, con el reparto de
responsabilidad financiera, y dejar el reporte para que la administración lo revise.

## Dos archivos, en este orden

1. **Encargados** (`kind = GUARDIANS`): `legacyId`, `firstName`, `lastName`, `nationalId` (opcional), `rtn` (opcional),
   `email`, `phone`, `address`, `preferredChannel` (opcionales). Cada fila entra por **el mismo caso de uso del alta manual**
   (código `P-000001`, documento y RTN cifrados con índice ciego, auditoría). Nueva columna `guardians_guardian.external_ref`
   con índice único `(institution_id, external_ref)`. Reaplicar omite lo ya importado (`already-imported`).
2. **Vínculos** (`kind = GUARDIAN_LINKS`): `studentLegacyId`, `guardianLegacyId`, `relationship` (`FATHER`, `MOTHER`,
   `LEGAL_GUARDIAN`, `SELF`, `OTHER`), `financialResponsibilityPercent`, `isPrimaryPayer`, `isBillingRecipient` (`true`/`false`,
   vacío = `false`). Los ids de origen se resuelven por `external_ref` contra estudiantes y encargados ya importados.

## Reglas de los vínculos (INV-32)

- Las filas se **agrupan por estudiante** y cada grupo se valida como el conjunto completo que exige el registro manual:
  suma exactamente 100.00 con aritmética decimal exacta (nunca `double`), a lo sumo un pagador principal y un destinatario
  de factura, sin encargado repetido, porcentajes de 0.01 a 100 con dos decimales. Un grupo inválido se rechaza **entero**: todas sus líneas con el motivo
  (`sum-not-100`, `multiple-primary-payer`, `multiple-billing-recipient`, `duplicate-guardian`, `invalid-percent`).
- Motivos propios: `unknown-student` y `unknown-guardian` (el `legacyId` no está importado), `student-already-linked` (el
  estudiante ya tiene vínculos vigentes, hechos a mano o importados: la importación **nunca reemplaza** vínculos existentes),
  `guardian-not-active`, `student-not-active`, `duplicate-in-file`.
- Al aplicar, cada grupo válido se registra con el mismo caso de uso que el `PUT` del conjunto de vínculos (bloqueo de la
  fila del estudiante, historial, auditoría, restricción diferida de la base de datos), en una transacción por estudiante:
  un fallo de un estudiante no afecta a los demás. La simulación no escribe.

## API

Se amplían las rutas del corte anterior con el tipo: `POST /api/v1/imports/{kind}/simulate` y `/apply` con `kind` en
`students`, `guardians`, `guardian-links` (las rutas de estudiantes no cambian de forma). Permisos: encargados y vínculos
`GUARDIANS_CREATE` (vínculos además exigen `GUARDIANS_UPDATE`); `GET /api/v1/imports` ahora con filtro `kind` y
`GUARDIANS_READ` o `STUDENTS_READ` según el tipo. Mismos límites de tasa, de tamaño y de filas; mismas rutas multipart
permitidas (ampliar la lista del filtro a las nuevas). Auditoría `imports.batch.simulated|applied|failed` con tipo,
conteos y huella, **sin nombres, documentos, contactos ni valores**.

## Pantalla (`admin-web`)

La pantalla «Importaciones» ofrece elegir el tipo (Estudiantes, Encargados, Vínculos) con su plantilla descargable y el
orden recomendado (estudiantes, encargados, vínculos). Reutiliza el asistente de pasos, el reporte de rechazos y el
historial (con columna y filtro por tipo). Ningún dato del archivo se muestra ni se guarda.

## Casos límite a probar

Encargado con documento repetido en el archivo y ya existente (solo al aplicar), RTN inválido, contacto sin formato,
reaplicar el archivo de encargados, vínculos que suman 99.99, 100.01, 100.00 con 33.33+33.33+33.34, dos pagadores principales,
encargado repetido en el grupo, `legacyId` desconocido, estudiante con vínculos previos, encargado o estudiante inactivo,
grupo inválido que no impide a los válidos, reaplicar el archivo de vínculos (`student-already-linked` por idempotencia: debe
reportarse como omitido y no como error), dos aplicaciones simultáneas, aislamiento entre instituciones, 403 sin efecto,
límites de tasa, ausencia de datos personales en bitácora, logs, lotes y respuestas.

## Criterio de aceptación

Con 100 estudiantes importados, un administrador importa 80 encargados y 120 vínculos con 6 grupos defectuosos (por ejemplo,
suma 90), ve exactamente esos grupos rechazados, corrige, aplica, y cada estudiante queda con vínculos que suman 100; reaplicar
los archivos no cambia nada.

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

## Construido en el backend

Se amplió el módulo `com.confia.imports` con dos tipos nuevos, `GUARDIANS` y `GUARDIAN_LINKS`, sobre el mismo analizador CSV, la misma tabla de
lotes, el flujo simular y aplicar, la huella SHA-256 atada a cuenta y tipo, los límites de tasa (`admin-import`, `admin-import-account`), el filtro
multipart, la traza `imports.batch.failed` y los topes de 5 MB, 10 000 filas y 200 rechazos. Migración `V22__import_guardians_and_links.sql`:
`imports_batch.kind` admite los dos tipos y `guardians_guardian.external_ref` (`VARCHAR(64)`, nula, sin vacíos ni espacios en los bordes) con índice
único `(institution_id, external_ref)`. El rol de solo lectura no la recibe.

### Estructura

- `guardians` publica dos puertos como `@NamedInterface` (`guardians.application.importing`, consumidor `imports`): `GuardianImportGateway`
  (`evaluate`, `createAll`) y `GuardianLinkImportGateway` (`evaluate`, `applyAll`). `imports` no importa dominio ajeno ni lee tablas ajenas.
- Encargados (`GuardianImport`): cada fila entra por `ManageGuardians` (`createImported` en bloques de 100 y `createImportedAlone` fila por fila si un
  bloque choca; código `P-000001`, documento y RTN cifrados, índice ciego, auditoría). Un documento ya guardado solo se descubre al aplicar y deja
  una entrada `denied` sin valores, como el alta manual; la simulación nunca consulta documentos. `already-imported` se decide al simular por
  `legacyId` (`INSERT ... ON CONFLICT DO NOTHING`).
- Vínculos (`GuardianLinkImport` + `LinkImportRules`, dominio puro): grupos por estudiante, juzgados con `ResponsibilitySplit.of` (INV-32, decimal
  exacto) y `LinkDraft`; se aplican con `ManageGuardianLinks.registerImported`, que comparte con `replace` el cuerpo `replaceLocked` (bloqueo del
  estudiante, bloqueo compartido de encargados, historial, auditoría, restricción diferida), una transacción por estudiante, y nunca reemplaza.
  Referencias por `StudentDirectory.findByExternalRefs` (método nuevo del puerto existente) y `GuardianRepository.findByExternalRefs`.
- `ImportStudents` pasó a `ManageImports` con un `ImportProcessor` por tipo y una plantilla `CsvTemplate` por tipo (`StudentsCsv` conserva su API).

### Forma de la API

Respuestas iguales a estudiantes (`{ batch, rejections, rejectionsTruncated }`, `batch.kind` `GUARDIANS` o `GUARDIAN_LINKS`, `no-store`, `409
import-not-simulated` / `import-file-changed` por cuenta, tipo y huella).

- Rutas literales por tipo: `/api/v1/imports/{students|guardians|guardian-links}/{simulate|apply}`. Permisos: estudiantes `students:create`; encargados
  `guardians:create`; vínculos `guardians:create` y `guardians:update`. `GET /api/v1/imports?kind=` (borde `students:read`; los tipos de encargados
  además `guardians:read`; `kind` desconocido `400`, no legible `403`).
- Encargados: obligatorias `legacyId`, `firstName`, `lastName`; opcionales `nationalId`, `rtn`, `email`, `phone`, `address`, `preferredChannel`.
  Motivos: `required`, `invalid-format`, `too-long`, `duplicate-in-file`, `already-imported` (omitida), `duplicate-national-id` (solo al aplicar).
- Vínculos: obligatorias `studentLegacyId`, `guardianLegacyId`, `relationship`, `financialResponsibilityPercent` (`\d{1,3}(\.\d{1,2})?`); opcionales
  `isPrimaryPayer`, `isBillingRecipient` (`true`/`false`, vacío `false`). Motivos: los de campo, `invalid-percent`, `unknown-student`,
  `unknown-guardian`, `student-not-active`, `guardian-not-active`, y sobre todas las líneas del grupo `sum-not-100`, `multiple-primary-payer`,
  `multiple-billing-recipient`, `duplicate-guardian`. `student-already-linked` cuenta como omitida (no rechazo).
- `admin.openapi.json` regenerado (45 operaciones, 38 con permiso). `packages/contracts` no se regeneró.

### Decisiones y desviaciones

- Rutas literales por tipo (no `/{kind}`) para no alterar las operaciones de estudiantes y declarar un permiso por ruta.
- `PermissionedEndpoint` gana `alsoRequired` y `PermissionDecider` lo respeta (todos deben cumplirse); solo lo usan las rutas de vínculos.
- Historial: el borde exige `students:read`; el controlador agrega `guardians:read` para los tipos de encargados con `PermissionResolver`. Una cuenta con
  solo `guardians:read` no podría listar (ningún rol actual es así).
- Defecto en una línea de un grupo: esa línea lleva su motivo y columna; las demás del grupo `group-rejected` con `field = row` (motivo nuevo).
  `invalid-percent` va solo en la línea defectuosa (la nota decía «todas»). `duplicate-in-file` no se emite para vínculos (es `duplicate-guardian`).
- `student-already-linked` es siempre omitida, también si los vínculos vigentes son manuales y distintos.
- Al aplicar, un estudiante o encargado desactivado tras la simulación rechaza el conjunto entero.

### Pruebas

Unitarias: `LinkImportRulesTest` (99.99, 100.01, 100.00 con 33.33 + 33.33 + 33.34, dos pagadores, dos destinatarios, encargado repetido, grupo rechazado entero,
`student-already-linked`, estudiante o encargado inactivo, referencias desconocidas), `GuardianFieldCheckTest`, `ImportKindTest`, `GuardianImportTest` (puertos con el
registro en memoria: bloques, reintento fila por fila, idempotencia, fallo parcial, conjuntos que nunca reemplazan, sin datos personales en la bitácora),
`ManageImportsTest` (casos de uso por tipo, huella por tipo, conteos, traza `failed`, historial por tipos y por ruta), `PermissionDeciderTest` (dos permisos),
`PermissionedEndpointsTest` y las listas de rutas (`PublicRouteAllowListTest`, `CurrentSessionEndpointIT`, `AuthorizationEdgeIT`, `OpenApiContractSnapshotTest`).
Integración: `ImportGuardiansEndpointsIT` (criterio de aceptación con 100 estudiantes, 80 encargados y 120 vínculos con 6 grupos defectuosos; documento ya
guardado solo al aplicar; tres aplicaciones simultáneas; aislamiento; 403 sin efecto; filtro por tipo; límites de tasa; sin datos personales), `ImportsBatchSchemaIT`
(tipos y `external_ref` de encargados).

Resultado (pruebas dirigidas, sin `verify` completo): 551 pruebas unitarias y de arquitectura sin fallos; 189 de integración sin fallos en la primera ronda
(`ImportEndpointsIT` 30, `ImportGuardiansEndpointsIT` 11, `StudentEndpointsIT` 37, `GuardianEndpointsIT` 30, `MultiTenantSchemaIT` 11, `CommittingBaseContractIT` 3,
`ImportsBatchSchemaIT` 14, `GuardiansGuardianSchemaIT` 16, `CurrentSessionEndpointIT` 12, `AuthorizationEdgeIT` 25) y 41 tras mover la lógica de `ImportKind` fuera del
controlador (los dos de importación). Cobertura de ramas (JaCoCo, informe regenerado de `jacoco-ut.exec`): `imports.domain` 100 %, `guardians.domain` 99.7 %,
`students.domain` 98.1 %.

### Correcciones de la auditoría de seguridad (backend)

- **M1**: en `GUARDIAN_LINKS` una fila con número de celdas distinto o sin referencia de estudiante utilizable (`required`, `invalid-format`, `too-long` en
  `studentLegacyId`) rechaza el archivo entero (`400 validation-failed`, `file` / `row-shape`), porque no se puede atribuir a un grupo y el resto del grupo
  podría registrarse incompleto. Un archivo así nunca tiene simulación, de modo que aplicarlo da `409 import-not-simulated` sin leerlo. Pruebas:
  `ManageImportsTest` y `ImportGuardiansEndpointsIT` (fila de 100 más fila mal formada del mismo estudiante: nada creado, ningún lote).
- **B1**: las rutas de vínculos declaran `x-also-required-permissions: ["guardians:update"]` además de `x-required-permission`; una prueba en
  `OpenApiRouteClassificationTest` compara cada operación con su `PermissionedEndpoint`, `alsoRequired` incluido.
- **B2**: `PermissionDecider` deja los permisos resueltos en el atributo de petición `PermissionDecider.RESOLVED_PERMISSIONS`; `ImportController` los lee y ya no
  tiene `PermissionResolver`.
- **B3**: `ImportGuardiansEndpointsIT` prueba, con un resolvedor sintético para cuentas elegidas (sustituye por reflexión el resolvedor del decisor; ningún rol real
  tiene `guardians:create` sin `guardians:update`), el `403` de las rutas de vínculos antes de leer el cuerpo y la denegación auditada con `guardians:update`, y el
  `403` del historial de un tipo no legible con la denegación `guardians:read`.
- **B4**: `ManageGuardians.createImported` corre dentro de la transacción del llamador y no abre otra, así un 40P01 llega al único componente transaccional, que
  reintenta el bloque. La prueba comprueba una sola confirmación por bloque; el reintento real ante 40P01 no se probó (el doble en memoria no lo produce).
- **I5**: descripción de las rutas de vínculos corregida. **I2**: `external_ref` de estudiantes y encargados en el inventario y en la tabla de anonimización de
  `docs/08-datos-privacidad-y-retencion.md`.

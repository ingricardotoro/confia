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

## Construido en el frontend

La pantalla «Importaciones» (`apps/admin-web/src/features/imports/`) ahora carga los tres tipos de archivo sobre el mismo asistente de cuatro
pasos, el mismo reporte de rechazos, el mismo resultado y el mismo historial. Nada del archivo se muestra ni se guarda (el `File` sigue viviendo
solo en el estado del contenedor; sin `localStorage`, caché de consultas ni parámetros de URL del archivo).

### Pantalla

- **Tipo y orden.** Un selector «Qué va a importar» (Estudiantes, Encargados, Vínculos) en el primer paso, con el orden recomendado como
  descripción asociada. El tipo es estado local de la pantalla (no va en la URL). Cambiar de tipo suelta el archivo, el resumen y el resultado, y
  el selector se bloquea mientras hay una petición en vuelo. Cada tipo tiene su etiqueta de campo, su ayuda de columnas, su plantilla vacía
  descargable (`plantilla-estudiantes.csv`, `plantilla-encargados.csv`, `plantilla-vinculos.csv`, con la cabecera del backend) y sus textos de
  resumen, confirmación, resultado y reintento. El título pasa a «Importar datos».
- **Confirmación de vínculos.** Explica que cada estudiante se valida con todas sus filas juntas (suma exacta de 100.00, a lo sumo un pagador
  principal y un destinatario de factura), que un defecto rechaza todo el grupo sin afectar a los demás estudiantes y que un estudiante con
  vínculos vigentes se omite: la importación nunca los reemplaza. Encargados conserva el aviso de que los documentos ya guardados se revisan solo
  al aplicar.
- **Motivos nuevos** en el catálogo es-HN: `invalid-percent`, `unknown-student`, `unknown-guardian`, `student-not-active`,
  `guardian-not-active`, `student-already-linked` (se lista como omitida, no como error), `group-rejected` (campo `row`), `sum-not-100`,
  `multiple-primary-payer`, `multiple-billing-recipient` y `duplicate-guardian`. `duplicate-national-id` pasa a «Ya hay un registro con ese
  documento de identidad» (vale para estudiantes y encargados). El nombre de un campo solo se muestra si es columna de la plantilla **del tipo**
  en pantalla (`fieldNameOf(field, kind)`); cualquier otro sale como «Sin campo». No hay códigos de problema nuevos: `KNOWN_PROBLEM_CODES` y
  `PROBLEM_KEYS` no cambian.
- **Historial.** Columna «Tipo» y filtro «Tipo de importación» (`?kind=students|guardians|guardian-links`, validado con Zod; un valor inválido se
  descarta). El filtro viaja al servidor como `kind`, reinicia la página y, si no deja filas, ofrece «Quitar filtros». Cada región paginada tiene
  nombre propio (rechazos de la simulación, rechazos del resultado, historial).

### Capa de datos

- `shared/api/import-operations.ts`: `simulateImport(route, token, file)` y `applyImport(route, token, file)` sobre
  `/api/v1/imports/{students|guardians|guardian-links}/{simulate|apply}`; `listImportBatches` no cambia (el `kind` va en los parámetros).
- `useSimulateImport(kind)` y `useApplyImport(kind)`: sin actualización optimista ni reintentos. Tras aplicar se invalida `importKeys.all` y el
  registro afectado (estudiantes, encargados, o ambos para vínculos).
- `OptionField` (organización) gana la propiedad opcional `disabled`.
- Se regeneró `packages/contracts` (ignorado por git). El OpenAPI declaraba `kind` solo como `STUDENTS` y los motivos sin los nuevos, de modo que
  el Zod del cliente habría rechazado cualquier respuesta de encargados o vínculos; se corrigió en el backend (`ImportBatchResponse`,
  `RejectionResponse` y `admin.openapi.json`).

### Simulador (`e2e/mock-api`) y pruebas

- `imports-model.ts` atiende los tres tipos. Encargados: obligatorios, RTN de 14 dígitos, correo, documento repetido en el archivo, `legacyId`
  ya importado (omitido) y documento ya guardado solo al aplicar. Vínculos: las filas se agrupan por estudiante y cada grupo se juzga entero con
  suma en centésimas enteras (sin `number` decimal; 33.33 + 33.33 + 33.34 pasa, 99.99 y 100.01 no), a lo sumo un pagador principal y un
  destinatario, sin encargado repetido; un defecto rechaza el grupo (`group-rejected`); las referencias se buscan entre lo que el escenario ya
  importó; un estudiante ya vinculado se reporta como omitido (`student-already-linked`). Los 409 y las simulaciones se llevan por tipo y el
  historial filtra por `kind` (desconocido, 400). No modela estudiantes ni encargados inactivos (eso se prueba en el backend).
- Vitest: `import-model`, `ImportWizard`, `ImportHistory`, `ImportsContainer` (MSW), `import-operations` y `imports-model` cubren tipos,
  plantillas, filtro, motivos nuevos y rutas.
- Playwright: `imports-guardians.e2e.ts` (criterio de aceptación con 100 estudiantes, 80 encargados y 120 vínculos con 6 grupos con suma 90,
  corrección, aplicación y reaplicación sin cambios; grupo rechazado entero por un porcentaje inválido; referencias desconocidas y orden;
  plantillas por tipo y soltar el archivo al cambiar de tipo; columna y filtro de tipo en el historial; nombres distintos de las paginaciones).
  Puerta de axe: 5 estados nuevos de `/imports` (`kind-guardian-links`, `links-simulated-with-group-rejections`, `dialog-apply-links`,
  `links-nothing-to-create`, `history-filtered-empty`).

### Puerta de axe en los diálogos de aplicar

- `dialog-apply` (estudiantes) y el nuevo `dialog-apply-links` fallaban con `color-contrast` en estado incompleto (`elmPartiallyObscuring`), en los dos
  temas. La causa, leída del detalle de axe-core 4.14: el aviso del diálogo ocupaba dos líneas de texto y axe compara la pila de elementos de cada
  línea hasta el fondo de la página (el diálogo está sobre un fondo semitransparente); detrás, un elemento de la pantalla (el contenedor del botón
  «Aplicar la importación», o el párrafo del selector según el desplazamiento) cubría solo una de las dos líneas, y las pilas distintas hacen que axe
  no pueda determinar el fondo. No hay cambio de código en `Alert` ni en `Dialog` desde el corte anterior: depende de la altura de la página detrás
  y del número de líneas, y el selector de tipo desplazó esa página. Los demás diálogos pasan porque su texto cabe en una línea o queda sobre una
  zona uniforme.
- Corrección sin tocar reglas de axe: los avisos de los diálogos de aplicar (`imports.kinds.*.lateNote`) se acortaron a una sola línea de texto
  (138 caracteres), conservando su contenido (documentos revisados solo al aplicar; grupos validados juntos, vínculos existentes nunca reemplazados).
- Defecto latente, no tocado: `Dialog` usa `max-w-lg`, pero el tema de Tailwind está reiniciado y esa clase no se genera, así que todos los
  diálogos ocupan el ancho de la ventana menos 2rem en lugar de 32rem. Conviene `max-w-[32rem]` en un cambio aparte, porque altera todos los diálogos.

### Pendiente

- `AW61` falla como ya se sabía.

# Corte: encargados y vínculos (`guardians`)

Fase F1, entregable 4. Rama `feat/guardians-registry`, apilada sobre `feat/enrollments` (PR #306).

## Objetivo

El personal registra a los encargados de pago y los vincula a los estudiantes con parentesco y porcentaje de
responsabilidad financiera. Es el dato maestro que luego usan cobranza, facturación y el portal.

## Fuera de alcance

Cuenta o credencial del encargado en el portal, verificación de correo y evento `GuardianLinked` (F6),
consentimiento de comunicaciones y canales de contacto adicionales (siguiente corte), importación masiva.
No hay importes.

## Modelo y reglas

Módulo nuevo `com.confia.guardians` (cuatro capas). Tablas `guardians_guardian` y `guardians_guardian_student`,
con `institution_id`, seguridad por fila forzada, sin `DELETE`.

| Campo del encargado | Regla |
|---|---|
| `firstName`, `lastName` | Obligatorios, 1 a 100 caracteres |
| `nationalId` | Opcional. Cifrado con `ColumnEncryptionService` e índice ciego (misma llave versionada que estudiantes); único por institución cuando existe (`409 duplicate-national-id`). **Nunca se devuelve**: solo `hasNationalId` |
| `rtn` | Opcional, 14 dígitos. Es dato fiscal: cifrado igual que el documento, solo `hasRtn` |
| `email`, `phone`, `address` | Opcionales, formato validado. Sensibles: no van a bitácora ni a logs |
| `preferredChannel` | `EMAIL`, `SMS`, `WHATSAPP`, `NONE` (por defecto `NONE`) |
| `status` | `ACTIVE`, `INACTIVE`. Un encargado inactivo no puede recibir vínculos nuevos |

- Código `P-000001` por institución (contador como estudiantes). Nada se borra: se inactiva.
- **Vínculo** (`GuardianStudent`): `relationship` (`FATHER`, `MOTHER`, `LEGAL_GUARDIAN`, `SELF`, `OTHER`),
  `financialResponsibilityPercent` (`NUMERIC(5,2)`, 0.01 a 100), `isPrimaryPayer`, `isBillingRecipient`,
  `validFrom`, `validTo` (nula mientras está vigente).
- **INV-32:** si un estudiante tiene vínculos vigentes, su suma es exactamente 100. Como el primer vínculo
  nunca podría sumar 100 mientras se crean uno por uno, los vínculos de un estudiante se **reemplazan como
  conjunto atómico** (`PUT`): se valida suma 100.00 (aritmética decimal exacta, nunca `double`), un solo
  pagador principal y un solo destinatario de factura, sin encargado repetido, todos activos. Un estudiante
  sin vínculos es válido (aún sin asignar). Se serializa por estudiante con bloqueo de su fila.
- **Historial:** el reemplazo no borra. Un vínculo ausente o con porcentaje/parentesco/banderas distintos
  cierra el anterior (`validTo` = hoy en Honduras) y abre uno nuevo; uno idéntico se deja igual. Un vínculo
  cerrado nunca se reabre ni se edita.
- Un estudiante inactivo no admite cambios de vínculos (`409 student-not-active`). Un encargado se puede
  inactivar aunque tenga vínculos vigentes, pero el aviso se devuelve y los vínculos siguen (decisión del
  personal; no cuenta como INV-32).

## API

| Operación | Permiso |
|---|---|
| `GET /api/v1/guardians` paginado: `page`, `size` (50, máx. 200), `sort` (`lastName`, `code`, `status`), `q` (nombre, apellido o código), `status` | `GUARDIANS_READ` |
| `GET /api/v1/guardians/{id}` (incluye los estudiantes vinculados vigentes: código, nombres, parentesco, porcentaje) | `GUARDIANS_READ` |
| `POST /api/v1/guardians` → 201 | `GUARDIANS_CREATE` |
| `PUT /api/v1/guardians/{id}` → 200 (datos y estado) | `GUARDIANS_UPDATE` |
| `GET /api/v1/students/{studentId}/guardians` (vínculos vigentes, con suma) | `GUARDIANS_READ` |
| `PUT /api/v1/students/{studentId}/guardians` → 200 (conjunto completo) | `GUARDIANS_UPDATE` |

- Institución desde el token. Sin `Idempotency-Key` (no mueve dinero; el `PUT` de conjunto es idempotente por naturaleza).
- Auditoría `guardians.guardian.created|updated` y `guardians.links.replaced` con códigos, estados, porcentajes y
  nombres de campos cambiados; **sin nombres, documento, RTN, correo, teléfono ni dirección**.
- Límites de tasa con las políticas existentes. Respuestas `Cache-Control: no-store`.
- Errores nuevos: `duplicate-national-id` (409, si no existe ya), `invalid-responsibility-split` (400 con campo y motivo:
  `sum-not-100`, `multiple-primary-payer`, `multiple-billing-recipient`, `duplicate-guardian`), `guardian-not-active` (409).
- `students` y `guardians` no se importan el dominio: `guardians` consulta estudiantes por un puerto publicado por `students`
  (`@NamedInterface`), como se hizo con `organization`.

## Pantallas (`admin-web`)

Entrada «Encargados»: tabla paginada y ordenada en servidor con búsqueda y filtro de estado; crear, editar e
inactivar (documento y RTN solo se escriben, nunca se muestran). En el detalle del estudiante no existe pantalla
aún: el vínculo se gestiona desde «Estudiantes» con la acción «Encargados» por fila, que abre un diálogo con el
conjunto actual, agregar/quitar encargado (buscador), parentesco, porcentaje, pagador principal y destinatario,
suma visible y error claro si no suma 100. Estados de carga, vacío, error y éxito.

## Casos límite a probar

Suma 99.99, 100.01, 100.00 con tres encargados (33.33 + 33.33 + 33.34), 0 vínculos, porcentaje 0 y 100.01, dos pagadores
principales, encargado repetido, encargado inactivo, estudiante inactivo, reemplazo que cambia un porcentaje (historial
cerrado/abierto), reemplazo idéntico (sin cambios ni auditoría de cambio), dos reemplazos simultáneos del mismo estudiante,
documento duplicado (y con otro formato), aislamiento entre instituciones, 403 sin efecto, límites de tasa, ausencia de
datos personales en respuestas, auditoría y logs.

## Criterio de aceptación

Un administrador registra dos encargados, los vincula a un estudiante con 60/40 y luego los cambia a 50/50; el sistema
rechaza 60/30, conserva el historial del primer reparto y nunca muestra el documento.

## Construido en el backend

Módulo `com.confia.guardians` (cuatro capas, clases nuevas), migración `V19__create_guardians_guardian.sql` (tablas
`guardians_guardian`, `guardians_guardian_code_counter` y `guardians_guardian_student`, seguridad por fila forzada,
`REVOKE ALL FROM PUBLIC`, sin `DELETE`). Seis rutas, todas con `@RateLimited` por dirección y por cuenta (lecturas
`admin-read` y `admin-read-account`; escrituras `admin-write` y `admin-write-account`; `429` con `Retry-After`). Permisos en
`PermissionedEndpoints.forAdmin()` y `x-required-permission` en el OpenAPI: `guardians:read` (las tres lecturas),
`guardians:create` (crear encargado) y `guardians:update` (editar encargado y reemplazar el conjunto de un estudiante). Las
escrituras auditan `guardians.guardian.created|updated` y `guardians.links.replaced`. `StaffRole` no cambia: administrador y
super administrador crean, leen y actualizan; caja, contabilidad, auditoría y coordinación académica solo leen (coincide con
la matriz de `docs/03-seguridad.md`, que `PermissionMatrixParityTest` compara celda por celda).

### Base de datos

- `guardians_guardian`: `national_id_encrypted` y `rtn_encrypted` (formato `v1:` de `ColumnEncryptionService`, atado a la
  tabla, la columna y la fila), `national_id_blind_index` (`v1:<64 hex>`, único por institución mientras exista) y los datos
  de contacto en claro (`email`, `phone`, `address`). El RTN no tiene índice ciego: el corte no pide unicidad. El rol de solo
  lectura no ve ninguna de esas columnas (concesión por columna).
- `guardians_guardian_student`: claves compuestas `(institution_id, id)` hacia `guardians_guardian` y `students_student`;
  `NUMERIC(5,2)` con `CHECK` de 0.01 a 100; índices únicos parciales (solo vínculos vigentes, `valid_to IS NULL`) por
  encargado y estudiante, por pagador principal y por destinatario de factura. Sin `DELETE`; la aplicación solo puede
  `UPDATE (valid_to)` (concesión por columna) y un disparador `BEFORE UPDATE` impide tocar un vínculo ya cerrado o cambiar
  cualquier otra columna, incluso al dueño del esquema: un vínculo se cierra una vez y nunca se reabre ni se edita.
- INV-32 (suma exacta de 100) no es una restricción (es una regla sobre un conjunto de filas): la valida el caso de uso y la
  serializa el bloqueo de la fila del estudiante. La base garantiza lo local a las filas (arriba).

### Referencias entre módulos

`students` publica el paquete `com.confia.students.application.directory` como `@NamedInterface` (consumidor: `guardians`,
ADR-0022) con el puerto de solo lectura `StudentDirectory` y su vista `StudentRef` (`studentId`, `studentCode`, `firstName`,
`lastName`, `active`; nunca fecha de nacimiento ni documento). Operaciones: `find`, `lock` (`SELECT ... FOR UPDATE` sobre la
fila del estudiante) y `findAll` (por lote). Lo implementa `JooqStudentDirectory` en la infraestructura de `students` y se
registra en `StudentsConfiguration`. `guardians` no importa el dominio de `students` ni consulta sus tablas. El caso de uso y
los controladores se registran en `GuardiansConfiguration` y `GuardiansWebConfiguration` (importadas por `AdminApplication`;
el portal y el trabajador no las cargan: `ProcessBeanPolicy` las declara solo del proceso administrativo). Reutiliza el
`BlindIndexService` (llaves versionadas) y el `ColumnEncryptionService` ya existentes; el propósito del índice ciego es
`guardians.national-id`, distinto del de estudiantes.

### Concurrencia

Reemplazar el conjunto de un estudiante toma los bloqueos siempre en este orden: la fila del estudiante (`FOR UPDATE`, por el
directorio), luego los vínculos vigentes y luego los encargados implicados en modo compartido (`FOR SHARE`, ordenados por
identificador). Dos reemplazos simultáneos del mismo estudiante se turnan: el segundo ve lo que dejó el primero y lo
reemplaza; el resultado final es siempre uno de los conjuntos pedidos, nunca una mezcla. Inactivar un encargado
(`FOR UPDATE` de su fila) espera al reemplazo que lo está juzgando, y un reemplazo que llega después ve al encargado
inactivo (`409 guardian-not-active`). Los índices únicos parciales son la última palabra. Todo ocurre en el
`TransactionRunner` con `READ COMMITTED`. Las comprobaciones que solo dependen de la petición (porcentaje, encargado repetido,
banderas, suma) se juzgan antes de abrir la transacción.

### Forma de la API que necesita el frontend

- **Encargado** (`GuardianResponse`), en este orden: `id`, `guardianCode`, `firstName`, `lastName`, `hasNationalId`, `hasRtn`,
  `email`, `phone`, `address` (los tres pueden ser `null`), `preferredChannel`, `status`, `createdAt`, `updatedAt` y `students`.
  `students` (arreglo) viene en el detalle y en las respuestas de las dos escrituras, y **no viene** (el miembro se omite) en los
  listados. Cada elemento: `linkId`, `studentId`, `studentCode`, `studentFirstName`, `studentLastName`, `relationship`,
  `financialResponsibilityPercent`, `isPrimaryPayer`, `isBillingRecipient`, `validFrom`. Nunca trae el documento ni el RTN: solo
  `hasNationalId` y `hasRtn`. Un encargado `INACTIVE` con `students` no vacío es el aviso de «tiene vínculos vigentes que
  siguen» (la respuesta de `PUT` lo incluye).
- **Listado** `GET /api/v1/guardians`: `{ items, total, page, size }`. Parámetros: `page` (desde 0), `size` (50 por defecto,
  máximo 200), `sort` (`lastName`, `code`, `status`, con `,asc` o `,desc`; **por defecto `lastName` ascendente**), `q` (2 a 100
  caracteres; nombre, apellido o código) y `status`. Un `sort` no permitido, `size` fuera de 1..200, `page` negativa, `q` de un
  carácter, `status` desconocido o un identificador mal formado responden `400 validation-failed`.
- **Crear** `POST /api/v1/guardians` → `201`: `firstName` y `lastName` obligatorios; `nationalId`, `rtn`, `email`, `phone`,
  `address` y `preferredChannel` (`EMAIL`, `SMS`, `WHATSAPP`, `NONE`; `NONE` si falta) opcionales. El código (`P-000001`...) lo
  genera el servidor; un miembro desconocido (`guardianCode`, `institutionId`, `status`) es `400`.
- **Editar** `PUT /api/v1/guardians/{id}` → `200`: reemplaza el registro completo. `firstName`, `lastName` y `status`
  obligatorios; `email`, `phone`, `address` y `preferredChannel` omitidos **se borran** (`NONE` el canal); `nationalId` y `rtn`
  omitidos **se conservan**, vacíos se **quitan** y con valor se **reemplazan**.
- **Encargados de un estudiante** `GET /api/v1/students/{studentId}/guardians`: `{ studentId, studentCode, totalPercent, links }`;
  `links`: `linkId`, `guardianId`, `guardianCode`, `guardianFirstName`, `guardianLastName`, `guardianStatus`, `relationship`,
  `financialResponsibilityPercent`, `isPrimaryPayer`, `isBillingRecipient`, `validFrom`. Solo vínculos vigentes. `totalPercent`
  lo calcula el servidor con aritmética decimal exacta (`100.00`, o `0.00` sin encargados): el navegador no suma. `404` si el
  estudiante no es de la institución. Un estudiante inactivo se puede leer.
- **Reemplazar** `PUT /api/v1/students/{studentId}/guardians` → `200`: `{ links: [{ guardianId, relationship,
  financialResponsibilityPercent, isPrimaryPayer, isBillingRecipient }] }`, hasta 20; responde lo mismo que el `GET`. La lista
  vacía quita a todos. Los porcentajes viajan como número JSON (el servidor los lee como decimal exacto y los responde con dos
  decimales: `60.00`); acepta 0.01 a 100 con a lo sumo dos decimales (`33.330` se rechaza en el borde).
- **Errores**: `400 validation-failed` con `errors` (`field`, `reason`): porcentaje fuera de rango o con más de dos decimales es
  `links[].financialResponsibilityPercent` con motivo `decimal-min`, `decimal-max` o `digits`; miembro obligatorio ausente, el
  nombre del campo con `not-null`/`not-blank`. `400 invalid-responsibility-split` con `errors: [{ field: "links", reason }]`,
  `reason` ∈ `sum-not-100`, `multiple-primary-payer`, `multiple-billing-recipient`, `duplicate-guardian`.
  `404 resource-not-found` (encargado, estudiante o un encargado del conjunto que no existe en la institución, y también los de
  otra institución). `409`: `duplicate-national-id`, `guardian-not-active`, `student-not-active`. `403 forbidden` sin permiso,
  `401` sin sesión.
- Sin `Idempotency-Key` (no mueve dinero; el `PUT` de conjunto es idempotente). Las respuestas llevan `Cache-Control: no-store`.

### Decisiones tomadas al construir

- **Orden de comprobaciones del reemplazo**: forma de cada vínculo y reglas del conjunto (`400`), estudiante (`404`, con
  bloqueo), estudiante activo (`409`), encargados (`404`, con bloqueo compartido), encargados activos (`409`). Un conjunto que
  rompe INV-32 se rechaza sin tomar ningún bloqueo.
- **Reglas que la nota no fija** y que se eligieron así: «un solo pagador principal» y «un solo destinatario» significan **a lo
  sumo uno** (un conjunto sin ninguno es válido; no hay motivo `missing-primary`); todos los encargados del conjunto deben estar
  activos, de modo que para cambiar el reparto de un estudiante cuyo encargado quedó inactivo hay que quitarlo del conjunto; un
  estudiante inactivo no admite ningún cambio, ni siquiera la lista vacía; el «hoy» con el que se cierran y abren los vínculos
  es la fecha en Honduras, y un vínculo abierto y cerrado el mismo día es un historial válido de un día
  (`valid_from = valid_to`); `valid_to` es el día en que dejó de aplicar y el vínculo nuevo aplica desde ese mismo día.
- **Historial**: un vínculo con el mismo encargado, parentesco, porcentaje (comparado como número) y banderas se deja igual;
  todo lo demás cierra y abre. Un reemplazo que no cambia nada no escribe ni audita. La lectura del historial cerrado no tiene
  ruta (la nota no la pide): vive en la tabla y está probada en la base y en la prueba de punta a punta.
- **Auditoría**: encargado: `guardianCode`, `status`, `preferredChannel`, `hasNationalId`, `hasRtn`, `hasEmail`, `hasPhone`,
  `hasAddress` y, en la edición, `changedFields` (nombres, nunca valores). Reemplazo: entidad `guardians.links` con el
  identificador del estudiante, y antes y después `studentCode`, `linkCount`, `totalPercent` y `links` como
  `P-000001:MOTHER:60.00:primary:billing,...`. Nunca nombres (del encargado ni del estudiante), documento, RTN, correo,
  teléfono ni dirección.
- **Códigos de error**: `ProblemCode` gana `invalid-responsibility-split` (400) y `guardian-not-active` (409);
  `duplicate-national-id` y `student-not-active` se reutilizan (su texto del catálogo ahora dice «estudiante o encargado» y
  «matricular o cambiar sus encargados»). Para listar `errors` con un código propio, `ProblemCodeException` acepta ahora una
  lista de `FieldViolation` y el traductor único la escribe (sin lista, igual que antes).
- **Pendiente conocido (rotación de llaves)**: `docs/03-seguridad.md` (nota de 2026-11-11) describe el reindexado de los
  índices ciegos solo para estudiantes (`CONFIA_STUDENTS_REINDEX_INSTITUTIONS`). Los índices de encargados llevan versión y la
  detección de duplicados prueba todas las versiones configuradas, pero **no existe aún un reindexado de
  `guardians_guardian.national_id_blind_index`**: debe construirse antes de la primera rotación de llave, o la llave anterior
  no podrá retirarse sin dejar sin detección a los encargados ya registrados.

### Pruebas

`ResponsibilitySplitTest` (INV-32: 99.99, 100.01, 100.00 con 33.33 + 33.33 + 33.34, sin vínculos, porcentajes 0 y 100.01,
dos pagadores, dos destinatarios, encargado repetido, encargado inactivo, y dos propiedades jqwik sobre divisiones de 100.00 en
centésimas), `LinkReplacementTest` (cerrar y abrir, igual no cambia, historial), `GuardianTest` (campos, correo, teléfono,
canal, documento y RTN, redacción), `ManageGuardianLinksTest` y `ManageGuardiansTest` (casos de la nota a través de los casos
de uso con puertos en memoria: orden de bloqueos, auditoría sin datos personales, aislamiento, lectura de solo lectura),
`GuardiansGuardianSchemaIT` (restricciones, índices únicos parciales, claves compuestas, el disparador del vínculo cerrado,
seguridad por fila y privilegios: sin `DELETE`, solo `UPDATE (valid_to)`, el rol de solo lectura no ve documento, RTN ni
contacto) y `GuardianEndpointsIT` (flujo de aceptación 60/40 → 50/50 con historial y 60/30 rechazado, cada regla con su código,
carreras contra PostgreSQL —seis reemplazos simultáneos del mismo estudiante, reemplazo contra inactivación del encargado, ocho
altas simultáneas, dos altas con el mismo documento—, aislamiento, `403` sin efecto, sesión cerrada, `429` por dirección y por
cuenta, auditoría y barrido de respuestas, filas de auditoría y registros contra el documento, el RTN, los nombres, el correo,
el teléfono y la dirección). Además: `ProblemCodeTest`, `ProblemCodeExceptionTest`, `ProblemExceptionHandlerTest`,
`PermissionedEndpointsTest`, `PublicRouteAllowListTest`, `CurrentSessionEndpointIT`, `AuthorizationEdgeIT`,
`OpenApiContractSnapshotTest` (instantánea regenerada: 36 operaciones, 29 con permiso) y las pruebas de arquitectura
(`ProcessBeanPolicy`, `WebEdgeScopeExclusionInventoryTest`).

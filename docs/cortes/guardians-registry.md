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
  no podrá retirarse sin dejar sin detección a los encargados ya registrados. `docs/03-seguridad.md` §7.3 lo advierte
  ahora de forma explícita. No se agregó una verificación al arranque: los estudiantes tampoco la tienen (solo documentan
  la rotación) y una verificación de versiones por filas exigiría leer la tabla de cada institución al arrancar.

- **Endurecimiento tras la auditoría de seguridad** (V19 editada en su lugar: no estaba aplicada en ningún entorno):
  INV-32 ya no depende solo del caso de uso: un disparador de restricción `DEFERRABLE INITIALLY DEFERRED`
  (`guardians_guardian_student_check_split`, `AFTER INSERT OR UPDATE`) exige, al confirmar y por estudiante afectado,
  que los vínculos vigentes sean ninguno o sumen exactamente 100.00 (`23514`); un reemplazo cierra los vínculos y abre el
  conjunto nuevo en una transacción, de modo que los estados intermedios se permiten. Un vínculo siempre nace abierto
  (`BEFORE INSERT` rechaza `valid_to` no nulo) y solo se cierra en un día que no sea futuro en `America/Tegucigalpa`
  (y, por la restricción existente, no anterior a `valid_from`). `JooqStudentDirectory` lee solo `id`, `student_code`,
  `first_name`, `last_name` y `status` (sin documento, índice ciego ni fecha de nacimiento). El texto de
  `duplicate-national-id` ya no promete una comprobación entre módulos («Ya existe un registro de este tipo en la
  institución con ese documento de identidad»).
- **Auditoría del documento duplicado**: un alta o edición rechazada por documento duplicado deja, en una transacción
  propia (la del intento se revierte), una entrada de la acción intentada con resultado `denied`, motivo
  `duplicate-national-id` y sin valores previos ni posteriores. Se usa `denied` y no `rejected` porque
  `shared_audit_log_outcome_chk` (V2, ya aplicada) solo admite `success`, `denied` y `error`.

### Pruebas

`ResponsibilitySplitTest` (INV-32: 99.99, 100.01, 100.00 con 33.33 + 33.33 + 33.34, sin vínculos, porcentajes 0 y 100.01,
dos pagadores, dos destinatarios, encargado repetido, encargado inactivo, y dos propiedades jqwik sobre divisiones de 100.00 en
centésimas), `LinkReplacementTest` (cerrar y abrir, igual no cambia, historial), `GuardianTest` (campos, correo, teléfono,
canal, documento y RTN, redacción), `ManageGuardianLinksTest` y `ManageGuardiansTest` (casos de la nota a través de los casos
de uso con puertos en memoria: orden de bloqueos, auditoría sin datos personales, aislamiento, lectura de solo lectura),
`GuardiansGuardianSchemaIT` (suma 99, 140 y 100.01 rechazadas al confirmar, reemplazo completo y cero vínculos aceptados, vínculo nacido cerrado y cierre futuro rechazados, restricciones, índices únicos parciales, claves compuestas, el disparador del vínculo cerrado,
seguridad por fila y privilegios: sin `DELETE`, solo `UPDATE (valid_to)`, el rol de solo lectura no ve documento, RTN ni
contacto) y `GuardianEndpointsIT` (flujo de aceptación 60/40 → 50/50 con historial y 60/30 rechazado, cada regla con su código,
carreras contra PostgreSQL —seis reemplazos simultáneos del mismo estudiante, reemplazo contra inactivación del encargado, ocho
altas simultáneas, dos altas con el mismo documento—, aislamiento, `403` sin efecto, sesión cerrada, `429` por dirección y por
cuenta, auditoría y barrido de respuestas, filas de auditoría y registros contra el documento, el RTN, los nombres, el correo,
el teléfono y la dirección). Además: `ProblemCodeTest`, `ProblemCodeExceptionTest`, `ProblemExceptionHandlerTest`,
`PermissionedEndpointsTest`, `PublicRouteAllowListTest`, `CurrentSessionEndpointIT`, `AuthorizationEdgeIT`,
`OpenApiContractSnapshotTest` (instantánea regenerada: 36 operaciones, 29 con permiso) y las pruebas de arquitectura
(`ProcessBeanPolicy`, `WebEdgeScopeExclusionInventoryTest`).

## Construido en el frontend

Ruta `/guardians` («Encargados» en la navegación, registrada en `app/features.ts` después de «Matrículas») y la acción
«Encargados» por fila en «Estudiantes». Código en `apps/admin-web/src/features/guardians/`, operaciones en
`shared/api/guardian-operations.ts`. Reutiliza la mecánica de los cortes anteriores sin cambiar su comportamiento:
`ResourceScreen`, `ResourceFormDialog`, campos, `useListState`, `useSearchBox`, `useAccessForm`, la clasificación de fallos de
`features/organization/`, el cliente de recursos paginados y el buscador con pausa de las matrículas. El cliente se generó con
`pnpm --filter @confia/contracts generate`.

**Decisiones**

- **Estado de la tabla en la URL**: `page`, `pageSize`, `sort=campo:asc|desc` (solo `lastName`, `code`, `status`, que son también los
  identificadores de las columnas ordenables), `q` (2 a 100 caracteres) y `status`. Un parámetro inválido se descarta. A la API viaja
  como `page` desde 0, `size` y `sort=campo,dir`. Orden por omisión, el del servidor: apellido ascendente.
- **Documento y RTN son de solo escritura.** Al crear se escriben en cajas con `autocomplete="off"`, `autocorrect="off"` y
  `spellcheck="false"`; al editar solo se muestra «Registrado» o «Sin registrar» (`hasNationalId`, `hasRtn`) y se actúa en tres
  gestos separados: caja «Reemplazar» (vacía conserva: el miembro no se envía), casilla «Quitar» (envía texto vacío) y nada más. Los
  valores escritos viven solo en el formulario montado: ni en la URL, ni en una clave de consulta, ni en la caché de mutaciones
  (`gcTime: 0` y reinicio), ni en el almacenamiento, ni en la consola; una prueba lo comprueba tras guardar.
- **Editar reemplaza el registro.** Correo, teléfono y dirección vacíos no se envían (el servidor los borra); el canal siempre se
  envía. Activar o desactivar desde la fila reenvía el registro completo de la fila con el otro estado y sin documento ni RTN
  (el servidor los conserva). Un encargado `INACTIVE` que sigue con estudiantes (`students` no vacío en la respuesta) produce el aviso
  «sigue vinculado a N estudiantes» en el aviso de la pantalla; los vínculos no se tocan.
- **Detalle.** Diálogo de solo lectura con `GET /api/v1/guardians/{id}`: datos, y tabla de estudiantes vinculados (código, nombre,
  parentesco, porcentaje con dos decimales alineado a la derecha, pagador principal, destinatario, desde). Esqueleto al cargar y
  reintento si falla; aviso si un encargado inactivo conserva vínculos.
- **Diálogo «Encargados» de un estudiante** (`StudentGuardiansContainer`, abierto desde la fila de «Estudiantes»; el foco vuelve al
  botón). Lee `GET /students/{id}/guardians` y edita el conjunto completo con `PUT`. Cada encargado es una fila (fieldset con
  leyenda «Nombre · P-000001»): parentesco, porcentaje (texto, `inputMode="decimal"`, alineado a la derecha), casillas de pagador
  principal y destinatario de la factura, y «Quitar a X del reparto». Agregar usa un combobox ARIA (flechas y Enter, 2 o más
  caracteres tras 300 ms, solo `status=ACTIVE`, `size=10`) que excluye a los ya agregados; Enter nunca envía el conjunto. El foco va a
  la fila nueva al agregar y a la caja del buscador al quitar, con un anuncio `aria-live`. Un conjunto vacío se puede guardar (quita
  todos) y el diálogo lo advierte antes. Máximo 20 filas.
- **Sin aritmética en el navegador.** La suma que se muestra es el `totalPercent` del servidor para lo guardado («la calcula el
  servidor»), con `Intl.NumberFormat` a dos decimales (`shared/format/format-percent.ts`); no hay suma en vivo. El porcentaje
  escrito se valida solo como texto (forma `\d{1,3}(\.\d{1,2})?` y rango 0.01 a 100 leído de los dígitos) y viaja como número JSON
  con `Number(texto)`. Si suma 100.00 o no, lo dice el servidor.
- **Errores.** Códigos nuevos con texto propio: `invalid-responsibility-split` y `guardian-not-active` (`KNOWN_PROBLEM_CODES` pasa de
  27 a 29 y `PROBLEM_KEYS` los espeja). `readProblem` ahora lee también `errors` de `invalid-responsibility-split`, y el clasificador
  elige el texto por motivo: `sum-not-100`, `multiple-primary-payer`, `multiple-billing-recipient`, `duplicate-guardian`. El alert
  recibe el foco y no borra lo escrito. `duplicate-national-id` se dice «encargado» en esta pantalla y `student-not-active` «no admite
  cambios en sus encargados» en el diálogo (los textos de estudiantes y matrículas no cambian). Errores de campo: `links[i].
  financialResponsibilityPercent` con `digits`, `decimal-min` y `decimal-max` bajo la fila correcta y en el resumen; `pattern` de
  `rtn`, `email` y `phone` con su mensaje. Un 403 se dice en llano dentro del diálogo y nada cambia.
- **Sin dinero, sin `Idempotency-Key`, sin actualización optimista.** Todas las escrituras invalidan `guardianKeys.all` (listas,
  detalles y conjuntos); mientras una escritura está en vuelo el botón se bloquea y un segundo clic no envía nada.
- **Texto.** Todo en el catálogo `es-HN` tipado (`guardians.*`, `problem.*`, `field.*`, `navigation.guardians`,
  `students.guardiansAction*`).
- **Cambios compartidos mínimos y aditivos:** `RowActions` acepta `extra`, `useResourceView` gana `announce`, `clearNotice` y el aviso
  opcional de `saved` y `toggle`, `FieldReason` gana cinco motivos, y `fieldErrorKey` los textos de formato.

**Pruebas del frontend.** Vitest con Testing Library y MSW. `GuardiansContainer.test.tsx` (la petición exacta, página, orden,
búsqueda desde dos caracteres, filtros en la URL y descarte de lo inválido, vacío, error con reintento, 403, carga, crear con los
miembros exactos, documento y RTN sin rastro, formato, documento duplicado, un solo envío, editar conservando, reemplazando y
quitando, activar y desactivar con aviso, detalle). `StudentGuardiansContainer.test.tsx` (conjunto actual, suma del servidor,
búsqueda, teclado, cuerpo exacto con 33.33 + 33.33 + 33.34, quitar, conjunto vacío, validación local, cada motivo del reparto,
`guardian-not-active`, `student-not-active`, 403, error de campo por fila, 429). Un archivo de prueba por componente de presentación,
`guardian-form.test.ts`, `link-model.test.ts`, `shared/api/guardian-operations.test.ts` y `format-percent.test.ts`.

## Pruebas e2e

**Simulador.** `mock-api/guardians-model.ts` atiende las seis operaciones sobre los estudiantes del mismo escenario
(`guardians-seed.ts`: 30 encargados, uno por apellido, cada décimo inactivo; el estudiante 1 vinculado a Rosa Aguilar 60.00 y Luis
Alvarado 40.00). Reglas delgadas: suma exacta 100.00 (en centésimas), a lo sumo un pagador y un destinatario, encargado repetido,
encargado y estudiante inactivos, documento repetido aun con otros guiones, historial al cerrar y abrir vínculos y reemplazo idéntico
sin cambios. Perfiles nuevos: `guardians-empty`, `guardians-forbidden` y `guardians-server-error`. Pruebas unitarias en
`mock-api/guardians-model.test.ts` (99.99, 100.01, 60/30, 33.33 + 33.33 + 33.34, sin vínculos, 0 y 100.01, dos pagadores, repetido,
inactivos, historial).

**Recorridos** (`e2e/specs/journeys/guardians-*.e2e.ts`, apoyo en `e2e/support/guardians.ts`): `guardians-crud`, `guardians-lists`,
`guardians-states`, `guardians-links` (el criterio de aceptación: 60/40 a 50/50, 60/30 rechazado, el documento nunca se muestra) y
`guardians-keyboard`. `a11y/gate` (matriz en `support/a11y-matrix.ts`): `/guardians` en listado, nuevo, editar con documento,
resumen de errores, detalle, sin coincidencias, vacío y error de carga, y en `/students` el diálogo de encargados (conjunto,
resultados de búsqueda y error de reparto), en tema claro y oscuro.

**Resultado de la verificación (frontend).** `tsc --noEmit` y `eslint` (src y e2e) limpios. Vitest dirigido: el subconjunto de
`shared`, `organization`, `students`, `enrollments`, `app`, `i18n`, `e2e/mock-api` y `e2e/support` pasa completo salvo AW61 (directorio
`apps/portal-web` vacío, conocido y ajeno al corte); los contenedores de encargados y del diálogo de encargados de un estudiante
pasan (60 pruebas), igual que el simulador, las pruebas de presentación y los tests de `app`. Playwright, solo lo del corte: los cinco
recorridos de encargados (26 pruebas) y las 22 evaluaciones de axe (11 estados en tema claro y oscuro: 8 de `/guardians` y 3 del
diálogo de encargados en `/students`) pasan sin violaciones bloqueantes. No se ejecutó `test:e2e` completo ni la cobertura global
por falta de memoria del equipo.

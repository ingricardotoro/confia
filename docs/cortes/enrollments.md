# Corte: matrícula (`students`)

Fase F1, entregable 3. Rama `feat/enrollments`, apilada sobre `feat/students-registry` (PR #305).

## Objetivo

El personal matricula a un estudiante en una sección de un año lectivo, lo traslada de sección y
registra su retiro, traslado a otra institución o graduación. La matrícula es el hecho que más
adelante dispara el devengo de cargos.

## Fuera de alcance

Generación de cargos y el evento `EnrollmentWithdrawn` hacia cobranza (F3, no existen los
consumidores), matrícula masiva o por importación, encargados, becas. No hay importes.

## Modelo y reglas

Tablas `students_enrollment` y `students_enrollment_transfer`, con `institution_id`, seguridad por
fila forzada, sin `DELETE`. El grado y la modalidad se derivan de la sección; no se guardan.

| Campo | Regla |
|---|---|
| `studentId` | Estudiante de la institución y en estado `ACTIVE` |
| `sectionId` | Sección existente y activa. El año lectivo **se deriva de la sección**: el cliente no lo envía |
| `enrolledOn` | Dentro del rango del año lectivo de la sección (extremos inclusivos), no futura |
| `status` | `ACTIVE` al crear. Transiciones: `ACTIVE → WITHDRAWN`, `ACTIVE → TRANSFERRED` (a otra institución), `ACTIVE → GRADUATED`. Los otros tres son finales |
| `withdrawnOn` | Obligatoria al salir de `ACTIVE`. No anterior a `enrolledOn`, no futura |
| `withdrawalReason` | Obligatoria para `WITHDRAWN` y `TRANSFERRED` (3 a 500 caracteres); opcional en `GRADUATED` |

- **INV-31:** un estudiante no puede tener dos matrículas `ACTIVE` en el mismo año lectivo. Lo
  impone la base con un índice único parcial `(institution_id, student_id, academic_year_id) WHERE status = 'ACTIVE'`.
  Tras un retiro puede volver a matricularse en el mismo año.
- **Cupo:** si la sección tiene `capacity`, no se admiten más matrículas `ACTIVE` que ese número
  (`409 section-full`). Se comprueba bajo bloqueo de la fila de la sección para que dos altas
  simultáneas no excedan el cupo.
- **Año cerrado:** no se matricula, traslada ni retira en un año `CLOSED` (`409 academic-year-closed`, ya existe).
- **Traslado de sección** (`transfer`): solo para una matrícula `ACTIVE`; la sección nueva debe ser del
  **mismo año lectivo**, distinta de la actual, activa y con cupo. La matrícula pasa a la sección nueva y se
  agrega una fila inmutable en `students_enrollment_transfer` (`fromSectionId`, `toSectionId`,
  `transferredOn`, `reason`, actor). El historial no se edita ni se borra.
- **Referencias entre módulos:** `students` no importa el dominio de `organization`. Consulta secciones y
  años a través de un puerto de aplicación publicado por `organization` (`@NamedInterface`), por lote
  para los listados.

## API

| Operación | Permiso |
|---|---|
| `GET /api/v1/enrollments` paginado: `page`, `size` (50, máx. 200), `sort` (`enrolledOn`, `studentLastName`, `status`), `academicYearId` (por defecto el año `ACTIVE` si existe), `sectionId`, `gradeId`, `status` | `STUDENTS_READ` |
| `GET /api/v1/enrollments/{id}` (incluye el historial de traslados) | `STUDENTS_READ` |
| `GET /api/v1/students/{studentId}/enrollments` | `STUDENTS_READ` |
| `POST /api/v1/enrollments` → 201: `studentId`, `sectionId`, `enrolledOn` | `STUDENTS_CREATE` |
| `POST /api/v1/enrollments/{id}/transfer` → 200: `toSectionId`, `transferredOn`, `reason` | `STUDENTS_UPDATE` |
| `POST /api/v1/enrollments/{id}/exit` → 200: `status` (`WITHDRAWN`, `TRANSFERRED`, `GRADUATED`), `withdrawnOn`, `withdrawalReason` | `STUDENTS_UPDATE` |

- Las respuestas incluyen, además de los ids, `studentCode`, nombres del estudiante, `academicYearName`,
  `sectionName`, `gradeName`, `modalityName`, `status` y fechas. No incluyen el documento.
- La institución sale del token. Sin `Idempotency-Key`: no mueve dinero; repetir una transición ya hecha
  responde `409 invalid-enrollment-state`.
- Escrituras auditan `students.enrollment.created|transferred|exited` con códigos y estados, **sin nombres
  ni fechas de nacimiento**; el motivo de retiro se audita solo como «presente».
- Límites de tasa con las políticas existentes de dirección y de cuenta (lectura y escritura).
- Errores nuevos: `student-not-active` (409), `inactive-reference` (ya existe, para sección inactiva),
  `section-full` (409), `duplicate-active-enrollment` (409), `invalid-enrollment-state` (409),
  `transfer-year-mismatch` (409), `enrollment-date-out-of-range` (400 `validation-failed` con campo).

## Pantalla (`admin-web`)

Entrada «Matrículas». Tabla paginada y ordenada en servidor con filtros de año lectivo (por defecto el
activo), grado, sección y estado. Acciones: «Matricular» (diálogo: buscar estudiante por nombre, apellido
o código con la búsqueda existente de estudiantes, elegir sección del año, fecha), «Trasladar de sección»
y «Registrar salida» (retiro, traslado a otra institución o graduación, con fecha y motivo) por fila.
Detalle de una matrícula con su historial de traslados. Estados de carga, vacío, error y éxito. Escrituras
visibles para todos; el servidor responde 403.

## Casos límite a probar

Segunda matrícula activa en el mismo año (y permitida tras retiro), estudiante inactivo, sección inactiva,
año cerrado, fecha fuera del rango del año (un día antes del inicio, el último día, un día después),
cupo exacto y dos altas simultáneas con un solo cupo libre, traslado a la misma sección, a sección de otro
año, a sección llena, transiciones desde un estado final, retiro sin motivo, fecha de salida anterior a la
de ingreso, doble salida simultánea, historial inmutable, aislamiento entre instituciones, 403 sin efecto,
límites de tasa, ausencia de datos personales en auditoría y logs.

## Criterio de aceptación

Un administrador matricula a un estudiante en una sección, lo traslada a otra sección del mismo año y
luego registra su retiro; el historial lo muestra y el cupo de la sección se libera.

## Construido en el backend

Módulo `com.confia.students` (clases nuevas; el comportamiento del registro de estudiantes no cambió), migración
`V20__create_students_enrollment.sql` (tablas `students_enrollment` y `students_enrollment_transfer`, seguridad por fila
forzada, `REVOKE ALL FROM PUBLIC`, sin `DELETE`; el historial de traslados solo admite `INSERT` y `SELECT`). Seis rutas,
todas con `@RateLimited` por dirección y por cuenta (lecturas `admin-read` y `admin-read-account`; escrituras `admin-write`
y `admin-write-account`; `429` con `Retry-After`). Permisos en `PermissionedEndpoints.forAdmin()` y `x-required-permission`
en el OpenAPI: `students:read` (las tres lecturas), `students:create` (matricular), `students:update` (trasladar y dar de
baja). Las escrituras auditan `students.enrollment.created|transferred|exited`.

### Referencias entre módulos

`organization` publica el paquete `com.confia.organization.application.directory` como `@NamedInterface` (consumidor:
`students`, ADR-0022) con el puerto de solo lectura `SectionDirectory` y su vista `SectionRef` (`sectionId`, `name`,
`active`, `capacity`, `academicYearId`, `academicYearName`, `academicYearStatus`, `startDate`, `endDate`, `gradeId`,
`gradeName`, `modalityName`). Lo implementa `JooqSectionDirectory` en la infraestructura de `organization` y se registra en
`AcademicStructureConfiguration`. Operaciones: `find`, `lock` (`SELECT ... FOR UPDATE OF organization_section`), `findAll`
(por lote), `sectionIdsOfGrade` y `activeAcademicYearId`. `students` no importa el dominio de `organization` ni consulta sus
tablas. El filtro `gradeId` se resuelve con `sectionIdsOfGrade` y el año por defecto con `activeAcademicYearId`. El caso de
uso y los controladores se registran en `EnrollmentsConfiguration` y `EnrollmentsWebConfiguration` (nuevas, importadas por
`AdminApplication`).

### Concurrencia

Orden de bloqueos único: la matrícula (`SELECT ... FOR UPDATE`) y luego la sección destino. Matricular bloquea la fila de la
sección antes de contar los cupos; trasladar bloquea la matrícula y la sección destino; dar de baja bloquea la matrícula, de
modo que dos salidas simultáneas se turnan y la segunda responde `409 invalid-enrollment-state`. INV-31 tiene como última
palabra el índice único parcial `students_enrollment_active_uq`, cuya violación se traduce a
`409 duplicate-active-enrollment`. Todo ocurre en el `TransactionRunner` con `READ COMMITTED`.

### Forma de la API que necesita el frontend

- **Matrícula** (`StudentEnrollmentResponse`; el nombre `EnrollmentResponse` ya lo usa el módulo de segundo factor), en este
  orden: `id`, `studentId`, `studentCode`, `studentFirstName`, `studentLastName`, `academicYearId`, `academicYearName`,
  `sectionId`, `sectionName`, `gradeId`, `gradeName`, `modalityName`, `enrolledOn` (`YYYY-MM-DD`), `status` (`ACTIVE`,
  `WITHDRAWN`, `TRANSFERRED`, `GRADUATED`), `withdrawnOn` (`null` mientras está activa), `withdrawalReason` (`null` mientras
  está activa o si una graduación no lo trae), `createdAt`, `updatedAt` y `transfers`. `transfers` (arreglo, el más antiguo
  primero) viene en el detalle y en las respuestas de las tres escrituras, y **no viene** (el miembro se omite) en los
  listados. Nunca trae el documento ni la fecha de nacimiento.
- **Traslado de sección** (`EnrollmentTransferResponse`): `id`, `fromSectionId`, `fromSectionName`, `toSectionId`,
  `toSectionName`, `transferredOn`, `reason` (puede ser `null`), `transferredBy` (cuenta del personal), `createdAt`.
- **Listado** `GET /api/v1/enrollments`: `{ items, total, page, size }`. Parámetros: `page` (desde 0), `size` (50 por defecto,
  máximo 200), `sort` (`enrolledOn`, `studentLastName`, `status`, con `,asc` o `,desc`; **por defecto `studentLastName`
  ascendente**), `academicYearId` (por defecto el año `ACTIVE`; si no existe, todos los años), `sectionId`, `gradeId` (la
  sección y el grado se combinan: una sección que no es del grado no deja nada) y `status`. Un `sort` no permitido, `size`
  fuera de 1..200, `page` negativa, `status` desconocido o un identificador mal formado responden `400 validation-failed`.
- **Detalle** `GET /api/v1/enrollments/{id}`: la matrícula con `transfers`. **Por estudiante**
  `GET /api/v1/students/{studentId}/enrollments`: `{ items }` (sin paginar, la más reciente primero, sin `transfers`);
  `404` si el estudiante no es de la institución.
- **Matricular** `POST /api/v1/enrollments` → `201`: `studentId`, `sectionId`, `enrolledOn`, los tres obligatorios. Un miembro
  desconocido (por ejemplo `academicYearId` o `institutionId`) es `400`.
- **Trasladar** `POST /api/v1/enrollments/{id}/transfer` → `200`: `toSectionId` y `transferredOn` obligatorios; `reason`
  opcional (3 a 500 caracteres recortado; vacío es ninguno). Responde la matrícula con `transfers`.
- **Dar de baja** `POST /api/v1/enrollments/{id}/exit` → `200`: `status` (`WITHDRAWN`, `TRANSFERRED`, `GRADUATED`) y
  `withdrawnOn` obligatorios; `withdrawalReason` obligatorio (3 a 500 caracteres recortado) para `WITHDRAWN` y
  `TRANSFERRED`, opcional para `GRADUATED`. Responde la matrícula con `transfers`.
- **Errores**: `400 validation-failed` con la lista `errors` (`field`, `reason`): una fecha fuera del rango, futura o anterior a
  la de ingreso es el campo `enrolledOn`, `transferredOn` o `withdrawnOn` con motivo `enrollment-date-out-of-range`; falta de
  motivo es `withdrawalReason` / `not-blank`; motivo demasiado corto o largo, `withdrawalReason` o `reason` / `size`; trasladar
  a la sección actual, `toSectionId` / `same-section`; estado de salida inválido, `status` / `pattern`; un miembro obligatorio
  ausente, el nombre del campo con `not-null`. `404 resource-not-found` (matrícula, estudiante o sección que no existe en la
  institución, y también los de otra institución). `409`: `student-not-active`, `inactive-reference` (sección inactiva),
  `section-full`, `duplicate-active-enrollment`, `invalid-enrollment-state` (matrícula que ya no está activa, incluida la
  segunda de dos salidas o traslados simultáneos), `transfer-year-mismatch`, `academic-year-closed` (matricular, trasladar o
  dar de baja en un año `CLOSED`). `403 forbidden` sin permiso, `401` sin sesión.
- Sin `Idempotency-Key` (no mueve dinero). Las respuestas llevan `Cache-Control: no-store`.

### Decisiones tomadas al construir

- **Orden de comprobaciones de una matrícula**: estudiante (404) y sección (404, con bloqueo), estudiante activo, año no
  cerrado, sección activa, fecha (obligatoria, dentro del año y no futura), segunda matrícula activa del año y, al final,
  cupo. Traslado: matrícula (404, con bloqueo) y sección destino (404, con bloqueo), matrícula activa, año no cerrado,
  destino distinto de la actual, mismo año, destino activo, fecha, cupo. Baja: matrícula (404, con bloqueo), activa, año no
  cerrado, estado de salida, fecha, motivo.
- **Reglas que la nota no fija** y que se eligieron así: el motivo de un traslado es opcional (3 a 500 si se envía); la fecha
  de un traslado no es anterior al ingreso, no es futura y no pasa del fin del año lectivo; trasladar a la sección en la que
  ya está es `400 validation-failed` sobre `toSectionId` (no `409`: el estado de la matrícula es válido); el «hoy» de las
  reglas es la fecha en Honduras.
- **Auditoría**: el estado antes y después lleva `studentCode`, `sectionId`, `academicYearId`, `enrolledOn` y `status`; el
  traslado añade `transferredOn` y `hasReason`, la baja `withdrawnOn` y `hasWithdrawalReason`. Nunca nombres, fecha de
  nacimiento ni el texto de un motivo.
- **Claves foráneas entre módulos**: `students_enrollment` y `students_enrollment_transfer` referencian con clave compuesta
  `(institution_id, id)` a `students_student`, `organization_section` y `organization_academic_year`. El año se guarda para
  que INV-31 sea un índice único simple; la aplicación lo deriva de la sección.
- El texto del catálogo de `inactive-reference` ahora nombra también la sección.
- `RequestFieldException` (aplicación) lleva el campo de un error del dominio hasta la capa web, que lo responde con el mismo
  formato `errors` de Bean Validation (los controladores no ven el dominio).

### Pruebas

`EnrollmentTest` (reglas del dominio: fechas con ambos extremos, cupo exacto, estados finales, motivo, traslado),
`ManageEnrollmentsTest` (casos de la nota a través del caso de uso con puertos en memoria: orden de bloqueos, auditoría sin
datos personales, filtros del listado, aislamiento), `StudentsEnrollmentSchemaIT` (restricciones, INV-31, claves foráneas,
seguridad por fila y privilegios: sin `DELETE`, historial solo `INSERT`) y `EnrollmentEndpointsIT` (flujo de aceptación,
cada regla con su código, cuatro carreras contra PostgreSQL —dos altas con un solo cupo, dos altas del mismo estudiante, dos
salidas, dos traslados al último cupo—, historial inmutable, aislamiento, `403` sin efecto, sesión cerrada, `429` por
dirección y por cuenta, auditoría y barrido de respuestas, filas de auditoría y registros contra el documento, los nombres,
la fecha de nacimiento y los motivos). Además: `ProblemCodeTest`, `PermissionedEndpointsTest`, `PublicRouteAllowListTest`,
`CurrentSessionEndpointIT`, `OpenApiContractSnapshotTest` (instantánea regenerada: 30 operaciones, 23 con permiso) y
`AuthorizationEdgeIT`.

## Construido en el frontend

Ruta `/enrollments` («Matrículas» en la navegación, registrada en `app/features.ts`). Código en
`apps/admin-web/src/features/enrollments/`, operaciones en `shared/api/enrollment-operations.ts`. Reutiliza la mecánica de
los cortes anteriores sin cambiar su comportamiento: `ResourceScreen`, `ResourceFormDialog`, campos, `useListState`, la
clasificación de fallos de `features/organization/`, `useAccessForm`, el cliente de recursos paginados
(`shared/api/resource-client.ts`), los lookups de años, grados y modalidades, y la lista de estudiantes para buscar. El cliente
se generó con `pnpm --filter @confia/contracts generate` (el nombre `EnrollmentResponse` es del segundo factor; la matrícula
es `StudentEnrollmentResponse`).

**Decisiones**

- **Estado de la tabla en la URL**: `page`, `pageSize`, `sort=campo:asc|desc` (solo `enrolledOn`, `studentLastName`, `status`),
  `academicYearId`, `gradeId`, `sectionId` y `status`. Un parámetro inválido se descarta. A la API viaja como `page` desde 0,
  `size` y `sort=campo,dir`. Orden por omisión, el del servidor: apellido del estudiante ascendente (la cabecera lo marca sin
  escribirlo en la URL). Cambiar un filtro reemplaza la entrada del historial y vuelve a la página 1.
- **Año por omisión como lo hace el servidor.** Sin `academicYearId` en la URL la pantalla no lo envía: el servidor usa el año
  `ACTIVE` y el filtro «Año lectivo» lo muestra elegido (lo toma de la lista de años). Con un año en curso el filtro no ofrece
  «Todos los años», porque la API no puede listarlos todos en ese caso; sin año en curso sí lo ofrece. Cambiar el año borra el
  filtro de sección (las secciones son de un año). El filtro de sección lista las secciones del año mostrado, cada una con su
  «Modalidad · Grado · Sección», porque el mismo nombre existe en varios grados.
- **Acciones por fila.** «Ver detalle» en todas; «Trasladar de sección» y «Registrar salida» solo en una matrícula `ACTIVE` (los
  otros tres estados son finales). Cada botón nombra al estudiante en su etiqueta accesible. Tras una salida la fila pierde esos
  botones y el foco pasa a su «Ver detalle».
- **Matricular.** Diálogo con buscador de estudiante (combobox ARIA con listbox de resultados, flechas y Enter, sin dependencia
  nueva), sección del año en curso agrupada por «Modalidad · Grado» (`optgroup` de un `select` nativo) y fecha nativa que
  empieza en hoy (fecha de Honduras). Sin año en curso el diálogo lo dice y no ofrece secciones. El año no se envía: lo
  deriva la sección. El cuerpo es exactamente `{ studentId, sectionId, enrolledOn }`.
- **Búsqueda del estudiante.** Reutiliza `GET /api/v1/students` con `status=ACTIVE` (el servidor no matricula a otros), `size=10`,
  orden por apellido y `q`. Solo pregunta con 2 o más caracteres, tras una pausa de 300 ms; con 1 carácter lo dice. Estados:
  buscando, sin resultados, fallo con «Reintentar búsqueda» y resultados. El estudiante elegido reemplaza al cuadro (con
  «Cambiar estudiante»); el foco va a ese botón al elegir y vuelve al cuadro al cambiar. El cuadro lleva `autocomplete="off"`,
  `autocorrect="off"` y `spellcheck="false"`. Los nombres viven solo en el DOM (ni en la URL, ni en el almacenamiento, ni en la consola).
- **Trasladar de sección.** Secciones activas del mismo año (se consultan por el año de la matrícula) distintas de la actual,
  fecha (hoy por omisión) y motivo opcional de 3 a 500 caracteres; un motivo vacío no se envía.
- **Registrar salida.** Tipo (`WITHDRAWN`, `TRANSFERRED`, `GRADUATED`), fecha y motivo; el motivo es obligatorio salvo en la
  graduación y la ayuda del campo dice cuál aplica. La salida no se puede revertir y el diálogo lo advierte.
- **Detalle.** Diálogo de solo lectura que lee `GET /api/v1/enrollments/{id}`: datos, motivo de la salida y tabla del historial de
  traslados (el más antiguo primero) o «Esta matrícula no tiene traslados de sección». Cargando es un esqueleto; si falla, ofrece reintentar.
- **Sin dinero, sin `Idempotency-Key`, sin actualización optimista.** Las tres escrituras invalidan todas las listas y todos los
  detalles (`enrollmentKeys.all`); la mutación se libera al terminar (`gcTime: 0` y reinicio) para que el motivo escrito no quede
  en la caché. Mientras una escritura está en vuelo el botón se bloquea y un segundo clic no envía nada.
- **Errores.** Nuevos códigos con texto propio: `student-not-active`, `section-full`, `duplicate-active-enrollment`,
  `invalid-enrollment-state`, `transfer-year-mismatch` (ya existían `academic-year-closed`, `inactive-reference`, `forbidden` y
  `resource-not-found`). `KNOWN_PROBLEM_CODES` pasa de 22 a 27 y `PROBLEM_KEYS` los espeja. Errores de campo de
  `validation-failed`: `enrollment-date-out-of-range` (texto «La fecha está fuera del rango permitido…»), `same-section` y `size` de
  `reason`/`withdrawalReason`, que el cliente lee como «de 3 a 500 caracteres» (es lo único que puede significar `size` ahí, por
  corto o por largo). Un 403 se dice en llano dentro del diálogo; nada cambia en pantalla.
- **Accesibilidad.** Foco devuelto al botón que abrió el diálogo (a «Matricular» tras matricular), error resumido con el foco en
  el resumen, `aria-live` en el aviso del resultado y en el estado del buscador, lista de resultados con `aria-activedescendant`.
- **Texto.** Todo en el catálogo `es-HN` tipado (`enrollments.*`, `problem.*`, `field.*`).

**Pruebas del frontend.** Vitest con Testing Library y MSW. `EnrollmentsContainer.test.tsx` recorre el listado (la petición
exacta, el año en curso, página, orden, filtros en la URL y descarte de lo inválido, acciones ocultas en filas finales, vacío con
y sin filtros, error con reintento, 403 de lectura), el detalle (historial, error con reintento y foco de vuelta), matricular
(búsqueda solo desde 2 caracteres, resultados, elección con puntero y con teclado, cuerpo exacto, cada error de negocio, error de
campo, un solo envío, sin año en curso, foco al cancelar, sin rastro de nombres), trasladar y salida (cuerpos exactos, cada error,
errores de campo, motivo corto y obligatorio, sin rastro del motivo). Un archivo de prueba por componente de presentación
(`components/*.test.tsx`, con su comentario de estados), `enrollment-model.test.ts` (fecha de Honduras, agrupación, búsqueda,
formularios) y `shared/api/enrollment-operations.test.ts`.

## Pruebas e2e

Misma mecánica que los cortes anteriores (Playwright en Chromium contra `vite build && vite preview`, API simulada por
`e2e/mock-api`: una simulación de las formas publicadas, no una prueba de la pila real ni de sus reglas).

**Simulador.** `mock-api/enrollments-model.ts` atiende las cinco operaciones de `/api/v1/enrollments` con estado en memoria por
escenario, sobre los estudiantes y la estructura académica del mismo escenario (`enrollments-seed.ts`: 37 matrículas del año en
curso, 25 activas llenan «Segundo A» —cupo 25—, 12 más en los dos primeros, con 6 retiros, 1 graduación y 1 traslado en el
historial; los estudiantes activos 36, 43 a 49 y 51 a 59 no tienen matrícula). Reglas delgadas: INV-31 (se puede volver a matricular
tras un retiro), cupo, estudiante inactivo, año cerrado, sección inactiva, fecha dentro del año y no futura
(`enrollment-date-out-of-range`), transición solo desde `ACTIVE`, motivo obligatorio salvo graduación, traslado a la misma
sección, a otro año o a una sección llena. El listado pagina, ordena y filtra como el servidor y toma el año en curso por omisión.
Perfiles nuevos: `enrollments-empty`, `enrollments-forbidden` (lecturas normales, escrituras `403`) y `enrollments-server-error` (las
tres primeras lecturas responden `500` con `traceId`). Pruebas unitarias en `mock-api/enrollments-model.test.ts`.

**Recorridos** (`e2e/specs/journeys/enrollments-*.e2e.ts`, apoyo en `e2e/support/enrollments.ts`):

- `enrollments-crud`: matricular → trasladar → registrar salida con el historial en el detalle; la salida libera el cupo de una sección
  llena y el mismo estudiante puede matricularse de nuevo; matricular tras un retiro y no dos veces a un activo; una matrícula final
  solo ofrece su detalle.
- `enrollments-lists`: año en curso sin escribirlo en la URL; paginación (37 filas, dos páginas); orden en el servidor; filtros de estado,
  grado y sección en la URL y en la consulta; «Quitar filtros»; una dirección compartida.
- `enrollments-states`: fecha fuera del año (un día antes del inicio y el primer día), formulario vacío, salida sin motivo (y graduación
  sin él), traslado a una sección llena, un `403`, el vacío con su salida, el error con identificador y reintento, y la búsqueda que solo
  llega al servidor desde 2 caracteres.
- `enrollments-keyboard`: matricular solo con teclado (buscar, flechas y Enter, sección, fecha, enviar, foco de vuelta), errores,
  trampa de foco con Escape y salida con el foco en el detalle de la fila.
- `a11y/gate` (matriz en `support/a11y-matrix.ts`): `/enrollments` en listado, diálogo de matricular, resultados de búsqueda, diálogo de
  trasladar, resumen de errores, detalle, vacío y error de carga, en tema claro y oscuro, sin violaciones bloqueantes y sin debilitar reglas.

**Resultado de la verificación.** `typecheck` y `lint` limpios. Vitest completo: 1191 pruebas pasan; fallan las dos que dependen de
cosas ajenas a este corte o ya corregidas (AW61 por el directorio `apps/portal-web` vacío, conocido; AW59 se actualizó con la ruta
nueva y pasa). Playwright: los cuatro recorridos de matrículas (21 pruebas) y las 16 evaluaciones de axe de `/enrollments`
(8 estados en dos temas) pasan sin violaciones bloqueantes. La ejecución completa de `test:e2e` se interrumpió por falta de memoria
del equipo y queda pendiente de repetir.

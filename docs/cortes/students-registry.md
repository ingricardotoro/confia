# Corte: registro de estudiantes (`students`)

Fase F1, entregable 2. Rama `feat/students-registry`, apilada sobre `feat/organization-academic-structure` (PR #304).

## Objetivo

El personal registra, busca y edita estudiantes desde el panel. Los datos de menores se minimizan y
el documento de identidad se guarda cifrado por columna.

## Fuera de alcance

Matrícula y traslados (corte siguiente), encargados y vínculos (después), importación masiva,
consentimientos, cuenta financiera del estudiante (F3). No hay importes: no aplica `Money`.

## Modelo y reglas

Tabla `students_student` con `institution_id`, seguridad por fila forzada, sin `DELETE`.

| Campo | Regla |
|---|---|
| `studentCode` | Único por institución. Opcional al crear: si falta, se genera `E-000001`, `E-000002`... con un contador por institución bajo bloqueo (nunca `MAX + 1`). Inmutable después |
| `firstName`, `lastName` | Obligatorios, 1 a 100 caracteres, se recortan espacios |
| `birthDate` | Obligatoria, no futura, no anterior a 1990-01-01 (mensaje claro; evita errores de captura) |
| `sex` | `F`, `M` o `UNSPECIFIED` |
| `nationalId` | Opcional (muchos menores no tienen). 8 a 20 caracteres alfanuméricos y guiones. Se cifra con `ColumnEncryptionService` antes de guardar. Único por institución: se detecta con un **índice ciego** (HMAC con llave propia, columna `national_id_blind_index`) para rechazar duplicados sin descifrar |
| `status` | `ACTIVE`, `INACTIVE`. No se borra: se inactiva con `PUT` |

El documento **nunca** viaja, ni completo ni en parte (ni siquiera sus últimos caracteres: con la fecha de nacimiento
revelarían casi todo el DNI hondureño), en listados, respuestas, logs ni auditoría. Las respuestas devuelven solo
`hasNationalId`. Para cambiarlo se envía
el valor nuevo completo; omitirlo lo conserva; enviarlo vacío lo elimina. Ver el valor completo
queda fuera de este corte (requeriría un permiso y una auditoría de acceso propios).

## API (`/api/v1/students`)

| Operación | Permiso |
|---|---|
| `GET /` paginado: `page`, `size` (50, máx. 200), `sort` (`studentCode`, `lastName`, `firstName`, `birthDate`), `q` (busca en nombre, apellido y código; mínimo 2 caracteres), `status` | `STUDENTS_READ` |
| `GET /{id}` | `STUDENTS_READ` |
| `POST /` → 201 | `STUDENTS_CREATE` |
| `PUT /{id}` → 200 (reemplaza campos editables e incluye `status`) | `STUDENTS_UPDATE` |

- La institución sale del token. Sin `Idempotency-Key` (no mueve dinero).
- Escrituras auditan `students.student.created|updated` sin datos personales del menor (la
  bitácora se conserva 7 años y no se borra): solo `studentCode`, `status`, `hasNationalId` y, en la edición, los
  **nombres** de los campos que cambiaron. Nunca nombres, fecha de nacimiento ni documento (ni valor, ni cifrado, ni cola).
- Prohibido registrar nombres, fechas o documentos en logs (regla 11).
- Errores nuevos: `duplicate-student-code` (409), `duplicate-national-id` (409). `resource-not-found` ya existe.
- Los permisos `STUDENTS_*` ya existen y ya están asignados a los roles.

## Pantalla (`admin-web`)

Entrada «Estudiantes» en la navegación. Tabla paginada y ordenada en servidor con búsqueda `q` y filtro
de estado; alta y edición en diálogo (código solo lectura al editar; el documento muestra el valor
enmascarado y un campo para reemplazarlo); inactivar/activar desde la fila; estados de carga, vacío,
error y éxito. Escrituras visibles para todos, el servidor responde 403 (la sesión no expone permisos).

## Casos límite a probar

Código generado secuencial y bajo concurrencia (dos altas simultáneas, sin repetidos), código
repetido, documento duplicado (incluido con distinto formato de guiones), documento que no se
filtra en respuesta, auditoría ni logs, nacimiento futuro y anterior a 1990, espacios en nombres,
búsqueda con menos de 2 caracteres, ordenar por campo no permitido, aislamiento entre instituciones,
403 sin efecto, inactivar y volver a listar, quitar el documento con cadena vacía.

## Criterio de aceptación

Un administrador registra un estudiante con documento, lo encuentra por apellido, edita sus datos y lo
inactiva; en la base el documento está cifrado y en ningún lugar de la API aparece completo.

## Construido en el backend

Módulo `com.confia.students` (dominio, aplicación, `infrastructure`, `web`), migración `V17__create_students_student.sql`
(tablas `students_student` y `students_student_code_counter`, seguridad por fila forzada, sin `DELETE`). Cuatro rutas
bajo `/api/v1/students`, cada una con `@RateLimited` (lecturas `admin-read`, 300 por minuto por IP; escrituras `admin-write`,
60 por minuto por IP; `429` con `Retry-After`); las escrituras auditan `students.student.created|updated` con el estado
antes y después reducido a `studentCode`, `status` y `hasNationalId`, más `changedFields` (nombres separados por coma,
nunca valores) en el estado posterior de la edición.

### Forma de la API que necesita el frontend

- **Estudiante** (misma forma en listado, detalle, alta y edición), en este orden: `id`, `studentCode`, `firstName`,
  `lastName`, `birthDate` (`YYYY-MM-DD`), `sex` (`F`, `M`, `UNSPECIFIED`), `status` (`ACTIVE`, `INACTIVE`),
  `hasNationalId` (boolean, derivado de `national_id_encrypted IS NOT NULL`), `createdAt`, `updatedAt`.
  El documento completo no es nunca un campo de ninguna respuesta.
- **Listado**: `{ items, total, page, size }`. Parámetros: `page` (desde 0), `size` (50 por defecto, máximo 200),
  `sort` (`studentCode`, `lastName`, `firstName`, `birthDate`, con `,asc` o `,desc`; **por defecto `lastName` ascendente**),
  `q` (2 a 100 caracteres; busca sin distinguir mayúsculas en nombre, apellido y código; `%` y `_` son texto),
  `status` (`ACTIVE` o `INACTIVE`; ambos si falta). Un `sort` no permitido, `size` fuera de 1..200, `page` negativa,
  `status` desconocido o `q` de menos de 2 caracteres (incluida `q=` vacía: no la envíe, omita el parámetro) responden
  `400 validation-failed`.
- **Alta** `POST`: `studentCode` (opcional, hasta 30; vacío o ausente se genera `E-000001`...), `firstName`, `lastName`,
  `birthDate`, `sex` obligatorios; `nationalId` opcional (8 a 20 letras, dígitos o guiones; con o sin guiones es el
  mismo documento). Cualquier miembro desconocido (por ejemplo `institutionId`) es `400`.
- **Edición** `PUT /{id}`: `firstName`, `lastName`, `birthDate`, `sex`, `status` obligatorios; `nationalId` ausente conserva
  el documento, `""` (o solo espacios) lo elimina, otro valor lo reemplaza. **`studentCode` no se acepta** (es `400`):
  la pantalla lo muestra solo lectura. Inactivar es enviar `status: INACTIVE` con el resto de los campos actuales.
- **Errores**: `400 validation-failed` con la lista `errors` (`field`, `reason`) cuando falla Bean Validation: fecha
  futura es `birthDate` / `past-or-present`, anterior a 1990-01-01 es `birthDate` / `birth-date-floor`, formato de
  `sex`/`status`/`nationalId` es `pattern`; `409 duplicate-student-code`; `409 duplicate-national-id` (incluye el mismo
  documento con otro formato de guiones o de mayúsculas); `404 resource-not-found` (también para un estudiante de otra
  institución); `403 forbidden` sin permiso; `401` sin sesión.
- Los nombres se recortan; el límite de 1 a 100 caracteres se aplica ya recortado. El código se compara tal cual
  (distingue mayúsculas).

### Decisiones tomadas al construir

- **Ningún carácter del documento en claro** (hallazgo A1 de la revisión de seguridad): se eliminó `national_id_last4`
  (la migración V17, aún no publicada, se editó en su lugar). La cola de 4 caracteres combinada con la fecha de nacimiento
  casi reveló el DNI (municipio + año + secuencia). `hasNationalId` se deriva de `national_id_encrypted IS NOT NULL`, la
  restricción de consistencia entre el cifrado y el índice ciego se conserva, y el valor completo sigue únicamente cifrado
  (`national_id_encrypted`, formato `v1:` de `ColumnEncryptionService`, ligado a la fila); el rol de solo lectura no
  puede leer esa columna ni el índice ciego. La API ya no tiene `nationalIdMasked`.
- **Auditoría sin datos personales** (hallazgo A2): la bitácora no contiene nombres, fecha de nacimiento, sexo ni el
  documento. `changedFields` es un texto con los nombres separados por coma porque `AuditJson` no tiene arreglos.
- **Limitación de tasa** (hallazgo M2): políticas `admin-read` (300 por minuto) y `admin-write` (60 por minuto), por IP, en
  `confia.web.rate-limit.*` de `application.yml`. El mecanismo `@RateLimited` solo cuenta por dirección; el límite por
  cuenta de `docs/03-seguridad.md` sección 10 (600 y 120 por minuto) no es posible con él y queda en el seguimiento.
- **Índice ciego**: HMAC-SHA-256 del documento normalizado (mayúsculas, sin guiones) junto con la institución, con una
  llave derivada de la llave maestra de columnas por propósito (`students.national-id`) en el nuevo
  `shared.crypto.BlindIndexService`. Rotar la llave maestra obliga a recalcular los índices.
- **Normalizado mínimo**: tras quitar guiones el documento debe conservar al menos 5 caracteres (longitud mínima razonable del documento).
- **Código generado**: contador por institución en `students_student_code_counter`, tomado con
  `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` (el bloqueo de fila serializa altas simultáneas y un fallo devuelve
  el número). Si un código escrito a mano ya ocupa el siguiente número, se salta.
- **Fecha de nacimiento**: «hoy» se calcula con la hora de Honduras. Los instantes de escritura se truncan al
  microsegundo para que la respuesta del alta sea igual a la lectura posterior.
- La institución se toma del token (`AuthenticatedActor`); el módulo no importa nada de `organization`.

### Pruebas

`StudentTest`, `NationalIdTest`, `ManageStudentsTest` (reglas y casos límite de la nota con puertos en memoria),
`BlindIndexServiceTest` (vectores RFC 4231), `StudentsStudentSchemaIT` (restricciones, RLS y privilegios) y
`StudentEndpointsIT` (flujo de aceptación, concurrencia, aislamiento, roles, auditoría, `429` con `Retry-After` por
dirección y barrido de respuestas, filas de auditoría y logs capturados contra el documento completo, su cifrado y su
cola de 4 caracteres) y `RateLimitPropertiesTest` (valores de las políticas).

### Seguimiento

- **M1**: llave dedicada y versionada para el índice ciego, en vez de derivarla de la llave maestra de columnas, y un
  trabajo de recálculo de índices ante rotación; antes de la primera rotación de la llave maestra.
- **Límite por cuenta** (600 lecturas y 120 escrituras por minuto, `docs/03-seguridad.md` sección 10): el limitador solo
  conoce la dirección; necesita una clave por cuenta.
- **Limitación de tasa en `organization`**: los controladores de `organization` (años, grados, modalidades y secciones) no declaran `@RateLimited`;
  hay que aplicar `admin-read` y `admin-write` como en `students`.
- PostgreSQL con `log_error_verbosity=terse`, para que los errores de restricción no repitan en el registro del servidor
  los valores de la fila que falló (nombres, fecha de nacimiento).
- Los códigos de estudiante secuenciales (`E-000123`) revelan cuántos estudiantes tiene la institución.
- El texto de la sección 10 de `docs/03-seguridad.md` y el frontend (`apps/admin-web`, `packages/contracts`) aún mencionan
  `nationalIdMasked`; hay que regenerar los contratos y quitar el campo de la pantalla.



## Construido en el frontend

Ruta `/students` («Estudiantes» en la navegación). Código en `apps/admin-web/src/features/students/`, operaciones en
`shared/api/student-operations.ts`. Reutiliza la mecánica del corte anterior: `ResourceScreen`, `ResourceFormDialog`,
campos, `useListState`, `useResourceView` y la clasificación de fallos de `features/organization/`, y el cliente de
recursos paginados, que se extrajo a `shared/api/resource-client.ts` (las operaciones de la estructura académica lo usan
ahora sin cambiar su comportamiento).

**Decisiones**

- **Estado de la tabla en la URL**: `page`, `pageSize`, `sort=campo:asc|desc` (solo `studentCode`, `lastName`,
  `firstName`, `birthDate`), `q` y `status` (`ACTIVE` o `INACTIVE`). Un parámetro inválido se descarta. A la API viaja como
  `page` desde 0, `size` y `sort=campo,dir`. Orden por omisión, el del servidor: apellidos ascendente (la cabecera lo
  marca sin escribirlo en la URL). Cambiar un filtro o el texto reemplaza la entrada del historial y vuelve a la página 1.
- **Búsqueda con pausa.** El cuadro «Buscar» muestra lo que se escribe al instante; la URL (y el servidor) lo siguen
  300 ms después de la última tecla, **y solo con 2 caracteres o más** (recortado). Con 1 carácter no se envía `q` y la
  ayuda del campo pasa a «Escriba al menos 2 caracteres para buscar.». Un cambio de la URL que el cuadro no hizo (atrás,
  un enlace, «Quitar filtros») reemplaza lo escrito; lo que el propio cuadro escribió nunca lo pisa.
- **Documento de identidad en el formulario.** Al crear, un campo opcional (8 a 20 letras, números o guiones; el patrón
  generado no tiene anclas y en el navegador aceptaría cualquier texto, así que el formulario lo ancla y el servidor sigue
  siendo la autoridad). Al editar son **tres actos separados**: «Documento actual» (enmascarado, solo lectura; «Sin
  documento» si no hay), «Reemplazar documento» (vacío conserva: el cuerpo no lleva `nationalId`) y la casilla «Quitar
  documento» (única forma de enviar `nationalId: ""`; solo se ofrece si hay documento; al marcarla se vacía y se
  deshabilita el campo de reemplazo). Un campo vacío nunca elimina el documento. El código es opcional al crear («si lo
  deja vacío, el sistema genera uno») y de solo lectura al editar; nunca se envía en un `PUT`.
- **El documento no deja rastro.** No se escribe en la URL ni en claves de consulta (ninguna consulta lo envía), no se
  registra en consola, y el formulario se desmonta al cerrar el diálogo (tras guardar o cancelar), así que el valor
  escrito desaparece del estado. La mutación que lo llevó tampoco se conserva: `gcTime: 0` y `reset()` al terminar, porque
  el caché de mutaciones guarda las variables enviadas. Tras un error el formulario conserva lo escrito para corregirlo.
- **Activar o desactivar desde la fila** envía el `PUT` con los datos actuales y el otro `status`, sin `nationalId`.
  El formulario de edición también permite cambiar el estado.
- **Códigos nuevos** `duplicate-student-code` y `duplicate-national-id` (`KNOWN_PROBLEM_CODES` pasa a 22,
  `PROBLEM_KEYS`, catálogo `es-HN` y `organization-failure.ts`). Los errores de campo de un `400` tienen texto propio:
  `birthDate` con `past-or-present` («no puede ser posterior a hoy»), `birth-date-floor` («anterior al 1 de enero de
  1990») y `nationalId` con `pattern` (formato del documento). El `403` conserva el mensaje del corte anterior.
- Tabla: código, apellidos, nombres, fecha de nacimiento (`Intl.DateTimeFormat` `es-HN`, en UTC), sexo, documento
  enmascarado tal como lo envía el servidor o «—» (con «Sin documento» para lector de pantalla), estado y acciones.
  Ordenan en el servidor las cuatro columnas que la API admite. Sin dinero, sin `Idempotency-Key` y sin actualización
  optimista; tras toda escritura se invalida `students` completo.

**Límites conocidos**

- Los componentes de presentación y los hooks de lista viven en `features/organization/` y se importan desde
  `features/students/`. Si aparece una tercera pantalla de listado conviene moverlos a `shared/`.
- No hay selector de tamaño de página en pantalla (el parámetro `pageSize` de la URL sí se respeta).
- El texto de `q` va a la URL: si alguien escribe ahí un número de documento, quedará en la dirección. La API no busca
  por documento, y el campo dice que busca por nombre, apellido o código.
- Se tocaron fuera de la superficie de la pantalla: `field-errors.ts` y `use-access-form.ts` (razones nuevas),
  `organization-failure.ts`, `list-search.ts` (se exportan `page`, `pageSize` y `sortOf`), `fields.tsx` (`disabled` en
  `TextField`), `use-list-search.ts` (extraído de `feature.tsx`), `test/setup.ts` (stub de `ResizeObserver`: Radix mide
  la casilla dentro de un `<form>` y jsdom no lo trae) y las pruebas de inventario `AppShellContainer.test.tsx` y
  `features/identity/routes.test.tsx` (una entrada y una ruta más).

**Pruebas** (Vitest, Testing Library y MSW sobre rutas, capa de consultas y transporte reales):
`StudentsContainer.test.tsx` cubre listado (columnas, fecha, documento enmascarado o guion, consulta inicial), página y
orden enviados al servidor y a la URL, parámetros inválidos descartados, filtro de estado, búsqueda (no se envía `q` con 1
carácter, una sola petición tras la pausa, recorte, volver a 1 carácter, «Quitar filtros» vacía el cuadro), estados vacío,
de error con reintento, `403` y de carga, alta sin código (cuerpo exacto), con código y documento, rastro del documento
(DOM, URL, cachés de consultas y de mutaciones, consola), formulario nuevo vacío tras guardar, errores de campo locales y
del `400`, `duplicate-student-code`, `duplicate-national-id`, `403` y `404` en el diálogo, envío único con doble clic y
edición conservando, reemplazando y quitando el documento con el cuerpo exacto de cada `PUT`; activar y desactivar con
cuerpo sin documento y `403`. Más pruebas de componente (`StudentsView`, `StudentFilters`, `StudentForm`, con su
comentario de estados) y de `student-form.ts`, `students-search.ts` y `student-operations.ts`.

## Pruebas e2e

Misma mecánica que el corte anterior (Playwright en Chromium contra `vite build && vite preview`, API simulada por
`e2e/mock-api`: una simulación de las formas publicadas, no una prueba de la pila real ni de sus reglas).

**Simulador.** `mock-api/students-model.ts` atiende las cuatro operaciones de `/api/v1/students` con estado en memoria
por escenario (`students-seed.ts`: 60 estudiantes, dos por cada uno de 30 apellidos, uno de cada tres con documento y uno
de cada diez inactivo, con identificadores fijos). Listado paginado, ordenado (`studentCode`, `lastName`, `firstName`,
`birthDate`; por omisión apellidos), con `q` (2 a 100 caracteres, sin distinguir mayúsculas, en nombre, apellido y
código) y `status`; todo lo demás es `400 validation-failed`. `POST` genera `E-000061`, `E-000062`... si falta el código;
`PUT` rechaza `studentCode` y cualquier miembro desconocido; el documento omitido se conserva, vacío se elimina y otro
valor se reemplaza; fecha futura (`past-or-present`) o anterior a 1990 (`birth-date-floor`); `duplicate-student-code`;
`duplicate-national-id` también con otros guiones o mayúsculas. El documento completo se guarda aparte de las filas y
ninguna respuesta lo lleva (solo `hasNationalId` y `nationalIdMasked`). Perfiles nuevos: `students-empty`,
`students-forbidden` (lecturas normales, escrituras `403`) y `students-server-error` (las tres primeras lecturas
responden `500` con `traceId`). Pruebas unitarias en `mock-api/server.test.ts`.

**Recorridos** (`e2e/specs/journeys/students-*.e2e.ts`, apoyo en `e2e/support/students.ts`):

- `students-crud`: inicio de sesión y apertura de «Estudiantes»; alta con documento, búsqueda por apellido (código
  generado, documento enmascarado, y el documento completo ausente de la página, la URL y todas las respuestas); alta
  con código propio y sin documento; formulario vacío sin petición; edición (código fijo, documento enmascarado,
  conservar); reemplazar y quitar el documento con la casilla; activar y desactivar desde la fila; errores de fecha del
  servidor bajo su campo.
- `students-lists`: paginar con la URL, las filas y la consulta enviada; orden por omisión y por cada cabecera; volver a la
  página 1 al cambiar el orden; enlace compartido con parámetros inválidos descartados; `q` solo desde 2 caracteres y tras la
  pausa; búsqueda por nombre, apellido y código sin distinguir mayúsculas; filtro de estado; combinación, sin resultados y
  «Quitar filtros»; búsqueda y estado restaurados desde la URL.
- `students-states`: código repetido y documento repetido (con otros guiones) en español y con el diálogo abierto, y también
  al editar; formato de documento sin petición; cancelar sin dejar el documento en la página; `403` en el alta y en la
  desactivación; estado vacío con su acción; estado de error con identificador de soporte y reintento.
- `students-keyboard`: abrir el diálogo con Enter, llenarlo (incluida la fecha por segmentos y el sexo con las flechas) y
  enviarlo solo con teclado, con foco visible, foco atrapado, Escape y retorno del foco; errores del formulario vacío con
  Espacio; la casilla «Quitar documento» con Espacio.

**Puerta de axe.** Ocho combinaciones nuevas en `e2e/support/a11y-matrix.ts`, cada una en tema claro y oscuro, todas en
`/students`: lista con filas, diálogo nuevo, diálogo de edición con documento (casilla), diálogo con resumen de errores,
diálogo con el error de código repetido, sin coincidencias, vacío y error de carga.

**Resultado de la puerta de axe y hallazgo.** Cero violaciones bloqueantes en las 16 evaluaciones nuevas. En la primera
corrida, los dos diálogos con error (`dialog-error-summary` y `dialog-duplicate-code`, ambos temas) fallaron con
`color-contrast` (serio, incompleto, contado como bloqueante por la política) sobre el título y la descripción del
diálogo. Causa: el formulario del estudiante, con su resumen de errores, es más alto que el 90 vh que permite el `Dialog`
en el viewport por omisión de 720 px; el foco que va al resumen desplaza el contenido del diálogo, el título queda fuera
del área recortada y axe no puede calcular su fondo. No es un defecto de contraste. Esos dos estados se miden ahora en un
viewport de 1280 x 1000 (`tallViewport` en `a11y-matrix.ts`). Queda como observación de diseño: en una pantalla baja el
título del diálogo se pierde al saltar al resumen; conviene que `confia-uiux-designer` decida entre un formulario en dos
columnas o un encabezado fijo del diálogo (no se tocó `packages/ui`).

**Corrida completa** (`pnpm --filter @confia/admin-web test:e2e`): 157 pruebas, 157 pasaron, 0 fallos, 0 inestables.
Una corrida previa del subconjunto de recorridos de estudiantes con dos trabajadores mostró un fallo aislado de «inicia
sesión y abre Estudiantes» (la espera de las filas), que no se repitió solo ni en las dos corridas completas siguientes.

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

El documento **nunca** viaja completo en listados ni en logs. El detalle devuelve solo
`nationalIdMasked` (los últimos 4 caracteres, `****1234`) y `hasNationalId`. Para cambiarlo se envía
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
- Escrituras auditan `students.student.created|updated` con antes y después **sin el documento**: se
  audita solo `hasNationalId` y los últimos 4. Nunca el valor ni el cifrado.
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
bajo `/api/v1/students`; las escrituras auditan `students.student.created|updated`.

### Forma de la API que necesita el frontend

- **Estudiante** (misma forma en listado, detalle, alta y edición), en este orden: `id`, `studentCode`, `firstName`,
  `lastName`, `birthDate` (`YYYY-MM-DD`), `sex` (`F`, `M`, `UNSPECIFIED`), `status` (`ACTIVE`, `INACTIVE`),
  `hasNationalId` (boolean), `nationalIdMasked` (`****1234`, o `null` sin documento), `createdAt`, `updatedAt`.
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

- **Últimos cuatro del documento en claro** (`national_id_last4`, solo los últimos 4 caracteres del documento
  normalizado): permite devolver `nationalIdMasked` en el listado y auditar «los últimos 4» sin descifrar nunca nada
  en la aplicación. Es lo mismo que la pantalla ya muestra enmascarado. El valor completo sigue únicamente cifrado
  (`national_id_encrypted`, formato `v1:` de `ColumnEncryptionService`, ligado a la fila) y el rol de solo lectura no
  puede leer esa columna ni el índice ciego.
- **Índice ciego**: HMAC-SHA-256 del documento normalizado (mayúsculas, sin guiones) junto con la institución, con una
  llave derivada de la llave maestra de columnas por propósito (`students.national-id`) en el nuevo
  `shared.crypto.BlindIndexService`. Rotar la llave maestra obliga a recalcular los índices.
- **Normalizado mínimo**: tras quitar guiones el documento debe conservar al menos 5 caracteres (para que la máscara
  oculte algo).
- **Código generado**: contador por institución en `students_student_code_counter`, tomado con
  `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` (el bloqueo de fila serializa altas simultáneas y un fallo devuelve
  el número). Si un código escrito a mano ya ocupa el siguiente número, se salta.
- **Fecha de nacimiento**: «hoy» se calcula con la hora de Honduras. Los instantes de escritura se truncan al
  microsegundo para que la respuesta del alta sea igual a la lectura posterior.
- La institución se toma del token (`AuthenticatedActor`); el módulo no importa nada de `organization`.

### Pruebas

`StudentTest`, `NationalIdTest`, `ManageStudentsTest` (reglas y casos límite de la nota con puertos en memoria),
`BlindIndexServiceTest` (vectores RFC 4231), `StudentsStudentSchemaIT` (restricciones, RLS y privilegios) y
`StudentEndpointsIT` (flujo de aceptación, concurrencia, aislamiento, roles, auditoría y barrido de respuestas,
filas de auditoría y logs capturados contra el documento completo y su cifrado).

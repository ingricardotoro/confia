# Corte: estructura académica (`organization`)

Fase F1, entregable 1. Rama `feat/organization-academic-structure`.

## Objetivo

La institución administra su año lectivo, sus modalidades, sus grados y sus secciones desde el
panel administrativo. Es el eje que usarán estudiantes, matrícula y cobros.

## Fuera de alcance

Períodos de cobro (corte siguiente, dependen de F2), estudiantes, encargados, matrícula,
importación de datos. No hay importes en este corte: no aplica `Money`.

## Modelo y reglas

Todas las tablas llevan `institution_id`, seguridad por fila forzada, `is_active` y marcas de
tiempo. **Nada se borra**: se desactiva con `PUT`.

| Entidad | Campos | Reglas |
|---|---|---|
| `AcademicYear` | `name`, `startDate`, `endDate`, `status` (`PLANNED`, `ACTIVE`, `CLOSED`) | `endDate` posterior a `startDate`; `name` único por institución; los rangos no se solapan; como máximo un año `ACTIVE`; un año `CLOSED` no se edita ni reabre |
| `Modality` | `code`, `name` | `code` único por institución |
| `Grade` | `modalityId`, `name`, `ordinal` | `name` y `ordinal` únicos dentro de la modalidad; la modalidad debe estar activa |
| `Section` | `academicYearId`, `gradeId`, `name`, `capacity` (opcional) | `name` único por año y grado; `capacity` mayor que cero; el año no puede estar `CLOSED`; grado activo |

Desactivar una modalidad o un grado no afecta secciones ya creadas.

## API (`/api/v1/organization`)

| Recurso | Operaciones |
|---|---|
| `/academic-years` | `GET` (paginado), `POST`, `PUT /{id}` |
| `/modalities` | `GET` (paginado), `POST`, `PUT /{id}` |
| `/grades` | `GET` (paginado, filtro `modalityId`), `POST`, `PUT /{id}` |
| `/sections` | `GET` (paginado, filtros `academicYearId`, `gradeId`), `POST`, `PUT /{id}` |

- Permisos existentes: `GET` exige `ORGANIZATION_READ`, `POST` exige `ORGANIZATION_CREATE`,
  `PUT` exige `ORGANIZATION_UPDATE`. No se crean permisos nuevos.
- Listados: `page` (desde 0), `size` (por defecto 50, máximo 200), `sort` por campo permitido.
  Respuesta `{ items, total, page, size }`.
- La institución sale siempre del token. Sin `Idempotency-Key`: el corte no mueve dinero (a
  confirmar con el propietario si se quiere de todos modos).
- Cada escritura audita (`organization.<entidad>.created|updated`) con valor anterior y posterior,
  dentro de la misma transacción.
- Errores nuevos (Problem Details): `academic-year-overlap` (409), `academic-year-already-active`
  (409), `academic-year-closed` (409), `duplicate-name` (409), `resource-not-found` (404),
  `inactive-reference` (409).

## Construido en el backend

Migración `V16__create_organization_academic_structure.sql`; código en
`com.confia.organization.{domain,application,infrastructure,web}`.

**Decisiones de forma de la API** (la nota no las fijaba; el OpenAPI de `apps/api/openapi/admin.openapi.json`
es la fuente):

- Los cuerpos `PUT` reemplazan los campos editables completos e incluyen `isActive` (obligatorio).
  Editables: año (`name`, `startDate`, `endDate`, `status`), modalidad (`code`, `name`), grado (`name`,
  `ordinal`), sección (`name`, `capacity`). **Inmutables tras crear**: `modalityId` del grado y
  `academicYearId` y `gradeId` de la sección.
- `POST /academic-years` acepta `status` opcional (`PLANNED` por defecto, o `ACTIVE`); `CLOSED` no se
  acepta al crear. `POST` responde `201` con el recurso; `PUT` responde `200` con el recurso.
- Todas las respuestas llevan `id`, los campos de la entidad, `isActive`, `createdAt` y `updatedAt`.
  `capacity` es `null` cuando no hay cupo.
- Listados: `page` (desde 0), `size` (50 por defecto; fuera de 1 a 200 es `400`), `sort=campo[,asc|desc]`
  con campos permitidos (años: `name`, `startDate`, `endDate`, `status`, por defecto `startDate` desc;
  modalidades: `code`, `name`; grados: `name`, `ordinal`; secciones: `name`, `capacity`) y un
  desempate estable por `id`. Devuelven filas activas e inactivas. Un campo no permitido es `400`.
- Los rangos de fechas tienen extremos inclusivos: un año que empieza el día en que otro termina se
  solapa (`409 academic-year-overlap`); el que empieza al día siguiente no.
- `duplicate-name` cubre también el `code` repetido de una modalidad y el `ordinal` repetido de un
  grado. Un cuerpo con `endDate` no posterior a `startDate`, o `capacity` cero o negativa, es `400
  validation-failed` (Bean Validation en el borde; el dominio lo repite como segunda defensa).
- Una sección de un año `CLOSED` tampoco se edita (`academic-year-closed`); desactivar un grado no
  impide editar sus secciones existentes.

**Garantías**: los nombres, códigos, ordinales, el único año `ACTIVE` y las claves compuestas
`(institution_id, id)` los impone la base. La no superposición de rangos no puede ser una restricción
sin `btree_gist` (no se instala); la impone el caso de uso bajo un `SELECT ... FOR UPDATE` de la fila de
la institución, que serializa a quienes escriben rangos de una misma institución. Las carreras (dos
años solapados a la vez, dos activaciones a la vez, dos códigos iguales a la vez) tienen prueba.

**Pruebas**: `AcademicYearTest` y `ModalityGradeSectionTest` (dominio), `ManageAcademicYearsTest` y
`ManageModalitiesGradesSectionsTest` (casos de uso con fakes y auditoría),
`OrganizationAcademicStructureSchemaIT` (restricciones, aislamiento por fila, privilegios) y
`AcademicStructureEndpointsIT` (HTTP real: criterio de aceptación, cada regla con su código, `403` sin
efecto, sesión cerrada, aislamiento entre instituciones, un asiento de auditoría por escritura y las
carreras). Los casos de uso se registran en `AcademicStructureConfiguration` (aparte de
`OrganizationConfiguration`, que también carga el arnés web sin base de datos) y los controladores en
`OrganizationWebConfiguration`, ambos importados solo por el proceso administrativo.

**Verificación**: `./mvnw -B verify` en `apps/api` pasa (3098 pruebas unitarias, 961 de integración,
JaCoCo sin violaciones).

## Pantallas (`admin-web`)

Menú «Estructura académica» con cuatro vistas: años lectivos, modalidades, grados y secciones.
Cada una con tabla paginada en servidor, formulario de alta y edición, desactivación y estados de
carga, vacío, error y éxito. Visible para quienes tengan lectura; las acciones de escritura solo
se ofrecen a quienes tengan permiso (la decisión real sigue en el servidor).

## Casos límite a probar

Solape de años por un día, año que termina el día que otro empieza, segundo año activo,
edición de año cerrado, `ordinal` duplicado, sección en año cerrado, capacidad cero o negativa,
lectura entre instituciones (un usuario no ve datos de otra), desactivar y volver a listar,
rol sin permiso recibe 403 sin efecto, cada escritura deja asiento de auditoría.

## Criterio de aceptación

Un administrador crea un año, una modalidad, un grado y una sección desde la pantalla y los ve
listados; las reglas anteriores se rechazan con mensaje claro en español.

## Construido en el frontend

Cuatro vistas bajo `/organization` (`/academic-years`, `/modalities`, `/grades`, `/sections`) con
subnavegación de enlaces; `/organization` redirige a años lectivos. Código en
`apps/admin-web/src/features/organization/`, operaciones en `shared/api/organization-operations.ts`.

**Decisiones**

- **Estado de la tabla en la URL**, según `docs/ui-ux/04` §3.2: `page` desde 1, `pageSize` (25, 50 o 100;
  25 por omisión y no se escribe), `sort=campo:asc|desc` y los filtros por identificador (`modalityId`,
  `academicYearId`, `gradeId`). Cada vista valida sus parámetros con Zod y descarta los inválidos. A la API
  viaja como `page` desde 0, `size` y `sort=campo,dir`. Paginar y ordenar no filtran nada en el navegador.
  Cambiar un filtro reemplaza la entrada del historial y vuelve a la página 1; cambiar de página la agrega.
- **Permisos.** La lectura de la sesión no expone permisos, así que las acciones de escritura se ofrecen a
  todos y la decisión sigue en el servidor: un `403` se muestra como «No tiene permiso para realizar esta
  acción». `forbidden` y `resource-not-found` dejan de usar el mensaje genérico y tienen texto propio.
- **Códigos nuevos** (`academic-year-overlap`, `academic-year-already-active`, `academic-year-closed`,
  `duplicate-name`, `inactive-reference`) reflejados en `KNOWN_PROBLEM_CODES` (ahora 20), en `PROBLEM_KEYS` y
  en el catálogo `es-HN` con mensaje propio. El `400` por `endAfterStart` se muestra en el campo de fin.
- **Token de acceso.** Las pantallas no lo tocan: `AccessSession.authorized(operación)` entrega el token
  (refrescado si queda menos de un minuto), devuelve los datos o lanza `ApiFailure`, y un `401` cierra la
  sesión como la lectura de la sesión actual. La vista lo recibe por `AuthorizedSendProvider` en el shell.
- Los `PUT` (formulario y activar o desactivar desde la fila) envían el cuerpo completo con `isActive`; los
  campos inmutables tras crear (modalidad del grado; año y grado de la sección) se muestran de solo lectura
  al editar. Una sección sin cupo omite `capacity`. Fechas con entrada nativa y formato `Intl.DateTimeFormat`
  `es-HN` (en UTC, para no mover el día). Sin dinero y sin `Idempotency-Key`; sin actualización optimista.
- Tras toda escritura se invalida `organization` completo, porque un año o una modalidad renombrados también
  son la etiqueta de las listas de grados y secciones.

**Límites conocidos**

- Los selects (filtros y alta) y los nombres de las filas salen de listas de apoyo de hasta 200 filas
  (máximo de la API): con más de 200 años, modalidades o grados faltarían opciones y la fila mostraría
  «No disponible». Si la institución crece, hace falta un endpoint de búsqueda.
- No hay selector de tamaño de página en pantalla (el parámetro `pageSize` de la URL sí se respeta).
- La puerta de accesibilidad con Playwright y el simulador de API de `e2e/` no se ampliaron a `/organization`;
  la accesibilidad se cubre con pruebas de componente (etiquetas, descripciones de error, foco del diálogo,
  regiones `aria-live`). Falta correr axe sobre estas rutas.
- Se tocó fuera de la superficie de pantallas: `access-session.ts` (método `authorized`), su prueba
  `access-session-authorized.test.ts` y el inventario de rutas de `features/identity/routes.test.tsx`.

**Pruebas** (Vitest, Testing Library y MSW sobre rutas, capa de consultas y transporte reales): un archivo por
vista con los flujos de listado, página y orden enviados al servidor, URL, alta, error de campo, cada código
de negocio, `403`, estados vacío y de error con reintento, edición y desactivación; pruebas de componente de
cada pieza de presentación; y pruebas de operaciones, razones de esquema, formato de fecha y clasificación de
fallos.

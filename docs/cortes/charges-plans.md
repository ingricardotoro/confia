# Corte: planes de cobro (charges)

Fase F3, corte 2 (`charges-plans` de la propuesta de `docs/cortes/ledger-core.md`). Primer corte del módulo `charges`:
define **qué se devenga, con qué periodicidad y a qué población**. No genera cargos ni escribe en el libro mayor; eso
llega con el motor de devengo (corte 3).

## Objetivo

Que el personal administrativo configure planes de cobro por modalidad, grado o sección: una lista de conceptos del
catálogo, cuántos períodos se cobran y desde cuándo, con la periodicidad y el día de vencimiento. El precio no se guarda
aquí: lo resuelve el motor, por fecha, en las listas de precios (F2).

## Fuera de alcance

- Generar cargos, aplicar precios, becas y descuentos, y escribir en el libro (corte 3, INV-14).
- Cargos manuales y ajustes (corte 4). Estado de cuenta (corte 5).
- Resolver conflictos entre planes que cubren al mismo estudiante: lo hará el motor, que es idempotente por
  estudiante, concepto y período.
- Borrar un plan o un renglón: no existe. Un plan se archiva.

## Modelo y reglas (`docs/02-modelo-de-dominio.md` §3.4)

`charges_plan`: `institution_id`, `id`, `academic_year_id`, `name`, `target_scope` (`MODALITY`, `GRADE`, `SECTION`),
`target_ref_id` (modalidad, grado o sección de la misma institución; ámbito y referencia no cambian después),
`frequency` (`MONTHLY`, `BIMONTHLY`, `ANNUAL`, `ONE_TIME`), `due_day_of_month` (1 a 28, para que exista en todos los
meses), `status` (`DRAFT`, `ACTIVE`, `ARCHIVED`), `version`, `created_at`, `updated_at`.

`charges_plan_item`: `institution_id`, `id`, `plan_id`, `payment_concept_id`, `periods_count` (1 a 36),
`first_period_on` (primer día del primer período), `created_at`.

- **Estados:** un plan nace en `DRAFT`. Mientras es borrador se pueden agregar y quitar renglones y cambiar nombre,
  periodicidad y día. `ACTIVE` lo congela: solo cambian el nombre y el estado (a `ARCHIVED`). `ARCHIVED` es final. Para
  cambiar un plan activo se crea otro; así un plan que ya sirvió para devengar nunca cambia por debajo de sus cargos.
- Activar exige al menos un renglón. Un concepto aparece una sola vez por plan.
- El concepto debe existir en la institución y estar activo al agregarlo. Un concepto gravado sin tasa se acepta (el motor
  lo tratará como error de cálculo, nunca como impuesto cero).
- Año lectivo, modalidad, grado y sección deben ser de la institución de la sesión (404 si no). La institución sale de la
  sesión, nunca de la petición. Aislamiento por RLS forzada. Sin `DELETE` para ningún rol.
- Edición concurrente con `version` (409 `stale-version`); agregar o quitar renglones bloquea la fila del plan
  (`FOR UPDATE`) para que activar y editar no se crucen.
- Toda alta y cambio escribe en la bitácora de auditoría.

## API (módulo `charges`, permisos nuevos `charges:read|create|update`)

| Ruta | Permiso | Resultado |
|---|---|---|
| `GET /api/v1/charges/plans` | read | Página; filtros `academicYearId`, `targetScope`, `status` |
| `POST /api/v1/charges/plans` | create | 201 en `DRAFT` |
| `GET /api/v1/charges/plans/{id}` | read | Plan con sus renglones |
| `PUT /api/v1/charges/plans/{id}` | update | Nombre, periodicidad, día y estado; exige `version`; 409 si el estado o el contenido no lo permite |
| `POST /api/v1/charges/plans/{id}/items` | update | 201 renglón; solo en `DRAFT` |
| `DELETE /api/v1/charges/plans/{id}/items/{itemId}` | update | 204; solo en `DRAFT` (el renglón no es un dato financiero) |

Los permisos nuevos se agregan al catálogo y a la matriz de roles (`docs/03-seguridad.md` §5.2) siguiendo el patrón de
`catalog:*`.

## Pantallas (admin-web)

«Planes de cobro» dentro de una sección nueva «Cobros» de la navegación: lista paginada con filtros, alta, detalle con los
renglones, diálogo «Agregar concepto», activar y archivar. Estados de carga, vacío, error y éxito; textos en el catálogo
es-HN. Esta parte del corte espera a que el rediseño visual esté consolidado.

## Casos límite a probar

Día de vencimiento 0, 29 y 31; períodos 0 y 37; ámbito y referencia de otra institución; concepto inactivo o de otra
institución; concepto repetido en el plan; activar sin renglones; editar o agregar renglones en un plan activo o archivado;
archivar y reactivar (la reactivación no existe); `version` desfasada; otra institución no ve ni escribe; rol sin permiso
(403); dos activaciones simultáneas (una gana); la base rechaza renglones de un plan que no es borrador.

## Criterio de aceptación

Un administrador crea el plan «Primaria 2027» para el grado de primaria, mensual con vencimiento el día 5, le agrega
«Colegiatura» por diez períodos desde febrero y «Matrícula» por un período, lo activa, y ya no puede cambiarle los
renglones; otra institución no ve el plan.

## Decisiones del backend

Puntos que la nota no fijaba y que el backend resolvió; el propietario puede cambiarlos sin tocar el resto.

- **Transiciones:** solo `DRAFT` a `ACTIVE` y `ACTIVE` a `ARCHIVED`. Un borrador no se archiva directamente (se edita o se activa).
  Cualquier otra transición, y todo cambio de un plan archivado, responde `409 plan-not-editable`.
- **Primer día del primer período:** debe ser el primer día de un mes (entre 2000-01-01 y 2100-12-01); la base lo exige con un `CHECK`.
  Relajarlo después es compatible con los datos; endurecerlo no.
- **Códigos de error nuevos** (todos `409`): `plan-not-editable`, `plan-without-items` y `duplicate-plan-item` (un concepto repetido en el plan;
  la nota no nombraba el código). Se reutilizan `stale-version`, `inactive-reference`, `resource-not-found` y `validation-failed`.
- **Permisos:** el catálogo ya tenía `charges:create|read|update`; faltaba que el Administrador tuviera `charges:update` (la matriz
  efectiva decía `C L N P X`). Ahora es `C L A N P X`, con nota fechada en `docs/03-seguridad.md` §5.2.
- **Sección y año:** no se exige que la sección del objetivo pertenezca al año lectivo del plan; solo que exista en la institución.
  Queda como pregunta abierta para el motor de devengo (corte 3).
- **Sin `DELETE` de planes para ningún rol.** Los renglones sí se borran, solo con el plan en borrador (el único `DELETE` concedido).

## Construido (backend)

- **Migración `V31__create_charges_plan.sql`:** `charges_plan` y `charges_plan_item` con llaves compuestas con la institución (año,
  modalidad, grado y sección como llaves foráneas reales mediante columnas generadas por ámbito; concepto del catálogo), `CHECK` de ámbito,
  periodicidad, estado, día de vencimiento 1 a 28, períodos 1 a 36 y primer día de mes, un concepto una sola vez por plan, RLS forzada.
  Disparadores: el plan nace en `DRAFT`, año, objetivo y creación no cambian, solo `DRAFT` a `ACTIVE` a `ARCHIVED`, un plan activo conserva
  periodicidad y día, un archivado no cambia, no se activa sin renglones y no se borra (tampoco el dueño); los renglones solo se insertan,
  cambian o se borran con el plan en `DRAFT` (la fila del plan se lee `FOR SHARE`, así activar y agregar un renglón no se cruzan).
  Permisos: `SELECT, INSERT` y `UPDATE (name, frequency, due_day_of_month, status, version, updated_at)` sobre el plan;
  `SELECT, INSERT, DELETE` sobre los renglones y ningún `UPDATE`; `SELECT` a `confia_readonly`; nada al portal.
- **Dominio puro** (`com.confia.charges.domain`): `ChargePlan` (estados, versión, día 1 a 28; `edit` valida versión, transición y congelación
  y exige renglones para activar) y `ChargePlanItem` (períodos 1 a 36, primer día de mes), enums y errores.
- **Aplicación:** `ManageChargePlans` (crear, editar, listar, detalle, agregar y quitar renglón). Bloqueo `FOR UPDATE` del plan en toda
  escritura y luego `FOR SHARE` del concepto; auditoría `charges.plan.created|updated|activated|archived` y
  `charges.plan_item.added|removed` en la misma transacción.
- **Publicaciones entre módulos** (ADR-0022): `organization.application.directory.AcademicStructureDirectory.sectionExists` y la interfaz nueva
  `catalog.application.directory.PaymentConceptDirectory` (solo existencia y estado activo del concepto, con `FOR SHARE`).
- **Infraestructura y web:** `JooqChargePlanRepository`, `JooqChargePlanItemRepository`, `ChargePlanController` (seis rutas bajo
  `/api/v1/charges/plans`, permisos en `PermissionedEndpoints`) y la configuración, importada solo por `AdminApplication` y declarada en
  `ProcessBeanPolicy` (el portal y el trabajador la tienen prohibida).
- **Contrato:** instantánea OpenAPI y `packages/contracts` regenerados (seis operaciones nuevas, sin cambios en las existentes).
- **Pruebas:** `ChargePlanTest`, `ChargePlanItemTest` y `ManageChargePlansTest` (reglas), `ChargesPlanSchemaIT` (base real: checks,
  referencias, estados, renglones solo en borrador, grants, RLS) y `ChargePlanEndpointsIT` (criterio de aceptación, casos límite,
  activaciones simultáneas, roles, aislamiento y auditoría).
- **Pendiente para otros cortes:** generación de cargos (corte 3), resolver planes que cubren al
  mismo estudiante.

## Construido (frontend)

Sección «Cobros» (grupo Finanzas) con «Planes de cobro», en `apps/admin-web`, sobre el rediseño visual consolidado.

- **Rutas y navegación:** `/charges/plans` (lista) y `/charges/plans/$planId` (detalle); `/charges` redirige a la lista. La entrada «Cobros» está
  en la navegación, grupo Finanzas, detrás del catálogo. Estado de la lista (página, tamaño, orden por nombre o fecha, año lectivo, ámbito y
  estado) en la URL, validado con Zod; paginación, orden y filtros los hace el servidor.
- **Lista:** tabla con nombre (enlace al detalle), año lectivo, ámbito, modalidad/grado/sección, periodicidad, día de vencimiento y estado
  (insignia con punto); diálogo «Nuevo plan de cobro» (nombre, año, ámbito, referencia que sigue al ámbito y se vacía al cambiarlo,
  periodicidad, día 1 a 28) que crea el borrador. La sección se nombra con su grado y su año («Primero A (Año lectivo 2026)»).
- **Detalle:** tarjeta de resumen, tabla de renglones (concepto, períodos, primer período), diálogo «Agregar concepto» (solo conceptos activos que
  el plan aún no tiene; períodos 1 a 36; primer día del primer período validado como primer día de mes, con mensaje claro), quitar renglón,
  «Activar plan» (verde, confirmación que explica el congelamiento), «Archivar plan» (rojo, confirmación) y «Editar plan» (nombre, periodicidad y
  día en borrador; solo el nombre en un plan activo). Un plan archivado es solo de consulta. Cada acción envía la `version` leída; ante 409 se
  relee el plan y se muestra el mensaje en español de `plan-not-editable`, `plan-without-items`, `duplicate-plan-item`, `stale-version`,
  `inactive-reference` y `resource-not-found`.
- **Estados:** carga (esqueleto), vacío (con acción), error con reintento e identificador de soporte, y éxito en la región viva; todo el texto está
  en el catálogo es-HN (`charges.*`, `problem.*`, `navigation.charges`). Los nombres de botón con `aria-label` contienen el texto visible.
- **Arquitectura:** feature nueva `src/features/charges` (contenedores, componentes de presentación puros en `components/`, claves de consulta,
  formularios y búsqueda propios) con su cliente en `shared/api/charges-operations.ts`. Para no importar otra feature se movieron a `shared/` lo
  mínimo: las claves de los conceptos (`shared/query-keys/payment-concept-keys.ts`, que el catálogo reexporta con los mismos valores),
  `useConceptLookup` (`shared/catalog-lookups`), `useSectionLookup` (`shared/organization-lookups`) y el diálogo `ConfirmDialog`
  (`shared/resource-screen/components`). Los tres códigos de problema nuevos están en `problem.ts`, `problem-keys.ts` y `resource-failure.ts`.
  Ninguna mutación es financiera (el plan no guarda precios ni asienta nada), por lo que no lleva `Idempotency-Key`; ninguna es optimista y
  toda escritura invalida el prefijo `["charges", "plans"]`. El botón de confirmar queda ocupado mientras la solicitud está en vuelo.
- **Simulador (`e2e/mock-api`):** `charges-model.ts` y `charges-seed.ts` (28 planes: activo, borrador vacío, archivado y borrador con renglón, más 24
  borradores genéricos), con sus reglas de estados, renglones solo en borrador, concepto activo y no repetido, versión desfasada y 403; presets
  `charges-empty`, `charges-stale-version`, `charges-forbidden` y `charges-server-error`. La sesión simulada no lleva permisos (la interfaz no los
  decide), así que `charges:*` no cambia nada del lado del cliente: el 403 se simula por escritura.
- **Pruebas:** cliente (`charges-operations.test.ts`), lógica de formularios y referencias, vistas, contenedores sobre MSW (lista, detalle, alta de
  renglón, quitar, activar, archivar, editar, rechazos y estados de carga), `ConfirmDialog`, simulador (`charges-model.test.ts` y bloque en
  `server.test.ts`), recorridos Playwright `charges-plans-crud|keyboard|lists|states` y 18 estados nuevos en la matriz de accesibilidad.
- **Decisiones de interfaz:** el primer período se elige con un campo de fecha (no `type="month"`, que Firefox no admite) y se valida que sea el
  día 1; el cálculo de qué períodos cubre un renglón no se hace en el navegador (sin columna «último período»). Los renglones de un plan se
  quitan con confirmación aunque no sean datos financieros, porque quitarlos es irreversible mientras no se vuelva a agregar.

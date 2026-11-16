# Corte: listas de precios (catalog)

Fase F2, entregable 2 («Listas de precios con vigencia desde/hasta»). Segundo corte del módulo `catalog`. Sigue a
`catalog-payment-concepts` y deja listo el precio que el motor de devengo (F3) consultará.

## Objetivo

Que el personal administrativo defina cuánto cuesta cada concepto: una lista de precios por año lectivo (y, si hace falta,
por modalidad o grado) con un precio por concepto y su vigencia. Un cambio de precio crea una vigencia nueva; el precio
anterior queda intacto.

## Fuera de alcance

- Tasas de impuesto y su vínculo con el concepto (corte 3), descuentos y becas, simulador (F2 posteriores) y cargos (F3).
- Borrar una lista o un precio: no existe. Una lista se archiva; un precio se cierra con una fecha y se sustituye.
- Resolver «qué precio aplica a un estudiante»: lo hará el motor de devengo en F3. Aquí solo se guardan y se consultan.

## Modelo y reglas (`docs/02-modelo-de-dominio.md` §3.3)

`catalog_price_list`: `institution_id`, `id`, `academic_year_id`, `name`, `scope` (`INSTITUTION`, `MODALITY`, `GRADE`),
`scope_ref_id` (nulo si `INSTITUTION`; obligatorio y de la misma institución en los otros dos), `status` (`ACTIVE`,
`ARCHIVED`), `version`, `created_at`, `updated_at`.

`catalog_price_list_item`: `institution_id`, `id`, `price_list_id`, `payment_concept_id`, `amount NUMERIC(14,4)` y
`currency` (ISO 4217), `valid_from`, `valid_to` (inclusivo; nulo = sin fin), `created_by`, `created_at`.

- **INV-11:** dos precios del mismo concepto dentro de la misma lista no pueden solapar su vigencia. Lo rechaza la base de
  datos con `EXCLUDE USING gist` sobre `(price_list_id, payment_concept_id, daterange(valid_from, valid_to, '[]'))`.
- **Un precio no se edita** (INV-12, regla anticipada: en F2 aún no hay cargos). Ni el importe, ni la moneda, ni el concepto,
  ni `valid_from` cambian jamás. La única mutación permitida es cerrar una vigencia abierta (`valid_to` de nulo a una
  fecha, una sola vez), y ocurre dentro de la operación «nueva vigencia». Sin `DELETE` para ningún rol.
- **Nueva vigencia:** al agregar un precio de un concepto que ya tiene una vigencia abierta anterior, el servidor la cierra
  el día previo a `valid_from` y inserta la nueva, en una sola transacción. Cualquier otro solape se rechaza (409).
- Importe mayor que cero, escala cuatro, moneda explícita; el concepto debe existir, ser de la institución y estar activo.
- Año lectivo, modalidad y grado deben ser de la institución de la sesión; la institución sale de la sesión, nunca de la
  petición. Aislamiento por RLS forzada. Toda alta y cambio escribe en la bitácora de auditoría.
- **Concurrencia (cierra B-1 del corte 1):** agregar un precio bloquea la fila de la lista (`FOR UPDATE`); editar una lista o
  un concepto exige `version` y responde 409 si no coincide. La migración agrega `version` al concepto de pago.

## API (permisos `catalog:read|create|update`)

| Ruta | Permiso | Resultado |
|---|---|---|
| `GET /api/v1/catalog/price-lists` | read | Página; filtros `academicYearId`, `scope`, `status` |
| `POST /api/v1/catalog/price-lists` | create | 201; año, ámbito y referencia no cambian después |
| `GET /api/v1/catalog/price-lists/{id}` | read | Lista con sus precios (todas las vigencias) |
| `PUT /api/v1/catalog/price-lists/{id}` | update | Nombre y estado (`ACTIVE`/`ARCHIVED`); exige `version` |
| `POST /api/v1/catalog/price-lists/{id}/items` | create | 201 nueva vigencia; 409 `price-validity-overlap` |

Una lista archivada no admite precios nuevos (409). El importe viaja como `{ amount, currency }`.

## Pantallas (admin-web)

Pestaña «Listas de precios» en Catálogo: lista paginada con filtros; detalle con la tabla de precios por concepto y su
historial de vigencias, diálogo «Nuevo precio / nueva vigencia» y archivar. Importes con numeración tabular, a la derecha y
con moneda. Estados de carga, vacío, error y éxito. Textos en el catálogo es-HN.

## Casos límite a probar

Solape exacto, parcial, contiguo (permitido) y contenido; vigencia abierta cerrada por la nueva; `valid_to` anterior a
`valid_from`; importe cero, negativo y con más de cuatro decimales; moneda inválida; concepto inactivo o de otra
institución; lista archivada; otra institución no ve ni escribe; rol sin permiso (403); `version` desfasada (409); dos
altas concurrentes del mismo concepto (una gana, la otra 409); la base rechaza `UPDATE` del importe y `DELETE`.

## Criterio de aceptación

Un administrador crea la lista «2027 — Institución», le fija «Colegiatura» a L 1 500,00 desde el 1 de enero, y el 1 de julio
registra L 1 650,00: la primera vigencia queda cerrada el 30 de junio y ambas se ven en el historial; la base rechaza
cualquier solape y ningún usuario ve listas de otra institución.

## Construido (backend)

- Migración `V28__create_catalog_price_list.sql`: tablas `catalog_price_list` y `catalog_price_list_item` con clave
  primaria `(institution_id, id)`, llaves foráneas compuestas (año lectivo, lista, concepto) y `CHECK` de ámbito, estado,
  referencia del ámbito, importe mayor que cero, moneda (`HNL` o `USD`, el mismo conjunto del núcleo) y `valid_to >=
  valid_from`. La referencia del ámbito (`scope_ref_id`) se parte en dos columnas generadas, `modality_id` y `grade_id`,
  cada una con su llave foránea hacia `organization_modality` y `organization_grade`; una llave con la columna nula no se
  evalúa, así que la base prueba que la modalidad o el grado son de la misma institución.
- INV-11 en la base: `EXCLUDE USING gist` sobre `(institution_id, price_list_id, payment_concept_id,
  daterange(valid_from, valid_to, '[]'))`; un `valid_to` nulo es un rango sin fin, de modo que no hace falta `COALESCE`.
  Requiere `btree_gist`, instalada en un esquema propio (`extensions`) porque PostgreSQL asigna los objetos de una
  extensión de confianza al superusuario de arranque y las pruebas de migración exigen que `public` contenga solo objetos
  de `confia_owner`. `confia_owner` debe poder crear la extensión (es de confianza desde PostgreSQL 13).
- INV-12 anticipado: el rol de la aplicación solo tiene `UPDATE (valid_to)` sobre los precios y un disparador rechaza
  cualquier otro cambio y cualquier segundo cierre, también al dueño; sin `DELETE` para nadie (permiso y disparador). En
  la lista, `UPDATE (name, status, version, updated_at)` y un disparador que impide cambiar año, ámbito y referencia.
  RLS forzada en ambas tablas.
- La misma migración agrega `version BIGINT NOT NULL DEFAULT 0` (con `CHECK >= 0`) a `catalog_payment_concept`.
- Dominio (`PriceList`, `PriceListItem` con `Money` del núcleo, `PriceListScope`, `PriceListStatus`), caso de uso
  `ManagePriceLists`, adaptadores jOOQ y `PriceListController`. «Nueva vigencia» bloquea la fila de la lista
  (`SELECT ... FOR UPDATE`), cierra la vigencia abierta anterior el día previo y inserta la nueva en la misma transacción.
- Año, modalidad y grado se comprueban con el puerto nuevo `AcademicStructureDirectory` que publica `organization`
  (paquete `directory`, segundo consumidor tras `students`); lo que no existe en la institución responde 404
  `resource-not-found`.
- Códigos nuevos: `price-validity-overlap`, `price-list-archived` y `stale-version` (409); se reutiliza
  `inactive-reference` para un concepto inactivo. Auditoría `catalog.price_list.created|updated` y
  `catalog.price_list_item.created`, que lleva dentro la vigencia anterior cerrada (`closedPreviousItemId`,
  `closedPreviousValidTo`).
- Cierra B-1: `PUT` de la lista y del concepto exigen `version` (obligatoria, no negativa) y responden 409
  `stale-version` si no coincide; las respuestas traen `version`. El `UPDATE` lleva `WHERE version = ?`, así que dos
  ediciones simultáneas con la misma versión dejan una sola ganadora.

### Decisiones y supuestos

- La nueva vigencia reemplaza a la abierta solo cuando es también abierta (sin `validTo`) y empieza después; un precio
  acotado que cae dentro de una vigencia abierta se rechaza con 409, porque cerrarla dejaría días sin precio. Un precio
  acotado enteramente anterior a la vigencia abierta se agrega sin tocarla (historial cargado a posteriori).
- El importe de respuesta sale redondeado por el servidor a la unidad menor de la moneda (`HALF_UP`, dos decimales);
  la base conserva hasta cuatro. Un importe que no pueda redondearse sin salir de rango (por ejemplo `9999999999.9999`) se
  rechaza con 400 en lugar de guardarse y fallar al responder. El importe de entrada debe ser una cadena JSON: un número
  se rechaza con 400.
- Un importe con más de cuatro decimales lo rechaza el dominio y el borde; la base, por ser `NUMERIC(14,4)`, redondearía
  en silencio, por eso esa regla no se prueba a nivel de base.
- No hay `Idempotency-Key` en estas rutas: fijar un precio no mueve dinero ni toca el libro mayor.
- No existe aún un convertidor compartido de `Money` con `NUMERIC(14,4)` y moneda; el adaptador de precios lo construye en
  un único punto (`JooqPriceListItemRepository.toDomain`) y queda para consolidarlo cuando haya un segundo módulo.
- Cambios de contrato sobre el corte 1: `PUT /api/v1/catalog/payment-concepts/{id}` pasa a exigir `version` en el cuerpo
  y puede responder 409 `stale-version`; `PaymentConceptResponse` agrega `version`. Rutas nuevas: las cinco de
  `/api/v1/catalog/price-lists` y los esquemas `Money` (ya fijado), `AddPriceRequest`, `CreatePriceListRequest`,
  `UpdatePriceListRequest`, `PriceListResponse`, `PriceListDetailResponse`, `PriceListItemResponse` y
  `PriceListPageResponse`.

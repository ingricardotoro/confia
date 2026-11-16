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
`valid_from`; importe cero, negativo y con más de dos decimales; moneda inválida; concepto inactivo o de otra
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
- **Precisión del precio:** un precio admite como máximo dos decimales (la unidad menor de `HNL` y `USD`), de modo que lo
  guardado es lo mostrado; el servidor ya no redondea al responder. Se exige en el borde (patrón sin signo, dos decimales),
  en el dominio (`PriceListItem`) y en la base (`CHECK amount = round(amount, 2)`); la columna sigue siendo
  `NUMERIC(14,4)`, el tipo de todo importe del sistema. El importe de entrada debe ser una cadena JSON: un número se
  rechaza con 400. La petición usa el esquema `PriceInput` (sin signo); la respuesta usa `Money`.
- **Fechas:** `validFrom` y `validTo` deben estar entre 2000-01-01 y 2100-12-31, en el borde (400), en el dominio y en la
  base (`CHECK`), para que un año mal tecleado nunca llegue a un 500.
- **Carrera con el concepto:** al agregar un precio se bloquea primero la lista (`FOR UPDATE`) y luego el concepto
  (`FOR SHARE`) en la misma transacción, así un concepto no se desactiva mientras se le agrega un precio.
- **Permisos del concepto:** la migración restringe `UPDATE` de `catalog_payment_concept` a las columnas que la aplicación
  edita; el código y la institución ya no los puede cambiar el rol de la aplicación.
- No hay `Idempotency-Key` en estas rutas: fijar un precio no mueve dinero ni toca el libro mayor.
- Un convertidor compartido (`shared.infrastructure.money.MoneyColumns`) construye y descompone el par `NUMERIC(14,4)` más
  moneda; ningún repositorio usa `Money.of` ni `CurrencyCode` directamente (ADR-0015, regla 5).
- `btree_gist` queda documentada en `docs/03-seguridad.md` (§6.3.1).
- Cambios de contrato sobre el corte 1: `PUT /api/v1/catalog/payment-concepts/{id}` pasa a exigir `version` en el cuerpo
  y puede responder 409 `stale-version`; `PaymentConceptResponse` agrega `version`. Rutas nuevas: las cinco de
  `/api/v1/catalog/price-lists` y los esquemas `Money` (ya fijado), `PriceInput`, `AddPriceRequest`, `CreatePriceListRequest`,
  `UpdatePriceListRequest`, `PriceListResponse`, `PriceListDetailResponse`, `PriceListItemResponse` y
  `PriceListPageResponse`.

## Construido (frontend)

Pestaña «Listas de precios» dentro de Catálogo, en `apps/admin-web/src/features/catalog` (la funcionalidad no importa de
ninguna otra; las piezas genéricas vienen de `shared/resource-screen` y `shared/organization-lookups`).

- **Rutas.** `/catalog` pasa a ser el área (encabezado, subnavegación `CatalogTabs` y la vista hija) y conserva en su
  dirección la lista de conceptos de pago, de modo que los enlaces existentes siguen valiendo. Se agregan
  `/catalog/price-lists` (lista) y `/catalog/price-lists/$priceListId` (detalle). La lista valida su búsqueda con Zod
  (página, tamaño, orden por `name` o `createdAt`, año lectivo, ámbito y estado) y la lleva en la URL; paginación, orden
  y filtros los hace el servidor.
- **Lista.** Tabla con nombre (enlace al detalle), año lectivo, ámbito, modalidad o grado y estado; diálogo «Nueva lista
  de precios» con año (de las consultas de `organization`), ámbito y, para modalidad o grado, el selector de la
  referencia, que se olvida al cambiar el ámbito. Sin edición ni borrado: el año, el ámbito y la referencia no cambian.
- **Detalle.** Datos de la lista, archivar y reactivar (con la `version` leída) y, por cada concepto, una tabla con todas
  sus vigencias, la más reciente primero, con la abierta marcada «Abierta» y las demás «Cerrada». Diálogo «Nuevo precio /
  nueva vigencia» con el concepto (solo conceptos activos), el importe, la moneda (`HNL` o `USD`), `validFrom` y `validTo`
  opcional. Tras agregar un precio se vuelve a leer el detalle, porque el servidor cierra la vigencia anterior; una lista
  archivada deshabilita la acción y lo dice.
- **Dinero.** El importe viaja y se muestra como cadena. Se pinta con `MoneyAmount` del sistema de diseño
  (`Intl.NumberFormat`, numeración tabular, alineado a la derecha) más una columna de moneda. El navegador no convierte,
  redondea ni calcula: solo valida los caracteres (hasta diez enteros y cuatro decimales, sin signo, con algún dígito
  distinto de cero) para avisar antes de enviar; el redondeo a dos decimales lo entrega el servidor.
- **Contrato.** Zod generado para todas las peticiones y respuestas; sin `Idempotency-Key` y sin actualización
  optimista. Las mutaciones invalidan todo el prefijo `["catalog", "price-lists"]` (detalle y listados).
- **Concurrencia (cierra B-1 en pantalla).** El `PUT` del concepto envía la `version` cargada; ante 409 `stale-version`
  (concepto o lista) se muestra «El registro cambió desde que lo abrió…» y se vuelve a leer la consulta. Mensajes propios
  para `price-validity-overlap` y `price-list-archived`; `inactive-reference` y 404 reutilizan los existentes. Se
  agregaron los tres códigos a `KNOWN_PROBLEM_CODES` (ahora treinta y cinco) y a `PROBLEM_KEYS`.
- **Textos y estados.** Todo en el catálogo es-HN (`catalog.tabs`, `catalog.priceLists.*`, `problem.*`, `field.*`);
  carga con esqueleto, vacío con acción, error con reintento e identificador de soporte, y éxito en la región `aria-live`.
- **Simulador (`e2e/mock-api`).** Versiones en conceptos y listas, las cinco rutas nuevas con redondeo a dos decimales
  por dígitos, cierre de la vigencia abierta, solape y lista archivada; escenarios `catalog-price-lists-empty` y
  `catalog-stale-version` (la primera edición responde 409 una vez).
- **Pruebas.** Vitest: cliente, lógica de formularios, vistas, formularios, contenedores (lista y detalle) y simulador.
  Playwright: `catalog-price-lists-{crud,lists,states,keyboard}`, y trece estados nuevos en la matriz de accesibilidad.

### Decisiones y supuestos (frontend)

- `CatalogTabs` repite el patrón de `OrganizationTabs` en lugar de importarlo, porque una funcionalidad no puede importar
  de otra (`admin-features-never-import-features`). Moverlo a `shared` queda para cuando haya un tercer consumidor.
- Los nombres de concepto, año, modalidad y grado salen de consultas de una sola página del tamaño máximo (200), como el
  resto de las pantallas; con más de 200 conceptos el nombre faltante se muestra como «No disponible».
- El diálogo de edición de un concepto no se refresca solo tras un 409: se cierra y se vuelve a abrir con la versión
  vigente, y el mensaje lo dice. Hacerlo sin perder lo escrito exigiría decidir qué valor gana, y eso es del usuario.
- Un importe cero o una vigencia con fin anterior al inicio los rechaza ya el cliente; si el servidor los rechazara sin
  nombrar un campo, se muestra el mensaje general «No se pudieron procesar los datos enviados».

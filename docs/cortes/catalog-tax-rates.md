# Corte: tasas de impuesto (catalog)

Fase F2, entregable 3 («Tasas de impuesto con vigencia»). Tercer corte del módulo `catalog`. Sigue a
`catalog-payment-concepts` y `catalog-price-lists`; deja la tasa que el motor de devengo (F3) resolverá por fecha.

## Objetivo

Que el personal administrativo configure las tasas de impuesto (por ejemplo, el impuesto sobre ventas) con su vigencia, y
que cada concepto gravado indique cuál le corresponde. Una tasa nunca se edita: un cambio de porcentaje es una vigencia
nueva (`docs/04-cumplimiento-fiscal-sar.md` §7).

## Fuera de alcance

- Calcular impuestos sobre cargos o facturas (F3/F4) y el tratamiento de operaciones exentas y exoneradas, que son
  distintos entre sí y quedan para el corte fiscal. Aquí «no gravado» sigue siendo `isTaxable = false`.
- **Ninguna tasa se escribe en el código ni se siembra.** Los porcentajes los carga el administrador; la tasa general vigente
  en Honduras está **PENDIENTE DE VALIDACIÓN** contra la resolución del SAR.
- Mapeo contable (`accountMappingId`, F3), borrar una tasa o una vigencia: no existe.

## Modelo y reglas (`docs/02-modelo-de-dominio.md` §3.3)

La tasa se separa de sus vigencias para que el concepto apunte a una identidad estable.

`catalog_tax_rate`: `institution_id`, `id`, `code` (mismo patrón y unicidad sin distinguir mayúsculas que el código del
concepto; no cambia), `name`, `is_active`, `version`, `created_at`, `updated_at`.

`catalog_tax_rate_period`: `institution_id`, `id`, `tax_rate_id`, `rate_percent NUMERIC(7,4)`, `valid_from`, `valid_to`
(inclusivo; nulo = sin fin), `created_by`, `created_at`.

- Porcentaje mayor que 0 y hasta 100, con hasta cuatro decimales, como texto en la API (nunca número JSON).
- **Sin solape** de vigencias de la misma tasa, garantizado por la base de datos con `EXCLUDE USING gist` (misma técnica que
  INV-11 en los precios). Fechas acotadas a 2000-01-01…2100-12-31.
- **Una vigencia no se edita:** la única mutación permitida es cerrar la abierta (`valid_to` de nulo a una fecha, una vez),
  dentro de la operación «nueva vigencia», que cierra la anterior el día previo y inserta la nueva en una transacción,
  con la fila de la tasa bloqueada (`FOR UPDATE`). Sin `DELETE` para ningún rol.
- La tasa se edita solo en `name` e `is_active`, con `version` (409 `stale-version` si no coincide). Una tasa inactiva no
  admite vigencias nuevas ni se puede asignar a un concepto.
- **Concepto:** gana `tax_rate_id` (opcional en la base). Un concepto gravado debe indicar una tasa al crearse o
  editarse; uno no gravado no puede tenerla (`CHECK`). Los conceptos gravados ya existentes sin tasa se toleran hasta que
  se editen. La tasa debe ser de la institución y estar activa (404 si es de otra; 409 `inactive-reference` si está inactiva).
- Aislamiento por RLS forzada; la institución sale de la sesión. Toda alta y cambio escribe en la bitácora de auditoría.

## API (permisos `catalog:read|create|update`)

| Ruta | Permiso | Resultado |
|---|---|---|
| `GET /api/v1/catalog/tax-rates` | read | Página; filtro `active`; orden por código o nombre |
| `POST /api/v1/catalog/tax-rates` | create | 201; 409 `duplicate-tax-rate-code` |
| `GET /api/v1/catalog/tax-rates/{id}` | read | Tasa con todas sus vigencias |
| `PUT /api/v1/catalog/tax-rates/{id}` | update | Nombre y estado; exige `version` |
| `POST /api/v1/catalog/tax-rates/{id}/periods` | create | 201 nueva vigencia; 409 `tax-rate-period-overlap` |

Las rutas de conceptos aceptan y devuelven `taxRateId` (y el código y nombre de la tasa en la respuesta).

## Pantallas (admin-web)

Pestaña «Tasas de impuesto» en Catálogo: lista con filtro de estado, alta de tasa, detalle con el historial de vigencias,
diálogo «Nueva vigencia» y activar o desactivar. El formulario del concepto muestra la tasa (solo activas) cuando el concepto
es gravado. Estados de carga, vacío, error y éxito; textos en el catálogo es-HN.

## Casos límite a probar

Código repetido con otra capitalización; porcentaje 0, negativo, mayor que 100, con más de cuatro decimales y como número
JSON; solape exacto, parcial, contenido y contiguo (permitido); vigencia abierta cerrada por la nueva; fechas fuera de
ventana; tasa inactiva; tasa de otra institución; concepto gravado sin tasa y no gravado con tasa; otra institución no ve
ni escribe; rol sin permiso (403); `version` desfasada (409); dos altas concurrentes de vigencia (una gana); la base
rechaza `UPDATE` del porcentaje y `DELETE`.

## Criterio de aceptación

Un administrador crea la tasa «ISV» sin ninguna cifra precargada, le registra un porcentaje desde una fecha y luego otro
posterior (la primera vigencia queda cerrada el día previo), asigna la tasa a «Colegiatura» (gravado) y ve ambas vigencias en
el historial; la base rechaza cualquier solape y ningún usuario ve tasas de otra institución.

## Construido (backend)

- Migración `V29__create_catalog_tax_rate.sql`: tablas `catalog_tax_rate` y `catalog_tax_rate_period` con clave primaria
  `(institution_id, id)`, llave foránea compuesta de la vigencia hacia la tasa, código con el mismo patrón del concepto y
  único sin distinguir mayúsculas (`catalog_tax_rate_code_uq`), `CHECK` de porcentaje (`rate_percent > 0 AND <= 100`,
  columna `NUMERIC(7,4)`), `valid_to >= valid_from` y ventana 2000-01-01…2100-12-31. RLS forzada en ambas tablas. No se
  siembra ninguna cifra.
- INV-11 en la base: `EXCLUDE USING gist` sobre `(institution_id, tax_rate_id, daterange(valid_from, valid_to, '[]'))`
  (`catalog_tax_rate_period_validity_excl`); `btree_gist` ya estaba instalada por V28.
- Inmutabilidad: el rol de la aplicación solo tiene `UPDATE (valid_to)` sobre las vigencias y un disparador rechaza
  cualquier otro cambio y cualquier segundo cierre, también al dueño; sin `DELETE` para nadie (permiso y disparador). En la
  tasa, `UPDATE (name, is_active, version, updated_at)` y un disparador que impide cambiar el código y la creación.
- `catalog_payment_concept` gana `tax_rate_id UUID` (nulo), llave foránea compuesta `(institution_id, tax_rate_id)` hacia
  la tasa, `CHECK (is_taxable OR tax_rate_id IS NULL)` e índice; se agrega `GRANT UPDATE (tax_rate_id)` al rol de la
  aplicación. Los conceptos gravados existentes sin tasa se toleran.
- Dominio: `TaxRate`, `TaxRatePeriod` y el objeto de valor `RatePercent` (un porcentaje no es dinero: `BigDecimal` a escala
  cuatro, mayor que cero y hasta 100, un quinto decimal se rechaza y nunca se redondea). `PaymentConcept` incorpora
  `taxRateId`: `create` y `edit` exigen tasa si es gravado; uno no gravado con tasa es inválido siempre.
- Casos de uso `ManageTaxRates` (crear, editar, listar, detalle, nueva vigencia) y `ManagePaymentConcepts` (ahora con
  `TaxRateRepository`); adaptadores jOOQ `JooqTaxRateRepository` y `JooqTaxRatePeriodRepository`; `TaxRateController` con
  las cinco rutas de `/api/v1/catalog/tax-rates`. «Nueva vigencia» bloquea la fila de la tasa (`FOR UPDATE`), cierra la
  vigencia abierta anterior el día previo y inserta la nueva en la misma transacción, con las mismas reglas que los precios.
  Editar la tasa también toma `FOR UPDATE`.
- Concepto: crear y editar aceptan `taxRateId`; la tasa se lee `FOR SHARE` en la misma transacción que la escritura, así no
  se desactiva entre medias. La respuesta agrega `taxRateId`, `taxRateCode` y `taxRateName` (nulos si no tiene tasa); la
  lista las resuelve con una sola consulta por página.
- Códigos nuevos: `duplicate-tax-rate-code` y `tax-rate-period-overlap` (409); se reutilizan `stale-version` e
  `inactive-reference`. Auditoría `catalog.tax_rate.created|updated` y `catalog.tax_rate_period.created` (con
  `closedPreviousPeriodId` y `closedPreviousValidTo`); las entradas del concepto llevan `taxRateId`.

### Decisiones y supuestos

- **Códigos de error del concepto:** gravado sin tasa y no gravado con tasa son `400 validation-failed` (regla entre campos
  del mismo cuerpo, verificada en el dominio); tasa de otra institución o inexistente, `404 resource-not-found`; tasa
  inactiva, `409 inactive-reference`. El orden es 400, luego 404, luego 409.
- **Tasa ya asignada y luego desactivada:** una edición que conserva la misma tasa no exige que esté activa, de modo que el
  concepto sigue pudiendo renombrarse o desactivarse; solo asignar o cambiar a una tasa inactiva responde 409.
- **Porcentaje:** cadena JSON obligatoria (un número se rechaza con 400), patrón sin signo `^\d{1,3}(\.\d{1,4})?$`; el
  dominio rechaza 0 y mayores que 100. Se devuelve siempre con cuatro decimales exactos (`"15.0000"`), tal como se guarda;
  no se redondea.
- Una tasa inactiva no admite vigencias nuevas (`409 inactive-reference`); sí admite edición, para reactivarla.
- **Fechas:** igual que los precios, 2000-01-01…2100-12-31 en el borde (400), el dominio y la base.
- No hay `Idempotency-Key`: fijar una tasa no mueve dinero ni toca el libro mayor.
- Cambios de contrato: `CreatePaymentConceptRequest` y `UpdatePaymentConceptRequest` agregan `taxRateId` (opcional);
  `PaymentConceptResponse` agrega `taxRateId`, `taxRateCode` y `taxRateName`; `POST` y `PUT` de conceptos documentan 404, y
  un gravado sin tasa responde 400. Rutas nuevas: las cinco de `/api/v1/catalog/tax-rates` y los esquemas
  `CreateTaxRateRequest`, `UpdateTaxRateRequest`, `AddTaxRatePeriodRequest`, `TaxRateResponse`, `TaxRateDetailResponse`,
  `TaxRatePeriodResponse` y `TaxRatePageResponse`.

## Construido (frontend)

Todo en `apps/admin-web`, dentro de la funcionalidad `catalog` (sin importar de otras funcionalidades; las tasas que
ofrece el formulario del concepto se leen con el cliente propio de `catalog`).

- **Pestaña «Tasas de impuesto»** en Catálogo (`/catalog/tax-rates`, hermana de «Conceptos de pago» y «Listas de precios»).
  Lista paginada, ordenada (código, nombre) y filtrada por estado en el servidor, con el estado en la URL (parámetros
  validados con Zod, igual que las otras listas). Alta de tasa con código y nombre (sin porcentaje), edición del nombre con
  el código de solo lectura, y activar o desactivar desde la fila enviando la `version` leída.
- **Detalle** (`/catalog/tax-rates/{id}`): datos de la tasa y el historial completo de vigencias, de la más nueva a la más
  antigua, con las cerradas y la abierta, el porcentaje tal como lo entrega el servidor (cuatro decimales y el signo %
  del catálogo de textos, formateado con `Intl.NumberFormat` sobre la cadena, sin convertirla a número), activar o
  desactivar con `version`, y el diálogo «Nueva vigencia» (porcentaje como texto, desde, hasta opcional).
- **Validación del cliente** del porcentaje sobre sus caracteres (nunca como número): patrón `^\d{1,3}(\.\d{1,4})?$`, mayor
  que cero y no mayor que 100, y fechas dentro de 2000-01-01…2100-12-31 con el mismo mensaje que el servidor da a
  `validity-day`; la autoridad sigue siendo el servidor. Sin ninguna cifra de porcentaje en el código, en los textos ni en
  datos de producción: los porcentajes de los simuladores y de las pruebas son ficticios.
- **Errores 409** con mensaje propio: `duplicate-tax-rate-code` (bajo el campo código), `tax-rate-period-overlap`,
  `inactive-reference` (también cuando se desactiva la tasa mientras se edita) y `stale-version`, que además vuelve a leer
  los datos. Sin actualización optimista y sin `Idempotency-Key` (no mueve dinero). Tras escribir se invalidan la lista, el
  detalle y las listas de conceptos (que muestran la tasa).
- **Formulario del concepto**: con «Gravado» marcado aparece la «Tasa de impuesto» (obligatoria, solo tasas activas más la que
  el concepto ya tenga aunque se haya desactivado después); al desmarcarlo se oculta y se descarta lo elegido, y el cuerpo
  no envía `taxRateId`. La lista de conceptos gana la columna «Tasa de impuesto» (código y nombre; «Sin tasa asignada» para un
  gravado anterior al corte y «No aplica» para uno no gravado). Activar o desactivar desde la fila reenvía la tasa que el
  concepto ya tiene.
- **Simulador (`e2e/mock-api`)**: cinco rutas de tasas con las reglas que usan los recorridos (código repetido, versión
  desfasada, solape, cierre del día previo, tasa inactiva, ventana de fechas, rango del porcentaje) y las reglas de
  `taxRateId` en el concepto (400, 404, 409); preajuste `catalog-tax-rates-empty`. Siembra ficticia: dos tasas activas con
  vigencias, una inactiva y veinticuatro genéricas.
- **Pruebas**: Vitest (cliente, lógica de formularios, vistas, componentes, contenedores y simulador), recorridos de
  Playwright (`catalog-tax-rates-crud|keyboard|lists|states`) y quince estados nuevos más uno del concepto en la matriz de
  accesibilidad.

### Decisiones y supuestos (frontend)

- El porcentaje se formatea pasando la cadena a `Intl.NumberFormat` (lectura decimal exacta) en `shared/format/format-rate.ts`;
  no se reutilizó `formatPercent`, que recibe un `number`.
- La edición del nombre está en la lista; el detalle solo activa o desactiva y registra vigencias.
- Un concepto gravado anterior al corte, sin tasa, no se puede activar ni desactivar desde la fila mientras no se edite y se
  le asigne una: el servidor exige la tasa en toda edición y responde 400, que la pantalla muestra con el mensaje genérico de
  datos no procesados. Si se quiere un mensaje propio, hace falta un código de error específico del backend.
- La opción «inactiva» de la tasa que el concepto ya tiene se muestra con el sufijo «(inactiva)» en el selector.

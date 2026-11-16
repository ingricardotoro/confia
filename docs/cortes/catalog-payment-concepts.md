# Corte: conceptos de pago (catalog)

Fase F2, entregable 1 («Conceptos de pago con categoría, recurrencia y condición de gravado»). Primer corte del módulo
`catalog`: el más delgado que ya muestra algo funcionando (listar, crear y editar).

## Objetivo

Que el personal administrativo defina qué se cobra: cada concepto tiene un código, un nombre, una categoría, si es
recurrente y si es gravado. Sin precios todavía: el precio llega con las listas de precios (corte 2).

## Fuera de alcance

- Listas de precios y vigencias (corte 2), tasas de impuesto y su vínculo con el concepto (`taxRateId`, corte 3), mapeo a
  cuentas contables (`accountMappingId`, F3), becas y descuentos.
- Borrar un concepto: no existe. Se desactiva (`isActive`); un concepto desactivado deja de ofrecerse para listas nuevas,
  pero nada financiero que lo referencie cambia.

## Modelo y reglas

`catalog_payment_concept` (`docs/02-modelo-de-dominio.md` §3.3): `institution_id`, `id`, `code`, `name`, `category`
(`TUITION`, `ENROLLMENT`, `TRANSPORT`, `MATERIAL`, `FINE`, `OTHER`), `is_taxable`, `is_recurring`, `is_active`,
`created_at`, `updated_at`.

- El código es único por institución (sin distinguir mayúsculas) y no cambia una vez creado: otros cortes lo referencian.
- La categoría es un conjunto cerrado, verificado por `CHECK` en la base de datos y por el dominio.
- Aislamiento por institución con política a nivel de fila; la institución sale de la sesión, nunca de la petición.
- Sin `DELETE` concedido a ningún rol. Toda alta y cambio escribe en la bitácora de auditoría.
- Permisos existentes: `catalog:read` (listar), `catalog:create` (crear), `catalog:update` (editar).

## API

| Ruta | Permiso | Resultado |
|---|---|---|
| `GET /api/v1/catalog/payment-concepts` | `catalog:read` | Página (orden por código o nombre; filtro por categoría y activo) |
| `POST /api/v1/catalog/payment-concepts` | `catalog:create` | 201 con el concepto; 409 `duplicate-name` si el código existe |
| `PUT /api/v1/catalog/payment-concepts/{id}` | `catalog:update` | 200; el código no se envía; 404 si no es de la institución |

## Pantallas (admin-web)

«Catálogo» en la navegación: lista paginada con filtros, formulario de alta y edición en diálogo, con los estados de
carga, vacío, error y éxito. Textos en el catálogo es-HN.

## Casos límite a probar

Código repetido con otra capitalización; código vacío o demasiado largo; categoría fuera del conjunto; otra institución
no ve ni edita el concepto; un rol sin permiso recibe 403; editar un concepto inexistente da 404; desactivar y reactivar.

## Criterio de aceptación

Un administrador crea «Colegiatura» (recurrente, gravado), la ve en la lista, la edita y la desactiva, todo auditado; la
base rechaza duplicados y categorías inválidas y ningún usuario ve conceptos de otra institución.

## Construido (backend)

- Migración `V27__create_catalog_payment_concept.sql`: tabla `catalog_payment_concept` con clave primaria
  `(institution_id, id)`, `CHECK` de categoría, índice único `catalog_payment_concept_code_uq` sobre
  `(institution_id, lower(code))`, RLS forzada, sin `DELETE` para ningún rol.
- Módulo `com.confia.catalog` (dominio, aplicación, infraestructura, web) con `PaymentConcept`, `PaymentCategory` y el
  caso de uso `ManagePaymentConcepts`; auditoría `catalog.payment_concept.created|updated`.
- Rutas `GET|POST /api/v1/catalog/payment-concepts` y `PUT /api/v1/catalog/payment-concepts/{id}`; el código no viaja en
  la edición y un cuerpo que lo traiga se rechaza con 400 `validation-failed`. La lista filtra por `category` y `active`
  y ordena por `code` (sin distinguir mayúsculas) o `name`.
- Un código duplicado (con cualquier capitalización) responde 409 `duplicate-name`; un identificador de otra
  institución responde 404 `resource-not-found`.

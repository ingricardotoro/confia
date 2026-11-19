# Corte: sesiones de caja (cashbox, corte 1)

Fase F4, corte 1. Es previo al registro de pagos (corte 2, `payments-register`): el efectivo exige una sesión abierta (INV-20), así
que la sesión de caja debe existir antes que el cobro. Este corte no registra pagos ni toca el libro mayor.

## Objetivo

Que un cajero abra su caja con un fondo inicial, registre retiros y depósitos de efectivo, y la cierre declarando el conteo físico; que
el sistema calcule lo esperado y la diferencia, y que una diferencia distinta de cero exija motivo y la aprobación de otra persona.
Que Contabilidad pueda consultar las sesiones y aprobar los cierres con diferencia.

## Reglas e invariantes

- **INV-22**: un usuario no puede tener más de una sesión `open` a la vez (índice único parcial por institución y cajero).
- **INV-21**: una sesión `closed` o `reconciled` no acepta movimientos (aplicación y disparador de base de datos).
- **INV-23**: `differenceAmount = declaredAmount − expectedAmount` (columna generada). Si es distinta de cero, `differenceReason` es
  obligatorio (20 a 500 caracteres) y el cierre queda `pending_approval` hasta que un aprobador **distinto del cajero** con
  `cashbox:approve` lo apruebe (segregación de funciones, `docs/03-seguridad.md` §5.3). Con diferencia cero el cierre queda `closed`.
- **Esperado** = fondo inicial + depósitos − retiros ± ajustes de los movimientos de la sesión. Se calcula en el servidor sobre
  `Money`; el navegador solo lo muestra. En este corte no hay `payment_in` (llega con el corte 2, sin cambiar la fórmula).
- Nada se borra ni se edita: movimientos y cierre son filas de solo inserción; el estado de la sesión se deriva o avanza por
  transiciones registradas (`open → closed | pending_approval → closed`). Una corrección es un movimiento `adjustment` con motivo.
- Una sesión es de una moneda (la de la institución, HNL). El fondo inicial es mayor o igual a cero; retiros y depósitos, mayores a cero.
- Idempotencia: `Idempotency-Key` obligatoria en abrir, mover, cerrar y aprobar. Toda acción escribe en la bitácora.

## Permisos (ya existen en `Permission`; sin cambio de matriz)

`cashbox:create` (Cajero y Super Administrador: abrir, mover, cerrar), `cashbox:read` (Cajero solo su sesión; Contabilidad,
Auditor y demás lectura), `cashbox:approve` (Contabilidad, Super Administrador). El Administrador no maneja efectivo.

## Endpoints (`/api/v1/cashbox`)

| Ruta | Permiso | Resultado |
|---|---|---|
| `POST /sessions` `{openingFloat}` | `cashbox:create` | 201 sesión abierta. 409 `cash-session-already-open` |
| `GET /sessions/current` | `cashbox:create` | Mi sesión abierta con esperado y movimientos, o 404 |
| `POST /sessions/{id}/movements` `{type, amount, notes}` | `cashbox:create` | 201 movimiento (`deposit`, `withdrawal`, `adjustment`) |
| `POST /sessions/{id}/close` `{declaredAmount, differenceReason?}` | `cashbox:create` | 200 cierre; `pending_approval` si hay diferencia |
| `POST /sessions/{id}/approve` `{note}` | `cashbox:approve` | 200 aprobada. 403 si el aprobador es el cajero |
| `GET /sessions` (`status`, `cashier`, `from`, `to`, `sort`, `page`, `size`) | `cashbox:read` | Página de sesiones |
| `GET /sessions/{id}` | `cashbox:read` | Detalle con movimientos y cierre (base del reporte imprimible) |

Otra institución e identificador desconocido responden igual: 404 `resource-not-found`.

## Pantallas (admin-web)

- **Mi caja** (`/cashbox`): sin sesión, formulario de apertura; con sesión, esperado, movimientos, registrar retiro o depósito y cerrar
  caja (conteo declarado, diferencia en vivo informativa y motivo cuando aplique).
- **Sesiones de caja** (`/cashbox/sessions`): listado filtrable y detalle; aprobar cierres pendientes para quien tiene `cashbox:approve`.
- El reporte imprimible del cierre es el detalle con estilos de impresión (firma del cajero y del aprobador).
- Estados de carga, vacío, error y éxito; bloqueo del reenvío mientras la petición está en curso.

## Casos límite

Segunda apertura (misma y distinta clave), apertura simultánea desde dos peticiones, fondo cero y negativo, movimiento sobre sesión
cerrada, retiro mayor al esperado (se permite pero el esperado no puede quedar negativo: se rechaza), cierre con diferencia cero,
positiva y negativa, cierre con diferencia sin motivo, aprobador igual al cajero, aprobar una sesión sin diferencia, doble
aprobación, cajero que consulta la sesión de otro, sesión de otra institución (aislamiento por fila, prueba como
`confia_admin_app`), reenvío con la misma clave.

## Criterio de aceptación

Un cajero abre con 500.00, deposita 100.00, retira 50.00 y cierra declarando 540.00: esperado 550.00, diferencia −10.00, queda
`pending_approval`; el mismo cajero no puede aprobarla, Contabilidad sí, y la sesión queda `closed` con todo en la bitácora. Una
segunda apertura mientras hay una abierta responde 409. Con la sesión cerrada, ningún movimiento nuevo entra, ni por la API ni por SQL.

## Fuera de alcance

Pagos y movimiento `payment_in` (corte 2), reembolsos (`refund_out`), conteo por denominaciones (`CashCount`), cajones físicos
(`CashDrawer`) y asientos contables del efectivo.

# Corte: anulación de cargos e integridad nocturna (charges, corte 3b)

Fase F3, corte 3b (`charges-void-and-integrity`, apéndice C de `docs/cortes/charges-accrual.md`). Decisiones del propietario ya
tomadas: el cargo anulado no se vuelve a devengar (decisión 4) y el reverso y el trabajo nocturno de integridad son este corte
(decisión 5). Alcance de esta entrega: **backend y contrato**; la pantalla queda para el siguiente paso.

## Objetivo

Que un administrador anule un cargo con motivo, dejando el libro mayor intacto y cuadrado (el reverso es el espejo exacto del
asiento del cargo), que pueda listar los cargos de un estudiante para elegir cuál anular, y que cada noche el sistema pruebe, por
institución, que cargos y libro siguen de acuerdo, dejando el resultado consultable.

## Reglas e invariantes

- **Anular** (`charges:void`, ya en `Permission`; Super Administrador y Administrador, sin cambio de matriz). Motivo de 20 a 500
  caracteres. Un cargo se anula **una sola vez**. Nada se borra ni se edita: el cargo no cambia (INV-47); la anulación es una fila
  nueva en `charges_charge_void` (solo inserción: quién, cuándo, motivo, transacción de reverso) y «anulado» se **deriva** de ella.
- **Misma transacción de base de datos** (INV-05, INV-41): bloqueo `FOR UPDATE` de la subcuenta del estudiante, relectura bajo el
  bloqueo, `LedgerPostingApi.reverseCharge` (espejo exacto, solo el módulo de origen) y la fila de anulación. La base lo exige: el
  disparador de la anulación pide un `REVERSAL` de `charges` que invierte el asiento del cargo y se creó en esta misma transacción.
- **INV-43 incluye a los anulados** (decisión 4): el motor no vuelve a devengar el período; la corrección es un cargo manual (corte 4).
- **Idempotencia.** `Idempotency-Key` obligatoria, guardada de forma permanente en la anulación con el hash de la petición: misma
  clave, mismo cuerpo y mismo actor devuelven la misma anulación; misma clave con otro cuerpo u otro actor, 422 `idempotency-payload-mismatch`; otra clave sobre
  un cargo ya anulado, 409 `charge-already-voided`. Clave del libro: `charges:void:<chargeId>` (permanente); el libro recibe una frase fija como motivo, no el texto libre.
- **Integridad nocturna** (tarea recurrente de db-scheduler, solo `confia-worker`, 02:30 `America/Tegucigalpa`, por institución):
  (a) toda transacción del libro cuadra; (b) todo cargo tiene exactamente un asiento `CHARGE` por su total, todo cargo anulado
  exactamente un `REVERSAL` espejo, y no hay asientos de `charges` sin cargo ni reversos de cargos no anulados; (c) la suma de los
  cargos no anulados es el movimiento neto de las cuentas por cobrar de los asientos de `charges`, por moneda. Una discrepancia se
  confirma en una segunda lectura antes de registrarse (evita falsas alarmas por una operación concurrente). Cada corrida deja una
  fila inmutable (`OK` o `DISCREPANCIES`) con sus discrepancias, una entrada de auditoría `charges.integrity_run.completed` y, si hay
  discrepancias, un evento `ERROR` y la ocurrencia queda como fallida y visible. Nunca repara nada.

## Endpoints (`/api/v1/charges`)

| Ruta | Permiso | Resultado |
|---|---|---|
| `POST /{chargeId}/void` con `Idempotency-Key`, cuerpo `{reason}` | `charges:void` | 200 con la anulación |
| `GET /students/{studentId}/charges` (`status`, `periodFrom`, `periodTo`, `sort`, `page`, `size`) | `charges:read` | Página de cargos con estado derivado `ACTIVE` o `VOIDED`; importes `{amount, currency}` |
| `GET /integrity-runs`, `GET /integrity-runs/latest`, `GET /integrity-runs/{runId}` | `charges:read` | Corridas (conteos) y detalle con discrepancias |

Códigos nuevos: `charge-already-voided` (409) y `charge-not-voidable` (409, el libro rechaza el reverso). Otra institución y un
identificador desconocido responden igual: 404 `resource-not-found`.

## Decisiones del propietario

- **2026-11-18: la anulación de un cargo no exige segundo aprobador** (excepción documentada en `docs/03-seguridad.md` §5.3). Se
  compensa con motivo obligatorio, bitácora, reverso único y trabajo nocturno de integridad; revisar si se introduce cobranza con
  montos altos.
- El texto libre del motivo vive solo en `charges_charge_void`; el libro y su bitácora reciben una frase fija.

## Casos límite

Doble anulación (misma y distinta clave), anulación simultánea desde dos peticiones, anular y volver a devengar el período, motivo
ausente o corto, falta de `Idempotency-Key`, cargo de otra institución, cuenta del estudiante en revisión, reverso espejo cuadrado
por moneda, lista con filtros y orden fuera del conjunto permitido, integridad sobre datos limpios (`OK`), con un asiento huérfano
inyectado (`DISCREPANCIES`), con reverso sin anulación, y doble ejecución del trabajo.

## Criterio de aceptación

Con los 30 cargos del corte 3a en una base real: anular uno deja 30 cargos, 1 anulación, 1 `REVERSAL` espejo y un saldo del estudiante
en cero; anularlo de nuevo responde 409 y no escribe; volver a ejecutar el devengo de ese período no crea nada; la base rechaza editar
o borrar la anulación; la corrida nocturna sobre esos datos termina `OK`, y con un asiento de `charges` sin cargo termina
`DISCREPANCIES` con la discrepancia nombrada; otra institución no ve nada.

## Pendiente con el contador y fiscal

Si el reverso de un cargo gravado debe acompañarse de una nota de crédito fiscal cuando ya existe factura (F5) y la fecha efectiva del
reverso en un período contable cerrado. Hoy el reverso se fecha el día de la anulación (o el inicio del período si fuera posterior).

## Construido (backend y contrato, corte 3b)

- **Migración `V33__create_charges_void_and_integrity.sql`:** `charges_charge_void` (solo inserción; único por cargo, por
  transacción de reverso y por clave; motivo de 20 a 500 caracteres; disparador `BEFORE INSERT` que exige un `REVERSAL` de
  `charges` que invierte el asiento del cargo, creado por esta misma transacción de base de datos, con un crédito a la subcuenta
  del estudiante igual al total), `charges_integrity_run` y `charges_integrity_discrepancy` (solo inserción, estado `OK` si y solo
  si no hay discrepancias), `UPDATE`/`DELETE`/`TRUNCATE` rechazados también al dueño, RLS forzada, permisos mínimos, y el índice
  `ledger_transaction_source_idx` que usa la lectura nocturna. El cargo no cambió: no tiene columna de estado.
- **Libro:** `LedgerPostingApi.reverseCharge` (espejo exacto dentro de la transacción del llamador; las negativas del libro llegan como
  `ReversalRefusedException` con motivo cerrado, sin que el dominio del libro cruce el módulo) y la interfaz de solo lectura
  `LedgerIntegrityApi` (transacciones de un módulo de origen, transacciones descuadradas y movimiento de las cuentas por cobrar).
- **Dominio puro (`charges.domain`):** `VoidReason`, `ChargeVoid`, `ChargeStatus`, `IntegrityCheck` (función pura de lo leído; toda la
  aritmética es de `Money`), `IntegrityFinding`/`IntegrityKind`/`IntegrityRun`, y los errores `charge-already-voided` y
  `charge-not-voidable` (409).
- **Aplicación:** `VoidCharge` (lee el cargo; bloquea la subcuenta `FOR UPDATE`; relee clave y cargo bajo el bloqueo; reversa; inserta la
  anulación; audita `charges.charge.voided` sin el texto del motivo), `ListStudentCharges` (audita `charges.student_charges.viewed`),
  `ViewIntegrityRuns` y `RunChargesIntegrityCheck`.
- **Trabajo nocturno:** tarea recurrente `charges.integrity-check`, diaria 02:30 `America/Tegucigalpa`, por institución, registrada solo en
  `ChargesWorkerConfiguration`; lee en transacciones de solo lectura, repite la lectura si hubo hallazgos y registra solo los que ambas
  confirman (el aislamiento `SERIALIZABLE` está reservado por ADR-0010), escribe la corrida y `charges.integrity_run.completed` en una
  transacción aparte y, si hay discrepancias, emite el evento `charges_integrity_discrepancies_found` y lanza para que la ocurrencia
  quede fallida y visible. No repara nada.
- **API:** las cinco rutas de la tabla; `charges:void` y `charges:read` ya existían (sin cambio de matriz ni de `docs/03-seguridad.md`).
  La clave no usa `@IdempotentWrite` por la misma razón que el devengo: se guarda permanente en la anulación.
- **Pruebas:** `IntegrityCheckTest`, `ChargeVoidRulesTest`, `VoidChargeTest`, `RunChargesIntegrityCheckTest` (sin base de datos);
  `ChargeVoidIT` (espejo y saldo, repetición, doble anulación con la misma y con otra clave, dos anulaciones simultáneas, anular y volver
  a devengar, cuenta en revisión, otra institución, lista, rechazos de la base), `ChargesIntegrityIT` (datos limpios, asiento huérfano
  inyectado, reverso sin anulación, doble ejecución, aislamiento, resultados inmutables) y `ChargeVoidEndpointsIT` (las cinco rutas en el
  proceso administrativo real, roles, aislamiento y validación).
- **Límites conocidos:** la lectura nocturna trae a memoria los cargos y asientos de `charges` de la institución (adecuado para
  decenas de miles al año); si crece, pasa a lectura por lotes con cursor. Falta la pantalla (corte 3b-2) y una fila de la tarea en la
  tabla de `docs/07-... §11`.
- **Pruebas guardianas actualizadas** (todas por la suma intencional de superficie, nada relajado): `PermissionedEndpointsTest`,
  `PublicRouteAllowListTest`, `CurrentSessionEndpointIT` y `OpenApiContractSnapshotTest` (cinco rutas, 80 a 85 operaciones),
  `CurrentAbsencesTest` y `RecurringJobCatalogTest` (la tarea `charges.integrity-check`), `QueueObservationOnWorkerIT` (nueve tareas
  recurrentes), `IdempotencyScopeExclusionInventoryTest` (`ChargeController` nombra la cabecera como `AccrualController`), `ProblemCodeTest`.
  `ProcessBeanPolicy` no cambió: el trabajador ya admite la aplicación e infraestructura de `charges` y `ledger`.

## Construido (pantallas, corte 3b-2)

- **Cargos del estudiante** (`/charges/student-charges`, entrada «Cargos del estudiante» en Finanzas): lista paginada, ordenada (período, vencimiento o
  total) y filtrada (estado y período) **en el servidor**, con estudiante, filtros, orden y página en la URL. Importes `{amount, currency}` tal como los
  entrega el servidor, a la derecha y con numeración tabular; insignia «Vigente»/«Anulado» (texto y punto) y, en los anulados, la fecha de anulación (el texto
  libre del motivo nunca se muestra en la lista). Se llega desde el estado de cuenta («Ver cargos del estudiante») y desde el registro de estudiantes
  («Cargos»).
- **Anular cargo:** diálogo compacto con el resumen del cargo, aviso de que la anulación no se puede deshacer y deja un reverso exacto en el libro mayor,
  motivo obligatorio de 20 a 500 caracteres con contador en vivo (por puntos de código, como el servidor, sin los espacios de los bordes y sin saltos de línea
  ni caracteres de control), y advertencia de no escribir datos personales. Botón destructivo, un intento a la vez, sin actualización optimista. La
  `Idempotency-Key` se genera una vez por intento y se reutiliza solo al reintentar lo mismo (red caída o respuesta ilegible); si cambia el motivo, o el
  servidor responde 422 `idempotency-payload-mismatch`, el siguiente intento lleva una clave nueva. Errores 409 `charge-already-voided` y
  `charge-not-voidable`, 422, 403 y 404 se dicen dentro del diálogo, con el identificador de soporte. Al confirmar se invalidan las listas de cargos del
  estudiante y todos los estados de cuenta (el saldo y los indicadores del tablero aún no tienen consulta propia en el panel).
- **Integridad de cargos** (`/charges/integrity` y `/charges/integrity/:runId`, entrada «Integridad de cargos» en Finanzas): lista de corridas nocturnas
  (resultado, inicio, fin, conteos) paginada por el servidor, con un aviso de la última corrida (sin discrepancias, con N discrepancias, aún no ejecutada o
  no se pudo leer) y el detalle con cada discrepancia (tipo en palabras, cargo, asiento, importe según los cargos e importe según el libro mayor).
- **Estados y accesibilidad:** carga (esqueleto), vacío (con acción), error (con identificador de soporte y reintento) y éxito en cada vista; contenedor y
  presentación separados; catálogo `es-HN` sin textos embebidos.
- **Simulador (`e2e/mock-api`):** `charge-void-model.ts` con cargos sembrados (estudiante 1: 3 cargos; estudiante 5: 30 cargos para paginar), anulación con
  `Idempotency-Key` y repetición, y corridas de integridad; escenarios `charge-void-empty`, `charge-void-already-voided`, `charge-void-not-voidable`,
  `charge-void-forbidden`, `charge-void-read-forbidden`, `charge-void-server-error` e `integrity-discrepancies`.
- **Pruebas:** componentes y contenedores con MSW (lista, filtros, orden, anulación, errores, reintento con la misma clave, integridad), modelo del
  simulador, operaciones del cliente y un recorrido de Playwright (`charges-void.e2e.ts`: del estado de cuenta a la anulación).
- **Límites conocidos:** (1) la sesión del panel no trae permisos, así que «Anular cargo» se ofrece en todo cargo vigente y el 403 del servidor se
  explica en el diálogo (igual que el mapa de cuentas); ocultarlo por `charges:void` requiere un endpoint o un campo de permisos en la sesión.
  (2) El detalle de una corrida muestra todas sus discrepancias de una vez porque el endpoint no pagina. (3) El nombre del estudiante en «Cargos del
  estudiante» solo se conoce si se eligió en esa pantalla; al llegar por un enlace se muestra «Estudiante elegido» (los cargos no traen el nombre).
  (4) Sin entradas de la matriz de accesibilidad de Playwright para las rutas nuevas (el estado de cuenta tampoco las tiene).

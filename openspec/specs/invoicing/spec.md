# Capacidad: Facturación

- **Identificador:** invoicing
- **Estado:** Borrador
- **Fase:** F5

## Propósito

Emitir los documentos fiscales del régimen hondureño (SAR/CAI) que respaldan cada cobro, con
correlativos irrepetibles y control del rango autorizado. Le corresponde: la emisión de facturas y
notas de crédito, la asignación de correlativos, la validación de vigencia del rango CAI, la
anulación de documentos y las alertas de consumo y vencimiento del rango. NO le corresponde:
decidir si un pago está confirmado (capacidad `payments`) ni calcular el saldo del estudiante
(capacidad `ledger`); esta capacidad emite el documento fiscal a partir de una transacción ya
registrada en esas otras capacidades.

## Requisitos

### Requisito: Correlativo obtenido bajo bloqueo y nunca reutilizado

El sistema DEBE asignar el correlativo de cada documento fiscal mediante una secuencia protegida
por bloqueo, y NO DEBE asignar jamás el mismo correlativo a dos documentos, incluso ante
solicitudes de emisión simultáneas.

#### Escenario: Emisión secuencial de correlativos

- **DADO** que el último correlativo emitido dentro del rango CAI `AB12CD-34EF56-GH7890-IJ1234`
  fue `000-001-01-00000042`
- **CUANDO** se emite una factura nueva
- **ENTONCES** el sistema le asigna el correlativo `000-001-01-00000043`
- **Y** ese correlativo queda marcado como usado antes de que la respuesta se devuelva al cliente

#### Escenario: Dos emisiones simultáneas no producen el mismo correlativo

- **DADO** el mismo rango CAI con el correlativo `000-001-01-00000043` como el último asignado
- **CUANDO** dos solicitudes de emisión llegan en el mismo instante desde dos cajeros distintos
- **ENTONCES** el sistema asigna correlativos distintos y consecutivos a cada una, por ejemplo
  `000-001-01-00000044` y `000-001-01-00000045`
- **Y** en ningún caso ambas solicitudes reciben `000-001-01-00000044`

### Requisito: Validación de vigencia y disponibilidad del rango CAI antes de emitir

El sistema DEBE verificar, antes de emitir cualquier documento fiscal, que el rango CAI tenga
correlativos disponibles y que la fecha de emisión esté dentro de su fecha límite autorizada.

#### Escenario: Emisión dentro de rango vigente

- **DADO** un rango CAI autorizado del `000-001-01-00000001` al `000-001-01-00001000`, con fecha
  límite de emisión 2026-12-31, y el correlativo `000-001-01-00000500` como el siguiente
  disponible
- **CUANDO** se emite una factura el 2026-06-10
- **ENTONCES** el sistema valida que la fecha 2026-06-10 es anterior a la fecha límite y que
  quedan correlativos disponibles
- **Y** procede con la emisión asignando `000-001-01-00000500`

#### Escenario: Validación previa detiene una emisión con rango casi agotado

- **DADO** el mismo rango, con el correlativo `000-001-01-00001000` como el siguiente disponible,
  el último del rango autorizado
- **CUANDO** se solicita emitir esa factura
- **ENTONCES** el sistema valida que ese correlativo sigue dentro del rango autorizado y permite
  la emisión
- **Y** marca el rango como agotado inmediatamente después, de modo que la siguiente solicitud sea
  rechazada por el requisito de rango agotado

### Requisito: Prohibición de emitir con rango agotado o fecha límite vencida

El sistema NO DEBE emitir un documento fiscal cuando el rango CAI correspondiente no tiene
correlativos disponibles, o cuando la fecha de emisión es posterior a la fecha límite autorizada,
aunque existan correlativos sin usar.

#### Escenario: Intento de emisión con rango agotado

- **DADO** un rango CAI cuyo último correlativo `000-001-01-00001000` ya fue asignado
- **CUANDO** se intenta emitir una factura nueva bajo ese rango
- **ENTONCES** el sistema rechaza la emisión
- **Y** el error indica que el rango CAI está agotado y requiere autorización de uno nuevo

#### Escenario: Intento de emisión con fecha límite vencida

- **DADO** un rango CAI con fecha límite de emisión 2026-05-31 y correlativos disponibles hasta
  `000-001-01-00000800`
- **CUANDO** se intenta emitir una factura el 2026-06-01
- **ENTONCES** el sistema rechaza la emisión aunque existan correlativos disponibles
- **Y** el error indica que la fecha límite del rango CAI venció el 2026-05-31

### Requisito: Inmutabilidad del documento emitido

El sistema NO DEBE permitir la edición ni el borrado de un documento fiscal una vez emitido, sin
importar el rol de quien lo solicite.

#### Escenario: Intento de editar una factura ya emitida

- **DADO** la factura `000-001-01-00000500` emitida el 2026-06-10 por 3,200.00 HNL
- **CUANDO** un usuario con rol `administrator` intenta cambiar el monto a 3,000.00 HNL
- **ENTONCES** el sistema rechaza la edición
- **Y** la factura conserva su monto original de 3,200.00 HNL

#### Escenario: Intento de borrar una factura ya emitida

- **DADO** la misma factura `000-001-01-00000500`
- **CUANDO** cualquier usuario intenta eliminarla del sistema
- **ENTONCES** el sistema rechaza la operación
- **Y** la factura permanece consultable con su correlativo original

### Requisito: Anulación con motivo obligatorio y registro

El sistema DEBE exigir un motivo registrado para anular un documento fiscal, y DEBE dejar
constancia permanente de la anulación asociada al correlativo original, sin liberar ese
correlativo para reuso.

#### Escenario: Anulación con motivo registrado

- **DADO** la factura `000-001-01-00000500` de 3,200.00 HNL, emitida por error a nombre de un
  estudiante equivocado
- **CUANDO** el personal autorizado la anula con el motivo "emitida a nombre de estudiante
  incorrecto, corresponde a EST-00458"
- **ENTONCES** el sistema marca la factura como "anulada" con el motivo registrado y la fecha de
  anulación
- **Y** el correlativo `000-001-01-00000500` queda marcado como anulado, sin volver a asignarse a
  ningún otro documento

#### Escenario: Anulación sin motivo rechazada

- **DADO** la misma factura `000-001-01-00000500`
- **CUANDO** se intenta anularla sin proporcionar ningún motivo
- **ENTONCES** el sistema rechaza la anulación
- **Y** la factura permanece en estado "emitida"

### Requisito: Nota de crédito con referencia obligatoria a la factura original

El sistema DEBE exigir, en toda emisión de nota de crédito, una referencia a la factura original
que corrige, y NO DEBE emitir una nota de crédito sin esa referencia ni por un monto mayor al de
la factura referenciada.

#### Escenario: Nota de crédito por cobro parcial en exceso

- **DADO** la factura `000-001-01-00000612` de 2,800.00 HNL emitida el 2026-07-01, donde se
  detectó un cobro en exceso de 300.00 HNL
- **CUANDO** se emite una nota de crédito de 300.00 HNL referenciando `000-001-01-00000612`
- **ENTONCES** el sistema emite la nota de crédito con su propio correlativo y la referencia
  obligatoria a `000-001-01-00000612`
- **Y** el saldo del estudiante se reduce en 300.00 HNL en el libro mayor

#### Escenario: Nota de crédito sin referencia rechazada

- **DADO** una solicitud de nota de crédito de 500.00 HNL sin ninguna factura referenciada
- **CUANDO** el sistema procesa la solicitud
- **ENTONCES** la rechaza
- **Y** el error indica que toda nota de crédito requiere la referencia a un documento fiscal
  original

### Requisito: Alertas por consumo de rango y por proximidad de la fecha límite

El sistema DEBE generar una alerta accionable cuando el porcentaje de correlativos consumidos de
un rango CAI supere el ochenta por ciento, y otra alerta cuando falten treinta días o menos para
su fecha límite de emisión.

#### Escenario: Alerta por porcentaje de rango consumido

- **DADO** un rango CAI de 1,000 correlativos, del cual ya se emitieron 812 documentos
- **CUANDO** se emite el documento número 813, que representa el 81.3% del rango
- **ENTONCES** el sistema genera una alerta indicando que el rango superó el ochenta por ciento de
  consumo

#### Escenario: Alerta por proximidad de la fecha límite

- **DADO** un rango CAI con fecha límite de emisión 2026-08-15
- **CUANDO** el trabajo diario de verificación corre el 2026-07-20, con quince días restantes
  hasta esa fecha límite
- **ENTONCES** el sistema genera una alerta de proximidad de vencimiento del rango CAI
- **Y** la alerta indica el número exacto de días restantes

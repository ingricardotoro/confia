# Delta para Bitácora de auditoría

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `backup-restore-and-drill` (F0, cambio 12)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-09

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `AT39` en adelante. La especificación vigente llega a `AT31` y el delta en curso
  `rbac-permission-matrix-and-audit-integration` reserva `AT32` a `AT38`.
- Este delta solo añade un requisito: la cadena de auditoría se verifica sobre una base restaurada. El contrato del
  verificador (`Contrato observable del verificador de cadena`) no se modifica; se reutiliza tal cual.
- Etiquetas: `[LOCAL]` se comprueba en local y en integración continua con Testcontainers.

## ADDED Requirements

### Requisito: La cadena de auditoría de cada institución se verifica íntegra sobre una base restaurada

La verificación de una restauración DEBE ejecutar el verificador de cadena existente sobre la base restaurada para
cada institución que tenga asientos, desde el registro génesis hasta la última fila, y la restauración NO DEBE
darse por verificada si alguna cadena diverge. La cadena restaurada DEBE ser idéntica a la de origen: el volcado y
la restauración NO DEBEN alterar ningún campo que entre en la preimagen del hash. El verificador NO DEBE reparar
nada ni reescribir ninguna fila al ejecutarse sobre la base restaurada.

#### Escenario: [AT39] [LOCAL] Una base restaurada conserva todas las cadenas íntegras

- **DADO** una base de origen con asientos de al menos dos instituciones y cadenas íntegras, respaldada y
  restaurada en una base limpia
- **CUANDO** se invoca el verificador sobre cada institución de la base restaurada, desde el génesis
- **ENTONCES** reporta integridad para todas y el número de filas verificadas por institución es igual al de origen

#### Escenario: [AT40] [LOCAL] Una fila alterada antes del respaldo se detecta tras la restauración

- **DADO** una base de origen en la que una fila de una institución fue alterada con acceso de superusuario sin
  recalcular la cadena, y que se respaldó y restauró
- **CUANDO** se invoca el verificador sobre la base restaurada
- **ENTONCES** identifica exactamente esa fila por su `institution_id` y su `id`, y la verificación de la
  restauración termina con código distinto de `0`

#### Escenario: [AT41] [LOCAL] El verificador no modifica la base restaurada

- **DADO** una base restaurada y el recuento de `shared_audit_log` y su último `row_hash` antes de verificar
- **CUANDO** se ejecuta la verificación completa
- **ENTONCES** el conteo y los hashes de `shared_audit_log` son los mismos después, y no se escribió ningún
  asiento de reparación

#### Escenario: [AT42] [LOCAL] Una institución sin asientos no falla la verificación

- **DADO** una base restaurada con una institución sin ningún asiento de auditoría
- **CUANDO** se ejecuta la verificación de la restauración
- **ENTONCES** esa institución se informa sin filas y no hace fallar la comprobación, mientras que una cadena
  divergente de otra institución sí la hace fallar

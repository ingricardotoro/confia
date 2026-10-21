# Delta para Bitácora de auditoría encadenada

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `session-endpoints-cookie-and-csrf` (F0, cambio 7, parte 4b, segundo cambio S2 de tres)
- **Propuesta:** `proposal.md`, DA-13 *a* confirmada por el propietario el 2026-10-20 (seguimiento F1c)

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `AT01` en adelante; la especificación vigente no numera los suyos.
- El requisito «Reproducibilidad de la serialización canónica entre PL/pgSQL y Java» NO cambia: se refiere al
  cálculo de `row_hash` sobre la fila, y este delta se refiere al JSON de detalle que escribe la aplicación.
  Los bytes del JSON de detalle forman parte de la preimagen, así que su igualdad protege la cadena.

## ADDED Requirements

### Requisito: El JSON de detalle de un asiento se construye con un constructor tipado común y es igual byte a byte al anterior

El sistema DEBE proveer en `com.confia.shared.audit` un constructor tipado del JSON de detalle de un asiento de
auditoría, que escape correctamente todo valor, emita las claves en un orden determinista y no permita
construir el documento por concatenación. Todo sitio de producción que escribe un asiento con detalle (al menos
dieciséis sitios en ocho clases de `identity`) DEBE usarlo. La migración de los sitios existentes NO DEBE cambiar
los bytes del detalle que cada uno escribía: para las mismas entradas, el JSON escrito DEBE ser idéntico byte a
byte, de modo que `row_hash` de los asientos nuevos sea el que habría resultado antes y la verificación de la
cadena no cambie. Los asientos nuevos de este cambio DEBEN usar el constructor desde su primer uso.

#### Escenario: [AT01] Igualdad byte a byte en los sitios migrados

- **DADO** cada sitio migrado y las entradas con las que sus pruebas existentes lo ejercitan
- **CUANDO** se compara el detalle escrito por la versión anterior (capturado como valor esperado) con el escrito
  por el constructor
- **ENTONCES** los bytes son idénticos en todos los sitios

#### Escenario: [AT02] Un valor con caracteres especiales se escapa

- **DADO** un valor de detalle con comillas, barra invertida, salto de línea y caracteres no ASCII
- **CUANDO** el constructor lo serializa
- **ENTONCES** el resultado es JSON válido que, al leerse, devuelve el valor original

#### Escenario: [AT03] El orden de claves es determinista

- **DADO** el mismo conjunto de claves añadido en dos órdenes distintos
- **CUANDO** el constructor serializa ambos
- **ENTONCES** producen los mismos bytes

#### Escenario: [AT04] La cadena verifica tras la migración

- **DADO** una cadena con asientos escritos con el constructor
- **CUANDO** el verificador de cadena la recorre
- **ENTONCES** no reporta divergencia y la prueba de propiedades de la serialización canónica sigue pasando

#### Escenario: [AT05] Un valor que no es un tipo admitido se rechaza

- **DADO** un intento de añadir un objeto arbitrario al constructor
- **CUANDO** se compila o se ejecuta
- **ENTONCES** no se admite: solo cadenas, enteros, booleanos, nulos e instantes en una forma fija

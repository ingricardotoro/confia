# Capacidad: Integridad de la construcción

- **Identificador:** build-integrity
- **Estado:** Borrador
- **Fase:** F0

## Propósito

Capacidad técnica: garantiza que las reglas de dependencia, capas y seguridad ya decididas en
los ADR de CONFIA rompan `./mvnw verify` cuando se violan, en local y en integración continua.
No define reglas de negocio nuevas; hace verificable lo ya decidido.

## Requisitos

### Requisito: Frontera de dominio entre módulos de negocio

El sistema DEBE romper la construcción si una clase de un módulo de negocio importa el `domain`
de otro módulo de negocio.

#### Escenario: Importación cruzada de dominio

- **DADO** dos módulos de negocio existentes
- **CUANDO** el módulo A importa una clase de `domain` del módulo B
- **ENTONCES** `./mvnw verify` falla por la importación prohibida

#### Escenario: Cada módulo usa solo su propio dominio

- **DADO** los mismos dos módulos
- **CUANDO** ninguno importa el `domain` del otro
- **ENTONCES** `./mvnw verify` termina en verde

### Requisito: Pureza del módulo `kernel`

El módulo `kernel` NO DEBE depender de nada fuera del JDK; el sistema DEBE romper la
construcción si lo hace.

#### Escenario: `kernel` importa Spring

- **DADO** `kernel` sin dependencias externas al JDK
- **CUANDO** una clase de `kernel` importa un tipo de Spring
- **ENTONCES** la construcción falla antes de compilar `kernel`

#### Escenario: `kernel` solo usa el JDK

- **DADO** el mismo módulo
- **CUANDO** su código usa únicamente tipos del JDK
- **ENTONCES** `./mvnw verify` compila y valida `kernel` sin error

### Requisito: Dependencias de persistencia y de tareas prohibidas

El sistema NO DEBE permitir en `apps/api` a Hibernate, Jakarta Persistence, Spring Data JPA,
Spring Data JDBC, Quartz, JobRunr ni otra biblioteca de programación de tareas (ADR-0015,
ADR-0016). El controlador JDBC de PostgreSQL y el JDBC del JDK no están prohibidos: jOOQ y el
componente transaccional se apoyan en ellos.

#### Escenario: Se agrega Hibernate

- **DADO** el POM padre sin dependencias prohibidas
- **CUANDO** un módulo agrega Hibernate
- **ENTONCES** `./mvnw verify` falla señalando la dependencia prohibida

#### Escenario: Sin dependencias prohibidas

- **DADO** el mismo POM padre
- **CUANDO** ningún módulo declara una dependencia de la lista prohibida
- **ENTONCES** la construcción no falla por esta causa

### Requisito: Paquetes con nombre de capa técnica prohibidos

El sistema NO DEBE permitir un paquete `interface`, `interfaces`, `controllers`, `services`,
`repositories`, `entities`, `utils`, `helpers` o `common` en un módulo de negocio.

#### Escenario: Paquete `services`

- **DADO** un módulo sin paquetes de nombre prohibido
- **CUANDO** se agrega un paquete `services`
- **ENTONCES** `./mvnw verify` falla por el nombre prohibido

#### Escenario: Paquetes por capacidad de negocio

- **DADO** el mismo módulo
- **CUANDO** sus paquetes nombran capacidades de negocio, no capas técnicas
- **ENTONCES** la construcción no falla por esta causa

### Requisito: Reglas de capas y ausencia de ciclos

El sistema DEBE exigir `web` → `application` → `domain`, `infrastructure` implementando
puertos de `application`, y NO DEBE permitir ciclos de dependencia (ADR-0002).

#### Escenario: `domain` importa `infrastructure`

- **DADO** un módulo con las capas separadas
- **CUANDO** `domain` importa una clase de `infrastructure`
- **ENTONCES** la construcción falla por invertir el sentido permitido

#### Escenario: Ciclo entre dos paquetes

- **DADO** el paquete A sin dependencia hacia B
- **CUANDO** A pasa a depender de B y B ya depende de A
- **ENTONCES** la construcción falla por ciclo de dependencia

### Requisito: Integración continua verifica cada empuje

El sistema DEBE ejecutar `./mvnw verify` en integración continua ante cada empuje a la rama del
cambio, con resultado visible en el remoto.

#### Escenario: Empuje con violación

- **DADO** un empuje que introduce una violación de arquitectura
- **CUANDO** la integración continua verifica ese empuje
- **ENTONCES** el resultado en rojo queda visible en el remoto

#### Escenario: Empuje sin violaciones

- **DADO** un empuje sin violaciones
- **CUANDO** la integración continua verifica ese empuje
- **ENTONCES** el resultado en verde queda visible en el remoto

### Requisito: Vulnerabilidad alta o crítica rompe la construcción

El sistema DEBE escanear vulnerabilidades de dependencias en cada verificación y DEBE romper la
construcción ante severidad alta o crítica (ADR-0008, ADR-0013).

#### Escenario: Vulnerabilidad crítica

- **DADO** una dependencia con vulnerabilidad pública crítica
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando esa dependencia

#### Escenario: Solo severidad baja

- **DADO** dependencias con vulnerabilidades solo de severidad baja o media
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** el escaneo no rompe la construcción

### Requisito: Ninguna regla se desactiva sin un ADR

El sistema NO DEBE permitir que una regla de esta capacidad quede deshabilitada, suprimida o
excluida sin una referencia explícita a un ADR que lo autorice.

#### Escenario: Exclusión sin justificación

- **DADO** una regla de esta capacidad activa
- **CUANDO** alguien la deshabilita sin citar un ADR
- **ENTONCES** esa exclusión es en sí misma una violación

#### Escenario: Excepción documentada por ADR

- **DADO** una excepción necesaria a una regla de capas
- **CUANDO** el código cita junto a ella el ADR que la autoriza
- **ENTONCES** no se considera una violación de esta capacidad

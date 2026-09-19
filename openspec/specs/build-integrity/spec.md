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

> **Escenario diferido.** El escenario «Cada módulo usa solo su propio dominio» (DADO dos módulos
> de negocio existentes, CUANDO ninguno importa el `domain` del otro, ENTONCES `./mvnw verify`
> termina en verde) no pertenece a este cambio: su premisa exige dos módulos de negocio y este
> cambio no crea ninguno. Se traslada a los cambios de F0 que introducen esos módulos:
> `institution-root-and-multitenancy-baseline` (cambio 4, primer módulo) y
> `staff-authentication-mfa-sessions` (cambio 7, segundo módulo, único que puede demostrarlo).
> Hallazgo W2 del informe de verificación de este cambio.

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

El sistema DEBE escanear las vulnerabilidades de sus dependencias en cada verificación de
integración continua y DEBE romper esa verificación ante severidad alta o crítica (ADR-0008,
ADR-0013). El escaneo vive en la integración continua y no en `./mvnw verify`, porque depende de
una base de datos de vulnerabilidades en línea y encarecería cada construcción local.

#### Escenario: Vulnerabilidad crítica

- **DADO** una dependencia con vulnerabilidad pública crítica
- **CUANDO** la integración continua verifica el empuje
- **ENTONCES** la verificación falla señalando esa dependencia y el resultado en rojo queda visible
  en el remoto

#### Escenario: Solo severidad baja

- **DADO** dependencias con vulnerabilidades solo de severidad baja o media
- **CUANDO** la integración continua verifica el empuje
- **ENTONCES** el escaneo no rompe la verificación

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

### Requisito: Cobertura mínima del módulo `kernel`

El sistema DEBE romper `./mvnw verify` si la cobertura de líneas o de ramas del módulo `kernel`,
medida con JaCoCo, cae por debajo de noventa y cinco por ciento.

#### Escenario: Cobertura por debajo del umbral

- **DADO** el módulo `kernel` con cobertura de líneas o de ramas por debajo de 95 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el módulo `kernel` y el porcentaje medido

#### Escenario: Cobertura en el umbral o por encima

- **DADO** el módulo `kernel` con cobertura de líneas y de ramas igual o superior a 95 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción no falla por esta causa

### Requisito: Puntuación de mutación del módulo `kernel` según la rama

El sistema DEBE ejecutar pruebas de mutación con PIT sobre el módulo `kernel` con umbral mínimo de
ochenta. En la rama principal, el perfil `mutation-gate` DEBE romper la construcción si la
puntuación de mutación cae por debajo del umbral. En cualquier otra rama, el perfil
`mutation-report` NO DEBE romper la construcción por una puntuación baja y SOLO DEBE informarla.

#### Escenario: Mutación baja en la rama principal

- **DADO** un empuje a la rama principal con el perfil `mutation-gate` activo
- **CUANDO** la puntuación de mutación de `kernel` resulta menor a 80
- **ENTONCES** `./mvnw verify` falla señalando la puntuación medida

#### Escenario: Mutación baja en una rama de trabajo

- **DADO** un empuje a una rama `change/**` con el perfil `mutation-report` activo
- **CUANDO** la puntuación de mutación de `kernel` resulta menor a 80
- **ENTONCES** `./mvnw verify` no falla por esta causa y el reporte de mutación queda visible en la
  integración continua

#### Escenario: Selección de perfil de mutación según la rama

- **DADO** la integración continua ejecutando un empuje
- **CUANDO** la rama que se verifica es la rama principal
- **ENTONCES** se activa `mutation-gate`; en cualquier otra rama se activa `mutation-report`, con el
  mismo mecanismo de activación condicional por rama que ya usa el perfil `no-snapshots-on-main`
  (propiedad `confia.ci.mainBranch`)

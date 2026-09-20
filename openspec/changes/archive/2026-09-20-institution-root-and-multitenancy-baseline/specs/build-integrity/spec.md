# Delta para Integridad de la construcción

## ADDED Requirements

### Requisito: Prohibición de coma flotante para importes y de igualdad cruda de `BigDecimal` fuera de `Money`

El sistema DEBE romper `./mvnw verify` con reglas de ArchUnit que se ejecutan sobre todo el código
de producción de `apps/api` (ADR-0004 §Cumplimiento 2) y que prohíben:

- construir un `BigDecimal` con `new BigDecimal(double)` o con `BigDecimal.valueOf(double)`;
- invocar `BigDecimal.equals` fuera del módulo `kernel`;
- declarar un campo, un parámetro o un valor de retorno `double` o `float` en un tipo monetario,
  entendiendo por tipo monetario `Money`, `Percentage` y toda clase de `apps/api` que declare un
  campo de alguno de esos dos tipos.

Cada una de las tres reglas DEBE tener su propio fixture de prueba permanente que la viole a
propósito, para demostrar que la regla realmente falla cuando corresponde, y no solo que pasa por
ausencia de código que la ejercite.

#### Escenario: Fixture que construye `BigDecimal` desde `double`

- **DADO** un fixture de prueba en `apps/api/app` que invoca `new BigDecimal(double)` o
  `BigDecimal.valueOf(double)`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase del fixture y la regla incumplida

#### Escenario: Fixture que invoca `BigDecimal.equals` fuera de `Money`

- **DADO** un fixture de prueba fuera del módulo `kernel` que invoca `BigDecimal.equals` sobre un
  importe
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase del fixture y la regla incumplida

#### Escenario: Fixture con un campo `double` en un tipo monetario

- **DADO** un fixture de prueba que declara un campo `double` en una clase con un campo `Money` o
  `Percentage`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase del fixture y la regla incumplida

#### Escenario: Código de producción sin infracciones

- **DADO** el código de producción real de `apps/api`, sin ninguno de los tres patrones prohibidos
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** las tres reglas pasan sin excepción de conjunto vacío, porque seleccionan código de
  producción real y no un conjunto vacío

### Requisito: Cobertura global mínima del módulo `app`

El sistema DEBE romper `./mvnw verify` si la cobertura de líneas o de ramas del módulo `app`,
medida con JaCoCo sobre la totalidad de su código de producción, cae por debajo de ochenta por
ciento.

#### Escenario: Cobertura del módulo `app` por debajo del umbral

- **DADO** el módulo `app` con cobertura de líneas o de ramas por debajo de 80 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el módulo `app` y el porcentaje medido

#### Escenario: Cobertura del módulo `app` en el umbral o por encima

- **DADO** el módulo `app` con cobertura de líneas y de ramas igual o superior a 80 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción no falla por esta causa

### Requisito: Cobertura y mutación del paquete `domain` de cada módulo de negocio

El sistema DEBE romper `./mvnw verify` si la cobertura de líneas o de ramas del paquete `domain`
de cualquier módulo de negocio de `apps/api/app`, medida con JaCoCo, cae por debajo de noventa y
cinco por ciento. El sistema DEBE ejecutar pruebas de mutación con PIT sobre ese mismo paquete con
umbral mínimo de ochenta, con la misma selección de perfil por rama que ya rige para `kernel`: en
la rama principal, el perfil `mutation-gate` DEBE romper la construcción si la puntuación cae por
debajo del umbral; en cualquier otra rama, el perfil `mutation-report` NO DEBE romper la
construcción por una puntuación baja y SOLO DEBE informarla.

#### Escenario: Cobertura de `organization.domain` por debajo del umbral

- **DADO** el paquete `com.confia.organization.domain` con cobertura de líneas o de ramas por
  debajo de 95 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el paquete y el porcentaje medido

#### Escenario: Cobertura de `organization.domain` en el umbral o por encima

- **DADO** el paquete `com.confia.organization.domain` con cobertura de líneas y de ramas igual o
  superior a 95 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción no falla por esta causa

#### Escenario: Mutación baja de `organization.domain` en la rama principal

- **DADO** un empuje a la rama principal con el perfil `mutation-gate` activo
- **CUANDO** la puntuación de mutación de `com.confia.organization.domain` resulta menor a 80
- **ENTONCES** `./mvnw verify` falla señalando la puntuación medida

#### Escenario: Mutación baja de `organization.domain` en una rama de trabajo

- **DADO** un empuje a una rama `change/**` con el perfil `mutation-report` activo
- **CUANDO** la puntuación de mutación de `com.confia.organization.domain` resulta menor a 80
- **ENTONCES** `./mvnw verify` no falla por esta causa y el reporte de mutación queda visible en la
  integración continua

# Delta para build-integrity

Este cambio agrega dos requisitos a la capacidad `build-integrity`: la cobertura mínima del módulo
`kernel` y la puntuación de mutación mínima de `kernel`, con activación distinta según la rama. No
modifica ningún requisito existente de la capacidad. Las reglas de ArchUnit contra coma flotante y
contra `BigDecimal.equals` fuera de `Money` (ADR-0004 §Cumplimiento 2) quedan explícitamente
diferidas al cambio 4 (`institution-root-and-multitenancy-baseline`, decisión D3 de la propuesta) y
no forman parte de este delta.

## ADDED Requirements

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

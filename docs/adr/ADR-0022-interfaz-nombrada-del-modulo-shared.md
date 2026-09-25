# ADR-0022: Interfaz nombrada de Spring Modulith para los paquetes de `shared` que otros módulos consumen

- **Estado:** Aceptado
- **Fecha:** 2026-09-24
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Verificación de módulos de Spring Modulith (`SpringModulithVerificationTest`),
  paquetes `com.confia.shared.security` y `com.confia.shared.audit`, dependencias de producción de
  `apps/api/app/pom.xml`. Concreta ADR-0002 y ADR-0015 sin reemplazarlas.

## Contexto y problema

`SpringModulithVerificationTest` verifica el árbol de producción con
`ApplicationModules.of("com.confia", DO_NOT_INCLUDE_TESTS).verify()`. Su comportamiento confirmado
—por lectura y, en este cambio, por sonda ejecutada— es que **cada sub-paquete directo de la raíz
`com.confia` es un módulo propio**, y `verify()` lanza `Violations` si un módulo accede a un paquete
interno no expuesto de otro módulo. `com.confia.shared.security` y `com.confia.shared.audit` son
paquetes anidados del módulo `shared`, no su paquete base.

Hasta `identity-module-and-password-authentication` (F0 cambio 7), ninguna clase de producción
cruzaba de un módulo de negocio a otro: las únicas dependencias entre paquetes de producción eran
internas a `shared` o hacia `com.confia.kernel`, y esas funcionan porque los tipos del núcleo viven
en el **paquete base** de su módulo, que es API por omisión en Spring Modulith. Este cambio es el
primero en que eso deja de bastar: `identity.application` necesita
`com.confia.shared.security.TransactionRunner` (para abrir su única transacción) y
`com.confia.shared.audit.AuditLogWriter` (para escribir en la bitácora), y los dos viven en paquetes
anidados de `shared`.

Es la pregunta abierta 2 que `audit-log-and-transaction-runner` dejó escrita: «interfaz nombrada de
Spring Modulith para `com.confia.shared.security` … si resultara caro o ambiguo, se difiere al
cambio 6». El cambio 6 (`idempotency-key-infrastructure`) no la necesitó porque consumió el
componente solo desde pruebas. Este cambio sí la necesita, en producción.

**Sonda S1, ejecutada.** Se creó temporalmente una clase de producción mínima en
`com.confia.identity.application` que importa `com.confia.shared.security.TransactionRunner`, sin
ninguna anotación de Spring Modulith declarada todavía, y se ejecutó
`SpringModulithVerificationTest`. Falló, exactamente como se predijo, con:

```
org.springframework.modulith.core.Violations: - Module 'identity' depends on non-exposed type
com.confia.shared.security.TransactionRunner within module 'shared'!
```

Confirma que la anotación es necesaria, no precautoria.

**Sonda S1b, ejecutada.** `org.springframework.modulith.NamedInterface` —la anotación— vive en el
artefacto **`spring-modulith-api`**, no en `spring-modulith-core`. Existe un segundo tipo con el
mismo nombre simple, `org.springframework.modulith.core.NamedInterface`, en `spring-modulith-core`:
es una clase del modelo (`implements Iterable<JavaClass>`), no una anotación, y `spring-modulith-core`
arrastra ArchUnit, una biblioteca solo de pruebas. Consecuencia directa para este ADR: lo que se
declara en alcance de compilación es `spring-modulith-api`, nunca `spring-modulith-core`.

Si no se decide: cada módulo de negocio futuro (`payments`, `billing`) tropieza con la misma
violación de Spring Modulith la primera vez que consuma algo de `shared`, sin una decisión escrita
contra la que compararse.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Superficie expuesta mínima | Muy alto | `shared` no debe quedar abierto entero solo para resolver un caso puntual |
| Ninguna biblioteca de pruebas en el camino de clases de producción | Muy alto | `spring-modulith-core` arrastra ArchUnit; promoverlo violaría esa frontera sin necesidad |
| Decisión escrita, no descubierta por el siguiente módulo que tropiece | Alto | `payments` y `billing` van a necesitar lo mismo |
| Convergencia del enforcer (`dependencyConvergence`) | Alto | Cualquier dependencia de producción nueva debe pasar la sonda S6 sin excepción silenciosa |

## Opciones consideradas

### Opción A: `@NamedInterface` sobre `shared.security` y `shared.audit`

Anotar el `package-info.java` de cada uno de los dos paquetes que `identity` consume con
`@org.springframework.modulith.NamedInterface`, declarada por el propio paquete que se expone.

**Ventajas.** Expone exactamente los dos paquetes que un consumidor real necesita hoy, no el módulo
entero. Dependencia de producción nueva mínima (`spring-modulith-api`, sin ArchUnit). Dice en el
árbol, de forma explícita, qué parte de `shared` es contrato público.

**Desventajas.** Cuesta una dependencia de producción nueva (antes, las anotaciones de Spring
Modulith eran de alcance `test`).

### Opción B: `@ApplicationModule(type = OPEN)` sobre `com.confia.shared`

**Ventajas.** Misma dependencia nueva; una sola anotación en el paquete base de `shared` en vez de
dos.

**Desventajas.** Expone el módulo `shared` entero, incluido `shared.infrastructure`, que ningún
consumidor necesita hoy. Es la opción más ancha posible, y la anchura no tiene marcha atrás barata:
una vez abierto, cualquier módulo puede importar cualquier paquete de `shared` sin que Spring
Modulith se queje.

### Opción C: detección basada en `@Modulithic(sharedModules = …)` desde la prueba

**Ventajas.** No anota código de producción.

**Desventajas.** La anotación sigue exigiendo el mismo artefacto en el camino de compilación de
todos modos si se declara en el árbol principal, y trasladar una decisión de arquitectura a un
archivo de prueba es peor, no mejor: quien lea `shared.security` no vería, en el propio paquete,
que es contrato público de otro módulo.

### Opción D: mover `TransactionRunner` y `AuditLogWriter` al paquete base de `shared`

**Ventajas.** Sin dependencia nueva: los tipos del paquete base ya son API por omisión.

**Desventajas.** Imposible para `TransactionRunner`: ADR-0015 regla 7 lo sitúa literalmente en
`shared/security`, y `TransactionsOnlyInSharedSecurityTest` (R3) exime exactamente ese prefijo.
Movería además `AuditLogWriter` fuera de donde `AuditLogReader` ya vive, rompiendo la cohesión del
paquete sin necesidad. **Descartada.**

## Decisión

**Se adopta la opción A.** `com.confia.shared.security` y `com.confia.shared.audit` declaran
`@org.springframework.modulith.NamedInterface` en su `package-info.java`, con `spring-modulith-api`
declarado en alcance de **compilación** en `apps/api/app/pom.xml` — nunca `spring-modulith-core`,
que se mantiene en alcance `test`, donde `SpringModulithVerificationTest` lo usa.

Si en un futuro `@NamedInterface` no se comportara como esta decisión asume, el respaldo ya diseñado
es la opción B (`@ApplicationModule(type = OPEN)` sobre `com.confia.shared`), y se reporta la
desviación en vez de relajar `SpringModulithVerificationTest` o añadir una supresión de conveniencia.

## Consecuencias

**Positivas:**

- `identity.application` puede consumir `TransactionRunner` y `AuditLogWriter` sin romper
  `SpringModulithVerificationTest`.
- La superficie pública de `shared` queda escrita en el árbol, en el paquete que se expone, no en un
  archivo de prueba ni en un comentario disperso.
- Ningún módulo futuro que necesite lo mismo de `shared` tiene que redescubrir la sonda S1: este ADR
  ya la deja registrada.

**Negativas y costos aceptados:**

- Una dependencia de producción nueva (`spring-modulith-api`) que antes no existía fuera del alcance
  de prueba.
- Cada módulo de negocio nuevo que necesite otro paquete de `shared` (por ejemplo
  `shared.infrastructure`, si algún día un consumidor real lo justificara) exige su propia anotación
  y, en principio, su propia revisión de si la opción A sigue siendo la más estrecha posible.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Alguien promueve `spring-modulith-core` a alcance de compilación por comodidad, arrastrando ArchUnit a producción | Este ADR y el comentario adyacente en `apps/api/app/pom.xml` documentan explícitamente que solo `spring-modulith-api` va en alcance de compilación |
| `@NamedInterface` no se comporta como se espera en una versión futura de Spring Modulith | Respaldo ya diseñado: `@ApplicationModule(type = OPEN)` sobre `com.confia.shared`, documentado arriba |
| Un módulo futuro anota un paquete de `shared` más ancho de lo que su consumidor real necesita | Revisión de código: cada `@NamedInterface` nuevo debe nombrar el consumidor real que lo justifica |

## Cumplimiento y verificación

1. **`SpringModulithVerificationTest.productionModulesHaveNoViolationYet`.** Rompe la construcción
   si cualquier módulo de negocio depende de un paquete de `shared` no expuesto como interfaz
   nombrada.
2. **`apps/api/app/pom.xml`.** `spring-modulith-api` en alcance de compilación, con comentario que
   cita este ADR; `spring-modulith-core` permanece en alcance `test`.
3. **`maven-enforcer-plugin` (`dependencyConvergence`).** Confirmado en verde tras declarar
   `spring-modulith-api` (sonda S6, `apply-progress.md`).
4. **Revisión humana, declarada como tal:** que ninguna anotación `@NamedInterface` nueva exponga más
   de lo que su consumidor real necesita.

## Referencias

- ADR-0002: monolito modular, reglas de capa
- ADR-0015: acceso a datos con jOOQ y componente transaccional único, regla 7
- `openspec/changes/archive/2026-09-22-audit-log-and-transaction-runner/design.md`, pregunta abierta 2
- `openspec/changes/identity-module-and-password-authentication/design.md`, decisión 12
- `openspec/changes/identity-module-and-password-authentication/apply-progress.md`, sondas S1 y S1b

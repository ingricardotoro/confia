# ADR-0020: Capas opcionales en la regla de capas mientras un módulo no tiene adaptadores

- **Estado:** Aceptado
- **Fecha:** 2026-09-19
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `apps/api/app/src/test/java/com/confia/architecture/` (`LayeredArchitectureTest`, `EmptyShouldExceptionInventoryTest`, `SuppressionCitesAdrTest`), capacidad `build-integrity`, cambio 4 de F0 (`institution-root-and-multitenancy-baseline`). Amplía ADR-0018 sin reemplazarlo.

## Contexto y problema

ADR-0018 autoriza una sola excepción de conjunto vacío: la mitad de producción de una regla de
arquitectura puede evaluarse contra cero clases **mientras `apps/api` no contenga ningún módulo de
negocio** (§1.4). Esa excepción vence con el cambio 4, que crea el módulo `organization` con clases
en `domain` y `application`.

El mismo cambio deja vacías, por decisión aprobada, las capas `infrastructure` y `web` de ese módulo
(propuesta del cambio 4, «Dentro de alcance», punto 4): el único adaptador honesto de
`infrastructure` es el repositorio jOOQ del cambio 5, y `web` no tiene caso de uso de negocio hasta
que ADR-0009 permita administrar instituciones. Crear clases sin propósito en `src/main` para
llenarlas es la opción C que ADR-0018 descarta.

La regla compuesta `layeredArchitecture()` de ArchUnit, además de comprobar dependencias, comprueba
por omisión que **cada capa declarada contenga al menos una clase** y, si no, informa
`Layer '<nombre>' is empty`. Hoy esa comprobación queda neutralizada por `allowEmptyShould(true)`
sobre toda la regla. Al retirar esa llamada, como exige ADR-0018 §2, se espera que la regla falle
por las dos capas vacías. La vacuidad ya no proviene de la ausencia de módulos de negocio, así que
ADR-0018 §1.4 no la ampara de forma literal y su §4 exige un ADR propio para una excepción de
naturaleza distinta.

**Condición de aplicación.** Este ADR solo se aplica si la sonda descrita en «Cumplimiento y
verificación», punto 1, confirma contra ArchUnit 1.4.2 (la versión resuelta, fijada en
`apps/api/pom.xml`) que la regla falla por capas vacías y que `optionalLayer` lo resuelve. Si la
sonda muestra que la regla pasa sin excepción alguna, este ADR pasa a **Rechazado** sin aplicarse y
se conserva, como exige `docs/adr/README.md`.

Si no se decide: o bien la construcción del cambio 4 queda en rojo sin salida válida, o bien alguien
conserva `allowEmptyShould(true)` sobre toda la regla con una condición reescrita, lo que también
silenciaría un error de ámbito en las capas `domain` y `application`, que ya no están vacías.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| La excepción no cubre más de lo que está vacío | Muy alto | Una capa mal definida que queda vacía por error debe seguir rompiendo la construcción |
| Caducidad por evento verificado, no por memoria | Muy alto | Mismo criterio que ADR-0018 §2 |
| Nada falso en `src/main` | Alto | ADR-0002 y ADR-0018, opción C |
| Una sola regla compuesta de capas | Alto | El hallazgo C1 del cambio 1 exigió una regla `layeredArchitecture()` completa, no reglas sueltas |
| Visible para el escáner de supresiones | Alto | Una exclusión que el escáner no ve es una exclusión sin control |

## Opciones consideradas

### Opción A: capas opcionales por nombre, con inventario y escáner

La mitad de producción declara `Infrastructure` y `Web` con `optionalLayer(...)` en lugar de
`layer(...)`. Cada una tiene su entrada en el inventario de caducidad, con su propia condición, y el
escáner de supresiones cataloga la llamada como marcador que exige cita de ADR y cuyo número de
apariciones debe coincidir con las entradas de ese tipo. La mitad de fixture sigue declarando las
cuatro capas como obligatorias.

**Ventajas.** Solo relaja la comprobación de vacío de las dos capas que están vacías; las
dependencias de toda la regla y la presencia de `Domain` y `Application` siguen verificándose. Cada
capa caduca por separado: `Infrastructure` con el cambio 5, `Web` con el primer controlador de
negocio.

**Desventajas.** Dos definiciones de la regla (producción y fixture) que comparten las restricciones,
un tipo de marcador más en el inventario y en el escáner.

### Opción B: conservar `allowEmptyShould(true)` sobre toda la regla con una condición nueva

**Desventajas.** Neutraliza también la comprobación de vacío de `Domain` y `Application`. Si alguien
cambiara por error el patrón `..domain..`, la regla pasaría en silencio. Es más amplia que el
problema. **Se descarta.**

### Opción C: `withOptionalLayers(true)`

**Desventajas.** Vuelve opcionales las cuatro capas de la regla, con el mismo defecto que la opción B
y sin caducidad por capa. **Se descarta y se prohíbe** en cualquier archivo.

### Opción D: clases sin propósito de negocio en `infrastructure` y `web`

**Desventajas.** Es la opción C de ADR-0018, ya descartada: pone en el artefacto de producción
código que no nombra ninguna capacidad y produce una garantía cosmética. **Se descarta.**

## Decisión

**Se adopta la opción A.** Una capa de la regla de capas puede declararse opcional en la mitad de
producción, solo mientras ningún código de producción resida en un paquete de esa capa, con cita a
este ADR junto a la llamada y con una entrada en el inventario de caducidad que rompe la
construcción cuando aparece la primera clase de esa capa.

Alcance exacto:

1. **Dónde.** Solo en `LayeredArchitectureTest`, mitad de producción. Nunca en la mitad de fixture.
2. **Qué.** Solo las capas `Infrastructure` y `Web`, cada una con su llamada `optionalLayer(...)`.
   `Domain` y `Application` son siempre obligatorias.
3. **Caducidad.** `Infrastructure`: vence cuando cualquier clase de producción reside en un paquete
   con el segmento `infrastructure` (previsto en el cambio 5). `Web`: vence cuando cualquier clase de
   producción reside en un paquete con el segmento `web`. Al vencer, se sustituye `optionalLayer` por
   `layer` y se retira la entrada del inventario en el mismo commit.
4. **Prohibido** `withOptionalLayers(true)` en cualquier archivo de `apps/api`.
5. ADR-0018 sigue vigente sin cambios para `allowEmptyShould(true)`.

## Consecuencias

**Positivas:**

- La regla de capas deja de estar neutralizada: evalúa clases reales de `organization` desde el
  cambio 4, con la comprobación de vacío activa para `Domain` y `Application`.
- Cada capa vacía tiene una caducidad propia que rompe la construcción.

**Negativas y costos aceptados:**

- El inventario de caducidad y el escáner ganan un segundo tipo de marcador que hay que mantener.
- La regla de capas se escribe dos veces (producción y fixture) sobre un ayudante común.
- Cuando el cambio 5 añada `infrastructure`, la construcción se pondrá en rojo de forma programada,
  igual que ocurrió con ADR-0018 en el cambio 4. Debe preverse en su `tasks.md`.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Alguien declara opcional `Domain` o `Application` | El inventario solo admite las dos entradas de este ADR; el conteo del escáner obliga a declarar toda llamada nueva, y la revisión aplica el punto 2 del alcance |
| Alguien usa `withOptionalLayers(true)` para silenciar el rojo del cambio 5 | El escáner falla ante esa cadena en cualquier archivo |
| `optionalLayer` no relaja las subreglas de dependencia sobre una capa vacía | Lo descarta la sonda previa; si ocurriera, la aplicación se detiene y se consulta al propietario |

## Cumplimiento y verificación

1. **Sonda previa (una sola vez, en el cambio 4).** Contra ArchUnit 1.4.2, con la forma real de la
   regla y un conjunto con clases solo en `domain` y `application`: (a) sin `allowEmptyShould` ni
   capas opcionales, la regla falla nombrando `Infrastructure` y `Web`; (b) con `optionalLayer` en
   esas dos capas, pasa; (c) con `optionalLayer` pero con `Domain` vacía, sigue fallando. El resultado
   observado se registra en el informe de aplicación.
2. **Inventario de caducidad** (`EmptyShouldExceptionInventoryTest`): una entrada por capa opcional,
   con su condición ejecutable y la cita de este ADR.
3. **Escáner de supresiones** (`SuppressionCitesAdrTest`): `optionalLayer(` exige cita `ADR-NNNN`
   adyacente; el número de llamadas coincide con las entradas de ese tipo; `withOptionalLayers(true)`
   rompe la construcción en cualquier archivo.
4. **Mitad de fixture**: declara las cuatro capas como obligatorias y debe seguir rechazando su
   fixture permanente.
5. **Revisión humana, declarada como tal**: que ninguna otra capa se vuelva opcional.

## Referencias

- ADR-0002: monolito modular, reglas de capa
- ADR-0018: conjunto vacío en las reglas de arquitectura, §1.4, §2 y §4
- ADR-0009: multi-institución, punto 5
- `openspec/changes/institution-root-and-multitenancy-baseline/proposal.md`, decisión P2
- `openspec/changes/institution-root-and-multitenancy-baseline/design.md`, decisión 4

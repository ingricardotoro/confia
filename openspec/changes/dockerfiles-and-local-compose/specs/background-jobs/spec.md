# Delta para Trabajos en segundo plano

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `dockerfiles-and-local-compose` (F0, cambio 11a)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-09

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BJ73` en adelante, a continuación del máximo de la especificación vigente
  (`BJ72`). El escenario `BJ38` se conserva con su número y su texto se ajusta.
- Este cambio incorpora Actuator solo para salud (ver `container-runtime`); no incorpora métricas, exportadores ni
  ningún `MeterRegistry`, que siguen siendo del cambio 10.

## MODIFIED Requirements

### Requisito: Ausencia de exportadores de métricas (brecha con destino: cambios 10 y 11)

El sistema NO DEBE incluir, al cerrar el cambio 11a, ningún exportador de métricas (Prometheus ni otro), ningún
exportador de métricas de cola, ninguna dependencia directa de Micrometer ni ningún endpoint de Actuator distinto
de la salud. Actuator se admite únicamente como módulo de salud, con sus grupos de vida y de preparación. El
iniciador de la biblioteca trae `micrometer-core` como dependencia transitiva; eso se admite siempre que ningún
proceso contenga un `MeterRegistry`. Los exportadores y los tableros pertenecen a los cambios 10 y 11. La
publicación de las métricas de cola es solo el registro estructurado.
(Previously: el sistema no podía incluir Actuator en ningún caso; el cambio 11a lo admite solo para salud.)

#### Escenario: [BJ38] No hay exportadores en el árbol — cierto hoy, falso al cerrar los cambios 10 y 11

- **DADO** el árbol de dependencias de `apps/api` al cerrar el cambio 11a
- **CUANDO** se busca una dependencia de Micrometer o Prometheus declarada por este cambio, la dependencia de
  Actuator, y un `MeterRegistry` en el contexto de cada uno de los tres procesos
- **ENTONCES** ninguna dependencia directa de Micrometer ni de Prometheus existe, la única dependencia de
  Actuator es la de salud, y ningún proceso contiene un `MeterRegistry`; este resultado deja de ser cierto el
  día que los cambios 10 y 11 incorporen las métricas

#### Escenario: [BJ73] Ningún proceso contiene un `MeterRegistry` con la salud habilitada

- **DADO** los procesos administrativo, del portal y trabajador arrancados por su camino de producción, con la
  salud habilitada
- **CUANDO** se consultan los beans de tipo `MeterRegistry` de cada contexto
- **ENTONCES** ninguno contiene ninguno

#### Escenario: [BJ74] Ninguna ruta de métricas responde

- **DADO** los procesos administrativo y del portal con la salud habilitada
- **CUANDO** se piden `/actuator/metrics` y `/actuator/prometheus`
- **ENTONCES** ninguna responde contenido de métricas

#### Escenario: [BJ75] Una prueba falla si aparece un `MeterRegistry` o una ruta de Actuator extra

- **DADO** un fixture permanente que añade un `MeterRegistry` al contexto, y otro que expone un endpoint de
  Actuator distinto de la salud
- **CUANDO** se ejecuta la prueba de ausencia
- **ENTONCES** la prueba falla en ambos fixtures, y con el código real pasa evaluando los tres contextos reales y
  no un conjunto vacío

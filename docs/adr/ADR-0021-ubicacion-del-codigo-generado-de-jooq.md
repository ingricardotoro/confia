# ADR-0021: Ubicación del código generado de jOOQ fuera del paquete base de la aplicación

- **Estado:** Aceptado
- **Fecha:** 2026-09-20
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Generación de código de jOOQ en `apps/api/app` (ADR-0015, regla 2), reglas de ArchUnit de `com.confia.architecture`, verificación de módulos de Spring Modulith, puertas de cobertura de JaCoCo y de mutación de PIT, escáner de supresiones de ADR-0018. Concreta ADR-0015 sin reemplazarla.

## Contexto y problema

ADR-0015, regla 2, decide que el código de jOOQ **se genera en cada construcción** desde las
migraciones de Flyway y **no se compromete al repositorio**, pero no dice **en qué paquete Java**
aterriza. Esa omisión no es menor, porque cuatro mecanismos de verificación ya existentes se
comportan de forma distinta según dónde viva ese paquete:

1. **ArchUnit.** `ArchitectureTestSupport.productionClasses()` importa `com.confia` completo. Todo lo
   que caiga ahí dentro queda sujeto a las reglas de capas, de nombres de paquete, de ciclos y de
   módulos, y ninguna de esas reglas tiene sentido aplicada a código que ninguna persona escribió.
2. **Spring Modulith.** `ApplicationModules.of("com.confia", ...)` trata cada subpaquete directo de
   `com.confia` como un módulo. Un `com.confia.generated` sería un módulo más, con fronteras que
   nadie diseñó.
3. **JaCoCo.** El código generado se compila a `target/classes` y entraría en la regla `BUNDLE` del
   80 % que fijó el cambio 4, hundiéndola con código sin autor y sin pruebas.
4. **PIT.** Su selector actual es `com.confia.*.domain.*`, de modo que solo le afecta si el paquete
   generado cae bajo `com.confia`.

La decisión es costosa de revertir: el paquete generado aparece en los `import` de **todos** los
repositorios del sistema, así que moverlo más adelante toca cada módulo con persistencia.

El propietario ya resolvió el sentido de la decisión el 2026-09-20 (decisión D3 de
`openspec/changes/jooq-flyway-testcontainers-wiring/proposal.md`): **fuera de `com.confia`**. Este
ADR fija el valor concreto y sus consecuencias verificables, y deja constancia de la única exclusión
de herramienta que sigue siendo necesaria, para que el escáner de supresiones de ADR-0018 tenga un
ADR real al que citar.

Si no se decide: cada regla de arquitectura acumula una excepción por caso, la puerta de cobertura se
relaja por código que nadie escribió, y ninguna de esas dos cosas queda registrada como decisión.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Ninguna excepción por caso en las reglas de arquitectura | Muy alto | Una excepción por regla es una puerta para relajarlas sin decisión (ADR-0018) |
| La puerta de cobertura mide código con autor | Muy alto | El 80 % global de `app` protege trabajo humano, no salida de un generador |
| Costo de cambiarlo después | Alto | El paquete aparece en el `import` de cada repositorio del sistema |
| Toda exclusión visible para el escáner de supresiones | Alto | Una exclusión que el escáner no ve es una exclusión sin control (ADR-0018) |
| La propiedad de tablas por módulo sigue siendo verificable | Alto | ADR-0015, cumplimiento 4 |

## Opciones consideradas

### Opción A: `com.confia.generated`, dentro del paquete base

**Ventajas.** Convención de nombres estándar de Java (dominio invertido), un único árbol de paquetes.

**Desventajas.** Obliga a exceptuar el paquete, uno por uno, en la regla de capas, en la de nombres
de paquete, en la de ciclos, en la verificación de módulos de Spring Modulith y en el selector de
PIT, además de en JaCoCo. Cada excepción es un marcador que el escáner de supresiones exige declarar
y justificar. **Se descarta**; el propietario ya la descartó en D3.

### Opción B: `confia.generated.jooq`, fuera del paquete base

El generador escribe en `target/generated-sources/jooq` con paquete raíz propio, distinto de
`com.confia`.

**Ventajas.** ArchUnit y Spring Modulith **nunca lo importan**, porque ambos parten de `com.confia`,
y sin embargo las reglas pueden seguir prohibiéndolo: ArchUnit evalúa el destino de cada dependencia
aunque no haya importado la clase destino. El selector de PIT tampoco lo alcanza. Queda una sola
exclusión real, la de JaCoCo, declarada y citada.

**Desventajas.** Un paquete raíz de un solo segmento se aparta de la convención de dominio invertido.
Es precisamente lo que se busca: leer `confia.generated.jooq` en un `import` dice «esto no es código
de la aplicación».

### Opción C: un módulo Maven aparte (`confia-db`) con el código generado

**Ventajas.** Evitaría incluso la exclusión de JaCoCo, porque el código generado viviría en otro
artefacto y por tanto en otro paquete de cobertura. Separa con claridad lo que exige Docker de lo que
no.

**Desventajas.** Se aparta del mapa de áreas afectadas de la propuesta aprobada, duplica el cableado
de generación, y deja sin ruta natural el script de roles solo de prueba, que hoy comparten el
contenedor de generación y las pruebas de integración de `app`. **No se adopta ahora**, y se registra
como la alternativa a considerar si el volumen de código generado creciera hasta molestar en los
informes de cobertura.

### Opción D: comprometer el código generado al repositorio

**Desventajas.** Lo prohíbe ADR-0015, regla 2, y rompe la garantía que justifica todo el mecanismo:
el esquema y el código dejarían de sincronizarse en cada construcción. **Se descarta.**

## Decisión

**Se adopta la opción B.** El código que genera jOOQ vive en el paquete **`confia.generated.jooq`**,
se escribe en `target/generated-sources/jooq` del módulo `app`, no se compromete al repositorio y se
excluye de la medición de cobertura.

Reglas de la decisión:

1. **Paquete raíz propio.** `confia.generated.jooq`, fuera de `com.confia`. Ningún código de
   producción fuera de un paquete `infrastructure` lo importa (ADR-0015, regla 4).
2. **Sin referencias globales.** La generación desactiva las clases paraguas de jOOQ (`Tables`,
   `Keys`, `Indexes`). Cada referencia a una tabla nombra su propio tipo generado, que es lo que hace
   verificable la propiedad de tablas por módulo de ADR-0015, cumplimiento 4: sin ese ajuste, un solo
   tipo paraguas daría acceso a todas las tablas del sistema y la regla no distinguiría nada.
3. **Tablas técnicas excluidas de la generación** (ADR-0017, regla 6).
4. **Una sola exclusión de herramienta, declarada.** JaCoCo excluye `confia/generated/**` en las
   ejecuciones de informe y de comprobación de `app`, con una cita a este ADR adyacente a cada
   `<exclude>`, como exige el escáner de supresiones de ADR-0018. **No se añade ninguna exclusión de
   PIT**: su selector `com.confia.*.domain.*` no alcanza al paquete generado.
5. **Ninguna excepción en las reglas de arquitectura.** Si alguna regla necesitara exceptuar el
   paquete generado, es señal de que la regla está mal escrita o de que el paquete acabó donde no
   debía; se corrige eso, no se añade la excepción.

## Consecuencias

**Positivas:**

- Las reglas de arquitectura y la verificación de módulos no ganan ni una sola excepción por caso.
- La puerta de cobertura del 80 % sigue midiendo código con autor.
- Un `import confia.generated.jooq...` fuera de `infrastructure` se lee como lo que es: una
  violación, y además rompe la construcción.
- La propiedad de tablas por módulo se vuelve verificable con una regla sobre tipos, sin analizar
  cadenas de texto.

**Negativas y costos aceptados:**

- El paquete raíz de un solo segmento se aparta de la convención de dominio invertido de Java.
- Sin clases paraguas, cada consulta importa el tipo de cada tabla que usa: más `import` por archivo.
- Queda una exclusión de JaCoCo que hay que mantener citada; si el paquete se renombrara, hay que
  renombrarla con él.
- La medición de cobertura del módulo `app` depende de que esa exclusión sea correcta: un patrón mal
  escrito relajaría la puerta en silencio. Lo cubre la verificación 3 de abajo.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Alguien mueve el paquete generado bajo `com.confia` por comodidad | La regla de confinamiento de jOOQ nombra el paquete explícitamente y su fixture negativo permanente falla si el nombre deja de corresponder |
| El patrón de exclusión de JaCoCo deja de coincidir y la cobertura se calcula sobre código generado | El informe se revisa al cerrar el corte que introduce la generación, antes de escribir el primer adaptador, y la cifra global se compara con la anterior |
| Alguien reactiva las referencias globales de jOOQ | La regla de propiedad de tablas por módulo deja de tener sujeto y su fixture negativo deja de ser rechazado, lo que rompe la construcción |
| El volumen de código generado crece y molesta en los informes | Se reconsidera la opción C, con un ADR nuevo |

## Cumplimiento y verificación

Todo lo siguiente se ejecuta en integración continua y una falla rompe la construcción.

1. **Confinamiento.** Regla de ArchUnit: ninguna clase fuera de un paquete `infrastructure` depende
   de `org.jooq..` ni de `confia.generated..`, con fixture negativo permanente que la regla debe
   rechazar nombrando la clase infractora.
2. **Propiedad de tablas.** Regla de ArchUnit que asocia el nombre del tipo generado al módulo que lo
   usa, con su propio fixture negativo. Depende de la regla 2 de esta decisión.
3. **Exclusión declarada y citada.** El escáner de supresiones de ADR-0018 exige una cita
   `ADR-NNNN` a menos de cuatro líneas de cada `<exclude>` de JaCoCo y comprueba que ese ADR existe
   en `docs/adr/`. Este ADR es esa cita.
4. **Código generado ausente del repositorio.** Vive bajo `target/`, que ya está ignorado; la
   revisión de cada pull request lo comprueba y `git status` lo evidencia.
5. **Revisión humana, declarada como tal:** que ninguna regla de arquitectura gane una excepción por
   el paquete generado.

## Referencias

- ADR-0002: monolito modular, reglas de capa
- ADR-0015: acceso a datos con jOOQ, reglas 2 y 4, cumplimiento 2 y 4
- ADR-0017: tablas técnicas y tabla raíz, regla 6
- ADR-0018: conjunto vacío en las reglas de arquitectura y escáner de supresiones
- `openspec/changes/jooq-flyway-testcontainers-wiring/proposal.md`, decisión D3
- `openspec/changes/jooq-flyway-testcontainers-wiring/design.md`, decisión 2

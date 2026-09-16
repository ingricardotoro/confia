# ADR-0018: Conjunto vacío en las reglas de arquitectura y caducidad de sus excepciones

- **Estado:** Propuesto
- **Fecha:** 2026-09-16
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Pruebas de arquitectura de `apps/api/app` (paquete `com.confia.architecture`), `apps/api/app/src/test/resources/archunit.properties`, capacidad `build-integrity` de F0 (cambios 1 y 4), integración continua. Complementa ADR-0002 y ADR-0008.

## Contexto y problema

ArchUnit trae una red de seguridad: una regla que se evalúa contra **cero clases** no pasa en
silencio, sino que falla la construcción. La propiedad que gobierna ese comportamiento es
`archRule.failOnEmptyShould` y su valor predeterminado es `true`. Existe porque el modo de fallo
más peligroso de una regla de arquitectura no es que rechace algo válido, sino que no inspeccione
nada: una regla cuyo ámbito quedó mal escrito coincide con cero clases, pasa siempre y produce una
garantía falsa escrita en el repositorio.

CONFIA escribió sus reglas de arquitectura en el cambio 1 de F0
(`maven-workspace-and-ci-skeleton`), **antes** de tener módulos de negocio. Eso es deliberado y la
propuesta lo defendió: las reglas deben existir antes que el código que van a restringir. La
consecuencia mecánica es que hoy la mitad de cada regla que se aplica al código de producción se
evalúa contra un conjunto vacío, porque no hay ninguna clase en un paquete `domain`, `application`,
`infrastructure` o `web`. La otra mitad de cada regla, la que se ejercita contra el paquete de
fixtures permanente `com.confia.architecture.fixture`, nunca está vacía, y es la que realmente
demuestra que la regla funciona (verificado de primera mano en la prueba negativa P4 del informe de
verificación).

Para que la mitad de producción no rompiera la construcción, el cambio 1 fijó
`archRule.failOnEmptyShould=false` en `archunit.properties`, con una justificación escrita que cita
`docs/06-estrategia-de-testing.md` sección 14.1 y el `design.md` del cambio. El informe de
verificación lo marcó como hallazgo crítico C3, y tiene razón por dos motivos distintos:

1. **La excepción es global y permanente.** La propiedad no distingue entre las reglas de hoy y las
   de mañana: cubre por adelantado a toda regla que alguien escriba en el futuro. Una regla nueva
   con el ámbito mal escrito, en el cambio 7 o en F5, coincidirá con cero clases y pasará en
   silencio para siempre. Además nada obliga a devolver la propiedad a `true` cuando desaparezca la
   razón que la justificó: no hay tarea, ni fecha, ni mecanismo.
2. **La excepción no cita un ADR.** El requisito "Ninguna regla se desactiva sin un ADR" de la
   especificación `build-integrity` declara que una desactivación sin referencia a un ADR es, en sí
   misma, una violación. La regla general ya existía antes: `docs/06` sección 3, "Regla de la
   excepción", exige referencia escrita al ADR que autoriza cualquier exclusión de ArchUnit,
   Spring Modulith, JaCoCo o PIT, y ADR-0002 advierte que si las reglas de arquitectura "se
   desactivan o sus reglas se relajan sin ADR, la decisión queda anulada en la práctica". Citar dos
   documentos de diseño no satisface esa exigencia.

Hay un tercer problema, más profundo, que este ADR también debe resolver: ese requisito **no tiene
ningún mecanismo automático**. Hoy se sostiene en la prosa de un `package-info.java` y en
comentarios del POM. Una regla que solo vive en un comentario no es una regla.

Si no se decide: el cambio 4, `institution-root-and-multitenancy-baseline`, introducirá el primer
módulo de negocio real contra un conjunto de reglas cuya red de seguridad ante conjunto vacío está
apagada globalmente, y nadie se enterará. Es exactamente el fallo que la capacidad `build-integrity`
existe para impedir.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| La excepción no puede cubrir reglas futuras | Muy alto | Una excepción escrita antes de que exista la regla que ampara es un cheque en blanco. |
| Caducidad automática, no recordatoria | Muy alto | Con un solo desarrollador, lo que depende de recordar no ocurre. Una excepción que vence en silencio es el modo de fallo que este ADR existe para prevenir. |
| Alcance mínimo y enumerable | Muy alto | Una excepción global no se puede revisar; una lista cerrada sí. |
| Nada falso en `src/main` | Alto | El artefacto de producción no lleva clases sin propósito de negocio (ADR-0002, organización screaming). |
| Verificable en integración continua | Alto | Lo que no rompe la construcción se erosiona (`docs/06` sección 3). |
| Costo de escritura hoy | Medio | Aceptable si compra caducidad verificada. |

## Opciones consideradas

### Opción A: `archRule.failOnEmptyShould=false` global (estado actual)

Una sola línea en `archunit.properties` desactiva la comprobación de conjunto vacío para todas las
reglas del módulo.

**Ventajas.** Coste de escritura cero. Ninguna regla existente necesita tocarse.

**Desventajas.** Desactiva la mitad de producción de **toda** regla, incluidas las que todavía no se
han escrito, sin fecha ni evento de caducidad. Convierte un error de ámbito en un aprobado
permanente. Es además la desactivación que hoy incumple el requisito 8 por no citar un ADR. Tiene un
efecto colateral que el informe de verificación ya señaló como S2: con la propiedad en `false`, un
fallo por conjunto vacío en la mitad de fixture no puede distinguirse de un rechazo legítimo, porque
`assertRuleRejects` acepta cualquier `AssertionError`. **No se adopta.**

### Opción B: valor predeterminado `true` y excepción explícita por regla, con caducidad verificada

Se elimina la desactivación global y `archunit.properties` declara el valor `true` de forma
explícita. La regla concreta que hoy se evalúa contra cero clases lleva `allowEmptyShould(true)` en
su definición, con la cita de este ADR junto al código. Un inventario en código enumera cada
excepción vigente y la condición que la justifica, y falla la construcción cuando esa condición deja
de cumplirse.

**Ventajas.** La excepción se ve en el diff de la regla que la necesita, no escondida en un archivo
de propiedades. Ninguna regla futura queda cubierta por adelantado: quien escriba una regla nueva mal
delimitada verá fallar la construcción, que es el comportamiento correcto. La caducidad es una
comprobación, no un recordatorio. Devuelve además la red de seguridad a la mitad de fixture: si algún
día el fixture deja de coincidir con la regla, la construcción falla en vez de leerse como un
rechazo.

**Desventajas.** Hay que tocar cada regla existente y mantener el inventario. La caducidad se
manifiesta como una construcción en rojo dentro del cambio 4, que hay que prever en su lista de
tareas.

### Opción C: valor predeterminado `true` y un fixture de producción por regla

Se crean bajo `src/main/java` clases mínimas en paquetes `domain`, `application`, `infrastructure` y
`web` de un módulo de muestra, para que ninguna regla se evalúe contra un conjunto vacío.

**Ventajas.** No hace falta ninguna excepción: la propiedad queda en `true` sin matices.

**Desventajas.** Mete en el artefacto de producción, y por tanto en las tres imágenes de contenedor,
un módulo que no nombra ninguna capacidad de negocio, lo que contradice de frente la organización
screaming de ADR-0002 y la regla de nombres de `CLAUDE.md`. Y compra una garantía cosmética: la regla
deja de estar vacía, pero sigue sin inspeccionar código real, con el agravante de que ahora **parece**
que sí. Alguien tendría además que acordarse de borrar ese módulo falso cuando llegue el real, con lo
que el problema de caducidad vuelve, esta vez sin ninguna comprobación que lo detecte. **Se descarta.**

### Opción D: aplazar o deshabilitar las mitades de producción hasta el cambio 4

Se borran, o se anotan como deshabilitadas, las pruebas que aplican cada regla al código de
producción, y se reescriben cuando exista el primer módulo.

**Desventajas.** Es la alternativa que la propuesta del cambio 1 ya rechazó: aplazar la regla hasta
que exista el código que debe restringir invierte el orden. Y deja la protección dependiendo de que
alguien recuerde reactivarla, que es el mismo modo de fallo de la opción A con otra forma. **Se
descarta.**

## Decisión

**Se adopta la opción B: el valor predeterminado de ArchUnit ante conjunto vacío vuelve a `true` en
todo el repositorio, y la ausencia de módulos de negocio se declara regla por regla, con cita a este
ADR y con una caducidad que rompe la construcción cuando su justificación desaparece.**

Razón principal: una excepción global sin caducidad protege al desarrollador de una molestia de hoy
a cambio de desarmar en silencio todas las reglas de mañana; una excepción por regla con caducidad
verificada cuesta unas líneas y no puede sobrevivir a su motivo.

### 1. Alcance exacto de la excepción

La excepción autorizada por este ADR es **una sola y cabe en una frase**: la mitad de producción de
una regla de arquitectura de la capacidad `build-integrity` puede evaluarse contra cero clases,
mientras `apps/api` no contenga ningún módulo de negocio.

Queda delimitada así:

1. **Dónde.** Únicamente en las definiciones de regla de
   `apps/api/app/src/test/java/com/confia/architecture/`. En ningún otro paquete, módulo Maven ni
   aplicación del monorepo.
2. **Cómo.** De forma fluida y por regla, con `allowEmptyShould(true)` en la definición de esa regla.
   Queda prohibido `archRule.failOnEmptyShould=false`, en cualquier archivo de propiedades y en
   cualquier módulo. El archivo `archunit.properties` declara el valor `true` de forma explícita, para
   que un cambio futuro a `false` sea visible en el diff y no una omisión.
3. **Nunca en la mitad de fixture.** La mitad de cada regla que se ejercita contra
   `com.confia.architecture.fixture` no lleva la excepción jamás. Ese conjunto no está vacío por
   construcción, y si algún día lo estuviera, la construcción debe fallar.
4. **Solo por ausencia de módulos de negocio.** La excepción ampara la vacuidad que proviene de que el
   código de producción todavía no existe. Una regla vacía porque su ámbito está mal escrito es un
   defecto, no una excepción, y este ADR no la autoriza.
5. **No se extiende a ninguna otra herramienta.** `maven-enforcer-plugin`, la verificación de módulos
   de Spring Modulith, JaCoCo, PIT, Trivy, gitleaks, `dependency-cruiser` y las reglas de frontera de
   ESLint no quedan relajados por este ADR en ninguna medida. Una exclusión en cualquiera de ellos
   necesita su propio ADR.

### 2. Caducidad: un evento verificado, no una fecha

La excepción **vence con la aparición del primer módulo de negocio en `apps/api/app`**, es decir con
el cambio 4 de F0, `institution-root-and-multitenancy-baseline`, que introduce el módulo
`organization`. No se fija una fecha de calendario: una fecha caduca sola y no prueba nada, mientras
que el evento es observable en el propio código.

Cerrar la caducidad es trabajo de ese cambio y debe figurar en su lista de tareas: retirar
`allowEmptyShould(true)` de cada regla cuyo ámbito de producción haya dejado de estar vacío, junto con
su entrada en el inventario y su comentario.

Una regla puede seguir legítimamente vacía después de ese evento. El caso claro es
`no-cross-module-domain`, cuya premisa son dos módulos de negocio: con uno solo, la comparación entre
módulos distintos sigue sin tener nada que inspeccionar. Para esos casos, la excepción sobrevive solo
si el cambio que la conserva **actualiza su condición de caducidad en el inventario** —por ejemplo, de
"no existe ningún módulo de negocio" a "existe menos de un segundo módulo de negocio"— citando este
ADR. Lo que no se admite es conservar la excepción con su condición original ya incumplida: en ese
estado la construcción falla, y debe fallar.

### 3. Mecanismo de reversión automática

Se añaden dos pruebas al módulo `app`, que se ejecutan dentro de `./mvnw verify` y por tanto en cada
empuje.

**a) Inventario de caducidad.** Una prueba enumera, en código, cada excepción vigente: la regla que la
lleva, la condición que la justifica y este ADR. Para cada entrada evalúa la condición contra las
clases de producción importadas y **falla la construcción cuando la condición ya no se cumple**,
nombrando la regla y la línea de la que hay que retirar `allowEmptyShould(true)`. Mientras no exista
ningún módulo de negocio, todas las condiciones se cumplen y la prueba pasa; el día que el cambio 4
añada `organization`, la construcción se pone en rojo hasta que alguien decida explícitamente qué hace
con cada excepción. Ese rojo programado es el producto de este ADR, no un efecto secundario.

**b) Escáner de supresiones sin ADR.** Una prueba recorre el árbol de fuentes de `apps/api` y falla si
encuentra un marcador de supresión sin una cita `ADR-NNNN` adyacente, si el ADR citado no existe como
archivo en `docs/adr/`, o si aparece `failOnEmptyShould=false` en cualquier archivo. Verifica además
que el número de apariciones de `allowEmptyShould(` coincide exactamente con el número de entradas del
inventario, de modo que **no se puede añadir una excepción sin declarar cuándo caduca**. El catálogo
de marcadores nace con `allowEmptyShould(`, `failOnEmptyShould` y las anotaciones de exclusión de
ArchUnit, y crece con cada herramienta que incorpore un mecanismo de exclusión: las exclusiones de
JaCoCo y de PIT se añaden en el cambio 2, las de `dependency-cruiser` y ESLint en el cambio 3.

Esta segunda prueba es también lo que da al requisito "Ninguna regla se desactiva sin un ADR" el
mecanismo automático que hoy le falta: deja de ser prosa en un `package-info.java`.

### 4. Regla permanente para excepciones futuras

Toda desactivación, supresión o exclusión de una regla de `build-integrity`, hoy y en adelante, lleva
**junto al código** la cita del ADR que la autoriza, por número. Mientras no exista un ADR más
específico, una excepción de conjunto vacío cita `ADR-0018`. Una excepción de naturaleza distinta no
se ampara en este ADR: necesita el suyo. Una supresión sin cita es un defecto y el escáner rompe la
construcción.

## Consecuencias

**Positivas:**

- Ninguna regla futura queda amparada por una excepción escrita antes de que la regla existiera. Una
  regla nueva con el ámbito mal escrito falla al escribirla, que es cuando cuesta barato arreglarla.
- La excepción caduca por comprobación, no por memoria. El evento que la invalida rompe la
  construcción.
- El requisito 8 de `build-integrity` pasa de ser prosa a tener dos verificaciones ejecutables, lo que
  permite convertir su escenario "Exclusión sin justificación" en una prueba real.
- La red de seguridad ante conjunto vacío vuelve a proteger la mitad de fixture de cada regla, lo que
  mitiga en parte el hallazgo S2 del informe de verificación: un fixture que dejara de coincidir ya no
  puede leerse como un rechazo legítimo. La aserción sobre el mensaje de la violación sigue siendo
  deseable y no la sustituye este ADR.
- La excepción es visible en la revisión del código de la regla, no enterrada en un archivo de
  configuración que nadie abre.

**Negativas y costos aceptados:**

- **Cada regla escrita antes del cambio 4 paga dos líneas**: la llamada `allowEmptyShould(true)` con su
  comentario y la entrada en el inventario. Es un costo real y recurrente durante F0.
- **El cambio 4 nace con una construcción en rojo programada.** Es intencional, pero hay que preverlo
  en su `tasks.md` y en su presupuesto de revisión; descubrirlo por sorpresa a mitad de ese cambio sería
  una mala experiencia evitable.
- **El escáner de supresiones es código propio que hay que mantener.** Su catálogo de marcadores crece
  con cada herramienta nueva, y un marcador que falte es un agujero. Se acepta porque la alternativa es
  no tener ningún mecanismo.
- **El inventario puede desalinearse** si alguien escribe la excepción con una forma que el escáner no
  reconoce. La comprobación de conteo lo detecta en el caso normal, pero no es una garantía formal.
- Un desarrollador con prisa puede vaciar el inventario y retirar las excepciones a la fuerza en vez de
  decidir. Eso rompería las reglas de inmediato y de forma visible, que es preferible a romperlas en
  silencio.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Alguien devuelve `archRule.failOnEmptyShould=false` para silenciar el rojo del cambio 4 | El escáner falla ante esa cadena en cualquier archivo del repositorio, y el valor `true` está escrito de forma explícita para que el cambio se vea en el diff. |
| Se añade una excepción nueva sin declarar su caducidad | El escáner exige que el número de apariciones de `allowEmptyShould(` coincida con el número de entradas del inventario. |
| La condición de caducidad se escribe tan laxa que nunca se incumple | La condición se revisa como parte de la revisión de la regla; el agente `confia-code-reviewer` la trata como criterio. Es la parte que depende de juicio humano y se declara como tal. |
| El escáner produce falsos positivos y se termina desactivando | Su catálogo de marcadores es una lista cerrada y explícita, no una heurística; ampliarla es un cambio deliberado. |
| Una regla queda vacía por ámbito mal escrito y se ampara indebidamente en este ADR | La regla 4 del alcance lo prohíbe expresamente, y la mitad de fixture de cada regla obliga a que el ámbito coincida con algo real. |

## Cumplimiento y verificación

Todo lo siguiente se ejecuta dentro de `./mvnw verify`, y por tanto en local y en el trabajo
`backend` de integración continua. Una falla rompe la construcción.

1. **Inventario de caducidad.** Prueba que evalúa la condición declarada de cada excepción vigente
   contra las clases de producción importadas y falla cuando una condición deja de cumplirse, nombrando
   la regla afectada.
2. **Escáner de supresiones.** Prueba que recorre las fuentes de `apps/api` y falla ante un marcador de
   supresión sin cita `ADR-NNNN` adyacente, ante un ADR citado que no existe en `docs/adr/`, ante
   cualquier aparición de `failOnEmptyShould=false`, y ante una discrepancia entre el número de
   excepciones en el código y el número de entradas del inventario.
3. **Valor explícito de la propiedad.** `apps/api/app/src/test/resources/archunit.properties` declara
   `archRule.failOnEmptyShould=true`, con el comentario que cita este ADR. La comprobación 2 cubre la
   regresión.
4. **Mitad de fixture de cada regla.** Se conserva tal cual: cada regla debe rechazar su fixture
   permanente. Con el valor predeterminado restituido, un fixture que dejara de coincidir falla en vez
   de pasar como rechazo.
5. **Revisión humana, declarada como tal.** Si la condición de caducidad de una excepción es
   suficientemente exigente es un juicio que ninguna herramienta emite. Se revisa en el pull request que
   introduce la excepción, con el criterio de la sección 2 de este ADR.

### Por confirmar durante la implementación

De la API de ArchUnit, este ADR solo da por establecidos dos elementos, ambos atestiguados en el
informe de verificación del cambio 1: la propiedad `archRule.failOnEmptyShould` y la forma fluida
`allowEmptyShould(true)`. Antes de escribir el código se confirman contra la documentación de la
versión fijada de ArchUnit: la disponibilidad de `allowEmptyShould` en cada tipo de constructor de
regla que CONFIA usa (incluidas las reglas de capas y de ciclos, que no son `noClasses()`), y el
nombre exacto de las anotaciones de exclusión que debe vigilar el escáner. Si alguna de esas
confirmaciones obliga a apartarse de lo decidido aquí, la desviación se eleva a un ADR nuevo y no se
entierra en un `design.md` (`docs/13-metodologia-sdd.md`, regla 5).

## Referencias

- ADR-0002: monolito modular frente a microservicios, sección "Cumplimiento y verificación", reglas de
  capa y de módulo
- ADR-0008: estrategia de pruebas
- ADR-0013: backend en Java con Spring Boot
- `docs/06-estrategia-de-testing.md`, sección 3 ("Regla de la excepción") y sección 14.1
- `docs/13-metodologia-sdd.md`, regla 5
- `openspec/changes/maven-workspace-and-ci-skeleton/specs/build-integrity/spec.md`, requisito "Ninguna
  regla se desactiva sin un ADR"
- `openspec/changes/maven-workspace-and-ci-skeleton/design.md`, decisión 3 (fixtures negativos
  permanentes) y punto 3 de "Por confirmar durante la implementación"
- `openspec/changes/maven-workspace-and-ci-skeleton/verify-report.md`, hallazgos C3 y S2
- [ArchUnit: configuración y comportamiento ante conjunto vacío](https://www.archunit.org/userguide/html/000_Index.html)

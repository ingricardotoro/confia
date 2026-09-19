# Propuesta: raíz de institución y base multi-institución

- **Cambio:** `institution-root-and-multitenancy-baseline`
- **Fase del roadmap:** F0, cambio 4 de 13 (exploración `foundations-plan`, aprobada el 2026-09-15;
  alcance nuevo incorporado a F0 por el hallazgo de esa exploración)
- **Exploración:** `openspec/changes/institution-root-and-multitenancy-baseline/exploration.md`
- **Rama:** `change/institution-root-and-multitenancy-baseline`
- **Estado:** aprobada por el propietario el 2026-09-19 (P3, `openspec/config.yaml`,
  `rules.proposal`). D1, D2, P1, P2 y P3 resueltas

## Intención

ADR-0009 exige que todo el modelo de datos gire alrededor de la institución, y ADR-0017 asigna la
tabla raíz de instituciones al módulo `organization`. Hoy `apps/api/app` solo contiene
`com.confia.bootstrap`: no existe ningún módulo de negocio, ningún tipo que represente a la
institución y ningún identificador de institución que los módulos siguientes (cambios 5 a 9) puedan
usar en sus firmas.

Este cambio crea el primer módulo de negocio del backend con el agregado `Institution` y su
identificador, y con eso cierra tres obligaciones que dependen justamente de que exista un primer
módulo:

1. **El rojo programado de ADR-0018.** La excepción de conjunto vacío de la regla de capas caduca con
   el primer módulo de negocio; el inventario rompe la construcción hasta que se retire.
2. **Las reglas de ArchUnit de ADR-0004 §Cumplimiento 2**, diferidas a este cambio por la decisión
   D3 del cambio 2.
3. **La puerta global de cobertura de 80 % sobre `app`**, que `openspec/config.yaml` (línea 42)
   anuncia para este cambio, más los umbrales de 95 % y de mutación 80 del paquete `domain` del nuevo
   módulo (`CLAUDE.md`, «Pruebas»).

Sin este cambio, el cambio 5 no tiene a qué institución apuntar su primera migración, y las reglas
de arquitectura seguirían evaluándose contra un conjunto vacío.

## Alcance

### Dentro de alcance

1. **Identificador de institución (`InstitutionId`)**, objeto de valor sobre `UUID`, sin
   constructor que acepte nulo. Ubicación por defecto: `kernel` (ver decisión técnica 1).
2. **Módulo `com.confia.organization`, capa `domain`** (sin framework, sin entrada ni salida):
   - Agregado raíz `Institution` con los atributos de `docs/02-modelo-de-dominio.md` (línea 222):
     `id`, `legalName`, `tradeName`, `rtn`, `address`, `defaultCurrency` (`CurrencyCode` de
     `kernel`), `locale`, `timezone` e `isActive`.
   - Invariantes de construcción y de transición (activar y desactivar) expresadas como subclases
     de `DomainException` con códigos kebab-case estables (ADR-0019), por ejemplo
     `institution-legal-name-blank` o `institution-rtn-invalid`; la lista exacta se fija en la
     especificación. Los errores de programación (argumento nulo) usan las excepciones del JDK
     (ADR-0019, punto 6).
   - Prueba de catálogo de los códigos del módulo: formato y ausencia de repetidos (ADR-0019,
     «Cumplimiento», punto 2, extendido al módulo).
3. **Capa `application`** con lo mínimo que tiene un consumidor nombrado:
   - Puerto de salida `InstitutionRepository` (cargar por identificador), que implementa el cambio 5
     con jOOQ.
   - Puerto de salida para la institución de la solicitud en curso (nombre provisional
     `CurrentInstitutionProvider`), que implementa el cambio 7 a partir del token autenticado,
     nunca de un parámetro del cliente (ADR-0009, «Implementación del aislamiento»).
   - Caso de uso que resuelve la institución en curso y rechaza una institución inexistente o
     inactiva con su error de dominio. Se prueba con dobles en memoria; no se registra como bean de
     Spring hasta que existan sus adaptadores, porque sin ellos el contexto no arrancaría.
4. **Capas `infrastructure` y `web`: vacías en este cambio**, con justificación:
   - `infrastructure`: su único adaptador real es el repositorio jOOQ (cambio 5, decisión D1) y el
     proveedor de institución desde el token (cambio 7, Spring Security). No existe un adaptador
     honesto sin base de datos ni seguridad; uno basado en configuración contradiría ADR-0009
     («a partir del token autenticado»).
   - `web`: ADR-0009, punto 5, prohíbe construir ahora la administración de instituciones, y
     springdoc llega con el cambio 3. Un controlador sin caso de uso de negocio sería una clase sin
     propósito en `src/main`, lo que ADR-0018 (opción C) descarta expresamente.
5. **Rojo programado de ADR-0018**: retirar `allowEmptyShould(true)` de
   `LayeredArchitectureTest.java` (línea 74), el ayudante `noBusinessModuleExistsYet` y su entrada
   en `EmptyShouldExceptionInventoryTest`, manteniendo exacto el invariante de conteo de
   `SuppressionCitesAdrTest`. Si la regla de capas falla por las capas `infrastructure` y `web`
   vacías, se aplica la decisión P2.
6. **Reglas de ArchUnit de ADR-0004 §Cumplimiento 2**, cada una con su mitad de producción y su
   fixture negativo permanente:
   - prohibir `new BigDecimal(double)` y `BigDecimal.valueOf(double)` en todo el código de
     producción;
   - prohibir `BigDecimal.equals` fuera de `Money`;
   - prohibir campos, parámetros y retornos `double` o `float` en tipos monetarios.
   Se diseñan para no ser vacías (ver decisión técnica 3); una excepción nueva de conjunto vacío
   solo se admite con la decisión P2.
7. **Puertas de calidad en `app`**:
   - JaCoCo global de 80 % de líneas y ramas sobre `app`, que rompe `./mvnw verify`.
   - JaCoCo de 95 % de líneas y ramas sobre `com.confia.organization.domain`.
   - PIT con umbral 80 sobre `com.confia.organization.domain`, con los perfiles existentes
     `mutation-gate` (rama principal) y `mutation-report` (ramas de trabajo).
8. **`openspec/config.yaml`**: actualizar el comentario de `coverage_threshold` para declarar la
   puerta global ya activa.
9. **Enmienda documental de la decisión D1**: nota fechada en
   `openspec/changes/foundations-plan/exploration.md` que asigna al cambio 5 la migración de
   `organization_institution` y su repositorio jOOQ. Ya escrita en esta fase.

### Fuera de alcance

- **Tabla `organization_institution`, su migración, su política de seguridad a nivel de fila y su
  repositorio jOOQ**: primera migración del cambio 5 (decisión D1).
- **`AcademicYear`, `Modality`, `Grade`, `Section` y `BillingPeriod`**: entregable 1 de F1
  (`docs/09-roadmap-y-fases.md`), decisión D2.
- **Adaptador del proveedor de institución desde el token** y todo cableado de Spring Security:
  cambio 7.
- **Administración de instituciones** (alta, conmutador, pantallas, endpoints): diferida por
  ADR-0009, punto 5.
- **Escenario positivo de W2** («Cada módulo usa solo su propio dominio»): exige dos módulos de
  negocio; lo demuestra el cambio 7. Este cambio solo aporta el primer módulo.
- **Pendientes de `Money`** heredados del cambio 2 (tope de partes de `allocate`, validación
  duplicada entre `Money` y `Percentage`, regla de escala de `BigDecimal` en `docs/03`): siguen
  diferidos; `organization` no manipula importes.
- **Auditoría de acciones sensibles**: no hay acción sensible en este cambio (no hay escritura ni
  endpoint); la bitácora llega con el cambio 5.
- **Regla de ArchUnit que exige que las excepciones de `domain` hereden de `DomainException`**
  (mencionada como mitigación posible en ADR-0019): no está asignada a este cambio; se registra
  como seguimiento opcional.

## Capacidades

### Nuevas

- `organization`: capacidad de negocio del catálogo de `openspec/project.md` (línea 110). En este
  cambio especifica solo el agregado `Institution`, su identificador, sus invariantes y errores, y la
  resolución de la institución en curso a través de puertos. Especificación en
  `openspec/changes/institution-root-and-multitenancy-baseline/specs/organization/spec.md`.

### Modificadas

- `build-integrity`: delta con `## ADDED Requirements` (encabezados `### Requisito:` en español,
  como la especificación canónica):
  - prohibición de primitivas de coma flotante para importes y de `BigDecimal.equals` fuera de
    `Money` (ADR-0004 §Cumplimiento 2);
  - cobertura global mínima de 80 % en el módulo `app`;
  - cobertura mínima de 95 % y puntuación de mutación mínima de 80 en el paquete `domain` de cada
    módulo de negocio, con la misma selección de perfil por rama que ya rige para `kernel`.
  No se modifica ningún requisito existente. La nota del escenario diferido W2 sigue siendo exacta.

## Enfoque

Dos ciclos rojo-verde separados para la parte de ArchUnit, como recomienda la exploración, porque el
invariante de conteo de `SuppressionCitesAdrTest` es frágil:

1. **Instrumental primero.** Cablear JaCoCo global y los umbrales del paquete `domain` en
   `app/pom.xml`, y PIT acotado a `organization.domain`, comprobando que cada umbral rompe la
   construcción.
2. **`InstitutionId` y `Institution` con TDD estricto**, de adentro hacia afuera: identificador,
   atributos con sus invariantes, activación y desactivación, catálogo de códigos.
3. **Ciclo A (rojo programado).** Al aparecer la primera clase en `organization.domain`, observar
   el rojo del inventario de ADR-0018, retirar la excepción, su ayudante y su entrada, y confirmar
   contra ArchUnit 1.4.2 el comportamiento con capas vacías antes de elegir el mecanismo (P2).
4. **Capa `application`**: puertos y caso de uso con dobles en memoria.
5. **Ciclo B (ADR-0004).** Cada regla nace con su prueba contra el fixture en rojo; después su mitad
   de producción en verde, sin excepción de conjunto vacío.
6. **Documentación**: `openspec/config.yaml`.

### Decisiones técnicas por defecto (con justificación)

1. **`InstitutionId` en `kernel`.** Todas las tablas y casi todos los casos de uso llevan el
   identificador de institución (ADR-0009). Si viviera en `organization.domain`, ningún otro módulo
   podría usarlo sin violar la regla de frontera de dominio, y Spring Modulith trata los subpaquetes
   como internos. `docs/01-arquitectura.md` y `CLAUDE.md` ya ubican los identificadores en `kernel`.
   Alternativa: el paquete raíz `com.confia.organization`, que sí es API del módulo para Spring
   Modulith, pero obliga a todos los módulos a depender de `organization` solo por un tipo.
2. **Sin clases en `infrastructure` ni en `web`** (ver «Dentro de alcance», punto 4). ADR-0018
   descarta clases sin propósito de negocio en `src/main`.
3. **Reglas de ADR-0004 no vacías por construcción.** Las dos primeras seleccionan todo el código de
   producción, que ya no está vacío. Para la tercera, «tipo monetario» se define de modo que incluya
   `Money` y `Percentage` de `kernel` y toda clase con un campo de esos tipos; como las pruebas de
   arquitectura importan `com.confia` desde la ruta de clases, el conjunto incluye `kernel` (se
   confirma en diseño). Así ninguna regla nueva necesita excepción de conjunto vacío, que es además
   lo que exige ADR-0018 §1.4 una vez existe un módulo de negocio.
4. **Umbrales en `app/pom.xml` con ejecuciones de `jacoco-check` propias**, igual que el patrón de
   `kernel/pom.xml`; PIT reutiliza los perfiles `mutation-gate` y `mutation-report` del POM padre.
5. **Validación del RTN** según el formato vigente del SAR, confirmado en diseño contra una fuente
   primaria. Si no se puede verificar, el diseño lo declara y valida solo lo demostrable.

## Áreas afectadas

| Área | Impacto | Descripción |
|---|---|---|
| `apps/api/kernel/src/main/java/com/confia/kernel/` | Nueva | `InstitutionId` |
| `apps/api/kernel/src/test/java/com/confia/kernel/` | Nueva | Pruebas de `InstitutionId` |
| `apps/api/app/src/main/java/com/confia/organization/domain/` | Nueva | `Institution`, objetos de valor y errores de dominio |
| `apps/api/app/src/main/java/com/confia/organization/application/` | Nueva | Puertos y caso de uso de resolución de institución |
| `apps/api/app/src/test/java/com/confia/organization/` | Nueva | Pruebas unitarias del dominio y de la aplicación |
| `apps/api/app/src/test/java/com/confia/architecture/LayeredArchitectureTest.java` | Modificada | Se retira la excepción de ADR-0018 y su ayudante |
| `apps/api/app/src/test/java/com/confia/architecture/EmptyShouldExceptionInventoryTest.java` | Modificada | Se retira la entrada vencida |
| `apps/api/app/src/test/java/com/confia/architecture/SuppressionCitesAdrTest.java` | Posible | Solo si el mecanismo de P2 añade marcadores al catálogo |
| `apps/api/app/src/test/java/com/confia/architecture/` (nueva prueba y fixtures) | Nueva | Reglas de ADR-0004 §Cumplimiento 2 |
| `apps/api/app/pom.xml` | Modificada | JaCoCo global y de `domain`, PIT sobre `organization.domain` |
| `openspec/config.yaml` | Modificada | Comentario del umbral de cobertura |
| `openspec/changes/foundations-plan/exploration.md` | Modificada | Nota fechada de D1 (ya escrita) |
| `openspec/specs/organization/spec.md` | Nueva al archivar | Capacidad `organization` |
| `openspec/specs/build-integrity/spec.md` | Delta al archivar | Tres requisitos nuevos |

## Tamaño estimado y presupuesto de revisión

Pronóstico de líneas de autor (adiciones más eliminaciones), ya ajustado al alza porque las
estimaciones del cambio 2 resultaron aproximadamente 1,5 veces bajas:

| Bloque | Estimación |
|---|---|
| `InstitutionId` en `kernel` con pruebas | 60 a 110 |
| Producción de `organization.domain` (agregado, objetos de valor, errores) | 280 a 420 |
| Producción de `organization.application` | 60 a 110 |
| Pruebas de dominio y de aplicación | 450 a 650 |
| ArchUnit: rojo de ADR-0018, reglas de ADR-0004, fixtures e inventario | 200 a 320 |
| `app/pom.xml` (JaCoCo y PIT) y `openspec/config.yaml` | 100 a 190 |
| **Total de código** | **1 150 a 1 800** |
| Artefactos de OpenSpec | 800 a 1 200 adicionales |

**El cambio supera el presupuesto de 800 líneas por pull request** (`CLAUDE.md`,
`docs/15-flujo-de-trabajo-git.md` §3) incluso en el extremo bajo. La estrategia de entrega de la
sesión es `single-pr`, así que hace falta la decisión P1 antes de aplicar.

**Tareas:** entre 12 y 14, cerca del límite de 15 de `openspec/config.yaml`.

**Puntos de corte naturales, sin decidir:**

- **Corte A — puertas y reglas (unas 300 a 510 líneas):** reglas de ADR-0004 con sus fixtures y
  JaCoCo global de 80 % sobre `app`. Autónomo: no depende del módulo.
- **Corte B — raíz de institución (unas 650 a 1 000 líneas):** `InstitutionId`, `Institution`,
  errores, rojo de ADR-0018, umbrales de 95 % y PIT de `organization.domain`. En el extremo alto
  todavía supera 800; se subdividiría separando `InstitutionId` y los objetos de valor del agregado.
- **Corte C — aplicación (unas 150 a 290 líneas):** puertos y caso de uso.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Comportamiento de `allowEmptyShould` y de las capas vacías en la regla compuesta `layeredArchitecture()` no confirmado (ADR-0018, «Por confirmar»); ArchUnit podría rechazar capas vacías con independencia de esa llamada | Alta | Confirmarlo en diseño contra ArchUnit 1.4.2 antes de fijar el mecanismo; si exige apartarse de ADR-0018, se eleva a un ADR (P2), nunca se entierra en `design.md` |
| Desbalance del invariante de conteo de `SuppressionCitesAdrTest` al retirar la excepción | Media | Ciclos A y B separados; cada ciclo termina con `./mvnw verify` en verde |
| Honestidad documental: alguien cree que ADR-0017 ya se cumplió con este cambio | Media | Nota fechada en `foundations-plan/exploration.md` (ya escrita); `docs/09` se actualiza al archivar; el cambio 5 debe recoger la tabla en su propuesta |
| Puertos sin adaptador durante los cambios 5 a 7 | Media | Cada puerto nombra al cambio que lo implementa; el caso de uso no se registra como bean hasta entonces |
| `InstitutionId` en `kernel` amplía un módulo con umbrales de 95 % y mutación 80 | Baja | Es un objeto de valor pequeño con pruebas completas |
| Formato del RTN no verificable con fuente primaria | Media | Decisión técnica 5 |
| Entorno local: `JAVA_HOME` por defecto en JDK 21 y brecha PKIX de Windows | Alta | Fijar `JAVA_HOME` en `C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot` y `MAVEN_OPTS` con el almacén de confianza `Windows-ROOT`; la integración continua es la fuente de verdad. Nunca se baja un umbral ni se omite una prueba para pasar en local |
| El tamaño supera 800 líneas | Alta | Decisión P1 antes de aplicar |

## Plan de reversión

No hay base de datos, esquema, despliegue ni datos: este cambio no crea tablas. Revertir es cerrar el
pull request o revertir su commit de fusión en `main`, lo que retira el módulo, `InstitutionId`, las
reglas nuevas y las puertas a la vez. Al revertir, la excepción de ADR-0018 vuelve con su condición
original, que de nuevo se cumple porque desaparece el módulo, de modo que la construcción queda en
verde. Una puerta que bloquee por error no se desactiva con una bandera (ADR-0008): se revierte el
commit que la introdujo o se corrige con un ADR. La nota de D1 en `foundations-plan/exploration.md`
se revierte solo si el propietario revoca D1. El punto de no retorno práctico llega cuando el cambio 5
dependa de `InstitutionId` y de `InstitutionRepository`.

## Dependencias

- Cambio 1 (`maven-workspace-and-ci-skeleton`) y cambio 2 (`kernel-money-value-object`), archivados
  y fusionados: reglas de arquitectura, inventario de ADR-0018, `DomainException`, `CurrencyCode`,
  JaCoCo, PIT y sus perfiles.
- JDK 25 en local y en integración continua.
- El cambio 5 asume la tabla `organization_institution` (decisión D1).

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` termina en verde con JDK 25 en la integración continua.
- [ ] `LayeredArchitectureTest` evalúa clases reales de `organization` sin `allowEmptyShould(true)`,
      y el inventario de ADR-0018 ya no contiene la entrada vencida.
- [ ] Ninguna excepción de conjunto vacío nueva existe sin la aprobación de P2.
- [ ] Cada regla de ADR-0004 §Cumplimiento 2 rechaza su fixture negativo nombrando la clase
      infractora, y su mitad de producción pasa sin excepción.
- [ ] La cobertura global de `app` por debajo de 80 % rompe la construcción.
- [ ] La cobertura de `organization.domain` por debajo de 95 % rompe la construcción; su
      puntuación de mutación por debajo de 80 rompe en la rama principal y solo informa en las ramas
      de trabajo.
- [ ] Construir una `Institution` inválida lanza una subclase de `DomainException` con un código
      kebab-case del catálogo del módulo; todos los códigos cumplen el formato y no se repiten.
- [ ] El caso de uso de resolución rechaza una institución inexistente o inactiva con su error de
      dominio.
- [ ] `openspec/changes/foundations-plan/exploration.md` registra la nota fechada de D1.

## Resolución del propietario (2026-09-19)

- **D1 (conflicto de dependencias): opción 2.** Este cambio entrega el módulo `organization` sin
  tabla de base de datos. La tabla `organization_institution`, con la semántica de `institution_id`
  y seguridad a nivel de fila sobre su propia clave primaria (ADR-0009, ADR-0017) y prefijo según
  ADR-0015, se crea en la **primera migración del cambio 5**. La exigencia de ADR-0009 de tener
  `institution_id` «desde la primera migración» se sigue cumpliendo, porque esa primera migración es
  la del cambio 5. Como parte de los artefactos de este cambio se enmienda
  `openspec/changes/foundations-plan/exploration.md` con una nota fechada que asigna al cambio 5 la
  migración de `organization_institution` y su repositorio jOOQ.
- **D2 (alcance de entidades): solo `Institution`.** `AcademicYear`, `Modality`, `Grade`,
  `Section` y `BillingPeriod` permanecen en el entregable 1 de F1 (`docs/09`).
- **P3 (aprobación): aprobada**, incluida la ubicación de `InstitutionId` en `kernel`.
- **P1 (forma de entrega): opción B.** Pull requests encadenados dentro de este cambio SDD, con los
  cortes A (reglas de ArchUnit de ADR-0004 y puerta global del 80 %), B (`InstitutionId`,
  `Institution`, retirada de la excepción de ADR-0018 y puertas del dominio) y C (capa de aplicación).
  Encadenamiento según `docs/15-flujo-de-trabajo-git.md` §3: cada pull request apunta al anterior y
  se fusionan en orden (`delivery_strategy: auto-chain`, `chain_strategy: stacked-to-main`). Al
  cerrar cada pull request se mide el diff real; si supera 800 líneas, la aplicación se detiene y
  decide el propietario.
- **P2 (capas vacías): excepción por capa mediante ADR-0020, aceptado.** La sonda sobre ArchUnit
  1.4.2 confirmó que las capas `infrastructure` y `web` vacías hacen fallar la regla de capas y que
  `optionalLayer` lo resuelve sin relajar `domain` ni `application` (ver `design.md`, «Sonda de P2»).

## Decisiones que confirma el propietario al aprobar

El modo de ejecución es automático: estas decisiones quedan registradas en lugar de preguntarse en
conversación. El orquestador las presenta.

- **P1. Forma de entrega ante el exceso de tamaño.** El pronóstico (1 150 a 1 800 líneas de código)
  supera 800 con la estrategia `single-pr` de la sesión.
  - *Opción A:* `size:exception` para este cambio, como en el cambio 1. Un pull request; revisión
    pesada.
  - *Opción B:* pull requests encadenados dentro de este cambio SDD, con los cortes A, B y C de
    «Tamaño estimado». Exige cambiar la estrategia de entrega de la sesión.
  - La propuesta no elige.
- **P2. Capas `infrastructure` y `web` vacías en el primer módulo.** Si ArchUnit rechaza la regla
  de capas porque esas capas no tienen clases, hace falta una excepción que ADR-0018 §1.4 no ampara
  de forma literal (la vacuidad ya no proviene de la ausencia de módulos de negocio).
  - *Recomendación:* excepción por capa (`optionalLayer` o el mecanismo que confirme el diseño), con
    condición de caducidad «el módulo `organization` todavía no tiene clases en `infrastructure` /
    `web`», entrada en el inventario, marcador nuevo en el catálogo del escáner y un ADR breve que
    amplíe ADR-0018 para este caso. Vence con el cambio 5 (`infrastructure`) y con el primer
    controlador de negocio (`web`).
  - *Alternativa:* añadir en `src/main` clases de `infrastructure` o `web` sin caso de uso real, que
    ADR-0018 (opción C) descarta.
  - Si el diseño confirma que ArchUnit acepta capas vacías sin excepción, P2 desaparece.
- **P3. Aprobación de la propuesta completa** antes de especificar, diseñar y planificar tareas
  (`openspec/config.yaml`, `rules.proposal`), incluida la ubicación de `InstitutionId` en `kernel`
  (decisión técnica 1), que amplía el alcance del núcleo.

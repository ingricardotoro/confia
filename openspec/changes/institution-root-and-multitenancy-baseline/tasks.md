# Tareas: raíz de institución y base multi-institución

## Review Workload Forecast

| Campo | Valor |
|---|---|
| Presupuesto de revisión de esta sesión (guardia literal de la fase) | 400 líneas |
| Presupuesto de revisión vigente para este cambio (decisión del propietario, P1) | **800 líneas** de cambio efectivo por pull request (`CLAUDE.md`, `docs/15-flujo-de-trabajo-git.md` §3) — prevalece sobre el valor de sesión; no se re-decide aquí |
| Líneas de autor estimadas (código, sin `openspec/` ni `docs/adr/`) | 1 127 a 1 669, según `design.md` §«Pronóstico de tamaño por corte» |
| Riesgo frente al presupuesto de 400 (guardia literal de la fase) | **High** — cualquier corte individual supera 400 |
| Riesgo frente al presupuesto de 800 vigente (P1) | **Low a Medium** — con la subdivisión B1/B2 ya planificada, cada pull request cae dentro de 800 salvo el extremo alto de B1 (hasta 700, dentro) y de B2 (hasta 345, dentro); el corte A (232–334) y C (185–290) caben con holgura. El riesgo real se confirma con la tarea de medición de cada PR |
| Pull requests encadenados recomendados | Sí — ya decidido por el propietario (P1, opción B) |
| División sugerida | PR A (`change/institution-root-and-multitenancy-baseline`, base `main`) → PR B1 (`...-domain`, base PR A) → PR B2 (`...-domain-attributes`, base PR B1) → PR C (`...-application`, base PR B2) |
| Estrategia de entrega | `auto-chain` |
| Estrategia de cadena | `stacked-to-main` |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Nota sobre el excedente frente al presupuesto de sesión (ya resuelta, no bloquea la aplicación):**
la política de la sesión SDD registra 400 líneas; el proyecto fija 800 para este cambio
(`CLAUDE.md`, `docs/15` §3, decisión P1 del propietario). Se sigue el valor de 800, como instruye el
lanzamiento de esta fase. Si al cerrar un PR el diff real supera 800, la tarea de medición de ese PR
(1.6, 2.9, 3.9 o 4.3) detiene la aplicación y consulta al propietario, con los puntos de subdivisión
que ya nombra `design.md` («Pronóstico de tamaño por corte»). Esto ya está decidido como
procedimiento; no es una decisión pendiente antes de iniciar `sdd-apply`.

### Nota sobre el límite de quince tareas por pull request

Esta lista tiene **31 tareas en total**, repartidas en cuatro pull requests (7, 10, 10 y 4). **El
propietario del producto aceptó esta excepción el 2026-09-19, con un máximo de quince tareas por
pull request.** El
lanzamiento de esta fase fija el límite en **quince tareas por pull request**, no por cambio SDD
completo; cada uno de los cuatro pull requests queda muy por debajo de ese límite. Se deja constancia
de esta interpretación (igual que en el precedente `kernel-money-value-object`, que superó quince
tareas en total con dos pull requests, cada uno dentro del límite) por si el propietario prefiere una
lectura distinta de `openspec/config.yaml` («Un cambio con más de quince tareas es demasiado grande y
debe dividirse»); no se decide aquí, se informa.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| A | Puertas y reglas de ADR-0004 §Cumplimiento 2, sin módulo de negocio; JaCoCo `BUNDLE` de 80 % en `app` si la medición lo permite | PR A (`change/institution-root-and-multitenancy-baseline`) | `./mvnw -pl apps/api/app -am test -Dtest=MonetaryFloatingPointTest` | `./mvnw -B verify` en `apps/api`, JDK 25 | Revertir el pull request completo; `apps/api/app/pom.xml` y `openspec/config.yaml` vuelven a su estado del cambio 3; ningún otro módulo depende de este PR |
| B1 | `InstitutionId`, `Institution` mínima (`id`, `legalName`, `tradeName`, `isActive`), sus errores, ciclo A completo de ADR-0018/ADR-0020, puertas del `domain` | PR B1 (`...-domain`) | `./mvnw -pl apps/api/app -am test -Dtest=InstitutionCreationTest,InstitutionLifecycleTest,OrganizationErrorCodesTest` | `./mvnw -B verify -Pmutation-report` en `apps/api` | Revertir el PR B1; el módulo `organization` desaparece, la excepción de ADR-0018 vuelve con su condición original (que se cumple de nuevo), PR A queda intacto y completo por sí solo |
| B2 | Atributos restantes del agregado (`rtn`, `address`, `defaultCurrency`, `locale`, `timezone`) con sus invariantes, precedencia completa, catálogo a once códigos | PR B2 (`...-domain-attributes`) | `./mvnw -pl apps/api/app -am test -Dtest=InstitutionCreationTest,InstitutionLifecycleTest,OrganizationErrorCodesTest` | `./mvnw -B verify -Pmutation-report` en `apps/api` | Revertir el PR B2 sin fusionar; PR B1 queda intacto y completo por sí solo (institución sin RTN/dirección/moneda/localización/huso horario, sin consumidores fuera del módulo) |
| C | Puertos `InstitutionRepository` y `CurrentInstitutionProvider`, caso de uso `ResolveCurrentInstitution`, dos errores de resolución, catálogo a trece códigos | PR C (`...-application`) | `./mvnw -pl apps/api/app -am test -Dtest=ResolveCurrentInstitutionTest,OrganizationErrorCodesTest` | `./mvnw -B verify -Pmutation-report` en `apps/api` | Revertir el PR C; el módulo `organization` queda sin capa `application`, sin afectar `domain`; ningún bean de Spring se registra en ningún PR |

Ejecutor de todas las tareas: `./mvnw -B verify` en `apps/api`, con `JAVA_HOME` en
`C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot` (JDK 25) y
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (`design.md`, «Restricciones del entorno
local»). La integración continua en `ubuntu-latest` es la fuente de verdad; nunca se baja un umbral
ni se omite una prueba para pasar en local.

**Estado ya resuelto, no se re-planifica:** la sonda de P2 (paso B0 del diseño) ya se ejecutó el
2026-09-19 con resultado (b) — la regla de capas falla con `Infrastructure` y `Web` vacías, y
`optionalLayer` lo resuelve sin relajar `Domain` ni `Application` — y el propietario aceptó ADR-0020
el mismo día (estado Aceptado, `docs/adr/ADR-0020-capas-opcionales-en-la-regla-de-capas.md`, ya con su
fila en `docs/adr/README.md`). Las tareas de este documento aplican directamente la opción A de
ADR-0020; ninguna tarea repite la sonda.

---

## PR A — Puertas y reglas de ADR-0004, sin módulo de negocio

Rama `change/institution-root-and-multitenancy-baseline` (rama actual), base `main`. Compila y pasa
`./mvnw verify` con las reglas nuevas en verde sobre el código de producción existente
(`com.confia.bootstrap` y `kernel`).

- [x] 1.1 **Medición de cobertura de `app` con solo `bootstrap`.** Declarar `jacoco-maven-plugin` en
  `apps/api/app/pom.xml` con las ejecuciones `prepare-agent` y `report` (heredadas del padre) pero
  **sin** ejecución `check` todavía. Ejecutar `./mvnw -B -pl apps/api/app -am verify` y leer el
  informe HTML/XML de JaCoCo de `app` (cobertura de líneas y de ramas sobre `com.confia.bootstrap`,
  sin ningún módulo de negocio). Registrar el porcentaje exacto como evidencia: decide si la regla
  `BUNDLE` de 80 % entra en la tarea 1.5 de este PR o se traslada a la tarea 2.7 de PR B1
  (`design.md`, decisión 9, «Por corte»; «Por confirmar», punto 5). Sin RED/GREEN (medición, no
  comportamiento de producción). — Capacidad `build-integrity`, requisito «Cobertura global mínima
  del módulo `app`» (precondición de la decisión de corte)
  - **Evidencia (2026-09-19):** `./mvnw -B -pl app -am verify` en verde (31 pruebas, 0 fallos).
    Informe JaCoCo de `app` (7 clases de `com.confia.bootstrap`): líneas 35/39 = **89.7 %**, ramas
    11/13 = **84.6 %**, instrucciones 157/169 = 92.9 %. Ambos porcentajes superan 80 %, así que la
    regla `BUNDLE` **entra en este PR** (tarea 1.5), no se traslada a PR B1.

- [x] 1.2 **TDD — regla 1, `NO_BIG_DECIMAL_FROM_FLOATING_POINT`.** ROJO: crear
  `apps/api/app/src/test/java/com/confia/architecture/MonetaryFloatingPointTest.java` con la regla
  que prohíbe `new BigDecimal(double)`, `new BigDecimal(double, MathContext)` y
  `BigDecimal.valueOf(double)` en `productionClasses()`, y el fixture
  `apps/api/app/src/test/java/com/confia/architecture/fixture/monetary/FloatingPointBigDecimal.java`
  con las tres llamadas prohibidas en métodos distintos; la prueba de rechazo del fixture falla al
  no existir aún la regla. VERDE: la regla pasa sobre el código de producción real (ya no vacío:
  incluye `com.confia.bootstrap` y `kernel`) y rechaza el fixture nombrando la clase infractora.
  Neutralizar el fixture (quitar una de las tres llamadas) y observar que la prueba de rechazo falla,
  como prueba de no vacuidad (lección de Engram #354); revertir la neutralización sin comprometerla.
  REFACTOR: ninguno esperado. — Capacidad `build-integrity`, requisito «Prohibición de coma flotante
  para importes y de igualdad cruda de `BigDecimal` fuera de `Money`» (escenario «Fixture que
  construye `BigDecimal` desde `double`» y «Código de producción sin infracciones»)
  - **Evidencia (2026-09-19):** ROJO observado: `cannot find symbol NO_BIG_DECIMAL_FROM_FLOATING_POINT`
    al compilar `MonetaryFloatingPointTest` (`./mvnw -B -pl app -am test -Dtest=MonetaryFloatingPointTest
    -Dsurefire.failIfNoSpecifiedTests=false`). VERDE: 2/2 pruebas en verde tras añadir la regla.
    No vacuidad: se comentaron las tres llamadas del fixture y `rejectsTheFixtureFloatingPointBigDecimalConstruction`
    falló (1 fallo de 2 pruebas); se revirtió la neutralización y las 2 pruebas volvieron a verde.

- [x] 1.3 **TDD — regla 2, `NO_BIG_DECIMAL_EQUALS_OUTSIDE_MONEY`.** ROJO: extender
  `MonetaryFloatingPointTest` con la regla que prohíbe `BigDecimal.equals(Object)` en clases que no
  pertenecen a `Money` (ni a sus anidadas), y crear el fixture
  `.../fixture/monetary/RawBigDecimalComparison.java` con `left.equals(right)` sobre `BigDecimal`
  como tipo estático. VERDE: la regla rechaza el fixture nombrando la clase infractora y no rechaza
  a `Money`, que queda fuera de la selección (confirmar con una aserción explícita o con la ejecución
  de la prueba). Neutralizar el fixture y observar el fallo de la prueba de rechazo; revertir.
  REFACTOR: ninguno esperado. — Capacidad `build-integrity`, mismo requisito (escenario «Fixture que
  invoca `BigDecimal.equals` fuera de `Money`»)
  - **Evidencia (2026-09-19):** ROJO observado: `cannot find symbol NO_BIG_DECIMAL_EQUALS_OUTSIDE_MONEY`
    y `cannot find symbol NOT_MONEY`/`Money` al compilar. VERDE: 5/5 pruebas, incluida la aserción
    explícita `NOT_MONEY.test(productionClasses().get(Money.class))` es `false`. No vacuidad: se
    cambió `left.equals(right)` por `left.compareTo(right) == 0` en el fixture;
    `rejectsTheFixtureRawBigDecimalComparison` falló sola (1 de 5); se revirtió y las 5 volvieron a
    verde.

- [x] 1.4 **TDD — reglas 3, 4 y 5, campos/retornos/parámetros de coma flotante en tipos
  monetarios.** ROJO: extender `MonetaryFloatingPointTest` con el predicado `MONETARY_TYPE` (`Money`,
  `Percentage`, o toda clase con un campo de esos tipos) y las tres reglas que prohíben `double`,
  `float`, `Double` y `Float` como campo declarado, como retorno y como parámetro (con la condición
  propia de la regla de parámetros) en un tipo monetario; escribir además
  `productionImportIncludesTheKernelMonetaryTypes`, que afirma que `productionClasses()` contiene
  `Money` y `Percentage`. Crear el fixture `.../fixture/monetary/FloatingPointPriceTag.java` con
  campo `Money price` (lo hace monetario), campo `double discountRate`, método `double
  discountRate()` y método `void applyRate(float rate)`. VERDE: las tres reglas rechazan el fixture
  nombrando la clase infractora; la regla de `Money.multiply(long)` (que llama internamente a
  `BigDecimal.valueOf(long)`, no `valueOf(double)`) sigue pasando sin excepción, confirmando que la
  regla 1 distingue las sobrecargas por firma exacta. Neutralizar cada miembro infractor del fixture
  uno a uno y observar el fallo correspondiente; revertir. REFACTOR: ninguno esperado. — Capacidad
  `build-integrity`, mismo requisito (escenario «Fixture con un campo `double` en un tipo monetario»
  y «Código de producción sin infracciones»)
  - **Evidencia (2026-09-19):** ROJO observado: `cannot find symbol` para las tres reglas nuevas (6
    errores de compilación). VERDE: 12/12 pruebas, incluida `productionImportIncludesTheKernelMonetaryTypes`
    (confirma `Money` y `Percentage` en `productionClasses()`) y la reconfirmación de que
    `productionCodeNeverConstructsBigDecimalFromFloatingPoint` (tarea 1.2) sigue en verde con
    `Money.multiply(long)` en producción, probando que la regla 1 distingue las sobrecargas. No
    vacuidad, uno a uno: (a) campo neutralizado → solo `rejectsTheFixtureFloatingPointField` falla
    (1/12); (b) retorno neutralizado → solo `rejectsTheFixtureFloatingPointReturn` falla (1/12); (c)
    parámetro neutralizado → solo `rejectsTheFixtureFloatingPointParameter` falla (1/12). Las tres
    neutralizaciones se revirtieron; 12/12 en verde al final.

- [x] 1.5 **JaCoCo `BUNDLE` de 80 % en `app`, condicionado al resultado de la tarea 1.1.** Si la
  medición de 1.1 alcanza 80 % de líneas y de ramas: añadir a `apps/api/app/pom.xml` la ejecución
  `jacoco-check` con la regla `BUNDLE` (`LINE` y `BRANCH`, `COVEREDRATIO` mínimo `0.80`), y en el
  mismo commit actualizar el comentario de `coverage_threshold` en `openspec/config.yaml` a
  `95 # kernel and every module's domain package, line+branch (JaCoCo); 80 global on app`. Demostrar
  el fallo bajando temporalmente el umbral configurado a un valor por encima de la cobertura real (o
  comentando una prueba de `bootstrap` si existe) y observando que `jacoco:check` rompe `./mvnw
  verify`; revertir sin comprometer. Si la medición de 1.1 **no** alcanza 80 % (previsible en la
  cobertura de ramas, `design.md` «Por confirmar» punto 5): no declarar esta regla aquí; dejar
  constancia explícita en este PR de que se traslada a la tarea 2.7 de PR B1, sin bajar el umbral ni
  excluir `ConfiaApplication.main`. — Capacidad `build-integrity`, requisito «Cobertura global mínima
  del módulo `app`» (ambos escenarios)
  - **Evidencia (2026-09-19):** la medición de 1.1 alcanzó 80 %, así que se declaró la regla
    `BUNDLE` (`LINE`/`BRANCH`, mínimo 0.80) en `apps/api/app/pom.xml` y se actualizó el comentario
    de `coverage_threshold` en `openspec/config.yaml` en el mismo commit. `./mvnw -B -pl app -am
    verify` en verde. Demostración del fallo: se subió temporalmente el mínimo a 0.95 y `verify`
    rompió con `Rule violated for bundle confia-api: lines covered ratio is 0.89, but expected
    minimum is 0.95` y el mismo mensaje para `branches`; se revirtió a 0.80 y `verify` volvió a
    BUILD SUCCESS.

- [x] 1.6 **Medir el diff real de PR A** con
  `git diff --numstat main...change/institution-root-and-multitenancy-baseline -- . ':(exclude)openspec' ':(exclude)docs/adr'`.
  Si el total cabe en 800 líneas, continuar. **Si supera 800, detener la aplicación y consultar al
  propietario**, con el corte A como unidad ya mínima (no tiene subdivisión natural adicional
  planificada); registrar la decisión que tome. — P1 de la propuesta; `design.md`, «Pronóstico de
  tamaño por corte»
  - **Evidencia (2026-09-19):** `git diff --numstat main...change/institution-root-and-multitenancy-baseline
    -- . ':(exclude)openspec' ':(exclude)docs/adr'` → `apps/api/app/pom.xml` 45+/3-;
    `MonetaryFloatingPointTest.java` 183+/0-; `FloatingPointBigDecimal.java` 32+/0-;
    `FloatingPointPriceTag.java` 29+/0-; `RawBigDecimalComparison.java` 21+/0-. **Total: 310
    adiciones + 3 eliminaciones = 313 líneas de autor**, dentro del pronóstico de `design.md`
    (232–334) y muy por debajo de 800. Se continúa sin consultar al propietario.

- [x] 1.7 **Verificación final de PR A**: en checkout limpio, con `JAVA_HOME` en
  `C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot` y `MAVEN_OPTS` con el almacén de confianza
  `Windows-ROOT`, ejecutar `./mvnw -B verify` en `apps/api`. Confirmar que las tres reglas de
  ADR-0004 §Cumplimiento 2 pasan sobre producción y rechazan sus tres fixtures, y (si se aplicó 1.5)
  que la cobertura de `app` ≥ 80 % rompe la construcción por debajo del umbral. Empujar la rama
  `change/institution-root-and-multitenancy-baseline` y confirmar en la integración continua que el
  trabajo `backend` termina en verde. — Capacidad `build-integrity`; criterios de éxito de la
  propuesta («Cada regla de ADR-0004 §Cumplimiento 2 rechaza su fixture negativo...»)
  - **Evidencia (2026-09-19):** árbol de trabajo limpio (`git status` sin cambios) en
    `change/institution-root-and-multitenancy-baseline`, commit `ea1ec76`. `./mvnw -B verify` en
    `apps/api` con `JAVA_HOME`/`MAVEN_OPTS` fijados: **BUILD SUCCESS**, 172 pruebas en `kernel` + 43
    en `app` (incluidas las 12 de `MonetaryFloatingPointTest`), sin `Rule violated` de JaCoCo (la
    puerta `BUNDLE` de 80 % pasó en silencio). Las tres reglas de ADR-0004 §Cumplimiento 2 pasan
    sobre producción real y rechazan sus tres fixtures (tareas 1.2–1.4). **Pendiente fuera de mi
    alcance en esta ejecución:** empujar la rama y confirmar el trabajo `backend` en la integración
    continua — el lanzamiento de esta fase instruye explícitamente no empujar ni abrir PR (lo hace
    el orquestador); queda como siguiente paso del orquestador antes de dar PR A por cerrado en CI.

---

## PR B1 — `InstitutionId`, `Institution` mínima, ciclo de ADR-0018/ADR-0020, puertas del `domain`

Rama `...-domain` (por ejemplo `change/institution-root-and-multitenancy-baseline-domain`), base PR
A. El nombre debe empezar por `change/` para que `ci.yml` lo ejecute mientras apunta al PR A
(`-Pmutation-report`).

- [x] 2.1 **TDD — `InstitutionId`.** ROJO: crear
  `apps/api/kernel/src/test/java/com/confia/kernel/InstitutionIdTest.java`: construcción desde un
  `UUID` válido expone ese mismo `UUID`; un valor nulo lanza `NullPointerException`; dos instancias
  con el mismo `UUID` son iguales y su `hashCode` coincide; dos instancias con `UUID` distinto no son
  iguales. VERDE: crear `apps/api/kernel/src/main/java/com/confia/kernel/InstitutionId.java` como
  `public record InstitutionId(UUID value)` con constructor compacto que aplica
  `Objects.requireNonNull(value, "value")`; actualizar
  `apps/api/kernel/src/main/java/com/confia/kernel/package-info.java` para mencionar los
  identificadores entre los tipos del núcleo. Sin código nuevo en `KernelErrorCodesTest` (el nulo es
  un error de programación, ADR-0019 punto 6). REFACTOR: ninguno esperado. — Especificación
  `organization`, requisito «Identificador de institución en el núcleo (`InstitutionId`)» (los tres
  escenarios)
  - **Evidencia (2026-09-19):** ROJO observado: `cannot find symbol class InstitutionId` (10 errores
    de compilación) al ejecutar `./mvnw -B -pl kernel -am test -Dtest=InstitutionIdTest
    -Dsurefire.failIfNoSpecifiedTests=false`. VERDE: `InstitutionId` como `record` con constructor
    compacto (`Objects.requireNonNull`); 4/4 pruebas en verde (construcción, nulo, igualdad/hashCode,
    desigualdad). `package-info.java` actualizado para nombrar `InstitutionId` explícitamente entre
    los tipos del núcleo. Sin cambios en `KernelErrorCodesTest` (el nulo es error de programación).

- [x] 2.2 **TDD — `Institution` mínima (caso feliz + `legalName`).** ROJO: crear
  `apps/api/app/src/main/java/com/confia/organization/package-info.java` (documenta la capacidad
  `organization`, qué queda fuera de alcance y qué cambio lo aporta) y
  `apps/api/app/src/test/java/com/confia/organization/domain/InstitutionCreationTest.java` con: caso
  feliz de construcción con `id`, `legalName` y `tradeName` válidos, `isActive` verdadero; rechazo de
  `legalName` en blanco (`institution-legal-name-blank`) y de más de 200 puntos de código
  (`institution-legal-name-too-long`, con los límites exactos 200/201); `id` nulo lanza
  `NullPointerException`; `legalName` nulo lanza `NullPointerException`. VERDE: crear
  `apps/api/app/src/main/java/com/confia/organization/domain/Institution.java` (`public final class
  Institution`, constructor privado, fábrica `create(InstitutionId id, String legalName, String
  tradeName)` que exige `strip()` + no vacío + máximo 200 puntos de código para `legalName`,
  `isActive` verdadero al crear, `MAX_NAME_LENGTH = 200`) y
  `apps/api/app/src/main/java/com/confia/organization/domain/InvalidInstitutionException.java`
  (`final`, hereda de `DomainException`, fábricas de paquete `legalNameBlank()` y
  `legalNameTooLong()` con los códigos `institution-legal-name-blank` e
  `institution-legal-name-too-long`, mensajes que nunca repiten la entrada). Ejecutar `./mvnw -B
  verify` completo en `apps/api`: **observar el rojo programado** de
  `EmptyShouldExceptionInventoryTest` (ADR-0018 §2) al aparecer la primera clase en
  `organization.domain`; registrar el mensaje exacto como evidencia, sin corregirlo todavía.
  REFACTOR: ninguno esperado. — Especificación `organization`, requisito «Construcción de
  `Institution` y sus atributos de identidad obligatorios» (escenarios de `legalName` y de atributos
  obligatorios nulos, parcial: sin `address` todavía); ADR-0018 §2 (rojo programado, ahora observado)
  - **Evidencia (2026-09-19):** ROJO observado: `cannot find symbol class Institution` /
    `InvalidInstitutionException` (compilación) al ejecutar `./mvnw -B -pl app -am test
    -Dtest=InstitutionCreationTest -Dsurefire.failIfNoSpecifiedTests=false`. VERDE: 11/11 pruebas.
    **Desviación registrada:** además de `legalName`, esta tarea implementa ya la validación
    completa de `tradeName` (blanco y máximo 200 puntos de código,
    `institution-trade-name-blank`/`institution-trade-name-too-long`), adelantando parte del
    requisito «Nombre comercial opcional» (nominalmente de la tarea 3.6 en PR B2). Necesario para
    que el catálogo de seis códigos de la tarea 2.6 (en este mismo PR B1) tenga las cuatro fábricas
    de `InvalidInstitutionException` realmente alcanzables desde producción, no fábricas muertas sin
    invocar — relevante para las puertas de cobertura 95 %/mutación 80 de la tarea 2.7, también en
    este PR. La tarea 3.6 en PR B2 encontrará ambas fábricas ya completas y solo confirmará el
    comportamiento. `./mvnw -B verify` completo: **rojo programado observado**, mensaje exacto de
    `EmptyShouldExceptionInventoryTest.everyExceptionsConditionStillHolds`: "`LayeredArchitectureTest.
    productionCodeRespectsLayeringYet's ADR-0018 exception no longer holds (no class in
    apps/api/app production code resides in a domain, application, infrastructure or web package
    yet, because no business module exists (change 4 introduces the first one)). Remove
    allowEmptyShould(true) from that rule and this inventory entry, or update the condition and
    cite the ADR that extends it (ADR-0018, section 2).`" `LayeredArchitectureTest` sigue en verde
    (todavía conserva `allowEmptyShould(true)`); se cierra en la tarea 2.3. Sin comprometer el rojo.

- [x] 2.3 **Cierre de la caducidad de ADR-0018 (parte común).** En
  `apps/api/app/src/test/java/com/confia/architecture/LayeredArchitectureTest.java`: borrar
  `.allowEmptyShould(true)` y el comentario que la acompaña; renombrar la prueba a
  `productionCodeRespectsLayering`; borrar `LAYER_SEGMENTS` y `noBusinessModuleExistsYet` con su
  Javadoc y los imports que queden sin uso (`JavaClass`, `JavaClasses`, `Arrays`, `Set`, según
  aplique). En
  `apps/api/app/src/test/java/com/confia/architecture/EmptyShouldExceptionInventoryTest.java`: borrar
  la única entrada existente; `EXCEPTIONS` queda `List.of()`. Ejecutar `./mvnw -B -pl apps/api/app
  -am test -Dtest=EmptyShouldExceptionInventoryTest,SuppressionCitesAdrTest`: el inventario pasa
  (lista vacía) y `SuppressionCitesAdrTest` pasa con su conteo en 0 == 0 (sin cambios en ese
  archivo). Ejecutar `productionCodeRespectsLayering` de forma aislada y **registrar si falla**
  nombrando `Layer 'Infrastructure' is empty` y `Layer 'Web' is empty`, confirmando en código el
  resultado (b) ya observado en la sonda de `design.md`. — ADR-0018 §2 y §3.a (caducidad cerrada);
  criterios de éxito de la propuesta («`LayeredArchitectureTest` evalúa clases reales... sin
  `allowEmptyShould(true)`, y el inventario ya no contiene la entrada vencida»)
  - **Evidencia (2026-09-19):** `EmptyShouldExceptionInventoryTest` (lista vacía) y
    `SuppressionCitesAdrTest` (0 == 0 llamadas `allowEmptyShould(`) pasan:
    `./mvnw -B -pl app -am test -Dtest=EmptyShouldExceptionInventoryTest,SuppressionCitesAdrTest
    -Dsurefire.failIfNoSpecifiedTests=false` → 9/9. `productionCodeRespectsLayering` ejecutada de
    forma aislada (`-Dtest=LayeredArchitectureTest#productionCodeRespectsLayering`) **falla**,
    confirmando el resultado (b) de la sonda: nombra `Layer 'Application' is empty`, `Layer
    'Infrastructure' is empty` y `Layer 'Web' is empty`. **Hallazgo no cubierto por la sonda de
    design.md, registrado para la tarea 2.4:** la sonda de P2 solo probó `optionalLayer` con una
    muestra donde `domain` **y** `application` ya tenían clases; en el estado real de este PR,
    `application` sigue vacía (sus puertos llegan en PR C, tarea 4.1) y ADR-0020 declara `Application`
    siempre obligatoria (§2 de su decisión). Aplicar `optionalLayer` solo a `Infrastructure` y `Web`
    en la tarea 2.4 no cerraría este rojo mientras `Application` siga vacía y obligatoria; se verifica
    en la tarea 2.4 antes de comprometer ese cambio.

- [x] 2.3b **Puertos de la capa `application` (adelantados desde la tarea 4.1, decisión del
  propietario del 2026-09-19).** Crear
  `apps/api/app/src/main/java/com/confia/organization/application/InstitutionRepository.java`
  (`Optional<Institution> findById(InstitutionId id)`) y
  `.../CurrentInstitutionProvider.java` (`InstitutionId currentInstitutionId()`, con el Javadoc que
  fija el contrato de ADR-0009: el valor se deriva del token autenticado y nunca de un parámetro,
  cabecera o cuerpo del cliente). Son interfaces sin implementación en este cambio: el adaptador de
  persistencia llega con el cambio 5 y el de seguridad con el cambio 7. Sin anotaciones de Spring.
  **Motivo del adelanto:** ADR-0020 §2 mantiene `Application` como capa siempre obligatoria, de modo
  que dejarla vacía hasta el PR C haría fallar `productionCodeRespectsLayering` en los PR B1 y B2.
  Con los puertos aquí, la capa deja de estar vacía por una razón real y no hace falta ampliar
  ninguna excepción. Sin ciclo ROJO/VERDE propio: son interfaces sin comportamiento; su prueba llega
  con el caso de uso en la tarea 4.1. — Especificación `organization`, requisitos «Puerto de salida
  para cargar una institución por identificador» y «Puerto de salida para la institución de la
  solicitud en curso»; ADR-0009; ADR-0020 §2
  - **Evidencia (2026-09-19):** sin ciclo ROJO/VERDE propio, como indica el motivo del adelanto: son
    interfaces sin comportamiento (`InstitutionRepository.findById(InstitutionId)` devuelve
    `Optional<Institution>`; `CurrentInstitutionProvider.currentInstitutionId()` devuelve
    `InstitutionId`, con el Javadoc del contrato de ADR-0009 citando textualmente
    «Implementación del aislamiento»). Compilación en verde:
    `./mvnw -B -pl app -am test -Dtest=InstitutionCreationTest -Dsurefire.failIfNoSpecifiedTests=false`
    → 11/11 (sin regresión). **Comprobación del motivo del adelanto:**
    `productionCodeRespectsLayering` ejecutada de forma aislada ya no nombra `Application` como capa
    vacía, solo `Layer 'Infrastructure' is empty` y `Layer 'Web' is empty` — confirma en código que
    los dos puertos bastan para que `application` deje de estar vacía, antes de que la tarea 2.4
    aplique `optionalLayer` a las otras dos.

- [x] 2.4 **Aplicación de ADR-0020 (opción A, ya aceptada): capas opcionales por marcador.** ROJO:
  en `apps/api/app/src/test/java/com/confia/architecture/SuppressionCitesAdrTest.java`, añadir el
  patrón `OPTIONAL_LAYER_CALL` (`\.optionalLayer\(`) y `WITH_OPTIONAL_LAYERS_TRUE`
  (`withOptionalLayers\(\s*true\s*\)`) a la lista de marcadores con nombre, con sus ejemplos y la
  aserción de que ninguno aparece todavía (`0 == 0`); ejecutar la prueba y confirmar que pasa antes
  de tocar `LayeredArchitectureTest` (los patrones existen pero no se usan aún). En
  `EmptyShouldExceptionInventoryTest`, añadir el `enum Marker { ALLOW_EMPTY_SHOULD, OPTIONAL_LAYER }`,
  el campo `marker` en `ExpiringException`, `countOf(Marker)`, y las dos entradas nuevas
  (`Infrastructure` con condición «ninguna clase de producción reside en un paquete `infrastructure`»,
  `Web` con condición «ninguna clase de producción reside en un paquete `web`»), cada una apuntando a
  un método `noProductionClassInLayer(JavaClasses, String)` que todavía no existe en
  `LayeredArchitectureTest`: la compilación falla (rojo). VERDE: en `LayeredArchitectureTest`, separar
  la regla en `productionLayeringRule()` (con `optionalLayer("Infrastructure")` y
  `optionalLayer("Web")`, cada llamada con el comentario que cita ADR-0020 y la condición de
  caducidad) y `fixtureLayeringRule()` (las cuatro capas obligatorias, sin cambios de comportamiento
  frente al fixture existente), con el método común `constrained(LayeredArchitecture)` que conserva
  las ocho cláusulas `whereLayer` y el `because()` existentes; añadir el método de paquete
  `static boolean noProductionClassInLayer(JavaClasses classes, String layerSegment)`. Ejecutar
  `./mvnw -B -pl apps/api/app -am test -Dtest=LayeredArchitectureTest,EmptyShouldExceptionInventoryTest,SuppressionCitesAdrTest`:
  las tres pasan; `SuppressionCitesAdrTest` ahora exige 2 == 2 apariciones de `.optionalLayer(`.
  **Demostración de caducidad** (sin comprometer): añadir temporalmente una clase mínima en un
  paquete `com.confia.organization.infrastructure` de prueba, ejecutar
  `EmptyShouldExceptionInventoryTest` y observar que falla nombrando la entrada de `Infrastructure`;
  borrar la clase temporal (`git status` limpio) antes de continuar. REFACTOR: ninguno esperado. —
  ADR-0020 (opción A completa); `design.md`, «Ediciones exactas de las pruebas de arquitectura»
  (parte «Solo si se aplica ADR-0020»)
  - **Evidencia (2026-09-19):** paso 1 (patrones sin usar): tras añadir `OPTIONAL_LAYER_CALL` y
    `WITH_OPTIONAL_LAYERS_TRUE` a `SuppressionCitesAdrTest` (con el ejemplo
    `.optionalLayer("Web")` y la prueba dedicada de que `WITH_OPTIONAL_LAYERS_TRUE` distingue
    `true` de `false`), `./mvnw -B -pl app -am test -Dtest=SuppressionCitesAdrTest
    -Dsurefire.failIfNoSpecifiedTests=false` → 10/10 en verde (`0 == 0` apariciones de
    `.optionalLayer(`). ROJO observado: al añadir a `EmptyShouldExceptionInventoryTest` el `enum
    Marker`, `countOf(Marker)` y las dos entradas `OPTIONAL_LAYER` apuntando a
    `LayeredArchitectureTest.noProductionClassInLayer(...)`, la compilación falla exactamente como
    predijo la tarea: `cannot find symbol / method
    noProductionClassInLayer(com.tngtech.archunit.core.domain.JavaClasses,java.lang.String) /
    location: class com.confia.architecture.LayeredArchitectureTest` (dos apariciones, una por
    entrada). VERDE: tras separar `LayeredArchitectureTest` en `productionLayeringRule()`
    (`optionalLayer("Infrastructure")`/`optionalLayer("Web")`, cada una con el comentario que cita
    ADR-0020), `fixtureLayeringRule()` (cuatro capas obligatorias, sin cambios de comportamiento) y
    `constrained(LayeredArchitecture)` con las ocho cláusulas `whereLayer` y el `because()`
    existentes, más el método de paquete `noProductionClassInLayer`, y tras ajustar
    `SuppressionCitesAdrTest` para comparar cada contador contra
    `EmptyShouldExceptionInventoryTest.countOf(Marker....)` en vez del `EXCEPTIONS.size()` global
    (necesario porque el inventario ahora mezcla dos tipos de marcador):
    `./mvnw -B -pl app -am test
    -Dtest=LayeredArchitectureTest,EmptyShouldExceptionInventoryTest,SuppressionCitesAdrTest
    -Dsurefire.failIfNoSpecifiedTests=false` → 13/13 en verde; `SuppressionCitesAdrTest` confirma
    `2 == 2` apariciones de `.optionalLayer(` y `0 == 0` de `.allowEmptyShould(`. **Demostración de
    caducidad** (sin comprometer): se creó temporalmente
    `apps/api/app/src/main/java/com/confia/organization/infrastructure/TempExpiryProbe.java` (una
    clase mínima, sin lógica); `EmptyShouldExceptionInventoryTest` falló nombrando exactamente la
    entrada esperada: "`LayeredArchitectureTest.productionLayeringRule, layer Infrastructure's
    ADR-0020 exception no longer holds (no production class resides in an infrastructure package
    yet ...)`"; se borró el archivo y el directorio temporal, `git status` quedó limpio (solo los
    tres archivos de prueba de arquitectura modificados) antes de continuar. **Verificación del
    punto de control de esta fase:** `./mvnw -B verify` completo en `apps/api` → BUILD SUCCESS, 56
    pruebas (`productionCodeRespectsLayering` pasa con `Infrastructure` y `Web` opcionales, sin
    ampliar ninguna excepción), puerta JaCoCo `BUNDLE` de 80 % cumplida.

- [x] 2.5 **TDD — activación y desactivación.** ROJO: crear
  `apps/api/app/src/test/java/com/confia/organization/domain/InstitutionLifecycleTest.java`:
  desactivar una institución activa tiene éxito e `isActive` pasa a falso sin modificar otro
  atributo; reactivar una inactiva tiene éxito; activar una ya activa falla con
  `institution-already-active` sin modificar el estado; desactivar una ya inactiva falla con
  `institution-already-inactive` sin modificar el estado; dos instituciones con el mismo `id` pero
  atributos distintos son iguales (igualdad por identidad) y su `hashCode` coincide. VERDE: añadir
  `activate()` y `deactivate()` a `Institution` (mutan solo `active`, sin idempotencia) y crear
  `apps/api/app/src/main/java/com/confia/organization/domain/InstitutionStateException.java`
  (`final`, hereda de `DomainException`, fábricas de paquete para `institution-already-active` e
  `institution-already-inactive`); implementar `equals`/`hashCode` de `Institution` sobre `id`.
  REFACTOR: ninguno esperado. — Especificación `organization`, requisito «Activación y desactivación
  de una institución» (los cuatro escenarios) y requisito «Jerarquía de errores de dominio del módulo
  `organization`» (escenario de herencia de `DomainException`, parcial)
  - **Evidencia (2026-09-19):** ROJO observado:
    `./mvnw -B -pl app -am test -Dtest=InstitutionLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false`
    → `cannot find symbol method deactivate()/activate()` y
    `cannot find symbol class InstitutionStateException` (compilación). VERDE:
    `./mvnw -B -pl app -am test -Dtest=InstitutionLifecycleTest,InstitutionCreationTest
    -Dsurefire.failIfNoSpecifiedTests=false` → 16/16 (5 nuevas de `InstitutionLifecycleTest` + 11 de
    `InstitutionCreationTest`, sin regresión). REFACTOR: ninguno necesario.

- [x] 2.6 **`OrganizationErrorCodesTest` con seis códigos.** Crear
  `apps/api/app/src/test/java/com/confia/organization/domain/OrganizationErrorCodesTest.java`
  (espejo de `KernelErrorCodesTest`): catálogo cerrado de
  `institution-legal-name-blank`, `institution-legal-name-too-long`, `institution-trade-name-blank`,
  `institution-trade-name-too-long`, `institution-already-active`, `institution-already-inactive`;
  formato kebab-case `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` de máximo 64 caracteres; ausencia de repetidos;
  prefijo `institution-`; cada código pertenece a una subclase de `DomainException`. Verificación:
  falla si dos excepciones comparten código o si un código incumple el formato. — Especificación
  `organization`, requisito «Catálogo de códigos del módulo: formato y ausencia de repetidos» (ambos
  escenarios, parcial: seis de los once códigos de este corte)
  - **Evidencia (2026-09-19):** sin ciclo ROJO propio: los seis códigos ya existen en producción
    desde las tareas 2.2 y 2.5 (espejo de `KernelErrorCodesTest`, que tampoco tiene rojo cuando el
    catálogo ya existe). VERDE inmediato:
    `./mvnw -B -pl app -am test -Dtest=OrganizationErrorCodesTest -Dsurefire.failIfNoSpecifiedTests=false`
    → 5/5 (catálogo exacto, sin repetidos, formato kebab-case y longitud máxima, prefijo
    `institution-`, herencia de `DomainException`).

- [x] 2.7 **Puertas de calidad del `domain` en `app`.** En `apps/api/app/pom.xml`: si la tarea 1.5
  no declaró la regla `BUNDLE`, declararla ahora (`LINE`/`BRANCH` `COVEREDRATIO` 0.80) y actualizar
  el comentario de `coverage_threshold` en `openspec/config.yaml` en el mismo commit. Declarar la
  regla `PACKAGE` de `jacoco-check` con `<include>com.confia.*.domain</include>` y
  `<include>com.confia.*.domain.*</include>`, `LINE`/`BRANCH` `COVEREDRATIO` 0.95. Adherirse a
  `pitest-maven` con `targetClasses`/`targetTests` = `com.confia.*.domain.*`, y añadir
  `junit-platform-launcher` en alcance `test` (mismo motivo que `kernel`: `pitest-junit5-plugin`
  1.2+ lo exige); reutilizar los perfiles `mutation-gate` y `mutation-report` del padre sin cambios.
  Ejecutar `./mvnw -B -pl apps/api/app -am verify -Pmutation-report` como humo: confirmar que JaCoCo
  mide `com.confia.organization.domain` y que PIT genera y evalúa mutantes sobre esas clases sin
  romper por `failWhenNoMutations=true`. — Capacidad `build-integrity`, requisitos «Cobertura global
  mínima del módulo `app`» (si aplica aquí) y «Cobertura y mutación del paquete `domain` de cada
  módulo de negocio» (ambos escenarios de cobertura, más los dos de mutación por selección de perfil,
  heredados del comportamiento ya probado en `kernel`)
  - **Evidencia (2026-09-19):** tarea 1.5 ya había declarado `BUNDLE` (80 %) y el comentario de
    `coverage_threshold`, así que solo se añadió la regla `PACKAGE` (95 % líneas/ramas sobre
    `com.confia.*.domain` y `com.confia.*.domain.*`), la adhesión a `pitest-maven`
    (`targetClasses`/`targetTests` = `com.confia.*.domain.*`, espejo exacto de `kernel/pom.xml`) y
    `junit-platform-launcher` en alcance `test`. **Primer humo:**
    `./mvnw -B -pl app -am verify -Pmutation-report` rompió por
    `Rule violated for package com.confia.organization.domain: branches covered ratio is 0.85, but
    expected minimum is 0.95` — cobertura real insuficiente (no una demostración deliberada de la
    tarea 2.8), causada por tres ramas de `Institution.equals` sin ejercitar
    (`this == other`, `!(other instanceof Institution)`, ids distintos) y por
    `Institution.hashCode()` sin una aserción que distinga su valor de una constante. Se añadieron
    a `InstitutionLifecycleTest` los casos faltantes (autoigualdad, no igual a un tipo distinto ni a
    `null`, dos instituciones con `id` distinto nunca son iguales, y `hashCode()` igual al de `id()`)
    sin tocar la aplicación ni sus reglas de negocio. **Humo final:**
    `./mvnw -B -pl app -am verify -Pmutation-report` → BUILD SUCCESS; JaCoCo mide
    `com.confia.organization.domain` (regla `PACKAGE` cumplida) y PIT genera y evalúa 32 mutaciones
    sobre ese paquete, 32/32 muertas (100 %, informativo en esta rama), sin romper por
    `failWhenNoMutations=true`.

- [x] 2.8 **Demostración de que las puertas fallan** (sin comprometer): (a) comentar temporalmente
  una aserción de `InstitutionCreationTest` u `OrganizationErrorCodesTest` y observar que la regla
  `PACKAGE` rompe `./mvnw verify` por debajo de 95 %; revertir. (b) debilitar temporalmente una
  aserción de `Institution` (por ejemplo, aceptar `legalName` en blanco) y observar que
  `-Pmutation-gate` rompe por debajo de 80 mientras `-Pmutation-report` termina en verde y solo
  informa; revertir. Registrar ambas evidencias observadas. — Capacidad `build-integrity`, mismo
  requisito (escenarios de umbral de cobertura y de selección de perfil de mutación)
  - **Evidencia (2026-09-19):** (a) comentar una sola aserción, como en el precedente de
    `kernel-money-value-object` (tarea 1.13), no sirve: el método compartido `requireValidName` que
    valida `legalName` y `tradeName` sigue cubierto por la otra prueba. Se usó en su lugar
    `@Disabled` temporal sobre toda la clase `InstitutionLifecycleTest` (nunca comprometido):
    `./mvnw -B -pl app -am verify` → `BUILD FAILURE`,
    `Rule violated for package com.confia.organization.domain: lines covered ratio is 0.69, but
    expected minimum is 0.95` y `branches covered ratio is 0.42, but expected minimum is 0.95`
    (además de la puerta `BUNDLE` global, que también rompió: 0.78/0.62 frente a 0.80). Se revirtió
    quitando el `@Disabled` y el import de `org.junit.jupiter.api.Disabled`; `git status` quedó
    limpio. (b) debilitar la validación real de `Institution` rompe las pruebas existentes en la
    fase `test` antes de llegar a PIT (`rejectsALegalNameProvidedAsBlank` y
    `rejectsATradeNameProvidedAsBlank` fallan), lo que no aísla la diferencia entre los dos
    perfiles — mismo hallazgo que el precedente de `kernel-money-value-object` (tarea 1.13,
    evidencia (a)). Se usó en su lugar la técnica de ese mismo precedente: sobrescribir el umbral
    por línea de comandos sin tocar ningún archivo. Con la puntuación real de `organization.domain`
    en 100 % (32/32, cerrado en la tarea 2.7): tras instalar `kernel` y el POM padre en el
    repositorio Maven local (`./mvnw -B -pl kernel install -DskipTests` y `./mvnw -B -N install`,
    necesarios para poder acotar la ejecución a `app` en solitario), `./mvnw -B -pl app verify
    -Pmutation-gate -Dconfia.pit.mutationThreshold=101` → `BUILD FAILURE`,
    «Mutation score of 100 is below threshold of 101»; `./mvnw -B -pl app verify -Pmutation-report
    -Dconfia.pit.mutationThreshold=101` con el mismo umbral → `BUILD SUCCESS` (solo informa). Ningún
    archivo de producción ni de prueba quedó modificado; `git status` limpio durante toda la
    demostración.

- [x] 2.9 **Medir el diff real de PR B1** con `git diff --numstat <base-de-PR-A>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr'`
  (rama actual contra la base real de PR A, que puede ser `main` o el commit final de PR A si aún no
  se fusionó). Si cabe en 800 líneas, continuar. **Si supera 800, detener la aplicación y consultar
  al propietario** entre una subdivisión adicional dentro de B1 o una excepción de tamaño; no
  decidirlo sin el propietario. — P1 de la propuesta; `design.md`, «Pronóstico de tamaño por corte»
  - *Resultado (2026-09-19):* 933 líneas, por encima de 800. El propietario decidió partir PR B1 en
    el commit `0fe434e`: **PR B1** (rama `...-domain`, 773 líneas: puertos, ADR-0020, `Institution`
    con su ciclo de vida) y **PR B1-gates** (rama `...-domain-gates`, base PR B1, 160 líneas:
    catálogo de códigos y puertas de calidad del `domain`). Las dos ramas verifican en verde por
    separado; PR B2 pasa a tener base en PR B1-gates.
  - **Medición (2026-09-19), sin decisión tomada — DETENIDO, se consulta al propietario:**
    `git diff --numstat e3b9e84...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr'` (base real de
    PR A, commit `e3b9e84`, confirmado con `git merge-base`) →
    16 archivos, **933 líneas de autor** (878 adiciones + 55 eliminaciones), desglose:
    `apps/api/app/pom.xml` 49+/0-; `CurrentInstitutionProvider.java` 33+/0-;
    `InstitutionRepository.java` 29+/0-; `Institution.java` 137+/0-;
    `InstitutionStateException.java` 29+/0-; `InvalidInstitutionException.java` 50+/0-;
    `organization/package-info.java` 19+/0-; `EmptyShouldExceptionInventoryTest.java` 48+/21-;
    `LayeredArchitectureTest.java` 58+/30-; `SuppressionCitesAdrTest.java` 38+/3-;
    `InstitutionCreationTest.java` 121+/0-; `InstitutionLifecycleTest.java` 106+/0-;
    `OrganizationErrorCodesTest.java` 87+/0-; `InstitutionId.java` (kernel) 23+/0-;
    `kernel/package-info.java` 1+/1-; `InstitutionIdTest.java` 50+/0-. **933 > 800**, así que esta
    tarea se detiene exactamente como instruye su propio texto: no se decide aquí entre una
    subdivisión adicional dentro de B1 o una excepción de tamaño (`size:exception`); se reporta al
    propietario/orquestador. No se marca esta tarea como completada ni se continúa con la tarea
    2.10 hasta recibir esa decisión (coincide con el punto de parada dura fijado explícitamente en
    el lanzamiento de esta fase).

- [x] 2.10 **Verificación final de PR B1**: en checkout limpio, con `JAVA_HOME` en JDK 25, ejecutar
  `./mvnw -B verify -Pmutation-gate` en `apps/api`. Confirmar cobertura de `organization.domain` ≥
  95 % (líneas y ramas) y puntuación de mutación ≥ 80. Confirmar que `productionCodeRespectsLayering`
  pasa con las capas `Infrastructure` y `Web` opcionales. Empujar la rama `...-domain` (apuntando a
  PR A) y confirmar en la integración continua que el trabajo `backend` termina en verde con
  `-Pmutation-report`. — Capacidad `build-integrity`, ambos requisitos nuevos; criterios de éxito de
  la propuesta relativos a cobertura, mutación y ADR-0018/ADR-0020
  - *Evidencia (2026-09-19, `-Pmutation-gate`):* **PR B1** en `0fe434e`: `BUILD SUCCESS`, 176
    pruebas de `kernel` y 61 de `app`, todas las reglas de JaCoCo cumplidas, PIT de `kernel`
    176/178; corrida de integración continua `35486736639` en verde. **PR B1-gates** en `6eb311b`:
    `BUILD SUCCESS`, 245 pruebas, JaCoCo `BUNDLE` 80 % y `PACKAGE` 95 % sobre
    `organization.domain`, PIT de `organization.domain` 32/32 (100 %); corrida `35486736412` en
    verde.

---

## PR B2 — Atributos restantes del agregado (`rtn`, `address`, `defaultCurrency`, `locale`, `timezone`)

Rama `...-domain-attributes`, base PR B1. La firma de `Institution.create(...)` cambia en este PR
(pasa de tres a ocho parámetros); es aceptable porque el agregado no tiene consumidores fuera del
módulo (`design.md`, «Pronóstico de tamaño por corte»).

- [x] 3.1 **TDD — `rtn`.** ROJO: extender `InstitutionCreationTest` con la nueva firma de
  `Institution.create(id, legalName, tradeName, rtn, ...)` (los parámetros siguientes se añaden en
  tareas posteriores de este mismo PR; usar valores válidos fijos para ellos mientras no tengan su
  propia validación): rechazo de `rtn` vacío, de `rtn` con guiones (`"0801-1990-12345"`), de `rtn` de
  21 dígitos, y aceptación de `rtn` de 1, 14 y 20 dígitos, todos con el código
  `institution-rtn-invalid`. VERDE: extender `Institution.create(...)` con el parámetro `rtn`
  (`String`, `requireNonNull`, patrón `^[0-9]{1,20}$`, `MAX_RTN_DIGITS = 20`) y añadir a
  `InvalidInstitutionException` la fábrica de paquete `rtnInvalid()` con el código
  `institution-rtn-invalid`; actualizar todas las llamadas existentes a `create(...)` en
  `InstitutionCreationTest` y `InstitutionLifecycleTest` para incluir el nuevo parámetro. REFACTOR:
  ninguno esperado. — Especificación `organization`, requisito «RTN presente y numérico, con el
  formato exacto pendiente» (ambos escenarios)
  - **Evidencia (2026-09-19):** ROJO observado:
    `./mvnw -B -pl app -am test -Dtest=InstitutionCreationTest,InstitutionLifecycleTest
    -Dsurefire.failIfNoSpecifiedTests=false` → errores de compilación, `create` de tres parámetros
    no aplica a las llamadas de cuatro parámetros y `cannot find symbol RTN_INVALID`/`rtn()`. VERDE:
    tras añadir `rtn` (`String`, `requireNonNull`, `RTN_PATTERN = ^[0-9]{1,20}$`,
    `MAX_RTN_DIGITS = 20`) a `Institution.create(...)` y la fábrica `rtnInvalid()` a
    `InvalidInstitutionException`, y actualizar las llamadas existentes:
    `InstitutionCreationTest` 15/15 (4 nuevas: RTN vacío, RTN con guiones/21 dígitos, RTN de 1 y 20
    dígitos, RTN nulo), `InstitutionLifecycleTest` 8/8 (sin regresión), `OrganizationErrorCodesTest`
    5/5 (sin cambios, catálogo sigue en seis). REFACTOR: ninguno necesario.

- [x] 3.2 **TDD — `address`.** ROJO: extender `InstitutionCreationTest` con el parámetro `address`:
  rechazo de `address` vacía o de solo espacios (`institution-address-blank`), aceptación con exactamente
  500 puntos de código y rechazo con 501 (`institution-address-too-long`). VERDE: extender
  `Institution.create(...)` con `address` (`strip()`, no vacío, máximo 500 puntos de código,
  `MAX_ADDRESS_LENGTH = 500`) y añadir a `InvalidInstitutionException` las fábricas `addressBlank()`
  y `addressTooLong()`. Actualizar llamadas existentes a `create(...)`. REFACTOR: ninguno esperado. —
  Especificación `organization`, requisito «Construcción de `Institution` y sus atributos de
  identidad obligatorios» (escenarios de `address`, completando el requisito iniciado en la tarea
  2.2)
  - **Evidencia (2026-09-19):** ROJO observado: `./mvnw -B -pl app -am test
    -Dtest=InstitutionCreationTest,InstitutionLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false`
    → errores de compilación, `create` de cuatro parámetros no aplica a las llamadas de cinco
    parámetros y `cannot find symbol address()`. VERDE: tras añadir `address` (`strip()`, no vacío,
    máximo 500 puntos de código, `MAX_ADDRESS_LENGTH = 500`) a `Institution.create(...)` y las
    fábricas `addressBlank()`/`addressTooLong()` a `InvalidInstitutionException`, y actualizar las
    llamadas existentes: `InstitutionCreationTest` 20/20 (5 nuevas: dirección vacía, dirección de
    solo espacios, 500/501 puntos de código, dirección nula), `InstitutionLifecycleTest` 8/8 (sin
    regresión), `OrganizationErrorCodesTest` 5/5 (sin cambios, catálogo sigue en seis). REFACTOR:
    `requireValidName` se generalizó a `requireValidText(rawText, maxLength, blankError,
    tooLongError)` para reutilizarse entre `legalName`/`tradeName` (200) y `address` (500), sin
    cambiar ningún comportamiento observable.

- [x] 3.3 **TDD — `defaultCurrency`.** ROJO: extender `InstitutionCreationTest` con el parámetro
  `defaultCurrency` (`CurrencyCode` de `kernel`): un valor nulo lanza `NullPointerException`, no un
  error de dominio; un valor `HNL` o `USD` se acepta. VERDE: extender `Institution.create(...)` con
  `defaultCurrency` (`requireNonNull`, sin validación de dominio adicional porque `CurrencyCode` ya
  es un conjunto cerrado). Actualizar llamadas existentes a `create(...)`. REFACTOR: ninguno
  esperado. — Especificación `organization`, requisito «Moneda por defecto, localización y huso
  horario válidos» (escenario «Moneda por defecto nula»)
  - **Evidencia (2026-09-19):** ROJO observado: `./mvnw -B -pl app -am test
    -Dtest=InstitutionCreationTest,InstitutionLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false`
    → errores de compilación, `create` de cinco parámetros no aplica a las llamadas de seis
    parámetros y `cannot find symbol defaultCurrency()`. VERDE: tras añadir `defaultCurrency`
    (`CurrencyCode`, `requireNonNull`, sin regla de dominio adicional) a `Institution.create(...)` y
    actualizar las llamadas existentes: `InstitutionCreationTest` 22/22 (2 nuevas: moneda nula,
    `HNL`/`USD` aceptadas), `InstitutionLifecycleTest` 8/8 (sin regresión), `OrganizationErrorCodesTest`
    5/5 (sin cambios, catálogo sigue en seis). REFACTOR: ninguno necesario.

- [x] 3.4 **TDD — `locale`.** ROJO: extender `InstitutionCreationTest` con el parámetro `locale`
  (`java.util.Locale`): `Locale.forLanguageTag("es-HN")` se acepta; `Locale.ROOT` (sin idioma) falla
  con `institution-locale-invalid`; un `locale` nulo lanza `NullPointerException`. VERDE: extender
  `Institution.create(...)` con `locale` (`requireNonNull`, rechaza idioma vacío) y añadir a
  `InvalidInstitutionException` la fábrica `localeInvalid()`. Actualizar llamadas existentes a
  `create(...)`. REFACTOR: ninguno esperado. — Especificación `organization`, mismo requisito
  (escenarios «Localización sin idioma»)
  - **Evidencia (2026-09-19):** ROJO observado: `./mvnw -B -pl app -am test
    -Dtest=InstitutionCreationTest,InstitutionLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false`
    → errores de compilación, `create` de seis parámetros no aplica a las llamadas de siete
    parámetros y `cannot find symbol locale()`. VERDE: tras añadir `locale` (`java.util.Locale`,
    `requireNonNull`, rechaza `getLanguage().isEmpty()`) a `Institution.create(...)` y la fábrica
    `localeInvalid()` a `InvalidInstitutionException`, y actualizar las llamadas existentes:
    `InstitutionCreationTest` 24/24 (2 nuevas: `Locale.ROOT` rechazado, `locale` nulo),
    `InstitutionLifecycleTest` 8/8 (sin regresión), `OrganizationErrorCodesTest` 5/5 (sin cambios,
    catálogo sigue en seis). REFACTOR: ninguno necesario.

- [ ] 3.5 **TDD — `timezone`.** ROJO: extender `InstitutionCreationTest` con el parámetro `timezone`
  (`java.time.ZoneId`): `ZoneId.of("America/Tegucigalpa")` se acepta; `ZoneOffset.ofHours(-6)`
  (desplazamiento fijo) falla con `institution-timezone-invalid`; un `timezone` nulo lanza
  `NullPointerException`. VERDE: extender `Institution.create(...)` con `timezone` (`requireNonNull`,
  rechaza instancias de `ZoneOffset`) y añadir a `InvalidInstitutionException` la fábrica
  `timezoneInvalid()`; con esto la firma de `create(...)` queda completa según `design.md`
  «Contratos e interfaces». Actualizar llamadas existentes a `create(...)`. REFACTOR: ninguno
  esperado. — Especificación `organization`, mismo requisito (escenarios «Huso horario reconocido» y
  «Huso horario de desplazamiento fijo»)

- [ ] 3.6 **TDD — nombre comercial opcional y sus límites.** ROJO: extender `InstitutionCreationTest`
  (si no quedó cubierto en la tarea 2.2): construcción exitosa con `tradeName` nulo (`tradeName` en
  el resultado es nulo); rechazo de `tradeName` provisto como cadena de solo espacios
  (`institution-trade-name-blank`); rechazo de `tradeName` de 201 caracteres y aceptación de 200
  (`institution-trade-name-too-long`). VERDE: confirmar/ajustar la validación de `tradeName` en
  `Institution.create(...)` (nulo permitido; si no es nulo, mismas reglas que `legalName`) y las
  fábricas `tradeNameBlank()`/`tradeNameTooLong()` de `InvalidInstitutionException` (si no se
  completaron en 2.2, completarlas aquí). REFACTOR: ninguno esperado. — Especificación
  `organization`, requisito «Nombre comercial opcional» (ambos escenarios)

- [ ] 3.7 **TDD — precedencia de invariantes.** ROJO: añadir a `InstitutionCreationTest` una prueba
  parametrizada que confirma el orden exacto de `design.md` («Flujo de datos»): primero
  `requireNonNull` de los siete argumentos obligatorios (todos salvo `tradeName`), luego las reglas
  de negocio en el orden `legalName` → `tradeName` (si no es nulo) → `rtn` → `address` → `locale` →
  `timezone`; con al menos tres pares de atributos simultáneamente inválidos (por ejemplo, `legalName`
  en blanco y `rtn` inválido a la vez → falla con `institution-legal-name-blank`; `tradeName` en
  blanco y `address` vacía a la vez → falla con `institution-trade-name-blank`; `rtn` inválido y
  `timezone` de desplazamiento fijo a la vez → falla con `institution-rtn-invalid`), confirmando que
  se lanza una sola excepción, la primera que aplica. VERDE: ajustar el orden de las comprobaciones
  dentro de `Institution.create(...)` si la implementación incremental de las tareas 3.1 a 3.6 no
  coincide exactamente con este orden. REFACTOR: extraer el orden de validación a un método privado
  legible si mejora la claridad. — `design.md`, «Agregado `Institution`», tabla de atributos e
  invariantes (orden de evaluación); no hay escenario propio en la especificación, pero está
  implícito en el requisito «Construcción de `Institution`...» al exigir «una sola excepción, la
  primera que aplica»

- [ ] 3.8 **`toString()` sin RTN ni dirección.** Extender `InstitutionLifecycleTest` con una
  aserción sobre `Institution.toString()` que confirma el formato `"Institution[id=..., tradeName=...]"`
  y la ausencia literal del `rtn` y de la `address` usados en el fixture de la prueba (ahora
  significativa, porque ambos atributos existen desde este PR). Ajustar `Institution.toString()` si
  no cumple el formato exacto. — `design.md`, decisión 5, «Razones puntuales» (mensajes técnicos que
  nunca repiten la entrada, `CLAUDE.md` regla 11); no hay escenario propio en la especificación

- [ ] 3.9 **`OrganizationErrorCodesTest` a once códigos.** Extender el catálogo cerrado de la tarea
  2.6 con `institution-rtn-invalid`, `institution-address-blank`, `institution-address-too-long`,
  `institution-locale-invalid`, `institution-timezone-invalid` (once códigos en total para el corte
  B). Confirmar formato, unicidad, prefijo y herencia de `DomainException` para los cinco nuevos. —
  Especificación `organization`, requisito «Catálogo de códigos del módulo: formato y ausencia de
  repetidos» (completa los once del corte B)

- [ ] 3.10 **Medir el diff real de PR B2 y verificación final.** Medir con
  `git diff --numstat <base-de-PR-B1>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr'`. Si cabe
  en 800 líneas, continuar; **si supera 800, detener la aplicación y consultar al propietario** entre
  una subdivisión adicional o una excepción de tamaño. Si cabe: en checkout limpio, con `JAVA_HOME`
  en JDK 25, ejecutar `./mvnw -B verify -Pmutation-gate` en `apps/api`; confirmar cobertura de
  `organization.domain` ≥ 95 % y mutación ≥ 80 sobre el agregado completo. Empujar la rama
  `...-domain-attributes` (apuntando a PR B1) y confirmar en la integración continua que el trabajo
  `backend` termina en verde. — P1 de la propuesta; capacidad `build-integrity`, ambos requisitos;
  criterios de éxito de la propuesta relativos a `Institution` inválida y al catálogo de códigos

---

## PR C — Capa `application`: puertos y caso de uso

Rama `...-application`, base PR B2.

- [ ] 4.1 **TDD — `ResolveCurrentInstitution`, caso activo.** ROJO: crear
  `apps/api/app/src/test/java/com/confia/organization/application/InMemoryInstitutionRepository.java`
  y `.../FixedCurrentInstitutionProvider.java` (dobles de prueba, en `src/test`, sobre los puertos
  creados en la tarea 2.3b) y
  `apps/api/app/src/test/java/com/confia/organization/application/ResolveCurrentInstitutionTest.java`
  con: un `CurrentInstitutionProvider` que resuelve un `InstitutionId` conocido y un
  `InstitutionRepository` que devuelve, para ese identificador, una `Institution` con `isActive`
  verdadero → el caso de uso devuelve esa `Institution`; el constructor de `ResolveCurrentInstitution`
  rechaza cada puerto nulo con `NullPointerException`. VERDE: crear
  `apps/api/app/src/main/java/com/confia/organization/application/InstitutionRepository.java`
  `.../ResolveCurrentInstitution.java` (`final`, sin anotaciones de Spring, constructor con
  `requireNonNull` de ambos puertos, `execute()` que resuelve el identificador y carga la
  institución). Ningún bean de Spring se registra: la clase no lleva anotaciones y no se escanea
  desde `com.confia.bootstrap`. REFACTOR: ninguno esperado. — Especificación `organization`,
  requisitos «Puerto de salida para cargar una institución por identificador» (escenario «Carga con
  dobles en memoria»), «Puerto de salida para la institución de la solicitud en curso» (su
  escenario) y «Caso de uso de resolución de la institución en curso» (escenario «Resolución exitosa
  de una institución activa»)

- [ ] 4.2 **TDD — institución inexistente e inactiva.** ROJO: extender
  `ResolveCurrentInstitutionTest`: un `InstitutionRepository` sin ninguna institución registrada bajo
  el identificador resuelto → el caso de uso falla con `institution-not-found`; un
  `InstitutionRepository` que devuelve una `Institution` con `isActive` falso → el caso de uso falla
  con `institution-inactive`; el doble `InMemoryInstitutionRepository` sin ninguna institución
  registrada devuelve una ausencia de resultado sin lanzar ninguna excepción, para cualquier
  `InstitutionId`. VERDE: crear
  `apps/api/app/src/main/java/com/confia/organization/domain/InstitutionNotFoundException.java`
  (`final`, hereda de `DomainException`, constructor público, código `institution-not-found`) y
  `.../InstitutionInactiveException.java` (constructor público, código `institution-inactive`);
  implementar en `ResolveCurrentInstitution.execute()` el rechazo de ausencia de resultado y de
  institución inactiva con esos errores. Extender `OrganizationErrorCodesTest` a trece códigos
  (once del corte B más `institution-not-found` e `institution-inactive`), confirmando que ambos son
  construibles con constructor público (a diferencia de las fábricas de paquete del resto del
  catálogo) y que igual heredan de `DomainException` con formato válido. REFACTOR: ninguno esperado.
  — Especificación `organization`, requisitos «Puerto de salida para cargar una institución por
  identificador» (escenario «Ausencia de resultado para un identificador desconocido»), «Caso de uso
  de resolución de la institución en curso» (escenarios «Rechazo de una institución inexistente» y
  «Rechazo de una institución inactiva») y «Catálogo de códigos del módulo» (trece códigos completos)

- [ ] 4.3 **Medir el diff real de PR C** con
  `git diff --numstat <base-de-PR-B2>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr'`. Si
  cabe en 800 líneas, continuar. **Si supera 800, detener la aplicación y consultar al propietario**;
  el corte C ya es la unidad más pequeña de las cuatro (185–290 líneas estimadas), por lo que un
  exceso aquí sería inesperado y merece revisión aparte antes de subdividir. — P1 de la propuesta;
  `design.md`, «Pronóstico de tamaño por corte»

- [ ] 4.4 **Verificación final de PR C y cierre del cambio.** En checkout limpio, con `JAVA_HOME` en
  JDK 25, ejecutar `./mvnw -B verify` y `./mvnw -B verify -Pmutation-report` en `apps/api`. Confirmar
  que `organization.domain` mantiene cobertura ≥ 95 % y mutación ≥ 80 con las dos clases de error
  nuevas incluidas, y que `organization.application` no está sujeta a las puertas de `domain` (sin
  regla `PACKAGE` sobre ella). Empujar la rama `...-application` (apuntando a PR B2) y confirmar en
  la integración continua que el trabajo `backend` termina en verde. Repasar la lista completa de
  «Criterios de éxito» de `proposal.md` contra el estado final del reactor y dejar constancia de cada
  uno como cumplido u observado. — Capacidad `organization` y `build-integrity` completas; todos los
  criterios de éxito de la propuesta

# Tareas: objeto de valor `Money` en el módulo `kernel`

## Review Workload Forecast

| Campo | Valor |
|---|---|
| Presupuesto de revisión de esta sesión | 400 líneas (preflight de sesión) |
| Presupuesto de revisión vigente para este cambio (decisión del propietario D2) | **800 líneas** de cambio efectivo por pull request — prevalece sobre el valor de sesión; no se re-decide aquí |
| Líneas de autor estimadas (código, sin artefactos OpenSpec ni ADR-0019) | 1 318 a 1 767, según `design.md` §«Entrega en dos pull requests» |
| Riesgo frente al presupuesto de 400 (guardia literal de la fase) | **High** — cualquier partición razonable supera 400 por PR |
| Riesgo frente al presupuesto de 800 vigente (D2) | **Medium** — PR1 653–887, PR2 665–880; los valores centrales (770, 772) caben en 800, solo el extremo alto se excede (87 y 80 líneas) |
| PR encadenados recomendados | Sí — ya decidido por el propietario (D5, opción B) |
| División sugerida | PR 1 (`change/kernel-money-value-object`, base `main`) → PR 2 (`change/kernel-money-value-object-arithmetic`, base PR 1) |
| Estrategia de entrega | `auto-chain` |
| Estrategia de cadena | `stacked-to-main` |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Nota sobre el excedente en el extremo alto (ya resuelta, no bloquea la aplicación):** si al cerrar
un PR el diff real supera 800 líneas, la tarea de medición de ese PR (1.14 o 2.11) detiene la
aplicación y consulta al propietario, con los puntos de corte que nombra `design.md`
(`1a`/`1b` y `2a`/`2b`). Esto ya está decidido como procedimiento (D5 ajustada); no es una decisión
pendiente antes de iniciar `sdd-apply`.

### Nota sobre el límite de quince tareas (`openspec/changes/README.md`)

Esta lista tiene **27 tareas** (15 en PR 1, 12 en PR 2), por encima del límite de quince. Se
mantiene como excepción justificada, sin dividir la propuesta en cambios SDD adicionales. **El
propietario del producto aceptó esta excepción el 2026-09-18, con un máximo de quince tareas por
pull request.** Las razones concretas son:

1. **TDD estricto exige orden ROJO → VERDE → REFACTOR por comportamiento.** `openspec/config.yaml`
   activa `strict_tdd: true` en este mismo cambio; cada tarea de comportamiento agrupa su propio
   ciclo completo (para mantenerla en un archivo/comportamiento, no para evitar la disciplina), pero
   los 13 requisitos y 45 escenarios de `specs/money/spec.md` no caben en menos de una decena de
   ciclos sin mezclar comportamientos no relacionados en una sola tarea.
2. **Dos pull requests encadenados, cada uno con sus propias puertas.** La decisión D5 exige que
   cada PR compile, pase JaCoCo (95 %) y PIT (80) **sobre lo que contiene**, con la medición del
   diff real y la posible parada para el propietario como tarea explícita en cada uno (1.14 y 2.11).
   Eso ya son cuatro tareas de cierre de puerta que no existen en un cambio de un solo PR.
3. **El instrumental (JaCoCo, PIT, jqwik, dos perfiles de mutación, integración continua) es
   condición previa a `Money`**, no parte de él: son piezas separables que no pueden compactarse en
   la tarea de una clase sin perder trazabilidad de verificación (`design.md`, «Secuencia de
   implementación»).
4. **El riesgo del entorno local** (PKIX de Windows, PIT y jqwik no descargados) exige una tarea de
   humo explícita y separable (1.7), con la integración continua en `ubuntu-latest` como fuente de
   verdad, en vez de enterrarla dentro de otra tarea.

Cada tarea sigue siendo pequeña, verificable y de una sola sesión; el conteo alto refleja el tamaño
real del cambio (ya reconocido por el propietario en D2/D5), no tareas vagas ni fragmentación
artificial.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Instrumental + semilla mínima (`DomainException`, `CurrencyCode`, `UnsupportedCurrencyException`) con TDD estricto activo | PR 1 | `./mvnw -pl apps/api/kernel test` | `./mvnw -pl apps/api/kernel -am verify -Pmutation-report` (prueba de humo de PIT/jqwik sobre Java 25 y JUnit 6) | Revertir el PR 1 completo; `openspec/config.yaml` vuelve a `strict_tdd: false` |
| 2 | `Money` exacto: construcción, igualdad, `add`, `subtract`, `negate`, con `MoneyConstructionTest` y la primera parte de `MoneyArithmeticTest` | PR 1 | `./mvnw -pl apps/api/kernel test -Dtest=MoneyConstructionTest,MoneyArithmeticTest` | `./mvnw -B verify` en `apps/api` con `JAVA_HOME` en JDK 25 | Borrar `Money.java`, `CurrencyMismatchException.java`, `InvalidMoneyAmountException.java` y sus pruebas; PR 1 queda en el estado de la unidad 1 |
| 3 | Comparación, multiplicación, `Percentage`, redondeo y `allocate`, con las pruebas restantes y las propiedades de jqwik | PR 2 | `./mvnw -pl apps/api/kernel test -Dtest=MoneyComparisonTest,MoneyArithmeticTest,PercentageTest,MoneyAllocationTest,MoneyProperties,MoneyRegressionTest` | `./mvnw -B verify -Pmutation-report` en la rama `change/kernel-money-value-object-arithmetic` | Cerrar el PR 2 sin fusionar; el PR 1 queda intacto y completo por sí solo |
| 4 | Guardas transversales y catálogo de errores (`MoneyApiShapeTest`, `KernelErrorCodesTest`) y verificación final de PR 2 | PR 2 | `./mvnw -pl apps/api/kernel test -Dtest=MoneyApiShapeTest,KernelErrorCodesTest` | `./mvnw -B verify` completo en `apps/api`, JDK 25, en checkout limpio | Borrar ambas clases de prueba; no afecta ninguna otra unidad |

Ejecutor de todas las tareas: `./mvnw -B verify` en `apps/api`, con `JAVA_HOME` fijado en
`C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot` (JDK 25) en toda ejecución local
(`design.md`, «Restricciones del entorno local»). PIT, `pitest-junit5-plugin`, jqwik y la versión de
JaCoCo con soporte de Java 25 no están en la caché local de esta máquina: si la descarga falla por
la brecha PKIX de Windows documentada en `apps/api/pom.xml`, la integración continua en
`ubuntu-latest` es la fuente de verdad del resultado. Nunca se omite una prueba, se baja un umbral o
se sustituye una versión por una cacheada incompatible para pasar en local.

---

## PR 1 — Instrumental, semilla y `Money` exacto

Rama `change/kernel-money-value-object`, base `main`. Compila y pasa `./mvnw verify` con JaCoCo a
95 % y PIT a 80 sobre lo que contiene. Ninguna operación se entrega sin su prueba en el mismo PR.

- [x] 1.1 `openspec/config.yaml`: `strict_tdd: true`, `rules.apply.tdd: true`,
  `rules.apply.test_command: "./mvnw verify"` (en `apps/api`, JDK 25),
  `rules.verify.coverage_threshold: 95` (líneas y ramas de `kernel`; el umbral global de 80 % sobre
  `app` llega con el cambio 4, decisión ya cerrada), y actualizar el bloque `context` para retirar
  la descripción «sin código todavía» (código existe desde el cambio 1: reactor Maven en `apps/api`
  e integración continua). Verificación: el archivo sigue siendo YAML válido y coherente con
  `docs/13-metodologia-sdd.md`. — Alcance de la propuesta, punto 9; sin RED/GREEN (no es
  comportamiento de producción)

- [x] 1.2 `apps/api/pom.xml` (POM padre): `pluginManagement` de `jacoco-maven-plugin` (versión con
  soporte oficial de Java 25, `0.8.14` o posterior; confirmar contra las notas de versión, nunca
  bajar a `0.8.12` cacheada) y de `org.pitest:pitest-maven` con la dependencia de complemento
  `org.pitest:pitest-junit5-plugin`; propiedades `confia.pit.phase=none`,
  `confia.pit.mutationThreshold=80`, `confia.pit.effectiveThreshold=${confia.pit.mutationThreshold}`;
  perfiles `mutation-report` (activación `-Pmutation-report`, `confia.pit.phase=verify`,
  `confia.pit.effectiveThreshold=0`) y `mutation-gate` (activación por `confia.ci.mainBranch=true` o
  `-Pmutation-gate`, `confia.pit.phase=verify`, `confia.pit.effectiveThreshold=${confia.pit.mutationThreshold}`),
  declarado **después** de `mutation-report` para que gane si ambos están activos a la vez
  (`design.md`, decisiones 9 y 10). Verificación: `./mvnw -N validate` resuelve sin error. —
  Capacidad `build-integrity`, requisitos «Cobertura mínima del módulo `kernel`» y «Puntuación de
  mutación del módulo `kernel` según la rama»; sin RED/GREEN (configuración de build)

- [x] 1.3 `apps/api/kernel/pom.xml`: declarar `jqwik` (versión gestionada por la propiedad
  `jqwik.version` en `dependencyManagement` del padre) y `junit-platform-launcher` en alcance
  `test` (no toca `enforce-kernel-purity`, que solo prohíbe `compile`/`runtime`/`provided`/`system`);
  añadir el complemento `jacoco-maven-plugin` con la ejecución `check` en fase `verify` sobre
  `BUNDLE`, `LINE` y `BRANCH`, `COVEREDRATIO` mínimo `0.95`; adherirse a `pitest-maven` con
  `targetClasses`/`targetTests` en `com.confia.kernel.*`, `mutators=STRONGER`, `threads=2`,
  `timestampedReports=false`, `outputFormats=HTML,XML`, `failWhenNoMutations=true`. Crear
  `apps/api/kernel/src/test/resources/junit-platform.properties` con
  `jqwik.database=target/jqwik-database` y `jqwik.tries.default=1000`. Verificación:
  `./mvnw -pl apps/api/kernel -am validate` resuelve sin error. — Capacidad `build-integrity`,
  ambos requisitos nuevos; `design.md` decisiones 8, 9 y 12; sin RED/GREEN (configuración de build)

- [x] 1.4 Eliminar `apps/api/kernel/src/test/java/com/confia/kernel/BuildSmokeTest.java` (su
  Javadoc «sin reglas de negocio todavía» queda falso) y actualizar
  `apps/api/kernel/src/main/java/com/confia/kernel/package-info.java` para documentar el paquete
  plano `com.confia.kernel` (`design.md`, decisión 1: sin subpaquetes, por la verificación de Spring
  Modulith de `app`). Verificación: `./mvnw -pl apps/api/kernel test` sigue en verde sin la prueba
  de humo retirada. — `design.md`, «Cambios de archivos»; sin RED/GREEN (limpieza)

- [x] 1.5 **TDD — `DomainException`** (ADR-0019). ROJO: escribir
  `apps/api/kernel/src/test/java/com/confia/kernel/DomainExceptionTest.java` contra una subclase de
  prueba: `code()` estable, formato kebab-case `<sujeto>-<condición>`, `DomainException` es
  abstracta y no instanciable directamente, es una excepción no comprobada (no exige `throws` ni
  captura), y el constructor rechaza un código nulo o mal formado. VERDE: implementar
  `apps/api/kernel/src/main/java/com/confia/kernel/DomainException.java` (`public abstract class
  DomainException extends RuntimeException`, `protected DomainException(String code, String
  message)`, `public final String code()`) hasta que la prueba pase. REFACTOR: extraer la validación
  del formato del código si se repite. — Especificación `money`, requisito «Jerarquía de errores de
  dominio de `kernel`» (escenarios «no se instancia directamente», «no obliga a declararse ni a
  capturarse»)

- [x] 1.6 **TDD — `CurrencyCode` y `UnsupportedCurrencyException`**. ROJO: escribir
  `CurrencyCodeTest.java` cubriendo `HNL` y `USD` con sus dígitos de unidad menor,
  `fromIsoCode` sensible a mayúsculas, `UnsupportedCurrencyException` (código
  `currency-unsupported`, mensaje que nunca repite la entrada rechazada) ante un código fuera del
  conjunto cerrado, y una **propiedad de jqwik** que compara `minorUnitDigits()` contra
  `java.util.Currency.getInstance(name()).getDefaultFractionDigits()` para cada valor del `enum`
  (esta propiedad sirve también como humo del motor de jqwik). VERDE: implementar
  `CurrencyCode.java` (`enum CurrencyCode { HNL(2), USD(2); }`, `minorUnitDigits()`,
  `fromIsoCode(String)`) y `UnsupportedCurrencyException.java` (código `currency-unsupported`,
  hereda de `DomainException`). REFACTOR: limpiar el analizador de `fromIsoCode`. — Especificación
  `money`, requisito «Moneda cerrada al conjunto habilitado» (ambos escenarios)

- [x] 1.7 **Prueba de humo de PIT y jqwik sobre Java 25 y JUnit 6**, antes de escribir `Money`
  (`design.md`, «Secuencia de implementación», paso 3): ejecutar
  `./mvnw -pl apps/api/kernel -am verify -Pmutation-report` y confirmar que (a) JaCoCo instrumenta
  clases compiladas para Java 25, (b) PIT genera y evalúa mutantes sobre `DomainException` y
  `CurrencyCode` con `pitest-junit5-plugin` sobre JUnit Platform 6, y (c) Surefire ejecuta la
  propiedad de jqwik de la tarea 1.6. Registrar el resultado exacto como evidencia. **Si PIT, su
  complemento o jqwik fallan por incompatibilidad, detener la aplicación y reportar el error exacto
  al propietario: nunca desactivar la puerta, nunca bajar el umbral, nunca sustituir jqwik**
  (ADR-0008). Si la máquina local no puede descargar los artefactos (brecha PKIX de Windows), dar
  por cumplida la prueba de humo solo cuando la integración continua la ejecute en verde en
  `ubuntu-latest`. — Capacidad `build-integrity`, ambos requisitos; riesgo de la propuesta «PIT y
  jqwik nunca se descargaron en esta máquina»

- [x] 1.8 `SuppressionCitesAdrTest`: agregar los marcadores de JaCoCo y PIT del catálogo de
  `design.md` decisión 13 (exclusiones de PIT, desactivación de cualquier complemento,
  `haltOnFailure=false` de JaCoCo, exclusión de clases de JaCoCo, `@Generated`) a la lista de
  patrones con nombre, y una prueba parametrizada que confirma, para cada patrón, que coincide con
  su ejemplo y que **no** coincide con las exclusiones legítimas del enforcer. Verificación:
  `./mvnw -pl apps/api/app test -Dtest=SuppressionCitesAdrTest` en verde. — ADR-0018, sección 3.b
  (obligación asignada a este cambio, no forma parte del catálogo original del cambio 1)

- [x] 1.9 `.github/workflows/ci.yml`: seleccionar el perfil de mutación según `github.ref` en el
  paso `verify` del trabajo `backend`
  (`-Dconfia.ci.mainBranch=true` en `main`, `-Pmutation-report` en cualquier otro caso, incluidos
  `change/**` y `pull_request`), agregar `upload-artifact` fijado por SHA de commit con los reportes
  de JaCoCo y PIT, y reescribir el comentario de cabecera (líneas 3–6) y el del paso `verify`.
  Alinear `docs/06-estrategia-de-testing.md` línea 844 con la activación real por propiedad.
  Verificación: `help:evaluate` local de la expresión con ambos valores simulados de `github.ref`.
  — Capacidad `build-integrity`, requisito «Puntuación de mutación del módulo `kernel` según la
  rama» (escenario «Selección de perfil de mutación según la rama»)

- [x] 1.10 **TDD — `CurrencyMismatchException` e `InvalidMoneyAmountException`**. ROJO: pruebas que
  confirman que ambas heredan de `DomainException`, que `CurrencyMismatchException` expone
  `expected()`/`actual()` de tipo `CurrencyCode` con código `currency-mismatch`, y que
  `InvalidMoneyAmountException` tiene tres fábricas de paquete privadas por causa (`malformed()`,
  `scaleExceeded(int)`, `outOfRange()`) con códigos `money-amount-malformed`,
  `money-scale-exceeded`, `money-amount-out-of-range`, cuyos mensajes nunca repiten la cadena de
  entrada. VERDE: implementar ambas clases. REFACTOR: ninguno esperado. — Especificación `money`,
  requisito «Jerarquía de errores de dominio de `kernel`» (escenario de `CurrencyMismatchException`)
  y requisito «Construcción y normalización a escala cuatro» (códigos de error)

- [x] 1.11 **TDD — construcción e igualdad de `Money`** (`MoneyConstructionTest`). ROJO: escribir
  los escenarios de la especificación `money`: cadena decimal válida normalizada a escala 4;
  `Money.zero(HNL)`; importe negativo; mínimo representable `0.01`; límite de `NUMERIC(14,4)`
  `9999999999.9999`; rechazo con más de cuatro decimales (`money-scale-exceeded`); rechazo de cadena
  mal formada con separador de miles (`money-amount-malformed`); rechazo por encima del límite
  (`money-amount-out-of-range`); precedencia `malformed` → `scaleExceeded` → `outOfRange`; ausencia
  de fábrica desde `double`/`float` (el código no compila); igualdad insensible a la escala de
  entrada (`"1.0" == "1.00"`, mismo `hashCode`); mismo importe con moneda distinta no son iguales;
  importes distintos en la misma moneda no son iguales; `toPlainString` exacto y su ida y vuelta sin
  pérdida; construcción con moneda habilitada. VERDE: implementar
  `apps/api/kernel/src/main/java/com/confia/kernel/Money.java` como `public final class Money
  implements Comparable<Money>` (constructor privado con `setScale(4, RoundingMode.UNNECESSARY)`,
  validación de forma `-?\d+(\.\d+)?` de hasta 64 caracteres, validación de rango
  `|amount| <= 9999999999.9999`), fábricas `of(String, CurrencyCode)`, `of(BigDecimal,
  CurrencyCode)`, `zero(CurrencyCode)`, `amount()`, `currency()`, `toPlainString()`, `equals`
  (misma moneda y `amount.compareTo(...) == 0`), `hashCode` (`31 * amount.hashCode() +
  currency.hashCode()`), `toString()` (`"1234.5500 HNL"`, solo para registros). REFACTOR: extraer
  la validación de forma y de rango a métodos privados reutilizables por las demás operaciones
  (tarea 1.12 y las de PR 2). — Especificación `money`, requisitos «Construcción y normalización a
  escala cuatro», «Igualdad por importe normalizado y moneda» y «Exposición del importe sin
  acoplarse al DTO de la API»

- [x] 1.12 **TDD — `add`, `subtract`, `negate`** (primera parte de `MoneyArithmeticTest`). ROJO:
  suma exitosa en la misma moneda; resta exitosa en la misma moneda; suma de monedas distintas
  lanza `CurrencyMismatchException` sin construir ningún `Money`; suma que excede el límite
  superior de `NUMERIC(14,4)` falla con `money-amount-out-of-range` sin construir ningún `Money`;
  resta que excede el límite inferior falla igual; aritmética con importe negativo sin normalizar
  el signo (`-45.50 + 20.00 = -25.50`); ausencia de una operación de valor absoluto en `Money`.
  VERDE: implementar `add`, `subtract` y `negate` sobre el constructor privado ya extraído (tarea
  1.11), verificando el invariante de rango sobre el resultado exacto en cada operación, nunca solo
  en la construcción inicial. REFACTOR: ninguno esperado. — Especificación `money`, requisitos
  «Suma y resta solo entre la misma moneda» e «Importes negativos como información contable»

- [x] 1.13 **Demostración de que las puertas fallan** (misma lógica que el cambio 1: una regla que
  nunca falló no prueba nada), sin comprometer el cambio: (a) comentar temporalmente una aserción de
  `MoneyConstructionTest` y observar que `jacoco:check` rompe `./mvnw verify` por debajo de 95 %;
  revertir. (b) debilitar temporalmente una aserción de `Money` y observar que
  `-Pmutation-gate` rompe por debajo de 80 mientras `-Pmutation-report` termina en verde y solo
  informa; revertir. (c) `mvn help:evaluate -Dexpression=confia.pit.effectiveThreshold` en las
  cuatro combinaciones de la tabla de `design.md` decisión 10, más ambos perfiles activos a la vez,
  confirmando que `mutation-gate` gana por ser el último declarado. Registrar las tres evidencias
  observadas en el informe de verificación; si el entorno local no ejecuta PIT, (b) y (c) quedan
  para la integración continua con autorización explícita del propietario para un commit temporal.
  — Capacidad `build-integrity`, ambos requisitos (demostración de bloqueo/aviso por rama)
  - *Evidencia observada (2026-09-18, local, JDK 25):* (a) comentar aserciones no sirve: otras
    pruebas cubren las mismas líneas (cobertura seguía en 100 %). Se usó en su lugar un método
    temporal sin cubrir en `CurrencyCode`: `jacoco:check` falló con «Coverage checks have not been
    met»; revertido. (b) Con puntuación real de 93 y `-Dconfia.pit.mutationThreshold=95`,
    `-Pmutation-gate` falló con «Mutation score of 93 is below threshold of 95», mientras
    `-Pmutation-report` con el mismo umbral terminó en verde. (c) `help:evaluate`: sin perfil
    umbral 80 y fase `none`; `-Pmutation-report` 0/`verify`; `-Pmutation-gate` 80/`verify`;
    `-Dconfia.ci.mainBranch=true` 80/`verify`; ambos perfiles activos 80/`verify` (gana la puerta).

- [x] 1.14 **Medir el diff real de PR 1** con `git diff --numstat main...change/kernel-money-value-object`,
  excluyendo `openspec/` y `docs/adr/ADR-0019-error-de-dominio-base-en-el-nucleo.md` (y su fila en
  `docs/adr/README.md`), tal como fija `design.md` («Qué se cuenta»). Si el total cabe en 800
  líneas, continuar. **Si supera 800, detener la aplicación y consultar al propietario** entre un
  tercer PR en el punto de corte `1a` (instrumental + semilla) / `1b` (`Money` exacto) o una
  excepción de tamaño para PR 1; nunca decidir esto sin el propietario. — Decisión D2/D5 de la
  propuesta; `design.md`, «Control durante la aplicación»
  - *Resultado (2026-09-18):* 1337 líneas, por encima de 800. El propietario eligió partir PR 1 en
    el punto de corte del diseño: **PR 1a** (`change/kernel-money-value-object`, commits `30dbea4`
    y `780c5cb`, unas 646 líneas: instrumental y semilla) y **PR 1b**
    (`change/kernel-money-value-object-exact`, base PR 1a, commit `26ef15d`, unas 691 líneas:
    `Money` exacto). PR 2 pasa a tener base en PR 1b.

- [x] 1.15 **Verificación final de PR 1**: en checkout limpio, con `JAVA_HOME` en
  `C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot`, ejecutar `./mvnw -B verify` en
  `apps/api`. Confirmar cobertura de líneas y de ramas de `kernel` ≥ 95 % (JaCoCo) y, si PIT corrió
  localmente, puntuación de mutación ≥ 80 sobre las clases nuevas; si no corrió localmente, dejar
  constancia de que la integración continua en `ubuntu-latest` es la fuente de verdad. Empujar la
  rama `change/kernel-money-value-object` y confirmar en GitHub Actions que el trabajo `backend`
  termina en verde con la selección de perfil de la tarea 1.9. — Capacidad `build-integrity`,
  ambos requisitos; criterios de éxito de la propuesta (`./mvnw verify` en verde, cobertura ≥ 95 %,
  mutación ≥ 80, `main`/`change/**` usan el perfil correcto)
  - *Evidencia local (2026-09-18, `-Pmutation-gate`):* PR 1a en `780c5cb`: `BUILD SUCCESS`,
    JaCoCo cumplido, PIT 13/13 (100 %). PR 1b en `26ef15d`: `BUILD SUCCESS`, JaCoCo 100 % de
    líneas y ramas en `kernel`, PIT 50/54 (93 %; cuatro mutantes supervivientes a revisar en
    PR 2). La corrida en GitHub Actions se registra al empujar cada rama.

---

## PR 2 — Comparación, multiplicación, `Percentage`, redondeo y reparto

Rama `change/kernel-money-value-object-arithmetic`, base `change/kernel-money-value-object` (PR 1).
El nombre debe empezar por `change/` para que `ci.yml` ejecute su verificación mientras apunta al
PR 1 (`-Pmutation-report`); al fusionarse PR 1, PR 2 se redirige a `main` y recibe su ejecución de
`pull_request`.

- [x] 2.1 **TDD — comparación total** (`MoneyComparisonTest`). ROJO: `isGreaterThan` verdadero y su
  simétrico `isLessThan` también verdadero en la misma moneda; `isZero`, `isPositive`, `isNegative`
  correctos sobre cero, positivo y negativo (los demás predicados falsos en cada caso);
  `compareTo`, `isGreaterThan` o cualquier otra comparación entre monedas distintas lanza
  `CurrencyMismatchException`. VERDE: declarar `Money implements Comparable<Money>`, implementar
  `compareTo` (compara importes, lanza `CurrencyMismatchException` ante monedas distintas),
  `isZero`, `isPositive`, `isNegative`, `isGreaterThan`, `isGreaterThanOrEqual`, `isLessThan`,
  `isLessThanOrEqual` sobre `compareTo`. REFACTOR: ninguno esperado. — Especificación `money`,
  requisito «Comparación total ordenada entre importes de la misma moneda» (los tres escenarios)
  - *Evidencia (2026-09-18, local):* RED: 21 errores de compilación en `MoneyComparisonTest`
    (métodos ausentes). GREEN: `./mvnw -pl kernel test -Dtest=MoneyComparisonTest` → 9/9;
    conjunto completo del módulo, 52/52. Sin REFACTOR.

- [x] 2.2 **TDD — `Percentage`** (`PercentageTest`). ROJO: construcción válida desde `String` y
  desde `BigDecimal` en puntos porcentuales, a escala 4; rechazo fuera de `[0, 100]`
  (`percentage-out-of-range`, ejemplo `"100.01"`); ausencia de fábrica desde `double`/`float` (no
  compila); mismas reglas de forma y escala que `Money` (`money-scale-exceeded` /
  `percentage-scale-exceeded` según corresponda). VERDE: implementar
  `apps/api/kernel/src/main/java/com/confia/kernel/Percentage.java` (`public final class
  Percentage`, `SCALE = 4`, `of(String)`, `of(BigDecimal)`, `value()`, `toPlainString()`, `equals`/
  `hashCode` sobre el valor normalizado, `toString` `"15.0000%"`) e
  `InvalidPercentageException.java` con sus tres fábricas de paquete
  (`malformed()`, `scaleExceeded(int)`, `outOfRange()`) y códigos `percentage-malformed`,
  `percentage-scale-exceeded`, `percentage-out-of-range`. REFACTOR: reutilizar la validación de
  forma/escala extraída en la tarea 1.11 si aplica sin acoplar `Money` y `Percentage` entre sí más
  allá de lo necesario. — Especificación `money`, requisito «`Percentage` como colaborador
  explícito de `Money`» (los cuatro escenarios propios de `Percentage`)
  - *Evidencia (2026-09-18, local):* RED: errores de compilación en `PercentageTest` (símbolos
    ausentes). GREEN: `./mvnw -pl kernel test -Dtest=PercentageTest` → 14/14. Sin REFACTOR: la
    validación de forma/escala se mantiene propia de `Percentage`, sin acoplarla a `Money` (la
    reutilización del diseño es condicional, "si aplica").

- [x] 2.3 **TDD — `multiply`** (segunda parte de `MoneyArithmeticTest`). ROJO: `multiply(long)`
  exacto sin redondeo (`1234.55 × 3 = 3703.65`, sin necesidad de redondear porque cabe en la escala
  interna); `multiply(BigDecimal, RoundingMode)` con un único redondeo a escala 4 explícito, sin
  modo implícito. VERDE: implementar `multiply(long factor)` (exacto, sin `RoundingMode`) y
  `multiply(BigDecimal factor, RoundingMode rounding)` (un solo redondeo a escala 4), verificando el
  invariante de rango sobre el resultado. REFACTOR: ninguno esperado. — Especificación `money`,
  requisitos «Redondeo explícito con `HALF_UP` en toda operación que redondea» (escenario de
  multiplicación exacta) y «Casos de regresión permanentes de ADR-0004» (la colegiatura de tres
  meses, verificada aquí y confirmada de nuevo como regresión permanente en la tarea 2.8)
  - *Evidencia (2026-09-18, local):* RED: 5 errores de compilación (`multiply` ausente). GREEN:
    `./mvnw -pl kernel test -Dtest=MoneyArithmeticTest` → 17/17 (incluye desbordamiento de rango
    en ambas sobrecargas). Sin REFACTOR.

- [x] 2.4 **TDD — `percentage(Percentage, RoundingMode)`**. ROJO: aplicación con redondeo explícito
  (`12.30` × `Percentage.of("5")` con `HALF_UP` → `0.62`, intermedio exacto `0.6150`); tasa cero deja
  el importe base sin modificar y devuelve `Money.zero`; el caso permanente de ADR-0004
  (`6.70` × `Percentage.of("15")` con `HALF_UP` → `1.01`, intermedio exacto `1.0050`, nunca `1.00`).
  VERDE: implementar `percentage(Percentage percentage, RoundingMode rounding)` como
  `amount × p / 100` exacto (`multiply(p.value()).movePointLeft(2)`), redondeado una sola vez a
  escala 4. REFACTOR: ninguno esperado. — Especificación `money`, requisitos «`Percentage` como
  colaborador explícito de `Money`» (escenarios de aplicación y de tasa cero) y «Casos de regresión
  permanentes de ADR-0004» (el impuesto que la coma flotante pierde)
  - *Evidencia (2026-09-18, local):* RED: 4 errores de compilación (`percentage` ausente). GREEN:
    `./mvnw -pl kernel test -Dtest=MoneyArithmeticTest` → 21/21. Sin REFACTOR. Nota: `percentage()`
    redondea solo a escala 4 (design.md, decisión 4); el resultado final `0.62`/`1.01` de la
    especificación exige además `roundToMinorUnit` (tarea 2.5), verificado en conjunto en 2.8.

- [x] 2.5 **TDD — `roundToMinorUnit(RoundingMode)`**. ROJO: los tres puntos medios exactos
  obligatorios `0.615`, `2.345`, `1.005` redondeados con `HALF_UP` a la escala menor de la moneda dan
  `0.62`, `2.35`, `1.01`; su simetría negativa `-0.615`, `-2.345`, `-1.005` da `-0.62`, `-2.35`,
  `-1.01`; redondear el máximo representable `9999999999.9999` con `HALF_UP` produce
  `money-amount-out-of-range` (el resultado re-expresado a escala 4 excede el límite). VERDE:
  implementar `roundToMinorUnit(RoundingMode rounding)` (redondea a la escala menor de la moneda,
  re-expresa a escala 4, verifica el invariante de rango sobre el resultado). REFACTOR: ninguno
  esperado. — Especificación `money`, requisito «Redondeo explícito con `HALF_UP` en toda operación
  que redondea» (ambos escenarios de puntos medios)
  - *Evidencia (2026-09-18, local):* RED: 7 errores de compilación (`roundToMinorUnit` ausente).
    GREEN: `./mvnw -pl kernel test -Dtest=MoneyArithmeticTest` → 25/25 (incluye los tres puntos
    medios, su simetría negativa y el desbordamiento al redondear el límite superior). Sin
    REFACTOR.

- [x] 2.6 **TDD — `allocate(int...)`** (`MoneyAllocationTest`). ROJO: reparto con residuo por el
  método del resto mayor (`100.00` entre `[1,1,1]` → `[33.34, 33.33, 33.33]`, suma exactamente
  `100.00`); reparto exacto sin residuo (`90.00` entre `[1,1,1]` → `[30.00, 30.00, 30.00]`); rechazo
  de lista vacía, de un peso negativo y de todos los pesos en cero (los tres casos, ninguno
  construye reparto); un total que no es múltiplo exacto de la unidad menor de la moneda lanza
  `IllegalArgumentException` (error de programación, `design.md` decisión 7). VERDE: implementar
  `allocate(int... ratios)` con aritmética de `BigInteger` (unidades menores, cociente y resto por
  peso, desempate por índice ascendente ante resto igual, signo aplicado al valor absoluto
  repartido, `List.copyOf(...)` inmodificable en el orden de los pesos). REFACTOR: extraer el
  algoritmo de reparto a un método privado legible según el pseudocódigo de `design.md` decisión 7.
  — Especificación `money`, requisito «Reparto proporcional sin pérdida de residuo (`allocate`)»
  (los tres primeros escenarios)
  - *Evidencia (2026-09-18, local):* RED: 7 errores de compilación (`allocate` ausente). GREEN:
    `./mvnw -pl kernel test -Dtest=MoneyAllocationTest` → 11/11. REFACTOR: se extrajo el algoritmo
    a `requireValidRatios`, `requireExactMinorUnitMultiple` y
    `distributeByLargestRemainder` (métodos privados), siguiendo el pseudocódigo de `design.md`
    decisión 7; conjunto completo de `kernel` tras el refactor: `./mvnw -pl kernel test` → 122/122.

- [ ] 2.7 **Propiedades de jqwik** (`MoneyProperties`), generadores acotados al rango de
  `NUMERIC(14,4)`, mil intentos por propiedad, semilla informada en caso de fallo: (a) la suma de
  las partes de `allocate` es exactamente igual al total para cualquier total no negativo y
  cualquier vector de pesos enteros positivos de longitud uno o más, y ninguna parte es negativa;
  (b) ninguna parte es positiva si el total no lo es; (c) cada parte dista menos de una unidad menor
  de su cuota exacta; (d) toda secuencia de `add`, `subtract`, `negate` y `multiply(long)` coincide
  con el mismo cálculo en `BigDecimal` a escala completa sin redondeos intermedios, redondeado una
  sola vez al final con `HALF_UP` vía `roundToMinorUnit`; (e) `percentage` coincide con el producto
  exacto redondeado una sola vez. — Especificación `money`, requisitos «Reparto proporcional sin
  pérdida de residuo (`allocate`)» (escenario de propiedad) y «Coherencia entre operaciones
  intermedias y el cálculo a escala completa» (escenario de propiedad)

- [ ] 2.8 **`MoneyRegressionTest`**, Javadoc que prohíbe borrar los casos: `6.70 ×
  Percentage.of("15")` con `HALF_UP` → `1.01` (el impuesto que la coma flotante pierde);
  `1234.55 × 3 == 3703.65` exactamente, igual según `equals` a `Money.of("3703.65", HNL)` (la
  colegiatura de tres meses); mil sumas consecutivas de `0.1` sobre `Money.zero(HNL)` dan
  exactamente `100.00` (la acumulación de mil sumas); `0.1 + 0.2 == 0.3` exactamente (el caso
  clásico). Estos cuatro casos ya se ejercitaron en las tareas 2.3, 2.4 y esta misma tarea consolida
  su forma permanente en un único archivo, conforme exige la especificación. *Actualización del
  2026-09-18 (revisión de los PR #3 y #4):* `MoneyRegressionTest` ya existe en PR 1b con los dos
  casos que solo usan `add` (mil sumas de `0.1` y `0.1 + 0.2`); esta tarea añade únicamente los dos
  que dependen de `multiply` y `percentage`. — Especificación
  `money`, requisito «Casos de regresión permanentes de ADR-0004» (los cuatro escenarios)

- [ ] 2.9 **`MoneyApiShapeTest`**: reflexión del JDK sobre `Money` y `Percentage` completos (ambos
  ya terminados en este PR) que confirma que ningún miembro público usa `double`, `float`, `Double`
  ni `Float`, ni como parámetro ni como retorno. Verificación: falla si se agrega temporalmente una
  sobrecarga con `double` a cualquiera de las dos clases, y vuelve a verde al retirarla. —
  Especificación `money`, requisitos «Construcción y normalización a escala cuatro» y «`Percentage`
  como colaborador explícito de `Money`» (ambos escenarios de «ausencia de fábrica desde coma
  flotante»); reemplaza la regla de ArchUnit diferida al cambio 4 (decisión D3 de la propuesta)

- [ ] 2.10 **`KernelErrorCodesTest`**: catálogo completo y cerrado de los ocho códigos de error del
  núcleo (`currency-mismatch`, `currency-unsupported`, `money-amount-malformed`,
  `money-scale-exceeded`, `money-amount-out-of-range`, `percentage-malformed`,
  `percentage-scale-exceeded`, `percentage-out-of-range`): formato kebab-case, unicidad entre todas
  las excepciones de `kernel`, y que cada uno pertenece a una subclase de `DomainException`.
  Verificación: falla si dos excepciones comparten código o si un código no sigue el formato. —
  Especificación `money`, requisito «Jerarquía de errores de dominio de `kernel`»; `design.md`,
  decisión 2 (catálogo de códigos, «definitivos»)

- [ ] 2.11 **Medir el diff real de PR 2** con
  `git diff --numstat change/kernel-money-value-object...change/kernel-money-value-object-arithmetic`,
  con las mismas exclusiones de la tarea 1.14. Si cabe en 800 líneas, continuar. **Si supera 800,
  detener la aplicación y consultar al propietario** entre un tercer PR en el punto de corte `2a`
  (comparación, multiplicación, porcentaje y redondeo) / `2b` (`allocate`, propiedades y
  regresiones) o una excepción de tamaño para PR 2. — Decisión D2/D5 de la propuesta; `design.md`,
  «Control durante la aplicación»

- [ ] 2.12 **Verificación final de PR 2**: en checkout limpio de la rama
  `change/kernel-money-value-object-arithmetic` (base PR 1), con `JAVA_HOME` en JDK 25, ejecutar
  `./mvnw -B verify -Pmutation-report`. Confirmar cobertura de líneas y de ramas de `kernel` ≥ 95 %
  sobre el módulo completo (PR 1 + PR 2) y puntuación de mutación ≥ 80 informada, sin romper la
  construcción por estar en una rama de trabajo. Confirmar además, simulando
  `-Dconfia.ci.mainBranch=true` en local o en un commit temporal autorizado, que `mutation-gate`
  **sí** rompe la construcción si la puntuación cae por debajo de 80, mientras
  `mutation-report` en la misma rama de trabajo solo informa (cierre de la demostración de puertas
  de la tarea 1.13, ahora sobre el módulo `Money` completo). Empujar la rama y confirmar en GitHub
  Actions que el trabajo `backend` termina en verde con `-Pmutation-report` mientras apunta al PR 1.
  — Capacidad `build-integrity`, ambos requisitos; criterios de éxito de la propuesta (cobertura,
  mutación y selección de perfil por rama)

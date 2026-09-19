```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:1be226fc8c8fc4aa17f8449c2cdeae2c7292d86c2c5f81388fbe5d68f9d13e90
verdict: pass_with_warnings
blockers: 0
critical_findings: 0
requirements: 14/15
scenarios: 55/56
test_command: ./mvnw -B verify -Pmutation-gate
test_exit_code: 0
test_output_hash: sha256:ed8328176d406ad40cfbac688f9dd4867b404ff3797141cdead5b4588837a54c
build_command: ./mvnw -B verify -Pmutation-gate
build_exit_code: 0
build_output_hash: sha256:ed8328176d406ad40cfbac688f9dd4867b404ff3797141cdead5b4588837a54c
```

## Verification Report — Ronda 1 (final)

**Change**: kernel-money-value-object (F0, cambio 2 de 13)
**Versión de las especificaciones**: `specs/money/spec.md` (blob `5af4782503087de5584642fd82325777c0055c2c`, 13 requisitos, 51 escenarios) y `specs/build-integrity/spec.md` (blob `4577011b295a6b80307b4375317943233dee448f`, delta con 2 requisitos añadidos, 5 escenarios)
**Mode**: Strict TDD (`openspec/config.yaml`: `strict_tdd: true`, `rules.apply.tdd: true`)
**Commit verificado**: `41524a2779da6fd35bf909b79636958e3ad663db`, rama `main` (fusión del PR #6; los PR #3, #4, #5 y #6 ya están fusionados), árbol limpio
**Comando real**: `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" ./mvnw -B verify -Pmutation-gate`, ejecutado en `apps/api`
**`evidence_revision`**: SHA-256 de las cuatro líneas `commit=<HEAD>`, `spec_blob_money=<blob>`, `spec_blob_build_integrity=<blob>` y `build_output=sha256:<hash de la salida>`, en ese orden y terminadas en salto de línea.

Criterio de conteo, idéntico al del cambio 1: un escenario cuenta como completado solo si es
COMPLIANT; un requisito cuenta como completado solo si todos sus escenarios lo son.

**Nota sobre el validador.** El cambio 1 validó su informe con `gentle-ai sdd-verify-validate`. En
`gentle-ai 3.1.0` ese comando ya no existe (`unknown command "sdd-verify-validate"`), y
`gentle-ai sdd-attempt` solo conserva la operación `grant`. El informe no se validó con ninguna
herramienta; el bloque YAML conserva el formato del cambio 1 por trazabilidad, no como certificado.

---

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 27 (15 de PR 1, 12 de PR 2; excepción al límite de quince aceptada por el propietario el 2026-09-18) |
| Tasks complete | 27 |
| Tasks incomplete | 0 |

Contadas con `grep -cE "^- \[x\]" tasks.md` → 27 y `grep -cE "^- \[ \]" tasks.md` → 0. Las marcas
se contrastaron con evidencia, no solo con las casillas:

- **1.1 a 1.4, 1.8, 1.9**: configuración y limpieza presentes en `openspec/config.yaml`,
  `apps/api/pom.xml`, `apps/api/kernel/pom.xml`, `junit-platform.properties`,
  `SuppressionCitesAdrTest` (8 pruebas en verde) y `.github/workflows/ci.yml`; `BuildSmokeTest` ya
  no existe.
- **1.5, 1.6, 1.10 a 1.12, 2.1 a 2.10**: cada clase de prueba nombrada existe y pasa en esta
  ejecución (tabla de pruebas más abajo).
- **1.13**: las tres demostraciones de puertas se **reobservaron en esta ronda** (sección
  «Demostración de puertas»).
- **1.14 y 2.11**: mediciones registradas con la decisión del propietario (división en PR 1a/1b y
  2a/2b); se aceptan como registro histórico.
- **1.15 y 2.12**: `apply-progress.md` deja la integración continua como «pendiente». Esta ronda la
  confirmó en `main` (corrida `35452164229`, ver más abajo).

---

### Build & Tests Execution

**Build**: Passed — `BUILD SUCCESS`, exit 0, 53.3 s, 491 líneas de salida, sobre el árbol limpio en `41524a2`.

```text
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.dependency.DependencyConvergence passed
[INFO] Rule 2: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed   (confia-kernel)
[INFO] CONFIA API Parent .................................. SUCCESS [  0.616 s]
[INFO] CONFIA Kernel ...................................... SUCCESS [ 38.954 s]
[INFO] CONFIA API ......................................... SUCCESS [ 13.288 s]
[INFO] BUILD SUCCESS
```

**Tests**: 202 passed / 0 failed / 0 skipped (171 en `confia-kernel`, 31 en `confia-api`).

```text
Tests run: 7   -- CurrencyCodeTest (JUnit)          Tests run: 1  -- CurrencyCodeTest (jqwik)
Tests run: 3   -- CurrencyMismatchExceptionTest     Tests run: 14 -- DomainExceptionTest
Tests run: 5   -- InvalidMoneyAmountExceptionTest   Tests run: 4  -- KernelErrorCodesTest
Tests run: 13  -- MoneyAllocationTest               Tests run: 2  -- MoneyApiShapeTest
Tests run: 33  -- MoneyArithmeticTest               Tests run: 12 -- MoneyComparisonTest
Tests run: 39  -- MoneyConstructionTest             Tests run: 4  -- MoneyRegressionTest
Tests run: 29  -- PercentageTest                    Tests run: 5  -- MoneyPropertiesTest (jqwik)
confia-api: EmptyShouldExceptionInventoryTest 1, LayeredArchitectureTest 2,
NoCrossModuleDomainImportsTest 2, NoCyclesTest 2, NoStandardStreamAccessTest 2,
NoTechnicalLayerPackageNamesTest 2, SpringModulithVerificationTest 2, SuppressionCitesAdrTest 8,
ConfiaApplicationTest 10
```

**Coverage (JaCoCo 0.8.14, `kernel/target/site/jacoco/jacoco.csv`)**: 100 % en todos los contadores.
«All coverage checks have been met» (BUNDLE, LINE y BRANCH ≥ 0.95).

| Clase | Líneas | Ramas | Instrucciones |
|---|---|---|---|
| `Money` | 119/119 | 62/62 | 651/651 |
| `Percentage` | 34/34 | 22/22 | 142/142 |
| `DomainException` | 14/14 | 6/6 | 48/48 |
| `CurrencyCode` | 11/11 | 4/4 | 55/55 |
| `CurrencyMismatchException` | 6/6 | — | 21/21 |
| `InvalidMoneyAmountException` | 5/5 | — | 24/24 |
| `InvalidPercentageException` | 5/5 | — | 24/24 |
| `UnsupportedCurrencyException` | 2/2 | — | 5/5 |
| **Total `kernel`** | **196/196** | **94/94** | **970/970** |

**Mutación (PIT 1.30.0, `STRONGER`, `-Pmutation-gate`, umbral 80)**:

```text
>> Line Coverage (for mutated classes only): 194/194 (100%)
>> Generated 178 mutations Killed 176 (99%)
>> Mutations with no coverage 0. Test strength 99%
>> Ran 524 tests (2.94 tests per mutation)
```

Conteo por estado en `kernel/target/pit-reports/mutations.xml`: 175 `KILLED`, 1 `TIMED_OUT`
(detectado), 2 `SURVIVED`. Detalle de los tres no `KILLED`:

| Estado | Clase y método | Línea | Mutador | Juicio |
|---|---|---|---|---|
| `TIMED_OUT` | `Money.distributeByLargestRemainder` | 313 | `RemoveConditionalMutator_ORDER_IF` (`i < partCount` → `true`) | Detectado: el bucle se vuelve infinito y PIT lo cuenta como muerto |
| `SURVIVED` | `Money.requireBoundedScale` | 108 | `ConditionalsBoundaryMutator` (`scale() < 0` → `<= 0`) | **Equivalente** (ver análisis) |
| `SURVIVED` | `Percentage.requireBoundedScale` | 77 | `ConditionalsBoundaryMutator` (`scale() < 0` → `<= 0`) | **Equivalente** (ver análisis) |

**Análisis de equivalencia.** El mutante solo cambia el camino cuando la escala es exactamente 0.
En `Money`, con escala 0, `precision - 0 > 10` significa once o más dígitos enteros. El mutante
lanza `InvalidMoneyAmountException.outOfRange()` en la guardia; el código original deja pasar el
valor a `normalizeToScale`, que lo reescala a 4 sin coste apreciable (la escala es 0) y sin fallo
(no hay decimales), y el constructor privado lanza la misma `outOfRange()` porque
`|valor| ≥ 10^10 > 9999999999.9999`. Con escala 0 no puede haber exceso de decimales, así que la
precedencia `scale-exceeded` → `out-of-range` tampoco cambia. Misma clase, mismo código y mismo
mensaje; solo difieren el marco de la traza y un reescalado barato. En `Percentage` el razonamiento
es idéntico con tres dígitos enteros: `|valor| ≥ 1000` queda fuera de `[0, 100]`, también para
negativos, y el constructor lanza la misma `outOfRange()`. **Ambos mutantes son verdaderamente
equivalentes**; ninguna prueba puede matarlos sin observar detalles internos.

**Integración continua en el commit verificado**: `gh run list --branch main --limit 3` muestra
`35452164229` (fusión del PR #6, `headSha` `41524a2` confirmado con `gh run view`), `35452089815`
(PR #5) y `35417888084` (PR #4), todas en `success`. En `35452164229`: `backend verify: success`,
`security scanning: success`; el paso ejecutó
`./mvnw --batch-mode verify -Dconfia.ci.mainBranch=true`, con `enforce-no-snapshots-on-main`,
`jacoco:check (jacoco-check)` y `pitest:mutationCoverage (pit-mutation)` en el registro; 171 y 31
pruebas, «All coverage checks have been met», `Generated 178 mutations Killed 176 (99%)`,
`BUILD SUCCESS`. Artefacto `backend-reports` (136 950 bytes) publicado.

---

### Demostración de puertas (tarea 1.13, reobservada en esta ronda)

Sin modificar código ni configuración:

| Demostración | Comando (en `apps/api`) | Observado |
|---|---|---|
| JaCoCo rompe por debajo de 95 % | `./mvnw -B -pl kernel verify -Dtest=CurrencyCodeTest -Dsurefire.failIfNoSpecifiedTests=false`, tras apartar `kernel/target/jacoco.exec` | Exit 1, `BUILD FAILURE`: «Rule violated for bundle confia-kernel: lines covered ratio is 0.11, but expected minimum is 0.95» y «branches covered ratio is 0.07» |
| `mutation-gate` bloquea | `./mvnw -B -pl kernel verify -Pmutation-gate -Dconfia.pit.mutationThreshold=100` | Exit 1: «Mutation score of 99 is below threshold of 100» |
| `mutation-report` solo informa | `./mvnw -B -pl kernel verify -Pmutation-report -Dconfia.pit.mutationThreshold=100` | Exit 0, `BUILD SUCCESS`, `Killed 176 (99%)` |
| Selección de perfil | `./mvnw -q -N help:evaluate` de `confia.pit.effectiveThreshold` y `confia.pit.phase` | Sin perfil: 80/`none`; `-Pmutation-report`: 0/`verify`; `-Dconfia.ci.mainBranch=true`: 80/`verify`; ambos: 80/`verify` (gana la puerta) |

La primera ejecución de la demostración de JaCoCo, sin apartar el `jacoco.exec` de la corrida
completa, terminó en **verde** con una sola clase de prueba: ver W3.

---

### Spec Compliance Matrix — `money`

Rutas relativas a `apps/api/kernel/src/test/java/com/confia/kernel/`. Todas las pruebas citadas
pasaron en esta ronda.

| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| 1. Construcción y normalización | Cadena decimal válida | `MoneyConstructionTest > constructsFromAValidDecimalStringNormalizedToScaleFour` | COMPLIANT |
| 1. Construcción y normalización | Importe cero | `MoneyConstructionTest > zeroConstructsToScaleFourZero`; `MoneyComparisonTest > isZeroIsPositiveAndIsNegativeAreExhaustiveAndMutuallyExclusive` | COMPLIANT |
| 1. Construcción y normalización | Importe negativo | `MoneyConstructionTest > constructsANegativeAmount` | COMPLIANT |
| 1. Construcción y normalización | Mínimo representable | `MoneyConstructionTest > constructsTheMinimumRepresentableAmount` | COMPLIANT |
| 1. Construcción y normalización | Límite de `NUMERIC(14,4)` | `MoneyConstructionTest > constructsAtTheNumeric14x4Limit`, `constructsAtTheNegativeNumeric14x4Limit` | COMPLIANT |
| 1. Construcción y normalización | Más de cuatro decimales | `MoneyConstructionTest > rejectsMoreThanFourDecimalDigits`, `theBigDecimalFactoryStillRejectsMoreThanFourDecimalDigits` | COMPLIANT |
| 1. Construcción y normalización | Ceros sobrantes aceptados | `MoneyConstructionTest > acceptsTrailingZerosBeyondScaleFourBecauseNoInformationIsLost` | COMPLIANT |
| 1. Construcción y normalización | Longitud máxima (64/65) | `MoneyConstructionTest > acceptsAStringOfExactlySixtyFourCharacters`, `rejectsAStringLongerThanSixtyFourCharacters` | COMPLIANT |
| 1. Construcción y normalización | Precedencia entre errores | `MoneyConstructionTest > malformedTakesPrecedenceOverScaleExceeded`, `scaleExceededTakesPrecedenceOverOutOfRange`, `theBigDecimalFactoryKeepsScaleExceededAheadOfOutOfRange` (literales distintos a los de la especificación, ver S6) | COMPLIANT |
| 1. Construcción y normalización | Cadena mal formada | `MoneyConstructionTest > rejectsAMalformedStringWithAThousandsSeparator`, `rejectsExponentialNotation`, `rejectsALeadingPlusSign` | COMPLIANT |
| 1. Construcción y normalización | Por encima del límite | `MoneyConstructionTest > rejectsAboveTheNumeric14x4Limit`, `theBigDecimalFactoryStillRejectsAnOutOfRangeAmount` | COMPLIANT |
| 1. Construcción y normalización | Sin fábrica de coma flotante | `MoneyConstructionTest > noFactoryAcceptsADoubleOrAFloatParameter`; `MoneyApiShapeTest > moneyHasNoPublicMemberUsingAFloatingPointType` (RED registrado en 2.9) | COMPLIANT |
| 1. Construcción y normalización | Escala extrema en `BigDecimal` | `MoneyConstructionTest > rejectsAnExtremelyLargePositiveInputScaleWithoutHanging`, `rejectsAnExtremelyNegativeInputScaleWithoutHanging`, `theBigDecimalFactoryRejectsANegativeScaleBeyondTenIntegerDigitsAsOutOfRange` (`1E+10`), `acceptsABigDecimalInputScaleOfExactlyThirtyFourWhenAllTrailingDigitsAreZero`, `rejectsABigDecimalInputScaleOfThirtyFiveEvenWithOnlyTrailingZeros`, `treatsAZeroValueOfAnExtremeScaleAsZeroWithoutHanging`; equivalentes en `PercentageTest`; todas con `assertTimeoutPreemptively` de 2 s | COMPLIANT |
| 2. Moneda cerrada | Moneda habilitada | `MoneyConstructionTest > constructsWithEachEnabledCurrency` | COMPLIANT |
| 2. Moneda cerrada | Código no habilitado | `CurrencyCodeTest > fromIsoCodeRejectsACodeOutsideTheClosedSet`, `fromIsoCodeIsCaseSensitiveAndRejectsLowercase` (sin aserción sobre `values()`, ver S5) | COMPLIANT |
| 3. Igualdad | Insensible a la escala | `MoneyConstructionTest > treatsDifferentInputScalesAsTheSameAmountWithTheSameHashCode` | COMPLIANT |
| 3. Igualdad | Moneda distinta | `MoneyConstructionTest > theSameAmountInDifferentCurrenciesIsNotEqual` | COMPLIANT |
| 3. Igualdad | Importes distintos | `MoneyConstructionTest > differentAmountsInTheSameCurrencyAreNotEqual` | COMPLIANT |
| 4. Suma y resta | Suma exitosa | `MoneyArithmeticTest > addsTwoAmountsInTheSameCurrency` | COMPLIANT |
| 4. Suma y resta | Resta exitosa | `MoneyArithmeticTest > subtractsTwoAmountsInTheSameCurrency` | COMPLIANT |
| 4. Suma y resta | Monedas distintas | `MoneyArithmeticTest > addingDifferentCurrenciesThrowsAndConstructsNoMoney`, `subtractingDifferentCurrenciesThrows` | COMPLIANT |
| 4. Suma y resta | Excede el límite superior | `MoneyArithmeticTest > addingAboveTheUpperNumeric14x4LimitFailsWithoutConstructingAnyMoney` | COMPLIANT |
| 4. Suma y resta | Excede el límite inferior | `MoneyArithmeticTest > subtractingBelowTheLowerNumeric14x4LimitFailsWithoutConstructingAnyMoney` | COMPLIANT |
| 5. Redondeo explícito | Tres puntos medios | `MoneyArithmeticTest > roundsHalfUpAtTheThreeMandatoryExactMidpoints` | COMPLIANT |
| 5. Redondeo explícito | Simetría negativa | `MoneyArithmeticTest > roundingIsSymmetricForNegativeMidpoints` | COMPLIANT |
| 5. Redondeo explícito | Factor decimal exacto | `MoneyArithmeticTest > multipliesByAnExactIntegerFactorWithoutRounding`, `multiplyingByADecimalFactorNeedingNoRoundingIsExact` | COMPLIANT |
| 5. Redondeo explícito | Factor de escala absurda | `MoneyArithmeticTest > multiplyingByADecimalFactorWithAnExtremelyLargePositiveScaleFailsWithoutHanging`, `multiplyingByADecimalFactorWithAnExtremelyNegativeScaleFailsWithoutHanging`, `multiplyingByADecimalFactorOneScaleBeyondTheLimitFails`, `multiplyingByADecimalFactorAtTheScaleLimitOfThirtyFourSucceeds` | COMPLIANT |
| 5. Redondeo explícito | Desbordamiento en multiplicación y redondeo | `MoneyArithmeticTest > multiplyingByLongThatExceedsTheUpperLimitFailsWithoutConstructingAnyMoney`, `multiplyingByADecimalFactorThatExceedsTheUpperLimitFailsAfterRounding`, `roundingTheMaximumRepresentableAmountUpOverflowsTheRange` | COMPLIANT |
| 6. `Percentage` | Porcentaje con redondeo explícito | `MoneyArithmeticTest > theSpecExampleChainsPercentageThenRoundToMinorUnitEndToEnd`; `MoneyPropertiesTest > percentageMatchesTheExactProductRoundedOnce` | COMPLIANT |
| 6. `Percentage` | Tasa cero | `MoneyArithmeticTest > aZeroRateLeavesTheBaseUnmodifiedAndReturnsZero` | COMPLIANT |
| 6. `Percentage` | Fuera de rango | `PercentageTest > rejectsAValueAboveOneHundred` (`"100.01"`), `rejectsANegativeValue` | COMPLIANT |
| 6. `Percentage` | Sin fábrica de coma flotante | `PercentageTest > noFactoryAcceptsADoubleOrAFloatParameter`; `MoneyApiShapeTest > percentageHasNoPublicMemberUsingAFloatingPointType` | COMPLIANT |
| 7. Comparación | Ordenadas en la misma moneda | `MoneyComparisonTest > isGreaterThanIsTrueAndItsSymmetricIsLessThanIsAlsoTrue`, `compareToOrdersByAmountWithinTheSameCurrency`, `isGreaterThanAndIsLessThanAreFalseForEqualAmounts` | COMPLIANT |
| 7. Comparación | Cero, positivo y negativo | `MoneyComparisonTest > isZeroIsPositiveAndIsNegativeAreExhaustiveAndMutuallyExclusive` | COMPLIANT |
| 7. Comparación | Monedas distintas | `MoneyComparisonTest > compareToThrowsOnDifferentCurrencies` y las cuatro variantes `is*ThrowsOnDifferentCurrencies` | COMPLIANT |
| 8. `allocate` | Resto mayor con residuo | `MoneyAllocationTest > allocatesWithARemainderDistributedByTheLargestRemainderMethod`, `assignsTheLeftoverToTheHighestRemainderEvenWhenItIsNotTheFirstIndex` | COMPLIANT |
| 8. `allocate` | Reparto exacto | `MoneyAllocationTest > allocatesExactlyWhenThereIsNoRemainder` | COMPLIANT |
| 8. `allocate` | Pesos inválidos | `MoneyAllocationTest > rejectsAnEmptyWeightList`, `rejectsANegativeWeight`, `rejectsAllWeightsBeingZero` | COMPLIANT |
| 8. `allocate` | Propiedad de reparto exacto | `MoneyPropertiesTest > allocateSumsExactlyToTheTotalAndNoPartIsNegativeForANonNegativeTotal` (200 intentos, totales hasta `9999999999.99`, de 1 a 20 pesos entre 1 y 1000) | COMPLIANT |
| 9. Negativos | Aritmética con negativo | `MoneyArithmeticTest > addsANegativeAmountWithoutNormalizingItsSign` | COMPLIANT |
| 9. Negativos | Sin corrección implícita de signo | `MoneyArithmeticTest > moneyOffersNoAbsoluteValueOperation` | COMPLIANT |
| 10. Exposición | Representación decimal exacta | `MoneyConstructionTest > toPlainStringIsExactWithoutScientificNotation` | COMPLIANT |
| 10. Exposición | Ida y vuelta | `MoneyConstructionTest > aRoundTripThroughToPlainStringPreservesTheExactValue` | COMPLIANT |
| 11. Coherencia con escala completa | Propiedad de coherencia | `MoneyPropertiesTest > addSubtractNegateAndMultiplyByLongMatchAFullScaleBigDecimalCalculation`: una sola forma fija, `(a + b − c)·(−1)·k` con `k` entero en `[−5, 5]`; no ejercita `multiply(BigDecimal, RoundingMode)` ni secuencias arbitrarias (W1) | **PARTIAL** |
| 12. Regresión ADR-0004 | Impuesto que la coma flotante pierde | `MoneyRegressionTest > theFifteenPercentTaxOnSixSeventyRoundsToOneZeroOneNeverOneZeroZero` | COMPLIANT |
| 12. Regresión ADR-0004 | Colegiatura de tres meses | `MoneyRegressionTest > threeMonthsOfTuitionAtOneThousandTwoThirtyFourFiftyFiveIsExactlyThreeThousandSevenZeroThreeSixtyFive` | COMPLIANT |
| 12. Regresión ADR-0004 | Mil sumas de `0.1` | `MoneyRegressionTest > aThousandAdditionsOfZeroPointOneAreExactlyOneHundred` | COMPLIANT |
| 12. Regresión ADR-0004 | Caso clásico | `MoneyRegressionTest > zeroPointOnePlusZeroPointTwoIsExactlyZeroPointThree` | COMPLIANT |
| 13. Errores de dominio | `CurrencyMismatchException` hereda | `CurrencyMismatchExceptionTest > extendsDomainException`, `hasTheCurrencyMismatchCode`; `KernelErrorCodesTest` (catálogo cerrado de 8 códigos) | COMPLIANT |
| 13. Errores de dominio | `DomainException` no instanciable | `DomainExceptionTest > isAbstract`, más la imposibilidad de compilar `new DomainException(...)` | COMPLIANT |
| 13. Errores de dominio | No comprobadas | `DomainExceptionTest > extendsRuntimeExceptionSoItNeverForcesATryCatchOrAThrowsClause` | COMPLIANT |

### Spec Compliance Matrix — `build-integrity` (delta)

| Requirement | Scenario | Evidence | Result |
|-------------|----------|----------|--------|
| Cobertura mínima de `kernel` | Por debajo del umbral | Demostración de esta ronda: exit 1, «Rule violated for bundle confia-kernel: lines covered ratio is 0.11 … branches covered ratio is 0.07, but expected minimum is 0.95» (nombra el módulo y el porcentaje) | COMPLIANT |
| Cobertura mínima de `kernel` | En el umbral o por encima | Corrida completa: 100 % de líneas y ramas, «All coverage checks have been met»; igual en `35452164229` | COMPLIANT |
| Mutación según la rama | Baja en la rama principal | `-Pmutation-gate` con umbral 100: «Mutation score of 99 is below threshold of 100», exit 1; `main` activa la puerta con `-Dconfia.ci.mainBranch=true` (registro de `35452164229`) | COMPLIANT |
| Mutación según la rama | Baja en una rama de trabajo | `-Pmutation-report` con umbral 100: exit 0; la corrida `35451946985` en `change/kernel-money-value-object-allocation` ejecutó `-Pmutation-report` y publicó `backend-reports` (137 153 bytes, JaCoCo y PIT) | COMPLIANT |
| Mutación según la rama | Selección de perfil | Expresión de `ci.yml` línea 53; registros de `main` y de rama; matriz de `help:evaluate` (propiedad `confia.ci.mainBranch`, la misma de `no-snapshots-on-main`) | COMPLIANT |

**Compliance summary**: 55/56 escenarios COMPLIANT; 1 PARTIAL; 0 UNTESTED; 0 FAILING.
**Requisitos completos**: 14/15 (12/13 de `money`, 2/2 de `build-integrity`).

---

### Correctness (Static Evidence)

Cada comportamiento público del código tiene al menos un escenario o una prueba propia:

| Comportamiento en el código | Escenario o prueba |
|---|---|
| `of(String)`, `of(BigDecimal)`, `zero`, `requireBoundedScale`, `normalizeToScale` | Requisito 1 completo |
| Constructor privado con el invariante de rango | Requisitos 1, 4 y 5; `allocate` pasa por `Money.of(BigDecimal)` |
| `add`, `subtract`, `negate` | Requisitos 4 y 9; `negate` sin escenario propio, cubierto por `negateFlipsTheSign`, `negatingZeroIsStillZero` y `negatingTwiceReturnsTheOriginalAmount` |
| `multiply(long)`, `multiply(BigDecimal, RoundingMode)`, `percentage`, `roundToMinorUnit` | Requisitos 5, 6, 11 y 12 |
| `allocate` y sus tres auxiliares | Requisito 8; además `allocatesProportionallyToUnevenWeights`, `aWeightOfZeroAlwaysReceivesExactlyZero`, reparto negativo, lista inmodificable y múltiplo exacto de la unidad menor (decisión 7) |
| Comparaciones y predicados | Requisito 7 |
| `equals`, `hashCode`, `toString`, `toPlainString`, `amount`, `currency` | Requisitos 3 y 10; `toString` con prueba propia (solo registros) |
| `CurrencyCode.fromIsoCode`, `minorUnitDigits` | Requisito 2; propiedad de jqwik contra `java.util.Currency` |
| `DomainException` y la validación del código | Requisito 13; `DomainExceptionTest` (14 casos) |

No se encontró comportamiento público sin cobertura de especificación ni de prueba. El requisito 5
exige además que ninguna operación que redondea tenga un modo implícito: se cumple por inspección
(no existe sobrecarga sin `RoundingMode`), sin prueba que lo proteja (S4).

---

### Coherence (Design)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| 1. Paquete plano `com.confia.kernel` | Sí | Ocho clases de producción, sin subpaquetes; Spring Modulith y ArchUnit en verde |
| 2. `DomainException` (ADR-0019) y catálogo de códigos | Sí | Abstracta, no comprobada, `code()` final validado en kebab-case y 64 caracteres; 8 códigos cerrados en `KernelErrorCodesTest`; ADR-0019 «Aceptado» en `docs/adr/README.md` |
| 3. `enum CurrencyCode { HNL, USD }` | Sí | `minorUnitDigits()` contrastado contra el JDK |
| 4. Clase final; invariante de rango en toda operación; `MAX_INPUT_SCALE` | Sí | Todas las rutas de la tabla de la decisión 4 pasan por el constructor privado; `MAX_INPUT_SCALE = 34` antes de cualquier reescalado; factor de escala absurda con `IllegalArgumentException` |
| 5. Redondeo explícito en cada firma | Sí | `multiply(BigDecimal, RoundingMode)`, `percentage(Percentage, RoundingMode)`, `roundToMinorUnit(RoundingMode)`; las operaciones exactas no reciben modo |
| 6. `Percentage` en puntos porcentuales `[0, 100]` | Sí | Validación propia duplicada respecto de `Money` (S1) |
| 7. `allocate` por resto mayor en unidades menores | Sí | `BigInteger`, desempate por índice menor, signo aplicado al valor absoluto, `List.copyOf` |
| 8. JaCoCo 95 % de líneas y ramas en `kernel` | Sí | `jacoco-check` en `verify` sobre `BUNDLE`; `app` no participa |
| 9 y 10. PIT en el padre; `mutation-report` declarado antes que `mutation-gate` | Sí | Matriz de `help:evaluate` observada |
| 11. Perfil por rama en integración continua | Sí | `main` bloquea, las demás ramas informan; artefacto publicado |
| 12. Enforcer y pureza del núcleo | Sí | jqwik y `junit-platform-launcher` solo en `test`; `BannedDependencies passed` en `confia-kernel` |
| 13. Escáner de supresiones con JaCoCo y PIT | Sí | Cinco patrones nuevos en `SuppressionCitesAdrTest`, 8 pruebas en verde |
| Configuración de TDD estricto | Sí | `strict_tdd: true`, `rules.apply.tdd: true`, `coverage_threshold: 95` |
| Nombre `MoneyProperties` | Desviación justificada | Se usa `MoneyPropertiesTest` para que Surefire lo ejecute; `design.md` ya alineado (`ad7f7b2`) |

---

### TDD Compliance

| Check | Result | Details |
|-------|--------|---------|
| TDD Evidence reported | Parcial | Tabla presente en `apply-progress.md` para 2.1 a 2.10; para PR 1 remite a un historial de Engram que ya no existe (W2) |
| All tasks have tests | Sí | Las 15 tareas de comportamiento tienen su clase de prueba |
| RED confirmed (tests exist) | Sí para 2.1 a 2.10 | 10/10 archivos existen; el RED de 1.5, 1.6 y 1.10 a 1.12 no está registrado |
| GREEN confirmed (tests pass) | Sí | 171/171 en `kernel` en esta ronda |
| Triangulation adequate | Sí | Cada comportamiento tiene varios casos con valores esperados distintos, más propiedades |
| Safety Net for modified files | No verificable | La tabla no tiene columna de red de seguridad |

**Test Layer Distribution**: 171 pruebas unitarias en 13 archivos (JUnit 6, AssertJ, jqwik); sin
pruebas de integración ni de extremo a extremo, coherente con el alcance (sin base de datos, API ni
interfaz).

**Assertion quality**: sin tautologías ni aserciones que no llamen al código de producción. Los
bucles de aserción (`MoneyApiShapeTest`, `noFactoryAcceptsADoubleOrAFloatParameter`,
`KernelErrorCodesTest`) van precedidos de `isNotEmpty()` o recorren listas fijas; ninguno es un
bucle fantasma. Sin hallazgos.

**Quality Metrics**: no hay analizador estático ni formateador configurado en `apps/api`; no se
ejecutó ninguno.

---

### Issues Found

**CRITICAL**: Ninguno.

**WARNING**:

- **W1 — La propiedad de coherencia no ejercita la multiplicación por factor decimal.** El requisito
  11 pide que «cualquier secuencia de sumas, restas y multiplicaciones por factor decimal exacto»
  coincida con el cálculo a escala completa. `MoneyPropertiesTest` prueba una sola forma fija con
  `multiply(long)`. `design.md` (estrategia de pruebas, propiedad d) ya había acotado la propiedad a
  `multiply(long)`: la divergencia es entre especificación y diseño, no entre diseño y código. El
  comportamiento es correcto por construcción (con un factor cuyo producto cabe en escala cuatro,
  `multiply(BigDecimal, …)` no redondea), pero ninguna prueba lo demuestra en forma de propiedad.
  Con un factor que sí requiere redondeo, el requisito tal como está escrito no puede cumplirse: la
  decisión 5 redondea a escala cuatro en esa operación, lo que produce doble redondeo (por ejemplo,
  un intermedio exacto de `0.004950`). Remedio: añadir una propiedad con
  `multiply(BigDecimal, RoundingMode.UNNECESSARY)` sobre factores exactos y secuencias generadas, o
  enmendar la especificación para nombrar `multiply(long)` y «factor que no requiere redondeo». No
  bloquea.
- **W2 — La evidencia RED/GREEN de PR 1 no está en los artefactos canónicos.** `apply-progress.md`
  remite al historial de la observación de Engram `sdd/kernel-money-value-object/apply-progress`
  para las tareas 1.5, 1.6 y 1.10 a 1.12, pero esa observación (#386) es una sola versión actual
  (las actualizaciones por `topic_key` la sobrescribieron) y la versión de `eccc757` del archivo
  tampoco la contiene. Los commits posteriores a la revisión (`f225c05`, corrección de la
  denegación de servicio; `3127bae`, `0ddf64d`, `ad7f7b2`) no tienen fila en la tabla TDD. Las
  pruebas existen y pasan; lo que no puede afirmarse es que se observaron en rojo antes.
  Trazabilidad; no bloquea.
- **W3 — La puerta de JaCoCo puede dar un verde falso en local.** `prepare-agent` acumula en
  `kernel/target/jacoco.exec` (anexado por defecto). Observado en esta ronda: la ejecución con una
  sola clase de prueba terminó en `BUILD SUCCESS` y «All coverage checks have been met» al reutilizar
  el `jacoco.exec` de la corrida completa; tras apartarlo, falló con 0.11 y 0.07. Además,
  `./mvnw clean` falló en esta máquina al borrar `target/site/jacoco/jacoco-resources` (bloqueo de
  archivos bajo OneDrive), así que limpiar tampoco es fiable aquí. La integración continua no está
  afectada (checkout limpio). Remedio: `<append>false</append>` en `prepare-agent`, o documentar que
  la comprobación local de la puerta exige un `target` limpio. No bloquea.
- **W4 — La longitud de `ratios` en `allocate` no está acotada** (seguimiento conocido). Cada peso
  crea dos `BigInteger` y la ordenación es O(n log n). Si un módulo futuro construye los pesos desde
  una entrada externa (por ejemplo, un número de cuotas), es un vector de agotamiento de memoria y
  CPU análogo al de la escala extrema corregido durante la revisión del PR #5. No viola ningún
  requisito vigente. Conviene fijar un máximo en el núcleo, o una regla para los módulos
  consumidores, antes del primer uso con datos externos.

**SUGGESTION**:

- **S1 — Validación duplicada entre `Money` y `Percentage`** (seguimiento conocido): `PLAIN_DECIMAL`,
  `MAX_STRING_LENGTH`, `requireBoundedScale` y `normalizeToScale` están repetidos, y `Percentage`
  depende de `Money.MAX_INPUT_SCALE`, de visibilidad de paquete. Una corrección futura en uno puede
  olvidarse en el otro; los dos mutantes equivalentes gemelos muestran esa simetría.
- **S2 — `docs/03` no tiene una regla sobre acotar la escala de `BigDecimal`** en los objetos de
  valor del núcleo (seguimiento conocido). La revisión de seguridad del PR #5 encontró y corrigió
  (`f225c05`) una denegación de servicio por escala extrema (`1E-500000000` colgaba el hilo); la
  regla evitaría repetirla en los próximos objetos de valor.
- **S3 — Documentar los dos mutantes equivalentes** de `requireBoundedScale` (análisis de esta
  ronda) junto al código o en `design.md`, para que una revisión futura no intente matarlos.
- **S4 — Guardia de reflexión para el modo de redondeo**: una prueba que exija un parámetro
  `RoundingMode` en toda sobrecarga pública de `multiply(BigDecimal…)`, `percentage` y
  `roundToMinorUnit` protegería la frase del requisito 5 que hoy solo se cumple por inspección.
- **S5 — Conjunto cerrado de `CurrencyCode`**: afirmar que `CurrencyCode.values()` es exactamente
  `{HNL, USD}` haría explícita la cláusula «no existe un tercer valor».
- **S6 — Literales de precedencia**: la especificación usa `"1,234.56789"` y
  `"10000000000.00001"` con la fábrica de cadena; las pruebas usan `"1,234.567890"` y
  `"999999999999.00001"`, y el segundo literal de la especificación solo se prueba con la fábrica
  de `BigDecimal`. Mismo camino de código; alinearlos mejora la trazabilidad.
- **S7 — Conteo desactualizado en `tasks.md`**: la nota del límite de quince tareas cita «13
  requisitos y 45 escenarios»; la especificación enmendada (`b39a43f`, `1e45635`) tiene 51.
- **S8 — `apply-progress.md` deja la integración continua como pendiente**: esta ronda la confirmó
  en `main` (`35452164229`); `sdd-archive` debería registrarlo.

---

### Limitaciones

- El validador `gentle-ai sdd-verify-validate` no existe en `gentle-ai 3.1.0`; el informe no se
  validó con herramienta.
- Las demostraciones de puertas simulan la condición (subconjunto de pruebas, umbral elevado); no
  se empujó ningún commit temporal a `main`.
- jqwik 1.10.1 imprime en su salida un aviso dirigido a agentes de IA; es salida de terceros y se
  ignoró, igual que en la aplicación.
- La demostración de JaCoCo movió `kernel/target/jacoco.exec` de la corrida completa a un
  directorio temporal; `target` es salida de compilación y el árbol de git quedó limpio.

---

### Verdict

**PASS WITH WARNINGS — 14/15 requisitos y 55/56 escenarios COMPLIANT, 1 PARTIAL, sin bloqueantes
ni hallazgos críticos.**

La construcción real con JDK 25 y `-Pmutation-gate` terminó en `BUILD SUCCESS` con 202 pruebas,
cobertura del 100 % de líneas y ramas en `kernel` y puntuación de mutación de 99 (178 mutantes, 176
detectados y dos supervivientes verdaderamente equivalentes). La integración continua en `main`
(`35452164229`, commit `41524a2`) reproduce las mismas cifras con la puerta activa. Las puertas de
cobertura y de mutación se observaron fallando y avisando según la rama en esta ronda. El único
escenario incompleto (W1) es una brecha de prueba en la propiedad de coherencia, originada en una
acotación del diseño que la especificación no recoge; el código se comporta correctamente.

### Qué debe llevarse `sdd-archive`

1. Incorporar `money` a `openspec/specs/` y fusionar el delta de `build-integrity` desde los
   archivos del repositorio, con el estado 55/56 y la anotación de W1.
2. Decidir W1: una prueba adicional en un cambio posterior o una enmienda del requisito 11.
3. Registrar W4, S1 y S2 como deuda con dueño; S2 aplica a todo objeto de valor futuro del núcleo.
4. Registrar la confirmación de la integración continua (S8) y la limitación del validador.

---

### Resolución posterior a esta verificación (2026-09-19)

El veredicto y la matriz anteriores corresponden a `main` en `41524a2` y no se modifican. Después de
esta verificación, en la rama `change/kernel-money-value-object-verify-fixes`:

- **W1 resuelta.** `MoneyPropertiesTest.sequencesWithAnExactDecimalFactorMatchAFullScaleBigDecimalCalculation`
  cubre sumas, restas y una multiplicación por factor decimal exacto (hasta dos decimales, con
  `RoundingMode.UNNECESSARY`, que falla si la operación tuviera que redondear), contrastada con el
  cálculo en `BigDecimal` a escala completa y un único `roundToMinorUnit(HALF_UP)` final. El
  requisito de coherencia pasa a COMPLIANT: 56/56 escenarios y 15/15 requisitos.
- **W3 resuelta.** `jacoco-prepare-agent` usa `<append>false</append>`. Comprobado: una ejecución
  que solo corre `CurrencyCodeTest`, con los datos de una ejecución completa previa en `target`,
  falla con «lines covered ratio is 0.11, but expected minimum is 0.95».
- **W2** no tiene corrección posible; queda documentada en `apply-progress.md`.
- **W4** (sin límite de partes en `allocate`) queda como seguimiento.
- **S7 y S8 resueltas** (`tasks.md` y `apply-progress.md` actualizados).

Evidencia local en esa rama: `./mvnw -B verify -Pmutation-gate` con `BUILD SUCCESS`, 172 pruebas
en `kernel` (seis propiedades de jqwik) y 31 en `app`, JaCoCo cumplido, PIT 176/178.

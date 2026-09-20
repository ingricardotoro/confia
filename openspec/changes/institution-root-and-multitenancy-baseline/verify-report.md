```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:07028887cc4aa86537ca03e71fd0fbd91645f615a2228ae7154cc666bc6fce8c
verdict: pass_with_warnings
blockers: 0
critical_findings: 0
requirements: 14/14
scenarios: 39/39
test_command: ./mvnw -B verify -Pmutation-gate
test_exit_code: 0
test_output_hash: sha256:9c4ec1f887763f5f4def0031df230e32af823fe77fdb14f11690b345eaf19da4
build_command: ./mvnw -B verify -Pmutation-gate
build_exit_code: 0
build_output_hash: sha256:9c4ec1f887763f5f4def0031df230e32af823fe77fdb14f11690b345eaf19da4
```

## Verification Report — Ronda 1 (final)

**Change**: institution-root-and-multitenancy-baseline (F0, cambio 4 de 13)
**Versión de las especificaciones**: `specs/organization/spec.md` (blob `907c5c8ab37b1e837e23408c71bd357ae24513b7`, 11 requisitos, 29 escenarios) y `specs/build-integrity/spec.md` (blob `755110bf5e951b60147b1444c0914ec00a3396f5`, delta con 3 requisitos añadidos, 10 escenarios)
**Mode**: Strict TDD (`openspec/config.yaml`: `strict_tdd: true`, `rules.apply.tdd: true`)
**Commit verificado**: `1194ac923347c343a9be937de71a0f1b9274ec3a`, rama `main` (fusión del PR #13; los PR #9, #10, #11, #12 y #13 ya están fusionados), árbol limpio
**Comando real**: `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" ./mvnw -B verify -Pmutation-gate`, ejecutado en `apps/api`
**`evidence_revision`**: SHA-256 de las cuatro líneas `commit=<HEAD>`, `spec_blob_organization=<blob>`, `spec_blob_build_integrity=<blob>` y `build_output=sha256:<hash de la salida>`, en ese orden y terminadas en salto de línea.

Criterio de conteo, idéntico al de los cambios 1 y 2: un escenario cuenta como completado solo si es
COMPLIANT; un requisito cuenta como completado solo si todos sus escenarios lo son.

**Nota sobre el validador.** `gentle-ai 3.1.0` ya no expone el comando `sdd-verify-validate` (fue
retirado). **Este informe no está validado con ninguna herramienta**; el bloque YAML conserva el
formato de los cambios 1 y 2 por trazabilidad, no como certificado.

---

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 32 (7 de PR A, 11 de PR B1 incluida la 2.3b, 10 de PR B2, 4 de PR C) |
| Tasks complete | 32 |
| Tasks incomplete | 0 |

Contadas con `grep -cE "^- \[x\]" tasks.md` → 32 y `grep -cE "^- \[ \]" tasks.md` → 0. Las marcas se
contrastaron con evidencia observable en el reactor, no solo con las casillas:

- **1.2 a 1.4**: `MonetaryFloatingPointTest` existe con sus doce pruebas y los tres fixtures
  permanentes de `com.confia.architecture.fixture.monetary`; las doce pasan en esta ronda.
- **1.1, 1.5 y 2.7**: las dos reglas de JaCoCo y la adhesión a PIT están declaradas en
  `apps/api/app/pom.xml` (líneas 63–140) y se observaron **rompiendo la construcción** en esta ronda
  (sección «Demostración de puertas»). El comentario de `coverage_threshold` está actualizado
  (`openspec/config.yaml`, línea 42).
- **2.1 a 2.6**: `InstitutionId`, `Institution`, `InvalidInstitutionException`,
  `InstitutionStateException` y los dos puertos existen; `LayeredArchitectureTest` ya no contiene
  ninguna llamada a la supresión de ADR-0018, el inventario tiene exactamente dos entradas
  `OPTIONAL_LAYER` y el escáner exige `0 == 0` y `2 == 2` (verificado leyendo el código y con las
  diez pruebas de `SuppressionCitesAdrTest` en verde).
- **3.1 a 3.9**: la firma de `create(...)` tiene los ocho parámetros; los trece códigos están en
  producción; `toString()` no expone RTN ni dirección.
- **4.1 a 4.4**: `ResolveCurrentInstitution` y sus dos errores existen; las ocho pruebas de
  `ResolveCurrentInstitutionTest` pasan.
- **1.6, 2.9, 3.10, 4.3**: mediciones de tamaño registradas con la decisión del propietario (división
  de PR B1 en B1 y B1-gates); se aceptan como registro histórico.
- **1.7, 2.10, 3.10, 4.4**: `apply-progress.md` deja la integración continua como «pendiente del
  empuje». Esta ronda la confirmó en `main` (corrida `35538036392`, ver más abajo).
- **Fuera del libro mayor**: el commit `f64a5ec`, posterior a la última tarea, añadió una prueba a
  cada catálogo de códigos y una nota al documento de interfaz. No tiene tarea ni fila de evidencia
  (ver W3).

---

### Build & Tests Execution

**Build**: Passed — `BUILD SUCCESS`, exit 0, 1 min 4 s, 686 líneas de salida, sobre el árbol limpio
en `1194ac9`.

```text
[INFO] CONFIA API Parent .................................. SUCCESS [  0.514 s]
[INFO] CONFIA Kernel ...................................... SUCCESS [ 36.392 s]
[INFO] CONFIA API ......................................... SUCCESS [ 27.437 s]
[INFO] BUILD SUCCESS
```

**Tests**: 276 passed / 0 failed / 0 skipped (177 en `confia-kernel`, 99 en `confia-api`).

```text
confia-kernel (177): CurrencyCodeTest 7 (+1 jqwik), CurrencyMismatchExceptionTest 3,
DomainExceptionTest 14, InstitutionIdTest 4, InvalidMoneyAmountExceptionTest 5,
KernelErrorCodesTest 5, MoneyAllocationTest 13, MoneyApiShapeTest 2, MoneyArithmeticTest 33,
MoneyComparisonTest 12, MoneyConstructionTest 39, MoneyRegressionTest 4, PercentageTest 29,
MoneyPropertiesTest 6 (jqwik)

confia-api (99): EmptyShouldExceptionInventoryTest 1, LayeredArchitectureTest 2,
MonetaryFloatingPointTest 12, NoCrossModuleDomainImportsTest 2, NoCyclesTest 2,
NoStandardStreamAccessTest 2, NoTechnicalLayerPackageNamesTest 2, SpringModulithVerificationTest 2,
SuppressionCitesAdrTest 10, ConfiaApplicationTest 10, ResolveCurrentInstitutionTest 8,
InstitutionCreationTest 30, InstitutionLifecycleTest 9, OrganizationErrorCodesTest 7
```

`apply-progress.md` y las evidencias de las tareas 4.2 y 4.4 registran 274 pruebas (176 + 98); la
diferencia de dos es el commit `f64a5ec` posterior (ver W3).

**Coverage (JaCoCo 0.8.14)**. Las tres reglas se evaluaron y todas pasaron: «All coverage checks
have been met» en `confia-kernel` y en `confia-api`, sin ningún mensaje `Rule violated`.

| Regla | Ámbito | Umbral | Medido | Resultado |
|---|---|---|---|---|
| `BUNDLE` (`kernel/pom.xml`) | `confia-kernel` | 0.95 líneas y ramas | líneas 199/199 = **100 %**, ramas 94/94 = **100 %** | Cumple |
| `BUNDLE` (`app/pom.xml`) | `confia-api` completo | 0.80 líneas y ramas | líneas 126/130 = **96.9 %**, ramas 33/35 = **94.3 %** | Cumple |
| `PACKAGE` (`app/pom.xml`, `com.confia.*.domain`, `com.confia.*.domain.*`) | `com.confia.organization.domain` | 0.95 líneas y ramas | líneas 79/79 = **100 %**, ramas 20/20 = **100 %** | Cumple |

Desglose de `confia-api` (`app/target/site/jacoco/jacoco.csv`):

| Clase | Paquete | Líneas | Ramas | Instrucciones |
|---|---|---|---|---|
| `Institution` | `organization.domain` | 60/60 | 20/20 | 213/213 |
| `InvalidInstitutionException` | `organization.domain` | 11/11 | — | 59/59 |
| `InstitutionStateException` | `organization.domain` | 4/4 | — | 17/17 |
| `InstitutionNotFoundException` | `organization.domain` | 2/2 | — | 5/5 |
| `InstitutionInactiveException` | `organization.domain` | 2/2 | — | 5/5 |
| `ResolveCurrentInstitution` | `organization.application` | 12/12 | 2/2 | 36/36 |
| `ConfiaApplication` | `bootstrap` | 15/19 | 4/6 | 66/78 |
| `AppProfile` y el resto de `bootstrap` | `bootstrap` | 20/20 | 7/7 | 91/91 |

`com.confia.organization.application` queda **fuera** de los `<includes>` de la regla `PACKAGE`
(`com.confia.*.domain`, `com.confia.*.domain.*`): `ResolveCurrentInstitution` tiene cobertura
completa por sus pruebas, no por una puerta, exactamente como exige la tarea 4.4. Las cuatro líneas
y dos ramas sin cubrir del módulo `app` están en `ConfiaApplication`, preexistentes al cambio 3.

**Mutación (PIT 1.30.0, `STRONGER`, `-Pmutation-gate`, umbral 80)**:

```text
confia-kernel  >> Line Coverage (for mutated classes only): 194/194 (100%)
               >> Generated 178 mutations Killed 176 (99%)
               >> Mutations with no coverage 0. Test strength 99%
               >> Ran 534 tests (3 tests per mutation)

confia-api     >> Line Coverage (for mutated classes only): 75/75 (100%)
               >> Generated 49 mutations Killed 49 (100%)
               >> Mutations with no coverage 0. Test strength 100%
               >> Ran 113 tests (2.31 tests per mutation)
```

Conteo por estado en los dos `mutations.xml`:

| Módulo | KILLED | TIMED_OUT | SURVIVED | NO_COVERAGE |
|---|---|---|---|---|
| `confia-kernel` (`com.confia.kernel.*`) | 175 | 1 | 2 | 0 |
| `confia-api` (`com.confia.*.domain.*`) | 49 | 0 | 0 | 0 |

**`com.confia.organization.domain` no tiene ningún mutante superviviente**: 49 de 49 muertos, 100 %,
muy por encima del umbral de 80 del perfil `mutation-gate`. Detalle de los tres mutantes no `KILLED`,
todos en `kernel` y **todos ajenos a este cambio**:

| Estado | Clase y método | Línea | Mutador | Juicio |
|---|---|---|---|---|
| `TIMED_OUT` | `Money.distributeByLargestRemainder` | 313 | `RemoveConditionalMutator_ORDER_IF` (comparación → `true`) | Detectado: el bucle se vuelve infinito y PIT lo cuenta como muerto |
| `SURVIVED` | `Money.requireBoundedScale` | 108 | `ConditionalsBoundaryMutator` (`scale() < 0` → `<= 0`) | **Equivalente**, ya analizado y aceptado en el cambio 2 |
| `SURVIVED` | `Percentage.requireBoundedScale` | 77 | `ConditionalsBoundaryMutator` (`scale() < 0` → `<= 0`) | **Equivalente**, ya analizado y aceptado en el cambio 2 |

Los dos supervivientes son exactamente los dos mutantes equivalentes documentados en
`openspec/changes/archive/2026-09-19-kernel-money-value-object/verify-report.md` («Análisis de
equivalencia»): con escala 0, mutante y original terminan lanzando la misma excepción con el mismo
código. Este cambio no los introduce ni los agrava; la puntuación de 99 % de `kernel` es idéntica a
la del cambio 2.

**Integración continua en el commit verificado**: `gh run list --branch main --limit 3` muestra
`35538036392` (fusión del PR #13, `headSha` `1194ac923347c343a9be937de71a0f1b9274ec3a` confirmado con
`gh run view`) en `success`, y las dos anteriores (`35538022801` del PR #12 y `35537991884` del PR
#11) en `cancelled` por haber sido superadas por empujes posteriores a `main`. En `35538036392`:
`backend verify: success`, `security scanning: success`. Líneas de puerta del registro:

```text
./mvnw --batch-mode verify -Dconfia.ci.mainBranch=true
[INFO] --- enforcer:3.6.3:enforce (enforce-no-snapshots-on-main) @ confia-api-parent ---
[INFO] --- enforcer:3.6.3:enforce (enforce-no-snapshots-on-main) @ confia-kernel ---
[INFO] Tests run: 177, Failures: 0, Errors: 0, Skipped: 0          (confia-kernel)
[INFO] --- jacoco:0.8.14:check (jacoco-check) @ confia-kernel ---
[INFO] All coverage checks have been met.
[INFO] --- pitest:1.30.0:mutationCoverage (pit-mutation) @ confia-kernel ---
>> Generated 178 mutations Killed 176 (99%)
[INFO] --- enforcer:3.6.3:enforce (enforce-no-snapshots-on-main) @ confia-api ---
[INFO] Tests run: 99, Failures: 0, Errors: 0, Skipped: 0           (confia-api)
[INFO] --- jacoco:0.8.14:check (jacoco-check) @ confia-api ---
[INFO] All coverage checks have been met.
[INFO] --- pitest:1.30.0:mutationCoverage (pit-mutation) @ confia-api ---
>> Generated 49 mutations Killed 49 (100%)
[INFO] BUILD SUCCESS
[INFO] Total time:  01:39 min
```

Artefacto `backend-reports` (312 062 bytes) publicado. Las cifras de la integración continua
coinciden exactamente con las de esta ronda local, con la puerta de mutación **activa** en `main`
(`-Dconfia.ci.mainBranch=true`). El criterio de éxito 1 de la propuesta, que `apply-progress.md`
dejaba abierto por no empujar, queda **confirmado**.

---

### Demostración de puertas (reobservada en esta ronda)

Sin modificar ningún archivo del repositorio (`git status` limpio antes y después). Todas en
`apps/api`, con `JAVA_HOME` en JDK 25:

| Demostración | Comando | Observado |
|---|---|---|
| JaCoCo `BUNDLE` de `app` rompe por debajo de 80 % | `./mvnw -B -pl app verify -Dtest=OrganizationErrorCodesTest -Dsurefire.failIfNoSpecifiedTests=false` | Exit 1, `BUILD FAILURE`: «Rule violated for bundle confia-api: lines covered ratio is 0.14, but expected minimum is 0.80» y «branches covered ratio is 0.00» |
| JaCoCo `PACKAGE` rompe por debajo de 95 % | mismo comando | Exit 1: «Rule violated for package com.confia.organization.domain: lines covered ratio is 0.24, but expected minimum is 0.95» y «branches covered ratio is 0.00» |
| `mutation-gate` bloquea | `./mvnw -B -pl app verify -Pmutation-gate -Dconfia.pit.mutationThreshold=101` | Exit 1: «Mutation score of 100 is below threshold of 101», `Generated 49 mutations Killed 49 (100%)` |
| `mutation-report` solo informa | `./mvnw -B -pl app verify -Pmutation-report -Dconfia.pit.mutationThreshold=101` | Exit 0, `BUILD SUCCESS`, «All coverage checks have been met», `Generated 49 mutations Killed 49 (100%)` |

Ambas reglas de JaCoCo nombran el elemento (módulo o paquete) y el porcentaje medido, como exige el
texto de los dos requisitos. La selección de perfil por rama, además, se observa en el registro de la
corrida `35538036392`: `main` ejecuta con `-Dconfia.ci.mainBranch=true` y la puerta activa.

---

### Spec Compliance Matrix — `organization`

Rutas de prueba relativas a `apps/api/app/src/test/java/com/confia/organization/` salvo
`InstitutionIdTest`, que vive en `apps/api/kernel/src/test/java/com/confia/kernel/`. Todas las
pruebas citadas pasaron en esta ronda.

| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| 1. `InstitutionId` en el núcleo | Construcción desde un `UUID` válido | `InstitutionIdTest > constructionFromAValidUuidExposesThatSameUuid` | COMPLIANT |
| 1. `InstitutionId` en el núcleo | Rechazo de un identificador nulo | `InstitutionIdTest > rejectsANullUuid` | COMPLIANT |
| 1. `InstitutionId` en el núcleo | Igualdad por el `UUID` subyacente | `InstitutionIdTest > twoInstancesWithTheSameUuidAreEqualAndShareAHashCode`, `twoInstancesWithDifferentUuidsAreNotEqual` | COMPLIANT |
| 2. Construcción y atributos obligatorios | Construcción exitosa con todos los atributos válidos | `domain/InstitutionCreationTest > happyPathConstructionWithAllValidAttributesSucceeds`, `constructionStripsLeadingAndTrailingWhitespaceFromNames` | COMPLIANT |
| 2. Construcción y atributos obligatorios | Rechazo de un nombre legal en blanco | `InstitutionCreationTest > rejectsALegalNameProvidedAsBlank` | COMPLIANT |
| 2. Construcción y atributos obligatorios | Rechazo de una dirección vacía | `InstitutionCreationTest > rejectsAnEmptyAddress`, `rejectsAnAddressMadeOnlyOfWhitespace` | COMPLIANT |
| 2. Construcción y atributos obligatorios | Longitud máxima del nombre legal y de la dirección | `InstitutionCreationTest > acceptsALegalNameOfExactlyTwoHundredCodePoints`, `rejectsALegalNameOfTwoHundredAndOneCodePoints`, `acceptsAnAddressOfExactlyFiveHundredCodePoints`, `rejectsAnAddressOfFiveHundredAndOneCodePoints` | COMPLIANT |
| 3. RTN presente y numérico | Rechazo de un RTN vacío | `InstitutionCreationTest > rejectsAnEmptyRtn` | COMPLIANT |
| 3. RTN presente y numérico | Rechazo de un RTN no numérico o demasiado largo (y aceptación de 14 dígitos) | `InstitutionCreationTest > rejectsARtnWithNonDigitCharactersOrTooLong` (con `"0801-1990-12345"`, 21 dígitos y la aceptación explícita de 14), `acceptsRtnOfOneAndTwentyDigits` | COMPLIANT |
| 4. Moneda, localización y huso horario | Huso horario reconocido | `InstitutionCreationTest > acceptsARecognizedRegionTimezone` | COMPLIANT |
| 4. Moneda, localización y huso horario | Huso horario de desplazamiento fijo | `InstitutionCreationTest > rejectsAFixedOffsetTimezone` | COMPLIANT |
| 4. Moneda, localización y huso horario | Localización sin idioma | `InstitutionCreationTest > rejectsALocaleWithNoLanguage` | COMPLIANT |
| 4. Moneda, localización y huso horario | Moneda por defecto nula | `InstitutionCreationTest > rejectsANullDefaultCurrency`; además `acceptsEveryEnabledCurrency` cubre el conjunto cerrado | COMPLIANT |
| 5. Nombre comercial opcional | Institución sin nombre comercial | `InstitutionCreationTest > constructionSucceedsWithoutATradeName` | COMPLIANT |
| 5. Nombre comercial opcional | Nombre comercial provisto en blanco | `InstitutionCreationTest > rejectsATradeNameProvidedAsBlank`; los límites 200/201 en `acceptsATradeNameOfExactlyTwoHundredCodePoints`, `rejectsATradeNameOfTwoHundredAndOneCodePoints` | COMPLIANT |
| 6. Activación y desactivación | Desactivación exitosa de una institución activa | `domain/InstitutionLifecycleTest > deactivatingAnActiveInstitutionSucceedsWithoutModifyingOtherAttributes` | COMPLIANT |
| 6. Activación y desactivación | Reactivación exitosa de una institución inactiva | `InstitutionLifecycleTest > reactivatingAnInactiveInstitutionSucceeds` | COMPLIANT |
| 6. Activación y desactivación | Activación de una institución ya activa | `InstitutionLifecycleTest > activatingAnAlreadyActiveInstitutionFailsWithoutModifyingState` | COMPLIANT |
| 6. Activación y desactivación | Desactivación de una institución ya inactiva | `InstitutionLifecycleTest > deactivatingAnAlreadyInactiveInstitutionFailsWithoutModifyingState` | COMPLIANT |
| 7. Jerarquía de errores del módulo | Un error de construcción hereda de `DomainException` | `domain/OrganizationErrorCodesTest > everyExceptionThatDeclaresAnOrganizationCodeIsADomainException`, `everyCodeCarriesTheModulePrefix`; que `DomainException` no es comprobada lo prueba `kernel/DomainExceptionTest > extendsRuntimeExceptionSoItNeverForcesATryCatchOrAThrowsClause` | COMPLIANT |
| 7. Jerarquía de errores del módulo | Un argumento nulo no produce una excepción de dominio | `InstitutionCreationTest > rejectsANullId`, `rejectsANullLegalName`, `rejectsANullRtn`, `rejectsANullAddress`, `rejectsANullDefaultCurrency`, `rejectsANullLocale`, `rejectsANullTimezone` (siete, uno por argumento obligatorio); `isInstanceOf(NullPointerException.class)` excluye por construcción toda subclase de `DomainException` | COMPLIANT |
| 8. Catálogo de códigos del módulo | El catálogo cumple el formato y no tiene repetidos | `OrganizationErrorCodesTest > catalogHasExactlyTheThirteenCodesOfTheCompleteModule`, `everyCodeIsUniqueAcrossTheModule`, `everyCodeFollowsTheKebabCaseFormatAndMaximumLength` | COMPLIANT |
| 8. Catálogo de códigos del módulo | Un código incumple el formato | `OrganizationErrorCodesTest > theKebabCasePatternRejectsAMalformedCode` (añadida en el commit `f64a5ec`, posterior a la última tarea; ver W3) | COMPLIANT |
| 9. Puerto `InstitutionRepository` | Carga con dobles en memoria | `application/ResolveCurrentInstitutionTest > inMemoryRepositoryReturnsARegisteredInstitutionForItsIdentifier` | COMPLIANT |
| 9. Puerto `InstitutionRepository` | Ausencia de resultado para un identificador desconocido | `ResolveCurrentInstitutionTest > inMemoryRepositoryReturnsAnAbsentResultForAnyUnregisteredIdentifier` | COMPLIANT |
| 10. Puerto `CurrentInstitutionProvider` | Resolución con un doble en memoria | `ResolveCurrentInstitutionTest > fixedProviderReturnsItsConfiguredIdentifier` | COMPLIANT |
| 11. Caso de uso de resolución | Resolución exitosa de una institución activa | `ResolveCurrentInstitutionTest > resolvesTheActiveInstitutionForTheCurrentRequest` | COMPLIANT |
| 11. Caso de uso de resolución | Rechazo de una institución inexistente | `ResolveCurrentInstitutionTest > rejectsResolutionWhenNoInstitutionIsRegisteredForTheCurrentIdentifier` | COMPLIANT |
| 11. Caso de uso de resolución | Rechazo de una institución inactiva | `ResolveCurrentInstitutionTest > rejectsResolutionWhenTheResolvedInstitutionIsInactive` | COMPLIANT |

El escenario diferido del requisito 3 (formato exacto del RTN vigente del SAR) sigue declarado como
diferido en la propia especificación; no se cuenta ni como cumplido ni como incumplido, igual que el
escenario diferido W2 del cambio 1.

### Spec Compliance Matrix — `build-integrity` (delta)

| Requirement | Scenario | Evidence | Result |
|-------------|----------|----------|--------|
| 1. Prohibición de coma flotante y de `BigDecimal.equals` | Fixture que construye `BigDecimal` desde `double` | `architecture/MonetaryFloatingPointTest > rejectsTheFixtureFloatingPointBigDecimalConstruction`, contra `fixture/monetary/FloatingPointBigDecimal` (tres llamadas: `new BigDecimal(double)`, `new BigDecimal(double, MathContext)`, `BigDecimal.valueOf(double)`) | COMPLIANT |
| 1. Prohibición de coma flotante y de `BigDecimal.equals` | Fixture que invoca `BigDecimal.equals` fuera de `Money` | `MonetaryFloatingPointTest > rejectsTheFixtureRawBigDecimalComparison`, contra `fixture/monetary/RawBigDecimalComparison`; `moneyIsExcludedFromTheEqualsRuleSelection` afirma que `Money` queda fuera de la selección | COMPLIANT |
| 1. Prohibición de coma flotante y de `BigDecimal.equals` | Fixture con un campo `double` en un tipo monetario | `MonetaryFloatingPointTest > rejectsTheFixtureFloatingPointField`, `rejectsTheFixtureFloatingPointReturn`, `rejectsTheFixtureFloatingPointParameter`, contra `fixture/monetary/FloatingPointPriceTag` | COMPLIANT |
| 1. Prohibición de coma flotante y de `BigDecimal.equals` | Código de producción sin infracciones | `MonetaryFloatingPointTest > productionCodeNeverConstructsBigDecimalFromFloatingPoint`, `productionCodeNeverCallsBigDecimalEqualsOutsideMoney`, `productionCodeNeverDeclaresAFloatingPointFieldInAMonetaryType`, la variante de retorno y la de parámetro, más `productionImportIncludesTheKernelMonetaryTypes` (la premisa de no vacuidad: `productionClasses()` contiene `Money` y `Percentage`). Ninguna de las cinco reglas lleva excepción de conjunto vacío: `SuppressionCitesAdrTest` exige cero llamadas a esa supresión | COMPLIANT |
| 2. Cobertura global mínima del módulo `app` | Cobertura por debajo del umbral | Demostración de esta ronda: exit 1, «Rule violated for bundle confia-api: lines covered ratio is 0.14 … branches covered ratio is 0.00, but expected minimum is 0.80» (nombra el módulo y el porcentaje) | COMPLIANT |
| 2. Cobertura global mínima del módulo `app` | Cobertura en el umbral o por encima | Corrida completa: 96.9 % líneas y 94.3 % ramas, «All coverage checks have been met»; igual en `35538036392` | COMPLIANT |
| 3. Cobertura y mutación del `domain` de cada módulo | Cobertura de `organization.domain` por debajo del umbral | Demostración de esta ronda: exit 1, «Rule violated for package com.confia.organization.domain: lines covered ratio is 0.24 … branches covered ratio is 0.00, but expected minimum is 0.95» | COMPLIANT |
| 3. Cobertura y mutación del `domain` de cada módulo | Cobertura de `organization.domain` en el umbral o por encima | Corrida completa: 100 % líneas (79/79) y 100 % ramas (20/20); igual en `35538036392` | COMPLIANT |
| 3. Cobertura y mutación del `domain` de cada módulo | Mutación baja en la rama principal | `-Pmutation-gate` con umbral 101: «Mutation score of 100 is below threshold of 101», exit 1; `main` activa la puerta con `-Dconfia.ci.mainBranch=true` (registro de `35538036392`) | COMPLIANT |
| 3. Cobertura y mutación del `domain` de cada módulo | Mutación baja en una rama de trabajo | `-Pmutation-report` con el mismo umbral 101: exit 0, `BUILD SUCCESS`, `Generated 49 mutations Killed 49 (100%)` informado y no bloqueante | COMPLIANT |

**Compliance summary**: 39/39 escenarios COMPLIANT; 0 PARTIAL; 0 UNTESTED; 0 FAILING.
**Requisitos completos**: 14/14 (11/11 de `organization`, 3/3 de `build-integrity`).

---

### Correctness (Static Evidence)

Cada comportamiento público del código nuevo tiene al menos un escenario o una prueba propia:

| Comportamiento en el código | Escenario o prueba |
|---|---|
| `InstitutionId(UUID)`, su constructor compacto, `value()`, igualdad generada | Requisito 1 completo; PIT muta el constructor compacto y lo mata |
| `Institution.create(...)` con sus ocho parámetros y sus siete `requireNonNull` | Requisitos 2, 3, 4 y 5; siete pruebas de nulo, una por argumento obligatorio |
| `requireValidText(...)` (compartido por `legalName`, `tradeName`, `address`) | Requisitos 2 y 5 con los límites exactos 200/201 y 500/501 en puntos de código |
| `RTN_PATTERN` (`^[0-9]{1,20}$`) | Requisito 3 con 1, 14, 20 y 21 dígitos, guiones y cadena vacía |
| `activate()`, `deactivate()`, `isActive()` | Requisito 6, los cuatro escenarios |
| Los ocho accesores de atributo más `isActive()` | Requisito 2 (caso feliz) y cada invariante en su prueba |
| `equals`, `hashCode` (por `id`) | Sin escenario propio en la especificación (decisión 5 del diseño); cubiertos por cinco pruebas de `InstitutionLifecycleTest` (autoigualdad, tipo distinto, `null`, `id` distinto, `hashCode` igual al de `id()`) — ver S6 |
| `toString()` sin RTN ni dirección | Sin escenario propio (decisión 5; `CLAUDE.md` regla 11); `InstitutionLifecycleTest > toStringShowsIdAndTradeNameButNeverRtnOrAddress` |
| Orden de precedencia de invariantes | Sin escenario propio; `InstitutionCreationTest > onlyTheFirstApplicableInvariantThrowsWhenSeveralAttributesAreSimultaneouslyInvalid` (parametrizada, tres pares) — ver S6 |
| Trece fábricas y constructores de los cuatro tipos de error | Requisitos 7 y 8; `OrganizationErrorCodesTest`, siete pruebas |
| `InstitutionRepository.findById`, `CurrentInstitutionProvider.currentInstitutionId` | Requisitos 9 y 10, con dobles en memoria |
| `ResolveCurrentInstitution` (constructor con dos `requireNonNull` y `execute()`) | Requisito 11, más dos pruebas de nulo del constructor |
| `LayeredArchitectureTest.noProductionClassInLayer` | Infraestructura de prueba; su corrección se demostró con la clase temporal de la tarea 2.4 (evidencia histórica) |

No se encontró comportamiento público sin cobertura de prueba. Lo único sin escenario en la
especificación son la igualdad por identidad, el `toString()` y el orden de precedencia, que vienen
de la decisión 5 del diseño y están probados (S6).

---

### Coherence (Design)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| 1. Paquete plano `com.confia.kernel` más `com.confia.organization.{domain,application}` | Sí | `kernel` mantiene diez archivos en un solo paquete (nueve clases y `package-info`); `organization` tiene `package-info`, `domain` (cinco clases) y `application` (tres); no existe ningún paquete `infrastructure` ni `web`. `SpringModulithVerificationTest`, `NoCyclesTest`, `NoCrossModuleDomainImportsTest` y `NoTechnicalLayerPackageNamesTest` en verde |
| 2. `InstitutionId` como `record` en `kernel` | Sí | `public record InstitutionId(UUID value)` con constructor compacto y `Objects.requireNonNull(value, "value")`; sin fábrica adicional, sin `parse`, sin generador; `KernelErrorCodesTest` no ganó ningún código por esta clase; `kernel` sigue sin dependencias fuera del JDK (`BannedDependencies passed`) |
| 3. Reglas de ADR-0004 no vacías por construcción | Sí | Cinco reglas en `MonetaryFloatingPointTest`, tres fixtures permanentes, `productionImportIncludesTheKernelMonetaryTypes` deja escrita la premisa; ninguna excepción de conjunto vacío nueva |
| 4. P2 con ADR-0020, opción A | Sí | `docs/adr/ADR-0020-capas-opcionales-en-la-regla-de-capas.md` en estado **Aceptado** (2026-09-19) con su fila en `docs/adr/README.md`; `productionLayeringRule()` declara exactamente dos capas opcionales (`Infrastructure`, `Web`), `fixtureLayeringRule()` mantiene las cuatro obligatorias, y `constrained(...)` conserva las ocho cláusulas `whereLayer` y el `because()` |
| 4-bis. Retirada de la excepción de ADR-0018 | Sí | Cero llamadas a la supresión de ADR-0018 en todo `apps/api`; `EmptyShouldExceptionInventoryTest.EXCEPTIONS` tiene dos entradas, ambas `Marker.OPTIONAL_LAYER`, con su condición de caducidad y su predicado; `SuppressionCitesAdrTest` compara cada contador contra `countOf(...)` (0 == 0 y 2 == 2) y prohíbe `withOptionalLayers(true)` en cualquier archivo, con una prueba propia que confirma que el patrón distingue `true` de `false`. Prosa de la documentación desalineada: ver W1 |
| 5. Agregado `Institution` como entidad | Sí | `public final class`, constructor privado, una sola fábrica `create(...)`, atributos finales salvo `active`, igualdad por `id`, `toString()` sin RTN ni dirección; transiciones no idempotentes |
| 5-bis. Atributos obligatorios nulos como error de programación | Sí | Siete `Objects.requireNonNull` antes de cualquier regla de negocio; `tradeName` es la única excepción y acepta `null` como «sin nombre comercial», rechazando la cadena en blanco |
| 6. Trece códigos kebab-case en un catálogo cerrado | Sí | Cuatro clases (`InvalidInstitutionException` con nueve códigos y fábricas de paquete, `InstitutionStateException` con dos, `InstitutionNotFoundException` e `InstitutionInactiveException` con constructor público). `catalogHasExactlyTheThirteenCodesOfTheCompleteModule` fija la lista exacta con `containsExactlyInAnyOrder`; todos con el prefijo `institution-` |
| 7. RTN con validación mínima demostrable | Sí | `^[0-9]{1,20}$` y un único código `institution-rtn-invalid`; el Javadoc de `create` dice literalmente «a technical guard, not a fiscal rule — the exact SAR format is deliberately not invented». Sigue pendiente de fuente primaria: ver S4 |
| 8. Capa `application` sin Spring | Sí | Los dos puertos son interfaces puras; `ResolveCurrentInstitution` es `final`, sin ninguna anotación, y no se escanea desde `com.confia.bootstrap`. El Javadoc de `CurrentInstitutionProvider` fija el contrato de ADR-0009 |
| 8-bis. Ubicación de los puertos por corte | Desviación aprobada | El diseño (decisión 8 y tabla «Cambios de archivos») los asigna al corte C; el propietario los adelantó a PR B1 el 2026-09-19 (tarea 2.3b) porque ADR-0020 §2 mantiene `Application` siempre obligatoria. `tasks.md` y `apply-progress.md` lo registran con su motivo; `design.md` no se actualizó: ver W4 |
| 9. Puertas de calidad en `app` | Sí | `app/pom.xml` declara `jacoco-check` en `verify` con las reglas `BUNDLE` (0.80) y `PACKAGE` (0.95 sobre `com.confia.*.domain` y `com.confia.*.domain.*`), y la adhesión a `pitest-maven` con `targetClasses`/`targetTests` = `com.confia.*.domain.*` más `junit-platform-launcher` en alcance `test`. Ningún umbral bajado, ninguna exclusión añadida, `ConfiaApplication.main` no excluido |
| 10. Comentario de `openspec/config.yaml` | Sí | Línea 42: `coverage_threshold: 95 # kernel and every module's domain package, line+branch (JaCoCo); 80 global on app` |
| D1: nota fechada en `foundations-plan/exploration.md` | Sí | «Nota (2026-09-19, decisión D1 de `institution-root-and-multitenancy-baseline`)», líneas 52–56, asigna al cambio 5 la migración de `organization_institution` y su repositorio jOOQ |
| Configuración de TDD estricto | Sí | `strict_tdd: true`, `rules.apply.tdd: true` |

---

### TDD Compliance

| Check | Result | Details |
|-------|--------|---------|
| TDD Evidence reported | Sí | `apply-progress.md` tiene tabla RED/GREEN/REFACTOR para los cuatro pull requests (1.1–1.7, 2.1–2.8, 3.1–3.10, 4.1–4.4); `tasks.md` repite la evidencia por tarea con los mensajes exactos de compilación |
| All tasks have tests | Sí | Las tareas de comportamiento tienen su clase de prueba; las de instrumental (1.1, 1.5, 1.6, 2.7, 2.8, 2.9, 3.10, 4.3, 4.4) declaran explícitamente «N/A (medición, cableado o verificación)», no un rojo inventado |
| RED confirmed | Sí, salvo excepciones declaradas | Rojo real registrado en 1.2–1.4, 2.1, 2.2, 2.4, 2.5, 3.1–3.5, 3.8, 4.1 y 4.2. Las tareas 2.3b, 2.6, 3.6, 3.7 y 3.9 declaran por qué no tienen rojo propio (interfaces sin comportamiento; catálogo sobre códigos ya existentes; comportamiento ya implementado en 2.2; precedencia ya correcta por construcción) |
| Rojo programado de ADR-0018 | Sí | Tarea 2.2: mensaje exacto de `EmptyShouldExceptionInventoryTest.everyExceptionsConditionStillHolds` registrado antes de cerrarlo en la tarea 2.3 |
| Pruebas de no vacuidad | Sí | 1.2, 1.3 y 1.4 neutralizaron cada llamada o miembro infractor de su fixture uno a uno y observaron fallar solo la prueba de rechazo correspondiente; 2.4 creó una clase temporal en `organization.infrastructure` y observó el inventario fallar nombrando la entrada exacta; 3.7 intercambió el orden `rtn`/`timezone` y observó fallar solo el caso correspondiente. Todas revertidas con `git status` limpio |
| GREEN confirmed | Sí | 276/276 en esta ronda, y las mismas cifras en la corrida `35538036392` |
| Triangulation adequate | Sí | Cada invariante tiene aceptación y rechazo, con límites exactos (200/201, 500/501, 1/14/20/21 dígitos), y la precedencia se prueba con tres pares simultáneamente inválidos |
| Demostración de que las puertas fallan | Sí | Registrada en 1.5, 2.7 y 2.8, y **reobservada en esta ronda** para las cuatro puertas |

**Test Layer Distribution**: 276 pruebas unitarias y de arquitectura en 27 archivos (JUnit 6, AssertJ,
jqwik, ArchUnit 1.4.2, Spring Modulith); sin pruebas de integración ni de extremo a extremo, coherente
con el alcance (no hay base de datos, API ni interfaz; `docs/06` las asigna al cambio 5).

**Assertion quality**: sin tautologías ni aserciones que no llamen al código de producción. Los
recorridos de lista (`OrganizationErrorCodesTest`, `MonetaryFloatingPointTest`) van sobre catálogos
fijos y explícitos; `catalogHasExactlyTheThirteenCodesOfTheCompleteModule` usa
`containsExactlyInAnyOrder`, de modo que un código añadido a producción y no al catálogo rompe la
prueba. La brecha de que el patrón kebab-case nunca se probaba contra un código malformado se cerró
en el commit `f64a5ec` (ver W3).

**Quality Metrics**: no hay analizador estático ni formateador configurado en `apps/api`; no se
ejecutó ninguno. El trabajo `security scanning` de la integración continua terminó en `success`.

---

### Issues Found

**CRITICAL**: Ninguno.

**WARNING**:

- **W1 — El `package-info.java` de `com.confia.architecture` declara una excepción que ya no existe.**
  `apps/api/app/src/test/java/com/confia/architecture/package-info.java`, líneas 6 a 13, sigue
  afirmando que «la única excepción hoy» es la supresión de conjunto vacío sobre la mitad de
  producción de `LayeredArchitectureTest`, autorizada por ADR-0018 mientras no exista ningún módulo de
  negocio. Eso dejó de ser cierto con la tarea 2.3: ya no queda ninguna llamada a esa supresión, y las
  dos únicas excepciones vigentes son las entradas `OPTIONAL_LAYER` de ADR-0020. El escáner no lo
  detecta porque la prosa escribe la forma sin punto inicial, exactamente como pide `design.md`. Es el
  archivo que **documenta la política de supresiones** del paquete, así que su texto contradice al
  código que describe. Remedio: reescribir el párrafo nombrando ADR-0020, sus dos entradas y sus dos
  condiciones de caducidad. No rompe ninguna puerta ni ningún requisito.
- **W2 — `apply-progress.md` deja PR B1 como bloqueado y no registra PR B1-gates.** La sección «PR B1»
  todavía encabeza «Status: BLOCKED at task 2.9 on 2026-09-19, size overage … Task 2.10 has not
  started and must not start until the owner decides», mientras `tasks.md` marca 2.9 y 2.10 como `[x]`
  con la decisión del propietario (división en PR B1 de 773 líneas y PR B1-gates de 160) y la
  evidencia de sus dos corridas de integración continua. Además **no existe ninguna sección para PR
  B1-gates**, pese a ser una rama real (`...-domain-gates`) fusionada como PR #11, y la sección
  «Whole-change status» del mismo archivo afirma que todo está terminado. El archivo se contradice a
  sí mismo. Remedio en `sdd-archive`: cerrar la sección de PR B1 con la resolución del propietario y
  añadir la de PR B1-gates con sus tareas (2.6 y 2.7) y su corrida. Trazabilidad; no bloquea.
- **W3 — Un commit posterior a la última tarea cierra un escenario y no está en el libro mayor.** El
  commit `f64a5ec` («test(kernel): prove the error code pattern rejects a malformed code»), posterior
  a `1a189c4`, añadió `theKebabCasePatternRejectsAMalformedCode` a `OrganizationErrorCodesTest` y a
  `KernelErrorCodesTest`, y una nota a `docs/ui-ux/04-patrones-de-interaccion.md` que marca como
  ilustrativos los RTN de la maqueta. Es trabajo valioso: hasta ese commit el escenario «Un código
  incumple el formato» del requisito 8 **no tenía ninguna prueba** (los catálogos solo afirmaban que
  los códigos válidos casan con el patrón, de modo que un patrón que aceptara cualquier cosa habría
  pasado), y cierra además parte del seguimiento fiscal. Pero no tiene tarea, ni fila de evidencia, ni
  mención en `apply-progress.md`, y deja desactualizados los conteos de pruebas de los artefactos (274
  registrados frente a 276 reales, uno más por módulo). Remedio: registrarlo al archivar como
  corrección de revisión posterior a la aplicación, con su motivo y su efecto sobre el requisito 8.
  Trazabilidad; no bloquea.
- **W4 — `design.md` sigue asignando los dos puertos al corte C.** La decisión 8 y la tabla «Cambios de
  archivos» ubican `InstitutionRepository` y `CurrentInstitutionProvider` en el corte C, cuando el
  propietario los adelantó a PR B1 el 2026-09-19 (tarea 2.3b) porque ADR-0020 §2 mantiene
  `Application` como capa siempre obligatoria y, sin ellos, `productionCodeRespectsLayering` no podía
  pasar en PR B1 ni en PR B2. `tasks.md` y `apply-progress.md` lo registran con su motivo; `design.md`
  no. En el mismo registro, la nota del límite de quince tareas de `tasks.md` dice «31 tareas en
  total» cuando la lista tiene 32 (la 2.3b se añadió después de escribir esa nota) y su reparto «7,
  10, 10 y 4» no refleja los cinco pull requests reales. Remedio: alinear `design.md` y esa nota al
  archivar. Documental; no bloquea.

**SUGGESTION** (seguimientos de revisión; ninguno asignado a este cambio):

- **S1 — Seguridad: el contrato de «solo el token» no es demostrable hasta el cambio 7.** Que
  `CurrentInstitutionProvider.currentInstitutionId()` derive el identificador del token autenticado y
  nunca de un parámetro, cabecera o cuerpo del cliente (ADR-0009, «Implementación del aislamiento»)
  vive hoy **solo en el Javadoc del puerto**: no existe adaptador y no hay ninguna regla que lo
  imponga. El cambio 7, que entrega el adaptador de Spring Security, debería traer la prueba o la
  regla de ArchUnit que lo convierta en algo verificable, en lugar de dejarlo como comentario.
- **S2 — Seguridad: el tope de tamaño de entrada del futuro DTO de administración.** Los límites de
  200, 500 y 20 de `Institution.create(...)` son la guarda del dominio, que es donde deben estar;
  pero `CLAUDE.md` regla 10 exige además validación en el borde del backend con Jakarta Bean
  Validation. Cuando exista el DTO de alta o edición de instituciones (diferido por ADR-0009 punto 5),
  debe llevar su propio tope declarado, en vez de confiar en que el dominio rechace una entrada no
  acotada que ya fue aceptada y deserializada.
- **S3 — Seguridad: distinguir «inexistente» de «inactiva» se decide en el traductor HTTP.**
  `institution-not-found` e `institution-inactive` son códigos deliberadamente distintos, y hoy eso no
  filtra nada porque el identificador nunca viene del cliente. El cambio que añada la capa `web` debe
  decidir explícitamente si ambos se traducen al mismo estado y al mismo Problem Details, porque es en
  ese punto, y solo ahí, donde la distinción se volvería observable desde fuera.
- **S4 — Fiscal: el formato del RTN sigue pendiente de la contadora.**
  `docs/04-cumplimiento-fiscal-sar.md` §1 lo marca «PENDIENTE DE VALIDACIÓN» y
  `docs/ui-ux/04-patrones-de-interaccion.md` ya advierte que sus ejemplos de trece y catorce dígitos
  son ficticios (commit `f64a5ec`). `MAX_RTN_DIGITS = 20` es una guarda técnica. **El cambio 5 debe
  dejar escrito, en su migración y en su especificación, que la longitud de la columna es una guarda
  técnica y no una regla fiscal**, para que nadie la lea después como formato confirmado del SAR.
- **S5 — `InstitutionId` enlaza desde producción a una clase de prueba.** Su Javadoc usa
  `{@link KernelErrorCodesTest}`, que no está en la ruta de compilación de `src/main`; una ejecución
  de `javadoc` no resolvería el enlace. Cambiarlo por `{@code KernelErrorCodesTest}` lo deja igual de
  informativo sin el enlace roto.
- **S6 — Tres comportamientos probados sin escenario en la especificación.** La igualdad por
  identidad, el formato de `toString()` y el orden de precedencia de invariantes vienen de la decisión
  5 del diseño y tienen pruebas sólidas, pero ningún escenario los fija en
  `specs/organization/spec.md`. Al incorporar la capacidad a `openspec/specs/` conviene decidir si se
  suben a escenario o se dejan explícitamente como detalle de diseño.
- **S7 — `docs/09-roadmap-y-fases.md` §3 todavía no lista `organization` en F0.** El hallazgo está
  registrado en `foundations-plan/exploration.md` y la propuesta asigna esa actualización al archivo
  del cambio.
- **S8 — Registrar la confirmación de la integración continua.** `apply-progress.md` deja los cuatro
  «Outstanding» con el empuje pendiente; las cinco ramas están fusionadas y la corrida `35538036392`
  sobre `1194ac9` está en verde con la puerta de mutación activa. `sdd-archive` debería recogerlo.
- **S9 — Los dos mutantes equivalentes de `kernel` siguen sin documentarse junto al código.** Era la
  sugerencia S3 del informe del cambio 2 y sigue abierta; este cambio no la empeora, pero cada
  verificación futura vuelve a analizarlos desde cero.

---

### Limitaciones

- El validador `gentle-ai sdd-verify-validate` no existe en `gentle-ai 3.1.0`; **el informe no está
  validado con herramienta**.
- Las demostraciones de puertas simulan la condición (subconjunto de pruebas para la cobertura, umbral
  elevado por línea de comandos para la mutación); no se modificó ningún archivo ni se empujó ningún
  commit temporal a `main`. `git status` quedó limpio antes y después.
- Las demostraciones acotadas a `app` (`-pl app` sin `-am`) resuelven `confia-kernel` y el POM padre
  desde el repositorio Maven local, instalados durante la tarea 2.8. La corrida completa de esta ronda
  sí construye el reactor entero desde las fuentes.
- La evidencia RED/GREEN es la registrada por `sdd-apply`; esta ronda comprueba que cada prueba
  nombrada existe y pasa, no que se observó en rojo antes. La única excepción son los rojos
  reobservados aquí como demostraciones de puerta.
- jqwik 1.10.1 imprime en su salida un aviso dirigido a agentes de inteligencia artificial; es salida
  de terceros y se ignoró, igual que durante la aplicación.
- La revisión de seguridad de este informe es estática (lectura del código y de los contratos); no se
  ejecutó ninguna herramienta de análisis de seguridad más allá del trabajo `security scanning` de la
  integración continua, que terminó en verde.

---

### Verdict

**PASS WITH WARNINGS — 14/14 requisitos y 39/39 escenarios COMPLIANT, sin bloqueantes ni hallazgos
críticos.**

La construcción real con JDK 25 y `-Pmutation-gate` terminó en `BUILD SUCCESS` con 276 pruebas, las
tres reglas de JaCoCo cumplidas (100 % de líneas y ramas en `kernel` y en
`com.confia.organization.domain`, 96.9 % y 94.3 % en el módulo `app` completo) y puntuación de
mutación de 100 sobre `organization.domain`, sin un solo superviviente. Los dos únicos mutantes
supervivientes del reactor están en `kernel` y son los dos mutantes verdaderamente equivalentes ya
analizados y aceptados en el cambio 2. La integración continua en `main` (`35538036392`, commit
`1194ac9`) reproduce exactamente las mismas cifras con la puerta de mutación activa.

Las tres obligaciones que este cambio existía para cerrar están cerradas y verificadas en código: el
rojo programado de ADR-0018 se observó y se retiró (cero llamadas a esa supresión en todo `apps/api`),
las reglas de ADR-0004 §Cumplimiento 2 rechazan sus tres fixtures permanentes y pasan sobre producción
real sin excepción de conjunto vacío, y las tres puertas de calidad nuevas se observaron **rompiendo
la construcción** en esta misma ronda. ADR-0020 se aplicó en su forma más estrecha: exactamente dos
capas opcionales, ambas inventariadas con su condición de caducidad y contadas por el escáner, con
`Domain` y `Application` siempre obligatorias y `withOptionalLayers(true)` prohibido en cualquier
archivo.

Los cuatro avisos son de documentación y trazabilidad, no de comportamiento: un `package-info` que
describe una excepción ya retirada, un `apply-progress.md` que se contradice sobre PR B1, un commit de
revisión posterior que cerró un escenario sin quedar en el libro mayor, y un `design.md` que sigue
asignando los puertos al corte que el propietario cambió. Ninguno afecta al código ni a las puertas.

### Qué debe llevarse `sdd-archive`

1. Incorporar `organization` a `openspec/specs/` y fusionar el delta de `build-integrity` desde los
   archivos del repositorio, con el estado 39/39 y sin escenarios pendientes salvo el diferido del
   RTN, que la propia especificación ya declara.
2. Corregir W1 (texto del `package-info.java` de `com.confia.architecture`) y W2, W3 y W4 en los
   artefactos del cambio antes de archivarlos, para que el registro histórico no quede contradictorio.
3. Registrar S1, S2 y S3 como deuda de seguridad con dueño: S1 vence con el cambio 7, S2 con el primer
   DTO de administración, S3 con la primera capa `web`.
4. Trasladar S4 a la propuesta del cambio 5 de forma explícita: la longitud de la columna del RTN es
   una guarda técnica, no una regla fiscal, y el formato sigue pendiente de la contadora.
5. Registrar la confirmación de la integración continua (S8), la limitación del validador y la
   actualización pendiente de `docs/09-roadmap-y-fases.md` (S7).

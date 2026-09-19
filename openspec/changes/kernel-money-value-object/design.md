# Diseño: objeto de valor `Money` en el módulo `kernel`

Cambio 2 de F0. Implementa la propuesta aprobada el 2026-09-18, con todas sus decisiones resueltas
por el propietario (D1, D3 y D4; D2 = 800 líneas de cambio efectivo por pull request; D5 = opción B,
dos pull requests encadenados según `docs/15-flujo-de-trabajo-git.md` §3), la capacidad nueva
`money` y el delta de `build-integrity`.

## Enfoque técnico

Primero el instrumental y después el tipo, en **dos pull requests encadenados** que compilan y
pasan sus propias puertas cada uno (ver «Entrega en dos pull requests»). El orden interno es:

1. `openspec/config.yaml` pasa a TDD estricto antes de escribir cualquier código de producción.
2. JaCoCo, PIT y jqwik se cablean junto con la **semilla mínima de producción** (`DomainException`,
   `CurrencyCode` y su error), porque una puerta sin clases que medir no prueba nada: PIT falla por
   ausencia de mutaciones y el cociente de cobertura de JaCoCo sobre cero líneas no demuestra el
   umbral. Esa semilla es también la prueba de humo de PIT y de jqwik sobre Java 25 y JUnit 6.
3. `Money` de adentro hacia afuera: construcción e igualdad, suma, resta y negación (primer pull
   request); después comparación, multiplicación, `Percentage`, redondeo a la unidad menor y, al
   final, `allocate` con sus propiedades de jqwik y los cuatro casos de regresión de ADR-0004
   (segundo pull request).
4. La integración continua se ajusta en el primer pull request, en cuanto existen los perfiles,
   para que el segundo ya se mida en `ubuntu-latest`.

Todo el código de producción vive en el paquete raíz `com.confia.kernel` (decisión 1). No hay
lógica de negocio en `kernel`: `Money` ofrece operaciones; mora, impuestos e imputación siguen
fuera de alcance (`CLAUDE.md`, regla 3).

## Decisiones de arquitectura

### 1. Paquete plano `com.confia.kernel`, sin subpaquetes

**Elección:** todos los tipos públicos del núcleo en `com.confia.kernel`.
**Alternativas descartadas:** subpaquetes `com.confia.kernel.money` y `com.confia.kernel.error`.
`app` depende de `confia-kernel`, y `SpringModulithVerificationTest` construye
`ApplicationModules.of("com.confia", DO_NOT_INCLUDE_TESTS)`, que trata cada subpaquete directo de
`com.confia` como un módulo y, por omisión, solo su paquete base como API. Con subpaquetes, el
primer módulo de negocio que use `com.confia.kernel.money.Money` (cambio 4) violaría la verificación
de Spring Modulith. Exponer los subpaquetes exigiría `@NamedInterface` o `@ApplicationModule`, que
son anotaciones de Spring Modulith y rompen `enforce-kernel-purity`; excluir `kernel` de la
verificación sería una exclusión que necesitaría su propio ADR (ADR-0018, punto 5 del alcance).
**Razón:** el núcleo es pequeño por definición (`Money`, identificadores, error base y tipos base,
`docs/01-arquitectura.md` sección 4). Un paquete plano es API del módulo `kernel` sin anotación ni
excepción. Si algún día el núcleo crece tanto que lo necesita, la división se decide con un ADR.
**Verificación colateral:** los nombres `Money`, `CurrencyCode`, etc. no contienen ningún segmento
`domain`, `application`, `infrastructure` ni `web`, de modo que
`LayeredArchitectureTest.noBusinessModuleExistsYet` sigue devolviendo `true` y el inventario de
ADR-0018 no se pone en rojo antes de tiempo. `NoTechnicalLayerPackageNamesTest` tampoco se ve
afectado.

### 2. `DomainException` en el núcleo (D1, elevada a ADR-0019)

**Elección:** `public abstract class DomainException extends RuntimeException` con
`protected DomainException(String code, String message)` y `public final String code()`. Código en
kebab-case, `<sujeto>-<condición>`, estable, único y validado en el constructor. Los errores de
programación (nulos, pesos de reparto inválidos, exactitud imposible con `UNNECESSARY`) usan las
excepciones del JDK.
**Alternativas descartadas:** solo excepciones concretas (traslada la migración al cambio 4);
`Result<T, E>` (contradice el estilo de Spring y jOOQ del backend).
**Razón y detalle:** ver `docs/adr/ADR-0019-error-de-dominio-base-en-el-nucleo.md`. Es una decisión
que condiciona a todos los módulos posteriores, se eligió entre alternativas viables y fija una
restricción permanente: cumple los criterios de `docs/adr/README.md` para un ADR. Se registra como
**Propuesto**; el propietario aprobó D1 en la propuesta y cambia el estado a Aceptado al revisar el
texto.

Catálogo de códigos del núcleo:

| Clase | Código | Cuándo |
|---|---|---|
| `CurrencyMismatchException` | `currency-mismatch` | Suma, resta o comparación entre monedas distintas |
| `UnsupportedCurrencyException` | `currency-unsupported` | Código ISO 4217 fuera del conjunto habilitado |
| `InvalidMoneyAmountException` | `money-amount-malformed` | La cadena no es un decimal plano válido |
| `InvalidMoneyAmountException` | `money-scale-exceeded` | Más de cuatro decimales significativos |
| `InvalidMoneyAmountException` | `money-amount-out-of-range` | Valor absoluto mayor que `9999999999.9999` |
| `InvalidPercentageException` | `percentage-malformed` | La cadena no es un decimal plano válido |
| `InvalidPercentageException` | `percentage-scale-exceeded` | Más de cuatro decimales significativos |
| `InvalidPercentageException` | `percentage-out-of-range` | Fuera de `[0, 100]` |

**Estos ocho códigos y los nombres de las fábricas de la sección «Contratos e interfaces» son
definitivos.** La especificación `money` los cita literalmente; cambiar uno exige modificar el
diseño y la especificación en el mismo commit.

**Precedencia cuando una entrada incumple varias reglas** (una sola excepción, siempre la primera
que aplica): `money-amount-malformed` antes que `money-scale-exceeded`, y este antes que
`money-amount-out-of-range`; lo mismo para `percentage-*`. La escala se evalúa antes que el rango
porque el rango se comprueba sobre el valor ya normalizado a escala cuatro.

`InvalidMoneyAmountException` e `InvalidPercentageException` exponen fábricas estáticas por causa
(`malformed()`, `scaleExceeded(int scale)`, `outOfRange()`), de visibilidad de paquete y con
constructor privado, para que el código y el mensaje de cada causa se escriban en un solo lugar.
Ningún mensaje repite la cadena de entrada (`CLAUDE.md`, regla 11, y riesgo de inyección en
registros); dicen solo el hecho estructural («scale 6 exceeds 4»). `CurrencyMismatchException`
expone `expected()` y `actual()` de tipo `CurrencyCode`; las monedas no son datos personales.

### 3. Moneda como `enum CurrencyCode { HNL, USD }`

**Elección:** `enum` cerrado con los dígitos de la unidad menor de cada moneda y un analizador
estricto `fromIsoCode(String)`.
**Alternativas descartadas:** `java.util.Currency`, que acepta cualquier código ISO 4217 y es más
laxo que el `CHECK (currency IN ('HNL','USD'))` de ADR-0004; `String` crudo, sin validación.
**Razón:** refleja la restricción del esquema. Agregar una moneda es deliberado: un valor del
`enum` más la restricción de base de datos. `fromIsoCode` distingue mayúsculas (ISO 4217 es en
mayúsculas y la tolerancia esconde defectos de integración) y lanza `UnsupportedCurrencyException`
en lugar del `IllegalArgumentException` de `valueOf`. Una prueba compara `minorUnitDigits()` con
`java.util.Currency.getInstance(name()).getDefaultFractionDigits()`, de modo que la tabla del
`enum` no puede contradecir la del JDK.

### 4. `Money` como clase final, no como `record`

**Elección:** `public final class Money implements Comparable<Money>` con constructor privado,
campos finales y fábricas estáticas.
**Alternativa descartada:** `record Money(BigDecimal amount, CurrencyCode currency)`. Un `record`
expone siempre un constructor canónico público y genera `equals` sobre `BigDecimal.equals`; aunque
el constructor compacto normalice, la igualdad queda implícita en código generado, justo el punto
que ADR-0004 y la regla de ArchUnit diferida al cambio 4 quieren explícito.
**Razón:** control total de la construcción (solo `String` y `BigDecimal`), igualdad escrita a la
vista del revisor y ninguna superficie pública que no se haya decidido.

**Semántica fijada:**

- **Invariante:** `amount.scale() == 4` siempre, `|amount| <= 9999999999.9999` (rango de
  `NUMERIC(14,4)`, límites incluidos), `currency != null`. Se verifica en el único constructor
  privado, por el que pasan **sin excepción** las fábricas y el resultado de toda operación. El
  límite es del dominio, no se descubre al insertar en la base. Aplicación exacta, que la
  especificación cita:

  | Punto de entrada | Cuándo se comprueba el rango | Puede fallar por rango |
  |---|---|---|
  | `Money.of(String, CurrencyCode)` | Tras validar la forma y normalizar a escala cuatro | Sí |
  | `Money.of(BigDecimal, CurrencyCode)` | Tras normalizar a escala cuatro | Sí |
  | `Money.zero(CurrencyCode)` | Pasa por el mismo constructor | No (cero está en rango) |
  | `add`, `subtract` | Sobre el resultado exacto | Sí |
  | `negate` | Sobre el resultado | No (el rango es simétrico), pero se comprueba igual |
  | `multiply(long)` | Sobre el resultado exacto | Sí |
  | `multiply(BigDecimal, RoundingMode)` | Tras el único redondeo a escala cuatro | Sí |
  | `percentage(Percentage, RoundingMode)` | Tras el único redondeo a escala cuatro | No (`p <= 100`), pero se comprueba igual |
  | `roundToMinorUnit(RoundingMode)` | Tras redondear y re-expresar a escala cuatro | Sí (`9999999999.9999` con `HALF_UP` da `10000000000.00`) |
  | Cada parte de `allocate(int...)` | Sobre cada parte | No (`abs(parte) <= abs(total)`), pero se comprueba igual |

  En todos los casos la violación lanza `InvalidMoneyAmountException` con código
  `money-amount-out-of-range`; nunca un `ArithmeticException` ni un valor truncado. Los operandos
  quedan intactos (inmutabilidad).
- **Construcción:** `setScale(4, RoundingMode.UNNECESSARY)`. Un exceso de decimales con dígitos
  distintos de cero falla con `money-scale-exceeded`; ceros de más (`"1.000000"`) se aceptan porque
  no se pierde información. `of(String)` acepta solo `-?\d+(\.\d+)?`, con longitud máxima de 64
  caracteres: sin notación exponencial, sin signo `+`, sin espacios, sin separadores de miles. Es la
  forma exacta del campo `amount` de la API. `of(BigDecimal)` acepta cualquier escala, incluida la
  negativa, porque es el valor que entrega el controlador de base de datos.
- **Igualdad:** misma moneda y `amount.compareTo(other.amount) == 0`. Con la escala normalizada,
  `compareTo` y `equals` coinciden, pero se usa `compareTo` para que la igualdad no dependa de la
  normalización. `hashCode` es `31 * amount.hashCode() + currency.hashCode()`, coherente porque la
  escala siempre es cuatro.
- **Orden:** `compareTo` compara importes y lanza `CurrencyMismatchException` ante monedas
  distintas. Es coherente con `equals` dentro de una moneda. Ordenar una colección con monedas
  mezcladas falla a propósito.
- **Signo:** se admiten negativos; el signo es información contable (ADR-0007). `negate()` existe
  porque todo movimiento financiero se reversa con un asiento de signo contrario (`CLAUDE.md`,
  regla 5).
- **Inmutabilidad y concurrencia:** `BigDecimal` y el `enum` son inmutables; los campos son finales
  y se publican de forma segura. `Money` y `Percentage` son seguros entre hilos sin sincronización.
  No implementan `Serializable`: ningún mecanismo del sistema usa serialización de Java.
- **`toString()`:** `"1234.5500 HNL"`, solo para registros y depuración. La API usa
  `toPlainString()` y `currency()`, nunca `toString()`.

### 5. Redondeo explícito en cada firma que redondea

**Elección:** toda operación que puede producir más de cuatro decimales recibe un `RoundingMode`
obligatorio y no nulo. No existe sobrecarga con modo implícito. Las operaciones exactas
(`add`, `subtract`, `negate`, `multiply(long)`) no reciben modo porque nunca redondean.
**Alternativa descartada:** fijar `HALF_UP` dentro de `Money`. ADR-0004 establece `HALF_UP` como
política, pero la política pertenece al punto del negocio que redondea (emisión fiscal, asiento,
presentación); `UNNECESSARY` es además legítimo como afirmación de exactitud en pruebas y en reglas
que lo exigen.
**Razón:** el modo queda escrito en cada llamada, que es lo que la revisión y una futura regla de
ArchUnit pueden inspeccionar. `UNNECESSARY` sobre un resultado inexacto propaga la
`ArithmeticException` del JDK: es un error de programación (ADR-0019, punto 6).

### 6. `Percentage` en el núcleo, en puntos porcentuales

**Elección:** `public final class Percentage` que guarda puntos porcentuales (`15` significa
quince por ciento) como `BigDecimal` a escala cuatro, en el rango cerrado `[0, 100]`, con las mismas
reglas de construcción que `Money` (`UNNECESSARY`, cadena decimal plana).
**Alternativas descartadas:** `BigDecimal` crudo como parámetro (aritmética monetaria sin tipo,
`CLAUDE.md` regla 3); guardar la fracción (`0.15`), que obliga a quien lee a recordar la
convención y dobla la escala necesaria para las mismas tasas.
**Razón:** la forma en puntos coincide con como se escriben las tasas fiscales y los descuentos
(«15 %»). `Money.percentage(p, mode)` calcula `amount × p / 100` de forma exacta
(`multiply(p.value()).movePointLeft(2)`) y redondea una sola vez a escala cuatro. El rango `[0, 100]`
cubre impuestos, descuentos y becas; una tasa negativa o superior a cien no tiene hoy caso de uso y
se rechaza con `percentage-out-of-range`. Si un módulo futuro necesita otra semántica (un recargo
superior al cien por ciento), se amplía con un cambio explícito.

### 7. `allocate` por resto mayor en unidades menores de la moneda

**Elección:** `public List<Money> allocate(int... ratios)`, que reparte en **unidades menores de la
moneda** (centavos para HNL y USD) y exige que el total ya sea múltiplo exacto de la unidad menor.
**Alternativas descartadas:** repartir en unidades de escala cuatro (`0.0001`), que convierte
`L 100.00` entre tres en `33.3334 + 33.3333 + 33.3333`, importes que nadie puede cobrar ni
facturar y que al redondearse vuelven a perder el centavo; `List<Integer>`, más ruido sin
beneficio.
**Razón:** se reparte lo que se va a registrar. Quien parte de un importe con más decimales llama
primero a `roundToMinorUnit(RoundingMode.HALF_UP)`, que es el único punto de redondeo que ADR-0004
admite. Un total que no es múltiplo de la unidad menor es un error de programación y lanza
`IllegalArgumentException`.

Algoritmo, con aritmética de `BigInteger` para que `unidades × peso` no desborde `long`:

```
entrada: total T (moneda c, d = c.minorUnitDigits()), pesos r[0..n-1]
validar: n >= 1; todo r[i] >= 0; S = Σ r[i] > 0 (suma en long); T múltiplo de 10^-d
signo  = signum(T);  U = |T| · 10^d            (entero, unidades menores)
para cada i:  q[i] = (U · r[i]) div S;  resto[i] = (U · r[i]) mod S
sobrante = U − Σ q[i]                          (0 <= sobrante < n)
ordenar índices por resto descendente; a igualdad de resto, índice ascendente
sumar 1 a q[i] en los primeros «sobrante» índices de ese orden
parte[i] = signo · q[i] · 10^-d, en moneda c
```

- **Desempate:** a igualdad de resto gana el índice menor. Es determinista, reproducible con una
  calculadora y coincide con la expectativa del negocio de que las primeras cuotas absorban el
  centavo (`100.00 / (1,1,1) = 33.34, 33.33, 33.33`).
- **Pesos cero:** permitidos individualmente. Su resto es cero y siempre hay al menos `sobrante`
  índices con resto positivo por delante, de modo que una parte de peso cero es siempre exactamente
  cero.
- **Negativos:** se reparte el valor absoluto y se aplica el signo a cada parte, de modo que el
  reparto es simétrico (`-100.00 / (1,1,1) = -33.34, -33.33, -33.33`).
- **Invariantes (jqwik):** `Σ partes == total` exacto; ninguna parte negativa si el total no lo es;
  ninguna parte positiva si el total no lo es; `|parte[i] − T·r[i]/S| < 1 unidad menor`.
- Devuelve `List.copyOf(...)`, inmodificable, en el orden de los pesos.

### 8. Cobertura con JaCoCo: 95 % de líneas y ramas en `kernel`, en `verify`

**Elección:** `jacoco-maven-plugin` gestionado en el POM padre (versión y ejecuciones genéricas
`prepare-agent` y `report`); `kernel` declara el plugin y añade la ejecución `check` en la fase
`verify` con dos límites sobre el elemento `BUNDLE`: `LINE` y `BRANCH`, `COVEREDRATIO`, mínimo
`0.95`. `haltOnFailure` conserva su valor por omisión (`true`).
**Alternativa descartada:** declarar la regla en el padre para todos los módulos, que hoy aplicaría
95 % a `app` (solo puntos de entrada) y adelantaría el umbral global de 80 % que el propietario
difirió al cambio 4.
**Razón:** el umbral es del módulo que lo exige. El cambio 4 añade la regla de `app` con su propio
umbral y los paquetes `domain` a 95 %. Rompe `./mvnw verify` en cualquier rama y en local.
**Versión:** la caché local tiene JaCoCo 0.8.12, que no reconoce archivos de clase de Java 25. Hace
falta una versión con soporte oficial de Java 25 (0.8.14 o posterior, se confirma contra las notas
de versión al implementar). Si la versión elegida no instrumenta clases de Java 25, se detiene y se
informa; no se baja la versión de destino del compilador.
**Interacción con Surefire:** `prepare-agent` fija la propiedad `argLine`. Si en el futuro Surefire
necesita su propio `argLine`, debe escribirse como `@{argLine} ...` para no perder el agente.

### 9. Mutación con PIT: configuración en el padre, adhesión explícita por módulo

**Elección:**

- El POM padre gestiona `org.pitest:pitest-maven` con la dependencia de complemento
  `org.pitest:pitest-junit5-plugin`, la configuración común y **una ejecución** `pit-mutation`
  (objetivo `mutationCoverage`) cuya fase es `${confia.pit.phase}` y cuyo umbral es
  `${confia.pit.effectiveThreshold}`.
- Propiedades del padre: `confia.pit.phase=none` (PIT no corre en un `verify` sin perfil),
  `confia.pit.mutationThreshold=80` (única declaración del umbral) y
  `confia.pit.effectiveThreshold=${confia.pit.mutationThreshold}`.
- `kernel` se adhiere declarando `pitest-maven` en sus `<plugins>` con `targetClasses` y
  `targetTests` en `com.confia.kernel.*`. `app` no lo declara: PIT no corre en `app` hasta que el
  cambio 4 introduzca paquetes `domain` y se adhiera con sus propios `targetClasses`.
- Configuración común: `mutators` = `STRONGER`; `threads` = 2; `timestampedReports` = `false`;
  `outputFormats` = `HTML`, `XML`; `failWhenNoMutations` = `true` de forma explícita.

**Alternativas descartadas:** declarar los perfiles con el plugin completo en cada módulo
(duplicación entre `kernel` y los módulos del cambio 4); un perfil del padre que declare el plugin en
`<build><plugins>` (activaría PIT en `app` con cero clases objetivo y obligaría a
`failWhenNoMutations=false`, que es relajar una puerta); `skip` controlado por propiedad (un
marcador de desactivación que el escáner de ADR-0018 debe tratar como supresión).
**Razón:** `docs/06` pide la configuración de PIT en el POM padre; la adhesión por módulo deja
explícito qué se muta; `mutators=STRONGER` añade las mutaciones de condicionales de igualdad, que es
exactamente donde vive el riesgo de redondeo, sin el ruido de mutantes equivalentes de `ALL`.
**Por qué la fase por propiedad:** la fase `none` desvincula la ejecución sin ningún indicador de
omisión; los perfiles la vinculan a `verify`. El mecanismo se confirma en la prueba de humo (paso 3
de «Secuencia de implementación»).

### 10. Perfiles `mutation-gate` y `mutation-report`

**Elección:** en el POM padre, declarados en este orden:

| Perfil | Activación | Propiedades | Efecto |
|---|---|---|---|
| `mutation-report` | Solo explícita, `-Pmutation-report` | `confia.pit.phase=verify`, `confia.pit.effectiveThreshold=0` | PIT corre e informa; nunca rompe por puntuación |
| `mutation-gate` | Propiedad `confia.ci.mainBranch=true`, o `-Pmutation-gate` | `confia.pit.phase=verify`, `confia.pit.effectiveThreshold=${confia.pit.mutationThreshold}` | PIT corre y rompe por debajo de 80 |

`mutation-gate` reutiliza exactamente el mecanismo de `no-snapshots-on-main`: la misma propiedad
que la integración continua fija solo en `main`. Una sola señal activa las dos puertas de la rama
principal; no hay forma de olvidar una y no la otra.

**Ambos activos a la vez** (por ejemplo `-Pmutation-report -Dconfia.ci.mainBranch=true`): Maven
inyecta las propiedades de los perfiles activos en el orden de declaración del POM, y el último gana.
Por eso `mutation-gate` se declara **después** de `mutation-report`: ante la duda, bloquea. Se
confirma con `help:evaluate -Dexpression=confia.pit.effectiveThreshold` en la prueba de humo, y si
Maven no se comportara así se detiene y se informa.

**Alternativa descartada:** activar `mutation-report` por ausencia de la propiedad
(`!confia.ci.mainBranch`). Lo activaría en todo `verify` local, que pasaría a necesitar PIT, lento y
hoy no descargable en esta máquina (ver «Restricciones del entorno local»).

### 11. Integración continua: selección de perfil por rama

**Elección:** en el paso `verify` del trabajo `backend`:

```yaml
      - name: verify
        working-directory: apps/api
        run: ./mvnw --batch-mode verify ${{ github.ref == 'refs/heads/main' && '-Dconfia.ci.mainBranch=true' || '-Pmutation-report' }}
      - name: upload coverage and mutation reports
        if: always()
        uses: actions/upload-artifact@<SHA de v4 resuelto al implementar> # v4
        with:
          name: backend-reports
          path: |
            apps/api/**/target/site/jacoco/
            apps/api/**/target/pit-reports/
          retention-days: 14
```

- `main` (empuje): `-Dconfia.ci.mainBranch=true` activa `no-snapshots-on-main` y `mutation-gate`.
- `change/**` (empuje) y todo `pull_request` (su `github.ref` es `refs/pull/<n>/merge`):
  `-Pmutation-report`.
- La expresión solo produce una de dos constantes; no interpola ningún dato controlado por el
  usuario en la orden.
- `upload-artifact` se fija por SHA de commit, como las demás acciones (commit `e836b33`). Sin él,
  el informe de `mutation-report` solo es legible en el registro de la ejecución.
- Se reescribe el comentario de cabecera (líneas 3 a 6) y el comentario del paso `verify` para
  describir JaCoCo, PIT y la selección por rama.
- `docs/06-estrategia-de-testing.md` línea 844 usa `-P...mutation-gate`; se alinea con la forma
  real para que el documento no contradiga el flujo.

### 12. Enforcer y pureza del núcleo

- `jqwik` y `junit-platform-launcher` se añaden a `kernel` en alcance `test`. La regla
  `enforce-kernel-purity` solo prohíbe `compile`, `runtime`, `provided` y `system`: no se toca.
- La versión de `jqwik` se declara en `dependencyManagement` del padre (propiedad `jqwik.version`,
  alcance `test`), porque la lista de materiales de Spring Boot no la gestiona. Sus dependencias
  transitivas de JUnit Platform quedan fijadas por la lista de materiales, lo que mantiene verde
  `dependencyConvergence`.
- `junit-platform-launcher` (versión gestionada por la lista de materiales) lo exige
  `pitest-junit5-plugin` desde su línea 1.2, que dejó de empaquetarlo para respetar la versión de
  JUnit del proyecto. Se confirma al implementar.
- PIT y JaCoCo son solo complementos de Maven; no entran en el árbol de dependencias del proyecto ni
  en `bannedDependencies`.

### 13. El escáner de supresiones incorpora JaCoCo y PIT

ADR-0018, sección 3.b, asigna a este cambio añadir las exclusiones de JaCoCo y de PIT al catálogo
de `SuppressionCitesAdrTest`. No figuraba en la tabla de áreas afectadas de la propuesta; es una
obligación de un ADR aceptado y entra en el primer pull request.

Marcadores nuevos (todos exigen una cita `ADR-NNNN` a cuatro líneas o menos):

| Marcador | Expresión | Por qué |
|---|---|---|
| Exclusiones de PIT | `<(excludedClasses\|excludedMethods\|excludedTestClasses\|excludedGroups\|avoidCallsTo)>` | Sacan código o pruebas de la mutación |
| Desactivación de cualquier plugin | `<skip>\s*true` y `\b(skipPitest\|jacoco\.skip)\b` | Apagan una puerta completa |
| Umbral de JaCoCo no bloqueante | `<haltOnFailure>\s*false` | Convierte la puerta en informe |
| Exclusión de clases de JaCoCo | `<exclude>[^<:]*</exclude>` | JaCoCo usa rutas de clase; las exclusiones del enforcer siempre llevan `:` y no coinciden |
| Anotación de generado | `@Generated\b` en `.java` | JaCoCo omite las clases anotadas con un `@Generated` retenido |

El catálogo se extrae a una lista de patrones con nombre y gana una prueba parametrizada que afirma,
para cada patrón, que coincide con su ejemplo y que **no** coincide con las exclusiones legítimas
del enforcer (`<exclude>org.hibernate:*</exclude>`). Los nombres exactos de los parámetros de PIT y
JaCoCo se confirman contra la documentación de las versiones fijadas.

## Flujo de datos

Construcción y aritmética:

```
String / BigDecimal ──> Money.of(...) ──> constructor privado ──> invariante
                             │               (escala 4 UNNECESSARY,
                             │                rango NUMERIC(14,4))
                             └── InvalidMoneyAmountException (code)

Money ──add/subtract/negate/multiply(long)──> Money       (exacto, sin modo)
Money ──multiply(BigDecimal, mode)─────────> Money       (redondea una vez a escala 4)
Money ──percentage(Percentage, mode)───────> Money       (amount × p / 100, una vez)
Money ──roundToMinorUnit(mode)─────────────> Money       (escala menor, re-expresado a 4)
Money ──allocate(int...)───────────────────> List<Money> (unidades menores, Σ = total)
```

Selección del perfil de mutación en la integración continua:

```
evento de GitHub        github.ref                 argumento de ./mvnw        perfiles activos
────────────────        ──────────                 ───────────────────        ────────────────
push a main        ──>  refs/heads/main       ──>  -Dconfia.ci.mainBranch ──> no-snapshots-on-main
                                                   =true                      + mutation-gate (80, bloquea)
push a change/**   ──>  refs/heads/change/... ──>  -Pmutation-report      ──> mutation-report (informa)
pull_request       ──>  refs/pull/<n>/merge   ──>  -Pmutation-report      ──> mutation-report (informa)
verify local       ──>  (ninguno)             ──>  (nada)                 ──> PIT no corre; JaCoCo sí
```

Secuencia de `./mvnw verify` en `kernel` con `mutation-gate`:

```
validate   enforce-build-integrity, enforce-kernel-purity, enforce-no-snapshots-on-main
compile    Java 25
initialize jacoco:prepare-agent  (fija argLine)
test       Surefire: JUnit 6 + AssertJ + jqwik (motor de JUnit Platform)
verify     jacoco:report, jacoco:check (LINE y BRANCH >= 0.95)   ──> falla la construcción
           pitest:mutationCoverage (umbral 80)                   ──> falla la construcción
```

## Contratos e interfaces

Todas las clases en `package com.confia.kernel;`. Firmas públicas completas. Los nombres de tipo,
de fábrica (`Money.of(String, CurrencyCode)`, `Money.of(BigDecimal, CurrencyCode)`,
`Money.zero(CurrencyCode)`, `Percentage.of(String)`, `Percentage.of(BigDecimal)`,
`CurrencyCode.fromIsoCode(String)` y las fábricas de paquete `malformed()`, `scaleExceeded(int)` y
`outOfRange()`) y las constantes de código son **definitivos**; la especificación `money` los cita
literalmente.

```java
public abstract class DomainException extends RuntimeException {
    protected DomainException(String code, String message); // IAE if code is null or malformed
    public final String code();
}

public enum CurrencyCode {
    HNL(2), USD(2);
    public int minorUnitDigits();
    public static CurrencyCode fromIsoCode(String isoCode); // UnsupportedCurrencyException
}

public final class CurrencyMismatchException extends DomainException {
    public static final String CODE = "currency-mismatch";
    public CurrencyMismatchException(CurrencyCode expected, CurrencyCode actual);
    public CurrencyCode expected();
    public CurrencyCode actual();
}

public final class UnsupportedCurrencyException extends DomainException {
    public static final String CODE = "currency-unsupported";
    public UnsupportedCurrencyException(); // message never echoes the rejected input
}

public final class InvalidMoneyAmountException extends DomainException {
    public static final String MALFORMED = "money-amount-malformed";
    public static final String SCALE_EXCEEDED = "money-scale-exceeded";
    public static final String OUT_OF_RANGE = "money-amount-out-of-range";
    static InvalidMoneyAmountException malformed();
    static InvalidMoneyAmountException scaleExceeded(int scale);
    static InvalidMoneyAmountException outOfRange();
}

public final class InvalidPercentageException extends DomainException {
    public static final String MALFORMED = "percentage-malformed";
    public static final String SCALE_EXCEEDED = "percentage-scale-exceeded";
    public static final String OUT_OF_RANGE = "percentage-out-of-range";
    static InvalidPercentageException malformed();
    static InvalidPercentageException scaleExceeded(int scale);
    static InvalidPercentageException outOfRange();
}

public final class Percentage {
    public static final int SCALE = 4;
    public static Percentage of(String percentagePoints);     // "15", "12.5"
    public static Percentage of(BigDecimal percentagePoints);
    public BigDecimal value();                                // scale 4, in [0, 100]
    public String toPlainString();
    // equals/hashCode on normalized value; toString "15.0000%"
}

public final class Money implements Comparable<Money> {
    public static final int SCALE = 4;
    public static Money of(String amount, CurrencyCode currency);
    public static Money of(BigDecimal amount, CurrencyCode currency);
    public static Money zero(CurrencyCode currency);

    public BigDecimal amount();        // always scale 4
    public CurrencyCode currency();
    public String toPlainString();     // "1234.5500"

    public Money add(Money other);                                    // CurrencyMismatchException
    public Money subtract(Money other);                               // CurrencyMismatchException
    public Money negate();
    public Money multiply(long factor);                               // exact
    public Money multiply(BigDecimal factor, RoundingMode rounding);  // one rounding to scale 4
    public Money percentage(Percentage percentage, RoundingMode rounding);
    public Money roundToMinorUnit(RoundingMode rounding);             // result keeps scale 4
    public List<Money> allocate(int... ratios);                       // decision 7

    public boolean isZero();
    public boolean isPositive();
    public boolean isNegative();
    public boolean isGreaterThan(Money other);        // all four: CurrencyMismatchException
    public boolean isGreaterThanOrEqual(Money other);
    public boolean isLessThan(Money other);
    public boolean isLessThanOrEqual(Money other);
    @Override public int compareTo(Money other);      // CurrencyMismatchException

    @Override public boolean equals(Object o);        // same currency, compareTo == 0
    @Override public int hashCode();
    @Override public String toString();               // "1234.5500 HNL"
}
```

Reglas transversales: todo parámetro de referencia es no nulo (`Objects.requireNonNull` con el
nombre del parámetro); ninguna firma pública recibe ni devuelve `double`, `float`, `Double` ni
`Float`; toda operación que redondea recibe su `RoundingMode`.

## Cambios de archivos

| Archivo | Acción | PR | Descripción |
|---|---|---|---|
| `openspec/config.yaml` | Modificar | 1 | `strict_tdd: true`, `apply.tdd: true`, ejecutor y umbral (ver abajo); contexto actualizado |
| `apps/api/pom.xml` | Modificar | 1 | Versiones de JaCoCo, PIT, `pitest-junit5-plugin` y jqwik; `pluginManagement` de JaCoCo y PIT; propiedades `confia.pit.*`; perfiles `mutation-report` y `mutation-gate` |
| `apps/api/kernel/pom.xml` | Modificar | 1 | jqwik y `junit-platform-launcher` en `test`; JaCoCo con `check` a 95 %; adhesión a PIT |
| `apps/api/kernel/src/test/resources/junit-platform.properties` | Crear | 1 | `jqwik.database=target/jqwik-database`, `jqwik.tries.default=1000` |
| `apps/api/kernel/src/main/java/com/confia/kernel/package-info.java` | Modificar | 1 | Documenta el paquete plano (decisión 1) |
| `apps/api/kernel/src/main/java/com/confia/kernel/DomainException.java` | Crear | 1 | Error base (ADR-0019) |
| `apps/api/kernel/src/main/java/com/confia/kernel/CurrencyCode.java` | Crear | 1 | `enum` de monedas habilitadas |
| `apps/api/kernel/src/main/java/com/confia/kernel/UnsupportedCurrencyException.java` | Crear | 1 | `currency-unsupported` |
| `apps/api/kernel/src/test/java/com/confia/kernel/DomainExceptionTest.java` | Crear | 1 | Contrato del código: formato kebab-case, nulo, longitud |
| `apps/api/kernel/src/test/java/com/confia/kernel/CurrencyCodeTest.java` | Crear | 1 | Incluye una propiedad de jqwik (ida y vuelta de `fromIsoCode`) como humo del motor |
| `apps/api/kernel/src/test/java/com/confia/kernel/BuildSmokeTest.java` | Eliminar | 1 | Sustituida por pruebas reales; su Javadoc («sin reglas de negocio todavía») queda falso |
| `apps/api/app/src/test/java/com/confia/architecture/SuppressionCitesAdrTest.java` | Modificar | 1 | Marcadores de JaCoCo y PIT (decisión 13) y prueba de cada patrón |
| `.github/workflows/ci.yml` | Modificar | 1 | Selección de perfil por rama, subida de informes, comentarios |
| `docs/06-estrategia-de-testing.md` | Modificar | 1 | Línea 844 alineada con la activación por propiedad |
| `docs/adr/ADR-0019-error-de-dominio-base-en-el-nucleo.md` | Crear | 1 | Escrito en esta fase de diseño |
| `docs/adr/README.md` | Modificar | 1 | Fila de ADR-0019, escrita en esta fase |
| `apps/api/kernel/src/main/java/com/confia/kernel/Money.java` | Crear | 1 y 2 | Fábricas, invariante, `equals`, `hashCode`, `toString`, `toPlainString`, `add`, `subtract`, `negate` (1); `implements Comparable<Money>`, `compareTo`, `isZero`, `isPositive`, `isNegative`, las cuatro comparaciones, `multiply`, `percentage`, `roundToMinorUnit`, `allocate` (2) |
| `apps/api/kernel/src/main/java/com/confia/kernel/CurrencyMismatchException.java` | Crear | 1 | `currency-mismatch`, necesaria para `add` y `subtract` |
| `apps/api/kernel/src/main/java/com/confia/kernel/InvalidMoneyAmountException.java` | Crear | 1 | Tres códigos de construcción y rango |
| `apps/api/kernel/src/test/java/com/confia/kernel/MoneyConstructionTest.java` | Crear | 1 | Cadenas válidas e inválidas, precedencia de códigos, escala, rango, `1.0 == 1.00`, `hashCode` |
| `apps/api/kernel/src/test/java/com/confia/kernel/MoneyArithmeticTest.java` | Crear | 1 y 2 | Suma, resta, negación y desbordamiento de rango (1); multiplicación, porcentaje, redondeo (2) |
| `apps/api/kernel/src/test/java/com/confia/kernel/MoneyComparisonTest.java` | Crear | 2 | `compareTo`, signo y las cuatro comparaciones, incluida moneda distinta |
| `apps/api/kernel/src/test/java/com/confia/kernel/MoneyApiShapeTest.java` | Crear | 2 | Reflexión del JDK: ningún miembro público de `Money` ni `Percentage` usa `double` o `float` |
| `apps/api/kernel/src/test/java/com/confia/kernel/KernelErrorCodesTest.java` | Crear | 2 | Catálogo completo de los ocho códigos del núcleo: formato y unicidad (ADR-0019) |
| `apps/api/kernel/src/main/java/com/confia/kernel/Percentage.java` | Crear | 2 | Decisión 6 |
| `apps/api/kernel/src/main/java/com/confia/kernel/InvalidPercentageException.java` | Crear | 2 | Tres códigos |
| `apps/api/kernel/src/test/java/com/confia/kernel/PercentageTest.java` | Crear | 2 | Rango, escala, forma |
| `apps/api/kernel/src/test/java/com/confia/kernel/MoneyAllocationTest.java` | Crear | 2 | Casos de ejemplo de `allocate`, pesos cero, negativos, errores |
| `apps/api/kernel/src/test/java/com/confia/kernel/MoneyProperties.java` | Crear | 2 | Propiedades de jqwik (ver «Estrategia de pruebas») |
| `apps/api/kernel/src/test/java/com/confia/kernel/MoneyRegressionTest.java` | Crear | 2 | Los cuatro casos permanentes de ADR-0004, marcados como tales en el Javadoc |

Contenido de `openspec/config.yaml` tras el cambio (solo lo que cambia):

```yaml
context: |
  ... (stack sin cambios) ...
  Code exists since F0 change 1: Maven reactor in apps/api (kernel, app) and CI in
  .github/workflows/ci.yml. Frontend apps and packages do not exist yet.
strict_tdd: true # runner: ./mvnw verify in apps/api, JAVA_HOME must point to JDK 25
rules:
  apply:
    guidelines:
      - Money arithmetic only through com.confia.kernel.Money; business money rules live in the
        domain package of the owning module; never double/float for money
    tdd: true
    test_command: "./mvnw verify" # in apps/api, JDK 25
  verify:
    test_command: "./mvnw verify" # add -Pmutation-report to include PIT
    coverage_threshold: 95 # kernel line+branch (JaCoCo); 80 global arrives with change 4
```

## Estrategia de pruebas

| Nivel | Qué se prueba | Cómo |
|---|---|---|
| Unitario | Construcción, igualdad, aritmética, comparación, redondeo, `Percentage`, `CurrencyCode`, errores y sus códigos | JUnit 6 y AssertJ, una clase de prueba por comportamiento, escritas en rojo antes del código (TDD estricto) |
| Casos límite obligatorios | Cero, negativo, un centavo, `9999999999.9999` y uno más, puntos medios `0.615`, `2.345`, `1.005` y sus negativos con `HALF_UP` (`-0.615 → -0.62`), `1.0 == 1.00`, más de cuatro decimales, tasa cero, moneda distinta, `allocate` | Pruebas con nombre explícito del caso; parametrizadas donde el caso es una tabla |
| Regresión permanente | `6.70 × 0.15 → 1.0050 → 1.01`, `1234.55 × 3 == 3703.65`, `Σ 1000 × 0.1 == 100.00`, `0.1 + 0.2 == 0.3` | `MoneyRegressionTest`, cuyo Javadoc prohíbe borrarlas (ADR-0004, cumplimiento 5) |
| Propiedades | (a) `Σ allocate(r) == total`; (b) sin partes negativas si el total no lo es; (c) cada parte a menos de una unidad menor de su cuota exacta; (d) toda secuencia de `add`, `subtract`, `negate` y `multiply(long)` coincide con el mismo cálculo en `BigDecimal` a escala completa, y un único `roundToMinorUnit(HALF_UP)` al final coincide con `setScale(d, HALF_UP)` del resultado exacto; (e) `percentage` coincide con el producto exacto redondeado una vez | jqwik con generadores acotados al rango de `NUMERIC(14,4)`; mil intentos por propiedad; semilla informada en caso de fallo |
| Forma de la API | Ningún miembro público de `Money` ni `Percentage` usa `double`, `float`, `Double` ni `Float` | Reflexión del JDK hasta que la regla de ArchUnit llegue con el cambio 4 (D3) |
| Cobertura | 95 % de líneas y de ramas en `kernel` | `jacoco:check` en `verify` |
| Mutación | Puntuación de 80 sobre `com.confia.kernel.*` | PIT con `STRONGER`; bloquea en `main`, informa en ramas |
| Arquitectura | El paquete plano no rompe Spring Modulith ni el inventario de ADR-0018 | Las pruebas existentes de `app` en verde con `kernel` poblado |
| Integración y extremo a extremo | Ninguna | No hay base de datos, API ni interfaz en este cambio |

**Demostración de que las puertas fallan** (misma lógica que el cambio 1: una regla que nunca falló
no prueba nada). Durante la aplicación, sin comprometer el cambio:

1. JaCoCo: comentar temporalmente una prueba y observar que `jacoco:check` rompe `verify`.
2. PIT: debilitar temporalmente una aserción y observar que `-Pmutation-gate` rompe por debajo de 80
   mientras `-Pmutation-report` termina en verde y solo informa.
3. Selección de perfil: `help:evaluate` de `confia.pit.phase` y `confia.pit.effectiveThreshold` en
   las cuatro combinaciones de la tabla de la decisión 10, más la combinación con los dos perfiles.

Las salidas observadas se registran como evidencia en el informe de verificación. Si el entorno
local no puede ejecutar PIT, los pasos 2 y 3 se ejecutan en la integración continua solo con una
autorización explícita del propietario para empujar un commit temporal (ver «Preguntas abiertas»).

## Secuencia de implementación y prueba de humo de PIT

1. `openspec/config.yaml` a TDD estricto.
2. POM padre y de `kernel`, `junit-platform.properties`, y la semilla (`DomainException`,
   `CurrencyCode`, `UnsupportedCurrencyException`) escrita en rojo y luego en verde.
3. **Prueba de humo de PIT y jqwik sobre Java 25 y JUnit 6**, antes de escribir `Money`:
   `./mvnw -pl kernel -am verify -Pmutation-report` debe mostrar que Surefire ejecuta la propiedad
   de jqwik, que JaCoCo instrumenta las clases de Java 25 y que PIT genera y evalúa mutantes con el
   complemento de JUnit 5 sobre JUnit Platform 6. **Si PIT, su complemento o jqwik fallan por
   compatibilidad, se detiene la aplicación y se informa al propietario con el error exacto.** Nunca
   se desactiva la puerta, nunca se baja el umbral y nunca se sustituye jqwik por otra cosa sin
   decisión (ADR-0008, `docs/06` sección 3). Si la máquina local no puede descargar los artefactos,
   la prueba de humo se da por cumplida solo cuando la integración continua la ejecuta en verde.
4. Escáner de supresiones y `ci.yml`.
5. Resto del primer pull request (construcción, igualdad, suma, resta y negación de `Money`) y
   después el segundo, cada comportamiento en rojo antes que en verde.

## Entrega en dos pull requests

Resolución del propietario (propuesta, «Resolución del propietario»): D2 = **800 líneas de cambio
efectivo** por pull request; D5 = **opción B**, dos pull requests encadenados según
`docs/15-flujo-de-trabajo-git.md` §3 (`chain_strategy: stacked-to-main`): el segundo apunta al
primero y se fusionan en orden. Cada pull request compila, pasa `./mvnw verify` con JaCoCo a 95 %
de líneas y ramas sobre lo que contiene, y pasa PIT a 80 sobre lo que contiene. Ninguno separa una
operación de sus pruebas (`docs/15` §3).

**Qué se cuenta.** Adiciones más eliminaciones en código de producción, pruebas, POM, `ci.yml`,
`openspec/config.yaml` y `docs/06`. Quedan fuera los artefactos de OpenSpec y ADR-0019 con su fila
del índice, que la propuesta ya contó aparte («adicionales»). Es un supuesto que el propietario debe
confirmar (ver «Preguntas abiertas»): esos artefactos ya están en la rama y aparecerán en el diff
del primer pull request.

### Estimación por bloque (líneas de autor, bajo a alto)

| Bloque | Contenido | Estimación |
|---|---|---|
| T. Instrumental | `config.yaml` (12–20), POM padre (80–110), POM de `kernel` (30–40), `junit-platform.properties` (2–3), `SuppressionCitesAdrTest` (35–55), `ci.yml` (15–22), `docs/06` (2–4) | 176 a 254 |
| S. Semilla | `DomainException` (25–35) y su prueba (30–40), `CurrencyCode` (30–40) y su prueba (35–45), `UnsupportedCurrencyException` (12–18), `package-info` (5–10), eliminación de `BuildSmokeTest` (10–15) | 147 a 203 |
| C. Construcción e igualdad | Núcleo de `Money`: constructor, invariante, fábricas, accesores, `equals`, `hashCode`, `toString`, `toPlainString` (95–120); `InvalidMoneyAmountException` (30–40); `MoneyConstructionTest` (110–140) | 235 a 300 |
| A. Aritmética exacta | `add`, `subtract`, `negate` (15–25); `CurrencyMismatchException` (25–30); primera parte de `MoneyArithmeticTest` (55–75) | 95 a 130 |
| K. Comparación | `compareTo`, signo y cuatro comparaciones en `Money` (30–40); `MoneyComparisonTest` (45–60) | 75 a 100 |
| X. Guardas transversales | `MoneyApiShapeTest` (30–40); `KernelErrorCodesTest` (25–35) | 55 a 75 |
| R. Multiplicación, porcentaje, redondeo y reparto | Operaciones en `Money` (100–130), `Percentage` (60–80), `InvalidPercentageException` (30–40), `PercentageTest` (70–90), segunda parte de `MoneyArithmeticTest` (90–120), `MoneyAllocationTest` (70–90), `MoneyProperties` (80–110), `MoneyRegressionTest` (35–45) | 535 a 705 |
| **Total** | | **1 318 a 1 767** |

Esta estimación es más alta que la de la propuesta (1 065 a 1 445) porque desglosa archivo por
archivo, incluye el Javadoc exigido y el escáner de supresiones que la propuesta no contaba.

### Resultado: dos pull requests dentro de 800 en el extremo alto no es posible

El total en el extremo alto (1 767) supera dos presupuestos completos (1 600). **Ninguna partición
honesta deja los dos pull requests por debajo de 800 en el extremo alto.** No se fuerzan los
números. Particiones evaluadas, con los bloques como unidad mínima (partirlos separaría código de
sus pruebas):

| Partición | PR 1 | PR 2 | Exceso máximo en el extremo alto |
|---|---|---|---|
| Literal de D5 (comparaciones en el PR 1): T+S+C+A+K / X+R | 728 a 987 | 590 a 780 | 187 (PR 1) |
| PR 1 estrictamente dentro de 800: T+S+C / A+K+X+R | 558 a 757 | 760 a 1 010 | 210 (PR 2) |
| **Elegida, equilibrada:** T+S+C+A / K+X+R | **653 a 887** | **665 a 880** | **87 (PR 1) y 80 (PR 2)** |

**Desviación mínima:** la partición equilibrada. Sus extremos bajos y sus valores centrales (770 y
772) caben en 800; solo el extremo alto los supera, en 87 y 80 líneas. Frente al texto de D5, mueve
al segundo pull request las comparaciones (`compareTo`, signo y las cuatro comparaciones con su
prueba), `MoneyApiShapeTest` y `KernelErrorCodesTest`. La forma de D5 (dos pull requests
encadenados, instrumental y operaciones exactas primero, multiplicación, porcentaje, redondeo y
reparto después) se conserva.

### Contenido definitivo

**PR 1 — instrumental, semilla y `Money` exacto** (653 a 887). Rama `change/kernel-money-value-object`,
base `main`.

- Instrumental: `openspec/config.yaml` a TDD estricto; POM padre (JaCoCo, PIT, jqwik, propiedades
  `confia.pit.*`, perfiles `mutation-report` y `mutation-gate`); POM de `kernel`;
  `junit-platform.properties`; `SuppressionCitesAdrTest` con los marcadores de JaCoCo y PIT;
  `ci.yml`; `docs/06`.
- Semilla: `DomainException`, `CurrencyCode`, `UnsupportedCurrencyException`, sus pruebas (con la
  propiedad de jqwik de humo), `package-info`, eliminación de `BuildSmokeTest`.
- `Money`: fábricas `of(String, CurrencyCode)`, `of(BigDecimal, CurrencyCode)` y
  `zero(CurrencyCode)`; invariante de escala y rango; `amount`, `currency`, `toPlainString`,
  `equals`, `hashCode`, `toString`; `add`, `subtract`, `negate`. `InvalidMoneyAmountException` con
  sus tres códigos y `CurrencyMismatchException`. `MoneyConstructionTest` y la primera parte de
  `MoneyArithmeticTest`, incluido el desbordamiento de rango en `add` y `subtract`.
- Documentación fuera del cómputo: ADR-0019 y su fila; artefactos de OpenSpec del cambio.

**PR 2 — comparación, multiplicación, porcentaje, redondeo y reparto** (665 a 880). Rama
`change/kernel-money-value-object-arithmetic`, base `change/kernel-money-value-object`.

- `Money implements Comparable<Money>`, `compareTo`, `isZero`, `isPositive`, `isNegative`, las
  cuatro comparaciones y `MoneyComparisonTest`.
- `multiply(long)`, `multiply(BigDecimal, RoundingMode)`, `percentage`, `roundToMinorUnit`,
  `allocate`; `Percentage` e `InvalidPercentageException`; `PercentageTest`, segunda parte de
  `MoneyArithmeticTest`, `MoneyAllocationTest`, `MoneyProperties`, `MoneyRegressionTest`.
- `MoneyApiShapeTest` (cubre `Money` y `Percentage` completos) y `KernelErrorCodesTest` (los ocho
  códigos).

**Puertas por pull request.** Cada clase de producción llega con sus pruebas en el mismo pull
request, de modo que JaCoCo a 95 % y PIT a 80 se evalúan sobre un conjunto cerrado. El PR 1 introduce
`Money` sin la guarda de reflexión contra `double` y `float`; su revisión lo comprueba a mano y el
PR 2 añade la prueba antes de que exista ningún consumidor (el primero es el cambio 4).

**Encadenamiento e integración continua.** `ci.yml` dispara `pull_request` solo hacia `main` y
`push` en `change/**`. Mientras el PR 2 apunta al PR 1, su verificación es la del empuje a su rama
(`-Pmutation-report`); el nombre de la rama debe empezar por `change/` para que se ejecute. Al
fusionarse el PR 1, el PR 2 se redirige a `main`, recibe su ejecución de `pull_request` y se fusiona
después. No hace falta cambiar los disparadores. La segunda rama es una excepción a «una rama por
cambio SDD» (`CLAUDE.md`, convenciones de Git) que exige la propia D5.

**Control durante la aplicación.** El pronóstico no es la medida: al cerrar cada pull request se
mide el diff real (`git diff --numstat` contra su base, con las exclusiones de «Qué se cuenta»). Si
cabe en 800, se entrega. Si no cabe, **la aplicación se detiene y se consulta al propietario**; no
se abre un tercer pull request ni se declara una excepción de tamaño sin su decisión, porque D5
fija dos. Puntos de división ya identificados para esa consulta: PR 1 → 1a instrumental y semilla
(T+S) / 1b `Money` exacto (C+A); PR 2 → 2a comparación, multiplicación, porcentaje y redondeo /
2b `allocate`, propiedades y regresiones. Ninguno separa una operación de sus pruebas.

La semilla del PR 1 es un ajuste respecto del corte propuesto («instrumental y `config.yaml`»): sin
clases de producción, PIT falla por ausencia de mutaciones y la prueba de humo no puede ejecutarse.

## Restricciones del entorno local

- **`JAVA_HOME` debe ser JDK 25.** El valor por omisión de esta máquina es JDK 21 y el enforcer exige
  `[25,26)`. Toda ejecución local usa `C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot`.
- **PIT, `pitest-junit5-plugin`, jqwik y la versión de JaCoCo con soporte de Java 25 no están en la
  caché local** (`~/.m2` solo tiene JaCoCo 0.8.12 y JUnit 6.0.3). La brecha del almacén de confianza
  PKIX de Windows documentada en `apps/api/pom.xml` probablemente impide descargarlos. **La
  integración continua en `ubuntu-latest` es la fuente de verdad** del resultado de `verify`. La
  alternativa local es importar al almacén de confianza del JDK 25 la cadena de certificados que
  falta; es una acción del propietario sobre su máquina, no del agente.
- Nunca se omite una prueba, se baja un umbral ni se cambia una versión a una cacheada
  incompatible para pasar en local.

## Matriz de amenazas

| Frontera | Aplicabilidad | Respuesta de diseño | Prueba |
|---|---|---|---|
| Selección de perfil por rama en integración continua | **Aplicable**: `ci.yml` compone la orden de `./mvnw` a partir de `github.ref` | La expresión solo produce dos constantes; ningún dato del evento se interpola en la orden. `main` activa la puerta con la misma propiedad que `no-snapshots-on-main`; `refs/pull/<n>/merge` y `change/**` informan; con ambos perfiles activos gana la puerta por orden de declaración | `help:evaluate` de `confia.pit.phase` y `confia.pit.effectiveThreshold` en cada combinación (paso 3 de la demostración de puertas) |
| Rutas tipo documentación | N/A: el cambio no clasifica ni ejecuta archivos por nombre |  |  |
| Selección de repositorio Git | N/A: el cambio no ejecuta `git -C` ni resuelve rutas de repositorio |  |  |
| Estado de índice | N/A: el cambio no automatiza `commit` |  |  |
| Estado de empuje | N/A: el cambio no automatiza `push` |  |  |
| Órdenes de pull request | N/A: el cambio no automatiza pull requests |  |  |

## Migración y despliegue

Sin migración: no hay base de datos, esquema, datos ni despliegue. Revertir es revertir los commits
del cambio (o su commit de fusión en `main`), lo que retira `Money`, las puertas, ADR-0019 y el
cambio de `openspec/config.yaml` a la vez. Una puerta que bloquee por error no se desactiva con una
bandera: se corrige con un cambio explícito o con un ADR (ADR-0008).

## Por confirmar durante la implementación

No se inventan aquí; se confirman contra la documentación de cada herramienta antes de escribirlos:

1. **Pendiente.** Versión de JaCoCo con soporte oficial de Java 25 (0.8.14 o posterior).
2. **Pendiente.** Versiones de `pitest-maven` y `pitest-junit5-plugin` compatibles con Java 25
   (ASM 9.8 o posterior) y con JUnit 6 (JUnit Platform 6); nombre exacto de los parámetros de
   exclusión y desactivación para el catálogo del escáner.
3. **Pendiente.** Versión de jqwik que ejecuta sobre JUnit 6 (JUnit Platform 6), y si su motor
   necesita alguna dependencia adicional.
4. Que `pitest-junit5-plugin` exige `junit-platform-launcher` en el classpath de pruebas.
5. Que la fase `${confia.pit.phase}` se interpola desde propiedades de perfil y que `none`
   desvincula la ejecución.
6. Que con los dos perfiles activos gana el declarado en último lugar.
7. SHA de commit de `actions/upload-artifact` v4.

Si alguna confirmación obliga a apartarse de lo decidido, se eleva a un ADR y no se entierra aquí
(`docs/13-metodologia-sdd.md`, regla 5).

## Preguntas abiertas

- [x] **D2 y D5** resueltas por el propietario el 2026-09-18: 800 líneas por pull request y dos
      pull requests encadenados (`stacked-to-main`). Ver «Entrega en dos pull requests».
- [ ] **Exceso pronosticado en el extremo alto** (propietario): con estimaciones honestas, ninguna
      partición en dos deja ambos pull requests bajo 800 en el extremo alto (PR 1 hasta 887, PR 2
      hasta 880; valores centrales 770 y 772). El diseño aplica la desviación mínima y mide el diff
      real al cerrar cada pull request; si alguno supera 800, la aplicación se detiene y el
      propietario decide entre un tercer pull request en el punto de división ya identificado o una
      excepción de tamaño. También debe aceptar que las comparaciones, `MoneyApiShapeTest` y
      `KernelErrorCodesTest` pasen al PR 2, a diferencia del texto de D5.
- [ ] **Cómputo del presupuesto** (propietario): el diseño excluye del cómputo los artefactos de
      OpenSpec y ADR-0019, que ya están en la rama del PR 1. Si cuentan, el PR 1 supera 800 con
      cualquier partición.
- [ ] **Estrategia de entrega de la sesión**: la sesión SDD registra `single-pr`; D5 fija
      `auto-chain` con `stacked-to-main`. Prevalece la decisión del propietario; el orquestador debe
      alinear su registro antes de la fase de tareas.
- [ ] **Puerta de `main` posiblemente inerte** (verificar antes de confiar en ella): el perfil
      `no-snapshots-on-main` aplica `requireReleaseVersion`, y la versión del proyecto es
      `0.1.0-SNAPSHOT`. Si eso rompe `validate` en cada empuje a `main`, PIT nunca llega a ejecutarse
      allí y `mutation-gate` no protege nada. Es un defecto previo del cambio 1, no de este; el
      agente no consulta el estado remoto de la integración continua sin autorización. El
      propietario debe revisar la última ejecución de `main` y decidir si se corrige aquí o en un
      cambio aparte.
- [ ] **Pull requests hacia `main`**: `docs/06` hace bloqueante la mutación solo en la rama
      principal, de modo que un PR informa y la puerta actúa después de fusionar. Alternativa: usar
      `mutation-gate` también en `pull_request` hacia `main`, que detiene el defecto antes de la
      fusión a cambio de que una rama de trabajo quede bloqueada por mutación. Se mantiene lo que dice
      `docs/06` salvo decisión contraria.
- [ ] **Demostración de puertas en la integración continua**: si PIT no corre en local, demostrar
      que `mutation-gate` falla exige empujar un commit temporal. Empujar requiere autorización
      explícita del propietario.
- [ ] **Cadena de presentación**: el primer DTO de `web` necesitará el importe a la escala menor
      como cadena (`"1.01"`). Se decide en el cambio que lo introduzca; hoy `Money` solo expone
      `toPlainString()` a escala cuatro.
- [ ] **ADR-0019** queda en estado Propuesto hasta que el propietario lo acepte.

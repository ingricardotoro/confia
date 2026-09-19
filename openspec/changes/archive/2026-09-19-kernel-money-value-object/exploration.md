# Exploración: `Money` como objeto de valor en `apps/api/kernel` (F0, cambio 2)

- **Cambio:** `kernel-money-value-object`
- **Fase:** explorar
- **Fecha:** 2026-09-17
- **Estado:** exploración terminada, pendiente de propuesta y de aprobación del propietario
- **Fuente:** transcripción literal de la observación de Engram `sdd/kernel-money-value-object/explore`
  (#368). El agente de exploración no tenía permiso de escritura en el repositorio; la fase de
  propuesta escribe este archivo.

## Estado actual

- `apps/api/kernel` existe pero está vacío salvo `package-info.java` y `BuildSmokeTest.java` (del cambio 1, ya archivado en `openspec/changes/archive/2026-09-17-maven-workspace-and-ci-skeleton/`).
- `kernel/pom.xml` ya permite dependencias de test (junit-jupiter, assertj-core) y tiene una ejecución `enforce-kernel-purity` de maven-enforcer-plugin que banea *:*:*:*:compile/runtime/provided/system — jqwik puede añadirse igual, en scope test, sin tocar esa regla.
- El POM raíz (`apps/api/pom.xml`) no declara JaCoCo ni PIT en ninguna parte (ni pluginManagement ni profiles). `.github/workflows/ci.yml` lo confirma explícitamente en un comentario: "No -Pmutation-gate/-Pmutation-report: those profiles arrive with change 2." Este cambio es quien introduce esos perfiles.
- `openspec/config.yaml`: `strict_tdd: false` con comentario "revisit at F0"; `apply.tdd: false`; `verify.coverage_threshold: 0`. `foundations-plan/exploration.md` ya señaló que esto debe pasar a `true`/95 en el cambio 2 con tarea explícita.
- No existe ninguna especificación de dominio (`openspec/specs/*`) que mencione dinero hoy (identity, ledger, payments, cashbox, invoicing, build-integrity). Este cambio escribe una capacidad nueva (`kernel` o `money`), no un delta sobre una existente.

## Contrato exacto que debe cumplir `Money` (CLAUDE.md reglas 1-5 + confia-money-rules)

- `BigDecimal` normalizado a escala 4, coherente con `NUMERIC(14,4)`; normalización con `setScale(4)` sin modo (= `UNNECESSARY` implícito) para que un exceso de decimales en la entrada falle en vez de truncar en silencio (así lo hacen ambos ejemplos ilustrativos, en confia-money-rules y en ADR-0011).
- Moneda ISO 4217 explícita, validada contra el conjunto habilitado (hoy HNL y USD, ver ADR-0004 CHECK de esquema).
- Sin constructor desde `double`/`float`; factories desde `String` y desde `BigDecimal` (para el valor que devuelve el driver).
- Igualdad sobre importe normalizado + moneda (nunca `BigDecimal.equals` crudo); hashCode coherente.
- Aritmética total con redondeo `HALF_UP` explícito en cada llamada que redondea (nunca un default): suma/resta solo misma moneda (si no, `CurrencyMismatchException`), multiplicación por factor decimal, `percentage(...)`, `roundToMinorUnit(RoundingMode)`, `allocate(ratios...)` con reparto de residuo por método del resto mayor (invariante: suma de partes == total, nunca división que descarte residuo).
- Comparación total: `isZero`, `isPositive`, `isNegative`, `isGreaterThan`, `isGreaterThanOrEqual`, `isLessThan`, `isLessThanOrEqual`, `compareTo` (falla ante monedas distintas).
- Money admite negativos (el signo es información contable legítima); la restricción de signo vive en el tipo de transacción del módulo dueño, no en Money.
- Serialización en la API como `{ amount: string, currency: string }` — ese DTO vive en `web`, fuera de kernel; kernel expone `toPlainString()`/getters, no el DTO.
- Casos límite obligatorios de prueba (confia-money-rules §10 y ADR-0004 §Cumplimiento 5): cero, negativo (y rechazo cuando el tipo de transacción no lo admite — eso es de otro módulo, no de Money), un centavo, importe grande cerca de NUMERIC(14,4), HALF_UP en punto medio exacto (0.615, 2.345, 1.005) y su simetría en negativos, escala 1.0==1.00, construcción con más de 4 decimales, descuento 100%, descuento>base, beca+descuento combinados (esto ya es domain de otro módulo, Money solo ofrece las operaciones), impuesto tasa cero, CurrencyMismatchException, prorrateo con allocate, acumulación de 1000 sumas de 0.1 (usa BigDecimal así que no debería fallar, pero se prueba igual), ida y vuelta DTO, y los 4 casos de regresión permanentes de ADR-0004 (6.70×0.15, 1234.55×3, acumulación de mil 0.1, 0.1+0.2).

## Documentos y ADR relevantes

- `docs/01-arquitectura.md` §3.4 (instrumental de pruebas), §4 (kernel = Money + identificadores + errores de dominio + tipos base; ninguna regla de negocio en kernel/shared), §6 (núcleo financiero: Money es principio 4 del libro mayor).
- `docs/02-modelo-de-dominio.md` línea 595 (INV-09: totalAmount de un Charge se calcula con la regla de redondeo declarada una sola vez en Money) y línea 804 (resumen reglas de dinero).
- `docs/06-estrategia-de-testing.md`: umbrales 95%/JaCoCo y 80%/PIT sobre kernel y paquetes domain (líneas 85-87); mutación bloquea solo en main y antes de tag, en rama de trabajo solo advierte (líneas 110-116); tabla de referencia `./mvnw verify` §14.1 con los perfiles `mutation-gate` (bloquea) y `mutation-report` (solo informa) que este cambio introduce.
- ADR-0004 (fuente de verdad principal): especifica forma exacta de Money, política de redondeo de 6 puntos, por qué la moneda vive junto al importe, y la tabla de verificación/cumplimiento con los 4 casos de regresión permanentes y las pruebas de propiedad con jqwik para `allocate`.
- ADR-0011: ejemplo ilustrativo de Money con `setScale(SCALE, RoundingMode.UNNECESSARY)`, confirma que la conversión de moneda queda fuera de alcance (se difiere) y que sumar monedas distintas lanza error de dominio.
- ADR-0008: niveles y umbrales de prueba, jqwik para Money específicamente (línea 141), regla de "una puerta bloqueante no se desactiva sin ADR".
- `docs/09-roadmap-y-fases.md` línea 80: entregable 2 de F0 = exactamente el alcance de este cambio.

## Estado del entorno de construcción

- `apps/api/pom.xml`: Java 25, Spring Boot 4.1.1 BOM, Spring Modulith 2.1.1 BOM, enforcer 3.6.3, sin JaCoCo/PIT. Tiene ya un profile `no-snapshots-on-main` como precedente de "profile activado solo en CI/main" — mismo patrón aplicable a `mutation-gate` si se decide que solo bloquea en main.
- `~/.m2/repository`: JaCoCo 0.8.12 (jacoco-maven-plugin) está completamente cacheado (jar, no solo POM). **PIT (`org.pitest`) y jqwik (`net.jqwik`) no tienen ningún directorio en el repositorio local**: nunca se han descargado. Dado el problema de PKIX documentado en el propio `apps/api/pom.xml` (comentario sobre archunit 1.5.0: "a Windows PKIX trust-store gap blocks new Maven Central downloads here"), es probable que `./mvnw verify` con PIT/jqwik nuevos falle localmente en esta máquina hasta resolver ese gap de confianza. La CI de GitHub Actions corre en `ubuntu-latest` con un almacén de confianza estándar, así que no debería heredar este problema — pero es un supuesto sin verificar hasta la primera ejecución real del pipeline con estas dependencias nuevas.
- JDK: la máquina tiene JAVA_HOME por defecto en JDK 21, con JDK 25 instalado aparte en `C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot`. Cualquier ejecución local de `./mvnw verify` para este cambio necesita `JAVA_HOME` apuntando a esa ruta 25, o falla `requireJavaVersion [25,26)` del enforcer.

## Preguntas de diseño abiertas (para sdd-propose/sdd-design)

1. **Representación de moneda**: `java.util.Currency` (JDK, cualquier ISO 4217) vs enum propio `CurrencyCode` cerrado a {HNL, USD}. Los ADR piden "validado contra el conjunto habilitado" y la DB tiene `CHECK (currency IN ('HNL','USD'))` — un enum cerrado refleja mejor esa restricción y es lo que ADR-0011 ilustra (`CurrencyCode`). `java.util.Currency` sería más laxo que el esquema. Recomendación técnica: enum propio; **decisión de bajo riesgo, resoluble por el agente de diseño**, no requiere al propietario.
2. **Qué significa "errores de dominio" en kernel**: ¿una única `CurrencyMismatchException` de Money, o también un tipo base `DomainException`/`DomainError` del que las excepciones de cada módulo de negocio heredarán después? `docs/01-arquitectura.md` §4 dice que kernel contiene "tipos base" además de Money e identificadores. Es una decisión de bajo-medio riesgo: afecta la forma en que otros 12 cambios posteriores lanzarán errores de dominio. **Recomendado resolver en design.md de este cambio**, no delegarlo a un cambio futuro, porque cambiar la jerarquía de excepciones después de que 5+ módulos la usen es costoso.
3. **`allocate` — forma exacta de la API** (varargs de enteros como pesos vs `List<Integer>`, y si valida pesos negativos/cero). ADR-0004 y confia-money-rules usan `allocate(1,1,1)`; jqwik property test en confia-money-rules usa `int[]`. Detalle técnico, resuelto en diseño.
4. **Dónde vive `Percentage`**: confia-money-rules usa `Percentage.of("5")` como colaborador de Money (`percentage(Percentage, RoundingMode)`). ¿Percentage es otro objeto de valor de kernel, o Money acepta un BigDecimal/String crudo de porcentaje? Si Percentage es un tipo de kernel, entra en el alcance de este cambio (kernel = "Money, identificadores... y tipos base"); si no, se define en el módulo `catalog`/`charges` más adelante. Recomendación: incluir `Percentage` en este cambio, porque `Money.percentage(...)` es parte del contrato mínimo exigido por confia-money-rules y ADR-0004, y dejar el parámetro como `BigDecimal` crudo violaría la regla 3 de CLAUDE.md (aritmética monetaria fuera de un tipo explícito).
5. **Alcance de la aritmética de mora/impuestos/imputación**: CLAUDE.md regla 3 y confia-money-rules §3 son explícitos en que esas reglas de negocio NO viven en kernel, viven en el paquete `domain` del módulo dueño (aún no existe ningún módulo de negocio). Este cambio NO debe intentar implementarlas ni sus excepciones de negocio (p. ej. `InvalidChargeAmountException` es de `charges`, no de `kernel`). Confirmado, no es una pregunta abierta real, pero conviene declararlo explícitamente en el `proposal.md` como fuera de alcance para evitar que el agente de tareas intente colar reglas de mora aquí.
6. **`strict_tdd` y `coverage_threshold`**: `foundations-plan/exploration.md` ya recomendó pasar `strict_tdd: true` y fijar `verify.coverage_threshold` (95 kernel / 80 global aún no aplica porque no hay más módulos) como tarea explícita de este cambio. Confirmado por lectura directa de `openspec/config.yaml`.

## Tamaño y forma del cambio

- Alcance técnico: `Money`, `CurrencyCode` (enum), `Percentage` (probable), 1-2 tipos de error (`CurrencyMismatchException`, posible `DomainException` base), batería de pruebas JUnit/AssertJ con todos los casos límite de la sección 10 de confia-money-rules, pruebas de propiedad jqwik para `allocate` y para "cualquier secuencia de operaciones coincide con el cálculo a escala completa", 4 pruebas de regresión permanentes de ADR-0004, wiring de JaCoCo (umbral 95% kernel) y PIT (umbral 80%, perfiles `mutation-gate`/`mutation-report`) en `apps/api/pom.xml`, actualización de `.github/workflows/ci.yml` para invocar esos perfiles, y actualización de `openspec/config.yaml` (`strict_tdd: true`, umbrales).
- Conteo de tareas: `foundations-plan` estimó 10-12, dentro del límite de 15 de `openspec/changes/README.md`. Verosímil.
- Presupuesto de líneas: la preflight de esta sesión fija 400 líneas cambiadas como política de revisión (coincide con CLAUDE.md y `docs/15-flujo-de-trabajo-git.md`, que también dicen 400; no hay ninguna mención de 800 en la documentación del proyecto — si el orquestador tiene un valor de 800 en otra fuente, ese valor no proviene de los documentos de CONFIA). Riesgo real: una batería de pruebas que cubra ~16 casos límite obligatorios más jqwik más 4 regresiones permanentes, junto con el wiring de JaCoCo/PIT y CI, probablemente exceda 400 líneas de cambio efectivo. Se recomienda que `sdd-tasks` forecast explícitamente este riesgo y, si lo confirma, proponga dividir en (a) `Money`/`CurrencyCode`/errores + pruebas, y (b) wiring de JaCoCo/PIT/CI/config — aunque la estrategia de entrega de esta sesión ya está fijada en `single-pr`, por lo que esa división tendría que resolverse como excepción explícita o como unidades revisables dentro del mismo PR, no como cambios SDD separados.

## Riesgos

1. PIT y jqwik no están en la caché local de Maven; el gap de confianza PKIX de Windows documentado en `apps/api/pom.xml` puede bloquear su descarga en esta máquina para desarrollo local (CI en GitHub Actions no debería heredar este problema, pero no está verificado).
2. `JAVA_HOME` por defecto en esta máquina es JDK 21; toda ejecución local de `./mvnw verify` para este cambio requiere apuntar explícitamente a JDK 25.
3. El presupuesto de 400 líneas de la política de revisión de esta sesión es ajustado dado el volumen de pruebas obligatorias (casos límite + jqwik + regresiones permanentes) más el wiring de build; alto riesgo de que `sdd-tasks` deba señalarlo como forecast de riesgo alto.
4. Decisión de jerarquía de excepciones de dominio en kernel (pregunta 2) tiene efecto de arrastre sobre los 11 cambios de F0 restantes que dependen de este; conviene resolverla explícitamente en `design.md`, no dejarla implícita.
5. `mutation-gate` bloqueante solo debe activarse en `main`/antes de tag (docs/06 líneas 110-116); replicar el patrón del profile `no-snapshots-on-main` ya existente en `apps/api/pom.xml` evita reinventar el mecanismo de activación condicional por rama.

## Artefacto exploration.md

Esta exploración se guarda en Engram (`sdd/kernel-money-value-object/explore`). El agente de exploración no escribe archivos de proyecto (instrucción explícita del arnés de ejecución); si la convención híbrida de este repo exige también `openspec/changes/kernel-money-value-object/exploration.md` como archivo, ese archivo debe crearlo la siguiente fase que sí tiene permiso de escritura (`sdd-propose` u otro escritor delegado), copiando este contenido.

---

## Nota de transcripción (fase de propuesta)

El texto anterior es literal. Una afirmación no se sostiene al contrastarla con el repositorio: la
sección «Tamaño y forma del cambio» dice que no hay mención de 800 líneas en la documentación del
proyecto, pero `openspec/changes/foundations-plan/exploration.md` (líneas 12 y 109) y
`openspec/changes/archive/2026-09-17-maven-workspace-and-ci-skeleton/tasks.md` (línea 7) sí fijan
un presupuesto de 800 líneas. `CLAUDE.md` y `docs/15-flujo-de-trabajo-git.md` §3 fijan 400. La
propuesta registra este conflicto como decisión del propietario.

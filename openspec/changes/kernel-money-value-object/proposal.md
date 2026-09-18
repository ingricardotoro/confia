# Propuesta: objeto de valor `Money` en el módulo `kernel`

- **Cambio:** `kernel-money-value-object`
- **Fase del roadmap:** F0, cambio 2 de 13 (exploración `foundations-plan`, aprobada el 2026-09-15;
  entregable 2 de F0 en `docs/09-roadmap-y-fases.md`)
- **Exploración:** `openspec/changes/kernel-money-value-object/exploration.md`
- **Estado:** aprobada por el propietario del producto el 2026-09-18 (`openspec/config.yaml`,
  `rules.proposal`), con D2 y D5 pendientes antes de aplicar

## Intención

CONFIA maneja dinero real, y ninguna regla financiera posterior (cargos, libro mayor, pagos, caja,
facturación, mora) se puede escribir sin un tipo monetario exacto. Hoy `apps/api/kernel` solo
contiene `package-info.java` y una prueba de humo: no existe `Money`, y los umbrales de cobertura
(95 %) y de mutación (80 %) que exigen ADR-0004, ADR-0008 y `docs/06-estrategia-de-testing.md` no
se miden. `.github/workflows/ci.yml` (línea 5) reserva explícitamente los perfiles
`mutation-gate` y `mutation-report` para este cambio.

Este cambio entrega el tipo monetario único del sistema y, en el mismo movimiento, las puertas que
impiden que se degrade: si `Money` existe sin cobertura y mutación verificadas, la primera regla de
negocio heredaría errores de redondeo que nadie detectaría. También activa el TDD estricto que
`openspec/config.yaml` dejó pendiente «hasta F0», porque a partir de aquí sí existe un ejecutor de
pruebas y reglas que escribir primero como prueba.

## Alcance

### Dentro de alcance

1. **`Money`** en `kernel` (sin dependencias fuera del JDK):
   - `BigDecimal` normalizado a escala 4 con `RoundingMode.UNNECESSARY` al construir: un exceso de
     decimales falla, nunca se trunca en silencio (ADR-0004, ADR-0011).
   - Fábricas desde `String` y desde `BigDecimal`; **ninguna** desde `double` ni `float`.
   - Igualdad por importe normalizado y moneda, `hashCode` coherente; nunca `BigDecimal.equals`
     sin normalizar.
   - Suma y resta solo entre la misma moneda; multiplicación por factor decimal exacto con
     `HALF_UP` explícito a escala 4; `percentage(Percentage, RoundingMode)`;
     `roundToMinorUnit(RoundingMode)`; comparaciones totales (`isZero`, `isPositive`,
     `isNegative`, `isGreaterThan`, `isGreaterThanOrEqual`, `isLessThan`, `isLessThanOrEqual`,
     `compareTo`), que fallan ante monedas distintas.
   - `allocate(ratios)` con reparto del residuo por el método del resto mayor: la suma de las
     partes es exactamente el total. No existe división que descarte residuo.
   - Admite importes negativos: el signo es información contable; la restricción de signo es del
     tipo de transacción del módulo dueño.
   - Expone el importe como cadena decimal y la moneda; no conoce el DTO de la API.
2. **Tipo de moneda** cerrado al conjunto habilitado (ver decisión técnica 1).
3. **`Percentage`** como objeto de valor de `kernel`, colaborador de `Money.percentage(...)`.
4. **Errores de dominio de `kernel`**: `CurrencyMismatchException` y los errores de construcción
   inválida, con la jerarquía que el propietario confirme (decisión D1).
5. **Batería de pruebas completa**, escrita primero (TDD estricto):
   - Casos límite obligatorios: cero, negativo, un centavo, máximo representable en
     `NUMERIC(14,4)`, punto medio exacto de `HALF_UP` (`0.615`, `2.345`, `1.005`) y su simetría en
     negativos, `1.0 == 1.00`, construcción con más de cuatro decimales, tasa cero, moneda
     distinta, reparto con `allocate`.
   - Los cuatro casos de regresión permanentes de ADR-0004 §Cumplimiento 5: `6.70 × 0.15`,
     `1234.55 × 3`, acumulación de mil sumas de `0.1` y `0.1 + 0.2`.
   - Propiedades con jqwik (ADR-0004 §Cumplimiento 6 y riesgo «redondeo intermedio»): la suma de
     las partes de `allocate` es exactamente el total y ninguna parte es negativa si el total no lo
     es; toda secuencia de operaciones coincide con el cálculo a escala completa redondeado una
     sola vez al final.
6. **Cobertura con JaCoCo**: 95 % de líneas y de ramas en `kernel`, que rompe `./mvnw verify` en
   cualquier rama.
7. **Mutación con PIT** sobre `kernel`, umbral 80, con dos perfiles (`docs/06` §14.1):
   - `mutation-gate`: bloquea; se activa en la rama principal.
   - `mutation-report`: solo informa; se usa en las ramas de trabajo.
8. **Integración continua**: `.github/workflows/ci.yml` invoca `-Pmutation-gate` en `main` y
   `-Pmutation-report` en el resto, conforme a `docs/06` línea 844.
9. **`openspec/config.yaml`**: `strict_tdd: true`, `apply.tdd: true`, ejecutor `./mvnw verify`
   (en `apps/api`, JDK 25) y umbral de cobertura declarado; se retira el texto de contexto que
   describe el proyecto como «sin código».

### Fuera de alcance

- **Reglas monetarias de negocio**: mora, impuestos, imputación de pagos, descuentos y becas.
  Viven en el paquete `domain` del módulo que las posee (`CLAUDE.md`, regla 3); ninguno existe
  todavía. Tampoco sus excepciones (por ejemplo, `InvalidChargeAmountException` es de `charges`).
- **DTO de API `{ amount: string, currency: string }`** y su esquema OpenAPI: vive en `web` de los
  módulos futuros (cambio 3 en adelante).
- **Conversión entre monedas**: diferida por ADR-0011.
- **Conversión `NUMERIC` ↔ `Money` en el acceso a datos**: llega con jOOQ (cambio 5).
- **Identificadores y otros tipos base de `kernel`**: los introduce el primer cambio que los
  necesite.
- **Puerta de mutación antes de una etiqueta de versión y ejecución nocturna**: no existe todavía
  flujo de publicación; llegan con el cambio 11 (`containerization-and-cicd-pipeline`).
- **Umbral global de 80 % sobre `app`**: se activa con el primer módulo de negocio (cambio 4);
  hoy `app` solo contiene puntos de entrada y pruebas de arquitectura (supuesto registrado).
- **Reglas de ArchUnit contra coma flotante** de ADR-0004 §Cumplimiento 2: ver decisión D3.

## Capacidades

### Nuevas

- `money`: capacidad **técnica** de `kernel` que especifica el comportamiento de `Money`,
  `Percentage` y la moneda (construcción, igualdad, aritmética, redondeo, reparto, comparación y
  errores). Igual que `build-integrity`, no pertenece al catálogo de capacidades de negocio de
  `openspec/project.md`; su creación requiere la aprobación del propietario (decisión D4). Delta en
  `openspec/changes/kernel-money-value-object/specs/money/spec.md`.

### Modificadas

- `build-integrity`: se agregan dos requisitos: la cobertura de `kernel` por debajo de 95 % rompe
  la construcción; la puntuación de mutación por debajo de 80 bloquea en la rama principal y solo
  advierte en las ramas de trabajo. Si D3 se resuelve a favor, también la prohibición de coma
  flotante y de `BigDecimal.equals` fuera de `Money`.

## Enfoque

Primero el instrumental, luego el tipo:

1. Pasar `openspec/config.yaml` a TDD estricto como **primera** tarea, para que `Money` mismo se
   construya con prueba en rojo antes de cada comportamiento.
2. Cablear JaCoCo, PIT, jqwik y los dos perfiles de mutación con `kernel` todavía mínimo, y
   comprobar que el umbral rompe la construcción (misma lógica de «una regla que nunca falló no
   prueba nada» del cambio 1).
3. Construir `Money` de adentro hacia afuera: construcción e igualdad, suma y resta, comparación,
   multiplicación y porcentaje, redondeo a la unidad menor y, al final, `allocate` con sus
   propiedades de jqwik y los cuatro casos de regresión.
4. Integración continua al final, cuando `./mvnw verify` ya pasa.

`mutation-gate` reutiliza el mecanismo de activación del perfil `no-snapshots-on-main` ya presente
en `apps/api/pom.xml` (propiedad `confia.ci.mainBranch`), en lugar de inventar otro. Las versiones
exactas de PIT, del complemento de JUnit 5 para PIT y de jqwik se confirman durante la
implementación.

### Decisiones técnicas por defecto (con justificación)

1. **Moneda como `enum CurrencyCode { HNL, USD }`** cerrado, con los dígitos de la unidad menor de
   cada moneda. Refleja el `CHECK (currency IN ('HNL','USD'))` de ADR-0004 y el fragmento de
   ADR-0011. `java.util.Currency` aceptaría cualquier código ISO 4217 y sería más laxo que el
   esquema. Agregar una moneda es agregar un valor al `enum` y a la restricción, de forma
   deliberada.
2. **`UNNECESSARY` al construir y `HALF_UP` explícito en cada operación que redondea**; ningún modo
   de redondeo por defecto implícito en ninguna firma.
3. **`allocate` recibe pesos enteros positivos** (varargs o lista, a fijar en diseño) y rechaza
   pesos negativos, todos cero o vacíos.
4. **`Percentage` en `kernel`**: dejar el porcentaje como `BigDecimal` crudo pondría aritmética
   monetaria fuera de un tipo explícito (`CLAUDE.md`, regla 3).
5. **jqwik en alcance `test`** en `kernel`: no toca la regla `enforce-kernel-purity`, que solo
   prohíbe alcances de compilación y ejecución.

## Áreas afectadas

| Área | Impacto | Descripción |
|---|---|---|
| `apps/api/kernel/src/main/java/com/confia/kernel/` | Nueva | `Money`, `CurrencyCode`, `Percentage`, errores de dominio |
| `apps/api/kernel/src/test/java/com/confia/kernel/` | Nueva | Pruebas unitarias, de regresión y de propiedad |
| `apps/api/kernel/pom.xml` | Modificada | jqwik en alcance `test`; JaCoCo y PIT activos en el módulo |
| `apps/api/pom.xml` | Modificada | `pluginManagement` de JaCoCo y PIT; perfiles `mutation-gate` y `mutation-report` |
| `.github/workflows/ci.yml` | Modificada | Selección de perfil de mutación por rama; se retira el comentario de la línea 5 |
| `openspec/config.yaml` | Modificada | TDD estricto, ejecutor y umbral de cobertura |
| `openspec/specs/build-integrity/spec.md` | Delta al archivar | Requisitos de cobertura y mutación |

## Tamaño estimado y presupuesto de revisión

Pronóstico de líneas de autor (adiciones más eliminaciones):

| Bloque | Estimación |
|---|---|
| Código de producción (`Money`, `CurrencyCode`, `Percentage`, errores) | 360 a 480 |
| Pruebas (unitarias, regresión, jqwik) | 580 a 770 |
| POM, integración continua y `config.yaml` | 125 a 195 |
| **Total de código** | **1 065 a 1 445** |
| Artefactos de OpenSpec (exploración, propuesta, especificaciones, diseño, tareas, verificación) | 800 a 1 200 adicionales |

**El cambio supera el presupuesto de revisión con cualquiera de los dos valores vigentes**, y
también lo supera el código de producción más el cableado sin contar pruebas.

**Conflicto de presupuesto sin resolver.** `CLAUDE.md` («Convenciones de Git») y
`docs/15-flujo-de-trabajo-git.md` §3 fijan **400** líneas de cambio efectivo, igual que la política
de esta sesión. Pero `openspec/changes/foundations-plan/exploration.md` (líneas 12 y 109), el
registro de inicialización del proyecto en Engram (`sdd-init`, observación #333) y `tasks.md` del cambio 1 (línea 7) usan
**800** (`review_budget_lines`). El cambio 1 se entregó con `size:exception` aceptada solo para él,
y su informe de verificación dice que esa excepción no se hereda. Esta propuesta no elige entre los
dos valores (decisión D2).

**Opciones de entrega, sin decidir:**

- **A. `size:exception` para este cambio**, como en el cambio 1. Un PR único; revisión más pesada.
- **B. PR encadenados dentro de este cambio SDD.** Exige cambiar la estrategia de entrega de la
  sesión (`single-pr`). Corte natural: (1) instrumental y `config.yaml`; (2) construcción,
  igualdad, suma, resta y comparación; (3) multiplicación, `Percentage`, redondeo, `allocate`,
  jqwik y regresiones. Cada corte compila y pasa su umbral.
- **C. Dos cambios SDD**: instrumental de calidad y `Money`. Duplica el ciclo SDD completo y deja
  al primero sin código que medir.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| PIT y jqwik nunca se descargaron en esta máquina; el problema de confianza PKIX de Windows documentado en `apps/api/pom.xml` puede impedir su descarga | Alta | La integración continua en `ubuntu-latest` es la fuente de verdad del resultado; alternativa, importar al almacén de confianza del JDK 25 la cadena que falta. Nunca se omite una prueba ni se baja un umbral para pasar en local. El supuesto de que la integración continua sí descarga está sin verificar hasta la primera ejecución |
| `JAVA_HOME` apunta por defecto a JDK 21; el enforcer exige `[25,26)` | Alta | Toda ejecución local fija `JAVA_HOME` en `C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot`; se documenta en las tareas |
| `mutation-gate` bloquea ramas de trabajo, en contra de `docs/06` (líneas 112 a 116) | Media | Selección de perfil por rama en `ci.yml` y activación por `confia.ci.mainBranch`; una tarea demuestra que una puntuación baja advierte en una rama y bloquea en `main` |
| La puerta de mutación «antes de etiqueta» queda sin implementar | Cierta | Declarado fuera de alcance; asignado al cambio 11 |
| Compatibilidad de PIT con Java 25 y con la versión de JUnit que gestiona Spring Boot 4.1 no verificada | Media | Prueba de humo de PIT antes de escribir `Money`; si no es compatible, se detiene y se informa, nunca se desactiva la puerta (ADR-0008) |
| La jerarquía de errores de dominio condiciona a los once cambios siguientes | Media | Decisión D1 explícita en esta propuesta y fijada en `design.md` |
| El tamaño supera el presupuesto de revisión | Alta | Decisiones D2 y D5 antes de aplicar |
| Pruebas de propiedad lentas o no deterministas | Baja | Semilla fija registrada en caso de fallo y número de intentos acotado |

## Plan de reversión

No hay base de datos, esquema, despliegue ni datos. Revertir es cerrar el PR o revertir su commit
de fusión en `main`, lo que retira `Money`, las puertas y el cambio de `openspec/config.yaml` a la
vez. Una puerta que bloquee por error no se desactiva con una bandera (ADR-0008): se revierte el
commit que la introdujo o se corrige con un ADR. El punto de no retorno práctico llega cuando el
cambio 4 o el 5 dependan de `Money` y de su jerarquía de errores.

## Dependencias

- Cambio 1 (`maven-workspace-and-ci-skeleton`), archivado el 2026-09-17: POM padre, `kernel`,
  enforcer e integración continua.
- JDK 25 en la máquina de desarrollo y en la integración continua.
- Acceso a Maven Central para PIT y jqwik (ver primer riesgo).

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` termina en verde con JDK 25, en la integración continua.
- [ ] No existe forma de construir `Money` desde `double` ni `float`: el intento no compila.
- [ ] `Money.of("1.0", HNL)` es igual a `Money.of("1.00", HNL)` y tiene el mismo `hashCode`.
- [ ] Construir con más de cuatro decimales falla en vez de truncar.
- [ ] Sumar o comparar importes de monedas distintas lanza `CurrencyMismatchException`.
- [ ] Los cuatro casos de regresión de ADR-0004 pasan y quedan marcados como permanentes.
- [ ] La propiedad de jqwik de `allocate` (suma exacta, sin partes negativas) pasa.
- [ ] Cobertura de líneas y ramas de `kernel` igual o superior a 95 %; bajarla rompe la
      construcción.
- [ ] Puntuación de mutación de `kernel` igual o superior a 80; por debajo, `mutation-gate` rompe
      la construcción y `mutation-report` solo informa.
- [ ] En la integración continua, `main` usa `mutation-gate` y las ramas `change/**` usan
      `mutation-report`.
- [ ] `openspec/config.yaml` declara `strict_tdd: true` y el ejecutor `./mvnw verify`.
- [ ] `kernel` sigue sin dependencias de compilación o ejecución fuera del JDK.

## Decisiones que confirma el propietario al aprobar

El modo de ejecución es automático: estas decisiones quedan registradas en lugar de preguntarse en
conversación. El orquestador las presenta.

- **D1. Jerarquía de errores de dominio en `kernel`.**
  - *Recomendación:* una clase base abstracta y no comprobada, `DomainException extends
    RuntimeException`, con un código estable legible por máquina (`code()`), de la que heredan
    `CurrencyMismatchException` y los errores de los módulos futuros.
  - *A favor:* un único punto para traducir errores de dominio a respuestas HTTP y a la bitácora,
    sin que `web` conozca cada excepción; el código estable permite catálogos de i18n; cambiar la
    jerarquía cuando cinco módulos la usen es costoso.
  - *En contra:* fija un contrato antes de tener evidencia de más de un consumidor; riesgo de que
    la base crezca con comportamiento ajeno a `kernel`.
  - *Alternativa:* solo excepciones concretas en `kernel`, y la base se decide con el primer módulo
    de negocio (cambio 4). Más simple hoy; traslada la decisión y su migración.
- **D2. Presupuesto de revisión: 400 u 800 líneas.** Hay fuentes vigentes con ambos valores (ver
  «Tamaño estimado»). La propuesta no elige.
- **D3. Reglas de ArchUnit contra coma flotante y contra `BigDecimal.equals` fuera de `Money`**
  (ADR-0004 §Cumplimiento 2). *Recomendación:* diferirlas a un cambio nombrado explícitamente para
  que no se pierdan, porque el tamaño ya excede el presupuesto; incluirlas aquí costaría unas 60 a
  100 líneas más. Si se difieren, hay que asignarlas a un cambio concreto.
- **D4. Crear la capacidad técnica `money`** en `openspec/specs/`, con el precedente de
  `build-integrity`.
- **D5. Forma de entrega ante el exceso de tamaño**: opción A, B o C de «Tamaño estimado».

**Resolución del propietario (2026-09-18):**

- **D1 aprobada** según la recomendación: `DomainException` abstracta y no comprobada, con `code()`.
- **D3 aprobada:** las reglas de ArchUnit se difieren al cambio 4
  (`institution-root-and-multitenancy-baseline`), que ya toca ArchUnit por el rojo programado de
  ADR-0018.
- **D4 aprobada:** se crea la capacidad técnica `money`.
- El umbral global de 80 % sobre `app` espera al cambio 4.
- **D2 y D5 siguen pendientes y bloquean la fase de aplicación**, no la de especificación ni la de
  diseño.

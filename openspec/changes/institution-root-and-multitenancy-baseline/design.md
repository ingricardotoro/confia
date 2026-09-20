# Diseño: raíz de institución y base multi-institución

Cambio 4 de F0. Implementa la propuesta aprobada el 2026-09-19 (D1 = opción 2, sin tabla; D2 = solo
`Institution`; P3 aprobada, con `InstitutionId` en `kernel`). P1 (forma de entrega) sigue pendiente
del propietario; P2 se resuelve aquí de forma condicionada a una sonda (decisión 4). La
especificación se escribe en paralelo: donde este diseño propone un dato que la especificación fija
(la lista exacta de códigos de error, los límites de longitud), **prevalece la especificación** y
esta tabla se alinea antes de la fase de tareas.

## Enfoque técnico

Tres bloques que coinciden con los cortes naturales de la propuesta, en este orden, de modo que el
orden sirve igual para un solo pull request que para una cadena (P1):

1. **Corte A — puertas y reglas, sin módulo.** Las tres reglas de ArchUnit de ADR-0004
   §Cumplimiento 2 con sus fixtures permanentes (ciclo B de la propuesta), y JaCoCo en `app` con el
   umbral global de 80 %. Ninguna de las dos cosas necesita el módulo `organization`: las reglas de
   ADR-0004 seleccionan código de `kernel`, que las pruebas de arquitectura ya importan (decisión 3).
2. **Corte B — raíz de institución.** `InstitutionId` en `kernel`; la primera clase de
   `com.confia.organization.domain` dispara el rojo programado de ADR-0018 (ciclo A de la
   propuesta), que se cierra antes de seguir; después se cablean los umbrales del paquete `domain`
   (95 % y PIT 80) y se construye el resto del agregado bajo esas puertas.
3. **Corte C — aplicación.** Puertos `InstitutionRepository` y `CurrentInstitutionProvider`, el caso
   de uso `ResolveCurrentInstitution` y sus dos errores, con dobles en memoria solo en pruebas.

Los dos ciclos de ArchUnit (ADR-0004 y ADR-0018) quedan separados en commits y cortes distintos,
como recomendó la exploración, porque el invariante de conteo de `SuppressionCitesAdrTest` es
frágil. Todo con TDD estricto (`openspec/config.yaml`, `strict_tdd: true`).

## Evidencia obtenida en esta fase y límites

| Pregunta | Resultado | Evidencia |
|---|---|---|
| P2: ¿`layeredArchitecture()` de ArchUnit 1.4.2 falla con capas declaradas sin clases? | **No ejecutada en esta fase.** El agente de diseño no dispone de ninguna herramienta para ejecutar procesos (ni Maven ni `javap`), así que la sonda pedida no pudo correr. Inferencia fuerte: **sí falla**, con `Layer '<nombre>' is empty` | (1) La prueba de producción actual pasa con las cuatro capas vacías solo gracias a `allowEmptyShould(true)`, luego existe una comprobación que esa llamada neutraliza. (2) `ArchitectureTestSupport.assertRuleRejects` filtra el texto `"is empty"`, añadido en la tarea 5.5 del cambio 1 tras observar ese mensaje al borrar el paquete de fixtures, con la versión 1.4.2 ya resuelta (Engram #354). (3) La prueba de ADR-0018 que sigue abierta («Por confirmar durante la implementación») es exactamente esta |
| ¿`productionClasses()` importa `kernel`? | **Sí, por lectura; se vuelve verificación ejecutable** | `ClassFileImporter.importPackages("com.confia")` recorre toda la ruta de clases, no solo el módulo; `confia-kernel` es dependencia de alcance `compile` de `app` y está en la ruta de clases de Surefire; `DO_NOT_INCLUDE_TESTS` solo excluye ubicaciones de clases de prueba. El diseño del cambio 2 (decisión 1) ya razonaba sobre `kernel` como módulo visible desde `com.confia`. Se añade una aserción permanente (decisión 3) y, además, `failOnEmptyShould=true` rompería la regla sobre tipos monetarios si `kernel` no se importara |
| RTN: formato según fuente primaria del SAR | **No verificable en esta fase** (sin acceso web). `docs/04-cumplimiento-fiscal-sar.md` línea 32 ya lo declara «PENDIENTE DE VALIDACIÓN», y los ejemplos de `docs/ui-ux/04-patrones-de-interaccion.md` (líneas 823 y 838) muestran 14 y 13 dígitos, contradictorios entre sí | Se valida solo lo demostrable (decisión 7). No se inventa longitud exacta ni dígito verificador |

La sonda de P2 pasa a ser el **primer paso obligatorio de la aplicación** (sección «Sonda de P2»),
con resultado registrado antes de escribir la solución del ciclo A. ADR-0020 queda redactado en
estado Propuesto con una condición de aplicación explícita: si la sonda lo desmiente, pasa a
Rechazado y P2 desaparece.

## Decisiones de arquitectura

### 1. Distribución de paquetes

**Elección:**

```
apps/api/kernel/src/main/java/com/confia/kernel/InstitutionId.java
apps/api/app/src/main/java/com/confia/organization/
├── package-info.java                  # capacidad organization; fuera de alcance: tabla (cambio 5)
├── domain/                            # Institution y sus errores; sin framework, sin E/S
└── application/                       # puertos y caso de uso; sin anotaciones de Spring
```

Sin paquetes `infrastructure` ni `web` (propuesta, «Dentro de alcance», punto 4). Nada se registra en
`AdminApplication`, `PortalApplication` ni `WorkerApplication`: su `scanBasePackages` sigue siendo
`com.confia.bootstrap`, y el caso de uso no es un bean hasta que existan sus adaptadores.

**Alternativas descartadas:** `InstitutionId` en `com.confia.organization` (paquete raíz del módulo,
API para Spring Modulith), que obligaría a todos los módulos a depender de `organization` por un tipo;
en `organization.domain`, que otros módulos no pueden importar (regla de frontera y Spring
Modulith). Ya decidido por el propietario en P3.

**Razón y verificación colateral:** `com.confia.organization` es un subpaquete directo de
`com.confia`, así que Spring Modulith lo trata como módulo; `domain` y `application` son internos y
nadie de fuera los usa. `organization` depende solo del paquete base de `kernel`, que es su API
(diseño del cambio 2, decisión 1). La regla de capas considera irrelevantes las dependencias hacia
`kernel`, porque `com.confia.kernel` no coincide con ninguna capa
(`consideringOnlyDependenciesInLayers()`). `NoTechnicalLayerPackageNamesTest` no se ve afectado.

### 2. `InstitutionId` como `record` en `kernel`

**Elección:** `public record InstitutionId(UUID value)` con constructor compacto que aplica
`Objects.requireNonNull(value, "value")`. Sin fábrica adicional, sin `parse(String)` y sin generador:
no tienen consumidor en este cambio (el cambio 7 añadirá la lectura desde el token si la necesita,
el cambio 5 la generación si la necesita).

**Alternativa descartada:** clase final con constructor privado, como `Money`.
**Razón:** la objeción de `Money` contra `record` era la igualdad generada sobre `BigDecimal.equals`;
aquí la igualdad de `UUID` es exacta. Un `record` es la forma más corta y correcta de un
identificador inmutable. El nulo es un error de programación (ADR-0019, punto 6): lanza
`NullPointerException`, no un error de dominio, así que **no se añade ningún código a `kernel`** y
`KernelErrorCodesTest` no cambia. `kernel` sigue sin dependencias fuera del JDK. JaCoCo filtra los
métodos generados de los `record`; la prueba cubre construcción, nulo e igualdad, y PIT (ya
adherido con `com.confia.kernel.*`) muta el constructor compacto.

### 3. Reglas de ADR-0004 §Cumplimiento 2, no vacías por construcción

**Elección:** una clase de prueba nueva, `MonetaryFloatingPointTest`, en `com.confia.architecture`,
con cinco reglas y el patrón de dos mitades de las demás (producción en verde, fixture rechazado
nombrando la clase infractora):

| Regla | Selección | Prohíbe |
|---|---|---|
| `NO_BIG_DECIMAL_FROM_FLOATING_POINT` | todas las clases | `new BigDecimal(double)`, `new BigDecimal(double, MathContext)`, `BigDecimal.valueOf(double)` |
| `NO_BIG_DECIMAL_EQUALS_OUTSIDE_MONEY` | clases que no pertenecen a `Money` (ni a sus anidadas) | `BigDecimal.equals(Object)` |
| `NO_FLOATING_POINT_FIELDS_IN_MONETARY_TYPES` | campos declarados en tipos monetarios | tipo `double`, `float`, `Double`, `Float` |
| `NO_FLOATING_POINT_RETURNS_IN_MONETARY_TYPES` | métodos declarados en tipos monetarios | retorno de esos cuatro tipos |
| `NO_FLOATING_POINT_PARAMETERS_IN_MONETARY_TYPES` | constructores y métodos declarados en tipos monetarios | algún parámetro de esos cuatro tipos (condición propia) |

**Tipo monetario** (predicado `MONETARY_TYPE`, descrito en el mensaje de la regla): `Money`,
`Percentage`, o toda clase que declare un campo cuyo tipo sin parámetros sea `Money` o `Percentage`.

**Por qué no son vacías:** las dos primeras seleccionan todo el código de producción, que ya contiene
`com.confia.bootstrap` y `kernel`. Las tres últimas seleccionan los miembros de `Money` y
`Percentage`, que están en la ruta de clases (ver «Evidencia»). Ninguna necesita
`allowEmptyShould(true)` ni excepción nueva, que además ADR-0018 §1.4 ya no ampararía. Una prueba
más de la misma clase, `productionImportIncludesTheKernelMonetaryTypes`, afirma que
`productionClasses()` contiene `Money` y `Percentage`: deja escrita la premisa de la que depende la
no vacuidad, en lugar de dejarla implícita.

**Precisión que importa:** `Money.multiply(long)` llama a `BigDecimal.valueOf(long)` (línea 181).
La regla se escribe por firma exacta (`callMethod(BigDecimal.class, "valueOf", double.class)`), no
por nombre, de modo que la mitad de producción pasa sin excepción; eso mismo prueba que la regla no
confunde las dos sobrecargas.

**Límites declarados (no se ocultan):** la regla de `equals` detecta la llamada con tipo estático
`BigDecimal`; un `Objects.equals(a, b)` o una llamada a través de `Object` no se detecta. La
definición de tipo monetario no mira campos genéricos (`List<Money>`, `Optional<Money>`). Se
aceptan: cubren el texto de ADR-0004 y la revisión cubre el resto. `MoneyApiShapeTest` de `kernel`
se conserva: protege a `kernel` por sí solo, sin depender de `app`.

**Fixtures permanentes**, en `com.confia.architecture.fixture.monetary` (nombre sin segmentos de
capa ni nombres técnicos prohibidos):

| Clase | Viola | Contenido |
|---|---|---|
| `FloatingPointBigDecimal` | regla 1 | `new BigDecimal(0.1)`, `new BigDecimal(0.1, MathContext.DECIMAL64)` y `BigDecimal.valueOf(0.1)` en métodos distintos |
| `RawBigDecimalComparison` | regla 2 | `left.equals(right)` con `BigDecimal` como tipo estático |
| `FloatingPointPriceTag` | reglas 3, 4 y 5 | campo `Money price` (lo hace monetario), campo `double discountRate`, método `double discountRate()`, método `void applyRate(float rate)` |

**No vacuidad de cada fixture (lección de Engram #354):** durante la aplicación, sin comprometerlo,
se **neutraliza** cada fixture quitando solo la llamada o el miembro infractor y se observa que su
prueba de rechazo falla. Borrar el paquete es una prueba más débil y no cuenta.

### 4. P2: capas `infrastructure` y `web` vacías (ADR-0020, condicionado a la sonda)

**Elección, si la sonda confirma la inferencia:** la opción A de ADR-0020. La mitad de producción
declara `Infrastructure` y `Web` con `optionalLayer(...)`; la mitad de fixture declara las cuatro
capas como obligatorias; el inventario de ADR-0018 gana un tipo de marcador y dos entradas, una por
capa, cada una con su condición de caducidad; el escáner cataloga `optionalLayer(` y prohíbe
`withOptionalLayers(true)`.

**Alternativas descartadas:** `allowEmptyShould(true)` sobre toda la regla con condición reescrita
(también apagaría el control de vacío de `Domain` y `Application`, que ya tienen clases);
`withOptionalLayers(true)` (vuelve opcionales las cuatro capas); clases sin propósito en `src/main`
(opción C de ADR-0018); partir la regla compuesta en reglas sueltas por capa (deshace el hallazgo C1
del cambio 1).

**Razón:** es la excepción más estrecha posible: solo relaja la comprobación de vacío de las dos
capas vacías, y cada una caduca por separado (`Infrastructure` con el cambio 5, `Web` con el primer
controlador de negocio). Por ser una excepción de naturaleza distinta a la de ADR-0018 §1.4, se
eleva a ADR (ADR-0018 §4; `docs/13-metodologia-sdd.md`, regla 5), no se entierra aquí.

**Tabla de resultados de la sonda:**

| Resultado observado | Consecuencia |
|---|---|
| (a) Sin `allowEmptyShould` la regla pasa con `infrastructure` y `web` vacías | P2 desaparece. ADR-0020 pasa a Rechazado (se conserva). Se omite el paso B4 de la secuencia; el inventario queda con lista vacía y el escáner sin cambios |
| (b) Falla con `Layer 'Infrastructure' is empty` / `Layer 'Web' is empty` y `optionalLayer` lo resuelve sin relajar `Domain` | Se aplica ADR-0020 **cuando el propietario lo acepte** (P2). Hasta entonces, la aplicación no pasa del paso B3 |
| (c) Falla y `optionalLayer` no existe en 1.4.2 o no evita el fallo (por ejemplo, las subreglas de dependencia fallan sobre una capa vacía) | La aplicación se detiene y se informa al propietario con el mensaje exacto. No se usa `withOptionalLayers`, no se vuelve a `allowEmptyShould` ni se crean clases de relleno sin decisión |

### 5. Agregado `Institution`

**Elección:** entidad con identidad, `public final class Institution`, constructor privado y una sola
fábrica `create(...)` que produce una institución activa. Los atributos son finales salvo el estado
de activación, que cambia solo por `activate()` y `deactivate()`. Igualdad y `hashCode` por `id`.

**Alternativas descartadas:** agregado inmutable con `withActive(boolean)` (semántica de valor para
algo que tiene identidad y ciclo de vida; el repositorio del cambio 5 persiste el agregado cargado);
`record` (igualdad por todos los atributos, contraria a una entidad); una fábrica de rehidratación
`restore(..., boolean active)` (sin consumidor hasta el cambio 5, que la añade con su repositorio).

**Atributos e invariantes** (orden de evaluación: primero los nulos de los siete argumentos
obligatorios, después las reglas de negocio en el orden de la tabla; una sola excepción, la primera
que aplica). Un argumento obligatorio nulo es un error de programación y lanza
`NullPointerException` (ADR-0019). `tradeName` es la única excepción: es opcional y `null` significa
«sin nombre comercial», así que se acepta; una cadena en blanco sí se rechaza.

| Atributo | Tipo | Regla | Código si se incumple |
|---|---|---|---|
| `id` | `InstitutionId` | no nulo | `NullPointerException` |
| `legalName` | `String` | se guarda sin espacios de borde (`strip()`); no vacío tras ello | `institution-legal-name-blank` |
| | | como máximo 200 caracteres (puntos de código) | `institution-legal-name-too-long` |
| `tradeName` | `String` | mismas reglas que `legalName` | `institution-trade-name-blank`, `institution-trade-name-too-long` |
| `rtn` | `String` | solo dígitos ASCII, entre 1 y 20; sin separadores; sin normalización | `institution-rtn-invalid` |
| `address` | `String` | `strip()`; no vacío; como máximo 500 caracteres | `institution-address-blank`, `institution-address-too-long` |
| `defaultCurrency` | `CurrencyCode` | no nulo; el `enum` ya limita a `HNL` y `USD` | `NullPointerException` |
| `locale` | `java.util.Locale` | idioma no vacío (rechaza `Locale.ROOT`, que es lo que devuelve `Locale.forLanguageTag` ante una etiqueta inválida) | `institution-locale-invalid` |
| `timezone` | `java.time.ZoneId` | identificador de región, no desplazamiento fijo (`ZoneOffset`) | `institution-timezone-invalid` |
| `active` | `boolean` | `true` al crear | — |

Transiciones: `activate()` sobre una institución activa lanza `institution-already-active`;
`deactivate()` sobre una inactiva lanza `institution-already-inactive`. No son idempotentes a
propósito: la propuesta las declara invariantes de transición, y un doble cambio de estado es una
señal de defecto del llamador, no una operación válida.

**Razones puntuales:**

- **Longitudes por puntos de código**, no por unidades UTF-16, para coincidir con
  `varchar(n)`/`text` de PostgreSQL que cuenta caracteres. Los valores 200 y 500 son una propuesta de
  este diseño (guarda técnica contra entrada no acotada); **los fija la especificación** y el cambio 5
  los refleja en la columna.
- **Zona de región** porque ADR-0011, punto 5, hace de la zona un dato de configuración de la
  institución y un desplazamiento fijo no conserva las reglas históricas ni futuras de la región
  (`America/Tegucigalpa`, `docs/06` línea 734).
- **Sin validar `locale` contra el catálogo** `es-HN`: ADR-0011 difiere los demás catálogos, y atar
  el dominio al catálogo existente convertiría una decisión de contenido en una de modelo.
- **Mensajes técnicos en inglés, sin repetir la entrada** (`CLAUDE.md`, regla 11): «legal name
  exceeds 200 characters», nunca el nombre ni el RTN.
- `toString()` muestra `id` y `tradeName`; no incluye el RTN ni la dirección.

### 6. Errores de dominio de `organization` (ADR-0019)

**Elección:** cuatro clases en `com.confia.organization.domain`, todas `final` y subclases de
`DomainException`, con el patrón de fábricas por causa del cambio 2 (constructor privado, fábricas de
visibilidad de paquete cuando solo las usa el agregado del mismo paquete):

| Clase | Códigos | Corte |
|---|---|---|
| `InvalidInstitutionException` | `institution-legal-name-blank`, `institution-legal-name-too-long`, `institution-trade-name-blank`, `institution-trade-name-too-long`, `institution-rtn-invalid`, `institution-address-blank`, `institution-address-too-long`, `institution-locale-invalid`, `institution-timezone-invalid` | B |
| `InstitutionStateException` | `institution-already-active`, `institution-already-inactive` | B |
| `InstitutionNotFoundException` | `institution-not-found` (constructor público: lo lanza `application`) | C |
| `InstitutionInactiveException` | `institution-inactive` (constructor público: lo lanza `application`) | C |

Trece códigos, todos con el prefijo `institution-`, que los separa por construcción de los ocho de
`kernel`. `OrganizationErrorCodesTest` (en `organization.domain`, espejo de `KernelErrorCodesTest`)
fija la lista cerrada, el formato kebab-case, la ausencia de repetidos, el prefijo y que cada clase
es `DomainException`. En el corte B afirma once códigos; el corte C añade los dos restantes en el
mismo commit que sus clases.

**Alternativa descartada:** una clase por código (trece clases casi vacías, el doble de líneas sin
información nueva) y una sola clase para todo (mezcla invariantes de construcción, de transición y
de resolución, que el `web` futuro traduce a estados HTTP distintos).

`institution-inactive` y `institution-already-inactive` son distintos a propósito: el primero es
«no se puede operar con esta institución», el segundo «transición inválida». Que el caso de uso
distinga inexistente de inactiva no filtra información a un cliente, porque el identificador nunca
proviene del cliente (ADR-0009); la traducción a Problem Details la decide el cambio que añada `web`.

### 7. RTN: validación mínima demostrable

**Elección:** solo dígitos ASCII (`^[0-9]{1,20}$`), un único código `institution-rtn-invalid` que
cubre vacío, caracteres no numéricos y exceso de longitud. El límite de 20 es una guarda técnica
contra entrada no acotada, **no una regla fiscal**, y así se documenta en el Javadoc.

**Pendiente, registrado y no inventado:** longitud exacta, máscara de captura y cualquier dígito
verificador. Base de lo que sí se valida: `docs/04-cumplimiento-fiscal-sar.md` línea 32, documento
del propio proyecto, que declara el RTN «formato numérico de longitud fija». Cuando exista una fuente
primaria del SAR, un cambio explícito endurece la regla y actualiza la especificación.

**Alternativa descartada:** fijar 14 dígitos por memoria o por los ejemplos de la documentación de
interfaz, que se contradicen entre sí (14 y 13).

### 8. Capa `application`: puertos y caso de uso

**Elección:**

- `InstitutionRepository` (puerto de salida): `Optional<Institution> findById(InstitutionId id)`.
  Lo implementa el cambio 5 con jOOQ. Solo lectura: no hay caso de uso de escritura.
- `CurrentInstitutionProvider` (puerto de salida): `InstitutionId currentInstitutionId()`. Su
  Javadoc fija el contrato de ADR-0009: el valor se deriva del token autenticado y **nunca** de un
  parámetro, cabecera o cuerpo del cliente; la ausencia de sesión autenticada la resuelve el
  adaptador del cambio 7 antes de llegar aquí, no este puerto.
- `ResolveCurrentInstitution` (caso de uso, clase `final` sin anotaciones de Spring): recibe los dos
  puertos por constructor (`requireNonNull`), y `Institution execute()` devuelve la institución
  activa o lanza `InstitutionNotFoundException` o `InstitutionInactiveException`.

**Alternativas descartadas:** interfaz de puerto de entrada más implementación (sin consumidor que
dependa de la abstracción; la añade el primer `web` si la necesita); `Optional` en
`CurrentInstitutionProvider` (llevaría la decisión de autenticación a la aplicación); registrar el
caso de uso como bean (sin adaptadores el contexto no arrancaría; propuesta, punto 3).

**Dobles en memoria** solo en `src/test`: `InMemoryInstitutionRepository` y
`FixedCurrentInstitutionProvider`, en `com.confia.organization.application` de las fuentes de
prueba. `DO_NOT_INCLUDE_TESTS` los deja fuera de las reglas de producción.

### 9. Puertas de calidad en `app`

**Elección:** `app/pom.xml` declara `jacoco-maven-plugin` (versión y ejecuciones `prepare-agent` y
`report` heredadas del padre) con una ejecución propia `jacoco-check` en `verify` y dos reglas:

```xml
<rule>
  <element>BUNDLE</element>
  <limits>
    <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.80</minimum></limit>
    <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.80</minimum></limit>
  </limits>
</rule>
<rule>
  <element>PACKAGE</element>
  <includes>
    <include>com.confia.*.domain</include>
    <include>com.confia.*.domain.*</include>
  </includes>
  <limits>
    <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.95</minimum></limit>
    <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.95</minimum></limit>
  </limits>
</rule>
```

Y se adhiere a PIT declarando `pitest-maven` con `targetClasses` y `targetTests` =
`com.confia.*.domain.*`, más `junit-platform-launcher` en alcance `test` (mismo motivo que en
`kernel`: `pitest-junit5-plugin` 1.2+ lo exige). Los perfiles `mutation-gate` y `mutation-report`
del padre se reutilizan sin cambios: bloquea en `main`, informa en ramas.

**Alternativas descartadas:** fijar `com.confia.organization.domain` literalmente (la especificación
`build-integrity` exige el umbral en el paquete `domain` de **cada** módulo; con el comodín, el
cambio 7 queda cubierto sin tocar el POM); declarar el umbral en el padre (aplicaría a `kernel`, que
tiene el suyo).

**Por corte:** la regla `BUNDLE` de 80 % va en el corte A **si** la medición previa de `app` con solo
`com.confia.bootstrap` alcanza 80 % de líneas y de ramas (ver «Por confirmar», punto 5); si no, se
mueve al corte B, donde `organization` la eleva. Nunca se baja el umbral ni se excluye
`ConfiaApplication.main`. La regla `PACKAGE` y la adhesión a PIT van en el corte B, en el commit
siguiente al de la primera clase de `organization.domain`: sin clases, PIT falla por
`failWhenNoMutations=true` y la regla de JaCoCo no mide nada.

**Escáner:** ninguna de estas líneas es un marcador de supresión (`<include>` no lo es; `<exclude>`
sí). No se añade ninguna exclusión.

### 10. `openspec/config.yaml`

Solo el comentario de `coverage_threshold`:

```yaml
    coverage_threshold: 95 # kernel and every module's domain package, line+branch (JaCoCo); 80 global on app
```

## Flujo de datos

Construcción del agregado:

```
create(id, legalName, tradeName, rtn, address, currency, locale, zone)
   │
   ├── requireNonNull de los siete obligatorios (todos salvo tradeName) ──> NullPointerException
   ├── strip + blank + longitud (legalName, address; tradeName solo si no es null)
   ├── ^[0-9]{1,20}$ (rtn)
   ├── idioma no vacío (locale), no ZoneOffset (zone)
   │        └── InvalidInstitutionException(code)        (primera regla incumplida)
   └── Institution(activa)
            ├── activate()   ──> InstitutionStateException(institution-already-active)
            └── deactivate() ──> InstitutionStateException(institution-already-inactive)
```

Resolución de la institución en curso (diagrama de secuencia; adaptadores reales en los cambios 5
y 7, dobles en memoria en este cambio):

```
web (futuro)      ResolveCurrentInstitution   CurrentInstitutionProvider   InstitutionRepository
    │ execute()           │                          │                           │
    │────────────────────>│ currentInstitutionId()   │                           │
    │                     │─────────────────────────>│ (del token, cambio 7)     │
    │                     │<──────── InstitutionId ──│                           │
    │                     │ findById(id)                                         │
    │                     │─────────────────────────────────────────────────────>│ (jOOQ, cambio 5)
    │                     │<──────────────────────────── Optional<Institution> ──│
    │                     │ vacío     ──> InstitutionNotFoundException (institution-not-found)
    │                     │ inactiva  ──> InstitutionInactiveException (institution-inactive)
    │<──── Institution ───│ activa
```

Ciclo A (rojo programado de ADR-0018 y P2), diagrama de secuencia de la construcción:

```
primera clase en organization.domain
   │
   ├─> EmptyShouldExceptionInventoryTest ── ROJO: noBusinessModuleExistsYet == false
   │        (se registra el mensaje: es el rojo programado de ADR-0018)
   ├─> se retiran allowEmptyShould(true), noBusinessModuleExistsYet, LAYER_SEGMENTS y la entrada
   │        SuppressionCitesAdrTest: 0 llamadas == 0 entradas ── VERDE
   ├─> LayeredArchitectureTest.productionCodeRespectsLayering
   │        ├── VERDE ──> P2 desaparece (ADR-0020 a Rechazado)
   │        └── ROJO «Layer 'Infrastructure' is empty», «Layer 'Web' is empty» ──> P2 (ADR-0020)
   └─> (con ADR-0020 aceptado) marcador + entradas + optionalLayer ── VERDE
```

## Cambios de archivos

| Archivo | Acción | Corte | Descripción |
|---|---|---|---|
| `apps/api/app/src/test/java/com/confia/architecture/MonetaryFloatingPointTest.java` | Crear | A | Cinco reglas de ADR-0004 §Cumplimiento 2, mitades de producción y de fixture, premisa de importación de `kernel` |
| `apps/api/app/src/test/java/com/confia/architecture/fixture/monetary/FloatingPointBigDecimal.java` | Crear | A | Fixture de la regla 1 |
| `apps/api/app/src/test/java/com/confia/architecture/fixture/monetary/RawBigDecimalComparison.java` | Crear | A | Fixture de la regla 2 |
| `apps/api/app/src/test/java/com/confia/architecture/fixture/monetary/FloatingPointPriceTag.java` | Crear | A | Fixture de las reglas 3, 4 y 5 |
| `apps/api/app/pom.xml` | Modificar | A y B | JaCoCo con regla `BUNDLE` de 80 % (A, o B si la medición no alcanza); regla `PACKAGE` de 95 %, adhesión a PIT y `junit-platform-launcher` (B); descripción del módulo actualizada |
| `openspec/config.yaml` | Modificar | A o B | Comentario de `coverage_threshold`, en el mismo commit que active la puerta global |
| `apps/api/kernel/src/main/java/com/confia/kernel/InstitutionId.java` | Crear | B | Decisión 2 |
| `apps/api/kernel/src/test/java/com/confia/kernel/InstitutionIdTest.java` | Crear | B | Nulo, igualdad y `hashCode` por valor, desigualdad |
| `apps/api/kernel/src/main/java/com/confia/kernel/package-info.java` | Modificar | B | Menciona los identificadores entre los tipos del núcleo |
| `apps/api/app/src/main/java/com/confia/organization/package-info.java` | Crear | B | Capacidad `organization`; qué no incluye y qué cambio lo aporta |
| `apps/api/app/src/main/java/com/confia/organization/domain/Institution.java` | Crear | B | Decisión 5 |
| `apps/api/app/src/main/java/com/confia/organization/domain/InvalidInstitutionException.java` | Crear | B | Nueve códigos de construcción |
| `apps/api/app/src/main/java/com/confia/organization/domain/InstitutionStateException.java` | Crear | B | Dos códigos de transición |
| `apps/api/app/src/test/java/com/confia/organization/domain/InstitutionCreationTest.java` | Crear | B | Caso feliz, cada invariante con sus límites, precedencia, nulos |
| `apps/api/app/src/test/java/com/confia/organization/domain/InstitutionLifecycleTest.java` | Crear | B | Activar, desactivar, transiciones inválidas, igualdad por identidad, `toString` sin RTN |
| `apps/api/app/src/test/java/com/confia/organization/domain/OrganizationErrorCodesTest.java` | Crear | B y C | Catálogo cerrado (11 en B, 13 en C) |
| `apps/api/app/src/test/java/com/confia/architecture/LayeredArchitectureTest.java` | Modificar | B | Ciclo A: retira la excepción de ADR-0018 y su ayudante; con ADR-0020, reglas de producción y de fixture separadas (ver «Contratos») |
| `apps/api/app/src/test/java/com/confia/architecture/EmptyShouldExceptionInventoryTest.java` | Modificar | B | Retira la entrada vencida; con ADR-0020, tipo de marcador y dos entradas |
| `apps/api/app/src/test/java/com/confia/architecture/SuppressionCitesAdrTest.java` | Modificar | B (solo con ADR-0020) | Marcador `optionalLayer(`, prohibición de `withOptionalLayers(true)`, conteo por tipo de marcador |
| `docs/adr/ADR-0020-capas-opcionales-en-la-regla-de-capas.md` | Crear | B | Escrito en esta fase, estado Propuesto |
| `docs/adr/README.md` | Modificar | B | Fila de ADR-0020, escrita en esta fase |
| `apps/api/app/src/main/java/com/confia/organization/application/InstitutionRepository.java` | Crear | C | Puerto de salida |
| `apps/api/app/src/main/java/com/confia/organization/application/CurrentInstitutionProvider.java` | Crear | C | Puerto de salida, contrato de ADR-0009 en el Javadoc |
| `apps/api/app/src/main/java/com/confia/organization/application/ResolveCurrentInstitution.java` | Crear | C | Caso de uso |
| `apps/api/app/src/main/java/com/confia/organization/domain/InstitutionNotFoundException.java` | Crear | C | `institution-not-found` |
| `apps/api/app/src/main/java/com/confia/organization/domain/InstitutionInactiveException.java` | Crear | C | `institution-inactive` |
| `apps/api/app/src/test/java/com/confia/organization/application/ResolveCurrentInstitutionTest.java` | Crear | C | Activa, inexistente, inactiva, nulos del constructor |
| `apps/api/app/src/test/java/com/confia/organization/application/InMemoryInstitutionRepository.java` | Crear | C | Doble de prueba |
| `apps/api/app/src/test/java/com/confia/organization/application/FixedCurrentInstitutionProvider.java` | Crear | C | Doble de prueba |

No se modifican `NoCrossModuleDomainImportsTest`, `SpringModulithVerificationTest`,
`NoTechnicalLayerPackageNamesTest`, `NoCyclesTest` ni `KernelErrorCodesTest`.

## Contratos e interfaces

```java
// kernel
package com.confia.kernel;
public record InstitutionId(UUID value) {           // NPE on null value
}

// organization.domain
package com.confia.organization.domain;
public final class Institution {
    public static final int MAX_NAME_LENGTH = 200;      // code points; the spec fixes the value
    public static final int MAX_ADDRESS_LENGTH = 500;   // code points; the spec fixes the value
    public static final int MAX_RTN_DIGITS = 20;        // technical guard, not a fiscal rule
    public static Institution create(InstitutionId id, String legalName, String tradeName,
            String rtn, String address, CurrencyCode defaultCurrency, Locale locale,
            ZoneId timezone);                            // InvalidInstitutionException; NPE
    public InstitutionId id();
    public String legalName();
    public String tradeName();
    public String rtn();
    public String address();
    public CurrencyCode defaultCurrency();
    public Locale locale();
    public ZoneId timezone();
    public boolean isActive();
    public void activate();                              // InstitutionStateException
    public void deactivate();                            // InstitutionStateException
    // equals/hashCode on id; toString = "Institution[id=..., tradeName=...]"
}

public final class InvalidInstitutionException extends DomainException {
    public static final String LEGAL_NAME_BLANK = "institution-legal-name-blank";
    public static final String LEGAL_NAME_TOO_LONG = "institution-legal-name-too-long";
    public static final String TRADE_NAME_BLANK = "institution-trade-name-blank";
    public static final String TRADE_NAME_TOO_LONG = "institution-trade-name-too-long";
    public static final String RTN_INVALID = "institution-rtn-invalid";
    public static final String ADDRESS_BLANK = "institution-address-blank";
    public static final String ADDRESS_TOO_LONG = "institution-address-too-long";
    public static final String LOCALE_INVALID = "institution-locale-invalid";
    public static final String TIMEZONE_INVALID = "institution-timezone-invalid";
    // package-private factories, one per code; messages never echo the input
}

public final class InstitutionStateException extends DomainException {
    public static final String ALREADY_ACTIVE = "institution-already-active";
    public static final String ALREADY_INACTIVE = "institution-already-inactive";
}

public final class InstitutionNotFoundException extends DomainException {
    public static final String CODE = "institution-not-found";
    public InstitutionNotFoundException();
}

public final class InstitutionInactiveException extends DomainException {
    public static final String CODE = "institution-inactive";
    public InstitutionInactiveException();
}

// organization.application
package com.confia.organization.application;
public interface InstitutionRepository {
    Optional<Institution> findById(InstitutionId id);   // implemented in change 5 (jOOQ)
}
public interface CurrentInstitutionProvider {
    InstitutionId currentInstitutionId();               // from the authenticated token only (ADR-0009), change 7
}
public final class ResolveCurrentInstitution {
    public ResolveCurrentInstitution(CurrentInstitutionProvider provider,
            InstitutionRepository repository);
    public Institution execute();  // InstitutionNotFoundException, InstitutionInactiveException
}
```

### Ediciones exactas de las pruebas de arquitectura (ciclo A)

**Parte común a los tres resultados de la sonda** (paso B3):

- `LayeredArchitectureTest`: se borra `.allowEmptyShould(true)` y el comentario de las líneas 70 a
  73; la prueba se renombra `productionCodeRespectsLayering` (el sufijo «Yet» señalaba la
  excepción); se borran `LAYER_SEGMENTS` y `noBusinessModuleExistsYet` con su Javadoc y los imports
  que queden sin uso (`JavaClass`, `JavaClasses`, `Arrays`, `Set`).
- `EmptyShouldExceptionInventoryTest`: se borra la única entrada; `EXCEPTIONS` queda `List.of()`.
  El mecanismo permanece para excepciones futuras.
- `SuppressionCitesAdrTest`: sin cambios; su conteo pasa de 1 == 1 a 0 == 0.

**Solo si se aplica ADR-0020** (paso B4):

```java
// LayeredArchitectureTest
private static ArchRule productionLayeringRule() {
    return constrained(layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            // ADR-0020: optional while no production class resides in an infrastructure package
            .optionalLayer("Infrastructure").definedBy("..infrastructure..")
            // ADR-0020: optional while no production class resides in a web package
            .optionalLayer("Web").definedBy("..web.."));
}

private static ArchRule fixtureLayeringRule() {   // every layer mandatory (ADR-0018 §1.3)
    return constrained(layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            .layer("Infrastructure").definedBy("..infrastructure..")
            .layer("Web").definedBy("..web.."));
}

private static ArchRule constrained(LayeredArchitecture layers) {
    return layers.whereLayer("Domain")...   // the eight existing whereLayer clauses and because()
}

/** Package-visible for EmptyShouldExceptionInventoryTest (ADR-0020 expiry conditions). */
static boolean noProductionClassInLayer(JavaClasses classes, String layerSegment) { ... }
```

```java
// EmptyShouldExceptionInventoryTest
enum Marker { ALLOW_EMPTY_SHOULD, OPTIONAL_LAYER }

record ExpiringException(Marker marker, String rule, String adr, String condition,
        Predicate<JavaClasses> stillJustified) { }

static final List<ExpiringException> EXCEPTIONS = List.of(
        new ExpiringException(Marker.OPTIONAL_LAYER,
                "LayeredArchitectureTest.productionLayeringRule, layer Infrastructure", "ADR-0020",
                "no production class resides in an infrastructure package yet (change 5 adds the "
                        + "first jOOQ adapter)",
                classes -> LayeredArchitectureTest.noProductionClassInLayer(classes, "infrastructure")),
        new ExpiringException(Marker.OPTIONAL_LAYER,
                "LayeredArchitectureTest.productionLayeringRule, layer Web", "ADR-0020",
                "no production class resides in a web package yet (the first business controller "
                        + "adds one)",
                classes -> LayeredArchitectureTest.noProductionClassInLayer(classes, "web")));

static long countOf(Marker marker) { ... }   // for SuppressionCitesAdrTest
```

```java
// SuppressionCitesAdrTest
private static final Pattern OPTIONAL_LAYER_CALL = Pattern.compile("\\.optionalLayer\\(");
private static final Pattern WITH_OPTIONAL_LAYERS_TRUE =
        Pattern.compile("withOptionalLayers\\(\\s*true\\s*\\)");
// OPTIONAL_LAYER_CALL joins ADR_CITED_PATTERNS with example ".optionalLayer(\"Web\")".
// WITH_OPTIONAL_LAYERS_TRUE is a problem in any file, like failOnEmptyShould=false.
// Two count assertions: allowEmptyShould( occurrences == countOf(ALLOW_EMPTY_SHOULD) (0),
// .optionalLayer( occurrences == countOf(OPTIONAL_LAYER) (2).
// A small test proves both forbidden patterns match their examples.
```

Cuidado de redacción: ningún Javadoc ni comentario de `apps/api` escribe la forma con punto
`.optionalLayer(` ni `.allowEmptyShould(true)` en prosa, porque el escáner las contaría como llamadas.

## Estrategia de pruebas

| Nivel | Qué se prueba | Cómo |
|---|---|---|
| Unitario, `kernel` | `InstitutionId`: nulo, igualdad y `hashCode` por valor | JUnit 6 y AssertJ; puertas existentes de `kernel` (95 %, PIT 80) |
| Unitario, dominio | Cada invariante con casos límite explícitos: vacío, solo espacios, espacios de borde que se recortan, exactamente 200/500 puntos de código, uno más, un carácter fuera del plano básico contado como uno; RTN con 1 y 20 dígitos, 21 dígitos, guion, espacio, dígitos no ASCII (por ejemplo `٠١٢`); `Locale.ROOT`; `ZoneOffset.ofHours(-6)` frente a `America/Tegucigalpa`; precedencia entre dos reglas incumplidas; cada nulo | Pruebas parametrizadas donde el caso es una tabla; una aserción de código por caso |
| Unitario, dominio | Transiciones, igualdad por identidad, `toString` sin RTN | `InstitutionLifecycleTest` |
| Catálogo | Códigos cerrados, formato, unicidad, prefijo, subclase de `DomainException` | `OrganizationErrorCodesTest` |
| Unitario, aplicación | Resolución activa, inexistente e inactiva; constructor con nulos | Dobles en memoria; sin Spring |
| Arquitectura | Reglas de ADR-0004 en producción (verde) y en fixture (rechazo nombrando la clase); capas con clases reales; inventario y escáner | ArchUnit 1.4.2; neutralización de cada fixture como prueba de no vacuidad |
| Cobertura | 80 % de líneas y ramas en `app`; 95 % en cada paquete `domain` | `jacoco:check` en `verify` |
| Mutación | 80 sobre `com.confia.*.domain.*` | PIT `STRONGER`; bloquea en `main`, informa en ramas |
| Integración y extremo a extremo | Ninguna | No hay base de datos, API ni interfaz; las pruebas de aislamiento por fila llegan con el cambio 5 |

No hay acción sensible ni escritura: no aplica bitácora de auditoría. No se registra en logs ningún
dato de la institución.

**Demostración de que las puertas fallan** (sin comprometer): comentar una prueba de dominio y
observar que la regla `PACKAGE` rompe `verify`; comentar pruebas de `bootstrap` y observar la regla
`BUNDLE`; debilitar una aserción del dominio y observar que `-Pmutation-gate` rompe mientras
`-Pmutation-report` informa; añadir de forma temporal una clase en
`com.confia.organization.infrastructure` y observar que el inventario falla nombrando la entrada de
`Infrastructure` (solo con ADR-0020). Las salidas se registran como evidencia.

## Sonda de P2 (primer paso de la aplicación, antes del paso B3)

> **Resultado observado (2026-09-19, ejecutada por el orquestador).** Con la forma real de
> `layeringRule()` sobre ArchUnit 1.4.2 y una muestra con clases solo en `domain` y `application`
> (`application` depende de `domain`): sin excepción, la regla **falla**; con
> `allowEmptyShould(true)`, **pasa**; con `optionalLayer` para `Infrastructure` y `Web`, **pasa**;
> con `withOptionalLayers(true)`, **pasa**. Como la variante con `optionalLayer` solo para esas dos
> capas pasa, la falla proviene exclusivamente de las capas vacías. Es el **resultado (b)**:
> se aplica ADR-0020, que el propietario aceptó el mismo día. La sonda se eliminó del repositorio y
> del directorio `target`. El paso B0 queda cumplido y no se repite en la aplicación.

Prueba temporal, no comprometida, que se borra al terminar (`git status` debe quedar limpio):

1. Clases de prueba mínimas en `apps/api/app/src/test/java/com/confia/probe/sample/domain/` y
   `.../sample/application/` (una clase cada una; la de `application` usa la de `domain`).
2. Una prueba en `com.confia.probe` que importa solo `com.confia.probe.sample` con
   `new ClassFileImporter().importPackages(...)` y evalúa, con `archunit.properties` real
   (`failOnEmptyShould=true`), la forma exacta de `layeringRule()` en cuatro variantes:
   (a) sin `allowEmptyShould`; (b) con `allowEmptyShould(true)`; (c) con `optionalLayer` en
   `Infrastructure` y `Web`; (d) como (c) pero sin la clase de `domain`. Cada variante imprime
   «pasa» o el mensaje completo de `AssertionError`.
3. `./mvnw -B -pl app -am test -Dtest=ProbeTest -Dsurefire.failIfNoSpecifiedTests=false`
   con la salida redirigida a un archivo fuera del repositorio.

Resultado esperado según la inferencia: (a) falla nombrando `Infrastructure` y `Web`; (b) pasa;
(c) pasa; (d) falla nombrando `Domain`. Cualquier otro resultado se resuelve con la tabla de la
decisión 4. La variante `withOptionalLayers(true)` no se prueba: está descartada.

## Secuencia de implementación con TDD estricto

**Corte A**

- A1. Medición: declarar JaCoCo en `app` sin `check`, ejecutar `verify` y leer el informe de `app`
  (solo `bootstrap`). Decide si la regla `BUNDLE` entra aquí o en B (decisión 9).
- A2. Rojo: `MonetaryFloatingPointTest` con la prueba de fixture de la regla 1 y su fixture
  (no compila: rojo). Verde: la regla; la mitad de producción pasa. Neutralizar el fixture y observar
  el fallo.
- A3. Igual para la regla 2, incluido que `Money` queda fuera de la selección.
- A4. Igual para las reglas 3, 4 y 5 con `FloatingPointPriceTag`, más la prueba de la premisa
  (`productionClasses()` contiene `Money` y `Percentage`).
- A5. Si A1 lo permite: regla `BUNDLE` de 80 % y comentario de `config.yaml`; demostrar su fallo.

**Corte B**

- B0. Sonda de P2 (sección anterior); resultado en el informe de aplicación.
- B1. `InstitutionIdTest` en rojo, `InstitutionId` en verde.
- B2. `InstitutionCreationTest` con el caso feliz en rojo; `Institution` mínima en verde. `verify`
  completo: **rojo programado** de `EmptyShouldExceptionInventoryTest`; se registra el mensaje.
- B3. Cierre de la caducidad de ADR-0018 (parte común de «Ediciones exactas»). Resultado de
  `LayeredArchitectureTest` según la sonda.
- B4. Solo con ADR-0020 aceptado: rojo del escáner con los ejemplos de los dos patrones nuevos;
  verde con los patrones y el conteo por marcador; rojo del conteo al añadir las dos entradas del
  inventario; verde al separar la regla de producción con `optionalLayer`. Demostración de caducidad
  con una clase temporal en `infrastructure`.
- B5. Puertas del dominio: regla `PACKAGE` de 95 %, adhesión a PIT y `junit-platform-launcher`
  (y la regla `BUNDLE` si no entró en A). Humo de PIT en `app` con `-Pmutation-report`.
- B6. Invariantes restantes de construcción, una a una en rojo y verde, con sus códigos;
  transiciones; `OrganizationErrorCodesTest` con once códigos.

**Corte C**

- C1. `ResolveCurrentInstitutionTest` con el caso activo en rojo; puertos, dobles y caso de uso en
  verde.
- C2. Inexistente e inactiva en rojo; sus dos clases de error en verde; catálogo a trece códigos.
- C3. `./mvnw -B verify` y `./mvnw -B verify -Pmutation-report` en verde; `git diff --numstat`
  contra la base del corte.

Cada paso termina con `./mvnw -B verify` en verde salvo los rojos programados, que se observan y se
cierran en el mismo commit.

## Pronóstico de tamaño por corte

Líneas de autor (adiciones más eliminaciones) en código, pruebas, POM y `config.yaml`. Quedan fuera
los artefactos de OpenSpec y ADR-0020 con su fila del índice (unas 150 líneas, ya escritas en esta
fase), igual que en el cambio 2. Estimación desglosada por archivo, que ya incorpora Javadoc y la
desviación de aproximadamente 1,5 veces observada en el cambio 2 (la estimación de la propuesta de
ese cambio fue 1 065 a 1 445 y el desglose del diseño, 1 318 a 1 767).

| Corte | Contenido | Estimación |
|---|---|---|
| A | `MonetaryFloatingPointTest` (140–190), tres fixtures (60–95), JaCoCo `BUNDLE` en `app/pom.xml` (30–45), `config.yaml` (2–4) | **232 a 334** |
| B | `InstitutionId` y prueba (55–85), `package-info` (15–25), `Institution` (150–210), dos clases de error (85–125), `InstitutionCreationTest` (170–240), `InstitutionLifecycleTest` (50–80), `OrganizationErrorCodesTest` (40–55), ciclo A común (30–45), ciclo A con ADR-0020 (80–125), `app/pom.xml` dominio y PIT (35–55) | **710 a 1 045** |
| C | Dos puertos (30–50), caso de uso (35–50), dos clases de error (25–40), prueba del caso de uso (60–90), dos dobles (30–50), catálogo (+5–10) | **185 a 290** |
| **Total** | | **1 127 a 1 669** |

Si la sonda resulta en el caso (a), el corte B baja en 80 a 125 líneas (630 a 920).

**Frente al presupuesto de 800 líneas por pull request** (`docs/15-flujo-de-trabajo-git.md` §3,
decisión D2 del cambio 2; la política de revisión de la sesión SDD dice 400 y debe reconciliarse):

- El total supera 800 en todo el rango, confirmando la propuesta: un solo pull request exige
  `size:exception` (P1, opción A).
- Con cortes (P1, opción B): A y C caben con holgura; **B supera 800 en su extremo alto**
  (hasta 1 045). Punto de subdivisión ya identificado, sin separar código de sus pruebas:
  **B1** = `InstitutionId`, `Institution` con `id`, nombres y estado, sus errores y pruebas, ciclo A
  completo y puertas del dominio (unas 480 a 700); **B2** = `rtn`, `address`, `defaultCurrency`,
  `locale` y `timezone` con sus códigos y pruebas (unas 230 a 345). B2 cambia la firma de `create`,
  lo que es aceptable porque aún no tiene consumidores fuera del módulo.
- Este diseño no elige la forma de entrega: P1 es del propietario.

## Restricciones del entorno local

- `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot"` y
  `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`; siempre `./mvnw -B` desde `apps/api`.
- ArchUnit resuelto: 1.4.2 (fijado en `apps/api/pom.xml`, W6 del cambio 1). La sonda se ejecuta
  contra esa versión, no contra 1.5.0.
- La integración continua es la fuente de verdad. Nunca se baja un umbral ni se omite una prueba
  para pasar en local.

## Matriz de amenazas

N/A: el cambio no introduce enrutamiento, órdenes de shell, subprocesos, automatización de Git o de
pull requests, clasificación de archivos ejecutables ni integración de procesos. La frontera de
seguridad relevante (origen del identificador de institución) se documenta como contrato del puerto
`CurrentInstitutionProvider` y la implementa el cambio 7.

## Migración y despliegue

Sin migración: no hay base de datos, esquema, datos ni despliegue. Revertir es revertir los commits
del cambio o su commit de fusión; la excepción de ADR-0018 vuelve con su condición original, que se
cumple de nuevo al desaparecer el módulo. Una puerta que bloquee por error no se desactiva con una
bandera (ADR-0008): se corrige con un cambio explícito o con un ADR.

## Por confirmar durante la implementación

1. **Sonda de P2** (sección propia): comportamiento de `layeredArchitecture()` con capas vacías y
   existencia y efecto de `optionalLayer(String)` en ArchUnit 1.4.2.
2. Disponibilidad de `doNotBelongToAnyOf(Class...)` en `ClassesThat` de 1.4.2; si no existe,
   `that(DescribedPredicate.not(JavaClass.Predicates.belongToAnyOf(Money.class)))`, con el mismo
   significado.
3. Que JaCoCo 0.8.14 interpreta `<include>` de una regla `PACKAGE` con nombres de paquete con puntos
   y que `*` abarca segmentos (`com.confia.*.domain` coincide con `com.confia.organization.domain`).
   Se confirma con la demostración de fallo de la regla.
4. Que el comodín de PIT `com.confia.*.domain.*` abarca segmentos y selecciona solo clases de
   producción; se confirma en el humo de B5 leyendo las clases mutadas del informe.
5. Cobertura real de `app` con solo `bootstrap` (paso A1). Estimación por lectura: por encima de
   80 % de líneas; las ramas dependen de cómo JaCoCo 0.8.14 trate los `switch` de
   `ConfiaApplication.launch` y `AppProfile.resolve`, y de las dos ramas no cubiertas de `main`.
6. Que `app` necesita `junit-platform-launcher` en alcance `test` para PIT (como `kernel`).
7. Que el fixture nuevo `fixture.monetary` no altera las pruebas de fixture existentes (Spring
   Modulith lo verá como un módulo más que depende de un tipo fuera de su raíz).

Si alguna confirmación obliga a apartarse de lo decidido, se eleva a un ADR y no se entierra aquí
(`docs/13-metodologia-sdd.md`, regla 5).

## Preguntas abiertas

- [ ] **P1, forma de entrega** (propietario): un pull request con `size:exception`, o cortes A, B
      (posiblemente B1 y B2) y C. Pronóstico en «Pronóstico de tamaño por corte».
- [ ] **P2 / ADR-0020** (propietario): aceptar ADR-0020 si la sonda confirma el caso (b). Sin esa
      aceptación, la aplicación se detiene en el paso B3 con la construcción en rojo en local, sin
      comprometer.
- [ ] **Sonda de P2 no ejecutada en diseño**: el agente de diseño no tenía herramienta de ejecución.
      Queda como paso B0, bloqueante.
- [ ] **Lista de códigos y límites de longitud**: los fija la especificación, que se escribe en
      paralelo; este diseño propone trece códigos y 200/500 caracteres.
- [ ] **RTN**: longitud exacta, máscara y dígito verificador pendientes de fuente primaria del SAR
      (`docs/04`, línea 32).
- [ ] **Presupuesto de revisión**: la política de la sesión SDD registra 400 líneas; el proyecto fija
      800 (`docs/15` §3). El orquestador debe alinear su registro.
- [ ] **ADR-0020** queda en estado Propuesto hasta que el propietario lo acepte o, si la sonda lo
      desmiente, lo marque como Rechazado.

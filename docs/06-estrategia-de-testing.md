# CONFIA — Estrategia de Testing

> Documento maestro de calidad. Define qué se prueba, con qué herramienta, en qué nivel, con qué
> umbral y qué bloquea una fusión.
> Fuente de verdad de la arquitectura: `docs/01-arquitectura.md`. Reglas no negociables:
> `CLAUDE.md`. Si algo aquí contradice la arquitectura, gana la arquitectura.

---

## 1. Filosofía: trofeo de pruebas, no pirámide

La pirámide clásica optimiza velocidad de ejecución: muchas pruebas unitarias, pocas de
integración, casi ninguna de extremo a extremo. Es una buena heurística para bibliotecas puras.
Es una mala heurística para un sistema financiero construido por una sola persona.

CONFIA adopta el **trofeo de pruebas**: el mayor peso recae en las pruebas de **integración**, con
una base sólida de análisis estático, un cuerpo focalizado de pruebas unitarias sobre el dominio
puro y una punta muy corta de extremo a extremo.

### Por qué la integración con base de datos real tiene valor desproporcionado aquí

En este sistema, la mayor parte de los invariantes que realmente importan **no viven en el código
de la aplicación**:

1. **La contabilidad de doble partida cuadra o no cuadra en la base de datos.** El cuadre se declara
   con restricciones `CHECK` y disparadores. Una prueba unitaria contra un repositorio simulado
   valida que el código llamó al método correcto, no que el asiento cuadre.
2. **La seguridad a nivel de fila es una política del motor de PostgreSQL.** Un doble de prueba no
   tiene políticas de fila. Simular el repositorio y afirmar que devolvió una lista vacía prueba
   que el doble fue programado así, no que la base rechace la consulta.
3. **La precisión monetaria depende del tipo de columna.** `NUMERIC(14,4)` redondea distinto que
   `double precision`. Una prueba contra un arreglo en memoria nunca detecta que una migración
   declaró la columna con el tipo equivocado.
4. **La concurrencia solo existe con transacciones reales.** El bloqueo de la cuenta del estudiante,
   el aislamiento `SERIALIZABLE` del cierre de caja y la asignación del correlativo fiscal bajo
   bloqueo se comportan de una única manera verificable: dos conexiones concurrentes contra un
   PostgreSQL real.
5. **La idempotencia depende de un índice único.** El índice existe en la migración, no en el
   código de aplicación.

Un doble de prueba solo puede confirmar la creencia que el desarrollador ya tenía. Con un solo
desarrollador no hay un segundo par de ojos que corrija esa creencia, de modo que la única fuente
externa de verdad disponible es el motor de base de datos ejecutando el esquema real.

El costo de esa decisión es tiempo de ejecución. Se acepta y se administra: el conjunto de
integración se paraleliza por archivo, comparte un único contenedor por proceso trabajador y usa
reversión de transacción en vez de recreación de esquema.

### Reparto de esfuerzo objetivo

| Nivel | Proporción aproximada del esfuerzo | Racional |
|---|---|---|
| Estático | Costo casi cero, se ejecuta continuamente | Elimina una clase entera de defectos antes de escribir una prueba |
| Unitario sobre el módulo de núcleo y los paquetes `domain` del backend | 25 % | Aritmética monetaria, mora, impuestos, redondeo: lógica pura y densa en casos límite |
| Integración con PostgreSQL real | 45 % | El corazón del trofeo. Invariantes, concurrencia, políticas de fila, idempotencia |
| Componente y contrato | 20 % | Estados de interfaz y compatibilidad del contrato compartido |
| Extremo a extremo | 10 % | Solo recorridos críticos de negocio |

---

## 2. Niveles de prueba

| Nivel | Herramienta | Qué cubre | Qué NO cubre | Dónde viven los archivos | Presupuesto de tiempo |
|---|---|---|---|---|---|
| Estático (backend) | Compilador de Java, `maven-enforcer-plugin`, ArchUnit y verificación de módulos de Spring Modulith | Tipos, pureza del módulo de núcleo, reglas de capa hexagonal, fronteras entre módulos, prohibición de `double` y `float` en importes | Comportamiento en tiempo de ejecución, corrección de una fórmula | POM padre de `apps/api` y pruebas de arquitectura del módulo de aplicación | Menos de 90 s |
| Estático (frontend) | TypeScript en modo estricto, ESLint, `dependency-cruiser` | Tipos, reglas de estilo, reglas de frontera del frontend, importaciones prohibidas, aritmética sobre importes | Comportamiento en tiempo de ejecución | Configuración en `packages/config`; se ejecuta sobre aplicaciones web y `packages/*` | Menos de 90 s |
| Unitario (backend) | JUnit y AssertJ, con jqwik para pruebas de propiedad | Objetos de valor, reglas puras: `Money`, redondeo, mora, impuestos, imputación, máquinas de estado | Persistencia, transacciones, concurrencia, políticas de fila | `src/test/java/**/*Test.java` de cada módulo Maven | Menos de 30 s en total; cada prueba por debajo de 50 ms |
| Integración (backend) | JUnit con Testcontainers y PostgreSQL 18 real (ADR-0015) | Repositorios, casos de uso transaccionales, restricciones y disparadores, migraciones de Flyway, RLS, idempotencia, concurrencia, trabajos en segundo plano | Interfaz de usuario, red externa real, pasarela de pago real | `src/test/java/**/*IT.java` del módulo de aplicación | Menos de 8 min en integración continua; cada clase por debajo de 20 s |
| Componente | Testing Library + MSW | Render de organismos y páginas, estados de carga, vacío, error y éxito, interacción con teclado, formularios con Zod | Conexión real a la API, enrutamiento completo del navegador | `apps/*-web/src/**/*.test.tsx` | Menos de 3 min; cada prueba por debajo de 300 ms |
| Contrato | Comparación del OpenAPI generado por springdoc-openapi contra la instantánea aprobada; regeneración de `packages/contracts` con orval y `tsc --noEmit` de las aplicaciones web contra el paquete regenerado | Que ningún cambio rompe a un consumidor, que todo importe tiene la forma `{ amount: string, currency: string }`, que el frontend y la futura app móvil compilan contra el mismo contrato | Corrección semántica de la respuesta | Instantánea del OpenAPI versionada en `apps/api`; verificación de tipos en el pipeline del frontend | Menos de 60 s |
| Extremo a extremo | Playwright | Recorridos críticos completos con navegador real contra API y base de datos reales | Todo lo que no sea un recorrido crítico | `e2e/specs/**/*.e2e.ts` | Menos de 12 min; cada recorrido por debajo de 90 s |
| Accesibilidad | axe-core, integrado en componente y en extremo a extremo | Violaciones automatizables de WCAG 2.2 AA: contraste, nombre accesible, orden de foco, semántica | Juicio humano: claridad del lenguaje, sentido del orden de lectura | `e2e/specs/a11y/**` y aserciones dentro de pruebas de componente | Se suma a los niveles donde se integra |
| Rendimiento | k6 | Latencia y rendimiento de los endpoints financieros bajo carga, comportamiento del bloqueo de correlativo bajo concurrencia | Percepción de rapidez de la interfaz, que se mide con presupuestos de paquete y Lighthouse | `perf/k6/**/*.js` | Menos de 10 min; se ejecuta nocturno, no en cada fusión |
| Seguridad | Semgrep, OWASP ZAP en línea base, Trivy, gitleaks | Patrones de código inseguro, cabeceras y configuración expuesta, vulnerabilidades de dependencias e imagen, secretos filtrados | Lógica de autorización de negocio, que se prueba en integración | `.semgrep/`, `security/zap/`, configuración en el flujo de trabajo | Menos de 5 min; ZAP en ejecución nocturna |
| Mutación | PIT sobre el módulo de núcleo y el paquete `domain` de cada módulo | Calidad real de las pruebas unitarias del dominio: si una prueba no falla al mutar el operador, no probaba nada | Cualquier paquete fuera del núcleo y de los paquetes `domain` | Configuración de PIT en el POM padre de `apps/api` | Menos de 15 min; en cada construcción del backend (bloqueante solo en la rama principal), completa en ejecución nocturna y antes de una etiqueta de versión |

---

## 3. Umbrales y puertas de calidad

### Umbrales

| Métrica | Umbral | Alcance |
|---|---|---|
| Cobertura de líneas y ramas global | 80 % mínimo | Todo el monorepo, excluyendo generado y configuración |
| Cobertura de líneas y ramas | 95 % mínimo, medida con JaCoCo | Módulo de núcleo y paquete `domain` de cada módulo del backend |
| Puntuación de mutación | 80 % mínimo, medida con PIT | Módulo de núcleo y paquete `domain` de cada módulo del backend |
| Violaciones de accesibilidad de severidad crítica o seria | 0 | Todas las pantallas cubiertas |
| Vulnerabilidades de dependencia de severidad alta o crítica | 0 | Dependencias de Maven y de `pnpm`, y Trivy sobre la imagen |
| Secretos detectados | 0 | Todo el historial verificado por gitleaks |
| Violaciones de regla de dependencia | 0 | ArchUnit y Spring Modulith en el backend; `dependency-cruiser` en el frontend |

### Qué bloquea la fusión

Bloquean, sin excepción ni anulación manual:

- Error de compilación del backend, de verificación de tipos del frontend o de ESLint.
- Violación de una regla de ArchUnit, de la verificación de módulos de Spring Modulith o de una
  regla de frontera de `dependency-cruiser`.
- Cualquier prueba unitaria, de integración, de componente o de contrato en rojo.
- Diferencia no declarada entre el OpenAPI generado y la instantánea aprobada.
- Cobertura por debajo del umbral, global o en el módulo de núcleo y los paquetes `domain`.
- Cualquier recorrido crítico de extremo a extremo en rojo.
- Violación de accesibilidad crítica o seria.
- Secreto detectado por gitleaks.
- Vulnerabilidad alta o crítica en dependencias o en la imagen de contenedor.
- Hallazgo de Semgrep de severidad `ERROR` en las reglas de la categoría financiera o de
  inyección.

### Qué solo advierte

Advierte y crea una tarea, pero no bloquea:

- Puntuación de mutación por debajo del umbral en una rama de trabajo. Bloquea únicamente en la
  rama principal y antes de una etiqueta de versión, porque la ejecución es lenta y ruidosa
  durante el desarrollo iterativo.
- Regresión de rendimiento en k6 inferior al 20 % respecto de la línea base.
- Hallazgos nuevos de ZAP en línea base de severidad media o baja.
- Violaciones de accesibilidad de severidad moderada o menor.
- Vulnerabilidades de dependencia de severidad media o baja.
- Aumento del tamaño del paquete del frontend por debajo del presupuesto declarado pero superior
  al 5 % respecto de la rama principal.

### Regla de la excepción

Una puerta bloqueante no se desactiva. Si un umbral resulta insostenible, se cambia el umbral en
este documento mediante un cambio SDD explícito y revisado, no mediante un comentario que
silencia la regla en un archivo. Un `eslint-disable` sin justificación escrita en la misma línea
es un defecto, y lo mismo vale para una exclusión de ArchUnit, de Spring Modulith, de JaCoCo o de
PIT sin referencia escrita al ADR que la autoriza (ADR-0002).

---

## 4. Qué se prueba obligatoriamente por ser un sistema financiero

Las siguientes áreas no admiten la excusa de "es código simple". Cada una tiene pruebas
obligatorias en el nivel indicado.

| Área | Nivel obligatorio | Qué debe demostrar la prueba |
|---|---|---|
| Aritmética monetaria y redondeo | Unitario en el módulo de núcleo + propiedad con jqwik + mutación | Que `Money` opera sobre `BigDecimal` normalizado a escala cuatro, compara por importe normalizado y moneda, rechaza operaciones entre monedas distintas, no se construye desde `double` ni `float` y aplica una única regla de redondeo declarada (`HALF_UP`) |
| Cuadre del libro mayor | Integración | Que ninguna transacción se persiste si la suma del debe no iguala la suma del haber, incluso intentando forzarlo por SQL directo |
| Aplicación de pagos a cargos | Unitario para la política de imputación + integración para la persistencia | Que la imputación sigue la política declarada y que nunca aplica más que el saldo del cargo |
| Cálculo de mora | Unitario + mutación | Que el resultado es determinista y reproducible para una fecha de corte dada, con días de gracia, tope y exenciones |
| Cálculo de impuestos | Unitario + integración con el documento emitido | Que la base imponible, el impuesto y el total cuadran exactamente y que el redondeo ocurre en el punto de emisión fiscal |
| Asignación de correlativo fiscal bajo concurrencia | Integración con conexiones concurrentes reales | Que N emisiones simultáneas producen N correlativos distintos y consecutivos, sin huecos no justificados |
| Idempotencia de escrituras | Integración | Que dos solicitudes con la misma `Idempotency-Key` producen un único efecto y una respuesta idéntica |
| Cierre de caja | Integración con aislamiento `SERIALIZABLE` | Que el conteo esperado corresponde a los movimientos de la sesión y que la diferencia queda registrada |
| Políticas de seguridad a nivel de fila | Integración con roles reales de PostgreSQL | Que un encargado no puede leer datos de otro ni siquiera con una consulta construida a mano |
| Bitácora de auditoría encadenada | Integración | Que cada registro contiene el hash del anterior y que un intento de actualización o borrado es rechazado por el motor |
| Escala de los importes de presentación | Contrato o integración sobre respuestas reales de la API | Que ningún campo de importe de presentación trae más decimales que la escala menor de su moneda (dos para HNL y USD), redondeado por el servidor con `HALF_UP` (ADR-0004, verificación 10) |

---

## 5. Casos límite obligatorios de toda regla monetaria

Toda función que produzca, transforme o compare un importe tiene, como mínimo, una prueba para
cada uno de estos casos. Ausencia de uno de ellos es motivo de rechazo en revisión.

- **Cero.** Importe cero como entrada y como resultado.
- **Negativo.** Importe negativo donde el dominio lo permite, y rechazo explícito donde no.
- **Mínima unidad.** Un centavo, para verificar que no se pierde en un redondeo.
- **Redondeo de medio.** Un valor exactamente en el punto medio, por ejemplo `12.345`, con la
  regla declarada aplicada de forma consistente.
- **Redondeo con muchos decimales.** Un porcentaje que produce más de cuatro decimales, para
  verificar el redondeo explícito `HALF_UP` a escala cuatro, coherente con `NUMERIC(14,4)`, y que
  ningún dígito se descarta de forma implícita.
- **Moneda distinta.** Operación entre dos monedas: debe lanzar error de dominio, nunca convertir
  de forma implícita.
- **Máximo representable.** Importe cercano al límite de `NUMERIC(14,4)`, para verificar que no
  hay desbordamiento silencioso.
- **Suma que debe cuadrar.** Un reparto de un total entre N partes donde la suma de las partes
  debe devolver exactamente el total, sin centavo perdido.
- **Concurrencia.** Dos operaciones simultáneas sobre el mismo saldo.
- **Idempotencia.** La misma operación repetida con la misma clave.
- **Orden de operaciones.** Que aplicar descuento y luego impuesto produce el resultado declarado
  y no el inverso.
- **Valor nulo o ausente.** Entrada faltante rechazada en el borde por Bean Validation, nunca
  convertida a cero de forma silenciosa.
- **Escala igual.** Dos importes iguales con distinta escala (`1.0` y `1.00`) son iguales para
  `Money`; nunca se comparan con `BigDecimal.equals` sin normalizar.

---

## 6. Estrategia de datos de prueba

### Fábricas tipadas, no accesorios estáticos

Los accesorios estáticos en JSON envejecen mal: cuando el esquema cambia, se actualizan de forma
mecánica hasta que dejan de representar un caso real. Se usan **fábricas tipadas** que reciben una
sobrescritura parcial y completan el resto con valores válidos por defecto. Los ejemplos de esta
sección son ilustrativos: los nombres de tipos del dominio y la biblioteca de datos sintéticos
(por ejemplo, Datafaker) se fijan en F0. `<paquete-base>` es un marcador
(`docs/01-arquitectura.md`, sección 4).

```java
// apps/api/app/src/test/java/<paquete-base>/students/StudentFactory.java
final class StudentFactory {

    private StudentFactory() {}

    /** Always returns a valid student. Tests override only what the case is about. */
    static StudentDraft.Builder aStudent(Faker faker) {
        return StudentDraft.builder()
                .institutionId(TestInstitution.ID)
                .code(faker.regexify("[A-Z0-9]{8}"))
                .firstName(faker.name().firstName())
                .lastName(faker.name().lastName())
                .enrolledAt(LocalDate.parse("2026-01-15"))
                .status(StudentStatus.ACTIVE);
    }
}

// Usage: the reader sees exactly which property makes the case fail.
// var withdrawn = StudentFactory.aStudent(faker).status(StudentStatus.WITHDRAWN).build();
```

Regla: la fábrica devuelve siempre una entidad **válida**. Un caso inválido se construye de forma
explícita en la prueba mediante una sobrescritura, de modo que el lector vea exactamente qué
propiedad hace fallar el caso.

### Base de datos limpia por prueba

Cada prueba de integración se ejecuta dentro de una transacción que se revierte al terminar. No se
trunca ni se recrea el esquema entre pruebas, porque eso multiplica el tiempo de ejecución por un
orden de magnitud.

Excepciones donde la reversión no aplica y se usa truncamiento selectivo:

- Pruebas que verifican el comportamiento de la propia transacción, como `SERIALIZABLE` o
  bloqueos, porque necesitan conexiones independientes que confirmen.
- Pruebas de concurrencia con dos o más conexiones.
- Pruebas de disparadores que actúan al confirmar.

### Semilla determinista

El generador de datos aleatorios se inicializa con una semilla fija derivada del nombre del archivo
de prueba. Una prueba que falla es reproducible con exactitud; una que pasa hoy y falla mañana con
los mismos datos es un defecto real y no ruido del generador.

```java
// apps/api/app/src/test/java/<paquete-base>/support/SeededFaker.java
final class SeededFaker {

    private SeededFaker() {}

    /** String.hashCode is specified by the JLS, so the seed is stable across runs and machines. */
    static Faker forTestClass(Class<?> testClass) {
        return new Faker(new Random(testClass.getName().hashCode()));
    }
}
```

### Prohibición absoluta de datos reales

**Queda prohibido usar datos reales de estudiantes, encargados o pagos en cualquier entorno que no
sea producción.** No en desarrollo, no en preproducción, no en una prueba, no en una captura de
pantalla de un ticket, no en un archivo de ejemplo del repositorio.

El sistema procesa datos de menores de edad. Un volcado de producción restaurado en el portátil del
desarrollador es una brecha de datos, aunque nunca salga del portátil. Ver
`docs/08-datos-privacidad-y-retencion.md`.

Cuando se necesite un conjunto realista para preproducción o capacitación, se genera con el
comando de anonimización que sustituye nombres, documentos de identidad, correos y teléfonos por
valores sintéticos, preservando únicamente la forma y los volúmenes. La verificación de que el
conjunto no contiene datos reales es parte del procedimiento y queda registrada.

---

## 7. Cómo se prueba la concurrencia

La concurrencia no se prueba con simulaciones. Se prueba abriendo varias conexiones reales contra
PostgreSQL y ejecutando las operaciones en paralelo.

### Ejemplo 1: dos cajeros cobrando al mismo estudiante

Escenario: el estudiante tiene un cargo de colegiatura de 3,500.00 HNL. Dos cajeros registran, en
el mismo instante, un pago de 3,500.00 HNL cada uno. El resultado correcto es que la deuda queda
en cero y el segundo pago genera un saldo a favor de 3,500.00 HNL. El resultado incorrecto, y el
que se busca hacer imposible, es que ambos pagos se apliquen al mismo cargo y el saldo quede en
menos 3,500.00 HNL sin registro de saldo a favor.

```java
// apps/api/app/src/test/java/<paquete-base>/payments/ConcurrentPaymentsIT.java
// Illustrative: use case, fixture and Money factory names are fixed by their specifications.
class ConcurrentPaymentsIT extends PostgresIntegrationTest {

    @Test
    void appliesEachPaymentExactlyOnceAndTurnsTheExcessIntoCredit() throws Exception {
        Money tuition = Money.of("3500.00", "HNL");
        var fixture = fixtures.studentWithCharge(tuition);

        // Each call runs in its own thread, its own transaction and its own pooled connection.
        // The use case locks the student account (SELECT ... FOR UPDATE) inside the transaction
        // opened by the single transaction component of shared/security (ADR-0015).
        Callable<PaymentResult> cashierA = () -> registerPayment.handle(new RegisterPaymentCommand(
                fixture.studentId(), tuition, PaymentMethod.CASH, "cashier-a-0001"));
        Callable<PaymentResult> cashierB = () -> registerPayment.handle(new RegisterPaymentCommand(
                fixture.studentId(), tuition, PaymentMethod.CASH, "cashier-b-0001"));

        List<PaymentResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (Future<PaymentResult> future : executor.invokeAll(List.of(cashierA, cashierB))) {
                results.add(future.get());
            }
        }

        assertThat(results).extracting(PaymentResult::status)
                .containsExactly(PaymentStatus.CONFIRMED, PaymentStatus.CONFIRMED);

        assertThat(charges.outstandingOf(fixture.chargeId())).isEqualTo(Money.zero("HNL"));

        // Credit balance in favour of the student, derived from the ledger.
        assertThat(ledger.balanceOf(fixture.studentId())).isEqualTo(Money.of("-3500.00", "HNL"));

        // Never apply more than the charge outstanding amount.
        assertThat(paymentApplications.totalAppliedTo(fixture.chargeId())).isEqualTo(tuition);
    }
}
```

### Ejemplo 2: dos emisiones simultáneas de factura

Escenario: dos cajeros emiten una factura al mismo tiempo sobre el mismo rango CAI. El resultado
correcto es dos correlativos distintos y consecutivos. El resultado incorrecto, y el que se busca
hacer imposible, es el mismo correlativo asignado dos veces, algo que ocurre de manera sistemática
con `MAX(...) + 1`.

```java
// apps/api/app/src/test/java/<paquete-base>/invoicing/ConcurrentFiscalNumberingIT.java
class ConcurrentFiscalNumberingIT extends PostgresIntegrationTest {

    @Test
    void neverAssignsTheSameCorrelativeTwice() throws Exception {
        fixtures.activeCaiRange("TEST-CAI-0000-0000-0000-0000-0000-00", 1, 500,
                Instant.parse("2026-12-31T23:59:59Z"));

        List<Callable<IssuedInvoice>> issuances = IntStream.range(0, 50)
                .mapToObj(index -> (Callable<IssuedInvoice>) () -> issueInvoice.handle(
                        new IssueInvoiceCommand(TestStudents.ID, "race-" + index)))
                .toList();

        List<Long> correlatives = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
            for (Future<IssuedInvoice> future : executor.invokeAll(issuances)) {
                correlatives.add(future.get().correlative());
            }
        }

        assertThat(correlatives).doesNotHaveDuplicates().hasSize(50);
        assertThat(correlatives).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, 50).boxed().toList());
    }
}
```

Regla de escritura de estas pruebas: hilos independientes, cada uno con su propia transacción y su
propia conexión del pool, nunca llamadas que comparten la misma conexión, porque una única
conexión serializa el trabajo y la prueba pasa sin haber probado nada. Por la misma razón, estas
clases no usan la reversión automática de transacción por prueba (sección 6).

---

## 8. Cómo se prueba la seguridad a nivel de fila

La política de fila se prueba **conectándose a PostgreSQL con el rol restringido del portal**, no
con el rol administrativo de las migraciones.

```java
// apps/api/app/src/test/java/<paquete-base>/portal/GuardianRowLevelSecurityIT.java
// Plain parameterized JDBC on purpose: the test must not depend on any application code path.
class GuardianRowLevelSecurityIT extends PostgresIntegrationTest {

    @Test
    void hidesAnotherGuardianStatementEvenWithAHandWrittenQuery() throws SQLException {
        var guardianA = fixtures.guardianWithStudent();
        var guardianB = fixtures.guardianWithStudent();

        try (Connection portal = database.connectAs("confia_portal_app")) {
            portal.setAutoCommit(false);

            // Transaction local context, exactly as docs/03-seguridad.md section 6.2 requires.
            try (PreparedStatement context = portal.prepareStatement("""
                    SELECT set_config('app.actor_id',       ?, true),
                           set_config('app.actor_kind',     'guardian', true),
                           set_config('app.institution_id', ?, true)
                    """)) {
                context.setString(1, guardianA.id().toString());
                context.setString(2, guardianA.institutionId().toString());
                context.execute();
            }

            assertThat(countLedgerEntries(portal, guardianA.studentId())).isPositive();
            assertThat(countLedgerEntries(portal, guardianB.studentId())).isZero();

            try (PreparedStatement delete =
                         portal.prepareStatement("DELETE FROM ledger_entry WHERE student_id = ?")) {
                delete.setObject(1, guardianA.studentId());
                assertThatThrownBy(delete::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
            portal.rollback();
        }
    }

    private static long countLedgerEntries(Connection connection, UUID studentId) throws SQLException {
        try (PreparedStatement query =
                     connection.prepareStatement("SELECT count(*) FROM ledger_entry WHERE student_id = ?")) {
            query.setObject(1, studentId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }
}
```

### Por qué un doble de prueba jamás lo detectaría

Un repositorio simulado devuelve lo que el desarrollador programó que devuelva. Si el caso de uso
olvida agregar el filtro por encargado, el simulacro sigue devolviendo la lista filtrada, porque
el filtro vive en el simulacro y no en la consulta. La prueba pasa en verde mientras el sistema
real expone el estado de cuenta de otro estudiante.

La política de fila es una regla del motor, y solo el motor puede confirmar que está activa, que
apunta a la variable de sesión correcta, que el rol no la puede eludir por ser propietario de la
tabla y que la migración que la crea se aplicó. Ninguna de esas cuatro cosas es observable desde
el código de la aplicación. Por eso, en este sistema, **toda política de fila tiene una prueba de integración con
base de datos real, y esa prueba es bloqueante**.

---

## 9. Pruebas de extremo a extremo

### Recorridos críticos que se automatizan

Se automatizan solo los recorridos donde un fallo silencioso significa dinero mal cobrado,
documento fiscal inválido o exposición de datos.

| Recorrido | Por qué es crítico |
|---|---|
| Inicio de sesión del personal con MFA obligatoria | Puerta de entrada a todo el sistema administrativo |
| Apertura de sesión de caja, cobro en efectivo y cierre con arqueo | Control interno de efectivo, la brecha de mayor riesgo operativo |
| Registro de pago en ventanilla con emisión de factura | Camino principal del negocio, extremo a extremo del dinero |
| Anulación de factura y emisión de nota de crédito | Corrección fiscal: el error humano más caro si falla |
| Aplicación de pago parcial y generación de saldo a favor | Regla de imputación con impacto directo en la deuda del estudiante |
| Consulta del estado de cuenta en el portal de encargados | Único punto donde datos de menores llegan a internet abierto |
| Intento de acceso cruzado entre encargados | Verificación de aislamiento en el recorrido completo, no solo en la consulta |

### Regla de no automatizar todo

Cada prueba de extremo a extremo es lenta, frágil y cara de mantener. Con un solo desarrollador, un
conjunto de extremo a extremo grande se vuelve un conjunto de extremo a extremo apagado.

Reglas:

- Si un caso se puede verificar en integración, se verifica en integración. Extremo a extremo no
  duplica cobertura de reglas de negocio.
- Un recorrido de extremo a extremo cubre el **camino feliz completo** y, a lo sumo, un desvío
  crítico. Los casos límite viven en los niveles inferiores.
- Las validaciones de formulario campo por campo se prueban en nivel de componente, nunca en
  extremo a extremo.
- Una prueba de extremo a extremo intermitente se corrige o se elimina en un plazo de cuarenta y
  ocho horas. No se marca como omitida de forma indefinida: una prueba omitida es deuda invisible.
- El conjunto completo no supera los doce minutos. Si los supera, se recorta, no se amplía el
  tiempo permitido.

---

## 10. Estrategia de pruebas de regresión visual

La regresión visual se aplica de forma selectiva, porque en un sistema administrativo denso las
capturas de pantalla completas producen ruido constante.

- **Alcance.** Se capturan los componentes del sistema de diseño en `packages/ui` mediante
  historias, no las páginas completas de la aplicación. Excepción: tres pantallas críticas por su
  contenido monetario, que son estado de cuenta, comprobante de pago y reporte de cierre de caja.
- **Herramienta.** Playwright con comparación de capturas, ejecutado en un contenedor Linux fijo
  para eliminar diferencias de renderizado de fuentes entre sistemas operativos.
- **Determinismo.** Antes de capturar se congela el reloj, se deshabilitan animaciones y
  transiciones mediante inyección de CSS, y se usan datos de una semilla fija. Sin estas tres
  medidas, la comparación visual genera falsos positivos y se abandona en dos semanas.
- **Variantes obligatorias por componente.** Tema claro y oscuro, viewport de escritorio y de
  móvil, estado por defecto, de carga, vacío y de error.
- **Umbral.** Diferencia máxima del 0.1 % de píxeles. Por encima, la prueba falla.
- **Aprobación de cambios.** Una diferencia visual no se aprueba de forma automática. La imagen de
  referencia se actualiza con un comando explícito y el cambio de imagen viaja en el mismo commit
  que el cambio de código, de modo que sea revisable.
- **Puerta.** Advierte en rama de trabajo, bloquea en la rama principal.

---

## 11. Presupuestos de rendimiento

### Latencia de la API

Medida en el percentil 95, bajo la carga nominal declarada de 40 usuarios administrativos
concurrentes y 300 encargados concurrentes en el portal.

| Tipo de operación | Presupuesto p95 | Presupuesto p99 |
|---|---|---|
| Lectura simple con clave primaria | 80 ms | 200 ms |
| Listado paginado con filtros en servidor | 300 ms | 700 ms |
| Estado de cuenta de un estudiante, derivado del libro mayor | 400 ms | 900 ms |
| Registro de pago con aplicación a cargos | 500 ms | 1 200 ms |
| Emisión de factura con asignación de correlativo | 700 ms | 1 500 ms |
| Cierre de caja con arqueo | 2 000 ms | 4 000 ms |
| Generación de reporte exportable, en segundo plano | Tarea programada en menos de 200 ms; entregado en menos de 60 s | 120 s |
| Autenticación con verificación de contraseña | 400 ms | 800 ms |

Reglas adicionales: ninguna consulta individual supera 100 ms en el plan de ejecución sobre el
conjunto de datos de referencia de 2 000 estudiantes y 200 000 asientos, y ninguna ruta ejecuta
más de 15 consultas por solicitud. La detección de consultas en bucle es automática y bloqueante.

### Presupuestos de tamaño de paquete en el frontend

Medidos comprimidos con Brotli, sobre la compilación de producción.

| Artefacto | Presupuesto |
|---|---|
| `admin-web`: JavaScript de la ruta inicial | 220 KB |
| `admin-web`: CSS de la ruta inicial | 40 KB |
| `admin-web`: fragmento por ruta perezosa | 120 KB |
| `portal-web`: JavaScript de la ruta inicial | 150 KB |
| `portal-web`: CSS de la ruta inicial | 30 KB |
| Cualquier dependencia individual nueva | 50 KB; por encima requiere justificación escrita en el cambio SDD |

Métricas de experiencia en el portal, que es el que se consume desde redes móviles: Largest
Contentful Paint por debajo de 2.5 s, Interaction to Next Paint por debajo de 200 ms y Cumulative
Layout Shift por debajo de 0.1, medidos en perfil de red 4G lenta.

---

## 12. Definición de terminado desde la perspectiva de calidad

Una unidad de trabajo no está terminada hasta que cumple todo lo siguiente. La lista amplía la
definición de terminado general de `CLAUDE.md` con el detalle de calidad.

- [ ] La especificación de la capacidad afectada está actualizada y sus escenarios corresponden a
      pruebas reales.
- [ ] Backend: compilación, `maven-enforcer-plugin`, ArchUnit y Spring Modulith en verde.
      Frontend: verificación de tipos, ESLint y `dependency-cruiser` en verde. Sin supresiones ni
      exclusiones nuevas sin justificación escrita.
- [ ] Toda regla monetaria nueva o modificada tiene pruebas unitarias con la lista completa de
      casos límite de la sección 5.
- [ ] Toda escritura financiera nueva tiene prueba de integración de idempotencia.
- [ ] Toda operación que afecte el libro mayor tiene prueba de integración de cuadre.
- [ ] Toda política de fila nueva o modificada tiene prueba de integración con el rol restringido.
- [ ] Toda operación concurrente sensible tiene prueba con conexiones reales en paralelo.
- [ ] Cobertura global igual o superior al 80 %, y módulo de núcleo y paquetes `domain` iguales o
      superiores al 95 %, medida con JaCoCo.
- [ ] Puntuación de mutación con PIT igual o superior al 80 % si el cambio tocó el módulo de
      núcleo o un paquete `domain`.
- [ ] Los estados de carga, vacío, error y éxito están implementados y tienen prueba de componente.
- [ ] axe-core no reporta violaciones críticas ni serias en las pantallas afectadas.
- [ ] El OpenAPI generado coincide con la instantánea aprobada o su cambio está declarado,
      `packages/contracts` se regeneró con orval y las aplicaciones web compilan contra él.
- [ ] Los campos de importe de presentación salen del servidor con la escala menor de su moneda.
- [ ] Las acciones sensibles escriben en la bitácora de auditoría y existe prueba que lo demuestra.
- [ ] Ningún dato personal aparece en logs, en pruebas, en accesorios ni en capturas.
- [ ] Los presupuestos de latencia y de tamaño de paquete aplicables se respetan.
- [ ] Si el cambio corrige un defecto, existe la prueba de regresión que fallaba antes de la
      corrección.

---

## 13. Cómo se prueba un defecto

El procedimiento es fijo y no admite atajos.

1. **Reproducir.** Escribir la prueba más pequeña posible que falle por la misma causa que el
   defecto reportado. Se escribe en el nivel más bajo donde el defecto sea observable: si es una
   regla monetaria, unitaria; si es un invariante de base de datos o una condición de carrera,
   integración.
2. **Confirmar el rojo.** Ejecutar y verificar que falla, y que **falla por la razón correcta**.
   Una prueba que falla por un error de compilación o por un dato mal construido no reprodujo nada.
3. **Registrar.** El nombre de la prueba referencia el identificador del defecto, por ejemplo
   `regression BUG-142: partial payment leaves one cent outstanding`.
4. **Corregir.** Escribir el cambio mínimo que pone la prueba en verde. Ninguna refactorización
   oportunista en el mismo commit.
5. **Confirmar el verde.** La prueba nueva pasa y ninguna prueba existente se rompe.
6. **Ampliar.** Preguntar qué otro caso comparte la misma causa raíz y agregar esos casos. Un
   defecto de redondeo casi nunca es un caso aislado.
7. **Verificar la guardia.** Revertir la corrección de forma temporal y comprobar que la prueba
   vuelve a fallar. Si sigue en verde, la prueba no protege nada y hay que reescribirla.
8. **Cerrar.** El commit de corrección incluye la prueba. Una corrección sin prueba de regresión no
   se fusiona.

---

## 14. Configuración de referencia

### 14.1 Backend: qué verifica `./mvnw verify`

El backend se verifica con un único comando del Maven Wrapper en `apps/api`. La configuración
exacta de cada complemento se fija en el POM padre en F0; este documento fija los controles, los
umbrales y el orden, no la sintaxis de cada complemento.

| Control | Herramienta | Fase de Maven | Umbral o regla |
|---|---|---|---|
| Versión de Java, convergencia de dependencias, sin `SNAPSHOT` en la rama principal, módulo de núcleo sin dependencias fuera del JDK, dependencias prohibidas de Hibernate, Jakarta Persistence, Spring Data JPA y Spring Data JDBC, y de Quartz, JobRunr o cualquier otra biblioteca de programación distinta de db-scheduler | `maven-enforcer-plugin` | `validate` | Falla ante cualquier violación (ADR-0013, ADR-0015, ADR-0016) |
| Generación de código de jOOQ | Migraciones de Flyway aplicadas sobre un PostgreSQL 18 temporal y generación de clases; mecanismo concreto validado en F0 | `generate-sources` | Una consulta incompatible con el esquema no compila (ADR-0015) |
| Pruebas unitarias | Surefire con JUnit, AssertJ y jqwik (`*Test.java`) | `test` | Todas en verde |
| Pruebas de arquitectura | ArchUnit y verificación de módulos de Spring Modulith (ADR-0002), más las reglas monetarias de ADR-0004 y las de acceso a datos de ADR-0015 (jOOQ solo en `infrastructure`, propiedad de tablas por módulo, transacciones solo en el componente de `shared/security`, SQL plano solo en la lista aprobada de reportes) y las de trabajos en segundo plano de ADR-0016 (sin `@Scheduled`, `@EnableScheduling` ni `@Async` en el código de producción). Comportamiento ante conjunto vacío: `archRule.failOnEmptyShould` queda en `true`; una regla solo puede evaluarse contra cero clases con una excepción declarada por regla, que cita ADR-0018 y caduca de forma verificada (inventario de caducidad y escáner de supresiones, ADR-0018) | `test` | Cero violaciones |
| Pruebas de integración | Failsafe con JUnit y Testcontainers (`*IT.java`) | `integration-test` y `verify` | Todas en verde |
| Trabajos en segundo plano (ADR-0016) | Pruebas de integración con Testcontainers: arranque por perfil (el ejecutor de tareas solo existe en `confia-worker`), programación dentro de transacciones confirmadas y revertidas, doble ejecución por tipo de tarea con un solo efecto, lista aprobada de campos de los datos de cada tipo de tarea, contexto de seguridad del manejador (solo ve datos de la institución de la tarea) y verificación de esquema con el catálogo cerrado de ADR-0017, exactamente cuatro tablas sin `institution_id`: tres tablas técnicas y la tabla raíz de instituciones (`docs/03-seguridad.md`, sección 6.4) | `integration-test` y `verify` | Todas en verde. Agregar una tabla a la lista aprobada exige un ADR |
| Tablas técnicas y eventos persistidos (ADR-0017) | Prueba de configuración de bibliotecas (creación automática de esquema de Spring Modulith desactivada, modo de finalización `DELETE`, modo de archivo inactivo); lista aprobada de campos por tipo de evento que se persiste en el registro, sin datos personales; matriz de permisos sobre las tres tablas técnicas y casos de uso del portal ejecutados con `confia_portal_app` (`docs/03-seguridad.md`, sección 6.4) | `test` e `integration-test` | Todas en verde |
| Cobertura | JaCoCo, con los datos de las pruebas unitarias y de integración | `verify` | 80 % global; 95 % en el módulo de núcleo y en cada paquete `domain` |
| Mutación | PIT sobre el módulo de núcleo y los paquetes `domain` | `verify`, con los perfiles `mutation-gate` (bloquea) y `mutation-report` (solo informa) | Puntuación mínima 80 |
| Contrato | Generación del OpenAPI con springdoc-openapi y comparación contra la instantánea aprobada | `verify` | Cero diferencias no declaradas; todo importe con forma `{ amount: string, currency: string }` |

### 14.2 Base de las pruebas de integración con Testcontainers

```java
// apps/api/app/src/test/java/<paquete-base>/support/PostgresIntegrationTest.java
// Illustrative. The Testcontainers API is the one of the version fixed in F0.
@SpringBootTest
public abstract class PostgresIntegrationTest {

    // One container per test JVM, reused by every integration test class. Starting it once
    // is what keeps the suite inside its time budget (section 2).
    static final PostgreSQLContainer<?> POSTGRES =
            // PostgreSQL 18, the same major version as every other environment (ADR-0015).
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"))
                    .withDatabaseName("confia_test")
                    .withUsername("confia_owner")
                    .withPassword("test-only-not-a-secret")
                    .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off",
                            "-c", "max_connections=200")
                    .withTmpFs(Map.of("/var/lib/postgresql/data", "rw,noexec,nosuid,size=1024m"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // Flyway migrates as the schema owner. In deployed processes only APP_PROFILE=migrate
        // runs Flyway (docs/05-infraestructura-y-despliegue.md, section 9).
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // The application connects with its least privilege role, never as the owner.
        // Application roles are created by a test only init script, never by a Flyway
        // migration that carries a password.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "confia_admin_app");
        registry.add("spring.datasource.password", () -> "test-only-not-a-secret");
    }
}
```

Base de datos limpia por prueba (sección 6): las clases que no prueban concurrencia ni el
comportamiento de la propia transacción se anotan con `@Transactional` de Spring en la prueba, que
revierte la transacción al terminar. Las clases de concurrencia, `SERIALIZABLE`, bloqueos y
disparadores al confirmar usan truncamiento selectivo.

Esa anotación vive solo en clases de prueba. La regla de ArchUnit de ADR-0015, que permite
`@Transactional`, `TransactionTemplate` y el gestor de transacciones únicamente dentro del
componente transaccional de `shared/security`, se aplica al código de producción. Las pruebas del
propio componente (contexto de seguridad establecido antes de cualquier consulta, nivel de
aislamiento y reintento ante error de serialización, ADR-0015, verificación 7) confirman de verdad
y usan truncamiento selectivo.

### 14.3 `vitest.config.ts` del frontend

```ts
import { defineConfig } from 'vitest/config';

// Frontend only. The backend is tested with JUnit through `./mvnw verify` (section 14.1).
export default defineConfig({
  test: {
    globals: false,
    reporters: process.env.CI ? ['default', 'junit'] : ['default'],
    outputFile: { junit: './reports/vitest-junit.xml' },
    projects: [
      {
        test: {
          name: 'web',
          include: ['apps/*-web/src/**/*.test.tsx', 'packages/ui/src/**/*.test.tsx'],
          environment: 'jsdom',
          setupFiles: ['./test/setup/testing-library.ts', './test/setup/msw.ts'],
        },
      },
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'lcov'],
      reportsDirectory: './coverage',
      exclude: [
        '**/*.config.*',
        '**/dist/**',
        '**/node_modules/**',
        '**/*.d.ts',
        '**/generated/**',
        'packages/contracts/**', // generated from the backend OpenAPI
        '**/test/**',
      ],
      thresholds: { lines: 80, branches: 80, functions: 80, statements: 80 },
    },
  },
});
```

### 14.4 `playwright.config.ts`

```ts
import { defineConfig, devices } from '@playwright/test';

const ADMIN_URL = process.env.ADMIN_BASE_URL ?? 'http://localhost:5173';
const PORTAL_URL = process.env.PORTAL_BASE_URL ?? 'http://localhost:5174';

export default defineConfig({
  testDir: './e2e/specs',
  outputDir: './e2e/.artifacts',
  timeout: 90_000,
  expect: { timeout: 10_000, toHaveScreenshot: { maxDiffPixelRatio: 0.001 } },
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI
    ? [['github'], ['html', { outputFolder: './reports/playwright' }], ['junit', { outputFile: './reports/playwright-junit.xml' }]]
    : [['list']],
  use: {
    trace: 'on-first-retry',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'es-HN',
    timezoneId: 'America/Tegucigalpa',
    actionTimeout: 10_000,
  },
  projects: [
    {
      name: 'admin-setup',
      testMatch: /admin\.setup\.ts$/,
      use: { ...devices['Desktop Chrome'], baseURL: ADMIN_URL },
    },
    {
      name: 'admin',
      dependencies: ['admin-setup'],
      testMatch: /admin\/.*\.e2e\.ts$/,
      use: {
        ...devices['Desktop Chrome'],
        baseURL: ADMIN_URL,
        storageState: './e2e/.auth/admin.json',
      },
    },
    {
      name: 'portal',
      testMatch: /portal\/.*\.e2e\.ts$/,
      use: { ...devices['Desktop Chrome'], baseURL: PORTAL_URL },
    },
    {
      name: 'portal-mobile',
      testMatch: /portal\/.*\.e2e\.ts$/,
      use: { ...devices['Pixel 7'], baseURL: PORTAL_URL },
    },
    {
      name: 'a11y',
      testMatch: /a11y\/.*\.e2e\.ts$/,
      use: { ...devices['Desktop Chrome'], baseURL: ADMIN_URL },
    },
  ],
  // The API is not started here. It runs from the same container image as production
  // (docker compose), started before Playwright: see the e2e job in ci.yml.
  webServer: [
    {
      command: 'pnpm --filter @confia/admin-web preview --port 5173',
      url: ADMIN_URL,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
    {
      command: 'pnpm --filter @confia/portal-web preview --port 5174',
      url: PORTAL_URL,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
  ],
});
```

### 14.5 Flujo de trabajo de integración continua

Dos cadenas de herramientas, un orden fijo: el backend se verifica y publica su OpenAPI; el
frontend regenera `packages/contracts` a partir de ese OpenAPI y se verifica contra él. Los nombres
de las tareas de Turborepo y la ubicación de salida del OpenAPI se fijan en F0.

```yaml
# .github/workflows/ci.yml
name: ci

on:
  pull_request:
  push:
    branches: [main]

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

env:
  # Must match the parent pom.xml and the API base image (ADR-0013, check 2).
  JAVA_VERSION: '25'
  JAVA_DISTRIBUTION: temurin
  NODE_VERSION: '22'
  PNPM_VERSION: '9'

jobs:
  secrets:
    name: secret scan
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          fetch-depth: 0
      - uses: gitleaks/gitleaks-action@v2
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

  backend:
    name: backend verify
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
      - uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4
        with:
          distribution: ${{ env.JAVA_DISTRIBUTION }}
          java-version: ${{ env.JAVA_VERSION }}
          cache: maven
      # `verify` runs, in order: maven-enforcer-plugin, compilation, unit tests (JUnit,
      # AssertJ, jqwik), ArchUnit and Spring Modulith architecture tests, integration tests
      # with Testcontainers on real PostgreSQL, JaCoCo thresholds (80 % global, 95 % kernel
      # and every domain package), OpenAPI generation and comparison against the approved
      # snapshot, and PIT on the kernel and domain packages (threshold 80).
      # Mutation blocks on main and only reports on work branches (section 3).
      - name: verify
        working-directory: apps/api
        run: ./mvnw --batch-mode verify ${{ github.ref == 'refs/heads/main' && '-Dconfia.ci.mainBranch=true' || '-Pmutation-report' }}
        env:
          TESTCONTAINERS_RYUK_DISABLED: 'false'
      - name: upload openapi
        uses: actions/upload-artifact@v4
        with:
          name: openapi
          path: apps/api/**/target/openapi/
      - name: upload coverage and mutation reports
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: backend-reports
          path: |
            apps/api/**/target/site/jacoco/
            apps/api/**/target/pit-reports/

  frontend:
    name: frontend static, component and contract
    runs-on: ubuntu-latest
    needs: backend
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
      - uses: actions/download-artifact@v4
        with:
          name: openapi
          path: build/openapi
      - uses: pnpm/action-setup@v4
        with:
          version: ${{ env.PNPM_VERSION }}
      - uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: pnpm
      - run: pnpm install --frozen-lockfile
      - name: regenerate packages/contracts with orval
        run: pnpm turbo run contracts:generate
      - name: typecheck web apps against the regenerated contract (tsc --noEmit)
        run: pnpm turbo run typecheck
      - name: lint
        run: pnpm turbo run lint
      - name: frontend dependency boundaries
        run: pnpm depcruise --config .dependency-cruiser.cjs apps/admin-web apps/portal-web packages
      - name: component tests
        run: pnpm vitest run --project web --coverage
      - name: bundle budgets
        run: pnpm turbo run build && pnpm run size-limit

  e2e:
    name: end to end
    runs-on: ubuntu-latest
    needs: [backend, frontend]
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
      - uses: actions/download-artifact@v4
        with:
          name: openapi
          path: build/openapi
      - uses: pnpm/action-setup@v4
        with:
          version: ${{ env.PNPM_VERSION }}
      - uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: pnpm
      - run: pnpm install --frozen-lockfile
      - run: pnpm turbo run contracts:generate
      # The API runs from the same image definition as production. Placeholder values only.
      - name: start data services, migrate, start api
        run: |
          cp .env.example .env
          docker compose up --detach --wait postgres redis minio
          docker compose run --rm -e APP_PROFILE=migrate api-admin
          docker compose up --detach --wait api-admin api-portal
      - name: install browsers
        run: pnpm exec playwright install --with-deps chromium
      - name: run critical journeys
        run: pnpm exec playwright test
      - uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: playwright-report
          path: reports/playwright/

  security:
    name: security scanning
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
      - name: semgrep
        uses: semgrep/semgrep-action@v1
        with:
          config: >-
            p/java
            p/typescript
            p/owasp-top-ten
            .semgrep/confia-financial.yml
      - uses: pnpm/action-setup@v4
        with:
          version: ${{ env.PNPM_VERSION }}
      - uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: pnpm
      - run: pnpm install --frozen-lockfile
      - name: pnpm dependency audit
        run: pnpm audit --audit-level high
      # Baseline scan of both dependency chains (pom.xml and pnpm-lock.yaml). The definitive
      # Maven scanner is fixed in F0 (docs/03-seguridad.md, section 13).
      - name: maven and pnpm dependency scan
        uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0
        with:
          scan-type: fs
          scan-ref: .
          scanners: vuln
          severity: HIGH,CRITICAL
          exit-code: '1'
          ignore-unfixed: true
      - name: build image
        run: docker build -f infra/docker/api.Dockerfile -t confia-api:ci .
      - name: trivy image scan
        uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0
        with:
          image-ref: confia-api:ci
          severity: HIGH,CRITICAL
          exit-code: '1'
          ignore-unfixed: true

  quality-gate:
    name: quality gate
    runs-on: ubuntu-latest
    needs: [secrets, backend, frontend, e2e, security]
    steps:
      - name: all required checks passed
        run: echo "merge allowed"
```

```yaml
# .github/workflows/nightly.yml
name: nightly

on:
  schedule:
    - cron: '0 6 * * *'
  workflow_dispatch:

jobs:
  mutation:
    name: full mutation run on kernel and domain packages
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
      - uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4
        with:
          distribution: temurin
          java-version: '25'
          cache: maven
      - name: pit
        working-directory: apps/api
        run: ./mvnw --batch-mode verify -Pmutation-gate

  performance:
    name: k6 load budgets
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
      - uses: grafana/setup-k6-action@v1
      - name: payment registration load
        run: k6 run perf/k6/register-payment.js
      - name: fiscal numbering under concurrency
        run: k6 run perf/k6/issue-invoice.js

  zap:
    name: owasp zap baseline
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
      - name: zap baseline scan
        uses: zaproxy/action-baseline@v0.12.0
        with:
          target: ${{ secrets.STAGING_PORTAL_URL }}
          rules_file_name: security/zap/rules.tsv
          cmd_options: '-a -j'
```

---

## 15. Documentos relacionados

| Documento | Contenido |
|---|---|
| `CLAUDE.md` | Reglas no negociables e idioma de los artefactos |
| `docs/01-arquitectura.md` | Arquitectura, fronteras de módulo y stack |
| `docs/03-seguridad.md` | Modelo de amenazas y controles |
| `docs/04-cumplimiento-fiscal-sar.md` | CAI, correlativos e impuestos |
| `docs/08-datos-privacidad-y-retencion.md` | Datos de menores y política de retención |
| `docs/ui-ux/03-accesibilidad.md` | Criterios de accesibilidad AA |
| `openspec/specs/` | Especificaciones vigentes por capacidad, origen de los escenarios de prueba |

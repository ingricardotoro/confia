---
name: confia-testing-playbook
description: Guía de pruebas de CONFIA. Se dispara al escribir pruebas, decidir qué nivel de prueba corresponde, corregir un defecto, revisar cobertura o mutación, o trabajar con JUnit, AssertJ, jqwik, Testcontainers, JaCoCo, PIT, ArchUnit, Vitest, Testing Library, MSW o Playwright.
---

# Guía de pruebas de CONFIA

CONFIA adopta el **trofeo de pruebas**, no la pirámide clásica: el mayor peso recae en pruebas de
integración con PostgreSQL real, porque la mayoría de los invariantes que importan en este sistema
no viven en el código de la aplicación, viven en el motor de base de datos. Fuente de verdad
completa: `docs/06-estrategia-de-testing.md`. Esta skill resume lo accionable para escribir o
revisar una prueba concreta.

Instrumental:

- **Backend (Java):** JUnit, AssertJ, jqwik para pruebas de propiedad, Testcontainers con
  PostgreSQL real, JaCoCo para cobertura, PIT para mutación, ArchUnit y las pruebas de Spring
  Modulith. Todo se ejecuta con `./mvnw verify` en `apps/api`.
- **Frontend (TypeScript):** Vitest, Testing Library, MSW, Playwright y axe-core.
- **Rendimiento:** k6.

Los ejemplos de código son ilustrativos: los nombres de fábricas, accesorios y casos de uso los
fija la especificación de cada cambio, y `<paquete-base>` se fija en F0.

## 1. Qué nivel de prueba corresponde a cada tipo de código

| Tipo de código | Nivel obligatorio | Por qué |
|---|---|---|
| `Money` y tipos del módulo `kernel`, reglas puras de un paquete `domain` (redondeo, mora, impuestos, imputación) | Unitario con JUnit y AssertJ, propiedad con jqwik donde haya invariantes generales, mutación con PIT | Lógica pura, sin dependencias externas, densa en casos límite |
| Caso de uso de `application` | Unitario con dobles de prueba para los puertos, más integración si escribe en base de datos | El unitario prueba la orquestación; la integración prueba que el efecto real ocurrió |
| Adaptador de `infrastructure` | Integración con Testcontainers | Necesita el motor real: tipo de columna, restricciones, disparadores |
| Política de seguridad a nivel de fila | Integración con el rol de PostgreSQL restringido | Un doble de prueba nunca tiene políticas de fila. Ver sección 3 |
| Controlador de `web` | Integración del endpoint + contrato (OpenAPI contra la instantánea aprobada) + prueba de componente del cliente que lo consume | El contrato garantiza forma; el componente garantiza consumo correcto |
| Componente de React | Componente con Vitest, Testing Library y MSW | Estados de interfaz, interacción, formularios |
| Máquina de estados (pago, factura, caja, promesa, solicitud) | Unitario sobre las transiciones + integración sobre la persistencia del estado | Ver sección 6 |
| Recorrido crítico de negocio completo | Extremo a extremo con Playwright | Solo los recorridos de `docs/06-estrategia-de-testing.md` sección 9 |
| Regla de dependencia entre capas o módulos | Estático: ArchUnit y Spring Modulith en el backend, `dependency-cruiser` en el frontend | No es un comportamiento en tiempo de ejecución, es una regla de estructura |
| Punto de entrada del portal | Integración que arranca el contexto del portal y afirma que ningún controlador administrativo está registrado | Aislamiento del proceso del portal (ADR-0003) |

Nombres de archivo del backend: `*Test.java` para unitarias (Surefire) y `*IT.java` para
integración (Failsafe), bajo `src/test/java` del módulo Maven correspondiente.

## 2. Casos límite obligatorios de toda regla financiera

Ver también `confia-money-rules` sección 10 para el detalle de cada uno. Ninguno se puede omitir en
una regla que produce, transforma o compara dinero:

- [ ] Importe cero, como entrada y como resultado.
- [ ] Importe negativo, admitido o rechazado según el tipo de transacción.
- [ ] Un centavo, la mínima unidad representable.
- [ ] Redondeo `HALF_UP` en el punto medio exacto (`0.615`, `12.345`), positivo y negativo.
- [ ] Valor con más de cuatro decimales, redondeado de forma explícita a escala 4.
- [ ] Valor cercano al límite de `NUMERIC(14,4)`, para detectar desborde silencioso.
- [ ] Moneda distinta: debe lanzar `CurrencyMismatchException`, nunca convertir en silencio.
- [ ] Prorrateo cuya suma de partes debe igualar exactamente el total.
- [ ] Concurrencia: dos operaciones simultáneas sobre el mismo saldo.
- [ ] Idempotencia: la misma operación repetida con la misma clave.
- [ ] Orden de operaciones (descuento antes de impuesto, nunca al revés).
- [ ] Entrada nula o ausente, rechazada en el borde por Bean Validation, nunca convertida a cero en
      silencio.
- [ ] Escala igual: `1.0` y `1.00` son el mismo `Money`; nunca se comparan con `BigDecimal.equals`.

## 3. Cómo se prueba una política de seguridad a nivel de fila, y por qué un doble jamás la detecta

**Se prueba conectando con el rol de PostgreSQL restringido real** (`confia_portal_app` o
equivalente), nunca con el propietario del esquema ni con un repositorio simulado. La prueba usa
JDBC parametrizado a propósito, para no depender de ningún camino de código de la aplicación.
Ejemplo completo en `docs/06-estrategia-de-testing.md` sección 8.

```java
// apps/api/app/src/test/java/<paquete-base>/portal/GuardianRowLevelSecurityIT.java
@Test
void hidesAnotherGuardianStatementEvenWithAHandWrittenQuery() throws SQLException {
    var guardianA = fixtures.guardianWithStudent();
    var guardianB = fixtures.guardianWithStudent();

    try (Connection portal = database.connectAs("confia_portal_app")) {
        portal.setAutoCommit(false);
        // Transaction-local context (third argument true), never SET SESSION.
        // Same context variables as docs/03-seguridad.md section 6.2.
        try (PreparedStatement context = portal.prepareStatement("""
                SELECT set_config('app.actor_id',       ?, true),
                       set_config('app.actor_kind',     'guardian', true),
                       set_config('app.institution_id', ?, true)
                """)) {
            context.setString(1, guardianA.id().toString());
            context.setString(2, guardianA.institutionId().toString());
            context.execute();
        }

        assertThat(countLedgerEntries(portal, guardianB.studentId())).isZero(); // zero rows, not an error
        portal.rollback();
    }
}
```

**Por qué un doble de prueba jamás lo detecta.** Un repositorio simulado devuelve exactamente lo
que el desarrollador programó que devuelva. Si el caso de uso olvida agregar el filtro por
encargado, el simulacro sigue devolviendo la lista "filtrada" porque el filtro vive en el propio
simulacro, no en una consulta real contra un motor con políticas activas. La prueba pasa en verde
mientras el sistema real expondría el estado de cuenta de otro estudiante. Solo el motor de base de
datos, ejecutando la política real bajo el rol real, puede confirmar que la barrera existe, que
apunta a la variable de sesión correcta y que el rol no la elude por ser propietario de la tabla.
Por eso **toda política de fila tiene una prueba de integración con base de datos real, y esa
prueba es bloqueante.** Incluye además la prueba de contexto ausente (falla cerrado) y la de fuga
entre solicitudes que reutilizan la misma conexión del pool.

## 4. Cómo se prueba la idempotencia

Tres casos por escritura financiera: nuevo, repetición exacta y conflicto.

```java
@Test
void returnsTheExactSameResultForARepeatedIdempotencyKey() {
    var command = validPayment().idempotencyKey("test-idem-0001").build();

    PaymentResult first = registerPayment.handle(command);
    PaymentResult second = registerPayment.handle(command);

    assertThat(second).isEqualTo(first);                                   // same response
    assertThat(payments.countByIdempotencyKey("test-idem-0001")).isOne();  // a single effect
}

@Test
void rejectsTheSameKeyWithADifferentPayload() {
    registerPayment.handle(validPayment().idempotencyKey("test-idem-0002").build());

    assertThatThrownBy(() -> registerPayment.handle(validPayment()
            .idempotencyKey("test-idem-0002")
            .amount(Money.of("999.00", CurrencyCode.HNL))
            .build()))
            .isInstanceOf(IdempotencyKeyConflictException.class);
}
```

### 4.1 Tareas en segundo plano (ADR-0016)

La ejecución de db-scheduler es "al menos una vez": un reintento o una ejecución abandonada puede
repetir el manejador. Cada tipo de tarea tiene su prueba de doble ejecución con PostgreSQL real.

```java
@Test
void runningTheSameReceiptTaskTwiceProducesOneDelivery() {
    var data = new ReceiptDeliveryJob.Data(institutionId, paymentId);

    receiptDeliveryJob.execute(data);
    receiptDeliveryJob.execute(data);   // retry or abandoned run

    assertThat(deliveries.countByPayment(paymentId)).isOne();   // unique constraint holds
}
```

Además, por tipo de tarea: prueba de lista aprobada de campos de sus datos (solo identificadores) y
prueba de contexto de seguridad (el manejador solo ve datos de la institución de la tarea). Para el
mecanismo común: una tarea programada dentro de una transacción revertida no existe y dentro de
una confirmada sí, y solo el contexto de `confia-worker` arranca el ejecutor de tareas
(`docs/06-estrategia-de-testing.md`, sección 14.1).

## 5. Cómo se prueba la concurrencia con dos transacciones simultáneas

Se prueba con hilos independientes, cada uno con su propia transacción y su propia conexión del
pool, por ejemplo con un `ExecutorService` e `invokeAll`. Nunca sobre la misma conexión: una sola
conexión serializa el trabajo y la prueba pasa sin haber probado nada. Estas clases no usan la
reversión automática de transacción por prueba; limpian con truncamiento selectivo.

```java
List<Callable<PaymentResult>> cashiers = List.of(
        () -> registerPayment.handle(paymentFor(fixture, "cashier-a-0001")),
        () -> registerPayment.handle(paymentFor(fixture, "cashier-b-0001")));

List<PaymentResult> results = new ArrayList<>();
try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
    for (Future<PaymentResult> future : executor.invokeAll(cashiers)) {
        results.add(future.get());
    }
}

assertThat(charges.outstandingOf(fixture.chargeId())).isEqualTo(Money.zero(CurrencyCode.HNL));
assertThat(ledger.balanceOf(fixture.studentId())).isEqualTo(Money.of("-3500.00", CurrencyCode.HNL));
```

Ejemplo completo, incluido el correlativo fiscal bajo concurrencia, en
`docs/06-estrategia-de-testing.md` sección 7.

## 6. Cómo se prueban las máquinas de estado

Cada transición válida y cada transición inválida tiene su propia prueba explícita. No se prueba
solo el camino feliz de la máquina.

```java
class CashSessionTest {

    @Test
    void movesFromOpenToClosedOnlyThroughAValidCount() {
        CashSession session = CashSession.open(Money.of("500.00", CurrencyCode.HNL));
        CashSession closed = session.close(Money.of("3800.00", CurrencyCode.HNL));
        assertThat(closed.status()).isEqualTo(CashSessionStatus.CLOSED);
    }

    @Test
    void rejectsClosingASessionThatIsAlreadyClosed() {
        CashSession closed = givenClosedSession();
        assertThatThrownBy(() -> closed.close(anyAmount()))
                .isInstanceOf(InvalidCashSessionStateException.class);
    }

    @Test
    void rejectsATransitionTheStateMachineDoesNotDeclare() {
        CashSession open = CashSession.open(Money.zero(CurrencyCode.HNL));
        assertThatThrownBy(open::reopen).isInstanceOf(InvalidCashSessionStateException.class);
    }
}
```

## 7. Uso de Testcontainers

- Un contenedor `postgres:18-alpine` por JVM de pruebas (PostgreSQL 18, la misma versión mayor de
  todos los entornos, ADR-0015), compartido por todas las clases de integración de esa JVM. Arrancarlo una sola vez es lo que mantiene el conjunto dentro de su
  presupuesto de tiempo.
- Las migraciones reales de **Flyway** se aplican al contenedor como propietario del esquema antes
  de correr las pruebas, nunca un esquema recreado a mano que pueda divergir del real.
- La aplicación se conecta con su rol de mínimo privilegio, nunca como propietario. Los roles con
  credencial los crea un script de inicialización exclusivo de pruebas, nunca una migración.
- Cada prueba que no verifica concurrencia ni el comportamiento de la propia transacción se ejecuta
  en una transacción que se revierte al terminar (`@Transactional` de Spring en la clase de
  prueba). No se trunca ni se recrea el esquema entre pruebas. Esa anotación solo es válida en
  código de prueba: en producción, ADR-0015 la permite únicamente dentro del componente
  transaccional de `shared/security`.
- Excepción: pruebas de `SERIALIZABLE`, de bloqueo, de concurrencia con más de una conexión, de
  disparadores al confirmar o del propio componente transaccional (contexto de seguridad antes de
  cualquier consulta y reintento ante error de serialización, ADR-0015), que necesitan confirmar de
  verdad y usan truncamiento selectivo.
- Configuración de referencia completa en `docs/06-estrategia-de-testing.md` sección 14.2.

## 8. Uso de MSW en el frontend

Las pruebas de componente interceptan la red con MSW, nunca hacen mocks del módulo cliente HTTP a
mano. El handler de MSW usa el esquema Zod generado en `packages/contracts` para construir y validar
una respuesta, de modo que un cambio de contrato regenerado rompe la prueba en vez de dejarla en
verde con datos obsoletos.

```ts
// test/msw/handlers/payments.handlers.ts
export const paymentsHandlers = [
  http.post('/api/v1/payments', async ({ request }) => {
    const body = RegisterPaymentRequestSchema.parse(await request.json());
    return HttpResponse.json(buildPaymentResponse({ studentId: body.studentId }), { status: 201 });
  }),
];
```

Los importes de las respuestas simuladas se escriben como cadenas ya redondeadas a la escala menor
de la moneda, igual que las entrega el servidor.

## 9. Qué NO se prueba, y por qué

- **Detalles de implementación de un componente** (nombres de variables internas, estructura del
  DOM no observable por el usuario). Se prueba comportamiento observable: qué ve y qué puede hacer
  el usuario.
- **El código generado**: `packages/contracts` (tipos y esquemas Zod generados con orval) y
  cualquier cliente generado desde el OpenAPI. Es responsabilidad de la herramienta que lo genera;
  lo que sí se prueba es el contrato, comparando el OpenAPI contra la instantánea aprobada.
- **Bibliotecas de terceros.** No se reescribe una prueba para confirmar que TanStack Query cachea,
  que Bean Validation aplica una anotación básica o que Zod valida un tipo básico; se confía en sus
  propias pruebas.
- **Getters triviales y mapeos uno a uno** sin lógica, que no pueden fallar de una forma
  distinguible de un error de compilación.
- **Estilos visuales exactos fuera de la regresión visual selectiva** de `packages/ui` y las tres
  pantallas críticas señaladas en `docs/06-estrategia-de-testing.md` sección 10.

## 10. Regla de escribir primero la prueba que falla, al corregir un defecto

Procedimiento fijo, sin atajos, de `docs/06-estrategia-de-testing.md` sección 13:

1. **Reproducir.** Escribe la prueba más pequeña posible que falle por la misma causa que el
   defecto reportado, en el nivel más bajo donde sea observable.
2. **Confirmar el rojo**, y que falla por la razón correcta, no por un error de compilación o un
   dato mal construido.
3. **Registrar.** El nombre de la prueba referencia el identificador del defecto, por ejemplo
   `@DisplayName("regression BUG-142: partial payment leaves one cent outstanding")`.
4. **Corregir** con el cambio mínimo. Ninguna refactorización oportunista en el mismo commit.
5. **Confirmar el verde**, sin romper ninguna prueba existente.
6. **Ampliar.** Un defecto de redondeo casi nunca es un caso aislado: agrega los casos que
   comparten la misma causa raíz.
7. **Verificar la protección.** Revierte la corrección de forma temporal y confirma que la prueba
   vuelve a fallar. Si sigue en verde, la prueba no protege nada.
8. **Cerrar.** El commit de corrección incluye la prueba. Una corrección sin prueba de regresión no
   se fusiona.

## 11. Umbrales de cobertura y mutación

| Métrica | Umbral | Alcance | Herramienta |
|---|---|---|---|
| Cobertura de líneas y ramas | 80 % mínimo | Todo el monorepo, excluyendo generado y configuración | JaCoCo en el backend, cobertura de Vitest en el frontend |
| Cobertura de líneas y ramas | 95 % mínimo | Módulo `kernel` y paquete `domain` de cada módulo | JaCoCo |
| Puntuación de mutación | 80 % mínimo | Módulo `kernel` y paquete `domain` de cada módulo | PIT |

Un umbral insostenible se cambia en `docs/06-estrategia-de-testing.md` mediante un cambio SDD
explícito y revisado, nunca con un `eslint-disable`, un `@SuppressWarnings` ni una exclusión de
JaCoCo, PIT, ArchUnit o Spring Modulith sin referencia escrita al ADR que la autoriza.

## 12. Pruebas de mutación con PIT sobre `kernel` y los paquetes `domain`

PIT se ejecuta sobre el módulo `kernel` y los paquetes `domain`, no sobre el resto del backend,
porque es donde vive la lógica pura, densa en casos límite, cuya calidad de prueba importa más. Un
mutante sobreviviente en una regla de redondeo o de imputación significa que existe un
comportamiento incorrecto que ninguna prueba detectaría.

- La configuración vive en el POM padre de `apps/api` y se fija en F0.
- Se ejecuta con `./mvnw verify` y los perfiles `mutation-gate` (bloquea) o `mutation-report`
  (solo informa) (`docs/06-estrategia-de-testing.md`, sección 14.1).
- Bloquea en la rama principal y antes de una etiqueta de versión; en rama de trabajo solo
  advierte. La ejecución completa corre además cada noche.

Un mutante sobreviviente encontrado en revisión no se ignora: se escribe la prueba que lo mata o se
justifica por escrito por qué esa mutación es equivalente (produce el mismo comportamiento
observable) y no un hueco real de cobertura.

## 13. Ejemplo real del dominio: prueba completa

```java
// apps/api/kernel/src/test/java/<paquete-base>/kernel/MoneyTest.java
class MoneyTest {

    @Test
    void roundsHalfUpAtTheExactMidpoint() {
        Money base = Money.of("12.30", CurrencyCode.HNL);
        assertThat(base.percentage(Percentage.of("5"), RoundingMode.HALF_UP)
                .roundToMinorUnit(RoundingMode.HALF_UP))
                .isEqualTo(Money.of("0.62", CurrencyCode.HNL)); // 0.615
    }

    @Test
    void neverLosesCentsWhenAllocatingATotalAcrossParts() {
        Money total = Money.of("100.00", CurrencyCode.HNL);
        List<Money> parts = total.allocate(1, 1, 1);
        assertThat(parts.stream().reduce(Money.zero(CurrencyCode.HNL), Money::add)).isEqualTo(total);
    }

    @Test
    void throwsOnOperationsBetweenDifferentCurrencies() {
        Money hnl = Money.of("100.00", CurrencyCode.HNL);
        Money usd = Money.of("100.00", CurrencyCode.USD);
        assertThatThrownBy(() -> hnl.add(usd)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void keepsTheAdr0004RegressionCaseExact() {
        Money tax = Money.of("6.70", CurrencyCode.HNL)
                .percentage(Percentage.of("15"), RoundingMode.HALF_UP)
                .roundToMinorUnit(RoundingMode.HALF_UP);
        assertThat(tax).isEqualTo(Money.of("1.01", CurrencyCode.HNL)); // double arithmetic gives 1.00
    }
}
```

## 14. Antes de dar por terminado

- [ ] El nivel de prueba elegido corresponde al tipo de código, según la tabla de la sección 1.
- [ ] Toda regla monetaria nueva tiene la lista completa de casos límite de la sección 2.
- [ ] Toda política de fila nueva o modificada tiene prueba de integración con el rol restringido.
- [ ] Toda escritura financiera nueva tiene prueba de idempotencia con los tres casos: nuevo,
      repetición, conflicto.
- [ ] Toda operación concurrente sensible tiene prueba con conexiones reales en paralelo.
- [ ] Toda máquina de estados nueva tiene pruebas de sus transiciones válidas y de al menos una
      transición inválida por estado.
- [ ] Si el cambio corrige un defecto, existe la prueba de regresión con el identificador del
      defecto en su nombre.
- [ ] `./mvnw verify` en verde: cobertura global igual o superior a 80 %, `kernel` y paquetes
      `domain` iguales o superiores a 95 % con JaCoCo, y mutación con PIT igual o superior a 80 % si
      el cambio los tocó.
- [ ] Ningún dato personal real aparece en accesorios de prueba, capturas o archivos de ejemplo.

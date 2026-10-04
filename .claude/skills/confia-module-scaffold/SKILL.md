---
name: confia-module-scaffold
description: Estructura de un módulo del backend de CONFIA. Se dispara al crear un módulo nuevo de apps/api, agregar una capacidad de negocio, o estructurar código Java de Spring Boot con arquitectura hexagonal y Spring Modulith.
---

# Andamiaje de un módulo de CONFIA

Cada módulo de negocio es un paquete de primer nivel del módulo Maven de aplicación de `apps/api`,
con arquitectura hexagonal y organización screaming: el nombre del paquete describe una capacidad de
negocio, no una capa técnica. Ver `docs/01-arquitectura.md` sección 4, ADR-0002, ADR-0013 y
`docs/02-modelo-de-dominio.md` sección 2 para la lista de contextos delimitados existentes antes de
crear uno nuevo.

`<paquete-base>` y los nombres de los módulos Maven se fijan en F0. En esta skill son marcadores,
no nombres decididos. Los ejemplos de código son ilustrativos: los nombres de tipos concretos los
fija la especificación de cada cambio.

## 1. Árbol de archivos

Los cuatro paquetes de capa son obligatorios y la verificación de la construcción falla si falta
uno, si aparece un paquete llamado `interface` o `interfaces`, o si aparece un paquete de primer
nivel con nombre de capa técnica (`controllers`, `services`, `repositories`, `entities`, `utils`,
`helpers`, `common`). Los subpaquetes internos de cada capa son orientativos hasta que el primer
módulo de F0 los fije.

```
apps/api/app/src/main/java/<paquete-base>/<capability>/
├── package-info.java                    # module declaration and public API for Spring Modulith
├── domain/                              # no framework, no I/O
│   ├── <Aggregate>.java
│   ├── <ValueObject>.java
│   ├── <Aggregate><PastTense>.java      # domain event
│   └── <Specific>Exception.java         # domain errors, extend the kernel base error
├── application/                         # use cases, ports, transactional orchestration
│   ├── <Verb><Noun>UseCase.java
│   ├── <Verb><Noun>Command.java
│   └── port/
│       └── <Name>Repository.java        # interface declared by the consumer
├── infrastructure/                      # adapters
│   ├── persistence/<Name>Adapter.java   # implements the port with jOOQ (ADR-0015)
│   ├── jobs/                            # db-scheduler task definitions and handlers (ADR-0016)
│   └── http/<Provider>Client.java
└── web/                                 # conceptual `interface` layer
    ├── <Capability>Controller.java
    ├── dto/<Verb><Noun>Request.java     # Jakarta Bean Validation constraints
    ├── dto/<Noun>Response.java          # explicit output DTO
    ├── <Noun>Mapper.java
    └── <Capability>Permissions.java     # declared permissions of the module

apps/api/app/src/test/java/<paquete-base>/<capability>/
├── domain/<Aggregate>Test.java          # unit: JUnit + AssertJ (+ jqwik for properties)
├── application/<Verb><Noun>UseCaseTest.java
└── <Verb><Noun>IT.java                  # integration: Testcontainers + real PostgreSQL
```

La capa conceptual `interface` vive en el paquete Java `web`, porque `interface` es palabra
reservada de Java. Las pruebas unitarias se nombran `*Test.java` y las de integración `*IT.java`
(`docs/06-estrategia-de-testing.md`, sección 2).

`Money`, los identificadores y los errores de dominio base viven en el módulo Maven `kernel`
(`apps/api/kernel/src/main/java/<paquete-base>/kernel/`), que no depende de nada fuera del JDK.
Ninguna regla de negocio vive en `kernel` ni en `shared`.

Las migraciones de Flyway que crean las tablas del módulo las escribe `confia-database`. Cada tabla
lleva el prefijo del módulo propietario.

## 2. La regla de dependencia

```
interface (web)  ──depende de──>  application  ──depende de──>  domain
infrastructure   ──implementa puertos de──>  application
domain           ──no importa de nadie (solo del JDK y de kernel)
kernel           ──sin dependencias fuera del JDK
```

Verificada en cada construcción, dentro de `./mvnw verify`:

- **ArchUnit**: `domain-is-pure`, `no-cross-module-domain`, `application-no-infrastructure`,
  `interface-only-application`, `no-cycles`, `portal-module-boundary` (ADR-0002).
- **Spring Modulith**: ningún módulo accede a tipos de otro que no formen parte de su API pública
  declarada.
- **Maven**: `kernel` es una frontera de compilación; importar Spring desde él no compila.

Una violación rompe la construcción, no es una advertencia. Una excepción a una regla de ArchUnit o
de Spring Modulith exige un comentario con el número de ADR que la autoriza.

- **Ningún módulo importa el `domain` de otro módulo.** La comunicación entre `payments` y
  `ledger`, por ejemplo, ocurre por un caso de uso público de `ledger` o por un evento de dominio
  que `ledger` publica, ambos declarados como su API pública, nunca importando
  `<paquete-base>.ledger.domain.*` desde `payments`.
- `infrastructure` puede depender de `domain` (para mapear) y de `application` (para implementar
  puertos), nunca al revés.
- `web` no accede a `infrastructure` ni a `domain` directamente. Todo pasa por un caso de uso de
  `application`.

```java
// WRONG: payments imports a domain entity of ledger directly
import <paquete-base>.ledger.domain.LedgerTransaction;

// CORRECT: payments depends on the public use case that ledger exposes
// from its application layer, or reacts to a domain event ledger publishes.
import <paquete-base>.ledger.application.RecordLedgerTransactionUseCase;
```

## 3. Qué va en cada capa

### `domain`: sin framework, sin entrada ni salida

Entidades, objetos de valor, invariantes, eventos de dominio y errores de dominio. Ningún import de
`org.springframework`, `jakarta`, controladores de base de datos, clientes HTTP, clientes de Redis
ni paquetes de entrada y salida del JDK (`java.sql`, `java.net`, `java.nio.file`).

```java
// domain/Payment.java
public final class Payment {

    private final PaymentId id;
    private final StudentId studentId;
    private final Money amount;
    private PaymentStatus status;
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private Payment(PaymentId id, StudentId studentId, Money amount, PaymentStatus status) {
        this.id = id;
        this.studentId = studentId;
        this.amount = amount;
        this.status = status;
    }

    public static Payment register(StudentId studentId, Money amount) {
        if (!amount.isPositive()) {
            throw new InvalidPaymentAmountException(amount);
        }
        Payment payment = new Payment(PaymentId.generate(), studentId, amount, PaymentStatus.CONFIRMED);
        payment.domainEvents.add(new PaymentConfirmed(payment.id, payment.amount));
        return payment;
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }
}
```

### `application`: casos de uso, puertos y orquestación transaccional

Un caso de uso por clase, con verbo en el nombre. Orquesta el dominio y los puertos, nunca hace
aritmética de dinero ni contiene SQL. Ver la sección 6 para el patrón completo.

Un **puerto** es una interfaz que `application` declara e `infrastructure` implementa. Vive en
`application`, no en `infrastructure`, porque es el consumidor quien define el contrato que
necesita.

```java
// application/port/PaymentRepository.java
public interface PaymentRepository {
    Optional<Payment> findByIdempotencyKey(InstitutionId institutionId, IdempotencyKey key);
    void lockStudentAccount(StudentId studentId);
    void save(Payment payment);
}
```

### `infrastructure`: adaptadores

Implementa los puertos declarados por `application`. Aquí viven el acceso a datos, el cliente HTTP
de un proveedor externo, los trabajos en segundo plano y los mapeadores entre el modelo de
persistencia y las entidades de dominio.

**El acceso a datos es jOOQ, edición de código abierto (ADR-0015).** Sin JPA, Hibernate ni Spring
Data. Las clases de jOOQ se generan en cada construcción desde las migraciones de Flyway del módulo.
Reglas que ArchUnit verifica:

- `org.jooq` y las clases generadas solo aparecen en `infrastructure`; nada de jOOQ sale hacia
  `application` ni `domain`.
- El módulo usa solo las clases generadas de sus propias tablas (prefijo del módulo).
- `Money` se construye y se descompone solo con el convertidor compartido de `NUMERIC(14,4)` más
  moneda. `BigDecimal` nunca cruza hacia `application`.
- El adaptador nunca abre transacciones: se ejecuta dentro de la que abre el componente
  transaccional de `shared/security` (sección 6).
- SQL plano de jOOQ solo en la lista aprobada de clases de reporte.

```java
// infrastructure/persistence/PaymentAdapter.java
// Illustrative (ADR-0015). Generated table names (PAYMENTS_*) and the MoneyConverter name are
// placeholders fixed in F0.
final class PaymentAdapter implements PaymentRepository {

    private final DSLContext dsl;

    PaymentAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void lockStudentAccount(StudentId studentId) {
        // Pessimistic lock with bound parameters; runs inside the caller's transaction.
        // It waits for a concurrent payment instead of failing (ADR-0010). The wait is bounded
        // by the lock_timeout that the transaction component sets for every transaction.
        dsl.select(PAYMENTS_STUDENT_ACCOUNT.ID)
                .from(PAYMENTS_STUDENT_ACCOUNT)
                .where(PAYMENTS_STUDENT_ACCOUNT.ID.eq(studentId.value()))
                .forUpdate()
                .fetchOne();
    }

    @Override
    public void save(Payment payment) {
        // Explicit insert. The shared converter splits Money into NUMERIC(14,4) and currency.
        dsl.insertInto(PAYMENTS_PAYMENT)
                .set(PAYMENTS_PAYMENT.ID, payment.id().value())
                .set(PAYMENTS_PAYMENT.AMOUNT, MoneyConverter.amountOf(payment.amount()))
                .set(PAYMENTS_PAYMENT.CURRENCY, MoneyConverter.currencyOf(payment.amount()))
                .execute();
    }

    // findByIdempotencyKey: select with bound parameters, map the record to a Payment and build
    // its amount with MoneyConverter.toMoney(amount, currency).
}
```

**Los trabajos en segundo plano usan db-scheduler sobre PostgreSQL (ADR-0016).** Las definiciones de
tarea y sus manejadores viven en `infrastructure/jobs/`. El caso de uso programa la tarea a través
de un puerto declarado en `application`, dentro de la misma transacción que el cambio de negocio;
solo `confia-worker` la ejecuta.

```java
// infrastructure/jobs/ReceiptDeliveryJob.java
// Illustrative (ADR-0016). The task is built with Tasks.oneTime(...) and scheduled through
// SchedulerClient; exact db-scheduler calls, task names and TransactionRunner are fixed in F0.
final class ReceiptDeliveryJob implements ReceiptDelivery {   // ReceiptDelivery: application port

    // Task data carries identifiers only: never names, e-mails, amounts or national ids.
    record Data(UUID institutionId, UUID paymentId) implements Serializable {}

    @Override
    public void schedule(InstitutionId institutionId, PaymentId paymentId) {
        // Joins the caller's transaction: if the payment rolls back, the task does not exist.
    }

    // Handler, executed only in confia-worker, at least once.
    void execute(Data data) {
        transactionRunner.runAsSystem(data.institutionId(), () -> {
            // Loads what it needs inside the transaction. A unique constraint on the delivery
            // record makes a second run produce no second effect.
        });
    }
}
```

`@Scheduled`, `@EnableScheduling`, `@Async` y cualquier otra biblioteca de programación están
prohibidos. Correo, PDF y proveedores externos nunca se ejecutan dentro de la transacción financiera.

### `web`: controladores HTTP, DTO y permisos declarados

Traduce HTTP a comandos de caso de uso y el resultado a un DTO de salida explícito. Nunca contiene
lógica de negocio. Ver `confia-api-conventions` para rutas, verbos y códigos de estado.

```java
// web/PaymentsController.java
// Illustrative: annotations are Spring MVC and Spring Security; the permission mechanism is
// validated in F0 (docs/03-seguridad.md, section 5).
@RestController
@RequestMapping("/api/v1/payments")
class PaymentsController {

    private final RegisterPaymentUseCase registerPayment;

    PaymentsController(RegisterPaymentUseCase registerPayment) {
        this.registerPayment = registerPayment;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PaymentsPermissions.CREATE + "')")
    ResponseEntity<PaymentResponse> create(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RegisterPaymentRequest request) {
        Payment payment = registerPayment.handle(new RegisterPaymentCommand(
                actor,
                request.studentId(),
                Money.of(request.amount(), request.currency()),
                idempotencyKey));
        return ResponseEntity
                .created(URI.create("/api/v1/payments/" + payment.id()))
                .body(PaymentMapper.toResponse(payment));
    }
}
```

El DTO de entrada declara sus restricciones con Jakarta Bean Validation y el deserializador JSON
rechaza propiedades no declaradas (la configuración exacta se fija en F0). El DTO de salida redondea
los importes de presentación a la escala menor de la moneda llamando a la operación de `Money`
correspondiente, nunca implementando el redondeo por su cuenta.

## 4. Registro del módulo en el punto de entrada correcto

El paquete `bootstrap` contiene tres puntos de entrada sobre el mismo artefacto: administrativo,
portal y trabajador. Cada uno declara de forma explícita qué módulos carga. Ver
`docs/01-arquitectura.md` sección 5 y ADR-0003.

**Un módulo administrativo NUNCA se carga en el punto de entrada del portal.** No es una convención
de estilo: es la barrera que garantiza que el código de facturación, caja, usuarios administrativos
y auditoría completa no está siquiera cargado en memoria en el proceso expuesto a internet abierto.

Cada punto de entrada vive en su propio subpaquete (`bootstrap.admin`, `bootstrap.portal` y
`bootstrap.worker`), se declara con `@SpringBootConfiguration` + `@EnableAutoConfiguration` y **no
escanea componentes**: lo que carga lo escribe en su `@Import` (ADR-0024). Un módulo que un proceso
deba cargar expone una **configuración pública** en su paquete base, o en un paquete anotado con
`@NamedInterface` (ADR-0022), que declara sus beans de forma explícita; esa configuración se crea
cuando existe el consumidor real, nunca de forma anticipada. Registrar el módulo exige **dos
ediciones visibles en el mismo pull request**:

1. el `@Import` de esa configuración en la clase de entrada del proceso (`AdminApplication`,
   `PortalApplication` o `WorkerApplication`), y
2. el paquete del módulo en la lista de permitidos de ese proceso en `ProcessBeanPolicy`
   (`apps/api/app/src/test/java/<paquete-base>/bootstrap/`). Si el módulo es administrativo, su
   paquete debe seguir en la lista de prohibidos del portal.

Sin la segunda edición, `ProcessBeanIsolationTest` rompe la construcción: la lista de permitidos
falla cerrado. Ninguna otra clase puede referenciar una clase de entrada (regla de ArchUnit en
`BootstrapEntryPointRulesTest`). La garantía no depende solo de la estructura, sino de la prueba
obligatoria que arranca el contexto del portal y afirma que ningún bean administrativo quedó
registrado; para un controlador, en concreto:

```java
// Illustrative. Verifies ADR-0003 and ADR-0013, check 9.
@Test
void portalContextRegistersNoAdministrativeController() {
    Set<String> controllerPackages = handlerMapping.getHandlerMethods().values().stream()
            .map(method -> method.getBeanType().getPackageName())
            .collect(Collectors.toSet());

    assertThat(controllerPackages)
            .noneMatch(name -> name.contains(".invoicing.")
                    || name.contains(".cashbox.")
                    || name.contains(".identity."));
}
```

Al crear un módulo nuevo, decide explícitamente: ¿es administrativo, es de portal, o expone una
porción de solo lectura a ambos? Un módulo que sirve a ambos procesos separa su superficie en
controladores distintos con permisos distintos, nunca un único controlador que decide en tiempo de
ejecución qué exponer según el proceso.

## 5. Declaración de permisos del módulo

Cada módulo declara su tabla de permisos en `web/<Capability>Permissions.java`, con el patrón
`modulo:verbo` de `docs/03-seguridad.md` sección 5.1. Los verbos válidos son exactamente `create`,
`read`, `update`, `void`, `approve`, `export`. Ninguno más.

```java
// web/PaymentsPermissions.java
public final class PaymentsPermissions {
    public static final String CREATE = "payments:create";
    public static final String READ = "payments:read";
    public static final String VOID = "payments:void";
    public static final String EXPORT = "payments:export";

    private PaymentsPermissions() {}
}
```

El arranque de la aplicación falla si encuentra un endpoint sin autorización declarada ni marca
explícita de ruta pública. No agregues una ruta sin su autorización confiando en agregarla después.

## 6. Patrón de caso de uso con su transacción

```java
// application/RegisterPaymentUseCase.java
// Illustrative. TransactionRunner, TxIsolation and the run signature are placeholders for the
// single transaction component of shared/security, fixed in F0 (ADR-0015). It opens the
// transaction, sets the row-level security context as its first statement, applies the isolation
// level and retries on serialization failure or deadlock. No @Transactional anywhere else.
public class RegisterPaymentUseCase {

    private final TransactionRunner transactions;
    private final PaymentRepository payments;
    private final AuditLog audit;

    public RegisterPaymentUseCase(TransactionRunner transactions, PaymentRepository payments,
            AuditLog audit) {
        this.transactions = transactions;
        this.payments = payments;
        this.audit = audit;
    }

    public Payment handle(RegisterPaymentCommand command) {
        // The body may run more than once on retry: no side effects outside the transaction.
        return transactions.run(TxIsolation.READ_COMMITTED, () -> {
            // 1. Idempotency first.
            Optional<Payment> existing = payments.findByIdempotencyKey(
                    command.actor().institutionId(), command.idempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }

            // 2. Lock the affected account (SELECT ... FOR UPDATE).
            payments.lockStudentAccount(command.studentId());

            // 3. The domain decides, the use case orchestrates.
            Payment payment = Payment.register(command.studentId(), command.amount());

            // 4. Atomic persistence.
            payments.save(payment);

            // 5. Audit in the same transaction. See confia-audit-logging.
            audit.append(AuditEvent.paymentRegistered(payment, command.actor()));

            return payment;
        });
    }
}
```

Este patrón replica el de `confia-ledger-invariants` porque toda escritura financiera del sistema
sigue la misma forma: idempotencia, bloqueo, dominio, persistencia, auditoría, en ese orden y dentro
de una única transacción. Cierre de caja y emisión de correlativo fiscal piden al componente
transaccional aislamiento `SERIALIZABLE`; el reintento ante error de serialización lo hace el propio
componente (ADR-0010, ADR-0015), nunca el caso de uso ni el repositorio.

## 7. Mapeo de errores de dominio a Problem Details

Los errores de dominio se lanzan como excepciones propias en `domain`, que extienden el error de
dominio base de `kernel`, nunca como cadenas ni como códigos HTTP directos. Un único traductor
global en un componente transversal compartido los convierte en Problem Details (RFC 9457). Ver
`confia-api-conventions`.

```java
// domain/InvalidPaymentAmountException.java
public final class InvalidPaymentAmountException extends DomainException {
    public InvalidPaymentAmountException(Money amount) {
        super("PAYMENT_INVALID_AMOUNT", "Payment amount must be positive, received " + amount);
    }
}
```

```java
// Illustrative: one global translator, registered once. Location fixed in F0.
@RestControllerAdvice
class DomainExceptionTranslator {

    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.of(
            "PAYMENT_INVALID_AMOUNT", HttpStatus.UNPROCESSABLE_ENTITY,
            "OVERPAYMENT_NOT_ALLOWED", HttpStatus.CONFLICT);

    @ExceptionHandler(DomainException.class)
    ProblemDetail translate(DomainException exception) {
        HttpStatus status = STATUS_BY_CODE.getOrDefault(exception.code(), HttpStatus.UNPROCESSABLE_ENTITY);
        return ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        // type, title, instance and traceId are filled in by the same translator.
    }
}
```

Un error de dominio nunca se captura en `web` para devolver un mensaje genérico distinto del que
declara: el traductor global es el único, así todo el sistema responde con la misma forma de
Problem Details.

## 8. Archivos que SIEMPRE se crean junto al módulo

Al crear un módulo nuevo, ninguno de estos archivos queda pendiente para "después":

- [ ] Los cuatro paquetes `domain`, `application`, `infrastructure` y `web`.
- [ ] `package-info.java` con la declaración del módulo y de su API pública para Spring Modulith.
- [ ] Al menos una entidad de dominio con su prueba unitaria `*Test.java` cubriendo sus
      invariantes.
- [ ] Al menos un caso de uso con su prueba unitaria `*Test.java` usando dobles de prueba para los
      puertos.
- [ ] Una prueba de integración `*IT.java` con Testcontainers para todo caso de uso que escriba en
      base de datos, cubriendo al menos idempotencia y, si aplica, cuadre o concurrencia.
- [ ] `<Capability>Permissions.java` con la tabla de permisos del módulo.
- [ ] El registro del módulo en el punto de entrada correcto (administrativo, portal, trabajador o
      más de uno, de forma explícita).
- [ ] DTO de entrada con restricciones de Bean Validation y DTO de salida explícito para cada
      endpoint.
- [ ] OpenAPI regenerado, diferencia declarada contra la instantánea y `packages/contracts`
      regenerado con orval.
- [ ] `./mvnw verify` en verde, incluidas ArchUnit y Spring Modulith, sin exclusiones nuevas sin ADR.

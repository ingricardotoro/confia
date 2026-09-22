# CONFIA — Observabilidad y operaciones

> Estado: **Propuesta v1.0**.
> Subordinado a `docs/01-arquitectura.md`, que es la fuente de verdad de la arquitectura.
> Si algo aquí lo contradice, gana el documento de arquitectura.

Este documento define cómo se observa y se opera un sistema financiero mantenido por **un solo
desarrollador**. Ese detalle condiciona todo lo que sigue: la observabilidad no existe para tener
tableros bonitos, existe para responder tres preguntas a las dos de la mañana.

1. ¿El sistema está atendiendo solicitudes?
2. ¿El dinero registrado hoy cuadra?
3. ¿Qué pasó exactamente en la solicitud que el usuario reclama?

Todo lo que no ayuda a responder alguna de esas tres preguntas es ruido, y el ruido en un equipo
de una persona no es un inconveniente estético: es la causa por la que se ignora la alerta que sí
importaba.

**Regla de accionabilidad.** Toda alerta de este documento declara qué se hace cuando suena. Una
alerta sin acción definida no se implementa, y una alerta que nadie atendió durante dos ciclos se
elimina o se corrige, nunca se silencia de forma indefinida. Ver sección 7.4.

---

## 1. Los tres pilares aplicados a este sistema

| Pilar | Herramienta | Pregunta que responde | Retención |
|---|---|---|---|
| **Logs estructurados** | Registro estructurado nativo de Spring Boot (JSON a `stdout`) | Qué ocurrió en esta solicitud concreta | 30 días en caliente, 1 año en frío |
| **Métricas** | Prometheus, visualizadas en Grafana | Cómo se comporta el sistema en conjunto y a lo largo del tiempo | 15 días de alta resolución, 13 meses agregado |
| **Trazas** | OpenTelemetry con colector propio | Dónde se fue el tiempo dentro de una operación lenta | 7 días, muestreo del 10 por ciento más el 100 por ciento de errores |

Los tres pilares están unidos por un identificador común. Un log, una métrica de ejemplar y una
traza de la misma operación comparten `request_id` y `trace_id`. Sin esa unión, tres herramientas
no son observabilidad: son tres archivos que hay que correlacionar a mano.

### 1.1 Dónde vive el código

```
apps/api/app/src/main/java/<paquete-base>/shared/observability/
├── logging/          # configuración del registro estructurado JSON y lista de redacción
├── metrics/          # adaptadores de métricas técnicas y de negocio hacia Prometheus
├── tracing/          # configuración de OpenTelemetry y propagación de contexto
├── context/          # contexto de solicitud en el MDC del registro
└── health/           # indicadores de salud de Spring Boot Actuator
```

`<paquete-base>` es un marcador del paquete base de Java, que se fija en F0
(`docs/01-arquitectura.md`, sección 4). Las métricas se exponen con Spring Boot Actuator y su
registro de Prometheus; el mecanismo concreto de instrumentación de OpenTelemetry para la JVM se
fija en F0.

`shared/observability` es infraestructura transversal. **Ningún módulo de negocio configura el
registro ni importa directamente las bibliotecas de métricas, Prometheus ni OpenTelemetry.** Los módulos publican eventos de dominio y emiten
métricas de negocio a través de un puerto declarado en `application`, cuyo adaptador vive en
`shared/observability`. Esa disciplina permite cambiar de proveedor sin tocar `domain`.

### 1.2 Contenedores de observabilidad

Coherente con `docs/01-arquitectura.md` sección 9, la observabilidad corre contenerizada, en la
red privada y sin puerto público:

| Servicio | Imagen | Exposición |
|---|---|---|
| `prometheus` | Imagen oficial fijada por digest | Solo red privada |
| `grafana` | Imagen oficial fijada por digest | Detrás del subdominio administrativo, con MFA |
| `otel-collector` | Colector de OpenTelemetry, fijado por digest | Solo red privada |
| `loki` o equivalente de agregación de logs | Fase posterior, opcional | Solo red privada |

Mientras no exista agregador de logs, los logs se recogen del controlador de registro de Docker,
se rotan y se envían cifrados al almacenamiento de objetos. Es suficiente para una institución y
evita un componente más que mantener.

---

## 2. Logs estructurados en JSON

### 2.1 Principios

1. **Un log es un evento con campos, no una frase.** Nunca se concatena información dentro del
   mensaje: el mensaje es una constante corta y todo lo variable son campos.
2. **JSON a `stdout`, siempre.** El contenedor no escribe archivos de log. La recolección es
   responsabilidad del entorno, no de la aplicación.
3. **Nada de datos personales.** Ver 2.2. La redacción es una lista explícita aplicada por la
   biblioteca, no una recomendación al programador.
4. **Todo log lleva el contexto de solicitud** de forma automática, no manual. Ver sección 3.
5. **El log no sustituye a la bitácora de auditoría.** Los logs se rotan y se pierden; la
   auditoría es una tabla de solo inserción encadenada por hash. Ver `docs/03-seguridad.md`
   sección 12. Si un auditor externo puede preguntarlo, va a `shared_audit_log`, no al log.

```java
// WRONG: information inside the message, impossible to filter, and it carries personal data
log.info("Payment of L 1250.15 registered for Maria Lopez (0801-2010-01234)");

// RIGHT: constant message, structured fields, identifiers only (SLF4J fluent API)
log.atInfo()
        .setMessage("payment.registered")
        .addKeyValue("paymentId", paymentId)
        .addKeyValue("studentAccountId", studentAccountId)
        .addKeyValue("amount", "1250.1500")
        .addKeyValue("currency", "HNL")
        .addKeyValue("method", "cash")
        .log();
```

Sobre el importe: los logs registran el importe como **cadena decimal** junto con su moneda, con
la misma forma que la API. Nunca como número JSON, porque el consumidor del log lo reconstruiría
como coma flotante y un log de un importe corrompido es peor que no tener log. Ver
`.claude/skills/confia-money-rules/SKILL.md` sección 9.

### 2.2 Qué se registra y qué está prohibido registrar

| Se registra siempre | Está prohibido registrar |
|---|---|
| `request_id`, `trace_id`, `span_id` | Contraseñas en claro o hasheadas |
| `actor_id` (UUID), `actor_kind` (`staff`, `guardian`, `system`) | Tokens de acceso, de refresco, de recuperación y cabeceras de autorización |
| `institution_id` | Cookies de sesión |
| Método, ruta con parámetros normalizados, código de estado, duración | Secretos TOTP, códigos TOTP y códigos de recuperación |
| Identificadores de entidad (`payment_id`, `charge_id`, `invoice_id`) | Número de documento de identidad de estudiantes y encargados (`nationalId`) |
| Importes como cadena decimal, con moneda | Nombres, apellidos, fecha de nacimiento y dirección de estudiantes y encargados |
| Nombre e instancia de la tarea en segundo plano, intento y resultado | Correo electrónico y teléfono en claro |
| Códigos de error de dominio | Cualquier dato de tarjeta: PAN, CVV, vencimiento, nombre del tarjetahabiente |
| Nombre y versión del despliegue, digest de la imagen | Firma de webhook y secretos compartidos |
| Resultado de la autorización (`granted`, `denied`) y permiso evaluado | Cuerpo completo de una solicitud o respuesta con datos personales |
| Consultas lentas con la sentencia parametrizada, sin valores | Valores vinculados de una consulta SQL |

Regla práctica: **si el dato sirve para identificar a una persona fuera del sistema, no va al
log.** El identificador interno UUIDv7 sí, porque para resolverlo hay que consultar la base de
datos, y esa consulta queda auditada.

Cuando se necesita correlacionar un destinatario de notificación sin exponerlo, se registra
`recipient_hash`, el mismo campo que ya usa `NotificationLog` en `docs/02-modelo-de-dominio.md`.

### 2.3 Configuración de redacción

La lista de redacción es un dato explícito del código, no una recomendación al programador. Se
aplica en el codificador del registro estructurado, dentro de `shared/observability`, de modo que
un campo listado nunca llega a `stdout` aunque alguien registre un objeto completo por accidente.
El punto exacto de enganche con el registro estructurado de Spring Boot se valida en F0; la lista
y sus reglas no dependen de ese mecanismo.

```java
// apps/api/app/src/main/java/<paquete-base>/shared/observability/logging/RedactedFields.java

/**
 * Explicit redaction list, applied by the structured log encoder.
 *
 * Rules:
 * - Field names match at any nesting level, so nested payloads are covered.
 * - The list is additive only. Removing a name requires a security review.
 * - Covered by a unit test that logs a fixture containing every sensitive field
 *   and asserts none of the raw values appear in the emitted line.
 * - Redacted values are replaced by "[REDACTED]"; the key is kept so its presence is auditable.
 */
public final class RedactedFields {

    private RedactedFields() {}

    /** HTTP headers, matched case insensitively. */
    public static final Set<String> HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-csrf-token", "x-webhook-signature");

    public static final Set<String> FIELDS = Set.of(
            // Credentials and session material
            "password", "currentPassword", "newPassword", "passwordHash", "passwordConfirmation",
            "token", "accessToken", "refreshToken", "resetToken", "activationCode",

            // Multi factor authentication
            "mfaSecret", "totpSecret", "totpCode", "recoveryCode", "recoveryCodes",

            // National identifiers and tax identifiers of natural persons
            "nationalId", "nationalIdEncrypted", "customerRtn",

            // Personal data of minors and guardians
            "firstName", "lastName", "fullName", "birthDate", "address", "email", "phone",
            "customerName",

            // Card data. The system must never receive these fields (hosted fields, SAQ A),
            // so a hit here is also an alert condition, not only a redaction.
            "cardNumber", "pan", "cvv", "cvc", "expiryMonth", "expiryYear", "cardholderName",

            // Provider secrets and signatures
            "webhookSignature", "signature", "apiKey", "clientSecret");
}
```

Reglas del formato de cada línea, independientes del mecanismo:

- **Formato.** JSON estructurado nativo de Spring Boot. El formato concreto (por ejemplo, ECS o
  Logstash) se elige en F0.
- **Identidad del despliegue.** Toda línea lleva `service` (`confia-api-admin`,
  `confia-api-portal` o `confia-worker`), `version`, `imageDigest` y el perfil de entorno.
- **Correlación.** Toda línea emitida dentro de una solicitud o de un trabajo lleva `requestId`,
  `correlationId`, `traceId`, `spanId`, `actorId`, `actorKind`, `institutionId` y, en trabajos,
  `jobId`, tomados del contexto de la sección 3.
- **Solicitud y respuesta por lista blanca.** De la solicitud se registran método, ruta
  normalizada sin cadena de consulta (puede llevar filtros con datos personales) y un hash del
  agente de usuario; de la respuesta, el código de estado y la duración. Nada más.
- **Errores.** Tipo, código, tipo de problema y mensaje. La traza de pila se conserva en el log
  fuera de producción y **nunca** se devuelve al cliente (RFC 9457).
- **Nivel por defecto** `info`, configurable por variable de entorno.

**Cómo se comprueba.** Prueba unitaria obligatoria en `shared/observability`: se registra un objeto
que contiene los valores literales `"SECRETO_PRUEBA_NACIONAL_ID"`, `"SECRETO_PRUEBA_TOKEN"` y
similares en cada campo prohibido, se captura la salida del transporte y se afirma que ninguno de
esos literales aparece. La prueba forma parte de la puerta de calidad, no de la buena voluntad.

Complemento en integración continua: una regla de Semgrep que rechaza `System.out`,
`System.err`, `printStackTrace` y la configuración directa del registro fuera de
`shared/observability`.

### 2.4 Niveles de log

| Nivel | Cuándo se usa | Ejemplos concretos de este sistema | Destino |
|---|---|---|---|
| `trace` | Detalle interno de un algoritmo. **Nunca activo en producción** | Cada paso del asignador de imputación de un pago, comparación de cada cargo pendiente | Solo local |
| `debug` | Diagnóstico de una funcionalidad concreta. Activable de forma temporal por servicio | Sentencia SQL parametrizada sin valores, decisión del motor de mora antes de aplicar la exención por beca | Local y preproducción |
| `info` | Hecho de negocio esperado que ocurrió. Es el nivel por defecto en producción | `payment.registered`, `invoice.issued`, `cash_session.opened`, `charge_generation.completed`, `ledger.transaction.recorded` | Producción |
| `warn` | Situación anómala que el sistema resolvió por sí mismo, o umbral que se acerca a un límite | Rango CAI al 80 por ciento consumido, reintento de un trabajo de notificación, degradación de la consulta a la lista de contraseñas comprometidas, bloqueo por retroceso exponencial, cierre de caja con diferencia dentro de la tolerancia | Producción |
| `error` | La operación del usuario falló y alguien debe mirarlo. El sistema sigue en pie | Fallo al emitir el documento fiscal tras obtener el correlativo, webhook con firma inválida, tarea en segundo plano que agotó sus intentos y quedó fallida, pago rechazado por la pasarela con error del proveedor | Producción, con alerta agregada por tasa |
| `fatal` | El proceso no puede continuar y se detiene | No se pudo conectar a PostgreSQL al arrancar, migración pendiente incompatible, secreto obligatorio ausente, verificación de la cadena de auditoría rota al iniciar | Producción, con alerta inmediata |

Criterios de desempate que evitan la degradación clásica del nivel de log:

- Un error del usuario, como validación fallida en el borde o permiso denegado, es `info` con
  `outcome: "denied"`. **No es `error`.** Si la validación de entrada generara `error`, un
  escáner automático llenaría el canal de alertas en una tarde.
- Un descuadre del libro mayor es siempre `error`, aunque el importe sea de un centavo. Ver
  `.claude/skills/confia-ledger-invariants/SKILL.md` sección 8: se detecta, se escala, no se
  corrige.
- Un fallo de facturación posterior a la asignación del correlativo es `error` con campo
  `fiscalGap: true`, porque produce un hueco en la numeración que debe justificarse ante la SAR.

---

## 3. Correlación de solicitudes

Un padre reclama que pagó y el sistema no lo refleja. La respuesta tiene que reconstruirse en
minutos, atravesando la petición HTTP, la tarea en segundo plano que envió la notificación y el proceso
nocturno que verificó la integridad. Eso exige que los tres compartan identificador.

### 3.1 Identificadores

| Campo | Origen | Alcance | Uso |
|---|---|---|---|
| `requestId` | Generado en el borde como UUIDv7 si el cliente no lo envía | Una solicitud HTTP | Correlación de logs y auditoría. Se persiste en `shared_audit_log.request_id` |
| `correlationId` | Igual al `requestId` de la solicitud que originó la cadena | Toda la cadena, incluidos trabajos derivados y trabajos de trabajos | Reconstrucción del flujo completo de negocio |
| `traceId` y `spanId` | OpenTelemetry, propagados con la cabecera `traceparent` (W3C Trace Context) | Traza distribuida | Análisis de latencia |
| `jobId` | db-scheduler: nombre e instancia de la tarea (ADR-0016) | Ejecución de una tarea | Diagnóstico de tareas atascadas o fallidas |
| `idempotencyKey` | Cabecera `Idempotency-Key` del cliente | Operación financiera | Detección de reintentos y de doble cobro |

`requestId` no se acepta del cliente sin validar. Si llega en la cabecera `X-Request-Id`, se
verifica que sea un UUID; si no lo es, se descarta y se genera uno nuevo. Un identificador
controlado por el atacante podría usarse para inyectar contenido en los logs.

### 3.2 Propagación en HTTP

El contexto de solicitud vive en el MDC del registro, de modo que toda línea emitida durante la
solicitud lo lleva sin que ningún caso de uso lo pase a mano. `traceId` y `spanId` los agrega la
integración de OpenTelemetry. `actorId`, `actorKind` e `institutionId` se agregan una vez que
Spring Security autenticó al actor. Cuando el trabajo salta de hilo (ejecución asíncrona), el
contexto se propaga de forma explícita; el mecanismo de propagación se valida en F0.

```java
// apps/api/app/src/main/java/<paquete-base>/shared/observability/context/RequestContextFilter.java

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestContextFilter extends OncePerRequestFilter {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    // UUIDv7 generator from the kernel module. Its final name is fixed in F0.
    private final IdentifierGenerator identifiers;

    RequestContextFilter(IdentifierGenerator identifiers) {
        this.identifiers = identifiers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader("X-Request-Id");
        String requestId = incoming != null && UUID_PATTERN.matcher(incoming).matches()
                ? incoming
                : identifiers.newUuidV7().toString();

        // The client can correlate its own report with the server log.
        response.setHeader("X-Request-Id", requestId);

        MDC.put("requestId", requestId);
        MDC.put("correlationId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Pooled threads must never carry this context into the next request.
            MDC.clear();
        }
    }
}
```

El mismo contexto alimenta el `set_config('app.request_id', ...)` del middleware transaccional
descrito en `docs/03-seguridad.md` sección 6.2, de modo que el `request_id` termina persistido en
`shared_audit_log` sin que ningún caso de uso tenga que pasarlo a mano.

### 3.3 Propagación hacia los trabajos en segundo plano

Los trabajos en segundo plano usan db-scheduler sobre PostgreSQL (ADR-0016). Lo que sigue es el
contrato de correlación de sus tareas.

Los datos de una tarea **solo llevan identificadores** (institución, entidad, clave de
idempotencia), nunca importes ni datos personales (`docs/03-seguridad.md` sección 2.4). El
contexto de correlación viaja como metadatos declarados dentro de esos datos.

```java
// apps/api/app/src/main/java/<paquete-base>/shared/observability/context/TracedJob.java
// Envelope of the db-scheduler task data (ADR-0016). Final shape and serialization fixed in F0.

public record TracedJob<T>(T payload, JobMeta meta) {

    /** Every field except actorId and traceparent is mandatory. */
    public record JobMeta(
            String correlationId,
            String requestId,
            String actorId,        // originating actor; null for system actors
            ActorKind actorKind,   // originating actor kind: STAFF, GUARDIAN or SYSTEM
            UUID institutionId,
            String traceparent) {} // W3C Trace Context carrier
}
```

Reglas del contrato:

- **Quien programa.** Nunca programa una tarea sin `meta`, y solo lo hace desde un caso de uso,
  dentro de la transacción del negocio. Toma `correlationId`, `requestId`, actor e institución del
  contexto de la solicitud en curso, y el `traceparent` del contexto de trazas activo. Una tarea
  programada sin solicitud de origen usa actor de sistema explícito.
- **Manejador en `confia-worker`.** Antes de ejecutar, restaura el contexto de trazas desde
  `traceparent` y coloca en el MDC los campos de `meta` más `jobId`. El actor de `meta` se registra
  para correlación y auditoría, pero el manejador siempre ejecuta con actor `system` y el contexto
  de la institución de la tarea (ADR-0016, regla 4). Al terminar, limpia el MDC.

**Cómo se comprueba.** Prueba de lista aprobada de campos por tipo de tarea: una tarea sin `meta` o
con un `payload` que contenga campos no permitidos falla la validación de quien programa. Prueba de
integración que programa una tarea desde una petición HTTP y verifica que el log del trabajador
contiene el mismo `correlationId` que el log de la petición.

### 3.4 Propagación en trabajos programados

Una tarea recurrente (generación de cargos, integridad nocturna del libro mayor, purga de tokens de
refresco, vigilancia del rango CAI) se declara en código con expresión de calendario y zona horaria
`America/Tegucigalpa` (ADR-0016) y no nace de una solicitud HTTP. Se le asigna un `correlationId` propio al inicio de la ejecución, con actor de
sistema explícito, y todos los trabajos hijos lo heredan.

```java
// The scheduled run owns the correlation for its whole fan-out.
UUID runId = identifiers.newUuidV7();
MDC.put("requestId", runId.toString());
MDC.put("correlationId", runId.toString());
MDC.put("actorKind", "system");
MDC.put("institutionId", institutionId.toString());
try {
    chargeGeneration.execute(new GenerateChargesCommand(institutionId, periodKey, runId));
} finally {
    MDC.clear();
}
```

Ese `runId` es el mismo `ChargeGenerationRun.id` del modelo de dominio, de modo que un cargo
generado automáticamente puede rastrearse hasta la ejecución exacta que lo creó. Eso responde la
pregunta "¿de dónde salió este cargo?" sin depender de logs que ya se rotaron.

---

## 4. Trazas con OpenTelemetry

- Instrumentación de HTTP, del acceso a PostgreSQL por JDBC, de Redis, de la ejecución de tareas
  de db-scheduler y del cliente de correo. El mecanismo de instrumentación de OpenTelemetry para la
  JVM (agente de Java o integración de Spring Boot) se fija en F0.
- Exportación por OTLP hacia el colector en la red privada. La aplicación nunca habla directo con
  un proveedor externo.
- **Muestreo**: 10 por ciento de las trazas normales, 100 por ciento de las trazas con error, 100
  por ciento de las operaciones financieras de escritura (registro de pago, emisión fiscal, cierre
  de caja). El costo de trazar el dinero completo es pequeño y su valor diagnóstico es alto.
- Los atributos de tramo siguen la misma lista de redacción de la sección 2.3. Un atributo con
  nombre de estudiante en una traza es exactamente igual de grave que en un log.
- Tramos manuales obligatorios, con nombre en inglés: `ledger.record_transaction`,
  `payment.allocate`, `invoicing.assign_sequence`, `cashbox.close_session`,
  `charges.generate_period`.

Regla de correlación: el `trace_id` se copia en `shared_audit_log.trace_id`, columna ya prevista en
`docs/03-seguridad.md` sección 12.1.

---

## 5. Métricas

### 5.1 Convenciones

- Prefijo `confia_` en toda métrica propia. Nombres en inglés, en `snake_case`, con la unidad como
  sufijo (`_seconds`, `_bytes`, `_total`, `_percent`, `_minor_units`).
- Etiquetas de baja cardinalidad. **Prohibido** usar como etiqueta un identificador de estudiante,
  de encargado, de pago o de factura: eso hace explotar la cardinalidad de Prometheus y además
  convierte las métricas en un almacén de datos personales.
- Etiquetas comunes disponibles en casi toda métrica: `service`, `env`, `institution_id`.
  `institution_id` se mantiene desde el inicio por la decisión de multi-institución
  (`docs/01-arquitectura.md` sección 10).
- El endpoint de exposición es `/internal/metrics`, **no público**, accesible solo desde la red
  privada y nunca montado en el proceso del portal hacia internet.

> **Las métricas monetarias no son fuente de verdad financiera.** Prometheus almacena valores como
> coma flotante de doble precisión, y este sistema prohíbe la coma flotante para dinero. Por eso
> todo importe expuesto como métrica se publica en **unidades menores como entero** (centavos),
> obtenido de `Money` mediante una operación del módulo de núcleo y nunca con aritmética propia
> del adaptador de métricas, lo
> que un `float64` representa de forma exacta hasta 2^53, es decir del orden de noventa mil
> millones de lempiras. Sirve para tableros y alertas. **Todo reporte, cierre o conciliación se
> genera desde el libro mayor con `NUMERIC(14,4)`**, nunca desde una métrica. Ver
> `.claude/skills/confia-money-rules/SKILL.md`.

### 5.2 Métricas técnicas

| Nombre | Tipo | Etiquetas | Para qué sirve |
|---|---|---|---|
| `http_server_request_duration_seconds` | Histogram | `service`, `method`, `route`, `status_class` | Latencia por endpoint. Base de los SLO de latencia. Cubetas: 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10 |
| `http_server_requests_total` | Counter | `service`, `method`, `route`, `status_code` | Tasa de solicitudes y tasa de error |
| `http_server_active_requests` | Gauge | `service` | Detección de saturación y de solicitudes colgadas |
| `confia_db_pool_connections` | Gauge | `service`, `state` (`idle`, `active`, `waiting`) | Agotamiento del pool antes de que se convierta en caída |
| `confia_db_query_duration_seconds` | Histogram | `service`, `operation`, `repository` | Consultas lentas por repositorio, sin exponer valores |
| `confia_db_transaction_retries_total` | Counter | `service`, `reason` (`serialization_failure`, `deadlock`, `lock_timeout`) | Contención en cierre de caja y emisión de correlativo |
| `confia_queue_depth` | Gauge | `task_name`, `state` (`scheduled`, `due`, `running`, `failed`) | Tareas de db-scheduler por tipo y estado, calculadas consultando la tabla de tareas (ADR-0016) desde el componente de observabilidad de `shared`, con una consulta de solo lectura de la lista aprobada de SQL plano (ADR-0015, regla 9; ADR-0017, regla 6). La representación exacta del estado fallido se valida en F0 |
| `confia_queue_oldest_job_age_seconds` | Gauge | `task_name` | Antigüedad de la tarea vencida más antigua que nadie ha tomado, calculada sobre la tabla de tareas. Detecta cola atascada mejor que la cantidad |
| `confia_job_duration_seconds` | Histogram | `task_name`, `outcome` | Duración y resultado de cada ejecución, emitida por el envoltorio común de los manejadores |
| `confia_job_failures_total` | Counter | `task_name`, `error_code` | Tareas que agotaron sus intentos y quedaron fallidas |
| `confia_redis_up` | Gauge | `service` | Disponibilidad de Redis para caché y límite de tasa |
| `confia_backup_last_success_timestamp_seconds` | Gauge | `backup_type` (`dump`) | Antigüedad del último respaldo lógico fuera de sitio correcto, publicada por `infra/scripts/backup.sh` (ADR-0014). Sostiene el objetivo de punto de recuperación ante la pérdida total de la cuenta de AWS |
| `confia_backup_duration_seconds` | Histogram | `backup_type` | Degradación del proceso de respaldo lógico fuera de sitio |
| `confia_rds_backup_enabled` | Gauge (0/1) | - | Respaldos automáticos de RDS habilitados. Recolectada del estado de RDS, no del proceso de aplicación |
| `confia_rds_restorable_time_lag_seconds` | Gauge | - | Retraso entre el instante actual y el último punto restaurable de RDS (`LatestRestorableTime`). Determina el objetivo de punto de recuperación real ante fallos normales dentro de AWS |
| `confia_tls_certificate_expiry_timestamp_seconds` | Gauge | `domain` | Vencimiento del certificado |
| `confia_build_info` | Gauge (valor 1) | `version`, `image_digest`, `commit` | Saber qué artefacto exacto está corriendo |
| `confia_rate_limit_rejections_total` | Counter | `service`, `route`, `scope` (`ip`, `account`) | Abuso y ajuste de umbrales |
| `confia_auth_failed_attempts_total` | Counter | `service`, `actor_kind`, `reason` | Detección de relleno de credenciales |
| `confia_log_redaction_hits_total` | Counter | `field_class` (`card`, `credential`, `national_id`) | Si sube, alguien está intentando registrar datos prohibidos |

### 5.3 Métricas de negocio

Estas son las que justifican el sistema ante la dirección y las que detectan un problema
financiero antes que un reclamo.

| Nombre | Tipo | Etiquetas | Para qué sirve |
|---|---|---|---|
| `confia_daily_collection_minor_units` | Gauge | `institution_id`, `currency`, `payment_method` | **Recaudación del día.** Se recalcula desde el libro mayor cada 5 minutos. Panel principal del tablero financiero |
| `confia_payments_registered_total` | Counter | `institution_id`, `payment_method`, `channel` (`cashbox`, `portal`, `gateway`, `bank_transfer`) | **Pagos registrados por hora** mediante `rate()`. Detecta caída de operación en horario de cobro |
| `confia_payment_amount_minor_units` | Histogram | `institution_id`, `currency`, `payment_method` | Distribución del importe de pago. Detecta un pago atípico por magnitud |
| `confia_cai_range_consumed_percent` | Gauge | `institution_id`, `issuance_point`, `document_type`, `cai` | **Porcentaje de rango CAI consumido.** Dispara el runbook antes de quedarse sin capacidad de facturar |
| `confia_cai_range_remaining_numbers` | Gauge | `institution_id`, `issuance_point`, `document_type` | Correlativos restantes en términos absolutos, que es lo que se le pide al contador |
| `confia_cai_range_days_to_expiry` | Gauge | `institution_id`, `issuance_point`, `document_type` | Días hasta la fecha límite de emisión. Un rango puede vencer con numeración disponible |
| `confia_ledger_unbalanced_transactions` | Gauge | `institution_id` | **Transacciones del libro mayor descuadradas.** Valor esperado permanente: 0. Sin tolerancia |
| `confia_ledger_cached_balance_drift_accounts` | Gauge | `institution_id` | Cuentas donde el saldo cacheado difiere del recalculado. Valor esperado: 0 |
| `confia_ledger_over_allocated_charges` | Gauge | `institution_id` | Cargos con imputación superior a su importe. Valor esperado: 0 |
| `confia_ledger_double_reversal_transactions` | Gauge | `institution_id` | Transacciones con más de un reverso. Valor esperado: 0 |
| `confia_ledger_transactions_total` | Counter | `institution_id`, `transaction_type` | Volumen por tipo de asiento. Un pico de `adjustment` o `write_off` es señal de control interno |
| `confia_cashbox_closures_total` | Counter | `institution_id`, `outcome` (`balanced`, `shortage`, `overage`) | **Cierres de caja con diferencia.** Base de la alerta y del indicador por cajero |
| `confia_cashbox_difference_minor_units` | Histogram | `institution_id`, `currency`, `direction` (`shortage`, `overage`) | Magnitud de las diferencias de caja a lo largo del tiempo |
| `confia_cashbox_open_sessions` | Gauge | `institution_id` | Sesiones de caja abiertas. Una sesión abierta fuera de horario es una anomalía |
| `confia_notifications_failed_total` | Counter | `institution_id`, `channel` (`email`, `whatsapp`, `sms`), `error_class` | **Notificaciones fallidas.** Sin notificación no se puede aplicar mora con respaldo |
| `confia_notifications_sent_total` | Counter | `institution_id`, `channel`, `status` (`sent`, `delivered`, `bounced`, `suppressed`) | Tasa de entrega efectiva por canal |
| `confia_charges_generated_total` | Counter | `institution_id`, `origin` (`automatic`, `manual`) | Verificación de que el devengo del período corrió completo |
| `confia_charge_generation_last_success_timestamp_seconds` | Gauge | `institution_id`, `charge_plan` | Si el devengo del mes no corrió, esto lo revela antes que el reclamo de cartera |
| `confia_outstanding_balance_minor_units` | Gauge | `institution_id`, `currency`, `aging_bucket` (`current`, `1_30`, `31_60`, `61_90`, `over_90`) | Antigüedad de la cartera. Reporte que pide la dirección |
| `confia_invoices_issued_total` | Counter | `institution_id`, `document_type` | Volumen de emisión fiscal |
| `confia_invoices_voided_total` | Counter | `institution_id`, `document_type` | Anulaciones. Un aumento sostenido es indicio de control interno, ver `docs/03-seguridad.md` 2.7 actor 2 |
| `confia_fiscal_sequence_gaps` | Gauge | `institution_id`, `issuance_point`, `document_type` | Huecos en el correlativo sin anulación registrada. Valor esperado: 0 |
| `confia_audit_chain_verified_rows` | Gauge | `institution_id`, `scope` (`daily`, `weekly`) | Filas verificadas en la última corrida de verificación de cadena |
| `confia_audit_chain_broken` | Gauge | `institution_id` | Cadena de auditoría rota. Valor esperado: 0. Dispara incidente S1 |
| `confia_data_retention_pending_records` | Gauge | `category` | Registros vencidos según la política de retención y aún no tratados. Ver `docs/08-datos-privacidad-y-retencion.md` |
| `confia_dsr_open_requests` | Gauge | `type` (`access`, `rectification`, `portability`, `erasure`, `objection`) | Solicitudes de derechos del titular abiertas y su antigüedad |

### 5.4 Cómo se emiten sin romper la regla de dependencia

```java
// apps/api/app/src/main/java/<paquete-base>/payments/application/port/BusinessMetricsPort.java
public interface BusinessMetricsPort {

    /** The amount travels as Money; the adapter derives minor units through a kernel operation. */
    void paymentRegistered(UUID institutionId, String paymentMethod, PaymentChannel channel,
                           Money amount);
}

// PaymentChannel: CASHBOX, PORTAL, GATEWAY, BANK_TRANSFER
```

El adaptador que implementa este puerto vive en `shared/observability/metrics`. `domain` no conoce
Prometheus, `application` conoce solo el puerto, e `infrastructure` conoce el cliente. Es la misma
regla de dependencia del resto del sistema y no se relaja para observabilidad.

---

## 6. Tableros de Grafana

Tres tableros. Ni uno más al inicio: un tablero que nadie abre es documentación falsa de que el
sistema está vigilado.

### 6.1 Tablero operativo: `CONFIA / Operación`

Audiencia: el desarrollador. Se abre ante una sospecha de caída o lentitud.

| Panel | Contenido | Tipo |
|---|---|---|
| Estado de servicios | `up` de `confia-api-admin`, `confia-api-portal`, `confia-worker`, RDS for PostgreSQL, Redis, S3 (MinIO en local) | Estado con color |
| Versión desplegada | `confia_build_info` por servicio, con digest de imagen | Tabla |
| Tasa de solicitudes | `rate(http_server_requests_total[5m])` por servicio | Serie temporal |
| Tasa de error | Proporción de respuestas 5xx sobre el total, por servicio | Serie temporal con línea de umbral en 1 por ciento |
| Latencia p50, p95, p99 | Cuantiles de `http_server_request_duration_seconds` por servicio | Serie temporal |
| Endpoints más lentos | p95 por `route`, ordenado descendente | Tabla |
| Tareas en segundo plano | `confia_queue_depth` por tipo de tarea y estado | Serie temporal apilada |
| Antigüedad de la tarea vencida más antigua | `confia_queue_oldest_job_age_seconds` | Serie temporal con umbral en 900 segundos |
| Tareas fallidas | `increase(confia_job_failures_total[1h])` por tipo de tarea | Barras |
| Pool de conexiones | `confia_db_pool_connections` por estado | Serie temporal |
| Reintentos por serialización | `rate(confia_db_transaction_retries_total[15m])` | Serie temporal |
| Antigüedad del último respaldo lógico fuera de sitio | `time() - confia_backup_last_success_timestamp_seconds` | Estadística con umbral |
| Retraso del último punto restaurable de RDS | `confia_rds_restorable_time_lag_seconds` | Estadística con umbral en 900 segundos |
| Vencimiento de certificados | Días restantes por dominio | Tabla |
| Presupuesto de error consumido | Consumo del mes por cada SLO de la sección 8 | Medidor |

### 6.2 Tablero financiero: `CONFIA / Finanzas`

Audiencia: el propietario del producto y la administración de la institución.

| Panel | Contenido | Tipo |
|---|---|---|
| Recaudación del día | `confia_daily_collection_minor_units`, formateado como lempiras con dos decimales | Estadística grande |
| Recaudación por método de pago | Misma métrica desglosada por `payment_method` | Anillo |
| Recaudación de los últimos 30 días | Serie diaria con comparación contra el mismo período del mes anterior | Barras |
| Pagos por hora | `rate(confia_payments_registered_total[1h]) * 3600` por canal | Serie temporal |
| Distribución del importe de pago | Mapa de calor de `confia_payment_amount_minor_units` | Mapa de calor |
| Cartera por antigüedad | `confia_outstanding_balance_minor_units` por `aging_bucket` | Barras apiladas |
| Consumo del rango CAI | `confia_cai_range_consumed_percent` con umbrales en 70, 85 y 95 | Medidor por punto de emisión |
| Días para vencimiento del CAI | `confia_cai_range_days_to_expiry` | Estadística con umbral |
| Facturas emitidas y anuladas | Contadores del día y del mes, con tasa de anulación | Tabla |
| Cierres de caja | `confia_cashbox_closures_total` por resultado, últimos 30 días | Barras apiladas |
| Diferencias de caja | Suma y magnitud máxima por semana | Serie temporal |
| Asientos por tipo | `increase(confia_ledger_transactions_total[24h])` por tipo | Barras |
| Ajustes e incobrables del mes | Conteo e importe, con enlace al reporte de auditoría | Tabla |
| Estado de integridad del libro | `confia_ledger_unbalanced_transactions`, `confia_ledger_over_allocated_charges`, `confia_fiscal_sequence_gaps` | Estado, verde solo si los tres valen 0 |

Cada panel monetario lleva una nota fija al pie: *"Vista operativa. El dato oficial se obtiene del
reporte del libro mayor."*

### 6.3 Tablero de seguridad: `CONFIA / Seguridad`

Audiencia: el desarrollador en su rol de responsable de seguridad. Revisión semanal programada.

| Panel | Contenido | Tipo |
|---|---|---|
| Intentos fallidos de autenticación | `rate(confia_auth_failed_attempts_total[5m])` por `actor_kind` y motivo | Serie temporal |
| Indicio de relleno de credenciales | IPs distintas contra 5 o más cuentas en 10 minutos | Tabla |
| Rechazos por límite de tasa | `rate(confia_rate_limit_rejections_total[5m])` por ruta y alcance | Serie temporal |
| Accesos denegados por permiso | Conteo de eventos de auditoría con `outcome = 'denied'`, por actor y módulo | Tabla |
| Exportaciones de datos personales | Conteo de exportaciones sobre `students` y `guardians`, con filas exportadas por actor | Tabla con alerta por umbral |
| Consultas de expediente completo | Volumen por actor, para detectar recorrido del padrón | Serie temporal |
| Cadena de auditoría | `confia_audit_chain_broken` y `confia_audit_chain_verified_rows`, con hora de la última ancla publicada | Estado |
| Cambios de rol y de permisos | Eventos de auditoría de asignación y revocación de rol, últimos 30 días | Tabla |
| Aciertos de redacción de logs | `rate(confia_log_redaction_hits_total[1h])` por clase de campo | Serie temporal |
| Webhooks con firma inválida | Conteo por hora | Serie temporal |
| Sesiones de caja abiertas fuera de horario | `confia_cashbox_open_sessions` fuera de la ventana 06:00 a 20:00 | Estado |
| Estado de MFA | Usuarios con permiso de escritura financiera y MFA inactiva | Tabla, valor esperado vacío |
| Vencimiento de secretos y certificados | Días restantes por secreto rotable | Tabla |

Grafana se sirve detrás del subdominio administrativo, con autenticación y MFA, y **nunca** se
expone en el subdominio del portal.

---

## 7. Alertas

### 7.1 Canales y severidad

La severidad reutiliza la escala de `docs/03-seguridad.md` sección 17.1, para que no existan dos
vocabularios de gravedad en el mismo sistema.

| Severidad | Significado | Canal | Expectativa de respuesta |
|---|---|---|---|
| **S1 Crítica** | Pérdida de dinero, exposición de datos, o sistema caído | Llamada telefónica automatizada más mensaje al canal de guardia | Inmediata, a cualquier hora |
| **S2 Alta** | Degradación grave o riesgo de bloqueo operativo próximo | Mensaje al canal de guardia y correo | Menos de 4 horas en horario, menos de 12 fuera de horario |
| **S3 Media** | Anomalía que requiere revisión pero no compromete la operación | Correo diario agregado | Menos de 24 horas hábiles |
| **S4 Informativa** | Registro para revisión periódica | Tablero, sin notificación activa | Revisión semanal |

### 7.2 Catálogo de alertas

| Alerta | Condición concreta | Severidad | Canal | Acción esperada |
|---|---|---|---|---|
| `LedgerUnbalancedTransaction` | `confia_ledger_unbalanced_transactions > 0` durante 1 minuto | S1 | Llamada más canal de guardia | Ejecutar `docs/runbooks/descuadre-de-libro-mayor.md`. **No corregir con SQL** |
| `AuditChainBroken` | `confia_audit_chain_broken > 0`, o divergencia contra el ancla horaria | S1 | Llamada más canal de guardia | Ejecutar `docs/runbooks/incidente-de-seguridad.md`. Preservar evidencia antes de tocar nada |
| `ApiDown` | `up{service=~"confia-api-.*"} == 0` durante 2 minutos | S1 | Llamada | Verificar contenedor, dependencias y disco. Escalar a AWS si es de infraestructura |
| `DatabaseDown` | `up{job="rds-postgres"} == 0` durante 1 minuto | S1 | Llamada | Verificar el estado de la instancia de RDS en la consola de AWS. Sin base de datos no hay operación |
| `RdsBackupDisabled` | `confia_rds_backup_enabled == 0` | S1 | Llamada | Los respaldos automáticos de RDS están deshabilitados. Habilitarlos de inmediato: sin ellos no hay restauración a un punto en el tiempo dentro de AWS |
| `RdsRestorableTimeLagHigh` | `confia_rds_restorable_time_lag_seconds > 900` durante 10 minutos | S1 | Llamada | El objetivo de punto de recuperación de 15 minutos dentro de AWS está en riesgo. Revisar el estado de respaldo de RDS en la consola de AWS |
| `BackupMissing` | `time() - confia_backup_last_success_timestamp_seconds{backup_type="dump"} > 93600` (26 horas) | S1 | Llamada | El respaldo lógico fuera de sitio no se ha completado en un día. Ejecutar `infra/scripts/backup.sh` manualmente y diagnosticar: es el único punto de recuperación ante la pérdida total de la cuenta de AWS (ADR-0014) |
| `FiscalSequenceGap` | `confia_fiscal_sequence_gaps > 0` | S1 | Canal de guardia | Identificar el hueco y su anulación. Un hueco injustificado es un problema ante la SAR |
| `CaiRangeExhausted` | `confia_cai_range_remaining_numbers == 0` o `confia_cai_range_consumed_percent >= 100` | S1 | Llamada más aviso a contabilidad | Ejecutar `docs/runbooks/rango-cai-agotado.md`. La institución no puede facturar |
| `CaiRangeCritical` | `confia_cai_range_consumed_percent >= 95` durante 5 minutos | S2 | Canal de guardia y correo a contabilidad | Gestionar el rango nuevo ante la SAR de inmediato |
| `CaiRangeWarning` | `confia_cai_range_consumed_percent >= 85` durante 30 minutos | S3 | Correo a contabilidad | Iniciar el trámite del rango siguiente |
| `CaiRangeExpiringSoon` | `confia_cai_range_days_to_expiry <= 30` | S3 | Correo a contabilidad | Tramitar antes del vencimiento, aunque quede numeración |
| `CashboxDifferenceOverThreshold` | Cierre con `abs(difference) > 100.00 HNL` (10000 unidades menores) | S2 | Canal de guardia y correo al administrador | Ejecutar `docs/runbooks/cierre-de-caja-con-diferencia.md` |
| `CashboxRepeatedDifference` | 3 o más cierres con diferencia del mismo cajero en 30 días | S2 | Correo al propietario del producto | Investigación de control interno, no incidente técnico |
| `CashboxSessionLeftOpen` | `confia_cashbox_open_sessions > 0` después de las 20:00 | S3 | Correo al administrador | Contactar al cajero y cerrar la sesión con arqueo |
| `ErrorRateHigh` | Proporción de 5xx sobre total superior a 1 por ciento durante 5 minutos, por servicio | S2 | Canal de guardia | Revisar logs por `request_id` y decidir reversión de despliegue |
| `WriteLatencyHigh` | p95 de `http_server_request_duration_seconds` en rutas financieras de escritura mayor a 1 segundo durante 10 minutos | S2 | Canal de guardia | Revisar bloqueos, pool y consultas lentas |
| `QueueStalled` | `confia_queue_oldest_job_age_seconds > 900` durante 5 minutos | S2 | Canal de guardia | Revisar que `confia-worker` esté vivo y ejecutando, las tareas fallidas y la tarea envenenada |
| `QueueFailureBurst` | `increase(confia_job_failures_total[15m]) > 20` en un tipo de tarea | S2 | Canal de guardia | Diagnosticar la causa común antes de reprogramar las tareas fallidas |
| `TaskFailed` | `confia_queue_depth{state="failed"} > 0` | S3 | Correo al administrador | Una tarea agotó sus intentos. Corregir la causa y reprogramarla; el manejador es idempotente. Nunca se descarta en silencio (ADR-0016) |
| `ChargeGenerationMissed` | `confia_charge_generation_last_success_timestamp_seconds` con más de 26 horas el día de devengo | S2 | Canal de guardia y correo al administrador | Ejecutar el devengo manualmente. Es idempotente |
| `NotificationFailureRateHigh` | `rate(confia_notifications_failed_total[1h])` sobre el total del canal mayor al 20 por ciento durante 1 hora | S2 | Canal de guardia | Revisar el proveedor. Sin notificación entregada no se aplica mora |
| `LedgerCacheDrift` | `confia_ledger_cached_balance_drift_accounts > 0` | S2 | Canal de guardia | Reconstruir la caché desde el libro. Investigar la causa antes de reconstruir |
| `OverAllocatedCharge` | `confia_ledger_over_allocated_charges > 0` | S1 | Canal de guardia | Ejecutar el runbook de descuadre. Hay dinero imputado de más |
| `CredentialStuffingSuspected` | Una IP contra 5 o más cuentas distintas en 10 minutos | S2 | Canal de seguridad | Bloqueo temporal de la IP y revisión de cuentas afectadas |
| `MassExportDetected` | Exportación de `students` o `guardians` con más de 500 filas | S2 | Canal de seguridad | Confirmar con el actor que la exportación fue legítima |
| `CardDataDetectedInLogs` | `increase(confia_log_redaction_hits_total{field_class="card"}[1h]) > 0` | S2 | Canal de seguridad | El sistema no debe recibir datos de tarjeta nunca (SAQ A). Investigar el origen |
| `MfaMissingOnFinancialRole` | Un usuario con permiso de escritura financiera y MFA inactiva, verificación diaria | S2 | Correo al propietario | Suspender la sesión y exigir alta de MFA |
| `CertificateExpiringSoon` | `confia_tls_certificate_expiry_timestamp_seconds` a menos de 21 días | S3 | Correo | Verificar el certificado de origen de Cloudflare y el certificado de borde de Cloudflare; fuera de AWS, verificar la renovación automática de `certbot` |
| `DiskSpaceLow` | Espacio libre del volumen EBS de la instancia de aplicación menor al 15 por ciento, o almacenamiento libre de RDS por debajo del umbral de la alarma de AWS | S2 | Canal de guardia | Liberar espacio o ampliar el volumen. Sin espacio, la aplicación o RDS se detienen |
| `DbPoolSaturated` | `confia_db_pool_connections{state="waiting"} > 0` durante 5 minutos | S2 | Canal de guardia | Revisar fugas de conexión y consultas largas |
| `RestoreDrillOverdue` | Más de 35 días desde el último simulacro de restauración exitoso | S3 | Correo | Ejecutar el simulacro. Un respaldo no restaurado no es un respaldo |
| `RetentionBacklog` | `confia_data_retention_pending_records > 0` por más de 7 días | S3 | Correo | Ejecutar la tarea de retención y revisar el fallo |
| `DsrOverdue` | Solicitud de derechos del titular abierta por más de 25 días | S2 | Correo al propietario | Atender antes del plazo de 30 días. Ver documento 08 |

### 7.3 Alertas que deliberadamente no existen

- **Uso de CPU o memoria por encima de un porcentaje.** No es accionable por sí solo. Lo accionable
  es la latencia, la tasa de error y la saturación del pool, que ya están cubiertas.
- **Cada intento fallido de una tarea.** Un fallo aislado con reintento pendiente no requiere una
  persona despierta. La alerta es por ráfaga, por antigüedad de la tarea vencida más antigua y por
  tarea que agotó sus intentos.
- **Cada intento fallido de inicio de sesión.** El sistema ya aplica retroceso exponencial. Lo
  alertable es el patrón, no el evento.
- **Cada cierre de caja con diferencia de un lempira.** Existe un umbral de tolerancia
  justificado. Ver `docs/runbooks/cierre-de-caja-con-diferencia.md`.

### 7.4 Higiene de alertas

Regla explícita y obligatoria: **una alerta que nadie atiende se elimina o se corrige, nunca se
ignora.**

Procedimiento de revisión mensual:

1. Se lista cada alerta disparada en el mes, con su conteo y su tiempo hasta la primera acción.
2. Toda alerta que se disparó y **no** produjo ninguna acción entra en revisión obligatoria.
3. Para cada una se decide una de tres cosas, y se registra la decisión con fecha:
   - **Ajustar** el umbral o la ventana, porque la condición era correcta y el umbral no.
   - **Eliminar** la alerta, porque la condición no era accionable.
   - **Convertirla** en panel de tablero de severidad S4, porque el dato es útil pero no urgente.
4. Se prohíbe la cuarta opción, que es la que degrada todo sistema de alertas: dejarla activa y
   acostumbrarse a descartarla. Un canal con ruido garantiza que la alerta de descuadre del libro
   mayor pase desapercibida a las dos de la mañana, y esa es la única que no puede pasar
   desapercibida.
5. El silenciamiento temporal existe, pero **siempre con fecha de expiración obligatoria**, máximo
   siete días, y con el motivo registrado. No hay silenciamiento indefinido.

Métrica de la propia salud del sistema de alertas: número de alertas disparadas por semana. Si
supera diez en un sistema de esta escala, el problema son las alertas, no el sistema.

---

## 8. Objetivos de nivel de servicio

Los objetivos se miden sobre ventana móvil de 30 días. El presupuesto de error es la parte del
objetivo que se puede gastar sin incumplir.

| SLO | Indicador | Objetivo | Presupuesto de error en 30 días | Qué se hace al agotarlo |
|---|---|---|---|---|
| **Disponibilidad de la API administrativa** | Proporción de solicitudes con respuesta distinta de 5xx, en horario 06:00 a 20:00 | 99.5 por ciento | 2 horas y 10 minutos de indisponibilidad efectiva | Se congela el trabajo de funcionalidad nueva hasta cerrar la causa raíz |
| **Disponibilidad del portal de encargados** | Igual, 24 horas | 99.0 por ciento | 7 horas y 18 minutos | Igual. El portal tolera algo más porque no bloquea la operación de caja |
| **Latencia de lectura** | p95 de `http_server_request_duration_seconds` en rutas `GET` | Menor a 400 ms | 5 por ciento de solicitudes por encima | Revisión de índices y de consultas antes de agregar funcionalidad |
| **Latencia de escritura financiera** | p95 en `POST /api/v1/payments`, `POST /api/v1/invoices`, `POST /api/v1/cash-sessions/*/close` | Menor a 1000 ms | 5 por ciento por encima | Revisión de contención de bloqueos y de aislamiento serializable |
| **Tasa de error** | Proporción de 5xx sobre el total | Menor a 0.5 por ciento | 0.5 por ciento de las solicitudes del mes | Corrección obligatoria antes del siguiente despliegue de funcionalidad |
| **Objetivo de punto de recuperación** | `confia_rds_restorable_time_lag_seconds` máximo observado, dentro de AWS | Menor a 15 minutos | Ninguno: cualquier incumplimiento sostenido es S1 | Corrección inmediata del respaldo de RDS. Ante la pérdida total de la cuenta de AWS, el objetivo lo fija el respaldo lógico fuera de sitio más reciente, límite aceptado en ADR-0014 |
| **Objetivo de tiempo de recuperación** | Duración del simulacro mensual de restauración | Menor a 4 horas | Ninguno | Si el simulacro excede 4 horas, se ajusta el procedimiento hasta cumplirlo |
| **Integridad del libro mayor** | `confia_ledger_unbalanced_transactions` | Exactamente 0, siempre | **Sin presupuesto de error** | Detención de escrituras sobre la cuenta afectada y escalamiento |
| **Integridad de la cadena de auditoría** | `confia_audit_chain_broken` | Exactamente 0, siempre | **Sin presupuesto de error** | Incidente de seguridad S1 |

Los dos últimos no son objetivos estadísticos sino invariantes. Un sistema financiero no negocia
un porcentaje de descuadre aceptable.

**Latencia medida dónde.** Los indicadores se calculan en el servidor de aplicación, no en el
borde. Se documenta explícitamente porque la latencia percibida por el usuario incluye red y
navegador, y confundir ambas produce discusiones improductivas.

---

## 9. Comprobaciones de salud

Dos endpoints con propósitos distintos. Confundirlos es el error clásico que provoca reinicios en
cascada: si la comprobación de vida verifica la base de datos, una caída de PostgreSQL reinicia
todos los contenedores de la API sin resolver nada.

| Endpoint | Propósito | Verifica | Efecto de fallar |
|---|---|---|---|
| `GET /health/live` | ¿El proceso está vivo y responde? | Nada externo. Solo el estado de vida de la aplicación | Docker reinicia el contenedor |
| `GET /health/ready` | ¿Puede atender tráfico? | PostgreSQL, Redis, estado de la tabla de tareas de db-scheduler, almacenamiento de objetos, migraciones aplicadas | nginx deja de enviarle tráfico. **No** se reinicia |
| `GET /health/startup` | ¿Terminó de arrancar? | Migraciones aplicadas y conexiones establecidas | Retrasa el inicio de las otras dos comprobaciones |

Los tres son rutas públicas explícitas en la lista blanca de `docs/03-seguridad.md` sección 5.1, y
**no revelan detalle de infraestructura** a un consumidor no autenticado: desde fuera de la red
privada devuelven únicamente `{"status":"ok"}` o el código 503, sin el desglose por dependencia.

Las tres comprobaciones se implementan con Spring Boot Actuator mediante grupos de salud: un grupo
por comprobación, cada uno expuesto en su ruta pública como ruta adicional. La configuración
siguiente es orientativa y se valida en F0 contra la versión de Spring Boot adoptada; los nombres
`objectStorage`, `backgroundJobs` y `migrations` corresponden a indicadores propios de
`shared/observability/health`.

```yaml
# application.yml (extract, validated in F0)
management:
  endpoint:
    health:
      probes:
        enabled: true
      # Outside the private network only {"status": ...} is returned, never the breakdown.
      show-details: never
      group:
        liveness:
          # No external dependency: a database outage must not trigger a restart storm.
          include: livenessState
          additional-path: "server:/health/live"
        readiness:
          include: readinessState, db, redis, objectStorage, backgroundJobs
          additional-path: "server:/health/ready"
        startup:
          include: migrations, db
          additional-path: "server:/health/startup"
```

Reglas de los indicadores propios, independientes del mecanismo:

- **PostgreSQL.** Una sentencia trivial y parametrizada, con tiempo de espera corto. Prueba la
  conexión y el rol, no los datos; una sonda más pesada convierte la salud en carga extra.
- **Sin fugas.** Ningún indicador devuelve la cadena de conexión ni el error del controlador de
  base de datos en el cuerpo de la respuesta: solo `unreachable`.
- **Trabajos en segundo plano.** Se calcula sobre la tabla de tareas de db-scheduler (ADR-0016):
  degradado si la tarea vencida más antigua supera 900 segundos, coherente con la alerta
  `QueueStalled`. Un atraso del trabajador no debe retirar del tráfico a los procesos que atienden
  peticiones, que solo programan tareas: el estado degradado se informa sin marcar la preparación
  como caída. El mapeo exacto del estado en Actuator se valida en F0. La tabla de tareas es una
  tabla técnica fuera de la generación de código de jOOQ: la lee únicamente el componente de
  observabilidad de `shared`, con consultas de solo lectura incluidas en la lista aprobada de SQL
  plano (ADR-0015, regla 9; ADR-0017, regla 6), que nunca seleccionan la columna de datos de la
  tarea. En el proceso del portal este indicador solo puede existir si `confia_portal_app` tiene
  `SELECT` sobre `scheduled_tasks` porque el cliente de programación lo exige (validado en F0):
  ADR-0017 no concede ese privilegio para la salud, de modo que, si no lo tiene, el grupo de
  preparación del portal no incluye `backgroundJobs`.
- **Migraciones.** El arranque no se completa hasta que el esquema coincide con las migraciones
  de Flyway esperadas por el artefacto. La comprobación lee `flyway_schema_history` con el
  `SELECT` que ADR-0017 concede a `confia_admin_app` y a `confia_portal_app`, mediante una
  consulta de solo lectura de la lista aprobada de SQL plano; ningún rol de aplicación escribe en
  esa tabla. Los procesos desplegados no aplican migraciones: eso lo hace solo
  `APP_PROFILE=migrate` (`docs/05-infraestructura-y-despliegue.md`, sección 9), con `confia_owner`.
- **Memoria.** La vida del proceso no depende de un umbral de heap: el heap está acotado por el
  límite del contenedor mediante `JAVA_TOOL_OPTIONS`, y ante falta de memoria la JVM termina y
  Docker la reinicia.

### 9.1 Declaración en Docker Compose

```yaml
# infra/docker/compose.prod.yml (extracto)
services:
  api-admin:
    healthcheck:
      # The JRE image may not ship wget or curl: the command is validated in F0
      # (docs/05-infraestructura-y-despliegue.md, section 3.2).
      test: ["CMD", "wget", "-q", "--spider", "http://127.0.0.1:3000/health/live"]
      interval: 15s
      timeout: 3s
      retries: 3
      start_period: 60s

  postgres:
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U confia_owner -d confia"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    healthcheck:
      test: ["CMD-SHELL", "redis-cli --no-auth-warning -a \"$$REDIS_PASSWORD\" ping | grep -q PONG"]
      interval: 10s
      timeout: 3s
      retries: 5
```

Comprobación manual desde el host. Si la imagen de ejecución no incluye `curl`, se usa la
herramienta validada en F0 para la comprobación de salud:

```bash
# Liveness y readiness del proceso administrativo
docker compose exec api-admin curl -fsS http://127.0.0.1:3000/health/live
docker compose exec api-admin curl -fsS http://127.0.0.1:3000/health/ready

# Estado de todos los contenedores y su salud
docker compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Health}}'
```

---

## 10. Retención y costo

### 10.1 Política de retención

| Dato | Retención en caliente | Retención en frío | Base de la decisión |
|---|---|---|---|
| Logs de aplicación | 30 días con búsqueda | 12 meses en almacenamiento de objetos, comprimidos y cifrados | 30 días cubre la investigación de un reclamo típico. 12 meses cubre una auditoría anual |
| Logs de acceso de nginx | 30 días | 12 meses | Igual. Contienen IP, que es dato personal: no se conservan más allá de lo justificable |
| Métricas de alta resolución | 15 días a 15 segundos | - | Diagnóstico de incidentes recientes |
| Métricas agregadas | - | 13 meses a 5 minutos | Comparación interanual del mismo mes de matrícula |
| Trazas | 7 días | - | Su valor es diagnóstico inmediato. Conservarlas más tiempo no aporta y sí cuesta |
| Bitácora de auditoría (`shared_audit_log`) | **No se rota. Permanece en la base de datos** | Copia periódica a almacenamiento de objetos con bloqueo de objeto | Es evidencia, no log. Ver documento 08 para su período de conservación |
| Registros de incidente | Permanentes en el repositorio de documentación | - | Aprendizaje institucional |

Los logs y trazas **no** son el sistema de registro financiero. La retención corta es aceptable
precisamente porque la evidencia vive en `shared_audit_log` y en el libro mayor, ambos inmutables.

### 10.2 Costo estimado

Estimación para una institución de aproximadamente 500 estudiantes, que es el escenario de la fase
uno. Los números son órdenes de magnitud, no un presupuesto cerrado, y se recalculan cuando el
volumen real esté medido durante el primer trimestre de producción.

Supuestos declarados:

- Alrededor de 20000 solicitudes HTTP por día hábil, con picos en la primera semana de cada mes.
- Alrededor de 5 líneas de log por solicitud, más los trabajos en segundo plano: unas 120000 líneas
  diarias, con un tamaño medio de 700 bytes.
- Unas 2000 series de métricas activas, con muestreo cada 15 segundos.
- Muestreo de trazas del 10 por ciento, con unos 12 tramos por traza.

| Concepto | Volumen | Cálculo | Costo mensual aproximado |
|---|---|---|---|
| Logs en caliente, 30 días | 84 MB por día sin comprimir, unos 12 MB comprimidos | 12 MB por 30 días, unos 360 MB | Incluido en el disco del host. Costo marginal cercano a cero |
| Logs en frío, 12 meses | Unos 4.4 GB comprimidos y cifrados | 4.4 GB en almacenamiento de objetos | Menos de 0.05 USD al mes con un proveedor de bajo costo |
| Métricas, 15 días de alta resolución | 2000 series por 5760 muestras diarias, con compresión de aproximadamente 2 bytes por muestra: unos 23 MB por día | 23 MB por 15 días, unos 350 MB | Incluido en el disco. Costo marginal cercano a cero |
| Métricas agregadas, 13 meses | Muestreo cada 5 minutos, unos 2.3 GB | Disco del host o almacenamiento de objetos | Menos de 0.05 USD al mes |
| Trazas, 7 días | 2000 trazas diarias muestreadas por 12 tramos, unos 60 MB por día | 60 MB por 7 días, unos 420 MB | Incluido en el disco |
| Respaldo lógico fuera de sitio, fuera de AWS | Estimado entre 5 y 20 GB con retención escalonada | Almacenamiento de objetos con bloqueo de objeto y versionado, en un proveedor distinto al de cómputo (ADR-0014) | Menos de 1 USD al mes (ver ADR-0014, estimación de costos), más costo de salida en una restauración |
| Recursos de cómputo de Prometheus, Grafana y colector | Unos 1.5 GB de memoria y consumo bajo de CPU | Comparten el host de aplicación en fase uno | Costo marginal frente al servidor ya existente |

**Conclusión de costo.** La observabilidad completa de este sistema cuesta menos de un dólar
mensual en almacenamiento y aproximadamente 1.5 GB de memoria en el servidor. Es despreciable
frente al costo de no poder responder a un reclamo de pago. La decisión de retención corta en
trazas y larga en auditoría no se toma por dinero sino por privacidad: los logs contienen
direcciones IP y patrones de uso de personas, y conservarlos más de lo justificable es un
incumplimiento del principio de limitación del plazo de conservación. Ver
`docs/08-datos-privacidad-y-retencion.md`.

**Justificación del proveedor de bajo costo.** Se asume una tarifa del orden de 0.006 USD por GB al
mes para el almacenamiento de objetos de tipo Backblaze B2, y almacenamiento local en el disco del
host para lo caliente. Si se migra a un proveedor gestionado con tarifas por ingesta, el costo
cambia por completo y se debe revisar esta sección antes de adoptarlo.

---

## 11. Tareas de operación programadas

Las tareas de aplicación de esta tabla son tareas recurrentes de db-scheduler (ADR-0016): se
declaran en código con expresión de calendario y zona horaria `America/Tegucigalpa`, se ejecutan
solo en `confia-worker` a través del componente transaccional con actor `system` y el contexto de
cada institución, llevan `correlationId` propio y registran su resultado en la bitácora de
auditoría cuando tocan datos. La tabla de tareas coordina la ejecución, de modo que una tarea
recurrente corre una sola vez aunque haya varias réplicas del trabajador. Ninguna corre como `cron`
del sistema operativo dentro del contenedor de la API ni con `@Scheduled` de Spring: eso duplicaría
la ejecución en cada proceso. Las horas de la tabla son de `America/Tegucigalpa`.

Las tareas de infraestructura de la tabla (respaldo lógico fuera de sitio, prueba de restauración,
verificación de certificados y escaneo de la imagen) no son código de la aplicación y quedan fuera
de ADR-0016: no corren en `confia-worker`, cuya imagen solo contiene el JRE y el artefacto. Se
ejecutan mediante un temporizador de `systemd` en la instancia EC2 (`infra/scripts/backup.sh`,
`docs/05-infraestructura-y-despliegue.md`, sección 11), validado en F0. Los respaldos automáticos
de RDS con restauración a un punto en el tiempo los gestiona AWS directamente, sin una tarea que
programar (ADR-0014).

| Tarea | Frecuencia | Qué hace | Métrica que publica | Qué pasa si falla |
|---|---|---|---|---|
| **Verificación de integridad del libro mayor** | Diaria, 02:00 | Ejecuta las seis verificaciones de `.claude/skills/confia-ledger-invariants/SKILL.md` sección 7: cuadre por transacción, cuadre por cuenta contra la caché, imputación no excedida, unicidad de reverso, continuidad de auditoría y correlativo fiscal | `confia_ledger_unbalanced_transactions`, `confia_ledger_cached_balance_drift_accounts`, `confia_ledger_over_allocated_charges`, `confia_ledger_double_reversal_transactions`, `confia_fiscal_sequence_gaps` | Alerta S1 y runbook de descuadre. La cuenta afectada se marca `UNDER_REVIEW` y se bloquean sus escrituras |
| **Generación de cargos** | Diaria; genera los cargos de cada plan de cobro en su día de devengo | Genera los cargos del período, con un cargo por estudiante, concepto y período garantizado por restricción única | `confia_charges_generated_total`, `confia_charge_generation_last_success_timestamp_seconds` | Alerta S2 `ChargeGenerationMissed`. Reejecutar es seguro: la tarea es idempotente |
| **Purga de tokens de refresco vencidos** | Diaria | Elimina los tokens de refresco vencidos o revocados (ADR-0005) | Publica el conteo purgado como métrica | Alerta S3 `TaskFailed`. Un token vencido se rechaza igual al validarse; la purga reduce volumen y superficie |
| **Verificación de la cadena de auditoría, horaria** | Cada hora | Compara el último `record_hash` contra el ancla publicada en almacenamiento de objetos y publica el ancla nueva | `confia_audit_chain_broken` | Alerta S1 y runbook de incidente de seguridad. No se repara la cadena: se preserva la evidencia |
| **Verificación de la cadena de auditoría, diaria** | Diaria, 03:00 | Recalcula la cadena completa del último mes | `confia_audit_chain_verified_rows` | Igual |
| **Verificación de la cadena de auditoría, semanal** | Domingo, 03:30 | Recalcula desde el registro génesis | `confia_audit_chain_verified_rows{scope="weekly"}` | Igual |
| **Respaldo lógico fuera de sitio** | Diaria, 01:00 | `infra/scripts/backup.sh nightly`: `pg_dump` contra RDS, cifrado con age, subido a un proveedor de almacenamiento distinto al de cómputo, sin retención local (ADR-0014) | `confia_backup_last_success_timestamp_seconds`, `confia_backup_duration_seconds` | Alerta S1 `BackupMissing`. Es el único punto de recuperación ante la pérdida total de la cuenta de AWS |
| **Respaldo automático de RDS** | Continuo, gestionado por AWS | Respaldo automático con restauración a un punto en el tiempo, sin script propio (ADR-0014) | `confia_rds_backup_enabled`, `confia_rds_restorable_time_lag_seconds` | Alertas S1 `RdsBackupDisabled` y `RdsRestorableTimeLagHigh`. El objetivo de punto de recuperación de 15 minutos dentro de AWS depende de esto |
| **Prueba de restauración de respaldo** | Mensual, primer sábado | Restaura el respaldo más reciente en un entorno aislado, cuenta registros, verifica el cuadre del libro mayor y verifica la cadena de auditoría. Deja acta con fecha, duración y resultado | `confia_restore_drill_last_success_timestamp_seconds`, `confia_restore_drill_duration_seconds` | Alerta S3 `RestoreDrillOverdue`. Un respaldo no restaurado no cuenta como respaldo. Ver `docs/runbooks/restauracion-de-respaldo.md` |
| **Revisión de rangos CAI** | Diaria, 07:00, y tras cada emisión | Recalcula porcentaje consumido, correlativos restantes y días hasta la fecha límite por punto de emisión | `confia_cai_range_consumed_percent`, `confia_cai_range_remaining_numbers`, `confia_cai_range_days_to_expiry` | Alertas escalonadas en 85, 95 y 100 por ciento. Ver `docs/runbooks/rango-cai-agotado.md` |
| **Limpieza por retención de datos** | Semanal, domingo 04:00 | Aplica la política de `docs/08-datos-privacidad-y-retencion.md`: elimina o anonimiza lo vencido, respetando siempre las obligaciones de conservación fiscal | `confia_data_retention_pending_records` | Alerta S3 `RetentionBacklog`. Cada eliminación o anonimización se audita |
| **Reconstrucción de la caché de saldos** | Diaria, 02:30, después de la verificación de integridad | Reconstruye desde cero el saldo materializado y lo compara contra el existente antes de sustituirlo | `confia_ledger_cached_balance_drift_accounts` | Alerta S2. **Nunca se sobrescribe la caché sin registrar primero la divergencia detectada** |
| **Verificación de MFA en roles financieros** | Diaria, 06:00 | Comprueba que ningún usuario con permiso de escritura financiera tiene MFA inactiva | Publica el conteo como métrica | Alerta S2 `MfaMissingOnFinancialRole` y suspensión de sesión |
| **Recálculo de cartera por antigüedad** | Diaria, 05:00 | Recalcula la cartera vencida por tramo desde el libro mayor | `confia_outstanding_balance_minor_units` | Alerta S3. Es informativo, no bloqueante |
| **Verificación de certificados y secretos** | Diaria, 08:00 | Días restantes de certificados TLS y de secretos con rotación programada | `confia_tls_certificate_expiry_timestamp_seconds` | Alerta S3 `CertificateExpiringSoon` |
| **Escaneo de vulnerabilidades de la imagen en ejecución** | Semanal, lunes 06:00 | Trivy contra el digest desplegado, no contra la etiqueta | Publica conteo por severidad | Alerta S3, o S2 si hay vulnerabilidad crítica con explotación conocida |
| **Reporte de segregación de funciones** | Trimestral | Genera el reporte "quién puede hacer qué" y lo deja para revisión con el propietario | - | Alerta S3 si no se generó |

### 11.1 Comandos de operación manual

```bash
# Estado general del despliegue
docker compose -f infra/docker/compose.prod.yml ps

# Logs correlacionados de una solicitud concreta, en los tres servicios
docker compose logs --since 24h --no-log-prefix api-admin api-portal worker \
  | grep '"requestId":"018f3a2b-9c4d-7e10-8f21-9a7b3c5d1e00"'

# Manual ledger integrity check: triggered from the admin API through a use case guarded by a
# permission and audited, which schedules the same one-time task used by the nightly run
# (ADR-0016). Nobody writes to the task table by hand. Endpoint and permission are defined in the
# ledger module specification.

# Background task status, read only. Default db-scheduler columns, validated in F0 (ADR-0016).
# The task data column is never selected.
docker compose exec postgres psql -U confia_owner -d confia -c "
  SELECT task_name,
         count(*) FILTER (WHERE NOT picked AND execution_time <= now())             AS due,
         count(*) FILTER (WHERE picked)                                             AS running,
         count(*) FILTER (WHERE consecutive_failures > 0)                           AS failing,
         min(execution_time) FILTER (WHERE NOT picked AND execution_time <= now())  AS oldest_due
  FROM scheduled_tasks
  GROUP BY task_name
  ORDER BY task_name;"

# Estado actual de los rangos CAI
docker compose exec postgres psql -U confia_owner -d confia -c "
  SELECT issuance_point_id, document_type, cai, range_from, range_to, current_number,
         ROUND(100.0 * (current_number - range_from) / NULLIF(range_to - range_from, 0), 2)
           AS consumed_percent,
         valid_until, status
  FROM cai_range
  WHERE status = 'active'
  ORDER BY consumed_percent DESC;"
```

---

## 12. Lista de verificación previa a producción

- [ ] Los tres servicios emiten logs JSON a `stdout`, sin escritura a archivo dentro del contenedor.
- [ ] La prueba de redacción del registro estructurado pasa y cubre todos los campos de la sección 2.3.
- [ ] Ninguna ruta registra el cuerpo completo de solicitud o respuesta en producción.
- [ ] Toda solicitud devuelve la cabecera `X-Request-Id` y el mismo valor aparece en `shared_audit_log`.
- [ ] Una tarea de db-scheduler programada desde una petición HTTP comparte `correlationId` con
      ella, verificado por prueba de integración (ADR-0016).
- [ ] Las métricas de tareas en segundo plano se calculan sobre la tabla de tareas y las alertas
      `QueueStalled`, `QueueFailureBurst` y `TaskFailed` se probaron en preproducción (ADR-0016).
- [ ] `/internal/metrics` no es alcanzable desde internet en ninguno de los dos subdominios.
- [ ] Las métricas de negocio obligatorias existen y tienen valor distinto de nulo.
- [ ] Ninguna métrica usa un identificador de persona como etiqueta.
- [ ] Los tres tableros de Grafana están creados y sus consultas devuelven datos.
- [ ] Grafana exige autenticación con MFA y vive solo en el subdominio administrativo.
- [ ] Cada alerta del catálogo está configurada, probada disparándola de forma artificial, y su
      runbook está enlazado en la anotación de la regla.
- [ ] `/health/live` no consulta dependencias externas.
- [ ] `/health/ready` falla de verdad cuando se detiene PostgreSQL, verificado en preproducción.
- [ ] Las comprobaciones de salud no exponen detalle de infraestructura a un cliente no autenticado.
- [ ] El respaldo lógico fuera de sitio y el respaldo automático de RDS publican su métrica de
      última ejecución o de retraso del punto restaurable correcta.
- [ ] El simulacro de restauración se ejecutó al menos una vez, con acta y duración registrada.
- [ ] Cada tarea programada de la sección 11 está registrada, con su horario y su métrica.
- [ ] La revisión mensual de higiene de alertas está agendada.

---

## 13. Documentos relacionados

| Documento | Relación |
|---|---|
| `docs/01-arquitectura.md` | Fuente de verdad. Sección 8 resume la política de observabilidad |
| `docs/03-seguridad.md` | Bitácora de auditoría, respuesta a incidentes y clasificación de severidad |
| `docs/08-datos-privacidad-y-retencion.md` | Retención de datos personales y limitación de conservación de logs |
| `docs/10-analisis-de-brechas.md` | Brechas B6 (auditoría) y B10 (respaldo y continuidad) |
| `docs/runbooks/` | Procedimientos que ejecutan las alertas de este documento |
| `.claude/skills/confia-ledger-invariants/SKILL.md` | Verificaciones de integridad del libro mayor |
| `.claude/skills/confia-money-rules/SKILL.md` | Representación de importes, también en métricas y logs |

---
name: confia-security-checklist
description: Lista de verificación de seguridad de CONFIA. Se dispara antes de fusionar un cambio, al crear o modificar un endpoint, al tocar autenticación, autorización, Spring Security, consultas a la base de datos, manejo de archivos, formularios, integraciones externas o tareas en segundo plano.
---

# Lista de verificación de seguridad de CONFIA

CONFIA mueve dinero real y almacena datos de menores de edad, con un solo desarrollador y sin
segundo par de ojos por defecto. La disciplina de seguridad no se sostiene en la memoria: se
sostiene en listas verificables que se marcan antes de fusionar. Un ítem sin marcar es un defecto,
no una advertencia.

Fuente de verdad de los controles: `docs/03-seguridad.md`. Si esta skill contradice ese documento,
gana el documento. El backend es Java con Spring Boot y Spring Security, y el frontend es
TypeScript con React (ADR-0013). Los ejemplos de código son ilustrativos; los mecanismos concretos
de Spring Security (filtros, autorización a nivel de método y componentes transversales) se validan
en F0.

## 0. Cómo usar esta skill

1. Identifica el tipo de cambio (endpoint nuevo, cambio de esquema, formulario nuevo, integración
   externa, tarea en segundo plano). Puede ser más de uno.
2. Aplica la lista correspondiente completa. No omitas un ítem porque "en este caso no aplica" sin
   dejar constancia explícita de por qué no aplica.
3. Antes de proponer que un cambio está listo para fusionar, revisa además la sección 6 de patrones
   peligrosos y la lista final de la sección 7.

## 1. Endpoint nuevo

- [ ] El endpoint declara su autorización (`modulo:verbo`) con la autorización a nivel de método de
      Spring Security o la regla de rutas equivalente, usando las constantes de
      `<Capability>Permissions`. Un endpoint sin autorización declarada ni marca explícita de ruta
      pública no debe arrancar la aplicación; si arranca, es un defecto de arranque, no una
      excepción válida.
- [ ] La autenticación la resuelve la cadena de filtros de Spring Security antes de llegar al
      controlador. Ningún controlador interpreta tokens o cookies por su cuenta.
- [ ] La autorización se decide en el servidor. Ningún dato de la petición (rol, permiso,
      identificador de institución) se toma como verdad sin verificarlo contra la sesión.
- [ ] Existe verificación de propiedad a nivel de objeto en el caso de uso: el actor y el
      identificador del recurso se comparan antes de actuar, no se delega al repositorio.
- [ ] Un objeto que existe pero no pertenece al actor responde **404**, nunca 403.
- [ ] Toda entrada se valida en el borde con Jakarta Bean Validation sobre el DTO de entrada
      (`@Valid`), y el deserializador JSON rechaza propiedades no declaradas. Un campo no declarado
      rompe la validación, nunca se asigna en silencio.
- [ ] La salida usa un DTO explícito. Ninguna entidad de persistencia ni de dominio se serializa
      directamente hacia el cliente.
- [ ] Si el endpoint escribe dinero: exige y respeta la cabecera `Idempotency-Key`. Ver
      `confia-money-rules` y `confia-ledger-invariants`.
- [ ] Si el endpoint afecta el libro mayor: la escritura ocurre dentro de una transacción explícita
      con bloqueo de la cuenta afectada.
- [ ] La acción sensible escribe en la bitácora de auditoría dentro de la misma transacción de
      negocio. Ver `confia-audit-logging`.
- [ ] Los errores se traducen a Problem Details (RFC 9457) en el traductor global. Nunca se filtra
      traza de pila, mensaje de base de datos ni detalle interno. Ver `confia-api-conventions`.
- [ ] El endpoint tiene límite de tasa apropiado a su superficie: agresivo en el portal público,
      moderado en la API administrativa.
- [ ] La ruta aparece en el OpenAPI generado con sus códigos de respuesta reales, incluidos los de
      error. Swagger UI y el endpoint del OpenAPI siguen deshabilitados en el perfil de producción.
- [ ] Si el endpoint es administrativo, no queda registrado en el punto de entrada del portal.
- [ ] Si el endpoint puede devolver datos filtrados por fila (estudiante, encargado, institución):
      existe prueba de integración de la política de seguridad a nivel de fila con base de datos
      real. Un doble de prueba no la detecta. Ver `confia-testing-playbook`.

## 2. Cambio de esquema

- [ ] El cambio es una migración de Flyway nueva; ninguna migración aplicada se edita.
- [ ] Toda tabla nueva que lleve `institution_id` tiene `ROW LEVEL SECURITY` y `FORCE ROW LEVEL
      SECURITY` activados, con su política declarada en la misma migración.
- [ ] Los permisos de PostgreSQL se conceden por tabla y de forma explícita. Nunca se hereda un
      privilegio amplio "por si acaso". Se aplica `REVOKE ALL` de partida.
- [ ] Ninguna tabla financiera o de auditoría concede `UPDATE` ni `DELETE` al rol de aplicación.
- [ ] Toda columna de dinero es `NUMERIC(14,4)` con su columna de moneda al lado. Nunca `float8`,
      `real`, `double precision` ni `money`. Ver `confia-money-rules`.
- [ ] Los invariantes financieros se declaran en el esquema con `CHECK`, `UNIQUE` o disparadores,
      no solo en el código Java. Ver `confia-ledger-invariants`.
- [ ] Ninguna columna nueva almacena un identificador nacional, un número de tarjeta o un dato de
      menor sin evaluar si requiere cifrado a nivel de columna.
- [ ] Ninguna migración contiene una contraseña ni un secreto.
- [ ] La migración se revisa como cambio de alto riesgo y se acompaña de su procedimiento de
      reversión cuando es técnicamente posible.

## 3. Formulario nuevo

- [ ] El formulario usa el esquema Zod generado en `packages/contracts` desde el OpenAPI del
      backend. No existe una segunda validación escrita a mano y divergente en el cliente, y la
      validación del cliente nunca sustituye la del servidor.
- [ ] Ningún cálculo de dinero ocurre en el componente. Si hace falta una previsualización de un
      total, la calcula un endpoint del servidor; el navegador solo la formatea.
- [ ] Los campos con datos de un menor o de su responsable financiero se limitan a lo
      estrictamente necesario para la operación.
- [ ] El formulario no envía un campo que el contrato no declare. El backend rechaza cualquier
      propiedad adicional inyectada desde el cliente.
- [ ] Las acciones irreversibles con efecto fiscal o contable exigen confirmación reforzada, según
      `docs/ui-ux/00-principios-de-diseno.md` principio 4.

## 4. Integración externa (pasarela de pago, correo, mensajería, servicio de terceros)

- [ ] Todo webhook entrante verifica la firma (HMAC u otro mecanismo del proveedor) en tiempo
      constante antes de procesar el cuerpo. Sin firma válida, no hay procesamiento.
- [ ] El webhook es idempotente por el identificador de evento del proveedor, con índice único.
- [ ] La conexión saliente verifica el certificado TLS y el nombre de host. Queda prohibido
      cualquier gestor de confianza que acepte todo o verificador de nombre de host permisivo.
- [ ] El destino de toda llamada saliente está en una lista blanca explícita, a través del cliente
      saneado de `shared/security`. Ninguna URL ni host llega desde datos de entrada del usuario
      sin pasar por esa lista (control contra SSRF).
- [ ] El secreto de la integración vive en el gestor de secretos o en variables de entorno, nunca
      en el repositorio, y nunca se registra en logs.
- [ ] La respuesta del proveedor externo se valida (DTO con Bean Validation o validación explícita)
      antes de usarse. Nunca se confía en su forma por contrato implícito.
- [ ] El endpoint que recibe el webhook tiene su propio controlador y su propio actor de sistema
      con un permiso único y mínimo. No reutiliza un controlador general.

## 5. Tarea en segundo plano

Los trabajos en segundo plano usan db-scheduler sobre PostgreSQL (ADR-0016), sin intermediario de
mensajes ni colas en Redis.

- [ ] La tarea se programa desde un caso de uso, dentro del componente transaccional y en la misma
      transacción que el cambio de negocio. Una transacción revertida no deja la tarea.
- [ ] Solo `confia-worker` ejecuta la tarea; los procesos administrativo y del portal solo la
      programan.
- [ ] Los datos de la tarea contienen únicamente identificadores (institución, entidad, clave de
      idempotencia). Nunca importes, nombres, correos, teléfonos ni documentos de identidad.
      Existe la prueba de lista aprobada de campos del tipo de tarea.
- [ ] El manejador recalcula todo importe desde la base de datos en el momento de ejecutar, nunca
      confía en un valor que viajó con la tarea.
- [ ] El manejador ejecuta a través del componente transaccional con el contexto de la institución
      indicada en la tarea y el actor `system`, auditado igual que un actor humano.
- [ ] El tipo de tarea declara máximo de intentos y espera exponencial. Una tarea agotada queda
      fallida con alerta; nunca se descarta en silencio.
- [ ] El manejador es idempotente por restricción única en la base, con prueba de doble ejecución.
      Si escribe dinero o un documento fiscal, sigue además las reglas de un endpoint financiero.
- [ ] Correo, PDF y proveedores externos no se ejecutan dentro de la transacción financiera ni con
      el bloqueo de la cuenta tomado.
- [ ] La tarea lleva `requestId` y `correlationId` para poder reconstruir su origen ante una
      auditoría.
- [ ] Ningún `@Scheduled`, `@EnableScheduling` ni `@Async` en código de producción.

## 6. Patrones peligrosos: detectar y rechazar en revisión

### Confiar en el cliente para autorización o para un importe

```java
// WRONG: role and amount arrive from the client and are used as-is
public void approveDiscount(ApproveDiscountRequest request) {
    if ("admin".equals(request.role())) { /* ... */ }
}

// CORRECT: the actor comes from the session verified on the server,
// the amount is recalculated by the domain
public void approveDiscount(AuthenticatedActor actor, ChargeId chargeId) {
    if (!actor.hasPermission(ScholarshipsPermissions.APPROVE)) {
        throw new AccessDeniedException(ScholarshipsPermissions.APPROVE);
    }
    Money amount = discountCalculator.calculateFor(chargeId);
}
```

### Concatenación de SQL

```java
// WRONG: direct SQL injection
String sql = "SELECT * FROM student WHERE code = '" + input.code() + "'";

// WRONG: the same injection through jOOQ's plain SQL API
dsl.fetch("SELECT * FROM students_student WHERE code = '" + input.code() + "'");

// CORRECT: jOOQ DSL, every value travels as a bound parameter (ADR-0015).
// Generated table names are illustrative, fixed in F0.
dsl.selectFrom(STUDENTS_STUDENT)
        .where(STUDENTS_STUDENT.CODE.eq(input.code()))
        .fetchOne();
```

El acceso a datos es jOOQ (ADR-0015). La DSL vincula cada valor como parámetro por construcción. La
API de SQL plano de jOOQ (`DSL.sql`, `DSL.field(String)`, `fetch(String)` y afines) solo se permite
en la lista aprobada de clases de reporte, y aun ahí con parámetros vinculados: una cadena
concatenada dentro de esa API es tan inyectable como en JDBC. ArchUnit y la regla de Semgrep
`no-raw-sql-concat` lo verifican.

### Serializar la entidad directamente

```java
// WRONG: leaks every present or future column, including a password hash
return ResponseEntity.ok(studentRow);

// CORRECT: explicit DTO
return ResponseEntity.ok(StudentMapper.toResponse(student));
```

### Verificación de propiedad ausente o delegada al repositorio

```java
// WRONG: any authenticated id can read any student
public Statement getStatement(StudentId studentId) {
    return ledger.statementOf(studentId);
}

// CORRECT: the use case verifies the relationship before acting
public Statement getStatement(AuthenticatedActor actor, StudentId studentId) {
    guardianLinks.assertOwnership(actor, studentId); // throws NotFoundException when it does not apply
    return ledger.statementOf(studentId);
}
```

### Datos personales o secretos en logs

```java
// WRONG
log.info("Login attempt for {} with password {}", email, password);
log.error("Payment failed, authorization header {}", request.getHeader("Authorization"));

// CORRECT
log.info("Login attempt, actorRef={}, outcome={}", hashedActorRef, "denied");
```

`System.out`, `System.err` y `printStackTrace` están prohibidos en `apps/api`: evaden el registro
estructurado y su redacción de campos.

### Ausencia de idempotencia en escritura financiera

```java
// WRONG: a network retry duplicates the charge
public LedgerTransaction registerPayment(RegisterPaymentCommand command) {
    return ledger.record(command.toTransaction());
}

// CORRECT
public LedgerTransaction registerPayment(RegisterPaymentCommand command) {
    return ledger.findByIdempotencyKey(command.institutionId(), command.idempotencyKey())
            .orElseGet(() -> ledger.record(command.toTransaction()));
}
```

### Correlativo fiscal con `MAX(...) + 1`

```sql
-- WRONG: race condition, two simultaneous issuances get the same number
SELECT COALESCE(MAX(correlative), 0) + 1 FROM fiscal_document WHERE cai_range_id = ?;

-- CORRECT: see confia-sar-invoicing (row lock on the CAI range inside the issuance transaction)
```

### Manejo de archivos sin restricción

```java
// WRONG: accepts any type and size, stores on the application server disk
Files.write(Path.of("uploads", file.getOriginalFilename()), file.getBytes());

// CORRECT: MIME type and size validated, generated name, S3-compatible storage
String key = "documents/" + institutionId + "/" + UUID.randomUUID() + "." + allowedExtensionOf(file.getContentType());
storage.put(key, file.getInputStream(), MAX_UPLOAD_BYTES);
```

### Secreto embebido

```java
// WRONG
PaymentGatewayClient client = new PaymentGatewayClient("sk_live_4f2a...");

// CORRECT: injected from external configuration (environment or secret manager)
PaymentGatewayClient client = new PaymentGatewayClient(gatewayProperties.apiKey());
```

### Verificación TLS desactivada

Ningún cliente HTTP saliente se construye con un gestor de confianza que acepte cualquier
certificado ni con un verificador de nombre de host que acepte cualquier nombre, ni siquiera "para
probar en local".

### Swagger UI en producción

Swagger UI y el endpoint del OpenAPI se habilitan solo en los perfiles local y de preproducción. Una
prueba por proceso arranca con el perfil de producción y afirma que ambos responden 404 (ADR-0013).

## 7. Antes de dar por terminado

- [ ] Toda autorización se decidió en el servidor, nunca en el cliente.
- [ ] Todo endpoint tiene autorización declarada o marca explícita de ruta pública.
- [ ] Toda consulta con datos de entrada usa parámetros enlazados.
- [ ] Toda entrada se validó con Bean Validation y toda salida pasa por un DTO explícito.
- [ ] Toda escritura financiera es idempotente y transaccional con bloqueo.
- [ ] Toda acción sensible escribe en la bitácora de auditoría en la misma transacción.
- [ ] Ningún log contiene contraseñas, tokens, cabeceras de autorización, documentos de identidad
      ni datos de menores.
- [ ] Ningún secreto vive en el repositorio.
- [ ] Si el cambio toca una tabla con `institution_id`, existe prueba de integración de su política
      de fila con el rol de base de datos restringido, no con el propietario del esquema.
- [ ] Swagger UI y el endpoint del OpenAPI siguen deshabilitados en producción.

---
name: confia-api-conventions
description: Convenciones de la API REST de CONFIA. Se dispara al crear o modificar un endpoint, definir un DTO, diseñar una ruta o versionar la API.
---

# Convenciones de API de CONFIA

REST con OpenAPI 3.1, versionado por ruta bajo `/api/v1`. El OpenAPI generado desde el código del
backend con springdoc-openapi es la fuente de verdad del contrato. `packages/contracts` contiene los
tipos TypeScript y los esquemas Zod generados con orval a partir de ese OpenAPI, consumidos por
`apps/admin-web`, `apps/portal-web` y la futura app móvil; nunca se edita a mano. Ver
`docs/01-arquitectura.md` sección 7 y ADR-0013.

En el backend, la entrada se valida con Jakarta Bean Validation sobre el DTO de entrada y la salida
es siempre un DTO explícito. Swagger UI y el endpoint del OpenAPI solo existen en los perfiles local
y de preproducción.

## 1. Rutas y versionado

- Prefijo de versión: `/api/v1/...`. Ninguna ruptura de contrato sin nueva versión de ruta.
- **Recursos en plural y en inglés.** `payments`, `students`, `guardians`, `invoices`,
  `credit-notes`, `cashbox-sessions`, `scholarships`. Nunca en español, nunca en singular.
- Los subrecursos anidan cuando la relación es de pertenencia real, con un máximo de un nivel de
  anidación en la ruta:

```
GET    /api/v1/students/{studentId}/charges
POST   /api/v1/students/{studentId}/payments
GET    /api/v1/cashbox-sessions/{sessionId}/movements
```

Una relación que no es de pertenencia (por ejemplo, un pago referencia un cargo pero no pertenece
exclusivamente a él) se expone como recurso propio con el identificador como filtro, no como ruta
anidada: `GET /api/v1/payments?chargeId=...`.

```
// INCORRECTO: verbo en la ruta, español mezclado con inglés
POST /api/v1/pagos/registrar
GET  /api/v1/estudiante/{id}/estadoCuenta

// CORRECTO
POST /api/v1/payments
GET  /api/v1/students/{studentId}/account-summary
```

## 2. Verbos HTTP y códigos de estado

| Operación | Verbo | Éxito | Casos de error típicos |
|---|---|---|---|
| Listar colección | `GET /resource` | `200` con envoltorio de paginación | `400` filtro inválido |
| Leer un recurso | `GET /resource/{id}` | `200` | `404` no existe o no pertenece al actor |
| Crear | `POST /resource` | `201` con `Location` y el recurso creado | `422` validación de dominio, `409` conflicto de idempotencia |
| Actualizar campos no financieros | `PATCH /resource/{id}` | `200` con el recurso actualizado | `404`, `422` |
| Reemplazo completo | `PUT /resource/{id}` | `200` | `404`, `422` |
| Anular / reversar | `POST /resource/{id}/void` | `200` con el recurso anulado, o `202` si el efecto es asíncrono | `404`, `409` ya anulado, `422` motivo faltante |
| Aprobar | `POST /resource/{id}/approve` | `200` | `403` aprobador igual al iniciador, `404`, `409` ya resuelto |
| Exportar | `POST /resource/export` | `202` encolado, con ubicación de consulta del resultado | `422` filtro inválido |
| Sin autenticar | cualquiera | — | `401` |
| Autenticado sin permiso | cualquiera | — | `403` |
| Objeto ajeno al actor | cualquiera | — | `404`, nunca `403` (ver `confia-security-checklist`) |
| Clave de idempotencia repetida con carga distinta | `POST` financiero | — | `409` (ver sección 4) |
| Límite de tasa excedido | cualquiera | — | `429` con `Retry-After` |

**No existe `DELETE` sobre ningún recurso financiero.** La anulación es siempre `POST
.../void`, nunca un borrado HTTP. Ver `confia-ledger-invariants`.

## 3. Problem Details (RFC 9457)

Todo error usa el formato Problem Details. Nunca se devuelve una traza de pila ni un mensaje de
base de datos.

```jsonc
// 422 Unprocessable Entity: violación de una regla de dominio
{
  "type": "https://confia.example/problems/payment-invalid-amount",
  "title": "El importe del pago no es válido",
  "status": 422,
  "detail": "El importe debe ser positivo. Se recibió -150.00 HNL.",
  "instance": "/api/v1/payments",
  "traceId": "9f3e2c1a-7b4d-4e2a-8f1a-2d6b7c9e0a11"
}
```

```jsonc
// 409 Conflict: reutilización de Idempotency-Key con carga útil distinta
{
  "type": "https://confia.example/problems/idempotency-key-conflict",
  "title": "La clave de idempotencia ya se usó con una solicitud distinta",
  "status": 409,
  "detail": "Idempotency-Key 'a1c9-...' ya registró una operación con un cuerpo diferente al enviado.",
  "instance": "/api/v1/payments",
  "traceId": "9f3e2c1a-7b4d-4e2a-8f1a-2d6b7c9e0a11"
}
```

```jsonc
// 404: objeto que existe pero no pertenece al actor. Nunca 403 en este caso.
{
  "type": "https://confia.example/problems/resource-not-found",
  "title": "Recurso no encontrado",
  "status": 404,
  "detail": "No existe un estudiante con ese identificador visible para este actor.",
  "instance": "/api/v1/students/7c2e.../account-summary",
  "traceId": "9f3e2c1a-7b4d-4e2a-8f1a-2d6b7c9e0a11"
}
```

La traducción de errores de dominio a Problem Details la hace un único traductor global del
backend (por ejemplo, un `@RestControllerAdvice` que devuelve `ProblemDetail` de Spring; el
mecanismo exacto se valida en F0). Ningún controlador arma su propio cuerpo de error.

Cada tipo de error de dominio tiene un `type` estable y documentado en OpenAPI. `traceId` siempre
está presente y corresponde al identificador de traza de observabilidad, para poder correlacionar
un reclamo del usuario con los logs del servidor sin exponer el log en sí.

## 4. Cabecera `Idempotency-Key`

Obligatoria en todo endpoint que mueva dinero o emita un documento fiscal. Comportamiento exacto:

1. El cliente genera una clave única por intención de operación (recomendado: UUID v4) y la envía
   en la cabecera `Idempotency-Key`.
2. El servidor busca una operación previa con la misma clave, dentro del mismo `institutionId` (o
   ámbito equivalente).
3. **Si no existe**: procesa la operación y persiste el resultado asociado a la clave, dentro de la
   misma transacción que el efecto de negocio.
4. **Si existe con el mismo cuerpo de solicitud** (comparado por un hash canónico del payload):
   devuelve la respuesta original guardada, con el mismo código de estado, **sin repetir el
   efecto.**
5. **Si existe con un cuerpo distinto**: responde `409 Conflict` con Problem Details indicando el
   conflicto de idempotencia. Nunca se procesa la segunda solicitud ni se sobrescribe la primera.
6. Una clave de idempotencia tiene una ventana de retención razonable (documentada en el módulo
   correspondiente); expirada la ventana, la misma clave puede reutilizarse como si fuera nueva.

```java
// CORRECT: compare the canonical body hash before deciding replay or conflict.
Optional<StoredOperation> existing = idempotency.find(institutionId, key);
if (existing.isPresent()) {
    String incomingHash = CanonicalHash.of(request);
    if (!existing.get().requestHash().equals(incomingHash)) {
        throw new IdempotencyKeyConflictException(key);
    }
    return existing.get().response(); // exact replay, the effect is not repeated
}
```

Ver `confia-money-rules` y `confia-ledger-invariants` para dónde vive la garantía transaccional
detrás de esta cabecera.

## 5. Paginación por cursor

Todo listado potencialmente grande usa paginación por cursor, nunca por número de página con
`OFFSET` creciente, y nunca trae el conjunto completo para paginar en el cliente.

```
GET /api/v1/payments?limit=25&cursor=eyJpZCI6IjdjMmUu...
```

```jsonc
// Respuesta
{
  "data": [ /* hasta 25 elementos */ ],
  "pagination": {
    "nextCursor": "eyJpZCI6ImE5ZjEu...",
    "hasMore": true,
    "limit": 25
  }
}
```

- `limit` tiene un valor por defecto y un tope máximo aplicado en servidor (por ejemplo 100). Un
  `limit` mayor al tope se recorta, no se rechaza.
- `cursor` es un valor opaco para el cliente (codificado, no un número de página ni un offset
  crudo), derivado de la clave de ordenamiento del último elemento de la página anterior.
- `hasMore` y `nextCursor` son ausentes o `null` cuando no hay página siguiente.
- El total exacto de filas **no** se calcula en cada página por defecto, porque es costoso sobre
  tablas grandes; si la interfaz necesita un conteo, se expone como un campo aparte y explícito,
  documentado como potencialmente costoso.

## 6. Filtrado y ordenamiento

- Los filtros son parámetros de consulta con nombre explícito y tipo validado en el backend con
  Bean Validation, declarados en el OpenAPI: `?status=OVERDUE&gradeId=...&from=2026-01-01&to=2026-01-31`.
- El ordenamiento usa un parámetro `sort` con el nombre del campo y prefijo `-` para descendente:
  `?sort=-occurredAt`. Solo se aceptan campos de una lista blanca explícita por recurso; un campo
  fuera de la lista responde `400`.
- El filtrado y el ordenamiento ocurren siempre en el servidor. Nunca se trae un conjunto amplio
  para filtrar u ordenar en el navegador.

## 7. Fechas

Toda fecha y hora en la API es **ISO 8601 con zona horaria explícita**, nunca una fecha ingenua sin
zona.

```jsonc
// INCORRECTO
{ "occurredAt": "2026-09-10 14:32:00" }

// CORRECTO
{ "occurredAt": "2026-09-10T14:32:00-06:00" }
```

Una fecha sin componente de hora, cuando el dominio la exige así (por ejemplo un rango de vigencia
por día), usa `YYYY-MM-DD` y su documentación en OpenAPI declara explícitamente que es una fecha
sin hora, para que el consumidor no le atribuya zona horaria.

## 8. Dinero serializado

Todo importe cruza la API como cadena decimal acompañada de su moneda. Nunca como número JSON.

```jsonc
{ "amount": "1250.15", "currency": "HNL" }
```

Razón completa en `confia-money-rules` sección 9. Esta skill no repite el detalle: en el backend,
cualquier DTO que declare un campo de dinero usa el único DTO de dinero del proyecto, con la forma
`{ amount: string, currency: string }`, y nunca redefine su propio patrón. Los campos de
presentación salen ya redondeados por el servidor a la escala menor de la moneda (dos decimales
para HNL y USD) con `HALF_UP`. El OpenAPI generado declara esa forma, y la prueba de contrato falla
si algún campo monetario aparece como `number`.

## 9. Documentación OpenAPI obligatoria

Todo endpoint nuevo o modificado:

- [ ] Declara sus parámetros, cuerpo de solicitud y **todas** las respuestas posibles, incluidas
      las de error (`401`, `403` si aplica, `404`, `409`, `422`, `429`).
- [ ] El esquema sale de los DTO y del controlador reales, generado con springdoc-openapi. Nunca
      se escribe ni se duplica a mano.
- [ ] Documenta si requiere `Idempotency-Key` y si soporta paginación por cursor.
- [ ] El OpenAPI generado se compara en integración continua contra la instantánea aprobada; toda
      diferencia se declara en el cambio (`docs/06-estrategia-de-testing.md`, sección 14.1).
- [ ] `packages/contracts` se regeneró con orval y las aplicaciones web compilan contra él.

## 10. Ejemplo completo: registrar un pago

```http
POST /api/v1/payments HTTP/1.1
Authorization: Bearer <token>
Idempotency-Key: 3fa4c9de-6b7a-4b1e-9a2f-8d0c1e2b3a44
Content-Type: application/json

{
  "studentId": "7c2e1a4b-9f3d-4e2a-8f1a-2d6b7c9e0a11",
  "amount": "3500.00",
  "currency": "HNL",
  "method": "CASH",
  "cashboxSessionId": "a9f1e2b3-4c5d-4e6f-8a9b-0c1d2e3f4a5b"
}
```

```http
HTTP/1.1 201 Created
Location: /api/v1/payments/9d1e8c7b-6a5f-4e3d-2c1b-0a9f8e7d6c5b
Content-Type: application/json

{
  "id": "9d1e8c7b-6a5f-4e3d-2c1b-0a9f8e7d6c5b",
  "studentId": "7c2e1a4b-9f3d-4e2a-8f1a-2d6b7c9e0a11",
  "amount": "3500.00",
  "currency": "HNL",
  "method": "CASH",
  "status": "CONFIRMED",
  "occurredAt": "2026-09-10T14:32:00-06:00",
  "allocations": [
    { "chargeId": "4b3a2c1d-...", "amount": "3500.00", "currency": "HNL" }
  ]
}
```

```http
POST /api/v1/payments HTTP/1.1
Idempotency-Key: 3fa4c9de-6b7a-4b1e-9a2f-8d0c1e2b3a44
Content-Type: application/json

{ "studentId": "7c2e1a4b-...", "amount": "1.00", "currency": "HNL", "method": "CASH", "cashboxSessionId": "a9f1..." }
```

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{
  "type": "https://confia.example/problems/idempotency-key-conflict",
  "title": "La clave de idempotencia ya se usó con una solicitud distinta",
  "status": 409,
  "detail": "Idempotency-Key '3fa4c9de-...' ya registró un pago de 3500.00 HNL.",
  "instance": "/api/v1/payments",
  "traceId": "b1c2d3e4-..."
}
```

## 11. Antes de dar por terminado

- [ ] La ruta usa recursos en plural, en inglés, bajo `/api/v1`.
- [ ] El código de estado corresponde exactamente al caso, según la tabla de la sección 2.
- [ ] Los errores siguen el formato Problem Details con `type` estable y `traceId`.
- [ ] Si la operación mueve dinero o emite documento fiscal, exige `Idempotency-Key` y maneja los
      tres casos: nuevo, replay, conflicto.
- [ ] Los listados grandes usan paginación por cursor, con filtrado y ordenamiento en servidor.
- [ ] Toda fecha lleva zona horaria explícita.
- [ ] Todo importe sale como cadena con su moneda, usando el DTO de dinero único del backend, y
      los campos de presentación salen redondeados por el servidor.
- [ ] El endpoint está documentado en OpenAPI con todas sus respuestas posibles.

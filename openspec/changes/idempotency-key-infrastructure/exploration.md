# Exploración: infraestructura de clave de idempotencia

Cambio 6 de la fase F0. Cierra la brecha B7 y el criterio de salida 4: «dos solicitudes con la misma
clave de idempotencia producen un solo efecto y la misma respuesta».

> **Nota de persistencia.** El agente de exploración no dispuso de herramienta de escritura de
> archivos, así que su informe quedó solo en Engram (`sdd/idempotency-key-infrastructure/explore`,
> #471). El orquestador lo transcribió aquí. El agente además fue cortado por el límite semanal de la
> cuenta justo después de guardar, así que este documento es su trabajo completo, no un resumen.

## 1. Dónde vive: delta de `build-integrity`, no capacidad nueva

Evidencia verificada, no analogía:

- `docs/01-arquitectura.md` coloca la idempotencia dentro del árbol `shared/security`, junto al
  componente transaccional, no como módulo de negocio propio.
- `openspec/specs/build-integrity/spec.md` ya tiene el requisito «Contexto de sesión, nivel de
  aislamiento y reintento acotado del componente transaccional único». La idempotencia es la misma
  clase de garantía mecánica, verificable por `./mvnw verify`, no un comportamiento de negocio.
- La matriz de permisos de `docs/03-seguridad.md` §5.2 **no** tiene fila para idempotencia, mientras
  que `audit` sí la tiene. No hay verbo de negocio que la consuma como capacidad.
- `openspec/specs/payments/spec.md` ya escribe su propio requisito «Registro de pago con clave de
  idempotencia» a nivel de capacidad consumidora. Eso confirma el reparto: el comportamiento
  observable se declara en cada capacidad que lo usa; `build-integrity` declara la garantía mecánica
  de la infraestructura compartida.

## 2. Qué se almacena y dónde

**ADR-0010 ya propone un esquema `idempotency_keys`** con `UNIQUE(endpoint, idempotency_key)`. Ese
ADR es anterior a ADR-0015 y ADR-0017, y **viola dos puertas que hoy están en verde**:

- `MultiTenantSchemaIT.everyUniqueIndexOfABusinessTableIncludesTheInstitutionDiscriminator`: el
  índice único del ADR no incluye `institution_id`.
- La regla de prefijo de módulo (ADR-0015 regla 3): el nombre `idempotency_keys` no lleva `shared_`.

Recomendación: tabla `shared_idempotency_key`, en singular, siguiendo el precedente de
`shared_audit_log` y `organization_institution`, con **clave primaria natural compuesta**
`(institution_id, endpoint, idempotency_key)`. Eso resuelve el discriminador por construcción y
evita necesitar un identificador sustituto generado: `pgcrypto` no está disponible, confirmado por
la sonda S6 del cambio 5. Seguridad de fila forzada con el mismo patrón `NULLIF` de V1 a V3.

`confia_admin_app` necesita `SELECT`, `INSERT` y `UPDATE` —este último para pasar de «en curso» a
«completado»—, que ya cabe en lo que `docs/03` §6.1 le concede, sin excepción de ADR-0017.

## 3. Comportamiento de la segunda solicitud

ADR-0010 documenta la tabla completa: clave nueva ejecuta; misma clave con misma carga y completada
devuelve 200 con `Idempotent-Replay: true` y la respuesta original; misma clave en curso devuelve
409; misma clave con carga distinta devuelve 422; clave de más de 24 horas se trata como nueva.
**Está en documentación y no hay implementación**, así que nada de eso está verificado en código.

### Tensión de diseño detectada, no verificada todavía

ADR-0010 dice que el marcador se inserta «dentro de la misma transacción» que el efecto. Pero bajo un
índice único de PostgreSQL, un segundo `INSERT` con la misma clave **se bloquea** hasta que la
primera transacción resuelva, no falla de inmediato. Si eso es así, la solicitud concurrente quedaría
esperando en el hilo HTTP en vez de recibir el 409 inmediato que narra el ADR.

**Necesita sonda dedicada antes de implementar.** Es el riesgo técnico principal del cambio.

## 4. Relación con el componente transaccional

Verificado en código: el Javadoc de `TransactionRunner` dice literalmente que ningún proceso lo
registra todavía como bean, y que registrarlo «es trabajo del primer cambio que lo consuma (cambio
6)». La propuesta del cambio 5 lo confirma: «el cambio 6 no puede escribir idempotencia sin una
transacción única».

Este cambio debe entonces registrar `TransactionRunner` como bean donde haga falta, y construir el
componente que escribe el marcador y el efecto de negocio **dentro de la misma llamada** a
`execute(...)`. Hoy esa firma no tiene ninguna noción de idempotencia: hace falta una API nueva, no
solo una tabla y un interceptor.

## 5. Qué es demostrable hoy sin endpoint real

No existe ningún endpoint que mueva dinero; F3 y F4 no han empezado y `payments/spec.md` está en
borrador. Pero el criterio de salida 4 exige una demostración.

Precedente reutilizable: `organization_institution` ya se usa como sustrato de escritura real en
`TransactionRunnerRetryIT`, que actualiza `legal_name`. Recomendación: reutilizar ese mismo camino
como sustrato de demostración, en vez de inventar un endpoint financiero fuera de alcance. Se
descartó esperar al primer endpoint real de F1, que rompería el límite de fase.

## 6. Las deudas W1 y W2 no encajan aquí

Ambas son deuda de integración continua y de generación de código, ortogonales a la idempotencia.
Encajan mejor en el cambio 11, que ya toca ese pipeline. Incorporarlas aquí sería relleno que diluye
la revisión de un cambio que ya trae de ocho a nueve tareas.

## Riesgos

1. El esquema de ADR-0010 viola dos puertas ya en verde. Necesita corrección, probablemente como nota
   editorial fechada sobre el ADR, siguiendo el precedente del cambio 5, no reescritura del cuerpo.
2. **La semántica de concurrencia del 409 no es trivialmente compatible con el bloqueo real del
   índice único de PostgreSQL.** Necesita sonda de diseño.
3. `TransactionRunner` no tiene API consciente de idempotencia. Es su primer consumidor real.
4. No hay endpoint financiero para demostrar el criterio de salida 4: hace falta decidir el sustrato.
5. La limpieza por expiración depende de db-scheduler, del cambio 9, que a su vez depende de este.
   La purga física queda fuera; solo se puede modelar `expires_at`.
6. `pgcrypto` no está disponible. La clave primaria natural compuesta evita necesitarlo.

## Listo para propuesta

Sí, con cinco decisiones explícitas pendientes: el nombre y la forma de la clave primaria, el
mecanismo real de concurrencia para el caso «en curso», el sustrato de demostración del criterio de
salida 4, la exclusión explícita de W1 y W2, y la forma exacta de la API de `TransactionRunner` para
envolver marcador y efecto en una sola transacción.

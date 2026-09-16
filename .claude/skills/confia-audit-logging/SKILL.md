---
name: confia-audit-logging
description: Bitácora de auditoría de CONFIA. Se dispara ante cualquier acción sensible, cambio de configuración, movimiento de dinero, cambio de permisos o de rol, o acceso a datos personales de estudiantes y encargados.
---

# Bitácora de auditoría de CONFIA

`shared/audit/` implementa la bitácora encadenada por hash. Es la evidencia que sostiene tanto una
acusación como la defensa de un empleado honesto, y la única fuente que un auditor externo o el
SAR van a aceptar. Fuente de verdad completa: `docs/03-seguridad.md` sección 12.

## 1. Qué eventos se auditan obligatoriamente

| Categoría | Eventos |
|---|---|
| Identidad | Inicio de sesión exitoso y fallido, cierre de sesión, cambio de contraseña, restablecimiento, alta y baja de MFA, uso de código de recuperación, bloqueo por retroceso exponencial |
| Autorización | Todo acceso denegado por permiso (`outcome = 'denied'`), alta y baja de usuario, asignación y revocación de rol, cambio en la definición de un rol |
| Dinero | Creación de pago, reverso de pago, creación de cargo, aplicación de descuento o beca, ajuste manual, baja por incobrable, reembolso, todo asiento del libro mayor |
| Fiscal | Emisión de factura, emisión de nota de crédito y de débito, anulación, alta de rango CAI, cambio de correlativo actual, cambio de tasa de impuesto, bloqueo de emisión |
| Caja | Apertura de sesión, cada movimiento, cierre, diferencia declarada con su justificación, aprobación de la diferencia |
| Datos personales | Consulta de un expediente completo de estudiante, **toda exportación** con su filtro y conteo de filas, atención de una solicitud de derechos del titular, eliminación o anonimización por retención |
| Configuración | Cambio de cualquier parámetro con impacto financiero o fiscal, cambio de plantilla de notificación, cambio de umbral de alerta, activación o desactivación de bandera de funcionalidad |
| Operación | Ejecución de migración en producción, restauración de respaldo, acceso interactivo a la base de datos de producción, rotación de secretos |

**Regla práctica para decidir si algo nuevo se audita:** si la respuesta a "¿un auditor externo
preguntaría por esto?" es sí, se audita. Ante la duda, se audita: el costo de una fila de más es
insignificante frente al costo de no poder reconstruir qué pasó.

## 2. Estructura del registro de auditoría

```sql
CREATE TABLE audit_log (
    id               BIGSERIAL     PRIMARY KEY,
    institution_id   UUID          NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    actor_id         UUID,                        -- NULL para actor de sistema
    actor_kind       TEXT          NOT NULL,      -- 'staff' | 'guardian' | 'system'
    actor_label      TEXT          NOT NULL,      -- correo o nombre de trabajo, para lectura
    source_ip        INET,
    user_agent       TEXT,
    request_id       UUID          NOT NULL,
    trace_id         TEXT,
    action           TEXT          NOT NULL,      -- 'invoice.void', 'payment.create', ...
    entity_type      TEXT          NOT NULL,
    entity_id        TEXT          NOT NULL,
    outcome          TEXT          NOT NULL,      -- 'success' | 'denied' | 'error'
    before_value     JSONB,                       -- redactado, sin datos sensibles en claro
    after_value      JSONB,
    reason           TEXT,                        -- obligatorio en anulación y ajuste
    approver_id      UUID,                        -- segregación de funciones
    prev_hash        BYTEA         NOT NULL,
    row_hash         BYTEA         NOT NULL,
    CONSTRAINT audit_log_outcome_chk
        CHECK (outcome IN ('success', 'denied', 'error')),
    CONSTRAINT audit_log_actor_kind_chk
        CHECK (actor_kind IN ('staff', 'guardian', 'system'))
);

CREATE INDEX audit_log_entity_idx     ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX audit_log_actor_idx      ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX audit_log_action_idx     ON audit_log (action, occurred_at DESC);
CREATE INDEX audit_log_request_idx    ON audit_log (request_id);
```

Cada fila identifica: quién (`actor_id`, `actor_kind`, `actor_label`), desde dónde (`source_ip`,
`user_agent`), qué solicitud (`request_id`, `trace_id`), qué acción sobre qué entidad
(`action`, `entity_type`, `entity_id`), con qué resultado (`outcome`), qué cambió
(`before_value`, `after_value`), por qué (`reason`, obligatorio en anulación y ajuste) y quién
aprobó cuando aplica segregación de funciones (`approver_id`).

## 3. Encadenamiento por hash y su verificación

Cada fila incluye el hash de la anterior, formando una cadena. Alterar o eliminar cualquier fila
rompe la cadena a partir de ese punto y la verificación periódica lo detecta.

```
row_hash = SHA256(
    prev_hash ||
    canonical_json({ id, institution_id, occurred_at, actor_id, actor_kind,
                     source_ip, request_id, action, entity_type, entity_id,
                     outcome, before_value, after_value, reason, approver_id })
)
```

La serialización canónica ordena las claves y normaliza los números, para que el hash sea
reproducible. La cadena se inicia con un registro génesis cuyo `prev_hash` son 32 bytes de ceros.
**El cálculo del hash y la inserción ocurren en un disparador `BEFORE INSERT` de PostgreSQL, no en
la aplicación**, de modo que una escritura por cualquier vía queda encadenada, incluida una
escritura directa que evadiera el código de la aplicación.

**Ancla externa.** Cada hora, una tarea recurrente de db-scheduler en `confia-worker` (ADR-0016) publica el `row_hash` de la última fila y su `id` en un
almacenamiento de objetos con bloqueo de objeto en modo de cumplimiento. Un atacante que
recalculara toda la cadena dentro de la base de datos no puede alterar el ancla ya publicada, y la
divergencia queda demostrada.

**Verificación periódica**, en tres cadencias:

- **Diaria:** recalcula la cadena completa del último mes.
- **Semanal:** verificación completa desde el registro génesis.
- **Horaria:** compara el último `row_hash` contra la última ancla publicada.

Una discrepancia genera **alerta de severidad S1** y dispara
`docs/runbooks/incidente-de-seguridad.md`. **No se intenta reparar la cadena: se preserva la
evidencia.** Igual que ante un descuadre del libro mayor (ver `confia-ledger-invariants`), la
respuesta correcta es detenerse y escalar, nunca corregir en caliente.

Nota honesta sobre el límite del control: un `SUPERUSER` de PostgreSQL puede deshabilitar el
disparador que calcula el hash. Por eso existen el ancla externa horaria y la copia periódica a
almacenamiento de solo escritura. El objetivo alcanzable no es hacer la manipulación imposible,
sino hacerla **evidente**.

## 4. Qué campos se redactan

`before_value` y `after_value` nunca contienen en claro: contraseñas, secretos de MFA, tokens,
cabeceras de autorización, números de documento de identidad, números de tarjeta ni el contenido
íntegro de un dato de menor de edad más allá de lo estrictamente necesario para identificar qué
cambió. Un cambio sobre un campo sensible registra que el campo cambió (nombre del campo, y un
valor enmascarado o un hash cuando haga falta comparar), nunca el valor sensible completo en ambos
lados.

```java
// WRONG: previous and new values end up in clear text in audit_log
audit.append(new AuditEntry("guardian.update", guardian, updated));

// CORRECT: the audit event redacts before persisting
audit.append(AuditEvent.guardianUpdated(guardian.id(), SensitiveFields.redact(guardian, updated), actor));
```

## 5. Permisos de base de datos que impiden borrado y actualización

Dos barreras independientes, ninguna suficiente por sí sola:

```sql
-- Denegación de partida
REVOKE ALL ON audit_log FROM PUBLIC;

-- La aplicación solo puede insertar y leer
GRANT SELECT, INSERT ON audit_log TO confia_admin_app;
GRANT USAGE, SELECT   ON SEQUENCE audit_log_id_seq TO confia_admin_app;

-- El portal no tiene acceso alguno: no se emite ningún GRANT para confia_portal_app

-- Segunda barrera: un disparador que rechaza a nivel de motor,
-- incluso para el propietario del esquema.
CREATE OR REPLACE FUNCTION audit_log_is_append_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'audit_log es de solo inserción: % rechazado', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_log_no_update  BEFORE UPDATE   ON audit_log FOR EACH ROW      EXECUTE FUNCTION audit_log_is_append_only();
CREATE TRIGGER audit_log_no_delete  BEFORE DELETE   ON audit_log FOR EACH ROW      EXECUTE FUNCTION audit_log_is_append_only();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log FOR EACH STATEMENT EXECUTE FUNCTION audit_log_is_append_only();
```

Esto aplica al rol de aplicación **y ningún rol de aplicación tiene el atributo `BYPASSRLS`**. Ver
`confia-security-checklist` y `docs/03-seguridad.md` sección 6.1 para la tabla completa de roles.

## 6. Cómo se escribe la auditoría dentro de la misma transacción de negocio

**La auditoría se escribe con la misma garantía de atomicidad que el efecto que audita.** Si el
asiento del libro mayor se confirma pero la fila de auditoría no, o al revés, el sistema queda con
una fila financiera sin rastro o con un rastro de un efecto que no ocurrió. Ninguna de las dos
situaciones es aceptable.

```java
// CORRECT: audit inside the same transaction as the business effect.
// If the audit write fails, the whole transaction rolls back
// and the payment is not recorded either.
// TransactionRunner is an illustrative placeholder for the single transaction component of
// shared/security, fixed in F0 (ADR-0015).
public Payment handle(RegisterPaymentCommand command) {
    return transactions.run(TxIsolation.READ_COMMITTED, () -> {
        Payment payment = payments.save(Payment.register(command));
        audit.append(AuditEvent.paymentRegistered(payment, command.actor()));
        return payment;
    });
}

// WRONG: the audit write runs after commit, in a separate transaction
// (or asynchronously). A failure between both leaves the payment untraced.
public Payment handle(RegisterPaymentCommand command) {
    Payment payment = registerInItsOwnTransaction(command);
    audit.append(AuditEvent.paymentRegistered(payment, command.actor())); // outside the tx
    return payment;
}
```

La transacción la abre siempre el componente transaccional único de `shared/security` (ADR-0015);
`@Transactional` y `TransactionTemplate` están prohibidos fuera de él. La escritura de auditoría
usa el mismo acceso con jOOQ y participa en esa misma transacción. La regla no cambia: auditoría y
efecto de negocio se confirman o se revierten juntos.

**Qué pasa si la escritura de auditoría falla:** la transacción completa se revierte, incluido el
efecto de negocio. Un pago, un asiento o un cambio de configuración que no puede auditarse no
ocurre. No existe un modo "mejor esfuerzo" para la auditoría de una acción sensible: silenciar el
fallo de auditoría para no bloquear la operación de negocio recrea exactamente el escenario que
esta bitácora existe para prevenir.

```java
// WRONG: the audit failure is caught and swallowed so the payment is not blocked
try {
    audit.append(event);
} catch (RuntimeException e) {
    log.warn("audit write failed, continuing anyway");
}
```

## 7. Bitácora de auditoría contra logs de aplicación

Son dos sistemas distintos, con propósitos distintos, y no se sustituyen entre sí:

| | Bitácora de auditoría (`audit_log`) | Logs de aplicación (registro estructurado de Spring Boot) |
|---|---|---|
| Propósito | Evidencia legal y contable de una acción sensible | Diagnóstico operativo y depuración |
| Mutabilidad | Solo inserción, encadenada por hash, verificada | Rotables, pueden purgarse por retención |
| Contenido | Estructurado, campos fijos, sin datos personales en claro | Estructurado en JSON, con contexto de depuración |
| Quién la lee | Auditor, Super Administrador, un ente regulador en una disputa | El propio desarrollador, herramientas de observabilidad |
| Retención | Larga, alineada a la retención fiscal y legal | Corta, alineada a necesidad operativa |
| Acceso | Rol `audit:read`, con su propia auditoría de acceso | Acceso operativo estándar |
| Qué pasa si falla su escritura | La transacción de negocio se revierte completa | Se degrada, nunca bloquea la operación de negocio |

**No registres una acción sensible solo en los logs de aplicación pensando que "ya queda
registrado".**
Un log de aplicación se rota, se puede desactivar por nivel de severidad y no está encadenado ni
protegido contra edición. Solo `audit_log` cumple el estándar de evidencia que este sistema
necesita.

## 8. Antes de dar por terminado

- [ ] La acción nueva está en la tabla de eventos obligatorios de la sección 1, o se justificó por
      qué no aplica.
- [ ] El registro de auditoría se escribe dentro de la misma transacción que el efecto de negocio.
- [ ] Ningún campo sensible (contraseña, token, documento de identidad, dato completo de un menor)
      queda en claro en `before_value` ni en `after_value`.
- [ ] El rol de aplicación que escribe la auditoría no tiene `UPDATE`, `DELETE` ni `TRUNCATE` sobre
      `audit_log`, verificado por prueba de integración.
- [ ] Si la acción exige segregación de funciones, `approver_id` se registra y difiere del actor
      que inició la operación.
- [ ] Existe prueba de integración que confirma que un intento de `UPDATE` o `DELETE` sobre
      `audit_log` con el rol de aplicación es rechazado por el motor.

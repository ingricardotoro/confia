# Corte: encargados y vínculos (`guardians`)

Fase F1, entregable 4. Rama `feat/guardians-registry`, apilada sobre `feat/enrollments` (PR #306).

## Objetivo

El personal registra a los encargados de pago y los vincula a los estudiantes con parentesco y porcentaje de
responsabilidad financiera. Es el dato maestro que luego usan cobranza, facturación y el portal.

## Fuera de alcance

Cuenta o credencial del encargado en el portal, verificación de correo y evento `GuardianLinked` (F6),
consentimiento de comunicaciones y canales de contacto adicionales (siguiente corte), importación masiva.
No hay importes.

## Modelo y reglas

Módulo nuevo `com.confia.guardians` (cuatro capas). Tablas `guardians_guardian` y `guardians_guardian_student`,
con `institution_id`, seguridad por fila forzada, sin `DELETE`.

| Campo del encargado | Regla |
|---|---|
| `firstName`, `lastName` | Obligatorios, 1 a 100 caracteres |
| `nationalId` | Opcional. Cifrado con `ColumnEncryptionService` e índice ciego (misma llave versionada que estudiantes); único por institución cuando existe (`409 duplicate-national-id`). **Nunca se devuelve**: solo `hasNationalId` |
| `rtn` | Opcional, 14 dígitos. Es dato fiscal: cifrado igual que el documento, solo `hasRtn` |
| `email`, `phone`, `address` | Opcionales, formato validado. Sensibles: no van a bitácora ni a logs |
| `preferredChannel` | `EMAIL`, `SMS`, `WHATSAPP`, `NONE` (por defecto `NONE`) |
| `status` | `ACTIVE`, `INACTIVE`. Un encargado inactivo no puede recibir vínculos nuevos |

- Código `P-000001` por institución (contador como estudiantes). Nada se borra: se inactiva.
- **Vínculo** (`GuardianStudent`): `relationship` (`FATHER`, `MOTHER`, `LEGAL_GUARDIAN`, `SELF`, `OTHER`),
  `financialResponsibilityPercent` (`NUMERIC(5,2)`, 0.01 a 100), `isPrimaryPayer`, `isBillingRecipient`,
  `validFrom`, `validTo` (nula mientras está vigente).
- **INV-32:** si un estudiante tiene vínculos vigentes, su suma es exactamente 100. Como el primer vínculo
  nunca podría sumar 100 mientras se crean uno por uno, los vínculos de un estudiante se **reemplazan como
  conjunto atómico** (`PUT`): se valida suma 100.00 (aritmética decimal exacta, nunca `double`), un solo
  pagador principal y un solo destinatario de factura, sin encargado repetido, todos activos. Un estudiante
  sin vínculos es válido (aún sin asignar). Se serializa por estudiante con bloqueo de su fila.
- **Historial:** el reemplazo no borra. Un vínculo ausente o con porcentaje/parentesco/banderas distintos
  cierra el anterior (`validTo` = hoy en Honduras) y abre uno nuevo; uno idéntico se deja igual. Un vínculo
  cerrado nunca se reabre ni se edita.
- Un estudiante inactivo no admite cambios de vínculos (`409 student-not-active`). Un encargado se puede
  inactivar aunque tenga vínculos vigentes, pero el aviso se devuelve y los vínculos siguen (decisión del
  personal; no cuenta como INV-32).

## API

| Operación | Permiso |
|---|---|
| `GET /api/v1/guardians` paginado: `page`, `size` (50, máx. 200), `sort` (`lastName`, `code`, `status`), `q` (nombre, apellido o código), `status` | `GUARDIANS_READ` |
| `GET /api/v1/guardians/{id}` (incluye los estudiantes vinculados vigentes: código, nombres, parentesco, porcentaje) | `GUARDIANS_READ` |
| `POST /api/v1/guardians` → 201 | `GUARDIANS_CREATE` |
| `PUT /api/v1/guardians/{id}` → 200 (datos y estado) | `GUARDIANS_UPDATE` |
| `GET /api/v1/students/{studentId}/guardians` (vínculos vigentes, con suma) | `GUARDIANS_READ` |
| `PUT /api/v1/students/{studentId}/guardians` → 200 (conjunto completo) | `GUARDIANS_UPDATE` |

- Institución desde el token. Sin `Idempotency-Key` (no mueve dinero; el `PUT` de conjunto es idempotente por naturaleza).
- Auditoría `guardians.guardian.created|updated` y `guardians.links.replaced` con códigos, estados, porcentajes y
  nombres de campos cambiados; **sin nombres, documento, RTN, correo, teléfono ni dirección**.
- Límites de tasa con las políticas existentes. Respuestas `Cache-Control: no-store`.
- Errores nuevos: `duplicate-national-id` (409, si no existe ya), `invalid-responsibility-split` (400 con campo y motivo:
  `sum-not-100`, `multiple-primary-payer`, `multiple-billing-recipient`, `duplicate-guardian`), `guardian-not-active` (409).
- `students` y `guardians` no se importan el dominio: `guardians` consulta estudiantes por un puerto publicado por `students`
  (`@NamedInterface`), como se hizo con `organization`.

## Pantallas (`admin-web`)

Entrada «Encargados»: tabla paginada y ordenada en servidor con búsqueda y filtro de estado; crear, editar e
inactivar (documento y RTN solo se escriben, nunca se muestran). En el detalle del estudiante no existe pantalla
aún: el vínculo se gestiona desde «Estudiantes» con la acción «Encargados» por fila, que abre un diálogo con el
conjunto actual, agregar/quitar encargado (buscador), parentesco, porcentaje, pagador principal y destinatario,
suma visible y error claro si no suma 100. Estados de carga, vacío, error y éxito.

## Casos límite a probar

Suma 99.99, 100.01, 100.00 con tres encargados (33.33 + 33.33 + 33.34), 0 vínculos, porcentaje 0 y 100.01, dos pagadores
principales, encargado repetido, encargado inactivo, estudiante inactivo, reemplazo que cambia un porcentaje (historial
cerrado/abierto), reemplazo idéntico (sin cambios ni auditoría de cambio), dos reemplazos simultáneos del mismo estudiante,
documento duplicado (y con otro formato), aislamiento entre instituciones, 403 sin efecto, límites de tasa, ausencia de
datos personales en respuestas, auditoría y logs.

## Criterio de aceptación

Un administrador registra dos encargados, los vincula a un estudiante con 60/40 y luego los cambia a 50/50; el sistema
rechaza 60/30, conserva el historial del primer reparto y nunca muestra el documento.

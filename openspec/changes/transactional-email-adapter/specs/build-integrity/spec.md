# Delta para Integridad de la construcción

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `transactional-email-adapter` (F0, cambio 14a)
- **Propuesta:** `proposal.md`, aprobada el 2026-11-09, con P1 a P8 vinculantes (P2: módulo `notifications`
  mínimo, solo en el trabajador, y regla de ArchUnit del cliente de correo; P3: migración de compensación)

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BI150` en adelante. La especificación vigente termina en `BI118`, pero
  `rbac-permission-matrix-and-audit-integration` y `admin-web-staff-sign-in` ya ocupan números hasta `BI139` en
  sus deltas sin archivar; este cambio deja libre el tramo `BI140` a `BI149` para `password-recovery-endpoints` y
  usa `BI150` a `BI169`.
- «Cliente de correo» son los paquetes `jakarta.mail..`, `org.eclipse.angus..` y `org.springframework.mail..`.
- Los números de migración (`V16` y `V17`) son los del diseño sobre `origin/main` en `af20884`; si otro cambio
  fusiona antes una migración con esos números, se renumeran al rebasar y los escenarios se leen con el número
  nuevo.

## ADDED Requirements

### Requisito: Solo `notifications.infrastructure` depende del cliente de correo

El sistema DEBE romper la construcción si una clase de producción fuera del paquete
`com.confia.notifications.infrastructure` (y sus subpaquetes) depende de un tipo del cliente de correo
(`docs/08` §8.4). La regla DEBE evaluar el árbol real de producción y NO DEBE eximir ningún paquete por nombre
fuera de ese.

#### Escenario: [BI150] La regla pasa con el árbol real y falla con dos fixtures

- **DADO** la regla de confinamiento del cliente de correo
- **CUANDO** se ejecuta con el árbol real, con un fixture de `identity.infrastructure` que importa
  `jakarta.mail.internet.InternetAddress` y con uno de `notifications.application` que importa
  `org.springframework.mail.SimpleMailMessage`
- **ENTONCES** pasa con el real y falla en los dos fixtures, nombrando la clase infractora

#### Escenario: [BI151] Ningún proceso carga la autoconfiguración de correo de Spring Boot

- **DADO** el classpath de producción del artefacto
- **CUANDO** se buscan las autoconfiguraciones de correo de Spring Boot y los beans de tipo emisor de correo en
  los tres procesos
- **ENTONCES** la autoconfiguración no está en el classpath y solo el trabajador tiene un emisor, el que declara
  la configuración de `notifications`

### Requisito: El módulo `notifications` se registra solo en el trabajador

El sistema DEBE registrar `com.confia.notifications` en la lista de permitidos del trabajador de
`ProcessBeanPolicy` y DEBE mantenerlo prohibido en los procesos administrativo y del portal. El módulo DEBE ser un
módulo de Spring Modulith que expone a otros módulos solo su API de envío y su configuración del trabajador, por
interfaces nombradas (ADR-0022), y NO DEBE tener paquete `web` (ADR-0020).

#### Escenario: [BI152] La lista de permitidos del trabajador incluye `notifications` y las demás no

- **DADO** `ProcessBeanIsolationTest` con la política actualizada
- **CUANDO** se ejecuta contra los tres procesos reales
- **ENTONCES** pasa, y con un fixture que añade `com.confia.notifications` al proceso administrativo falla
  nombrando el bean y el paquete

#### Escenario: [BI153] Solo la API nombrada de `notifications` se consume desde `identity`

- **DADO** `SpringModulithVerificationTest`
- **CUANDO** se verifica el árbol real y un fixture en el que `identity.infrastructure` usa un tipo interno de
  `notifications.application`
- **ENTONCES** pasa con el real y falla con el fixture

### Requisito: Tabla `notifications_email_delivery`, con `institution_id` y seguridad de fila forzada

El sistema DEBE crear, en la migración `V16`, la tabla `notifications_email_delivery` con `institution_id NOT
NULL`, clave primaria `(institution_id, id)`, seguridad a nivel de fila habilitada y forzada con la política de
aislamiento por institución, y restricciones `CHECK` que cierran el canal (`email`), el propósito
(`transactional`), el estado (`sent`, `failed`), la clase de fallo (la lista cerrada de `notifications`), la forma
del hash (64 caracteres hexadecimales en minúsculas) y la coherencia entre estado, identificador del proveedor y
clase de fallo. La tabla NO DEBE tener ninguna columna de dirección, asunto, cuerpo ni parámetros. Las pruebas
genéricas de `MultiTenantSchemaIT` DEBEN cubrirla sin exención.

#### Escenario: [BI154] Un rol de aplicación no lee filas de otra institución

- **DADO** una fila de `INS-1` y una de `INS-2` en la tabla
- **CUANDO** `confia_admin_app` lee con el contexto de `INS-1`, y después sin contexto
- **ENTONCES** con contexto ve solo la de `INS-1`, y sin contexto no ve ninguna
- **Y** un `INSERT` de una fila de `INS-2` con el contexto de `INS-1` se rechaza por la política

#### Escenario: [BI155] Las restricciones rechazan filas incoherentes

- **DADO** la tabla vacía
- **CUANDO** se insertan, por separado, una fila `sent` con clase de fallo, una `failed` sin clase, una con canal
  `sms`, una con hash de 63 caracteres y una con clase de fallo `smtp-error`
- **ENTONCES** las cinco se rechazan por su restricción `CHECK`

#### Escenario: [BI156] El catálogo genérico cubre la tabla nueva sin exención

- **DADO** `MultiTenantSchemaIT` con la migración `V16` aplicada
- **CUANDO** se ejecutan sus pruebas de catálogo e inventario
- **ENTONCES** la tabla aparece con `institution_id NOT NULL`, seguridad de fila forzada y prefijo de su módulo,
  sin entrar en ninguna lista de exención

### Requisito: Permisos de acceso a `notifications_email_delivery` por rol de base de datos

El sistema DEBE conceder sobre `notifications_email_delivery`, exactamente y sin uno más ni uno menos: a
`confia_admin_app` (el rol con el que se conecta el trabajador), `SELECT` e `INSERT`, **sin `UPDATE` ni
`DELETE`**; a `confia_portal_app`, ningún privilegio; a `confia_readonly`, solo `SELECT`, sujeto a la política de
fila; a `confia_backup`, el alcance de `pg_read_all_data`; a `confia_owner`, ninguno adicional a los de
propietario, sin `BYPASSRLS`. El sistema DEBE extender `RolePrivilegeMatrixIT` con las filas de la tabla nueva.

#### Escenario: [BI157] La matriz de privilegios cubre la tabla nueva para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de `notifications_email_delivery`
- **CUANDO** se ejecuta
- **ENTONCES** confirma exactamente los privilegios de este requisito para cada rol

#### Escenario: [BI158] `confia_admin_app` inserta y lee, pero no actualiza ni borra

- **DADO** `confia_admin_app` con el contexto de una institución
- **CUANDO** intenta `INSERT` y `SELECT`, y después `UPDATE` y `DELETE`
- **ENTONCES** las dos primeras se permiten y las dos últimas se rechazan por falta de privilegio

### Requisito: Estado de anulación por entrega fallida en `identity_password_reset_token`

El sistema DEBE añadir, en la migración `V17`, la columna `delivery_failed_at TIMESTAMPTZ` nula a
`identity_password_reset_token`; DEBE sustituir la restricción de estado final por una que admite como máximo uno
de `consumed_at`, `superseded_at` y `delivery_failed_at`; y DEBE redefinir el índice único parcial «un token
abierto por cuenta» para que un token anulado por entrega fallida no sea abierto. Los privilegios de la tabla NO
DEBEN cambiar. Las migraciones `V1` a `V16` NO DEBEN editarse.

#### Escenario: [BI159] Un token no puede tener dos estados finales

- **DADO** un token anulado por entrega fallida
- **CUANDO** se intenta marcarlo también como consumido o como superado
- **ENTONCES** la restricción de estado final rechaza la actualización

#### Escenario: [BI160] Un token anulado no ocupa el lugar del token abierto

- **DADO** la cuenta `ACC-7` con un token anulado por entrega fallida
- **CUANDO** se inserta un token abierto nuevo para `ACC-7`
- **ENTONCES** la inserción se permite
- **Y** una segunda inserción abierta para `ACC-7` sigue rechazándose con `23505`

#### Escenario: [BI161] Las migraciones aplicadas no cambian

- **DADO** las sumas de verificación de `V1` a `V16` registradas antes de `V17`
- **CUANDO** se aplica `V17` y se ejecuta la validación de Flyway
- **ENTONCES** las sumas no cambian y la validación pasa

# Delta para Identidad

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `rbac-permission-matrix-and-audit-integration` (F0, cambio 8)
- **Propuesta:** `proposal.md`, aprobada el 2026-11-03, con O1 a O10 y Q1 a Q5 vinculantes (O3: un rol por cuenta e
  institución; O4 y Q1: MFA obligatoria para toda cuenta del personal, con o sin rol; O9: permisos por petición;
  O7: primera ruta real con permiso)

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `I278` en adelante, a continuación del máximo de la especificación vigente
  (`I277`). Los escenarios que se conservan de la especificación vigente conservan su identificador; los que no
  llevaban número no lo llevan.
- Este delta describe comportamiento observable; los nombres de clases, de tablas, de columnas y de la forma exacta del
  informe los fija el diseño. El catálogo de roles, la matriz y la decisión de permiso están en la capacidad
  `authorization`; los privilegios y la seguridad de fila de la tabla nueva, en `build-integrity`.
- **Con la MFA obligatoria para todo el personal (Q1), el desenlace `Authenticated` ya no se produce en producción para
  una cuenta del personal.** El tipo cerrado conserva sus cuatro desenlaces; los escenarios que ejercitan
  `Authenticated` y su traducción a sesión usan una política de MFA de prueba que no exige el segundo factor. El diseño
  confirma si ese camino se mantiene como código alcanzable solo por esa política.
- **Valores que fija el diseño:** los códigos de rol almacenados, el nombre de la tabla de asignación y de su
  restricción, la forma del resultado del resolutor ante un fallo de lectura, la forma del informe de roles y el
  conjunto exacto de privilegios de la tabla.
- La regla «MFA activa» sigue significando una credencial TOTP verificada; ya no depende de ninguna columna.

## ADDED Requirements

### Requisito: Una cuenta del personal tiene a lo sumo un rol por institución, y la base de datos lo hace cumplir

El sistema DEBE almacenar la asignación de rol de una cuenta del personal en una tabla propia de `identity`, con
`institution_id NOT NULL`, de modo que la clave única sea la cuenta dentro de su institución: una cuenta NO DEBE tener
dos roles en la misma institución, y la base de datos DEBE rechazar la segunda asignación (decisión O3). La tabla DEBE
restringir el código de rol al conjunto cerrado de los seis roles del catálogo, DEBE tener una clave foránea a la cuenta
dentro de su institución y seguridad de fila forzada por institución. Una cuenta del personal PUEDE no tener ninguna
asignación. La tabla NO DEBE almacenar permisos ni roles a medida: solo la asignación. Este cambio NO ofrece ninguna
ruta para asignar roles; los roles se asignan en pruebas.

#### Escenario: [I278] La segunda asignación a la misma cuenta se rechaza

- **DADO** la cuenta `ACC-1` de `INS-1` con el rol Cajero asignado
- **CUANDO** se intenta insertar una segunda asignación para `ACC-1` en `INS-1` con el rol Auditor, y otra con el
  mismo rol Cajero
- **ENTONCES** la base de datos rechaza ambas por la restricción de unicidad

#### Escenario: [I279] Un código de rol fuera del conjunto cerrado se rechaza

- **DADO** un código de rol que no es uno de los seis del catálogo
- **CUANDO** se intenta insertar una asignación con ese código
- **ENTONCES** la base de datos la rechaza por la restricción del conjunto cerrado

#### Escenario: [I280] La asignación no cruza instituciones

- **DADO** la cuenta `ACC-2` que existe solo en `INS-1`
- **CUANDO** se intenta insertar una asignación de `ACC-2` en `INS-2`
- **ENTONCES** la base de datos la rechaza por la clave foránea a la cuenta dentro de su institución

#### Escenario: [I281] Una institución no lee las asignaciones de otra

- **DADO** dos instituciones, cada una con una asignación de rol
- **CUANDO** la segunda, con su propio contexto de sesión y el rol `confia_admin_app`, consulta la tabla sin predicado
  adicional
- **ENTONCES** solo obtiene las filas de su institución y, sin contexto de institución, obtiene cero filas

#### Escenario: [I282] Una cuenta sin asignación es válida

- **DADO** una cuenta del personal sin ninguna fila de asignación
- **CUANDO** se consulta su asignación
- **ENTONCES** no existe fila y la cuenta sigue siendo válida; sus permisos efectivos son el conjunto vacío

#### Escenario: [I283] La tabla no almacena permisos ni roles a medida

- **DADO** el esquema migrado
- **CUANDO** se inspeccionan las columnas de la tabla de asignación
- **ENTONCES** contiene solo la institución, la cuenta, el código de rol y las columnas técnicas que fija el diseño,
  y ninguna columna de permisos

### Requisito: El resolutor de permisos tiene un único adaptador, en `identity`, de solo lectura dentro de `TransactionRunner.executeReadOnly`

El sistema DEBE implementar el puerto de resolución de permisos de `shared.security` con exactamente una implementación
de producción, ubicada en `com.confia.identity`, que lee la asignación de la cuenta autenticada en la institución del
token con una consulta parametrizada dentro de una transacción de solo lectura del único `TransactionRunner`. Ninguna
clase de producción distinta del decisor de permisos de la cadena administrativa DEBE consumir el puerto. El puerto NO
DEBE depender de ninguna clase de `com.confia.identity`.

#### Escenario: [I284] Exactamente un adaptador, en `identity`, con un único consumidor

- **DADO** el código de producción de `apps/api`
- **CUANDO** se ejecuta la prueba de inventario de puertos
- **ENTONCES** el puerto figura con exactamente una implementación de producción, en `com.confia.identity`, y su único
  consumidor es el decisor de permisos de la cadena

#### Escenario: [I285] La consulta usa la institución del token, no la del cuerpo ni de cabeceras

- **DADO** una cuenta `ACC-3` de `INS-1` con el rol Auditor, un token de `INS-1` y una cabecera `X-Institution-Id`
  de `INS-2`
- **CUANDO** se resuelven los permisos
- **ENTONCES** se leen en `INS-1` y la cabecera se ignora

#### Escenario: [I286] La resolución corre en una transacción de solo lectura

- **DADO** el adaptador del resolutor
- **CUANDO** se inspecciona la transacción con la que consulta
- **ENTONCES** es de solo lectura y se cierra antes de que el controlador se invoque

### Requisito: La MFA es obligatoria para toda cuenta del personal y la decide una política, no una columna

El sistema DEBE decidir si una cuenta del personal exige segundo factor mediante una política (`MfaPolicy`) que NO lee
ninguna columna de la cuenta. Con la decisión Q1 del propietario, la política DEBE exigir el segundo factor a toda
cuenta del personal, tenga o no rol asignado, y un cambio o ausencia de rol NO DEBE desactivarlo. La política DEBE ser la
única fuente de esa decisión para el inicio de sesión, el segundo factor y el restablecimiento de contraseña. Ningún
código de producción DEBE leer `mfa_required` y la columna NO DEBE existir al cerrar el cambio.

#### Escenario: [I287] Una cuenta con rol exige segundo factor

- **DADO** la cuenta `luis.cruz@colegio.edu.hn` con el rol Contabilidad y una credencial TOTP verificada
- **CUANDO** inicia sesión con contraseña correcta
- **ENTONCES** el desenlace es `SecondFactorRequired`

#### Escenario: [I288] Una cuenta sin rol también exige segundo factor

- **DADO** la cuenta `rosa.paz@colegio.edu.hn` sin ninguna asignación de rol y sin credencial TOTP
- **CUANDO** inicia sesión con contraseña correcta
- **ENTONCES** el desenlace es `SecondFactorEnrollmentRequired`, nunca `Authenticated`

#### Escenario: [I289] Quitar o cambiar el rol no desactiva la exigencia

- **DADO** la cuenta `rosa.paz@colegio.edu.hn` con el rol Auditor y credencial verificada, a la que luego se le quita
  la asignación
- **CUANDO** inicia sesión con contraseña correcta
- **ENTONCES** el desenlace sigue siendo `SecondFactorRequired`

#### Escenario: [I290] Los seis roles exigen segundo factor

- **DADO** una cuenta por cada uno de los seis roles, con credencial TOTP verificada
- **CUANDO** cada una inicia sesión con contraseña correcta
- **ENTONCES** las seis obtienen `SecondFactorRequired`

#### Escenario: [I291] El restablecimiento usa la misma política

- **DADO** una cuenta sin rol con credencial TOTP verificada y un token de recuperación vivo
- **CUANDO** presenta el token y la contraseña nueva sin segundo factor
- **ENTONCES** el restablecimiento se rechaza por falta de segundo factor, como para una cuenta con rol

#### Escenario: [I292] La columna ya no existe y nada la lee

- **DADO** el esquema migrado y el código de producción al cerrar el cambio
- **CUANDO** se busca la columna `mfa_required` y toda referencia en código de producción, en consultas y en el
  código generado de jOOQ
- **ENTONCES** la columna no existe y no hay ninguna referencia

### Requisito: `GET /api/v1/identity/roles` informa quién puede hacer qué, exige `identity:read` y no contiene datos personales

El sistema DEBE ofrecer `GET /api/v1/identity/roles`, que exige el permiso `identity:read` y una sesión con `otp` en
`amr`. La respuesta `200` DEBE ser un informe construido a partir del catálogo en código con un DTO explícito: para cada
uno de los seis roles, su identificador y la lista de sus permisos efectivos, en un orden determinista (roles en el
orden del catálogo y permisos ordenados). El informe NO DEBE contener cuentas, correos, nombres, identificadores de
usuario ni ningún dato personal, ni dato de la base de datos. La ruta NO DEBE exigir `Idempotency-Key` ni usar el
mecanismo de idempotencia. La respuesta DEBE llevar `Cache-Control: no-store`. Los roles con `identity:read` son
Super Admin, Administrador y Auditor; los demás reciben `403 forbidden` y un asiento de denegación.

#### Escenario: [I293] Un Auditor obtiene el informe

- **DADO** una cuenta con el rol Auditor y sesión `[pwd, otp]`
- **CUANDO** envía `GET /api/v1/identity/roles`
- **ENTONCES** la respuesta es `200` con `Cache-Control: no-store`

#### Escenario: [I294] El informe contiene los seis roles con los permisos del catálogo

- **DADO** la respuesta del escenario anterior
- **CUANDO** se compara con el catálogo
- **ENTONCES** contiene exactamente los seis roles y, para cada uno, exactamente los permisos de la matriz efectiva
  (por ejemplo, el Cajero con el permiso de solicitud de anulación y sin `invoicing:void`)

#### Escenario: [I295] El informe no contiene datos personales

- **DADO** las cuentas `luis.cruz@colegio.edu.hn` y `rosa.paz@colegio.edu.hn` con roles asignados
- **CUANDO** se solicita el informe
- **ENTONCES** el cuerpo no contiene ningún correo, nombre, identificador de cuenta ni de institución

#### Escenario: [I296] El orden del informe es determinista

- **DADO** dos solicitudes sucesivas del informe
- **CUANDO** se comparan los cuerpos
- **ENTONCES** son idénticos byte a byte

#### Escenario: [I297] Los roles sin `identity:read` reciben 403

- **DADO** una cuenta Cajero, una Contabilidad y una Coordinador Académico, cada una con sesión `[pwd, otp]`
- **CUANDO** cada una solicita el informe
- **ENTONCES** las tres reciben `403 forbidden`, el controlador registra cero invocaciones y existe un asiento
  `authorization.access.denied` por cada una, con `entity_id` igual a `identity:read`

#### Escenario: [I298] La ruta no usa idempotencia

- **DADO** la ruta del informe
- **CUANDO** se envía sin `Idempotency-Key`
- **ENTONCES** la respuesta es `200` y no se crea ningún marcador de idempotencia

#### Escenario: [I299] El informe no expone entidades de base de datos

- **DADO** el DTO del informe
- **CUANDO** se ejecutan las reglas de la capa `web`
- **ENTONCES** ningún tipo expuesto es una entidad de dominio ni un registro de jOOQ

### Requisito: Ausencia de la revisión DA-15, de la asignación de roles y del primer Super Admin (brecha con destino: cambio 8b)

El sistema NO DEBE incluir, en este cambio, la revisión del riesgo aceptado DA-15 sobre la reinscripción del segundo
factor, ni la creación del primer Super Admin sin secreto en migración ni en repositorio, ni ningún caso de uso que
asigne o cambie roles. Todo ello es del cambio **8b**. El riesgo DA-15 sigue aceptado hasta entonces.

#### Escenario: [I300] Ningún caso de uso asigna roles ni crea el primer Super Admin

- **DADO** el conjunto de casos de uso públicos de `identity` y las migraciones
- **CUANDO** se busca un caso de uso que inserte o cambie una asignación de rol, y una migración que cree una cuenta o
  una asignación
- **ENTONCES** no existe ninguno
- **Y** este escenario deja de ser cierto el día que el cambio 8b los entregue

## RENAMED Requirements

### Requisito: MFA obligatoria para cuentas marcadas con `mfa_required` → MFA obligatoria para toda cuenta del personal, con o sin rol

(Reason: por las decisiones O4 y Q1 del propietario del 2026-11-03, la MFA es obligatoria para toda cuenta del
personal, con o sin rol, y `mfa_required` deja de ser un valor almacenado independiente.)
(Migration: las referencias a «cuentas marcadas con `mfa_required`» pasan a «cuentas del personal»; las pruebas y
fixtures que fijaban `mfa_required` asignan un rol con un ayudante común o no fijan nada.)

### Requisito: Ausencia de revocación por desactivación de cuenta y de `admin_revoke` (brecha con destino: cambio 8) → Ausencia de revocación por desactivación de cuenta y de `admin_revoke` (brecha con destino: cambio 8b)

(Reason: el alcance del cambio 8 se divide por decisión O6; la desactivación de cuentas y `admin_revoke` pasan al cambio
8b.)
(Migration: el escenario I215 conserva su aserción y su dueño pasa a 8b.)

### Requisito: Ausencia de inscripción y baja voluntarias del segundo factor, de step-up y de vista de sesiones activas (brechas con destino: cambio 8 y primer cambio de `apps/admin-web`) → Ausencia de inscripción y baja voluntarias del segundo factor, de step-up y de vista de sesiones activas (brechas con destino: cambio 8b y primer cambio de `apps/admin-web`)

(Reason: el alcance del cambio 8 se divide por decisión O6; la inscripción y baja voluntarias, el step-up y las rutas que
exigen OTP con sesión completa para ello pasan al cambio 8b.)
(Migration: el escenario I216 conserva su aserción y su dueño pasa a 8b; `NoFullSessionMfaRoutesTest` se invierte en 8b.)

### Requisito: Ausencia del restablecimiento administrativo (brecha con destino: cambio 8 y `password-recovery-endpoints`) → Ausencia del restablecimiento administrativo (brecha con destino: cambio 8b y `password-recovery-endpoints`)

(Reason: el alcance del cambio 8 se divide por decisión O6; el restablecimiento administrativo pasa al cambio 8b.)
(Migration: los dos escenarios conservan su aserción y el dueño del primero pasa a 8b.)

## MODIFIED Requirements

### Requisito: MFA obligatoria para toda cuenta del personal, con o sin rol

El sistema DEBE exigir un segundo factor de autenticación para toda cuenta del personal, tenga o no un rol asignado
(decisión O4 y respuesta Q1 del propietario, 2026-11-03), y NO DEBE producir el desenlace `Authenticated` para esa
cuenta mientras el segundo factor no se haya completado. La exigencia la decide la política de MFA, no una columna.
Cuando la cuenta tiene una credencial TOTP **verificada**, el
sistema DEBE producir el desenlace `SecondFactorRequired`, pendiente de un código TOTP
válido. Cuando la cuenta no tiene ninguna credencial TOTP, o tiene una inscrita pero nunca verificada, el
sistema DEBE producir el desenlace `SecondFactorEnrollmentRequired`. Ninguno de los dos desenlaces DEBE hacer avanzar el
contador de retroceso por intentos fallidos, porque ninguno es un rechazo de credenciales. La traducción de
`SecondFactorRequired` y de `SecondFactorEnrollmentRequired` a un token restringido, y de la verificación
correcta a un token de acceso completo, la entrega el cambio de los endpoints de inicio de sesión y de
segundo factor.
(Previously: la exigencia dependía de la columna `mfa_required` de cada cuenta, decidida por quien la creaba.)

#### Escenario: Cuenta del personal con secreto TOTP ya inscrito y verificado

- **DADO** la cuenta de personal `sofia.mejia@colegio.edu.hn`, con una credencial TOTP verificada
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema devuelve el desenlace `SecondFactorRequired`, pendiente de un código TOTP
  válido
- **Y** no devuelve el desenlace `Authenticated`
- **Y** el contador de retroceso por intentos fallidos de esa cuenta no avanza
- **Y** el endpoint de inicio de sesión lo traduce a un token restringido `mfa-verify`

#### Escenario: Cuenta del personal sin ningún secreto TOTP inscrito

- **DADO** la cuenta de personal `jorge.aguilar@colegio.edu.hn`, sin ningún secreto TOTP inscrito
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema devuelve el desenlace `SecondFactorEnrollmentRequired`
- **Y** no devuelve el desenlace `Authenticated`
- **Y** el contador de retroceso por intentos fallidos de esa cuenta no avanza
- **Y** el endpoint de inicio de sesión lo traduce a un token restringido `mfa-enroll`

#### Escenario: [I217] Cuenta con secreto inscrito pero nunca verificado

- **DADO** la cuenta `patricia.nunez@colegio.edu.hn`, con una credencial recién
  inscrita (contador -1)
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema devuelve `SecondFactorEnrollmentRequired` y el contador de retroceso no avanza

#### Escenario: [I301] Una cuenta del personal sin rol y con credencial verificada exige el código

- **DADO** una cuenta del personal sin ninguna asignación de rol y con una credencial TOTP verificada
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el desenlace es `SecondFactorRequired`, igual que para una cuenta con rol

### Requisito: Resultado tipado de la autenticación con cuatro desenlaces

El sistema DEBE representar el resultado del caso de uso de autenticación con contraseña como un
tipo cerrado con exactamente cuatro desenlaces posibles: `Authenticated`, que lleva el
identificador del usuario y el de la institución; `Rejected`, que lleva un motivo interno de
rechazo, registrado en la bitácora de auditoría y nunca expuesto a quien llama; `SecondFactorRequired`,
que lleva el identificador del usuario y el de la institución, pendiente de un código TOTP válido; y
`SecondFactorEnrollmentRequired`, que lleva el identificador del usuario y el de la institución,
pendiente de completar la inscripción del segundo factor. Ninguno de los cuatro desenlaces DEBE
llevar un campo de alcance de autorización. Con la política real de MFA del personal, `Authenticated` no se produce
para una cuenta del personal; el desenlace se conserva en el tipo cerrado.
(Previously: el primer escenario producía `Authenticated` con una cuenta cuya columna `mfa_required` valía `false`.)

#### Escenario: Contraseña correcta produce el desenlace `Authenticated` sin campo de alcance

- **DADO** `maria.lopez@colegio.edu.hn` con contraseña vigente y una política de MFA de prueba que no exige el
  segundo factor (la política real del personal siempre lo exige)
- **CUANDO** el caso de uso de autenticación se invoca con esa contraseña
- **ENTONCES** devuelve el desenlace `Authenticated` con el identificador del usuario y el de la
  institución
- **Y** ese desenlace no lleva ningún campo de alcance de autorización

#### Escenario: Toda causa de rechazo produce el desenlace `Rejected` con un motivo interno

- **DADO** dos intentos de autenticación distintos: contraseña incorrecta contra una cuenta
  existente, y un correo que no corresponde a ninguna cuenta
- **CUANDO** el caso de uso procesa cada uno por separado
- **ENTONCES** ambos devuelven el desenlace `Rejected`, cada uno con un motivo interno distinto que
  la auditoría registra
- **Y** ningún campo del resultado expuesto a quien llama distingue entre los dos motivos
- **Y** un retroceso vigente **no** es causa de `Rejected`: retarda la respuesta y deja que el
  intento se resuelva por sus credenciales

#### Escenario: Los cuatro desenlaces son exactamente los que el tipo cerrado permite, ninguno más

- **DADO** el tipo cerrado `AuthenticationResult`
- **CUANDO** se inspecciona la cláusula `permits` que declara
- **ENTONCES** contiene exactamente `Authenticated`, `Rejected`, `SecondFactorRequired` y
  `SecondFactorEnrollmentRequired`, ninguno más
- **Y** ninguno de los cuatro lleva un campo de alcance de autorización

#### Escenario: [I302] Con la política real, el personal nunca obtiene `Authenticated`

- **DADO** una cuenta del personal con rol y otra sin rol, con contraseña vigente
- **CUANDO** el caso de uso se invoca con la política real de MFA
- **ENTONCES** ninguna obtiene `Authenticated`

### Requisito: Exhaustividad forzada por el compilador sobre los cuatro desenlaces de `AuthenticationResult`

El sistema DEBE consumir el resultado del caso de uso de autenticación con contraseña mediante un
`switch` exhaustivo sobre los cuatro desenlaces de `AuthenticationResult`, sin cláusula `default`,
en todo punto de producción que decida el efecto de ese resultado sobre el estado del retroceso o
sobre qué se audita. El sistema NO DEBE decidir ese efecto mediante `instanceof` ni mediante
cualquier otro mecanismo que no obligue al compilador a rechazar la construcción si
`AuthenticationResult` gana un desenlace nuevo sin que ese punto lo maneje.

#### Escenario: Un desenlace no manejado rompe la compilación, no el comportamiento en producción

- **DADO** un `switch` exhaustivo sin `default` sobre los cuatro desenlaces de
  `AuthenticationResult`, en el punto de `AuthenticateWithPassword` que decide el estado del
  retroceso
- **CUANDO** se elimina, en un fixture de prueba permanente, la rama que maneja
  `SecondFactorRequired`
- **ENTONCES** la compilación de ese fixture falla, porque el `switch` deja de ser exhaustivo
- **Y** por tanto ningún cambio futuro que agregue un desenlace puede tratarlo en silencio como
  `Rejected`

#### Escenario: `SecondFactorRequired` no avanza el contador de retroceso ni se audita como fallo

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn` con secreto TOTP ya
  inscrito y verificado, y su contador de retroceso en cero
- **CUANDO** el caso de uso produce el desenlace `SecondFactorRequired` para esa cuenta
- **ENTONCES** el `switch` exhaustivo que decide el efecto sobre el retroceso trata esa rama de
  forma distinta de `Rejected`
- **Y** el contador de retroceso de la cuenta permanece en cero
- **Y** la auditoría no registra un intento fallido para ese evento

### Requisito: Segundo factor exigido en el restablecimiento cuando la cuenta tiene MFA activa

Una cuenta tiene MFA activa cuando tiene una credencial TOTP **verificada**, que es la misma condición con la que el
inicio de sesión devuelve `SecondFactorRequired`; la exigencia de MFA es de toda cuenta del personal (política de MFA) y
ya no depende de ninguna columna. Para una
cuenta con MFA activa, el sistema NO DEBE aceptar un restablecimiento que no presente, además del
token, un código TOTP válido o un código de recuperación de MFA sin usar (`docs/03-seguridad.md`
§4.7). El código de recuperación de MFA aceptado DEBE quedar consumido. Un restablecimiento
rechazado por falta de segundo factor NO DEBE consumir el token ni cambiar la contraseña.
(Previously: MFA activa exigía `mfa_required` en `true` y una credencial TOTP verificada.)

#### Escenario: Sin segundo factor no hay restablecimiento, y con un código TOTP válido sí

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, con una credencial TOTP
  verificada y el token vivo `PRT-33e1` emitido a las 09:00:00 del 2026-10-12
- **CUANDO** a las 09:05:00 presenta `PRT-33e1` con la contraseña nueva `Montaña de Celaque 2026` y
  sin ningún código
- **ENTONCES** el sistema rechaza el restablecimiento indicando que falta el segundo factor
- **Y** la contraseña vigente no cambia y `PRT-33e1` sigue vivo
- **Y** cuando a las 09:06:00 presenta `PRT-33e1`, la misma contraseña nueva y un código TOTP válido
  para ese instante, el sistema acepta el restablecimiento

#### Escenario: Un código de recuperación de MFA sustituye al código TOTP y queda consumido

- **DADO** la misma cuenta `sofia.mejia@colegio.edu.hn`, con diez códigos de recuperación de MFA sin
  usar y el token vivo `PRT-33e2` emitido a las 11:00:00 del 2026-10-12
- **CUANDO** a las 11:04:00 presenta `PRT-33e2`, la contraseña nueva `Montaña de Celaque 2026` y uno
  de sus códigos de recuperación
- **ENTONCES** el sistema acepta el restablecimiento
- **Y** ese código de recuperación queda usado y la cuenta conserva nueve sin usar
- **Y** presentar el mismo código en un restablecimiento posterior no cuenta como segundo factor

### Requisito: Una cuenta sin MFA activa restablece solo con el enlace

Para una cuenta sin MFA activa, el sistema DEBE aceptar el restablecimiento con el token y la
contraseña nueva, sin pedir segundo factor. Esto incluye una cuenta del personal sin secreto inscrito, o con un
secreto inscrito pero nunca verificado: en su siguiente inicio de sesión con la
contraseña nueva, el sistema DEBE devolver
`SecondFactorEnrollmentRequired`, de modo que la inscripción se sigue exigiendo.
(Previously: la regla se expresaba sobre `mfa_required` en `true`, y se incluía una cuenta con `mfa_required` en
`false` que tuviera un secreto inscrito; esa combinación ya no existe porque el segundo factor es obligatorio para todo
el personal.)

#### Escenario: Una cuenta sin secreto restablece solo con el enlace, y la inscripción sigue exigida

- **DADO** la cuenta `jorge.aguilar@colegio.edu.hn`, sin secreto TOTP
  inscrito y con el token vivo `PRT-44f0` emitido a las 08:00:00 del 2026-10-13
- **CUANDO** a las 08:10:00 presenta `PRT-44f0` y la contraseña nueva `Semana Morazánica 2026`, sin
  ningún código
- **ENTONCES** el sistema acepta el restablecimiento
- **Y** cuando a las 08:12:00 inicia sesión con `Semana Morazánica 2026`, el sistema devuelve
  `SecondFactorEnrollmentRequired` y no `Authenticated`

#### Escenario: [I218] Una credencial nunca verificada restablece solo con el enlace

- **DADO** la cuenta `patricia.nunez@colegio.edu.hn`, con una credencial inscrita
  y nunca verificada, y un token vivo
- **CUANDO** restablece con el token y la contraseña nueva, sin ningún código
- **ENTONCES** el sistema acepta el restablecimiento
- **Y** el siguiente inicio de sesión devuelve `SecondFactorEnrollmentRequired`

### Requisito: El inicio de sesión se ofrece en `POST /api/v1/auth/sessions` y responde según el desenlace de la autenticación

El sistema DEBE ofrecer `POST /api/v1/auth/sessions`, sin credencial previa, con cuerpo JSON
`{"identifier": string, "password": string}` y `Content-Type: application/json` obligatorio. La ruta DEBE
traducir los cuatro desenlaces de `AuthenticationResult` así: `Authenticated` produce `200` con la respuesta de
sesión, emite una familia de refresco con `second_factor_verified` en `false` y las dos cookies (ver el
requisito de cookies en `web-edge`); `SecondFactorRequired` produce `200` con `status` igual a
`second-factor-required`, `secondFactorToken` (token restringido con uso `mfa-verify`) y
`secondFactorTokenExpiresAt`; `SecondFactorEnrollmentRequired` produce `200` con `status` igual a
`second-factor-enrollment-required` y un token restringido con uso `mfa-enroll`; `Rejected` produce `401` con el
código `authentication-failed`. Los dos desenlaces de segundo factor NO DEBEN emitir cookies, ni crear
familia ni token de refresco. Con la política real de MFA, toda cuenta del personal recibe uno de los dos desenlaces de
segundo factor o `Rejected`; la traducción de `Authenticated` se ejercita con una política de prueba que no exige el
segundo factor. El cuerpo NO DEBE contener datos personales. La institución DEBE provenir de la
configuración del proceso. La validación DEBE limitarse a la longitud máxima de cada campo y NO DEBE imponer la
longitud mínima de la contraseña. Las propiedades desconocidas del cuerpo DEBEN rechazarse con `400` y
`validation-failed`. Un tipo de contenido distinto de JSON DEBE responder `415`. Toda respuesta DEBE llevar
`Cache-Control: no-store`. La ruta NO DEBE usar `Idempotency-Key`. El inicio de sesión no es una ruta de cookie: NO
DEBE leer la cookie de sesión ni exigir el token CSRF (la protegen el `Origin` obligatorio, el tipo de contenido
JSON y `SameSite=Strict`); si el navegador ya traía una cookie de sesión anterior, la respuesta con sesión la
sobrescribe.
(Previously: los escenarios nombraban `mfa_required` y el primer escenario emitía sesión para una cuenta con
`mfa_required` en `false`.)

#### Escenario: [I149] Credenciales correctas sin segundo factor emiten sesión y cookies

- **DADO** la cuenta `maria.lopez@colegio.edu.hn` con contraseña vigente y una política de MFA de prueba que no exige
  el segundo factor
- **CUANDO** envía `POST /api/v1/auth/sessions` con un `Origin` permitido, `Content-Type: application/json` y
  sus credenciales correctas
- **ENTONCES** la respuesta es `200` con `status` igual a `authenticated` y un `accessToken` cuyo `amr` es
  `[pwd]` y cuyo `sid` es la familia recién creada
- **Y** la respuesta contiene exactamente dos cabeceras `Set-Cookie`, la de sesión y la CSRF
- **Y** existe una familia con la IP y la huella del agente de la petición, y un asiento de emisión de sesión
- **Y** el cuerpo no contiene el correo, el nombre ni el `sid`

#### Escenario: [I150] Cuenta con segundo factor verificado recibe un token restringido y ninguna cookie

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn` con credencial verificada
- **CUANDO** inicia sesión con contraseña correcta
- **ENTONCES** la respuesta es `200` con `status` igual a `second-factor-required` y un `secondFactorToken`
  cuyo uso es `mfa-verify` y cuya vida es de 300 segundos
- **Y** la respuesta no contiene `Set-Cookie`
- **Y** no se creó ninguna familia ni ningún token de refresco

#### Escenario: [I151] Cuenta sin secreto recibe un token de inscripción

- **DADO** la cuenta `jorge.aguilar@colegio.edu.hn` sin secreto TOTP
- **CUANDO** inicia sesión con contraseña correcta
- **ENTONCES** la respuesta es `200` con `status` igual a `second-factor-enrollment-required` y un token
  restringido cuyo uso es `mfa-enroll`
- **Y** la respuesta no contiene `Set-Cookie` y no se creó ninguna familia

#### Escenario: [I152] Un rechazo responde `401 authentication-failed` sin cookies ni token

- **DADO** una contraseña incorrecta para `maria.lopez@colegio.edu.hn`
- **CUANDO** se envía el inicio de sesión
- **ENTONCES** la respuesta es `401` con el código `authentication-failed` y la cabecera
  `WWW-Authenticate: Bearer`
- **Y** no contiene `Set-Cookie`, token de acceso ni token restringido

#### Escenario: [I153] Validación del cuerpo

- **DADO** cuerpos sin `identifier`, con `password` vacía, con un campo de longitud máxima más uno y con una
  propiedad desconocida `role`
- **CUANDO** se envía cada uno
- **ENTONCES** los cuatro responden `400` con `validation-failed` que nombra el campo, sin repetir el valor
  rechazado
- **Y** el caso de uso registra cero invocaciones en cada uno
- **Y** una contraseña de 3 caracteres con longitud válida de campo llega al caso de uso (no se impone el
  mínimo de 12 al entrar)

#### Escenario: [I154] Tipo de contenido distinto de JSON

- **DADO** una petición con `Content-Type: text/plain` o `application/x-www-form-urlencoded`
- **CUANDO** se envía a la ruta
- **ENTONCES** la respuesta es `415` con `unsupported-media-type` y el caso de uso registra cero invocaciones

#### Escenario: [I155] La institución del cuerpo se ignora

- **DADO** un proceso configurado con la institución `d290f1ee-6c54-4b01-90e6-d701748f0851` y un cuerpo que
  intenta declarar otra institución en una cabecera `X-Institution-Id`
- **CUANDO** se inicia sesión
- **ENTONCES** la cuenta se resuelve en la institución de la configuración y la cabecera se ignora

#### Escenario: [I228] El inicio de sesión no exige CSRF y sobrescribe una cookie anterior

- **DADO** un navegador con una cookie de sesión anterior, sin cabecera `X-CSRF-Token`, un `Origin` permitido y una
  política de MFA de prueba que no exige el segundo factor
- **CUANDO** inicia sesión con credenciales correctas
- **ENTONCES** la respuesta es `200` y los dos `Set-Cookie` sustituyen a las cookies anteriores
- **Y** la petición no se rechazó por falta de token CSRF
- **Y** la familia anterior no se revoca por este inicio de sesión

#### Escenario: [I156] El retardo del retroceso se materializa después de confirmar y no deniega

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con credencial verificada, tres fallos consecutivos y la
  contraseña correcta
- **CUANDO** inicia sesión
- **ENTONCES** la respuesta es `200` con `status` igual a `second-factor-required`, enviada después de la espera del
  retardo exigible
- **Y** la transacción ya estaba confirmada al empezar la espera

### Requisito: Una credencial TOTP está verificada solo tras aceptar un código TOTP, y «MFA activa» significa secreto verificado

El sistema DEBE considerar verificada una credencial TOTP cuando aceptó al menos un código TOTP (su último
contador aceptado es mayor o igual que cero). Una credencial recién inscrita, con el contador en -1, NO DEBE
considerarse verificada. Un código de recuperación NO DEBE verificar una credencial. «MFA activa» significa
una credencial verificada (la exigencia de MFA es de toda cuenta del personal y ya no depende de ninguna columna); una
credencial inscrita pero nunca verificada equivale,
para el inicio de sesión y para el restablecimiento, a no tener secreto inscrito. Por tanto, el inicio de sesión
de una cuenta del personal con credencial nunca verificada DEBE devolver
`SecondFactorEnrollmentRequired`, de modo que el usuario nunca reciba un token `mfa-verify` para su primer
código.
(Previously: «MFA activa» significaba `mfa_required` en `true` y una credencial verificada.)

#### Escenario: [I167] Una credencial recién inscrita no está verificada

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, recién inscrita
- **CUANDO** inicia sesión con contraseña correcta
- **ENTONCES** el desenlace es `SecondFactorEnrollmentRequired`, no `SecondFactorRequired`

#### Escenario: [I168] Un código TOTP aceptado verifica la credencial

- **DADO** la credencial de `sofia.mejia@colegio.edu.hn` con contador -1
- **CUANDO** se acepta un código TOTP válido
- **ENTONCES** el contador pasa a un valor mayor o igual que cero
- **Y** el siguiente inicio de sesión devuelve `SecondFactorRequired`

#### Escenario: [I169] Un código de recuperación no verifica

- **DADO** una credencial con contador -1 y diez códigos de recuperación sin usar
- **CUANDO** se presenta un código de recuperación a la verificación
- **ENTONCES** se rechaza como `second-factor-invalid` (la primera verificación, con el token `mfa-enroll`,
  acepta solo un código TOTP), el contador sigue en -1 y el código sigue sin usar

### Requisito: Autorización basada en permisos evaluada en el servidor

El sistema DEBE evaluar en el servidor, en cada solicitud, si el permiso requerido por la
operación lo tiene la cuenta autenticada, resolviéndolo por petición desde la asignación de rol almacenada en el
servidor para la institución del token, y NO DEBE conceder una operación protegida
basándose en una afirmación de permiso enviada por el cliente ni en permisos llevados en el token.
(Previously: el permiso se evaluaba «contra la sesión autenticada», con los datos de la sesión almacenados en el
servidor; ahora se resuelve por petición desde la asignación de rol.)

#### Escenario: Permiso presente por el rol asignado

- **DADO** una cuenta de personal con el rol Auditor, que incluye `identity:read`, y sesión `[pwd, otp]`
- **CUANDO** solicita `GET /api/v1/identity/roles`
- **ENTONCES** el sistema resuelve el permiso en el servidor desde la asignación de rol de la cuenta y autoriza la
  operación

#### Escenario: Cliente afirma un permiso que la cuenta no tiene

- **DADO** una cuenta de personal con el rol Cajero, que NO incluye `identity:read`
- **CUANDO** solicita `GET /api/v1/identity/roles` incluyendo en la solicitud un
  campo, una cabecera y un parámetro `claimedPermission: "identity:read"` fabricados por el cliente
- **ENTONCES** el sistema ignora por completo esos valores, evalúa únicamente el rol registrado en el servidor para esa
  cuenta, y rechaza la operación con `403 Forbidden`

### Requisito: Las claims del token de acceso son una lista cerrada, sin datos personales y sin `permissions`

El sistema DEBE emitir el token de acceso con exactamente estas claims: `iss`, `aud`, `sub`, `exp`,
`iat`, `jti`, `sid`, `tenant` y `amr`, y ninguna otra. `iss` y `aud` DEBEN valer `confia-admin`, con `aud` como cadena y no como arreglo; `sub`
DEBE ser el identificador opaco de la cuenta; `sid` el identificador de la familia de sesión; `tenant`
el identificador de la institución; `jti` DEBE ser distinto en cada token; `amr` DEBE ser un arreglo
que contiene `pwd` y, solo si se completó el segundo factor, `otp`, en ese orden. La diferencia `exp - iat` DEBE ser
de 600 segundos. El token NO DEBE contener ningún dato personal (nombre, correo, documento de
identidad ni dato de un estudiante). El token NO DEBE contener la claim `permissions` ni ninguna claim de rol: los
permisos se resuelven por petición desde la base de datos (ADR nuevo, que reemplaza la parte de ADR-0005 sobre permisos
en el token), porque un conjunto vacío podría leerse como autoritativo y un permiso viejo sobreviviría a su revocación.
(Previously: el token no contenía `permissions` «hasta el cambio 8» (decisión 11 del propietario).)

#### Escenario: [I58] La lista de claims es cerrada

- **DADO** un token de acceso emitido para la cuenta `maria.lopez@colegio.edu.hn`
- **CUANDO** se decodifica su cuerpo
- **ENTONCES** el conjunto de nombres de claims es exactamente `iss`, `aud`, `sub`, `exp`, `iat`, `jti`,
  `sid`, `tenant` y `amr`
- **Y** `iss` y `aud` valen `confia-admin`

#### Escenario: [I59] Vida de 600 segundos y audiencia del proceso emisor (ADR-0005, prueba 3)

- **DADO** un token emitido a las 10:00:00 del 2026-10-12
- **CUANDO** se decodifica
- **ENTONCES** `exp - iat` es 600 y `exp` corresponde a las 10:10:00
- **Y** `aud` coincide con la audiencia del proceso emisor

#### Escenario: [I60] Ningún dato personal ni `permissions` en el token

- **DADO** un token emitido para `maria.lopez@colegio.edu.hn`, cuyo nombre completo, correo y
  documento de identidad son conocidos por la prueba
- **CUANDO** se decodifican cabecera y cuerpo y se busca cada uno de esos valores
- **ENTONCES** ninguno aparece, y `sub` es un identificador opaco que no contiene el correo
- **Y** el cuerpo no contiene la claim `permissions`

#### Escenario: [I61] `amr` refleja el segundo factor completado

- **DADO** una sesión iniciada solo con contraseña y otra cuyo segundo factor se completó
- **CUANDO** se emite el token de cada una
- **ENTONCES** el `amr` de la primera es `[pwd]` y el de la segunda es `[pwd, otp]`, en ese orden

#### Escenario: [I62] `jti` distinto en cada emisión

- **DADO** mil emisiones sucesivas para la misma sesión
- **CUANDO** se recolectan sus `jti`
- **ENTONCES** son mil valores distintos

#### Escenario: [I303] Ninguna claim de rol

- **DADO** un token emitido para una cuenta con el rol Contabilidad
- **CUANDO** se decodifica su cuerpo
- **ENTONCES** no contiene ninguna claim de rol ni de permisos

### Requisito: Ausencia de revocación por desactivación de cuenta y de `admin_revoke` (brecha con destino: cambio 8b)

El sistema NO DEBE revocar ninguna familia por la desactivación de una cuenta ni ofrecer el motivo `admin_revoke`.
Ambos son del **cambio 8b** (división del cambio 8, `rbac-permission-matrix-and-audit-integration`), con su propia
migración.
(Previously: ambos eran del cambio 8.)

#### Escenario: [I215] Desactivar una cuenta no existe y `admin_revoke` se rechaza

- **DADO** el conjunto de casos de uso públicos de `identity` y la restricción de `revoked_reason`
- **CUANDO** se busca un caso de uso de desactivación de cuenta y se intenta guardar `admin_revoke`
- **ENTONCES** no existe el caso de uso y la base rechaza el motivo
- **Y** este escenario deja de ser cierto el día que el cambio 8b los entregue

### Requisito: Ausencia de inscripción y baja voluntarias del segundo factor, de step-up y de vista de sesiones activas (brechas con destino: cambio 8b y primer cambio de `apps/admin-web`)

El sistema NO DEBE ofrecer inscripción ni baja voluntarias del segundo factor con una sesión completa, ni elevar
una sesión `[pwd]` a `[pwd, otp]`: son del **cambio 8b**. La política de rutas que exigen `otp` existe solo para las
rutas con permiso (capacidad `authorization`), no para la inscripción ni la baja. Tampoco
DEBE ofrecer la vista de sesiones activas ni la columna `device_label` (DA-6): son del primer cambio de
`apps/admin-web` que la muestre. Cambiar la contraseña con sesión («todas salvo la actual») es del cambio que
entregue ese flujo.
(Previously: la política de rutas que exigen `otp` (DA-12) y la inscripción y baja voluntarias eran del cambio 8.)

#### Escenario: [I216] Ninguna ruta de inscripción con sesión completa ni de listado de sesiones

- **DADO** el mapa de rutas de producción del proceso administrativo
- **CUANDO** se busca una ruta que acepte un token de acceso completo para inscribir o dar de baja MFA, o que
  liste sesiones
- **ENTONCES** no existe ninguna
- **Y** este escenario deja de ser cierto el día que sus cambios dueños las entreguen

### Requisito: Ausencia del restablecimiento administrativo (brecha con destino: cambio 8b y `password-recovery-endpoints`)

El sistema NO DEBE ofrecer, en este cambio, ningún caso de uso con el que un actor distinto del
titular dispare el restablecimiento de otra cuenta ni invalide su contraseña (`docs/03-seguridad.md`
§4.7, «Restablecimiento administrativo»), porque el endpoint de ese restablecimiento llega con el cambio **8b**
(división del cambio 8, `rbac-permission-matrix-and-audit-integration`) y con
`password-recovery-endpoints`. Ningún caso de uso DEBE permitir fijar una contraseña sin un token de
recuperación válido.
(Previously: el rol de Super Administrador llegaba con el cambio 8 y el endpoint con `password-recovery-endpoints`.)

#### Escenario: El único camino para cambiar una contraseña es el token

- **DADO** el conjunto de casos de uso públicos del módulo de identidad
- **CUANDO** se inspecciona cuáles escriben el hash de contraseña de una cuenta
- **ENTONCES** el único es el restablecimiento con token

#### Escenario: Un restablecimiento sin token válido nunca cambia la contraseña

- **DADO** la cuenta `ana.martinez@colegio.edu.hn`, con la contraseña vigente `Cafetal de Copán 2026`
- **CUANDO** se intenta restablecerla con un token que nunca se emitió
- **ENTONCES** el sistema lo rechaza y `Cafetal de Copán 2026` sigue autenticando

## REMOVED Requirements

### Requisito: Columna `mfa_required`, señal mínima y desnormalizada, con obligación de derivación real en el cambio 8

(Reason: el cambio 8 cumple la obligación del requisito: elimina la doble fuente. Por las decisiones O4 y Q1 la MFA es
obligatoria para toda cuenta del personal y la decide una política; la columna `mfa_required` se retira con su propia
migración, posterior a la derivación.)
(Migration: la exigencia de MFA queda en «MFA obligatoria para toda cuenta del personal, con o sin rol» y en «La MFA es
obligatoria para toda cuenta del personal y la decide una política, no una columna». La migración de retiro, el código
generado de jOOQ y los fixtures de prueba se tratan en `build-integrity`. Los dos escenarios del requisito, sobre la
creación de la cuenta con la columna y sobre la ausencia de derivación, dejan de ser ciertos.)

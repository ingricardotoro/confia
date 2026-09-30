# Delta para Integridad de la construcción

## ADDED Requirements

### Requisito: Tablas nuevas de cifrado de columna y MFA, con `institution_id` y seguridad de fila forzada

El sistema DEBE mantener la migración `V6`, que añade la columna `mfa_required BOOLEAN NOT NULL` a
`identity_staff_account` y crea **cuatro** tablas nuevas: la tabla de llaves de datos del sobre de
llaves, con el prefijo de módulo `shared_` porque su gestión vive en `com.confia.shared.crypto`
(decisión D3 de la propuesta), con su estado `active`/`retired`; y, con el prefijo de módulo
`identity_`, la tabla de credencial TOTP por cuenta, la tabla de códigos de recuperación de MFA, y
la tabla de retroceso de verificación TOTP. Todo lo que este requisito exige de «la tabla nueva»
DEBE cumplirse en **cada una de las cuatro**. Cada tabla DEBE declarar `institution_id NOT NULL`
(ADR-0009, punto 1) y toda restricción única DEBE incluir `institution_id` como discriminador
(ADR-0009, punto 2). Cada tabla DEBE tener `ENABLE ROW LEVEL SECURITY` y `FORCE ROW LEVEL
SECURITY`, con una política sobre `institution_id` que siga el mismo patrón
`current_setting(..., true)` con fallo cerrado ante `NULL` que ya usan las migraciones `V1` a `V5`
(`docs/03-seguridad.md` §6.2). La tabla de llaves de datos DEBE además aislar las llaves por
institución, de modo que cifrar o descifrar el secreto de una institución nunca toque la llave de
otra. La migración DEBE partir de `REVOKE ALL ... FROM PUBLIC` antes de conceder ningún privilegio
explícito.

#### Escenario: Las puertas genéricas de esquema pasan sobre cada tabla nueva sin lista de exclusión

- **DADO** cada una de las cuatro tablas nuevas ya migrada, con `institution_id NOT NULL`, su
  seguridad a nivel de fila habilitada y forzada, y su restricción única con discriminador de
  institución
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
- **ENTONCES** todas pasan sobre **cada una de las cuatro** sin necesitar ninguna excepción ni
  entrada en una lista de exclusión

#### Escenario: Una institución no lee la llave de datos de otra

- **DADO** dos instituciones, cada una con al menos una llave de datos activa en la tabla de llaves
- **CUANDO** la segunda, con su propio contexto de sesión y el rol `confia_admin_app`, consulta la
  tabla sin ningún predicado adicional
- **ENTONCES** solo obtiene las filas de su propia institución, nunca las de la primera
- **Y** por tanto cifrar o descifrar el secreto de la primera institución nunca toca la llave de la
  segunda

#### Escenario: Sin contexto de institución, la consulta devuelve cero filas, no un error de permiso

- **DADO** cada tabla nueva ya migrada, con filas de al menos una institución
- **CUANDO** se consulta con el rol `confia_admin_app` sin que `app.institution_id` esté establecido
  en la transacción
- **ENTONCES** la consulta devuelve cero filas, porque la política deniega ante `NULL`, en vez de
  fallar por falta de permiso

### Requisito: Permisos de acceso a las tablas nuevas de cifrado y MFA por rol de base de datos

El sistema DEBE conceder sobre cada una de las cuatro tablas nuevas, exactamente y sin uno más ni
uno menos (`docs/03-seguridad.md` §6.1): a `confia_admin_app`, `SELECT`, `INSERT` y `UPDATE`, sin
`DELETE`; a `confia_portal_app`, **ningún privilegio**, por ser datos de personal; a
`confia_readonly`, solo `SELECT`, sujeto a la misma política de fila por institución que los demás
roles; a `confia_backup`, el alcance de `pg_read_all_data` que ya tiene sobre el resto del esquema;
a `confia_owner`, ningún privilegio adicional a los de propietario del esquema, sin el atributo
`BYPASSRLS`. El sistema DEBE extender `RolePrivilegeMatrixIT` con las filas de las cuatro tablas
nuevas para los cinco roles.

#### Escenario: La matriz de privilegios cubre las cuatro tablas nuevas para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de las cuatro tablas nuevas
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de `docs/03-seguridad.md` §6.1 sobre cada una de
  las cuatro tablas para cada uno de los cinco roles, sin uno más ni uno menos

#### Escenario: `confia_admin_app` puede leer, insertar y actualizar, pero no borrar

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` sobre cada tabla nueva, y después intenta
  `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de
  privilegio

#### Escenario: `confia_portal_app` no tiene ningún privilegio sobre ninguna tabla nueva

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre cualquiera de las cuatro tablas
  nuevas
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

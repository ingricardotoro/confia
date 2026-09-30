# Delta para Integridad de la construcción

> Cambio `password-recovery-token`. Sigue el precedente de las partes 1 y 2 del cambio 7: el esquema,
> la seguridad de fila y los privilegios de una tabla nueva se especifican en esta capacidad. No se
> añade ninguna regla de arquitectura.

## ADDED Requirements

### Requisito: Tabla nueva de tokens de recuperación de contraseña, con `institution_id` y seguridad de fila forzada

El sistema DEBE mantener la migración `V7`, que crea la tabla de tokens de recuperación de contraseña
con el prefijo de módulo `identity_` (ADR-0015, regla 3). La tabla DEBE declarar `institution_id NOT
NULL` (ADR-0009, punto 1), y toda restricción única o índice único DEBE incluir `institution_id` como
discriminador (ADR-0009, punto 2). La cuenta DEBE referenciarse por su identificador interno, con una
llave foránea compuesta hacia la tabla de cuentas de personal. El hash del token DEBE restringirse,
por una restricción de verificación del catálogo, a exactamente 64 caracteres hexadecimales en
minúscula. La tabla DEBE tener `ENABLE ROW LEVEL SECURITY` y `FORCE ROW LEVEL SECURITY`, con una
política sobre `institution_id` que siga el mismo patrón `current_setting(..., true)` con fallo
cerrado ante `NULL` que ya usan las migraciones `V1` a `V6` (`docs/03-seguridad.md` §6.2). La
migración DEBE partir de `REVOKE ALL ... FROM PUBLIC` antes de conceder ningún privilegio explícito.

#### Escenario: Las puertas genéricas de esquema pasan sobre la tabla nueva sin lista de exclusión

- **DADO** la tabla de tokens de recuperación ya migrada, con `institution_id NOT NULL`, su seguridad
  a nivel de fila habilitada y forzada, y sus restricciones únicas con discriminador de institución
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
- **ENTONCES** todas pasan sobre la tabla nueva sin necesitar ninguna excepción ni entrada en una
  lista de exclusión

#### Escenario: Una institución no lee los tokens de otra

- **DADO** dos instituciones, cada una con al menos un token de recuperación emitido
- **CUANDO** la segunda, con su propio contexto de sesión y el rol `confia_admin_app`, consulta la
  tabla sin ningún predicado adicional
- **ENTONCES** solo obtiene las filas de su propia institución, nunca las de la primera

#### Escenario: Sin contexto de institución, la consulta devuelve cero filas, no un error de permiso

- **DADO** la tabla de tokens de recuperación con filas de al menos una institución
- **CUANDO** se consulta con el rol `confia_admin_app` sin que `app.institution_id` esté establecido
  en la transacción
- **ENTONCES** la consulta devuelve cero filas, porque la política deniega ante `NULL`, en vez de
  fallar por falta de permiso

#### Escenario: El catálogo rechaza un hash que no es de 64 caracteres hexadecimales

- **DADO** la tabla de tokens de recuperación ya migrada
- **CUANDO** se intenta insertar, con el rol `confia_admin_app` y el contexto de su institución, una
  fila cuyo hash es un token de 43 caracteres en base64url en vez de su SHA-256 hexadecimal
- **ENTONCES** la base rechaza la inserción por la restricción de verificación

### Requisito: Permisos de acceso a la tabla de tokens de recuperación por rol de base de datos

El sistema DEBE conceder sobre la tabla de tokens de recuperación de contraseña, exactamente y sin
uno más ni uno menos (`docs/03-seguridad.md` §6.1 y su adenda): a `confia_admin_app`, `SELECT`,
`INSERT` y `UPDATE`, **sin `DELETE`**, porque las filas vencidas se retienen hasta que el cambio 9
decida la purga (respuesta del propietario del 2026-09-30); a `confia_portal_app`, **ningún
privilegio**, por ser datos de personal; a `confia_readonly`, solo `SELECT`, sujeto a la misma
política de fila por institución; a `confia_backup`, el alcance de `pg_read_all_data` que ya tiene
sobre el resto del esquema; a `confia_owner`, ningún privilegio adicional a los de propietario del
esquema, sin el atributo `BYPASSRLS`. El sistema DEBE extender `RolePrivilegeMatrixIT` con las filas
de la tabla nueva para los cinco roles.

#### Escenario: La matriz de privilegios cubre la tabla nueva para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de la tabla de tokens de recuperación
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de este requisito para cada uno de los cinco
  roles, sin uno más ni uno menos

#### Escenario: `confia_admin_app` puede leer, insertar y actualizar, pero no borrar

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` sobre la tabla de tokens de recuperación, y
  después intenta `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de
  privilegio

#### Escenario: `confia_portal_app` no tiene ningún privilegio sobre la tabla nueva

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre la tabla de tokens de recuperación
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

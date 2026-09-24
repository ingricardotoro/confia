# Delta para Integridad de la construcción

## MODIFIED Requirements

### Requisito: Frontera de dominio entre módulos de negocio

El sistema DEBE romper la construcción si una clase de un módulo de negocio importa el `domain` de
otro módulo de negocio. Este cambio introduce `identity` como segundo módulo de negocio del sistema,
junto a `organization`, y con él demuestra por primera vez la mitad positiva de esta regla: hasta
hoy, con un solo módulo de negocio, «ninguno importa el `domain` del otro» era cierto de forma
vacía, por no existir un segundo módulo del que aislarse. El escenario que sigue estaba diferido en
`openspec/specs/build-integrity/spec.md` (líneas 26 a 32) a los cambios de F0 que introdujeran esos
dos módulos: `institution-root-and-multitenancy-baseline` (cambio 4, primer módulo) y
`staff-authentication-mfa-sessions` (cambio 7, segundo módulo). El cambio 7 se dividió el
2026-09-24 en tres cambios SDD secuenciales, y `identity-module-and-password-authentication` es la
primera de esas tres partes: es el mismo trabajo que la nota diferida nombraba como «cambio 7», bajo
otro nombre de cambio.

#### Escenario: Importación cruzada de dominio

- **DADO** dos módulos de negocio existentes
- **CUANDO** el módulo A importa una clase de `domain` del módulo B
- **ENTONCES** `./mvnw verify` falla por la importación prohibida

#### Escenario: Cada módulo usa solo su propio dominio

- **DADO** los dos módulos de negocio existentes, `organization` e `identity`
- **CUANDO** ninguno importa el `domain` del otro
- **ENTONCES** `./mvnw verify` termina en verde

## ADDED Requirements

### Requisito: Tabla nueva de la cuenta de personal, con `institution_id` y seguridad de fila forzada

El sistema DEBE mantener la migración `V5`, que crea la tabla nueva de identidad —con el prefijo de
módulo `identity_`, según ADR-0015 regla 3— para la cuenta de personal y su estado de retroceso por
intentos fallidos. La tabla DEBE declarar `institution_id NOT NULL` (ADR-0009, punto 1) y toda
restricción única DEBE incluir `institution_id` como discriminador (ADR-0009, punto 2). La tabla
DEBE tener `ENABLE ROW LEVEL SECURITY` y `FORCE ROW LEVEL SECURITY`, con una política sobre
`institution_id` que siga el mismo patrón `current_setting(..., true)` con fallo cerrado ante `NULL`
que ya usan las migraciones `V1` a `V4` (`docs/03-seguridad.md` §6.2). La migración DEBE partir de
`REVOKE ALL ... FROM PUBLIC` antes de conceder ningún privilegio explícito.

#### Escenario: Las puertas genéricas de esquema pasan sobre la tabla nueva sin lista de exclusión

- **DADO** la tabla nueva de identidad ya migrada, con `institution_id NOT NULL`, su seguridad a
  nivel de fila habilitada y forzada, y su restricción única con discriminador de institución
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
  —`institution_id NOT NULL`, `relrowsecurity` y `relforcerowsecurity` en verdadero, prefijo de
  módulo `identity_`, y restricción única con discriminador de institución—
- **ENTONCES** todas pasan sobre la tabla nueva sin necesitar ninguna excepción ni entrada en una
  lista de exclusión

#### Escenario: Una institución no lee la cuenta de personal de otra

- **DADO** dos instituciones, cada una con al menos una cuenta de personal en la tabla nueva
- **CUANDO** la segunda, con su propio contexto de sesión y el rol `confia_admin_app`, consulta la
  tabla sin ningún predicado adicional
- **ENTONCES** solo obtiene las filas de su propia institución, nunca las de la primera

#### Escenario: Sin contexto de institución, la consulta devuelve cero filas, no un error de permiso

- **DADO** la tabla nueva de identidad ya migrada, con filas de al menos una institución
- **CUANDO** se consulta con el rol `confia_admin_app` sin que `app.institution_id` esté establecido
  en la transacción
- **ENTONCES** la consulta devuelve cero filas, porque la política deniega ante `NULL`, en vez de
  fallar por falta de permiso

### Requisito: Permisos de acceso a la tabla nueva de identidad por rol de base de datos

El sistema DEBE conceder sobre la tabla nueva de identidad, exactamente y sin uno más ni uno menos
(`docs/03-seguridad.md` §6.1): a `confia_admin_app`, `SELECT`, `INSERT` y `UPDATE`, sin `DELETE`, por
tratarse de una tabla no financiera; a `confia_portal_app`, **ningún privilegio**, porque §6.1
declara literalmente «sin acceso alguno a `user`, `role`, `cai_range`, `cashbox_session` ni
`shared_audit_log`»; a `confia_readonly`, solo `SELECT`, sujeto a la misma política de fila por
institución que los demás roles; a `confia_backup`, el alcance de `pg_read_all_data` que ya tiene
sobre el resto del esquema; a `confia_owner`, ningún privilegio adicional a los de propietario del
esquema, sin el atributo `BYPASSRLS`. El sistema DEBE extender `RolePrivilegeMatrixIT` con las filas
de la tabla nueva para los cinco roles.

#### Escenario: La matriz de privilegios cubre la tabla nueva para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de la tabla nueva de identidad
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de `docs/03-seguridad.md` §6.1 sobre esa tabla
  para cada uno de los cinco roles, sin uno más ni uno menos

#### Escenario: `confia_admin_app` puede leer, insertar y actualizar, pero no borrar

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` sobre la tabla nueva de identidad, y después
  intenta `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de
  privilegio

#### Escenario: `confia_portal_app` no tiene ningún privilegio sobre la cuenta de personal

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre la tabla nueva de identidad
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

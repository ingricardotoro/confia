# Delta para Integridad de la construcción

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `backup-restore-and-drill` (F0, cambio 12)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-09

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BI140` en adelante. La especificación vigente llega a `BI118`, pero los deltas
  en curso `rbac-permission-matrix-and-audit-integration` (`BI119` a `BI139`) y `dockerfiles-and-local-compose`
  (`BI119` a `BI127`) ya reservan números; `BI140` evita colisión con ambos. La numeración definitiva se reconcilia
  al archivar.
- Este delta solo añade requisitos: la política de lectura de `confia_backup` por tabla con su prueba de cobertura,
  la conservación de la invariante `NOBYPASSRLS` y el trabajo de CI de ida y vuelta. El comportamiento del
  respaldo, la restauración y la verificación vive en la capacidad nueva `backup-and-restore`.
- Ningún requisito vigente se modifica ni se elimina. Los requisitos vigentes que mencionan «el alcance de
  `pg_read_all_data` que ya tiene» `confia_backup` siguen siendo ciertos para los privilegios; este delta añade la
  política de fila por tabla que permite que ese alcance se ejerza bajo `FORCE ROW LEVEL SECURITY`.
- Etiquetas: `[LOCAL]` se comprueba en la construcción local y de CI con Docker.
- Decisión del propietario del 2026-11-09 (D2, opción B): `confia_backup` sigue sin `BYPASSRLS` y recibe una
  política de solo lectura por tabla.

## ADDED Requirements

### Requisito: `confia_backup` conserva `NOBYPASSRLS` y no gana privilegios de escritura

El sistema DEBE mantener a `confia_backup` sin el atributo `BYPASSRLS` y sin ningún privilegio de escritura
(`INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`) sobre ninguna tabla, después de aplicar este cambio. La invariante de
`docs/03-seguridad.md` §6.1 («ningún rol con `BYPASSRLS`») DEBE seguir probada para los cinco roles, y la prueba
`DatabasePipelineIT` y la matriz de privilegios DEBEN seguir pasando sin excepción para `confia_backup`.

#### Escenario: [BI140] [LOCAL] Ningún rol tiene `BYPASSRLS`, incluido `confia_backup`

- **DADO** la base migrada con este cambio aplicado
- **CUANDO** se consulta `pg_roles.rolbypassrls` para los cinco roles de la aplicación
- **ENTONCES** es falso para todos, incluido `confia_backup`

#### Escenario: [BI141] [LOCAL] `confia_backup` no puede escribir en ninguna tabla

- **DADO** la matriz de privilegios con las tablas existentes
- **CUANDO** se consultan los privilegios de `confia_backup` sobre cada tabla
- **ENTONCES** posee `SELECT` y ningún `INSERT`, `UPDATE`, `DELETE` ni `TRUNCATE`, y un intento de escritura con
  ese rol es rechazado

### Requisito: Toda tabla con seguridad de fila forzada tiene una política de lectura para `confia_backup`

El sistema DEBE definir, en cada tabla con `ENABLE` y `FORCE ROW LEVEL SECURITY`, una política `FOR SELECT TO
confia_backup USING (true)` y ninguna otra política para ese rol. Esa política NO DEBE aplicar a ningún otro rol y
NO DEBE conceder `INSERT`, `UPDATE`, `DELETE` ni `TRUNCATE`. Una prueba de esquema DEBE enumerar las tablas con
seguridad de fila forzada desde el catálogo y fallar si alguna carece de la política, sin lista de exclusión para
tablas nuevas.

#### Escenario: [BI142] [LOCAL] Una tabla nueva con seguridad de fila forzada y sin política de respaldo rompe la construcción

- **DADO** una migración de prueba (fixture) que crea una tabla con `ENABLE` y `FORCE ROW LEVEL SECURITY` pero sin
  la política de lectura de `confia_backup`
- **CUANDO** se ejecuta la prueba de esquema de cobertura
- **ENTONCES** falla señalando el nombre de la tabla y la política ausente

#### Escenario: [BI143] [LOCAL] Las tablas existentes están todas cubiertas

- **DADO** el esquema real tal como queda tras las migraciones
- **CUANDO** se ejecuta la prueba de esquema de cobertura
- **ENTONCES** pasa evaluando todas las tablas con seguridad de fila forzada (un conjunto no vacío) sin una lista
  de exclusión

#### Escenario: [BI144] [LOCAL] La política de respaldo no afecta a los demás roles

- **DADO** dos instituciones con filas en una tabla protegida y una sesión de `confia_admin_app` con el contexto de
  la primera institución
- **CUANDO** `confia_admin_app` consulta la tabla
- **ENTONCES** ve únicamente las filas de la primera institución, igual que antes del cambio

#### Escenario: [BI145] [LOCAL] `confia_backup` lee todas las instituciones solo con la seguridad de fila habilitada para la sesión

- **DADO** dos instituciones con filas en cada tabla protegida
- **CUANDO** `confia_backup` consulta cada tabla con `row_security` activado y sin contexto de institución
- **ENTONCES** obtiene las filas de ambas instituciones, igual al conteo del propietario

#### Escenario: [BI146] [LOCAL] La política cubre `shared_audit_log` sin debilitar su inmutabilidad

- **DADO** `shared_audit_log` con la política de lectura de `confia_backup`
- **CUANDO** `confia_backup` intenta `UPDATE` o `DELETE` sobre ella
- **ENTONCES** son rechazados, y los disparadores y privilegios de inmutabilidad vigentes no cambian

### Requisito: La migración de acceso de `confia_backup` se aplica sin editar migraciones anteriores y es reversible por una migración nueva

El sistema DEBE introducir el acceso de lectura de `confia_backup` mediante una migración nueva de Flyway, sin
editar ninguna migración ya aplicada, y las migraciones posteriores que creen tablas con seguridad de fila forzada
DEBEN incluir su política de respaldo. Revertir el cambio DEBE hacerse con una migración nueva que retire las
políticas.

#### Escenario: [BI147] [LOCAL] Las sumas de verificación de las migraciones anteriores no cambian

- **DADO** la base migrada hasta antes de este cambio
- **CUANDO** se aplica la migración nueva
- **ENTONCES** la validación de Flyway pasa sin sumas de verificación divergentes

#### Escenario: [BI148] [LOCAL] La migración de retirada deja el esquema sin políticas de respaldo

- **DADO** la base con las políticas de respaldo aplicadas
- **CUANDO** se aplica la migración de retirada en una base de pruebas
- **ENTONCES** ninguna tabla conserva política para `confia_backup` y la prueba de cobertura falla, lo que demuestra
  que la prueba detecta el estado revertido

### Requisito: La ida y vuelta de respaldo y restauración es un trabajo de integración continua que rompe la construcción

El sistema DEBE ejecutar el trabajo de ida y vuelta definido en `backup-and-restore` en cada empuje, como una
puerta de integración continua cuyo fallo rompe la verificación, con resultado visible en el remoto. El trabajo NO
DEBE depender de secretos reales del repositorio de CI ni de acceso a AWS o al proveedor fuera de AWS.

#### Escenario: [BI149] [LOCAL] El trabajo rojo se ve en el remoto

- **DADO** un empuje que rompe la restauración o la verificación
- **CUANDO** la integración continua ejecuta el trabajo de ida y vuelta
- **ENTONCES** el trabajo falla y el resultado en rojo es visible en el remoto

#### Escenario: [BI150] [LOCAL] El trabajo no necesita secretos reales

- **DADO** un entorno de integración continua sin ningún secreto real configurado
- **CUANDO** se ejecuta el trabajo
- **ENTONCES** termina en verde usando únicamente llaves y credenciales efímeras generadas para la prueba, y el
  repositorio no contiene ninguna llave age privada ni KEK de ejemplo reutilizable

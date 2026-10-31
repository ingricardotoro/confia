### 6.1 Roles de base de datos

| Rol | Uso | Privilegios |
|---|---|---|
| `confia_owner` | Migraciones. No lo usa la aplicación en ejecución | Propietario del esquema. `BYPASSRLS` no se concede |
| `confia_admin_app` | Proceso `confia-api-admin` | `SELECT`, `INSERT` en todas las tablas de negocio. `UPDATE` solo en tablas no financieras. Sin `DELETE` en ninguna tabla de negocio. Sobre las tablas técnicas, exactamente los privilegios de ADR-0017: `SELECT`, `INSERT`, `UPDATE` y `DELETE` en `scheduled_tasks` (db-scheduler toma, reprograma y retira tareas, ADR-0016) y en `event_publication` (Spring Modulith borra las publicaciones completadas), y solo `SELECT` en `flyway_schema_history`, para la comprobación de salud de migraciones aplicadas. Sin `UPDATE` ni `DELETE` en `shared_audit_log` ni `ledger_entry`. También lo usa `confia-worker` |
| `confia_portal_app` | Proceso `confia-api-portal` | `SELECT` sobre una lista corta y explícita de tablas. `INSERT` solo en `document_request`, `payment_intent` y `notification_preference`. Sobre las tablas técnicas, exactamente los privilegios de ADR-0017: `INSERT` en `scheduled_tasks`, más `SELECT` solo si el cliente de programación lo exige (validado en F0); ningún privilegio sobre `event_publication`, porque el portal no usa el registro de eventos; `SELECT` en `flyway_schema_history`, para la comprobación de salud. Ningún `UPDATE` ni `DELETE` en ninguna tabla. Sin acceso alguno a `user`, `role`, `cai_range`, `cashbox_session` ni `shared_audit_log` |
| `confia_readonly` | Reportes pesados y réplica de lectura desde fase dos | Solo `SELECT`, con RLS activa. Ningún acceso a las tablas técnicas de ADR-0017 |
| `confia_backup` | Trabajo de respaldo | `pg_read_all_data` |

Ninguno de estos roles es `SUPERUSER` y ninguno tiene el atributo `BYPASSRLS`. Ese detalle es
crítico: un rol con `BYPASSRLS` anula silenciosamente todas las políticas.

> **Nota editorial, 2026-09-24 (`identity-module-and-password-authentication`).** La tabla que esta
> sección llama `user` se entrega como `identity_staff_account`, con el prefijo de módulo que exige
> la regla 3 de ADR-0015. La tabla `identity_login_backoff`, que esta sección no nombra por ser
> posterior, recibe el mismo trato que `user`: `SELECT`, `INSERT` y `UPDATE` para
> `confia_admin_app`, sin `DELETE`; `SELECT` para `confia_readonly`; y **ningún privilegio** para
> `confia_portal_app`.
>
> **Adenda, 2026-09-28 (`column-encryption-and-mfa-totp`).** Las cuatro tablas de ese cambio,
> `shared_data_encryption_key`, `identity_mfa_totp_credential`, `identity_mfa_recovery_code` e
> `identity_mfa_totp_backoff`, reciben el mismo trato que `identity_login_backoff`: `SELECT`,
> `INSERT` y `UPDATE` para `confia_admin_app`, sin `DELETE`; `SELECT` para `confia_readonly`; y
> **ningún privilegio** para `confia_portal_app`.
>
> **Adenda, 2026-10-03 (`password-recovery-token`).** La tabla `identity_password_reset_token` recibe
> el mismo trato que las anteriores: `SELECT`, `INSERT` y `UPDATE` para `confia_admin_app`, sin
> `DELETE`; `SELECT` para `confia_readonly`; y **ningún privilegio** para `confia_portal_app`. Al no
> haber `DELETE`, los tokens vencidos, usados y reemplazados se conservan hasta que el cambio 9
> entregue la purga.


# Exploración: componente transaccional único y bitácora de auditoría encadenada

Parte B del cambio 5 de la fase F0. La parte A, `jooq-flyway-testcontainers-wiring`, está fusionada
y archivada en `openspec/changes/archive/2026-09-21-jooq-flyway-testcontainers-wiring/`.

> **Nota de persistencia.** El agente de exploración no dispuso de herramienta de escritura de
> archivos en su sesión, así que el orquestador transcribió este documento desde la observación de
> Engram `sdd/audit-log-and-transaction-runner/explore` (#458), corrigiendo un punto que el informe
> del agente afirmaba de forma incorrecta (ver «Riesgo descartado» al final).

## 1. Estado actual verificado en el código

Existe hoy: el reactor Maven, Flyway, la generación de código de jOOQ y Testcontainers cableados;
los cinco roles de PostgreSQL, ninguno `SUPERUSER` ni `BYPASSRLS`; la tabla
`organization_institution` con seguridad a nivel de fila habilitada y forzada; el adaptador
`JooqInstitutionRepository`, de solo lectura; y cuatro reglas de ArchUnit con sus fixtures de
violación permanentes.

No existe: `com.confia.shared.security`, la tabla de auditoría, el disparador de hash, el
componente transaccional, ni `CommittingPostgresIntegrationTest`.

## 2. Qué entrega esta parte B

Según la nota de división del 2026-09-20 en `openspec/changes/foundations-plan/exploration.md`:

1. El **componente transaccional único** de `shared/security` (ADR-0015, regla 7).
2. La **bitácora de auditoría de solo inserción, encadenada por hash** (`docs/03-seguridad.md` §12).

## 3. Decisión del propietario sobre el ancla externa

El **ancla externa** de la cadena —publicación horaria del último `row_hash` en almacenamiento de
objetos con bloqueo en modo de cumplimiento, `docs/03-seguridad.md` §12.1— **queda fuera de esta
parte B** y será un cambio propio, con ranura explícita en el roadmap, atado al cambio 11, que es
el que trae la infraestructura de contenedores y despliegue.

El propietario eligió esta opción sobre declarar un puerto sin adaptador, porque ADR-0019
desaconseja superficie especulativa sin consumidor de producción.

**Consecuencia que la especificación DEBE declarar de forma visible, no en una nota al pie.**
`docs/03-seguridad.md` §12.1 dice textualmente que sin el ancla «la cadena solo protege contra
manipulación torpe». Un atacante con acceso administrativo al motor que recalcule la cadena entera
no deja divergencia detectable mientras no exista un ancla publicada fuera de la base de datos. La
especificación de este cambio debe declarar ese límite como requisito, para que nadie lea la
bitácora como inviolable mientras no lo sea.

## 4. Respuestas a las preguntas de la exploración

### 4.1 `CommittingPostgresIntegrationTest` es necesaria aquí

Confirmado. El `design.md` de la parte A la asignó al corte A1, la parte A decidió no crearla y lo
documentó dos veces. Esta parte B es su primer consumidor real, porque tres grupos de pruebas
necesitan filas **confirmadas**, visibles fuera de la transacción de la propia prueba:

- La prueba de manipulación de `docs/03-seguridad.md` §12.4: insertar filas, alterar una
  directamente con `SUPERUSER` y verificar que el verificador la detecta e identifica la fila exacta.
- La concurrencia del disparador `BEFORE INSERT`: dos transacciones reales y confirmadas insertando
  a la vez deben encadenar `prev_hash → row_hash` sin condición de carrera.
- El verificador de cadena, que recalcula desde el registro génesis y necesita datos persistidos
  entre métodos.

Ninguna es posible bajo la reversión automática de `TransactionalPostgresIntegrationTest`.

### 4.2 La regla R3 deja de ser una guarda vacía

`TransactionsOnlyInSharedSecurityTest` prohíbe hoy `@Transactional`, `TransactionTemplate` y
`PlatformTransactionManager` fuera de `com.confia.shared.security`, un paquete que no existe. Su
propio Javadoc lo declara guarda preventiva.

Cuando este cambio cree ese paquete con el componente real, la rama de exención deja de estar vacía
por primera vez: la regla pasa de custodiar un futuro hipotético a proteger producción real. El
Javadoc de la regla y el de `BadTransactionalRepository` quedan obsoletos y deben corregirse.

**Recomendación:** añadir una aserción positiva —que `shared.security` sí contiene una clase que usa
la API de transacciones—. Sin ella, si alguien implementara el componente con otro mecanismo por
error, la regla seguiría en verde sin custodiar nada. Es la misma familia de defecto que la parte A
pagó por aprender.

### 4.3 El hash se calcula en la base, no en la aplicación

`docs/03-seguridad.md` (alrededor de la línea 1354): el cálculo del hash y la inserción ocurren en
un disparador `BEFORE INSERT`, «así una escritura por cualquier vía queda encadenada».

Implicaciones:

- El componente transaccional no calcula ni pasa `prev_hash` ni `row_hash`. Solo abre la
  transacción, fija el contexto de sesión (`docs/03` §6.2) y ejecuta el caso de uso.
- Permisos ya decididos en `docs/03` §6.1 y §12.3: `confia_admin_app` con `SELECT` e `INSERT`, sin
  `UPDATE` ni `DELETE`; `confia_portal_app` sin acceso.
- Verificación: el patrón de `RolePrivilegeMatrixIT`, extendido con filas para `shared_audit_log`.
- **Punto nuevo que el patrón existente no cubre:** los disparadores deben rechazar la actualización
  y el borrado **incluso al propietario del esquema**, `confia_owner`. `organization_institution` no
  necesitó esto; aquí necesita su propio caso de prueba.

### 4.4 La serialización canónica se duplica, y ahí está el riesgo

La serialización canónica —ordena claves, normaliza números— vive en PL/pgSQL, dentro del
disparador. El verificador de cadena, en Java, tiene que reproducir **exactamente** el mismo
algoritmo para comparar.

Esa duplicación es el riesgo técnico principal del cambio: una divergencia silenciosa entre las dos
implementaciones produce **falsos positivos de manipulación** en producción, que es el peor
resultado posible para un control cuyo valor entero depende de su credibilidad.

**Recomendación:** fijar el algoritmo de forma explícita en el diseño, por ejemplo adaptando la
canonicalización de RFC 8785, y escribir una prueba de propiedades con jqwik —ya está en el stack—
que compare los hashes de ambas implementaciones sobre entradas generadas. No hay PL/pgSQL escrito
todavía: es un punto de diseño abierto, no cerrado por la documentación actual.

### 4.5 Qué cabe del verificador de cadena

El criterio de salida 3 de F0 —«El verificador de cadena de auditoría detecta una manipulación
inyectada en una prueba», `docs/09-roadmap-y-fases.md`— está asignado a este cambio.

- **Dentro:** una rutina invocable y probada que recalcula `row_hash` y reporta la primera fila
  divergente, demostrada con una prueba de manipulación sobre filas confirmadas.
- **Fuera:** la programación recurrente de las tres cadencias, que necesita db-scheduler y llega con
  el cambio 9; y el ancla externa, del cambio 11.
- **Recomendación:** nombrar aquí el contrato público del verificador, para que el cambio 9 solo
  tenga que envolver la programación.

### 4.6 `audit:read` queda fuera

`docs/03-seguridad.md` §6.3: el criterio de seguridad por fila de la bitácora es solo la institución.
El filtro por permiso —personal con `audit:read`— se aplica en la capa `web`, no en la política de
fila, y depende del control de acceso por roles, que llega con el cambio 8, dependiente del 7.

Esta parte B crea `shared_audit_log` con su política de institución, que además exigen
automáticamente las puertas genéricas de `MultiTenantSchemaIT`, pero no construye lectura, ni
controlador, ni verificación de `audit:read`.

**La especificación debe declarar esta exclusión de forma explícita**, y evitar el error que cometió
la parte A: una nota de «fuera de alcance» que quedó obsoleta sin corregir, y que hubo que retirar a
mano durante el archivado porque vivía fuera de todo bloque `### Requisito:`.

### 4.7 Deudas heredadas de la parte A

| Deuda | Encaje aquí | Razón |
|---|---|---|
| W3: nada impide nombrar `*Test` a una clase que necesita contenedor | **Sí, natural** | Esta parte es justo donde `CommittingPostgresIntegrationTest` obtiene sus primeras subclases. Una regla ligera —toda subclase de `PostgresIntegrationTest` termina en `*IT`— con el patrón de dos mitades ya establecido |
| W1: el presupuesto de 8 minutos no se exige | Oportunista | Solo si sale barato. Si no, diferir |
| W2: la fuente única no cubre al segundo consumidor | **No, sería relleno** | Toca maquinaria que esta parte no necesita tocar. Diferir al cambio que traiga Renovate |

## 5. Áreas afectadas

- `docs/03-seguridad.md` §12, §6.1, §6.3 — todas nombran `audit_log`, no `shared_audit_log`.
- `.claude/skills/confia-audit-logging/SKILL.md` — misma inconsistencia, con ejemplos de código.
- `docs/adr/ADR-0015` regla 7 y su verificación 7.
- `TransactionsOnlyInSharedSecurityTest` y `BadTransactionalRepository` — Javadoc obsoleto tras este
  cambio.
- `TransactionalPostgresIntegrationTest` — patrón a replicar para la clase de confirmación real.
- `RolePrivilegeMatrixIT` y `MultiTenantSchemaIT` — extensibles sin reescritura, porque verifican por
  pertenencia contra el catálogo real.
- `docs/09-roadmap-y-fases.md` — deudas heredadas y criterio de salida 3.
- Otros archivos detectados por búsqueda con `audit_log`, no leídos individualmente: `docs/07`,
  `docs/08`, `.claude/agents/confia-database.md`, `.claude/skills/confia-security-checklist/SKILL.md`
  y dos runbooks. Revisar durante la propuesta.

## 6. Enfoque recomendado

**Cortes encadenados**, como la parte A. División natural:

1. Componente transaccional más corrección de la regla R3, sin tabla.
2. Migración `shared_audit_log` con sus disparadores y permisos, más extensión de las puertas de
   esquema.
3. Clase base de confirmación real, verificador de cadena y pruebas de manipulación y concurrencia.

El pronóstico de tamaño es estructuralmente mayor que el de la parte A, que consumió 1 502 líneas en
tres pull requests encadenados sobre un alcance más angosto.

## 7. Riesgos

- **Divergencia de la serialización canónica** entre PL/pgSQL y Java. El más grave: produce falsos
  positivos de manipulación. Mitigación en §4.4.
- **El límite del ancla externa no es visible por defecto.** Mitigación: requisito explícito en la
  especificación, §3.
- **Deriva del nombre de la tabla.** Al menos cuatro documentos verificados siguen usando
  `audit_log` en vez del `shared_audit_log` ya decidido. Si la propuesta no los actualiza, el
  repositorio queda con guías contradictorias.
- **La regla R3 sin aserción positiva** seguiría en verde aunque el componente real no usara la API
  de transacciones.

### Riesgo descartado tras verificación del orquestador

El informe del agente de exploración planteó como riesgo más urgente una «reconciliación del
presupuesto de revisión: 400 vs 800 vs `single-pr`», que exigiría decisión del propietario antes de
proponer. **Ese conflicto no existe**, y la propuesta no debe abrirlo:

- El presupuesto es de **ochocientas líneas de cambio efectivo**, fijado por el propietario el
  2026-09-18 y documentado en `docs/15-flujo-de-trabajo-git.md` §3 y en `CLAUDE.md`. El valor de 400
  es el genérico por defecto del orquestador, superado por esa decisión.
- La estrategia de entrega de esta sesión es **`auto`**, cortes encadenados, recogida en la preflight
  de sesión del 2026-09-21. El valor `single-pr` que el agente leyó proviene del registro de
  `sdd-init` del 2026-09-15, ya caducado y corregido desde entonces.

## 8. Listo para proponer

Sí. La propuesta debe redactar el límite del ancla externa como requisito visible de especificación,
y no reabrir el presupuesto de revisión.

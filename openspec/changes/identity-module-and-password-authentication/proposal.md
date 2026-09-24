# Propuesta: identidad del personal, contraseña y segundo factor

- **Cambio:** `staff-identity-password-and-mfa` (parte A del cambio 7 de F0)
- **Fase del roadmap:** F0, entregable 3 (`docs/09-roadmap-y-fases.md` §3)
- **Exploración:** `openspec/changes/staff-identity-password-and-mfa/exploration.md` (compartida con
  la parte B; describe el cambio 7 completo, antes de la división)
- **División aprobada:** nota del 2026-09-24 en `openspec/changes/foundations-plan/exploration.md` §1
- **Parte B:** `session-tokens-and-web-layer`, que depende de esta
- **Rama:** `change/staff-authentication-mfa-sessions`
- **Depende de:** cambios 1, 2, 4, 5A, 5B y 6, todos fusionados y archivados
- **Estado:** pendiente de aprobación del propietario (`openspec/config.yaml`, `rules.proposal`)

## Intención

`openspec/specs/identity/spec.md` es una capacidad **publicada** con ocho requisitos, y en el árbol
**no existe ni una sola clase bajo `com.confia.identity`**: verificado, el paquete no aparece en
`apps/api/app/src/main/java/com/confia/`, donde hoy solo viven `bootstrap`, `organization` y
`shared`. Tampoco existe ninguna tabla de identidad: las migraciones aplicadas son `V1`
(`organization_institution`), `V2` y `V3` (`shared_audit_log` y su encadenamiento) y `V4`
(`shared_idempotency_key`).

Eso deja tres compromisos abiertos, y los tres se pagan aquí:

1. **Una capacidad publicada sin sustrato.** Ocho requisitos con dieciséis escenarios prometen
   comportamiento que hoy nada sostiene. A diferencia de `payments`, que promete para F3, `identity`
   promete para **F0**: es el entregable 3, y los cambios 8 y 9 dependen de él.
2. **El sistema no tiene todavía su segundo módulo de negocio, y una puerta espera ese momento.**
   `openspec/specs/build-integrity/spec.md` (líneas 26–32) difiere el escenario «Cada módulo usa solo
   su propio dominio» y nombra a este cambio como **el único que puede demostrarlo**, porque su
   premisa exige dos módulos de negocio existentes. `organization` es el primero; `identity` es el
   segundo.
3. **No existe ninguna acción sensible de producción que auditar, y por eso la bitácora sigue sin
   escritor.** El cambio 5B excluyó el adaptador de escritura de auditoría en Java con un motivo
   escrito y un dueño nombrado: «No hay ninguna acción sensible en producción que auditar hasta los
   **cambios 7 y 8**» (`openspec/changes/archive/2026-09-22-audit-log-and-transaction-runner/proposal.md`,
   líneas 115–118). Autenticarse, fallar, bloquearse, inscribir el segundo factor y recuperar la
   contraseña son exactamente esas acciones, y `docs/03-seguridad.md` §12.2 las enumera en su primera
   fila. Este cambio entrega el contenido del continente que el cambio 5B construyó.

Este cambio es **fundación de identidad sin exposición HTTP**. No emite ningún token, no abre ningún
endpoint y no arranca ningún proceso de producción. Su valor se mide por lo que hace posible: la
parte B no puede emitir una sesión sin una identidad verificada, y el cambio 8 no puede asignar
permisos a usuarios que no existen.

## Alcance

### Dentro de alcance

1. **Módulo de negocio `identity`**, con sus cuatro capas conceptuales según ADR-0002 y la regla
   hexagonal ya vigente para `organization`: `domain`, `application`, `infrastructure`. **Sin
   paquete `web`**: ver «Fuera de alcance».
2. **Cierre del escenario diferido W2** de `build-integrity` («Cada módulo usa solo su propio
   dominio»), que este cambio es el único capaz de demostrar porque introduce el segundo módulo de
   negocio real.
3. **Migración `V5` del esquema de identidad**, con nombres según la regla 3 de ADR-0015 —prefijo
   estricto `<módulo>_<entidad>`, con el precedente literal de `shared_audit_log` y
   `shared_idempotency_key`—: la cuenta de personal, su segundo factor, sus códigos de recuperación,
   el estado de bloqueo y el token de recuperación de contraseña. Cada tabla con `institution_id
   NOT NULL` (ADR-0009, punto 1), toda restricción única incluyéndolo (punto 2), seguridad a nivel de
   fila **habilitada y forzada** con el mismo patrón `NULLIF` de `V1` a `V4`, y `REVOKE ALL ... FROM
   PUBLIC` más los `GRANT` exactos que `docs/03-seguridad.md` §6.1 concede a cada rol.
4. **Migración de la tabla de llaves de datos** (`shared_data_key`), sustrato del sobre de llaves de
   `docs/03-seguridad.md` §7.3. Ver la decisión **D1**.
5. **Extensión de las puertas de esquema existentes, sin reescribirlas**: filas de las tablas nuevas
   en `RolePrivilegeMatrixIT` para los cinco roles —incluido explícitamente que `confia_portal_app`
   **no tiene ninguno** sobre la cuenta de personal, que `docs/03` §6.1 exige literalmente—, y
   verificación de que las puertas genéricas de `MultiTenantSchemaIT` pasan **sin lista de
   exclusión**.
6. **Hash de contraseña con Argon2id** tras un puerto de `application`, con su adaptador en
   `infrastructure`, y con la pimienta de 32 bytes fuera de la base de datos que exige
   `docs/03-seguridad.md` §4.1. Ver el punto de diseño **P1**.
7. **Caso de uso de autenticación con contraseña**, que devuelve un **resultado tipado** y no un
   token. Ver «Enfoque», punto 3, y la decisión **D4**.
8. **MFA TOTP**: verificación según RFC 6238 con SHA-1, seis dígitos, periodo de 30 segundos y
   ventana ±1; prevención de reutilización por contador monótono; inscripción del segundo factor;
   diez códigos de recuperación de un solo uso hasheados con el mismo Argon2id. Parámetros ya fijados
   por `docs/03-seguridad.md` §4.3; esta propuesta no los reabre.
9. **Cifrado a nivel de columna del secreto TOTP en reposo**, con sobre de llaves AES-256-GCM. Es la
   decisión principal de esta propuesta. Ver **D1**.
10. **Bloqueo por intentos fallidos con retroceso exponencial**, en su dimensión **por cuenta**, con
    el estado en PostgreSQL. Ver **D2** y **D3**.
11. **Recuperación de contraseña con token de un solo uso**: 32 bytes de un generador
    criptográfico, almacenado solo como SHA-256, vigencia de treinta minutos, consumo dentro de la
    misma transacción que cambia la contraseña, e invalidación de los anteriores al emitir uno nuevo
    (`docs/03-seguridad.md` §4.7; ADR-0005 para la vigencia).
12. **Prevención de enumeración de usuarios en la mitad que no es HTTP**: resultado idéntico para
    cuenta existente e inexistente, y verificación Argon2id contra un hash señuelo constante para que
    el tiempo observable no distinga los casos (`docs/03` §4.6). La mitad de códigos de estado y
    cuerpo de respuesta es de la parte B.
13. **Puerto y adaptador jOOQ de escritura de la bitácora de auditoría**, que el cambio 5B difirió a
    este cambio con dueño nombrado, con los eventos de identidad de `docs/03` §12.2 escritos **dentro
    de la misma transacción** que el efecto que los produce.
14. **ADR nuevo sobre el cifrado de columna en la aplicación**, si el propietario aprueba **D1**.
15. **Notas editoriales fechadas** sobre `docs/03-seguridad.md` §4.4, §6.1 y §7.3, y actualización de
    `docs/09-roadmap-y-fases.md` en el entregable 3 con su mitad diferida nombrada.

### Fuera de alcance

Cada exclusión lleva **destino nombrado**, y las que un cambio posterior vaya a cerrar se escriben
**dentro de bloques `### Requisito:`** del delta, nunca como prosa suelta: es la disciplina que el
cambio 5B estableció y el cambio 6 aplicó, después de que el cambio 5A tuviera que retirar a mano un
«Fuera de alcance» que su propia entrega volvió falso.

**Todo lo siguiente pertenece a la parte B, `session-tokens-and-web-layer`**, por decisión del
propietario del 2026-09-24. Esta propuesta no lo entrega, y tampoco lo entrega «preparado para»:

- La capa `web` completa y el primer controlador, con la conversión de `optionalLayer("Web")` en capa
  obligatoria y la retirada de su entrada de `EmptyShouldExceptionInventoryTest`.
- Spring Security, y con ella toda la cadena de filtros.
- JWT con EdDSA, JWKS, y la emisión de cualquier token de sesión o de refresco.
- Rotación del token de refresco con detección de reutilización y revocación de familia.
- El adaptador real de `CurrentInstitutionProvider` sobre el token y su prueba de ADR-0009.
- El registro de la fuente de datos de producción y la retirada de la exclusión de
  `DataSourceAutoConfiguration` en los tres puntos de entrada.
- La mecánica genérica de RBAC con su guarda de arranque.
- El cableado HTTP de la idempotencia que el cambio 6 difirió, CSRF, CSP, Problem Details y
  springdoc.

Y además, con destino nombrado fuera de la parte B:

- **Dimensión por dirección IP del retroceso exponencial** y las tres reglas de IP de `docs/03`
  §4.4, más el límite de tasa de §10: **parte B** para el control, porque una dirección IP solo
  existe en el borde HTTP y aquí no hay petición; y **cambio 11**
  (`containerization-and-cicd-pipeline`) para el aprovisionamiento de Redis que hoy no existe. Ver
  **D2**.
- **Verificación contra listas de contraseñas comprometidas** (`docs/03` §4.2, sus dos capas) y
  **rehash transparente de parámetros** (§4.1): ningún requisito publicado de `identity` los exige
  —los ocho requisitos no mencionan política de contraseñas—, y la lista local de cien mil entradas
  es un artefacto empaquetado con su propia procedencia y licencia que no cabe en el presupuesto de
  revisión de este cambio. Destino: **cambio 8** o un cambio de identidad posterior. Ver **D6**.
- **Calibración de Argon2id en el servidor real** (`docs/03` §4.1, «Calibración obligatoria antes de
  producción»): exige el servidor de producción dentro del contenedor y con sus límites de memoria.
  No existe ningún entorno desplegado. Destino: **cambio 11**, con el valor elegido registrado en un
  ADR con fecha y hardware, como el propio §4.1 pide.
- **Prueba de extremo a extremo del flujo completo de MFA con Playwright** (`docs/03` §4.3, «Cómo se
  comprueba»): el cambio 3 no está archivado, no hay `packages/*` ni aplicación React. Destino: F1 o
  posterior, cuando exista la pantalla.
- **Envío del correo de recuperación**: no existe la capacidad `notifications`. Esta propuesta
  entrega el puerto de despacho y no su adaptador de producción, por el mismo criterio de ADR-0019
  que el cambio 5B aplicó para no entregar un adaptador sin consumidor. Destino: el cambio que traiga
  `notifications`.
- **Trabajo de integridad diario que suspende sesiones de usuarios con escritura financiera y MFA
  inactiva** (`docs/03` §4.3, «Regla dura»): exige db-scheduler y la matriz de permisos. Destino:
  **cambios 8 y 9**.
- **Identidad de encargados** (dominio `portal`): la capacidad `identity` cubre las dos audiencias,
  pero el entregable 3 de F0 es explícitamente «autenticación del personal». Destino: F2, con el
  portal.

## Capacidades

> Contrato con `/sdd-spec`.

### Nuevas

**Ninguna.** `identity` ya está publicada con ocho requisitos. Esta propuesta es un **delta puro**
sobre ella, como recomendó la exploración: la capa `web` vive dentro del módulo por la regla
hexagonal ya vigente, así que dividir la capacidad en dos no tendría dónde apoyarse.

### Modificadas

- **`identity`**: delta con `## ADDED Requirements` para el comportamiento nuevo que la parte A
  entrega sin token —resultado tipado de autenticación, inscripción y verificación del segundo
  factor, cifrado del secreto en reposo, estado de bloqueo persistente, ciclo de vida del token de
  recuperación, y las exclusiones con destino nombrado— y `## MODIFIED Requirements` sobre dos
  requisitos publicados cuya redacción actual no se puede cumplir con honestidad sin capa web. Ver
  **D3** y **D4**.
- **`build-integrity`**: delta con `## MODIFIED Requirements` sobre el requisito «Aislamiento del
  `domain` entre módulos», para incorporar el escenario diferido W2 que este cambio cierra, y
  retirar la nota de diferimiento de las líneas 26–32.

## Las cuatro decisiones que esta propuesta resuelve

### 1. Cifrado del secreto TOTP: en la aplicación, con sobre de llaves

**El hecho de partida, con su referencia exacta y sin exagerarlo.** La sonda S6 del cambio 5 parte B
**no comprobó si los archivos de `pgcrypto` vienen en la imagen**: lo que estableció es que
`sha256(bytea)` es función interna (`pg_proc.prolang = 12`) y que por tanto `pgcrypto` **no hacía
falta** para aquel cambio
(`openspec/changes/archive/2026-09-22-audit-log-and-transaction-runner/design.md`, líneas 1292 y
1341). El hecho verificado y relevante aquí es otro, y es de privilegios:

> `CREATE EXTENSION pgcrypto` desde una migración de Flyway **fallaría**, porque Flyway migra como
> `confia_owner`, que **no es `SUPERUSER`**
> (`apps/api/app/src/test/resources/db/testing/create-test-roles.sql:11`,
> `CREATE ROLE confia_owner ... NOSUPERUSER NOBYPASSRLS ...`), y `pgcrypto` **no es una extensión de
> confianza**. Así lo escribe el diseño archivado del cambio 5 parte B, líneas 92 y 602–604.

La ruta nativa está cerrada **por privilegios**, no por ausencia comprobada del binario. Esa
distinción abre una alternativa que hay que evaluar y no despachar.

**Alternativa evaluada y descartada: crear la extensión con un operador `SUPERUSER`, fuera de
Flyway, en un paso de aprovisionamiento.** Es técnicamente posible y se rechaza por tres motivos
concretos, no por preferencia:

1. **Rompe la propiedad de que el esquema se reconstruye entero desde las migraciones**, que ADR-0017
   regla 3 fija («Esquema creado solo por migraciones») y que `docs/03` §2.6 sostiene como control
   contra el cambio manual no reproducible. Un objeto de base creado fuera de `db/migration` no
   aparece en ninguna revisión de pull request.
2. **No hay dónde ponerlo.** No existe infraestructura de aprovisionamiento: el cambio 11 sigue sin
   archivar y no hay Compose en el árbol. El único script privilegiado que existe es
   `create-test-roles.sql`, que es **solo de prueba** y declara serlo en sus propios comentarios
   (líneas 1–10). Añadir ahí un `CREATE EXTENSION` haría que las pruebas pasaran con una capacidad
   que ningún entorno real tendría, que es la peor clase de verde.
3. **Aun creada, `pgcrypto` no implementa el esquema que `docs/03` §7.3 ya decidió.** Ese documento
   fija AES-256-GCM con vector de inicialización de 96 bits, etiqueta de autenticación de 128 bits y
   **datos adicionales autenticados** (`tabla|columna|id_de_fila`), que es precisamente lo que impide
   mover un valor cifrado de una fila a otra. Según la documentación de PostgreSQL, la función
   `encrypt()` de `pgcrypto` admite solo los modos ECB y CBC, sin cifrado autenticado ni AAD.
   **Esta última afirmación es de documentación externa, no del árbol**, y no es determinante —los
   motivos 1 y 2 bastan—, pero si el diseño quisiera apoyarse en ella debe confirmarla con sonda. Ver
   **P2**.

**Recomendación: cifrado en la aplicación, con el sobre de llaves que `docs/03-seguridad.md` §7.3 ya
especifica, implementado sobre la criptografía del JDK.** Concretamente:

- **Algoritmo y formato: los de §7.3, sin inventar nada.** AES-256-GCM, vector de inicialización de
  96 bits aleatorio por operación y nunca reutilizado, etiqueta de 128 bits, AAD
  `tabla|columna|id_de_fila`, y formato almacenado `v1:<id_dek>:<iv>:<ciphertext>:<tag>`. El JDK
  provee `AES/GCM/NoPadding` y `SecureRandom` sin ninguna dependencia nueva.
- **Dónde vive la llave y cómo llega al proceso.** La llave maestra (KEK) **no se almacena en la base
  de datos ni en el repositorio**, según §7.3 y la regla 13 de `CLAUDE.md`. La parte A declara un
  **puerto** que entrega la KEK y un adaptador que la lee del **entorno del proceso**, nunca de un
  archivo del árbol. El aprovisionamiento real —SOPS con age y el gestor de secretos de `docs/03`
  §11— llega con el **cambio 11**; esta propuesta no lo adelanta y lo declara como límite conocido.
  En pruebas, la KEK entra por ese mismo puerto como credencial literal de prueba, con el precedente
  exacto y ya revisado de `create-test-roles.sql` líneas 8–10: un valor público, documentado como no
  secreto, usado solo dentro de un contenedor efímero.
- **Rotación sin reescribir filas a ciegas: para eso existe el sobre.** La KEK cifra la DEK, y la DEK
  cifra el valor de columna. Rotar la KEK recifra **solo las filas de `shared_data_key`**, no los
  secretos TOTP. Rotar la DEK sí exige recifrado progresivo, y por eso cada valor almacenado lleva
  su `id_dek` y la DEK antigua se conserva marcada como retirada para descifrar lo aún no migrado,
  exactamente como §7.3 prescribe. Sin el identificador de DEK en el propio valor, la rotación sería
  una reescritura a ciegas; con él, es un trabajo por lotes reanudable. La parte A entrega el
  **formato y el modelo que hacen posible la rotación**, y **no** entrega el trabajo por lotes que la
  ejecuta: sin db-scheduler (cambio 9) no hay dónde programarlo.
- **Tenencia de la tabla de llaves: `shared_data_key`, por institución y con seguridad de fila.**
  `docs/03` §7.3 la llama `data_key`, sin prefijo de módulo y sin decir nada de institución. Un
  nombre sin prefijo rompe la regla 3 de ADR-0015, y una tabla sin `institution_id` exigiría ampliar
  el catálogo cerrado de ADR-0017, que es exactamente la alternativa que
  `openspec/changes/foundations-plan/exploration.md` §2 descartó por contradecir el propósito del
  catálogo. Con prefijo, `institution_id` y política de fila, la tabla pasa las puertas genéricas sin
  excepción, y como beneficio secundario el radio de daño de una DEK comprometida queda acotado a
  una institución. **Alternativa descartada:** una tabla global sin institución, que sería más simple
  y exigiría un ADR para ampliar un catálogo que se declaró cerrado.
- **Alternativa descartada: cifrar directamente con la KEK, sin DEK ni tabla.** Sería menos código y
  una tabla menos. Se descarta porque convierte la rotación de la KEK en la reescritura de todos los
  secretos TOTP del sistema, que es justo lo que la pregunta del encargo pide evitar, y porque
  divergiría de un esquema ya escrito y aceptado en `docs/03` §7.3 sin ningún beneficio de
  seguridad.

**¿Merece un ADR nuevo?** Sí, y el siguiente número libre es **ADR-0022**: el ADR más alto del árbol
es `docs/adr/ADR-0021-ubicacion-del-codigo-generado-de-jooq.md`. Lo que justifica el ADR no es el
algoritmo —§7.3 ya lo fijó— sino cuatro decisiones que hoy no tienen respaldo en ninguna decisión
aceptada: que el cifrado ocurre **en la aplicación** y por qué la ruta nativa está cerrada; el
nombre, la tenencia y la política de fila de `shared_data_key`; la forma en que la llave maestra
llega al proceso antes de que exista gestor de secretos; y el modelo de rotación con `id_dek`
incrustado en el valor. Hay precedente directo: los ADR 0018, 0019, 0020 y 0021 nacieron todos de
cambios SDD de F0, y `openspec/config.yaml` (`rules.design`) exige elevar a ADR toda decisión de
arquitectura real en vez de enterrarla en `design.md`. **No cabe dentro de un ADR existente:** el más
cercano sería ADR-0015, que es de acceso a datos con jOOQ y no de criptografía, y los ADR aceptados
no se editan.

### 2. El estado del bloqueo vive en PostgreSQL, no en Redis

**Lo verificado.** `docs/03-seguridad.md` §4.4 dice literalmente «con estado en Redis». En el árbol
**no hay cliente de Redis** en `apps/api/app/pom.xml`, **no hay infraestructura de
aprovisionamiento** —el cambio 11 sigue sin archivar y no existe ningún archivo de Compose— y el
único motor disponible y probado es PostgreSQL, con Testcontainers desde el cambio 5A.

**Recomendación: PostgreSQL en la parte A.** Tres motivos, en orden de peso:

1. **Atomicidad con la auditoría, que es una regla no negociable.** La regla 14 de `CLAUDE.md` exige
   que toda acción sensible escriba en la bitácora, y `docs/03` §12.2 enumera «bloqueo por retroceso
   exponencial» como evento obligatorio. Registrar el intento fallido, aplicar el retroceso y
   escribir el asiento de auditoría en **una sola transacción** de `TransactionRunner` solo es
   posible si el estado vive en el mismo motor. Partirlo entre Redis y PostgreSQL crea una ventana de
   fallo parcial en la que la cuenta queda bloqueada sin rastro, o auditada sin bloqueo.
2. **Durabilidad.** Un contador de bloqueo es un control de seguridad. Redis sin persistencia lo
   pierde al reiniciar, y un atacante que provoque ese reinicio limpia el bloqueo de todas las
   cuentas. PostgreSQL da durabilidad por construcción, y el aislamiento por institución que ADR-0009
   exige, que un almacén de claves y valores no da gratis.
3. **Costo de introducir la primera dependencia de Redis.** Serían: la dependencia en el POM, su
   configuración por perfil, un segundo contenedor en la suite de integración, su modo de fallo —y
   `docs/03` §3 punto 4 exige **fallo cerrado**: si el verificador no responde, se rechaza— y el
   aprovisionamiento que no existe. Todo eso para un almacén cuyo único consumidor en la parte A es
   un caso de uso.

**Costo aceptado, dicho en voz alta.** Cada intento fallido se convierte en una escritura en
PostgreSQL, con contención sobre la fila de la cuenta bajo un ataque de fuerza bruta concentrado. Es
aceptable aquí: el retroceso exponencial acota por diseño la tasa de intentos por cuenta, que es
justo lo que hace que el volumen no crezca. La dimensión donde el argumento se invierte es la de IP,
y por eso es la que se difiere.

**Qué queda pendiente de migrar a Redis, y en qué cambio.** Las tres reglas de dimensión de IP de
`docs/03` §4.4 —límite por IP, y el indicador de relleno de credenciales por IP contra cuentas
distintas— más el límite de tasa general de §10. Son volumen alto, vida corta y sin necesidad de
durabilidad: el perfil exacto de Redis. **Dueño del control: la parte B**, porque una dirección IP
solo existe en el borde HTTP y aquí no hay petición. **Dueño del aprovisionamiento: el cambio 11.**
Se escribe como requisito con escenario ejecutable, cierto hoy y falso el día que la parte B lo
cierre.

**Esta recomendación no decide el modelo de bloqueo**, que es otra cosa y está en **D3**.

### 3. La costura con la parte B: cómo se demuestra el segundo factor sin emitir ningún token

**El problema, exacto.** Los dos escenarios publicados del requisito «MFA obligatoria para roles con
escritura financiera o de configuración» terminan en emisión de token: «emite un token de sesión con
los permisos del rol» y «emite únicamente una sesión restringida». La emisión de tokens es de la
parte B. Si la parte A no hace nada al respecto, o bien no puede demostrar nada, o bien adelanta
media capa de sesión para cumplir una frase.

**La solución: el caso de uso devuelve un resultado tipado, no un token.** El caso de uso de
autenticación devuelve un tipo sellado —nombres exactos en inglés, fijados por el diseño— con
exactamente estos desenlaces:

| Desenlace | Qué significa | Qué lleva |
|---|---|---|
| `Authenticated` | Contraseña correcta, y el segundo factor no es exigible o ya se verificó | Identificador de usuario, institución, y el **alcance de autorización** `FULL` |
| `SecondFactorRequired` | Contraseña correcta, el rol exige MFA y hay un secreto inscrito | Identificador de usuario y un **desafío** opaco, de un solo uso y de vida corta, almacenado hasheado con SHA-256, con el precedente exacto del token de recuperación |
| `SecondFactorEnrollmentRequired` | Contraseña correcta, el rol exige MFA y no hay secreto inscrito | Identificador de usuario y el alcance de autorización `MFA_ENROLLMENT_ONLY` |
| `Rejected` | Credenciales incorrectas, cuenta inexistente, o bloqueo vigente | Un motivo **interno** que la auditoría registra y que el cliente nunca verá distinto |

**Por qué un desafío no es un token.** No lleva reclamaciones, no está firmado, no transporta
permisos, no se valida en el cliente y no se puede presentar a ningún endpoint: es un identificador
opaco que el servidor almacena hasheado y que solo sirve para correlacionar el segundo paso con el
primero. Es el mismo mecanismo del token de recuperación que §4.7 ya especifica, no una sesión
adelantada. Si el diseño descubre que el desafío empieza a parecerse a una sesión, eso es la señal de
que se cruzó la línea.

**Qué contrato queda fijado para que la parte B no lo rediseñe.** Tres cosas y ninguna más:

1. **El tipo de resultado y sus cuatro desenlaces.** La parte B traduce cada uno a HTTP y a token;
   no añade desenlaces ni reinterpreta los existentes.
2. **El alcance de autorización como dato del resultado**, no como decisión de la capa web. La
   «sesión restringida cuya única acción permitida es configurar el segundo factor» del escenario
   publicado se decide **aquí**, en el caso de uso, y la parte B la materializa en las reclamaciones
   del token. Esto importa: si la decisión viviera en la capa web, sería una decisión de autorización
   tomada fuera del servidor de dominio, que es exactamente lo que el requisito «Autorización basada
   en permisos evaluada en el servidor» prohíbe.
3. **El origen de la institución previa a la autenticación.** Ver **D5**: es una costura que hay que
   fijar aquí o la parte B la resolverá por su cuenta, probablemente mal.

**Qué escenarios publicados quedan parcialmente cubiertos, nombrados uno a uno.** Esta es la tabla
que el delta de especificación tiene que sostener:

| Requisito publicado | Escenario | Parte A | Parte B |
|---|---|---|---|
| Autenticación con contraseña | Credenciales correctas | Verifica la combinación y **audita el éxito** | «emite un token de sesión válido para el dominio de personal» |
| Autenticación con contraseña | Contraseña incorrecta | **Completo**: error genérico, ningún artefacto de sesión, intento fallido registrado | — |
| MFA obligatoria | Cajero con MFA configurada | Exige el código de seis dígitos, valida la ventana de vigencia y rechaza la reutilización | «emite un token de sesión con los permisos del rol» |
| MFA obligatoria | Rol administrativo sin MFA configurada aún | Decide y devuelve el alcance restringido de inscripción | «emite únicamente una sesión restringida» y su cumplimiento en cada endpoint |
| Bloqueo por intentos fallidos | Primer bloqueo tras cinco fallos | **Completo** (con **D3** resuelta) | — |
| Bloqueo por intentos fallidos | Segundo ciclo con retroceso mayor | **Completo**, incluida la auditoría de cada ciclo con su duración | — |
| Recuperación de contraseña | Recuperación dentro de la ventana | **Completo** salvo el envío del correo, que no tiene capacidad dueña todavía | — |
| Recuperación de contraseña | Token expirado o superado por uno más reciente | **Completo** | — |
| Prohibición de enumeración | Correo existente | Resultado y tiempo indistinguibles a nivel de caso de uso | `202 Accepted` y el cuerpo literal del mensaje |
| Prohibición de enumeración | Correo inexistente | Ídem, y ningún correo enviado | Ídem |
| Rotación de token de refresco | Los dos escenarios | **No toca** | Completo |
| Separación entre dominios de identidad | Los dos escenarios | **No toca** | Completo |
| Autorización basada en permisos | Los dos escenarios | **No toca** | Parte B y **cambio 8** |

**Sí, el requisito publicado necesita un delta de redacción, y son dos.** Ver **D3** y **D4**.

### 4. Cómo se demuestra todo esto sin capa web y sin frontend

El precedente de los seis cambios anteriores es exacto y se sigue sin invención: pruebas unitarias de
dominio más pruebas de integración reales contra PostgreSQL con Testcontainers, conectadas con el
**rol de aplicación** y no con el propietario del esquema.

| Qué | A qué nivel | Con qué |
|---|---|---|
| Cálculo del retroceso, máquina de estados del bloqueo, ciclo de vida del token de recuperación, política mínima de contraseña | Unitario de `domain`, con reloj inyectado y sin `Instant.now()` dentro del dominio | JUnit, AssertJ, jqwik; cobertura 95 % y PIT 80, como exige `openspec/config.yaml` |
| TOTP según RFC 6238 | Unitario de `domain`, contra los **vectores de prueba oficiales del RFC** | Ídem |
| Sobre de llaves AES-256-GCM | Unitario, más integración | Patrón literal de `docs/03` §7.3: leer la columna con SQL crudo y verificar que **no contiene** el texto en claro y **sí** el prefijo `v1:`; e intentar descifrar con el AAD de otra fila y esperar fallo de autenticación |
| Política de fila y privilegios de cada tabla nueva | Integración con `confia_admin_app` y con `confia_portal_app` | `MultiTenantSchemaIT` y `RolePrivilegeMatrixIT` extendidos, más una prueba de contexto ausente que exige cero filas (`docs/03` §6.4, pruebas 1 a 4) |
| Atomicidad de efecto y auditoría | Integración con confirmación real | `CommittingPostgresIntegrationTest`: si el efecto falla, el asiento de auditoría no sobrevive |
| Intento fallido concurrente y token de recuperación de un solo uso bajo concurrencia | Integración con sincronización determinista | `CyclicBarrier`, patrón ya establecido en `TransactionRunnerRetryIT` y `IdempotentExecutorConcurrencyIT`. **Nunca esperas por reloj** |
| Adaptadores jOOQ | Integración | Precedente de `JooqInstitutionRepositoryIT` y `JooqIdempotencyRecordStoreIT` |
| Uniformidad de tiempo entre cuenta existente e inexistente | Integración, **medida y reportada** | Ver la advertencia de abajo |

**Lo que no se puede demostrar todavía, declarado como hizo el cambio 6 con su criterio de salida:**

| Lo demostrado aquí | Lo no demostrado, y su dueño |
|---|---|
| El caso de uso rechaza credenciales inválidas con un resultado genérico | Que un endpoint responda `401` con el cuerpo uniforme de `docs/03` §4.6 — **parte B** |
| Resultado idéntico para cuenta existente e inexistente en recuperación | Que la respuesta HTTP sea `202 Accepted` con el mensaje literal — **parte B** |
| La verificación del segundo factor y el alcance de autorización resultante | Que se emita un token con esos permisos, y una sesión restringida que los endpoints respeten — **parte B** |
| El bloqueo por cuenta con su retroceso | El bloqueo por IP y el indicador de relleno de credenciales — **parte B**, con aprovisionamiento del **cambio 11** |
| El secreto TOTP cifrado en reposo con llave del entorno | Que la llave provenga de un gestor de secretos real — **cambio 11** |
| Los parámetros de Argon2id declarados | Que estén calibrados a 250–500 ms en el servidor real — **cambio 11** |
| El flujo de MFA a nivel de caso de uso | La prueba de extremo a extremo con Playwright de `docs/03` §4.3 — cuando exista la pantalla |

**Advertencia honesta sobre la prueba de tiempo.** `docs/03` §4.6 especifica una prueba de 200
intentos con correo existente y 200 con inexistente, que falla si la diferencia de medianas supera
50 milisegundos. Esa prueba mide **respuestas HTTP** y aquí no hay ninguna; además, una prueba de
tiempo en una máquina compartida con un contenedor de PostgreSQL y una función de derivación
deliberadamente costosa es una candidata natural a ser escamosa. **Recomendación:** la parte A
entrega el mecanismo —verificación Argon2id contra un hash señuelo en el camino de cuenta
inexistente— y una prueba que **mide y reporta** la diferencia de medianas sin convertirla en puerta
de construcción, con la puerta real diferida a la parte B sobre respuestas HTTP. Una puerta escamosa
se termina desactivando, y una puerta desactivada es peor que un diagnóstico honesto. El mecanismo
sí se prueba de forma determinista: que el camino de cuenta inexistente ejecuta la verificación
señuelo se verifica por interacción, no por cronómetro.

## Enfoque

De adentro hacia afuera, como el cambio 5B: el dominio primero, porque aquí lo caro es la regla y no
el mecanismo de base de datos, que ya existe. Cada paso deja `./mvnw verify` en verde antes del
siguiente y sigue **TDD estricto** (`openspec/config.yaml`, `strict_tdd: true`, ejecutor
`./mvnw verify` en `apps/api`): rojo observado y registrado antes de cada verde, sin excepción.

### 1. El módulo y el esquema

`identity` es el segundo módulo de negocio. Su `package-info.java` lo declara como módulo de Spring
Modulith, igual que `organization`, y su sola existencia cierra el escenario diferido W2. Ninguna
clase de `identity.domain` importa de `organization.domain` ni al revés: esa es exactamente la
afirmación que el escenario diferido exige demostrar en verde.

La migración `V5` llega después de que el paquete exista, por la misma razón mecánica que obligó al
cambio 5: la puerta de prefijo de módulo de `TableOwnershipByModuleTest` se deriva de los paquetes de
producción reales, así que una tabla `identity_*` sin paquete `identity` no tendría dueño.

### 2. El cifrado, antes que el segundo factor

El sobre de llaves se construye y se prueba **antes** de que exista una sola columna de secreto TOTP.
El orden importa: si el secreto se modela primero, la tentación de guardarlo en claro «temporalmente»
es real, y una migración aplicada no se edita.

### 3. Autenticación, bloqueo y auditoría, en una sola transacción

El caso de uso recibe el `SecurityContext` de su llamador —hoy una prueba, mañana el controlador de
la parte B— exactamente como `IdempotentExecutor` lo recibe hoy. Ese es el precedente del árbol y
evita inventar un mecanismo nuevo de propagación.

Dentro de **una sola** invocación de `TransactionRunner.execute(...)`: se consulta el estado de
bloqueo, se verifica la contraseña —o el señuelo, si la cuenta no existe—, se actualiza el contador
de fallos, y se escribe el asiento de auditoría. Si algo falla, no queda ni bloqueo sin auditar ni
auditoría sin bloqueo.

**El puerto de escritura de auditoría es de este cambio, y su ubicación sigue el precedente exacto
del cambio 5B**: puerto en `com.confia.shared.audit`, junto a `AuditLogReader`, y adaptador jOOQ en
`com.confia.shared.infrastructure`, junto a `JooqAuditLogReader`. La regla R1 confina jOOQ a
`infrastructure` y este cambio no la toca.

### 4. Segundo factor y recuperación

TOTP se implementa sobre `javax.crypto.Mac` del JDK, en `domain`, sin biblioteca nueva. Ver **P3**
para el motivo y su alternativa. La recuperación reutiliza el patrón de token opaco hasheado con
SHA-256 que §4.7 fija, con el consumo y el cambio de contraseña en la misma transacción.

## Restricciones que este cambio no puede violar

Se escriben aquí porque un módulo de identidad es donde más barato resulta violarlas por descuido, y
porque van a ser criterio de revisión de cada pull request.

1. **Regla 11 de `CLAUDE.md`, la más delicada aquí: nunca registrar en logs contraseñas, tokens,
   cabeceras de autorización ni números de documento.** En este módulo la lista efectiva es más
   larga: la contraseña en claro, el hash Argon2id, la pimienta, el secreto TOTP en claro y cifrado,
   los códigos de recuperación, el token de recuperación de contraseña, el desafío de segundo factor,
   la KEK y la DEK. Ninguno de esos valores aparece en un mensaje de registro, en un mensaje de
   excepción, en un `toString()` ni en un aserto de prueba que los imprima al fallar. El diseño debe
   decidir cómo se garantiza —un envoltorio de valor con `toString()` redactado es el camino obvio— y
   la verificación debe comprobarlo, no confiarlo.
2. **Regla 14: toda acción sensible escribe en la bitácora.** Autenticación exitosa y fallida, cierre
   de sesión, cambio de contraseña, restablecimiento, alta y baja de MFA, uso de código de
   recuperación y bloqueo por retroceso. Es la primera fila de `docs/03` §12.2, íntegra, salvo el
   cierre de sesión, que no existe sin sesión.
3. **Regla 12: consultas siempre parametrizadas.** jOOQ las genera; la lista aprobada de SQL plano de
   ADR-0015 regla 9 no se amplía en este cambio.
4. **Regla 13: secretos jamás en el repositorio, ni en ejemplos ni en pruebas.** Las credenciales
   literales de prueba siguen el patrón ya revisado de `create-test-roles.sql` líneas 8–10: valor
   público, declarado no secreto en su propio comentario, y usado solo dentro de un contenedor
   efímero.
5. **Reglas de dependencia:** `application` → `domain`; `infrastructure` implementa puertos de
   `application`; `domain` no importa de nadie ni conoce ningún framework —lo que sitúa Argon2id,
   jOOQ y la criptografía en `infrastructure`, tras puertos—; y ningún módulo importa el `domain` de
   otro. `identity` no importa `organization.domain`.
6. **Regla 7: toda operación que afecte el libro mayor ocurre en una transacción explícita.** Aquí no
   hay libro mayor, pero la regla R3 sigue vigente: el único punto que abre transacciones es
   `com.confia.shared.security`, y este cambio no abre ninguna por su cuenta.
7. **`domain` sin reloj propio.** El bloqueo, el TOTP y la vigencia del token dependen del tiempo;
   todos lo reciben, ninguno lo lee.

## Decisiones que requieren aprobación explícita del propietario

Numeradas para que se respondan de una en una. **D1, D2 y D3 bloquean la especificación**; las demás
pueden resolverse durante la fase de especificación si el propietario lo prefiere.

- **D1. Cifrado del secreto TOTP en la aplicación, con sobre de llaves, y ADR-0022 nuevo.**
  Recomendación: sí, por la sección «1. Cifrado del secreto TOTP». Incluye cuatro sub-decisiones que
  se aprueban juntas: AES-256-GCM con el formato de §7.3; tabla `shared_data_key` con
  `institution_id` y política de fila; llave maestra por el entorno del proceso hasta el cambio 11; y
  ADR-0022 como decisión de arquitectura propia. **Alternativa descartada:** crear `pgcrypto` con un
  operador privilegiado fuera de Flyway, que rompe la reconstrucción del esquema desde migraciones,
  no tiene dónde vivir hoy, y aun así no implementa el cifrado autenticado con AAD que §7.3 exige.
  **Segunda alternativa descartada:** cifrar directamente con la KEK, sin DEK, que convierte cada
  rotación de llave en una reescritura de todos los secretos.

- **D2. El estado del bloqueo por intentos fallidos vive en PostgreSQL, no en Redis.**
  Recomendación: sí, por la sección «2». Implica que `docs/03` §4.4 recibe una nota editorial fechada
  y que la dimensión por IP queda fuera con dueño nombrado —parte B para el control, cambio 11 para
  el aprovisionamiento—. **Alternativa descartada:** introducir aquí la primera dependencia de Redis
  del proyecto, sin aprovisionamiento, sin Compose y sin un segundo consumidor, rompiendo además la
  atomicidad entre el bloqueo y su asiento de auditoría.

- **D3. Qué modelo de bloqueo gobierna, porque hoy hay dos y no son el mismo.** Esta propuesta no la
  resuelve sola porque cambia un parámetro de seguridad. Los dos modelos, ambos citados literalmente:
  - `openspec/specs/identity/spec.md`, requisito publicado: bloqueo **tras cinco intentos fallidos
    consecutivos**, un minuto el primer ciclo, cinco minutos el segundo.
  - `docs/03-seguridad.md` §4.4: **retardo** de `2^(n-3)` segundos **a partir del intento fallido
    3**, con tope de 900 segundos y contador que expira a los 30 minutos sin intentos.

  Difieren en el umbral (3 frente a 5), en la naturaleza del control (un retardo antes de responder
  frente a un bloqueo de la cuenta) y en la progresión. **Recomendación: gobierna la especificación
  publicada**, porque es el contrato aprobado, sus dos escenarios son exactos y verificables, y un
  cambio SDD no reescribe en silencio un requisito publicado; `docs/03` §4.4 recibe una nota
  editorial fechada que remite a él, con el precedente de la decisión D2 del cambio 5 sobre ADR-0003
  y de la nota del cambio 6 sobre ADR-0010. **Alternativa:** que gobierne §4.4, lo que exige un
  `## MODIFIED Requirements` sobre el requisito publicado con sus dos escenarios reescritos. El
  retardo uniforme de §4.4 aplicado también a cuentas inexistentes **se conserva en ambos casos**,
  porque pertenece a la prevención de enumeración de §4.6 y no al modelo de bloqueo.

- **D4. Delta de redacción sobre dos requisitos publicados de `identity`.** Recomendación: sí. Son
  dos, y ambos existen porque la redacción actual no se puede cumplir con honestidad sin capa web:
  1. **MFA obligatoria**: sus dos escenarios terminan en emisión de token. El delta debe separar lo
     que decide el caso de uso —exigir el segundo factor, validar el código, resolver el alcance de
     autorización— de lo que materializa la capa de sesión, y nombrar a la parte B como dueña de la
     segunda mitad. Sin este delta, la verificación de la parte A tendría que declarar dos escenarios
     incumplidos, o fingir que un desafío es un token.
  2. **Bloqueo, escenario «Primer bloqueo tras cinco fallos»**: dice que el sexto intento con la
     contraseña correcta «es rechazado por bloqueo activo, no por credenciales inválidas». Leído como
     comportamiento observable por el cliente, **contradice** la prohibición de enumeración de §4.6,
     que exige un único mensaje «Credenciales inválidas» y el mismo `401`: distinguir el bloqueo
     revela que la cuenta existe. El delta debe aclarar que la distinción es **interna y auditada**,
     nunca observable por el cliente, y que la parte B mapea ambos desenlaces a la misma respuesta.

- **D5. La institución previa a la autenticación proviene de configuración del servidor, nunca de la
  solicitud.** Esta es una costura que hay que fijar ahora. Toda tabla `identity_*` lleva política de
  fila sobre `app.institution_id`, y una política que recibe `NULL` **deniega** (`docs/03` §6.2,
  regla 4). Pero el inicio de sesión ocurre **antes** de que exista token, y ADR-0009 exige que el
  identificador de institución provenga del token autenticado y **nunca de un parámetro del
  cliente**. El puerto `CurrentInstitutionProvider` no resuelve esto: su Javadoc dice que el
  comportamiento sin sesión autenticada «is that adaptor's own decision», y su adaptador real es de
  la parte B. **Recomendación:** la institución previa a la autenticación proviene de la
  configuración del proceso —despliegue de una sola institución, ADR-0009 punto 5—, nunca del cuerpo,
  la cabecera ni la consulta; la parte A recibe esa institución en el `SecurityContext` que le pasa
  su llamador y **escribe el contrato como requisito con escenario**, para que la parte B lo
  materialice sin reinventarlo. **Alternativa descartada:** una consulta global sin contexto de
  institución, imposible sin `BYPASSRLS`, que ningún rol tiene ni tendrá (`create-test-roles.sql`,
  líneas 11–15, `NOBYPASSRLS` en los cinco).

- **D6. Alcance excluido de la parte A pese a estar en `docs/03`.** Recomendación: excluir, con los
  destinos de «Fuera de alcance»: listas de contraseñas comprometidas, rehash transparente,
  calibración de Argon2id, dimensión por IP, envío del correo de recuperación, trabajo de integridad
  diario de MFA, y extremo a extremo con Playwright. Ninguno está en los ocho requisitos publicados
  de `identity`, así que excluirlos no rompe ningún contrato publicado. **Alternativa:** incorporar
  alguno, con el costo de presupuesto de **D7**.

- **D7. Presupuesto y estrategia de entrega, donde hay una contradicción que el propietario debe
  resolver.** Tres datos que no encajan entre sí:
  - `docs/15-flujo-de-trabajo-git.md` §3 y `CLAUDE.md` fijan **800 líneas** de cambio efectivo por
    pull request; el preámbulo de esta sesión declara una política de revisión de **400**.
  - La estrategia de entrega declarada para esta sesión es **`single-pr`**.
  - El pronóstico de esta propuesta, en la sección siguiente, es de **2 400 a 4 000 líneas** y de
    **14 a 16 tareas**, contra el límite de **quince** de `openspec/changes/README.md`.

  **No cabe, y esta propuesta no va a fingir que cabe.** Recomendación: **un solo cambio SDD con
  cuatro cortes encadenados**, que es el precedente válido de los cambios 5 y 6 —el 5 se entregó en
  varios pull requests dentro de un mismo cambio, y el 6 declaró tres cortes— y que **no exige otra
  división de cambio**. Eso implica cambiar la estrategia de esta sesión de `single-pr` a
  `auto-chain`. **Alternativa, si el propietario mantiene `single-pr` literalmente:** partir la parte
  A en dos cambios SDD secuenciales, `identity-module-and-password-authentication` (módulo, esquema,
  privilegios, contraseña, bloqueo, auditoría, enumeración) y `mfa-totp-and-password-recovery`
  (cifrado de columna, TOTP, inscripción, códigos de recuperación, recuperación de contraseña), con
  el corte natural en el punto donde el cifrado entra en escena.

- **D8. Aprobación de la propuesta completa** antes de especificar, diseñar y planificar tareas
  (`openspec/config.yaml`, `rules.proposal`).

**No forman parte de estas decisiones**, por estar ya fijadas: la división del cambio 7 en parte A y
parte B, aprobada por el propietario el 2026-09-24; los parámetros de TOTP y de los códigos de
recuperación de `docs/03` §4.3; la vigencia de treinta minutos del token de recuperación de ADR-0005;
el modo de ejecución automático con TDD estricto; y el reparto de la capa web, la sesión y RBAC a la
parte B.

## Puntos de diseño abiertos

No son decisiones de producto. Son preguntas técnicas que esta propuesta **no cierra a propósito**.

- **P1. Cómo se aplica la pimienta de Argon2id, y con qué biblioteca.** `docs/03` §4.1 exige una
  pimienta de 32 bytes fuera de la base de datos, «aplicado como `secret` de Argon2id». **Afirmación
  sobre biblioteca externa, no verificada contra el árbol:** el `Argon2PasswordEncoder` de
  `spring-security-crypto` no expone un parámetro de secreto en su constructor, mientras que
  `Argon2BytesGenerator` de Bouncy Castle sí lo hace con `withSecret(...)`. Si se confirma, la
  pimienta obliga a Bouncy Castle directo con codificación propia del formato `$argon2id$`, en vez
  del codificador de Spring. El diseño debe confirmarlo con una sonda antes de elegir, porque de ello
  depende una dependencia nueva y el formato almacenado que la prueba de §4.1 verifica. Ambas
  bibliotecas son dependencias nuevas sujetas a la revisión obligatoria de `docs/03` §2.6; ninguna
  está en la lista de prohibidas del `maven-enforcer-plugin` de `apps/api/pom.xml`. **Y una
  precisión de alcance:** `spring-security-crypto` no es `spring-boot-starter-security`; usar el
  primero no adelanta la cadena de filtros ni contradice el reparto de la parte B.
- **P2. Si hace falta comprobar la disponibilidad de `pgcrypto` en la imagen.** Esta propuesta
  sostiene que la ruta está cerrada por privilegios y por el esquema de §7.3, sin necesidad de esa
  comprobación. Si el diseño quisiera apoyarse en la ausencia del binario, la sonda exacta es, contra
  la imagen declarada en la propiedad `confia.postgres.image` de `apps/api/pom.xml`:
  `docker run --rm postgres:18-alpine ls /usr/local/share/postgresql/extension/ | grep pgcrypto`, o
  `SELECT name, default_version FROM pg_available_extensions WHERE name = 'pgcrypto';` contra la
  instancia de Testcontainers. **Nadie lo ha comprobado todavía**, y esta propuesta no afirma su
  resultado.
- **P3. TOTP propio sobre el JDK, o biblioteca.** Recomendación: propio, en `domain`, sobre
  `javax.crypto.Mac` con `HmacSHA1`. Son unas decenas de líneas, RFC 6238 publica vectores de prueba
  oficiales que hacen la verificación objetiva, el dominio queda sin framework como exige ADR-0002, y
  se evita una dependencia nueva en la superficie más sensible del sistema. El único hueco del JDK es
  Base32, que el URI de aprovisionamiento necesita; el diseño decide entre implementarlo o
  diferir el URI a la parte B, que es quien lo expondría.
- **P4. Qué se escribe como `actor_label` al auditar un intento fallido contra una cuenta
  inexistente.** La columna es `NOT NULL` (`docs/03` §12.1) y el escenario publicado pide «registra el
  intento fallido asociado a la cuenta». Escribir el identificador presentado convierte la bitácora en
  un depósito de cadenas controladas por el atacante y, leída por un auditor, en un oráculo de
  enumeración. El diseño decide; la recomendación es una etiqueta constante cuando la cuenta no
  existe, y el identificador real cuando existe.
- **P5. Forma exacta del hash señuelo** que iguala el tiempo de la cuenta inexistente. Un literal mal
  formado falla rápido y no iguala nada; el señuelo tiene que costar lo mismo que una verificación
  real con los parámetros vigentes. El diseño fija cómo se obtiene sin introducir un secreto en el
  repositorio.
- **P6. Granularidad de la DEK.** Una por institución es la recomendación de tenencia; queda por
  decidir si además se separa por propósito —secreto TOTP hoy, documentos de identidad en F1— o si
  una sola DEK por institución sirve a todas las columnas cifradas. Afecta al volumen del recifrado
  futuro.
- **P7. Nombres exactos** de tablas, columnas, clases, puertos y del tipo de resultado de
  autenticación. En inglés, según `CLAUDE.md`. Las tablas llevan prefijo `identity_`, con el
  precedente literal de `shared_audit_log`.

## Impacto en migraciones, privilegios y auditoría

**Migraciones.** Una migración nueva, `V5`, con las tablas de identidad y la de llaves de datos. Se
aplica sobre un esquema que hoy tiene cuatro migraciones y ningún entorno desplegado, así que no
existe todavía la restricción de «una migración aplicada no se edita»: esa regla entra en vigor con
el cambio 11. El código de jOOQ se genera desde las migraciones en cada `generate-sources`, así que
una tabla mal nombrada rompe la construcción antes de compilar nada.

**Privilegios.** Se conceden exactamente los de `docs/03` §6.1 y ni uno más, verificados fila por
fila en `RolePrivilegeMatrixIT` para los cinco roles:

| Rol | Sobre las tablas de `identity` |
|---|---|
| `confia_owner` | Propietario, por ser quien migra. Sin `BYPASSRLS` |
| `confia_admin_app` | `SELECT`, `INSERT`, y `UPDATE` por tratarse de tablas no financieras. **Sin `DELETE`** |
| `confia_portal_app` | **Ninguno.** §6.1 dice literalmente «Sin acceso alguno a `user`, `role`, …» |
| `confia_readonly` | `SELECT`, con política de fila activa |
| `confia_backup` | Lectura, por `pg_read_all_data` |

Sobre `shared_data_key` la recomendación es más restrictiva que la regla general y el diseño debe
justificarla: `confia_readonly` no necesita leer material de llave para generar reportes, y
concedérselo ampliaría la superficie de un volcado de solo lectura, que es precisamente la amenaza
que §7.3 mitiga.

**Auditoría.** Este cambio entrega el **primer escritor de producción** de `shared_audit_log`. El
disparador de encadenamiento del cambio 5B asigna `id`, `prev_hash` y `row_hash`, así que el escritor
no calcula ningún hash: inserta, y el motor encadena. Los eventos son los de la primera fila de
`docs/03` §12.2. Cada asiento viaja en la misma transacción que su efecto. La bitácora deja de estar
vacía en producción, lo que significa que las pruebas de cadena ya existentes empiezan a cubrir
escrituras reales y no solo insertadas a mano por la prueba.

## Pronóstico de cortes y tamaño

**La estimación de 12 a 13 tareas de la tabla de F0 no se sostiene**, y la razón la escribió la
propia nota del 2026-09-24: esa tabla se redactó antes de casi toda la evidencia. Aquella estimación
cubría además el cambio 7 **completo**, incluidas la capa web y la sesión que ahora son de la parte
B. Inventario real de la parte A:

| Bloque de trabajo | Tareas |
|---|---|
| Módulo `identity`, `package-info`, cierre del escenario diferido W2 | 1 a 2 |
| Migración `V5`, política de fila, privilegios y extensión de las puertas de esquema | 2 a 3 |
| Sobre de llaves AES-256-GCM, `shared_data_key` y ADR-0022 | 2 a 3 |
| Argon2id tras puerto, autenticación, bloqueo con retroceso y hash señuelo | 3 a 4 |
| Puerto y adaptador jOOQ de escritura de auditoría, con sus eventos | 1 a 2 |
| TOTP, inscripción del segundo factor y códigos de recuperación | 2 a 3 |
| Recuperación de contraseña y sus pruebas de concurrencia | 1 a 2 |
| Notas fechadas de `docs/03` y `docs/09`, y exclusiones con destino nombrado | 1 a 2 |
| **Total** | **14 a 16** |

Roza el límite de quince de `openspec/changes/README.md` y puede superarlo. Si `/sdd-tasks` produce
más de quince, la alternativa de **D7** —partir la parte A en dos cambios secuenciales— deja de ser
opcional.

Estimación de líneas, con el historial de subestimación de 1,5× a 3× de este repositorio declarado y
**no** descontado:

| Corte | Contenido | Estimación |
|---|---|---|
| **A1 — módulo y esquema** | Módulo `identity`, escenario W2, migración `V5`, `shared_data_key`, política de fila, `REVOKE`/`GRANT`, filas de `RolePrivilegeMatrixIT`, `MultiTenantSchemaIT` sin exclusión | 500 a 800 |
| **A2 — cifrado y contraseña** | Sobre de llaves, ADR-0022, Argon2id tras puerto, caso de uso de autenticación, resultado tipado, hash señuelo | 700 a 1 100 |
| **A3 — bloqueo y auditoría** | Estado de bloqueo con retroceso, escritor de auditoría con su adaptador jOOQ, atomicidad y concurrencia | 600 a 1 000 |
| **A4 — segundo factor y recuperación** | TOTP, inscripción, códigos de recuperación, token de recuperación, notas documentales y exclusiones | 600 a 1 100 |
| **Total de código** | | **2 400 a 4 000** |
| Artefactos de OpenSpec | | 800 a 1 200 adicionales |

**Pronóstico de entrega: cuatro pull requests encadenados.** Al cerrar cada corte se mide el diff
real; si supera el presupuesto vigente que resuelva **D7**, la aplicación se detiene y el corte se
parte, que es el patrón que el cambio 5 pagó por aprender cuando su corte A3 midió 813 líneas.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| **Un secreto —contraseña, secreto TOTP, código de recuperación, KEK— termina en un log, en un mensaje de excepción o en la salida de una prueba fallida.** Es la violación más fácil de cometer en este módulo y la más cara | **Alta si no se diseña** | Restricción 1 de la sección de restricciones, con envoltorios de valor de `toString()` redactado y verificación explícita, no confianza |
| **La llave maestra no tiene gestor de secretos hasta el cambio 11**, así que vive en el entorno del proceso con lo que el operador ponga ahí | **Alta, verificada** | Declarado como límite conocido con dueño nombrado; el puerto no cambia cuando llegue el gestor, solo su adaptador |
| **El desafío de segundo factor se convierte de facto en una sesión** y la parte A termina entregando media capa de sesión | Media | El contrato de la sección 3 lo acota: sin reclamaciones, sin firma, sin permisos, opaco y almacenado hasheado. Si empieza a parecerse a un token, es señal de haber cruzado la línea |
| **La parte B rediseña el resultado de autenticación** y la costura se paga dos veces | Media | El delta de especificación fija los cuatro desenlaces y el alcance de autorización como contrato publicado, no como detalle de implementación |
| **La institución previa a la autenticación acaba viniendo del cliente** en la parte B, violando ADR-0009 en el único endpoint que no tiene token | **Alta si no se fija ahora** | **D5**: el contrato se escribe como requisito con escenario en este cambio, antes de que exista el endpoint |
| La prueba de uniformidad de tiempo resulta escamosa y se termina desactivando | **Alta si se convierte en puerta** | Se entrega como medición reportada, no como puerta; el mecanismo se prueba por interacción determinista |
| Contención en la fila de la cuenta bajo fuerza bruta concentrada, por llevar el estado de bloqueo a PostgreSQL | Media | Costo aceptado y declarado; el propio retroceso acota la tasa. La dimensión de volumen alto —por IP— se difiere a Redis con dueño nombrado |
| El modelo de bloqueo se implementa según `docs/03` §4.4 y la verificación lo mide contra la especificación publicada, o al revés | **Alta si D3 no se resuelve antes de especificar** | **D3** bloquea la especificación |
| El alcance no cabe en quince tareas ni en un pull request, y se fuerza igual | **Alta, ya visible en el pronóstico** | **D7**, con la alternativa de partir en dos cambios explícitamente sobre la mesa |
| Una dependencia criptográfica nueva entra sin la revisión que `docs/03` §2.6 exige | Media | **P1** la trata como decisión con sonda previa, no como detalle |
| El escenario diferido W2 se declara cerrado sin demostrarlo de verdad, porque los dos módulos existen pero nada lo verifica en verde | Media | El delta de `build-integrity` incorpora el escenario con su verificación, no una nota que diga que ya se puede |
| Una migración aplicada se edita después | Baja hoy | No hay entorno desplegado: la base vive solo en contenedores efímeros. La regla entra en vigor con el cambio 11 |

## Plan de reversión

Sigue siendo barata por la misma razón que en los cambios 4, 5 y 6: **no existe ningún entorno
desplegado ni ningún dato real**. La base de datos vive exclusivamente en contenedores de prueba
efímeros y ningún proceso de producción arranca todavía con una fuente de datos.

1. **Revertir el commit de fusión** del corte afectado, o cerrar su pull request. Con cortes
   encadenados, revertir uno obliga a revertir los posteriores que dependan de él.
2. **A1 se puede revertir sola; A2, A3 y A4 no se revierten sin A1.** Si desaparece la migración
   `V5`, el código generado de jOOQ deja de contener las tablas de identidad y los adaptadores no
   compilan. Es un rojo inmediato y ruidoso, no un fallo en ejecución.
3. **A2 no se revierte sin A3 ni A4** en la dirección contraria: si desaparece el sobre de llaves, el
   secreto TOTP se queda sin mecanismo de cifrado y la entrega correcta es revertir también el
   segundo factor, nunca almacenar el secreto en claro «mientras tanto».
4. **ADR-0022 se revierte por separado y sin consecuencia técnica.** Si el propietario prefiere no
   crear el ADR, la decisión se documenta en `design.md` y nada del código depende de ello,
   exactamente como ocurrió con la decisión D2 del cambio 5.
5. **Las notas editoriales de `docs/03` y `docs/09` se revierten solas.** Son documentación y no
   arrastran nada.
6. No hay dato que migrar hacia atrás ni migración de reverso que escribir: el siguiente
   `./mvnw verify` levanta un contenedor nuevo y aplica lo que quede en `db/migration`.
7. Si el sobre de llaves resulta inviable en el diseño, la reversión parcial **no** es almacenar el
   secreto TOTP sin cifrar: es detener la parte de MFA y entregar A1, A3 y la mitad de contraseña,
   elevando la decisión al propietario. Un secreto de segundo factor en claro en una columna es
   precisamente la amenaza que `docs/03` §7.3 nombra.
8. Una puerta que bloquee por error **no se desactiva con una bandera** (ADR-0008): se revierte el
   commit que la introdujo o se corrige con un ADR.

**Punto de no retorno práctico:** el cambio 11, cuando exista el primer entorno desplegado. Desde
entonces, una migración aplicada no se edita y una llave maestra en uso no se sustituye sin
procedimiento de rotación.

## Dependencias

- **Cambio 5A**, archivado: Flyway, generación de código de jOOQ desde las migraciones,
  Testcontainers, roles de PostgreSQL por script de inicialización solo de prueba,
  `organization_institution` y su adaptador jOOQ.
- **Cambio 5B**, archivado: `TransactionRunner`, `SecurityContext`, `IsolationLevel`,
  `CommittingPostgresIntegrationTest`, `shared_audit_log` con su disparador de encadenamiento,
  `AuditLogReader` y `JooqAuditLogReader` como precedente de puerto y adaptador, `RolePrivilegeMatrixIT`,
  `MultiTenantSchemaIT`, y la regla R3.
- **Cambio 6**, archivado: `IdempotentExecutor` como precedente de caso de uso que recibe el
  `SecurityContext` de su llamador, y `IdempotentExecutorConcurrencyIT` como precedente de prueba de
  concurrencia determinista.
- **Cambio 4**, archivado: `InstitutionId` del `kernel`, `CurrentInstitutionProvider` como puerto, y
  el precedente de códigos de error estables de ADR-0019 (`OrganizationErrorCodesTest`).
- JDK 25 y **Docker** en local y en integración continua, ya exigidos por la construcción desde A1.
- **Dependen de este cambio:** la **parte B** (`session-tokens-and-web-layer`), que no puede emitir
  una sesión sin identidad verificada; el **cambio 8** (matriz de permisos), que asigna roles a
  usuarios que hoy no existen; y, a través de ellos, todo F1 en adelante.

## Criterios de salida de F0 que este cambio acerca o cierra

Ninguno se declara cerrado sin que una prueba lo sostenga.

- **Criterio 1 — «Un intento de importar el `domain` de otro módulo rompe la construcción».** Hoy la
  mitad negativa ya está en verde con un fixture, pero la mitad positiva —«Cada módulo usa solo su
  propio dominio»— está **diferida por escrito** a este cambio, porque exige dos módulos de negocio
  reales. Este cambio **la cierra**, y con ella el criterio queda completo con módulos de producción
  y no solo con fixtures.
- **Criterio 2 — «Un usuario sin permiso recibe 403 y el intento queda en `AuditLog`».** Este cambio
  **no lo cierra**, y ni siquiera lo toca en su mitad de `403`, que exige capa web y RBAC. Sí entrega
  **la mitad de la bitácora**: el escritor de auditoría con el que ese intento denegado quedará
  registrado cuando la parte B y el cambio 8 lo produzcan. Dueño del criterio: **cambio 8**.
- **Criterio 4 — idempotencia.** Ya cerrado por el cambio 6. Este cambio **no lo toca**; la mitad de
  cabecera HTTP obligatoria del entregable 6 sigue diferida a la **parte B**, y esta propuesta no la
  declara cerrada.
- **Entregable 3 de F0 — «Autenticación del personal con MFA, roles, permisos, sesiones y bloqueo por
  fuerza bruta».** Este cambio entrega **autenticación, MFA y bloqueo**. Deja explícitamente
  pendientes **sesiones** (parte B) y **roles y permisos** (cambio 8). `docs/09-roadmap-y-fases.md` se
  actualiza para que esas dos mitades diferidas queden nombradas y no caduquen en silencio, con el
  precedente exacto de cómo el cambio 6 dejó escrita su mitad de cabecera HTTP.

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` termina en verde con JDK 25 y Docker, en local y en integración
      continua, incluida la puerta de mutación de `main`.
- [ ] Existe el módulo `identity` como segundo módulo de negocio, y `SpringModulithVerificationTest`
      y `NoCrossModuleDomainImportsTest` pasan con dos módulos de producción reales.
- [ ] El escenario «Cada módulo usa solo su propio dominio» está **demostrado en verde** y su nota de
      diferimiento retirada de `openspec/specs/build-integrity/spec.md`.
- [ ] Todas las tablas nuevas tienen `institution_id NOT NULL`, toda restricción única lo incluye, y
      la seguridad a nivel de fila está **habilitada y forzada**, verificado por las puertas genéricas
      de `MultiTenantSchemaIT` **sin lista de exclusión**.
- [ ] `RolePrivilegeMatrixIT` confirma los privilegios de `docs/03` §6.1 sobre cada tabla nueva para
      los cinco roles, sin uno más ni uno menos, e incluye explícitamente que `confia_portal_app`
      **no tiene ninguno** sobre las tablas de identidad.
- [ ] Una prueba de contexto ausente demuestra que, sin `app.institution_id`, una consulta sobre una
      tabla de identidad devuelve **cero filas**, no un error de permiso.
- [ ] El secreto TOTP almacenado **no contiene** el valor en claro y sí el prefijo de versión del
      formato de `docs/03` §7.3, verificado leyendo la columna con SQL crudo.
- [ ] Un intento de descifrar un secreto con el AAD de otra fila **falla la autenticación** del
      cifrado, demostrando que un valor cifrado no se puede mover de una fila a otra.
- [ ] La llave maestra **no aparece en ningún archivo del repositorio**, ni en código, ni en recursos,
      ni en pruebas: llega al proceso por su puerto.
- [ ] Un código TOTP válido se acepta dentro de la ventana ±1, y el **mismo código** se rechaza en un
      segundo uso por el contador monótono.
- [ ] Cinco intentos fallidos consecutivos producen el bloqueo, y el intento siguiente con la
      contraseña correcta se rechaza por bloqueo vigente, con la progresión que resuelva **D3**.
- [ ] Cada ciclo de bloqueo queda en `shared_audit_log` **con su duración**, como pide el escenario
      publicado.
- [ ] Una prueba de integración demuestra que el efecto y su asiento de auditoría se escriben **dentro
      de la misma transacción**: si el efecto falla, el asiento no sobrevive.
- [ ] El token de recuperación es de un solo uso bajo **concurrencia real** con sincronización
      determinista, y una solicitud posterior invalida la anterior.
- [ ] La autenticación de una cuenta inexistente ejecuta la verificación señuelo, verificado por
      interacción y no por cronómetro; y la diferencia de medianas de tiempo se **mide y se reporta**.
- [ ] Ningún secreto aparece en un mensaje de registro, en un mensaje de excepción, en un
      `toString()` ni en la salida de una prueba fallida, verificado y no confiado.
- [ ] Las exclusiones con destino nombrado —dimensión por IP, listas de contraseñas comprometidas,
      calibración de Argon2id, envío de correo, emisión de token y alcance restringido— viven **dentro
      de bloques `### Requisito:`**, nombran su cambio dueño, y cada una tiene al menos un escenario
      **cierto hoy y falso el día que la brecha se cierre**.
- [ ] La cobertura de `com.confia.identity.domain` alcanza el 95 % de líneas y ramas con JaCoCo, y el
      umbral de mutación de 80 con PIT.
- [ ] ADR-0022 existe con su decisión, sus alternativas descartadas y su motivo, si **D1** se aprueba.
- [ ] `docs/09-roadmap-y-fases.md` refleja el entregable 3 con sus dos mitades diferidas nombradas
      —sesiones a la parte B, roles y permisos al cambio 8—, y `docs/03-seguridad.md` lleva sus notas
      editoriales fechadas sobre §4.4, §6.1 y §7.3, con el cuerpo de cada sección **sin reescribir**.
- [ ] El tiempo de la suite `*IT.java` se **mide y se reporta** al cerrar cada corte, no se estima.

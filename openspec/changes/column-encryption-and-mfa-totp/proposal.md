# Propuesta: MFA con TOTP y recuperación de contraseña

- **Cambio:** `mfa-totp-and-password-recovery` — **segunda de tres partes** en que se dividió el
  cambio 7 de F0 (`docs/09-roadmap-y-fases.md` §3)
- **Exploración:** `openspec/changes/mfa-totp-and-password-recovery/exploration.md`, con una de sus
  nueve decisiones (§8, punto 2) ya resuelta por el propietario el 2026-09-27
- **Depende de:** `identity-module-and-password-authentication`, archivado el 2026-09-27
  (`openspec/changes/archive/2026-09-27-identity-module-and-password-authentication/`)
- **Cambio siguiente de la secuencia:** `session-tokens-and-web-layer` — JWT, JWKS, capa `web`,
  RBAC genérico y quien materializa `AuthenticationDecision.requiredDelay()`. Depende de este cambio
  y del anterior.
- **Rama:** `change/mfa-totp-and-password-recovery`
- **Estado:** pendiente de aprobación del propietario (`openspec/config.yaml`, `rules.proposal`)

## Intención

El módulo `identity` que la parte 1 entregó autentica con contraseña y audita, pero deja tres cosas
sin resolver que este cambio existe para cerrar, y una cuarta que no es de este cambio pero que este
cambio **tiene la obligación de no dejar peor**.

1. **El requisito publicado «MFA obligatoria para roles con escritura financiera o de configuración»
   sigue sin ningún mecanismo detrás.** `identity_staff_account` tiene hoy cinco columnas —
   `institution_id`, `id`, `email`, `password_hash`, `created_at` — y ninguna dice si una cuenta
   necesita segundo factor. Este cambio entrega TOTP (RFC 6238), la inscripción del segundo factor,
   los códigos de recuperación, y la columna mínima que decide cuándo exigirlo.
2. **El secreto TOTP debe cifrarse a nivel de columna** (`docs/03-seguridad.md` §7.3), y hoy no
   existe en el árbol ningún mecanismo de cifrado de columna ni de sobre de llaves. Este cambio es el
   primero que necesita uno, y por tanto el que debe construirlo.
3. **La recuperación de contraseña con token de un solo uso** está publicada
   (`openspec/specs/identity/spec.md`, líneas 191-234) y no tiene ningún caso de uso detrás.
4. **El tipo sellado `AuthenticationResult` promete, en su propio Javadoc, una protección del
   compilador que no existe todavía**, y este cambio es quien la construye o la deja sin construir
   para siempre. Su Javadoc dice que añadir un desenlace nuevo obliga «a cada `switch` existente» a
   manejarlo, pero **verificado en el árbol** (`AuthenticateWithPassword.java:155` y `:175`), los dos
   únicos consumidores en producción son `result instanceof Authenticated`. Un `instanceof` no
   exige exhaustividad: añadir `SecondFactorRequired` al `permits` sin tocar esas dos líneas
   **compilaría sin ninguna advertencia**, y las trataría como si el resultado fuera `Rejected` en
   los dos sitios donde importa — el que decide el estado del retroceso (línea 155) y el que decide
   qué se audita (línea 175). **Un segundo factor pendiente avanzaría el contador de retroceso como
   si fuera una contraseña incorrecta.** Convertir esos dos `instanceof` en un `switch` exhaustivo
   sin `default` es trabajo de este cambio, no una mejora opcional, y **modifica código ya archivado
   y verificado** con cobertura de dominio del 95 % y mutación del 80 %. Se declara así, sin
   disimularlo como una simple extensión.

**Por qué ahora, y no en `session-tokens-and-web-layer`.** El cambio 3 —el que emite tokens— llega
**antes** que el cambio 8 (matriz de roles y permisos) en la cadena de dependencias del roadmap. Si
este cambio no entrega ya una señal de qué cuentas necesitan MFA, existiría una ventana en la que el
sistema emitiría sesiones completas a usuarios con permisos financieros sin exigir nunca un segundo
factor, porque nada sabría que hace falta. Esa es la razón, ya resuelta por el propietario, que se
explica en detalle más abajo.

## Alcance

### Dentro de alcance

1. **Sobre de llaves para cifrado de columna** (`docs/03-seguridad.md` §7.3): motor de cifrado
   AES-256-GCM puro, llave maestra (KEK) por configuración del proceso, tabla de llaves de datos
   (DEK) con estado `active`/`retired`, y el ADR-0023 que registra la decisión. **Sin `pgcrypto`**:
   el cifrado ocurre en la aplicación.
2. **TOTP** (RFC 6238, SHA-1, 6 dígitos, periodo de 30 segundos, ventana ±1, ya decidido en la
   exploración compartida): inscripción del segundo factor, verificación con prevención de
   reutilización por contador, y el secreto cifrado con el sobre de llaves del punto 1.
3. **Diez códigos de recuperación de un solo uso**, hasheados con Argon2id, con su propio puerto de
   verificación (decisión D6 más abajo).
4. **Columna mínima `mfa_required`** en `identity_staff_account`, decidida por quien crea la cuenta,
   sin modelar rol ni permiso — decisión ya resuelta por el propietario, ver más abajo.
5. **Edición de `AuthenticationResult`**: se añaden los desenlaces `SecondFactorRequired` y
   `SecondFactorEnrollmentRequired` a la cláusula `permits`, y los dos `instanceof` de
   `AuthenticateWithPassword` se convierten en un `switch` exhaustivo sin `default`. Ver la sección
   «La costura con la parte 1».
6. **Recuperación de contraseña con token de un solo uso**, tal como ya la publica
   `openspec/specs/identity/spec.md` (líneas 191-212): sin delta de redacción, solo implementación —
   caso de uso, tabla propia, concurrencia sobre el mismo token, respuesta `202` uniforme a nivel de
   caso de uso (la traducción HTTP exacta la hace `session-tokens-and-web-layer`, igual que con el
   `401` de contraseña incorrecta en la parte 1).
7. **Delta de redacción sobre «MFA obligatoria para roles con escritura financiera o de
   configuración»** y sobre «Resultado tipado de la autenticación con dos desenlaces» (pasa a
   cuatro). Ver la sección dedicada.
8. **Notas editoriales fechadas** sobre `docs/03-seguridad.md` §4.1 (ya tiene una de la parte 1;
   esta añade la suya propia sobre §4.3 y §7.3) y actualización de `docs/09-roadmap-y-fases.md`.
9. Sujeto a **D5** más abajo: límite de tasa sobre la verificación de código TOTP (`docs/03` §4.3) y
   la señal de aviso al quedar con menos de tres códigos de recuperación.

### Fuera de alcance

Cada exclusión lleva destino nombrado, dentro de bloques `### Requisito:` en el delta cuando el
destino es un cambio futuro identificado, siguiendo la disciplina que la parte 1 aplicó.

**De `session-tokens-and-web-layer`** (sin cambios respecto a como la parte 1 ya lo dejó escrito):

- JWT, JWKS, la capa `web`, Spring Security, y **quien de verdad materializa**
  `AuthenticationDecision.requiredDelay()` fuera de la transacción.
- La dimensión por dirección IP del retroceso exponencial (`docs/03` §4.4), con aprovisionamiento de
  Redis en el cambio 11.
- La cabecera `Idempotency-Key` obligatoria sobre el primer endpoint.
- RBAC genérico y `CurrentInstitutionProvider` real.

**Del cambio 8** (matriz de roles y permisos): **la derivación real de `mfa_required` desde el
permiso del rol**, que este cambio no puede construir porque el dato de rol no existe todavía. Se
escribe como obligación con dueño en la sección de la decisión resuelta, no como pregunta abierta.

**Del cambio 9** (`db-scheduler`, trabajos en segundo plano): **la ejecución real de la rotación
anual de la llave de datos**, con recifrado progresivo por lotes. Este cambio entrega el esquema que
la hace posible — la columna de estado `active`/`retired`, usada desde el primer día para decidir con
qué llave se cifra cada valor nuevo — pero no el trabajo que recifra. Es la misma clase de brecha que
la parte 1 ya declaró para la purga de `identity_login_backoff`.

**Sin destino todavía identificado en el roadmap:** el envío real de la notificación de correo al
quedar con menos de tres códigos de recuperación (`docs/03` §4.3), condicionado a **D5**. Ningún
cambio de esta secuencia entrega un adaptador de envío de correo — la parte 1 ya lo señaló para la
recuperación de contraseña —, así que este cambio, si D5 aprueba la señal, entrega únicamente el
**dato calculado y auditado**, nunca el correo.

**Nada «preparado para» un cambio futuro.** La tabla de llaves de datos, la de credencial TOTP y la
de códigos de recuperación se usan las tres dentro de este mismo cambio; ninguna es infraestructura
sin consumidor.

## Las decisiones ya resueltas, con su evidencia

### La columna mínima `mfa_required` (§8, punto 2 de la exploración) — RESUELTA POR EL PROPIETARIO EL 2026-09-27

**El problema.** Ninguna de las 19 requisitos publicados de `identity` puede evaluarse sin un dato de
rol o de permiso, y ese dato **no existe en ningún lugar del árbol**: una búsqueda de `role` o
`permission` en `apps/api/app/src/main/java` no devuelve nada, y la matriz de dieciocho filas de
roles y permisos es del cambio 8, que **depende de este cambio**, no al revés.

**Decisión: columna `mfa_required BOOLEAN NOT NULL` en `identity_staff_account`, decidida por quien
crea la cuenta, sin modelar rol ni permiso.** El argumento decisivo fue de **secuencia, no de
modelado**: el cambio 3 emite tokens y llega antes que el cambio 8; con cualquier alternativa que no
fuera esta, existiría una ventana entre ambos en la que el sistema emitiría sesiones completas a
usuarios con permisos financieros sin ninguna exigencia de segundo factor, porque nada sabría que
hace falta. Esta es la única opción que permite al cambio 3 exigir MFA desde el primer día en que
emite tokens.

Se descartó la alternativa de que la exigencia dependiera solo de si la cuenta **ya tiene** un
secreto TOTP inscrito (sin distinguir por rol): invierte la propiedad de seguridad, porque el
requisito existe para que un usuario privilegiado **no pueda** evitar el segundo factor, y bajo esa
alternativa quien nunca se inscribe nunca se lo piden. También vuelve inexpresable el segundo
escenario publicado, el de la sesión restringida para una cuenta sin MFA configurada. Se descartó
también no tocar la obligatoriedad en este cambio: eso dejaría el escenario completo como brecha
hasta el cambio 8, y el cambio 3 seguiría sin ninguna señal en su ventana propia.

**El costo aceptado, dicho en voz alta.** La columna es un **duplicado desnormalizado** de algo que
el cambio 8 va a poseer: el permiso de escritura financiera o de configuración de un rol. Si un rol
gana uno de esos permisos y la columna no se actualiza para las cuentas que ya tienen ese rol, el
control queda **silenciosamente desactivado** para esas cuentas, sin que nada lo señale.

**La obligación que este cambio impone al cambio 8, escrita como obligación con dueño y no como
pregunta abierta** — por la misma razón que la condición de aceptación del crecimiento de
`identity_login_backoff` en `openspec/changes/foundations-plan/exploration.md`: una pregunta abierta
no obliga a nadie.

> **El cambio 8 (matriz de roles y permisos) DEBE derivar `identity_staff_account.mfa_required` del
> permiso de escritura financiera o de configuración del rol de la cuenta, y eliminar la doble
> fuente**, antes de considerar cerrado el requisito publicado «MFA obligatoria para roles con
> escritura financiera o de configuración». Mientras esa derivación no exista, un cambio de rol que
> otorgue un permiso financiero sin tocar esta columna deja el control desactivado en silencio para
> esa cuenta, y **eso es exactamente lo que el cambio 8 debe cerrar**, no una advertencia que pueda
> ignorarse.

**El delta de redacción que esta decisión exige**, sobre los dos escenarios publicados de «MFA
obligatoria...» (líneas 47-65 de `openspec/specs/identity/spec.md`):

- Los dos escenarios publicados hoy hablan de un usuario con «rol `cashier`, que incluye el permiso
  `payments:write`» y de un usuario con rol `administrator`. **Ese lenguaje de rol no puede
  cumplirse en este cambio**: no existe ningún dato de rol. El delta debe sustituirlo por lenguaje
  sobre `mfa_required = true`, que es lo único que este cambio puede observar y producir.
- Los dos escenarios terminan en «el sistema exige un código... antes de emitir el token de sesión
  completo» y «el sistema emite únicamente una sesión restringida». **Ese lenguaje de emisión de
  token pertenece a `session-tokens-and-web-layer`**, que no existe todavía. Este cambio no emite
  ningún token, así que el delta debe terminar los dos escenarios en el desenlace tipado que este
  cambio sí produce — `SecondFactorRequired` en el primer caso, `SecondFactorEnrollmentRequired` en
  el segundo —, dejando escrito en el propio requisito que la traducción de ese desenlace a un token
  de sesión, completo o restringido, es responsabilidad del cambio siguiente. El requisito publicado
  «Resultado tipado de la autenticación con dos desenlaces» (líneas 257-283) necesita el mismo
  tratamiento: su título y su cuerpo dicen literalmente «dos desenlaces», y este cambio entrega
  cuatro. Los dos delta se escriben juntos en la fase de especificación, porque describen la misma
  costura desde dos ángulos.

### El catálogo cerrado de ADR-0017 no admite la tabla de llaves — confirmado por lectura, no es una decisión

ADR-0017 fija un catálogo cerrado de **exactamente cuatro** tablas exceptuadas de `institution_id`,
seguridad de fila, prefijo de módulo o propiedad de tablas, y dice literalmente que «ninguna otra
tabla puede exceptuarse sin un ADR nuevo». El plan de F0 (`foundations-plan/exploration.md`) ya
evaluó y descartó explícitamente ampliar ese catálogo. **No hay decisión que tomar aquí**: la tabla
de llaves de datos sigue la regla general de ADR-0009, como cualquier tabla de negocio —
`institution_id NOT NULL`, restricciones únicas con ese discriminador, seguridad de fila habilitada y
forzada.

**El beneficio que eso trae, y que conviene declarar en voz alta y no solo como costo:** con
`institution_id` en la tabla de llaves, **las llaves de datos quedan aisladas por institución**.
Cifrar o descifrar el secreto de una institución nunca toca la llave de otra, y restaurar el respaldo
de una sola institución no depende de una tabla de llaves compartida entre todas.

## La costura con la parte 1: qué se modifica, y por qué es seguro modificarlo

Cuatro ediciones concretas sobre código ya fusionado y verificado (95 % de cobertura de dominio, 80
de mutación con PIT):

1. **Editar la cláusula `permits` de `AuthenticationResult`** para añadir `SecondFactorRequired` y
   `SecondFactorEnrollmentRequired`. El propio Javadoc del archivo ya anticipa este paso: dice
   literalmente que un cambio futuro «edits this file's `permits` clause», y que el compilador
   entonces «forces every existing `switch`» a manejarlo. Con la corrección del punto 2, esa frase
   deja de ser aspiracional.
2. **Convertir los dos `instanceof Authenticated` de `AuthenticateWithPassword.java` (líneas 155 y
   175) en un `switch` exhaustivo sin `default`.** Hoy no lo son, y el Javadoc de la clase asume que
   sí. Sin esta corrección, el paso 1 compila y se comporta mal en silencio: un segundo factor
   pendiente contaría como fallo de contraseña en el retroceso y en la auditoría.
3. **`AuthenticationDecision` y su envoltorio de retardo no cambian de forma.** Los cuatro
   desenlaces siguen viajando dentro del mismo `record AuthenticationDecision(AuthenticationResult
   result, Duration requiredDelay)`; ninguno lleva un campo de alcance de autorización, siguiendo la
   misma restricción que ya rige `Authenticated` y `Rejected`.
4. **Ningún otro archivo de la parte 1 se toca.** El compilador es la garantía: cualquier `switch`
   exhaustivo que no se actualice rompe la construcción, en cualquier archivo, del módulo que sea.

**Por qué es aceptable modificar código ya archivado, dicho con la razón de fondo y no solo por ser
barato.** El propio mecanismo que obliga a esta edición —la exhaustividad de un tipo sellado— es la
garantía de seguridad que la hace segura. No hay forma de que este cambio olvide una rama: la
construcción se rompe hasta que la actualice. Los dos desenlaces existentes, `Authenticated` y
`Rejected`, no cambian de forma ni de significado.

## Cómo se determina qué cuenta necesita segundo factor, dentro del caso de uso

`AuthenticateWithPassword.runWithinTransaction` produce hoy `Authenticated` en cuanto la contraseña
coincide. Este cambio inserta, después de esa verificación y antes de devolver el resultado, la
lectura de `mfa_required` y del estado de inscripción TOTP de la cuenta:

| `mfa_required` | Secreto TOTP inscrito | Desenlace |
|---|---|---|
| `false` | — | `Authenticated` (sin cambios respecto a la parte 1) |
| `true` | Sí | `SecondFactorRequired` — pendiente de un código válido |
| `true` | No | `SecondFactorEnrollmentRequired` — sesión restringida a la inscripción |

Ninguna de las tres ramas nuevas modifica el estado del retroceso como si fuera un fallo: solo
`Rejected` lo hace. Es exactamente la corrección que el `switch` exhaustivo hace cumplir.

## Capacidades

> Contrato con `/sdd-spec`. Investigado contra `openspec/specs/identity/spec.md` (19 requisitos, 37
> escenarios) antes de completar esta sección.

### Nuevas

**Ninguna.** Delta puro sobre `identity`, siguiendo el mismo razonamiento que la parte 1: TOTP,
cifrado y recuperación de contraseña son comportamiento del mismo módulo hexagonal, no una capacidad
separada.

### Modificadas

- **`identity`**, con:
  - `## MODIFIED Requirements` sobre «MFA obligatoria para roles con escritura financiera o de
    configuración» (sustituye el lenguaje de rol por `mfa_required`, y el lenguaje de emisión de
    token por los desenlaces tipados de este cambio).
  - `## MODIFIED Requirements` sobre «Resultado tipado de la autenticación con dos desenlaces» (pasa
    a cuatro, con `SecondFactorRequired` y `SecondFactorEnrollmentRequired`).
  - `## ADDED Requirements` para: inscripción y verificación TOTP con prevención de reutilización;
    códigos de recuperación de un solo uso; cifrado a nivel de columna del secreto TOTP con sobre de
    llaves y su estado `active`/`retired`; implementación completa de recuperación de contraseña
    (sin delta de redacción, el requisito ya publicado se satisface); y, sujeto a **D5**, límite de
    tasa de verificación TOTP y señal de aviso de códigos de recuperación bajos.
  - `## ADDED Requirements` de ausencia con destino nombrado: ejecución real de la rotación de la
    llave de datos (cambio 9); derivación de `mfa_required` desde el permiso del rol (cambio 8, ya
    escrita arriba como obligación).

No se toca `build-integrity`: ninguna regla de arquitectura nueva se introduce — el paquete
`com.confia.shared.crypto` sigue el patrón de `@NamedInterface` que ADR-0022 ya estableció y que
`SpringModulithVerificationTest` ya verifica sin cambios.

## Decisiones que requieren aprobación explícita del propietario

Numeradas para responderlas de una en una. Las tres primeras traen una recomendación con muy poco
margen de alternativa real; las últimas cinco son decisiones de diseño genuinas.

- **D1. ¿Cómo llega la llave maestra (KEK) al proceso?**

  **Recomendación: el precedente literal de `Argon2Pepper`, sin variación.** Objeto de valor con
  `toString()` redactado, construido desde configuración del proceso (variable de entorno, contenido
  en base64, longitud exacta verificada), que **falla al construirse** si falta o mide otra cosa. En
  pruebas, un literal declarado no secreto en su propio comentario, patrón ya revisado de
  `create-test-roles.sql:8-10`. El cableado real desde el gestor de secretos es del cambio 11, igual
  que ya lo es para la pimienta. No hace falta inventar ningún mecanismo nuevo: es la misma pregunta
  que `Argon2Pepper` ya respondió, aplicada a un secreto distinto.

- **D2. ¿Se declara explícitamente que la ejecución real de la rotación de la llave de datos es una
  brecha, en vez de construir algo parcial ahora?**

  **Recomendación: sí.** `docs/03` §7.3 pide rotación anual con recifrado progresivo **en trabajo por
  lotes**, y eso es exactamente lo que `db-scheduler` existe para hacer (ADR-0016) — que todavía no
  está en el árbol; su infraestructura es el cambio 9. Este cambio entrega el esquema que hace
  posible la rotación —la columna de estado `active`/`retired`, usada desde el primer día para
  decidir con qué llave se cifra cada valor nuevo— pero no la ejecución. Es la misma clase de brecha
  que la parte 1 ya declaró para la purga de `identity_login_backoff`, con el mismo dueño futuro:
  el cambio que introduzca mantenimiento programado.

- **D3. ¿Se divide este cambio en dos cambios SDD secuenciales — cifrado y TOTP por un lado,
  recuperación de contraseña por otro — o se mantiene como uno solo?**

  **Recomendación: dividir**, en `2a` (sobre de llaves con su ADR-0023, TOTP completo, edición del
  tipo sellado) y `2b` (recuperación de contraseña). El motivo del corte: la recuperación de
  contraseña **no depende del cifrado de columna ni de TOTP en absoluto** — es una tabla y un flujo
  propios —, mientras que el sobre de llaves debe entregarse **junto con** su único consumidor real
  de este cambio, el secreto TOTP, no aislado. Entregarlo solo sería infraestructura sin uso, el
  patrón que el propietario ya rechazó cuatro veces. Se descartó dividir en tres, aislando el sobre
  de llaves de TOTP, por esa misma razón.

  El pronóstico de tamaño (más abajo) muestra por qué esto no es prudencia excesiva: `2a` por sí sola
  ya se estima entre 13 y 16 tareas, con riesgo real de superar el límite de quince, y este cambio
  tiene más piezas nuevas que la parte 1, que ya necesitó cuatro cortes y nueve pull requests contra
  un pronóstico de 12 a 13 tareas. Si se aprueba, el paso mecánico siguiente es idéntico al que ya
  ocurrió una vez el 2026-09-24: bifurcar esta carpeta en dos cambios SDD secuenciales antes de la
  fase de especificación, con `2a` conservando este nombre de cambio y rama, y `2b` naciendo como
  cambio nuevo — el mismo mecanismo por el que `mfa-totp-and-password-recovery` nació de partir la
  mitad de identidad.

- **D4. ¿Dónde vive el motor de cifrado puro** — una función de bytes a bytes, sin acceso a base de
  datos —, y dónde vive la gestión de la tabla de llaves?

  **Recomendación: el motor puro en `com.confia.kernel`; la gestión de la tabla de llaves en un
  paquete nuevo `com.confia.shared.crypto`, expuesto con `@NamedInterface` siguiendo el patrón que
  ADR-0022 ya estableció para `shared.security` y `shared.audit`.**

  A diferencia de Argon2id, que necesitó Bouncy Castle porque el codificador de Spring no expone
  `withSecret(...)`, **AES-256-GCM está disponible de forma nativa en `javax.crypto` del JDK**: no
  hace falta ninguna dependencia externa para sellar y abrir un sobre. Eso significa que el motor
  puro —cifrar y descifrar bytes dados una llave, un vector de inicialización y datos autenticados
  adicionales, sin ninguna otra entrada— puede vivir en `kernel`, que exige pureza de JDK
  (`build-integrity`, «Pureza del módulo `kernel`»), sin violar esa regla por primera vez. Es además
  el módulo que ya consumen todos los módulos de negocio presentes y futuros, así que ningún consumo
  nuevo exige una anotación de Spring Modulith ni una revisión de superficie expuesta.

  La gestión de la tabla de llaves de datos sí necesita jOOQ, y **no puede** vivir en `kernel` bajo
  ninguna alternativa. `identity.infrastructure` es una opción posible, pero ata un mecanismo
  transversal a un solo módulo de negocio: `docs/03` §7.3 nombra ya, como consumidores futuros del
  mismo sobre de llaves, el documento del estudiante, el del encargado, su RTN, la cuenta de
  reembolso, el token de la pasarela y las notas del estudiante — ninguno de `identity`. Colocarlo en
  `shared.crypto`, con el mismo mecanismo de `@NamedInterface` que ADR-0022 ya decidió para
  `shared.security` y `shared.audit`, evita que cada módulo futuro redescubra la misma sonda que
  `identity.application` ya tuvo que resolver para consumir `TransactionRunner`.

- **D5. ¿Se escribe el ADR-0023 para el sobre de llaves, con qué alcance exacto?**

  **Recomendación: sí, y su alcance es exactamente:** (a) el algoritmo y formato de sobre —
  AES-256-GCM, vector de inicialización de 96 bits, etiqueta de 128 bits, datos autenticados
  adicionales `tabla|columna|id_de_fila`, formato `v1:<id_dek>:<iv>:<ciphertext>:<tag>` — tal como
  `docs/03` §7.3 ya los fija, sin margen de reinterpretación; (b) el esquema de la tabla de llaves de
  datos, con su estado `active`/`retired` y su aislamiento por institución; (c) la fuente de la llave
  maestra, por el precedente de **D1**; (d) la ubicación del motor puro y del puerto de gestión de
  llaves, por la decisión de **D4**. Queda **fuera** de su alcance, y se declara así dentro del
  propio ADR: la ejecución de la rotación (**D2**, brecha con dueño en el cambio 9) y la mitad de
  búsqueda determinista por HMAC (**D7**, siguiente punto).

  Merece ADR por la misma razón que la mereció ADR-0022: es una decisión que otros módulos futuros
  van a necesitar igual, y decidirla sin ADR obliga a cada módulo siguiente a redescubrirla. **Los
  ADR van del 0001 al 0022 sin huecos** (verificado por listado de `docs/adr/`), así que el siguiente
  número libre es **0023**. La propuesta archivada de la parte 1 había anticipado que este cifrado
  ocuparía el 0022, pero ese número lo consumió una decisión distinta durante la implementación de
  esa parte — la interfaz nombrada del módulo `shared`, que **D4** reutiliza sin reabrir.

- **D6. ¿Se reutiliza `PasswordHasher` para los códigos de recuperación, o se declara un puerto
  nuevo sobre las mismas primitivas?**

  **Recomendación: puerto nuevo.** `PasswordHasher` está tipado sobre `PlainPassword` y
  `StoredPasswordHash`, dos nombres atados semánticamente a «contraseña». Forzarlos a significar
  también «código de recuperación» sería el mismo defecto que el módulo ya evita en otros lugares:
  un nombre que deja de describir lo que contiene. La reutilización limpia es **el perfil Argon2id,
  el códec del formato PHC y el hasher de bajo nivel** — misma pimienta, mismo formato `$argon2id$`
  — detrás de un puerto propio (`RecoveryCodeHasher`, con sus propios objetos de valor
  `PlainRecoveryCode` y `StoredRecoveryCodeHash`, ambos con `toString()` redactado siguiendo el
  mismo patrón que `PlainPassword`). Esto evita reabrir `PasswordHasher`, que es contrato ya
  verificado con cobertura y mutación en verde.

- **D7. ¿Se entrega la mitad de búsqueda determinista por HMAC de `docs/03` §7.3, o se declara sin
  consumidor?**

  **Recomendación: se declara sin consumidor, no se construye.** El secreto TOTP nunca se busca por
  valor — se lee siempre por cuenta, con la misma clave primaria que ya resuelve la fila —, así que
  una columna de HMAC de búsqueda determinista no tendría ningún lector en este cambio. Construirla
  de todos modos sería exactamente el trabajo «preparado para» que el propietario ya rechazó cuatro
  veces, aplicado esta vez a una columna en vez de a una tabla. El ADR-0023 documenta el mecanismo
  general para cuando un módulo futuro lo necesite de verdad — por ejemplo, buscar un estudiante por
  su documento nacional cifrado —, sin que este cambio lo implemente sin uso.

- **D8. ¿Se añaden como requisitos los dos controles de `docs/03` sin requisito publicado — límite
  de tasa de verificación TOTP (§4.3, 5 intentos por 15 minutos con retroceso posterior) y aviso al
  quedar con menos de tres códigos de recuperación — o se declaran brecha con destino nombrado?**

  **Recomendación: se añaden los dos, con alcance recortado, reutilizando la arquitectura que la
  parte 1 ya probó.**

  Para el límite de tasa: la verificación de un código TOTP ocurre siempre contra una cuenta ya
  identificada por contraseña — a diferencia del inicio de sesión, aquí no hay caso de cuenta
  inexistente que proteger de enumeración —, así que el mismo patrón de `identity_login_backoff`
  —contador en PostgreSQL, calculado en el dominio con reloj inyectado, probado sin esperar— se
  aplica de forma directa, con la cuenta como clave en vez de la huella del identificador presentado.
  Es el control emparejado con el propio mecanismo que este cambio construye; dejarlo fuera sería
  entregar TOTP sin el control que `docs/03` §4.3 llama «regla dura» junto a él.

  Para el aviso de códigos bajos: se entrega **solo el dato calculado y auditado** — cuántos códigos
  de recuperación sin usar quedan tras consumir uno, con la señal de «por debajo de tres» — nunca el
  envío del correo, porque ningún cambio de esta secuencia tiene un adaptador de envío. El destino
  del envío real queda **sin identificar en el roadmap actual**, dicho así en vez de inventando un
  dueño.

  **El costo de esta recomendación, declarado sin rodeos:** añade alcance a un cambio cuyo
  pronóstico de `2a` ya roza el límite de quince tareas. Si al llegar a la fase de tareas el total
  no cabe, el corte adicional natural es exactamente el que la exploración ya nombra: separar el
  límite de tasa TOTP en su propio corte de pull request encadenado dentro de `2a`, nunca en un
  cambio SDD nuevo — mismo patrón que ya usó la parte 1 para sus tres cortes internos.

## Cobertura de los 19 requisitos publicados de `identity`

| Requisito | Este cambio | Lo que falta y su dueño |
|---|---|---|
| MFA obligatoria... | **Con delta de redacción (D2 de la exploración, ya resuelta), luego completo** en la mitad que no es HTTP | `session-tokens-and-web-layer` traduce el desenlace a token completo o restringido |
| Recuperación de contraseña... | **Completo**, sin delta | — |
| Prohibición de enumeración (mitad de recuperación) | **Completo** a nivel de caso de uso | `session-tokens-and-web-layer` entrega el `202` HTTP |
| Resultado tipado con dos desenlaces | **Con delta de redacción**, pasa a cuatro | — |
| Los demás 15 requisitos (contraseña, retroceso, rotación de refresco, separación de dominios, autorización por permisos, atomicidad, redacción de secretos) | **No toca** — ya cerrados por la parte 1, o de un cambio posterior | Ver la tabla equivalente de la propuesta archivada de la parte 1 |

## Impacto en migraciones, privilegios y auditoría

**Migraciones.** Una migración nueva (`V6`, siguiendo la numeración secuencial de Flyway), con al
menos: la columna `mfa_required` sobre `identity_staff_account`; la tabla de llaves de datos; la
tabla de credencial TOTP por cuenta; la tabla de códigos de recuperación; la tabla de token de
recuperación de contraseña; y, si **D8** se aprueba, la tabla de retroceso de verificación TOTP. Los
nombres exactos se fijan en diseño, con el precedente literal de `identity_staff_account` e
`identity_login_backoff`: prefijo de módulo, `institution_id NOT NULL`, seguridad de fila habilitada
y forzada, sin excepción alguna del catálogo cerrado de ADR-0017.

**Privilegios.** Mismo patrón que `V5`: `REVOKE ALL FROM PUBLIC` antes de todo `GRANT`;
`confia_admin_app` con `SELECT`, `INSERT`, `UPDATE` y sin `DELETE`; `confia_readonly` con `SELECT`;
`confia_portal_app` sin ningún privilegio, por ser datos de personal (`docs/03` §6.1). Extensión de
`RolePrivilegeMatrixIT` con las filas nuevas.

**Auditoría.** Eventos nuevos sobre la primera fila de `docs/03` §12.2 que la parte 1 no produjo:
inscripción de MFA, verificación de código TOTP (éxito y fallo), uso de código de recuperación,
solicitud y consumo de token de recuperación de contraseña. Cada uno dentro de la misma transacción
que su efecto, siguiendo el patrón ya establecido por `AuditLogWriter`.

## Restricciones que este cambio no puede violar

1. **Regla 11 de `CLAUDE.md`, con su historia detrás.** Las tres revisiones de la parte 1
   encontraron un hallazgo bloqueante cada una, y **las tres fueron fugas de secretos que pasaban la
   verificación sin alcanzar el caso peligroso**: la interpolación de un hash completo en un mensaje
   de excepción, y —el más instructivo— `AuthenticationCommand`, un `record` de Java, filtrando la
   contraseña por su `toString()` **generado automáticamente por el lenguaje**, no escrito por
   nadie. En este cambio los secretos nuevos son el secreto TOTP en claro, los códigos de
   recuperación en claro, la llave maestra y las llaves de datos. Ninguno de esos valores puede
   viajar en un `record` sin `toString()` redactado explícito: cada objeto de valor que los cargue
   es una clase final con un `toString()` sobreescrito, nunca un `record` sin más, exactamente como
   `PlainPassword` y `Argon2Pepper` ya lo hacen. La verificación lo comprueba por inspección del
   texto producido, nunca por confianza en el diseño.
2. **Regla 14: toda acción sensible se audita.** Inscripción y baja de MFA, uso de código de
   recuperación, verificación TOTP fallida, solicitud y consumo de recuperación de contraseña.
3. **Reglas de dependencia y de capas.** `domain` sin framework; jOOQ confinado a
   `infrastructure`; ningún módulo importa el `domain` de otro; `kernel` sin dependencias fuera del
   JDK (relevante para **D4**).
4. **Ninguna columna, tabla ni interfaz sin consumidor en este mismo cambio.**
5. **TDD estricto**, ejecutor `./mvnw verify` en `apps/api`, rojo observado y registrado antes de
   cada verde.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Un secreto nuevo —TOTP, código de recuperación, llave maestra, llave de datos— termina en un log, una excepción o un `toString()` generado por un `record` | **Alta si no se diseña**, con precedente exacto de los tres hallazgos de la parte 1 | Restricción 1, con envoltorios de valor explícitos y verificación por inspección |
| El pronóstico de `2a` (13-16 tareas) supera el límite de quince, agravado si **D8** añade el límite de tasa TOTP | **Alta, ya visible en el pronóstico** | **D3** recomienda dividir en `2a`/`2b`; si `2a` sigue sobrando, corte de pull request encadenado dentro de `2a`, no cambio SDD nuevo |
| La columna `mfa_required` queda desactualizada cuando un rol gana un permiso financiero, porque es un duplicado desnormalizado | Media, aceptada y declarada | Obligación con dueño impuesta al cambio 8, escrita arriba |
| El `switch` exhaustivo se implementa mal y dos ramas nuevas colapsan al mismo comportamiento | Baja, por construcción | El compilador rechaza cualquier `switch` que no cubra los cuatro casos; sin `default` que oculte un caso olvidado |
| Historial de subestimación: la parte 1 pronosticó 12-13 tareas y necesitó 12 tareas mas cuatro cortes y nueve pull requests, todos sobre el presupuesto de 800 líneas | Alta, declarada sin descontar | Pronóstico de esta propuesta ya asume 1,5x a 3x sobre el inventario bruto, ver más abajo |
| Una dependencia nueva (`spring-modulith-api` ya la trajo la parte 1; ninguna adicional se anticipa para AES-256-GCM, que es JDK puro) entra sin la revisión de `docs/03` §2.6 | Baja | Ninguna dependencia nueva prevista; si el diseño la introduce, sigue el mismo trato que Bouncy Castle recibió en la parte 1 |

## Plan de reversión

Igual de barata que en la parte 1: **no existe ningún entorno desplegado ni dato real**. La base
vive solo en contenedores efímeros de prueba.

1. Revertir el commit de fusión del corte afectado, o cerrar su pull request.
2. Si `2a` y `2b` son cambios SDD separados (**D3**), cada uno se revierte de forma independiente:
   `2b` no depende del esquema de `2a`.
3. Dentro de `2a`, la edición de `AuthenticationResult` no se revierte sola si algún corte posterior
   ya la consume; se revierte el corte completo que la introdujo.
4. Ninguna nota editorial de `docs/03` ni de `docs/09` arrastra código.
5. Una puerta que bloquee por error no se desactiva con una bandera (ADR-0008): se revierte el commit
   que la introdujo.

## Dependencias

- **`identity-module-and-password-authentication`**, archivado: el módulo, el esquema de dos
  tablas, `AuthenticationResult`, `AuthenticateWithPassword`, `PasswordHasher`, `Argon2Pepper`,
  `Argon2RawHasher`, `Argon2Profile`, `TransactionRunner`, `AuditLogWriter` y el patrón
  `@NamedInterface` de ADR-0022.
- **Dependen de este cambio:** `session-tokens-and-web-layer`, que traduce los cuatro desenlaces a
  tokens completos o restringidos; y, a través de la secuencia completa, el cambio 8, que además
  hereda la obligación de derivar `mfa_required` del permiso del rol.

## Pronóstico de cortes y tamaño

Sin reutilizar el pronóstico de la parte 1 por proporción: es un pronóstico nuevo sobre este alcance,
con el historial de subestimación de 1,5× a 3× de este repositorio declarado y **no** descontado.

| Sub-cambio | Bloque de trabajo | Tareas estimadas |
|---|---|---|
| **2a** | ADR-0023, motor de cifrado en `kernel`, puerto y adaptador de llaves en `shared.crypto`, migración de la tabla de llaves | 3 a 4 |
| **2a** | TOTP: inscripción, verificación con vector RFC 6238, prevención de reutilización, secreto cifrado | 3 a 4 |
| **2a** | Códigos de recuperación con su puerto propio | 2 a 3 |
| **2a** | Edición de `AuthenticationResult` y `AuthenticateWithPassword` (switch exhaustivo), columna `mfa_required`, delta de redacción de los dos requisitos publicados | 2 a 3 |
| **2a**, sujeto a **D8** | Límite de tasa de verificación TOTP y señal de aviso de códigos bajos | 2 a 3 |
| **2a total** | | **12 a 17** |
| **2b** | Recuperación de contraseña: token de un solo uso, tabla propia, concurrencia, respuesta uniforme | 6 a 8 |

**`2a` roza o supera el límite de quince tareas de `openspec/changes/README.md`, incluso antes de
contar el margen de subestimación histórico.** Es la evidencia central detrás de **D3**. Si el
propietario no aprueba **D8**, `2a` baja a 10-14 tareas y el margen mejora, pero el control de
verificación TOTP quedaría entregado sin su límite de tasa emparejado.

**Sobre el presupuesto de líneas por pull request.** El corte de la sesión declara una política de
revisión de 400 líneas cambiadas; `CLAUDE.md` y `docs/15-flujo-de-trabajo-git.md` §3 fijan
**ochocientas**, con nota explícita de que **antes eran cuatrocientas** y el propietario elevó el
número el 2026-09-18. Esta propuesta usa las ochocientas del repositorio, no las cuatrocientas de la
heurística de la sesión, siguiendo el mismo razonamiento que la propuesta archivada de la parte 1 ya
escribió para el mismo aparente conflicto. Con `2a` en 12-17 tareas y piezas nuevas de cifrado, TOTP
y códigos de recuperación, se anticipa **entrega en pull requests encadenados**, no en uno solo; la
estrategia de sesión `single-pr` no sirve para este cambio, igual que no sirvió para la parte 1.

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` termina en verde, incluida la puerta de mutación de `main`.
- [ ] `AuthenticationResult` tiene cuatro desenlaces, y `AuthenticateWithPassword` los consume con un
      `switch` exhaustivo sin `default`, verificado por una prueba que confirma que el compilador
      rechaza una rama olvidada.
- [ ] Una cuenta con `mfa_required = true` y secreto TOTP inscrito produce `SecondFactorRequired`;
      una con `mfa_required = true` sin inscribir produce `SecondFactorEnrollmentRequired`; ninguna
      de las dos avanza el contador de retroceso como si fuera un fallo.
- [ ] El secreto TOTP se almacena cifrado con el formato `v1:<id_dek>:<iv>:<ciphertext>:<tag>`, y una
      prueba de integración confirma que la columna no contiene el secreto en claro.
- [ ] Un valor cifrado con los datos autenticados adicionales de una fila falla al descifrarse con
      los de otra fila.
- [ ] Diez códigos de recuperación se generan, se muestran una vez, y usar uno lo invalida sin
      afectar a los nueve restantes.
- [ ] La recuperación de contraseña cumple sus dos escenarios publicados sin ningún delta de
      redacción: ventana de treinta minutos, invalidación por token más reciente.
- [ ] Ningún secreto de este cambio —TOTP, código de recuperación, llave maestra, llave de datos—
      aparece en un registro, una excepción, un `toString()` ni la salida de una prueba fallida,
      verificado por inspección del texto producido.
- [ ] `docs/09-roadmap-y-fases.md` y `docs/03-seguridad.md` (§4.1, §4.3, §7.3) llevan sus notas
      editoriales fechadas, con el cuerpo de cada sección sin reescribir.
- [ ] La cobertura de los paquetes `domain` de este cambio alcanza el 95 % con JaCoCo y el 80 de
      mutación con PIT.

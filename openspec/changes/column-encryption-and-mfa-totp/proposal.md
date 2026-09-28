# Propuesta: Cifrado de columna y MFA con TOTP

- **Cambio:** `column-encryption-and-mfa-totp` — **segundo de cuatro** cambios secuenciales en que
  se ejecuta el cambio 7 de F0 (`docs/09-roadmap-y-fases.md` §3)
- **Historia de la carpeta.** Nació como la segunda de **tres** partes (`mfa-totp-and-password-
  recovery`) el 2026-09-24. El propietario la partió a su vez el **2026-09-27**, porque su propia
  propuesta anterior pronosticó **12 a 17 tareas solo para la mitad que no es recuperación de
  contraseña**, contra el límite de quince de `openspec/changes/README.md`. Esta carpeta se renombró
  a `column-encryption-and-mfa-totp` y conserva esa mitad; la otra mitad nace como cambio nuevo,
  `password-recovery-token`. Esta reescritura recorta el alcance de la propuesta en consecuencia.
  Ver `exploration.md` (cabecera) y `openspec/changes/foundations-plan/exploration.md` (nota del
  2026-09-27, «tercer corte») para la justificación completa del corte.
- **Exploración:** `openspec/changes/column-encryption-and-mfa-totp/exploration.md` — compartida con
  `password-recovery-token`, hecha antes del tercer corte. Una de sus nueve decisiones (§8, punto 2)
  ya está resuelta por el propietario desde el 2026-09-27.
- **Depende de:** `identity-module-and-password-authentication`, archivado el 2026-09-27
  (`openspec/changes/archive/2026-09-27-identity-module-and-password-authentication/`).
- **Siguiente cambio de esta secuencia:** `password-recovery-token` (tercero de cuatro). **No
  depende técnicamente de nada que este cambio entregue** — no cifra ninguna columna, no usa TOTP, y
  su token se almacena hasheado, no cifrado —; la secuencia entre ambos es solo de origen (nacieron
  de partir el mismo cambio), no de dependencia funcional.
- **Cuarto y último cambio de esta secuencia:** `session-tokens-and-web-layer` — JWT, JWKS, capa
  `web`, RBAC genérico, y quien materializa `AuthenticationDecision.requiredDelay()`. Depende de este
  cambio (los cuatro desenlaces tipados) y de `password-recovery-token` (la traducción HTTP de su
  respuesta uniforme).
- **Rama:** `change/mfa-totp-and-password-recovery` (nombre heredado de antes del tercer corte; no
  se renombra en este documento porque renombrar una rama en curso no es una decisión de esta fase).
- **Estado:** pendiente de aprobación del propietario (`openspec/config.yaml`, `rules.proposal`)

## Intención

El módulo `identity` que la parte 1 entregó autentica con contraseña y audita, pero deja pendientes
dos cosas que este cambio existe para cerrar, y una tercera que no es de este cambio pero que este
cambio **tiene la obligación de no dejar peor**.

1. **El requisito publicado «MFA obligatoria para roles con escritura financiera o de configuración»
   sigue sin ningún mecanismo detrás.** `identity_staff_account` tiene hoy cinco columnas —
   `institution_id`, `id`, `email`, `password_hash`, `created_at` — y ninguna dice si una cuenta
   necesita segundo factor. Este cambio entrega TOTP (RFC 6238), la inscripción del segundo factor,
   los códigos de recuperación de MFA, y la columna mínima que decide cuándo exigirlo.
2. **El secreto TOTP debe cifrarse a nivel de columna** (`docs/03-seguridad.md` §7.3), y hoy no
   existe en el árbol ningún mecanismo de cifrado de columna ni de sobre de llaves. Este cambio es el
   primero que necesita uno, y por tanto el que debe construirlo.
3. **El tipo sellado `AuthenticationResult` promete, en su propio Javadoc, una protección del
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

**La recuperación de contraseña queda fuera de esta propuesta.** Está publicada
(`openspec/specs/identity/spec.md`, líneas 191-234) y sigue sin ningún caso de uso detrás, pero es
responsabilidad de `password-recovery-token`, el tercer cambio de esta secuencia, precisamente
porque no depende de nada de lo que este cambio entrega. Ver «Fuera de alcance».

**Por qué la columna `mfa_required` entra ahora, y no en `session-tokens-and-web-layer`.** El cuarto
cambio de esta secuencia —el que emite tokens— llega **antes** que el cambio 8
(`rbac-permission-matrix-and-audit-integration`, matriz de roles y permisos) en la cadena de
dependencias del roadmap. Si este cambio no entrega ya una señal de qué cuentas necesitan MFA,
existiría una ventana en la que el sistema emitiría sesiones completas a usuarios con permisos
financieros sin exigir nunca un segundo factor, porque nada sabría que hace falta. Esa es la razón,
ya resuelta por el propietario, que se explica en detalle más abajo.

## Alcance

### Dentro de alcance

1. **Sobre de llaves para cifrado de columna** (`docs/03-seguridad.md` §7.3): motor de cifrado
   AES-256-GCM puro, llave maestra (KEK) por configuración del proceso, tabla de llaves de datos
   (DEK) con estado `active`/`retired`, y el ADR-0023 que registra la decisión. **Sin `pgcrypto`**:
   el cifrado ocurre en la aplicación.
2. **TOTP** (RFC 6238, SHA-1, 6 dígitos, periodo de 30 segundos, ventana ±1, ya decidido en la
   exploración compartida): inscripción del segundo factor, verificación con prevención de
   reutilización por contador, y el secreto cifrado con el sobre de llaves del punto 1.
3. **Diez códigos de recuperación de MFA, de un solo uso**, hasheados con Argon2id, con su propio
   puerto de verificación (decisión D5 más abajo). **Distintos del token de recuperación de
   contraseña** de `password-recovery-token`: estos códigos sustituyen un código TOTP cuando el
   usuario perdió su dispositivo, no restablecen una contraseña.
4. **Columna mínima `mfa_required`** en `identity_staff_account`, decidida por quien crea la cuenta,
   sin modelar rol ni permiso — decisión ya resuelta por el propietario, ver más abajo.
5. **Edición de `AuthenticationResult`**: se añaden los desenlaces `SecondFactorRequired` y
   `SecondFactorEnrollmentRequired` a la cláusula `permits`, y los dos `instanceof` de
   `AuthenticateWithPassword` se convierten en un `switch` exhaustivo sin `default`. Ver la sección
   «La costura con la parte 1».
6. **Delta de redacción sobre «MFA obligatoria para roles con escritura financiera o de
   configuración»** y sobre «Resultado tipado de la autenticación con dos desenlaces» (pasa a
   cuatro). Ver la sección dedicada.
7. **Notas editoriales fechadas** sobre `docs/03-seguridad.md` §4.1 (ya tiene una de la parte 1;
   esta añade la suya propia sobre §4.3 y §7.3) y actualización de `docs/09-roadmap-y-fases.md`.
8. Sujeto a **D7** y **D8** más abajo: límite de tasa sobre la verificación de código TOTP (`docs/03`
   §4.3) y la señal de aviso al quedar con menos de tres códigos de recuperación.

### Fuera de alcance

Cada exclusión lleva destino nombrado, dentro de bloques `### Requisito:` en el delta cuando el
destino es un cambio futuro identificado, siguiendo la disciplina que la parte 1 aplicó.

**De `password-recovery-token`** (tercer cambio de esta secuencia, íntegro): la recuperación de
contraseña con su token de un solo uso y de corta vida, su tabla propia, su concurrencia sobre el
mismo token, y la respuesta uniforme (`202` a nivel de caso de uso) que exige la prohibición de
enumeración. **El requisito publicado «Recuperación de contraseña con token de un solo uso y de
corta vida» (líneas 191-212 de `openspec/specs/identity/spec.md`) y los dos escenarios de
recuperación del requisito «Prohibición de enumeración de usuarios» (líneas 220-234) son de ese
cambio, no de este.** Este cambio no crea ningún delta sobre ninguno de los dos: los deja
exactamente como la parte 1 los publicó, sin tocar una línea, para que `password-recovery-token` los
resuelva sin arrastrar nada de aquí. **El corte, dicho sin rodeos: la recuperación de contraseña no
cifra ninguna columna, no usa TOTP, y su token se almacena hasheado y no cifrado — no depende de
nada de lo que entrega este cambio.**

**De `session-tokens-and-web-layer`** (cuarto y último de esta secuencia; sin cambios respecto a
como la parte 1 ya lo dejó escrito):

- JWT, JWKS, la capa `web`, Spring Security, y **quien de verdad materializa**
  `AuthenticationDecision.requiredDelay()` fuera de la transacción.
- La dimensión por dirección IP del retroceso exponencial (`docs/03` §4.4), con aprovisionamiento de
  Redis en el cambio 11 (`containerization-and-cicd-pipeline`).
- La cabecera `Idempotency-Key` obligatoria sobre el primer endpoint.
- RBAC genérico y `CurrentInstitutionProvider` real.

**Del cambio 8** (`rbac-permission-matrix-and-audit-integration`, matriz de roles y permisos): **la
derivación real de `mfa_required` desde el permiso del rol**, que este cambio no puede construir
porque el dato de rol no existe todavía. Se escribe como obligación con dueño en la sección de la
decisión resuelta, no como pregunta abierta.

**Del cambio 9** (`background-jobs-with-db-scheduler`, trabajos en segundo plano): **la ejecución
real de la rotación anual de la llave de datos**, con recifrado progresivo por lotes. Este cambio
entrega el esquema que la hace posible — la columna de estado `active`/`retired`, usada desde el
primer día para decidir con qué llave se cifra cada valor nuevo — pero no el trabajo que recifra. Es
la misma clase de brecha que la parte 1 ya declaró para la purga de `identity_login_backoff`.

**Sin destino todavía identificado en el roadmap:** el envío real de la notificación de correo al
quedar con menos de tres códigos de recuperación (`docs/03` §4.3), condicionado a **D8**. Ningún
cambio de esta secuencia entrega un adaptador de envío de correo — la parte 1 ya lo señaló para la
recuperación de contraseña —, así que este cambio, si D8 aprueba la señal, entrega únicamente el
**dato calculado y auditado**, nunca el correo.

**Nada «preparado para» un cambio futuro.** La tabla de llaves de datos, la de credencial TOTP y la
de códigos de recuperación de MFA se usan las tres dentro de este mismo cambio; ninguna es
infraestructura sin consumidor. En particular, **este cambio no crea ninguna tabla, columna ni
puerto para la recuperación de contraseña** — ni siquiera un esquema vacío o un nombre reservado:
esa tabla nace íntegra en `password-recovery-token`.

## Las decisiones ya resueltas, con su evidencia

### La columna mínima `mfa_required` (§8, punto 2 de la exploración) — RESUELTA POR EL PROPIETARIO EL 2026-09-27

**El problema.** Ninguna de las 19 requisitos publicados de `identity` puede evaluarse sin un dato de
rol o de permiso, y ese dato **no existe en ningún lugar del árbol**: una búsqueda de `role` o
`permission` en `apps/api/app/src/main/java` no devuelve nada, y la matriz de dieciocho filas de
roles y permisos es del cambio 8, que **depende de este cambio**, no al revés.

**Decisión: columna `mfa_required BOOLEAN NOT NULL` en `identity_staff_account`, decidida por quien
crea la cuenta, sin modelar rol ni permiso.** El argumento decisivo fue de **secuencia, no de
modelado**: el cuarto cambio de esta secuencia emite tokens y llega **antes** que el cambio 8; con
cualquier alternativa que no fuera esta, existiría una ventana entre ambos en la que el sistema
emitiría sesiones completas a usuarios con permisos financieros sin ninguna exigencia de segundo
factor, porque nada sabría que hace falta. Esta es la única opción que permite al cuarto cambio de
esta secuencia exigir MFA desde el primer día en que emite tokens.

Se descartó la alternativa de que la exigencia dependiera solo de si la cuenta **ya tiene** un
secreto TOTP inscrito (sin distinguir por rol): invierte la propiedad de seguridad, porque el
requisito existe para que un usuario privilegiado **no pueda** evitar el segundo factor, y bajo esa
alternativa quien nunca se inscribe nunca se lo piden. También vuelve inexpresable el segundo
escenario publicado, el de la sesión restringida para una cuenta sin MFA configurada. Se descartó
también no tocar la obligatoriedad en este cambio: eso dejaría el escenario completo como brecha
hasta el cambio 8, y el cuarto cambio de esta secuencia seguiría sin ninguna señal en su ventana
propia.

**El costo aceptado, dicho en voz alta.** La columna es un **duplicado desnormalizado** de algo que
el cambio 8 va a poseer: el permiso de escritura financiera o de configuración de un rol. Si un rol
gana uno de esos permisos y la columna no se actualiza para las cuentas que ya tienen ese rol, el
control queda **silenciosamente desactivado** para esas cuentas, sin que nada lo señale.

**La obligación que este cambio impone al cambio 8, escrita como obligación con dueño y no como
pregunta abierta** — por la misma razón que la condición de aceptación del crecimiento de
`identity_login_backoff` en `openspec/changes/foundations-plan/exploration.md`: una pregunta abierta
no obliga a nadie.

> **El cambio 8 (`rbac-permission-matrix-and-audit-integration`) DEBE derivar
> `identity_staff_account.mfa_required` del permiso de escritura financiera o de configuración del
> rol de la cuenta, y eliminar la doble fuente**, antes de considerar cerrado el requisito publicado
> «MFA obligatoria para roles con escritura financiera o de configuración». Mientras esa derivación
> no exista, un cambio de rol que otorgue un permiso financiero sin tocar esta columna deja el
> control desactivado en silencio para esa cuenta, y **eso es exactamente lo que el cambio 8 debe
> cerrar**, no una advertencia que pueda ignorarse.

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
  de sesión, completo o restringido, es responsabilidad del cuarto cambio de esta secuencia. El
  requisito publicado «Resultado tipado de la autenticación con dos desenlaces» (líneas 257-283)
  necesita el mismo tratamiento: su título y su cuerpo dicen literalmente «dos desenlaces», y este
  cambio entrega cuatro. Los dos delta se escriben juntos en la fase de especificación, porque
  describen la misma costura desde dos ángulos.

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
cifrado y códigos de recuperación de MFA son comportamiento del mismo módulo hexagonal, no una
capacidad separada.

### Modificadas

- **`identity`**, con:
  - `## MODIFIED Requirements` sobre «MFA obligatoria para roles con escritura financiera o de
    configuración» (sustituye el lenguaje de rol por `mfa_required`, y el lenguaje de emisión de
    token por los desenlaces tipados de este cambio).
  - `## MODIFIED Requirements` sobre «Resultado tipado de la autenticación con dos desenlaces» (pasa
    a cuatro, con `SecondFactorRequired` y `SecondFactorEnrollmentRequired`).
  - `## ADDED Requirements` para: inscripción y verificación TOTP con prevención de reutilización;
    códigos de recuperación de MFA de un solo uso; cifrado a nivel de columna del secreto TOTP con
    sobre de llaves y su estado `active`/`retired`; y, sujeto a **D7** y **D8**, límite de tasa de
    verificación TOTP y señal de aviso de códigos de recuperación de MFA bajos.
  - `## ADDED Requirements` de ausencia con destino nombrado: ejecución real de la rotación de la
    llave de datos (cambio 9); derivación de `mfa_required` desde el permiso del rol (cambio 8, ya
    escrita arriba como obligación).
  - **Ningún delta** sobre «Recuperación de contraseña con token de un solo uso y de corta vida» ni
    sobre los dos escenarios de recuperación de «Prohibición de enumeración de usuarios»: quedan
    exactamente como la parte 1 los publicó, para que `password-recovery-token` los resuelva sin
    heredar nada de este cambio.

No se toca `build-integrity`: ninguna regla de arquitectura nueva se introduce — el paquete
`com.confia.shared.crypto` sigue el patrón de `@NamedInterface` que ADR-0022 ya estableció y que
`SpringModulithVerificationTest` ya verifica sin cambios.

## Decisiones que requieren aprobación explícita del propietario

> **Estado al 2026-09-28.**
>
> **D7 y D8: APROBADAS POR EL PROPIETARIO.** Los dos controles de `docs/03` §4.3 —el límite de tasa de
> verificación de código TOTP y el aviso al quedar con menos de tres códigos de recuperación— se
> **añaden como requisitos** de la capacidad `identity`, no se declaran brecha.
>
> **La consecuencia de tamaño, dicha sin suavizar.** Con las dos aprobadas, el pronóstico queda en
> **12 a 17 tareas** contra el límite de **quince** de `openspec/changes/README.md`, y el historial de
> subestimación de este repositorio es de 1,5× a 3× sin descontar, así que la expectativa realista se
> acerca al extremo alto. Sin ellas habría quedado en 10 a 14, con margen cómodo.
>
> Es decir: **este cambio se acepta a sabiendas de que puede superar el límite de tareas del propio
> repositorio.** La regla de tamaño diría partirlo, y **no hay dónde**: esa herramienta ya se usó dos
> veces sobre el cambio 7 —el segundo corte del 2026-09-24 y el tercero del 2026-09-27— y el corte que
> quedaba, separar la recuperación de contraseña, es exactamente el que produjo esta carpeta. La
> mitigación es la que ya usó la parte 1: **cortes de pull request encadenados dentro de este mismo
> cambio SDD**, con el presupuesto de 800 líneas por corte.
>
> Si la fase de tareas confirma un total por encima de quince, eso **no** es una sorpresa que obligue a
> replanificar: está previsto aquí, con su mitigación nombrada y su motivo escrito.
>
> **D1 a D6: APROBADAS el 2026-09-28**, al aprobar el propietario la propuesta completa
> (`openspec/config.yaml`, `rules.proposal`: «Product owner approves the proposal before
> specs/design/tasks proceed»). Antes de esa aprobación estuvieron registradas aquí como «recomendadas
> y no objetadas», porque se habían presentado como recomendaciones junto a la única pregunta que
> entonces estaba abierta, y no hubo aprobación separada de cada una. Quedan aprobadas con el
> documento que las contiene.
>
> **La propuesta completa queda aprobada, y con ella las ocho decisiones.** Pasa a la fase de
> especificación.



Renumeradas de forma continua desde D1, para responderlas de una en una. Cada una indica a qué
número correspondía en la propuesta anterior (antes del tercer corte), para que el informe ya escrito
siga siendo rastreable.

**Decisión retirada de esta lista, no relocalizada: la antigua D3** («¿se divide este cambio en dos
—cifrado y TOTP por un lado, recuperación de contraseña por otro— o se mantiene como uno solo?»).
**Queda resuelta, no pendiente**: el propietario aprobó dividir el 2026-09-27, y esa división es
exactamente el tercer corte que dio origen a esta reescritura — la «parte cifrado y TOTP» de aquella
D3 es esta carpeta completa, y la «parte recuperación de contraseña» es `password-recovery-token`. No
hay ninguna otra decisión numerada de la propuesta anterior que tratara específicamente sobre la
recuperación de contraseña: las ocho decisiones originales son todas de cifrado, TOTP o códigos de
recuperación de MFA, y las siete que siguen vigentes permanecen íntegras en este cambio.

- **D1 — antes D1. ¿Cómo llega la llave maestra (KEK) al proceso?**

  **Recomendación: el precedente literal de `Argon2Pepper`, sin variación.** Objeto de valor con
  `toString()` redactado, construido desde configuración del proceso (variable de entorno, contenido
  en base64, longitud exacta verificada), que **falla al construirse** si falta o mide otra cosa. En
  pruebas, un literal declarado no secreto en su propio comentario, patrón ya revisado de
  `create-test-roles.sql:8-10`. El cableado real desde el gestor de secretos es del cambio 11, igual
  que ya lo es para la pimienta. No hace falta inventar ningún mecanismo nuevo: es la misma pregunta
  que `Argon2Pepper` ya respondió, aplicada a un secreto distinto.

- **D2 — antes D2. ¿Se declara explícitamente que la ejecución real de la rotación de la llave de
  datos es una brecha, en vez de construir algo parcial ahora?**

  **Recomendación: sí.** `docs/03` §7.3 pide rotación anual con recifrado progresivo **en trabajo por
  lotes**, y eso es exactamente lo que `db-scheduler` existe para hacer (ADR-0016) — que todavía no
  está en el árbol; su infraestructura es el cambio 9. Este cambio entrega el esquema que hace
  posible la rotación —la columna de estado `active`/`retired`, usada desde el primer día para
  decidir con qué llave se cifra cada valor nuevo— pero no la ejecución. Es la misma clase de brecha
  que la parte 1 ya declaró para la purga de `identity_login_backoff`, con el mismo dueño futuro:
  el cambio que introduzca mantenimiento programado.

- **D3 — antes D4. ¿Dónde vive el motor de cifrado puro** — una función de bytes a bytes, sin acceso
  a base de datos —, y dónde vive la gestión de la tabla de llaves?

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

- **D4 — antes D5. ¿Se escribe el ADR-0023 para el sobre de llaves, con qué alcance exacto?**

  **Recomendación: sí, y su alcance es exactamente:** (a) el algoritmo y formato de sobre —
  AES-256-GCM, vector de inicialización de 96 bits, etiqueta de 128 bits, datos autenticados
  adicionales `tabla|columna|id_de_fila`, formato `v1:<id_dek>:<iv>:<ciphertext>:<tag>` — tal como
  `docs/03` §7.3 ya los fija, sin margen de reinterpretación; (b) el esquema de la tabla de llaves de
  datos, con su estado `active`/`retired` y su aislamiento por institución; (c) la fuente de la llave
  maestra, por el precedente de **D1**; (d) la ubicación del motor puro y del puerto de gestión de
  llaves, por la decisión de **D3**. Queda **fuera** de su alcance, y se declara así dentro del
  propio ADR: la ejecución de la rotación (**D2**, brecha con dueño en el cambio 9) y la mitad de
  búsqueda determinista por HMAC (**D6**, más abajo).

  Merece ADR por la misma razón que la mereció ADR-0022: es una decisión que otros módulos futuros
  van a necesitar igual, y decidirla sin ADR obliga a cada módulo siguiente a redescubrirla. **Los
  ADR van del 0001 al 0022 sin huecos** (verificado por listado de `docs/adr/`), así que el siguiente
  número libre es **0023**. La propuesta archivada de la parte 1 había anticipado que este cifrado
  ocuparía el 0022, pero ese número lo consumió una decisión distinta durante la implementación de
  esa parte — la interfaz nombrada del módulo `shared`, que **D3** reutiliza sin reabrir.

- **D5 — antes D6. ¿Se reutiliza `PasswordHasher` para los códigos de recuperación de MFA, o se
  declara un puerto nuevo sobre las mismas primitivas?**

  **Recomendación: puerto nuevo.** `PasswordHasher` está tipado sobre `PlainPassword` y
  `StoredPasswordHash`, dos nombres atados semánticamente a «contraseña». Forzarlos a significar
  también «código de recuperación» sería el mismo defecto que el módulo ya evita en otros lugares:
  un nombre que deja de describir lo que contiene. La reutilización limpia es **el perfil Argon2id,
  el códec del formato PHC y el hasher de bajo nivel** — misma pimienta, mismo formato `$argon2id$`
  — detrás de un puerto propio (`RecoveryCodeHasher`, con sus propios objetos de valor
  `PlainRecoveryCode` y `StoredRecoveryCodeHash`, ambos con `toString()` redactado siguiendo el
  mismo patrón que `PlainPassword`). Esto evita reabrir `PasswordHasher`, que es contrato ya
  verificado con cobertura y mutación en verde. **Este puerto es exclusivo de los códigos de
  recuperación de MFA; el token de recuperación de contraseña de `password-recovery-token` es un
  mecanismo distinto, con su propia decisión de hasheo en su propia propuesta.**

- **D6 — antes D7. ¿Se entrega la mitad de búsqueda determinista por HMAC de `docs/03` §7.3, o se
  declara sin consumidor?**

  **Recomendación: se declara sin consumidor, no se construye.** El secreto TOTP nunca se busca por
  valor — se lee siempre por cuenta, con la misma clave primaria que ya resuelve la fila —, así que
  una columna de HMAC de búsqueda determinista no tendría ningún lector en este cambio. Construirla
  de todos modos sería exactamente el trabajo «preparado para» que el propietario ya rechazó cuatro
  veces, aplicado esta vez a una columna en vez de a una tabla. El ADR-0023 documenta el mecanismo
  general para cuando un módulo futuro lo necesite de verdad — por ejemplo, buscar un estudiante por
  su documento nacional cifrado —, sin que este cambio lo implemente sin uso.

- **D7 — antes la primera mitad de D8. ¿Se añade como requisito el límite de tasa de verificación
  TOTP (`docs/03` §4.3, 5 intentos por 15 minutos con retroceso posterior), o se declara brecha con
  destino nombrado?**

  **Recomendación: se añade, reutilizando la arquitectura que la parte 1 ya probó.** La verificación
  de un código TOTP ocurre siempre contra una cuenta ya identificada por contraseña — a diferencia
  del inicio de sesión, aquí no hay caso de cuenta inexistente que proteger de enumeración —, así que
  el mismo patrón de `identity_login_backoff` —contador en PostgreSQL, calculado en el dominio con
  reloj inyectado, probado sin esperar— se aplica de forma directa, con la cuenta como clave en vez
  de la huella del identificador presentado. Es el control emparejado con el propio mecanismo que
  este cambio construye; dejarlo fuera sería entregar TOTP sin el control que `docs/03` §4.3 llama
  «regla dura» junto a él.

  **Reevaluación de costo contra el pronóstico nuevo, dicha sin rodeos.** Con la recuperación de
  contraseña ya fuera de esta carpeta, el pronóstico de este cambio (más abajo) sigue siendo
  exactamente el que antes era solo la mitad «2a»: **12 a 17 tareas**, con riesgo real de superar el
  límite de quince — la salida de la recuperación de contraseña no lo alivia, porque nunca fue parte
  de ese bloque. Añadir este control mantiene la recomendación sin cambios respecto a la propuesta
  anterior, porque el argumento no dependía del tamaño de la otra mitad: el control está emparejado
  con TOTP, y `docs/03` §4.3 lo trata como regla dura, no como mejora. El costo del riesgo de tamaño
  se sigue absorbiendo con cortes de pull request encadenados dentro de este mismo cambio, nunca con
  otra división de cambio SDD — ver «Pronóstico de cortes y tamaño».

- **D8 — antes la segunda mitad de D8. ¿Se añade como requisito el aviso al quedar con menos de tres
  códigos de recuperación de MFA, o se declara brecha con destino nombrado?**

  **Recomendación: se añade, pero solo el dato calculado y auditado.** Se entrega **solo** cuántos
  códigos de recuperación de MFA sin usar quedan tras consumir uno, con la señal de «por debajo de
  tres» — nunca el envío del correo, porque ningún cambio de esta secuencia tiene un adaptador de
  envío. El destino del envío real queda **sin identificar en el roadmap actual**, dicho así en vez
  de inventando un dueño. Se mantiene la recomendación de la propuesta anterior sin cambios: los
  códigos de recuperación de MFA son de este cambio, así que su aviso de agotamiento también lo es, y
  el argumento de costo es idéntico al de **D7** — el riesgo de tamaño no lo crea este control, y se
  absorbe de la misma forma.

## Cobertura de los 19 requisitos publicados de `identity`

| Requisito | Este cambio | Lo que falta y su dueño |
|---|---|---|
| MFA obligatoria... | **Con delta de redacción (decisión resuelta arriba), luego completo** en la mitad que no es HTTP | `session-tokens-and-web-layer` (cuarto de esta secuencia) traduce el desenlace a token completo o restringido |
| Recuperación de contraseña... | **No tocado por este cambio** | `password-recovery-token` (tercero de esta secuencia) lo implementa completo, sin delta de redacción |
| Prohibición de enumeración (mitad de recuperación) | **No tocado por este cambio** | `password-recovery-token` lo cierra a nivel de caso de uso; `session-tokens-and-web-layer` entrega el `202` HTTP |
| Resultado tipado con dos desenlaces | **Con delta de redacción**, pasa a cuatro | — |
| Los demás 15 requisitos (contraseña, retroceso, rotación de refresco, separación de dominios, autorización por permisos, atomicidad, redacción de secretos) | **No toca** — ya cerrados por la parte 1, o de un cambio posterior | Ver la tabla equivalente de la propuesta archivada de la parte 1 |

## Impacto en migraciones, privilegios y auditoría

**Migraciones.** Una migración nueva (`V6`, siguiendo la numeración secuencial de Flyway), con al
menos: la columna `mfa_required` sobre `identity_staff_account`; la tabla de llaves de datos; la
tabla de credencial TOTP por cuenta; la tabla de códigos de recuperación de MFA; y, si **D7** se
aprueba, la tabla de retroceso de verificación TOTP. **Ninguna tabla de recuperación de contraseña**:
esa migración es de `password-recovery-token`. Los nombres exactos se fijan en diseño, con el
precedente literal de `identity_staff_account` e `identity_login_backoff`: prefijo de módulo,
`institution_id NOT NULL`, seguridad de fila habilitada y forzada, sin excepción alguna del catálogo
cerrado de ADR-0017.

**Privilegios.** Mismo patrón que `V5`: `REVOKE ALL FROM PUBLIC` antes de todo `GRANT`;
`confia_admin_app` con `SELECT`, `INSERT`, `UPDATE` y sin `DELETE`; `confia_readonly` con `SELECT`;
`confia_portal_app` sin ningún privilegio, por ser datos de personal (`docs/03` §6.1). Extensión de
`RolePrivilegeMatrixIT` con las filas nuevas.

**Auditoría.** Eventos nuevos sobre la primera fila de `docs/03` §12.2 que la parte 1 no produjo:
inscripción de MFA, verificación de código TOTP (éxito y fallo), uso de código de recuperación de
MFA. **La solicitud y el consumo del token de recuperación de contraseña no son de este cambio**:
son eventos de `password-recovery-token`. Cada evento de este cambio se escribe dentro de la misma
transacción que su efecto, siguiendo el patrón ya establecido por `AuditLogWriter`.

## Restricciones que este cambio no puede violar

1. **Regla 11 de `CLAUDE.md`, con su historia detrás.** Las tres revisiones de la parte 1
   encontraron un hallazgo bloqueante cada una, y **las tres fueron fugas de secretos que pasaban la
   verificación sin alcanzar el caso peligroso**: la interpolación de un hash completo en un mensaje
   de excepción, y —el más instructivo— `AuthenticationCommand`, un `record` de Java, filtrando la
   contraseña por su `toString()` **generado automáticamente por el lenguaje**, no escrito por
   nadie. En este cambio los secretos nuevos son el secreto TOTP en claro, los códigos de
   recuperación de MFA en claro, la llave maestra y las llaves de datos. Ninguno de esos valores
   puede viajar en un `record` sin `toString()` redactado explícito: cada objeto de valor que los
   cargue es una clase final con un `toString()` sobreescrito, nunca un `record` sin más, exactamente
   como `PlainPassword` y `Argon2Pepper` ya lo hacen. La verificación lo comprueba por inspección del
   texto producido, nunca por confianza en el diseño.
2. **Regla 14: toda acción sensible se audita.** Inscripción y baja de MFA, uso de código de
   recuperación de MFA, verificación TOTP fallida.
3. **Reglas de dependencia y de capas.** `domain` sin framework; jOOQ confinado a
   `infrastructure`; ningún módulo importa el `domain` de otro; `kernel` sin dependencias fuera del
   JDK (relevante para **D3**).
4. **Ninguna columna, tabla ni interfaz sin consumidor en este mismo cambio.** En particular, ninguna
   preparada «para cuando llegue» `password-recovery-token`.
5. **TDD estricto**, ejecutor `./mvnw verify` en `apps/api`, rojo observado y registrado antes de
   cada verde.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Un secreto nuevo —TOTP, código de recuperación de MFA, llave maestra, llave de datos— termina en un log, una excepción o un `toString()` generado por un `record` | **Alta si no se diseña**, con precedente exacto de los tres hallazgos de la parte 1 | Restricción 1, con envoltorios de valor explícitos y verificación por inspección |
| El pronóstico (12-17 tareas) supera el límite de quince, agravado si **D7**/**D8** añaden sus dos controles | **Alta, ya visible en el pronóstico, y ya no queda margen de dividir en otro cambio SDD** | Absorber el exceso con cortes de pull request encadenados dentro de este mismo cambio, ver «Pronóstico de cortes y tamaño» |
| La columna `mfa_required` queda desactualizada cuando un rol gana un permiso financiero, porque es un duplicado desnormalizado | Media, aceptada y declarada | Obligación con dueño impuesta al cambio 8, escrita arriba |
| El `switch` exhaustivo se implementa mal y dos ramas nuevas colapsan al mismo comportamiento | Baja, por construcción | El compilador rechaza cualquier `switch` que no cubra los cuatro casos; sin `default` que oculte un caso olvidado |
| Historial de subestimación: la parte 1 pronosticó 12-13 tareas y necesitó 12 tareas más cuatro cortes y nueve pull requests, todos sobre el presupuesto de ochocientas líneas | Alta, declarada sin descontar | Pronóstico de esta propuesta ya asume el mismo factor de 1,5x a 3x, ver más abajo |
| Una dependencia nueva (`spring-modulith-api` ya la trajo la parte 1; ninguna adicional se anticipa para AES-256-GCM, que es JDK puro) entra sin la revisión de `docs/03` §2.6 | Baja | Ninguna dependencia nueva prevista; si el diseño la introduce, sigue el mismo trato que Bouncy Castle recibió en la parte 1 |

## Plan de reversión

Igual de barata que en la parte 1: **no existe ningún entorno desplegado ni dato real**. La base
vive solo en contenedores efímeros de prueba.

1. Revertir el commit de fusión del corte de pull request afectado, o cerrar su pull request. Este
   cambio ya no tiene una sub-división en cambios SDD (esa división es ahora la frontera entre esta
   carpeta y `password-recovery-token`, no algo interno a esta carpeta), así que cada corte se
   revierte de forma independiente dentro de la misma cadena de pull requests, en orden inverso al
   de fusión.
2. La edición de `AuthenticationResult` no se revierte sola si algún corte posterior ya la consume;
   se revierte el corte completo que la introdujo.
3. Ninguna nota editorial de `docs/03` ni de `docs/09` arrastra código.
4. Una puerta que bloquee por error no se desactiva con una bandera (ADR-0008): se revierte el commit
   que la introdujo.
5. `password-recovery-token` no depende del esquema de este cambio, así que revertir este cambio no
   arrastra nada de aquel, y viceversa.

## Dependencias

- **`identity-module-and-password-authentication`**, archivado: el módulo, el esquema de dos
  tablas, `AuthenticationResult`, `AuthenticateWithPassword`, `PasswordHasher`, `Argon2Pepper`,
  `Argon2RawHasher`, `Argon2Profile`, `TransactionRunner`, `AuditLogWriter` y el patrón
  `@NamedInterface` de ADR-0022.
- **No depende de este cambio:** `password-recovery-token` (tercero de esta secuencia) — comparte
  origen con este cambio, pero ninguna dependencia técnica; puede diseñarse y aplicarse sin esperar a
  que este cambio se fusione, aunque el roadmap lo ordene después por convención de secuencia.
- **Dependen de este cambio:** `session-tokens-and-web-layer` (cuarto de esta secuencia), que traduce
  los cuatro desenlaces a tokens completos o restringidos; y, a través de la secuencia completa, el
  cambio 8, que además hereda la obligación de derivar `mfa_required` del permiso del rol.

## Pronóstico de cortes y tamaño

Sin reutilizar el pronóstico de la propuesta anterior por proporción: es el mismo pronóstico que ya
existía para la mitad que no era recuperación de contraseña, porque sacar esa mitad de esta carpeta
no cambia el tamaño de lo que queda. Se conserva íntegro el historial de subestimación de 1,5× a 3×
de este repositorio, declarado y **no** descontado.

| Bloque de trabajo | Tareas estimadas |
|---|---|
| ADR-0023, motor de cifrado en `kernel`, puerto y adaptador de llaves en `shared.crypto`, migración de la tabla de llaves | 3 a 4 |
| TOTP: inscripción, verificación con vector RFC 6238, prevención de reutilización, secreto cifrado | 3 a 4 |
| Códigos de recuperación de MFA con su puerto propio | 2 a 3 |
| Edición de `AuthenticationResult` y `AuthenticateWithPassword` (switch exhaustivo), columna `mfa_required`, delta de redacción de los dos requisitos publicados | 2 a 3 |
| Sujeto a **D7**/**D8**: límite de tasa de verificación TOTP y señal de aviso de códigos de MFA bajos | 2 a 3 |
| **Total** | **12 a 17** |

**Esto sigue rozando o superando el límite de quince tareas de `openspec/changes/README.md`, incluso
antes de contar el margen de subestimación histórico — sacar la recuperación de contraseña de esta
carpeta no lo resuelve, porque esa mitad nunca aportó tareas a este bloque.** En el extremo bajo (12)
el cambio queda con margen razonable; en el extremo alto (17) lo supera; con el historial de
subestimación de este repositorio sin descontar, la expectativa realista se acerca más al extremo
alto que al bajo. Si el propietario no aprueba **D7** ni **D8**, el total baja a 10-14 y el margen
mejora, pero los dos controles de `docs/03` §4.3 quedarían entregados como brecha en vez de cerrados.

**No hay más margen que ganar dividiendo este cambio en otro cambio SDD**: esa herramienta ya se usó
tres veces sobre el mismo cambio 7 (el segundo corte del 2026-09-24 y el tercero del 2026-09-27), y
el corte que queda —separar la recuperación de contraseña— es exactamente el que ya se ejecutó para
producir esta carpeta. Si la fase de tareas confirma que el total supera quince, la mitigación es la
misma que ya usó la parte 1: **cortes de pull request encadenados dentro de este mismo cambio SDD**,
uno por bloque de trabajo natural de la tabla de arriba — por ejemplo cinco cortes (`C1` sobre de
llaves y motor de cifrado, `C2` TOTP, `C3` códigos de recuperación de MFA, `C4` edición de
`AuthenticationResult` y columna `mfa_required`, `C5` límite de tasa y aviso de códigos bajos si D7 y
D8 se aprueban) —, cada uno apuntando al anterior y no a `main`, siguiendo `docs/15-flujo-de-trabajo-
git.md` §3.

**Sobre el presupuesto de líneas por pull request.** `docs/15-flujo-de-trabajo-git.md` §3 fija
**ochocientas líneas de cambio efectivo** por pull request, con una nota explícita de precisión: el
propietario del producto fijó esa cifra el **2026-09-18**, y **antes eran cuatrocientas**. Es decir,
las cuatrocientas líneas no son una invención de ninguna herramienta de este flujo de trabajo: **eran
el valor anterior de este mismo repositorio**, ya reemplazado. Gobiernan las ochocientas. Esta
propuesta las usa, siguiendo el mismo razonamiento que la propuesta archivada de la parte 1 ya
escribió para el mismo aparente conflicto entre el valor histórico y el vigente. Con 12-17 tareas y
piezas nuevas de cifrado, TOTP y códigos de recuperación de MFA, se anticipa **entrega en pull
requests encadenados**, no en uno solo.

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
- [ ] Diez códigos de recuperación de MFA se generan, se muestran una vez, y usar uno lo invalida sin
      afectar a los nueve restantes.
- [ ] Ningún secreto de este cambio —TOTP, código de recuperación de MFA, llave maestra, llave de
      datos— aparece en un registro, una excepción, un `toString()` ni la salida de una prueba
      fallida, verificado por inspección del texto producido.
- [ ] `docs/09-roadmap-y-fases.md` y `docs/03-seguridad.md` (§4.1, §4.3, §7.3) llevan sus notas
      editoriales fechadas, con el cuerpo de cada sección sin reescribir.
- [ ] La cobertura de los paquetes `domain` de este cambio alcanza el 95 % con JaCoCo y el 80 de
      mutación con PIT.
- [ ] Ninguna tabla, columna ni puerto de recuperación de contraseña existe en el árbol al cerrar
      este cambio.

# Exploración: MFA con TOTP y recuperación de contraseña

- **Cambio original:** `mfa-totp-and-password-recovery`
- **Partido el 2026-09-27 en:** `column-encryption-and-mfa-totp` (esta carpeta) y
  `password-recovery-token`
- El cambio 7 de F0 se ejecuta por tanto como **cuatro** cambios secuenciales:
  1. `identity-module-and-password-authentication` — **archivado el 2026-09-27**
  2. `column-encryption-and-mfa-totp` — esta carpeta
  3. `password-recovery-token`
  4. `session-tokens-and-web-layer`
- **Fase:** explorar
- **Fecha:** 2026-09-27
- **Estado:** exploración terminada, división aprobada por el propietario, pendiente de propuesta

> **Alcance de este documento.** Esta exploración se hizo sobre `mfa-totp-and-password-recovery`
> completo, antes del corte, y por eso describe las dos mitades. Es la exploración compartida:
> `password-recovery-token` la referencia desde su propia carpeta en vez de repetirla, como ya hicieron
> la parte B del cambio 5 y las tres partes del cambio 7.
>
> **Por qué se partió.** La propuesta pronosticó **12 a 17 tareas solo para la primera mitad**, contra
> el límite de quince, y más si se aprueba añadir los dos controles que `docs/03` documenta sin
> requisito publicado. El corte cae donde la recuperación de contraseña deja de depender de todo lo
> demás: no cifra ninguna columna, no usa TOTP, y su token se almacena hasheado y no cifrado.

> **Nota de persistencia.** El agente de exploración no dispuso de herramienta de escritura de
> archivos, así que entregó el informe íntegro y el orquestador lo transcribió, como en los tres
> cambios anteriores. Las dos afirmaciones más consecuentes del informe se verificaron de forma
> independiente antes de transcribirlo, y las dos se sostienen; quedan marcadas abajo.

## 1. Estado actual verificado

### 1.1 Qué requisitos publicados son de este cambio

`openspec/specs/identity/spec.md` tiene 19 requisitos y 37 escenarios tras el archivado de la parte 1.

**Ya cumplidos por la parte 1:** autenticación con contraseña; retardo por intentos fallidos con
retroceso exponencial; resultado tipado con dos desenlaces; verificación Argon2id contra hash señuelo;
estado del retroceso en PostgreSQL; institución previa a la autenticación; puerto y adaptador de
escritura de auditoría; ningún secreto observable; y el retardo calculado dentro de la transacción.

**De este cambio:** «MFA obligatoria para roles con escritura financiera o de configuración»
(líneas 40-65), que **necesita delta de redacción**; «Recuperación de contraseña con token de un solo
uso y de corta vida» (191-212); y «Prohibición de enumeración de usuarios» (214-234), cuyos dos únicos
escenarios publicados son de recuperación —el de inicio de sesión ya lo cierra la parte 1 con el hash
señuelo—.

**De la parte 3:** rotación de token de refresco; separación entre dominios de identidad; autorización
por permisos evaluada en el servidor.

**Brechas con destino ajeno, que no se tocan:** dimensión por IP (parte 3 y cambio 11); contraseñas
comprometidas y rehash (cambio 8); calibración de Argon2id (cambio 11); extremo a extremo con
Playwright (F1 o posterior).

### 1.2 Dos controles de `docs/03` sin requisito publicado

`docs/03-seguridad.md` documenta como controles el **límite de tasa de verificación de código TOTP**
—cinco intentos por cada quince minutos por usuario, con retroceso posterior— y el **aviso al quedar
con menos de tres códigos de recuperación**. **Ninguno de los 19 requisitos publicados los exige.** Es
un vacío entre lo que el documento de seguridad describe y lo que la especificación reconoce como
comportamiento obligatorio, y la propuesta debe decidir si se añaden como requisitos o se declaran
brecha con destino nombrado.

### 1.3 El tipo sellado: la red de seguridad prometida no existe

**Verificado de forma independiente por el orquestador.** `AuthenticationResult` declara
`permits Authenticated, Rejected`, y su Javadoc dice que este cambio añade su desenlace «editando la
cláusula `permits` de este archivo; el compilador entonces obliga a cada `switch` existente a
manejarlo».

**En producción no existe ningún `switch` sobre ese tipo.** Los dos únicos consumidores son
`result instanceof Authenticated`, en `AuthenticateWithPassword.java:155` y `:175`.

Un `instanceof` **no fuerza exhaustividad**. Añadir `SecondFactorRequired` al `permits` sin tocar esas
dos líneas **compila sin una advertencia** y trata el desenlace nuevo como si no estuviera autenticado
—es decir, como un fallo— en los dos sitios donde importa: el que decide el estado del retroceso
(línea 155) y el que decide qué se audita (línea 175). **Un segundo factor pendiente contaría como
intento fallido y avanzaría el contador del retroceso.**

La garantía que el Javadoc afirma, y que el informe de archivado de la parte 1 repetía, es
**aspiracional y no construida**. Convertir esos dos `instanceof` en un `switch` exhaustivo sin
`default` es trabajo de este cambio, y hasta entonces la red no existe. El informe de archivado ya
quedó corregido en ese punto antes de fusionarse.

### 1.4 No existe ningún concepto de rol ni de permiso en el árbol

**Verificado de forma independiente por el orquestador.** Una búsqueda de `role` o `permission` en
todo `apps/api/app/src/main/java` no devuelve nada, y `identity_staff_account` tiene exactamente cinco
columnas: `institution_id`, `id`, `email`, `password_hash` y `created_at`. **Ninguna de rol ni de
permiso.**

El requisito publicado que este cambio debe cumplir presupone justo eso: sus dos escenarios hablan de
un usuario con «rol `cashier`, que incluye el permiso `payments:write`» y de un usuario con rol
`administrator`. **Ese dato no existe en ningún lugar del esquema ni del dominio.**

La matriz de dieciocho filas de roles y permisos es del **cambio 8**, y en la cadena de dependencias
del roadmap el cambio 8 **depende de este**, no al revés. Es decir: **este cambio necesita un dato que
su propio dueño formal todavía no ha creado.**

Ni la exploración original, ni la propuesta, ni el informe de archivado de la parte 1 lo detectaron.
Hay tres caminos honestos, y las tres opciones **se usan** en este cambio, así que ninguna es el
trabajo «preparado para» que el propietario ya rechazó cuatro veces:

- **(a)** Este cambio añade una columna mínima explícita —por ejemplo `mfa_required BOOLEAN NOT NULL`
  en `identity_staff_account`, decidida por quien crea la cuenta— sin modelar rol ni permiso, y
  declara como brecha nombrada que la derivación real desde el permiso del rol es del cambio 8.
- **(b)** La exigencia de MFA es función únicamente de si la cuenta **ya tiene** un secreto TOTP
  inscrito, sin distinguir por rol, y los dos escenarios publicados se reescriben para que dejen de
  mencionar `cashier` y `administrator` como disparadores.
- **(c)** Este cambio no toca la obligatoriedad en absoluto: entrega el mecanismo de inscripción y
  verificación, y el escenario completo queda como brecha hasta el cambio 8.

> **RESUELTA POR EL PROPIETARIO EL 2026-09-27: opción (a).** Este cambio añade la columna mínima, y
> **el argumento decisivo fue de secuencia, no de modelado.** El cambio 3 —el que emite tokens— llega
> **antes** del cambio 8. Con (c), entre uno y otro habría una ventana en la que el sistema emite
> sesiones completas a usuarios con permisos financieros **sin ninguna exigencia de segundo factor**,
> porque nada sabría que hace falta. La opción (a) es la única que permite al cambio 3 exigir MFA
> desde el primer día en que emite tokens.
>
> La opción (b) se descartó porque **invierte la propiedad de seguridad**: el requisito existe para que
> un usuario privilegiado **no pueda** saltarse el segundo factor, y bajo (b) quien nunca se inscribe
> nunca se lo piden, de modo que el control pasa a ser opcional a conveniencia de quien lo evita.
> Además vuelve inexpresable el segundo escenario publicado, el de la sesión restringida para una
> cuenta que **no** tiene MFA configurada.
>
> **El costo aceptado, dicho en voz alta:** la columna es un **duplicado desnormalizado** de algo que
> el cambio 8 va a poseer. Si un rol gana un permiso financiero y la columna no se actualiza, el
> control queda silenciosamente desactivado para esa cuenta. La propuesta debe escribir ese riesgo y
> **el cambio 8 asume por requisito** la obligación de derivar esa columna del permiso del rol y
> eliminar la doble fuente. Se escribe como obligación del cambio 8, y no como «pregunta abierta»,
> por la misma razón que la condición de aceptación del crecimiento de `identity_login_backoff`: una
> pregunta abierta no obliga a nadie.

### 1.5 Qué se reutiliza del módulo existente

- **`PasswordHasher`** expone `matches`, `hash` y `decoyHash`, tipado sobre `PlainPassword` y
  `StoredPasswordHash` — nombres atados semánticamente a contraseña.
- **`Argon2RawHasher`** es la primitiva real, genérica sobre `byte[]`, pero **de paquete y no
  pública**: su propio comentario dice que es una costura de prueba, no un puerto.
- **`Argon2Profile`** ya es genérico: su forma no menciona contraseñas.

Para los diez códigos de recuperación, la reutilización limpia es **el perfil, el códec PHC y el
hasher de bajo nivel** —misma pimienta, mismo formato `$argon2id$`— pero con **un puerto nuevo** y sus
propios objetos de valor, en vez de forzar a `PlainPassword` y `StoredPasswordHash` a significar dos
cosas distintas. Eso evita reabrir `PasswordHasher`, que es contrato ya verificado.

### 1.6 Precedentes de prueba aprovechables

Todos demostrables **sin capa web**, que sigue sin existir: concurrencia con `CyclicBarrier`
(`LoginBackoffConcurrencyIT`), atomicidad (`LoginBackoffAtomicityIT`), redacción de secretos
(`IdentitySecretRedactionIT`), inventario de exclusión con conjunto no vacío
(`IdentityScopeExclusionInventoryTest`), vector de prueba de un RFC comparado byte a byte
(`Argon2PhcCodecTest`, patrón directamente aplicable a un vector de RFC 6238 para TOTP), y medición de
uniformidad de tiempo (`LoginTimingReportIT`, reutilizable para la respuesta `202` de recuperación).

## 2. La pregunta del cifrado

### 2.1 El sobre de llaves ya está especificado

`docs/03-seguridad.md` §7.3 fija: AES-256-GCM, vector de inicialización de 96 bits aleatorio por
operación, etiqueta de 128 bits, datos adicionales autenticados con `tabla|columna|id_de_fila`, y
formato `v1:<id_dek>:<iv>:<ciphertext>:<tag>`. La llave maestra vive en un gestor de secretos y nunca
en la base; la llave de datos vive cifrada en una tabla propia.

### 2.2 La recomendación de la parte 1 sigue en pie

AES-256-GCM está disponible de forma nativa en `javax.crypto` del JDK. **No hay aquí ningún parámetro
que falte**, a diferencia de Argon2id, que necesitó Bouncy Castle específicamente porque el
codificador de Spring no expone `withSecret(...)`. Eso sostiene la recomendación original de **sin
dependencia nueva**.

Queda abierta una pregunta de ubicación: si el cifrado puro —una función de bytes a bytes, sin acceso a
base de datos— debe vivir en `kernel`, que exige pureza de JDK, o en `identity.infrastructure`, o en
un paquete de `shared` con su propia interfaz nombrada. La gestión de la tabla de llaves necesita jOOQ
y **no puede** vivir en `kernel` bajo ninguna alternativa.

### 2.3 La tabla de llaves no entra en el catálogo cerrado, y eso ya está decidido

ADR-0017 tiene **exactamente cuatro** entradas en su catálogo cerrado y dice literalmente que ninguna
otra tabla puede exceptuarse sin un ADR nuevo. Además, el plan de F0 ya evaluó y **descartó
explícitamente** ampliar ese catálogo, porque contradice su propósito.

Con eso, **no hay decisión que tomar**: la tabla de llaves sigue la regla general de ADR-0009 —
`institution_id NOT NULL`, restricción única con ese discriminador, seguridad de fila habilitada y
forzada—, como cualquier tabla de negocio.

Y eso tiene una consecuencia que conviene declarar **como beneficio y no solo como costo**: si la
tabla lleva `institution_id`, **las llaves de datos quedan aisladas por institución**. Cifrar o
descifrar el secreto de una institución nunca toca la llave de otra, y restaurar el respaldo de una
sola institución no depende de una tabla de llaves global compartida.

### 2.4 La rotación real no cabe en este cambio, por dependencia y no por elección

§7.3 pide rotación anual de la llave de datos «con recifrado progresivo **en trabajo por lotes**». Un
recifrado por lotes es exactamente lo que `db-scheduler` existe para hacer (ADR-0016), y
**`db-scheduler` todavía no está en el árbol**: su infraestructura es el cambio 9, que viene después
de las tres partes del cambio 7.

**Consecuencia verificada:** este cambio puede entregar el **esquema que hace posible** la rotación
—una columna de estado `active`/`retired` que se usa desde el primer día para decidir con qué llave se
cifra cada valor nuevo— pero **no su ejecución**. La rotación por lotes es una brecha con destino
nombrado, el mismo cambio que introduzca mantenimiento programado, igual que ya se difirió la purga de
`identity_login_backoff`.

### 2.5 La llave maestra sigue el precedente que ya existe

`Argon2Pepper` es un objeto de valor con `toString()` redactado, construido desde configuración del
proceso, que **falla al construirse** si falta o mide otra cosa; en pruebas se usa un literal declarado
no secreto en su propio comentario; y el cableado real desde el gestor de secretos se dejó para el
cambio 11.

Ese mismo patrón, aplicado sin variación a la llave maestra, responde la pregunta de forma consistente
con el resto del repositorio. **No hace falta inventar ningún mecanismo nuevo.**

### 2.6 El ADR que corresponde es el 0023, no el 0022

Merece ADR por la misma razón que lo mereció ADR-0022: es una decisión que **otros módulos futuros**
van a necesitar igual —§7.3 nombra el documento nacional del estudiante, el del encargado, su RTN, la
cuenta de reembolso, el token de la pasarela y las notas del estudiante—, y decidirla sin ADR obliga a
cada módulo siguiente a redescubrirla.

**Los ADR van del 0001 al 0022 sin huecos**, así que el siguiente libre es el **0023**. La propuesta
archivada de la parte 1 había anticipado que este cifrado ocuparía el 0022, pero ese número lo consumió
una decisión distinta durante la implementación de esa parte: la de las interfaces nombradas del módulo
`shared`.

## 3. La costura con la parte 1

En resumen, cuatro cosas concretas:

1. Editar la cláusula `permits` de `AuthenticationResult` para añadir el desenlace o desenlaces nuevos.
2. **Convertir los dos `instanceof` de `AuthenticateWithPassword` en un `switch` exhaustivo sin
   `default`**, porque hoy no lo son y el Javadoc de la clase asume que sí (§1.3).
3. Decidir de dónde sale la señal «esta cuenta necesita MFA», dado que no existe ningún dato de rol
   (§1.4).
4. Escribir el delta de redacción del requisito publicado, cuyos dos escenarios terminan en «emite un
   token de sesión completo» y «emite únicamente una sesión restringida» — lenguaje que pertenece a la
   parte 3.

Añadir el desenlace implica **modificar código ya archivado y verificado**, no solo el archivo del
tipo sellado: hoy no hay ningún punto en el cuerpo transaccional que consulte si la cuenta tiene MFA
configurada, porque no hay dato que lo diga.

## 4. La costura con la parte 3

Este cambio debe devolver un resultado tipado que distinga al menos tres situaciones —autenticado sin
segundo factor pendiente; segundo factor requerido, pendiente de código; y MFA no inscrita, con sesión
restringida a la inscripción—, cada una con los identificadores que la parte 3 necesita para decidir
qué token emitir. **Nunca con un campo de alcance de autorización**, siguiendo la misma restricción que
ya rige los dos desenlaces existentes.

El patrón a seguir es el que ya estableció `AuthenticationDecision`: el resultado más lo que el borde
necesita, decidido en el dominio y materializado fuera.

## 5. Riesgos

1. **El Javadoc afirma una protección del compilador que no existe** (§1.3). Si este cambio solo edita
   el `permits`, el código compila y se comporta mal en silencio.
2. **No existe dato de rol ni de permiso** para sostener la obligatoriedad de MFA que el requisito
   publicado exige (§1.4). Decisión de arquitectura no identificada por ninguna fase anterior.
3. **La rotación de la llave de datos exige `db-scheduler`, que no existe** (§2.4): queda fuera de
   alcance por dependencia de infraestructura.
4. **Dos controles de `docs/03` no tienen requisito publicado** (§1.2): implementarlos exige añadir
   requisitos que hoy no están.
5. **Reutilizar `PasswordHasher` para los códigos de recuperación** haría que sus nombres dejen de
   describir lo que contienen (§1.5).
6. **Historial de subestimación.** La parte 1 se pronosticó en 12 o 13 tareas y terminó en 12, pero con
   **cuatro cortes y nueve pull requests**, y los cuatro cortes se pasaron del presupuesto de 800
   líneas. Este cambio tiene **más** piezas nuevas que la parte 1: cifrado con su ADR, TOTP con vector
   de RFC, códigos de recuperación con hasher nuevo, recuperación de contraseña con tabla y
   concurrencia propias, y edición de código ya archivado.

## 6. Recomendación

**Dividir en dos cambios secuenciales.**

- **`2a`** — sobre de llaves con su ADR-0023, TOTP (inscripción, verificación contra el vector de
  RFC 6238, códigos de recuperación) y la edición de `AuthenticationResult` con su `switch`.
- **`2b`** — recuperación de contraseña: token de un solo uso, concurrencia propia, respuesta uniforme
  `202`.

El motivo del corte: **la recuperación de contraseña no depende del cifrado de columna ni de TOTP en
absoluto** — es una tabla y un flujo propios. Y el sobre de llaves debe entregarse **junto con** su
único consumidor real de este cambio, el secreto TOTP, no aislado: entregarlo solo sería
infraestructura sin uso, patrón que este repositorio ya rechazó.

Se descartó dividir en tres —aislando el sobre de llaves de TOTP— por esa misma razón.

## 7. Estimación de tamaño

- **`2a`**: **13 a 16 tareas**, con riesgo real de superar el límite de quince. Migración de dos tablas
  nuevas, motor de cifrado, ADR-0023, generador y verificador TOTP con su vector, códigos de
  recuperación, edición de dos clases ya archivadas, y extensión de las puertas de esquema y de
  privilegios.
- **`2b`**: **7 a 9 tareas**.

Si al llegar a la fase de tareas `2a` supera quince, el corte adicional natural es separar el sobre de
llaves con su ADR del mecanismo TOTP, **en cortes de pull request encadenados y no en cambios SDD
nuevos**, declarando en la misma propuesta que el primero es **usado** por el segundo.

## 8. Decisiones que la propuesta debe resolver, numeradas

1. **¿Se divide este cambio en dos** —cifrado y TOTP, por un lado; recuperación de contraseña, por
   otro— **o se mantiene como uno solo?**
2. **RESUELTA EL 2026-09-27: opción (a).** ¿Cómo determina este cambio si una cuenta necesita MFA
   obligatoria, dado que no existe ningún dato de rol ni de permiso? Se añade la columna mínima, por
   el argumento de secuencia que §1.4 recoge: es la única opción que permite al cambio 3 exigir MFA
   desde el primer día en que emite tokens. Queda pendiente de escribir en la propuesta el riesgo de
   deriva y la obligación que este cambio impone al cambio 8.
3. **¿Dónde vive el motor de cifrado puro** — `kernel`, `identity.infrastructure`, o un paquete de
   `shared` con su propia interfaz nombrada? Importa porque otros módulos futuros necesitarán la misma
   primitiva.
4. **¿Se escribe el ADR-0023 para el sobre de llaves, con qué alcance exacto?**
5. **¿Se entrega la mitad de búsqueda determinista por HMAC de §7.3, o se declara sin consumidor?** El
   secreto TOTP nunca se busca por valor, solo se lee por cuenta.
6. **¿Se añaden como requisitos los dos controles de `docs/03` que hoy no tienen ninguno** —límite de
   tasa de verificación TOTP y aviso de códigos de recuperación bajos— **o se declaran brecha con
   destino nombrado?**
7. **¿Se reutiliza `PasswordHasher` para los códigos de recuperación, o se declara un puerto nuevo**
   sobre las mismas primitivas?
8. **¿Cómo llega la llave maestra al proceso?** Recomendado el precedente literal de `Argon2Pepper`,
   pero corresponde al propietario confirmarlo.
9. **¿Se declara explícitamente que la ejecución de la rotación de la llave de datos es una brecha**
   con destino en el cambio que introduzca mantenimiento programado, en vez de construir algo parcial
   ahora?

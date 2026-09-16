# ADR-0005: Autenticación y gestión de sesiones

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `modules/identity` (dominios `staff` y `guardian` separados según ADR-0003), `shared/security` (filtros y políticas de autorización de Spring Security, cifrado, limitación de tasa), `apps/admin-web`, `apps/portal-web`, `apps/mobile` (fase futura), esquema de PostgreSQL (`staff_users`, `guardian_users`, familias de tokens de refresco, dispositivos), Redis para limitación de tasa y bloqueo.
- **Revisión:** 2026-09-14. Alineado con ADR-0013 (backend en Java con Spring Boot). La decisión no cambia; se actualizan las herramientas y los mecanismos de verificación. La columna de institución se llama `institution_id`, como en ADR-0009.

## Contexto y problema

CONFIA autentica a dos poblaciones que no comparten nada: decenas de miembros del personal
administrativo que cobran, facturan, anulan y configuran tarifas desde la red institucional, y
cientos o miles de encargados de pago que entran desde internet abierto, con su propio dispositivo,
en una red desconocida. ADR-0003 ya decidió que son **dos dominios de identidad disjuntos**. Lo que
falta decidir es **cómo se autentica y cómo se sostiene la sesión** en cada uno de ellos, y cómo se
sostiene en una app móvil que todavía no existe pero que va a consumir la misma API.

Las fuerzas que actúan:

1. **Tres clientes con restricciones distintas y un solo backend.** El panel administrativo y el
   portal son aplicaciones de navegador, donde la cookie `HttpOnly` es la única forma de guardar un
   secreto fuera del alcance del JavaScript de la página. La app móvil futura no tiene el mismo
   modelo de cookies ni de orígenes, y su almacén seguro es el del sistema operativo. Un solo
   mecanismo de transporte de credencial no sirve bien para ambos.
2. **El robo de token es el vector realista.** No hace falta romper la criptografía: basta con un
   dispositivo comprometido, una extensión de navegador maliciosa o un respaldo de teléfono sin
   cifrar. La pregunta no es cómo evitar que un token se filtre alguna vez, sino **cómo detectarlo y
   cortarlo cuando ocurra**.
3. **Datos de menores de edad y dinero.** Una sesión secuestrada del personal permite emitir
   facturas y mover dinero. Una sesión secuestrada de un encargado expone el estado de cuenta de un
   menor. Ambas cosas obligan a notificación y consecuencias legales.
4. **Un solo desarrollador.** Cada componente de infraestructura que se agrega hay que instalarlo,
   parchearlo, monitorearlo, respaldarlo y recuperarlo. Una pieza más de alta disponibilidad es una
   pieza más que puede tumbar el sistema a las dos de la mañana.
5. **La revocación tiene que existir de verdad.** Un sistema financiero necesita responder a "cierra
   la sesión de ese cajero ahora" y a "cierra todas las sesiones de ese encargado porque perdió el
   teléfono". Un token de acceso de larga vida sin mecanismo de revocación no puede responder a eso.
6. **Enumeración de usuarios.** El portal es público. Un formulario de recuperación de contraseña
   que responde distinto según exista o no la cuenta le regala al atacante la lista de encargados
   registrados, y por extensión la lista de familias de la institución.

Si no se decide, el resultado por defecto es un JWT de veinticuatro horas guardado en
`localStorage`, sin revocación, sin rotación y con la misma clave para ambas audiencias. Eso
funciona la primera semana y es indefendible ante cualquier revisión de seguridad.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Revocación efectiva y rápida de una sesión concreta | Muy alto | "Cierra esa sesión ahora" debe tener respuesta. Un token de acceso irrevocable durante horas no la tiene. |
| Detección de robo de credencial, no solo prevención | Muy alto | El robo va a ocurrir. La rotación con detección de reutilización convierte un robo silencioso en un evento observable. |
| Separación criptográfica real entre personal y encargados | Muy alto | Requisito heredado de ADR-0003. Un token del portal debe fallar por firma, no por rol. |
| El secreto de larga vida nunca alcanzable por JavaScript en la web | Muy alto | Una inyección de scripts en el portal no debe poder exfiltrar una sesión persistente. |
| Soporte de la app móvil futura sin rediseñar el backend | Alto | El contrato de API es un producto desde el día uno (`docs/01-arquitectura.md`, sección 1). |
| Costo operativo de la infraestructura de sesión | Alto | Un desarrollador solo no sostiene un almacén de sesión de alta disponibilidad ni un proveedor de identidad más. |
| Resistencia a fuerza bruta y a relleno de credenciales | Alto | El portal es público y los encargados reutilizan contraseñas. |
| Ausencia de dependencia externa crítica en el camino de inicio de sesión | Alto | Si el proveedor de identidad cae, nadie cobra ese día. |
| Complejidad de implementación y superficie de error | Medio | La rotación con familias es más código que una sesión de servidor. Es código acotado y probado. |

## Opciones consideradas

### Opción A: Sesiones de servidor con estado en Redis

Cookie de sesión opaca, estado completo de la sesión en Redis, revocación por borrado de la clave.

**Ventajas.**

- Revocación instantánea y trivial: se borra la clave y la sesión deja de existir en la siguiente
  petición.
- El cliente nunca tiene reclamaciones firmadas, de modo que no hay riesgo de tokens que sobrevivan
  a un cambio de rol o de permisos.
- Modelo mental simple, muy conocido, con soporte directo en Spring Security y Spring Session.
- Listar y revocar sesiones activas por dispositivo es una consulta al almacén.

**Desventajas.**

- **Redis pasa a estar en el camino crítico de cada petición autenticada.** Redis ya existe en el
  stack para limitación de tasa (`docs/01-arquitectura.md`, sección 3.1), pero ahí una caída
  degrada funcionalidad secundaria; aquí una caída deja al sistema completo sin autenticación. Eso
  obliga a Redis con persistencia y con alta disponibilidad, que es exactamente el tipo de
  infraestructura que un desarrollador solo no debe operar.
- La cookie de sesión no encaja bien con la app móvil. Un cliente nativo tendría que gestionar un
  contenedor de cookies propio y un modelo de expiración que no controla, o se acabaría enviando el
  identificador de sesión en una cabecera, con lo cual se pierde la protección `HttpOnly` que era la
  ventaja del enfoque.
- Cada petición cuesta una lectura de red adicional antes de decidir la autorización.
- El estado de sesión en un almacén volátil hace que un reinicio mal configurado de Redis expulse a
  todos los usuarios a la vez, incluido el cajero a mitad de un cobro.

### Opción B: Proveedor de identidad externo, autoalojado o como servicio

Keycloak autoalojado, o un proveedor como Auth0, con OpenID Connect.

**Ventajas.**

- Flujos maduros y probados por terceros: MFA, recuperación de contraseña, políticas, federación,
  inicio de sesión social, auditoría de identidad.
- Menos código propio de seguridad, que es el código donde un error cuesta más caro.
- Camino directo si en el futuro la institución quiere federar con su directorio corporativo.

**Desventajas.**

- **Keycloak autoalojado es un servidor más que instalar, dimensionar, parchear y actualizar.** Sus
  actualizaciones mayores han roto configuraciones y han requerido migraciones de base de datos
  propias. Para un desarrollador solo, es un segundo sistema con su propio ciclo de vida operativo,
  y contradice la restricción de "sin infraestructura que exija un equipo de plataforma"
  (`docs/01-arquitectura.md`, sección 1).
- **Un proveedor como servicio introduce una dependencia externa crítica en el camino de inicio de
  sesión.** Si el proveedor tiene una interrupción, nadie cobra en ventanilla ese día. La
  institución no acepta que su capacidad de operar dependa de un tercero que no puede llamar.
- Costo recurrente por usuario activo. Con cientos o miles de encargados, el precio escala con la
  población de padres de familia, que es precisamente la parte del sistema que no genera ingreso
  directo.
- Los datos de identidad de menores y de sus responsables saldrían del control de la institución
  hacia un tercero, lo que agrega una evaluación de tratamiento de datos personales que
  `docs/08-datos-privacidad-y-retencion.md` tendría que cubrir.
- La separación de dos dominios de identidad disjuntos de ADR-0003 se implementaría como dos reinos
  o dos tenants del proveedor, lo que duplica la configuración externa y hace más difícil demostrar
  en una prueba automatizada que la separación se cumple.
- La lógica de autorización fina de CONFIA (permisos por módulo, contexto de institución, contexto
  de encargado para la seguridad a nivel de fila) se queda igualmente en el backend. El proveedor
  resuelve autenticación, no la parte cara.

### Opción C: JWT de acceso de corta vida más token de refresco rotativo con detección de reutilización

Token de acceso firmado, de vida muy corta, sin estado. Token de refresco opaco, persistido en
PostgreSQL, rotado en cada uso, agrupado en una **familia** por sesión, con revocación de la familia
completa cuando se detecta reutilización de un token ya consumido.

**Ventajas.**

- El token de acceso no requiere consulta de estado, de modo que la autenticación no depende de
  Redis ni de ningún servicio externo en el camino crítico.
- **La rotación con detección de reutilización convierte un robo de credencial en un evento
  detectable.** Si el atacante usa el token robado, el usuario legítimo dejará de poder refrescar, o
  al revés; en cualquiera de los dos casos aparece un token ya consumido y la familia entera se
  revoca. Un robo silencioso deja de ser silencioso.
- La ventana de exposición de un token robado de acceso es de diez minutos, no de horas.
- El estado de refresco vive en PostgreSQL, que ya es el almacén crítico del sistema, con respaldo y
  recuperación a un punto en el tiempo (`docs/01-arquitectura.md`, sección 8). No agrega un
  componente nuevo que operar.
- Listar y revocar sesiones activas por dispositivo es una consulta sobre la tabla de familias.
- Sirve a los tres clientes con el mismo backend: cookie `HttpOnly` para navegador, cabecera
  `Authorization` para la app móvil, mismo modelo de familia y de rotación en ambos casos.

**Desventajas.**

- Es más código propio que la opción A, y es código de seguridad. La rotación, la ventana de
  tolerancia ante una respuesta perdida por red y la revocación de familia son detalles fáciles de
  implementar mal.
- **La revocación de un token de acceso no es instantánea**: existe una ventana de hasta diez
  minutos entre el cierre forzado de una sesión y la expiración natural del token de acceso. Se
  acepta esa ventana para lecturas y se cierra para operaciones sensibles con una comprobación de
  vigencia de familia en la política de autorización de escritura financiera.
- Un cambio de rol o de permisos no se refleja hasta el siguiente refresco, por la misma razón.
- La rotación produce falsos positivos si el cliente reintenta un refresco cuya respuesta se perdió.
  Exige una ventana de gracia acotada y bien probada, o el usuario será expulsado por una red mala.
- Persistir refrescos en PostgreSQL agrega escrituras frecuentes a una tabla que crece y hay que
  purgar.

## Decisión

**Se adopta la opción C: token de acceso JWT de corta vida más token de refresco rotativo con
familias y detección de reutilización, con dominios de identidad, claves de firma y audiencias
totalmente separados entre personal y encargados, transportado por cookie `HttpOnly` en la web y
por cabecera `Authorization` en la app móvil futura.**

Gana por tres factores de peso muy alto que las otras opciones no satisfacen a la vez. Primero,
**detección de robo, no solo prevención**: ni la sesión en Redis ni el proveedor externo detectan por
sí mismos que una credencial fue clonada; la rotación con familias sí. Segundo, **ausencia de
dependencia crítica nueva**: la opción A pone Redis en el camino de cada petición y la opción B pone
un tercero o un servidor de identidad adicional en el camino del inicio de sesión, y ninguna de las dos es sostenible por una
sola persona. Tercero, **soporte del cliente móvil futuro sin rediseño**, que la cookie de sesión de
la opción A no da limpiamente.

Se descarta la opción A porque su ventaja real (revocación instantánea) se obtiene casi por completo
en la opción C con una vida de acceso de diez minutos y comprobación de familia en escrituras
sensibles, mientras que su costo (Redis de alta disponibilidad en el camino crítico, más un modelo
que no encaja con la app móvil) es permanente.

Se descarta la opción B porque traslada el problema barato (autenticación) a un tercero mientras
deja el problema caro (autorización fina, contexto de institución y de encargado para seguridad a
nivel de fila) en el backend, y a cambio introduce una dependencia externa en la capacidad de cobrar
o un servicio más que parchear.

### Formato y vida de los tokens

| Elemento | Personal (`staff`) | Encargados (`guardian`) |
|---|---|---|
| Emisor (`iss`) | `confia-admin` | `confia-portal` |
| Audiencia (`aud`) | `confia-admin` | `confia-portal` |
| Algoritmo de firma | `EdDSA` (Ed25519) | `EdDSA` (Ed25519) |
| Par de claves | Par administrativo, variable de entorno propia | Par del portal, variable de entorno distinta |
| Vida del token de acceso | 10 minutos | 10 minutos |
| Vida del token de refresco | 8 horas, no renovable más allá de 12 horas de sesión absoluta | 30 días de inactividad, 90 días absolutos |
| Transporte web | Cookie `HttpOnly`, `Secure`, `SameSite=Strict`, `Path` propio, dominio administrativo | Igual, con nombre, dominio y `Path` del portal |
| Transporte móvil | No aplica en fase uno | `Authorization: Bearer` más refresco en almacén seguro del dispositivo |

Se elige **EdDSA sobre Ed25519** en vez de HMAC porque una clave simétrica compartida entre el
proceso administrativo y el proceso trabajador es una clave que puede firmar tokens; con firma
asimétrica, los verificadores solo necesitan la clave pública. `RS256` sería una alternativa
aceptable y más ampliamente soportada; se prefiere `EdDSA` por tamaño de firma menor y por no
depender de la elección correcta de parámetros de relleno. Si una integración futura exigiera
`RS256`, el cambio es de configuración y no de arquitectura.

Las reclamaciones del token de acceso incluyen: `sub` (identificador opaco del usuario), `iss`,
`aud`, `exp`, `iat`, `jti`, `sid` (identificador de la familia de sesión), `tenant` (institución,
ver ADR-0009), `amr` (métodos de autenticación usados, con `otp` presente si se completó MFA) y el
conjunto de permisos efectivos. **Nunca se incluye en el token ningún dato personal**: ni nombre, ni
correo, ni documento de identidad, ni datos de estudiantes (`CLAUDE.md`, regla 11).

### Rotación con familias y detección de reutilización

Cada inicio de sesión crea una **familia de tokens** que representa una sesión en un dispositivo. En
cada refresco:

1. Se busca el token de refresco presentado. Si no existe, se rechaza.
2. Si existe y **ya fue consumido**, se trata como reutilización: se revoca la familia completa, se
   invalida toda sesión asociada, se escribe un evento en la bitácora de auditoría y se notifica al
   titular de la cuenta. El atacante y el usuario legítimo quedan ambos fuera, que es el resultado
   correcto: es preferible obligar a un inicio de sesión a permitir que un intruso permanezca.
3. Si existe y está vigente, se marca como consumido, se emite un token de refresco nuevo dentro de
   la misma familia y un token de acceso nuevo.
4. **Ventana de gracia**: si el mismo token se presenta de nuevo dentro de los 10 segundos
   siguientes a su consumo y desde el mismo dispositivo, se devuelve el par emitido en el paso 3 en
   lugar de declarar reutilización. Esto cubre el caso real de una respuesta perdida por una red
   móvil mala, sin abrir una ventana significativa a un atacante.

Esquema conceptual, coherente con ADR-0009 en cuanto a `institution_id`:

```sql
CREATE TABLE refresh_token_families (
    id             UUID PRIMARY KEY,
    institution_id      UUID        NOT NULL,
    principal_type TEXT        NOT NULL CHECK (principal_type IN ('staff', 'guardian')),
    principal_id   UUID        NOT NULL,
    device_label   TEXT,
    user_agent_fp  TEXT        NOT NULL,
    ip_first_seen  INET,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoked_reason TEXT CHECK (
        revoked_reason IN ('logout', 'reuse_detected', 'password_change',
                           'admin_revoke', 'absolute_expiry')
    )
);

CREATE TABLE refresh_tokens (
    id            UUID PRIMARY KEY,
    institution_id     UUID        NOT NULL,
    family_id     UUID        NOT NULL REFERENCES refresh_token_families (id),
    token_hash    BYTEA       NOT NULL,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ,
    replaced_by   UUID REFERENCES refresh_tokens (id),
    CONSTRAINT refresh_tokens_hash_unique UNIQUE (institution_id, token_hash)
);

CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id, issued_at DESC);
CREATE INDEX refresh_token_families_principal_idx
    ON refresh_token_families (institution_id, principal_type, principal_id)
    WHERE revoked_at IS NULL;
```

El token de refresco se guarda **hasheado**, nunca en claro. Un volcado de esa tabla no permite
suplantar a nadie, igual que un volcado de la tabla de contraseñas no permite iniciar sesión.

### Contraseñas: almacenamiento y política

**Hash con Argon2id**, con parámetros `m = 19456 KiB` (19 MiB), `t = 2`, `p = 1` y sal aleatoria de
16 bytes por contraseña. Es una de las configuraciones mínimas recomendadas por OWASP para Argon2id
y está elegida para un host modesto: 19 MiB por verificación concurrente es asumible en el
dimensionamiento de fase uno, mientras que una configuración de 64 MiB o más convertiría un pico de
inicios de sesión al inicio del mes de cobro en una presión de memoria real. **Los parámetros se
almacenan junto al hash**, de modo que subirlos más adelante no invalida las contraseñas existentes:
se rehashea de forma transparente en el siguiente inicio de sesión correcto.

Política alineada con NIST SP 800-63B:

1. **Longitud mínima de 12 caracteres** para personal y de 10 para encargados, con máximo de 128.
   Se aceptan todos los caracteres imprimibles Unicode, incluidos espacios y emoji, con
   normalización `NFKC` antes de hashear.
2. **Sin reglas de composición forzada.** No se exige mayúscula, número ni símbolo. Esas reglas
   empujan a los usuarios hacia patrones predecibles y hacia escribir la contraseña en un papel
   pegado al monitor de caja.
3. **Sin expiración periódica forzada.** La contraseña se cambia cuando hay indicio de compromiso, no
   cada noventa días. La rotación obligatoria produce contraseñas incrementales y previsibles.
4. **Verificación contra listas de contraseñas comprometidas** en el momento de establecerla o
   cambiarla, mediante una lista local de las contraseñas filtradas más comunes. Se rechaza también
   la contraseña que contenga el nombre de la institución, el correo del usuario o el nombre del
   sistema.
5. **Medidor de fortaleza informativo, nunca bloqueante** más allá de las reglas anteriores.
6. Cambio de contraseña **revoca todas las familias de tokens** del usuario excepto la sesión desde
   la que se realiza el cambio, e informa de ello de forma explícita.

### MFA

**TOTP obligatoria** (RFC 6238, ventana de 30 segundos, tolerancia de un paso, secreto de 160 bits)
para todo rol del personal con capacidad de escritura financiera o de configuración: cajero,
contador y administrador. Opcional pero recomendada para el resto del personal, y opcional para
encargados, con la excepción de que el portal exige verificación adicional para cambiar el correo de
contacto o los datos de un método de pago.

- El secreto TOTP se almacena cifrado con una clave de aplicación, no en claro.
- Se emiten diez **códigos de recuperación** de un solo uso, mostrados una única vez, almacenados
  hasheados.
- La reutilización de un código TOTP dentro de su ventana se rechaza, para impedir replay de un
  código capturado.
- La inscripción de MFA y su desactivación son acciones sensibles y escriben en la bitácora de
  auditoría (`CLAUDE.md`, regla 14). La desactivación de MFA de un usuario con escritura financiera
  requiere aprobación de un administrador distinto del titular.
- Se elige TOTP y no SMS porque el SMS es interceptable por intercambio de SIM y porque tiene costo
  por mensaje que escala con la población. Se elige TOTP y no WebAuthn como obligatorio de fase uno
  porque WebAuthn exige dispositivos con autenticador disponible en cada estación de caja, lo cual no
  se puede garantizar hoy. WebAuthn queda como incorporación posterior en el mismo modelo `amr`, sin
  cambio de arquitectura.

### Bloqueo, fuerza bruta y enumeración

- **Retroceso exponencial por cuenta y por dirección de origen.** Tras el tercer intento fallido, el
  siguiente intento se retrasa 2 segundos, luego 4, 8, 16, hasta un tope de 15 minutos. El contador
  se reinicia con un inicio de sesión correcto. El estado del contador vive en Redis, que es
  apropiado aquí porque su pérdida degrada la protección sin impedir el inicio de sesión.
- **Bloqueo temporal** de la cuenta tras diez fallos consecutivos, con notificación al titular, y
  alerta de seguridad si el patrón se repite sobre muchas cuentas distintas, que es la firma del
  relleno de credenciales.
- **Prevención de enumeración de usuarios.** El inicio de sesión responde exactamente el mismo
  cuerpo y el mismo código de estado exista o no la cuenta. La recuperación de contraseña responde
  siempre "si la dirección corresponde a una cuenta, recibirá un mensaje", exista o no. El registro
  de encargado no revela si el correo ya está en uso; en su lugar envía un mensaje al titular
  informando del intento.
- **Igualación del tiempo de respuesta.** Cuando el usuario no existe, se ejecuta igualmente una
  verificación Argon2id contra un hash señuelo constante, de modo que el tiempo de respuesta no
  distinga una cuenta existente de una inexistente. Sin esto, la respuesta idéntica no sirve de
  nada: el atacante mide milisegundos.
- Limitación de tasa agresiva en el portal, por dirección de origen y por cuenta, con CAPTCHA
  activado por umbral en lugar de por defecto.

### Recuperación de contraseña

1. El usuario solicita recuperación. La respuesta es siempre la misma.
2. Si la cuenta existe, se genera un token aleatorio de 256 bits, se almacena **hasheado**, con
   vigencia de **30 minutos** y **un solo uso**.
3. El enlace se envía al correo verificado. El token nunca se registra en logs (`CLAUDE.md`, regla 11).
4. Al consumirse, el token se marca usado, se establece la contraseña nueva y **se revocan todas las
   familias de tokens del usuario**, en todos los dispositivos. Si la cuenta fue tomada, la sesión
   del atacante muere ahí.
5. Se notifica al titular que la contraseña cambió, con fecha y origen aproximado.
6. Solicitar recuperación **no bloquea** la cuenta ni invalida la sesión actual, para que un atacante
   no pueda expulsar a un usuario legítimo con solo conocer su correo.

### Cierre de sesión y gestión de dispositivos

- **Cierre de sesión** revoca la familia actual y borra la cookie. En la web es una operación de
  servidor, no un borrado de almacenamiento del cliente.
- **Cierre de sesión en todos los dispositivos** revoca todas las familias del usuario.
- Ambas aplicaciones ofrecen una vista de **sesiones activas** que lista, por familia, la etiqueta de
  dispositivo, el instante de creación, el de último uso y el origen aproximado, con acción de
  revocar individualmente. Esa vista consulta `refresh_token_families`, no requiere estado adicional.
- Toda revocación escribe en la bitácora de auditoría.

### App móvil futura

- El token de refresco se almacena en el **almacén seguro del sistema operativo**: Keychain en iOS
  con `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, y Keystore respaldado por hardware en
  Android. Nunca en preferencias compartidas ni en un archivo de la aplicación, porque un respaldo
  del dispositivo o un teléfono con acceso de superusuario los expone.
- **Por qué no cookies en la app móvil.** La protección de una cookie `HttpOnly` proviene de que el
  navegador impide que el JavaScript de la página la lea, y de que el propio navegador aplica el
  aislamiento de origen. En una app nativa no existe esa frontera: el código de la app tiene acceso
  total a su contenedor de cookies, de modo que la cookie no aporta ninguna protección adicional
  frente a la cabecera y sí agrega complejidad de gestión de expiración y de dominio. Además,
  `SameSite=Strict` no tiene significado fuera de un navegador. La protección equivalente en móvil
  es el almacén seguro del sistema operativo, y por eso se usa `Authorization: Bearer` con el
  refresco guardado ahí.
- La app declara una etiqueta de dispositivo al iniciar sesión, de modo que la vista de sesiones
  activas sea comprensible para el usuario.
- Se aplica fijación de certificado en el cliente móvil frente a los dominios de CONFIA.
- La rotación, la detección de reutilización y la revocación de familia son idénticas a las de la
  web. El backend no distingue el cliente salvo en el transporte de la credencial.

## Consecuencias

**Positivas:**

- El robo de un token de refresco deja de ser silencioso: la reutilización revoca la familia entera
  y genera un evento de auditoría y una notificación.
- La ventana de utilidad de un token de acceso robado es de diez minutos.
- La autenticación no depende de Redis ni de ningún tercero en su camino crítico. Redis solo aporta
  limitación de tasa y contadores de bloqueo, cuya pérdida degrada pero no impide operar.
- Un token del portal es criptográficamente inválido contra la API administrativa, según ADR-0003.
- La app móvil futura se soporta sin tocar el modelo de sesión del backend.
- El usuario puede ver y revocar sus sesiones activas por dispositivo, sin infraestructura adicional.
- La política de contraseñas alineada a NIST reduce la fricción para los encargados sin reducir la
  seguridad real.

**Negativas y costos aceptados:**

- **La revocación no es instantánea para el token de acceso.** Existe una ventana de hasta diez
  minutos en la que un token ya revocado sigue permitiendo lecturas. Se acepta para lecturas y se
  cierra para escrituras financieras y de configuración, donde la política de autorización de escritura
  comprueba la vigencia de la familia (`sid`) contra la base de datos antes de ejecutar. Ese cheque agrega una consulta indexada
  a operaciones que ya son transaccionales y de baja frecuencia.
- Un cambio de permisos de un usuario tarda hasta diez minutos en propagarse, salvo que se revoque
  su sesión de forma explícita.
- Es código de seguridad propio, con casos límite reales: ventana de gracia, refresco concurrente
  desde dos pestañas, reloj desfasado en TOTP. Exige pruebas dedicadas y cuidadosas.
- La tabla de tokens de refresco crece con cada refresco de cada usuario. Requiere un trabajo de
  purga y vigilancia de tamaño.
- `SameSite=Strict` rompe el flujo en que un usuario llega desde un enlace externo y espera estar
  autenticado. Para el portal, el retorno desde la pasarela de pago se resuelve con una página
  intermedia del propio origen que recupera el estado, no relajando la cookie a `Lax`.
- Argon2id con 19 MiB por verificación impone un límite práctico de verificaciones concurrentes que
  hay que dimensionar y monitorear.
- Dos pares de claves de firma que rotar, custodiar y versionar, uno por dominio de identidad.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Un cliente con red inestable dispara falsos positivos de reutilización y expulsa usuarios legítimos | Ventana de gracia de 10 segundos ligada al mismo dispositivo, más prueba automatizada que simula una respuesta de refresco perdida y afirma que la sesión sobrevive. |
| El token de refresco se filtra por registro en logs | Redacción por lista de campos en el logger, más prueba que ejecuta un inicio de sesión y un refresco y afirma que ninguna línea de log contiene el valor del token ni la cabecera `Authorization`. |
| Confusión de claves entre dominios de identidad | Claves cargadas desde variables de entorno distintas, verificadas al arranque. Prueba que firma con la clave del portal y afirma rechazo por firma inválida en la API administrativa, y viceversa (heredada de ADR-0003). |
| Enumeración de usuarios por diferencia de tiempo de respuesta | Verificación Argon2id contra hash señuelo en la rama de usuario inexistente, más prueba estadística que compara la distribución de latencias de ambas ramas y falla si la diferencia de medianas excede un umbral. |
| Un endpoint sensible se despliega sin la exigencia de MFA | Prueba que recorre el mapa de rutas registradas y falla si alguna ruta marcada como escritura financiera o de configuración no declara la política de autorización que exige MFA. La lista de rutas exentas es una instantánea aprobada. |
| Un token de acceso revocado sigue autorizando una escritura financiera | Política de autorización de escritura financiera, aplicada con Spring Security, que valida `sid` contra `refresh_token_families.revoked_at`. Prueba de integración que revoca una familia y afirma que la escritura siguiente falla aunque el token de acceso no haya expirado. |
| El secreto TOTP se almacena en claro por un error de mapeo | Prueba de integración que inscribe MFA y consulta la fila directamente en PostgreSQL, afirmando que el valor almacenado no coincide con el secreto original. |
| Crecimiento sin límite de la tabla de tokens de refresco | Trabajo programado en segundo plano que purga tokens expirados y familias revocadas con más de 30 días, más alerta por tamaño de tabla. El mecanismo de trabajos está pendiente de ADR específico (ver ADR-0013). |
| Los parámetros de Argon2id quedan obsoletos con el tiempo | Parámetros almacenados junto al hash y rehasheo transparente en el siguiente inicio de sesión correcto cuando la configuración vigente es más fuerte que la registrada. |
| Reloj desfasado en la estación de caja invalida los códigos TOTP | Tolerancia de un paso de 30 segundos, mensaje de error que sugiere revisar la hora del dispositivo, y monitoreo de la tasa de fallos de TOTP por estación. |

## Cumplimiento y verificación

Todas las verificaciones corren en integración continua. Las de base de datos usan PostgreSQL real
mediante Testcontainers, según ADR-0008.

1. **Prueba de rotación y reutilización.** Se inicia sesión, se refresca una vez y se reintenta con
   el token ya consumido fuera de la ventana de gracia. La prueba afirma que la respuesta es de
   error, que la familia queda con `revoked_reason = 'reuse_detected'`, que ningún token de la
   familia sigue vigente y que se escribió el evento de auditoría correspondiente.
2. **Prueba de ventana de gracia.** El mismo token se presenta dos veces dentro de 10 segundos desde
   el mismo dispositivo. La prueba afirma que la segunda respuesta devuelve el mismo par emitido y
   que la familia no se revoca.
3. **Prueba de vida del token de acceso.** Prueba que decodifica el token emitido y falla si
   `exp - iat` difiere de 600 segundos, y si `aud` no coincide con el proceso emisor.
4. **Prueba de rechazo cruzado de tokens** entre dominios de identidad, heredada de ADR-0003,
   verificando que el rechazo es por firma inválida y no por rol insuficiente.
5. **Prueba de almacenamiento de credenciales.** Prueba de integración que crea un usuario y
   consulta la fila en PostgreSQL, afirmando que el campo de contraseña comienza con el prefijo
   `$argon2id$` y declara `m=19456,t=2,p=1`, y que el token de refresco almacenado no coincide con
   el valor entregado al cliente.
6. **Prueba de política de contraseñas.** Casos obligatorios: contraseña por debajo del mínimo,
   contraseña presente en la lista de filtradas, contraseña que contiene el correo del usuario, y
   contraseña larga con espacios y emoji que debe aceptarse. Prueba adicional que afirma que no
   existe ninguna validación de composición forzada ni campo de expiración de contraseña en el
   esquema.
7. **Prueba de no enumeración.** Se ejecutan inicio de sesión y recuperación con una cuenta
   existente y con una inexistente, y se afirma igualdad exacta de cuerpo, código de estado y
   cabeceras. Prueba de latencia que ejecuta ambas ramas un número suficiente de veces y falla si la
   diferencia de medianas supera el umbral acordado.
8. **Prueba de retroceso exponencial.** Se ejecutan intentos fallidos consecutivos y se afirma que
   los retrasos siguen la progresión declarada y que un inicio de sesión correcto reinicia el
   contador.
9. **Cobertura de MFA por ruta.** Prueba que inspecciona el mapa de rutas del proceso administrativo
   y falla si una ruta clasificada como escritura financiera o de configuración carece de la
   política de autorización que exige MFA. La lista de exenciones es una instantánea que hay que actualizar de forma explícita.
10. **Prueba de recuperación de contraseña.** Se afirma que el token es de un solo uso, que expira a
    los 30 minutos, que su consumo revoca todas las familias del usuario y que un segundo uso falla.
11. **Prueba de atributos de cookie.** Prueba de extremo a extremo con Playwright que verifica en
    ambos orígenes que la cookie de refresco declara `HttpOnly`, `Secure`, `SameSite=Strict` y el
    `Path` esperado, y que su valor no es legible desde `document.cookie`.
12. **Prueba de redacción en logs.** Se ejecuta un flujo completo de inicio de sesión, refresco y
    cierre de sesión capturando la salida del logger, y se afirma que no aparecen la contraseña, el
    token de acceso, el token de refresco, el secreto TOTP ni la cabecera `Authorization`.
13. **Regla de dependencia.** Reglas de ArchUnit prohíben que el dominio de encargados de
    `modules/identity` sea usado desde módulos administrativos y viceversa, y prohíben que
    cualquier módulo fuera de `modules/identity` y `shared/security` use utilidades de firma o de
    hash. Un fallo rompe la construcción. Ver ADR-0002.
14. **Verificación de arranque.** Cada proceso comprueba que su par de claves está presente, que la
    clave privada del otro dominio no está montada en su entorno, y aborta si alguna condición falla.

## Referencias

- `docs/01-arquitectura.md`, secciones 1, 3.1 y 5
- `CLAUDE.md`, sección Seguridad, reglas 9 a 14
- `docs/03-seguridad.md`
- `docs/08-datos-privacidad-y-retencion.md`
- ADR-0001: stack tecnológico (reemplazado por ADR-0013)
- ADR-0003: separación entre administración y portal
- ADR-0008: estrategia de pruebas
- ADR-0009: multitenencia
- ADR-0012: contenerización
- ADR-0013: backend en Java con Spring Boot

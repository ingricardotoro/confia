# Delta para Identidad

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `session-tokens-and-web-layer` (F0, cambio 7, parte 4b, primer cambio S1 de tres)
- **Propuesta:** `proposal.md`, con las once decisiones del propietario del 2026-10-06 como vinculantes
- **Cortes que cubre:** C4a, C4b (la ruta de la sesión actual), C5a, C5b y C7a

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `I46` en adelante, a continuación de los 45 que dejó
  `password-recovery-token`. `I24` conserva el número con el que `docs/09-roadmap-y-fases.md` ya cita la
  prueba de revocación al restablecer, y `I23` el del escenario que esa prueba invierte.
- Los identificadores `session-endpoints-cookie-and-csrf` (S2) y `password-recovery-endpoints` (S3) son
  los propuestos en la propuesta; el propietario puede cambiarlos al aprobar, y entonces se reemplazan
  en todo este delta.
- Un escenario marcado «(solo documental)» no tiene aserción automática: se verifica por lectura del
  documento citado.
- Todo requisito de este delta describe comportamiento observable; los nombres de clases y de paquetes
  aparecen solo cuando una especificación vigente ya los usa.
- **Revisión del 2026-10-06 (diseño y decisiones D-N1 y D-N2 del propietario).** Los escenarios añadidos
  en la revisión se numeran `I138` en adelante. Ningún número existente cambió. En el requisito de la
  rotación, `GraceReplayed` abrevia el desenlace `Rotated` de tipo `grace` y `ReuseDetected` abrevia el
  desenlace `Rejected` con motivo `reuse_detected`.

## ADDED Requirements

### Requisito: El token de acceso es un JWS compacto firmado con EdDSA, con cabecera cerrada

El sistema DEBE emitir el token de acceso del dominio de personal como un JWS en serialización
compacta, firmado con Ed25519 (`EdDSA`) mediante la implementación de firma del JDK y sin dependencia
de terceros. La cabecera protegida DEBE contener exactamente dos miembros: `alg` con el valor `EdDSA` y
`kid` con el identificador de la clave que firmó. La cabecera DEBE ser exactamente la que el sistema
emite para ese `kid`, `{"alg":"EdDSA","kid":"<kid>"}`, con los miembros en ese orden y sin espacios; el
sistema NO DEBE aceptar en la verificación ningún otro algoritmo, ningún otro miembro de cabecera ni otra
serialización de la misma cabecera, aunque la firma sea válida.

#### Escenario: [I46] La primitiva de firma verifica el vector de RFC 8037 y firma con claves generadas

- **DADO** la clave pública Ed25519, la entrada de firma y la firma publicadas en el Apéndice A.4 de RFC
  8037 (sin la clave privada, que no se versiona), y un par Ed25519 generado al ejecutar la prueba
- **CUANDO** se verifica la firma publicada con la clave pública del apéndice, y se firma una entrada con
  la clave generada
- **ENTONCES** la verificación de la firma publicada tiene éxito y la de esa firma con un solo bit
  alterado falla
- **Y** la firma generada se verifica con su clave pública, es determinista (dos firmas de la misma
  entrada son idénticas) y no se verifica con la clave pública del apéndice

#### Escenario: [I47] Un token emitido se verifica y su cabecera tiene solo `alg` y `kid`

- **DADO** una clave de firma con `kid` `admin-2026a` en el anillo de claves
- **CUANDO** se emite un token de acceso y se decodifica su cabecera sin verificarla
- **ENTONCES** la cabecera contiene exactamente `alg` con `EdDSA` y `kid` con `admin-2026a`, y ningún
  otro miembro
- **Y** el verificador acepta el token

#### Escenario: [I48] Confusión de algoritmo: `none`, `HS256`, `RS256`, `ES256` y variantes de `EdDSA`

- **DADO** un token con las claims correctas y el `kid` de la clave vigente, construido una vez con
  cada uno de estos valores de `alg`: `none` (sin firma), `HS256` firmado con los bytes de la clave
  pública como secreto del HMAC, `RS256`, `ES256` y `eddsa` (minúsculas)
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los cinco se rechazan como inválidos, sin que ninguno llegue a evaluar sus claims

#### Escenario: [I49] `alg` ausente o duplicado

- **DADO** un token cuya cabecera no declara `alg`, y otro cuya cabecera declara `alg` dos veces con
  el mismo valor `EdDSA`, ambos con firma válida de la clave vigente
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** ambos se rechazan como inválidos

#### Escenario: [I50] `crit` y cualquier cabecera adicional se rechazan aunque la firma sea válida

- **DADO** seis tokens firmados con la clave vigente, cada uno con un miembro de cabecera adicional:
  `crit`, `jku`, `jwk`, `x5u`, `typ` y `b64`
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los seis se rechazan como inválidos
- **Y** un séptimo token firmado con la misma clave y solo `alg` y `kid` se acepta

#### Escenario: [I147] Una cabecera semánticamente equivalente pero con otra serialización se rechaza

- **DADO** tres tokens firmados con la clave vigente, cuyas cabeceras contienen solo `alg` con `EdDSA` y
  `kid` con el de la clave vigente, pero con los miembros en orden inverso, con un espacio tras los dos
  puntos y con `EdDSA` seguido de una secuencia de escape Unicode equivalente
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los tres se rechazan como inválidos
- **Y** el token emitido por el sistema con la misma clave se acepta

### Requisito: El `kid` es obligatorio y selecciona la única clave de verificación

El sistema DEBE exigir el miembro `kid` en la cabecera, DEBE verificar la firma únicamente con la clave
del anillo cuyo `kid` coincide, y NO DEBE probar otras claves del anillo cuando no hay coincidencia. Un
`kid` ausente, desconocido, que no sea una cadena o vacío DEBE rechazar el token como inválido.

#### Escenario: [I51] `kid` ausente

- **DADO** un token con `alg` `EdDSA`, sin `kid` y con firma válida de la clave vigente
- **CUANDO** el verificador lo procesa
- **ENTONCES** se rechaza como inválido

#### Escenario: [I52] `kid` desconocido, vacío o de tipo incorrecto

- **DADO** tres tokens firmados con la clave vigente, con `kid` igual a `no-existe`, a la cadena vacía
  y al número `7`
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los tres se rechazan como inválidos

#### Escenario: [I53] No hay recurso a otras claves del anillo

- **DADO** un anillo con dos claves, `admin-2026a` y `admin-2026b`, y un token firmado con la clave
  `admin-2026b` pero con `kid` igual a `admin-2026a`
- **CUANDO** el verificador lo procesa
- **ENTONCES** se rechaza como inválido, aunque la clave que lo firmó pertenece al anillo

#### Escenario: [I54] Firma de una clave ajena con un `kid` conocido

- **DADO** un token con el `kid` de la clave vigente, firmado con una clave Ed25519 que no pertenece al
  anillo
- **CUANDO** el verificador lo procesa
- **ENTONCES** se rechaza como inválido por firma

### Requisito: El token usa base64url estricto sin relleno y tres segmentos exactos

El sistema DEBE aceptar únicamente tokens de a lo sumo 2048 caracteres, de tres segmentos no vacíos
separados por un punto, cada uno en base64url sin relleno y en su forma canónica, cuyo cuerpo decodifique a
un objeto JSON sin nombres de miembro repetidos y cuya firma tenga exactamente 64 bytes. El cuerpo
NO DEBE analizarse antes de verificar la firma. Una firma maleable (con el escalar `S` aumentado en el
orden `L` del grupo, de modo que sigue cabiendo en 32 bytes) y una firma de longitud distinta de 64 bytes
DEBEN terminar como token inválido, sin que la excepción que lanza la primitiva de firma del JDK
escape como error interno. Todo lo demás DEBE rechazarse como inválido.

#### Escenario: [I55] Relleno, alfabeto estándar, espacios y bits sobrantes no canónicos

- **DADO** tokens con firma válida a los que se les aplica, uno por vez: relleno `=` en un segmento, un
  carácter `+` o `/` del alfabeto estándar, un espacio o un salto de línea intercalado, y un último
  carácter de segmento cuyos bits sobrantes no son cero
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los cuatro se rechazan como inválidos

#### Escenario: [I56] Número de segmentos y firma de longitud incorrecta

- **DADO** un token de dos segmentos, otro de cuatro, otro con la firma vacía, otro con una firma de
  63 bytes, otro con una firma de 65 bytes y otro de 2049 caracteres
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los seis se rechazan como inválidos

#### Escenario: [I57] Cabecera o cuerpo que no son un objeto JSON, o con miembros repetidos

- **DADO** un token cuya cabecera decodifica a un arreglo JSON, otro cuyo cuerpo decodifica a un
  número, y otro cuyo cuerpo repite el nombre de la claim `sub`, todos con firma válida
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los tres se rechazan como inválidos

#### Escenario: [I138] Una firma maleable o de 63 bytes termina como token inválido, no como error interno

- **DADO** un token emitido por el sistema al que se le sustituye la firma, una vez, por la firma
  maleable obtenida sumando el orden `L` del grupo al escalar `S` de la firma original (de modo que sigue
  teniendo 64 bytes), y otra vez por una firma de 63 bytes
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** ambos desenlaces son `Invalid`
- **Y** ninguno deja escapar una excepción del verificador, aunque la primitiva de firma del JDK lance
  una excepción en lugar de devolver `false`

### Requisito: Las claims del token de acceso son una lista cerrada, sin datos personales y sin `permissions`

El sistema DEBE emitir el token de acceso con exactamente estas claims: `iss`, `aud`, `sub`, `exp`,
`iat`, `jti`, `sid`, `tenant` y `amr`, y ninguna otra. `iss` y `aud` DEBEN valer `confia-admin`, con `aud` como cadena y no como arreglo; `sub`
DEBE ser el identificador opaco de la cuenta; `sid` el identificador de la familia de sesión; `tenant`
el identificador de la institución; `jti` DEBE ser distinto en cada token; `amr` DEBE ser un arreglo
que contiene `pwd` y, solo si se completó el segundo factor, `otp`, en ese orden. La diferencia `exp - iat` DEBE ser
de 600 segundos. El token NO DEBE contener ningún dato personal (nombre, correo, documento de
identidad ni dato de un estudiante). El token NO DEBE contener la claim `permissions` hasta el cambio 8
(decisión 11 del propietario), porque un conjunto vacío podría leerse como autoritativo.

#### Escenario: [I58] La lista de claims es cerrada

- **DADO** un token de acceso emitido para la cuenta `maria.lopez@colegio.edu.hn`
- **CUANDO** se decodifica su cuerpo
- **ENTONCES** el conjunto de nombres de claims es exactamente `iss`, `aud`, `sub`, `exp`, `iat`, `jti`,
  `sid`, `tenant` y `amr`
- **Y** `iss` y `aud` valen `confia-admin`

#### Escenario: [I59] Vida de 600 segundos y audiencia del proceso emisor (ADR-0005, prueba 3)

- **DADO** un token emitido a las 10:00:00 del 2026-10-12
- **CUANDO** se decodifica
- **ENTONCES** `exp - iat` es 600 y `exp` corresponde a las 10:10:00
- **Y** `aud` coincide con la audiencia del proceso emisor

#### Escenario: [I60] Ningún dato personal ni `permissions` en el token

- **DADO** un token emitido para `maria.lopez@colegio.edu.hn`, cuyo nombre completo, correo y
  documento de identidad son conocidos por la prueba
- **CUANDO** se decodifican cabecera y cuerpo y se busca cada uno de esos valores
- **ENTONCES** ninguno aparece, y `sub` es un identificador opaco que no contiene el correo
- **Y** el cuerpo no contiene la claim `permissions`

#### Escenario: [I61] `amr` refleja el segundo factor completado

- **DADO** una sesión iniciada solo con contraseña y otra cuyo segundo factor se completó
- **CUANDO** se emite el token de cada una
- **ENTONCES** el `amr` de la primera es `[pwd]` y el de la segunda es `[pwd, otp]`, en ese orden

#### Escenario: [I62] `jti` distinto en cada emisión

- **DADO** mil emisiones sucesivas para la misma sesión
- **CUANDO** se recolectan sus `jti`
- **ENTONCES** son mil valores distintos

### Requisito: La verificación valida emisor, audiencia, vigencia y claims declaradas

El verificador DEBE rechazar un token cuyo `iss` o cuya `aud` no sea `confia-admin`, al que le falte
cualquiera de las claims declaradas, cuyas claims tengan un tipo distinto del declarado (`exp` y `iat`
numéricos, `sub`, `sid`, `tenant` y `jti` como UUID en forma canónica, `amr` como arreglo de cadenas) o que contenga
cualquier claim no declarada, entre ellas `permissions`. Un token DEBE aceptarse en un instante
estrictamente anterior a `exp` y NO DEBE aceptarse en `exp` ni después; el verificador NO DEBE aplicar
tolerancia de reloj sobre `exp`. Un `iat` posterior en más de 60 segundos al instante de la verificación
DEBE rechazarse, y uno posterior en 60 segundos o menos DEBE aceptarse. Una diferencia `exp - iat` distinta
de 600 segundos DEBE rechazarse. El reloj DEBE inyectarse.

#### Escenario: [I63] El borde de `exp` es estricto

- **DADO** un token con `exp` igual a las 10:10:00 del 2026-10-12
- **CUANDO** se verifica a las 10:09:59 y a las 10:10:00 del mismo día
- **ENTONCES** se acepta a las 10:09:59 y se rechaza a las 10:10:00

#### Escenario: [I64] Emisor o audiencia incorrectos

- **DADO** dos tokens firmados con la clave vigente, uno con `iss` igual a `confia-portal` y otro con
  `aud` igual a `confia-portal`
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** ambos se rechazan como inválidos

#### Escenario: [I65] Falta de una claim declarada

- **DADO** nueve tokens firmados con la clave vigente, cada uno sin una de las nueve claims declaradas
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los nueve se rechazan como inválidos

#### Escenario: [I66] Tipo incorrecto de una claim

- **DADO** tokens firmados con la clave vigente con `exp` como cadena, `tenant` que no es un UUID,
  `sid` que no es un UUID, `amr` que no es un arreglo, `aud` como arreglo de un elemento
  `["confia-admin"]` y `sub` con valor nulo
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los seis se rechazan como inválidos

#### Escenario: [I67] Una claim no declarada se rechaza, incluida `permissions`

- **DADO** tres tokens firmados con la clave vigente y las nueve claims correctas, cada uno con una
  claim adicional: `permissions` con un arreglo vacío, `role` y `nbf`
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los tres se rechazan como inválidos

#### Escenario: [I139] Reglas de tiempo de emisión y de duración exacta

- **DADO** el instante de verificación 10:00:00 del 2026-10-12 y tokens firmados con la clave vigente con
  `iat` a las 10:01:00, `iat` a las 10:01:01, `exp - iat` igual a 601 segundos y `exp - iat` igual a 599
  segundos (siendo `exp` posterior al instante en los cuatro)
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** el primero se acepta
- **Y** los otros tres se rechazan como inválidos

### Requisito: Un token de otro dominio de identidad se rechaza por firma o por audiencia, nunca por permiso

El sistema NO DEBE aceptar en el proceso administrativo un token firmado con una clave que no
pertenece a su anillo ni uno cuya audiencia no sea la administrativa, aunque esté vigente en su propio
dominio (ADR-0003; ADR-0005, verificación 4). El rechazo DEBE ocurrir en la verificación del token,
antes de que se evalúe ninguna autorización.

#### Escenario: [I68] Token firmado con la clave del portal

- **DADO** un token con todas las claims correctas y `aud` igual a `confia-portal`, firmado con una
  clave Ed25519 distinta de las del anillo administrativo, con un `kid` desconocido para el anillo
- **CUANDO** el verificador administrativo lo procesa
- **ENTONCES** se rechaza como inválido por `kid` desconocido

#### Escenario: [I69] Token con el `kid` administrativo pero firmado con la clave ajena

- **DADO** el mismo token firmado con la clave ajena pero con el `kid` de la clave administrativa
  vigente
- **CUANDO** el verificador administrativo lo procesa
- **ENTONCES** se rechaza como inválido por firma, y el rechazo no consulta ningún permiso

#### Escenario: [I70] Token firmado con la clave administrativa pero con audiencia del portal

- **DADO** un token firmado con la clave administrativa vigente con `aud` igual a `confia-portal`
- **CUANDO** el verificador administrativo lo procesa
- **ENTONCES** se rechaza como inválido por audiencia

### Requisito: El resultado de la verificación es tipado y no revela la causa del rechazo

El verificador DEBE devolver un resultado cerrado con exactamente tres desenlaces: `Valid`, que lleva las
claims; `Expired`, solo para un token íntegro (firma, estructura, emisor y audiencia correctos) cuyo
`exp` ya pasó; e `Invalid`, para todo lo demás, sin ningún dato observable de la causa. La firma DEBE
comprobarse antes que la vigencia. Ninguna excepción no prevista DEBE escapar del verificador ante una
entrada mal formada, vacía o nula, ni siquiera la que lanza la primitiva de firma del JDK ante una firma
maleable o de longitud incorrecta: todo eso termina como `Invalid`, nunca como un error interno. El
verificador NO DEBE registrar el contenido del token. (Si el desenlace se entrega como valor o como un
rechazo tipado que solo distingue «vencido» de «inválido» lo decide el diseño; lo observable es el
desenlace.)

#### Escenario: [I71] Un token íntegro y vencido produce `Expired`

- **DADO** un token firmado con la clave vigente, con emisor y audiencia correctos, y `exp` igual a las
  10:10:00 del 2026-10-12
- **CUANDO** se verifica a las 10:30:00 del mismo día
- **ENTONCES** el desenlace es `Expired`

#### Escenario: [I72] Un token vencido con firma o audiencia incorrectas produce `Invalid`

- **DADO** un token vencido firmado con una clave ajena, y otro vencido con `aud` igual a
  `confia-portal` firmado con la clave vigente
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** ambos desenlaces son `Invalid`, nunca `Expired`

#### Escenario: [I73] Entradas basura terminan como token inválido, sin excepción no prevista

- **DADO** como token una cadena vacía, `null`, una cadena de diez mil caracteres `A`, una cadena con
  bytes nulos y un token con un segmento de megabytes
- **CUANDO** el verificador procesa cada una
- **ENTONCES** todas producen `Invalid` y ninguna deja escapar una excepción no prevista

### Requisito: El token restringido de MFA es de otro tipo, de vida corta y sin familia

El sistema DEBE poder emitir y verificar un token restringido de MFA, cuya vida DEBE ser exactamente de
300 segundos (`exp - iat`). Sus claims DEBEN ser exactamente `iss`, `aud`, `sub`, `exp`, `iat`, `jti`,
`tenant`, `amr` y `purpose`, cuyo valor DEBE ser `mfa-verify` o `mfa-enroll`; `iss` DEBE valer
`confia-admin` y `aud` DEBE valer `confia-admin-mfa`, una audiencia distinta de la del token de acceso;
`amr` DEBE ser `[pwd]` y NO DEBE existir `sid`. Emitirlo NO DEBE crear ninguna familia ni ningún token de
refresco. La verificación del token restringido NO DEBE aceptar un token de acceso, y la del token de
acceso NO DEBE aceptar uno restringido; el rechazo del restringido por el verificador de acceso DEBE
producirse por su audiencia, sin depender de que alguien consulte `purpose`. Ningún endpoint lo entrega
en este cambio (los endpoints son de `session-endpoints-cookie-and-csrf`).

#### Escenario: [I74] Claims y vida del token restringido

- **DADO** un token restringido emitido a las 10:00:00 del 2026-10-12 con uso `mfa-verify`
- **CUANDO** se decodifica
- **ENTONCES** sus claims son exactamente las declaradas, `amr` es `[pwd]`, `aud` es
  `confia-admin-mfa`, no hay `sid`, y `exp - iat` es exactamente 300 segundos

#### Escenario: [I75] Emitir un token restringido no crea ninguna familia

- **DADO** el almacén de familias y de tokens de refresco con un número conocido de filas
- **CUANDO** se emiten diez tokens restringidos
- **ENTONCES** el número de filas de familias y de tokens de refresco no cambió

#### Escenario: [I76] Los dos tipos de token no son intercambiables

- **DADO** un token de acceso válido y un token restringido válido
- **CUANDO** cada uno se presenta a la verificación del otro tipo
- **ENTONCES** ambos desenlaces son `Invalid`
- **Y** el token restringido presentado a la verificación del token de acceso se rechaza por su
  audiencia `confia-admin-mfa`, aunque su firma y sus claims sean íntegros

#### Escenario: [I77] El uso del token restringido pertenece a un conjunto cerrado

- **DADO** un token restringido firmado con la clave vigente cuyo uso es `admin` y otro cuyo uso falta
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** ambos desenlaces son `Invalid`

### Requisito: El anillo de claves tiene una o dos claves con `kid` y se carga desde el entorno propio del dominio

El sistema DEBE cargar el anillo de claves administrativo desde variables de entorno propias del
dominio administrativo. El anillo DEBE contener una o dos claves Ed25519, cada una con un `kid` único;
exactamente una (la vigente) DEBE ser la clave de firma, con su par de clave privada y pública, y la otra
(la anterior), si existe, solo verifica y NUNCA lleva clave privada (rotación semestral de
`docs/03-seguridad.md` §4.5). El `kid` DEBE tener entre 1 y 64 caracteres de `A-Z`, `a-z`, `0-9`, `.`, `_`
y `-`. El arranque DEBE fallar si falta la clave vigente (anillo vacío), si el `kid` no tiene ese
formato, si el `kid` o la clave pública de la anterior repiten los de la vigente, si falta la clave
pública de la anterior declarada, si un valor no es una clave Ed25519 válida (por ejemplo, una clave
Ed448) o si la clave pública vigente no corresponde a su clave privada. El mensaje de error DEBE nombrar
la variable y NO DEBE contener ningún material de clave ni la causa encadenada de la excepción de
decodificación.

#### Escenario: [I78] El token se firma con la clave de firma y verifica con cualquiera de las dos

- **DADO** un anillo con `admin-2026a` como clave de firma y `admin-2026b` como clave de solo
  verificación, y un token firmado antes con `admin-2026b`
- **CUANDO** se emite un token nuevo y se verifican los dos
- **ENTONCES** el token nuevo lleva `kid` `admin-2026a`
- **Y** los dos tokens se aceptan

#### Escenario: [I79] La rotación retira la clave anterior

- **DADO** el anillo del escenario anterior, después de retirar `admin-2026b`
- **CUANDO** se presenta el token firmado con `admin-2026b`
- **ENTONCES** se rechaza como inválido por `kid` desconocido

#### Escenario: [I80] El arranque falla con un anillo mal formado

- **DADO** configuraciones sin clave vigente, con un `kid` de formato inválido, con una anterior de
  igual `kid` que la vigente, con una anterior de igual clave pública que la vigente, con una anterior
  sin clave pública, con una clave privada vigente que es Ed448 en lugar de Ed25519, con un Base64
  inválido y con una clave pública que no corresponde a la privada vigente
- **CUANDO** arranca el proceso administrativo con cada una
- **ENTONCES** las ocho fallan el arranque con un mensaje que nombra la variable
- **Y** ningún mensaje contiene material de clave, verificado por inspección del texto producido

### Requisito: Cada proceso verifica sus claves al arrancar y aborta si falta la suya o sobra la del otro dominio

La verificación DEBE ocurrir antes de crear el contexto de la aplicación y DEBE cumplir esta tabla
(ADR-0005, verificación 14):

| Proceso | Comprobación |
|---|---|
| administrativo | su clave privada de firma está presente y es válida (se valida al construir el anillo) |
| administrativo | ninguna propiedad `confia.security.portal-signing.*.private-key` está presente: el nombre se reserva desde ahora para la clave privada del portal (decisión D-N2 del propietario) |
| portal y trabajador | ni `confia.security.admin-signing.current.private-key` ni `confia.security.admin-signing.previous.private-key` están presentes (decisión 9 del propietario) |

La presencia DEBE detectarse venga de una variable de entorno, de una propiedad del sistema o de un
argumento del proceso, con el enlace relajado de nombres de Spring. En este cambio el portal y el
trabajador no cargan ningún par de claves propio, y el nombre reservado del portal no carga ninguna clave:
solo hace fallar al proceso administrativo si aparece. El mensaje de aborto DEBE nombrar la propiedad y NO
DEBE contener el valor.

#### Escenario: [I81] Administración sin clave no arranca

- **DADO** el proceso administrativo arrancado por su camino de producción sin la variable de su clave
  privada
- **CUANDO** se construye su contexto
- **ENTONCES** el arranque aborta con un mensaje que nombra la variable

#### Escenario: [I82] Administración con clave arranca

- **DADO** el proceso administrativo con una clave privada válida generada por la prueba
- **CUANDO** se construye su contexto
- **ENTONCES** el contexto arranca y el anillo contiene su clave

#### Escenario: [I83] El portal y el trabajador abortan si encuentran la clave privada administrativa

- **DADO** los procesos del portal y del trabajador, cada uno con, por separado, la propiedad de la clave
  privada administrativa vigente y la de la clave privada administrativa anterior presentes en su entorno
- **CUANDO** se construye cada contexto
- **ENTONCES** los cuatro casos abortan el arranque con un mensaje que nombra la propiedad y no contiene
  su valor

#### Escenario: [I84] El portal y el trabajador arrancan sin la clave administrativa

- **DADO** los procesos del portal y del trabajador con el entorno por omisión
- **CUANDO** se construye cada contexto
- **ENTONCES** los dos arrancan, y ninguno contiene un anillo de claves ni un verificador de tokens

#### Escenario: [I140] Administración no arranca si aparece el nombre reservado de la clave privada del portal

- **DADO** el proceso administrativo con una clave de firma válida y, además, una propiedad
  `confia.security.portal-signing.current.private-key` presente en su entorno, y otra ejecución con
  `confia.security.portal-signing.previous.private-key`
- **CUANDO** arranca cada una
- **ENTONCES** las dos abortan antes de crear el contexto, con un mensaje que nombra la propiedad y no
  contiene su valor
- **Y** sin esas propiedades el proceso administrativo arranca, y ninguna clave se carga a partir del
  nombre reservado

### Requisito: Ningún secreto de sesión es observable en registros, excepciones, `toString()` ni pruebas

El sistema NO DEBE hacer observable, en ningún mensaje de registro, mensaje de excepción,
representación textual (`toString()` o equivalente, incluida la generada por un `record` de Java) ni
salida de una prueba fallida, el token de acceso, el token de refresco en claro, el material de una
clave de firma ni el sucesor de un token de refresco, en claro o cifrado. Todo objeto de valor que
cargue alguno de ellos DEBE ser una clase final con un `toString()` sobrescrito de forma explícita.

#### Escenario: [I85] Ni los tokens ni las claves ni el sucesor aparecen en ningún texto producido

- **DADO** una emisión de familia, una rotación, un reintento en gracia y una reutilización detectada
  para `maria.lopez@colegio.edu.hn`, con captura de todos los registros
- **CUANDO** el sistema procesa cada operación y, en cualquier punto, produce un mensaje de registro,
  lanza una excepción o invoca `toString()` sobre un objeto que carga alguno de los secretos
- **ENTONCES** ninguno de esos textos contiene el token de acceso, el refresco en claro, el sucesor en
  claro o cifrado ni la clave privada de firma

#### Escenario: [I86] La prueba de redacción detecta una fuga reintroducida

- **DADO** la prueba del escenario anterior
- **CUANDO** se ejecuta contra una variante de control en la que el objeto que carga el refresco expone
  su valor en `toString()`
- **ENTONCES** la prueba falla, lo que demuestra que alcanza el caso peligroso

### Requisito: La emisión de una familia de refresco crea la familia y su primer token en una transacción con su auditoría

El sistema DEBE ofrecer un caso de uso de emisión que, para una cuenta de personal y una institución,
cree una familia de sesión y su primer token de refresco dentro de una misma transacción, junto con el
asiento de auditoría de la emisión. La familia DEBE registrar la institución, la cuenta, la huella del
agente de usuario, la IP inicial, el instante de creación, `last_used_at` igual al de creación y
`absolute_expires_at` igual a creación más 12 horas, sin revocación. El token de refresco DEBE ser
opaco, de 32 bytes aleatorios de un generador criptográfico codificados en base64url sin relleno (43
caracteres), DEBE entregarse al llamador una única vez y DEBE almacenarse únicamente como su SHA-256; su
vencimiento DEBE ser el menor entre la emisión más 8 horas y el vencimiento absoluto de la familia. Si la
transacción no confirma, ni la familia, ni el token, ni el asiento DEBEN sobrevivir. En este cambio solo
el código de pruebas invoca la emisión; el endpoint que la usa es de `session-endpoints-cookie-and-csrf`.

#### Escenario: [I87] Los valores de la emisión

- **DADO** la cuenta `maria.lopez@colegio.edu.hn` y una emisión a las 08:00:00 del 2026-10-12
- **CUANDO** el caso de uso termina
- **ENTONCES** existe una familia con `created_at` y `last_used_at` iguales a las 08:00:00,
  `absolute_expires_at` igual a las 20:00:00 y sin revocación
- **Y** existe un token de refresco con `expires_at` igual a las 16:00:00 y sin consumir
- **Y** el llamador recibió el refresco una única vez, de 43 caracteres base64url sin relleno que
  decodifican a 32 bytes

#### Escenario: [I88] El valor almacenado no es el entregado (ADR-0005, prueba 5)

- **DADO** el refresco entregado por la emisión del escenario anterior
- **CUANDO** se consulta con SQL crudo la tabla de tokens de refresco
- **ENTONCES** la fila contiene el SHA-256 del refresco entregado y ninguna columna de ninguna fila
  contiene el refresco en claro
- **Y** dos emisiones sucesivas entregan refrescos distintos

#### Escenario: [I89] La emisión y su asiento se confirman o revierten juntos

- **DADO** una emisión procesada dentro de una transacción que a continuación falla de forma
  determinista antes de confirmar
- **CUANDO** se inspecciona el estado tras la reversión
- **ENTONCES** no existe la familia, ni el token, ni el asiento de auditoría de esa emisión

#### Escenario: [I90] El asiento de emisión existe y no contiene secretos

- **DADO** una emisión confirmada
- **CUANDO** se lee el asiento de auditoría que produjo
- **ENTONCES** existe exactamente un asiento de emisión con la familia como entidad
- **Y** ningún campo del asiento contiene el refresco ni su SHA-256

### Requisito: La rotación consume el token con bloqueo de la familia y devuelve un desenlace tipado

El sistema DEBE ofrecer un caso de uso de rotación que, ante un token de refresco presentado, bloquee la
fila de su familia y devuelva un resultado cerrado con exactamente dos desenlaces: `Rotated`, que lleva la
sesión emitida (el token de refresco sucesor, su vencimiento y un token de acceso nuevo de la misma
familia) y un tipo, `fresh` o `grace`; y `Rejected`, con un motivo cerrado: `unknown_token`,
`family_revoked`, `family_expired` (por vencimiento absoluto o por inactividad), `token_expired` o
`reuse_detected`. El caso de uso NO DEBE lanzar una excepción por ninguno de ellos. El orden de
evaluación DEBE ser: familia revocada; familia vencida (absoluta o por inactividad); token ya consumido
(gracia o reutilización); vencimiento propio del token; rotación normal. Por tanto, un token consumido
presentado a una familia vencida produce `family_expired` y no `reuse_detected`. En una rotación normal
(`fresh`) DEBE marcar el token presentado como consumido con el instante y con la huella del agente de
usuario de quien lo presentó, emitir un token nuevo en la misma familia enlazado como sucesor, con
vencimiento igual al menor entre el instante más 8 horas y el vencimiento absoluto, avanzar
`last_used_at` al instante de la rotación y escribir el asiento de la rotación, todo en una transacción.
Un texto que no puede ser un token emitido y un token desconocido DEBEN producir `Rejected` con motivo
`unknown_token` sin alterar ninguna familia. Un token de otra institución NO DEBE ser visible para la
sesión de esta. Dos rotaciones simultáneas del mismo token DEBEN serializarse por el bloqueo de la familia. En esta especificación, `GraceReplayed` abrevia `Rotated` de tipo `grace` y `ReuseDetected`
abrevia `Rejected` con motivo `reuse_detected`.

#### Escenario: [I91] Rotación normal

- **DADO** el token `RT-1001` de una familia sin revocar, emitido a las 09:00:00 del 2026-10-12
- **CUANDO** se presenta a las 09:15:00 del mismo día
- **ENTONCES** el desenlace es `Rotated` de tipo `fresh` con un token nuevo `RT-1002` de la misma familia
  y un token de acceso nuevo cuyo `sid` es esa familia
- **Y** `RT-1001` queda consumido a las 09:15:00 y enlazado a `RT-1002` como su sucesor
- **Y** `last_used_at` de la familia es las 09:15:00 y el asiento de rotación existe

#### Escenario: [I92] Token desconocido

- **DADO** un valor de refresco que nunca se emitió, y un texto que no puede ser un token emitido (vacío,
  de longitud distinta de 43 o con caracteres fuera de base64url)
- **CUANDO** se presenta cada uno a la rotación
- **ENTONCES** el desenlace es `Rejected` con motivo `unknown_token` en los dos casos
- **Y** ninguna familia ni ningún token cambió

#### Escenario: [I93] Token de una familia revocada

- **DADO** el token vigente `RT-2001` de una familia ya revocada con el motivo `password_change`
- **CUANDO** se presenta a la rotación
- **ENTONCES** el desenlace es `Rejected` con motivo `family_revoked` y el estado de la familia no cambia

#### Escenario: [I94] El token de otra institución no se ve

- **DADO** el token vigente `RT-3001` de una familia de la institución A
- **CUANDO** se presenta a la rotación con el contexto de sesión de la institución B
- **ENTONCES** el desenlace es `Rejected` con motivo `unknown_token`, igual que un token inexistente

#### Escenario: [I95] Dos rotaciones concurrentes del mismo token producen exactamente un sucesor

- **DADO** el token vigente `RT-4001`, y dos transacciones reales y sincronizadas con
  `CyclicBarrier` que lo presentan en el mismo instante del reloj inyectado, con el mismo agente de
  usuario
- **CUANDO** terminan las dos
- **ENTONCES** existe exactamente un token sucesor de `RT-4001`
- **Y** un desenlace es `Rotated` de tipo `fresh` y el otro `Rotated` de tipo `grace`, con el mismo valor
  de sucesor

#### Escenario: [I143] Dos presentaciones simultáneas del mismo token desde dispositivos distintos

- **DADO** el token vigente `RT-4101`, y dos transacciones reales y sincronizadas con `CyclicBarrier`
  que lo presentan en el mismo instante del reloj inyectado, con huellas de agente de usuario distintas
- **CUANDO** terminan las dos
- **ENTONCES** un desenlace es `Rotated` de tipo `fresh` y el otro es `ReuseDetected`
- **Y** la familia queda revocada con `reuse_detected` y ningún token de la familia está vigente

### Requisito: La ventana de gracia de 10 segundos devuelve el sucesor cifrado al mismo dispositivo

Cuando un token ya consumido se presenta de nuevo estrictamente antes de que transcurran 10 segundos
desde su consumo, desde el mismo dispositivo (la misma huella de agente de usuario que presentó el
consumo del token, no la del inicio de sesión: una actualización del navegador durante la sesión no
convierte un reintento legítimo en reutilización), con la familia viva y sin que la familia haya
avanzado, el sistema DEBE devolver el mismo token de refresco sucesor que se emitió, junto con un token
de acceso recién emitido (otro `jti` e `iat`, y los mismos `sid`, `sub`, `tenant` y `amr`), sin revocar la
familia y avanzando `last_used_at` al instante del reintento (ADR-0005, punto 4 y verificación 2; decisión
D-N1 del propietario, que precisa «el mismo par» en ese sentido). El token de acceso del primer intento no
se conserva. Como solo se conserva el SHA-256 de cada token, el sistema DEBE guardar el sucesor cifrado
con el servicio de cifrado de columna (los datos adicionales autenticados ligan el valor a su propia
fila) y NO DEBE descifrarlo a los 10 segundos ni después, aunque el valor siga en la base de datos
(decisión 6 del propietario). El sucesor cifrado DEBE vaciarse en el siguiente uso de la familia: en la
rotación de cualquiera de sus tokens (los consumidos hace 10 segundos o más), en toda revocación y en el
rechazo por familia vencida; la purga del resto es del cambio 9. La familia «ha avanzado» cuando el
sucesor ya fue consumido. Un agente de usuario ausente en ambas presentaciones se trata como el mismo
dispositivo.

#### Escenario: [I96] Reintento dentro de la gracia desde el mismo dispositivo (ADR-0005, prueba 2)

- **DADO** el token `RT-5001` rotado a las 10:00:00 del 2026-10-12 por una petición con el agente
  `agente-prueba`, que produjo el sucesor `RT-5002`
- **CUANDO** `RT-5001` se presenta de nuevo a las 10:00:09 con el agente `agente-prueba`
- **ENTONCES** el desenlace es `GraceReplayed` con el mismo valor de `RT-5002` entregado antes
- **Y** la familia no se revoca, ningún token de refresco adicional se emite y `last_used_at` de la
  familia es las 10:00:09

#### Escenario: [I141] La gracia devuelve el mismo refresco sucesor y un token de acceso nuevo (decisión D-N1)

- **DADO** el estado del escenario anterior, con el token de acceso `AT-1` entregado en la rotación de
  las 10:00:00
- **CUANDO** `RT-5001` se presenta a las 10:00:09 con el agente `agente-prueba`
- **ENTONCES** el refresco devuelto es idéntico al `RT-5002` entregado antes
- **Y** el token de acceso devuelto es distinto de `AT-1`, con otro `jti` y otro `iat`, y con los mismos
  `sid`, `sub`, `tenant` y `amr`
- **Y** el token de acceso devuelto se verifica como válido y su `exp - iat` es 600 segundos

#### Escenario: [I97] El borde de 10 segundos es estricto

- **DADO** el mismo estado del escenario anterior
- **CUANDO** `RT-5001` se presenta a las 10:00:09,999 y, en otra ejecución, exactamente a las 10:00:10
- **ENTONCES** la primera es `GraceReplayed` y la segunda es `ReuseDetected`

#### Escenario: [I98] Otro dispositivo dentro de la ventana es reutilización

- **DADO** el mismo estado, con una huella de agente distinta en la presentación
- **CUANDO** `RT-5001` se presenta a las 10:00:05
- **ENTONCES** el desenlace es `ReuseDetected` y la familia se revoca

#### Escenario: [I99] El sucesor se guarda cifrado y ligado a su fila

- **DADO** una rotación confirmada
- **CUANDO** se consulta con SQL crudo el valor del sucesor del token consumido
- **ENTONCES** comienza con el prefijo `v1:` y no contiene el sucesor en claro
- **Y** descifrarlo con los datos adicionales autenticados de otra fila falla por fallo de autenticación

#### Escenario: [I100] Pasados los 10 segundos el valor sigue en la base y no se descifra

- **DADO** el mismo estado de `RT-5001`, sin ningún contacto posterior con la familia
- **CUANDO** se presenta a las 10:00:11 y se consulta la fila
- **ENTONCES** el desenlace es `ReuseDetected` sin que el sucesor se haya descifrado
- **Y** el valor cifrado siguió en la fila hasta ese contacto, como limitación declarada hasta la purga
  del cambio 9

#### Escenario: [I101] El sucesor cifrado se vacía en el siguiente uso de la familia

- **DADO** el token `RT-5001` consumido a las 10:00:00 con su sucesor cifrado y `RT-5002` vigente
- **CUANDO** `RT-5002` se rota a las 10:00:30, y en otra ejecución la familia se revoca a las 10:00:30
- **ENTONCES** en ambos casos el valor cifrado del sucesor de `RT-5001` queda vacío

#### Escenario: [I102] Si la familia ya avanzó no hay reintento en gracia

- **DADO** `RT-5001` rotado a las 10:00:00 y su sucesor `RT-5002` rotado a las 10:00:03
- **CUANDO** `RT-5001` se presenta a las 10:00:06 con el mismo agente
- **ENTONCES** el desenlace es `ReuseDetected`, porque el sucesor ya fue consumido y no hay valor que
  devolver

#### Escenario: [I103] Sin agente de usuario en ambas presentaciones

- **DADO** un token rotado sin agente de usuario (la huella registrada en su consumo es la de «sin
  agente»)
- **CUANDO** el token consumido se presenta de nuevo dentro de la ventana, también sin agente
- **ENTONCES** el desenlace es `GraceReplayed`

### Requisito: La reutilización fuera de la gracia revoca la familia, audita y se devuelve como desenlace

Cuando un token consumido se presenta fuera de la ventana de gracia, desde otro dispositivo o con la
familia ya avanzada, el sistema DEBE revocar la familia con el motivo `reuse_detected`, dejar sin
ningún token vigente a todos los de esa familia, escribir un asiento de auditoría que lo registre como
posible fuga de token, y devolver el desenlace `ReuseDetected`. La revocación y el asiento DEBEN
confirmarse aunque la petición se rechace: el caso de uso NO DEBE lanzar una excepción que los
revierta. La reutilización NO DEBE afectar a ninguna otra familia de la misma cuenta. Presentar después
un token de una familia ya revocada NO DEBE cambiar su instante ni su motivo de revocación ni escribir
un segundo asiento de reutilización. El aviso al titular no es parte de este cambio.

#### Escenario: [I104] Reutilización fuera de la gracia (ADR-0005, prueba 1)

- **DADO** `RT-6001` rotado a las 09:00:00 del 2026-10-12, que produjo `RT-6002`
- **CUANDO** `RT-6001` se presenta a las 09:20:00
- **ENTONCES** el desenlace es `ReuseDetected`
- **Y** la familia queda con `revoked_reason` igual a `reuse_detected` y `revoked_at` igual a las
  09:20:00
- **Y** ningún token de la familia sigue vigente, incluido `RT-6002`
- **Y** existe un asiento de auditoría de posible fuga de token con la familia como entidad

#### Escenario: [I105] La revocación se confirma aunque la petición se rechace

- **DADO** una reutilización detectada por un caso de uso invocado desde una capa que traduce el
  desenlace `ReuseDetected` en un rechazo
- **CUANDO** se consulta el estado desde una conexión distinta, después del rechazo
- **ENTONCES** la familia está revocada y el asiento de auditoría existe, ninguno revertido

#### Escenario: [I106] Solo se revoca la familia afectada

- **DADO** la cuenta `maria.lopez@colegio.edu.hn` con dos familias vivas, `F-1` y `F-2`
- **CUANDO** se detecta reutilización en `F-1`
- **ENTONCES** `F-1` queda revocada y `F-2` sigue viva con todos sus tokens vigentes

#### Escenario: [I107] Presentar un token de una familia ya revocada no la modifica

- **DADO** la familia del escenario [I104], ya revocada a las 09:20:00
- **CUANDO** `RT-6001` se presenta de nuevo a las 09:25:00
- **ENTONCES** el desenlace es `Rejected` con motivo `family_revoked`
- **Y** `revoked_at` sigue siendo las 09:20:00, el motivo sigue siendo `reuse_detected` y no se escribió
  un segundo asiento de reutilización

#### Escenario: [I108] Reutilización y rotación concurrentes dejan la familia revocada

- **DADO** `RT-6001` consumido fuera de la gracia y `RT-6002` vigente, y dos transacciones reales y
  sincronizadas con `CyclicBarrier`: una presenta `RT-6001` y la otra rota `RT-6002`
- **CUANDO** terminan las dos
- **ENTONCES** la familia está revocada con `reuse_detected` y ningún token de la familia está vigente,
  sea cual sea el orden en que el bloqueo serializó las dos operaciones

#### Escenario: [I144] Cada rechazo de un refresco deja un asiento de denegación sin secretos

- **DADO** cinco presentaciones: un token desconocido, un token de una familia revocada, un token de una
  familia vencida, un token propio vencido y un token consumido fuera de la gracia
- **CUANDO** se procesa cada una
- **ENTONCES** las cuatro primeras escriben un asiento de refresco rechazado con resultado de denegación
  cuyo motivo es `unknown_token`, `family_revoked`, `family_expired` y `token_expired`, y la quinta
  escribe el asiento de reutilización detectada con la familia como entidad
- **Y** el asiento del token desconocido no tiene una familia como entidad
- **Y** ningún asiento contiene el refresco, su SHA-256, el sucesor cifrado, el token de acceso, la huella
  del agente de usuario ni el `jti`

### Requisito: Las vigencias de la sesión administrativa son de 8 horas, 12 horas absolutas y 30 minutos de inactividad

El sistema DEBE aplicar estas vigencias al rotar (decisión 4 del propietario): cada token de refresco
vence en el menor entre su emisión más 8 horas y el vencimiento absoluto de su familia; la familia
vence a las 12 horas de su creación; y la familia se rechaza si han transcurrido 30 minutos o más desde
su `last_used_at`. Un token o una familia DEBEN aceptarse en un instante estrictamente anterior a su
vencimiento y NO DEBEN aceptarse en el instante exacto ni después. El rechazo por vigencia NO DEBE
revocar la familia con `reuse_detected` ni con ningún otro motivo (el vencimiento absoluto y la
inactividad se derivan de las columnas y del reloj, no se escriben), ni afectar a otra familia; sí DEBE
vaciar los sucesores cifrados de la familia vencida. El sistema NO DEBE actualizar
`last_used_at` en las peticiones autenticadas con el token de acceso, solo al rotar. El reloj DEBE
inyectarse.

#### Escenario: [I109] El borde de 30 minutos de inactividad

- **DADO** una familia con `last_used_at` igual a las 08:00:00 del 2026-10-12 y un token vigente
- **CUANDO** se rota a las 08:29:59 y, en otra ejecución, a las 08:30:00
- **ENTONCES** la primera es `Rotated` y la segunda es `Rejected` con motivo `family_expired`

#### Escenario: [I110] El vencimiento absoluto acota al token nuevo

- **DADO** una familia con `absolute_expires_at` igual a las 20:00:00 del 2026-10-12 y
  `last_used_at` igual a las 19:50:00
- **CUANDO** se rota a las 19:59:59
- **ENTONCES** el desenlace es `Rotated` y el token nuevo vence a las 20:00:00, no 8 horas después

#### Escenario: [I111] El borde de las 12 horas absolutas

- **DADO** la misma familia con `last_used_at` igual a las 19:59:00
- **CUANDO** se rota exactamente a las 20:00:00
- **ENTONCES** el desenlace es `Rejected` con motivo `family_expired`

#### Escenario: [I112] El vencimiento propio del token

- **DADO** un token vigente de una familia viva, con `expires_at` igual a las 16:00:00
- **CUANDO** se presenta a las 16:00:00
- **ENTONCES** el desenlace es `Rejected` con motivo `token_expired`

#### Escenario: [I113] El rechazo por vigencia no es reutilización

- **DADO** el rechazo del escenario [I109] y otra familia viva de la misma cuenta
- **CUANDO** se inspecciona el estado
- **ENTONCES** la familia rechazada no está revocada, no tiene `reuse_detected` como motivo y la otra
  familia no cambió

#### Escenario: [I142] Un token consumido presentado a una familia vencida es rechazo por vigencia, no reutilización

- **DADO** `RT-7001` rotado a las 08:00:00 del 2026-10-12 con el sucesor `RT-7002` sin usar, y su
  sucesor cifrado todavía en la fila
- **CUANDO** `RT-7001` se presenta a las 08:30:00 (30 minutos de inactividad), y en otra ejecución, con la
  familia de `absolute_expires_at` igual a las 08:00:30, a las 08:00:30
- **ENTONCES** ambos desenlaces son `Rejected` con motivo `family_expired`, no `reuse_detected`
- **Y** la familia no queda revocada y el sucesor cifrado de `RT-7001` queda vacío

#### Escenario: [I145] Un token consumido y vencido por sí mismo en una familia viva es reutilización

- **DADO** `RT-7101` consumido por una rotación anterior hace más de 10 segundos, cuyo `expires_at` ya
  pasó, en una familia viva
- **CUANDO** `RT-7101` se presenta
- **ENTONCES** el desenlace es `Rejected` con motivo `reuse_detected` y la familia se revoca, porque la
  evaluación del consumo precede a la del vencimiento propio del token

#### Escenario: [I114] Una petición autenticada no escribe `last_used_at`

- **DADO** una familia con `last_used_at` igual a las 09:00:00 y un token de acceso vigente de esa
  sesión
- **CUANDO** se hacen cinco peticiones autenticadas a las 09:01, 09:02, 09:03, 09:04 y 09:05
- **ENTONCES** `last_used_at` sigue siendo las 09:00:00 y no se escribió ninguna fila de V8

### Requisito: La vigencia de la sesión se consulta por el puerto `SessionValidity` y el `sid` se comprueba en las rutas de gestión de sesión y en las operaciones sensibles

El módulo de identidad DEBE implementar el puerto `SessionValidity` de `shared.security`, que recibe el
actor autenticado (cuenta, institución y sesión) y no solo el identificador de la sesión: una sesión
está activa si y solo si su familia existe en la institución del actor, pertenece a la cuenta del
actor, no está revocada, su vencimiento absoluto no ha llegado y no han transcurrido 30 minutos o más
desde su `last_used_at`. Un identificador desconocido, de otra institución o de otra cuenta DEBE producir
«no activa». La consulta NO DEBE escribir en la base de datos, ni auditar, ni actualizar `last_used_at`.
Un error de base de datos durante la consulta NO DEBE producir una sesión activa: la petición falla
cerrada con `500` y el código `internal-error`. La comprobación del `sid` contra la base de datos DEBE aplicarse a las rutas de gestión de
sesión, entre ellas la lectura de la sesión actual, y DEBE estar disponible para las operaciones
sensibles (decisión 10 del propietario); NO DEBE aplicarse a toda petición autenticada. Mientras el
token de acceso no venza, una petición a una ruta que no declara la comprobación sigue aceptándose
aunque su familia haya sido revocada (ventana de hasta diez minutos que acepta ADR-0005 para lecturas).

#### Escenario: [I115] Familia activa

- **DADO** una familia viva, sin revocar, antes de su vencimiento absoluto y con `last_used_at` de hace
  menos de 30 minutos
- **CUANDO** se consulta la vigencia con el actor de esa sesión
- **ENTONCES** la sesión está activa

#### Escenario: [I116] Familia revocada, vencida, inactiva, desconocida, de otra institución o de otra cuenta

- **DADO** una familia revocada, otra pasada su vencimiento absoluto, otra con `last_used_at` de hace
  exactamente 30 minutos, un identificador que no existe, una familia viva de la institución A consultada
  con el contexto de la institución B y una familia viva de la cuenta X consultada con un actor de la
  cuenta Y de la misma institución
- **CUANDO** se consulta la vigencia de cada una
- **ENTONCES** las seis sesiones no están activas

#### Escenario: [I117] Una ruta de gestión de sesión rechaza un token de una familia revocada

- **DADO** un token de acceso vigente cuya familia se revocó hace un minuto
- **CUANDO** se presenta a una ruta que declara la comprobación del `sid`
- **ENTONCES** la petición se rechaza con `401` y el código `token-invalid`

#### Escenario: [I118] Una ruta que no declara la comprobación acepta el token hasta su vencimiento

- **DADO** el mismo token y una ruta de prueba autenticada que no declara la comprobación del `sid`
- **CUANDO** se presenta antes de su `exp`
- **ENTONCES** la petición se acepta, y se rechaza con `token-expired` una vez vencido el token

#### Escenario: [I146] Un fallo de base de datos en la comprobación falla cerrado

- **DADO** una ruta que declara la comprobación del `sid`, un token de acceso vigente y una base de datos
  que lanza un error al consultar la familia
- **CUANDO** se presenta el token
- **ENTONCES** la respuesta es `500` con el código `internal-error`, nunca `200`, y el controlador
  registra cero invocaciones

### Requisito: La familia registra la huella del agente de usuario y la IP inicial, sin aplicarlas todavía

El sistema DEBE registrar en la familia, al emitirla, la huella del agente de usuario (un resumen, nunca
el valor en claro) y la IP inicial de quien inició la sesión; DEBE aceptar la ausencia de ambos. La
huella DEBE usarse para decidir «el mismo dispositivo» de la ventana de gracia. El sistema NO DEBE, en
este cambio, revocar ni rechazar una rotación por el cambio de agente de usuario o de IP (decisión 5
del propietario; la aplicación se reevalúa en `session-endpoints-cookie-and-csrf`).

#### Escenario: [I119] Solo se guarda la huella y la IP inicial

- **DADO** una emisión con el agente `Mozilla/5.0 (agente-prueba)` y la IP `203.0.113.9`
- **CUANDO** se consulta con SQL crudo la fila de la familia
- **ENTONCES** ninguna columna contiene el texto del agente, la huella es un resumen y la IP inicial es
  `203.0.113.9`

#### Escenario: [I120] Una emisión sin origen se acepta

- **DADO** una emisión por una prueba sin petición HTTP activa
- **CUANDO** se consulta la fila de la familia
- **ENTONCES** la IP inicial es nula y la huella corresponde a «sin agente»

#### Escenario: [I121] El cambio de agente y de IP no impide rotar un token vigente

- **DADO** una familia emitida con el agente `agente-uno` desde `203.0.113.9` y un token vigente sin
  consumir
- **CUANDO** se presenta a rotar con el agente `agente-dos` desde `198.51.100.7`, fuera de cualquier
  ventana de gracia
- **ENTONCES** el desenlace es `Rotated` y la familia no se revoca

### Requisito: El restablecimiento de contraseña revoca todas las familias vivas de la cuenta en la misma transacción

El sistema DEBE revocar, al completarse un restablecimiento de contraseña con token, todas las familias
vivas de la cuenta con el motivo `password_change`, dentro de la misma transacción que consume el token
y escribe el hash nuevo (`docs/03-seguridad.md` §4.5 y §4.7; ADR-0005, «Recuperación de contraseña»,
punto 4; condición dura H2). La revocación NO DEBE afectar a las familias de otra cuenta, NO DEBE
modificar una familia ya revocada y NO DEBE ocurrir ante ningún desenlace distinto de `Completed`. Si
la revocación falla, el restablecimiento completo DEBE revertirse. El sistema DEBE escribir, en la misma
transacción, un asiento de revocación con la cuenta como entidad y el número de familias revocadas,
también cuando ese número es cero, como evidencia de que la revocación corrió. Si el asiento falla, el
restablecimiento completo DEBE revertirse. Las familias ya revocadas conservan su motivo y su instante, y
la revocación vacía los sucesores cifrados de las familias que revoca. Una familia emitida después de
confirmar el restablecimiento NO DEBE verse afectada. Esta revocación no excluye ninguna sesión,
porque el restablecimiento ocurre sin sesión. El sistema NO DEBE emitir ningún token de refresco sin
que esta revocación exista.

#### Escenario: [I24] Un restablecimiento revoca todas las familias de la cuenta y ninguna ajena

- **DADO** la cuenta `ana.martinez@colegio.edu.hn` con tres familias vivas, la cuenta
  `carlos.ramirez@colegio.edu.hn` con dos, y el token vivo `PRT-88c1` de la primera, con PostgreSQL real
- **CUANDO** se restablece la contraseña de `ana.martinez@colegio.edu.hn` con `PRT-88c1`
- **ENTONCES** las tres familias de `ana.martinez@colegio.edu.hn` quedan revocadas con
  `password_change` y ningún token suyo sigue vigente
- **Y** las dos familias de `carlos.ramirez@colegio.edu.hn` siguen vivas
- **Y** el asiento de revocación registra tres familias revocadas

#### Escenario: [I122] Si la transacción se revierte, ninguna familia queda revocada

- **DADO** el mismo estado, con el restablecimiento procesado dentro de una transacción que falla de
  forma determinista después de consumir el token, escribir el hash y revocar, antes de confirmar
- **CUANDO** se inspecciona el estado tras la reversión
- **ENTONCES** las tres familias siguen vivas, `PRT-88c1` sigue vivo, la contraseña vigente es la
  anterior y no existe ningún asiento de revocación

#### Escenario: [I123] Si la revocación falla, el restablecimiento no ocurre

- **DADO** un puerto de revocación de prueba que lanza una excepción
- **CUANDO** se procesa un restablecimiento con un token vivo
- **ENTONCES** la contraseña vigente no cambia y el token sigue vivo

#### Escenario: [I124] Solo el desenlace `Completed` revoca

- **DADO** una cuenta con tres familias vivas y restablecimientos que terminan en `TokenRejected`,
  `PasswordRejected`, `SecondFactorMissing` y `SecondFactorRejected`
- **CUANDO** se procesa cada uno
- **ENTONCES** ninguna familia se revoca en ninguno de los cuatro casos

#### Escenario: [I125] Cuenta sin familias y familias ya revocadas

- **DADO** una cuenta sin ninguna familia, y otra con una familia revocada a las 08:00:00 por
  `reuse_detected` y otra viva
- **CUANDO** se completa un restablecimiento para cada una
- **ENTONCES** el de la primera se completa y escribe el asiento de revocación con cero familias
  revocadas
- **Y** en la segunda, la familia ya revocada conserva su instante y su motivo originales y solo la
  viva queda revocada con `password_change`

#### Escenario: [I126] Una sesión iniciada después del restablecimiento no se ve afectada

- **DADO** un restablecimiento confirmado a las 10:00:00 y una familia emitida a las 10:05:00 para la
  misma cuenta
- **CUANDO** se consulta el estado de esa familia
- **ENTONCES** sigue viva

#### Escenario: [I127] El token de acceso de una familia revocada falla la comprobación del `sid`

- **DADO** un token de acceso vigente de una familia revocada por un restablecimiento
- **CUANDO** se presenta a una ruta de gestión de sesión
- **ENTONCES** la petición se rechaza con `401` y el código `token-invalid`

#### Escenario: [I128] Restablecimiento y rotación concurrentes no dejan ningún token vivo

- **DADO** una familia viva con un token vigente, y dos transacciones reales y sincronizadas con
  `CyclicBarrier`: un restablecimiento de la cuenta y una rotación de ese token
- **CUANDO** terminan las dos
- **ENTONCES** la familia está revocada y ningún token de ninguna familia de la cuenta está vigente, sea
  cual sea el orden en que el bloqueo serializó las dos operaciones

### Requisito: La sesión actual se lee en `GET /api/v1/auth/sessions/current` sin datos personales

El sistema DEBE ofrecer la ruta `GET /api/v1/auth/sessions/current`, autenticada con un token de acceso
completo y sin permiso de rol, que devuelve un DTO explícito con una lista cerrada de campos: el
identificador de la sesión (`sessionId`), el identificador opaco del usuario (`accountId`), el
identificador de la institución (`institutionId`), los métodos de autenticación (`authenticationMethods`,
con los valores de `amr`), el instante de vencimiento del token de acceso (`accessTokenExpiresAt`), el
instante de vencimiento absoluto de la sesión (`sessionExpiresAt`) y el instante en que vencería por
inactividad (`idleExpiresAt`, `last_used_at` más 30 minutos), con fechas en ISO 8601 en UTC. La respuesta
NO DEBE contener ningún dato personal. Las respuestas posibles DEBEN ser `200`; `401` con
`authentication-required`, `token-invalid` o `token-expired`, siempre con `WWW-Authenticate`; y `500` con
`internal-error`. NO DEBE existir `403` (todo actor autenticado puede leer su propia sesión) ni `429`. La
ruta DEBE comprobar el `sid` contra la base de datos (decisión 10 del propietario) y NO DEBE escribir en
la base de datos. Esta es la única ruta de producción de este cambio, y la que `session-endpoints-cookie-and-csrf`
hereda como estilo de recursos.

#### Escenario: [I129] Token válido y sesión viva

- **DADO** un token de acceso vigente de una familia viva de `maria.lopez@colegio.edu.hn`
- **CUANDO** se envía `GET /api/v1/auth/sessions/current` con `Authorization: Bearer <token>`
- **ENTONCES** la respuesta es `200` con el DTO de la sesión, cuyo identificador de sesión es el `sid`
  del token, `authenticationMethods` coincide con el `amr` del token, y cuyos vencimientos coinciden con
  `exp`, con el vencimiento absoluto de la familia y con su `last_used_at` más 30 minutos

#### Escenario: [I130] La lista de campos es cerrada y sin datos personales

- **DADO** la respuesta del escenario anterior
- **CUANDO** se comparan sus campos con la lista cerrada de siete campos aprobada y se busca el correo,
  el nombre y el documento de la cuenta
- **ENTONCES** no existe ningún campo fuera de la lista y ninguno de esos valores aparece en el cuerpo
  ni en las cabeceras

#### Escenario: [I131] Sin credencial

- **DADO** una petición sin cabecera `Authorization`
- **CUANDO** se envía a la ruta
- **ENTONCES** la respuesta es `401` con el código `authentication-required` y la cabecera
  `WWW-Authenticate`

#### Escenario: [I132] Token vencido, alterado o restringido

- **DADO** un token íntegro y vencido, un token con un carácter de la firma alterado, y un token
  restringido de MFA vigente
- **CUANDO** se presenta cada uno a la ruta
- **ENTONCES** el primero recibe `401` con `token-expired` y los otros dos reciben `401` con
  `token-invalid`, siempre con `WWW-Authenticate` (el restringido, por su audiencia)

#### Escenario: [I133] Sesión revocada o desconocida con un token vigente

- **DADO** un token de acceso vigente cuya familia está revocada, y otro vigente cuyo `sid` no
  corresponde a ninguna familia
- **CUANDO** se presenta cada uno a la ruta
- **ENTONCES** ambos reciben `401` con `token-invalid`, y las dos respuestas son idénticas entre sí y a
  la de un token alterado, salvo `instance` y el identificador de traza

#### Escenario: [I134] La ruta es de solo lectura

- **DADO** una familia viva con `last_used_at` conocido y el recuento de asientos de auditoría
- **CUANDO** se envían diez peticiones con éxito a la ruta
- **ENTONCES** `last_used_at` no cambió, ninguna fila de V8 se escribió y no se añadió ningún asiento

### Requisito: Ausencia de aviso al titular por reutilización detectada (brecha con destino: cambio 14)

El sistema NO DEBE enviar, en este cambio, ningún correo ni notificación al titular cuando se detecta la
reutilización de un token de refresco. El destino es **`transactional-email-adapter`**, cambio 14 de F0.
El sistema entrega solo la revocación y su asiento de auditoría.

#### Escenario: Una reutilización detectada no notifica a nadie

- **DADO** una reutilización detectada para `maria.lopez@colegio.edu.hn`
- **CUANDO** se inspeccionan los asientos que produjo y los adaptadores de envío
- **ENTONCES** ningún asiento nombra una notificación, un correo ni un intento de entrega, y ningún
  adaptador de envío fue invocado
- **Y** este escenario deja de ser cierto el día que el cambio 14 entregue el aviso

### Requisito: Ausencia de purga de familias y tokens de refresco (brecha con destino: cambio 9)

El sistema NO DEBE eliminar, en este cambio, ninguna familia ni ningún token de refresco, esté vencido,
usado o revocado, ni vaciar por tiempo el sucesor cifrado que ya no puede descifrarse. Ningún rol de
aplicación recibe privilegio `DELETE` sobre las dos tablas. La purga que pide
`docs/08-datos-privacidad-y-retencion.md`, incluida la de la IP inicial, queda con el **cambio 9**
(`background-jobs-with-db-scheduler`).

#### Escenario: Las filas vencidas y revocadas se conservan

- **DADO** una familia revocada hace dos días y un token vencido hace dos días
- **CUANDO** se consultan las tablas el día siguiente
- **ENTONCES** las filas siguen existiendo

#### Escenario: El sucesor cifrado residual se conserva hasta la purga

- **DADO** el sucesor cifrado de un token consumido hace una hora, sin ningún contacto posterior con la
  familia
- **CUANDO** se consulta la fila
- **ENTONCES** el valor cifrado sigue en la base de datos
- **Y** este escenario deja de ser cierto el día que el cambio 9 entregue la purga

### Requisito: Ausencia de la aplicación de la vinculación por agente de usuario e IP (brecha con destino: `session-endpoints-cookie-and-csrf`)

El sistema NO DEBE aplicar, en este cambio, ninguna vinculación de la sesión al agente de usuario ni a
la IP, más allá de decidir «el mismo dispositivo» en la ventana de gracia. La decisión de revocar la
familia cuando cambian ambos al refrescar (`docs/03-seguridad.md` §4.5, «Vinculación») se reevalúa con el
endpoint de refresco en **`session-endpoints-cookie-and-csrf`**.

#### Escenario: Un cambio simultáneo de agente y de IP no exige reautenticación todavía

- **DADO** una familia viva y un token vigente presentado desde otro agente y otra IP
- **CUANDO** se rota
- **ENTONCES** la rotación tiene éxito, sin exigir reautenticación
- **Y** este escenario deja de ser cierto el día que se decida aplicar la vinculación

### Requisito: Ausencia de revocación de sesiones por cambio de MFA y por desactivación de cuenta (brecha con destino: `session-endpoints-cookie-and-csrf` y cambio 8)

El sistema NO DEBE revocar, en este cambio, ninguna familia por un cambio del segundo factor ni por la
desactivación de una cuenta, y el catálogo de motivos de revocación de la migración `V8` NO DEBE
incluir `admin_revoke`, `logout` ni ningún otro motivo sin productor en este cambio: el catálogo es
exactamente `reuse_detected` y `password_change` (decisión 7 del propietario). La revocación por
inscripción de MFA y `logout` son de **`session-endpoints-cookie-and-csrf`**; la desactivación de cuenta y
`admin_revoke`, del **cambio 8**; cada uno amplía el catálogo con su propia migración.

#### Escenario: Inscribir el segundo factor no revoca ninguna familia

- **DADO** una cuenta con dos familias vivas
- **CUANDO** se inscribe su segundo factor con el caso de uso existente
- **ENTONCES** ambas familias siguen vivas
- **Y** este escenario deja de ser cierto el día que `session-endpoints-cookie-and-csrf` entregue la
  revocación por cambio de MFA

#### Escenario: El catálogo de motivos no declara motivos sin productor

- **DADO** la restricción de verificación de `revoked_reason` en `V8`
- **CUANDO** se intenta insertar una familia revocada con el motivo `admin_revoke` y otra con el motivo
  `logout`
- **ENTONCES** la base de datos rechaza las dos inserciones
- **Y** los motivos `reuse_detected` y `password_change` se aceptan

### Requisito: Ausencia del endpoint JWKS y del par de claves del portal (brecha con destino: el primer verificador distinto del proceso administrativo y la primera ruta del portal)

El sistema NO DEBE publicar, en este cambio, ningún endpoint JWKS ni ninguna ruta de claves públicas,
porque el único verificador es el propio proceso administrativo con el anillo en memoria (decisión 8
del propietario). El sistema NO DEBE cargar el par de claves del portal ni su verificación (decisión
9); solo reserva el nombre `confia.security.portal-signing.*.private-key` para que el proceso
administrativo se niegue a arrancar si aparece (decisión D-N2 del propietario). El primero llega con el
primer verificador que no sea el proceso administrativo, y el segundo con la primera ruta del portal.

#### Escenario: Ninguna ruta de claves públicas

- **DADO** el mapa de rutas del proceso administrativo
- **CUANDO** se busca una ruta de claves públicas, por ejemplo `/.well-known/jwks.json`
- **ENTONCES** no existe ninguna y la petición recibe `401`
- **Y** este escenario deja de ser cierto el día que se entregue un verificador externo

#### Escenario: El portal no tiene par de claves

- **DADO** el proceso del portal con el entorno por omisión
- **CUANDO** se inspecciona su contexto
- **ENTONCES** no contiene ningún anillo de claves ni carga ninguna clave de firma; solo comprueba, al
  arrancar, que la clave privada administrativa no está presente
- **Y** este escenario deja de ser cierto el día que el portal entregue su primera ruta

### Requisito: La transferencia de las condiciones duras y las notas de documentación quedan escritas, sin reescribir su texto

El cambio DEBE registrar por escrito, con nota fechada y sin reescribir el cuerpo de ninguna condición,
que H1 y la condición 3 obligan a `session-endpoints-cookie-and-csrf`, que H3 y H4 obligan a
`password-recovery-endpoints`, que H2 se cierra en este cambio, y que este cambio no puede declarar
cerrada ninguna de las otras cuatro. DEBE además dejar notas fechadas en `docs/03-seguridad.md` §4.5
(JWS propio sobre el JDK, claves por variable de entorno, rotación con clave vigente y anterior, JWKS
diferido), en `docs/05` (las variables de las claves de firma y el nombre reservado de la clave del
portal), en `docs/08-datos-privacidad-y-retencion.md` (la IP inicial y la huella del agente de usuario de
la familia, conservadas hasta la purga del cambio 9) y en `docs/ui-ux/04-patrones-de-interaccion.md` (la
SPA refresca solo ante actividad del usuario, por la lectura de la inactividad), y una nota de ADR-0005
que precise la lectura de las vigencias, el vocabulario de revocación `password_change` y que la ventana
de gracia devuelve el mismo refresco sucesor con un token de acceso nuevo (decisión D-N1 del
propietario).

#### Escenario: [I135] Nota de transferencia en el plan de fundaciones (solo documental)

- **DADO** `openspec/changes/foundations-plan/exploration.md` tras este cambio
- **CUANDO** se lee su nota fechada «quinto corte» del 2026-10-06
- **ENTONCES** registra la partición en tres cambios, declara a quién obliga cada condición dura y
  declara H2 cerrada en este cambio
- **Y** el texto original de H1, H2, H3, H4 y de la condición 3 no fue modificado

#### Escenario: [I136] Notas fechadas en `docs/03`, `docs/05` y `docs/ui-ux/04` (solo documental)

- **DADO** los tres documentos tras este cambio
- **CUANDO** se leen las secciones citadas
- **ENTONCES** cada una tiene una nota fechada con su contenido y el texto original no fue modificado

#### Escenario: [I137] El informe de archivo lista las cuatro condiciones como abiertas y transferidas (solo documental)

- **DADO** el informe de archivo de este cambio
- **CUANDO** se lee su sección de condiciones duras
- **ENTONCES** lista H1, H3, H4 y la condición 3 como abiertas y transferidas, cada una con su nuevo
  dueño, y H2 como cerrada con la referencia a la prueba I24

#### Escenario: [I148] Notas fechadas en `docs/05`, `docs/08` y ADR-0005 (solo documental)

- **DADO** `docs/05-infraestructura-y-despliegue.md`, `docs/08-datos-privacidad-y-retencion.md` y
  `docs/adr/ADR-0005` tras este cambio
- **CUANDO** se leen
- **ENTONCES** `docs/05` tiene una nota fechada con las cinco propiedades del anillo de claves, el nombre
  reservado de la clave privada del portal y la aclaración de que ninguna tiene valor en el repositorio
- **Y** `docs/08` tiene una nota fechada sobre la IP y la huella del agente de usuario de la familia
- **Y** ADR-0005 tiene una nota fechada sobre las vigencias, el vocabulario `password_change` y la gracia
  con refresco igual y acceso nuevo
- **Y** el texto original de los tres documentos no fue modificado

### Requisito: Ausencia de la dimensión por dirección IP del retroceso exponencial (brecha con destino: `session-endpoints-cookie-and-csrf` y cambio 11)

El sistema NO DEBE aplicar, en esta parte del cambio, ninguna de las tres reglas de dimensión por
dirección IP de `docs/03-seguridad.md` §4.4 —límite por IP, límite por IP contra cuentas distintas,
ni el indicador de relleno de credenciales—, porque no existe todavía ningún endpoint de inicio de
sesión que las aplique. El control por IP es responsabilidad de **`session-endpoints-cookie-and-csrf`**,
que cierra la condición dura H1, y su aprovisionamiento sobre Redis, del **cambio 11**
(`containerization-and-cicd-pipeline`). (Sustituye al requisito del mismo nombre cuyo destino era
`session-tokens-and-web-layer`; el texto de fondo se conserva.)

#### Escenario: Ninguna regla de dirección IP se aplica todavía

- **DADO** el caso de uso de autenticación de este cambio, invocado sin ningún concepto de dirección
  IP
- **CUANDO** se ejecutan diez intentos fallidos consecutivos como si vinieran de la misma dirección
  IP, contra cinco cuentas distintas
- **ENTONCES** el sistema no aplica ningún límite ni indicador por dirección IP, porque ese control
  no existe todavía en esta parte del cambio
- **Y** este escenario deja de ser cierto el día que `session-endpoints-cookie-and-csrf` entregue el
  control, con el aprovisionamiento del cambio 11

### Requisito: Ausencia del límite por dirección IP y de la respuesta HTTP de la recuperación (brecha con destino: `password-recovery-endpoints` y cambio 11)

El sistema NO DEBE aplicar, en este cambio, el límite de 10 solicitudes por hora por dirección IP de
`docs/03-seguridad.md` §4.7 y §10, ni producir la respuesta `202 Accepted` con su mensaje, porque no
existe el endpoint de solicitud de recuperación. Es condición dura de aceptación de
**`password-recovery-endpoints`**: ese cambio NO DEBE fusionar el endpoint de solicitud de recuperación
sin ese límite por IP, con el aprovisionamiento de Redis del **cambio 11**, operativo y probado.
(Sustituye al requisito del mismo nombre cuyo destino era `session-tokens-and-web-layer`; la condición
dura H3 conserva su texto y solo cambia de dueño.)

#### Escenario: Once solicitudes seguidas no encuentran ningún límite por IP

- **DADO** once cuentas de personal distintas
- **CUANDO** se solicita recuperación para cada una entre las 10:00:00 y las 10:05:00 del 2026-10-16,
  como si vinieran de la misma dirección IP
- **ENTONCES** las once quedan programadas, porque el control por IP no existe todavía

#### Escenario: El resultado uniforme es del caso de uso, no una respuesta HTTP

- **DADO** una solicitud de recuperación con cualquier correo
- **CUANDO** el caso de uso devuelve su resultado
- **ENTONCES** ese resultado no es un código de estado HTTP ni lleva un texto para el usuario
- **Y** el `202 Accepted` y su mensaje, tomado del catálogo de internacionalización, quedan como
  responsabilidad de `password-recovery-endpoints`

### Requisito: Ausencia del restablecimiento administrativo (brecha con destino: cambio 8 y `password-recovery-endpoints`)

El sistema NO DEBE ofrecer, en este cambio, ningún caso de uso con el que un actor distinto del
titular dispare el restablecimiento de otra cuenta ni invalide su contraseña (`docs/03-seguridad.md`
§4.7, «Restablecimiento administrativo»), porque el rol de Super Administrador llega con el **cambio
8**, `rbac-permission-matrix-and-audit-integration`, y el endpoint con
`password-recovery-endpoints`. Ningún caso de uso DEBE permitir fijar una contraseña sin un token de
recuperación válido. (Sustituye al requisito del mismo nombre cuyo destino era el cambio 8 y
`session-tokens-and-web-layer`.)

#### Escenario: El único camino para cambiar una contraseña es el token

- **DADO** el conjunto de casos de uso públicos del módulo de identidad
- **CUANDO** se inspecciona cuáles escriben el hash de contraseña de una cuenta
- **ENTONCES** el único es el restablecimiento con token

#### Escenario: Un restablecimiento sin token válido nunca cambia la contraseña

- **DADO** la cuenta `ana.martinez@colegio.edu.hn`, con la contraseña vigente `Cafetal de Copán 2026`
- **CUANDO** se intenta restablecerla con un token que nunca se emitió
- **ENTONCES** el sistema lo rechaza y `Cafetal de Copán 2026` sigue autenticando

## MODIFIED Requirements

### Requisito: MFA obligatoria para cuentas marcadas con `mfa_required`

El sistema DEBE exigir un segundo factor de autenticación para toda cuenta cuya columna
`mfa_required` esté en `true`, y NO DEBE producir el desenlace `Authenticated` para esa cuenta
mientras el segundo factor no se haya completado. Cuando la cuenta tiene un secreto TOTP ya
inscrito, el sistema DEBE producir el desenlace `SecondFactorRequired`, pendiente de un código TOTP
válido. Cuando la cuenta no tiene ningún secreto TOTP inscrito, el sistema DEBE producir el
desenlace `SecondFactorEnrollmentRequired`. Ninguno de los dos desenlaces DEBE hacer avanzar el
contador de retroceso por intentos fallidos, porque ninguno es un rechazo de credenciales. **La
traducción de `SecondFactorRequired` y de `SecondFactorEnrollmentRequired` a un token de sesión
completo o restringido es responsabilidad de `session-endpoints-cookie-and-csrf`**, el segundo cambio de la
partición de la parte 4b: `session-tokens-and-web-layer` entrega el token restringido y el token de
acceso completo, pero ningún endpoint que los entregue; este cambio decide y devuelve el desenlace
tipado, y no emite ningún token.
(Previously: la traducción era responsabilidad de `session-tokens-and-web-layer`, el cuarto cambio de
esta secuencia; ahora lo es de `session-endpoints-cookie-and-csrf`, que recibe los tokens de aquel.)

#### Escenario: Cuenta con `mfa_required` en `true` y secreto TOTP ya inscrito

- **DADO** la cuenta de personal `sofia.mejia@colegio.edu.hn`, con `mfa_required` en `true` y un
  secreto TOTP ya inscrito
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema devuelve el desenlace `SecondFactorRequired`, pendiente de un código TOTP
  válido
- **Y** no devuelve el desenlace `Authenticated`
- **Y** el contador de retroceso por intentos fallidos de esa cuenta no avanza
- **Y** la traducción de este desenlace a un token de sesión completo, una vez recibido un código
  válido, queda como responsabilidad de `session-endpoints-cookie-and-csrf`

#### Escenario: Cuenta con `mfa_required` en `true` sin ningún secreto TOTP inscrito

- **DADO** la cuenta de personal `jorge.aguilar@colegio.edu.hn`, con `mfa_required` en `true` y sin
  ningún secreto TOTP inscrito
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema devuelve el desenlace `SecondFactorEnrollmentRequired`
- **Y** no devuelve el desenlace `Authenticated`
- **Y** el contador de retroceso por intentos fallidos de esa cuenta no avanza
- **Y** la traducción de este desenlace a una sesión restringida a la inscripción, en vez de una
  sesión completa, queda como responsabilidad de `session-endpoints-cookie-and-csrf`

### Requisito: Prohibición de enumeración de usuarios

El sistema NO DEBE revelar, a través de ninguna respuesta de autenticación o de recuperación de
contraseña, si una cuenta con un identificador dado existe o no. La respuesta DEBE ser idéntica
en forma, contenido y tiempo observable de respuesta en ambos casos.

Al nivel del caso de uso, la solicitud de recuperación DEBE devolver el mismo resultado y escribir el
mismo número de asientos de auditoría, con la misma acción, para una cuenta existente, para un
identificador sin cuenta y para una cuenta cuya emisión omitirá el límite por hora. El motivo real
DEBE quedar solo en la bitácora. El identificador presentado DEBE aparecer en la bitácora como su
huella con llave, la misma que usa el inicio de sesión, y nunca en claro cuando no corresponde a
ninguna cuenta. La respuesta `202 Accepted` con su mensaje y la medición del tiempo sobre respuestas
HTTP son responsabilidad de `password-recovery-endpoints`.
(Previously: esa responsabilidad figuraba como de `session-tokens-and-web-layer`; la partición de la
parte 4b la deja en el tercer cambio, `password-recovery-endpoints`.)

#### Escenario: Solicitud de recuperación con correo existente

- **DADO** que `maria.lopez@colegio.edu.hn` existe como cuenta de personal
- **CUANDO** se solicita recuperación de contraseña para ese correo
- **ENTONCES** el caso de uso devuelve el resultado uniforme de solicitud recibida
- **Y** escribe exactamente un asiento de solicitud, con la huella con llave del correo como entidad
- **Y** programa una emisión para la cuenta

#### Escenario: Solicitud de recuperación con correo inexistente

- **DADO** que `nadie.registrado@colegio.edu.hn` no corresponde a ninguna cuenta
- **CUANDO** se solicita recuperación de contraseña para ese correo
- **ENTONCES** el caso de uso devuelve el mismo resultado que en el escenario anterior
- **Y** escribe exactamente un asiento de solicitud con la misma acción, con la huella con llave del
  correo como entidad y sin el correo en claro en ninguna columna
- **Y** no programa ninguna emisión, así que no se envía ningún correo

#### Escenario: Una cuenta en el límite por hora es indistinguible de una cuenta inexistente

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn`, con tres tokens emitidos en la última hora
- **Y** el correo `nadie.registrado@colegio.edu.hn`, que no corresponde a ninguna cuenta
- **CUANDO** se solicita recuperación para cada uno a las 10:30:00 del 2026-10-12
- **ENTONCES** los dos resultados son idénticos y cada solicitud escribe exactamente un asiento de
  solicitud con la misma acción
- **Y** el hecho de que la cuenta esté en el límite solo aparece en la bitácora, al omitirse su
  emisión

### Requisito: Ausencia de envío real del enlace y de la notificación al titular (brecha con destino: `transactional-email-adapter`, cambio 14 de F0)

El sistema NO DEBE enviar, en este cambio, ningún correo con el enlace de recuperación ni ninguna
notificación al titular tras un restablecimiento (`docs/03-seguridad.md` §4.7, «Efecto»; ADR-0005,
«Recuperación de contraseña», punto 5). El puerto de envío que usa la emisión NO DEBE tener adaptador
de producción. El destino es **`transactional-email-adapter`**, cambio 14 de F0, posterior al cambio
9. La IP de la notificación la aporta además `session-endpoints-cookie-and-csrf`, con la propagación del
origen de la petición a los comandos de identidad.
(Previously: la IP de la notificación figuraba como aportada por `session-tokens-and-web-layer`.)

#### Escenario: El puerto de envío no tiene adaptador de producción

- **DADO** el árbol de clases de producción del módulo de identidad
- **CUANDO** se inspecciona qué clases implementan el puerto de envío del enlace
- **ENTONCES** ninguna lo hace
- **Y** esta comprobación falla el día que exista un adaptador sin retirarla

#### Escenario: Un restablecimiento completado no notifica a nadie

- **DADO** un restablecimiento completado para `ana.martinez@colegio.edu.hn`
- **CUANDO** se inspeccionan los asientos de auditoría que produjo
- **ENTONCES** ninguno nombra una notificación, un correo ni un intento de entrega

## REMOVED Requirements

### Requisito: Ausencia de revocación de sesiones al restablecer (brecha con destino: `session-tokens-and-web-layer`, condición dura de aceptación)

(Reason: la condición dura H2 se cumple en este cambio. `session-tokens-and-web-layer` entrega la
emisión de familias de refresco y, antes de que exista cualquier camino HTTP que entregue un refresco,
la revocación de todas las familias al restablecer, con su prueba de integración I24. El escenario «El
restablecimiento solo cambia la contraseña» (I23) deja de ser cierto porque ahora existen sesiones que
revocar, y el escenario «La brecha se cierra con la primera emisión de tokens de refresco» (I24) se
convierte en prueba.)
(Migration: el requisito positivo vive en este mismo capítulo, «El restablecimiento de contraseña
revoca todas las familias vivas de la cuenta en la misma transacción», con el escenario I24. La
inversión de `IdentityScopeExclusionInventoryTest` se declara en `build-integrity`.)

### Requisito: Ausencia de la dimensión por dirección IP del retroceso exponencial (brecha con destino: `session-tokens-and-web-layer` y cambio 11)

(Reason: la partición de la parte 4b (DA-1) asigna el control por IP del inicio de sesión al segundo
cambio, `session-endpoints-cookie-and-csrf`, que cierra H1. El texto de la brecha se conserva sin
cambios de fondo; solo cambia su dueño, porque `session-tokens-and-web-layer` no entrega ningún
endpoint de inicio de sesión.)
(Migration: sustituido por «Ausencia de la dimensión por dirección IP del retroceso exponencial (brecha
con destino: `session-endpoints-cookie-and-csrf` y cambio 11)», en la sección ADDED de este delta.)

### Requisito: Ausencia del límite por dirección IP y de la respuesta HTTP de la recuperación (brecha con destino: `session-tokens-and-web-layer` y cambio 11)

(Reason: la partición de la parte 4b (DA-1 y DA-6) asigna el endpoint de solicitud de recuperación al
cuarto cambio de la cadena, `password-recovery-endpoints`, posterior al cambio 9. La condición dura H3
conserva su texto y su condición; solo cambia su dueño.)
(Migration: sustituido por «Ausencia del límite por dirección IP y de la respuesta HTTP de la
recuperación (brecha con destino: `password-recovery-endpoints` y cambio 11)», en la sección ADDED de este
delta.)

### Requisito: Ausencia del restablecimiento administrativo (brecha con destino: cambio 8 y `session-tokens-and-web-layer`)

(Reason: el endpoint del restablecimiento es del cambio `password-recovery-endpoints` por la partición
de la parte 4b; el rol de Super Administrador sigue siendo del cambio 8.)
(Migration: sustituido por «Ausencia del restablecimiento administrativo (brecha con destino: cambio 8 y
`password-recovery-endpoints`)», en la sección ADDED de este delta.)


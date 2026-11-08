# Delta para Inicio de sesión del personal

- **Estado:** requisitos separados el 2026-11-05 de `admin-web-foundations-and-session`, tras la resolución de Q1
- **Fase:** F0 (cambio 13b-1, parte 2 de 2)
- **Cambio:** `admin-web-staff-sign-in`
- **Propuesta:** la de este cambio, con las decisiones P1 a P3 y P6 a P10 y la resolución de Q2 de la parte 1 vinculantes

## Propósito

Agrega a la capacidad que publica la parte 1 las pantallas de inicio de sesión, verificación del segundo factor e inscripción
obligatoria con todos sus estados, la ausencia de fugas de secretos a lo largo de los recorridos completos, y los recorridos de navegador contra una API simulada. Con
la puerta de accesibilidad sobre rutas (capacidad `build-integrity`) cierra el criterio de salida 8 de F0
(`docs/09-roadmap-y-fases.md`): «La verificación de accesibilidad no reporta violaciones críticas en las pantallas existentes».

## Convenciones de este delta

- **Cómo se fusiona este delta.** Se archiva después de `admin-web-foundations-and-session`. Bajo `## MODIFIED Requirements`, un requisito con su encabezado
  exacto trae el cuerpo vigente y **solo los escenarios que esta parte agrega**. Al archivar, los escenarios que la parte 1 ya
  publicó se conservan y estos se suman. No se repiten porque un escenario vive en un solo cambio.
- **Textos fijados por esta especificación o por la documentación** (el resto de los textos los fija el catálogo `admin`, con los valores
  de `design.md`, sección 8, aprobados por el propietario): credenciales no aceptadas, «El correo o la contraseña no son correctos.»
  (`docs/ui-ux/05`:121); código TOTP no aceptado, «El código de verificación no es correcto o ya expiró. Genere uno nuevo en su
  aplicación.» (`docs/ui-ux/05`:123 en «usted»); límite de solicitudes, «Hizo demasiadas solicitudes seguidas. Espere unos segundos e
  intente de nuevo.» (`docs/ui-ux/04`:729); sesión vencida, el de la parte 1.
- **Valores fijados por el diseño** (`design.md`, sección 4 y D11, D12 y D15):
  - **Identificador:** se recortan solo los extremos antes de enviar y guardar; ni NFKC ni minúsculas.
  - **URI:** `otpauth://totp/CONFIA:<encodeURIComponent(identificador)>?secret=<secreto>&issuer=CONFIA`, sin `algorithm`, `digits` ni `period`.
  - **Inscripción:** secciones de una sola ruta, solo hacia adelante, sin entradas de historial.
  - **Código de recuperación:** sin espacios, en mayúsculas, de 1 a 64 caracteres; sin comprobar el alfabeto.
  - **QR:** `qrcode.react`, `QRCodeSVG`, como única vía. Si SS83 falla en rojo, el PR se detiene y decide el propietario.
    **Decodificador de prueba:** `jsqr`.

> **Nota fechada 2026-11-07 (decisión del propietario, fase de diseño).** Se retira la salida alternativa que este bloque daba por decidida.
> Texto anterior: «si SS83 falla en rojo, la salida ya decidida es `qrcode` con `QRCode.create()` y un `<path>` propio». Contradecía el
> requisito de la inscripción, que exige `QRCodeSVG` de `qrcode.react` con DEBE (`design.md`, E11).
  - **Matriz de la puerta:** la de `build-integrity`, 22 estados por 2 temas.

## ADDED Requirements

### Requisito: Todo formulario de acceso se envía con el mismo comportamiento de espera, error y foco

El sistema DEBE aplicar a los formularios de inicio de sesión, verificación y primer código de la inscripción el mismo
comportamiento (`docs/ui-ux/03` §5 y §4). Validar con Zod en el cliente antes de enviar, con los topes del contrato y
`.min(1)` donde el contrato admite vacíos, sin enviar nada si hay errores. El botón de envío DEBE estar siempre
habilitado; durante la petición DEBE llevar `aria-busy="true"` con un `Spinner` y una segunda activación NO DEBE emitir una
segunda petición. Los errores de campo, del cliente o de un `400 validation-failed`, DEBEN mostrarse en un
`FormErrorSummary` al inicio del formulario que recibe el foco, con un enlace por error que mueve el foco al control, y bajo
cada campo con `aria-invalid` y `aria-describedby`. Los errores que no son de un campo DEBEN mostrarse en un `Alert` al
inicio del formulario que recibe el foco. Un `429 too-many-requests` DEBE mostrar el texto fijado con una cuenta regresiva
que empieza en el valor de `Retry-After`, sin que el texto cambie cada segundo en una región activa. Un `503
capacity-exceeded`, un error de red y un error inesperado DEBEN mostrar su mensaje y permitir enviar de nuevo; ninguno
reintenta solo. La espera de una respuesta de hasta unos 30 s NO DEBE tener un tiempo límite menor en el cliente. Los
campos DEBEN permitir pegar y NO DEBEN tener `maxLength` menor que el del contrato.

#### Escenario: [SS37] Validación en el cliente sin petición

- **DADO** el formulario de inicio de sesión con los dos campos vacíos
- **CUANDO** la persona activa el envío
- **ENTONCES** el servidor registra cero peticiones
- **Y** el resumen de errores aparece al inicio con dos enlaces y recibe el foco
- **Y** cada campo tiene `aria-invalid="true"` y su descripción referencia su mensaje

#### Escenario: [SS38] Cada enlace del resumen lleva el foco a su campo

- **DADO** el resumen de errores con un error por campo
- **CUANDO** la persona activa el enlace del segundo error
- **ENTONCES** el foco está en el segundo campo
- **Y** la URL y el historial no cambian

#### Escenario: [SS39] El botón de envío no se deshabilita y marca la espera

- **DADO** el formulario con datos válidos y un servidor que retiene la respuesta
- **CUANDO** la persona activa el envío
- **ENTONCES** el botón sigue enfocable, no tiene el atributo `disabled`, expone `aria-busy="true"` y contiene un `Spinner`

#### Escenario: [SS40] Una segunda activación en vuelo no emite una segunda petición

- **DADO** el envío en vuelo
- **CUANDO** la persona activa el botón tres veces más con teclado y con ratón
- **ENTONCES** el servidor registra una sola petición

#### Escenario: [SS41] Un `400` por campo se muestra bajo el campo y en el resumen

- **DADO** una respuesta `400 validation-failed` con `errors` `[{"field":"password","reason":"max"}]`
- **CUANDO** la pantalla la procesa
- **ENTONCES** el campo de contraseña muestra su error con el texto del catálogo para la razón `max`, y el resumen recibe el
  foco con un enlace a ese campo
- **Y** el valor rechazado no aparece en ningún mensaje

#### Escenario: [SS42] Un error que no es de un campo se muestra en un `Alert` con el foco

- **DADO** una respuesta `401 authentication-failed`
- **CUANDO** la pantalla la procesa
- **ENTONCES** aparece un `Alert` destructivo al inicio del formulario con región activa asertiva y recibe el foco

#### Escenario: [SS43] `429`: texto fijado y cuenta regresiva

- **DADO** una respuesta `429 too-many-requests` con `Retry-After: 15`
- **CUANDO** la pantalla la procesa
- **ENTONCES** muestra «Hizo demasiadas solicitudes seguidas. Espere unos segundos e intente de nuevo.» y una cuenta
  regresiva que empieza en 15 segundos y baja

#### Escenario: [SS44] La cuenta regresiva no se anuncia cada segundo

- **DADO** la cuenta regresiva de 15 segundos en curso y un observador de mutaciones sobre todas las regiones activas
- **CUANDO** transcurre la cuenta completa
- **ENTONCES** el texto de las regiones activas cambia como máximo dos veces: al aparecer el aviso y al terminar la espera
- **Y** el aviso de cortesía `polite` del final indica que ya se puede intentar de nuevo

#### Escenario: [SS45] Activar el envío durante la cuenta regresiva no emite una petición

- **DADO** la cuenta regresiva en curso
- **CUANDO** la persona activa el envío
- **ENTONCES** el servidor registra cero peticiones nuevas y la cuenta regresiva sigue
- **Y** el botón no estaba deshabilitado

#### Escenario: [SS46] Terminada la espera se puede enviar de nuevo

- **DADO** la cuenta regresiva terminada
- **CUANDO** la persona activa el envío
- **ENTONCES** el servidor registra una petición nueva

#### Escenario: [SS47] `503 capacity-exceeded`

- **DADO** una respuesta `503` con `capacity-exceeded`
- **CUANDO** la pantalla la procesa
- **ENTONCES** muestra el mensaje de servicio ocupado en un `Alert` con el foco, sin reintentar sola
- **Y** una segunda activación del envío emite una petición nueva

#### Escenario: [SS48] Espera larga sin tiempo límite en el cliente

- **DADO** una respuesta del servidor simulado que tarda 31 segundos, con una prueba cuyo tiempo límite se declara
  explícitamente mayor
- **CUANDO** la persona envía el formulario
- **ENTONCES** el botón mantiene `aria-busy="true"` durante toda la espera y un mensaje de estado en una región de cortesía
  `polite` indica que se está verificando
- **Y** al llegar la respuesta se muestra su resultado sin error de tiempo agotado

#### Escenario: [SS49] Error de red al enviar

- **DADO** un transporte que falla por red al enviar
- **CUANDO** la pantalla lo procesa
- **ENTONCES** muestra el mensaje de error de red en un `Alert` y conserva los valores escritos
- **Y** el servidor no registra un segundo intento

#### Escenario: [SS50] Error inesperado con identificador de traza

- **DADO** una respuesta `500 internal-error` con `traceId`
- **CUANDO** la pantalla la procesa
- **ENTONCES** muestra el mensaje genérico, el identificador visible y el control que lo copia, en un `Alert` con el foco

#### Escenario: [SS51] Se puede pegar y no hay `maxLength` menor que el contrato

- **DADO** los campos de texto de los tres formularios
- **CUANDO** se pega una cadena en cada uno y se inspeccionan sus atributos
- **ENTONCES** el pegado se acepta
- **Y** ningún `maxLength` es menor que el máximo del contrato del campo

> **Nota fechada 2026-11-07 (decisión del propietario, fase de diseño).** `docs/ui-ux/04-patrones-de-interaccion.md:393-394` dice que, tras un
> envío fallido, el foco salta al primer campo con error y que el resumen es `role="alert"`. Este requisito (SS37), `docs/ui-ux/03-accesibilidad.md:141`
> y `FormErrorSummary` (`role="group"`) enfocan el resumen. Ganan esta especificación y `docs/ui-ux/03`. El texto de este requisito no cambia; la
> nota fechada en `docs/ui-ux/04` se agrega en el PR que implementa el formulario, no en esta fase (`design.md`, E3 y sección 14).

### Requisito: El inicio de sesión pide identificador y contraseña, no ofrece recuperación de contraseña y permite mostrar la contraseña

El sistema DEBE mostrar un formulario con el campo del identificador (`autocomplete="username"`) y el de la contraseña
(`autocomplete="current-password"`, oculta por defecto), cada uno con etiqueta visible asociada y marcado en texto como
obligatorio. DEBE ofrecer un control «mostrar contraseña» compuesto con `Input` e `IconButton` en la funcionalidad de
identidad (decisión P10): con el nombre accesible fijo «Mostrar contraseña» y el estado en `aria-pressed`, que conserva el valor
y el foco y que vuelve a ocultar la contraseña cada vez que se entra a la pantalla. DEBE mostrar un texto que remite a la
administración para recuperar la contraseña y NO DEBE ofrecer enlace, ruta ni operación de contraseña olvidada (decisión
P3; el autoservicio por HTTP no existe). Una respuesta `401 authentication-failed` DEBE mostrar siempre el mismo texto
fijado, sin distinguir cuál de los dos datos falló. La respuesta `200` DEBE llevar a la pantalla que corresponde a su
`status`. El identificador DEBE recortarse solo en los extremos, sin NFKC ni minúsculas, y enviarse con el mismo valor que se conserva para la etiqueta del URI de la inscripción.

#### Escenario: [SS52] Campos, etiquetas y atributos de autocompletado

- **DADO** la pantalla de inicio de sesión
- **CUANDO** se consultan sus controles por rol y nombre accesible
- **ENTONCES** hay un campo con etiqueta visible y `autocomplete="username"`, y otro con etiqueta visible,
  `autocomplete="current-password"` y `type="password"`
- **Y** ambos indican en texto que son obligatorios

#### Escenario: [SS53] Mostrar y ocultar la contraseña

- **DADO** la contraseña escrita «CENTINELA-CLAVE»
- **CUANDO** la persona activa el control de mostrar contraseña
- **ENTONCES** el campo pasa a `type="text"` con el mismo valor, el control tiene `aria-pressed="true"` y su nombre accesible
  sigue siendo «Mostrar contraseña», y el foco sigue en el control
- **Y** al activarlo de nuevo vuelve a `type="password"` y el control tiene `aria-pressed="false"`

> **Nota fechada 2026-11-07 (decisión del propietario, fase de diseño).** Se enmiendan este escenario y el cuerpo de su requisito: el
> nombre accesible del control es fijo y el estado lo lleva solo `aria-pressed`, porque combinar un nombre que cambia con `aria-pressed`
> da dos señales del mismo estado. Texto anterior del requisito: «con nombre accesible que cambia entre mostrar y ocultar, `aria-pressed`».
> Texto anterior de la primera cláusula ENTONCES: «el control tiene `aria-pressed="true"` y su nombre accesible pasó de «mostrar» a
> «ocultar»». La segunda cláusula agrega `aria-pressed="false"` (`design.md`, E8).

#### Escenario: [SS54] La contraseña vuelve a estar oculta al entrar de nuevo

- **DADO** la contraseña mostrada
- **CUANDO** la persona sale del inicio de sesión y vuelve a entrar
- **ENTONCES** el campo está en `type="password"`

#### Escenario: [SS55] Texto de contacto en lugar de contraseña olvidada

- **DADO** la pantalla de inicio de sesión
- **CUANDO** se busca el texto de contacto, un enlace o botón de contraseña olvidada, una ruta de recuperación en el enrutador
  y una operación de recuperación en el cliente
- **ENTONCES** el texto de contacto existe, con un texto del catálogo que remite a la administración
- **Y** no existe ninguno de los otros cuatro

#### Escenario: [SS56] Credenciales no aceptadas

- **DADO** una respuesta `401 authentication-failed`
- **CUANDO** la pantalla la procesa
- **ENTONCES** muestra, exactamente, «El correo o la contraseña no son correctos.»
- **Y** el identificador escrito se conserva en su campo
- **Y** el texto es idéntico para una cuenta existente y para una inexistente

#### Escenario: [SS57] Cada `status` de la respuesta lleva a su pantalla

- **DADO** respuestas `200` con `status` `authenticated`, `second-factor-required` y `second-factor-enrollment-required`
- **CUANDO** la pantalla las procesa
- **ENTONCES** llevan, respectivamente, a la página autenticada, a la verificación y a la inscripción

#### Escenario: [SS58] El identificador enviado es el que se conserva para la inscripción

- **DADO** un identificador escrito «ana.pérez+admin@colegio.edu.hn» y una respuesta `second-factor-enrollment-required`
- **CUANDO** la petición de inicio de sesión sale y la inscripción llega al paso del QR
- **ENTONCES** el valor del campo `identifier` enviado es idéntico al valor que usa la etiqueta del URI

#### Escenario: [SS59] El identificador solo se conserva cuando hay inscripción

- **DADO** respuestas `authenticated` y `second-factor-required` tras escribir un identificador
- **CUANDO** se inspecciona el almacén de la aplicación
- **ENTONCES** no conserva el identificador

#### Escenario: [SS60] Avisos al llegar al inicio de sesión

- **DADO** un motivo de llegada: sesión vencida, verificación interrumpida, inscripción interrumpida o segundo factor ya
  inscrito
- **CUANDO** se muestra el inicio de sesión
- **ENTONCES** aparece un `Alert` con el texto propio de ese motivo, en una región `polite`, que no mueve el foco del `h1`
- **Y** el motivo no viaja en la URL ni en `history.state`
- **Y** al recargar el inicio de sesión el aviso ya no aparece

### Requisito: La verificación acepta el código TOTP o un código de recuperación y maneja el token restringido vencido

El sistema DEBE mostrar, con un token restringido de uso `mfa-verify`, un campo de código TOTP de 6 dígitos con
`inputmode="numeric"` y `autocomplete="one-time-code"` que tolera espacios (incluidos los de ancho cero y los que inserta
el pegado) y envía solo los 6 dígitos. DEBE ofrecer en esa misma pantalla la alternativa de un código de recuperación (decisión
P9), con un campo propio que quita los mismos espacios y pasa a mayúsculas, de 1 a 64 caracteres y sin comprobar el alfabeto, y enviar exactamente uno de `code` o `recoveryCode`. Un `401 second-factor-invalid` DEBE mostrar
el texto fijado para el código TOTP, o el propio del código de recuperación, sin decir por qué. Un `401 token-expired` o `401
token-invalid` DEBE descartar el token restringido y llevar al inicio de sesión con el aviso de verificación interrumpida.
La pantalla de verificación de la inscripción (primer código) NO DEBE ofrecer el código de recuperación, porque el backend
lo rechaza y lo cuenta como `second-factor-invalid`. Un `200` DEBE llevar a la página autenticada.

#### Escenario: [SS61] Atributos del campo TOTP

- **DADO** la pantalla de verificación
- **CUANDO** se inspecciona el campo del código
- **ENTONCES** tiene `inputmode="numeric"`, `autocomplete="one-time-code"` y etiqueta visible

#### Escenario: [SS62] El código con espacios se normaliza

- **DADO** el código «123 456» pegado, y otro «123456» con espacios de ancho cero
- **CUANDO** la persona envía
- **ENTONCES** cada petición lleva `code` igual a «123456» y no lleva `recoveryCode`

#### Escenario: [SS63] Un código TOTP que no tiene 6 dígitos no se envía

- **DADO** los códigos «12345», «1234567» y «12a456»
- **CUANDO** la persona envía cada uno
- **ENTONCES** el servidor registra cero peticiones y cada intento muestra su error de campo en el resumen

#### Escenario: [SS64] Cambiar al código de recuperación

- **DADO** la pantalla de verificación en modo TOTP
- **CUANDO** la persona activa «usar un código de recuperación»
- **ENTONCES** el campo TOTP se reemplaza por un campo de código de recuperación con etiqueta visible
- **Y** el foco queda en el campo nuevo y un control permite volver al modo TOTP

#### Escenario: [SS65] Se envía exactamente uno de los dos códigos

- **DADO** un código de recuperación escrito en el modo de recuperación
- **CUANDO** la persona envía
- **ENTONCES** la petición lleva `recoveryCode` y no lleva `code`

#### Escenario: [SS66] Un código de recuperación vacío no se envía

- **DADO** el modo de recuperación con el campo vacío o solo con espacios
- **CUANDO** la persona envía
- **ENTONCES** el servidor registra cero peticiones y se muestra el error de campo

#### Escenario: [SS67] Código no aceptado

- **DADO** una respuesta `401 second-factor-invalid` en modo TOTP
- **CUANDO** la pantalla la procesa
- **ENTONCES** muestra, exactamente, «El código de verificación no es correcto o ya expiró. Genere uno nuevo en su
  aplicación.», con el foco en el `Alert`, y la pantalla sigue en el mismo modo

#### Escenario: [SS68] Código de recuperación no aceptado

- **DADO** una respuesta `401 second-factor-invalid` en modo de recuperación
- **CUANDO** la pantalla la procesa
- **ENTONCES** muestra el texto propio del código de recuperación, que no pide generar un código en la aplicación

#### Escenario: [SS69] Token restringido vencido en la verificación

- **DADO** una respuesta `401 token-expired` a la verificación
- **CUANDO** la pantalla la procesa
- **ENTONCES** descarta el token restringido y lleva al inicio de sesión con el aviso de verificación interrumpida

#### Escenario: [SS70] Un token inválido recibe el mismo trato

- **DADO** una respuesta `401 token-invalid` a la verificación
- **CUANDO** la pantalla la procesa
- **ENTONCES** el resultado es el mismo que el del escenario SS69

#### Escenario: [SS71] Verificación correcta

- **DADO** una respuesta `200` con la sesión
- **CUANDO** la pantalla la procesa
- **ENTONCES** el token de acceso queda solo en memoria, el token restringido se descarta y se llega a la página autenticada

#### Escenario: [SS72] La verificación de la inscripción no ofrece el código de recuperación

- **DADO** el paso del primer código de la inscripción
- **CUANDO** se buscan controles que cambien al código de recuperación
- **ENTONCES** no existe ninguno

#### Escenario: [SS73] Volver al inicio de sesión desde la verificación

- **DADO** la pantalla de verificación
- **CUANDO** la persona activa «volver al inicio de sesión»
- **ENTONCES** llega al inicio de sesión sin aviso y el token restringido queda descartado

### Requisito: La inscripción obligatoria entrega el secreto en un QR y en una clave manual, generados solo en el navegador

El sistema DEBE llevar a la inscripción al recibir `second-factor-enrollment-required` y emitir exactamente una petición
`POST /api/v1/auth/mfa/enrollments` por cada entrada a la pantalla, incluso con el montaje doble de React en desarrollo, mostrando un
estado de carga mientras espera. Los tres pasos son secciones de una sola ruta, solo hacia adelante y sin entradas de historial. DEBE mostrar el secreto de dos formas a la vez: un código QR del URI `otpauth://` y la clave
manual en grupos de 4 caracteres con un control para copiarla (sin separadores) con aviso de cortesía `polite`. El URI DEBE ser
de tipo `totp`, llevar el secreto base32 tal como lo devolvió el servidor, el parámetro `issuer` igual a «CONFIA», y como
etiqueta el identificador escrito sin transformación, codificado como componente de URI, con el prefijo del emisor: `otpauth://totp/CONFIA:<encodeURIComponent(identificador)>?secret=<secreto>&issuer=CONFIA`,
sin `algorithm`, `digits` ni `period`. El QR DEBE generarse solo en el navegador, sin ninguna petición de red, con `QRCodeSVG` de `qrcode.react`
(`level="M"`, `marginSize={4}`), SVG de atributos, sin estilos en línea, con módulos oscuros sobre fondo claro y zona de silencio en
ambos temas, y con un nombre accesible. El secreto, el URI y el identificador NO DEBEN salir del navegador en ninguna
petición. Un `409 mfa-already-enrolled` DEBE llevar al inicio de sesión con el aviso de segundo factor ya inscrito y descartar el
token. Un error de red DEBE mostrar `ErrorState` con un reintento que ejecuta la persona; un `401 token-expired` o
`token-invalid`, el aviso de inscripción interrumpida.

#### Escenario: [SS74] Una sola petición de inscripción por entrada

- **DADO** la llegada a la pantalla de inscripción
- **CUANDO** pasan 5 segundos y la persona navega por los pasos
- **ENTONCES** el servidor registra exactamente una petición `POST /api/v1/auth/mfa/enrollments`, con `Authorization: Bearer`
  del token restringido y sin cuerpo

#### Escenario: [SS75] El montaje doble de React no duplica la petición

- **DADO** la pantalla de inscripción renderizada dentro de `React.StrictMode` en una prueba de componente
- **CUANDO** se monta
- **ENTONCES** el cliente simulado registra una sola petición de inscripción

#### Escenario: [SS76] Estado de carga de la inscripción

- **DADO** una inscripción cuya respuesta el servidor retiene
- **CUANDO** se observa la pantalla
- **ENTONCES** muestra un indicador de carga con mensaje en una región `polite` y ningún secreto

#### Escenario: [SS77] La clave manual en grupos de cuatro

- **DADO** el secreto «JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP» devuelto por el servidor
- **CUANDO** se muestra la clave manual
- **ENTONCES** el texto es ocho grupos de cuatro caracteres separados por un espacio: «JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP»

#### Escenario: [SS78] Copiar la clave

- **DADO** la clave manual visible
- **CUANDO** la persona activa «copiar clave»
- **ENTONCES** el portapapeles contiene el secreto de 32 caracteres sin espacios
- **Y** una región `polite` anuncia que se copió, y el control tiene nombre accesible

#### Escenario: [SS79] El URI contiene emisor, secreto y la etiqueta codificada

- **DADO** el identificador «ana.pérez+admin@colegio.edu.hn» y el secreto «JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP»
- **CUANDO** se construye el URI y se interpreta con un analizador de URL
- **ENTONCES** el esquema es `otpauth`, el tipo es `totp`, el parámetro `secret` es el secreto sin cambios, el parámetro
  `issuer` es «CONFIA»
- **Y** la etiqueta, decodificada, es «CONFIA:» seguido del identificador escrito
- **Y** el URI no contiene espacios ni caracteres sin codificar de la etiqueta

#### Escenario: [SS80] Una etiqueta con caracteres reservados se codifica

- **DADO** los identificadores «a:b c@x.hn», «ñandú@x.hn» y «a%b@x.hn»
- **CUANDO** se construye el URI de cada uno
- **ENTONCES** la etiqueta decodificada de cada uno, **sin el prefijo `CONFIA:`**, es idéntica a su identificador, y el `:`, el espacio y
  el `%` del identificador aparecen codificados

#### Escenario: [SS81] El QR se decodifica al mismo URI

- **DADO** el QR renderizado en Chromium
- **CUANDO** una prueba lo decodifica con `jsqr`, biblioteca de decodificación de solo prueba
- **ENTONCES** el texto decodificado es idéntico al URI construido

#### Escenario: [SS82] El QR se genera sin red

- **DADO** el bloqueo de toda petición que no sea a la API de la aplicación
- **CUANDO** se genera y se muestra el QR
- **ENTONCES** no se emite ninguna petición adicional y el QR se muestra

#### Escenario: [SS83] El QR es compatible con la CSP y accesible

- **DADO** el QR renderizado con la CSP del cambio
- **CUANDO** se inspecciona su subárbol
- **ENTONCES** no hay atributos `style`, el elemento tiene un nombre accesible del catálogo, y los eventos
  `securitypolicyviolation` son cero

#### Escenario: [SS84] El QR es legible en el tema oscuro

- **DADO** la preferencia de color oscura emulada
- **CUANDO** se leen los colores calculados de los módulos y del fondo del QR
- **ENTONCES** los módulos son más oscuros que el fondo y el fondo rodea a los módulos con una zona de silencio

#### Escenario: [SS85] Ni el secreto ni el URI salen del navegador

- **DADO** el secreto «JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP» y el registro de todas las peticiones del recorrido
- **CUANDO** termina la inscripción
- **ENTONCES** ninguna URL, cabecera ni cuerpo contiene el secreto, su forma en grupos, la cadena `otpauth` ni el identificador
  como etiqueta de URI (el identificador solo viaja en la petición de inicio de sesión)

#### Escenario: [SS86] `409 mfa-already-enrolled`

- **DADO** una respuesta `409 mfa-already-enrolled` a la inscripción
- **CUANDO** la pantalla la procesa
- **ENTONCES** descarta el token y el estado, y lleva al inicio de sesión con el aviso de segundo factor ya inscrito

#### Escenario: [SS87] Error de red en la inscripción

- **DADO** una petición de inscripción que falla por red
- **CUANDO** la pantalla lo procesa
- **ENTONCES** muestra `ErrorState` con un reintento de etiqueta no vacía
- **Y** el servidor no registra un segundo intento hasta que la persona activa el reintento

#### Escenario: [SS88] Token restringido vencido en la inscripción

- **DADO** una respuesta `401 token-expired` a la inscripción
- **CUANDO** la pantalla la procesa
- **ENTONCES** lleva al inicio de sesión con el aviso de inscripción interrumpida

### Requisito: Los códigos de recuperación se muestran una sola vez y exigen una confirmación explícita antes de continuar

El sistema DEBE mostrar los códigos de recuperación recibidos de la inscripción (diez, según `identity`) en un paso propio,
como texto seleccionable en una lista, **una sola vez**, con una casilla de confirmación explícita («He guardado mis
códigos de recuperación») y un control para continuar. El control para continuar NO DEBE estar deshabilitado: si la casilla
no está marcada, DEBE mostrar un error en el resumen y no avanzar. Al avanzar, los códigos DEBEN descartarse de la memoria y no
poder volver a mostrarse, tampoco con el regreso del navegador. El campo del primer código DEBE alcanzarse solo tras esa
confirmación. La clave y el QR DEBEN seguir disponibles hasta completar la activación. El paso NO DEBE ofrecer descarga
ni impresión.

#### Escenario: [SS89] Los diez códigos se muestran como texto seleccionable

- **DADO** una inscripción que devolvió diez códigos
- **CUANDO** llega al paso de códigos
- **ENTONCES** se muestran diez elementos de lista con texto, no imágenes, que la persona puede seleccionar

#### Escenario: [SS90] Continuar sin confirmar no avanza

- **DADO** el paso de códigos con la casilla sin marcar
- **CUANDO** la persona activa continuar
- **ENTONCES** no avanza, el botón no estaba deshabilitado, y el resumen de errores aparece con el foco y un enlace a la casilla

#### Escenario: [SS91] Confirmar permite avanzar al primer código

- **DADO** la casilla marcada
- **CUANDO** la persona activa continuar
- **ENTONCES** llega al campo del primer código

#### Escenario: [SS92] El campo del primer código no es alcanzable sin confirmar

- **DADO** el paso de códigos sin confirmar
- **CUANDO** se recorre la pantalla con el teclado completo
- **ENTONCES** no existe en el documento ningún campo de código TOTP hasta la confirmación

#### Escenario: [SS93] Los códigos no vuelven a mostrarse

- **DADO** la confirmación hecha y el avance al primer código
- **CUANDO** la persona retrocede con el botón del navegador o con un control de la pantalla
- **ENTONCES** ningún nodo del documento contiene ninguno de los diez códigos

#### Escenario: [SS94] La clave y el QR siguen disponibles hasta activar

- **DADO** el paso del primer código
- **CUANDO** la persona necesita volver a ver el QR o la clave
- **ENTONCES** están disponibles sin una petición nueva de inscripción

#### Escenario: [SS95] No hay descarga ni impresión de los códigos

- **DADO** el paso de códigos
- **CUANDO** se buscan controles de descarga, impresión o envío por correo
- **ENTONCES** no existe ninguno

### Requisito: El primer código activa la credencial y la inscripción borra el estado al terminar

El sistema DEBE enviar el primer código solo como TOTP con el token restringido de uso `mfa-enroll`. Un `200` DEBE activar
la sesión, descartar el token restringido y borrar de la memoria el identificador escrito, el secreto, el URI y los códigos
de recuperación, y llevar a la página autenticada con reemplazo de la entrada del historial. Un `401 second-factor-invalid`
DEBE mostrar el texto fijado y conservar el QR y la clave. Abandonar la inscripción por cualquier otro camino DEBE borrar el
mismo estado. Reiniciar la inscripción reemplaza el secreto en el servidor, y lo escaneado antes deja de valer
(`identity`): la aplicación DEBE decirlo en el aviso de inscripción interrumpida.

#### Escenario: [SS96] Primer código correcto

- **DADO** el primer código correcto y una respuesta `200` con la sesión
- **CUANDO** la persona lo envía
- **ENTONCES** llega a la página autenticada y el almacén de la inscripción no conserva el identificador, el secreto, el URI ni
  los códigos

#### Escenario: [SS97] Primer código incorrecto

- **DADO** una respuesta `401 second-factor-invalid` al primer código
- **CUANDO** la pantalla la procesa
- **ENTONCES** muestra el texto fijado del código TOTP y el QR y la clave siguen visibles

#### Escenario: [SS98] Abandonar la inscripción borra el estado

- **DADO** una inscripción en curso
- **CUANDO** la persona vuelve al inicio de sesión
- **ENTONCES** el almacén de la inscripción no conserva el identificador, el secreto, el URI ni los códigos

#### Escenario: [SS99] Después de la inscripción no se vuelve a ella

- **DADO** la inscripción terminada y la página autenticada visible
- **CUANDO** la persona retrocede con el botón del navegador
- **ENTONCES** no vuelve a ningún paso de la inscripción ni se emite una petición de inscripción

#### Escenario: [SS100] Reiniciar la inscripción genera un secreto nuevo

- **DADO** una inscripción interrumpida por recarga tras haber mostrado el secreto «S1»
- **CUANDO** la persona inicia sesión de nuevo y llega a la inscripción
- **ENTONCES** se hace una nueva petición de inscripción y se muestra el secreto de esa respuesta, distinto de «S1»

### Requisito: La rama `authenticated` sin segundo factor se maneja y se prueba

El sistema DEBE manejar la respuesta `200` con `status` `authenticated` al iniciar sesión, aunque tras el cambio 8 no se
produzca para una cuenta del personal: DEBE guardar el token de acceso solo en memoria y llevar a la página autenticada.

#### Escenario: [SS101] Inicio de sesión que ya trae sesión

- **DADO** una respuesta `200 authenticated` al inicio de sesión
- **CUANDO** la pantalla la procesa
- **ENTONCES** llega a la página autenticada con el token en memoria y sin pasar por la verificación ni la inscripción

#### Escenario: [SS102] La rama se ejerce en un recorrido simulado

- **DADO** el conjunto de recorridos de navegador de esta capacidad
- **CUANDO** se enumeran
- **ENTONCES** incluye uno que inicia sesión con una cuenta simulada sin segundo factor

### Requisito: Un recorrido completo no deja ningún secreto observable y se puede hacer solo con teclado

El sistema DEBE completar cada recorrido sin que ningún token, secreto ni código aparezca en los lugares que enumera el primer
requisito de esta capacidad, y sin que el secreto ni el URI aparezcan en ninguna petición saliente. Los recorridos de
inicio de sesión con TOTP y de inscripción obligatoria DEBEN poder completarse solo con teclado (`docs/ui-ux/06` §1,
criterios de aceptación; `docs/ui-ux/03` §9), con el orden del foco igual al visual y el foco visible.

#### Escenario: [SS113] Recorrido de inicio de sesión con TOTP solo con teclado

- **DADO** el inicio de sesión, la verificación y la página autenticada, y la persona sin ratón
- **CUANDO** completa el recorrido usando solo `Tab`, `Shift+Tab`, `Enter`, `Espacio` y la escritura
- **ENTONCES** llega a la página autenticada y el elemento con foco es siempre visible

#### Escenario: [SS114] Recorrido de inscripción obligatoria solo con teclado

- **DADO** el inicio de sesión que lleva a la inscripción, y la persona sin ratón
- **CUANDO** completa los tres pasos con el teclado, incluida la casilla de confirmación y la copia de la clave
- **ENTONCES** llega a la página autenticada

#### Escenario: [SS115] Pegar el código

- **DADO** el campo del código TOTP y un portapapeles con «123 456»
- **CUANDO** la persona pega con el atajo del navegador
- **ENTONCES** el campo acepta el contenido y el envío usa «123456»

#### Escenario: [SS116] Ningún recorrido deja centinelas

- **DADO** los recorridos de inicio de sesión con TOTP, con código de recuperación, de inscripción completa, de recarga, de
  cierre de sesión, de sesión vencida y de token restringido vencido, cada uno con centinelas únicos
- **CUANDO** termina cada uno
- **ENTONCES** los centinelas no aparecen en `localStorage`, `sessionStorage`, IndexedDB, la URL, `history.state` ni la
  consola
- **Y** tampoco aparecen en la caché de consultas ni en la de mutaciones, lo que se comprueba en el flujo en jsdom de SS03
- **Y** el secreto, el URI y la contraseña no aparecen en ninguna petición saliente, salvo la contraseña en el cuerpo de la
  petición de inicio de sesión

## MODIFIED Requirements

### Requisito: Los tokens, los secretos y los códigos viven solo en memoria de módulo

El sistema DEBE mantener el token de acceso, el token restringido, la contraseña, el secreto TOTP, el identificador
escrito durante la inscripción y los códigos de recuperación únicamente en memoria de módulo, en un almacén privado. NO
DEBEN aparecer en `localStorage`, `sessionStorage`, IndexedDB, la URL, el estado del historial (`history.state`), la caché
de consultas ni la de mutaciones de TanStack Query (incluidos sus datos, variables y errores), ninguna propiedad
enumerable de `window`, la consola ni ningún informe de errores (`docs/ui-ux/04` §7; `CLAUDE.md`, regla 11). La aplicación
NO DEBE escribir ninguna cookie: la cookie de sesión es de servidor y de acceso exclusivo HTTP, y la cookie CSRF solo se
lee. Los metadatos de la sesión que no son secretos pasan por TanStack Query.
(Previously: el mismo cuerpo, publicado por `admin-web-foundations-and-session`, sin los escenarios de este delta.)

#### Escenario: [SS01] Los almacenamientos no contienen secretos tras cada recorrido

- **DADO** un recorrido con valores centinela únicos para el token de acceso, el token restringido, la contraseña, el
  secreto, los diez códigos de recuperación y el código TOTP escrito
- **CUANDO** termina cada recorrido simulado de esta capacidad
- **ENTONCES** ninguno de los centinelas aparece, en ninguna forma de codificación habitual, en `localStorage`,
  `sessionStorage` ni IndexedDB

#### Escenario: [SS02] La URL y el estado del historial no contienen secretos

- **DADO** el mismo recorrido con los mismos centinelas
- **CUANDO** se recorre cada pantalla, incluidos el regreso y el avance del navegador
- **ENTONCES** ni `location.href` ni `history.state` contienen un centinela en ningún momento

#### Escenario: [SS03] La caché de consultas y la de mutaciones no contienen secretos

- **DADO** un flujo en jsdom que monta la aplicación completa con MSW y los mismos centinelas, porque producción no expone el
  `QueryClient`
- **CUANDO** se serializa el contenido completo de la caché de consultas y de la de mutaciones (datos, variables y errores)
  al terminar cada pantalla
- **ENTONCES** ningún centinela aparece en ellas

#### Escenario: [SS04] Ni la consola ni las propiedades globales contienen secretos

- **DADO** el mismo recorrido, con la captura de todos los mensajes de consola y el recorrido de las propiedades
  enumerables de `window`
- **CUANDO** termina
- **ENTONCES** ningún centinela aparece en ellos

#### Escenario: [SS05] La aplicación no escribe cookies

- **DADO** el código de la aplicación y un recorrido completo con inicio de sesión
- **CUANDO** se busca una asignación a `document.cookie` y se leen las cookies visibles a script al terminar
- **ENTONCES** no existe ninguna asignación
- **Y** la única cookie visible a script es `__Host-confia_csrf`

### Requisito: Las guardas de ruta, el foco y el título cuidan el recorrido de varias pantallas

El sistema DEBE proteger la página autenticada: sin sesión DEBE llevar al inicio de sesión. Las pantallas de verificación e
inscripción DEBEN exigir un token restringido en memoria: sin él DEBEN llevar al inicio de sesión con un aviso propio
(distinto para la inscripción). Una persona con sesión que abre el inicio de sesión, la verificación o la inscripción DEBE
llegar a la página autenticada. El token restringido y el estado de la inscripción DEBEN descartarse al salir de las
pantallas del segundo factor por cualquier camino que no sea una verificación correcta, incluidos el regreso y el avance
del navegador. Cada pantalla DEBE actualizar `document.title` con un texto del catálogo, tener un único encabezado de nivel
1 dentro de `main`, mover el foco a ese encabezado al aparecer, y el documento DEBE declarar `lang` igual al idioma activo.
(Previously: el mismo cuerpo, publicado por `admin-web-foundations-and-session`, sin los escenarios de este delta.)

#### Escenario: [SS28] Sin token restringido, la verificación lleva al inicio de sesión con su aviso

- **DADO** una persona sin token restringido
- **CUANDO** abre la ruta de verificación
- **ENTONCES** llega al inicio de sesión con el aviso de verificación interrumpida

#### Escenario: [SS29] Sin token restringido, la inscripción lleva al inicio de sesión con el aviso de inscripción

- **DADO** una persona que recarga durante la inscripción y por tanto pierde el token restringido
- **CUANDO** la aplicación vuelve a cargar la ruta de inscripción
- **ENTONCES** llega al inicio de sesión con el aviso de inscripción interrumpida
- **Y** el aviso dice que el código QR y los códigos de recuperación anteriores ya no sirven y que se generarán nuevos

#### Escenario: [SS31] Salir de las pantallas del segundo factor descarta el token restringido

- **DADO** una persona en la verificación con un token restringido
- **CUANDO** retrocede al inicio de sesión y luego avanza a la verificación con el navegador
- **ENTONCES** llega al inicio de sesión con el aviso de verificación interrumpida y no se emite ninguna petición con el
  token anterior

### Requisito: Los recorridos de navegador usan una API simulada que reproduce el contrato y se validan contra los esquemas

El sistema DEBE ofrecer recorridos de Playwright sobre `vite preview` contra una API simulada, un servidor Node detrás del proxy de Vite
(`e2e/mock-api`, puerto `8099`) que eligió la sonda del paso 0, sin un service worker en el resultado de producción. La API simulada DEBE reproducir, para las seis
operaciones, los códigos Problem, los estados y las cabeceras de `identity` y `web-edge`, y DEBE poder provocar por
configuración de la prueba: `401 authentication-failed`, `400 validation-failed` con `errors`, `429 too-many-requests` con
`Retry-After`, `503 capacity-exceeded`, una respuesta de 31 s, un token restringido vencido, `409 mfa-already-enrolled` y la
rotación del refresco con su gracia de 10 segundos. Cada respuesta simulada con cuerpo DEBE validarse con los esquemas de
`@confia/contracts/admin` antes de enviarse; una respuesta de error se valida sin su campo `instance`, que se emite como ruta relativa,
igual que el backend, hasta que el cambio 8b corrija el esquema. El cálculo del TOTP en las pruebas DEBE usar `node:crypto` según RFC 6238, sin
dependencia nueva. Antes de cerrar el diseño, la sonda DEBE registrar en `design.md` qué atributos de las cookies se
ejercitan de verdad y cuáles no; los recorridos simulados NO DEBEN presentarse como prueba de la pila real.
(Previously: el mismo cuerpo, publicado por `admin-web-foundations-and-session`, sin los escenarios de este delta.)

#### Escenario: [SS117] Existen los siete recorridos exigidos

- **DADO** la suite de recorridos de `apps/admin-web`
- **CUANDO** se enumeran
- **ENTONCES** incluye el inicio de sesión con TOTP, con código de recuperación y con inscripción obligatoria completa, la
  recarga que conserva la sesión, el cierre de sesión, la sesión vencida y el token restringido vencido

#### Escenario: [SS123] Los recorridos simulados no se presentan como prueba de la pila real

- **DADO** el informe del cambio y `docs/09-roadmap-y-fases.md`
- **CUANDO** se leen las menciones a los recorridos
- **ENTONCES** declaran que corren contra una API simulada y que la prueba contra la pila real es del cambio 11

> **Nota fechada 2026-11-07 (decisión del propietario, fase de diseño).** El simulador que entrega la parte 1 no cumple todavía todo lo que este
> requisito le pide: no tiene escenario de `400 validation-failed` con `errors` por configuración de la prueba (solo lo responde ante un cuerpo
> que el contrato rechaza), ni de `500 internal-error` con `traceId`, ni de token restringido vencido en la inscripción; devuelve el mismo secreto
> en cada inscripción de un escenario, lo que impide SS100, y no expone la clave ni los códigos de la cuenta ya inscrita que necesitan los recorridos
> con TOTP y con código de recuperación. El texto del requisito no cambia: esta parte cierra esas brechas (`design.md`, E13).

### Requisito: Ausencia de lo que pertenece a los cambios 13b-2 y 8b (brechas con destino)

El sistema NO DEBE contener en este cambio: la detección de actividad de la persona como disparador de refresco, el aviso
de inactividad a los 25 minutos con `Dialog`, el cálculo con `idleExpiresAt` y la corrección del desfase de reloj, los
avisos por vencimiento absoluto, la sincronización del cierre de sesión entre pestañas, el parámetro `redirect` y el regreso
a una ruta profunda, y «Mis sesiones activas»: son del **cambio 13b-2** (o del cambio que el propietario asigne con la
decisión P4). NO DEBE contener la inscripción ni la baja voluntarias del segundo factor con sesión completa, la creación del
primer Super Admin ni el restablecimiento administrativo de contraseña: son del **cambio 8b**. La brecha aceptada hasta
13b-2 es que la página mínima no detecta por sí sola el vencimiento por inactividad: sigue en pantalla, sin datos
personales, y una recarga lleva al inicio de sesión con el mensaje de sesión vencida.
(Previously: el mismo cuerpo, publicado por `admin-web-foundations-and-session`, sin los escenarios de este delta.)

#### Escenario: [SS127] No existen las funcionalidades del cambio 8b

- **DADO** la aplicación y su cliente de API
- **CUANDO** se buscan pantallas u operaciones de inscripción o baja voluntarias del segundo factor, de creación del primer
  Super Admin y de restablecimiento administrativo de contraseña
- **ENTONCES** no existe ninguna
- **Y** este escenario deja de ser cierto el día que el cambio 8b las entregue

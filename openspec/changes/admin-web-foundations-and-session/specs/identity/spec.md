# Delta para Identidad

- **Estado:** actualizado el 2026-11-05 con los cambios de especificación que el propietario aprobó junto con el diseño
- **Cambio:** `admin-web-foundations-and-session` (F0, cambio 13b-1, parte 1 de 2). El reemplazo de la ausencia de prueba de extremo a extremo vive en `admin-web-staff-sign-in`.
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-04, con P2 (QR más clave manual, emisor «CONFIA», etiqueta
  igual al identificador escrito, generados solo en el navegador), P6 (e2e simulada ahora; la de pila real, brecha fechada del
  cambio 11) y la resolución de Q2 (la cuenta de personal para esa e2e la crea el cambio 8b) vinculantes

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `I304` en adelante, a continuación del máximo del delta del cambio 8 (`I303`), que a su vez
  sigue al de la especificación vigente (`I277`). Los escenarios que se conservan de la especificación vigente y no llevaban
  número no lo llevan.
- Este delta no cambia ningún comportamiento del backend: el contrato de los endpoints de `identity` y de `web-edge` queda
  intacto. Cambia una afirmación: que el URI `otpauth://` y el QR dependen de un emisor y una etiqueta «por institución».
- La afirmación de que no existe prueba de extremo a extremo del inicio de sesión con contraseña sigue siendo cierta al terminar esta
  parte, que no tiene pantalla de inicio de sesión. Por eso su reemplazo vive en `admin-web-staff-sign-in`, y esta parte solo agrega el requisito que
  exige validar las respuestas simuladas.
- Este delta y el del cambio 8 sobre `identity` no se solapan: el requisito «Inscripción y verificación del segundo factor TOTP
  según RFC 6238» no figura en el del cambio 8.
- **Valor fijado por el diseño** (`design.md`, D11): `otpauth://totp/CONFIA:<encodeURIComponent(identificador)>?secret=<secreto>&issuer=CONFIA`,
  sin `algorithm`, `digits` ni `period`.

## ADDED Requirements

### Requisito: La API simulada de los recorridos valida cada respuesta con los esquemas del contrato

El sistema DEBE validar con los esquemas de `@confia/contracts/admin`, antes de enviarla, cada respuesta con cuerpo de la API simulada
que usan los recorridos de Playwright, y una respuesta que no los cumpla DEBE hacer fallar la prueba. El campo `instance` de una respuesta
de error queda excluido de esa validación, porque el esquema generado exige `zod.url()` y el backend lo emite como ruta relativa; el dueño de
corregir el esquema es el cambio 8b.

#### Escenario: [I306] Las respuestas simuladas se validan con los esquemas del contrato

- **DADO** la API simulada de los recorridos
- **CUANDO** envía una respuesta con cuerpo
- **ENTONCES** la valida antes con el esquema correspondiente de `@confia/contracts/admin`, y una respuesta que no lo cumple
  hace fallar la prueba
- **Y** una respuesta de error se valida con `ProblemDetail` sin el campo `instance`, que se emite como ruta relativa, igual que el
  backend, hasta que el cambio 8b corrija el esquema a `uri-reference`

## MODIFIED Requirements

### Requisito: Inscripción y verificación del segundo factor TOTP según RFC 6238

El sistema DEBE generar, al inscribir el segundo factor de una cuenta, un secreto TOTP de 20 bytes
aleatorios, y DEBE verificar un código presentado con el algoritmo TOTP de RFC 6238, SHA-1, 6
dígitos, periodo de 30 segundos y ventana de tolerancia de ±1 periodo (`docs/03-seguridad.md`
§4.3). El sistema DEBE almacenar, por cuenta, el último contador aceptado, y NO DEBE aceptar
ningún código cuyo contador sea menor o igual al último aceptado, aunque el código en sí sea
matemáticamente válido para ese contador.

El sistema DEBE devolver el secreto generado al llamador de la inscripción, **una única vez y en el
retorno de esa llamada**, codificado en base32 según RFC 4648 sin relleno, que es la forma que toda
aplicación de autenticación acepta como entrada manual. Sin esa devolución ninguna aplicación puede
aprender el secreto, y el segundo factor queda inscrito en la base pero imposible de activar en la
práctica. NO DEBE existir ninguna consulta posterior que recupere el secreto en claro: igual que los
códigos de recuperación, después de ese retorno solo persiste su forma cifrada.

El backend NO DEBE construir el identificador URI `otpauth://` ni su representación como código QR, y la
respuesta de inscripción NO DEBE contenerlos: los construye el navegador, en la aplicación `apps/admin-web`, con el emisor
fijo «CONFIA» y como etiqueta el identificador que la persona escribió al iniciar sesión (decisión P2), porque la sesión solo
devuelve `institutionId` y no existe un endpoint con el nombre de la institución. El secreto y el URI NO DEBEN salir del
navegador (capacidad `staff-sign-in`).
(Previously: la construcción del URI y del QR quedaban fuera de este requisito porque exigían «un emisor y una etiqueta por
institución, que son información del módulo de organización», y pertenecían al cambio que construyera la pantalla de
inscripción.)

#### Escenario: La inscripción devuelve el secreto en base32 una única vez

- **DADO** que la cuenta `sofia.mejia@colegio.edu.hn` no tiene todavía segundo factor inscrito
- **CUANDO** se inscribe su segundo factor
- **ENTONCES** el retorno de esa llamada incluye el secreto TOTP codificado en base32 según RFC 4648,
  sin relleno, de 32 caracteres para los 20 bytes del secreto
- **Y** ese secreto en base32 corresponde exactamente al mismo secreto que quedó cifrado en la
  credencial almacenada, no a uno distinto
- **Y** ninguna consulta posterior a `identity_mfa_totp_credential` puede recuperar el secreto en
  claro

#### Escenario: Un código válido dentro de la ventana de tolerancia se acepta

- **DADO** el secreto TOTP inscrito para `sofia.mejia@colegio.edu.hn` y el instante
  `2026-10-05T08:00:00Z`
- **CUANDO** presenta el código de 6 dígitos correspondiente al periodo de 30 segundos
  inmediatamente anterior a ese instante
- **ENTONCES** el sistema lo acepta, por estar dentro de la ventana de tolerancia de ±1 periodo

#### Escenario: Un código ya aceptado no puede reutilizarse, aunque siga siendo matemáticamente válido

- **DADO** que el código correspondiente al contador `58204` de la cuenta
  `sofia.mejia@colegio.edu.hn` ya fue aceptado
- **CUANDO** se presenta de nuevo ese mismo código, todavía dentro de su ventana de 30 segundos de
  vigencia
- **ENTONCES** el sistema lo rechaza, porque su contador no es mayor que el último contador
  aceptado

#### Escenario: [I310] La respuesta de inscripción no contiene el URI ni el QR

- **DADO** el esquema `EnrollmentResponse` de `@confia/contracts/admin` y una respuesta de inscripción de la API
- **CUANDO** se inspeccionan sus propiedades
- **ENTONCES** son exactamente `secret` y `recoveryCodes`
- **Y** ninguna contiene la cadena `otpauth` ni una imagen

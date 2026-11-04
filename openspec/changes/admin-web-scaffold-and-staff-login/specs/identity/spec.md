# Delta para Identidad

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `admin-web-scaffold-and-staff-login` (F0, cambio 13b-1)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-04, con P2 (QR más clave manual, emisor «CONFIA», etiqueta
  igual al identificador escrito, generados solo en el navegador), P6 (e2e simulada ahora; la de pila real, brecha fechada del
  cambio 11) y la resolución de Q2 (la cuenta de personal para esa e2e la crea el cambio 8b) vinculantes

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `I304` en adelante, a continuación del máximo del delta del cambio 8 (`I303`), que a su vez
  sigue al de la especificación vigente (`I277`). Los escenarios que se conservan de la especificación vigente y no llevaban
  número no lo llevan.
- Este delta no cambia ningún comportamiento del backend: el contrato de los endpoints de `identity` y de `web-edge` queda
  intacto. Cambia dos afirmaciones que dejan de ser ciertas con la entrega de la aplicación: que no existe ninguna prueba de
  extremo a extremo del inicio de sesión, y que el URI `otpauth://` y el QR dependen de un emisor y una etiqueta «por
  institución».
- Este delta y el del cambio 8 sobre `identity` no se solapan: el requisito «Inscripción y verificación del segundo factor TOTP
  según RFC 6238» y el de la ausencia de la prueba de extremo a extremo no figuran en el del cambio 8.
- **Valores que fija el diseño y que esta especificación no fija:** la composición exacta del URI (si antepone el emisor a la
  etiqueta y los parámetros `algorithm`, `digits` y `period`), que el diseño de `staff-sign-in` fija.

## RENAMED Requirements

### Requisito: Ausencia de prueba de extremo a extremo con Playwright del flujo de autenticación (brecha con destino: F1 o posterior) → Prueba de extremo a extremo simulada del flujo de autenticación y ausencia de la prueba contra la pila real (brecha con destino: cambio 11)

(Reason: el cambio 13b-1 entrega la aplicación `apps/admin-web` con recorridos de Playwright contra una API simulada, así que
la afirmación de que no existe ninguna prueba de extremo a extremo deja de ser cierta; la prueba contra la pila real, con
cookies reales, pasa al cambio 11 como brecha fechada, decisión P6.)
(Migration: el escenario anterior, sin número, se reemplaza por los escenarios I304 a I309; las referencias a «F1 o posterior»
pasan a «cambio 11».)

## MODIFIED Requirements

### Requisito: Prueba de extremo a extremo simulada del flujo de autenticación y ausencia de la prueba contra la pila real (brecha con destino: cambio 11)

El sistema DEBE contar con recorridos de Playwright que ejerciten el flujo de autenticación del personal de principio a fin
contra una API **simulada** que reproduce el contrato de `identity` y de `web-edge`, y cuyas respuestas se validan con los
esquemas de `@confia/contracts/admin` (capacidad `staff-sign-in`). El sistema NO DEBE contar todavía con ninguna prueba de
extremo a extremo del flujo contra la pila real, con las cookies reales del backend: porque no existe una cuenta de personal
sembrada (la cuenta se guarda con Argon2id y un *pepper* de entorno, y su creación es del mecanismo del primer Super Admin
del **cambio 8b**) y porque no hay infraestructura de API en la integración continua hasta el **cambio 11**. La brecha se
fecha el **2026-11-04**, tiene como dueño el **cambio 11**, que usa la cuenta que el cambio 8b crea, y su escenario queda
escrito como diferido. Mientras tanto, nadie puede probar el inicio de sesión a mano contra el backend, y los recorridos
simulados NO DEBEN presentarse como prueba de la pila real.
(Previously: «el sistema NO DEBE contar con ninguna prueba de extremo a extremo con Playwright del flujo completo de
autenticación con contraseña, porque no existe ninguna aplicación React desplegable todavía; el destino es F1 o una fase
posterior».)

#### Escenario: [I304] Existe el recorrido simulado del inicio de sesión con segundo factor

- **DADO** el árbol de pruebas de `apps/admin-web/e2e`
- **CUANDO** se busca una prueba de Playwright que ejercite el inicio de sesión con contraseña y la verificación con un código TOTP
  de principio a fin
- **ENTONCES** existe, y llega a la página autenticada contra la API simulada

#### Escenario: [I305] Existen los siete recorridos simulados

- **DADO** el mismo árbol de pruebas
- **CUANDO** se enumeran sus recorridos
- **ENTONCES** incluye el inicio de sesión con TOTP, con código de recuperación y con inscripción obligatoria completa, la
  recarga que conserva la sesión, el cierre de sesión, la sesión vencida y el token restringido vencido

#### Escenario: [I306] Las respuestas simuladas se validan con los esquemas del contrato

- **DADO** la API simulada de los recorridos
- **CUANDO** envía una respuesta con cuerpo
- **ENTONCES** la valida antes con el esquema correspondiente de `@confia/contracts/admin`, y una respuesta que no lo cumple
  hace fallar la prueba

#### Escenario: [I307] Ninguna prueba ejercita el flujo contra la pila real

- **DADO** el árbol de pruebas del repositorio
- **CUANDO** se busca una prueba que ejercite el inicio de sesión del personal contra el backend real, con las cookies reales
- **ENTONCES** no existe ninguna
- **Y** este escenario deja de ser cierto el día que el cambio 11 entregue esa prueba

#### Escenario: [I308] La brecha está fechada y asignada

- **DADO** esta especificación y `docs/09-roadmap-y-fases.md`
- **CUANDO** se lee la brecha de la prueba contra la pila real
- **ENTONCES** declara la fecha 2026-11-04, el cambio 11 como dueño y el cambio 8b como el que crea la cuenta de personal que
  usa

#### Escenario: [I309] Escenario diferido al cambio 11: inicio de sesión real con inscripción de MFA

- **DADO** (diferido; lo ejercita el cambio 11, no este) el backend real arrancado, la aplicación servida bajo el mismo origen
  que la API, y la cuenta de personal creada por el cambio 8b sin segundo factor
- **CUANDO** una persona inicia sesión en Chromium, completa la inscripción obligatoria con un código TOTP calculado en la
  prueba y recarga la página
- **ENTONCES** las cookies reales `__Secure-confia_admin_sid` (de acceso exclusivo HTTP) y `__Host-confia_csrf` (legible) se
  guardan con los atributos de `web-edge`, la recarga restaura la sesión con un único refresco y el cierre de sesión borra
  ambas cookies
- **Y** este escenario no tiene aserción automática hasta que el cambio 11 lo implemente

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

#### Escenario: [I311] El navegador construye el URI con el secreto sin transformarlo

- **DADO** un secreto base32 de 32 caracteres devuelto por la inscripción y el identificador escrito al iniciar sesión
- **CUANDO** la aplicación construye el URI `otpauth://` para el QR
- **ENTONCES** el parámetro `secret` del URI es idéntico al secreto devuelto, el parámetro `issuer` es «CONFIA» y la etiqueta
  decodificada es idéntica al identificador escrito
- **Y** ninguna petición saliente de la aplicación contiene el secreto ni el URI

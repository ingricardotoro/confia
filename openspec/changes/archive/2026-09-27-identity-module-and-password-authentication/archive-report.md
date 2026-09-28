# Informe de archivado: `identity-module-and-password-authentication`

- **Fecha de archivado:** 2026-09-27
- **Cambio 7 de F0, primera de tres partes.** Las otras dos, `mfa-totp-and-password-recovery` y
  `session-tokens-and-web-layer`, siguen pendientes.
- **Tareas:** 12 de 12 cerradas.
- **Entregado en ocho pull requests** fusionados a `main`: #38 y #39 (corte C1); #41, #42 y #43
  (corte C2); #44 y #45 (corte C3a); #46, #47 y #48 (corte C3b). Más #40, la condición de aceptación
  del crecimiento de `identity_login_backoff`.

## Capacidades publicadas

La fusión de los deltas se hizo **a mano**, como en los tres cambios anteriores, porque
`sdd-archive-compose` no sabe leer los encabezados `### Requisito:` en español (defecto reportado en
`Gentleman-Programming/gentle-ai#4797`). Se aplicaron las tres comprobaciones del precedente:
**prefijo original intacto byte a byte**, **SHA-256 de los bytes añadidos idéntico al del delta**, y
**los conteos cuadran**.

| Capacidad | Antes | Después | Delta aplicado |
|---|---|---|---|
| `identity` | 8 req / 16 escen | **19 req / 37 escen** | 11 añadidos (16 escen) y 1 **sustituido** (7 escen, reemplazando 2) |
| `build-integrity` | 40 req / 94 escen | **42 req / 101 escen** | 2 añadidos (6 escen) y 1 **sustituido** (2 escen, reemplazando 1) |

Las dos sustituciones se verificaron como tales y no como añadidos: el requisito «Bloqueo por
intentos fallidos con retroceso exponencial» **desapareció** y «Retardo por intentos fallidos con
retroceso exponencial» ocupa su lugar en la misma posición; y la nota de diferimiento del escenario
W2 **se retiró**, con el escenario «Cada módulo usa solo su propio dominio» en su sitio.

## Deudas que este cambio cierra

- **W2 del cambio 5**, abierta desde el 2026-09-21: el escenario «Cada módulo usa solo su propio
  dominio» exigía **dos** módulos de negocio para no ser cierto de vacío. Con `identity` junto a
  `organization` la regla tiene contenido por primera vez, y la guarda de no vacuidad lo hace
  verificable.
- **El escritor de producción de `shared_audit_log`**, que el cambio 5B excluyó con el motivo escrito
  «no hay ninguna acción sensible en producción que auditar hasta los cambios 7 y 8». Este cambio es
  su primer dueño real.
- **El contrato de ADR-0009 sobre el origen de `institution_id`**, que no tenía ninguna prueba de
  integración hasta `LoginInstitutionIT`.

## Decisiones del propietario, con su motivo

1. **Dividir el cambio 7 en tres cambios secuenciales** (2026-09-24, en dos pasos). El original
   pronosticaba 12 a 13 tareas contra un límite de quince, y la propuesta de su primera mitad
   pronosticó 14 a 16. Se partió **antes de continuar a diseño**, como pide la regla de tamaño.
2. **Gobierna el modelo de retroceso de `docs/03` §4.4 sobre el requisito publicado** (2026-09-24),
   **en contra de la recomendación de la propuesta**. Tres razones: un retardo se aplica igual a una
   cuenta inexistente y un bloqueo no, así que el modelo de retardo **disuelve** la contradicción con
   la no-enumeración en vez de administrarla; el requisito publicado no declaraba tope, y un
   crecimiento exponencial sin techo es un vector de denegación de servicio; y §4.4 ya explicaba por
   escrito por qué había rechazado el bloqueo.
3. **El estado del retroceso vive en PostgreSQL, no en Redis** (2026-09-24), por atomicidad con su
   asiento de auditoría, aceptando de forma consciente apartarse de una frase de la misma sección
   §4.4 que se acababa de declarar autoritativa.
4. **Nota editorial sobre §4.1 autorizada** (2026-09-24): Argon2id sin Spring Security.

## Decisiones de arquitectura

- **El retardo se calcula dentro de la transacción y se materializa fuera.** El caso de uso devuelve
  la duración exigible tras confirmar; el borde HTTP espera. Un retardo de 900 segundos cuesta **cero
  recursos de PostgreSQL**, y la alternativa literal —dormir el hilo con la transacción abierta—
  habría convertido el control anti-fuerza-bruta en un amplificador de denegación de servicio. Una
  regla de ArchUnit prohíbe cualquier primitiva de espera bajo `com.confia.identity..`, así que la
  propiedad queda verificada y no como nota de diseño.
- **Dos tablas, no una.** El estado del retroceso se indexa por una huella con llave del
  identificador **presentado**, no por la cuenta, y existe también para identificadores sin cuenta.
  Eso hace que el camino del retroceso sea **idéntico** en ambos casos —misma sentencia, mismo
  bloqueo, mismo número de asientos—, y convierte la uniformidad que exige §4.6 en una **propiedad
  estructural** en vez de una promesa.
- **Argon2id sobre Bouncy Castle directo** (decisión 6 del diseño), porque es la única de las dos API
  candidatas que expone `withSecret(byte[])`, que es como §4.1 exige aplicar la pimienta. Verificado
  con `javap`, no supuesto: el codificador de Spring tiene un único constructor de cinco enteros y
  ningún parámetro de secreto, y además compila contra las mismas tres clases de Bouncy Castle sin
  declararlas, de modo que usarlo costaría dos artefactos en vez de uno y seguiría sin alcanzar la
  pimienta.
- **`@NamedInterface` sobre `shared.security` y `shared.audit`** (ADR-0022), sin lo cual el módulo
  `identity` no puede consumir el componente transaccional: la sonda S1 lo demostró rompiendo la
  construcción antes de escribir código.

## Lo que este cambio deja escrito para los siguientes

- **Condición dura de aceptación de `session-tokens-and-web-layer`** (en
  `openspec/changes/foundations-plan/exploration.md`, fusionada en #40): ese cambio **no debe**
  fusionar un endpoint de inicio de sesión que escriba en `identity_login_backoff` sin el control por
  dirección IP de §4.4 —o un tope equivalente— existente, probado y operativo. Se escribió como
  condición y no como «pregunta abierta» porque una pregunta abierta no obliga a nadie.
- **La purga de `identity_login_backoff`**, con dueño propuesto en el cambio que introduzca
  mantenimiento programado con `db-scheduler` (ADR-0016), y plazo **antes de F1**.
- **El ciclo de vida del hasher:** `BouncyCastleArgon2PasswordHasher` ejecuta un Argon2id completo en
  su constructor, para construir el señuelo. Registrarlo **por solicitud** en vez de como singleton
  pagaría ese costo en cada instanciación, que es un vector de agotamiento de recursos barato de
  evitar.
- **El tipo sellado `AuthenticationResult`** tiene dos desenlaces, y
  `mfa-totp-and-password-recovery` tendrá que **modificar** su cláusula `permits`, no extenderla desde
  fuera.

  > **Corrección del 2026-09-27, antes de fusionar este informe.** La versión anterior de este párrafo
  > decía que la modificación «es aceptable porque el compilador rompe la construcción hasta que cada
  > `switch` cubra los casos nuevos». **Eso no es cierto hoy, y lo escribió el orquestador.** La
  > exploración de `mfa-totp-and-password-recovery` fue a comprobarlo y encontró que en código de
  > producción **no existe ningún `switch` exhaustivo** sobre este tipo: los dos únicos consumidores
  > son `result instanceof Authenticated` en `AuthenticateWithPassword.java:155` y `:175`, verificado
  > por `grep`.
  >
  > Un `instanceof` **no fuerza exhaustividad**. Añadir `SecondFactorRequired` al `permits` sin tocar
  > esas dos líneas compila sin una advertencia y trata el desenlace nuevo como si **no** estuviera
  > autenticado, es decir como un fallo, en los dos sitios donde importa: el que decide el estado del
  > retroceso (línea 155) y el que decide qué se audita (línea 175). Un segundo factor pendiente
  > contaría como intento fallido y avanzaría el contador.
  >
  > La red de seguridad que este párrafo prometía es **aspiracional, no construida**. Convertir esos
  > dos `instanceof` en un `switch` exhaustivo sin `default` es trabajo de
  > `mfa-totp-and-password-recovery`, y hasta que ocurra la garantía no existe. El Javadoc de
  > `AuthenticationResult` arrastra la misma imprecisión y debe corregirse en ese cambio.

## Verificación final

`./mvnw -B verify` sobre la punta de la cadena, antes de fusionar: **BUILD SUCCESS**, 177 pruebas
unitarias de `kernel`, 267 de `app` y **116 de integración**, cero fallos, cobertura cumplida en los
dos niveles, 3 min 54 s — muy por debajo del presupuesto de ocho minutos de la suite `*IT.java`.

Integración continua en verde en los ocho pull requests, tanto la construcción como el escaneo de
vulnerabilidades.

## Las tres revisiones, y el patrón que comparten

Cada corte tuvo revisión previa a la fusión, y **cada una encontró un hallazgo bloqueante**:

| Corte | Bloqueante |
|---|---|
| C1 | faltaba la prueba de aislamiento de fila de `identity_login_backoff` |
| C2 | `Argon2PhcCodec` interpolaba el hash completo en tres mensajes de excepción |
| C3 | `AuthenticationCommand`, un `record`, filtraba la contraseña por su `toString()` generado |

**Los tres son la misma familia: verificación que pasaba sin alcanzar el caso peligroso.** Ninguno
era un error de lógica. En dos de los tres la prueba correcta ya existía y solo le faltaba llegar al
caso que importaba: la prueba de aislamiento escrita para una tabla y no para la otra; y la prueba de
redacción que **construía** el objeto que filtraba sin recoger nunca su texto.

El tercero merece una nota aparte: **nadie escribió esa fuga, la escribió el lenguaje.** Un `record`
de Java genera `toString()` con todos sus componentes, así que la declaración se leía correcta y
sobrevivió a tres revisiones. El módulo ya lo evitaba en todas las demás clases que cargan secretos.

Por eso el **control negativo** fue la herramienta decisiva: es lo único que distingue una prueba que
detecta de una que pasa. Se aplicó a cada corrección —neutralizando la política de fila, devolviendo
la interpolación al mensaje, devolviendo la contraseña al `toString()`— y en los tres casos la prueba
nueva falló y la vieja siguió pasando, que es exactamente la evidencia que hacía falta.

Se corrigió además, en la revisión de C3, una comprobación que **no podía fallar**: una regla por
reflexión sobre nombres de parámetro, inútil sin la bandera `-parameters` del compilador, ausente en
este POM. Se eliminó en vez de dejarla como ceremonia que parece cobertura.

## Lo que queda declarado sin demostrar

Dicho aquí para que no se pierda, y sin presentarlo como verificado:

- **Dos reglas de ArchUnit de `IdentityScopeExclusionInventoryTest`** —red y registro— **no tienen
  control negativo ejecutado**. El riesgo es bajo, porque usan la API de dependencias de ArchUnit y no
  una comparación de texto, pero no es cero.
- **Falta la prueba de aislamiento entre instituciones a nivel de adaptador para
  `identity_login_backoff`.** No hay vacío de control real: `IdentityRowSecurityIT` lo demuestra a
  nivel de esquema.
- **Las pruebas de extremo a extremo con Playwright** que `docs/03` exige para el flujo completo no
  pueden ejecutarse: no existe interfaz contra la cual correrlas. Lo que este cambio demuestra es
  contrato de dominio y aplicación, más integración real de base de datos.

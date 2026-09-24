# Delta para Identidad

## ADDED Requirements

### Requisito: Resultado tipado de la autenticación con dos desenlaces

El sistema DEBE representar el resultado del caso de uso de autenticación con contraseña como un
tipo cerrado con exactamente dos desenlaces posibles: `Authenticated`, que lleva el identificador
del usuario y el identificador de la institución, y `Rejected`, que lleva un motivo interno de
rechazo, registrado en la bitácora de auditoría y nunca expuesto a quien llama. Ninguno de los dos
desenlaces DEBE llevar un campo de alcance de autorización.

#### Escenario: Contraseña correcta produce el desenlace `Authenticated` sin campo de alcance

- **DADO** `maria.lopez@colegio.edu.hn` con contraseña vigente
- **CUANDO** el caso de uso de autenticación se invoca con esa contraseña
- **ENTONCES** devuelve el desenlace `Authenticated` con el identificador del usuario y el de la
  institución
- **Y** ese desenlace no lleva ningún campo de alcance de autorización

#### Escenario: Toda causa de rechazo produce el desenlace `Rejected` con un motivo interno

- **DADO** dos intentos de autenticación distintos: contraseña incorrecta contra una cuenta
  existente, y un correo que no corresponde a ninguna cuenta
- **CUANDO** el caso de uso procesa cada uno por separado
- **ENTONCES** ambos devuelven el desenlace `Rejected`, cada uno con un motivo interno distinto que
  la auditoría registra
- **Y** ningún campo del resultado expuesto a quien llama distingue entre los dos motivos
- **Y** un retroceso vigente **no** es causa de `Rejected`: retarda la respuesta y deja que el
  intento se resuelva por sus credenciales

### Requisito: Verificación Argon2id contra un hash señuelo cuando la cuenta no existe

Cuando el identificador de cuenta presentado no corresponde a ninguna cuenta existente, el sistema
DEBE ejecutar igualmente una verificación Argon2id de la contraseña presentada contra un hash
señuelo constante, con los mismos parámetros vigentes de Argon2id que una verificación real, de
modo que la ejecución de esa verificación no dependa de si la cuenta existe.

#### Escenario: La verificación señuelo se ejecuta ante una cuenta inexistente

- **DADO** que `nadie.registrado@colegio.edu.hn` no corresponde a ninguna cuenta
- **CUANDO** se intenta autenticar con ese correo y cualquier contraseña
- **ENTONCES** el sistema ejecuta la verificación Argon2id contra el hash señuelo constante,
  verificable por interacción con el puerto de verificación, antes de devolver el desenlace
  `Rejected`
- **Y** el motivo interno del rechazo registrado en la auditoría es que la cuenta no existe, nunca
  expuesto a quien llama

### Requisito: Estado del retroceso persistido en PostgreSQL, con atomicidad de efecto y auditoría

El sistema DEBE persistir el estado del retroceso por cuenta —contador de intentos fallidos
consecutivos y marca de tiempo del último intento— en PostgreSQL, y DEBE registrar el intento
fallido, calcular el retardo resultante y escribir el asiento de auditoría correspondiente dentro
de una única transacción. Si esa transacción no confirma, ninguno de esos tres efectos DEBE
sobrevivir.

#### Escenario: El efecto y su asiento de auditoría se confirman o revierten juntos

- **DADO** un intento fallido real contra una cuenta existente, procesado dentro de una transacción
  que a continuación falla de forma determinista antes de confirmar
- **CUANDO** se inspecciona el estado tras la reversión
- **ENTONCES** ni el contador de fallos de esa cuenta avanzó
- **Y** no existe ningún asiento nuevo en la bitácora de auditoría para ese intento

#### Escenario: Dos intentos fallidos concurrentes contra la misma cuenta no pierden ninguna escritura

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con un intento fallido ya registrado
- **CUANDO** dos intentos fallidos adicionales contra la misma cuenta se ejecutan de forma
  concurrente y sincronizada
- **ENTONCES** el contador final de la cuenta refleja los tres fallos
- **Y** ninguna de las dos escrituras concurrentes se pierde

### Requisito: La institución previa a la autenticación proviene de la configuración del proceso

La institución que el caso de uso de autenticación usa para resolver la cuenta, y contra la que la
política de seguridad a nivel de fila evalúa esa resolución, DEBE provenir de la configuración del
proceso del servidor. NO DEBE provenir del cuerpo de la solicitud, de una cabecera ni de un
parámetro de consulta, porque el inicio de sesión ocurre antes de que exista un token autenticado
del que derivarla.

#### Escenario: La institución se resuelve de la configuración del proceso, nunca de la solicitud

- **DADO** un proceso configurado con la institución `d290f1ee-6c54-4b01-90e6-d701748f0851`
- **Y** una solicitud de autenticación que declara, en un campo de su propio cuerpo, la institución
  distinta `5a6a5e02-1c2e-4e3a-9d3f-6b2b3a1e9f10`
- **CUANDO** se invoca el caso de uso de autenticación con esa solicitud
- **ENTONCES** el sistema resuelve la cuenta usando la institución
  `d290f1ee-6c54-4b01-90e6-d701748f0851` de la configuración del proceso
- **Y** ignora por completo la institución declarada en el cuerpo de la solicitud

### Requisito: Puerto y adaptador de escritura de la bitácora de auditoría

El sistema DEBE proveer, junto al puerto existente de lectura de `shared_audit_log`, un puerto y un
adaptador de escritura, que produzcan, dentro de la misma transacción que su efecto, al menos los
eventos de autenticación exitosa, autenticación fallida y ciclo de retroceso. El adaptador de
escritura NO DEBE calcular `prev_hash` ni `row_hash`: el disparador de encadenamiento existente de
la tabla los asigna.

#### Escenario: El primer escritor de producción de `shared_audit_log`

- **DADO** que, hasta este cambio, ningún proceso de producción escribe en `shared_audit_log`
- **CUANDO** el caso de uso de autenticación registra un intento exitoso, uno fallido, o un ciclo de
  retroceso
- **ENTONCES** el adaptador inserta la fila correspondiente sin calcular `prev_hash` ni `row_hash`
- **Y** el disparador de encadenamiento existente la encadena a la cadena de su institución

### Requisito: Ningún secreto de este módulo es observable en registros, excepciones ni pruebas

El sistema NO DEBE hacer observable, en ningún mensaje de registro, mensaje de excepción,
representación textual (`toString()` o equivalente) ni salida de una prueba fallida, la contraseña
en claro, el hash Argon2id resultante, ni la pimienta de Argon2id.

#### Escenario: Ningún registro ni excepción expone la contraseña, el hash o la pimienta

- **DADO** un intento de autenticación con la contraseña `Segura#2026` contra
  `maria.lopez@colegio.edu.hn`
- **CUANDO** el sistema procesa el intento y, en cualquier punto de ese procesamiento, produce un
  mensaje de registro o lanza una excepción relacionada
- **ENTONCES** ninguno de esos mensajes contiene la contraseña en claro, el hash Argon2id calculado
  ni la pimienta
- **Y** eso se verifica por inspección del texto producido, no por confianza en el diseño

### Requisito: Ausencia de la dimensión por dirección IP del retroceso exponencial (brecha con destino: `session-tokens-and-web-layer` y cambio 11)

El sistema NO DEBE aplicar, en esta parte del cambio, ninguna de las tres reglas de dimensión por
dirección IP de `docs/03-seguridad.md` §4.4 —límite por IP, límite por IP contra cuentas distintas,
ni el indicador de relleno de credenciales—, porque una dirección IP solo existe en el borde HTTP y
este cambio no entrega ninguna capa `web`. El control por IP es responsabilidad de
**`session-tokens-and-web-layer`**, y su aprovisionamiento sobre Redis, del **cambio 11**
(`containerization-and-cicd-pipeline`).

#### Escenario: Ninguna regla de dirección IP se aplica todavía

- **DADO** el caso de uso de autenticación de este cambio, invocado sin ningún concepto de dirección
  IP
- **CUANDO** se ejecutan diez intentos fallidos consecutivos como si vinieran de la misma dirección
  IP, contra cinco cuentas distintas
- **ENTONCES** el sistema no aplica ningún límite ni indicador por dirección IP, porque ese control
  no existe todavía en esta parte del cambio
- **Y** este escenario deja de ser cierto el día que `session-tokens-and-web-layer` entregue el
  control, con el aprovisionamiento del cambio 11

### Requisito: Ausencia de verificación contra contraseñas comprometidas y de rehash transparente (brecha con destino: cambio 8 o un cambio de identidad posterior)

El sistema NO DEBE verificar, en esta parte del cambio, la contraseña presentada contra ninguna
lista de contraseñas comprometidas de `docs/03-seguridad.md` §4.2, ni recalcular el hash almacenado
cuando sus parámetros de Argon2id difieren de los vigentes según §4.1, porque ninguno de los ocho
requisitos publicados de `identity` los exige. El destino de ambos controles es **el cambio 8 o un
cambio de identidad posterior**.

#### Escenario: Ninguna verificación contra contraseñas comprometidas ocurre durante la autenticación

- **DADO** un intento de autenticación con una contraseña presente en listas públicas de
  contraseñas filtradas, contra una cuenta cuya contraseña vigente coincide con ese valor
- **CUANDO** el sistema verifica la combinación
- **ENTONCES** el resultado depende únicamente de si la contraseña coincide con el hash almacenado,
  sin ninguna consulta a una lista de contraseñas comprometidas ni a un servicio externo
- **Y** este escenario deja de ser cierto el día que el cambio 8 o un cambio de identidad posterior
  entregue la verificación

#### Escenario: El hash almacenado no se recalcula aunque sus parámetros difieran de los vigentes

- **DADO** una cuenta cuyo hash almacenado se calculó con parámetros de Argon2id distintos de los
  vigentes en el sistema
- **CUANDO** esa cuenta se autentica con éxito
- **ENTONCES** el sistema no recalcula ni sustituye el hash almacenado
- **Y** este escenario deja de ser cierto el día que un cambio posterior entregue el rehash
  transparente

### Requisito: Ausencia de calibración de Argon2id en servidor real (brecha con destino: cambio 11)

El sistema NO DEBE declarar, en esta parte del cambio, los parámetros de Argon2id como calibrados
contra un servidor de producción real (`docs/03-seguridad.md` §4.1, «Calibración obligatoria antes
de producción»), porque no existe ningún entorno desplegado. El destino es **el cambio 11**, que
registra el valor elegido en un ADR con fecha y hardware.

#### Escenario: Los parámetros vigentes son el piso declarado, no un valor calibrado

- **DADO** los parámetros de Argon2id que este cambio declara
- **CUANDO** se pregunta si fueron calibrados contra el servidor de producción real
- **ENTONCES** la respuesta es que no, porque ningún entorno desplegado existe todavía
- **Y** este escenario deja de ser cierto el día que el cambio 11 registre la calibración en un ADR
  con fecha y hardware

### Requisito: Ausencia de prueba de extremo a extremo con Playwright del flujo de autenticación (brecha con destino: F1 o posterior)

El sistema NO DEBE contar, en esta parte del cambio, con ninguna prueba de extremo a extremo con
Playwright del flujo completo de autenticación con contraseña, porque no existe ninguna aplicación
React desplegable todavía. El destino es **F1 o una fase posterior**, cuando exista la pantalla.

#### Escenario: Ninguna prueba de extremo a extremo ejercita el flujo de autenticación

- **DADO** el árbol de pruebas de este cambio
- **CUANDO** se busca una prueba de Playwright que ejercite el inicio de sesión con contraseña de
  principio a fin
- **ENTONCES** no existe ninguna
- **Y** este escenario deja de ser cierto el día que F1 o una fase posterior entregue la pantalla y
  su prueba

## MODIFIED Requirements

### Requisito: Retardo por intentos fallidos con retroceso exponencial

El sistema DEBE retardar la respuesta a un intento de autenticación a partir del tercer intento
fallido consecutivo contra la misma cuenta, con un retardo de `2^(n-3)` segundos —donde `n` es el
número de intentos fallidos consecutivos— y un tope de 900 segundos. El retardo **demora la
respuesta, no la deniega**: el sistema NO DEBE rechazar por causa del retroceso un intento cuyas
credenciales son correctas, ni bloquear la cuenta de ninguna otra forma. El contador de intentos
fallidos consecutivos DEBE expirar a los 30 minutos sin ningún intento, y un inicio de sesión
exitoso DEBE limpiarlo. El sistema DEBE auditar cada ciclo de retroceso con su duración, y el motivo
de todo rechazo —credenciales incorrectas o cuenta inexistente— DEBE ser interno y auditado, nunca
observable por quien llama.

#### Escenario: El retardo se aplica desde el tercer fallo consecutivo, y el ciclo queda auditado con su duración

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` sin fallos previos registrados
- **CUANDO** ocurren tres intentos fallidos consecutivos entre las 08:00:00 y las 08:00:40 del
  2026-03-10
- **ENTONCES** el sistema aplica, antes de responder al tercer intento, un retardo de `2^(3-3)`, es
  decir 1 segundo
- **Y** registra en la bitácora de auditoría el ciclo de retroceso con su duración de 1 segundo

#### Escenario: La progresión exponencial se limita a 900 segundos

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con doce intentos fallidos consecutivos ya
  registrados desde las 08:00:00 del 2026-03-10
- **CUANDO** ocurre el decimotercer intento fallido consecutivo a las 08:15:00 del mismo día
- **ENTONCES** el sistema aplica un retardo de 900 segundos, el tope fijado, en vez de los
  `2^(13-3)`, es decir 1024 segundos, que resultarían de la progresión sin tope
- **Y** registra el ciclo con su duración de 900 segundos

#### Escenario: El contador expira a los 30 minutos sin intentos

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con tres intentos fallidos consecutivos a las
  09:00:00 del 2026-03-10, que activaron un retardo de 1 segundo
- **CUANDO** transcurren 31 minutos sin ningún intento adicional y ocurre un nuevo intento fallido a
  las 09:31:00
- **ENTONCES** el sistema trata ese intento como el primero de un ciclo nuevo, sin aplicar ningún
  retardo, porque el contador expiró a los 30 minutos sin intentos

#### Escenario: Un inicio de sesión exitoso limpia el contador de la cuenta

- **DADO** la cuenta `maria.lopez@colegio.edu.hn` con dos intentos fallidos consecutivos registrados
  a las 10:00:00 del 2026-03-10
- **CUANDO** inicia sesión con la contraseña correcta a las 10:05:00 del mismo día
- **ENTONCES** el sistema limpia el contador de fallos de esa cuenta
- **Y** un intento fallido inmediatamente posterior, a las 10:06:00, se trata como el primer fallo de
  un ciclo nuevo, sin retardo

#### Escenario: El retardo se aplica también a una cuenta inexistente, calculado sobre el correo presentado

- **DADO** que `nadie.registrado@colegio.edu.hn` no corresponde a ninguna cuenta
- **CUANDO** se presentan tres intentos fallidos consecutivos contra ese correo entre las 11:00:00 y
  las 11:00:40 del 2026-03-10
- **ENTONCES** el sistema aplica, antes de responder al tercer intento, el mismo retardo de 1
  segundo que aplicaría a una cuenta existente en su tercer fallo, calculado sobre el correo
  presentado
- **Y** la respuesta es indistinguible en forma, contenido y tiempo observable de la que recibiría
  una cuenta existente con contraseña incorrecta en su tercer fallo

#### Escenario: El motivo del rechazo es interno y nunca observable por el cliente

- **DADO** las dos causas posibles de rechazo de un intento de autenticación: contraseña incorrecta
  y cuenta inexistente
- **CUANDO** cada una ocurre por separado
- **ENTONCES** el sistema produce, en ambos casos, la misma respuesta observable por quien llama
- **Y** el motivo real queda registrado únicamente en la bitácora de auditoría, nunca expuesto en la
  respuesta

#### Escenario: Una contraseña correcta durante el retroceso tiene éxito tras el retardo, y no es rechazada

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con tres intentos fallidos consecutivos
  registrados a las 12:00:00 del 2026-03-10, que dejaron el contador en tres
- **CUANDO** presenta la contraseña **correcta** en el cuarto intento, a las 12:00:10 del mismo día
- **ENTONCES** el sistema aplica el retardo de `2^(4-3)`, es decir 2 segundos, antes de responder
- **Y** a continuación **autentica con éxito**: el retroceso demora la respuesta y NO la deniega
- **Y** limpia el contador de fallos de esa cuenta

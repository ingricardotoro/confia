# Exploración: autenticación del personal, MFA y sesiones

- **Cambio original:** `staff-authentication-mfa-sessions` (cambio 7 de F0)
- **Dividido el 2026-09-24, en dos pasos, en tres cambios secuenciales:**
  1. `identity-module-and-password-authentication` — esta carpeta
  2. `mfa-totp-and-password-recovery`
  3. `session-tokens-and-web-layer`
- **Fase:** explorar
- **Fecha:** 2026-09-24
- **Estado:** exploración terminada, división aprobada por el propietario, pendiente de propuesta

> **Alcance de este documento.** Esta exploración se hizo sobre el cambio 7 completo, antes de
> cualquier división, y por eso describe la superficie entera. Es la exploración compartida de los
> tres cambios: los dos siguientes la referencian desde su propia carpeta en vez de repetirla, como
> hizo la parte B del cambio 5.
>
> **Por qué dos pasos.** El propietario aprobó primero el corte entre identidad y sesión, que la
> exploración recomendaba. La propuesta resultante pronosticó de 14 a 16 tareas contra el límite de
> quince de `openspec/changes/README.md`, y el propietario aprobó entonces el segundo corte, donde
> entra el cifrado de columna. La regla del repositorio pide exactamente eso: partir **antes** de
> continuar a diseño, no descubrirlo en la fase de tareas.

> **Nota de persistencia y dos correcciones del orquestador.** El agente de exploración no dispuso de
> herramienta de escritura de archivos, así que entregó el contenido íntegro y el orquestador lo
> transcribió, como ya ocurrió en el cambio 6.
>
> **Corrección 1, sobre el dueño del registro del bean.** El agente distinguió bien dos Javadoc que
> se parecen y no dicen lo mismo, verificado contra el árbol:
> `CurrentInstitutionProvider` **sí** dice literalmente «change 7's responsibility»;
> `TransactionRunner` dice «whichever change that turns out to be», y **no asigna dueño**. El primero
> es una asignación; el segundo, una condición. Este cambio la cumple si registra una fuente de datos
> de producción, pero eso es una decisión, no una herencia.
>
> **Corrección 2, sobre una frase que el orquestador atribuyó al repositorio.** El encargo de
> exploración citó, sobre el `lock_timeout`, la frase «antes de conectar el primer endpoint
> financiero real» como si estuviera escrita en el árbol. **No lo está.** El agente la buscó, no la
> encontró y lo reportó en vez de inventar la referencia. Era una síntesis del orquestador del
> hallazgo del auditor de seguridad del cambio 6, escrita en un cuerpo de pull request y en memoria.
> El **mecanismo** que sostiene la preocupación sí está verificado por lectura del diseño archivado,
> y el agente lo documenta abajo con precisión mayor que la frase original.

## Estado actual

### Dónde vive hoy el trabajo

`openspec/specs/identity/spec.md` es una capacidad **publicada** con ocho requisitos que cubren
exactamente el alcance de este cambio: autenticación con contraseña, MFA obligatoria para roles con
escritura financiera o de configuración, bloqueo por intentos fallidos con retroceso exponencial,
rotación de token de refresco con detección de reutilización, separación total entre el dominio de
identidad del personal y el de encargados, recuperación de contraseña con token de un solo uso,
prohibición de enumeración de usuarios, y autorización por permisos evaluada en el servidor.

**No existe todavía ningún paquete `com.confia.identity`** — ni dominio, ni aplicación, ni
infraestructura, ni migración, ni una sola clase.

### La capa web no existe en ningún módulo, y hay una guarda diseñada para caducar aquí

`LayeredArchitectureTest` declara hoy `optionalLayer("Web")`, y `EmptyShouldExceptionInventoryTest`
registra esa excepción con la condición explícita «no production class resides in a web package yet
(the first business controller adds one)».

Ese mecanismo está diseñado **a propósito** para fallar el día que este cambio añada la primera
clase en un paquete `..web..`, obligando a convertir `optionalLayer("Web")` en capa obligatoria y a
retirar la entrada del inventario de caducidad. Es trabajo de este cambio, no un efecto colateral.

`apps/api/app/pom.xml` tiene `spring-boot-starter-web`, pero **no** tiene Spring Security en ninguna
variante, ni springdoc-openapi, ni Argon2id, ni una biblioteca JWT con EdDSA, ni cliente de Redis, ni
biblioteca TOTP. Todas entran en este cambio.

### `CurrentInstitutionProvider`: puerto sin adaptador, con dueño nombrado

Su Javadoc dice literalmente que «the real adapter over Spring Security is change 7's
responsibility». **No existe hoy ninguna prueba de integración** que demuestre el contrato de
ADR-0009: que `institution_id` proviene solo del token. Este cambio debe escribir el adaptador real
y esa prueba, que demuestre que ninguna cabecera, parámetro ni cuerpo de la petición influye en la
institución resuelta.

### La mitad HTTP de la idempotencia, con una tensión real

El cambio 6 difirió aquí la cabecera `Idempotency-Key` obligatoria, la cabecera `Idempotent-Replay`,
y la traducción de las salidas a `200`, `409` y `422`.

**La tensión, verificada:** ADR-0010 dice que «todo endpoint que mueva dinero exige la cabecera
`Idempotency-Key`». **Ninguno de los endpoints propios de este cambio mueve dinero** — inicio de
sesión, MFA, refresco, cierre de sesión, recuperación. El roadmap asignó la mitad HTTP aquí porque
este cambio trae el primer endpoint del sistema, no porque los suyos la necesiten.

Queda como decisión: ¿entrega este cambio la infraestructura genérica reutilizable sin que ninguno
de sus endpoints la consuma, demostrándola de forma sintética como el cambio 6 demostró su criterio
de salida sin endpoint financiero, y deja el primer consumo real para F3 y F4?

### El aviso del `lock_timeout`, con alcance preciso

El `lock_timeout` se fija **a nivel de transacción**, como primera sentencia del cuerpo que
`IdempotentExecutor` pasa a `TransactionRunner`. Alcanza exactamente a las transacciones que ese
componente abre. El diseño archivado asume y escribe la consecuencia: el límite cubre todo el
cuerpo, incluido el caso de uso.

Eso significa que si un caso de uso que mueve dinero, envuelto por `IdempotentExecutor`, escribe
además en `shared_audit_log` —cuyo disparador toma bloqueo por institución sobre la cabecera de
cadena—, una contención real ahí podría agotar el mismo `lock_timeout` y llegar como `55P03`,
exactamente el código que el adaptador traduce a conflicto de idempotencia.

**Evaluación:** este cambio **no** conecta el primer endpoint financiero. Ninguno de sus endpoints
mueve dinero, así que ninguno es consumidor natural de `IdempotentExecutor`. El riesgo sigue abierto
y se activará cuando F3 o F4 conecten un caso de uso de `ledger` o `payments`. **Se registra como
riesgo conocido con dueño allí, no se resuelve aquí.**

### La guarda de no-vacuidad, localizada con precisión

En `IdempotencyScopeExclusionInventoryTest`, el método que comprueba el árbol completo solo prueba
que **ese** árbol no está vacío. El bucle que filtra por paquete `..web..` **no tiene aserción propia
de no-vacuidad sobre ese subconjunto**: hoy tiene cero elementos, el cuerpo nunca se ejecuta, y la
aserción pasa vacíamente.

La especificación archivada ya lo dice: ese escenario deja de ser cierto el día que el cambio 7
entregue el primer endpoint. Este cambio rompe esa vacuidad y debe decidir si fortalece la prueba
con su propia guarda o la sustituye por la prueba real de cabecera obligatoria.

### MFA: mecanismo decidido, cifrado sin resolver

**Decidido**, no abierto: TOTP según RFC 6238, SHA-1, seis dígitos, periodo de 30 segundos, ventana
±1. Diez códigos de recuperación de un solo uso, hasheados con Argon2id, con prevención de
reutilización por contador.

**Sin resolver:** el secreto TOTP debe ir cifrado a nivel de columna en reposo. No existe en el
árbol ningún mecanismo de cifrado de columna ni de sobre de llaves, y la ruta nativa de PostgreSQL
está cerrada **por privilegios**: `CREATE EXTENSION pgcrypto` desde una migración de Flyway fallaría,
porque Flyway migra como `confia_owner`, que no es `SUPERUSER` (`create-test-roles.sql:11`), y
`pgcrypto` no es una extensión de confianza. Eso empuja hacia cifrado en la aplicación, que **no está
decidido en ningún documento**. Es una decisión de arquitectura, no un detalle de implementación.

> **Corrección 3 del orquestador (2026-09-24).** El encargo de exploración afirmaba que «`pgcrypto`
> está confirmado no disponible» en la imagen `postgres:18-alpine`, citando la sonda S6 del cambio 5.
> Verificado contra el diseño archivado de la parte B del cambio 5, **S6 no probó eso**: probó que
> `sha256(bytea)` es función interna (`pg_proc.prolang = 12`) y que por tanto `pgcrypto` no hacía
> falta allí. **Nadie ha comprobado si los archivos de la extensión vienen en la imagen.** El hecho
> verificado es el de privilegios, y es el que se cita arriba. La diferencia no es cosmética: deja
> abierta la alternativa de crear la extensión fuera de Flyway con un rol privilegiado, que la
> propuesta debe evaluar y rechazar con motivo en vez de darla por imposible.

### Sesiones y tokens de refresco: decidido, con concurrencia real

Token de refresco opaco de 32 bytes, almacenado con SHA-256, con **rotación en cada uso** y
**detección de reutilización**: presentar un token ya usado revoca toda la familia.

Eso exige, por renovación, una operación atómica de verificar vigencia y no-uso, marcar usado y
emitir sucesor — con dos solicitudes concurrentes sobre el mismo token como caso adversarial real:
un atacante repitiendo mientras el cliente legítimo renueva.

El patrón ya existe y está probado en este repositorio: `SELECT ... FOR UPDATE` más `UPDATE` con
predicado de estado y cero filas como señal de derrota, del diseño del cambio 6. Es precedente
reutilizable, aunque sea un dominio de bloqueo distinto.

El **retroceso exponencial** vive explícitamente en Redis, y **no hay cliente de Redis en el árbol**
ni infraestructura de aprovisionamiento: el cambio 11 sigue sin archivar. Este cambio introduciría
la primera dependencia de Redis del proyecto.

### Lo que la capa web trae por primera vez

Ninguna de estas cosas tiene implementación hoy: política de seguridad de contenido basada en nonce,
cabeceras de navegador, protección CSRF de doble envío con comparación en tiempo constante y
verificación de origen, Problem Details según RFC 9457 con su traductor global, validación en el
borde con Jakarta Bean Validation y rechazo de campos desconocidos, y springdoc-openapi.

El precedente de código estable sobre excepciones de dominio **sí** existe, de las dos excepciones
de idempotencia, y este cambio es su primer consumidor real hacia HTTP.

### RBAC: reglas nuevas con dueño ambiguo entre el 7 y el 8

`docs/03-seguridad.md` §5.1 dice que un controlador sin permiso declarado no arranca la aplicación.
Esa guarda no existe en ningún archivo.

El cambio 8 es dueño de la matriz de 18 filas y su semilla. Pero la **mecánica genérica** —anotación
de permiso, verificación, y el recorrido de arranque que rechaza una ruta sin metadato— hace falta
desde el primer controlador, que es de **este** cambio.

Recomendación: este cambio entrega el mecanismo genérico y el arranque que lo exige, con los
permisos de sus pocos endpoints; el cambio 8 entrega la matriz completa y su verificación cruzada.
**Debe quedar explícito en la propuesta**, para no repetir lo que ya pasó con la idempotencia.

### Cómo se prueba sin frontend

El cambio 3 no está archivado: no hay `packages/*` ni aplicación React. Lo demostrable hoy, por el
precedente exacto de los cinco cambios anteriores —ninguno tuvo frontend—: pruebas de integración
reales contra PostgreSQL con un cliente HTTP de prueba, y Swagger UI en local.

Las pruebas de extremo a extremo con Playwright que `docs/03-seguridad.md` exige para el flujo
completo de MFA **no pueden ejecutarse contra una interfaz real todavía**. Este cambio debe declarar
ese límite explícitamente: lo que se demuestra aquí es contrato HTTP más integración real de base de
datos.

## Enfoques

1. **Un solo cambio SDD.** Ventaja: no rompe la secuencia de dependencias y da una revisión
   conceptual completa de autenticación. Desventaja: la evidencia verificada —fuente de datos de
   producción, reglas ArchUnit de `web`, MFA con cifrado sin mecanismo, JWT con EdDSA y JWKS,
   rotación con concurrencia real, adaptador de ADR-0009, cableado HTTP de idempotencia, CSRF, CSP,
   Problem Details, springdoc y Redis— excede con claridad las 12 a 13 tareas estimadas, que se
   escribieron **antes** de que existiera la mayor parte de esta evidencia. El cambio 5, con menos
   superficie, tuvo que dividirse; el 6, más angosto, terminó en seis cortes.

2. **Dividir en dos cambios secuenciales** (recomendado):
   - **Parte A**: cimientos de identidad — módulo, esquema, migración, privilegios, autenticación
     con contraseña, MFA con su mecanismo de cifrado, bloqueo con retroceso, recuperación,
     prevención de enumeración. **Sin capa web**, demostrado con pruebas de integración al caso de
     uso.
   - **Parte B**: sesión y exposición — JWT y JWKS, rotación con detección de reutilización,
     `CurrentInstitutionProvider` real con la prueba de ADR-0009, registro de la fuente de datos, el
     primer controlador y la capa web completa, la mecánica genérica de RBAC con su guarda de
     arranque, y la decisión sobre el cableado de idempotencia.

   Cada parte cierra con su propio verde y cabe con margen en el presupuesto. Hay identidad sin capa
   web, pero no hay sesión sin identidad.

## Recomendación

Delta puro de la capacidad `identity`, sin crear capacidad nueva: la capa `web` vive dentro del
módulo por la regla hexagonal ya vigente para `organization`. Y llevar a la propuesta una
recomendación explícita de **dividir en dos cambios secuenciales**, con el corte entre «identidad
sin HTTP» y «sesión y exposición HTTP».

## Riesgos

1. **El secreto MFA no tiene mecanismo de cifrado decidido**, y la ruta de `pgcrypto` está cerrada
   por privilegios desde una migración de Flyway. Decisión de arquitectura pendiente.
2. La interacción entre el `lock_timeout` y el bloqueo de la cadena de auditoría no está probada.
   Queda abierta **más allá de este cambio**, con dueño en F3 o F4.
3. **La guarda de arranque de RBAC no tiene dueño claro** entre el 7 y el 8. Sin resolverlo, alguno
   lo asumirá de forma implícita otra vez.
4. Redis entra sin infraestructura de aprovisionamiento previa.
5. El registro de la fuente de datos de producción **no está formalmente asignado** a este cambio:
   su Javadoc dice «whichever change that turns out to be». Debe confirmarse antes de tareas.
6. La estimación de 12 a 13 tareas es anterior a casi toda esta evidencia.

## Listo para propuesta

Sí, con cinco decisiones que la propuesta debe resolver explícitamente antes de especificar:

- (a) dividir o no en dos cambios, y dónde cortar;
- (b) mecanismo de cifrado del secreto MFA;
- (c) dueño de la guarda de arranque de RBAC;
- (d) si los endpoints propios deben exigir `Idempotency-Key` dado que ninguno mueve dinero;
- (e) alcance del registro de la fuente de datos: uno, dos o los tres procesos de arranque.

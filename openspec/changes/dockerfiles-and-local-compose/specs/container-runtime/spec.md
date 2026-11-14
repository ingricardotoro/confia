# Capacidad: Ejecución en contenedores

- **Identificador:** container-runtime
- **Estado:** pendiente de aprobación del propietario del producto
- **Fase:** F0
- **Cambio:** `dockerfiles-and-local-compose` (F0, cambio 11a)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-09

**Convenciones de esta especificación.**

- Es una capacidad nueva completa. Sus escenarios se numeran `CR01` en adelante.
- Describe comportamiento observable de los artefactos de contenedor y de la pila local; no fija rutas de
  archivo, nombres de pruebas ni el mecanismo concreto de ninguna sonda. Eso lo decide el diseño.
- «Imagen de la API» es la única imagen de la aplicación Spring Boot; sus cuatro procesos (`admin`, `portal`,
  `worker`, `migrate`) se seleccionan con la variable `APP_PROFILE`. «Imagen web» es la de `apps/admin-web`.
  «Pila local» es el conjunto de servicios del archivo Compose base. «Overlay de producción» es el archivo
  Compose adicional de endurecimiento.
- «Servicio de larga vida» es cualquiera de `postgres`, `api-admin`, `api-portal`, `worker` y `admin-web`;
  `migrate` es un servicio de una sola ejecución.
- Los escenarios que exigen un demonio de Docker se ejecutan en la verificación de este cambio con Docker local o
  de integración continua; los que se verifican sin demonio se señalan como tales.

## Propósito

Hoy CONFIA no puede ejecutarse como contenedor: no hay jar ejecutable, ni imágenes, ni Compose, ni rutas de
salud, y el perfil `migrate` termina sin aplicar ninguna migración. Esta capacidad define el contrato de ejecución
en contenedores que debe cumplirse al cerrar el cambio: un artefacto ejecutable, un proceso `migrate` que aplica
las migraciones y termina, sondas de vida y de preparación, imágenes endurecidas, una pila local reproducible,
ausencia de secretos en el repositorio y en las imágenes, y la medición de memoria contra límites.

Fuentes de verdad: `docs/05-infraestructura-y-despliegue.md`, `docs/07-observabilidad-y-operaciones.md` §9,
`docs/09-roadmap-y-fases.md`, ADR-0013, ADR-0021, `CLAUDE.md` reglas 11 y 13.

## Requisitos

### Requisito: La construcción produce un jar ejecutable con nombre fijo

El sistema DEBE producir, al construir `apps/api`, un jar ejecutable de la aplicación con el nombre fijo
`confia-api.jar`, que arranca con `java -jar` y selecciona su proceso con `APP_PROFILE`. El módulo `kernel` NO
DEBE producir un jar ejecutable. El jar NO DEBE contener clases de prueba ni valores de secretos.

#### Escenario: [CR01] El jar arranca con el perfil indicado

- **DADO** el jar `confia-api.jar` producido por la construcción y un PostgreSQL migrado
- **CUANDO** se ejecuta `java -jar` con `APP_PROFILE=admin`
- **ENTONCES** arranca el proceso administrativo y su ruta de preparación responde `200`
- **Y** con `APP_PROFILE=portal` arranca el proceso del portal y con `APP_PROFILE=worker` el trabajador

#### Escenario: [CR02] El jar no arrastra pruebas ni secretos

- **DADO** el jar `confia-api.jar`
- **CUANDO** se inspeccionan sus entradas
- **ENTONCES** no contiene clases del árbol de pruebas ni ningún archivo de configuración con un valor de
  secreto, y `kernel` no produce ningún jar ejecutable

### Requisito: El perfil `migrate` aplica las migraciones de Flyway y termina

El proceso `migrate` DEBE aplicar todas las migraciones de Flyway pendientes conectado como `confia_owner` y
terminar con código de salida `0`. Ante cualquier fallo (base de datos inalcanzable, credenciales rechazadas,
migración que falla, suma de verificación divergente) DEBE terminar con un código distinto de `0`. NO DEBE
quedar en ejecución, NO DEBE abrir ningún puerto y NO DEBE escribir en su salida ninguna contraseña.

#### Escenario: [CR03] Aplica todas las migraciones sobre una base vacía

- **DADO** un PostgreSQL 18 con los roles creados y sin ninguna migración aplicada
- **CUANDO** se ejecuta `APP_PROFILE=migrate` con las credenciales de `confia_owner`
- **ENTONCES** el proceso termina con código `0`, todas las migraciones figuran aplicadas con éxito en
  `flyway_schema_history` y los objetos creados pertenecen a `confia_owner`

#### Escenario: [CR04] Repetir la ejecución no cambia nada

- **DADO** una base con todas las migraciones ya aplicadas
- **CUANDO** se ejecuta `APP_PROFILE=migrate` de nuevo
- **ENTONCES** termina con código `0` y `flyway_schema_history` no cambia

#### Escenario: [CR05] Base de datos inalcanzable

- **DADO** `APP_PROFILE=migrate` apuntando a una base que no responde
- **CUANDO** el proceso intenta conectar
- **ENTONCES** termina con un código distinto de `0`

#### Escenario: [CR06] Migración que falla

- **DADO** una migración pendiente cuya ejecución falla, o una migración ya aplicada cuya suma de verificación
  difiere
- **CUANDO** se ejecuta `APP_PROFILE=migrate`
- **ENTONCES** termina con un código distinto de `0` y la salida identifica la migración sin contener la
  contraseña de la conexión

### Requisito: Ningún otro perfil aplica migraciones

Los procesos `admin`, `portal` y `worker` NO DEBEN aplicar migraciones, crear el esquema ni modificar
`flyway_schema_history`, con ninguna combinación de variables de entorno que soporte este cambio.

#### Escenario: [CR07] Un proceso desplegado contra una base vacía no crea el esquema

- **DADO** un PostgreSQL con los roles creados y sin ninguna migración aplicada
- **CUANDO** se arranca cada uno de los perfiles `admin`, `portal` y `worker`
- **ENTONCES** ninguno crea tablas ni `flyway_schema_history`, y su ruta o señal de preparación no indica
  preparado

#### Escenario: [CR08] Un proceso desplegado no aplica una migración pendiente

- **DADO** una base con las migraciones aplicadas hasta la anterior a la última
- **CUANDO** se arranca el perfil `admin`
- **ENTONCES** la migración pendiente sigue sin aplicarse y `flyway_schema_history` no cambia

### Requisito: Sondas de vida y de preparación sin revelar detalle

Los procesos `admin` y `portal` DEBEN responder `GET /health/live` y `GET /health/ready`. La sonda de vida NO DEBE
verificar ninguna dependencia externa y responde `200` mientras el proceso esté vivo. La sonda de preparación
DEBE responder `200` solo si el proceso está preparado para recibir tráfico y PostgreSQL responde, y `503` en
cualquier otro caso. El grupo de preparación NO DEBE incluir en este cambio ningún otro indicador (`redis`,
`objectStorage`, `backgroundJobs`, `migrations`): son de otros cambios. El cuerpo de toda respuesta de estas
rutas DEBE contener únicamente el campo `status` (`UP` o `DOWN`), sin desglose por componente, sin mensaje del
controlador de base de datos, sin nombres de host y sin diferencia para un cliente autenticado.

#### Escenario: [CR09] Con todo en orden ambas sondas responden `200`

- **DADO** el proceso administrativo (o el del portal) con PostgreSQL disponible
- **CUANDO** se piden `/health/live` y `/health/ready` sin credencial
- **ENTONCES** ambas responden `200` con el cuerpo `{"status":"UP"}` y nada más

#### Escenario: [CR10] PostgreSQL caído: la vida sigue y la preparación cae

- **DADO** el proceso administrativo en marcha y PostgreSQL detenido
- **CUANDO** se piden `/health/live` y `/health/ready`
- **ENTONCES** la primera sigue en `200` y la segunda responde `503` con el cuerpo `{"status":"DOWN"}` y nada más

#### Escenario: [CR11] La respuesta de preparación no filtra infraestructura

- **DADO** PostgreSQL detenido con un error de conexión que menciona el host y el puerto de la base
- **CUANDO** se pide `/health/ready` sin credencial y con un token administrativo válido
- **ENTONCES** ninguna de las dos respuestas contiene `components`, `details`, el nombre del host, el puerto ni
  el texto del error del controlador, y ambas tienen el mismo cuerpo

#### Escenario: [CR12] El grupo de preparación depende solo del estado de la aplicación y de la base

- **DADO** el proceso administrativo con PostgreSQL disponible
- **CUANDO** se inspecciona qué determina `/health/ready`
- **ENTONCES** son únicamente el estado de preparación de la aplicación y la conexión a PostgreSQL, sin ningún
  indicador de Redis, de almacenamiento de objetos, de trabajos ni de migraciones

#### Escenario: [CR13] Las sondas responden con el perfil de producción

- **DADO** el proceso administrativo arrancado con el perfil por omisión (producción) y el del portal igual
- **CUANDO** se piden `/health/live` y `/health/ready`
- **ENTONCES** ambos responden como en el perfil `local`, sin depender de que springdoc esté habilitado

### Requisito: Actuator expone solo salud

El sistema NO DEBE exponer ningún endpoint de Actuator distinto de las dos rutas de salud, en ningún proceso ni
perfil. Las rutas `/actuator`, `/actuator/env`, `/actuator/metrics`, `/actuator/prometheus`, `/actuator/info`,
`/actuator/health` y cualquier otra NO DEBEN responder contenido.

#### Escenario: [CR14] Rutas de Actuator distintas de la salud

- **DADO** los procesos administrativo y del portal
- **CUANDO** se piden `/actuator`, `/actuator/health`, `/actuator/env`, `/actuator/metrics`,
  `/actuator/prometheus` y `/actuator/info` sin credencial
- **ENTONCES** todas responden `401` con Problem Details y `authentication-required`, ninguna `2xx`

#### Escenario: [CR15] El mapa de endpoints de Actuator contiene solo la salud

- **DADO** el contexto de cada proceso con web
- **CUANDO** se enumeran los endpoints de Actuator expuestos
- **ENTONCES** el conjunto contiene únicamente la salud, agrupada en vida y preparación

### Requisito: El trabajador declara una comprobación de salud observable

El proceso `worker` DEBE poder ser comprobado como sano o no sano por el orquestador (Compose), con un mecanismo
que el diseño elige entre las alternativas del documento de exploración. Cualquiera sea el mecanismo, la
comprobación DEBE: indicar sano mientras el trabajador está en marcha y su base responde; no revelar ningún
detalle de infraestructura; y NO DEBE publicar ningún puerto en el anfitrión ni abrir ninguna ruta de negocio.

#### Escenario: [CR16] El trabajador en marcha se reporta sano

- **DADO** el trabajador arrancado con PostgreSQL migrado y disponible
- **CUANDO** el orquestador evalúa su comprobación de salud
- **ENTONCES** el servicio `worker` pasa a sano dentro del tiempo de arranque declarado

#### Escenario: [CR17] La comprobación del trabajador no expone detalle ni puertos

- **DADO** el servicio `worker` de la pila local
- **CUANDO** se inspeccionan sus puertos publicados y la información que su comprobación entrega
- **ENTONCES** no publica ningún puerto en el anfitrión y la información de su comprobación se limita al estado,
  sin detalle de infraestructura

### Requisito: La imagen de la API copia el jar construido fuera

La imagen de la API DEBE construirse copiando el jar `confia-api.jar` ya construido; la construcción de la imagen
NO DEBE ejecutar Maven ni requerir acceso a un demonio de Docker, porque la generación del código de jOOQ lo
necesita (ADR-0021). La imagen de ejecución DEBE usar un JRE de Java 25 de la misma distribución OpenJDK que el
JDK de integración continua, sin compilador, sin Maven y sin código fuente.

#### Escenario: [CR18] La imagen se construye sin Maven ni demonio dentro del build

- **DADO** el jar construido fuera y el contexto de construcción de la imagen
- **CUANDO** se construye la imagen de la API
- **ENTONCES** la construcción termina sin ejecutar `mvnw` ni arrancar contenedores, y la imagen contiene el jar
  idéntico (misma suma de verificación) al construido fuera

#### Escenario: [CR19] La imagen de ejecución es un JRE de la distribución de integración continua

- **DADO** la imagen de la API construida
- **CUANDO** se ejecuta `java -version` dentro de ella y se compara con el JDK que usa el trabajo `backend`
- **ENTONCES** ambos son Java 25 de la misma distribución OpenJDK, y la imagen no contiene `javac`, `mvn` ni
  archivos `.java`

### Requisito: La imagen de la API corre sin privilegios y con raíz de solo lectura

La imagen de la API DEBE ejecutar sus procesos con el usuario no raíz `10001` y DEBE funcionar con el sistema de
archivos raíz de solo lectura y `/tmp` montado como `tmpfs` con `noexec,nosuid`. La imagen NO DEBE requerir
quitar `noexec` de `/tmp` ni escribir fuera de los montajes `tmpfs` declarados.

#### Escenario: [CR20] El proceso corre como usuario 10001

- **DADO** un contenedor de la imagen de la API en marcha
- **CUANDO** se consulta el usuario efectivo del proceso principal
- **ENTONCES** es `10001`, no `root`

#### Escenario: [CR21] Los cuatro perfiles arrancan con raíz de solo lectura

- **DADO** contenedores de la imagen de la API con `read_only` activado y `/tmp` como `tmpfs` con `noexec,nosuid`
- **CUANDO** se arrancan con `APP_PROFILE` igual a `admin`, `portal`, `worker` y `migrate`
- **ENTONCES** `admin`, `portal` y `worker` llegan a sanos y `migrate` termina con código `0`, sin ningún error
  de escritura en el sistema de archivos

#### Escenario: [CR22] El directorio temporal no permite ejecutar

- **DADO** un contenedor de la imagen de la API con `/tmp` montado como se exige
- **CUANDO** se intenta ejecutar un archivo copiado a `/tmp`
- **ENTONCES** el sistema operativo lo rechaza por `noexec`

### Requisito: La memoria de la JVM se dimensiona respecto del límite del contenedor

La imagen de la API DEBE fijar en `JAVA_TOOL_OPTIONS` un `MaxRAMPercentage` de modo que el montón máximo sea una
fracción del límite de memoria del contenedor y no de la memoria del anfitrión.

#### Escenario: [CR23] El montón máximo sigue el límite del contenedor

- **DADO** un contenedor de la imagen de la API con un límite de memoria declarado
- **CUANDO** se consulta el tamaño máximo de montón efectivo de la JVM
- **ENTONCES** es el porcentaje configurado del límite del contenedor, inferior a ese límite

#### Escenario: [CR24] Dos límites distintos producen dos montones distintos

- **DADO** dos contenedores de la imagen de la API con límites de memoria distintos
- **CUANDO** se consulta el montón máximo de cada uno
- **ENTONCES** difieren en proporción a sus límites

### Requisito: La comprobación de salud de la API funciona con el contenido de la imagen

La comprobación de salud de los servicios `api-admin` y `api-portal` DEBE funcionar con las herramientas que
trae la imagen de ejecución elegida. NO DEBE exigir añadir a la imagen paquetes, clientes HTTP ni intérpretes que
el diseño no registre con su justificación.

#### Escenario: [CR25] La sonda de la API reporta sano sin herramientas añadidas

- **DADO** la imagen de la API sin paquetes instalados más allá de los de su imagen base
- **CUANDO** Compose evalúa la comprobación de salud de `api-admin`
- **ENTONCES** el servicio pasa a sano

#### Escenario: [CR26] La sonda reporta no sano cuando la preparación cae

- **DADO** `api-admin` en marcha y PostgreSQL detenido
- **CUANDO** Compose evalúa la comprobación de salud tras los reintentos declarados
- **ENTONCES** el servicio pasa a no sano

### Requisito: Las imágenes base se fijan por etiqueta exacta

Todo Dockerfile DEBE declarar cada imagen base con una etiqueta de versión exacta; NO DEBE usar `latest` ni una
etiqueta sin versión. La imagen de PostgreSQL de la pila local DEBE ser PostgreSQL 18 por etiqueta exacta. La
fijación por dígest queda fuera de este cambio.

#### Escenario: [CR27] Sin `latest` ni etiqueta ausente

- **DADO** los Dockerfiles y el archivo Compose del repositorio
- **CUANDO** se enumeran las imágenes base y las imágenes de servicio
- **ENTONCES** todas llevan una etiqueta de versión exacta y ninguna es `latest` ni carece de etiqueta

#### Escenario: [CR28] Una etiqueta flotante rompe la verificación

- **DADO** un Dockerfile con una imagen base sin etiqueta o con `latest`
- **CUANDO** se ejecuta la verificación de Dockerfiles de integración continua
- **ENTONCES** la verificación falla señalando la línea

### Requisito: Ninguna capa de imagen contiene secretos

Ninguna capa de la imagen de la API ni de la imagen web DEBE contener contraseñas, claves, tokens ni archivos
`.env`. Los valores sensibles llegan solo por variables de entorno en tiempo de ejecución.

#### Escenario: [CR29] Las capas no contienen secretos

- **DADO** las imágenes de la API y web construidas
- **CUANDO** se inspeccionan sus capas y su configuración (historial, variables `ENV`, archivos copiados)
- **ENTONCES** no aparece ninguna contraseña, clave ni token, ni ningún archivo `.env`, ni el valor de ninguna
  variable de `.env.example`

#### Escenario: [CR30] El contexto de construcción excluye el archivo `.env`

- **DADO** un `.env` local con valores reales en el directorio de trabajo
- **CUANDO** se construye cualquiera de las imágenes
- **ENTONCES** el archivo no entra en el contexto de construcción ni en ninguna capa

### Requisito: La imagen web sirve la aplicación con nginx sin privilegios

La imagen web de `apps/admin-web` DEBE construir la aplicación con Vite en una etapa de Node, a partir de las
instantáneas aprobadas de `packages/contracts` y sin JDK ni demonio de Docker, y DEBE servir el resultado con un
nginx sin privilegios que corra con un usuario no raíz, escuche en un puerto no privilegiado y responda
`GET /healthz` con `200` sin depender de ningún servicio externo. La construcción DEBE parametrizarse por
aplicación, de modo que `apps/portal-web` la reutilice cuando exista. La imagen final NO DEBE contener el código
fuente, `node_modules` ni la cadena de herramientas de Node.

#### Escenario: [CR31] La imagen web sirve la aplicación y `/healthz`

- **DADO** la imagen web de `admin-web` construida
- **CUANDO** se arranca y se pide `/healthz` y `/`
- **ENTONCES** `/healthz` responde `200` y `/` responde el documento de la aplicación

#### Escenario: [CR32] Sin privilegios y sin herramientas de construcción

- **DADO** un contenedor de la imagen web en marcha
- **CUANDO** se consulta el usuario efectivo, el puerto en escucha y el contenido de la imagen
- **ENTONCES** el usuario no es `root`, el puerto es mayor que 1024, y no hay código fuente, `node_modules` ni
  `node` en la imagen final

#### Escenario: [CR33] La construcción se parametriza por aplicación

- **DADO** el Dockerfile web con su argumento de aplicación
- **CUANDO** se construye con `admin-web` y con un nombre de aplicación que no existe
- **ENTONCES** la primera construcción termina bien y la segunda falla indicando que la aplicación no existe

#### Escenario: [CR34] La construcción no necesita JDK ni demonio

- **DADO** un entorno sin JDK y sin acceso a un demonio dentro de la construcción
- **CUANDO** se construye la imagen web desde un repositorio recién clonado
- **ENTONCES** la construcción termina bien, con los esquemas de `packages/contracts` tomados de las
  instantáneas aprobadas

### Requisito: La decisión sobre `read_only` del nginx web queda registrada

El sistema DEBE dejar decidido y registrado si el nginx de las aplicaciones web corre con raíz de solo lectura:
validada y activada con el usuario `10001` y rutas temporales para caché y archivo de identificador de proceso, o
bien una excepción documentada con su motivo.

#### Escenario: [CR35] Raíz de solo lectura validada

- **DADO** que se decide activar `read_only` en la imagen web
- **CUANDO** se arranca el contenedor con raíz de solo lectura y los montajes temporales declarados
- **ENTONCES** `/healthz` responde `200` y el servicio llega a sano sin errores de escritura

#### Escenario: [CR36] Excepción documentada

- **DADO** que se decide no activar `read_only` en la imagen web
- **CUANDO** se lee la documentación de infraestructura actualizada por este cambio
- **ENTONCES** contiene una nota fechada con el motivo de la excepción y la evidencia de la validación

### Requisito: La pila local levanta los servicios definidos y solo ellos

El archivo Compose base DEBE definir exactamente los servicios `postgres`, `migrate`, `api-admin`, `api-portal`,
`worker` y `admin-web`, cada servicio de larga vida con su comprobación de salud declarada. Todo puerto publicado
DEBE enlazarse solo a `127.0.0.1`. La pila NO DEBE incluir Redis, almacenamiento de objetos, nginx de borde ni un
servicio de `portal-web`.

#### Escenario: [CR37] Servicios definidos

- **DADO** el archivo Compose base resuelto con `docker compose config` y los valores de `.env.example`
- **CUANDO** se listan sus servicios
- **ENTONCES** son exactamente `postgres`, `migrate`, `api-admin`, `api-portal`, `worker` y `admin-web`, y los
  cinco de larga vida declaran una comprobación de salud

#### Escenario: [CR38] Puertos solo en la interfaz local

- **DADO** el archivo Compose base resuelto
- **CUANDO** se enumeran los puertos publicados de todos los servicios
- **ENTONCES** cada uno se enlaza a `127.0.0.1` y ninguno a `0.0.0.0` ni a una interfaz sin dirección

#### Escenario: [CR39] Sin servicios de otros cambios

- **DADO** el archivo Compose base
- **CUANDO** se buscan servicios de Redis, de almacenamiento de objetos, de borde o de `portal-web`
- **ENTONCES** no existe ninguno; sus dueños son los cambios 11b, 11e, 11d y el que cree `apps/portal-web`

### Requisito: `migrate` es un servicio de una sola ejecución y ordena el arranque

El servicio `migrate` DEBE esperar a que `postgres` esté sano, ejecutar una vez y terminar. Los servicios
`api-admin`, `api-portal` y `worker` DEBEN arrancar solo después de que `migrate` termine con éxito y `postgres`
esté sano. Si `migrate` falla, esos tres servicios NO DEBEN arrancar. `migrate` NO DEBE reiniciarse tras terminar
con éxito.

#### Escenario: [CR40] Orden de arranque con éxito

- **DADO** una pila local sin contenedores, con un `.env` creado desde `.env.example`
- **CUANDO** se ejecuta `docker compose up`
- **ENTONCES** `postgres` llega a sano, `migrate` aplica las migraciones y termina con código `0`, y solo
  entonces arrancan `api-admin`, `api-portal` y `worker`
- **Y** los cinco servicios de larga vida terminan sanos y `migrate` permanece terminado

#### Escenario: [CR41] `migrate` falla y nada más arranca

- **DADO** una migración que falla
- **CUANDO** se ejecuta `docker compose up`
- **ENTONCES** `migrate` termina con código distinto de `0` y `api-admin`, `api-portal` y `worker` no se crean ni
  arrancan

#### Escenario: [CR42] `postgres` no sano retiene a `migrate`

- **DADO** un `postgres` que no llega a sano
- **CUANDO** se ejecuta `docker compose up`
- **ENTONCES** `migrate` no arranca y no se ejecuta ninguna migración

### Requisito: Los roles de PostgreSQL locales los crea un script exclusivo de la pila local

La pila local DEBE crear los roles de base de datos del sistema (`confia_owner`, `confia_admin_app`,
`confia_portal_app`, `confia_readonly`, `confia_backup`) con un script de inicialización de PostgreSQL que solo
usa Compose local, con contraseñas leídas del entorno. El script NO DEBE contener ninguna contraseña literal, NO
DEBE copiarse a ninguna imagen de la aplicación y NO DEBE usarse en el overlay de producción.

#### Escenario: [CR43] Los roles existen antes de `migrate`

- **DADO** un volumen de PostgreSQL nuevo
- **CUANDO** arranca `postgres` por primera vez
- **ENTONCES** los cinco roles existen, cada uno con la contraseña tomada de su variable de entorno, antes de que
  `migrate` se conecte

#### Escenario: [CR44] El script no lleva contraseñas literales y no sale de la pila local

- **DADO** el script de roles y las imágenes construidas
- **CUANDO** se inspeccionan
- **ENTONCES** el script no contiene ninguna contraseña literal, y ninguna imagen de la aplicación ni el overlay
  de producción lo incluyen

### Requisito: `.env.example` solo contiene marcadores y `.env` no se versiona

El repositorio DEBE incluir un `.env.example` con todas las variables que la pila local necesita y NINGÚN valor
secreto real: solo marcadores reconocibles. El valor de ningún ejemplo de la documentación de infraestructura
NO DEBE copiarse. El archivo `.env` DEBE estar excluido del control de versiones. Un `.env` creado copiando
`.env.example` DEBE bastar para levantar la pila local, o `.env.example` DEBE documentar el comando que genera
cada valor que no pueda ser un marcador.

#### Escenario: [CR45] `.env.example` sin secretos reales

- **DADO** el archivo `.env.example`
- **CUANDO** se revisan los valores de sus variables sensibles
- **ENTONCES** todos son marcadores o están vacíos, ninguno coincide con una contraseña, clave o token real ni con
  el literal de contraseña de los ejemplos de `docs/05`

#### Escenario: [CR46] `.env` ignorado por Git

- **DADO** un `.env` local con valores reales
- **CUANDO** se consulta el estado de Git
- **ENTONCES** `.env` aparece ignorado y `.env.example` aparece versionado

#### Escenario: [CR47] Falta una variable obligatoria

- **DADO** un entorno sin `.env` y sin las variables obligatorias definidas
- **CUANDO** se ejecuta `docker compose config` o `docker compose up`
- **ENTONCES** el comando falla antes de crear ningún contenedor, nombrando la variable que falta

### Requisito: El overlay de producción endurece los servicios y no incluye PostgreSQL

El overlay `docker-compose.prod.yml` DEBE aplicar a los servicios de la aplicación (`api-admin`, `api-portal`,
`worker`, `migrate` y `admin-web`): usuario `10001`, `no-new-privileges`, `cap_drop: [ALL]` y `read_only`, junto
con un límite de memoria declarado para cada servicio de la JVM. NO DEBE definir el servicio `postgres`, porque en
producción la base es gestionada. Su uso real es del cambio 11d; este cambio solo lo valida con
`docker compose config`.

#### Escenario: [CR48] Endurecimiento aplicado

- **DADO** el archivo base combinado con el overlay y resuelto con `docker compose config`
- **CUANDO** se inspeccionan los servicios de la aplicación
- **ENTONCES** cada uno declara usuario `10001`, `no-new-privileges`, `cap_drop` con `ALL` y raíz de solo lectura,
  y cada servicio de la JVM declara un límite de memoria

#### Escenario: [CR49] Sin `postgres` en el overlay

- **DADO** el archivo base combinado con el overlay
- **CUANDO** se listan los servicios resultantes
- **ENTONCES** no existe el servicio `postgres` y ningún servicio depende de él

### Requisito: La memoria de los procesos de la JVM se mide contra sus límites y se documenta

El sistema DEBE medir el consumo de memoria de los procesos `admin`, `portal` y `worker` ejecutándose bajo los
límites de memoria que declara Compose, DEBE ajustar los límites y `JAVA_TOOL_OPTIONS` con el resultado, y DEBE
documentar la medición en `docs/09` con una nota fechada. Si un proceso no cabe en su límite, la medición DEBE
registrarlo y la topología DEBE escalarse según ADR-0013 antes del cambio 11d.

#### Escenario: [CR50] Medición registrada por proceso

- **DADO** la pila local en marcha bajo sus límites de memoria
- **CUANDO** se lee la documentación actualizada por este cambio
- **ENTONCES** registra, para `admin`, `portal` y `worker`, el consumo medido, el límite aplicado, las
  condiciones de la medición y la fecha

#### Escenario: [CR51] Los límites vigentes corresponden a la medición

- **DADO** la medición documentada y los límites y `JAVA_TOOL_OPTIONS` resultantes
- **CUANDO** se comparan
- **ENTONCES** ningún proceso tiene un consumo medido superior a su límite vigente, o la nota documenta la
  decisión de escalado que lo resuelve

#### Escenario: [CR52] La pila no sufre terminación por falta de memoria

- **DADO** la pila local en reposo y tras un recorrido de arranque completo
- **CUANDO** se inspecciona el estado de los contenedores
- **ENTONCES** ninguno terminó por falta de memoria

### Requisito: Ausencia de publicación, de cadena de suministro y de entornos remotos (brecha con destino: cambios 11b a 11f)

El sistema NO DEBE incluir en este cambio flujo de publicación de imágenes, análisis de imágenes, SBOM, firma,
fijación por dígest, Renovate, recursos de AWS, nginx de borde ni recorridos de extremo a extremo contra la pila
real. Cada uno pertenece a un cambio con dueño nombrado.

#### Escenario: [CR53] Sin flujo de publicación ni fijación por dígest — cierto hoy, falso al cerrar 11c

- **DADO** el repositorio al cerrar este cambio
- **CUANDO** se buscan un flujo de publicación (`cd.yml`), referencias por dígest en los Dockerfiles y el archivo
  Compose, y configuración de Renovate
- **ENTONCES** no existe ninguno; este resultado deja de ser cierto cuando el cambio 11c los entregue

#### Escenario: [CR54] Sin recursos de nube ni borde

- **DADO** el repositorio al cerrar este cambio
- **CUANDO** se buscan definiciones de infraestructura como código, un nginx de borde y recorridos de extremo a
  extremo contra la pila real
- **ENTONCES** no existe ninguno; este resultado deja de ser cierto cuando 11d y 11f los entreguen

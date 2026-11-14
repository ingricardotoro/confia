# Capacidad: Respaldo, restauración y simulacro

- **Identificador:** backup-and-restore
- **Estado:** pendiente de aprobación del propietario del producto
- **Fase:** F0
- **Cambio:** `backup-restore-and-drill` (F0, cambio 12)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-09

**Convenciones de esta especificación.**

- Es una capacidad nueva completa. Sus escenarios se numeran `BR01` en adelante.
- Describe comportamiento observable de los scripts de respaldo y restauración, de la verificación de la
  restauración, de la configuración del almacenamiento fuera de AWS y de los procedimientos humanos. No fija rutas
  de archivo, nombres de clases ni el mecanismo de publicación de métricas; eso lo decide el diseño.
- El nombre de la capacidad, `backup-and-restore`, quedó confirmado por el propietario el 2026-11-09 y la propuesta se alineó con él.
- **Etiquetas de verificación.** Cada escenario lleva una etiqueta que dice dónde y cómo se comprueba:
  - `[LOCAL]`: se comprueba en local y en integración continua (Testcontainers con PostgreSQL y MinIO, pruebas de
    esquema, pruebas unitarias de reglas de alerta, comprobaciones estructurales). Rompe la construcción si falla.
  - `[AWS]`: solo se comprueba en preproducción de AWS (depende de 11d `preprod-environment-and-deploy`).
  - `[PROVEEDOR]`: solo se comprueba contra el proveedor real fuera de AWS (Backblaze B2), con un bucket de pruebas
    y retención de un día.
  - `[CAMBIO-10]`: la parte de recolección y disparo depende de Prometheus y Alertmanager del cambio 10
    `structured-logging-and-tracing`.
  - `[HUMANO]`: lo ejecutan o firman personas; la evidencia es el acta o el registro del procedimiento.
- «Respaldo» es el volcado lógico de PostgreSQL, cifrado con age y guardado fuera de AWS. «Respaldo de RDS» es el
  respaldo automático gestionado por AWS. «Etiqueta» es el nivel de retención de un respaldo: `daily`, `weekly`
  o `monthly`. «Propietario» es el propietario del producto. «Llave age» es el par de llaves de cifrado de los
  respaldos; el respaldo solo conoce la parte pública. «KEK» es la llave maestra del cifrado de columnas.
- «Cuatro alertas» son `BackupMissing`, `RdsBackupDisabled`, `RdsRestorableTimeLagHigh` y `RestoreDrillOverdue`.
- Quedan fuera de esta capacidad, con su dueño: EC2, RDS, red, gestor de secretos y aprovisionamiento de roles en
  RDS (11d); Prometheus, Alertmanager y el recolector del nodo (cambio 10); imágenes, Compose y script de roles
  local (11a); ancla externa de la cadena de auditoría (11e); réplica de objetos de negocio de S3; y la lista de
  eliminaciones pendientes de propagación (sin dueño, ver el requisito del runbook).

## Propósito

Hoy CONFIA no tiene ningún respaldo ejecutable ni restaurado. Esta capacidad define el contrato que debe cumplirse
al cerrar el cambio: un respaldo nocturno completo, cifrado y guardado fuera de AWS bajo bloqueo de cumplimiento;
una restauración en un PostgreSQL limpio con sus roles; una verificación que no puede dar verde sin haber
comprobado la cadena de auditoría y descifrado una fila real; métricas y alertas; custodia y rotación de llaves; y
un simulacro con acta firmada que cierra el criterio de salida 6 de `docs/09-roadmap-y-fases.md`.

Fuentes de verdad: `docs/05-infraestructura-y-despliegue.md` §11, `docs/03-seguridad.md` §6.1 y §7.2,
`docs/07-observabilidad-y-operaciones.md`, `docs/08-datos-privacidad-y-retencion.md`, ADR-0014, ADR-0016 y
`CLAUDE.md` reglas 11 y 13.

## Requisitos

### Requisito: El respaldo vuelca la base completa como `confia_backup` con la seguridad de fila activa

El respaldo DEBE producir un volcado en formato personalizado de `pg_dump` conectado como el rol `confia_backup`
(nunca como `confia_owner` ni como un rol con `BYPASSRLS`) y con la seguridad a nivel de fila habilitada para el
volcado. El volcado DEBE contener todas las filas de todas las tablas con seguridad de fila forzada, de todas las
instituciones. Un volcado vacío o parcial NO DEBE terminar con código `0`.

#### Escenario: [BR01] [LOCAL] El volcado contiene todas las filas de todas las instituciones

- **DADO** una base migrada con filas de al menos dos instituciones en cada tabla con seguridad de fila forzada
- **CUANDO** el respaldo vuelca la base como `confia_backup` y el volcado se restaura en una base limpia
- **ENTONCES** el conteo de filas de cada tabla en la base restaurada es igual al conteo que lee el propietario en
  la base de origen, sin ninguna tabla vacía por efecto de la política de fila

#### Escenario: [BR02] [LOCAL] El volcado se ejecuta con la identidad de `confia_backup`

- **DADO** el respaldo en ejecución contra una base de pruebas
- **CUANDO** se inspecciona la sesión de volcado (`pg_stat_activity` o el registro del servidor)
- **ENTONCES** la conexión usa el rol `confia_backup`, que mantiene `NOBYPASSRLS` y no posee ningún privilegio de
  escritura

#### Escenario: [BR03] [LOCAL] Un volcado que sale vacío sin error se detecta como fallo

- **DADO** una base donde `confia_backup` no tiene política de lectura sobre una tabla con filas (la política se
  retira a propósito en una base de pruebas)
- **CUANDO** se ejecuta el respaldo
- **ENTONCES** la verificación de completitud del respaldo falla, el respaldo termina con código distinto de `0`
  señalando la tabla, y no se sube ningún objeto como respaldo válido

### Requisito: El respaldo se cifra con age y no puede leerse sin la llave privada

El respaldo DEBE cifrar el volcado con age usando solo la clave pública (destinatario) antes de que salga del
proceso de volcado. El servidor que ejecuta el respaldo NO DEBE tener acceso a la llave privada. El texto plano
del volcado NO DEBE subirse al almacenamiento fuera de AWS en ningún caso.

#### Escenario: [BR04] [LOCAL] Sin la llave privada el objeto subido no se descifra

- **DADO** un respaldo subido a MinIO y descargado
- **CUANDO** se intenta descifrarlo con una llave age distinta de la del destinatario
- **ENTONCES** el descifrado falla y no se obtiene ningún byte del volcado

#### Escenario: [BR05] [LOCAL] El objeto subido no es un volcado legible

- **DADO** un respaldo subido a MinIO
- **CUANDO** se inspecciona el contenido del objeto sin descifrar
- **ENTONCES** no comienza con la firma de un archivo de `pg_dump` ni contiene los nombres de las tablas ni valores
  de filas en claro

#### Escenario: [BR06] [LOCAL] El respaldo solo recibe la parte pública de la llave

- **DADO** la configuración que recibe el respaldo en ejecución
- **CUANDO** se revisan sus entradas y su entorno
- **ENTONCES** contiene únicamente el destinatario público de age y ninguna identidad (llave privada)

### Requisito: Cada respaldo lleva una suma sha256 que se sube con el objeto y se verifica

El respaldo DEBE calcular el sha256 del objeto cifrado y subirlo junto con él. Antes de dar un respaldo por
completo, el respaldo DEBE confirmar que el objeto almacenado coincide con la suma calculada.

#### Escenario: [BR07] [LOCAL] La suma subida coincide con la del objeto

- **DADO** un respaldo subido a MinIO
- **CUANDO** se descarga el objeto y su suma sha256
- **ENTONCES** el sha256 calculado sobre el objeto descargado es igual a la suma subida

#### Escenario: [BR08] [LOCAL] Una subida corrompida termina con error

- **DADO** una subida cuyo objeto almacenado difiere del cifrado local (la corrupción se simula)
- **CUANDO** el respaldo compara el objeto almacenado con su suma
- **ENTONCES** termina con código distinto de `0`, no registra éxito y no actualiza la métrica de último éxito

### Requisito: El respaldo no deja copia local ni texto plano en disco

El respaldo DEBE transmitir el volcado por tubería hacia el cifrado y la subida, o limitar cualquier archivo
temporal al cifrado y eliminarlo antes de terminar. NO DEBE quedar en el disco local ningún texto plano ni copia
del respaldo al terminar, ya sea con éxito o con fallo.

#### Escenario: [BR09] [LOCAL] Sin residuos tras un respaldo exitoso

- **DADO** un directorio temporal vacío asignado al respaldo
- **CUANDO** el respaldo termina con código `0`
- **ENTONCES** el directorio temporal sigue vacío y ningún archivo con el contenido del volcado, cifrado o no,
  persiste en el disco local

#### Escenario: [BR10] [LOCAL] Sin residuos tras un respaldo fallido

- **DADO** un respaldo cuya subida falla (MinIO detenido)
- **CUANDO** termina con código distinto de `0`
- **ENTONCES** no queda en el disco local ningún archivo con contenido del volcado

### Requisito: Los registros del respaldo y la restauración no contienen secretos ni datos personales

Los scripts de respaldo y restauración NO DEBEN escribir en su salida ni en sus registros contraseñas, cadenas de
conexión con credenciales, llaves de acceso del almacenamiento, llaves age, la KEK, ni datos de las filas
(`CLAUDE.md`, regla 11). Tampoco DEBEN incluirse secretos en el repositorio, en ejemplos ni en pruebas.

#### Escenario: [BR11] [LOCAL] Una ejecución exitosa no imprime secretos

- **DADO** un respaldo y una restauración ejecutados con credenciales de prueba reconocibles
- **CUANDO** se examina la salida estándar y la de error completas
- **ENTONCES** ninguna credencial, contraseña, llave de acceso, llave age ni valor de fila aparece en ellas

#### Escenario: [BR12] [LOCAL] Una ejecución fallida tampoco imprime secretos

- **DADO** un respaldo que falla por credenciales del almacenamiento rechazadas
- **CUANDO** se examina la salida estándar y la de error
- **ENTONCES** el mensaje identifica la etapa que falló pero no contiene las credenciales ni la cadena de conexión

#### Escenario: [BR13] [LOCAL] El repositorio no contiene secretos de respaldo

- **DADO** los scripts, unidades, ejemplos de configuración y pruebas de este cambio
- **CUANDO** se ejecuta el escaneo de secretos del repositorio
- **ENTONCES** no reporta ningún hallazgo y los ejemplos usan únicamente marcadores

### Requisito: El respaldo elige una etiqueta escalonada y admite el modo previo a la migración

El respaldo DEBE guardar cada objeto bajo un prefijo según su etiqueta. En modo nocturno, la etiqueta se decide por
la fecha UTC: `monthly` si es el día 1 del mes, `weekly` si no y es domingo, `daily` en cualquier otro caso. El
modo `pre-migration` DEBE guardar el objeto bajo un prefijo propio, distinto de los tres escalonados, con un nombre
que incluya la marca de tiempo, y NO DEBE contar como respaldo nocturno para la métrica del último éxito de los
respaldos nocturnos.

#### Escenario: [BR14] [LOCAL] La etiqueta se deriva de la fecha

- **DADO** las fechas UTC 2026-12-01 (martes), 2026-12-06 (domingo) y 2026-12-08 (martes)
- **CUANDO** el respaldo nocturno decide la etiqueta para cada una
- **ENTONCES** elige `monthly`, `weekly` y `daily` respectivamente

#### Escenario: [BR15] [LOCAL] El modo previo a la migración usa su propio prefijo

- **DADO** el respaldo invocado con el modo `pre-migration`
- **CUANDO** termina con código `0`
- **ENTONCES** el objeto queda bajo el prefijo `pre-migration/` y ningún prefijo `daily/`, `weekly/` ni
  `monthly/` recibe objetos nuevos

#### Escenario: [BR16] [LOCAL] Un modo desconocido se rechaza

- **DADO** el respaldo invocado con un modo no reconocido
- **CUANDO** valida sus argumentos
- **ENTONCES** termina con el código de uso incorrecto antes de conectar a la base de datos o al almacenamiento

### Requisito: La retención del bloqueo de cada objeto nunca supera la de su ciclo de vida

El respaldo DEBE fijar sobre cada objeto una retención de bloqueo en modo de cumplimiento igual a la duración del
ciclo de vida de su etiqueta (7 días para `daily`, 4 semanas para `weekly`, 12 meses para `monthly`) y NUNCA mayor,
para que el bloqueo no impida la expiración por ciclo de vida. Ningún objeto DEBE poder borrarse ni modificarse
antes de que expire su retención.

#### Escenario: [BR17] [LOCAL] La fecha de retención del objeto coincide con la regla de la etiqueta

- **DADO** un respaldo `daily` y un respaldo `weekly` subidos a un MinIO con bloqueo de objeto en modo de
  cumplimiento
- **CUANDO** se lee la retención de cada objeto
- **ENTONCES** la fecha de retención del `daily` es la de subida más 7 días y la del `weekly` la de subida más 4
  semanas, y ninguna excede la duración de su regla de ciclo de vida

#### Escenario: [BR18] [LOCAL] Un borrado antes del vencimiento es rechazado

- **DADO** un objeto de respaldo bajo bloqueo en modo de cumplimiento en MinIO
- **CUANDO** se intenta borrar la versión del objeto, incluso con las credenciales de administración
- **ENTONCES** el almacenamiento rechaza el borrado

#### Escenario: [BR19] [PROVEEDOR] El proveedor real aplica el mismo bloqueo

- **DADO** un bucket de pruebas de Backblaze B2 con bloqueo de objeto en modo de cumplimiento y retención de un día
- **CUANDO** se sube un objeto con el respaldo y se intenta borrar su versión
- **ENTONCES** B2 rechaza el borrado y la retención del objeto es la solicitada

### Requisito: La credencial de escritura del respaldo no puede borrar ni sobrescribir

La credencial con la que el respaldo escribe en el almacenamiento fuera de AWS DEBE poder listar y crear objetos
pero NO DEBE poder borrar versiones, ni sobrescribir un objeto existente, ni cambiar la configuración del bucket.
La comprobación DEBE hacerse con un intento real rechazado y no solo por la lectura de la política.

#### Escenario: [BR20] [LOCAL] La credencial de solo escritura no borra en MinIO

- **DADO** el bucket de MinIO de pruebas y una credencial con únicamente los permisos de escritura del respaldo
- **CUANDO** se intenta borrar un objeto existente con esa credencial
- **ENTONCES** la solicitud es rechazada y el objeto sigue presente

#### Escenario: [BR21] [LOCAL] La credencial de solo escritura no sobrescribe

- **DADO** un objeto ya subido y la misma credencial
- **CUANDO** se intenta escribir de nuevo la misma clave de objeto
- **ENTONCES** la solicitud es rechazada y el contenido original no cambia

#### Escenario: [BR22] [PROVEEDOR] La llave de aplicación de B2 no borra ni sobrescribe

- **DADO** el bucket real fuera de AWS y la llave de aplicación de escritura del respaldo
- **CUANDO** se intenta borrar y sobrescribir un objeto de prueba
- **ENTONCES** B2 rechaza ambas operaciones; el resultado queda registrado en el acta o en la evidencia del cambio

### Requisito: La configuración del bucket fuera de AWS está versionada y define versionado, bloqueo y ciclo de vida 7/4/12

La configuración del bucket (como código o como procedimiento versionado en el repositorio) DEBE definir:
versionado activado; bloqueo de objeto en modo de cumplimiento habilitado desde la creación; reglas de ciclo de
vida por prefijo de 7 días para `daily/`, 4 semanas para `weekly/` y 12 meses para `monthly/`; y la credencial de
escritura del requisito anterior. El proveedor es Backblaze B2 (decisión del propietario del 2026-11-09).

#### Escenario: [BR23] [LOCAL] La configuración versionada contiene las reglas esperadas

- **DADO** la configuración del bucket entregada por este cambio
- **CUANDO** una comprobación estructural la lee
- **ENTONCES** declara versionado, bloqueo en modo de cumplimiento, y las tres reglas de ciclo de vida con sus
  duraciones, sin ningún valor de credencial

#### Escenario: [BR24] [PROVEEDOR] El bucket real coincide con la configuración versionada

- **DADO** el bucket real fuera de AWS en el proveedor
- **CUANDO** se compara su estado (versionado, bloqueo, reglas de ciclo de vida) con la configuración versionada
- **ENTONCES** coinciden sin diferencias, y la comparación queda en la evidencia del cambio

#### Escenario: [BR25] [PROVEEDOR] Un objeto vence y rota sin intervención

- **DADO** un objeto de prueba cuya retención y regla de ciclo de vida coinciden
- **CUANDO** transcurre su retención
- **ENTONCES** el ciclo de vida lo elimina sin que el bloqueo lo impida

### Requisito: Los códigos de salida del respaldo y la restauración distinguen cada clase de fallo

El respaldo y la restauración DEBEN terminar con `0` solo cuando todas sus etapas tuvieron éxito, y con un código
distinto de `0` documentado para cada clase de fallo: uso incorrecto, configuración ausente, volcado, cifrado,
subida, verificación de integridad, descarga, descifrado, restauración y verificación de la restauración. Un
fallo en una etapa NO DEBE continuar a las siguientes ni ser enmascarado por el éxito de una tubería.

#### Escenario: [BR26] [LOCAL] Un fallo del volcado no sube nada

- **DADO** un volcado que falla (base inalcanzable)
- **CUANDO** el respaldo termina
- **ENTONCES** el código es el del fallo de volcado, no existe ningún objeto nuevo en el bucket y la salida
  nombra la etapa

#### Escenario: [BR27] [LOCAL] Un fallo dentro de una tubería no se enmascara

- **DADO** una tubería de volcado y cifrado donde el volcado falla a mitad de camino y el cifrado termina bien
- **CUANDO** el respaldo evalúa el resultado
- **ENTONCES** termina con código distinto de `0` y no considera el objeto como respaldo válido

#### Escenario: [BR28] [LOCAL] Cada clase de fallo tiene un código propio

- **DADO** los fallos simulados de volcado, cifrado, subida y verificación de integridad
- **CUANDO** se recogen los códigos de salida
- **ENTONCES** son distintos entre sí, distintos de `0`, y coinciden con la tabla documentada en el runbook

### Requisito: La restauración descarga, verifica el hash y descifra antes de tocar el destino, y nunca opera contra producción

La restauración DEBE: descargar el objeto y su suma; verificar el sha256 antes de descifrar; descifrar con la llave
indicada por el operador; crear los roles de la base en el destino limpio antes de `pg_restore`; y restaurar. NO
DEBE ejecutarse contra la base de producción ni contra un destino que ya contenga datos, y DEBE rechazarlo antes
de modificar nada.

#### Escenario: [BR29] [LOCAL] Un hash que no coincide detiene la restauración

- **DADO** un objeto descargado cuyo contenido fue alterado después de calcular su suma
- **CUANDO** la restauración compara el sha256
- **ENTONCES** termina con el código de verificación de integridad, no intenta descifrar y no modifica el destino

#### Escenario: [BR30] [LOCAL] Los roles existen antes de restaurar y se conservan los permisos

- **DADO** un PostgreSQL limpio sin los roles de CONFIA
- **CUANDO** la restauración termina con código `0`
- **ENTONCES** los cinco roles existen, las políticas de fila y los permisos del esquema restaurado son los del
  origen, y `confia_admin_app` puede operar bajo la política de su institución

#### Escenario: [BR31] [LOCAL] La restauración se niega contra un destino con datos

- **DADO** un destino que ya contiene el esquema de CONFIA con filas, o que está marcado como producción
- **CUANDO** se invoca la restauración
- **ENTONCES** termina con el código de destino prohibido antes de descargar o modificar cualquier cosa

#### Escenario: [BR32] [LOCAL] Una llave age incorrecta detiene la restauración

- **DADO** un objeto íntegro y una llave age que no es la del destinatario
- **CUANDO** la restauración intenta descifrar
- **ENTONCES** termina con el código de descifrado y el destino queda intacto

### Requisito: La verificación de la restauración comprueba cuatro cosas y solo termina en `0` si todas pasan

La verificación de la restauración DEBE ejecutarse sobre la base restaurada y terminar con `0` únicamente si
todas estas comprobaciones pasan, y con un código distinto de `0` si cualquiera falla o no puede ejecutarse:

1. el historial de Flyway está completo: toda migración del artefacto figura aplicada con éxito, sin faltantes ni
   sumas de verificación divergentes;
2. el conteo de filas de cada tabla existente se obtiene e informa, y no hay ninguna tabla esperada ausente;
3. la cadena de auditoría de cada institución con asientos se verifica desde el registro génesis con el
   verificador de cadena existente (`DefaultAuditChainVerifier`);
4. la fila canario cifrada se descifra con la KEK custodiada y su texto plano coincide con el valor esperado.

Una comprobación omitida NO DEBE contar como aprobada. La salida DEBE nombrar la comprobación que falló.

#### Escenario: [BR33] [LOCAL] Base íntegra y llaves correctas terminan en 0

- **DADO** una base restaurada desde un respaldo real de la ida y vuelta, con la KEK correcta
- **CUANDO** se ejecuta la verificación
- **ENTONCES** las cuatro comprobaciones pasan, la salida las enumera con su resultado y el código es `0`

#### Escenario: [BR34] [LOCAL] Una cadena de auditoría alterada termina distinto de 0

- **DADO** una base restaurada en la que se alteró una fila de `shared_audit_log` sin recalcular la cadena
- **CUANDO** se ejecuta la verificación
- **ENTONCES** la comprobación de la cadena falla identificando la institución y la fila, y el código es distinto
  de `0`

#### Escenario: [BR35] [LOCAL] Una KEK equivocada termina distinto de 0

- **DADO** una base restaurada íntegra y una KEK que no es la custodiada
- **CUANDO** se ejecuta la verificación
- **ENTONCES** el descifrado del canario falla, la comprobación se reporta como fallida y el código es distinto
  de `0`

#### Escenario: [BR36] [LOCAL] Una migración faltante o una tabla ausente termina distinto de 0

- **DADO** una base restaurada a la que le falta una migración del historial de Flyway, o una tabla esperada
- **CUANDO** se ejecuta la verificación
- **ENTONCES** la comprobación del historial o de las tablas falla y el código es distinto de `0`

#### Escenario: [BR37] [LOCAL] Sin fila canario la verificación falla, no se omite

- **DADO** una base restaurada sin la fila canario
- **CUANDO** se ejecuta la verificación
- **ENTONCES** falla con un código distinto de `0` indicando que el canario no existe, en lugar de tratar la
  comprobación como no aplicable

### Requisito: Las verificaciones de fases futuras figuran como pendientes y no aportan un verde falso

La verificación de la restauración DEBE listar las comprobaciones que dependen de tablas de fases posteriores (libro
mayor, pagos, correlativos fiscales y similares) como pendientes por fase, con la fase que las introduce. Una
comprobación pendiente NO DEBE contarse como aprobada ni fallar la verificación mientras su tabla no exista, y DEBE
pasar a ser obligatoria cuando su tabla exista.

#### Escenario: [BR38] [LOCAL] Las comprobaciones futuras se informan como pendientes

- **DADO** la base restaurada de F0, sin tablas de libro mayor ni de pagos
- **CUANDO** se ejecuta la verificación
- **ENTONCES** la salida lista cada comprobación futura como «pendiente (fase X)» y no las suma a las aprobadas

#### Escenario: [BR39] [LOCAL] Una comprobación cuya tabla ya existe es obligatoria

- **DADO** una base restaurada que contiene una tabla cubierta por una comprobación marcada como pendiente de fase
- **CUANDO** se ejecuta la verificación
- **ENTONCES** la comprobación se ejecuta como obligatoria y su fallo hace fallar la verificación

### Requisito: Una fila canario cifrada permite probar la recuperación de datos cifrados

El sistema DEBE contar con un procedimiento que cree y conserve en la base una fila canario protegida con el cifrado
de columnas, con un texto plano conocido, y la verificación DEBE descifrarla con la KEK custodiada. La fila NO DEBE
contener datos de personas reales ni de menores.

#### Escenario: [BR40] [LOCAL] El canario se descifra tras la ida y vuelta

- **DADO** una base con la fila canario creada por el procedimiento, respaldada y restaurada
- **CUANDO** la verificación descifra la fila con la KEK de pruebas correcta
- **ENTONCES** obtiene el texto plano esperado

#### Escenario: [BR41] [LOCAL] El canario no contiene datos personales

- **DADO** la fila canario y el procedimiento que la crea
- **CUANDO** se revisa su contenido
- **ENTONCES** el texto plano es un valor sintético fijo sin datos de personas

### Requisito: La ida y vuelta completa pasa en integración continua y rompe la construcción si falla

El sistema DEBE ejecutar en integración continua, con PostgreSQL y MinIO en contenedores, el ciclo completo:
volcado como `confia_backup`, cifrado, sha256, subida con bloqueo, descarga, verificación del hash, descifrado,
restauración en una base limpia con roles y verificación de la restauración. Un fallo de cualquier etapa DEBE
romper la construcción con un resultado visible en el remoto.

#### Escenario: [BR42] [LOCAL] La ida y vuelta pasa de extremo a extremo

- **DADO** una base migrada con datos de dos instituciones, asientos de auditoría y la fila canario
- **CUANDO** se ejecuta el trabajo de ida y vuelta
- **ENTONCES** todas las etapas terminan en verde y la verificación de la restauración termina en `0`

#### Escenario: [BR43] [LOCAL] Una etapa rota rompe la construcción

- **DADO** un cambio que rompe el cifrado (por ejemplo, destinatario equivocado) o el descifrado
- **CUANDO** se ejecuta el trabajo de ida y vuelta
- **ENTONCES** el trabajo falla, el resultado en rojo es visible en el remoto y el empuje no se considera verificado

### Requisito: Los respaldos automáticos de RDS están habilitados con restauración a un punto en el tiempo

La instancia de RDS de preproducción DEBE tener los respaldos automáticos habilitados con un período de retención
mayor que cero, y la restauración a un punto en el tiempo DEBE ejecutarse con éxito según la ruta A del runbook.
El objetivo de punto de recuperación dentro de AWS es de 15 minutos.

#### Escenario: [BR44] [AWS] Los respaldos automáticos están habilitados

- **DADO** la instancia de RDS de preproducción aprovisionada por 11d
- **CUANDO** se consulta su configuración
- **ENTONCES** el período de retención de respaldos es mayor que cero y la métrica `confia_rds_backup_enabled`
  vale `1`

#### Escenario: [BR45] [AWS] [HUMANO] La restauración a un punto en el tiempo sigue la ruta A

- **DADO** un instante reciente elegido por el operador dentro del período restaurable
- **CUANDO** se restaura la instancia a ese punto según la ruta A del runbook y se ejecuta la verificación
- **ENTONCES** la instancia restaurada arranca, la verificación termina en `0` y los tiempos quedan en el acta

### Requisito: El temporizador de `systemd` ejecuta el respaldo nocturno y db-scheduler no lo hace

El respaldo nocturno DEBE ejecutarse mediante un servicio y un temporizador de `systemd` en la instancia de EC2,
con instalación documentada. El respaldo NO DEBE programarse con db-scheduler (ADR-0016), `@Scheduled` ni con
GitHub Actions. El temporizador DEBE disparar el respaldo a diario a la hora acordada en `docs/07` (01:00).

#### Escenario: [BR46] [LOCAL] Las unidades cumplen la forma esperada

- **DADO** el servicio y el temporizador entregados
- **CUANDO** una comprobación estructural los lee
- **ENTONCES** el temporizador declara la cadencia diaria de las 01:00, el servicio ejecuta el respaldo en modo
  nocturno sin contraseñas incrustadas y ninguna tarea de db-scheduler ni de GitHub Actions ejecuta el respaldo

#### Escenario: [BR47] [AWS] Siete respaldos nocturnos consecutivos

- **DADO** el temporizador instalado en preproducción
- **CUANDO** transcurren siete noches
- **ENTONCES** existen siete respaldos nocturnos consecutivos en el bucket y la métrica del último éxito avanza cada
  noche

### Requisito: Los scripts publican las métricas de respaldo y de simulacro

El respaldo y el simulacro DEBEN publicar, con los nombres exactos de `docs/07`:
`confia_backup_last_success_timestamp_seconds{backup_type="dump"}` y `confia_backup_duration_seconds` (respaldo);
`confia_restore_drill_last_success_timestamp_seconds` y `confia_restore_drill_duration_seconds` (simulacro); y se
DEBE recolectar `confia_rds_backup_enabled` y `confia_rds_restorable_time_lag_seconds` desde el estado de RDS. La
marca de último éxito SOLO DEBE avanzar tras un respaldo (o simulacro) completo y verificado, y NO DEBE retroceder
ni avanzar ante un fallo.

#### Escenario: [BR48] [LOCAL] Un respaldo exitoso publica sus dos métricas

- **DADO** un respaldo nocturno exitoso
- **CUANDO** se lee la salida de métricas que el recolector consumirá
- **ENTONCES** contiene `confia_backup_last_success_timestamp_seconds{backup_type="dump"}` con la hora de fin y
  `confia_backup_duration_seconds` con la duración medida

#### Escenario: [BR49] [LOCAL] Un respaldo fallido no avanza la marca de éxito

- **DADO** una marca de último éxito publicada y un respaldo posterior que falla
- **CUANDO** se lee la salida de métricas
- **ENTONCES** la marca de último éxito conserva su valor anterior

#### Escenario: [BR50] [LOCAL] El respaldo previo a la migración no avanza la marca nocturna

- **DADO** una marca de último éxito nocturno publicada
- **CUANDO** se ejecuta `pre-migration` con éxito
- **ENTONCES** la marca con `backup_type="dump"` no cambia

#### Escenario: [BR51] [CAMBIO-10] [AWS] Prometheus recolecta las seis métricas

- **DADO** Prometheus del cambio 10 recolectando el nodo y el estado de RDS en preproducción
- **CUANDO** se consultan las seis métricas
- **ENTONCES** todas devuelven series con valores recientes

### Requisito: Las cuatro alertas de respaldo existen con las condiciones de `docs/07`

El sistema DEBE definir las reglas de alerta con estas condiciones y severidades:

- `BackupMissing` (S1): `time() - confia_backup_last_success_timestamp_seconds{backup_type="dump"} > 93600`
  (26 horas);
- `RdsBackupDisabled` (S1): `confia_rds_backup_enabled == 0`;
- `RdsRestorableTimeLagHigh` (S1): `confia_rds_restorable_time_lag_seconds > 900` durante 10 minutos;
- `RestoreDrillOverdue` (S3): más de 35 días desde `confia_restore_drill_last_success_timestamp_seconds`.

#### Escenario: [BR52] [LOCAL] Las reglas se comportan como se especifica en pruebas unitarias de reglas

- **DADO** series sintéticas de las métricas en una prueba unitaria de reglas (`promtool test rules`)
- **CUANDO** el último éxito del respaldo tiene 27 horas, el estado de RDS tiene `backup_enabled` en `0`, el
  retraso restaurable pasa de 900 segundos durante 10 minutos y el último simulacro tiene 36 días
- **ENTONCES** disparan `BackupMissing`, `RdsBackupDisabled`, `RdsRestorableTimeLagHigh` y `RestoreDrillOverdue`
  respectivamente, con sus severidades

#### Escenario: [BR53] [LOCAL] Dentro de los límites ninguna alerta dispara

- **DADO** un último éxito de respaldo de 25 horas, RDS con respaldos habilitados, retraso de 600 segundos y un
  simulacro de 30 días
- **CUANDO** se evalúan las reglas en la prueba unitaria
- **ENTONCES** ninguna de las cuatro dispara

#### Escenario: [BR54] [CAMBIO-10] [AWS] `BackupMissing` dispara al detener el temporizador

- **DADO** Alertmanager del cambio 10 y el temporizador de respaldo en preproducción
- **CUANDO** se detiene el temporizador más de 26 horas
- **ENTONCES** `BackupMissing` se dispara y llega a su destino de notificación

### Requisito: El simulacro restaura un respaldo real en un entorno limpio, con tiempos medidos y acta

El simulacro DEBE descargar un respaldo real fuera de AWS, descifrarlo con la llave custodiada, restaurarlo en un
entorno limpio (sin datos ni acceso al entorno de producción), ejecutar la verificación de la restauración y medir
cada etapa. El tiempo de recuperación total DEBE ser menor a cuatro horas. El acta DEBE usar la plantilla del
cambio, registrar fecha, ejecutor, respaldo usado, tiempo de cada etapa contra su objetivo, resultado de cada
comprobación, quién tuvo acceso a las llaves durante el simulacro y la firma del propietario.

#### Escenario: [BR55] [LOCAL] La plantilla del acta contiene los campos obligatorios

- **DADO** la plantilla de acta entregada
- **CUANDO** una comprobación estructural la lee
- **ENTONCES** contiene los campos de fecha, ejecutor, respaldo, tiempos por etapa con su objetivo, el tiempo total
  contra las cuatro horas, resultado de las cuatro comprobaciones, registro de acceso a las llaves y firma del
  propietario

#### Escenario: [BR56] [AWS] [HUMANO] El simulacro restaura en menos de cuatro horas

- **DADO** un respaldo nocturno real de preproducción descargado fuera de AWS
- **CUANDO** el desarrollador ejecuta el simulacro en un entorno limpio con la llave custodiada
- **ENTONCES** la restauración y la verificación terminan en verde en menos de cuatro horas y la métrica
  `confia_restore_drill_last_success_timestamp_seconds` se publica

#### Escenario: [BR57] [HUMANO] El propietario firma el acta y se cierra el criterio de salida 6

- **DADO** el acta del simulacro completada con las cuatro comprobaciones en verde
- **CUANDO** el propietario la revisa y la firma
- **ENTONCES** el acta firmada queda archivada con la fecha y el criterio de salida 6 de `docs/09` se marca como
  cumplido con una referencia al acta

#### Escenario: [BR58] [HUMANO] Sin firma el criterio no se cierra

- **DADO** un simulacro con verificación en verde pero sin la firma del propietario
- **CUANDO** se evalúa el cierre del criterio de salida 6
- **ENTONCES** el criterio permanece abierto

### Requisito: Las llaves se custodian fuera del servidor, de AWS y del repositorio

La llave privada age y la KEK DEBEN custodiarse fuera del servidor de respaldo, de AWS y del repositorio, con una
copia física sellada en la institución, bajo custodia del propietario (decisión del 2026-11-09). El desarrollador
DEBE recibirlas solo durante un simulacro o un incidente, con registro en el acta. El acceso a las llaves DEBE
comprobarse cada mes.

#### Escenario: [BR59] [LOCAL] El servidor de respaldo no tiene la llave privada

- **DADO** la configuración de la unidad de respaldo y de sus variables
- **CUANDO** se revisa
- **ENTONCES** no contiene la llave privada age ni la KEK, y el respaldo funciona solo con la parte pública

#### Escenario: [BR60] [HUMANO] El propietario puede producir ambas llaves

- **DADO** la verificación mensual de acceso a las llaves
- **CUANDO** el propietario demuestra que puede acceder a la llave privada age y a la KEK desde su custodia
- **ENTONCES** el resultado se registra con fecha, y una verificación omitida más de un mes se reporta como
  incumplimiento

#### Escenario: [BR61] [HUMANO] La entrega temporal de llaves queda registrada

- **DADO** un simulacro o un incidente en que el propietario entrega las llaves al desarrollador
- **CUANDO** el desarrollador las usa y las devuelve
- **ENTONCES** el acta o el registro del incidente deja constancia de la entrega, el uso y la devolución, y las
  copias temporales se eliminan

### Requisito: La rotación de una llave exige restaurar con la llave nueva antes de retirar la anterior

El procedimiento de rotación de la llave age y de la KEK DEBE exigir, antes de retirar la llave anterior, que un
respaldo creado con la llave nueva se restaure y pase la verificación completa. La llave anterior DEBE conservarse
mientras existan respaldos cifrados con ella dentro de su retención.

#### Escenario: [BR62] [LOCAL] Un respaldo con la llave nueva se restaura y verifica

- **DADO** un respaldo cifrado al destinatario nuevo de age
- **CUANDO** se restaura con la llave privada nueva y se ejecuta la verificación
- **ENTONCES** la verificación termina en `0`, y la llave privada anterior ya no es necesaria para ese respaldo

> **Nota (2026-11-09):** el escenario BR63 (rotación de la KEK con reenvoltura de las llaves de datos) **se
> trasladó fuera de este cambio**, por decisión del propietario, al cambio futuro que cablee la KEK de cifrado de
> columnas en producción; hoy no existe mecanismo de reenvoltura que probar. La numeración no cambia y BR63 no es
> un escenario activo de `backup-and-restore` en este cambio. La regla de procedimiento de BR64 sigue vigente.

#### Escenario: [BR64] [HUMANO] No se retira la llave anterior sin restauración previa

- **DADO** una rotación en curso sin restauración registrada con la llave nueva
- **CUANDO** se solicita retirar la llave anterior
- **ENTONCES** el procedimiento lo prohíbe, y la retirada solo procede con el acta de la restauración exitosa

### Requisito: El runbook de restauración está adaptado a F0, declara los objetivos y exige la lista de eliminaciones pendientes

El runbook de restauración DEBE: ejecutar solo comprobaciones que existan en F0 y marcar las de fases futuras
(libro mayor, pagos, correlativos fiscales) como pasos por fase; validar la ruta A contra RDS; distinguir los
objetivos por ruta, con punto de recuperación de 15 minutos dentro de AWS (ruta A) y de hasta 24 horas ante la
pérdida total de la cuenta de AWS (ruta B), aceptado por el propietario mientras no existan datos reales y por
revisar antes del primer dato real; y exigir como paso obligatorio, tras toda restauración que contenga datos de
titulares, reaplicar la lista de eliminaciones pendientes de propagación (`docs/08`). Mientras esa lista no
exista, el runbook DEBE decir que el paso está bloqueado y no asignado, y DEBE impedir cargar datos reales.

#### Escenario: [BR65] [LOCAL] El runbook no ejecuta comprobaciones inexistentes en F0

- **DADO** el runbook entregado
- **CUANDO** se revisan sus comprobaciones ejecutables
- **ENTONCES** cada una apunta a tablas o rutinas existentes en F0, y las demás figuran marcadas como pendientes
  de fase

#### Escenario: [BR66] [LOCAL] Los objetivos distinguen las dos rutas

- **DADO** el encabezado del runbook
- **CUANDO** se lee
- **ENTONCES** declara 15 minutos para la ruta A, hasta 24 horas para la ruta B con la aceptación del propietario y
  su condición de revisión, y menos de cuatro horas de tiempo de recuperación

#### Escenario: [BR67] [LOCAL] El paso de eliminaciones pendientes está presente y marcado como bloqueado

- **DADO** el runbook entregado
- **CUANDO** se revisa el procedimiento posterior a la restauración
- **ENTONCES** incluye el paso obligatorio de reaplicar la lista de eliminaciones pendientes, indica que su
  implementación aún no existe y no tiene dueño, y condiciona cargar datos reales a resolverlo

#### Escenario: [BR68] [HUMANO] El simulacro de salida anual tiene procedimiento

- **DADO** el runbook entregado
- **CUANDO** se revisa la sección del simulacro anual
- **ENTONCES** describe cómo restaurar en un proveedor distinto de AWS con la llave custodiada; su ejecución es
  posterior a F0 y no forma parte del cierre de este cambio

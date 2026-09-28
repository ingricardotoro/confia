# CONFIA — Seguridad

> Estado: **Propuesta v1.0**. Documento maestro de seguridad.
> Subordinado a `docs/01-arquitectura.md`, que es la fuente de verdad de la arquitectura.
> Si algo aquí contradice ese documento, gana el documento de arquitectura.

Este sistema mueve dinero real y almacena datos personales de **menores de edad**, y lo mantiene
**un solo desarrollador**. Esa combinación define el criterio de diseño de todo lo que sigue:
se prefiere siempre el control que el motor de base de datos, el sistema operativo o la
integración continua aplican por sí mismos frente al control que depende de que el programador
recuerde aplicarlo. Un control que solo vive en la disciplina humana no es un control: es una
intención.

**Regla de verificabilidad.** Cada control de este documento declara cómo se comprueba que está
activo. Un control sin método de comprobación se considera no implementado.

---

## 1. Estándar de referencia adoptado

| Estándar | Uso en CONFIA | Alcance |
|---|---|---|
| **OWASP ASVS 4.0.3, nivel 2** | Objetivo formal de verificación de la aplicación | Todo el sistema. Nivel 2 es el nivel adecuado para aplicaciones que manejan transacciones significativas. |
| **OWASP Top 10 (2021)** | Mapeo de riesgos y guía de revisión de código | Uso como lista de comprobación en revisión de pull request |
| **OWASP API Security Top 10 (2023)** | Guía específica del contrato REST | `apps/api` en sus dos perfiles de despliegue |
| **NIST SP 800-63B** | Política de autenticación y contraseñas | Módulo `identity` |
| **CIS Benchmarks** (Docker, PostgreSQL, Debian/Ubuntu) | Endurecimiento de servidor y contenedores | `infra/` |
| **PCI DSS v4.0, SAQ A** | Alcance de pagos con tarjeta | Integración de pasarela, fase F9 |

### 1.1 Mapeo OWASP Top 10 a controles de este documento

| Riesgo | Control principal en CONFIA | Sección |
|---|---|---|
| A01 Control de acceso roto | RBAC con permisos granulares más seguridad a nivel de fila en PostgreSQL más verificación a nivel de objeto | 5, 6 |
| A02 Fallas criptográficas | TLS 1.3, Argon2id, cifrado de columna con sobre de llaves | 4, 7 |
| A03 Inyección | Consultas parametrizadas en todo acceso a datos, prohibición de concatenación SQL, Jakarta Bean Validation en el borde del backend | 9 |
| A04 Diseño inseguro | Modelo de amenazas STRIDE, segregación de funciones, libro mayor inmutable | 2, 5 |
| A05 Configuración de seguridad incorrecta | Cabeceras de navegador, endurecimiento de servidor, imágenes mínimas | 8, 15 |
| A06 Componentes vulnerables | Fijado de versiones, Renovate, auditoría de dependencias, SBOM | 13 |
| A07 Fallas de identificación y autenticación | MFA TOTP, retroceso exponencial, prevención de enumeración | 4 |
| A08 Fallas de integridad de software y datos | Firma de imágenes, bitácora encadenada por hash, verificación de firma de webhook | 12, 13, 16 |
| A09 Fallas de registro y monitoreo | Bitácora de auditoría, logs estructurados, alertas accionables | 12, y `docs/07-observabilidad-y-operaciones.md` |
| A10 Falsificación de solicitudes del lado del servidor | Lista blanca de destinos salientes, resolución DNS controlada | 9 |

### 1.2 Mapeo OWASP API Security Top 10

| Riesgo | Control principal | Sección |
|---|---|---|
| API1 Autorización a nivel de objeto rota | Verificación de propiedad en cada caso de uso más seguridad a nivel de fila | 5.4, 6 |
| API2 Autenticación rota | Ver sección 4 completa | 4 |
| API3 Autorización a nivel de propiedad de objeto rota | DTO explícitos de salida, nunca serialización directa de entidades | 9.2 |
| API4 Consumo de recursos sin restricción | Límites de tasa, límite de tamaño de carga útil, paginación con tope en servidor | 10 |
| API5 Autorización a nivel de función rota | Permisos declarados por controlador y verificados por Spring Security, prueba automática de cobertura de permisos | 5 |
| API6 Acceso sin restricción a flujos de negocio sensibles | Idempotencia, segregación de funciones, límites por cuenta | 5.3, 10 |
| API7 Falsificación de solicitudes del lado del servidor | Lista blanca de destinos salientes | 9.3 |
| API8 Configuración de seguridad incorrecta | Cabeceras, CORS por lista blanca, dos perfiles de despliegue | 8 |
| API9 Gestión inadecuada de inventario | OpenAPI 3.1 versionado y publicado, inventario de endpoints en integración continua | 14 |
| API10 Consumo inseguro de API | Validación de la respuesta de la pasarela con Bean Validation sobre un DTO explícito, verificación de firma | 16 |

**Comprobación del estándar.** Se mantiene `docs/seguridad/asvs-nivel-2.md` con la lista de
requisitos ASVS nivel 2, cada uno con estado (cumple, no cumple, no aplica), evidencia y fecha de
última verificación. Ese archivo se revisa en cada cierre de fase. Un requisito sin evidencia
cuenta como no cumple.

---

## 2. Modelo de amenazas STRIDE por superficie

Se modelan seis superficies. Para cada amenaza se declara vector, impacto y control, y el control
indica dónde se verifica.

### 2.1 Superficie A: portal público de encargados

Expuesto a internet abierto. Identidad de bajo privilegio. Es la superficie con mayor volumen de
ataque automatizado y la de menor privilegio efectivo por diseño.

| STRIDE | Amenaza | Vector | Impacto | Control |
|---|---|---|---|---|
| **S** Suplantación | Toma de control de cuenta de encargado | Relleno de credenciales con listas filtradas, phishing del enlace de recuperación | Acceso al estado de cuenta y a los datos del menor asociado | Argon2id, verificación contra listas de contraseñas comprometidas, retroceso exponencial por cuenta y por IP, MFA TOTP opcional pero promovida, token de recuperación de un solo uso con vida de 30 minutos, notificación por correo de todo cambio de credencial |
| **S** Suplantación | Registro de un encargado que no lo es | Alta libre con correo arbitrario reclamando vínculo con un estudiante | Acceso a datos de un menor ajeno | El alta no se autoservicio pura: el vínculo encargado-estudiante lo crea el personal administrativo, y el registro solo activa una credencial sobre un vínculo preexistente, con código de activación entregado por canal verificado |
| **T** Manipulación | Alteración del monto en el inicio de pago | Modificación del cuerpo de la solicitud de inicio de pago | Pago por menos de lo debido | El monto nunca viaja desde el cliente. El caso de uso recalcula el importe desde el libro mayor y crea la intención de pago del lado servidor |
| **R** Repudio | Encargado niega haber solicitado una operación | Ausencia de rastro | Disputa sin evidencia | Bitácora de auditoría con actor, IP, agente de usuario e identificador de solicitud para toda acción del portal |
| **I** Divulgación | Referencia directa insegura a objeto | Cambiar el identificador de estudiante en la URL o el cuerpo | Fuga de datos de un menor | Identificadores opacos (UUIDv7), verificación de propiedad en el caso de uso y **seguridad a nivel de fila en PostgreSQL** como segunda barrera independiente |
| **I** Divulgación | Enumeración de cuentas | Diferencia de mensaje o de tiempo entre correo existente y no existente | Lista de correos válidos de encargados | Respuesta y tiempo uniformes en login, recuperación y registro. Ver 4.6 |
| **D** Denegación | Agotamiento de recursos | Reportes pesados, paginación sin tope, carga de archivos grandes | Portal caído en fecha de pago | Límite de tasa agresivo, tope de página en servidor, límite de tamaño de carga útil, reportes pesados solo por cola, nunca sincrónicos en el portal |
| **E** Elevación | Alcanzar funciones administrativas | Adivinar rutas administrativas desde el portal | Compromiso total | **El proceso `confia-api-portal` no tiene cargados los módulos administrativos.** Esas rutas no existen en ese binario. Además, rol de PostgreSQL distinto y mínimo |
| **E** Elevación | Token del portal aceptado por la API administrativa | Reutilización de token entre dominios de identidad | Compromiso total | Claves de firma distintas y `aud` distinto. Un token del portal es criptográficamente inválido contra la API administrativa |

### 2.2 Superficie B: API administrativa

No pública. Detrás de restricción por dirección IP institucional o VPN, y MFA obligatoria. Alto
privilegio. La amenaza dominante aquí no es el atacante externo sino el abuso interno.

| STRIDE | Amenaza | Vector | Impacto | Control |
|---|---|---|---|---|
| **S** Suplantación | Uso de la sesión de un compañero | Estación desatendida, cookie robada | Operaciones financieras atribuidas a otro | Expiración de sesión inactiva de 30 minutos, expiración absoluta de 12 horas, cookies `HttpOnly`, `Secure`, `SameSite=Strict`, vinculación de sesión a huella de agente de usuario e IP con revalidación ante cambio |
| **T** Manipulación | Alteración de un registro de deuda | Uso legítimo de permisos de escritura para reducir la deuda de un estudiante | Pérdida económica y fraude | Libro mayor inmutable, sin actualización ni borrado. Toda corrección es un asiento de reverso con motivo obligatorio. Los ajustes exigen aprobación de un segundo actor. Ver 5.3 |
| **T** Manipulación | Manipulación de la bitácora | Actualización o borrado en la tabla de auditoría | Encubrimiento | Permisos de PostgreSQL sin `UPDATE` ni `DELETE` para el rol de aplicación, más disparador que rechaza ambas operaciones, más encadenamiento por hash. Ver 12 |
| **R** Repudio | Cajero niega haber anulado una factura | Registro insuficiente | Disputa laboral y fiscal | Auditoría obligatoria de emisión, anulación, nota de crédito, ajuste, apertura y cierre de caja, con valores anteriores y posteriores |
| **I** Divulgación | Exportación masiva de datos personales | Uso del permiso de exportar para extraer el padrón completo | Fuga masiva, incluida información de menores | El verbo `exportar` es un permiso separado de `leer`, se audita siempre con el filtro aplicado y el conteo de filas, tiene límite de tasa propio y genera alerta cuando supera un umbral configurable de filas |
| **D** Denegación | Consulta de reporte que bloquea la base | Reporte sin índice sobre todo el histórico | Operación de caja detenida | `statement_timeout` por rol, reportes pesados en cola con réplica de lectura desde fase dos, presupuesto de consulta en revisión de código |
| **E** Elevación | Autoasignación de rol | Un Administrador se concede permisos de Super Administrador | Compromiso total del control interno | La asignación de roles es permiso exclusivo de Super Administrador, no puede autoasignarse un rol, y todo cambio de rol genera alerta al canal de seguridad y a un segundo destinatario |

### 2.3 Superficie C: base de datos

| STRIDE | Amenaza | Vector | Impacto | Control |
|---|---|---|---|---|
| **S** Suplantación | Conexión con credencial de aplicación desde fuera | Credencial filtrada en repositorio, log o volcado | Lectura total | PostgreSQL sin puerto público, `pg_hba.conf` restringido a la red privada, secretos fuera del repositorio, gitleaks en integración continua, rotación documentada |
| **T** Manipulación | Escritura directa por SQL fuera de la aplicación | Acceso administrativo al motor | Alteración de dinero sin rastro | Acceso interactivo al motor solo por túnel SSH con llave, sesiones registradas, y la cadena de hash de auditoría revela cualquier alteración de la bitácora. Las escrituras directas en producción requieren registro previo en el diario de operaciones |
| **R** Repudio | Cambio sin autor identificable | Uso de un rol compartido | Sin trazabilidad | Un rol de base por perfil de proceso, más el actor de aplicación establecido en el contexto de sesión y persistido en cada fila de auditoría |
| **I** Divulgación | Robo del volcado de respaldo | Copia fuera de sitio sin cifrar | Fuga masiva | Respaldos cifrados con age antes de salir del host, llave de restauración custodiada aparte, cifrado de disco en reposo |
| **I** Divulgación | Lectura de identificadores nacionales en claro | Acceso de solo lectura al motor o al respaldo | Fuga de documentos de identidad de menores y responsables | Cifrado a nivel de columna con sobre de llaves. Ver 7.3 |
| **D** Denegación | Agotamiento de conexiones | Fuga de conexiones o pico de tráfico | Caída total | PgBouncer o límite de pool por proceso, `max_connections` dimensionado, alerta de saturación de pool |
| **E** Elevación | El rol del portal alcanza tablas administrativas | Error de concesión de permisos | Fuga de usuarios, CAI y auditoría | Concesiones explícitas por tabla, `REVOKE ALL` de partida, prueba de integración que falla si el rol del portal puede leer una tabla prohibida. Ver 6.4 |

### 2.4 Superficie D: trabajos en segundo plano

Los trabajos en segundo plano usan **db-scheduler sobre PostgreSQL** (ADR-0016). El almacén de
trabajos es una tabla técnica de la base existente: no hay intermediario de mensajes ni colas en
Redis. Las tareas se programan dentro de la transacción del negocio a través del componente
transaccional único (ADR-0015) y solo el proceso `confia-worker` las ejecuta; los procesos
administrativo y del portal solo programan, siempre desde un caso de uso.

La tabla de tareas (`scheduled_tasks`) y la de publicación de eventos de Spring Modulith
(`event_publication`) son tablas técnicas sin política de fila, dentro del catálogo cerrado de
ADR-0017. Por eso ni los datos de tarea ni los eventos de dominio que se persisten llevan datos
personales: solo identificadores y hechos, nunca nombres, correos, teléfonos ni documentos de
identidad. El proceso del portal no usa el registro de eventos: las reacciones durables de sus casos
de uso se programan como tareas, y `confia_portal_app` no tiene ningún privilegio sobre
`event_publication` (sección 6.1).

| STRIDE | Amenaza | Vector | Impacto | Control |
|---|---|---|---|---|
| **S** Suplantación | Inyección de tarea falsa | Escritura directa en la tabla de tareas | Generación de cargos o notificaciones fraudulentas | La tabla de tareas vive en PostgreSQL, sin puerto público y en red privada. Solo los roles de aplicación la alcanzan, y solo desde un caso de uso. Los manejadores revalidan contra la base de datos todo lo que la tarea referencia |
| **T** Manipulación | Alteración de los datos de una tarea programada | Escritura directa en la tabla de tareas | Cargo por monto arbitrario | Los datos de la tarea llevan solo identificadores (institución, entidad, clave de idempotencia), nunca importes. El manejador recalcula todo importe desde la base de datos dentro de su transacción |
| **R** Repudio | Un cargo aparece sin origen | Tarea sin trazabilidad | Reclamo de un padre sin respuesta | Toda tarea lleva `requestId` y `correlationId`, y el asiento resultante referencia el identificador de ejecución de la tarea |
| **I** Divulgación | Datos personales en los datos de la tarea | Nombres y documentos en la tabla de tareas, visibles en consultas de diagnóstico o respaldos | Fuga | Prohibido incluir nombres, correos, teléfonos, documentos de identidad o importes en claro. Solo identificadores. Verificado por prueba de lista aprobada de campos por tipo de tarea (ADR-0016) |
| **D** Denegación | Tarea envenenada | Tarea que siempre falla y consume el trabajador | Notificaciones y cargos detenidos | Número máximo de intentos y espera exponencial por tipo de tarea; al agotarse queda en estado fallido con alerta, nunca se descarta en silencio. Alerta por antigüedad de la tarea vencida más antigua |
| **E** Elevación | Una tarea se ejecuta sin contexto de autorización | Trabajador con permisos totales | Operación sin control o lectura entre instituciones | El manejador ejecuta a través del componente transaccional con el contexto de seguridad a nivel de fila de la institución indicada en la tarea y el tipo de actor `system` (sección 6.2), auditado como cualquier actor humano. Prueba de integración que verifica que solo ve datos de esa institución |

### 2.5 Superficie E: webhooks de pasarela de pago

| STRIDE | Amenaza | Vector | Impacto | Control |
|---|---|---|---|---|
| **S** Suplantación | Webhook falsificado que acredita un pago inexistente | Envío directo al endpoint público | Deuda cancelada sin dinero | **Verificación obligatoria de firma HMAC** con el secreto compartido, comparación en tiempo constante, rechazo si falta la firma. Sin firma válida no hay procesamiento |
| **T** Manipulación | Reproducción de un webhook válido antiguo | Reenvío del cuerpo original firmado | Doble acreditación | Marca de tiempo dentro del cuerpo firmado con ventana de tolerancia de 5 minutos, más idempotencia por identificador de evento del proveedor con índice único |
| **R** Repudio | El proveedor niega haber enviado el evento | Sin evidencia | Disputa de liquidación | Persistencia del cuerpo crudo, cabeceras y firma recibidos, antes de cualquier procesamiento, en una tabla de solo inserción |
| **I** Divulgación | Fuga del secreto de firma | Secreto en repositorio o en log | Falsificación de pagos | Secreto en gestor de secretos, nunca en repositorio, rotación documentada con soporte de dos secretos activos durante la ventana de rotación |
| **D** Denegación | Inundación del endpoint de webhook | Envío masivo | Cola saturada | Límite de tasa por IP de origen del proveedor, aceptación rápida con `202` y procesamiento asíncrono, tope de tamaño de cuerpo |
| **E** Elevación | El endpoint de webhook alcanza operaciones administrativas | Reutilización de un controlador general | Escalada | El webhook tiene su propio controlador, su propio caso de uso y un actor de sistema con un único permiso: acreditar pago de pasarela |

### 2.6 Superficie F: el equipo del desarrollador

Superficie frecuentemente omitida y, con un solo desarrollador, la de mayor concentración de
riesgo. Este equipo tiene acceso a producción, a los secretos y al código.

| STRIDE | Amenaza | Vector | Impacto | Control |
|---|---|---|---|---|
| **S** Suplantación | Robo de la sesión de GitHub o del proveedor de nube | Malware, phishing, cookie robada | Despliegue de código malicioso a producción | MFA con llave de hardware (WebAuthn) en GitHub, proveedor de nube, registro de contenedores y gestor de secretos. Sin excepciones ni códigos SMS |
| **S** Suplantación | Uso de la llave SSH de producción | Llave sin frase de paso copiada del equipo | Acceso total al servidor | Llave SSH con frase de paso, almacenada en el agente del sistema operativo, distinta por entorno, y registrada con caducidad y rotación anual |
| **T** Manipulación | Dependencia maliciosa ejecuta código en la instalación | Ataque de confusión de dependencias o de tipografía | Robo de secretos del entorno de desarrollo | Fijado exacto de versiones: en el frontend, archivo de bloqueo y `pnpm` con `ignore-scripts` donde sea viable; en el backend, Maven Wrapper comprometido, versiones gestionadas por la lista de materiales de Spring Boot y `maven-enforcer-plugin`. Revisión obligatoria de toda incorporación de dependencia nueva, Renovate con periodo de maduración mínimo |
| **T** Manipulación | Extensión de editor comprometida | Extensión con acceso al espacio de trabajo | Exfiltración de código y secretos | Inventario mínimo de extensiones, sin extensiones de terceros con permiso de red en el repositorio de CONFIA, secretos fuera del árbol del proyecto |
| **R** Repudio | Cambio en producción sin registro | Edición manual en el servidor | Estado no reproducible | Prohibición de edición manual en producción. Todo cambio pasa por integración continua. El diario de operaciones registra cualquier excepción con motivo |
| **I** Divulgación | Volcado de producción en el equipo local | Depuración con datos reales | Datos de menores fuera del entorno controlado | **Prohibido copiar datos de producción al equipo local.** Los entornos no productivos usan datos sintéticos o un volcado anonimizado generado por script. Ver `docs/08-datos-privacidad-y-retencion.md` |
| **D** Denegación | Pérdida o robo del equipo | Físico | Interrupción del mantenimiento | Cifrado de disco completo activado, respaldo del entorno de trabajo, credenciales revocables desde otro dispositivo, procedimiento de revocación de emergencia documentado en el runbook de incidente |
| **E** Elevación | Ransomware cifra el repositorio local y los respaldos accesibles | Adjunto o descarga | Pérdida de código y de respaldos | Respaldos con copia inmutable fuera de sitio, no montada como unidad de red escribible desde el equipo. Ver 2.7 y `docs/runbooks/restauracion-de-respaldo.md` |

### 2.7 Actores de amenaza realistas

El modelo STRIDE describe categorías. Estos son los cinco adversarios concretos que este sistema
va a enfrentar, ordenados por probabilidad real.

#### Actor 1: padre que quiere ver el estado de cuenta de otro estudiante

- **Motivación.** Curiosidad social, conflicto familiar, disputa de custodia, comparación de becas.
- **Capacidad.** Baja. Navegador, herramientas de desarrollo, cambio de valores en la URL.
- **Vector probable.** Cambiar un identificador en la URL o en el cuerpo de una solicitud.
  Reutilizar el enlace de un estado de cuenta compartido por otro padre. Intentar registrarse
  reclamando un estudiante ajeno.
- **Impacto.** Fuga de datos de un menor. Impacto reputacional y legal desproporcionado respecto
  del esfuerzo del atacante.
- **Control.** Tres barreras independientes: identificadores opacos UUIDv7 sin secuencialidad,
  verificación de propiedad en el caso de uso, y **seguridad a nivel de fila en PostgreSQL** que
  filtra por el encargado en sesión aunque el código falle. El vínculo encargado-estudiante lo
  crea el personal, no el autoservicio.
- **Cómo se comprueba.** Prueba de integración obligatoria por cada política de fila: el
  encargado A consulta el estudiante de B y recibe cero filas, no un error de permiso. Prueba de
  extremo a extremo en Playwright que manipula el identificador y espera 404.

#### Actor 2: empleado que quiere desviar efectivo

- **Motivación.** Económica. Es el actor con mayor probabilidad de causar pérdida real.
- **Capacidad.** Alta dentro de su privilegio legítimo. Conoce los procesos y los horarios.
- **Vector probable.** Cobrar en efectivo y no registrar. Registrar, entregar recibo y anular
  después. Registrar un descuento inexistente. Cerrar caja declarando el monto que le conviene.
  Emitir una nota de crédito sin justificación real.
- **Impacto.** Pérdida económica sostenida y difícil de detectar sin control interno.
- **Control.** Este es un problema de **control interno**, no de criptografía. Sesiones de caja
  obligatorias con fondo inicial y arqueo, bloqueo de cobro fuera de sesión abierta, anulación y
  nota de crédito con aprobación de un segundo actor (segregación de funciones, ver 5.3),
  numeración de recibos sin huecos no justificados, alerta por diferencia de caja, alerta por
  tasa de anulación por cajero superior a la media, y bitácora inmutable que sostiene tanto la
  acusación como la defensa del cajero honesto.
- **Cómo se comprueba.** El reporte trimestral de segregación de funciones no debe mostrar ningún
  rol con permisos de registrar y aprobar la misma operación. Métrica de anulaciones por cajero
  en el tablero operativo con alerta por desviación.

#### Actor 3: atacante externo automatizado buscando credenciales

- **Motivación.** Oportunista, no dirigida. Busca cualquier sistema con credenciales reutilizadas.
- **Capacidad.** Media, pero con volumen alto y costo cero. Botnets, listas de credenciales
  filtradas, escáneres de rutas conocidas.
- **Vector probable.** Relleno de credenciales contra el portal, fuerza bruta sobre recuperación
  de contraseña, escaneo de rutas de administración conocidas, explotación de una dependencia
  vulnerable publicada, búsqueda de secretos en el repositorio si llegara a hacerse público.
- **Impacto.** Toma de control de cuentas de encargados con contraseñas reutilizadas.
- **Control.** Verificación contra listas de contraseñas comprometidas en el alta y el cambio,
  retroceso exponencial por cuenta y por IP, MFA, ausencia total de rutas administrativas en el
  proceso del portal, restricción por IP institucional en la API administrativa, actualización de
  dependencias automatizada, gitleaks en cada commit y en el historial completo.
- **Cómo se comprueba.** Prueba de extremo a extremo que ejecuta N intentos fallidos y verifica el
  bloqueo. Métrica de intentos fallidos por minuto con alerta. Ejecución de gitleaks sobre todo
  el historial en cada corrida de integración continua.

#### Actor 4: atacante que quiere alterar registros de deuda

- **Motivación.** Cancelar una deuda propia o de un tercero a cambio de pago.
- **Capacidad.** Variable. Puede ser un empleado con acceso, alguien con credenciales robadas, o
  un tercero con acceso temporal al equipo del desarrollador.
- **Vector probable.** `UPDATE` directo sobre la base de datos. Uso de un permiso de ajuste sin
  supervisión. Borrado de la evidencia en la bitácora tras la alteración.
- **Impacto.** Pérdida económica y, peor, pérdida de confiabilidad de todo el sistema. Si el saldo
  puede alterarse sin rastro, ningún reporte del sistema tiene valor probatorio.
- **Control.** El diseño hace la alteración detectable en vez de imposible, que es el objetivo
  alcanzable. Libro mayor de doble partida inmutable, saldo derivado y nunca almacenado como
  campo mutable, bitácora de solo inserción encadenada por hash con verificación periódica,
  permisos de PostgreSQL que niegan `UPDATE` y `DELETE` sobre auditoría y asientos, trabajo
  nocturno de integridad que recalcula saldos y verifica el cuadre del debe y el haber, y copia
  del ancla de hash a almacenamiento externo de solo escritura.
- **Cómo se comprueba.** El trabajo nocturno de verificación de cadena debe reportar cadena
  íntegra. Una discrepancia dispara la alerta crítica y el runbook
  `docs/runbooks/descuadre-de-libro-mayor.md`.

#### Actor 5: ransomware

- **Motivación.** Extorsión. No dirigida al sistema, sino a cualquier objetivo alcanzable.
- **Capacidad.** Alta en propagación. Cifra todo lo que la cuenta comprometida puede escribir,
  incluidas unidades de red montadas y respaldos accesibles.
- **Vector probable.** Compromiso del equipo del desarrollador (credencial de AWS filtrada, sesión
  de Session Manager secuestrada) o dependencia maliciosa. No hay puerto SSH que forzar
  (`docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`), pero una credencial de AWS con
  permisos de instancia sigue siendo un vector real. Propagación a los respaldos si están
  montados como escribibles.
- **Impacto.** Pérdida total de la base de datos financiera. Este es el escenario del que una
  institución no se recupera.
- **Control.** La defensa efectiva es el respaldo que el atacante no puede escribir. Regla tres,
  dos, uno con copia fuera de sitio en almacenamiento de objetos con **bloqueo de objeto en modo
  de cumplimiento** y versionado, credencial de escritura de respaldo distinta y sin permiso de
  borrado ni de sobrescritura, respaldos cifrados con age, respaldo automático de RDS for
  PostgreSQL con restauración a un punto en el tiempo dentro de AWS, y simulacro mensual de
  restauración documentado (ADR-0014).
- **Cómo se comprueba.** El simulacro mensual restaura sobre un entorno limpio y verifica que el
  objetivo de tiempo de recuperación de cuatro horas se cumple. Se comprueba trimestralmente que
  la credencial usada por el trabajo de respaldo **no** puede borrar ni sobrescribir un objeto
  existente, ejecutando el intento y confirmando el rechazo.

---

## 3. Principios transversales

1. **Denegación por defecto.** Todo endpoint requiere autenticación y un permiso declarado. Un
   controlador sin permiso declarado falla la construcción, no queda abierto.
2. **Defensa en profundidad.** Ningún dato sensible depende de una sola barrera. La regla práctica
   es: código de aplicación más motor de base de datos, siempre.
3. **Mínimo privilegio.** Roles de base de datos, roles de aplicación, permisos de nube y llaves
   SSH se conceden por necesidad demostrada, no por comodidad.
4. **Fallo cerrado.** Si el servicio de autorización, el verificador de MFA o el motor de
   políticas no responde, la operación se rechaza. Nunca se continúa asumiendo permiso.
5. **Auditabilidad.** Toda acción sensible deja rastro inmutable. Ver sección 12.
6. **Secretos fuera del código.** Sin excepciones, ni en pruebas ni en comentarios.

---

## 4. Controles de autenticación

### 4.1 Hash de contraseñas: Argon2id

Se adopta **Argon2id** por medio de una implementación madura para la JVM, integrada con el
codificador de contraseñas de Spring Security. La biblioteca concreta y su forma de aplicar la
pimienta se validan en F0. No se usa bcrypt (limitación de 72 bytes y sin resistencia a hardware
especializado) ni PBKDF2.

Parámetros recomendados de partida, alineados con OWASP Password Storage Cheat Sheet y con el
perfil de servidor de fase uno. Los nombres de la tabla son conceptuales; cada biblioteca los
expone con su propia nomenclatura:

| Parámetro | Valor | Justificación |
|---|---|---|
| `memoryCost` | 19456 KiB (19 MiB) | Mínimo recomendado por OWASP para Argon2id con `timeCost` 2 |
| `timeCost` | 3 | Un paso por encima del mínimo, margen para hardware modesto |
| `parallelism` | 1 | Recomendación OWASP para el perfil de menor memoria |
| `outputLen` | 32 bytes | Estándar |
| Sal | 16 bytes aleatorios por contraseña | Generada por la biblioteca |
| Pimienta | Secreto de 32 bytes fuera de la base de datos | Aplicado como `secret` de Argon2id. Un volcado de base sin el gestor de secretos no permite ataque por diccionario |

**Calibración obligatoria antes de producción.** Los valores anteriores son el piso, no el
objetivo. Se ejecuta la prueba de calibración de Argon2 del backend (definida en F0) en el
servidor real, dentro del contenedor y con los límites de memoria de producción, y se ajusta
`memoryCost` al valor
más alto cuyo tiempo de verificación se mantenga entre 250 y 500 milisegundos bajo carga
concurrente esperada. El valor elegido se registra en el ADR correspondiente con la fecha y el
hardware.

**Rehash transparente.** Al iniciar sesión con éxito, si los parámetros almacenados difieren de
los vigentes, se recalcula el hash con los parámetros nuevos dentro de la misma transacción.

**Cómo se comprueba.** Prueba unitaria que verifica que el hash almacenado comienza con
`$argon2id$` y declara los parámetros vigentes. Prueba de rendimiento en integración continua que
falla si la verificación baja de 100 milisegundos (parámetros demasiado débiles).

> **Nota editorial, 2026-09-24 (`identity-module-and-password-authentication`), autorizada por el
> propietario el 2026-09-24.** Este cambio implementa Argon2id **sin** Spring Security, sobre Bouncy
> Castle directo, porque la cadena de filtros pertenece a `session-tokens-and-web-layer` y traerla
> aquí adelantaría trabajo de otro cambio. La exigencia sustantiva de este mismo apartado —la
> pimienta de 32 bytes fuera de la base, aplicada como `secret` de Argon2id— **se cumple**, y es
> precisamente lo que empuja a Bouncy Castle directo: `Argon2PasswordEncoder` de
> `spring-security-crypto` no expone ningún parámetro de secreto (verificado con `javap`, sonda S2),
> mientras que `Argon2Parameters.Builder` de Bouncy Castle sí lo hace con `withSecret(...)`. La
> integración con `PasswordEncoder` sigue siendo trivial el día que llegue la cadena de filtros,
> porque el puerto `PasswordHasher` tiene exactamente esa forma. Esta nota no renuncia a la
> integración: registra que llega con el cambio que trae el marco.

### 4.2 Política de contraseñas alineada a NIST SP 800-63B

| Regla | Valor | Razón |
|---|---|---|
| Longitud mínima | **12 caracteres** para personal, **10** para encargados | NIST exige 8 como mínimo absoluto; se eleva por el perfil financiero |
| Longitud máxima | 128 caracteres | Se acepta cualquier frase de paso razonable. No se trunca nunca |
| Conjunto de caracteres | Todo Unicode imprimible, incluidos espacios y emoji | NIST 5.1.1.2. Normalización NFKC antes de hashear |
| Reglas de composición | **Ninguna.** No se exige mayúscula, número ni símbolo | NIST las desaconseja: producen contraseñas predecibles como `Password1!` |
| Rotación forzada periódica | **No existe.** Sin caducidad arbitraria | NIST 5.1.1.2. La rotación forzada produce contraseñas incrementales y peores |
| Rotación forzada por evento | **Sí**, ante evidencia de compromiso, filtración conocida o restablecimiento administrativo | Es el único caso donde la rotación aporta |
| Pistas de contraseña | Prohibidas | NIST 5.1.1.2 |
| Preguntas de seguridad | Prohibidas | NIST las descarta como autenticador |
| Pegado en el campo | Permitido | Habilita el uso de gestores de contraseñas |
| Mostrar contraseña | Botón de alternar disponible | Reduce errores en contraseñas largas |

**Verificación contra listas de contraseñas comprometidas.** Obligatoria en alta, cambio y
restablecimiento. Dos capas:

1. Lista local de las 100000 contraseñas más comunes, empaquetada con la aplicación y consultada
   sin salida de red. Es la barrera que siempre funciona.
2. Consulta al servicio Pwned Passwords por **k-anonimato**: se envían los primeros 5 caracteres
   del SHA-1 de la contraseña y se compara localmente el resto. La contraseña completa nunca sale
   del servidor. Si el servicio no responde, se continúa solo con la capa local y se registra la
   degradación. Se falla abierto **solo en esta comprobación** porque la capa local ya cubre el
   caso mayoritario.

Adicionalmente se rechazan contraseñas que contengan el nombre de la institución, el correo del
usuario o su nombre propio.

**Cómo se comprueba.** Prueba de integración que intenta registrar `Password123!` y `123456789012`
y espera rechazo con el mensaje de orientación correspondiente.

### 4.3 MFA con TOTP

| Rol | MFA |
|---|---|
| Super Administrador | **Obligatoria.** No puede desactivarse |
| Administrador | **Obligatoria** |
| Cajero | **Obligatoria** (tiene escritura financiera) |
| Contabilidad | **Obligatoria** (emite y anula documentos fiscales) |
| Auditor | Obligatoria (accede a datos personales en volumen) |
| Coordinador Académico | Recomendada, obligatoria si tiene permiso de exportar |
| Encargado | Opcional, promovida activamente en la interfaz |

**Regla dura: todo rol con permiso de escritura financiera exige MFA.** La verificación no es
declarativa: un trabajo de integridad diario comprueba que ningún usuario con un permiso de
escritura financiera tiene MFA inactiva, y si lo encuentra, suspende la sesión y notifica.

Especificación técnica:

- Algoritmo TOTP (RFC 6238), SHA-1 para compatibilidad con autenticadores estándar, 6 dígitos,
  periodo de 30 segundos, ventana de tolerancia de ±1 periodo.
- Secreto de 20 bytes aleatorios, cifrado a nivel de columna en reposo. Ver 7.3.
- **Prevención de reutilización de código**: se almacena el último contador aceptado por usuario y
  se rechaza cualquier código de un contador menor o igual.
- Límite de tasa específico sobre la verificación de código: 5 intentos por cada 15 minutos por
  usuario, con retroceso exponencial posterior.
- **Códigos de recuperación**: 10 códigos de un solo uso de 10 caracteres, mostrados una única vez
  y almacenados con el mismo Argon2id que las contraseñas. Usar uno lo invalida y se notifica al
  usuario. Quedar con menos de 3 códigos genera aviso.
- Se acepta WebAuthn como segundo factor adicional en fase posterior. TOTP es el mínimo.

**Cómo se comprueba.** Prueba de extremo a extremo del flujo completo de inicio de sesión con MFA.
Prueba de integración que reutiliza un código ya consumido y espera rechazo.

### 4.4 Bloqueo con retroceso exponencial

No se usa bloqueo permanente de cuenta: es un vector de denegación de servicio contra usuarios
legítimos, porque cualquiera que conozca un correo puede bloquear esa cuenta.

Se aplica retroceso exponencial en dos dimensiones simultáneas, con estado en Redis:

| Dimensión | Umbral | Comportamiento |
|---|---|---|
| Por cuenta | A partir del intento fallido 3 | Retardo de `2^(n-3)` segundos, con tope de 900 segundos (15 minutos). Contador que expira a los 30 minutos sin intentos |
| Por dirección IP | A partir del intento fallido 10 en 10 minutos | Límite de tasa de 1 intento por minuto para esa IP, con tope de 1 hora |
| Por IP contra cuentas distintas | 5 cuentas distintas en 10 minutos | Indicador de relleno de credenciales: bloqueo de 1 hora y alerta al canal de seguridad |

El retardo se aplica **antes** de responder, de forma uniforme para cuenta existente e
inexistente, para no filtrar la existencia de la cuenta.

Un inicio de sesión exitoso limpia el contador de la cuenta pero no el de la IP.

**Cómo se comprueba.** Prueba de integración que ejecuta la secuencia de intentos y verifica los
retardos y el bloqueo por IP contra múltiples cuentas.

> **Nota editorial, 2026-09-24 (`identity-module-and-password-authentication`).** El estado del
> retroceso **por cuenta** vive en PostgreSQL y no en Redis, por atomicidad con la bitácora de
> auditoría (propuesta D1, aprobada). La dimensión por dirección IP de esta misma sección sigue
> apuntando a Redis, con el control en `session-tokens-and-web-layer` y el aprovisionamiento en el
> cambio 11.
>
> Sobre «el retardo se aplica antes de responder»: el retardo se **calcula y se exige** dentro de la
> transacción del caso de uso, y se **materializa** en el borde HTTP una vez confirmada esa
> transacción. Nunca se espera reteniendo una transacción, una conexión del grupo, un bloqueo de fila
> ni un hilo de plataforma: hacerlo convertiría este control en un amplificador de denegación de
> servicio, que es justo lo que esta sección existe para evitar.
>
> Y una precisión sobre su alcance: el retardo por cuenta **no es un limitador de tasa**. Un atacante
> que cierra la conexión no espera nada. Lo que este control da es uniformidad de tiempo frente a la
> enumeración, penalización del atacante secuencial y rastro auditable. La cota de tasa la pone la
> dimensión por IP.

### 4.5 Sesiones y tokens

| Aspecto | Decisión |
|---|---|
| Transporte | Cookie `HttpOnly`, `Secure`, `SameSite` y `Path` propio por dominio de identidad, según ADR-0005, con prefijo `__Secure-`. **No** se usa el prefijo `__Host-`, porque ese prefijo exige `Path=/` y prohíbe todo `Path` distinto, y ADR-0005 exige un `Path` propio y específico por dominio de identidad (administrativo y portal), no `Path=/`. El prefijo `__Secure-` sí admite un `Path` específico, exige `Secure` y es la elección coherente con esa decisión. Ninguna cookie declara el atributo `Domain`: es una cookie de solo host (*host-only*), lo que además preserva el aislamiento de origen de ADR-0003 |
| Nombre de cookie | Distinto por dominio de identidad: `__Secure-confia_admin_sid` y `__Secure-confia_portal_sid` |
| Token de acceso | JWT de vida corta, 10 minutos, firmado con EdDSA (Ed25519), según ADR-0005 |
| Claves de firma | **Distintas por dominio de identidad.** `aud` distinto. Un token del portal es inválido contra la API administrativa |
| Rotación de claves | Semestral, con dos claves activas durante la ventana, publicadas por JWKS interno |
| Token de refresco | Opaco, aleatorio de 32 bytes, almacenado hasheado con SHA-256, con **rotación en cada uso** y detección de reutilización: si se presenta un refresco ya usado, se revoca toda la familia de sesión y se notifica al usuario |
| Expiración por inactividad | 30 minutos en administración (control adicional de este documento, ver nota siguiente); 30 días en portal, que es la vida del token de refresco de encargados según ADR-0005 |
| Expiración absoluta | 12 horas en administración, 90 días en portal, según ADR-0005 |
| Revocación | Lista de sesiones activas por usuario, con cierre individual y cierre de todas las sesiones. Todo cambio de contraseña o de MFA cierra las demás sesiones |
| Vinculación | Se registra huella de agente de usuario e IP. Un cambio de ambos simultáneo exige reautenticación |

**Cierre de sesión administrativa por inactividad, adicional a ADR-0005.** ADR-0005 fija la vida
del token de refresco administrativo en 8 horas, no renovable más allá de 12 horas de sesión
absoluta, pero no define un cierre de sesión por inactividad. El panel administrativo cierra la
sesión del personal tras **30 minutos sin actividad**, como control de este documento adicional a
ADR-0005 y compatible con él: reduce la ventana de exposición de una estación de caja
desatendida, sin cambiar la vida del token de refresco ni la revocación por familia que ADR-0005
ya especifica. `docs/ui-ux/04-patrones-de-interaccion.md` documenta el comportamiento visible de
este control.

### 4.6 Prevención de enumeración de usuarios

La enumeración es el paso previo de todo ataque de relleno de credenciales, y en este sistema
revelaría además qué correos corresponden a encargados de la institución.

| Flujo | Respuesta uniforme |
|---|---|
| Inicio de sesión | Un único mensaje: "Credenciales inválidas". Nunca "usuario no existe" ni "contraseña incorrecta". Mismo código de estado 401 |
| Recuperación de contraseña | Siempre 202 con "Si la dirección corresponde a una cuenta, se enviará un enlace". Nunca confirma existencia |
| Registro de encargado | Nunca revela si el correo ya está registrado. El correo de aviso difiere según el caso, pero la respuesta HTTP no |
| Verificación de correo | Respuesta uniforme ante token válido e inválido consumido |

**Uniformidad de tiempo.** La diferencia de tiempo es un canal lateral tan efectivo como el
mensaje. Cuando el usuario no existe, se ejecuta igualmente una verificación Argon2id contra un
hash señuelo constante, de modo que el tiempo de respuesta no distinga los casos. El retardo del
retroceso exponencial se aplica también a cuentas inexistentes, calculado sobre el correo
proporcionado.

**Cómo se comprueba.** Prueba automatizada que mide la distribución de tiempos de respuesta de 200
intentos con correo existente y 200 con correo inexistente, y falla si la diferencia de medianas
supera 50 milisegundos.

### 4.7 Recuperación de contraseña segura

| Propiedad | Valor |
|---|---|
| Token | 32 bytes aleatorios de un generador criptográfico, codificado en base64url |
| Almacenamiento | Solo el SHA-256 del token. El valor en claro existe únicamente dentro del correo |
| Vida útil | **30 minutos**, según ADR-0005 |
| Uso | **Un solo uso.** Se marca consumido dentro de la misma transacción que cambia la contraseña |
| Invalidación | Solicitar un token nuevo invalida los anteriores de esa cuenta |
| Alcance | El token identifica la cuenta por su identificador interno, no por el correo en la URL |
| Referrer | La página de restablecimiento usa `Referrer-Policy: no-referrer` para que el token no viaje a terceros |
| Efecto | Cambiar la contraseña cierra todas las sesiones activas y notifica por correo al titular, incluida la IP y el momento |
| MFA | Si la cuenta tiene MFA activa, el restablecimiento exige además un código TOTP válido o un código de recuperación |
| Límite de tasa | 3 solicitudes por hora por cuenta y 10 por hora por IP |

**Restablecimiento administrativo.** Un Super Administrador puede forzar el restablecimiento de un
usuario del personal, pero **no puede fijar la contraseña**: solo puede invalidar la actual y
disparar el flujo de token. Esto elimina el escenario del administrador que conoce la contraseña
de un cajero. La acción se audita siempre.

---

## 5. Controles de autorización

### 5.1 Modelo RBAC con permisos granulares

La autorización se decide **siempre en el servidor**. El frontend oculta lo que el usuario no
puede hacer solo por experiencia de uso, nunca como control.

Estructura:

```
Usuario  ->  Rol (uno o varios)  ->  Permiso (módulo + verbo)
```

Un permiso se nombra `modulo:verbo`, por ejemplo `payments:create` o `invoicing:void`. Los verbos
son los seis siguientes y ninguno más:

| Verbo | Significado | Nota |
|---|---|---|
| `create` | Crear un registro nuevo | |
| `read` | Consultar | |
| `update` | Modificar datos no financieros | Nunca aplica a asientos del libro mayor |
| `void` (anular) | Anular o reversar una operación | Siempre genera asiento de reverso, nunca borra |
| `approve` (aprobar) | Autorizar una operación que otro actor inició | Base de la segregación de funciones |
| `export` (exportar) | Extraer datos a CSV, Excel o PDF en volumen | Separado de `read` a propósito, por riesgo de fuga masiva |

Los permisos se declaran en el controlador con una anotación y Spring Security los verifica en
el servidor. **Un controlador sin permiso declarado no arranca la aplicación**: el arranque
recorre el mapa de rutas y falla si encuentra una ruta sin metadato de permiso o sin marca
explícita de pública.

**Cómo se comprueba.** Prueba de arranque que enumera todas las rutas de OpenAPI y verifica que
cada una declara permiso o es explícitamente pública. La lista de rutas públicas es una lista
blanca corta y revisada.

### 5.2 Matriz de permisos por rol

Leyenda: **C** crear, **L** leer, **A** actualizar, **N** anular, **P** aprobar, **X** exportar.
Una celda vacía significa sin acceso. `L*` significa lectura restringida por seguridad a nivel de
fila a los propios estudiantes vinculados.

| Módulo | Super Admin | Administrador | Cajero | Contabilidad | Auditor | Coord. Académico | Encargado |
|---|---|---|---|---|---|---|---|
| `identity` (usuarios, roles) | C L A N P X | L | | | L | | |
| `identity` (MFA propia) | C L A | C L A | C L A | C L A | C L A | C L A | C L A |
| `organization` (institución, año, grado) | C L A N X | C L A X | L | L | L X | C L A X | |
| `students` | C L A N X | C L A X | L | L | L X | C L A X | L* |
| `guardians` | C L A N X | C L A X | L | L | L X | L | L* A* |
| `catalog` (conceptos y tarifas) | C L A N X | C L A X | L | L X | L X | L | |
| `scholarships` (becas) | C L A N P X | C L A P X | L | L X | L X | C L | L* |
| `charges` (devengo y cargos) | C L A N P X | C L N P X | L | L X | L X | L | L* |
| `ledger` (libro mayor) | L N P X | L N X | L | L X | L X | | L* |
| `payments` | C L N P X | C L N P X | **C L** | L N X | L X | | L* |
| `cashbox` (sesiones de caja) | C L N P X | L P X | **C L** | L X | L X | | |
| `invoicing` (factura, CAI, NC) | C L N P X | L P X | **C L** | **C L N P X** | L X | | L* |
| `collections` (mora y cobranza) | C L A N P X | C L A P X | L | L X | L X | L | L* |
| `reconciliation` (bancaria) | C L A P X | L P X | | **C L A P X** | L X | | |
| `documents` (constancias) | C L A N P X | C L A P X | L | | L X | C L A P X | **C** L* |
| `reporting` | L X | L X | L (solo su caja) | L X | **L X** | L X (académico) | |
| `notifications` | C L A N X | C L A X | L | | L X | C L | L* A* (preferencias) |
| `audit` (bitácora) | **L X** | L | | | **L X** | | |
| `settings` (configuración) | C L A N X | L A | | L | L X | | |

Reglas de la matriz que no se ven en la tabla:

1. **Nadie tiene `update` sobre `ledger`.** Ni el Super Administrador. La tabla de asientos no
   acepta `UPDATE` a nivel de motor de base de datos.
2. **Nadie tiene `delete`.** El verbo no existe en el sistema. La anulación es siempre `void`.
3. **El Auditor es de solo lectura absoluta.** Es el único rol con lectura completa de la bitácora
   y sin ninguna capacidad de escritura en todo el sistema. Su cuenta existe para responder a un
   auditor externo sin concederle privilegio operativo.
4. **El Cajero no puede anular.** Puede crear pagos y facturas dentro de su sesión de caja, pero
   la anulación requiere `invoicing:void`, que reside en Contabilidad y Super Administrador. Este
   es el control central contra el actor 2.
5. **El Administrador no puede cobrar.** No tiene `payments:create` ni `cashbox:create`. Quien
   configura el sistema no maneja el efectivo.
6. **`export` sobre `students` y `guardians`** se audita siempre con el filtro y el conteo de
   filas, y dispara alerta sobre un umbral configurable.
7. **El Encargado nunca tiene permisos administrativos.** Su privilegio se define además por el
   proceso: el binario del portal no contiene los módulos administrativos.

La matriz vive también como dato en la migración semilla, de modo que el documento y el sistema no
puedan divergir en silencio.

**Cómo se comprueba.** Una prueba de integración lee esta matriz desde un archivo de definición
compartido y verifica, rol por rol y endpoint por endpoint, que el acceso concedido y el denegado
coinciden con la tabla. Un cambio de permiso que no actualice la definición rompe la construcción.
Trimestralmente se genera el reporte "quién puede hacer qué" y se revisa con el propietario.

### 5.3 Segregación de funciones

Ninguna persona debe poder completar sola un ciclo que mueva dinero fuera del control interno.

| Operación sensible | Quien inicia | Quien aprueba | Regla |
|---|---|---|---|
| Anulación de factura | Cajero o Contabilidad | Contabilidad o Super Administrador, **actor distinto del que emitió** | El sistema rechaza la aprobación si el aprobador es el emisor |
| Nota de crédito sobre monto mayor al umbral | Contabilidad | Super Administrador | Umbral configurable, con vigencia |
| Ajuste manual en el libro mayor | Administrador | Super Administrador | Motivo obligatorio de al menos 20 caracteres, adjunto opcional |
| Baja por incobrable | Contabilidad | Super Administrador | Siempre requiere aprobación, sin umbral |
| Cierre de caja con diferencia superior al umbral | Cajero | Administrador o Super Administrador | Ver `docs/runbooks/cierre-de-caja-con-diferencia.md` |
| Alta o cambio de rol de usuario | Super Administrador | No autoaplicable | Un usuario nunca puede modificar sus propios roles |
| Cambio de configuración fiscal (CAI, tasas) | Contabilidad | Super Administrador | Toda la configuración fiscal tiene vigencia y auditoría |
| Reembolso a un tercero | Contabilidad | Super Administrador | Con documento fiscal correspondiente |

Implementación: las operaciones marcadas crean un registro en estado `pendiente de aprobación` con
su actor iniciador. La aprobación es un caso de uso separado que valida que el aprobador tiene el
permiso `approve` del módulo y que **su identificador difiere del iniciador**. Ambos eventos se
auditan. Una aprobación caduca a las 72 horas sin resolución y notifica.

**Cómo se comprueba.** Prueba de integración por cada fila: el mismo actor intenta iniciar y
aprobar, y el sistema rechaza con 403 y un tipo de error específico. El reporte trimestral de
segregación de funciones no debe listar ningún usuario con ambos permisos del mismo par.

### 5.4 Autorización a nivel de objeto

El control de acceso a nivel de función (¿puede este rol llamar a este endpoint?) es necesario
pero insuficiente. La falla más común en APIs reales es la autorización a nivel de objeto: el rol
puede llamar al endpoint, pero no debería acceder a **ese** objeto.

Reglas:

1. **Identificadores opacos.** Todas las claves primarias expuestas son UUIDv7. Nunca enteros
   secuenciales. Un identificador adivinado no revela volumen ni permite recorrido.
2. **Verificación de propiedad en el caso de uso.** El caso de uso recibe el actor y el
   identificador del objeto, y verifica la relación antes de actuar. No se delega esta
   verificación al controlador ni al repositorio.
3. **Seguridad a nivel de fila como segunda barrera.** Ver sección 6. Aunque el paso 2 falle por
   un error de programación, el motor devuelve cero filas.
4. **Respuesta uniforme.** Un objeto que existe pero no pertenece al actor devuelve **404**, no
   403. Un 403 confirma la existencia del objeto y habilita la enumeración.
5. **Prohibición de asignación masiva.** Toda entrada pasa por un DTO de entrada explícito,
   validado con Jakarta Bean Validation, y el deserializador JSON se configura para rechazar
   campos desconocidos. Un campo no declarado hace fallar la validación en vez de asignarse en
   silencio.
6. **DTO de salida explícito.** Nunca se serializa una entidad de persistencia directamente. El DTO
   declara los campos y el resto no sale, aunque alguien agregue una columna sensible después.

**Cómo se comprueba.** Por cada recurso con propietario existe una prueba de integración con el
patrón: actor A crea el objeto, actor B lo solicita, se espera 404 y cero filas en el registro de
consulta. Esta prueba es obligatoria en la definición de terminado.

---

## 6. Seguridad a nivel de fila en PostgreSQL

Esta es la barrera que hace que un error de aplicación no se convierta automáticamente en una fuga
de datos de un menor. Con un solo desarrollador, no es opcional.

### 6.1 Roles de base de datos

| Rol | Uso | Privilegios |
|---|---|---|
| `confia_owner` | Migraciones. No lo usa la aplicación en ejecución | Propietario del esquema. `BYPASSRLS` no se concede |
| `confia_admin_app` | Proceso `confia-api-admin` | `SELECT`, `INSERT` en todas las tablas de negocio. `UPDATE` solo en tablas no financieras. Sin `DELETE` en ninguna tabla de negocio. Sobre las tablas técnicas, exactamente los privilegios de ADR-0017: `SELECT`, `INSERT`, `UPDATE` y `DELETE` en `scheduled_tasks` (db-scheduler toma, reprograma y retira tareas, ADR-0016) y en `event_publication` (Spring Modulith borra las publicaciones completadas), y solo `SELECT` en `flyway_schema_history`, para la comprobación de salud de migraciones aplicadas. Sin `UPDATE` ni `DELETE` en `shared_audit_log` ni `ledger_entry`. También lo usa `confia-worker` |
| `confia_portal_app` | Proceso `confia-api-portal` | `SELECT` sobre una lista corta y explícita de tablas. `INSERT` solo en `document_request`, `payment_intent` y `notification_preference`. Sobre las tablas técnicas, exactamente los privilegios de ADR-0017: `INSERT` en `scheduled_tasks`, más `SELECT` solo si el cliente de programación lo exige (validado en F0); ningún privilegio sobre `event_publication`, porque el portal no usa el registro de eventos; `SELECT` en `flyway_schema_history`, para la comprobación de salud. Ningún `UPDATE` ni `DELETE` en ninguna tabla. Sin acceso alguno a `user`, `role`, `cai_range`, `cashbox_session` ni `shared_audit_log` |
| `confia_readonly` | Reportes pesados y réplica de lectura desde fase dos | Solo `SELECT`, con RLS activa. Ningún acceso a las tablas técnicas de ADR-0017 |
| `confia_backup` | Trabajo de respaldo | `pg_read_all_data` |

Ninguno de estos roles es `SUPERUSER` y ninguno tiene el atributo `BYPASSRLS`. Ese detalle es
crítico: un rol con `BYPASSRLS` anula silenciosamente todas las políticas.

> **Nota editorial, 2026-09-24 (`identity-module-and-password-authentication`).** La tabla que esta
> sección llama `user` se entrega como `identity_staff_account`, con el prefijo de módulo que exige
> la regla 3 de ADR-0015. La tabla `identity_login_backoff`, que esta sección no nombra por ser
> posterior, recibe el mismo trato que `user`: `SELECT`, `INSERT` y `UPDATE` para
> `confia_admin_app`, sin `DELETE`; `SELECT` para `confia_readonly`; y **ningún privilegio** para
> `confia_portal_app`.

### 6.2 Contexto de sesión seguro

Las políticas necesitan saber quién consulta. El contexto se establece por transacción, nunca por
conexión, porque el pool reutiliza conexiones entre solicitudes y un contexto persistente se
filtraría a la solicitud siguiente.

```sql
-- Se ejecuta como primera sentencia dentro de cada transacción de la aplicación.
-- El tercer parámetro `true` hace el ajuste local a la transacción: se revierte
-- automáticamente al terminar, aunque la transacción falle.
SELECT set_config('app.actor_id',        $1, true),
       set_config('app.actor_kind',      $2, true),  -- 'staff' | 'guardian' | 'system'
       set_config('app.institution_id',  $3, true),
       set_config('app.request_id',      $4, true);
```

Reglas de implementación, todas verificables:

1. Se usa `set_config(..., true)` y **jamás** `SET SESSION`. Un `SET SESSION` sobrevive a la
   transacción y contamina la siguiente solicitud que reciba esa conexión del pool.
2. Los valores se pasan como **parámetros vinculados**, nunca interpolados en la cadena SQL.
3. El contexto lo establece el componente transaccional único de `shared/security` (ADR-0015),
   que además fija el nivel de aislamiento de la transacción y reintenta un número acotado de veces
   ante errores de serialización o de interbloqueo. Ningún repositorio abre transacciones por su
   cuenta. Una regla de ArchUnit prohíbe usar `@Transactional`, `TransactionTemplate` o el gestor
   de transacciones fuera de ese componente, incluida la capa `infrastructure`.
4. Las políticas usan `current_setting('app.actor_id', true)`, con el segundo parámetro `true`
   para que devuelva `NULL` en vez de error si falta. **Una política que recibe `NULL` deniega**,
   nunca permite. Este es el punto donde el diseño falla cerrado.

### 6.3 Políticas existentes

Se activa `ROW LEVEL SECURITY` y también `FORCE ROW LEVEL SECURITY` en cada tabla protegida. Sin
`FORCE`, el propietario de la tabla omite las políticas.

```sql
ALTER TABLE student        ENABLE ROW LEVEL SECURITY;
ALTER TABLE student        FORCE  ROW LEVEL SECURITY;

-- Aislamiento por institución: aplica a todos los actores, en todas las tablas
-- que llevan institution_id. Es la base de la multi-institución futura.
CREATE POLICY student_institution_isolation ON student
  USING (institution_id = current_setting('app.institution_id', true)::uuid);

-- Aislamiento del encargado: solo ve estudiantes con vínculo vigente.
CREATE POLICY student_guardian_scope ON student
  FOR SELECT
  TO confia_portal_app
  USING (
    current_setting('app.actor_kind', true) = 'guardian'
    AND EXISTS (
      SELECT 1
      FROM guardian_student gs
      WHERE gs.student_id   = student.id
        AND gs.guardian_id  = current_setting('app.actor_id', true)::uuid
        AND gs.status       = 'active'
        AND gs.valid_from  <= now()
        AND (gs.valid_to IS NULL OR gs.valid_to > now())
    )
  );
```

Tablas con política obligatoria y su criterio:

| Tabla | Criterio de fila | Actores alcanzados |
|---|---|---|
| `student` | Institución, y vínculo vigente si el actor es encargado | Todos |
| `guardian` | Institución, y el propio identificador si es encargado | Todos |
| `guardian_student` | Institución, y el propio identificador si es encargado | Todos |
| `ledger_entry` | Institución, y estudiante vinculado si es encargado | Todos |
| `charge` | Institución, y estudiante vinculado si es encargado | Todos |
| `payment` | Institución, y estudiante vinculado si es encargado | Todos |
| `invoice` | Institución, y estudiante vinculado si es encargado | Todos |
| `document_request` | Institución, y el propio solicitante si es encargado | Todos |
| `notification_log` | Institución, y el propio destinatario si es encargado | Todos |
| `cashbox_session` | Institución, y el propio cajero salvo rol supervisor | Personal |
| `shared_audit_log` | Institución | Personal con permiso `audit:read` |

### 6.4 Cómo se prueba

Esta sección es la razón por la que el control es real y no una declaración.

1. **Prueba de integración por política.** Obligatoria por cada fila de la tabla anterior. Patrón:
   con Testcontainers se levanta PostgreSQL, se crean dos encargados con estudiantes distintos, se
   establece el contexto del encargado A y se consulta el estudiante de B. **Se espera cero
   filas.** La prueba se ejecuta conectada con `confia_portal_app`, no con el propietario.
2. **Prueba de contexto ausente.** Se ejecuta la misma consulta sin establecer `app.actor_id` y se
   verifica que devuelve cero filas. Confirma el fallo cerrado.
3. **Prueba de fuga entre solicitudes.** Se ejecutan dos transacciones consecutivas sobre la misma
   conexión del pool, con actores distintos, y se verifica que la segunda no ve datos de la
   primera. Detecta el uso accidental de `SET SESSION`.
4. **Prueba de inventario.** Una consulta al catálogo de PostgreSQL verifica que toda tabla que
   contiene `institution_id` tiene `relrowsecurity` y `relforcerowsecurity` en verdadero. Si
   alguien crea una tabla nueva sin política, la prueba falla.

```sql
-- Prueba de inventario: no debe devolver ninguna fila.
SELECT c.relname
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'institution_id'
WHERE n.nspname = 'public'
  AND c.relkind = 'r'
  AND (c.relrowsecurity = false OR c.relforcerowsecurity = false);
```

5. **Prueba de permisos del rol del portal.** Se recorre la lista de tablas prohibidas y se intenta
   `SELECT` con `confia_portal_app`, esperando error de permiso insuficiente en cada una.
6. **Prueba de ausencia de `BYPASSRLS`.** Consulta a `pg_roles` verificando `rolbypassrls = false`
   para los cinco roles de aplicación.
7. **Prueba de esquema multi-institución (ADR-0009, ADR-0016, ADR-0017).** Toda tabla lleva
   `institution_id NOT NULL`, salvo el catálogo cerrado de ADR-0017, que contiene exactamente
   cuatro tablas:
   - Tres tablas técnicas cuyo esquema define una biblioteca: `scheduled_tasks` (db-scheduler),
     `event_publication` (Spring Modulith) y `flyway_schema_history` (Flyway), con el nombre
     predeterminado de cada biblioteca, confirmado en F0. No tienen política de fila; por eso
     ningún dato personal ni importe vive en ellas.
   - La tabla raíz de instituciones del módulo `organization`, cuya clave primaria es el
     identificador de institución. Solo está exceptuada de la columna: tiene seguridad a nivel de
     fila activa y forzada, con una política sobre su clave primaria
     (`id = current_setting('app.institution_id', true)::uuid`). La segunda consulta lo comprueba,
     porque la prueba de inventario (prueba 4) solo recorre tablas que tienen `institution_id`.

   Agregar otra tabla a la lista exige un ADR nuevo. El nombre definitivo de la tabla raíz lleva
   el prefijo del módulo `organization` (ADR-0015) y se fija en F0; hasta entonces las consultas
   usan el marcador `<institution_root_table>`, que se sustituye por ese nombre. Una consulta que
   conserve el marcador no es una verificación válida.

```sql
-- Schema check: no row expected. Technical table names are each library's default, confirmed
-- in F0. <institution_root_table> is a placeholder for the root table name fixed in F0.
SELECT c.relname
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind = 'r'
  AND c.relname NOT IN ('scheduled_tasks', 'event_publication', 'flyway_schema_history',
                        '<institution_root_table>')
  AND NOT EXISTS (
    SELECT 1
    FROM pg_attribute a
    WHERE a.attrelid = c.oid
      AND a.attname = 'institution_id'
      AND a.attnotnull
      AND NOT a.attisdropped);
```

```sql
-- Root table check: no row expected. Returns the placeholder name when the root table is missing,
-- lacks enabled or forced row level security, or has no policy that compares its primary key with
-- app.institution_id. Matching on the deparsed policy expression is illustrative; the exact
-- assertion is validated in F0.
SELECT '<institution_root_table>' AS relname
WHERE NOT EXISTS (
  SELECT 1
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_policy p ON p.polrelid = c.oid
  JOIN pg_constraint k ON k.conrelid = c.oid AND k.contype = 'p'
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY (k.conkey)
  WHERE n.nspname = 'public'
    AND c.relkind = 'r'
    AND c.relname = '<institution_root_table>'
    AND c.relrowsecurity
    AND c.relforcerowsecurity
    AND pg_get_expr(p.polqual, p.polrelid) ~ ('\m' || a.attname || '\M')
    AND pg_get_expr(p.polqual, p.polrelid) LIKE '%app.institution_id%');
```

8. **Matriz de permisos de las tablas técnicas (ADR-0003, ADR-0017).** La prueba de permisos por
   rol incluye las tres tablas técnicas y exige exactamente los privilegios de la sección 6.1, ni
   uno más ni uno menos, consultando `has_table_privilege` para cada rol, tabla y privilegio:
   `confia_portal_app` sin ningún privilegio sobre `event_publication` y sin `UPDATE` ni `DELETE`
   sobre `scheduled_tasks`, y `confia_readonly` sin ningún privilegio sobre las tres. La tabla raíz
   se verifica con la matriz de las tablas de negocio.
9. **Casos de uso del portal con su rol real (ADR-0017).** Las pruebas de integración de los casos
   de uso del portal se ejecutan conectadas como `confia_portal_app`, nunca como `confia_owner` ni
   como `confia_admin_app`. Cualquier intento de escribir en `event_publication` hace fallar la
   prueba, lo que demuestra que ningún caso de uso del portal depende del registro de eventos.

---

## 7. Protección de datos en tránsito y en reposo

### 7.1 En tránsito

| Control | Valor | Verificación |
|---|---|---|
| Protocolo | **TLS 1.3** preferente, TLS 1.2 permitido solo con suites AEAD. TLS 1.0 y 1.1 deshabilitados | Calificación A o superior en SSL Labs, ejecutada en cada despliegue de producción y trimestralmente |
| Suites de cifrado | Solo AEAD con secreto perfecto hacia adelante. Sin RC4, 3DES, CBC ni renegociación insegura | `testssl.sh` en la lista de verificación previa a producción |
| Certificados | Certificado de origen de Cloudflare entre Cloudflare y la instancia de aplicación, en modo *Full (strict)*, con TLS de borde gestionado por Cloudflare hacia el navegador (ADR-0014, `docs/05-infraestructura-y-despliegue.md`, sección 10 y 13). Verificado por alerta a 21 días de la expiración de cualquiera de los dos certificados | Alerta de certificado por vencer en el tablero operativo |
| HSTS | `max-age=63072000; includeSubDomains; preload`, con el dominio inscrito en la lista de precarga | `curl -I` en la lista de verificación. Consulta a hstspreload.org |
| Redirección | Todo HTTP redirige a HTTPS con 301. No existe contenido servido por HTTP salvo el desafío ACME | Prueba de humo tras despliegue |
| Base de datos | Conexión con `sslmode=verify-full` y certificado de la autoridad interna, incluso dentro de la red privada | Cadena de conexión revisada en la lista previa a producción |
| Redis | TLS habilitado y `requirepass` | Igual |
| Almacenamiento de objetos | HTTPS obligatorio, política de bucket que rechaza peticiones no cifradas | Política de bucket revisada |
| Salientes | Toda llamada a pasarela, correo o mensajería exige verificación de certificado. Prohibido `rejectUnauthorized: false` | Regla de Semgrep que bloquea ese patrón |

### 7.2 En reposo: disco y respaldos

- **Cifrado en reposo con llaves gestionadas por AWS** (ADR-0014, nivel 2 de servicios
  permitidos): el volumen EBS de la instancia de aplicación y el almacenamiento de RDS for
  PostgreSQL están cifrados con llaves gestionadas por el proveedor. Esto reemplaza el cifrado de
  disco completo con LUKS de versiones anteriores de este documento, que aplicaba a un host de
  datos autogestionado que ADR-0014 sustituyó por RDS. El cifrado a nivel de columna de la sección
  7.3 sigue aplicando igual, como segunda capa independiente del proveedor de nube.
- Respaldos cifrados con **age** antes de salir del host que ejecuta el respaldo. La llave privada
  de restauración se custodia fuera del servidor, fuera de AWS y fuera del repositorio, con copia
  en custodia física sellada.
- Almacenamiento de objetos con cifrado del lado del servidor activado y bloqueo de objeto en modo
  de cumplimiento para el bucket de respaldos fuera de sitio (fuera de AWS, ADR-0014).
- **Verificación:** el simulacro mensual de restauración prueba la cadena completa, incluido el
  descifrado con la llave custodiada. Una llave que no se ha usado para restaurar no se considera
  válida.

### 7.3 Cifrado a nivel de columna con sobre de llaves

El cifrado de disco protege contra el robo físico del servidor, no contra un volcado obtenido con
una credencial de solo lectura. Los datos de mayor sensibilidad se cifran además a nivel de
columna.

**Datos que se cifran a nivel de columna:**

| Dato | Tabla | Razón |
|---|---|---|
| Número de identidad nacional del estudiante | `student` | Identificador nacional de un **menor de edad** |
| Número de identidad nacional del encargado | `guardian` | Identificador nacional |
| RTN del encargado o de la institución pagadora | `guardian` | Identificador tributario |
| Secreto TOTP | `user_mfa` | Compromete el segundo factor |
| Cuenta bancaria para reembolso | `refund_account` | Dato financiero directo |
| Token de la pasarela asociado al cliente | `payment_method_token` | Aunque sea un token del proveedor |
| Notas de casos sensibles del estudiante | `student_note` | Puede contener datos de categoría especial |

**Datos que no se cifran a nivel de columna** y por qué: nombre, grado, importes y fechas de
movimiento. Cifrarlos impediría índices, ordenamiento, agregación y reportes, y el beneficio
marginal no compensa la pérdida de funcionalidad y el riesgo de una implementación compleja mal
hecha. Esos datos se protegen por RLS, cifrado de disco y control de acceso.

**Esquema de sobre de llaves:**

```
Llave maestra (KEK)              en gestor de secretos, nunca en la base de datos
   └── cifra la llave de datos (DEK)   almacenada cifrada en tabla `data_key`
          └── cifra el valor de la columna con AES-256-GCM
```

- Algoritmo: AES-256-GCM. Vector de inicialización de 96 bits aleatorio por operación, nunca
  reutilizado. Etiqueta de autenticación de 128 bits almacenada junto al texto cifrado.
- **Datos adicionales autenticados (AAD):** se incluye `tabla|columna|id_de_fila`. Esto impide
  mover un valor cifrado de una fila a otra, que de otro modo sería un ataque válido: copiar el
  documento de identidad cifrado de un estudiante a otro registro.
- Formato almacenado: `v1:<id_dek>:<iv_base64>:<ciphertext_base64>:<tag_base64>`. El prefijo de
  versión permite migrar de algoritmo sin ambigüedad.
- Las claves de búsqueda determinista se resuelven con una columna adicional de HMAC-SHA-256 con
  llave dedicada, que permite búsqueda por igualdad exacta sin descifrar y sin exponer el valor.
  No permite búsqueda parcial, y eso es intencional.

**Gestión y rotación de llaves:**

| Llave | Ubicación | Rotación | Procedimiento |
|---|---|---|---|
| KEK maestra | Gestor de secretos de producción | Anual, o inmediata ante sospecha | Se genera la nueva, se recifran todas las DEK, se retiene la anterior 30 días para reversión |
| DEK de datos | Tabla `data_key`, cifrada por la KEK | Anual, con recifrado progresivo en trabajo por lotes | La DEK antigua se conserva marcada como `retired` para descifrar registros aún no migrados |
| Pimienta de Argon2id | Gestor de secretos | Solo ante compromiso | Su rotación obliga a restablecer todas las contraseñas. Se documenta como evento mayor |
| Llave de HMAC de búsqueda | Gestor de secretos | Junto con la DEK | Su rotación exige recalcular las columnas de búsqueda |
| Llave de firma JWT | Gestor de secretos | Semestral | Dos claves activas durante la ventana |
| Llave age de respaldo | Custodia física y gestor de secretos | Anual | Se prueba con una restauración antes de retirar la anterior |

**Cómo se comprueba.** Una prueba de integración inserta un registro con documento de identidad,
consulta la columna directamente con SQL crudo y verifica que el valor almacenado **no contiene**
el texto en claro y sí el prefijo `v1:`. Otra prueba intenta descifrar un valor con el AAD de otra
fila y espera fallo de autenticación. Un trabajo mensual reporta el inventario de llaves con su
antigüedad y alerta si alguna supera su periodo de rotación.

---

## 8. Cabeceras y defensas del navegador

### 8.1 Política de seguridad de contenido basada en nonce

Sin `unsafe-inline` y sin `unsafe-eval`. Esta restricción condiciona el frontend desde el inicio,
y por eso se declara aquí y no al final: retrofitear una CSP estricta sobre una aplicación React
ya construida es caro.

- El servidor genera un nonce aleatorio de 128 bits por respuesta HTML y lo inyecta en la etiqueta
  de script y en la cabecera.
- Tailwind CSS v4 se compila a un archivo estático, sin estilos en línea generados en tiempo de
  ejecución. Los estilos dinámicos se resuelven con variables CSS declaradas en el archivo
  compilado.
- `report-uri` apunta a un endpoint propio que registra violaciones. Antes de producción se opera
  dos semanas en modo `Content-Security-Policy-Report-Only` para descubrir violaciones legítimas
  sin romper la aplicación.

Política del **panel administrativo**:

```
Content-Security-Policy:
  default-src 'none';
  script-src 'nonce-{NONCE}' 'strict-dynamic';
  style-src 'self';
  img-src 'self' data: blob:;
  font-src 'self';
  connect-src 'self' https://api-admin.confia.example;
  form-action 'self';
  frame-ancestors 'none';
  base-uri 'none';
  object-src 'none';
  worker-src 'self' blob:;
  manifest-src 'self';
  upgrade-insecure-requests;
  report-uri /api/v1/csp-report
```

Política del **portal de encargados**, que además debe permitir el marco de la pasarela cuando se
usen campos alojados:

```
Content-Security-Policy:
  default-src 'none';
  script-src 'nonce-{NONCE}' 'strict-dynamic' https://js.pasarela.example;
  style-src 'self';
  img-src 'self' data: blob:;
  font-src 'self';
  connect-src 'self' https://api-portal.confia.example https://api.pasarela.example;
  frame-src https://checkout.pasarela.example;
  form-action 'self' https://checkout.pasarela.example;
  frame-ancestors 'none';
  base-uri 'none';
  object-src 'none';
  upgrade-insecure-requests;
  report-uri /api/v1/csp-report
```

Los dominios de la pasarela se sustituyen por los reales del proveedor cuando se seleccione, en
fase F9. Se declaran los mínimos que el proveedor documente, no comodines.

### 8.2 Resto de cabeceras

| Cabecera | Valor | Qué previene |
|---|---|---|
| `Strict-Transport-Security` | `max-age=63072000; includeSubDomains; preload` | Degradación a HTTP y ataque de intermediario en la primera visita |
| `X-Frame-Options` | `DENY` | Secuestro de clic en navegadores antiguos. `frame-ancestors 'none'` es el control moderno; ambos se envían |
| `X-Content-Type-Options` | `nosniff` | Interpretación de un archivo subido como script |
| `Referrer-Policy` | `strict-origin-when-cross-origin`, y `no-referrer` en las páginas de restablecimiento de contraseña | Fuga de tokens y de identificadores en la URL hacia terceros |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()` | Uso de capacidades del dispositivo por código inyectado |
| `Cross-Origin-Opener-Policy` | `same-origin` | Ataques de referencia entre ventanas |
| `Cross-Origin-Resource-Policy` | `same-origin` | Inclusión del recurso desde otro origen |
| `Cross-Origin-Embedder-Policy` | `require-corp` en el panel administrativo | Aislamiento del contexto de navegación |
| `Cache-Control` | `no-store` en toda respuesta con datos personales o financieros | Persistencia en caché de disco o de intermediarios |
| `X-Powered-By`, `Server` | Eliminadas | Reduce la información de versión disponible para el atacante automatizado |

### 8.3 Protección CSRF de doble envío

Las cookies de sesión usan `SameSite=Strict`, que cubre la mayoría de los casos. Se agrega una
segunda barrera porque `SameSite` depende del navegador y no cubre todos los escenarios de
subdominio.

Esquema de token de doble envío firmado:

1. Al establecer la sesión, el servidor emite además una cookie `__Host-confia_csrf` **sin**
   `HttpOnly`, con un valor aleatorio de 32 bytes.
2. El cliente lee esa cookie y envía su valor en la cabecera `X-CSRF-Token` en toda solicitud con
   método `POST`, `PUT`, `PATCH` o `DELETE`.
3. El servidor verifica que la cookie y la cabecera coinciden, mediante comparación en tiempo
   constante, y que el valor está vinculado criptográficamente a la sesión con HMAC. La
   vinculación evita el ataque de fijación de subdominio, donde un subdominio comprometido
   establece una cookie CSRF válida.
4. Adicionalmente se verifica que la cabecera `Origin` pertenece a la lista blanca del perfil de
   despliegue. Si `Origin` falta y el método no es seguro, se rechaza.

Este control no aplica a la API consumida por la futura app móvil, que usa token en cabecera
`Authorization` y por tanto no es vulnerable a CSRF.

**Cómo se comprueba.** Prueba de integración que envía una solicitud de escritura con cookie
válida y sin cabecera `X-CSRF-Token`, y espera 403. Otra que envía un token CSRF válido de otra
sesión y espera 403.

### 8.4 Configuración nginx de ejemplo

Configuración del portal público. La del panel administrativo agrega restricción por IP y omite
los orígenes de la pasarela. Vive en `infra/nginx/`. La configuración operativa completa,
incluida la terminación TLS detrás de Cloudflare con certificado de origen y la recuperación de
la IP real del cliente, está en `docs/05-infraestructura-y-despliegue.md`, sección 10
(ADR-0014); este ejemplo se centra en las cabeceras y la CSP.

```nginx
# infra/nginx/portal.conf

limit_req_zone  $binary_remote_addr zone=portal_general:10m rate=30r/m;
limit_req_zone  $binary_remote_addr zone=portal_auth:10m    rate=5r/m;
limit_conn_zone $binary_remote_addr zone=portal_conn:10m;

map $request_method $is_write {
    default 0;
    POST    1;
    PUT     1;
    PATCH   1;
    DELETE  1;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name portal.confia.example;

    # Certificado de origen de Cloudflare, no Let's Encrypt/certbot: el grupo de
    # seguridad de la instancia solo admite HTTPS desde los rangos de Cloudflare, sin
    # puerto 80 público para un desafío HTTP-01 (ADR-0014,
    # docs/05-infraestructura-y-despliegue.md, sección 10).
    ssl_certificate     /etc/confia/origin-tls/fullchain.pem;
    ssl_certificate_key /etc/confia/origin-tls/privkey.pem;

    ssl_protocols             TLSv1.2 TLSv1.3;
    ssl_ciphers               ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;
    ssl_prefer_server_ciphers off;
    ssl_session_cache         shared:SSL:10m;
    ssl_session_timeout       1d;
    ssl_session_tickets       off;
    ssl_stapling              on;
    ssl_stapling_verify       on;

    add_header Strict-Transport-Security "max-age=63072000; includeSubDomains; preload" always;
    add_header X-Content-Type-Options    "nosniff" always;
    add_header X-Frame-Options           "DENY" always;
    add_header Referrer-Policy           "strict-origin-when-cross-origin" always;
    add_header Permissions-Policy        "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
    add_header Cross-Origin-Opener-Policy   "same-origin" always;
    add_header Cross-Origin-Resource-Policy "same-origin" always;

    server_tokens off;

    client_max_body_size 5m;
    client_body_timeout  15s;
    client_header_timeout 15s;
    send_timeout          30s;

    limit_conn portal_conn 20;

    # La CSP con nonce la emite la aplicación, no nginx, porque el nonce
    # cambia en cada respuesta.

    location /api/v1/auth/ {
        limit_req zone=portal_auth burst=3 nodelay;
        proxy_pass http://127.0.0.1:3001;
        include /etc/nginx/snippets/proxy-headers.conf;
    }

    location /api/v1/ {
        limit_req zone=portal_general burst=20 nodelay;
        proxy_pass http://127.0.0.1:3001;
        include /etc/nginx/snippets/proxy-headers.conf;
    }

    location / {
        root  /srv/confia/portal-web;
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-store" always;
    }

    location ~* \.(js|css|woff2|png|svg)$ {
        root  /srv/confia/portal-web;
        add_header Cache-Control "public, max-age=31536000, immutable" always;
    }
}
```

```nginx
# /etc/nginx/snippets/proxy-headers.conf
proxy_http_version 1.1;
proxy_set_header Host              $host;
proxy_set_header X-Real-IP         $remote_addr;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Request-Id      $request_id;
proxy_set_header Upgrade           $http_upgrade;
proxy_set_header Connection        "";
proxy_read_timeout 30s;
proxy_connect_timeout 5s;
proxy_buffering on;
```

**Cómo se comprueba.** `nginx -t` en el pipeline de despliegue. Prueba de humo posterior al
despliegue que verifica con `curl -I` la presencia de las seis cabeceras obligatorias y falla el
despliegue si alguna falta.

---

## 9. Validación de entrada y codificación de salida

### 9.1 Jakarta Bean Validation en el borde

- **Toda** entrada HTTP (cuerpo, parámetros de ruta, parámetros de consulta y cabeceras
  relevantes) se valida en el backend con Jakarta Bean Validation sobre un DTO de entrada
  explícito del paquete `web` del módulo. El OpenAPI generado desde esas declaraciones es la
  fuente de verdad del contrato.
- El frontend y la futura app móvil validan con los esquemas Zod generados en
  `packages/contracts` a partir del OpenAPI. Esa validación mejora la experiencia de usuario y
  **nunca sustituye** a la del servidor (`CLAUDE.md`, regla 9).
- El deserializador JSON rechaza campos desconocidos: un campo no declarado hace fallar la
  validación. Esto elimina la asignación masiva como clase de vulnerabilidad.
- **Lista blanca, nunca lista negra.** Los enumerados se declaran como tipos enumerados. Las
  cadenas libres llevan longitud máxima explícita. Los identificadores se validan como UUID. Nunca
  se intenta "limpiar" una entrada peligrosa: se rechaza.
- Los importes nunca llegan como número de coma flotante. Se reciben como cadena decimal y se
  convierten al objeto de valor `Money` en el borde. Un importe que no valide es un rechazo, no
  una coerción.
- Los errores de validación se devuelven en formato Problem Details (RFC 9457) con la lista de
  campos inválidos, **sin** reflejar el valor recibido, para no crear un vector de XSS reflejado
  ni filtrar datos en logs de intermediarios.

### 9.2 Codificación de salida

- React escapa por defecto en el renderizado de texto. **`dangerouslySetInnerHTML` está prohibido**
  y una regla de ESLint lo bloquea. Si en el futuro se requiriera renderizar HTML de plantilla, se
  sanea con DOMPurify en una utilidad única y revisada.
- Nunca se serializa una entidad de persistencia directamente. Todo lo que sale pasa por un DTO
  explícito.
- Las respuestas de API siempre llevan `Content-Type: application/json; charset=utf-8` y nunca
  `text/html`, para eliminar el XSS a través de la respuesta de la API.
- Los archivos descargados se sirven con `Content-Disposition: attachment` y nombre saneado, desde
  una URL prefirmada de corta duración del almacenamiento de objetos, nunca desde el dominio de la
  aplicación. Un PDF servido desde el propio origen que resultara ser HTML sería un XSS
  almacenado.
- Las exportaciones a CSV escapan la **inyección de fórmulas**: todo campo que comience con `=`,
  `+`, `-`, `@`, tabulador o retorno de carro se prefija con comilla simple. Sin esto, un nombre de
  estudiante malicioso se convierte en ejecución de comando cuando contabilidad abre el archivo.

### 9.3 Límites, archivos y salidas de red

| Control | Valor | Verificación |
|---|---|---|
| Tamaño máximo de cuerpo JSON | 256 KB en el portal, 1 MB en administración | Prueba de integración con cuerpo sobredimensionado, espera 413 |
| Tamaño máximo de archivo subido | 5 MB por archivo, 20 MB por solicitud | Igual |
| Profundidad máxima de JSON | 10 niveles | Evita agotamiento de pila en el analizador |
| Tamaño máximo de página en listados | 100 elementos, aplicado en servidor e ignorando el valor del cliente si lo supera | Prueba que solicita 10000 y verifica que devuelve 100 |
| Tipo de archivo | **Validado por contenido, no por extensión.** Se leen los bytes mágicos con `file-type` y se compara contra una lista blanca: PDF, PNG, JPEG. Se rechaza si extensión y contenido discrepan | Prueba que sube un ejecutable renombrado a `.pdf` y espera rechazo |
| Nombre de archivo | Se descarta el nombre original. El objeto se almacena con un UUIDv7 y el nombre original se guarda como metadato saneado | Prueba con `../../etc/passwd` como nombre |
| Ejecución de contenido subido | Los archivos se almacenan en el almacenamiento de objetos, **nunca** en el disco del servidor de aplicación, y se sirven desde un dominio distinto sin cookies de sesión | Revisión de configuración |
| Escaneo antivirus | ClamAV sobre archivos subidos, en trabajo asíncrono. El archivo queda en cuarentena hasta el resultado | Prueba con el archivo de prueba EICAR |

**Prevención de falsificación de solicitudes del lado del servidor.** Toda integración saliente
(pasarela, correo, mensajería, webhooks salientes futuros) pasa por un cliente HTTP único en
`shared/security` que aplica:

1. **Lista blanca de destinos** por nombre de host exacto, cargada desde configuración. Un destino
   no listado se rechaza antes de resolver DNS.
2. Resolución DNS explícita y **verificación de que la dirección resultante no pertenece** a rangos
   privados, de bucle local, de enlace local (incluido `169.254.169.254`, el punto de metadatos de
   nube), ni a IPv6 mapeadas de esos rangos.
3. **Prohibición de seguir redirecciones** hacia hosts fuera de la lista blanca. Máximo 2 saltos.
4. Esquemas permitidos: solo `https`. Nunca `file`, `gopher`, `ftp` ni `http`.
5. Tiempo de espera de 10 segundos y tamaño máximo de respuesta de 1 MB.
6. Ninguna URL proporcionada por un usuario se solicita jamás desde el servidor. Si en el futuro
   se necesitara (por ejemplo, una imagen de perfil por URL), se resuelve con descarga del lado
   del cliente y subida del archivo.

**Cómo se comprueba.** Prueba unitaria del cliente HTTP con una batería de URL hostiles:
`http://169.254.169.254/`, `http://127.0.0.1:5432/`, `http://[::1]/`, un host público que redirige
a `10.0.0.1`, y una URL con esquema `file`. Todas deben ser rechazadas. Regla de Semgrep que
bloquea el uso directo de `fetch` o `axios` fuera de `shared/security`.

---

## 10. Limitación de tasa y protección contra abuso

Dos capas: nginx en el borde (barata, protege contra el volumen bruto) y la aplicación con Redis
(consciente de la identidad, protege la lógica de negocio). Se necesitan ambas.

| Superficie | Endpoint | Límite por IP | Límite por cuenta | Al exceder |
|---|---|---|---|---|
| Portal | Inicio de sesión | 5 por minuto | Retroceso exponencial desde el intento 3 | 429 con `Retry-After` |
| Portal | Recuperación de contraseña | 10 por hora | **3 por hora** | 202 uniforme, pero sin enviar correo |
| Portal | Registro y activación | 5 por hora | 3 por hora por correo | 429 |
| Portal | Verificación de MFA | 20 por hora | 5 por cada 15 minutos | 429 y alerta si es sostenido |
| Portal | Consulta de estado de cuenta | 30 por minuto | 60 por minuto | 429 |
| Portal | Inicio de pago | 10 por hora | 5 por hora por estudiante | 429 y alerta |
| Portal | Descarga de PDF | 20 por hora | 30 por hora | 429 |
| Portal | Resto de lectura | 60 por minuto | 120 por minuto | 429 |
| Administración | Inicio de sesión | 10 por minuto | Retroceso exponencial | 429 |
| Administración | Lectura general | 300 por minuto | 600 por minuto | 429 |
| Administración | Escritura financiera | 60 por minuto | 120 por minuto | 429 |
| Administración | **Exportación** | 10 por hora | 20 por día | 429 y alerta al superar el umbral de filas |
| Administración | Generación de reporte pesado | 5 por hora | 10 por día | Encolado, nunca sincrónico |
| Webhook | Recepción de pasarela | 100 por minuto por IP del proveedor | No aplica | 429, con reintento del proveedor |

Reglas adicionales:

- Los límites son **más estrictos en el portal que en administración**, porque el portal está en
  internet abierto y su población de usuarios es mucho mayor y menos controlada.
- La respuesta 429 incluye `Retry-After` en segundos y no revela el límite exacto restante en
  endpoints de autenticación, para no ayudar a calibrar el ataque.
- Los límites viven en configuración con vigencia, no en el código, para poder ajustarlos en una
  fecha de pago sin desplegar.
- El estado de límite vive en Redis con expiración, de modo que los dos procesos y las futuras
  réplicas compartan el contador.
- **Protección específica contra enumeración:** un mismo origen que consulta más de 20
  identificadores de estudiante distintos en 10 minutos dispara alerta, aunque cada consulta
  individual esté dentro del límite y devuelva 404.

**Cómo se comprueba.** Prueba de carga con k6 que ejecuta el escenario de cada fila y verifica el
código de estado y la cabecera `Retry-After`. Panel de tasa de 429 por endpoint en el tablero
operativo: un pico de 429 legítimos indica un límite mal calibrado, no un ataque.

---

## 11. Gestión de secretos

### 11.1 Reglas

1. **Ningún secreto en el repositorio.** Ni en código, ni en pruebas, ni en comentarios, ni en
   archivos de ejemplo, ni en el historial de Git. El archivo `.env.example` contiene solo
   marcadores como `CAMBIAR_ESTE_VALOR`, nunca valores plausibles.
2. **Desarrollo local:** archivo `.env` fuera del control de versiones, con `.env` en
   `.gitignore` verificado. Los valores de desarrollo son distintos de los de producción y no
   sirven contra ningún sistema real.
3. **Preproducción y producción:** cifrado con **SOPS** y **age**, con el archivo cifrado
   versionado en `infra/secrets/` y la llave privada age fuera del repositorio, en el gestor de
   llaves del host y con copia en custodia. Alternativa aceptada si el proveedor lo ofrece: un
   gestor de secretos administrado. La decisión se registra en un ADR.
4. **Un secreto por entorno y por propósito.** El secreto de webhook de preproducción nunca es el
   de producción. Un secreto compartido entre propósitos multiplica el impacto de su filtración.
5. **Los secretos se inyectan como variables de entorno del contenedor en tiempo de ejecución**,
   nunca en la imagen. Una imagen con un secreto incrustado filtra ese secreto a cualquiera que
   pueda descargar la imagen.
6. **Nunca se registra un secreto.** La redacción del registro estructurado del backend incluye la lista de campos de la sección
   correspondiente de `docs/07-observabilidad-y-operaciones.md`.

### 11.2 Rotación

| Secreto | Periodo | Procedimiento resumido |
|---|---|---|
| Contraseñas de roles de PostgreSQL | Anual | Crear la nueva, actualizar el secreto, recargar el proceso, verificar, retirar la anterior |
| Secreto de webhook de la pasarela | Anual o ante incidente | El sistema acepta dos secretos activos durante 48 horas |
| Llaves de firma JWT | Semestral | Dos claves activas, publicadas por JWKS interno |
| KEK maestra de cifrado de columna | Anual | Ver 7.3 |
| Llave age de respaldo | Anual | Solo tras una restauración exitosa con la llave nueva |
| Rol de despliegue de AWS (OIDC) | Semestral, o ante cambio de personal con acceso | Sin credencial de larga vida que rotar: se revisa y, si aplica, se reduce el alcance (*trust policy*) del rol asumido por el flujo de despliegue (`docs/05-infraestructura-y-despliegue.md`, sección 7). Fuera de AWS, llaves SSH de despliegue: llave nueva agregada, verificada, llave anterior removida |
| Tokens de API de terceros (correo, mensajería) | Anual o ante incidente | Según el proveedor |
| Frase de paso de LUKS (solo fuera de AWS) | Ante cambio de personal con acceso | Documentado en el runbook de incidente. En AWS, el cifrado de EBS y de RDS usa llaves gestionadas por el proveedor y no requiere esta rotación (ADR-0014) |

Cada rotación se registra en `docs/seguridad/registro-de-rotacion.md` con secreto, fecha, actor y
verificación posterior. Un secreto sin registro de rotación en el último periodo aparece en la
alerta mensual de higiene de secretos.

### 11.3 Escaneo de secretos

- **gitleaks** en tres puntos: gancho de pre-commit local, cada corrida de integración continua
  sobre el diff, y una corrida semanal sobre **todo el historial**. Un secreto que entró hace seis
  meses y se removió después sigue estando en el historial y sigue siendo válido.
- Reglas personalizadas para los formatos propios: prefijo de secreto de webhook, formato de llave
  age, cadena de conexión de PostgreSQL.
- Detección de secretos también en las imágenes de contenedor, mediante Trivy en modo `secret`.
- **Procedimiento ante hallazgo:** el secreto se considera comprometido desde el momento de su
  commit, sin importar si el repositorio es privado. Se rota inmediatamente, se reescribe el
  historial si es viable, y se registra como incidente de severidad S2. Remover el archivo en un
  commit posterior **no** resuelve nada.

**Cómo se comprueba.** La corrida de integración continua falla si gitleaks encuentra algo. La
corrida semanal sobre el historial completo envía su resultado al canal de seguridad aunque no
encuentre nada, para que el silencio no se confunda con un trabajo detenido.

---

## 12. Bitácora de auditoría

Brecha B6 del análisis de brechas, severidad bloqueante, fase F0. Una bitácora que un
administrador puede editar no prueba nada, y en una disputa financiera la prueba es todo el valor.

### 12.1 Diseño de la tabla

```sql
CREATE TABLE shared_audit_log (
    id               BIGINT        NOT NULL,      -- asignado por el disparador de encadenamiento
    institution_id   UUID          NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    actor_id         UUID,                        -- NULL para actor de sistema
    actor_kind       TEXT          NOT NULL,      -- 'staff' | 'guardian' | 'system'
    actor_label      TEXT          NOT NULL,      -- correo o nombre de trabajo, para lectura
    source_ip        INET,
    user_agent       TEXT,
    request_id       UUID          NOT NULL,
    trace_id         TEXT,
    action           TEXT          NOT NULL,      -- 'invoice.void', 'payment.create', ...
    entity_type      TEXT          NOT NULL,
    entity_id        TEXT          NOT NULL,
    outcome          TEXT          NOT NULL,      -- 'success' | 'denied' | 'error'
    before_value     JSONB,                       -- redactado, sin datos sensibles en claro
    after_value      JSONB,
    reason           TEXT,                        -- obligatorio en anulación y ajuste
    approver_id      UUID,                        -- segregación de funciones
    prev_hash        BYTEA         NOT NULL,      -- asignado por el disparador de encadenamiento
    row_hash         BYTEA         NOT NULL,      -- asignado por el disparador de encadenamiento
    CONSTRAINT shared_audit_log_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT shared_audit_log_prev_hash_uq UNIQUE (institution_id, prev_hash),
    CONSTRAINT shared_audit_log_outcome_chk
        CHECK (outcome IN ('success', 'denied', 'error')),
    CONSTRAINT shared_audit_log_actor_kind_chk
        CHECK (actor_kind IN ('staff', 'guardian', 'system'))
);

CREATE INDEX shared_audit_log_entity_idx  ON shared_audit_log (institution_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX shared_audit_log_actor_idx   ON shared_audit_log (institution_id, actor_id, occurred_at DESC);
CREATE INDEX shared_audit_log_action_idx  ON shared_audit_log (institution_id, action, occurred_at DESC);
CREATE INDEX shared_audit_log_request_idx ON shared_audit_log (institution_id, request_id);
```

**Sin `BIGSERIAL`.** La clave primaria es compuesta, `(institution_id, id)`: ningún rol de
PostgreSQL tiene el atributo `BYPASSRLS`, así que un verificador que corre con el contexto de una
institución no puede leer ni recalcular una cadena global. `id` no lo asigna una secuencia, sino un
contador por institución que el propio disparador de encadenamiento mantiene en la tabla
`shared_audit_chain_head`, que también sirve como punto de serialización para dos escritores
concurrentes de la misma institución.

**Encadenamiento por hash, uno por institución.** Cada fila incluye el hash de la anterior **de su
misma institución**, formando una cadena independiente por institución. Alterar o eliminar
cualquier fila rompe esa cadena a partir de ese punto y la verificación lo detecta.

```
row_hash = SHA256(
    prev_hash ||
    canonical_json({ id, institution_id, occurred_at, actor_id, actor_kind,
                     source_ip, request_id, action, entity_type, entity_id,
                     outcome, before_value, after_value, reason, approver_id })
)
```

La serialización canónica ordena las claves y normaliza los números, para que el hash sea
reproducible. **Cada institución inicia su propia cadena** con su propio registro génesis, cuyo
`prev_hash` son 32 bytes de ceros; las cadenas de instituciones distintas son independientes entre
sí. El cálculo del hash y la inserción ocurren en un disparador `BEFORE INSERT` de PostgreSQL, no en
la aplicación: así una escritura por cualquier vía queda encadenada.

**Ancla externa.** Cada hora, un trabajo publica el `row_hash` de la última fila de cada institución
y su `id` en un almacenamiento de objetos con bloqueo de objeto en modo de cumplimiento. Un
atacante que recalculara toda la cadena de una institución dentro de la base de datos no puede
alterar el ancla ya publicada, y la divergencia queda demostrada. Sin este paso, cada cadena solo
protege contra manipulación torpe.

### 12.2 Eventos de auditoría obligatorios

| Categoría | Eventos |
|---|---|
| Identidad | Inicio de sesión exitoso y fallido, cierre de sesión, cambio de contraseña, restablecimiento, alta y baja de MFA, uso de código de recuperación, bloqueo por retroceso exponencial |
| Autorización | Todo acceso denegado por permiso (`outcome = 'denied'`), alta y baja de usuario, asignación y revocación de rol, cambio en la definición de un rol |
| Dinero | Creación de pago, reverso de pago, creación de cargo, aplicación de descuento o beca, ajuste manual, baja por incobrable, reembolso, todo asiento del libro mayor |
| Fiscal | Emisión de factura, emisión de nota de crédito y de débito, anulación, alta de rango CAI, cambio de correlativo actual, cambio de tasa de impuesto, bloqueo de emisión |
| Caja | Apertura de sesión, cada movimiento, cierre, diferencia declarada con su justificación, aprobación de la diferencia |
| Datos personales | Consulta de un expediente completo de estudiante, **toda exportación** con su filtro y conteo de filas, atención de una solicitud de derechos del titular, eliminación o anonimización por retención |
| Configuración | Cambio de cualquier parámetro con impacto financiero o fiscal, cambio de plantilla de notificación, cambio de umbral de alerta, activación o desactivación de bandera de funcionalidad |
| Operación | Ejecución de migración en producción, restauración de respaldo, acceso interactivo a la base de datos de producción, rotación de secretos |

Regla práctica: si la respuesta a "¿un auditor externo preguntaría por esto?" es sí, se audita.

### 12.3 Permisos que impiden borrado y actualización

```sql
-- Denegación de partida
REVOKE ALL ON shared_audit_log FROM PUBLIC;

-- La aplicación solo puede insertar y leer; sin secuencia que conceder, el id lo asigna el
-- disparador de encadenamiento sobre shared_audit_chain_head (sección 12.1)
GRANT SELECT, INSERT ON shared_audit_log TO confia_admin_app;
GRANT SELECT          ON shared_audit_log TO confia_readonly;

-- El portal no tiene acceso alguno
-- (no se emite ningún GRANT para confia_portal_app)

-- Segunda barrera: un disparador que rechaza a nivel de motor,
-- incluso para el propietario del esquema.
CREATE OR REPLACE FUNCTION shared_audit_is_append_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        '% es de solo inserción: % rechazado', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER shared_audit_log_no_update
    BEFORE UPDATE ON shared_audit_log
    FOR EACH ROW EXECUTE FUNCTION shared_audit_is_append_only();

CREATE TRIGGER shared_audit_log_no_delete
    BEFORE DELETE ON shared_audit_log
    FOR EACH ROW EXECUTE FUNCTION shared_audit_is_append_only();

CREATE TRIGGER shared_audit_log_no_truncate
    BEFORE TRUNCATE ON shared_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION shared_audit_is_append_only();
```

Nota honesta sobre el límite del control: un `SUPERUSER` de PostgreSQL puede deshabilitar el
disparador. Por eso existen el ancla externa horaria y la copia periódica a almacenamiento de solo
escritura. El objetivo alcanzable no es hacer la manipulación imposible, sino hacerla **evidente**.

### 12.4 Verificación periódica de la cadena

- **Diaria:** un trabajo verifica, **por cada institución**, la cadena completa del último mes,
  recalculando cada `row_hash`. Se registra la métrica `confia_audit_chain_verified_rows` y el
  resultado.
- **Semanal:** verificación completa de cada cadena desde su propio registro génesis.
- **Horaria:** comparación del último `row_hash` de cada institución contra su última ancla
  publicada.
- Una discrepancia en cualquier cadena genera **alerta de severidad S1** y dispara
  `docs/runbooks/incidente-de-seguridad.md`. No se intenta reparar la cadena: se preserva la
  evidencia.

**Cómo se comprueba.** Prueba de integración que inserta filas, altera una directamente con
`SUPERUSER` en el contenedor de prueba, y verifica que el verificador la detecta e identifica la
fila exacta. Prueba que intenta `UPDATE` y `DELETE` con el rol de aplicación y espera error.

---

## 13. Seguridad de la cadena de suministro

Con un solo desarrollador, una dependencia comprometida es un vector con mejor relación de costo y
beneficio para el atacante que cualquier ataque directo.

| Control | Implementación | Verificación |
|---|---|---|
| Fijado de versiones | Frontend: `pnpm-lock.yaml` versionado e instalación con `pnpm install --frozen-lockfile`. Backend: Maven Wrapper comprometido, versiones gestionadas por la lista de materiales de Spring Boot, `maven-enforcer-plugin` con convergencia de dependencias y prohibición de versiones `SNAPSHOT` en la rama principal. Sin rangos flexibles resueltos en el despliegue | La construcción falla si el archivo de bloqueo no coincide con el manifiesto o si el enforcer detecta divergencia |
| Imágenes base fijadas por digest | Imagen JDK y JRE de la misma distribución de Java 25 para la API, e imagen de Node para la construcción estática del frontend, todas con `@sha256:...`. Nunca `:latest` ni una etiqueta móvil | Regla de Hadolint en integración continua |
| Actualización de dependencias | **Renovate**, con agrupación por tipo, periodo de maduración mínimo de 3 días para versiones menores y parche, y 7 días para mayores. Las actualizaciones de seguridad se marcan y se priorizan | Pull request automático que ejecuta la batería completa |
| Auditoría de dependencias | Frontend: `pnpm audit --audit-level=high` en cada corrida, más `osv-scanner` sobre el archivo de bloqueo, que cubre avisos ausentes del registro npm. Backend: escaneo de las dependencias resueltas de Maven en cada corrida; la herramienta concreta se fija en F0. Trivy sobre la imagen cubre además las bibliotecas empaquetadas en el artefacto | La corrida falla ante severidad alta o crítica sin excepción documentada |
| Excepciones | Toda excepción vive en `.security/audit-exceptions.yaml` con identificador del aviso, motivo, evaluación de explotabilidad y **fecha de caducidad**. Una excepción caducada rompe la construcción | Revisión de la lista en el cierre de cada fase |
| Nuevas dependencias | Toda incorporación se justifica en el pull request: qué problema resuelve, alternativas, mantenimiento del proyecto, número de dependencias transitivas que arrastra | Revisión humana obligatoria |
| Scripts de instalación (frontend) | `pnpm` configurado para no ejecutar scripts de ciclo de vida de dependencias, con lista blanca explícita para las que lo requieren de verdad (por ejemplo, binarios nativos) | `.npmrc` versionado y revisado |
| Lista de materiales de software | Generación de SBOM en formato **CycloneDX** para el código de ambas cadenas (con `@cyclonedx/cyclonedx-npm` en el frontend y con el complemento CycloneDX de Maven en el backend) y con Syft para la imagen de contenedor. Se adjunta como artefacto de cada versión etiquetada y se conserva junto al registro de despliegue | El paso de construcción falla si el SBOM no se genera |
| Imágenes base mínimas | API: JDK de Java 25 para construcción y JRE de la misma distribución para ejecución. Se prefiere una variante sin gestor de paquetes ni intérprete de comandos si existe para Java 25 en esa distribución; la elección se valida en F0. Frontend: Node solo para la construcción estática y nginx para servirla | Trivy reporta el conteo de paquetes. Si la imagen final de la API conserva shell, la excepción se documenta con su motivo y se revisa en cada actualización de imagen base |
| Firma de imágenes | **cosign** con firma sin llave por OIDC de GitHub Actions. El host de producción verifica la firma y la procedencia antes de ejecutar | Paso de despliegue que ejecuta `cosign verify` y aborta si falla |
| Procedencia de la construcción | Atestación SLSA nivel 2 generada por GitHub Actions y adjuntada a la imagen | Verificada junto con la firma |

**Cómo se comprueba en conjunto.** El despliegue a producción se detiene si: el archivo de bloqueo
no coincide, la auditoría reporta severidad alta sin excepción vigente, el SBOM no se generó, o la
firma de la imagen no verifica. Ninguna de estas puertas admite omisión manual desde la interfaz.

---

## 14. Seguridad en el ciclo de desarrollo

Las puertas se ejecutan en integración continua y **bloquean la fusión**. Con un solo
desarrollador, la revisión por pares no existe como control: la automatización la sustituye.

| Etapa | Herramienta | Configuración | Bloquea |
|---|---|---|---|
| Pre-commit local | gitleaks en todo el repositorio; ESLint, Prettier y verificación de tipos sobre archivos del frontend modificados; compilación del backend | `lefthook` | El commit |
| Verificación de tipos | Frontend: `tsc --noEmit` con `strict`, `noUncheckedIndexedAccess` y `exactOptionalPropertyTypes`. Backend: compilador de Java dentro de `./mvnw verify` | Aplicaciones web, `packages/*` y `apps/api` | La fusión |
| Análisis estático de seguridad | **Semgrep** con `p/java`, `p/typescript`, `p/owasp-top-ten` más reglas propias | Ver abajo | La fusión ante severidad `ERROR` |
| Análisis estático profundo | **CodeQL** en la rama principal y en cada pull request | Consultas de seguridad y calidad | La fusión ante severidad alta |
| Reglas de frontera | Backend: ArchUnit y Spring Modulith. Frontend: `dependency-cruiser` y reglas de ESLint de límites de módulo | Ver `docs/01-arquitectura.md` sección 4 y ADR-0002 | La fusión |
| Pruebas unitarias | Backend: JUnit y AssertJ, con jqwik para pruebas de propiedad. Frontend: Vitest | Cobertura global 80 por ciento; módulo de núcleo y paquete `domain` de cada módulo 95 por ciento, medida con JaCoCo | La fusión |
| Pruebas de mutación | PIT sobre el módulo de núcleo y el paquete `domain` de cada módulo | Umbral 80 | La fusión |
| Pruebas de integración | JUnit con Testcontainers sobre PostgreSQL real | Incluye todas las pruebas de RLS de la sección 6.4 | La fusión |
| Escaneo de contenedores | **Trivy** en modos `vuln`, `secret`, `misconfig` | Severidad `HIGH` y `CRITICAL` | La fusión |
| Escaneo de infraestructura | Trivy y Hadolint sobre Dockerfiles y compose | Severidad alta | La fusión |
| Análisis dinámico | **OWASP ZAP** en modo línea base contra el entorno de preproducción | Ejecución nocturna y previa a cada versión | La versión, no cada fusión |
| Accesibilidad | axe-core en pruebas de componente y en Playwright | Cero violaciones críticas o serias | La fusión |
| Pruebas de extremo a extremo | Playwright sobre los flujos críticos | Ver `docs/06-estrategia-de-testing.md` | El despliegue |

**Reglas propias de Semgrep** (viven en `.semgrep/`), cada una derivada de una regla no negociable
del proyecto:

| Regla | Qué bloquea |
|---|---|
| `no-float-money` | Backend: tipo `double`, `float` o sus envolturas en un identificador cuyo nombre coincide con `amount`, `total`, `monto`, `importe`, `saldo`, `price`. Frontend: aritmética con `number` sobre esos identificadores. Complementa las reglas de ArchUnit de ADR-0004 |
| `no-raw-sql-concat` | Concatenación o formateo de cadenas para construir una sentencia SQL en el backend |
| `no-entity-serialization` | Retorno directo de una entidad de persistencia desde un controlador, sin pasar por un DTO |
| `no-transaction-outside-security` | Declaración o apertura de transacciones fuera del componente transaccional de `shared/security` |
| `no-direct-http-client` | Uso directo de un cliente HTTP fuera del cliente saneado de `shared/security` |
| `no-tls-bypass` | Desactivación de la verificación de certificados TLS: gestores de confianza o verificadores de nombre de host permisivos |
| `no-dangerous-html` | `dangerouslySetInnerHTML` |
| `no-console-in-api` | `System.out`, `System.err` o `printStackTrace` en `apps/api`, que evaden la redacción del registro estructurado |
| `require-permission-annotation` | Método de controlador sin anotación de permiso ni marca de público |
| `no-eval` | Frontend: `eval`, `new Function`, `setTimeout` con cadena |

**Cómo se comprueba que las puertas funcionan.** Se mantiene una rama de prueba con violaciones
deliberadas de cada regla, y una corrida mensual verifica que **todas** las puertas la rechazan.
Una puerta que nunca ha fallado puede estar apagada, y no hay forma de saberlo sin probarla.

---

## 15. Endurecimiento del servidor

### 15.1 Contenedores

| Control | Implementación | Verificación |
|---|---|---|
| Sin privilegios de superusuario | Usuario dedicado `confia` (UID 10001) en los Dockerfile de la API y de las aplicaciones web (`docs/05-infraestructura-y-despliegue.md`, sección 3). `user: "10001:10001"` en compose | `docker inspect` en la lista previa a producción confirma que el usuario no es root |
| Sistema de archivos raíz de solo lectura | `read_only: true`, con `tmpfs` para `/tmp` con `noexec` y `nosuid` | El contenedor no arranca si el código intenta escribir fuera de los volúmenes declarados |
| Sin escalada de privilegios | `security_opt: ["no-new-privileges:true"]` | Igual |
| Capacidades mínimas | `cap_drop: [ALL]`, sin `cap_add` | Igual |
| Imagen base mínima | Distroless para ejecución. Sin shell, sin gestor de paquetes, sin curl | Un intento de `docker exec ... sh` falla, y eso es lo esperado |
| Límites de recursos | `mem_limit`, `cpus` y `pids_limit` declarados por servicio | Evita que un servicio agote el host |
| Sin socket de Docker montado | Prohibido montar `/var/run/docker.sock` en cualquier contenedor de aplicación | Revisión de compose en integración continua |
| Comprobaciones de salud | `HEALTHCHECK` en el Dockerfile y `healthcheck` en compose | El despliegue sin interrupción depende de ellas |
| Seccomp y AppArmor | Perfil por defecto de Docker activo, nunca `unconfined` | Revisión de compose |

### 15.2 Sistema operativo

**En producción sobre AWS, no hay acceso SSH.** ADR-0014 prohíbe el puerto SSH en la instancia
EC2 de aplicación: el acceso administrativo es por AWS Systems Manager Session Manager, que no
requiere un puerto de entrada abierto, se audita centralmente y no depende de una llave SSH que
custodiar. Las filas de esta tabla sobre SSH, `fail2ban` para `sshd` y el cortafuegos de sistema
operativo son la base de endurecimiento que sigue aplicando (a) en desarrollo local y en
preproducción si corren fuera de AWS, y (b) como referencia si la institución, tras la
transferencia tecnológica (ADR-0014, plan de salida), decide operar sobre infraestructura propia
sin Session Manager. En la instancia EC2 de producción, el grupo de seguridad de AWS (nivel 2 de
ADR-0014) cumple el rol del cortafuegos: solo admite HTTPS desde los rangos de Cloudflare, sin
ninguna regla para el puerto 22.

| Control | Implementación | Verificación |
|---|---|---|
| Distribución | Debian estable o Ubuntu LTS, instalación mínima sin entorno gráfico | Inventario en la lista previa a producción |
| Acceso administrativo en AWS | AWS Systems Manager Session Manager, sin puerto SSH abierto en el grupo de seguridad | Verificación en el pipeline de despliegue de que el grupo de seguridad no admite el puerto 22 (`docs/05-infraestructura-y-despliegue.md`, sección 2) |
| Acceso SSH (fuera de AWS, o tras la transferencia a infraestructura propia) | **Solo por llave.** `PasswordAuthentication no`, `PermitRootLogin no`, `KbdInteractiveAuthentication no`, `AllowUsers` con la lista explícita | `sshd -T` en la lista previa a producción |
| Puerto SSH (fuera de AWS) | No estándar. Reduce el ruido de escaneo automatizado. **No es un control de seguridad por sí mismo**, y se declara como tal para no crear una falsa sensación de protección | Verificación de configuración |
| fail2ban (fuera de AWS) | Filtros activos para `sshd` y para nginx (`nginx-limit-req` y `nginx-botsearch`). Prohibición de 1 hora tras 5 intentos, con prohibición recurrente extendida | `fail2ban-client status` en la revisión mensual |
| Cortafuegos | En AWS, el grupo de seguridad de la instancia: **política de denegación por defecto**, solo HTTPS (443) desde los rangos de Cloudflare, sin puerto SSH. Fuera de AWS, `ufw` o `nftables` equivalente | Verificación del grupo de seguridad en AWS; `ufw status verbose` fuera de AWS |
| Actualizaciones de seguridad | `unattended-upgrades` limitado al repositorio de seguridad, con reinicio automático en ventana de mantenimiento declarada y notificación por correo | Revisión mensual del registro de actualizaciones |
| Sincronización de tiempo | `chrony` con fuentes conocidas. Crítico: la bitácora, los tokens TOTP y las fechas fiscales dependen del reloj | Alerta si la desviación supera 1 segundo |
| Sin servicios innecesarios | Sin servidor de correo escuchando, sin RPC, sin NFS. `ss -tlnp` no debe mostrar nada inesperado | Revisión mensual con la salida guardada como evidencia |
| Auditoría del sistema | `auditd` con reglas para acceso a `/etc/shadow`, cambios en configuración de SSH y ejecución de `docker` | Revisión mensual |
| Endurecimiento base | Aplicación del CIS Benchmark de la distribución en su nivel 1, con excepciones documentadas | Ejecución de `lynis audit system`, con puntuación mínima acordada |

### 15.3 Separación de red

| Control | Implementación | Verificación |
|---|---|---|
| PostgreSQL sin puerto público | En producción, RDS for PostgreSQL en subred privada, sin acceso público, con un grupo de seguridad que solo admite conexiones desde el grupo de seguridad de la instancia de aplicación (ADR-0014). En local y en pruebas, `listen_addresses` limitado a la interfaz de la red privada, sin publicación de puerto en compose. **Ni siquiera de forma temporal para una migración** | Verificación de que RDS no tiene `PubliclyAccessible` activado y de que su grupo de seguridad no admite `0.0.0.0/0`; `nmap` desde fuera en local y en pruebas |
| Redis sin puerto público | Sin publicación de puerto en compose, más `requirepass` y `rename-command` para `FLUSHALL`, `CONFIG` y `KEYS` | Igual |
| Red privada entre capas | En AWS, la instancia de aplicación y RDS se comunican dentro de la misma VPC, por subredes y grupos de seguridad (ADR-0014, nivel 2). Sin NAT Gateway: RDS no necesita salida a internet. Fuera de AWS, red privada del proveedor o WireGuard si no existe. Nunca por internet | Revisión de topología |
| Redes de Docker segmentadas | Red `edge` (nginx y aplicaciones) y red `data` (aplicaciones, Redis; RDS vive fuera de Docker Compose). nginx **no** pertenece a la red `data` | Revisión de compose en integración continua |
| Acceso administrativo al panel | Restricción por dirección IP institucional en nginx, evaluada sobre la IP real recuperada tras el borde de Cloudflare (`docs/05-infraestructura-y-despliegue.md`, sección 10). La restricción es una capa adicional, nunca la única: MFA sigue siendo obligatoria | Prueba de acceso desde una IP no autorizada |
| Salidas de red | La instancia de aplicación no necesita salida a internet salvo para actualizaciones, envío de correo por SES y respaldo fuera de sitio. Se restringe con reglas de salida del grupo de seguridad | Revisión de reglas |

---

## 16. Alcance de cumplimiento de pagos con tarjeta

Brecha A9 del análisis de brechas. Fase F9.

### 16.1 Por qué página alojada o campos alojados

La decisión determinante es **no tocar nunca los datos de tarjeta**. Se adopta una de estas dos
integraciones del proveedor, y ninguna otra:

1. **Página alojada (redirección):** el encargado sale del portal hacia el dominio del proveedor,
   paga allí y regresa. Es la opción de menor alcance y menor riesgo.
2. **Campos alojados (iframe del proveedor):** el formulario se ve integrado en el portal, pero los
   campos de tarjeta viven en un iframe del dominio del proveedor. El portal nunca recibe el
   número de tarjeta.

Consecuencia de cumplimiento: con cualquiera de las dos, el alcance de PCI DSS se reduce al
cuestionario de autoevaluación más simple aplicable (SAQ A para página alojada, y SAQ A o SAQ A-EP
según cómo el proveedor implemente los campos alojados y quién controle la página que los
contiene). Si el formulario de tarjeta viviera en el portal y los datos pasaran por el servidor de
CONFIA, el alcance se dispararía a SAQ D, que exige controles que **un desarrollador solo no puede
sostener**: segmentación de red auditada, escaneo trimestral por proveedor aprobado, pruebas de
penetración de segmentación, gestión formal de vulnerabilidades y política documentada de
seguridad de la información.

**Punto que requiere confirmación:** el cuestionario exacto aplicable depende del proveedor
seleccionado y de su documentación de cumplimiento. Se confirma con el proveedor y se archiva su
Atestación de Cumplimiento antes de integrar.

### 16.2 Lo que queda prohibido tocar

| Prohibición | Alcance |
|---|---|
| Recibir el número de tarjeta (PAN) en cualquier endpoint de CONFIA | Absoluto |
| Recibir el código de verificación (CVV/CVC) | Absoluto, incluso de forma transitoria |
| Recibir la fecha de vencimiento junto con el PAN | Absoluto |
| Almacenar el PAN, aunque sea cifrado o truncado más allá de los últimos 4 dígitos | Absoluto |
| Registrar en logs cualquier fragmento de dato de tarjeta | Absoluto. La redacción del registro estructurado incluye patrones de PAN |
| Servir el JavaScript del proveedor desde el propio dominio | Absoluto. Se carga siempre desde el dominio del proveedor, declarado en la CSP |
| Capturar los campos del iframe con JavaScript | Absoluto, y técnicamente imposible por el aislamiento de origen. Se declara para que nadie lo intente con un proveedor que lo permita |
| Almacenar datos de tarjeta en una captura de pantalla de soporte o en un adjunto de incidente | Absoluto |

Lo que sí se almacena, porque el proveedor lo devuelve y no es dato de tarjeta: los últimos 4
dígitos, la marca, el token del proveedor (cifrado a nivel de columna, ver 7.3), el identificador
de la transacción y su estado.

**Cómo se comprueba.** Regla de Semgrep que bloquea cualquier identificador que coincida con
`card_number`, `pan`, `cvv`, `cvc`, `card_cvc`, `expiry` en un DTO del backend o en un esquema del
frontend. Prueba que envía un cuerpo con un campo `cardNumber` a cada endpoint del portal y
verifica que el rechazo de campos desconocidos lo rechaza. Escaneo periódico de la base de datos buscando patrones de PAN con
`luhn` sobre columnas de texto.

### 16.3 Verificación de firma e idempotencia de webhooks

Procedimiento obligatorio en el orden exacto:

1. **Leer el cuerpo crudo** como bytes, antes de cualquier análisis de JSON. Analizar y volver a
   serializar cambia los bytes y rompe la verificación de firma.
2. **Verificar la firma HMAC** con el secreto compartido, comparando en tiempo constante con
   `MessageDigest.isEqual` del JDK. Si falta la firma o no coincide, responder 401 y registrar el intento
   como evento de seguridad. **No hay procesamiento sin firma válida.**
3. **Verificar la marca de tiempo** incluida dentro del cuerpo firmado. Si difiere del reloj del
   servidor en más de 5 minutos, rechazar. Previene la reproducción de un evento antiguo.
4. **Persistir el evento crudo** (cuerpo, cabeceras, firma, momento de recepción) en una tabla de
   solo inserción, antes de procesarlo.
5. **Verificar idempotencia** por el identificador de evento del proveedor, con índice único. Si
   ya existe, responder 200 sin volver a procesar. Un proveedor reintenta, y debe hacerlo.
6. **Validar la estructura con Bean Validation** sobre un DTO explícito. La respuesta del proveedor es entrada no confiable, aunque
   la firma sea válida.
7. **Reconciliar el monto contra la intención de pago** creada por el servidor. Si el monto o la
   moneda no coinciden con lo esperado, no se acredita: se registra la discrepancia y se genera
   alerta.
8. **Responder 202 rápido** y procesar de forma asíncrona. Un procesamiento lento provoca
   reintentos del proveedor y multiplica la carga.
9. **Conciliar contra la liquidación** del proveedor de forma periódica. El webhook dice que el
   pago se autorizó; la liquidación dice que el dinero llegó. No son lo mismo.

**Cómo se comprueba.** Pruebas de integración: firma inválida devuelve 401 y no crea asiento;
firma válida con marca de tiempo de hace 10 minutos devuelve 400; evento duplicado devuelve 200 y
crea **exactamente un** asiento; monto discrepante no acredita y genera alerta.

---

## 17. Respuesta a incidentes

### 17.1 Clasificación de severidad

| Severidad | Definición | Ejemplos | Respuesta inicial | Quién decide |
|---|---|---|---|---|
| **S1 Crítica** | Compromiso confirmado, pérdida de dinero, o exposición de datos personales de menores | Acceso no autorizado confirmado, cadena de auditoría rota, ransomware, base de datos comprometida | **Inmediata**, a cualquier hora | Desarrollador, con notificación inmediata al propietario |
| **S2 Alta** | Compromiso probable o vulnerabilidad explotable en producción | Secreto filtrado en el repositorio, vulnerabilidad crítica sin parche, indicios de relleno de credenciales exitoso | **Menos de 4 horas** | Desarrollador |
| **S3 Media** | Vulnerabilidad sin explotación conocida, o incidente contenido | Dependencia vulnerable no alcanzable desde la ruta expuesta, intento de ataque bloqueado y sostenido | **Menos de 24 horas** | Desarrollador |
| **S4 Baja** | Hallazgo sin impacto inmediato | Cabecera faltante, resultado informativo de un escaneo | Siguiente ciclo de trabajo | Desarrollador |

### 17.2 Procedimiento paso a paso

**Fase 1: detección y registro (primeros 15 minutos)**

1. Abrir un registro de incidente en `docs/incidentes/AAAA-MM-DD-<identificador>.md` con la hora
   de detección en UTC, la fuente de la detección y los indicios.
2. Asignar severidad provisional. Ante la duda, se escala hacia arriba: una S1 degradada después
   cuesta menos que una S2 que resultó ser S1.
3. **Iniciar la bitácora de acciones.** Cada acción con su hora exacta. Esta bitácora es
   evidencia y determina la calidad de la notificación de brecha posterior.

**Fase 2: contención (S1: primera hora)**

4. **Preservar evidencia antes de tocar nada.** Ver 17.5. Reiniciar un servidor comprometido borra
   la memoria y con ella la mayor parte de la evidencia.
5. Contener según el vector:
   - Cuenta comprometida: revocar todas sus sesiones, deshabilitar la cuenta, forzar
     restablecimiento. No borrar la cuenta: es evidencia.
   - Secreto filtrado: rotar de inmediato, aunque no haya evidencia de uso.
   - Servidor comprometido: aislar de la red antes de apagar. Preferir el aislamiento al apagado.
   - Vulnerabilidad explotable: deshabilitar la funcionalidad afectada con una bandera antes que
     desplegar una corrección apresurada.
   - Ransomware: aislar todo, **no pagar**, ejecutar `docs/runbooks/restauracion-de-respaldo.md`.
6. Declarar si el sistema continúa operando o se detiene. Un sistema financiero comprometido que
   sigue registrando dinero produce datos que después habrá que auditar movimiento por movimiento.

**Fase 3: evaluación (S1: primeras 24 horas)**

7. Determinar el alcance: qué datos, cuántos titulares, cuántos son **menores de edad**, qué
   periodo de exposición, si hubo exfiltración confirmada o solo posible acceso.
8. Determinar el vector inicial y si sigue abierto.
9. Determinar si hay impacto financiero y si el libro mayor está íntegro (verificar la cadena de
   auditoría y el cuadre del debe y el haber).
10. **Decidir si corresponde notificación de brecha.** Ver 17.4.

**Fase 4: erradicación y recuperación**

11. Cerrar el vector con una corrección verificada, no con un parche apresurado.
12. Rotar **todos** los secretos que el atacante pudo alcanzar, no solo los que se confirmó que
    alcanzó.
13. Restaurar desde un punto anterior al compromiso si hay manipulación de datos. Ver el runbook
    de restauración.
14. Verificar la integridad completa antes de reanudar: cadena de auditoría, cuadre del libro
    mayor, correlativos fiscales sin huecos injustificados.
15. Monitoreo reforzado durante 30 días.

**Fase 5: cierre**

16. Análisis de causa raíz **sin atribución de culpa**. El objetivo es el control faltante, no la
    persona.
17. Acciones correctivas con responsable y fecha, incorporadas al backlog como trabajo comprometido.
18. Actualizar este documento y los runbooks con lo aprendido.
19. Cerrar el registro de incidente y conservarlo según la política de retención.

### 17.3 Contactos

Se mantiene la tabla completa en `docs/seguridad/contactos-de-incidente.md`, fuera del repositorio
público si el repositorio llegara a serlo, y **también en copia impresa**, porque un incidente
puede dejar sin acceso al repositorio.

| Rol | Responsabilidad | Cuándo se contacta |
|---|---|---|
| Desarrollador responsable | Respuesta técnica y contención | Toda severidad |
| Propietario del producto de la institución | Decisión de negocio, comunicación interna | S1 de inmediato, S2 en 4 horas |
| Dirección de la institución | Decisión sobre notificación pública y a padres | S1 con exposición de datos |
| Contador de la institución | Impacto fiscal y contable | Cualquier incidente que afecte facturación o libro mayor |
| Asesoría legal de la institución | Obligaciones de notificación y plazos locales | S1 con datos personales. **Punto que requiere confirmación:** el plazo y la autoridad receptora concretos en Honduras deben ser confirmados por asesoría legal. Ver `docs/08-datos-privacidad-y-retencion.md` |
| Proveedor de hosting | Aislamiento, instantáneas, registros de red | Compromiso de servidor |
| Proveedor de pasarela | Fraude o compromiso relacionado con pagos | Incidente con pagos |
| Autoridad de protección de datos aplicable | Notificación formal | Según determine la asesoría legal |

### 17.4 Procedimiento de notificación de brecha

**Punto que requiere confirmación con asesoría legal:** las obligaciones concretas de notificación
en Honduras, incluidos el plazo, la autoridad receptora y el contenido exigido. Mientras no se
confirmen, se adopta como referencia el estándar más exigente conocido (72 horas desde el
conocimiento, con notificación a los titulares cuando exista alto riesgo), porque cumplir un
estándar más exigente nunca incumple uno más laxo.

**Criterio de notificación.** Se notifica cuando hay acceso no autorizado, pérdida o alteración de
datos personales con riesgo para los titulares. Dado que el sistema trata datos de **menores de
edad**, el umbral de riesgo se considera más bajo y el criterio por defecto es **notificar**.

**Contenido mínimo de la notificación a la autoridad:**

1. Naturaleza de la brecha y categorías de datos afectados.
2. Número aproximado de titulares afectados, **con indicación expresa de cuántos son menores**.
3. Momento de ocurrencia, momento de detección y momento de contención.
4. Consecuencias probables.
5. Medidas adoptadas y propuestas, incluidas las de mitigación para los titulares.
6. Datos de contacto del responsable.
7. Si la información está incompleta, se notifica lo conocido y se completa después. **El plazo no
   se detiene mientras se investiga.**

**Contenido de la notificación a los titulares** (padres y encargados), en lenguaje claro y sin
tecnicismos: qué ocurrió, qué datos de su hijo o de él mismo estuvieron afectados, qué está
haciendo la institución, qué debe hacer el titular (por ejemplo, cambiar la contraseña si la
reutiliza en otros servicios) y a quién contactar.

### 17.5 Preservación de evidencia

Reglas, en orden de prioridad:

1. **No apagar un servidor comprometido antes de capturar la memoria**, si el escenario lo
   permite. Aislar de la red es preferible a apagar.
2. Tomar una **instantánea del disco** en el proveedor de nube antes de cualquier remediación.
3. Copiar los logs relevantes a almacenamiento inmutable **antes** de que la rotación los elimine.
   La rotación por defecto puede ser de 7 días y un incidente investigado en la semana dos pierde
   la evidencia.
4. Exportar el rango relevante de la bitácora de auditoría y **verificar su cadena de hash contra
   el ancla externa** antes de la exportación, para que la evidencia sea defendible.
5. Registrar el hash SHA-256 de cada artefacto de evidencia en el registro del incidente, con su
   momento de captura.
6. Conservar la cadena de custodia: quién capturó qué, cuándo y dónde se almacenó.
7. Almacenar la evidencia cifrada y con acceso restringido. La evidencia de una brecha de datos
   personales contiene datos personales.
8. Retención de la evidencia: mínimo 2 años, o el plazo que indique la asesoría legal si es mayor.

---

## 18. Lista de verificación previa a producción

Ninguna casilla se marca por criterio: cada una exige el comando ejecutado o el artefacto adjunto.
Una casilla sin evidencia no está marcada.

### Autenticación e identidad

- [ ] Argon2id calibrado en el hardware de producción, con tiempo de verificación entre 250 y 500 ms bajo carga. Evidencia: salida del benchmark
- [ ] Pimienta de Argon2id presente en el gestor de secretos y ausente de la base de datos
- [ ] Verificación contra listas de contraseñas comprometidas activa, con la lista local empaquetada. Evidencia: intento de registro con `Password123!` rechazado
- [ ] MFA obligatoria y activa para todos los usuarios con permiso de escritura financiera. Evidencia: salida de la consulta de verificación
- [ ] Sin cuentas con contraseña por defecto, de ejemplo o de semilla en producción. Evidencia: consulta de usuarios creados
- [ ] Retroceso exponencial verificado. Evidencia: prueba de integración en verde
- [ ] Uniformidad de tiempo de respuesta contra enumeración. Evidencia: reporte de la prueba de temporización
- [ ] Recuperación de contraseña: token de un solo uso, 30 minutos, hasheado en reposo. Evidencia: prueba de integración
- [ ] Claves de firma JWT distintas entre portal y administración, con `aud` distinto. Evidencia: prueba que presenta un token del portal a la API administrativa y recibe 401

### Autorización

- [ ] Toda ruta de OpenAPI declara permiso o está en la lista blanca de rutas públicas. Evidencia: salida de la prueba de arranque
- [ ] La matriz de permisos de la sección 5.2 coincide con la definición semilla. Evidencia: prueba de matriz en verde
- [ ] Segregación de funciones verificada en las 8 operaciones de la sección 5.3. Evidencia: pruebas en verde
- [ ] Prueba de autorización a nivel de objeto presente por cada recurso con propietario. Evidencia: inventario de pruebas
- [ ] Objetos ajenos devuelven 404, no 403. Evidencia: prueba de extremo a extremo

### Base de datos

- [ ] RLS habilitada y forzada en todas las tablas con `institution_id`. Evidencia: la consulta de inventario de 6.4 devuelve cero filas
- [ ] Ningún rol de aplicación tiene `BYPASSRLS` ni es `SUPERUSER`. Evidencia: consulta a `pg_roles`
- [ ] El rol del portal no puede leer `user`, `role`, `cai_range`, `cashbox_session` ni `shared_audit_log`. Evidencia: prueba de permisos
- [ ] `shared_audit_log` rechaza `UPDATE`, `DELETE` y `TRUNCATE` para el rol de aplicación, incluso para `confia_owner`, el propietario del esquema. Evidencia: prueba de integración
- [ ] PostgreSQL no responde en el puerto 5432 desde fuera de la red privada. Evidencia: salida de `nmap` desde una IP externa
- [ ] Conexión con `sslmode=verify-full`. Evidencia: cadena de conexión revisada
- [ ] `statement_timeout` configurado por rol

### Criptografía y datos

- [ ] TLS 1.3 activo, calificación A o superior en SSL Labs. Evidencia: captura del reporte
- [ ] HSTS con `preload` y dominio inscrito. Evidencia: consulta a hstspreload.org
- [ ] Certificado de origen de Cloudflare vigente y TLS de borde de Cloudflare en modo *Full (strict)*. Fuera de AWS: certificados con renovación automática probada. Evidencia: fecha de expiración del certificado de origen, o ejecución de renovación en seco
- [ ] Cifrado de columna activo en todos los campos de la tabla de 7.3. Evidencia: consulta SQL cruda que muestra el prefijo `v1:` y no el texto en claro
- [ ] AAD verificado: un valor cifrado no descifra con el contexto de otra fila. Evidencia: prueba de integración
- [ ] Cifrado en reposo activo: EBS y RDS cifrados con llaves gestionadas por AWS. Evidencia: configuración de la instancia y de RDS (ADR-0014). Fuera de AWS: cifrado de disco LUKS activo en el host de datos. Evidencia: salida de `lsblk` con el tipo `crypt`

### Navegador y borde

- [ ] CSP con nonce activa, sin `unsafe-inline` ni `unsafe-eval`, en ambos dominios. Evidencia: `curl -I` y consola del navegador sin violaciones
- [ ] Las seis cabeceras obligatorias presentes. Evidencia: salida de `curl -I` de ambos dominios
- [ ] Protección CSRF verificada. Evidencia: prueba de integración
- [ ] CORS con lista blanca por aplicación, sin comodines. Evidencia: configuración revisada
- [ ] `server_tokens off` y cabeceras de versión eliminadas. Evidencia: `curl -I`

### Entrada y abuso

- [ ] Todo DTO de entrada del backend rechaza campos desconocidos. Evidencia: prueba de integración en verde
- [ ] Límites de tamaño de carga útil activos. Evidencia: prueba que recibe 413
- [ ] Validación de tipo de archivo por contenido. Evidencia: prueba con ejecutable renombrado
- [ ] Escaneo antivirus operativo. Evidencia: prueba con archivo EICAR
- [ ] Cliente HTTP saneado contra falsificación de solicitudes del lado del servidor. Evidencia: batería de URL hostiles rechazadas
- [ ] Límites de tasa de la sección 10 activos y calibrados. Evidencia: reporte de k6
- [ ] Escape de inyección de fórmulas en exportaciones CSV. Evidencia: prueba unitaria

### Secretos y cadena de suministro

- [ ] gitleaks sobre el historial completo sin hallazgos. Evidencia: salida del último escaneo semanal
- [ ] Ningún secreto en `.env.example`, en pruebas ni en el historial
- [ ] Todos los secretos de producción distintos de los de desarrollo y preproducción
- [ ] Registro de rotación al día. Evidencia: `docs/seguridad/registro-de-rotacion.md`
- [ ] SBOM CycloneDX generado y archivado para la versión desplegada
- [ ] Imagen firmada con cosign y verificada en el host antes de ejecutar. Evidencia: salida de `cosign verify`
- [ ] Sin vulnerabilidades altas o críticas sin excepción vigente. Evidencia: reporte de Trivy, del escaneo de dependencias de Maven y de osv-scanner

### Auditoría y observabilidad

- [ ] Bitácora de auditoría operativa con encadenamiento por hash. Evidencia: verificación completa en verde
- [ ] Ancla externa publicándose cada hora. Evidencia: listado de objetos en el bucket
- [ ] Los eventos obligatorios de 12.2 se registran todos. Evidencia: recorrido manual con verificación
- [ ] Redacción de campos sensibles activa en el registro estructurado del backend. Evidencia: inspección de logs de un flujo de pago completo
- [ ] Alertas de la sección correspondiente de observabilidad configuradas y **probadas disparando cada una**
- [ ] Comprobaciones de salud y disponibilidad respondiendo correctamente

### Servidor

- [ ] Contenedores sin superusuario, con raíz de solo lectura, sin nuevas capacidades. Evidencia: `docker inspect`
- [ ] Sin puerto SSH en el grupo de seguridad de la instancia EC2; acceso administrativo solo por Session Manager. Evidencia: descripción del grupo de seguridad (ADR-0014). Fuera de AWS: SSH solo por llave, sin acceso de root, en puerto no estándar. Evidencia: `sshd -T`
- [ ] Grupo de seguridad con denegación por defecto, solo HTTPS desde los rangos de Cloudflare. Evidencia: descripción del grupo de seguridad. Fuera de AWS: cortafuegos con denegación por defecto. Evidencia: `ufw status verbose`
- [ ] fail2ban activo con filtros de sshd y nginx, aplicable fuera de AWS. Evidencia: `fail2ban-client status`
- [ ] Actualizaciones de seguridad automáticas activas. Evidencia: configuración y último registro
- [ ] Sincronización de tiempo activa con desviación menor a 1 segundo. Evidencia: `chronyc tracking`
- [ ] Sin servicios inesperados escuchando. Evidencia: salida de `ss -tlnp`
- [ ] `lynis audit system` con la puntuación mínima acordada. Evidencia: reporte

### Continuidad

- [ ] Respaldo automático de RDS con restauración a un punto en el tiempo habilitado. Evidencia: configuración de RDS y último punto restaurable (ADR-0014)
- [ ] Respaldo lógico fuera de sitio nocturno funcionando. Evidencia: últimos 7 registros de ejecución de `infra/scripts/backup.sh`
- [ ] Respaldos cifrados con age. Evidencia: intento de lectura sin llave que falla
- [ ] Copia fuera de sitio con bloqueo de objeto. Evidencia: intento de borrado con la credencial de respaldo que es rechazado
- [ ] **Restauración probada de extremo a extremo** con el objetivo de tiempo de recuperación de 4 horas cumplido. Evidencia: acta del simulacro
- [ ] Llave de restauración custodiada fuera del servidor y probada
- [ ] Runbooks de `docs/runbooks/` revisados y ejecutados al menos una vez cada uno

### Cumplimiento

- [ ] Prueba de penetración externa realizada y hallazgos críticos y altos resueltos. Evidencia: reporte y plan de remediación
- [ ] Análisis dinámico con OWASP ZAP sin hallazgos de riesgo alto. Evidencia: reporte
- [ ] Lista ASVS nivel 2 completada con evidencia por requisito
- [ ] Integración de pasarela sin ningún dato de tarjeta tocando CONFIA. Evidencia: revisión de código y prueba de rechazo de campos de tarjeta
- [ ] Atestación de Cumplimiento del proveedor de pasarela archivada
- [ ] Documento de privacidad y retención aprobado. Ver `docs/08-datos-privacidad-y-retencion.md`
- [ ] Acuerdos de tratamiento de datos firmados con todos los encargados del tratamiento
- [ ] Evaluación de impacto sobre la protección de datos completada

---

## 19. Cadencia de pruebas de seguridad

| Actividad | Frecuencia | Alcance | Responsable | Evidencia |
|---|---|---|---|---|
| **Prueba de penetración externa** | **Antes de salir a producción** y luego **anual** | Ambos dominios, API administrativa y portal, autenticación, autorización a nivel de objeto y lógica de negocio financiera | Tercero independiente | Reporte con hallazgos clasificados y carta de remediación |
| Reprueba de hallazgos de la prueba de penetración | A los 30 días de cada prueba | Solo los hallazgos críticos y altos | El mismo tercero | Confirmación de cierre |
| **Revisión de permisos** | **Trimestral** | Todos los usuarios, sus roles y sus permisos efectivos. Bajas de personal. Cuentas sin uso en 90 días | Desarrollador con el propietario | Reporte "quién puede hacer qué" firmado |
| Revisión de segregación de funciones | Trimestral | Ningún usuario con ambos permisos de un par de la sección 5.3 | Desarrollador | Reporte de excepciones, que debe estar vacío |
| **Simulacro de restauración** | **Mensual**, una vez al año en un proveedor distinto de AWS | Restauración de un respaldo real, descargado del almacenamiento fuera de sitio y descifrado con la llave custodiada, en un entorno limpio, con verificación de integridad (conteo de registros, cuadre del libro mayor y cadena de auditoría) y medición del tiempo frente al objetivo de cuatro horas (`docs/01-arquitectura.md` sección 8, `docs/05-infraestructura-y-despliegue.md` sección 11). La ejecución anual fuera de AWS es el simulacro de salida de ADR-0014 | Desarrollador | Acta con tiempos medidos frente a los objetivos declarados |
| Ejercicio de recuperación ante desastre | Semestral, adicional al simulacro mensual y sin reemplazarlo | Reconstrucción del entorno completo desde la infraestructura como código y las imágenes por digest, con restauración del respaldo y prueba de humo | Desarrollador | Acta con tiempos medidos frente a los objetivos declarados |
| **Simulacro de incidente** | **Semestral** | Ejercicio de mesa sobre un escenario rotativo: ransomware, credencial comprometida, fuga de datos de menores, alteración de deuda | Desarrollador con el propietario | Acta con brechas de procedimiento detectadas |
| Análisis dinámico con OWASP ZAP | Nocturno en preproducción, y antes de cada versión | Línea base automatizada | Integración continua | Reporte archivado |
| Escaneo de dependencias y contenedores | Cada corrida de integración continua | Código e imágenes | Integración continua | Reporte de Trivy y osv-scanner |
| Escaneo de secretos sobre el historial completo | Semanal | Todo el repositorio | Integración continua | Reporte enviado aunque esté vacío |
| Verificación de la cadena de auditoría | Diaria (último mes), semanal (completa), horaria (contra ancla) | `shared_audit_log` | Trabajo programado | Métrica y alerta |
| Prueba de las puertas de calidad | Mensual | La rama con violaciones deliberadas debe ser rechazada por todas las puertas | Integración continua | Reporte de la corrida |
| Revisión de la lista ASVS | Al cierre de cada fase | Requisitos afectados por la fase | Desarrollador | `docs/seguridad/asvs-nivel-2.md` actualizado |
| Revisión de este documento | Semestral, o ante cambio arquitectónico | Completo | Desarrollador | Nueva versión con registro de cambios |

---

## 20. Documentos relacionados

| Documento | Relación |
|---|---|
| `docs/01-arquitectura.md` | Fuente de verdad. Secciones 5 y 6 sustentan el aislamiento y la inmutabilidad financiera |
| `docs/04-cumplimiento-fiscal-sar.md` | Controles fiscales, CAI y correlativos |
| `docs/05-infraestructura-y-despliegue.md` | Implementación concreta del endurecimiento y del despliegue |
| `docs/06-estrategia-de-testing.md` | Niveles de prueba y umbrales que sostienen las verificaciones de este documento |
| `docs/07-observabilidad-y-operaciones.md` | Logs, redacción, métricas, alertas y runbooks |
| `docs/08-datos-privacidad-y-retencion.md` | Datos de menores, base legal, retención y notificación de brechas |
| `docs/10-analisis-de-brechas.md` | Brechas B6, B9, B10, A7 y A9, que este documento resuelve |
| `docs/11-riesgos.md` | Registro de riesgos |
| `docs/runbooks/` | Procedimientos operativos de respuesta |
| `docs/adr/` | Decisiones de arquitectura con su justificación |

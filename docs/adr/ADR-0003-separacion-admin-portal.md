# ADR-0003: Separación entre el panel administrativo y el portal de encargados

- **Estado:** Aceptado
- **Fecha:** 2026-09-09
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `apps/api` (puntos de entrada administrativo, portal y trabajador del mismo artefacto Spring Boot), `apps/admin-web`, `apps/portal-web`, `modules/identity`, `modules/portal`, esquema de PostgreSQL y sus roles, políticas de seguridad a nivel de fila, `infra/nginx`, `infra/docker`.
- **Revisión:** 2026-09-14. Alineado con ADR-0013 (backend en Java con Spring Boot). La decisión no cambia; se actualizan las herramientas y los mecanismos de verificación. El contexto de sesión se unifica con `docs/03-seguridad.md` sección 6.2 (`app.institution_id`, `app.actor_id`, `app.actor_kind`) y la columna de institución se llama `institution_id`, como en ADR-0009.

## Contexto y problema

CONFIA tiene dos audiencias con riesgo radicalmente asimétrico:

| Audiencia | Ubicación | Privilegio | Volumen | Exposición |
|---|---|---|---|---|
| Personal administrativo | Red institucional | Alto: cobra, factura, anula, configura tarifas, gestiona usuarios | Decenas de usuarios | Controlable |
| Encargados de pago | Internet abierto, dispositivo propio, red desconocida | Mínimo: consulta su estado de cuenta, inicia un pago, solicita documentos | Cientos o miles de usuarios | Ninguna |

El requerimiento original del cliente plantea la separación como una necesidad, y el instinto es
correcto: **el portal no debe poder tocar la operación administrativa.** Lo que hay que decidir es
cómo se materializa esa separación.

Las fuerzas que actúan:

1. **Riesgo de exposición.** El portal es la superficie que un atacante encuentra primero. Una
   vulnerabilidad ahí no debe dar acceso a facturación, caja, usuarios ni bitácora de auditoría.
2. **Datos de menores de edad.** Un fallo de autorización que permita a un encargado ver el estado de
   cuenta de otro estudiante no es un defecto funcional: es una brecha de datos personales de un
   menor, con obligación de notificación y consecuencias legales.
3. **Una sola fuente de verdad del dinero.** El saldo que ve el encargado en el portal y el que ve el
   cajero en ventanilla deben ser el mismo dato, en el mismo instante. Si el encargado paga y el
   cajero cobra el mismo cargo con dos minutos de diferencia, el sistema debe detectarlo.
4. **Un solo desarrollador.** Cualquier solución que exija mantener dos implementaciones de la misma
   regla, o conciliar dos almacenes de dinero, es insostenible.
5. **El error de aplicación es inevitable.** Un desarrollador solo, sin par de revisión, escribirá
   tarde o temprano una consulta sin el filtro de encargado. La arquitectura debe hacer que ese error
   no se convierta automáticamente en una fuga de datos.

Si no se decide, el resultado por defecto es una sola aplicación con un `if` de rol en cada
controlador. Eso significa que la única barrera entre un encargado y la bitácora de auditoría es que
el programador nunca se equivoque.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Una sola verdad del dinero, sin conciliación entre almacenes | Muy alto | Dos bases de datos con dinero producen descuadre. El sistema existe para evitar descuadre. |
| Contención ante un compromiso total del componente público | Muy alto | Debe existir una respuesta concreta a "el portal fue comprometido, qué alcanza el atacante". |
| El aislamiento no depende de que el código sea correcto | Muy alto | Con un solo desarrollador, la defensa debe estar en el motor de datos, no en la disciplina. |
| Una sola implementación de cada regla de negocio | Muy alto | Dos copias de la regla de mora divergen. Es cuestión de tiempo. |
| Costo operativo y de infraestructura | Alto | Fase uno son dos hosts. |
| Reversibilidad y evolución a topología separada | Alto | El portal debe poder mudarse a su propio host sin rediseño. |
| Complejidad de la superficie de identidad | Medio | Dos dominios de identidad cuestan más que uno, pero valen lo que cuestan. |

## Opciones consideradas

### Opción A: Una sola aplicación con control por roles

Un proceso, una base de datos, un dominio de identidad, un solo origen web. La distinción entre
personal y encargado es un rol dentro de la misma tabla de usuarios. Cada endpoint declara qué roles
lo pueden invocar.

**Ventajas.**

- La opción más barata de construir y de operar: un proceso, un despliegue, un certificado, una
  configuración.
- Una sola verdad del dinero por construcción.
- Una sola implementación de cada regla.
- La superficie de identidad es mínima.

**Desventajas.**

- **El código de facturación, de caja, de usuarios y de auditoría está cargado en el mismo proceso
  que atiende internet abierto.** Una ejecución remota de código, una deserialización insegura o una
  vulnerabilidad en una dependencia dan acceso inmediato a todo.
- La separación entre encargados depende íntegramente de que cada consulta lleve el filtro correcto.
  Una sola consulta sin la cláusula de encargado expone estados de cuenta de menores.
- Un error en la declaración de roles de un endpoint (una anotación olvidada, un rol mal escrito)
  expone una operación administrativa a internet.
- Mismo origen web: una vulnerabilidad de inyección de scripts en el portal permite actuar contra
  la API administrativa con la sesión de un administrador que tenga ambas pestañas abiertas.
- La escalada de privilegio es un cambio de valor en una columna. Si el atacante logra escribir en
  la tabla de usuarios, se convierte en administrador.

Es la opción que la mayoría de sistemas de este tipo implementa, y es la razón por la que la mayoría
de esos sistemas tienen incidentes.

### Opción B: Dos aplicaciones con dos bases de datos completamente separadas y sincronización

Dos sistemas independientes. El administrativo con su base de datos; el portal con la suya, que
contiene una réplica de la información necesaria para consulta y para iniciar pagos. Un proceso de
sincronización mantiene ambos alineados.

**Ventajas.**

- Aislamiento máximo aparente: el atacante que compromete el portal solo alcanza la base de datos
  del portal.
- Escalado y disponibilidad independientes.
- Es la interpretación literal de "duplicar el sistema", que es lo que suele pedirse.

**Desventajas, y aquí está el punto central de este ADR.**

**Dos bases de datos con dinero producen conciliación permanente, y la conciliación permanente es
un trabajo a tiempo completo que un desarrollador solo no puede sostener.**

El argumento en detalle:

1. **El pago llega por el lado equivocado.** El encargado paga por la pasarela contra el portal. Ese
   pago debe asentar en el libro mayor, que vive en el sistema administrativo. Entre el momento en
   que el portal lo acepta y el momento en que el administrativo lo asienta hay una ventana. En esa
   ventana el cajero de ventanilla ve el cargo como pendiente y puede cobrarlo de nuevo. **El
   estudiante paga dos veces y el sistema no lo sabe.**
2. **La sincronización falla, y falla en silencio.** Toda replicación asíncrona falla alguna vez: la
   red cae, el consumidor muere a mitad de lote, un mensaje se procesa dos veces, un mensaje llega
   fuera de orden. Cada modo de fallo produce una divergencia distinta entre los dos almacenes. Cada
   divergencia hay que detectarla, diagnosticarla y corregirla a mano.
3. **La pregunta "cuál es el saldo real" deja de tener respuesta única.** Si el portal dice
   quinientos y el administrativo dice ochocientos, la respuesta correcta requiere reconstruir la
   historia de ambos lados y decidir cuál ganó. **Un padre que reclama con una captura de pantalla
   del portal tiene razón desde su punto de vista, y la institución no puede demostrar lo
   contrario.** Eso destruye la confianza en el sistema completo, que es literalmente su nombre.
4. **La corrección de una divergencia es una escritura manual sobre datos financieros**, y toda
   escritura manual sobre datos financieros es exactamente lo que la arquitectura prohíbe
   (`CLAUDE.md`, regla 5: nada financiero se borra ni se edita).
5. **El costo se paga todos los días, el beneficio es hipotético.** La conciliación entre almacenes
   consume tiempo cada semana, para siempre. El beneficio (contención ante un compromiso del portal)
   se puede obtener casi por completo con la opción C, sin duplicar el almacén de dinero.
6. **Duplicar el código duplica las reglas.** El cálculo de saldo, de mora y de aplicación de abonos
   tendría que existir en ambos lados. Dos implementaciones divergen. Cuando divergen, el portal
   muestra un número y ventanilla cobra otro, y el sistema pierde credibilidad ante el usuario final
   antes que ante el auditor.
7. **El aislamiento que promete es menor de lo que parece.** La base de datos del portal contiene
   estados de cuenta de menores, nombres, vínculos familiares e importes adeudados. Comprometerla ya
   es una brecha de datos personales que hay que notificar. Lo que la opción B evita respecto de la
   opción C es acceso a facturación y caja, y eso la opción C también lo evita, a un costo mucho
   menor.

La opción B intercambia un riesgo agudo y contenible (compromiso del portal) por un riesgo crónico y
sin final (descuadre entre dos almacenes de dinero mantenidos por una sola persona). **Para un
desarrollador solo, es un mal negocio.**

### Opción C: Un código fuente y una base de datos, con aislamiento por proceso, privilegio, identidad y origen

Un artefacto de compilación, una base de datos, desplegado como **dos procesos independientes** con
perfiles distintos, cada uno conectado con un **rol de PostgreSQL distinto** bajo **seguridad a nivel
de fila**, con **dominios de identidad disjuntos** y **orígenes web separados**.

**Ventajas.**

- Una sola verdad del dinero. Cero conciliación entre almacenes. El saldo que ve el encargado es el
  mismo registro que ve el cajero, en la misma transacción.
- Una sola implementación de cada regla de negocio.
- El proceso del portal **no tiene cargado en memoria** el código de facturación, caja, usuarios
  administrativos ni bitácora de auditoría. Esas rutas no existen en ese proceso.
- El aislamiento entre encargados lo aplica el motor de base de datos, no el código de aplicación.
  Un error de consulta no produce fuga.
- Un token del portal es criptográficamente inválido contra la API administrativa.
- El aislamiento de origen del navegador impide que una vulnerabilidad en el portal actúe contra el
  panel administrativo.
- Evoluciona sin rediseño: mover el portal a su propio host y a una réplica de lectura es un cambio
  de configuración, no de arquitectura.

**Desventajas.**

- La base de datos es un punto único de compromiso: un atacante que obtenga el rol administrativo
  alcanza todo. La mitigación es que el rol del portal no puede escalar a administrativo desde
  dentro de PostgreSQL, y que la base de datos nunca tiene puerto público.
- La seguridad a nivel de fila añade complejidad real: cada conexión debe establecer el contexto de
  sesión, y una política mal escrita puede bloquear operaciones legítimas o, peor, no filtrar nada.
  Exige pruebas dedicadas.
- Dos dominios de identidad significan dos flujos de registro, verificación, recuperación de
  contraseña y bloqueo. Más superficie que mantener.
- Dos procesos, dos configuraciones de nginx, dos certificados, dos conjuntos de variables de
  entorno. Más piezas operativas que la opción A.
- Un fallo en el artefacto compartido (una migración incorrecta, un fallo de arranque) afecta a
  ambos procesos.

## Decisión

**Se adopta la opción C: un solo código fuente y una sola base de datos, con separación real en
cuatro dimensiones: proceso y red, privilegio de base de datos, dominio de identidad, y origen web.**

La opción A se descarta porque su única barrera es la corrección del código de aplicación, y con un
desarrollador solo, sin par de revisión, esa barrera es insuficiente para datos de menores y para
dinero.

La opción B se descarta porque el aislamiento que ofrece por encima de la opción C es marginal
(facturación y caja, que la opción C también protege), mientras que su costo es un problema de
conciliación permanente entre dos almacenes de dinero. **El sistema existe para que las cuentas
cuadren. Una arquitectura que introduce una fuente estructural de descuadre contradice su propio
propósito.**

La opción C consigue el objetivo real del cliente, que es que el portal no pueda tocar la operación
administrativa, sin introducir una segunda verdad sobre el dinero.

### Primera dimensión: proceso y red

Un mismo artefacto de compilación, dos puntos de entrada distintos.

| Proceso | Punto de entrada | Módulos registrados | Exposición de red |
|---|---|---|---|
| `confia-api-admin` | Punto de entrada administrativo | Todos los módulos administrativos | No público. VPN o lista de direcciones institucionales, más MFA obligatoria. |
| `confia-api-portal` | Punto de entrada del portal | Únicamente `portal`, y de `identity` solo el subdominio de encargados | Internet abierto, tras cortafuegos de aplicación, con limitación de tasa agresiva. |

La diferencia no es de configuración ni de un filtro de rutas: **son contextos de aplicación de
Spring distintos, y cada punto de entrada declara de forma explícita qué módulos carga** (ADR-0013).
Si el proceso del portal fuera comprometido por completo, el atacante no encontraría registrado el
código de facturación, de caja ni de gestión de usuarios administrativos, porque esos controladores
nunca se registraron en ese proceso.

Los trabajos en segundo plano corren en un tercer proceso trabajador, con el perfil administrativo,
sin exposición HTTP. El mecanismo de trabajos está pendiente de ADR específico (ver ADR-0013).

Swagger UI y el endpoint del OpenAPI están deshabilitados en producción en los tres procesos, de
modo que el proceso público del portal no expone el mapa de la API (ADR-0013).

### Segunda dimensión: privilegio en la base de datos

Esta es la frontera que realmente importa, porque es la única que sobrevive a un error del
programador.

| Rol | Uso | Permisos |
|---|---|---|
| `confia_owner` | Solo migraciones, nunca la aplicación | Propietario del esquema. Credencial fuera de la aplicación, en el pipeline de despliegue. |
| `confia_admin_app` | Proceso `confia-api-admin` | `SELECT`, `INSERT`, `UPDATE` según tabla. **Sin `DELETE` sobre ninguna tabla financiera.** Sin `UPDATE` ni `DELETE` sobre la bitácora de auditoría ni sobre el libro mayor. |
| `confia_portal_app` | Proceso `confia-api-portal` | `SELECT` sobre la vista mínima de estado de cuenta, estudiantes vinculados y documentos propios. `INSERT` sobre intenciones de pago y solicitudes. **Sin acceso alguno** a `users`, `roles`, rangos CAI, `cashbox_*`, `audit_log`, ni a las tablas de asientos en escritura. |
| `confia_readonly` | Reportes y réplica de lectura | Solo `SELECT`. |

Ningún rol de aplicación es `SUPERUSER` ni tiene `BYPASSRLS`. Ninguno puede otorgarse permisos a sí
mismo. La revocación es explícita: se parte de `REVOKE ALL` sobre el esquema y se concede lo mínimo,
tabla por tabla.

**Seguridad a nivel de fila obligatoria.** Toda tabla que contenga datos de un encargado o de un
estudiante tiene `ENABLE ROW LEVEL SECURITY` y `FORCE ROW LEVEL SECURITY`. Las políticas filtran por
el contexto de sesión que la aplicación establece al tomar la conexión:

- `app.institution_id`: identificador de institución (ver ADR-0009).
- `app.actor_id`: identificador de quien consulta: el encargado autenticado en el proceso del
  portal, o el usuario administrativo en el proceso administrativo.
- `app.actor_kind`: tipo de actor (`staff`, `guardian` o `system`), para que una política del
  portal nunca acepte un identificador administrativo.

El detalle de cómo se establece el contexto está en `docs/03-seguridad.md`, sección 6.2.

El contexto se establece con `set_config(..., true)`, es decir con alcance de transacción, de modo
que una conexión devuelta al pool no arrastre el contexto del usuario anterior. Ese detalle no es
menor: un `set_config` con alcance de sesión sobre un pool de conexiones es una fuga de datos entre
usuarios esperando a ocurrir.

Consecuencia práctica: **un encargado no puede leer el estado de cuenta de otro estudiante aunque el
código de aplicación tenga un error, porque el motor de base de datos no devuelve esas filas.** Esa
es la diferencia entre confiar en que el programador no se equivoque y construir un sistema donde
equivocarse no basta para causar una brecha.

### Tercera dimensión: identidad

Dos dominios de identidad disjuntos, sin ninguna superposición.

| Aspecto | Personal administrativo | Encargados de pago |
|---|---|---|
| Almacén | Tabla `staff_users` | Tabla `guardian_users` |
| Emisor y audiencia del token | `aud: confia-admin` | `aud: confia-portal` |
| Clave de firma | Par de claves administrativo | Par de claves del portal, distinto |
| Cookie | Nombre y dominio administrativos, `Path` propio | Nombre y dominio del portal, `Path` propio |
| MFA | Obligatoria | Recomendada, obligatoria para operaciones sensibles |
| Registro | Alta por un administrador, nunca autoservicio | Autoservicio validado contra el vínculo estudiante y encargado, con verificación de correo |
| Bloqueo por intentos | Independiente | Independiente |

Un token emitido por el portal **falla la verificación de firma** contra la API administrativa. No es
que se rechace por rol: es que criptográficamente no valida. La escalada de privilegio por
manipulación de reclamaciones del token deja de ser un vector, porque no existe una clave común. Ver
ADR-0005.

### Cuarta dimensión: origen web

| Aplicación | Origen | Política de seguridad de contenido | CORS |
|---|---|---|---|
| Panel administrativo | `admin.<dominio>` | Estricta, sin `unsafe-inline`, con nonce | Lista blanca con únicamente el origen administrativo |
| Portal de encargados | `portal.<dominio>` | Estricta, sin `unsafe-inline`, con nonce, con `frame-ancestors 'none'` | Lista blanca con únicamente el origen del portal |

Orígenes distintos significan almacenamientos distintos, cookies que no se comparten y aislamiento de
origen aplicado por el navegador. Una vulnerabilidad de inyección de scripts en el portal no puede
leer la cookie administrativa ni emitir peticiones autenticadas contra la API administrativa.

### Topología física por fase

**Fase uno**, con presupuesto mínimo:

```
                 Internet
                    |
        [ Borde: WAF, TLS, protección DDoS ]
                    |
        +-----------+------------+
        |                        |
   subdominio admin        subdominio portal
   (acceso restringido      (público, límite de
    por IP y MFA)            tasa estricto)
        |                        |
   +----+------------------------+----+
   |        Host A (nginx)            |
   |  +------------+  +------------+  |
   |  | api-admin  |  | api-portal |  |  <- mismo artefacto, distinto perfil
   |  +-----+------+  +------+-----+  |
   +--------+----------------+--------+
            |  red privada   |
   +--------+----------------+--------+
   |  Host B (sin IP pública)         |
   |  PostgreSQL, Redis, almacenamiento|
   |  rol administrativo / rol portal  |
   +----------------------------------+
```

**Fase dos**, cuando el portal tenga tráfico real: la API del portal se muda a su propio host, se
agrega una réplica de lectura de PostgreSQL para sus consultas, y el nodo de escritura queda
reservado para la operación administrativa y los webhooks de la pasarela. Esto es un cambio de
configuración, no de arquitectura, porque el portal ya está aislado por proceso, rol e identidad.

**Regla permanente:** PostgreSQL nunca tiene puerto público. Ni en desarrollo compartido, ni de
forma temporal para una migración, ni por diez minutos.

## Consecuencias

**Positivas:**

- Una sola verdad sobre el dinero. Cero conciliación entre almacenes. El pago por pasarela y el
  cobro en ventanilla compiten sobre el mismo registro y el control de concurrencia decide, en vez
  de que ambos tengan éxito en almacenes distintos. Ver ADR-0010.
- Una sola implementación de cada regla de negocio, probada una sola vez.
- Respuesta concreta y verificable a "el portal fue comprometido": el atacante alcanza lecturas
  filtradas por seguridad a nivel de fila y escrituras sobre intenciones de pago y solicitudes. No
  alcanza facturación, caja, usuarios, rangos CAI ni bitácora de auditoría.
- El aislamiento entre encargados no depende de que ninguna consulta esté bien escrita.
- Un token del portal no vale nada contra la API administrativa.
- Evolución a topología separada sin rediseño.

**Negativas y costos aceptados:**

- **La base de datos sigue siendo un punto único de compromiso.** Si un atacante obtiene la
  credencial de `confia_admin_app` o acceso al host B, alcanza todo. Se acepta porque la alternativa
  (opción B) sustituye este riesgo por un problema de descuadre permanente, y porque el host B no
  tiene IP pública ni puerto expuesto.
- La seguridad a nivel de fila añade complejidad de desarrollo permanente: cada conexión debe
  establecer contexto, cada tabla nueva necesita política, y una política ausente pasa desapercibida
  hasta que alguien la busca. Se mitiga con verificación automatizada de cobertura de políticas.
- Dos dominios de identidad significan dos flujos completos de registro, verificación, recuperación
  y bloqueo, y dos conjuntos de claves que rotar.
- Mayor complejidad operativa: dos procesos, dos configuraciones de borde, dos certificados, dos
  conjuntos de variables de entorno, dos paneles de métricas.
- Un fallo en el artefacto compartido o en una migración afecta a ambos procesos simultáneamente.
- El desarrollo local necesita levantar dos procesos para probar un flujo completo de extremo a
  extremo.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Una tabla nueva se crea sin política de seguridad a nivel de fila | Prueba de integración que consulta `pg_class` y falla si alguna tabla con `institution_id` o `guardian_id` no tiene `relrowsecurity` y `relforcerowsecurity` activos. |
| El contexto de sesión no se establece o se filtra entre peticiones por el pool | El contexto se establece con alcance de transacción. Prueba de integración que ejecuta dos peticiones de encargados distintos sobre la misma conexión del pool y afirma que la segunda no ve datos de la primera. |
| Un controlador administrativo se registra por error en el perfil del portal | Prueba de arranque que levanta el contexto del punto de entrada del portal y afirma que el mapa de rutas no contiene ninguna ruta de `invoicing`, `cashbox`, `reconciliation` ni de `identity` administrativo. La lista de rutas del portal es una instantánea aprobada. |
| El rol del portal recibe un permiso de más en una migración | Prueba que consulta `information_schema.role_table_grants` para `confia_portal_app` y la compara contra una matriz de permisos aprobada. Cualquier permiso no declarado falla. |
| Confusión de claves de firma entre dominios de identidad | Prueba que emite un token con la clave del portal y afirma que la verificación administrativa lo rechaza por firma inválida, y viceversa. Las claves se cargan desde variables de entorno distintas, verificadas al arranque. |
| Un encargado enumera estudiantes por identificador secuencial | Identificadores opacos no secuenciales en las rutas del portal, más seguridad a nivel de fila como defensa final. Prueba que intenta acceder al estado de cuenta de un estudiante no vinculado y espera respuesta de no encontrado. |
| Los orígenes CORS se relajan durante una depuración y quedan así | La configuración de CORS se lee de variable de entorno con lista blanca explícita. Un valor comodín provoca fallo de arranque en producción. |

## Cumplimiento y verificación

Todas estas verificaciones corren en integración continua contra PostgreSQL real mediante
Testcontainers. Ver ADR-0008.

1. **Aislamiento del grafo de módulos.** Prueba que arranca la aplicación con el perfil del portal e
   inspecciona el mapa de rutas registradas, comparándolo contra una instantánea aprobada. Cualquier
   ruta nueva en el portal exige actualizar la instantánea de forma explícita, lo cual la hace
   visible en la revisión.
2. **Regla de dependencia del módulo portal.** Una regla de ArchUnit prohíbe que el módulo `portal`
   dependa de los módulos `invoicing`, `cashbox` y `reconciliation` o del dominio administrativo de
   `identity`, y la verificación de Spring Modulith impide que acceda a los internos de cualquier
   otro módulo. Un fallo rompe la construcción. Ver ADR-0002.
3. **Matriz de permisos de roles.** Prueba de integración que consulta
   `information_schema.role_table_grants` y `information_schema.role_routine_grants` para cada rol de
   aplicación y la compara contra la matriz declarada en el repositorio. Un permiso concedido de más
   o de menos rompe la construcción.
4. **Cobertura de seguridad a nivel de fila.** Prueba que recorre `pg_class` y falla si existe una
   tabla con columna `institution_id` o `guardian_id` sin `relrowsecurity` y `relforcerowsecurity`. Esto
   convierte el olvido de una política en un fallo de construcción y no en un hallazgo de auditoría.
5. **Prueba de fuga entre encargados.** Escenario obligatorio para cada tabla expuesta al portal: se
   crean dos encargados con estudiantes distintos, se establece el contexto del primero y se ejecuta
   una consulta **deliberadamente sin filtro de aplicación**. La prueba afirma que solo se devuelven
   las filas del primero. Esta prueba verifica el motor, no el código, que es exactamente lo que se
   quiere demostrar.
6. **Prueba de reutilización de conexión del pool.** Dos peticiones consecutivas de encargados
   distintos forzadas sobre la misma conexión física. La segunda no debe ver datos de la primera.
7. **Prueba de rechazo cruzado de tokens.** Un token con `aud: confia-portal` firmado con la clave del
   portal se presenta a un endpoint administrativo: la respuesta debe ser no autorizado por firma
   inválida, no por rol insuficiente. Y en sentido inverso.
8. **Verificación de arranque.** Cada proceso valida al arrancar que su cadena de conexión usa el rol
   esperado (`SELECT current_user`) y aborta si no coincide. Un despliegue que conecte el portal con
   el rol administrativo no llega a atender la primera petición.
9. **Cabeceras de seguridad.** Prueba de extremo a extremo con Playwright que verifica en ambos
   orígenes la presencia y el valor de `Content-Security-Policy`, `Strict-Transport-Security`,
   `X-Content-Type-Options`, `Referrer-Policy` y los atributos de cookie `HttpOnly`, `Secure` y
   `SameSite`.
10. **Exposición de red.** Verificación en el pipeline de despliegue que confirma que el puerto de
    PostgreSQL no es alcanzable desde fuera de la red privada, y que el proceso administrativo no
    responde desde una dirección fuera de la lista permitida.

## Referencias

- `docs/01-arquitectura.md`, sección 5
- `docs/03-seguridad.md`
- `docs/10-analisis-de-brechas.md`, brecha A8 y brecha B9
- ADR-0002: monolito modular
- ADR-0005: autenticación y gestión de sesiones
- ADR-0009: multitenencia
- ADR-0010: idempotencia y concurrencia financiera
- ADR-0013: backend en Java con Spring Boot

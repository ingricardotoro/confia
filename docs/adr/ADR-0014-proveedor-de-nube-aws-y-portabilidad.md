# ADR-0014: AWS como proveedor de nube con política de portabilidad

- **Estado:** Aceptado
- **Fecha:** 2026-09-14
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Topología de despliegue de `docs/05-infraestructura-y-despliegue.md`, respaldos, almacenamiento de objetos, correo transaccional, borde HTTP, infraestructura como código en `infra/`, adaptadores de `infrastructure` de `apps/api`, integración y entrega continuas.

## Contexto y problema

La arquitectura define qué se ejecuta (tres procesos de la misma imagen, PostgreSQL, Redis,
almacenamiento compatible con S3 y un borde HTTP) y cómo se empaqueta (ADR-0012), pero no dónde.
`docs/05-infraestructura-y-despliegue.md` asumió, para estimar costos, dos servidores virtuales de
un proveedor económico.

El propietario del producto quiere usar **Amazon Web Services** como proveedor de nube, con una
condición explícita: **no depender exclusivamente de AWS**. Si en el futuro conviene otro
proveedor, o si la institución decide operar el sistema con infraestructura propia tras la
transferencia tecnológica, el cambio debe ser posible sin reescribir la aplicación.

Las fuerzas:

- **AWS ofrece servicios gestionados que reducen carga operativa**, en particular PostgreSQL
  gestionado, que `docs/01-arquitectura.md` sección 9 declara preferible a administrarlo en
  contenedor propio.
- **AWS también ofrece servicios cuyo modelo de programación solo existe en AWS.** Cada uno que
  entra al código convierte una migración de infraestructura en una reescritura de la aplicación.
  El acoplamiento no llega de una decisión grande, sino de muchas pequeñas tomadas por comodidad.
- **Un solo desarrollador.** La portabilidad no puede depender de disciplina manual. Tiene que
  verificarse en integración continua.
- **Transferencia tecnológica.** ADR-0012 exige que el departamento de sistemas de la institución
  pueda operar el sistema sin depender de un panel de control de terceros.
- **Datos de menores fuera de Honduras.** AWS no tiene región en Honduras. `docs/08` declara que la
  transferencia internacional de datos personales de menores requiere confirmación legal antes de
  cerrar la elección de proveedor.

Si no se decide una política explícita, el sistema termina atado a AWS por acumulación: una cola
aquí, una función allá, un servicio de identidad porque ahorraba una semana.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Posibilidad real de cambiar de proveedor | Muy alto | Requisito explícito del propietario del producto. |
| Continuidad ante la pérdida del proveedor o de la cuenta | Muy alto | Un sistema con dinero real no puede perder los datos si la cuenta se compromete o se suspende. |
| Carga operativa para un solo desarrollador | Muy alto | Delegar respaldo y parcheo de la base de datos vale más que casi cualquier otra comodidad. |
| Transferencia tecnológica | Alto | La institución debe poder operar el sistema en otro lugar si lo decide. |
| Costo mensual | Alto | El presupuesto de `docs/05` sección 12 es modesto. |
| Cercanía de la región | Medio | Latencia y cantidad de jurisdicciones implicadas (`docs/08`). |
| Aprovechamiento de servicios gestionados | Medio | Se aprovechan cuando no comprometen la portabilidad. |

## Opciones consideradas

### Opción A: AWS con política de portabilidad por protocolo abierto

Se usan servicios de AWS que implementan un protocolo o una interfaz estándar (PostgreSQL, API de
S3, SMTP, máquinas virtuales con Docker). Se prohíben los servicios que imponen un modelo de
programación propietario. Los respaldos viven fuera de AWS y el DNS y el borde se gestionan fuera de
AWS.

**Ventajas.**

- PostgreSQL gestionado con respaldo automático, parcheo y restauración a un punto en el tiempo, sin
  cambiar una línea de la aplicación.
- La aplicación no sabe que corre en AWS: se conecta a un PostgreSQL, a un almacenamiento S3 y a un
  servidor SMTP. En otro proveedor cambian las variables de entorno.
- La topología de ADR-0012 (Docker Compose sobre máquinas virtuales) se conserva intacta.
- AWS no cobra la transferencia de salida de datos cuando se migra a otro proveedor, previa
  solicitud a soporte, lo cual reduce el costo de un eventual cambio.

**Desventajas.**

- Se renuncia a servicios que ahorrarían trabajo hoy: colas, funciones, identidad y bases de datos
  propietarias.
- Costo mayor que un servidor virtual económico, sobre todo por la base de datos gestionada.
- La infraestructura como código sigue siendo específica del proveedor. Migrar implica reescribir
  la capa de infraestructura, aunque no la aplicación.

### Opción B: AWS nativo

Contenedores gestionados (ECS con Fargate o App Runner), Aurora, SQS, Lambda, Cognito, Secrets
Manager desde el código y CloudFormation o CDK.

**Ventajas.** Máxima delegación operativa y escalado automático. Menos servidores que parchear.

**Desventajas.** Contradice el requisito central del propietario del producto: cada servicio
propietario entra al código o al modelo de despliegue, y salir de AWS se convierte en una
reescritura. Contradice también ADR-0012 (Docker Compose como unidad de despliegue) y el objetivo de
transferencia tecnológica. Para el volumen de CONFIA, el escalado automático no tiene una necesidad
real que lo justifique.

### Opción C: Servidor virtual económico, sin AWS

La suposición original de `docs/05`: dos servidores virtuales de un proveedor de gama económica,
PostgreSQL en contenedor.

**Ventajas.** Es la opción más barata y la más portable.

**Desventajas.** PostgreSQL queda autogestionado, con respaldo, parcheo y actualización mayor a
cargo del desarrollador único, que es exactamente lo que `docs/01-arquitectura.md` sección 9 sugiere
delegar. Menor oferta de servicios gestionados, de controles de identidad y acceso granulares y de
regiones cercanas.

**Es una opción legítima.** Si el costo de la opción A resulta inasumible tras la estimación
obligatoria de este ADR, la opción C sigue siendo viable sin cambios en la aplicación.

### Opción D: Multinube activa

Desplegar simultáneamente en dos proveedores.

**Desventajas.** Duplica la operación, la red y la conciliación de datos. Para un solo
desarrollador es inviable. La portabilidad que se busca es la **capacidad** de cambiar, no operar
en dos lugares a la vez.

## Decisión

**Se adopta la opción A: AWS como proveedor de nube, bajo una política de portabilidad que permite
únicamente servicios con protocolo o interfaz estándar dentro de la aplicación, mantiene los
respaldos, el DNS y el borde fuera de AWS, y se verifica en integración continua.**

El factor determinante es la combinación de los dos factores de mayor peso: **la posibilidad real
de cambiar de proveedor y la carga operativa del desarrollador único.** La opción A delega la
operación de PostgreSQL sin atar la aplicación a AWS. La opción B resuelve la carga operativa pero
destruye la portabilidad. La opción C resuelve la portabilidad pero no la carga operativa.

### Clasificación de servicios

**Nivel 1. Permitidos, porque implementan un estándar.** Pueden usarse libremente.

| Necesidad | Servicio | Estándar que implementa | Sustituto en otro proveedor |
|---|---|---|---|
| Cómputo | EC2 con Docker Compose | Máquina virtual Linux | Cualquier servidor virtual o físico |
| Base de datos | RDS for PostgreSQL | Protocolo y SQL de PostgreSQL | PostgreSQL gestionado de otro proveedor o en contenedor |
| Archivos | S3 | API de S3 | Backblaze B2, Cloudflare R2, MinIO |
| Correo transaccional | SES por SMTP | SMTP | Cualquier proveedor SMTP |
| Discos | EBS | Volumen de bloque | Volumen de bloque del otro proveedor |

**Nivel 2. Permitidos solo en la operación, nunca en el código ni en la imagen.** Se reemplazan
por equivalentes al migrar, sin tocar la aplicación.

- Red y control de acceso: VPC, grupos de seguridad, IAM y roles de instancia.
- Acceso administrativo a los servidores: Session Manager, que evita exponer el puerto SSH.
- Cifrado en reposo de discos y base de datos con llaves gestionadas por AWS.
- Alertas de presupuesto de AWS Budgets.
- Estado de la infraestructura como código en un bucket de S3 cifrado y versionado.

**Nivel 3. Prohibidos sin un ADR nuevo que justifique la excepción.**

- Aurora, DynamoDB y cualquier base de datos propietaria.
- Lambda, SQS, SNS, EventBridge y Step Functions en cualquier flujo de la aplicación. En particular,
  el ADR pendiente de trabajos en segundo plano (ADR-0013) no puede elegir SQS.
- Cognito o cualquier servicio de identidad del proveedor. La identidad es la de ADR-0005.
- ECS, EKS, Fargate, App Runner y Elastic Beanstalk como modelo de despliegue.
- El SDK de Secrets Manager, Parameter Store o X-Ray dentro del código de la aplicación.
- CloudFormation y CDK.

### Fuera de AWS por diseño

- **DNS, borde, WAF, TLS y protección DDoS en Cloudflare.** Con el DNS fuera de AWS, migrar el
  tráfico es cambiar un registro. `docs/05` sección 12 ya contempla Cloudflare como borde.
- **Respaldos fuera de sitio en un proveedor distinto al de cómputo**, como ya exige `docs/05`
  sección 11, por ejemplo Backblaze B2 o Cloudflare R2. La llave privada de descifrado no vive en
  AWS ni en el proveedor del respaldo.
- **Registro de imágenes en GitHub Container Registry**, como ya está definido.
- **Secretos en los entornos protegidos de GitHub Actions**, inyectados como variables de entorno en
  el despliegue, como ya está definido.
- **Observabilidad con OpenTelemetry, Prometheus y Grafana**, sin CloudWatch como única fuente.

### Topología en AWS

La topología de `docs/05` sección 2 se simplifica, porque la base de datos gestionada reemplaza al
host de datos.

**Fase uno (F0 a F7):**

- Una instancia EC2 en subred pública ejecuta nginx de borde, `api-admin`, `api-portal` (sin
  exponer hasta F8), `worker`, las dos aplicaciones web y Redis, con Docker Compose.
- El grupo de seguridad de esa instancia solo admite tráfico HTTPS desde los rangos de Cloudflare.
  No hay puerto SSH abierto: el acceso administrativo es por Session Manager.
- RDS for PostgreSQL en subred privada, sin acceso público, con conexiones admitidas solo desde el
  grupo de seguridad de la instancia de aplicación. Se preserva la regla permanente: PostgreSQL
  nunca tiene puerto público.
- S3 reemplaza a MinIO en preproducción y producción. MinIO sigue en desarrollo local.
- La instancia accede a S3 con un rol de instancia. En otro proveedor, el mismo cliente S3 lee
  credenciales de variables de entorno sin cambio de código.
- Sin NAT Gateway: la instancia de aplicación está en subred pública y RDS no necesita salida a
  internet.

**Fase dos (F8 en adelante):** `api-portal` pasa a una segunda instancia EC2 y se agrega una réplica
de lectura de RDS para sus consultas, conforme a `docs/01-arquitectura.md` sección 5.

### Región

**Región elegida por el propietario del producto: Norte de Virginia (`us-east-1`).** Es entre 4 % y
6 % más barata que México en los servicios con precio regional, y todos los servicios de este ADR
están disponibles en ella, incluido SES, de modo que todos los datos quedan en una sola
jurisdicción.

**Alternativa: México (`mx-central-1`)**, más cercana y con EC2 Graviton y RDS for PostgreSQL. Si
se eligiera, SES no está disponible en esa región y el correo tendría que enviarse por SMTP a SES
en otra región, lo cual agrega una jurisdicción para el contenido de los correos.

La elección final queda **condicionada a la confirmación legal de la transferencia internacional de
datos personales de menores** que exige `docs/08`. Ningún dato real se carga en AWS antes de esa
confirmación y de firmar el acuerdo de tratamiento de datos que ofrece el proveedor.

### Plan de salida

Migrar a otro proveedor consiste en:

1. Aprovisionar máquinas virtuales y un PostgreSQL en el proveedor destino con la infraestructura
   como código correspondiente.
2. Restaurar el último respaldo fuera de sitio, o replicar la base con replicación lógica para
   reducir la ventana de corte.
3. Copiar los objetos de S3 al almacenamiento destino con una herramienta compatible con S3.
4. Desplegar las mismas imágenes por digest con las variables de entorno del destino.
5. Cambiar los registros DNS en Cloudflare.

La aplicación no cambia. Lo que cambia es la infraestructura como código y la configuración.

## Estimación de costos

Precios unitarios obtenidos el 2026-09-14 de la lista pública de precios de AWS (archivos de
precios publicados el 10 y 11 de septiembre de 2026) y de las páginas oficiales de Backblaze y
Cloudflare. Importes en dólares estadounidenses por mes, con 730 horas por mes, **sin impuestos**.
Son estimaciones para presupuestar, no una cotización.

### Supuestos de tamaño (fase uno, F0 a F7)

| Componente | Producción | Preproducción |
|---|---|---|
| Servidor de aplicación | 1 × `t4g.large` (2 vCPU, 8 GiB) | 1 × `t4g.medium` (2 vCPU, 4 GiB) |
| Disco del servidor | 30 GB gp3 | 30 GB gp3 |
| Base de datos | RDS PostgreSQL `db.t4g.small`, una zona | `db.t4g.micro`, una zona |
| Disco de la base | 20 GB gp3 (mínimo de RDS) | 20 GB gp3 |
| Archivos en S3 | 10 GB, 50.000 escrituras y 200.000 lecturas | Mínimo |
| Correo (SES) | 5.000 correos | Mínimo |
| Transferencia de salida | Menos de 100 GB, dentro de la franja gratuita | Mínima |

El servidor de producción necesita 8 GiB porque los límites de memoria de
`docs/05-infraestructura-y-despliegue.md` suman unos 3,5 GiB entre las tres JVM, las dos
aplicaciones web y Redis, más el sistema operativo. El respaldo de RDS no tiene costo adicional
mientras no supere el almacenamiento aprovisionado de la base.

### Producción

| Concepto | México | N. Virginia |
|---|---|---|
| EC2 `t4g.large` | 51,54 | 49,06 |
| Disco del servidor (30 GB) | 2,52 | 2,40 |
| Dirección IPv4 pública | 3,65 | 3,65 |
| RDS `db.t4g.small` | 24,82 | 23,36 |
| Disco de la base (20 GB) | 2,42 | 2,30 |
| S3 | 0,59 | 0,56 |
| SES | 0,50 | 0,50 |
| **Total, pago por uso** | **86,04** | **81,83** |
| **Total con compromiso de un año** | **61,00** | **57,15** |

El compromiso de un año, sin pago adelantado, combina un Savings Plan de instancias EC2 para el
servidor y una instancia reservada de RDS para la base. Ahorra unos 25 dólares al mes y obliga a
pagar el año completo aunque se deje de usar.

### Preproducción

| Modalidad | México | N. Virginia |
|---|---|---|
| Encendida todo el mes | 46,87 | 44,66 |
| Encendida solo en horario laboral (12 horas, 22 días) | 20,17 | 19,21 |

Apagar preproducción fuera de horario requiere programar el apagado y encendido del servidor y de
la base de datos. Los discos se siguen cobrando aunque estén apagados.

### Fuera de AWS

| Concepto | Costo mensual |
|---|---|
| Respaldos cifrados en Backblaze B2 (unos 27 GB, 10 GB gratuitos) | Menos de 1 dólar |
| Cloudflare, plan gratuito | 0 |
| Cloudflare Pro, opcional para reglas de borde avanzadas | 20 (pago anual) o 25 (pago mensual) |

### Resumen por fase

| Escenario | México | N. Virginia |
|---|---|---|
| F0 a F7, mínimo (compromiso de un año y preproducción en horario laboral) | 81 | 76 |
| F0 a F7, máximo (pago por uso y preproducción siempre encendida) | 133 | 126 |
| Incremento de fase dos (segundo servidor para el portal y réplica de lectura), pago por uso | +85 | +81 |

La estimación de `docs/05` sección 12 para F0 a F5 era de 40 a 90 dólares con servidores virtuales
económicos. AWS queda en la parte alta de ese rango solo con compromiso de un año y preproducción
en horario laboral; sin esas medidas lo supera en unos 40 dólares al mes.

### Comparación con Lightsail

Lightsail es la oferta de precio fijo de AWS. En `us-east-1`, un servidor de 8 GB (44 dólares) más
una base PostgreSQL gestionada de 2 GB (30 dólares) suman 74 dólares al mes para producción: menos
que EC2 con RDS en pago por uso, pero más que con compromiso de un año. **Lightsail no está
disponible en `mx-central-1`** y no ofrece el control de red de la topología de este ADR, por lo que
no se adopta.

### Qué no incluye

- Impuestos que agregue la facturación de AWS según el país de la cuenta.
- Dominio, plan de soporte de AWS y Cloudflare Pro.
- Crecimiento de datos por encima de los supuestos, ni transferencia de salida por encima de
  100 GB al mes (0,09 dólares por GB).
- El costo humano del desarrollador.

## Consecuencias

**Positivas:**

- PostgreSQL gestionado con respaldo automático, parcheo y restauración a un punto en el tiempo, que
  era la recomendación de la arquitectura para un desarrollador solo.
- La aplicación queda independiente del proveedor: PostgreSQL, API de S3 y SMTP.
- Topología más simple que la de `docs/05`: desaparece el host de datos y el PostgreSQL en
  contenedor en producción.
- Sin puertos SSH ni de base de datos expuestos.
- La pérdida de la cuenta de AWS no implica pérdida de datos, porque los respaldos y la llave de
  descifrado viven fuera.
- Salir de AWS es un procedimiento documentado y ensayado, no una reescritura.

**Negativas y costos aceptados:**

- **Costo mayor que la estimación de `docs/05` sección 12.** Según la estimación de este ADR, la
  fase uno cuesta entre 76 y 133 dólares al mes, frente a 40 a 90 con servidores virtuales
  económicos. Para quedar en la parte baja hay que comprometer un año de uso y apagar
  preproducción fuera de horario.
- **Límite del objetivo de punto de recuperación ante la pérdida total de AWS.** RDS no permite
  enviar el archivado continuo de WAL a otro proveedor. El objetivo de quince minutos se cumple con
  la restauración a un punto en el tiempo de RDS ante fallos normales. Ante la pérdida total de la
  cuenta, el punto de recuperación es el último respaldo fuera de sitio. Se acepta y se mitiga con
  respaldos fuera de sitio más frecuentes que el nocturno si el propietario del producto lo exige.
- Se renuncia a colas, funciones e identidad gestionadas de AWS.
- Cloudflare se convierte en un segundo proveedor del que se depende para el borde. Su dependencia
  es baja porque el DNS se exporta y las reglas de borde se pueden recrear.
- SES inicia en modo de pruebas y requiere solicitar acceso de producción con anticipación.
- `docs/05`, ADR-0012 y la estimación de costos deben actualizarse para reflejar esta topología.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Un servicio de nivel 3 entra al código por comodidad | Regla de construcción que solo admite los artefactos del SDK de AWS necesarios para S3, y regla de ArchUnit que confina ese SDK a paquetes `infrastructure`. |
| Un identificador de AWS queda fijado en el código | Verificación en integración continua que falla si aparece `arn:aws:` o `amazonaws.com` fuera de `infra/`. |
| La compatibilidad con S3 se rompe sin que nadie lo note | Las pruebas de integración usan MinIO con Testcontainers: si el adaptador funciona contra MinIO, funciona contra cualquier almacenamiento compatible con S3. |
| Compromiso o suspensión de la cuenta de AWS | Respaldos cifrados en otro proveedor, llave de descifrado fuera de ambos, credenciales raíz con MFA y sin uso diario. |
| El costo mensual se dispara | Alertas de presupuesto con umbrales declarados y revisión mensual del costo junto con el registro de riesgos. |
| El plan de salida no funciona cuando se necesita | Simulacro de salida anual en un proveedor distinto de AWS (ver cumplimiento). |
| Transferencia internacional de datos sin base legal | Ningún dato real en AWS antes de la confirmación legal de `docs/08` y del acuerdo de tratamiento firmado. |

## Cumplimiento y verificación

1. **SDK de AWS acotado.** `maven-enforcer-plugin` con `bannedDependencies` prohíbe todo artefacto
   `software.amazon.awssdk` salvo los necesarios para el cliente de S3. Agregar otro exige un ADR.
2. **SDK de AWS confinado.** Regla de ArchUnit: ninguna clase fuera de paquetes `infrastructure`
   importa `software.amazon.awssdk`.
3. **Sin identificadores de AWS en el código.** Verificación en integración continua que falla si
   aparece `arn:aws:` o `amazonaws.com` en `apps/` o en archivos de configuración de la aplicación.
4. **Configuración solo por variables de entorno.** El punto de acceso de S3, el servidor SMTP y la
   conexión a PostgreSQL se configuran por variables de entorno, verificado por la prueba de arranque
   de cada perfil.
5. **Compatibilidad con S3 probada en cada construcción** contra MinIO en Testcontainers, y
   compatibilidad con SMTP contra el servidor de correo de prueba.
6. **Infraestructura como código con OpenTofu**, en `infra/tofu/`, con `tofu validate` y `tofu plan`
   en integración continua. La construcción falla si aparecen plantillas de CloudFormation o
   proyectos de CDK.
7. **Simulacro de salida anual.** Una vez al año, el simulacro de restauración de
   `docs/05` sección 11 se ejecuta en un proveedor distinto de AWS, a partir del respaldo fuera de
   sitio y de las mismas imágenes por digest, con prueba de humo y acta firmada. Si falla, se
   registra como riesgo de exposición alta en `docs/11-riesgos.md`.
8. **Revisión humana de nivel 3.** Toda solicitud de uso de un servicio de nivel 3 se revisa contra
   este ADR. Es revisión humana y se declara así, porque los niveles 2 y 3 de la infraestructura no
   se pueden verificar por completo de forma automatizada.

## Referencias

- `docs/01-arquitectura.md`, secciones 5, 8 y 9
- `docs/05-infraestructura-y-despliegue.md`, secciones 2, 6, 7, 11 y 12
- `docs/08-datos-privacidad-y-retencion.md`, proveedores y transferencia internacional
- `docs/11-riesgos.md`, riesgos R01, R04, R06 y R12
- ADR-0003: separación entre administración y portal
- ADR-0005: autenticación y gestión de sesiones
- ADR-0012: contenerización
- ADR-0013: backend en Java con Spring Boot
- [AWS Mexico (Central) Region](https://aws.amazon.com/blogs/aws/now-open-aws-mexico-central-region)
- [Eliminación del cobro de transferencia al salir de AWS](https://www.networkworld.com/article/1311925/aws-removes-transfer-fees-for-customers-leaving-with-their-data.html)

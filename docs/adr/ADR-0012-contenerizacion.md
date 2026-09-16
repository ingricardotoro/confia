# ADR-0012: Contenerización total del sistema en los tres entornos

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `infra/docker/`, `infra/nginx/`, `infra/scripts/`, `docker-compose.yml`,
  `docker-compose.prod.yml`, pipeline de integración y entrega continuas
  (`.github/workflows/ci.yml`, `.github/workflows/cd.yml`), los tres procesos de aplicación
  (`confia-api-admin`, `confia-api-portal`, `confia-worker`), y los servicios de datos
  (PostgreSQL, Redis, MinIO).
- **Revisión:** 2026-09-14. Alineado con ADR-0013 (backend en Java con Spring Boot). La decisión no cambia; el runtime de la imagen de aplicación pasa a ser una JVM de Java 25 en lugar de Node.js, y el consumo de memoria de los tres procesos se mide en F0.

## Contexto y problema

CONFIA es un sistema financiero desarrollado y operado por **una sola persona**, que debe
sostenerse en producción, sobrevivir a la pérdida de un servidor y, eventualmente, transferirse
al departamento de sistemas de una institución educativa que no participó en su construcción.
Ninguna de esas tres exigencias es negociable: son restricciones declaradas en
`docs/01-arquitectura.md`, sección 1.

La pregunta que este ADR resuelve es cómo se empaqueta y se ejecuta el sistema en local,
preproducción y producción, de forma que:

1. El entorno de desarrollo se reproduzca de manera idéntica en cualquier máquina, sin una
   lista de instalación manual que envejece mal.
2. Si el servidor de producción desaparece por completo (falla de hardware, error humano,
   incidente del proveedor), la reconstrucción sea un procedimiento ejecutable, no un ejercicio
   de arqueología sobre configuración que solo el desarrollador recuerda.
3. La imagen que pasó las pruebas en preproducción sea, por construcción, la misma que corre en
   producción, eliminando la categoría de fallo "funciona en mi máquina, no en el servidor".
4. El día en que el sistema se entregue al departamento de sistemas de la institución, esa
   entrega sea "levantar los contenedores y restaurar el respaldo", no una sesión de traspaso
   de conocimiento tácito que depende de que el desarrollador original siga disponible.

Si no se decide una estrategia explícita, cada componente del sistema (API, procesos de
trabajo en segundo plano, dos aplicaciones web, base de datos, caché, almacenamiento de
objetos, borde HTTP) termina desplegándose de una forma distinta, decidida de manera ad hoc
en el momento de instalarlo, y la reproducibilidad se pierde por acumulación de excepciones.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Reproducibilidad del entorno completo | Muy alto | Un solo desarrollador no puede sostener una lista de instalación manual sincronizada entre su máquina, preproducción y producción |
| Recuperación ante desastre | Muy alto | La pérdida del servidor no puede depender de la memoria del desarrollador. Debe ser un procedimiento ejecutable |
| Transferencia tecnológica futura | Muy alto | El departamento de sistemas de la institución debe poder operar el sistema sin haberlo construido |
| Paridad entre entornos | Alto | Elimina la clase de fallo "funciona en desarrollo, falla en producción" causada por diferencias de versión o configuración del sistema operativo host |
| Costo operativo proporcional a un desarrollador solo | Alto | Cualquier solución que exija un equipo de plataforma dedicado queda descartada de entrada |
| Curva de aprendizaje y complejidad de depuración | Medio | Un desarrollador que hoy no opera contenedores en producción asume un costo real de aprendizaje y de nuevas herramientas de diagnóstico |
| Velocidad de iteración en desarrollo local | Medio | La solución no debe hacer más lento el ciclo diario de código, prueba, código |
| Escalamiento futuro (varias instituciones) | Bajo hoy | No es la prioridad actual, pero la solución no debe cerrar esa puerta |

## Opciones consideradas

### Opción A: Despliegue directo sobre el sistema operativo del servidor

Instalar una JVM de Java 25, PostgreSQL, Redis y nginx directamente en el sistema operativo del
servidor, con un gestor de procesos como `systemd` y scripts de despliegue que copian el
artefacto compilado.

**Ventajas.**

- Sin capa de virtualización adicional: el consumo de recursos es el mínimo posible.
- Sin curva de aprendizaje de una herramienta nueva; el desarrollador ya conoce Linux.
- Depuración directa: los procesos son visibles con las herramientas estándar del sistema
  operativo, sin una capa de aislamiento que inspeccionar.

**Desventajas.**

- El entorno de desarrollo del portátil del desarrollador y el del servidor divergen con el
  tiempo casi con certeza: versión distinta de una librería del sistema, una variable de
  entorno que solo existe en un lugar, un paquete instalado manualmente y nunca documentado.
- La recuperación ante desastre exige reinstalar y reconfigurar manualmente cada componente,
  reproduciendo de memoria decisiones tomadas meses atrás. Es exactamente el escenario que la
  sección de contexto declara inaceptable.
- La transferencia tecnológica requiere documentar cada paso de instalación en un runbook que
  hay que mantener sincronizado a mano con la realidad del servidor, y que se desactualiza en
  cuanto alguien instala algo "solo por esta vez".
- Sin aislamiento entre procesos, un fallo de recursos de un componente (por ejemplo, un
  reporte pesado) puede degradar directamente a los demás sin ningún límite declarado.

Se descarta: resuelve el factor de menor peso (rendimiento bruto) a costa de los tres factores
de mayor peso.

### Opción B: Contenedores con Docker Compose

Cada componente del sistema (los tres procesos de aplicación, las dos aplicaciones web, la
base de datos, la caché, el almacenamiento de objetos y el borde nginx) corre en un contenedor
Docker, orquestado con Docker Compose, con una imagen construida una sola vez y promovida por
digest entre entornos.

**Ventajas.**

- El entorno completo se levanta con un comando (`docker compose up`), incluida la base de
  datos con datos de prueba, en cualquier máquina que tenga Docker instalado.
- La recuperación ante desastre se reduce a: aprovisionar un host nuevo, instalar Docker,
  restaurar el respaldo, ejecutar `docker compose up`. Es un procedimiento, no un acto de
  memoria.
- La imagen construida en integración continua y escaneada con Trivy es exactamente la que se
  ejecuta en preproducción y en producción, eliminando la divergencia de entorno.
- El costo operativo es proporcional a lo que un solo desarrollador puede sostener: no exige
  un clúster, ni un equipo de plataforma, ni conocimiento especializado de orquestación
  distribuida.
- Docker Compose es, hoy, la herramienta que la enorme mayoría de tutoriales, documentación y
  soporte de la comunidad asumen como punto de partida, lo que reduce el costo de aprendizaje
  frente a un orquestador más sofisticado.

**Desventajas.**

- Curva de aprendizaje real: construcción multietapa, redes de Docker, volúmenes, políticas de
  reinicio y depuración de un proceso dentro de un contenedor son herramientas nuevas para
  quien nunca las operó en producción.
- La depuración de un problema de red o de recursos dentro de un contenedor exige herramientas
  y comandos distintos a los que un desarrollador usaría directamente sobre el sistema
  operativo (`docker exec`, `docker logs`, `docker stats`, en vez de `journalctl` y `htop`
  directos sobre el proceso).
- Docker Compose no ofrece por sí mismo alta disponibilidad, ni failover automático entre
  hosts, ni un verdadero despliegue *blue-green*. Con un solo host por perfil (sección 2 de
  `docs/05-infraestructura-y-despliegue.md`), un fallo del host completo sigue siendo una
  interrupción real, mitigada por respaldo y procedimiento de recuperación, no por redundancia
  automática.
- **Contenerizar PostgreSQL es aceptable, pero solo bajo condiciones estrictas y no
  negociables**, porque una base de datos con estado es el componente donde un error de
  contenerización tiene el costo más alto:
  1. **Volumen persistente en disco del anfitrión**, nunca efímero. El contenedor de
     PostgreSQL se destruye y se recrea con libertad; el volumen de datos, nunca.
  2. **Versión fijada por digest**, nunca `latest` ni una etiqueta móvil como `16-alpine`, para
     que una actualización de la imagen base no ocurra por accidente al reconstruir.
  3. **Respaldos fuera del contenedor**, en almacenamiento de objetos externo y cifrado, según
     `docs/05-infraestructura-y-despliegue.md`, sección 11. El volumen local no es el respaldo.
  4. **Procedimiento de actualización mayor documentado y ensayado**, porque un salto de
     versión mayor de PostgreSQL dentro de un contenedor no es transparente: exige `pg_upgrade`
     o una migración lógica completa, y ejecutarlo por primera vez en producción sin haberlo
     practicado es un riesgo que este ADR no está dispuesto a aceptar en silencio.

### Opción C: Orquestación con Kubernetes

Un clúster de Kubernetes gestiona el ciclo de vida de todos los componentes, con auto-escalado,
recuperación automática de nodos y despliegues declarativos avanzados.

**Ventajas.**

- Auto-recuperación de pods y de nodos sin intervención manual.
- Escalamiento horizontal automático por carga.
- Es el estándar de facto para sistemas que sí necesitan multi-nodo real y equipos de
  plataforma dedicados.

**Desventajas.**

- Sobrecarga operativa desproporcionada para un solo desarrollador: `etcd`, el plano de
  control, redes de superposición (*overlay*), controladores de ingreso, gestión de secretos
  propia de Kubernetes y actualizaciones del propio clúster son, cada una, una superficie de
  aprendizaje y de fallo adicional que no existía en la opción B.
- El beneficio central (auto-escalado y alta disponibilidad multi-nodo) no tiene contraparte de
  necesidad real: `docs/01-arquitectura.md`, sección 9, ya establece que la fase uno opera con
  dos hosts y cientos de estudiantes por institución, muy por debajo del punto donde Kubernetes
  empieza a pagarse solo.
- Ejecutar PostgreSQL con estado dentro de Kubernetes sin un operador especializado (por
  ejemplo, CloudNativePG) es más riesgoso, no menos, que la opción B, y adoptar ese operador es
  una capa más de conocimiento especializado.
- Se descarta explícitamente en `docs/01-arquitectura.md`, sección 9: "no se usa Kubernetes.
  Docker Compose con un pipeline de despliegue simple es suficiente hasta varias
  instituciones".

### Opción D: Plataforma como servicio (PaaS)

Delegar cómputo, base de datos gestionada, colas y almacenamiento a un proveedor de PaaS
(por ejemplo Render, Railway o Fly.io), sin gestionar contenedores ni servidores directamente.

**Ventajas.**

- Menor carga operativa inmediata: el proveedor gestiona parcheo del sistema operativo,
  escalamiento básico y, con frecuencia, respaldo de la base de datos gestionada.
- Despliegue muy rápido de configurar al inicio del proyecto.

**Desventajas.**

- **Dependencia fuerte de un proveedor** cuyo modelo de precios, disponibilidad regional y
  continuidad de negocio están fuera del control de la institución. Para un sistema que maneja
  dinero real y datos de menores, atar la continuidad operativa a la continuidad comercial de
  un proveedor externo es un riesgo que compite directamente con el objetivo de recuperación
  ante desastre.
- Reduce, no elimina, la reproducibilidad local: en la práctica casi todos los PaaS relevantes
  igual consumen una imagen de contenedor o un `Dockerfile` como unidad de despliegue, así que
  la disciplina de contenerización se necesita de todas formas; lo que cambia es solo quién
  opera el orquestador.
- **Contradice directamente el objetivo de transferencia tecnológica.** Entregar el sistema al
  departamento de sistemas de la institución exige que ellos puedan operarlo con
  infraestructura propia si así lo deciden. Un PaaS propietario ata esa decisión a un panel de
  control de terceros que el departamento de sistemas no necesariamente puede o quiere heredar.
- El costo mensual de un PaaS gestionado con los componentes que CONFIA necesita (cómputo para
  tres procesos, PostgreSQL, Redis, almacenamiento de objetos) suele superar el rango estimado
  para self-hosting en VPS de la sección 12 de `docs/05-infraestructura-y-despliegue.md`, sin
  resolver la dependencia de proveedor.

No se descarta como alternativa futura para una fase de mucho mayor escala, pero no resuelve
las restricciones actuales del proyecto mejor que la opción B, y las empeora en el eje que más
importa: la continuidad operativa independiente del desarrollador original.

## Decisión

**Se adopta la opción B: contenedores con Docker Compose, en los tres entornos, con una sola
imagen de aplicación construida una vez y promovida por digest.**

El factor determinante es la combinación de **reproducibilidad** y **recuperación ante
desastre** bajo la restricción de un solo desarrollador. Docker Compose es la herramienta más
simple que resuelve ambos problemas por completo sin introducir una superficie operativa que
un desarrollador solo no pueda sostener. La opción A no resuelve ninguno de los dos. La opción
C resuelve ambos, pero a un costo operativo que la propia arquitectura del proyecto ya descartó
por desproporcionado. La opción D resuelve la reproducibilidad de forma parcial, pero
compromete directamente el objetivo de transferencia tecnológica, que es una restricción tan
dura como las otras dos.

El segundo factor es la **transferencia tecnológica**. Docker Compose es autocontenido: el
`docker-compose.yml` y los `Dockerfile` del repositorio son, en sí mismos, la documentación
ejecutable de cómo se levanta el sistema. Eso es exactamente lo que el departamento de sistemas
de la institución necesita heredar el día de la transferencia, sin depender de que el
desarrollador original explique de memoria una instalación manual, ni de un panel de control de
un proveedor externo al que no necesariamente tendrán acceso continuado.

Se acepta conscientemente que Docker Compose no ofrece alta disponibilidad automática
multi-nodo. Con un solo desarrollador y la topología de fase uno de `docs/01-arquitectura.md`,
sección 5, ese no es hoy un requisito real; si en el futuro lo fuera (opción C rechazada por
razones de costo, no de imposibilidad), la migración desde contenedores ya construidos y
disciplinados es un trabajo acotado, mientras que migrar desde una instalación directa sobre el
sistema operativo (opción A) habría sido una reescritura completa de la capa de despliegue.

## Consecuencias

**Positivas:**

- El entorno de desarrollo se levanta con un solo comando en cualquier máquina, incluida la
  base de datos con datos de prueba.
- La imagen que pasa preproducción es, por construcción, la misma que corre en producción
  (sección 7 de `docs/05-infraestructura-y-despliegue.md`), eliminando divergencias de entorno
  como causa de incidentes.
- La recuperación ante la pérdida completa de un servidor es un procedimiento documentado y
  ensayable, no un acto de memoria.
- La transferencia tecnológica futura al departamento de sistemas de la institución se reduce a
  entregar acceso al repositorio y a los respaldos, no a transferir conocimiento tácito de
  instalación.
- Límites de recursos explícitos por contenedor (`docker-compose.prod.yml`) contienen el
  impacto de un componente con fuga de memoria o de CPU sobre el resto del sistema, algo que la
  opción A no ofrecía de forma nativa.

**Negativas y costos aceptados:**

- Se asume una **curva de aprendizaje real** en construcción multietapa de imágenes, redes de
  Docker, volúmenes y depuración dentro de contenedores. Se mitiga documentando los comandos de
  diagnóstico habituales (`docker compose logs`, `docker compose exec`, `docker stats`) como
  parte del runbook operativo de `docs/07-observabilidad-y-operaciones.md`.
- La **depuración de un incidente en producción** exige un paso adicional (entrar al
  contenedor o leer sus logs agregados) frente a depurar un proceso nativo del sistema
  operativo. Se mitiga con logs estructurados exportados fuera del contenedor desde el primer
  día (`docs/01-arquitectura.md`, sección 8), de modo que el caso común de diagnóstico no
  dependa de entrar al contenedor.
- **Riesgo específico de PostgreSQL contenerizado**, descrito en detalle en la opción B: volumen
  persistente, versión fijada, respaldo fuera del contenedor y procedimiento de actualización
  mayor documentado son condiciones no negociables de esta decisión, no una mejora opcional.
  Si el presupuesto lo permite en una fase posterior, migrar a una base de datos gestionada por
  el proveedor sigue siendo preferible y no contradice este ADR (`docs/01-arquitectura.md`,
  sección 9).
- Sin alta disponibilidad automática multi-host: un fallo del host de datos es una interrupción
  real, mitigada por respaldo con objetivo de recuperación de quince minutos y objetivo de
  tiempo de recuperación de cuatro horas, no evitada por completo.
- Docker Compose exige disciplina manual en la secuencia de actualización progresiva
  (`infra/scripts/deploy.sh`, `docs/05-infraestructura-y-despliegue.md`, sección 9) que un
  orquestador más sofisticado automatizaría; se acepta porque automatizarlo con Kubernetes
  cuesta más de lo que ahorra en esta fase.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Una actualización mayor de PostgreSQL ejecutada por primera vez en producción, sin práctica previa | Procedimiento de actualización mayor documentado y ensayado en preproducción antes de cada salto de versión mayor, con respaldo verificado inmediatamente antes |
| El volumen persistente de PostgreSQL se pierde junto con el host (por ejemplo, un volumen efímero mal configurado) | Verificación explícita en la lista previa a producción de que el volumen está en almacenamiento persistente del anfitrión, más respaldo fuera del contenedor como red de seguridad independiente |
| Deriva de configuración entre `docker-compose.yml` y `docker-compose.prod.yml` que introduce una diferencia no documentada | El *overlay* de producción se revisa en cada cambio con el mismo proceso de revisión que el código; `docker compose config` se ejecuta en integración continua para validar que la composición final es la esperada |
| El desarrollador único se familiariza con Docker pero el departamento de sistemas receptor no | La transferencia tecnológica de las fases F10 y F12 (`docs/09-roadmap-y-fases.md`) incluye sesión práctica de operación de los contenedores, no solo entrega de documentación |
| Una imagen base con una vulnerabilidad crítica llega a producción por una fijación de digest desactualizada | Trivy en cada construcción con severidad alta o crítica bloqueando la publicación, más revisión periódica programada de la vigencia de los digests fijados |

## Cumplimiento y verificación

1. **Hadolint sobre los `Dockerfile` en integración continua.** Un job dedicado ejecuta
   `hadolint infra/docker/api.Dockerfile` y `hadolint infra/docker/web.Dockerfile` con
   severidad de error para reglas de buenas prácticas (uso de `COPY` frente a `ADD`, ausencia de
   versión fijada, uso de `USER` antes de la instrucción final, entre otras). Una violación
   rompe la construcción.
2. **Trivy sobre la imagen construida**, con severidad `HIGH,CRITICAL` y `exit-code: 1`, como ya
   está integrado en `.github/workflows/cd.yml` (`docs/05-infraestructura-y-despliegue.md`,
   sección 7). Una vulnerabilidad alta o crítica sin corrección disponible bloquea la
   publicación de la imagen.
3. **Verificación de que el usuario de ejecución no es `root`**, incluida explícitamente en la
   lista de verificación previa a producción: `docker run --rm <imagen> id` debe reportar un
   UID distinto de `0`, y el mismo chequeo se automatiza como paso de la construcción
   (`docker inspect --format='{{.Config.User}}'` no vacío y distinto de `root`).
4. **Prueba de que la imagen desplegada coincide por digest con la que pasó preproducción.**
   El flujo de despliegue a producción recibe el digest como salida directa del job que publicó
   y escaneó la imagen (`docs/05-infraestructura-y-despliegue.md`, sección 7), nunca como una
   etiqueta reconstruida. Como verificación adicional posterior al despliegue, un paso de humo
   ejecuta `docker inspect --format='{{index .RepoDigests 0}}'` sobre el contenedor corriendo
   en producción y lo compara contra el digest que aprobó preproducción; una discrepancia falla
   el despliegue y dispara alerta.
5. **`docker compose config` en integración continua** sobre la combinación
   `docker-compose.yml` + `docker-compose.prod.yml`, para detectar de forma temprana una
   referencia rota o una variable de entorno no resuelta antes de que ocurra en producción.

## Referencias

- `docs/01-arquitectura.md`, sección 9 (contenerización) y sección 5 (topología de despliegue)
- `docs/05-infraestructura-y-despliegue.md`, secciones 3, 7, 9 y 11
- `docs/06-estrategia-de-testing.md`, sección 14 (`.github/workflows/ci.yml`)
- ADR-0002: monolito modular con arquitectura hexagonal y organización screaming
- ADR-0004: PostgreSQL y representación monetaria

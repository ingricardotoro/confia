# Propuesta: esqueleto de espacio de trabajo Maven e integración continua

- **Cambio:** `maven-workspace-and-ci-skeleton`
- **Fase del roadmap:** F0, cambio 1 de 13 (exploración `foundations-plan`, aprobada el 2026-09-15)
- **Estado:** aprobada por el propietario del producto el 2026-09-15

## Intención

El repositorio no tiene código, ni `pom.xml`, ni repositorio Git. Todas las garantías que protegen
el dinero y los datos de menores (pureza del dominio, fronteras entre módulos, prohibición de JPA y
de bibliotecas de programación paralelas, umbrales de cobertura) están decididas en los ADR y
**ninguna se verifica hoy**. Una regla que no rompe la construcción no protege nada.

Este cambio instala el mecanismo de verificación **antes** de la primera regla financiera, para que
ningún módulo posterior se escriba sin esa red. Es el primer cambio de F0 porque los otros doce
dependen de que exista un proyecto construible.

## Alcance

### Dentro de alcance

1. POM padre de `apps/api` con Java 25 y la lista de materiales de Spring Boot 4.1; Maven Wrapper
   comprometido al repositorio (ADR-0013).
2. `maven-enforcer-plugin`: versión de Java en un único lugar, convergencia de dependencias, sin
   versiones `SNAPSHOT` en la rama principal, `kernel` sin dependencias fuera del JDK y dependencias
   prohibidas de ADR-0015 (Hibernate, Jakarta Persistence, Spring Data JPA y JDBC) y de ADR-0016
   (Quartz, JobRunr y cualquier otra biblioteca de programación).
3. Módulo Maven `kernel` vacío, como frontera de compilación, y módulo `app` con artefacto
   `confia-api`, con los puntos de entrada de `bootstrap` seleccionados por perfil y sin ningún
   módulo de negocio todavía.
4. Suite de ArchUnit con las reglas de ADR-0002 (`domain-is-pure`, `no-cross-module-domain`,
   `application-no-infrastructure`, `interface-only-application`, `no-cycles`), la verificación de
   módulos de Spring Modulith y la regla estructural que rechaza paquetes `interface`, `interfaces`
   o con nombre de capa técnica.
5. Flujo de integración continua que ejecuta `./mvnw verify` en `apps/api` y un escaneo de
   vulnerabilidades de las dependencias de Maven que bloquea ante severidad alta o crítica
   (ADR-0008, ADR-0013).
6. Decisión registrada: paquete base `com.confia`, módulos `kernel` y `app`, artefacto `confia-api`.
7. Una prueba trivial en verde y una violación deliberada y temporal que demuestre que las reglas
   efectivamente rompen la construcción.

### Fuera de alcance

pnpm, Turborepo y `packages/*` (cambio 3); Flyway, jOOQ y Testcontainers (cambio 5); `Money` y los
umbrales de cobertura y mutación (cambio 2); springdoc y endpoints (cambio 3); Spring Security
(cambio 7); Dockerfiles y Compose (cambio 11); cualquier tabla o migración (cambios 4 y 5); las
reglas de ArchUnit que dependen del esquema o de jOOQ (cambio 5).

## Capacidades

### Nuevas

- `build-integrity`: capacidad **técnica**, no de negocio, que registra como requisito verificable
  que una violación de las reglas de dependencia rompe la construcción. No pertenece al catálogo de
  diecisiete capacidades de negocio de `openspec/project.md`. **El propietario aprobó crearla el
  2026-09-15**, de modo que este cambio produce un delta de especificación en
  `openspec/changes/maven-workspace-and-ci-skeleton/specs/build-integrity/spec.md` y, al archivar,
  la capacidad se incorpora a `openspec/specs/`.

### Modificadas

- Ninguna.

## Entregable y criterio de salida de F0

Cubre parcialmente el entregable 1 de F0 y **sienta el mecanismo del criterio de salida 1** («un
intento de importar el `domain` de otro módulo rompe la construcción»). No lo cierra: ese criterio
solo se puede comprobar por completo cuando existan al menos dos módulos de negocio (cambios 4 y 7).

## Requisito previo humano: cumplido

Cumplido el 2026-09-15 con autorización del propietario: repositorio Git inicializado, commit
inicial `b351d0d` con la línea base de documentación, repositorio **privado**
`github.com/ingricardotoro/confia` con `main` publicado, y rama `maven-workspace-and-ci-skeleton`
creada conforme a la convención de una rama por cambio de `CLAUDE.md`. Ya no bloquea la
integración continua.

## Enfoque

Construir de adentro hacia afuera: primero el POM padre y el Maven Wrapper con una prueba trivial en
verde (prueba de humo del instrumental), luego `kernel` como frontera de compilación, luego `app`
con sus puntos de entrada, y al final las reglas de ArchUnit, Spring Modulith y el enforcer, cada
una acompañada de la violación deliberada que demuestra que bloquea. La integración continua se
agrega cuando `./mvnw verify` ya pasa en local.

Las versiones exactas de los complementos, los nombres de sus objetivos y el mecanismo concreto de
la verificación de Spring Modulith **se confirman durante la implementación**: esta propuesta no los
fija.

## Áreas afectadas

| Área | Impacto | Descripción |
|---|---|---|
| `apps/api/pom.xml`, `mvnw`, `.mvn/` | Nueva | POM padre, enforcer y Maven Wrapper |
| `apps/api/kernel/` | Nueva | Módulo vacío, frontera de compilación |
| `apps/api/app/` | Nueva | Módulo de aplicación, puntos de entrada y pruebas de arquitectura |
| Flujo de integración continua | Nueva | Ejecuta `./mvnw verify` |
| `openspec/config.yaml` | Sin cambio | `strict_tdd` sigue en `false` (ver abajo) |

## Criterios de aceptación

- [ ] `./mvnw verify` termina en verde en una máquina limpia, sin instalar Maven a mano.
- [ ] Importar Spring desde `kernel` no compila.
- [ ] Una violación deliberada de capa rompe la construcción y, al retirarla, vuelve a verde.
- [ ] Agregar una dependencia prohibida (por ejemplo Hibernate o Quartz) rompe la construcción.
- [ ] Un paquete llamado `interface` o `services` rompe la construcción.
- [ ] La integración continua ejecuta `./mvnw verify` en cada empuje a la rama y su resultado es
      visible en el remoto.
- [ ] El escaneo de dependencias corre en la integración continua y una vulnerabilidad alta o
      crítica rompe la construcción.
- [ ] El paquete base, los nombres de módulo y el artefacto quedan escritos y no cambian después sin
      un ADR.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Java 25 y Spring Boot 4.1 son versiones recientes: la compatibilidad de enforcer, ArchUnit y Spring Modulith 2 no está verificada | Media | Prueba de humo (POM padre y prueba trivial en verde) **antes** de construir el resto; si algo no es compatible, se detiene el cambio y se informa, nunca se degrada una regla en silencio |
| Unas reglas de ArchUnit que nunca fallaron no prueban nada | Alta si no se mitiga | Cada regla se demuestra con una violación temporal deliberada que rompe la construcción y se retira en el mismo cambio |
| El paquete base y los nombres de módulo tienen baja reversibilidad | Media | Decisión ya aprobada por el propietario (`com.confia`, `kernel`, `app`, `confia-api`) y registrada aquí; cambiarla después exige un ADR |
| La creación del remoto excede la autoridad de los agentes | Cerrado | Repositorio privado y rama creados el 2026-09-15 con autorización del propietario |
| El esqueleto crece más allá de lo necesario y consume el presupuesto de revisión | Media | Alcance cerrado arriba; todo lo demás está asignado a otro de los trece cambios |

## Plan de reversión

No hay base de datos, ni esquema, ni despliegue, ni datos. Revertir es descartar la rama del cambio
y borrar `apps/api`. El único punto de no retorno práctico aparece después: cuando el cambio 2
dependa del paquete base y de los nombres de módulo.

## Dependencias

- Repositorio Git, remoto y rama: **cumplido** (ver requisito previo).
- JDK 25 disponible en la máquina de desarrollo y en la integración continua.
- Docker todavía no hace falta: llega con el cambio 5 (Testcontainers y generación de jOOQ).

## Nota sobre `strict_tdd`

`strict_tdd` permanece en **`false`** en `openspec/config.yaml` durante este cambio: aún no existe
`Money` ni ninguna regla de negocio que escribir primero como prueba. Pasa a `true` en el cambio 2,
`kernel-money-value-object`, junto con los umbrales de cobertura, con tarea explícita para ello.

## Ronda de preguntas de propuesta

El modo de ejecución es automático, así que estas preguntas quedan registradas en lugar de hacerse
en conversación. El propietario puede responderlas, corregirlas o darlas por aceptadas antes de la
fase de especificación.

1. ¿Se acepta crear la capacidad técnica `build-integrity` en `openspec/specs/`, aunque no
   pertenezca al catálogo de capacidades de negocio, o el criterio de salida 1 se verifica solo por
   tareas?
2. **Discrepancia resuelta, no hay contradicción.** ADR-0013 fija **tres procesos** de aplicación
   (administrativo, portal y trabajador) y `docs/05-infraestructura-y-despliegue.md` líneas 139 y
   748 agregan un cuarto valor de `APP_PROFILE`, `migrate`, que no es un proceso de servicio: aplica
   las migraciones de Flyway y termina. El lanzador de este cambio reconoce los cuatro valores; el
   comportamiento de `migrate` llega con el cambio 5, cuando exista Flyway. No requiere decisión del
   propietario.
3. **Resuelto por el orquestador.** El escaneo de vulnerabilidades de dependencias de Maven se
   incluye en este cambio, con bloqueo ante severidad alta o crítica, porque este es el cambio que
   crea la integración continua y ADR-0008 y ADR-0013 lo exigen en cada construcción. Adelantarlo
   cuesta poco ahora, con pocas dependencias, y evita que la regla nazca desactivada.

**Supuestos registrados mientras no haya respuesta:** `build-integrity` se crea como delta técnico.

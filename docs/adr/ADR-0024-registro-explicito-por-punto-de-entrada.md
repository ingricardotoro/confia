# ADR-0024: Registro explícito de lo que carga cada punto de entrada, sin escaneo de componentes

- **Estado:** Aceptado
- **Fecha:** 2026-10-03
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Paquete `com.confia.bootstrap` de `apps/api/app` (puntos de entrada
  administrativo, del portal y trabajador). Concreta ADR-0003 y ADR-0013 y aclara ADR-0003 y
  ADR-0022 sin reescribirlas.

## Contexto y problema

ADR-0003 promete que el proceso del portal no tiene cargado el código administrativo, porque «cada
punto de entrada declara de forma explícita qué módulos carga». Hasta este cambio esa promesa no se
cumplía ni siquiera con el código que existía: las tres clases de entrada vivían juntas en
`com.confia.bootstrap`, con visibilidad de paquete, y las tres llevaban `@SpringBootApplication`
con `scanBasePackages = "com.confia.bootstrap"`. Como `@SpringBootApplication` es a su vez una
`@Configuration`, cada punto de entrada, al escanear su propio paquete, registraba a los otros dos.

**Evidencia en rojo, ejecutada** (`ProcessBeanIsolationTest` contra el árbol sin cambios de
producción, `apply-progress.md` del cambio `process-entry-point-isolation`, tarea 1.1):

- Los contextos administrativo y del portal contenían **tres** configuraciones de entrada:
  `adminApplication`, `portalApplication` y `workerApplication`.
- El contexto del trabajador contenía `ContractSchemas` y `ProcessApiInfo`, que le llegaban por el
  `@Import` de las otras dos clases, aunque el trabajador nunca sirve la superficie OpenAPI.

Hoy la fuga no expone nada, porque todavía no hay beans de negocio. Si no se decide ahora, el primer
módulo que se registre en el punto de entrada administrativo aparece también en el portal, el
proceso expuesto a internet abierto, sin que nadie lo note en revisión.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| La separación falla cerrado | Muy alto | Un módulo que nadie pensó en prohibir no debe aparecer en el portal por omisión |
| Verificable en `./mvnw verify` | Muy alto | Una garantía que depende de la disciplina de revisión no protege nada (ADR-0018) |
| No crear API pública de módulos sin consumidor | Alto | ADR-0022 expone solo lo que un consumidor real necesita |
| Costo mínimo para un desarrollador solo | Alto | Pocas piezas, una sola lista legible por proceso |

## Opciones consideradas

### Opción A: subpaquetes por proceso, cada uno con su propio `scanBasePackages`

Corta la fuga actual. **Desventajas:** sigue descubriendo por escaneo; cualquier clase anotada que
alguien deje en el subpaquete entra al contexto sin pasar por un `@Import` visible.

### Opción B: `@Import` de la configuración pública de cada módulo, construida ya

Coincide con la letra de ADR-0003. **Desventajas:** exige crear hoy configuraciones públicas de
módulos que ningún proceso consume todavía, contra ADR-0022.

### Opción C: condiciones `@Profile`, `@ConditionalOnProperty` o `AppProfile`

**Desventajas:** falla abierto. Un bean sin la condición se registra en todos los procesos, y
`APP_PROFILE` no es un perfil de Spring. ADR-0003 exige separación por contexto, no por
configuración. **Descartada.**

### Opción D: subpaquetes por proceso más `@Import` explícito de lo que existe hoy

Cada proceso vive en su subpaquete y se declara con `@SpringBootConfiguration` +
`@EnableAutoConfiguration`, sin `@ComponentScan`. Lo que carga aparece escrito en su `@Import`.
**Ventajas:** garantía estructural (sin escaneo, nada entra sin estar escrito) y verificada (una
prueba compara los beans de cada contexto contra una lista de permitidos que falla cerrado). Deja
la forma de lista que el primer módulo registrado extenderá, sin crear API prematura.
**Desventajas:** las clases de entrada pasan a ser públicas y cada módulo futuro necesita una
configuración pública.

## Decisión

**Se adopta la opción D.** Cada proceso tiene su propio subpaquete de `com.confia.bootstrap`
(`admin`, `portal`, `worker`) y su clase de entrada declara `@SpringBootConfiguration` +
`@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)` + `@Import` de lo que
carga. `ConfiaApplication`, en el paquete base, sigue siendo el único `main` y selecciona el
proceso por `APP_PROFILE`. Ninguna clase de entrada usa `@SpringBootApplication` ni
`@ComponentScan`.

### Aclaración de ADR-0003: «el trabajador corre con el perfil administrativo»

ADR-0003 dice que el trabajador corre «con el perfil administrativo». Eso significa que usa el rol
de base de datos `confia_admin_app` y la configuración administrativa. **No** significa que cargue el
grafo de beans administrativo. El trabajador declara sus propios módulos en `WorkerApplication` y
hoy no importa ninguno. El cuerpo de ADR-0003 no se edita.

### Regla para módulos futuros

Un módulo que un punto de entrada deba cargar expone una **configuración pública**, en su paquete
base o en un paquete anotado con `@NamedInterface` (ADR-0022), que declara sus beans de forma
explícita. Esa configuración se crea cuando existe el consumidor real: el primero es
`session-tokens-and-web-layer`, nunca de forma anticipada.

Registrar un módulo en un proceso exige **dos ediciones visibles en el mismo pull request**:

1. el `@Import` de su configuración pública en la clase de entrada del proceso, y
2. su paquete en la lista de permitidos de ese proceso en `ProcessBeanPolicy` (pruebas).

Si un módulo necesitara escaneo de componentes, solo puede ser de su propio paquete, y lo decide el
cambio que lo introduce. La regla de ArchUnit de este ADR solo cubre `com.confia.bootstrap`.

## Consecuencias

**Positivas:**

- La separación es estructural y verificada: un contexto solo contiene su clase de entrada, lo que
  importa y lo que aporta la autoconfiguración de Spring Boot.
- La lista de módulos por proceso se lee en un solo archivo por proceso y en una tabla de pruebas.
- Un módulo nuevo obliga a editar la lista de permitidos, y esa edición se ve en revisión.

**Negativas y costos aceptados:**

- Cada módulo que un proceso cargue necesita una configuración pública.
- Las clases de entrada son públicas, solo para que `ConfiaApplication` las arranque desde otro
  paquete; una regla de ArchUnit impide que cualquier otra clase las referencie.
- `@EnableAutoConfiguration` registra como paquete de autoconfiguración el de la clase anotada, que
  pasa de `com.confia.bootstrap` a `com.confia.bootstrap.<proceso>`. Hoy no lo consume nadie (no hay
  JPA, Hibernate ni Spring Data, ADR-0015).

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Una biblioteca futura que use `@AutoConfigurationPackage` para buscar componentes vería una raíz demasiado estrecha | Quien la incorpore declara la raíz de forma explícita; el inspector delata cualquier bean que aparezca sin estar permitido |
| El cambio 9 añade el starter de db-scheduler y se autoconfigura también en administración y portal | El paquete `com.github.kagkarlsson` está prohibido por nombre en ambos procesos; `dbSchedulerAbsenceIsVacuousUntilChange9` falla ese día y obliga a revisar el caso |
| La prohibición de `com.confia.identity` en el portal queda demasiado ancha cuando llegue el subdominio de encargados | El cambio que lo cree la estrecha al subpaquete de personal, con la edición visible en su pull request |

## Cumplimiento y verificación

1. **`ProcessBeanIsolationTest`.** Arranca cada proceso por `ConfiaApplication.launch`, sin base de
   datos, y compara cada bean de `com.confia` contra la lista de permitidos del proceso (falla
   cerrado) y contra su lista nominal de prohibidos. Afirma una sola configuración de entrada por
   contexto, que el inspector ve beans reales y que el trabajador no es un contexto con servidor web.
2. **`ProcessBeanInspectorTest`.** Prueba negativa permanente: un bean fuera de la lista y un bean
   prohibido nominalmente se detectan, y un contexto limpio no se rechaza.
3. **`BootstrapEntryPointRulesTest`.** Tres reglas de ArchUnit, cada una con mitad de producción y
   mitad de fixture permanente (ADR-0018): los subpaquetes de `bootstrap` no dependen entre sí,
   nada fuera de `bootstrap` referencia una clase de entrada y ningún punto de entrada es
   `@ComponentScan`, directo o por meta-anotación.
4. **`SpringModulithVerificationTest`.** Sin violaciones en producción; los fixtures de las reglas
   anteriores son módulos propios y no alteran el resultado esperado.
5. **Pendiente, declarado:** la instantánea del mapa de rutas del portal (ADR-0003, verificación 1)
   llega con la primera capa web. La aserción positiva de db-scheduler en el trabajador llega con
   el cambio 9.

## Referencias

- ADR-0003: separación entre administración y portal
- ADR-0013: backend en Java con Spring Boot
- ADR-0016: trabajos en segundo plano con db-scheduler
- ADR-0018: conjunto vacío en las reglas de arquitectura
- ADR-0022: interfaz nombrada de Spring Modulith para los paquetes de `shared`
- `openspec/changes/process-entry-point-isolation/` (propuesta, diseño, tareas y progreso de
  aplicación)

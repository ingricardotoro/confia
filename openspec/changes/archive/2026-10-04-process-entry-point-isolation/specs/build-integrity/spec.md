# Delta para Integridad de la construcción

- **Estado:** aprobado por el propietario del producto el 2026-10-03 (propuesta, delta y diseño)
- **Cambio:** `process-entry-point-isolation` (F0, cambio 15)

## ADDED Requirements

### Requisito: Cada proceso registra solo beans de `com.confia.*` incluidos en su lista de permitidos

El sistema DEBE verificar, en cada ejecución de `./mvnw verify`, que el contexto de cada uno de los
tres procesos —administrativo, portal y trabajador— contiene únicamente beans de `com.confia.*`
cuyo paquete pertenece a la lista de permitidos de ese proceso (ADR-0003). La verificación DEBE
arrancar cada proceso por el mismo camino de arranque de producción, sin base de datos. La lista de
permitidos DEBE fallar cerrado: un bean de `com.confia.*` cuyo paquete no figura en la lista del
proceso DEBE romper la construcción, de modo que registrar un módulo nuevo en un punto de entrada
obliga a editar la lista y esa edición queda visible en la revisión. La lista NO DEBE reemplazarse
por una lista de prohibidos como único control.

#### Escenario: Un bean fuera de la lista rompe la construcción

- **DADO** el contexto de un proceso con un bean de `com.confia.*` en un paquete que no figura en la
  lista de permitidos de ese proceso
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el proceso, el bean y su paquete

#### Escenario: Un módulo nuevo obliga a editar la lista

- **DADO** un módulo nuevo cuya configuración un punto de entrada importa, sin haber editado la
  lista de permitidos de ese proceso
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla, y el único modo de volverla verde es editar la lista, con la
  edición visible en el diff del PR

#### Escenario: Cada proceso solo contiene beans permitidos

- **DADO** los tres procesos tal como los entrega este cambio
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** todo bean de `com.confia.*` de cada contexto pertenece a la lista de permitidos de su
  proceso, y la verificación evalúa beans reales de cada contexto, no un conjunto vacío

### Requisito: El contexto del portal no contiene beans de otros puntos de entrada ni de módulos administrativos

El contexto del proceso del portal NO DEBE contener ningún bean de los paquetes de entrada del
proceso administrativo (`com.confia.bootstrap.admin`) ni del proceso trabajador
(`com.confia.bootstrap.worker`), ni de los módulos `invoicing`, `cashbox` y `reconciliation`, ni de
`identity` mientras ese módulo sea solo para personal. Esta prohibición nominal DEBE regir además de
la lista de permitidos del requisito anterior, no en su lugar. El sistema DEBE romper la
construcción si el contexto del portal contiene alguno de ellos (ADR-0003, verificación 1, en su
parte de grafo de beans).

#### Escenario: El portal arrastra un punto de entrada ajeno

- **DADO** el contexto del portal con un bean de `com.confia.bootstrap.admin` o de
  `com.confia.bootstrap.worker`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean prohibido

#### Escenario: El portal arrastra un módulo administrativo

- **DADO** el contexto del portal con un bean de `invoicing`, `cashbox`, `reconciliation` o de
  `identity` mientras `identity` sea solo para personal
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean y el módulo prohibido, aunque la lista de
  permitidos del portal llegara a incluir su paquete por error

#### Escenario: El portal contiene solo lo suyo

- **DADO** el contexto del portal tal como lo entrega este cambio
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** no contiene ningún bean de `bootstrap.admin`, de `bootstrap.worker` ni de los módulos
  administrativos, y la verificación pasa evaluando beans reales del contexto

### Requisito: El contexto del trabajador no contiene beans de los otros puntos de entrada ni sus importaciones

El contexto del proceso trabajador NO DEBE contener ningún bean de `com.confia.bootstrap.admin` ni
de `com.confia.bootstrap.portal`, ni los beans que esos puntos de entrada importan para su propia
superficie de API, `ContractSchemas` y `ProcessApiInfo`. El trabajador DEBE declarar sus propios
módulos y NO DEBE heredar el grafo de beans administrativo por el hecho de usar la configuración y
el rol de base de datos administrativos (aclaración de ADR-0003 que fija ADR-0024). El sistema DEBE
romper la construcción si el contexto del trabajador contiene alguno de ellos.

#### Escenario: El trabajador recibe una importación ajena

- **DADO** el contexto del trabajador con un bean `ContractSchemas` o `ProcessApiInfo`, o con un
  bean de `bootstrap.admin` o de `bootstrap.portal`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean ajeno

#### Escenario: El trabajador arranca sin servidor web y sin beans ajenos

- **DADO** el proceso trabajador arrancado por el camino de producción, sin servidor web y sin base
  de datos
- **CUANDO** se inspecciona su contexto
- **ENTONCES** no contiene ningún bean de los otros dos puntos de entrada ni sus importaciones, y la
  verificación evalúa el contexto real del trabajador

### Requisito: Los puntos de entrada se registran de forma explícita, sin escaneo implícito

Cada uno de los tres puntos de entrada DEBE residir en su propio subpaquete de
`com.confia.bootstrap` (`admin`, `portal` y `worker`) y DEBE declarar de forma explícita lo que
importa. Los puntos de entrada NO DEBEN usar `@SpringBootApplication` ni `@ComponentScan`. Los tres
DEBEN conservar la exclusión de `DataSourceAutoConfiguration`. `ConfiaApplication` DEBE seguir
siendo el único método `main` y DEBE conservar la selección del proceso por `APP_PROFILE` y el
arranque del trabajador sin servidor web. El sistema DEBE romper la construcción si un punto de
entrada vuelve a declarar un escaneo de componentes.

#### Escenario: Un punto de entrada vuelve a escanear

- **DADO** un punto de entrada que declara `@SpringBootApplication` o `@ComponentScan`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el punto de entrada y la anotación prohibida

#### Escenario: Los tres puntos de entrada cumplen la forma explícita

- **DADO** `AdminApplication`, `PortalApplication` y `WorkerApplication` en `bootstrap.admin`,
  `bootstrap.portal` y `bootstrap.worker`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** ninguno usa `@SpringBootApplication` ni `@ComponentScan`, los tres excluyen
  `DataSourceAutoConfiguration`, y `ConfiaApplication` sigue arrancando cada proceso según
  `APP_PROFILE`

### Requisito: Los subpaquetes de `bootstrap` no dependen entre sí

El sistema DEBE romper la construcción si una clase de un subpaquete de `com.confia.bootstrap`
(`admin`, `portal` o `worker`) depende de una clase de otro de esos subpaquetes. Esta regla
DEBE tener su propio fixture de prueba permanente que la viole a propósito, con la convención de
dos pruebas del repositorio: una mitad que demuestra que una violación real rompe la construcción y
otra que demuestra que el código de producción real no falla por esta causa.

#### Escenario: Fixture con dependencia entre subpaquetes de `bootstrap`

- **DADO** un fixture de prueba permanente en el que una clase de un subpaquete de `bootstrap`
  depende de una clase de otro subpaquete
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la dependencia cruzada

#### Escenario: Código de producción sin dependencias cruzadas

- **DADO** el código de producción real de `bootstrap.admin`, `bootstrap.portal` y
  `bootstrap.worker`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla pasa evaluando los tres subpaquetes reales, sin excepción de conjunto vacío

### Requisito: Nada fuera de `bootstrap` referencia una clase de entrada

El sistema DEBE romper la construcción si una clase fuera de `com.confia.bootstrap` referencia
`AdminApplication`, `PortalApplication` o `WorkerApplication`, que son públicas. Esta regla DEBE
tener su propio fixture de prueba permanente que la viole a propósito, con la misma convención de
dos mitades que las demás reglas de esta capacidad.

#### Escenario: Fixture que referencia una clase de entrada desde fuera de `bootstrap`

- **DADO** un fixture de prueba permanente, fuera de `com.confia.bootstrap`, que referencia una
  clase de entrada
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la referencia prohibida

#### Escenario: Código de producción sin referencias externas a clases de entrada

- **DADO** el código de producción real de `apps/api`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** ninguna clase fuera de `bootstrap` referencia una clase de entrada, y la regla evalúa
  código real, sin excepción de conjunto vacío

### Requisito: La aserción sobre db-scheduler en administración y portal es vacua hasta el cambio 9 (brecha con destino: cambio 9)

La verificación de aislamiento DEBE afirmar que ningún bean de `com.github.kagkarlsson` existe en
los contextos administrativo y del portal. Mientras db-scheduler no esté en el camino de clases
(ADR-0018), esa aserción NO DEBE presentarse como una garantía: DEBE declararse de forma explícita
como vacua, en la propia verificación y en esta especificación. La aserción positiva de que el
trabajador sí registra db-scheduler es responsabilidad del **cambio 9**
(`background-jobs-with-db-scheduler`), y este cambio NO DEBE afirmarla.

#### Escenario: La aserción es vacua y está declarada como tal

- **DADO** db-scheduler ausente del camino de clases de `apps/api`
- **CUANDO** se ejecuta la verificación de aislamiento sobre los contextos administrativo y del
  portal
- **ENTONCES** la aserción de ausencia pasa, y la verificación deja declarado de forma explícita que
  pasa por vacío y cita ADR-0018 y el cambio 9; este escenario deja de ser cierto el día que el
  cambio 9 incorpore la biblioteca

#### Escenario: La ausencia de db-scheduler se vuelve efectiva con la biblioteca presente

- **DADO** db-scheduler ya en el camino de clases tras el cambio 9
- **CUANDO** un bean de `com.github.kagkarlsson` aparece en el contexto administrativo o del portal
- **ENTONCES** la misma aserción falla señalando el bean y el proceso, sin necesitar reescribirse

### Requisito: Prueba negativa permanente del inspector de aislamiento

El sistema DEBE mantener una prueba negativa permanente que construya un contexto con una fuga
deliberada —un bean fuera de la lista de permitidos y un bean prohibido nominalmente— y afirme que
el inspector de aislamiento la detecta. Esta prueba demuestra que la verificación de aislamiento
no pasa por vacío: sin ella, un inspector que no inspeccionara nada sería indistinguible de uno
que no encuentra fugas.

#### Escenario: El inspector detecta un bean fuera de la lista

- **DADO** un contexto con un bean de `com.confia.*` en un paquete fuera de la lista de permitidos
- **CUANDO** el inspector lo evalúa
- **ENTONCES** reporta la violación señalando el bean y su paquete

#### Escenario: El inspector detecta un bean prohibido nominalmente

- **DADO** un contexto con un bean de un paquete de la lista de prohibidos del portal, por ejemplo
  `com.confia.bootstrap.admin`
- **CUANDO** el inspector lo evalúa con la lista del portal
- **ENTONCES** reporta la violación señalando el bean y la regla nominal incumplida

#### Escenario: El inspector no reporta un contexto limpio

- **DADO** un contexto cuyos beans de `com.confia.*` pertenecen todos a la lista de permitidos y a
  ningún paquete prohibido
- **CUANDO** el inspector lo evalúa
- **ENTONCES** no reporta ninguna violación, de modo que la detección de los escenarios anteriores
  no se debe a un inspector que rechaza todo

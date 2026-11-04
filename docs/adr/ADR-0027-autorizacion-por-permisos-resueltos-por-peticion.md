# ADR-0027: Autorización por permisos resueltos por petición

- **Estado:** Propuesto
- **Fecha:** 2026-11-04
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `kernel` (catálogo de permisos), `identity.domain` (roles y matriz), `identity.infrastructure`
  (asignación de rol por cuenta), `shared.security` (puerto de resolución), `shared.web` (decisión en la cadena de filtros),
  `shared.audit` (denegaciones). Cambio SDD `rbac-permission-matrix-and-audit-integration` (cambio 8 de F0). Reemplaza partes de
  ADR-0005 y precisa `docs/03-seguridad.md` §5.1 a §5.3.

## Contexto y problema

Hasta este cambio el sistema autentica, pero no autoriza por función: `ActorAuthentication` no entrega ninguna autoridad, ninguna
ruta exige un permiso y no existe un catálogo de roles. El criterio de salida 2 de F0 pide que un usuario sin un permiso reciba `403`
y que la denegación quede en la bitácora. Antes de construirlo hay que cerrar cuatro cosas que los documentos vigentes dejan abiertas
o contradicen:

- **ADR-0005 decidió el mecanismo de una forma que no sirve para escritura financiera.** Los permisos viajan en el token de acceso,
  con una ventana de hasta diez minutos para propagar un cambio de permisos o de rol, y la MFA es obligatoria solo para algunos roles.
  Una cuenta a la que se le quita un rol seguiría operando hasta diez minutos; un token puede además quedar desfasado de la matriz sin
  que nada lo detecte.
- **`docs/03` §5.1 pide una anotación en el controlador.** «Los permisos se declaran en el controlador con una anotación» deja la
  cobertura al cuidado de cada desarrollador, y el arranque solo podría verificarla recorriendo metadatos de cada método. Con un solo
  desarrollador, una ruta olvidada es un defecto silencioso.
- **`docs/03` §5.1 dice «uno o varios» roles por usuario**, y §5.3 dice que un usuario nunca modifica sus propios roles. Con varios
  roles por cuenta el conjunto efectivo depende de una unión que ninguna prueba compara con el documento.
- **`docs/03` §5.2 se contradice a sí misma.** El Administrador figura con `payments:create` y con lectura de la bitácora, pero la
  regla 5 dice que no cobra y la regla 3 dice que el Auditor tiene la lectura completa de la bitácora. El Cajero necesita solicitar
  una anulación sin poder anularla, y la tabla no tiene un permiso para eso. La MFA propia figura como una fila de permisos, aunque es
  autoservicio de cualquier cuenta.

Si no se decide ahora, el cambio 8b (gestión de roles y cuentas) y cada módulo de negocio de F1 en adelante tendrían que elegir su
propio mecanismo.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Revocación inmediata de un permiso | Muy alto | Es dinero y datos de menores; diez minutos con un rol retirado no son aceptables (regla 9 de `CLAUDE.md`: nada se confía al cliente) |
| Falla cerrada | Muy alto | Un error de lectura nunca puede interpretarse como «sin permisos» ni, peor, como «permitido» |
| Cobertura verificable sin disciplina individual | Muy alto | Un solo desarrollador: una ruta sin clasificar debe impedir el arranque, no depender de una revisión |
| La matriz del documento y la del código no divergen en silencio | Alto | Hoy el documento ya se contradice; una prueba debe comparar ambos |
| Reglas de dependencia (`docs/01`) | Alto | `domain` no importa a nadie; `shared` nunca importa un módulo |
| Costo por petición | Medio | Dos lecturas indexadas por petición con permiso son aceptables frente a operaciones transaccionales |

## Opciones consideradas

Las cinco que se descartan están en la sección **Alternativas descartadas**; la elegida es la de la sección **Decisión**.

## Decisión

Seis decisiones:

1. **Los permisos se resuelven por petición desde la base de datos**, en una transacción de solo lectura, sin caché. El token de acceso
   no lleva permisos ni roles; un claim `permissions` o `roles` se rechaza. Un fallo de lectura se lanza como excepción y responde `500`;
   nunca se lee como conjunto vacío.
2. **La decisión se toma en la cadena de filtros, sobre una lista cerrada de rutas con permiso**, no con anotaciones. El arranque del
   proceso administrativo falla si encuentra una ruta que no es pública, autenticada, con permiso ni excluida de forma explícita. No
   existen `@PreAuthorize`, `@RequirePermission` ni seguridad de métodos.
3. **Seis roles fijos en código y una asignación por cuenta e institución.** Los roles son Super Admin (por institución, no de
   plataforma), Administrador, Cajero, Contabilidad, Auditor y Coordinador Académico. El catálogo de 89 permisos (`Permission`, en
   `kernel`) y la matriz de los roles (`StaffRole`, en `identity.domain`) son código; la base de datos guarda únicamente qué rol tiene
   cada cuenta, con la cuenta y su institución como clave. No hay tablas globales de roles ni de permisos, de modo que la lista cerrada
   de tablas sin `institution_id` de ADR-0017 no cambia. No hay un rol del Encargado: su control de acceso espera a F8.
4. **La MFA es obligatoria para toda cuenta del personal** y la decide una política derivada, no una columna ni el rol.
5. **Toda ruta con permiso exige el segundo factor (`otp`) y una sesión viva.** Un token con solo contraseña recibe `403` sin consultar
   el resolutor, y un token de una sesión revocada recibe `401`.
6. **Las denegaciones se auditan con un tope en memoria**: un asiento completo por denegación hasta un tope por cuenta y permiso, y un
   resumen por ventana a partir de ahí, de modo que una inundación de denegaciones no inunde la bitácora. El tope se pierde ante una
   caída del proceso y se acepta.

La **matriz efectiva** es la de `docs/03` §5.2 con cuatro diferencias, registradas en una nota fechada de ese documento: el Cajero gana
`invoicing:request-void` (un código compuesto, distinto de `invoicing:void`), el Administrador pierde `audit:read` y `payments:create`,
y la MFA propia deja de ser una fila de permisos. El catálogo contiene exactamente los permisos que algún rol concede: 89, de los cuales
88 son combinaciones de módulo y verbo. `PermissionMatrixParityTest` compara el código con la tabla de esa nota celda por celda.

## Partes de ADR-0005 que reemplaza

El resto de ADR-0005 sigue vigente: la duración del token de acceso, las familias de tokens de refresco, la rotación y la detección de
reutilización.

1. **«Los permisos viajan en el token».** El token de acceso conserva `sub`, `iss`, `aud`, `exp`, `iat`, `jti`, `sid`, `tenant` y
   `amr`; deja de llevar el «conjunto de permisos efectivos».
2. **El retraso de hasta diez minutos para propagar un cambio de rol o de permisos.** Con la resolución por petición el cambio rige en
   la siguiente petición. La ventana de revocación del token de acceso en lecturas sin permiso sigue como estaba.
3. **La MFA por rol.** ADR-0005 la exige para cajero, contador y administrador y la deja opcional para el resto, incluidos el Auditor y
   el Coordinador Académico. Pasa a ser obligatoria para los seis roles y para una cuenta sin rol.
4. **El mecanismo de la verificación 9, «Cobertura de MFA por ruta».** La prueba que inspecciona el mapa de rutas y compara una lista de
   exenciones se reemplaza por la guarda de arranque de la cobertura de rutas y por la exigencia de `otp` en toda ruta con permiso.

## Alternativas descartadas

- **Claim de permisos en el token** (lo que decía ADR-0005): no permite revocar sin esperar al vencimiento y deja una copia de la matriz
  fuera de la base de datos.
- **Claim de roles en el token:** el mismo retraso, con una resolución a permisos en cada petición que sigue haciendo falta.
- **Seguridad de métodos de Spring (`@PreAuthorize`) o una anotación propia en el controlador:** deja la cobertura a cada método; una
  ruta sin anotación no se distingue de una pública sin recorrer los metadatos, y la verificación de arranque depende de la disciplina.
- **Tablas de roles y permisos por institución:** permite que dos instituciones diverjan, abre una tabla de escritura de privilegios
  que ningún caso de uso necesita todavía y obligaría a una migración semilla que el documento y el código podrían contradecir.
- **Caché de permisos con invalidación:** reintroduce una ventana de propagación y un segundo lugar donde la matriz puede estar
  desfasada, a cambio de ahorrar una lectura indexada por petición.

## Consecuencias

**Positivas.** La revocación de un rol rige en la siguiente petición. Una ruta sin clasificar impide arrancar. La matriz del documento
y la del código se comparan en cada construcción. El dominio del catálogo no depende de ningún marco.

**Negativas y costos aceptados.** Dos lecturas por petición con permiso (sesión viva y resolución). El tope de denegaciones es por
proceso y se pierde ante una caída. Una asignación de rol a mitad de una sesión no revoca las familias vivas de la cuenta; eso queda
para el cambio 8b, junto con la gestión de roles y cuentas.

**Riesgos y mitigaciones.** Un error de lectura del resolutor podría confundirse con «sin permisos»: se lanza como excepción y la
respuesta es `500`, con una prueba que lo demuestra. Una ruta nueva podría omitirse de la lista cerrada: la guarda de arranque falla
ante toda ruta sin clasificar. El Administrador no puede cobrar pero sí anular y aprobar pagos: es la matriz aprobada, y la segregación
de funciones de `docs/03` §5.3 la complementa cuando existan las operaciones con aprobación.

## Cómo se verifica

- `PermissionTest` y `StaffRoleTest`: 89 permisos, seis roles y la matriz por columna.
- `PermissionMatrixParityTest`: seis roles por 18 módulos contra la nota fechada de `docs/03` §5.2, con controles que cambian un solo
  lado.
- `DomainPurityRulesTest`: el dominio y el catálogo no dependen de marcos.
- `DocumentationNotesTest`: las notas fechadas de `docs/03` y de ADR-0005, y este ADR indexado.
- En los PR siguientes del mismo cambio: la falla cerrada del resolutor, la guarda de arranque, el rechazo sin `otp` y el criterio de
  salida 2 de extremo a extremo.

## Referencias

- ADR-0003 (separación de procesos), ADR-0005 (autenticación y sesiones), ADR-0009 (multi-institución), ADR-0017 (tablas técnicas y
  tabla raíz), ADR-0024 (registro explícito por punto de entrada).
- `docs/03-seguridad.md` §4.3, §5.1 a §5.4; `docs/01-arquitectura.md`; `docs/09-roadmap-y-fases.md` (criterio de salida 2 de F0).

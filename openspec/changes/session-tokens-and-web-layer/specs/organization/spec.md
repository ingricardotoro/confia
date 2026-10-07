# Delta para Organización

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `session-tokens-and-web-layer` (F0, cambio 7, parte 4b, primer cambio S1 de tres)
- **Corte que cubre:** C4b (adaptador de `CurrentInstitutionProvider` y prueba de ADR-0009)

Los escenarios nuevos se numeran `OR01` en adelante. El diseño fijó el adaptador en
`organization.infrastructure` (la regla W3 impide que `shared` implemente un puerto de `organization`);
esta especificación solo describe su comportamiento observable. Revisión del 2026-10-06: sin principal
autenticado el adaptador falla con un error interno (`500`), no con `401`, por ser un defecto de cableado.

## ADDED Requirements

### Requisito: El adaptador de producción de `CurrentInstitutionProvider` resuelve la institución solo del principal autenticado

El sistema DEBE tener un adaptador de producción de `CurrentInstitutionProvider` que resuelva el
`InstitutionId` de la petición en curso únicamente a partir de la institución del principal autenticado,
tomada de la claim `tenant` del token verificado. El adaptador NO DEBE usar la institución de la
configuración del proceso, que es exclusiva de las operaciones previas a la autenticación. Sin principal
autenticado, el adaptador DEBE fallar cerrado y NO DEBE devolver una institución por omisión; como una
ruta que llega a pedir la institución sin autenticar es un defecto de cableado y no un rechazo del
cliente, el fallo es un error interno que el borde traduce a `500` con el código `internal-error`, no a
un `401`. El adaptador NO DEBE recibir la petición ni leer ninguna cabecera, parámetro ni cuerpo. La
resolución DEBE ser propia de cada petición, también bajo concurrencia.

#### Escenario: [OR01] La institución sale del token

- **DADO** un token de acceso vigente con `tenant` igual a `d290f1ee-6c54-4b01-90e6-d701748f0851`
- **CUANDO** el controlador de prueba pide la institución a `CurrentInstitutionProvider`
- **ENTONCES** recibe `d290f1ee-6c54-4b01-90e6-d701748f0851`

#### Escenario: [OR02] La institución de la configuración del proceso no se usa

- **DADO** un proceso configurado con la institución `5a6a5e02-1c2e-4e3a-9d3f-6b2b3a1e9f10` y un token
  con `tenant` igual a `d290f1ee-6c54-4b01-90e6-d701748f0851`
- **CUANDO** el controlador de prueba pide la institución a `CurrentInstitutionProvider`
- **ENTONCES** recibe `d290f1ee-6c54-4b01-90e6-d701748f0851`, no la de la configuración

#### Escenario: [OR03] Sin principal autenticado falla cerrado

- **DADO** una petición a una ruta pública que invoca `CurrentInstitutionProvider`, sin credencial
- **CUANDO** se resuelve la institución
- **ENTONCES** falla con un error interno de cableado que el borde traduce a `500` con el código
  `internal-error`, y no devuelve ningún `InstitutionId`
- **Y** la respuesta no revela el motivo del fallo ni el nombre de la clase

#### Escenario: [OR04] Peticiones concurrentes de instituciones distintas no se cruzan

- **DADO** 50 peticiones concurrentes, sincronizadas con `CyclicBarrier`, cada una con un token de una
  institución distinta
- **CUANDO** cada controlador de prueba pide la institución a `CurrentInstitutionProvider`
- **ENTONCES** cada petición recibe la institución de su propio token, sin cruces

#### Escenario: [OR05] Un token con el `tenant` de una institución y el `sid` de otra no autentica la sesión

- **DADO** un token válido, firmado por el emisor, con `tenant` de la institución B y `sid` de una
  familia viva de la institución A
- **CUANDO** se presenta a `GET /api/v1/auth/sessions/current`
- **ENTONCES** la respuesta es `401` con el código `token-invalid`, porque la comprobación de la
  sesión se hace con el contexto de la institución B (la del token) y la familia no es visible en él

### Requisito: Ninguna cabecera, parámetro de consulta, cuerpo ni cookie altera la institución resuelta (ADR-0009)

El sistema NO DEBE permitir que ninguna cabecera, parámetro de consulta, cuerpo de petición ni cookie
altere el `InstitutionId` que resuelve `CurrentInstitutionProvider`, ni el contexto de institución de
la sesión de base de datos de esa petición (ADR-0009, «Implementación del aislamiento»; `docs/09`). La
autorización por institución se decide solo en el servidor, a partir del token verificado.

#### Escenario: [OR06] Las cabeceras de institución se ignoran

- **DADO** un token con `tenant` de la institución A
- **CUANDO** se envía la petición con las cabeceras `X-Institution-Id`, `Institution-Id`, `X-Tenant-Id`,
  `X-Forwarded-Host` y `Host` apuntando a la institución B
- **ENTONCES** la institución resuelta es A y la respuesta no contiene ningún dato de B

#### Escenario: [OR07] Los parámetros de consulta y las cookies de institución se ignoran

- **DADO** un token con `tenant` de la institución A
- **CUANDO** se envía la petición con `?institutionId=<B>&tenant=<B>` y con una cookie
  `institution=<B>`
- **ENTONCES** la institución resuelta es A

#### Escenario: [OR08] Un campo de institución en el cuerpo se ignora

- **DADO** un controlador de prueba autenticado con cuerpo JSON y un token con `tenant` de la
  institución A
- **CUANDO** se envía un cuerpo con el campo `institutionId` igual a la institución B
- **ENTONCES** la institución resuelta es A y el controlador no recibe la institución B

#### Escenario: [OR09] Un valor de institución malformado no produce error ni cambia la resolución

- **DADO** un token con `tenant` de la institución A
- **CUANDO** se envía la petición con `X-Institution-Id: no-es-un-uuid` y `?tenant=%00`
- **ENTONCES** la institución resuelta es A y la respuesta no es un error por ese valor

#### Escenario: [OR10] El contexto de la sesión de base de datos es el del token

- **DADO** un token con `tenant` de la institución A y filas de familias de A y de B
- **CUANDO** el controlador de prueba lee con el rol restringido real, con las cabeceras y los parámetros
  de los escenarios anteriores apuntando a B
- **ENTONCES** solo ve las filas de la institución A

## MODIFIED Requirements

### Requisito: Puerto de salida para la institución de la solicitud en curso

La capa `application` de `organization` DEBE declarar el puerto de salida
`CurrentInstitutionProvider`, con una operación que resuelve el `InstitutionId` de la solicitud en
curso. Este puerto NO DEBE depender de un parámetro provisto por el cliente (ADR-0009,
«Implementación del aislamiento»): su implementación real, a partir del token autenticado, la entrega
`session-tokens-and-web-layer` (cambio 7, parte 4b, primer cambio). Ante la ausencia de una sesión
autenticada, la implementación real DEBE fallar cerrado con un error interno (defecto de cableado, no un
rechazo del cliente) y NO DEBE devolver una institución por omisión.
Esta especificación no exige que el puerto resuelva un identificador en cada invocación.
(Previously: la implementación real era responsabilidad del cambio 7 y declarar el comportamiento ante
ausencia de sesión autenticada era responsabilidad del cambio que la implementara.)

#### Escenario: Resolución con un doble en memoria

- **DADO** un doble de prueba de `CurrentInstitutionProvider` configurado para devolver un
  `InstitutionId` conocido
- **CUANDO** se invoca su operación de resolución
- **ENTONCES** el doble devuelve ese `InstitutionId`

#### Escenario: [OR11] La implementación real falla cerrado sin sesión autenticada

- **DADO** la implementación real del puerto y ninguna sesión autenticada
- **CUANDO** se invoca su operación de resolución
- **ENTONCES** falla con un error interno y no devuelve ningún `InstitutionId`

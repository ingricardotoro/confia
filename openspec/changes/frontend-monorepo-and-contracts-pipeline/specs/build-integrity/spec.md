# Delta para Integridad de la construcción

- **Estado:** aprobado por el propietario del producto el 2026-09-30

## ADDED Requirements

### Requisito: El OpenAPI generado coincide con la instantánea aprobada

El sistema DEBE generar, en cada ejecución de `./mvnw verify`, el documento OpenAPI 3.1 de cada
una de sus dos superficies de API: la **administrativa** y la **del portal** (ADR-0003). Cada
documento DEBE compararse contra su instantánea aprobada, comprometida en el repositorio, y
cualquier diferencia no declarada DEBE romper la construcción. La comparación NO DEBE sobrescribir
nunca la instantánea: actualizarla DEBE ser un paso explícito y separado, cuyo resultado queda
visible en el diff del PR. El documento generado DEBE conservarse como artefacto de cada ejecución
de la integración continua. Este requisito cierra el criterio de salida 7 de F0 («OpenAPI 3.1 se
genera y publica como artefacto versionado»).

#### Escenario: Una diferencia no declarada rompe la construcción

- **DADO** la instantánea aprobada del documento administrativo, comprometida en el repositorio
- **CUANDO** la instantánea se altera a mano, de modo que ya no coincide con el documento que el
  código genera
- **ENTONCES** `./mvnw verify` falla y nombra el documento que difiere
- **Y** la instantánea comprometida queda intacta después de la ejecución

#### Escenario: Documento idéntico a la instantánea

- **DADO** las dos instantáneas aprobadas, sin alterar
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** los dos documentos generados coinciden con sus instantáneas y la construcción
  termina en verde

#### Escenario: Cada superficie tiene su propio documento

- **DADO** los dos documentos generados
- **CUANDO** se inspecciona su contenido
- **ENTONCES** son dos documentos distintos, uno por superficie, y ninguna operación del documento
  administrativo aparece en el del portal

### Requisito: Swagger UI y el endpoint del OpenAPI solo en local y preproducción

El sistema DEBE habilitar Swagger UI y el endpoint del documento OpenAPI únicamente cuando el
perfil de configuración activo es `local` o `preprod`. Con cualquier otro perfil, incluido `prod`,
NO DEBE responder ninguno de los dos, en ninguno de los tres procesos
(`docs/01-arquitectura.md` §7; `docs/05-infraestructura-y-despliegue.md`, variable
`SPRING_PROFILES_ACTIVE`).

#### Escenario: Perfil de producción

- **DADO** el proceso administrativo arrancado con el perfil `prod`
- **CUANDO** se pide el endpoint del documento OpenAPI y la ruta de Swagger UI
- **ENTONCES** ninguno de los dos devuelve el documento ni la interfaz

#### Escenario: Perfil local

- **DADO** el proceso administrativo arrancado con el perfil `local`
- **CUANDO** se pide el endpoint del documento OpenAPI
- **ENTONCES** devuelve un documento OpenAPI 3.1 válido

### Requisito: Esquemas transversales del contrato presentes desde el primer documento

El sistema DEBE publicar en ambos documentos OpenAPI, aunque todavía no exista ninguna operación,
los dos esquemas que el contrato ya fija (`docs/01-arquitectura.md` §7): el importe, con `amount`
y `currency` ambos de tipo cadena y obligatorios, y el error en formato Problem Details (RFC 9457).
El importe NO DEBE declararse nunca con tipo numérico (`CLAUDE.md`, regla 1).

#### Escenario: El importe viaja como cadena

- **DADO** el documento OpenAPI generado
- **CUANDO** se lee el esquema del importe
- **ENTONCES** `amount` y `currency` son de tipo `string` y ambos obligatorios
- **Y** ninguna propiedad del esquema es de tipo `number` ni `integer`

#### Escenario: Problem Details presente

- **DADO** el documento OpenAPI generado
- **CUANDO** se lee el esquema de error
- **ENTONCES** declara los campos de RFC 9457 (`type`, `title`, `status`, `detail`, `instance`) y
  el identificador de traza que exige `docs/01-arquitectura.md` §7

### Requisito: Los contratos del frontend se generan y un cambio incompatible rompe la compilación

El sistema DEBE generar `packages/contracts` con orval a partir de las instantáneas aprobadas: tipos
TypeScript y esquemas Zod, en un espacio de nombres por superficie de API. El código generado NO
DEBE comprometerse en el repositorio ni editarse a mano; la construcción del monorepo lo regenera
siempre. Una prueba de tipos DEBE consumir los contratos generados, de modo que un cambio
incompatible del contrato rompa la compilación de TypeScript en la integración continua, no en
producción.

#### Escenario: Cambio incompatible del contrato

- **DADO** la prueba de tipos que consume el esquema del importe
- **CUANDO** la instantánea se altera para que `amount` pase de cadena a número y se regeneran los
  contratos
- **ENTONCES** la verificación de tipos del monorepo falla

#### Escenario: Contrato sin cambios

- **DADO** las instantáneas aprobadas, sin alterar
- **CUANDO** se regeneran los contratos y se verifica los tipos del monorepo
- **ENTONCES** la verificación termina en verde

#### Escenario: La salida generada no está en el repositorio

- **DADO** el repositorio recién clonado, sin ninguna construcción previa
- **CUANDO** se inspecciona `packages/contracts`
- **ENTONCES** no contiene ningún archivo generado por orval, porque el control de versiones lo
  ignora

### Requisito: Reglas de dependencia del frontend

El sistema DEBE romper la construcción del monorepo, mediante `dependency-cruiser` y ESLint, cuando
el código TypeScript viola alguna de estas reglas: un paquete de `packages/` importa de una
aplicación de `apps/`; una aplicación importa de otra aplicación; cualquier código importa una ruta
interna de `packages/contracts` en lugar de su punto de entrada público; o el código del portal
importa el espacio de nombres administrativo de `packages/contracts`. Cada regla DEBE estar
acompañada de una violación deliberada que demuestre que la rechaza (ADR-0018). Ninguna regla puede
pasar sobre un conjunto vacío: una regla que no encuentra nada que evaluar no protege nada.

#### Escenario: Un paquete importa de una aplicación

- **DADO** la violación deliberada en la que un módulo de `packages/` importa de `apps/`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: El portal importa el contrato administrativo

- **DADO** la violación deliberada en la que código del portal importa el espacio de nombres
  administrativo de `packages/contracts`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: Código que respeta las reglas

- **DADO** el código real del monorepo, excluidas las violaciones deliberadas
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** termina en verde

### Requisito: Versión única de Node y de pnpm

El sistema DEBE fijar la versión de Node (24, la LTS activa) y la de pnpm en un único lugar del
repositorio, y la instalación DEBE fallar con cualquier otra versión de Node. La integración
continua DEBE usar exactamente esas versiones. El archivo de bloqueo de pnpm DEBE comprometerse, y
la integración continua DEBE instalar sin modificarlo.

#### Escenario: Versión de Node distinta

- **DADO** una máquina con una versión mayor de Node distinta de la fijada
- **CUANDO** se instala el monorepo
- **ENTONCES** la instalación falla y nombra la versión exigida

#### Escenario: Archivo de bloqueo desactualizado

- **DADO** un `package.json` modificado sin actualizar el archivo de bloqueo
- **CUANDO** la integración continua instala las dependencias
- **ENTONCES** la instalación falla en lugar de reescribir el archivo de bloqueo

### Requisito: La integración continua verifica el monorepo y escanea sus dependencias

El sistema DEBE ejecutar, en cada empuje a una rama de cambio y en cada pull request contra la rama
principal, la verificación del monorepo: análisis estático, verificación de tipos, pruebas y
construcción. El escaneo de vulnerabilidades DEBE cubrir también las dependencias de pnpm y romper
la construcción ante severidad alta o crítica, igual que ya hace con las de Maven (ADR-0008,
ADR-0013).

#### Escenario: Empuje con un error de tipos

- **DADO** un empuje a una rama de cambio con un error de tipos en el monorepo
- **CUANDO** corre la integración continua
- **ENTONCES** el trabajo del frontend falla y el resultado es visible en el remoto

#### Escenario: Vulnerabilidad crítica en una dependencia de pnpm

- **DADO** una dependencia de pnpm con una vulnerabilidad de severidad crítica conocida
- **CUANDO** corre el escaneo de dependencias
- **ENTONCES** la construcción falla y nombra la dependencia

#### Escenario: Solo severidad baja

- **DADO** dependencias de pnpm con vulnerabilidades conocidas solo de severidad baja o media
- **CUANDO** corre el escaneo de dependencias
- **ENTONCES** el escaneo las reporta sin romper la construcción

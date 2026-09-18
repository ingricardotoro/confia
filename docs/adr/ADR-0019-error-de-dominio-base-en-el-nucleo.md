# ADR-0019: Error de dominio base en el núcleo, con código estable legible por máquina

- **Estado:** Propuesto
- **Fecha:** 2026-09-18
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Módulo `apps/api/kernel` (paquete `com.confia.kernel`), paquete `domain` de
  todo módulo de negocio futuro, traducción de errores a Problem Details (RFC 9457) en la capa
  `web`, bitácora de auditoría y catálogos de internacionalización del frontend. Formaliza la
  decisión D1 de la propuesta `kernel-money-value-object`, aprobada por el propietario el
  2026-09-18.

## Contexto y problema

El cambio 2 de F0 (`kernel-money-value-object`) introduce el primer error de dominio del sistema:
sumar o comparar importes de monedas distintas debe fallar con un error explícito, no con un número
sin sentido (ADR-0004, ADR-0011). Detrás vienen once cambios más de F0 y todas las fases
posteriores, y cada módulo de negocio lanzará sus propios errores: rango CAI agotado, sesión de
caja cerrada, imputación que excede el saldo, factura ya anulada.

Esos errores tienen tres consumidores que no pueden depender de cada clase concreta:

1. **La capa `web`**, que debe traducirlos a Problem Details (RFC 9457,
   `docs/01-arquitectura.md` sección 7) con un `type` estable. El catálogo de tipos ya está escrito
   en `docs/ui-ux/04-patrones-de-interaccion.md` sección 9 (`cai-range-exhausted`,
   `over-allocation`, `cash-session-not-open`, entre otros), y el frontend mapea ese `type` a una
   clave de internacionalización propia.
2. **La bitácora de auditoría y el registro estructurado**, que necesitan clasificar el rechazo sin
   interpretar el texto del mensaje.
3. **Las pruebas**, que deben afirmar qué regla rechazó la operación sin acoplarse a la redacción
   de un mensaje técnico.

Si no se decide ahora, el primer módulo que necesite traducir errores (cambio 4 en adelante)
elegirá una forma, el siguiente otra, y el traductor de `web` terminará con un `instanceof` por
cada excepción de cada módulo. Cambiar la jerarquía cuando cinco módulos ya la usan obliga a tocar
cada excepción, cada prueba y cada traducción.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Un único punto de traducción a Problem Details | Muy alto | `web` no debe conocer cada excepción de cada módulo (ADR-0002, dependencia de capas). |
| Identificador estable independiente del texto | Muy alto | El `type` de RFC 9457 y las claves de i18n no pueden cambiar porque alguien corrija un mensaje. |
| `kernel` sin dependencias fuera del JDK | Muy alto | Regla `enforce-kernel-purity` (ADR-0002, ADR-0013). |
| Costo de cambiarlo después | Alto | Cada cambio posterior hereda la forma elegida. |
| Mínimo hoy, sin comportamiento especulativo | Alto | Un solo desarrollador; lo que no se usa se pudre. |

## Opciones consideradas

### Opción A: clase base abstracta no comprobada con código estable

`public abstract class DomainException extends RuntimeException` en `kernel`, con un constructor
protegido que recibe un código y un mensaje técnico, y un método `code()` final. Toda excepción de
dominio de `kernel` y de los módulos de negocio hereda de ella.

**Ventajas.** Un único `@ExceptionHandler(DomainException.class)` en `web` puede producir el
Problem Details con `type` derivado de `code()`. El código es un contrato explícito y verificable.
No comprobada, como exige el estilo de casos de uso de Spring y como hace ya el JDK con
`ArithmeticException`: la firma de los puertos no se contamina con `throws`.

**Desventajas.** Fija un contrato antes de tener un segundo consumidor real. Riesgo de que la base
acumule comportamiento ajeno a `kernel` (estado HTTP, severidad de registro, datos para la
interfaz).

### Opción B: solo excepciones concretas en `kernel`; la base se decide en el cambio 4

`CurrencyMismatchException extends RuntimeException` y nada más.

**Ventajas.** Cero contrato especulativo hoy.

**Desventajas.** Traslada la decisión exactamente al momento en que empieza a ser cara: el cambio 4
tendría que introducir la base y migrar las excepciones de `kernel`, y todo cambio que llegue antes
de que alguien lo haga escribe la forma que quiera.

### Opción C: resultado explícito en lugar de excepción (`Result<T, DomainError>`)

**Ventajas.** Hace visible el error en el tipo de retorno.

**Desventajas.** Contradice el estilo de Spring y de jOOQ que usa todo el backend (ADR-0013,
ADR-0015), obliga a desenvolver resultados en cada caso de uso y no elimina la necesidad de un
código estable. Para un solo desarrollador es fricción diaria sin beneficio proporcional. Se
descarta.

## Decisión

**Se adopta la opción A: `com.confia.kernel.DomainException`, abstracta y no comprobada, con un
código estable legible por máquina expuesto por `code()`. Toda excepción de dominio del sistema
hereda de ella.**

Contrato:

1. **Forma.** `public abstract class DomainException extends RuntimeException`. Único constructor
   `protected DomainException(String code, String message)`. Método `public final String code()`.
   Sin estado adicional, sin estado HTTP, sin severidad, sin datos para la interfaz: la base no
   sabe nada de `web`.
2. **Formato del código.** Minúsculas en kebab-case, `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`, como máximo
   64 caracteres, con la forma `<sujeto>-<condición>` (`currency-mismatch`,
   `money-scale-exceeded`). Es el mismo formato que los `type` de Problem Details ya catalogados en
   `docs/ui-ux/04-patrones-de-interaccion.md`, de modo que el traductor de `web` usa el código como
   último segmento del `type` sin transformarlo. El constructor rechaza un código nulo o con otro
   formato con `IllegalArgumentException`, porque es un error de programación, no de dominio.
3. **Estabilidad.** Un código publicado no se renombra ni se reutiliza: es parte del contrato de la
   API y de los catálogos de i18n. Retirarlo exige un cambio SDD explícito.
4. **Unicidad.** Un código identifica una sola condición en todo el sistema. Una clase puede emitir
   varios códigos (por ejemplo, las tres causas de un importe inválido), pero dos clases no comparten
   código.
5. **Mensaje.** En inglés, técnico, para registro y depuración; nunca se muestra al usuario (la
   interfaz traduce el código). No contiene datos personales ni repite la entrada cruda del usuario
   (`CLAUDE.md`, regla 11).
6. **Frontera con los errores de programación.** Un argumento nulo, un peso de reparto inválido o un
   modo de redondeo que exige exactitud imposible son errores de programación y se señalan con las
   excepciones del JDK (`NullPointerException`, `IllegalArgumentException`,
   `ArithmeticException`), no con `DomainException`. `DomainException` se reserva para condiciones
   que un dato de negocio puede provocar y que el sistema debe explicar.
7. **Ubicación.** En el paquete raíz `com.confia.kernel`, junto a los demás tipos públicos del
   núcleo, para que la verificación de módulos de Spring Modulith lo trate como API del módulo
   `kernel` (ver `openspec/changes/kernel-money-value-object/design.md`, decisión 1).

Razón principal: el costo de la opción A hoy es una clase de unas cuarenta líneas; el costo de no
tenerla es una migración de todas las excepciones del sistema en el momento en que más módulos
dependen de ellas.

## Consecuencias

**Positivas:**

- `web` traduce todo error de dominio a Problem Details con un solo manejador y un mapa de código a
  estado HTTP, que es además el registro natural de códigos del sistema.
- El frontend y la bitácora trabajan con identificadores estables, no con texto.
- Las pruebas afirman `code()`, que no cambia cuando se corrige la redacción de un mensaje.

**Negativas y costos aceptados:**

- El contrato existe antes que su segundo consumidor. Si el cambio 4 descubre que falta algo, lo
  añade con un ADR nuevo que reemplace a este, no con una edición silenciosa.
- La unicidad global de códigos no se puede verificar de forma estática mientras los códigos sean
  valores por instancia. Hoy la verifica una prueba de `kernel` sobre sus propios códigos; la
  verificación global llega con el mapa de códigos del traductor de `web`.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| La base crece con comportamiento de `web` (estado HTTP, títulos) | El punto 1 de la decisión lo prohíbe; cualquier campo nuevo exige un ADR que reemplace a este. Revisión con `confia-code-reviewer`. |
| Un módulo lanza `RuntimeException` genérica para una regla de negocio | Revisión de código hoy; una regla de ArchUnit puede exigir que las excepciones de los paquetes `domain` hereden de `DomainException` cuando existan esos paquetes (cambio 4). |
| Dos módulos eligen el mismo código | Prueba de unicidad en `kernel` para sus códigos; el mapa de códigos del traductor de `web` rechaza duplicados cuando se introduzca. |
| Un mensaje filtra datos personales | Punto 5 de la decisión; la revisión de seguridad lo trata como defecto. |

## Cumplimiento y verificación

1. **Prueba de contrato en `kernel`** (`DomainExceptionTest`): el constructor rechaza código nulo,
   vacío, con mayúsculas, con guiones al inicio o al final, o de más de 64 caracteres; `code()`
   devuelve el código recibido; la clase es abstracta y extiende `RuntimeException`.
2. **Prueba de catálogo en `kernel`**: enumera cada código que emite el núcleo y afirma que todos
   cumplen el formato y que no hay repetidos.
3. **Pureza del núcleo**: la ejecución `enforce-kernel-purity` del enforcer sigue impidiendo
   cualquier dependencia de compilación o ejecución en `kernel`.
4. **Revisión humana, declarada como tal**: que un error sea de dominio y no de programación (punto
   6) es un juicio que se revisa en cada pull request.

## Referencias

- `openspec/changes/kernel-money-value-object/proposal.md`, decisión D1 y su resolución
- `openspec/changes/kernel-money-value-object/design.md`
- `docs/01-arquitectura.md`, secciones 4 y 7
- `docs/ui-ux/04-patrones-de-interaccion.md`, sección 9
- `CLAUDE.md`, regla 11
- ADR-0002: monolito modular frente a microservicios
- ADR-0004: PostgreSQL y representación monetaria
- ADR-0011: internacionalización y multi-moneda
- ADR-0013: backend en Java con Spring Boot
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)

# Accesibilidad

> **Objetivo declarado: WCAG 2.2 nivel AA.** No es una mejora posterior. Es criterio de aceptación:
> una pantalla con violaciones críticas o serias no se fusiona.

---

## 1. Por qué es requisito y no aspiración

Tres razones concretas para este producto, ninguna de ellas moral.

**El portal lo usan padres de familia, no usuarios de software.** Su rango de edad, visión,
alfabetización digital y calidad de dispositivo es completamente heterogéneo. Un portal accesible
es simplemente un portal que funciona para su audiencia real.

**El panel lo usan personas ocho horas al día.** El contraste insuficiente y los objetivos táctiles
pequeños producen fatiga y errores. En un sistema donde un error tipográfico es un cobro mal
registrado, esto tiene consecuencias financieras.

**El nivel AA es requisito de contratación en muchos países.** Para la ambición internacional
declarada, no cumplirlo cierra mercados enteros. Y retrofitear accesibilidad cuesta varias veces
más que construirla.

---

## 2. Criterios aplicables y qué significan aquí

| Criterio | Qué significa en CONFIA | Verificación |
|---|---|---|
| 1.1.1 Contenido no textual | Todo icono de estado lleva texto o etiqueta accesible. Los gráficos del dashboard tienen tabla equivalente | axe más revisión manual |
| 1.3.1 Información y relaciones | Las tablas usan encabezados con alcance. Los campos de formulario tienen etiqueta asociada. El estado de cuenta usa marcado de lista o tabla, no divisiones sueltas | axe |
| 1.3.5 Identificar el propósito de la entrada | Los campos de contacto del encargado declaran `autocomplete` | Manual |
| 1.4.3 Contraste mínimo | Verificado por token en el documento de tokens. Texto 4.5 a 1, elementos 3 a 1 | Automático sobre los tokens |
| 1.4.4 Cambio de tamaño del texto | Todo en unidades relativas. Nada se rompe al 200 por ciento | Manual |
| 1.4.10 Reajuste | Sin desplazamiento horizontal a 320 píxeles de ancho. Las tablas se vuelven tarjetas | Playwright con ventana estrecha |
| 1.4.11 Contraste no textual | Bordes de campo, anillos de foco e iconos de estado cumplen 3 a 1 | Automático |
| 1.4.13 Contenido al pasar el cursor | Los tooltips se cierran con Escape y no se ocultan al mover el puntero hacia ellos | Manual |
| 2.1.1 Teclado | Todo el sistema es operable sin ratón. Registrar un pago completo sin tocar el ratón es una prueba obligatoria | Playwright |
| 2.1.2 Sin trampas de teclado | El foco entra y sale de todo diálogo | axe más Playwright |
| 2.4.1 Evitar bloques | Enlace de salto al contenido principal | Manual |
| 2.4.3 Orden del foco | Sigue el orden visual. Sin `tabindex` positivos | axe |
| 2.4.7 Foco visible | Anillo de foco obligatorio con su token. Nunca `outline: none` sin reemplazo | Regla de análisis estático |
| 2.4.11 Foco no oscurecido | Una barra fija no puede tapar el elemento enfocado | Manual |
| 2.5.3 Etiqueta en el nombre | El nombre accesible de un botón empieza por su texto visible | axe |
| 2.5.7 Movimientos de arrastre | Toda reordenación por arrastre tiene alternativa por botón o menú | Manual |
| 2.5.8 Tamaño del objetivo | Mínimo 24 por 24 píxeles, y 44 por 44 en el portal móvil | Manual más revisión de tokens |
| 3.2.2 Al recibir entrada | Ningún campo dispara navegación ni envío por cambiar su valor | Manual |
| 3.3.1 Identificación de errores | Los errores se identifican en texto, no solo con color de borde | axe más manual |
| 3.3.2 Etiquetas o instrucciones | Todo campo tiene etiqueta visible. El marcador de posición no sustituye a la etiqueta | axe |
| 3.3.3 Sugerencia ante error | El mensaje dice qué corregir, no solo que hay error | Revisión de contenido |
| 3.3.7 Entrada redundante | El asistente de pago no vuelve a pedir datos ya ingresados en un paso anterior | Manual |
| 3.3.8 Autenticación accesible | El inicio de sesión permite pegar la contraseña y el código de verificación | Manual |
| 4.1.2 Nombre, función, valor | Componentes construidos sobre Radix, que ya lo resuelve. Los propios lo declaran | axe |
| 4.1.3 Mensajes de estado | Los resultados asíncronos se anuncian en regiones activas sin robar el foco | Manual |

---

## 3. Navegación por teclado

### Reglas generales

- **Nada es exclusivo del ratón.** Si una acción solo se alcanza pasando el cursor, es un defecto.
- **Sin `tabindex` positivos.** Reordenan el foco de forma impredecible en toda la página.
- **El foco visible nunca se elimina.** La regla `outline: none` solo se permite si el mismo
  selector define un anillo alternativo con el token de foco.
- **El foco vuelve a su origen.** Al cerrar un diálogo, el foco regresa al elemento que lo abrió.
  Perder el foco al cerrar deja a un usuario de teclado al inicio de la página.
- **Escape cierra** todo elemento superpuesto: diálogo, panel lateral, menú, tooltip.
- **Enlace de salto** al contenido principal como primer elemento enfocable.

### Atajos del panel

Los atajos aceleran al cajero en ventanilla. Se deshabilitan mientras el foco está en un campo de
texto, para no interceptar la escritura.

| Atajo | Acción |
|---|---|
| `/` | Enfocar la búsqueda global |
| `g` luego `p` | Ir a Pagos |
| `g` luego `e` | Ir a Estudiantes |
| `g` luego `c` | Ir a Caja |
| `n` | Nueva acción de la pantalla actual |
| `?` | Mostrar la lista de atajos |
| `Escape` | Cerrar el elemento superpuesto activo |

La lista completa está disponible con `?`, porque un atajo que nadie descubre no existe.

### Diálogos

Radix resuelve el confinamiento del foco, el cierre con Escape y el atributo de modal. Lo que queda
por hacer manualmente:

1. Enfocar el primer elemento interactivo al abrir, no el botón de cerrar.
2. En un diálogo destructivo, enfocar el campo de motivo y **nunca** el botón de confirmar.
3. Devolver el foco al origen al cerrar.

---

## 4. Lectores de pantalla

### Regiones activas

Los resultados asíncronos se anuncian sin robar el foco.

| Situación | Cortesía | Contenido anunciado |
|---|---|---|
| Pago registrado | `polite` | "Pago de L 3,200.00 registrado. Recibo 000-001-01-00001234" |
| Error al registrar | `assertive` | El mensaje de error completo |
| Filtro aplicado a una tabla | `polite` | "47 resultados" |
| Cambio de página de la tabla | `polite` | "Página 2 de 8, mostrando 21 a 40 de 156" |
| Guardado automático | `polite` | "Cambios guardados" |
| Sesión por expirar | `assertive` | Aviso con la acción para extenderla |

**Regla:** `assertive` solo para lo que impide continuar. Usarlo para todo interrumpe la lectura
constantemente y la persona termina apagando el lector.

### Tabla de datos con paginación en servidor

Es el caso que peor se resuelve por defecto. Cuando cambia el filtro o la página, el contenido de la
tabla se reemplaza sin que nada lo anuncie, y un usuario de lector de pantalla no tiene forma de
saber que algo pasó.

Lo obligatorio:

1. La tabla usa `<table>` real, con `<th scope="col">` y `<caption>` que la describe.
2. El orden se declara con `aria-sort` en el encabezado correspondiente.
3. Una región activa fuera de la tabla anuncia el nuevo conteo de resultados.
4. Durante la recarga, la tabla se marca con `aria-busy`.
5. El foco permanece donde estaba. Nunca salta al inicio de la tabla.

---

## 5. Formularios

| Regla | Razón |
|---|---|
| Etiqueta visible siempre, asociada por identificador | El marcador de posición desaparece al escribir y deja el campo sin identificar |
| El error se identifica en texto, no solo con color | Un borde rojo es invisible para quien no distingue el rojo |
| El error se asocia con `aria-describedby` y el campo con `aria-invalid` | El lector anuncia el problema al llegar al campo |
| Resumen de errores al inicio, con enlaces a cada campo | En un formulario largo, el error puede estar fuera de la pantalla |
| El resumen recibe el foco al fallar el envío | Sin esto, quien usa teclado no se entera de que falló |
| Los campos obligatorios se marcan en texto, no solo con asterisco | El asterisco sin leyenda no significa nada |
| `autocomplete` en campos de contacto | Reduce esfuerzo y errores |

### Por qué el botón de envío nunca se deshabilita

Es la regla que más discusión genera y la que más importa.

Un botón deshabilitado no es enfocable, así que un usuario de teclado o de lector de pantalla llega
al final del formulario, encuentra un botón que no responde, y **no recibe ninguna explicación**. No
hay forma de saber qué falta.

El comportamiento correcto: el botón siempre está habilitado. Al pulsarlo con errores, se valida
todo, se muestra el resumen de errores, se enfoca el resumen y se anuncia. La persona sabe
exactamente qué corregir.

La única excepción es el envío en curso, donde el botón muestra estado de carga y se marca con
`aria-busy` para evitar el doble envío. Aun así, la protección real contra el doble cobro es la
clave de idempotencia del servidor, nunca el estado del botón.

---

## 6. Color y contraste

**El color nunca es el único portador de información.** Violaciones concretas que este producto debe
evitar:

| Violación | Corrección |
|---|---|
| Fila de cargo vencido solo con fondo rojo | Fondo más `StatusBadge` con icono y texto |
| Variación del dashboard solo en verde o rojo | Color más flecha más signo explícito |
| Campo con error solo con borde rojo | Borde más icono más mensaje de texto |
| Serie del gráfico distinguida solo por color | Color más patrón o marcador más etiqueta directa |
| Enlace dentro de un párrafo solo por color | Subrayado, o contraste de 3 a 1 contra el texto circundante más otra señal |

Los valores de contraste verificados por token están en
[01-tokens-de-diseno.md](01-tokens-de-diseno.md), sección 5.

---

## 7. Objetivos táctiles

| Contexto | Mínimo | Razón |
|---|---|---|
| Portal en móvil | 44 por 44 píxeles | Padres de familia, dispositivos variados, uso ocasional |
| Panel en escritorio | 32 por 32 píxeles con separación de 8 | Uso frecuente con ratón preciso |
| Acciones de fila en tabla densa | 24 por 24 con área ampliada invisible | El área táctil puede exceder el área visible |
| Mínimo absoluto | 24 por 24 | Criterio 2.5.8 |

---

## 8. Movimiento y zoom

Toda animación respeta la preferencia de movimiento reducido. Ninguna animación es superior a
quinientos milisegundos y ninguna se repite de forma indefinida salvo los indicadores de carga.

Nada parpadea más de tres veces por segundo.

Sobre el zoom: el sistema debe funcionar al doscientos por ciento sin pérdida de contenido ni
desplazamiento horizontal. Esto se logra con unidades relativas, contenedores flexibles, y la
regla de que ningún elemento tiene un ancho mínimo mayor que la pantalla. Solo las tablas, los
diagramas y los bloques de código pueden desplazarse horizontalmente, cada uno dentro de su propio
contenedor con desplazamiento.

---

## 9. Verificación automatizada

### En Playwright

```typescript
import AxeBuilder from '@axe-core/playwright';

test('la pantalla de pagos no tiene violaciones de accesibilidad', async ({ page }) => {
  await page.goto('/pagos');
  await page.getByRole('table', { name: /pagos/i }).waitFor();

  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze();

  const blocking = results.violations.filter(
    (v) => v.impact === 'critical' || v.impact === 'serious',
  );

  expect(blocking).toEqual([]);
});
```

La verificación se ejecuta **después** de que el contenido cargó. Analizar durante el esqueleto de
carga produce un falso verde.

### En Storybook

El complemento de accesibilidad se activa para todos los componentes de `packages/ui`, con
severidad crítica y seria configurada como error.

### Prueba de teclado obligatoria

```typescript
test('se puede registrar un pago completo sin usar el raton', async ({ page }) => {
  await page.goto('/pagos/nuevo');
  // recorrido completo solo con page.keyboard
  // ...
  await expect(page.getByRole('status')).toContainText('Pago registrado');
});
```

---

## 10. Lista de verificación manual

La automatización detecta cerca de un tercio de los problemas reales. Lo siguiente exige revisión
humana antes de dar por terminada una pantalla:

- [ ] Recorrer la pantalla completa solo con teclado, sin tocar el ratón
- [ ] El orden del foco sigue el orden visual
- [ ] El foco es visible en todo momento, incluido sobre fondos de color
- [ ] Al cerrar un diálogo, el foco vuelve al elemento que lo abrió
- [ ] Los mensajes de error se anuncian y son comprensibles fuera de contexto
- [ ] Las etiquetas describen el propósito real, no la implementación
- [ ] Los iconos de acción tienen nombre accesible correcto
- [ ] Al 200 por ciento de zoom no hay desplazamiento horizontal ni contenido perdido
- [ ] A 320 píxeles de ancho la pantalla sigue siendo usable
- [ ] Los importes se leen correctamente con lector de pantalla, con su moneda
- [ ] Ningún estado depende solo del color
- [ ] La navegación con lector de pantalla real, aunque sea una revisión rápida

---

## 11. Puerta de calidad

| Condición | Efecto |
|---|---|
| Violación crítica o seria de axe | **Bloquea la fusión** |
| Violación moderada | Advierte y se registra como tarea |
| Violación menor | Se registra |
| Lista manual sin completar en una pantalla nueva | **Bloquea la fusión** |
| Componente de `packages/ui` sin historia con verificación | **Bloquea la fusión** |

La verificación es automática en integración continua. No depende de que alguien se acuerde bajo
presión de entrega, que es precisamente cuando la accesibilidad se sacrifica.

---

## 12. Documentos relacionados

- [01-tokens-de-diseno.md](01-tokens-de-diseno.md), sección de contraste
- [02-sistema-de-diseno-y-componentes.md](02-sistema-de-diseno-y-componentes.md)
- [04-patrones-de-interaccion.md](04-patrones-de-interaccion.md)
- `docs/06-estrategia-de-testing.md`

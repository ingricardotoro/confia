# Documentación de UI/UX de CONFIA

Estos siete documentos definen cómo se ve, cómo se comporta y cómo habla el producto. No son
sugerencias: son criterio de aceptación.

## Regla de uso

**Ninguna pantalla se implementa sin haber leído los principios, los tokens y el patrón de
interacción que le corresponde.** Una pantalla construida sin esto genera deuda visual que después
nadie repara, porque reparar diseño ya construido siempre pierde contra la siguiente funcionalidad.

## Los documentos

| Documento | Qué contiene | Cuándo leerlo |
|---|---|---|
| [00-principios-de-diseno.md](00-principios-de-diseno.md) | Principios rectores, perfiles de usuario, y la diferencia entre el panel y el portal | Una vez, completo, antes de empezar |
| [01-tokens-de-diseno.md](01-tokens-de-diseno.md) | Color, tipografía, espaciado, movimiento, iconos, y el bloque CSS listo para copiar | Al configurar el proyecto y ante cualquier duda de valor visual |
| [02-sistema-de-diseno-y-componentes.md](02-sistema-de-diseno-y-componentes.md) | Diseño atómico, contenedor y presentación, inventario, y especificación de los componentes de dominio | Antes de crear o usar cualquier componente |
| [03-accesibilidad.md](03-accesibilidad.md) | Criterios de nivel AA aplicados a este producto, verificación automatizada y lista manual | Antes de dar por terminada cualquier pantalla |
| [04-patrones-de-interaccion.md](04-patrones-de-interaccion.md) | Navegación, listados, formularios, confirmaciones, errores y atajos | Antes de construir una pantalla del tipo correspondiente |
| [05-guia-de-contenido-y-voz.md](05-guia-de-contenido-y-voz.md) | Voz, formato de datos, catálogo de mensajes y plantillas de cobranza | Al escribir cualquier texto visible |
| [06-flujos-clave.md](06-flujos-clave.md) | Los trece recorridos críticos con sus criterios de aceptación | Antes de implementar el flujo correspondiente |

## Orden de lectura recomendado

**Primera vez, para entender el producto:** 00, luego 06. Los principios explican el porqué y los
flujos explican qué hace realmente el sistema.

**Para configurar el proyecto:** 01 y 02.

**Para construir una pantalla concreta:** 04 para el patrón, 02 para los componentes, 05 para los
textos, 03 antes de terminar.

## Lista de verificación antes de dar una pantalla por terminada

- [ ] Usa componentes existentes del sistema de diseño, sin colores ni espaciados propios
- [ ] Sigue el patrón de interacción documentado para su tipo
- [ ] Implementa los cuatro estados: carga, vacío, error y éxito
- [ ] Los importes usan el componente de dinero, con dos decimales y numeración tabular
- [ ] Los estados financieros llevan color, icono y texto
- [ ] Todos los textos vienen del catálogo de traducción
- [ ] Los mensajes de error dicen qué pasó y qué hacer
- [ ] Funciona completamente con teclado, con foco visible
- [ ] axe no reporta violaciones críticas ni serias
- [ ] Funciona a 320 píxeles de ancho y al 200 por ciento de zoom
- [ ] Funciona en modo claro y en modo oscuro
- [ ] Las acciones destructivas tienen la fricción que les corresponde

## Relación con el resto del proyecto

- La skill `confia-ui-screen-recipe` convierte estos documentos en un procedimiento paso a paso.
- El agente `confia-uiux-designer` es el custodio de esta carpeta.
- El agente `confia-frontend-dev` debe leer estos documentos antes de construir.
- `docs/adr/ADR-0006-tanstack-frontend.md` y `ADR-0011` contienen las decisiones técnicas de fondo.

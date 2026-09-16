# CONFIA — Control Financiero Académico

Sistema de gestión de pagos estudiantiles, facturación fiscal y cobranza para instituciones
educativas. Operación inicial en Honduras, bajo el régimen fiscal del Servicio de Administración
de Rentas.

**Estado del proyecto:** planificación. No hay código todavía. Este repositorio contiene, por
ahora, la arquitectura, las especificaciones y la organización del trabajo.

---

## Qué es este sistema

Centraliza el registro de pagos estudiantiles, la facturación, el control de caja y la cobranza,
con un libro mayor de doble partida como núcleo financiero. Se compone de tres productos sobre un
mismo backend:

| Producto | Audiencia | Estado |
|---|---|---|
| Panel administrativo | Personal administrativo, caja, contabilidad, auditoría | Fase 1 |
| Portal de encargados | Padres y responsables de pago | Fase 8 |
| Aplicación móvil | Padres y responsables de pago | Fase 12 |

---

## Por dónde empezar a leer

Si es tu primera vez en este repositorio, lee en este orden:

1. **[docs/00-vision-y-alcance.md](docs/00-vision-y-alcance.md)** — qué se construye y para quién.
2. **[docs/01-arquitectura.md](docs/01-arquitectura.md)** — la fuente de verdad de la arquitectura.
3. **[docs/10-analisis-de-brechas.md](docs/10-analisis-de-brechas.md)** — qué faltaba en el
   requerimiento original y por qué importa.
4. **[docs/09-roadmap-y-fases.md](docs/09-roadmap-y-fases.md)** — el plan de entrega.
5. **[CLAUDE.md](CLAUDE.md)** — las reglas no negociables de desarrollo.

---

## Índice de documentación

### Planificación y arquitectura

| Documento | Contenido |
|---|---|
| [00-vision-y-alcance.md](docs/00-vision-y-alcance.md) | Objetivos, actores, alcance y criterios de éxito |
| [01-arquitectura.md](docs/01-arquitectura.md) | Arquitectura del sistema. Fuente de verdad. |
| [02-modelo-de-dominio.md](docs/02-modelo-de-dominio.md) | Entidades, invariantes y lenguaje ubicuo |
| [09-roadmap-y-fases.md](docs/09-roadmap-y-fases.md) | Plan de entrega por fases |
| [10-analisis-de-brechas.md](docs/10-analisis-de-brechas.md) | Módulos faltantes y su justificación |
| [11-riesgos.md](docs/11-riesgos.md) | Registro de riesgos y mitigaciones |
| [14-glosario.md](docs/14-glosario.md) | Glosario de negocio, fiscal y técnico |
| [adr/](docs/adr/) | Registros de decisión de arquitectura |

### Seguridad y cumplimiento

| Documento | Contenido |
|---|---|
| [03-seguridad.md](docs/03-seguridad.md) | Modelo de amenazas y controles |
| [04-cumplimiento-fiscal-sar.md](docs/04-cumplimiento-fiscal-sar.md) | CAI, correlativos, impuestos y notas de crédito |
| [08-datos-privacidad-y-retencion.md](docs/08-datos-privacidad-y-retencion.md) | Datos de menores, consentimiento y retención |
| [seguridad/asvs-nivel-2.md](docs/seguridad/asvs-nivel-2.md) | Registro vivo de cumplimiento del estándar de verificación |
| [seguridad/registro-de-rotacion.md](docs/seguridad/registro-de-rotacion.md) | Bitácora de rotación de secretos |
| [seguridad/contactos-de-incidente.md](docs/seguridad/contactos-de-incidente.md) | Contactos de escalamiento. Mantener copia impresa |

### Construcción y operación

| Documento | Contenido |
|---|---|
| [05-infraestructura-y-despliegue.md](docs/05-infraestructura-y-despliegue.md) | Entornos, contenedores y entrega continua |
| [06-estrategia-de-testing.md](docs/06-estrategia-de-testing.md) | Niveles de prueba y puertas de calidad |
| [07-observabilidad-y-operaciones.md](docs/07-observabilidad-y-operaciones.md) | Logs, métricas, alertas y respaldos |
| [runbooks/](docs/runbooks/) | Procedimientos de operación ante incidentes |
| [15-flujo-de-trabajo-git.md](docs/15-flujo-de-trabajo-git.md) | Ramas, commits y revisión |

### Diseño de interfaz

| Documento | Contenido |
|---|---|
| [ui-ux/00-principios-de-diseno.md](docs/ui-ux/00-principios-de-diseno.md) | Principios y perfiles de usuario |
| [ui-ux/01-tokens-de-diseno.md](docs/ui-ux/01-tokens-de-diseno.md) | Color, tipografía, espaciado y movimiento |
| [ui-ux/02-sistema-de-diseno-y-componentes.md](docs/ui-ux/02-sistema-de-diseno-y-componentes.md) | Inventario y especificación de componentes |
| [ui-ux/03-accesibilidad.md](docs/ui-ux/03-accesibilidad.md) | Requisitos de accesibilidad nivel AA |
| [ui-ux/04-patrones-de-interaccion.md](docs/ui-ux/04-patrones-de-interaccion.md) | Navegación, listados, formularios y confirmaciones |
| [ui-ux/05-guia-de-contenido-y-voz.md](docs/ui-ux/05-guia-de-contenido-y-voz.md) | Redacción de interfaz y formato de datos |
| [ui-ux/06-flujos-clave.md](docs/ui-ux/06-flujos-clave.md) | Recorridos críticos documentados |

### Método de trabajo

| Documento | Contenido |
|---|---|
| [13-metodologia-sdd.md](docs/13-metodologia-sdd.md) | Ciclo dirigido por especificaciones |
| [12-agentes-y-herramientas.md](docs/12-agentes-y-herramientas.md) | Agentes especializados y cuándo usarlos |
| [16-mcp-y-herramientas-externas.md](docs/16-mcp-y-herramientas-externas.md) | Servidores MCP y sus riesgos |
| [openspec/](openspec/) | Especificaciones vigentes y cambios en curso |

---

## Stack

Backend en Java 25 con Spring Boot 4.1, construido con Maven, sobre PostgreSQL. Frontend en
TypeScript con React, la suite TanStack, Tailwind CSS y shadcn/ui. Monorepo con pnpm y Turborepo.
El contrato de API se genera desde el backend como OpenAPI 3.1, y los tipos y esquemas que
consumen los clientes se generan a partir de él.

El detalle y la justificación de cada elección están en
[docs/01-arquitectura.md](docs/01-arquitectura.md), sección 3, y en
[docs/adr/ADR-0013-backend-java-spring-boot.md](docs/adr/ADR-0013-backend-java-spring-boot.md),
que reemplaza a ADR-0001.

---

## Método de trabajo

El proyecto sigue desarrollo dirigido por especificaciones. Ninguna funcionalidad se implementa
sin especificación aprobada.

```
explorar → proponer → especificar → diseñar → tareas → aplicar → verificar → archivar
```

Ver [docs/13-metodologia-sdd.md](docs/13-metodologia-sdd.md).

---

## Siguiente paso

Aprobar o ajustar los registros de decisión de arquitectura en [docs/adr/](docs/adr/), y luego
arrancar la fase de fundaciones con el primer cambio SDD.

---

## Licencia y propiedad

Software desarrollado por SITE-CONNECTION. Todos los derechos reservados.

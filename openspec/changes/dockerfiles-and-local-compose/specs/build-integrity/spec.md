# Delta para Integridad de la construcción

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `dockerfiles-and-local-compose` (F0, cambio 11a)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-09

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BI119` en adelante, a continuación del máximo de la especificación vigente
  (`BI118`).
- Este delta solo añade requisitos: el jar ejecutable como artefacto de la construcción y las dos puertas de
  integración continua sobre los archivos de contenedor. El comportamiento de las imágenes, de `migrate` y de
  Compose vive en la capacidad nueva `container-runtime`.
- Ningún requisito vigente de esta capacidad se modifica ni se elimina.

## ADDED Requirements

### Requisito: La construcción de `apps/api` produce el jar ejecutable sin debilitar sus reglas

El sistema DEBE producir, en la construcción de `apps/api` (`./mvnw verify` en local y el trabajo `backend` en
integración continua), el jar ejecutable `confia-api.jar` del módulo `app`, con el nombre fijo y por el mismo
camino de construcción que ya ejecuta la generación de jOOQ, las pruebas, la cobertura y la mutación. La
producción del jar NO DEBE relajar ni omitir ninguna regla de esta capacidad, y la construcción de las imágenes
NO DEBE reconstruir el jar.

#### Escenario: [BI119] `./mvnw verify` deja el jar ejecutable

- **DADO** un árbol de trabajo limpio con Docker disponible para la generación de jOOQ
- **CUANDO** se ejecuta `./mvnw verify` en `apps/api`
- **ENTONCES** la construcción termina en verde y existe `confia-api.jar`, arrancable con `java -jar`, producido
  por la misma ejecución que corrió las reglas de arquitectura, la cobertura y la mutación

#### Escenario: [BI120] Producir el jar no desactiva ninguna regla

- **DADO** el jar ejecutable producido
- **CUANDO** se introduce una violación de las reglas de esta capacidad (por ejemplo, una importación cruzada de
  `domain`)
- **ENTONCES** `./mvnw verify` falla igual que antes del cambio y no llega a producir el jar

#### Escenario: [BI121] El jar sale una sola vez de la construcción de la API

- **DADO** el trabajo `backend` de integración continua con el jar producido
- **CUANDO** el flujo construye la imagen de la API
- **ENTONCES** la imagen copia el jar de esa construcción y ningún paso de la construcción de la imagen invoca
  Maven

### Requisito: Hadolint rompe la construcción ante un Dockerfile defectuoso

El sistema DEBE ejecutar Hadolint sobre todos los Dockerfiles del repositorio en integración continua, como un
trabajo cuyo fallo rompe la verificación de ese empuje, con resultado visible en el remoto. Un hallazgo no
suprimido DEBE romper el trabajo. Toda supresión DEBE llevar junto a sí el motivo.

#### Escenario: [BI122] Dockerfile con un hallazgo

- **DADO** un empuje que introduce un Dockerfile con una imagen base sin etiqueta de versión
- **CUANDO** la integración continua ejecuta Hadolint
- **ENTONCES** el trabajo falla señalando el archivo y la regla incumplida, y el resultado en rojo queda visible
  en el remoto

#### Escenario: [BI123] Dockerfiles sin hallazgos

- **DADO** los Dockerfiles de la API y de la web tal como los entrega este cambio
- **CUANDO** la integración continua ejecuta Hadolint
- **ENTONCES** el trabajo termina en verde, evaluando archivos reales y no un conjunto vacío

#### Escenario: [BI124] Una supresión sin motivo no se admite

- **DADO** un Dockerfile que suprime una regla de Hadolint sin indicar el motivo
- **CUANDO** se revisa la configuración y los Dockerfiles
- **ENTONCES** la supresión se considera una violación de esta capacidad, igual que la exclusión de una regla
  sin ADR

### Requisito: `docker compose config` valida el archivo base y el overlay y rompe la construcción

El sistema DEBE ejecutar `docker compose config` en integración continua sobre el archivo base y sobre el archivo
base combinado con el overlay de producción, usando los valores de `.env.example`, como un trabajo cuyo fallo
rompe la verificación. La validación NO DEBE depender de un `.env` real ni de secretos del repositorio de CI.

#### Escenario: [BI125] Compose inválido

- **DADO** un empuje que introduce un error de sintaxis o una referencia a un servicio inexistente en el archivo
  base o en el overlay
- **CUANDO** la integración continua ejecuta `docker compose config`
- **ENTONCES** el trabajo falla señalando el archivo y el resultado en rojo queda visible en el remoto

#### Escenario: [BI126] Archivo base y overlay válidos

- **DADO** el archivo base y el overlay tal como los entrega este cambio
- **CUANDO** la integración continua ejecuta `docker compose config` sobre el base y sobre el base combinado con
  el overlay, con los valores de `.env.example`
- **ENTONCES** ambos comandos terminan en verde

#### Escenario: [BI127] La validación no necesita secretos reales

- **DADO** un entorno de integración continua sin ningún secreto real configurado
- **CUANDO** se ejecuta la validación de Compose
- **ENTONCES** termina en verde usando únicamente los marcadores de `.env.example`

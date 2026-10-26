# Validación de entrega

Actualización: 26 de octubre de 2026, America/Bogota. La integración DynamoDB inicial corresponde al 23 de octubre.

## Pruebas unitarias

Ejecutadas con **Eclipse Temurin JDK 25.0.4.1**, Spring Boot 4.0.0, JUnit Jupiter **5.13.4**, Mockito, reactor-test y JaCoCo **0.8.14**.

Resultado de `mvn clean verify` sin habilitar integración:

- **45 tests unitarios correctos**, sin fallos ni errores.
- 2 tests de integración omitidos intencionalmente en esa ejecución.
- **422 de 425 líneas cubiertas: 99,29%.**
- **95 de 96 ramas cubiertas: 98,96%.**
- Sin exclusiones de clases en JaCoCo; bootstrap y configuración también forman parte del denominador.
- El umbral de líneas de 90% está configurado y pasó en `verify`.
- Casos de uso, controladores y workers: 100% de líneas cubiertas.

Evidencia: `reports/unit/coverage/index.html`, `reports/unit/coverage/jacoco.xml`, `reports/unit/tests/` y `reports/unit/build.log`.

## Integración real

El 23 de octubre se ejecutaron correctamente **2 tests contra DynamoDB Local real**, binario oficial reportando versión **1.25.1**, levantado sin Docker. Incluyen 100 órdenes concurrentes sobre capacidad 20, reentregas, idempotencia, auditoría, outbox y liberación por vencimiento. Esa evidencia se conserva en `reports/integration`; no se presenta como una ejecución nueva de AWS.

Los tests usan tablas aisladas con prefijo `ticketing-test-` y las eliminan al terminar. El doble `MemoryDynamo` de la suite unitaria no se utiliza en estas dos pruebas.

El entorno Windows requirió una ruta corta para `jdk.net.unixdomain.tmpdir`; este ajuste fue exclusivo de la ejecución local, no está fijado en el proyecto ni es necesario normalmente en contenedores Linux.

## Extensión AWS: 26 de octubre

- Terraform **1.13.5** y proveedor AWS **6.20.0**: `fmt -check`, `validate` y validación del bootstrap correctos.
- **6 escenarios Terraform correctos**, todos con `command = plan` y proveedor AWS simulado. No se usaron credenciales para desplegar ni se ejecutó apply.
- El backend compiló, empaquetó y pasó JaCoCo con Java 25 tras añadir Actuator, región configurable y perfil AWS. Se probó el uso de región/endpoint en ambos clientes SDK sin hacer llamadas a AWS.
- Smoke test del JAR local: `/actuator/health/readiness` y `/actuator/health/liveness` devolvieron `UP` en localhost:8091. El proceso temporal fue cerrado al terminar.
- El smoke test no tenía DynamoDB/SQS activos: los workers registraron errores de conexión y readiness siguió UP. Esto confirma que health comprueba el proceso y no garantiza disponibilidad del flujo de compras.
- Evidencia: `reports/aws-extension/terraform-tests.txt`, `terraform-validate.txt` y `health-smoke.json`.
- Se entregan diagramas Mermaid editables y un SVG de la topología propuesta. Se comprobó la estructura XML del SVG.

## Alcance pendiente

- Docker Compose no se ejecutó porque Docker no está disponible en el entorno. La imagen Compose fija DynamoDB Local 2.5.4; esa versión exacta no fue la usada en la ejecución sin Docker.
- LocalStack/SQS real y las colecciones Postman completas no se ejecutaron aquí. Los workers SQS sí se probaron con mocks del SDK, incluyendo fallos y orden de acuses.
- El script k6 se entrega, pero no fue ejecutado. No se afirma capacidad medida de miles de solicitudes ni latencias de producción.
- Terraform no se desplegó en AWS. Permanecen pendientes plan con cuenta real, certificado/DNS, conectividad privada, pull de imagen, permisos efectivos, alarmas, recuperación y carga. Los mocks no prueban esos aspectos.

Para completar la validación en una máquina con Docker:

```sh
docker compose up --build -d
docker compose --profile test run --rm tests
# Ejecutar las colecciones con Collection Runner.
k6 run tests/load.js
```

# Terraform AWS: infraestructura propuesta

El módulo define una base privada para ejecutar el backend existente en AWS. **No se ha aplicado ni probado en una cuenta AWS.** Las pruebas con mocks verifican configuración y controles locales; no verifican cuotas, permisos reales, disponibilidad de certificados, pull de imágenes o conectividad de Fargate.

## Qué se define

| Archivo | Responsabilidad |
|---|---|
| `versions.tf` | Versiones, backend S3 parcial, guardrail de cuenta y tags |
| `network.tf` | VPC, dos subredes privadas, rutas, SG y endpoints |
| `data.tf` | DynamoDB, PITR, KMS, SQS/DLQ y ECR |
| `iam.tf` | Separación task role / execution role y recursos permitidos |
| `ingress.tf` | ALB interno HTTPS, ACM existente y access logs S3 |
| `compute.tf` | Fargate, digest inmutable, hardening, rolling deployment y autoscaling |
| `observability.tf` | Logs, Flow Logs, alarmas, dashboard, SNS y presupuesto opcional |
| `bootstrap/` | Bucket S3 de estado versionado/cifrado y KMS dedicado |
| `tests/` | Planes con proveedor AWS simulado, sin recursos reales |
| `environments/` | Ejemplos de entradas para dev/prod |

La región y nombres del ejemplo corresponden a la partición comercial AWS. El certificado debe estar emitido en la misma región y cubrir el nombre privado que usarán los clientes. VPN/peering, rutas de conectividad corporativa, DNS privado y certificado se administran fuera del módulo.

## Validación sin credenciales

Requiere Terraform >= 1.10 y < 2, y descarga del proveedor AWS fijado por `.terraform.lock.hcl`.

```sh
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform test
terraform -chdir=bootstrap init -backend=false
terraform -chdir=bootstrap validate
```

Todos los `run` de las pruebas usan `command = plan` y el proveedor AWS es un mock. No ejecutar `apply` como parte de una validación local. Los seis escenarios comprueban:

1. Red privada en dos zonas, PITR, protección de datos y configuración SQS.
2. Servicio habilitado con digest, dos tareas, root FS de solo lectura y rollback.
3. Rechazo de ingreso 0.0.0.0/0.
4. Rechazo de servicio sin digest.
5. Rechazo de entorno inválido.
6. Rechazo de límites de escalado inconsistentes.

## Estado y aislamiento

Preferir **cuentas distintas por entorno**, roles diferentes y buckets/key prefixes separados. Los archivos tfvars y los workspaces por sí solos no constituyen una frontera de seguridad. `allowed_account_ids` obliga a declarar explícitamente la cuenta destino.

`bootstrap/` crea un bucket de estado con versionado, bloqueo de acceso público, SSE-KMS y política TLS. El bootstrap arranca con estado local: debe migrarse a su propio backend remoto después de crear el bucket, o ser sustituido por el backend corporativo. No guardar su estado local en Git.

El módulo principal usa S3 con `use_lockfile = true`. El rol de CI necesita GetObject/PutObject sobre su state, GetObject/PutObject/DeleteObject sobre su `.tflock`, ListBucket limitado a su prefijo y permisos KMS acotados a la clave del estado. Nunca usar credenciales persistentes dentro de `backend.hcl`.

## Flujo de despliegue para una cuenta autorizada

Los siguientes comandos son una guía para un operador; **no fueron ejecutados para desplegar esta entrega** y crean recursos facturables si se aplican.

1. Configurar IAM Identity Center o un rol de CI por OIDC, con permisos revisados y cuenta destino verificada.
2. Usar el backend corporativo existente, o revisar y aplicar el bootstrap en una operación separada:
   `terraform -chdir=bootstrap init`, `plan` y `apply`, proporcionando `aws_account_id`, `aws_region` y un nombre globalmente único de bucket.
3. Copiar `backend.hcl.example` a un archivo local, reemplazar bucket/key/región y configurar aislamiento de cuenta y permisos.
4. Copiar `environments/dev.tfvars.example` a `dev.tfvars`; reemplazar cuenta, CIDRs, certificado, owner y centro de costos.
5. Inicializar y revisar el plan:

```sh
terraform init -backend-config=backend.hcl
terraform plan -var-file=dev.tfvars -out=foundation.tfplan
# Aplicar solo tras revisión del plan y autorización para crear recursos:
terraform apply foundation.tfplan
terraform output -raw ecr_repository_url
```

El primer despliegue usa `enable_service = false`: crea infraestructura y ECR, pero no arranca tareas con una imagen inexistente. Aun así genera cargos por endpoints, ALB y otros recursos.

6. Construir la imagen desde la raíz del repositorio, autenticarse contra ECR con credenciales temporales y publicar una etiqueta única de release:

```sh
docker build --platform linux/amd64 -t REPOSITORY_URL:RELEASE_ID .
aws ecr get-login-password --region REGION | docker login --username AWS --password-stdin REGISTRY_HOST
docker push REPOSITORY_URL:RELEASE_ID
aws ecr describe-image-scan-findings --repository-name REPOSITORY_NAME --image-id imageTag=RELEASE_ID --region REGION
aws ecr describe-images --repository-name REPOSITORY_NAME --image-ids imageTag=RELEASE_ID --region REGION
```

Esperar la finalización del scan y aplicar la política de vulnerabilidades de la organización. El módulo activa scan-on-push; **no contiene un gate automático que bloquee vulnerabilidades**.

7. Copiar el digest `sha256:...` a `image_digest`, establecer `enable_service = true` y revisar un segundo plan antes de aplicarlo.
8. Desde una red privada autorizada, configurar el nombre DNS cubierto por ACM hacia el ALB interno. No usar su hostname generado como si coincidiera con el certificado.
9. Comprobar readiness, contratos API, rotación de credenciales, alarmas, rollback y prueba de carga. La conectividad VPN/peering necesita rutas de ida/vuelta y no la crea este módulo.

Se usa `SPRING_PROFILES_ACTIVE=aws`, endpoints regionales HTTPS y `DefaultCredentialsProvider` con task role. No hay access keys en la task definition. El perfil AWS exige tabla y URL de cola y evita los endpoints localhost de desarrollo.

## Networking y seguridad

- Ninguna tarea tiene IP pública. No hay Internet Gateway ni NAT.
- ALB interno acepta 443 desde CIDRs explícitos. Solo su SG puede llegar al 8080 de tareas.
- Las tareas acceden por 443 a los interface endpoints y a los prefix lists DynamoDB/S3. Solo se permite además el endpoint link-local de credenciales Fargate.
- ECR necesita los endpoints API y DKR y acceso al bucket regional de capas por S3. Logs, SQS y Secrets Manager tienen endpoints privados.
- La política del gateway DynamoDB está limitada al task role y a la tabla/índices; las políticas de interface endpoints filtran task/execution roles y los permisos efectivos se acotan en IAM.
- Task role permite operaciones del inventario y cola principal. No permite administración de IAM, creación de tablas, lectura del DLQ ni borrado del repositorio.
- Las transacciones DynamoDB necesitan permisos de las operaciones subyacentes, incluido `ConditionCheckItem`; no se inventa un permiso `TransactWriteItems` para reemplazarlas.
- Execution role solo descarga esa imagen, escribe sus logs y, opcionalmente, lee un secreto existente. El valor del secreto no se introduce en Terraform.
- El secreto de cortesías es opcional; sin él el endpoint queda deshabilitado. Rotarlo requiere redeploy porque ECS lo inyecta al arranque.
- KMS por entorno cifra datos/colas/logs/ECR. Se separa la clave de estado. Para separación de funciones más estricta, usar claves diferentes por servicio.
- Los access logs del ALB usan SSE-S3 por requisito del servicio; no se afirma SSE-KMS en ese bucket.
- Fargate usa usuario 10001, root FS de solo lectura y volumen efímero dedicado en `/app/tmp`. El Dockerfile declara VOLUME y permisos para el UID. Verificar ese montaje con la imagen final durante aceptación AWS.
- HTTPS termina en ALB; el salto a la tarea usa HTTP privado. No hay TLS extremo a extremo implementado.
- No se crea identidad OIDC ni autorización de usuarios: **red privada no sustituye autenticación**.

## Escalabilidad y despliegues

Dos tareas mínimas y despliegue 100/200 permiten reemplazos con capacidad adicional. Circuit breaker intenta rollback si no estabiliza la nueva revisión. Los límites de cuota y disponibilidad siguen sujetos a AWS.

Target tracking de CPU escala entre mínimos y máximos. La API, consumidores, publicador y expirador todavía comparten el mismo proceso, por lo que todas sus capacidades escalan juntas. CPU no detecta por sí sola backlog de I/O. Una evolución debe separar roles de ejecución y escalar consumidores con backlog por tarea, sin saturar la clave de inventario.

DynamoDB on-demand no elimina claves calientes ni la contención de una transacción por evento. Los GSI ACTIVE y OUTBOX también requieren particionarse según la carga. No añadir réplicas indefinidamente para resolver conflictos de base de datos.

## Protección y retiro

DynamoDB tiene PITR y deletion protection; tabla, KMS, ECR y buckets usan `prevent_destroy`; ALB tiene deletion protection. Por ello un `terraform destroy` general puede fallar deliberadamente.

El retiro requiere un plan separado: respaldos/exportaciones, revisión de obligaciones de retención, detención de productores/consumidores, reconciliación y cambio explícito revisado de las protecciones. No se incluye un script que vacíe buckets o desactive protecciones automáticamente.

## Fuentes

- [Fargate y endpoints privados](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/vpc-endpoints.html)
- [Endpoints gateway sin cargo adicional](https://docs.aws.amazon.com/vpc/latest/privatelink/gateway-endpoints.html)
- [IAM y transacciones DynamoDB](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis-iam.html)
- [ALB: acceso y cifrado de logs](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/enable-access-logging.html)
- [Bind mounts y permisos Fargate](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/bind-mounts.html)
- [Backend S3 y lockfile](https://developer.hashicorp.com/terraform/language/backend/s3)
- [Tests con proveedor simulado](https://developer.hashicorp.com/terraform/language/tests/mocking)

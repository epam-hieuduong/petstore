# PetStore Azure Deployment Steps (All Services)

Manual, copy-pasteable Azure CLI steps to deploy the entire PetStore stack:
`petstorepetservice`, `petstoreproductservice`, `petstoreorderservice`, and
`petstoreapp` as containers on Azure App Service (Part A), plus
`petstoreorderitemsreserver` as a container Azure Function connected via
Service Bus with a Logic App dead-letter fallback (Part B).

Pet and Product services are backed by Azure Database for PostgreSQL. Order
service is backed by Azure Cosmos DB and publishes to Service Bus on every
cart update; OrderItemsReserver consumes those messages and uploads order
JSON to Blob Storage.

> Run these in PowerShell. Requires Azure CLI (`az`) and Docker installed, and `az login` completed.

> Parts A and B share the same `$RG`/`$LOCATION`/`$ACR_NAME` set in Part A
> step 0 - there's no technical requirement to split them across resource
> groups or regions.

---

# Part A: Core services (Pet, Product, Order, App)

## 0. Set shared variables

```powershell
$RG        = "petstore-rg"
$LOCATION  = "centralus"
$ACR_NAME  = "petstoreacr1234"
$PLAN_NAME = "asp-petstore-centralus"
```

## 1. Create resource group + Azure Container Registry (ACR)

```powershell
az group create --name $RG --location $LOCATION

az acr create --resource-group $RG --name $ACR_NAME --sku Basic --admin-enabled true
```

## 2. Docker build + push each service to ACR

```powershell
az acr login --name $ACR_NAME
$ACR_LOGIN_SERVER = az acr show --name $ACR_NAME --query loginServer -o tsv

# Pet Service
docker build -t "$ACR_LOGIN_SERVER/petstorepetservice:latest" ../petstorepetservice
docker push "$ACR_LOGIN_SERVER/petstorepetservice:latest"

# Product Service
docker build -t "$ACR_LOGIN_SERVER/petstoreproductservice:latest" ../petstoreproductservice
docker push "$ACR_LOGIN_SERVER/petstoreproductservice:latest"

# Order Service
docker build -t "$ACR_LOGIN_SERVER/petstoreorderservice:latest" ../petstoreorderservice
docker push "$ACR_LOGIN_SERVER/petstoreorderservice:latest"

# PetStore App (front end)
docker build -t "$ACR_LOGIN_SERVER/petstoreapp:latest" ../petstoreapp
docker push "$ACR_LOGIN_SERVER/petstoreapp:latest"
```

## 3. Create App Service Plan (Linux)

```powershell
az appservice plan create `
    --name $PLAN_NAME `
    --resource-group $RG `
    --location $LOCATION `
    --is-linux `
    --sku B3
```

> Running 4 Java containers on a single B1 plan can be slow to start / time out on log streaming. If you hit that, scale up:
> ```powershell
> az appservice plan update --name $PLAN_NAME --resource-group $RG --sku S1
> ```

## 4. Create a Web App per service, pointing at the ACR image

```powershell
$ACR_USER = az acr credential show --name $ACR_NAME --query username -o tsv
$ACR_PASS = az acr credential show --name $ACR_NAME --query "passwords[0].value" -o tsv

# Pet Service
az webapp create `
    --resource-group $RG `
    --plan $PLAN_NAME `
    --name petstore-petservice `
    --deployment-container-image-name "$ACR_LOGIN_SERVER/petstorepetservice:latest"

az webapp config container set `
    --resource-group $RG `
    --name petstore-petservice `
    --container-image-name "$ACR_LOGIN_SERVER/petstorepetservice:latest" `
    --container-registry-url "https://$ACR_LOGIN_SERVER" `
    --container-registry-user $ACR_USER `
    --container-registry-password $ACR_PASS

# Product Service
az webapp create `
    --resource-group $RG `
    --plan $PLAN_NAME `
    --name petstore-productservice `
    --deployment-container-image-name "$ACR_LOGIN_SERVER/petstoreproductservice:latest"

az webapp config container set `
    --resource-group $RG `
    --name petstore-productservice `
    --container-image-name "$ACR_LOGIN_SERVER/petstoreproductservice:latest" `
    --container-registry-url "https://$ACR_LOGIN_SERVER" `
    --container-registry-user $ACR_USER `
    --container-registry-password $ACR_PASS

# Order Service
az webapp create `
    --resource-group $RG `
    --plan $PLAN_NAME `
    --name petstore-orderservice `
    --deployment-container-image-name "$ACR_LOGIN_SERVER/petstoreorderservice:latest"

az webapp config container set `
    --resource-group $RG `
    --name petstore-orderservice `
    --container-image-name "$ACR_LOGIN_SERVER/petstoreorderservice:latest" `
    --container-registry-url "https://$ACR_LOGIN_SERVER" `
    --container-registry-user $ACR_USER `
    --container-registry-password $ACR_PASS

# PetStore App (front end)
az webapp create `
    --resource-group $RG `
    --plan $PLAN_NAME `
    --name petstore-app `
    --deployment-container-image-name "$ACR_LOGIN_SERVER/petstoreapp:latest"

az webapp config container set `
    --resource-group $RG `
    --name petstore-app `
    --container-image-name "$ACR_LOGIN_SERVER/petstoreapp:latest" `
    --container-registry-url "https://$ACR_LOGIN_SERVER" `
    --container-registry-user $ACR_USER `
    --container-registry-password $ACR_PASS
```

All four Dockerfiles `EXPOSE 8080` and the apps default to port 8080, so no `WEBSITES_PORT` app setting is needed. If you override `PETSTOREPETSERVICE_SERVER_PORT` / `PETSTOREPRODUCTSERVICE_SERVER_PORT` / `PETSTOREORDERSERVICE_SERVER_PORT` / `PETSTOREAPP_SERVER_PORT`, set `WEBSITES_PORT` to match.

> Spring Boot cold-start on a constrained plan (B1/S1) can take 2-4 minutes, but Azure's container warm-up probe defaults to a 230s timeout and kills the container just before it finishes booting (visible as `ContainerTimeout` / `Container did not start within expected time limit of 230s` in `az webapp log tail`). Raise the limit for each app right after creating it:
> ```powershell
> foreach ($app in @("petstore-petservice","petstore-productservice","petstore-orderservice","petstore-app")) {
>     az webapp config appsettings set --resource-group $RG --name $app --settings WEBSITES_CONTAINER_START_TIME_LIMIT=460
> }
> ```
>
> Also enable **Always On** for every app - without it, an idle app is unloaded and cold-starts (1-4 min) on the next request, which will blow past `petstoreapp`'s Feign client read timeout when it calls the pet/product/order services:
> ```powershell
> foreach ($app in @("petstore-petservice","petstore-productservice","petstore-orderservice","petstore-app")) {
>     az webapp config set --resource-group $RG --name $app --always-on true
> }
> ```

## 5. Create Azure Database for PostgreSQL Flexible Server + databases

```powershell
$PG_SERVER   = "petstore-pg-01"      # must be globally unique
$PG_ADMIN    = "petstoreadmin"
$PG_PASSWORD = "<choose-a-strong-password>"

az postgres flexible-server create `
    --resource-group $RG `
    --name $PG_SERVER `
    --location $LOCATION `
    --admin-user $PG_ADMIN `
    --admin-password $PG_PASSWORD `
    --sku-name Standard_B1ms `
    --tier Burstable `
    --storage-size 32 `
    --version 16 `
    --public-access 0.0.0.0

az postgres flexible-server db create `
    --resource-group $RG --server-name $PG_SERVER --name petstorepetservice_db

az postgres flexible-server db create `
    --resource-group $RG --server-name $PG_SERVER --name petstoreproductservice_db
```

## 6. Allow access through the Postgres firewall

```powershell
# Allow Azure services (App Service) to reach the server
az postgres flexible-server firewall-rule create `
    --resource-group $RG --server-name $PG_SERVER --name AllowAzureServices `
    --start-ip-address 0.0.0.0 --end-ip-address 0.0.0.0

# Allow your local machine (for running psql below) - replace with your public IP
$MY_IP = (Invoke-RestMethod -Uri "https://api.ipify.org?format=text")
az postgres flexible-server firewall-rule create `
    --resource-group $RG --server-name $PG_SERVER --name AllowMyMachine `
    --start-ip-address $MY_IP --end-ip-address $MY_IP
```

## 7. Seed the databases with schema + sample data

```powershell
$PG_FQDN = "$PG_SERVER.postgres.database.azure.com"

psql "host=$PG_FQDN port=5432 dbname=petstorepetservice_db user=$PG_ADMIN password=$PG_PASSWORD sslmode=require" `
    -f ../petstorepetservice/src/main/resources/scripts/petservice.sql

psql "host=$PG_FQDN port=5432 dbname=petstoreproductservice_db user=$PG_ADMIN password=$PG_PASSWORD sslmode=require" `
    -f ../petstoreproductservice/src/main/resources/scripts/productservice.sql
```

## 8. Create Azure Cosmos DB for Order Service

```powershell
$COSMOS_ACCOUNT = "petstore-cosmos-01"      # must be globally unique

az cosmosdb create `
    --resource-group $RG `
    --name $COSMOS_ACCOUNT `
    --locations regionName=$LOCATION `
    --capabilities EnableServerless `
    --default-consistency-level Session

az cosmosdb sql database create `
    --resource-group $RG `
    --account-name $COSMOS_ACCOUNT `
    --name petstoreorderservice_db

az cosmosdb sql container create `
    --resource-group $RG `
    --account-name $COSMOS_ACCOUNT `
    --database-name petstoreorderservice_db `
    --name orders `
    --partition-key-path /id

$COSMOS_URI = az cosmosdb show --resource-group $RG --name $COSMOS_ACCOUNT --query documentEndpoint -o tsv
$COSMOS_KEY = az cosmosdb keys list --resource-group $RG --name $COSMOS_ACCOUNT --query primaryMasterKey -o tsv
```

> Serverless mode (`EnableServerless`) is used here for lowest cost - pay per request, no provisioned throughput to manage. Not all regions support serverless; if `cosmosdb create` fails with a capability/region error, drop `--capabilities EnableServerless` and add `--locations regionName=$LOCATION failoverPriority=0` with a `--throughput` on the container/database, or try a different region.

## 9. (Optional) Store PostgreSQL credentials in Azure Key Vault

> **This step is optional.** Step 10 below configures `petstore-petservice`/`petstore-productservice` with plain-text `PGHOST`/`PGUSER`/`PGPASSWORD` app settings by default (same treatment as `COSMOS_URI`/`COSMOS_KEY` for the Order Service) - skip straight to step 10 if you don't need Key Vault-backed secrets. Do this step first only if you want the hardened setup where credentials live in Key Vault instead of as plain app settings.

`petstorepetservice` and `petstoreproductservice` already read `PGHOST` / `PGUSER` / `PGPASSWORD` as environment variables (see `spring.datasource.url/username/password` in each service's `application.yml`), so no code changes are needed - only the *source* of those app settings changes, from plain text to Key Vault references.

### 9.1 Create the Key Vault

```powershell
$KV_NAME = "petstore-kv-1234"    # must be globally unique

az keyvault create `
    --resource-group $RG `
    --name $KV_NAME `
    --location $LOCATION
```

> This uses the default **access policy** permission model (not RBAC), since step 9.4 below grants access via an explicit Key Vault access policy per app.

> **If step 9.2 fails with `(Forbidden) ... ForbiddenByRbac`:** your subscription/CLI defaulted the new vault to the **RBAC** authorization model instead of access policies. Check with:
> ```powershell
> az keyvault show --name $KV_NAME --query properties.enableRbacAuthorization
> ```
> If `true`, switch it to access policies and grant yourself a policy so you can manage secrets (propagation can take a minute):
> ```powershell
> az keyvault update --name $KV_NAME --resource-group $RG --enable-rbac-authorization false
>
> $MY_OBJECT_ID = az ad signed-in-user show --query id -o tsv
> az keyvault set-policy --name $KV_NAME --object-id $MY_OBJECT_ID --secret-permissions get list set delete
> ```

### 9.2 Store the DB host, username and password as secrets

```powershell
az keyvault secret set --vault-name $KV_NAME --name petstore-pg-host     --value $PG_FQDN
az keyvault secret set --vault-name $KV_NAME --name petstore-pg-user     --value $PG_ADMIN
az keyvault secret set --vault-name $KV_NAME --name petstore-pg-password --value $PG_PASSWORD
```

> `PGDATABASE` differs per app (`petstorepetservice_db` vs `petstoreproductservice_db`) and isn't sensitive, so it stays a plain app setting in step 10 rather than a Key Vault secret.

### 9.3 Enable managed identity for the two apps that use PostgreSQL

```powershell
$PETSERVICE_PRINCIPAL_ID = az webapp identity assign `
    --resource-group $RG --name petstore-petservice --query principalId -o tsv

$PRODUCTSERVICE_PRINCIPAL_ID = az webapp identity assign `
    --resource-group $RG --name petstore-productservice --query principalId -o tsv
```

### 9.4 Grant each app's managed identity a Key Vault access policy

```powershell
az keyvault set-policy `
    --name $KV_NAME --object-id $PETSERVICE_PRINCIPAL_ID `
    --secret-permissions get list

az keyvault set-policy `
    --name $KV_NAME --object-id $PRODUCTSERVICE_PRINCIPAL_ID `
    --secret-permissions get list
```

### 9.5 Get the secret URIs

```powershell
$PG_HOST_SECRET_URI     = az keyvault secret show --vault-name $KV_NAME --name petstore-pg-host     --query id -o tsv
$PG_USER_SECRET_URI     = az keyvault secret show --vault-name $KV_NAME --name petstore-pg-user     --query id -o tsv
$PG_PASSWORD_SECRET_URI = az keyvault secret show --vault-name $KV_NAME --name petstore-pg-password --query id -o tsv
```

> These URIs include a version segment (e.g. `.../secrets/petstore-pg-host/<version>`). Strip the trailing `/<version>` before using them below so Key Vault always resolves the latest version and secret rotation doesn't require re-deploying app settings.

> **Troubleshooting:** if the app setting shows a resolution error in the Portal's "Environment variables" blade instead of "Resolved", the most common causes are a missing `list` permission in the access policy (step 9.4), the managed identity not yet propagated (wait ~1 minute and restart), or the Postgres firewall rule from step 6 not allowing Azure services.

## 10. Configure App Settings on each Web App

> **Windows/PowerShell gotcha:** `az` on Windows is a `.cmd` wrapper that shells out through `cmd.exe`. Even when a value containing parentheses is quoted in PowerShell (e.g. a Key Vault reference, see the alternative below), the wrapper can drop the quotes before invoking Python, and `cmd.exe` then treats the bare `(`/`)` as its own grouping syntax - failing with `'PGPORT' was unexpected at this time.` (a `cmd.exe` parser error, not a PowerShell one). Passing settings from a JSON file avoids this entirely, since only the filename ever reaches the command line.

```powershell
# Pet Service - plain-text PostgreSQL connection settings
az webapp config appsettings set `
    --resource-group $RG --name petstore-petservice `
    --settings PGHOST=$PG_FQDN PGPORT=5432 PGDATABASE=petstorepetservice_db PGUSER=$PG_ADMIN PGPASSWORD=$PG_PASSWORD

# Product Service - plain-text PostgreSQL connection settings
az webapp config appsettings set `
    --resource-group $RG --name petstore-productservice `
    --settings PGHOST=$PG_FQDN PGPORT=5432 PGDATABASE=petstoreproductservice_db PGUSER=$PG_ADMIN PGPASSWORD=$PG_PASSWORD

# Order Service - Cosmos DB connection + needs to reach Product Service for order enrichment
az webapp config appsettings set `
    --resource-group $RG --name petstore-orderservice `
    --settings COSMOS_URI=$COSMOS_URI COSMOS_KEY=$COSMOS_KEY COSMOS_DATABASE=petstoreorderservice_db PETSTOREPRODUCTSERVICE_URL="https://petstore-productservice.azurewebsites.net"

# PetStore App (front end) - needs to reach all backend services
az webapp config appsettings set `
    --resource-group $RG --name petstore-app `
    --settings `
        PETSTOREPETSERVICE_URL="https://petstore-petservice.azurewebsites.net" `
        PETSTOREPRODUCTSERVICE_URL="https://petstore-productservice.azurewebsites.net" `
        PETSTOREORDERSERVICE_URL="https://petstore-orderservice.azurewebsites.net" `
        PETSTORE_SECURITY_ENABLED="false" `
        APPLICATIONINSIGHTS_ENABLED="false"
```

> Verify after restarting (step 11) with:
> ```powershell
> az webapp config appsettings list --resource-group $RG --name petstore-petservice --query "[?name=='PGHOST']"
> ```
> If you used step 9's Key Vault-backed alternative instead, replace the `--settings` lines above with `PGHOST/PGUSER/PGPASSWORD` values of `"@Microsoft.KeyVault(SecretUri=$PG_HOST_SECRET_URI)"` etc. (passed via a JSON file per the Windows/PowerShell gotcha above), and check the "Resolved"/error status in the Portal's App Service > Environment variables blade.

## 11. Restart the apps and verify

```powershell
az webapp restart --resource-group $RG --name petstore-petservice
az webapp restart --resource-group $RG --name petstore-productservice
az webapp restart --resource-group $RG --name petstore-orderservice
az webapp restart --resource-group $RG --name petstore-app
```

- PetStore App: `https://petstore-app.azurewebsites.net/`
- Pet Service Swagger: `https://petstore-petservice.azurewebsites.net/swagger-ui.html`
- Product Service Swagger: `https://petstore-productservice.azurewebsites.net/swagger-ui.html`
- Order Service Swagger: `https://petstore-orderservice.azurewebsites.net/swagger-ui.html`

Try `GET /petstorepetservice/v2/pet/all` and `GET /petstoreproductservice/v2/product/all` (adjust path per each service's actual controller mapping) to confirm data is returned from PostgreSQL. Then open the PetStore App URL, browse pets/products, add to cart, and place an order to confirm the full flow works end-to-end. Confirm the order document appears in the Cosmos DB `orders` container (Data Explorer in the Portal), and that it survives an `az webapp restart` of `petstore-orderservice` (proves it's no longer in-memory).

## Redeploying after code changes (Part A)

Repeat step 2 (build + push new image tag or `:latest`) for the service you changed, then restart the corresponding Web App:

```powershell
docker build -t "$ACR_LOGIN_SERVER/petstorepetservice:latest" ../petstorepetservice
docker push "$ACR_LOGIN_SERVER/petstorepetservice:latest"
az webapp restart --resource-group $RG --name petstore-petservice
```

---

# Part B: OrderItemsReserver (Service Bus + Function App + Logic App fallback)

`petstoreorderservice` (already deployed in Part A) publishes an order JSON
message to a Service Bus queue on every cart update. `petstoreorderitemsreserver`
is a separate container Azure Function, triggered by that queue, which uploads
the order JSON to Blob Storage. If it fails 3 times, Service Bus dead-letters
the message and a Logic App emails a manager as a fallback.

## B0. Variables

Reuses `$RG`/`$LOCATION`/`$ACR_NAME` from Part A step 0.

```powershell
$SB_NS          = "petstoreservicebus1234"    # 6-50 chars, globally unique
$SB_QUEUE       = "order-items-reservation"
$STORAGE_NAME   = "petstoreitemsreserve01"    # 3-24 lowercase alphanumeric, globally unique
$FUNC_PLAN_NAME = "asp-petstorefunction-centralus"
$FUNC_NAME      = "petstore-orderitemsreserver"
$IMAGE_NAME     = "petstoreorderitemsreserver:v1.0.0"
```

> If you deploy the Function App with `mvn azure-functions:deploy` instead of
> the CLI/container steps below, that plugin's `<appName>`/`<resourceGroup>`/
> `<region>` in `petstoreorderitemsreserver/pom.xml` are configured
> independently and won't automatically match `$FUNC_NAME`/`$RG`/`$LOCATION`
> here - update both places consistently if you use that flow.

## B1. Create the Service Bus namespace + queue

Standard tier is required for dead-letter queue (DLQ) support. `--max-delivery-count 3`
means Service Bus dead-letters a message after 3 failed delivery attempts if
the function throws/abandons it - this backs up the function's own in-code
retry-then-throw logic (`BlobStorageService.uploadOrder`, up to 3 attempts).

```powershell
az servicebus namespace create `
  --resource-group $RG `
  --name $SB_NS `
  --location $LOCATION `
  --sku Standard

az servicebus queue create `
  --resource-group $RG `
  --namespace-name $SB_NS `
  --name $SB_QUEUE `
  --max-delivery-count 3 `
  --default-message-time-to-live P14D

$SB_CONN = az servicebus namespace authorization-rule keys list `
  --resource-group $RG `
  --namespace-name $SB_NS `
  --name RootManageSharedAccessKey `
  --query primaryConnectionString -o tsv
```

## B2. Point petstoreorderservice (publisher) at the queue

```powershell
az webapp config appsettings set `
  --resource-group $RG `
  --name petstore-orderservice `
  --settings "SERVICEBUS_CONNECTION_STRING=$SB_CONN" "SERVICEBUS_QUEUE_NAME=$SB_QUEUE"

az webapp restart --resource-group $RG --name petstore-orderservice
```

## B3. Storage account for the Function (runtime + reservation blobs)

One account, two containers: `azure-webjobs-*` (auto-created by the Functions runtime) and `orderitemsreserver` (order JSON blobs).

```powershell
az storage account create `
  --name $STORAGE_NAME `
  --resource-group $RG `
  --location $LOCATION `
  --sku Standard_LRS `
  --kind StorageV2

$STORAGE_CONN = az storage account show-connection-string `
  --name $STORAGE_NAME --resource-group $RG --query connectionString -o tsv

az storage container create `
  --name orderitemsreserver `
  --connection-string $STORAGE_CONN
```

## B4. Build and push the Function container image

Run from the `petstoreorderitemsreserver` directory (uses the `Dockerfile` already in this module).

```powershell
az acr build --registry $ACR_NAME --image $IMAGE_NAME .
```

## B5. Linux App Service Plan + Function App from the container image

B1 (Basic) is sufficient for a Linux custom-container Function App; bump to a Premium plan (EP1) later if you need faster cold starts or VNet integration.

```powershell
az appservice plan create `
  --name $FUNC_PLAN_NAME `
  --resource-group $RG `
  --location $LOCATION `
  --sku B1 `
  --is-linux

$ACR_LOGIN_SERVER = az acr show --name $ACR_NAME --query loginServer -o tsv
$ACR_USERNAME     = az acr credential show --name $ACR_NAME --query username -o tsv
$ACR_PASSWORD     = az acr credential show --name $ACR_NAME --query "passwords[0].value" -o tsv

az functionapp create `
  --name $FUNC_NAME `
  --resource-group $RG `
  --plan $FUNC_PLAN_NAME `
  --storage-account $STORAGE_NAME `
  --functions-version 4 `
  --image "$ACR_LOGIN_SERVER/$IMAGE_NAME" `
  --registry-username $ACR_USERNAME `
  --registry-password $ACR_PASSWORD
```

> If ACR admin user is disabled, enable it first: `az acr update --name $ACR_NAME --admin-enabled true` - or switch to a managed identity + `az functionapp identity assign` + `az role assignment create` (AcrPull) instead of admin credentials.

## B6. Function App settings (Blob Storage + Service Bus)

```powershell
az functionapp config appsettings set `
  --name $FUNC_NAME `
  --resource-group $RG `
  --settings `
    "BLOB_STORAGE_CONNECTION_STRING=$STORAGE_CONN" `
    "BLOB_STORAGE_CONTAINER_NAME=orderitemsreserver" `
    "SERVICEBUS_CONNECTION=$SB_CONN" `
    "SERVICEBUS_QUEUE_NAME=$SB_QUEUE" `
    "WEBSITES_ENABLE_APP_SERVICE_STORAGE=false" `
    "FUNCTIONS_WORKER_RUNTIME=java"

az webapp restart --resource-group $RG --name $FUNC_NAME
```

> App settings changes normally trigger an automatic restart, but restarting explicitly avoids relying on that timing before B7's verification.

## B7. Verify

```powershell
az functionapp show --name $FUNC_NAME --resource-group $RG --query state -o tsv   # should be "Running"
```

The Azure CLI has no data-plane command to send a message directly to a queue
(`az servicebus queue` only supports `create`/`list`/`show`/`update`), so send
a test message one of these ways instead:
- **Easiest**: update the cart from the PetStoreApp UI - this exercises the
  real end-to-end path through `petstoreorderservice`.
- **Manual test message**: Portal > the `$SB_NS` namespace > `$SB_QUEUE` >
  **Service Bus Explorer (preview)** > **Send messages**, with body
  `{"id":"test-session-1","email":"test@example.com","complete":false,"products":[{"id":1,"quantity":2}]}`.

Then check the `orderitemsreserver` container in `$STORAGE_NAME` for
`order-test-session-1.json` (or `order-<sessionId>.json` for the UI test), and
`az functionapp function list --name $FUNC_NAME --resource-group $RG -o table`
/ `az webapp log tail --name $FUNC_NAME --resource-group $RG` to confirm the
`reserveOrderItems` Service Bus trigger fired.

## B8. Logic App: dead-letter monitoring + email fallback

When `reserveOrderItems` exhausts its 3 in-process upload retries and throws,
the Service Bus message is left uncompleted and redelivered. Once the queue's
`max-delivery-count` (3, from step B1) is reached, Service Bus automatically
moves the message to the dead-letter sub-queue (`$SB_QUEUE/$DeadLetterQueue`).
This Logic App watches that sub-queue and emails a manager so the order can be
reserved manually.

The Service Bus connector uses a SAS connection string (no interactive
consent needed), but the email connector (Outlook.com or Office 365) needs a
one-time interactive sign-in, so build this via the **Logic App Designer in
the Azure Portal** rather than pure CLI/ARM.

1. Create an empty Consumption Logic App. As with step 10's app settings, an
   inline JSON string hits the same Windows/`cmd.exe` quote-mangling issue
   (`az` shells out through `cmd.exe`, which strips the quotes before the
   JSON reaches the parser, causing a `Shorthand Syntax Error`) - pass it from
   the checked-in `infra/logic-app-empty-definition.json` file instead. Note
   `az logic workflow create --definition` expects an ARM resource fragment
   with the workflow nested under a top-level `definition` key (not the raw
   Workflow Definition Language JSON on its own), and it takes a plain file
   path (no `@` prefix needed):
   ```powershell
   $LOGICAPP_NAME = "petstore-orderitemsreserver-dlq-fallback"

   az logic workflow create `
     --resource-group $RG `
     --location $LOCATION `
     --name $LOGICAPP_NAME `
     --definition .\infra\logic-app-empty-definition.json
   ```
   > First run adds the `logic` CLI extension - accept the prompt, or
   > pre-approve with `az config set extension.use_dynamic_install=yes_without_prompt`.
2. Portal > the `$LOGICAPP_NAME` Logic App > **Logic app designer**. Add
   trigger **Service Bus > When a message is received in a queue (peek-lock)**:
   - Connection string: `$SB_CONN` from step B1 (or a scoped `Listen`-only SAS policy).
   - **Queue name**: `order-items-reservation`; **Queue type**: **Dead-letter queue**.
   - **Interval**: `1`, **Frequency**: `Minute`.
3. Add action **Parse JSON** on `@{triggerBody()?['ContentData']}` with schema:
   ```json
   {
     "type": "object",
     "properties": {
       "id": { "type": "string" },
       "email": { "type": "string" },
       "complete": { "type": "boolean" },
       "products": {
         "type": "array",
         "items": {
           "type": "object",
           "properties": {
             "id": { "type": "integer" },
             "name": { "type": "string" },
             "quantity": { "type": "integer" }
           }
         }
       }
     }
   }
   ```
   The dead-letter reason/description are trigger properties:
   `@{triggerBody()?['DeadLetterReason']}` / `@{triggerBody()?['DeadLetterErrorDescription']}`.
4. Add action **Outlook.com** or **Office 365 Outlook > Send an email (V2)**
   (sign in to create the connection):
   - **To**: the manager's email address.
   - **Subject**: `Order reservation failed - session @{body('Parse_JSON')?['id']}`
   - **Body**: include `@{body('Parse_JSON')?['id']/['email']/['complete']/['products']}`
     and `@{triggerBody()?['DeadLetterReason']/['DeadLetterErrorDescription']}`.
5. Add action **Service Bus > Complete the message in a queue** (same
   connection; **Queue type**: **Dead-letter queue**; **Lock token**:
   `@{triggerBody()?['LockToken']}`) so it doesn't stay locked/reprocessed.
6. Save, then test: manually dead-letter a message via the Portal's Service
   Bus Explorer (Queue > Service Bus Explorer > peek a message > Dead-letter),
   or temporarily set `max-delivery-count` to `1` and send a message that will
   fail. Confirm the manager's inbox receives the email and the message is
   gone from the dead-letter queue afterward.

## Troubleshooting (Part B)

**ImageNotFoundFailure pulling from docker.io**: if logs show the container
pulling `docker.io/library/petstoreorderitemsreserver:latest` instead of your
ACR image, the `--image` wasn't fully qualified with the ACR login server:
```powershell
az functionapp config container set `
  --name $FUNC_NAME --resource-group $RG `
  --image "$ACR_LOGIN_SERVER/petstoreorderitemsreserver:latest" `
  --registry-server "https://$ACR_LOGIN_SERVER" `
  --registry-username $ACR_USERNAME `
  --registry-password $ACR_PASSWORD

az webapp restart --name $FUNC_NAME --resource-group $RG
```

**"0 functions found (Custom)" / no functions listed**: the Functions host is
treating the app as a Custom Handler instead of Java. `FUNCTIONS_WORKER_RUNTIME`
isn't baked into the image, so it must be set explicitly:
```powershell
az functionapp config appsettings set `
  --name $FUNC_NAME --resource-group $RG `
  --settings "FUNCTIONS_WORKER_RUNTIME=java"

az webapp restart --name $FUNC_NAME --resource-group $RG
```
Re-check with `az functionapp function list --name $FUNC_NAME --resource-group $RG -o table` - you should see `reserveOrderItems`.

**Container keeps restarting / "webjobs.storage" health check unhealthy**:
usually CPU/memory starvation on a small plan (JVM cold start competes with
the platform's health check pings), not a real storage connectivity issue
(rule that out via Storage Account > Networking > Public network access).
Scale up the plan:
```powershell
az appservice plan update --name $FUNC_PLAN_NAME --resource-group $RG --sku B2   # or B3 / Elastic Premium EP1
az webapp restart --name $FUNC_NAME --resource-group $RG
```

## Redeploying after code changes (Part B)

```powershell
az acr build --registry $ACR_NAME --image "petstoreorderitemsreserver:v1.0.1" .
az functionapp config container set `
  --name $FUNC_NAME --resource-group $RG `
  --image "$ACR_LOGIN_SERVER/petstoreorderitemsreserver:v1.0.1"
```

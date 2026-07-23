# PetStore Azure Deployment Steps (All Services)

Manual, copy-pasteable Azure CLI steps to containerize and deploy `petstorepetservice`, `petstoreproductservice`, `petstoreorderservice`, and `petstoreapp` to Azure App Service (Linux containers). Pet and Product services are backed by Azure Database for PostgreSQL. Order service is backed by Azure Cosmos DB.

> `petstoreorderitemsreserver` is a separate Azure Functions app and is **not** covered by this guide.

> Run these in PowerShell. Requires Azure CLI (`az`) and Docker installed, and `az login` completed.

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

## 9. Store PostgreSQL credentials in Azure Key Vault

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

> **Windows/PowerShell gotcha:** `az` on Windows is a `.cmd` wrapper that shells out through `cmd.exe`. Even when a `KEY="@Microsoft.KeyVault(...)"` value is quoted in PowerShell, the wrapper can drop the quotes before invoking Python, and `cmd.exe` then treats the bare `(`/`)` as its own grouping syntax - failing with `'PGPORT' was unexpected at this time.` (a `cmd.exe` parser error, not a PowerShell one). Passing settings from a JSON file avoids this entirely, since only the filename ever reaches the command line.

```powershell
# Pet Service - PostgreSQL connection via Key Vault references
@"
[
  { "name": "PGHOST", "value": "@Microsoft.KeyVault(SecretUri=$PG_HOST_SECRET_URI)" },
  { "name": "PGPORT", "value": "5432" },
  { "name": "PGDATABASE", "value": "petstorepetservice_db" },
  { "name": "PGUSER", "value": "@Microsoft.KeyVault(SecretUri=$PG_USER_SECRET_URI)" },
  { "name": "PGPASSWORD", "value": "@Microsoft.KeyVault(SecretUri=$PG_PASSWORD_SECRET_URI)" }
]
"@ | Set-Content -Path petservice-settings.json -Encoding utf8

az webapp config appsettings set `
    --resource-group $RG --name petstore-petservice `
    --settings "@petservice-settings.json"

# Product Service - PostgreSQL connection via Key Vault references
@"
[
  { "name": "PGHOST", "value": "@Microsoft.KeyVault(SecretUri=$PG_HOST_SECRET_URI)" },
  { "name": "PGPORT", "value": "5432" },
  { "name": "PGDATABASE", "value": "petstoreproductservice_db" },
  { "name": "PGUSER", "value": "@Microsoft.KeyVault(SecretUri=$PG_USER_SECRET_URI)" },
  { "name": "PGPASSWORD", "value": "@Microsoft.KeyVault(SecretUri=$PG_PASSWORD_SECRET_URI)" }
]
"@ | Set-Content -Path productservice-settings.json -Encoding utf8

az webapp config appsettings set `
    --resource-group $RG --name petstore-productservice `
    --settings "@productservice-settings.json"

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

> Verify resolution after restarting (step 11) with:
> ```powershell
> az webapp config appsettings list --resource-group $RG --name petstore-petservice --query "[?name=='PGHOST']"
> ```
> or check the "Resolved"/error status in the Portal's App Service > Environment variables blade.

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

## Redeploying after code changes

Repeat step 2 (build + push new image tag or `:latest`) for the service you changed, then restart the corresponding Web App:

```powershell
docker build -t "$ACR_LOGIN_SERVER/petstorepetservice:latest" ../petstorepetservice
docker push "$ACR_LOGIN_SERVER/petstorepetservice:latest"
az webapp restart --resource-group $RG --name petstore-petservice
```

# Deploying OrderItemsReserver to Azure

Azure CLI commands to provision the resources for the `petstoreorderitemsreserver` container Function App. Run from a PowerShell terminal with Azure CLI logged in (`az login`) and pointed at the right subscription (`az account set --subscription <id>`).

## 0. Variables

```powershell
$RG            = "petstore_rg"
$LOCATION      = "westeurope"
$ACR_NAME      = "petstoreacr1234"
$STORAGE_NAME  = "petstoreitemsreserve01"          # 3-24 lowercase alphanumeric, globally unique
$PLAN_NAME     = "asp-petstorefunction-westeurope"
$FUNC_NAME     = "demo-petstoreorderitemsreserver-westeurope-02"
$IMAGE_TAG     = "v1.0.0"
$IMAGE_NAME    = "petstoreorderitemsreserver:$IMAGE_TAG"
$SB_NS         = "petstoreservicebus1234"          # must match infra/SERVICE_BUS_DEPLOYMENT.md's $SB_NS
```

## 1. Storage account (AzureWebJobsStorage runtime + reservation blobs)

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

## 2. Build and push the container image to your existing ACR

Run from the `petstoreorderitemsreserver` directory (uses the `Dockerfile` already in this module).

```powershell
az acr build --registry $ACR_NAME --image $IMAGE_NAME .
```

## 3. Linux App Service Plan for the container Function App

B1 (Basic) is sufficient for a Linux custom-container Function App; bump to a Premium plan (EP1) later if you need faster cold starts or VNet integration.

```powershell
az appservice plan create `
  --name $PLAN_NAME `
  --resource-group $RG `
  --location $LOCATION `
  --sku B1 `
  --is-linux
```

## 4. Create the Function App from the container image

```powershell
$ACR_LOGIN_SERVER = az acr show --name $ACR_NAME --query loginServer -o tsv
$ACR_USERNAME     = az acr credential show --name $ACR_NAME --query username -o tsv
$ACR_PASSWORD     = az acr credential show --name $ACR_NAME --query "passwords[0].value" -o tsv

az functionapp create `
  --name $FUNC_NAME `
  --resource-group $RG `
  --plan $PLAN_NAME `
  --storage-account $STORAGE_NAME `
  --functions-version 4 `
  --image "$ACR_LOGIN_SERVER/$IMAGE_NAME" `
  --registry-username $ACR_USERNAME `
  --registry-password $ACR_PASSWORD
```

> If ACR admin user is disabled, enable it first: `az acr update --name $ACR_NAME --admin-enabled true` — or switch to a managed identity + `az functionapp identity assign` + `az role assignment create` (AcrPull) instead of admin credentials.

## 5. App settings (Blob Storage + Service Bus config, disable default content share for containers)

```powershell
$SB_CONN = az servicebus namespace authorization-rule keys list `
  --resource-group $RG `
  --namespace-name $SB_NS `
  --name RootManageSharedAccessKey `
  --query primaryConnectionString -o tsv

az functionapp config appsettings set `
  --name $FUNC_NAME `
  --resource-group $RG `
  --settings `
    "BLOB_STORAGE_CONNECTION_STRING=$STORAGE_CONN" `
    "BLOB_STORAGE_CONTAINER_NAME=orderitemsreserver" `
    "SERVICEBUS_CONNECTION=$SB_CONN" `
    "SERVICEBUS_QUEUE_NAME=order-items-reservation" `
    "WEBSITES_ENABLE_APP_SERVICE_STORAGE=false" `
    "FUNCTIONS_WORKER_RUNTIME=java"
```

> Requires the Service Bus namespace + queue to already exist - run `infra/SERVICE_BUS_DEPLOYMENT.md` steps 1-2 first (with `$SB_NS` matching the value set above).

## 6. Point PetStoreOrderService at the Service Bus queue

The Function no longer has an HTTP endpoint - PetStoreOrderService publishes to the Service Bus queue instead of calling this Function directly. Configure the already-deployed `petstore-orderservice` App Service (see `infra/SERVICE_BUS_DEPLOYMENT.md` step 4):

```powershell
az webapp config appsettings set `
  --name petstore-orderservice `
  --resource-group $RG `
  --settings "SERVICEBUS_CONNECTION_STRING=$SB_CONN" "SERVICEBUS_QUEUE_NAME=order-items-reservation"

az webapp restart --name petstore-orderservice --resource-group $RG
```

## 7. Verify

```powershell
az functionapp show --name $FUNC_NAME --resource-group $RG --query state -o tsv   # should be "Running"

# Send a test message directly to the queue instead of an HTTP call:
az servicebus queue message send `
  --resource-group $RG `
  --namespace-name $SB_NS `
  --queue-name order-items-reservation `
  --body '{"id":"test-session-1","email":"test@example.com","complete":false,"products":[{"id":1,"quantity":2}]}'
```
Then check the `orderitemsreserver` container in `$STORAGE_NAME` for `order-test-session-1.json`, and `az functionapp function list --name $FUNC_NAME --resource-group $RG -o table` / `az webapp log tail` to confirm the `reserveOrderItems` Service Bus trigger fired.

> `az servicebus queue message send` requires a recent Azure CLI with the `servicebus` extension (`az extension add --name servicebus`). Alternatively, trigger it end-to-end by updating the cart from the PetStoreApp UI.

## Troubleshooting: ImageNotFoundFailure pulling from docker.io

If `az functionapp show`/`az webapp log tail` shows the container trying to pull
`docker.io/library/petstoreorderitemsreserver:latest` (Docker Hub) instead of your
ACR image, the `--image` value passed to `functionapp create`/`config container set`
wasn't fully qualified with the ACR login server. Fix by re-pointing the container
at the correct, fully-qualified image reference with registry credentials:

```powershell
$ACR_LOGIN_SERVER = az acr show --name $ACR_NAME --query loginServer -o tsv
$ACR_USERNAME     = az acr credential show --name $ACR_NAME --query username -o tsv
$ACR_PASSWORD     = az acr credential show --name $ACR_NAME --query "passwords[0].value" -o tsv

az functionapp config container set `
  --name $FUNC_NAME `
  --resource-group $RG `
  --image "$ACR_LOGIN_SERVER/petstoreorderitemsreserver:latest" `
  --registry-server "https://$ACR_LOGIN_SERVER" `
  --registry-username $ACR_USERNAME `
  --registry-password $ACR_PASSWORD

az webapp restart --name $FUNC_NAME --resource-group $RG
```

Verify the fix:
```powershell
az functionapp config container show --name $FUNC_NAME --resource-group $RG
az webapp log tail --name $FUNC_NAME --resource-group $RG
```
You should see `Pulling image: <ACR_LOGIN_SERVER>/petstoreorderitemsreserver:latest` followed by `Application started. ... Now listening on: http://[::]:80`.

## Troubleshooting: "0 functions found (Custom)" / no functions listed

If `az functionapp function list` returns nothing and the container logs show
`Reading functions metadata (Custom)` followed by `0 functions found (Custom)`,
the Functions host is treating the app as a **Custom Handler** app instead of
a Java app. This happens when the `FUNCTIONS_WORKER_RUNTIME` app setting isn't
set to `java` on the Function App resource (it is not set automatically by
`az functionapp create --image ...`, and it is not baked into the container
image itself). Fix:

```powershell
az functionapp config appsettings set `
  --name $FUNC_NAME `
  --resource-group $RG `
  --settings "FUNCTIONS_WORKER_RUNTIME=java"

az webapp restart --name $FUNC_NAME --resource-group $RG
```

Then re-check:
```powershell
az functionapp function list --name $FUNC_NAME --resource-group $RG -o table
```
You should now see `reserveOrderItems` listed.

## Troubleshooting: Container keeps restarting / "webjobs.storage" health check unhealthy

If the container logs show `Host started`, `Job host started`, and
`Site startup probe succeeded`, but then shortly after a health check log line like:

```
Health check entries are {"azure.functions.web_host.lifecycle":{"status":"Healthy"},
"azure.functions.script_host.lifecycle":{"status":"Healthy"},
"azure.functions.webjobs.storage":{"status":"Unhealthy","description":"A timeout occurred while running check."}}
```

...followed by the container being stopped/recreated in a loop, this is usually
**not** a real storage connectivity problem (check Storage Account > Networking >
Public network access is `Enable` / `Enable from all networks` to rule that out).
It's more commonly **CPU/memory starvation** on a small plan: the JVM cold start
(which can take 50+ seconds for Azure Functions Java worker + extension bundle)
competes with the platform's own health check pings for CPU, causing the storage
health check to time out even though nothing is wrong with the connection string.

Fix: scale up the App Service Plan.

```powershell
az appservice plan update `
  --name $PLAN_NAME `
  --resource-group $RG `
  --sku B2

az webapp restart --name $FUNC_NAME --resource-group $RG
```

If B2 (2 vCPU/3.5GB) still isn't enough, try B3 (4 vCPU/7GB), or switch to an
Elastic Premium plan (EP1) which is purpose-built for Azure Functions with
pre-warmed instances.

## Re-deploying after code changes

```powershell
az acr build --registry $ACR_NAME --image "petstoreorderitemsreserver:v1.0.1" .
az functionapp config container set `
  --name $FUNC_NAME --resource-group $RG `
  --image "$ACR_LOGIN_SERVER/petstoreorderitemsreserver:v1.0.1"
```
